"""THE HOLD SPELL ON THE TOP SCREEN'S WINGS (ActionBar nbar21, PadMap m195), off-device: the REAL ActionBar.lua (+ Safety, Hub) under
tools/test_nintendo_bar.py's stub (our frames recorded, the game's reads stubbed, the clock NOW), driven through the bar's own tick.

    python -B tools/test_hold_wings.py [--shots DIR]

The user on test 1529, 2026-11-24: "When casting a hold spell its not clear, I think it needs to take over the main spell circle until
the [GCD] completes following it or if its canceled for whatever reason. Also they look like they are ready to cast in conditions that
they are not like when mana is low or its out of range"; "after like .25s of holding I want a charge up circle to appear around the
little hold icon so that its clear when I am casting it"; "And that also needs to appear on the top screen ui".

Proves: (0) the tag; (1) THE BADGE'S STATES: the hold spell's not enough mana = the icon blue, not usable = grey (desaturated), out of
range = the badge's ring RED (range only), a ready one plain; (2) THE CHARGE RING round the badge: nothing before 0.25 s held, beads
filling clockwise to the hold point (WC.padMap.holdSecs, Short / Normal / Long), "charged" (all bright) from it while held, gone on an
early release, never while the router does not keep the press (WC.padMapKept); (3) THE TAKEOVER: WC.holdFired puts the hold spell on the
big face (and the tap spell on the badge); a cast keeps it to the cast's end, then the GCD that started with it; "fail" sends it back at
once; with no word it goes 2 s after the fire; it eases back over 0.2 s and the badge shows the hold spell again; the face's sweep is
the hold spell's own while it has the face; (4) nothing secure (plain frames only, no SetBinding / SetCVar).
ROUND 1533 (ActionBar nbar25, PadMap m199; the user on test 1532, 2026-11-25: "The red outline is an eyesore we should just shade
the ability like we do with the glyphs. And same with the charge up dots, it should be a glowing ring around the skill. When the ability
is casting it should show the ability with a golden ring, a great example of this is raptor strikes normal behavior"): (1) out of range
SHADES the badge's icon red (its disc stays brown), mana + range red, unusable + range grey; (2) no beads: a GLOWING ring (hb.ring: the
circle glow, a track, one Cooldown started once from 0.25 s to the fire point, reversed so the gold grows; "full" when charged); (3)
the takeover's face wears the game's gold checked ring (b.hsel), eased with it; a queued hold (IsCurrentSpell, PadMap's "q") keeps the
face past the 2 s fallback until it stops being current; "none" ends it at once; "gcd" gives its end.
(5) THE FACE SAYS THE SPELL IT SHOWS (nbar25; the user on test 1532, 2026-11-25: "When using hold to cast it will show the button glyph
as red if the original spell was not usable, I want that to also shift with the ability that its showing casting so its not
confusing"): while the takeover has the face the glyph's red (range only), b.range, the face's own shade (b.hflash: blue mana, grey
unusable) and the count are the HOLD spell's, switched at the fire and back at the end at once; the badge fades out (1 - the face's
alpha), never wearing the tap spell. The NEXT spell's GCD (begun at the hold's GCD's end, the game's spell-queue window) is not the
hold's: the face does not pop back while it eases out; a GCD up to 0.45 s after the fire (an instant queued in that window) is.
--shots DIR renders the four faces with the real art (the game's ring / glyph art from the art root, the icons from its icons.pak).
Needs lupa (PIL for --shots). Exit code 1 on any failure."""
import bisect
import math
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import test_nintendo_bar as tnb  # noqa: E402

FAILS = []


def chk(name, ok, got=None):
    print(("PASS " if ok else "FAIL ") + name + ("" if ok or got is None else " -> " + repr(got)))
    if not ok:
        FAILS.append(name)


# the faces' button numbers (0-based, as C_GamePad gives them: PAD1 A 0, PAD2 B 1, PAD3 X 2, PAD4 Y 3), the triggers 10 / 11
PRE = r'''
PADIDX = { PAD1 = 0, PAD2 = 1, PAD3 = 2, PAD4 = 3, PADDLEFT = 14, PADDUP = 12, PADDRIGHT = 15, PADDDOWN = 13, PADLTRIGGER = 10, PADRTRIGGER = 11 }
C_GamePad.ButtonBindingToIndex = function(n) return PADIDX[n] end
HELD = {}
C_GamePad.GetDeviceMappedState = function() local b = { [11] = LT, [12] = RT }; for k, v in pairs(HELD) do b[PADIDX[k] + 1] = v end; return { buttons = b } end
-- PadMap's hold views: A (8) Purge (no mana), Y (6) Flame Shock (out of range), X (5) Searing Totem (not usable), the D-pad's up (2)
-- Lightning Bolt (ready); the router keeps the presses in KEPT
HOLDS = { [8] = { n = "Purge", id = 370, i = 136075 }, [6] = { n = "Flame Shock", id = 8050, i = 135813 },
          [5] = { n = "Searing Totem", id = 3599, i = 135825 }, [2] = { n = "Lightning Bolt", id = 403, i = 136048 } }
KEPT, SECS = {}, 0.373          -- nbar28: PadMap m204's holdSecs is the ring's full point R (Normal at 60 fps)
-- nbar25: the glowing ring's Cooldown as the client takes it, and the game's "current" read (a queued next-swing spell)
local FM = getmetatable(UIParent)
FM.SetCooldown = function(self, st, d) self.cdStart, self.cdDur = st, d; self.cdN = (self.cdN or 0) + 1 end
FM.SetReverse = function(self, v) self.reverse = v end
FM.SetSwipeColor = function(self, r, g, b, a) self.swipeColor = { r, g, b, a } end
CURRENT = {}
C_Spell.IsCurrentSpell = function(id) return CURRENT[id] == true end
-- the fix pass: a texture's draw layer and sublevel as the client takes them
local ctex = FM.CreateTexture
FM.CreateTexture = function(self, name, layer, tpl, sub) local t = ctex(self, name, layer); t.sub = sub; return t end
FM.SetDrawLayer = function(self, layer, sub) self.layer, self.sub = layer, sub end
'''
POST = r'''
local pm = WowCompanion.padMap
pm.holdOn = function() return true end
pm.holdTab = function() return "normal" end
pm.holdAt = function(page, set, pos, tab) if set == 1 then return HOLDS[pos] end end
pm.holdSecs = function() return SECS end
WowCompanion.padMapKept = function(p) return KEPT[p] == true end
function TICK(s, step)
    step = step or 0.05
    local t = 0
    while t < s - 1e-9 do NOW = NOW + step; t = t + step; WowCompanion.nbar.tick(step) end
end
'''


def boot(pre=""):
    L = tnb.boot(pre=PRE + pre)
    L.execute(POST)
    G = L.globals()
    G.ROWS["uisw"](None, 35)                          # the Nintendo bar on (Hub's switch)
    L.execute("TICK(0.3)")
    return L, G


