"""NO PARTIAL CHARACTER KEY (Safety k6 WC.charKey / WC.realmFix; PadMap m178, ActionBar cb16, Spells s18, Radial r27 / q8, Professions p26,
TapPad pad27, Trail prd21, Hub hub74, Map pq2), off-device: the REAL add-on files under tools/wowstub.py's stubbed game, Lua 5.1 as the game's.

    python tools/test_realm_key.py

The 11-20 hunter (test build 1513): "what happened to my combat bars on my hunter? they are gone?". GetRealmName() answered "" for the whole
session: every per-character store was read and written under "Name-" (PadMap, Spells) / "Name-?" (ActionBar's castBar*, Radial), the stores
looked empty and the bars were gone; the real "Name-Realm" data was intact. Proven here:
  a WC.charKey: no key from an empty / nil / secret / "?" realm with nothing kept (never "Name-" / "Name-?"); the game's realm (remembered in
    WowCompanionDB.realmOf); with no realm from the game, the ONE realm the name already holds settings under (the remembered one, or our
    tables' keys); two realms = no key (the game's normalized realm picks); a later live realm wins;
  b the realm empty at login, a NEW character: no store is made under any partial key (the tables hold no "Korvash-" / "Korvash-?" key
    after the login, a padmap and edits), the padmap's who carries no realm (rs "none"), an edit is refused "no character";
  c THE DEVICE'S SHAPE (real "Korvash-Realm" in 22 stores, orphan "Korvash-" in 15, "Korvash-?" in 2), the realm empty at login: the key
    is the real one ("kept"), the fight layer is armed from the real fightBy (the bars come back), the orphans are folded out of both
    tables (the real key's 22 stores byte for byte as before; the one store the real key lacked takes the orphan's setting; an empty
    orphan is dropped), the fold's backup kept (WowCompanionDB.realmFixBak, the 2 newest), a second login folds nothing more;
  d the real key is NEVER overwritten: an orphan with a different value for a store the real key holds is dropped;
  e in a fight at login nothing is folded until the fight's end (out of combat only);
  f the tap-to-cast bar (ActionBar cb16): the realm empty, the real castBar's spells on the row; the "Korvash-?" orphan gone;
  g the realm back (live) with orphans left from before: folded the same at login;
  h no spied call (no trace) anywhere.
Safety k7 (the k6 review's must-fix list), proven here too:
  i A SAME-NAME CHARACTER ON ANOTHER REALM (the realm empty, no hint): its home server (UnitGUID) is not the one the hunter's realm was
    recorded with, or its class is another, or the hunter's realm has no evidence at all: no key, no edit, the hunter's stores byte for
    byte as they were, no fold;
  j the fold is SKIPPED while the name holds settings under two realms (the app's RealmFix rule): orphans left, no backup;
  k a kept realm found BEFORE PLAYER_LOGIN (WowCompanionSpellsDB not loaded yet) is asked again at PLAYER_LOGIN;
  l the fold's backup holds COPIES (an edit of the adopted store after the fold leaves the backup as it was);
  m the padmap's who carries rh (the game's realm hint present / none) for the app's diagnostic line.
Safety k8 (the k6 review, round 2), proven here too:
  n the class alone ("maybe": no server id to compare) picks a kept realm only AFTER PLAYER_LOGIN (no key before it);
  o the fold's backup keeps the 2 newest folds PER NAME (another character's folds never push this one's out).
Safety k9 and Bags bags-rk1 (the k6 review, round 3), proven here too:
  p BAGS (bags-rk1): no key at PLAYER_LOGIN, the key known later: the real bagBinds are read then (never the near-empty set saved over
    them); a bind made before is kept on top; a key that changes takes the new key's own record (the old set never copied onto it);
  q THE GUID (k9): a private server that shares one server id over realms: a same-name alt (another GUID, the same server and class) gets
    no key; the same server and another class (a record from before k9) gets none either; this character's own GUID: kept;
  r the orphans' fold needs this character's evidence: a key from the class alone ("maybe") leaves the orphans (no fold, no backup).
Needs lupa. Exit code 1 on any failure."""
import json
import os
import re
import sys

from lupa import lua51 as lupa

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import ADDONS, GAME, SRC, boot  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FL = open(os.path.join(ROOT, "tools", "test_fight_layer.py"), encoding="utf-8").read()
EXTRA = re.search(r"\nEXTRA = r'''(.*?)'''", FL, re.S).group(1)
AFTER = re.search(r"\nAFTER = r'''(.*?)'''", FL, re.S).group(1)
PARTY = open(os.path.join(ROOT, "tools", "test_party.py"), encoding="utf-8").read()
WORLD_HELPERS = re.search(r"\nWORLD_HELPERS = r'''(.*?)'''", PARTY, re.S).group(1)
AB = open(os.path.join(ADDONS, "WowCompanion", "ActionBar.lua"), encoding="utf-8").read()
CASTBAR = re.search(r'-- CASTBAR-BEGIN[^\n]*\n(.*?)-- CASTBAR-END', AB, re.S).group(1)
TEXT = {k: open(os.path.join(ADDONS, *p), encoding="utf-8").read() for k, p in {
    "safety": ("WowCompanion", "Safety.lua"), "padmap": ("WowCompanionSpells", "PadMap.lua"), "spells": ("WowCompanionSpells", "Spells.lua"),
    "radial": ("WowCompanionSpells", "Radial.lua"), "prof": ("WowCompanionProfessions", "Professions.lua"),
    "tappad": ("WowCompanion", "TapPad.lua"), "trail": ("WowCompanion", "Trail.lua"), "hub": ("WowCompanionHub", "Hub.lua"),
    "map": ("WowCompanion", "Map.lua"), "bags": ("WowCompanion", "Bags.lua")}.items()}

fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


