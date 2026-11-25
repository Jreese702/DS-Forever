"""Evidence shots for single screen mode WHILE PLAYING (the user, 2026-11-25: "a single screen mode where the game shifts to the top screen when
a button is pushed. We need to rebuild all of our menus and features to work on the top screen"; the pick for "While playing": "1, 2, and
also the chat on the left thr ds toolbar should have the same functionality in single screen mode. We can shift those panels ine and out
of combat to all fit better"): the 1920 x 1080 top screen as the Thor shows it - the game (a capture), the add-ons' top-screen scene run
for real (ActionBar.lua under test_top_castbar.py's stub game, PadMap's single() on: the Wings in a fight, OUR top cast bar for every cast,
drawn from the game's casting-bar pictures by render_top_castbar; the HUD / Party mode's rows / the compass / our Nintendo bar as the
scenes' labelled schematic boxes) and over it the app's play docks as the REAL Home views draw them (SinglePlayShotsTest's layers:
SINGLE_PLAY_SHOTS=<OUT>/layers; HomeView's map, Party mode's list and chat lent to SingleDocks).

    python -B tools/render_single_play.py [OUT_DIR]        (default C:/dsf-work/r1533-shots/single/play)

  play_01_out_of_combat.png            out of combat, Party mode on: the map top right, the party list and the chat on the left, our
                                       Nintendo bar; nothing over the HUD, the compass, the bar
  play_02_in_combat_wings.png          a fight: the Wings and our top cast bar (a Frostbolt), the docks in their fight places (the map
                                       240, the list up, the chat's 3 newest lines above the Wings' reach)
  play_03_glide_{0,50,100}.png         the shift into the fight: each piece gliding inside its grown window as the Wings slide up
  play_04_hearth_cast_out_of_combat.png  a Hearthstone OUT of the fight: our top cast bar shows it (ActionBar nbar25), no Wings
  play_05_keepout_debug.png            the keep-outs (red) and the docks (green), out of combat and in combat, side by side
  play_06_hold_to_talk.png             D-pad Left held: hold to talk's pop-up over the party list
  play_07_solo.png                     Party mode off: out of combat / in combat (the chat under our party frames, then above them)
SINGLE_TOP_PLAY = the game's frame (default the CORE shots' _bg_sharp.png); TOPCAST_ART / TOPCAST_FONTS as render_top_castbar.
Needs lupa and PIL."""
import json
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
OUT = sys.argv[1] if len(sys.argv) > 1 else "C:/dsf-work/r1533-shots/single/play"
sys.argv = sys.argv[:1] + [OUT]
import render_top_castbar as rtc  # noqa: E402
import render_wings_match as rwm  # noqa: E402
import test_wings_match as twm  # noqa: E402

rtc.OUT = OUT
LAYERS = os.path.join(OUT, "layers")
BG = os.environ.get("SINGLE_TOP_PLAY") or "C:/dsf-work/r1533-shots/single/core/_bg_sharp.png"
W, H = 1920, 1080


def boxes(name="docks.json"):
    p = os.path.join(LAYERS, name)
    return json.load(open(p, encoding="utf-8")) if os.path.exists(p) else {}


def scene(party=False, fight=False, cast=None, dur=3.0, single=True):
    """the add-ons' top screen: ActionBar under test_top_castbar's scene, PadMap's single() = [single] (combatTopOn / combatCastTopOn
    answer it, as PadMap m199 does), the saved Top-screen flags OFF"""
    L = rtc.boot()
    G = L.globals()
    tick = rtc.tick_fn(L)
    L.execute('''
local pm = WowCompanion.padMap
SS = %s
pm.single = function() return SS end
pm.combatTopOn = function() return SS end
pm.combatCastTopOn = function() return SS end
CCB = SS
''' % ("true" if single else "false"))
    if party:
        G.PARTY = True
        G.HUD.members = 4
        G.WowCompanion.prd.place()
        G.WowCompanion.uf.pf.draw()
    tick(40)
    G.PLACED = L.table()
    if fight:
        G.COMBAT = True
    if cast:
        L.execute('CASTSTART("%s", "%s", %r)' % (cast, cast, dur))
    return L, G, tick


def base():
    try:
        return Image.open(BG).convert("RGBA").resize((W, H), Image.LANCZOS)
    except Exception:      # noqa: BLE001
        return Image.new("RGBA", (W, H), (52, 62, 74, 255))


def font(n):
    return rtc.font(n, False)


