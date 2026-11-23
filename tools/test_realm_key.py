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
The 1514 merge into movable Home (PadMap m181) and the 1515 merge (m183), proven here too:
  hs THE HOME KEYS (rbm: the DS toolbar's mirror bit, ctop: the top-screen combat bar's flag): no key = both refused ("no character"),
    nothing written under any partial key, the padmap's `ct` absent (not "off"); the key arriving late: the character's own rbm / ctop
    read then (the sides re-bound, `m` / `ct` / `d` in the next padmap, sent by itself), nothing written over them; an edit then goes
    under the whole key only; another character's bit never read.
Safety k10 THE STICKY REALM (the user, 2026-11-20: WoW Forever's realm is a ruleset pool, the reported realm can be empty or another one,
and a character's name is unique) supersedes k7-k9's evidence rules; the sections above prove k10's answers for their shapes, and:
  s THE DEVICE (test build 1514): GetRealmName "" + a hint that names another realm + ONE kept realm with settings + orphans "Name-" /
    "Name-?": the key is the kept one ("sticky"), the real stores untouched, the orphans folded only into stores the real key lacks,
    the backup kept, the GUID record written;
  t a live realm that differs from the one kept realm: the kept one wins, and never flaps later in the session (nor the next one);
  u the same GUID, the reported realm changed: its record's key, nothing renamed;
  v the GUID's server part changed, the id segment and class the same: the same key, the record moved;
  w one name under two realms: no key, the candidates published (realm + settings count); the padtab pick (third page 10 + i) is
    stored by GUID: the key, and the next login has it with no question; a "new character" pick (9); a NEW character while other
    characters' settings exist: no question, its own key;
  x a brand-new character with nothing to claim: the game's realm, else its hint, else "Name-Forever", the same next session;
  y two different characters (other GUIDs) never resolve to the same key by themselves;
  z the padmap's diagnostics: rs / rr / rh / nc / uc / gs words only (no realm, no GUID beyond the candidates' realms).
Safety k11 (the k10 review, round 1), proven here too:
  aa a realm with ZERO settings (a remembered realm, a k6 `true`, an empty-table key) is never where the settings live: the game's realm
     or hint naming it keeps the key on the real settings (sticky), the orphans fold into the real key, never pinned on the empty one;
  ab a new key prefers a realm the name is already known under (the hint after norm(), else the only one) over a spelling of our own;
  ac a same-name record of this class under another GUID never blocks the sticky key (a name is one character): no question, the
     record re-pointed; of two realms with settings, the one such a record holds;
  ad the diagnostics' counts on every path (a guid session included);
Safety k12 / PadMap m182 (the k10 review, round 2), proven here too:
  af our Spells add-on turned off for a session (its table nil, the game says it is not loaded): no "new" key is recorded (no key at
     all for a name with nothing seen; a key found in the core table is this session's only: no record, no fold); the next session
     (both tables) finds the real settings; a record from before k12 whose key holds NO settings follows the ONE realm that does (the
     orphan folded into the real key only); a player's pick (p = 1) never moves by itself; two realms with settings keep the record;
  ag the padmap's who.ad (the app may adopt this name's In-combat backup): only a fresh key nobody chose, never after the question's
     answer (a realm or "New character"), never a guid / sticky session.
  ae the login's stamp ls (a new one at every PLAYER_LOGIN): the app's Later lasts until the next login.
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
check('WC.KERNEL = "k12"' in TEXT["safety"] and "-- k11 (the k10 review, round 1)" in TEXT["safety"] and "-- k12 (the k10 review, round 2)" in TEXT["safety"] and "function WC.charKey()" in TEXT["safety"] and "what happened to my combat bars on my hunter" in TEXT["safety"]
      and "k7 THE KEPT REALM IS THIS CHARACTER'S" in TEXT["safety"] and "-- k8 (the k6 review, round 2)" in TEXT["safety"]
      and "-- k9 (the k6 review, round 3): THE CHARACTER'S OWN GUID" in TEXT["safety"] and "-- k10 THE STICKY REALM" in TEXT["safety"]
      and "Realms work different in wow forever" in TEXT["safety"] and "function WC.charKeyPick(i)" in TEXT["safety"],
      "0. Safety k12 (k6-k11's notes kept): WC.charKey, the sticky realm, the note quotes the user")
check('local BV = "b76"' in SRC["book"] and "b74 THE STICKY REALM'S QUESTION" in SRC["book"] and "P3[9] = function()" in SRC["book"],
      "0. Book b74: the third page's 9 / 10-19 (the sticky realm's question)")
check('local PV = "m185"' in TEXT["padmap"] and "m180 THE STICKY REALM" in TEXT["padmap"] and "m181 (Safety k11" in TEXT["padmap"] and "m182 (Safety k12" in TEXT["padmap"] and 'local CBV = "cb16"' in AB and 'local SV = "s18"' in TEXT["spells"]
      and 'local RV = "r27"' in TEXT["radial"] and 'local SRV = "q8"' in TEXT["radial"] and 'local PV = "p27"' in TEXT["prof"] and 'p26 NO PARTIAL CHARACTER KEY' in TEXT["prof"]
      and 'local PADV = "pad27"' in TEXT["tappad"] and 'WC.prd = { V = "prd21"' in TEXT["trail"] and 'local HV = "hub74"' in TEXT["hub"]
      and 'v = "pq2"' in TEXT["map"], "0. the tags: PadMap m182, ActionBar cb16, Spells s18, Radial r27 / q8, Professions p27 (its p26 note kept), TapPad pad27, Trail prd21, Hub hub74, Map pq2")
# every per-character builder goes through WC.charKey: no file but Safety concatenates GetRealmName() into a key any more
for k, s in list(TEXT.items()) + [("actionbar", AB)]:
    if k == "safety":
        continue
    bad = re.findall(r'GetRealmName\(\)[^\n]*\)\s*or\s*"\?"|tostring\(r or ""\)|n \.\. "-" \.\. \(r or "\?"\)', s)
    check(not bad, "0. %s builds no partial key (no 'or \"?\"' / tostring(r or \"\"))" % k, bad[:1])

# ================================================================ a WC.charKey (k10)
S = boot(["safety"], pre=HELP + 'ORIG_UnitGUID = UnitGUID')
def ck(L):
    r = L.eval('(function() local k, n, r, s = WowCompanion.charKey(); return { k = k, n = n, r = r, s = s } end)()')
    return {x: r[x] for x in ("k", "n", "r", "s")}


FRESH = ('WowCompanion.ck.n = nil; WowCompanion.ck.r = nil; WowCompanion.ck.key = nil; WowCompanion.ck.src = nil; WowCompanion.ck.missAt = nil; '
         'WowCompanion.ck.told = nil; WowCompanion.ck.pk = nil; ')
for realm, label in (('""', 'an empty realm'), ('nil', 'a nil realm'), ('"?"', 'a "?" realm'), ('"   "', 'a blank realm'), ('SECRETV', 'a secret realm')):
    S.execute('REALM = %s; ' % realm + FRESH + 'WowCompanionDB = {}; WowCompanionSpellsDB = {}')
    check(ck(S)["k"] is None, "a. %s before PLAYER_LOGIN, nothing kept: no key (never \"Korvash-\" / \"Korvash-?\")" % label, ck(S))
S.execute('REALM = "Realm"')
check(ck(S) == {"k": "Korvash-Realm", "n": "Korvash", "r": "Realm", "s": "live"} and S.eval('WowCompanion.ck.early') is True,
      "a. before PLAYER_LOGIN, nothing kept: the game's realm (live), early (asked again at the login)", ck(S))
check(S.eval('WowCompanionDB.who') is None and S.eval('WowCompanionDB.realmOf') is None, "a. ... nothing recorded before the login")
S.execute('fire("PLAYER_LOGIN")')
check(ck(S) == {"k": "Korvash-Realm", "n": "Korvash", "r": "Realm", "s": "new"}, "a. at PLAYER_LOGIN: a new character, nothing to claim: the game's realm (new)", ck(S))
check(S.eval('SER(WowCompanionDB.who)') == '{["Player-1-Korvash"]={["c"]="SHAMAN",["i"]="Korvash",["k"]="Korvash-Realm"}}',
      "a. ... recorded under this GUID (k = the key, c = the class, i = the GUID's id segment)", S.eval('SER(WowCompanionDB.who)'))
check(S.eval('SER(WowCompanionDB.realmOf)') == '{["Korvash"]={["Realm"]={["c"]="SHAMAN",["g"]="Player-1-Korvash",["s"]="1"}}}',
      "a. ... the key's realm remembered with its evidence (s = home server id, c = class, g = the GUID)", S.eval('SER(WowCompanionDB.realmOf)'))
S.execute('REALM = ""')
check(ck(S)["k"] == "Korvash-Realm", "a. the realm empty later this session: the session's key stays", ck(S))
S.execute('REALM = "Other Realm"; NOW = NOW + 60')
check(ck(S)["k"] == "Korvash-Realm", "a. another realm reported later this session: the key never flaps", ck(S))
S.execute(FRESH)
check(ck(S) == {"k": "Korvash-Realm", "n": "Korvash", "r": "Realm", "s": "guid"}, "a. a new session, another realm reported: this GUID's record (guid)", ck(S))
S.execute(FRESH + 'REALM = ""')
check(ck(S)["s"] == "guid" and ck(S)["k"] == "Korvash-Realm", "a. ... the realm empty: the same", ck(S))
S.execute(FRESH + 'WowCompanionDB = {}; WowCompanionSpellsDB = { fightBy = { ["Korvash-Realm 7"] = { [1] = 1 }, ["Korvash-"] = {}, ["Korvash-?"] = {} } }')
check(ck(S) == {"k": "Korvash-Realm 7", "n": "Korvash", "r": "Realm 7", "s": "sticky"},
      "a. k10: the ONE realm our tables hold the name under, no record at all: that one (sticky; orphans are no realm)", ck(S))
