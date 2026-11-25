"""Smart Y's "Sit by the fire" row (PadMap m190; the user, 2026-11-24: "I want to add a smart y option to sit when the player has the
nearby campfire buff"), off-device, in Lua with the game stubbed:

- the router's REAL secure pre-click snippet (PRE): a "sit" pick is a macro of the client's own sit command, on a bare Y out of combat
  with nothing targeted; mounted, swimming or channeling at the press: never the sit (Y falls through as with no pick);
- the pure blocks between PadMap.lua's FIRE-BEGIN / FIRE-END: the campfire aura by spell id (1283391 Campfire Nearby, WoW Forever's; 7353 Basic
  Campfire, 7358 Bright Campfire), by the ids' own names only when the aura's id is not readable; when the row is due (out of combat, standing, not eating /
  drinking, not mounted / on a taxi / in a vehicle / flying / swimming / moving / casting / dead); the seat tracker; the sit command;
- the list: the new "fire" kind in the migration (added once at the end, removable, kept out while the list is full), the edits (Add
  then select cell sel0 + 3), the ordered walk;
- the wiring in OV.smartApply / the events / sbDoc, and the tag.

    python tools/test_smarty_fire.py

Needs lupa. Exit code 1 on any failure."""
import os
import re
import sys

import lupa

