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
CVARS = { Sound_EnableErrorSpeech = "1", Sound_EnableSFX = "1" }
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
            if nm == "DSFSR0" or nm == "DSFSR1" then
                -- m149: the macro's own lines on our plain buttons (no secure attributes): logged, then the button's OnClick
                RAN[#RAN + 1] = how .. ":" .. line
                local sc = type(t) == "table" and type(t.GetScript) == "function" and t:GetScript("OnClick")
                if sc then sc(t, "LeftButton", false) elseif type(t) == "table" and type(t.Click) == "function" then t:Click() end
            elseif type(t) == "table" and t.a then doClick(t, btn ~= "" and btn or "LeftButton", d == "1" or (d == "" and true) or false) end
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


def boot(macros_loaded=True, pre=None):
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(GAME)
    L.execute(EXTRA)
    if pre:
        L.execute(pre)                                                # m147: a later load's saved state (and the macro window's)
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for k in ("safety", "tappad", "book", "padmap", "padtabctl"):
        load(SRC[k], '@' + os.path.basename(F[k]))(os.path.basename(F[k]))
    L.execute(WORLD_HELPERS)
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
    if macros_loaded:
        L.execute('fire("UPDATE_MACROS"); runTimers(NOW + 1)')       # the game: the macros arrive (m147 waits for them)
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
check('local PV = "m157"' in SRC["padmap"] and 'local CV = "p52"' in SRC["padtabctl"] and 'local BV = "b57"' in SRC["book"],
      "the versions: PadMap m157, PadTabCtl p52, Book b57")
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
BODY = "#showtooltip\n/click DSFSR0\n/cast Raptor Strike(Rank 1)\n/cast Arcane Shot(Rank 1)\n/click DSFSR1"
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
check(got[1:] == ["m:/click DSFSR0", "m:/cast Raptor Strike(Rank 1)", "m:/cast Arcane Shot(Rank 1)", "m:/click DSFSR1"],
      "k. its lines in order: SR0, Raptor Strike, Arcane Shot, SR1", got)
CVL = [W.eval('CVLOG[%d]' % i) for i in range(1, int(W.eval('#CVLOG')) + 1)]
check(CVL[:2] == ["Sound_EnableErrorSpeech=0", "Sound_EnableSFX=0"] and sorted(CVL[2:]) == ["Sound_EnableErrorSpeech=1", "Sound_EnableSFX=1"]
      and W.eval('CVARS.Sound_EnableErrorSpeech') == "1" and W.eval('CVARS.Sound_EnableSFX') == "1" and W.eval('CLEARS') >= 1,
      "d. SR0 turned the error speech AND the error sound (m147: Sound_EnableSFX) off, SR1 gave the player's own values back (on), "
      "the error text cleared", (CVL, W.eval('CLEARS')))
W.execute('trig(false, false); hit(2)')
check(W.eval('router(2).a["macro"]') is None, "k. a bare D-pad Up press in the same fight: no saved macro (the attribute cleared)")
W.execute('trig(false, false); fightOff()')
clean(W, "k")

# ================================================================ d. SR0 / SR1: already off, a missed SR1
W.execute('CVARS.Sound_EnableErrorSpeech = "0"; CVARS.Sound_EnableSFX = "0"; CVLOG = {}; DSF_SR0(); DSF_SR1()')
check(W.eval('#CVLOG') == 0 and W.eval('CVARS.Sound_EnableErrorSpeech') == "0" and W.eval('CVARS.Sound_EnableSFX') == "0",
      "d. the player's own OFF (both): never written, never forced on")
W.execute('CVARS.Sound_EnableErrorSpeech = "1"; CVARS.Sound_EnableSFX = "0"; CVLOG = {}; DSF_SR0(); DSF_SR1()')
check(W.eval('CVARS.Sound_EnableErrorSpeech') == "1" and W.eval('CVARS.Sound_EnableSFX') == "0" and W.eval('#CVLOG') == 2,
      "d. m147: speech on, sound effects off (the player's own): the sound effects never written, speech given back", W.eval('#CVLOG'))
W.execute('CVARS.Sound_EnableSFX = nil; CVLOG = {}; DSF_SR0(); DSF_SR1(); CVARS.Sound_EnableSFX = "1"')
check(W.eval('#CVLOG') == 2 and W.eval('CVLOG[1]') == "Sound_EnableErrorSpeech=0", "d. a CVar the client does not have: skipped", W.eval('#CVLOG'))
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
check(W.eval('body("DSF ZR+Up")').endswith("Arcane Shot(Rank 1)\n/click DSFSR1") and W.eval('#VIOL') == 0,
      "i. Arcane Shot Rank 2 learned IN a fight: not rewritten yet (no lockdown write)", W.eval('body("DSF ZR+Up")'))
a0 = int(W.eval('#SENT'))
ok = W.eval('(PM.srPut(1, 3, 2, "r", bs(1978)))')
check(ok is False and acks(W, a0, 'srput')[-1].get('why') == 'combat', "i. a put in combat: refused 'combat'")
W.execute('fightOff(); runTimers(NOW + 3)')
check(W.eval('body("DSF ZR+Up")') == "#showtooltip\n/click DSFSR0\n/cast Raptor Strike(Rank 2)\n/cast Arcane Shot(Rank 2)\n/click DSFSR1",
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
# m148 / p48 (the user: "it should only list the abilities that can work with it in the melee options"): the Melee chooser's rows
seen = []
for _ in range(16):
    i = ctl(Y)['chooser']['id']
    if i in seen:
        break
    seen.append(i)
    Y.execute('pp("PADDDOWN")')
check(set(seen) == {2973, 90001, 90004} and 2974 not in seen and 3044 not in seen,
      "l. p48: the Melee chooser lists ONLY the next-swing attacks known (Raptor Strike, the tooltip 'Next melee' ones): no Wing Clip", seen)
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
check(ran(Y, r0)[:2] == ["runmacro:DSF Y", "m:/click DSFSR0"] and Y.eval('router(6).a["wc-kind"]') == "fight",
      "k. in a fight the base Y runs 'DSF Y'", ran(Y, r0))
Y.execute('fightOff()')
# X on a base face: no Move there (the page shows none); X moves the picked half of a slot button: ZR + Y's Range onto ZR + X
Y.execute('pp("PAD3")')
check(ctl(Y).get('note') == 'locked' and not ctl(Y).get('carry'), "l. X on the base Y: no Move (the base X / Y / A stay)", ctl(Y).get('note'))
check(Y.eval('(PM.srMove(1, 1, 6, "r", 1, 3, 6))') is True, "l. (the add-on's own move: Y's Range half onto ZR + Y)")
SY = Y.eval('WowCompanion.spellsStorageFor(1, 3, 6)')
check(tbl(Y.eval('FT(%d)' % SY)).get('n') == 'Arcane Shot' and Y.eval('FACEE(6).rg') is None and Y.eval('body("DSF Y")') is None,
      "l. Arcane Shot is ZR + Y's Range half, Y's pair broken: its macro gone")
Y.execute('PT.touchSet(3); PT.touchPos(6); padtab(39); padtab(2); pp("PAD3")')
check(ctl(Y).get('carry', {}).get('h') == 'r', "l. X on ZR + Y with Range picked: the half is carried", ctl(Y).get('carry'))
Y.execute('PT.touchPos(5); pp("PAD1")')
SX = Y.eval('WowCompanion.spellsStorageFor(1, 3, 5)')
check(ctl(Y).get('note') == 'moved' and tbl(Y.eval('FT(%d)' % SX)).get('n') == 'Arcane Shot' and Y.eval('FT(%d)' % SY) is None,
      "l. A on ZR + X: Arcane Shot moved there (its Range half); ZR + Y left empty, its box still checked", ctl(Y).get('note'))
check(slot_doc(Y, 3, 6).get('sr') is True and slot_doc(Y, 3, 5).get('sr') is True, "l. both boxes checked")
# touch: padtab 39 + code
Y.execute('PT.touchSet(1); PT.touchPos(6)')
Y.execute('padtab(39); padtab(2)')
check(ctl(Y).get('srh') == 'r' and ctl(Y).get('note') == 'touch-sr-half', "l. touch: padtab 39 then 2 = pick Range (no game action)", ctl(Y))
Y.execute('padtab(39); padtab(0)')
check(slot_doc(Y, 1, 6).get('sr') is None and Y.eval('FACEE(6).n') == 'Raptor Strike', "l. touch: padtab 39 then 0 = the box off (Raptor Strike stays)")
check(ctl(Y).get('view') == 'combat', "l. a padtab 0 after the pick is NOT the WoW Defaults tab")
dump(Y, "off")
clean(Y, "l")

# ================================================================ m. PadMap m147 / PadTabCtl p47: the review of m146 / p46
# (the user, 2026-11-12: "okay so I was skeptical and so I tried it with a different melee skill, wing clip and it did not work")
check(all('I tried it with a different melee skill, wing clip and it did not work' in SRC[k] for k in ("padmap", "padtabctl")),
      "m. the m147 / p47 notes keep the user's words")
# m1. a login before the game has loaded the macros: nothing made; the macros arrive: ONE macro (ours read back, never a second copy)
SER = r"""
function ser(v)
    if type(v) == "table" then local t = {} for k, x in pairs(v) do t[#t + 1] = "[" .. ser(k) .. "]=" .. ser(x) end return "{" .. table.concat(t, ",") .. "}"
    elseif type(v) == "string" then return string.format("%q", v) else return tostring(v) end
end
"""
HIDE = r"""
MHIDE = true
local g1, g2, g3 = GetNumMacros, GetMacroInfo, GetMacroIndexByName
function GetNumMacros() if MHIDE then return 0, 0 end return g1() end
function GetMacroInfo(x) if MHIDE then return nil end return g2(x) end
function GetMacroIndexByName(n) if MHIDE then return 0 end return g3(n) end
"""
G = boot()
G.execute('PM.srToggle(1, 3, 2, true); PM.srPut(1, 3, 2, "m", bs(2973)); PM.srPut(1, 3, 2, "r", bs(3044))')
check(G.eval('nmac()') == 1 and G.eval('HDR("wc-ftm-%d")' % S) == "DSF ZR+Up", "m1. (the pair made: one macro, the router runs it)")
G.execute(SER)
SAVED = 'WowCompanionSpellsDB = ' + G.eval('ser(WowCompanionSpellsDB)') + '; CHARM = ' + G.eval('ser(CHARM)')
# the next load (a fresh Lua state): the saved variables and the server's macros there, the macros not yet given to the UI
G = boot(macros_loaded=False, pre=SAVED + HIDE + 'MLOG = {}')
check(mlog(G) == [] and G.eval('nmac()') == 1, "m1. a login before the game has loaded the macros: nothing made, renamed or deleted (m147 waits)", mlog(G))
G.execute('MHIDE = false; fire("UPDATE_MACROS"); runTimers(NOW + 2)')
check(mlog(G) == [] and G.eval('nmac()') == 1 and G.eval('HDR("wc-ftm-%d")' % S) == "DSF ZR+Up",
      "m1. the macros arrive (UPDATE_MACROS): our own read back, still ONE, the router runs it", (mlog(G), G.eval('nmac()')))
clean(G, "m1")
# m2. a second copy of ours (left by an older login): deleted by its index; the player's own untouched
G.execute('table.insert(mymac(), { name = "Mine", body = "/say mine" }); table.insert(mymac(), { name = "DSF ZR+Up", body = body("DSF ZR+Up") })')
G.execute('MLOG = {}; PM.srSync()')
names = [G.eval('mymac()[%d].name' % i) for i in range(1, int(G.eval('nmac()')) + 1)]
check(names == ["DSF ZR+Up", "Mine"] and mlog(G) == ["delete DSF ZR+Up"] and G.eval('body("Mine")') == "/say mine",
      "m2. two 'DSF ZR+Up' of ours: the second deleted, the player's 'Mine' kept", (names, mlog(G)))
check(G.eval('HDR("wc-ftm-%d")' % S) is None or G.eval('HDR("wc-ftm-%d")' % S) == "DSF ZR+Up", "m2. the router still names ours")
clean(G, "m2")

# m3. the base Y's saved macro name follows the sync (the face re-armed without a faceSetup)
Z = boot()
Z.execute('PM.srToggle(1, 1, 6, true); PM.srPut(1, 1, 6, "m", bs(2973)); PM.srPut(1, 1, 6, "r", bs(3044))')
check(Z.eval('router(6).a["wc-fight-mname"]') == "DSF Y", "m3. (Y's pair: the face runs 'DSF Y')")
Z.execute('for _, m in ipairs(mymac()) do if m.name == "DSF Y" then m.name = "My Y" end end')
Z.execute('for i = #mymac() + 1, 18 do mymac()[i] = { name = "Mine " .. i, body = "/say " .. i } end')
Z.execute('MLOG = {}; fire("UPDATE_MACROS"); runTimers(NOW + 2)')
check(Z.eval('router(6).a["wc-fight-mname"]') is None and Z.eval('FACEE(6).se') == "full" and mlog(Z) == [],
      "m3. the player renamed 'DSF Y' and the window is full: the face no longer names it (no faceSetup needed); nothing touched",
      (Z.eval('router(6).a["wc-fight-mname"]'), Z.eval('FACEE(6).se'), mlog(Z)))
Z.execute('fightOn()')
r0 = int(Z.eval('#RAN'))
Z.execute('hit(6)')
check(ran(Z, r0)[:2] == ["t:/click DSFSR0", "t:/cast Raptor Strike(Rank 1)"],
      "m3. in a fight Y runs the pair's own lines (the stand-in), never a missing / the player's macro", ran(Z, r0))
Z.execute('fightOff()')
Z.execute('for i, m in ipairs(mymac()) do if m.name == "Mine 18" then table.remove(mymac(), i) break end end')
Z.execute('MLOG = {}; fire("UPDATE_MACROS"); runTimers(NOW + 2)')
check(Z.eval('router(6).a["wc-fight-mname"]') == "DSF Y" and mlog(Z) == ["create DSF Y"] and Z.eval('body("My Y")') is not None,
      "m3. a slot free again: 'DSF Y' made again and the face names it at once; the player's 'My Y' untouched", mlog(Z))
clean(Z, "m3")

# m4. a Move in progress: a tap on the Smart Range plate / Melee / Range changes nothing on the hidden target button
Z = boot()                                                          # (a fresh load: Z's macro window is full)
Z.execute('PM.srToggle(1, 3, 2, true); PM.srPut(1, 3, 2, "m", bs(2973)); PM.srPut(1, 3, 2, "r", bs(3044))')
Z.execute('PT.enter("buttons"); pp("PADRSHOULDER"); PT.touchSet(3); PT.touchPos(2); padtab(39); padtab(2); pp("PAD3")')
check(ctl(Z).get('carry', {}).get('h') == 'r', "m4. (ZR + Up's Range half carried)", ctl(Z).get('carry'))
Z.execute('PT.touchPos(5)')
b5 = slot_doc(Z, 3, 5).get('sr')
Z.execute('padtab(39); padtab(0)')
check(ctl(Z).get('note') == 'sr-carry' and slot_doc(Z, 3, 5).get('sr') == b5 and ctl(Z).get('carry', {}).get('h') == 'r',
      "m4. padtab 39 + 0 while carrying: refused 'sr-carry', ZR + X's box as it was, the carry kept", ctl(Z).get('note'))
Z.execute('padtab(39); padtab(1)')
check(ctl(Z).get('note') == 'sr-carry' and ctl(Z).get('srh') is None, "m4. padtab 39 + 1 while carrying: no half picked", ctl(Z))
Z.execute('pp("PAD2")')
check(not ctl(Z).get('carry'), "m4. B: the carry cancelled")
# m5. a half's Clear goes straight off (no box naming the other half)
Z.execute('PT.touchPos(2); pp("PAD1"); pp("PADDRIGHT"); pp("PAD1")')
check(ctl(Z).get('srh') == 'r' and ctl(Z).get('chooser', {}).get('list') == 'spells', "m5. (the Range half's chooser)", ctl(Z))
for _ in range(16):
    if ctl(Z)['chooser']['id'] == 'uncombat':
        break
    Z.execute('pp("PADDDOWN")')
check(ctl(Z)['chooser']['id'] == 'uncombat', "m5. (its Clear row)", ctl(Z)['chooser'])
Z.execute('pp("PAD1")')
S5 = Z.eval('WowCompanion.spellsStorageFor(1, 3, 2)')
e5 = tbl(Z.eval('FT(%d)' % S5))
check(not ctl(Z).get('box') and e5.get('n') == 'Raptor Strike' and e5.get('rg') is None and Z.eval('body("DSF ZR+Up")') is None,
      "m5. Clear on the Range half: no box; Arcane Shot off, Raptor Strike stays, the pair's macro gone", (ctl(Z).get('box'), e5))
clean(Z, "m5")

# ================================================================ n. PadMap m148 / PadTabCtl p48: WHO HAS IT (the user, 2026-11-12: "this
# option should only appear for classes with next swing pairs- and it should only list the abilities that can work with it in the melee
# options"; "for druid this should only appear for the bear form action bar, and that's even if there is ranged attacks that bear druid
# can use. maybe taunt?")
check(all("this option should only appear for classes with next swing pairs" in SRC[k] and "for druid this should only appear for the bear form action bar" in SRC[k]
          for k in ("padmap", "padtabctl")), "n. the m148 / p48 notes quote the user")


def chooser_ids(L):
    seen = []
    for _ in range(24):
        i = ctl(L)['chooser']['id']
        if i in seen:
            break
        seen.append(i)
        L.execute('pp("PADDDOWN")')
    return seen


def sro(L):
    L.execute('WowCompanion.padMap.emit(true)')
    return docs(L, 'padmap')[-1]['m'].get('sro')


# n1. a class with no next-swing attack: no box anywhere, every Smart Range call refused, the room's Y / touch say so
STALE = 'WowCompanionSpellsDB = { srBy = { ["Korvash-Realm"] = { s26 = 1, f6 = 1 } } }'
for cls in ("PALADIN", "ROGUE", "MAGE", "SHAMAN", "PRIEST", "WARLOCK"):
    C = boot(pre='CLASS = "%s"; ' % cls + STALE)
    check(sro(C) is None and slot_doc(C, 3, 2).get('sr') is None and slot_doc(C, 1, 6).get('sr') is None,
          "n1. %s: no `sro` in the doc, no box on ZR + Up or the base Y (an old side-map box ignored)" % cls, sro(C))
    a0 = int(C.eval('#SENT'))
    r1 = C.eval('PM.srToggle(1, 3, 2, true)')
    r2 = C.eval('PM.srPut(1, 3, 2, "m", bs(2973))')
    r3 = C.eval('PM.srClear(1, 3, 2, "m")')
    r4 = C.eval('PM.srToggle(1, 1, 6, true)')
    whys = [d.get('why') for d in acks(C, a0)]
    check(r1[0] is False and r1[1] == 'class' and r2[0] is False and r3[0] is False and r4[0] is False and whys == ['class'] * 4,
          "n1. %s: srToggle / srPut / srClear refused 'class'" % cls, whys)
    C.execute('PT.enter("buttons"); pp("PADRSHOULDER"); PT.touchSet(3); PT.touchPos(2); pp("PAD4")')
    n1 = ctl(C).get('note')
    C.execute('padtab(39); padtab(0)')
    n2 = ctl(C).get('note')
    C.execute('padtab(39); padtab(1)')
    check(n1 == 'sr-class' and n2 == 'sr-class' and ctl(C).get('note') == 'sr-class' and ctl(C).get('srh') is None
          and slot_doc(C, 3, 2).get('sr') is None, "n1. %s: Y / touch (padtab 39 + code) in the room: 'sr-class', nothing changed" % cls, (n1, n2))
    C.execute('pp("PAD1")')
    check(ctl(C).get('srp') is None and ctl(C).get('chooser', {}).get('list') == 'spells',
          "n1. %s: A on the button is the plain chooser (no Melee / Range picker)" % cls, ctl(C))
    C.execute('pp("PAD2"); PM.putCombat(1, 3, 2, bs(2973))')
    e = tbl(C.eval('FT(%d)' % S))
    check(e.get('n') == 'Raptor Strike' and e.get('sr') is None and C.eval('HDR("wc-ftm-%d")' % S) is None and C.eval('nmac()') == 0
          and 'sr' not in slot_doc(C, 3, 2).get('c', {}), "n1. %s: a combat spell is the plain assigned spell (no half, no macro)" % cls, e)
    clean(C, "n1 " + cls)
    if cls == "PALADIN":
        C.execute('PT.touchPos(2)')
        dump(C, "paladin")

# n2. Warrior / Hunter: the box on their buttons; a Warrior's stance set stays refused ("form"); the Melee chooser lists Heroic Strike / Cleave
WARRIOR = r'''
CLASS = "WARRIOR"
SPELLNAME[78] = "Heroic Strike"; SPELLNAME[845] = "Cleave"; SPELLNAME[100] = "Charge"; SPELLNAME[772] = "Rend"; SPELLNAME[2457] = "Battle Stance"
SUB[78] = "Rank 1"; SUB[845] = "Rank 1"; SUB[100] = "Rank 1"; SUB[772] = "Rank 1"
BOOK = { 78, 845, 100, 772 }
GetNumShapeshiftForms = function() return 1 end
GetShapeshiftFormInfo = function(x) if x == 1 then return 132349, false, true, 2457 end end
'''
Wr = boot(pre=WARRIOR)
check((sro(Wr) or {}).get('c') == 1 and sorted((sro(Wr) or {}).get('sw') or []) == [78, 845] and not (sro(Wr) or {}).get('f'),
      "n2. Warrior: `sro` c = 1 (its buttons), sw = Heroic Strike + Cleave, no form set", sro(Wr))
Wr.execute('PT.enter("buttons"); pp("PADRSHOULDER"); PT.touchSet(3); PT.touchPos(2); pp("PAD4")')
check(ctl(Wr).get('note') == 'sr-toggled' and slot_doc(Wr, 3, 2).get('sr') is True, "n2. Warrior: Y on ZR + Up checks the box", ctl(Wr).get('note'))
Wr.execute('pp("PAD1"); pp("PAD1")')
ids = chooser_ids(Wr)
check(ids == [78, 845], "n2. Warrior: the Melee chooser = Heroic Strike, Cleave (no Charge / Rend)", ids)
Wr.execute('pp("PAD2"); PT.touchSet(1); PT.touchForm(1); PT.touchPos(2); pp("PAD4")')
check(ctl(Wr).get('note') == 'sr-form' and Wr.eval('PM.srToggle(1, 1, 2, true, 2457)')[1] == 'form',
      "n2. Warrior: Battle Stance's own set stays refused ('form': the current build's stance rule)", ctl(Wr).get('note'))
clean(Wr, "n2")
H = boot()
check((sro(H) or {}).get('c') == 1 and set((sro(H) or {}).get('sw') or []) == {2973, 90001, 90004},
      "n2. Hunter: `sro` c = 1, sw = Raptor Strike + the tooltip 'Next melee' ones (never Wing Clip)", sro(H))

# n3. a Druid: never the caster's buttons / X / Y / A / Cat Form; the Bear Form set only (Maul + Growl), its own saved macro and press
DRUID = r'''
CLASS = "DRUID"
SPELLNAME[5487] = "Bear Form"; SPELLNAME[768] = "Cat Form"; SPELLNAME[6807] = "Maul"; SPELLNAME[6795] = "Growl"; SPELLNAME[5176] = "Wrath"
SPELLNAME[16857] = "Faerie Fire (Feral)"
SUB[6807] = "Rank 1"; SUB[6795] = "Rank 1"; SUB[5176] = "Rank 1"; SUB[16857] = "Rank 1"
BOOK = { 6807, 6795, 5176, 16857, 5487, 768 }
FORMNOW = 0
GetNumShapeshiftForms = function() return 2 end
GetShapeshiftFormInfo = function(x)
    if x == 1 then return 132276, FORMNOW == 1, true, 5487 end
    if x == 2 then return 132115, FORMNOW == 2, true, 768 end
end
local scop = SecureCmdOptionParse
function SecureCmdOptionParse(s)
    local n, rest = s:gsub("^%s+", ""):match("^%[form:(%d+)%]%s*(.*)$")
    if n then if tonumber(n) == FORMNOW then return rest end return nil end
    return scop(s)
end
function FFE(form, pos) local t = ((WowCompanionSpellsDB.formFightBy or {})[WHOK()] or {})[form]; return t and t[pos] end
'''
D = boot(pre=DRUID)
so = sro(D) or {}
check(so.get('c') is None and so.get('f') == [5487] and so.get('sw') == [6807],
      "n3. Druid: `sro` f = Bear Form only (no caster sets), sw = Maul", so)
D.execute('PT.enter("buttons"); pp("PADRSHOULDER"); PT.touchSet(3); PT.touchPos(2); pp("PAD4")')
n1 = ctl(D).get('note')
D.execute('PT.touchSet(1); PT.touchPos(6); pp("PAD4")')
n2 = ctl(D).get('note')
D.execute('PT.touchPos(2); pp("PAD4")')
check(n1 == 'sr-bear' and n2 == 'sr-bear' and ctl(D).get('note') == 'sr-bear' and slot_doc(D, 3, 2).get('sr') is None
      and slot_doc(D, 1, 6).get('sr') is None, "n3. Druid caster buttons (ZR + Up, base Y, bare Up): no box, 'sr-bear'", (n1, n2))
check(D.eval('PM.srToggle(1, 3, 2, true)')[1] == 'bear' and D.eval('PM.srToggle(1, 1, 6, true)')[1] == 'bear',
      "n3. Druid: the add-on refuses the caster's places too ('bear')")
D.execute('PT.touchForm(2); PT.touchPos(2); pp("PAD4")')
check(ctl(D).get('note') == 'sr-form' and D.eval('PM.srToggle(1, 1, 2, true, 768)')[1] == 'form', "n3. Druid: Cat Form's set refused ('form')",
      ctl(D).get('note'))
D.execute('PT.touchForm(1); PT.touchPos(2)')
a0 = int(D.eval('#SENT'))
D.execute('pp("PAD4")')
D.execute('WowCompanion.padMap.emit(true)')
m = docs(D, 'padmap')[-1]['m']
check(ctl(D).get('note') == 'sr-toggled' and {'f': 5487, 'p': 2} in (m.get('ffsr') or []) and D.eval('WowCompanionSpellsDB.srBy[WHOK()]["F5487:2"]') == 1,
      "n3. Bear Form's Up: Y checks its box (the doc's ffsr, the side map 'F5487:2')", (ctl(D).get('note'), m.get('ffsr')))
check(len(acks(D, a0, 'srange')) == 1 and acks(D, a0, 'srange')[0]['ok'] is True, "n3. one padack srange ok")
D.execute('pp("PAD1")')
check(ctl(D).get('srp') == 1, "n3. A on Bear's Up: the Melee / Range picker", ctl(D))
D.execute('pp("PAD1")')
ids = chooser_ids(D)
check(ctl(D).get('srh') == 'm' and ids == [6807], "n3. the Melee chooser lists ONLY Maul (no Growl / Wrath / Faerie Fire)", ids)
D.execute('pp("PAD1")')
e = tbl(D.eval('FFE(5487, 2)'))
check(e.get('n') == 'Maul' and e.get('sr') == 1 and e.get('h') == 'm' and 'page' not in e and D.eval('nmac()') == 0,
      "n3. Maul in Bear Up's Melee half (the form's own entry, no macro yet)", e)
dump(D, "bear_melee", lambda c: c.pop('srh', None))
D.execute('pp("PAD1"); pp("PADDRIGHT"); pp("PAD1")')
ids = chooser_ids(D)
check(ctl(D).get('srh') == 'r' and set(ids) >= {6795, 5176, 16857} and 6807 in ids,
      "n3. the Range chooser lists anything (Growl, Wrath, Faerie Fire (Feral), Maul)", ids)
for _ in range(24):
    if ctl(D)['chooser']['id'] == 6795:
        break
    D.execute('pp("PADDUP")')
D.execute('pp("PAD1")')
BB = "#showtooltip\n/click DSFSR0\n/cast Maul(Rank 1)\n/cast Growl(Rank 1)\n/click DSFSR1"
check(D.eval('body("DSF Bear Up")') == BB and mlog(D) == ["create DSF Bear Up"], "n3. Growl in the Range half: ONE macro 'DSF Bear Up', Maul first",
      (D.eval('body("DSF Bear Up")'), mlog(D)))
check(D.eval('HDR("wc-ffm-1-2")') == "DSF Bear Up" and D.eval('HDR("wc-ff-1-2")') == BB[len("#showtooltip\n"):],
      "n3. the router: wc-ffm-<form>-<pos> = our macro, wc-ff = its lines (the stand-in)", (D.eval('HDR("wc-ffm-1-2")'), D.eval('HDR("wc-ff-1-2")')))
D.execute('WowCompanion.padMap.emit(true)')
ff = [x for x in (docs(D, 'padmap')[-1]['m'].get('ff') or []) if x['f'] == 5487 and x['p'] == 2]
check(ff and ff[0].get('sr') == 1 and ff[0].get('h') == 'm' and ff[0].get('rg', {}).get('n') == 'Growl' and ff[0].get('mn') == 'DSF Bear Up',
      "n3. the doc's ff entry carries the halves (sr, h, rg) and the macro", ff)
fs = set(D.eval('WowCompanion.padMapFightSpells()').values())
check({6807, 6795} <= fs, "n3. the state list has both halves (Home's picture follows the press)", fs)
dump(D, "bear")
# the press: in Bear Form a bare Up runs our saved macro; in caster form it does not
D.execute('FORMNOW = 1; driveAll(); fightOn()')
r0 = int(D.eval('#RAN'))
D.execute('hit(2)')
got = ran(D, r0)
check(D.eval('router(2).a["wc-kind"]') == "slotfight" and D.eval('router(2).a["macro"]') == "DSF Bear Up" and got[:2] == ["runmacro:DSF Bear Up", "m:/click DSFSR0"]
      and got[2:4] == ["m:/cast Maul(Rank 1)", "m:/cast Growl(Rank 1)"], "n3. in Bear Form, in a fight, Up runs 'DSF Bear Up' (Maul then Growl)", got)
D.execute('FORMNOW = 0; driveAll()')
r0 = int(D.eval('#RAN'))
D.execute('hit(2)')
check(D.eval('router(2).a["macro"]') is None and "runmacro:DSF Bear Up" not in ran(D, r0), "n3. caster form: Up never runs the Bear macro", ran(D, r0))
D.execute('fightOff()')
# no Move of a Bear half; a spell put while the box is on goes into its half (putCombatForm)
D.execute('PT.touchPos(2); padtab(39); padtab(2); pp("PAD3")')
check(ctl(D).get('note') == 'locked' and not ctl(D).get('carry'), "n3. X on a Bear half: no Move (as the base X / Y / A)", ctl(D).get('note'))
ok = D.eval('(PM.srToggle(1, 1, 5, true, 5487))')
ok2 = D.eval('(PM.putCombatForm(5487, 5, bs(6807)))')
e5 = tbl(D.eval('FFE(5487, 5)'))
check(ok is True and ok2 is True and e5.get('sr') == 1 and e5.get('h') == 'm' and e5.get('n') == 'Maul',
      "n3. Bear's X box on, then the Spellbook / chooser's put: Maul goes into its Melee half", e5)
a0 = int(D.eval('#SENT'))
D.eval('PM.srPut(1, 1, 5, "m", bs(6795), 5487)')
check(acks(D, a0, 'srput')[-1].get('why') == 'swing' and tbl(D.eval('FFE(5487, 5)')).get('n') == 'Maul',
      "n3. Growl into the Melee half: refused 'swing' (the backstop)", acks(D, a0, 'srput'))
# clear: the whole entry (its box and macro go)
D.execute('PM.clearCombatForm(5487, 2)')
check(D.eval('FFE(5487, 2)') is None and D.eval('body("DSF Bear Up")') is None and D.eval('WowCompanionSpellsDB.srBy[WHOK()]["F5487:2"]') is None
      and D.eval('HDR("wc-ffm-1-2")') is None, "n3. Clear combat spell on Bear's Up: the pair, its box and 'DSF Bear Up' gone", mlog(D)[-1:])
clean(D, "n3")

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
