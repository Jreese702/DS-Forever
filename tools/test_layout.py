"""THE TOP-SCREEN LAYOUT EDITOR, STAGE 1 (TapPad pad22, Trail prd18 / cp41, ActionBar nbar3, Chat chat37, Hub hub55 / uf23), off-device:
the REAL add-on files under tools/wowstub.py's stubbed game (the strict world: the lockdown on every protected call).

    python tools/test_layout.py            (PARTY_FIXTURES_WRITE=1 rewrites the fixtures the app's tests read: dsforever/src/test/resources/
                                            layout-fixtures, LayoutModelTest and LayoutShotsTest; otherwise a stale one fails)

The user, 2026-11-16: "we need to make a way to basically edit the UI locations on the top screen from our UI menu"; the design's picks,
all the recommended ones (DS Forever's own pieces first; taps that set an exact spot; linked pieces follow their parent until moved; one
saved layout per character plus "Copy from character"; a Layout group first on Settings > Top screen, HUD height and scale in the editor).
Proven here:
  a  the versions, the header notes quoting the user, Lua 5.1 compiles, and what the editor's code never calls (the game's Edit Mode
     saves, its windows, bindings, CVars);
  b  THE ROW: "lay" is the GUEST of the Calendar room's row "cal" (no row of the pad's own: the fullest layout's 56 rows unchanged), not
     described until the Settings room holds the controller, then leased (described at cal's row, cal not); the Calendar asks its row
     back (padtab 39 then 35) and gets it;
  c  the document at login: six pieces in order, their select columns 0-5, their default anchors (the HUD's top-centre 350 under the
     centre, the compass 4 from the top, the target bar under it, the party column, the Nintendo bar at the stock bar's centre, the chat
     strip's TOP-centre, its bottom border 16 under the screen's edge); no "norow" once the row is made (the review);
  d  OPEN / SELECT / A SPOT: the catcher up (TOOLTIP, level 0: under every cell), a tap at a spot puts the selected piece's anchor there
     (the HUD's frame at it after the ease), the document (part) says where, NOTHING saved until Save (WowCompanionDB.lay untouched;
     SvKeep's svkeep carries no lay), then Save writes it and SvKeep carries it;
  e  THE CATCHER: the mapping (1080-line pixels from the cursor), the 60 ms same-spot guard, the pad band and the sink refused, the
     reach switch (a tap REACH lower reaches a spot inside the band; on a smaller screen the reach still clears the band), the selected
     piece kept on the screen;
  f  LINKED: the target bar follows the compass until it is moved itself; Reset re-attaches it;
  g  SIZE (32 / 33) within the piece's limits, the chat strip moved sideways from its own place (its anchor on the screen), REVERT (35),
     CLOSE without Save drops the draft, COPY FROM CHARACTER (24..29);
  h  THE FIGHT: the look and the stick catch hide at once, the catcher inert (a tap moves nothing) and gone LINGER_S after its last tap,
     the draft kept, everything back at the fight's end; the row's cells pressed in a fight breach nothing;
  i  the game's own Edit Mode shown: the editor never opens, and closes when it opens; the Settings room letting go closes it (AWAY_S);
  j  THE HUD'S ONE-TIME MOVE (layV 1): every character's old height / scale becomes its layout entry with the bars' top where it was; a
     second login moves nothing; the hub document says prd.lay;
  k  the owners place themselves by the layout: the Nintendo bar, the chat strip, the party column, the compass, the target bar;
  l  clean: no Lua error, no spied call, no lockdown breach;
  m  hub58 (the user, 2026-11-17: "the edit mode should include any UI element that is currently turned on, including the wow UI and
     polestar. the polestar ui should be broken into 2, one for the arrow and one for the actual guide."): only the pieces that are on
     are listed (live: a new document when the set changes; an unlisted piece never selected, let go when it goes); Polestar 0.2.5's
     PolestarNavArrow and PolestarGuideFrame as two pieces (an offset from Polestar's own place, re-applied after its own placing calls
     and at OnShow, a protected frame never moved in a fight, Polestar's own save of a place drops our offset, Reset, nothing of
     PolestarDB written);
  n  hub59 (the user's stage-2 picks: our own offsets, every one that's on, out of combat, move and resize): the game's frames as
     pieces (fake frames of the client's shape: Edit Mode systems with their ...Base methods, the controller bar no system, a
     protected one in a fight queued to its end, the game's layout update and Edit Mode exit re-applying ours, Reset game UI; never a
     plain system method, never a C_EditMode call).
  o  hub61 (the user, 2026-11-17: "DPS meters and also the cooldown manager ... we need to make sure that its consistent with the other
     picture options"): the game's damage meter and its four Cooldown Manager viewers hidden by alpha 0 + EnableMouse(false) only (the
     hide switches, never Hide / Show / a CVar), the press on "lay" while the editor is closed, per character, Dynamic's 15 s linger
     (cancelled by a new fight), a press in a fight held to its end, Edit Mode's opacity / layout update re-asserted, the game's Edit
     Mode shown = all shown, the game's options only read (the hub document's mt), a stored choice applied at login without the app.
  p  hub63 (the user, 2026-11-17: "we need to split out the Buffs controller UI and XP bar" -> "Both"): the controller UI, the buffs and
     the XP bar each with its own word on "lay" while the editor is closed (on / off / ride the layout bit), no word = the old element,
     the editor listing and moving each part alone, the game re-anchoring the buffs re-applied, a word in a fight with no breach.
Needs lupa. Exit code 1 on any failure."""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wowstub  # noqa: E402
from wowstub import ADDONS, ADDON_OF, F, SRC, boot_world  # noqa: E402
from lupa import lua51 as lupa  # noqa: E402

fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


ORDER = [("WowCompanion", ["Safety", "Sink", "TapPad", "Harvest", "ActionBar", "Coords", "Map", "Trail", "MapArt", "Chat", "Bags", "Core"]),
         ("WowCompanionQuests", ["Quests", "Tracker"]),
         ("WowCompanionHub", ["Hub"]),
         ("WowCompanionInteract", ["Interact", "Dialogs"]),
         ("WowCompanionSpells", ["Spells", "Book", "Talents", "TalentsCtl", "PadMap", "PadTabCtl", "Radial"]),
         ("WowCompanionCharacter", ["Character", "Auras"]),
         ("WowCompanionSocial", ["Social"]), ("WowCompanionCalendar", ["Calendar"]),
         ("WowCompanionGroupFinder", ["GroupFinder"]), ("WowCompanionProfessions", ["Professions"]), ("WowCompanionPvp", ["Pvp"])]
ALL = []
for d, fs in ORDER:
    for f in fs:
        k = "x_" + d + "_" + f
        p = os.path.join(ADDONS, d, f + ".lua")
        if not os.path.exists(p):
            continue
        F[k] = p
        SRC[k] = open(p, encoding="utf-8").read()
        ADDON_OF[k] = d
        ALL.append(k)
NAMES = [d for d, _ in ORDER]
LOGIN = ('for _, a in ipairs({ %s }) do fire("ADDON_LOADED", a) end; fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); '
         'runTimers(NOW + 10)' % ", ".join('"%s"' % n for n in NAMES))
# what the stub lacks for this: the cursor, UIParent's effective scale, the scale / strata / level a frame was given
PRE = ('function GetCursorPosition() return CURSOR_X or 0, CURSOR_Y or 0 end\n'
       'function UIParent:GetEffectiveScale() return 1 end\n'
       'function Frame:SetScale(s) self._scale = s end\n'
       'function Frame:GetScale() return self._scale or 1 end\n'
       'function Frame:SetFrameStrata(s) self._strata = s end\n'
       'function Frame:GetFrameStrata() return self._strata end\n'
       'function Frame:SetFrameLevel(n) self._level = n end\n'
       'function Frame:SetSize(w, h) self._w, self._h = w, h end\n'
       'function Frame:SetAlpha(a) self._alpha = a end\n')
QUOTE = '"we need to make a way to basically edit the UI locations on the top screen from our UI menu"'
WHO = "Korvash-Realm"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIXDIR = os.path.join(ROOT, "dsforever", "src", "test", "resources", "layout-fixtures")
FIX = {}


def world(pre_extra=''):
    L, errs = boot_world(files=tuple(ALL), pre=PRE + pre_extra, strict=True, login=LOGIN, expose=False)
    # with every add-on loaded Sink.lua carries the sends: every line into SENT again (the documents read below), and the editor's
    # document once more for the record
    # hub58: the editor lists only the pieces that are turned on: the HUD (the bar's own switch) and the Nintendo bar on, as the app's
    # defaults would have them, so all six of ours are listed (the party block by its own want, the chat strip by Hub's LAY.WANT)
    L.execute('WowCompanion.npWant = WowCompanion.npWant or {}; WowCompanion.npWant.prd = true; pcall(WowCompanion.nbarSet, true)')
    L.execute('WowCompanion.send = function(s) SENT[#SENT + 1] = s end; WowCompanion.layEditor.emit(true)')
    return L, errs


def lay_docs(L, start=0):
    return wowstub.docs(L, start, "lay")


def last(L):
    d = lay_docs(L)
    return d[-1] if d else None


def last_full(L):
    d = [x for x in lay_docs(L) if not x.get("part")]
    return d[-1] if d else None


def fresh(L):
    """the editor's full document now (a part document may have come after the last full one)"""
    L.execute('WowCompanion.layEditor.emit(true)')
    return last(L)


def piece(doc, pid):
    for p in (doc or {}).get("p") or []:
        if p.get("id") == pid:
            return p
    return None


def hold(L, on=True):
    """the Settings room's hold / free through the Quests facility (qact 12 / 13: GUESTS[3] = settings), then the editor's tick"""
    L.eval('hwTap')("qact", 12 if on else 13)
    L.execute('advance(0.3); tick(0.25)')


def cell(L, col):
    """a tap on the lay row's cell [col] (only where it is shown: the row is the tenant)"""
    shown = L.eval('(function(c) local r = WowCompanion.pad.rows.lay; local b = r and r.cells and r.cells[c]; return b ~= nil and b:IsShown() end)')(col)
    if not shown:
        return False
    L.eval('hwTap')("lay", col)
    L.execute('advance(0.3)')
    return True


def ease(L, n=30):
    for _ in range(n):
        L.execute('local f = WowCompanion.layEditor.frame; if f:IsShown() and f.scripts.OnUpdate then f.scripts.OnUpdate(f, 0.05) end')


def catch(L, x, y, dt=0.3):
    """a finger's tap at 1080-line pixel (x, y) on the top screen: the cursor there, then the catcher's press (a cell over it would
    take it first: the caller only taps the catcher where no cell is)"""
    L.execute('local uh = UIParent:GetHeight(); CURSOR_X = %r * uh / 1080; CURSOR_Y = uh - %r * uh / 1080' % (x, y))
    L.execute('local c = WowCompanion.lay.catcher; if c then doClick(c, "LeftButton", true) end')
    L.execute('advance(%r)' % dt)


def lua_errors(L, errs):
    return list(errs) + [L.eval('ERRS[%d]' % i) for i in range(1, int(L.eval('#ERRS')) + 1)]


def fixture(L, name, part=False):
    """the editor's document as the add-on's own encoder wrote it (the full one now, or the last part one) -> FIX[name]"""
    if not part:
        L.execute('WowCompanion.layEditor.emit(true)')
    for i in range(int(L.eval('#SENT')), 0, -1):
        t = L.eval('SENT[%d]' % i)
        try:
            d = json.loads(t)
        except Exception:
            continue
        if isinstance(d, dict) and d.get("type") == "lay" and bool(d.get("part")) == part:
            FIX[name] = t
            return


# ================================================================ a. versions, notes, compile, what the editor's code never calls
comp = lupa.LuaRuntime().eval('function(s, n) local f, e = loadstring(s, n); return e end')
FILES = {"tappad": "WowCompanion/TapPad.lua", "trail": "WowCompanion/Trail.lua", "actionbar": "WowCompanion/ActionBar.lua",
         "chat": "WowCompanion/Chat.lua", "hub": "WowCompanionHub/Hub.lua"}
TXT = {k: open(os.path.join(ADDONS, v), encoding="utf-8").read() for k, v in FILES.items()}
for k, v in TXT.items():
    e = comp(v, k)
    check(e is None, "a. Lua 5.1 compiles %s" % FILES[k], e or '')