HERE = os.path.dirname(os.path.abspath(__file__))
PADMAP = os.path.join(HERE, '..', 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons', 'WowCompanionSpells', 'PadMap.lua')
SRC = open(PADMAP, encoding='utf-8').read()
fails = 0


def check(ok, name, got=''):
    global fails
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if not ok:
        fails += 1


def block(begin, end):
    m = re.search(r'-- ' + begin + r'[^\n]*\n(.*?)-- ' + end, SRC, re.S)
    return m.group(1) if m else None


# ---------------------------------------------------------------- 0. the tag
check('local PV = "m198"' in SRC and 'm190 SIT BY THE FIRE' in SRC, "0. PadMap's tag m190 and its note")

# ---------------------------------------------------------------- 1. the snippet
L = lupa.LuaRuntime(unpack_returned_tuples=True)
L.execute('''
function newtable(...) return {...} end
function mkframe(attrs, refs)
  local f = { a = attrs or {}, refs = refs or {} }
  function f:GetAttribute(k) return self.a[k] end
  function f:SetAttribute(k, v) self.a[k] = v end
  function f:GetFrameRef(k) return self.refs[k] end
  function f:IsShown() return true end
  function f:GetID() return 0 end
  return f
end
TRUE = {}
function SecureCmdOptionParse(s) local c = s:match("^%[(.-)%]"); if TRUE[c] then return "1" end return nil end
function GetGamePadState() return { buttons = {} } end
''')
pre = re.search(r'local PRE = \[=\[(.*?)\]=\]', SRC, re.S).group(1)
run = L.execute('return function(self, button, down) ' + pre + ' end')


def press_sit(conds=(), val="/sit", typ="sit"):
    L.execute('TRUE = {}')
    for c in conds:
        L.execute('TRUE["%s"] = true' % c)
    attrs = L.table_from({"wc-n-top": 1, "wc-dest-top-1": "ForeverInspectButton", "wc-pos": 6, "wc-state": "idle",
                          "wc-smarty": val, "wc-smarty-t": typ})
    mk = L.eval('mkframe')
    btn = mk(attrs, L.table_from({"wc-hdr": mk(L.table_from({})), "wc-bar-top-1": mk(L.table_from({}))}))
    run(btn, "LeftButton", True)
    return btn.a["wc-kind"], btn.a["type"], btn.a["macrotext"], btn.a["unit"]


k = press_sit()
check(k[0] == "smarty" and k[1] == "macro" and k[2] == "/sit" and k[3] is None,
      "1a. a sit pick: Y runs the sit command as a macro (no unit, no spell)", k)
k = press_sit(val="/sentarse")
check(k[0] == "smarty" and k[2] == "/sentarse", "1b. the client's own (localized) sit command is what runs", k)
for cond in ("mounted", "swimming", "channeling"):
    k = press_sit([cond])
    check(k[0] == "native" and k[2] != "/sit", "1c. %s at the press: never the sit (Y as with no pick: Forever's Inspect)" % cond, k[0])
k = press_sit(["combat"])
check(k[0] == "native", "1d. in combat: never the sit", k[0])
k = press_sit(["@target,exists"])
check(k[0] != "smarty", "1e. with a target: never the sit (the target's radial / Inspect, Smart Y's m61 rule)", k[0])
k = press_sit(["mounted"], val="item:4540", typ="item")
check(k[0] == "smarty" and k[1] == "item", "1f. the press-time guard is the sit's only: food while mounted is unchanged", k)
check(pre.count('/sit') == 0, "1g. the snippet carries no sit text of its own (the value armed out of combat is the command)")

# ---------------------------------------------------------------- 2. the pure blocks
fire = block('FIRE-BEGIN', 'FIRE-END')
check(fire is not None, "2. the FIRE-BEGIN / FIRE-END block exists")
if fire is None:
    print('%d FAILED' % fails)
    sys.exit(1)
F = lupa.LuaRuntime(unpack_returned_tuples=True)
F.execute('OV = {}\n' + fire)
ids = list(F.eval('OV.FIRE_IDS').values())
check(ids == [1283391, 7353, 7358], "2a. by id: Campfire Nearby 1283391 (WoW Forever's camp buff, first: the row's icon), Cozy Fire 7353 (Basic Campfire, +4 Spirit) and 7358 (Bright Campfire, +8)", ids)
F.execute('''
function aura(list, names) return OV.fireAura(list, names) end
NAMES = { ["Cozy Fire"] = 7353 }
''')
au = F.eval('aura')
lt = F.eval('function(t) return t end')
def A(*rows):
    return F.execute('return { ' + ', '.join(rows) + ' }')
check(au(A('{ id = 7353, n = "Cozy Fire" }'), F.eval('NAMES')) == 7353, "2b. the Basic Campfire's aura by its id", "")
check(au(A('{ id = 1459, n = "Arcane Intellect" }', '{ id = 7358, n = "Cozy Fire" }'), F.eval('NAMES')) == 7358,
      "2c. the Bright Campfire's aura (another id, the same name) by its id", "")
check(au(A('{ id = 99999, n = "Cozy Fire" }'), F.eval('NAMES')) is None,
      "2d. a readable id that is not a campfire's: never matched by its name", "")
check(au(A('{ n = "Cozy Fire" }'), F.eval('NAMES')) == 7353,
      "2e. the id not readable (a secret): matched by the name the ids themselves give (GetSpellInfo)", "")
check(au(A('{ n = "Feu douillet" }'), F.eval('NAMES')) is None, "2f. ... and never by any other name", "")
check(au(A(), F.eval('NAMES')) is None and au(A('{ n = "Cozy Fire" }'), None) is None, "2g. no auras / no names: nothing", "")

due = F.eval('function(s) local ok, why = OV.fireDue(s); return ok, why end')
# the 1531 follow-up (the user on the Thor: "I'm near a campfire and smart y is not triggering"; the buff on their bar: "Its called
# "campfire nearby""): WoW Forever's camp buff is Campfire Nearby, spell 1283391 - by its id, and by the name its id gives when the id is
# hidden (OV.fireNames, the real function, the ids' names read through a stubbed OV.sbName)
check(1283391 in ids and 7353 in ids and 7358 in ids, "2k. Campfire Nearby (1283391, WoW Forever's camp buff) with Cozy Fire's 7353 / 7358", ids)
fn = re.search(r'\nfunction OV\.fireNames\(\)\n.*?\nend\n', SRC, re.S)
check(fn is not None, "2k. OV.fireNames is there")
F.execute('SPELLN = { [7353] = "Cozy Fire", [7358] = "Cozy Fire", [1283391] = "Campfire Nearby" }\n'
          'function OV.sbName(id) return SPELLN[id] end\nfunction TS(f) local ok, v = pcall(f); if ok then return v end end\n'
          + (fn.group(0) if fn else ''))
fdue = F.eval('function(auras) local id = OV.fireAura(auras, OV.fireNames()); local ok = OV.fireDue({ fire = id ~= nil }); return ok, id end')
ok, fid = fdue(A('{ id = 1283391, n = "Campfire Nearby" }'))
check(ok is True and fid == 1283391, "2k. the Campfire Nearby aura by its id: due", (ok, fid))
ok, fid = fdue(A('{ n = "Campfire Nearby" }'))
check(ok is True and fid == 1283391, "2k. ... its id hidden: by the name its id gives (Campfire Nearby): due", (ok, fid))
ok, fid = fdue(A('{ id = 1459, n = "Arcane Intellect" }'))
check(ok is False and fid is None, "2k. no camp buff: not due", (ok, fid))
def S(**k):
    base = dict(fire=True)
    base.update(k)
    return F.table_from(base)
ok, why = due(S())
check(ok is True, "2h. by the fire, standing still, out of combat: due", why)
ok, why = due(S(fire=False))
check(ok is False and why == "no fire", "2i. no Cozy Fire: not due", why)
for cond in ("combat", "dead", "seated", "eating", "drinking", "mounted", "taxi", "vehicle", "flying", "swimming", "moving", "casting"):
    ok, why = due(S(**{cond: True}))
    check(ok is False and why == cond, "2j. %s: not due (and said why)" % cond, why)

seat = F.eval('OV.seatNext')
check(seat(False, "sit") is True and seat(False, "eat") is True, "2k. our sit press / an eating or drinking aura: seated", "")
for ev in ("stand", "move", "jump", "cast", "combat", "mount", "dead", "world"):
    check(seat(True, ev) is False, "2l. seated, then %s: standing" % ev, "")
check(seat(True, "toggle") is False and seat(False, "toggle") is True, "2m. the game's sit / stand key: flips", "")
check(seat(True, "aura") is True and seat(False, "aura") is False, "2n. anything else: unchanged", "")

cmd = F.eval('OV.sitCmd')
G = F.eval('function(t) return t end')
check(cmd(F.table_from({"EMOTE1_TOKEN": "AGREE", "EMOTE1_CMD1": "/agree", "EMOTE2_TOKEN": "SIT", "EMOTE2_CMD1": "/sit"}), 5) == "/sit",
      "2o. the sit command from the client's emote table (the SIT token's first command)", "")
check(cmd(F.table_from({"EMOTE7_TOKEN": "SIT", "EMOTE7_CMD1": "/sentarse"}), 10) == "/sentarse", "2p. a localized client: its own word", "")
check(cmd(F.table_from({}), 10) == "/sit", "2q. no emote table: /sit", "")
check(cmd(F.table_from({"EMOTE3_TOKEN": "SIT", "EMOTE3_CMD1": "/sit\n/cast Fireball"}), 10) == "/sit",
      "2r. a command that is not one slash word: never used (/sit instead)", "")

# ---------------------------------------------------------------- 3. the list: migration, edits, walk
mg = block('SB-MIGRATE-BEGIN', 'SB-MIGRATE-END')
MG = lupa.LuaRuntime(unpack_returned_tuples=True)
MG.execute('OV = {}\n' + mg + r"""
function kinds(e) local o = {}; for _, r in ipairs(e.rows) do o[#o + 1] = r.k or ("b" .. table.concat(r.ids, "+")) end; return table.concat(o, ",") end
""")
kinds = MG.eval('kinds')
mig = MG.eval('function(e, mn) return OV.sbFire(OV.sbMigrate(e, mn), mn) end')   # as OV.sbdb runs them
check(list(MG.eval('OV.SB_KINDS').values()) == ["food", "drink", "manners", "fire"], "3a. Add's kinds: Food, Water, Manners, Sit by the fire (sel0 + 3)", "")
e = mig(MG.eval('function() return { rows = {} } end')(), False)
check(kinds(e) == "food,drink,fire", "3b. a fresh character: Food, Water, then Sit by the fire", kinds(e))
e = mig(MG.eval('function() return { rows = { { ids = { 1459 } } } } end')(), True)
check(kinds(e) == "food,drink,b1459,manners,fire", "3c. v1 -> v2 with Manners: the fire row last", kinds(e))
e = mig(MG.eval('function() return { v = 2, rows = { { ids = { 1 } }, { k = "food" }, { k = "drink" } } } end')(), False)
check(kinds(e) == "b1,food,drink,fire", "3d. an existing v2 list (1.0.40): the fire row added at its end, the player's order kept", kinds(e))
check(kinds(mig(e, False)) == "b1,food,drink,fire", "3e. again: unchanged (one fire row)", "")
plain = MG.eval('function() return OV.sbMigrate({ v = 2, rows = { { k = "food" }, { k = "fire" }, { k = "nope" } } }, false) end')()
check(kinds(plain) == "food,fire", "3e2. sbMigrate alone keeps a fire row (a kind, not malformed) and adds none (its contract unchanged)", kinds(plain))
check('OV.sbFire(OV.sbMigrate(db.smartBuffs[k], mn), mn)' in SRC, "3e3. OV.sbdb adds the fire row after the migration", "")
e = MG.eval('function() return { v = 2, fireOff = true, rows = { { k = "food" } } } end')()
check(kinds(mig(e, False)) == "food", "3f. removed by the player (fireOff): not added back", "")
e = MG.eval('function() return { v = 2, rows = { { k = "fire" }, { k = "food" }, { k = "fire" } } } end')()
check(kinds(mig(e, False)) == "fire,food", "3g. moved to the top: stays there; a second fire row dropped", "")
full = MG.eval('function() local r = {}; for i = 1, 16 do r[i] = { ids = { i } } end; return { v = 2, rows = r } end')()
check(kinds(mig(full, False)).count("fire") == 0, "3h. a full list (16 rows shown): the fire row not added", "")
vis = MG.eval('function() return table.concat(OV.sbVis({ { k = "fire" }, { k = "manners" } }, false), ",") end')
check(vis() == "1", "3i. the fire row is always shown", vis())

so_ = re.search(r'(function OV\.sbOp\(col\).*?\nend)\n', SRC, re.S).group(1)
ak = re.search(r'(function OV\.sbAddKind\(kind\).*?\nend)\n', SRC, re.S).group(1)
TR = lupa.LuaRuntime(unpack_returned_tuples=True)
TR.execute('OV = {}; WC = {}\n' + mg + r"""
OV.SB_SEL0, OV.SB_OPS, OV.SB_MAX = 8, { remove = 24, up = 25, down = 26, add = 27, addor = 28, out = 29 }, 16
ACKS = {}
function OV.sbAck(op, ok, why) ACKS[#ACKS + 1] = { op = op, ok = ok, why = why } end
function OV.smartApply() end
function emit() end
function WC.push() end
function GetTime() return 100 end
MN = false
function OV.mannersOn() return MN end
DB = { rows = {} }
function OV.sbdb() return OV.sbFire(OV.sbMigrate(DB, MN), MN) end
""" + so_ + '\n' + ak + r"""
function setRows(t, off) DB = { v = 2, rows = t, fireOff = off }; ACKS = {}; OV.sbPick = nil; OV.sbSel = nil end
function k2() local o = {}; for _, r in ipairs(DB.rows) do o[#o + 1] = r.k or ("b" .. table.concat(r.ids, "+")) end; return table.concat(o, ",") end
function taps(...) for _, c in ipairs({ ... }) do OV.sbOp(c) end; local a = ACKS[#ACKS]; return k2() .. (a and (" " .. tostring(a.ok) .. ":" .. tostring(a.why)) or "") end
""")
TR.execute('setRows({ { k = "food" }, { k = "drink" }, { k = "fire" } })')
got = TR.eval('taps(10, 24)')
check(got.startswith("food,drink ") and TR.eval('DB.fireOff == true'), "3j. the fire row removed: gone, marked so it is not added back", got)
got = TR.eval('taps(27, 11)')
check(got.startswith("food,drink,fire true") and TR.eval('DB.fireOff == nil'), "3k. Add then select cell + 3: the fire row back at the end", got)
got = TR.eval('taps(27, 11)')
check(got.endswith("false:already on the list"), "3l. Add it again: refused, said", got)
got = TR.eval('taps(10, 25)')
check(got.startswith("food,fire,drink"), "3m. moved up like any row", got)
check(TR.eval('taps(9, 28)').endswith("false:not a buff row"), "3n. Or on the fire row: refused (buff rows only)", "")

oc = block('SMARTY-ORDER-BEGIN', 'SMARTY-ORDER-END')
wk = block('SMARTY-WALK-BEGIN', 'SMARTY-WALK-END')
W = lupa.LuaRuntime(unpack_returned_tuples=True)
W.execute('OV = {}\n' + oc + '\n' + wk + r"""
F, D, S = { v = "food" }, { v = "drink" }, { v = "sit" }
B = { v = "buff", cost = 50 }
function walk(rows, due, mana) local st, k = OV.smartWalk(rows, due, mana); return st and (st.v .. ":" .. k) or false end
""")
walk = W.eval('walk')
lua = W.execute
std = 'return { { k = "food" }, { k = "drink" }, { ids = { 1 } }, { k = "fire" } }'
def wdue(**k):
    return W.execute('return { ' + ', '.join('%s = %s' % (n, v) for n, v in k.items()) + ' }')
check(walk(lua(std), wdue(fire='S'), 100) == "sit:fire", "3o. only the fire due: Y sits", "")
check(walk(lua(std), wdue(food='F', fire='S'), 100) == "food:food", "3p. food due above it: food first (eating sits too)", "")
check(walk(lua(std), wdue(buff='B', buffAt=3, fire='S'), 100) == "buff:buff", "3q. a buff due above it: the buff first", "")
check(walk(lua('return { { k = "fire" }, { k = "food" } }'), wdue(food='F', fire='S'), 100) == "sit:fire", "3r. moved to the top: it wins", "")
check(walk(lua(std), wdue(), 100) is False, "3s. nothing due (seated, no fire): nothing (Y as with no pick)", "")
check(walk(lua('return { { k = "food" } }'), wdue(fire='S'), 100) is False, "3t. the fire row removed: never sits", "")
check(walk(lua('return { { ids = { 1 } }, { k = "fire" }, { k = "food" } }'), wdue(buff='B', buffAt=1, fire='S', food='F'), 10)
      == "sit:fire", "3u. a buff short of mana gives way to food below it - and the fire row above that food goes first", "")

# ---------------------------------------------------------------- 4. the wiring
sa = SRC[SRC.index('function OV.smartApply()'):SRC.index('OV.smartF = CreateFrame("Frame")')]
check('OV.sbHas(rows, "fire")' in sa and 'due.fire' in sa and 'OV.fireStep(' in sa, "4a. smartApply: the fire row's step when the row is on the list", "")
check(sa.index('if inCombat() then OV.smartPending = true; return end') < sa.index('due.fire'),
      "4b. never armed in combat (smartApply waits for the fight's end)", "")
ev = SRC[SRC.index('OV.smartF = CreateFrame("Frame")'):SRC.index('OV.smartF:SetScript("OnEvent"')]
check("UNIT_AURA" in ev, "4c. re-evaluated on UNIT_AURA (the buff comes / goes; smartApply's own frame)", "")
ev = SRC[SRC.index('OV.fireF = CreateFrame("Frame")'):SRC.index('OV.fireF:SetScript("OnEvent"')]
check('RegisterUnitEvent(e, "player")' in ev, "4c. the casts: the player's own only (a unit event)", "")
for e_ in ("PLAYER_STARTED_MOVING", "PLAYER_STOPPED_MOVING", "UNIT_SPELLCAST_START", "UNIT_SPELLCAST_STOP",
           "UNIT_SPELLCAST_CHANNEL_START", "UNIT_SPELLCAST_CHANNEL_STOP", "UNIT_SPELLCAST_SUCCEEDED", "PLAYER_MOUNT_DISPLAY_CHANGED"):
    check(e_ in ev, "4c. re-evaluated on " + e_, "")
fs = SRC[SRC.index('function OV.fireStep('):SRC.index('-- m190 END')] if 'function OV.fireStep(' in SRC and '-- m190 END' in SRC else ''
check(fs != '' and 'OV.fireDue(' in fs and 'OV.fireAura(' in fs and 'OV.sitCmd' in fs, "4d. fireStep: the aura, the due check, the sit command", "")
for bad in ('SetBinding', 'SetCVar', 'CreateMacro', 'EditMacro', 'SaveBindings', 'DoEmote("SIT")', "DoEmote('SIT')"):
    check(bad not in fs, "4e. leave no trace / no insecure sit: no " + bad + " in the fire code", "")
check('hooksecurefunc("DoEmote"' in fs and 'hooksecurefunc("SitStandOrDescendStart"' in fs and 'hooksecurefunc("JumpOrAscendStart"' in fs,
      "4f. the seat tracker hears the game's own sit / stand / jump (post-hooks: nothing of the game's changed)", "")
check('typ = "sit"' in fs and 'k = "fire"' in fs, "4g. the step: typ sit, shown kind fire", "")
doc = SRC[SRC.index('function OV.sbDoc()'):SRC.index('-- m72: the vitals DIAGNOSTIC')]
check('fire = ' in doc and 'OV.FIRE_IDS[1]' in doc, "4h. sbDoc: the fire row's picture (the Cozy Fire spell's own icon)", "")

print('ALL PASS' if fails == 0 else '%d FAILED' % fails)
sys.exit(1 if fails else 0)
