// MicroBoy site: Google Play status, theme swatches, the animated demo
// screen, and copy buttons.

// Flip `live` to true once the store listing is published.
const PLAY = {
  live: false,
  price: "$0.50",
  url: "https://play.google.com/store/apps/details?id=rom.Micro.Boy",
};

// Same seeds as app/src/main/java/dev/m4s4t0/microboy/ui/theme/Palettes.kt.
const THEMES = [
  ["midnight", "Midnight", "#B39DFF", "#FF8A80", "#14121F"],
  ["glacier", "Glacier", "#7FD8FF", "#B8F2E6", "#0E1A24"],
  ["arcade", "Arcade", "#FF4FD8", "#3CF0FF", "#07060C"],
  ["ember", "Ember", "#FF9248", "#FFD166", "#1C110D"],
  ["lime", "Lime Soda", "#C6F432", "#4FE3A1", "#111A0E"],
  ["ocean", "Deep Sea", "#4FC3F7", "#7C83FF", "#0A1428"],
  ["pocket", "Pocket LCD", "#9BBC0F", "#8BAC0F", "#0F380F"],
  ["oled", "OLED Black", "#E0E0E0", "#B39DFF", "#000000"],
  ["sakura", "Sakura", "#D9467A", "#7B5EA7", "#FFF4F7"],
  ["paper", "Paper", "#2F4FA2", "#D9822B", "#F7F3EA"],
  ["stone", "Stone", "#6A4BC4", "#B0305C", "#E6E4E0"],
];

// --- color helpers -----------------------------------------------------------

const rgb = (hex) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
const hex = (c) => "#" + c.map((v) => Math.round(v).toString(16).padStart(2, "0")).join("");
const mix = (a, b, t) => hex(rgb(a).map((v, i) => v + (rgb(b)[i] - v) * t));
const lum = (h) => {
  const [r, g, b] = rgb(h).map((v) => {
    v /= 255;
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};

// --- Google Play state ---------------------------------------------------------

document.querySelectorAll("[data-price]").forEach((el) => (el.textContent = PLAY.price));
document.querySelectorAll("[data-play-status]").forEach((el) => (el.textContent = PLAY.live ? "available now" : "coming soon"));
document.querySelectorAll("[data-play-cta]").forEach((el) => (el.textContent = PLAY.live ? `Buy on Google Play — ${PLAY.price}` : "Coming soon to Google Play"));
if (PLAY.live) {
  document.querySelectorAll("[data-play-link]").forEach((a) => {
    a.href = PLAY.url;
    a.removeAttribute("aria-disabled");
  });
} else {
  document.querySelectorAll("[data-play-link]").forEach((a) =>
    a.addEventListener("click", (e) => {
      if (a.closest("#get")) e.preventDefault();
    })
  );
}

// --- themes --------------------------------------------------------------------

const root = document.documentElement;
let palette = null; // current [primary, accent, bg], read by the demo screen

function applyTheme(id) {
  const t = THEMES.find((x) => x[0] === id) || THEMES[0];
  const [, , primary, accent, bg] = t;
  const dark = lum(bg) < 0.4;
  const ink = dark ? mix("#FFFFFF", primary, 0.08) : mix("#15131A", primary, 0.1);
  root.classList.toggle("light", !dark);
  root.style.setProperty("--bg", bg);
  root.style.setProperty("--primary", primary);
  root.style.setProperty("--accent", accent);
  root.style.setProperty("--ink", ink);
  root.style.setProperty("--mint", dark ? "#7FE0C2" : "#1B8A68");
  root.style.setProperty("--on-primary", lum(mix(primary, accent, 0.5)) > 0.35 ? "#15121F" : "#FFFFFF");
  document.querySelector('meta[name="theme-color"]').content = bg;
  document.querySelectorAll(".swatch").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.theme === t[0])));
  palette = [primary, accent, bg];
  try { localStorage.setItem("microboy-theme", t[0]); } catch (_) {}
  drawFrame();
}

const list = document.getElementById("theme-list");
for (const [id, name, primary, accent, bg] of THEMES) {
  const b = document.createElement("button");
  b.type = "button";
  b.className = "swatch";
  b.dataset.theme = id;
  b.innerHTML = `<span class="dots" aria-hidden="true"><i style="background:${bg}"></i><i style="background:${primary}"></i><i style="background:${accent}"></i></span>${name}`;
  b.addEventListener("click", () => applyTheme(id));
  list.appendChild(b);
}

// --- demo screen ---------------------------------------------------------------
// A 240×160 pixel scene, the GBA's resolution, with a frame counter so the
// speed buttons visibly fast-forward it.

const canvas = document.getElementById("screen");
const ctx = canvas.getContext("2d");
const W = 240, H = 160;
let frame = 0;
let speed = 1;

// 3×5 bitmap font, just the glyphs the scene needs.
const FONT = {
  M: "101111111101101", I: "111010010010111", C: "111100100100111", R: "110101110101101",
  O: "111101101101111", B: "110101110101110", Y: "101101010010010", F: "111100110100100",
  A: "010101111101101", E: "111100110100111", X: "101101010101101", " ": "000000000000000",
  0: "111101101101111", 1: "010110010010111", 2: "111001111100111", 3: "111001111001111",
  4: "101101111001001", 5: "111100111001111", 6: "111100111101111", 7: "111001001001001",
  8: "111101111101111", 9: "111101111001111",
};
function text(str, x, y, scale, color) {
  ctx.fillStyle = color;
  for (const ch of String(str)) {
    const g = FONT[ch] || FONT[" "];
    for (let i = 0; i < 15; i++) if (g[i] === "1") ctx.fillRect(x + (i % 3) * scale, y + Math.floor(i / 3) * scale, scale, scale);
    x += 4 * scale;
  }
}

