"""The world markers' textures (cp21, the user's pick "D: beam + rings"): OUR OWN art, white, tinted by the add-on.
  WowCompanion/Media/marker_ring.tga  a glowing ring with a faint filled middle (stretched to the ground ellipse)
  WowCompanion/Media/marker_beam.tga  a soft vertical light beam: a bright core in a wide halo, fading upward, a soft foot

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


if __name__ == "__main__":
    for p in (ring(), beam()):
        print(p, os.path.getsize(p))
