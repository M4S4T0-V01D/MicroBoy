"""The 20 Jukebox scenes: art, effects, particles, mascot and song for each.

Layers are listed front to back: [near, mid, far, sky]. Scroll speeds are in
1/256 pixel per frame. Effects: ("cycle", layer, first, count, frames),
("twinkle", layer, slot, 0, frames), ("flash", layer, 0, 0, 0).
"""
import math
import random

import sprites as S
from music import Song
from pixel import Layer, mix


def scene(name, layers, scroll, song, fx=(), particles=None, mascot=None):
    return {"name": name, "layers": layers, "scroll": scroll, "song": song, "fx": list(fx),
            "particles": particles, "mascot": mascot}


def parts(sprite, count, vx, vy, sway=0, anim=12):
    frames, legend = sprite
    return {"frames": frames, "legend": legend, "count": count, "vx": vx, "vy": vy, "sway": sway, "anim": anim}


def mascot(sprite, motion, x, y, vx=0.0, amp=4, anim=16, members=None):
    frames, legend, size = sprite
    return {"frames": frames, "legend": legend, "size": size, "motion": motion, "x": x, "y": y,
            "vx": vx, "amp": amp, "anim": anim, "members": members}


def rainbow_band(L, cx, cy, r, width, color):
    """Upper half of a ring: a rainbow stripe, tested pixel by pixel so the top stays solid."""
    for dy in range(-r - 1, 1):
        for dx in range(-r - 1, r + 2):
            d = math.sqrt(dx * dx + dy * dy)
            if r - width < d <= r:
                L.put(cx + dx, cy + dy, color)


def flowers(L, tops, seed, colors, density=0.06):
    r = random.Random(seed)
    for x in range(L.w):
        if r.random() < density:
            y = tops[x] + r.randrange(2, 10)
            L.put(x, y, r.choice(colors))


# ======================================================================================
# Cute
# ======================================================================================

def sakura_hill():
    sky = Layer(256)
    sky.gradient(0, 160, ["#ffb7d2", "#ffd6e6", "#fff1f6"])
    sky.disc(196, 38, 14, "#fff8e8")
    far = Layer()
    far.ridge(104, 12, "#e7a8c8", 1, freqs=(2, 3, 7))
    tops = far.ridge_tops(104, 12, 1, freqs=(2, 3, 7))
    far.snowcaps(tops, 3, "#f7d9e8")
    mid = Layer()
    mt = mid.ridge_tops(128, 6, 3, freqs=(1, 3, 6))
    mid.ridge(128, 6, "#9fd39a", 3, freqs=(1, 3, 6))
    for x in range(20, 512, 72):
        mid.tree_round(x, mt[x] + 2, 12, 11, "#7a4b3a", "#ff9ec7", "#ffd1e6")
    near = Layer()
    nt = near.ridge_tops(150, 3, 5, freqs=(4, 9, 17))
    near.ridge(150, 3, "#76c26f", 5, freqs=(4, 9, 17))
    flowers(near, nt, 7, ["#ffffff", "#ffe36b", "#ff8fbd"], 0.08)
    song = Song(key="F4", scale="major", prog_a=(0, 5, 3, 4), prog_b=(3, 4, 2, 5), tempo=10,
                rhythm="gentle", lead=(1, 11, 5), harm_style="arp8", harm=(0, 5, 2),
                bass_style="bounce", wave="sine", drums="soft", penta=True, seed=11)
    return scene("SAKURA HILL", [near, mid, far, sky], [192, 96, 32, 0], song,
                 particles=parts(S.PETAL, 18, -0.35, 0.35, sway=1),
                 mascot=mascot(S.BUNNY, "hop", 56, 128, amp=8, anim=10))


def candy_clouds():
    sky = Layer(256)
    sky.gradient(0, 160, ["#c4b0ff", "#ffcfe0", "#ffe9c4"])
    for i, col in enumerate(["#ff9aa2", "#ffc89a", "#fff1a1", "#b8f0b0", "#a8d8ff", "#cdb4ff"]):
        rainbow_band(sky, 128, 170, 104 - i * 5, 5, col)
    far = Layer()
    for i, x in enumerate(range(0, 512, 128)):
        far.cloud(x + 40, 52 + (i % 2) * 14, 16, "#ffffff", "#f1e4ff")
    mid = Layer()
    for i, x in enumerate(range(30, 512, 96)):
        mid.cloud(x, 96 + (i % 3) * 8, 13, "#fff5fb", "#ffd9ec")
    near = Layer()
    for x in range(0, 512, 48):
        near.cloud(x + 24, 150, 18, "#ffffff", "#f3e8ff")
    song = Song(key="C5", scale="lydian", prog_a=(0, 1, 5, 4), prog_b=(3, 1, 4, 0), tempo=10,
                rhythm="dreamy", lead=(2, 9, 7), harm_style="arp16", harm=(0, 4, 1),
                bass_style="long", wave="triangle", drums="none", seed=22, lead_range=(-3, 6))
    return scene("CANDY CLOUDS", [near, mid, far, sky], [160, 80, 32, 0], song,
                 particles=parts(S.SPARKLE, 12, -0.12, 0.12, sway=1, anim=20))


