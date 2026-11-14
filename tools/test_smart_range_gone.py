"""SMART RANGE IS GONE (PadMap m160, PadTabCtl p54, Book b61), off-device: the REAL add-on files under tools/wowstub.py's stubbed game.

    python tools/test_smart_range_gone.py

The user, 2026-11-13: "Lets scrap the smart ranged attack". PadMap m146-m149 (the In combat tab's Smart Range box: a Melee / Range pair
per button as ONE saved character macro "DSF <button>" running "/click DSFSR0" / "/click DSFSR1", which also turned the error speech /
sound off for the press) are removed. The players who had it get a ONE-TIME CLEAN-UP per character (the leave-no-trace ruling). Proven:
  a the versions and header notes; the feature's code is gone (no DSFSR0 / DSFSR1 buttons, no DSF_SR0 / DSF_SR1, no sr* calls, the press
    snippet never sets a saved-macro attribute, no padtab 39 code 0-4 route, no CVar or macro WRITE in the clean-up's code);
  b A WORLD WITH OUR LEFTOVERS (an old build's saved state and macros): at login, out of combat, once the macros are loaded, ONLY the
    macros that are provably ours (our name AND exactly our body, the /click and the older /run lines) are deleted; the player's own
    macros stay byte for byte - a look-alike name ("DSF Up" with the player's body), our lines edited by the player, our body under a
    name we never made, and every other macro; no macro is made or edited; no CVar is written;
  c the stored entries keep their top spell and lose the Smart Range fields; the srBy / srMacros records go (this character's only);
    the padmap doc carries the counts (srx: m / b / k) and nothing else of Smart Range (no sr / sro / ffsr); the router arms the plain
    fight spell (no saved-macro name anywhere);
  d idempotent: a second run, more events and a fresh load find nothing to do and keep the record;
  e in a fight: nothing until PLAYER_REGEN_ENABLED, no protected call in the lockdown; then the clean-up;
  f before the game has loaded the macros: nothing (every read finds none); UPDATE_MACROS starts it;
  g per character: another character's macros and records are untouched until that character logs in;
  h a character that never had Smart Range: nothing deleted, the record says 0 / 0 / 0;
  i the In combat tab still works: Y is a no-op there, A opens the chooser, a fight spell goes on and casts.
Needs lupa. Exit code 1 on any failure."""
import json
import os
import re
import sys

from lupa import lua51 as lupa

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADDONS = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons")
F = {
    "safety": os.path.join(ADDONS, "WowCompanion", "Safety.lua"),
    "tappad": os.path.join(ADDONS, "WowCompanion", "TapPad.lua"),
    "book": os.path.join(ADDONS, "WowCompanionSpells", "Book.lua"),
    "padmap": os.path.join(ADDONS, "WowCompanionSpells", "PadMap.lua"),
    "padtabctl": os.path.join(ADDONS, "WowCompanionSpells", "PadTabCtl.lua"),
}
SRC = {k: open(p, encoding="utf-8").read() for k, p in F.items()}
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import GAME  # noqa: E402
PARTY = open(os.path.join(ROOT, "tools", "test_party.py"), encoding="utf-8").read()
WORLD_HELPERS = re.search(r"\nWORLD_HELPERS = r'''(.*?)'''", PARTY, re.S).group(1)
fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


