package com.jrees.wowcompanion

/**
 * THE CHAT MODULE'S CONTROLLER. Pure Kotlin, so the rules are tested off the device (tools/chat_test). ChatView owns the drawing
 * and does what [press] returns. The module is a guest of Quests' generic facility (id "chat"): the keys arrive as the game names
 * them, the D-pad on the press, the rest on the release; the guest keeps LB, RB, Select, LT and RT too. Start is Home's pause
 * wheel in every room (the user, 10-09): it reaches the room only while the keyboard is up (ChatView.ownsStart).
 *
 * THE CHAT REWORK (the user, 2026-10-07; UI Art's SHEET_chat_rework, "we can build it as shown"):
 *   the cursor starts IN THE MESSAGE BAR (the user, 2026-09-30), there the moment the module opens
 *   LB / RB             the chat TYPE, from anywhere (the X menu and the filter row are gone); on the log screen D-pad left /
 *                       right too (the user, 2026-10-07), except on a selected line, where they pick its links
 *   X                   QUICK CHAT <-> the KEYBOARD: the board under the bar turns its page (no board yet: quick chat)
 *   A on the bar        THE INPUT the Settings name (the user, b121: the keyboard by default); B closes it again
 *   Select / View       the FILTER MENU (the Map room's Show:; on Start until 10-09); while the keyboard is up, nothing
 *   D-pad up / down     from the bar up into the log (newest line first), and back down; down from the bar into quick chat
 *   D-pad left / right  on the selected line: its LINKS (the sender first, then what the text links); in quick chat, the tiles
 *   A                   what a touch on the cursor's thing does: the bar opens the keyboard, a line is selected (the cursor on its
 *                       first link), a link opens its view (the game's tooltip; a player's actions), a tile SENDS at once
 *   Y                   back down to the message bar (the user's earlier rule); an open menu or view closes on the way
 *   B                   one step back: a link's view, the menu, the board, the selected line; with nothing open, Home
 * While the KEYBOARD is up it has the buttons ([ChatKeys]: LT delete, RT shift, Start Send, B closes), except X / LB / RB.
 */
class ChatPad {

    enum class Board { NONE, QUICK, KEYS }

    sealed class Focus {
        /** a line of the log by its place in the shown list; -1 = the newest */
        data class Line(val i: Int) : Focus()
        /** link [k] of the SELECTED line [i] (0 = its sender when the line has one) */
        data class Link(val i: Int, val k: Int) : Focus()
        /** quick chat's tile [t] (0..11, three across) */
        data class Quick(val t: Int) : Focus()
        /** the filter menu's row [m] */
        data class Menu(val m: Int) : Focus()
        object Field : Focus() { override fun toString() = "Field" }
    }

    /** What the module shows right now, as far as the controller needs it. */
    data class Scene(
        val lines: Int,                     // lines in the shown list
        val selected: Int? = null,          // the selected line's place in it
        val links: Int = 0,                 // the selected line's links (its sender counted)
        val board: Board = Board.NONE,
        val menu: Boolean = false,          // Start's filter menu is open
        val menuItems: Int = 6,
        val menuCurrent: Int = 0,           // the filter shown now
        val view: Boolean = false,          // a link's view is open (on the link under the cursor)
        val viewAct: Boolean = false,       // and it has an action, which A on the link does (a player's: Whisper)
        val quickTiles: Int = 12,
        val defaultBoard: Board = Board.KEYS,   // what A on the bar opens (the Settings choice, the user's default: the keyboard)
    )

    /** What the module is to do. */
    sealed class Act {
        object Glow : Act() { override fun toString() = "Glow" }            // only redraw
        data class Select(val i: Int) : Act()
        object Deselect : Act() { override fun toString() = "Deselect" }
        data class Type(val step: Int) : Act()
        data class Board(val to: ChatPad.Board) : Act()
        data class Quick(val t: Int) : Act()
        data class Menu(val open: Boolean) : Act()
        data class Filter(val m: Int) : Act()
        data class Open(val k: Int) : Act()
        object CloseView : Act() { override fun toString() = "CloseView" }
        data class ViewAct(val k: Int) : Act()
        object Leave : Act() { override fun toString() = "Leave" }
    }

    /** where the cursor is; null before the module shows */
    var focus: Focus? = null
    private var lastQuick = 0
    private var beforeMenu: Focus? = null

    /** B leads Home (nothing open that B would close first): the Home button shows it */
    fun leadsHome(s: Scene) = !s.menu && !s.view && s.board == Board.NONE && s.selected == null

