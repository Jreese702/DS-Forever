"""bags-open1 / Interact i161, THE REVIEW (wk-containers; the user, 2026-11-16: "opening containers from my bags is a little cluncky, there is
not a button although weirdly x does work to open them, it opens the loot menu back in the home room, it should just open the loot menu inside
of the bags and then keep the player inside of the bag room."). END TO END off-device: the REAL Bags.lua and Interact.lua (+ Dialogs, TapPad,
Hub, Quests, the Spells files) under tools/wowstub.py's STRICT game, with the hardware model the review needed: a press reaches Interact's
grab frame first (OnGamePadButtonDown / Up, its SetPropagateKeyboardInput), and only what the frame hands on reaches the override bindings
(the binding resolved at each edge, the worst case for a release).

  - X on a clam in the Bags room: Bags asks for the slot's use, the slot's secure cell is clicked (its PreClick notes the click), the game
    opens its loot window -> the loot window holds OVER the room (doc.under = "bags"), Bags is not told it lost anything;
  - B on the loot window closes it (our secure close, acting on the PRESS) and the player stays in Bags: ONE step. The review's DOUBLE B:
    when LOOT_CLOSED and the resume came before that B's release, the release reached Bags as its own B (Bags left, the app went Home).
    Both ways into the room: the game's bag window open (Bags passes B to the game in its grid) and the room entered by touch (B Bags' own);
  - the next B leaves Bags with B (Home), as ever;
  - a pop-up already waiting behind Bags when the box is opened (Dialogs' take queues behind any room) keeps waiting after the loot: the
    review found it WON the resume (Bags told lost "taken", the app sent Home);
  - a Locked box: its row says lockedBox, X is refused ("locked"), no use asked;
  - no lockdown breach, no spied call (no trace), no handle / snippet / Lua error.

    python -B tools/test_bags_lootpad.py
"""
import os
import sys

from wowstub import ADDONS, ADDON_OF, F, SRC, WORLD_FILES, boot_world, docs

fails = passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' and not ok else ''))
    if ok:
        passes += 1
    else:
        fails += 1


for _k, (_d, _f) in {"bags": ("WowCompanion", "Bags.lua"), "spellsroom": ("WowCompanionSpells", "Spells.lua")}.items():
    F[_k] = os.path.join(ADDONS, _d, _f)
    SRC[_k] = open(F[_k], encoding="utf-8").read()
    ADDON_OF[_k] = _d
FILES = tuple(list(WORLD_FILES[:2]) + ["bags"] + list(WORLD_FILES[2:7]) + ["spellsroom"] + list(WORLD_FILES[7:]))
LOGIN = ('for _, a in ipairs({ "WowCompanion", "WowCompanionQuests", "WowCompanionHub", "WowCompanionInteract", "WowCompanionSpells" }) do '
         'fire("ADDON_LOADED", a) end; fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')

