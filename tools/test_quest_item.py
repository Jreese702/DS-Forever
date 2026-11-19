"""THE QUEST'S USABLE ITEM in the quests document (WowCompanionQuests/Quests.lua q64; the user, 2026-11-17: "one thing that does make
sense though on the home screen quests is showing the Icon for if there is a usable quest Item that needs to be used for the quest"),
off-device: the REAL files under a stub game whose quest log and special-item APIs the test sets.

    python tools/test_quest_item.py

Checks: a quest whose log entry has a special item (GetQuestLogSpecialItemInfo by log index, as the game's ObjectiveTracker reads it)
carries si = { id, ic, c? }; a quest without one carries none; a complete quest keeps it only when the game says showItemWhenComplete
(QuestUtil.QuestShowsItemByIndex); a running cooldown (GetQuestLogSpecialItemCooldown) goes as cd = { d, l } with the seconds left
outside the change test (the document is not sent again as the seconds fall); enable 0 = held (h); BAG_UPDATE_COOLDOWN asks for the
document only while a quest has an item; nothing is selected or used. Needs lupa. Exit code 1 on any failure."""
import json
import os
import sys

from lupa import lua51 as lupa                    # the game's Lua is 5.1 (loadstring, unpack)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADDONS = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons")
SAFETY = os.path.join(ADDONS, "WowCompanion", "Safety.lua")
TAPPAD = os.path.join(ADDONS, "WowCompanion", "TapPad.lua")
QUESTS = os.path.join(ADDONS, "WowCompanionQuests", "Quests.lua")
fails = 0


def check(ok, name, got=''):
    global fails
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if not ok:
        fails += 1


