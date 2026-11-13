"""SMART RANGE (PadMap m146, PadTabCtl p46, Book b52), off-device: the REAL add-on files under tools/wowstub.py's stubbed game.

    python tools/test_smart_range.py            (SR_DUMP=<dir>: also writes the In combat tab's documents for the app's renders)

The user, 2026-11-12: "revise the right section [of the In combat tab] and remove the "Otherwise spell", and instead move down the
assigned spell and make space for an optional checkbox called "Smart Range". when enabled I want two selectable buttons, one for melee and
one for range ... Melee should show that skill when the enemy is in melee range, range should show that skill when the enemy is not in
Melee range. only having one selected will just show that ability all of the time. this should be per button/ button modifier combo";
"I am completely fine with saving macros for the addon". The device facts (the user's own game macros, a level 15 Hunter): Raptor Strike
then Arcane Shot works everywhere; Wing Clip first does not (a refused GCD spell uses up the press). Proven here:
  a the box on a D-pad + ZR slot (Y on the In combat tab) and on the base Y; the documents carry it;
  b the Melee half takes a next-swing attack only (Raptor Strike by id, a tooltip "Next melee" spell, Wing Clip REFUSED: nothing changed);
  c both halves = ONE saved character macro, its exact body (SR0, melee, range, SR1) at exact ranks, its name; the length guard;
  d DSF_SR0 / DSF_SR1 keep and give back the player's OWN error-speech value (on, already off, a missed SR1), clear the error text;
  e one half only = that spell, the plain press, no macro;
  f all character macros in use: the pair is refused, nothing changed, no macro made;
  g the box off keeps the Melee half and remembers the Range half; on again brings the pair (and its macro) back;
  h clear a half / Move a half (the macro renamed) / clear the button (the macro deleted);
  i a new rank of either half rewrites the macro out of combat; in combat it waits for the fight's end; puts are refused in combat;
  j a player's own macro is never touched (also one with our name: "taken"); per character;
  k the router arms the press to run OUR macro (a slot's "wc-ftm-<slot>", the face's "wc-fight-mname"), the press runs its lines;
  l the controller (Y, the A picker, X Move) and touch (padtab 39 + code) routes; the padtabctl doc's srp / srh.
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
DUMP = os.environ.get("SR_DUMP")
fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


# a level 15 Hunter's book, ranks, the game's macro API (18 character macros PER CHARACTER, 120 General), the CVars, the error frame,
# the tooltip, and a secure press that runs a saved macro by its "macro" attribute (the game's SecureTemplates: "macro" before "macrotext")
EXTRA = r'''
CLASS = "HUNTER"
SPELLNAME[2973] = "Raptor Strike"; SPELLNAME[14260] = "Raptor Strike"; SPELLNAME[3044] = "Arcane Shot"; SPELLNAME[14281] = "Arcane Shot"
SPELLNAME[2974] = "Wing Clip"; SPELLNAME[1978] = "Serpent Sting"; SPELLNAME[90001] = "Savage Swing"; SPELLNAME[1130] = "Hunter's Mark"
SPELLNAME[90002] = string.rep("Long", 30); SPELLNAME[90003] = string.rep("Wide", 30); SPELLNAME[90004] = string.rep("Swing", 30)
SUB = { [2973] = "Rank 1", [14260] = "Rank 2", [3044] = "Rank 1", [14281] = "Rank 2", [2974] = "Rank 1", [1978] = "Rank 1" }
BOOK = { 2973, 3044, 2974, 1978, 90001, 1130, 90002, 90003, 90004 }
C_Spell.GetSpellSubtext = function(id) return SUB[id] end
C_Spell.GetSpellInfo = function(x)
    if type(x) == "string" then
        local best, bn
        for _, id in ipairs(BOOK) do
            if SPELLNAME[id] == x then local r = tonumber((SUB[id] or ""):match("%d+")) or 0; if not bn or r > bn then best, bn = id, r end end
        end
        if best then return { spellID = best, name = x, iconID = 130000 + best } end
        return nil
    end
    if SPELLNAME[x] then return { spellID = x, name = SPELLNAME[x], iconID = 130000 + x, maxRange = (x == 2973 or x == 14260) and 0 or 35 } end
end
C_Spell.IsSpellHarmful = function(id) return id ~= 1130 end
SPELL_ON_NEXT_SWING = "Next melee"
C_TooltipInfo = { GetSpellByID = function(id)
    local l = { { leftText = SPELLNAME[id] or "?" }, { leftText = (id == 90001 or id == 90004) and "Next melee" or "Instant", rightText = "5 yd range" } }
    return { lines = l } end }
-- the macros: General (none of ours), and each CHARACTER's own 18
MAXC = 18
CHARM = {}
MLOG = {}
function mymac() local k = WHO .. "-" .. REALM; CHARM[k] = CHARM[k] or {}; return CHARM[k] end
Constants = setmetatable({ MacroConsts = { MAX_ACCOUNT_MACROS = 120, MAX_CHARACTER_MACROS = MAXC } }, { __index = function() return STUB end })
function GetNumMacros() return 0, #mymac() end
local function at(x)
    local l = mymac()
    if type(x) == "number" then return x - 120, l[x - 120] end
    for i, m in ipairs(l) do if m.name == x then return i, m end end
end
function GetMacroInfo(x) local _, m = at(x); if m then return m.name, m.icon, m.body end end
function GetMacroIndexByName(n) local i = at(n); return i and (i + 120) or 0 end
function CreateMacro(name, icon, body, per)
    if COMBAT then VIOL[#VIOL + 1] = "CreateMacro"; error("ADDON_ACTION_BLOCKED CreateMacro") end
    MLOG[#MLOG + 1] = "create " .. name
    local l = mymac()
    if not per then error("a General macro") end
    if #l >= MAXC then error("too many macros") end
    l[#l + 1] = { name = name, icon = icon, body = body }
    return #l + 120
end
function EditMacro(x, name, icon, body)
    if COMBAT then VIOL[#VIOL + 1] = "EditMacro"; error("ADDON_ACTION_BLOCKED EditMacro") end
    local _, m = at(x)
    if not m then error("no macro") end
    MLOG[#MLOG + 1] = "edit " .. m.name .. (name ~= m.name and (" -> " .. name) or "")
    m.name, m.icon, m.body = name or m.name, icon or m.icon, body or m.body
end
function DeleteMacro(x)
    if COMBAT then VIOL[#VIOL + 1] = "DeleteMacro"; error("ADDON_ACTION_BLOCKED DeleteMacro") end
    local i, m = at(x)
    if not m then return end
    MLOG[#MLOG + 1] = "delete " .. m.name
    table.remove(mymac(), i)
end
function body(n) local _, m = at(n); return m and m.body end
function nmac() return #mymac() end
-- the CVars (this client's error speech), every write logged
CVARS = { Sound_EnableErrorSpeech = "1" }
CVLOG = {}
C_CVar = { GetCVar = function(k) return CVARS[k] end, SetCVar = function(k, v) CVLOG[#CVLOG + 1] = k .. "=" .. tostring(v); CVARS[k] = tostring(v) end }
function GetCVar(k) return CVARS[k] end
function SetCVar(k, v) C_CVar.SetCVar(k, v) end
CLEARS = 0
UIErrorsFrame = { Clear = function() CLEARS = CLEARS + 1 end }
-- the bars, the drivers, the header's own bindings (test_fight_layer.py's)
ACTIONINFO = {}
function GetActionInfo(s) local a = ACTIONINFO[s]; if a then return a[1], a[2] end end
function GetActionTexture(s) local a = ACTIONINFO[s]; return a and 130000 + a[2] or nil end
ADRV = {}
function RegisterAttributeDriver(f, attr, cond) ADRV[#ADRV + 1] = { f = f, a = attr, c = cond } end
function driveAll()
    for _, d in ipairs(ADRV) do
        for clause in (d.c .. ";"):gmatch("([^;]*);") do
            local r = SecureCmdOptionParse((clause:gsub("^%s+", "")))
            if r ~= nil and r ~= "" then d.f.a[d.a] = r break end
        end
    end
    driveStates()
end
local function hb(self, what) if COMBAT and not IN_SECURE then VIOL[#VIOL + 1] = what end end
function Frame:SetBinding(p, key, act) hb(self, "SetBinding"); BIND[key] = act; BOWNER[key] = self end
function Frame:SetBindingClick(p, key, name, mb) hb(self, "SetBindingClick"); BIND[key] = "CLICK " .. name .. ":" .. (mb or "LeftButton"); BOWNER[key] = self end
function Frame:ClearBinding(key) hb(self, "ClearBinding"); if BOWNER[key] == self then BIND[key] = nil; BOWNER[key] = nil end end
C_SpellBook = { GetSpellBookItemInfo = function(slot) local id = BOOK[slot]; return id and { spellID = id } or nil end }
-- THE PRESS: what the secure action button does (the game's SecureTemplates: a "macro" attribute runs that saved macro, else the text)
RAN = {}
local function runText(text, how)
    for line in (text or ""):gmatch("[^\n]+") do
        local nm, btn, d = line:match("^/click%s+([%w_]+)%s*(%S*)%s*(%S*)")
        local code = line:match("^/run%s+(.*)$")
        if nm then
            local t = rawget(_G, nm)
            if type(t) == "table" and t.a then doClick(t, btn ~= "" and btn or "LeftButton", d == "1" or (d == "" and true) or false) end
        elseif code then
            RAN[#RAN + 1] = how .. ":" .. line
            local f = loadstring(code); if f then IN_SECURE_WAS = IN_SECURE; f() end
        elseif line:sub(1, 1) ~= "#" then
            RAN[#RAN + 1] = how .. ":" .. line
            ACTIONS[#ACTIONS + 1] = { kind = "macroline", line = line }
        end
    end
end
local function secureAct2(f, button, down)
    local t = f.a["type"]
    local hold = f.a["pressAndHoldAction"]
    local onDown = ACT_ON_DOWN or hold
    local act
    if (down and onDown) or ((not down) and not onDown) then act = t
    elseif (not down) and hold and f.a["typerelease"] then act = f.a["typerelease"] end
    if not act then return end
    if act == "macro" then
        local m = f.a["macro"]
        if m then local b = body(m); RAN[#RAN + 1] = "runmacro:" .. tostring(m); runText(b, "m") else runText(f.a["macrotext"], "t") end
    else ACTIONS[#ACTIONS + 1] = { kind = act, frame = f._name, spell = f.a["spell"], unit = f.a["unit"], item = f.a["item"] } end
end
function doClick(f, button, down)
    local reg = false
    for _, c in ipairs(f.clicks) do
        if c == "AnyDown" and down then reg = true end
        if c == "AnyUp" and not down then reg = true end
        if c == "LeftButtonUp" and not down and button == "LeftButton" then reg = true end
    end
    if #f.clicks == 0 and f._tpl:find("SecureActionButtonTemplate") then reg = not down end
    if not reg then return end
    for _, w in ipairs(f.wraps.OnClick or {}) do
        local r = runSecure(w.pre, f, button, down)
        if r == false then return end
    end
    if f._tpl:find("SecureHandlerClickTemplate") and f.a["_onclick"] then
        runSecure(compileSnippet(f.a["_onclick"], "self, button, down"), f, button, down)
    end
    if f.scripts.OnClick then f.scripts.OnClick(f, button, down) end
    if f._tpl:find("SecureActionButtonTemplate") then secureAct2(f, button, down) end
    for _, fn in ipairs(f.hooks.OnClick or {}) do fn(f, button, down) end
    for _, fn in ipairs(f.hooks.PostClick or {}) do fn(f, button, down) end
end
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
function HDR(k) return rawget(_G, "WowCompanionFaceRouter").a[k] end
function fightOn() fire("PLAYER_REGEN_DISABLED"); COMBAT = true; driveAll() end
function fightOff() COMBAT = false; driveAll(); fire("PLAYER_REGEN_ENABLED"); runTimers(NOW + 2) end
function bs(id) for i, x in ipairs(BOOK) do if x == id then return i end end end
function padtab(col) WowCompanion.pad.rows["padtab"].handler(nil, col) end
'''


def boot():
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(GAME)
    L.execute(EXTRA)
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for k in ("safety", "tappad", "book", "padmap", "padtabctl"):
        load(SRC[k], '@' + os.path.basename(F[k]))(os.path.basename(F[k]))
    L.execute(WORLD_HELPERS)
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
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


def tbl(v):
    if v is None or isinstance(v, (int, float, str, bool)):
        return v
    try:
        keys = list(v.keys())
    except Exception:
        return v
    return {k: tbl(v[k]) for k in keys}


def slot_doc(L, set_, pos):
    L.execute('WowCompanion.padMap.emit(true)')
    m = docs(L, 'padmap')[-1]['m']
    return m['sets'][set_ - 1]['slots'][pos - 1]


def ctl(L):
    return docs(L, 'padtabctl')[-1]['c']


def acks(L, start, op=None):
    return [d for d in docs(L, 'padack', start) if op is None or d.get('op') == op]


def mlog(L):
    return [L.eval('MLOG[%d]' % i) for i in range(1, int(L.eval('#MLOG')) + 1)]


def ran(L, start=0):
    return [L.eval('RAN[%d]' % i) for i in range(start + 1, int(L.eval('#RAN')) + 1)]


def clean(L, name):
    check(L.eval('#VIOL') == 0 and L.eval('#SNIPERR') == 0 and list(L.eval('ERRS').values()) == [],
          name + ": no lockdown breach, no snippet error, no Lua error",
          (L.eval('VIOL[1]'), L.eval('SNIPERR[1]'), list(L.eval('ERRS').values())[:2]))


def dump(L, name, ctl_edit=None):
    if not DUMP:
        return
    os.makedirs(os.path.join(DUMP, "docs"), exist_ok=True)
    L.execute('WowCompanion.padMap.emit(true); WowCompanion.padTabCtl.sync()')
    pm = [d for d in docs(L, 'padmap')][-1]
    pc = [d for d in docs(L, 'padtabctl')][-1]
    if ctl_edit:
        ctl_edit(pc['c'])
    json.dump(pm, open(os.path.join(DUMP, "docs", "sr_" + name + "_map.json"), "w"))
    json.dump(pc, open(os.path.join(DUMP, "docs", "sr_" + name + "_ctl.json"), "w"))


comp = lupa.LuaRuntime().eval('function(s, n) local f, e = loadstring(s, n); return e end')
for k in ("padmap", "padtabctl", "book"):
    e = comp(SRC[k], k)
    check(e is None, "Lua 5.1 compiles %s (200 locals, 60 upvalues)" % os.path.basename(F[k]), e or '')
check('local PV = "m146"' in SRC["padmap"] and 'local CV = "p46"' in SRC["padtabctl"] and 'local BV = "b52"' in SRC["book"],
      "the versions: PadMap m146, PadTabCtl p46, Book b52")
check(all('this should be per button/ button modifier combo' in SRC[k] for k in ("padmap", "padtabctl", "book")),
      "each changed file's header note quotes the user")
PRE = re.search(r'local PRE = \[=\[(.*?)\]=\]', SRC["padmap"], re.S).group(1)
check(not re.search(r'\bfunction\b', PRE) and 'self:SetAttribute("macro", ftm)' in PRE and 'self:SetAttribute("macro", nil)' in PRE,
      "the press snippet: no 'function'; a pair's slot press sets the macro attribute, every other press clears it")

# ================================================================ a. the box: D-pad + ZR (the controller's Y) and the base Y
W = boot()
clean(W, "a. the world loads")
S = W.eval('WowCompanion.spellsStorageFor(1, 3, 2)')                          # ZR + D-pad Up, page 1
W.execute('PT.enter("buttons"); pp("PADRSHOULDER")')
check(ctl(W)['view'] == 'combat', "a. the In combat tab", ctl(W)['view'])
W.execute('PT.touchSet(3); PT.touchPos(2)')
a0 = int(W.eval('#SENT'))
W.execute('pp("PAD4")')
check(ctl(W).get('note') == 'sr-toggled' and slot_doc(W, 3, 2).get('sr') is True, "a. Y on ZR + Up: its Smart Range box is checked (the doc's `sr`)",
      (ctl(W).get('note'), slot_doc(W, 3, 2).get('sr')))
check(W.eval('WowCompanionSpellsDB.srBy[WHOK()]["s%d"]' % S) == 1 and W.eval('FT(%d)' % S) is None,
      "a. no spell yet: the checked box is the small side map's (srBy, per character)")
check(len(acks(W, a0, 'srange')) == 1 and acks(W, a0, 'srange')[0]['ok'] is True, "a. one padack op srange ok")
W.execute('PT.touchSet(1); PT.touchPos(6); pp("PAD4")')
check(slot_doc(W, 1, 6).get('sr') is True, "a. Y on the base Y (In combat): its box checked too", slot_doc(W, 1, 6))
W.execute('PT.touchPos(7); pp("PAD4")')
check(ctl(W).get('note') == 'stays-back' and slot_doc(W, 1, 7).get('sr') is None, "a. B: no Smart Range (it stays the game's Back)", ctl(W).get('note'))
check(W.eval('nmac()') == 0 and mlog(W) == [], "a. no macro made yet (no pair)")
W.execute('PT.leave()')
check(slot_doc(W, 2, 2).get('sr') is None, "a. another combo (ZL + Up) is its own: unchecked")

# ================================================================ b. the Melee half: next-swing attacks only
a0 = int(W.eval('#SENT'))
ok = W.eval('(PM.srPut(1, 3, 2, "m", bs(2974)))')
check(ok is False and acks(W, a0, 'srput')[-1].get('why') == 'swing' and W.eval('FT(%d)' % S) is None,
      "b. Wing Clip in the Melee half: refused 'swing', nothing changed", acks(W, a0, 'srput'))
check(W.eval('(PM.srSwing(2973))') is True and W.eval('(PM.srSwing(14260))') is True and W.eval('(PM.srSwing(90001))') is True
      and W.eval('(PM.srSwing(2974))') is False and W.eval('(PM.srSwing(3044))') is False,
      "b. next-swing: Raptor Strike's ranks (known ids), a tooltip 'Next melee' spell; not Wing Clip / Arcane Shot")
ok = W.eval('(PM.srPut(1, 3, 2, "m", bs(2973)))')
e = tbl(W.eval('FT(%d)' % S))
check(ok is True and e.get('sr') == 1 and e.get('h') == 'm' and e.get('n') == 'Raptor Strike' and e.get('rg') is None,
      "b. Raptor Strike in the Melee half (h m, on top)", e)
check(W.eval('HDR("wc-ft-%d")' % S).endswith("/cast Raptor Strike(Rank 1)") and W.eval('HDR("wc-ftm-%d")' % S) is None and W.eval('nmac()') == 0,
      "e. one half only: the plain single press at its exact rank (start attack on cast's lines as today), no saved macro", (W.eval('HDR("wc-ft-%d")' % S), W.eval('nmac()')))

# ================================================================ c. both halves: the saved macro
a0 = int(W.eval('#SENT'))
ok = W.eval('(PM.srPut(1, 3, 2, "r", bs(3044)))')
BODY = "#showtooltip\n/run DSF_SR0()\n/cast Raptor Strike(Rank 1)\n/cast Arcane Shot(Rank 1)\n/run DSF_SR1()"
check(ok is True and W.eval('body("DSF ZR+Up")') == BODY, "c. Arcane Shot in the Range half: ONE character macro 'DSF ZR+Up', melee FIRST",
      W.eval('body("DSF ZR+Up")'))
check(mlog(W) == ["create DSF ZR+Up"] and len(W.eval('"DSF ZR+Up"')) <= 16, "c. made once (CreateMacro, per character), the name within 16", mlog(W))
e = tbl(W.eval('FT(%d)' % S))
check(e.get('rg', {}).get('n') == 'Arcane Shot' and e.get('mn') == 'DSF ZR+Up' and e.get('se') is None, "c. the entry: rg = Arcane Shot, mn", e)
check(W.eval('HDR("wc-ftm-%d")' % S) == "DSF ZR+Up" and W.eval('HDR("wc-ft-%d")' % S) == BODY[len("#showtooltip\n"):],
      "k. the router: wc-ftm-<slot> = our macro's name; wc-ft = its lines (the stand-in)", (W.eval('HDR("wc-ftm-%d")' % S), W.eval('HDR("wc-ft-%d")' % S)))
sl = slot_doc(W, 3, 2)
c = sl.get('c', {})
check(sl['kind'] == 'combat' and c.get('sr') == 1 and c.get('h') == 'm' and c.get('n') == 'Raptor Strike' and c.get('rg', {}).get('n') == 'Arcane Shot'
      and c.get('rg', {}).get('id') == 3044 and c.get('mn') == 'DSF ZR+Up', "c. the document: c = Raptor Strike (sr, h m), rg = Arcane Shot, mn", sl)
fs = W.eval('WowCompanion.padMapFightSpells()')
check(set(fs.values()) >= {2973, 3044}, "c. the fight state lists both halves' ids (Home's picture follows the press)", list(fs.values()))
# the length guard: two long names
a0 = int(W.eval('#SENT'))
S2 = W.eval('WowCompanion.spellsStorageFor(1, 2, 3)')
W.execute('PM.srToggle(1, 2, 3, true); PM.srPut(1, 2, 3, "m", bs(90001))')
ok = W.eval('(PM.srPut(1, 2, 3, "r", bs(90002)))')
check(ok is True, "c. a 120-letter range spell fits (body under 255)")
W.execute('PM.srClear(1, 2, 3, "r"); PM.srPut(1, 2, 3, "m", bs(90004))')
ok = W.eval('(PM.srPut(1, 2, 3, "r", bs(90003)))')
check(ok is False and acks(W, a0, 'srput')[-1].get('why') == 'long' and W.eval('body("DSF ZL+Right")') is None,
      "c. lines past the game's 255: refused 'long', no macro", acks(W, a0, 'srput')[-1])
W.execute('PM.srClear(1, 2, 3, "m"); PM.srToggle(1, 2, 3, false)')
check(W.eval('FT(%d)' % S2) is None and W.eval('nmac()') == 1, "c. that button emptied again (its box off)", W.eval('nmac()'))
clean(W, "c")

# ================================================================ k. the press in a fight: ZR + Up and the router's saved macro
W.execute('fightOn(); trig(false, true)')
r0 = int(W.eval('#RAN'))
W.execute('hit(2)')
kind, mac = W.eval('router(2).a["wc-kind"]'), W.eval('router(2).a["macro"]')
got = ran(W, r0)
check(kind == "slotfight" and mac == "DSF ZR+Up" and got[:1] == ["runmacro:DSF ZR+Up"],
      "k. in a fight ZR + Up runs OUR saved macro by name (the router's macro attribute)", (kind, mac, got))
check(got[1:] == ["m:/run DSF_SR0()", "m:/cast Raptor Strike(Rank 1)", "m:/cast Arcane Shot(Rank 1)", "m:/run DSF_SR1()"],
      "k. its lines in order: SR0, Raptor Strike, Arcane Shot, SR1", got)
check(W.eval('CVLOG[1]') == "Sound_EnableErrorSpeech=0" and W.eval('CVLOG[2]') == "Sound_EnableErrorSpeech=1" and W.eval('#CVLOG') == 2
      and W.eval('CVARS.Sound_EnableErrorSpeech') == "1" and W.eval('CLEARS') >= 1,
      "d. SR0 turned the error speech off, SR1 gave the player's own value back (on), the error text cleared", (W.eval('#CVLOG'), W.eval('CLEARS')))
W.execute('trig(false, false); hit(2)')
check(W.eval('router(2).a["macro"]') is None, "k. a bare D-pad Up press in the same fight: no saved macro (the attribute cleared)")
W.execute('trig(false, false); fightOff()')
clean(W, "k")

# ================================================================ d. SR0 / SR1: already off, a missed SR1
W.execute('CVARS.Sound_EnableErrorSpeech = "0"; CVLOG = {}; DSF_SR0(); DSF_SR1()')
check(W.eval('#CVLOG') == 0 and W.eval('CVARS.Sound_EnableErrorSpeech') == "0", "d. the player's own OFF: never written, never forced on")
W.execute('CVARS.Sound_EnableErrorSpeech = "1"; CVLOG = {}; DSF_SR0()')
check(W.eval('CVARS.Sound_EnableErrorSpeech') == "0", "d. SR0 alone: off")
W.execute('runTimers(NOW + 2)')
check(W.eval('CVARS.Sound_EnableErrorSpeech') == "1", "d. a missed SR1: the player's value back a moment later")
W.execute('DSF_SR0(); DSF_SR0(); DSF_SR1()')
check(W.eval('CVARS.Sound_EnableErrorSpeech') == "1", "d. two SR0 in a row then SR1: still the player's own value (on)")

# ================================================================ i. ranks: a new rank of either half rewrites the macro out of combat
W.execute('BOOK[#BOOK + 1] = 14260; fire("SPELLS_CHANGED"); runTimers(NOW + 2)')
check(W.eval('body("DSF ZR+Up")') == BODY.replace("Raptor Strike(Rank 1)", "Raptor Strike(Rank 2)") and W.eval('FT(%d).id' % S) == 14260,
      "i. Raptor Strike Rank 2 learned: the macro casts Rank 2 (rewritten out of combat)", W.eval('body("DSF ZR+Up")'))
W.execute('fightOn(); BOOK[#BOOK + 1] = 14281; fire("SPELLS_CHANGED"); runTimers(NOW + 2)')
check(W.eval('body("DSF ZR+Up")').endswith("Arcane Shot(Rank 1)\n/run DSF_SR1()") and W.eval('#VIOL') == 0,
      "i. Arcane Shot Rank 2 learned IN a fight: not rewritten yet (no lockdown write)", W.eval('body("DSF ZR+Up")'))
a0 = int(W.eval('#SENT'))
ok = W.eval('(PM.srPut(1, 3, 2, "r", bs(1978)))')
check(ok is False and acks(W, a0, 'srput')[-1].get('why') == 'combat', "i. a put in combat: refused 'combat'")
W.execute('fightOff(); runTimers(NOW + 3)')
check(W.eval('body("DSF ZR+Up")') == "#showtooltip\n/run DSF_SR0()\n/cast Raptor Strike(Rank 2)\n/cast Arcane Shot(Rank 2)\n/run DSF_SR1()",
      "i. the fight's end: rewritten with Arcane Shot Rank 2", W.eval('body("DSF ZR+Up")'))
check(W.eval('nmac()') == 1 and "delete DSF ZR+Up" not in mlog(W) and mlog(W).count("create DSF ZR+Up") == 1, "i. still ONE macro of ours (edited in place)", mlog(W))
clean(W, "i")

# ================================================================ g. the box off: Melee stays, Range remembered; on again
ok = W.eval('(PM.srToggle(1, 3, 2, false))')
e = tbl(W.eval('FT(%d)' % S))
check(ok is True and e.get('sr') is None and e.get('n') == 'Raptor Strike' and e.get('rgOff', {}).get('n') == 'Arcane Shot' and e.get('rg') is None,
      "g. off: Raptor Strike stays the button's one spell, Arcane Shot remembered (rgOff)", e)
check(W.eval('body("DSF ZR+Up")') is None and mlog(W)[-1] == "delete DSF ZR+Up" and W.eval('HDR("wc-ftm-%d")' % S) is None
      and W.eval('HDR("wc-ft-%d")' % S).endswith("/cast Raptor Strike(Rank 2)"), "g. ... our macro deleted, the plain press", mlog(W)[-1])
sl = slot_doc(W, 3, 2)
check(sl.get('sr') is None and 'sr' not in sl.get('c', {}), "g. the document: box off")
ok = W.eval('(PM.srToggle(1, 3, 2, true))')
e = tbl(W.eval('FT(%d)' % S))
check(ok is True and e.get('h') == 'm' and e.get('rg', {}).get('n') == 'Arcane Shot' and W.eval('body("DSF ZR+Up")') is not None,
      "g. on again: the pair is back (Raptor Strike / Arcane Shot) and its macro made again", e)

# ================================================================ f. every character macro in use: refused, nothing changed
F2 = boot()
F2.execute('for i = 1, MAXC do mymac()[i] = { name = "Mine " .. i, icon = 1, body = "/say " .. i } end')
F2.execute('PM.srToggle(1, 3, 2, true); PM.srPut(1, 3, 2, "m", bs(2973))')
a0 = int(F2.eval('#SENT'))
before = tbl(F2.eval('FT(%d)' % S))
ok = F2.eval('(PM.srPut(1, 3, 2, "r", bs(3044)))')
check(ok is False and acks(F2, a0, 'srput')[-1].get('why') == 'full' and tbl(F2.eval('FT(%d)' % S)) == before and mlog(F2) == [],
      "f. 18 of 18 character macros in use: the Range half refused 'full', the button as it was, no macro call", acks(F2, a0, 'srput'))
F2.execute('PT.enter("buttons"); pp("PADRSHOULDER"); PT.touchSet(3); PT.touchPos(2); PT.srPick(); PT.touchSr(2); PT.combatLayer(); PT.touchSpell(3044)')
check(ctl(F2).get('note') == 'sr-full' and tbl(F2.eval('FT(%d)' % S)) == before, "f. by touch too: the room's note 'sr-full'", ctl(F2).get('note'))
check(all(F2.eval('body("Mine %d")' % i) == "/say %d" % i for i in range(1, 19)), "f. the player's 18 untouched")
clean(F2, "f")

# ================================================================ h. clear a half, Move a half, clear the button
ok = W.eval('(PM.srClear(1, 3, 2, "m"))')
e = tbl(W.eval('FT(%d)' % S))
check(ok is True and e.get('h') == 'r' and e.get('n') == 'Arcane Shot' and e.get('rg') is None and W.eval('body("DSF ZR+Up")') is None,
      "h. the Melee half cleared: Arcane Shot alone (h r), our macro deleted (the pair broken)", e)
W.execute('PM.srPut(1, 3, 2, "m", bs(14260))')
check(W.eval('body("DSF ZR+Up")') is not None, "h. Raptor Strike back: the pair and its macro")
S3 = W.eval('WowCompanion.spellsStorageFor(1, 3, 4)')                         # ZR + D-pad Down
ok = W.eval('(PM.srMove(1, 3, 2, "r", 1, 3, 4))')
e3, e2 = tbl(W.eval('FT(%d)' % S3)), tbl(W.eval('FT(%d)' % S))
check(ok is True and e3.get('sr') == 1 and e3.get('h') == 'r' and e3.get('n') == 'Arcane Shot' and e2.get('rg') is None and e2.get('n') == 'Raptor Strike',
      "h. Move the Range half to ZR + Down: its Range half there (its box checked), Raptor Strike alone here", (e2, e3))
check(W.eval('body("DSF ZR+Up")') is None and W.eval('nmac()') == 0, "h. no pair left: our macro deleted", mlog(W)[-2:])
ok = W.eval('(PM.srMove(1, 3, 4, "r", 1, 3, 2))')
check(ok is True and W.eval('body("DSF ZR+Up")') is not None and W.eval('nmac()') == 1, "h. ... and back: the pair and its macro again", mlog(W)[-1])
# a whole-button Move (WoW Defaults' Move road): the macro is renamed with it
W.execute('PM.move(1, 3, 2, 1, 2, 4)')
S4 = W.eval('WowCompanion.spellsStorageFor(1, 2, 4)')
check(tbl(W.eval('FT(%d)' % S4)).get('rg', {}).get('n') == 'Arcane Shot' and W.eval('body("DSF ZL+Down")') is not None
      and W.eval('body("DSF ZR+Up")') is None and mlog(W)[-1] == "edit DSF ZR+Up -> DSF ZL+Down" and W.eval('nmac()') == 1,
      "h. the whole button moved to ZL + Down: the pair goes, our macro RENAMED (one edit, no new slot)", mlog(W)[-2:])
check(W.eval('WowCompanionSpellsDB.srBy[WHOK()]["s%d"]' % S) is None, "h. no box left behind on ZR + Up")
W.execute('PM.move(1, 2, 4, 1, 3, 2)')
ok = W.eval('(PM.clearCombat(1, 3, 2))')
check(ok is True and W.eval('FT(%d)' % S) is None and W.eval('nmac()') == 0 and mlog(W)[-1] == "delete DSF ZR+Up",
      "h. Clear combat spell (the whole button): the pair gone, our macro deleted", mlog(W)[-1])
clean(W, "h")

# ================================================================ j. never a player's macro; per character
J = boot()
J.execute('mymac()[1] = { name = "DSF ZR+Up", icon = 1, body = "/say mine" }; mymac()[2] = { name = "Pull", icon = 2, body = "/cast Arcane Shot" }')
J.execute('PM.srToggle(1, 3, 2, true); PM.srPut(1, 3, 2, "m", bs(2973))')
a0 = int(J.eval('#SENT'))
ok = J.eval('(PM.srPut(1, 3, 2, "r", bs(3044)))')
check(ok is False and acks(J, a0, 'srput')[-1].get('why') == 'taken' and J.eval('body("DSF ZR+Up")') == "/say mine" and mlog(J) == [],
      "j. a player's macro with our name: refused 'taken', never edited or deleted", acks(J, a0, 'srput'))
J.execute('PM.srToggle(1, 3, 2, false); PM.clearCombat(1, 3, 2); PM.srSync()')
check(J.eval('body("DSF ZR+Up")') == "/say mine" and J.eval('body("Pull")') == "/cast Arcane Shot" and mlog(J) == [],
      "j. the clean-ups never touch the player's macros", mlog(J))
J.execute('mymac()[1] = nil; mymac()[1] = mymac()[2]; mymac()[2] = nil')
J.execute('PM.srToggle(1, 3, 2, true); PM.srPut(1, 3, 2, "m", bs(2973)); PM.srPut(1, 3, 2, "r", bs(3044))')
check(J.eval('body("DSF ZR+Up")') is not None, "j. Korvash's pair made")
J.execute('WHO = "Other"; fire("PLAYER_ENTERING_WORLD", false, true); runTimers(NOW + 3); WowCompanion.padMap.emit(true)')
check(J.eval('FT(%d)' % S) is None and slot_doc(J, 3, 2).get('sr') is None and J.eval('nmac()') == 0 and mlog(J)[-1:] == ["create DSF ZR+Up"],
      "j. another character: no box, no entry, no macro made or deleted (their own macro list)", mlog(J)[-2:])
J.execute('WHO = "Korvash"; fire("PLAYER_ENTERING_WORLD", false, true); runTimers(NOW + 3)')
check(J.eval('body("DSF ZR+Up")') is not None and tbl(J.eval('FT(%d)' % S)).get('rg', {}).get('n') == 'Arcane Shot',
      "j. back on Korvash: the pair and its macro as they were")
clean(J, "j")

# ================================================================ k2 / l. the base Y: the controller's picker, the face press
Y = boot()
Y.execute('PT.enter("buttons"); pp("PADRSHOULDER"); PT.touchSet(1); PT.touchPos(6); pp("PAD4")')
check(slot_doc(Y, 1, 6).get('sr') is True, "l. Y on the base Y: the box")
Y.execute('pp("PAD1")')
check(ctl(Y).get('srp') == 1 and ctl(Y).get('note') == 'sr-picker' and not ctl(Y).get('chooser'), "l. A first asks Melee or Range (the picker on Melee)", ctl(Y))
Y.execute('pp("PAD1")')
c = ctl(Y)
check(c.get('srh') == 'm' and c.get('chooser', {}).get('list') == 'spells' and c.get('srp') is None,
      "l. A on Melee: the chooser for the Melee half (srh m)", c)
for _ in range(12):
    if ctl(Y)['chooser']['id'] == 2974:
        break
    Y.execute('pp("PADDDOWN")')
Y.execute('pp("PAD1")')
check(ctl(Y).get('note') == 'sr-swing' and Y.eval('FACEE(6)') is None, "l. Wing Clip chosen for Melee: the note 'sr-swing', nothing put", ctl(Y).get('note'))
Y.execute('pp("PAD1"); pp("PAD1")')
for _ in range(12):
    if ctl(Y)['chooser']['id'] == 2973:
        break
    Y.execute('pp("PADDUP")')
Y.execute('pp("PAD1")')
check(Y.eval('FACEE(6).n') == "Raptor Strike" and Y.eval('FACEE(6).h') == "m" and ctl(Y).get('srh') == 'm', "l. Raptor Strike in Y's Melee half")
dump(Y, "on_none", lambda c: c.pop('srh', None))
Y.execute('pp("PAD1"); pp("PADDRIGHT")')
check(ctl(Y).get('srp') == 2, "l. the picker: Right = Range")
Y.execute('pp("PAD1")')
for _ in range(12):
    if ctl(Y)['chooser']['id'] == 3044:
        break
    Y.execute('pp("PADDDOWN")')
Y.execute('pp("PAD1")')
check(Y.eval('body("DSF Y")') == BODY and Y.eval('FACEE(6).rg.n') == "Arcane Shot", "l. Arcane Shot in Y's Range half: the macro 'DSF Y'", Y.eval('body("DSF Y")'))
check(Y.eval('router(6).a["wc-fight-mname"]') == "DSF Y" and Y.eval('router(6).a["wc-fight-type"]') == "macro",
      "k. the face button is armed to run 'DSF Y' (wc-fight-mname, type macro)", Y.eval('router(6).a["wc-fight-mname"]'))
cf = slot_doc(Y, 1, 6).get('cf', {})
check(cf.get('sr') == 1 and cf.get('id') == 2973 and cf.get('rg', {}).get('id') == 3044, "l. the document: cf with both halves", cf)
dump(Y, "on_both")
Y.execute('fightOn()')
r0 = int(Y.eval('#RAN'))
Y.execute('hit(6)')
check(ran(Y, r0)[:2] == ["runmacro:DSF Y", "m:/run DSF_SR0()"] and Y.eval('router(6).a["wc-kind"]') == "fight",
      "k. in a fight the base Y runs 'DSF Y'", ran(Y, r0))
Y.execute('fightOff()')
# X moves the picked half: Range onto ZR + Y
Y.execute('pp("PAD3")')
check(ctl(Y).get('carry', {}).get('h') == 'r', "l. X: the picked (Range) half is carried", ctl(Y).get('carry'))
Y.execute('PT.touchSet(3); PT.touchPos(6); pp("PAD1")')
SY = Y.eval('WowCompanion.spellsStorageFor(1, 3, 6)')
check(ctl(Y).get('note') == 'moved' and tbl(Y.eval('FT(%d)' % SY)).get('n') == 'Arcane Shot' and Y.eval('FACEE(6).rg') is None
      and Y.eval('body("DSF Y")') is None, "l. A on ZR + Y: Arcane Shot moved there (its Range half), Y's macro gone", ctl(Y).get('note'))
# touch: padtab 39 + code
Y.execute('PT.touchSet(1); PT.touchPos(6)')
Y.execute('padtab(39); padtab(2)')
check(ctl(Y).get('srh') == 'r' and ctl(Y).get('note') == 'touch-sr-half', "l. touch: padtab 39 then 2 = pick Range (no game action)", ctl(Y))
Y.execute('padtab(39); padtab(0)')
check(slot_doc(Y, 1, 6).get('sr') is None and Y.eval('FACEE(6).n') == 'Raptor Strike', "l. touch: padtab 39 then 0 = the box off (Raptor Strike stays)")
check(ctl(Y).get('view') == 'combat', "l. a padtab 0 after the pick is NOT the WoW Defaults tab")
dump(Y, "off")
clean(Y, "l")

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
