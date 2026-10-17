package com.jrees.wowcompanion

/**
 * THE MAP ROOM'S CONTROLLER (pure: tools/map_test). The user's rulings, 2026-10-07 (via tracking, word for word in feedback memory):
 * B is Home everywhere ("B needs to be return to home for consistancy"); the quest panel is always out (not on Azeroth), every quest
 * under collapsible zone headers; D-pad right on the map = the quest list, left in the list = the map (after b106).
 * After b107 the GAME'S cursor is back: "lets bring back the map cursor instead. but I want it to look just like the one that wow
 * forver does, with the golden lines and cross hair", with the game's legend: "A/X change map, and then when hovering over a quest give
 * it the A Focus/Track(if its currently focused then dont list this option) and X track/Untrack". And the user's three picks: the D-pad
 * "up and down can also go up and down map layers. left does nothing."; the stick slows down over a marker, never jumps; Y = the
 * game's "More actions". Forever's own map (blizzard_worldmap.lua) binds A = GamepadMapClick (the hovered pin, then the zone under the
 * cursor) and X = GamepadMapUp (up a level) as ONE prompt "Change map", Y = More actions (the current area's drop-down), Start = the
 * filters. So now:
 *   MAP   the left stick: the free cursor (the view, [cursorStep]). A: open the zone / capital under it; on a quest marker track + focus
 *         it (nothing on the focused one). X: up a level; on a quest marker track / untrack it. D-pad right: the quest list; up,
 *         down, left: nothing (the user after b109). Y: the shown map's neighbours (More actions).
 *         B: Home. LB / RB: zoom out / in. Select / View: Show: (the game's own filter button; Start is the app's pause wheel
 *         from every room, the user 2026-10-09).
 *   LIST  D-pad up / down: the rows; left: back to the map. A: a header opens / closes its group, a quest is tracked + focused.
 *         X: track or untrack. Y on a quest: its options, the game's quest-log menu ([questOptions]; the user after b108: "When
 *         browsing the quests pane, Y should have a more options on the quest like with wow to share and abandon quests"). B: Home.
 * A menu (Show:, More actions / a breadcrumb's list, a quest's options, Abandon's question) takes the keys first while it is open
 * (the view; [menuMove] for the lists).
 */
object MapRoomKeys {
    enum class Region { MAP, LIST }

    sealed class Do {
        object None : Do()
        object Home : Do()
        object Up : Do()
        object ShowMenu : Do()
        object MoreActions : Do()
        object ToMap : Do()
        object ToList : Do()
        data class ToggleGroup(val zone: String) : Do()
        object ZoomIn : Do()
        object ZoomOut : Do()
        data class Open(val zone: Int) : Do()
        data class TrackFocus(val quest: Int) : Do()
        data class ToggleTrack(val quest: Int) : Do()
        data class ListMove(val d: Int) : Do()
        data class QuestOptions(val quest: Int) : Do()
    }

    /**
     * where the cursor is: [zoneView] = a zone (quest markers) rather than a continent / the world (zones); [hoverZone] / [hoverQuest] /
     * [hoverCity] = the zone / quest marker / map to open (a capital, or a neighbouring zone in a zone's art) under the map cursor (a
     * marker wins over a map around it); [hoverFocused] / [hoverTracked] = that quest is the focused one / tracked; [listQuest] /
     * [listHeader] = the list cursor's row; [hasParent] = there is a level up; [listHasQuests] = the quest list has a row to go to
     */
    class Ctx(
        val region: Region, val zoneView: Boolean, val hoverZone: Int?, val hoverQuest: Int?, val listQuest: Int?,
        val hasParent: Boolean, val listHasQuests: Boolean,
        val listHeader: String? = null, val hoverCity: Int? = null, val hoverFocused: Boolean = false, val hoverTracked: Boolean = false,
    )

