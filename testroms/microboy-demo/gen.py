#!/usr/bin/env python3
"""Generates scene.h for the MicroBoy demo ROM: background tiles and maps,
sprite graphics, the 11 theme palettes, the font and the music tables.

The scene is the one on the MicroBoy website (docs/site.js): a striped sun,
twinkling stars and three parallax ridges. Every ridge is a sum of sines whose
periods divide 512, so the 512-pixel-wide tile maps wrap seamlessly and the
hardware scroll can run forever.
"""
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))

# Same seeds as Palettes.kt and docs/site.js.
THEMES = [
    ("MIDNIGHT", "#B39DFF", "#FF8A80", "#14121F"),
    ("GLACIER", "#7FD8FF", "#B8F2E6", "#0E1A24"),
    ("ARCADE", "#FF4FD8", "#3CF0FF", "#07060C"),
    ("EMBER", "#FF9248", "#FFD166", "#1C110D"),
    ("LIME SODA", "#C6F432", "#4FE3A1", "#111A0E"),
    ("DEEP SEA", "#4FC3F7", "#7C83FF", "#0A1428"),
    ("POCKET LCD", "#9BBC0F", "#8BAC0F", "#0F380F"),
    ("OLED BLACK", "#E0E0E0", "#B39DFF", "#000000"),
    ("SAKURA", "#D9467A", "#7B5EA7", "#FFF4F7"),
    ("PAPER", "#2F4FA2", "#D9822B", "#F7F3EA"),
    ("STONE", "#6A4BC4", "#B0305C", "#E6E4E0"),
]

W, H = 240, 160
MAP_W, MAP_H = 512, 256


# --- color ------------------------------------------------------------------------

def rgb(h):
    return [int(h[i:i + 2], 16) for i in (1, 3, 5)]


def mix(a, b, t):
    return [x + (y - x) * t for x, y in zip(a, b)]


def lum(c):
    def ch(v):
        v /= 255
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = (ch(v) for v in c)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def bgr555(c):
    r, g, b = (min(31, max(0, round(v / 255 * 31))) for v in c)
    return r | (g << 5) | (b << 10)


WHITE, BLACK = [255] * 3, [0] * 3

# Palette slots. Each background layer has its own 16-color bank.
SKY_BANDS = [1, 2, 3, 4]
SKY_HAZE = 5
STAR_WHITE, STAR_ACCENT, STAR_TWINKLE = 6, 7, 8
SUN = list(range(9, 16))  # 7 shades, top to bottom
RIDGE = 1
GROUND, STRIPE = 2, 3
OBJ_TEXT, OBJ_SHADOW, OBJ_COUNTER = 1, 2, 3


def theme_palette(primary, accent, bg):
    p, a, b = rgb(primary), rgb(accent), rgb(bg)
    night = mix(b, BLACK, 0.2 if lum(b) < 0.4 else 0.75)
    banks = [[[0, 0, 0]] * 16 for _ in range(5)]  # BG banks 0-3, OBJ bank 0

    sky = banks[0]
    sky[0] = night
    for i, slot in enumerate(SKY_BANDS):
        sky[slot] = mix(night, p, i * 0.09)
    sky[SKY_HAZE] = mix(night, a, 0.35)
    sky[STAR_WHITE] = WHITE
    sky[STAR_ACCENT] = a
    sky[STAR_TWINKLE] = WHITE
    for i, slot in enumerate(SUN):
        sky[slot] = mix(a, p, i / (len(SUN) - 1) * 0.72)

    banks[1][RIDGE] = mix(night, p, 0.45)
    banks[2][RIDGE] = mix(night, p, 0.25)
    banks[3][RIDGE] = mix(night, a, 0.18)
    banks[3][GROUND] = mix(night, BLACK, 0.35)
    banks[3][STRIPE] = mix(night, p, 0.35)

    obj = banks[4]
    obj[OBJ_TEXT] = WHITE
    obj[OBJ_SHADOW] = mix(night, BLACK, 0.55)
    obj[OBJ_COUNTER] = mix(WHITE, a, 0.3)

    twinkle_dim = mix(sky[SKY_BANDS[1]], WHITE, 0.25)
    return [[bgr555(c) for c in bank] for bank in banks], bgr555(twinkle_dim)


# --- scene layers -------------------------------------------------------------------

def blank(fill=0):
    return [[fill] * MAP_W for _ in range(MAP_H)]


def ridge(x, seed, amp):
    # Periods 512/3, 512/7 and 512/17 pixels: all divide the 512-pixel map.
    t = 2 * math.pi * x / MAP_W
    return amp * (0.55 * math.sin(3 * t + seed) + 0.3 * math.sin(7 * t + seed * 2.3) + 0.15 * math.sin(17 * t + seed * 4.1))