check(int(re.search(r'local PADV = "pad(\d+)"', TXT["tappad"]).group(1)) >= 22 and int(re.search(r'WC\.prd = \{ V = "prd(\d+)"', TXT["trail"]).group(1)) >= 19 and int(re.search(r'WC\.compass = \{ V = "cp(\d+)" \}', TXT["trail"]).group(1)) >= 43
      and int(re.search(r'local NB = \{ V = "nbar(\d+)"', TXT["actionbar"]).group(1)) >= 4 and 'local CHATV = "chat43"' in TXT["chat"] and int(re.search(r'local HV = "hub(\d+)"', TXT["hub"]).group(1)) >= 59
      and int(re.search(r'V = "uf(\d+)" \}', TXT["hub"]).group(1)) >= 24 and 'V = "lay1"' in TXT["hub"],
      "a. the versions: TapPad pad22, Trail prd19 / cp43 (or later), ActionBar nbar4 (or later: nbar5 the split swing lines), Chat chat42 (the toast glow veil + notice flash), Hub hub59+ (hub58 + the game's own UI; editor lay1, uf24): the 11-16 merge")
check(all(QUOTE in TXT[k] for k in TXT), "a. each changed file's header note quotes the user", [k for k in TXT if QUOTE not in TXT[k]])
check(TXT["trail"].count(QUOTE) >= 2, "a. Trail: both notes (prd18, cp41) quote the user")
ED = TXT["hub"][TXT["hub"].index("-- ==================================================================== hub55 THE TOP-SCREEN LAYOUT EDITOR"):]
CA = TXT["tappad"][TXT["tappad"].index("-- ---------------------------------------------------------------- pad22 THE LAYOUT EDITOR'S CATCHER"):]
code = "\n".join(re.sub(r'--.*$', '', ln) for ln in (ED + "\n" + CA).splitlines())
bad = [w for w in ("SaveLayouts", "SetActiveLayout", "OnLayoutAdded", "ReloadUI", "ShowUIPanel", "HideUIPanel", "SetCVar", "SetBinding",
                   "SaveBindings", "SetOverrideBinding", "EnterEditMode", "ExitEditMode", "EditModeManagerFrame:Show",
                   "EditModeManagerFrame:Hide", "RunMacroText", "CastSpell", "UseAction", "TargetUnit") if w in code]
check(bad == [], "a. the editor and the catcher never call the game's Edit Mode saves, windows, bindings, CVars or actions", bad)
check("LAY.game = nil" not in code and "game = nil" in ED and "STAGE 2 HOOK" in ED,
      "a. stage 2's hook is named (LAY.game, every piece g = false)")


# ================================================================ b. the row
L, errs = world()
check(lua_errors(L, errs) == [], "b. the world loads with every add-on, no error", lua_errors(L, errs)[:3])
rows = dict((k, int(v)) for k, v in L.eval('(function() local t = {} for n, r in pairs(WowCompanion.pad.rows) do t[n] = r.row end return t end)()').items())
check(rows.get("lay") is not None and rows.get("lay") == rows.get("cal") and L.eval('WowCompanion.pad.rows.lay.guestOf') == "cal",
      "b. lay is the guest of the Calendar room's row cal (no row of its own)", (rows.get("lay"), rows.get("cal")))
check(len(set(rows.values())) == len(set(v for k, v in rows.items() if k != "lay")),
      "b. the guest takes no place of the pad's own", (len(rows), len(set(rows.values()))))
d0 = L.eval('WowCompanion.pad.describe()')
check(d0.rows.lay is None and d0.rows.cal is not None, "b. at login the Calendar holds the row: cal described, lay not")
doc = last(L)
check(doc is not None and doc.get("row") == "lay" and doc.get("open") is False and doc.get("held") is None,
      "b. the lay document at login: the row, closed, the Settings room not holding", doc and (doc.get("row"), doc.get("open"), doc.get("held")))
check(doc is not None and doc.get("why") is None and L.eval('WowCompanion.layEditor.why') is None,
      "b. the row made: no 'norow' (the review: `rowOk and why or 'norow'` said it with no why yet)", doc and doc.get("why"))
hold(L)
d1 = L.eval('WowCompanion.pad.describe()')
check(d1.rows.lay == rows["cal"] and d1.rows.cal is None, "b. the Settings room holds: lay leased onto the row (described), cal not",
      (d1.rows.lay, d1.rows.cal))
doc = last(L)
check(doc.get("held") is True and (doc.get("pad") or {}).get("rows", {}).get("lay") == rows["cal"],
      "b. the document says so: held, the pad's lay row", (doc.get("held"), (doc.get("pad") or {}).get("rows", {}).get("lay")))
fixture(L, "lay_closed")

# ================================================================ c. the document's pieces
ids = [p["id"] for p in doc["p"]]
check(ids == ["hud", "compass", "target", "party", "nbar", "chat", "psarrow", "psguide"], "c. eight pieces, in order (hub58: Polestar's two last)", ids)
check([p["c"] for p in doc["p"]] == [0, 1, 2, 3, 4, 5, 6, 7], "c. their select columns 0-7", [p["c"] for p in doc["p"]])
check([p["id"] for p in doc["p"] if p["on"]] == ["hud", "compass", "target", "party", "nbar", "chat"],
      "c. hub58: our six listed (turned on), Polestar's two not (not loaded)", [(p["id"], p["on"]) for p in doc["p"]])
check(piece(doc, "party")["vis"] is False and piece(doc, "chat")["vis"] is False and piece(doc, "compass")["vis"] is True,
      "c. hub58: vis = shown now (the party block solo, the chat strip between messages: listed, not shown)",
      [(p["id"], p.get("vis")) for p in doc["p"]])
W = doc["w"]
hud = piece(doc, "hud")
check(abs(hud["ax"] - W / 2) < 0.01 and abs(hud["ay"] - 890) < 0.01 and hud["d"] is True and hud["s"] == 100
      and hud["sn"] == 70 and hud["sx"] == 160 and hud["ss"] == 10,
      "c. the HUD: its top-centre 350 under the centre (%s, %s), at the default, 70-160 in 10s" % (hud["ax"], hud["ay"]), hud)
cmp_ = piece(doc, "compass")
check(abs(cmp_["ax"] - W / 2) < 0.01 and cmp_["ay"] == 4 and cmp_["w"] == 860 and cmp_["h"] == 76,
      "c. the compass: top-centre 4 from the top, 860 x 76", (cmp_["ax"], cmp_["ay"], cmp_["w"], cmp_["h"]))
tgt = piece(doc, "target")
check(tgt["l"] == "compass" and abs(tgt["ay"] - 86) < 0.01 and abs(tgt["ax"] - (W / 2 + 16 - 186)) < 0.01 and tgt["d"] is True,
      "c. the target bar: linked to the compass, its top-left under it (%s, %s)" % (tgt["ax"], tgt["ay"]), tgt)
par = piece(doc, "party")
check(par["ax"] == 24 and par["ay"] == 250 and par["w"] == 300 and par["h"] == 256, "c. the party block: the left column (24, 250), 300 x 256",
      (par["ax"], par["ay"], par["w"], par["h"]))
chat = piece(doc, "chat")
check(abs(chat["ay"] - 655) < 0.01 and abs(chat["y"] - 655) < 0.01 and abs(chat["y"] + chat["h"] - 1096) < 0.01
      and chat["sn"] == 100 and chat["sx"] == 100,
      "c. the chat strip: its anchor the top-centre (on the screen), its bottom 16 under the edge, moved only",
      (chat["ay"], chat["y"], chat["y"] + chat["h"], chat["sn"], chat["sx"]))
nbar = piece(doc, "nbar")
check(nbar is not None and nbar["w"] > 0 and nbar["h"] > 0, "c. the Nintendo bar: its rect at the stock bar's centre", nbar)
check(all(p["g"] is False and p["m"] is True for p in doc["p"]), "c. every piece is ours (g false) and can move")
catch_ = doc.get("catch") or {}
no = catch_.get("no") or []
check(catch_.get("reach") == 160 and len(no) == 2 and no[0]["why"] == "pad" and no[1]["why"] == "sink",
      "c. the catcher's zones: the pad band and the sink; reach 160", catch_)
check(doc.get("chars") == [] and doc.get("dirty") is False and doc.get("combat") is False, "c. no other character's layout, clean")

# ================================================================ d. open, select, a spot, nothing saved until Save
check(cell(L, 37), "d. Open with the grid (37) is tappable")
doc = last(L)
c = L.eval('WowCompanion.lay.catcher')
check(doc["open"] is True and doc["grid"] is True and L.eval('WowCompanion.lay.editing') is True,
      "d. open: the document says so, the grid on, the owners read the view")
check(c is not None and c._shown is True and c._strata == "TOOLTIP" and c._level == 0 and list(c.clicks.values()) == ["AnyDown"],
      "d. the catcher: shown, TOOLTIP, level 0 (under every cell and the sink), the press only", c and (c._strata, c._level, list(c.clicks.values())))
check(L.eval('WowCompanion.layEditor.look._shown') is True and L.eval('WowCompanion.layEditor.look.grid._shown') is True,
      "d. the top screen's look and the grid shown")
check(L.eval('WowCompanion.layEditor.stick ~= nil and WowCompanion.layEditor.stick._shown') is True,
      "d. the stick catch shown (the stick glides the piece, not the character)")
cell(L, 0)
check(last(L)["sel"] == "hud", "d. select the HUD (0)")
n0 = wowstub.nsent(L)
catch(L, 1000, 800)
ease(L)
P = L.eval('WowCompanion.prd')
L.execute('WowCompanion.prd.build()')                              # (the HUD's frames: built at its first show on the device)
lay = L.eval('WowCompanion.lay')
v = lay.view.hud
check(v is not None and v.dx == round(1000 - W / 2) and v.dy == 800 - 890, "d. the spot (1000, 800): the HUD's view there (dx %s, dy %s)" % (v and v.dx, v and v.dy))
pts = P.f and P.f._pts
check(pts is not None and abs(pts[4] - (1000 - W / 2) * L.eval('UIParent:GetHeight()') / 1080) < 0.6
      and abs(pts[5] - (540 - 800) * L.eval('UIParent:GetHeight()') / 1080) < 0.6,
      "d. the HUD's frame placed there (its top-centre from the centre, UI units)", pts and (pts[4], pts[5]))
parts = [x for x in lay_docs(L, n0) if x.get("part")]
check(parts and parts[-1]["p"][0]["id"] == "hud" and abs(parts[-1]["p"][0]["ay"] - 800) < 0.01 and parts[-1]["dirty"] is True,
      "d. the document (part): the HUD alone, at its new anchor, dirty", parts and parts[-1]["p"][0])
fixture(L, "lay_part", part=True)
fixture(L, "lay_moved")
check(L.eval('WowCompanionDB.lay == nil or WowCompanionDB.lay["%s"] == nil' % WHO) is True,
      "d. NOTHING SAVED until Save (WowCompanionDB.lay untouched)")
L.execute('tick(5)')
sv = [x for x in wowstub.docs(L, 0, "svkeep") if x.get("var") == "WowCompanionDB"]
check(not sv or '["lay"]' not in sv[-1]["lua"], "d. SvKeep's copy carries no draft (nothing new to send, or no lay in it)", sv and sv[-1]["lua"][:80])
cell(L, 36)
saved = L.eval('WowCompanionDB.lay["%s"]' % WHO)
check(saved is not None and saved.hud.dx == round(1000 - W / 2) and saved.hud.dy == -90 and saved.hud.s == 100,
      "d. Save (36): the HUD's entry written for this character", saved and (saved.hud.dx, saved.hud.dy, saved.hud.s))
check(last(L)["dirty"] is False and last(L)["saves"] == 1, "d. after Save: clean")
L.execute('tick(5)')
sv = [x for x in wowstub.docs(L, 0, "svkeep") if x.get("var") == "WowCompanionDB"]
check(sv and '["lay"]={["%s"]={["hud"]={["dx"]=' % WHO in sv[-1]["lua"], "d. SvKeep carries the saved layout", sv and sv[-1]["lua"][:120])

# ================================================================ e. the catcher
lay.lastAt = -1
before = lay.n
catch(L, 1100, 700, dt=0.03)                                     # (dt: the time that passes AFTER the press)
catch(L, 1100, 700, dt=0.04)                                     # 30 ms after the first: the tap's own second press
check(lay.n == before + 1, "e. the 60 ms guard: a second press at the same spot within 30 ms is the tap's own (%d)" % (lay.n - before))
catch(L, 1101.5, 700.5, dt=0.0)                                  # 70 ms after the first
check(lay.n == before + 2, "e. ... 70 ms later the next tap counts")
catch(L, 1200, 700, dt=0.0)
catch(L, 1300, 700, dt=0.3)
check(lay.n == before + 4, "e. ... and a different spot within the window counts too")
ease(L)
check(lay.view.hud.dx == round(1300 - W / 2), "e. the latest spot wins (%s)" % lay.view.hud.dx)
ref = lay.refused
band = no[0]
catch(L, band["x"] + band["w"] / 2, band["y"] + band["h"] / 2)
catch(L, no[1]["x"] + no[1]["w"] / 2, no[1]["y"] + no[1]["h"] / 2)
ease(L)
check(lay.refused == ref + 2 and lay.view.hud.dx == round(1300 - W / 2), "e. the pad band and the sink: refused, nothing moved",
      (lay.refused - ref, lay.view.hud.dx))