# a level 15 Hunter's book and ranks, the game's macro API (18 character macros PER CHARACTER, 120 General; every write logged and
# refused in the lockdown), the CVars (every write logged)
EXTRA = r'''
CLASS = "HUNTER"
SPELLNAME[2973] = "Raptor Strike"; SPELLNAME[3044] = "Arcane Shot"; SPELLNAME[1978] = "Serpent Sting"; SPELLNAME[2974] = "Wing Clip"
SUB = { [2973] = "Rank 1", [3044] = "Rank 1", [1978] = "Rank 1", [2974] = "Rank 1" }
BOOK = { 2973, 3044, 1978, 2974 }
C_Spell.GetSpellSubtext = function(id) return SUB[id] end
C_Spell.GetSpellInfo = function(x)
    if type(x) == "string" then
        for _, id in ipairs(BOOK) do if SPELLNAME[id] == x then return { spellID = id, name = x, iconID = 130000 + id } end end
        return nil
    end
    if SPELLNAME[x] then return { spellID = x, name = SPELLNAME[x], iconID = 130000 + x } end
end
C_Spell.IsSpellHarmful = function(id) return true end
MAXC = 18
CHARM = CHARM or {}
MLOG = {}
function mymac() local k = WHO .. "-" .. REALM; CHARM[k] = CHARM[k] or {}; return CHARM[k] end
Constants = setmetatable({ MacroConsts = { MAX_ACCOUNT_MACROS = 120, MAX_CHARACTER_MACROS = MAXC } }, { __index = function() return STUB end })
-- the General macros (account-wide, indexes 1-120): Smart Range never made one; ours by name and body there stay (never scanned)
GENM = GENM or {}
function GetNumMacros() return #GENM, #mymac() end
local function at(x)
    local l = mymac()
    if type(x) == "number" then if x <= 120 then return nil, GENM[x] end return x - 120, l[x - 120] end
    for i, m in ipairs(l) do if m.name == x then return i, m end end
end
function GetMacroInfo(x) local _, m = at(x); if m then return m.name, m.icon, m.body end end
function GetMacroIndexByName(n) local i = at(n); return i and (i + 120) or 0 end
function CreateMacro(name) MLOG[#MLOG + 1] = "create " .. tostring(name); if COMBAT then VIOL[#VIOL + 1] = "CreateMacro" end; error("no CreateMacro here") end
function EditMacro(x) MLOG[#MLOG + 1] = "edit " .. tostring(x); if COMBAT then VIOL[#VIOL + 1] = "EditMacro" end; error("no EditMacro here") end
function DeleteMacro(x)
    if COMBAT then VIOL[#VIOL + 1] = "DeleteMacro"; error("ADDON_ACTION_BLOCKED DeleteMacro") end
    local i, m = at(x)
    if not m then return end
    MLOG[#MLOG + 1] = "delete " .. m.name
    table.remove(mymac(), i)
end
function names() local o = {}; for _, m in ipairs(mymac()) do o[#o + 1] = m.name end; return table.concat(o, "|") end
function bodyAt(i) local m = mymac()[i]; return m and m.body end
CVARS = { Sound_EnableErrorSpeech = "0", Sound_EnableSFX = "1" }
CVLOG = {}
C_CVar = { GetCVar = function(k) return CVARS[k] end, SetCVar = function(k, v) CVLOG[#CVLOG + 1] = k .. "=" .. tostring(v); CVARS[k] = tostring(v) end }
function GetCVar(k) return CVARS[k] end
function SetCVar(k, v) C_CVar.SetCVar(k, v) end
ACTIONINFO = {}
function GetActionInfo(s) local a = ACTIONINFO[s]; if a then return a[1], a[2] end end
C_SpellBook = { GetSpellBookItemInfo = function(slot) local id = BOOK[slot]; return id and { spellID = id } or nil end }
function ser(v)
    if type(v) == "table" then local t = {} for k, x in pairs(v) do t[#t + 1] = "[" .. ser(k) .. "]=" .. ser(x) end return "{" .. table.concat(t, ",") .. "}"
    elseif type(v) == "string" then return string.format("%q", v) else return tostring(v) end
end
'''
HIDE = r'''
MHIDE = true
local g1, g2, g3 = GetNumMacros, GetMacroInfo, GetMacroIndexByName
function GetNumMacros() if MHIDE then return 0, 0 end return g1() end
function GetMacroInfo(x) if MHIDE then return nil end return g2(x) end
function GetMacroIndexByName(n) if MHIDE then return 0 end return g3(n) end
'''
AFTER = r'''
WowCompanion.spellsStorageFor = function(page, set, pos) return (page - 1) * 48 + (set - 1) * 12 + pos end
local ctl = WowCompanion._spellsBook.ctl
ctl.croom, ctl.ctrlTab, ctl.onTabs = true, true, true
function pp(key) return WowCompanion.padTabCtl.press(key) end
PT = WowCompanion.padTabCtl
PM = WowCompanion.padMap
WHOK = function() return WHO .. "-" .. REALM end
function FT(slot) local t = (WowCompanionSpellsDB.fightBy or {})[WHOK()]; return t and t[slot] end
function FACEE(p) local t = (WowCompanionSpellsDB.faceBy or {})[WHOK()]; return t and t[p] end
function GONE(k) local t = WowCompanionSpellsDB.srGone; t = t and t[k or WHOK()]; return t and ser(t) end
function HDR(k) return rawget(_G, "WowCompanionFaceRouter").a[k] end
function fightOff() COMBAT = false; fire("PLAYER_REGEN_ENABLED"); runTimers(NOW + 2) end
-- every frame's attributes: any saved-macro attribute ("macro" / wc-ftm / wc-ffm / wc-fight-mname) or a DSFSR line anywhere
function attrScan()
    local bad = {}
    for _, f in ipairs(ALLF) do
        for k, v in pairs(f.a or {}) do
            if k == "macro" or tostring(k):find("^wc%-ftm") or tostring(k):find("^wc%-ffm") or k == "wc-fight-mname"
                or (type(v) == "string" and (v:find("DSFSR", 1, true) or v:find("DSF_SR", 1, true))) then
                bad[#bad + 1] = tostring(f._name) .. ":" .. tostring(k)
            end
        end
    end
    return table.concat(bad, ",")
end
'''

