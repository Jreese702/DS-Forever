"""Evidence shots for Edit Home's top-screen preview (ActionBar nbar23 / Hub hub79 / Trail prd25 / PadMap m198; the user on test 1530,
2026-11-25: "When choosing the top screen options in combat it should also display the top screen ui so that its easy to visualize.
Likewise the party mode should show a full party on the top screen so its easy to visualize"): the REAL ActionBar.lua runs under
test_top_castbar.py's scene (test_wings_match's stub game; NO fight, the saved Top-screen flag OFF - only the preview's words), frame by
frame, with PadMap's preview() answering the code the app sends; the top screen is drawn by render_top_castbar's drawing (the Wings from
their real plate / glyph art, our top cast bar from the game's casting-bar pictures; the HUD and the rows are the scene's boxes - the
party rows labelled with Hub hub79's placeholders and class colours), and the bottom screen's own render (EditPreviewShotsTest, the
real Home with HOME_LAYOUT_ART) under it, as the Thor stacks them.

    python -B tools/render_edit_preview.py [OUT_DIR]       (default C:/dsf-work/r1531-shots/editpreview)

  10_in_combat_top_cast_top.png     In combat tab: Combat bars Top + Cast bar Top + Swing (code 35) - the Wings, our bar, its swing
  11_in_combat_top_cast_bottom.png  In combat tab: Cast bar Bottom screen (code 33) - the Wings alone (the strip on the bottom screen)
  12_party_mode_full_party.png      Party mode tab, Bottom screen (code 36): a full party - you (the HUD) and Party 1 .. Party 4
  13_party_mode_top_wings.png       Party mode tab, Top screen (code 37): the full party centred, the Wings clear of it
Needs lupa and PIL."""
import os
import re
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
OUT = sys.argv[1] if len(sys.argv) > 1 else "C:/dsf-work/r1531-shots/editpreview"
sys.argv = sys.argv[:1] + [OUT]
import render_top_castbar as rtc  # noqa: E402
import test_wings_match as twm  # noqa: E402

rtc.OUT = OUT
BOTTOM = os.path.join(OUT, "bottom")
HUB = os.path.join(twm.ADDONS, "WowCompanionHub", "Hub.lua")
CLS_RGB = {"WARRIOR": (199, 156, 110), "PRIEST": (255, 255, 255), "MAGE": (64, 199, 235), "HUNTER": (171, 212, 115),
           "ROGUE": (255, 245, 105), "PALADIN": (245, 140, 186), "DRUID": (255, 125, 10), "SHAMAN": (0, 112, 222)}
LOOK = {33: "{ c = 33, wings = true }", 34: "{ c = 34, wings = true, cast = true }",
        35: "{ c = 35, wings = true, cast = true, swing = true }", 36: "{ c = 36, party = true }",
        37: "{ c = 37, party = true, wings = true }"}


def hub_classes():
    m = re.search(r'PF\.PV_CLS = \{([^}]*)\}', open(HUB, encoding="utf-8").read())
    return re.findall(r'"(\w+)"', m.group(1)) if m else ["WARRIOR", "PRIEST", "MAGE", "HUNTER"]


def run(code):
    L = rtc.boot()
    G = L.globals()
    tick = rtc.tick_fn(L)
    L.execute('''
local pm = WowCompanion.padMap
pm.combatTopOn = function() return false end
CCB, FSW, CSW = false, false, false
PARTY = false
HUD.members = 5
local PF = WowCompanion.uf.pf
local d0 = PF.draw
function PF.draw() local p = PARTY; if PV and PV.party then PARTY = true end; d0(); PARTY = p end
PV = nil
pm.preview = function() return PV end
''')
    G.HUD.x0 = 820
    G.WowCompanion.prd.place()
    tick(30)
    G.PLACED = L.table()
    L.execute("PV = " + LOOK[code])
    for _ in range(150):
        tick(1)
        L.execute("WowCompanion.uf.pf.draw()")
    return L, G