def draw_top(G, L, layer=None, nbar=False, wings=True, labels=True):
    """the top screen: the game, the scene's HUD / rows (schematic), the compass and target bar (schematic), our Nintendo bar out of fights
    (schematic), the Wings (real art), our top cast bar (real art), then the app's docks layer"""
    img = base()
    d = ImageDraw.Draw(img)
    # Trail's compass and Hub's target bar under it (K1's pieces)
    d.rounded_rectangle((530, 4, 1390, 80), radius=10, fill=(12, 12, 12, 150), outline=(150, 130, 90, 200), width=2)
    if labels:
        d.text((960, 42), "compass (Trail, schematic)", fill=(230, 220, 190, 230), font=font(18), anchor="mm")
    d.rounded_rectangle((774, 96, 1146, 140), radius=6, fill=(40, 10, 10, 150), outline=(170, 60, 50, 200), width=2)
    if labels:
        d.text((960, 118), "target bar (Hub, schematic)", fill=(240, 200, 190, 230), font=font(15), anchor="mm")
    for nm, r in twm.drawn_parts(G):
        x, y, w, h = r
        if nm == "bars":
            d.rounded_rectangle((x, y, x + w, y + h), radius=6, fill=(10, 10, 10, 200))
            d.rectangle((x + 4, y + 4, x + w * 0.82, y + h * 0.55), fill=(40, 200, 40, 255))
            d.rectangle((x + 4, y + h * 0.62, x + w * 0.66, y + h - 4), fill=(40, 90, 230, 255))
            if labels:
                d.text((x + 6, y + h + 4), "HUD (Trail, schematic)", fill=(255, 255, 255, 210), font=font(14))
        else:
            col = (60, 110, 160, 220) if nm.startswith("row") else (200, 170, 60, 220)
            d.rounded_rectangle((x, y, x + w, y + h), radius=4, fill=col)
            if labels:
                d.text((x + 4, y + 3), "Party mode row (Hub, schematic)" if nm.startswith("row") else nm, fill=(255, 255, 255, 230), font=font(12))
    if nbar and labels:
        # our Nintendo bar stands at the stock action bar's place (ActionBar nbar1, CORE's preset): this scene does not lay it - its keep-out
        d.rectangle((650, 860, 1270, 1064), outline=(240, 200, 90, 200), width=2)
        d.text((656, 1066), "our Nintendo bar's keep-out (K3: nbar1 at the stock bar's place, not laid by this scene)", fill=(240, 220, 160, 230),
               font=font(13), anchor="lb")
    NB = G.WowCompanion.nbar
    if wings and NB.top.shown and len(G.PLACED) > 0:
        e = G.PLACED[len(G.PLACED)]
        s = float(e.s)
        for i, cx, cy in ((1, float(e.lx), float(e.ly)), (2, float(e.rx), float(e.ry))):
            rwm.draw_wings_half(img, NB, NB.f, i, cx, cy, s)
    rtc.draw_bar(img, G, 1.0)
    if layer:
        p = os.path.join(LAYERS, layer)
        if os.path.exists(p):
            img.alpha_composite(Image.open(p).convert("RGBA"))
        else:
            print("  (no layer %s: run SinglePlayShotsTest with SINGLE_PLAY_SHOTS=%s)" % (layer, LAYERS))
    return img


def captioned(img, title, sub=""):
    out = Image.new("RGBA", (img.width, img.height + 74), (22, 22, 22, 255))
    out.alpha_composite(img, (0, 74))
    d = ImageDraw.Draw(out)
    d.text((14, 8), title, fill=(240, 230, 200), font=font(24))
    d.text((14, 42), sub, fill=(200, 210, 200), font=font(17))
    return out


def save(img, name):
    os.makedirs(OUT, exist_ok=True)
    p = os.path.join(OUT, name)
    img.convert("RGB").save(p)
    print(p)
    return p


def shot_out():
    L, G, tick = scene(party=True)
    tick(20)
    b = boxes().get("dock_out", {})
    img = draw_top(G, L, "dock_out.png", nbar=True)
    save(captioned(img, "One screen, out of combat (Party mode on): the game's HUD + our Nintendo bar; Home's map, party list and chat over the game",
                   "the docks: map %s, party %s, chat %s (%s lines) - every one clear of the compass, the HUD block, the Nintendo bar; a touch "
                   "anywhere else is the game's" % (b.get("map"), b.get("party"), b.get("chat"), b.get("chatLines"))), "play_01_out_of_combat.png")


def shot_in():
    L, G, tick = scene(party=True, fight=True, cast="Frostbolt", dur=2.5)
    tick(70)
    b = boxes().get("dock_in", {})
    img = draw_top(G, L, "dock_in.png")
    save(captioned(img, "One screen, in a fight: the Wings and our top cast bar (PadMap m199's single screen mode; the saved flags untouched)",
                   "the docks in their fight places: map %s, party %s, chat %s (%s lines) - above the Wings' widest reach (y 550)"
                   % (b.get("map"), b.get("party"), b.get("chat"), b.get("chatLines"))), "play_02_in_combat_wings.png")


