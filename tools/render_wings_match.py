"""Evidence shots for ActionBar nbar17 (the Wings as the band on the device), drawn with the REAL art - never placeholder boxes for the
buttons' pictures: the plates, reticles, glyphs and chips are composited from the files each texture names, at the size, anchor and
texcoords the add-on itself set (the REAL ActionBar.lua run under tools/test_wings_match.py's stub, frame by frame); "before" = the
same driver on the 1523 add-on (git show 0f57b547), "after" = the working tree. The band (the bottom screen) is CastView's own drawing
of its L / R plate (the vector path, UiKit.ringShape's ring_square edge, drawReticle, the Gamepad_Rev glyphs, triggerChip at 0.55).
Everything at one physical scale (mm on each panel: top 0.06918 mm a px, bottom 0.06055).

    python -B tools/render_wings_match.py [OUT_DIR]          (default C:/dsf-work/r1524-shots/wings)

  01_plates_band_vs_wings.png   the band's L / R plates and ZL / ZR chips vs the Wings' - 1523 and now - on a 1 mm grid, with what each
                                measures (the plate's drawn body, its aspect, every texture's stretch)
  02_slide_timeline.png         both screens stacked as on the Thor at 0 / 25 / 50 / 75 / 100 % of the way up (the switch to the band's
                                landing), 1523 above, now below, with each side's travel in mm
  03_party_<n>_members.png      Party-mode Top fights (2 / 3 / 5 members; the block right of the centre): the top screen at the fight's
                                start, mid-move, settled and after a late join / pet, 1523 vs now - every frame's hits counted
Needs lupa and PIL, and the harvested game art (ART below: the band's art root's ui folder)."""
import math
import os
import subprocess
import sys
import tempfile

from PIL import Image, ImageChops, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import test_wings_match as twm  # noqa: E402

ROOT = os.path.dirname(HERE)
ART = os.environ.get("WINGS_ART", "E:/wow-build/runtime-art/r1/files/ui")
MEDIA = twm.MEDIA
OUT = sys.argv[1] if len(sys.argv) > 1 else "C:/dsf-work/r1524-shots/wings"
MM_TOP, MM_BOT = twm.MM_TOP, twm.MM_BOTTOM
B2T = MM_BOT / MM_TOP
BEFORE_REV = "0f57b547"
AB_REL = "companion/app/src/main/assets/companion/addons/WowCompanion/ActionBar.lua"
ATLAS_FILE = {
    "gamepad-targeting-left-shoulder-bg": "cast/badge_shoulder_left_bg.png", "gamepad-targeting-right-shoulder-bg": "cast/badge_shoulder_right_bg.png",
    "gamepad-targeting-left-shoulder-pressed": "cast/badge_shoulder_left_pressed.png",
    "gamepad-targeting-right-shoulder-pressed": "cast/badge_shoulder_right_pressed.png",
    "gamepad-targeting-friendly": "cast/badge_friendly.png", "gamepad-targeting-hostile": "cast/badge_hostile.png",
    "gamepad-targeting-shortcuts": "cast/badge_shortcuts.png",
    "gamepad-switch-128x-zl-normal": "cast/glyph_nintendo_lt.png", "gamepad-switch-128x-zr-normal": "cast/glyph_nintendo_rt.png",
    "gamepad-switch-128x-shoulder-l-normal": "cast/glyph_nintendo_lb.png", "gamepad-switch-128x-shoulder-r-normal": "cast/glyph_nintendo_rb.png",
    "Gamepad_Rev_LShoulder_64": "kit/pad_rev_lshoulder.png", "Gamepad_Rev_RShoulder_64": "kit/pad_rev_rshoulder.png",
    "Gamepad_Rev_LTrigger_64": "kit/pad_rev_ltrigger.png", "Gamepad_Rev_RTrigger_64": "kit/pad_rev_rtrigger.png",
}
_cache = {}


def art(path):
    if path not in _cache:
        _cache[path] = Image.open(path).convert("RGBA")
    return _cache[path]


def font(n):
    try:
        return ImageFont.truetype("arial.ttf", n)
    except Exception:
        return ImageFont.load_default()


