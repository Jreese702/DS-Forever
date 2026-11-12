"""THE TOP SCREEN'S TARGET BAR, RENDERED off-device (the user, 2026-11-12, on 1.0.26: "the target bars cast bar is too squished into
it, also the aggro tint looks like its floating because its trying to go around the cast bar too (which is not there)").

    python tools/render_target_bar.py [--out DIR] [--rev GIT_REV --tag NAME] [--sheet BEFORE_TAG,AFTER_TAG]

The rig of tools/render_top_party.py (Trail.lua + Hub.lua in one lupa runtime on the compass tests' stub game, every region's rect
resolved from its anchors) with a hostile target ("Kobold Tunneler"), in shots:
    plain          no cast, no tint                 tint          no cast, the aggro tint
    cast_tint      a cast                           pips_cast_tint   a Rogue's combo pips (3 of 5) + a cast
    full_tint      pips + cast + two debuffs and a buff
The HUD rim pieces are drawn from the REAL art (E:/safepath-work/design/prd/game_art2/m_ui_hud_cooldownmanager_bar_bg_c60.png, cut by
each piece's own tex coords) when it is there, else the placeholder rim; the aggro tint is our own tint_glow.tga, tinted red, in the
frame the code places: Trail.lua's WC.rings.barAnchor (at8 on), else at7's offsets (TOPLEFT -3k-7, 3k+7 / BOTTOMRIGHT 7k+7, -8k-7).
--rev renders that commit's Hub.lua (and its Trail.lua) instead; --sheet stacks two tags' shots as a before / after sheet."""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import render_top_party as rt  # noqa: E402
import test_compass as tc  # noqa: E402

ROOT = rt.ROOT
TRAIL_REL = "companion/app/src/main/assets/companion/addons/WowCompanion/Trail.lua"
TINT_TGA = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanion", "Media", "tint_glow.tga")
ART_DIR = "E:/safepath-work/design/prd/game_art2"
ART = {"UI-HUD-CoolDownManager-Bar-BG-c60": "m_ui_hud_cooldownmanager_bar_bg_c60.png",
       "UI-HUD-CoolDownManager-Bar-BG": "m_ui_hud_cooldownmanager_bar_bg.png",
       "UI-HUD-CoolDownManager-Bar": "m_ui_hud_cooldownmanager_bar_c60.png"}
OUT = "C:/Users/Jrees/AppData/Local/Temp/claude/6c5148a90427b4bc/789b5138-341f-4ef8-8102-099551ca3a8a/scratchpad/target-bar-shots"
SCALE = 2                                      # the crop's zoom in the PNGs

SHOTS = [("plain", dict(tint=False)), ("tint", dict(tint=True)), ("cast_tint", dict(tint=True, cast=True)),
         ("pips_cast_tint", dict(tint=True, cast=True, pips=True)), ("full_tint", dict(tint=True, cast=True, pips=True, auras=True))]

SETUP = r'''
local baseClass = UnitClass
SHOT = {}
function UnitClass(u) if u == "player" and SHOT.pips then return "Rogue", "ROGUE" end return baseClass(u) end
function GetComboPoints() return SHOT.pips and 3 or 0 end
local basePM = UnitPowerMax
function UnitPowerMax(u, t) if u == "player" and t == 4 then return 5 end return basePM(u, t) end
function UnitCastingInfo(u)
    if u == "target" and SHOT.cast then local t = GAME.t * 1000; return "Fireball", "Fireball", 135812, t - 900, t + 1100 end
end
function UnitChannelInfo() return nil end
C_UnitAuras = C_UnitAuras or {}
C_UnitAuras.GetAuraDataByIndex = function(u, i, filter)
    if u ~= "target" or not SHOT.auras then return nil end
    local harm = filter:find("HARMFUL") ~= nil
    if harm and filter:find("PLAYER") and i <= 2 then
        return { auraInstanceID = 100 + i, icon = 136000 + i, expirationTime = GAME.t + 8, duration = 12, applications = (i == 2) and 3 or 1, dispelName = "Magic" }
    end
    if not harm and i == 1 then return { auraInstanceID = 200, icon = 135900, expirationTime = GAME.t + 60, duration = 120, applications = 1 } end
    return nil
end
TINTF = CreateFrame("Frame")
'''