    /**
     * The module opens: the cursor is there at once, on what is chosen (the standing rule, 09-30). Quick chat is up from the start
     * (the user, 2026-10-07), so it stands on the tile last sent (the first time "Hello!"); with no board, in the message bar.
     */
    fun show(s: Scene) {
        focus = if (s.board == Board.QUICK) Focus.Quick(lastQuick.coerceIn(0, s.quickTiles - 1)) else Focus.Field
        settle(s)
    }

    /** where the cursor goes when quick chat comes up some other way (the keyboard's Quick chat key): the tile last sent */
    fun quickFocus(s: Scene): Focus = Focus.Quick(lastQuick.coerceIn(0, s.quickTiles - 1))

    /** the cursor on something that is gone moves to the nearest thing that is there */
    fun settle(s: Scene) {
        focus = when (val f = focus) {
            null -> null
            is Focus.Line -> when {
                s.lines == 0 -> Focus.Field
                else -> {
                    val i = if (f.i < 0 || f.i >= s.lines) s.lines - 1 else f.i
                    if (i == s.selected && s.links > 0) Focus.Link(i, 0) else Focus.Line(i)
                }
            }
            is Focus.Link -> when {
                s.selected == null || s.lines == 0 -> if (s.lines == 0) Focus.Field else Focus.Line(f.i.coerceIn(0, s.lines - 1))
                s.links == 0 -> Focus.Line(s.selected)
                else -> Focus.Link(s.selected, f.k.coerceIn(0, s.links - 1))
            }
            is Focus.Quick -> if (s.board != Board.QUICK) Focus.Field else Focus.Quick(f.t.coerceIn(0, s.quickTiles - 1))
            is Focus.Menu -> if (!s.menu) (beforeMenu ?: Focus.Field) else Focus.Menu(f.m.coerceIn(0, s.menuItems - 1))
            Focus.Field -> Focus.Field
        }
        if (s.menu && focus !is Focus.Menu) focus = Focus.Menu(s.menuCurrent.coerceIn(0, s.menuItems - 1))
    }

    /** One press. Returns what the module is to do, in order; the cursor has already moved. */
    fun press(key: String, s: Scene): List<Act> {
        settle(s)
        val f = focus ?: Focus.Field.also { focus = it }       // never waits for a second press
        return when (key) {
            "PADLSHOULDER" -> listOf(Act.Type(-1))
            "PADRSHOULDER" -> listOf(Act.Type(1))
            // the user, 10-09: Start opens Home's pause wheel in every room, so the filter menu is on Select / View; Start reaches
            // the room only while the keyboard is up (Home asks ownsStart()), and the keyboard takes it as Send. The input up: no
            // filters there (the user, b121)
            "PADBACK" -> if (s.board != Board.NONE) emptyList() else menu(f, s)
            "PAD3" -> swap(s)
            "PADDUP" -> { focus = up(f, s); moved(f, s) }
            "PADDDOWN" -> { focus = down(f, s); moved(f, s) }
            "PADDLEFT" -> side(f, s, -1)
            "PADDRIGHT" -> side(f, s, 1)
            "PAD1" -> a(f, s)
            "PAD2" -> b(f, s)
            "PAD4" -> bar(s)
            else -> emptyList()
        }
    }

    /** a link's view belongs to the link under the cursor: moving off it closes the view */
    private fun moved(from: Focus, s: Scene): List<Act> = if (s.view && focus != from) listOf(Act.CloseView) else listOf(Act.Glow)

    /** Select / View: the filter menu, on the filter shown now; again closes it */
    private fun menu(f: Focus, s: Scene): List<Act> =
        if (s.menu) { focus = beforeMenu ?: Focus.Field; listOf(Act.Menu(false)) }
        else {
            beforeMenu = if (f is Focus.Menu) Focus.Field else f
            focus = Focus.Menu(s.menuCurrent.coerceIn(0, s.menuItems - 1))
            (if (s.view) listOf<Act>(Act.CloseView) else emptyList()) + Act.Menu(true)
        }

    /** X: quick chat <-> the keyboard (the user's Q2); with no board up, quick chat */
    private fun swap(s: Scene): List<Act> {
        val to = if (s.board == Board.QUICK) Board.KEYS else Board.QUICK
        val closing = ArrayList<Act>()
        if (s.menu) closing.add(Act.Menu(false))
        if (s.view) closing.add(Act.CloseView)
        focus = if (to == Board.QUICK) Focus.Quick(lastQuick.coerceIn(0, s.quickTiles - 1)) else Focus.Field
        return closing + Act.Board(to)
    }