def before_ab():
    p = os.path.join(tempfile.gettempdir(), "ActionBar_%s.lua" % BEFORE_REV)
    if not os.path.exists(p):
        data = subprocess.check_output(["git", "-C", ROOT, "show", "%s:%s" % (BEFORE_REV, AB_REL)])
        open(p, "wb").write(data)
    return p


def boot(ab):
    """test_wings_match's scene on the ActionBar at [ab]"""
    real = twm.os.path.join(twm.ADDONS, "WowCompanion", "ActionBar.lua")
    L = twm.lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(twm.STUB)
    L.execute(twm.SCENE)
    L.execute("PADMAP_ON()")
    load = L.eval('function(src, name, a) local f, e = loadstring(src, name); if not f then error(e) end; return f(a) end')
    load(open(os.path.join(twm.ADDONS, "WowCompanion", "Safety.lua"), encoding="utf-8").read(), "@Safety.lua", "WowCompanion")
    load(open(ab or real, encoding="utf-8").read(), "@ActionBar.lua", "WowCompanion")
    L.execute(twm.SIM)
    return L


# ------------------------------------------------------------------------------------------------------------ a texture -> pixels
def tex_image(t, w, h):
    """texture [t] drawn in a w x h px box as the client would: its file (texcoords applied, a mirrored rect flipped) or its atlas's
    picture stretched into the box; a colour texture filled; None when it has nothing"""
    w, h = max(1, int(round(w))), max(1, int(round(h)))
    if t.tex:
        p = str(t.tex)
        fn = os.path.join(MEDIA, p.split("\\")[-1] + ("" if p.lower().endswith(".tga") else ".tga"))
        im = art(fn)
        tc = [float(t.tcoord[i]) for i in range(1, 5)] if t.tcoord else [0.0, 1.0, 0.0, 1.0]
        l, r, u, b = tc
        box = (int(round(min(l, r) * im.width)), int(round(min(u, b) * im.height)), int(round(max(l, r) * im.width)),
               int(round(max(u, b) * im.height)))
        im = im.crop(box)
        if l > r:
            im = im.transpose(Image.FLIP_LEFT_RIGHT)
        return im.resize((w, h), Image.LANCZOS)
    if t.atlas:
        f = ATLAS_FILE.get(str(t.atlas))
        if not f:
            return None
        return art(os.path.join(ART, f)).resize((w, h), Image.LANCZOS)
    if t.color is not None:
        c = [float(t.color[i]) for i in range(1, 5)]
        return Image.new("RGBA", (w, h), tuple(int(v * 255) for v in c))
    return None


def put(dst, im, cx, cy, alpha=1.0, tint=None):
    if im is None:
        return
    if tint is not None:
        r, g, b, a = im.split()
        im = Image.merge("RGBA", (r.point(lambda v: int(v * tint[0])), g.point(lambda v: int(v * tint[1])),
                                  b.point(lambda v: int(v * tint[2])), a))
    if alpha < 1:
        im = im.copy()
        im.putalpha(im.getchannel("A").point(lambda v: int(v * alpha)))
    layer = Image.new("RGBA", dst.size, (0, 0, 0, 0))
    layer.paste(im, (int(round(cx - im.width / 2)), int(round(cy - im.height / 2))))
    dst.alpha_composite(layer)


def child_box(t, pw, ph):
    """a texture's (dx, dy, w, h) in bar units from its parent's centre (y up): SetAllPoints = the parent's box; CENTER points"""
    pts = t.pts
    if pts and pts[1] and pts[1][1] == "ALL":
        return 0.0, 0.0, pw, ph
    dx = dy = 0.0
    if pts and pts[1] and pts[1][1] == "CENTER":
        dx, dy = float(pts[1][4] or 0), float(pts[1][5] or 0)
    return dx, dy, float(t.w or pw), float(t.h or ph)


