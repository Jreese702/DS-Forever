"""THE BOTTOM SCREEN IN A FIGHT, off-device (E:/wow-build/combat_nav_design.md and its USER DECISIONS 2026-11-11): the REAL add-on files
(Safety, TapPad, Map, Quests, Hub, Interact + Dialogs, Book, PadMap, PadTabCtl) under tools/wowstub.py's game with STRICT laid over it.

    python tools/test_combat_pad.py

The user, 2026-11-11: "everything on the bottom screen breaks in combat. If I am in combat and press B I should be able to fully navigate
everything like normal. If I am looting ... my dpad should not stop working. If I am looking at the bottom map then I should be able to press
B to exit out of it and then start fighting".

STRICT (the design's "Harness additions"): every protected call from insecure code while COMBAT is on is an ADDON_ACTION_BLOCKED (VIOL, and it
raises); a snippet's handle on an UNPROTECTED frame raises "Invalid frame handle" in a fight (HANDLEERR); a press goes binding -> click ->
PRE -> the click it resolves to (the /click chain followed); a tap is a click on a TapPad cell. Every scenario asserts no VIOL, no spied call
(SPY: SetBinding, macros, CVars, Blizzard open / close), no handle error, no snippet error. Plus the static snippet scan and a reload in a
fight.

The scenarios (S1 map open at the pull, S2 loot in a fight, S3 B navigation in a fight, Party mode in a fight, the game windows at the pull)
describe the behaviour the design and the user's decisions ask for. They started as XFAILs against 97036b2b; Build plan Steps 1-5 flipped
every one (the sections STEP 2 / STEP 3 / STEP 5 below add each step's own checks). An XPASS (a marked check that passes) is a FAILURE.
Needs lupa. Exit code 1 on any FAIL or XPASS."""
import os
import re
import sys

from wowstub import ROOT, F, SRC, boot_world, docs, nsent, WORLD_FILES

fails = passes = xfails = xpasses = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' and not ok else ''))
    if ok:
        passes += 1
    else:
        fails += 1


def xcheck(ok, name, why, got=''):
    """Expected to fail TODAY (why = what today's code does instead). Passing = XPASS = a failure: flip it to check()."""
    global xfails, xpasses
    if ok:
        xpasses += 1
        print('XPASS ' + name + '  (flip this marker to check(): it passes now)')
    else:
        xfails += 1
        print('XFAIL ' + name + '  [today: ' + why + ']' + ((' -> ' + str(got)) if got != '' else ''))


# ================================================================ the world
ROUTER = {"PAD1": "WowCompanionCombatFace8", "PAD2": "WowCompanionFaceB", "PAD3": "WowCompanionCombatFace5", "PAD4": "WowCompanionCombatFace6",
          "PADDLEFT": "WowCompanionFaceD1", "PADDUP": "WowCompanionFaceD2", "PADDRIGHT": "WowCompanionFaceD3", "PADDDOWN": "WowCompanionFaceD4"}
GAME_PRE = r'''
mkwin("LootFrame", false); mkwin("WorldMapFrame", false); mkwin("GameMenuFrame", false); mkwin("StaticPopup1", false)
mkwin("ReadyCheckFrame", false); mkwin("GroupLootFrame1", false)
LOOTS = { { 133, "Linen Cloth", 2, nil, 1 }, { 134, "Rough Stone", 1, nil, 1 } }
function GetNumLootItems() if LootFrame._shown == false then return 0 end; return #LOOTS end
function GetLootSlotInfo(i) local l = LOOTS[i]; if l then return l[1], l[2], l[3], l[4], l[5], false, false, nil, true, false end end
function GetLootSlotLink(i) return nil end
function LootSlot(i) ACTIONS[#ACTIONS + 1] = { kind = "lootslot", slot = i } end
-- the review of 2026-11-11: frames every hide dims that are SHOWN in the real game (the input legend, the item tooltip), and the real
-- loot window's close: CloseLoot -> LOOT_CLOSED a frame later -> Close() (isOpen false, the 0.1 s HideAnim) -> hidden when the fade ends
CreateFrame("Frame", "GamepadPersistentInputLegend", UIParent); CreateFrame("Frame", "GameTooltip", UIParent)
LootFrame:HookScript("OnShow", function() LootFrame.isOpen = true end)
LootFrame.CloseButton:SetScript("OnClick", function()
    if LootFrame._shown == false then return end
    ACTIONS[#ACTIONS + 1] = { kind = "close", frame = "LootFrame" }
    C_Timer.After(0.02, function()
        LootFrame.isOpen = false
        fire("LOOT_CLOSED")
        C_Timer.After(0.1, function() LootFrame:Hide() end)
    end)
end)
'''


def world(mode=True, party_ok=True, loot_top=False, pre='', login=True):
    """mode = the Hub's NPC-windows bit (Interact's N.mode: "the companion is in charge of the game's windows")."""
    p = GAME_PRE + ('WowCompanion.partyOk = true\n' if party_ok else '') + ('WowCompanionDB.lootTop = true\n' if loot_top else '') + pre
    L, errs = boot_world(pre=p) if login else boot_world(pre=p, login='')
    L.execute('LOADERRS = {}')
    for e in errs:
        L.eval('function(s) LOADERRS[#LOADERRS + 1] = s end')(e)
    if login:
        L.execute('__IN.ACT.trust(); advance(1)')
        if mode:
            L.execute('__IN.N.setMode(true); advance(1)')
    return L


def lst(L, expr):
    t = L.eval(expr)
    return [] if t is None else list(t.values())


def clean(L):
    """The design's "every scenario asserts": no lockdown breach, no spied call, no handle error, no snippet error, no Lua error."""
    return {k: v for k, v in (("VIOL", lst(L, 'VIOL')), ("SPY", lst(L, 'SPY')), ("HANDLEERR", lst(L, 'HANDLEERR')),
                              ("SNIPERR", lst(L, 'SNIPERR')), ("ERRS", lst(L, 'ERRS')), ("LOADERRS", lst(L, 'LOADERRS'))) if v}


def pull(L, order='loaded'):
    """PLAYER_REGEN_DISABLED (fired BEFORE the lockdown, as the game does), then the lockdown. order: the handlers as registered (Interact
    before PadMap: the game's load order) or reversed (PadMap first)."""
    if order == 'reversed':
        L.execute('local t = EVT.PLAYER_REGEN_DISABLED; local r = {}; for i = #t, 1, -1 do r[#r + 1] = t[i] end; EVT.PLAYER_REGEN_DISABLED = r')
    L.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.5)')
    if order == 'reversed':
        L.execute('local t = EVT.PLAYER_REGEN_DISABLED; local r = {}; for i = #t, 1, -1 do r[#r + 1] = t[i] end; EVT.PLAYER_REGEN_DISABLED = r')


def fight_end(L):
    L.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1); NOW = NOW + 2; tick(0.1); tick(0.5); tick(1); advance(1)')


def press(L, key, lt=False, rt=False):
    a0, g0, n0 = int(L.eval('#ACTIONS')), int(L.eval('#GAMEKEYS')), nsent(L)
    how, log = L.eval('hwPress')(key, lt, rt)
    L.execute('advance(0.2)')
    acts = [dict(L.eval('ACTIONS[%d]' % i)) for i in range(a0 + 1, int(L.eval('#ACTIONS')) + 1)]
    return {"how": how, "clicks": list(log.values()) if log else [], "acts": acts,
            "game": [L.eval('GAMEKEYS[%d]' % i) for i in range(g0 + 1, int(L.eval('#GAMEKEYS')) + 1)],
            "pads": [d for d in docs(L, n0) if d.get('type') in ('guestpad', 'mappad') and (d.get('k') or d.get('lost'))]}


def tap(L, cell):
    """A tap of the app on the cell a document names ({row, col})."""
    if not cell:
        return False, []
    r = L.eval('hwTap')(cell['row'], cell['col'])
    ok, log = r if isinstance(r, tuple) else (r, None)
    L.execute('advance(0.3)')
    return ok, (list(log.values()) if log else [])


def qtap(L, col):
    L.eval('hwTap')("qact", col)
    L.execute('advance(0.3)')


QACT = {"quests": 8, "map": 10}                      # Quests' generic guests: hold = 8 + 2i, free = 9 + 2i (GUESTS' order)


def idoc(L):
    d = docs(L, 0, 'interact')
    return d[-1] if d else {}


def holder(L):
    return L.eval('WowCompanion.capture.state().holder')


def modes(L):
    return [L.eval('rawget(_G, "%s").a["wc-mode"]' % n) for n in ROUTER.values()]


def carried(L):
    """all 8 router keys bound to the router (R.aside nil): the controller is kept at the pull"""
    b = L.eval('BIND')
    return all(b[k] == "CLICK %s:LeftButton" % n for k, n in ROUTER.items())


def keys(L):
    return {k: v for k, v in dict(L.eval('BIND')).items() if k.startswith('PAD') or k.startswith('SHIFT-PAD')}


def maybe(xfail, ok, name, why, got=''):
    """xcheck while `xfail` (a case today's code fails), check otherwise"""
    if xfail:
        xcheck(ok, name, why, got)
    else:
        check(ok, name, got)


REVERSED_WHY = ("PadMap's PLAYER_REGEN_DISABLED runs first and carries the keys; then Interact's sets N.fight = \"router\", whose capture "
                "change (capCheck -> onChange) runs PadMap's routerCheck BEFORE the lockdown with the out-of-combat rule: a room holds = aside "
                "\"room\" -> ClearOverrideBindings(router), every key gone for the whole fight")


def pad_keys(r):
    return [d.get('k') for d in r['pads'] if d.get('k')]


def stopattack(r):
    return any(a.get('kind') == 'macroline' and a.get('line') == '/stopattack' for a in r['acts'])


# ================================================================ H. THE HARNESS ITSELF (the enforcement bites where the game's does)
H = world()
check(clean(H) == {}, "H0 the world loads (Safety, TapPad, Map, Quests, Hub, Interact, Dialogs, Book, PadMap, PadTabCtl), strict, clean", clean(H))
H.execute('COMBAT = true')
r = H.eval('(function() local f = WowCompanionRoomEnter; local ok = pcall(function() f:SetAttribute("x", 1) end); return ok end)()')
check(r is False and lst(H, 'VIOL')[-1:] == ["SetAttribute WowCompanionRoomEnter x"],
      "H1 an insecure SetAttribute on a secure frame in a fight: ADDON_ACTION_BLOCKED (VIOL, raises)", lst(H, 'VIOL'))
r = H.eval('(function() local ok = pcall(function() WowCompanionRoomEnter:Show() end); return ok end)()')
check(r is False and lst(H, 'VIOL')[-1:] == ["Show WowCompanionRoomEnter"], "H2 Show on a secure frame in a fight: blocked", lst(H, 'VIOL'))
r = H.eval('(function() local ok = pcall(function() SetOverrideBindingClick(UIParent, true, "PAD1", "X") end); return ok end)()')
check(r is False and lst(H, 'VIOL')[-1:] == ["SetOverrideBinding PAD1"], "H3 an override binding in a fight: blocked", lst(H, 'VIOL'))
H.execute('COMBAT = false; HSEC = CreateFrame("Button", "HarnessSec", UIParent, "SecureHandlerClickTemplate"); HSEC:RegisterForClicks("AnyDown"); '
          'SecureHandlerSetFrameRef(HSEC, "w", LootFrame); SecureHandlerSetFrameRef(HSEC, "r", WowCompanionRoomEnter); '
          'HSEC:SetAttribute("_onclick", [=[ local w = self:GetFrameRef("w"); self:SetAttribute("seen", w:IsShown() and 1 or 0) ]=]); '
          'HSEC2 = CreateFrame("Button", "HarnessSec2", UIParent, "SecureHandlerClickTemplate"); HSEC2:RegisterForClicks("AnyDown"); '
          'SecureHandlerSetFrameRef(HSEC2, "r", WowCompanionRoomEnter); '
          'HSEC2:SetAttribute("_onclick", [=[ local r = self:GetFrameRef("r"); r:SetAttribute("wc-probe", 7); self:SetAttribute("ok", r:IsShown()) ]=])')