// Deterministic "noise" for the hills: a sum of sines.
const ridge = (x, seed, amp) =>
  amp * (Math.sin(x * 0.021 + seed) * 0.55 + Math.sin(x * 0.047 + seed * 2.3) * 0.3 + Math.sin(x * 0.11 + seed * 4.1) * 0.15);

const STARS = Array.from({ length: 40 }, (_, i) => [(i * 97) % W, (i * 53) % 70, i % 3]);

function drawFrame() {
  if (!palette) return;
  const [primary, accent, bg] = palette;
  const night = mix(bg, "#000000", lum(bg) < 0.4 ? 0.2 : 0.75);

  // Sky in four flat bands, like a real GBA background layer.
  for (let b = 0; b < 4; b++) {
    ctx.fillStyle = mix(night, primary, b * 0.09);
    ctx.fillRect(0, b * 22, W, 22);
  }
  ctx.fillStyle = mix(night, accent, 0.35);
  ctx.fillRect(0, 88, W, H - 88);

  const drift = Math.floor(frame * 0.05);
  for (const [sx, sy, s] of STARS) {
    if ((frame + sx) % 50 < 44 || s) { // star type 0 twinkles
      ctx.fillStyle = s === 2 ? accent : "#FFFFFF";
      ctx.fillRect((((sx - drift) % W) + W) % W, sy, 1, 1);
    }
  }

  // Sun, drawn as a pixel disc with stripes cut out.
  const sunY = 62;
  for (let dy = -18; dy <= 18; dy++) {
    const half = Math.floor(Math.sqrt(18 * 18 - dy * dy));
    if (dy > 2 && dy % 4 === 0) continue;
    ctx.fillStyle = mix(accent, primary, (dy + 18) / 50);
    ctx.fillRect(170 - half, sunY + dy, half * 2, 1);
  }

  // Three parallax ridges.
  const layers = [
    [0.25, 1.3, 26, 92, mix(night, primary, 0.45)],
    [0.6, 4.2, 22, 112, mix(night, primary, 0.25)],
    [1.2, 7.7, 14, 132, mix(night, accent, 0.18)],
  ];
  for (const [rate, seed, amp, base, color] of layers) {
    ctx.fillStyle = color;
    const off = frame * rate;
    for (let x = 0; x < W; x += 2) {
      const y = Math.round(base + ridge(x + off, seed, amp));
      ctx.fillRect(x, y, 2, H - y);
    }
  }

  // Ground stripes scrolling fastest.
  ctx.fillStyle = mix(night, "#000000", 0.35);
  ctx.fillRect(0, 146, W, 14);
  ctx.fillStyle = mix(night, primary, 0.35);
  for (let x = -((frame * 2) % 16); x < W; x += 16) ctx.fillRect(x, 150, 8, 2);

  // Title and frame counter.
  const bob = Math.round(Math.sin(frame / 18) * 2);
  text("MICROBOY", 72 + 2, 20 + bob + 2, 3, "rgba(0,0,0,.45)");
  text("MICROBOY", 72, 20 + bob, 3, "#FFFFFF");
  text(`FRAME ${String(frame % 100000).padStart(5, "0")}`, 6, 6, 1, mix("#FFFFFF", accent, 0.3));
  if (speed > 1) text(`${speed}X`, W - 14, 6, 1, accent);
}

const reduceMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;
let visible = true;
let last = 0, acc = 0;
const STEP = 1000 / 60;

function loop(t) {
  if (last) acc += Math.min(t - last, 100);
  last = t;
  let advanced = false;
  while (acc >= STEP) {
    frame += speed;
    acc -= STEP;
    advanced = true;
  }
  if (advanced) drawFrame();
  if (visible) requestAnimationFrame(loop);
}

function start() {
  last = 0;
  acc = 0;
  if (!reduceMotion) requestAnimationFrame(loop);
}

if ("IntersectionObserver" in window) {
  new IntersectionObserver(([e]) => {
    const was = visible;
    visible = e.isIntersecting;
    if (visible && !was) start();
  }).observe(canvas);
}

document.querySelectorAll("[data-speed]").forEach((b) =>
  b.addEventListener("click", () => {
    speed = Number(b.dataset.speed);
    document.querySelectorAll("[data-speed]").forEach((x) => x.setAttribute("aria-pressed", String(x === b)));
    if (reduceMotion) {
      frame += 60 * speed; // jump one second ahead per tap instead of animating
      drawFrame();
    }
  })
);

// --- copy buttons ----------------------------------------------------------------

const COPY_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/></svg>';
const DONE_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12l5 5 9-10"/></svg>';
document.querySelectorAll(".code").forEach((block) => {
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "copy";
  btn.setAttribute("aria-label", "Copy command");
  btn.innerHTML = COPY_ICON;
  btn.addEventListener("click", async () => {
    // Copy only the commands, not the "# →" output comments.
    const code = block.querySelector("code").cloneNode(true);
    code.querySelectorAll(".c").forEach((c) => c.remove());
    try {
      await navigator.clipboard.writeText(code.textContent.trim());
      btn.innerHTML = DONE_ICON;
      setTimeout(() => (btn.innerHTML = COPY_ICON), 1500);
    } catch (_) {}
  });
  block.appendChild(btn);
});

// --- boot ----------------------------------------------------------------------

let saved = null;
try { saved = localStorage.getItem("microboy-theme"); } catch (_) {}
applyTheme(saved || "midnight");
requestAnimationFrame(() => requestAnimationFrame(() => root.classList.add("fade")));
start();
