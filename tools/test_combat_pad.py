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
describe the behaviour the design and the user's decisions ask for. Where TODAY's code (97036b2b) does not do it yet the check is an XFAIL with
the reason; the build steps flip them. An XPASS (a marked check that passes) is a FAILURE: flip its marker in the same commit.
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
    xcheck(modes(W) == ['room'] * 8 and holder(W) == 'map' and any(d.get('k') in ('PAD2', 'BACK') for d in r['pads']),
           "S1[%s] B in the fight goes to the Map room's own B chain (one step back, then Home), the room keeps holding" % order,
           "B in room mode IS the secure leave (wc-room-dest PAD2 = WowCompanionRoomLeave): one B drops the room and closes the map, "
           "whatever level of the map's chain was open", (modes(W)[:2], holder(W), r['clicks']))
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
xcheck(any('WowCompanionRoomLeave' in x for x in r['clicks']) and 'wc-esc' in str(dict(W.eval('WowCompanionFaceB.a'))),
       "S1 LT + RT + B in room mode is the secure escape (wc-esc = WowCompanionRoomLeave)",
       "no escape chord: any B is the leave today (so it gets out, but not through wc-esc)", r['clicks'])

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
    check(holder(W) is None and modes(W) == [None] * 8, "S2[%s] B leaves the loot: the router back to the fight" % order, (holder(W), modes(W)[:2]))
    xcheck(W.eval('LootFrame._shown') is False, "S2[%s] ... and the game's loot window is CLOSED by that leave (design phase 2: a pre-aimed leave per window)" % order,
           "the leave was aimed at the pull (no window then): it closes nothing; the loot window stays open, hidden, driven by nobody",
           (W.eval('LootFrame._shown'), W.eval('LootFrame:GetAlpha()')))
    check(W.eval('LootFrame:GetAlpha()') == 0 or W.eval('LootFrame._shown') is False,
          "S2[%s] ... a window the leave could not close stays hidden by alpha in the fight (never unhidden mid-fight)" % order, W.eval('LootFrame:GetAlpha()'))
    fight_end(W)
    xcheck(W.eval('LootFrame._shown') is False, "S2[%s] the fight's end closes the loot window left behind (design 5a)" % order,
           "nothing closes it after the fight", W.eval('LootFrame._shown'))
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
    xcheck(W.eval('LootFrame._shown') is False, "S2[%s] ... and closes the loot window" % tag,
           "the pull aims the leave from G.spec (a room's hide / back / leave) only: the npc holder has no spec, so the leave clicks nothing",
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
        xcheck(holder(W) == 'pad:quests' and lost and lost[-1]['lost'] == 'taken' and clean(W) == {},
               "S3[%s] room to room is an in-place swap: the new room holds at once, the old one lost(taken), no secure write" % tag,
               "the take is QUEUED behind the holder (doc.wait) until a LEAVE + a new ENTER: two taps, presses dropped in between",
               (holder(W), idoc(W).get('wait'), lost))
    else:
        check(holder(W) in ('pad:quests', 'pad:map'), "S3[%s] room to room out of a fight: the capture's queue / pre-emption as before" % tag, holder(W))
    # B: the app's own B chain decides (one step back or Home); at Home the reconciler taps LEAVE
    W2 = W
    if combat:
        r = press(W2, "PAD2")
        xcheck(modes(W2) == ['room'] * 8 and pad_keys(r) == ['PAD2'], "S3[%s] B in room mode goes to the app as PAD2 (its B chain), the mode stays" % tag,
               "B in room mode is the secure leave: the room is dropped at once, the app's chain never sees the B", (modes(W2)[:2], r['clicks']))
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

print('%s (%d pass, %d xfail, %d fail, %d xpass)' % ('ALL PASS' if fails == 0 and xpasses == 0 else 'FAILED', passes, xfails, fails, xpasses))
sys.exit(1 if (fails or xpasses) else 0)