H.execute('VIOL = {}; SNIPERR = {}; HANDLEERR = {}; doClick(HSEC, "LeftButton", true)')
check(H.eval('HSEC.a.seen') == 0 and lst(H, 'HANDLEERR') == [], "H4 out of a fight a snippet reads an unprotected frame's handle (LootFrame)",
      (H.eval('HSEC.a.seen'), lst(H, 'HANDLEERR')))
H.execute('COMBAT = true; doClick(HSEC, "LeftButton", true); doClick(HSEC2, "LeftButton", true)')
check(lst(H, 'HANDLEERR') == ["Invalid frame handle LootFrame:IsShown"] and len(lst(H, 'SNIPERR')) == 1,
      "H5 in a fight the same read raises Invalid frame handle (the game's restrictedframes.lua)", (lst(H, 'HANDLEERR'), lst(H, 'SNIPERR')))
check(H.eval('WowCompanionRoomEnter.a["wc-probe"]') == 7 and H.eval('HSEC2.a.ok') is True and lst(H, 'VIOL') == [],
      "H6 ... while a PROTECTED frame's handle works in a fight, and a snippet's own writes are no breach", lst(H, 'VIOL'))
H.execute('COMBAT = false; VIOL = {}; SNIPERR = {}; HANDLEERR = {}; WowCompanionRoomEnter.a["wc-probe"] = nil')
H.execute('HO1 = CreateFrame("Frame"); HO2 = CreateFrame("Frame"); SetOverrideBinding(HO1, true, "CTRL-F12", "A1"); '
          'SetOverrideBinding(HO2, true, "CTRL-F12", "A2"); HB1 = GetBindingAction("CTRL-F12"); ClearOverrideBindings(HO2); HB2 = GetBindingAction("CTRL-F12"); '
          'ClearOverrideBindings(HO1); HB3 = GetBindingAction("CTRL-F12")')
check((H.eval('HB1'), H.eval('HB2'), H.eval('HB3')) == ("A2", "A1", ""), "H7 override bindings layer per owner (the latest on top; a clear shows the one under it)",
      (H.eval('HB1'), H.eval('HB2'), H.eval('HB3')))
r = press(H, "PAD3")
check(r['how'] == 'game' and r['game'] == ['PAD3+', 'PAD3-'], "H8 the press model: a key with no override of ours is the game's", r)
H.execute('BIND.PAD3 = "CLICK WowCompanionRoomEnter:LeftButton"')
r = press(H, "PAD3")
check(r['how'] == 'click' and r['clicks'][:1] == ['WowCompanionRoomEnter+'], "H9 the press model: a CLICK binding clicks its button (the chain logged)", r)
H.execute('BIND.PAD3 = nil')
ok, log = tap(H, {"row": "qact", "col": 10})
check(ok and len(log) == 2, "H10 the tap model: a TapPad cell clicked on both edges", log)
check(not tap(H, {"row": "nosuchrow", "col": 1})[0], "H11 the tap model: no such cell = no tap")

# ================================================================ STATIC SNIPPET SCAN (the design: a GetFrameRef read followed by a method call on a
# frame in the unprotected list fails; the game errors "Invalid frame handle" there in a fight)
UNPROTECTED = ["LootFrame", "GossipFrame", "QuestFrame", "MerchantFrame", "WorldMapFrame", "SpellBookFrame", "PlayerSpellsFrame", "TaxiFrame",
               "BankFrame", "MailFrame", "TradeFrame", "ClassTrainerFrame", "GameMenuFrame", "StaticPopup", "ReadyCheckFrame", "GroupLootFrame",
               "CharacterFrame", "ContainerFrame", "QuestLogFrame", "FriendsFrame", "CommunitiesFrame", "ProfessionsFrame", "TradeSkillFrame",
               "PetStableFrame", "AuctionHouseFrame", "ItemTextFrame", "PetitionFrame", "GamepadRadial", "UIParent"]
LONG = re.compile(r'\[(=*)\[(.*?)\]\1\]', re.S)
REFVAR = re.compile(r'(?:local\s+)?([A-Za-z_]\w*)\s*=\s*[A-Za-z_]\w*:GetFrameRef\(\s*([^)]*)\)')
REFDIRECT = re.compile(r':GetFrameRef\(\s*([^)]*)\)\s*:\s*([A-Za-z_]\w*)\s*\(')


def snippet_reads(body):
    """the frame-ref expressions a snippet calls a method on: [(ref expression, method)]"""
    out = []
    for var, ref in REFVAR.findall(body):
        for m in re.findall(r'\b' + re.escape(var) + r'\s*:\s*([A-Za-z_]\w*)\s*\(', body):
            out.append((ref.strip(), m))
    for ref, m in REFDIRECT.findall(body):
        out.append((ref.strip(), m))
    return out


bodies = {}
for k in WORLD_FILES:
    for _, b in LONG.findall(SRC[k]):
        if 'GetFrameRef' in b:
            bodies.setdefault(b, k)
reads = [(bodies[b], ref, m) for b in bodies for ref, m in snippet_reads(b)]
check(len(reads) > 10, "SC0 the static scan finds the snippets' frame-ref reads (%d)" % len(reads), len(reads))
bad = [(f, ref, m) for f, ref, m in reads if any(n in ref for n in UNPROTECTED)]
check(not bad, "SC1 no snippet names an unprotected game window in a GetFrameRef read with a method call", bad[:4])
# every SecureHandlerSetFrameRef whose frame is an unprotected game window
setrefs = []
for k in WORLD_FILES:
    for m in re.finditer(r'SecureHandlerSetFrameRef\(([^\n]*)', SRC[k]):
        args = m.group(1)
        if any(re.search(r'\b' + n + r'\w*\b', args) for n in UNPROTECTED if n != "UIParent"):
            setrefs.append((k, args.strip()[:100]))
check(not setrefs, "SC2 no SecureHandlerSetFrameRef hands a snippet an unprotected game window", setrefs[:4])


def ref_patterns(rs):
    """ref expressions -> (exact names, prefixes) a snippet reads with a method call"""
    exact, pre = set(), set()
    for _, ref, _ in rs:
        lit = re.match(r'^"([^"]*)"$', ref)
        if lit:
            exact.add(lit.group(1))
            continue
        lead = re.match(r'^"([^"]*)"\s*\.\.', ref)
        if lead:
            pre.add(lead.group(1))
    return exact, pre


def dynamic_scan(L):
    """in a live world: every frame ref (any frame's refs) whose name a snippet reads with a method call must be a PROTECTED frame"""
    rs = list(reads)
    for b in list(L.eval('SNIPPETS').keys()):
        for ref, m in snippet_reads(b):
            rs.append(('runtime', ref, m))
    exact, pre = ref_patterns(rs)
    out = []
    n = int(L.eval('#ALLF'))
    for i in range(1, n + 1):
        f = L.eval('ALLF[%d]' % i)
        refs = f.refs
        if refs is None:
            continue
        for name, ref in dict(refs).items():
            if not (name in exact or any(name.startswith(p) for p in pre)):
                continue
            if not L.eval('isProtected')(ref):
                out.append((f._name, name, ref._name if hasattr(ref, '_name') else '?'))
    return out


# ================================================================ RELOAD IN A FIGHT (the design: no secure pieces exist, nothing is bound, no errors)
RL = world(pre='COMBAT = true', login=False)
RL.execute('for _, a in ipairs({ "WowCompanion", "WowCompanionQuests", "WowCompanionHub", "WowCompanionInteract", "WowCompanionSpells" }) do '
           'fire("ADDON_LOADED", a) end; fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", false, true); advance(10)')
secure_made = RL.eval('(function() local n = 0; for _, f in ipairs(ALLF) do if f._secure then n = n + 1 end end; return n end)()')
ours = [k for k, v in keys(RL).items() if v.startswith('CLICK WowCompanion')] + [k for k, v in dict(RL.eval('BIND')).items() if v.startswith('CLICK WowCompanion')]
c = clean(RL)
check(c == {}, "RL1 a /reload in a fight: the add-ons load with no ADDON_ACTION_BLOCKED, no error (Step 2: i144 F.make out of combat only)",
      {k: v[:4] for k, v in c.items()})
check(secure_made == 0, "RL2 ... no secure piece exists in that fight", secure_made)
check(not ours, "RL3 ... nothing of ours is bound in the fight", ours[:4])
RL.execute('VIOL = {}; ERRS = {}; SNIPERR = {}')
fight_end(RL)
check(RL.eval('WowCompanion.padMap.routerReady()') is True and RL.eval('__IN.N.routerArm()') is True and clean(RL) == {},
      "RL4 ... the fight's end builds what the reload skipped: the router ready, the fight hold armable (Step 2)",
      (RL.eval('WowCompanion.padMap.routerReady()'), RL.eval('__IN.N.routerArm()'), clean(RL)))

# ================================================================ S1. THE BOTTOM MAP OPEN AT THE PULL (the user: "If I am looking at the bottom map then I
# should be able to press B to exit out of it and then start fighting"; design S3: the hold is kept, keys carried, B walks the map's chain
# to Home, then LEAVE closes WorldMapFrame; it stays hidden by alpha until it closes)
for order in ('loaded', 'reversed'):
    W = world()
    W.execute('WorldMapFrame:Show(); advance(3)')
    check(holder(W) == 'map' and W.eval('WorldMapFrame:GetAlpha()') == 0, "S1[%s] out of combat the Map room holds, the game's map hidden by alpha" % order,
          (holder(W), W.eval('WorldMapFrame:GetAlpha()')))
    pull(W, order)
    check(holder(W) == 'map' and carried(W),
          "S1[%s] the pull: the Map room keeps the controller, all 8 router keys carried (Step 2: m134 routerCheck waits for the lockdown)" % order,
          (holder(W), keys(W)))
    check(modes(W) == ['room'] * 8, "S1[%s] the pull: every router button in room mode" % order, modes(W))
    check(W.eval('WorldMapFrame:GetAlpha()') == 0, "S1[%s] the game's map stays hidden by alpha through the pull" % order, W.eval('WorldMapFrame:GetAlpha()'))
    r = press(W, "PADDDOWN")
    check('PADDDOWN' in pad_keys(r) or any('WowCompanionInteractPad' in x for x in r['clicks']),
          "S1[%s] D-pad Down in the fight reaches the Map room" % order, r)
    r = press(W, "PAD2")
    check(modes(W) == ['room'] * 8 and holder(W) == 'map' and any(d.get('k') in ('PAD2', 'BACK') for d in r['pads']),
          "S1[%s] B in the fight goes to the Map room's own B chain (one step back, then Home), the room keeps holding (Step 4)" % order,
          (modes(W)[:2], holder(W), r['clicks']))
    # the app at Home: the reconciler taps LEAVE (doc.fightLeave) - today B already did it
    if holder(W) == 'map':
        tap(W, idoc(W).get('fightLeave'))
    check(holder(W) is None and modes(W) == [None] * 8 and W.eval('WorldMapFrame._shown') is False,
          "S1[%s] the leave: the room lets go, the router back to the fight, the game's map CLOSED (its own close button)" % order,
          (holder(W), modes(W)[:2], W.eval('WorldMapFrame._shown')))
    r = press(W, "PAD2")
    check(stopattack(r), "S1[%s] then B is the fight's (stop attack): \"then start fighting\"" % order, r['acts'])
    check(clean(W) == {}, "S1[%s] no lockdown breach, no spied call, no handle / snippet error" % order, clean(W))
    if order == 'loaded':
        check(dynamic_scan(W) == [], "SC3 (live world) every frame ref a snippet reads with a method call is a protected frame", dynamic_scan(W))