    /** Y: back to the message bar from anywhere; an open menu or view closes on the way */
    private fun bar(s: Scene): List<Act> {
        focus = Focus.Field
        val acts = ArrayList<Act>()
        if (s.menu) acts.add(Act.Menu(false))
        if (s.view) acts.add(Act.CloseView)
        if (acts.isEmpty()) acts.add(Act.Glow)
        return acts
    }

    private fun lineFocus(i: Int, s: Scene): Focus = if (i == s.selected && s.links > 0) Focus.Link(i, 0) else Focus.Line(i)

    private fun up(f: Focus, s: Scene): Focus = when (f) {
        is Focus.Line -> lineFocus(maxOf(0, f.i - 1), s)
        is Focus.Link -> lineFocus(maxOf(0, f.i - 1), s).let { if (f.i == 0) f else it }
        is Focus.Quick -> if (f.t >= 3) Focus.Quick(f.t - 3) else Focus.Field
        is Focus.Menu -> Focus.Menu(maxOf(0, f.m - 1))
        Focus.Field -> if (s.lines > 0) lineFocus(s.lines - 1, s) else f
    }

    private fun down(f: Focus, s: Scene): Focus = when (f) {
        is Focus.Line, is Focus.Link -> {
            val i = if (f is Focus.Line) f.i else (f as Focus.Link).i
            if (i < s.lines - 1) lineFocus(i + 1, s) else Focus.Field
        }
        is Focus.Quick -> if (f.t + 3 < s.quickTiles) Focus.Quick(f.t + 3) else f
        is Focus.Menu -> Focus.Menu(minOf(s.menuItems - 1, f.m + 1))
        Focus.Field -> if (s.board == Board.QUICK) Focus.Quick(lastQuick.coerceIn(0, s.quickTiles - 1)) else f
    }

    private fun side(f: Focus, s: Scene, step: Int): List<Act> {
        when (f) {
            is Focus.Link -> {
                val k = (f.k + step).coerceIn(0, maxOf(0, s.links - 1))
                if (k != f.k && s.view) { focus = Focus.Link(f.i, k); return listOf(Act.CloseView) }
                focus = Focus.Link(f.i, k)
            }
            is Focus.Quick -> {
                val col = f.t % 3 + step
                if (col in 0..2 && f.t + step < s.quickTiles) focus = Focus.Quick(f.t + step)
            }
            // THE TYPE ON THE D-PAD TOO (the user: "before opening the message window, I want the message type tabs to be able to be
            // changed by left and right on the Dpad in the chat module"): on the log screen, from the bar or a line; a SELECTED
            // line keeps left / right for its links (above)
            is Focus.Line, Focus.Field -> if (s.board == Board.NONE && !s.menu) return listOf(Act.Type(step))
            else -> {}
        }
        return listOf(Act.Glow)
    }

    private fun a(f: Focus, s: Scene): List<Act> = when (f) {
        is Focus.Line -> when {
            s.selected != f.i -> { focus = Focus.Line(f.i); listOf(Act.Select(f.i)) }
            else -> listOf(Act.Deselect)                                    // a line with nothing to pick: as a second tap
        }
        is Focus.Link -> when {
            s.view && s.viewAct -> listOf(Act.ViewAct(f.k))                  // the open view's action (a player's: Whisper)
            s.view -> listOf(Act.CloseView)                                  // A again on a tooltip: it goes
            else -> listOf(Act.Open(f.k))
        }
        is Focus.Quick -> { lastQuick = f.t; listOf(Act.Quick(f.t)) }
        is Focus.Menu -> { focus = beforeMenu ?: Focus.Field; listOf(Act.Filter(f.m), Act.Menu(false)) }
        // A on the bar: THE INPUT, the one the Settings name (the user, b121: "it should still open with A after opening it")
        Focus.Field -> {
            focus = if (s.defaultBoard == Board.QUICK) Focus.Quick(lastQuick.coerceIn(0, s.quickTiles - 1)) else Focus.Field
            listOf(Act.Board(s.defaultBoard))
        }
    }

    /** B: one step back, in the order things cover the module; with nothing open, Home */
    private fun b(f: Focus, s: Scene): List<Act> = when {
        s.view -> listOf(Act.CloseView)                                     // the cursor stays on its link
        s.menu -> { focus = beforeMenu ?: Focus.Field; listOf(Act.Menu(false)) }
        s.board != Board.NONE -> { focus = Focus.Field; listOf(Act.Board(Board.NONE)) }        // the input goes: the log again
        s.selected != null -> { focus = Focus.Line(s.selected); listOf(Act.Deselect) }
        else -> { focus = null; listOf(Act.Leave) }
    }
}
