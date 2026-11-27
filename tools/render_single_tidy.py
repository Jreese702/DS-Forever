"""The tidy pass's before / after sheet (single screen mode, feat/single-tidy): full 1920 x 1080 top-screen frames of each state - the
play docks over the game (SinglePlayShotsTest's layers: before = feat/single-docks2's, after = this branch's), the chat's and the map's
open (before: the old fade, panel at half alpha rising 16 px; after: the dock zoom, SingleFade.zoomFit ported here, at 0 / 50 / 100 %),
and a row comparing the bottom screen's Home (SingleTidyShotsTest's bottom_home.png) with single mode at the same pixel size.

    python -B tools/render_single_tidy.py BEFORE_LAYERS AFTER_LAYERS TIDY_SHOTS OUT_DIR

BEFORE_LAYERS / AFTER_LAYERS: SINGLE_PLAY_SHOTS folders (dock_*.png, docks.json); TIDY_SHOTS: SINGLE_TIDY_SHOTS (docks_out.png,
panel_chat.png, panel_map.png, bottom_home.png, tidy.json). Writes frame_*.png (1920 x 1080) and sheet.png. Needs PIL.
SINGLE_TOP_PLAY = the game's frame (default the CORE shots' _bg_sharp.png)."""
import json
import os
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFont

W, H = 1920, 1080
BG = os.environ.get("SINGLE_TOP_PLAY") or "C:/dsf-work/r1533-shots/single/core/_bg_sharp.png"
R = 12            # SingleType.R
DIM = 0.55        # the panel's dim over the game (a room's look)
IN_MS, OUT_MS, FADE = 240.0, 200.0, 120.0


def font(n):
    for f in ("C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/arial.ttf"):
        if os.path.exists(f):
            return ImageFont.truetype(f, n)
    return ImageFont.load_default()


def game():
    g = Image.open(BG).convert("RGBA")
    return g.resize((W, H)) if g.size != (W, H) else g


def over(base, layer_path):
    img = base.copy()
    if os.path.exists(layer_path):
        img.alpha_composite(Image.open(layer_path).convert("RGBA"))
    return img


def dim(img, a):
    out = img.copy()
    out.alpha_composite(Image.new("RGBA", (W, H), (0, 0, 0, int(255 * a))))
    return out


def zoom_fit(dock, panel, g):
    """SingleFade.zoomFit: the view's scale, its translation and the cut in its own px"""
    k = max(0.0, min(1.0, g))
    b = [dock[i] + (panel[i] - dock[i]) * k for i in range(4)]
    pw, ph = panel[2] - panel[0], panel[3] - panel[1]
    sc = max((b[2] - b[0]) / pw, (b[3] - b[1]) / ph, 0.05)
    tx, ty = b[0] - panel[0] * sc, b[3] - panel[3] * sc
    return sc, tx, ty, b


def curve(t):
    """Motion.curve (FastOutSlowIn) approximated: a cubic bezier (0.4, 0, 0.2, 1) solved for x = t"""
    lo, hi = 0.0, 1.0
    for _ in range(40):
        m = (lo + hi) / 2
        x = 3 * (1 - m) ** 2 * m * 0.4 + 3 * (1 - m) * m * m * 0.2 + m ** 3
        lo, hi = (m, hi) if x < t else (lo, m)
    m = (lo + hi) / 2
    return 3 * (1 - m) * m * m + m ** 3


def zoom_frame(base, panel_img, dock, panel, frac):
    """the open's frame at [frac] of its time: the dim easing in, the panel drawn into the gliding box at one scale, cut round"""
    g = curve(frac)
    t = frac * IN_MS
    img = dim(base, DIM * min(1.0, t / 180.0))
    sc, tx, ty, b = zoom_fit(dock, panel, g)
    pw, ph = panel_img.size
    big = panel_img.resize((max(1, round(pw * sc)), max(1, round(ph * sc))), Image.LANCZOS).convert("RGBA")
    px, py = round(panel[0] * sc + tx), round(panel[1] * sc + ty)
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    layer.paste(big, (px, py))
    mask = Image.new("L", (W, H), 0)
    ImageDraw.Draw(mask).rounded_rectangle([round(b[0]), round(b[1]), round(b[2]) - 1, round(b[3]) - 1], R, fill=int(255 * min(1.0, t / FADE)))
    layer.putalpha(ImageChops.multiply(layer.getchannel("A"), mask))
    img.alpha_composite(layer)
    return img


