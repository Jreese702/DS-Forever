package com.jrees.wowcompanion

/**
 * THE CALENDAR BUTTON ON HOME (the user, 2026-11-13: "Lets scrap the smart ranged attack and start building a macro room. The calender
 * can be moved up to a button on the right side of the map name make it small. Use the room slot for macros."). Pure geometry (no
 * Android types: the off-device tests read it); HomeView draws it with UiKit.calendarButton.
 *
 *  - AT REST AND IN A FIGHT: a square the band's own height (64 px) at the RIGHT END of the map-name band, where the game's own
 *    calendar button sits beside the zone name (GameTimeFrame); the band ends [GAP] before it, so a long zone name ellipsizes a little
 *    sooner. It is drawn with the band (it fades and slides with it: the pause menu's slide, a move), so it shows wherever the name
 *    shows - the fight view included (the pick D8: "shows in fights and works on the first touch"). It lies over the band's own strip
 *    only: under it the quest card (b121: under the band) and the buff row (BTN_TOP) start lower, so it covers neither.
 *  - THE TOUCH: a larger box round it ([HIT_W] x [HIT_H], the map's top right corner), checked by the aura pane that lies exactly over
 *    the map (its own-touch test), so the map underneath never sees that touch; one touch opens the Calendar room, in a fight too.
 *  - THE PAD ROUTE (Home's focus model): the OPEN map module's cursor (D-pad Right from the map onto the auras) has one more stop, the
 *    same button at the top right next to the buffs' first row ([fullBox]); Right from the first row's right end reaches it, A opens
 *    the Calendar, Left / B go back. With no auras Right goes straight onto it.
 */
object HomeCalendarButton {
    /** the band's height (HomeView.ZONE_BAND_BOTTOM - ZONE_BAND_TOP): the button is square */
    const val SIZE = 64f
    /** between the band's shortened end and the button */
    const val GAP = 6f
    /** the band's inset from the map's left / right edges and its top (HomeView's) */
    const val EDGE = 8f
    const val TOP = 8f
    /** the finger's box, the map's top right corner: wider than the button, and ending above the buff row (BTN_TOP = 80) */
    const val HIT_W = 120f
    const val HIT_H = 78f

    /** the band's right edge when the button shows ([mapRight] = the map's right edge) */
    fun bandRight(mapRight: Float): Float = mapRight - EDGE - SIZE - GAP

    /** the button's box (left, top, right, bottom) on a map whose right edge is [mapRight] and top is [mapTop] */
    fun box(mapRight: Float, mapTop: Float): FloatArray = floatArrayOf(mapRight - EDGE - SIZE, mapTop + TOP, mapRight - EDGE, mapTop + TOP + SIZE)

    /** the finger's box in the aura pane's own coordinates (the pane lies exactly over the map, [paneW] wide) */
    fun hit(paneW: Float): FloatArray = floatArrayOf(paneW - HIT_W, 0f, paneW, HIT_H)

    fun inHit(x: Float, y: Float, paneW: Float): Boolean = x >= paneW - HIT_W && x <= paneW && y >= 0f && y <= HIT_H

    /** the open map module's stop: the top right, [fullRight] in from the edge (the map's own column), at [fullTop] */
    fun fullBox(paneW: Float, fullRight: Float, fullTop: Float): FloatArray =
        floatArrayOf(paneW - fullRight - SIZE, fullTop, paneW - fullRight, fullTop + SIZE)

    /** the open map's aura rows end this far left of where they did, so the button has the corner */
    const val FULL_SHIFT = SIZE + GAP

    fun inBox(x: Float, y: Float, b: FloatArray, pad: Float = 0f): Boolean =
        x >= b[0] - pad && x <= b[2] + pad && y >= b[1] - pad && y <= b[3] + pad
}
