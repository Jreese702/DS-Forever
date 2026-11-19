"""THE QUEST ITEM BY TAP (WowCompanionQuests/Quests.lua q65; the user, 2026-11-17: "make the quest item usable by tap out of combat"),
off-device: the REAL Safety / TapPad / Quests files under the shared stub game (wowstub.py, its secure-button and lockdown model).

    python tools/test_quest_item_use.py

Checks: the pfocus row grows one cell (col 30, no new pad row); at rest that cell has no action; the app's pair - the quest's qpick
column, then pfocus 30 - out of combat uses the quest's item through the cell's secure "item" action (the game's own /use path; a
hardware click), and the cell is empty again after the click; the answer is questack "useitem" with the quest's id. In a fight the cell
is never touched (no lockdown violation), nothing is used and the answer says "In combat". No pick, a stale pick, a quest without an
item or an item no longer in the bags: refused, nothing used. Never UseQuestLogSpecialItem, no macro, no binding. Needs lupa."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import SRC, boot, docs, nsent   # noqa: E402

fails = 0


def check(ok, name, got=''):
    global fails
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if not ok:
        fails += 1


PRE = r'''
LOG = { {id = 33, title = "Wolves Across the Border"}, {id = 7, title = "Kobold Camp Cleanup"} }
SI = { [1] = { "|cffffffff|Hitem:23688::::::::12:::::|h[Shrine Scroll]|h|r", 134939, 3, false } }
COUNT = { [23688] = 1 }
USES = 0
C_QuestLog = setmetatable({
    GetNumQuestLogEntries = function() return #LOG end,
    GetInfo = function(i) local q = LOG[i]; if q then return { questID = q.id, title = q.title, isHeader = false, isHidden = false, level = 10 } end end,
    GetLogIndexForQuestID = function(id) for i, q in ipairs(LOG) do if q.id == id then return i end end end,
    GetQuestObjectives = function() return {} end,
    GetQuestsOnMap = function() return {} end,
    IsComplete = function() return false end,
    ShouldShowQuestRewards = function() return false end,
}, { __index = function() return function() return nil end end })
function GetQuestLogSpecialItemInfo(i) local t = SI[i]; if t then return t[1], t[2], t[3], t[4] end end
function GetQuestLogSpecialItemCooldown() return 0, 0, 1 end
function UseQuestLogSpecialItem() USES = USES + 1 end
C_Item = setmetatable({ GetItemCount = function(id) return COUNT[id] or 0 end }, { __index = function() return function() return nil end end })
function GetItemCount(id) return COUNT[id] or 0 end
function GetQuestLogQuestText() return nil end
'''
Q = boot(["safety", "tappad", "quests"], pre=PRE)
# the app's tap on a pad cell: a hardware click, both edges
Q.execute('''function tapCell(row, col)
    local c = WowCompanion.pad.rows[row] and WowCompanion.pad.rows[row].cells[col]
    if not c then return false end
    doClick(c, "LeftButton", true); doClick(c, "LeftButton", false)
    NOW = NOW + 0.1
    return true
end''')
Q.execute('advance(1)')


def acks(n0):
    return [d for d in docs(Q, n0, 'questack') if d.get('op') == 'useitem']


def cell(col):
    return Q.eval('(function() local b = WowCompanion.pad.rows.pfocus.cells[%d]; return b and { type = b.a["type"], item = b.a["item"] } end)()' % col)


doc = Q.eval('WowCompanion.collectQuests()')
slot = None
for i in range(1, int(Q.eval('#WowCompanion.collectQuests().list')) + 1):
    if Q.eval('WowCompanion.collectQuests().list[%d].id' % i) == 33:
        slot = Q.eval('WowCompanion.collectQuests().list[%d].slot' % i)
check(slot is not None, "quest 33 has its qpick column", slot)
check(Q.eval('WowCompanion.pad.rows.pfocus.n') == 31, "pfocus grows to 31 cells (col 30: the quest item; no new pad row)",
      Q.eval('WowCompanion.pad.rows.pfocus.n'))
c = cell(30)
check(c is not None and c['type'] is None and c['item'] is None, "at rest the cell has no action", c and dict(c))
check(Q.eval('WowCompanion.pad.rows.pfocus.cells[2].a["macrotext"]') == "/inspect", "pfocus 2 still /inspect (the row's other cells kept)")


def use(pick=True):
    n0 = nsent(Q)
    Q.execute('ACTIONS = {}')
    if pick:
        Q.eval('tapCell')("qpick", slot)
    Q.eval('tapCell')("pfocus", 30)
    Q.execute('advance(1)')
    acts = [dict(a) for a in Q.eval('ACTIONS').values()]
    return acts, acks(n0)


# 1. out of combat: the pair uses the item through the secure cell
acts, a = use()
check([x.get('item') for x in acts if x.get('kind') == 'item'] == ['item:23688'], "out of combat: the cell's secure item action uses item:23688",
      acts)
check(bool(a) and a[-1].get('ok') is True and a[-1].get('id') == 33, "questack useitem ok for quest 33", a)
c = cell(30)
check(c['type'] is None and c['item'] is None, "the cell is empty again after the click (no trace)", dict(c))

# 2. in a fight: nothing set, nothing used, "In combat"
Q.execute('COMBAT = true; VIOL = {}')
acts, a = use()
check(not [x for x in acts if x.get('kind') == 'item'], "in a fight: nothing used", acts)
check(bool(a) and a[-1].get('ok') is False and a[-1].get('why') == "In combat", "in a fight: refused, In combat", a)
check(int(Q.eval('#VIOL')) == 0, "no lockdown violation (the cell is never touched in a fight)", list(Q.eval('VIOL').values()))
Q.execute('COMBAT = false')

# 3. no pick / a stale pick
acts, a = use(pick=False)
check(not [x for x in acts if x.get('kind') == 'item'] and bool(a) and a[-1].get('ok') is False, "no pick: refused, nothing used", a)
Q.execute('ACTIONS = {}')
Q.eval('tapCell')("qpick", slot)
Q.execute('advance(5)')
n0 = nsent(Q)
Q.eval('tapCell')("pfocus", 30)
Q.execute('advance(1)')
check(not [x for x in Q.eval('ACTIONS').values() if x['kind'] == 'item'] and acks(n0) and acks(n0)[-1].get('ok') is False,
      "a pick older than its two seconds: refused")

# 4. the item gone from the bags / a quest with no item
Q.execute('COUNT[23688] = 0')
acts, a = use()
check(not [x for x in acts if x.get('kind') == 'item'] and bool(a) and a[-1].get('why') == "No item", "no item in the bags: No item", a)
Q.execute('COUNT[23688] = 1; SI = {}')
acts, a = use()
check(not [x for x in acts if x.get('kind') == 'item'] and bool(a) and a[-1].get('why') == "No item", "a quest with no item: No item", a)
c = cell(30)
check(c['type'] is None and c['item'] is None, "the cell still empty", dict(c))

# 5. no trace
check(int(Q.eval('USES')) == 0, "UseQuestLogSpecialItem never called from add-on code")
spy = list(Q.eval('SPY').values())
check(not [s for s in spy if s in ("CreateMacro", "EditMacro", "SetBinding", "SaveBindings", "SetCVar", "PlaceAction")], "no macro, binding or CVar", spy)

src = SRC["quests"]
check('local QV = "q70"' in src and "make the quest item usable by tap out of combat" in src, "Quests.lua says q70, q65 quoting the user")

print("ALL PASS" if fails == 0 else ("%d FAILED" % fails))
sys.exit(1 if fails else 0)
