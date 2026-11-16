"""THE TOP-SCREEN LAYOUT EDITOR, STAGE 1 (TapPad pad22, Trail prd18 / cp41, ActionBar nbar3, Chat chat37, Hub hub55 / uf23), off-device:
the REAL add-on files under tools/wowstub.py's stubbed game (the strict world: the lockdown on every protected call).

    python tools/test_layout.py            (LAYOUT_DUMP=<dir>: the "lay" documents for the app's render, LayoutShotsTest)

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
     strip's bottom-centre 16 under the screen's edge);
  d  OPEN / SELECT / A SPOT: the catcher up (TOOLTIP, level 0: under every cell), a tap at a spot puts the selected piece's anchor there
     (the HUD's frame at it after the ease), the document (part) says where, NOTHING saved until Save (WowCompanionDB.lay untouched;
     SvKeep's svkeep carries no lay), then Save writes it and SvKeep carries it;
  e  THE CATCHER: the mapping (1080-line pixels from the cursor), the 60 ms same-spot guard, the pad band and the sink refused, the
     reach switch (a tap REACH lower reaches a spot inside the band), the selected piece kept on the screen;
  f  LINKED: the target bar follows the compass until it is moved itself; Reset re-attaches it;
  g  SIZE (32 / 33) within the piece's limits, REVERT (35), CLOSE without Save drops the draft, COPY FROM CHARACTER (24..29);
  h  THE FIGHT: the look and the stick catch hide at once, the catcher inert (a tap moves nothing) and gone LINGER_S after its last tap,
     the draft kept, everything back at the fight's end; the row's cells pressed in a fight breach nothing;
  i  the game's own Edit Mode shown: the editor never opens, and closes when it opens; the Settings room letting go closes it (AWAY_S);
  j  THE HUD'S ONE-TIME MOVE (layV 1): every character's old height / scale becomes its layout entry with the bars' top where it was; a
     second login moves nothing; the hub document says prd.lay;
  k  the owners place themselves by the layout: the Nintendo bar, the chat strip, the party column, the compass, the target bar;
  l  clean: no Lua error, no spied call, no lockdown breach.
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
DUMP = os.environ.get("LAYOUT_DUMP")


def world(pre_extra=''):
    L, errs = boot_world(files=tuple(ALL), pre=PRE + pre_extra, strict=True, login=LOGIN, expose=False)
    # with every add-on loaded Sink.lua carries the sends: every line into SENT again (the documents read below), and the editor's
    # document once more for the record
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


def dump(name, doc):
    if not DUMP or doc is None:
        return
    os.makedirs(DUMP, exist_ok=True)
    with open(os.path.join(DUMP, name + ".json"), "w", encoding="utf-8") as fh:
        json.dump(doc, fh, sort_keys=True)


# ================================================================ a. versions, notes, compile, what the editor's code never calls
comp = lupa.LuaRuntime().eval('function(s, n) local f, e = loadstring(s, n); return e end')
FILES = {"tappad": "WowCompanion/TapPad.lua", "trail": "WowCompanion/Trail.lua", "actionbar": "WowCompanion/ActionBar.lua",
         "chat": "WowCompanion/Chat.lua", "hub": "WowCompanionHub/Hub.lua"}
TXT = {k: open(os.path.join(ADDONS, v), encoding="utf-8").read() for k, v in FILES.items()}
for k, v in TXT.items():
    e = comp(v, k)
    check(e is None, "a. Lua 5.1 compiles %s" % FILES[k], e or '')
check('local PADV = "pad22"' in TXT["tappad"] and 'WC.prd = { V = "prd18"' in TXT["trail"] and 'WC.compass = { V = "cp41" }' in TXT["trail"]
      and 'local NB = { V = "nbar3"' in TXT["actionbar"] and 'local CHATV = "chat37"' in TXT["chat"] and 'local HV = "hub55"' in TXT["hub"]
      and 'V = "uf23" }' in TXT["hub"] and 'V = "lay1"' in TXT["hub"],
      "a. the versions: TapPad pad22, Trail prd18 / cp41, ActionBar nbar3, Chat chat37, Hub hub55 (editor lay1, uf23)")
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
hold(L)
d1 = L.eval('WowCompanion.pad.describe()')
check(d1.rows.lay == rows["cal"] and d1.rows.cal is None, "b. the Settings room holds: lay leased onto the row (described), cal not",
      (d1.rows.lay, d1.rows.cal))
doc = last(L)
check(doc.get("held") is True and (doc.get("pad") or {}).get("rows", {}).get("lay") == rows["cal"],
      "b. the document says so: held, the pad's lay row", (doc.get("held"), (doc.get("pad") or {}).get("rows", {}).get("lay")))
dump("closed", doc)

# ================================================================ c. the document's pieces
ids = [p["id"] for p in doc["p"]]
check(ids == ["hud", "compass", "target", "party", "nbar", "chat"], "c. six pieces, in order", ids)
check([p["c"] for p in doc["p"]] == [0, 1, 2, 3, 4, 5], "c. their select columns 0-5", [p["c"] for p in doc["p"]])
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
check(abs(chat["ay"] - 1096) < 0.01 and chat["sn"] == 100 and chat["sx"] == 100 and abs(chat["y"] - 655) < 0.01,
      "c. the chat strip: its bottom-centre 16 under the edge, moved only", (chat["ay"], chat["y"], chat["sn"], chat["sx"]))
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
dump("moved", last_full(L) and dict(last_full(L), **{"sel": "hud"}))
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
dump("linked", dict(last_full(L), sel="target"))

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
dump("dirty", dict(last_full(L), sel="hud"))
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
dump("copy", dict(d, sel=None))
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
dump("combat", dict(last_full(L)))
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

print("%s (%d pass, %d fail)" % ("ALL PASS" if fails == 0 else "FAILURES", passes, fails))
sys.exit(1 if fails else 0)
