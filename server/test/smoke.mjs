// Smoke test for the link server. Start `npx wrangler dev` first, then:
//   node test/smoke.mjs [ws://127.0.0.1:8787]
const BASE = process.argv[2] || "ws://127.0.0.1:8787";
const LINK = "test-1";
let failures = 0;

function check(ok, what) {
  console.log(`${ok ? "PASS" : "FAIL"}  ${what}`);
  if (!ok) failures++;
}

// A WebSocket with a queue of received messages and await-able expectations.
function client(path) {
  const ws = new WebSocket(BASE + path);
  ws.binaryType = "arraybuffer";
  const queue = [];
  const waiters = [];
  ws.addEventListener("message", (e) => {
    const msg = typeof e.data === "string" ? JSON.parse(e.data) : new Uint8Array(e.data);
    const i = waiters.findIndex((w) => w.match(msg));
    if (i >= 0) waiters.splice(i, 1)[0].resolve(msg);
    else queue.push(msg);
  });
  return {
    ws,
    open: new Promise((r, j) => { ws.addEventListener("open", r); ws.addEventListener("error", j); }),
    send: (m) => ws.send(typeof m === "object" && !(m instanceof Uint8Array) ? JSON.stringify(m) : m),
    next(match, ms = 3000) {
      const i = queue.findIndex(match);
      if (i >= 0) return Promise.resolve(queue.splice(i, 1)[0]);
      return new Promise((resolve, reject) => {
        const w = { match, resolve };
        waiters.push(w);
        setTimeout(() => {
          const k = waiters.indexOf(w);
          if (k >= 0) { waiters.splice(k, 1); reject(new Error("timeout")); }
        }, ms);
      });
    },
    close: () => ws.close(),
  };
}

const t = (type) => (m) => m.t === type;
const latestLists = async (c, pred) => {
  for (;;) {
    const m = await c.next(t("lists"));
    if (pred(m)) return m;
  }
};

const a = client("/lobby");
const b = client("/lobby");
await Promise.all([a.open, b.open]);

a.send({ t: "hello", nick: "Ash", install: "install-aaaaaaaaaaaaaaaa", link: LINK, share: true, game: { title: "POKEMON EMER", code: "BPEE" } });
const wa = await a.next(t("welcome"));
b.send({ t: "hello", nick: "Misty", install: "install-bbbbbbbbbbbbbbbb", link: LINK, share: false, game: { title: "POKEMON FIRE", code: "BPRE" } });
const wb = await b.next(t("welcome"));
check(wa.id && wb.id && wa.id !== wb.id, "both players get distinct ids");

const la = await latestLists(a, (m) => m.open.length + m.games.length > 0);
check(la.open.some((p) => p.id === wb.id && !p.game), "Ash sees Misty in the nickname-only list (no game shown)");
const lb = await latestLists(b, (m) => m.games.length > 0);
check(lb.games.some((p) => p.id === wa.id && p.game.title === "POKEMON EMER"), "Misty sees Ash with his game");

const bad = client("/lobby");
await bad.open;
bad.send({ t: "hello", nick: "fuckface", install: "install-cccccccccccccccc", link: LINK });
check((await bad.next(t("error"))).code === "nick", "offensive nickname rejected");
bad.close();

a.send({ t: "invite", to: wb.id });
const inv = await b.next(t("invite"));
check(inv.from === wa.id && inv.game && inv.game.code === "BPEE", "Misty receives Ash's invite with his game");
b.send({ t: "reply", to: wa.id, accept: true });
const ma = await a.next(t("matched"));
const mb = await b.next(t("matched"));
check(ma.room === mb.room && ma.player === 1 && mb.player === 2, "match: same room, inviter is player 1");

const ra = client(`/room/${ma.room}?p=1`);
const rb = client(`/room/${mb.room}?p=2`);
await Promise.all([ra.open, rb.open]);
await ra.next((m) => m.t === "room" && m.event === "joined");
await rb.next((m) => m.t === "room" && m.event === "joined");
check(true, "both players joined the room");

const big = new Uint8Array(200_000).map((_, i) => (i * 7) & 0xff);
ra.send(big);
const got = await rb.next((m) => m instanceof Uint8Array);
check(got.length === big.length && got.every((v, i) => v === big[i]), "200 KB binary relayed intact");
rb.send({ t: "hello", nick: "Misty" });
check((await ra.next(t("hello"))).nick === "Misty", "JSON relayed back");

ra.close();
check((await rb.next((m) => m.t === "room" && m.event === "left")).event === "left", "room reports the other player leaving");
rb.close();

b.send({ t: "block", id: wa.id });
const after = await latestLists(a, (m) => !m.open.some((p) => p.id === wb.id));
check(!after.open.some((p) => p.id === wb.id) && !after.games.some((p) => p.id === wb.id), "after Misty blocks Ash, Ash no longer sees her");
a.send({ t: "invite", to: wb.id });
check((await a.next(t("unavailable"))).id === wb.id, "Ash can't invite Misty after the block");

a.close();
b.close();
console.log(failures ? `${failures} FAILED` : "all passed");
process.exit(failures ? 1 : 0);