# the Hub's NPC-windows bit off (the companion not in charge of game windows): the bottom map is still the bottom map
W = world(mode=False)
W.execute('WorldMapFrame:Show(); advance(3)')
pull(W)
check(holder(W) == 'map' and carried(W) and modes(W) == ['room'] * 8, "S1[mode off] the Map room keeps the controller at the pull with the NPC-windows bit off (Step 2)",
      (holder(W), modes(W)[:2], W.eval('WorldMapFrame:GetAlpha()')))
check(W.eval('WorldMapFrame:GetAlpha()') == 0, "S1[mode off] ... and the game's map never shows on the top screen in the fight", W.eval('WorldMapFrame:GetAlpha()'))
check(clean(W) == {}, "S1[mode off] no lockdown breach, no spied call, no handle / snippet error", clean(W))
# the escape chord (design Step 4): LT + RT + B always gets out of room mode
W = world()
W.execute('WorldMapFrame:Show(); advance(3)')
pull(W)
r = press(W, "PAD2", lt=True, rt=True)
check(any('WowCompanionRoomLeave' in x for x in r['clicks']) and 'wc-esc' in str(dict(W.eval('WowCompanionFaceB.a'))),
      "S1 LT + RT + B in room mode is the secure escape (wc-esc = WowCompanionRoomLeave)", r['clicks'])
check(holder(W) is None and modes(W) == [None] * 8 and clean(W) == {},
      "S1 ... the escape clears the room mode from inside the snippet (FLIP): the fight's again, no breach", (holder(W), modes(W)[:2], clean(W)))
W = world()
W.execute('WorldMapFrame:Show(); advance(3)')
pull(W)
r = press(W, "PAD2", lt=True)
check(holder(W) == 'map' and modes(W) == ['room'] * 8 and not any('WowCompanionRoomLeave' in x for x in r['clicks']),
      "S1 LT + B alone is no escape: the room's own B", (holder(W), r['clicks']))

# ================================================================ S2. LOOTING IN A FIGHT (the user: "If I am looting ... my dpad should not stop working";
# the user's decision 2: loot always mirrored on the bottom screen in fights, whatever the "loot on top" switch says)
for order in ('loaded', 'reversed'):
    W = world()
    pull(W, order)
    W.execute('LootFrame:Show(); fire("LOOT_OPENED"); advance(1)')
    d = idoc(W)
    check(d.get('loot') and [i['name'] for i in d['loot']['items']] == ['Linen Cloth', 'Rough Stone'],
          "S2[%s] loot opens in the fight: mirrored on the bottom screen" % order, d.get('loot'))
    check(W.eval('LootFrame:GetAlpha()') == 0, "S2[%s] ... the game's loot window hidden by alpha" % order, W.eval('LootFrame:GetAlpha()'))
    check((d.get('enter') or {}).get('for') == 'npc', "S2[%s] ... the controller waits for the app's ENTER tap (doc.enter for npc)" % order, d.get('enter'))
    tap(W, d.get('enter'))
    check(holder(W) == 'npc' and modes(W) == ['room'] * 8, "S2[%s] the ENTER tap: the loot holds, every router button in room mode" % order,
          (holder(W), modes(W)[:2]))
    r = press(W, "PADDDOWN")
    nav = (idoc(W).get('nav') or {})
    check(nav.get('cur') == 'loot 2' and 'PADDDOWN' == nav.get('last'), "S2[%s] D-pad Down moves the loot cursor (loot 2)" % order, nav.get('cur'))
    r = press(W, "PAD1")
    check([a for a in r['acts'] if a.get('kind') == 'lootslot'] == [{'kind': 'lootslot', 'slot': 2}], "S2[%s] A loots the picked item (slot 2)" % order, r['acts'])
    r = press(W, "PAD2")
    d = idoc(W)
    check(holder(W) == 'npc' and modes(W) == ['room'] * 8 and d.get('fightBack') and 'CloseLoot' not in lst(W, 'SPY'),
          "S2[%s] B at the loot's top level (Step 4: B is the sink's PAD2): no game close from our code, the app asked for the LEAVE (doc.fightBack)" % order,
          (holder(W), d.get('fightBack')))
    tap(W, d.get('fightLeave'))                  # the app's LEAVE (PadOwnerSync: fightBack stepped)
    check(holder(W) is None and modes(W) == [None] * 8, "S2[%s] the app's LEAVE: the router back to the fight" % order, (holder(W), modes(W)[:2]))
    check(W.eval('LootFrame._shown') is False, "S2[%s] ... and the game's loot window is CLOSED by that leave (Step 5c: its own pre-aimed leave)" % order,
          (W.eval('LootFrame._shown'), W.eval('LootFrame:GetAlpha()')))
    check(W.eval('LootFrame:GetAlpha()') == 0 or W.eval('LootFrame._shown') is False,
          "S2[%s] ... a window the leave could not close stays hidden by alpha in the fight (never unhidden mid-fight)" % order, W.eval('LootFrame:GetAlpha()'))
    fight_end(W)
    check(W.eval('LootFrame._shown') is False, "S2[%s] the fight's end: no loot window left behind (closed by its leave)" % order, W.eval('LootFrame._shown'))
    check(clean(W) == {}, "S2[%s] no lockdown breach, no spied call, no handle / snippet error" % order, clean(W))
# loot open AT the pull (looting when a mob pulls you): the npc hold is kept, the D-pad keeps working
for order in ('loaded', 'reversed'):
    tag = 'at pull, ' + order
    W = world()
    W.execute('LootFrame:Show(); fire("LOOT_OPENED"); advance(1)')
    check(holder(W) == 'npc', "S2[%s] out of combat the loot window holds the controller" % tag, holder(W))
    pull(W, order)
    check(holder(W) == 'npc' and carried(W) and modes(W) == ['room'] * 8,
          "S2[%s] the pull keeps the loot's hold (keys carried, room mode)" % tag, (holder(W), modes(W)[:2], keys(W)))
    press(W, "PADDDOWN")
    check((idoc(W).get('nav') or {}).get('cur') == 'loot 2', "S2[%s] D-pad Down still moves the loot cursor in the fight" % tag, (idoc(W).get('nav') or {}).get('cur'))
    r = press(W, "PAD2")
    if holder(W) is not None:
        tap(W, idoc(W).get('fightLeave'))
    check(holder(W) is None, "S2[%s] B / the leave gives the fight back" % tag, (holder(W), r['clicks']))
    check(W.eval('LootFrame._shown') is False, "S2[%s] ... and closes the loot window (Step 5c: the npc hold's own leave)" % tag,
          (W.eval('LootFrame._shown'), r['clicks']))
    check(W.eval('LootFrame._shown') is False or W.eval('LootFrame:GetAlpha()') == 0,
          "S2[%s] ... a loot window it could not close stays hidden by alpha in the fight (i136's fight dim)" % tag, W.eval('LootFrame:GetAlpha()'))
    check(clean(W) == {}, "S2[%s] no lockdown breach, no spied call, no handle / snippet error" % tag, clean(W))
# the "loot on top" switch on: in a fight the loot is mirrored anyway (decision 2), out of combat the switch keeps its meaning
W = world(loot_top=True)
W.execute('LootFrame:Show(); fire("LOOT_OPENED"); advance(1)')
check(not idoc(W).get('loot') and holder(W) is None, "S2[loot on top] out of combat the switch keeps its meaning (the game's own window)", idoc(W).get('loot'))
W.execute('LootFrame:Hide(); fire("LOOT_CLOSED"); advance(1)')
pull(W)
W.execute('LootFrame:Show(); fire("LOOT_OPENED"); advance(1)')
d = idoc(W)
check(d.get('loot') and (d.get('enter') or {}).get('for') == 'npc' and W.eval('LootFrame:GetAlpha()') == 0,
      "S2[loot on top] in a fight the loot is mirrored on the bottom whatever the switch (the user's decision 2; i144 LOOT.top)",
      (bool(d.get('loot')), d.get('enter'), W.eval('LootFrame:GetAlpha()')))
check(clean(W) == {}, "S2[loot on top] no lockdown breach, no spied call, no handle / snippet error", clean(W))
# the NPC-windows bit off: loot in a fight still on the bottom with the D-pad (the design: rooms no longer depend on the Hub's bit)
W = world(mode=False)
pull(W)
W.execute('LootFrame:Show(); fire("LOOT_OPENED"); advance(1)')
d = idoc(W)
check((d.get('enter') or {}).get('for') == 'npc', "S2[mode off] loot in a fight waits for the ENTER tap with the NPC-windows bit off too (i144)", d.get('enter'))
check(clean(W) == {}, "S2[mode off] no lockdown breach, no spied call, no handle / snippet error", clean(W))

# ================================================================ S3. B AND FULL NAVIGATION IN A FIGHT (the user: "If I am in combat and press B I should be
# able to fully navigate everything like normal")
for combat in (False, True):
    tag = 'fight' if combat else 'no fight'
    W = world(pre='UNITS.target = { name = "Kobold", alive = true, foe = true }')
    if combat:
        pull(W)
        check(carried(W), "S3[%s] the pull with nothing open: all 8 router keys carried" % tag, keys(W))
        r = press(W, "PAD2")
        check(stopattack(r), "S3[%s] B is the fight's stop attack" % tag, r['acts'])
    qtap(W, QACT["map"])                         # the app opens Home's map module (D-pad Left / the bar)
    d = idoc(W)
    if combat:
        check((d.get('enter') or {}).get('for') == 'pad:map' and holder(W) is None, "S3[%s] the map module's take waits for the ENTER tap" % tag, d.get('enter'))
        tap(W, d.get('enter'))
    check(holder(W) == 'pad:map', "S3[%s] the map module holds the controller" % tag, holder(W))
    if combat:
        check(modes(W) == ['room'] * 8, "S3[%s] every router button in room mode" % tag, modes(W))
    r = press(W, "PADDDOWN")
    check(pad_keys(r) == ['PADDDOWN'], "S3[%s] D-pad Down reaches the module (guestpad PADDDOWN)" % tag, r['pads'])
    r = press(W, "PAD1")
    check(pad_keys(r) == ['PAD1'], "S3[%s] A reaches the module" % tag, r['pads'])
    # room to room: the app opens the Map room's quest panel (the quests guest) from the module
    n0 = nsent(W)
    qtap(W, QACT["quests"])
    lost = [d for d in docs(W, n0, 'guestpad') if d.get('id') == 'map' and d.get('lost')]
    if combat:
        check(holder(W) == 'pad:quests' and lost and lost[-1]['lost'] == 'taken' and clean(W) == {} and modes(W) == ['room'] * 8,
              "S3[%s] room to room is an in-place swap: the new room holds at once, the old one lost(taken), no secure write (Step 3)" % tag,
              (holder(W), idoc(W).get('wait'), lost))
    else:
        check(holder(W) in ('pad:quests', 'pad:map'), "S3[%s] room to room out of a fight: the capture's queue / pre-emption as before" % tag, holder(W))
    # B: the app's own B chain decides (one step back or Home); at Home the reconciler taps LEAVE
    W2 = W
    if combat:
        r = press(W2, "PAD2")
        check(modes(W2) == ['room'] * 8 and pad_keys(r) == ['PAD2'], "S3[%s] B in room mode goes to the app as PAD2 (its B chain), the mode stays (Step 4)" % tag,
              (modes(W2)[:2], r['clicks']))
        if holder(W2) is not None:
            tap(W2, idoc(W2).get('fightLeave'))
        check(holder(W2) is None and modes(W2) == [None] * 8, "S3[%s] Home: the leave gives the fight back (router modes cleared)" % tag,
              (holder(W2), modes(W2)[:2]))
        r = press(W2, "PAD2")
        check(stopattack(r) and r['clicks'] == ['WowCompanionFaceB+', 'WowCompanionFaceB-'],
              "S3[%s] then B is the fight's again (the bars given back already: stop attack + clear target)" % tag, r)
    else:
        r = press(W2, "PAD2")
        check(pad_keys(r) == ['PAD2'], "S3[%s] B reaches the room (its own B chain)" % tag, r['pads'])
    check(clean(W2) == {}, "S3[%s] no lockdown breach, no spied call, no handle / snippet error" % tag, clean(W2))
