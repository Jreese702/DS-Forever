"""Evidence shots for the 1535 single screen polish (the user on test 1533, 2026-11-26: "I have tested the new single screen mode, I mostly
love it but I want to change some things ..." and the corner buttons: "I was thinking maybe we could add some touch buttons in the bottome
left and right"): the 1920 x 1080 top screen as the Thor shows it - the game (a capture), the add-ons' top-screen scene run for real
(ActionBar.lua nbar28 under test_top_castbar.py's stub game: the Wings at rest out of fights, in fights, our top cast bar; the HUD /
Party mode's rows / the compass as the scenes' labelled schematic boxes) and over it the app's play docks as the REAL Home views draw them
(SinglePlayShotsTest's layers: SINGLE_PLAY_SHOTS=<OUT>/layers), SingleFadeTest's fade frames (SINGLE_FADE_SHOTS=<OUT>/fade) and
SingleNpcShotsTest's loot pictures (SINGLE_SHOTS_DIR=<OUT>/npc); the 1533 pictures beside them as "before".

    python -B tools/render_single_polish.py [OUT_DIR]        (default C:/dsf-work/r1535-shots/single-polish)

Writes after_*.png and sheet.png (one title row per tile). Needs lupa and PIL; TOPCAST_ART as render_top_castbar."""
import os
import struct
import bisect
import io
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
OUT = sys.argv[1] if len(sys.argv) > 1 else "C:/dsf-work/r1535-shots/single-polish"
sys.argv = sys.argv[:1] + [OUT]
import render_single_play as rsp  # noqa: E402

rsp.OUT = OUT
rsp.LAYERS = os.path.join(OUT, "layers")
rsp.rtc.OUT = OUT
BEFORE = "C:/dsf-work/r1533-shots"
ICON_ART = os.environ.get("HOME_LAYOUT_ART", "E:/dsf-issues/bottom-artroot")
W, H = 1920, 1080


def wings_now(G):
    """the Wings as they stand now: the last laid frame, else the settled spot (NB.top.cur)"""
    NB = G.WowCompanion.nbar
    T = NB.top
    if not T.shown:
        return None
    if len(G.PLACED) > 0:
        e = G.PLACED[len(G.PLACED)]
        return float(e.lx), float(e.ly), float(e.rx), float(e.ry), float(e.s)
    c = T.cur
    return float(c.l.x), float(c.l.y), float(c.r.x), float(c.r.y), float(c.s) * float(T.m or 1)


def top(G, L, layer, hud=True):
    """the game, the scene's schematic pieces (no HUD bars out of fights: they hide), the Wings where they stand, our cast bar, the docks"""
    parts0 = rsp.twm.drawn_parts
    if not hud:
        rsp.twm.drawn_parts = lambda g: [p for p in parts0(g) if p[0] != "bars"]
    try:
        img = rsp.draw_top(G, L, None, wings=False)
    finally:
        rsp.twm.drawn_parts = parts0
    w = wings_now(G)
    if w:
        NB = G.WowCompanion.nbar
        for i, cx, cy in ((1, w[0], w[1]), (2, w[2], w[3])):
            rsp.rwm.draw_wings_half(img, NB, NB.f, i, cx, cy, w[4])
    p = os.path.join(rsp.LAYERS, layer)
    if os.path.exists(p):
        img.alpha_composite(Image.open(p).convert("RGBA"))
    else:
        print("  (no layer %s)" % p)
    return img