STUB = r'''
local STUBMT = {}
STUB = setmetatable({}, STUBMT)
STUBMT.__index = function() return STUB end
STUBMT.__call = function() return nil end
setmetatable(_G, { __index = function(_, k) return STUB end })

NOW = 1000
function GetTime() return NOW end
function GetTimePreciseSec() return NOW end
function debugprofilestop() return NOW * 1000 end
function GetServerTime() return 1700000000 end
FIGHT = false
function InCombatLockdown() return FIGHT end
function UnitAffectingCombat() return FIGHT end
function IsInGroup() return false end
function UnitLevel() return 12 end
function UnitName() return "Tester" end
function UnitClass() return "Shaman", "SHAMAN" end
function GetProfessions() return nil end
function UnitQuestTrivialLevelRange() return 5 end
function GetPhysicalScreenSize() return 960, 540 end
function hooksecurefunc() end
function GetQuestLogQuestText() return nil end
-- THE QUEST ITEMS, by LOG INDEX (the tracker's own reads, Blizzard_ObjectiveTrackerShared.lua 107 / 120):
-- SI[i] = { link, icon, charges, showWhenComplete }; SICD[i] = { start, duration, enable }
SI, SICD, SIREADS, USES = {}, {}, 0, 0
function GetQuestLogSpecialItemInfo(i) SIREADS = SIREADS + 1; local t = SI[i]; if t then return t[1], t[2], t[3], t[4] end end
function GetQuestLogSpecialItemCooldown(i) local t = SICD[i]; if t then return t[1], t[2], t[3] end end
function UseQuestLogSpecialItem() USES = USES + 1 end
function IsQuestLogSpecialItemInRange() return 1 end
TIMERS = {}
C_Timer = {
    After = function(s, fn) TIMERS[#TIMERS + 1] = { at = NOW + s, fn = fn } end,
    NewTicker = function() return STUB end,
}
function runTimers(upto)
    local again = true
    while again do
        again = false
        for i, t in ipairs(TIMERS) do
            if t.at <= upto then table.remove(TIMERS, i); t.fn(); again = true; break end
        end
    end
end

FRAMES = {}
local Frame = {}
Frame.__index = function(t, k)
    if Frame[k] then return Frame[k] end
    if type(k) == "string" and k:match("^[A-Z]") then return function() return nil end end
    return nil
end
function Frame:CreateTexture() return setmetatable({}, Frame) end
function Frame:CreateFontString() return setmetatable({}, Frame) end
function Frame:GetWidth() return 960 end
function Frame:GetHeight() return 540 end
function Frame:IsShown() return false end
function Frame:RegisterEvent(e) rawset(self, "ev_" .. e, true); return true end
function Frame:HookScript() end
function Frame:SetScript(k, fn) rawset(self, "on" .. k, fn) end
UIParent = setmetatable({}, Frame)
function CreateFrame() local f = setmetatable({}, Frame); FRAMES[#FRAMES + 1] = f; return f end
-- the frame that hears QUEST_LOG_UPDATE and GET_ITEM_INFO_RECEIVED (the quests feed's own)
function fire(e)
    for _, f in ipairs(FRAMES) do
        if rawget(f, "ev_" .. e) and rawget(f, "ev_GET_ITEM_INFO_RECEIVED") and rawget(f, "onOnEvent") then f.onOnEvent(f, e) end
    end
end

-- what must never happen: a selection, or a protected call
SELECTS = 0
function SelectQuestLogEntry() SELECTS = SELECTS + 1 end
function SetAbandonQuest() SELECTS = SELECTS + 100 end

LOG = {}
C_QuestLog = {
    GetNumQuestLogEntries = function() return #LOG end,
    GetInfo = function(i) local q = LOG[i]; if q then return { questID = q.id, title = q.title, isHeader = false, isHidden = false, level = 10 } end end,
    GetLogIndexForQuestID = function(id) for i, q in ipairs(LOG) do if q.id == id then return i end end end,
    GetQuestWatchType = function() return 0 end,
    GetQuestObjectives = function() return {} end,
    GetQuestsOnMap = function() return {} end,
    IsComplete = function(id) return DONE[id] == true end,
    SetSelectedQuest = function() SELECTS = SELECTS + 1 end,
    GetSelectedQuest = function() return 0 end,
    ShouldShowQuestRewards = function(id) return not HIDE[id] end,
}
C_SuperTrack = { GetSuperTrackedQuestID = function() return 0 end }
C_Map = { GetBestMapForUnit = function() return 1429 end }

-- THE REWARDS, by questID: RW[id] = { m, xp, hon, ch = {{name, icon, count, quality, usable, id}}, it = {...}, sp = {spellID...} }
RW, HIDE, CALLS, DONE = {}, {}, 0, {}
local function rw(id) return RW[id] or {} end
function GetQuestLogRewardMoney(id) return rw(id).m or 0 end
function GetQuestLogRewardXP(id) return rw(id).xp or 0, rw(id).xp or 0 end
function GetQuestLogRewardHonor(id) return rw(id).hon or 0 end
function GetNumQuestLogChoices(id) return #(rw(id).ch or {}) end
function GetNumQuestLogRewards(id) return #(rw(id).it or {}) end
local function item(t) if t then return t[1], t[2], t[3], t[4], t[5], t[6] end end
function GetQuestLogChoiceInfo(i, id) CALLS = CALLS + 1; return item((rw(id).ch or {})[i]) end
function GetQuestLogRewardInfo(i, id) CALLS = CALLS + 1; return item((rw(id).it or {})[i]) end
KNOWN = {}
C_QuestInfoSystem = {
    GetQuestRewardSpells = function(id) return rw(id).sp or {} end,
    GetQuestRewardSpellInfo = function(id, sid) return { name = "Spell " .. sid, texture = 136000 + sid } end,
}
C_SpellBook = { IsSpellInSpellBook = function(sid) return KNOWN[sid] == true end }
Enum = { SpellBookSpellBank = { Player = 0 } }

SENT = {}
TIPS = {}
WowCompanion = { send = function(s) SENT[#SENT + 1] = s end, push = function() end,
                 emit = function(t) SENT[#SENT + 1] = WowCompanion.encode(t) end,
                 snapshotSoon = function() end,
                 tipOf = function(key, opt) TIPS[#TIPS + 1] = key
                     return { key = key, lines = { { t = "Binds when equipped", c = "ffffff" } }, cmp = { { slot = 6 } } } end }
'''



def setup():
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(STUB)
    for path in (SAFETY, TAPPAD, QUESTS):
        src = open(path, encoding='utf-8').read()
        fn = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')(src, '@' + path)
        fn(os.path.basename(path))
    return L


def collect(L):
    """the document as collectQuests returns it (the change test's view)"""
    return json.loads(L.eval('WowCompanion.encode((WowCompanion.collectQuests()))'))


def byId(doc):
    return {q['id']: q for q in doc['list']}


def sent(L, start=0):
    """the quests documents sent after SENT[start] (what goes on the wire)"""
    out = []
    for i in range(start + 1, int(L.eval('#SENT')) + 1):
        d = json.loads(L.eval('SENT[%d]' % i))
        if d.get('type') == 'quests':
            out.append(d['q'])
    return out


L = setup()
L.execute('''
LOG = { {id = 33, title = "Wolves Across the Border"}, {id = 26, title = "Lost Necklace"}, {id = 7, title = "Kobold Camp Cleanup"} }
SI[1] = { "|cffffffff|Hitem:23688::::::::12:::::|h[Shrine Scroll]|h|r", 134939, 3, false }
SI[2] = { "|cffffffff|Hitem:7342::::::::12:::::|h[Rough Torch]|h|r", 135432, 0, true }
''')
q = byId(collect(L))
check(q[33].get('si') == {"id": 23688, "ic": 134939, "c": 3}, "q64: the quest's item: id (from its link), icon, charges", q[33].get('si'))
check(q[26].get('si') == {"id": 7342, "ic": 135432}, "no charges (0): no c", q[26].get('si'))
check('si' not in q[7], "a quest with no item carries no si", q[7].get('si'))