def git_show(rev, rel):
    return subprocess.run(["git", "-C", ROOT, "show", "%s:%s" % (rev, rel)], capture_output=True, check=True).stdout.decode("utf-8")


def shoot(hub_src, trail_src, opts):
    """One fresh rig, the shot's game, the target bar's items + the tint rect (or None) + the health bar's rect."""
    old_trail = tc.TRAIL
    tmp = None
    if trail_src is not None:
        tmp = os.path.join(OUT, "_trail_rev.lua")
        os.makedirs(OUT, exist_ok=True)
        with open(tmp, "w", encoding="utf-8", newline="") as fh:
            fh.write(trail_src)
        tc.TRAIL = tmp
    try:
        rig = rt.Rig(hub_src)
    finally:
        tc.TRAIL = old_trail
    rig.L.execute(SETUP)
    G = rig.G
    for k, v in opts.items():
        G.SHOT[k] = v
    rig.scene(dict(n=1, pet=False, mode=False, pick=None, dead=None, oor=None))
    rig.run(0.5)
    f = rig.UF.tb.f
    roots = rig.L.table()
    roots[f] = "target"
    got = G.COLLECT(roots)
    items = [dict(got[i]) for i in range(1, len(got) + 1)]
    want = {it["seq"] for it in items}
    coords = {}
    A = G.ALLR
    for i in range(1, len(A) + 1):                                     # each piece's tex coords (COLLECT leaves them out)
        r = A[i]
        if r.seq in want and r.coord is not None:
            coords[r.seq] = r.coord
    for it in items:
        it["coord"] = coords.get(it["seq"])
    hp = rig.rect(f.hp)
    tint = None
    if opts.get("tint"):
        w, h = hp[2] - hp[0], hp[3] - hp[1]
        R = G.WowCompanion.rings
        if R.barAnchor is not None:                                    # at8 on: the code's own anchor
            R.barAnchor(G.TINTF, f.hp, w, h)
            tint = rig.rect(G.TINTF)
        else:                                                          # at7: its offsets, as Trail.lua had them
            k = h / 8
            tint = (hp[0] - 3 * k - 7, hp[1] - 8 * k - 7, hp[2] + 7 * k + 7, hp[3] + 3 * k + 7)
    cast = rig.rect(f.cast) if f.cast.shown is not False else None
    cp = rig.rect(f.cp) if f.cp.shown is not False else None
    return items, tint, hp, cast, cp, str(rig.UF.V), str(G.WowCompanion.rings.V)


_art = {}


def art(name):
    if name not in _art:
        from PIL import Image
        p = os.path.join(ART_DIR, ART.get(name, "-"))
        _art[name] = Image.open(p).convert("RGBA") if os.path.exists(p) else None
    return _art[name]


def lua4(t):
    if t is None:
        return None
    try:
        return [t[i] for i in range(1, 5)]
    except Exception:
        return None


