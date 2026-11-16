"""THE MAIL ENVELOPE (Calendar c15), off-device: the REAL add-on files under tools/wowstub.py's stubbed game (its strict world).

    python tools/test_mail.py

The user, 2026-11-16: "we need to add an icon somewhere to the home menu for when the player has mail". The picks: a small envelope BY
the Calendar button at the end of Home's map-name band, shown only while the player has mail; a touch shows who it is from, as the
game's own minimap mail tooltip does (HasNewMail + GetLatestThreeSenders; events UPDATE_PENDING_MAIL / MAIL_INBOX_UPDATE /
PLAYER_ENTERING_WORLD; plain values; event-driven, no polling). The add-on side folds into Calendar.lua's existing `calendar` document.
Proven here:
  a the version (c15) and its header note quoting the user; the file loads clean;
  b the block reads ONLY HasNewMail and GetLatestThreeSenders (no inbox call, nothing opened / taken / deleted, no mail window), sets no
    timer and no ticker, prints nothing;
  c login with no mail: the document says {on = false}; new mail with one sender, then three (a fourth never sent): {on, from};
    mail the game names nobody for: {on} with no `from`; all read: {on = false};
  d event-driven: the reading happens on UPDATE_PENDING_MAIL / MAIL_INBOX_UPDATE / PLAYER_ENTERING_WORLD only - minutes of quiet and the
    heartbeat's forced document read nothing (the heartbeat carries the last reading); a MAIL_INBOX_UPDATE that changes nothing sends
    no document; the reading taken at load rides a /reload's login document (sent before the world entry: no blink);
  e plain values only: a secret HasNewMail leaves the last reading as it was; a secret or empty sender is dropped; no HasNewMail on the
    client = no `mail` at all;
  f the game rule IngameMailNotificationDisabled = {off = true}; the calendar switched off still carries `mail`;
  g in a fight: the new mail's document goes, no lockdown breach, no spied call; no Lua error anywhere;
  h privacy: the names appear only in the calendar document's `mail.from`, never in any other line the add-ons write.
With MAIL_DUMP=<dir> the calendar documents for the renders are written there (none / one / three / long / nobody .json).
Needs lupa. Exit code 1 on any failure."""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import ADDONS, ADDON_OF, F, SRC, WORLD_FILES, boot_world, docs, nsent  # noqa: E402

F["calendar"] = os.path.join(ADDONS, "WowCompanionCalendar", "Calendar.lua")
SRC["calendar"] = open(F["calendar"], encoding="utf-8").read()
ADDON_OF["calendar"] = "WowCompanionCalendar"
FILES = tuple(list(WORLD_FILES) + ["calendar"])
LOGIN = ('for _, a in ipairs({ "WowCompanion", "WowCompanionQuests", "WowCompanionHub", "WowCompanionInteract", "WowCompanionSpells", '
         '"WowCompanionCalendar" }) do fire("ADDON_LOADED", a) end; fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); '
         'runTimers(NOW + 10)')
# the game's mail: HAS = HasNewMail()'s answer, SENDERS = GetLatestThreeSenders()'s values; READS counts the reads of each
MAIL_PRE = r'''
HAS, SENDERS, READS = false, {}, { has = 0, senders = 0 }
NO_MAIL_API = false
function HasNewMail() READS.has = READS.has + 1; return HAS end
function GetLatestThreeSenders() READS.senders = READS.senders + 1; return unpack(SENDERS) end
RULES = {}
Enum = setmetatable({ GameRule = { IngameCalendarDisabled = 76, IngameMailNotificationDisabled = 77 } }, { __index = function() return STUB end })
C_GameRules = { IsGameRuleActive = function(r) return RULES[r] == true end }
-- a secret STRING as the client has them (the stub's SECRETV is a table): the client's own test says so
SECRET_STR = { ["Hidden Sender"] = true }
do local was = issecretvalue; function issecretvalue(v) return was(v) or (type(v) == "string" and SECRET_STR[v] == true) end end
-- the mail calls the block must never make (each a spy: nothing opened, taken, deleted, marked)
for _, n in ipairs({ "CheckInbox", "GetInboxHeaderInfo", "GetInboxNumItems", "GetInboxText", "TakeInboxItem", "TakeInboxMoney",
                     "AutoLootMailItem", "DeleteInboxItem", "ReturnInboxItem", "InboxItemCanDelete", "SendMail", "CloseMail",
                     "GetInboxItem", "GetInboxItemLink" }) do
    _G[n] = function(...) SPY[#SPY + 1] = n end
end
'''
fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


