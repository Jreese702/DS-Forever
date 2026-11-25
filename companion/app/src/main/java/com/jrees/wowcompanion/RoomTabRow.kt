package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View

/**
 * THE ROOMS' TAB ROW ON TOP (the user, 2026-11-25, on 1.0.41: "On all of the rooms that have the tabs on the right side we need to move the
 * test to the top and make them so that L and R shift the tabs left and right"). Character, Group Finder, Professions and Social had their
 * tabs down the right edge (the game's side tabs); the same icons now stand in ONE ROW under the header, as the Spellbook's book tabs
 * ([UiKit.bookTab], drawn before the page so the page's top edge covers their feet), and the room's page takes the whole width under it
 * (it moves down [DY]; the view draws it translated, so its own layout is unchanged).
 *
 * The house rules for a tab row stay as they are:
 *  - the gaps open round the chosen tab and ease when it changes ([UiKit.TabGaps]; the user after b118);
 *  - L far left and R far right, always ([UiKit.TabGaps.badges] -> [UiKit.tabEnds]; the user, 15 Oct: "Every tabbed room shows L on the
 *    far left and R on the far right, always");
 *  - the game's gold arrows beside the tab the cursor is on, no words, none past an end ([UiKit.moveArrows]; the 2026-10-07 ruling);
 *  - a cursor that lands on a tab opens it, no A (2026-10-07), so Left / Right along the row open the neighbour.
 * L / R ([step]) = the tab before / after, NO WRAP (the Controller room's LB / RB, PadTabCtl p9 / p13: nothing past an end); from the row
 * the cursor moves with the tab, from the page it goes to the new page's own start place (setTab's HOME_FOCUS there).
 * Pure parts in the companion: [step], [mayLand], [lands] (RoomTabRowTest).
 */
object RoomTabsPad {
    /** DirectPad's registration for the rooms' L / R */
    const val ID = "roomtabs"

    /**
     * the capture's holder id (HomeBack.roomsOf's) of the tabbed room named [room] (a Room name), or null for a room whose L / R are its
     * own already or that has no tabs: the four rooms whose side tabs moved on top, and the rooms whose tabs were on top already with no
     * L / R before (the Spellbook's book tabs and Talents' schools, the Spells guest's; the Macros grid's two tabs). Not the Controller
     * room (PadTabCtl's own LB / RB), Quests / Settings / Chat (their own LB / RB), Map (L / R zoom), Bags, Calendar, Home
     */
    fun idOf(room: String): String? = when (room) {
        "CHARACTER" -> "character"
        "GROUPFINDER" -> "groupfinder"
        "PROFESSIONS" -> "professions"
        "SOCIAL" -> "social"
        "SPELLBOOK", "TALENTS" -> "spells"
        "MACROS" -> "macros"
        else -> null
    }

    /**
     * L / R are the room's: it [shown] and holds the controller ([holder]: the capture's holder is its id; or a fight's room mode, where
     * the router hands every press to us: PadOwnerSync.directLive), nothing of ours [covered] it that reads the pad itself, and the room
     * is where its tabs show ([free]: the Spellbook's book, Talents without its Apply box, the Macros grid; always for the four)
     */
    fun live(shown: Boolean, holder: Boolean, roomMode: Boolean, covered: Boolean, free: Boolean = true): Boolean =
        PadOwnerSync.directLive(shown, holder, roomMode) && !covered && free

    /** the capture's name of a bumper's key code (L1 -> PADLSHOULDER, R1 -> PADRSHOULDER), null for any other key */
    fun keyName(keyCode: Int): String? = when (keyCode) {
        android.view.KeyEvent.KEYCODE_BUTTON_L1 -> DirectPadKeys.LB
        android.view.KeyEvent.KEYCODE_BUTTON_R1 -> DirectPadKeys.RB
        else -> null
    }

    /**
     * ONE DECISION PER PRESS (review of the first cut: the launcher's hold and the reader's delivery each asked "does the room own L / R"
     * a looper turn apart, and a relayed copy was dropped by the answer ~200 ms later, so a press the ownership flipped under could reach
     * both the game and the room, or neither). The launcher's key path decides each bumper press ONCE ([decide]): held from the game
     * for the room, or the game's. The app's reader then acts on it only when it was the room's ([take]); a press the game got reaches
     * the room only as its relayed copy (Group Finder's / Social's / Professions' capture extras), as before. With no key path (no
     * launcher key callback saw the press) the reader's own [take] decides by the room's ownership then, and the relayed copy of such a
     * press is dropped ([relayDropped]). Main thread only (the launcher's key callbacks and the reader's deliveries).
     */
    private val decided = HashMap<String, Pair<Boolean, Long>>()
    private val fallbackAt = HashMap<String, Long>()
    /** a decision older than this has lost its delivery (the reader down): never applied to a later press */
    const val DECIDED_MS = 1000L
    /** how late a relayed copy can come (the game -> log -> app trip, ~200 ms, slow frames) */
    const val RELAY_MS = 1500L

