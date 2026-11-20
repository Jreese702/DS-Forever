"""ISSUE #358, THE NAMES IN THE GAME'S FONT FAMILY, off-device (a player: "Character names: Cyrillic characters are displayed as empty
squares instead of actual letters"; the user's pick, 2026-11-16: the font family, "CreateFontFamily with the game's per-alphabet members,
like the game's own frames").

    python tools/test_font_family.py

Part A, Trail.lua cp41's WC.font alone (its block, under a bare stub):
  - a family per (height, flags) with the game's own members (blizzard_fonts_shared fonts.xml): roman FRIZQT__, korean 2002, simplified
    Chinese ARKai_T, traditional Chinese blei00d, russian FRIZQT___CYR; the same key reuses it; unique runtime names
  - a client that refuses that set: roman + russian, for that family and every later one
  - no CreateFontFamily, a family with no font, a refused SetFontObject, a bad height: SetFont(FRIZQT__) exactly as before
  - SetFontObject's reset of the string's colour, shadow, justification and spacing undone (plain values only: a secret colour is
    never compared nor put back); a string's text is never read
Part B, Trail + Hub in render_top_party's rig (a party of four, one Cyrillic name, a hostile target), CreateFontFamily stubbed the
worst way (SetFontObject resets every look):
  - every string of ours (party rows, the target bar, its target of target and cast bar, the compass, the HUD) is on a family, none on
    a bare SetFont; the names pass to SetText untouched; justification, colour and shadow survive a K change's re-set
  - the tags: Trail cp41 / ag12 / prd18, Hub hub55 / uf23, each header note quoting the issue and the user's pick
Needs lupa. Exit code 1 on any failure."""
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import render_target_bar as rtb  # noqa: E402
import render_top_party as rt  # noqa: E402
import test_compass as tc  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HUB = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanionHub", "Hub.lua")
ROMAN, CYR = "Fonts\\FRIZQT__.TTF", "Fonts\\FRIZQT___CYR.TTF"
GAME_MEMBERS = [("roman", ROMAN), ("korean", "Fonts\\2002.ttf"), ("simplifiedchinese", "Fonts\\ARKai_T.ttf"),
                ("traditionalchinese", "Fonts\\blei00d.TTF"), ("russian", CYR)]

# the worst case of the real client: SetFontObject hands the string ALL of the object's looks (colour, shadow, justification, spacing)
FAMILY = r'''
FAMS, FAMCALLS, REFUSE, TEXTREADS, FONTOBJ = {}, 0, nil, 0, 0
local R = getmetatable(CreateFrame("Frame"))
function CreateFontFamily(name, members)
    FAMCALLS = FAMCALLS + 1
    if type(name) ~= "string" or rawget(_G, name) ~= nil then error("a bad or used family name " .. tostring(name)) end
    for _, m in ipairs(members) do
        if REFUSE and REFUSE[m.alphabet] then error("no font for " .. m.alphabet) end
        if type(m.file) ~= "string" or type(m.height) ~= "number" or m.height <= 0 or type(m.flags) ~= "string" then error("a bad member") end
    end
    local o = { famName = name, members = members }
    function o:GetFont() if NOFONT then return nil end return members[1].file, members[1].height, members[1].flags end
    FAMS[#FAMS + 1] = o
    rawset(_G, name, o)
    return o
end
function R:SetFontObject(o)
    if REFUSE_OBJ then error("refused") end
    self.fontObj = o
    self.font, self.fsize, self.flags = o.members[1].file, o.members[1].height, o.members[1].flags
    self.tcolor, self.col, self.so, self.sc, self.jh, self.jv, self.sp = nil, nil, nil, nil, nil, nil, nil
    FONTOBJ = FONTOBJ + 1
end
local sf = R.SetFont
function R:SetFont(f, s, fl) self.fontObj = nil; self.bare = true; return sf(self, f, s, fl) end
function R:GetTextColor()
    if rawget(self, "secretColor") then return SECRETV, SECRETV, SECRETV, SECRETV end
    local c = rawget(self, "tcolor") or rawget(self, "col"); if c then return c[1], c[2], c[3], c[4] or 1 end
    return 1, 1, 1, 1
end
function R:SetShadowOffset(x, y) self.so = { x, y } end
function R:GetShadowOffset() local s = rawget(self, "so"); if s then return s[1], s[2] end return 0, 0 end
function R:SetShadowColor(r, g, b, a) self.sc = { r, g, b, a } end
function R:GetShadowColor() local s = rawget(self, "sc"); if s then return s[1], s[2], s[3], s[4] end return 0, 0, 0, 1 end
function R:GetJustifyH() return rawget(self, "jh") or "CENTER" end
function R:SetJustifyV(j) self.jv = j end
function R:GetJustifyV() return rawget(self, "jv") or "MIDDLE" end
function R:SetSpacing(s) self.sp = s end
function R:GetSpacing() return rawget(self, "sp") or 0 end
function R:GetText() TEXTREADS = TEXTREADS + 1; return rawget(self, "text") end
SECRETV = setmetatable({}, { __eq = function() error("a secret compared") end, __lt = function() error("a secret compared") end,
                             __le = function() error("a secret compared") end, __tostring = function() error("a secret read") end })
'''