def world(pre=''):
    L, errs = boot_world(files=FILES, pre=MAIL_PRE + 'WowCompanion.partyOk = true\n' + pre, strict=True, login=LOGIN, expose=False)
    return L, errs


def cal_docs(L, start=0):
    return docs(L, start, typ='calendar')


def last_mail(L):
    d = cal_docs(L)
    return (d[-1].get('c') or {}).get('mail', 'NONE') if d else 'NO DOC'


def senders(L, *names):
    L.execute('SENDERS = {}')
    add = L.eval('function(s) SENDERS[#SENDERS + 1] = s end')
    for n in names:
        if n == 'SECRET':
            L.execute('SENDERS[#SENDERS + 1] = SECRETV')
        else:
            add(n)


def mail_event(L, ev='UPDATE_PENDING_MAIL'):
    n = nsent(L)
    L.execute('fire("%s"); advance(1)' % ev)
    return cal_docs(L, n)


def clean(L, errs):
    out = {}
    if errs:
        out['load'] = errs
    e = [L.eval('ERRS[%d]' % i) for i in range(1, int(L.eval('#ERRS')) + 1)]
    if e:
        out['errs'] = e
    v = [L.eval('VIOL[%d]' % i) for i in range(1, int(L.eval('#VIOL')) + 1)]
    if v:
        out['viol'] = v
    s = [L.eval('SPY[%d]' % i) for i in range(1, int(L.eval('#SPY')) + 1)]
    if s:
        out['spy'] = s
    return out


# ================================================================ a the version, the notes, a clean load
src = SRC["calendar"]
m = re.search(r'local CV = "([a-z0-9]+)"(.*)', src)
check(m is not None and m.group(1) == "c15", "a Calendar's tag is c15", m and m.group(1))
QUOTE = '"we need to add an icon somewhere to the home menu for when the player has mail"'
check(m is not None and QUOTE in m.group(2), "a the tag's note quotes the user")
check(src.count(QUOTE) >= 2, "a the block's own header quotes the user too")
L, errs = world()
check(clean(L, errs) == {}, "a the world loads clean (every file, the calendar's included)", clean(L, errs))

# ================================================================ b what the block calls
blk = src[src.index('(c15) THE MAIL ENVELOPE\n'):src.index('function WC.collectCalendar()')]
calls = set(re.findall(r'\b([A-Z][A-Za-z_]+)\s*\(', blk)) - {"CALL", "S"}
check(calls <= {"HasNewMail", "GetLatestThreeSenders", "IsGameRuleActive"},
      "b the block's game calls: HasNewMail, GetLatestThreeSenders (and the game rule's read) only", sorted(calls))
check("C_Timer" not in blk and "NewTicker" not in blk and "OnUpdate" not in blk, "b the block sets no timer, ticker or OnUpdate")
check(not re.search(r'\bprint\s*\(|AddMessage|DEFAULT_CHAT_FRAME|C_Log', blk), "b the block prints nothing and logs nothing")
check("UPDATE_PENDING_MAIL" in src and "MAIL_INBOX_UPDATE" in src, "b the game icon's events are registered")
check(L.eval('EVT["UPDATE_PENDING_MAIL"] ~= nil and #EVT["UPDATE_PENDING_MAIL"] > 0') and
      L.eval('EVT["MAIL_INBOX_UPDATE"] ~= nil and #EVT["MAIL_INBOX_UPDATE"] > 0'), "b ... and the frame really listens to both")

# ================================================================ c the readings
check(last_mail(L) == {"on": False}, "c login, no mail: {on = false}", last_mail(L))
L.execute('HAS = true')
senders(L, "Brannoc")
d = mail_event(L)
check(len(d) == 1 and d[-1]['c'].get('mail') == {"on": True, "from": ["Brannoc"]}, "c new mail, one sender: {on, from = {Brannoc}}",
      d and d[-1]['c'].get('mail'))
