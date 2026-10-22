"""The world markers' textures (cp21, the user's pick "D: beam + rings"): OUR OWN art, white, tinted by the add-on.
  WowCompanion/Media/marker_ring.tga  a glowing ring with a faint filled middle (stretched to the ground ellipse)
  WowCompanion/Media/marker_beam.tga  a soft vertical light beam: a bright core in a wide halo, fading upward, a soft foot
  WowCompanion/Media/marker_pin.tga   (cp22: the world markers are a pin again) a map pin, white shaded for the add-on's tint: a
                                      round head with a hole, tapering to its TIP at the texture's bottom middle, a dark rim
  WowCompanion/Media/quest_disc.tga   (cp27: the quest's objective on the compass) the classic quest-number disc: a gold-filled
                                      circle, lit from the top, a dark rim; the number drawn dark on it by the add-on
  WowCompanion/Media/marker_diamond.tga (cp32: the CURRENT checkpoint) a waypoint diamond, white shaded for the add-on's gold tint: a
                                      faceted rhombus (lit top left) with a dark rim, an inner bevel and a bright gem dot; its BOTTOM tip at
                                      the texture's bottom middle (the ground point)
  (marker_ring / marker_beam: cp21's, unused since cp22)
  WowCompanion/Media/tint_glow.tga    (at3, the aggro tint) the outline of Forever's nameplate health bar (chamfered corners, ~3 px
                                      out) with a soft glow outside, white for the add-on's tint; TINT_H of its 64 rows used

    python tools/make_world_markers.py
"""
import math
import os

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MEDIA = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanion", "Media")


def ring():
    n = 256
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    px = img.load()
    for y in range(n):
        for x in range(n):
            r = math.hypot((x + 0.5) / n - 0.5, (y + 0.5) / n - 0.5) * 2          # 0 at the middle, 1 at the texture's edge
            core = math.exp(-((r - 0.78) / 0.035) ** 2)                            # the bright line
            glow = 0.55 * math.exp(-((r - 0.78) / 0.12) ** 2)                      # its glow both sides
            fill = 0.16 * (1 - min(1.0, r / 0.78) ** 3)                             # a faint disc inside
            a = min(1.0, core + glow + fill)
            if r > 0.98:
                a *= max(0.0, (1.0 - r) / 0.02)                                     # nothing at the texture's own edge
            px[x, y] = (255, 255, 255, int(round(255 * a)))
    out = os.path.join(MEDIA, "marker_ring.tga")
    img.save(out, compression=None)
    return out


def beam():
    w, h = 64, 256
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = img.load()
    for y in range(h):
        v = 1 - (y + 0.5) / h                                                        # 1 at the foot (the bottom row), 0 at the top
        up = v ** 1.6                                                                # fading upward
        foot = min(1.0, (1 - v) / 0.04 + 0.35) if v > 0.96 else 1.0                 # a soft foot, no hard bottom line
        for x in range(w):
            u = abs((x + 0.5) / w - 0.5) * 2                                         # 0 at the centre line, 1 at the sides
            core = math.exp(-(u / 0.10) ** 2)
            halo = 0.5 * math.exp(-(u / 0.55) ** 2)
            side = max(0.0, 1 - u)
            a = min(1.0, (core + halo) * side) * up * foot
            px[x, h - 1 - y] = (255, 255, 255, int(round(255 * a)))
    out = os.path.join(MEDIA, "marker_beam.tga")
    img.save(out, compression=None)
    return out


# at3 (test72, the user: "it should be more conformed to the whole shape"): the glow follows Forever's nameplate health bar - its
# frame (UI-HUD-CoolDownManager-Bar-BG and the game's own selected border, read from the client: CHAMFERED corners, the outline about
# 3 px outside the bar, 4 on top). The texture covers the bar plus TINT_PAD on every side, at the bar's medium-size aspect
# (166 x 20 + 2 x 9: 184 x 38), so it is not distorted: drawn 256 wide, TINT_H of its 64 rows used (the add-on crops the rest).
TINT_BAR = (166.0, 20.0)                             # the health bar, UI units (NAME_PLATE_WIDTH 190 less 2 x 12; LARGE height 20)
TINT_PAD = 9.0                                       # the add-on's anchor outset (each side)
TINT_OUT = (3.0, 3.0, 3.0, 4.0)                      # the outline's outset from the bar: left, right, bottom, top
TINT_CHAMFER = 4.0                                   # the corner cut (UI units)
TINT_W = 256
TINT_H = int(round(TINT_W * (TINT_BAR[1] + 2 * TINT_PAD) / (TINT_BAR[0] + 2 * TINT_PAD)))


def tint():
    w, h = TINT_W, 64
    k = w / (TINT_BAR[0] + 2 * TINT_PAD)            # texture pixels per UI unit
    ss = 4
    # the outline's box in UI units, the frame's top-left at 0, 0 (y down)
    x0, x1 = TINT_PAD - TINT_OUT[0], TINT_PAD + TINT_BAR[0] + TINT_OUT[1]
    y0, y1 = TINT_PAD - TINT_OUT[3], TINT_PAD + TINT_BAR[1] + TINT_OUT[2]
    cx, cy, hx, hy = (x0 + x1) / 2, (y0 + y1) / 2, (x1 - x0) / 2, (y1 - y0) / 2
    c = TINT_CHAMFER
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = img.load()
    for py in range(TINT_H):
        for qx in range(w):
            acc = 0.0
            for sy in range(ss):
                for sx in range(ss):
                    x = (qx + (sx + 0.5) / ss) / k
                    y = (py + (sy + 0.5) / ss) / k
                    ax, ay = abs(x - cx), abs(y - cy)
                    # the signed distance to the chamfered box (UI units; > 0 outside)
                    d = max(ax - hx, ay - hy, (ax + ay - (hx + hy - c)) / math.sqrt(2))
                    line = math.exp(-(d / 0.8) ** 2)                       # the crisp outline, ~1.5 units
                    if d > 0:
                        glow = 0.6 * math.exp(-d / 2.6)                     # the soft glow outside
                    else:
                        glow = 0.18 * math.exp(d / 1.4)                     # a faint lip just inside
                    acc += min(1.0, line + glow)
            a = acc / (ss * ss)
            edge = min(qx, w - 1 - qx, py, TINT_H - 1 - py) / k
            if edge < 1.5:
                a *= max(0.0, edge / 1.5)                                  # nothing at the frame's own edge
            px[qx, py] = (255, 255, 255, int(round(255 * min(1.0, a))))
    out = os.path.join(MEDIA, "tint_glow.tga")
    img.save(out, compression=None)
    return out