def ridge_layer(seed, amp, base, ground=False):
    img = blank()
    for x in range(MAP_W):
        top = round(base + ridge(x, seed, amp))
        for y in range(top, H):
            img[y][x] = RIDGE
    if ground:
        for y in range(146, H):
            for x in range(MAP_W):
                img[y][x] = GROUND
        for x in range(MAP_W):
            if x % 16 < 8:
                img[150][x] = img[151][x] = STRIPE
    return img


def sky_layer():
    img = blank()
    for y in range(H):
        slot = SKY_BANDS[y // 22] if y < 88 else SKY_HAZE
        for x in range(MAP_W):
            img[y][x] = slot
    # Sun: a pixel disc with stripes cut out of its lower half.
    for dy in range(-18, 19):
        if dy > 2 and dy % 4 == 0:
            continue
        half = int(math.isqrt(18 * 18 - dy * dy))
        shade = SUN[min(len(SUN) - 1, (dy + 18) * len(SUN) // 37)]
        for x in range(170 - half, 170 + half):
            img[62 + dy][x] = shade
    # Stars: same placement as the website. Every third one twinkles.
    for i in range(40):
        sx, sy, kind = (i * 97) % W, (i * 53) % 70, i % 3
        if img[sy][sx] in SKY_BANDS:
            img[sy][sx] = STAR_TWINKLE if kind == 0 else STAR_ACCENT if kind == 2 else STAR_WHITE
    return img


# --- tiles ----------------------------------------------------------------------------

tiles = [bytes(32)]  # tile 0 is fully transparent
tile_index = {tiles[0]: 0}


def tile_bytes(img, tx, ty):
    out = bytearray()
    for y in range(8):
        row = img[ty * 8 + y]
        for x in range(0, 8, 2):
            out.append(row[tx * 8 + x] | (row[tx * 8 + x + 1] << 4))
    return bytes(out)


def to_map(img, palbank, width_tiles):
    """Returns screen entries in GBA screenblock order (32×32 blocks)."""
    entries = []
    for block in range(width_tiles // 32):
        for ty in range(32):
            for tx in range(block * 32, block * 32 + 32):
                t = tile_bytes(img, tx, ty)
                if t not in tile_index:
                    tile_index[t] = len(tiles)
                    tiles.append(t)
                idx = tile_index[t]
                entries.append(0 if idx == 0 else idx | (palbank << 12))
    return entries


maps = {
    "sky": to_map(sky_layer(), 0, 32),
    "far": to_map(ridge_layer(1.3, 26, 92), 1, 64),
    "mid": to_map(ridge_layer(4.2, 22, 112), 2, 64),
    "near": to_map(ridge_layer(7.7, 14, 132, ground=True), 3, 64),
}
assert len(tiles) <= 1024, f"{len(tiles)} background tiles; the maps can only address 1024"

# --- font and title sprite ----------------------------------------------------------------

FONT = {
    "A": "010101111101101", "B": "110101110101110", "C": "111100100100111", "D": "110101101101110",
    "E": "111100110100111", "F": "111100110100100", "G": "111100101101111", "H": "101101111101101",
    "I": "111010010010111", "J": "001001001101111", "K": "101101110101101", "L": "100100100100111",
    "M": "101111111101101", "N": "110101101101101", "O": "111101101101111", "P": "111101111100100",
    "Q": "111101101111001", "R": "110101110101101", "S": "111100111001111", "T": "111010010010010",
    "U": "101101101101111", "V": "101101101101010", "W": "101101111111101", "X": "101101010101101",
    "Y": "101101010010010", "Z": "111001010100111", " ": "000000000000000",
    "0": "111101101101111", "1": "010110010010111", "2": "111001111100111", "3": "111001111001111",
    "4": "101101111001001", "5": "111100111001111", "6": "111100111101111", "7": "111001001001001",
    "8": "111101111101111", "9": "111101111001111",
}
FONT_CHARS = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"


def title_bitmap():
    """'MICROBOY' at 3× scale with a drop shadow, as four 32×32 sprites."""
    img = [[0] * 128 for _ in range(32)]
    for layer, (ox, oy, color) in enumerate(((2, 2, OBJ_SHADOW), (0, 0, OBJ_TEXT))):
        for n, ch in enumerate("MICROBOY"):
            g = FONT[ch]
            for i in range(15):
                if g[i] == "1":
                    for yy in range(3):
                        for xx in range(3):
                            x = 8 + n * 12 + (i % 3) * 3 + xx + ox
                            y = 8 + (i // 3) * 3 + yy + oy
                            img[y][x] = color
    out = []
    for sprite in range(4):  # 1D mapping: each 32×32 sprite is 16 consecutive tiles
        for ty in range(4):
            for tx in range(4):
                for y in range(8):
                    row = img[ty * 8 + y]
                    for x in range(0, 8, 2):
                        px = sprite * 32 + tx * 8 + x
                        out.append(row[px] | (row[px + 1] << 4))
    return out


# --- music --------------------------------------------------------------------------------
# "Pocket Lullaby": a slow, original 16-bar tune in F major for the GBA's four
# PSG channels, written for this ROM. It ends on C so it falls straight back
# into bar 1 and loops forever.
#   square 1  music-box lead (plucky, each note fades)
#   square 2  soft broken chords
#   wave      round bass from a sine-like wavetable
#   noise     gentle hi-hats and a soft snare
# The score is on a 16th-note grid; main.c plays one step every few frames.

NOTE_NAMES = {"C": 0, "C#": 1, "Db": 1, "D": 2, "D#": 3, "Eb": 3, "E": 4, "F": 5, "F#": 6, "Gb": 6,
              "G": 7, "G#": 8, "Ab": 8, "A": 9, "A#": 10, "Bb": 10, "B": 11}


def midi(name):
    return NOTE_NAMES[name[:-1]] + 12 * (int(name[-1]) + 1)


def hz(m):
    return 440 * 2 ** ((m - 69) / 12)


def square_reg(m):
    return round(2048 - 131072 / hz(m))


def wave_reg(m):
    return round(2048 - 65536 / hz(m))


STEPS_PER_BAR = 16

LEAD = """
A4:4 C5:2 A4:2 G4:4 F4:4 | A4:4 D5:4 C5:6 r:2 | D5:2 C5:2 Bb4:2 A4:2 G4:4 F4:4 | G4:6 A4:2 G4:4 E4:4 |
A4:4 C5:2 A4:2 F5:4 E5:2 D5:2 | C5:4 A4:2 C5:2 E5:6 r:2 | D5:4 Bb4:4 C5:4 E4:4 | F4:12 r:4 |
D5:2 F5:2 D5:2 Bb4:2 D5:4 C5:4 | E5:2 G5:2 E5:2 C5:2 E5:4 D5:4 | C5:4 E5:4 A5:6 G5:2 | F5:4 E5:2 D5:2 A4:8 |
Bb4:2 D5:2 G5:4 F5:2 E5:2 D5:4 | E5:4 C5:4 G4:4 Bb4:4 | A4:4 C5:4 D5:4 F5:4 | E5:6 D5:2 C5:8
"""

# One chord per bar, or two separated by a space for half bars.
CHORDS = ["F", "Dm", "Bb", "C", "F", "Am", "Bb C", "F",
          "Bb", "C", "Am", "Dm", "Gm", "C7", "F Bb", "C"]

TRIADS = {
    "F": ["F", "A", "C"], "Dm": ["D", "F", "A"], "Bb": ["Bb", "D", "F"], "C": ["C", "E", "G"],
    "Am": ["A", "C", "E"], "Gm": ["G", "Bb", "D"], "C7": ["C", "E", "Bb"],
}

# Noise hits per bar: h = hi-hat, s = snare, . = nothing. Busier in the bridge.
DRUMS_A = "..h...h.s.h...h."
DRUMS_B = "h.h.h.h.s.h.h.h."


def parse_lead():
    steps = [None] * (len(CHORDS) * STEPS_PER_BAR)
    pos = 0
    for tok in LEAD.replace("|", " ").split():
        name, dur = tok.split(":")
        steps[pos] = "rest" if name == "r" else midi(name)
        pos += int(dur)
    assert pos == len(steps), f"lead is {pos} steps, expected {len(steps)}"
    return steps


def voiced(root_names, low, high):
    """Chord tones placed in [low, high), ascending."""
    out = []
    for n in root_names:
        m = NOTE_NAMES[n] + 12 * 3
        while m < low:
            m += 12
        while m >= high:
            m -= 12
        out.append(m)
    return sorted(out)


def parse_backing():
    harm = [None] * (len(CHORDS) * STEPS_PER_BAR)
    bass = [None] * len(harm)
    for bar, spec in enumerate(CHORDS):
        names = spec.split()
        half = STEPS_PER_BAR // len(names)
        for k, name in enumerate(names):
            start = bar * STEPS_PER_BAR + k * half
            lo, mid, hi = voiced(TRIADS[name], midi("F3"), midi("F4"))
            # Broken chord in 8th notes: low, high, middle, high...
            pattern = [lo, hi, mid, hi]
            for i in range(0, half, 2):
                harm[start + i] = pattern[(i // 2) % 4]
            root = voiced(TRIADS[name][:1], midi("E2"), midi("E3"))[0]
            fifth = root + 7
            # Bass: root, fifth, root an octave up, fifth (quarters), or root/fifth in a half bar.
            line = [root, fifth, root + 12, fifth] if half == 16 else [root, fifth]
            for i, m in enumerate(line):
                bass[start + i * 4] = m
    return harm, bass


def parse_drums():
    out = []
    for bar in range(len(CHORDS)):
        pattern = DRUMS_B if 8 <= bar < 15 else DRUMS_A
        out += [{"h": 1, "s": 2}.get(c, 0) for c in pattern]
    return out


lead_steps = parse_lead()
harm_steps, bass_steps = parse_backing()
drum_steps = parse_drums()
SONG_STEPS = len(lead_steps)

# Per-step register values for main.c: 0 = no change, 0xFFFF = silence.
LEAD_TRACK = [0 if v is None else 0xFFFF if v == "rest" else square_reg(v) for v in lead_steps]
HARM_TRACK = [0 if v is None else square_reg(v) for v in harm_steps]
BASS_TRACK = [0 if v is None else wave_reg(v) for v in bass_steps]

# 32 4-bit samples: a slightly rounded sine for a soft, warm bass.
WAVE = [round(7.5 + 7.5 * math.sin(2 * math.pi * (i + 0.5) / 32) * 0.95) for i in range(32)]
WAVE_BYTES = [(WAVE[i] << 4) | WAVE[i + 1] for i in range(0, 32, 2)]

# --- emit ----------------------------------------------------------------------------------

def c_array(ctype, name, values, per_line=12):
    lines = []
    for i in range(0, len(values), per_line):
        lines.append("    " + ", ".join(str(v) for v in values[i:i + per_line]) + ",")
    return f"static const {ctype} {name}[{len(values)}] = {{\n" + "\n".join(lines) + "\n};\n"


def words(byte_seq):
    b = bytes(byte_seq)
    return [int.from_bytes(b[i:i + 4], "little") for i in range(0, len(b), 4)]


palettes, twinkles = [], []
for _, p, a, b in THEMES:
    banks, dim = theme_palette(p, a, b)
    palettes.append(banks)
    twinkles.append(dim)

out = ["// Generated by gen.py. Do not edit.", "#pragma once", "#include <stdint.h>", ""]
out.append(f"#define THEME_COUNT {len(THEMES)}")
out.append(f"#define BG_TILE_COUNT {len(tiles)}")
out.append(f"#define STAR_TWINKLE {STAR_TWINKLE}")
out.append(f"#define OBJ_TEXT {OBJ_TEXT}")
out.append(f"#define OBJ_SHADOW {OBJ_SHADOW}")
out.append(f"#define OBJ_COUNTER {OBJ_COUNTER}")
out.append(f"#define SONG_STEPS {SONG_STEPS}")
out.append("")
out.append(c_array("uint32_t", "bgTiles", words(b"".join(tiles)), 8))
for name, entries in maps.items():
    out.append(c_array("uint16_t", f"map_{name}", entries, 16))
out.append(c_array("uint32_t", "titleTiles", words(title_bitmap()), 8))
out.append(c_array("uint16_t", "themePalettes", [c for banks in palettes for bank in banks for c in bank], 16))
out.append(c_array("uint16_t", "twinkleDim", twinkles))
names = [n.ljust(10)[:10] for n, *_ in THEMES]
out.append(f"static const char themeNames[{len(THEMES)}][11] = {{ " + ", ".join(f'"{n}"' for n in names) + " };\n")
out.append(c_array("uint16_t", "fontGlyphs", [int(FONT[c][::-1], 2) for c in FONT_CHARS]))
out.append(f'static const char fontChars[] = "{FONT_CHARS}";\n')
out.append(c_array("uint16_t", "leadTrack", LEAD_TRACK, 16))
out.append(c_array("uint16_t", "harmTrack", HARM_TRACK, 16))
out.append(c_array("uint16_t", "bassTrack", BASS_TRACK, 16))
out.append(c_array("uint8_t", "drumTrack", drum_steps, 32))
out.append(c_array("uint8_t", "waveTable", WAVE_BYTES, 16))
bob = [round(2 * math.sin(2 * math.pi * i / 112)) for i in range(112)]
out.append(c_array("int8_t", "bobTable", bob, 16))

with open(os.path.join(HERE, "scene.h"), "w") as f:
    f.write("\n".join(out))
print(f"scene.h: {len(tiles)} background tiles, {len(THEMES)} themes")