check(int(L.eval('PUSHES')) > 0, "c ... and pushed (the app sees it at once)")
DOC_ONE = d[-1] if d else None
senders(L, "Brannoc", "Auction House", "Thalia Ashgrove", "Fourth Sender")
d = mail_event(L)
check(len(d) == 1 and d[-1]['c'].get('mail') == {"on": True, "from": ["Brannoc", "Auction House", "Thalia Ashgrove"]},
      "c three senders (a fourth value is never sent)", d and d[-1]['c'].get('mail'))
DOC_THREE = d[-1] if d else None
n0 = nsent(L)
L.execute('advance(5)')
t0 = int(L.eval('#TIMERS'))
L.execute('fire("MAIL_INBOX_UPDATE")')
check(int(L.eval('#TIMERS')) == t0, "d a MAIL_INBOX_UPDATE that changes nothing queues no work (no collect, no timer)", int(L.eval('#TIMERS')) - t0)
d = mail_event(L, 'MAIL_INBOX_UPDATE')
check(d == [], "d ... and sends no document", len(d))
L.execute('HAS = false; SENDERS = {}')
d = mail_event(L, 'MAIL_INBOX_UPDATE')
check(len(d) == 1 and d[-1]['c'].get('mail') == {"on": False}, "c all read (the mailbox's event): {on = false}", d and d[-1]['c'].get('mail'))
DOC_NONE = d[-1] if d else None
L.execute('HAS = true; SENDERS = {}')
d = mail_event(L)
check(len(d) == 1 and d[-1]['c'].get('mail') == {"on": True}, "c mail the game names nobody for: {on}, no `from`", d and d[-1]['c'].get('mail'))
DOC_NOBODY = d[-1] if d else None

# ================================================================ d event-driven: no read without an event
r0 = (int(L.eval('READS.has')), int(L.eval('READS.senders')))
n0 = nsent(L)
L.execute('advance(600)')
check((int(L.eval('READS.has')), int(L.eval('READS.senders'))) == r0, "d ten quiet minutes: nothing read", r0)
check(cal_docs(L, n0) == [], "d ... and no document")
L.execute('WowCompanion.snapshot("heartbeat"); advance(1)')
hb = cal_docs(L, n0)
check((int(L.eval('READS.has')), int(L.eval('READS.senders'))) == r0, "d the heartbeat's forced document reads nothing")
check(len(hb) >= 1 and hb[-1]['c'].get('mail') == {"on": True}, "d ... and carries the last reading", hb and hb[-1]['c'].get('mail'))
L.execute('fire("PLAYER_ENTERING_WORLD", false, false); advance(1)')
check(int(L.eval('READS.has')) == r0[0] + 1, "d a world entry (a loading screen's end) reads once")
# a /reload: the core's "login" document (WC.snapshot at PLAYER_LOGIN, as Core.lua's start() sends it) goes BEFORE the world entry; it
# carries the reading taken at load, so Home's envelope never blinks off (review of feat/wk-mail)
RELOAD = ('for _, a in ipairs({ "WowCompanion", "WowCompanionQuests", "WowCompanionHub", "WowCompanionInteract", "WowCompanionSpells", '
          '"WowCompanionCalendar" }) do fire("ADDON_LOADED", a) end; fire("PLAYER_LOGIN"); WowCompanion.snapshot("login")')
LR, errsR = boot_world(files=FILES, pre=MAIL_PRE + 'WowCompanion.partyOk = true\nHAS = true; SENDERS = { "Brannoc" }\n', strict=True,
                       login=RELOAD, expose=False)
dR = cal_docs(LR)
check(len(dR) >= 1 and dR[0]['c'].get('mail') == {"on": True, "from": ["Brannoc"]},
      "d a /reload's login document (before the world entry) already carries the mail", dR and dR[0]['c'].get('mail', 'NONE'))