def save(img, name):
    p = os.path.join(OUT, name)
    img.convert("RGB").resize((img.width // 2, img.height // 2), Image.LANCZOS).save(p)
    print(p)
    return p


def shot_out():
    L, G, tick = rsp.scene(party=False)
    tick(30)
    save(top(G, L, "dock_out_solo.png", hud=False), "after_01_out_of_combat.png")
    L, G, tick = rsp.scene(party=True)
    tick(30)
    save(top(G, L, "dock_out.png", hud=False), "after_02_out_of_combat_party.png")


def shot_in():
    L, G, tick = rsp.scene(party=True, fight=True, cast="Frostbolt", dur=2.5)
    tick(70)
    save(top(G, L, "dock_in.png"), "after_03_in_combat.png")


def shot_fight_end():
    L, G, tick = rsp.scene(party=False, fight=True)
    tick(60)
    G.COMBAT = False
    G.PLACED = L.table()
    tick(6)                                            # ~0.1 s into REST_OUT_S: the Wings half way to rest, the row half way up
    save(top(G, L, "dock_row_half.png", hud=False), "after_04_fight_end_glide.png")


def shot_corners():
    L, G, tick = rsp.scene(party=False, fight=True)
    tick(60)
    save(top(G, L, "dock_corners_open.png"), "after_05_corners_open_in_a_fight.png")


def shot_fade():
    bg = rsp.base()
    for k in (33, 66, 100):
        p = os.path.join(OUT, "fade", "fade_in_%d.png" % k)
        if not os.path.exists(p):
            print("  (no fade frame %s)" % p)
            continue
        img = bg.copy()
        img.alpha_composite(Image.open(p).convert("RGBA"))
        save(img, "after_06_fade_in_%03d.png" % k)


class Pak:
    def __init__(self, path):
        self.data = open(path, "rb").read()
        magic, n, base = struct.unpack_from("<III", self.data, 0)
        self.ids = list(struct.unpack_from("<%dI" % n, self.data, 12))
        self.offs = list(struct.unpack_from("<%dI" % (n + 1), self.data, 12 + 4 * n))
        self.base = base

    def get(self, fid):
        i = bisect.bisect_left(self.ids, fid)
        if i >= len(self.ids) or self.ids[i] != fid:
            return None
        return Image.open(io.BytesIO(self.data[self.base + self.offs[i]:self.base + self.offs[i + 1]])).convert("RGBA")


def shot_icons():
    """the round face as the add-on masks it: the whole picture (nbar27) and its inner 0.07..0.93 (nbar28, the band's ICON_CROP), x3"""
    pak = Pak(os.path.join(ICON_ART, "icons.pak"))
    ring = os.path.join(ICON_ART, "ui", "cast", "frame_circle_normal.png")
    ring = Image.open(ring).convert("RGBA") if os.path.exists(ring) else None
    ids = [136048, 136026, 136052, 136051]
    d = 132
    out = Image.new("RGBA", (40 + len(ids) * (d + 24), 2 * (d + 50) + 20), (30, 26, 22, 255))
    dr = ImageDraw.Draw(out)
    for row, (crop, label) in enumerate(((0.0, "nbar27: the whole picture in the round mask (its baked square bevel shows)"),
                                         (0.07, "nbar28: its inner 0.07..0.93, as the bottom band (CastView ICON_CROP)"))):
        y = 10 + row * (d + 50)
        dr.text((20, y), label, fill=(240, 230, 200), font=rsp.font(18))
        for i, fid in enumerate(ids):
            im = pak.get(fid)
            if im is None:
                continue
            w, h = im.size
            im = im.crop((round(w * crop), round(h * crop), round(w * (1 - crop)), round(h * (1 - crop)))).resize((d - 14, d - 14), Image.LANCZOS)
            m = Image.new("L", im.size, 0)
            ImageDraw.Draw(m).ellipse((0, 0, im.width - 1, im.height - 1), fill=255)
            x = 20 + i * (d + 24)
            well = Image.new("RGBA", (d, d), (0, 0, 0, 0))
            ImageDraw.Draw(well).ellipse((4, 4, d - 5, d - 5), fill=(10, 8, 6, 255))
            out.alpha_composite(well, (x, y + 34))
            out.paste(im, (x + 7, y + 34 + 7), m)
            if ring is not None:
                out.alpha_composite(ring.resize((d, d), Image.LANCZOS), (x, y + 34))
    p = os.path.join(OUT, "after_07_icons_cropped.png")
    out.convert("RGB").save(p)
    print(p)


# ------------------------------------------------------------------------------------------------------------------------ the sheet
COLS, TW, GAP, TITLE = 3, 760, 16, 44
BG, TILE_BG, INK, HEAD = (22, 22, 26), (34, 34, 40), (236, 232, 220), (255, 205, 90)

SECTIONS = [
    ("1 + 5. No Nintendo bar; out of fights the Wings rest smaller, closer and higher, the room row slides up (XP bar hidden)", [
        (BEFORE + "/single/play/play_01_out_of_combat.png", "BEFORE 1533: our Nintendo bar, the game's XP bar and tracker"),
        ("after_01_out_of_combat.png", "AFTER: the Wings at rest, the room row, the quest card"),
        ("after_02_out_of_combat_party.png", "AFTER, Party mode on: the rows keep the HUD's place (no row)"),
    ]),
    ("2 + 5. The fight: the Wings glide to the fight spot, the row slides down (and back up after)", [
        (BEFORE + "/single/play/play_02_in_combat_wings.png", "BEFORE 1533: in a fight"),
        ("after_03_in_combat.png", "AFTER: in a fight - the chat right, the card folded"),
        ("after_04_fight_end_glide.png", "AFTER: the fight's end, 0.1 s - row half way up"),
    ]),
    ("9 + 10. The corner buttons (touch only): the tap-to-cast bar and the swirl's room row", [
        ("after_05_corners_open_in_a_fight.png", "In a fight: bar open (left), row asked up (swirl)"),
        ("after_01_out_of_combat.png", "Out of fights: both buttons at rest in the corners"),
    ]),
    ("2. Every window fades: a panel fading in (180 ms, rising 16 px), the dim easing", [
        (BEFORE + "/int1533/final/single-core/core_03_room_panel_bags.png", "BEFORE 1533: the panel pops in whole"),
        ("after_06_fade_in_033.png", "AFTER: 1/3 of the fade in"),
        ("after_06_fade_in_066.png", "AFTER: 2/3 of the fade in"),
    ]),
    ("4 + 6 + 3. See-through docks, the chat on the right, the focused-quest card (layer alone)", [
        ("layers/dock_out_solo.png|K", "The docks alone over black: grounds see-through"),
    ]),
    ("8. Looting no longer dims the game", [
        ("npc/npc_loot_1533_dim.png", "BEFORE 1533: the 0.55 dim + the window's own cover"),
        ("npc/npc_loot_1535_no_dim.png", "AFTER: the game undimmed under the loot window"),
    ]),
    ("7. The icons inside our round faces: the baked square bevel cropped as the bottom band does", [
        ("after_07_icons_cropped.png", "nbar27 above, nbar28 below (the real icons, x3)"),
    ]),
]


def tile(path, title):
    path, _, mode = path.partition("|")
    p = path if os.path.isabs(path) or ":" in path else os.path.join(OUT, path)
    if not os.path.exists(p):
        print("  (missing %s)" % p)
        return None
    im = Image.open(p).convert("RGBA")
    if mode == "K":
        k = Image.new("RGBA", im.size, (0, 0, 0, 255))
        k.alpha_composite(im)
        im = k
    im = im.convert("RGB")
    if im.width > TW:
        im = im.resize((TW, round(im.height * TW / im.width)), Image.LANCZOS)
    t = Image.new("RGB", (TW, TITLE + im.height), TILE_BG)
    d = ImageDraw.Draw(t)
    d.text((10, 11), title, fill=INK, font=rsp.font(20))
    t.paste(im, ((TW - im.width) // 2, TITLE))
    return t


def sheet():
    Wd = COLS * TW + (COLS + 1) * GAP
    blocks = []
    head = Image.new("RGB", (Wd, 70), BG)
    ImageDraw.Draw(head).text((GAP, 18), "DS Forever single screen polish (feat/single-polish, ActionBar nbar28)", fill=HEAD, font=rsp.font(32))
    blocks.append(head)
    for name, tiles in SECTIONS:
        hdr = Image.new("RGB", (Wd, 48), BG)
        ImageDraw.Draw(hdr).text((GAP, 12), name, fill=HEAD, font=rsp.font(24))
        blocks.append(hdr)
        ts = [t for t in (tile(p, ti) for p, ti in tiles) if t is not None]
        for r in range(0, len(ts), COLS):
            row = ts[r:r + COLS]
            hh = max(t.height for t in row)
            b = Image.new("RGB", (Wd, hh + GAP), BG)
            for i, t in enumerate(row):
                b.paste(t, (GAP + i * (TW + GAP), 0))
            blocks.append(b)
    out = Image.new("RGB", (Wd, sum(b.height for b in blocks)), BG)
    y = 0
    for b in blocks:
        out.paste(b, (0, y))
        y += b.height
    p = os.path.join(OUT, "sheet.png")
    out.save(p, optimize=True)
    print(p, out.size)


def main():
    os.makedirs(OUT, exist_ok=True)
    for fn in (shot_out, shot_in, shot_fight_end, shot_corners, shot_fade, shot_icons):
        fn()
    sheet()


if __name__ == "__main__":
    main()
