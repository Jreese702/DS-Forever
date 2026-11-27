"""The sheet for the 1536 DS menu's shots (single screen mode's menu made for the top screen, pass 1): SingleMenuShotsTest writes each
frame (1280 x 720) and titles.txt ("name.png|title" a line); this lays them two to a row, a title row over each tile.

    DS_MENU_SHOTS=<OUT> gradle :app:testModernDebugUnitTest --tests com.jrees.wowcompanion.SingleMenuShotsTest
    python -B tools/render_ds_menu.py [OUT]            (default C:/dsf-work/r1536-shots/ds-menu)

Writes OUT/sheet.png. Needs PIL."""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

OUT = sys.argv[1] if len(sys.argv) > 1 else "C:/dsf-work/r1536-shots/ds-menu"
TW, TH = 800, 450
GAP = 20
TITLE = 40
HEAD = 70


def font(size, bold=False):
    for n in (("arialbd.ttf" if bold else "arial.ttf"), "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(n, size)
        except OSError:
            pass
    return ImageFont.load_default()


def main():
    rows = []
    with open(os.path.join(OUT, "titles.txt"), encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if "|" in line:
                rows.append(line.split("|", 1))
    cols = 2
    n = len(rows)
    lines = (n + cols - 1) // cols
    W = cols * TW + (cols + 1) * GAP
    H = HEAD + lines * (TITLE + TH + GAP) + GAP
    sheet = Image.new("RGB", (W, H), (22, 20, 18))
    d = ImageDraw.Draw(sheet)
    d.text((GAP, 18), "The DS menu on the top screen - pass 1 (direction C, the row rises): out of a fight, then in one",
           font=font(30, True), fill=(255, 230, 150))
    ft = font(19, True)
    for i, (name, title) in enumerate(rows):
        c, r = i % cols, i // cols
        x = GAP + c * (TW + GAP)
        y = HEAD + r * (TITLE + TH + GAP)
        fight = "fight" in name
        d.text((x, y + 10), title, font=ft, fill=(255, 130, 110) if fight else (235, 235, 235))
        im = Image.open(os.path.join(OUT, name)).convert("RGB").resize((TW, TH), Image.LANCZOS)
        sheet.paste(im, (x, y + TITLE))
        if fight:
            d.rectangle((x - 3, y + TITLE - 3, x + TW + 2, y + TITLE + TH + 2), outline=(200, 50, 40), width=3)
    p = os.path.join(OUT, "sheet.png")
    sheet.save(p, optimize=True)
    print(p, sheet.size)


if __name__ == "__main__":
    main()
