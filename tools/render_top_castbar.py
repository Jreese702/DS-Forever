"""Evidence shots for ActionBar nbar18 (OUR OWN CAST BAR ABOVE THE HUD, Cast bar in fights = Top screen), drawn with the REAL art: the
REAL ActionBar.lua runs under test_top_castbar.py's scene (test_wings_match's stub game, the player's casts fired as the game fires them)
frame by frame; the cast bar is composited from the game's own casting-bar pictures (the harvested art root's ui/cast/castbar_*.png, the
atlases the add-on asks for by name) at the place, size, fill and alpha the add-on set, its name in the game's Friz Quadrata; the Wings
from their real plate / glyph art (render_wings_match.draw_wings_half). The HUD and Party rows are the scene's schematic boxes (labelled).
The top screen at the Thor's UI scale (UIParent 817 lines: 1.322 px a UI unit), over a blurred game capture.

    python -B tools/render_top_castbar.py [OUT_DIR]          (default C:/dsf-work/r1527-shots/topcast)

  01_fight_cast_default_hud.png    a Top-screen fight, a Frostbolt half cast, the default HUD: our bar right above it, the Wings beside
  02_party_centred.png             Party mode (3 members, the block right of the centre): the fight's start, mid-move, landed centred
  03_channel.png                   a channel draining (Arcane Missiles)
  04_interrupt.png                 an interrupt: the red bar with the game's word, held, then fading
  05_swing.png                     the swing line(s) over the bar: one hand, dual wield, dual wield with a cast
  06_disenchant_out_of_combat.png  a Disenchant out of the takeover: nothing on the top screen (the bottom screen's band has it - the
                                   app's own render beside it when FightCastStripShotsTest wrote it to OUT_DIR/bottom)
  07_wings_crowded.png             walls either side of the HUD: the Wings joined above the HUD block and our bar
  08_success.png                   a cast finishing: the full bar and its glow
Pictures the art root lacks (UI-CastingBar-Frame, -TextBox, -Uninterruptable, -Full-Channel, -Full-Glow-Channel) are drawn as marked
stand-ins (a thin metal rim, a dark plate) - on the device the client draws the game's own. Needs lupa and PIL."""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import render_wings_match as rwm  # noqa: E402
import test_top_castbar as ttc  # noqa: E402
import test_wings_match as twm  # noqa: E402

OUT = sys.argv[1] if len(sys.argv) > 1 else "C:/dsf-work/r1527-shots/topcast"
ART = os.environ.get("TOPCAST_ART", "E:/dsf-issues/artroot-k/ui")
FONTS = os.environ.get("TOPCAST_FONTS", "E:/dsf-issues/artroot-k/fonts")
BG = os.environ.get("TOPCAST_BG", "C:/Users/Jrees/AppData/Local/Temp/claude/6c5148a90427b4bc/789b5138-341f-4ef8-8102-099551ca3a8a/scratchpad/homeedit/top_bg.png")
KK = 1080 / 817.0                                   # the Thor's UI: UIParent 817 lines high
ATLAS = {"UI-CastingBar-Background": "cast/castbar_background.png", "UI-CastingBar-Filling-Standard": "cast/castbar_filling_standard.png",
         "UI-CastingBar-Filling-Channel": "cast/castbar_filling_channel.png", "UI-CastingBar-Interrupted": "cast/castbar_interrupted.png",
         "UI-CastingBar-Full-Standard": "cast/castbar_full_standard.png", "UI-CastingBar-Full-Glow-Standard": "cast/castbar_full_glow_standard.png",
         "UI-CastingBar-Pip": "cast/castbar_pip.png"}
STANDIN = []
rwm.ART = ART if os.path.isdir(os.path.join(ART, "cast")) else rwm.ART


def font(n, friz=True):
    try:
        return ImageFont.truetype(os.path.join(FONTS, "frizqt__.ttf") if friz else "arial.ttf", n)
    except Exception:      # noqa: BLE001
        return rwm.font(n)