OURS = "#showtooltip\\n/click DSFSR0\\n/cast Raptor Strike(Rank 1)\\n/cast Arcane Shot(Rank 1)\\n/click DSFSR1"
OURS_OLD = "#showtooltip\\n/run DSF_SR0()\\n/cast Raptor Strike(Rank 1)\\n/cast Arcane Shot(Rank 1)\\n/run DSF_SR1()"
EDITED = "#showtooltip\\n/click DSFSR0\\n/cast Raptor Strike(Rank 1)\\n/say help\\n/cast Arcane Shot(Rank 1)\\n/click DSFSR1"
S = 2 * 12 + 2        # ZR + D-pad Up, page 1 (the harness's storage: (page - 1) * 48 + (set - 1) * 12 + pos)
S2 = 2 * 12 + 4       # ZR + D-pad Down
S3 = 1 * 12 + 1       # ZL + D-pad Left
# an old build's saved state for Korvash (Hunter) and for a second character, and their macros (the server's, per character)
LEFTOVERS = r'''
WowCompanionSpellsDB = {
  fightBy = {
    ["Korvash-Realm"] = {
      [%(S)d] = { id = 2973, n = "Raptor Strike", i = 132223, page = 1, set = 3, pos = 2, sr = 1, h = "m", mn = "DSF ZR+Up",
                  rg = { id = 3044, n = "Arcane Shot", i = 132218 } },
      [%(S2)d] = { id = 3044, n = "Arcane Shot", i = 132218, page = 1, set = 3, pos = 4, sr = 1, h = "r" },
      [%(S3)d] = { id = 1978, n = "Serpent Sting", i = 132204, page = 1, set = 2, pos = 1, rgOff = { id = 3044, n = "Arcane Shot", i = 132218 } },
    },
    ["Other-Realm"] = { [%(S)d] = { id = 2973, n = "Raptor Strike", i = 132223, page = 1, set = 3, pos = 2, sr = 1, h = "m", mn = "DSF ZR+Up",
                        rg = { id = 3044, n = "Arcane Shot", i = 132218 } } },
  },
  faceBy = { ["Korvash-Realm"] = { [6] = { id = 2973, n = "Raptor Strike", i = 132223, sr = 1, h = "m", mn = "DSF Y", se = "taken",
                                         rg = { id = 3044, n = "Arcane Shot", i = 132218 } } } },
  srBy = { ["Korvash-Realm"] = { s30 = 1, f6 = 1 }, ["Other-Realm"] = { s5 = 1 } },
  srMacros = { ["Korvash-Realm"] = { ["DSF ZR+Up"] = true, ["DSF Y"] = true }, ["Other-Realm"] = { ["DSF ZR+Up"] = true } },
}
GENM = { { name = "DSF Up", icon = 1, body = "%(OURS)s" } }
CHARM = {
  ["Korvash-Realm"] = {
    { name = "Mine", icon = 1, body = "/say hi" },
    { name = "DSF ZR+Up", icon = 134400, body = "%(OURS)s" },
    { name = "DSF Up", icon = 1, body = "/cast Arcane Shot" },
    { name = "DSF Y", icon = 134400, body = "%(OURS_OLD)s" },
    { name = "DSF Down", icon = 1, body = "%(EDITED)s" },
    { name = "DSF ZL+Left 2", icon = 134400, body = "%(OURS)s\n" },
    { name = "DSFUp", icon = 1, body = "%(OURS)s" },
    { name = "DSF Jump", icon = 1, body = "%(OURS)s" },
    { name = "DSF Up x", icon = 1, body = "%(OURS)s" },
    { name = "DSF Bear Up", icon = 134400, body = "%(OURS)s" },
  },
  ["Other-Realm"] = {
    { name = "DSF ZR+Up", icon = 134400, body = "%(OURS)s" },
    { name = "Alt macro", icon = 1, body = "/wave" },
  },
}
''' % {"S": S, "S2": S2, "S3": S3, "OURS": OURS, "OURS_OLD": OURS_OLD, "EDITED": EDITED}


