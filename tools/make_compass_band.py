"""The compass bar's ground (WowCompanion/Media/compass_band.tga): OUR OWN art, a soft black band whose alpha fades smoothly to
every edge (a rounded band, blurred), as in the signed-off render F. A texture rather than stacked gradients, so the ends are a
real fade on every client (the Thor's test13 showed stepped boxes).

    python tools/make_compass_band.py
"""
import os

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanion", "Media", "compass_band.tga")
W, H = 512, 64               # stretched by the add-on to the band's size (860 x 76 spec px)
PEAK = 0.74                  # the band's darkest alpha (F: 175 / 255)


def main():
    mask = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(mask)
    # a stadium inset from every edge, blurred: the alpha falls off smoothly toward the ends and the top / bottom
    d.rounded_rectangle((28, 9, W - 29, H - 10), radius=(H - 18) // 2, fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(8))
    alpha = mask.point(lambda v: int(v * PEAK))
    img = Image.merge("RGBA", (Image.new("L", (W, H), 0),) * 3 + (alpha,))
    img.save(OUT, compression=None)
    print(OUT, os.path.getsize(OUT))


if __name__ == "__main__":
    main()
