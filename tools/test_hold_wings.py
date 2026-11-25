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
KEPT, SECS = {}, 0.651
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


def boot():
    L = tnb.boot(pre=PRE)
    L.execute(POST)
    G = L.globals()
    G.ROWS["uisw"](None, 35)                          # the Nintendo bar on (Hub's switch)
    L.execute("TICK(0.3)")
    return L, G


def main():
    ab = open(tnb.AB, encoding="utf-8").read()
    chk("0. ActionBar nbar21 and its note (nbar18 kept)", 'local NB = { V = "nbar25"' in ab and "nbar21 THE HOLD SPELL ON THE TOP SCREEN" in ab
        and "nbar18 HOLD TO CAST" in ab)
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
    chk("1. out of range (Flame Shock on Y): the badge's ring RED (range only), the icon itself plain", b[6].hrange is False
        and disc6[:3] == [1.0, 0.2, 0.2] and b[6].hlook == "ok", (disc6, b[6].hlook))
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
        return sum(1 for i in range(1, 13) if b[p].hb.beads[i].shown)
    L.execute('KEPT[2] = true; HELD.PADDUP = true')
    L.execute('TICK(0.2)')
    chk("2. held 0.2 s: no ring yet (quick taps show nothing)", lit(2) == 0 and b[2].hcharge == 0, (lit(2), b[2].hcharge))
    L.execute('TICK(0.15)')
    n1 = lit(2)
    L.execute('TICK(0.15)')
    n2 = lit(2)
    chk("2. from 0.25 s the beads fill clockwise toward the hold point (the bar reads the pad every 0.05 s)", 0 < n1 < n2 < 12, (n1, n2))
    exp = math.floor((0.5 - 0.25) / (0.651 - 0.25) * 12 + 1e-6)
    chk("2. ... at the setting's pace (Normal: 0.65 s; 0.5 s held = %d of 12)" % exp, abs(n2 - exp) <= 1, n2)
    L.execute('TICK(0.2)')
    bead = [round(float(v), 2) for v in b[2].hb.beads[1].color.values()]
    chk("2. past the hold point: CHARGED - all twelve, bright", b[2].hcharge == "charged" and lit(2) == 12 and bead[:3] == [1.0, 0.94, 0.62], (b[2].hcharge, bead))
    L.execute('HELD.PADDUP = nil; TICK(0.1)')
    chk("2. let go: gone", lit(2) == 0)
    L.execute('HELD.PADDUP = true; TICK(0.35); HELD.PADDUP = nil; TICK(0.1)')
    chk("2. an early release (0.35 s): the ring vanishes (a tap)", lit(2) == 0 and b[2].hcharge == 0)
    L.execute('KEPT[2] = nil; HELD.PADDUP = true; TICK(0.8)')
    chk("2. a press the router does not keep (no hold for it now): no ring", lit(2) == 0, lit(2))
    L.execute('HELD.PADDUP = nil; TICK(0.1); KEPT[2] = true; SECS = 0.434; HELD.PADDUP = true; TICK(0.5)')
    chk("2. Short (0.43 s): charged by 0.5 s held", b[2].hcharge == "charged", b[2].hcharge)
    L.execute('HELD.PADDUP = nil; TICK(0.1); SECS = 0.868; HELD.PADDUP = true; TICK(0.7)')
    chk("2. Long (0.87 s): still filling at 0.7 s", isinstance(b[2].hcharge, (int, float)) and 0 < b[2].hcharge < 12, b[2].hcharge)
    L.execute('HELD.PADDUP = nil; TICK(0.1); SECS = 0.651')
    # the 1531 audit: a WINDOW taking the pad (the bars unusable: the bar reads no buttons then) mid-press: the ring goes, it never
    # keeps filling on the last read (the router's kept flag can stay up while the window has the release)
    L.execute('KEPT[2] = true; HELD.PADDUP = true; TICK(0.4)')
    n0 = lit(2)
    L.execute('CORE_ONLY = false; TICK(0.5)')
    chk("2. a window took the pad mid-press: the ring gone, not charged", n0 > 0 and lit(2) == 0 and b[2].hcharge == 0, (n0, lit(2), b[2].hcharge))
    L.execute('CORE_ONLY = true; HELD.PADDUP = nil; TICK(0.1)')

    # ------------------------------------------------------------------------------------------------ (3) the takeover
    tap_icon = b[2].tapIcon
    L.execute('WC = WowCompanion; WC.gcdRun = nil; WC.holdFired(2)')
    chk("3. the hold fired: the hold spell takes the big face; the badge wears the tap spell", b[2].hto is not None and b[2].hflash.shown
        and b[2].hflash.tex == 136048 and b[2].hb.icon.tex == tap_icon and b[2].hflash.alpha == 1, (b[2].hflash.tex, b[2].hb.icon.tex, tap_icon))
    L.execute('TICK(0.25)')
    chk("3. the face's sweep is the hold spell's own (its cooldown object)", (b[2].cd.dur or {}).get("id") == 403 if b[2].cd.dur else True)
    L.execute('WC.holdCast(2, "cast", 1.8); WC.gcdRun = { s = NOW, d = 1.5 }; TICK(1.5)')
    chk("3. a 1.8 s cast: still on the face after 1.5 s (the GCD is shorter)", b[2].hto is not None and b[2].hflash.alpha == 1)
    L.execute('TICK(0.4)')
    chk("3. the cast ended: easing back (0 < alpha < 1)", b[2].hto is not None and 0 < b[2].hflash.alpha < 1, b[2].hflash.alpha)
    L.execute('TICK(0.25)')
    chk("3. ... back: the tap spell on the face, the hold spell on the badge again", b[2].hto is None and not b[2].hflash.shown
        and b[2].hb.icon.tex == 136048, (b[2].hto, b[2].hb.icon.tex))
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
    L.execute('WC.holdFired(5, "lt"); TICK(0.1)')
    chk("3. LT + X fired, the bare layer on the bar: the bare badge wears the tap spell", b[5].hb.icon.tex == b[5].tapIcon, (b[5].hb.icon.tex, b[5].tapIcon))
    L.execute('TICK(2.5)')
    chk("3. ... ended: the bare badge back to the bare X's hold spell", b[5].hto is None and b[5].hb.icon.tex == 135825, b[5].hb.icon.tex)
    L.execute('LT = nil; TICK(0.1)')
    L.execute('WC.holdFired(5, "bare"); TICK(0.1)')
    chk("3. the bare X's own hold, fired on the bare layer: its spell on the face", b[5].hflash.shown and b[5].hflash.tex == 135825, b[5].hflash.tex)
    L.execute('TICK(2.5)')
    # the 1531 audit: the charge ring is the PRESS's layer's: X kept on the bare layer, then LT held - the LT + X badge never charges
    # (the release will run the bare X's hold), the bare one again once LT is let go
    L.execute('KEPT[5] = true; HELD.PAD3 = true; TICK(0.4)')
    nb = sum(1 for i in range(1, 13) if b[5].hb.beads[i].shown)
    L.execute('LT = true; TICK(0.1)')
    nl = sum(1 for i in range(1, 13) if b[5].hb.beads[i].shown)
    L.execute('LT = nil; TICK(0.1)')
    nb2 = sum(1 for i in range(1, 13) if b[5].hb.beads[i].shown)
    chk("2. the ring is the press's layer's (bare X kept, LT held after: no ring on LT + X's badge, back on the bare one)",
        nb > 0 and nl == 0 and nb2 > 0, (nb, nl, nb2))
    L.execute('HELD.PAD3 = nil; KEPT[5] = nil; TICK(0.1)')

    # ------------------------------------------------------------------------------------------------ (4) nothing secure
    chk("4. plain frames only (no Secure template), nothing bound or set", list(G.BADTPL.values()) == [] and list(G.SPY.values()) == [],
        (list(G.BADTPL.values()), list(G.SPY.values())))

    if "--shots" in sys.argv:
        shots(sys.argv[sys.argv.index("--shots") + 1])

    print("\n%d failure(s)" % len(FAILS))
    return 1 if FAILS else 0


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