cell(L, 1)                                                        # the compass
cell(L, 31)                                                       # reach on
check(lay.reach is True and last(L)["reach"] is True, "e. the reach switch on (31)")
catch(L, W / 2 + 40, 20 + 160)                                    # under the band, meant for (W/2 + 40, 20): inside it
ease(L)
check(lay.view.compass.dx == 40 and lay.view.compass.dy == 16, "e. a tap 160 lower reaches a spot inside the band: the compass's anchor at "
      "(+40, 20) (dx %s, dy %s)" % (lay.view.compass.dx, lay.view.compass.dy))
cell(L, 30)
check(lay.reach is False, "e. reach off (30)")
rp = L.eval('(function() local g = WowCompanion.pad.geom; local o = g.ph; local r0 = WowCompanion.lay.reachPx(); g.ph = 720; '
            'local r = WowCompanion.lay.reachPx(); local z = WowCompanion.lay.forbidden()[1]; local d = WowCompanion.lay.catchDescribe(); '
            'g.ph = o; return { r0 = r0, r = r, bottom = z.y + z.h, d = d.reach } end)()')
check(rp.r0 == 160 and rp.r > 160 and rp.r >= rp.bottom + 8 and rp.d == rp.r,
      "e. the reach clears the band on a smaller screen too (720 lines: band bottom %.1f, reach %s; 160 at the Thor's)" % (rp.bottom, rp.r),
      (rp.r0, rp.r, rp.bottom, rp.d))
cell(L, 0)
catch(L, 5, 1075)                                                 # the HUD's top-centre in the corner: kept on the screen
ease(L)
r = piece(fresh(L), "hud")
check(r["x"] >= -0.01 and r["y"] + r["h"] <= 1080.01 and r["x"] < 1, "e. kept on the screen (the HUD's rect %s, %s, %s x %s)" % (r["x"], r["y"], r["w"], r["h"]))

# ================================================================ f. linked: the target bar follows the compass until moved
t0 = piece(fresh(L), "target")
c0 = piece(fresh(L), "compass")
check(t0["d"] is True and abs(t0["ay"] - (c0["y"] + c0["h"] + 6)) < 0.01, "f. the target bar follows the compass's move (no entry of its own)",
      (t0["ay"], c0["y"] + c0["h"] + 6))
cell(L, 2)
catch(L, 300, 300)
ease(L)
cell(L, 1)
catch(L, W / 2, 200)
ease(L)
t1 = piece(fresh(L), "target")
check(t1["d"] is False and t1["ax"] == 300 and t1["ay"] == 300, "f. moved itself, it stays when the compass moves again", (t1["ax"], t1["ay"], t1["d"]))
cell(L, 2)
cell(L, 34)
ease(L)
t2 = piece(fresh(L), "target")
c2 = piece(fresh(L), "compass")
check(t2["d"] is True and abs(t2["ay"] - (c2["y"] + c2["h"] + 6)) < 0.01, "f. Reset (34) re-attaches it under the compass", (t2["ay"], t2["d"]))
fixture(L, "lay_linked")

# ================================================================ g. size, revert, close without save, copy from character
cell(L, 0)
for _ in range(3):
    cell(L, 33)
check(piece(last_full(L), "hud")["s"] == 130, "g. three steps bigger: 130 %", piece(last_full(L), "hud")["s"])
for _ in range(5):
    cell(L, 33)
check(piece(last_full(L), "hud")["s"] == 160, "g. never past its largest (160 %)", piece(last_full(L), "hud")["s"])
for _ in range(12):
    cell(L, 32)
check(piece(last_full(L), "hud")["s"] == 70, "g. never under its smallest (70 %)", piece(last_full(L), "hud")["s"])
cell(L, 5)
cell(L, 33)
check(piece(last_full(L), "chat")["s"] == 100, "g. the chat strip has no size (100 % only)")
fixture(L, "lay_dirty")
catch(L, W / 2 - 100, 655)                                        # the chat strip (selected) sideways from its own place
ease(L)
check(lay.view.chat is not None and lay.view.chat.dx == -100 and lay.view.chat.dy == 0,
      "g. the chat strip moves sideways from its own place (its top-centre anchor is on the screen: dx %s, dy %s)"
      % (lay.view.chat and lay.view.chat.dx, lay.view.chat and lay.view.chat.dy))
cell(L, 35)
ease(L)
d = fresh(L)
check(d["dirty"] is False and piece(d, "hud")["dx"] == round(1000 - W / 2) and piece(d, "hud")["s"] == 100,
      "g. Revert (35): every piece back to the saved layout, clean", (d["dirty"], piece(d, "hud")["dx"], piece(d, "hud")["s"]))
cell(L, 0)
catch(L, 900, 700)
cell(L, 39)
check(last(L)["open"] is False and L.eval('WowCompanion.lay.editing') is False and L.eval('WowCompanionDB.lay["%s"].hud.dy' % WHO) == -90,
      "g. Close (39) without Save: the draft dropped, the saved layout kept")
check(L.eval('WowCompanion.layEditor.look._shown') is False and L.eval('WowCompanion.layEditor.stick._shown') is False,
      "g. ... the look and the stick catch gone")
check(L.eval('WowCompanion.lay.inert') is True and L.eval('WowCompanion.lay.catcher._shown') is True, "g. ... the catcher inert at once")
L.execute('advance(2)')
check(L.eval('WowCompanion.lay.catcher._shown') is False, "g. ... and hidden after LINGER_S")
L.execute('WowCompanionDB.lay["Other-Realm"] = { hud = { dx = 10, dy = -20, s = 120 }, compass = { dx = 0, dy = 30, s = 90 } }')
cell(L, 38)
d = last_full(L)
check(d["open"] is True and d["grid"] is False and d["chars"] == ["Other-Realm"], "g. Open without the grid (38); the other character listed",
      (d["open"], d["grid"], d["chars"]))
fixture(L, "lay_copy")
L.execute('advance(2.1)')                                         # (hub64: a cell 24-32 inside HS_GRACE_S of the open is dropped)
cell(L, 24)
d = last_full(L)
check(d["dirty"] is True and piece(d, "hud")["dx"] == 10 and piece(d, "hud")["s"] == 120 and piece(d, "compass")["dy"] == 30
      and piece(d, "target")["d"] is True, "g. Copy from character (24): their layout in the draft, the rest at the default", d["p"][:3])
cell(L, 36)
mine = L.eval('WowCompanionDB.lay["%s"]' % WHO)
check(mine.hud.dx == 10 and mine.hud.s == 120 and mine.compass.dy == 30 and mine.compass.s == 90,
      "g. ... saved as this character's own")
check(L.eval('WowCompanionDB.lay["Other-Realm"].hud.dx') == 10, "g. ... the other character's untouched")

# ================================================================ h. the fight
cell(L, 0)
catch(L, 960, 700)
ease(L)
draft_dy = lay.view.hud.dy
L.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true')          # (the event comes before the lockdown, as in the game)
check(L.eval('WowCompanion.layEditor.look._shown') is False and L.eval('WowCompanion.layEditor.stick._shown') is False,
      "h. a fight: the look and the stick catch hidden at once")
check(L.eval('WowCompanion.lay.inert') is True and L.eval('WowCompanion.lay.catcher._shown') is True and last(L)["combat"] is True,
      "h. ... the catcher inert (still up: taps in flight land on it), the document says combat")
catch(L, 960, 300, dt=0.5)
ease(L)
check(lay.view.hud.dy == draft_dy, "h. ... a tap moves nothing")
catch(L, 960, 310, dt=1.0)
check(L.eval('WowCompanion.lay.catcher._shown') is True, "h. ... the catcher stays LINGER_S after its LAST tap")
L.execute('advance(1.0)')
check(L.eval('WowCompanion.lay.catcher._shown') is False, "h. ... then hides")
viol0 = int(L.eval('#VIOL'))
cell(L, 1)
cell(L, 33)
check(int(L.eval('#VIOL')) == viol0, "h. the row's cells pressed in a fight breach nothing (the lockdown)")
check(last(L)["open"] is True and lay.view.hud.dy == draft_dy, "h. the draft kept through the fight")
fixture(L, "lay_combat")
L.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(0.3)')
check(L.eval('WowCompanion.layEditor.look._shown') is True and L.eval('WowCompanion.lay.catching()') is True and last(L)["combat"] is False,
      "h. the fight's end: the look and the catcher back")
cell(L, 39)

# ================================================================ i. the game's own Edit Mode; the Settings room letting go
L.execute('EditModeManagerFrame = mkwin("EditModeManagerFrame", true)')
cell(L, 37)
check(last(L)["open"] is False and last(L)["why"] == "editmode", "i. the game's Edit Mode shown: the editor never opens")
L.execute('EditModeManagerFrame:Hide()')
cell(L, 37)
check(last(L)["open"] is True, "i. ... once it is gone, it opens")
L.execute('EditModeManagerFrame:Show(); tick(0.25)')
check(last(L)["open"] is False and last(L)["why"] == "editmode", "i. the game's Edit Mode opening closes ours")
L.execute('EditModeManagerFrame:Hide()')
cell(L, 37)
# a wheel opened OVER the room (the pause wheel: the capture's overlay guest) only suspends it: the editor stays
L.execute('local c = WowCompanion.capture; RAWSTATE = c.state; c.state = function() return { holder = "pad:radial" } end')
L.execute('for _ = 1, 12 do tick(0.25); advance(0.25) end')
check(last(L)["open"] is True, "i. the pause wheel over the Settings room: the editor stays open (suspended, 3 s)")
L.execute('WowCompanion.capture.state = RAWSTATE')
# while open, the HUD is held up (why "layout": the piece being moved is seen), HUD mode on
L.execute('WowCompanion.npWant = WowCompanion.npWant or {}; WowCompanion.npWant.hud = true; pcall(WowCompanion.prd.tick, 0.1)')
check(L.eval('WowCompanion.prd.why') == "layout", "i. the HUD held up while the editor is open (why %s)" % L.eval('WowCompanion.prd.why'))
hold(L, False)
L.execute('for _ = 1, 6 do tick(0.25); advance(0.25) end')
check(last(L)["open"] is False and last(L)["why"] == "away", "i. the Settings room letting go closes it (AWAY_S)", (last(L)["open"], last(L)["why"]))
check(lua_errors(L, errs) == [] and int(L.eval('#VIOL')) == 0 and int(L.eval('#SPY')) == 0, "l. clean: no Lua error, no lockdown breach, no spied call",
      (lua_errors(L, errs)[:3], int(L.eval('#VIOL')), int(L.eval('#SPY'))))
hd = [x for x in wowstub.docs(L, 0, "hub")]
check(hd and (hd[-1]["hub"].get("prd") or {}).get("lay") is True, "j. the hub document's prd.lay: the layout owns the HUD's height and scale")

# ================================================================ j. the HUD's one-time move
MIG = ('WowCompanionDB = { prdY = { ["%s"] = -300, ["Other-Realm"] = -400 }, prdYv = 2, prdS = { ["%s"] = 120, ["Third-Realm"] = 80 } }\n'
       % (WHO, WHO))
L2, errs2 = world(MIG)
m = L2.eval('WowCompanionDB.lay')
check(L2.eval('WowCompanionDB.layV') == 1, "j. layV 1 after the login")
check(m is not None and m[WHO].hud.dx == 0 and m[WHO].hud.dy == round(-350 + 300 * 1.2) and m[WHO].hud.s == 120,
      "j. this character: -300 at 120 %% -> dy %s, s 120" % (m and m[WHO].hud.dy))
check(m["Other-Realm"].hud.dy == 50 and m["Other-Realm"].hud.s == 100 and m["Third-Realm"].hud.dy == -70 and m["Third-Realm"].hud.s == 80,
      "j. every character at once (-400 -> dy 50; the default height at 80 %% -> dy -70: the top 280 under the centre as it stood, s 80)",
      (m["Other-Realm"].hud.dy, m["Third-Realm"].hud.dy))
P2 = L2.eval('WowCompanion.prd')
P2.build()
uh = L2.eval('UIParent:GetHeight()')
check(abs(P2.f._pts[5] * 1.2 - (-300 * 1.2) * uh / 1080) < 1e-6 and abs(P2.y - (-300)) < 1e-9,
      "j. the bars' top where it was (the frame's offset x its scale = the old one: %s)" % (P2.f._pts[5] * 1.2))