def bunny_meadow():
    sky = Layer(256)
    sky.gradient(0, 160, ["#6cc0ff", "#a8dcff", "#d8f1ff"])
    sky.disc(40, 30, 12, "#fff3a8")
    sky.cloud(150, 40, 12, "#ffffff", "#e6f4ff")
    sky.cloud(220, 62, 9, "#ffffff", "#e6f4ff")
    far = Layer()
    far.ridge(112, 10, "#8fd18a", 2, freqs=(1, 3, 5))
    mid = Layer()
    mt = mid.ridge_tops(130, 7, 4, freqs=(2, 3, 7))
    mid.ridge(130, 7, "#5fb85a", 4, freqs=(2, 3, 7))
    flowers(mid, mt, 3, ["#ff7aa8", "#fff36b", "#ffffff"], 0.1)
    near = Layer()
    near.ridge(150, 3, "#3f9a45", 6, freqs=(5, 11, 23))
    song = Song(key="G4", scale="major", prog_a=(0, 3, 4, 0), prog_b=(5, 3, 1, 4), tempo=8,
                rhythm="bouncy", lead=(2, 11, 3), harm_style="stab", harm=(1, 6, 1),
                bass_style="bounce", wave="triangle", drums="skip", penta=True, seed=33)
    return scene("BUNNY MEADOW", [near, mid, far, sky], [224, 112, 40, 0], song,
                 particles=parts(S.BUTTERFLY, 6, 0.3, -0.05, sway=3, anim=8),
                 mascot=mascot(S.BUNNY, "hop", 120, 126, amp=10, anim=10))


def starry_nap():
    sky = Layer(256)
    sky.gradient(0, 160, ["#0b1035", "#1c2463", "#2c3a85"])
    tw = sky.reserve(["#ffffff", "#5a64a8"])
    sky.stars(40, 4, ["#c8d0ff", "#8a94d8"], 0, 110)
    sky.stars(16, 9, [tw], 0, 110)
    sky.crescent(176, 48, 24, "#fff1b8", bg_shift=8)
    far = Layer()
    for i, x in enumerate(range(0, 512, 160)):
        far.cloud(x + 60, 92 + (i % 2) * 10, 14, "#3a4a9a", "#2e3c86")
    mid = Layer()
    mt = mid.ridge_tops(132, 8, 2, freqs=(1, 3, 5))
    mid.ridge(132, 8, "#1a1f4a", 2, freqs=(1, 3, 5))
    for x in range(30, 512, 90):
        mid.house(x, mt[x] + 4, 14, 10, "#232a5c", "#141838", "#ffd97a")
    song = Song(key="D4", scale="major", prog_a=(0, 3, 0, 4), prog_b=(5, 3, 1, 4), tempo=12,
                rhythm="dreamy", lead=(2, 10, 6), harm_style="arp8", harm=(0, 4, 2),
                bass_style="long", wave="sine", drums="none", seed=44)
    return scene("STARRY NAP", [None, mid, far, sky], [0, 64, 24, 0], song,
                 fx=[("twinkle", 3, tw, 0, 37)],
                 particles=parts(S.STARDUST, 6, -0.05, 0.12, sway=1),
                 mascot=mascot(S.CAT, "bob", 168, 18, amp=1, anim=48))