# the NPC-windows bit off: a room opened in a fight still gets the controller
W = world(mode=False)
pull(W)
n0 = nsent(W)
qtap(W, QACT["map"])
ack = [d for d in docs(W, n0, 'guestack') if d.get('id') == 'map']
check(ack and ack[-1].get('ok') is True and ack[-1].get('why') == 'pending' and (idoc(W).get('enter') or {}).get('for') == 'pad:map',
      "S3[mode off] a room opened in a fight answers pending (an ENTER to tap), never \"combat\" (Step 2: armed out of combat)", ack)
check(clean(W) == {}, "S3[mode off] no lockdown breach, no spied call, no handle / snippet error", clean(W))

# ================================================================ PARTY MODE IN A FIGHT (design: wc-party routing stays, room mode first; a room only
# suspends Party mode, it resumes after LEAVE and nothing turns it off)
W = world(pre='BOOK = { 403, 331, 324 }')
W.execute('UNITS.party1 = { name = "Brannoc", cls = "WARRIOR", alive = true, role = "TANK", pt = "RAGE", guid = "Player-1-Brannoc" }; '
          'fire("GROUP_ROSTER_UPDATE"); advance(1); NOW = NOW + 2; tick(0.1)')
pull(W)
check(carried(W) and keys(W).get('PADBACK') == 'CLICK WowCompanionPartyToggle:LeftButton', "P1 grouped, the pull: all 8 keys and Select carried", keys(W))
press(W, "PADBACK")
pad = 'rawget(_G, "WowCompanionPartyPad").a'
check(W.eval(pad + '["wc-party"]') is True, "P2 Select in the fight turns Party mode on", W.eval(pad + '["wc-party"]'))
press(W, "PADDDOWN")
check(W.eval(pad + '["wc-u"]') == 'party1', "P3 D-pad Down picks party1", W.eval(pad + '["wc-u"]'))
qtap(W, QACT["map"])
tap(W, idoc(W).get('enter'))
r = press(W, "PADDUP")
check(holder(W) == 'pad:map' and pad_keys(r) == ['PADDUP'] and W.eval(pad + '["wc-u"]') == 'party1',
      "P4 a room entered in the fight gets the D-pad first (room mode over Party mode); the pick does not move", (holder(W), r['pads']))
if holder(W) is not None:
    r = press(W, "PAD2")
    if holder(W) is not None:
        tap(W, idoc(W).get('fightLeave'))
check(holder(W) is None and W.eval(pad + '["wc-party"]') is True, "P5 the leave: Party mode still on (only suspended)", W.eval(pad + '["wc-party"]'))
press(W, "PADDUP")
check(W.eval(pad + '["wc-u"]') == 'player', "P6 ... and the D-pad drives the party pick again (Up: you)", W.eval(pad + '["wc-u"]'))
check(clean(W) == {}, "P7 Party mode in a fight: no lockdown breach, no spied call, no handle / snippet error", clean(W))

# ================================================================ THE GAME'S WINDOWS AT THE PULL (the user's decision 1: "those scenarios should be built on the
# bottom screen. If we are in the game menu though ... it should be locked until the game menu closes. That's the only valid example")
for win, grouped in (("GameMenuFrame", False), ("GameMenuFrame", True)):
    W = world()
    if grouped:
        W.execute('UNITS.party1 = { name = "Brannoc", cls = "WARRIOR", alive = true, role = "TANK", pt = "RAGE", guid = "Player-1-Brannoc" }; '
                  'fire("GROUP_ROSTER_UPDATE"); advance(1)')
    W.execute('%s:Show(); advance(1); NOW = NOW + 2; tick(0.1)' % win)
    pull(W)
    r = press(W, "PADDDOWN")
    ok = r['how'] == 'game' and not any(v.startswith('CLICK WowCompanion') for k, v in keys(W).items() if k in ROUTER)
    tag = "W[%s, %s] the game menu open at the pull: the game keeps the pad (its own navigation)" % (win, 'grouped' if grouped else 'solo')
    check(ok, tag, keys(W))
    gtag = "W[%s, %s]" % (win, 'grouped' if grouped else 'solo')
    check(idoc(W).get('gmLock') is True, gtag + " ... and the bottom screen is locked while it shows (doc.gmLock)", idoc(W).get('gmLock'))
    W.execute('%s:Hide(); advance(1)' % win)
    check(not idoc(W).get('gmLock'), gtag + " ... released when the game menu closes", idoc(W).get('gmLock'))
    check(clean(W) == {}, "W[%s, %s] no lockdown breach, no spied call, no handle / snippet error" % (win, 'grouped' if grouped else 'solo'), clean(W))
for win in ("StaticPopup1", "ReadyCheckFrame", "GroupLootFrame1"):
    W = world()
    W.execute('%s:Show(); advance(1); NOW = NOW + 2; tick(0.1)' % win)
    pull(W)
    check(carried(W), "W[%s] a pop-up / ready check / loot roll at the pull (solo): the controller is kept (handled on the bottom screen; m134)" % win, keys(W))
    check(not idoc(W).get('gmLock'), "W[%s] ... no lock (only the game menu locks)" % win, idoc(W).get('gmLock'))
    check(clean(W) == {}, "W[%s] no lockdown breach, no spied call, no handle / snippet error" % win, clean(W))

# ================================================================ STEP 2: CARRY EVERYTHING AT THE PULL, ARM OUT OF COMBAT
# {window open, no holder} x {Party mode allowed or not} x {the D-pad toolbar on or off}: every key bound, R.aside nil (the user's decision 1)
for win in (None, "StaticPopup1", "ReadyCheckFrame", "GroupLootFrame1", "MerchantFrame"):
    for party_ok in (True, False):
        for tb in (True, False):
            tag = "C[%s, party %s, toolbar %s]" % (win or 'nothing', 'ok' if party_ok else 'off', 'on' if tb else 'off')
            W = world(party_ok=party_ok, pre='mkwin("MerchantFrame", false); WowCompanionSpellsDB = { dpad = setmetatable({}, { __index = function() return %s end }) }'
                      % ('true' if tb else 'false'))
            if win:
                W.execute('%s:Show(); advance(1); NOW = NOW + 2; tick(0.1)' % win)
            pull(W)
            st = W.eval('WowCompanion.padMap.dpadState()')
            check(carried(W) and st.aside is None, tag + " the pull: all 8 router keys carried, R.aside nil", (keys(W), st.aside))
            check(clean(W) == {}, tag + " no lockdown breach, no spied call, no handle / snippet error", clean(W))
# a first fight's take (no pull with anything held) answers pending, never "combat"
W = world()
pull(W)
n0 = nsent(W)
qtap(W, QACT["quests"])
ack = [d for d in docs(W, n0, 'guestack') if d.get('id') == 'quests']
check(ack and ack[-1].get('ok') is True and ack[-1].get('why') == 'pending', "A1 the first fight's take answers pending (armed out of combat)", ack)
check(clean(W) == {}, "A1 no lockdown breach, no spied call, no handle / snippet error", clean(W))
# the fight hold is armed out of combat, before any pull (N.ra), with the NPC-windows bit off too
W = world(mode=False)
check(W.eval('__IN.N.ra') is not None, "A2 the fight hold is armed out of combat (N.ra) with the NPC-windows bit off", W.eval('__IN.N.ra'))
# REGEN_ENABLED gives an "npc" holder its bindings and the grab back (audit item 11)
W = world()
W.execute('LootFrame:Show(); fire("LOOT_OPENED"); advance(1)')
pull(W)
W.execute('BIND.PADDDOWN = nil')                 # the game took a key meanwhile: N.check() takes it back
fight_end(W)
check(holder(W) == 'npc' and W.eval('WowCompanionInteractGrab._shown') is not False
      and (W.eval('BIND.PADDDOWN') or '').startswith('CLICK WowCompanionInteract'),
      "A3 the fight's end: the loot held through it gets its bindings and the grab back", (holder(W), W.eval('BIND.PADDDOWN')))
check(clean(W) == {}, "A3 no lockdown breach, no spied call, no handle / snippet error", clean(W))

# ================================================================ STEP 3: THE MODE REPORT, THE STRAY ENTER ("app"), THE IN-PLACE SWAP
W = world()
check(idoc(W).get('fightMode') is None and idoc(W).get('fightEnter') is None, "M1 out of a fight: no fightMode / fightEnter in the document",
      (idoc(W).get('fightMode'), idoc(W).get('fightEnter')))
pull(W)
d = idoc(W)
check(d.get('fightMode') == 'game' and (d.get('fightEnter') or {}).get('col') is not None and not d.get('enter'),
      "M2 the pull with nothing held: fightMode \"game\", the ENTER cell always there for the app's reconciler", (d.get('fightMode'), d.get('fightEnter')))
tap(W, d.get('fightEnter'))
d = idoc(W)
check(holder(W) == 'app' and modes(W) == ['room'] * 8 and d.get('fightMode') == 'room',
      "M3 a stray ENTER (the app wants the pad for something of its own): holder \"app\", room mode, fightMode \"room\"", (holder(W), d.get('fightMode')))
r = press(W, "PADDDOWN")
check(r['game'] == [] and not stopattack(r), "M4 ... its keys are swallowed (the app reads its own controller): nothing reaches the game", r)
check(d.get('holder') == 'app' and d.get('fightLeave'), "M5 ... never an \"npc\" (no orphan-NPC leave), the leave cell published", (d.get('holder'), d.get('fightLeave')))
n0 = nsent(W)
qtap(W, QACT["map"])
ack = [x for x in docs(W, n0, 'guestack') if x.get('id') == 'map']
check(holder(W) == 'pad:map' and not idoc(W).get('enter') and modes(W) == ['room'] * 8,
      "M6 a room's take over the \"app\" holder: an in-place swap, held at once (no second ENTER)", (holder(W), idoc(W).get('enter'), ack))
r = press(W, "PADDUP")
check(pad_keys(r) == ['PADDUP'], "M7 ... and the D-pad reaches it", r['pads'])
tap(W, idoc(W).get('fightLeave'))
check(holder(W) is None and modes(W) == [None] * 8 and idoc(W).get('fightMode') == 'game', "M8 the LEAVE: fightMode back to \"game\"",
      (holder(W), idoc(W).get('fightMode')))