def main():
    ab = open(tnb.AB, encoding="utf-8").read()
    chk("0. ActionBar nbar25 and its note (nbar21, nbar18 kept)", 'local NB = { V = "nbar31"' in ab and "nbar25 HOLD TO CAST'S VISUALS, ROUND 1533" in ab
        and "nbar21 THE HOLD SPELL ON THE TOP SCREEN" in ab and "nbar18 HOLD TO CAST" in ab)
    chk("0. no beads any more", "HOLD_BEADS" not in ab and "hb.beads" not in ab)
    L, G = boot()
    NB = G.WowCompanion.nbar
    f = NB.f
    b = {p: f.btn[p] for p in range(1, 9)}

    # ------------------------------------------------------------------------------------------------ (1) the badge's states
    chk("1. each button with a hold spell wears its badge", all(b[p].hb is not None and b[p].hb.shown for p in (2, 5, 6, 8)),
        [b[p].hb is not None for p in (2, 5, 6, 8)])
    chk("1. not enough mana (Purge on A): the badge's icon blue", b[8].hlook == "mana" and tuple(b[8].hb.icon.vc.values()) == (0.45, 0.55, 1),
        (b[8].hlook,))
    chk("1. not usable (Searing Totem on X): grey, desaturated", b[5].hlook == "unusable" and b[5].hb.icon.desat is True, b[5].hlook)
    disc6 = [round(float(v), 3) for v in b[6].hb.disc.color.values()]
    brown = [round(110 / 255, 3), round(89 / 255, 3), round(66 / 255, 3)]
    vc6 = [round(float(v), 3) for v in b[6].hb.icon.vc.values()]
    chk("1. out of range (Flame Shock on Y): the badge's ICON shaded red (1, 0.32, 0.30), its disc brown - no red ring", b[6].hrange is False
        and disc6[:3] == brown and b[6].hlook == "range" and vc6 == [1.0, 0.32, 0.3] and b[6].hb.icon.desat is False, (disc6, vc6, b[6].hlook))
    L.execute('SPELLS[370].range = false; SPELLS[3599].range = false; TICK(0.25)')
    vc8 = [round(float(v), 3) for v in b[8].hb.icon.vc.values()]
    chk("1. not enough mana AND out of range (Purge): red (red is range)", b[8].hlook == "range" and vc8 == [1.0, 0.32, 0.3], (b[8].hlook, vc8))
    chk("1. not usable AND out of range (Searing Totem): grey, desaturated", b[5].hlook == "unusable" and b[5].hb.icon.desat is True, b[5].hlook)
    chk("1. every disc brown (no red anywhere)", all([round(float(v), 3) for v in b[p].hb.disc.color.values()][:3] == brown for p in (2, 5, 6, 8)))
    L.execute('SPELLS[370].range = nil; SPELLS[3599].range = nil; TICK(0.25)')
    disc2 = [round(float(v), 3) for v in b[2].hb.disc.color.values()]
    chk("1. ready (Lightning Bolt on the D-pad's up): plain icon, the brown ring", b[2].hlook == "ok" and b[2].hrange is True
        and disc2[:3] == [round(110 / 255, 3), round(89 / 255, 3), round(66 / 255, 3)], (b[2].hlook, disc2))
    L.execute('TARGET = false; TICK(0.25)')
    chk("1. no target: no range to say (the ring brown again)", b[6].hrange is True)
    L.execute('TARGET = true; TICK(0.25)')
    # the 1531 audit: PARTY MODE has no holds (PadMap's OV.hold.mapNow says "party"; 1530 took Home's badges off for it): none on the
    # top screen either, back when it ends
    L.execute('WowCompanion.padMap.party = { live = function() return PARTYON == true end }; PARTYON = true; TICK(0.25)')
    chk("1. Party mode on: no hold badge on the top screen (Party mode never fires a hold)",
        all(b[p].hb is None or not b[p].hb.shown for p in (2, 5, 6, 8)) and all(b[p].hold is None for p in (2, 5, 6, 8)),
        [bool(b[p].hb and b[p].hb.shown) for p in (2, 5, 6, 8)])
    # the 1532 audit: Edit Home's In combat preview (PadMap m198's pv, party = false) while Party mode is really on: still no hold badge
    # (the preview lays the In combat look; the real Party mode still fires no hold)
    L.execute('WowCompanion.padMap.preview = function() return PV end; PV = { wings = true, party = false }; TICK(0.25)')
    chk("1. Party mode on, the In combat tab's preview up: still no hold badge on the top screen (the 1532 audit)",
        all(b[p].hb is None or not b[p].hb.shown for p in (2, 5, 6, 8)) and all(b[p].hold is None for p in (2, 5, 6, 8)),
        [bool(b[p].hb and b[p].hb.shown) for p in (2, 5, 6, 8)])
    L.execute('PV = nil; TICK(0.25)')
    L.execute('PARTYON = false; TICK(0.25)')
    chk("1. ... Party mode off: the badges back", all(b[p].hb is not None and b[p].hb.shown for p in (2, 5, 6, 8)))

    # ------------------------------------------------------------------------------------------------ (2) the charge ring
    def lit(p):
        """the glowing ring at [p] shown (nbar25: no beads)"""
        rg = b[p].hb.ring
        return rg is not None and rg.shown is True
    rg2 = b[2].hb.ring
    # nbar28 (fix/hold-tweaks, 1535; the user: "the charge up ring has like space between the ring and the glow effect. It also looks
    # orange instead of yellow like the golden casting glow"): the glow is three gold discs of the circle mask right beyond the track
    # (+3 / +6 / +10), under it - no circglw atlas (its glow sat beyond the ring, a dark gap between); all of it the game's checked gold
    gl = [rg2.glows[i] for i in (1, 2, 3)] if rg2 is not None and rg2.glows is not None else []
    chk("2. no beads: the glowing ring's own frame (its glow, a track, a Cooldown, full)", b[2].hb.beads is None and rg2 is not None
        and rg2.glow is not None and rg2.trk is not None and rg2.cd is not None and rg2.full is not None,
        (b[2].hb.beads, rg2 and rg2.glow))
    chk("2. nbar28: the glow HUGS the ring - three discs just beyond the track (+3 / +6 / +10 units), under it, no circglw atlas",
        len(gl) == 3 and L.eval("(function() local r = WowCompanion.nbar.f.btn[2].hb.ring; return r.glow == r.glows[1] end)()") and [float(g.w) for g in gl] == [float(rg2.trk.w) + d for d in (3, 6, 10)]
        and all(g.atlas is None for g in gl) and all(g.layer == "BACKGROUND" for g in gl)
        and [int(g.sub) for g in gl] == [0, -1, -2] and rg2.trk.layer == "ARTWORK", [(g.w, g.atlas, g.layer, g.sub) for g in gl])
    gold = [0.965, 0.84, 0.22]
    chk("2. nbar28: the ring, its glow and its full look the game's checked gold (the casting ring's yellow), not orange",
        [round(float(v), 3) for v in rg2.cd.swipeColor.values()][:3] == gold
        and all([round(float(v), 3) for v in g.color.values()][:3] == gold for g in gl)
        and [round(float(v), 3) for v in rg2.full.color.values()][:3] == [1.0, 0.93, 0.5],
        ([v for v in rg2.cd.swipeColor.values()], [list(g.color.values()) for g in gl]))
    chk("2. ... its Cooldown reversed (the gold grows clockwise from 12 o'clock), round", rg2.cd.reverse is True and rg2.cd.circEdge is True,
        (rg2.cd.reverse,))
    chk("2. ... under the badge (the badge over its middle leaves a ring)", (rg2.level or 0) < (b[2].hb.level or 0), (rg2.level, b[2].hb.level))
    L.execute('KEPT[2] = true; HELD.PADDUP = true')
    L.execute('TICK(0.05)')
    chk("2. held 0.05 s: no ring yet (quick taps show nothing)", not lit(2) and b[2].hcharge == 0, (lit(2), b[2].hcharge))
    L.execute('TICK(0.1)')
    hd_at = float(b[2].hdAt)
    c1, f1, g1 = rg2.cd.cdN, float(b[2].hcharge), float(rg2.glow.ga)
    chk("2. nbar28: from 0.1 s (was 0.25) the ring shows: its Cooldown started ONCE, from 0.1 s after the press to the ring's full point",
        lit(2) and c1 == 1 and abs(float(rg2.cd.cdStart) - (hd_at + 0.1)) < 1e-9 and abs(float(rg2.cd.cdDur) - (0.373 - 0.1)) < 1e-9,
        (lit(2), c1, rg2.cd.cdStart, hd_at, rg2.cd.cdDur))
    L.execute('TICK(0.1)')
    f2, g2 = float(b[2].hcharge), float(rg2.glow.ga)
    chk("2. ... filling (0..1 of the way) and its glow brightening, the Cooldown never restarted", 0 <= f1 < f2 < 1 and g1 < g2 and rg2.cd.cdN == 1,
        (f1, f2, g1, g2, rg2.cd.cdN))
    chk("2. ... the outer discs fainter (a soft edge)", float(rg2.glows[1].alpha) > float(rg2.glows[2].alpha) > float(rg2.glows[3].alpha) > 0,
        [float(rg2.glows[i].alpha) for i in (1, 2, 3)])
    exp = (0.25 - 0.1 - 0.025) / (0.373 - 0.1)
    chk("2. ... at the ring's pace (PadMap m204's R, Normal at 60 fps: 0.373 s; 0.25 s held = %.2f)" % exp, abs(f2 - exp) < 0.13, f2)
    L.execute('TICK(0.2)')
    chk("2. past the ring's full point: CHARGED - full and bright, the glow 0.95 (the hold is sure; the app holds a release down to F)",
        b[2].hcharge == "charged" and rg2.full.shown is True and abs(float(rg2.glow.ga) - 0.95) < 1e-9, (b[2].hcharge, rg2.full.shown, rg2.glow.ga))
    L.execute('HELD.PADDUP = nil; TICK(0.1)')
    chk("2. let go (the game read it up): gone (the Cooldown cleared, full hidden)", not lit(2) and rg2.full.shown is False and (rg2.cd.cleared or 0) >= 1)
    L.execute('HELD.PADDUP = true; TICK(0.2); HELD.PADDUP = nil; TICK(0.1)')
    chk("2. an early release (0.2 s): the ring vanishes (a tap)", not lit(2) and b[2].hcharge == 0)
    L.execute('KEPT[2] = nil; HELD.PADDUP = true; TICK(0.8)')
    chk("2. a press the router does not keep (no hold for it now): no ring", not lit(2), lit(2))
    L.execute('HELD.PADDUP = nil; TICK(0.1); KEPT[2] = true; SECS = 0.173; HELD.PADDUP = true; TICK(0.25)')
    chk("2. Short (0.17 s): charged by 0.25 s held", b[2].hcharge == "charged", b[2].hcharge)
    L.execute('HELD.PADDUP = nil; TICK(0.1); SECS = 0.573; HELD.PADDUP = true; TICK(0.4)')
    chk("2. Long (0.57 s): still filling at 0.4 s", isinstance(b[2].hcharge, (int, float)) and 0 < b[2].hcharge < 1, b[2].hcharge)
    L.execute('HELD.PADDUP = nil; TICK(0.1); SECS = 0.373')
    # nbar27 (the int/1533 review): the fill point is taken ONCE at the press (b.hdSure) - a frame time (ft) that changes mid-hold no
    # more moves the fill
    L.execute('KEPT[2] = true; HELD.PADDUP = true; TICK(0.2)')
    sure0 = float(b[2].hdSure)
    L.execute('SECS = 0.6; TICK(0.25)')
    chk("2. nbar27: the fill point frozen at the press (0.373 s): charged by 0.45 s held although holdSecs now says 0.6",
        abs(sure0 - 0.373) < 1e-9 and b[2].hcharge == "charged" and abs(float(rg2.cd.cdDur) - (0.373 - 0.1)) < 1e-9,
        (sure0, b[2].hcharge, rg2.cd.cdDur))
    L.execute('HELD.PADDUP = nil; TICK(0.1); HELD.PADDUP = true; TICK(0.45)')
    chk("2. ... the next press takes the new point (0.6 s): still filling at 0.45 s", abs(float(b[2].hdSure) - 0.6) < 1e-9
        and isinstance(b[2].hcharge, (int, float)) and 0 < b[2].hcharge < 1, (b[2].hdSure, b[2].hcharge))
    L.execute('HELD.PADDUP = nil; TICK(0.1); SECS = 0.373')
    # the 1531 audit: a WINDOW taking the pad (the bars unusable: the bar reads no buttons then) mid-press: the ring goes, it never
    # keeps filling on the last read (the router's kept flag can stay up while the window has the release)
    L.execute('KEPT[2] = true; HELD.PADDUP = true; TICK(0.4)')
    n0 = lit(2)
    L.execute('CORE_ONLY = false; TICK(0.5)')
    chk("2. a window took the pad mid-press: the ring gone, not charged", n0 and not lit(2) and b[2].hcharge == 0, (n0, lit(2), b[2].hcharge))
    L.execute('CORE_ONLY = true; HELD.PADDUP = nil; TICK(0.1)')

    # ------------------------------------------------------------------------------------------------ (3) the takeover
    tap_icon = b[2].tapIcon
    L.execute('WC = WowCompanion; WC.gcdRun = nil; WC.holdFired(2); TICK(0.2)')     # (nbar31: the morph in, 0.18 s)
    # nbar25 (the user on test 1532: "... so its not confusing"): the badge FADES OUT while the face has the hold spell - never the tap
    # spell on it (nbar21's swap retired)
    chk("3. the hold fired: the hold spell takes the big face; the badge faded out (its own hold spell, never the tap spell)",
        b[2].hto is not None and b[2].hflash.shown and b[2].hflash.tex == 136048 and b[2].hb.icon.tex == 136048 and b[2].hb.icon.tex != tap_icon
        and b[2].hb.alpha == 0 and b[2].hflash.alpha == 1, (b[2].hflash.tex, b[2].hb.icon.tex, tap_icon, b[2].hb.alpha))
    chk("3. nbar25: in the game's gold CHECKED ring (b.hsel: the squareslot's border-selected atlas, 4 units beyond), shown with the face",
        b[2].hsel is not None and b[2].hsel.shown and b[2].hsel.atlas == "gamepad-actionbar-squareslot-border-selected" and b[2].hsel.alpha == 1
        and b[2].hsel.w == b[2].sz + 8, (b[2].hsel and b[2].hsel.atlas, b[2].hsel and b[2].hsel.shown))
    chk("3. ... the glowing ring hands over at the fire (hidden)", not lit(2))
    L.execute('TICK(0.25)')
    chk("3. the face's sweep is the hold spell's own (its cooldown object)", (b[2].cd.dur or {}).get("id") == 403 if b[2].cd.dur else True)
    L.execute('WC.holdCast(2, "cast", 1.8); WC.gcdRun = { s = NOW, d = 1.5 }; TICK(1.5)')
    chk("3. a 1.8 s cast: still on the face after 1.5 s (the GCD is shorter)", b[2].hto is not None and b[2].hflash.alpha == 1)
    L.execute('TICK(0.3); TICK(0.02, 0.01)')                            # (nbar31: the face hands back early in the morph)
    chk("3. the cast ended: easing back (0 < alpha < 1)", b[2].hto is not None and 0 < b[2].hflash.alpha < 1, b[2].hflash.alpha)
    chk("3. ... its golden ring eases with it", b[2].hsel.shown and b[2].hsel.alpha == b[2].hflash.alpha, (b[2].hsel.alpha, b[2].hflash.alpha))
    L.execute('TICK(0.25)')
    chk("3. ... back: the tap spell on the face, the hold spell's badge in full again", b[2].hto is None and not b[2].hflash.shown
        and b[2].hb.icon.tex == 136048 and b[2].hb.alpha == 1, (b[2].hto, b[2].hb.icon.tex, b[2].hb.alpha))
    chk("3. ... and no golden ring", not b[2].hsel.shown)
    # nbar25: A QUEUED next-swing hold (Raptor Strike's normal look): kept past the 2 s fallback while the game says it is current
    L.execute('WC.gcdRun = nil; CURRENT[403] = true; WC.holdFired(2); WC.holdCast(2, "q"); TICK(2.3)')
    chk("3. queued (current, PadMap's q): still on the face with its golden ring after 2.3 s (no 2 s fallback)",
        b[2].hto is not None and b[2].hflash.shown and b[2].hsel.shown and b[2].hflash.alpha == 1, (b[2].hto is not None, b[2].hflash.alpha))
    L.execute('TICK(3)')
    chk("3. ... 5.3 s: still queued, still on", b[2].hto is not None and b[2].hflash.alpha == 1)
    L.execute('CURRENT[403] = nil; WC.holdCast(2, "ok"); TICK(0.2)')
    mid = b[2].hto is not None and b[2].hflash.alpha == 1
    L.execute('TICK(0.35)')
    chk("3. ... the swing landed (no longer current, ok): on for 0.3 s more, then back", mid and b[2].hto is None and not b[2].hsel.shown, mid)
    L.execute('CURRENT[403] = true; WC.holdFired(2); TICK(1); CURRENT[403] = nil; TICK(0.15)')
    grace = b[2].hto is not None and b[2].hflash.alpha == 1
    L.execute('TICK(0.35)')
    chk("3. queued by the bar's own read, then unqueued with no swing: on for PadMap's 0.2 s wait for the landing's word (the fix pass), "
        "then back", grace and b[2].hto is None, (grace, b[2].hto is not None))
    # nbar25: an instant that gave no GCD, no cast and no queue: PadMap's none - back at once
    L.execute('WC.holdFired(2); TICK(0.15); WC.holdCast(2, "none"); TICK(0.25)')
    chk("3. none (no GCD, no cast, no queue at 0.15 s): back at once", b[2].hto is None and not b[2].hflash.shown)
    L.execute('WC.holdFired(2); WC.holdCast(2, "gcd", nil, 1.4); TICK(1.3)')
    on13 = b[2].hto is not None and b[2].hflash.alpha == 1
    L.execute('TICK(0.35)')
    chk("3. gcd (gl 1.4): on the face to the GCD's end, then back", on13 and b[2].hto is None, on13)
    # an instant: to the GCD's end
    L.execute('WC.holdFired(2); WC.gcdRun = { s = NOW, d = 1.5 }; WC.holdCast(2, "ok"); TICK(1.4)')
    chk("3. an instant: on the face through its GCD (1.4 s of 1.5)", b[2].hto is not None and b[2].hflash.alpha == 1)
    L.execute('TICK(0.4)')
    chk("3. ... the GCD done: back", b[2].hto is None)
    # refused / interrupted: back at once
    L.execute('WC.holdFired(2); TICK(0.1); WC.holdCast(2, "fail"); TICK(0.25)')
    chk("3. fail (refused / interrupted): back at once (within the 0.2 s ease)", b[2].hto is None and not b[2].hflash.shown)
    # no word at all: 2 s, never stuck
    L.execute('WC.gcdRun = nil; WC.holdFired(2); TICK(1.9)')
    still = b[2].hto is not None
    L.execute('TICK(0.35)')
    chk("3. no word at all: 2 s, then back (never stuck)", still and b[2].hto is None)
    # a GCD older than the fire is not the hold's
    L.execute('WC.gcdRun = { s = NOW - 3, d = 10 }; WC.holdFired(2); TICK(2.3)')
    chk("3. an older GCD does not keep it", b[2].hto is None)
    # THE FIX PASS (two reviews of round 1533)
    # a hold QUEUED in the game's spell-queue window (an instant fired in the last GCD's end: current, PadMap's q) that goes off: its ok,
    # then its OWN GCD keeps the face (as Home's takeover) - not ok + 0.3 s
    L.execute('WC.gcdRun = nil; CURRENT[403] = true; WC.holdFired(2); WC.holdCast(2, "q"); TICK(0.3); CURRENT[403] = nil; '
              'WC.gcdRun = { s = NOW, d = 1.5 }; WC.holdCast(2, "ok"); TICK(1.3)')
    chk("3. queued, then off with its own GCD: on the face through that GCD (1.3 s of 1.5; was gone at ok + 0.3 s)",
        b[2].hto is not None and b[2].hflash.alpha == 1 and b[2].hsel.shown, (b[2].hto is not None, b[2].hflash.alpha))
    L.execute('TICK(0.45)')
    chk("3. ... its GCD done: back", b[2].hto is None)
    # the live read flips first (no longer current a tick before the landing's ok): no ease-and-pop - the face stays, then its GCD
    L.execute('WC.gcdRun = nil; CURRENT[403] = true; WC.holdFired(2); TICK(1); CURRENT[403] = nil; TICK(0.1)')
    flick = b[2].hto is not None and b[2].hflash.alpha == 1
    L.execute('WC.gcdRun = { s = NOW, d = 1.5 }; WC.holdCast(2, "ok"); TICK(1.3)')
    chk("3. the live read off a tick before ok: no flicker (alpha 1 throughout), then the GCD keeps it", flick and b[2].hto is not None
        and b[2].hflash.alpha == 1, (flick, b[2].hto is not None))
    L.execute('TICK(0.45)')
    # a queued hold that went off with no GCD of its own (a next-swing ability that landed): PadMap's none - back at once
    L.execute('WC.gcdRun = nil; CURRENT[403] = true; WC.holdFired(2); WC.holdCast(2, "q"); TICK(1); CURRENT[403] = nil; '
              'WC.holdCast(2, "none"); TICK(0.25)')
    chk("3. queued, then none (it landed with no GCD of its own): back at once", b[2].hto is None and not b[2].hsel.shown)
    # a long queue that lands: its caps from the landing (not 15 s from the fire)
    L.execute('WC.gcdRun = nil; CURRENT[403] = true; WC.holdFired(2); WC.holdCast(2, "q"); TICK(16); CURRENT[403] = nil; '
              'WC.gcdRun = { s = NOW, d = 1.5 }; WC.holdCast(2, "ok"); TICK(1)')
    chk("3. queued 16 s, then off with its GCD: still on 1 s later (the 15 s cap counts from the queue's end)",
        b[2].hto is not None and b[2].hflash.alpha == 1, b[2].hto is not None)
    L.execute('TICK(0.75)')
    # the GCD look-back: an interrupt (off the GCD) fired 0.3 s after a D-pad spell's GCD began - that GCD is not the hold's
    L.execute('WC.gcdRun = { s = NOW - 0.3, d = 1.5 }; WC.holdFired(2); TICK(0.1); WC.holdCast(2, "ok"); TICK(0.55)')
    chk("3. a GCD that began 0.3 s before the fire is not the hold's (the look-back is the click's own frame, 0.05 s): ok + 0.3 s, back",
        b[2].hto is None, b[2].hto is not None)
    L.execute('WC.gcdRun = { s = NOW - 0.02, d = 1.5 }; WC.holdFired(2); WC.holdCast(2, "ok"); TICK(1.3)')
    chk("3. ... one that began in the fire's own frame is", b[2].hto is not None and b[2].hflash.alpha == 1)
    L.execute('WC.gcdRun = nil; TICK(0.5)')
    # the NEXT spell's GCD (the player's next press goes off right at the hold's GCD's end, the game's spell-queue window) is not the
    # hold's: no pop back to the face while it eases out
    L.execute('WC.holdFired(2); WC.gcdRun = { s = NOW, d = 1.5 }; WC.holdCast(2, "ok"); TICK(1.55); WC.gcdRun = { s = NOW, d = 1.5 }; TICK(0.3)')
    chk("3. the next spell's GCD, begun at the hold's GCD's end: not the hold's (back, no pop)", b[2].hto is None, b[2].hto is not None)
    # ... an instant queued in the spell-queue window, off 0.4 s after the fire: its own GCD
    L.execute('WC.gcdRun = nil; TICK(0.5); WC.holdFired(2); TICK(0.4); WC.gcdRun = { s = NOW, d = 1.5 }; WC.holdCast(2, "ok"); TICK(1.3)')
    chk("3. ... a GCD 0.4 s after the fire (the spell-queue window): its own", b[2].hto is not None and b[2].hflash.alpha == 1)
    L.execute('WC.gcdRun = nil; TICK(0.6)')
    # the 1531 audit: THE TAKEOVER IS THE FIRED LAYER'S (PadMap passes it: WC.holdFired(pos, lay)) - LT + X's hold fired with LT let go
    # a moment before X: the face never shows the bare X's hold; it shows LT + X's own hold while the bar shows the LT layer (as Home's
    # band: HoldCast.takeover is per layer), and it still ends on time
    L.execute('''HOLDS2 = { [5] = { n = "Chain Lightning", id = 421, i = 136015 } }
local pm = WowCompanion.padMap
pm.holdAt = function(page, set, pos, tab) if set == 1 then return HOLDS[pos] elseif set == 2 then return HOLDS2[pos] end end
WC.gcdRun = nil; LT = nil; TICK(0.1); WC.holdFired(5, "lt"); TICK(0.1)''')
    # RULING A (the user's pick 2026-11-25, "Stay until it ends (Recommended)"): a trigger layer's hold keeps its takeover on the main
    # circle after the trigger is let go - the bare layer on the bar shows LT + X's OWN hold spell (never the bare X's) until it ends
    chk("3. LT + X's hold fired with the bare layer on the bar (LT let go): LT + X's own hold spell keeps the face (ruling A)",
        b[5].hto is not None and b[5].hflash.shown and b[5].hflash.tex == 136015, (b[5].hto is not None, b[5].hflash.shown, b[5].hflash.tex))
    L.execute('LT = true; TICK(0.1)')
    chk("3. ... the LT layer on the bar: LT + X's own hold spell has the face", b[5].hflash.shown and b[5].hflash.tex == 136015,
        (b[5].hflash.shown, b[5].hflash.tex))
    L.execute('LT = nil; TICK(0.1)')
    chk("3. ... LT let go again: still on the face (ruling A)", b[5].hflash.shown and b[5].hflash.tex == 136015, (b[5].hflash.shown, b[5].hflash.tex))
    L.execute('RT = true; TICK(0.1)')
    chk("3. ... RT held instead: RT's own buttons (a trigger the press did not have)", not b[5].hflash.shown, b[5].hflash.shown)
    L.execute('RT = nil; TICK(2.3)')
    chk("3. ... and it ends on time (no word: 2 s)", b[5].hto is None and not b[5].hflash.shown)
    # the badge while the takeover has the bare layer's face: the bare X's TAP spell on it (the face's own picture is under the takeover)
    L.execute('WC.holdFired(5, "lt"); TICK(0.2)')
    chk("3. LT + X fired, the bare layer on the bar: the bare badge faded out (nbar25: never the tap spell on it)",
        b[5].hb.alpha == 0 and b[5].hb.icon.tex == 135825, (b[5].hb.icon.tex, b[5].hb.alpha))
    L.execute('TICK(2.5)')
    chk("3. ... ended: the bare badge back in full, the bare X's hold spell", b[5].hto is None and b[5].hb.icon.tex == 135825 and b[5].hb.alpha == 1,
        (b[5].hb.icon.tex, b[5].hb.alpha))
    L.execute('LT = nil; TICK(0.1)')
    L.execute('WC.holdFired(5, "bare"); TICK(0.1)')
    chk("3. the bare X's own hold, fired on the bare layer: its spell on the face", b[5].hflash.shown and b[5].hflash.tex == 135825, b[5].hflash.tex)
    L.execute('TICK(2.5)')
    # the 1531 audit: the charge ring is the PRESS's layer's: X kept on the bare layer, then LT held - the LT + X badge never charges
    # (the release will run the bare X's hold), the bare one again once LT is let go
    L.execute('KEPT[5] = true; HELD.PAD3 = true; TICK(0.4)')
    nb = b[5].hb.ring.shown is True
    L.execute('LT = true; TICK(0.1)')
    nl = b[5].hb.ring.shown is True
    L.execute('LT = nil; TICK(0.1)')
    nb2 = b[5].hb.ring.shown is True
    chk("2. the ring is the press's layer's (bare X kept, LT held after: no ring on LT + X's badge, back on the bare one)",
        nb and not nl and nb2, (nb, nl, nb2))
    L.execute('HELD.PAD3 = nil; KEPT[5] = nil; TICK(0.1)')

    # THE FIX PASS: the checked ring's fallbacks - never a disc over the face. The selected atlas refused: the button's own border ring at
    # the checked ring's size, desaturated and gold; that refused too: a gold plate UNDER the face (sublevel -1 < its 0), round only on
    # the faces (the D-pad a square)
    L2, G2 = boot('REFUSED = { ["gamepad-actionbar-circleslot-border-selected"] = true, ["gamepad-actionbar-squareslot-border-selected"] = true }')
    f2 = G2.WowCompanion.nbar.f
    r6, r2 = f2.btn[6].hsel, f2.btn[2].hsel
    chk("3. the selected atlas refused: the border ring (circle / square), desaturated, gold, at the checked ring's size - a ring",
        r6.atlas == "gamepad-actionbar-circleslot-border-normal" and r2.atlas == "gamepad-actionbar-squareslot-border-normal"
        and r6.desat is True and [round(float(v), 2) for v in r6.vc.values()] == [1.0, 0.86, 0.45] and r6.color is None
        and r6.w == f2.btn[6].sz + 8 and f2.btn[6].hselFallback == "ring", (r6.atlas, r2.atlas, r6.color))
    L2.execute('WowCompanion.holdFired(6); TICK(0.1)')
    chk("3. ... shown with the face", f2.btn[6].hflash.shown and r6.shown)
    L3, G3 = boot('REFUSED = { ["gamepad-actionbar-circleslot-border-selected"] = true, ["gamepad-actionbar-squareslot-border-selected"] = true, '
                  '["gamepad-actionbar-circleslot-border-normal"] = true, ["gamepad-actionbar-squareslot-border-normal"] = true }')
    f3 = G3.WowCompanion.nbar.f
    b6, b2 = f3.btn[6], f3.btn[2]
    chk("3. both refused: a gold plate UNDER the face (OVERLAY -1 below the hold icon's 0), round on Y (the circle mask), square on the D-pad",
        b6.hselFallback == "under" and [round(float(v), 2) for v in b6.hsel.color.values()][:3] == [1.0, 0.86, 0.45]
        and b6.hsel.layer == "OVERLAY" and b6.hsel.sub == -1 and b6.hflash.sub == 0 and b6.hsel.sub < b6.hflash.sub
        and b6.hsel.masks is not None and b2.hsel.masks is None and b2.hsel.sub == -1,
        (b6.hselFallback, b6.hsel.layer, b6.hsel.sub, b6.hflash.sub, b6.hsel.masks is not None, b2.hsel.masks is not None))

    # ------------------------------------------------------------------------------------------------ (4) nothing secure
    chk("4. plain frames only (no Secure template), nothing bound or set", list(G.BADTPL.values()) == [] and list(G.SPY.values()) == [],
        (list(G.BADTPL.values()), list(G.SPY.values())))

    face_follows_the_hold()
    the_morph()
    the_morph_in_combat()

    if "--shots" in sys.argv:
        shots(sys.argv[sys.argv.index("--shots") + 1])
    if "--morph-shots" in sys.argv:
        morph_shots(sys.argv[sys.argv.index("--morph-shots") + 1])

    print("\n%d failure(s)" % len(FAILS))
    return 1 if FAILS else 0


