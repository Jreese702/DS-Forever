"""Home's focus through the tap pad (WowCompanionQuests/Quests.lua q48 + WowCompanion/TapPad.lua), off-device: the REAL files under a
stub game whose quest log and super-track the test sets.

    python tools/test_quests_focus.py

Checks (fix/focus-latency, test50): a qpick + qact FOCUS pair focuses the picked quest and its questack is followed by the quests
document IN THE SAME BURST (not 0.25 s later); a column whose quest left the log (a stale document) is refused as "holds no quest",
naming the column, and a fresh quests list follows the refusal even when nothing changed; a lost pick is still "no quest picked"; a
freed column is not handed to another quest inside TapPad's quarantine; while the route's pin stands the focus renames the pin's quest
and asks for the map snapshot at once. Needs lupa. Exit code 1 on any failure."""
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
-- anything the test does not model is a do-nothing stub: callable, indexable, and itself again
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
function InCombatLockdown() return false end
function UnitAffectingCombat() return false end
function IsInGroup() return false end
function UnitLevel() return 20 end
function UnitName() return "Tester" end
function UnitClass() return "Druid", "DRUID" end
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

local Frame = {}
-- a method (Capitalised, as the game's) is a no-op unless listed; a field (lower case: tex, pressedAt, ...) is the frame's own
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
function Frame:RegisterEvent() return true end
function Frame:HookScript() end
function Frame:SetScript(k, fn) rawset(self, "on" .. k, fn) end
UIParent = setmetatable({}, Frame)
function CreateFrame() return setmetatable({}, Frame) end

-- the quest log: LOG = { {id=, title=}, ... } in log order (no headers); FOCUS = the super-tracked quest (0 = none)
LOG, FOCUS = {}, 0
local function idx(id) for i, q in ipairs(LOG) do if q.id == id then return i end end end
C_QuestLog = {
    GetNumQuestLogEntries = function() return #LOG end,
    GetInfo = function(i) local q = LOG[i]; if q then return { questID = q.id, title = q.title, isHeader = false, isHidden = false, level = 20 } end end,
    GetLogIndexForQuestID = function(id) return idx(id) end,
    GetQuestWatchType = function(id) return 0 end,
    GetQuestObjectives = function() return {} end,
    GetQuestsOnMap = function() return {} end,
}
C_SuperTrack = {
    GetSuperTrackedQuestID = function() return FOCUS end,
    SetSuperTrackedQuestID = function(id) if REFUSE_FOCUS ~= id then FOCUS = id end end,
}
C_Map = { GetBestMapForUnit = function() return 1429 end }

-- the core's sink: every line in order; WC.push is "a flush at the end of this frame" (a timer at +0)
SENT = {}
SNAPS = {}
WowCompanion = { send = function(s) SENT[#SENT + 1] = s end, push = function() end,
                 emit = function(t) SENT[#SENT + 1] = WowCompanion.encode(t) end,
                 snapshotSoon = function(r) SNAPS[#SNAPS + 1] = r end }
'''


def setup():
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(STUB)
    for path in (SAFETY, TAPPAD, QUESTS):
        src = open(path, encoding='utf-8').read()
        fn = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')(src, '@' + path)
        fn(os.path.basename(path))
    return L


def tap(L, row, col):
    """TapPad's fire(): the row's handler with the column's key (nil for a column no key holds)"""
    L.eval('''function(row, col)
        local r = WowCompanion.pad.rows[row]
        local key = nil
        if r.ns then local t = WowCompanion.pad.ns[r.ns]; key = t and t.byCol[col] or nil end
        r.handler(key, col)
    end''')(row, col)


def docs(L, start=0):
    out = []
    for i in range(start + 1, int(L.eval('#SENT')) + 1):
        out.append(json.loads(L.eval('SENT[%d]' % i)))
    return out


def slots(L):
    doc = L.eval('(WowCompanion.collectQuests())')
    return {int(q.id): int(q.slot) for q in doc.list.values()}


L = setup()
L.execute('LOG = { {id = 92703, title = "A"}, {id = 92860, title = "B"}, {id = 93320, title = "C"} }')
s = slots(L)
check(len(set(s.values())) == 3, "each quest has its own column", s)

# 1. the focus pair: the quest is focused, the answer names it, and the quests document follows IN THE SAME BURST
n0 = int(L.eval('#SENT'))
tap(L, "qpick", s[92860]); tap(L, "qact", 2)
d = docs(L, n0)
check(int(L.eval('FOCUS')) == 92860, "the pair focuses the picked quest", L.eval('FOCUS'))
acks = [x for x in d if x.get('type') == 'questack']
check(len(acks) == 1 and acks[0]['op'] == 'track' and acks[0]['ok'] and acks[0]['id'] == 92860, "one questack: track ok 92860", acks)
qs = [i for i, x in enumerate(d) if x.get('type') == 'quests']
check(bool(qs) and qs[0] > d.index(acks[0]) and d[qs[0]]['q']['focus'] == 92860,
      "q48: the quests document right behind the answer, before any timer runs, with focus 92860", [x.get('type') for x in d])

# 2. 92860 leaves the log (turned in); an app with the OLD document taps its column: refused as a stale column, a fresh list follows
L.execute('LOG = { {id = 92703, title = "A"}, {id = 93320, title = "C"} }; FOCUS = 0')
L.execute('WowCompanion.collectQuests()')           # the add-on's next look frees the column (quarantined)
L.execute('NOW = 1001')
n0 = int(L.eval('#SENT'))
tap(L, "qpick", s[92860]); tap(L, "qact", 2)
d = docs(L, n0)
acks = [x for x in d if x.get('type') == 'questack']
check(len(acks) == 1 and not acks[0]['ok'] and 'holds no quest' in acks[0].get('why', '') and acks[0].get('col') == s[92860],
      "a stale column: refused, naming the column", acks)
check(int(L.eval('FOCUS')) == 0, "nothing was focused", L.eval('FOCUS'))
qd = [x for x in d if x.get('type') == 'quests']
check(len(qd) == 1 and 92860 not in [q['id'] for q in qd[0]['q']['list']],
      "after a refusal the quests list goes again (forced): 92860 is not in it", len(qd))

# 3. the freed column is quarantined: a new quest within 30 s does not get it
L.execute('LOG = { {id = 92703, title = "A"}, {id = 93320, title = "C"}, {id = 94896, title = "D"} }')
s2 = slots(L)
check(s2[94896] != s[92860], "a new quest inside the quarantine gets another column", s2)
L.execute('NOW = 1040; LOG = { {id = 92703, title = "A"}, {id = 93320, title = "C"} }')
L.execute('WowCompanion.collectQuests()')
L.execute('NOW = 1080; LOG = { {id = 92703, title = "A"}, {id = 93320, title = "C"}, {id = 94897, title = "E"} }')
s3 = slots(L)
check(s3[94897] in (s[92860], s2[94896]), "after the quarantine a freed column is used again (test50's column 8, reused)", s3)

# 4. a lost pick is still a lost pick
n0 = int(L.eval('#SENT'))
tap(L, "qact", 2)                                   # the pick was spent by the last action
d = docs(L, n0)
acks = [x for x in d if x.get('type') == 'questack']
check(len(acks) == 1 and acks[0].get('why') == 'no quest picked', "an action with no pick: no quest picked", acks)

# 5. the game refuses the focus: "did not take", and the fresh list follows
L.execute('REFUSE_FOCUS = 93320; FOCUS = 0')
n0 = int(L.eval('#SENT'))
tap(L, "qpick", s3[93320]); tap(L, "qact", 2)
d = docs(L, n0)
acks = [x for x in d if x.get('type') == 'questack']
check(len(acks) == 1 and not acks[0]['ok'] and acks[0].get('why') == 'did not take' and acks[0]['id'] == 93320,
      "the game refused it: did not take", acks)
check(any(x.get('type') == 'quests' for x in d), "the fresh list follows that refusal too")
L.execute('REFUSE_FOCUS = nil')

# 6. the route's pin stands: the focus renames the pin's quest and the map snapshot is asked for at once
L.execute('WowCompanion.routeWp = { active = true, qid = 92703 }; SNAPS = {}')
n0 = int(L.eval('#SENT'))
tap(L, "qpick", s3[93320]); tap(L, "qact", 2)
d = docs(L, n0)
check(int(L.eval('WowCompanion.routeWp.qid')) == 93320, "the pin now stands for the new quest", L.eval('WowCompanion.routeWp.qid'))
check(L.eval('SNAPS[1]') == 'supertrack', "q48: the map snapshot is asked for at once (no SUPER_TRACKING_CHANGED fires)", L.eval('SNAPS[1]'))
qd = [x for x in d if x.get('type') == 'quests']
check(bool(qd) and qd[0]['q']['focus'] == 93320, "the quests document says the new focus", qd[0]['q'].get('focus') if qd else None)
check('q48' in open(QUESTS, encoding='utf-8').read(), "Quests.lua says q48")

print("ALL PASS" if fails == 0 else ("%d FAILED" % fails))
sys.exit(1 if fails else 0)