S.execute(FRESH + 'WowCompanionDB = {}; WowCompanionSpellsDB.faceBy = { ["Korvash-Other Realm"] = { x = 1 } }')
check(ck(S)["k"] is None and S.eval('#WowCompanion.charKeyInfo().pk') == 2 and S.eval('WowCompanion.charKeySource()') == "ask",
      "a. two realms, no hint, no record: no key (never a guess), the question's 2 candidates", ck(S))
S.execute('NOW = NOW + 3; GetNormalizedRealmName = function() return "OtherRealm" end')
check(ck(S) == {"k": "Korvash-Other Realm", "n": "Korvash", "r": "Other Realm", "s": "hint"}, "a. ... the game's normalized realm (no spaces) picks one (hint)", ck(S))
check(S.eval('WowCompanion.charKeyHint()') == "present", "a. ... the hint's word: present")
S.execute('GetNormalizedRealmName = nil; ' + FRESH + 'WowCompanionDB = {}; REALM = "Realm 7"')
check(ck(S)["k"] == "Korvash-Realm 7" and ck(S)["s"] == "live", "a. two realms, the game's realm names one exactly: that one (live)", ck(S))
S.execute(FRESH + 'REALM = ""; CLASS = "WARRIOR"; WowCompanionSpellsDB = { fightBy = { ["Korvash-Realm"] = { [1] = 1 } } }; '
          'WowCompanionDB = { who = { ["Player-1-Korvash"] = { k = "Korvash-Realm", c = "SHAMAN", i = "Korvash" } }, '
          'realmOf = { Korvash = { Realm = { s = "1", c = "SHAMAN", g = "Player-1-Korvash" } } } }')
check(ck(S) == {"k": "Korvash-Forever", "n": "Korvash", "r": "Forever", "s": "new"},
      "a. this GUID's record holds ANOTHER class: never that key; a key of its own (new, Forever: no realm named)", ck(S))
S.execute('CLASS = "SHAMAN"; WHO = nil')
check(ck(S)["k"] is None, "a. no name: no key")
clean(S, "a")

# ================================================================ b a NEW character, the realm empty at login
B = world(pre='REALM = ""')
m = padmap(B)
who = (m.get('dpad') or {}).get('who') or {}
check(who.get('n') == 'Korvash' and who.get('r') == 'Forever' and who.get('rs') == 'new' and who.get('rr') == 'empty' and who.get('gs') == 'new',
      "b. k10: a NEW character, the realm empty: its own whole key Korvash-Forever (rs new), never a partial one", who)
ok = B.eval('WowCompanion.padMap.putFight(1, 1, 1, "command", "DSMAP")')
check(ok is True or (isinstance(ok, tuple) and ok[0] is True), "b. ... a fight-layer edit goes under it", ok)
B.execute('WowCompanion.padMap.emit(true); fire("PLAYER_REGEN_DISABLED"); fire("PLAYER_REGEN_ENABLED"); runTimers(NOW + 60)')
c, where = partials(B)
check(c == 0, "b. no store under any partial key in either table after the login, padmaps and an edit", where)
check(B.eval('(function() local ks = {} for k in pairs(WowCompanionSpellsDB.fightBy or {}) do ks[#ks + 1] = k end return table.concat(ks, ",") end)()') == "Korvash-Forever",
      "b. ... the fight layer's table holds only Korvash-Forever")
B2 = world(pre='WowCompanionDB = ' + B.eval('SER(WowCompanionDB)') + '\nWowCompanionSpellsDB = ' + B.eval('SER(WowCompanionSpellsDB)') + '\nREALM = "Live Realm"\n')
check(B2.eval('(WowCompanion.charKey())') == "Korvash-Forever" and B2.eval('WowCompanion.charKeySource()') == "guid",
      "b. ... the next session (a realm reported now): the same key from its record (guid), never flaps", B2.eval('(WowCompanion.charKey())'))
clean(B2, "b2")
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
ls = who.pop('ls', None)
check(who == {"n": "Korvash", "r": "Realm", "rs": "sticky", "rh": "none", "c": "SHAMAN", "rr": "empty", "nc": 1, "uc": 0, "gs": "new"}
      and isinstance(ls, int) and ls > 0,
      "c. the realm empty at login: the padmap's who is the REAL key's (rs sticky, rr empty, rh none, 1 name candidate, guid new)", who)
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
check(D.eval('SER(WowCompanionDB.realmOf)') == '{["Korvash"]={["Realm"]={["c"]="SHAMAN",["g"]="Player-1-Korvash",["s"]="1"}}}'
      and D.eval('WowCompanionDB.who["Player-1-Korvash"].k') == REAL,
      "c. k10: the key's own realm remembered with this GUID, the GUID's record holds the key", D.eval('SER(WowCompanionDB.realmOf)'))
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
check({k: who.get(k) for k in ("n", "r", "rs", "rh", "c", "rr")} == {"n": "Korvash", "r": "Realm", "rs": "live", "rh": "none", "c": "SHAMAN", "rr": "reported"} and partials(G)[0] == 0 and G.eval('WowCompanionDB.realmOf.Korvash.Realm.s') == "1",
      "g. the game's realm again: live, the orphans folded the same, the realm remembered", (who, partials(G)))
n2, after2 = G.eval('REALSTORES("%s")' % REAL)
check([x for x in after2.split("\n") if not x.startswith("S.hmBy=")] == cl, "g. ... the real stores as a login with no orphan leaves them")
clean(G, "g")

# ================================================================ i A SAME-NAME CHARACTER ON ANOTHER REALM (k7's probe; k10's answers)
# k10 (the user: a whole name is unique on WoW Forever): a record of this name with ANOTHER CLASS is another character (a deleted and
# remade one): it gets a key of its own, the hunter's stores are never written, the orphans are left (they may be the other's). A record
# of this name and class (another home server) or none at all is this character: the one kept realm (sticky)
SAME = (r'REALM = ""' + '\n' + 'WowCompanionSpellsDB = { fightBy = { ["%s"] = %s }, attackBy = { ["%s"] = { v = 1 } }, genUse = { ["%s"] = { hunter = true } }, '
        'dpad = { ["%s"] = true }, faceBy = { ["Korvash-"] = { x = 3 } } }\n') % (REAL, FIGHT, REAL, REAL, REAL)
for label, pre, mine in (
        ("another home server and another class", SAME + 'WowCompanionDB = { realmOf = { Korvash = { Realm = { s = "7", c = "HUNTER" } } } }\nCLASS = "WARRIOR"\n', False),
        ("no server id now, another class", SAME + 'WowCompanionDB = { realmOf = { Korvash = { Realm = { s = "7", c = "HUNTER" } } } }\nCLASS = "WARRIOR"\n'
                                               'UNITS.player.guid = "Creature-0-0"\n', False),
        ("another GUID's record of another class", SAME + 'WowCompanionDB = { who = { ["Player-1-Hunter"] = { k = "%s", c = "HUNTER", i = "Hunter" } } }\nCLASS = "WARRIOR"\n' % REAL, False),
        ("the same class, another home server (the same name: this character)", SAME + 'WowCompanionDB = { realmOf = { Korvash = { Realm = { s = "7", c = "SHAMAN" } } } }\n', True),
        ("no evidence at all (only our tables' keys: the name is this character)", SAME + 'WowCompanionDB = {}\nCLASS = "WARRIOR"\n', True)):
    P2 = lupa.LuaRuntime(unpack_returned_tuples=True)
    P2.execute(GAME); P2.execute(HELP); P2.execute(pre)
    before = P2.eval('REALSTORES("%s")' % REAL)[1]
    I = world(pre=pre)
    who = (padmap(I).get('dpad') or {}).get('who') or {}
    ok = I.eval('WowCompanion.padMap.putFight(1, 1, 2, "command", "DSCHAT")')
    I.execute('WowCompanion.padMap.emit(true); fire("PLAYER_REGEN_DISABLED"); fire("PLAYER_REGEN_ENABLED"); runTimers(NOW + 120)')
    if mine:
        check(I.eval('(WowCompanion.charKey())') == REAL and who.get('r') == 'Realm' and who.get('rs') == 'sticky',
              "i. k10 %s, the realm empty: the kept realm (sticky)" % label, who)
        check(I.eval('WowCompanionSpellsDB.fightBy["%s"][2].key' % REAL) == "DSCHAT" and partials(I)[0] == 0,
              "i. ... its edit goes to the kept key, its orphan folded", partials(I))
    else:
        check(I.eval('(WowCompanion.charKey())') == "Korvash-Forever" and who.get('r') == 'Forever' and who.get('rs') == 'new',
              "i. k10 %s, the realm empty: a key of its own (new), never the hunter's" % label, who)
        check(I.eval('REALSTORES("%s")' % REAL)[1] == before and I.eval('WowCompanionSpellsDB.faceBy["Korvash-"].x') == 3,
              "i. ... the hunter's stores byte for byte as they were (nothing written under its key, no fold)", I.eval('REALSTORES("%s")' % REAL)[1])
        check(I.eval('WowCompanionDB.realmFixBak') is None and I.eval('WowCompanion.realmFix.skipped') == "other",
              "i. ... no fold, no fold backup (another class's record holds the name)")
    clean(I, "i")