def draw(items, tint, hp, path, title, notes):
    from PIL import Image, ImageDraw, ImageFont
    pad_x, pad_top, pad_bot = 120, 70, 130
    L0, T0 = hp[0] - pad_x, hp[3] + pad_top                          # the crop's top-left in UI units (y up)
    Wc, Hc = int(hp[2] - hp[0] + 2 * pad_x), int(pad_top + (hp[3] - hp[1]) + pad_bot)
    S = SCALE
    img = Image.new("RGBA", (Wc * S, Hc * S))
    g = ImageDraw.Draw(img)
    for y in range(Hc * S):
        k = y / (Hc * S)
        g.line([(0, y), (Wc * S, y)], fill=(int(46 + 20 * k), int(60 + 22 * k), int(52 + 6 * k), 255))

    def px(x, y):
        return ((x - L0) * S, (T0 - y) * S)

    def paste(src, l, b, r, t, alpha=1.0, tintc=None):
        x0, y0 = px(l, t)
        x1, y1 = px(r, b)
        w, h = int(round(x1 - x0)), int(round(y1 - y0))
        if w <= 0 or h <= 0 or src is None:
            return
        im = src.resize((w, h), Image.BILINEAR)
        if tintc is not None or alpha < 1:
            rr, gg, bb, aa = im.split()
            if tintc is not None:
                rr = rr.point(lambda v: int(v * tintc[0]))
                gg = gg.point(lambda v: int(v * tintc[1]))
                bb = bb.point(lambda v: int(v * tintc[2]))
            aa = aa.point(lambda v: int(v * alpha))
            im = Image.merge("RGBA", (rr, gg, bb, aa))
        layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
        layer.paste(im, (int(round(x0)), int(round(y0))))
        img.alpha_composite(layer)

    fonts = {}

    def font(s):
        s = max(6, int(round(s)))
        if s not in fonts:
            try:
                fonts[s] = ImageFont.truetype("arialbd.ttf", s)
            except OSError:
                fonts[s] = ImageFont.load_default()
        return fonts[s]

    if tint is not None:                                            # under the bar (one frame level below it)
        tga = Image.open(TINT_TGA).convert("RGBA")
        tga = tga.crop((0, 0, tga.width, int(round(tga.height * 53 / 64))))
        paste(tga, tint[0], tint[1], tint[2], tint[3], 0.95, (0.93, 0.16, 0.12))
    od = ImageDraw.Draw(img)
    for it in items:
        l, b, r, t, a = it["l"], it["b"], it["r"], it["t"], it["alpha"]
        x0, y0 = px(l, t)
        x1, y1 = px(r, b)
        bx = [x0, y0, x1 - 1, y1 - 1]
        if it["kind"] == "fs":
            tx = it.get("text")
            if tx is None or str(tx) == "":
                continue
            tx = str(tx)
            fz = font((it.get("fsize") or 12) * S)
            col = rt._rgb(lua4(it.get("tcol")) or [1, 1, 1, 1], a)
            tw = od.textlength(tx, font=fz)
            jh = it.get("jh") or "CENTER"
            x = x0 if jh == "LEFT" else (x1 - tw if jh == "RIGHT" else (x0 + x1 - tw) / 2)
            od.text((x, (y0 + y1) / 2), tx, font=fz, fill=col, anchor="lm", stroke_width=S, stroke_fill=(0, 0, 0, col[3]))
            continue
        tintc = rt._lua_list(it.get("col")) or rt._lua_list(it.get("sbc"))
        ct, at, tex = rt._lua_list(it.get("ct")), it.get("atlas"), it.get("tex")
        name = str(at or tex or "")
        if name.startswith("atlas:"):
            name = name[6:]
        src = art(name) if name in ART else None
        coord = lua4(it.get("coord"))
        if ct:
            c = list(ct)
            if tintc:
                c = [c[i] * tintc[i] for i in range(3)] + c[3:]
            if it.get("grad"):
                c = c[:3] + [(c[3] if len(c) > 3 else 1) * 0.3]
            od.rectangle(bx, fill=rt._rgb(c, a))
        elif src is not None:
            if coord and all(isinstance(v, (int, float)) for v in coord):
                cl, cr, ctp, cb = coord
                W, H = src.size
                xa, xb = sorted((cl * W, cr * W))
                ya, yb = sorted((ctp * H, cb * H))
                piece = src.crop((int(round(xa)), int(round(ya)), max(int(round(xb)), int(round(xa)) + 1), max(int(round(yb)), int(round(ya)) + 1)))
                if cl > cr:
                    piece = piece.transpose(Image.FLIP_LEFT_RIGHT)
                if ctp > cb:
                    piece = piece.transpose(Image.FLIP_TOP_BOTTOM)
            else:
                piece = src
            tc3 = [tintc[i] for i in range(3)] if tintc else None
            paste(piece, l, b, r, t, a, tc3)
            od = ImageDraw.Draw(img)
        elif at or tex is not None:
            c = tintc or [0.75, 0.6, 0.85]
            od.rectangle(bx, fill=rt._rgb(list(c[:3]) + [0.92], a))
            if (x1 - x0) <= 80:
                od.rectangle(bx, outline=rt._rgb([0, 0, 0, 0.7], a))
            elif not it.get("sbc"):
                od.text((x0 + 4, (y0 + y1) / 2), name.split("\\")[-1][:40], font=font(9 * S), fill=(20, 20, 20, int(200 * a)), anchor="lm")
    od = ImageDraw.Draw(img)
    od.text((8, 6), title, font=font(13 * S), fill=(240, 240, 240, 255), stroke_width=2, stroke_fill=(0, 0, 0, 255))
    for i, n in enumerate(notes):
        od.text((8, img.height - 8 - 15 * S * (len(notes) - i)), n, font=font(10 * S), fill=(230, 230, 200, 255), stroke_width=2,
                stroke_fill=(0, 0, 0, 255))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.convert("RGB").save(path)