PRE = r'''
mkwin("LootFrame", false); mkwin("StaticPopup1", false)
CreateFrame("Frame", "GamepadPersistentInputLegend", UIParent); CreateFrame("Frame", "GameTooltip", UIParent)
-- the clam's loot; the real loot window's close: CloseLoot -> LOOT_CLOSED a moment later -> Close() (the fade) -> hidden
LOOTS = { { 133, "Small Lustrous Pearl", 1, nil, 2 }, { 134, "Clam Meat", 2, nil, 1 } }
function GetNumLootItems() if LootFrame._shown == false then return 0 end; return #LOOTS end
function GetLootSlotInfo(i) local l = LOOTS[i]; if l then return l[1], l[2], l[3], l[4], l[5], false, false, nil, true, false end end
function GetLootSlotLink(i) return nil end
function LootSlot(i) ACTIONS[#ACTIONS + 1] = { kind = "lootslot", slot = i } end
LootFrame:HookScript("OnShow", function() LootFrame.isOpen = true end)
LootFrame.CloseButton:SetScript("OnClick", function()
    if LootFrame._shown == false or LootFrame.closing then return end
    LootFrame.closing = true
    ACTIONS[#ACTIONS + 1] = { kind = "close", frame = "LootFrame" }
    C_Timer.After(0.02, function()
        LootFrame.isOpen = false
        fire("LOOT_CLOSED")
        C_Timer.After(0.1, function() LootFrame:Hide(); LootFrame.closing = nil end)
    end)
end)
-- the game's own words for a box (GlobalStrings)
LOCKED, ITEM_OPENABLE = "Locked", "<Right Click to Open>"
-- the backpack: slot 1 a clam, slot 2 a Locked lockbox (both hasLoot, as the game's controller bag reads them), the rest empty
ITEMS = { [1] = { id = 5523, tip = { "Small Barnacled Clam", "<Right Click to Open>" } },
          [2] = { id = 4632, tip = { "Ornate Bronze Lockbox", "Locked", "Requires Lockpicking (1)" } } }
local function fallback(t) return setmetatable(t, { __index = function() return STUB end }) end
C_Container = fallback({
    GetContainerNumSlots = function(b) if b == 0 then return 16 end return 0 end,
    GetContainerItemID = function(b, s) local it = b == 0 and ITEMS[s]; return it and it.id or nil end,
    GetContainerItemInfo = function(b, s)
        local it = b == 0 and ITEMS[s]
        if it then return { itemID = it.id, stackCount = 1, quality = 1, iconFileID = 1, hasLoot = true } end
    end,
    GetContainerItemLink = function() return nil end, PickupContainerItem = function() end,
})
C_Item = fallback({
    GetItemInfoInstant = function(id) if id == 5523 or id == 4632 then return id, "Miscellaneous", "Junk", "", 1, 15, 0 end end,
    GetItemNameByID = function(id) return (id == 5523) and "Small Barnacled Clam" or (id == 4632) and "Ornate Bronze Lockbox" or nil end,
})
C_TooltipInfo = fallback({
    GetBagItem = function(b, s)
        local it = b == 0 and ITEMS[s]
        if not it then return nil end
        local lines = {}
        for i, t in ipairs(it.tip) do lines[i] = { leftText = t } end
        return { lines = lines }
    end,
})
-- the game's bag window and its backpack button (the doors: the app opens the game's own bags behind the room)
mkwin("ContainerFrameCombinedBags", false)
local bp = CreateFrame("Button", "MainMenuBarBackpackButton", UIParent)
bp:RegisterForClicks("AnyDown", "AnyUp")
bp:SetScript("OnClick", function(_, _, down)
    if down == false then return end
    if ContainerFrameCombinedBags._shown then ContainerFrameCombinedBags:Hide() else ContainerFrameCombinedBags:Show() end
end)
-- THE HARDWARE, with Interact's grab frame first: the frame's handler hears the edge and says whether it hands it on
-- (SetPropagateKeyboardInput); only a handed-on edge reaches the override bindings (hwEdge: the binding of that moment)
function Frame:SetPropagateKeyboardInput(on) self._prop = on and true or false end
function hwPad(key, down)
    local g = rawget(_G, "WowCompanionInteractGrab")
    local fn = g and g._shown ~= false and g.scripts[down and "OnGamePadButtonDown" or "OnGamePadButtonUp"]
    if not fn then return hwEdge(key, down) end
    g._prop = true
    fn(g, key)
    if g._prop then return hwEdge(key, down) end
    return "frame"
end
-- the game's PreClick then the click (wowstub's doClick runs OnClick / PostClick only)
function preTap(row, col)
    local r = WowCompanion.pad and WowCompanion.pad.rows and WowCompanion.pad.rows[row]
    local c = r and r.cells and r.cells[col]
    if not c then return false end
    for _, fn in ipairs(c.hooks.PreClick or {}) do fn(c, "LeftButton", true) end
    return hwTap(row, col)
end
'''


def world(window):
    L, errs = boot_world(files=FILES, pre=PRE, login=LOGIN)
    L.execute('LOADERRS = {}')
    for e in errs:
        L.eval('function(s) LOADERRS[#LOADERRS + 1] = s end')(e)
    L.execute('__IN.ACT.trust(); advance(1); __IN.N.setMode(true); advance(1)')
    if window:
        L.eval('hwTap')('bag0', 39)                 # the backpack's open door (the game's own bag window behind the room)
    else:
        L.eval('hwTap')('bmvq', 29)                 # the room entered by touch, no game window
    L.execute('advance(0.5)')
    return L