# the tap at Y (6) is Flame Shock (8050) and its hold Lightning Bolt (403): NB.resolve's pick for Y, PadMap's hold view
FACE_PRE = '''WC = WowCompanion; WC.gcdRun = nil
local old = WC.nbar.resolve
WC.nbar.resolve = function(set, p, ...) if p == 6 then return { kind = "spell", id = 8050, icon = 135813 } end return old(set, p, ...) end
HOLDS[6] = { n = "Lightning Bolt", id = 403, i = 136048 }
'''


def face_follows_the_hold():
    """(5) THE FACE SAYS THE SPELL IT SHOWS (nbar25; the user on test 1532, 2026-11-25: "When using hold to cast it will show the button
    glyph as red if the original spell was not usable, I want that to also shift with the ability that its showing casting so its not
    confusing"): while the takeover has the face, the glyph / word red, b.range, the face's own shade (b.hflash) and the count are the
    HOLD spell's; the badge fades out (the tap spell is never on it); the takeover over, the tap's again at once"""
    L, G = boot()
    NB = G.WowCompanion.nbar
    L.execute(FACE_PRE + 'SPELLS[8050].range = false; SPELLS[403].range = nil; TICK(0.3)')
    b6 = NB.f.btn[6]

    def gvc(bt):
        return tuple(round(float(v), 2) for v in bt.glyph.vc.values()) if bt.glyph.vc else None

    def fvc(bt):
        return tuple(round(float(v), 2) for v in bt.hflash.vc.values()) if bt.hflash is not None and bt.hflash.vc else None

    red, white = (1.0, 0.2, 0.2), (1.0, 1.0, 1.0)
    chk("5. the tap (Flame Shock) out of range, no takeover: the glyph red", b6.range is False and gvc(b6) == red, (b6.range, gvc(b6)))
    L.execute('WC.holdFired(6)')
    chk("5. the hold fired (Lightning Bolt in range): the glyph white AT ONCE - the hold spell's range (b.range true)",
        b6.hto is not None and b6.range is True and gvc(b6) == white, (b6.range, gvc(b6)))
    chk("5. ... nbar31: the badge starts the morph from its corner in full (no jump), the hold spell's own icon (never the tap spell)",
        b6.hb.alpha == 1 and b6.hmV == 0 and b6.hb.icon.tex == 136048, (b6.hb.alpha, b6.hmV, b6.hb.icon.tex))
    L.execute('TICK(0.2)')
    chk("5. ... the morph in: the badge FADES OUT into the face (alpha 0)", b6.hb.alpha == 0 and b6.hflash.shown, (b6.hb.alpha, b6.hflash.shown))
    L.execute('TICK(0.3)')
    chk("5. ... after the bar's own redraws: still the hold's (white), the badge still out", b6.range is True and gvc(b6) == white
        and b6.hb.alpha == 0 and b6.hb.icon.tex == 136048, (b6.range, gvc(b6), b6.hb.alpha, b6.hb.icon.tex))
    chk("5. ... the face (b.hflash) wears the hold's shade: plain (ready)", fvc(b6) == white and b6.hflash.desat is False, (fvc(b6), b6.hflash.desat))
    L.execute('SPELLS[403].nomana = true; TICK(0.3)')
    chk("5. the hold without the mana: the FACE blue (0.45, 0.55, 1), the glyph not red", fvc(b6) == (0.45, 0.55, 1.0) and gvc(b6) == white,
        (fvc(b6), gvc(b6)))
    L.execute('SPELLS[403].nomana = nil; SPELLS[403].usable = false; TICK(0.3)')
    chk("5. the hold not usable: the face grey, desaturated", fvc(b6) == (0.45, 0.45, 0.45) and b6.hflash.desat is True, (fvc(b6), b6.hflash.desat))
    L.execute('SPELLS[403].usable = nil; SPELLS[403].range = false; TICK(0.3)')
    chk("5. the hold out of range: the glyph red (the hold's range)", b6.range is False and gvc(b6) == red, (b6.range, gvc(b6)))
    L.execute('SPELLS[403].range = nil; TICK(0.3)')
    # the count: the hold spell's (none) over its face, never the tap's
    L.execute('local b = WC.nbar.f.btn[6]; b.tapSt = { cnt = 3 }; WC.nbar.faceLook(b)')
    chk("5. the count over the hold's face is the hold spell's (none), not the tap's 3", b6.count.text == "", b6.count.text)
    L.execute('TICK(2.5)')
    chk("5. the takeover over: the tap's red glyph again, the badge back (alpha 1, the hold spell's icon)",
        b6.hto is None and b6.range is False and gvc(b6) == red and b6.hb.alpha == 1 and b6.hb.icon.tex == 136048,
        (b6.hto is not None, b6.range, gvc(b6), b6.hb.alpha, b6.hb.icon.tex))
    L.execute('local b = WC.nbar.f.btn[6]; b.tapSt = { cnt = 3, r = 0 }; WC.nbar.faceLook(b)')
    chk("5. ... and the tap's count", b6.count.text == "3", b6.count.text)
    L.execute('TICK(0.3)')
    # the other way round: the tap in range, the hold out of range - red only while the takeover has the face
    L.execute('SPELLS[8050].range = nil; SPELLS[403].range = false; TICK(0.3)')
    chk("5. the tap in range: white", b6.range is True and gvc(b6) == white, gvc(b6))
    L.execute('WC.holdFired(6)')
    chk("5. ... the hold (out of range) fired: red at once", b6.range is False and gvc(b6) == red, gvc(b6))
    L.execute('TICK(1.0)')
    mid = gvc(b6)
    L.execute('TICK(1.5)')
    chk("5. ... red through the takeover, white again after it", mid == red and b6.hto is None and gvc(b6) == white and b6.range is True,
        (mid, gvc(b6)))
    # easing back: the badge comes back as the face goes (1 - the face's alpha)
    L.execute('WC.holdFired(6); TICK(2.0); TICK(0.02, 0.01)')
    ea = (float(b6.hflash.alpha), float(b6.hb.alpha)) if b6.hto is not None else None
    chk("5. easing back: the badge fades in as the face fades out (alpha 1 - the face's)", ea is not None and 0 < ea[0] < 1
        and abs(ea[1] - (1 - ea[0])) < 1e-9, ea)
    L.execute('TICK(0.5)')
    # a trigger layer's takeover (ruling A) shown on the bare layer: the bare badge out too; a layer that does not show it: the badge in
    L.execute('''HOLDS2 = { [5] = { n = "Chain Lightning", id = 421, i = 136015 } }
local pm = WowCompanion.padMap
pm.holdAt = function(page, set, pos, tab) if set == 1 then return HOLDS[pos] elseif set == 2 then return HOLDS2[pos] end end
LT = nil; TICK(0.1); WC.holdFired(5, "lt"); TICK(0.2)''')
    b5 = NB.f.btn[5]
    out5 = b5.hb.alpha
    L.execute('RT = true; TICK(0.1)')
    in5 = b5.hb.alpha
    L.execute('RT = nil; TICK(2.5)')
    chk("5. LT + X's takeover on the bare layer: the bare badge out; RT's layer (no takeover there): its badge in", out5 == 0 and in5 == 1,
        (out5, in5))
    chk("5. nothing secure, nothing bound or set", list(G.BADTPL.values()) == [] and list(G.SPY.values()) == [])