L2.execute('WowCompanionDB.prdY["%s"] = -200; WowCompanion.prd.fromLay()' % WHO)
check(L2.eval('WowCompanionDB.lay["%s"].hud.dy' % WHO) == round(-350 + 360) and abs(P2.y + 300) < 1e-9,
      "j. once only: a later old height moves nothing")
check(lua_errors(L2, errs2) == [], "j. clean", lua_errors(L2, errs2)[:3])

# ================================================================ k. the owners place themselves by the layout
L3, errs3 = world('WowCompanionDB = { layV = 1, lay = { ["%s"] = { nbar = { dx = 100, dy = -50, s = 120 }, chat = { dx = -40, dy = -100 }, '
                  'party = { dx = 30, dy = 60, s = 80 }, compass = { dx = 200, dy = 10, s = 130 } } } }\n' % WHO)
uh3 = L3.eval('UIParent:GetHeight()')
k3 = uh3 / 1080
NB = L3.eval('WowCompanion.nbar')
L3.execute('pcall(WowCompanion.nbar.build); pcall(WowCompanion.nbar.place)')
f = NB.f
check(f is not None and abs(f._scale - NB.scale * 1.2) < 1e-9, "k. the Nintendo bar: the stock scale x 120 %", f and (f._scale, NB.scale))
if f is not None and f._pts is not None:
    sc = NB.scale * 1.2
    cxu = f._pts[4] * sc
    want = (NB.lastC[1] * NB.scale + 100 * k3) if NB.at == "stock" else 100 * k3
    check(f._pts[1] == ("CENTER" if NB.at == "stock" else "BOTTOM") and abs(cxu - want) < 1e-6,
          "k. ... at its spot (%s) + 100 px right (UI units %s)" % (NB.at, cxu), (cxu, want))
C3 = L3.eval('WowCompanion.compass')
L3.execute('pcall(WowCompanion.compass.build)')
cf = C3.frame
check(cf is not None and abs(cf._scale - 1.3) < 1e-9 and abs(cf._pts[4] * 1.3 - 200 * k3) < 1e-6 and abs(cf._pts[5] * 1.3 + (4 + 10) * k3) < 1e-6,
      "k. the compass: 130 %, (200, 4 + 10) from the top-centre", cf and (cf._scale, cf._pts[4], cf._pts[5]))
UF3 = L3.eval('WowCompanion.uf')
L3.execute('WowCompanionDB.lay["%s"].target = { dx = -100, dy = 400, s = 110 }; pcall(WowCompanion.uf.placeTarget)' % WHO)
tb = UF3.tb.f
if tb is not None:
    check(abs(tb._scale - 1.1) < 1e-9 and tb._pts[1] == "TOPLEFT", "k. the target bar on its own: 110 %, its top-left", (tb._scale, tb._pts[1]))
else:
    ts = L3.eval('(function() local x, t, s = WowCompanion.uf.targetSpot(); return { x = x, t = t, s = s } end)()')
    check(ts.s is not None and abs(ts.s - 1.1) < 1e-9 and abs(ts.t - (4 + 76 + 6 + 400) * k3) < 0.01,
          "k. the target bar on its own: 110 %, under the compass's default + 400", (ts.x, ts.t, ts.s))
L3.execute('WowCompanionDB.lay["%s"].target = nil' % WHO)
ts = L3.eval('(function() local x, t, s = WowCompanion.uf.targetSpot(WowCompanion.compass.frame); return { x = x, t = t, s = s } end)()')
check(ts.s is None or ts.s == 1, "k. no target entry: no scale of its own (it follows the compass)", (ts.x, ts.t, ts.s))
gp = L3.eval('WowCompanion.prd.partyGeo()')
check(gp is not None, "k. the HUD's party geometry still answers (prd15, with the HUD's x)")
check(lua_errors(L3, errs3) == [], "k. clean", lua_errors(L3, errs3)[:3])

# ================================================================ m. hub58: only the pieces that are on; Polestar's arrow and guide
# The user, 2026-11-17: "the edit mode should include any UI element that is currently turned on, including the wow UI and polestar.
# the polestar ui should be broken into 2, one for the arrow and one for the actual guide." A Polestar of Polestar 0.2.5's shape: its
# global (Core/Init.lua:11 `Polestar = ns`), ns.Get over PolestarDB.settings (Init.lua:395), its arrow frame PolestarNavArrow placed by
# Nav.arrow:OnSettings's applyPosition (Nav/Arrow.lua:550-558, 602-611), its guide window PolestarGuideFrame placed by
# UI.GuideFrame.ApplyPosition (GuideFrame.lua:1667-1678), its own save of a place (ns.Set + SETTING_CHANGED's re-place).
POLESTAR = r'''
PolestarDB = { schema = 1, settings = {} }
Polestar = { Nav = { arrow = {} }, UI = { GuideFrame = {} } }
local function get(path)
    local node = PolestarDB.settings
    for k in path:gmatch("[^.]+") do if type(node) ~= "table" then return nil end; node = node[k] end
    return node
end
Polestar.Get = get
local function mk(name, w, h)
    local f = CreateFrame("Frame", name, UIParent)
    f.GetWidth = function() return w end
    f.GetHeight = function(self) return self._hh or h end
    f.GetPoint = function(self, i) local p = self._pts; if not p then return nil end; return p[1], p[2], p[3], p[4], p[5] end
    return f
end
PSA = mk("PolestarNavArrow", 200, 116); PSA:Hide()
PSG = mk("PolestarGuideFrame", 340, 260)
PSG:SetScale(1.25)
PSN = { arrow = 0, guide = 0 }
function Polestar.Nav.arrow:OnSettings()
    PSN.arrow = PSN.arrow + 1
    PSA:ClearAllPoints()
    local pos = get("nav.arrowPos")
    if type(pos) == "table" then PSA:SetPoint(pos.point, UIParent, pos.relPoint or pos.point, pos.x, pos.y)
    else PSA:SetPoint("TOP", UIParent, "TOP", 0, -140) end
end
function Polestar.UI.GuideFrame.ApplyPosition()
    PSN.guide = PSN.guide + 1
    local pos = get("ui.pos")
    PSG:ClearAllPoints()
    if type(pos) == "table" then PSG:SetPoint("TOPLEFT", UIParent, "BOTTOMLEFT", pos[1], pos[2])
    else PSG:SetPoint("TOPRIGHT", UIParent, "TOPRIGHT", -64, -240) end
end
function Polestar.UI.GuideFrame.ApplyScale() end
-- Polestar's own ns.Set of a place (its drag's save) and what its SETTING_CHANGED does with it
function PSSET(path, v)
    local keys = {}
    for k in path:gmatch("[^.]+") do keys[#keys + 1] = k end
    local node = PolestarDB.settings
    for i = 1, #keys - 1 do node[keys[i]] = node[keys[i]] or {}; node = node[keys[i]] end
    node[keys[#keys]] = v
    if path:sub(1, 4) == "nav." then Polestar.Nav.arrow:OnSettings() else Polestar.UI.GuideFrame.ApplyPosition() end
end
Polestar.Nav.arrow:OnSettings()
Polestar.UI.GuideFrame.ApplyPosition()
'''


def psdb(L):
    return L.eval('(function() local function ser(v) if type(v) ~= "table" then return tostring(v) end; local ks = {}; '
                  'for k in pairs(v) do ks[#ks + 1] = tostring(k) end; table.sort(ks); local o = {}; for _, k in ipairs(ks) do '
                  'local kk = tonumber(k) or k; o[#o + 1] = k .. "=" .. ser(v[kk]) end; return "{" .. table.concat(o, ",") .. "}" end; '
                  'return ser(PolestarDB) end)()')


def pts(L, name):
    p = L.eval('%s._pts' % name)
    return None if p is None else (p[1], p[3], p[4], p[5])


L4, errs4 = world(POLESTAR)
uh4 = L4.eval('UIParent:GetHeight()')
k4 = 1080 / uh4
db0 = psdb(L4)
L4.execute('tick(1)')
P4 = L4.eval('WowCompanion.psLay')
check(P4.hooked.psarrow is True and P4.hooked.psguide is True and P4.fnHooked.arrow is True and P4.fnHooked.ApplyPosition is True,
      "m. Polestar loaded: both frames found and hooked, its own placing calls post-hooked (Nav.arrow:OnSettings, GuideFrame.ApplyPosition)")
check(pts(L4, "PSA") == ("TOP", "TOP", 0, -140) and pts(L4, "PSG") == ("TOPRIGHT", "TOPRIGHT", -64, -240),
      "m. no entry: nothing of ours on either frame (Polestar's own places)", (pts(L4, "PSA"), pts(L4, "PSG")))
hold(L4)
d = fresh(L4)
check(piece(d, "psguide")["on"] is True and piece(d, "psarrow")["on"] is False,
      "m. the guide window shown: listed; the arrow hidden (no waypoint): not listed", (piece(d, "psguide")["on"], piece(d, "psarrow")["on"]))
g = piece(d, "psguide")
W4 = d["w"]
check(abs(g["ax"] - (W4 - 64 * 1.25 * k4)) < 0.05 and abs(g["ay"] - 240 * 1.25 * k4) < 0.05 and abs(g["w"] - 340 * 1.25 * k4) < 0.05
      and abs(g["x"] - (g["ax"] - g["w"])) < 0.05 and abs(g["y"] - g["ay"]) < 0.05 and g["sn"] == 100 and g["sx"] == 100 and g.get("ps") is True,
      "m. the guide's piece: its pin = Polestar's own TOPRIGHT corner at its scale 1.25 (%.1f, %.1f), its rect left of it, moved only"
      % (g["ax"], g["ay"]), g)
L4.execute('PSA:Show(); tick(0.25)')
d = last_full(L4)
check(d is not None and piece(d, "psarrow")["on"] is True and piece(d, "psarrow")["vis"] is True,
      "m. the arrow shows: a NEW document at once lists it (the list follows live)", d and piece(d, "psarrow"))
a = piece(d, "psarrow")
check(abs(a["ax"] - W4 / 2) < 0.05 and abs(a["ay"] - 140 * k4) < 0.05 and abs(a["x"] - (W4 / 2 - 100 * k4)) < 0.05,
      "m. the arrow's piece: its pin Polestar's TOP 0, -140 (%.1f, %.1f), its rect centred under it" % (a["ax"], a["ay"]), a)
fixture(L4, "lay_polestar")
# our hide switch (hub31: Polestar's windows hidden on the top screen): neither listed
L4.execute('WowCompanion.hub.psSet(true); tick(0.25)')
d = last_full(L4)
check(piece(d, "psarrow")["on"] is False and piece(d, "psguide")["on"] is False, "m. our 'hide Polestar' switch on: neither listed")
L4.execute('WowCompanion.hub.psSet(false); tick(0.25)')
check(piece(last_full(L4), "psguide")["on"] is True, "m. ... off again: listed again")
cell(L4, 37)
cell(L4, 7)
check(last(L4)["sel"] == "psguide", "m. select the guide (7)")
catch(L4, 400, 300)
ease(L4)
e = L4.eval('WowCompanion.lay.view.psguide')
gx = pts(L4, "PSG")
want_dx, want_dy = round(400 - g["ax"]), round(300 - g["ay"])
check(e is not None and e.dx == want_dx and e.dy == want_dy, "m. a spot moves the guide's pin there (dx %s, dy %s)" % (e and e.dx, e and e.dy))
check(gx is not None and gx[0] == "TOPRIGHT" and gx[1] == "TOPRIGHT" and abs(gx[2] - (-64 + want_dx / k4 / 1.25)) < 1e-6
      and abs(gx[3] - (-240 - want_dy / k4 / 1.25)) < 1e-6,
      "m. ... the frame keeps Polestar's own anchor point, our offset added in its own scaled units", gx)
cell(L4, 36)
saved = L4.eval('WowCompanionDB.lay["%s"].psguide' % WHO)
check(saved is not None and saved.dx == want_dx and saved.dy == want_dy, "m. Save: this character's entry (our own store)")
check(psdb(L4) == db0, "m. NOTHING of Polestar's saved variables written (PolestarDB as it was)", psdb(L4)[:120])
cell(L4, 39)
check(pts(L4, "PSG") == gx, "m. closed: the guide stays at the saved place")
L4.execute('Polestar.UI.GuideFrame.ApplyPosition()')
check(pts(L4, "PSG") == gx, "m. Polestar places it again itself (its ApplyPosition): our offset back on at once (the post-hook)",
      (pts(L4, "PSG"), gx))
