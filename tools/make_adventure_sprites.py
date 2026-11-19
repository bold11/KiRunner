"""
Generates the original pixel art for ADVENTURE mode (all drawn by code, no outside art).

    python tools/make_adventure_sprites.py            # writes into app/src/main/res/drawable-nodpi
    python tools/make_adventure_sprites.py --preview  # also writes .tmp/adventure_preview.png

Sprites are saved at 1x (native pixel size). The game renders the world into a small
low-resolution buffer and scales it up with nearest-neighbour, so every pixel stays crisp.

  adv_hero_0..4.png   hero sheet per form: idle, run1, run2, jump, hurt   (5 frames x 20x28)
  adv_cloud.png       the hero's flying cloud for boss fights              (32x12)
  adv_blob.png        micro monster: blob   (2 frames x 16x16)  stomp or blast
  adv_flit.png        micro monster: flit   (2 frames x 16x14)  swoops at you
  adv_spike.png       micro monster: spikeball (2 frames x 16x16) blast only
  adv_tiles.png       16x16 tiles: grass, dirt, brick, power, used, cloud, spikes, crystal
  boss_warden.png     Boss 1: GRANITE WARDEN, a stone golem knight      (64x64)
  boss_jinx.png       Boss 2: JINX, a floating jester phantom            (64x64)
  boss_null.png       Boss 3: THE NULL KING, a hooded shadow monarch     (64x64)
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_pixel_sprites import Layer, OUTLINE, new_canvas, paint, rgb  # noqa: E402

from PIL import Image  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")


def tone(a, b, c):
    return (rgb(a), rgb(b), rgb(c))


def img(canvas):
    h, w = len(canvas), len(canvas[0])
    im = Image.new("RGBA", (w, h))
    im.putdata([px for row in canvas for px in row])
    return im


def sheet(frames):
    w, h = frames[0].size
    out = Image.new("RGBA", (w * len(frames), h))
    for i, f in enumerate(frames):
        out.paste(f, (i * w, 0))
    return out


def put(canvas, pts, c):
    for x, y in pts:
        if 0 <= y < len(canvas) and 0 <= x < len(canvas[0]):
            canvas[y][x] = c


# =============================================================== hero (side view, facing right)

SKIN = tone("ffe2c4", "ffcfa6", "e9a77f")
JACKET = tone("5fd3bd", "2f9e8f", "1d6a68")
SLEEVE = tone("9af0dc", "49c2ad", "1f7a70")
GOLD = tone("ffe07a", "ffc23c", "c9851e")
PANTS = tone("3a3570", "27234f", "17142f")
BOOTS = tone("c98a5a", "8f5a35", "5a3520")
WRAP = tone("ffffff", "eceaf6", "b9b5cf")

HERO_FORMS = [
    dict(hair=tone("fff7b8", "ffd84a", "c8951c"), eye="2ce5d8"),   # BASE   (yellow)
    dict(hair=tone("eadfff", "b89bff", "6f55c9"), eye="aa82ff"),   # STORM
    dict(hair=tone("d4ffe2", "62e59c", "24915b"), eye="5aeb96"),   # JADE
    dict(hair=tone("b98cff", "4a2f80", "24164a"), eye="f3c6ff"),   # VOID
    dict(hair=tone("ffffff", "f3f5fb", "b4bcd6"), eye="dff8ff"),   # ZENITH (white)
]
HW, HH = 20, 28


def hero_frame(form, pose):
    c = new_canvas(HW, HH)
    hair_t = form["hair"]

    # body (legs, torso, arms) first
    L = Layer(HW, HH)
    J, S, G, P, B, A, W = 1, 2, 3, 4, 5, 6, 7
    if pose == "idle" or pose == "hurt":
        L.rect(7, 21, 9, 24, P); L.rect(11, 21, 13, 24, P)
        L.rect(6, 25, 9, 26, B); L.rect(11, 25, 14, 26, B)
    elif pose == "run1":
        L.rect(5, 21, 7, 23, P); L.rect(12, 21, 14, 24, P)
        L.rect(3, 24, 6, 25, B); L.rect(12, 25, 15, 26, B)
    elif pose == "run2":
        L.rect(8, 21, 10, 24, P); L.rect(10, 21, 12, 23, P)
        L.rect(7, 25, 10, 26, B); L.rect(12, 23, 15, 24, B)
    elif pose == "jump":
        L.rect(6, 21, 8, 22, P); L.rect(11, 21, 13, 23, P)
        L.rect(4, 22, 6, 24, B); L.rect(12, 24, 15, 25, B)
    L.rect(6, 14, 13, 20, J)                     # jacket
    L.rect(6, 19, 13, 19, G)                     # sash
    put_layer = L
    if pose == "jump":
        L.rect(14, 9, 15, 14, A); L.rect(14, 8, 15, 8, S)      # arm raised
        L.rect(4, 14, 5, 18, A); L.rect(4, 19, 5, 19, W)
    elif pose == "hurt":
        L.rect(3, 12, 5, 15, A); L.rect(2, 11, 3, 11, S)
        L.rect(14, 12, 16, 15, A); L.rect(16, 11, 17, 11, S)
    else:
        swing = {"idle": 0, "run1": -1, "run2": 1}[pose]
        L.rect(4, 14, 5, 18 + swing, A); L.rect(4, 19 + swing, 5, 19 + swing, W); L.rect(4, 20 + swing, 5, 20 + swing, S)
        L.rect(14, 14, 15, 18 - swing, A); L.rect(14, 19 - swing, 15, 19 - swing, W); L.rect(14, 20 - swing, 15, 20 - swing, S)
    paint(c, put_layer, {J: JACKET, S: SKIN, G: GOLD, P: PANTS, B: BOOTS, A: SLEEVE, W: WRAP})
    put(c, [(9, 14), (10, 14), (9, 15)], rgb("1e1a3c"))   # collar

    # hair behind
    hair = Layer(HW, HH)
    hair.ellipse(10, 7, 6.5, 4.8, 1)
    for tx, ty in [(1, 5), (3, 1), (8, 0), (13, 0), (17, 2), (19, 7), (2, 10)]:
        cx, cy = 10, 7
        dx, dy = tx - cx, ty - cy
        d = math.hypot(dx, dy) or 1
        ux, uy = dx / d, dy / d
        bx, by = cx + ux * 3.5, cy + uy * 2.5
        px, py = -uy * 2, ux * 2
        hair.poly([(bx + px, by + py), (tx, ty), (bx - px, by - py)], 1)
    paint(c, hair, {1: hair_t})

    # head
    head = Layer(HW, HH)
    head.rect(6, 6, 14, 12, 1)
    head.rect(7, 13, 13, 13, 1)
    paint(c, head, {1: SKIN})
    # fringe sweeping right
    fr = Layer(HW, HH)
    fr.rect(6, 5, 14, 6, 1)
    for x0, tip in [(7, 9), (10, 8), (13, 9)]:
        fr.poly([(x0 - 2, 6.5), (x0 + 2, 6.5), (x0 + 0.5, tip)], 1)
    for y in range(HH):
        for x in range(HW):
            if fr.m[y][x]:
                c[y][x] = hair_t[1] if fr.get(x, y - 1) else hair_t[0]
    # face (looking right)
    iris = rgb(form["eye"])
    if pose == "hurt":
        put(c, [(9, 9), (10, 10), (10, 9), (9, 10), (12, 9), (13, 10), (13, 9), (12, 10)], OUTLINE)
    else:
        put(c, [(9, 8), (10, 8), (12, 8), (13, 8)], OUTLINE)            # brows
        put(c, [(10, 9), (10, 10), (13, 9), (13, 10)], iris)
        put(c, [(9, 9), (12, 9)], rgb("ffffff"))
    put(c, [(11, 12), (12, 12)], rgb("b5655a"))
    return img(c)


def cloud():
    c = new_canvas(32, 12)
    L = Layer(32, 12)
    for e in [(16, 7, 14, 4), (9, 5, 6, 3.5), (17, 4, 7, 3.5), (25, 5, 6, 3.5)]:
        L.ellipse(*e, 1)
    paint(c, L, {1: tone("ffffff", "e6def9", "aa9bd9")})
    return img(c)


# =============================================================== micro monsters

def blob(squash):
    c = new_canvas(16, 16)
    L = Layer(16, 16)
    if squash:
        L.ellipse(8, 11.5, 7.5, 4.2, 1)
    else:
        L.ellipse(8, 10.5, 6.5, 5.2, 1)
    L.poly([(4, 7 if not squash else 8.5), (6, 7 if not squash else 8.5), (4, 3 if not squash else 5)], 2)
    L.poly([(10, 7 if not squash else 8.5), (12, 7 if not squash else 8.5), (12, 3 if not squash else 5)], 2)
    paint(c, L, {1: tone("ff9ad8", "e0408f", "8a1c63"), 2: tone("fff4d6", "ffd27a", "c98a2a")})
    ey = 10 if not squash else 11
    put(c, [(5, ey - 1), (6, ey), (11, ey - 1), (10, ey)], OUTLINE)
    put(c, [(5, ey), (5, ey + 1), (10, ey + 1), (11, ey), (11, ey + 1)], rgb("fff27a"))
    put(c, [(7, ey + 3), (8, ey + 3), (9, ey + 3)], OUTLINE)
    return img(c)


def flit(up):
    c = new_canvas(16, 14)
    L = Layer(16, 14)
    L.ellipse(8, 7.5, 3.6, 3.4, 1)
    if up:
        L.poly([(5, 7), (0, 1), (2, 7), (1, 9)], 2)
        L.poly([(11, 7), (16, 1), (14, 7), (15, 9)], 2)
    else:
        L.poly([(5, 7), (0, 12), (3, 9), (1, 6)], 2)
        L.poly([(11, 7), (16, 12), (13, 9), (15, 6)], 2)
    L.poly([(6, 5), (7, 5), (6, 2)], 1)
    L.poly([(9, 5), (10, 5), (10, 2)], 1)
    paint(c, L, {1: tone("c6a3ff", "7a4fd6", "3e2585"), 2: tone("a07de8", "5a35b0", "2c1a66")})
    put(c, [(7, 7), (9, 7)], rgb("fff27a"))
    put(c, [(7, 9), (8, 9), (9, 9)], rgb("ffffff"))
    return img(c)


def spikeball(rot):
    c = new_canvas(16, 16)
    L = Layer(16, 16)
    L.ellipse(8, 8, 4.8, 4.8, 1)
    for k in range(8):
        a = k * math.pi / 4 + (math.pi / 8 if rot else 0)
        tx, ty = 8 + math.cos(a) * 7.6, 8 + math.sin(a) * 7.6
        px, py = -math.sin(a) * 1.5, math.cos(a) * 1.5
        bx, by = 8 + math.cos(a) * 4, 8 + math.sin(a) * 4
        L.poly([(bx + px, by + py), (tx, ty), (bx - px, by - py)], 2)
    paint(c, L, {1: tone("d9dce8", "8f94ab", "4c506a"), 2: tone("ffffff", "c4c8d8", "70748c")})
    put(c, [(6, 7), (7, 7), (8, 7), (9, 7), (10, 7)], OUTLINE)
    put(c, [(7, 8), (8, 8), (9, 8)], rgb("ff4f6a"))
    return img(c)


# =============================================================== tiles (16x16 each)

def tiles():
    T = 16
    names = ["grass", "dirt", "brick", "power", "used", "cloud", "spikes", "crystal"]
    out = Image.new("RGBA", (T * len(names), T))
    for i, n in enumerate(names):
        c = new_canvas(T, T)
        if n in ("grass", "dirt"):
            # cosmic rock: dark blue-violet stone with faint star specks
            for y in range(T):
                for x in range(T):
                    v = (x * 7 + y * 13) % 11
                    c[y][x] = rgb("2e2b5e") if v else rgb("25224d")
                    if (x * 3 + y * 5) % 17 == 0:
                        c[y][x] = rgb("3b3878")
            for x, y in [(3, 9), (11, 6), (7, 13), (13, 11), (2, 4)]:
                c[y][x] = rgb("6b68b8")
            c[12][5] = rgb("bfe9ff")
            if n == "grass":
                # glowing crystal moss on top, dripping down a little
                tops = [rgb("c9fff6"), rgb("6ff0dc"), rgb("2fb8b8"), rgb("1f6f86")]
                for y in range(4):
                    for x in range(T):
                        c[y][x] = tops[y]
                for x in range(0, T, 3):
                    c[4][x] = rgb("1f6f86")
                for x in (2, 9, 14):
                    c[5][x] = rgb("1f6f86")
                for x in (4, 11):
                    c[0][x] = rgb("ffffff")
        elif n == "brick":
            for y in range(T):
                for x in range(T):
                    mortar = y % 8 == 7 or (x + (4 if (y // 8) % 2 else 0)) % 8 == 7
                    c[y][x] = rgb("1c173a") if mortar else (rgb("9a8ae0") if y % 8 == 0 else rgb("5f50a8"))
            for x, y in [(2, 3), (10, 11), (13, 2)]:
                c[y][x] = rgb("c9bbff")
        elif n in ("power", "used"):
            base, hi, lo = (("ffc23c", "ffe58a", "b97a16") if n == "power" else ("7a6a7a", "9a8a9a", "4a3e4a"))
            for y in range(T):
                for x in range(T):
                    c[y][x] = rgb(base)
            for k in range(T):
                c[0][k] = c[k][0] = rgb(hi)
                c[T - 1][k] = c[k][T - 1] = rgb(lo)
            for x, y in [(1, 1), (14, 1), (1, 14), (14, 14)]:
                c[y][x] = rgb(lo)
            if n == "power":   # teal ki diamond
                for y in range(4, 12):
                    half = 4 - abs(y - 7.5)
                    for x in range(int(8 - half), int(8 + half)):
                        c[y][x] = rgb("2ce5d8")
                c[6][7] = c[7][7] = rgb("ffffff")
        elif n == "cloud":   # nebula platform
            L = Layer(T, T)
            L.ellipse(4, 5, 4.5, 4, 1); L.ellipse(11, 4.5, 5.5, 4.2, 1); L.rect(0, 5, 15, 8, 1)
            paint(c, L, {1: tone("ffe6ff", "c9a8ff", "7a5ad6")}, outline=False)
            for x, y in [(4, 3), (11, 2), (8, 6)]:
                c[y][x] = rgb("ffffff")
        elif n == "spikes":
            for k in range(4):
                L = Layer(T, T)
                L.poly([(k * 4, 16), (k * 4 + 2, 6), (k * 4 + 4, 16)], 1)
                paint(c, L, {1: tone("e8ffff", "7fe6ff", "2a7fb0")}, outline=False)
        elif n == "crystal":
            for y in range(T):
                for x in range(T):
                    c[y][x] = rgb("2a2050") if (x + y) % 9 else rgb("3d2f73")
            for k in range(T):
                c[0][k] = rgb("a07de8")
                c[1][k] = rgb("6a4fc0")
            for x, y in [(4, 6), (5, 7), (11, 10), (12, 11), (8, 13)]:
                c[y][x] = rgb("c6a3ff")
        out.paste(img(c), (i * T, 0))
    return out


# =============================================================== bosses (original designs, 64x64)

B = 64


def boss_warden():
    """GRANITE WARDEN: a hulking stone golem knight with a bronze helm and a glowing visor slit."""
    c = new_canvas(B, B)
    STONE = tone("b9b0a2", "857b6e", "4f473f")
    BRONZE = tone("ffd08a", "c98a3a", "7a4a1a")
    MOSS = tone("a6e07a", "5fae45", "2f6a2a")
    L = Layer(B, B)
    L.rect(20, 50, 28, 60, 1); L.rect(36, 50, 44, 60, 1)               # legs
    L.poly([(14, 26), (50, 26), (46, 52), (18, 52)], 1)                 # torso
    L.ellipse(9, 38, 8, 8.5, 1); L.ellipse(55, 38, 8, 8.5, 1)          # giant fists
    L.rect(12, 26, 18, 32, 1); L.rect(46, 26, 52, 32, 1)               # arms
    paint(c, L, {1: STONE})
    P = Layer(B, B)
    P.ellipse(16, 25, 7, 5, 1); P.ellipse(48, 25, 7, 5, 1)             # pauldrons
    P.rect(22, 6, 42, 24, 1)                                           # helm
    P.poly([(22, 6), (32, 0), (42, 6)], 1)
    P.rect(18, 58, 29, 62, 1); P.rect(35, 58, 46, 62, 1)               # boots
    paint(c, P, {1: BRONZE})
    for x in range(25, 40):
        c[14][x] = rgb("2ce5d8"); c[15][x] = rgb("8ff8f0")              # visor slit
    for y in range(12, 19):
        c[y][32] = rgb("2ce5d8")
    M = Layer(B, B)
    M.ellipse(24, 34, 3.5, 2.5, 1); M.ellipse(40, 44, 4, 2.5, 1); M.ellipse(8, 33, 3, 2, 1)
    paint(c, M, {1: MOSS}, outline=False)
    put(c, [(30, 30), (31, 31), (31, 32), (32, 33), (33, 34), (33, 35), (36, 38), (35, 39)], rgb("3a332c"))   # cracks
    put(c, [(28, 40), (29, 40), (30, 41), (34, 41), (35, 40), (36, 40)], rgb("2ce5d8"))                    # core rune
    return img(c)


def boss_jinx():
    """JINX: a floating jester phantom - big grinning mask, two-pointed belled hat, ghost tail, white gloves."""
    c = new_canvas(B, B)
    L = Layer(B, B)
    L.poly([(18, 34), (46, 34), (40, 52), (34, 60), (30, 56), (24, 52)], 1)   # ghost tail body
    paint(c, L, {1: tone("d6a8ff", "8f5ad6", "4a2a85")})
    R = Layer(B, B)
    for k in range(7):
        R.ellipse(17 + k * 5, 35, 3.2, 2.6, 1)                         # ruffled collar
    paint(c, R, {1: tone("ffffff", "f0ecff", "b6aee0")})
    M = Layer(B, B)
    M.ellipse(32, 24, 13, 12, 1)                                        # mask
    paint(c, M, {1: tone("ffffff", "f4f1fb", "c2bad8")})
    H = Layer(B, B)
    H.poly([(19, 18), (32, 12), (12, 2), (8, 10)], 1)                   # hat flap left (magenta)
    H.poly([(45, 18), (32, 12), (52, 2), (56, 10)], 2)                  # hat flap right (teal)
    H.rect(20, 12, 44, 16, 3)
    paint(c, H, {1: tone("ff8fd0", "e0408f", "8a1c63"), 2: tone("8ff8f0", "2ce5d8", "14807a"),
                 3: tone("ffe07a", "ffc23c", "c9851e")})
    BL = Layer(B, B)
    BL.ellipse(8.5, 9.5, 2.6, 2.6, 1); BL.ellipse(55.5, 9.5, 2.6, 2.6, 1)
    paint(c, BL, {1: tone("fff6c0", "ffc23c", "b97a16")})
    # diamond eyes + crescent grin
    for cx in (26, 38):
        for y in range(19, 26):
            half = 3 - abs(y - 22)
            for x in range(cx - half, cx + half + 1):
                c[y][x] = OUTLINE
        c[21][cx] = rgb("2ce5d8")
        c[22][cx] = rgb("2ce5d8")
    for x in range(23, 42):
        t = (x - 32) / 9.5
        y = int(round(29 + (1 - t * t) * 3))
        c[y][x] = OUTLINE
        if 24 < x < 40:
            c[y - 1][x] = rgb("8a1c63")
    for x in (27, 31, 35):
        c[30][x] = rgb("ffffff")
    # floating gloves
    G = Layer(B, B)
    G.ellipse(7, 44, 5, 4.5, 1); G.ellipse(57, 44, 5, 4.5, 1)
    G.rect(4, 38, 6, 41, 1); G.rect(58, 38, 60, 41, 1)
    paint(c, G, {1: tone("ffffff", "eeeaf8", "aaa2cc")})
    return img(c)


def boss_null():
    """THE NULL KING: a tall hooded shadow monarch with a golden crown and glowing magenta eyes."""
    c = new_canvas(B, B)
    CLOAK = tone("5a3f9a", "251a4a", "100b24")
    L = Layer(B, B)
    L.poly([(32, 8), (48, 22), (54, 62), (10, 62), (16, 22)], 1)       # cloak
    for k in range(6):                                                   # ragged hem
        x = 12 + k * 8
        L.poly([(x, 61), (x + 4, 63), (x + 8, 61)], 1)
    paint(c, L, {1: CLOAK})
    HD = Layer(B, B)
    HD.ellipse(32, 20, 11, 11, 1)                                        # hood
    paint(c, HD, {1: tone("6a4fb0", "33245f", "171030")})
    V = Layer(B, B)
    V.ellipse(32, 22, 7, 7.5, 1)                                         # void face
    paint(c, V, {1: (rgb("05030c"), rgb("05030c"), rgb("05030c"))}, shade=False, outline=False)
    put(c, [(28, 21), (29, 21), (29, 22), (35, 21), (36, 21), (35, 22)], rgb("ff5fd2"))
    put(c, [(28, 22), (36, 22)], rgb("ffd0f2"))
    CR = Layer(B, B)
    CR.rect(23, 6, 41, 10, 1)
    for x in (23, 29, 35, 41):
        CR.poly([(x - 2, 6), (x + 2, 6), (x, 0)], 1)
    paint(c, CR, {1: tone("fff0a0", "ffc23c", "b97a16")})
    put(c, [(32, 8), (26, 8), (38, 8)], rgb("ff5fd2"))                  # crown gems
    CL = Layer(B, B)
    CL.ellipse(32, 35, 4, 3, 1)                                          # golden clasp
    paint(c, CL, {1: tone("fff0a0", "ffc23c", "b97a16")})
    c[35][32] = rgb("ff5fd2")
    HN = Layer(B, B)
    HN.ellipse(8, 40, 4.5, 4, 1); HN.ellipse(56, 40, 4.5, 4, 1)          # floating hands
    paint(c, HN, {1: tone("8a7ad0", "4a3a8a", "241a4a")})
    O = Layer(B, B)
    O.ellipse(56, 31, 4, 4, 1)                                           # void orb
    paint(c, O, {1: tone("ffd0f2", "ff5fd2", "8a1c63")})
    for y in range(B):                                                   # purple rim light
        for x in range(B):
            if c[y][x] == OUTLINE and (x < 20 or x > 44) and y > 20:
                c[y][x] = rgb("3a2470")
    return img(c)


# =============================================================== main

def main():
    os.makedirs(OUT, exist_ok=True)
    poses = ["idle", "run1", "run2", "jump", "hurt"]
    outputs = {}
    for i, f in enumerate(HERO_FORMS):
        outputs["adv_hero_%d" % i] = sheet([hero_frame(f, p) for p in poses])
    outputs["adv_cloud"] = cloud()
    outputs["adv_blob"] = sheet([blob(False), blob(True)])
    outputs["adv_flit"] = sheet([flit(True), flit(False)])
    outputs["adv_spike"] = sheet([spikeball(False), spikeball(True)])
    outputs["adv_tiles"] = tiles()
    outputs["boss_warden"] = boss_warden()
    outputs["boss_jinx"] = boss_jinx()
    outputs["boss_null"] = boss_null()
    for name, im in outputs.items():
        im.save(os.path.join(OUT, name + ".png"))

    if "--preview" in sys.argv:
        os.makedirs(os.path.join(ROOT, ".tmp"), exist_ok=True)
        z = 5
        rows = [outputs["adv_hero_%d" % i] for i in range(5)]
        rows += [outputs["adv_tiles"], sheet([outputs["adv_blob"], outputs["adv_spike"]]), outputs["adv_flit"], outputs["adv_cloud"]]
        bosses = [outputs["boss_warden"], outputs["boss_jinx"], outputs["boss_null"]]
        W = max(max(r.width for r in rows), 64 * 3 + 20) * z + 40
        Hh = sum(r.height * z + 10 for r in rows) + 64 * z + 40
        prev = Image.new("RGBA", (W, Hh), (60, 46, 96, 255))
        y = 10
        for r in rows:
            prev.alpha_composite(r.resize((r.width * z, r.height * z), Image.NEAREST), (10, y))
            y += r.height * z + 10
        for i, b in enumerate(bosses):
            prev.alpha_composite(b.resize((64 * z, 64 * z), Image.NEAREST), (10 + i * (64 * z + 20), y))
        prev.save(os.path.join(ROOT, ".tmp", "adventure_preview.png"))
    print("wrote", len(outputs), "adventure sprites to", OUT)


if __name__ == "__main__":
    main()