def atlas_img(name, w, h):
    f = ATLAS.get(str(name))
    if not f or not os.path.exists(os.path.join(ART, f)):
        if name not in STANDIN:
            STANDIN.append(str(name))
        return None
    return rwm.art(os.path.join(ART, f)).resize((max(1, int(round(w))), max(1, int(round(h)))), Image.LANCZOS)


def boot(kk=KK):
    L = ttc.boot()
    # (the scene's frames are laid at 1 px a UI unit; the bar takes the Thor's UI scale for its own size: its geometry's kk only)
    L.execute("local g0 = WowCompanion.nbar.cbGeo; WowCompanion.nbar.cbGeo = function(b, W, H, _, t) return g0(b, W, H, %r, t) end" % kk)
    return L


def tick_fn(L, dt=1 / 60):
    return ttc.tick_fn(L, dt)


# ------------------------------------------------------------------------------------------------------------------ drawing
def draw_bar(img, G, sc, ox=0, oy=0):
    """our cast bar as the add-on laid it (px from the top-left x sc, offset ox / oy), the real art"""
    NB = G.WowCompanion.nbar
    cb = NB.cb
    if cb is None or not cb.vis or float(cb.a or 0) <= 0.001:
        return None
    C = NB.CB
    at = NB.top.cbAt
    u = float(at.kk) * sc
    cx, cy = float(cb.x) * sc + ox, float(cb.y) * sc + oy
    W, H, B = float(C.W) * u, float(C.H) * u, float(C.BORDER) * u
    a_root = float(cb.a)
    lk = cb.look
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    # the swing lines (on the root: never faded by a cast's finish)
    for i in (1, 2):
        s = cb.sw[i]
        if s.on and s.up is not None:
            hh = float(s.hh) * u
            y1 = cy - H / 2 - float(s.up) * u
            x0 = cx - W / 2
            a = 1.0 if i == 1 else float(C.SW_OFF_A)
            d.rectangle((x0, y1 - hh, x0 + W, y1), fill=(0, 0, 0, int(153 * a)))
            fill = atlas_img("UI-CastingBar-Filling-Standard", W, hh)
            k = float(s.k or 0)
            if fill is not None and k > 0:
                g = fill.convert("LA").convert("RGBA")          # the standard fill desaturated (the add-on's SetDesaturated)
                crop = g.crop((0, 0, max(1, int(round(W * k))), g.height))
                crop.putalpha(crop.getchannel("A").point(lambda v, a=a: int(v * a)))
                layer.alpha_composite(crop, (int(round(x0)), int(round(y1 - hh))))
    if lk is not None:
        body = Image.new("RGBA", img.size, (0, 0, 0, 0))
        bd = ImageDraw.Draw(body)
        # the name plate under the bar (UI-CastingBar-TextBox: a stand-in plate), the name in the game's font
        name = str(lk.name or "")
        if name:
            pw, ph = float(C.PLATE_W) * u, float(C.PLATE_H) * u
            pt = cy + H / 2 + B
            bd.rounded_rectangle((cx - pw / 2, pt, cx + pw / 2, pt + ph), radius=ph / 3, fill=(0, 0, 0, 150))
            if "UI-CastingBar-TextBox" not in STANDIN:
                STANDIN.append("UI-CastingBar-TextBox")
            bd.text((cx, pt + ph / 2), name, fill=(255, 255, 255, 255), font=font(max(8, int(round(10 * u)))), anchor="mm",
                    stroke_width=max(1, int(u * 0.6)), stroke_fill=(0, 0, 0, 255))
        if cb.time.shown:
            bd.text((cx + W / 2 + (float(C.BORDER) + float(C.TIME_X)) * u, cy), str(cb.time.text), fill=(255, 255, 255, 255),
                    font=font(max(8, int(round(10 * u)))), anchor="lm")
        bg = atlas_img("UI-CastingBar-Background", W + 2 * u, H + 2 * u)
        if bg is not None:
            body.alpha_composite(bg, (int(round(cx - W / 2 - u)), int(round(cy - H / 2 - u))))
        p = float(lk.p)
        fill = atlas_img(lk.atlas, W, H)
        if fill is None:                                  # a stand-in: the game's classic tint
            rgb = [float(lk.rgb[i]) for i in (1, 2, 3)] if lk.rgb is not None else [1, 0.7, 0]
            fill = Image.new("RGBA", (max(1, int(round(W))), max(1, int(round(H)))), tuple(int(v * 255) for v in rgb) + (255,))
        if p > 0:
            crop = fill.crop((0, 0, max(1, int(round(fill.width * p))), fill.height))     # (the StatusBar crops its texture)
            body.alpha_composite(crop, (int(round(cx - W / 2)), int(round(cy - H / 2))))
        if float(lk.glow or 0) > 0 and lk.glowA is not None:
            gl = atlas_img(lk.glowA, W + 2 * u, H + 4 * u)
            if gl is not None:
                gl.putalpha(gl.getchannel("A").point(lambda v, a=float(lk.glow): int(v * a)))
                body.alpha_composite(gl, (int(round(cx - W / 2 - u)), int(round(cy - H / 2 - 2 * u))))
        if cb.spark.shown and cb.sparkX is not None:
            sp = atlas_img("UI-CastingBar-Pip", float(C.SPARK_W) * u, float(C.SPARK_H) * u)
            if sp is not None:
                sx = float(cb.sparkX) * sc + ox
                body.alpha_composite(sp, (int(round(sx - sp.width / 2)), int(round(cy - sp.height / 2))))
        # the border (UI-CastingBar-Frame: a stand-in metal rim)
        if "UI-CastingBar-Frame" not in STANDIN:
            STANDIN.append("UI-CastingBar-Frame")
        bd.rounded_rectangle((cx - W / 2 - B, cy - H / 2 - B, cx + W / 2 + B, cy + H / 2 + B), radius=B * 1.5, outline=(150, 138, 112, 255),
                             width=max(1, int(round(u))))
        ba = float(lk.alpha or 1)
        if ba < 1:
            body.putalpha(body.getchannel("A").point(lambda v, a=ba: int(v * a)))
        layer.alpha_composite(body)
    if a_root < 1:
        layer.putalpha(layer.getchannel("A").point(lambda v: int(v * a_root)))
    img.alpha_composite(layer)
    rs = NB.cbRects()
    box = None
    for k, v in (rs.items() if rs is not None else []):
        box = twm.union(box, (float(v.x), float(v.y), float(v.w), float(v.h)))
    return box


