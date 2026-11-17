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
 *   X                   QUICK CHAT <-> the KEYBOARD: the board under the bar turns its page; with no board up VOICE (Scene.voice, the
 *                       user's pick 2026-11-16: from a line the cursor jumps to the bar), else quick chat as before
 *   D-pad Down on the bar with no board: quick chat (the way there now that X is voice's)
 *   A on the bar        THE INPUT the Settings name (the user, b121: the keyboard by default); B closes it again; with quick chat
 *                       up, the keyboard (to change what a phrase put in the bar)
 *   Select / View       the FILTER MENU: a CHECKLIST of the message types (the user, 2026-11-16: "check a radio box for all of the
 *                       message types that I care about seeing"); A ticks the box under the cursor and the list stays open; Select,
 *                       B or Y closes it. Four columns (the game's Chat Settings groups and the joined channels) under one All box;
 *                       while the keyboard is up, nothing
 *   D-pad up / down     from the bar up into the log (newest line first), and back down; down from the bar into quick chat
 *   D-pad left / right  on the selected line: its LINKS (the sender first, then what the text links); in quick chat, the tiles
 *   A                   what a touch on the cursor's thing does: the bar opens the keyboard, a line is selected (the cursor on its
 *                       first link), a link opens its view (the game's tooltip; a player's actions), a tile PUTS ITS PHRASE IN THE
 *                       BAR and the cursor goes to the bar's Send (the user, 2026-11-16: "after selecting the option I get surprised
 *                       by it auto sending, I think we need to add a send button to that menu to the right of the message bar")
 *   Start               with quick chat up, Send (the keyboard's own rule: Start = Send while a board is up)
 *   Y                   back down to the message bar (the user's earlier rule); an open menu or view closes on the way
 *   B                   one step back: a link's view, the menu, the board, the selected line; with nothing open, Home
 * While the KEYBOARD is up it has the buttons ([ChatKeys]: LT delete, RT shift, Start Send, B closes), except X / LB / RB.
 * The bar's SEND stands right of the field while quick chat is up: D-pad right from the field, up from the tiles' right column.
 * THE WORLD-REFRESH "!" (chat40; the user, 2026-11-16: "the ! notice part should be a button to trigger the refresh"): while Home's
 * world-refresh notice shows ([Scene.notice]) it stands over the log as the top stop: D-pad up from the top line (from the bar with no
 * lines), down back into the log; A = the game's Refresh now ([Act.NoticeGo]); B / Y as on a line.
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
        /** the filter checklist's box [m]: 0 = All, then the columns' boxes, column by column ([Scene.menuCols]) */
        data class Menu(val m: Int) : Focus()
        object Field : Focus() { override fun toString() = "Field" }
        /** the bar's Send, right of the field while quick chat is up */
        object Send : Focus() { override fun toString() = "Send" }
        /** chat40: the world-refresh notice's "!" over the log (while [Scene.notice]) */
        object Notice : Focus() { override fun toString() = "Notice" }
    }

    /** What the module shows right now, as far as the controller needs it. */
    data class Scene(
        val lines: Int,                     // lines in the shown list
        val selected: Int? = null,          // the selected line's place in it
        val links: Int = 0,                 // the selected line's links (its sender counted)
        val board: Board = Board.NONE,
        val menu: Boolean = false,          // Select's filter checklist is open
        val menuCols: List<Int> = listOf(8, 6, 4, 0),   // the checklist's boxes per column (All above them)
        val view: Boolean = false,          // a link's view is open (on the link under the cursor)
        val viewAct: Boolean = false,       // and it has an action, which A on the link does (a player's: Whisper)
        val quickTiles: Int = 12,
        val defaultBoard: Board = Board.KEYS,   // what A on the bar opens (the Settings choice, the user's default: the keyboard)
        val voice: Boolean = false,             // X is voice's (Voice typing on, a microphone): X on the log screen starts it
        val notice: Boolean = false,            // chat40: the world-refresh notice and its "!" show over the log (the log screen)
    ) {
        /** the checklist's boxes: All and every column's */
        val menuItems: Int get() = 1 + menuCols.sum()
    }

    /** What the module is to do. */
    sealed class Act {
        object Glow : Act() { override fun toString() = "Glow" }            // only redraw
        data class Select(val i: Int) : Act()
        object Deselect : Act() { override fun toString() = "Deselect" }
        data class Type(val step: Int) : Act()
        data class Board(val to: ChatPad.Board) : Act()
        data class Quick(val t: Int) : Act()
        data class Menu(val open: Boolean) : Act()
        /** the checklist's box [m] ticked or unticked (0 = All); the list stays open */
        data class Filter(val m: Int) : Act()
        /** the bar's Send: what is in the bar goes */
        object Send : Act() { override fun toString() = "Send" }
        data class Open(val k: Int) : Act()
        object CloseView : Act() { override fun toString() = "CloseView" }
        data class ViewAct(val k: Int) : Act()
        object Leave : Act() { override fun toString() = "Leave" }
        /** VOICE (the user's pick 2026-11-16): the voice page on the message bar (the keyboard's board, its voice page) */
        object Voice : Act() { override fun toString() = "Voice" }
        /** chat40: the "!" pressed: the game's Refresh now (Home's route, Chat's chcmd column 1) */
        object NoticeGo : Act() { override fun toString() = "NoticeGo" }
    }

    /** where the cursor is; null before the module shows */
    var focus: Focus? = null
    private var lastQuick = 0
    private var beforeMenu: Focus? = null
    /** the checklist box the cursor was on when the list closed (it opens there again), and the column All goes down into */
    private var lastMenu = 1
    private var menuCol = 0

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
            Focus.Send -> if (s.board == Board.QUICK) Focus.Send else Focus.Field
            // the notice went (or a board / the menu covers it): the top line, else the bar
            Focus.Notice -> if (noticeStop(s)) Focus.Notice else if (s.lines > 0) lineFocus(0, s) else Focus.Field
        }
        if (s.menu && focus !is Focus.Menu) focus = Focus.Menu(lastMenu.coerceIn(0, s.menuItems - 1))
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
            // Start: Send while quick chat is up (the keyboard takes its own Start before this); the room has it only then
            "PADFORWARD" -> if (s.board == Board.QUICK && !s.menu) listOf(Act.Send) else emptyList()
            // VOICE (the user's pick 2026-11-16, "X starts voice everywhere in Chat; from a chat line, the cursor jumps to the message bar.
            // Inside the open chat keyboard, X still flips to quick chat"): on the log screen X is voice's; with a board up, its page
            "PAD3" -> if (s.voice && s.board == Board.NONE) voice(s) else swap(s)
            "PADDUP" -> { focus = up(f, s); moved(f, s) }
            // "... and D-pad Down from the bar also opens quick chat" (X no longer does from the log screen)
            "PADDDOWN" -> if (f == Focus.Field && s.board == Board.NONE && !s.menu) {
                focus = Focus.Quick(lastQuick.coerceIn(0, s.quickTiles - 1)); listOf(Act.Board(Board.QUICK))
            } else { focus = down(f, s); moved(f, s) }
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

    /** Select / View: the filter checklist, on the box the cursor was on last; again closes it */
    private fun menu(f: Focus, s: Scene): List<Act> =
        if (s.menu) { closeMenu(f); listOf(Act.Menu(false)) }
        else {
            beforeMenu = if (f is Focus.Menu) Focus.Field else f
            focus = Focus.Menu(lastMenu.coerceIn(0, s.menuItems - 1))
            (if (s.view) listOf<Act>(Act.CloseView) else emptyList()) + Act.Menu(true)
        }

    /** the checklist closes: its box is remembered, the cursor goes back where it was */
    private fun closeMenu(f: Focus) {
        if (f is Focus.Menu) lastMenu = f.m
        focus = beforeMenu ?: Focus.Field
    }

    /** box [m]'s column and row in the checklist, or null for All (0) */
    private fun menuAt(m: Int, cols: List<Int>): Pair<Int, Int>? {
        if (m <= 0) return null
        var k = m - 1
        for ((c, n) in cols.withIndex()) { if (k < n) return c to k; k -= n }
        return null
    }

    private fun menuIndex(col: Int, row: Int, cols: List<Int>): Int = 1 + cols.take(col).sum() + row

    /** the checklist's arrows: up / down in a column (up from the top: All), left / right to the next column that has boxes */
    private fun menuMove(m: Int, s: Scene, dx: Int, dy: Int): Int {
        val cols = s.menuCols
        val at = menuAt(m, cols)
        if (at == null) {
            if (dy <= 0) return m                                         // All: only down leaves it
            val c = if (cols.getOrElse(menuCol) { 0 } > 0) menuCol else cols.indexOfFirst { it > 0 }
            return if (c < 0) m else menuIndex(c, 0, cols)
        }
        val (c, r) = at
        if (dy < 0) { if (r == 0) { menuCol = c; return 0 }; return menuIndex(c, r - 1, cols) }
        if (dy > 0) return menuIndex(c, minOf(r + 1, cols[c] - 1), cols)
        var to = c + dx
        while (to in cols.indices && cols[to] == 0) to += dx
        if (to !in cols.indices) return m
        menuCol = to
        return menuIndex(to, minOf(r, cols[to] - 1), cols)
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

    /** X on the log screen: the cursor to the message bar (an open menu or view closes on the way), and its voice page */
    private fun voice(s: Scene): List<Act> {
        val acts = ArrayList<Act>()
        if (s.menu) acts.add(Act.Menu(false))
        if (s.view) acts.add(Act.CloseView)
        focus = Focus.Field
        return acts + Act.Voice
    }

    /** Y: back to the message bar from anywhere; an open menu or view closes on the way */
    private fun bar(s: Scene): List<Act> {
        (focus as? Focus.Menu)?.let { lastMenu = it.m }
        focus = Focus.Field
        val acts = ArrayList<Act>()
        if (s.menu) acts.add(Act.Menu(false))
        if (s.view) acts.add(Act.CloseView)
        if (acts.isEmpty()) acts.add(Act.Glow)
        return acts
    }

    /** the "!" is a stop only on the log screen while the notice shows */
    private fun noticeStop(s: Scene) = s.notice && s.board == Board.NONE && !s.menu

    private fun lineFocus(i: Int, s: Scene): Focus = if (i == s.selected && s.links > 0) Focus.Link(i, 0) else Focus.Line(i)

    private fun up(f: Focus, s: Scene): Focus = when (f) {
        is Focus.Line -> if (f.i == 0 && noticeStop(s)) Focus.Notice else lineFocus(maxOf(0, f.i - 1), s)
        is Focus.Link -> lineFocus(maxOf(0, f.i - 1), s).let { if (f.i == 0) f else it }
        // the top row's right tile goes up to the bar's Send (it stands over it), the others to the field
        is Focus.Quick -> if (f.t >= 3) Focus.Quick(f.t - 3) else if (f.t % 3 == 2) Focus.Send else Focus.Field
        is Focus.Menu -> Focus.Menu(menuMove(f.m, s, 0, -1))
        Focus.Field -> if (s.lines > 0) lineFocus(s.lines - 1, s) else if (noticeStop(s)) Focus.Notice else f
        Focus.Send -> if (s.lines > 0) lineFocus(s.lines - 1, s) else f
        Focus.Notice -> f
    }

    private fun down(f: Focus, s: Scene): Focus = when (f) {
        is Focus.Line, is Focus.Link -> {
            val i = if (f is Focus.Line) f.i else (f as Focus.Link).i
            if (i < s.lines - 1) lineFocus(i + 1, s) else Focus.Field
        }
        is Focus.Quick -> if (f.t + 3 < s.quickTiles) Focus.Quick(f.t + 3) else f
        is Focus.Menu -> Focus.Menu(menuMove(f.m, s, 0, 1))
        Focus.Field -> if (s.board == Board.QUICK) Focus.Quick(lastQuick.coerceIn(0, s.quickTiles - 1)) else f
        // down from Send: back to the phrase picked last (where the cursor came from)
        Focus.Send -> Focus.Quick(lastQuick.coerceIn(0, s.quickTiles - 1))
        Focus.Notice -> if (s.lines > 0) lineFocus(0, s) else Focus.Field
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
            is Focus.Menu -> focus = Focus.Menu(menuMove(f.m, s, step, 0))
            // the bar with quick chat up: the field and its Send, side by side
            Focus.Send -> if (step < 0) focus = Focus.Field
            Focus.Field -> if (s.board == Board.QUICK) { if (step > 0) focus = Focus.Send }
                           else if (!s.menu && s.board == Board.NONE) return listOf(Act.Type(step))
            // THE TYPE ON THE D-PAD TOO (the user: "before opening the message window, I want the message type tabs to be able to be
            // changed by left and right on the Dpad in the chat module"): on the log screen, from the bar or a line; a SELECTED
            // line keeps left / right for its links (above)
            is Focus.Line -> if (s.board == Board.NONE && !s.menu) return listOf(Act.Type(step))
            Focus.Notice -> {}
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
        // a phrase goes INTO THE BAR and the cursor onto its Send; the Keyboard tile opens the keyboard (the cursor in the field)
        is Focus.Quick -> {
            lastQuick = f.t
            if (f.t != s.quickTiles - 1) focus = Focus.Send
            listOf(Act.Quick(f.t))
        }
        // a box ticks or unticks; the list stays open for the next (Select, B or Y closes it)
        is Focus.Menu -> listOf(Act.Filter(f.m))
        Focus.Send -> listOf(Act.Send)
        Focus.Notice -> listOf(Act.NoticeGo)
        // A on the bar: THE INPUT, the one the Settings name (the user, b121: "it should still open with A after opening it"); with
        // quick chat already up, the keyboard (what a phrase put in the bar can be changed there)
        Focus.Field -> when {
            s.board == Board.QUICK -> listOf(Act.Board(Board.KEYS))
            else -> {
                focus = if (s.defaultBoard == Board.QUICK) Focus.Quick(lastQuick.coerceIn(0, s.quickTiles - 1)) else Focus.Field
                listOf(Act.Board(s.defaultBoard))
            }
        }
    }

    /** B: one step back, in the order things cover the module; with nothing open, Home */
    private fun b(f: Focus, s: Scene): List<Act> = when {
        s.view -> listOf(Act.CloseView)                                     // the cursor stays on its link
        s.menu -> { closeMenu(f); listOf(Act.Menu(false)) }
        s.board != Board.NONE -> { focus = Focus.Field; listOf(Act.Board(Board.NONE)) }        // the input goes: the log again
        s.selected != null -> { focus = Focus.Line(s.selected); listOf(Act.Deselect) }
        else -> { focus = null; listOf(Act.Leave) }
    }
}
