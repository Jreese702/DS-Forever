"""THE GAME'S WINDOWS ON THE BOTTOM SCREEN, off-device: the player reports of the Bags / Interact / Dialogs cluster (wave 2), each replayed
through the REAL add-on files (Safety, TapPad, Map, Quests, Hub, Interact + Dialogs, Book, PadMap, PadTabCtl, Bags, ...) under
tools/wowstub.py's game with STRICT laid over it (a protected call from insecure code in a fight raises).

    python -B tools/test_game_windows.py

  #89 (1.0.21, "replacing an existing armor kit with another one does not work, the confirmation screen loops"): the kit's pick list in the
      Bags room held A; the game's REPLACE_ENCHANT question came up over it, every A used the kit on the item again, the game asked again.
      Now (Bags bags-kit2) A / B are passed to the game while it asks: its own A answers its own question.
Needs lupa. Exit code 1 on any FAIL."""
import os
import sys

from wowstub import F, SRC, ADDONS, ADDON_OF, boot_world, docs, nsent, WORLD_FILES

fails = passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' and not ok else ''))
    if ok:
        passes += 1
    else:
        fails += 1


for _k, (_d, _f) in {"bags": ("WowCompanion", "Bags.lua"), "character": ("WowCompanionCharacter", "Character.lua")}.items():
    F[_k] = os.path.join(ADDONS, _d, _f)
    SRC[_k] = open(F[_k], encoding="utf-8").read()
    ADDON_OF[_k] = _d
FILES = tuple(list(WORLD_FILES[:2]) + ["bags"] + list(WORLD_FILES[2:]) + ["character"])
LOGIN = ('for _, a in ipairs({ "WowCompanion", "WowCompanionQuests", "WowCompanionHub", "WowCompanionInteract", "WowCompanionSpells", '
         '"WowCompanionCharacter" }) do fire("ADDON_LOADED", a) end; fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); '
         'runTimers(NOW + 10)')

# the game: a Heavy Armor Kit (4265) in the backpack's slot 1, a chest (2580) worn - it already has a kit on it, so using a kit on it makes
# the game ask REPLACE_ENCHANT (its StaticPopup, focused by its controller: A = Yes, B = No)
KIT_PRE = r'''
mkwin("StaticPopup1", false)
PICKING = false
C_Container = { GetContainerNumSlots = function(b) if b == 0 then return 16 end return 0 end,
                GetContainerItemID = function(b, s) if b == 0 and s == 1 then return 4265 end end,
                GetContainerItemInfo = function(b, s) if b == 0 and s == 1 then return { itemID = 4265, stackCount = 3, quality = 1, iconFileID = 1 } end end,
                GetContainerItemLink = function() return nil end, PickupContainerItem = function() end }
C_Item = C_Item or {}
C_Item.GetItemInfoInstant = function(id)
    if id == 2580 then return id, "Armor", "Leather", "INVTYPE_CHEST", 1, 4, 2 end
    if id == 4265 then return id, "Trade Goods", "Item Enhancement", "", 1, 0, 6 end
end
C_Item.GetItemNameByID = function(id) return ({ [2580] = "Rugged Leather Vest", [4265] = "Heavy Armor Kit" })[id] end
C_Item.GetItemIconByID = function() return 1 end
function GetInventoryItemID(_, slot) if slot == 5 then return 2580 end end
function GetInventoryItemTexture() return 1 end
function GetInventoryItemQuality() return 1 end
function SpellCanTargetItem() return PICKING end
function SpellCanTargetItemID() return false end
function SpellIsTargeting() return PICKING end
ASKED = 0
function ASK()                         -- the game: the kit used on the chest that has one = its question
    ASKED = ASKED + 1
    StaticPopup1.which = "REPLACE_ENCHANT"; StaticPopup1.gamepadFocused = true; StaticPopup1:Hide(); StaticPopup1:Show()
end
POPB = {}
for k = 1, 2 do
    local b = CreateFrame("Button", "StaticPopup1Button" .. k, StaticPopup1)
    b._text = (k == 1) and "Yes" or "No"
    b.GetText = function(self) return self._text end
    b.IsEnabled = function() return true end
    b:RegisterForClicks("AnyDown", "AnyUp")
    b:SetScript("OnClick", function() ACTIONS[#ACTIONS + 1] = { kind = "popup", k = k }; StaticPopup1:Hide(); if k == 1 then PICKING = false end end)
    POPB[k] = b
end
StaticPopup1.GetButton = function(self, k) return POPB[k] end
'''