# Part A's bare game: a string records its font calls; nothing else exists
BARE = r'''
CALLS = {}
local FS = {}
FS.__index = FS
function FS:SetFont(f, h, fl) self.font, self.fsize, self.flags, self.fontObj = f, h, fl, nil; CALLS[#CALLS + 1] = "SetFont" end
function FS:SetTextColor(r, g, b, a) self.tc = { r, g, b, a } end
function FS:GetTextColor() if self.secretColor then return SECRETV, SECRETV, SECRETV, SECRETV end local c = self.tc or { 1, 1, 1, 1 }; return c[1], c[2], c[3], c[4] end
function FS:SetShadowOffset(x, y) self.so = { x, y } end
function FS:GetShadowOffset() local s = self.so or { 0, 0 }; return s[1], s[2] end
function FS:SetShadowColor(r, g, b, a) self.sc = { r, g, b, a } end
function FS:GetShadowColor() local s = self.sc or { 0, 0, 0, 1 }; return s[1], s[2], s[3], s[4] end
function FS:SetJustifyH(j) self.jh = j end
function FS:GetJustifyH() return self.jh or "CENTER" end
function FS:SetJustifyV(j) self.jv = j end
function FS:GetJustifyV() return self.jv or "MIDDLE" end
function FS:SetSpacing(s) self.sp = s end
function FS:GetSpacing() return self.sp or 0 end
function FS:GetText() TEXTREADS = TEXTREADS + 1; return self.text end
function FS:SetFontObject(o)
    if REFUSE_OBJ then error("refused") end
    self.fontObj, self.font = o, nil
    self.tc, self.so, self.sc, self.jh, self.jv, self.sp = nil, nil, nil, nil, nil, nil
    CALLS[#CALLS + 1] = "SetFontObject"
end
function newFS() return setmetatable({}, FS) end
FAMS, FAMCALLS, REFUSE, TEXTREADS = {}, 0, nil, 0
function CreateFontFamily(name, members)
    FAMCALLS = FAMCALLS + 1
    if type(name) ~= "string" or rawget(_G, name) ~= nil then error("a bad or used family name") end
    for _, m in ipairs(members) do if REFUSE and REFUSE[m.alphabet] then error("no font for " .. m.alphabet) end end
    local o = { famName = name, members = members }
    function o:GetFont() if NOFONT then return nil end return members[1].file, members[1].height, members[1].flags end
    FAMS[#FAMS + 1] = o; rawset(_G, name, o)
    return o
end
SECRETV = setmetatable({}, { __eq = function() error("a secret compared") end, __lt = function() error("a secret compared") end,
                             __le = function() error("a secret compared") end, __tostring = function() error("a secret read") end })
WowCompanion = { isPlain = function(v) return not rawequal(v, SECRETV) end }
'''


def block():
    """Trail.lua's cp41 WC.font block: from its header note to the line before the compass's tag"""
    src = open(tc.TRAIL, encoding="utf-8").read()
    a = src.index("-- cp41 ISSUE #358")
    b = src.index('WC.compass = { V = "cp')                       # (cp43 or later)
    return src[a:b]