# helpers in the stubbed game: a deterministic dump of a value, and every partial key of a name in our two tables (three levels)
HELP = r'''
function SER(v)
    if type(v) ~= "table" then return type(v) == "string" and string.format("%q", v) or tostring(v) end
    local ks = {}
    for k in pairs(v) do ks[#ks + 1] = k end
    table.sort(ks, function(a, b) return tostring(a) < tostring(b) end)
    local out = {}
    for _, k in ipairs(ks) do out[#out + 1] = "[" .. SER(k) .. "]=" .. SER(v[k]) end
    return "{" .. table.concat(out, ",") .. "}"
end
function PARTIALS(n)
    local found = {}
    local function bad(k)
        if type(k) ~= "string" or k:sub(1, #n + 1) ~= n .. "-" then return false end
        local r = k:sub(#n + 2)
        return r == "" or r == "?" or r == "_" or (#r == 1 and r:byte(1) <= 3)
    end
    local function look(t, path, d)
        for k, v in pairs(t) do
            if bad(k) then found[#found + 1] = path .. "." .. tostring(k) end
            if type(v) == "table" and d < 3 and k ~= "realmFixBak" then look(v, path .. "." .. tostring(k), d + 1) end
        end
    end
    look(WowCompanionSpellsDB, "S", 1); look(WowCompanionDB, "C", 1)
    return #found, table.concat(found, " ")
end
function REALSTORES(key)
    local out = {}
    for _, pair in ipairs({ { "S", WowCompanionSpellsDB }, { "C", WowCompanionDB } }) do
        for k, v in pairs(pair[2]) do
            if type(v) == "table" and k ~= "realmFixBak" and k ~= "realmOf" and v[key] ~= nil then out[#out + 1] = pair[1] .. "." .. k .. "=" .. SER(v[key]) end
        end
    end
    table.sort(out)
    return #out, table.concat(out, "\n")
end
'''


def world(pre='', login=True):
    """the fight-layer world (test_fight_layer.py's boot: Safety, TapPad, Book, PadMap, PadTabCtl) with [pre] before the files"""
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(GAME)
    L.execute(EXTRA)
    L.execute(HELP)
    if pre:
        L.execute(pre)
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for k in ("safety", "tappad", "book", "padmap", "padtabctl"):
        load(SRC[k], '@' + k)(k)
    L.execute(WORLD_HELPERS)
    if login:
        L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
        L.execute(AFTER)
        L.execute('WowCompanion.padMap.emit(true); runTimers(NOW + 1)')
    return L


def docs(L, typ):
    out = []
    for i in range(1, int(L.eval('#SENT')) + 1):
        try:
            d = json.loads(L.eval('SENT[%d]' % i))
        except Exception:
            continue
        if isinstance(d, dict) and d.get('type') == typ:
            out.append(d)
    return out


def padmap(L):
    L.execute('WowCompanion.padMap.emit(true)')
    return docs(L, 'padmap')[-1]['m']


def partials(L, n="Korvash"):
    c, where = L.eval('PARTIALS("%s")' % n)
    return int(c), where


def clean(L, name):
    check(L.eval('#SPY') == 0 and list(L.eval('ERRS').values()) == [], name + ": no spied call (no trace), no event error",
          (L.eval('SPY[1]'), list(L.eval('ERRS').values())[:2]))


# ================================================================ 0 the versions and the builders
check('WC.KERNEL = "k9"' in TEXT["safety"] and "function WC.charKey()" in TEXT["safety"] and "what happened to my combat bars on my hunter" in TEXT["safety"]
      and "k7 THE KEPT REALM IS THIS CHARACTER'S" in TEXT["safety"] and "-- k8 (the k6 review, round 2)" in TEXT["safety"]
      and "-- k9 (the k6 review, round 3): THE CHARACTER'S OWN GUID" in TEXT["safety"],
      "0. Safety k9 (k6's, k7's and k8's notes kept): WC.charKey, its note quotes the user")
check('local PV = "m179"' in TEXT["padmap"] and 'local CBV = "cb16"' in AB and 'local SV = "s18"' in TEXT["spells"]
      and 'local RV = "r27"' in TEXT["radial"] and 'local SRV = "q8"' in TEXT["radial"] and 'local PV = "p26"' in TEXT["prof"]
      and 'local PADV = "pad27"' in TEXT["tappad"] and 'WC.prd = { V = "prd21"' in TEXT["trail"] and 'local HV = "hub74"' in TEXT["hub"]
      and 'v = "pq2"' in TEXT["map"], "0. the tags: PadMap m179, ActionBar cb16, Spells s18, Radial r27 / q8, Professions p26, TapPad pad27, Trail prd21, Hub hub74, Map pq2")
# every per-character builder goes through WC.charKey: no file but Safety concatenates GetRealmName() into a key any more
for k, s in list(TEXT.items()) + [("actionbar", AB)]:
    if k == "safety":
        continue
    bad = re.findall(r'GetRealmName\(\)[^\n]*\)\s*or\s*"\?"|tostring\(r or ""\)|n \.\. "-" \.\. \(r or "\?"\)', s)
    check(not bad, "0. %s builds no partial key (no 'or \"?\"' / tostring(r or \"\"))" % k, bad[:1])

# ================================================================ a WC.charKey
S = boot(["safety"], pre=HELP + 'ORIG_UnitGUID = UnitGUID')
def ck(L):
    r = L.eval('(function() local k, n, r, s = WowCompanion.charKey(); return { k = k, n = n, r = r, s = s } end)()')
    return {x: r[x] for x in ("k", "n", "r", "s")}


FRESH = 'WowCompanion.ck.n = nil; WowCompanion.ck.r = nil; WowCompanion.ck.missAt = nil; WowCompanion.ck.told = nil; '
for realm, label in (('""', 'an empty realm'), ('nil', 'a nil realm'), ('"?"', 'a "?" realm'), ('"   "', 'a blank realm'), ('SECRETV', 'a secret realm')):
    S.execute('REALM = %s; ' % realm + FRESH + 'WowCompanionDB = {}; WowCompanionSpellsDB = {}')
    check(ck(S)["k"] is None, "a. %s, nothing kept: no key (never \"Korvash-\" / \"Korvash-?\")" % label, ck(S))
S.execute('REALM = "Realm"')
check(ck(S) == {"k": "Korvash-Realm", "n": "Korvash", "r": "Realm", "s": "live"}, "a. the game's realm: Korvash-Realm (live)", ck(S))
check(S.eval('SER(WowCompanionDB.realmOf)') == '{["Korvash"]={["Realm"]={["c"]="SHAMAN",["g"]="Player-1-Korvash",["s"]="1"}}}',
      "a. ... remembered per name WITH ITS EVIDENCE (k7: realmOf[name][realm] = { s = home server id, c = class }; k9: g = the GUID)",
      S.eval('SER(WowCompanionDB.realmOf)'))
