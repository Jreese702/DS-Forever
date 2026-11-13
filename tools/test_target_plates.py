"""THE TARGET PLATE PASS, off-device (the user, 2026-11-13: "I dont like the friendly Healthcare plates I want to turn those back off even
if it means losing our floating Y glyph"; "I want an animation that transitions an enemy targets name plates to the top of the screen when
targeting them and putting a sort of animated red pointer over the in place of it (the skull and !should be moving to the top as well)";
"I dont like the current Y button visual on the target plates I think that it should go back to just being the Y glyph there maybe with
the magnifying glass in its corner"; "The target bar in general seems kind of blurry"; the pick "Bobbing red arrow").

Trail.lua ag9 / at9 + Hub.lua uf18 in tools/render_top_party.py's rig (both add-ons in one lupa runtime on the compass tests' stub game,
every region's rect resolved from its anchors, SetScale honoured) with render_target_bar.py's stand-in nameplate:
  ag9   a friendly target writes no plate option; a value of ours left from before is given back out of a fight (never in one)
  uf18  a hostile target: its plate hidden (alpha), the head's copy starts ON the plate's bar (scaled) and lands on the bar (the bar at
        alpha 0 till then), the aggro tint held while it flies (at9), the bobbing red arrow on the plate (our texture, WorldFrame's
        child or UIParent here, under the plate's UnitFrame level), it bobs; losing the target flies the copy down and gives the plate
        back only when it lands
  uf18  the prompt: the bare Y glyph (no hudBox), the spyglass on its corner; PadMap's floating glyph gets the corner spyglass too
  uf18  Trail's skull / '!' for the target ride on the bar; Trail hides them on the plate while Hub owns it (ag9)
  uf19  the bar's own top-left snapped: every edge of the bar / its health bar on whole pixels at 720 / 900 / 1080 / 1200 / 1440 lines
  uf18  crisp: u() in whole physical pixels at K = 768 / 1080 (and at a 1440-line screen), the font shadow one pixel, the rim NEAREST
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
    chk("ag9: the version tag", A.V == "ag9" and G.WowCompanion.rings.V == "at9", (A.V, G.WowCompanion.rings.V))
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
    chk("uf19: the version tag", UF.V == "uf19", UF.V)
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
    rim = [A2[i] for i in range(1, len(A2) + 1) if A2[i].kind == "tex" and str(A2[i].tex or "").startswith("atlas:UI-HUD-CoolDownManager-Bar-BG")
           and under_tb(A2[i])]
    chk("uf18: the HUD rim pieces drawn NEAREST (%d pieces)" % len(rim), rim and all(r.filter == "NEAREST" for r in rim))

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

    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