# nbar31 THE HOLD MORPH: the frames' own record of where our frames go (only ours: the stub's frames), at 60 fps
MORPH_PRE = r"""
local FM = getmetatable(UIParent)
MOVED = {}
local sp, ss = FM.SetPoint, FM.SetScale
FM.SetPoint = function(self, ...) MOVED[#MOVED + 1] = self; return sp(self, ...) end
FM.SetScale = function(self, k) MOVED[#MOVED + 1] = self; return ss(self, k) end
"""
FPS = 1 / 60.0


def the_morph():
    """(6) nbar31 THE HOLD MORPH (the user, 2026-11-27: "I want the button hold to transition that small circle into the big one and then
    back after the cast is complete or canceled"): at the takeover the badge grows and moves from its corner into the big face, every
    frame (not the bar's 0.05 s poll), its rim turning gold, while the tap's art shrinks and fades; the checked face takes over at its
    end; back when it ends (the cast over, a fail); a turn mid-way never jumps; a new press mid-way back retargets"""
    L, G = boot(MORPH_PRE)
    NB = G.WowCompanion.nbar
    L.execute('WC = WowCompanion; WC.gcdRun = nil')
    b = NB.f.btn[8]
    x0, y0 = float(b.hbx), float(b.hby)
    big = (float(b.sz) - 2 * float(NB.INSET)) / float(NB.HOLD_R)

    def st():
        pt = b.hb.pts[1]
        sc = float(b.hb.scale or 1)
        return {"m": float(b.hmV or 0), "x": float(pt[4]) * sc, "y": float(pt[5]) * sc, "s": sc, "a": float(b.hb.alpha),
                "fa": float(b.hflash.alpha) if b.hflash.shown else 0.0, "ti": float(b.tapIn or NB.INSET), "ta": float(b.icon.alpha),
                "ring_s": float(b.hb.ring.scale or 1), "disc": [round(float(v), 3) for v in list(b.hb.disc.color.values())[:3]]}

    chk("6. at rest: the badge in its corner at its own size, brown; the tap in full", b.hmV in (None, 0) and float(b.hb.scale or 1) == 1
        and b.hb.alpha == 1 and float(b.icon.alpha) == 1, (b.hmV, b.hb.scale, b.hb.alpha))
    chk("6. the timings: in 0.18 s, back 0.2 s (the takeover's own ease), the face from 0.6", float(NB.HOLD_MORPH_IN) == 0.18
        and float(NB.HOLD_MORPH_OUT) == 0.2 == float(NB.HOLD_EASE) and float(NB.HOLD_MORPH_FACE) == 0.6)
    L.execute('WC.holdFired(8); WC.holdCast(8, "cast", 2.0)')
    s0 = st()
    chk("6. the fire: no jump - the badge still in its corner, its size, in full; the face not shown yet", s0["m"] == 0 and abs(s0["x"] - x0) < 1e-6
        and abs(s0["y"] - y0) < 1e-6 and s0["s"] == 1 and s0["a"] == 1 and s0["fa"] == 0 and s0["ta"] == 1, s0)
    seq = [s0]
    for _ in range(14):
        L.execute('TICK(%r, %r)' % (FPS, FPS))
        seq.append(st())
    ms = [q["m"] for q in seq]
    chk("6. every frame (60 fps), not the bar's 0.05 s poll: the morph moves on each of the first frames", all(ms[i + 1] > ms[i] for i in range(8)), ms[:9])
    chk("6. smooth: no frame-to-frame step over 0.3 (the ease-out's own first frame)", max(ms[i + 1] - ms[i] for i in range(len(ms) - 1)) < 0.3,
        [round(v, 3) for v in ms])
    mid = min(seq, key=lambda q: abs(q["m"] - 0.5))
    chk("6. half way: the badge half way to the middle, half way to the face's size", abs(mid["x"] - x0 * (1 - mid["m"])) < 1e-6
        and abs(mid["s"] - (1 + (big - 1) * mid["m"])) < 1e-6 and abs(mid["ring_s"] - mid["s"]) < 1e-9, mid)
    chk("6. ... its glowing ring scales with it (the gold follows the moving circle); its rim turning gold", mid["ring_s"] > 1
        and mid["disc"] != [round(110 / 255, 3), round(89 / 255, 3), round(66 / 255, 3)], mid)
    chk("6. ... the tap's art smaller and fading", mid["ti"] > float(NB.INSET) and mid["ta"] < 1, mid)
    chk("6. ... the face not yet (below 0.6), then taking over", mid["fa"] == 0 and any(0 < q["fa"] < 1 for q in seq), [q["fa"] for q in seq])
    L.execute('TICK(0.1)')
    s1 = st()
    chk("6. in by 0.18 s: the hold's checked face in full, the badge out (at the middle, the face's size), the tap gone",
        s1["m"] == 1 and s1["fa"] == 1 and s1["a"] == 0 and abs(s1["x"]) < 1e-9 and abs(s1["s"] - big) < 1e-9 and s1["ta"] == 0
        and abs(s1["ti"] - (float(NB.INSET) + float(NB.HOLD_TAP_SHRINK) * (float(b.sz) / 2 - float(NB.INSET)))) < 0.05, s1)
    chk("6. ... the gold checked ring with the face", b.hsel.shown and float(b.hsel.alpha) == 1)
    # the cast's end (2.0 s): back
    L.execute('TICK(1.2)')
    chk("6. mid cast: still the big face", st()["m"] == 1)
    L.execute('TICK(0.47, %r); TICK(%r, %r)' % (FPS, FPS, FPS))
    back = []
    for _ in range(16):
        back.append(st())
        L.execute('TICK(%r, %r)' % (FPS, FPS))
    bm = [q["m"] for q in back]
    chk("6. the cast over: back - monotonic down, no jump", all(bm[i + 1] <= bm[i] for i in range(len(bm) - 1)) and bm[0] > 0.5
        and max(bm[i] - bm[i + 1] for i in range(len(bm) - 1)) < 0.3, [round(v, 3) for v in bm])
    L.execute('TICK(0.1)')
    s2 = st()
    chk("6. home: the takeover over, the badge in its corner at its size, brown, in full; the tap back in full", b.hto is None and s2["m"] == 0
        and abs(s2["x"] - x0) < 1e-6 and s2["s"] == 1 and s2["a"] == 1 and s2["ta"] == 1 and s2["ti"] == float(NB.INSET) and s2["fa"] == 0
        and s2["disc"] == [round(110 / 255, 3), round(89 / 255, 3), round(66 / 255, 3)], s2)
    # a cancel mid-way in: back from where it is (no jump)
    L.execute('WC.holdFired(8); TICK(%r, %r); TICK(%r, %r); TICK(%r, %r)' % (FPS, FPS, FPS, FPS, FPS, FPS))
    v = st()["m"]
    L.execute('WC.holdCast(8, "fail"); TICK(%r, %r)' % (FPS, FPS))
    v2 = st()["m"]
    chk("6. refused mid-way in: back from where it is - no jump, then down", 0.3 < v < 1 and v2 < v and v - v2 < 0.25, (v, v2))
    L.execute('TICK(0.25)')
    chk("6. ... home", b.hto is None and st()["m"] == 0 and st()["ta"] == 1)
    # a new press mid-way back: retargets in from where it is
    L.execute('WC.holdFired(8); TICK(0.2); WC.holdCast(8, "none"); TICK(%r, %r); TICK(%r, %r); TICK(%r, %r)' % (FPS, FPS, FPS, FPS, FPS, FPS))
    v = st()["m"]
    L.execute('WC.holdFired(8)')
    v1 = st()["m"]
    L.execute('TICK(%r, %r)' % (FPS, FPS))
    v2 = st()["m"]
    chk("6. a new press mid-way back: retargets in from the picture shown (no jump), then up", 0 < v < 1 and abs(v1 - v) < 1e-9 and v2 > v1,
        (v, v1, v2))
    L.execute('TICK(0.2)')
    chk("6. ... in again", st()["m"] == 1)
    L.execute('WC.holdCast(8, "fail"); TICK(0.3)')
    # a tap that ran (the fill's look, opt): it eases out through the morph too
    L.execute('WC.holdFired(8, nil, true); TICK(0.2); WC.holdTapped(8); TICK(%r, %r); TICK(%r, %r)' % (FPS, FPS, FPS, FPS))
    v = st()["m"]
    L.execute('TICK(0.3)')
    chk("6. the fill's casting look, the tap ran: back through the morph", 0 < v < 1 and b.hto is None and st()["m"] == 0, v)
    # the source: the morph's functions call nothing protected
    import re
    src = open(tnb.AB, encoding="utf-8").read()
    bad = []
    for name in ("NB.morphVal", "NB.morphMoving", "NB.morphTo", "NB.smooth", "NB.discCol", "NB.hbPlace", "NB.iconAlpha", "NB.tapMorph",
                 "NB.morphApply", "NB.morphFrame", "NB.holdOver"):
        m = re.search(r"\nfunction " + name.replace(".", r"\.") + r"\(.*?\nend", src.replace("\r\n", "\n"), re.S)
        if not m:
            bad.append(name + " missing")
            continue
        for word in ("SetBinding", "SetCVar", "CreateMacro", "EditMacro", "SetAttribute", ":Click", "RegisterStateDriver", "SecureHandler",
                     "PickupAction", "PlaceAction", "SetOverride", "InCombatLockdown", "RegisterAttributeDriver"):
            if word in m.group(0):
                bad.append(name + ": " + word)
    chk("6. the morph's functions call nothing protected (no attribute / binding / CVar / macro / click / state driver)", not bad, bad)
    chk("6. ... and the tick drives it every frame, before the 0.05 s poll", "    if NB.morphs then pcall(NB.morphFrame) end" in src
        and src.index("    if NB.morphs then pcall(NB.morphFrame) end") < src.index("    if NB.acc < NB.POLL_S then return end"))


