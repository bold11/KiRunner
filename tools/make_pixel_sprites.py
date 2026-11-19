"""
Generates Ki Runner's original pixel-art sprites (hero forms + enemy block).

    python tools/make_pixel_sprites.py            # writes into app/src/main/res/drawable-nodpi
    python tools/make_pixel_sprites.py --preview  # also writes .tmp/sprite_preview.png

Everything is drawn on a small pixel grid with simple shapes, then shaded (light from the
top-left), outlined, and scaled up with nearest-neighbour so the pixels stay crisp.
Forms: BASE (yellow hair), STORM, JADE, VOID, ZENITH (white hair).
Hero grid: 80 x 40 (x6 = 480 x 240, same size as the old art). Enemy grid: 40 x 40 (x8 = 320).
"""
import math
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


OUTLINE = rgb("1a1430")

# ---------------------------------------------------------------- grid helpers


class Layer:
    """A material-id grid. 0 = empty."""

    def __init__(self, w, h):
        self.w, self.h = w, h
        self.m = [[0] * w for _ in range(h)]

    def set(self, x, y, v):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.m[y][x] = v

    def get(self, x, y):
        if 0 <= x < self.w and 0 <= y < self.h:
            return self.m[y][x]
        return 0

    def rect(self, x0, y0, x1, y1, v):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, v)

    def ellipse(self, cx, cy, rx, ry, v):
        for y in range(int(cy - ry) - 1, int(cy + ry) + 2):
            for x in range(int(cx - rx) - 1, int(cx + rx) + 2):
                if ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2 <= 1.0:
                    self.set(x, y, v)

    def poly(self, pts, v):
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        for y in range(int(min(ys)) - 1, int(max(ys)) + 2):
            for x in range(int(min(xs)) - 1, int(max(xs)) + 2):
                if inside(x + 0.5, y + 0.5, pts):
                    self.set(x, y, v)

    def line(self, pts, v):
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            n = max(abs(x1 - x0), abs(y1 - y0), 1)
            for i in range(n + 1):
                self.set(round(x0 + (x1 - x0) * i / n), round(y0 + (y1 - y0) * i / n), v)


def inside(px, py, pts):
    hit = False
    j = len(pts) - 1
    for i in range(len(pts)):
        xi, yi = pts[i]
        xj, yj = pts[j]
        if (yi > py) != (yj > py) and px < (xj - xi) * (py - yi) / (yj - yi) + xi:
            hit = not hit
        j = i
    return hit