# the device's shape with NO evidence yet (no live session recorded one): k10, the one kept realm (sticky), the orphans folded
N = world(pre=NOEVID)
who = (padmap(N).get('dpad') or {}).get('who') or {}
check(who.get('rs') == 'sticky' and who.get('r') == 'Realm' and partials(N)[0] == 0 and N.eval('#WowCompanionDB.realmFixBak') == 1,
      "i. k10: the device's shape, no evidence recorded: the kept realm (sticky), the 19 orphans folded, the backup kept", (who, partials(N)[0]))
_, real_n = N.eval('REALSTORES("%s")' % REAL)
check([x for x in real_n.split("\n") if not x.startswith("S.hmBy=")] == cl, "i. ... the real stores as a login with no orphan leaves them")
check(N.eval('WowCompanionDB.realmOf.Korvash.Realm.g') == "Player-1-Korvash" and N.eval('WowCompanionDB.who["Player-1-Korvash"].k') == REAL,
      "i. ... the kept realm remembered with this GUID, and the GUID's record")
clean(N, "i")

# ================================================================ j two realms under the name: the fold only into a RESOLVED key
for label, pre, key in (("live", DEVICE.replace('REALM = ""', 'REALM = "Realm"'), REAL), ("ask", DEVICE, None)):
    J = world(pre=pre + 'WowCompanionSpellsDB.faceBy["Korvash-Other Realm"] = { x = 4 }\n')
    who = (padmap(J).get('dpad') or {}).get('who') or {}
    if key:
        check(who.get('r') == 'Realm' and who.get('rs') == 'live' and partials(J)[0] == 0 and J.eval('#WowCompanionDB.realmFixBak') == 1,
              "j. k10 (%s) the name under two realms, the game names one: that key, the orphans folded into it" % label, (who, partials(J)[0]))
    else:
        pk = who.get('pk') or []
        check('r' not in who and who.get('rs') == 'ask' and partials(J)[0] == 19 and J.eval('WowCompanionDB.realmFixBak') is None,
              "j. k10 (%s) the name under two realms, no realm named: no key, the fold skipped (19 orphans left, no backup)" % label, (who, partials(J)[0]))
        check([c.get('r') for c in pk] == ["Other Realm", "Realm"] and pk[1].get('n', 0) > 20 and pk[0].get('n') == 1,
              "j. ... the question's candidates: each realm and how many settings it holds", pk)
    check(J.eval('WowCompanionSpellsDB.faceBy["Korvash-Other Realm"].x') == 4, "j. ... the other realm's store untouched")
    clean(J, "j")

# ================================================================ k a key found before PLAYER_LOGIN is asked again then
K = boot(["safety"], pre=HELP + 'REALM = ""\nWowCompanionDB = { realmOf = { Korvash = { A = { c = "SHAMAN" } } } }\nWowCompanionSpellsDB = nil\n'
         'UnitGUID = function() return nil end\n')
check(ck(K)["k"] is None and K.eval('WowCompanion.ck.r') is None,
      "n. before PLAYER_LOGIN with no realm from the game: no key (the saved tables may not be loaded yet)", ck(K))
K.execute('WowCompanionSpellsDB = { fightBy = { ["Korvash-B"] = { [1] = 1 } } }')
K.execute('fire("PLAYER_LOGIN"); runTimers(NOW + 1)')
check(ck(K) == {"k": "Korvash-B", "n": "Korvash", "r": "B", "s": "sticky"} and K.eval('WowCompanion.ck.early') is False,
      "k. at PLAYER_LOGIN both tables are seen: k11, the remembered realm A holds NO settings (not where they live): B, the one that does "
      "(sticky), cached for the session (not early)", ck(K))
K.execute('WowCompanionSpellsDB = {}; WowCompanionDB.who = nil; WowCompanionDB.realmOf.Korvash.B = nil; ' + FRESH)
check(ck(K) == {"k": "Korvash-A", "n": "Korvash", "r": "A", "s": "new"},
      "k. ... no settings anywhere: the one realm the name is known under (A, new), never a spelling of our own", ck(K))
clean(K, "k")
# the game's realm before PLAYER_LOGIN: early, asked again at login, where this GUID's record decides
K2 = boot(["safety"], pre=HELP + 'REALM = "A"\nWowCompanionDB = { realmOf = { Korvash = { A = { s = "1", c = "SHAMAN" } } } }\nWowCompanionSpellsDB = nil\n')
check(ck(K2) == {"k": "Korvash-A", "n": "Korvash", "r": "A", "s": "live"} and K2.eval('WowCompanion.ck.early') is True,
      "k. before PLAYER_LOGIN, the game's realm holds the name's settings: A (live), marked early", ck(K2))
K2.execute('WowCompanionSpellsDB = { fightBy = { ["Korvash-B"] = { [1] = 1 } } }; WowCompanionDB.who = { ["Player-1-Korvash"] = { k = "Korvash-B", c = "SHAMAN", i = "Korvash" } }')
check(ck(K2)["k"] == "Korvash-A", "k. ... still cached until PLAYER_LOGIN", ck(K2))
K2.execute('fire("PLAYER_LOGIN"); runTimers(NOW + 1)')
check(ck(K2)["k"] == "Korvash-B" and ck(K2)["s"] == "guid", "k. ... at PLAYER_LOGIN asked again: this GUID's record picks B (the early answer dropped)", ck(K2))
K2.execute('REALM = "A"; NOW = NOW + 60')
check(ck(K2)["k"] == "Korvash-B", "k. ... and kept: the game's A later never flaps it", ck(K2))
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

# ================================================================ q THE GUID (k9's records; k10's answers)
Q = boot(["safety"], pre=HELP + 'REALM = ""\nWowCompanionSpellsDB = { fightBy = { ["Korvash-Realm"] = { [1] = 1 } } }\nWowCompanionDB = {}\n')
Q.execute('fire("PLAYER_LOGIN")')
for label, rec, want, src in (
        ("another GUID of this class holds the realm (k11: the same name = this character under a changed GUID)", '{ s = "1", c = "SHAMAN", g = "Player-1-Other" }', "Korvash-Realm", "sticky"),
        ("a record from before k9: the same server, another class", '{ s = "1", c = "HUNTER" }', "Korvash-Forever", "new"),
        ("a record from before k9: the same server and class", '{ s = "1", c = "SHAMAN" }', "Korvash-Realm", "sticky"),
        ("this character's own GUID", '{ s = "1", c = "SHAMAN", g = "Player-1-Korvash" }', "Korvash-Realm", "sticky"),
        ("its own GUID but another class recorded (never this one)", '{ s = "1", c = "HUNTER", g = "Player-1-Korvash" }', "Korvash-Forever", "new")):
    Q.execute(FRESH + 'WowCompanionDB = { realmOf = { Korvash = { Realm = %s } } }' % rec)
    check(ck(Q)["k"] == want and Q.eval('WowCompanion.charKeySource()') == src, "q. k10 %s: %s (%s)" % (label, want or "no key", src), ck(Q))
Q.execute(FRESH + 'WowCompanionDB = { realmOf = { Korvash = { Realm = { s = "1", c = "SHAMAN", g = "Player-1-Other" } } } }; REALM = "Realm"')
check(ck(Q)["k"] == "Korvash-Realm" and ck(Q)["s"] == "live" and Q.eval('WowCompanion.charKeyInfo().gs') == "id-changed"
      and Q.eval('WowCompanion.charKeyInfo().pk') is None and Q.eval('WowCompanionDB.realmOf.Korvash.Realm.g') == "Player-1-Korvash",
      "q. ... k11, the game names that realm: that key (live, no question), guid id-changed, the realm's record re-pointed to this GUID", ck(Q))
clean(Q, "q")
# the device's shape: the hunter's realm recorded with ANOTHER GUID of this class: k11, the same name = this character: its key, folded
QD = world(pre=DEVICE.replace(EVID, 'WowCompanionDB.realmOf = { Korvash = { Realm = { s = "1", c = "SHAMAN", g = "Player-1-Hunter" } } }'))
who = (padmap(QD).get('dpad') or {}).get('who') or {}
check(QD.eval('(WowCompanion.charKey())') == REAL and partials(QD)[0] == 0 and QD.eval('#WowCompanionDB.realmFixBak') == 1
      and who.get('rs') == 'sticky' and 'pk' not in who and who.get('gs') == 'id-changed',
      "q. k11 the device's shape, the realm's record holds another GUID of this class: the kept key (sticky, no question), the orphans folded", (partials(QD), who))
_, real_q = QD.eval('REALSTORES("%s")' % REAL)
check([x for x in real_q.split("\n") if not x.startswith("S.hmBy=")] == cl, "q. ... the real stores as a login with no orphan leaves them")
clean(QD, "q2")

# ================================================================ r k10: the fold needs no evidence (a name is one character)
RR = world(pre=DEVICE.replace(EVID, 'WowCompanionDB.realmOf = { Korvash = { Realm = { c = "SHAMAN" } } }'))
check(RR.eval('(WowCompanion.charKey())') == REAL and partials(RR)[0] == 0 and RR.eval('#WowCompanionDB.realmFixBak') == 1,
      "r. k10: the key from the class alone (sticky): the orphans folded into it, the backup kept", partials(RR)[0])
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
# p3 k10: the key NEVER changes in a session: this GUID's record (A) at the login, the game's B later: A's record kept, B untouched
P3 = bags_world('REALM = ""\nWowCompanionDB = { bagBinds = { ["Korvash-A"] = { PADBACK = 1 }, ["Korvash-B"] = { PADLSTICK = 2 } }, '
                'who = { ["Player-1-Korvash"] = { k = "Korvash-A", c = "SHAMAN", i = "Korvash" } } }\nWowCompanionSpellsDB = {}\n')