L4.execute('PSG:Hide(); PSG:ClearAllPoints(); PSG:SetPoint("TOPRIGHT", UIParent, "TOPRIGHT", -64, -240); PSG:Show()')
check(pts(L4, "PSG") == gx, "m. re-shown: the OnShow hook puts our offset on again", pts(L4, "PSG"))
hold(L4, False)
L4.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
L4.execute('Polestar.UI.GuideFrame.ApplyPosition()')
check(pts(L4, "PSG") == gx and int(L4.eval('#VIOL')) == 0, "m. in a fight (a plain frame): placed again, no lockdown breach")
L4.execute('PSG._protected = true; PSG.IsProtected = function() return true end; COMBAT = false; Polestar.UI.GuideFrame.ApplyPosition(); COMBAT = true')
L4.execute('PSG._protected = nil; PSG:ClearAllPoints(); PSG:SetPoint("TOPRIGHT", UIParent, "TOPRIGHT", -64, -240); PSG._protected = true')
L4.execute('WowCompanion.psLay.apply("psguide")')
check(pts(L4, "PSG") == ("TOPRIGHT", "TOPRIGHT", -64, -240) and L4.eval('WowCompanion.psLay.pending.psguide') is True
      and int(L4.eval('#VIOL')) == 0, "m. a PROTECTED frame in a fight is never moved: the re-apply waits")
L4.execute('COMBAT = false; PSG._protected = nil; PSG.IsProtected = function() return false end; fire("PLAYER_REGEN_ENABLED")')
check(pts(L4, "PSG") == gx and L4.eval('WowCompanion.psLay.pending.psguide') is None, "m. ... and goes on at the fight's end")
# Polestar saves a new place ITSELF (its drag's save: ui.pos from where the frame stood): our offset goes, the frame stays there
L4.execute('local l = 500; PSSET("ui.pos", { l, 700 })')
db1 = psdb(L4)
check(L4.eval('WowCompanionDB.lay["%s"].psguide' % WHO) is None and pts(L4, "PSG") == ("TOPLEFT", "BOTTOMLEFT", 500, 700)
      and L4.eval('WowCompanion.psLay.drops') == 1,
      "m. Polestar's own save of a place (its drag, grip, scale, Reset): our entry dropped, the frame where Polestar has it, no fight",
      (pts(L4, "PSG"), L4.eval('WowCompanion.psLay.drops')))
# the arrow: moved, then RESET (34) puts Polestar's own place back
hold(L4)
cell(L4, 37)
cell(L4, 6)
catch(L4, W4 / 2 + 200, 400)
ease(L4)
ax_ = pts(L4, "PSA")
check(ax_ is not None and ax_[0] == "TOP" and abs(ax_[2] - 200 / k4) < 1e-6, "m. the arrow (6) moved by a spot", ax_)
cell(L4, 34)
ease(L4)
check(pts(L4, "PSA") == ("TOP", "TOP", 0, -140) and piece(fresh(L4), "psarrow")["d"] is True,
      "m. Reset (34) for the arrow: Polestar's own place back", pts(L4, "PSA"))
cell(L4, 39)
# the hide switch (hub31) on: neither listed; a piece no longer listed is let go and never selected
cell(L4, 37)
cell(L4, 7)
L4.execute('PSA:Hide(); tick(0.25)')
d = last_full(L4)
check(piece(d, "psarrow")["on"] is False, "m. the arrow hidden again: unlisted at once", piece(d, "psarrow"))
L4.execute('PSG:Hide(); tick(0.25)')
d = last_full(L4)
check(piece(d, "psguide")["on"] is False and d.get("sel") is None, "m. the guide hidden while selected: unlisted and let go", (piece(d, "psguide")["on"], d.get("sel")))
cell(L4, 7)
check(last(L4).get("sel") is None, "m. an unlisted piece is never selected (7 refused)")
L4.execute('PSA:Show(); WowCompanion.uf.set("target", false); tick(0.25)')
d = last_full(L4)
check(piece(d, "target")["on"] is False and piece(d, "psarrow")["on"] is True and piece(d, "psguide")["on"] is False,
      "m. our target bar turned off: unlisted (its switch), the arrow back, the guide still hidden", [(p["id"], p["on"]) for p in d["p"]])
fixture(L4, "lay_off")
cell(L4, 39)
check(psdb(L4) == db1, "m. after Polestar's own save: PolestarDB written by nobody but Polestar (as it left it)")
check(lua_errors(L4, errs4) == [] and int(L4.eval('#VIOL')) == 0 and int(L4.eval('#SPY')) == 0, "m. clean",
      (lua_errors(L4, errs4)[:3], int(L4.eval('#VIOL')), int(L4.eval('#SPY'))))

# ================================================================ n. hub59: the game's own UI as pieces ("our own offsets")
# The user's stage-2 picks, 2026-11-17: "Our own offsets" (nothing written into the game), "Every one that's on" (unit frames and bars
# first, only what is shown), "Move them out of combat" (protected frames listed; a change in a fight queued to its end), "Move and
# resize"; Reset per piece and Reset game UI; the damage meter and the Cooldown Manager too. Fake game frames of the client's shape: an
# Edit Mode system keeps its original SetPoint / ClearAllPoints / SetScale as ...Base (editmodesystemtemplates.lua:9-17) and its plain
# ones are the manager's overrides (a spy here: never called); the controller bar is no system (its plain methods); the Edit Mode
# manager re-places every system at its layout update and at its exit (the game's own secure code: IN_SECURE).
QUOTE2 = '"the edit mode should include any UI element that is currently turned on, including the wow UI and polestar."'
GSRC = TXT["hub"][TXT["hub"].index("-- ==================================================================== hub59 THE GAME'S OWN UI"):
                  TXT["hub"].index("-- ==================================================================== hub55 THE TOP-SCREEN LAYOUT EDITOR")]
gcode = "\n".join(re.sub(r'--.*$', '', ln) for ln in GSRC.splitlines())
gbad = [w for w in ("C_EditMode", "SaveLayouts", "SetActiveLayout", "ReloadUI", "SetCVar", "SetBinding", "ShowUIPanel", "HideUIPanel",
                    ":SetPoint(", ":ClearAllPoints(", ":SetScale(", ":Show(", ":Hide(", "SetUserPlaced") if w in gcode]
check(gbad == [], "n. the game-frame block never writes the game's layouts, CVars, bindings, windows; never a plain SetPoint / Show / Hide", gbad)
check(int(re.search(r'local HV = "hub(\d+)"', TXT["hub"]).group(1)) >= 59 and QUOTE2 in TXT["hub"], "n. Hub hub59 (or later: hub60 the 11-17 merge), its note quoting the user")

GAMEPRE = r'''
C_EditMode = { SaveLayouts = function() EMSPY[#EMSPY + 1] = "SaveLayouts" end, SetActiveLayout = function() EMSPY[#EMSPY + 1] = "SetActiveLayout" end }
EMSPY, PLAIN = {}, {}
'''
GAMEPOST = r'''
local UW, UH = UIParent:GetWidth(), UIParent:GetHeight()
local PTS = { TOPLEFT = {0, 1}, TOP = {0.5, 1}, TOPRIGHT = {1, 1}, LEFT = {0, 0.5}, CENTER = {0.5, 0.5}, RIGHT = {1, 0.5},
              BOTTOMLEFT = {0, 0}, BOTTOM = {0.5, 0}, BOTTOMRIGHT = {1, 0} }
GDEF = {}
function geo(name, w, h, system, protected, def)
    local f = rawget(_G, name)
    if type(f) ~= "table" then f = CreateFrame("Frame", name, UIParent) end
    if getmetatable(f) ~= Frame then setmetatable(f, Frame) end
    f._P, f._w, f._h, f._scale, f._shown = {}, w, h, 1, true
    f.GetWidth = function(self) return self._w end
    f.GetHeight = function(self) return self._h end
    f.GetNumPoints = function(self) return #self._P end
    f.GetPoint = function(self, i) local p = self._P[i or 1]; if p then return p[1], p[2], p[3], p[4], p[5] end end
    f.GetScale = function(self) return self._scale end
    f.GetEffectiveScale = function(self) return self._scale end
    f.GetObjectType = function() return "Frame" end
    local function pos(self)
        local p = self._P[1]; local s = self._scale; local r = PTS[p[3]]; local q = PTS[p[1]]
        local px, py = UW * r[1] + p[4] * s, UH * r[2] + p[5] * s
        return (px - self._w * s * q[1]) / s, (py - self._h * s * q[2] + self._h * s) / s
    end
    f.GetLeft = function(self) if not self._P[1] then return nil end; local l = pos(self); return l end
    f.GetTop = function(self) if not self._P[1] then return nil end; local _, t = pos(self); return t end
    local function guard(what) if protected then blocked(what .. " " .. name) end end
    local function sp(self, p, rel, rp, x, y) guard("SetPoint"); self._P[#self._P + 1] = { p, rel or UIParent, rp or p, x or 0, y or 0 } end
    local function cl(self) guard("ClearAllPoints"); self._P = {} end
    local function sc(self, v) guard("SetScale"); self._scale = v end
    if system then
        f.SetPointBase, f.ClearAllPointsBase, f.SetScaleBase = sp, cl, sc
        f.SetPoint = function(self, ...) PLAIN[#PLAIN + 1] = name; sp(self, ...) end
        f.ClearAllPoints = function(self) PLAIN[#PLAIN + 1] = name; cl(self) end
        f.SetScale = function(self, v) PLAIN[#PLAIN + 1] = name; sc(self, v) end
    else
        f.SetPoint, f.ClearAllPoints, f.SetScale = sp, cl, sc
    end
    f.IsProtected = function() return protected and true or false end
    f._system = system
    GDEF[name] = def
    local was = IN_SECURE; IN_SECURE = true
    sp(f, def[1], UIParent, def[2], def[3], def[4])
    IN_SECURE = was
    return f
end
geo("PlayerFrame", 232, 100, true, true, { "TOPLEFT", "TOPLEFT", 268, -850 })
geo("TargetFrame", 232, 100, true, true, { "TOPLEFT", "TOPLEFT", 1100, -850 }):Hide()
geo("GamepadMainActionBarFrame", 600, 140, false, true, { "BOTTOM", "BOTTOM", 0, 110 })
geo("MinimapCluster", 200, 220, true, false, { "TOPRIGHT", "TOPRIGHT", -10, -10 })
geo("BuffFrame", 300, 60, true, false, { "TOPRIGHT", "TOPRIGHT", -240, -14 })
geo("DamageMeter", 400, 200, true, false, { "TOPLEFT", "TOPLEFT", 20, -300 })
geo("EssentialCooldownViewer", 300, 50, true, false, { "BOTTOM", "BOTTOM", 0, 300 }):SetAlpha(0)
EditModeManagerFrame = mkwin("EditModeManagerFrame", false)
-- the game's own layout update and exit: every system back at its layout's place, at its own scale (secure code)
function EditModeManagerFrame:UpdateLayoutInfo()
    local was = IN_SECURE; IN_SECURE = true
    for name, d in pairs(GDEF) do
        local f = _G[name]
        if f._system then f:ClearAllPointsBase(); f:SetScaleBase(1); f:SetPointBase(d[1], UIParent, d[2], d[3], d[4]) end
    end
    IN_SECURE = was
end
function EditModeManagerFrame:ExitEditMode() self:Hide(); self:UpdateLayoutInfo() end
'''


def gpts(L, name):
    p = L.eval('%s._P[1]' % name)
    return None if p is None else (p[1], p[3], round(p[4], 4), round(p[5], 4))


L5, errs5 = world(GAMEPRE)
L5.execute(GAMEPOST)
L5.execute('tick(0.5); advance(0.1)')
G5 = L5.eval('WowCompanion.gameLay')
uh5 = L5.eval('UIParent:GetHeight()')
k5 = 1080 / uh5
hold(L5)
L5.execute('tick(0.25)')
d = fresh(L5)
gp = [p for p in d["p"] if p.get("g")]
listed = [(p["id"], p.get("c"), p.get("k")) for p in gp if p["on"]]
check(listed == [("g.PlayerFrame", 8, "u"), ("g.GamepadMainActionBarFrame", 9, "b"), ("g.MinimapCluster", 10, "o"),
                 ("g.BuffFrame", 11, "o"), ("g.DamageMeter", 12, "o")],
      "n. the game's frames that are shown, unit frames and bars first, columns 8.. in order (the damage meter too)", listed)
off = [p["id"] for p in gp if not p["on"]]
check("g.TargetFrame" in off and "g.EssentialCooldownViewer" in off and piece(d, "g.TargetFrame").get("c") is None,
      "n. a hidden frame (the target frame) and one at alpha 0 (a Cooldown Manager bar our switch hides) not listed, no column", off)
