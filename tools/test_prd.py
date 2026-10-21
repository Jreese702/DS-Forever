"""The HUD's personal resource bar (WowCompanion/Trail.lua, WC.prd), off-device: Trail.lua under the compass test's stub game, plus
health / power that are SECRET (as on Forever), the player's auras, combat, and HUD mode's wish (WC.npWant.hud).

    python tools/test_prd.py

Checks: nothing without HUD mode; the secret values FED to the bars, never read; the power type's colour; the show rules (a fight,
starting one, not full by Controller's model; out of combat and full: held, then faded; no signal: fades after the fight); the aura
row (in combat only; buffs of 5 min or less up to 6, debuffs with a duration up to 4, the swipe, the seconds gold under 5, a second
row when they do not fit); the height (the app's steps, per character in our saved data, its range); our own frames, nothing written.
Needs lupa. Exit code 1 on any failure."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_compass as tc  # noqa: E402

GAMEPLUS = r'''
SECRET = setmetatable({}, { __tostring = function() return "SECRET" end })
function issecretvalue(v) return type(v) == "table" and getmetatable(v) and getmetatable(v).secret == true end
local function secret(n) return setmetatable({ n = n }, { secret = true }) end
ME = { hp = 620, hpMax = 1000, pw = 45, pwMax = 100, ptype = 0, tok = "MANA", fight = false, dead = false, buffs = {}, debuffs = {} }
READS = 0                                         -- arithmetic or comparison on a secret would error; this counts the feeds
function UnitHealth(u) return secret(ME.hp) end
function UnitHealthMax(u) return ME.hpMax end
function UnitPower(u, t) return secret(ME.pw) end
function UnitPowerMax(u, t) return ME.pwMax end
function UnitPowerType(u) return ME.ptype, ME.tok end
function UnitAffectingCombat(u) return ME.fight end
function UnitIsDeadOrGhost(u) return ME.dead end
function UnitName(u) return "Yes" end
function GetRealmName() return "Lander" end
PowerBarColor = { MANA = { r = 0, g = 0, b = 1 }, RAGE = { r = 1, g = 0, b = 0 }, ENERGY = { r = 1, g = 1, b = 0 } }
DebuffTypeColor = { none = { r = 0.8, g = 0, b = 0 }, Magic = { r = 0.2, g = 0.6, b = 1 } }
C_UnitAuras = { GetAuraDataByIndex = function(u, i, f)
    local l = (f == "HELPFUL") and ME.buffs or ME.debuffs
    return l[i]
end }
-- the widgets the bar uses, recorded (the stub's own Region: a frame's metatable)
local Region = getmetatable(CreateFrame("Frame"))
function Region:SetStatusBarTexture(t) self.bartex = t end
function Region:SetMinMaxValues(a, b) self.min, self.max = a, b end
function Region:SetValue(v) self.value = v; READS = READS + 1 end
function Region:SetStatusBarColor(r, g, b) self.color = { r, g, b } end
function Region:SetTexCoord(...) self.coord = { ... } end
function Region:SetColorTexture(...) self.colortex = { ... } end
function Region:SetHeight(h) self.h = h end
function Region:SetWidth(w) self.w = w end
function Region:SetCooldown(s, d) self.cdStart, self.cdDur = s, d end
function Region:SetReverse(r) self.reverse = r end
function Region:SetDrawEdge(e) self.edge = e end
function Region:SetHideCountdownNumbers(h) self.nonum = h end
function Region:RegisterUnitEvent(e, u) self.events = self.events or {}; self.events[e] = u end
function Region:GetAlpha() return self.alpha or 1 end
function Region:SetTextColor(r, g, b, a) self.tcolor = { r, g, b } end
function Region:CreateMaskTexture() return CreateFrame("Texture") end
function Region:AddMaskTexture(m) self.masked = m end
function Region:Clear() self.cdStart = nil end
function Region:SetCooldownFromExpirationTime(e, d) self.cdExp, self.cdDur = e, d end
'''


def main():
    L = tc.lupa.LuaRuntime()
    L.execute(tc.STUB)
    L.execute(GAMEPLUS)
    L.execute(open(tc.TRAIL, encoding="utf-8").read().replace("local ADDON = ...", "local ADDON = 'WowCompanion'", 1))
    fails = []

    def chk(name, ok):
        print(("PASS " if ok else "FAIL ") + name)
        if not ok:
            fails.append(name)

    G, Lg = L.globals().GAME, L.globals()
    P = Lg.WowCompanion.prd
    ME = Lg.ME
    frames = [Lg.FRAMES[i] for i in range(1, len(Lg.FRAMES) + 1)]
    ev = [f for f in frames if f.events and f.events.UNIT_AURA][0]

    def run(secs, dt=1 / 30):
        for _ in range(int(round(secs / dt))):
            G.t = G.t + dt
            ev.scripts.OnUpdate(ev, dt)

    def fire(e, a=None):
        ev.scripts.OnEvent(ev, e, a)

    def aura(icon, dur, left, kind=None, n=None):
        d = {"icon": icon, "duration": dur, "expirationTime": G.t + left}
        if kind:
            d["dispelName"] = kind
        if n:
            d["applications"] = n
        return L.table_from(d)

    def lst(*a):
        return L.table_from(list(a))

    G.t = 1000.0
    fire("PLAYER_ENTERING_WORLD")
    run(1)
    chk("HUD mode off: nothing built (%s)" % P.why, P.f is None and P.why == "hud off")
    Lg.WowCompanion.npWant = L.table_from({"hud": True})
    run(1)
    chk("HUD mode on, out of combat, no health signal, no fight yet: nothing shown (%s)" % P.why, P.f is not None and P.shown is False)
    chk("our own frame on UIParent, MEDIUM, mouse off, anchored below the centre (y %.1f = -250 spec px)" % P.f.point[5],
        L.eval("rawequal(WowCompanion.prd.f.parent, UIParent)") and P.f.strata == "MEDIUM" and P.f.mouse is False
        and P.f.point[1] == "TOP" and P.f.point[3] == "CENTER" and abs(P.f.point[5] + 250 * 768 / 1080) < 1e-6)

    # a fight starts (PLAYER_REGEN_DISABLED before UnitAffectingCombat reads true): in 0.15 s
    fire("PLAYER_REGEN_DISABLED")
    run(0.2)
    chk("starting combat: shown at once (alpha %.2f, %s)" % (P.alpha, P.why), P.alpha == 1 and P.f.shown is True and P.why == "fight")
    chk("health FED: the secret value itself to SetValue, the plain max to SetMinMaxValues",
        Lg.issecretvalue(P.hp.value) and P.hp.max == 1000 and P.hp.min == 0)
    chk("power FED the same way, the PlayerFrame's own mana fill (its colour in the art: no tint)", Lg.issecretvalue(P.pw.value)
        and P.pw.max == 100 and P.pw.fill.atlas == "UI-HUD-UnitFrame-Player-PortraitOn-Bar-Mana" and list(P.pw.color.values()) == [1, 1, 1])
    chk("prd2: health the PlayerFrame's own fill, 200 x 11 (spec px), a crisp border, the empty part see-through",
        P.hp.fill.atlas == "UI-HUD-UnitFrame-Player-PortraitOn-Bar-Health" and abs(P.hp.w - 200 * 768 / 1080) < 1e-6
        and abs(P.hp.h - 11 * 768 / 1080) < 1e-6 and P.shadow.tex.endswith("compass_band"))
    ME.ptype, ME.tok = 1, "RAGE"
    fire("UNIT_DISPLAYPOWER", "player")
    run(0.3)
    chk("a warrior's rage: its own fill", P.pw.fill.atlas == "UI-HUD-UnitFrame-Player-PortraitOn-Bar-Rage")
    ME.ptype, ME.tok = 3, "ENERGY"; run(0.3)
    chk("energy: its own fill", P.pw.fill.atlas == "UI-HUD-UnitFrame-Player-PortraitOn-Bar-Energy")
    ME.ptype, ME.tok = 4, "HAPPINESS"; run(0.3)
    chk("a power with no fill of its own: the plain bar in the power's colour (0, 1, 1)", list(P.pw.color.values()) == [0, 1, 1]
        and P.pw.fill.tex == "Interface\\TargetingFrame\\UI-StatusBar")
    ME.ptype, ME.tok = 0, "MANA"; run(0.3)

    # the auras, in combat
    ME.fight = True
    ME.buffs = lst(aura(135953, 15, 9), aura(136081, 12, 3.4), aura(132333, 120, 102), aura(135987, 1800, 1500),
                   aura(136000, 0, 0))
    ME.debuffs = lst(aura(136134, 18, 6, "none", 2), aura(136000, 0, 0, "Magic"))
    fire("UNIT_AURA", "player")
    run(0.1)
    b1, b2, b3, b4 = P.buffs[1], P.buffs[2], P.buffs[3], P.buffs[4]
    chk("buffs of 5 min or less: Renew, Rejuvenation, Battle Shout (not the 30-min buff, not the one with no duration): %d" % P.nb,
        P.nb == 3 and b1.f.shown and b2.f.shown and b3.f.shown and not b4.f.shown and b1.icon == 135953 and b3.icon == 132333)
    chk("the swipe: the game's Cooldown, reversed (the time gone darkens), from the aura's start for its duration",
        b1.cd.reverse is True and abs(b1.cd.cdStart - (G.t - 0.1 - 6)) < 0.05 and b1.cd.cdDur == 15 and b1.cd.template == "CooldownFrameTemplate")
    chk("the BuffFrame's timers: '%s', '%s', '%s' (gold, white under a minute)" % (b1.t.text, b2.t.text, b3.t.text),
        b1.t.text == "9 s" and b2.t.text == "4 s" and b3.t.text == "2 m" and list(b1.t.tcolor.values()) == [1, 1, 1]
        and list(b3.t.tcolor.values()) == [1, 0.82, 0])
    chk("the debuff with a duration only, from the right edge, in its type's colour, its stack count (%s)" % P.debuffs[1].n.text,
        P.nd == 1 and P.debuffs[1].f.shown and P.debuffs[1].bd.shown is True and P.debuffs[1].f.point[1] == "TOPRIGHT"
        and P.debuffs[1].n.text == "2" and not P.debuffs[2].f.shown)
    chk("a buff wears no debuff border", b1.bd.shown is False)
    run(1.0)
    chk("the seconds run down (Renew %s)" % b1.t.text, b1.t.text == "8 s")
    # crowded: 6 buffs + 4 debuffs do not fit across 236: the debuffs take a second row
    ME.buffs = lst(*[aura(1000 + i, 30, 20) for i in range(8)])
    ME.debuffs = lst(*[aura(2000 + i, 30, 20, "none") for i in range(5)])
    fire("UNIT_AURA", "player")
    run(0.1)
    chk("at most 6 buffs and 4 debuffs (%d, %d)" % (P.nb, P.nd), P.nb == 6 and P.nd == 4)
    chk("too many for one row: the debuffs on a second row under the buffs",
        P.debuffs[1].f.point[5] < P.buffs[1].f.point[5])

    # the fight ends at full health (the model says full): held 2 s, then faded over 1.5 s
    Lg.WowCompanion.vitals = L.eval("function() return { hp = HPM.hp, hpMax = HPM.max, hpSrc = 'model' } end")
    Lg.HPM = L.table_from({"hp": 1000, "max": 1000})
    ME.fight = False
    fire("PLAYER_REGEN_ENABLED")
    run(0.2)
    chk("(a) out of combat the row STAYS while the bar shows (test45: a HoT after the fight)", P.nb == 6 and P.buffs[1].f.shown)
    run(1.6)
    chk("full and out of combat: held 2 s (alpha %.2f at 1.8 s)" % P.alpha, P.alpha == 1)
    run(1.0)
    chk("... then fading (alpha %.2f at 2.8 s)" % P.alpha, 0.2 < P.alpha < 0.8 and P.why == "fading")
    run(1.0)
    chk("... gone by 3.5 s (%s)" % P.why, P.alpha == 0 and P.f.shown is False and P.why == "full")
    # hurt out of combat (the model below 99 %): shown, no aura row; full again: fades
    Lg.HPM.hp = 700
    run(0.3)
    chk("not at full health (the model, %s): shown out of combat, its auras with it" % P.src, P.alpha == 1 and P.why == "hurt" and P.src == "model" and P.nb == 6)
    chk("(b) the probe: %s HELPFUL seen" % (P.ab and P.ab.seen), P.ab is not None and P.ab.seen == 8 and P.ab.sec == 0 and P.ab.long == 0)
    Lg.HPM.hp = 995
    run(4)
    chk("99.5 % counts as full: faded again", P.alpha == 0)
    # no signal at all: after a fight it fades on its own (never stuck on)
    Lg.WowCompanion.vitals = None
    fire("PLAYER_REGEN_DISABLED"); ME.fight = True; run(0.5)
    ME.fight = False; fire("PLAYER_REGEN_ENABLED"); run(4)
    chk("no health signal: fades after the fight anyway (%s)" % P.src, P.alpha == 0 and P.src == "none")

    # dead: hidden; HUD mode off: hidden
    fire("PLAYER_REGEN_DISABLED"); ME.fight = True; run(0.3)
    ME.dead = True; run(0.1)
    chk("dead: hidden (%s)" % P.why, P.f.shown is False and P.why == "dead")
    ME.dead = False; run(0.3)
    Lg.WowCompanion.npWant.hud = None; run(0.1)
    chk("HUD mode off: hidden (%s)" % P.why, P.f.shown is False and P.why == "hud off")
    Lg.WowCompanion.npWant.hud = True; run(0.3)

    # the height: the app's steps (10 spec px), its range, the default, per character in our saved data
    y0 = P.y
    P.nudge(1)
    chk("a step up: -240 (%s), saved for Yes-Lander" % P.y, P.y == -240 and Lg.WowCompanionDB.prdY["Yes-Lander"] == -240
        and abs(P.f.point[5] + 240 * 768 / 1080) < 1e-6)
    for _ in range(30):
        P.nudge(1)
    chk("the top of the range: -120 (%s)" % P.y, P.y == -120)
    for _ in range(60):
        P.nudge(-1)
    chk("the bottom of the range: -420 (%s)" % P.y, P.y == -420)
    P.nudge(0)
    chk("back to the default: -250, nothing kept (%s)" % P.y, P.y == -250 and Lg.WowCompanionDB.prdY["Yes-Lander"] is None and y0 == -250)
    P.nudge(-1); P.y = None; P.loadY()
    chk("read back at log in (%s)" % P.y, P.y == -260)
    chk("Hub's names: y, on, min, max, step, def, nudge", P.min == -420 and P.max == -120 and P.step == 10 and P["def"] == -250
        and P.on is True and callable(P.nudge))
    chk("this client has no curve route in the stub: tried once, off, the read stands (%s)" % P.curveBad, P.curveBad is not None)

    # THE CURVE (exact): the layer's alpha = UnitHealthPercent through a step curve, the secret handed to SetAlpha
    L.execute("""
        CURVES = {}
        Enum = Enum or {}; Enum.LuaCurveType = { Step = 1 }
        C_CurveUtil = { CreateCurve = function()
            local c = { pts = {} }
            function c:SetType(t) self.kind = t end
            function c:AddPoint(x, y) self.pts[#self.pts + 1] = { x, y } end
            CURVES[#CURVES + 1] = c
            return c
        end }
        function UnitHealthPercent(u, pred, curve)
            local x, y = ME.hp / ME.hpMax, nil
            for _, p in ipairs(curve.pts) do if x >= p[1] then y = p[2] end end
            return setmetatable({ n = y }, { secret = true })
        end
    """)
    Lg.WowCompanion.vitals = L.eval("function() return { hp = HPM.hp, hpMax = HPM.max, hpSrc = 'model' } end")
    Lg.HPM.hp = 1000
    P.curveBad = None
    ME.fight = False; fire("PLAYER_REGEN_ENABLED"); run(3)
    c = Lg.CURVES[1]
    chk("out of combat: the curve route takes (%s): the bars' layer gets the SECRET alpha, our frame stays up" % P.src,
        P.src == "curve" and P.why == "curve" and Lg.issecretvalue(P.g.alpha) and P.f.shown is True and P.alpha == 1)
    chk("the curve: a step, 1 below 99 %%, 0 from it (%d points)" % len(c.pts), c.kind == 1 and c.pts[1][2] == 1 and c.pts[3][1] == 0.99
        and c.pts[3][2] == 0)
    ME.hp = 600; run(0.2)
    chk("hurt: the secret the engine gets is 1 (the stub's evaluation)", P.g.alpha.n == 1)
    ME.hp = 1000; run(0.2)
    chk("full: 0", P.g.alpha.n == 0)
    fire("PLAYER_REGEN_DISABLED"); ME.fight = True; run(0.2)
    chk("in a fight the layer is plainly 1 (the bars always shown)", P.g.alpha == 1 and P.why == "fight")
    ME.fight = False; fire("PLAYER_REGEN_ENABLED"); run(1)
    chk("the fight's hold first (the layer 1, %s)" % P.why, P.g.alpha == 1)
    run(1.5)
    chk("then the curve again", Lg.issecretvalue(P.g.alpha))
    # a client where SetAlpha refuses a secret: off for the session, the read stands
    L.execute("""local g = WowCompanion.prd.g
        g.SetAlpha = function(self, a) if issecretvalue(a) then error("secret refused") end self.alpha = a end""")
    P.curveBad = None
    Lg.HPM.hp = 700
    run(0.3)
    chk("SetAlpha refuses the secret: the curve is off (%s), the model's read shows it (%s, %s)" % (P.curveBad, P.src, P.why),
        P.curveBad is not None and "secret refused" in P.curveBad and P.src == "model" and P.why == "hurt" and P.g.alpha == 1)
    # (c) an aura whose times are secret: still shown, its swipe from the secrets, no seconds; the probe counts it
    fire("PLAYER_REGEN_DISABLED"); ME.fight = True
    ME.buffs = lst(aura(135953, 15, 9), L.table_from({"icon": 136081, "duration": L.eval("setmetatable({ n = 12 }, { secret = true })"),
                                                     "expirationTime": L.eval("setmetatable({ n = 2000 }, { secret = true })")}),
                   aura(135987, 1800, 1500), L.table_from({"icon": 1}))
    fire("UNIT_AURA", "player"); run(0.2)
    s2 = P.buffs[2]
    chk("(c) a secret duration: shown after the plain ones, no seconds, the swipe handed the secrets (%s)" % P.ab.sec,
        P.nb == 2 and s2.f.shown and s2.t.text == "" and Lg.issecretvalue(s2.cd.cdExp) and P.ab.sec == 1 and P.ab.long == 1 and P.ab.nodur == 1)
    chk("prd2: the group at its rest alpha (%.2f)" % P.f.alpha, abs(P.f.alpha - 0.92) < 1e-6)
    chk("nothing of the game's written (no CVar)", Lg.CVAR_WRITES == 0)
    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