def draw_scene(G, L, sc, caption, sub="", zoom=2.6, wings=True, mark=None):
    """the top screen at scale [sc] (the background, the scene's HUD / rows as labelled boxes, the Wings in real art, our bar) and a zoom
    on the bar; -> the image"""
    W, H = int(1920 * sc), int(1080 * sc)
    try:
        bg = Image.open(BG).convert("RGBA").resize((W, H), Image.LANCZOS)
        bg = Image.blend(bg, Image.new("RGBA", (W, H), (20, 20, 24, 255)), 0.35)
    except Exception:      # noqa: BLE001
        bg = Image.new("RGBA", (W, H), (52, 62, 74, 255))
    img = bg
    d = ImageDraw.Draw(img)
    for nm, r in twm.drawn_parts(G):
        a = 1.0
        if nm == "bars":
            x, y, w, h = [v * sc for v in r]
            d.rounded_rectangle((x, y, x + w, y + h), radius=4, fill=(10, 10, 10, 200))
            d.rectangle((x + 3, y + 3, x + w * 0.82, y + h * 0.55), fill=(40, 200, 40, 255))
            d.rectangle((x + 3, y + h * 0.62, x + w * 0.66, y + h - 3), fill=(40, 90, 230, 255))
            d.text((x + 4, y + h + 2), "HUD (schematic)", fill=(255, 255, 255, 200), font=font(10, False))
        else:
            x, y, w, h = [v * sc for v in r]
            col = (60, 110, 160, 220) if nm.startswith("row") else (200, 170, 60, 220)
            d.rounded_rectangle((x, y, x + w, y + h), radius=3, fill=col)
            d.text((x + 3, y + 2), "party row (schematic)" if nm.startswith("row") else nm, fill=(255, 255, 255, 230), font=font(9, False))
    NB = G.WowCompanion.nbar
    if wings and NB.top.shown and len(G.PLACED) > 0:
        e = G.PLACED[len(G.PLACED)]
        s = float(e.s)
        for i, cx, cy in ((1, float(e.lx), float(e.ly)), (2, float(e.rx), float(e.ry))):
            rwm.draw_wings_half(img, NB, NB.f, i, cx * sc, cy * sc, s * sc)
    box = draw_bar(img, G, sc)
    if mark:
        for r in mark:
            d.rectangle((r[0] * sc, r[1] * sc, (r[0] + r[2]) * sc, (r[1] + r[3]) * sc), outline=(255, 60, 60), width=2)
    # the zoom on the bar (or the HUD's top when no bar)
    zw, zh = int(W * 0.42), int(H * 0.42)
    zc = (box[0] + box[2] / 2, box[1] + box[3] / 2) if box else (960, 860)
    big = Image.new("RGBA", (int(1920 * sc * zoom), int(1080 * sc * zoom)), (0, 0, 0, 0))
    try:
        bgb = Image.open(BG).convert("RGBA").resize(big.size, Image.LANCZOS)
        bgb = Image.blend(bgb, Image.new("RGBA", big.size, (20, 20, 24, 255)), 0.35)
        big = bgb
    except Exception:      # noqa: BLE001
        big = Image.new("RGBA", big.size, (52, 62, 74, 255))
    db = ImageDraw.Draw(big)
    z = sc * zoom
    for nm, r in twm.drawn_parts(G):
        x, y, w, h = [v * z for v in r]
        if nm == "bars":
            db.rounded_rectangle((x, y, x + w, y + h), radius=6, fill=(10, 10, 10, 200))
            db.rectangle((x + 5, y + 5, x + w * 0.82, y + h * 0.55), fill=(40, 200, 40, 255))
            db.rectangle((x + 5, y + h * 0.62, x + w * 0.66, y + h - 5), fill=(40, 90, 230, 255))
        else:
            db.rounded_rectangle((x, y, x + w, y + h), radius=4, fill=(60, 110, 160, 220) if nm.startswith("row") else (200, 170, 60, 220))
    if wings and NB.top.shown and len(G.PLACED) > 0:
        e = G.PLACED[len(G.PLACED)]
        s = float(e.s)
        for i, cx, cy in ((1, float(e.lx), float(e.ly)), (2, float(e.rx), float(e.ry))):
            rwm.draw_wings_half(big, NB, NB.f, i, cx * z, cy * z, s * z)
    draw_bar(big, G, z)
    cxz, cyz = zc[0] * z, zc[1] * z
    crop = big.crop((int(cxz - zw / 2), int(cyz - zh / 2), int(cxz + zw / 2), int(cyz + zh / 2)))
    out = Image.new("RGBA", (W + zw + 36, max(H, zh) + 86), (22, 22, 22, 255))
    out.alpha_composite(img, (12, 70))
    out.alpha_composite(crop, (W + 24, 70))
    do = ImageDraw.Draw(out)
    do.rectangle((W + 24, 70, W + 24 + zw, 70 + zh), outline=(120, 120, 120), width=1)
    do.text((12, 10), caption, fill=(240, 230, 200), font=font(18, False))
    do.text((12, 38), sub, fill=(200, 210, 200), font=font(13, False))
    do.text((W + 24, 70 + zh + 4), "zoom x%.1f on our bar" % zoom, fill=(180, 180, 170), font=font(12, False))
    return out