check(clean(W) == {}, "M9 the mode report, the stray ENTER and the swap: no lockdown breach, no spied call, no handle / snippet error", clean(W))
# a room to room swap leaves the old room's game window hidden by alpha (never unhidden mid-fight)
W = world()
W.execute('WorldMapFrame:Show(); advance(3)')
pull(W)
qtap(W, QACT["quests"])
check(holder(W) == 'pad:quests' and W.eval('WorldMapFrame:GetAlpha()') == 0 and clean(W) == {},
      "M10 the Map room swapped for another in the fight: the game's map stays hidden by alpha, no secure write",
      (holder(W), W.eval('WorldMapFrame:GetAlpha()'), clean(W)))

# a noFight guest (Dialogs' pop-up hold) at the pull: released, every key still carried; the app's ENTER then gives it the pad ("app")
W = world()
W.execute('NFT = {}; WowCompanion.capture.take("dialogs", { press = function(k) NFT[#NFT + 1] = k end, noFight = true }); advance(1)')
check(holder(W) == 'dialogs', "N1 a noFight guest (the pop-up's hold) out of combat", holder(W))
pull(W)
check(holder(W) is None and carried(W) and idoc(W).get('fightMode') == 'game', "N2 the pull: a noFight guest is released, every key carried",
      (holder(W), keys(W)))
tap(W, idoc(W).get('fightEnter'))
r = press(W, "PAD1")
check(holder(W) == 'app' and r['game'] == [] and not any(a.get('kind') == 'spell' for a in r['acts']),
      "N3 ... the pop-up's mirror up (DialogView wants the pad): the ENTER, A reaches nobody in the game (the app answers its cell)", (holder(W), r))
check(clean(W) == {}, "N4 no lockdown breach, no spied call, no handle / snippet error", clean(W))

# ================================================================ STEP 5: LEAVING WINDOWS CLEANLY
# 5c's budget: the per-window leave cells are the idlg row's last columns (no new row; TapPad's 40 columns, 56 rows)
W = world()
pd = W.eval('WowCompanion.pad.describe()')
sizes = dict(pd.sizes) if pd is not None else {}
check(pd is not None and sizes.get('idlg') == 40 and sizes['idlg'] <= int(pd.cols) and len(sizes) < 56,
      "5c the TapPad row budget: idlg grows to 40 columns (<= cols), no new row (%d rows placed)" % len(sizes), (sizes.get('idlg'), len(sizes)))
# 5c: the cell chosen - an npc hold's own window, a room keeps the leave the pull aimed at its own close
pull(W)
W.execute('LootFrame:Show(); fire("LOOT_OPENED"); advance(1)')
tap(W, idoc(W).get('enter'))
fl = idoc(W).get('fightLeave') or {}
check(fl.get('row') == 'idlg' and fl.get('col') == 33, "5c loot held in the fight: doc.fightLeave is the loot window's own leave cell (idlg 33)", fl)
check(clean(W) == {}, "5c no lockdown breach, no spied call, no handle / snippet error", clean(W))
W = world()
W.execute('WorldMapFrame:Show(); advance(3)')
pull(W)
fl = idoc(W).get('fightLeave') or {}
check(fl.get('col') == 24, "5c the Map room held at the pull: the generic leave the pull aimed at its own close (idlg 24)", fl)
# 5a: a window its leave could not close stays hidden by alpha through the fight; the fight's end makes it the bottom screen's again
W = world(pre='LootFrame.CloseButton = nil; LootFrame.BorderFrame = nil; LootFrameCloseButton = nil')
pull(W)
W.execute('LootFrame:Show(); fire("LOOT_OPENED"); advance(1)')
tap(W, idoc(W).get('enter'))
press(W, "PAD2")
tap(W, idoc(W).get('fightLeave'))
check(holder(W) is None and W.eval('LootFrame._shown') is True and W.eval('LootFrame:GetAlpha()') == 0,
      "5a a loot window with no close: the leave lets go, the window stays hidden by alpha (never unhidden mid-fight)",
      (holder(W), W.eval('LootFrame._shown'), W.eval('LootFrame:GetAlpha()')))
W.execute('advance(2); NOW = NOW + 2; tick(0.1); advance(1)')
check(W.eval('LootFrame:GetAlpha()') == 0 and holder(W) is None, "5a ... still hidden a moment later in the fight (documents come and go)",
      (W.eval('LootFrame:GetAlpha()'), holder(W)))
fight_end(W)
check(holder(W) == 'npc' and W.eval('LootFrame:GetAlpha()') == 0, "5a the fight's end: the loot window is the bottom screen's again (held, B closes it)",
      (holder(W), W.eval('LootFrame:GetAlpha()')))
check(clean(W) == {}, "5a no lockdown breach, no spied call, no handle / snippet error", clean(W))
# 5b: a game window open at the pull with nothing of ours holding it: the first bare B is /click <close> then /stopattack
for combat_tag, mode in (("NPC-windows on", True), ("NPC-windows off", False)):
    W = world(mode=mode, pre='mkwin("MerchantFrame", false); UNITS.target = { name = "Kobold", alive = true, foe = true }')
    W.execute('MerchantFrame:Show(); advance(1); NOW = NOW + 2; tick(0.1)')
    pull(W)
    check(W.eval('WowCompanionFaceB.a["wc-pull-close"]') == 'MerchantFrameCloseButton',
          "5b[%s] the pull names the open window's close on B (wc-pull-close)" % combat_tag, W.eval('WowCompanionFaceB.a["wc-pull-close"]'))
    r = press(W, "PAD2")
    lines = [a.get('line') for a in r['acts'] if a.get('kind') == 'macroline']
    seq = [(a.get('kind'), a.get('frame') or a.get('line')) for a in r['acts']]
    check(seq[:2] == [('close', 'MerchantFrame'), ('macroline', '/stopattack')] and W.eval('MerchantFrame._shown') is False,
          "5b[%s] the first B: /click MerchantFrameCloseButton (its own close ran) then /stopattack" % combat_tag, seq)
    check(W.eval('WowCompanionFaceB.a["wc-pull-close"]') is None, "5b[%s] ... the name spent (cleared by the snippet)" % combat_tag,
          W.eval('WowCompanionFaceB.a["wc-pull-close"]'))
    r = press(W, "PAD2")
    check(not any(a.get('kind') == 'close' for a in r['acts']) and r['clicks'][:1] == ['WowCompanionFaceB+'] and r['game'] == [],
          "5b[%s] the next B is the fight's own again (m99: no close, the router's B)" % combat_tag, r)
    check(clean(W) == {}, "5b[%s] no lockdown breach, no spied call, no handle / snippet error" % combat_tag, clean(W))
# 5b: nothing named with nothing open, nor for a window ours holds (loot held: its own leave)
W = world()
pull(W)
check(W.eval('WowCompanionFaceB.a["wc-pull-close"]') is None, "5b nothing open at the pull: no pull-close", W.eval('WowCompanionFaceB.a["wc-pull-close"]'))
W = world()
W.execute('LootFrame:Show(); fire("LOOT_OPENED"); advance(1)')
pull(W)
check(W.eval('WowCompanionFaceB.a["wc-pull-close"]') is None, "5b the loot held at the pull: no pull-close (its own leave)", W.eval('WowCompanionFaceB.a["wc-pull-close"]'))
fight_end(W)
check(W.eval('WowCompanionFaceB.a["wc-pull-close"]') is None, "5b the fight's end clears it", W.eval('WowCompanionFaceB.a["wc-pull-close"]'))

# ================================================================ THE REVIEW OF 2026-11-11 (each case once failed on 39a11c13; Interact i148, Dialogs d28)
def shields(L):
    return len(lst(L, 'WowCompanion.shield.rects()'))


# R1: a stray ENTER's "app" holder ends with the fight - the controller is the game's again after it, nothing of ours bound
W = world()
pull(W)
tap(W, idoc(W).get('fightEnter'))
check(holder(W) == 'app', "R1 a stray ENTER in the fight: the \"app\" holder", holder(W))
fight_end(W)
ours = {k: v for k, v in keys(W).items() if (v or '').startswith('CLICK WowCompanionInteract')}
check(holder(W) is None and W.eval('__IN.N.holding') is False and not ours,
      "R1 ... the fight's end lets it go: no holder, not holding, no key of ours bound", (holder(W), W.eval('__IN.N.holding'), ours))
W.execute('NOW = NOW + 30; advance(1)')
r = press(W, "PADDDOWN")
check(not any(c.startswith('WowCompanionInteractPad') for c in r['clicks']) and holder(W) is None,
      "R1 ... a press 30 s later never reaches our pad sink", r)
check(clean(W) == {}, "R1 no lockdown breach, no spied call, no handle / snippet error", clean(W))

# R2: Step 5a with the frames the real game shows (the input legend, the tooltip) and the real loot close (LOOT_CLOSED a frame
# later, the 0.1 s fade): a leave that closed the window gives everything back - no "left behind", no shield, the alphas back
for at_pull in (False, True):
    tag = 'at the pull' if at_pull else 'mid-fight'
    W = world()
    W.execute('GamepadPersistentInputLegend:Show(); GameTooltip:Show()')
    if at_pull:
        W.execute('LootFrame:Show(); fire("LOOT_OPENED"); advance(1)')
        pull(W)
    else:
        pull(W)
        W.execute('LootFrame:Show(); fire("LOOT_OPENED"); advance(1)')
        tap(W, idoc(W).get('enter'))
    check(holder(W) == 'npc' and shields(W) >= 1 and W.eval('GameTooltip:GetAlpha()') == 0,
          "R2[%s] the loot held in the fight: shielded, the tooltip dimmed" % tag, (holder(W), shields(W)))
    press(W, "PAD2")
    tap(W, idoc(W).get('fightLeave'))
    W.execute('advance(1)')
    check(W.eval('LootFrame._shown') is False and W.eval('__IN.N.fightBehind') is None and shields(W) == 0,
          "R2[%s] the leave closed the loot window (through its fade): nothing left behind, no shield over its place" % tag,
          (W.eval('LootFrame._shown'), W.eval('__IN.N.fightBehind'), shields(W)))
    check(W.eval('GameTooltip:GetAlpha()') == 1 and W.eval('GamepadPersistentInputLegend:GetAlpha()') == 1,
          "R2[%s] ... the tooltip and the input legend have their alpha back" % tag,
          (W.eval('GameTooltip:GetAlpha()'), W.eval('GamepadPersistentInputLegend:GetAlpha()')))
    check(clean(W) == {}, "R2[%s] no lockdown breach, no spied call, no handle / snippet error" % tag, clean(W))

# R3: the game menu open at the pull, then closed in the fight: the fight stays the game's (PadMap stepped aside) - no ENTER cell, an
# ENTER tap anyway changes nothing, fightMode stays "game" and gmFight tells the app's reconciler not to try
W = world()
W.execute('GameMenuFrame:Show(); advance(1); NOW = NOW + 2; tick(0.1)')
pull(W)
W.execute('GameMenuFrame:Hide(); advance(1)')
d = idoc(W)
check(not d.get('gmLock') and d.get('gmFight') is True and d.get('fightMode') == 'game' and not d.get('fightEnter'),
      "R3 the game menu closed mid-fight: no lock, gmFight, fightMode \"game\", no ENTER cell", (d.get('gmLock'), d.get('gmFight'), d.get('fightEnter')))
tap(W, {'row': W.eval('__IN.N.ra.erow'), 'col': W.eval('__IN.N.ra.ecol')})
d = idoc(W)
r = press(W, "PADDDOWN")
check(holder(W) is None and W.eval('__IN.N.holding') is not True and d.get('fightMode') == 'game' and r['how'] == 'game',
      "R3 ... an ENTER tap anyway: no holder, fightMode still \"game\", the press is the game's", (holder(W), d.get('fightMode'), r['how']))
