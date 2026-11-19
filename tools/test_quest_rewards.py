"""THE QUEST'S REWARDS in the quests document (WowCompanionQuests/Quests.lua q63; the user, 2026-11-17: "The quests don't show rewards at
all anywhere"), off-device: the REAL files under a stub game whose quest log and reward APIs the test sets.

    python tools/test_quest_rewards.py

Checks: each quest carries rw (money, XP, honor, the choose-one items, the fixed items, an unknown spell) read BY questID; a quest with
none carries no rw; nothing is ever selected (SelectQuestLogEntry / C_QuestLog.SetSelectedQuest never called, no protected call); the
read is cached per quest and read again on QUEST_LOG_UPDATE / PLAYER_LEVEL_UP; an item whose name has not loaded is not kept and
GET_ITEM_INFO_RECEIVED asks again; ShouldShowQuestRewards false = no rw; every quest answering alike (a build ignoring the questID) =
dropped, rwMode "ignored"; each new reward item's tooltip goes out once as {"type":"qrtip"}, never in a fight. Needs lupa. Exit code 1
on any failure."""
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
function GetQuestLogSpecialItemInfo() return nil end
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
    SetSelectedQuest = function() SELECTS = SELECTS + 1 end,
    GetSelectedQuest = function() return 0 end,
    ShouldShowQuestRewards = function(id) return not HIDE[id] end,
}
C_SuperTrack = { GetSuperTrackedQuestID = function() return 0 end }
C_Map = { GetBestMapForUnit = function() return 1429 end }

-- THE REWARDS, by questID: RW[id] = { m, xp, hon, ch = {{name, icon, count, quality, usable, id}}, it = {...}, sp = {spellID...} }
RW, HIDE, CALLS = {}, {}, 0
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
    """the document as JSON (what goes on the wire)"""
    return json.loads(L.eval('WowCompanion.encode((WowCompanion.collectQuests()))'))


def byId(doc):
    return {q['id']: q for q in doc['list']}


def docs(L, start=0):
    return [json.loads(L.eval('SENT[%d]' % i)) for i in range(start + 1, int(L.eval('#SENT')) + 1)]


L = setup()
L.execute('''
LOG = { {id = 33, title = "Wolves Across the Border"}, {id = 46, title = "Bounty on Murlocs"}, {id = 7, title = "Kobold Camp Cleanup"} }
RW[33] = { m = 350, xp = 450, ch = { {"Fur-lined Boots", 132539, 1, 2, true, 2032}, {"Hunting Gloves", 132952, 1, 2, false, 2033} },
           it = { {"Tough Wolf Meat", 133970, 5, 1, true, 750} }, sp = { 8017, 403 } }
RW[46] = { m = 10500, xp = 1200, hon = 25 }
KNOWN[403] = true
''')
d = collect(L)
q = byId(d)
r = q[33].get('rw')
check(r is not None and r.get('m') == 350 and r.get('xp') == 450, "q63: quest 33 carries its money and XP", r)
check(r is not None and [c['n'] for c in r.get('ch', [])] == ["Fur-lined Boots", "Hunting Gloves"],
      "the choose-one items, in the game's order", r and r.get('ch'))
check(r is not None and r['ch'][0] == {"n": "Fur-lined Boots", "ic": 132539, "c": 1, "q": 2, "id": 2032},
      "an item: name, icon, count, quality, item id (usable: no u)", r and r['ch'][0])
check(r is not None and r['ch'][1].get('u') is False, "an item the character cannot use says u = false", r and r['ch'][1])
check(r is not None and r.get('it') == [{"n": "Tough Wolf Meat", "ic": 133970, "c": 5, "q": 1, "id": 750}], "the fixed item with its count",
      r and r.get('it'))
check(r is not None and r.get('sp') == [{"n": "Spell 8017", "ic": 144017, "id": 8017}], "the spell the character does not know (403 known: left out)",
      r and r.get('sp'))
check(q[46].get('rw') == {"m": 10500, "xp": 1200, "hon": 25}, "money, XP and honor alone", q[46].get('rw'))
check('rw' not in q[7], "a quest with no rewards carries no rw", q[7].get('rw'))
check(d.get('rwMode') == 'id', "the rewards are believed (rwMode id)", d.get('rwMode'))
check(int(L.eval('SELECTS')) == 0, "nothing was selected (no SelectQuestLogEntry / SetSelectedQuest), nothing protected", L.eval('SELECTS'))

# 2. the cache: another look reads nothing again; QUEST_LOG_UPDATE / PLAYER_LEVEL_UP read again
c0 = int(L.eval('CALLS'))
collect(L)
check(int(L.eval('CALLS')) == c0, "a second look within the same log reads no reward again (cached per quest)", int(L.eval('CALLS')) - c0)
L.execute('RW[33].m = 400')
L.execute('fire("QUEST_LOG_UPDATE")')
check(byId(collect(L))[33]['rw']['m'] == 400 and int(L.eval('CALLS')) > c0, "QUEST_LOG_UPDATE: read again (the new money)")
L.execute('RW[33].xp = 300')
L.execute('fire("PLAYER_LEVEL_UP")')
check(byId(collect(L))[33]['rw']['xp'] == 300, "PLAYER_LEVEL_UP: read again (the XP falls with the level)")

# 2b. in a fight a kill's QUEST_LOG_UPDATE reads nothing again; the fight's end does
L.execute('runTimers(NOW + 1)')
collect(L)
c2 = int(L.eval('CALLS'))
L.execute('FIGHT = true; RW[33].m = 500; fire("QUEST_LOG_UPDATE")')
check(byId(collect(L))[33]['rw']['m'] == 400 and int(L.eval('CALLS')) == c2, "in a fight: the rewards read before it stand (no read)",
      int(L.eval('CALLS')) - c2)