pf = piece(d, "g.PlayerFrame")
check(pf["pr"] is True and piece(d, "g.MinimapCluster").get("pr") in (False, None) and pf["sn"] == 50 and pf["sx"] == 200,
      "n. the player frame says protected; the minimap not; sizes 50-200 %", (pf.get("pr"), piece(d, "g.MinimapCluster").get("pr")))
check(abs(pf["ax"] - 268 * k5) < 0.05 and abs(pf["ay"] - 850 * k5) < 0.05 and abs(pf["w"] - 232 * k5) < 0.05,
      "n. its pin = its own TOPLEFT point where the game put it (%.1f, %.1f)" % (pf["ax"], pf["ay"]))
check(list(L5.eval('PLAIN').values()) == [], "n. nothing touched yet (no entry: never moved)")
# open, the player frame (8), a spot, a size
cell(L5, 37)
cell(L5, 8)
check(last(L5)["sel"] == "g.PlayerFrame", "n. select the player frame (8)")
catch(L5, 500, 700)
ease(L5)
cell(L5, 33)
ease(L5)
dx, dy = round(500 - pf["ax"]), round(700 - pf["ay"])
p1 = gpts(L5, "PlayerFrame")
want = ("TOPLEFT", "TOPLEFT", round((268 + dx / k5) / 1.1, 4), round((-850 - dy / k5) / 1.1, 4))
check(p1 == want and abs(L5.eval('PlayerFrame._scale') - 1.1) < 1e-9,
      "n. moved and 110 %: its own point + our offset, its own scale x 1.1, through the Base methods", (p1, want))
check(list(L5.eval('PLAIN').values()) == [] and int(L5.eval('#VIOL')) == 0,
      "n. never the plain SetPoint / ClearAllPoints / SetScale of a system (no manager taint), no breach")
cell(L5, 10)
catch(L5, 1500, 200)
ease(L5)
cell(L5, 9)
catch(L5, 960, 900)
ease(L5)
gb = gpts(L5, "GamepadMainActionBarFrame")
check(gb is not None and gb[0] == "BOTTOM" and gb[3] != 110, "n. the controller bar (no system: its own methods) moved", gb)
cell(L5, 36)
saved = L5.eval('WowCompanionDB.lay["%s"]' % WHO)
check(saved["g.PlayerFrame"].s == 110 and saved["g.MinimapCluster"] is not None and saved["g.GamepadMainActionBarFrame"] is not None,
      "n. Save: the offsets in our own store, per character")
fixture(L5, "lay_game")
cell(L5, 39)
check(gpts(L5, "PlayerFrame") == want, "n. closed: it stays")
check(list(L5.eval('EMSPY').values()) == [], "n. nothing written into the game (no C_EditMode call)")
# the game's own layout update re-places it: ours goes back on
L5.execute('EditModeManagerFrame:UpdateLayoutInfo()')
check(gpts(L5, "PlayerFrame") == ("TOPLEFT", "TOPLEFT", 268, -850), "n. (the game's layout update put it back at its place)")
L5.execute('advance(0.01)')
check(gpts(L5, "PlayerFrame") == want and abs(L5.eval('PlayerFrame._scale') - 1.1) < 1e-9,
      "n. a layout update: our offset and size back on at once (post-hook, deferred a frame)", gpts(L5, "PlayerFrame"))
# the game's Edit Mode: nothing moved while it shows; everything again when it closes
L5.execute('EditModeManagerFrame:Show(); EditModeManagerFrame:UpdateLayoutInfo(); advance(0.01); tick(0.5)')
check(gpts(L5, "PlayerFrame") == ("TOPLEFT", "TOPLEFT", 268, -850), "n. the game's Edit Mode shown: we leave its frames alone")
L5.execute('EditModeManagerFrame:ExitEditMode(); advance(0.01)')
check(gpts(L5, "PlayerFrame") == want, "n. its Edit Mode closing: ours back on")
# a re-place by another path (a managed-frame layout): the tick finds it
L5.execute('IN_SECURE = true; PlayerFrame:ClearAllPointsBase(); PlayerFrame:SetPointBase("TOPLEFT", UIParent, "TOPLEFT", 300, -800); IN_SECURE = false')
L5.execute('tick(0.5)')
want2 = ("TOPLEFT", "TOPLEFT", round((300 + dx / k5) / 1.1, 4), round((-800 - dy / k5) / 1.1, 4))
check(gpts(L5, "PlayerFrame") == want2, "n. any other re-place: its new place read, our offset on it (the tick)", gpts(L5, "PlayerFrame"))
# combat: a protected frame queued, a plain one at once
viol = int(L5.eval('#VIOL'))
L5.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
L5.execute('EditModeManagerFrame:UpdateLayoutInfo(); advance(0.01); tick(0.5)')
check(gpts(L5, "PlayerFrame") == ("TOPLEFT", "TOPLEFT", 268, -850) and int(L5.eval('#VIOL')) == viol,
      "n. in a fight the protected player frame is NOT moved (no breach)")
mc = gpts(L5, "MinimapCluster")
check(mc is not None and mc[2] != -10, "n. ... the plain minimap is (ours on at once)", mc)
L5.execute('tick(0.25)')
dq = last_full(L5)
check(dq["queued"] == 1 and piece(dq, "g.PlayerFrame")["q"] is True, "n. the document: one change queued (the app's line)",
      (dq.get("queued"), piece(dq, "g.PlayerFrame").get("q")))
fixture(L5, "lay_queued")
L5.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(0.01)')
check(gpts(L5, "PlayerFrame") == want, "n. the fight's end: the queued change goes on")
L5.execute('tick(0.25)')
check(last_full(L5)["queued"] == 0, "n. ... nothing queued now")
# Reset per piece (the controller bar), then Reset game UI (29) + Save: every game entry gone, every frame at the game's place
cell(L5, 37)
cell(L5, 9)
cell(L5, 34)
ease(L5)
check(gpts(L5, "GamepadMainActionBarFrame") == ("BOTTOM", "BOTTOM", 0, 110), "n. Reset (34): the controller bar at the game's place")
L5.execute('WowCompanionDB.lay["%s"]["g.FocusFrame"] = { dx = 5, dy = 5, s = 100 }' % WHO)   # (a frame not shown now: still dropped)
check(fresh(L5)["gset"] is True, "n. gset: offsets of the game's frames exist")
L5.execute('advance(2.1)')                                        # (hub64: past HS_GRACE_S of the open)
cell(L5, 29)
ease(L5)
check(gpts(L5, "PlayerFrame") == ("TOPLEFT", "TOPLEFT", 268, -850) and L5.eval('PlayerFrame._scale') == 1
      and gpts(L5, "MinimapCluster") == ("TOPRIGHT", "TOPRIGHT", -10, -10), "n. Reset game UI (29): every frame at the game's place (the draft)")
check(last(L5)["dirty"] is True, "n. ... unsaved until Save")
cell(L5, 36)
mine = L5.eval('WowCompanionDB.lay["%s"]' % WHO)
gk = [k for k in (mine.keys() if mine is not None else []) if str(k).startswith("g.")]
check(gk == [] and fresh(L5)["gset"] is False, "n. Save: every game entry gone, the hidden frame's too", gk)
cell(L5, 39)
check(list(L5.eval('PLAIN').values()) == [] and list(L5.eval('EMSPY').values()) == [], "n. through it all: no plain system method, no C_EditMode call")
check(lua_errors(L5, errs5) == [] and int(L5.eval('#VIOL')) == 0 and int(L5.eval('#SPY')) == 0, "n. clean",
      (lua_errors(L5, errs5)[:3], int(L5.eval('#VIOL')), int(L5.eval('#SPY'))))

# ================================================================ o. hub61: the damage meter and the Cooldown Manager (Settings > Top)
# The user, 2026-11-17: "DPS meters and also the cooldown manager ... we need to make sure that its consistent with the other picture
# options". The game's DamageMeter (its session window a child) and the four CooldownViewer frames, faked in the client's shape: Edit Mode
# systems whose opacity setting puts their alpha back (UpdateSystemSettingOpacity); the viewers' mouse is protected in a fight here (the
# strict world's rule for the secure half). Proven: alpha 0 + EnableMouse(false) only (never Hide / Show, never a CVar written); the
# player's press on the "lay" row while the editor is closed (and NOT while it is open); per character in WowCompanionDB.meters; Dynamic
# up in a fight and down LINGER_S after it (cancelled by a new fight); a press in a fight held to its end; Edit Mode's opacity and its
# layout update re-asserted; the game's Edit Mode shown = everything shown; the game's options only read (the hub document's mt).
QUOTE3 = '"DPS meters and also the cooldown manager ... we need to make sure that its consistent with the other picture options"'
MSRC = TXT["hub"][TXT["hub"].index("-- ==================================================================== hub61 THE DAMAGE METER"):
                  TXT["hub"].index("-- ==================================================================== hub55 THE TOP-SCREEN LAYOUT EDITOR")]
mcode = "\n".join(re.sub(r'--.*$', '', ln) for ln in MSRC.splitlines())
mbad = [w for w in (":Hide(", ":Show(", "SetShown", "SetCVar", "C_CVar.SetCVar", "CooldownViewerSettings", "ShowUIPanel", "HideUIPanel",
                    "EnterEditMode", "ExitEditMode", "OpenToCategory", "Settings.Open", "C_EditMode", "SetBinding") if w in mcode]
check(mbad == [], "o. the meters' block never hides / shows a frame, writes a CVar, opens the game's Settings, Cooldown settings or Edit Mode", mbad)
check(int(re.search(r'local HV = "hub(\d+)"', TXT["hub"]).group(1)) >= 61 and TXT["hub"].count(QUOTE3) >= 2,
      "o. Hub hub61+, its notes (the version line and the block) quote the user")

METERPRE = r'''
CVARS = { damageMeterEnabled = true, cooldownViewerEnabled = true }
C_CVar = { GetCVarBool = function(n) return CVARS[n] end }
DMAV, CDAV = true, true
C_DamageMeter = { IsDamageMeterAvailable = function() return DMAV end }
C_CooldownViewer = { IsCooldownViewerAvailable = function() return CDAV end }
SHOWHIDE = {}
function mkmeter(name, alpha, secure)
    local f = CreateFrame("Frame", name, UIParent)
    f._alpha, f._mouse, f._sec = alpha or 1, true, secure
    f.IsMouseEnabled = function(self) return self._mouse end
    f.EnableMouse = function(self, on)
        if COMBAT and self._sec then VIOL[#VIOL + 1] = "EnableMouse " .. name; error("ADDON_ACTION_BLOCKED EnableMouse") end
        self._mouse = on and true or false
    end
    f.Show = function(self) SHOWHIDE[#SHOWHIDE + 1] = "Show " .. name end
    f.Hide = function(self) SHOWHIDE[#SHOWHIDE + 1] = "Hide " .. name end
    f.SetShown = function(self) SHOWHIDE[#SHOWHIDE + 1] = "SetShown " .. name end
    return f
end
mkmeter("DamageMeter")
mkmeter("DamageMeterSessionWindow1")
DamageMeter.GetChildren = function() return DamageMeterSessionWindow1 end
for i, n in ipairs({ "EssentialCooldownViewer", "UtilityCooldownViewer", "BuffIconCooldownViewer", "BuffBarCooldownViewer" }) do
    local v = mkmeter(n, i == 2 and 0.6 or 1, true)
    v.opacity = v._alpha
    -- the game's own refresh (editmodesystemtemplates: self:SetAlpha(opacity / 100))
    v.UpdateSystemSettingOpacity = function(self) self:SetAlpha(self.opacity) end
end
'''
VIEWERS = ["EssentialCooldownViewer", "UtilityCooldownViewer", "BuffIconCooldownViewer", "BuffBarCooldownViewer"]


def mt(L):
    d = [x for x in wowstub.docs(L, 0, "hub") if isinstance(x.get("hub"), dict)]
    return d[-1]["hub"].get("mt") if d else None


def mfresh(L):
    L.execute('WowCompanion.meters.emit()')
    return mt(L)


def alpha(L, n):
    return round(float(L.eval('%s:GetAlpha()' % n)), 3)


def mouse(L, n):
    return L.eval('%s._mouse' % n)


L6, errs6 = world(METERPRE)
L6.execute('WowCompanion.meters.emit()')
d = mt(L6)
check(d is not None and d.get("v") == "mt1" and d.get("d") == "on" and d.get("dset") is None and d.get("c") is True and d.get("p") == 15
      and d.get("dok") is True and d.get("dav") is True and d.get("df") is True and d.get("cok") is True and d.get("cav") is True
      and d.get("cf") is True and d.get("cols", {}).get("PARTS") == 8,
      "o. the hub document's mt at login: nothing chosen (the game's own), the options and frames read", d)
