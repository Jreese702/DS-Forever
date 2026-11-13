"""THE RIM AT REAL PIXELS, before / after (Hub hub49 / uf20; the user, 2026-11-13: "The shaper bar still looks so fucking pixelated on
the corners").

    python tools/render_rim.py [--out DIR] [--before REV]

The rig of tools/render_target_bar.py (Trail.lua + Hub.lua in one lupa runtime, every region's rect resolved from its anchors) with a
768-unit UIParent on a 720 / 1080 / 1440-line screen (GetPhysicalScreenSize), drawn at ONE PHYSICAL PIXEL per image pixel with the
GPU's own sampling (render_target_bar.gpu_sample: bilinear at each pixel's centre without mipmaps, or NEAREST when the code asks):
    rim_<tag>_<ph>.png           the target bar (level box, bar, target of target) and a party row, at real size
    rim_<tag>_<ph>_corner4x.png  the level box and the bar's left end, 4x (each screen pixel a 4 x 4 block)
    rim_sheet.png                every corner crop: before above, after below, one column per screen height
BEFORE = the given rev's Hub.lua (default feat/ui1113-plates: uf19, the game's 19-texel art NEAREST, from
E:/safepath-work/design/prd/game_art2), AFTER = the working tree (our own hud_rim_<s>.tga, LINEAR)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import render_target_bar as rtb  # noqa: E402
import render_top_party as rt  # noqa: E402
import test_compass as tc  # noqa: E402

OUT = "C:/Users/Jrees/AppData/Local/Temp/claude/6c5148a90427b4bc/789b5138-341f-4ef8-8102-099551ca3a8a/scratchpad/ui1113-shots/rim"
HEIGHTS = (720, 1080, 1440)
UIH = 768
ZOOM = 4

GRID = '''
local RECT0 = RECT
function RECT(q)
    if q == nil or rawequal(q, UIParent) then return { 0, 0, UIParent.ww or 1920, UIParent.hh or 1080 } end
    return RECT0(q)
end
local R1 = getmetatable(CreateFrame("Frame"))
local gw = R1.GetWidth
function R1:GetWidth() if rawequal(self, UIParent) then return self.ww or 1920 end return gw(self) end
'''


def shoot(hub_src, trail_src, ph):
    old = tc.TRAIL
    if trail_src is not None:
        tmp = os.path.join(OUT, "_trail_rev.lua")
        os.makedirs(OUT, exist_ok=True)
        with open(tmp, "w", encoding="utf-8", newline="") as fh:
            fh.write(trail_src)
        tc.TRAIL = tmp
    try:
        rig = rt.Rig(hub_src)
    finally:
        tc.TRAIL = old
    rig.L.execute(rtb.SETUP)
    rig.L.execute(GRID)
    G = rig.G
    try:
        G.WowCompanion.compass.build()
    except Exception:
        pass
    G.GAME.physH = ph
    rig.set_k(UIH)
    rig.scene(dict(n=2, pet=False, mode=False, pick=None, dead=None, oor=None))
    rig.run(0.5)
    f = rig.UF.tb.f
    f.tot.Show(f.tot)
    G.UNITS.targettarget = rig.L.table_from({"name": "Brother Paxton", "lv": 7, "hp": 300, "hpMax": 400})
    rig.run(0.3)
    roots = rig.L.table()
    roots[f] = "target"
    p1 = rig.PF.frames[1]
    if p1 is not None:
        roots[p1] = "party1"
    got = G.COLLECT(roots)
    items = [dict(got[i]) for i in range(1, len(got) + 1)]
    want = {it["seq"] for it in items}
    A = G.ALLR
    extra = {}
    for i in range(1, len(A) + 1):
        r = A[i]
        if r.seq in want:
            extra[r.seq] = (r.coord, str(r.filter) if r.filter is not None else None)
    for it in items:
        c, flt = extra.get(it["seq"], (None, None))
        it["coord"] = c
        it["filter"] = flt
    px = rig.UF.px()
    hp = rig.rect(f.hp)
    sh = rig.rect(f.shield)
    party = rig.rect(p1) if p1 is not None else None
    files = sorted({str(it.get("tex")).split("\\")[-1] for it in items if it.get("tex") and "hud_rim" in str(it.get("tex"))})
    return items, hp, sh, party, px, str(rig.UF.V), files


def render(tag, hub_src, trail_src, ph):
    from PIL import Image
    items, hp, sh, party, px, ufv, files = shoot(hub_src, trail_src, ph)
    # one crop holding the level box, the bar, the target of target and the party row (UI units, y up)
    left = min(sh[0], party[0] if party else sh[0]) - 30
    right = hp[2] + 240
    top = hp[3] + 50
    bottom = min(hp[1] - 40, (party[1] - 20) if party else hp[1] - 40)
    box = (left, bottom, right, top)
    pads = (0, 0, 0)
    path = os.path.join(OUT, "rim_%s_%d.png" % (tag, ph))
    filt = sorted({it.get("filter") or "-" for it in items if it.get("coord") is not None})
    rtb.draw(items, None, box, path, "%s  %d lines  %s  %s" % (tag, ph, ufv, ",".join(files) or "game art " + "/".join(filt)), [],
             pads=pads, phys=px)
    # the corner crop: the level box's right side and the bar's left end, 4x
    im = Image.open(path)
    L0 = round(left / px) * px
    T0 = round(top / px) * px

    def ipx(x, y):
        return (x - L0) / px, (T0 - y) / px
    x0, y0 = ipx(sh[0], hp[3])
    x1, y1 = ipx(hp[0], hp[1])
    cx0, cy0 = int(x0 + (x1 - x0) * 0.35), int(y0 - 14 * ph / 1080)
    cx1, cy1 = int(x1 + 26 * ph / 1080), int(y1 + 14 * ph / 1080)
    crop = im.crop((cx0, cy0, cx1, cy1))
    crop = crop.resize((crop.width * ZOOM, crop.height * ZOOM), Image.NEAREST)
    cp = os.path.join(OUT, "rim_%s_%d_corner4x.png" % (tag, ph))
    crop.save(cp)
    return path, cp, files


def sheet(rows, out):
    from PIL import Image, ImageDraw, ImageFont
    ims = [[Image.open(p) for p in r] for _, r in rows]
    cw = [max(ims[i][j].width for i in range(len(ims))) for j in range(len(ims[0]))]
    ch = [max(im.height for im in r) for r in ims]
    head, lab = 36, 150
    s = Image.new("RGB", (lab + sum(cw) + 10 * len(cw), head + sum(ch) + 10 * len(ch)), (14, 14, 16))
    d = ImageDraw.Draw(s)
    try:
        f = ImageFont.truetype("arialbd.ttf", 22)
    except OSError:
        f = ImageFont.load_default()
    x = lab
    for j, h in enumerate(HEIGHTS):
        d.text((x + 6, 6), "%d lines (4x)" % h, font=f, fill=(240, 220, 120))
        x += cw[j] + 10
    y = head
    for i, (name, _) in enumerate(rows):
        d.text((8, y + 8), name, font=f, fill=(240, 220, 120))
        x = lab
        for j, im in enumerate(ims[i]):
            s.paste(im.convert("RGB"), (x, y))
            x += cw[j] + 10
        y += ch[i] + 10
    s.save(out)
    return out


def main():
    a = sys.argv[1:]
    global OUT
    OUT = a[a.index("--out") + 1] if "--out" in a else OUT
    before = a[a.index("--before") + 1] if "--before" in a else "feat/ui1113-plates"
    os.makedirs(OUT, exist_ok=True)
    rows = []
    for tag, hub, trail in (("before", rtb.git_show(before, rt.HUB_REL), rtb.git_show(before, rtb.TRAIL_REL)), ("after", None, None)):
        corners = []
        for ph in HEIGHTS:
            p, cp, files = render(tag, hub, trail, ph)
            print(p, cp, files)
            corners.append(cp)
        rows.append((tag.upper(), corners))
    print(sheet(rows, os.path.join(OUT, "rim_sheet.png")))


if __name__ == "__main__":
    main()