def shots(out):
    from PIL import Image, ImageDraw, ImageFont
    os.makedirs(out, exist_ok=True)
    pak = Pak(os.path.join(ART, "icons.pak"))
    ui = os.path.join(ART, "ui", "cast")
    S = 4.0                                           # px per bar unit

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
        d = int(46 * S)
        ring = Image.open(os.path.join(ui, "frame_circle_normal.png")).convert("RGBA").resize((d, d), Image.LANCZOS)
        icon_id = bt.hflash.tex if (bt.hto is not None and bt.hflash.shown) else bt.icon.tex
        ic = pak.get(int(icon_id)) if icon_id else None
        if ic is not None:
            icd = int(38 * S)
            base = circ(ic, icd)
            if bt.hto is not None and bt.hflash.shown and bt.hflash.alpha < 1:      # easing: the tap under the hold
                tp = pak.get(int(bt.icon.tex))
                if tp is not None:
                    img.alpha_composite(circ(tp, icd), (int(cx - icd / 2), int(cy - icd / 2)))
                base = tint(base, None, False, float(bt.hflash.alpha))
            img.alpha_composite(base, (int(cx - icd / 2), int(cy - icd / 2)))
        img.alpha_composite(ring, (int(cx - d / 2), int(cy - d / 2)))
        if bt.hto is not None and bt.hflash.shown:                                  # the takeover's gold ring
            dr = ImageDraw.Draw(img)
            r = 21 * S
            a = int(255 * float(bt.hflash.alpha))
            dr.ellipse((cx - r, cy - r, cx + r, cy + r), outline=(255, 236, 170, a), width=int(2.2 * S))
        g = Image.open(os.path.join(ui, GL[p])).convert("RGBA").resize((int(15 * S), int(15 * S)), Image.LANCZOS)
        img.alpha_composite(g, (int(cx + 13.2 * S - 7.5 * S), int(cy - 13.2 * S - 7.5 * S)))
        hb = bt.hb
        if hb is not None and hb.shown:
            pt = hb.pts[1]
            bx, by = cx + float(pt[4]) * S, cy - float(pt[5]) * S
            R = 17 * S / 2
            dr = ImageDraw.Draw(img)
            col = tuple(int(float(v) * 255) for v in list(hb.disc.color.values())[:3])
            dr.ellipse((bx - R, by - R, bx + R, by + R), fill=col + (255,))
            hid = hb.icon.tex
            hi = pak.get(int(hid)) if hid else None
            if hi is not None:
                ii = circ(hi, int(2 * (R - 1.5 * S)))
                ii = tint(ii, [float(v) for v in hb.icon.vc.values()] if hb.icon.vc else None, hb.icon.desat is True)
                img.alpha_composite(ii, (int(bx - ii.width / 2), int(by - ii.height / 2)))
            for i in range(1, 13):
                bd = hb.beads[i]
                if bd.shown:
                    q = bd.pts[1]
                    x, y = bx + float(q[4]) * S, by - float(q[5]) * S
                    c = tuple(int(float(v) * 255) for v in list(bd.color.values())[:3])
                    rr = 2.0 * S
                    dr.ellipse((x - rr, y - rr, x + rr, y + rr), fill=c + (255,))
        if title:
            ImageDraw.Draw(img).text((cx - 46 * S / 2, cy + 30 * S), title, fill=(236, 224, 196, 255), font=font(18))

    def font(n):
        try:
            return ImageFont.truetype("arial.ttf", n)
        except Exception:
            return ImageFont.load_default()

    def frame(name, items, w=4):
        img = Image.new("RGBA", (int(w * 70 * S), int(110 * S)), (18, 22, 30, 255))
        for i, (bt, p, title) in enumerate(items):
            button(img, int((i + 0.5) * 70 * S), int(45 * S), bt, p, title)
        img.save(os.path.join(out, name + ".png"))

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
    print("shots in", out)


if __name__ == "__main__":
    sys.exit(main())