def world(pre=''):
    L, errs = boot_world(files=FILES, pre='WowCompanion.partyOk = true\n' + KIT_PRE + pre, login=LOGIN)
    L.execute('LOADERRS = {}')
    for e in errs:
        L.eval('function(s) LOADERRS[#LOADERRS + 1] = s end')(e)
    L.execute('__IN.ACT.trust(); advance(1); __IN.N.setMode(true); advance(1)')
    return L


def lst(L, expr):
    t = L.eval(expr)
    return [] if t is None else list(t.values())


def clean(L):
    return {k: v for k, v in (("VIOL", lst(L, 'VIOL')), ("SPY", lst(L, 'SPY')), ("HANDLEERR", lst(L, 'HANDLEERR')),
                              ("SNIPERR", lst(L, 'SNIPERR')), ("ERRS", lst(L, 'ERRS')), ("LOADERRS", lst(L, 'LOADERRS'))) if v}


def press(L, key):
    g0 = int(L.eval('#GAMEKEYS'))
    how, log = L.eval('hwPress')(key, False, False)
    L.execute('advance(0.3)')
    return how, [L.eval('GAMEKEYS[%d]' % i) for i in range(g0 + 1, int(L.eval('#GAMEKEYS')) + 1)]


def rtap(L, row, col):
    r = L.eval('hwTap')(row, col)
    L.execute('advance(0.3)')
    return r


def ctl(L):
    d = docs(L, 0, 'bagsctl')
    return (d[-1].get('ctl') if d else None) or {}


def holder(L):
    return L.eval('WowCompanion.capture.state().holder')


def app_uses(L, c, seen):
    """the app's half (BagsView + KitTarget): one tap per new use.seq - an inv use = the binv cell of that slot"""
    u = c.get('use') or {}
    if not u or u.get('seq') in seen:
        return False
    seen.add(u.get('seq'))
    if u.get('inv'):
        ok = rtap(L, 'binv', int(u['inv']) - 1)
        if L.eval('PICKING') is True:                  # the game: the kit on a chest that has one asks first
            L.execute('ASK(); WowCompanion.dialogs.look(); tick(0.25); advance(0.6)')
        return ok
    return False


# ================================================================ #89: the armor kit's replace question
tag = '#89 REPLACE_ENCHANT'
W = world()
rtap(W, "bmvq", 29)                                     # the Bags room by touch
check(holder(W) == 'bags', "%s the Bags room holds the controller" % tag, holder(W))
W.execute('PICKING = true; tick(0.25); advance(0.6)')               # X on the kit: the game's "pick an item"
c = ctl(W)
check(c.get('mode') == 'target' and ((c.get('target') or {}).get('list') or [{}])[0].get('slot') == 5,
      "%s the pick is on: the room's list, the worn chest first" % tag, c.get('mode'))
seen = set()
press(W, "PAD1")                                        # A on the chest: the app taps binv 4 (/use 5), the game asks
app_uses(W, ctl(W), seen)
check(W.eval('ASKED') == 1 and W.eval('StaticPopup1._shown') is True, "%s A uses the kit on the chest: the game asks Replace?" % tag,
      W.eval('ASKED'))
c = ctl(W)
passed = dict(W.eval('__IN.G.spec and __IN.G.spec.pass') or {})
check(c.get('mode') == 'target' and (c.get('note') or '').startswith('pick-ask') and passed.get('PAD1') is True
      and passed.get('PAD2') is True and not W.eval('BIND.PAD1'),
      "%s while the game asks, A and B are PASSED to the game (bindask's rule), the room still holds" % tag,
      (c.get('mode'), c.get('note'), passed, W.eval('BIND.PAD1')))
how, game = press(W, "PAD1")                            # the player's A: the GAME's (its focused pop-up answers it)
app_uses(W, ctl(W), seen)
check(how == 'game' and 'PAD1+' in game and W.eval('ASKED') == 1,
      "%s A reaches the game, the room never uses the kit again (the loop: the question asked again)" % tag, (how, game, W.eval('ASKED')))