# 2. complete: the game's rule (QuestUtil.QuestShowsItemByIndex): shown only with showItemWhenComplete
L.execute('DONE[33] = true; DONE[26] = true')
q = byId(collect(L))
check('si' not in q[33], "complete, showItemWhenComplete false: no item", q[33].get('si'))
check(q[26].get('si', {}).get('id') == 7342, "complete, showItemWhenComplete true: still shown", q[26].get('si'))
L.execute('DONE = {}')

# 3. the cooldown: start / length / enable; the seconds left only on the wire, outside the change test
L.execute('SICD[1] = { 990, 30, 1 }')
cd = byId(collect(L))[33]['si'].get('cd')
check(cd is not None and cd.get('d') == 30, "a running cooldown: its length", cd)
L.execute('fire("QUEST_LOG_UPDATE"); runTimers(NOW + 1)')
n0 = int(L.eval('#SENT'))
L.execute('SICD[1] = { 991, 30, 1 }; NOW = NOW + 1; fire("QUEST_LOG_UPDATE"); runTimers(NOW + 1)')
w = sent(L, n0)
si = w[-1]['list'][0].get('si', {}) if w else {}
check(bool(w) and si.get('cd', {}).get('l') is not None and abs(si['cd']['l'] - 20) < 0.11 and 's' not in si['cd'],
      "on the wire: cd = { d, l } (seconds left), no game clock", si)
n1 = int(L.eval('#SENT'))
L.execute('NOW = NOW + 2; fire("QUEST_LOG_UPDATE"); runTimers(NOW + 1)')
check(len(sent(L, n1)) == 0, "the seconds falling do not send the document again", len(sent(L, n1)))
L.execute('SICD[1] = { 990, 30, 0 }')
check(byId(collect(L))[33]['si'].get('cd', {}).get('h') is True, "enable 0 (held until the fight ends): h", byId(collect(L))[33]['si'])
L.execute('SICD[1] = { 0, 0, 1 }')
check('cd' not in byId(collect(L))[33]['si'], "ready: no cd", byId(collect(L))[33]['si'])

# 4. BAG_UPDATE_COOLDOWN: the quests feed hears it, asks for the document only while a quest has an item
fr = [f for f in L.eval('FRAMES').values() if f['ev_GET_ITEM_INFO_RECEIVED']]
check(bool(fr) and bool(fr[0]['ev_BAG_UPDATE_COOLDOWN']), "the quests feed registers BAG_UPDATE_COOLDOWN")
L.execute('runTimers(NOW + 5); WowCompanion.collectQuests(); fire("BAG_UPDATE_COOLDOWN")')
check(int(L.eval('#TIMERS')) == 1, "BAG_UPDATE_COOLDOWN with a quest item in the log: the document is asked for", int(L.eval('#TIMERS')))
L.execute('runTimers(NOW + 5); SI = {}; WowCompanion.collectQuests(); fire("BAG_UPDATE_COOLDOWN")')
check(int(L.eval('#TIMERS')) == 0, "no quest item in the log: BAG_UPDATE_COOLDOWN does nothing", int(L.eval('#TIMERS')))

# 5. odd values: never a crash, never a wrong item
L.execute('SI[1] = { 42, "x", nil, nil }')
check('si' not in byId(collect(L))[33], "a link that is not a string: no item")
check(int(L.eval('SELECTS')) == 0 and int(L.eval('USES')) == 0, "nothing selected, nothing used", (L.eval('SELECTS'), L.eval('USES')))

src = open(QUESTS, encoding='utf-8').read()
check('local QV = "q70"' in src and "showing the Icon for if there is a usable quest Item" in src, "Quests.lua says q70, q64's note quoting the user kept")
check("q63 THE QUEST'S REWARDS" in src, "q63's note kept below it")
mark = "-- ---------------------------------------------------------------- q64 THE QUEST'S ITEM"
part = src[src.index(mark):src.index("-- q63: THE REWARD ITEMS' TOOLTIPS")] if mark in src else ""
check(bool(part) and "UseQuestLogSpecialItem(" not in part and "SelectQuestLogEntry(" not in part, "the q64 reader only reads")

print("ALL PASS" if fails == 0 else ("%d FAILED" % fails))
sys.exit(1 if fails else 0)
