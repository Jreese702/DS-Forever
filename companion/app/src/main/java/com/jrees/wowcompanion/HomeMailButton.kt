package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE PLAYER'S UNREAD MAIL, as the game's own minimap mail icon knows it (the user, 2026-11-16: "we need to add an icon somewhere to the
 * home menu for when the player has mail"): the calendar document's `mail` (Calendar c15, read on the game's mail events only).
 * [on] = HasNewMail(); [from] = up to three senders, GetLatestThreeSenders() as the game's tooltip lists them (empty when the game names
 * nobody). The names are the GAME'S text for the bottom screen only: never through Tr.t, never in a log line or a report.
 */
data class HomeMail(val on: Boolean, val from: List<String> = emptyList()) {
    /** the card's heading: the game's tooltip says "Unread mail from:" over names, else "You have unread mail" (terse here) */
    val heading: String get() = if (from.isNotEmpty()) "Unread mail from" else "Unread mail"

    override fun toString(): String = "HomeMail(on=$on, from=${from.size})"     // a log line never carries a name

    companion object {
        const val MAX = 3

        /**
         * The calendar document's (`{"type":"calendar","c":{...}}`) `mail`: null when it has none (an older add-on, not read yet), or
         * when the game's rule switched the notification off (`off`: the game shows no icon, nor does Home).
         */
        fun parse(msg: JSONObject?): HomeMail? {
            val m = (msg?.optJSONObject("c") ?: msg)?.optJSONObject("mail") ?: return null
            if (m.optBoolean("off")) return null
            val on = m.optBoolean("on")
            if (!on) return HomeMail(false)
            val a = m.optJSONArray("from")
            val names = ArrayList<String>()
            if (a != null) for (i in 0 until a.length()) {
                val s = a.optString(i, "").trim()
                if (s.isNotEmpty() && names.size < MAX) names.add(s)
            }
            return HomeMail(true, names)
        }
    }
}

/**
 * THE MAIL ENVELOPE ON HOME (the user, 2026-11-16: "we need to add an icon somewhere to the home menu for when the player has mail").
 * The picks: BY THE CALENDAR BUTTON, a small envelope beside it at the end of Home's map-name band, shown only while the player has
 * mail; a touch shows who it is from in a small card, as the game's minimap mail tooltip does. Pure geometry (no Android types: the
 * off-device tests read it); HomeView draws it with UiKit.mailButton / UiKit.mailCard.
 *
 *  - AT REST AND IN A FIGHT: a square the band's own height LEFT of the Calendar button, [GAP] between the two (the game's minimap
 *    has its mail icon beside the calendar's date square too); the band ends [GAP] before it, so while it shows the band is [SHIFT]
 *    shorter (a long zone name steps its size down before it is cut: UiKit.zoneBand). Drawn with the band and the Calendar button: it
 *    fades and slides with them (the pause menu's slide, a move) and shows in the fight view. It lies over the band's strip only, so
 *    it covers neither the quest card (under the band) nor the buff row (BTN_TOP).
 *  - THE TOUCH: the map's top right corner is the two buttons' finger box, split at the middle of the gap between them ([split]):
 *    the envelope's part reaches [LEAD] into the band's end ([hit]), the Calendar's keeps the corner ([calHit]).
 *  - THE OPEN MAP MODULE: its cursor's stop left of the Calendar's ([fullBox]); the aura rows end [SHIFT] further left. Right from the
 *    buffs' first row reaches the envelope, Right again the Calendar; A opens / closes the card, B closes the card, then goes back.
 *  - THE CARD: under the envelope, its right edge on the envelope's ([cardLeft]), never past the map's left inset; the heading and
 *    up to three names. B (the read-only watch at rest, the module's own B in the open map) or a touch anywhere on Home closes it.
 */