W.execute('StaticPopup1Button1:Click(); tick(0.25); advance(0.6)')  # the game's own A on its focused Yes (the stub's game has no pop-up navigation)
c = ctl(W)
passed = dict(W.eval('__IN.G.spec and __IN.G.spec.pass') or {})
check(c.get('mode') == 'grid' and c.get('note') == 'applied' and not passed.get('PAD1'),
      "%s Yes: the pick ends, the room is back on its grid, A is the room's again" % tag, (c.get('mode'), c.get('note'), passed))
check(holder(W) == 'bags' and clean(W) == {}, "%s still held, #VIOL == 0, no spied call, no Lua error" % tag, (holder(W), clean(W)))

# No: the game's B on its own question; the pick goes on (the stub's game keeps targeting), the list's A is the room's again
tag = '#89 REPLACE_ENCHANT, No'
W = world()
rtap(W, "bmvq", 29)
W.execute('PICKING = true; tick(0.25); advance(0.6)')
seen = set()
press(W, "PAD1")
app_uses(W, ctl(W), seen)
how, game = press(W, "PAD2")
check(how == 'game' and 'PAD2+' in game and ctl(W).get('mode') == 'target',
      "%s B while the game asks is the game's (its No), not the room's stop" % tag, (how, game, ctl(W).get('mode')))
W.execute('StaticPopup1Button2:Click(); tick(0.25); advance(0.6)')
c = ctl(W)
passed = dict(W.eval('__IN.G.spec and __IN.G.spec.pass') or {})
check(c.get('mode') == 'target' and c.get('note') == 'pick-answered' and not passed.get('PAD1'),
      "%s No: the question goes, the list is the room's again (A applies, B stops)" % tag, (c.get('mode'), c.get('note'), passed))
check(clean(W) == {}, "%s #VIOL == 0, no spied call, no Lua error" % tag, clean(W))

# a press that reaches the room before the pass is in force (the pop-up shown, the 0.25 s watcher not yet run) never re-uses the kit
tag = '#89 REPLACE_ENCHANT, a fast A'
W = world()
rtap(W, "bmvq", 29)
W.execute('PICKING = true; tick(0.25); advance(0.6)')
press(W, "PAD1")
c = ctl(W)
seq0 = (c.get('use') or {}).get('seq')
rtap(W, 'binv', 4)
W.execute('ASK()')                                      # no advance: the watcher has not seen it
how, game = press(W, "PAD1")
c = ctl(W)
check((c.get('use') or {}).get('seq') == seq0 and (c.get('note') or '').startswith('pick-ask'),
      "%s an A the room still gets while the game asks makes no new use (no second question)" % tag, (c.get('use'), c.get('note')))

# ================================================================ #69: the guild charter's Sign (1.0.18, "tried to sign a guild chart didnt work")
# The press paths (unchanged since 1.0.18): a touch on the idlg Sign cell and A on the charter's Sign (a binding click of Dialogs'
# WowCompanionPetSign) are each one secure click of the game's own PetitionFrameSignButton. Both work here, so the cause is the game's
# side or something the report does not show: Dialogs d31 adds the diagnostics (cap petSign, numbers only) that will decide it.
PET_PRE = r"""
do local mt = getmetatable(_G); local old = mt.__index
   -- the real game has no such global until Dialogs makes it (the stub's catch-all would answer a stub table)
   mt.__index = function(t, k) if type(k) == 'string' and (k:find('^WowCompanionPet') or k:find('^WowCompanionTrade')) then return nil end
                                return old(t, k) end end
mkwin("PetitionFrame", false)
SIGNS = 0
for _, n in ipairs({ "PetitionFrameSignButton", "PetitionFrameRequestButton", "PetitionFrameRenameButton" }) do
    local b = CreateFrame("Button", n, PetitionFrame)
    b:RegisterForClicks("AnyDown", "AnyUp")      -- (the real Click() runs OnClick whatever was registered)
    b.GetText = function() return n end; b.IsEnabled = function() return true end
    b:SetScript("OnClick", function() if n == "PetitionFrameSignButton" then SIGNS = SIGNS + 1 end end)
end
PetitionFrameRequestButton:Hide(); PetitionFrameRenameButton:Hide()
function GetPetitionInfo() return "guild", "Fancy Guild", "", 9, "Bob", false, 9 end
NAMES_N = 0
function GetNumPetitionNames() return NAMES_N end
PetitionFrameInstructions = { GetText = function() return "Sign it" end }
ERR_PETITION_ALREADY_SIGNED = "You have already signed that charter."
ERR_PETITION_SIGNED_S = "%s has signed your charter."
"""