S.execute('REALM = ""')
check(ck(S)["k"] == "Korvash-Realm", "a. the realm empty later this session: the session's realm stays", ck(S))
S.execute(FRESH)
check(ck(S) == {"k": "Korvash-Realm", "n": "Korvash", "r": "Realm", "s": "kept"}, "a. a new session, the realm empty: the remembered realm, its server matches (kept)", ck(S))
S.execute(FRESH + 'WowCompanionDB = {}; WowCompanionSpellsDB = { fightBy = { ["Korvash-Realm 7"] = { [1] = 1 }, ["Korvash-"] = {}, ["Korvash-?"] = {} } }')
check(ck(S)["k"] is None, "a. k7: the ONE realm our tables hold the name under, but NO evidence it is this character's: no key", ck(S))
S.execute(FRESH + 'WowCompanionDB = { realmOf = { Korvash = { ["Realm 7"] = { s = "1", c = "SHAMAN" } } } }')
check(ck(S)["k"] == "Korvash-Realm 7", "a. ... the realm recorded with this character's server: kept (orphans are no realm)", ck(S))
S.execute(FRESH + 'WowCompanionSpellsDB.faceBy = { ["Korvash-Other Realm"] = { x = 1 } }')
check(ck(S)["k"] == "Korvash-Realm 7", "a. two realms, no hint: the one whose recorded server is this character's picks (the other has none)", ck(S))
S.execute(FRESH + 'WowCompanionDB.realmOf.Korvash["Other Realm"] = { s = "1", c = "SHAMAN" }')
check(ck(S)["k"] is None, "a. two realms both recorded with this server (never a guess): no key", ck(S))
S.execute('NOW = NOW + 3; GetNormalizedRealmName = function() return "OtherRealm" end')
check(ck(S)["k"] == "Korvash-Other Realm", "a. ... the game's normalized realm (no spaces) picks one", ck(S))
check(S.eval('WowCompanion.charKeyHint()') == "present", "a. ... the hint's word: present")
S.execute('GetNormalizedRealmName = nil; ' + FRESH + 'WowCompanionDB.realmOf.Korvash["Other Realm"] = { s = "2", c = "SHAMAN" }')
check(ck(S)["k"] == "Korvash-Realm 7" and S.eval('WowCompanion.charKeyHint()') == "none",
      "a. two realms, one recorded with ANOTHER server: this character's server picks its own", ck(S))
# the server id not known now (UnitGUID nil): the class, with that realm the only one left
S.execute(FRESH + 'UnitGUID = function() return nil end; WowCompanionSpellsDB = { fightBy = { ["Korvash-Realm 7"] = {} } }; '
          'WowCompanionDB = { realmOf = { Korvash = { ["Realm 7"] = { s = "1", c = "SHAMAN" } } } }')
check(ck(S)["k"] is None, "n. k8: no server id now, BEFORE PLAYER_LOGIN: the class alone picks nothing (no key)", ck(S))
S.execute('fire("PLAYER_LOGIN"); ' + FRESH)
check(ck(S)["k"] == "Korvash-Realm 7", "a. no server id now, logged in: the recorded class matches, the one realm: kept", ck(S))
S.execute(FRESH + 'CLASS = "WARRIOR"')
check(ck(S)["k"] is None, "a. ... another class: no key", ck(S))
S.execute(FRESH + 'CLASS = "SHAMAN"; WowCompanionDB.realmOf.Korvash["Realm 8"] = { c = "SHAMAN" }')
check(ck(S)["k"] is None, "a. ... two realms with only the class to tell: no key (never a guess)", ck(S))
S.execute('UnitGUID = ORIG_UnitGUID; REALM = "Realm 7"')
check(ck(S) == {"k": "Korvash-Realm 7", "n": "Korvash", "r": "Realm 7", "s": "live"}, "a. a live realm always wins", ck(S))
S.execute('WHO = nil')
check(ck(S)["k"] is None, "a. no name: no key")
clean(S, "a")

# ================================================================ b a NEW character, the realm empty at login
B = world(pre='REALM = ""')
m = padmap(B)
who = (m.get('dpad') or {}).get('who') or {}
check(who.get('n') == 'Korvash' and 'r' not in who and who.get('rs') == 'none', "b. the padmap's who: the name, no realm, rs none", who)
ok = B.eval('WowCompanion.padMap.putFight(1, 1, 1, "command", "DSMAP")')
check(ok is not True and (ok[1] if isinstance(ok, tuple) else None) in (None, "no character"), "b. a fight-layer edit is refused (no character)", ok)
B.execute('WowCompanion.padMap.emit(true); fire("PLAYER_REGEN_DISABLED"); fire("PLAYER_REGEN_ENABLED"); runTimers(NOW + 60)')
c, where = partials(B)
check(c == 0, "b. no store under any partial key in either table after the login, padmaps and an edit", where)
check(B.eval('SER(WowCompanionSpellsDB.fightBy or {})') == '{}', "b. the fight layer's table holds no character at all")
clean(B, "b")

# ================================================================ c THE DEVICE'S SHAPE
# 22 stores of the real key (fightBy's DSMAP command on slot 1 among them: the fight layer = "the bars"); 15 "Korvash-" orphans (14 over
# stores the real key holds - some empty, some different values -, 1 with a setting where the real key has none); 2 "Korvash-?" orphans
REAL = "Korvash-Realm"
STORES = ["fightBy", "faceBy", "binds", "cmdBy", "stickBy", "attackBy", "dpad", "ccOff", "genUse", "lowPickBy", "smartOn", "smartBuffs",
          "partyBy", "petSpellBy", "macroRoom", "radialPins", "sradials", "castBar", "castBarFight", "castBarParty", "formSlotsBy", "padLayout"]
assert len(STORES) == 22
FIGHT = '{ [1] = { t = "command", key = "DSMAP", n = "Open Map", i = 1, page = 1, set = 1, pos = 1 } }'
lines = []
for i, s in enumerate(STORES):
    v = FIGHT if s == "fightBy" else ('true' if s in ("dpad", "smartOn") else '{ v = %d, note = "real %s" }' % (i + 1, s))
    lines.append('WowCompanionSpellsDB.%s = { ["%s"] = %s }' % (s, REAL, v))
REAL_LINES = list(lines) + ['WowCompanionDB.lay = { ["%s"] = { hud = { dx = 1, dy = 2, s = 100 } } }' % REAL]
DASH = STORES[:14]                  # 14 orphans over stores the real key holds
for j, s in enumerate(DASH):
    lines.append('WowCompanionSpellsDB.%s["Korvash-"] = %s' % (s, '{}' if j % 2 == 0 else '{ v = 999, note = "orphan" }'))