def boot(pre='', macros_loaded=True, combat=False):
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(GAME)
    L.execute(pre)                                                    # a later load's saved state (and the server's macros)
    L.execute(EXTRA)
    if combat:
        L.execute('COMBAT = true')
    if not macros_loaded:
        L.execute(HIDE)
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for k in ("safety", "tappad", "book", "padmap", "padtabctl"):
        load(SRC[k], '@' + os.path.basename(F[k]))(os.path.basename(F[k]))
    L.execute(WORLD_HELPERS)
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
    if macros_loaded:
        L.execute('fire("UPDATE_MACROS"); runTimers(NOW + 1)')
    L.execute(AFTER)
    L.execute('WowCompanion.padMap.emit(true); runTimers(NOW + 1)')
    return L


def docs(L, typ, start=0):
    out = []
    for i in range(start + 1, int(L.eval('#SENT')) + 1):
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


def mlog(L):
    return [L.eval('MLOG[%d]' % i) for i in range(1, int(L.eval('#MLOG')) + 1)]


def tbl(v):
    if v is None or isinstance(v, (int, float, str, bool)):
        return v
    try:
        keys = list(v.keys())
    except Exception:
        return v
    return {k: tbl(v[k]) for k in keys}


def clean(L, name):
    check(L.eval('#VIOL') == 0 and L.eval('#SNIPERR') == 0 and list(L.eval('ERRS').values()) == [],
          name + ": no lockdown breach, no snippet error, no Lua error",
          (L.eval('VIOL[1]'), L.eval('SNIPERR[1]'), list(L.eval('ERRS').values())[:2]))


def walk(o, path=''):
    """every (path, key) in a JSON document"""
    if isinstance(o, dict):
        for k, v in o.items():
            yield path, k
            yield from walk(v, path + '/' + str(k))
    elif isinstance(o, list):
        for i, v in enumerate(o):
            yield from walk(v, path + '/' + str(i))