def petsign(L, n0):
    d = [x for x in docs(L, n0, 'cap') if x.get('petSign')]
    return d[-1]['petSign'] if d else None


tag = '#69 charter'
W = world(PET_PRE)
W.execute('PetitionFrame:Show(); fire("PETITION_SHOW"); advance(1)')
pd = [x for x in docs(W, 0, 'interact') if x.get('petition')]
p = pd[-1]['petition'] if pd else {}
check(holder(W) == 'npc' and (p.get('btn') or {}).get('sign') is True and (p.get('cells') or {}).get('sign') is not None,
      "%s the charter is the bottom screen's (held), Sign shown with its touch cell" % tag, (holder(W), p.get('btn'), p.get('cells')))
n0 = nsent(W)
rtap(W, 'idlg', p['cells']['sign'])
check(W.eval('SIGNS') == 1, "%s a touch on Sign = one click of the game's own Sign button" % tag, W.eval('SIGNS'))
W.execute('fire("CHAT_MSG_SYSTEM", ERR_PETITION_ALREADY_SIGNED); advance(4.5)')
ps = petsign(W, n0)
check(ps is not None and ps.get('via') == 1 and ps.get('sys') == 3 and ps.get('before') == 0 and not ps.get('closed'),
      "%s d31: one cap petSign after the Sign - by touch, the game said 'already signed' (D.PETMSG 3), counts" % tag, ps)
check(ps is not None and all(isinstance(v, (bool, int, float)) for v in ps.values()), "%s ... numbers and flags only (no words)" % tag, ps)
check(W.eval('__IN.N.cur') == 'petsign' and W.eval('BIND.PAD1') == 'CLICK WowCompanionPetSign:LeftButton',
      "%s A on the charter = a binding click of Dialogs' Sign button" % tag, (W.eval('__IN.N.cur'), W.eval('BIND.PAD1')))
n0 = nsent(W)
press(W, 'PAD1')
check(W.eval('SIGNS') == 2, "%s A = one click of the game's own Sign button" % tag, W.eval('SIGNS'))
W.execute('fire("CHAT_MSG_SYSTEM", "Alice has signed your charter."); fire("UI_ERROR_MESSAGE", 412, "x"); NAMES_N = 1; '
          'PetitionFrame:Hide(); fire("PETITION_CLOSED"); advance(4.5)')
ps = petsign(W, n0)
check(ps is not None and ps.get('via') == 2 and ps.get('err') == 412 and ps.get('sys') == 2 and ps.get('closed') is True,
      "%s d31: by A, the red error id, a %%s message matched (D.PETMSG 2) and the window's close are in it" % tag, ps)
check(clean(W) == {}, "%s #VIOL == 0, no spied call, no Lua error" % tag, clean(W))