check(int(LR.eval('READS.has')) == 1, "d ... read once, at load", int(LR.eval('READS.has')))
LR.execute('fire("PLAYER_ENTERING_WORLD", false, true); runTimers(NOW + 10)')
check(clean(LR, errsR) == {}, "d ... and the reload world stays clean", clean(LR, errsR))

# ================================================================ e plain values only
L.execute('HAS = SECRETV')
d = mail_event(L)
check(d == [] and last_mail(L) == {"on": True}, "e a secret HasNewMail: the last reading stands, nothing sent", last_mail(L))
L.execute('HAS = true')
senders(L, "SECRET", "", "Hidden Sender", "Brannoc")
d = mail_event(L)
check(len(d) == 1 and d[-1]['c'].get('mail') == {"on": True, "from": ["Brannoc"]}, "e a secret (a table, a string) and an empty sender are dropped",
      d and d[-1]['c'].get('mail'))
# (the stub's _G answers a missing global with its STUB table: not a function, as the game's nil is not)
L2, errs2 = world('HasNewMail = nil\n')
check(last_mail(L2) == 'NONE', "e no HasNewMail on the client: no `mail` at all", last_mail(L2))
L3, errs3 = world('function HasNewMail() return 1 end\n')
check(last_mail(L3) == {"on": True}, "e an older client's 1: {on}", last_mail(L3))

# ================================================================ f the game rules
L4, errs4 = world('RULES[77] = true; HAS = true\n')
check(last_mail(L4) == {"off": True}, "f IngameMailNotificationDisabled: {off = true}", last_mail(L4))
L5, errs5 = world('RULES[76] = true; HAS = true; SENDERS = { "Brannoc" }\n')
d5 = cal_docs(L5)
check(d5 and d5[-1]['c'].get('off') is True and d5[-1]['c'].get('mail') == {"on": True, "from": ["Brannoc"]},
      "f the calendar switched off still carries `mail`", d5 and d5[-1]['c'])

# ================================================================ g in a fight
L6, errs6 = world()
L6.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.5); HAS = true')      # the event BEFORE the lockdown, as the game
senders(L6, "Brannoc")
d = mail_event(L6)
check(len(d) == 1 and d[-1]['c'].get('mail') == {"on": True, "from": ["Brannoc"]}, "g in a fight: new mail's document goes",
      d and d[-1]['c'].get('mail'))
L6.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(2)')
check(clean(L6, errs6) == {}, "g no lockdown breach, no spied call, no Lua error", clean(L6, errs6))
for nm, (w, e) in {"main": (L, errs), "no api": (L2, errs2), "older": (L3, errs3), "rule": (L4, errs4), "cal off": (L5, errs5)}.items():
    check(clean(w, e) == {}, "g clean: %s world" % nm, clean(w, e))

# ================================================================ h privacy: the names only in `mail.from`
leak = []
for i in range(1, nsent(L) + 1):
    s = L.eval('SENT[%d]' % i)
    try:
        dd = json.loads(s)
    except Exception:
        dd = None
    if isinstance(dd, dict) and dd.get('type') == 'calendar':
        c = dict(dd.get('c') or {})
        c.pop('mail', None)
        s = json.dumps(c)
    for nm in ("Brannoc", "Thalia Ashgrove", "Auction House"):
        if nm in s:
            leak.append((i, nm))
check(leak == [], "h the senders appear nowhere but the calendar document's `mail`", leak[:5])

# ================================================================ the renders' documents
DUMP = os.environ.get("MAIL_DUMP")
if DUMP:
    os.makedirs(DUMP, exist_ok=True)
    L7, _ = world('HAS = true; SENDERS = { "Feathermoon Stronghold Quartermaster Long Name", "Brannoc", "Thalia Ashgrove" }\n')
    for name, dd in (("none", DOC_NONE), ("one", DOC_ONE), ("three", DOC_THREE), ("nobody", DOC_NOBODY), ("long", cal_docs(L7)[-1])):
        if dd:
            open(os.path.join(DUMP, name + ".json"), "w", encoding="utf-8").write(json.dumps(dd))
    print("dumped to", DUMP)

print("\n%s (%d pass, %d fail)" % ("ALL PASS" if fails == 0 else "FAILURES", passes, fails))
sys.exit(1 if fails else 0)
