"""The live aggro layer's first slice (WowCompanion/Trail.lua, WC.live), off-device: Trail.lua under the compass test's stub game,
plus nameplates, units, the spellbook and the range checks.

    python tools/test_aggro.py

Checks the contract (safepath-work/docs/AGGRO_DOC_CONTRACT_v1.md): the range bands and brackets, the slots (stable, 10 s reuse, 24
at most), the per-mob fields (npc id out of combat only, secrets omitted), the full / same records and their transport (riding the
pos doc while moving, standalone at most 1 a second while still), the off states, the coverage record, the bearing from the plate's
screen x, the pull detector, and that nothing is written. Needs lupa. Exit code 1 on any failure."""
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_compass as tc  # noqa: E402

WORLD = r'''
UNITS = {}            -- token -> { d = yards, lv, cls, r, cb, om, dead, td, qi, guid, ct, player, x = screen x (UI units) }
SECRET = setmetatable({}, { __tostring = function() return "SECRET" end })
function issecretvalue(v) return v == SECRET end
local function U(t) return UNITS[t] end
function UnitExists(t) return U(t) ~= nil end
function UnitIsPlayer(t) return U(t) and U(t).player or false end
function UnitCanAttack(a, t) return U(t) ~= nil and (U(t).r or 2) <= 4 end
function UnitReaction(t, b) return U(t) and (U(t).r or 2) end
function UnitGUID(t) if GAME.fight then return SECRET end return U(t) and U(t).guid end
function UnitCreatureType(t) if GAME.fight then return SECRET, SECRET end return "Beast", U(t) and U(t).ct end
function UnitLevel(t) if t == "player" then return 6 end return U(t) and U(t).lv end
function UnitClassification(t) return U(t) and (U(t).cls or "normal") end
function UnitAffectingCombat(t) if t == "pet" then return GAME.petFight or false end return U(t) and U(t).cb or false end
function UnitThreatSituation(p, t) return U(t) and U(t).om end
function UnitIsTapDenied(t) return U(t) and U(t).td or false end
function UnitIsDead(t) return U(t) and U(t).dead or false end
C_QuestLog.UnitIsRelatedToActiveQuest = function(t) return U(t) and U(t).qi or false end
function IsInInstance() return GAME.inst or false, GAME.instKind end
-- the plate: its centre x in UIParent units (1365 wide)
C_NamePlate = { GetNamePlateForUnit = function(t)
    local u = U(t); if not u then return nil end
    return { GetCenter = function() return u.x or 682.6667, 400 end, GetEffectiveScale = function() return 1 end }
end }
-- a level-6 shaman's book: Earth Shock 20 yd, Lightning Bolt 30 yd (harmful), Healing Wave (helpful)
Enum = { SpellBookSpellBank = { Player = 0 } }
local SPELLS = { [8042] = { max = 20, harm = true }, [403] = { max = 30, harm = true }, [331] = { max = 40, harm = false },
                 [1001] = { max = 10 }, [1002] = { max = 25 } }
C_SpellBook = {
    GetNumSpellBookSkillLines = function() return 1 end,
    GetSpellBookSkillLineInfo = function(i) return { itemIndexOffset = 0, numSpellBookItems = 3 } end,
    GetSpellBookItemInfo = function(slot) return ({ { spellID = 8042 }, { spellID = 403 }, { spellID = 331 } })[slot] end,
}
C_Spell = {
    GetSpellInfo = function(id) local s = SPELLS[id]; if s then return { maxRange = s.max, minRange = 0 } end end,
    IsSpellHarmful = function(id) return SPELLS[id] and SPELLS[id].harm or false end,
    IsSpellInRange = function(id, t) local u = U(t); if not u then return nil end; return u.d <= SPELLS[id].max end,
}
-- items: Voodoo Charm 10 yd, Large Rope Net 25 yd cached; the rest not loaded yet (asked for)
local ITEM = { [8149] = 1001, [835] = 1002 }
LOADREQ = {}
C_Item = {
    IsItemDataCachedByID = function(id) return ITEM[id] ~= nil end,
    RequestLoadItemDataByID = function(id) LOADREQ[#LOADREQ + 1] = id end,
    GetItemSpell = function(id) if ITEM[id] then return "x", ITEM[id] end end,
    IsItemInRange = function(id, t) local u = U(t); if not u then return nil end; return u.d <= SPELLS[ITEM[id]].max end,
}
local CI = { [1] = 28, [2] = 8, [3] = 7, [4] = 28 }
function CheckInteractDistance(t, i) local u = U(t); if not u then return false end; return u.d <= CI[i] end
CV.nameplateMaxDistance = "41"; CV.nameplateShowOffscreen = "0"; CV.nameplateShowEnemies = "1"; CV.nameplateMaxVerticalDistance = "60"
CVSET = {}
C_CVar.SetCVar = function(k, v) CVAR_WRITES = CVAR_WRITES + 1; CVSET[#CVSET + 1] = k .. "=" .. tostring(v); CV[k] = tostring(v) end
WowCompanion.routeWp = { active = false }
'''


