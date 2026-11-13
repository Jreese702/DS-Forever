package com.jrees.wowcompanion

import android.graphics.RectF

/**
 * THE CONTROLLER ROOM'S LEFT-PAGE PICTURE, SHARED (party_design.md B: "The geometry moves into a shared helper, PadDiamonds"): the
 * set row's three plates, the form / Touch row, the D-pad's diamond (cx 190) and the face diamond (cx 556), 80 px squares 100 apart at
 * cy 590. PadTabPage (the normal layers) and PartyTabPage (Party mode) both draw on it. Pure ([PartyLayout.Box]); [rect] for Canvas.
 */
object PadDiamonds {
    /** the set row: ZL / ZR / ZL + ZR (sets 2-4; [i] 0-2), 208 x 70 at y 280 */
    fun setRect(i: Int) = PartyLayout.Box(40f + i * 222f, 280f, 40f + i * 222f + 208f, 350f)
    /** the form row's tile [i] (100 x 70 at y 364) */
    fun formRect(i: Int) = PartyLayout.Box(40f + i * 112f, 364f, 40f + i * 112f + 100f, 434f)
    /** the Touch row's two tiles on the form row's line (Touch, Hold), each 320 wide */
    fun touchRect(i: Int) = PartyLayout.Box(40f + i * 334f, 364f, 40f + i * 334f + 320f, 434f)
    /** the footer plate under the diamonds */
    val FOOTER = PartyLayout.Box(150f, 760f, 600f, 830f)

    /** position [p]'s centre (the router's 1-8: 1-4 the D-pad, 5-8 the faces) as drawn for the layout ([swap] = Nintendo's face diamond) */
    fun centre(p: Int, swap: Boolean): Pair<Float, Float> {
        // m150 STICK BUTTONS (GitHub #78): L3 / R3 low between the two diamonds (their inner bottom corners), as a pad's two sticks
        if (p == PadMapModel.POS_L3) return 290f to 690f
        if (p == PadMapModel.POS_R3) return 456f to 690f
        val cx = if (p <= 4) 190f else 556f; val cy = 590f
        return when (PadMapModel.drawnSpot(p, swap)) { 0 -> cx - 100f to cy; 1 -> cx to cy - 100f; 2 -> cx + 100f to cy; else -> cx to cy + 100f }
    }
    fun square(p: Int, swap: Boolean) = centre(p, swap).let { (x, y) -> PartyLayout.Box(x - 40f, y - 40f, x + 40f, y + 40f) }

    fun rect(b: PartyLayout.Box) = RectF(b.l, b.t, b.r, b.b)
}