lines.append('WowCompanionSpellsDB.hmBy = { ["Korvash-"] = { hp = 5, t = 1 } }')       # the 15th: a store the real key lacks (a setting)
lines.append('WowCompanionSpellsDB.castBar["Korvash-?"] = { [1] = { k = "spell", id = 1 } }')     # the 2 "-?" (ActionBar cbWho)
lines.append('WowCompanionSpellsDB.castBarFight["Korvash-?"] = {}')
lines.append('WowCompanionSpellsDB.lowPickBy["Korvash-?"] = nil')
lines.append('WowCompanionDB.lay = { ["%s"] = { hud = { dx = 1, dy = 2, s = 100 } }, ["Korvash-"] = {} }' % REAL)
lines.append('WowCompanionDB.skipBy = { ["Korvash-"] = "" }')                                   # an empty-string orphan: dropped
EVID = 'WowCompanionDB.realmOf = { Korvash = { Realm = { s = "1", c = "SHAMAN" } } }'     # a live k7 session recorded it
DEVICE = 'REALM = ""\nWowCompanionSpellsDB, WowCompanionDB = {}, {}\n' + "\n".join(lines) + "\n" + EVID + "\n"
NOEVID = 'REALM = ""\nWowCompanionSpellsDB, WowCompanionDB = {}, {}\n' + "\n".join(lines) + "\n"

P = lupa.LuaRuntime(unpack_returned_tuples=True)
P.execute(GAME)
P.execute(HELP)
P.execute(DEVICE)
n0, before = P.eval('REALSTORES("%s")' % REAL)
before_c = before
c0, _ = P.eval('PARTIALS("Korvash")')
check(int(n0) == 23 and int(c0) == 19, "c. the shape: the real key in 22 stores (+ the core lay), 15 + 2 orphans (+ 1 core lay, 1 empty string)",
      (n0, c0))

D = world(pre=DEVICE)
m = padmap(D)
who = (m.get('dpad') or {}).get('who') or {}
check(who == {"n": "Korvash", "r": "Realm", "rs": "kept", "rh": "none", "c": "SHAMAN"}, "c. the realm empty at login: the padmap's who is the REAL key's (rs kept, rh none)", who)
sl = m['sets'][0]['slots'][0]
check(sl.get('kind') == 'combat' and (sl.get('c') or {}).get('key') == 'DSMAP', "c. THE BARS COME BACK: slot 1's fight layer from the real fightBy", sl)
check(D.eval('HDR("wc-ft-1")') == "/click WowCompanionPadMap", "c. ... armed on the router (it runs in the next fight)", D.eval('HDR("wc-ft-1")'))
c, where = partials(D)
check(c == 0, "c. every orphan folded out of both tables", where)
# the control: the same real data, no orphan, the game's realm: what the add-on's own code makes of the real stores at a login
REALONLY = 'REALM = ""\nWowCompanionSpellsDB, WowCompanionDB = {}, {}\n' + "\n".join(REAL_LINES) + "\n" + EVID + "\n"
CTL = world(pre=REALONLY.replace('REALM = ""', 'REALM = "Realm"'))
_, ctl = CTL.eval('REALSTORES("%s")' % REAL)
n1, after = D.eval('REALSTORES("%s")' % REAL)
cl, al = ctl.split("\n"), [x for x in after.split("\n") if not x.startswith("S.hmBy=")]
check(len(cl) == 24 and al == cl, "c. the real key's 22 stores (and the core lay) exactly as a login with no orphan leaves them: never overwritten",
      [x for x in cl if x not in al][:3])
check(all(x in al for x in before.split("\n") if x.split("=")[0] not in ("S.attackBy", "S.genUse", "S.partyBy", "S.smartBuffs")),
      "c. ... and byte for byte as stored (but the four PadMap itself rewrites at every login; formFightBy made on read)")
check(D.eval('SER(WowCompanionSpellsDB.hmBy["%s"])' % REAL) == '{["hp"]=5,["t"]=1}', "c. the one store the real key lacked took the orphan's setting",
      D.eval('SER(WowCompanionSpellsDB.hmBy)'))
check(D.eval('WowCompanionDB.skipBy["%s"]' % REAL) is None, "c. an empty orphan is dropped, not given to the real key")
bak = D.eval('WowCompanionDB.realmFixBak')
check(bak is not None and D.eval('#WowCompanionDB.realmFixBak') == 1 and D.eval('#WowCompanionDB.realmFixBak[1].items') == 19,
      "c. the fold's backup kept: one record, 19 orphans", D.eval('#WowCompanionDB.realmFixBak[1].items') if bak is not None else None)
acts = D.eval('(function() local a, d = 0, 0 for _, it in ipairs(WowCompanionDB.realmFixBak[1].items) do if it.act == "adopted" then a = a + 1 else d = d + 1 end end return a .. "/" .. d end)()')
check(acts == "1/18", "c. ... 1 adopted, 18 dropped (each with its value kept)", acts)
check(D.eval('SER(WowCompanionDB.realmFixBak[1].items)').count('"orphan"') == 7, "c. ... the dropped orphans' own values are in it")
names = D.eval('SER(WowCompanionDB.realmFixBak)')
check("Korvash-" not in names, "c. the backup holds no partial key as a key (its kind only: - / -? / -_)")
D.execute('fire("PLAYER_ENTERING_WORLD", false, true); runTimers(NOW + 30)')
check(D.eval('#WowCompanionDB.realmFixBak') == 1, "c. idempotent: the next world entry folds nothing more")
check(D.eval('SER(WowCompanionDB.realmOf)') == '{["Korvash"]={["Realm"]={["c"]="SHAMAN",["s"]="1"}}}',
      "c. a realm only kept (not the game's): the evidence as it was (nothing new remembered)", D.eval('SER(WowCompanionDB.realmOf)'))
# l the fold's backup holds COPIES: the adopted store edited after the fold, the backup's value as it was
D.execute('WowCompanionSpellsDB.hmBy["%s"].hp = 77' % REAL)
hm = D.eval('(function() for _, it in ipairs(WowCompanionDB.realmFixBak[1].items) do if it.at == "hmBy" then return SER(it.v) end end end)()')
check(hm == '{["hp"]=5,["t"]=1}', "l. the fold's backup is a COPY: the adopted store edited after, the backup's value as folded", hm)
D.execute('WowCompanion.padMap.putFight(1, 1, 2, "command", "DSCHAT")')
check(D.eval('WowCompanionSpellsDB.fightBy["%s"][2].key' % REAL) == "DSCHAT" and partials(D)[0] == 0,
      "c. a new fight-layer edit goes to the REAL key")
clean(D, "c")

# two more realm-less sessions with fresh orphans: the backup keeps the 2 newest
saved_bak = D.eval('SER(WowCompanionDB.realmFixBak)')
for i in range(2):
    E = world(pre=DEVICE + 'WowCompanionDB.realmFixBak = %s\n' % saved_bak)
    saved_bak = E.eval('SER(WowCompanionDB.realmFixBak)')