def the_morph_in_combat():
    """(7) nbar31 IN A FIGHT: the whole morph (in, a cast, back; a cancel; another button) with the lockdown on - only OUR frames move
    (no Secure template among them, no Blizzard frame touched), no attribute / binding / CVar / macro call, nothing raised"""
    L, G = boot(MORPH_PRE)
    L.execute('COMBAT = true; TICK(0.3); MOVED = {}; WC = WowCompanion; WC.gcdRun = nil')
    bliz0 = len(list(G.BLIZ.values()))
    L.execute('WC.holdFired(8); WC.holdCast(8, "cast", 1.0); TICK(1.6, %r)' % FPS)
    L.execute('WC.holdFired(6); TICK(0.1, %r); WC.holdFired(2); TICK(0.05, %r); WC.holdCast(2, "fail"); TICK(0.4, %r)' % (FPS, FPS, FPS))
    L.execute('WC.holdFired(5); TICK(2.5)')
    moved = list(G.MOVED.values())
    bad = [str(m.tpl) for m in moved if m.tpl is not None and "Secure" in str(m.tpl)]
    L.execute('local set = {}; for _, o in ipairs(OURS) do set[o] = true end; NOTOURS = 0; '
              'for _, m in ipairs(MOVED) do if not set[m] then NOTOURS = NOTOURS + 1 end end')
    chk("7. in a fight: the morph moved frames (it ran)", len(moved) > 20, len(moved))
    chk("7. ... every frame it moved or scaled is OUR plain frame or texture (none with a Secure template)", not bad
        and G.NOTOURS == 0, (bad, G.NOTOURS))
    chk("7. ... no Blizzard frame touched, nothing secure made, nothing bound / set, nothing raised",
        len(list(G.BLIZ.values())) == bliz0 and list(G.BADTPL.values()) == [] and list(G.SPY.values()) == [] and G.RAISED == 0,
        (list(G.BLIZ.values())[bliz0:], list(G.BADTPL.values()), list(G.SPY.values()), G.RAISED))
    f = G.WowCompanion.nbar.f
    chk("7. ... and every button home after it (no takeover, the tap in full)", all(f.btn[p].hto is None and float(f.btn[p].hmV or 0) == 0
        and float(f.btn[p].icon.alpha) == 1 for p in (2, 5, 6, 8)), [(f.btn[p].hmV, f.btn[p].icon.alpha) for p in (2, 5, 6, 8)])


