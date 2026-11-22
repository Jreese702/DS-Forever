package com.jrees.wowcompanion

/**
 * EDIT MODE'S TOP BAR PIECES, WHERE THEY STAND (the user's rulings 2026-11-22, the picker's ALL FOUR: P1 the D-pad's and the ABXY's
 * pencils, P3 the Look chip, P4 the fight preview's pencil). Pure (no android.*): checked off-device against the band's real slot table
 * (BandEditPlaceTest). Nothing on the band moves for them: each stands in a gap the band already leaves, never on a button, the clock chip
 * or another piece:
 *  - THIN ROW: the gap between the D-pad's Right and the faces' left button (84 px at 1240 wide) - the D-pad's pencil hugging the D-pad,
 *    the ABXY's hugging the faces, the Look chip under the two;
 *  - DIAMONDS: the D-pad's pencil right of its Up button, over its Right; the ABXY's left of the faces' top button, over their left one;
 *    the Look chip between the D-pad's pencil and the clock chip;
 *  - the fight pencil takes the Look chip's place (the preview at the rest size), or the grown band's top centre.
 */
object BandEdit {
    /** a pencil's side, the Look chip's size (px at 1240 wide) */
    const val PEN = 40f
    const val PEN_ROW = 36f
    const val LOOK_W = 64f
    const val LOOK_H = 36f
    /** the clearance from a button */
    const val PAD = 4f

    class Pieces(val padPen: HomeLayoutSolver.Box, val facePen: HomeLayoutSolver.Box, val look: HomeLayoutSolver.Box?,
                 val fightPen: HomeLayoutSolver.Box?)

    /**
     * [band] = the band's box; [slots] its 8 buttons (0..3 the D-pad L / U / R / D, 4..7 the faces left / top / right / bottom); [clock]
     * the clock chip's box (null = none drawn); [diamonds] the look; [grown] the fight's bigger band; [preview] the fight preview is on
     * (no Look chip then, the fight pencil instead)
     */
    fun place(band: HomeLayoutSolver.Box, slots: List<HomeLayoutSolver.Box>, clock: HomeLayoutSolver.Box?, diamonds: Boolean,
              grown: Boolean, preview: Boolean): Pieces {
        val s = (band.r - band.l) / CastBarPlace.FRAME_W
        if (!diamonds) {
            // the thin row: the centre gap between the D-pad's Right (2) and the faces' left (4)
            val g0 = slots[2].r; val g1 = slots[4].l
            val p = minOf(PEN_ROW * s, (g1 - g0 - 3f * PAD * s) / 2f)
            val top = slots[2].t + 2f * s
            val pad = box(g0 + PAD * s, top, p, p)
            val face = box(g1 - PAD * s - p, top, p, p)
            val lw = minOf(LOOK_W * s, g1 - g0 - 2f * PAD * s)
            val lh = minOf(LOOK_H * s, slots[2].b - pad.b - 2f * PAD * s)
            val look = box((g0 + g1) / 2f - lw / 2f, pad.b + PAD * s + ((slots[2].b - pad.b - PAD * s) - lh) / 2f, lw, lh)
            return Pieces(pad, face, if (preview) null else look, if (preview) look else null)
        }
        // diamonds: right of the D-pad's Up (1) above its Right (2); left of the faces' top (5) above their left (4)
        val up = slots[1]; val right = slots[2]; val top5 = slots[5]; val left4 = slots[4]
        val pp = minOf(PEN * s, right.t - up.t - PAD * s, left4.t - top5.t - PAD * s).coerceAtLeast(24f * s)
        val pad = box(up.r + PAD * s, up.t, pp, pp)
        val face = box(top5.l - PAD * s - pp, top5.t, pp, pp)
        // the Look chip between the D-pad's pencil and the clock chip (its own row's middle), the fight pencil there at the rest size
        val cl = clock?.l ?: ((band.l + band.r) / 2f - 80f * s)
        val lw = minOf(LOOK_W * s, cl - pad.r - 2f * PAD * s)
        val look = box((pad.r + cl) / 2f - lw / 2f, pad.t + (pp - LOOK_H * s) / 2f, lw, LOOK_H * s)
        val fight = if (!preview) null
            else if (grown) box((band.l + band.r) / 2f - pp / 2f, maxOf(band.t + (CastBarPlace.TOP_STRIP + PAD) * s, up.t), pp, pp)
            else box((pad.r + cl) / 2f - pp / 2f, pad.t, pp, pp)
        return Pieces(pad, face, if (preview) null else look, fight)
    }

    private fun box(l: Float, t: Float, w: Float, h: Float) = HomeLayoutSolver.Box(l, t, l + w, t + h)
}