    /** the launcher's key path at [now]: bumper [k]'s press is held for the room's tabs ([room]) or left to the game */
    fun decide(k: String, room: Boolean, now: Long) { decided[k] = room to now }

    /** a press held for the room still waits for the reader's delivery (DirectPad claims it then, whoever owns the pad by now) */
    fun heldWaiting(now: Long): Boolean = decided.values.any { it.first && now - it.second <= DECIDED_MS }

    /** the reader delivers bumper [k]'s press at [now]: true = the room acts on it (the key path held it for the room; with no decision
     *  of the key path, the room's when [live]) */
    fun take(k: String, live: Boolean, now: Long): Boolean {
        val d = decided.remove(k)?.takeIf { now - it.second <= DECIDED_MS }
        if (d != null) return d.first
        if (live) fallbackAt[k] = now
        return live
    }

    /** a relayed copy of bumper [k] arrives at [now]: dropped only when the reader acted on that press with no key path's decision */
    fun relayDropped(k: String, now: Long): Boolean = fallbackAt[k]?.let { now - it <= RELAY_MS } == true

    /** tests: forget every decision */
    fun reset() { decided.clear(); fallbackAt.clear() }
}

class RoomTabRow(private val host: View) {
    companion object {
        /** the row's top: under the header (UiKit.header is 62 px; the Spellbook's tabs start at 66) */
        const val TOP = 66f
        /** a tab: the book tab's art at a little under the Spellbook's 116 x 100 (its aspect) */
        const val TAB_W = 108f
        const val TAB_H = 92f
        /** the page's top edge covers the tabs' feet by this much (the Spellbook's 8) */
        const val FEET = 8f
        /** how far the room's page moves down: its old top (62, under the header) to the row's foot less the feet */
        const val DY = TOP + TAB_H - FEET - 62f

        /** L / R: the tab [d] (-1 / +1) from [i] of [n]; null past an end (no wrap) */
        fun step(i: Int, n: Int, d: Int): Int? = (i + d).takeIf { n > 0 && it in 0 until n }

        /**
         * The D-pad between the row and the page: may a move by ([dx], [dy]) from a tab ([fromTab]) / the page land on a tab ([toTab];
         * [toOpen]: the tab whose page shows)? On the row only Left / Right move, along it (Up does nothing; Down leaves it for the page,
         * never onto another tab); from the page only Up reaches the row, and only on the OPEN tab (the row is centred: the tab nearest
         * the cursor would open another page by landing; the Professions room's way), never a sideways or a downward move.
         */
        fun mayLand(fromTab: Boolean, toTab: Boolean, dx: Int, dy: Int, toOpen: Boolean = true): Boolean = when {
            fromTab && dx != 0 -> toTab
            fromTab && dy < 0 -> false
            fromTab -> !toTab
            toTab -> dy < 0 && toOpen
            else -> true
        }

        /** where L / R leave the cursor: on the new tab when it was on the row ([onRow]), else null (the new page's own start) */
        fun lands(onRow: Boolean, to: Int): Int? = if (onRow) to else null
    }

    private val gaps = UiKit.TabGaps(vertical = false)

    /** the tabs' places in the view's own coordinates, from the last [draw] */
    var rects: List<RectF> = emptyList(); private set
    /** the tab drawn lit for the cursor at the last [draw] (null = none): a view whose cursor came to rest later in its frame redraws */
    var cursorDrawn: Int? = null; private set

    /**
     * The row across [w]: [icons] (one per tab), [chosen] the open tab, [cursor] the tab the controller's cursor is on (null = none;
     * the game's lit tab and the gold arrows only then). Draw it BEFORE the page, so the page's top edge covers the tabs' feet.
     */
    fun draw(c: Canvas, w: Float, icons: List<Bitmap?>, chosen: Int, cursor: Int?): List<RectF> {
        val n = icons.size
        val r = gaps.row(n, 0f, w, TOP, TAB_H, TAB_W, chosen.coerceIn(0, maxOf(0, n - 1)), host)
        rects = r
        cursorDrawn = cursor?.takeIf { it in r.indices }
        for (i in 0 until n) UiKit.bookTab(c, r[i], icons[i], i == chosen, cursor = cursor == i)
        if (n > 0) gaps.badges(c)
        if (cursor != null && cursor in r.indices) UiKit.moveArrows(c, r[cursor], left = cursor > 0, right = cursor < n - 1, words = false)
        return r
    }

    /** tab [i]'s box in the room's page coordinates (the page is drawn [DY] lower): the nav / touch box a room keeps with its own */
    fun pageBox(i: Int): RectF? = rects.getOrNull(i)?.let { RectF(it.left, it.top - DY, it.right, it.bottom - DY) }
}