PLAYER_KEPT = "Mine|DSF Up|DSF Down|DSFUp|DSF Jump|DSF Up x"

# ================================================================ a. versions, notes, the code that is gone
comp = lupa.LuaRuntime().eval('function(s, n) local f, e = loadstring(s, n); return e end')
for k in ("padmap", "padtabctl", "book"):
    e = comp(SRC[k], k)
    check(e is None, "a. Lua 5.1 compiles %s (200 locals, 60 upvalues)" % os.path.basename(F[k]), e or '')
check('local PV = "m160"' in SRC["padmap"] and 'local CV = "p54"' in SRC["padtabctl"] and 'local BV = "b61"' in SRC["book"],
      "a. the versions: PadMap m160, PadTabCtl p54, Book b61")
check(all('"Lets scrap the smart ranged attack"' in SRC[k] for k in ("padmap", "padtabctl", "book")),
      "a. each changed file's header note quotes the user")
BLOCK = SRC["padmap"][SRC["padmap"].index("-- ---------------------------------------------------------------- m160 SMART RANGE IS GONE"):]
BLOCK = BLOCK[:BLOCK.index("-- m105: a deleted spell radial's command")]
for k in ("padmap", "padtabctl", "book"):
    # (the m160 clean-up block names the old lines only to RECOGNISE our macros: it is scanned on its own below)
    code = "\n".join(re.sub(r'--.*$', '', ln) for ln in SRC[k].replace(BLOCK, '').splitlines())
    left = [w for w in ("srToggle", "srPut", "srClear", "srMove", "srSync", "srSwing", "srWhere", "srFlag", "srPair", "touchSr",
                        "srHalf", "srFaceArm", "SR_CVARS", "DSF_SR0", "DSF_SR1", "wc-ftm", "wc-ffm", "wc-fight-mname", "srp", "srh")
            if re.search(r'\b' + re.escape(w) + r'\b', code)]
    check(left == [], "a. %s: no Smart Range code left (%s)" % (os.path.basename(F[k]), "the box, halves, picker, macros, CVar flip"), left)
PRE = re.search(r'local PRE = \[=\[(.*?)\]=\]', SRC["padmap"], re.S).group(1)
check('SetAttribute("macro"' not in PRE and not re.search(r'\bfunction\b', PRE),
      "a. the press snippet never sets a saved-macro attribute (and has no 'function')")
bcode = "\n".join(re.sub(r'--.*$', '', ln) for ln in BLOCK.splitlines())
check(not re.search(r'\b(CreateMacro|EditMacro|SetCVar|GetCVar|PickupMacro|PlaceAction|PickupAction|SetBinding|SaveBindings)\b', bcode)
      and bcode.count('DeleteMacro') == 1,
      "a. the clean-up's code makes / edits nothing, reads / writes no CVar, no bar or binding write: ONE DeleteMacro (ours only)")
check(re.search(r'if col <= 4 .*touchSr', SRC["book"]) is None and "col == 39" in SRC["book"],
      "a. Book: padtab 39 stays the one-shot pick; its old codes 0-4 are routed nowhere")

# ================================================================ b. a world with our leftovers: only ours go
W = boot(pre=LEFTOVERS)
clean(W, "b. the world loads")
check(W.eval('rawget(_G, "DSFSR0")') is None and W.eval('rawget(_G, "DSFSR1")') is None
      and W.eval('rawget(_G, "DSF_SR0")') is None and W.eval('rawget(_G, "DSF_SR1")') is None,
      "b. no hidden DSFSR0 / DSFSR1 buttons, no DSF_SR0 / DSF_SR1 globals any more")
left = [fn for fn in ("srToggle", "srPut", "srClear", "srMove", "srSync", "srWhere", "srFormFlag", "srSwingKnown", "srSwing")
        if W.eval('WowCompanion.padMap.%s' % fn) is not None]