def lst(L, expr):
    t = L.eval(expr)
    return [] if t is None else list(t.values())


def clean(L):
    return {k: v for k, v in (("VIOL", lst(L, 'VIOL')), ("SPY", lst(L, 'SPY')), ("HANDLEERR", lst(L, 'HANDLEERR')),
                              ("SNIPERR", lst(L, 'SNIPERR')), ("ERRS", lst(L, 'ERRS')), ("LOADERRS", lst(L, 'LOADERRS'))) if v}


def pad(L, key, hold=0.0):
    """one press on the controller: the press, `hold` seconds of the game running, the release"""
    L.eval('hwPad')(key, True)
    if hold:
        L.execute('advance(%f)' % hold)
    L.eval('hwPad')(key, False)
    L.execute('advance(0.3)')


def holder(L):
    return L.eval('WowCompanion.capture.state().holder')


def under(L):
    return L.eval('WowCompanion.capture.state().under')


def ctl(L):
    c = L.eval('WowCompanion._bagsCtlDoc()')
    return {k: c[k] for k in ('on', 'note', 'mode')} if c is not None else {}


def idoc(L):
    d = docs(L, 0, 'interact')
    return d[-1] if d else {}


def row_of(L, slot):
    for d in reversed(docs(L, 0, 'full')):
        for it in ((d.get('bags') or {}).get('items') or []):
            if it.get('bag') == 0 and it.get('slot') == slot:
                return it
    return {}


def open_clam(L):
    """X on the clam (the cursor starts on bag 0 slot 1), the app's tap on the slot's secure cell, the game's loot window"""
    pad(L, "PAD3")
    c = L.eval('WowCompanion._bagsCtlDoc()')
    use = c['use'] if c is not None else None
    ok = bool(use) and use['bag'] == 0 and use['slot'] == 1
    L.eval('preTap')('bag0', 0)
    L.execute('advance(0.2); LootFrame:Show(); fire("LOOT_OPENED"); advance(1)')
    return ok


