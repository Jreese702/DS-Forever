"""NO PARTIAL CHARACTER KEY (Safety k6 WC.charKey / WC.realmFix; PadMap m178, ActionBar cb16, Spells s18, Radial r27 / q8, Professions p26,
TapPad pad27, Trail prd21, Hub hub72, Map pq2), off-device: the REAL add-on files under tools/wowstub.py's stubbed game, Lua 5.1 as the game's.

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
check('WC.KERNEL = "k6"' in TEXT["safety"] and "function WC.charKey()" in TEXT["safety"] and "what happened to my combat bars on my hunter" in TEXT["safety"],
      "0. Safety k6: WC.charKey, its note quotes the user")
check('local PV = "m178"' in TEXT["padmap"] and 'local CBV = "cb16"' in AB and 'local SV = "s18"' in TEXT["spells"]
      and 'local RV = "r27"' in TEXT["radial"] and 'local SRV = "q8"' in TEXT["radial"] and 'local PV = "p26"' in TEXT["prof"]
      and 'local PADV = "pad27"' in TEXT["tappad"] and 'WC.prd = { V = "prd21"' in TEXT["trail"] and 'local HV = "hub72"' in TEXT["hub"]
      and 'v = "pq2"' in TEXT["map"], "0. the tags: PadMap m178, ActionBar cb16, Spells s18, Radial r27 / q8, Professions p26, TapPad pad27, Trail prd21, Hub hub72, Map pq2")
# every per-character builder goes through WC.charKey: no file but Safety concatenates GetRealmName() into a key any more
for k, s in list(TEXT.items()) + [("actionbar", AB)]:
    if k == "safety":
        continue
    bad = re.findall(r'GetRealmName\(\)[^\n]*\)\s*or\s*"\?"|tostring\(r or ""\)|n \.\. "-" \.\. \(r or "\?"\)', s)
    check(not bad, "0. %s builds no partial key (no 'or \"?\"' / tostring(r or \"\"))" % k, bad[:1])

# ================================================================ a WC.charKey
S = boot(["safety"], pre=HELP)
def ck(L):
    r = L.eval('(function() local k, n, r, s = WowCompanion.charKey(); return { k = k, n = n, r = r, s = s } end)()')
    return {x: r[x] for x in ("k", "n", "r", "s")}


for realm, label in (('""', 'an empty realm'), ('nil', 'a nil realm'), ('"?"', 'a "?" realm'), ('"   "', 'a blank realm'), ('SECRETV', 'a secret realm')):
    S.execute('REALM = %s; WowCompanion.ck.n = nil; WowCompanion.ck.r = nil; WowCompanion.ck.missAt = nil; WowCompanionDB = {}; WowCompanionSpellsDB = {}' % realm)
    check(ck(S)["k"] is None, "a. %s, nothing kept: no key (never \"Korvash-\" / \"Korvash-?\")" % label, ck(S))
S.execute('REALM = "Realm"')
check(ck(S) == {"k": "Korvash-Realm", "n": "Korvash", "r": "Realm", "s": "live"}, "a. the game's realm: Korvash-Realm (live)", ck(S))
check(S.eval('WowCompanionDB.realmOf.Korvash.Realm') is True, "a. ... remembered per name (WowCompanionDB.realmOf)")
S.execute('REALM = ""')
check(ck(S)["k"] == "Korvash-Realm", "a. the realm empty later this session: the session's realm stays", ck(S))
S.execute('WowCompanion.ck.n = nil; WowCompanion.ck.r = nil; WowCompanion.ck.missAt = nil')
check(ck(S) == {"k": "Korvash-Realm", "n": "Korvash", "r": "Realm", "s": "kept"}, "a. a new session, the realm empty: the remembered realm (kept)", ck(S))
S.execute('WowCompanion.ck.n = nil; WowCompanion.ck.r = nil; WowCompanionDB = {}; '
          'WowCompanionSpellsDB = { fightBy = { ["Korvash-Realm 7"] = { [1] = 1 }, ["Korvash-"] = {}, ["Korvash-?"] = {} } }')
check(ck(S)["k"] == "Korvash-Realm 7", "a. nothing remembered: the ONE realm the tables hold the name under (orphans are no realm)", ck(S))
S.execute('WowCompanion.ck.n = nil; WowCompanion.ck.r = nil; WowCompanion.ck.missAt = nil; WowCompanionSpellsDB.faceBy = { ["Korvash-Other Realm"] = { x = 1 } }')
check(ck(S)["k"] is None, "a. two realms for one name, no hint: no key (never a guess)", ck(S))
S.execute('NOW = NOW + 3; GetNormalizedRealmName = function() return "OtherRealm" end')
check(ck(S)["k"] == "Korvash-Other Realm", "a. ... the game's normalized realm (no spaces) picks one", ck(S))
S.execute('REALM = "Realm 7"')
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
DEVICE = 'REALM = ""\nWowCompanionSpellsDB, WowCompanionDB = {}, {}\n' + "\n".join(lines) + "\n"

P = lupa.LuaRuntime(unpack_returned_tuples=True)
P.execute(GAME)
P.execute(HELP)
P.execute(DEVICE)
n0, before = P.eval('REALSTORES("%s")' % REAL)
c0, _ = P.eval('PARTIALS("Korvash")')
check(int(n0) == 23 and int(c0) == 19, "c. the shape: the real key in 22 stores (+ the core lay), 15 + 2 orphans (+ 1 core lay, 1 empty string)",
      (n0, c0))

D = world(pre=DEVICE)
m = padmap(D)
who = (m.get('dpad') or {}).get('who') or {}
check(who == {"n": "Korvash", "r": "Realm", "rs": "kept"}, "c. the realm empty at login: the padmap's who is the REAL key's (rs kept)", who)
sl = m['sets'][0]['slots'][0]
check(sl.get('kind') == 'combat' and (sl.get('c') or {}).get('key') == 'DSMAP', "c. THE BARS COME BACK: slot 1's fight layer from the real fightBy", sl)
check(D.eval('HDR("wc-ft-1")') == "/click WowCompanionPadMap", "c. ... armed on the router (it runs in the next fight)", D.eval('HDR("wc-ft-1")'))
c, where = partials(D)
check(c == 0, "c. every orphan folded out of both tables", where)
# the control: the same real data, no orphan, the game's realm: what the add-on's own code makes of the real stores at a login
REALONLY = 'REALM = ""\nWowCompanionSpellsDB, WowCompanionDB = {}, {}\n' + "\n".join(REAL_LINES) + "\n"
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
check(D.eval('WowCompanionDB.realmOf') is None or D.eval('WowCompanionDB.realmOf.Korvash') is None,
      "c. a realm only kept (not the game's) is not remembered as the game's")
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
R = world(pre='REALM = ""\nWowCompanionSpellsDB = { fightBy = { ["%s"] = %s, ["Korvash-"] = { [1] = { t = "command", key = "DSCHAT", n = "Chat", '
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
check(who == {"n": "Korvash", "r": "Realm", "rs": "live"} and partials(G)[0] == 0 and G.eval('WowCompanionDB.realmOf.Korvash.Realm') is True,
      "g. the game's realm again: live, the orphans folded the same, the realm remembered", (who, partials(G)))
n2, after2 = G.eval('REALSTORES("%s")' % REAL)
check([x for x in after2.split("\n") if not x.startswith("S.hmBy=")] == cl, "g. ... the real stores as a login with no orphan leaves them")
clean(G, "g")

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