check(left == [], "b. WC.padMap has no Smart Range calls any more", left)
check(W.eval('WowCompanion.padTabCtl.touchSr') is None and W.eval('type(WowCompanion.padTabCtl.srPick)') == 'function',
      "b. PadTabCtl: touchSr gone, the one-shot pick (srPick) kept")
log = mlog(W)
check(log == ["delete DSF Bear Up", "delete DSF ZL+Left 2", "delete DSF Y", "delete DSF ZR+Up"],
      "b. the login deleted ONLY our four (our name AND our exact body: /click, the older /run, a trailing newline), highest first", log)
check(W.eval('names()') == PLAYER_KEPT, "b. the player's macros all stay: Mine, a look-alike 'DSF Up', our lines edited ('DSF Down'), "
      "our body under names we never made ('DSFUp', 'DSF Jump', 'DSF Up x')", W.eval('names()'))
check(W.eval('#GENM') == 1 and W.eval('GENM[1].name') == "DSF Up", "b. a General macro with our name and body stays (Smart Range only made character macros)")
check(W.eval('bodyAt(1)') == "/say hi" and W.eval('bodyAt(2)') == "/cast Arcane Shot" and W.eval('bodyAt(3)') == EDITED.replace('\\n', '\n'),
      "b. ... byte for byte (their bodies untouched)")
check(not any(x.startswith(("create", "edit")) for x in log), "b. no macro made or edited", log)
check(W.eval('#CVLOG') == 0 and W.eval('CVARS.Sound_EnableErrorSpeech') == "0" and W.eval('CVARS.Sound_EnableSFX') == "1",
      "b. no CVar written: the player's own error-sound settings stay as they are (no saved record of a change ever existed)",
      (W.eval('CVLOG[1]'), W.eval('CVARS.Sound_EnableErrorSpeech')))

# ================================================================ c. the stored entries, the records, the doc, the router
e1, e2, e3, fy = tbl(W.eval('FT(%d)' % S)), tbl(W.eval('FT(%d)' % S2)), tbl(W.eval('FT(%d)' % S3)), tbl(W.eval('FACEE(6)'))
SRF = {"sr", "h", "rg", "mn", "se", "rgOff"}
check(e1 and e1.get('n') == "Raptor Strike" and e1.get('id') == 2973 and not (SRF & set(e1)),
      "c. ZR + Up's pair keeps its top spell (Raptor Strike, the Melee half) as its plain In-combat spell", e1)
check(e2 and e2.get('n') == "Arcane Shot" and not (SRF & set(e2)), "c. a Range-only box keeps its Range spell", e2)
check(e3 and e3.get('n') == "Serpent Sting" and not (SRF & set(e3)), "c. a remembered Range half (rgOff) is dropped, the spell stays", e3)
check(fy and fy.get('n') == "Raptor Strike" and not (SRF & set(fy)), "c. the base Y's pair: Raptor Strike, plain", fy)
check(W.eval('WowCompanionSpellsDB.srBy["Korvash-Realm"]') is None and W.eval('WowCompanionSpellsDB.srMacros["Korvash-Realm"]') is None
      and W.eval('WowCompanionSpellsDB.srBy["Other-Realm"].s5') == 1 and W.eval('WowCompanionSpellsDB.srMacros["Other-Realm"]["DSF ZR+Up"]') is True,
      "c. this character's srBy / srMacros records are gone; another character's stay for its own login")
gone = tbl(W.eval('WowCompanionSpellsDB.srGone["Korvash-Realm"]'))
check(gone == {"m": 4, "b": 4, "k": 2}, "c. the record (counts only): 4 macros deleted, 4 buttons had Smart Range (2 slots, the Y, an empty "
      "box), 2 look-alikes kept", gone)