P3.execute('fire("PLAYER_LOGIN")')
check(P3.eval('BB.key') == "Korvash-A" and P3.eval('BB.binds.PADBACK') == 1, "p. this GUID's key at the login: its binds read")
P3.execute('REALM = "B"; NOW = NOW + 60; BB.binds.PADRSHOULDER = 3; BB.save()')
check(P3.eval('SER(WowCompanionDB.bagBinds)') == '{["Korvash-A"]={["PADBACK"]=1,["PADRSHOULDER"]=3},["Korvash-B"]={["PADLSTICK"]=2}}' and P3.eval('BB.key') == "Korvash-A",
      "p. ... the game says B later: the key stays A (no flap), the bind saved under A, B untouched", P3.eval('SER(WowCompanionDB.bagBinds)'))
clean(P3, "p3")
# p4 no key the whole session: nothing written anywhere
P4 = bags_world('REALM = ""\nWowCompanionDB = { bagBinds = { ["Korvash-Realm"] = { PADBACK = 6948 }, ["Korvash-Other"] = { PADBACK = 1 } } }\nWowCompanionSpellsDB = {}\n')
P4.execute('fire("PLAYER_LOGIN"); BB.binds.PADRSHOULDER = 999; BB.save(); runTimers(NOW + 4000)')
check(P4.eval('SER(WowCompanionDB.bagBinds)') == '{["Korvash-Other"]={["PADBACK"]=1},["Korvash-Realm"]={["PADBACK"]=6948}}',
      "p. no key all session (the question never answered): nothing saved, the real binds as stored")
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
          'who = { ["Player-1-Korvash"] = { k = "Korvash-A", c = "SHAMAN", i = "Korvash" } } }\nWowCompanionSpellsDB = {}\n')
Q3.execute('fire("PLAYER_LOGIN")')
check(Q3.eval('WowCompanion.meters.key') == "Korvash-A", "q. this GUID's key at the login: its record read")
Q3.execute('REALM = "B"; NOW = NOW + 60; WowCompanion.meters.cell(WowCompanion.meters.C.DMG_OFF)')
check(Q3.eval('SER(WowCompanionDB.meters)') == '{["Korvash-A"]={["d"]="off"},["Korvash-B"]={["p"]=2}}',
      "q. ... the game says B later: the key stays A (no flap), the press saved under A, B untouched", Q3.eval('SER(WowCompanionDB.meters)'))
clean(Q3, "q3")

# ================================================================ s-z SAFETY k10 THE STICKY REALM
KEPT = "Korvash-CAMWOW US1 Realm 7 PvE"


def padtab(L, *cols):
    for c in cols:
        L.execute('WowCompanion.pad.rows["padtab"].handler(nil, %d); NOW = NOW + 0.1' % c)


def saved(L):
    """the two saved tables as the next session loads them"""
    return 'WowCompanionDB = %s\nWowCompanionSpellsDB = %s\n' % (L.eval('SER(WowCompanionDB)'), L.eval('SER(WowCompanionSpellsDB)'))


def keys_of(L, name):
    return L.eval('(function() local t = WowCompanion.realmsOf("%s"); return table.concat(t, "|") end)()' % name)


# s THE DEVICE (test build 1514): GetRealmName "", the game's hint names ANOTHER realm, ONE kept realm with settings, no realmOf record,
# the orphans "Korvash-" / "Korvash-?" (an empty one over a real store, one with another value over a real store, one with a setting
# where the real key holds only an empty table, one where the real key has nothing); other characters' settings under both pools
DEV = ('REALM = ""\nGetNormalizedRealmName = function() return "CAMWOWUS1Realm9PvE" end\n'
       'WowCompanionDB = { lay = { ["%(k)s"] = { hud = { dx = 1 } }, ["Brenna-Classic Beta PvE"] = { hud = { dx = 9 } } } }\n'
       'WowCompanionSpellsDB = {\n'
       '  fightBy = { ["%(k)s"] = %(f)s, ["Korvash-"] = {}, ["Tolvar-CAMWOW US1 Realm 7 PvE"] = { [1] = { t = "spell", id = 5 } } },\n'
       '  faceBy = { ["%(k)s"] = { a = 1 }, ["Korvash-"] = { a = 999 } },\n'
       '  castBar = { ["%(k)s"] = {}, ["Korvash-?"] = { [1] = { k = "spell", id = 331 } } },\n'
       '  hmBy = { ["Korvash-"] = { hp = 5 } },\n'
       '  smartOn = { ["%(k)s"] = true },\n'
       '}\n') % {"k": KEPT, "f": FIGHT}
SD = world(pre=DEV)
who = (padmap(SD).get('dpad') or {}).get('who') or {}
check(SD.eval('(WowCompanion.charKey())') == KEPT and who.get('r') == "CAMWOW US1 Realm 7 PvE" and who.get('rs') == 'sticky'
      and who.get('rr') == 'empty' and who.get('rh') == 'present' and who.get('nc') == 1 and 'pk' not in who,
      "s. THE DEVICE: realm empty, a hint naming another realm, one kept realm: its key (sticky), no question", who)
sl = padmap(SD)['sets'][0]['slots'][0]
check(sl.get('kind') == 'combat' and (sl.get('c') or {}).get('key') == 'DSMAP', "s. ... THE BARS COME BACK: slot 1's fight layer from the kept fightBy", sl)
check(SD.eval('SER(WowCompanionSpellsDB.faceBy)') == '{["%s"]={["a"]=1}}' % KEPT and SD.eval('WowCompanionSpellsDB.fightBy["%s"][1].key' % KEPT) == "DSMAP",
      "s. ... the real stores untouched (an orphan with another value dropped, never over the real one)", SD.eval('SER(WowCompanionSpellsDB.faceBy)'))
check(SD.eval('SER(WowCompanionSpellsDB.castBar["%s"])' % KEPT) == '{[1]={["id"]=331,["k"]="spell"}}'
      and SD.eval('SER(WowCompanionSpellsDB.hmBy["%s"])' % KEPT) == '{["hp"]=5}',
      "s. ... an orphan filled only stores the real key lacks or holds empty (castBar's empty table, hmBy none)", SD.eval('SER(WowCompanionSpellsDB.castBar)'))
check(partials(SD)[0] == 0 and SD.eval('#WowCompanionDB.realmFixBak') == 1 and SD.eval('#WowCompanionDB.realmFixBak[1].items') == 4,
      "s. ... every orphan out, all four kept in the fold's backup", partials(SD))
check(SD.eval('WowCompanionSpellsDB.fightBy["Tolvar-CAMWOW US1 Realm 7 PvE"][1].id') == 5 and SD.eval('WowCompanionDB.lay["Brenna-Classic Beta PvE"].hud.dx') == 9,
      "s. ... the other characters' settings untouched")
check(SD.eval('SER(WowCompanionDB.who)') == '{["Player-1-Korvash"]={["c"]="SHAMAN",["i"]="Korvash",["k"]="%s"}}' % KEPT,
      "s. ... the GUID's record: the kept key (nothing moved or renamed)", SD.eval('SER(WowCompanionDB.who)'))
check(keys_of(SD, "Korvash") == "CAMWOW US1 Realm 7 PvE", "s. ... the name still holds settings under the one realm only", keys_of(SD, "Korvash"))
SD.execute('WowCompanion.padMap.putFight(1, 1, 2, "command", "DSCHAT")')
check(SD.eval('WowCompanionSpellsDB.fightBy["%s"][2].key' % KEPT) == "DSCHAT" and partials(SD)[0] == 0, "s. ... a new edit goes to the kept key")
clean(SD, "s")

# t a LIVE realm that differs from the one kept realm: the kept one wins, never flaps (this session or the next)
TL = world(pre=DEV.replace('REALM = ""', 'REALM = "CAMWOW US1 Realm 9 PvE"'))
who = (padmap(TL).get('dpad') or {}).get('who') or {}
check(TL.eval('(WowCompanion.charKey())') == KEPT and who.get('rs') == 'sticky' and who.get('rr') == 'reported',
      "t. a live realm other than the kept one: the kept key (sticky)", who)
TL.execute('REALM = "CAMWOW US1 Realm 3 PvE"; NOW = NOW + 120; fire("PLAYER_ENTERING_WORLD", false, true); runTimers(NOW + 30)')
check(TL.eval('(WowCompanion.charKey())') == KEPT, "t. ... the game reports a third realm later: the key never flaps")
check(keys_of(TL, "Korvash") == "CAMWOW US1 Realm 7 PvE" and TL.eval('WowCompanionDB.realmOf.Korvash["CAMWOW US1 Realm 9 PvE"]') is None,
      "t. ... a reported realm the settings do not live under is never remembered (no second realm for the name)", keys_of(TL, "Korvash"))
clean(TL, "t")

# u the same GUID logs in, the reported realm changed: its record's key, nothing renamed
U = world(pre=saved(TL) + 'REALM = "CAMWOW US2 Realm 1 PvP"\n')
who = (padmap(U).get('dpad') or {}).get('who') or {}
check(U.eval('(WowCompanion.charKey())') == KEPT and who.get('rs') == 'guid' and who.get('gs') == 'same',
      "u. the next session, the game reports another realm: this GUID's record (guid, gs same)", who)