def draw_outer(dst, f, i, cx, cy, s, held=False):
    """half [i]'s plate (with its reticle and bumper glyph) and its ZL / ZR chip, the plate's centre at (cx, cy) px, s px a unit"""
    p, c = f.plate[i], f.chip[i]
    pw, ph = float(p.w), float(p.h)
    for t, show in ((p.bg, True), (p.held, held), (p.icon, p.icon.shown), (p.glyph, p.glyph.shown)):
        if not show or t is None:
            continue
        dx, dy, w, h = child_box(t, pw, ph)
        put(dst, tex_image(t, w * s, h * s), cx + dx * s, cy - dy * s)
    # the chip: its centre from both frames' anchors on the bar (CENTER, f, CENTER, x, y)
    pp, cp = p.pts[len(p.pts)], c.pts[len(c.pts)]
    ccx, ccy = cx + (float(cp[4]) - float(pp[4])) * s, cy - (float(cp[5]) - float(pp[5])) * s
    cw, ch = float(c.w), float(c.h)
    dx, dy, w, h = child_box(c.g, cw, ch)
    put(dst, tex_image(c.g, w * s, h * s), ccx + dx * s, ccy - dy * s, alpha=float(c.g.alpha or 1))
    return (ccx, ccy)


def wings_state(ab):
    """the add-on's Wings in a fight (shown, settled) for the ActionBar at [ab]"""
    L = boot(ab)
    G = L.globals()
    NB = G.WowCompanion.nbar
    tick = twm.ticker(L, 1 / 60)
    tick(30)
    G.COMBAT = True
    tick(40)
    NB.topDeco()
    return L, NB