m = padmap(W)
check(m.get('srx') == {"m": 4, "b": 4, "k": 2}, "c. the padmap doc's srx carries those counts (the problem report's line)", m.get('srx'))
keys = {k for _, k in walk(m.get('sets'))} | {k for _, k in walk(m.get('ff'))} | set(m)      # (commands carry their own `mn`)
check(not ({"sr", "sro", "ffsr", "rg", "mn", "se", "srh", "srp"} & keys), "c. no Smart Range field anywhere in the padmap doc",
      {"sr", "sro", "ffsr", "rg", "mn", "se"} & keys)
W.execute('PT.enter("buttons"); pp("PADRSHOULDER")')
c = docs(W, 'padtabctl')[-1]['c']
check(c.get('view') == 'combat' and 'srp' not in c and 'srh' not in c, "c. the padtabctl doc has no srp / srh", sorted(c)[:6])
W.execute('pp("PADLSHOULDER"); PT.leave()')
check(W.eval('attrScan()') == '', "c. no frame carries a saved-macro attribute or a DSFSR line (the router, the faces)", W.eval('attrScan()'))
ft = W.eval('HDR("wc-ft-%d")' % S)
check(isinstance(ft, str) and "/cast Raptor Strike" in ft and "Arcane Shot" not in ft,
      "c. the router's fight text for ZR + Up is the one spell's plain line", ft)
clean(W, "c")

# ================================================================ d. idempotent
W.execute('fire("PLAYER_ENTERING_WORLD", false, false); fire("UPDATE_MACROS"); runTimers(NOW + 5); fire("PLAYER_REGEN_ENABLED"); runTimers(NOW + 5)')
check(mlog(W) == log and W.eval('names()') == PLAYER_KEPT, "d. more events: nothing more deleted", mlog(W)[len(log):])
SAVED = 'WowCompanionSpellsDB = ' + W.eval('ser(WowCompanionSpellsDB)') + '; CHARM = ' + W.eval('ser(CHARM)')
W2 = boot(pre=SAVED)
check(mlog(W2) == [] and W2.eval('names()') == PLAYER_KEPT, "d. a fresh load with the cleaned state: nothing to delete", mlog(W2))
check(tbl(W2.eval('WowCompanionSpellsDB.srGone["Korvash-Realm"]')) == {"m": 4, "b": 4, "k": 2}
      and padmap(W2).get('srx') == {"m": 4, "b": 4, "k": 2}, "d. ... and the record of the first run is kept (a run that finds nothing)",
      padmap(W2).get('srx'))
check(tbl(W2.eval('FT(%d)' % S)).get('n') == "Raptor Strike", "d. ... the button still holds Raptor Strike")
clean(W2, "d")

# ================================================================ e. a login in a fight: nothing until it ends
C = boot(pre=LEFTOVERS, combat=True)
check(mlog(C) == [] and C.eval('#VIOL') == 0 and C.eval('#mymac()') == 10,
      "e. logged in during a fight: no macro touched, no protected call in the lockdown", (mlog(C), C.eval('VIOL[1]')))
check(padmap(C).get('srx') == {"p": 1} and C.eval('WowCompanionSpellsDB.srGone') is None,
      "e. ... the doc says the clean-up waits (p), no record yet", padmap(C).get('srx'))
C.execute('fightOff()')
check(mlog(C) == ["delete DSF Bear Up", "delete DSF ZL+Left 2", "delete DSF Y", "delete DSF ZR+Up"] and C.eval('names()') == PLAYER_KEPT,
      "e. PLAYER_REGEN_ENABLED: the clean-up runs (ours only)", mlog(C))
check(padmap(C).get('srx') == {"m": 4, "b": 4, "k": 2}, "e. ... and its record", padmap(C).get('srx'))
clean(C, "e")

# ================================================================ f. before the game has loaded the macros
H = boot(pre=LEFTOVERS, macros_loaded=False)
check(mlog(H) == [] and H.eval('WowCompanionSpellsDB.srGone') is None and tbl(H.eval('FT(%d)' % S)).get('sr') == 1,
      "f. the macros not loaded yet: nothing deleted, nothing stripped, no record (a read finds no macro at all)", mlog(H))