check(keys_of(U, "Korvash") == "CAMWOW US1 Realm 7 PvE" and U.eval('WowCompanionSpellsDB.fightBy["%s"][1].key' % KEPT) == "DSMAP",
      "u. ... nothing renamed: the settings where they were, no key under the new realm")
clean(U, "u")

# v the GUID's server part changes, the id segment and class the same: the same key, the record moved to the new GUID
V = world(pre=saved(TL) + 'REALM = ""\nUNITS.player.guid = "Player-2-Korvash"\n')
who = (padmap(V).get('dpad') or {}).get('who') or {}
check(V.eval('(WowCompanion.charKey())') == KEPT and who.get('rs') == 'guid-id' and who.get('gs') == 'server-changed',
      "v. the GUID's server part changed (same id, same class): the same key (guid-id, gs server-changed)", who)
check(V.eval('WowCompanionDB.who["Player-2-Korvash"].k') == KEPT and V.eval('WowCompanionDB.who["Player-1-Korvash"]') is None,
      "v. ... the record moved to the new GUID")
clean(V, "v")
V2 = world(pre=saved(TL) + 'REALM = ""\nUNITS.player.guid = "Player-2-Korvash"\nCLASS = "WARRIOR"\n')
check(V2.eval('(WowCompanion.charKey())') != KEPT, "v. ... the same id but ANOTHER class: never that key", V2.eval('(WowCompanion.charKey())'))
clean(V2, "v2")

# w ONE NAME UNDER TWO REALMS: no key, the candidates; the pick by the padtab's third page, stored by GUID
TWO = ('REALM = ""\nWowCompanionDB = {}\nWowCompanionSpellsDB = { fightBy = { ["%s"] = %s, ["Korvash-Classic Beta PvE"] = { [1] = { t = "spell", id = 7 } } }, '
       'faceBy = { ["%s"] = { a = 1, b = 2 } }, ["Brenna-Classic Beta PvE"] = { x = 1 } }\n') % (KEPT, FIGHT, KEPT)
W = world(pre=TWO)
who = (padmap(W).get('dpad') or {}).get('who') or {}
pk = who.get('pk') or []
check(W.eval('(WowCompanion.charKey())') is None and who.get('rs') == 'ask' and 'r' not in who
      and [(c.get('r'), c.get('n')) for c in pk] == [("CAMWOW US1 Realm 7 PvE", 9), ("Classic Beta PvE", 2)],
      "w. one name under two realms, nothing names one: no key, the question's candidates (realm, settings)", who)
ok = W.eval('WowCompanion.padMap.putFight(1, 1, 2, "command", "DSCHAT")')
check(ok is not True and W.eval('WowCompanionSpellsDB.fightBy["%s"][2]' % KEPT) is None, "w. ... meanwhile nothing is written (no key)", ok)
W.execute('COMBAT = true')
padtab(W, 39, 38, 37, 10)
check(W.eval('(WowCompanion.charKey())') is None, "w. ... a pick in a fight is refused (asked again after it)")
W.execute('COMBAT = false')
padtab(W, 39, 38, 37, 10)
who = (padmap(W).get('dpad') or {}).get('who') or {}
check(W.eval('(WowCompanion.charKey())') == KEPT and who.get('rs') == 'picked' and 'pk' not in who
      and W.eval('WowCompanionDB.who["Player-1-Korvash"].k') == KEPT,
      "w. ... the pick (padtab 39, 38, 37, 10 = the first): its key (picked), stored as this GUID's record", who)
W.execute('WowCompanion.padMap.putFight(1, 1, 2, "command", "DSCHAT")')
check(W.eval('WowCompanionSpellsDB.fightBy["%s"][2].key' % KEPT) == "DSCHAT" and W.eval('WowCompanionSpellsDB.fightBy["Korvash-Classic Beta PvE"][1].id') == 7,
      "w. ... edits go to the picked key; the other realm's settings untouched")
clean(W, "w")
W2 = world(pre=saved(W) + 'REALM = "Classic Beta PvE"\n')
who = (padmap(W2).get('dpad') or {}).get('who') or {}
check(W2.eval('(WowCompanion.charKey())') == KEPT and who.get('rs') == 'guid' and 'pk' not in who,
      "w. ... the next login: the picked key from the record, no question (even with the other realm reported)", who)
clean(W2, "w2")
# "a new character" (code 9): its own fresh key, never one of the candidates
WN = world(pre=TWO)
padtab(WN, 39, 38, 37, 9)
check(WN.eval('(WowCompanion.charKey())') == "Korvash-Forever" and WN.eval('WowCompanion.charKeySource()') == "new"
      and WN.eval('WowCompanionDB.who["Player-1-Korvash"].k') == "Korvash-Forever",
      "w. ... 'a new character' (padtab 39, 38, 37, 9): a fresh key of its own, recorded", WN.eval('(WowCompanion.charKey())'))
clean(WN, "wn")
# a lost pick code (no question pending) does nothing
WX = world(pre=saved(W2))
padtab(WX, 39, 38, 37, 11)
check(WX.eval('(WowCompanion.charKey())') == KEPT, "w. ... a pick code with no question pending changes nothing")
clean(WX, "wx")
# a NEW character while other characters' settings exist: no question, its own key
WB = world(pre=TWO + 'WHO = "Saelis"\n')
who = (padmap(WB).get('dpad') or {}).get('who') or {}
check(WB.eval('(WowCompanion.charKey())') == "Saelis-Forever" and who.get('rs') == 'new' and 'pk' not in who,
      "w. a NEW character while other characters' settings exist: never asked, its own key (new)", who)
clean(WB, "wb")

# x a brand-new character: the game's realm, else its hint, else "Name-Forever"; the same next session
for label, pre, want in (("the game's realm", 'REALM = "Pool A"\n', "Korvash-Pool A"),
                         ("the hint", 'REALM = ""\nGetNormalizedRealmName = function() return "PoolB" end\n', "Korvash-PoolB"),
                         ("neither", 'REALM = ""\n', "Korvash-Forever")):
    X = world(pre='WowCompanionDB = {}\nWowCompanionSpellsDB = {}\n' + pre)
    check(X.eval('(WowCompanion.charKey())') == want and X.eval('WowCompanion.charKeySource()') == "new",
          "x. a brand-new character, %s: %s (new)" % (label, want), X.eval('(WowCompanion.charKey())'))
    X.execute('WowCompanion.padMap.putFight(1, 1, 2, "command", "DSCHAT")')
    X2 = world(pre=saved(X) + 'REALM = "Pool C"\nGetNormalizedRealmName = function() return "PoolD" end\n')
    check(X2.eval('(WowCompanion.charKey())') == want and X2.eval('WowCompanion.charKeySource()') == "guid",
          "x. ... the next session (other realms reported): the same key (guid)", X2.eval('(WowCompanion.charKey())'))
    clean(X, "x"); clean(X2, "x2")

# y two different characters never resolve to the same key: another NAME never (a key is the name's), and a same-name record of ANOTHER
# class is another character (a deleted and remade one); k11 (the review round 1, the user's ruling "no one can have those names"): a
# same-name record of THIS class under another GUID is this character (its GUID changed): its key, the record re-pointed, no question
HUNTER = ('REALM = "Realm"\nWowCompanionSpellsDB = { fightBy = { ["Korvash-Realm"] = %s } }\n'
          'WowCompanionDB = { who = { ["Player-1-Hunter"] = { k = "Korvash-Realm", c = "%%s", i = "Hunter" } } }\n') % FIGHT
for cls, label in (("WARRIOR", "another class"), ("SHAMAN", "the same class")):
    Y = world(pre=HUNTER % cls)
    k = Y.eval('(WowCompanion.charKey())')
    if cls == "SHAMAN":
        check(k == "Korvash-Realm" and Y.eval('WowCompanion.charKeySource()') == "live" and Y.eval('WowCompanion.charKeyInfo().gs') == "id-changed"
              and Y.eval('WowCompanionDB.who["Player-1-Hunter"]') is None and Y.eval('WowCompanionDB.who["Player-1-Korvash"].k') == "Korvash-Realm",
              "y. k11 another GUID of the same class holds Korvash-Realm: the same name = this character: that key, the record re-pointed", k)
    else:
        check(k == "Korvash-Forever" and Y.eval('WowCompanionSpellsDB.fightBy["Korvash-Realm"][1].key') == "DSMAP"
              and Y.eval('WowCompanionSpellsDB.fightBy["Korvash-Realm"][2]') is None and Y.eval('WowCompanionDB.who["Player-1-Hunter"].k') == "Korvash-Realm",
              "y. another GUID of ANOTHER class holds Korvash-Realm: never that key (a key of its own), its record kept", k)
    clean(Y, "y")
YO = world(pre=HUNTER % "SHAMAN" + 'WHO = "Saelis"\n')
check(YO.eval('(WowCompanion.charKey())') == "Saelis-Realm" and YO.eval('WowCompanionDB.who["Player-1-Hunter"].k') == "Korvash-Realm",
      "y. another NAME on the same realm: its own key, the other's record untouched", YO.eval('(WowCompanion.charKey())'))
clean(YO, "yo")

