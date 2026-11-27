"""THE SOCIAL ROOM'S LEAVE GROUP (Social so15), off-device: the REAL Safety, TapPad and Social files under tools/wowstub.py's game with
STRICT laid over it (the lockdown, the press model).

    python -B tools/test_social_leave.py

A player's report on 1.0.39: "I cannot leave the party through the Social menu". The room had no Leave group action: the player's own
row had no actions and the room's own actions were Convert to Raid / Party and All Assist only. Fixed with a room code "leave" APPENDED
to the `sact` row (CODES is append only), backed by the game's own leave-party call (C_PartyInfo.LeaveParty, else LeaveParty: not
protected; Quests' player menu already calls it), allowed only while in a group. The app asks first ("Leave the group?").

Proves: (1) "leave" is the LAST code (every older column unchanged: column 39 on a 40-column row) and the `me` document names it; (2) in a
party, one tap at that column calls the game's leave once and answers ok; (3) not in a group: refused ("not in a group"), no call;
(4) in a fight: the same (the call is not protected: no lockdown breach); (5) no spied call (no binding, no macro, no CVar), no load or
snippet error. Needs lupa. Exit code 1 on any failure."""
import json
import os
import re
import sys

import wowstub
from wowstub import ADDONS, ADDON_OF, F, SRC, boot_world

fails = passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' and not ok else ''))
    if ok:
        passes += 1
    else:
        fails += 1


F["social"] = os.path.join(ADDONS, "WowCompanionSocial", "Social.lua")
SRC["social"] = open(F["social"], encoding="utf-8").read()
ADDON_OF["social"] = "WowCompanionSocial"
CODES = re.findall(r'"([a-z0-9]+)"', re.search(r'local CODES = [{](.*?)\n[}]', SRC["social"].replace("\r", ""), re.S).group(1))
# the columns 1.0.42's add-on set 434 shipped (append only: none may move)
SHIPPED = ["invite", "uninvite", "leader", "assist", "demote", "addfriend", "unfriend", "ignore", "unignore", "ginvite", "gremove",
           "gpromote", "gdemote", "group1", "group2", "group3", "group4", "group5", "group6", "group7", "group8", "toraid", "toparty",
           "allassist", "whozone", "wholevel", "whoboth", "vgroup", "vfriends", "vignore", "vguild", "vwho", "hold", "free", "vwhof",
           "whosearch", "wopen", "bh", "bb"]

PRE = r'''
LEFT = {}
C_PartyInfo = C_PartyInfo or {}
C_PartyInfo.LeaveParty = function(...) LEFT[#LEFT + 1] = "C_PartyInfo.LeaveParty" end
function LeaveParty() LEFT[#LEFT + 1] = "LeaveParty" end
function IsInRaid() return false end
'''
GAME_LEAVE = ("C_PartyInfo.LeaveParty", "LeaveParty")      # the game's own call (the newer one where the client has it)
LOGIN = ('for _, a in ipairs({ "WowCompanion", "WowCompanionSocial" }) do fire("ADDON_LOADED", a) end; fire("PLAYER_LOGIN"); '
         'fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')


def world(grouped=True):
    L, errs = boot_world(files=("safety", "tappad", "social"), pre=PRE + ('UNITS.party1 = { name = "Brannoc", alive = true }\n' if grouped else ''),
                         login=LOGIN, expose=False)
    L.execute('LOADERRS = {}')
    for e in errs:
        L.eval('function(s) LOADERRS[#LOADERRS + 1] = s end')(e)
    L.execute('advance(1)')
    return L


def lst(L, expr):
    t = L.eval(expr)
    return [] if t is None else list(t.values())


def clean(L):
    return {k: v for k, v in (("VIOL", lst(L, 'VIOL')), ("SPY", lst(L, 'SPY')), ("SNIPERR", lst(L, 'SNIPERR')),
                              ("ERRS", lst(L, 'ERRS')), ("LOADERRS", lst(L, 'LOADERRS'))) if v}


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


def tap(L, col):
    n0 = len(sent(L, 'socialack'))
    ok = L.eval('hwTap')("sact", col)
    ok = ok[0] if isinstance(ok, tuple) else ok
    L.execute('advance(0.3)')
    return ok, sent(L, 'socialack')[n0:]


# ---------------------------------------------------------------- (1) the column
check(CODES[:len(SHIPPED)] == SHIPPED, "(1) every shipped code keeps its column (append only)", CODES[:len(SHIPPED)])
check("leave" in CODES and CODES.index("leave") == 39 and len(CODES) <= 40, "(1) \"leave\" is the new last code, column 39 (the row's 40th)",
      (CODES.index("leave") if "leave" in CODES else None, len(CODES)))
W = world()
me = [d for d in sent(W, 'social') if d.get('k') == 'me']
codes = (me[-1].get('s') or {}).get('codes') if me else None
check(isinstance(codes, dict) and codes.get('leave') == 39, "(1) the me document names it (codes.leave = 39)", codes and codes.get('leave'))
LEAVE = CODES.index("leave") if "leave" in CODES else 39

# ---------------------------------------------------------------- (2) in a party: the game's own leave, once
ok, acks = tap(W, LEAVE)
check(ok and len(lst(W, 'LEFT')) == 1 and lst(W, 'LEFT')[0] in GAME_LEAVE, "(2) in a party: one tap = the game's leave-party call once", (ok, lst(W, 'LEFT')))
check(any(a.get('op') == 'leave' and a.get('ok') is True for a in acks), "(2) answered ok (op leave)", acks)
check(clean(W) == {}, "(5) in a party: clean", clean(W))

# ---------------------------------------------------------------- (3) not in a group: refused, no call
S = world(grouped=False)
ok, acks = tap(S, LEAVE)
check(lst(S, 'LEFT') == [] and any(a.get('op') == 'leave' and a.get('ok') is False and a.get('why') == 'not in a group' for a in acks),
      "(3) not in a group: refused (not in a group), no call", (lst(S, 'LEFT'), acks))
check(clean(S) == {}, "(5) not in a group: clean", clean(S))

# ---------------------------------------------------------------- (4) in a fight: not protected, works the same
C = world()
C.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.5)')
ok, acks = tap(C, LEAVE)
check(len(lst(C, 'LEFT')) == 1 and lst(C, 'LEFT')[0] in GAME_LEAVE and any(a.get('op') == 'leave' and a.get('ok') is True for a in acks),
      "(4) in a fight: the same leave (not a protected call)", (lst(C, 'LEFT'), acks))
check(clean(C) == {}, "(4) in a fight: no lockdown breach, no spied call", clean(C))

print("\n%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