for window in (True, False):
    tag = 'game bag window open' if window else 'entered by touch'
    W = world(window)
    check(holder(W) == 'bags' and ctl(W).get('on') is True, "[%s] the Bags room holds the controller" % tag, (holder(W), ctl(W)))
    passB = W.eval('__IN.G.spec.pass and __IN.G.spec.pass.PAD2') is True
    check(passB == window, "[%s] B in the grid is %s" % (tag, "passed to the game (its bag window open)" if window else "Bags' own"), passB)
    check(row_of(W, 1).get('opens') is True and not row_of(W, 1).get('lockedBox'), "[%s] the clam's row says opens" % tag, row_of(W, 1))
    check(row_of(W, 2).get('lockedBox') is True and not row_of(W, 2).get('opens'), "[%s] the Locked box's row says lockedBox, never opens" % tag,
          row_of(W, 2))
    # a Locked box: X refused, nothing asked of the game
    pad(W, "PADDRIGHT")
    use0 = (W.eval('WowCompanion._bagsCtlDoc()')['use'] or {})
    pad(W, "PAD3")
    use1 = (W.eval('WowCompanion._bagsCtlDoc()')['use'] or {})
    check(ctl(W).get('note') == 'locked' and dict(use1 or {}) == dict(use0 or {}), "[%s] X on the Locked box: refused ('locked'), no use asked" % tag,
          (ctl(W), use1))
    pad(W, "PADDLEFT")
    # 1) the clam: its loot window over the room
    ok = open_clam(W)
    d = idoc(W)
    check(ok and holder(W) == 'npc' and under(W) == 'bags' and d.get('under') == 'bags' and d.get('loot') is not None,
          "[%s] X on the clam: the loot window holds OVER the room (holder npc, under bags, the loot in the document)" % tag,
          (ok, holder(W), under(W), d.get('under')))
    check(ctl(W).get('on') is True and ctl(W).get('note') == 'loot-under', "[%s] ... Bags is not told it lost anything (note loot-under)" % tag,
          ctl(W))
    # 2) B on the loot window, held a moment: the loot window closes on the press, the room resumes, the release is NOT Bags' B
    rel0 = (d.get('rel') or {}).get('n')
    pad(W, "PAD2", hold=0.4)
    d = idoc(W)
    check(W.eval('LootFrame._shown') is False, "[%s] B closed the loot window (its own close)" % tag, W.eval('LootFrame._shown'))
    check(holder(W) == 'bags' and under(W) is None and ctl(W).get('on') is True and ctl(W).get('note') == 'loot-resumed',
          "[%s] ONE B: the player is still in Bags (holder bags, note loot-resumed) - the release never reached Bags as its B" % tag,
          (holder(W), under(W), ctl(W)))
    check(W.eval('__IN.N.rel and __IN.N.rel.by') == 'resume', "[%s] ... released by 'resume', never 'B' (the app stays in the room)" % tag,
          W.eval('__IN.N.rel and __IN.N.rel.by'))
    check(W.eval('__IN.N.trusted or __IN.N.frameSeen') is True and not W.eval('__IN.N.distrust'),
          "[%s] ... and the proof stands (no 'bind' distrust from a stray release)" % tag, W.eval('__IN.N.distrust'))
    # 3) the next B: Bags' own B at its grid, Home as ever
    pad(W, "PAD2")
    if window:
        W.execute('ContainerFrameCombinedBags:Hide(); advance(0.4)')        # the game's own B closed its bags (the stub's game has no B)
    check(holder(W) is None and ctl(W).get('on') is False and ctl(W).get('note') == 'left-B',
          "[%s] the next B leaves Bags with B (Home)" % tag, (holder(W), ctl(W)))
    check(clean(W) == {}, "[%s] no lockdown breach, no spied call, no handle / snippet / Lua error" % tag, clean(W))

# 4) a pop-up already waiting behind Bags when the box is opened: it keeps waiting, the player stays in Bags
for window in (True, False):
    tag = 'game bag window open' if window else 'entered by touch'
    W = world(window)
    W.execute('DLGLOST = {}; OKD, WHYD = WowCompanion.capture.take("dialogs", { press = function() end, '
              'lost = function(w) DLGLOST[#DLGLOST + 1] = w end, noFight = true })')
    check(W.eval('WHYD') == 'queued' and holder(W) == 'bags', "[%s, pop-up waiting] the pop-up's take waits behind Bags" % tag,
          (W.eval('WHYD'), holder(W)))
    open_clam(W)
    check(holder(W) == 'npc' and under(W) == 'bags', "[%s, pop-up waiting] the clam's loot window over the room" % tag, (holder(W), under(W)))
    pad(W, "PAD3")                                         # X = Loot All on the loot window
    pad(W, "PAD2", hold=0.4)
    check(holder(W) == 'bags' and ctl(W).get('on') is True and ctl(W).get('note') == 'loot-resumed',
          "[%s, pop-up waiting] the loot window gone: the player is still in Bags (the waiting take did not win it)" % tag,
          (holder(W), ctl(W)))
    check(W.eval('__IN.N.wait and __IN.N.wait.id') == 'dialogs' and len(lst(W, 'DLGLOST')) == 0,
          "[%s, pop-up waiting] ... the pop-up's take still waits behind Bags, never told lost" % tag,
          (W.eval('__IN.N.wait and __IN.N.wait.id'), lst(W, 'DLGLOST')))
    W.execute('WowCompanion._bagsTouch(30); advance(1)')       # the room left by touch (bmvq 30): the waiter's turn
    check(holder(W) == 'dialogs', "[%s, pop-up waiting] Bags let go: the pop-up's take holds at the next document" % tag, holder(W))
    check(clean(W) == {}, "[%s, pop-up waiting] no lockdown breach, no spied call, no handle / snippet / Lua error" % tag, clean(W))

print('passes %d, failed: %d' % (passes, fails))
sys.exit(1 if fails else 0)