def main():
    L = tc.lupa.LuaRuntime()
    L.execute(tc.STUB)
    L.execute(WORLD)
    L.execute(open(tc.TRAIL, encoding="utf-8").read().replace("local ADDON = ...", "local ADDON = 'WowCompanion'", 1))
    fails = []

    def chk(name, ok):
        print(("PASS " if ok else "FAIL ") + name)
        if not ok:
            fails.append(name)

    G, A, Lg = L.globals().GAME, L.globals().WowCompanion.live, L.globals()
    frames = [Lg.FRAMES[i] for i in range(1, len(Lg.FRAMES) + 1)]
    ev = [f for f in frames if f.events and f.events.NAME_PLATE_UNIT_ADDED][0]

    def fire(e, a=None):
        ev.scripts.OnEvent(ev, e, a)

    def tick(dt=0.2, moving=True):
        # moving: Core's pos sends drain the record (as at 10 Hz); still: nothing drains it
        G.t = G.t + dt
        if moving:
            A.drainAt = G.t
        ev.scripts.OnUpdate(ev, dt)

    def unit(tok, **kw):
        d = {"d": 17, "lv": 7, "r": 2, "guid": "Creature-0-1-2-3-3098-0000AAAA", "ct": 1}
        d.update(kw)
        Lg.UNITS[tok] = L.table_from(d)

    def mobs(rec):
        return {rec.m[i].s: rec.m[i] for i in range(1, len(rec.m) + 1)}

    G.t = 100.0
    G.facing = 0.0
    fire("PLAYER_ENTERING_WORLD")
    bands = [(A.bands[i].max, A.bands[i].kind) for i in range(1, len(A.bands) + 1)]
    chk("bands from the book, the items and CheckInteractDistance, sorted: %s" % bands,
        bands == [(8, "d"), (10, "i"), (20, "s"), (25, "i"), (28, "d"), (30, "s")])
    chk("the items not loaded yet are asked for (%d)" % len(Lg.LOADREQ), len(Lg.LOADREQ) == 9)

    # plates: a hostile mob, a friendly one, an enemy player
    unit("nameplate1")
    unit("nameplate2", r=5)
    unit("nameplate3", player=True)
    for t in ("nameplate1", "nameplate2", "nameplate3"):
        fire("NAME_PLATE_UNIT_ADDED", t)
    chk("only the hostile NPC gets a slot (0)", A.byTok.nameplate1 == 0 and A.byTok.nameplate2 is None and A.byTok.nameplate3 is None)
    tick()
    rec = A.last
    m = mobs(rec)[0]
    chk("the first full record: q=1, v=1, the player (lv, map, x, y, f)", rec.q == 1 and rec.v == 1 and rec.me.lv == 6 and rec.me.map == 1429)
    chk("17 yd: bracket a=10 (the 10 yd item), b=20 (Earth Shock), rk s", m.a == 10 and m.b == 20 and m.rk == "s")
    chk("its npc id (3098) and creature type, level, class, reaction", m.n == 3098 and m.ct == 1 and m.lv == 7 and m.c == "n" and m.r == 2)
    chk("booleans as 0/1, threat omitted when not on us", m.cb == 0 and m.om is None and m.qi == 0)
    # brackets near and far
    unit("nameplate4", d=9)
    unit("nameplate5", d=50)
    fire("NAME_PLATE_UNIT_ADDED", "nameplate4"); fire("NAME_PLATE_UNIT_ADDED", "nameplate5")
    tick()
    mm = mobs(A.last)
    chk("9 yd: a=8 (CheckInteractDistance), b=10 (the item), rk i", mm[1].a == 8 and mm[1].b == 10 and mm[1].rk == "i")
    chk("50 yd: a=30, b omitted (beyond our largest check)", mm[2].a == 30 and mm[2].b is None)
    # the transport: moving, the pos doc drains it
    r = Lg.WowCompanion.drainAggro()
    chk("moving: the pos doc takes the pending record (full)", r is not None and r.q == A.q and r.m is not None)
    tick()
    chk("unchanged: a SAME record (q kept, same=1)", A.pending.same == 1 and A.pending.q == A.q)
    Lg.WowCompanion.drainAggro()
    for i in range(25):
        tick()
        Lg.WowCompanion.drainAggro()
    chk("a full record forced every 5 s even unchanged", A.sent.full >= 2)
    # combat: the GUID is secret, a known npc id is kept, a newcomer gets none
    G.fight = True
    unit("nameplate6", guid="Creature-0-1-2-3-3099-0000BBBB")
    fire("NAME_PLATE_UNIT_ADDED", "nameplate6")
    tick()
    mm = mobs(A.last)
    chk("in combat: the known npc id kept (3098), the newcomer's omitted", mm[0].n == 3098 and mm[3].n is None and A.pending.comb == 1)
    G.fight = False
    tick()
    chk("out of combat again: the newcomer's id read (3099)", mobs(A.last)[3].n == 3099)
    # a secret level is omitted, never null
    unit("nameplate1", lv=Lg.SECRET)
    tick()
    chk("a secret level: omitted", mobs(A.last)[0].lv is None)
    unit("nameplate1")
    # STILL: nothing drains it: a standalone {"type":"aggro"} at most once a second, full records only
    sent0 = len(Lg.SENT)
    for i in range(20):
        Lg.UNITS.nameplate1.d = 17 if i % 2 else 18         # 17 / 18 change nothing in the bracket ... until a level change
        Lg.UNITS.nameplate1.lv = 7 + (i % 3)                # a real change every scan
        tick(moving=False)
    still = [Lg.SENT[i] for i in range(sent0 + 1, len(Lg.SENT) + 1) if getattr(Lg.SENT[i], "type", None) == "aggro"]
    chk("still: standalone records at most 1 a second (%d in 4 s)" % len(still), 3 <= len(still) <= 5 and all(s.same is None for s in still))
    Lg.UNITS.nameplate1.lv = 7
    # slots: freed, reused only after 10 s
    fire("NAME_PLATE_UNIT_REMOVED", "nameplate1")
    unit("nameplate7"); fire("NAME_PLATE_UNIT_ADDED", "nameplate7")
    chk("a slot freed just now is not reused (the new plate takes %s, not 0)" % A.byTok.nameplate7, A.byTok.nameplate7 not in (0, None))
    G.t = G.t + 11
    unit("nameplate8"); fire("NAME_PLATE_UNIT_ADDED", "nameplate8")
    chk("after 10 s free, slot 0 is reused", A.byTok.nameplate8 == 0)
    # dead: one record with dd=1, then the slot goes
    Lg.UNITS.nameplate8.dead = True
    fire("NAME_PLATE_UNIT_REMOVED", "nameplate8")
    tick()
    chk("died: a dd=1 entry for slot 0 in the next record", mobs(A.last)[0].dd == 1)
    tick()
    chk("... then gone", 0 not in mobs(A.last))
    # 24 at most
    for k in range(10, 40):
        unit("nameplate%d" % k)
        fire("NAME_PLATE_UNIT_ADDED", "nameplate%d" % k)
    tick()
    chk("never more than 24 slots (%d mobs reported)" % len(A.last.m), len(A.last.m) == 24)
    for k in range(10, 40):
        fire("NAME_PLATE_UNIT_REMOVED", "nameplate%d" % k)
        Lg.UNITS["nameplate%d" % k] = None
    # the bearing from the screen x: the plate 30% right of the centre, the camera = the facing (north)
    Lg.UNITS.nameplate4.x = 682.6667 + 0.3 * 682.6667
    G.facing = 0.0
    tick()
    m4 = mobs(A.last)[1]
    want = tc.lupa.LuaRuntime().eval("1") and -math.atan(0.3 * math.tan(math.radians(45)))
    chk("sx 0.3 and a bearing %.3f (the facing less the plate's angle), bs f" % want, abs(m4.sx - 0.3) < 1e-6 and abs(m4.br - want) < 0.002 and m4.bs == "f")
    Lg.UNITS.nameplate4.x = 1360.0
    tick()
    chk("at the screen's edge: cl=1 and no bearing", mobs(A.last)[1].cl == 1 and mobs(A.last)[1].br is None)
    # the pull detector: calm at 17..20, then the mob turns on us at 17
    unit("nameplate9", d=19, lv=7)
    fire("NAME_PLATE_UNIT_ADDED", "nameplate9")
    tick()
    s9 = A.byTok.nameplate9
    A.lastAction = -100
    Lg.UNITS.nameplate9.om = 3; Lg.UNITS.nameplate9.cb = True; Lg.UNITS.nameplate9.d = 17
    tick()
    pl = A.last.pl
    chk("a pull: [lo 10 .. hi 20] for slot %s, npc 3098, levels 7 / 6" % s9,
        pl is not None and pl[1].s == s9 and pl[1].lo == 10 and pl[1].hi == 20 and pl[1].n == 3098 and pl[1].plv == 6)
    unit("nameplate11", d=19); fire("NAME_PLATE_UNIT_ADDED", "nameplate11"); tick()
    fire("UNIT_SPELLCAST_SENT", "player")
    Lg.UNITS.nameplate11.om = 3; Lg.UNITS.nameplate11.cb = True
    tick()
    chk("a pull right after our own cast is rejected (%s)" % A.lastReject, A.last.pl is None and A.lastReject == "own action")
    # the encoded record is valid JSON with no null
    bad = []

    def walk(v, path):
        if tc.lupa.lua_type(v) == "table":
            for k in list(v.keys()):
                walk(v[k], path + "." + str(k))
        elif isinstance(v, bool):
            bad.append(path)
    walk(A.last, "rec")
    chk("the record holds no booleans (0/1 only) and no nil holes (%s)" % bad[:3], not bad)
    # off: an instance: one off record, the slots cleared, then nothing
    G.inst = True; G.instKind = "party"
    tick()
    off = A.pending
    chk("in an instance: one record off=inst, the slots cleared", off.off == "inst" and A.byTok.nameplate4 is None)
    Lg.WowCompanion.drainAggro(); tick()
    chk("... then nothing", A.pending is None)
    G.instKind = "pvp"; A.off = None; tick()
    chk("a battleground: off=pvp", A.pending.off == "pvp")
    G.inst = False; G.instKind = None
    fire("ENCOUNTER_START"); tick()
    chk("an encounter: off=enc", A.pending.off == "enc")
    fire("ENCOUNTER_END"); tick()
    # the coverage record
    cov = A.coverage()
    sp = [(cov.sp[i][1], cov.sp[i][2]) for i in range(1, len(cov.sp) + 1)]
    it = [(cov.it[i][1], cov.it[i][2]) for i in range(1, len(cov.it) + 1)]
    chk("coverage: the CVars READ (maxd 41, off 0, ena 1, vmax 60), sp %s, it %s, ci" % (sp, it),
        cov.cv.maxd == "41" and cov.cv.off == "0" and cov.cv.ena == "1" and cov.cv.vmax == "60" and sp == [(8042, 20), (403, 30)]
        and it == [(8149, 10), (835, 25)] and cov.ci[1] == 28)
    # the probe line
    A.diagAt = -100; A.diag()
    d = A.lastDiag
    chk("the probe line: plates, slots, bands s/i/d, sx plain, q, sent counts", d.probe == "aggro" and d.bs == 2 and d.bi == 2 and d.bd == 2 and d.sx is True)
    chk("no CVar written while no route runs", Lg.CVAR_WRITES == 0)

    # ag2: ENEMY NAMEPLATES ON while SafePath routes (the user's ruling), the player's value kept and given back
    CV = Lg.CV
    CV.nameplateShowEnemies = "0"
    Lg.WowCompanion.routeWp.active = True
    G.fight = True; tick()
    chk("the route starts in a fight: waits (nothing written)", Lg.CVAR_WRITES == 0 and A.np.state == "waiting (combat)")
    G.fight = False; tick()
    chk("out of the fight: nameplateShowEnemies = 1, the player's 0 kept in our saved data",
        CV.nameplateShowEnemies == "1" and Lg.WowCompanionDB.npEnemies.orig == "0" and A.np.state == "on (ours)")
    chk("only that one CVar is ever written (%s)" % list(Lg.CVSET.values()), list(Lg.CVSET.values()) == ["nameplateShowEnemies=1"])
    Lg.WowCompanion.routeWp.active = False
    for i in range(50):
        tick()
    chk("the route off 10 s: still on (the 15 s grace)", CV.nameplateShowEnemies == "1")
    for i in range(30):
        tick()
    chk("the route off 16 s: the player's 0 back, our saved copy gone", CV.nameplateShowEnemies == "0" and Lg.WowCompanionDB.npEnemies is None)
    # ag4: HUD mode (Hub hub14) wants them too: one owner, two reasons; given back only when neither wants them
    L.execute('WowCompanion.npWant = { hud = true }')
    tick()
    chk("HUD mode on, no route: nameplateShowEnemies = 1, the player's 0 kept", CV.nameplateShowEnemies == "1" and Lg.WowCompanionDB.npEnemies.orig == "0")
    Lg.WowCompanion.routeWp.active = True; tick(); Lg.WowCompanion.routeWp.active = False
    for i in range(80):
        tick()
    chk("the route ends while HUD mode is on: still on", CV.nameplateShowEnemies == "1")
    L.execute('WowCompanion.npWant.hud = nil')
    for i in range(80):
        tick()
    chk("HUD mode off too: the player's 0 back", CV.nameplateShowEnemies == "0" and Lg.WowCompanionDB.npEnemies is None)
    Lg.WowCompanion.routeWp.active = True; tick()
    CV.nameplateShowEnemies = "0"; tick()             # the player switches them off themselves
    chk("the player switches them off meanwhile: their choice stands (declined, not set again)",
        CV.nameplateShowEnemies == "0" and A.np.state == "declined")
    for i in range(5):
        tick()
    chk("... still declined", CV.nameplateShowEnemies == "0")
    A.np.declined = False; Lg.WowCompanionDB.npEnemies = None; tick()
    chk("(a new session) on again", CV.nameplateShowEnemies == "1")
    fire("PLAYER_LOGOUT")
    chk("log out: the player's value back", CV.nameplateShowEnemies == "0" and Lg.WowCompanionDB.npEnemies is None)
    Lg.WowCompanion.routeWp.active = False

    # ag2: the plate ICONS: AVOID on the dangerous, QUEST on objectives, none on the rest, anchored above the plate
    for t in list(A.byTok.keys()):
        fire("NAME_PLATE_UNIT_REMOVED", t)
    G.t = G.t + 11
    unit("nameplate50", d=15, lv=7, cls="elite")
    unit("nameplate51", d=15, lv=5, qi=True)
    unit("nameplate52", d=15, lv=5)
    unit("nameplate53", d=15, lv=9)
    for t in ("nameplate50", "nameplate51", "nameplate52", "nameplate53"):
        fire("NAME_PLATE_UNIT_ADDED", t)
    tick()
    ic = {A.byTok[t]: A.icon[A.byTok[t]] for t in ("nameplate50", "nameplate51", "nameplate52", "nameplate53")}
    s50, s51, s52, s53 = (A.byTok[t] for t in ("nameplate50", "nameplate51", "nameplate52", "nameplate53"))
    chk("an elite: AVOID (the skull), shown", ic[s50] is not None and ic[s50].f.shown and ic[s50].av.shown is not False and ic[s50].q.shown is False)
    chk("a quest objective: QUEST (!) only", ic[s51] is not None and ic[s51].f.shown and ic[s51].q.shown is not False and ic[s51].av.shown is False)
    chk("a plain mob 1 below us: no icon", ic[s52] is None or not ic[s52].f.shown)
    chk("a mob 3 above us: AVOID", ic[s53] is not None and ic[s53].f.shown and ic[s53].av.shown is not False)
    pt = ic[s50].f.point
    chk("anchored above its plate (BOTTOM to the plate's TOP), on our own frame", pt[1] == "BOTTOM" and pt[3] == "TOP"
        and L.eval("function(f) return rawequal(f.parent, UIParent) end")(ic[s50].f))
    fire("NAME_PLATE_UNIT_REMOVED", "nameplate50")
    chk("the plate gone: its icon hidden at once", ic[s50].f.shown is False)
    A.diagAt = -100; A.diag()
    chk("the probe line: np state, the value, the icons count", A.lastDiag.np is not None and A.lastDiag.npv == "0" and A.lastDiag.icons >= 2)
    # ag3: the app's PICKS (contract taps v2: iact keys, an octal code)
    def acks():
        return [Lg.SENT[i] for i in range(1, len(Lg.SENT) + 1) if getattr(Lg.SENT[i], "type", None) == "aggroack"]
    Lg.WowCompanion.routeWp.active = False; tick()
    chk("taps not wanted without a route", A.tapsWanted() is False)
    # ag5 (test45: the app routed for minutes with no pin up; the layer read route=false): the app's KEEP / SET within 60 s is a route
    L.execute("WowCompanion.routeWp.routing = function() return ROUTING == true end")
    Lg.ROUTING = True; tick()
    chk("ag5: no pin, but the app routing (its KEEP): taps wanted, the probe's route true", A.tapsWanted() is True and A.routing() is True)
    Lg.ROUTING = False; tick()
    chk("ag5: the app's word gone: not wanted", A.tapsWanted() is False)
    Lg.WowCompanion.routeWp.active = True; tick()
    chk("taps wanted while the route runs", A.tapsWanted() is True)
    for t in list(A.byTok.keys()):
        fire("NAME_PLATE_UNIT_REMOVED", t)
    G.t = G.t + 11
    for k in range(60, 65):
        unit("nameplate%d" % k, d=15, lv=5)
        fire("NAME_PLATE_UNIT_ADDED", "nameplate%d" % k)
    s61 = A.byTok.nameplate61
    n0 = len(acks())
    A.tap("p1"); A.tap(str(s61 // 8)); A.tap(str(s61 % 8))
    a = acks()[-1]
    chk("a pick on a slot younger than 0.6 s: refused (slot gone)", a.op == "pull" and a.ok == 0 and a.why == "slot gone")
    tick(); tick(); tick(); tick()
    A.tap("p1"); A.tap(str(s61 // 8)); A.tap(str(s61 % 8))
    a = acks()[-1]
    tick()
    m = mobs(A.last)
    ic = A.icon[s61]
    chk("pull #1 = slot %d (3 taps: op + 2 octal digits): ok, mk p1, the crosshair with 1 on its plate" % s61,
        a.op == "pull" and a.k == 1 and a.s == s61 and a.ok == 1 and m[s61].mk == "p1" and ic and ic.f.shown and ic.pk.shown is not False and ic.pn.text == "1")
    s62 = A.byTok.nameplate62
    A.tap("p2"); A.tap(str(s62 // 8)); A.tap(str(s62 % 8)); tick()
    chk("pull #2 on another: mk p2, its crosshair with 2", mobs(A.last)[s62].mk == "p2" and A.icon[s62].pn.text == "2")
    A.tap("clr"); A.tap("p1"); tick()
    a = acks()[-1]
    chk("clr + p1: pick #1 cleared (s -1), #2 kept", a.k == 1 and a.s == -1 and mobs(A.last)[s61].mk is None and mobs(A.last)[s62].mk == "p2")
    A.tap("clr"); A.tap("clr"); tick()
    chk("clr twice: both cleared", all(mobs(A.last)[x].mk is None for x in (s61, s62)))
    A.tap("p1"); G.t = G.t + 2.1; A.tap("1")
    chk("a digit after the 2 s window: refused (no window)", acks()[-1].why == "no window")
    A.tap("p1"); A.tap("clr")
    chk("clr with another window open: cancelled", acks()[-1].op == "cancel" and A.win is None)
    s63, s64 = A.byTok.nameplate63, A.byTok.nameplate64
    mask = (1 << s63) | (1 << s64) | (1 << 23)               # slot 23 is empty: dropped
    digits = [(mask >> (3 * i)) & 7 for i in range(8)]
    A.tap("qm")
    for dgt in digits:
        A.tap(str(dgt))
    a = acks()[-1]
    tick()
    chk("qmask (qm + 8 octal digits, low first): the applied mask has the live slots only (%s)" % a.mask,
        a.op == "qmask" and int(a.mask) == ((1 << s63) | (1 << s64)) and mobs(A.last)[s63].mk == "q" and A.icon[s63].q.shown is not False)
    A.tap("p1"); A.tap(str(s64 // 8)); A.tap(str(s64 % 8)); tick()
    fire("NAME_PLATE_UNIT_REMOVED", "nameplate64"); tick()
    chk("its plate gone: the pick and the quest bit go with it", A.pick[1] is None and A.qset[s64] is None)
    Lg.WowCompanion.routeWp.active = False; tick()
    chk("the route off: every pick dropped", A.pick[1] is None and A.pick[2] is None and len(list(A.qset.items())) == 0)
    chk("no error caught in the ticker / events (%s)" % A.err, A.err is None)
    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