def sheet(out, tags):
    from PIL import Image, ImageDraw, ImageFont
    rows = []
    for nm, _ in SHOTS:
        rows.append([os.path.join(out, "tb_%s_%s.png" % (t, nm)) for t in tags])
    ims = [[Image.open(p) for p in r] for r in rows]
    cw, ch = ims[0][0].size
    head = 40
    s = Image.new("RGB", (cw * len(tags), head + ch * len(rows)), (12, 12, 14))
    d = ImageDraw.Draw(s)
    try:
        f = ImageFont.truetype("arialbd.ttf", 26)
    except OSError:
        f = ImageFont.load_default()
    for j, t in enumerate(tags):
        d.text((j * cw + 10, 6), ("BEFORE  " if j == 0 else "AFTER  ") + t, font=f, fill=(240, 220, 120))
    for i, r in enumerate(ims):
        for j, im in enumerate(r):
            s.paste(im.resize((cw, ch)), (j * cw, head + i * ch))
    p = os.path.join(out, "target_bar_sheet.png")
    s.save(p)
    print(p)


def main():
    a = sys.argv[1:]
    out = a[a.index("--out") + 1] if "--out" in a else OUT
    if "--sheet" in a:
        sheet(out, a[a.index("--sheet") + 1].split(","))
        return
    rev = a[a.index("--rev") + 1] if "--rev" in a else None
    tag = a[a.index("--tag") + 1] if "--tag" in a else (rev or "now")
    hub = git_show(rev, rt.HUB_REL) if rev else None
    trail = git_show(rev, TRAIL_REL) if rev else None
    for nm, opts in SHOTS:
        items, tint, hp, cast, cp, ufv, atv = shoot(hub, trail, opts)
        notes = []
        if cp:
            notes.append("pips top %.1f px under the bar's bottom" % (hp[1] - cp[3]))
        if cast:
            notes.append("cast top %.1f px under the bar's bottom" % (hp[1] - cast[3]))
        if tint:
            notes.append("tint outsets L %.1f T %.1f R %.1f B %.1f" % (hp[0] - tint[0], tint[3] - hp[3], tint[2] - hp[2], hp[1] - tint[1]))
        p = os.path.join(out, "tb_%s_%s.png" % (tag, nm))
        draw(items, tint, hp, p, "%s  %s  %s / %s" % (tag, nm, ufv, atv), notes)
        print(p, "; ".join(notes))


if __name__ == "__main__":
    main()