check(all(alpha(L6, n) == (0.6 if n == "UtilityCooldownViewer" else 1) for n in ["DamageMeter"] + VIEWERS) and mouse(L6, "DamageMeter") is True,
      "o. nothing chosen: no frame touched (leave no trace)")
# the layout editor OPEN: cell 0 selects its first piece, the meters unchanged
hold(L6)
cell(L6, 37)
check(last(L6)["open"] is True, "o. (the editor open)")
cell(L6, 0)
check(alpha(L6, "DamageMeter") == 1 and mfresh(L6).get("dset") is None, "o. editor open: column 0 is its piece select, the meters unchanged")
cell(L6, 39)
check(last(L6)["open"] is False, "o. (the editor closed)")
# hub62 (the review, 11-17): a select the app sent on its last "open" document, arriving just after a close, is never a meter press
cell(L6, 0)
check(alpha(L6, "DamageMeter") == 1 and mfresh(L6).get("dset") is None and last(L6).get("mtDrop") == 1,
      "o. hub62: column 0 at once after the app's Close: dropped (mtDrop), the meters unchanged", last(L6).get("mtDrop"))
# the add-on's OWN close (the game's Edit Mode opening; the Settings room gone): the same grace
for how, close in (("the game's Edit Mode", 'EditModeManagerFrame = mkwin("EditModeManagerFrame", true); tick(0.25)'),
                   ("the room let go", 'local c = WowCompanion.capture; RAWSTATE6 = c.state; c.state = function() return { holder = "pad:home" } end; '
                                       'for _ = 1, 6 do tick(0.25); advance(0.25) end')):
    L6.execute('advance(3.1)')
    cell(L6, 37)
    check(last(L6)["open"] is True, "o. (open again: %s)" % how)
    L6.execute(close)
    check(last(L6)["open"] is False, "o. (closed by the add-on: %s)" % how, last(L6).get("why"))
    if how == "the room let go":
        L6.execute('WowCompanion.capture.state = RAWSTATE6')
    else:
        L6.execute('EditModeManagerFrame:Hide(); advance(0.01)')
    for col in (0, 2, 3, 8 + 5):                                       # the late selects: Off, Dynamic, CDM Off, a parts mask
        cell(L6, col)
    d = mfresh(L6)
    check(alpha(L6, "DamageMeter") == 1 and [alpha(L6, n) for n in VIEWERS] == [1, 0.6, 1, 1] and d.get("dset") is None
          and d.get("cset") is None and d.get("pset") is None and L6.eval('WowCompanionDB.meters and WowCompanionDB.meters["%s"]' % WHO) is None,
          "o. hub62: late selects after the add-on's own close (%s): all dropped, nothing saved, nothing hidden" % how, d)
L6.execute('advance(3.1)')
# Damage meter Off / On (the grace over: the app's tap again, GameUi.mtStep's 1.5 s)
check(cell(L6, 0), "o. the lay row's cell 0 is on the pad while the Settings room holds")
check(alpha(L6, "DamageMeter") == 0 and mouse(L6, "DamageMeter") is False and mouse(L6, "DamageMeterSessionWindow1") is False,
      "o. Off (0): alpha 0, the mouse off (its session window's too)", (alpha(L6, "DamageMeter"), mouse(L6, "DamageMeter")))
st = L6.eval('WowCompanionDB.meters["%s"]' % WHO)
check(st is not None and st.d == "off", "o. ... kept per character in WowCompanionDB.meters[name-realm]")
d = mfresh(L6)
check(d.get("d") == "off" and d.get("dset") is True and d.get("n") == 1, "o. ... the document says so", d)
cell(L6, 1)
check(alpha(L6, "DamageMeter") == 1 and mouse(L6, "DamageMeter") is True and mouse(L6, "DamageMeterSessionWindow1") is True,
      "o. On (1): its alpha and mouse back")
# Dynamic: a fight and LINGER_S after it
cell(L6, 2)
check(alpha(L6, "DamageMeter") == 0 and mouse(L6, "DamageMeter") is False, "o. Dynamic (2) out of a fight: hidden")
viol6 = int(L6.eval('#VIOL'))
L6.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.1)')
check(alpha(L6, "DamageMeter") == 1 and mouse(L6, "DamageMeter") is False, "o. a fight starts: shown (alpha), the mouse stays off")
L6.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(14)')
check(alpha(L6, "DamageMeter") == 1, "o. the fight's end: still shown for the read (14 s)")
L6.execute('advance(1.5)')
check(alpha(L6, "DamageMeter") == 0, "o. ... and hidden 15 s after it")
L6.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(1); COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(10)')
L6.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(10)')
check(alpha(L6, "DamageMeter") == 1, "o. a new fight inside the 15 s: the old linger's end does not hide it")
L6.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(10)')
check(alpha(L6, "DamageMeter") == 1, "o. ... its own 15 s count from its own end")
L6.execute('advance(6)')
check(alpha(L6, "DamageMeter") == 0 and int(L6.eval('#VIOL')) == viol6, "o. ... then hidden; no breach through the fights")
# the Cooldown Manager: off, on (each viewer's own alpha back), the parts' mask
cell(L6, 3)
check(all(alpha(L6, n) == 0 for n in VIEWERS) and all(mouse(L6, n) is False for n in VIEWERS), "o. Cooldown Manager Off (3): the four viewers at alpha 0, no mouse")
cell(L6, 4)
check([alpha(L6, n) for n in VIEWERS] == [1, 0.6, 1, 1] and all(mouse(L6, n) is True for n in VIEWERS),
      "o. On (4): each viewer's own alpha back (Utility's 60 % opacity kept)", [alpha(L6, n) for n in VIEWERS])
cell(L6, 8 + 1 + 4)
check([alpha(L6, n) for n in VIEWERS] == [1, 0, 1, 0], "o. the parts (8 + 0b0101): Essential and Buff icons shown, Utility and Buff bars hidden",
      [alpha(L6, n) for n in VIEWERS])
d = mfresh(L6)
check(d.get("p") == 5 and d.get("pset") is True and d.get("c") is True, "o. ... the document's p", d)
# Edit Mode's opacity refresh and its layout update: ours back on; the opacity it set is the one given back later
L6.execute('UtilityCooldownViewer.opacity = 0.8; UtilityCooldownViewer:UpdateSystemSettingOpacity()')
check(alpha(L6, "UtilityCooldownViewer") == 0, "o. Edit Mode's opacity refresh on a hidden viewer: 0 again at once (post-hook)")
L6.execute('BuffBarCooldownViewer:SetAlpha(1); fire("EDIT_MODE_LAYOUTS_UPDATED"); advance(0.01)')
check(alpha(L6, "BuffBarCooldownViewer") == 0, "o. EDIT_MODE_LAYOUTS_UPDATED: 0 again (deferred a frame)")
L6.execute('DamageMeter:SetAlpha(1); tick(1)')
check(alpha(L6, "DamageMeter") == 0, "o. the 1 s look: a hidden frame given its alpha back goes to 0 again")
# the game's own Edit Mode shown: everything shown while the player places it; hidden again when it closes
L6.execute('EditModeManagerFrame = mkwin("EditModeManagerFrame", false); WowCompanion.meters.emHooked = false; WowCompanion.meters.hook()')
L6.execute('EditModeManagerFrame:Show(); advance(0.01)')
check([alpha(L6, n) for n in ["DamageMeter"] + VIEWERS] == [1, 1, 0.8, 1, 1], "o. the game's Edit Mode shown: all shown (Utility at its new 80 %)",
      [alpha(L6, n) for n in ["DamageMeter"] + VIEWERS])
L6.execute('EditModeManagerFrame:Hide(); advance(0.01)')
check([alpha(L6, n) for n in ["DamageMeter"] + VIEWERS] == [0, 1, 0, 1, 0], "o. ... closed: hidden again as chosen",
      [alpha(L6, n) for n in ["DamageMeter"] + VIEWERS])
cell(L6, 8 + 15)
check([alpha(L6, n) for n in VIEWERS] == [1, 0.8, 1, 1], "o. all four parts again: Utility at the opacity Edit Mode gave it")
# a press in a fight: held to its end ("Changes when combat ends"), nothing breached
viol6 = int(L6.eval('#VIOL'))
L6.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.1)')
cell(L6, 3)
d = mfresh(L6)
check(all(alpha(L6, n) > 0 for n in VIEWERS) and d.get("q") is True and d.get("c") is False,
      "o. Off pressed in a fight: saved, held (q), the viewers as they were", d.get("q"))
L6.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(0.1)')
check(all(alpha(L6, n) == 0 for n in VIEWERS) and mfresh(L6).get("q") is None and int(L6.eval('#VIOL')) == viol6,
      "o. the fight's end: it goes on; no breach")
# the game's option off: only read and reported
L6.execute('CVARS.damageMeterEnabled = false; DMAV = false; fire("CVAR_UPDATE")')
d = mfresh(L6)
check(d.get("dok") is False and d.get("dav") is False and d.get("d") == "dyn", "o. the game's option off: dok / dav false, the choice kept", d)
check(list(L6.eval('SHOWHIDE').values()) == [] and int(L6.eval('#SPY')) == 0, "o. never a Hide / Show / SetShown of theirs, no spied call (SetCVar...)",
      (list(L6.eval('SHOWHIDE').values())[:3], int(L6.eval('#SPY'))))
check(lua_errors(L6, errs6) == [] and int(L6.eval('#VIOL')) == 0, "o. clean", (lua_errors(L6, errs6)[:3], int(L6.eval('#VIOL'))))
# a login with a stored choice: applied without the app (a /reload, a login on another day)
L7, errs7 = world(METERPRE + 'WowCompanionDB.meters = { ["%s"] = { d = "off", c = true, p = 3 } }\n' % WHO)
check(alpha(L7, "DamageMeter") == 0 and [alpha(L7, n) for n in VIEWERS] == [1, 0.6, 0, 0],
      "o. a login with a stored choice: the damage meter off, Buff icons / Buff bars off, at once (no app needed)",
      (alpha(L7, "DamageMeter"), [alpha(L7, n) for n in VIEWERS]))
L7.execute('WowCompanion.meters.emit()')
d = mt(L7)
check(d.get("d") == "off" and d.get("dset") is True and d.get("p") == 3, "o. ... its document", d)
L7.execute('WHO = "Other"; WowCompanion.meters.load(); WowCompanion.meters.apply()')
check(alpha(L7, "DamageMeter") == 1 and [alpha(L7, n) for n in VIEWERS] == [1, 0.6, 1, 1], "o. another character: its own (none: all back)")
check(lua_errors(L7, errs7) == [] and int(L7.eval('#VIOL')) == 0, "o. clean (stored)", lua_errors(L7, errs7)[:3])

# ================================================================ p. hub63 split-hud: the buffs, the controller UI and the XP bar apart
# The user, 2026-11-17: "we need to split out the Buffs controller UI and XP bar" -> "Both". The three rode ONE layout bit (1, "hud"),
# so they were hidden together and the layout editor (it lists a frame only while it is visible) listed them together. Now each part
# has a word on "lay" while the editor is closed (columns 24-32: on / off / ride), hub64: for this session only (never saved); unset = ride = the
# old element exactly. Proven: the old behaviour with no word; each word on its own (the editor lists the parts that are shown, each
# movable alone); the element's hidden() is the bit; a word applied in a fight with no breach; hub64: a login without the app shows every
# part (nothing saved); a word that lands just after an open is dropped uncounted (never Copy from / Reset / Smaller);
# the game re-anchoring the buffs (StatusTrayManager) or the XP bar (its layout update) gets our offset back; alpha only.
QUOTE4 = '"we need to split out the Buffs controller UI and XP bar" -> "Both"'
check(int(re.search(r'local HV = "hub(\d+)"', TXT["hub"]).group(1)) >= 63 and TXT["hub"].count(QUOTE4) >= 3,
      "p. Hub hub63+, its notes (the version line, the header, the blocks) quote the user")
HSRC = TXT["hub"][TXT["hub"].index("-- ---------------------------------------------------------------- hub63 the parts"):
                  TXT["hub"].index("-- ---------------------------------------------------------------- the game's cast bar (hub11)")]
HSRC += TXT["hub"][TXT["hub"].index("-- ==================================================================== hub63 SPLIT-HUD"):
                   TXT["hub"].index("-- ==================================================================== hub61 THE DAMAGE METER")]