def pin():
    n, ss = 128, 4                                   # drawn at 4x, box-filtered down (a crisp antialiased edge)
    N = n * ss
    cx, cy, r = N / 2, N * 0.36, N * 0.29           # the head
    tip = (N / 2, N - 0.5)                           # the tip: the bottom middle

    def shape(p, q, rr, ty):
        # inside the head circle or the triangle from the tip to its tangent points
        if (p - cx) ** 2 + (q - cy) ** 2 <= rr * rr:
            return True
        if q < cy or q > ty:
            return False
        dd = ty - cy
        half = rr * math.sqrt(max(0.0, 1 - (rr / dd) ** 2))          # the tangent chord's half width, at its height
        qt = cy + rr * rr / dd                                         # the tangent points' height
        if q < qt:
            return False
        return abs(p - cx) <= half * (ty - q) / (ty - qt)

    big = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    px = big.load()
    rim = 3.2 * ss
    for q in range(N):
        for p in range(N):
            x, y = p + 0.5, q + 0.5
            if not shape(x, y, r, tip[1]):
                continue
            hole = (x - cx) ** 2 + (y - cy) ** 2 <= (r * 0.36) ** 2
            inner = shape(x, y, r - rim, tip[1] - rim * 2.6)
            if hole:
                v = 0.30
            elif not inner:
                v = 0.32                                                         # the dark rim
            else:
                lx, ly = (x - (cx - r * 0.35)) / r, (y - (cy - r * 0.4)) / r     # lit from the top left
                v = 0.72 + 0.28 * math.exp(-(lx * lx + ly * ly) * 1.6)
                v *= 1 - 0.18 * max(0.0, (y - cy) / (tip[1] - cy))               # darker toward the tip
            g = int(round(255 * v))
            px[p, q] = (g, g, g, 255)
    img = big.resize((n, n), Image.BOX)
    out = os.path.join(MEDIA, "marker_pin.tga")
    img.save(out, compression=None)
    return out


def disc():
    n, ss = 64, 4
    N = n * ss
    c, r = N / 2, N / 2 - 1.5 * ss
    rim = 3.0 * ss
    big = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    px = big.load()
    for q in range(N):
        for p in range(N):
            x, y = p + 0.5, q + 0.5
            dd = math.hypot(x - c, y - c)
            if dd > r:
                continue
            if dd > r - rim:
                px[p, q] = (74, 44, 8, 255)                                       # the dark rim
                continue
            t = (y - (c - r)) / (2 * r)                                           # 0 top .. 1 bottom
            top, bot = (255, 226, 92), (226, 150, 12)
            col = [top[k] + (bot[k] - top[k]) * t for k in range(3)]
            lx, ly = (x - (c - r * 0.3)) / r, (y - (c - r * 0.45)) / r          # a soft highlight, top left
            h = 0.35 * math.exp(-(lx * lx + ly * ly) * 3.0)
            col = [min(255, v + (255 - v) * h) for v in col]
            px[p, q] = (int(col[0]), int(col[1]), int(col[2]), 255)
    img = big.resize((n, n), Image.BOX)
    out = os.path.join(MEDIA, "quest_disc.tga")
    img.save(out, compression=None)
    return out


def diamond():
    n, ss = 128, 4
    N = n * ss
    cx, cy = N / 2, N / 2
    hw, hh = N * 0.36, N / 2 - 1.0 * ss               # half width, half height (tips at the top and bottom edges)

    def inside(x, y, k):                              # inside the rhombus scaled by k about its centre
        return abs(x - cx) / (hw * k) + abs(y - cy) / (hh * k) <= 1

    big = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    px = big.load()
    for q in range(N):
        for p in range(N):
            x, y = p + 0.5, q + 0.5
            if not inside(x, y, 1.0):
                continue
            if not inside(x, y, 0.88):
                v = 0.30                                                     # the dark rim
            elif not inside(x, y, 0.80):
                v = 0.98                                                     # the bright bevel edge
            elif inside(x, y, 0.22):
                d = math.hypot(x - cx, y - cy) / (0.22 * hw)
                v = 1.0 - 0.25 * d                                           # the gem dot, bright
            elif not inside(x, y, 0.30):
                # the facets: the four quarters lit differently (top-left brightest), a soft fall-off to the edge
                left, top = x < cx, y < cy
                v = {(True, True): 0.95, (False, True): 0.80, (True, False): 0.72, (False, False): 0.58}[(left, top)]
            else:
                v = 0.40                                                     # the dark ring round the dot
            g = int(round(255 * v))
            px[p, q] = (g, g, g, 255)
    img = big.resize((n, n), Image.BOX)
    out = os.path.join(MEDIA, "marker_diamond.tga")
    img.save(out, compression=None)
    return out


if __name__ == "__main__":
    for p in (ring(), beam(), tint(), pin(), disc(), diamond()):
        print(p, os.path.getsize(p))
