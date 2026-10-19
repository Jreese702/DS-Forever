"""The compass bar's ground (WowCompanion/Media/compass_band.tga): OUR OWN art, a soft black band whose alpha fades smoothly to
every edge (a rounded band, blurred), as in the signed-off render F. A texture rather than stacked gradients, so the ends are a
real fade on every client (the Thor's test13 showed stepped boxes).

    python tools/make_compass_band.py
"""
import os

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanion", "Media", "compass_band.tga")
W, H = 512, 64               # stretched by the add-on to the band's size (860 x 76 spec px)
PEAK = 0.37                  # the band's darkest alpha (test15: "50% more transparent": half of cp3's 0.74)
END = 0.20                   # the share of the width each end fades over


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def main():
    # alpha = PEAK x across(x) x down(y), both reaching ZERO at the texture's own edges (test15: the blurred stadium was clipped by
    # the texture's top and bottom rows, which drew a hard line): across = a smooth plateau with soft ends, down = sin^2 (a soft
    # middle, nothing at the top or bottom row)
    import math
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    px = img.load()
    for y in range(H):
        v = math.sin(math.pi * (y + 0.5) / H) ** 2
        for x in range(W):
            u = (x + 0.5) / W
            h = smooth(u / END) if u < END else smooth((1 - u) / END) if u > 1 - END else 1.0
            px[x, y] = (0, 0, 0, int(round(255 * PEAK * h * v)))
    img.save(OUT, compression=None)
    print(OUT, os.path.getsize(OUT))


if __name__ == "__main__":
    main()
