// MicroBoy link server: matchmaking (Lobby) and relaying (Room) for the
// online link cable. The phones do the emulation; this only introduces them
// and forwards their messages.
//
//   GET /lobby          WebSocket. Who's online, invites, blocks and reports.
//   GET /room/<id>?p=1  WebSocket. Relays everything between the two players
//                       of a match (save states, and buttons when a direct
//                       peer-to-peer connection can't be made).
import { DurableObject } from "cloudflare:workers";

// Phones only see players with the same link version: both phones must run
// the exact same emulator to stay in sync.
const NICK_MAX = 16;
const LIST_MAX = 200;
const REPORTS_TO_HIDE = 3;
const REPORT_WINDOW_MS = 7 * 24 * 3600 * 1000;

// A short, deliberately conservative list. Nicknames are also limited to
// letters, digits, spaces and . _ - so they can't carry links or contact info.
const BLOCKED_WORDS = [
  "fuck", "shit", "cunt", "nigg", "fag", "rape", "nazi", "hitler", "whore", "slut",
  "retard", "kike", "spic", "chink", "porn", "sex", "cock", "dick", "pussy", "penis",
];

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/" || url.pathname === "/health") {
      return new Response("MicroBoy link server\n", { headers: { "content-type": "text/plain" } });
    }
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("Expected a WebSocket", { status: 426 });
    }
    if (url.pathname === "/lobby") {
      return env.LOBBY.get(env.LOBBY.idFromName("global")).fetch(request);
    }
    const room = url.pathname.match(/^\/room\/([0-9a-f]{32})$/);
    if (room) {
      return env.ROOM.get(env.ROOM.idFromName(room[1])).fetch(request);
    }
    return new Response("Not found", { status: 404 });
  },
};

// --- helpers -------------------------------------------------------------------------

function cleanNick(raw) {
  if (typeof raw !== "string") return null;
  const nick = raw.normalize("NFKC").replace(/[^\p{L}\p{N} ._-]/gu, "").replace(/\s+/g, " ").trim().slice(0, NICK_MAX);
  if (nick.length < 2) return null;
  const folded = nick.toLowerCase().replace(/[^a-z]/g, "").replace(/0/g, "o");
  if (BLOCKED_WORDS.some((w) => folded.includes(w))) return null;
  return nick;
}

function cleanGame(raw) {
  if (!raw || typeof raw !== "object") return null;
  const title = typeof raw.title === "string" ? raw.title.replace(/[\u0000-\u001f]/g, "").slice(0, 48) : "";
  const code = typeof raw.code === "string" && /^[A-Z0-9]{4}$/.test(raw.code) ? raw.code : "";
  return title ? { title, code } : null;
}

function send(ws, msg) {
  try {
    ws.send(JSON.stringify(msg));
  } catch {
    // The socket closed in the meantime; its close handler cleans up.
  }
}