# ================================================================ #50: several attachments on one letter (1.0.16, "the ingame mailbox interface
# itemselection has a problem whn you try to put multiple items into a single mail its very dificult to select the items")
# Before (Interact i113 .. i153): every item attached closed the bag list and put the cursor back on the tab row, so each next item was
# Down through To / Subject / the letter / the money / the attachments, A on "+", then the bag list from its top again. Now (i154) the list
# stays up like the game's own bags (each right-click attaches one more), the cursor walks on, and leaving it lands on the attachments.
MAIL_PRE = r"""
mkwin("MailFrame", false); mkwin("SendMailFrame", false)
BAGS = { [1] = { id = 2589, n = 5 }, [2] = { id = 2592, n = 3 }, [3] = { id = 118, n = 2 }, [4] = { id = 117, n = 4 } }
CUR, SENT_ITEMS = {}, {}          -- (a nil global would be the stub's catch-all)
C_Container = { GetContainerNumSlots = function(b) if b == 0 then return 16 end return 0 end,
                GetContainerItemID = function(b, s) if b == 0 and BAGS[s] then return BAGS[s].id end end,
                GetContainerItemInfo = function(b, s) local e = b == 0 and BAGS[s]
                    if e then return { itemID = e.id, stackCount = e.n, quality = 1, iconFileID = 1, isLocked = e.locked or false,
                                       isBound = false, hyperlink = "|cffffffff|Hitem:" .. e.id .. "|h[Item " .. e.id .. "]|h|r" } end end,
                GetContainerItemLink = function() return nil end,
                PickupContainerItem = function(b, s) if CUR.s then CUR.s = nil elseif b == 0 and BAGS[s] and not BAGS[s].locked then CUR.s = s end end }
function CursorHasItem() return CUR.s ~= nil end
function ClearCursor() CUR.s = nil end
function ClickSendMailItemButton() if CUR.s then BAGS[CUR.s].locked = true; SENT_ITEMS[#SENT_ITEMS + 1] = CUR.s; CUR.s = nil end end
function HasSendMailItem(k) return SENT_ITEMS[k] ~= nil end
function GetSendMailItem(k) local s = SENT_ITEMS[k]; if s then return "Item " .. BAGS[s].id, BAGS[s].id, 1, BAGS[s].n, 1 end end
function GetSendMailPrice() return 30 end
function GetMoney() return 100000 end
function GetInboxNumItems() return 0 end
"""
tag = '#50 mail attachments'
W = world(MAIL_PRE)
W.execute('MailFrame:Show(); fire("MAIL_SHOW"); advance(1)')
W.eval('__IN.ACT.mailtab')('send')
W.execute('SendMailFrame:Show(); advance(0.5)')


def cur(L):
    return L.eval('__IN.N.cur')


def walk_to(L, want, keys=("PADDDOWN",) * 8 + ("PADDRIGHT",) * 8):
    for k in keys:
        if cur(L) == want:
            return True
        press(L, k)
    return cur(L) == want


check(holder(W) == 'npc' and cur(W) == 'mailtab send', "%s the Send Mail tab is held, the cursor on its tab" % tag, (holder(W), cur(W)))
check(walk_to(W, 'mailpick on'), "%s Down / Right reach the attachments' \"+\"" % tag, cur(W))
press(W, 'PAD1')
check(cur(W) == 'mailattach 0 1 2589', "%s A on \"+\": the bag list, the cursor on its first item" % tag, cur(W))
press(W, 'PAD1')
check(W.eval('#SENT_ITEMS') == 1 and cur(W) == 'mailattach 0 2 2592',
      "%s A attaches it; the list STAYS UP and the cursor walks on to the next item" % tag, (W.eval('#SENT_ITEMS'), cur(W)))
press(W, 'PAD1')
press(W, 'PADDRIGHT')
press(W, 'PAD1')
check(W.eval('#SENT_ITEMS') == 3 and [W.eval('SENT_ITEMS[%d]' % i) for i in (1, 2, 3)] == [1, 2, 4],
      "%s three items on one letter without leaving the list (A, A, Right past one, A)" % tag, [W.eval('SENT_ITEMS[%d]' % i) for i in (1, 2, 3)])
md = [x for x in docs(W, 0, 'interact') if (x.get('mail') or {}).get('send')]
sd = md[-1]['mail']['send'] if md else {}
check(len(sd.get('items') or []) == 3 and sd.get('bags') is not None, "%s the document: 3 attachments, the bag list still up" % tag,
      (len(sd.get('items') or []), sd.get('bags') is not None))
press(W, 'PAD2')
check(cur(W) == 'mailpick on' and holder(W) == 'npc', "%s B closes the list: the cursor on \"+\" (not the tab row), still the mailbox" % tag,
      (cur(W), holder(W)))
press(W, 'PAD1')
check(cur(W) == 'mailattach 0 3 118', "%s \"+\" again: the list, on the first item that can still go" % tag, cur(W))
W.eval('__IN.ACT.mailpick')('off')
W.execute('advance(0.3)')
check(cur(W) == 'mailpick on', "%s the app's Close (mailpick off): the cursor on \"+\" too" % tag, cur(W))
check(clean(W) == {}, "%s #VIOL == 0, no spied call, no Lua error" % tag, clean(W))

print("\n%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