def save(im, name):
    os.makedirs(OUT, exist_ok=True)
    p = os.path.join(OUT, name)
    im.convert("RGB").save(p)
    return p


def fight(L, G, tick):
    tick(40)
    G.COMBAT = True
    tick(40)


# ------------------------------------------------------------------------------------------------------------------ the shots
def shot_default():
    L = boot()
    G = L.globals()
    tick = tick_fn(L)
    fight(L, G, tick)
    L.execute('CASTSTART("Frostbolt", "Frostbolt", 2.5)')
    tick(70)
    NB = G.WowCompanion.nbar
    sp = NB.top.spot
    im = draw_scene(G, L, 0.5, "Top-screen fight, a Frostbolt half cast: OUR bar right above the default HUD, the Wings beside it",
                    "Cast bar in fights = Top screen; the game's own casting bar art (UI-CastingBar-*), the name under the bar; Wings case %s"
                    % sp.case)
    return save(im, "01_fight_cast_default_hud.png")


def shot_party():
    frames = []
    L = boot()
    G = L.globals()
    tick = tick_fn(L, 1 / 30)
    G.PARTY = True
    G.HUD.members = 3
    G.HUD.x0 = 1100
    G.WowCompanion.prd.place()
    G.WowCompanion.uf.pf.draw()
    tick(30)
    G.PLACED = L.table()
    G.COMBAT = True
    L.execute('CASTSTART("Flash Heal", "Flash Heal", 3.0)')
    times = {3: "0.1 s: the fight starts", 9: "0.3 s: the block fades / moves", 36: "1.2 s: landed, centred"}
    for i in range(1, 37):
        tick(1)
        if i in times:
            frames.append(draw_scene(G, L, 0.34, "Party mode (3 members), %s" % times[i], "", zoom=2.2))
    w = max(f.width for f in frames)
    out = Image.new("RGBA", (w, sum(f.height for f in frames) + 40), (22, 22, 22, 255))
    d = ImageDraw.Draw(out)
    d.text((12, 8), "Party mode's centring: our bar moves with the HUD + party block, the Wings either side of both", fill=(240, 230, 200),
           font=font(18, False))
    y = 40
    for f in frames:
        out.alpha_composite(f, (0, y))
        y += f.height
    return save(out, "02_party_centred.png")


