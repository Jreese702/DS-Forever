"""The HUD's personal resource bar (WowCompanion/Trail.lua, WC.prd), off-device: Trail.lua under the compass test's stub game, plus
health / power that are SECRET (as on Forever), the player's auras, combat, and HUD mode's wish (WC.npWant.hud).

    python tools/test_prd.py

Checks: nothing without HUD mode; the secret values FED to the bars, never read; the power type's colour; the show rules (a fight,
starting one, not full by Controller's model; out of combat and full: held, then faded; no signal: fades after the fight); the aura
row (in combat only; buffs of 5 min or less up to 6, debuffs with a duration up to 4, the swipe, the seconds gold under 5, a second
row when they do not fit); the height (the app's steps, per character in our saved data, its range); our own frames, nothing written;
prd11: the pet's strip (its own layer beside the player's, gold health, the pet's power colour, the PRD frame at the player's texel scale,
the aura row moved by it, plain presence / happiness / death only, its own curve flag, the pet's casts never the player's);
prd12: the user's device look - the player's green, ONE frame round both pet bars (1 px apart), a 36 px round portrait centred on them,
a 20 px happiness face off the portrait, no name, the strip lower and centred under the player's bars;
prd13: the shade over the pet's health in the player's proportion (45 % of the health bar, not of the unit), the portrait clear of
the player's power-bar lip, the happiness face inside the HUD frame at the minimum width;
prd14: Party mode's pick on you / your pet (PadMap m131's box.u) - a gold box round the player's bars / the pet's strip, the HUD kept
shown while boxed, nothing while Party mode is off or the pick is a party member.
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
PET = nil                                         -- prd11: the pet ({ hp, hpMax, pw, pwMax, ptype, tok, name, icon, dead, happy }), nil = none
local function W(u) if u == "pet" then return PET or {} end return ME end
function UnitHealth(u) return secret(W(u).hp) end
function UnitHealthMax(u) return W(u).hpMax end
function UnitPower(u, t) return secret(W(u).pw) end
function UnitPowerMax(u, t) return W(u).pwMax end
function UnitPowerType(u) return W(u).ptype, W(u).tok end
function UnitAffectingCombat(u) return ME.fight end
function UnitIsDeadOrGhost(u) return ME.dead end
function UnitName(u) if u == "pet" then return PET and PET.name or nil end return "Yes" end
PET_EXISTS_SECRET = false
function UnitExists(u) if u == "pet" then if PET_EXISTS_SECRET then return secret(1) end return PET ~= nil end return true end
function HasPetUI() return PET ~= nil, PET and PET.hunter or false end
function UnitIsDead(u) if u == "pet" then return PET ~= nil and PET.dead == true end return ME.dead end
function GetPetIcon() return PET and PET.icon or nil end
C_PetInfo = { GetPetHappiness = function()
    if not PET or not PET.happy then return nil end
    if PET.happySecret then return secret(PET.happy) end
    return PET.happy
end }
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
function Region:RegisterUnitEvent(e, u, u2) self.events = self.events or {}; self.events[e] = u; self.units = self.units or {}; self.units[e] = { u, u2 } end
function Region:GetAlpha() return self.alpha or 1 end
function Region:SetTextColor(r, g, b, a) self.tcolor = { r, g, b } end
function Region:SetValue(v, interp) self.value = v; self.interp = interp; READS = READS + 1 end
function Region:GetFrameLevel() return self.level or 1 end
function Region:SetFrameLevel(l) self.level = l end
function Region:SetClipsChildren(c) self.clips = c end
function Region:SetBlendMode(m) self.blend = m end
-- prd14 review: every point a region holds (the stub keeps the last only), for the pick boxes' geometry
do
    local sp, cl = Region.SetPoint, Region.ClearAllPoints
    function Region:SetPoint(...) sp(self, ...); self.anch = self.anch or {}; self.anch[#self.anch + 1] = { ... } end
    function Region:ClearAllPoints() cl(self); self.anch = nil end
end
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
    chk("our own frame on UIParent, MEDIUM, mouse off, anchored below the centre (y %.1f = -350 spec px, hidden 14 lower)" % P.f.point[5],
        L.eval("rawequal(WowCompanion.prd.f.parent, UIParent)") and P.f.strata == "MEDIUM" and P.f.mouse is False
        and P.f.point[1] == "TOP" and P.f.point[3] == "CENTER" and abs(P.f.point[5] + (350 + 14) * 768 / 1080) < 1e-6 and P.y == -350)

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
    chk("prd6: health the PRD's own fill (rounded at both ends) tinted green, 280 x 15 (spec px; prd8: wider), in the PRD's bar BG (c60), no spark",
        P.hp.fill.atlas == "UI-HUD-CoolDownManager-Bar-c60" and list(P.hp.color.values()) == [0.12, 0.86, 0.12] and P.hp.spark is None and abs(P.hp.w - 280 * 768 / 1080) < 1e-6
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
    chk("a step up: -340 (%s), saved for Yes-Lander" % P.y, P.y == -340 and Lg.WowCompanionDB.prdY["Yes-Lander"] == -340
        and abs(P.f.point[5] + 340 * 768 / 1080) < 1e-6)
    for _ in range(30):
        P.nudge(1)
    chk("the top of the range: -170 (%s)" % P.y, P.y == -170)
    for _ in range(60):
        P.nudge(-1)
    chk("the bottom of the range: -470 (%s)" % P.y, P.y == -470)
    P.nudge(0)
    chk("back to the default: -350, nothing kept (%s)" % P.y, P.y == -350 and Lg.WowCompanionDB.prdY["Yes-Lander"] is None and y0 == -350)
    P.nudge(-1); P.y = None; P.loadY()
    chk("read back at log in (%s)" % P.y, P.y == -360)
    # prd4: a height saved on the OLD default (prdYv not yet 2) moves down with it, once
    Lg.WowCompanionDB.prdY["Yes-Lander"] = -230; Lg.WowCompanionDB.prdYv = None; P.loadY()
    y1 = P.y; P.loadY()
    chk("prd4: an old saved height (-230) shifted once with the default: %s, then %s" % (y1, P.y), y1 == -280 and P.y == -280)
    chk("Hub's names: y, on, min, max, step, def, nudge", P.min == -470 and P.max == -170 and P.step == 10 and P["def"] == -350
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
            if u == "pet" and PET_REFUSE then error("pet refused") end
            if u == "pet" and PET == nil then return nil end         -- (review of prd11: no pet, no value - the code's own "no value")
            local w = (u == "pet") and PET or ME
            local x, y = w.hp / w.hpMax, nil
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
        P.cost.shown is True and abs(P.cost.w - 0.25 * 280 * 768 / 1080) < 1e-6 and P.cost.point[1] in ("TOPRIGHT", "BOTTOMRIGHT")
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
    chk("prd8: wider by default: 280 spec px (prd9), scale 100 %, the step columns on the height", P.wd == 280 and P.sc == 100 and P.sel == "y")
    P.select(); P.nudge(1)
    chk("prd8: select -> width; a step up: 300 (%s), the bars that wide at once (the live preview), kept for Yes-Lander" % P.wd,
        P.wd == 300 and abs(P.hp.w - 300 * 768 / 1080) < 1e-6 and abs(P.f.w - 300 * 768 / 1080) < 1e-6 and Lg.WowCompanionDB.prdW["Yes-Lander"] == 300)
    P.select(); P.nudge(1); P.nudge(1)
    chk("prd8: select -> scale; two steps: 120 %% (%s), the whole bar scaled" % P.sc, P.sc == 120 and abs(P.f.scale - 1.2) < 1e-9
        and Lg.WowCompanionDB.prdS["Yes-Lander"] == 120)
    P.nudge(0)
    chk("prd8: default on the scale: 100, nothing kept", P.sc == 100 and Lg.WowCompanionDB.prdS["Yes-Lander"] is None)
    y0 = P.y; run(5); P.nudge(1)
    chk("prd8: 4 s with no tap: the step columns back on the height (an app that only knows the height still works): %s -> %s" % (y0, P.y),
        P.sel == "y" and P.y == y0 + 10 and P.wd == 300)
    P.nudge(-1)
    P.wd, P.sc = None, None; P.loadSize()
    chk("prd8: read back at log in: 300 wide, 100 %%", P.wd == 300 and P.sc == 100)
    P.select(); P.nudge(0); P.sel = "y"
    # prd10 (issue #120, a Rogue: "I can't see the combo points that are accumulated during combat"): the pips under the power bar
    chk("prd15: the version tag", P.V == "prd15")
    chk("prd10: the player's UNIT_POWER_FREQUENT is heard (combo points at the next frame)", ev.events.UNIT_POWER_FREQUENT == "player")
    L.execute('''
        local upm = UnitPowerMax
        function UnitPowerMax(u, t) if t == ((Enum.PowerType or {}).ComboPoints or 4) then return 5 end return upm(u, t) end
        CLASS = "MAGE"; function UnitClass() return CLASS:lower(), CLASS end
        CPOINTS = 3; function GetComboPoints() return setmetatable({ n = CPOINTS }, { secret = true }) end
    ''')
    P["values"]()
    chk("prd10: not a Rogue / a cat: no pips", P.cp is not None and P.cp.shown is False)
    Lg.CLASS = "ROGUE"
    P["values"]()
    gaps = [P.cp.gaps[i] for i in range(1, 5)]
    chk("prd10: a Rogue: the pips shown, 5 of them (4 gaps), the SECRET read fed straight to the bar (%s)" % (P.cp.value,),
        P.cp.shown is True and P.cp.max == 5 and P.cp.value is not None and getattr(P.cp.value, "n", None) == 3
        and all(g.shown for g in gaps) and P.cp.gaps[5] is None)
    L.execute('function GetComboPoints() return nil end; function UnitPower() return nil end')
    P["values"]()
    chk("prd10: nothing to read: an empty strip (0), no error", P.cp.shown is True and P.cp.value == 0)
    Lg.CLASS = "MAGE"; P["values"]()
    # prd11 PET HEALTH IN THE HUD (the user, 2026-11-11: "find a way to include Pet HP in the HUD, and better distinguish the player HUD vs
    # Pet HUD"; mock-up A "Sub-strip under")
    sk = 768 / 1080
    L.execute('function UnitPower(u, t) local w = (u == "pet") and (PET or {}) or ME; return setmetatable({ n = w.pw }, { secret = true }) end')
    def near(a, b): return a is not None and b is not None and abs(a - b) < 1e-6
    fire("PLAYER_REGEN_DISABLED"); ME.fight = True; run(0.3)
    ME.buffs = lst(aura(135953, 15, 9)); ME.debuffs = lst()
    fire("UNIT_AURA", "player"); run(0.3)
    top0 = P.buffs[1].f.point[5]
    chk("prd11: no pet: the strip built but hidden, it takes no room (petDrop %s), the aura row where it was" % P.petDrop,
        P.petL is not None and P.petL.shown is False and P.petDrop == 0 and near(top0, -(15 + 8 + 9 + 20) * sk))
    chk("prd11: the strip is its OWN layer: a child of the bar's frame beside the player's layer, never under it (the player's curve)",
        L.eval("rawequal(WowCompanion.prd.petL.parent, WowCompanion.prd.f)") and not L.eval("rawequal(WowCompanion.prd.petL.parent, WowCompanion.prd.g)"))
    u1 = ev.units.UNIT_HEALTH; u2 = ev.units.UNIT_SPELLCAST_START; u3 = ev.units.UNIT_AURA
    chk("prd11: the health / power events heard for the player AND the pet; the casts and the auras for the player only",
        u1[1] == "player" and u1[2] == "pet" and ev.units.UNIT_POWER_FREQUENT[2] == "pet" and ev.units.UNIT_MAXHEALTH[2] == "pet"
        and u2[1] == "player" and u2[2] is None and u3[1] == "player" and u3[2] is None and ev.units.UNIT_PET[1] == "player")
    Lg.PET = L.table_from({"hp": 300, "hpMax": 500, "pw": 60, "pwMax": 100, "ptype": 2, "tok": "FOCUS", "name": "Wolf", "icon": 132203,
                           "hunter": True, "happy": 3})
    fire("UNIT_PET", "player"); run(0.3)
    o = P.pet
    chk("prd11: a hunter's pet: the strip shown (%s), known by a plain UnitExists (%s)" % (P.petL.shown, P.pex and P.pex.src),
        P.petL.shown is True and P.pex.has is True and P.pex.src == "exists")
    chk("prd11: the pet's health FED: its secret straight to SetValue, the plain max to SetMinMaxValues",
        Lg.issecretvalue(o.hp.value) and o.hp.value.n == 300 and o.hp.max == 500 and o.hp.min == 0)
    chk("prd12: the pet's health in the PLAYER's green %s (the user: \"Same green as mine\")" % list(o.hp.color.values()),
        list(o.hp.color.values()) == list(P.hp.color.values()) == [0.12, 0.86, 0.12] and P.PET_RGB is None)
    chk("prd11: a hunter's pet's FOCUS colour %s, its power secret fed the same way (max %s)" % (list(o.pw.color.values()), o.pw.max),
        list(o.pw.color.values()) == [1, 0.5, 0.25] and Lg.issecretvalue(o.pw.value) and o.pw.max == 100)
    chk("prd11: thinner and shorter: %.1f x %.1f health, %.1f power, %d %% of the player's width" % (o.hp.w / sk, o.hp.h / sk, o.pw.h / sk, round(o.hp.w / P.hp.w * 100)),
        near(o.hp.w, 0.6 * 280 * sk) and near(o.hp.h, 8 * sk) and near(o.pw.h, 4 * sk) and near(o.pw.w, o.hp.w))
    chk("prd12: ONE PRD frame round both pet bars (the unit), at the PLAYER's texel scale: the rim one thickness (top %.2f = %.2f, bottom %.2f = %.2f)"
        % (o.frame.top.h, P.hpFrame.top.h, o.frame.bot.h, P.hpFrame.bot.h),
        o.frame.top is not None and near(o.frame.top.h, P.hpFrame.top.h) and near(o.frame.bot.h, P.hpFrame.bot.h)
        and near(o.frame.left.w, P.hpFrame.left.w) and near(o.frame.right.w, P.hpFrame.right.w) and near(o.k, 15 / 8 * sk)
        and o.hpFrame is None and o.pwFrame is None)
    chk("prd12: the unit spans the health's top to the power's bottom (%.1f x %.1f spec px): no lip between the two bars"
        % (o.unit.w / sk, o.unit.h / sk), L.eval("rawequal(WowCompanion.prd.pet.unit.point[2], WowCompanion.prd.pet.hp)")
        and o.unit.point[1] == "TOPLEFT" and o.unit.point[3] == "TOPLEFT" and near(o.unit.w, o.hp.w) and near(o.unit.h, (8 + 1 + 4) * sk)
        and L.eval("rawequal(WowCompanion.prd.pet.frame.bot.l.point[2], WowCompanion.prd.pet.unit)"))
    # prd13 (the user's "Same green as mine", 2026-11-11): the shade over the pet's health 45 % of ITS height, as the player's over theirs
    P.hp.hh = P.hp.h
    ps = P.frameFor(P.hp).shade.h
    P.hp.hh = None
    chk("prd13: the shade over the pet's health %.2f spec px = 45 %% of its 8 (was 45 %% of the 13 px unit); the player's %.2f of 15 (the same share)"
        % (o.frame.shade.h / sk, ps / sk), near(o.frame.shade.h, 0.45 * 8 * sk) and near(o.frame.shade.h / o.hp.h, ps / P.hp.h)
        and near(ps, 0.45 * 15 * sk) and o.frame.shade.h < o.hp.h)
    chk("prd12: the power right under the health: a %.1f spec px gap (<= 2)" % (-o.pw.point[5] / sk),
        o.pw.point[1] == "TOPLEFT" and o.pw.point[3] == "BOTTOMLEFT" and -o.pw.point[5] <= 2 * sk and -o.pw.point[5] > 0)
    chk("prd11: the strip's own lip over its bars (levels %s > %s), its track on the strip's layer" % (o.lip.level, o.hp.level or 1),
        o.lip.level > (o.hp.level or 1) and L.eval("rawequal(WowCompanion.prd.pet.frame.track.parent, WowCompanion.prd.petL)")
        and L.eval("rawequal(WowCompanion.prd.pet.frame.top.l.parent, WowCompanion.prd.pet.lip)"))
    chk("prd12: the round portrait %.1f spec px (>= 34), a thin rim (face %.1f): GetPetIcon's file (%s), masked round; NO name (%s)"
        % (o.ring.w / sk, o.face.w / sk, o.face.tex, o.name), o.ring.w >= 34 * sk and near(o.ring.w, o.ring.h) and near(o.ring.w - o.face.w, 4 * sk)
        and o.face.tex == 132203 and o.face.masked is not None and o.ring.masked is not None and o.name is None and P.PET_NAME is None)
    chk("prd11: the happiness face, plain 3 (happy): the game's PetFrame texture, its first face (%s)" % (o.happy.coord and list(o.happy.coord.values())),
        o.happy.shown is True and o.happy.tex.endswith("UI-PetHappiness") and list(o.happy.coord.values()) == [0, 0.1875, 0, 0.359375])
    # prd12: the rects in spec px across the strip (x from its left), from the anchors the code set
    g = P.petGeo()
    ring = (o.ring.point[4] / sk, o.ring.point[4] / sk + o.ring.w / sk)
    hpL = o.hp.point[4] / sk
    hapL = hpL + o.unit.w / sk + o.happy.point[4] / sk
    hap = (hapL, hapL + o.happy.w / sk)
    chk("prd12: the happiness face %.1f spec px (18-20), past the unit's right rim at x %.1f, clear of the portrait (%.1f..%.1f)"
        % (o.happy.w / sk, hap[0], ring[0], ring[1]), 18 * sk <= o.happy.w <= 20 * sk and near(o.happy.w, o.happy.h)
        and o.happy.point[1] == "LEFT" and o.happy.point[3] == "RIGHT" and L.eval("rawequal(WowCompanion.prd.pet.happy.point[2], WowCompanion.prd.pet.unit)")
        and o.happy.point[4] >= o.frame.right.w and (hap[0] >= ring[1] or hap[1] <= ring[0]) and hap[1] <= 280)
    grp = (ring[0], hap[1])
    chk("prd13: portrait + unit + happiness face centred under the player's bars (%.2f .. %.2f of 280)" % grp,
        near(grp[0], 280 - grp[1]) and grp[0] > 0 and o.ring.point[1] == "TOPLEFT" and o.hp.point[1] == "TOPLEFT"
        and near(hpL - ring[1], P.PET_IGAP) and hpL - ring[1] >= o.frame.left.w / sk)
    ringMid = -o.ring.point[5] / sk + o.ring.h / sk / 2
    unitMid = -o.hp.point[5] / sk + o.unit.h / sk / 2
    chk("prd12: the portrait vertically centred on the bars (%.2f = %.2f spec px from the strip's top), its top inside the strip"
        % (ringMid, unitMid), near(ringMid, unitMid) and o.ring.point[5] <= 0 and near(g.iconTop, 0))
    # prd13 (review, 2026-11-11): at the minimum width the happiness face inside the HUD frame (the pet bars narrowed to fit)
    P.W = P.W_MIN; P.petLayout(P.petDrop, True)
    hpL2 = o.hp.point[4] / sk
    hap2 = hpL2 + o.unit.w / sk + o.happy.point[4] / sk + o.happy.w / sk
    ring2 = o.ring.point[4] / sk
    chk("prd13: W = %s: the happiness face inside the frame (its right %.2f), portrait from %.2f, bars %.1f wide (<= 60 %%), centred"
        % (P.W_MIN, hap2, ring2, o.unit.w / sk), P.W_MIN == 180 and hap2 <= 180 + 1e-6 and ring2 >= 0 and o.unit.w <= 0.6 * 180 * sk + 1e-6
        and near(o.hp.w, o.unit.w) and near(o.pw.w, o.unit.w) and near(ring2, 180 - hap2) and o.happy.shown is True)
    P.W = 280; P.petLayout(P.petDrop, True)
    chk("prd13: back at 280: the bars 60 %% again (%.1f)" % (o.unit.w / sk), near(o.unit.w, 0.6 * 280 * sk))
    run(0.3)
    top1 = P.buffs[1].f.point[5]
    chk("prd13: the strip takes its room (petDrop %s = gap 17 + row 39.5): the aura row %.1f spec px lower, the frame taller, the shadow too"
        % (P.petDrop, (top0 - top1) / sk), near(P.petDrop, 56.5) and near(top0 - top1, 56.5 * sk) and near(P.f.h, (15 + 8 + 9 + 56.5) * sk)
        and near(P.shadow.point[5], -(15 + 8 + 9 + 16 + 56.5) * sk))
    clear = (-P.petL.point[5] - o.ring.point[5] - (15 + 8 + 9) * sk - P.pwFrame.bot.h) / sk
    chk("prd13: the strip under the power bar (%.1f spec px down); the portrait %.2f spec px clear of the player's power-bar lip (>= 1)"
        % (-P.petL.point[5] / sk, clear), near(P.petL.point[5], -(15 + 8 + 9 + 17) * sk) and P.petL.point[1] == "TOPLEFT" and clear >= 1 - 1e-6)
    # the pet health opening's top below the player's power bar; prd11 had it 16 spec px down (right under the lip): now lower
    petTop = (-P.petL.point[5] - o.hp.point[5]) / sk - (15 + 8 + 9)
    chk("prd12: the pet bars LOWER than prd11's (opening %.1f spec px under the player's power bar, was 16: +%.1f, 8..13)" % (petTop, petTop - 16),
        8 <= petTop - 16 <= 13 and petTop * sk - o.frame.top.h >= P.pwFrame.bot.h)
    chk("prd12: the row %s spec px (the portrait 36, the unit 13 with its rims 3 / 8 texels); the aura row under the unit's lip"
        % P.petRow(), near(P.petRow(), 39.5) and near(P.petL.h, 39.5 * sk) and near(g.row, max(g.iconTop + 36, g.unitTop + 13 + 15))
        and near(-o.hp.point[5] + o.unit.h + o.frame.bot.h, P.petL.h) and (P.ROW_GAP * sk) >= 0)
    chk("prd11: no lag chunk for the pet (its queue would keep a secret in a table)", o.lag is None)
    vals = [P.pex[k] for k in ("has", "src", "dead", "ptok", "happy", "cbad", "icon", "a")]
    chk("prd11: the probe's pet (has / src / ptok / happy) holds no secret", all(not Lg.issecretvalue(v) for v in vals)
        and P.pex.ptok == "FOCUS" and P.pex.happy == 3)
    Lg.PET.happySecret = True; run(0.3)
    chk("prd11: happiness SECRET: no face (%s)" % o.happy.shown, o.happy.shown is False and P.pex.happy is None)
    Lg.PET.happySecret = None
    # a pet's cast is never the player's
    sp0 = P.spentAt
    ev.scripts.OnEvent(ev, "UNIT_SPELLCAST_START", "pet", "guid", 8936)
    chk("prd11: a pet's cast: no cost segment on the player's power bar", P.cost.shown is False and P.ex.cost is None)
    ev.scripts.OnEvent(ev, "UNIT_SPELLCAST_SUCCEEDED", "pet", "guid", 17253)
    chk("prd11: a pet's cast done: the player's spend clock untouched (%s)" % P.spentAt, P.spentAt == sp0)
    ev.scripts.OnEvent(ev, "UNIT_AURA", "pet")
    chk("prd11: a pet's aura change: the player's aura list not re-read", not P.auraDirty)
    # a warlock's demon: mana, no happiness
    Lg.PET = L.table_from({"hp": 900, "hpMax": 900, "pw": 200, "pwMax": 400, "ptype": 0, "tok": "MANA", "name": "Voidwalker", "icon": 136221})
    fire("UNIT_PET", "player"); run(0.3)
    chk("prd11: a demon: MANA's blue %s, no happiness face, its own portrait (%s)" % (list(o.pw.color.values()), o.face.tex),
        list(o.pw.color.values()) == [0, 0.45, 1] and o.happy.shown is False and o.face.tex == 136221 and o.name is None)
    dr = o.hp.point[4] / sk + o.unit.w / sk + o.frame.right.w / sk
    chk("prd13: no face can show: portrait + unit alone centred again (%.2f .. %.2f of 280)" % (o.ring.point[4] / sk, dr),
        P.petHappyOn is False and near(o.ring.point[4] / sk, 280 - dr) and near(o.unit.w, 0.6 * 280 * sk))
    Lg.PET.tok = None; Lg.PET.ptype = 2; run(0.3)
    chk("prd11: no plain token: the type's number says FOCUS (%s)" % P.pex.ptok, P.pex.ptok == "FOCUS" and list(o.pw.color.values()) == [1, 0.5, 0.25])
    Lg.PET.tok = "MANA"; Lg.PET.ptype = 0
    # dead: greyed (prd12: no name, no word)
    Lg.PET.dead = True; run(0.3)
    chk("prd11: a dead pet greyed (%s), no name text (%s)" % (list(o.hp.color.values()), o.name),
        list(o.hp.color.values()) == [0.5, 0.5, 0.5] and o.name is None and P.pex.dead is True)
    Lg.PET.dead = None; run(0.3)
    chk("prd12: alive again: the player's green", list(o.hp.color.values()) == [0.12, 0.86, 0.12])
    # UnitExists secret: a plain name says it
    Lg.PET_EXISTS_SECRET = True; run(0.3)
    chk("prd11: UnitExists secret: known by its plain name (%s)" % P.pex.src, P.petL.shown is True and P.pex.src == "name")
    Lg.PET_EXISTS_SECRET = False
    # a Rogue's pips show: the strip under them
    Lg.CLASS = "ROGUE"; run(0.3)
    chk("prd11: the combo pips shown: the strip under them (petDrop %s, %.1f spec px down)" % (P.petDrop, -P.petL.point[5] / sk),
        P.cp.shown is True and near(P.petDrop, 65.5) and near(P.petL.point[5], -(15 + 8 + 9 + 9 + 17) * sk))
    Lg.CLASS = "MAGE"; run(0.3)
    chk("prd11: the pips gone again: back under the power bar (%s)" % P.petDrop, near(P.petDrop, 56.5))
    # its own switch
    Lg.WowCompanion.npWant.pet = False; run(0.3)
    chk("prd11: its switch off (npWant.pet false): hidden, no room taken, the aura row back (%s)" % P.petDrop,
        P.petL.shown is False and P.petDrop == 0 and near(P.buffs[1].f.point[5], top0) and P.pex.src == "off")
    Lg.WowCompanion.npWant.pet = None; run(0.3)
    chk("prd11: on unless turned off (npWant.pet nil): shown", P.petL.shown is True and P.wantedPet() is True)
    # the pet dismissed
    Lg.PET = None; fire("UNIT_PET", "player"); run(0.3)
    chk("prd11: the pet gone: hidden, petDrop 0 (%s)" % P.petDrop, P.petL.shown is False and P.petDrop == 0)
    # THE CURVE: the strip by the pet's own health, its own bad flag
    Lg.PET = L.table_from({"hp": 300, "hpMax": 500, "pw": 60, "pwMax": 100, "ptype": 2, "tok": "FOCUS", "name": "Wolf", "icon": 132203, "hunter": True})
    Lg.HPM = L.table_from({"hp": 1000, "max": 1000, "mp": 1000, "mpMax": 1000})
    ME.ptype, ME.tok, ME.hp = 0, "MANA", 1000
    fire("UNIT_DISPLAYPOWER", "player")
    P.curveBad = None
    ME.fight = False; fire("PLAYER_REGEN_ENABLED"); run(3)
    chk("prd11: the curve route out of combat (%s): the pet's strip gets the pet's own SECRET alpha (hurt: %s), the player's layer its own (%s)"
        % (P.src, P.petL.alpha and getattr(P.petL.alpha, "n", P.petL.alpha), getattr(P.g.alpha, "n", P.g.alpha)),
        P.src == "curve" and P.petSrc == "curve" and Lg.issecretvalue(P.petL.alpha) and P.petL.alpha.n == 1 and Lg.issecretvalue(P.g.alpha) and P.g.alpha.n == 0)
    Lg.PET.hp = 500; run(0.3)
    chk("prd11: the pet full: its strip's secret alpha 0", P.petL.alpha.n == 0)
    Lg.PET_REFUSE = True; Lg.PET.hp = 200; run(0.3)
    chk("prd11: the pet's curve refused: its OWN flag (%s), the strip on the player's curve (review fix: %s); the player's curve untouched (%s, %s)"
        % (P.petCurveBad, P.petSrc, P.curveBad, P.src),
        P.petCurveBad is not None and "pet refused" in P.petCurveBad and P.curveBad is None and P.src == "curve"
        and P.petSrc == "player" and Lg.issecretvalue(P.petL.alpha) and Lg.issecretvalue(P.g.alpha))
    Lg.PET_REFUSE = None
    fire("PLAYER_REGEN_DISABLED"); ME.fight = True; run(0.3)
    chk("prd11: in a fight the strip plainly shown", P.petL.alpha == 1 and P.petSrc == "plain")
    Lg.PET = None; run(0.3)
    # review of prd11 (2026-11-11): DISMISSED ON THE CURVE ROUTE - the tick asks the pet's curve before the poll; with no pet that must
    # latch nothing (the stub's UnitHealthPercent errors on a nil PET, as a refusal would)
    P.petCurveBad = None
    Lg.PET = L.table_from({"hp": 300, "hpMax": 500, "pw": 60, "pwMax": 100, "ptype": 2, "tok": "FOCUS", "name": "Wolf", "icon": 132203, "hunter": True})
    fire("UNIT_PET", "player"); ME.fight = False; fire("PLAYER_REGEN_ENABLED"); run(3)
    chk("prd11 review: the curve route with a pet (%s, %s)" % (P.src, P.petSrc), P.src == "curve" and P.petSrc == "curve" and P.petL.shown is True)
    Lg.PET = None
    ev.scripts.OnUpdate(ev, 1 / 30)                                      # a tick before UNIT_PET / the poll: the curve asked first
    chk("prd11 review: dismissed, the tick before the poll: nothing latched (%s), the strip hidden, no room (%s)" % (P.petCurveBad, P.petDrop),
        P.petCurveBad is None and P.petL.shown is False and P.petDrop == 0 and P.curveBad is None)
    fire("UNIT_PET", "player"); run(0.5)
    chk("prd11 review: ... and after UNIT_PET and the poll: still nothing latched, hidden (%s)" % P.petCurveBad,
        P.petCurveBad is None and P.petL.shown is False)
    Lg.PET = L.table_from({"hp": 300, "hpMax": 500, "pw": 60, "pwMax": 100, "ptype": 2, "tok": "FOCUS", "name": "Wolf", "icon": 132203, "hunter": True})
    Lg.PET.hp = 200
    fire("UNIT_PET", "player"); run(0.5)
    chk("prd11 review: resummoned out of combat: the pet's own curve alpha back (%s, %s)" % (P.petSrc, getattr(P.petL.alpha, "n", P.petL.alpha)),
        P.petL.shown is True and P.petSrc == "curve" and Lg.issecretvalue(P.petL.alpha) and P.petL.alpha.n == 1)
    Lg.PET = None; fire("UNIT_PET", "player")
    chk("prd11 review: UNIT_PET with no pet hides the strip at once (%s)" % P.petL.shown, P.petL.shown is False and P.petCurveBad is None)
    # a refusal WITH a pet out: latched, and the strip follows the PLAYER's curve (the bars' own layer: it fades with the HUD), not 1
    Lg.PET = L.table_from({"hp": 200, "hpMax": 500, "pw": 60, "pwMax": 100, "ptype": 2, "tok": "FOCUS", "name": "Wolf", "icon": 132203, "hunter": True})
    Lg.PET_REFUSE = True; ME.hp = 1000
    fire("UNIT_PET", "player"); run(0.5)
    chk("prd11 review: refused with a pet out: latched (%s), the strip on the PLAYER's curve (%s: player full -> %s)"
        % (P.petCurveBad, P.petSrc, getattr(P.petL.alpha, "n", P.petL.alpha)),
        P.petCurveBad is not None and P.petSrc == "player" and Lg.issecretvalue(P.petL.alpha) and P.petL.alpha.n == 0 and P.curveBad is None)
    ME.hp = 600; run(0.3)
    chk("prd11 review: ... the player hurt: the strip with the bars (%s)" % P.petL.alpha.n, P.petL.alpha.n == 1)
    ME.hp = 1000; Lg.PET_REFUSE = None; P.petCurveBad = None
    Lg.PET = None; run(0.3)
    # prd14 (the user, 2026-11-11: "When selecting myself or my pet it just highlights my HUD bar"): Party mode's pick on you / your pet
    Lg.WowCompanion.padMap = None
    ME.fight = False; fire("PLAYER_REGEN_ENABLED"); ME.hp = 1000; run(5)
    chk("prd14: no PadMap: no box (%s), the HUD faded out of a fight at full health (%s)" % (P.pickWho, P.why),
        P.pickWho is None and P.pick is not None and P.pick.you.on is not True and P.why != "party")
    L.execute('PMP = { on = false, u = "player" }; WowCompanion.padMap = { party = { on = function() return PMP.on end, '
              'box = function() return { u = PMP.u } end } }')
    PMP = Lg.PMP
    run(0.5)
    chk("prd14: Party mode off: no box (%s)" % P.pickWho, P.pickWho is None and P.pick.you.on is not True and P.why != "party")
    PMP.on = True; run(0.5)
    you = P.pick.you
    chk("prd14: the pick on you: the gold box round the player's bars (%s), the HUD shown for it (%s, alpha %.2f)" % (P.pickWho, P.why, P.alpha),
        P.pickWho == "player" and you.on is True and you.t[1].shown is True and P.pick.pet.on is not True and P.why == "party"
        and P.shown is True and P.g.alpha == 1)
    chk("prd14: the box in Hub's ring gold (1, 0.82, 0), on P.f's own layer (never the curve's P.g)",
        [you.t[1].colortex[i] for i in (1, 2, 3)] == [1, 0.82, 0] and L.eval("rawequal(WowCompanion.prd.pick.host.parent, WowCompanion.prd.f)"))
    PMP.u = "pet"; run(0.5)
    chk("prd14: the pick on the pet with no pet out: no box (%s)" % P.pickWho, P.pickWho is None and you.on is not True and P.pick.pet.on is not True)
    Lg.PET = L.table_from({"hp": 300, "hpMax": 500, "pw": 60, "pwMax": 100, "ptype": 2, "tok": "FOCUS", "name": "Wolf", "icon": 132203, "hunter": True})
    fire("UNIT_PET", "player"); run(0.5)
    chk("prd14: the pick on the pet, its strip out: the gold box round the strip (%s), none round yours; the strip plainly shown (%s)"
        % (P.pickWho, P.petSrc), P.pickWho == "pet" and P.pick.pet.on is True and you.on is not True and P.petL.shown is True
        and P.petL.alpha == 1 and P.petSrc == "party")
    # prd14 review (two reviewers, 2026-11-11): each box hangs on ONE area frame: the area's TOPLEFT on the first region, its BOTTOMRIGHT
    # on the second; all eight edge points on the area itself (no edge spans two regions - the pet's clashed: portrait vs bars)
    geo = L.eval('''(function()
        local P = WowCompanion.prd
        local function pts(r) local o = {}; for i, p in ipairs(r.anch or {}) do o[i] = p end; return o end
        local function box(r, tl, br)
            local a = pts(r.area)
            if #a ~= 2 or a[1][1] ~= "TOPLEFT" or not rawequal(a[1][2], tl) or a[1][3] ~= "TOPLEFT"
                or a[2][1] ~= "BOTTOMRIGHT" or not rawequal(a[2][2], br) or a[2][3] ~= "BOTTOMRIGHT" then return "area" end
            local want = { { "TOPLEFT", "TOPRIGHT" }, { "BOTTOMLEFT", "BOTTOMRIGHT" }, { "TOPLEFT", "BOTTOMLEFT" }, { "TOPRIGHT", "BOTTOMRIGHT" } }
            for i = 1, 4 do
                local e = pts(r.t[i])
                if #e ~= 2 then return "edge" .. i .. " n" end
                for j = 1, 2 do
                    if e[j][1] ~= want[i][j] or e[j][3] ~= want[i][j] or not rawequal(e[j][2], r.area) or e[j][4] ~= 0 or e[j][5] ~= 0 then
                        return "edge" .. i .. "." .. j
                    end
                end
            end
            return a
        end
        local y, q = box(P.pick.you, P.hp, P.pw), box(P.pick.pet, P.pet.ring, P.pet.unit)
        if type(y) == "string" then return "you " .. y end
        if type(q) == "string" then return "pet " .. q end
        local g, pad, k = P.petGeo(), P.PICK_PAD, P.HH / 8
        local s = (P.pet.ring.w or P.PET_ICON) / P.PET_ICON                -- the spec px -> UI scale (u is linear)
        -- the pet's area in the row's own frame (L's top-left = 0, 0; y down), in spec px: left / top / right / bottom
        local ringL, ringT = g.x0, g.iconTop
        local unitR, unitB = g.x0 + P.PET_ICON + P.PET_IGAP + g.barW, g.unitTop + g.unit
        local l, t = ringL + q[1][4] / s, ringT - q[1][5] / s
        local r, b = unitR + q[2][4] / s, unitB - q[2][5] / s
        return string.format("%.3f %.3f %.3f %.3f|%.3f %.3f %.3f %.3f", l, t, r, b,
            g.x0 - pad, -pad, unitR + 7 * k + pad, g.row + pad)
    end)()''')
    gl = geo.split("|") if "|" in geo else None
    chk("prd14 review: the boxes on one area each, every edge on it alone; the pet's area = the row: portrait left, row top / bottom, the "
        "bars' right rim, PICK_PAD out (%s)" % geo, gl is not None and gl[0] == gl[1])
    PMP.u = "party1"; run(0.5)
    chk("prd14: the pick on a party member: no box here (Hub's ring on the top block)", P.pickWho is None and P.pick.pet.on is not True)
    PMP.u = "player"; run(0.5)
    PMP.on = False; run(5)
    chk("prd14: Party mode off again: the box gone, the HUD back to its own rules (%s)" % P.why, P.pickWho is None and you.on is not True
        and P.why != "party")
    Lg.WowCompanion.padMap = None
    Lg.PET = None; fire("UNIT_PET", "player"); run(0.3)
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
