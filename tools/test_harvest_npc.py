"""A SERVICE PIN NEEDS THE NPC BEHIND THE WINDOW (Harvest.lua hv3; the user, 2026-11-24, a Hunter on 1.0.40: "Using my pet training is
showing up as a trainer on my map and that is not correct"). Beast Training (spell 5149) opens the game's trainer window with no NPC:
TRAINER_SHOW (and the umbrella PLAYER_INTERACTION_MANAGER_FRAME_SHOW) fired, and hv2 filed a trainer where the player stood. The real
Sink.lua + Harvest.lua under tools/wowstub.py's game, checks:
  - Beast Training's window (no "npc" unit; the target empty / the pet / a hostile creature; "npc" the player or the pet): no "poi" line,
    by the dedicated event and by the umbrella event, and nothing in the heartbeat's session list (WC.collectServices)
  - a real class trainer (a Creature "npc"): recorded, with its creature id and name; a real PET trainer NPC is still a trainer
  - the target is never borrowed for an NPC service any more (a vendor with no "npc" unit: nothing)
  - objects keep their pins: a mailbox with no unit is recorded by position; a guild vault never takes the target's creature id
  - quest givers: a quest shared by a party member / opened with "npc" the player is not a giver at the player's spot; an NPC's still is
  - the tag: hv3 on Harvest's cap lines; nothing written (no SetCVar / SetBinding / macro / Blizzard window call)
Run: python -B tools/test_harvest_npc.py
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import boot  # noqa: E402
from wowstub import ADDONS  # noqa: E402

passes = fails = 0


def check(ok, what, got=None):
    global passes, fails
    if ok:
        passes += 1; print('PASS', what)
    else:
        fails += 1; print('FAIL', what, '->', got)


HARVEST = open(os.path.join(ADDONS, 'WowCompanion', 'Harvest.lua'), encoding='utf-8').read()

PRE = r'''
Enum = { PlayerInteractionType = { Merchant = 5, Trainer = 7, Banker = 8, MailInfo = 17, StableMaster = 12, TaxiNode = 3 },
         TrainerType = { Class = 0, Profession = 1, Pet = 2 } }
TRAINER_TYPE = 0
C_Trainer = { GetTrainerType = function() return TRAINER_TYPE end }
C_Map = setmetatable({
    GetBestMapForUnit = function() return 1453 end,
    GetPlayerMapPosition = function(m) return { x = PX, y = 0.5, GetXY = function() return PX, 0.5 end } end,
    GetMapInfo = function(m) return { mapID = m, parentMapID = 0, name = "M" .. m, mapType = 3 } end,
}, { __index = function() return STUB end })
PX = 0.5
function UnitIsUnit(a, b)
    local x, y = UNITS[a], UNITS[b]
    if a == b then return true end
    return x ~= nil and x == y
end
function UnitPosition(u) return 100, 200, 0, 0 end
C_GossipInfo = { GetOptions = function() return {} end, SelectOption = function() end,
                 GetAvailableQuests = function() return {} end, GetActiveQuests = function() return {} end }
QUEST_ID = 0
function GetQuestID() return QUEST_ID end
SlashCmdList = {}
'''

PLAYER = 'Player-1-0000BEEF'
TRAINER = 'Creature-0-6782-0-1-3039-0000ABCDEF'       # a Hunter trainer, entry 3039
PETTRAINER = 'Creature-0-6782-0-1-3698-0000ABCDE0'    # a pet trainer NPC, entry 3698
MOB = 'Creature-0-6782-0-1-299-0000ABCDE1'            # a hostile wolf the hunter has targeted, entry 299
VENDOR = 'Creature-0-6782-0-1-1213-0000ABCDE2'
PET = 'Pet-0-6782-0-1-42-0100ABCDE3'


def world():
    L = boot(['safety', 'tappad'], pre=PRE)
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for name in ('Sink.lua', 'Harvest.lua'):
        src = open(os.path.join(ADDONS, 'WowCompanion', name), encoding='utf-8').read()
        load(src, '@' + name)('WowCompanion')
    L.execute('SENT = {}; WowCompanion.send = function(s) SENT[#SENT + 1] = s end; WowCompanion.pushThrottled = function() end')
    L.execute('UNITS.player.guid = "%s"; UNITS.player.name = WHO' % PLAYER)
    return L


def sent(L, typ):
    out = []
    for i in range(1, int(L.eval('#SENT')) + 1):
        try:
            d = json.loads(L.eval('SENT[%d]' % i))
        except Exception:
            continue
        if isinstance(d, dict) and d.get('type') == typ:
            out.append(d)
    return out


def units(L, npc=None, target=None, pet=True):
    """npc / target: (guid, name) or the string "player" / "pet" for those units themselves"""
    L.execute('UNITS.npc = nil; UNITS.target = nil; UNITS.pet = nil')
    if pet:
        L.execute('UNITS.pet = { guid = "%s", name = "Wolfie", alive = true }' % PET)
    for unit, v in (('npc', npc), ('target', target)):
        if v is None:
            continue
        if v in ('player', 'pet'):
            L.execute('UNITS.%s = UNITS.%s' % (unit, v))
        else:
            L.execute('UNITS.%s = { guid = "%s", name = "%s", alive = true }' % (unit, v[0], v[1]))


def fire_pim(L, t):
    L.execute('''for _, f in ipairs(EVT.PLAYER_INTERACTION_MANAGER_FRAME_SHOW or {}) do
        f.scripts.OnEvent(f, "PLAYER_INTERACTION_MANAGER_FRAME_SHOW", %d) end''' % t)


def fire(L, e):
    L.execute('for _, f in ipairs(EVT.%s or {}) do f.scripts.OnEvent(f, "%s") end' % (e, e))


def services(L):
    return L.eval('(function() local r = WowCompanion.collectServices(1453); return r and #r or 0 end)()')


# ---- Beast Training: the trainer window with no NPC behind it
CASES = (
    ("no npc, no target", None, None),
    ("no npc, the pet targeted", None, 'pet'),
    ("no npc, a hostile creature targeted", None, (MOB, 'Timber Wolf')),
    ('"npc" is the player', 'player', None),
    ('"npc" is the pet', 'pet', None),
)
for what, npc, target in CASES:
    L = world()
    L.execute('TRAINER_TYPE = 2')
    units(L, npc, target)
    L.execute('WowCompanion.onInteract("TRAINER_SHOW")')
    fire_pim(L, 7)
    p = sent(L, 'poi')
    check(p == [], "Beast Training (%s): no trainer pin" % what, p)
    check(services(L) == 0, "Beast Training (%s): nothing in the heartbeat's session list" % what, services(L))

# ---- a real class trainer: recorded, with the NPC's id and name
L = world()
units(L, (TRAINER, 'Dazalar'))
L.execute('WowCompanion.onInteract("TRAINER_SHOW")')
p = sent(L, 'poi')
check(len(p) == 1 and p[0]['poi']['svc'] == 'trainer' and p[0]['poi']['id'] == 3039 and p[0]['poi']['name'] == 'Dazalar',
      "a class trainer (a Creature \"npc\"): recorded with its id and name", p)
check(services(L) == 1, "...and in the heartbeat's session list", services(L))

# the umbrella event alone, at a real trainer
L = world()
units(L, (TRAINER, 'Dazalar'))
fire_pim(L, 7)
p = sent(L, 'poi')
check(len(p) == 1 and p[0]['poi']['id'] == 3039 and p[0]['poi']['src'] == 'PIM', "the umbrella event at a real trainer: recorded", p)

# a real PET trainer NPC is still a trainer
L = world()
L.execute('TRAINER_TYPE = 2')
units(L, (PETTRAINER, 'Keeneye'), target='pet')
L.execute('WowCompanion.onInteract("TRAINER_SHOW")')
p = sent(L, 'poi')
check(len(p) == 1 and p[0]['poi']['id'] == 3698, "a pet trainer NPC (a Creature \"npc\"): recorded", p)

# the target is never borrowed for an NPC service
L = world()
units(L, None, (VENDOR, 'Brother Danil'))
L.execute('WowCompanion.onInteract("MERCHANT_SHOW")')
p = sent(L, 'poi')
check(p == [], "a vendor window with no \"npc\" unit: the target is not borrowed, nothing recorded", p)
units(L, (VENDOR, 'Brother Danil'))
L.execute('WowCompanion.onInteract("MERCHANT_SHOW")')
p = sent(L, 'poi')
check(len(p) == 1 and p[0]['poi']['svc'] == 'vendor' and p[0]['poi']['id'] == 1213, "a vendor (a Creature \"npc\"): recorded", p)

# objects keep their pins
L = world()
units(L, None, None)
L.execute('WowCompanion.onInteract("MAIL_SHOW")')
p = sent(L, 'poi')
check(len(p) == 1 and p[0]['poi']['svc'] == 'mailbox' and p[0]['poi'].get('id') is None, "a mailbox (no unit): recorded by position", p)
L = world()
units(L, None, (MOB, 'Timber Wolf'))
L.execute('WowCompanion.onInteract("GUILDBANKFRAME_OPENED")')
p = sent(L, 'poi')
check(len(p) == 1 and p[0]['poi']['svc'] == 'guildbank' and p[0]['poi'].get('id') is None and p[0]['poi'].get('name') is None,
      "a guild vault (an object): recorded by position, never with the target's creature id", p)

# quest givers: a shared quest is not a giver at the player's spot (a fresh world each: the dedupe is per spot)
for what, npc, want in (("a quest shared by a party member (a Player \"npc\"): no quest-giver row", ('Player-1-0000CAFE', 'Partyfriend'), None),
                        ("a quest window with \"npc\" the player: no quest-giver row", 'player', None),
                        ("a quest window with \"npc\" the pet: no quest-giver row", 'pet', None),
                        ("an NPC's quest: still recorded", (TRAINER, 'Dazalar'), 3039)):
    L = world()
    L.execute('QUEST_ID = 1234')
    units(L, npc)
    fire(L, 'QUEST_DETAIL')
    q = sent(L, 'qg')
    if want is None:
        check(q == [], what, q)
    else:
        check(len(q) == 1 and q[0]['qg'].get('e') == want and q[0]['qg']['offer'] == [1234], what, q)

# the tag, and no trace
check('local HV = "hv3"' in HARVEST, "Harvest.lua's tag is hv3")
L = world()
L.execute('SENT = {}; WowCompanion.emit = function(t) SENT[#SENT + 1] = WowCompanion.encode(t) end; SlashCmdList["WCPOI"]()')
d = sent(L, 'cap')
check(len(d) == 1 and d[0]['v'] == 'hv3', "the /wcpoi probe's cap line says hv3", d)
check(len(list(L.eval('SPY').values())) == 0, "nothing written: no binding / CVar / macro / window call", list(L.eval('SPY').values()))

print(f'{passes} passed, {fails} failed')
sys.exit(1 if fails else 0)