H.execute('MHIDE = false; fire("UPDATE_MACROS"); runTimers(NOW + 2)')
check(mlog(H) == ["delete DSF Bear Up", "delete DSF ZL+Left 2", "delete DSF Y", "delete DSF ZR+Up"] and H.eval('names()') == PLAYER_KEPT,
      "f. UPDATE_MACROS: the clean-up runs", mlog(H))
clean(H, "f")

# ================================================================ g. per character
W.execute('WHO = "Other"; MLOG = {}; fire("PLAYER_ENTERING_WORLD", false, true); runTimers(NOW + 5)')
check(mlog(W) == ["delete DSF ZR+Up"] and W.eval('names()') == "Alt macro", "g. the second character's login: its own macro of ours goes, "
      "its 'Alt macro' stays", (mlog(W), W.eval('names()')))
check(W.eval('WowCompanionSpellsDB.srBy') is None and W.eval('WowCompanionSpellsDB.srMacros') is None
      and tbl(W.eval('WowCompanionSpellsDB.srGone["Other-Realm"]')) == {"m": 1, "b": 2, "k": 0},
      "g. its records go too (none left at all); its own counts (1 macro, 2 buttons: the pair and the empty box)",
      tbl(W.eval('WowCompanionSpellsDB.srGone["Other-Realm"]')))
W.execute('WHO = "Korvash"')
check(W.eval('names()') == PLAYER_KEPT and tbl(W.eval('WowCompanionSpellsDB.srGone["Korvash-Realm"]')) == {"m": 4, "b": 4, "k": 2},
      "g. the first character's macros and record as they were")
clean(W, "g")

# ================================================================ h. a character that never had Smart Range
N = boot(pre='CHARM = { ["Korvash-Realm"] = { { name = "Mine", icon = 1, body = "/say hi" } } }')
check(mlog(N) == [] and N.eval('names()') == "Mine" and padmap(N).get('srx') == {"m": 0, "b": 0, "k": 0},
      "h. nothing of ours: nothing deleted; the record says 0 / 0 / 0", (mlog(N), padmap(N).get('srx')))
clean(N, "h")

# ================================================================ i. the In combat tab still works
I = boot()
I.execute('PT.enter("buttons"); pp("PADRSHOULDER"); PT.touchSet(3); PT.touchPos(2)')
n0 = int(I.eval('#SENT'))
I.execute('pp("PAD4")')
c = docs(I, 'padtabctl')[-1]['c']
check(c.get('view') == 'combat' and not [d for d in docs(I, 'padack', n0)] and I.eval('FT(%d)' % S) is None,
      "i. Y on the In combat tab: nothing (no box, no answer, nothing stored)", c.get('note'))
I.execute('pp("PAD1")')
c = docs(I, 'padtabctl')[-1]['c']
check(c.get('chooser') is not None, "i. A opens the chooser for the button's fight spell", c.get('note'))
I.execute('PT.leave(); PM.putCombat(1, 3, 2, 3)')                   # Serpent Sting (book slot 3) onto ZR + Up
e = tbl(I.eval('FT(%d)' % S))
check(e and e.get('n') == "Serpent Sting" and not (SRF & set(e)), "i. a fight spell goes on as before (no Smart Range fields)", e)
ft = I.eval('HDR("wc-ft-%d")' % S)
check(isinstance(ft, str) and "/cast Serpent Sting" in ft and I.eval('attrScan()') == '', "i. ... and the router arms its plain line", ft)
I.execute('WowCompanion.pad.rows["padtab"].handler(nil, 39); WowCompanion.pad.rows["padtab"].handler(nil, 3)')
check(I.eval('WowCompanion.padTabCtl.srWants()') is not True and tbl(I.eval('FT(%d)' % S)).get('n') == "Serpent Sting",
      "i. padtab 39 then 3 (the old 'box on' code): the pick is spent, nothing changes")
clean(I, "i")

print("\n%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