def label_rows(im, G, sc, ox=12, oy=70):
    d = ImageDraw.Draw(im)
    cls = hub_classes()
    PF = G.WowCompanion.uf.pf
    for i in range(1, 5):
        f = PF.frames[i]
        if not (f.shown and (f.alpha or 0) > 0.02):
            continue
        x, y, w, h = [v * sc for v in twm.rect(f)]
        x, y = x + ox, y + oy
        d.rounded_rectangle((x, y, x + w, y + h), radius=3, fill=(14, 12, 10, 235), outline=(90, 80, 60, 255))
        hp = (1, 0.78, 0.55, 0.9)[i - 1]
        d.rectangle((x + w * 0.25, y + h * 0.45, x + w * (0.25 + 0.7 * hp), y + h * 0.72), fill=(30, 220, 30, 255))
        d.rectangle((x + w * 0.25, y + h * 0.76, x + w * 0.88, y + h * 0.88), fill=(200, 30, 30, 255) if cls[i - 1] == "WARRIOR" else (40, 90, 230, 255))
        d.text((x + w * 0.26, y + 1), "Party %d" % i, fill=CLS_RGB.get(cls[i - 1], (255, 210, 0)), font=rtc.font(max(9, int(12 * sc * 2)), True))
    return im


def stack(top, bottom_png, caption):
    """the top screen's render over the bottom screen's (scaled to the same width), as the Thor stacks them"""
    if bottom_png and os.path.exists(bottom_png):
        b = Image.open(bottom_png).convert("RGBA")
        b = b.crop((0, 0, 1240, 1080))
        k = (top.width * 0.62) / b.width
        b = b.resize((int(b.width * k), int(b.height * k)), Image.LANCZOS)
        out = Image.new("RGBA", (top.width, top.height + b.height + 40), (22, 22, 22, 255))
        out.alpha_composite(top, (0, 0))
        out.alpha_composite(b, ((top.width - b.width) // 2, top.height + 30))
        ImageDraw.Draw(out).text((12, top.height + 6), "bottom screen (Edit Home, EditPreviewShotsTest): " + caption, fill=(220, 220, 200),
                                 font=rtc.font(14, False))
        return out
    return top


def shot(code, name, title, sub, bottom, bcap, party=False):
    L, G = run(code)
    sc = 0.5
    im = rtc.draw_scene(G, L, sc, title, sub, zoom=2.2)
    if party:
        im = label_rows(im, G, sc)
    im = stack(im, os.path.join(BOTTOM, bottom) if bottom else None, bcap)
    p = rtc.save(im, name)
    NB = G.WowCompanion.nbar
    print("%s: code %d, Wings %s, our bar %s, Party layer %s, rows %d" % (p, code, bool(NB.top.shown), rtc.shown(G) if hasattr(rtc, "shown")
          else "-", NB.partyOn(), sum(1 for i in range(1, 5) if G.WowCompanion.uf.pf.frames[i].shown)))
    return p


def main():
    os.makedirs(OUT, exist_ok=True)
    shot(35, "10_in_combat_top_cast_top.png", "Edit Home > In combat: Combat bars Top screen, Cast bar Top screen, Swing on (code 35) - NO fight",
         "the top screen previews the Top fight: the Wings round the HUD, our cast bar above it (a sample cast) with its swing line",
         "01_bottom_in_combat_top_cast_top.png", "In combat tab, the fight pencil where the band went")
    shot(33, "11_in_combat_top_cast_bottom.png", "Edit Home > In combat: Combat bars Top screen, Cast bar Bottom screen (code 33)",
         "the Wings alone on top (the cast bar and swing stay on the bottom screen's strip)",
         "01b_bottom_in_combat_top_cast_bottom_bubble.png", "In combat tab, the Fight bubble open (Cast bar: Bottom screen)")
    shot(36, "12_party_mode_full_party.png", "Edit Home > Party mode: a FULL party on the top screen (code 36), solo, Party mode off",
         "you (the HUD's bars) and four placeholder rows Party 1 .. Party 4 (Hub hub79: class colours, no unit read), the Select tab lit",
         "02_bottom_party_mode.png", "Party mode tab (its Combat bars: Bottom screen)", party=True)
    shot(37, "13_party_mode_top_wings.png", "Edit Home > Party mode with Combat bars Top screen (code 37): the full party centred, the Wings clear",
         "Party mode's Top fight lay: the block centred (nbar14), the Wings either side with the Party layer", None, "", party=True)


if __name__ == "__main__":
    main()
