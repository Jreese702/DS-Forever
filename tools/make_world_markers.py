"""The world markers' textures (cp21, the user's pick "D: beam + rings"): OUR OWN art, white, tinted by the add-on.
  WowCompanion/Media/marker_ring.tga  a glowing ring with a faint filled middle (stretched to the ground ellipse)
  WowCompanion/Media/marker_beam.tga  a soft vertical light beam: a bright core in a wide halo, fading upward, a soft foot
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


if __name__ == "__main__":
    for p in (ring(), beam(), tint()):
        print(p, os.path.getsize(p))