def main():
    fails = []

    def chk(name, ok, got=''):
        print(("PASS " if ok else "FAIL ") + name + ((" -> " + str(got)) if got != '' and not ok else ''))
        if not ok:
            fails.append(name)

    def members(L, o):
        m = o.members
        return [(m[i].alphabet, m[i].file, m[i].height, m[i].flags) for i in range(1, len(m) + 1)]

    # ================================================================ Part A: WC.font alone
    def fresh(pre=""):
        L = tc.lupa.LuaRuntime()
        L.execute(BARE)
        if pre:
            L.execute(pre)
        L.execute("local WC = WowCompanion\n" + block())
        return L, L.globals()

    L, G = fresh("CreateFontFamily = nil")
    fs = G.newFS()
    r = G.WowCompanion.font(fs, 12, "OUTLINE")
    chk("A: no CreateFontFamily: SetFont(FRIZQT__, 12, OUTLINE) as before, false", r is False and fs.font == ROMAN and fs.fsize == 12
        and fs.flags == "OUTLINE" and list(G.CALLS.values()) == ["SetFont"], (r, fs.font, list(G.CALLS.values())))

    L, G = fresh()
    W, F = G.WowCompanion, G.WowCompanion.fontFamily
    fs = G.newFS()
    fs.SetTextColor(fs, 1, 0.83, 0.42, 1)
    fs.SetShadowOffset(fs, 0.7, -0.7)
    fs.SetShadowColor(fs, 0, 0, 0, 0.9)
    fs.SetJustifyH(fs, "LEFT")
    fs.SetJustifyV(fs, "TOP")
    fs.SetSpacing(fs, 2)
    r = W.font(fs, 12.5, "OUTLINE")
    o = fs.fontObj
    chk("A: a family: SetFontObject, no SetFont, true", r is True and o is not None and list(G.CALLS.values()) == ["SetFontObject"],
        (r, list(G.CALLS.values())))
    chk("A: the game's own five members, in fonts.xml's order, at the asked height and flags",
        o is not None and members(L, o) == [(a, f, 12.5, "OUTLINE") for a, f in GAME_MEMBERS], o and members(L, o))
    chk("A: SetFontObject's reset undone: colour, shadow offset and colour, justification, spacing",
        list(fs.tc.values()) == [1, 0.83, 0.42, 1] and list(fs.so.values()) == [0.7, -0.7] and list(fs.sc.values()) == [0, 0, 0, 0.9]
        and fs.jh == "LEFT" and fs.jv == "TOP" and fs.sp == 2)
    chk("A: a runtime name of ours (WowCompanionFont<n>)", o is not None and re.match(r"^WowCompanionFont\d+$", o.famName or ""), o and o.famName)
    fs2 = G.newFS()
    W.font(fs2, 12.5, "OUTLINE")
    chk("A: the same height and flags reuse the family (one CreateFontFamily)", G.FAMCALLS == 1 and fs2.fontObj.famName == o.famName,
        G.FAMCALLS)
    fs3 = G.newFS()
    W.font(fs3, 12.5, None)
    fs4 = G.newFS()
    W.font(fs4, 13, "THICKOUTLINE")
    chk("A: other flags / heights: their own families (no flags = \"\")", G.FAMCALLS == 3 and fs3.fontObj.members[1].flags == ""
        and fs4.fontObj.members[5].height == 13 and fs4.fontObj.members[5].flags == "THICKOUTLINE" and fs4.fontObj.members[5].file == CYR,
        G.FAMCALLS)
    names = set(G.FAMS[i].famName for i in range(1, len(G.FAMS) + 1))
    chk("A: every family's name unique", len(names) == len(G.FAMS), names)
    sec = G.newFS()
    sec.secretColor = True
    ok = True
    try:
        r = W.font(sec, 12.5, "OUTLINE")
    except Exception as e:
        ok, r = False, str(e)
    chk("A: a secret colour is never compared nor put back (no error, the family still applied)", ok and r is True
        and sec.fontObj is not None and sec.tc is None, (ok, r, sec.tc))
    chk("A: a string's text is never read", G.TEXTREADS == 0, G.TEXTREADS)
    bad = G.newFS()
    W.font(bad, 0, "OUTLINE")
    chk("A: a height the game refuses goes to SetFont as before (its own error, as today's call)", bad.font == ROMAN and bad.fontObj is None)

    L, G = fresh("REFUSE = { korean = true }")
    fs = G.newFS()
    r = G.WowCompanion.font(fs, 14, "OUTLINE")
    fs2 = G.newFS()
    G.WowCompanion.font(fs2, 16, "")
    chk("A: a client that refuses the five: roman + russian, for this family and the next (3 calls: one refused)",
        r is True and members(L, fs.fontObj) == [("roman", ROMAN, 14, "OUTLINE"), ("russian", CYR, 14, "OUTLINE")]
        and members(L, fs2.fontObj) == [("roman", ROMAN, 16, ""), ("russian", CYR, 16, "")] and G.FAMCALLS == 3
        and G.WowCompanion.fontFamily.set == 2, (members(L, fs.fontObj) if fs.fontObj else None, G.FAMCALLS))

    L, G = fresh("REFUSE = { roman = true }")
    fs = G.newFS()
    r = G.WowCompanion.font(fs, 14, "OUTLINE")
    chk("A: no set taken at all: SetFont(FRIZQT__) as before, and no further tries", r is False and fs.font == ROMAN and G.FAMCALLS == 2
        and (G.WowCompanion.font(G.newFS(), 15, "") is False) and G.FAMCALLS == 2, G.FAMCALLS)

    L, G = fresh("NOFONT = true")
    fs = G.newFS()
    r = G.WowCompanion.font(fs, 14, "OUTLINE")
    chk("A: a family with no font (GetFont empty): SetFont as before", r is False and fs.font == ROMAN and fs.fontObj is None)

    L, G = fresh("REFUSE_OBJ = true")
    fs = G.newFS()
    r = G.WowCompanion.font(fs, 14, "OUTLINE")
    chk("A: SetFontObject refused: SetFont as before", r is False and fs.font == ROMAN and fs.fontObj is None)

    # ================================================================ Part B: Trail + Hub on the rig
    saved = rt.GAME2
    rt.GAME2 = saved + FAMILY
    try:
        rig = rt.Rig()
    finally:
        rt.GAME2 = saved
    rig.L.execute(rtb.SETUP)
    G = rig.G
    rt.MEMBERS[0] = dict(rt.MEMBERS[0], name="Борис")
    try:
        rig.scene(dict(rt.shapes()["party5_pet_mode"], buffs=True))
    finally:
        rt.MEMBERS[0] = dict(rt.MEMBERS[0], name="Brakka")
    UF, PF, TB = rig.UF, rig.PF, rig.UF.tb
    C = G.WowCompanion.compass
    regs = [G.ALLR[i] for i in range(1, len(G.ALLR) + 1)]
    strs = [r for r in regs if r.kind == "fs"]
    fam = [r for r in strs if r.fontObj is not None]
    bare = [r for r in strs if r.fontObj is None and r.font is not None and r.font != "x"]
    chk("B: every string of ours on a family (%d), none on a bare SetFont (%d)" % (len(fam), len(bare)), len(fam) >= 20 and not bare,
        [(b.font, b.text) for b in bare][:5])
    bad = []
    for r in fam:
        m = members(rig.L, r.fontObj)
        if [(a, f) for a, f, _, _ in m] != [(a, f) for a, f in GAME_MEMBERS] or len(set((h, fl) for _, _, h, fl in m)) != 1:
            bad.append(m)
    chk("B: each family is the game's five members at one height and flags", not bad, bad[:2])
    keys = {}
    for i in range(1, len(G.FAMS) + 1):
        o = G.FAMS[i]
        k = ("%.3f" % o.members[1].height, o.members[1].flags)
        keys[k] = keys.get(k, 0) + 1
    chk("B: one family per (height, flags) (%d families)" % len(G.FAMS), all(v == 1 for v in keys.values()), keys)
    p1 = PF.frames[1]
    chk("B: the Cyrillic party name: on a family whose russian member is FRIZQT___CYR, handed to SetText untouched, still LEFT",
        p1 is not None and p1.name.fontObj is not None and p1.name.fontObj.members[5].file == CYR and p1.name.text == "Борис"
        and p1.name.jh == "LEFT", p1 and (p1.name.text, p1.name.jh))
    chk("B: the party level keeps its gold and its centre",
        p1 is not None and list(p1.lvl.tcolor.values())[:3] == [1, 0.83, 0.42] and p1.lvl.jh == "CENTER", p1 and list(p1.lvl.tcolor.values()))
    f = TB.f
    chk("B: the target bar's name, the target of target (THICKOUTLINE, uf7) and the cast bar's spell on families",
        f is not None and f.name.fontObj is not None and f.totName.fontObj is not None and f.totName.fontObj.members[1].flags == "THICKOUTLINE"
        and f.totName.jh == "LEFT" and f.castName.fontObj is not None, f and (f.totName.flags, f.totName.jh))
    so0 = list(p1.name.so.values())
    rig.set_k(900)
    rig.run(0.5)
    p1 = PF.frames[1]
    chk("B: a K change's re-set (party name / level): still families, the shadow, colour and justification kept",
        p1.name.fontObj is not None and p1.name.jh == "LEFT" and list(p1.name.so.values()) == so0
        and list(p1.lvl.tcolor.values())[:3] == [1, 0.83, 0.42] and p1.name.text == "Борис", (p1.name.jh, list(p1.name.so.values()), so0))
    bare = [r for r in (G.ALLR[i] for i in range(1, len(G.ALLR) + 1)) if r.kind == "fs" and r.fontObj is None and r.font is not None
            and r.font != "x"]
    chk("B: after the re-set, still no bare SetFont", not bare, [(b.font, b.text) for b in bare][:5])
    chk("B: no string's text read (GetText) by the font work", G.TEXTREADS == 0, G.TEXTREADS)

    # ================================================================ Part C: the compass (test_compass's stub game) on families
    L = tc.lupa.LuaRuntime()
    L.execute(tc.STUB)
    L.execute(FAMILY)
    L.execute(open(tc.TRAIL, encoding="utf-8").read().replace("local ADDON = ...", "local ADDON = 'WowCompanion'", 1))
    G = L.globals()
    C, GA = G.WowCompanion.compass, G.GAME
    GA.facing = math.radians(270)
    GA.pin = L.table(0.6, 0.5)
    C.refresh()
    C.draw()
    tick = [round(v, 4) for v in list(C.TICK.values())]
    lets = [C.slot[d].fs for d in (0, 45, 90, 135, 180, 225, 270, 315)]
    chk("C: the compass letters on families (OUTLINE), in the ticks' colour",
        all(x.fontObj is not None and x.fontObj.members[1].flags == "OUTLINE" and [round(v, 4) for v in list(x.col.values())[:3]] == tick
            for x in lets), [(x.fontObj is not None, x.col and list(x.col.values())) for x in lets][:3])
    e = C.slot[270]
    chk("C: E lifted under the pin: re-set on a smaller family, still the ticks' colour",
        e.lifted and e.fs.fontObj is not None and e.fs.fsize < C.slot[0].fs.fsize
        and [round(v, 4) for v in list(e.fs.col.values())[:3]] == tick, (e.lifted, e.fs.fsize))
    kids = [r for r in (C.frame.kids[i] for i in range(1, len(C.frame.kids) + 1)) if r.kind == "fs"]
    chk("C: every compass string on a family (%d)" % len(kids), kids and all(k.fontObj is not None for k in kids))

    # ================================================================ the tags and their notes
    trail = open(tc.TRAIL, encoding="utf-8").read()
    hub = open(HUB, encoding="utf-8").read()
    quote = "Cyrillic characters are displayed as empty squares instead of actual letters"
    chk("tags: Trail cp43 (or later) / ag13 (ag12f) / prd19, Hub hub56 / uf24 (the 11-16 merge)", int(C.V[2:]) >= 43 and int(G.WowCompanion.live.V[2:]) >= 13
        and int(G.WowCompanion.prd.V[3:]) >= 19 and int(re.search(r'local HV = "hub(\d+)"', hub).group(1)) >= 57 and int(UF.V[2:]) >= 24, (C.V, UF.V))
    chk("tags: each header note quotes the issue and the user's pick (2026-11-16)",
        all(n in trail for n in ("-- cp41 ISSUE #358", "-- ag12f ISSUE #358", "-- prd18 ISSUE #358"))
        and "-- uf23 (hub55) ISSUE #358" in hub and "hub55 ISSUE #358" in hub
        and trail.count(quote) >= 3 and hub.count(quote) >= 2 and trail.count("user's pick, 2026-11-16") >= 1 and trail.count("user's pick,") >= 3 and hub.count("the user's pick, 2026-11-16") >= 2)
    chk("Hub: no SetFont(FONT ...) left but the cooldown's own seconds; Trail: none of FRIZQT__ but the fallback and the calibration label",
        len(re.findall(r":SetFont\(FONT,", hub)) == 2 and "rg:SetFont(FONT, u(10)" in hub
        and "else fs:SetFont(FONT, h, flags) end" in hub
        and len(re.findall(r':SetFont\("Fonts', trail)) == 0 and len(re.findall(r":SetFont\(C\.FONT,", trail)) == 1
        and "Q.label:SetFont(C.FONT" in trail)

    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