def morph_shots(out):
    """nbar31: the morph's frames on the top screen (0 / 25 / 50 / 75 / 100 % in, then back 75 / 50 / 25 / 0 %), the real art, x4"""
    from PIL import Image, ImageDraw
    os.makedirs(out, exist_ok=True)
    r = renderer(out)
    L, G = boot()
    NB = G.WowCompanion.nbar
    bt = NB.f.btn
    L.execute('WC = WowCompanion; WC.gcdRun = nil; SPELLS[370].nomana = nil; KEPT[8] = true; HELD.PAD1 = true; TICK(0.05)')
    while bt[8].hm is None:
        L.execute('TICK(0.05)')
    names = []
    # the fill (nbar30's casting look): the takeover starts with the ring full, still held - the frames at the morph's own values
    t0 = float(bt[8].hm.at)

    def at_in(v):
        return float(NB.HOLD_MORPH_IN) * (1 - (1 - v) ** (1 / 3.0))

    for pc in (0, 25, 50, 75, 100):
        dt = at_in(pc / 100.0) if pc < 100 else 0.25
        L.execute('NOW = %r; WowCompanion.nbar.tick(0.001)' % (t0 + dt))
        n = "top_morph_in_%03d" % pc
        r["frame"](n, [(bt[8], 8, "in %d%% (m %.2f)" % (pc, float(bt[8].hmV or 0)))], 1)
        names.append(n)
    # let go, the cast (0.5 s) runs, then it ends: back
    L.execute('HELD.PAD1 = nil; KEPT[8] = nil; NOW = %r; WC.holdCast(8, "cast", 0.5); TICK(0.1)' % (t0 + 0.3))
    te = t0 + 0.8

    def at_out(v):
        return float(NB.HOLD_MORPH_OUT) * (1 - v ** (1 / 3.0))

    for pc in (100, 75, 50, 25, 0):
        dt = at_out(pc / 100.0) if pc > 0 else 0.25
        L.execute('NOW = %r; WowCompanion.nbar.tick(0.001)' % (te + dt))
        n = "top_morph_out_%03d" % pc
        r["frame"](n, [(bt[8], 8, "back %d%% (m %.2f)" % (pc, float(bt[8].hmV or 0)))], 1)
        names.append(n)
    ims = [Image.open(os.path.join(out, n + ".png")) for n in names]
    w0, h0 = ims[0].width, ims[0].height
    sheet = Image.new("RGBA", (w0 * 5, h0 * 2), (18, 22, 30, 255))
    for i, im in enumerate(ims):
        sheet.paste(im, ((i % 5) * w0, (i // 5) * h0))
    sheet.save(os.path.join(out, "top_morph_sheet.png"))
    # sheet.png: Home's frames (HoldCastShotsTest.theHoldMorphFrames' app_morph_sheet.png, when it is in [out]) over the top screen's
    app = os.path.join(out, "app_morph_sheet.png")
    if os.path.exists(app):
        from PIL import ImageFont
        try:
            fnt = ImageFont.truetype("arial.ttf", 26)
        except Exception:
            fnt = ImageFont.load_default()
        W = 1400
        parts = []
        for title, im in (("Home (bottom screen): in 0 / 25 / 50 / 75 / 100 %, then back 100 / 75 / 50 / 25 / 0 %", Image.open(app)),
                          ("Top screen (the Wings): the same", sheet)):
            im = im.convert("RGBA")
            im = im.resize((W, int(im.height * W / im.width)), Image.LANCZOS)
            parts.append((title, im))
        H = sum(44 + im.height for _, im in parts)
        out_im = Image.new("RGBA", (W, H), (18, 22, 30, 255))
        y = 0
        d = ImageDraw.Draw(out_im)
        for title, im in parts:
            d.text((12, y + 8), title, fill=(236, 224, 196, 255), font=fnt)
            out_im.paste(im, (0, y + 44))
            y += 44 + im.height
        out_im.save(os.path.join(out, "sheet.png"))
    print("morph shots in", out)


# ==================================================================================================== the renders (real art)
ART = os.environ.get("CLOCK_ART_ROOT", "E:/dsf-issues/bottom-artroot")


class Pak:
    def __init__(self, path):
        self.data = open(path, "rb").read()
        magic, n, base = struct.unpack_from("<III", self.data, 0)
        assert magic == 0x31494357
        self.ids = list(struct.unpack_from("<%dI" % n, self.data, 12))
        self.offs = list(struct.unpack_from("<%dI" % (n + 1), self.data, 12 + 4 * n))
        self.base = base

    def get(self, fid):
        i = bisect.bisect_left(self.ids, fid)
        if i >= len(self.ids) or self.ids[i] != fid:
            return None
        import io
        from PIL import Image
        return Image.open(io.BytesIO(self.data[self.base + self.offs[i]:self.base + self.offs[i + 1]])).convert("RGBA")


def renderer(out):
    """the bar's frames drawn with the real art (nbar31: the badge's scale and offset, its ring's alpha, the tap's inset and alpha, the
    face's alpha over the tap)"""
    from PIL import Image, ImageDraw, ImageFont
    pak = Pak(os.path.join(ART, "icons.pak"))
    ui = os.path.join(ART, "ui", "cast")
    SC = {"S": 4.0}                                   # px per bar unit (round 1533: 1:1 and x3 for the badge states)

    def set_scale(v):
        SC["S"] = v

    def circ(im, d):
        im = im.resize((d, d), Image.LANCZOS)
        m = Image.new("L", (d, d), 0)
        ImageDraw.Draw(m).ellipse((0, 0, d - 1, d - 1), fill=255)
        o = Image.new("RGBA", (d, d), (0, 0, 0, 0))
        o.paste(im, (0, 0), m)
        return o

    def tint(im, vc, desat, alpha=1.0):
        if desat:
            g = im.convert("LA").convert("RGBA")
            g.putalpha(im.getchannel("A"))
            im = g
        if vc:
            r, gg, bb, a = im.split()
            im = Image.merge("RGBA", (r.point(lambda v: int(v * vc[0])), gg.point(lambda v: int(v * vc[1])), bb.point(lambda v: int(v * vc[2])), a))
        if alpha < 1:
            im = im.copy()
            im.putalpha(im.getchannel("A").point(lambda v: int(v * alpha)))
        return im

    GL = {5: "glyph_nintendo_x.png", 6: "glyph_nintendo_y.png", 7: "glyph_nintendo_b.png", 8: "glyph_nintendo_a.png", 2: "glyph_nintendo_dpad_up.png"}

    def button(img, cx, cy, bt, p, title=None):
        d = int(46 * SC["S"])
        ring = Image.open(os.path.join(ui, "frame_circle_normal.png")).convert("RGBA").resize((d, d), Image.LANCZOS)
        icd = int(38 * SC["S"])
        # nbar31: the TAP's icon at its own inset (it shrinks in the morph) and alpha, then the hold's face over it at the face's alpha
        tp = pak.get(int(bt.icon.tex)) if bt.icon.tex else None
        ta = 1.0 if bt.icon.alpha is None else float(bt.icon.alpha)
        if tp is not None and ta > 0:
            tin = float(bt.tapIn) if bt.tapIn is not None else 3.5
            tdd = max(2, int(icd * (float(bt.sz) - 2 * tin) / (float(bt.sz) - 7)))
            ti = tint(circ(tp, tdd), [float(v) for v in bt.icon.vc.values()] if bt.icon.vc else None, bt.icon.desat is True, ta)
            img.alpha_composite(ti, (int(cx - tdd / 2), int(cy - tdd / 2)))
        face = bt.hto is not None and bt.hflash.shown
        ic = pak.get(int(bt.hflash.tex)) if face and bt.hflash.tex else None
        if ic is not None:
            base = tint(circ(ic, icd), [float(v) for v in bt.hflash.vc.values()] if bt.hflash.vc else None, bt.hflash.desat is True,
                        float(bt.hflash.alpha))                                 # nbar25: the face's own shade (the hold's in a takeover)
            img.alpha_composite(base, (int(cx - icd / 2), int(cy - icd / 2)))
        img.alpha_composite(ring, (int(cx - d / 2), int(cy - d / 2)))
        if bt.hto is not None and bt.hsel is not None and bt.hsel.shown:            # nbar25: the game's checked ring (its atlas art)
            sel = os.path.join(ui, "frame_circle_selected.png" if bt.circle else "frame_square_selected.png")
            ds = int((bt.sz + 8) * SC["S"] * 46 / 44) if bt.circle else int((bt.sz + 8) * SC["S"])
            if os.path.exists(sel):
                si = Image.open(sel).convert("RGBA").resize((ds, ds), Image.LANCZOS)
                si = tint(si, None, False, float(bt.hsel.alpha))
                img.alpha_composite(si, (int(cx - ds / 2), int(cy - ds / 2)))
        g = Image.open(os.path.join(ui, GL[p])).convert("RGBA").resize((int(15 * SC["S"]), int(15 * SC["S"])), Image.LANCZOS)
        if bt.glyph.vc:                                                             # the glyph's red (range only)
            g = tint(g, [float(v) for v in bt.glyph.vc.values()], False)
        img.alpha_composite(g, (int(cx + 13.2 * SC["S"] - 7.5 * SC["S"]), int(cy - 13.2 * SC["S"] - 7.5 * SC["S"])))
        hb = bt.hb
        hba = 1.0 if hb is None or hb.alpha is None else float(hb.alpha)            # nbar25: faded out while the face has the hold
        if hb is not None and hb.shown and hba > 0:
            under = img.copy()
            pt = hb.pts[1]
            hs = float(hb.scale or 1)                                               # nbar31: the morph's scale (the offset in its units)
            bx, by = cx + float(pt[4]) * hs * SC["S"], cy - float(pt[5]) * hs * SC["S"]
            R = 17 * hs * SC["S"] / 2
            dr = ImageDraw.Draw(img)
            rg = hb.ring
            if rg is not None and rg.shown:                                         # nbar25: the glowing ring (glow, track, gold, full)
                from PIL import ImageFilter
                before_ring = img.copy()                                            # nbar31: the ring's own alpha (it fades as it grows)
                rr = (17 + 7) / 2 * hs * SC["S"]
                frac = 1.0 if bt.hcharge == "charged" else float(bt.hcharge or 0)
                # nbar28: the glow's three gold discs of the circle mask beyond the track, under it (hard-edged textures, stacked)
                gl = Image.new("RGBA", img.size, (0, 0, 0, 0))
                gd = ImageDraw.Draw(gl)
                for gi in (3, 2, 1):
                    g = rg.glows[gi]
                    gr = float(g.w) / 2 * hs * SC["S"]
                    gd.ellipse((bx - gr, by - gr, bx + gr, by + gr), fill=(246, 214, 56, int(255 * float(g.alpha))))
                    img.alpha_composite(gl); gl = Image.new("RGBA", img.size, (0, 0, 0, 0)); gd = ImageDraw.Draw(gl)
                ov = Image.new("RGBA", img.size, (0, 0, 0, 0))
                od = ImageDraw.Draw(ov)
                od.ellipse((bx - rr, by - rr, bx + rr, by + rr), fill=(30, 22, 12, 115))
                img.alpha_composite(ov)
                ov = Image.new("RGBA", img.size, (0, 0, 0, 0))
                od = ImageDraw.Draw(ov)
                if rg.full.shown:
                    od.ellipse((bx - rr, by - rr, bx + rr, by + rr), fill=(255, 237, 128, 255))
                elif frac > 0:
                    od.pieslice((bx - rr, by - rr, bx + rr, by + rr), -90, -90 + 360 * frac, fill=(246, 214, 56, 242))
                img.alpha_composite(ov)
                rga = 1.0 if rg.alpha is None else float(rg.alpha)
                if rga < 1:
                    img.paste(Image.blend(before_ring, img, max(0.0, rga)))
                dr = ImageDraw.Draw(img)
            col = tuple(int(float(v) * 255) for v in list(hb.disc.color.values())[:3])
            dr.ellipse((bx - R, by - R, bx + R, by + R), fill=col + (255,))
            hid = hb.icon.tex
            hi = pak.get(int(hid)) if hid else None
            if hi is not None:
                ii = circ(hi, int(2 * (R - 1.5 * hs * SC["S"])))
                ii = tint(ii, [float(v) for v in hb.icon.vc.values()] if hb.icon.vc else None, hb.icon.desat is True)
                img.alpha_composite(ii, (int(bx - ii.width / 2), int(by - ii.height / 2)))
            if hba < 1:
                img.paste(Image.blend(under, img, hba))
        if title:
            ImageDraw.Draw(img).text((cx - 46 * SC["S"] / 2, cy + 30 * SC["S"]), title, fill=(236, 224, 196, 255), font=font(18))

    def font(n):
        try:
            return ImageFont.truetype("arial.ttf", n)
        except Exception:
            return ImageFont.load_default()

    def frame(name, items, w=4):
        img = Image.new("RGBA", (int(w * 70 * SC["S"]), int(110 * SC["S"])), (18, 22, 30, 255))
        for i, (bt, p, title) in enumerate(items):
            button(img, int((i + 0.5) * 70 * SC["S"]), int(45 * SC["S"]), bt, p, title)
        img.save(os.path.join(out, name + ".png"))

    return {"frame": frame, "set_scale": set_scale, "Image": Image}


def shots(out):
    from PIL import Image
    os.makedirs(out, exist_ok=True)
    r = renderer(out)
    frame, set_scale = r["frame"], r["set_scale"]
    L, G = boot()
    f = G.WowCompanion.nbar.f
    bt = {p: f.btn[p] for p in range(1, 9)}
    frame("w1_badges_states", [(bt[2], 2, "ready"), (bt[8], 8, "low mana: blue"), (bt[6], 6, "out of range: red ring"),
                               (bt[5], 5, "not usable: grey")])
    L.execute('KEPT[2] = true; HELD.PADDUP = true; TICK(0.2)')
    frame("w2_charge_0_20s_nothing", [(bt[2], 2, "held 0.20 s")], 1)
    L.execute('TICK(0.2)')
    frame("w3_charge_0_40s_filling", [(bt[2], 2, "held 0.40 s")], 1)
    L.execute('TICK(0.3)')
    frame("w4_charge_0_70s_charged", [(bt[2], 2, "held 0.70 s: charged")], 1)
    L.execute('HELD.PADDUP = nil; TICK(0.1); WC = WowCompanion; WC.holdFired(2); WC.holdCast(2, "cast", 1.8); WC.gcdRun = { s = NOW, d = 1.5 }; TICK(0.6)')
    frame("w5_takeover_mid_cast", [(bt[2], 2, "hold on the face (mid cast)")], 1)
    L.execute('TICK(1.25)')
    frame("w6_takeover_easing_back", [(bt[2], 2, "easing back")], 1)
    L.execute('TICK(0.3)')
    frame("w7_back_to_the_tap", [(bt[2], 2, "back")], 1)
    # ruling A (the user's pick 2026-11-25, "Stay until it ends"): LT + X's hold fired with LT held, then LT let go - the bare layer on
    # the bar keeps LT + X's hold spell on X's face until it ends
    L.execute('''HOLDS2 = { [5] = { n = "Chain Lightning", id = 421, i = 136015 } }
local pm = WowCompanion.padMap
pm.holdAt = function(page, set, pos, tab) if set == 1 then return HOLDS[pos] elseif set == 2 then return HOLDS2[pos] end end
WC.gcdRun = nil; LT = true; TICK(0.1); WC.holdFired(5, "lt"); WC.holdCast(5, "cast", 1.8); TICK(0.3)''')
    frame("w8_lt_x_takeover_lt_held", [(bt[5], 5, "LT + X hold, LT held")], 1)
    L.execute('LT = nil; TICK(0.3)')
    frame("w9_lt_let_go_takeover_stays", [(bt[5], 5, "LT let go: it stays")], 1)
    L.execute('TICK(1.6)')
    frame("w10_cast_over_back_to_bare_x", [(bt[5], 5, "cast over: back")], 1)

    # ---------------------------------------------------------------- round 1533 (nbar25): the spec's set
    L, G = boot()
    f = G.WowCompanion.nbar.f
    bt = {p: f.btn[p] for p in range(1, 9)}
    L.execute("WC = WowCompanion; WC.gcdRun = nil")

    def stitch(name, parts):
        ims = [Image.open(os.path.join(out, x + ".png")) for x in parts]
        w0 = sum(i.width for i in ims)
        img = Image.new("RGBA", (w0, max(i.height for i in ims)), (18, 22, 30, 255))
        x = 0
        for i in ims:
            img.paste(i, (x, 0)); x += i.width
        img.save(os.path.join(out, name + ".png"))
        for x in parts:
            os.remove(os.path.join(out, x + ".png"))

    for scale, sfx in ((1.0, ""), (3.0, "_x3")):
        set_scale(scale)
        frame("_tb1", [(bt[2], 2, "ready"), (bt[8], 8, "low mana"), (bt[5], 5, "not usable"), (bt[6], 6, "out of range")])
        L.execute('SPELLS[370].range = false; SPELLS[3599].range = false; TICK(0.25)')
        frame("_tb2", [(bt[8], 8, "mana + range"), (bt[5], 5, "unusable + range")], 2)
        L.execute('SPELLS[370].range = nil; SPELLS[3599].range = nil; TICK(0.25)')
        stitch("top_badge_states" + sfx, ["_tb1", "_tb2"])
    set_scale(4.0)
    # the glowing ring: 0 % (just shown), 50 %, 100 % (the fire point: full)
    L.execute('KEPT[2] = true; HELD.PADDUP = true; TICK(0.05)')
    while not (bt[2].hb.ring.shown):
        L.execute('TICK(0.05)')
    frame("top_ring_000", [(bt[2], 2, "0 %% (%.2f)" % float(bt[2].hcharge))], 1)
    while float(bt[2].hcharge) < 0.5:
        L.execute('TICK(0.05)')
    frame("top_ring_050", [(bt[2], 2, "50 %% (%.2f)" % float(bt[2].hcharge))], 1)
    while bt[2].hcharge != "charged":
        L.execute('TICK(0.05)')
    frame("top_ring_100", [(bt[2], 2, "100 %: the fire point")], 1)
    L.execute('HELD.PADDUP = nil; KEPT[2] = nil; TICK(0.1)')
    # Raptor Strike's normal look: queued for the next swing (current), past 1532's 2 s fallback, gone after the swing
    L.execute('CURRENT[403] = true; WC.holdFired(2); WC.holdCast(2, "q"); TICK(0.05)')
    frame("top_raptor_queued_t0", [(bt[2], 2, "queued: the fire")], 1)
    L.execute('TICK(2.45)')
    frame("top_raptor_queued_t2_5s", [(bt[2], 2, "queued: 2.5 s")], 1)
    L.execute('CURRENT[403] = nil; WC.holdCast(2, "ok"); TICK(0.55)')
    frame("top_raptor_queued_swing", [(bt[2], 2, "the swing landed: back")], 1)
    # an instant with a GCD (gl 1.46 at the fire): through it, then back; without one: none at 0.15 s
    L.execute('WC.holdFired(2); WC.holdCast(2, "gcd", nil, 1.46); TICK(0.1)')
    frame("top_instant_gcd_t01", [(bt[2], 2, "GCD: 0.1 s")], 1)
    L.execute('TICK(1.1)')
    frame("top_instant_gcd_t12", [(bt[2], 2, "GCD: 1.2 s")], 1)
    L.execute('TICK(0.5)')
    frame("top_instant_gcd_t17", [(bt[2], 2, "GCD over: back")], 1)
    L.execute('WC.holdFired(2); TICK(0.1)')
    frame("top_instant_nogcd_t01", [(bt[2], 2, "no GCD: 0.1 s")], 1)
    L.execute('WC.holdCast(2, "none"); TICK(0.3)')
    frame("top_instant_nogcd_t04", [(bt[2], 2, "none: back")], 1)
    # nbar25 THE FACE SAYS THE SPELL IT SHOWS: Y's tap Flame Shock out of range (its glyph red), its hold Lightning Bolt in range - the
    # glyph white while the hold has the face, the badge out; then the other way round
    L, G = boot()
    f = G.WowCompanion.nbar.f
    bt = {p: f.btn[p] for p in range(1, 9)}
    L.execute(FACE_PRE + 'SPELLS[8050].range = false; SPELLS[403].range = nil; TICK(0.3)')
    frame("top_glyph_tap_out_of_range", [(bt[6], 6, "tap out of range")], 1)
    L.execute('WC.holdFired(6); WC.holdCast(6, "cast", 1.8); TICK(0.3)')
    frame("top_glyph_hold_in_range_takeover", [(bt[6], 6, "hold in range: white")], 1)
    L.execute('SPELLS[403].nomana = true; TICK(0.3)')
    frame("top_glyph_hold_no_mana_takeover", [(bt[6], 6, "hold, no mana: blue")], 1)
    L.execute('SPELLS[403].nomana = nil; TICK(2.0)')
    frame("top_glyph_back_to_the_tap", [(bt[6], 6, "back: the tap's red")], 1)
    L.execute('SPELLS[8050].range = nil; SPELLS[403].range = false; TICK(0.3); WC.holdFired(6); WC.holdCast(6, "cast", 1.8); TICK(0.3)')
    frame("top_glyph_hold_out_of_range_takeover", [(bt[6], 6, "hold out of range: red")], 1)
    print("shots in", out)


if __name__ == "__main__":
    sys.exit(main())