def paint(canvas, layer, palette, shade=True, outline=True):
    """Draws a layer onto the RGBA canvas grid. palette[id] = (light, mid, dark) or a single color.
    Pixels on the top/left edge of a material get the light tone, bottom/right edge the dark tone."""
    w, h = layer.w, layer.h
    for y in range(h):
        for x in range(w):
            v = layer.m[y][x]
            if not v:
                continue
            tones = palette[v]
            if isinstance(tones[0], int):
                canvas[y][x] = tones
                continue
            light, mid, dark = tones
            c = mid
            if shade:
                if layer.get(x + 1, y) != v or layer.get(x, y + 1) != v:
                    c = dark
                elif layer.get(x - 1, y) != v or layer.get(x, y - 1) != v:
                    c = light
            canvas[y][x] = c
    if outline:
        for y in range(h):
            for x in range(w):
                if layer.m[y][x]:
                    continue
                if any(layer.get(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    canvas[y][x] = OUTLINE


def new_canvas(w, h):
    return [[(0, 0, 0, 0)] * w for _ in range(h)]


def to_image(canvas, scale):
    h, w = len(canvas), len(canvas[0])
    img = Image.new("RGBA", (w, h))
    img.putdata([px for row in canvas for px in row])
    return img.resize((w * scale, h * scale), Image.NEAREST)


# ---------------------------------------------------------------- the hero

W, H = 80, 40
SKIN = (rgb("ffe2c4"), rgb("ffcfa6"), rgb("e9a77f"))
JACKET = (rgb("5fd3bd"), rgb("2f9e8f"), rgb("1d6a68"))
WRAP = (rgb("ffffff"), rgb("eceaf6"), rgb("b9b5cf"))
GOLD = (rgb("ffe07a"), rgb("ffc23c"), rgb("c9851e"))
SLEEVE = (rgb("9af0dc"), rgb("49c2ad"), rgb("1f7a70"))
NAVY = (rgb("2a2552"), rgb("1e1a3c"), rgb("141030"))
CLOUD = (rgb("ffffff"), rgb("e6def9"), rgb("aa9bd9"))

# Form palettes: hair (light, mid, dark), iris, glowing eyes?
FORMS = [
    dict(name="player", hair=("fff7b8", "ffd84a", "c8951c"), eye="2ce5d8", glow=False, fx="sparkle"),
    dict(name="player_lv2", hair=("eadfff", "b89bff", "6f55c9"), eye="aa82ff", glow=False, fx="storm"),
    dict(name="player_lv3", hair=("d4ffe2", "62e59c", "24915b"), eye="5aeb96", glow=False, fx="sparkle"),
    dict(name="player_lv4", hair=("b98cff", "4a2f80", "24164a"), eye="f3c6ff", glow=True, fx="void"),
    dict(name="player_lv5", hair=("ffffff", "f3f5fb", "b4bcd6"), eye="dff8ff", glow=True, fx="zenith"),
]
PRISM = ["ff8fa3", "ffb36b", "ffe27a", "8ff0a4", "7fe6ff", "8fa8ff", "c99bff"]

BANGS = [(34, 13), (37, 12), (41, 13), (44, 12), (47, 13)]

# material ids
HAIR, SKN, JKT, WRP, GLD, NVY, CLD, SLV = 1, 2, 3, 4, 5, 6, 7, 8


def hair_layer():
    L = Layer(W, H)
    cx, cy = 40, 10
    L.ellipse(cx, cy, 11, 7, HAIR)
    # front fringe (drawn again over the forehead later)
    L.rect(33, 7, 47, 9, HAIR)
    for c, tip in BANGS:
        L.poly([(c - 2.5, 9), (c + 2.5, 9), (c - 0.5, tip)], HAIR)
    # spikes: (tip x, tip y, base half-width)
    spikes = [(21, 9, 3), (24, 3, 3), (30, 1, 3), (37, 1, 3), (44, 0, 3), (51, 1, 3), (57, 5, 3),
              (25, 15, 2.5), (56, 14, 2.5)]
    for tx, ty, hw in spikes:
        ty = max(ty, 1)
        dx, dy = tx - cx, ty - cy
        d = math.hypot(dx, dy)
        ux, uy = dx / d, dy / d
        bx, by = cx + ux * 6, cy + uy * 4.5
        px, py = -uy, ux
        L.poly([(bx + px * hw, by + py * hw), (tx, ty), (bx - px * hw, by - py * hw)], HAIR)
    return L


def hero(form):
    canvas = new_canvas(W, H)

    # --- back cloud puffs
    back = Layer(W, H)
    for e in [(18, 31, 10, 4.5), (31, 30, 8, 4), (50, 30, 8, 4), (62, 31, 10, 4.5)]:
        back.ellipse(*e, CLD)
    paint(canvas, back, {CLD: CLOUD})

    # --- character body
    body = Layer(W, H)
    body.rect(31, 22, 49, 33, JKT)              # torso
    body.rect(33, 21, 47, 21, JKT)              # shoulders
    body.rect(38, 19, 42, 21, SKN)              # neck
    for y, (a, b) in enumerate([(37, 43), (38, 42), (39, 41), (40, 40)], start=21):
        body.rect(a, y, b, y, NVY)              # V collar
    body.rect(28, 23, 31, 28, SLV)              # upper arms
    body.rect(49, 23, 52, 28, SLV)
    body.rect(31, 27, 50, 29, SLV)              # lower forearm
    body.rect(30, 24, 47, 26, SLV)              # upper forearm, crossing over it
    body.rect(43, 24, 46, 26, WRP)              # wrist wraps
    body.rect(31, 27, 33, 29, WRP)
    body.rect(47, 24, 50, 26, SKN)              # hands gripping the other arm
    body.rect(28, 27, 30, 29, SKN)
    body.rect(31, 30, 49, 31, GLD)              # sash
    paint(canvas, body, {JKT: JACKET, SLV: SLEEVE, SKN: SKIN, NVY: NAVY, WRP: WRAP, GLD: GOLD})

    # --- hair: rendered once as one shape, drawn behind the head, then the fringe again over it
    hair = hair_layer()
    hair_px = render_hair(hair, form)
    blit(canvas, hair_px)

    # --- head
    head = Layer(W, H)
    head.rect(33, 9, 47, 18, SKN)
    head.rect(34, 19, 46, 19, SKN)
    head.rect(36, 20, 44, 20, SKN)
    head.rect(32, 13, 32, 16, SKN)              # ears
    head.rect(48, 13, 48, 16, SKN)
    paint(canvas, head, {SKN: SKIN})

    # --- fringe over the forehead, with a dark line where it meets the skin
    fringe = Layer(W, H)
    fringe.rect(33, 7, 47, 9, HAIR)
    for c, tip in BANGS:
        fringe.poly([(c - 2.5, 9), (c + 2.5, 9), (c - 0.5, tip)], HAIR)
    for y in range(H):
        for x in range(W):
            if fringe.m[y][x] and hair_px[y][x][3]:
                canvas[y][x] = hair_px[y][x]
    for y in range(H - 1):
        for x in range(W):
            if fringe.m[y][x] and not fringe.m[y + 1][x] and canvas[y + 1][x] in SKIN:
                canvas[y + 1][x] = OUTLINE

    # --- face
    dark = OUTLINE
    iris = rgb(form["eye"])
    white = rgb("ffffff")
    for x, y in [(35, 12), (36, 12), (37, 13), (38, 13), (45, 12), (44, 12), (43, 13), (42, 13)]:
        canvas[y][x] = dark                     # determined brows
    for x0, pupil_side in [(35, 1), (42, -1)]:
        for x in range(x0, x0 + 4):
            canvas[14][x] = dark                # upper lid
        row = [white, iris, iris, white] if not form["glow"] else [iris] * 4
        if not form["glow"]:
            row[1 if pupil_side < 0 else 2] = dark
        for i, c in enumerate(row):
            canvas[15][x0 + i] = c
        if not form["glow"]:
            canvas[14][x0 + (2 if pupil_side < 0 else 1)] = rgb("ffffff")  # catch-light
        canvas[16][x0 + 1] = SKIN[2]
        canvas[16][x0 + 2] = SKIN[2]
    canvas[17][40] = SKIN[2]                     # nose
    for x in (39, 40, 41):
        canvas[19][x] = rgb("b5655a")           # small serious mouth

    # --- front cloud (covers the lower body) and the sash knot on top of it
    front = Layer(W, H)
    front.ellipse(40, 36, 31, 4.5, CLD)
    front.ellipse(26, 34, 9, 3.5, CLD)
    front.ellipse(54, 34, 9, 3.5, CLD)
    paint(canvas, front, {CLD: CLOUD})

    knot = Layer(W, H)
    knot.rect(39, 30, 41, 32, GLD)
    knot.poly([(39, 31.5), (34.5, 28.5), (34.5, 34)], GLD)
    knot.poly([(41, 31.5), (45.5, 28.5), (45.5, 34)], GLD)
    knot.poly([(38.5, 32), (40, 32), (37.5, 37), (36, 36)], GLD)
    knot.poly([(40, 32), (41.5, 32), (44, 36), (42.5, 37)], GLD)
    paint(canvas, knot, {GLD: GOLD})

    effects(canvas, form)
    return canvas


def blit(canvas, src):
    for y in range(H):
        for x in range(W):
            if src[y][x][3]:
                canvas[y][x] = src[y][x]


def render_hair(layer, form):
    tmp = new_canvas(W, H)
    if form["hair"]:
        paint(tmp, layer, {HAIR: tuple(rgb(c) for c in form["hair"])})
        return tmp
    # PRISM: rainbow bands across the hair, each with its own light / dark tone
    paint(tmp, layer, {HAIR: (rgb("ffffff"), rgb("888888"), rgb("444444"))})
    for y in range(H):
        for x in range(W):
            px = tmp[y][x]
            if px[3] == 0 or px == OUTLINE:
                continue
            band = rgb(PRISM[min(len(PRISM) - 1, max(0, (x - 18 + y // 2) * len(PRISM) // 44))])
            k = {255: 1.18, 136: 1.0, 68: 0.66}[px[0]]
            tmp[y][x] = tuple(min(255, int(c * k + (30 if k > 1 else 0))) for c in band[:3]) + (255,)
    return tmp


def sparkle(canvas, x, y, c, big=False):
    pts = [(0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)]
    if big:
        pts += [(2, 0), (-2, 0), (0, 2), (0, -2)]
    for dx, dy in pts:
        if 0 <= x + dx < W and 0 <= y + dy < H:
            canvas[y + dy][x + dx] = c


def bolt(canvas, pts, c, edge):
    L = Layer(W, H)
    L.line(pts, 1)
    for y in range(H):
        for x in range(W):
            if L.m[y][x]:
                canvas[y][x] = c
            elif any(L.get(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0))) and canvas[y][x][3] == 0:
                canvas[y][x] = edge


def effects(canvas, form):
    white = rgb("ffffff")
    fx = form["fx"]
    sparkle(canvas, 11, 10, white, big=True)
    sparkle(canvas, 69, 15, white)
    sparkle(canvas, 64, 24, white)
    if fx in ("storm", "prism", "zenith"):
        edge = rgb(form["eye"]) if fx == "storm" else rgb("7fe6ff")
        bolt(canvas, [(16, 6), (13, 11), (17, 13), (13, 20)], white, edge)
        bolt(canvas, [(65, 4), (68, 9), (64, 11), (68, 18)], white, edge)
    if fx == "void":
        c = rgb("cf8bff")
        for x, y in [(15, 14), (16, 9), (63, 8), (66, 19), (12, 22)]:
            canvas[y][x] = c
    if fx == "zenith":
        for x, y in [(9, 18), (72, 8), (20, 4), (60, 2)]:
            sparkle(canvas, x, y, rgb("dff8ff"))
    if fx == "prism":
        for i, (x, y) in enumerate([(9, 18), (72, 8), (20, 4), (60, 2)]):
            sparkle(canvas, x, y, rgb(PRISM[i * 2 % len(PRISM)]))


# ---------------------------------------------------------------- the enemy block (square obstacle)

EW = 40
SHELL = (rgb("ff9ad8"), rgb("e0408f"), rgb("8a1c63"))
SHELL_TOP = (rgb("ffd0ef"), rgb("ff7cc4"), rgb("c2307f"))
HORN = (rgb("fff4d6"), rgb("ffd27a"), rgb("c98a2a"))
GLOW = (255, 90, 190, 110)


def enemy():
    canvas = new_canvas(EW, EW)
    # soft square glow behind (inside the square image, so the hitbox stays honest)
    for y in range(1, EW - 1):
        for x in range(1, EW - 1):
            if 2 <= x <= EW - 3 and 2 <= y <= EW - 3:
                canvas[y][x] = (255, 90, 190, 45)

    L = Layer(EW, EW)
    L.poly([(9, 6), (31, 6), (35, 11), (5, 11)], 2)          # top face (lighter: reads as a 3D block)
    L.rect(5, 11, 34, 34, 1)                                  # front face
    L.rect(6, 35, 33, 35, 1)
    paint(canvas, L, {1: SHELL, 2: SHELL_TOP})

    horns = Layer(EW, EW)
    horns.poly([(9, 7), (13, 7), (8, 1)], 1)
    horns.poly([(27, 7), (31, 7), (32, 1)], 1)
    paint(canvas, horns, {1: HORN})

    dark = OUTLINE
    eye = rgb("fff27a")
    # angry slanted eyes
    for x, y in [(9, 15), (10, 15), (11, 16), (12, 16), (13, 17), (14, 17),
                 (30, 15), (29, 15), (28, 16), (27, 16), (26, 17), (25, 17)]:
        canvas[y][x] = dark
    for y in (18, 19, 20):
        for x in range(10, 15):
            canvas[y][x] = eye
        for x in range(25, 30):
            canvas[y][x] = eye
    for x, y in [(13, 19), (13, 20), (26, 19), (26, 20)]:
        canvas[y][x] = dark                     # pupils looking at the player
    # jagged mouth with fangs
    for x in range(11, 29):
        canvas[26][x] = dark
        canvas[29][x] = dark
    for y in (27, 28):
        canvas[y][11] = dark
        canvas[y][28] = dark
        for x in range(12, 28):
            canvas[y][x] = rgb("4a0f3a")
    for x in (13, 17, 21, 25):
        canvas[27][x] = rgb("ffffff")
        canvas[27][x + 1] = rgb("ffffff")
        canvas[28][x] = rgb("ffffff")
    # cracks / glow runes
    rune = rgb("ffd6f0")
    for x, y in [(30, 23), (31, 24), (31, 25), (32, 26), (7, 30), (8, 31), (8, 32), (21, 12), (22, 13)]:
        canvas[y][x] = rune
    return canvas


# ---------------------------------------------------------------- main

def main():
    os.makedirs(OUT, exist_ok=True)
    frames = []
    for f in FORMS:
        img = to_image(hero(f), 6)
        img.save(os.path.join(OUT, f["name"] + ".png"))
        frames.append(img)
    enemy_img = to_image(enemy(), 8)
    enemy_img.save(os.path.join(OUT, "obstacle.png"))

    if "--preview" in sys.argv:
        prev_dir = os.path.join(ROOT, ".tmp")
        os.makedirs(prev_dir, exist_ok=True)
        sheet = Image.new("RGBA", (480 * 3, 240 * 2 + 20), (40, 24, 70, 255))
        for i, fr in enumerate(frames):
            sheet.alpha_composite(fr, ((i % 3) * 480, (i // 3) * 250))
        sheet.alpha_composite(enemy_img.resize((240, 240), Image.NEAREST), (2 * 480 + 120, 250))
        sheet.save(os.path.join(prev_dir, "sprite_preview.png"))
    print("wrote", len(frames), "hero forms + obstacle to", OUT)


if __name__ == "__main__":
    main()