def shot_glide():
    L, G, tick = scene(party=True, fight=True, cast="Frostbolt", dur=2.5)
    for name, n, what in (("dock_glide_0.png", 1, "0 ms: the fight starts - each dock window grown to the union of both places"),
                          ("dock_glide_50.png", 7, "~120 ms: the pieces glide inside their windows (Motion.COMBAT_GROW, 240 ms)"),
                          ("dock_glide_100.png", 30, "landed: the windows shrunk to the fight places (two window updates a piece)")):
        tick(n)
        img = draw_top(G, L, name, labels=False)
        save(captioned(img, "The shift into the fight, " + what, "the Wings slide up from the bottom edge on the same fight word (ActionBar's own "
                       "slide); the docks move to make room, never per-frame window changes"), "play_03_glide_%s.png" % name.split("_")[-1].split(".")[0])


def shot_hearth():
    L, G, tick = scene(party=True, cast="Hearthstone", dur=10.0)
    tick(240)
    lk = rtc.ttc.look(G)
    img = draw_top(G, L, "dock_out.png", nbar=True)
    save(captioned(img, "A Hearthstone OUT of the fight: our top cast bar shows it (ActionBar nbar25: every cast while single screen mode is on)",
                   "no Wings out of a fight; the bar above the HUD block where the fight's bar stands (fill %.2f, '%s'); with two screens the "
                   "bottom band carries it" % (float(lk.p) if lk is not None else -1, lk.name if lk is not None else "-")),
         "play_04_hearth_cast_out_of_combat.png")


def keeps_panel(b, title):
    img = base()
    img = Image.blend(img, Image.new("RGBA", img.size, (0, 0, 0, 255)), 0.45)
    d = ImageDraw.Draw(img)
    for k in b.get("keeps", []):
        nm, l, t, r, bb = k
        d.rectangle((l, t, r, bb), outline=(255, 70, 60, 255), width=4)
        d.text((l + 8, t + 6), nm, fill=(255, 140, 120, 255), font=font(20))
    for piece in ("map", "party", "chat"):
        r = b.get(piece)
        if r:
            d.rectangle(tuple(r), outline=(70, 230, 90, 255), width=4, fill=(40, 160, 60, 70))
            d.text((r[0] + 8, r[1] + 6), "dock: " + piece, fill=(160, 255, 170, 255), font=font(20))
    return captioned(img, title)


def shot_keepout():
    bx = boxes()
    a = keeps_panel(bx.get("dock_out", {}), "Out of combat: keep-outs K1 K2 K3 (+K5 without Party mode)")
    c = keeps_panel(bx.get("dock_in", {}), "In combat: keep-outs K1 K2 K4 (the Wings' widest reach)")
    a = a.resize((a.width // 2, a.height // 2), Image.LANCZOS)
    c = c.resize((c.width // 2, c.height // 2), Image.LANCZOS)
    out = Image.new("RGBA", (a.width + c.width + 12, a.height), (22, 22, 22, 255))
    out.alpha_composite(a, (0, 0))
    out.alpha_composite(c, (a.width + 12, 0))
    save(captioned(out, "The keep-outs (red, read off the add-ons' layout code) and the docks (green): no overlap",
                   "SinglePlayLayoutTest checks it for every HUD the Settings steps allow (y -470..-170, scale 70..160 %, width 240..400), Party mode on / off"),
         "play_05_keepout_debug.png")


def shot_talk():
    L, G, tick = scene(party=True)
    tick(20)
    img = draw_top(G, L, "dock_talk.png", nbar=True)
    save(captioned(img, "D-pad Left held (the DS toolbar's Chat): hold to talk's pop-up over the party list, as on two screens",
                   "the chat tabs (L / R or a finger), the microphone in the chosen chat's colour, the words under it; the rest of the screen the game's"),
         "play_06_hold_to_talk.png")


def shot_solo():
    bs = boxes("docks_solo.json")
    L, G, tick = scene(party=False)
    tick(20)
    a = draw_top(G, L, "dock_out_solo.png", nbar=True)
    L2, G2, tick2 = scene(party=False, fight=True, cast="Frostbolt", dur=2.5)
    tick2(70)
    c = draw_top(G2, L2, "dock_in_solo.png")
    a = a.resize((a.width // 2, a.height // 2), Image.LANCZOS)
    c = c.resize((c.width // 2, c.height // 2), Image.LANCZOS)
    out = Image.new("RGBA", (a.width + c.width + 12, a.height), (22, 22, 22, 255))
    out.alpha_composite(a, (0, 0))
    out.alpha_composite(c, (a.width + 12, 0))
    save(captioned(out, "Party mode off: out of combat (left) the chat under our party frames' place; in a fight (right) the chat above them",
                   "out %s / in %s" % (bs.get("dock_out_solo", {}).get("chat"), bs.get("dock_in_solo", {}).get("chat"))), "play_07_solo.png")


def main():
    os.makedirs(OUT, exist_ok=True)
    for fn in (shot_out, shot_in, shot_glide, shot_hearth, shot_keepout, shot_talk, shot_solo):
        fn()
    if rtc.STANDIN:
        print("stand-ins (art the root lacks): %s" % ", ".join(rtc.STANDIN))


if __name__ == "__main__":
    main()
