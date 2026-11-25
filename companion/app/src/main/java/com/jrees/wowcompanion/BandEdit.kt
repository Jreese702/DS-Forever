package com.jrees.wowcompanion

/**
 * EDIT MODE'S TOP BAR PIECES, WHERE THEY STAND (the user's rulings 2026-11-22, the picker's ALL FOUR: P1 the D-pad's and the ABXY's
 * pencils, P3 the Look chip, P4 the fight preview's pencil). Pure (no android.*): checked off-device against the band's real slot table
 * (BandEditPlaceTest). Nothing on the band moves for them: each stands in a gap the band already leaves, never on the clock chip, another
 * piece or the OTHER group's buttons. Readability pass (the shots: the pencils read as one pair, the D-pad's beside the Look chip): each
 * pencil sits at its own part's corner, away from the other pieces:
 *  - DIAMONDS (and the grown fight band): the D-pad's pencil at the D-pad's OUTER top corner (left of its Up, over its Left), the ABXY's
 *    at the faces' outer top corner (right of their top button, over their right one) - the two at the band's two ends; the Look chip in
 *    the D-pad's inner notch (right of its Up, over its Right, short of the clock chip);
 *  - THIN ROW (the only gap is the 84 px between the D-pad's Right and the faces' left button): each pencil ON its own button's inner
 *    bottom corner like a badge (the extras' "-" badges sit on theirs) - the D-pad's on its Right's corner ([ROW_OUT] of it in the gap),
 *    the ABXY's on the round left face button's rim - the Look chip above them in the gap;
 *  - the fight pencil takes the Look chip's place (the preview at the rest size), or the grown band's top centre under the swing line;
 *    Top screen's preview (the 1519 integration: the band slid up off the page as the Top fight lays it, [place]'s away) puts it at the
 *    PAGE's top centre, where the band went - the band's other pieces leave with the band (above the page: no stop); with the fight
 *    strip there (Cast bar in fights = Bottom screen, or Top screen with the game's swing timer off: the swing alone) on the strip's
 *    left end instead ([onStrip], the user's pick 2026-11-23).
 */
object BandEdit {
    /** a pencil's side, the Look chip's size (px at 1240 wide) */
    const val PEN = 40f
    /**
     * THE FIGHT PENCIL, BIGGER (the user on test 1530, 2026-11-25: "The in fight pencil is way to small in edit mode to reasonably expect
     * someone to try tapping it"): its side where it has the room - on the page where the band went (Top screen's preview), in the
     * band's gap / notch / top centre as far as the band's own gaps allow (never on a button, the clock chip or another piece) - and
     * [PEN_ON_STRIP] on the fight strip's left end. Every pencil's TOUCH box is at least [PEN_TOUCH] ([touchBox]): 48 dp on the screen
     */
    const val PEN_FIGHT = 64f
    const val PEN_ON_STRIP = 56f
    /** the party list's pencil where the list has the room (HomeEditView.partyPenBox), else [PEN] */
    const val PEN_PARTY = 52f
    /** THE PENCILS' TOUCH FLOOR (the app's 48 dp, as MapView / HomeSettings / ChatView: 112 px on the Thor's 369 dpi bottom panel):
     *  48 x 369 / 160 = 110.7 screen px; edit mode draws the page zoomed out under its tab row (HomeView.pageMatrix, about 0.92 on the
     *  bottom screen's frame), so in Home's px [PEN_TOUCH] keeps 48 dp at any zoom down to [TOUCH_ZOOM_MIN] */
    const val TOUCH_DP = 48f
    const val PANEL_DPI = 369f
    const val TOUCH_ZOOM_MIN = 0.9f
    const val PEN_TOUCH = 124f
    /** [r]'s touch box: [r] grown about its centre to [min] square at least (never shrunk) */
    fun touchBox(r: HomeLayoutSolver.Box, min: Float = PEN_TOUCH): HomeLayoutSolver.Box {
        val dx = maxOf(0f, (min - (r.r - r.l)) / 2f); val dy = maxOf(0f, (min - (r.b - r.t)) / 2f)
        return HomeLayoutSolver.Box(r.l - dx, r.t - dy, r.r + dx, r.b + dy)
    }
    const val PEN_ROW = 32f
    const val LOOK_W = 76f
    const val LOOK_H = 36f
    /** the clearance from a button */
    const val PAD = 4f
    /** the thin row: the share of the D-pad's pencil out past its Right button's edge (the rest is on the button's corner) */
    const val ROW_OUT = 0.3f
    /** a face button's drawn circle: this share of its slot's half side (the ABXY pencil sits on its rim) */
    const val FACE_ROUND = 0.94f

