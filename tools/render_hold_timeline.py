"""Hold to cast round 1533 (PadMap m199): the drawn timeline of a HOLD (fired at the fill by the launcher) against a TAP, and the fire
point table F(n, f) = n x (200 + f) + f + 10 ms. Evidence only (PIL), no game, no device.

    python -B tools/render_hold_timeline.py OUT_DIR

The user on test 1532, 2026-11-25: "I also want this to cast right when the hold bar is filled, not when its released"."""
import os
import sys

from PIL import Image, ImageDraw, ImageFont


def fire_ms(n, f):
    return n * (200 + f) + f + 10


def font(n):
    for name in ("arial.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(name, n)
        except Exception:
            pass
    return ImageFont.load_default()


def main(out):
    os.makedirs(out, exist_ok=True)
    W, H = 1500, 760
    img = Image.new("RGB", (W, H), (18, 22, 30))
    d = ImageDraw.Draw(img)
    f18, f15, f13 = font(18), font(15), font(13)
    ink, dim, gold, blue, green, red = (236, 224, 196), (150, 144, 128), (255, 199, 77), (120, 170, 255), (130, 210, 140), (240, 110, 90)
    x0, x1, ms1 = 140, 1440, 1100

    def X(ms):
        return x0 + (x1 - x0) * ms / ms1

    d.text((24, 16), "Hold to cast, round 1533: the hold casts AT THE FILL (Normal, 3 looks, game at 60 fps: F = 678 ms)", fill=ink, font=f18)
    # the time axis
    for ms in range(0, ms1 + 1, 100):
        d.line((X(ms), 70, X(ms), 470), fill=(40, 46, 58))
        d.text((X(ms) - 12, 474), "%d" % ms, fill=dim, font=f13)
    d.text((x1 - 30, 492), "ms", fill=dim, font=f13)

    def lane(y, title):
        d.text((24, y - 9), title, fill=ink, font=f18)
        d.line((x0, y, x1, y), fill=(70, 76, 90), width=2)

    def mark(ms, y, label, col, up=True, dy=0):
        d.line((X(ms), y - 14, X(ms), y + 14), fill=col, width=3)
        ty = y - 40 - dy if up else y + 18 + dy
        tx = X(ms) - 4
        tw = d.textlength(label, font=f13) if hasattr(d, "textlength") else 7 * len(label)
        if tx + tw > W - 12:
            tx = X(ms) + 4 - tw                                         # (near the right edge: the label ends at its mark)
        d.text((tx, ty), label, fill=col, font=f13)

    # HOLD lane
    yh = 170
    lane(yh, "HOLD")
    F = fire_ms(3, 17)
    d.rectangle((X(250), yh - 6, X(F), yh + 6), fill=(110, 84, 30))
    d.text((X(250) + 6, yh + 10), "the charge ring fills (0.25 s -> F)", fill=gold, font=f13)
    mark(0, yh, "press: written at once; the router KEEPS it", ink)
    d.rectangle((X(50), yh - 64, X(350), yh - 52), outline=blue)
    d.text((X(50), yh - 84), "keep word reaches the app (about 50-350 ms)", fill=blue, font=f13)
    for i, t in enumerate((217, 434, 651)):
        mark(t, yh, "look %d" % (i + 1), dim, up=False, dy=(i % 2) * 16)
    mark(F, yh, "F = 678: the app writes A's UP (still held)", gold, dy=34)
    mark(F + 17, yh, "game reads it -> slothold: the hold casts", green, up=False, dy=40)
    d.rectangle((X(F + 17), yh + 74, X(1050), yh + 86), fill=(70, 60, 25))
    d.text((X(F + 17), yh + 90), "main circle: the hold spell in the game's gold checked ring (cast / GCD / queued)", fill=gold, font=f13)
    mark(980, yh, "physical release: swallowed (no edge)", red, dy=0)
    # TAP lane
    yt = 380
    lane(yt, "TAP")
    mark(0, yt, "press: written at once; kept", ink)
    mark(200, yt, "release at 200: written at once", ink, up=False)
    mark(217, yt, "router's tap on the next frame (the normal spell)", green, dy=20)
    d.text((X(300), yt - 6), "no delay added; buttons with no hold: untouched", fill=dim, font=f15)

    # the table
    ty = 540
    d.text((24, ty), "The fire point F(n, f) = n x (200 + f) + f + 10 ms (f = the game's longest recent frame; 25 when unknown)", fill=ink, font=f15)
    cols = [("game fps (f ms)", 24), ("Short (n = 2)", 330), ("Normal (n = 3)", 560), ("Long (n = 4)", 790)]
    for t, x in cols:
        d.text((x, ty + 30), t, fill=gold, font=f15)
    for i, (fps, f) in enumerate(((60, 17), (50, 20), (40, 25), (30, 34), (20, 50))):
        y = ty + 58 + i * 26
        d.text((24, y), "%d (%d)%s" % (fps, f, "  - also unknown" if f == 25 else ""), fill=ink, font=f15)
        for (t, x), n in zip(cols[1:], (2, 3, 4)):
            d.text((x, y), "%d ms" % fire_ms(n, f), fill=ink, font=f15)
    p = os.path.join(out, "timeline_fire_vs_tap.png")
    img.save(p)
    print(p)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