# z the padmap's diagnostics: words and counts only; no GUID ever leaves the game
Z = world(pre=DEV)
sent = "\n".join(x for x in (Z.eval('SENT[%d]' % i) for i in range(1, int(Z.eval('#SENT')) + 1)) if '"type":"svkeep"' not in x)
who = (padmap(Z).get('dpad') or {}).get('who') or {}
check(set(who) <= {"n", "r", "rs", "rh", "c", "rr", "nc", "uc", "gs", "pk", "ls"} and isinstance(who.get('nc'), int) and isinstance(who.get('uc'), int)
      and isinstance(who.get('ls'), int)
      and who.get('gs') in ("same", "server-changed", "id-changed", "new", "unknown"), "z. the who's fields: words and counts", who)
check("Player-1-Korvash" not in sent, "z. no GUID in any document sent (but svkeep: the account's own save file, kept by the app)")
clean(Z, "z")

# ================================================================ k11 THE k10 REVIEW, ROUND 1
# aa a realm with ZERO settings (a k6-k9 realmOf entry: a record of this GUID, a k6 `true`; an empty-table key) is not where the settings
# live: the game's realm or hint naming it never moves the key off the real settings, never pins it, never takes the orphans
R7 = "Korvash-Realm 7"
ZERO = {
    "a k9 record of this GUID": 'WowCompanionDB = { realmOf = { Korvash = { Other = { s = "1", c = "SHAMAN", g = "Player-1-Korvash" } } } }\n',
    "a k6 true entry": 'WowCompanionDB = { realmOf = { Korvash = { Other = true } } }\n',
    "an empty-table key": 'WowCompanionDB = { lay = { ["Korvash-Other"] = {} } }\n',
}
AA_SP = ('WowCompanionSpellsDB = { fightBy = { ["%s"] = %s }, faceBy = { ["%s"] = { a = 1, b = 2, c = 3 } }, attackBy = { ["Korvash-?"] = { v = 5 } }, '
         'hmBy = { ["Korvash-"] = { hp = 5 } } }\n') % (R7, FIGHT, R7)
for label, core in ZERO.items():
    for how, realm in (("the game's realm names it", 'REALM = "Other"\n'),
                       ("the realm empty, the hint names it", 'REALM = ""\nGetNormalizedRealmName = function() return "Other" end\n')):
        A1 = world(pre=AA_SP + core + realm)
        who = (padmap(A1).get('dpad') or {}).get('who') or {}
        check(A1.eval('(WowCompanion.charKey())') == R7 and who.get('rs') == 'sticky' and who.get('nc') == 1 and 'pk' not in who
              and A1.eval('WowCompanionDB.who["Player-1-Korvash"].k') == R7,
              "aa. k11 %s with no settings, %s: the key stays where the settings live (sticky), recorded so" % (label, how), who)
        check(A1.eval('WowCompanionSpellsDB.attackBy["%s"].v' % R7) == 5 and A1.eval('WowCompanionSpellsDB.attackBy["Korvash-Other"]') is None and A1.eval('SER(WowCompanionSpellsDB.hmBy)') == '{["%s"]={["hp"]=5}}' % R7
              and partials(A1)[0] == 0 and A1.eval('#WowCompanionDB.realmFixBak') == 1,
              "aa. ... the orphans folded into the REAL key (never into the empty one), the backup kept", A1.eval('SER(WowCompanionSpellsDB.attackBy)'))
        check(A1.eval('WowCompanionSpellsDB.fightBy["%s"][1].key' % R7) == "DSMAP" and A1.eval('SER(WowCompanionSpellsDB.faceBy["%s"])' % R7) == '{["a"]=1,["b"]=2,["c"]=3}'
              and A1.eval('(function() for _, db in ipairs({ WowCompanionSpellsDB }) do for _, t in pairs(db) do if type(t) == "table" and t["Korvash-Other"] ~= nil then return true end end end return false end)()') is False,
              "aa. ... the real stores as they were, nothing written under Korvash-Other")
        A2 = world(pre=saved(A1) + realm)
        check(A2.eval('(WowCompanion.charKey())') == R7 and A2.eval('WowCompanion.charKeySource()') == "guid",
              "aa. ... the next session (the same report): the real key from the record (guid)", A2.eval('(WowCompanion.charKey())'))
        clean(A1, "aa"); clean(A2, "aa2")
# two realms with settings and an empty one the game names: asked, the empty one never a candidate
A3 = world(pre=AA_SP + 'WowCompanionSpellsDB.faceBy["Korvash-Classic Beta PvE"] = { a = 9 }\n' + ZERO["a k6 true entry"] + 'REALM = "Other"\n')
who = (padmap(A3).get('dpad') or {}).get('who') or {}
check(A3.eval('(WowCompanion.charKey())') is None and who.get('rs') == 'ask' and [c.get('r') for c in who.get('pk') or []] == ["Classic Beta PvE", "Realm 7"]
      and who.get('nc') == 2, "aa. two realms hold settings, the game names an EMPTY third: asked, the empty one never a candidate", who)
clean(A3, "aa3")

# ab a NEW key prefers a realm the name is already known under (no settings there) over a spelling of our own: the hint after norm()
for label, pre, want in (("the hint names it after norm()", 'GetNormalizedRealmName = function() return "Realm7" end\n', R7),
                         ("no hint, the only one", '', R7)):
    AB = world(pre='REALM = ""\nWowCompanionDB = { realmOf = { Korvash = { ["Realm 7"] = true } } }\nWowCompanionSpellsDB = {}\n' + pre)
    check(AB.eval('(WowCompanion.charKey())') == want and AB.eval('WowCompanion.charKeySource()') == "new",
          "ab. k11 nothing holds settings, the name known under Realm 7, %s: %s (new), never Korvash-Realm7" % (label, want), AB.eval('(WowCompanion.charKey())'))
    clean(AB, "ab")

# ac THE REVIEW'S PROBE: session 1 sticky; session 2 with a WHOLLY different GUID (same name and class): the same key, no question
AC = world(pre=saved(SD) + 'REALM = ""\nGetNormalizedRealmName = function() return "CAMWOWUS1Realm9PvE" end\nUNITS.player.guid = "Player-77-Zed99"\n')
who = (padmap(AC).get('dpad') or {}).get('who') or {}
check(AC.eval('(WowCompanion.charKey())') == KEPT and who.get('rs') == 'sticky' and 'pk' not in who and who.get('gs') == 'id-changed',
      "ac. k11 another GUID entirely (same name, same class): the kept key (sticky), no question, gs id-changed", who)
check(AC.eval('WowCompanionDB.who["Player-77-Zed99"].k') == KEPT and AC.eval('WowCompanionDB.who["Player-1-Korvash"]') is None,
      "ac. ... the record re-pointed to the new GUID (the old one dropped)", AC.eval('SER(WowCompanionDB.who)'))
AC2 = world(pre=saved(AC) + 'REALM = ""\nUNITS.player.guid = "Player-77-Zed99"\n')
check(AC2.eval('(WowCompanion.charKey())') == KEPT and AC2.eval('WowCompanion.charKeySource()') == "guid", "ac. ... the next login: guid")
ACL = world(pre=saved(SD) + 'REALM = "CAMWOW US1 Realm 7 PvE"\nUNITS.player.guid = "Player-88-Other11"\n')
check(ACL.eval('(WowCompanion.charKey())') == KEPT and ACL.eval('WowCompanion.charKeySource()') == "live" and ACL.eval('WowCompanion.charKeyInfo().pk') is None,
      "ac. ... the game names the kept realm exactly, another GUID: live, no question")
clean(AC, "ac"); clean(AC2, "ac2"); clean(ACL, "acl")
# two realms with settings, one held by a same-name record of this class under another GUID: that one, no question
AD = world(pre=TWO.replace('WowCompanionDB = {}', 'WowCompanionDB = { who = { ["Player-5-Old"] = { k = "Korvash-Classic Beta PvE", c = "SHAMAN", i = "Old" } } }'))
check(AD.eval('(WowCompanion.charKey())') == "Korvash-Classic Beta PvE" and AD.eval('WowCompanion.charKeySource()') == "sticky"
      and AD.eval('WowCompanionDB.who["Player-5-Old"]') is None,
      "ac. two realms hold settings, a same-name record of this class (another GUID) holds one: that one, no question", AD.eval('(WowCompanion.charKey())'))
clean(AD, "ad")

# ad the diagnostics' counts on EVERY path: the guid session still counts the realms the name holds settings under
U2 = world(pre=saved(SD) + 'REALM = ""\n')
who = (padmap(U2).get('dpad') or {}).get('who') or {}
check(who.get('rs') == 'guid' and who.get('nc') == 1 and who.get('uc') == 0 and who.get('gs') == 'same',
      "ad. k11 a guid session: name candidates 1 (never 0), guid same", who)
clean(U2, "ad")

# ae the login's stamp: one per PLAYER_LOGIN (a /reload or a relog is a new one); a number only
L1 = world(pre=DEV)
L2 = world(pre=saved(L1) + 'NOW = NOW + 45\n')
s1 = ((padmap(L1).get('dpad') or {}).get('who') or {}).get('ls')
s2 = ((padmap(L2).get('dpad') or {}).get('who') or {}).get('ls')
check(isinstance(s1, int) and isinstance(s2, int) and s1 > 0 and s2 > 0 and s1 != s2,
      "ae. k11 the padmap's who.ls: the login's stamp, a new one at the next PLAYER_LOGIN (the app's Later lasts until it changes)", (s1, s2))
check(L2.eval('WowCompanion.ck.ls') == s2 and L2.eval('WowCompanion.charKeyInfo().ls') == s2, "ae. ... the add-on's own stamp of this login")
clean(L1, "ae"); clean(L2, "ae2")