n0 = nsent(W)
qtap(W, QACT["quests"])
ack = [x for x in docs(W, n0, 'guestack') if x.get('id') == 'quests']
check(holder(W) is None and not idoc(W).get('enter') and not (ack and ack[-1].get('why') == 'pending'),
      "R3 ... a room's take in that fight never waits for an ENTER", (holder(W), ack))
check(clean(W) == {}, "R3 no lockdown breach, no spied call, no handle / snippet error", clean(W))

# R4: a pop-up the app shows as its box while the loot holds in a fight (a BoP item looted): A and B are the pop-up's (DialogView taps
# its cells) - never also the loot cursor's A or the window's B / leave; gone, they are the loot's again
W = world()
pull(W)
W.execute('LootFrame:Show(); fire("LOOT_OPENED"); advance(1)')
tap(W, idoc(W).get('enter'))
W.execute('StaticPopup1.which = "LOOT_BIND"; StaticPopup1:Show(); advance(0.2); WowCompanion.dialogs.look(); advance(0.5)')
check(W.eval('WowCompanion.dialogs.mirrored()') == 1, "R4 the bind pop-up is mirrored (the app's box)", W.eval('WowCompanion.dialogs.mirrored()'))
b0 = idoc(W).get('fightBack') or 0
r1 = press(W, "PAD1")
r2 = press(W, "PAD2")
d = idoc(W)
check(not any(a.get('kind') == 'lootslot' for a in r1['acts'] + r2['acts']) and (d.get('fightBack') or 0) == b0 and holder(W) == 'npc'
      and W.eval('LootFrame._shown') is True,
      "R4 ... A and B on the pop-up: no loot taken, no B ask for the LEAVE, the loot still held", (r1['acts'], d.get('fightBack'), holder(W)))
W.execute('StaticPopup1:Hide(); advance(0.2); WowCompanion.dialogs.look(); advance(0.5)')
r = press(W, "PAD1")
check([a for a in r['acts'] if a.get('kind') == 'lootslot'] == [{'kind': 'lootslot', 'slot': 1}],
      "R4 the pop-up gone: A loots again", r['acts'])
check(clean(W) == {}, "R4 no lockdown breach, no spied call, no handle / snippet error", clean(W))

# ================================================================ B -> HOME FROM EVERY ROOM, IN A FIGHT AND OUT (the user, 2026-11-12, on 1429: "ALL ROOMS
# SHOULD ALWAYS GO HOME WITH B EVEN IN COMBAT"; the log: Bags opened 1 s before the pull, then B did nothing - Bags' own B let go through
# capture.release("bags", "B"), which a fight answers "tap leave", and nobody told the app). Interact i151: that release asks the app to
# go Home (doc.fightHome); Home's LEAVE (PadOwnerSync) lets go. THE MATRIX: every room x {opened out of combat, then the pull} x {opened in a
# fight} (and x {no fight}: unchanged): a sub-screen's B steps back first (where the stub can open one), the top level's B reaches Home - the
# room's own B asking (fightHome) or the app's B chain getting the press (its pad document) - then Home's LEAVE, the next B is the fight's
# again, #VIOL == 0. The rooms' REAL files load over the same stub.
from wowstub import ADDONS, ADDON_OF, SRC as _SRC
for _k, (_d, _f) in {"bags": ("WowCompanion", "Bags.lua"), "character": ("WowCompanionCharacter", "Character.lua"),
                     "social": ("WowCompanionSocial", "Social.lua"), "calendar": ("WowCompanionCalendar", "Calendar.lua"),
                     "gfinder": ("WowCompanionGroupFinder", "GroupFinder.lua"), "prof": ("WowCompanionProfessions", "Professions.lua"),
                     "spellsroom": ("WowCompanionSpells", "Spells.lua")}.items():
    F[_k] = os.path.join(ADDONS, _d, _f)
    _SRC[_k] = open(F[_k], encoding="utf-8").read()
    ADDON_OF[_k] = _d
# (Spells s10: the Macros room's block lives in Spells.lua, loaded before Book as the game's .toc does)
ROOM_FILES = tuple(list(WORLD_FILES[:2]) + ["bags"] + list(WORLD_FILES[2:7]) + ["spellsroom"] + list(WORLD_FILES[7:]) +
                   ["character", "social", "calendar", "gfinder", "prof"])