# ------------------------------------------------------------------------------------------------------------ the band's plate
def band_outer(scale, side=0, held=False):
    """the band's plate [side] with its bumper glyph and the trigger glyph under it (CastView plateAt / drawPlate / plateRing / drawReticle
    / UiKit.pad / drawTriggerStrip at Diamonds' rest, 1240 wide: ps 1), [scale] output px per bottom px -> (image, plate centre)"""
    SS = 4
    k = scale * SS
    unit = 124.0 / 39.0
    W, H = int(200 * k), int(220 * k)
    cx, cy = 100 * k, 60 * k
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    U = unit * k

    def P(x, y):
        return (cx + x * U, cy + y * U)
    pts = [(19.5, -15.5), (-9, -15.5), (-19.5, -5), (-19.5, 15.5), (19.5, 15.5)]
    if side == 1:
        pts = [(-x, y) for x, y in pts]
    poly = [P(x, y) for x, y in pts]
    ImageDraw.Draw(im).polygon(poly, fill=(58, 46, 26, 255) if held else (28, 22, 15, 255))
    ring = art(os.path.join(ART, "kit/ring_square.png"))
    C, S = 14, ring.width
    o, cw = 1.6 * k, 14 * 1.6 * k
    L_, T_, R_, B_ = cx - 19.5 * U, cy - 15.5 * U, cx + 19.5 * U, cy + 15.5 * U
    strips = Image.new("RGBA", im.size, (0, 0, 0, 0))

    def sc(src, box):
        l, t, r, b = box
        x = src.resize((max(1, round(r - l)), max(1, round(b - t))), Image.LANCZOS)
        layer = Image.new("RGBA", strips.size, (0, 0, 0, 0))
        layer.paste(x, (round(l), round(t)))
        strips.alpha_composite(layer)
    sc(ring.crop((C, 0, S - C, C)), (L_, T_ - o, R_, T_ - o + cw))
    sc(ring.crop((C, S - C, S - C, S)), (L_, B_ + o - cw, R_, B_ + o))
    sc(ring.crop((0, C, C, S - C)), (L_ - o, T_, L_ - o + cw, B_))
    sc(ring.crop((S - C, C, S, S - C)), (R_ + o - cw, T_, R_ + o, B_))
    a_, b_ = ((-9, -15.5), (-19.5, -5)) if side == 0 else ((9, -15.5), (19.5, -5))
    ax, ay = P(*a_)
    bx, by = P(*b_)
    mx, my = (ax + bx) / 2, (ay + by) / 2
    dx, dy = bx - ax, by - ay
    if (-dy) * (cx - mx) + dx * (cy - my) < 0:
        ax, ay, bx, by = bx, by, ax, ay
    ang = math.atan2(by - ay, bx - ax)
    ln = math.hypot(bx - ax, by - ay)
    ext = cw * 0.5
    strip = ring.crop((C, 0, S - C, C)).resize((max(1, round(ln + 2 * ext)), max(1, round(cw))), Image.LANCZOS)
    co, si = math.cos(ang), math.sin(ang)
    rot = strip.transform(im.size, Image.AFFINE, (co, si, -co * ax - si * ay + ext, -si, co, si * ax - co * ay + o), resample=Image.BILINEAR)
    strips.alpha_composite(rot)
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).polygon(poly, fill=255)
    strips.putalpha(ImageChops.multiply(strips.getchannel("A"), mask))
    im.alpha_composite(strips)
    d = ImageDraw.Draw(im)
    rx, ry = P(0, -1)
    col = (96, 214, 80, 255) if side == 0 else (235, 60, 50, 255)
    d.ellipse((rx - 5.2 * U, ry - 5.2 * U, rx + 5.2 * U, ry + 5.2 * U), fill=(58, 52, 46, 255))
    r2, sw = 6.6 * U, 1.5 * U
    d.ellipse((rx - r2 - sw / 2, ry - r2 - sw / 2, rx + r2 + sw / 2, ry + r2 + sw / 2), outline=col, width=round(sw))
    for i in range(4):
        th = math.radians(90 * i)
        px, py = rx + math.sin(th) * r2, ry - math.cos(th) * r2
        h = 1.3 * math.sqrt(2) * U
        d.polygon([(px, py - h), (px + h, py), (px, py + h), (px - h, py)], fill=col)
    g = art(os.path.join(ART, "kit/pad_rev_lshoulder.png" if side == 0 else "kit/pad_rev_rshoulder.png"))
    gx = (R_ - 10 * k) if side == 0 else (L_ + 10 * k)
    gy = B_ - 9 * k
    gs = 46 * k
    put(im, g.resize((round(gs), round(gs)), Image.LANCZOS), gx, gy)
    # the trigger glyph: TRIG_X 76 (the plate's centre), its centre at the view's 218 - 18 - 60 = 140 (the plate's at 55.3): 84.7 below
    t = art(os.path.join(ART, "kit/pad_rev_ltrigger.png" if side == 0 else "kit/pad_rev_rtrigger.png"))
    cs = 56 * 0.92 * k
    put(im, t.resize((round(cs), round(cs)), Image.LANCZOS), cx, cy + 84.7 * k, alpha=0.55)
    im = im.resize((W // SS, H // SS), Image.LANCZOS)
    return im, (cx / SS, cy / SS)


def grid_cell(w, h, mmpx, bg=(70, 96, 70, 255), line=(84, 112, 84, 255)):
    im = Image.new("RGBA", (w, h), bg)
    d = ImageDraw.Draw(im)
    x = 0.0
    while x <= w:
        d.line((x, 0, x, h), fill=line)
        x += mmpx
    y = 0.0
    while y <= h:
        d.line((0, y, w, y), fill=line)
        y += mmpx
    return im


def measure(f, i, s):
    """the drawn plate body (mm, aspect) and the worst texture stretch of half [i]"""
    p, c = f.plate[i], f.chip[i]
    kind, name, wh, body = twm.tex_src(p.bg)
    dw, dh = twm.drawn(p.bg)
    bw = dw * (body[2] - body[0]) / wh[0] if body else dw
    bh = dh * (body[3] - body[1]) / wh[1] if body else dh
    worst = 1.0
    for t in (p.bg, p.icon, p.glyph, c.g):
        k_, n_, wh_, _ = twm.tex_src(t)
        w_, h_ = twm.drawn(t)
        if wh_ and w_ and h_:
            r = (w_ / h_) / (wh_[0] / wh_[1])
            if abs(r - 1) > abs(worst - 1):
                worst = r
    return bw * s * MM_TOP, bh * s * MM_TOP, bw / bh, worst


def shot_plates():
    D = 3.2                                 # output px per top-screen px
    mmpx = D / MM_TOP
    Lb, NBb = wings_state(before_ab())
    La, NBa = wings_state(None)
    s = 1.75
    cellw, cellh = 760, 560
    cols = [("BOTTOM band (CastView)", None), ("TOP Wings 1523 (nbar16)", NBb), ("TOP Wings now (nbar17)", NBa)]
    out = Image.new("RGBA", (20 + 3 * (cellw + 20), 80 + 2 * (cellh + 150)), (44, 50, 44, 255))
    d = ImageDraw.Draw(out)
    d.text((20, 18), "The L / R plates and ZL / ZR chips at ONE physical scale (grid = 1 mm; top px x 0.06918 mm, bottom px x 0.06055 mm), "
                     "real art", fill=(240, 230, 200), font=font(24))
    for row, side in enumerate((0, 1)):
        for col, (title, NB) in enumerate(cols):
            x0, y0 = 20 + col * (cellw + 20), 80 + row * (cellh + 150)
            cell = grid_cell(cellw, cellh, mmpx)
            if NB is None:
                im, (pcx, pcy) = band_outer(D * B2T, side)
                cell.alpha_composite(im, (int(cellw / 2 - pcx), int(cellh * 0.3 - pcy)))
                caps = ["%s %s plate + %s glyph + %s" % (title, "L" if side == 0 else "R", "LB" if side == 0 else "RB",
                                                        "ZL" if side == 0 else "ZR"),
                        "plate 124 x 98.6 bottom px = 7.51 x 5.97 mm, aspect 1.258",
                        "bumper glyph 46 px = 2.79 mm, trigger glyph 51.5 px = 3.12 mm (square)"]
            else:
                f = NB.f
                draw_outer(cell, f, side + 1, cellw / 2, cellh * 0.3, s * D)
                mw, mh, asp, worst = measure(f, side + 1, s)
                gw = twm.drawn(f.plate[side + 1].glyph)[0] * s * MM_TOP
                ch = twm.drawn(f.chip[side + 1].g)[1] * s * MM_TOP
                caps = ["%s %s plate" % (title, "L" if side == 0 else "R"),
                        "drawn plate body %.2f x %.2f mm, aspect %.3f (band 1.258)" % (mw, mh, asp),
                        "worst texture stretch x%.2f; bumper glyph %.2f mm, trigger glyph %.2f mm" % (worst, gw, ch),
                        "plate: %s" % (str(f.plate[side + 1].bg.atlas or f.plate[side + 1].bg.tex).split("\\")[-1]),
                        "glyphs: %s / %s" % (f.plate[side + 1].glyph.atlas, f.chip[side + 1].g.atlas)]
            out.alpha_composite(cell, (x0, y0))
            for j, c_ in enumerate(caps):
                d.text((x0, y0 + cellh + 8 + j * 24), c_, fill=(235, 235, 225), font=font(18))
    p = os.path.join(OUT, "01_plates_band_vs_wings.png")
    out.convert("RGB").save(p)
    return p


# ------------------------------------------------------------------------------------------------------------ the slide timeline
def wings_track(ab):
    """the Wings' left half's centre y per ms after the switch (the add-on's own frames at 60 fps) and the spot / drop"""
    L = boot(ab)
    G = L.globals()
    dt = 1 / 60.0
    tick = twm.ticker(L, dt)
    tick(60)
    G.PLACED = L.table()
    G.COMBAT = True
    tick(1)
    t0 = float(G.NOW)
    tick(40)
    NB = G.WowCompanion.nbar
    sp = NB.top.spot
    drop = float(NB.slideDrop(sp, sp)[0])
    seq = [((float(G.PLACED[i].t) - t0) * 1000, float(G.PLACED[i].ly), float(G.PLACED[i].lx), float(G.PLACED[i].rx))
           for i in range(1, len(G.PLACED) + 1)]
    return L, NB, seq, float(sp.l.y), drop


def y_at(seq, t, y0):
    last = y0
    for tt, y, _, _ in seq:
        if tt <= t + 1e-6:
            last = y
    return last


def draw_wings_half(dst, NB, f, i, cx, cy, s):
    """one half at its diamond's centre (px): the slot rings (the game's square / circle slot art at the diamond's places) and the outer
    column (plate, glyph, chip) from the add-on's own frames"""
    T = NB.TOP
    ox, oy = float(T.OFF_X), float(T.OFF_Y)
    sq = art(os.path.join(ART, "cast/frame_square_normal.png" if i == 1 else "cast/frame_circle_normal.png"))
    size = float(NB.B if i == 1 else NB.BC) * s
    for ax_, ay_ in ((-1, 0), (0, 1), (1, 0), (0, -1)):
        x, y = cx + ax_ * ox * s, cy - ay_ * oy * s
        d = ImageDraw.Draw(dst)
        if i == 1:
            d.rectangle((x - size * 0.42, y - size * 0.42, x + size * 0.42, y + size * 0.42), fill=(20, 16, 12, 255))
        else:
            d.ellipse((x - size * 0.42, y - size * 0.42, x + size * 0.42, y + size * 0.42), fill=(20, 16, 12, 255))
        put(dst, sq.resize((max(1, round(size)), max(1, round(size))), Image.LANCZOS), x, y)
    g = NB.outerGeo()
    sgn = -1 if i == 1 else 1
    draw_outer(dst, f, i, cx + sgn * float(g.x) * s, cy - float(g.plateY) * s, s)


def band_strip(w_px, scale):
    """the band at rest on the bottom screen (Diamonds): 218 bottom px tall, its L / R plates and ZL / ZR in the corners, the slot
    rings of the two diamonds (the game's slot art) - [scale] output px per bottom px"""
    W, H = int(1240 * scale), int(218 * scale)
    im = Image.new("RGBA", (W, H), (30, 24, 18, 255))
    for side in (0, 1):
        pl, (pcx, pcy) = band_outer(scale, side)
        x = (14 + 62) * scale if side == 0 else (1240 - 14 - 62) * scale
        im.alpha_composite(pl, (int(x - pcx), int(55.3 * scale - pcy)))
    sq = art(os.path.join(ART, "cast/frame_square_normal.png"))
    ci = art(os.path.join(ART, "cast/frame_circle_normal.png"))
    for gx, ring, sz in ((333, sq, 91.5), (888, ci, 88.1)):
        for ax_, ay_ in ((-1, 0), (0, 1), (1, 0), (0, -1)):
            x, y = (gx + ax_ * 95) * scale, (108 - ay_ * 52) * scale
            d = ImageDraw.Draw(im)
            r = sz * 0.42 * scale
            if ring is sq:
                d.rectangle((x - r, y - r, x + r, y + r), fill=(16, 12, 9, 255))
            else:
                d.ellipse((x - r, y - r, x + r, y + r), fill=(16, 12, 9, 255))
            put(im, ring.resize((max(1, round(sz * scale)), max(1, round(sz * scale))), Image.LANCZOS), x, y)
    return im


def shot_timeline():
    """both screens stacked (the top panel above the bottom one, each at its own mm), at 0 / 25 / 50 / 75 / 100 % of the way up - the
    switch (t 0) to the band's landing (its start 82 ms + TopSlide.COLS_UP_MS) - 1523 above, now below"""
    mmpx = 5.0                                         # output px per mm
    TC, BC = 0.42, 0.36                                # the shown part: the top panel's lower 42 %, the bottom one's upper 36 %
    top_s, bot_s = mmpx * MM_TOP, mmpx * MM_BOT        # output px per panel px
    TW, TH = 1920 * top_s, 1080 * top_s
    BW, BH = 1240 * bot_s, 1080 * bot_s
    band_img = band_strip(1240, bot_s)
    total = twm.BAND_START_MS + twm.cols_up_ms()
    fr = [0, 0.25, 0.5, 0.75, 1.0]
    runs = [("1523 (nbar16)", before_ab()), ("now (nbar17)", None)]
    colw = int(TW) + 20
    VH = int(TH * TC) + 6 + int(BH * BC)
    out = Image.new("RGBA", (40 + len(fr) * colw, 110 + len(runs) * (VH + 80)), (24, 24, 24, 255))
    d = ImageDraw.Draw(out)
    d.text((20, 16), "The way up on one wall clock: the fight's switch at 0 ms, the band (bottom) from 82 ms (the channel 65 + its first "
                     "frame), the Wings (top) as the add-on lays them - at 0 / 25 / 50 / 75 / 100 %% of %d ms" % total,
           fill=(240, 230, 200), font=font(20))
    d.text((20, 44), "Each panel at its own physical size (1 mm = %.1f px here; the top panel's lower part above the bottom panel's upper part, as stacked on the Thor); travel in mm under each frame (band / Wings)" % mmpx,
           fill=(200, 200, 190), font=font(17))
    for ri, (name, ab) in enumerate(runs):
        L, NB, seq, y_end, drop = wings_track(ab)
        f = NB.f
        s_px = 1.75 * top_s                                # output px per bar unit
        y0 = y_end + drop
        lx, rx = seq[-1][2], seq[-1][3]
        for ci, k in enumerate(fr):
            t = k * total
            x0 = 20 + ci * colw
            yb = 90 + ri * (VH + 80)
            top = Image.new("RGBA", (int(TW), int(TH)), (52, 62, 74, 255))
            ImageDraw.Draw(top).rectangle((820 * top_s, 890 * top_s, 1100 * top_s, 1030 * top_s), fill=(110, 40, 40, 255))
            ly = y_at(seq, t, y0)
            for i, cx in ((1, lx), (2, rx)):
                draw_wings_half(top, NB, f, i, cx * top_s, ly * top_s, s_px)
            bot = Image.new("RGBA", (int(BW), int(BH)), (36, 30, 24, 255))
            bk = twm.cubic((t - twm.BAND_START_MS) / twm.BAND_UP_MS, 0.4, 0, 1, 1)
            by = 8 - bk * 242                                 # the band's top (bottom px): rest 8, away -(234)
            bot.alpha_composite(band_img, (0, int(by * bot_s)))
            out.alpha_composite(top.crop((0, int(TH * (1 - TC)), int(TW), int(TH))), (x0, yb))
            out.alpha_composite(bot.crop((0, 0, int(BW), int(BH * BC))), (x0 + int((TW - BW) / 2), yb + int(TH * TC) + 6))
            wmm = (y0 - ly) * MM_TOP
            bmm = bk * 242 * MM_BOT
            d.text((x0, yb + VH + 8), "%s  %3.0f%%  t %3.0f ms" % (name, k * 100, t), fill=(240, 230, 200), font=font(17))
            d.text((x0, yb + VH + 30), "band %5.1f mm   Wings %5.1f mm" % (bmm, wmm), fill=(220, 220, 210), font=font(17))
    p = os.path.join(OUT, "02_slide_timeline.png")
    out.convert("RGB").save(p)
    return p


# ------------------------------------------------------------------------------------------------------------ Party mode
def party_frames(ab, members, x0, late="join", at=(0.10, 0.20, 1.0, 1.6, 2.6)):
    """the Party-mode run (test_wings_match's scene) on the ActionBar at [ab]: per time in [at] the drawn block parts (with alpha), the
    Wings' halves (centres, scale), and every frame's hits"""
    L = boot(ab)
    G = L.globals()
    dt = 1 / 30.0
    tick = twm.ticker(L, dt)
    G.PARTY = True
    G.HUD.members = members
    G.HUD.x0 = x0
    G.WowCompanion.prd.place()
    G.WowCompanion.uf.pf.draw()
    tick(30)
    G.PLACED = L.table()
    G.COMBAT = True
    NB = G.WowCompanion.nbar
    snaps, hits = {}, 0
    want = sorted(at)
    for i in range(int(2.8 / dt)):
        t = (i + 1) * dt
        if abs(t - 1.5) < dt / 2:
            if late == "join":
                G.HUD.members = min(5, members + 1)
            elif late == "pet":
                G.HUD.pet = True
            G.WowCompanion.prd.place()
            G.WowCompanion.uf.pf.draw()
        tick(1)
        wb = twm.wings_boxes(G, L)
        parts = []
        P, PF = G.WowCompanion.prd, G.WowCompanion.uf.pf
        for nm, fr_ in (("HUD bars", P.f), ("pet", P.petL), ("tab", PF.tab)):
            if fr_.shown:
                parts.append((nm, twm.rect(fr_), float(fr_.alpha or 0)))
        for k in range(1, 5):
            fr_ = PF.frames[k]
            if fr_.shown:
                parts.append(("member %d" % k, twm.rect(fr_), float(fr_.alpha or 0)))
        n_hit = sum(1 for b in wb for nm, r, a in parts if a > 0.02 and twm.over(b, r))
        hits += n_hit
        for w in want:
            if w not in snaps and t >= w - 1e-6:
                e = G.PLACED[len(G.PLACED)] if len(G.PLACED) else None
                snaps[w] = {"parts": parts, "wings": (float(e.lx), float(e.ly), float(e.rx), float(e.ry), float(e.s)) if (e and NB.top.shown) else None,
                            "hit": n_hit, "cdx": NB.centreDx(), "case": NB.top.spot.case if NB.top.spot else None}
    return L, NB, snaps, hits


def shot_party(members):
    sc = 0.42
    W, H = int(1920 * sc), int(1080 * sc)
    at = (0.10, 0.20, 0.40, 1.0, 1.60, 2.60)
    labels = {0.10: "0.10 s: the fight starts", 0.20: "0.20 s: the HUD fades out", 0.40: "0.40 s: it fades in, centred",
              1.0: "1.0 s: settled", 1.60: "1.60 s: a member joins", 2.60: "2.6 s: settled again"}
    runs = [("1523 (nbar16)", before_ab()), ("now (nbar17)", None)]
    out = Image.new("RGBA", (20 + len(at) * (W + 12), 100 + len(runs) * (H + 70)), (22, 22, 22, 255))
    d = ImageDraw.Draw(out)
    d.text((20, 14), "Party mode, Top screen fight, %d members (the HUD block right of the centre): the Wings vs the centred HUD + party "
                     "block, real plate / glyph art" % members, fill=(240, 230, 200), font=font(20))
    d.text((20, 42), "Red outline = a Wings half over a drawn block part (alpha > 0.02) that frame", fill=(220, 150, 140), font=font(16))
    for ri, (name, ab) in enumerate(runs):
        L, NB, snaps, hits = party_frames(ab, members, 980, "join", at)
        f = NB.f
        for ci, t in enumerate(at):
            sn = snaps.get(t)
            img = Image.new("RGBA", (W, H), (52, 62, 74, 255))
            dd = ImageDraw.Draw(img)
            for nm, r, a in sn["parts"]:
                col = (150, 50, 50) if nm in ("HUD bars", "pet") else (60, 110, 160) if nm.startswith("member") else (200, 170, 60)
                fill = tuple(int(c * a + 52 * (1 - a)) for c in col) + (255,)
                dd.rectangle((r[0] * sc, r[1] * sc, (r[0] + r[2]) * sc, (r[1] + r[3]) * sc), fill=fill)
                if a > 0.3:
                    dd.text((r[0] * sc + 3, r[1] * sc + 2), nm, fill=(255, 255, 255), font=font(11))
            dd.line((W / 2, 0, W / 2, H), fill=(255, 255, 255, 60))
            if sn["wings"]:
                lx, ly, rx, ry, s = sn["wings"]
                for i, cx, cy in ((1, lx, ly), (2, rx, ry)):
                    draw_wings_half(img, NB, f, i, cx * sc, cy * sc, s * sc)
                if sn["hit"]:
                    hw, hh, ax, ay = NB.halfBox()
                    for (cx, cy), axx in (((lx, ly), ax), ((rx, ry), hw - ax)):
                        x0_ = cx - (axx if axx == ax else (hw - ax)) * s if False else None
                    lb = (lx - ax * s, ly - ay * s, hw * s, hh * s)
                    rb = (rx - (hw - ax) * s, ry - ay * s, hw * s, hh * s)
                    for b in (lb, rb):
                        dd.rectangle((b[0] * sc, b[1] * sc, (b[0] + b[2]) * sc, (b[1] + b[3]) * sc), outline=(255, 40, 40), width=3)
            x0 = 20 + ci * (W + 12)
            y0 = 76 + ri * (H + 70)
            out.alpha_composite(img, (x0, y0))
            d.text((x0, y0 + H + 4), "%s - %s" % (name, labels[t]), fill=(235, 235, 225), font=font(14))
            d.text((x0, y0 + H + 22), "case %s, shift told %+d%s" % (sn["case"], sn["cdx"], ", OVER THE BLOCK" if sn["hit"] else ""),
                   fill=(255, 120, 110) if sn["hit"] else (200, 220, 200), font=font(14))
        d.text((20 + len(at) * (W + 12) - 420, 76 + ri * (H + 70) + H + 40), "%s: %d half-frames over a drawn part in 2.8 s" % (name, hits),
               fill=(255, 120, 110) if hits else (160, 230, 160), font=font(15))
    p = os.path.join(OUT, "03_party_%d_members.png" % members)
    out.convert("RGB").save(p)
    return p


def main():
    os.makedirs(OUT, exist_ok=True)
    made = [shot_plates(), shot_timeline()]
    for m in (2, 3, 5):
        made.append(shot_party(m))
    for p in made:
        print(p)


if __name__ == "__main__":
    main()