# ================================================================ k12 THE k10 REVIEW, ROUND 2
# af THE REVIEW'S PROBE: session 1 with our Spells add-on turned off (the game's blocked-action pop-up: Disable + ReloadUI), its table nil
SPOFF = 'C_AddOns = { IsAddOnLoaded = function(n) if n == "WowCompanionSpells" then return false, false end return true, true end }\n'
POOL = 'REALM = ""\nGetNormalizedRealmName = function() return "PoolHint" end\n'
RR7 = "Korvash-Real Realm 7"
def bkeys(L, store):
    return L.eval('(function() local o = {} for k in pairs(WowCompanionSpellsDB.%s or {}) do o[#o + 1] = k end table.sort(o) return table.concat(o, " | ") end)()' % store)
F1 = boot(["safety"], pre=HELP + POOL + SPOFF + 'WowCompanionDB = { lay = { ["Korvash-"] = { hud = 1 } } }\nWowCompanionSpellsDB = nil\n')
F1.execute('fire("PLAYER_LOGIN")')
check(ck(F1)["k"] is None and F1.eval('WowCompanion.charKeySource()') == "none" and F1.eval('WowCompanion.charKeySpellsSeen()') is False,
      "af. k12 the Spells add-on off, nothing seen for the name: NO key (never a one-off new key)", ck(F1))
check(F1.eval('WowCompanionDB.who') is None and F1.eval('WowCompanionDB.realmOf') is None and F1.eval('WowCompanionDB.realmFixBak') is None
      and F1.eval('WowCompanionDB.lay["Korvash-"].hud') == 1,
      "af. ... nothing recorded, nothing folded (the core orphan left as it is)", F1.eval('SER(WowCompanionDB)'))
clean(F1, "af1")
# session 2: both tables, the real settings under Real Realm 7, an orphan over them
S2PRE = (POOL + 'WowCompanionDB = %s\n' % F1.eval('SER(WowCompanionDB)') +
         'WowCompanionSpellsDB = { fightBy = { ["%s"] = { [1] = { t = "spell", id = 5 } }, ["Korvash-"] = { [2] = { t = "spell", id = 9 } } }, '
         'faceBy = { ["%s"] = { a = 1 } } }\n' % (RR7, RR7))
F2 = boot(["safety"], pre=HELP + S2PRE)
F2.execute('fire("PLAYER_LOGIN")')
check(ck(F2) == {"k": RR7, "n": "Korvash", "r": "Real Realm 7", "s": "sticky"} and F2.eval('WowCompanionDB.who["Player-1-Korvash"].k') == RR7,
      "af. ... the next session (both tables): the real key (sticky), recorded", ck(F2))
check(bkeys(F2, "fightBy") == RR7 and F2.eval('WowCompanionSpellsDB.fightBy["%s"][1].id' % RR7) == 5 and F2.eval('WowCompanionSpellsDB.fightBy["%s"][2]' % RR7) is None
      and F2.eval('#WowCompanionDB.realmFixBak') == 1 and F2.eval('WowCompanionDB.lay["%s"].hud' % RR7) == 1,
      "af. ... the orphan never folded elsewhere (the real fightBy kept as it was, the orphan dropped and backed up; the core orphan into the real key)",
      (bkeys(F2, "fightBy"), F2.eval('SER(WowCompanionDB)')))
clean(F2, "af2")
# a key found in the core table while the Spells add-on is off: this session's only (no record, no fold)
F3 = boot(["safety"], pre=HELP + POOL + SPOFF + 'WowCompanionDB = { lay = { ["%s"] = { hud = 2 }, ["Korvash-"] = { hud = 1 } } }\nWowCompanionSpellsDB = nil\n' % RR7)
F3.execute('fire("PLAYER_LOGIN")')
check(ck(F3)["k"] == RR7 and F3.eval('WowCompanion.ck.temp') is True and F3.eval('WowCompanionDB.who') is None
      and F3.eval('WowCompanionDB.realmOf') is None and F3.eval('WowCompanionDB.lay["Korvash-"].hud') == 1 and F3.eval('WowCompanionDB.realmFixBak') is None,
      "af. the Spells add-on off, the core table names the realm: that key for this session only (no record, no realm remembered, no fold)",
      F3.eval('SER(WowCompanionDB)'))
clean(F3, "af3")
# a record from before k12 (a one-off new key recorded while the settings could not be seen): it follows the ONE realm that holds them
F4 = boot(["safety"], pre=HELP + S2PRE.replace('WowCompanionDB = {', 'WowCompanionDB = { who = { ["Player-1-Korvash"] = { k = "Korvash-PoolHint", c = "SHAMAN", i = "Korvash" } },', 1))
F4.execute('fire("PLAYER_LOGIN")')
check(ck(F4)["k"] == RR7 and ck(F4)["s"] == "sticky" and F4.eval('WowCompanionDB.who["Player-1-Korvash"].k') == RR7
      and bkeys(F4, "fightBy") == RR7 and F4.eval('WowCompanionSpellsDB.fightBy["Korvash-PoolHint"]') is None,
      "af. a k10/k11 record whose key holds no settings, ONE realm holds them: that key (sticky), the record re-pointed, nothing under the old key",
      (ck(F4), F4.eval('SER(WowCompanionDB.who)'), bkeys(F4, "fightBy")))
F4b = boot(["safety"], pre=HELP + 'REALM = ""\nWowCompanionDB = %s\nWowCompanionSpellsDB = %s\n' % (F4.eval('SER(WowCompanionDB)'), F4.eval('SER(WowCompanionSpellsDB)')))
F4b.execute('fire("PLAYER_LOGIN")')
check(ck(F4b)["k"] == RR7 and ck(F4b)["s"] == "guid", "af. ... the next login: guid", ck(F4b))
clean(F4, "af4"); clean(F4b, "af4b")
# the record's key holds settings of its own: kept (guid), never moved
F5 = boot(["safety"], pre=HELP + S2PRE.replace('WowCompanionDB = {', 'WowCompanionDB = { who = { ["Player-1-Korvash"] = { k = "Korvash-PoolHint", c = "SHAMAN", i = "Korvash" } }, bags = { ["Korvash-PoolHint"] = { x = 1 } },', 1))
F5.execute('fire("PLAYER_LOGIN")')
check(ck(F5)["k"] == "Korvash-PoolHint" and ck(F5)["s"] == "guid", "af. a record whose key HOLDS settings: kept (guid), whatever else the name holds", ck(F5))
# the player's pick (p = 1, "New character"): never moved by itself, even with no settings of its own yet
F6 = boot(["safety"], pre=HELP + S2PRE.replace('WowCompanionDB = {', 'WowCompanionDB = { who = { ["Player-1-Korvash"] = { k = "Korvash-Forever", c = "SHAMAN", i = "Korvash", p = 1 } },', 1))
F6.execute('fire("PLAYER_LOGIN")')
check(ck(F6)["k"] == "Korvash-Forever" and ck(F6)["s"] == "guid" and F6.eval('WowCompanionDB.who["Player-1-Korvash"].p') == 1,
      "af. the player's own pick (p = 1) with no settings yet: kept (guid), never moved to the realm that holds settings", ck(F6))
# two realms hold settings, the record's key none: kept (never a guess, never a question for a recorded character)
F7 = boot(["safety"], pre=HELP + S2PRE.replace('WowCompanionDB = {', 'WowCompanionDB = { who = { ["Player-1-Korvash"] = { k = "Korvash-PoolHint", c = "SHAMAN", i = "Korvash" } },', 1)
          .replace('faceBy = {', 'faceBy = { ["Korvash-Classic Beta PvE"] = { z = 1 },', 1))
F7.execute('fire("PLAYER_LOGIN")')
check(ck(F7)["k"] == "Korvash-PoolHint" and ck(F7)["s"] == "guid", "af. the record's key holds none, TWO realms do: the record kept (no guess)", ck(F7))
clean(F5, "af5"); clean(F6, "af6"); clean(F7, "af7")
# the Spells add-on loaded with no saved table yet (a first install): a nil table is empty, a new character's key as before
F8 = boot(["safety"], pre=HELP + 'REALM = "Pool A"\nC_AddOns = { IsAddOnLoaded = function() return true, true end }\nWowCompanionDB = nil\nWowCompanionSpellsDB = nil\n')
F8.execute('fire("PLAYER_LOGIN")')
check(ck(F8)["k"] == "Korvash-Pool A" and ck(F8)["s"] == "new" and F8.eval('WowCompanionDB.who["Player-1-Korvash"].k') == "Korvash-Pool A",
      "af. a first install (the Spells add-on loaded, no saved table yet): the new key, recorded", ck(F8))
clean(F8, "af8")