check(E.eval('#WowCompanionDB.realmFixBak') == 2, "c. three folds: the backup holds the 2 newest", E.eval('#WowCompanionDB.realmFixBak'))

# ================================================================ d the real key never overwritten
R = world(pre=EVID + '\nREALM = ""\nWowCompanionSpellsDB = { fightBy = { ["%s"] = %s, ["Korvash-"] = { [1] = { t = "command", key = "DSCHAT", n = "Chat", '
              'i = 2, page = 1, set = 1, pos = 1 }, [3] = { t = "command", key = "DSBAGS", n = "Bags", i = 3, page = 1, set = 1, pos = 3 } } } }\n' % (REAL, FIGHT))
check(R.eval('WowCompanionSpellsDB.fightBy["%s"][1].key' % REAL) == "DSMAP" and R.eval('WowCompanionSpellsDB.fightBy["%s"][3]' % REAL) is None
      and R.eval('WowCompanionSpellsDB.fightBy["Korvash-"]') is None,
      "d. an orphan fightBy over the real one: the real store kept as it is (slot 1 DSMAP, no slot 3), the orphan dropped")
clean(R, "d")

# ================================================================ e a fight at login
F = world(pre=DEVICE + 'COMBAT = true\n')
check(partials(F)[0] == 19, "e. in a fight at login: nothing folded yet", partials(F))
F.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); runTimers(NOW + 1)')
check(partials(F)[0] == 0 and F.eval('#WowCompanionDB.realmFixBak') == 1, "e. ... folded at the fight's end", partials(F))

# ================================================================ f the tap-to-cast bar (ActionBar cb16)
CB_PRE = (r'''
CD = {}
C_Spell.GetSpellCooldown = function(id) return CD[id] or { isActive = false, isOnGCD = false } end
C_Spell.IsSpellPassive = function() return false end
REALM = ""
WowCompanionSpellsDB = { castBar = { ["Korvash-Realm"] = { [1] = { k = "spell", id = 403 } }, ["Korvash-?"] = { [1] = { k = "spell", id = 331 } } } }
WowCompanionDB = { realmOf = { Korvash = { Realm = { s = "1", c = "SHAMAN" } } } }
''')
C = boot(["safety", "tappad"], pre=HELP + CB_PRE)
C.eval('function(src) local f, e = loadstring("local WC = _G.WowCompanion\\n" .. src, "@ActionBar.lua#castbar"); if not f then error(e) end; f() end')(CASTBAR)
C.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); WowCompanion.pad.layout(); runTimers(NOW + 5)')
check(C.eval('WowCompanion.pad.rows.castbar') is not None and C.eval('WowCompanion.pad.rows.castbar.cells[0].a["spell"]') == 403,
      "f. the realm empty: the cast bar's row casts the REAL castBar's spell (403), not the orphan's")
check(C.eval('WowCompanionSpellsDB.castBar["Korvash-?"]') is None, "f. the castBar \"Korvash-?\" orphan folded away")
cb = [d for d in docs(C, 'castbar')]
check(cb and cb[-1].get('v') == 'cb16', "f. the cast bar document: cb16", cb[-1].get('v') if cb else None)
clean(C, "f")

# ================================================================ g the realm back (live) with orphans from before
G = world(pre=DEVICE.replace('REALM = ""', 'REALM = "Realm"'))
who = (padmap(G).get('dpad') or {}).get('who') or {}
check(who == {"n": "Korvash", "r": "Realm", "rs": "live", "rh": "none", "c": "SHAMAN"} and partials(G)[0] == 0 and G.eval('WowCompanionDB.realmOf.Korvash.Realm.s') == "1",
      "g. the game's realm again: live, the orphans folded the same, the realm remembered", (who, partials(G)))
n2, after2 = G.eval('REALSTORES("%s")' % REAL)
check([x for x in after2.split("\n") if not x.startswith("S.hmBy=")] == cl, "g. ... the real stores as a login with no orphan leaves them")
clean(G, "g")

# ================================================================ i A SAME-NAME CHARACTER ON ANOTHER REALM (k7, the review's probe)
SAME = (r'REALM = ""' + '\n' + 'WowCompanionSpellsDB = { fightBy = { ["%s"] = %s }, attackBy = { ["%s"] = { v = 1 } }, genUse = { ["%s"] = { hunter = true } }, '
        'dpad = { ["%s"] = true }, faceBy = { ["Korvash-"] = { x = 3 } } }\n') % (REAL, FIGHT, REAL, REAL, REAL)
for label, pre in (
        ("another home server", SAME + 'WowCompanionDB = { realmOf = { Korvash = { Realm = { s = "7", c = "HUNTER" } } } }\nCLASS = "WARRIOR"\n'),
        ("the same class, another server", SAME + 'WowCompanionDB = { realmOf = { Korvash = { Realm = { s = "7", c = "SHAMAN" } } } }\n'),
        ("no server id now, another class", SAME + 'WowCompanionDB = { realmOf = { Korvash = { Realm = { s = "7", c = "HUNTER" } } } }\nCLASS = "WARRIOR"\n'
                                               'UNITS.player.guid = "Creature-0-0"\n'),
        ("the hunter's realm with no evidence at all (only our tables' keys)", SAME + 'WowCompanionDB = {}\nCLASS = "WARRIOR"\n')):
    P2 = lupa.LuaRuntime(unpack_returned_tuples=True)
    P2.execute(GAME); P2.execute(HELP); P2.execute(pre)
    before = P2.eval('REALSTORES("%s")' % REAL)[1]
    I = world(pre=pre)
    who = (padmap(I).get('dpad') or {}).get('who') or {}
    ok = I.eval('WowCompanion.padMap.putFight(1, 1, 2, "command", "DSCHAT")')
    check(I.eval('(WowCompanion.charKey())') is None and 'r' not in who and who.get('rs') == 'none',
          "i. a same-name character on another realm (%s), the realm empty: no key, the padmap's who has no realm" % label, who)
    check(ok is not True, "i. ... its fight-layer edit is refused", ok)
    I.execute('WowCompanion.padMap.emit(true); fire("PLAYER_REGEN_DISABLED"); fire("PLAYER_REGEN_ENABLED"); runTimers(NOW + 120)')
    check(I.eval('REALSTORES("%s")' % REAL)[1] == before and I.eval('WowCompanionSpellsDB.faceBy["Korvash-"].x') == 3,
          "i. ... the hunter's stores byte for byte as they were (nothing written under its key, no fold)", I.eval('REALSTORES("%s")' % REAL)[1])
    check(I.eval('WowCompanionDB.realmFixBak') is None, "i. ... no fold backup")
    clean(I, "i")

