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
 *    PAGE's top centre, where the band went - the band's other pieces leave with the band (above the page: no stop).
 */
object BandEdit {
    /** a pencil's side, the Look chip's size (px at 1240 wide) */
    const val PEN = 40f
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

    /**
     * [band] = the band's box; [slots] its 8 buttons (0..3 the D-pad L / U / R / D, 4..7 the faces left / top / right / bottom); [clock]
     * the clock chip's box (null = none drawn); [diamonds] the look; [grown] the fight's bigger band; [preview] the fight preview is on
     * (no Look chip then, the fight pencil instead); [away] the band laid above the page (Top screen's preview: HomeLayoutSolver.away) -
     * the fight pencil at the page's top centre, [PEN] square, [AWAY_TOP] down
     */
    fun place(band: HomeLayoutSolver.Box, slots: List<HomeLayoutSolver.Box>, clock: HomeLayoutSolver.Box?, diamonds: Boolean,
              grown: Boolean, preview: Boolean, away: Boolean = false): Pieces {
        val on = placeOn(band, slots, clock, diamonds, grown, preview)
        if (!(preview && away)) return on
        val pp = PEN * (band.r - band.l) / CastBarPlace.FRAME_W
        return Pieces(on.padPen, on.facePen, null, box((band.l + band.r) / 2f - pp / 2f, AWAY_TOP, pp, pp))
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
            val fp = minOf(p, lh)
            val fight = box((g0 + g1) / 2f - fp / 2f, look.t + (lh - fp) / 2f, fp, fp)
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
        val fight = if (!preview) null
            else if (grown) box((band.l + band.r) / 2f - pp / 2f, maxOf(band.t + (CastBarPlace.TOP_STRIP + PAD) * s, up.t), pp, pp)
            else box((n0 + n1) / 2f - pp / 2f, pad.t, pp, pp)
        return Pieces(pad, face, if (preview) null else look, fight)
    }

    private fun box(l: Float, t: Float, w: Float, h: Float) = HomeLayoutSolver.Box(l, t, l + w, t + h)
}