def ocean_bubbles():
    sky = Layer(256)
    sky.gradient(0, 160, ["#45d4ff", "#1f8ed8", "#0d4a9e"])
    for x0 in (30, 90, 170, 220):
        for y in range(0, 110):
            for dx in range(3):
                if (x0 + dx + y // 3) % 2 == 0:
                    sky.put(x0 + dx + y // 4, y, "#6fe0ff")
    far = Layer()
    far.ridge(132, 10, "#1b5fa8", 3, freqs=(3, 7, 13))
    mid = Layer()
    r = random.Random(5)
    for x in range(10, 512, 26):
        h = r.randrange(24, 52)
        for y in range(150 - h, 150):
            wob = int(2 * math.sin(y / 5 + x))
            mid.put(x + wob, y, "#2fbf71")
            mid.put(x + wob + 1, y, "#1f9a5a")
    for x in range(40, 512, 110):
        mid.disc(x, 146, 8, "#ff7f9e")
        mid.disc(x + 6, 142, 5, "#ffa8be")
    near = Layer()
    nt = near.ridge_tops(150, 3, 2, freqs=(2, 5, 9))
    near.ridge(150, 3, "#f2d48c", 2, freqs=(2, 5, 9))
    flowers(near, nt, 4, ["#ffb3c1", "#fff6e0"], 0.04)
    song = Song(key="A4", scale="major", prog_a=(0, 5, 3, 4), prog_b=(1, 4, 0, 5), tempo=10,
                rhythm="gentle", lead=(1, 10, 4), harm_style="arp16", harm=(0, 4, 1),
                bass_style="bounce", wave="triangle", drums="none", seed=55)
    return scene("OCEAN BUBBLES", [near, mid, far, sky], [128, 64, 24, 0], song,
                 particles=parts(S.BUBBLE, 14, 0.0, -0.5, sway=2),
                 mascot=mascot(S.FISH, "swim", 0, 70, vx=0.4, amp=6, anim=20))


def snow_village():
    sky = Layer(256)
    sky.gradient(0, 160, ["#2a2f6b", "#7a5ea8", "#f2a6b8"])
    sky.stars(18, 6, ["#e8e6ff"], 0, 50)
    far = Layer()
    tops = far.ridge_tops(108, 14, 4, freqs=(2, 5, 9), jag=True)
    far.ridge(108, 14, "#b9c3e8", 4, freqs=(2, 5, 9), jag=True)
    far.snowcaps(tops, 4, "#ffffff")
    mid = Layer()
    mid.ridge(138, 3, "#e6ecff", 8, freqs=(1, 3, 5))
    for x in range(16, 512, 64):
        mid.house(x, 140, 16, 12, "#8a5a7a", "#ffffff", "#ffd36b", "#5a3a4a")
        mid.tree_pine(x + 34, 140, 20, "#2f5a5a", snow="#ffffff")
    near = Layer()
    near.ridge(150, 2, "#f5f8ff", 3, freqs=(3, 7, 15))
    song = Song(key="Bb4", scale="major", prog_a=(0, 3, 4, 0), prog_b=(3, 4, 5, 4), tempo=10,
                rhythm="gentle", lead=(1, 11, 5), harm_style="arp8", harm=(0, 5, 2),
                bass_style="long", wave="sine", drums="lullaby", seed=66, lead_range=(-2, 7))
    return scene("SNOW VILLAGE", [near, mid, far, sky], [160, 80, 24, 0], song,
                 particles=parts(S.SNOW, 20, -0.1, 0.3, sway=2, anim=30))


def mushroom_grove():
    sky = Layer(256)
    sky.gradient(0, 160, ["#12352a", "#2a6442", "#4e8f50"])
    for x0 in (60, 150):
        for y in range(0, 120):
            for dx in range(-6 + y // 10, 6 + y // 10):
                if (x0 + dx + y) % 3 == 0:
                    sky.put(x0 + dx, y, "#6aa860")
    far = Layer()
    for x in range(0, 512, 40):
        far.tree_round(x, 128, 20, 14, "#173a28", "#1f4a32")
    mid = Layer()
    mid.ridge(140, 3, "#3c7a3a", 2, freqs=(2, 5, 9))
    r = random.Random(7)
    for x in range(30, 512, 70):
        h, cap = r.randrange(10, 18), r.randrange(9, 14)
        mid.rect(x - 2, 140 - h, 5, h, "#fff1d6")
        for dy in range(-cap // 2, 1):
            half = int(math.sqrt(max(0, cap * cap - (dy * 2) ** 2)))
            mid.hline(x - half, x + half + 1, 140 - h + dy, "#ff5a5a")
        for k in range(3):
            mid.put(x - cap // 2 + k * cap // 2, 140 - h - 3, "#ffffff")
            mid.put(x - cap // 2 + k * cap // 2 + 1, 140 - h - 3, "#ffffff")
    near = Layer()
    nt = near.ridge_tops(152, 3, 9, freqs=(5, 11, 23))
    near.ridge(152, 3, "#24572a", 9, freqs=(5, 11, 23))
    flowers(near, nt, 2, ["#ff8a8a", "#fff1d6"], 0.03)
    song = Song(key="G4", scale="mixolydian", prog_a=(0, 6, 3, 0), prog_b=(3, 6, 0, 4), tempo=9, swing=1,
                rhythm="bouncy", lead=(2, 10, 4), harm_style="stab", harm=(1, 6, 1),
                bass_style="walk", wave="triangle", drums="lofi", seed=77)
    return scene("MUSHROOM GROVE", [near, mid, far, sky], [176, 96, 32, 0], song,
                 particles=parts(S.FIREFLY, 10, 0.1, -0.05, sway=3, anim=24))


def strawberry_picnic():
    sky = Layer(256)
    sky.gradient(0, 160, ["#fff3c4", "#ffe2b0", "#ffd0a0"])
    sky.disc(200, 34, 16, "#ffe27a")
    far = Layer()
    far.ridge(116, 8, "#a8dc7a", 3, freqs=(1, 3, 5))
    mid = Layer()
    mid.ridge(134, 2, "#7cc560", 1, freqs=(1, 2, 3))
    for row, y in enumerate((124, 132, 140)):
        for x in range(row * 6, 512, 18):
            mid.disc(x, y, 5, "#3f9a3a")
            mid.put(x - 2, y - 1, "#ff3a4a")
            mid.put(x + 2, y + 1, "#ff3a4a")
            mid.put(x + 1, y - 2, "#ff3a4a")
    near = Layer()
    for x in range(0, 512, 8):
        for y in range(148, 160):
            near.put(x, y, "#ff5a6a" if ((x // 8) + (y - 148) // 6) % 2 == 0 else "#fff6f0")
        for dx in range(1, 8):
            for y in range(148, 160):
                near.put(x + dx, y, near.get(x, y))
    song = Song(key="C5", scale="major", prog_a=(0, 3, 4, 0), prog_b=(5, 1, 4, 4), tempo=8,
                rhythm="bouncy", lead=(2, 11, 3), harm_style="arp8", harm=(1, 6, 2),
                bass_style="bounce", wave="triangle", drums="skip", seed=88, lead_range=(-3, 6))
    return scene("STRAWBERRY PICNIC", [near, mid, far, sky], [128, 96, 32, 0], song,
                 particles=parts(S.HEART, 8, 0.0, -0.3, sway=2))


def rainbow_falls():
    sky = Layer(256)
    sky.gradient(0, 160, ["#8fdcff", "#c9f0ff", "#effcff"])
    for i, col in enumerate(["#ff9aa2", "#ffc89a", "#fff1a1", "#b8f0b0", "#a8d8ff"]):
        rainbow_band(sky, 128, 46, 38 - i * 3, 3, col)
    far = Layer(256)
    water = far.reserve(["#e8fbff", "#9fdcff", "#5ab8f0", "#c8f0ff"])
    for x0, x1 in ((0, 92), (164, 256)):
        far.rect(x0, 40, x1 - x0, 120, "#8a7aa8")
        for y in range(52, 160, 11):  # rock layers
            for x in range(x0, x1):
                far.put(x, y + int(2 * math.sin(x / 7 + y)), "#6e5f8e")
                far.put(x, y + 1 + int(2 * math.sin(x / 7 + y)), "#a092bc")
        edge = x1 - 1 if x0 == 0 else x0  # shaded face next to the falls
        for y in range(40, 160):
            for k in range(4):
                far.put(edge + (-k if x0 == 0 else k), y, "#6e5f8e")
        far.rect(x0, 38, x1 - x0, 5, "#5fb85a")
        for x in range(x0, x1, 3):
            far.put(x, 43 + (x % 2), "#4a9a46")
    for y in range(40, 160):
        for x in range(92, 164):
            far.put(x, y, water + ((y + (x - 92) // 6) % 4))
    mid = Layer()
    for x in range(0, 512, 56):
        mid.tree_round(x, 150, 10, 12, "#6a4a3a", "#4fae5a", "#7fd07a")
    near = Layer()
    nwater = near.reserve(["#e8fbff", "#9fdcff", "#5ab8f0", "#c8f0ff"])
    for y in range(148, 160):
        for x in range(512):
            near.put(x, y, nwater + ((x // 8 + y) % 4))
    song = Song(key="F4", scale="major", prog_a=(0, 4, 5, 3), prog_b=(3, 0, 4, 4), tempo=9,
                rhythm="dreamy", lead=(1, 10, 6), harm_style="arp16", harm=(0, 4, 1),
                bass_style="long", wave="sine", drums="soft", seed=99)
    return scene("RAINBOW FALLS", [near, mid, far, sky], [64, 48, 0, 0], song,
                 fx=[("cycle", 2, water, 4, 6), ("cycle", 0, nwater, 4, 10)],
                 particles=parts(S.SPARKLE, 8, 0.0, 0.2, sway=1, anim=16),
                 mascot=mascot(S.BIRD, "drift", 0, 30, vx=0.6, amp=3, anim=12))


def penguin_parade():
    sky = Layer(256)
    sky.gradient(0, 160, ["#a8dcff", "#d4efff", "#f0faff"])
    sky.disc(60, 30, 10, "#ffffff")
    far = Layer()
    tops = far.ridge_tops(110, 16, 6, freqs=(3, 7, 13), jag=True)
    far.ridge(110, 16, "#dff5ff", 6, freqs=(3, 7, 13), jag=True)
    for x, t in enumerate(tops):
        for y in range(t + 12, 160):
            far.put(x, y, "#c4e8f8")
    mid = Layer()
    mid.rect(0, 128, 512, 20, "#3aa0d8")
    for x in range(0, 512, 64):
        mid.rect(x + 10, 132, 22, 4, "#ffffff")
        mid.rect(x + 40, 140, 14, 3, "#e6f6ff")
    near = Layer()
    near.ridge(150, 2, "#ffffff", 2, freqs=(2, 5, 9))
    near.ridge(156, 1, "#d2ecff", 3, freqs=(3, 7, 11))
    song = Song(key="E4", scale="major", prog_a=(0, 3, 4, 0), prog_b=(1, 4, 0, 5), tempo=8,
                rhythm="bouncy", lead=(2, 11, 2), harm_style="stab", harm=(0, 6, 1),
                bass_style="bounce", wave="square", bass_vol=3, drums="march", seed=1010)
    return scene("PENGUIN PARADE", [near, mid, far, sky], [96, 64, 24, 0], song,
                 particles=parts(S.TINY_SNOW, 10, -0.05, 0.2, sway=1),
                 # A GNU float leads each group of Tux-style penguins (kind 1 = float).
                 mascot=mascot(S.PARADE, "waddle", 0, 121, vx=0.3, amp=1, anim=14,
                               members=[(0, 1), (36, 0), (56, 0), (76, 0), (124, 1), (160, 0), (180, 0), (200, 0)]))


# ======================================================================================
# Cool
# ======================================================================================

def neon_city():
    sky = Layer(256)
    sky.gradient(0, 160, ["#0a0418", "#2a0a4a", "#5a1a6a"])
    sky.stars(20, 3, ["#b9a8ff"], 0, 60)
    far = Layer()
    r = random.Random(1)
    x = 0
    while x < 512:
        w = r.choice((16, 24, 32))
        far.rect(x, r.choice((72, 80, 88, 96)), w, 160, "#1e0e3a")
        x += w
    mid = Layer()
    neon = mid.reserve(["#ff3ad8", "#3af0ff", "#ffe23a", "#ff6a9a"])
    x = 0
    k = 0
    while x < 512:
        w = r.choice((24, 32, 40))
        top = r.choice((96, 104, 112))
        mid.building(x, top, w, "#140a28", "#ffcf5a", "#2a1a44", seed=k)
        if k % 2 == 0:
            for sx in range(x + 2, x + w - 2):
                mid.put(sx, top + 2, neon + (sx // 8) % 4)
        x += w + 8
        k += 1
    near = Layer()
    nneon = near.reserve(["#ff3ad8", "#3af0ff", "#ffe23a", "#ff6a9a"])
    x = 0
    while x < 512:
        w = r.choice((32, 48))
        top = r.choice((132, 140))
        near.rect(x, top, w, 160, "#07030f")
        near.rect(x, top, w, 1, nneon + (x // 32) % 4)
        near.rect(x + w // 2, top - 8, 1, 8, "#07030f")
        x += w + 16
    song = Song(key="A4", scale="minor", prog_a=(0, 5, 2, 6), prog_b=(3, 4, 0, 0), tempo=7,
                rhythm="synth", lead=(2, 10, 0), harm_style="stab", harm=(0, 7, 1),
                bass_style="pump", wave="saw", drums="four", fill=True, seed=1111, lead_range=(-2, 7))
    return scene("NEON CITY", [near, mid, far, sky], [256, 128, 48, 0], song,
                 fx=[("cycle", 1, neon, 4, 10), ("cycle", 0, nneon, 4, 10)])


def outrun():
    sky = Layer(256)
    sky.gradient(0, 96, ["#1a0533", "#5a1063", "#ff5f8f", "#ffb36b"])
    sky.ring_disc(128, 80, 30, ["#ffe26a", "#ffb04a", "#ff7a6a", "#ff4a8a"])
    for y in range(84, 112, 5):
        sky.hline(90, 166, y, "#ff5f8f")
    sky.rect(0, 96, 256, 64, "#1a0026")
    far = Layer()
    far.ridge(96, 14, "#3a0f5a", 3, freqs=(2, 5, 11), jag=True)
    far.rect(0, 96, 512, 64, 0)
    grid = Layer(256)
    lines = grid.reserve(["#ff3ad8", "#7a1a8a", "#3a0a4a", "#2a0638"])
    grid.rect(0, 96, 256, 64, "#1a0026")
    for k in range(-9, 10):
        bottom = 128 + k * 30
        prev = None
        for y in range(97, 160):
            x = round(128 + (bottom - 128) * (y - 96) / 64)
            if prev is not None and abs(x - prev) > 1:
                lo, hi = sorted((prev, x))
                grid.hline(lo, hi + 1, y, "#ff3ad8")
            else:
                grid.put(x, y, "#ff3ad8")
            prev = x
    y, gap, i = 98.0, 2.0, 0
    while y < 160:
        grid.hline(0, 256, int(y), lines + i % 4)
        y += gap
        gap *= 1.35
        i += 1
    near = Layer()
    for x in range(0, 512, 128):
        for y in range(100, 160):
            lean = (160 - y) // 12
            near.rect(x + 6 + lean, y, 3, 1, "#120018")
        cx, cy = x + 12, 98
        for ang in (-2.9, -2.4, -1.9, -1.2, -0.7, -0.2):
            for t in range(18):
                px = cx + int(t * math.cos(ang))
                py = cy + int(t * math.sin(ang) * 0.5) + (t * t) // 40
                near.rect(px, py, 2, 2, "#120018")
    song = Song(key="E4", scale="minor", prog_a=(0, 5, 2, 6), prog_b=(3, 0, 6, 4), tempo=7,
                rhythm="synth", lead=(2, 9, 0), harm_style="arp16", harm=(0, 5, 1),
                bass_style="pump", wave="saw", drums="four", fill=True, seed=1212)
    return scene("OUTRUN", [near, grid, far, sky], [320, 0, 24, 0], song,
                 fx=[("cycle", 1, lines, 4, 4)],
                 mascot=mascot(S.CAR, "bob", 104, 118, amp=1, anim=6))


def space_cruise():
    sky = Layer(256)
    sky.gradient(0, 160, ["#02010a", "#070a1f", "#0c1430"])
    from pixel import BAYER4
    for cx, cy, rx, ry, col, dens in ((180, 100, 56, 30, "#1c1036", 0.55), (196, 92, 34, 18, "#2e1650", 0.45),
                                       (204, 88, 16, 9, "#4a2070", 0.4)):
        for dy in range(-ry, ry + 1):
            for dx in range(-rx, rx + 1):
                d = (dx / rx) ** 2 + (dy / ry) ** 2
                if d < 1 and (1 - d) * dens * 16 > BAYER4[(cy + dy) % 4][(cx + dx) % 4]:
                    sky.put(cx + dx, cy + dy, col)
    for dx in range(-34, 35):  # ring behind the planet
        dy = round(5 * math.sqrt(max(0, 1 - (dx / 34) ** 2)))
        sky.put(60 + dx, 50 - dy + dx // 7, "#c89a6a")
    sky.ring_disc(60, 50, 18, ["#ffcf8a", "#ff9a6a", "#d86a6a", "#9a4a6a"])
    for dx in range(-34, 35):  # ring in front
        dy = round(5 * math.sqrt(max(0, 1 - (dx / 34) ** 2)))
        sky.put(60 + dx, 50 + dy + dx // 7, "#ffe6b0")
        sky.put(60 + dx, 51 + dy + dx // 7, "#e8c08a")
    far = Layer()
    far.stars(70, 1, ["#5a6a9a", "#7a7aaa"], 0, 160)
    mid = Layer()
    mid.stars(40, 2, ["#b8c8ff", "#ffffff"], 0, 160)
    near = Layer()
    r = random.Random(3)
    for _ in range(14):
        x, y = r.randrange(512), r.randrange(160)
        near.hline(x, x + 6, y, "#ffffff")
    song = Song(key="D4", scale="minor", prog_a=(0, 6, 5, 6), prog_b=(3, 4, 0, 0), tempo=8,
                rhythm="synth", lead=(1, 9, 0), harm_style="arp16", harm=(0, 5, 1),
                bass_style="long", wave="saw", drums="break", fill=True, seed=1313)
    return scene("SPACE CRUISE", [near, mid, far, sky], [768, 256, 64, 0], song,
                 mascot=mascot(S.ROCKET, "bob", 70, 70, amp=3, anim=6))


def volcano():
    from pixel import BAYER4
    sky = Layer(256)
    sky.gradient(0, 160, ["#140404", "#3a0808", "#8a1f0c"])
    far = Layer(256)
    lava = far.reserve(["#ff3a0a", "#ff8a1a", "#ffd23a", "#ff6a1a"])
    # Glow above the crater, dithered into the sky colours of this layer
    for dy in range(-34, 4):
        for dx in range(-46, 47):
            d = (dx / 46) ** 2 + (dy / 34) ** 2
            if d < 1 and (1 - d) * 11 > BAYER4[(40 + dy) % 4][(128 + dx) % 4]:
                far.put(128 + dx, 40 + dy, "#5a1408" if d > 0.45 else "#8a2a0c")
    # The cone, with a shaded right flank
    for y in range(40, 160):
        half = (y - 40) * 1.15 + 9
        far.hline(128 - half, 128 + half, y, "#2a0a08")
        far.hline(128 + half * 0.35, 128 + half, y, "#200806")
    # Crater: a glowing pool at the summit
    for dx in range(-9, 10):
        far.put(128 + dx, 40, lava + 2)
        far.put(128 + dx, 41, lava + (abs(dx) // 3) % 4)
    # Lava streams branching down both flanks; colour index follows the flow
    # downhill, so cycling the palette moves the lava toward the river.
    rnd = random.Random(14)
    for bias, wobble in ((-1.05, 2.5), (-0.6, 3.0), (-0.2, 2.0), (0.25, 2.5), (0.7, 3.0), (1.05, 2.0)):
        x = 128 + rnd.uniform(-5, 5)
        phase = rnd.uniform(0, 6)
        for y in range(42, 160):
            half_cone = (y - 40) * 1.15 + 9
            x += bias * 0.55 + 0.35 * math.sin(y / 6 + phase) * wobble / 3
            x = max(128 - half_cone + 3, min(128 + half_cone - 3, x))
            width = 2 + (y - 42) // 38
            for w in range(width):
                far.put(int(x) + w, y, lava + ((y // 3) % 4))
            if y > 70 and rnd.random() < 0.02:  # a short side trickle
                tx = int(x) + (1 if bias >= 0 else -1) * 2
                for ty in range(y, min(160, y + 10)):
                    far.put(tx + (ty - y) // 4 * (1 if bias >= 0 else -1), ty, lava + ((ty // 3) % 4))
    mid = Layer()
    mid.ridge(132, 12, "#1a0606", 2, freqs=(3, 7, 13), jag=True)
    near = Layer()
    nlava = near.reserve(["#ff3a0a", "#ff8a1a", "#ffd23a", "#ff6a1a"])
    for y in range(144, 160):
        for x in range(512):
            near.put(x, y, nlava + ((x // 6 + y // 2) % 4))
    tops = near.ridge_tops(146, 2, 4, freqs=(5, 11, 23))
    for x, t in enumerate(tops):  # dark crust islands floating on the river
        if math.sin(x / 13) > 0.55:
            for y in range(max(144, t), min(160, t + 4)):
                near.put(x, y, "#100303")
    song = Song(key="C4", scale="phrygian", prog_a=(0, 1, 0, 6), prog_b=(5, 6, 0, 1), tempo=6,
                rhythm="drive", lead=(2, 11, 0), harm_style="stab", harm=(2, 8, 1),
                bass_style="pump", wave="square", bass_vol=1, drums="drive", fill=True, seed=1414)
    return scene("VOLCANO", [near, mid, far, sky], [192, 64, 0, 0], song,
                 fx=[("cycle", 2, lava, 4, 6), ("cycle", 0, nlava, 4, 5)],
                 particles=parts(S.EMBER, 24, 0.1, -0.5, sway=2, anim=10))


def desert_night():
    sky = Layer(256)
    sky.gradient(0, 160, ["#0d0b2a", "#3a2a6a", "#7a4a8a"])
    tw = sky.reserve(["#ffffff", "#5a4a8a"])
    sky.stars(30, 8, ["#c8c0ff"], 0, 90)
    sky.stars(12, 3, [tw], 0, 90)
    sky.disc(60, 40, 18, "#fff3d6")
    sky.disc(66, 36, 4, "#f2e2c0")
    far = Layer()
    far.ridge(114, 8, "#3a2a55", 2, freqs=(1, 2, 3))
    mid = Layer()
    mt = mid.ridge_tops(132, 7, 5, freqs=(1, 3, 4))
    mid.ridge(132, 7, "#5a3a6a", 5, freqs=(1, 3, 4))
    for x in range(40, 512, 120):
        g = mt[x]
        mid.rect(x, g - 18, 4, 18, "#1a1030")
        mid.rect(x - 5, g - 12, 4, 3, "#1a1030")
        mid.rect(x - 5, g - 16, 2, 5, "#1a1030")
        mid.rect(x + 5, g - 10, 4, 3, "#1a1030")
        mid.rect(x + 7, g - 15, 2, 6, "#1a1030")
    near = Layer()
    near.ridge(150, 6, "#7a4a6a", 7, freqs=(1, 2, 5))
    song = Song(key="D4", scale="harmonic", prog_a=(0, 3, 4, 0), prog_b=(5, 3, 4, 4), tempo=9, swing=1,
                rhythm="lofi", lead=(1, 10, 4), harm_style="arp8", harm=(0, 5, 2),
                bass_style="walk", wave="triangle", drums="lofi", seed=1515)
    return scene("DESERT NIGHT", [near, mid, far, sky], [160, 80, 24, 0], song,
                 fx=[("twinkle", 3, tw, 0, 41)])


def aurora():
    sky = Layer(256)
    sky.gradient(0, 160, ["#020814", "#06142a", "#0a1f33"])
    tw = sky.reserve(["#ffffff", "#3a4a6a"])
    au = sky.reserve(["#3aff9a", "#2ad8b8", "#8a6aff", "#1a9a7a"])
    sky.stars(30, 5, ["#a8c0e0"], 0, 110)
    sky.stars(12, 1, [tw], 0, 110)
    from pixel import BAYER4
    for x in range(256):
        top = int(20 + 9 * math.sin(x / 23) + 5 * math.sin(x / 9))
        height = int(26 + 10 * math.sin(x / 17 + 1))
        for y in range(top, top + height):
            fade = 1 - (y - top) / height          # bright at the top edge, fading down
            streak = 0.6 + 0.4 * math.sin(x / 2.3)  # vertical curtain folds
            if fade * streak * 16 > BAYER4[y % 4][x % 4]:
                sky.put(x, y, au + ((x // 6 + y // 8) % 4))
    far = Layer()
    tops = far.ridge_tops(112, 18, 3, freqs=(2, 5, 9), jag=True)
    far.ridge(112, 18, "#8aa0c0", 3, freqs=(2, 5, 9), jag=True)
    far.snowcaps(tops, 5, "#e0ecff")
    mid = Layer()
    mid.rect(0, 140, 512, 20, "#06121c")
    for x in range(0, 512, 14):
        mid.tree_pine(x + 7, 142, 18 + (x * 7) % 9, "#06121c", snow="#c8dcec")
    song = Song(key="F#4", scale="minor", prog_a=(0, 5, 2, 6), prog_b=(3, 0, 4, 4), tempo=10,
                rhythm="dreamy", lead=(0, 9, 0), harm_style="arp16", harm=(0, 4, 1),
                bass_style="long", wave="sine", drums="none", seed=1616)
    return scene("AURORA", [None, mid, far, sky], [0, 96, 32, 0], song,
                 fx=[("cycle", 3, au, 4, 8), ("twinkle", 3, tw, 0, 33)],
                 particles=parts(S.TINY_SNOW, 8, -0.05, 0.15, sway=1))


def rainy_alley():
    sky = Layer(256)
    sky.gradient(0, 160, ["#070a12", "#121a28", "#1a2333"])
    far = Layer()
    r = random.Random(4)
    x = 0
    k = 0
    while x < 512:
        w = r.choice((24, 32))
        far.building(x, r.choice((64, 72, 80)), w, "#121826", "#5a6a8a", "#141b29", seed=100 + k)
        x += w + 8
        k += 1
    mid = Layer()
    sign = mid.reserve(["#ff3a5a", "#ff9aa8", "#7a1a2a", "#ff3a5a"])
    x = 0
    while x < 512:
        w = r.choice((40, 48))
        mid.building(x, r.choice((88, 96)), w, "#20192a", "#ffd27a", "#2a2236", seed=200 + k)
        if k % 2:
            mid.rect(x + 6, 104, 18, 6, sign + (k % 4))
        x += w + 24
        k += 1
    near = Layer()
    near.rect(0, 146, 512, 14, "#0d121c")
    for x in range(0, 512, 24):
        near.hline(x, x + 10, 150 + (x // 24) % 6, "#3a4a66")
        near.hline(x + 4, x + 8, 154 + (x // 24) % 4, "#ffd27a")
    song = Song(key="C4", scale="dorian", prog_a=(0, 3, 0, 3), prog_b=(5, 6, 3, 4), tempo=10, swing=2,
                rhythm="lofi", lead=(1, 9, 4), harm_style="stab", harm=(0, 6, 2),
                bass_style="walk", wave="triangle", drums="lofi", seed=1717)
    return scene("RAINY ALLEY", [near, mid, far, sky], [0, 48, 16, 0], song,
                 fx=[("cycle", 1, sign, 4, 30)],
                 particles=parts(S.RAIN, 28, -1.2, 3.0, sway=0, anim=0))


def thunder_peaks():
    sky = Layer(256)
    sky.gradient(0, 160, ["#141a26", "#2a3448", "#46526c"])
    far = Layer()
    for x in range(0, 512, 96):
        far.cloud(x + 40, 40, 20, "#2a3344", "#222a38")
        far.cloud(x + 88, 60, 14, "#323c50", "#2a3344")
    mid = Layer()
    mid.ridge(118, 26, "#141a26", 7, freqs=(3, 7, 17), jag=True)
    near = Layer()
    near.ridge(148, 6, "#0a0e16", 4, freqs=(5, 9, 21), jag=True)
    song = Song(key="E4", scale="minor", prog_a=(0, 5, 6, 0), prog_b=(3, 5, 4, 4), tempo=6,
                rhythm="drive", lead=(2, 11, 0), harm_style="arp16", harm=(2, 6, 1),
                bass_style="pump", wave="square", bass_vol=2, drums="break", fill=True, seed=1818)
    return scene("THUNDER PEAKS", [near, mid, far, sky], [224, 64, 96, 0], song,
                 fx=[("flash", 3, 0, 0, 0)],
                 particles=parts(S.RAIN, 24, -1.0, 3.2, sway=0, anim=0))


def data_stream():
    sky = Layer(256)
    code = sky.reserve(["#eaffea", "#3aff7a", "#1aa84a", "#0a5a2a", "#06381a", "#000000"])
    sky.fill("#000000")
    r = random.Random(9)
    for col in range(0, 256, 8):
        off = r.randrange(6)
        for y in range(0, 160):
            if (y // 2 + col // 8) % 3 != 2 and (col // 8 + y // 4) % 5 != 0:
                sky.put(col + 2 + (y % 2), y, code + ((y // 6 + off) % 6))
    floor = Layer(256)
    lines = floor.reserve(["#3aff7a", "#1aa84a", "#0a5a2a", "#06381a"])
    floor.rect(0, 120, 256, 40, "#000000")
    for k in range(-10, 11):
        for y in range(120, 160):
            floor.put(128 + k * (y - 116) * 1.4, y, "#0a5a2a")
    y, gap, i = 121.0, 2.0, 0
    while y < 160:
        floor.hline(0, 256, int(y), lines + i % 4)
        y += gap
        gap *= 1.4
        i += 1
    song = Song(key="A4", scale="minor", prog_a=(0, 6, 5, 4), prog_b=(3, 4, 0, 0), tempo=7,
                rhythm="drive", lead=(0, 10, 0), harm_style="arp16", harm=(0, 5, 1),
                bass_style="pump", wave="saw", drums="four", fill=True, seed=1919)
    return scene("DATA STREAM", [floor, None, None, sky], [0, 0, 0, 0], song,
                 fx=[("cycle", 3, code, 6, 4), ("cycle", 0, lines, 4, 5)],
                 particles=parts(S.GLYPH, 10, 0.0, 2.0, sway=0, anim=6))


def comet_night():
    sky = Layer(256)
    sky.gradient(0, 160, ["#03081f", "#0f1f4a", "#2a3f7a"])
    tw = sky.reserve(["#ffffff", "#3a4a7a"])
    sky.stars(36, 2, ["#b8c8ff", "#7a8ac8"], 0, 100)
    sky.stars(14, 7, [tw], 0, 100)
    far = Layer()
    far.ridge(112, 12, "#1a2a55", 5, freqs=(1, 3, 5))
    mid = Layer()
    water = mid.reserve(["#2a4a8a", "#3a5a9a", "#1a3a7a", "#4a6aaa"])
    mid.ridge(128, 4, "#0f1a38", 3, freqs=(2, 5, 9))
    for y in range(136, 148):
        for x in range(512):
            mid.put(x, y, water + ((x // 10 + y) % 4))
    near = Layer()
    near.rect(0, 148, 512, 12, "#050a18")
    for x in range(0, 512, 22):
        near.tree_pine(x + 9, 150, 22 + (x * 5) % 11, "#050a18")
    song = Song(key="G4", scale="minor", prog_a=(0, 5, 2, 6), prog_b=(3, 4, 0, 0), tempo=9, swing=1,
                rhythm="synth", lead=(1, 10, 5), harm_style="arp8", harm=(0, 5, 2),
                bass_style="long", wave="triangle", drums="lofi", seed=2020)
    return scene("COMET NIGHT", [near, mid, far, sky], [160, 64, 24, 0], song,
                 fx=[("twinkle", 3, tw, 0, 29), ("cycle", 1, water, 4, 12)],
                 mascot=mascot(S.COMET, "drift", 0, 10, vx=0.35, amp=2, anim=60))


ALL = [sakura_hill, candy_clouds, bunny_meadow, starry_nap, ocean_bubbles, snow_village, mushroom_grove,
       strawberry_picnic, rainbow_falls, penguin_parade,
       neon_city, outrun, space_cruise, volcano, desert_night, aurora, rainy_alley, thunder_peaks,
       data_stream, comet_night]