# the device's shape with NO evidence yet (no live k7 session so far): no key, nothing folded, nothing written (the bars wait for a live one)
N = world(pre=NOEVID)
who = (padmap(N).get('dpad') or {}).get('who') or {}
check(who.get('rs') == 'none' and 'r' not in who and partials(N)[0] == 19 and N.eval('WowCompanionDB.realmFixBak') is None,
      "i. the device's shape, no evidence recorded yet: no key, the 19 orphans left, no fold", (who, partials(N)[0]))
_, real_n = N.eval('REALSTORES("%s")' % REAL)
check(real_n == before_c, "i. ... the real stores byte for byte as stored", [x for x in real_n.split("\n") if x not in before_c.split("\n")][:2])
N.execute('REALM = "Realm"; fire("PLAYER_ENTERING_WORLD", false, true); runTimers(NOW + 30)')
check(N.eval('WowCompanionDB.realmOf.Korvash.Realm.s') == "1" and partials(N)[0] == 0,
      "i. ... the game's realm back (live): the evidence recorded, the orphans folded")
clean(N, "i")

# ================================================================ j the fold SKIPPED while the name holds settings under two realms
for label, pre in (("live", DEVICE.replace('REALM = ""', 'REALM = "Realm"')), ("kept", DEVICE)):
    J = world(pre=pre + 'WowCompanionSpellsDB.faceBy["Korvash-Other Realm"] = { x = 4 }\n')
    who = (padmap(J).get('dpad') or {}).get('who') or {}
    check(who.get('r') == 'Realm' and partials(J)[0] == 19 and J.eval('WowCompanionDB.realmFixBak') is None
          and J.eval('WowCompanion.realmFix.skipped') is True,
          "j. (%s) the name under two realms: the key resolves, but the fold is skipped (19 orphans left, no backup)" % label, (who, partials(J)[0]))
    check(J.eval('WowCompanionSpellsDB.faceBy["Korvash-Other Realm"].x') == 4, "j. ... the other realm's store untouched")
    clean(J, "j")

# ================================================================ k a kept realm found before PLAYER_LOGIN is asked again then
K = boot(["safety"], pre=HELP + 'REALM = ""\nWowCompanionDB = { realmOf = { Korvash = { A = { c = "SHAMAN" } } } }\nWowCompanionSpellsDB = nil\n'
         'UnitGUID = function() return nil end\n')
check(ck(K)["k"] is None and K.eval('WowCompanion.ck.r') is None,
      "n. k8: before PLAYER_LOGIN with only the class to tell (no server id): no key (a same-class alt's realm never takes the writes)", ck(K))
K.execute('WowCompanionSpellsDB = { fightBy = { ["Korvash-B"] = { [1] = 1 } } }')
K.execute('fire("PLAYER_LOGIN"); runTimers(NOW + 1)')
check(ck(K)["k"] is None, "k. at PLAYER_LOGIN both tables are seen: a second realm (B) and only the class to tell = no key (never a guess)", ck(K))
K.execute('WowCompanionSpellsDB = {}; WowCompanion.ck.missAt = nil')
check(ck(K) == {"k": "Korvash-A", "n": "Korvash", "r": "A", "s": "kept"} and K.eval('WowCompanion.ck.early') is False,
      "k. ... after login the class alone picks the one realm left, cached for the session (not early)", ck(K))
clean(K, "k")
# the server id known before PLAYER_LOGIN: kept early (it matches), then asked again at login, where the game's own hint picks B
K2 = boot(["safety"], pre=HELP + 'REALM = ""\nWowCompanionDB = { realmOf = { Korvash = { A = { s = "1", c = "SHAMAN" } } } }\nWowCompanionSpellsDB = nil\n')
check(ck(K2) == {"k": "Korvash-A", "n": "Korvash", "r": "A", "s": "kept"} and K2.eval('WowCompanion.ck.early') is True,
      "k. before PLAYER_LOGIN, the server id matches: kept A, marked early", ck(K2))
K2.execute('WowCompanionSpellsDB = { fightBy = { ["Korvash-B"] = { [1] = 1 } } }; GetNormalizedRealmName = function() return "B" end')
check(ck(K2)["k"] == "Korvash-A", "k. ... still cached until PLAYER_LOGIN", ck(K2))
K2.execute('fire("PLAYER_LOGIN"); runTimers(NOW + 1)')
check(ck(K2)["k"] == "Korvash-B", "k. ... at PLAYER_LOGIN asked again: the game's hint picks B (the early answer dropped)", ck(K2))
clean(K2, "k2")

# ================================================================ o the fold's backup: the 2 newest PER NAME
OTHER = '{ at = %d, who = "Other", items = { { at = "x", k = "-", act = "dropped", v = %d } } }'
pre_o = DEVICE + 'WowCompanionDB.realmFixBak = { %s, %s, %s, { at = 1, who = "Korvash", items = { { at = "old", k = "-", act = "dropped", v = 1 } } } }\n' % (
    OTHER % (9, 9), OTHER % (8, 8), OTHER % (7, 7))
O = world(pre=pre_o)
per = O.eval('(function() local k, o = 0, 0 for _, r in ipairs(WowCompanionDB.realmFixBak) do if r.who == "Korvash" then k = k + 1 elseif r.who == "Other" then o = o + 1 end end return k .. "/" .. o end)()')
check(per == "2/2" and O.eval('WowCompanionDB.realmFixBak[1].who') == "Korvash" and partials(O)[0] == 0,
      "o. k8: the fold's backup keeps the 2 newest PER NAME (this one's new + old fold kept; the other name's 3 trimmed to its own 2)", per)
check(O.eval('(function() for _, r in ipairs(WowCompanionDB.realmFixBak) do if r.who == "Korvash" and r.at == 1 then return r.items[1].v end end end)()') == 1,
      "o. ... this character's earlier fold (its dropped orphan's value) is still there")
clean(O, "o")

# ================================================================ q THE GUID (k9)
Q = boot(["safety"], pre=HELP + 'REALM = ""\nWowCompanionSpellsDB = { fightBy = { ["Korvash-Realm"] = { [1] = 1 } } }\n')
for label, rec, want in (
        ("another GUID, the same server and class (a shared server id)", '{ s = "1", c = "SHAMAN", g = "Player-1-Other" }', None),
        ("a record from before k9: the same server, another class", '{ s = "1", c = "HUNTER" }', None),
        ("a record from before k9: the same server and class", '{ s = "1", c = "SHAMAN" }', "Korvash-Realm"),
        ("this character's own GUID", '{ s = "1", c = "SHAMAN", g = "Player-1-Korvash" }', "Korvash-Realm"),
        ("its own GUID but another class recorded (never this one)", '{ s = "1", c = "HUNTER", g = "Player-1-Korvash" }', None)):
    Q.execute(FRESH + 'WowCompanionDB = { realmOf = { Korvash = { Realm = %s } } }' % rec)
    check(ck(Q)["k"] == want, "q. k9 %s: %s" % (label, "kept" if want else "no key"), ck(Q))
