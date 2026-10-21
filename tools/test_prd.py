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
function Region:GetStatusBarTexture() return self.bartex end
function Region:SetScale(s) self.scale = s end
function Region:HookScript(ev, fn) self.hooks = self.hooks or {}; self.hooks[ev] = fn end
function Region:GetAlpha() return self.alpha or 1 end
-- the game's tooltip, with its lines (a reader reads them whatever the alpha)
GameTooltip = CreateFrame("Frame"); GameTooltip.lines = { "Peacebloom", "Herbalism" }
function GameTooltip:NumLines() return #self.lines end
ItemRefTooltip = CreateFrame("Frame")
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
function Region:SetValue(v, interp) self.value = v; self.interp = interp; READS = READS + 1 end
function Region:GetFrameLevel() return self.level or 1 end
function Region:SetFrameLevel(l) self.level = l end
function Region:SetClipsChildren(c) self.clips = c end
function Region:SetBlendMode(m) self.blend = m end
Enum = Enum or {}
Enum.StatusBarInterpolation = { Immediate = 0, ExponentialEaseOut = 1 }
INC, ABS = nil, nil
function UnitGetIncomingHeals(u) return INC end
function UnitGetTotalAbsorbs(u) return ABS end
C_Spell = { GetSpellPowerCost = function(id) if id == 8936 then return { { type = 0, cost = 25 } } end end }
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
    chk("our own frame on UIParent, MEDIUM, mouse off, anchored below the centre (y %.1f = -300 spec px, hidden 14 lower)" % P.f.point[5],
        L.eval("rawequal(WowCompanion.prd.f.parent, UIParent)") and P.f.strata == "MEDIUM" and P.f.mouse is False
        and P.f.point[1] == "TOP" and P.f.point[3] == "CENTER" and abs(P.f.point[5] + (300 + 14) * 768 / 1080) < 1e-6 and P.y == -300)

    # a fight starts (PLAYER_REGEN_DISABLED before UnitAffectingCombat reads true): in 0.15 s
    fire("PLAYER_REGEN_DISABLED")
    # prd8: the eased fade-and-slide in: up from 14 below, the alpha rising, both on one curve; at rest the offset is 0
    ys, als = [], []
    for _ in range(9):
        run(1 / 30)
        ys.append(P.dy); als.append(P.alpha)
    chk("prd8: entering: slides UP into place while fading in (offset %.1f -> %.1f, alpha %.2f -> %.2f), eased out" % (ys[0], ys[-1], als[0], als[-1]),
        ys[0] < ys[-1] <= 0 and als[0] < als[-1] and (ys[1] - ys[0]) > (ys[-1] - ys[-2]))
    run(0.1)
    chk("starting combat: shown at once (alpha %.2f, %s)" % (P.alpha, P.why), P.alpha == 1 and P.f.shown is True and P.why == "fight")
    chk("health FED: the secret value itself to SetValue, the plain max to SetMinMaxValues",
        Lg.issecretvalue(P.hp.value) and P.hp.max == 1000 and P.hp.min == 0)
    chk("power FED the same way, the PRD's own fill tinted mana's blue (0, 0.45, 1)", Lg.issecretvalue(P.pw.value)
        and P.pw.max == 100 and P.pw.fill.atlas == "UI-HUD-CoolDownManager-Bar-c60" and list(P.pw.color.values()) == [0, 0.45, 1])
    chk("prd6: health the PRD's own fill (rounded at both ends) tinted green, 260 x 15 (spec px; prd8: wider), in the PRD's bar BG (c60), no spark",
        P.hp.fill.atlas == "UI-HUD-CoolDownManager-Bar-c60" and list(P.hp.color.values()) == [0.12, 0.86, 0.12] and P.hp.spark is None and abs(P.hp.w - 260 * 768 / 1080) < 1e-6
        and abs(P.hp.h - 15 * 768 / 1080) < 1e-6 and P.shadow.tex.endswith("compass_band")
        and P.hpFrame.atlas == "UI-HUD-CoolDownManager-Bar-BG-c60" and P.pwFrame.atlas == "UI-HUD-CoolDownManager-Bar-BG-c60"
        and P.hpFrame.whole is not None)
    ME.ptype, ME.tok = 1, "RAGE"
    fire("UNIT_DISPLAYPOWER", "player")
    run(0.3)
    chk("a warrior's rage: red", list(P.pw.color.values()) == [1, 0, 0])
    ME.ptype, ME.tok = 3, "ENERGY"; run(0.3)
    chk("energy: yellow", list(P.pw.color.values()) == [1, 1, 0])
    ME.ptype, ME.tok = 4, "HAPPINESS"; run(0.3)
    chk("another power: its colour (0, 1, 1) on the same fill", list(P.pw.color.values()) == [0, 1, 1])
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
    run(0.5)
    chk("... then fading down and out (alpha %.2f, offset %.1f, 0.4 s into the fade)" % (P.alpha, P.dy), 0.2 < P.alpha < 0.8 and P.why == "fading" and P.dy < 0)
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
    chk("a step up: -290 (%s), saved for Yes-Lander" % P.y, P.y == -290 and Lg.WowCompanionDB.prdY["Yes-Lander"] == -290
        and abs(P.f.point[5] + 290 * 768 / 1080) < 1e-6)
    for _ in range(30):
        P.nudge(1)
    chk("the top of the range: -170 (%s)" % P.y, P.y == -170)
    for _ in range(60):
        P.nudge(-1)
    chk("the bottom of the range: -470 (%s)" % P.y, P.y == -470)
    P.nudge(0)
    chk("back to the default: -300, nothing kept (%s)" % P.y, P.y == -300 and Lg.WowCompanionDB.prdY["Yes-Lander"] is None and y0 == -300)
    P.nudge(-1); P.y = None; P.loadY()
    chk("read back at log in (%s)" % P.y, P.y == -310)
    # prd4: a height saved on the OLD default (prdYv not yet 2) moves down with it, once
    Lg.WowCompanionDB.prdY["Yes-Lander"] = -230; Lg.WowCompanionDB.prdYv = None; P.loadY()
    y1 = P.y; P.loadY()
    chk("prd4: an old saved height (-230) shifted once with the default: %s, then %s" % (y1, P.y), y1 == -280 and P.y == -280)
    chk("Hub's names: y, on, min, max, step, def, nudge", P.min == -470 and P.max == -170 and P.step == 10 and P["def"] == -300
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

    # prd3, THE HIT CHUNK: the lag bar under the health fill, the same SECRET values 0.3 s late, moved by the bar's own interpolation
    ME.hp = 620; run(0.5)
    ME.hp = 400; run(0.15)
    chk("prd3: hit: the health bar at the new value at once, the lag bar still at the old (%s / %s)" % (P.hp.value.n, P.lag.value.n),
        P.hp.value.n == 400 and P.lag.value.n == 620 and Lg.issecretvalue(P.lag.value))
    run(0.45)
    chk("prd3: ... a beat later (0.3 s after the poll that saw it) the lag bar follows, by the StatusBar's own ease (the chunk shrinks away)",
        P.lag.value.n == 400 and P.lag.interp == 1 and P.ex.interp is True)
    chk("prd3: the lag bar UNDER the health fill (frame levels %s < %s), the ground its own" % (P.lag.level, P.hp.level),
        P.lag.level < P.hp.level)
    # INCOMING HEALS and the SHIELD: fed straight, anchored at the fill's edge, clipped to the bar
    Lg.INC = L.eval("setmetatable({ n = 150 }, { secret = true })")
    Lg.ABS = L.eval("setmetatable({ n = 80 }, { secret = true })")
    run(0.15)
    chk("prd3: incoming heals: the secret fed to a bar anchored at the health fill's right edge (%s)" % P.ex.heal,
        P.heal.value.n == 150 and P.ex.heal == "uih" and P.heal.point[1] in ("TOPLEFT", "BOTTOMLEFT")
        and L.eval("rawequal(WowCompanion.prd.heal.point[2], WowCompanion.prd.hp.fill)") and P.clip.clips is True)
    chk("prd3: the shield after the heal's own edge", P.shield.value.n == 80 and P.ex.abs is True
        and L.eval("rawequal(WowCompanion.prd.shield.point[2], WowCompanion.prd.heal.fill)"))
    Lg.INC, Lg.ABS = None, None
    # THE COST: the spell's own cost over the plain max, on the power fill's right edge, gone when the cast ends
    ev.scripts.OnEvent(ev, "UNIT_SPELLCAST_START", "player", "guid", 8936)
    chk("prd3: casting a 25-mana spell of 100: the cost segment a quarter of the bar (%.2f), on the power fill's edge" % (P.cost.w or -1),
        P.cost.shown is True and abs(P.cost.w - 0.25 * 260 * 768 / 1080) < 1e-6 and P.cost.point[1] in ("TOPRIGHT", "BOTTOMRIGHT")
        and L.eval("rawequal(WowCompanion.prd.cost.point[2], WowCompanion.prd.pw.fill)"))
    ev.scripts.OnEvent(ev, "UNIT_SPELLCAST_STOP", "player", "guid", 8936)
    chk("prd3: the cast over: gone", P.cost.shown is False and P.ex.cost is None)
    ev.scripts.OnEvent(ev, "UNIT_SPELLCAST_START", "player", "guid", 1)
    chk("prd3: a spell with no cost: no segment", P.cost.shown is False)
    # a client without the interpolation: a step after the hold, no error
    Lg.Enum.StatusBarInterpolation = None
    P.lag = None; P.f = None; P.build(); run(0.2)
    ME.hp = 300; run(0.8)
    chk("prd3: no interpolation in this client: the lag bar steps (no error)", P.ex.interp is False and P.lag.value.n == 300 and P.lag.interp is None)
    # prd5, THE CHANNEL (the user: "It overlaps the border instead of looking like a 3d polished fill part of the asset"): with the
    # atlas's own coordinates, the frame cut along its art: the track under the bar, the lip (top, bottom, sides) over it
    L.execute("""C_Texture = { GetAtlasInfo = function(n) if n == "UI-HUD-CoolDownManager-Bar-BG-c60" then
        return { file = 4242, leftTexCoord = 0.5, rightTexCoord = 1.0, topTexCoord = 0.25, bottomTexCoord = 0.5 } end end }""")
    Lg.Enum.StatusBarInterpolation = L.eval("{ ExponentialEaseOut = 1 }")
    P.lag = None; P.f = None; P.lip = None; P.build(); run(0.2)
    hf = P.hpFrame
    k = 15 / 8 * 768 / 1080
    def near(a, b): return abs(a - b) < 1e-6
    chk("prd5: the track = the art's rows 3..11, the full width, UNDER the bar (on the layer)",
        hf.track.tex == 4242 and near(hf.track.coord[1], 0.5) and near(hf.track.coord[2], 1.0) and near(hf.track.coord[3], 0.25 + 0.25 * 3 / 19)
        and near(hf.track.coord[4], 0.25 + 0.25 * 11 / 19))
    chk("prd5: the lip OVER every bar: its own frame above the health bar (levels %s > %s)" % (P.lip.level, P.hp.level),
        P.lip.level > P.hp.level and L.eval("rawequal(WowCompanion.prd.hpFrame.top.parent, nil)") is not None)
    chk("prd5: the top lip: the art's rows 0..3, 3 texels tall, sitting ON the opening's top edge",
        near(hf.top.l.coord[3], 0.25) and near(hf.top.l.coord[4], 0.25 + 0.25 * 3 / 19) and near(hf.top.h, 3 * k)
        and hf.top.l.point[1] == "BOTTOMLEFT")
    chk("prd6: the rims in three: the art's end caps (0..11, 118..132) and a MIDDLE from the mark-free span 31..50 (no tick repeats)",
        near(hf.top.l.coord[2], 0.5 + 0.5 * 11 / 132) and near(hf.top.r.coord[1], 0.5 + 0.5 * 118 / 132)
        and near(hf.top.m.coord[1], 0.5 + 0.5 * 31 / 132) and near(hf.top.m.coord[2], 0.5 + 0.5 * 50 / 132)
        and near(hf.top.l.w, 11 * k) and near(hf.top.r.w, 14 * k) and near(hf.bot.m.coord[1], 0.5 + 0.5 * 31 / 132))
    chk("prd5: the bottom lip rows 11..19, 8 texels; the sides: 3 texels left, 7 right, beside the opening only",
        near(hf.bot.l.coord[3], 0.25 + 0.25 * 11 / 19) and near(hf.bot.h, 8 * k) and near(hf.left.w, 3 * k) and near(hf.right.w, 7 * k)
        and near(hf.left.coord[2], 0.5 + 0.5 * 3 / 132) and near(hf.right.coord[1], 0.5 + 0.5 * 125 / 132))
    chk("prd5: one texel scale for both bars (the power bar's lip the same thickness)", near(P.pwFrame.top.h, 3 * k) and near(P.pwFrame.bot.h, 8 * k))
    chk("prd5: the inner shadow over the opening's top", hf.shade is not None)
    # prd7 (test49, "The hud is gray for hp and invisible for mana"): three points
    def rebuild():
        P.lag = None; P.f = None; P.lip = None; P.build(); run(0.3)
    def vc(tex):
        return [round(v, 3) for v in list(tex.col.values())[:3]] if tex.col else None
    # (1) an atlas the client LACKS: GetAtlasInfo knows nothing, SetAtlas refuses quietly (false, no error) -> the plain bar, tinted
    L.execute("""C_Texture = { GetAtlasInfo = function(n) return nil end }
        local R = getmetatable(CreateFrame("Frame"))
        R.SetAtlasOld = R.SetAtlas
        R.SetAtlas = function(self, a) if a:find("CoolDownManager%-Bar") and not a:find("BG") then return false end self.atlas = a end""")
    ME.ptype, ME.tok = 0, "MANA"
    rebuild()
    chk("prd7 (1): the fill's atlas missing on this client: the plain UI-StatusBar, not a blank texture (%s / %s)"
        % (P.hp.fill.tex, P.ex.pwFill), P.hp.fill.tex == "Interface\\TargetingFrame\\UI-StatusBar" and P.hp.fillAtlas is None
        and P.pw.fill.tex == "Interface\\TargetingFrame\\UI-StatusBar" and P.ex.hpFill == "plain" and P.ex.pwFill == "plain")
    chk("prd7 (2): the health colour ON the bar's own texture (%s), not only the bar's" % vc(P.hp.fill),
        vc(P.hp.fill) == [0.12, 0.86, 0.12] and L.eval("rawequal(WowCompanion.prd.hp.fill, WowCompanion.prd.hp.bartex)"))
    chk("prd7 (3): a mana user from the first poll (type 0, no change of type): the power bar tinted (%s)" % vc(P.pw.fill),
        vc(P.pw.fill) == [0.0, 0.45, 1.0])
    # the atlas present: used, set BEFORE the bar took the texture, tinted the same
    L.execute("""local R = getmetatable(CreateFrame("Frame")); R.SetAtlas = R.SetAtlasOld
        C_Texture = { GetAtlasInfo = function(n) return { file = 1, leftTexCoord = 0, rightTexCoord = 1, topTexCoord = 0, bottomTexCoord = 1 } end }""")
    rebuild()
    chk("prd7: the PRD fill present: used (%s), tinted on its texture (%s)" % (P.ex.hpFill, vc(P.hp.fill)),
        P.ex.hpFill == "UI-HUD-CoolDownManager-Bar-c60" and vc(P.hp.fill) == [0.12, 0.86, 0.12] and vc(P.pw.fill) == [0.0, 0.45, 1.0])
    # something resets the texture's colour: the next poll puts it back
    L.execute("WowCompanion.prd.hp.fill:SetVertexColor(1, 1, 1)")
    run(0.2)
    chk("prd7: a grey reset of the texture is undone at the next poll (%s)" % vc(P.hp.fill), vc(P.hp.fill) == [0.12, 0.86, 0.12])
    # prd8: the animation: at rest the offset is 0; a hide turned into a show reverses where it is; the saved height untouched
    Lg.WowCompanion.vitals = None
    fire("PLAYER_REGEN_DISABLED"); ME.fight = True; run(1.0)
    y0, db0 = P.y, (Lg.WowCompanionDB.prdY and Lg.WowCompanionDB.prdY["Yes-Lander"])
    chk("prd8: shown and at rest: the slide offset is 0 (%s), the bar at the player's height" % P.dy,
        P.dy == 0 and abs(P.f.point[5] - P.y * 768 / 1080) < 1e-6)
    ME.fight = False; fire("PLAYER_REGEN_ENABLED"); run(2.0)            # (the hold)
    trail = []
    for _ in range(6):
        run(1 / 30); trail.append(P.dy)                                   # going: sliding down
    fire("PLAYER_REGEN_DISABLED"); ME.fight = True
    for _ in range(12):
        run(1 / 30); trail.append(P.dy)                                   # turned round mid-way: coming back up
    steps = [abs(trail[i] - trail[i - 1]) for i in range(1, len(trail))]
    chk("prd8: a hide turned into a show turns round where it is (largest step %.2f spec px a frame, the lowest %.1f)" % (max(steps), min(trail)),
        max(steps) < 2.5 and min(trail) > -14 and trail[5] < 0 and trail[-1] > trail[6])
    chk("prd8: the slide never touches the saved height (%s, %s)" % (P.y, db0),
        P.y == y0 and ((Lg.WowCompanionDB.prdY and Lg.WowCompanionDB.prdY["Yes-Lander"]) == db0))
    # prd8, THE POWER TOO (the user: "it should Fade when Mana is full as well"): out of combat the bar stays until health AND power rest
    Lg.HPM = L.table_from({"hp": 1000, "max": 1000, "mp": 400, "mpMax": 1000})
    Lg.WowCompanion.vitals = L.eval("function() return { hp = HPM.hp, hpMax = HPM.max, hpSrc = 'model', mp = HPM.mp, mpMax = HPM.mpMax, mpSrc = 'model' } end")
    ME.ptype, ME.tok = 0, "MANA"; run(0.3)
    ME.fight = False; fire("PLAYER_REGEN_ENABLED"); run(4)
    chk("prd8: full health, mana at 40 %%, out of combat: still shown (%s, %s)" % (P.why, P.prest), P.alpha > 0.9 and P.why == "power" and P.prest == "mana")
    Lg.HPM.mp = 1000; run(3.5)
    chk("prd8: the mana full too: faded away (%s)" % P.why, P.alpha == 0)
    Lg.HPM.mp = 900; run(0.4)
    chk("prd8: the mana drops (a cast): back at once", P.alpha > 0.9 and P.why == "power")
    Lg.HPM.mp = 1000; run(3.5)
    # energy: no reader - full ENERGY_S after the fight or the last spend; rage: at rest RAGE_S after the fight
    ME.ptype, ME.tok = 3, "ENERGY"; fire("UNIT_DISPLAYPOWER", "player")
    fire("PLAYER_REGEN_DISABLED"); ME.fight = True; run(0.5); ME.fight = False; fire("PLAYER_REGEN_ENABLED"); run(4)
    chk("prd8: a rogue 4 s after the fight: energy refilling, still shown (%s)" % P.prest, P.alpha > 0.9 and P.prest == "refill")
    ev.scripts.OnEvent(ev, "UNIT_SPELLCAST_SUCCEEDED", "player", "g", 1752); run(8)
    chk("prd8: a spend restarts the refill (8 s after it: still shown)", P.alpha > 0.9)
    run(5)
    chk("prd8: refilled: faded", P.alpha == 0)
    ME.ptype, ME.tok = 1, "RAGE"; fire("UNIT_DISPLAYPOWER", "player")
    fire("PLAYER_REGEN_DISABLED"); ME.fight = True; run(0.5); ME.fight = False; fire("PLAYER_REGEN_ENABLED"); run(10)
    chk("prd8: a warrior 10 s after the fight: rage decaying, still shown (%s)" % P.prest, P.alpha > 0.9 and P.prest == "decay")
    run(24)
    chk("prd8: rage at rest after 30 s: faded", P.alpha == 0)
    ME.ptype, ME.tok = 0, "MANA"; fire("UNIT_DISPLAYPOWER", "player")

    # prd8, WIDTH AND SCALE (the user: "can we add some scaling settings"): Hub's column 39 selects, its step columns move it
    fire("PLAYER_REGEN_DISABLED"); ME.fight = True; run(0.5)
    chk("prd8: wider by default: 260 spec px, scale 100 %, the step columns on the height", P.wd == 260 and P.sc == 100 and P.sel == "y")
    P.select(); P.nudge(1)
    chk("prd8: select -> width; a step up: 280 (%s), the bars that wide at once (the live preview), kept for Yes-Lander" % P.wd,
        P.wd == 280 and abs(P.hp.w - 280 * 768 / 1080) < 1e-6 and abs(P.f.w - 280 * 768 / 1080) < 1e-6 and Lg.WowCompanionDB.prdW["Yes-Lander"] == 280)
    P.select(); P.nudge(1); P.nudge(1)
    chk("prd8: select -> scale; two steps: 120 %% (%s), the whole bar scaled" % P.sc, P.sc == 120 and abs(P.f.scale - 1.2) < 1e-9
        and Lg.WowCompanionDB.prdS["Yes-Lander"] == 120)
    P.nudge(0)
    chk("prd8: default on the scale: 100, nothing kept", P.sc == 100 and Lg.WowCompanionDB.prdS["Yes-Lander"] is None)
    y0 = P.y; run(5); P.nudge(1)
    chk("prd8: 4 s with no tap: the step columns back on the height (an app that only knows the height still works): %s -> %s" % (y0, P.y),
        P.sel == "y" and P.y == y0 + 10 and P.wd == 280)
    P.nudge(-1)
    P.wd, P.sc = None, None; P.loadSize()
    chk("prd8: read back at log in: 280 wide, 100 %%", P.wd == 280 and P.sc == 100)
    P.select(); P.nudge(0); P.sel = "y"
    # tt1, HIDE THE GAME'S TOOLTIPS (the user: "I need a setting to hide top screen tooltips"): alpha only, held by hooks
    TT = Lg.WowCompanion.tips
    tip = Lg.GameTooltip
    TT.apply()
    chk("tt1: off by default: the tooltips as the game has them (alpha %s)" % tip.alpha, TT.on is False and tip.alpha == 1)
    TT.set(True)
    chk("tt1: on: alpha 0, nothing hidden or cleared, kept in our saved data", tip.alpha == 0 and tip.shown is True
        and Lg.WowCompanionDB.hideTips is True and Lg.ItemRefTooltip.alpha == 0)
    L.execute("GameTooltip:SetAlpha(1)")                                 # the game shows / fades it again
    tip.hooks.OnShow(tip)
    chk("tt1: the game shows it again: our OnShow hook puts it to 0", tip.alpha == 0)
    L.execute("GameTooltip:SetAlpha(0.6)"); tip.hooks.OnUpdate(tip)
    chk("tt1: the game's own fade: held at 0 by the OnUpdate hook", tip.alpha == 0)
    chk("tt1: a reader still gets the tooltip's lines (%d)" % tip.NumLines(tip), tip.NumLines(tip) == 2 and tip.lines[1] == "Peacebloom")
    TT.set(False)
    chk("tt1: off again: alpha 1 back, the saved choice gone", tip.alpha == 1 and Lg.ItemRefTooltip.alpha == 1 and Lg.WowCompanionDB.hideTips is None)
    L.execute("GameTooltip:SetAlpha(1)"); tip.hooks.OnShow(tip)
    chk("tt1: off: the hooks leave it alone", tip.alpha == 1)
    chk("nothing of the game's written (no CVar)", Lg.CVAR_WRITES == 0)
    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