hcode = "\n".join(re.sub(r'--.*$', '', ln) for ln in HSRC.splitlines())
hbad = [w for w in (":Hide(", ":Show(", "SetShown", "SetCVar", "ShowUIPanel", "HideUIPanel", "EnterEditMode", "ExitEditMode",
                    "SetParent", "C_EditMode", "SetBinding", "SetPoint", "ClearAllPoints") if w in hcode]
check(hbad == [], "p. the split's code never hides / shows / re-parents / moves a frame, writes a CVar or opens a game window", hbad)

SPLITPOST = r'''
geo("GamepadPersistentInputLegend", 600, 30, false, true, { "BOTTOM", "BOTTOM", 0, 60 })
geo("DebuffFrame", 300, 40, true, false, { "TOPRIGHT", "TOPRIGHT", -240, -90 })
geo("MainStatusTrackingBarContainer", 500, 14, true, false, { "BOTTOM", "BOTTOM", 0, 0 })
geo("SecondaryStatusTrackingBarContainer", 500, 10, true, false, { "BOTTOM", "BOTTOM", 0, 17 })
'''
PARTS = {"c": "GamepadMainActionBarFrame", "b": "BuffFrame", "x": "MainStatusTrackingBarContainer"}


def hubdoc(L):
    L.execute('WowCompanion.meters.emit()')
    d = [x for x in wowstub.docs(L, 0, "hub") if isinstance(x.get("hub"), dict)]
    return d[-1]["hub"] if d else {}


def shown3(L):
    return {k: alpha(L, v) > 0 for k, v in PARTS.items()}


def listed3(L):
    L.execute('tick(0.5)')
    d = fresh(L)
    on = {p["id"] for p in d["p"] if p.get("g") and p["on"]}
    return {k: ("g." + v) in on for k, v in PARTS.items()}


def layout(L, mask):
    L.eval('hwTap')("ui", mask)
    L.execute('advance(0.3)')


L8, errs8 = world(GAMEPRE)
L8.execute(GAMEPOST + SPLITPOST)
L8.execute('pcall(WowCompanion.nbarSet, false)')                        # the stock controller bar (the Nintendo bar keeps it at 0)
L8.execute('tick(0.5); advance(0.1)')
hold(L8)
h = hubdoc(L8)
hs = h.get("hs") or {}
check(hs.get("v") == "hs2" and hs.get("c") is None and hs.get("b") is None and hs.get("x") is None and hs.get("bit") is None
      and hs.get("cols") == {"C_ON": 24, "C_OFF": 25, "C_RIDE": 26, "B_ON": 27, "B_OFF": 28, "B_RIDE": 29, "X_ON": 30, "X_OFF": 31, "X_RIDE": 32},
      "p. the hub document's hs at login: no word (all three ride the bit), the columns", hs)
check(shown3(L8) == {"c": True, "b": True, "x": True} and listed3(L8) == {"c": True, "b": True, "x": True},
      "p. nothing hidden: all three shown and listed in the editor")
# NO WORD = THE OLD ELEMENT: the bit hides all three together, and shows them together
layout(L8, 2)
check(shown3(L8) == {"c": False, "b": False, "x": False} and alpha(L8, "DebuffFrame") == 0 and alpha(L8, "SecondaryStatusTrackingBarContainer") == 0
      and alpha(L8, "GamepadPersistentInputLegend") == 0, "p. no word: bit 1 hides the controller UI, the buffs + debuffs and both XP containers", shown3(L8))
hud = [e for e in hubdoc(L8)["els"] if e["id"] == "hud"][0]
check(hud["hidden"] is True and hubdoc(L8)["hs"].get("bit") is True, "p. ... the element reports hidden (the bit)", hud)
check(listed3(L8) == {"c": False, "b": False, "x": False}, "p. ... (all three out of the editor's list: why they read as one piece before)")
layout(L8, 0)
check(shown3(L8) == {"c": True, "b": True, "x": True} and alpha(L8, "DebuffFrame") == 1, "p. ... and bit 0 shows all three")
# THE WORDS: only on "lay" while the editor is closed (the meters' row, past their columns)
check(cell(L8, 28), "p. (the lay row's cell 28 is on the pad while the Settings room holds)")
check(shown3(L8) == {"c": True, "b": False, "x": True} and alpha(L8, "DebuffFrame") == 0,
      "p. B_OFF (28): the buffs (and debuffs) hidden, the controller UI and the XP bar stay", shown3(L8))
check(listed3(L8) == {"c": True, "b": False, "x": True}, "p. ... the editor lists the controller bar and the XP bar, not the buffs", listed3(L8))
fixture(L8, "lay_split_nobuffs")
check(L8.eval('WowCompanion.hub.hs.words.b') == "off" and hubdoc(L8)["hs"].get("b") == "off" and hubdoc(L8)["hs"].get("bh") is True
      and L8.eval('type(WowCompanionDB) == "table" and WowCompanionDB.hudsplit or nil') is None,
      "p. ... kept for this session (HS.words, never WowCompanionDB), the document says so")
hud = [e for e in hubdoc(L8)["els"] if e["id"] == "hud"][0]
check(hud["hidden"] is False, "p. ... the element still reports the bit (shown): a word never moves the layout", hud)
cell(L8, 24)                                                            # C_ON
layout(L8, 2)
check(shown3(L8) == {"c": True, "b": False, "x": False}, "p. C_ON (24) + the bit: the controller UI stays, the XP bar (riding) goes", shown3(L8))
check(listed3(L8) == {"c": True, "b": False, "x": False}, "p. ... the controller bar listed alone", listed3(L8))
cell(L8, 30)                                                            # X_ON
check(shown3(L8) == {"c": True, "b": False, "x": True} and listed3(L8) == {"c": True, "b": False, "x": True},
      "p. X_ON (30): the XP bar back while the bit holds", shown3(L8))
cell(L8, 29)                                                            # B_RIDE: follows the bit (set)
check(shown3(L8) == {"c": True, "b": False, "x": True} and L8.eval('WowCompanion.hub.hs.words.b') is None, "p. B_RIDE (29): the word gone, the bit decides")
layout(L8, 0)
check(shown3(L8) == {"c": True, "b": True, "x": True}, "p. ... bit 0: the buffs back")
check(listed3(L8) == {"c": True, "b": True, "x": True}, "p. ... all three listed, three pieces")
fixture(L8, "lay_split")
cell(L8, 25)                                                            # C_OFF
check(shown3(L8) == {"c": False, "b": True, "x": True} and listed3(L8) == {"c": False, "b": True, "x": True},
      "p. C_OFF (25): the controller UI alone hidden; buffs and XP bar listed, each its own piece", shown3(L8))
# each piece moves alone (the editor open: the buffs selected and moved, the XP bar not)
cell(L8, 37)
d = fresh(L8)
bcol = piece(d, "g.BuffFrame").get("c")
x0 = gpts(L8, "MainStatusTrackingBarContainer")
cell(L8, bcol)
catch(L8, 1200, 400)
ease(L8)
check(gpts(L8, "BuffFrame") != ("TOPRIGHT", "TOPRIGHT", -240, -14) and gpts(L8, "MainStatusTrackingBarContainer") == x0,
      "p. the buffs moved on their own (the XP bar where it was)", (gpts(L8, "BuffFrame"), x0))
cell(L8, 36)
cell(L8, 39)
b1 = gpts(L8, "BuffFrame")
# the game re-anchors the buffs (StatusTrayManager.UpdateTrayAndBuffFrameLayout, its default place) and the XP bar (its layout update)
L8.execute('IN_SECURE = true; BuffFrame:ClearAllPointsBase(); BuffFrame:SetPointBase("TOPRIGHT", UIParent, "TOPRIGHT", -205, -13); IN_SECURE = false')
L8.execute('tick(0.5); advance(0.01)')
check(gpts(L8, "BuffFrame") == ("TOPRIGHT", "TOPRIGHT", b1[2] + 35, b1[3] + 1),
      "p. the game re-anchoring the buffs (the tray manager): its new place read as the base, our offset back on", (gpts(L8, "BuffFrame"), b1))
# hub64 (the review): a part's word sent on a stale "closed" document lands just after an open - columns 24-32 are the editor's Copy from
# (24-28) / Reset game (29) / Reach (30-31) / Smaller (32) then: inside HS_GRACE_S it is dropped and NOT counted (the editor's own tap
# comes again after RETRY_MS), so the draft never changes
L8.execute('advance(3.1)')
cell(L8, 38)                                                            # the editor opens
d0 = fresh(L8)
w0 = L8.eval('WowCompanion.hub.hs.words.b')
for col in (27, 29, 32):
    cell(L8, col)
d = fresh(L8)
check(d0["open"] and d["open"] and d["cells"] == d0["cells"] and d.get("hsDrop") == 3 and not d.get("dirty") and d.get("gset") == d0.get("gset")
      and d.get("sel") == d0.get("sel") and L8.eval('WowCompanion.hub.hs.words.b') == w0,
      "p. a word just after the open (27 / 29 / 32): dropped, uncounted, the draft unchanged", (d0.get("cells"), d.get("cells"), d.get("hsDrop"), d.get("dirty")))
L8.execute('advance(2.1)')
cell(L8, 30)                                                            # past the grace: the editor's own Reach off
d = fresh(L8)
check(d["cells"] == d0["cells"] + 1 and d.get("hsDrop") == 3, "p. ... past HS_GRACE_S the editor's cell counts again", (d.get("cells"), d0.get("cells")))
cell(L8, 39)                                                            # closed (nothing saved)
# a word in a fight: alpha only, no breach
viol8 = int(L8.eval('#VIOL'))
L8.execute('advance(3.1); fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
cell(L8, 31)                                                            # X_OFF in a fight
check(alpha(L8, "MainStatusTrackingBarContainer") == 0 and int(L8.eval('#VIOL')) == viol8, "p. X_OFF in a fight: hidden at once (alpha), no breach")
L8.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(0.1)')
# the game gives a hidden rider its alpha back (a level-up's flash): only a hidden group's frame is zeroed again
L8.execute('MainStatusTrackingBarContainer:SetAlpha(1); BuffFrame:SetAlpha(1); WowCompanion.hub.ridersCheck()')
check(alpha(L8, "MainStatusTrackingBarContainer") == 0 and alpha(L8, "BuffFrame") == 1, "p. the riders' check zeroes the hidden XP bar, leaves the shown buffs")
check(lua_errors(L8, errs8) == [] and int(L8.eval('#VIOL')) == 0, "p. clean", (lua_errors(L8, errs8)[:3], int(L8.eval('#VIOL'))))
# hub64: a login (or a /reload) without the app: nothing saved, every part shown - the old element's Off (hidden only while the companion
# is on this screen); a WowCompanionDB.hudsplit an earlier test build left is never read
L9, errs9 = world(GAMEPRE + 'WowCompanionDB = WowCompanionDB or {}; WowCompanionDB.hudsplit = { b = "off" }\n')
L9.execute(GAMEPOST + SPLITPOST)
L9.execute('pcall(WowCompanion.nbarSet, false)')
L9.execute('WowCompanion.snapshot("login"); WowCompanion.snapshot("heartbeat"); advance(0.1)')
check(shown3(L9) == {"c": True, "b": True, "x": True} and L9.eval('WowCompanion.hub.hs.words.b') is None,
      "p. a login without the app: every part shown, no word (nothing saved)", shown3(L9))
L10, errs10 = world(GAMEPRE)
L10.execute(GAMEPOST + SPLITPOST)
L10.execute('pcall(WowCompanion.nbarSet, false)')
L10.execute('WowCompanion.hudHide(); WowCompanion.snapshot("heartbeat"); advance(0.1)')
check(alpha(L10, "GamepadMainActionBarFrame") == 0, "p. no word: the heartbeat never touches a part (/wchud hide stays)")
check(lua_errors(L9, errs9) == [] and lua_errors(L10, errs10) == [], "p. clean (stored)", (lua_errors(L9, errs9)[:3], lua_errors(L10, errs10)[:3]))

# ================================================================ the fixtures (the app's LayoutModelTest / LayoutShotsTest read them)
os.makedirs(FIXDIR, exist_ok=True)
stale = []
for name, text in sorted(FIX.items()):
    fp = os.path.join(FIXDIR, name + ".json")
    old = open(fp, encoding='utf-8').read() if os.path.isfile(fp) else None
    if os.environ.get("PARTY_FIXTURES_WRITE") == "1":
        with open(fp, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(text)
    elif old != text:
        stale.append(name)
check(len(FIX) == 13 and not stale, "the committed fixtures equal what the add-on sends now (%d files; PARTY_FIXTURES_WRITE=1 rewrites)" % len(FIX),
      stale[:4])

print("%s (%d pass, %d fail)" % ("ALL PASS" if fails == 0 else "FAILURES", passes, fails))
sys.exit(1 if fails else 0)