def fade_frame(base, panel_img, panel, frac):
    """the old open at [frac]: the panel fading in over 180 ms, rising 16 px"""
    k = curve(min(1.0, frac * IN_MS / 180.0))
    img = dim(base, DIM * k)
    p = panel_img.convert("RGBA").copy()
    p.putalpha(int(255 * k))
    img.alpha_composite(p, (int(panel[0]), int(panel[1] + 16 * (1 - k))))
    return img


def label(img, text, sub=None):
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, 64], fill=(0, 0, 0, 200))
    d.text((24, 12), text, font=font(34), fill=(255, 215, 120, 255))
    if sub:
        d.text((24, 1080 - 50), sub, font=font(26), fill=(255, 255, 255, 255), stroke_width=2, stroke_fill=(0, 0, 0, 255))
    return img


def main():
    before_l, after_l, tidy, out = sys.argv[1:5]
    os.makedirs(out, exist_ok=True)
    base = game()
    tj = json.load(open(os.path.join(tidy, "tidy.json")))
    panel = [float(v) for v in tj["panel"]]
    chat_dock = [float(v) for v in tj["chat"]]
    map_dock = [float(v) for v in tj["map"]]
    pchat = Image.open(os.path.join(tidy, "panel_chat.png"))
    pmap = Image.open(os.path.join(tidy, "panel_map.png")).resize((int(panel[2] - panel[0]), int(panel[3] - panel[1])))
    rows = []
    for name, title in (("dock_out", "Out of combat, Party mode"), ("dock_out_solo", "Out of combat, solo (room row, corner buttons)"),
                        ("dock_in", "In combat"), ("dock_corners_open", "Corner buttons open in a fight")):
        b = label(over(base, os.path.join(before_l, name + ".png")), "BEFORE  " + title)
        a = label(over(base, os.path.join(after_l, name + ".png")), "AFTER  " + title)
        rows.append((b, a, name))
    # the opens: before = the fade, after = the zoom from the dock
    docks_b = over(base, os.path.join(before_l, "dock_out.png"))
    docks_a = over(base, os.path.join(after_l, "dock_out.png"))
    for what, pimg, dock in (("chat", pchat, chat_dock), ("map", pmap, map_dock)):
        for frac, tag in ((0.0, "0 ms"), (0.35, "84 ms"), (0.65, "156 ms"), (1.0, "240 ms, landed")):
            if frac == 0.0:
                b = docks_b.copy(); a = docks_a.copy()
            else:
                b = fade_frame(base, pimg, panel, frac)
                a = zoom_frame(base, pimg, dock, panel, frac)
            rows.append((label(b, "BEFORE  open the %s: fade + rise  (%s)" % (what, tag)),
                         label(a, "AFTER  open the %s: zoom out of its dock  (%s)" % (what, tag)), "%s_open_%d" % (what, int(frac * 100))))
    # the comparison row: the bottom screen's Home beside single mode's docks, the same px
    bh = Image.open(os.path.join(tidy, "bottom_home.png")).convert("RGBA")
    left = Image.new("RGBA", (W, H), (20, 18, 16, 255))
    left.alpha_composite(bh, ((W - bh.width) // 2, 0))
    rows.append((label(left, "BOTTOM SCREEN  Home (1240 wide): chat 27, card 34 / 29, party 24"),
                 label(docks_a.copy(), "SINGLE MODE (after)  chat 26, card 30 / 26, party 26 - SingleType"), "compare"))
    for b, a, n in rows:
        b.convert("RGB").save(os.path.join(out, "frame_%s_before.png" % n))
        a.convert("RGB").save(os.path.join(out, "frame_%s_after.png" % n))
    sheet = Image.new("RGB", (W * 2 + 24, len(rows) * (H + 24)), (12, 12, 12))
    for i, (b, a, _) in enumerate(rows):
        sheet.paste(b.convert("RGB"), (0, i * (H + 24)))
        sheet.paste(a.convert("RGB"), (W + 24, i * (H + 24)))
    sheet.save(os.path.join(out, "sheet.png"), optimize=True)
    print("sheet", os.path.join(out, "sheet.png"), sheet.size)


if __name__ == "__main__":
    main()