L.execute('FIGHT = false; fire("PLAYER_REGEN_ENABLED"); runTimers(NOW + 1)')
check(byId(collect(L))[33]['rw']['m'] == 500, "the fight's end: read again")

# 3. an item whose name has not loaded: shown by its id, not kept; GET_ITEM_INFO_RECEIVED asks for the document again
L.execute('RW[46].it = { {nil, 134400, 1, 3, true, 6256} }; fire("QUEST_LOG_UPDATE"); runTimers(NOW + 1)')
r46 = byId(collect(L))[46]['rw']
check(r46.get('it') == [{"ic": 134400, "c": 1, "q": 3, "id": 6256}], "a nameless item still goes, by its id", r46.get('it'))
c1 = int(L.eval('CALLS'))
collect(L)
check(int(L.eval('CALLS')) > c1, "the nameless read was not kept (read again next look)")
L.execute('runTimers(NOW + 1); collect0 = WowCompanion.collectQuests(); fire("GET_ITEM_INFO_RECEIVED")')
check(int(L.eval('#TIMERS')) == 1, "GET_ITEM_INFO_RECEIVED while a name is missing: the document is asked for again", int(L.eval('#TIMERS')))
L.execute('RW[46].it[1][1] = "Fishing Pole"; runTimers(NOW + 1)')
collect(L)
L.execute('fire("GET_ITEM_INFO_RECEIVED")')
check(int(L.eval('#TIMERS')) == 0, "with every name read, GET_ITEM_INFO_RECEIVED does nothing", int(L.eval('#TIMERS')))

# 4. the game says not to show them
L.execute('HIDE[46] = true; fire("QUEST_LOG_UPDATE")')
check('rw' not in byId(collect(L))[46], "ShouldShowQuestRewards false: no rw")
L.execute('HIDE[46] = nil')

# 5. a build that ignores the questID: every quest answers the same = not believed
L.execute('''
local same = { m = 99, xp = 10, it = { {"Linen Cloth", 132889, 2, 1, true, 2589} } }
RW[33], RW[46], RW[7] = same, same, same
fire("QUEST_LOG_UPDATE")
''')
d = collect(L)
check(d.get('rwMode') == 'ignored' and all('rw' not in x for x in d['list']), "every quest alike: dropped, rwMode ignored",
      [x.get('rw') for x in d['list']])
L.execute('''
local pay = { m = 75, xp = 170 }
RW[33], RW[46], RW[7] = pay, pay, pay
fire("QUEST_LOG_UPDATE")
''')
d = collect(L)
check(d.get('rwMode') == 'id' and all(x.get('rw') == {"m": 75, "xp": 170} for x in d['list']),
      "three quests of a chain paying alike (money / XP alone): believed", d.get('rwMode'))

# 6. the tooltips: each new reward item once, as qrtip, the compare dropped; none in a fight
L.execute('''
RW[33] = { ch = { {"Lupine Cuffs", 132600, 1, 2, true, 5001}, {"Wolfskin Cap", 133100, 1, 2, true, 5002} },
           it = { {"Wolf Fang", 133970, 5, 1, true, 5003} } }
RW[46], RW[7] = { m = 5 }, {}
TIPS = {}
fire("QUEST_LOG_UPDATE")
FIGHT = true                                     -- the fight starts before the document goes
''')
n0 = int(L.eval('#SENT'))
L.execute('runTimers(NOW + 0.3)')                # the coalesced emit
L.execute('NOW = NOW + 5; runTimers(NOW)')
check(int(L.eval('#TIPS')) == 0, "in a fight no tooltip is read", int(L.eval('#TIPS')))
L.execute('FIGHT = false; NOW = NOW + 5; runTimers(NOW); NOW = NOW + 5; runTimers(NOW)')
tips = [x for x in docs(L, n0) if x.get('type') == 'qrtip']
check(sorted(x['id'] for x in tips) == [5001, 5002, 5003], "after the fight: one qrtip per new reward item", [x['id'] for x in tips])
check(all('cmp' not in x['tip'] and x['tip'].get('key') == 'link item:%d' % x['id'] and x['tip'].get('lines') for x in tips),
      "each tip: the game's lines by the item's link key, no compare", tips[:1])
check(all(k.startswith('link item:') for k in L.eval('TIPS').values()), "Interact's reader asked by item link only")
n1 = int(L.eval('#SENT'))
L.execute('fire("QUEST_LOG_UPDATE"); runTimers(NOW + 0.3); NOW = NOW + 5; runTimers(NOW)')
check(not [x for x in docs(L, n1) if x.get('type') == 'qrtip'], "an item already sent is not sent again")
check(int(L.eval('SELECTS')) == 0, "still nothing selected", L.eval('SELECTS'))

src = open(QUESTS, encoding='utf-8').read()
check('local QV = "q70"' in src and "q63 THE QUEST'S REWARDS (the user, 2026-11-17: \"The quests don't show rewards at all anywhere\")" in src, "Quests.lua says q70, q63's note quoting the user kept")
body = src[src.index("-- ---------------------------------------------------------------- q63 THE QUEST'S REWARDS"):src.index("-- q38: the words")]
check(not any(w in body for w in ("SelectQuestLogEntry(", "SetSelectedQuest(", "SetAbandonQuest", "CastSpell", "RunMacro", "SetBinding")),
      "the q63 reader calls nothing that selects or acts")

print("ALL PASS" if fails == 0 else ("%d FAILED" % fails))
sys.exit(1 if fails else 0)