object HomeMailButton {
    /** the band's height, as the Calendar button: the two stand side by side as a pair */
    const val SIZE = HomeCalendarButton.SIZE
    const val GAP = HomeCalendarButton.GAP
    /** how much further left the band ends (and the open map's aura rows) while the envelope shows */
    const val SHIFT = SIZE + GAP
    /** the envelope's finger box reaches this far into the band's end (the band's touch is the map's) */
    const val LEAD = 24f

    /** the band's right edge ([mapRight] = the map's right edge), with the envelope ([mail]) or without */
    fun bandRight(mapRight: Float, mail: Boolean): Float = HomeCalendarButton.bandRight(mapRight) - (if (mail) SHIFT else 0f)

    /** the envelope's box (left, top, right, bottom): the Calendar button's, [SHIFT] to the left */
    fun box(mapRight: Float, mapTop: Float): FloatArray =
        HomeCalendarButton.box(mapRight, mapTop).let { b -> floatArrayOf(b[0] - SHIFT, b[1], b[2] - SHIFT, b[3]) }

    /** where the corner's finger box is split between the two buttons: the middle of the gap between them, in the pane's px */
    fun split(paneW: Float): Float = paneW - HomeCalendarButton.EDGE - SIZE - GAP / 2f

    /** the envelope's finger box in the aura pane's own coordinates (the pane lies exactly over the map) */
    fun hit(paneW: Float): FloatArray = floatArrayOf(box(paneW, 0f)[0] - LEAD, 0f, split(paneW), HomeCalendarButton.HIT_H)

    fun inHit(x: Float, y: Float, paneW: Float): Boolean = hit(paneW).let { h -> x >= h[0] && x < h[2] && y >= h[1] && y <= h[3] }

    /** the Calendar button's finger box while the envelope shows: the corner from [split] on (its own [HomeCalendarButton.hit] else) */
    fun calHit(paneW: Float): FloatArray = floatArrayOf(split(paneW), 0f, paneW, HomeCalendarButton.HIT_H)

    fun inCalHit(x: Float, y: Float, paneW: Float): Boolean = calHit(paneW).let { h -> x >= h[0] && x <= h[2] && y >= h[1] && y <= h[3] }

    /** the open map module's stop: left of the Calendar's ([HomeCalendarButton.fullBox]) by [SHIFT] */
    fun fullBox(paneW: Float, fullRight: Float, fullTop: Float): FloatArray =
        HomeCalendarButton.fullBox(paneW, fullRight, fullTop).let { b -> floatArrayOf(b[0] - SHIFT, b[1], b[2] - SHIFT, b[3]) }

    /** the open map's split between the two stops' finger boxes (the middle of their gap) */
    fun fullSplit(paneW: Float, fullRight: Float): Float = paneW - fullRight - SIZE - GAP / 2f

    // ---------------------------------------------------------------- the card

    /** between the envelope's bottom and the card's top */
    const val CARD_GAP = 6f
    /** the card's padding inside the kit's menu box (UiKit.menu's own 10 px edge included) */
    const val CARD_PAD = 22f
    /** the heading's size and line, a name's size and line */
    const val HEAD_SIZE = 28f
    const val HEAD_LINE = 40f
    const val NAME_SIZE = 26f
    const val NAME_LINE = 36f
    /** never narrower than this (a short name) */
    const val CARD_MIN_W = 240f

    /** the card's height for [names] names under the heading */
    fun cardHeight(names: Int): Float = CARD_PAD * 2f + HEAD_LINE + names.coerceIn(0, HomeMail.MAX) * NAME_LINE

    /** the card's width for its widest line ([textW]), at least [CARD_MIN_W], at most [maxW] */
    fun cardWidth(textW: Float, maxW: Float): Float = (textW + CARD_PAD * 2f).coerceAtLeast(CARD_MIN_W).coerceAtMost(maxW)

    /** the card's left edge: its right on the envelope's right ([anchorRight]), never left of [minLeft] (the map's inset) */
    fun cardLeft(anchorRight: Float, w: Float, minLeft: Float): Float = maxOf(minLeft, anchorRight - w)
}