    class Pieces(val padPen: HomeLayoutSolver.Box, val facePen: HomeLayoutSolver.Box, val look: HomeLayoutSolver.Box?,
                 val fightPen: HomeLayoutSolver.Box?)

    /** Top screen's preview: the fight pencil's top on the page (the band above it, off the page) */
    const val AWAY_TOP = 4f
    /** the controller's cursor box on a band piece: the piece's box this much out (HomeEditView, UiKit.focus draws on it) */
    const val CURSOR_OUT = 6f
    /** the controller's cursor on a band piece, past the piece's edge: [CURSOR_OUT] + UiKit.focus's 5 px frame + half its 8 px stroke */
    const val RING = CURSOR_OUT + 9f

    /**
     * [band] = the band's box; [slots] its 8 buttons (0..3 the D-pad L / U / R / D, 4..7 the faces left / top / right / bottom); [clock]
     * the clock chip's box (null = none drawn); [diamonds] the look; [grown] the fight's bigger band; [preview] the fight preview is on
     * (no Look chip then, the fight pencil instead); [away] the band laid above the page (Top screen's preview: HomeLayoutSolver.away) -
     * the fight pencil at the page's top centre, [PEN] square, [AWAY_TOP] down; [strip] the fight strip's box on the page then (Cast bar
     * in fights = Bottom screen, or the swing alone: the strip HomeView draws; null = none) - the fight pencil at the strip's left end
     * ([onStrip])
     */
    fun place(band: HomeLayoutSolver.Box, slots: List<HomeLayoutSolver.Box>, clock: HomeLayoutSolver.Box?, diamonds: Boolean,
              grown: Boolean, preview: Boolean, away: Boolean = false, strip: HomeLayoutSolver.Box? = null): Pieces {
        val on = placeOn(band, slots, clock, diamonds, grown, preview)
        if (!(preview && away)) return on
        val s = (band.r - band.l) / CastBarPlace.FRAME_W
        if (strip != null) return Pieces(on.padPen, on.facePen, null, onStrip(strip, s))
        val pp = PEN_FIGHT * s
        return Pieces(on.padPen, on.facePen, null, box((band.l + band.r) / 2f - pp / 2f, AWAY_TOP, pp, pp))
    }

    /**
     * THE FIGHT PENCIL ON THE FIGHT STRIP'S LEFT END (the user's pick 2026-11-23, "Left end of the strip": at the page's top centre it
     * covered the middle of the strip - the cast's name and the swing's run). [PEN_ON_STRIP] square (since test 1530; 1530's [PEN_ROW] the
     * thin row's size, the strip only FIGHT_STRIP_H tall), over the strip's rounded left end cap, as near the strip's middle height as the controller's cursor round it
     * ([RING]) lets it stand on the page: the strip lies [RING] or less from the page's top and left, so the pencil's top and left are
     * [RING] in - its centre still on the strip's height, the strip's first few dozen px under it (the only place the cast's fill and the
     * swing start from, briefly) and the rest of the bar, its name and its swing clear. The strip itself never moves for it (the preview
     * shows the fight as it is laid)
     */
    fun onStrip(strip: HomeLayoutSolver.Box, s: Float = 1f): HomeLayoutSolver.Box {
        // (test 1530: [PEN_ON_STRIP] - taller than the strip, so it hangs from the strip's left end, its top [RING] in, over the strip's
        // height and a little of the page under it; the strip's middle still clear. RULING D (the user's pick 2026-11-25, "Party pencil
        // to the right (Recommended)"): always this size - the party list's pencil sits at the list's RIGHT end (HomeEditView.partyPenBox),
        // so nothing else is ever under the strip's left end; 1530's 32 px fallback beside it is gone)
        val p = PEN_ON_STRIP * s
        val l = maxOf(strip.l, RING)
        val t = maxOf((strip.t + strip.b) / 2f - p / 2f, RING)
        return box(l, t, p, p)
    }