Q.execute('fire("PLAYER_LOGIN"); ' + FRESH + 'WowCompanionDB = { realmOf = { Korvash = { Realm = { s = "1", c = "SHAMAN", g = "Player-1-Other" } } } }')
check(ck(Q)["k"] is None, "q. ... after PLAYER_LOGIN too: another GUID is never this character (not even as a class-only 'maybe')", ck(Q))
Q.execute('REALM = "Realm"; ' + FRESH)
check(ck(Q)["s"] == "live" and Q.eval('WowCompanionDB.realmOf.Korvash.Realm.g') == "Player-1-Korvash",
      "q. the game's realm (live): the record takes this character's GUID (a remade character of that name and realm is the one there now)")
clean(Q, "q")
# the device's shape: the hunter's realm recorded with ANOTHER GUID (the alt's session on a shared server id): no key, nothing folded
QD = world(pre=DEVICE.replace(EVID, 'WowCompanionDB.realmOf = { Korvash = { Realm = { s = "1", c = "SHAMAN", g = "Player-1-Hunter" } } }'))
check(QD.eval('(WowCompanion.charKey())') is None and partials(QD)[0] == 19 and QD.eval('WowCompanionDB.realmFixBak') is None,
      "q. the device's shape, the realm's record holds another GUID: no key, the 19 orphans left, no fold", partials(QD))
_, real_q = QD.eval('REALSTORES("%s")' % REAL)
check(real_q == before_c, "q. ... the real stores byte for byte as stored")
clean(QD, "q2")

# ================================================================ r the fold needs this character's evidence
RR = world(pre=DEVICE.replace(EVID, 'WowCompanionDB.realmOf = { Korvash = { Realm = { c = "SHAMAN" } } }'))
check(RR.eval('(WowCompanion.charKey())') == REAL and RR.eval('WowCompanion.realmFix.skipped') == "evidence"
      and partials(RR)[0] == 19 and RR.eval('WowCompanionDB.realmFixBak') is None,
      "r. k9: the key from the class alone ('maybe', after the login): the stores read under it, but the orphans are left (no fold, no backup)",
      (RR.eval('WowCompanion.realmFix.skipped'), partials(RR)[0]))
clean(RR, "r")

# ================================================================ p BAGS (bags-rk1)
BAGS = TEXT["bags"]
blines = BAGS.split("\n")
b0 = next(i for i, l in enumerate(blines) if l.startswith("function BB.charKey()"))
b1 = next(i for i, l in enumerate(blines) if l.startswith("function BB.button("))
BCHUNK = "\n".join(blines[b0:b1])
e0 = next(i for i, l in enumerate(blines) if l.startswith('    bf:RegisterEvent("PLAYER_LOGIN")'))
e1 = next(i for i in range(e0, len(blines)) if blines[i] == "end" and blines[i - 1] == "    end)")
BEVENTS = 'local bf = CreateFrame("Frame")\n' + "\n".join(blines[e0:e1])
check("bags-rk1" in BAGS and "function BB.sync()" in BAGS and "if me ~= BB.key then BB.sync(); return end" in BAGS,
      "p. the tag: Bags bags-rk1 (BB.sync; BB.save refuses another key's record)")


def bags_world(pre):
    L = boot(["safety"], pre=HELP + pre)
    L.execute("BB = { binds = {}, buttons = {}, applied = {} }; APPLIED = 0; function BB.apply() APPLIED = APPLIED + 1; return true end\n"
              "function BB.old() return nil end; function BB.migrate() BB.migrated = true end")
    f = L.eval('function(src) local f, e = loadstring("local WC = _G.WowCompanion\\nlocal function chordRefusal(k) return nil end\\n" .. src, "@Bags.lua#bb"); '
               'if not f then error(e) end; f() end')
    f(BCHUNK + "\n" + BEVENTS)
    return L


HUNTER_BINDS = ('WowCompanionDB = { bagBinds = { ["Korvash-Realm"] = { PADBACK = 6948, PADLSTICK = 1234 } }, '
                'realmOf = { Korvash = { Realm = { s = "1", c = "SHAMAN" } } } }\n')
# no evidence readable before the realm comes: a second realm under the name (only the class to tell) = no key
NOKEY = 'REALM = ""\nUnitGUID = function() return nil end\nWowCompanionSpellsDB = { fightBy = { ["Korvash-Other"] = { x = 1 } } }\n'
# p1 the review's probe: no key at PLAYER_LOGIN, the key known later, one bind saved
PB = bags_world(NOKEY + HUNTER_BINDS)
PB.execute('fire("PLAYER_LOGIN")')
check(PB.eval('(WowCompanion.charKey())') is None and PB.eval('next(BB.binds)') is None and PB.eval('BB.key') is None,
      "p. no key at PLAYER_LOGIN: no binds read (nothing to save under)")
PB.execute('REALM = "Realm"; BB.binds.PADRSHOULDER = 999; BB.save()')
check(PB.eval('SER(WowCompanionDB.bagBinds["Korvash-Realm"])') == '{["PADBACK"]=6948,["PADLSTICK"]=1234,["PADRSHOULDER"]=999}',
      "p. THE REVIEW'S PROBE: the key known later, one bind saved: the real binds KEPT, the new one added (never {PADRSHOULDER} alone)",
      PB.eval('SER(WowCompanionDB.bagBinds["Korvash-Realm"])'))
check(PB.eval('BB.key') == "Korvash-Realm" and PB.eval('BB.binds.PADBACK') == 6948 and PB.eval('APPLIED') >= 2,
      "p. ... BB.binds is the real record now, applied")
PB.execute('BB.binds.PADBACK = nil; BB.save()')
check(PB.eval('SER(WowCompanionDB.bagBinds["Korvash-Realm"])') == '{["PADLSTICK"]=1234,["PADRSHOULDER"]=999}',
      "p. ... a clear after that saves under the same key as usual")