# ag who.ad: the app's adoption only for a fresh key nobody chose
G1 = world(pre='WowCompanionDB = {}\nWowCompanionSpellsDB = {}\nREALM = ""\n')
who = (padmap(G1).get('dpad') or {}).get('who') or {}
check(who.get('rs') == 'new' and who.get('ad') == 1, "ag. k12 a fresh key nobody chose (new, no question): who.ad 1", who)
G2 = world(pre=saved(G1) + 'REALM = ""\n')
who = (padmap(G2).get('dpad') or {}).get('who') or {}
check(who.get('rs') == 'guid' and 'ad' not in who, "ag. ... the next session (guid): no ad", who)
clean(G1, "ag1"); clean(G2, "ag2")
for code, want_src in ((9, "new"), (10, "picked")):
    GP = world(pre=TWO)
    padtab(GP, 39, 38, 37, code)
    who = (padmap(GP).get('dpad') or {}).get('who') or {}
    check(who.get('rs') == want_src and 'ad' not in who and GP.eval('WowCompanionDB.who["Player-1-Korvash"].p') == 1,
          "ag. the question's answer (padtab ... %d, %s): no ad (never another realm's backup over the answer), the record marked p = 1" % (code, want_src), who)
    GQ = world(pre=saved(GP) + 'REALM = "Classic Beta PvE"\n')
    check(GQ.eval('WowCompanion.charKeySource()') == "guid" and GQ.eval('(WowCompanion.charKey())') == GP.eval('(WowCompanion.charKey())')
          and GQ.eval('WowCompanion.charKeyInfo().pk') is None,
          "ag. ... the next login: the answer's key (guid), no question", GQ.eval('(WowCompanion.charKey())'))
    clean(GP, "agp%d" % code); clean(GQ, "agq%d" % code)
who = (padmap(SD).get('dpad') or {}).get('who') or {}
check(who.get('rs') == 'sticky' and 'ad' not in who, "ag. the device's sticky key: no ad", who)

# ================================================================ hs THE HOME KEYS (PadMap m181 / m183: rbm / ctop through WC.charKey)
# the 1515 merge (Safety k12): a name with nothing anywhere now gets a fresh key ("new", x above); "no key" is the add-on ASKING (one
# name holding settings under 2+ realms, nothing naming one) - Home's two add-on stores (rbm: the DS toolbar's mirror bit, ctop: the
# top-screen combat bar's flag) read nothing and write nothing then, and the answer's key is read at once (the next padmap says it)
check('local kNow = whoKey()' in TEXT["padmap"] and 'R.rbmDB()[k] = on and 1 or 0' in TEXT["padmap"] and 'R.ctopDB()[k] = on and 1 or 0' in TEXT["padmap"]
      and 'ct = k and (R.ctopOn() and 1 or 0) or nil' in TEXT["padmap"] and "m183 THE 1515 MERGE INTO MOVABLE HOME" in TEXT["padmap"],
      "hs. the tag: PadMap m183 (rbm / ctop under whoKey, `ct` nil without a key)")
R7, R8 = "Korvash-Realm 7", "Korvash-Realm 8"
ASK = ('REALM = ""\nWowCompanionDB = {}\n'
       'WowCompanionSpellsDB = { dpad = { ["%(a)s"] = true, ["%(b)s"] = true }, faceBy = { ["%(a)s"] = { a = 1 }, ["%(b)s"] = { a = 2, b = 3 } },\n'
       '  rbm = { ["%(a)s"] = 1, ["%(b)s"] = 0, ["Other-Realm 7"] = 0 }, ctop = { ["%(a)s"] = 1, ["%(b)s"] = 0, ["Other-Realm 7"] = 1 } }\n') % {"a": R7, "b": R8}
HOME0 = '{["Korvash-Realm 7"]=1,["Korvash-Realm 8"]=0,["Other-Realm 7"]=%d}'
S1 = world(pre=ASK)
dp = padmap(S1).get('dpad') or {}
who = dp.get('who') or {}
pk = [c.get('r') for c in (who.get('pk') or [])]
check(S1.eval('(WowCompanion.charKey())') is None and who.get('rs') == 'ask' and 'r' not in who and sorted(pk) == ["Realm 7", "Realm 8"],
      "hs. ASKING (two realms hold the name's settings): no key, no realm in who, the question's two candidates", who)
check('ct' not in dp and dp.get('m') == 0 and S1.eval('WowCompanion.padMap.combatTopOn()') is False,
      "hs. ... nothing read: `ct` absent (unknown, not off), m = 0 (today's sides), the Wings' flag off", dp)
ok1, why1 = S1.eval('WowCompanion.padMap.dpadMirror(true)')
ok2, why2 = S1.eval('WowCompanion.padMap.combatTop(false)')
padtab(S1, 39, 38, 37, 21)
padtab(S1, 39, 38, 37, 22)
check(ok1 is False and why1 == "no character" and ok2 is False and why2 == "no character" and S1.eval('(WowCompanion.charKey())') is None,
      "hs. ... both refused (\"no character\"), the app's codes 21 / 22 too (no key, still asking)", (ok1, why1, ok2, why2))
check(S1.eval('SER(WowCompanionSpellsDB.rbm)') == HOME0 % 0 and S1.eval('SER(WowCompanionSpellsDB.ctop)') == HOME0 % 1 and partials(S1)[0] == 0,
      "hs. ... nothing written: rbm / ctop as saved, no partial key", (S1.eval('SER(WowCompanionSpellsDB.rbm)'), S1.eval('SER(WowCompanionSpellsDB.ctop)')))
clean(S1, "hs1")
for label, want, ctw, mw in (("Realm 7", R7, 1, 1), ("Realm 8", R8, 0, 0)):
    S2 = world(pre=ASK)
    pk = [c.get('r') for c in (((padmap(S2).get('dpad') or {}).get('who') or {}).get('pk') or [])]
    n_before = len(docs(S2, 'padmap'))
    padtab(S2, 39, 38, 37, 10 + pk.index(label))
    S2.execute('tick(0.1); runTimers(NOW + 2)')
    ds = docs(S2, 'padmap')
    dp = (ds[-1]['m'].get('dpad') or {}) if ds else {}
    check(S2.eval('(WowCompanion.charKey())') == want and S2.eval('WowCompanion.charKeySource()') == "picked" and len(ds) > n_before
          and dp.get('ct') == ctw and dp.get('m') == mw and (dp.get('who') or {}).get('r') == label and 'pk' not in (dp.get('who') or {}),
          "hs. THE PICK (%s): the next padmap (sent by itself) says that key's own bits (ct %d, m %d), who.r = %s" % (label, ctw, mw, label), dp)
    side = "WowCompanionPadMap" if mw else "WowCompanionPadChat"
    check(S2.eval('router(1).a["wc-roombar"]') == side and S2.eval('WowCompanion.padMap.combatTopOn()') is (ctw == 1),
          "hs. ... read again under the picked key: Left clicks the %s, the Wings' flag %s" % (side.replace("WowCompanionPad", ""), "on" if ctw else "off"), S2.eval('router(1).a["wc-roombar"]'))
    check(S2.eval('SER(WowCompanionSpellsDB.rbm)') == HOME0 % 0 and S2.eval('SER(WowCompanionSpellsDB.ctop)') == HOME0 % 1,
          "hs. ... nothing written over them by the pick", S2.eval('SER(WowCompanionSpellsDB.rbm)'))
    padtab(S2, 39, 38, 37, 20 + (1 - mw))
    padtab(S2, 39, 38, 37, 22 + (1 - ctw))
    S2.execute('runTimers(NOW + 1)')
    other = R8 if want == R7 else R7
    check(S2.eval('WowCompanionSpellsDB.rbm["%s"]' % want) == 1 - mw and S2.eval('WowCompanionSpellsDB.ctop["%s"]' % want) == 1 - ctw
          and S2.eval('WowCompanionSpellsDB.rbm["%s"]' % other) == (0 if other == R8 else 1)
          and S2.eval('WowCompanionSpellsDB.ctop["%s"]' % other) == (0 if other == R8 else 1)
          and S2.eval('WowCompanionSpellsDB.ctop["Other-Realm 7"]') == 1 and partials(S2)[0] == 0,
          "hs. ... an edit then (codes 20-23): under the picked key only; the other realm's and another character's untouched", S2.eval('SER(WowCompanionSpellsDB.ctop)'))
    clean(S2, "hs2 " + label)
# "a new character" (code 9): a fresh key, NONE of the candidates' bits read (today's sides, the flag off)
S3 = world(pre=ASK)
padtab(S3, 39, 38, 37, 9)
S3.execute('tick(0.1); runTimers(NOW + 2)')
dp = padmap(S3).get('dpad') or {}
k3 = S3.eval('(WowCompanion.charKey())')
check(k3 not in (None, R7, R8) and dp.get('ct') == 0 and dp.get('m') == 0 and dp.get('d') == {"1": "chat", "2": "radial", "3": "map", "4": "home"}
      and S3.eval('WowCompanion.padMap.combatTopOn()') is False,
      "hs. 'a new character': its own key (%s), no candidate's bit read (ct 0, m 0, today's sides)" % k3, dp)
padtab(S3, 39, 38, 37, 23)
S3.execute('runTimers(NOW + 1)')
check(S3.eval('WowCompanionSpellsDB.ctop["%s"]' % k3) == 1 and S3.eval('SER(WowCompanionSpellsDB.rbm)') == HOME0 % 0
      and S3.eval('WowCompanionSpellsDB.ctop["%s"]' % R7) == 1 and S3.eval('WowCompanionSpellsDB.ctop["%s"]' % R8) == 0,
      "hs. ... its edits go to the new key only", S3.eval('SER(WowCompanionSpellsDB.ctop)'))
clean(S3, "hs3")
# a pick in a fight is refused by Safety: still asking, nothing read or written
S4 = world(pre=ASK)
S4.execute('COMBAT = true')
padtab(S4, 39, 38, 37, 10)
dp = padmap(S4).get('dpad') or {}
check(S4.eval('(WowCompanion.charKey())') is None and 'ct' not in dp and S4.eval('SER(WowCompanionSpellsDB.ctop)') == HOME0 % 1,
      "hs. a pick in a fight: refused, still asking (no `ct`, nothing written)", dp)
S4.execute('COMBAT = false')
clean(S4, "hs4")

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