    private fun placeOn(band: HomeLayoutSolver.Box, slots: List<HomeLayoutSolver.Box>, clock: HomeLayoutSolver.Box?, diamonds: Boolean,
                        grown: Boolean, preview: Boolean): Pieces {
        val s = (band.r - band.l) / CastBarPlace.FRAME_W
        if (!diamonds) {
            // the thin row: the centre gap between the D-pad's Right (2) and the faces' left (4); the pencils on those two buttons'
            // inner bottom corners, the Look chip above them in the gap
            val d = slots[2]; val f = slots[4]
            val g0 = d.r; val g1 = f.l
            val p = minOf(PEN_ROW * s, (g1 - g0) * 0.45f)
            val pad = box(g0 - p * (1f - ROW_OUT), minOf(d.b, band.b) - p, p, p)
            // on the left face button's rim, down and to the left of its centre (45 degrees), never below the button
            val rr = (f.r - f.l) / 2f * FACE_ROUND * 0.7071f
            val fcx = (f.l + f.r) / 2f - rr; val fcy = minOf((f.t + f.b) / 2f + rr + p / 2f, f.b, band.b) - p / 2f
            val face = box(fcx - p / 2f, fcy - p / 2f, p, p)
            val lw = minOf(LOOK_W * s, g1 - g0 - 2f * PAD * s)
            val top = maxOf(slots[2].t, band.t)
            val under = minOf(pad.t, face.t)
            val lh = minOf(LOOK_H * s, under - top - 2f * PAD * s)
            val look = box((g0 + g1) / 2f - lw / 2f, top + (under - PAD * s - top - lh) / 2f, lw, lh)
            // test 1530: the fight pencil as big as the gap over the two pencils allows ([PEN_FIGHT] at most)
            val room = under - PAD * s - top
            val fp = maxOf(minOf(p, lh), minOf(PEN_FIGHT * s, g1 - g0 - 2f * PAD * s, room - PAD * s))
            val fight = box((g0 + g1) / 2f - fp / 2f, top + (room - fp) / 2f, fp, fp)
            return Pieces(pad, face, if (preview) null else look, if (preview) fight else null)
        }
        // diamonds: the D-pad's pencil left of its Up (1), over its Left (0); the faces' right of their top (5), over their right (6)
        val left = slots[0]; val up = slots[1]; val right = slots[2]; val top5 = slots[5]; val right6 = slots[6]
        val pp = minOf(PEN * s, left.t - up.t - PAD * s, right6.t - top5.t - PAD * s).coerceAtLeast(24f * s)
        val pad = box(up.l - PAD * s - pp, maxOf(up.t, band.t), pp, pp)
        val face = box(top5.r + PAD * s, maxOf(top5.t, band.t), pp, pp)
        // the Look chip in the D-pad's inner notch: right of its Up, over its Right, short of the clock chip; the fight pencil there at
        // the rest size
        val cl = clock?.l ?: ((band.l + band.r) / 2f - 80f * s)
        val n0 = up.r + PAD * s; val n1 = cl - PAD * s
        val lw = minOf(LOOK_W * s, n1 - n0)
        val lh = minOf(LOOK_H * s, right.t - up.t - 2f * PAD * s)
        val look = box((n0 + n1) / 2f - lw / 2f, pad.t + (pp - lh) / 2f, lw, lh)
        // test 1530: the fight pencil as big as its spot allows ([PEN_FIGHT] at most): the grown band's top centre between the D-pad's Up
        // and the faces' top, the notch between the Up and the clock chip above the Right
        val fight = if (!preview) null
            else if (grown) {
                val gp = maxOf(pp, minOf(PEN_FIGHT * s, top5.l - up.r - 2f * PAD * s))
                box((band.l + band.r) / 2f - gp / 2f, maxOf(band.t + (CastBarPlace.TOP_STRIP + PAD) * s, up.t), gp, gp)
            } else {
                // the notch over the Right, or the pocket between the Right and the clock chip (level with the Right) - the bigger
                val nt = maxOf(up.t, band.t)
                val np = maxOf(pp, minOf(PEN_FIGHT * s, n1 - n0, right.t - PAD * s - nt))
                val k0 = right.r + PAD * s
                val kp = minOf(PEN_FIGHT * s, n1 - k0, band.b - band.t - 2f * PAD * s)
                if (kp > np) {
                    val kt = ((right.t + right.b) / 2f - kp / 2f).coerceIn(band.t + PAD * s, band.b - PAD * s - kp)
                    box((k0 + n1) / 2f - kp / 2f, kt, kp, kp)
                } else box((n0 + n1) / 2f - np / 2f, nt, np, np)
            }
        return Pieces(pad, face, if (preview) null else look, fight)
    }

    private fun box(l: Float, t: Float, w: Float, h: Float) = HomeLayoutSolver.Box(l, t, l + w, t + h)
}