    fun press(k: String, c: Ctx): Do {
        if (k == "PADBACK") return Do.ShowMenu
        if (k == "PAD2") return Do.Home
        // the user after b107: Y on the map = the game's "More actions" (the shown map's drop-down of its neighbours; none on the
        // world); in the list (after b108) Y on a quest = its options (the game's OpenQuestOptions), on a header nothing
        if (k == "PAD4") return if (c.region == Region.LIST) c.listQuest?.takeIf { c.listHeader == null }?.let { Do.QuestOptions(it) } ?: Do.None
                                else if (c.hasParent) Do.MoreActions else Do.None
        if (c.region == Region.LIST) return when (k) {
            "PADDUP" -> Do.ListMove(-1)
            "PADDDOWN" -> Do.ListMove(1)
            "PADDLEFT" -> Do.ToMap
            // a zone header opens / closes its group; a quest: track + focus ("pressing A focuses")
            "PAD1" -> c.listHeader?.let { Do.ToggleGroup(it) } ?: c.listQuest?.let { Do.TrackFocus(it) } ?: Do.None
            "PAD3" -> c.listQuest?.let { Do.ToggleTrack(it) } ?: Do.None
            // one zoom on every map, the world to a city (test53: "zoom out all the way to continents ... all the way in to the cities")
            "PADLSHOULDER" -> Do.ZoomOut
            "PADRSHOULDER" -> Do.ZoomIn
            else -> Do.None
        }
        val quest = c.hoverQuest?.takeIf { c.zoneView }
        return when (k) {
            // the user after b109: "remove the up and down dpad for map level" (reversing b107's layers on up / down): the map's
            // layers change with A / X only, the game's way; up, down and left do nothing on the map (the game's up / down toggle its
            // full-screen map, which the room does not have)
            "PADDUP", "PADDDOWN", "PADDLEFT" -> Do.None
            "PADDRIGHT" -> if (c.listHasQuests) Do.ToList else Do.None
            // the game's A/X "Change map"; on a quest marker the user's A Focus/Track (not offered on the focused one) and X Track/Untrack
            "PAD1" -> if (quest != null) (if (c.hoverFocused) Do.None else Do.TrackFocus(quest)) else openable(c)?.let { Do.Open(it) } ?: Do.None
            "PAD3" -> if (quest != null) Do.ToggleTrack(quest) else if (c.hasParent) Do.Up else Do.None
            // one zoom on every map, the world to a city (test53: "zoom out all the way to continents ... all the way in to the cities")
            "PADLSHOULDER" -> Do.ZoomOut
            "PADRSHOULDER" -> Do.ZoomIn
            else -> Do.None
        }
    }

    /** the map under the cursor that A opens: a capital in a zone, a zone on a continent / the world */
    private fun openable(c: Ctx): Int? = if (c.zoneView) c.hoverCity else c.hoverZone

    /** where the room's next B leads (the game's close while it leads Home: Map.lua's spec.back) */
    @Suppress("UNUSED_PARAMETER")
    fun bLeadsHome(c: Ctx): Boolean = true            // B is Home everywhere (the user after b104)

    /** the legend's lines on the map side (the game's footer; the view draws them): which prompts show for [c] */
    enum class Prompt { CHANGE_MAP, FOCUS_TRACK, FOCUS, TRACK, UNTRACK, BACK, QUESTS }

    fun legend(c: Ctx): List<Prompt> {
        val out = ArrayList<Prompt>()
        val quest = c.hoverQuest?.takeIf { c.zoneView && c.region == Region.MAP }
        if (quest != null) out += questPrompts(c.hoverTracked, c.hoverFocused) else out += Prompt.CHANGE_MAP
        out += Prompt.BACK
        if (c.listHasQuests) out += Prompt.QUESTS
        return out
    }

    /**
     * A quest's A and X lines, map and list alike, as the user words them (after b107's look): "if its currently tracked, it should say
     * untrack, if its currently untracked it should say track / if its currently tracked but not focused, then the A option should just
     * say Focus." A: untracked = Focus/Track (it does both), tracked = Focus, focused = none (the user: "dont list this option").
     */
    fun questPrompts(tracked: Boolean, focused: Boolean): List<Prompt> {
        val out = ArrayList<Prompt>()
        if (!focused) out += if (tracked) Prompt.FOCUS else Prompt.FOCUS_TRACK
        out += if (tracked) Prompt.UNTRACK else Prompt.TRACK
        return out
    }

    /**
     * THE GAME'S CURSOR MOVE: Forever's SoftCursor moves at the stick's tilt times its top speed (SoftCursorMixin:OnGamePadStick:
     * speed = stick * maxSpeed), with no snapping. Here past a round dead-zone [dead] (rescaled, so the slowest crawl starts at the
     * zone's edge), [maxPxS] px a second at full tilt, over [dtS] seconds; the user's pick after b107: [overMarker] slows it to
     * [SLOW_OVER_MARKER] ("Slow down over a marker, never jump"). x right, y down; returns the move in px.
     */
    fun cursorStep(x: Float, y: Float, dtS: Float, maxPxS: Float, overMarker: Boolean, dead: Float = STICK_DEAD): Pair<Float, Float> {
        val m = Math.hypot(x.toDouble(), y.toDouble()).toFloat()
        if (m <= dead) return 0f to 0f
        val tilt = ((m - dead) / (1f - dead)).coerceIn(0f, 1f)
        val v = maxPxS * dtS * tilt * (if (overMarker) SLOW_OVER_MARKER else 1f) / m
        return x * v to y * v
    }
    const val STICK_DEAD = 0.15f
    const val SLOW_OVER_MARKER = 0.35f

    /** the stick is pushed past the dead-zone (the cursor moves) */
    fun stickMoves(x: Float, y: Float, dead: Float = STICK_DEAD): Boolean = Math.hypot(x.toDouble(), y.toDouble()) > dead

    /** the point nearest ([x], [y]) within [r] (screen px, so a marker's own drawn size), or null */
    fun underPx(points: List<Triple<Int, Float, Float>>, x: Float, y: Float, r: Float): Int? =
        points.map { (id, px, py) -> id to Math.hypot((px - x).toDouble(), (py - y).toDouble()) }
            .filter { it.second <= r }.minByOrNull { it.second }?.first

