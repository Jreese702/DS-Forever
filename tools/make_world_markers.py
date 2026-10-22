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
  WowCompanion/Media/tint_glow.tga    (at1, the aggro tint) a soft glow round a nameplate: a bright edge line where the plate's box
                                      is, a halo fading outward, the middle faint (the plate stays readable over it)

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


def tint():
    w, h = 256, 64
    inset = 10                                       # the plate's box inside the texture (the add-on's OUTSET, scaled)
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = img.load()
    for y in range(h):
        for x in range(w):
            # the distance outside the inner rounded box (negative inside it)
            dx = max(inset - x, 0, x - (w - 1 - inset))
            dy = max(inset - y, 0, y - (h - 1 - inset))
            out = math.hypot(dx, dy)
            if out > 0:
                a = 0.85 * math.exp(-(out / 5.0) ** 2)            # the halo outside
            else:
                inside = min(x - inset, w - 1 - inset - x, y - inset, h - 1 - inset - y)
                a = 0.22 + 0.7 * math.exp(-(inside / 1.6) ** 2)    # the bright edge, the faint middle
            edge = min(x, w - 1 - x, y, h - 1 - y)
            if edge < 2:
                a *= edge / 2.0                                    # nothing at the texture's own edge
            px[x, y] = (255, 255, 255, int(round(255 * min(1.0, a))))
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