def shot_channel():
    L = boot()
    G = L.globals()
    tick = tick_fn(L)
    fight(L, G, tick)
    L.execute('CHANSTART("Arcane Missiles", "Arcane Missiles", 5.0)')
    tick(120)
    im = draw_scene(G, L, 0.5, "A channel: the game's channel fill DRAINING (Arcane Missiles, 2 s of 5)", "UI-CastingBar-Filling-Channel")
    return save(im, "03_channel.png")


def shot_interrupt():
    frames = []
    L = boot()
    G = L.globals()
    tick = tick_fn(L)
    fight(L, G, tick)
    L.execute('CASTSTART("Polymorph", "Polymorph", 1.7)')
    tick(50)
    L.execute('CASTCUT()')
    for n, lab in ((2, "just interrupted"), (55, "held (1 s)"), (72, "fading (1.2 s)")):
        tick(n if not frames else n - prev)
        prev = n
        frames.append(draw_scene(G, L, 0.4, "An interrupt: %s" % lab, "UI-CastingBar-Interrupted, the game's INTERRUPTED word", zoom=2.4))
    out = Image.new("RGBA", (max(f.width for f in frames), sum(f.height for f in frames)), (22, 22, 22, 255))
    y = 0
    for f in frames:
        out.alpha_composite(f, (0, y))
        y += f.height
    return save(out, "04_interrupt.png")