clean(PB, "p1")
# p2 the key appears with no save in between: the login's timers read and apply the real binds
P2 = bags_world(NOKEY + HUNTER_BINDS)
P2.execute('fire("PLAYER_LOGIN"); runTimers(NOW + 3)')
check(P2.eval('next(BB.binds)') is None, "p. the key still unknown a few seconds in: nothing read, nothing written")
P2.execute('REALM = "Realm"; runTimers(NOW + 20)')
check(P2.eval('BB.key') == "Korvash-Realm" and P2.eval('SER(BB.binds)') == '{["PADBACK"]=6948,["PADLSTICK"]=1234}'
      and P2.eval('SER(WowCompanionDB.bagBinds["Korvash-Realm"])') == '{["PADBACK"]=6948,["PADLSTICK"]=1234}',
      "p. ... the key known: the next timer reads the real binds and applies them; the stored record untouched", P2.eval('SER(BB.binds)'))
P2.execute('runTimers(NOW + 4000)')
check(P2.eval('#TIMERS') == 0, "p. ... and stops asking once the key is known", P2.eval('#TIMERS'))
clean(P2, "p2")
# p3 a key that CHANGES (a kept realm the game's own answer replaced): the new key's record as stored; the old key's never copied onto it
P3 = bags_world('REALM = ""\nWowCompanionDB = { bagBinds = { ["Korvash-A"] = { PADBACK = 1 }, ["Korvash-B"] = { PADLSTICK = 2 } }, '
                'realmOf = { Korvash = { A = { s = "1", c = "SHAMAN" } } } }\nWowCompanionSpellsDB = {}\n')
P3.execute('fire("PLAYER_LOGIN")')
check(P3.eval('BB.key') == "Korvash-A" and P3.eval('BB.binds.PADBACK') == 1, "p. a kept key at the login: its binds read")
P3.execute('REALM = "B"; BB.binds.PADRSHOULDER = 3; BB.save()')
check(P3.eval('SER(WowCompanionDB.bagBinds)') == '{["Korvash-A"]={["PADBACK"]=1},["Korvash-B"]={["PADLSTICK"]=2}}' and P3.eval('BB.key') == "Korvash-B"
      and P3.eval('SER(BB.binds)') == '{["PADLSTICK"]=2}',
      "p. ... the key changes (the game says B): B's record as stored, A's set never written onto it, A untouched", P3.eval('SER(WowCompanionDB.bagBinds)'))
clean(P3, "p3")
# p4 no key the whole session: nothing written anywhere
P4 = bags_world('REALM = ""\nWowCompanionDB = { bagBinds = { ["Korvash-Realm"] = { PADBACK = 6948 } } }\nWowCompanionSpellsDB = {}\n')
P4.execute('fire("PLAYER_LOGIN"); BB.binds.PADRSHOULDER = 999; BB.save(); runTimers(NOW + 4000)')
check(P4.eval('SER(WowCompanionDB.bagBinds)') == '{["Korvash-Realm"]={["PADBACK"]=6948}}', "p. no key all session: nothing saved, the real binds as stored")
clean(P4, "p4")

# ---------------------------------------------------------------- q. Hub's meters store (hub73 MT.sync): the key can come after PLAYER_LOGIN
METERS = ('WowCompanionDB = { meters = { ["Korvash-Realm"] = { d = "off", c = false, p = 5 } }, '
          'realmOf = { Korvash = { Realm = { s = "1", c = "SHAMAN" } } } }\n')
check("function MT.sync()" in TEXT["hub"] and "if not MT.sync() then return end" in TEXT["hub"], "q. the tag: Hub hub74 MT.sync; MT.save writes only under the key it read")
Q1 = boot(["safety", "hub"], pre=HELP + NOKEY + METERS)
Q1.execute('fire("PLAYER_LOGIN")')
check(Q1.eval('WowCompanion.meters.key') is None, "q. no key at PLAYER_LOGIN: nothing read")
Q1.execute('REALM = "Realm"; WowCompanion.meters.cell(WowCompanion.meters.C.PARTS + 3)')
check(Q1.eval('SER(WowCompanionDB.meters)') == '{["Korvash-Realm"]={["c"]=false,["d"]="off",["p"]=3}}',
      "q. THE REVIEW'S PROBE: the key known later, one PARTS press: d and c KEPT, p = 3 (never {p=3} alone)", Q1.eval('SER(WowCompanionDB.meters)'))
clean(Q1, "q1")
Q2 = boot(["safety", "hub"], pre=HELP + NOKEY + METERS)
Q2.execute('fire("PLAYER_LOGIN"); WowCompanion.meters.cell(WowCompanion.meters.C.DMG_DYN)')
check(Q2.eval('SER(WowCompanionDB.meters)') == '{["Korvash-Realm"]={["c"]=false,["d"]="off",["p"]=5}}', "q. a press with no key: nothing written")
Q2.execute('REALM = "Realm"; for _, t in ipairs(TICKERS) do t.fn() end')
check(Q2.eval('WowCompanion.meters.key') == "Korvash-Realm" and Q2.eval('WowCompanion.meters.want.dmg') == "dyn"
      and Q2.eval('WowCompanion.meters.want.parts') == 5 and Q2.eval('WowCompanion.meters.want.cdm') is False,
      "q. ... the key known (1 s look): the real record read, only this session's press laid on top")
Q2.execute('WowCompanion.meters.cell(WowCompanion.meters.C.CDM_ON)')
check(Q2.eval('SER(WowCompanionDB.meters)') == '{["Korvash-Realm"]={["c"]=true,["d"]="dyn",["p"]=5}}',
      "q. ... the next press saves the merged record", Q2.eval('SER(WowCompanionDB.meters)'))
clean(Q2, "q2")
Q3 = boot(["safety", "hub"], pre=HELP + 'REALM = ""\nWowCompanionDB = { meters = { ["Korvash-A"] = { d = "on" }, ["Korvash-B"] = { p = 2 } }, '
          'realmOf = { Korvash = { A = { s = "1", c = "SHAMAN" } } } }\nWowCompanionSpellsDB = {}\n')
Q3.execute('fire("PLAYER_LOGIN")')
check(Q3.eval('WowCompanion.meters.key') == "Korvash-A", "q. a kept key at the login: its record read")
Q3.execute('REALM = "B"; WowCompanion.meters.cell(WowCompanion.meters.C.DMG_OFF)')
check(Q3.eval('SER(WowCompanionDB.meters)') == '{["Korvash-A"]={["d"]="on"},["Korvash-B"]={["d"]="off",["p"]=2}}',
      "q. ... the key changes: B's record + this press only; A's record never written onto B, A untouched", Q3.eval('SER(WowCompanionDB.meters)'))
clean(Q3, "q3")

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