ROOM_LOGIN = ('for _, a in ipairs({ "WowCompanion", "WowCompanionQuests", "WowCompanionHub", "WowCompanionInteract", "WowCompanionSpells", '
              '"WowCompanionCharacter", "WowCompanionSocial", "WowCompanionCalendar", "WowCompanionGroupFinder", "WowCompanionProfessions" }) do '
              'fire("ADDON_LOADED", a) end; fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
BAG_PRE = ('C_Container = { GetContainerNumSlots = function(b) if b == 0 then return 16 end return 0 end, '
           'GetContainerItemID = function(b, s) if b == 0 and s == 1 then return 2589 end end, '
           'GetContainerItemInfo = function(b, s) if b == 0 and s == 1 then return { itemID = 2589, stackCount = 2, quality = 1, iconFileID = 1 } end end, '
           'GetContainerItemLink = function() return nil end, PickupContainerItem = function() end }\n')


# Social's code columns: its CODES list's order (Social.lua: CODE_COL[c] = i - 1)
SOCIAL_COL = {c: i for i, c in enumerate(re.findall(r'"([a-z0-9]+)"', re.search(r'local CODES = [{](.*?)\n[}]', _SRC["social"].replace("\r", ""),
                                                                                 re.S).group(1)))}


def room_world():
    L, errs = boot_world(files=ROOM_FILES, pre=GAME_PRE + 'WowCompanion.partyOk = true\n' + BAG_PRE, login=ROOM_LOGIN)
    L.execute('LOADERRS = {}')
    for e in errs:
        L.eval('function(s) LOADERRS[#LOADERRS + 1] = s end')(e)
    L.execute('__IN.ACT.trust(); advance(1); __IN.N.setMode(true); advance(1)')
    # the Macros room's lazy row, made out of combat as the app's first open makes it (Book's padtab pick 39 then 34)
    L.execute('if WowCompanion.macroRoom then WowCompanion.macroRoom.ensure() end; advance(0.5)')
    return L


def slot(L, ns, key):
    return L.eval('WowCompanion.pad.slotFor')(ns, key)


def rtap(L, row, col):
    L.eval('hwTap')(row, col)
    L.execute('advance(0.4)')


def last(L, typ):
    d = docs(L, 0, typ)
    return d[-1] if d else {}


def bags_note(L):
    return (last(L, 'bagsctl').get('ctl') or {}).get('note')


# each room: how the app opens it (a tap, or the file's own touch entry), a sub-screen to step back from (None = none the stub can open),
# the press document its app view reads (None = the room's own Lua B chain: its top-level B asks fightHome) with the room's own
# "free because of B" cell the app taps at the top (None = none), and its capture id (HomeBack.roomsOf)
ROOMS = [
    ("BAGS", "bags", lambda L: rtap(L, "bmvq", 29),
     (lambda L: press(L, "PAD1"), lambda L: bags_note(L) == 'picked', lambda L: bags_note(L) == 'cancelled'), None),
    ("CHARACTER", "character", lambda L: (L.eval('WowCompanion.character.touchOpen')(), L.execute('advance(0.4)')), None, None),
    ("SPELLBOOK", "spells", lambda L: rtap(L, "spells", L.eval('WowCompanion.spellsWinCols.open')), None, None),
    ("TALENTS", "spells", lambda L: rtap(L, "spells", L.eval('WowCompanion.spellsWinCols.topen')), None, None),
    ("CALENDAR", "calendar", lambda L: rtap(L, "cal", slot(L, "cal", "h")), None, ('calpad', ("cal", "fb"))),
    # the Macros room (Spells s10): its own Lua B chain - B at the grid lets go with "B" (in a fight: Interact's fightHome)
    ("MACROS", "macros", lambda L: rtap(L, "mac", 0), None, None),
    ("GROUPFINDER", "groupfinder", lambda L: rtap(L, "gf", slot(L, "gf", "h")), None, ('gfpad', ("gf", "fb"))),
    ("SOCIAL", "social", lambda L: rtap(L, "sact", SOCIAL_COL["hold"]), None, ('socialpad', None)),
    ("PROFESSIONS", "professions", lambda L: rtap(L, "prof", slot(L, "profctl", "book")), None, ('profpad', None)),
    ("QUESTS", "pad:quests", lambda L: qtap(L, QACT["quests"]), None, ('guestpad', None)),
    ("MAP", "pad:map", lambda L: qtap(L, QACT["map"]), None, ('guestpad', None)),
    ("SETTINGS", "pad:settings", lambda L: qtap(L, 12), None, ('guestpad', None)),
    ("CHAT", "pad:chat", lambda L: qtap(L, 14), None, ('guestpad', None)),
]
for rname, who, opener, sub, appdoc in ROOMS:
    for how in ('before the pull', 'in a fight', 'no fight'):
        tag = 'B-HOME[%s, %s]' % (rname, how)
        W = room_world()
        if how == 'in a fight':
            pull(W)
        opener(W)
        if how == 'in a fight' and holder(W) != who:
            tap(W, idoc(W).get('enter'))                  # the room's take waits for the app's ENTER (PadOwnerSync: a room wants the pad)
        if how == 'before the pull':
            check(holder(W) == who, "%s the room holds the controller before the pull" % tag, holder(W))
            pull(W)
        check(holder(W) == who and (how == 'no fight' or modes(W) == ['room'] * 8),
              "%s %s" % (tag, "held through the fight in room mode" if how != 'no fight' else "held"), (holder(W), modes(W)[:2]))
        if sub:
            go, inside, back = sub
            go(W)
            check(inside(W), "%s a sub-screen opened" % tag, bags_note(W))
            r = press(W, "PAD2")
            check(back(W) and holder(W) == who and not idoc(W).get('fightHome'),
                  "%s B in the sub-screen steps back one level (the room still holds, no Home ask)" % tag, (bags_note(W), holder(W)))
        n0 = nsent(W)
        r = press(W, "PAD2")
        d = idoc(W)
        if how == 'no fight':
            # as before: the room lets go, its app chain has B, or B is the game window's own close through the binding (spec.back: the
            # window's OnHide then lets go - the stub's close button is a stand-in that closes nothing)
            bound = str(W.eval('BIND.PAD2') or '')
            gone = (holder(W) != who or bool(appdoc and [x for x in docs(W, n0, appdoc[0]) if x.get('k') == 'PAD2'])
                    or (bound.startswith('CLICK ') and 'WowCompanionInteract' not in bound))
            check(gone and not d.get('fightHome'), "%s B at the top level: the room's own route as before, no fightHome" % tag,
                  (holder(W), bound, d.get('fightHome')))
            check(clean(W) == {}, "%s no lockdown breach, no spied call, no handle / snippet error" % tag, clean(W))
            continue
        if appdoc:
            got = [x for x in docs(W, n0, appdoc[0]) if x.get('k') == 'PAD2']
            check(bool(got) and modes(W) == ['room'] * 8 and not stopattack(r),
                  "%s B reaches the room's app view (%s PAD2: its own B chain, Home at the top), the mode stays" % (tag, appdoc[0]),
                  (r['clicks'], modes(W)[:2]))
            if appdoc[1]:                                  # the app's top-level B also tells the room (its "free because of B" cell)
                rtap(W, appdoc[1][0], slot(W, *appdoc[1]))
                d = idoc(W)
                check((d.get('fightHome') or {}).get('who') == who and holder(W) == who and clean(W) == {},
                      "%s ... its free-by-B in the fight asks Home too (doc.fightHome), nothing written" % tag, (d.get('fightHome'), holder(W)))
        else:
            check((d.get('fightHome') or {}).get('who') == who and holder(W) == who and modes(W) == ['room'] * 8 and not stopattack(r),
                  "%s B at the top level asks the app to go Home (doc.fightHome who=%s), the room still holds, nothing written" % (tag, who),
                  (d.get('fightHome'), holder(W), r['clicks']))
        # the app is Home: nothing wants the pad, so PadOwnerSync taps the LEAVE once
        tap(W, d.get('fightLeave'))
        d = idoc(W)
        check(holder(W) is None and modes(W) == [None] * 8 and d.get('fightMode') == 'game',
              "%s Home's LEAVE gives the fight back (fightMode game)" % tag, (holder(W), d.get('fightMode')))
        r = press(W, "PAD2")
        check(stopattack(r), "%s then the next B on Home works: the fight's own stop attack" % tag, r['acts'])
        check(clean(W) == {}, "%s #VIOL == 0, no spied call, no handle / snippet error" % tag, clean(W))
        fight_end(W)
        check(not idoc(W).get('fightHome') and clean(W) == {}, "%s the fight's end: no fightHome left, still clean" % tag, idoc(W).get('fightHome'))


# ---------------------------------------------------------------- B-HOME, THE BAGS ROOM THROUGH THE GAME'S OWN BAG WINDOW (the review of 2026-11-12 on
# fix/b-home: the 1429 log's Bags was opened by the backpack's door, "quiet pad: bag0:39" - the game's ContainerFrameCombinedBags open, B
# PASSED to the game in the grid (Bags' syncPass), the fight's leave = a click of the window's own close button). The user, 2026-11-12: "ALL
# ROOMS SHOULD ALWAYS GO HOME WITH B EVEN IN COMBAT". Rows: opened by the door before the pull (the app's touchedOut on Home by either route:
# the close door bag0 38 or bmvq 30), opened by the game's own bag key in a fight, no fight
BAGWIN_PRE = r"""
mkwin("ContainerFrameCombinedBags", false)
local bp = CreateFrame("Button", "MainMenuBarBackpackButton", UIParent)
bp:RegisterForClicks("AnyDown", "AnyUp")
bp:SetScript("OnClick", function(_, _, down)        -- the game's ToggleBag(0), once per click
    if down == false then return end
    ACTIONS[#ACTIONS + 1] = { kind = "togglebag" }
    if ContainerFrameCombinedBags._shown then ContainerFrameCombinedBags:Hide() else ContainerFrameCombinedBags:Show() end
end)
"""


def bagwin_world():
    L, errs = boot_world(files=ROOM_FILES, pre=GAME_PRE + 'WowCompanion.partyOk = true\n' + BAG_PRE + BAGWIN_PRE, login=ROOM_LOGIN)
    L.execute('LOADERRS = {}')
    for e in errs:
        L.eval('function(s) LOADERRS[#LOADERRS + 1] = s end')(e)
    L.execute('__IN.ACT.trust(); advance(1); __IN.N.setMode(true); advance(1)')
    # the Macros room's lazy row, made out of combat as the app's first open makes it (Book's padtab pick 39 then 34)
    L.execute('if WowCompanion.macroRoom then WowCompanion.macroRoom.ensure() end; advance(0.5)')
    return L


def bagwin_shown(L):
    return L.eval('ContainerFrameCombinedBags._shown') is True


def bagwin_alpha(L):
    return L.eval('ContainerFrameCombinedBags:GetAlpha()')


for how, out in (('door, before the pull', ('bag0', 38)), ('door, before the pull', ('bmvq', 30)), ('bag key, in a fight', None),
                 ('door, no fight', None)):
    tag = 'B-HOME[BAGS window, %s%s]' % (how, (', Home taps %s %d' % out) if out else '')
    W = bagwin_world()
    if how.endswith('in a fight'):
        pull(W)
        W.execute('ContainerFrameCombinedBags:Show(); advance(0.4)')      # the game's own bag key, in the fight: the window's take
        if holder(W) != 'bags':
            tap(W, idoc(W).get('enter'))
    else:
        ok, log = tap(W, {'row': 'bag0', 'col': 39})                       # the backpack's open door (the 1429 log's bag0:39)
        check(ok and 'MainMenuBarBackpackButton+' in log, "%s the open door is a secure click of the game's backpack button" % tag, log)
    c = last(W, 'bagsctl').get('ctl') or {}
    spec_leave = W.eval('__IN.G.spec and __IN.G.spec.leave')
    check(holder(W) == 'bags' and c.get('open') is True and bagwin_shown(W) and spec_leave == 'ContainerFrameCombinedBagsCloseButton',
          "%s Bags holds with the game's bag window open (its leave = the window's own close button)" % tag,
          (holder(W), c.get('open'), bagwin_shown(W), spec_leave))
    if not how.endswith('in a fight'):
        check(W.eval('__IN.G.spec.pass and __IN.G.spec.pass.PAD2') is True and not W.eval('BIND.PAD2'),
              "%s out of combat, B in the grid is PASSED to the game (Bags' syncPass)" % tag, W.eval('BIND.PAD2'))
    if how.endswith('no fight'):
        press(W, "PAD2")
        W.execute('ContainerFrameCombinedBags:Hide(); advance(0.4)')        # the game's own B closed its bags (the stub's game has no B)
        check(holder(W) is None and bags_note(W) == 'left-B' and not idoc(W).get('fightHome') and clean(W) == {},
              "%s B is the game's: its bags close, Bags lets go with B (Home), no fightHome, clean" % tag, (holder(W), bags_note(W)))
        continue
    if how.endswith('before the pull'):
        pull(W)
    check(holder(W) == 'bags' and modes(W) == ['room'] * 8, "%s held through the fight in room mode" % tag, (holder(W), modes(W)[:2]))
    r = press(W, "PAD2")
    d = idoc(W)
    check((d.get('fightHome') or {}).get('who') == 'bags' and holder(W) == 'bags' and not stopattack(r) and clean(W) == {},
          "%s B in the grid reaches Bags in the fight (no pass in the lockdown) and asks Home (doc.fightHome who=bags), nothing written" % tag,
          (d.get('fightHome'), holder(W), r['clicks']))
    if out:                                                              # show(HOME): BagsView.touchedOut, by the door or bmvq 30
        ok, log = tap(W, {'row': out[0], 'col': out[1]})
        check(holder(W) == 'bags' and clean(W) == {}, "%s Home's touchedOut in the fight: still held till the LEAVE, clean" % tag,
              (log, holder(W), clean(W)))
    ok, log = tap(W, idoc(W).get('fightLeave'))
    W.execute('advance(0.5)')
    d = idoc(W)
    check(holder(W) is None and modes(W) == [None] * 8 and d.get('fightMode') == 'game',
          "%s Home's LEAVE gives the fight back (fightMode game)" % tag, (log, holder(W), d.get('fightMode')))
    if how.endswith('before the pull'):
        check(not bagwin_shown(W), "%s ... and the game's bag window is closed (its own close button, aimed before the pull)" % tag, log)
    else:
        # a bag window opened IN the fight has no pre-aimed close (combat_nav 5c aims seven windows' leaves; attributes cannot change in the
        # lockdown): it is hidden by alpha off the top screen for the rest of the fight (5a), never closed from our code
        check(bagwin_shown(W) and bagwin_alpha(W) == 0, "%s ... the bag window opened in the fight is hidden by alpha (5a), not closed" % tag,
              (bagwin_shown(W), bagwin_alpha(W)))
    r = press(W, "PAD2")
    check(stopattack(r), "%s then the next B on Home works: the fight's own stop attack" % tag, r['acts'])
    check(clean(W) == {}, "%s #VIOL == 0, no spied call, no handle / snippet error" % tag, clean(W))
    fight_end(W)
    W.execute('advance(2)')
    check(holder(W) is None and bags_note(W) == 'left-B' and not idoc(W).get('fightHome') and clean(W) == {},
          "%s the fight's end: Bags does not take the pad back (left with B), no fightHome left, clean" % tag, (holder(W), bags_note(W)))

# ---------------------------------------------------------------- B-HOME, THE BAGS ROOM WITH THE GAME'S BIND QUESTION UP AT THE PULL (the review of
# 2026-11-12: "B while the game's bind question is up is dropped" in a fight). A bag that binds when worn, equipped before the pull: Bags is
# in "bindask" (A / B passed to the game's own question). In the fight the question is the app's box (Dialogs mirrors it) and A / B are the
# DialogView's (Interact's sink lets them by, WC.dialogs.mirrored; DialogView.fightPress taps the button's own cell): B = Cancel, the
# question goes, Bags steps back to its grid (a sub-screen step, the "B in sub-screens" ruling); the next B goes Home
BINDASK_PRE = r"""
BAGID = 4496
C_Container.GetContainerItemID = function(b, s) if b == 0 and s == 1 then return BAGID end end
C_Container.GetContainerItemInfo = function(b, s) if b == 0 and s == 1 then return { itemID = BAGID, stackCount = 1, quality = 1, iconFileID = 1 } end end
C_Container.ContainerIDToInventoryID = function(b) if b >= 1 and b <= 4 then return 30 + b end end
C_Container.GetContainerNumFreeSlots = function() return 0 end
C_Item = {}
C_Item.GetItemInfoInstant = function(id) if id == BAGID then return id, "Bag", "Container", "INVTYPE_BAG", 1, 1, 0 end end
function GetInventoryItemID() return nil end
function CursorHasItem() return false end
function PutItemInBag(inv)
    ACTIONS[#ACTIONS + 1] = { kind = "putbag", inv = inv }
    StaticPopup1.which = "EQUIP_BIND"; StaticPopup1:Show(); WowCompanion.dialogs.look()
end
POPB = {}
for k = 1, 2 do
    local b = CreateFrame("Button", "StaticPopup1Button" .. k, StaticPopup1)
    b._text = (k == 1) and "Okay" or "Cancel"
    b.GetText = function(self) return self._text end
    b.IsEnabled = function() return true end
    b:RegisterForClicks("AnyDown", "AnyUp")
    b:SetScript("OnClick", function() ACTIONS[#ACTIONS + 1] = { kind = "popup", k = k }; StaticPopup1:Hide() end)
    POPB[k] = b
end
StaticPopup1.GetButton = function(self, k) return POPB[k] end
"""
tag = 'B-HOME[BAGS bind question, before the pull]'
W = room_world()
W.execute(BINDASK_PRE)
rtap(W, "bmvq", 29)
press(W, "PAD3")                                   # X on the bag: into the hand, the bag bar
press(W, "PAD1")                                   # A: into bag slot 1 - the game asks "bind it to you?"
W.execute('advance(0.5)')
check((last(W, 'bagsctl').get('ctl') or {}).get('mode') == 'bindask' and holder(W) == 'bags',
      "%s the game's bind question is up, Bags in bindask, held" % tag, last(W, 'bagsctl').get('ctl'))
pull(W)
W.execute('WowCompanion.dialogs.look(); advance(0.5)')
dl = (docs(W, 0, 'dialogs')[-1].get('list') or [{}])[0]
check(W.eval('WowCompanion.dialogs.mirrored()') == 1 and dl.get('which') == 'EQUIP_BIND' and 'b2' in (dl.get('cells') or {}),
      "%s in the fight the question is the app's box (mirrored, its Cancel has a cell)" % tag, dl)
r = press(W, "PAD2")
check((last(W, 'bagsctl').get('ctl') or {}).get('mode') == 'bindask' and not idoc(W).get('fightHome') and holder(W) == 'bags',
      "%s B is the box's (Interact's sink lets it by): nothing of Bags' acts on it" % tag, (r['clicks'], idoc(W).get('fightHome')))
ok, log = tap(W, {'row': 'idlg', 'col': dl['cells']['b2']})          # DialogView.fightPress: B = button 2's cell
W.execute('advance(1.5)')
check(ok and 'StaticPopup1Button2+' in log and not W.eval('StaticPopup1._shown'),
      "%s ... the box's B = a secure click of the game's own Cancel: the question goes" % tag, log)
c = last(W, 'bagsctl').get('ctl') or {}
check(c.get('mode') == 'grid' and c.get('note') == 'equip-cancelled' and holder(W) == 'bags' and not idoc(W).get('fightHome'),
      "%s ... Bags steps back to its grid (one level), still holds, no Home ask" % tag, (c.get('mode'), c.get('note'), holder(W)))
r = press(W, "PAD2")
d = idoc(W)
check((d.get('fightHome') or {}).get('who') == 'bags' and holder(W) == 'bags' and not stopattack(r),
      "%s the next B at the grid's top level asks Home (doc.fightHome who=bags)" % tag, (d.get('fightHome'), holder(W)))
tap(W, d.get('fightLeave'))
r = press(W, "PAD2")
check(holder(W) is None and idoc(W).get('fightMode') == 'game' and stopattack(r) and clean(W) == {},
      "%s Home's LEAVE gives the fight back; the next B is the stop attack; #VIOL == 0" % tag, (holder(W), clean(W)))

# ================================================================ ROUTER HEAL (Interact i152; the user, 2026-11-12: "I died and then my jump
# input just stopped working", "its not just a, inputs work like I can see the button pushing but its not actually doing anything in game").
# The log: the player died flagged in combat, the app's ENTER for the DEATH pop-up landed just AFTER PLAYER_REGEN_ENABLED, its FLIP set
# wc-mode "room" on all 8 router buttons and nothing out of a fight ever cleared it: every routed press went to our empty sink.
FACE8 = 'BIND.PAD1 = "CLICK WowCompanionCombatFace8:LeftButton"'      # A routed to the router out of combat (PadMap's routerBind)


def heal_state(L):
    d = idoc(L)
    return {"modes": modes(L), "holder": holder(L), "routerMode": d.get('routerMode'), "modeFix": d.get('modeFix'),
            "routerLeave": d.get('routerLeave'), "fightMode": d.get('fightMode')}


def force_room(L):
    for n in ROUTER.values():
        L.execute('rawget(_G, "%s"):SetAttribute("wc-mode", "room")' % n)


# L1: the bug's own order - the pull (nothing held: the ENTER cell published), death ends the fight (REGEN_ENABLED first), THEN the ENTER
W = world()
pull(W)
d = idoc(W)
enter = d.get('fightEnter')
check(enter is not None and d.get('fightMode') == 'game', "L1 the pull: fightMode \"game\", the ENTER cell published", (d.get('fightMode'), enter))
W.execute('UNITS.player.alive = false; fire("PLAYER_DEAD"); COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(0.05)')
ok, log = tap(W, enter)
s = heal_state(W)
check(ok and 'WowCompanionRoomEnter+' in log and s['modes'] == [None] * 8 and s['holder'] is None,
      "L1 the late ENTER (after PLAYER_REGEN_ENABLED): wc-mode nil on all 8 router buttons, no holder", (log, s))
check(s['modeFix'] == 1 and s['routerMode'] is None and s['fightMode'] is None and idoc(W).get('dead') is True,
      "L1 ... healed once (doc.modeFix 1), doc.routerMode nil, no fightMode, doc.dead", s)
W.execute(FACE8)
r = press(W, "PAD1")
check(r['clicks'][:1] == ['WowCompanionCombatFace8+'] and not any(c.startswith('WowCompanionInteractPad') for c in r['clicks'])
      and W.eval('WowCompanionCombatFace8.a["wc-at"]') != 'WowCompanionInteractPad',
      "L1 ... A reaches the Forever bar's CLICK chain (the router's own button), never our sink", (r['clicks'], W.eval('WowCompanionCombatFace8.a["wc-at"]')))
check(clean(W) == {}, "L1 no lockdown breach (VIOL), no spied call, no handle / snippet error (SNIPERR)", clean(W))

# L2: the pull's pre-lockdown window (PLAYER_REGEN_DISABLED fired, no lockdown yet) - a room holding keeps its room mode; neither a
# stray ENTER there, a document nor the watchdog clears it; the lockdown then finds the fight as before
W = world()
qtap(W, QACT["map"])
check(holder(W) == 'pad:map', "L2 a room holds out of combat", holder(W))
W.execute('fire("PLAYER_REGEN_DISABLED")')
check(modes(W) == ['room'] * 8 and W.eval('__IN.N.fight') == 'router', "L2 the pull, before the lockdown: room mode, the fight hold on",
      (modes(W), W.eval('__IN.N.fight')))
ra = W.eval('__IN.N.ra')
tap(W, {'row': ra.erow, 'col': ra.ecol})
W.execute('tick(1); WowCompanion.interactPoke(); advance(0.05)')
check(modes(W) == ['room'] * 8 and holder(W) == 'pad:map' and W.eval('__IN.N.modeFixN') == 0,
      "L2 ... a stray ENTER, a document and the watchdog there keep it (no heal: N.fight)", (modes(W), holder(W), W.eval('__IN.N.modeFixN')))
W.execute('COMBAT = true; advance(0.5); WowCompanion.interactPoke(); advance(0.05)')
d = idoc(W)
check(d.get('fightMode') == 'room' and holder(W) == 'pad:map', "L2 ... the lockdown: fightMode \"room\", the room still holds", (d.get('fightMode'), holder(W)))
r = press(W, "PADDUP")
check(pad_keys(r) == ['PADDUP'], "L2 ... and the D-pad reaches it", r['pads'])
fight_end(W)
check(modes(W) == [None] * 8 and clean(W) == {}, "L2 the fight's end: wc-mode nil; no VIOL / SNIPERR", (modes(W), clean(W)))

# L3: a forced out-of-fight room mode is healed by each path: the 1 s watchdog, a document, PLAYER_ALIVE, PLAYER_UNGHOST, PLAYER_DEAD,
# PLAYER_ENTERING_WORLD - once each (modeFix 1), routerMode nil
for how in ('watchdog', 'doc', 'PLAYER_ALIVE', 'PLAYER_UNGHOST', 'PLAYER_DEAD', 'PLAYER_ENTERING_WORLD'):
    W = world()
    W.execute('advance(1)')
    force_room(W)
    check(modes(W) == ['room'] * 8, "L3[%s] forced: room mode out of a fight, nobody holding" % how, modes(W))
    if how == 'watchdog':
        W.execute('tick(1)')
    elif how == 'doc':
        W.execute('WowCompanion.interactPoke()')
    else:
        W.execute('fire("%s")' % how)
    W.execute('advance(0.3)')
    s = heal_state(W)
    check(s['modes'] == [None] * 8 and s['modeFix'] == 1 and s['routerMode'] is None and s['holder'] is None,
          "L3[%s] healed: wc-mode nil on all 8, doc.modeFix 1, doc.routerMode nil" % how, s)
    W.execute(FACE8)
    r = press(W, "PAD1")
    check(not any(c.startswith('WowCompanionInteractPad') for c in r['clicks']) and clean(W) == {},
          "L3[%s] ... A never reaches our sink; no VIOL / SNIPERR" % how, (r['clicks'], clean(W)))

# L4: the document reports the router's mode: "room" in the pre-lockdown window (N.fight: no leave for the app), and out of a fight with
# nobody holding (read before the heal) the generic leave's cell, whose tap flips the mode back and sends a document
W = world()
qtap(W, QACT["map"])
W.execute('fire("PLAYER_REGEN_DISABLED"); WowCompanion.interactPoke(); advance(0.05)')
d = idoc(W)
check(d.get('routerMode') == 'room' and d.get('routerLeave') is None and d.get('fightMode') is None,
      "L4 the pre-lockdown window: doc.routerMode \"room\", no routerLeave (the fight's own), no fightMode yet", (d.get('routerMode'), d.get('routerLeave')))
W.execute('COMBAT = true; advance(0.5)')
fight_end(W)
W = world()
W.execute('advance(1)')
force_room(W)
W.execute('RD = {}; __IN.N.routerDoc(RD)')
rl = W.eval('RD.routerLeave')
check(W.eval('RD.routerMode') == 'room' and rl is not None and rl.row == 'idlg' and W.eval('__IN.F.leave.a["type"]') is None,
      "L4 out of a fight, nobody holding: routerMode \"room\" and routerLeave (the generic leave, aimed at nothing)",
      (W.eval('RD.routerMode'), rl and (rl.row, rl.col)))
ok, log = tap(W, {'row': rl.row, 'col': rl.col})
check(ok and modes(W) == [None] * 8 and W.eval('__IN.N.lastRouterRoom') is False and holder(W) is None and not W.eval('#ACTIONS > 0 and ACTIONS[#ACTIONS].kind == "close"'),
      "L4 ... the app's LEAVE tap there: wc-mode nil on all 8, a document asked for, nothing closed", (log, modes(W)))
check(clean(W) == {}, "L4 no VIOL / SNIPERR", clean(W))

# L5: the existing in-fight stray ENTER - its "app" holder still goes at PLAYER_REGEN_ENABLED, the router back to nil (no heal needed)
W = world()
pull(W)
tap(W, idoc(W).get('fightEnter'))
check(holder(W) == 'app' and modes(W) == ['room'] * 8, "L5 a stray ENTER in the fight: the \"app\" holder, room mode", (holder(W), modes(W)))
fight_end(W)
s = heal_state(W)
check(s['holder'] is None and s['modes'] == [None] * 8 and s['routerMode'] is None and s['modeFix'] == 0,
      "L5 ... PLAYER_REGEN_ENABLED releases it and clears the mode itself (doc.modeFix 0)", s)
check(clean(W) == {}, "L5 no VIOL / SNIPERR", clean(W))

# L6 (review): out of a fight the document offers the app NO routerLeave while anything holds (an "npc" hold has no G.holder) or waits
# to hold (N.pend) - a stale LEAVE crossing into the next pull would drop that fight's hold
for what, setup in (('holding', '__IN.N.holding = true'), ('pend', '__IN.N.pend = { id = "npc", n = 0 }')):
    W = world()
    W.execute('advance(1)')
    force_room(W)
    W.execute(setup)
    W.execute('RD = {}; __IN.N.routerDoc(RD)')
    check(W.eval('RD.routerMode') == 'room' and W.eval('RD.routerLeave') is None,
          "L6[%s] room mode out of a fight but something holds / waits: routerMode \"room\", no routerLeave" % what,
          (W.eval('RD.routerMode'), W.eval('RD.routerLeave')))
    check(clean(W) == {}, "L6[%s] no VIOL / SNIPERR" % what, clean(W))

print('%s (%d pass, %d xfail, %d fail, %d xpass)' % ('ALL PASS' if fails == 0 and xpasses == 0 else 'FAILED', passes, xfails, fails, xpasses))
sys.exit(1 if (fails or xpasses) else 0)