def shot_swing():
    frames = []
    L = boot()
    G = L.globals()
    tick = tick_fn(L)
    fight(L, G, tick)
    W = G.WowCompanion
    W.swingNote(2.6, 0)
    tick(70)
    frames.append(draw_scene(G, L, 0.4, "The swing with our bar: one hand (no cast)", "the swing line just OVER the bar, as wide as it", zoom=3.0))
    L.execute('UnitAttackSpeed = function() return 2.6, 1.8 end')
    W.swingNote(2.6, 0)
    W.swingNote(1.8, 1)
    tick(50)
    frames.append(draw_scene(G, L, 0.4, "Dual wield: the main hand on top, the off hand thinner under it, hugging the bar", "", zoom=3.0))
    L.execute('CASTSTART("Frostbolt", "Frostbolt", 2.5)')
    tick(40)
    frames.append(draw_scene(G, L, 0.4, "Dual wield with a cast: the swing over the bar, the cast in it, the name under it", "", zoom=3.0))
    out = Image.new("RGBA", (max(f.width for f in frames), sum(f.height for f in frames)), (22, 22, 22, 255))
    y = 0
    for f in frames:
        out.alpha_composite(f, (0, y))
        y += f.height
    return save(out, "05_swing.png")


def shot_disenchant():
    L = boot()
    G = L.globals()
    tick = tick_fn(L)
    tick(40)
    L.execute('CASTSTART("Disenchant", "Disenchant", 3.0)')
    tick(90)
    top = draw_scene(G, L, 0.4, "A Disenchant OUT of the takeover, Cast bar = Top screen: nothing on the top screen",
                     "the Wings are down, our bar never shows: the bottom screen's band carries the cast as ever", zoom=1.6)
    bottom = os.path.join(OUT, "bottom", "out_of_fight_top_setting_disenchant.png")
    if os.path.exists(bottom):
        b = Image.open(bottom).convert("RGBA")
        k = top.height / float(b.height)
        b = b.resize((int(b.width * k * 0.9), int(b.height * k * 0.9)), Image.LANCZOS)
        out = Image.new("RGBA", (top.width + b.width + 24, top.height), (22, 22, 22, 255))
        out.alpha_composite(top, (0, 0))
        out.alpha_composite(b, (top.width + 12, 40))
        ImageDraw.Draw(out).text((top.width + 12, 12), "the bottom screen (the app's own render): the band's cast line", fill=(240, 230, 200),
                                 font=font(14, False))
        top = out
    return save(top, "06_disenchant_out_of_combat.png")


def shot_crowded():
    L = boot()
    G = L.globals()
    L.execute('WowCompanion.nbar.topGame = function() return { { x = 0, y = 600, w = 760, h = 480, id = "g.ChatFrame1" }, '
              '{ x = 1160, y = 600, w = 760, h = 480, id = "g.ObjectiveTrackerFrame" } } end')
    tick = tick_fn(L)
    fight(L, G, tick)
    L.execute('CASTSTART("Frostbolt", "Frostbolt", 2.5)')
    tick(70)
    NB = G.WowCompanion.nbar
    walls = [(0, 600, 760, 480), (1160, 600, 760, 480)]
    im = draw_scene(G, L, 0.5, "Wings crowded (game windows either side, red): joined ABOVE the HUD block and our bar (case %s)" % NB.top.spot.case,
                    "our bar's room is part of the HUD block the Wings keep 60 px from", mark=walls, zoom=1.8)
    return save(im, "07_wings_crowded.png")


def shot_success():
    L = boot()
    G = L.globals()
    tick = tick_fn(L)
    fight(L, G, tick)
    L.execute('CASTSTART("Frostbolt", "Frostbolt", 1.0)')
    tick(58)
    L.execute('CASTDONE()')
    tick(8)
    im = draw_scene(G, L, 0.5, "A cast landing: the game's full bar and its glow (0.13 s in), faded by 0.5 s",
                    "UI-CastingBar-Full-Standard + UI-CastingBar-Full-Glow-Standard", zoom=2.6)
    return save(im, "08_success.png")


def main():
    made = [shot_default(), shot_party(), shot_channel(), shot_interrupt(), shot_swing(), shot_disenchant(), shot_crowded(), shot_success()]
    print("\n".join(made))
    print("stand-ins (not in the art root; the client draws the game's own): " + ", ".join(sorted(set(STANDIN))))


if __name__ == "__main__":
    main()