    /**
     * The More actions list (a breadcrumb's neighbours, [n] of them, [perCol] a column, in column order) under the D-pad: up / down one
     * row (stopping at the ends), left / right a column. Returns the new row.
     */
    fun menuMove(sel: Int, n: Int, perCol: Int, k: String): Int {
        if (n <= 0) return 0
        val to = when (k) {
            "PADDUP" -> sel - 1
            "PADDDOWN" -> sel + 1
            "PADDLEFT" -> sel - perCol
            "PADDRIGHT" -> sel + perCol
            else -> sel
        }
        return if (to in 0 until n) to else sel
    }

    /** a quest's options (the game's QuestMapLogTitleButton_CreateContextMenu), in its order */
    enum class Opt { TRACK, UNTRACK, FOCUS, STOP_FOCUS, SHARE, ABANDON }

    /**
     * The game's quest-log menu for a quest: Track / Untrack Quest, Focus Quest / Stop Focusing (by its state), Share Quest (always
     * listed; the view greys it while the game would refuse: not in a party, or not shareable), Abandon Quest only when the game
     * lets it be abandoned. The game's own "Share in Chat" (gamepad mode) is left out: the room has no way to put a link in chat.
     */
    fun questOptions(tracked: Boolean, focused: Boolean, canAbandon: Boolean): List<Opt> {
        val out = arrayListOf(if (tracked) Opt.UNTRACK else Opt.TRACK, if (focused) Opt.STOP_FOCUS else Opt.FOCUS, Opt.SHARE)
        if (canAbandon) out += Opt.ABANDON
        return out
    }

    /**
     * THE EDGE PAN with the drawer out (the user after b108: "I dont like that the map slides back in as I move back left, I would
     * rather it slide left when I hit the left side of the map"): the map's slide [push] (px, -[maxPush]..0) stays while the point
     * kept in view ([x], on screen with the slide) is inside [left]..[right]; pushed past the right edge (the drawer's) the map
     * slides left with it, past the left edge it slides back. Returns the new slide.
     */
    fun edgePan(push: Float, x: Float, left: Float, right: Float, maxPush: Float): Float {
        var p = push
        if (x > right) p -= x - right else if (x < left) p += left - x
        return p.coerceIn(-maxPush, 0f)
    }

    /** the stick's sector (PadInput: 0 = up, clockwise, 8 of them) as a direction, y down */
    fun sectorDir(sector: Int): Pair<Float, Float>? = when (sector) {
        0 -> 0f to -1f; 1 -> 1f to -1f; 2 -> 1f to 0f; 3 -> 1f to 1f
        4 -> 0f to 1f; 5 -> -1f to 1f; 6 -> -1f to 0f; 7 -> -1f to -1f
        else -> null
    }

    /**
     * THE LEFT STICK IN THE QUEST LIST, ONE ROW PER PUSH (the user after b106: "behaves a bit more like the Dpad would have"): it
     * engages past [on] of full tilt (one step, as a D-pad press), lets go only under [off] (hysteresis: rolling around the edge never
     * steps again), and while it stays pushed its direction is what the D-pad's own hold-repeat steps along.
     */
    class StickSteps(private val on: Float = 0.65f, private val off: Float = 0.40f) {
        var engaged = false
            private set
        var dir: Pair<Float, Float>? = null
            private set

        /** a new stick vector (x right, y down, -1..1); true = step now (the push itself) */
        fun update(x: Float, y: Float): Boolean {
            val m = Math.hypot(x.toDouble(), y.toDouble()).toFloat()
            if (!engaged) {
                if (m < on) return false
                engaged = true; dir = dirOf(x, y)
                return true
            }
            if (m < off) { engaged = false; dir = null; return false }
            dir = dirOf(x, y)
            return false
        }

        companion object {
            /** the eight sectors (0 = up, clockwise) of a vector, as a direction */
            fun dirOf(x: Float, y: Float): Pair<Float, Float>? {
                val a = Math.toDegrees(Math.atan2(x.toDouble(), -y.toDouble()))
                val sector = (((a + 360.0 + 22.5) % 360.0) / 45.0).toInt() % 8
                return sectorDir(sector)
            }
        }
    }

    /**
     * A on a quest: track AND focus it (the user); what that takes from the game: WATCH while it is not tracked, FOCUS while it is
     * not the focused one; nothing = it is both already
     */
    fun trackFocusActs(tracked: Boolean, focused: Boolean): List<QuestPad.Act> {
        val out = ArrayList<QuestPad.Act>()
        if (!tracked) out.add(QuestPad.Act.WATCH)
        if (!focused) out.add(QuestPad.Act.FOCUS)
        return out
    }

    /** X on a quest: track or untrack it */
    fun toggleTrackAct(tracked: Boolean): QuestPad.Act = if (tracked) QuestPad.Act.UNWATCH else QuestPad.Act.WATCH
}
