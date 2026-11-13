"""THE TARGET PLATE PASS, off-device (the user, 2026-11-13: "I dont like the friendly Healthcare plates I want to turn those back off even
if it means losing our floating Y glyph"; "I want an animation that transitions an enemy targets name plates to the top of the screen when
targeting them and putting a sort of animated red pointer over the in place of it (the skull and !should be moving to the top as well)";
"I dont like the current Y button visual on the target plates I think that it should go back to just being the Y glyph there maybe with
the magnifying glass in its corner"; "The target bar in general seems kind of blurry"; the pick "Bobbing red arrow").

Trail.lua ag9 / at9 + Hub.lua uf18 in tools/render_top_party.py's rig (both add-ons in one lupa runtime on the compass tests' stub game,
every region's rect resolved from its anchors, SetScale honoured) with render_target_bar.py's stand-in nameplate:
  ag9   a friendly target writes no plate option; a value of ours left from before is given back out of a fight (never in one)
  ag11  Off hides them (the user on 1462: "friendly name plates still show with the setting off"): the player's own friendly plate
        options on -> 0 out of a fight (never in one, kept through one), their values kept and given back at log out / when the
        switch goes On; a change of theirs meanwhile stands
  uf18  a hostile target: its plate hidden (alpha), the head's copy starts ON the plate's bar (scaled) and lands on the bar (the bar at
        alpha 0 till then), the aggro tint held while it flies (at9), the bobbing red arrow on the plate (our texture, WorldFrame's
        child or UIParent here, under the plate's UnitFrame level), it bobs; losing the target flies the copy down and gives the plate
        back only when it lands
  uf18  the prompt: the bare Y glyph (no hudBox), the spyglass on its corner; PadMap's floating glyph gets the corner spyglass too
  uf18  Trail's skull / '!' for the target ride on the bar; Trail hides them on the plate while Hub owns it (ag9)
  uf19  the bar's own top-left snapped: every edge of the bar / its health bar on whole pixels at 720 / 900 / 1080 / 1200 / 1440 lines
  uf18  crisp: u() in whole physical pixels at K = 768 / 1080 (and at a 1440-line screen), the font shadow one pixel
  uf20  the smooth rim (the user, 2026-11-13: "The shaper bar still looks so fucking pixelated on the corners"): no texture of ours is
        NEAREST; every rim piece (target bar, level box, target of target, party rows' bars + level boxes) is our hud_rim_<s> art, LINEAR,
        and at 720 / 1080 / 1440 lines every piece's art has at least one texture pixel per screen pixel across the rim (corners both
        ways), at most 2 (no skipped texels) once a texel is a pixel or more; the pieces' sizes on whole pixels; the Media files exist
  uf21  the old corner shape, smooth (the user, 2026-11-13: "Square-notched, smooth"): the Media files' md5 + size; the corner cut at
        45 degrees (not rounded), its edge anti-aliased, the corner piece seamless against the lip piece
Needs lupa. Exit code 1 on any failure."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import render_target_bar as rtb  # noqa: E402
import render_top_party as rt  # noqa: E402


def rig_with(opts=None):
    rig = rt.Rig()
    rig.L.execute(rtb.SETUP)
    G = rig.G
    for k, v in (opts or {}).items():
        G.SHOT[k] = v
    return rig


def main():
    fails = []

    def chk(name, ok, got=''):
        print(("PASS " if ok else "FAIL ") + name + ((" -> " + str(got)) if got != '' and not ok else ''))
        if not ok:
            fails.append(name)

    # ------------------------------------------------------------------ ag9: friendly plates off
    rig = rig_with()
    L, G = rig.L, rig.G
    A = G.WowCompanion.live
    L.execute('''
CVW = {}
CV = CV or {}
CV.nameplateShowFriendlyPlayers, CV.nameplateShowFriendlyNPCs, CV.nameplateNotSelectedAlpha = "0", "0", "0.5"
C_CVar.SetCVar = function(k, v) CVW[#CVW + 1] = k .. "=" .. tostring(v); CV[k] = tostring(v) end
function UnitIsFriend(a, b) return true end
''')
    G.UNITS = L.table_from({"target": L.table_from({"name": "Guard Thomas", "lv": 10, "hp": 1, "hpMax": 1})})
    G.TARGET = "target"
    G.GAME.fight = False
    A.friendWas = None
    A.friendsStep()
    rig.run(0.5)
    chk("ag9: a friendly target switches no plate option on (%d writes)" % len(G.CVW), len(G.CVW) == 0, list(G.CVW.values()))
    chk("ag9: the version tag", A.V == "ag11" and G.WowCompanion.rings.V == "at9", (A.V, G.WowCompanion.rings.V))
    # a player who had them on from ag2..ag8: our values in place, the saved originals
    L.execute('''
CV.nameplateShowFriendlyPlayers, CV.nameplateShowFriendlyNPCs, CV.nameplateNotSelectedAlpha = "1", "1", "0"
WowCompanionDB.npFriends = { orig = { nameplateShowFriendlyPlayers = "0", nameplateShowFriendlyNPCs = "0", nameplateNotSelectedAlpha = "0.5" } }
''')
    G.GAME.fight = True
    A.friendsStep()
    chk("ag9: ... in a fight nothing is written (the options are refused there)", len(G.CVW) == 0 and G.WowCompanionDB.npFriends is not None)
    G.GAME.fight = False
    A.friendsStep()
    chk("ag9: ... out of the fight the player's own values come back, once (%s)" % ",".join(G.CVW.values()),
        G.CV.nameplateShowFriendlyPlayers == "0" and G.CV.nameplateShowFriendlyNPCs == "0" and G.CV.nameplateNotSelectedAlpha == "0.5"
        and G.WowCompanionDB.npFriends is None)
    n = len(G.CVW)
    A.friendsStep()
    A.friendsStep()
    chk("ag9: ... and never again", len(G.CVW) == n)
    L.execute('WowCompanionDB.npFriendsSet = true; CV.nameplateShowFriends = "1"')
    A.friendsStep()
    chk("ag9: test30's old on (npFriendsSet) is put back too", G.CV.nameplateShowFriends == "0" and G.WowCompanionDB.npFriendsSet is None)

    # ------------------------------------------------------------------ uf18: the fly, the plate, the arrow
    rig = rig_with({"plate": True})
    L, G = rig.L, rig.G
    WC = G.WowCompanion
    UF = rig.UF
    TB = UF.tb
    WC.live.iconsStep = L.eval("function() end")
    WC.live.tgtIcons = L.table_from({"av": True, "q": True})
    rig.scene(dict(n=1, pet=False, mode=False, pick=None, dead=None, oor=None))
    fly = UF.fly
    fly.tick(G.GAME.t + 10)
    chk("uf22: the version tag", UF.V == "uf22", UF.V)
    plateUF = G.PLATE.UnitFrame
    chk("uf18: a hostile target's plate hidden while targeted (alpha 0; no attack needed)", plateUF.alpha == 0, plateUF.alpha)
    rig.fire("PLAYER_TARGET_CHANGED")
    t0 = G.GAME.t
    hb = rig.rect(plateUF.HealthBarsContainer)
    cp = rig.rect(fly.host.hp)
    chk("uf18: the copy's first frame sits ON the plate's bar (centre %.1f,%.1f vs %.1f,%.1f; width %.1f vs %.1f)"
        % ((cp[0] + cp[2]) / 2, (cp[1] + cp[3]) / 2, (hb[0] + hb[2]) / 2, (hb[1] + hb[3]) / 2, cp[2] - cp[0], hb[2] - hb[0]),
        abs((cp[0] + cp[2]) / 2 - (hb[0] + hb[2]) / 2) < 0.6 and abs((cp[1] + cp[3]) / 2 - (hb[1] + hb[3]) / 2) < 0.6
        and abs((cp[2] - cp[0]) - (hb[2] - hb[0])) < 0.6)
    chk("uf18: ... scaled down (%.2f), the bar itself at alpha 0, the fly 'up'" % fly.s,
        fly.s < 0.5 and TB.f.alpha == 0 and fly.dir == "up" and UF.flying() is True)
    names = [fly.host.name.text, fly.host.lvl.text]
    chk("uf18: the copy is the whole head: the name, the level, Trail's skull and '!' (%s)" % names,
        names[0] == "Kobold Tunneler" and str(names[1]) == "5" and fly.host.av.shown is True and fly.host.q.shown is True)
    fly.tick(t0 + UF.FLY_S / 2)
    mid = rig.rect(fly.host.hp)
    bar = rig.rect(TB.f.hp)
    chk("uf18: half way it is between the plate and the bar (y %.0f in %.0f..%.0f), bigger (%.2f)" % ((mid[1] + mid[3]) / 2, hb[1], bar[3], fly.s),
        hb[3] < (mid[1] + mid[3]) / 2 < bar[1] and 0.5 < fly.s < 1)
    # the aggro tint waits for the landing (at9): WC.uf.flying is what Trail reads
    chk("at9: Trail's tint reads the fly (UF.flying true mid-air)", UF.flying() is True)
    fly.tick(t0 + UF.FLY_S + 0.01)
    chk("uf18: landed: the copy gone, the bar at alpha 1", fly.host.shown is False and TB.f.alpha == 1 and UF.flying() is False)
    rig.run(0.2)
    ar = TB.arrow
    chk("uf18: the arrow: shown, our texture, anchored to the plate's top", ar is not None and ar.shown is True
        and "target_arrow" in str(ar.t.tex) and rig.L.eval("rawequal")(ar.point[2], G.PLATE) and ar.point[1] == "TOP")
    chk("uf18: ... one level under the plate's UnitFrame (5 -> 4), its strata", ar.level == 4 and ar.strata == "BACKGROUND", (ar.level, ar.strata))
    ys = []
    for i in range(12):
        G.GAME.t = G.GAME.t + TB.ARROW.PERIOD / 12
        ar.scripts.OnUpdate(ar, TB.ARROW.PERIOD / 12)
        ys.append(ar.t.point[5])
    chk("uf18: ... it bobs (%.0f..%.0f px over %.1f s), fully faded in" % (min(ys), max(ys), TB.ARROW.PERIOD),
        max(ys) - min(ys) >= TB.ARROW.BOB - 1.5 and ar.alpha == 1)
    ac = rig.rect(ar)
    pr = rig.rect(G.PLATE)
    chk("uf18: ... inside the plate's top (arrow top %.0f <= plate top %.0f)" % (ac[3], pr[3]), ac[3] <= pr[3] + 0.01)
    # the target lost: the copy flies down, the plate stays hidden under it, back when it lands
    G.TARGET = None
    G.UNITS = L.table_from({})
    rig.fire("PLAYER_TARGET_CHANGED")
    t1 = G.GAME.t
    chk("uf18: target lost: the copy flies down, the arrow goes, the plate still hidden under the copy",
        fly.dir == "down" and ar.shown is False and plateUF.alpha == 0 and TB.f.shown is False)
    fly.tick(t1 + UF.FLY_S + 0.01)
    chk("uf18: ... landed on the plate: the plate given back", plateUF.alpha == 1 and fly.dir is None, plateUF.alpha)

    # ------------------------------------------------------------------ uf18: the prompt, the corner spyglass, the icons
    rig = rig_with({"plate": True})
    L, G = rig.L, rig.G
    WC = G.WowCompanion
    UF = rig.UF
    TB = UF.tb
    WC.tgPrompt = "Gamepad_Rev_Y_64"
    WC.live.iconsStep = L.eval("function() end")
    WC.live.tgtIcons = L.table_from({"av": True, "q": False})
    G.YG.Show(G.YG)
    rig.scene(dict(n=1, pet=False, mode=False, pick=None, dead=None, oor=None))
    UF.fly.tick(G.GAME.t + 10)
    rig.run(0.2)
    p = TB.f.prompt
    kids = [G.ALLR[i] for i in range(1, len(G.ALLR) + 1) if G.ALLR[i].parent is p]
    borders = [k for k in kids if k.layer == "BORDER"]
    chk("uf18: the prompt is the bare glyph: its atlas over the whole frame, no box edge (%d BORDER pieces)" % len(borders),
        p.shown is True and p.btn.atlas == "Gamepad_Rev_Y_64" and not borders and p.btn.point is not None)
    pq, iq = rig.rect(p), rig.rect(p.icon)
    chk("uf18: ... the spyglass on its lower right corner (its centre %.0f,%.0f; the glyph %.0f..%.0f x %.0f..%.0f)"
        % ((iq[0] + iq[2]) / 2, (iq[1] + iq[3]) / 2, pq[0], pq[2], pq[1], pq[3]),
        "Spyglass" in str(p.icon.tex) and (iq[0] + iq[2]) / 2 > (pq[0] + pq[2]) / 2 and (iq[1] + iq[3]) / 2 < (pq[1] + pq[3]) / 2
        and (iq[2] - iq[0]) < (pq[2] - pq[0]) * 0.6)
    gf = TB.glassF
    gq, yq = (rig.rect(gf.icon) if gf is not None else None), rig.rect(G.YG)
    chk("uf18: PadMap's floating Y glyph gets the corner spyglass (Hub's own frame on it)", gf is not None and gf.shown is True
        and (gq[0] + gq[2]) / 2 > (yq[0] + yq[2]) / 2 and (gq[1] + gq[3]) / 2 < (yq[1] + yq[3]) / 2)
    G.YG.Hide(G.YG)
    rig.run(0.2)
    chk("uf18: ... and goes with it", TB.glassF.shown is False)
    chk("uf18: Trail's skull rides on the bar right of the name, no '!' when the plate has none",
        TB.f.av.shown is True and TB.f.q.shown is False and rig.L.eval("rawequal")(TB.f.av.point[2], TB.f.name))
    chk("uf18: Hub owns the target's plate (Trail hides its icons there)", UF.ownsPlate() is True)

    # ------------------------------------------------------------------ ag9: Trail's plate icons for the target (Trail's own iconsStep)
    rig3 = rig_with({"plate": True})
    L3, G3 = rig3.L, rig3.G
    A3 = G3.WowCompanion.live
    L3.execute('''
function UnitIsUnit(a, b) return b == "target" and a == "nameplate1" end
local other = CreateFrame("Frame")
C_NamePlate.GetNamePlateForUnit = function(u) if u == "nameplate1" then return PLATE elseif u == "nameplate3" then return other end end
''')
    A3.slot = L3.eval('{ [0] = { tok = "nameplate1", at = 0 }, [1] = { tok = "nameplate3", at = 0 } }')
    G3.WowCompanion.uf.ownsPlate = L3.eval("function() return true end")
    A3.iconsStep(L3.eval('{ { s = 0, c = "e", lv = 5, qi = 1 }, { s = 1, c = "e", lv = 5 } }'))
    ti = A3.tgtIcons
    o0, o1 = A3.icon[0], A3.icon[1]
    chk("ag9: the target's icons kept for the bar (av %s, q %s), its plate's icons at alpha 0, another plate's at 1"
        % (ti and ti.av, ti and ti.q), ti is not None and ti.av is True and ti.q is True and o0.f.alpha == 0 and o1.f.alpha == 1)
    G3.WowCompanion.uf.ownsPlate = L3.eval("function() return false end")
    A3.iconsStep(L3.eval('{ { s = 0, c = "e", lv = 5, qi = 1 } }'))
    chk("ag9: ... Hub not showing it: back at 1", A3.icon[0].f.alpha == 1)

    # ------------------------------------------------------------------ uf18: crisp
    rig = rig_with()
    G = rig.G
    G.GAME.physH = 1080
    rig.set_k(768)
    UF = rig.UF
    px = UF.px()
    chk("uf18: one physical pixel at a 768 UI height on 1080 lines = 768 / 1080 (%.4f)" % px, abs(px - 768 / 1080) < 1e-9)
    vals = [UF.u(v) for v in (148.8, 1.5, 0.875, 372, 24, 9.4)]
    chk("uf18: u() lands on whole pixels (%s)" % ["%.3f" % (v / px) for v in vals],
        all(abs(v / px - round(v / px)) < 1e-9 for v in vals))
    G.GAME.physH = 1440
    rig.set_k(768)
    px2 = UF.px()
    v = UF.u(16)
    chk("uf18: a 1440-line screen: the pixel is 768 / 1440 and u(16) still whole pixels (%.3f px)" % (v / px2),
        abs(px2 - 768 / 1440) < 1e-9 and abs(v / px2 - round(v / px2)) < 1e-9)
    G.GAME.physH = 1080
    rig.set_k(768)
    rig2 = rig_with()
    rig2.L.execute('''
local R0 = getmetatable(CreateFrame("Frame"))
function R0:SetShadowOffset(x, y) rawset(self, "shadow", { x, y }) end
''')
    rig2.G.GAME.physH = 1080
    rig2.set_k(768)
    rig2.scene(dict(n=1, pet=False, mode=False, pick=None, dead=None, oor=None))
    nm = rig2.UF.tb.f.name
    sh = nm.shadow
    chk("uf18: the font shadow is one physical pixel (%s)" % str(sh and (sh[1], sh[2])),
        sh is not None and abs(sh[1] - 768 / 1080) < 1e-9 and abs(sh[2] + 768 / 1080) < 1e-9)
    A2 = rig2.G.ALLR
    tbf = rig2.UF.tb.f

    def under_tb(r):
        o = r.parent
        while o is not None and not rig2.L.eval('rawequal')(o, rig2.G.UIParent):
            if rig2.L.eval('rawequal')(o, tbf):
                return True
            o = o.parent
        return False
    rim = [A2[i] for i in range(1, len(A2) + 1) if A2[i].kind == "tex" and "hud_rim_" in str(A2[i].tex or "") and under_tb(A2[i])]
    chk("uf20: the target bar's rim pieces are our art, LINEAR (%d pieces)" % len(rim), len(rim) >= 9 * 2 + 8 and all(r.filter == "LINEAR" for r in rim))
    old = [A2[i] for i in range(1, len(A2) + 1) if A2[i].kind == "tex" and "CoolDownManager-Bar-BG" in str(A2[i].tex or "") and under_tb(A2[i])]
    chk("uf20: none of the game's 19-texel HUD art left on the target bar (%d)" % len(old), len(old) == 0)
    hub_src = open(rt.HUB, encoding="utf-8").read()
    chk("uf20: no NEAREST filter in Hub.lua's code (crispTex / TB.FILTER gone)",
        '"NEAREST"' not in hub_src and "crispTex" not in hub_src and 'local HV = "hub53"' in hub_src)

    # ------------------------------------------------------------------ uf20: the smooth rim at 720 / 1080 / 1440 lines
    media = os.path.join(rt.ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanion", "Media")
    from PIL import Image
    sizes = {}
    for lv in (2, 4, 8, 16):
        f = os.path.join(media, "hud_rim_%d.tga" % lv)
        sizes[lv] = Image.open(f).size if os.path.exists(f) else None
    chk("uf20: the Media files hud_rim_2/4/8/16.tga, power-of-two, 16 x 32 art texels each (%s)" % sizes,
        all(sizes[lv] == (16 * lv, 32 * lv) for lv in sizes))
    # uf21 (the user, 2026-11-13, picking the corner: "Square-notched, smooth"): the files are tools/make_hud_rim.py's output as committed
    import hashlib
    RIM_MD5 = {2: ("be6cb6c7a421712b67590d37c3902a04", 8236), 4: ("7049448be55e975fc1fdb7d089a1d547", 32812),
               8: ("9ce20dc4881030cbf82f26439af3b027", 131116), 16: ("5648b662e55d9658c707f5707d844969", 524332)}
    got = {}
    for lv in RIM_MD5:
        f = os.path.join(media, "hud_rim_%d.tga" % lv)
        got[lv] = (hashlib.md5(open(f, "rb").read()).hexdigest(), os.path.getsize(f)) if os.path.exists(f) else None
    chk("uf21: hud_rim_2/4/8/16.tga are the notched art (md5 + size) %s" % {lv: got[lv] for lv in got if got[lv] != RIM_MD5[lv]},
        got == RIM_MD5)
    # the corner's shape and finish, read off the art: the corner texel cut away, the 45-degree edge anti-aliased (many partial alphas
    # along it, no hard 0 / 255 step), no round bulge (the cut's midpoint is outside), the corner piece meets the lip piece with no seam
    im16 = Image.open(os.path.join(media, "hud_rim_16.tga")).convert("RGBA")
    A16 = [[im16.getpixel((x, y))[3] for x in range(64)] for y in range(64)]
    cut_mid = A16[int(1.5 * 16) - 4][int(1.5 * 16) - 4]           # inside x + y < 3 by about 0.5 texel: transparent when cut straight
    diag = [A16[i][47 - i] for i in range(48)]                   # across the 45-degree edge (x + y = 3 texels)
    partial = len({v for v in diag if 0 < v < 255})
    chk("uf21: the rim's corner is cut at 45 degrees, not rounded (corner %d, inside the cut %d, on the straight side %d)"
        % (A16[0][0], cut_mid, A16[8][60]), A16[0][0] == 0 and cut_mid == 0 and A16[8][60] >= 150)
    chk("uf21: the cut edge is anti-aliased (%d partial alphas across the diagonal)" % partial, partial >= 6)
    # where the corner piece ends (x = 3 texels) the art is the straight lip's own: every band's diagonal ends on that edge
    seam = max(abs(im16.getpixel((48, y))[c] - im16.getpixel((64, y))[c]) for y in range(48) for c in range(4))
    chk("uf21: the art at the corner piece's edge (x = 3 texels) is the lip's own (seamless nine-slice, max diff %d)" % seam, seam <= 2)
    THIN_X = {(0, 3), (3, 0), (9, 16)}
    THIN_Y = {(0, 3), (3, 0), (11, 19)}
    for ph in (720, 1080, 1440):
        r = rig_with()
        r.G.GAME.physH = ph
        r.set_k(768)
        r.scene(dict(n=3, pet=False, mode=False, pick=None, dead=None, oor=None))
        r.UF.tb.f.tot.Show(r.UF.tb.f.tot)
        p = r.UF.px()
        A3 = r.G.ALLR
        allt = [A3[i] for i in range(1, len(A3) + 1) if A3[i].kind == "tex"]
        chk("uf20: %d lines: no texture of ours asks for NEAREST" % ph, not any(str(t.filter) == "NEAREST" for t in allt if t.filter is not None))
        pieces = [t for t in allt if "hud_rim_" in str(t.tex or "")]
        owners = set()
        bad, worst, snapbad = [], [9, 0], []
        for t in pieces:
            lv = int(str(t.tex).rsplit("_", 1)[1])
            c = t.coord or t.tc
            cl, cr, ct, cb = round(c[1] * 16, 6), round(c[2] * 16, 6), round(c[3] * 32, 6), round(c[4] * 32, 6)
            q = r.rect(t)
            w, h = (q[2] - q[0]) / p, (q[3] - q[1]) / p
            for ax, (a0, a1), size in (("x", (cl, cr), w), ("y", (ct, cb), h)):
                pair = (int(round(a0)), int(round(a1)))
                if pair not in (THIN_X if ax == "x" else THIN_Y):
                    continue
                if abs(size - round(size)) > 1e-6:
                    snapbad.append((str(t.tex), ax, size))
                texpx = abs(a1 - a0) * lv
                ratio = texpx / size if size > 0 else 0
                kp = size / abs(a1 - a0)                     # one art texel in screen pixels
                worst = [min(worst[0], ratio), max(worst[1], ratio if kp >= 1 else 0)]
                if ratio < 1 - 1e-9 or (kp >= 1 and ratio > 2.2):
                    bad.append((lv, ax, pair, round(size, 2), round(ratio, 2)))
            o = t.parent
            while o is not None:
                for i in range(1, 5):
                    if r.PF.frames[i] is not None and r.L.eval('rawequal')(o, r.PF.frames[i]):
                        owners.add("party%d" % i)
                o = o.parent
        chk("uf20: %d lines: %d rim pieces, every one LINEAR" % (ph, len(pieces)),
            len(pieces) > 60 and all(str(t.filter) == "LINEAR" for t in pieces))
        chk("uf20: %d lines: the art never magnified, never shrunk past 2x across the rim (texture px per screen px %.2f..%.2f) %s"
            % (ph, worst[0], worst[1], bad[:4]), not bad and worst[0] >= 1 - 1e-9)
        chk("uf20: %d lines: the rim's thickness on whole pixels %s" % (ph, snapbad[:3]), not snapbad)
        chk("uf20: %d lines: the party rows wear it too (%s)" % (ph, sorted(x for x in owners if x)[:4]),
            len(owners) >= 2)
        tbf = r.UF.tb.f
        files = {str(tbf.hp.rimFile), str(tbf.shield.rimFile), str(tbf.totHp.rimFile)}
        want = {720: "hud_rim_2", 1080: "hud_rim_4", 1440: "hud_rim_4"}[ph]
        chk("uf20: %d lines: the target bar's level is %s (a texel %.2f px) %s" % (ph, want, 3 * 768 / 1080 / p, files),
            str(tbf.hp.rimFile).endswith(want))

    # ------------------------------------------------------------------ uf19: the bar's own spot on whole pixels (review of uf18)
    # the rig's UIParent rect is made its real size (its GetWidth / GetHeight: ww x hh) so a 768-unit UIParent on 720 / 1080 / 1440
    # lines resolves as the client does; every edge of the bar and its health bar must land on a whole physical pixel
    def placed(ph, uih=768, compass=True):
        r = rig_with()
        r.L.execute('''
local RECT0 = RECT
function RECT(q)
    if q == nil or rawequal(q, UIParent) then return { 0, 0, UIParent.ww or 1920, UIParent.hh or 1080 } end
    return RECT0(q)
end
local R0 = getmetatable(CreateFrame("Frame"))
local gw = R0.GetWidth
function R0:GetWidth() if rawequal(self, UIParent) then return self.ww or 1920 end return gw(self) end
''')
        if compass:
            r.G.WowCompanion.compass.build()                 # the compass the bar sits under (built on its first show in the game)
        r.G.GAME.physH = ph
        r.set_k(uih)
        r.scene(dict(n=1, pet=False, mode=False, pick=None, dead=None, oor=None))
        p = r.UF.px()
        out = []
        for reg in (r.UF.tb.f, r.UF.tb.f.hp):
            q = r.rect(reg)
            out += [v / p for v in q]
        return r, out
    for ph in (720, 900, 1080, 1200, 1440):
        r3, e = placed(ph)
        chk("uf19: %d lines: the bar's and its health bar's edges on whole pixels (%s)" % (ph, ["%.2f" % v for v in e]),
            all(abs(v - round(v)) < 1e-6 for v in e))
    r3, e = placed(720)
    c = r3.G.WowCompanion.compass.frame
    tb = r3.rect(r3.UF.tb.f)
    cq = r3.rect(c) if c is not None else None
    px3 = r3.UF.px()
    chk("uf19: 720 lines: the bar still under the compass (gap %.2f px, within a pixel of u(6)) and centred on it + SHIFT (%.2f)"
        % ((cq[1] - tb[3]) / px3 if cq else -1, ((tb[0] + tb[2]) / 2 - (cq[0] + cq[2]) / 2) / px3 if cq else -1),
        cq is not None and abs((cq[1] - tb[3]) - r3.UF.u(6)) <= px3 + 1e-9
        and abs((tb[0] + tb[2]) / 2 - (cq[0] + cq[2]) / 2 - r3.UF.u(16)) <= px3 + 1e-9)
    r4, e = placed(720, compass=False)
    chk("uf19: no compass frame: the spot by u(90) is snapped too (%s)" % ["%.2f" % v for v in e[:4]],
        all(abs(v - round(v)) < 1e-6 for v in e))

    # ------------------------------------------------------------------ ag10 / hub52: the friendly plates switch (the user, 2026-11-13:
    # "friendly plates off ... maybe we should set this as a toggle defaulted to off")
    rig = rig_with()
    L, G = rig.L, rig.G
    WC = G.WowCompanion
    A = WC.live
    L.execute('''
CVW = {}
CV = CV or {}
CV.nameplateShowFriendlyPlayers, CV.nameplateShowFriendlyNPCs, CV.nameplateNotSelectedAlpha = "0", "0", "0.5"
C_CVar.SetCVar = function(k, v) CVW[#CVW + 1] = k .. "=" .. tostring(v); CV[k] = tostring(v) end
function UnitIsFriend(a, b) return true end
''')
    G.UNITS = L.table_from({"target": L.table_from({"name": "Guard Thomas", "lv": 10, "hp": 1, "hpMax": 1})})
    G.TARGET = "target"
    G.GAME.fight = False
    hub = WC.hub
    chk("ag10: the version tag; the switch Off by default, said in the doc (npf false, not chosen)",
        A.V == "ag11" and A.FRIENDS_ON is False and hub.npfFlag() is False and A.friendsChosen is None, (A.V, A.FRIENDS_ON))
    A.friendsStep()
    chk("ag10: Off: a friendly target writes nothing (ag9)", len(G.CVW) == 0, list(G.CVW.values()))
    chk("hub52: NPF_ON (column 39) reaches Trail", hub.swTap(39) is True and A.FRIENDS_ON is True and hub.npfFlag() is True
        and A.friendsChosen is True)
    G.GAME.fight = True
    A.friendsStep()
    chk("ag10: On, in a fight: nothing written", len(G.CVW) == 0)
    G.GAME.fight = False
    A.friendsStep()
    chk("ag10: On: a friendly target turns the friendly plates on, the player's own values kept (%s)" % ",".join(G.CVW.values()),
        G.CV.nameplateShowFriendlyPlayers == "1" and G.CV.nameplateShowFriendlyNPCs == "1" and G.CV.nameplateNotSelectedAlpha == "0"
        and G.WowCompanionDB.npFriends is not None and G.WowCompanionDB.npFriends.orig.nameplateNotSelectedAlpha == "0.5")
    G.TARGET = None
    G.UNITS = L.table_from({})
    A.friendsStep()
    chk("ag10: On: no friendly target puts them back", G.CV.nameplateShowFriendlyPlayers == "0" and G.CV.nameplateNotSelectedAlpha == "0.5"
        and G.WowCompanionDB.npFriends is None)
    G.UNITS = L.table_from({"target": L.table_from({"name": "Guard Thomas", "lv": 10, "hp": 1, "hpMax": 1})})
    G.TARGET = "target"
    A.friendsStep()
    chk("ag10: ... a friend again: on again", G.CV.nameplateShowFriendlyPlayers == "1")
    chk("hub52: NPF_OFF (column 38) reaches Trail", hub.swTap(38) is True and A.FRIENDS_ON is False and hub.npfFlag() is False)
    A.friendsStep()
    n = len(G.CVW)
    chk("ag10: Off with the friend still targeted: ours put back at once out of a fight",
        G.CV.nameplateShowFriendlyPlayers == "0" and G.CV.nameplateNotSelectedAlpha == "0.5" and G.WowCompanionDB.npFriends is None)
    A.friendsStep()
    chk("ag10: ... and nothing more written", len(G.CVW) == n)

    # ------------------------------------------------------------------ ag11: Off hides the player's OWN friendly plates (the user on
    # build 1462: "friendly name plates still show with the setting off")
    rig = rig_with()
    L, G = rig.L, rig.G
    WC = G.WowCompanion
    A = WC.live
    hub = WC.hub
    liveEv = [r for r in rig._frames() if r.events and r.events.NAME_PLATE_UNIT_ADDED and r.scripts][0]

    def fire(e):
        liveEv.scripts.OnEvent(liveEv, e, None)

    def steps(k=3):
        for _ in range(k):
            A.friendsStep()
    L.execute('''
CVW = {}
CV = CV or {}
CV.nameplateShowFriendlyPlayers, CV.nameplateShowFriendlyNPCs, CV.nameplateNotSelectedAlpha = "1", "1", "0.5"
CV.nameplateShowFriends = nil
C_CVar.SetCVar = function(k, v) CVW[#CVW + 1] = k .. "=" .. tostring(v); CV[k] = tostring(v) end
function UnitIsFriend(a, b) return true end
WowCompanionDB.npFriends = nil; WowCompanionDB.npFriendsOff = nil
''')
    G.UNITS = L.table_from({"target": L.table_from({"name": "Guard Thomas", "lv": 10, "hp": 1, "hpMax": 1})})
    G.TARGET = "target"
    G.GAME.fight = True
    A.friendsStep()
    chk("ag11: Off, the game's own friendly plates on, in a fight: nothing written", len(G.CVW) == 0, list(G.CVW.values()))
    G.GAME.fight = False
    steps()
    chk("ag11: Off: out of the fight the game's own friendly player + NPC plates go off, the player's values kept (%s)"
        % ",".join(G.CVW.values()),
        G.CV.nameplateShowFriendlyPlayers == "0" and G.CV.nameplateShowFriendlyNPCs == "0" and G.CV.nameplateNotSelectedAlpha == "0.5"
        and G.CV.nameplateShowFriends is None and G.WowCompanionDB.npFriendsOff is not None
        and G.WowCompanionDB.npFriendsOff.orig.nameplateShowFriendlyPlayers == "1"
        and G.WowCompanionDB.npFriendsOff.orig.nameplateShowFriendlyNPCs == "1")
    n = len(G.CVW)
    steps()
    chk("ag11: ... written once (%d)" % n, len(G.CVW) == n == 2)
    G.GAME.fight = True
    fire("PLAYER_REGEN_DISABLED")
    steps()
    chk("ag11: ... a fight keeps them off", G.CV.nameplateShowFriendlyPlayers == "0" and G.WowCompanionDB.npFriendsOff is not None
        and len(G.CVW) == n)
    fire("PLAYER_LOGOUT")
    chk("ag11: ... log out (even in a fight) gives the player's own values back",
        G.CV.nameplateShowFriendlyPlayers == "1" and G.CV.nameplateShowFriendlyNPCs == "1" and G.WowCompanionDB.npFriendsOff is None)
    # a new session: off again; the switch goes On -> the player's own values first, then On's rule
    G.GAME.fight = False
    steps()
    chk("ag11: next session: off again", G.CV.nameplateShowFriendlyPlayers == "0" and G.WowCompanionDB.npFriendsOff is not None)
    G.CVW = L.table_from({})
    chk("ag11: NPF_ON reaches Trail", hub.swTap(39) is True and A.FRIENDS_ON is True)
    steps()
    chk("ag11: On: the player's own values back, then a friendly target: On's rule (only the alpha was not theirs) (%s)"
        % ",".join(G.CVW.values()),
        G.CV.nameplateShowFriendlyPlayers == "1" and G.CV.nameplateShowFriendlyNPCs == "1" and G.CV.nameplateNotSelectedAlpha == "0"
        and G.WowCompanionDB.npFriendsOff is None and G.WowCompanionDB.npFriends is not None
        and G.WowCompanionDB.npFriends.orig.nameplateShowFriendlyPlayers is None
        and G.WowCompanionDB.npFriends.orig.nameplateNotSelectedAlpha == "0.5")
    chk("ag11: NPF_OFF reaches Trail", hub.swTap(38) is True and A.FRIENDS_ON is False)
    steps()
    chk("ag11: Off again: On's alpha back, the plates off, the player's values kept",
        G.CV.nameplateNotSelectedAlpha == "0.5" and G.CV.nameplateShowFriendlyPlayers == "0" and G.CV.nameplateShowFriendlyNPCs == "0"
        and G.WowCompanionDB.npFriends is None and G.WowCompanionDB.npFriendsOff.orig.nameplateShowFriendlyPlayers == "1")
    # the player turns friendly player plates on in the game's own menu: their choice stands (not fought, not restored)
    L.execute('CV.nameplateShowFriendlyPlayers = "1"')
    n = len(G.CVW)
    steps(5)
    chk("ag11: the player's own change in the game stands (not fought)",
        G.CV.nameplateShowFriendlyPlayers == "1" and len(G.CVW) == n
        and G.WowCompanionDB.npFriendsOff.orig.nameplateShowFriendlyPlayers is None
        and G.WowCompanionDB.npFriendsOff.orig.nameplateShowFriendlyNPCs == "1")
    fire("PLAYER_LOGOUT")
    chk("ag11: ... log out gives back only what is still ours", G.CV.nameplateShowFriendlyPlayers == "1"
        and G.CV.nameplateShowFriendlyNPCs == "1" and G.WowCompanionDB.npFriendsOff is None)
    # a /reload in a fight where the game refuses the write: the entry is kept for the next chance
    G.CVW = L.table_from({})
    steps()
    L.execute('local real = C_CVar.SetCVar; REFUSE = true; C_CVar.SetCVar = function(k, v) if REFUSE then return false end; return real(k, v) end')
    fire("PLAYER_LOGOUT")
    chk("ag11: a refused give-back keeps its entry (the declined one stays the player's)", G.CV.nameplateShowFriendlyNPCs == "0"
        and G.CV.nameplateShowFriendlyPlayers == "1" and G.WowCompanionDB.npFriendsOff is not None
        and G.WowCompanionDB.npFriendsOff.orig.nameplateShowFriendlyNPCs == "1"
        and G.WowCompanionDB.npFriendsOff.orig.nameplateShowFriendlyPlayers is None)
    L.execute('REFUSE = false')
    fire("PLAYER_LOGOUT")
    chk("ag11: ... given back at the next log out", G.CV.nameplateShowFriendlyPlayers == "1" and G.CV.nameplateShowFriendlyNPCs == "1"
        and G.WowCompanionDB.npFriendsOff is None)
    # already off in the game: nothing written, nothing recorded
    L.execute('CV.nameplateShowFriendlyPlayers, CV.nameplateShowFriendlyNPCs = "0", "0"')
    A.offDeclined = L.table_from({})
    n = len(G.CVW)
    steps()
    chk("ag11: already off in the game: nothing written or recorded", len(G.CVW) == n and G.WowCompanionDB.npFriendsOff is None)

    # ------------------------------------------------------------------ uf22: the bar on top, the glyph sizes
    rig = rig_with({"plate": True})
    L, G = rig.L, rig.G
    WC = G.WowCompanion
    UF = rig.UF
    TB = UF.tb
    WC.tgPrompt = "Gamepad_Rev_Y_64"
    L.execute("PLATE.UnitFrame.GetFrameStrata = function() return 'MEDIUM' end; PLATE.UnitFrame.GetFrameLevel = function() return 90 end; "
              "YG:SetFrameStrata('MEDIUM'); YG:SetFrameLevel(5); YG:SetSize(UFY(), UFY()); "
              "function YG:GetFrameStrata() return rawget(self, 'strata') end; function YG:GetFrameLevel() return rawget(self, 'level') end"
              .replace("UFY()", "WowCompanion.uf.yPlate()"))
    G.YG.Show(G.YG)
    rig.scene(dict(n=1, pet=False, mode=False, pick=None, dead=None, oor=None))
    UF.fly.tick(G.GAME.t + 10)
    rig.run(0.2)
    chk("uf22: the target bar and the fly's copy in MEDIUM at level 40 (%s %s / %s %s)" % (TB.f.strata, TB.f.level, UF.fly.host.strata, UF.fly.host.level),
        TB.f.strata == "MEDIUM" and TB.f.level == 40 and UF.fly.host.strata == "MEDIUM" and UF.fly.host.level == 40)
    ar = TB.arrow
    chk("uf22: a plate in the bar's strata: the arrow one strata under it (%s)" % ar.strata, ar.strata == "LOW")
    gf = TB.glassF
    chk("uf22: the spyglass in the glyph's strata, two levels over it, under the bar (%s %s)" % (gf.strata, gf.level),
        gf.strata == "MEDIUM" and gf.level == 7 and gf.level < TB.f.level)
    pq = rig.rect(TB.f.prompt)
    yq = rig.rect(G.YG)
    bw, pw = pq[2] - pq[0], yq[2] - yq[0]
    chk("uf22: the bar's glyph u(50) (the plate glyph's old size), the plate's 0.85 of it (%.1f / %.1f)" % (bw, pw),
        abs(bw - UF.u(50)) < 1e-6 and abs(pw / bw - 0.85) < 0.02)
    L.execute("YG:SetFrameLevel(45)")
    rig.run(0.2)
    chk("uf22: a glyph at the bar's level: the bar raised over it (%s > %s)" % (TB.f.level, gf.level), TB.f.level > gf.level)
    pm_src = open(os.path.join(rt.ROOT, *rtb.PADMAP_REL.split("/")), encoding="utf-8").read()
    chk("m155: PadMap's floating glyph in MEDIUM level 5, sized by Hub's UF.yPlate", rtb.glyph_layer(pm_src) == ("MEDIUM", 5)
        and 'local PV = "m158"' in pm_src and "uf.yPlate" in pm_src and 'f:SetFrameStrata("HIGH")' not in pm_src)

    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
