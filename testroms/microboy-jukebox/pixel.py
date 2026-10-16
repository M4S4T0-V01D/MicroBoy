"""Pixel-art toolkit for the Jukebox scenes.

A Layer is an indexed image (512 or 256 pixels wide, 160 tall, padded to 256
rows for the GBA map) with its own 16-colour palette: index 0 is transparent
(or the backdrop for the sky layer). Drawing wraps horizontally so scrolling
layers loop seamlessly. Colours are given as "#rrggbb" and get a palette slot
the first time they're used.
"""
import math
import random

H = 160


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def hexc(c):
    return "#%02x%02x%02x" % tuple(max(0, min(255, round(v))) for v in c)


def mix(a, b, t):
    a, b = rgb(a), rgb(b)
    return hexc([x + (y - x) * t for x, y in zip(a, b)])


def bgr555(h):
    r, g, b = (min(31, round(v / 255 * 31)) for v in rgb(h))
    return r | (g << 5) | (b << 10)


BAYER4 = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


class Layer:
    def __init__(self, width=512, opaque=False):
        self.w = width
        self.px = [[0] * width for _ in range(256)]
        self.colors = ["#000000"]  # slot 0
        self.opaque = opaque

    # --- palette ---------------------------------------------------------------
    def c(self, color):
        color = color.lower()
        if color not in self.colors:
            self.colors.append(color)
            assert len(self.colors) <= 16, f"layer palette overflow: {self.colors}"
        return self.colors.index(color)

    def reserve(self, colors):
        """Fixes palette slots (in order) for colour-cycling effects. Returns the first slot."""
        first = len(self.colors)
        for col in colors:
            self.colors.append(col.lower())
        assert len(self.colors) <= 16
        return first

    # --- pixels ------------------------------------------------------------------
    def put(self, x, y, color):
        if 0 <= y < H:
            self.px[y][int(x) % self.w] = color if isinstance(color, int) else self.c(color)

    def get(self, x, y):
        return self.px[y][int(x) % self.w] if 0 <= y < H else 0

    def hline(self, x0, x1, y, color):
        idx = color if isinstance(color, int) else self.c(color)
        for x in range(int(x0), int(x1)):
            self.put(x, y, idx)

    def rect(self, x, y, w, h, color):
        for yy in range(int(y), int(y + h)):
            self.hline(x, x + w, yy, color)

    def fill(self, color):
        self.rect(0, 0, self.w, H, color)

    def disc(self, cx, cy, r, color, stripes=0):
        """Filled circle; stripes > 0 cuts horizontal gaps in the lower half (retro sun)."""
        for dy in range(-r, r + 1):
            if stripes and dy > r // 5 and dy % stripes == 0:
                continue
            half = int(math.sqrt(max(0, r * r - dy * dy)))
            self.hline(cx - half, cx + half + 1, cy + dy, color)

    def ring_disc(self, cx, cy, r, colors):
        """Disc shaded in vertical bands of `colors` (top to bottom)."""
        for dy in range(-r, r + 1):
            half = int(math.sqrt(max(0, r * r - dy * dy)))
            col = colors[min(len(colors) - 1, (dy + r) * len(colors) // (2 * r + 1))]
            self.hline(cx - half, cx + half + 1, cy + dy, col)

    def crescent(self, cx, cy, r, color, bg_shift=5):
        for dy in range(-r, r + 1):
            for dx in range(-r, r + 1):
                if dx * dx + dy * dy <= r * r and (dx + bg_shift) ** 2 + (dy - 2) ** 2 > (r - 1) ** 2:
                    self.put(cx + dx, cy + dy, color)

    def gradient(self, y0, y1, colors, dither=True):
        """Vertical gradient through `colors`, ordered-dithered (tile-friendly)."""
        n = len(colors) - 1
        for y in range(y0, y1):
            t = (y - y0) / max(1, (y1 - y0 - 1)) * n
            i = min(n - 1, int(t))
            f = t - i
            for x in range(self.w):
                use_next = dither and f * 16 > BAYER4[y % 4][x % 4]
                self.put(x, y, colors[i + 1] if (use_next or (not dither and f > 0.5)) else colors[i])

    def bands(self, y0, y1, colors):
        """Flat horizontal bands (old-school sky)."""
        n = len(colors)
        for y in range(y0, y1):
            self.hline(0, self.w, y, colors[min(n - 1, (y - y0) * n // (y1 - y0))])

    def stars(self, n, seed, colors, y0=0, y1=100, avoid=None):
        r = random.Random(seed)
        for _ in range(n):
            x, y = r.randrange(self.w), r.randrange(y0, y1)
            if avoid and avoid(x, y):
                continue
            self.put(x, y, r.choice(colors))

    def ridge(self, base, amp, color, seed, freqs=(2, 5, 11), weights=(0.55, 0.3, 0.15), jag=False, step=1):
        """Periodic mountain silhouette from `base` (y) down to the bottom."""
        for x in range(0, self.w, step):
            t = 2 * math.pi * x / self.w
            v = sum(w * math.sin(f * t + seed * (k + 1) * 1.7) for k, (f, w) in enumerate(zip(freqs, weights)))
            if jag:
                v = abs(v) * 2 - 0.6
            top = round(base - amp * v)
            for xx in range(x, x + step):
                for y in range(max(0, top), H):
                    self.put(xx, y, color)

    def ridge_tops(self, base, amp, seed, freqs=(2, 5, 11), weights=(0.55, 0.3, 0.15), jag=False):
        out = []
        for x in range(self.w):
            t = 2 * math.pi * x / self.w
            v = sum(w * math.sin(f * t + seed * (k + 1) * 1.7) for k, (f, w) in enumerate(zip(freqs, weights)))
            if jag:
                v = abs(v) * 2 - 0.6
            out.append(round(base - amp * v))
        return out

    def snowcaps(self, tops, depth, color):
        for x, top in enumerate(tops):
            for y in range(top, min(H, top + depth + (x * 7 % 3))):
                self.put(x, y, color)

    def cloud(self, cx, cy, size, color, shade=None):
        """Puffy cloud from overlapping circles with a flat bottom."""
        puffs = [(-1.2, 0.2, 0.55), (-0.5, -0.25, 0.8), (0.4, -0.35, 0.9), (1.2, 0.1, 0.6), (0.0, 0.2, 0.7)]
        for ox, oy, rr in puffs:
            r = int(size * rr)
            for dy in range(-r, r + 1):
                half = int(math.sqrt(max(0, r * r - dy * dy)))
                y = int(cy + oy * size + dy)
                if y > cy + size * 0.45:
                    continue
                col = shade if (shade and y > cy + size * 0.15) else color
                self.hline(cx + ox * size - half, cx + ox * size + half + 1, y, col)

    def tree_round(self, x, ground, h, canopy, trunk, leaves, highlight=None):
        self.rect(x - 2, ground - h, 4, h, trunk)
        cy = ground - h - canopy // 3
        for ox, oy, rr in [(0, 0, 1.0), (-0.7, 0.35, 0.75), (0.7, 0.35, 0.75), (0, -0.5, 0.7)]:
            self.disc(x + int(ox * canopy), cy + int(oy * canopy), int(canopy * rr), leaves)
        if highlight:
            self.disc(x - canopy // 3, cy - canopy // 3, max(2, canopy // 4), highlight)

    def tree_pine(self, x, ground, h, color, trunk=None, snow=None):
        if trunk:
            self.rect(x - 1, ground - 4, 3, 4, trunk)
        tiers = 3
        for k in range(tiers):
            top = ground - h + k * h // (tiers + 1)
            bottom = top + h // 2
            for y in range(top, bottom):
                half = (y - top) * (h // 4 + k * 2) // max(1, bottom - top)
                self.hline(x - half, x + half + 1, y, color)
                if snow and y < top + 2:
                    self.hline(x - half, x + half + 1, y, snow)

    def house(self, x, ground, w, h, wall, roof, window, door=None):
        self.rect(x, ground - h, w, h, wall)
        for k in range(w // 2 + 3):
            self.hline(x - 2 + k, x + w + 2 - k, ground - h - k, roof)
        self.rect(x + 3, ground - h + 4, 4, 4, window)
        if w > 14:
            self.rect(x + w - 7, ground - h + 4, 4, 4, window)
        if door:
            self.rect(x + w // 2 - 2, ground - 7, 4, 7, door)

    def building(self, x, top, w, wall, window_on, window_off, seed, edge=None, sign=None):
        """Tile-aligned building: windows on a 4px grid, lit pseudo-randomly."""
        r = random.Random(seed)
        self.rect(x, top, w, H - top, wall)
        if edge:
            self.rect(x, top, w, 1, edge)
        for wy in range(top + 4, H - 4, 8):
            for wx in range(x + 2, x + w - 3, 4):
                self.rect(wx, wy, 2, 3, window_on if r.random() < 0.45 else window_off)
        if sign:
            sy = top + 6
            self.rect(x + 2, sy, w - 4, 3, sign)

    def bubble_sprite(self):
        return None


# --- sprites from ASCII art ---------------------------------------------------------

def ascii_sprite(rows, legend):
    """rows: equal-length strings; legend maps chars to '#rrggbb' (or '.' = transparent).
    Returns (pixels 2D list of colour hexes/None)."""
    out = []
    for row in rows:
        out.append([None if ch == "." else legend[ch] for ch in row])
    return out


class SpritePalette:
    def __init__(self):
        self.colors = ["#000000"]

    def idx(self, color):
        if color is None:
            return 0
        color = color.lower()
        if color not in self.colors:
            self.colors.append(color)
            assert len(self.colors) <= 16, "sprite palette overflow"
        return self.colors.index(color)


def sprite_tiles(frames, size, pal):
    """Packs sprite frames (size×size colour grids) as 4bpp tiles in 1D order."""
    out = bytearray()
    t = size // 8
    for fr in frames:
        for ty in range(t):
            for tx in range(t):
                for y in range(8):
                    row = fr[ty * 8 + y]
                    for x in range(0, 8, 2):
                        a = pal.idx(row[tx * 8 + x])
                        b = pal.idx(row[tx * 8 + x + 1])
                        out.append(a | (b << 4))
    return bytes(out)


def pad_sprite(grid, size):
    """Centres a smaller ASCII grid in size×size."""
    h, w = len(grid), len(grid[0])
    oy, ox = (size - h) // 2, (size - w) // 2
    out = [[None] * size for _ in range(size)]
    for y in range(h):
        for x in range(w):
            out[y + oy][x + ox] = grid[y][x]
    return out