async function playerId(install, salt) {
  const data = new TextEncoder().encode(`${salt}:${install}`);
  const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", data));
  return [...hash.slice(0, 6)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function randomHex(bytes) {
  return [...crypto.getRandomValues(new Uint8Array(bytes))].map((b) => b.toString(16).padStart(2, "0")).join("");
}

// --- Lobby -----------------------------------------------------------------------------
// Per-connection state lives in the WebSocket attachment so it survives
// hibernation: { id, nick, link, share, game, visible, blocked: [ids] }.

export class Lobby extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.ctx.blockConcurrencyWhile(async () => {
      this.ctx.storage.sql.exec(
        "CREATE TABLE IF NOT EXISTS reports (target TEXT, reporter TEXT, nick TEXT, reason TEXT, at INTEGER, PRIMARY KEY (target, reporter))"
      );
    });
  }

  async fetch() {
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({});
    return new Response(null, { status: 101, webSocket: client });
  }

  players() {
    const out = [];
    for (const ws of this.ctx.getWebSockets()) {
      const a = ws.deserializeAttachment();
      if (a && a.id) out.push({ ws, a });
    }
    return out;
  }

  find(id) {
    return this.players().find((p) => p.a.id === id);
  }

  // Players reported by enough different people are hidden for a while.
  hiddenIds() {
    const since = Date.now() - REPORT_WINDOW_MS;
    const rows = this.ctx.storage.sql.exec(
      "SELECT target FROM reports WHERE at > ? GROUP BY target HAVING COUNT(*) >= ?", since, REPORTS_TO_HIDE
    ).toArray();
    return new Set(rows.map((r) => r.target));
  }

  canSee(a, b, hidden = this.hiddenIds()) {
    return a.id !== b.id && b.visible && a.link === b.link &&
      !a.blocked.includes(b.id) && !b.blocked.includes(a.id) && !hidden.has(b.id);
  }

  broadcast() {
    const all = this.players();
    const hidden = this.hiddenIds();
    for (const me of all) {
      const games = [];
      const open = [];
      const seen = new Set();
      for (const other of all) {
        if (seen.has(other.a.id) || !this.canSee(me.a, other.a, hidden)) continue;
        seen.add(other.a.id);
        if (other.a.share && other.a.game) {
          if (games.length < LIST_MAX) games.push({ id: other.a.id, nick: other.a.nick, game: other.a.game });
        } else if (open.length < LIST_MAX) {
          open.push({ id: other.a.id, nick: other.a.nick });
        }
      }
      send(me.ws, { t: "lists", games, open });
    }
  }

  async webSocketMessage(ws, raw) {
    if (typeof raw !== "string" || raw.length > 4096) return;
    let m;
    try {
      m = JSON.parse(raw);
    } catch {
      return;
    }
    const a = ws.deserializeAttachment() || {};

    if (m.t === "hello") {
      const nick = cleanNick(m.nick);
      if (!nick) return send(ws, { t: "error", code: "nick", message: "Choose a different nickname." });
      if (typeof m.install !== "string" || m.install.length < 16 || typeof m.link !== "string") {
        return send(ws, { t: "error", code: "hello", message: "Update MicroBoy to use online link." });
      }
      const id = await playerId(m.install, this.env.PLAYER_SALT || "microboy");
      if (this.hiddenIds().has(id)) {
        return send(ws, { t: "error", code: "hidden", message: "Online link is unavailable for this device for now." });
      }
      // One connection per device: forget the old one right away, then close it.
      for (const p of this.players()) {
        if (p.a.id === id && p.ws !== ws) {
          p.ws.serializeAttachment({});
          p.ws.close(4000, "replaced");
        }
      }
      const blocked = Array.isArray(m.blocked) ? m.blocked.filter((b) => typeof b === "string").slice(0, 500) : [];
      ws.serializeAttachment({
        id, nick, link: m.link.slice(0, 32), share: !!m.share, game: cleanGame(m.game), visible: m.visible !== false, blocked,
      });
      send(ws, { t: "welcome", id, nick });
      return this.broadcast();
    }
    if (!a.id) return;

    switch (m.t) {
      case "update": {
        if ("share" in m) a.share = !!m.share;
        if ("game" in m) a.game = cleanGame(m.game);
        if ("visible" in m) a.visible = !!m.visible;
        if ("nick" in m) {
          const nick = cleanNick(m.nick);
          if (!nick) return send(ws, { t: "error", code: "nick", message: "Choose a different nickname." });
          a.nick = nick;
        }
        ws.serializeAttachment(a);
        return this.broadcast();
      }
      case "invite": {
        const target = this.find(m.to);
        if (!target || !this.canSee(a, target.a) || !this.canSee(target.a, a)) {
          return send(ws, { t: "unavailable", id: m.to });
        }
        return send(target.ws, { t: "invite", from: a.id, nick: a.nick, game: a.share ? a.game : null });
      }
      case "cancel": {
        const target = this.find(m.to);
        if (target) send(target.ws, { t: "cancelled", from: a.id });
        return;
      }
      case "reply": {
        const inviter = this.find(m.to);
        if (!inviter) return send(ws, { t: "unavailable", id: m.to });
        if (!m.accept) return send(inviter.ws, { t: "declined", from: a.id });
        // The inviter is player 1 (the link cable's parent side).
        const room = randomHex(16);
        send(inviter.ws, { t: "matched", room, player: 1, peer: { id: a.id, nick: a.nick } });
        send(ws, { t: "matched", room, player: 2, peer: { id: inviter.a.id, nick: inviter.a.nick } });
        return;
      }
      case "block": {
        if (typeof m.id === "string" && !a.blocked.includes(m.id)) {
          a.blocked.push(m.id);
          ws.serializeAttachment(a);
          this.broadcast();
        }
        return;
      }
      case "report": {
        const target = this.find(m.id);
        if (typeof m.id !== "string" || m.id === a.id) return;
        const reason = typeof m.reason === "string" ? m.reason.slice(0, 200) : "";
        this.ctx.storage.sql.exec(
          "INSERT OR REPLACE INTO reports (target, reporter, nick, reason, at) VALUES (?, ?, ?, ?, ?)",
          m.id, a.id, target ? target.a.nick : "", reason, Date.now()
        );
        if (!a.blocked.includes(m.id)) {
          a.blocked.push(m.id);
          ws.serializeAttachment(a);
        }
        send(ws, { t: "reported", id: m.id });
        return this.broadcast();
      }
    }
  }

  async webSocketClose(ws) {
    ws.serializeAttachment({});
    this.broadcast();
  }

  async webSocketError(ws) {
    ws.serializeAttachment({});
    this.broadcast();
  }
}

// --- Room ------------------------------------------------------------------------------
// Two sockets tagged "1" and "2". Everything one sends goes to the other
// unchanged; the room itself only says when the other player arrives or leaves.

export class Room extends DurableObject {
  async fetch(request) {
    const player = new URL(request.url).searchParams.get("p");
    if (player !== "1" && player !== "2") return new Response("Bad player", { status: 400 });
    for (const old of this.ctx.getWebSockets(player)) old.close(4000, "replaced");
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server, [player]);
    const other = this.other(player);
    if (other) {
      send(other, { t: "room", event: "joined" });
      send(server, { t: "room", event: "joined" });
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  // The newest open socket: after a reconnect the replaced one may linger.
  other(player) {
    const open = this.ctx.getWebSockets(player === "1" ? "2" : "1").filter((w) => w.readyState === 1);
    return open[open.length - 1];
  }

  async webSocketMessage(ws, message) {
    const [player] = this.ctx.getTags(ws);
    const other = this.other(player);
    if (!other) return;
    try {
      other.send(message);
    } catch {
      // Gone; the close handler tells the sender.
    }
  }

  async webSocketClose(ws) {
    const [player] = this.ctx.getTags(ws);
    // A reconnect replaces the old socket; that player hasn't left.
    if (this.ctx.getWebSockets(player).some((w) => w !== ws)) return;
    const other = this.other(player);
    if (other) send(other, { t: "room", event: "left" });
  }
}
