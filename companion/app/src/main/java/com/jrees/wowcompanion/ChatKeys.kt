package com.jrees.wowcompanion

/**
 * THE CHAT ROOM'S OWN CONTROLLER KEYBOARD: the rules (the user's pick 2026-09-30, "The companion's own
 * controller keyboard"). Android's keyboard cannot be raised by a controller press: it opens only for the
 * screen that holds the input focus, and that is the game's (measured on the Thor, AGENT_LOG `1850679`).
 * So the room draws its own and types into its own field; nothing reaches the game until Send.
 *
 * Pure Kotlin, tested off the device (tools/chat_test). The LOOK is UI Art's (mock-ups for the user
 * first); the [Layout] here is the default the tests run on, and the picked design passes its own.
 *
 * Keys (the user's pick with the Chat rework, 2026-10-07: "LT = Backspace, RT = Shift, ?123 by its key"; and "the send button
 * on the keyboard to be able to send with the Start button"): D-pad moves over the keys, A presses the key under the cursor,
 * B closes (the text is kept), Y = space, LT = backspace, RT = shift, Start = Send; the other page (letters / signs) only by its
 * ?123 key. X, LB and RB are NOT the keyboard's ([Act.Pass]): X swaps to quick chat, LB / RB change the chat type. Typed chat
 * carries printable ASCII only (32..126) and the game takes 255 characters in one message.
 */
class ChatKeys(private val layout: Layout = Layout.DEFAULT) {

    sealed class Key {
        data class Ch(val c: Char) : Key()
        object Shift : Key() { override fun toString() = "Shift" }
        object Page : Key() { override fun toString() = "Page" }
        object Space : Key() { override fun toString() = "Space" }
        object Back : Key() { override fun toString() = "Back" }
        object Send : Key() { override fun toString() = "Send" }
        /** "(X) Quick chat" (UI Art's SHEET_chat_input: the way back, a key on each board) */
        object Swap : Key() { override fun toString() = "Swap" }
    }

    /** a key and its width in key units (a letter is 1) */
    data class Cell(val key: Key, val w: Float = 1f)

    /** a row of keys; [lead] = how far in it starts, in key units (the A-L row: half a key, as on a real board) */
    data class Row(val cells: List<Cell>, val lead: Float = 0f) {
        val size get() = cells.size
        operator fun get(i: Int) = cells[i]
        val indices get() = cells.indices
    }

    /** pages of rows; page 0 is shown first */
    class Layout(val pages: List<List<Row>>) {
        companion object {
            private fun chars(s: String) = s.map { Cell(Key.Ch(it)) }
            private fun row(cells: List<Cell>, lead: Float = 0f) = Row(cells, lead)
            // THE USER'S APPROVED PICTURE (wow-companion-design/later/SHEET_keyboard_v2.png, 2026-09-30): the digits always on
            // top, QWERTY with the A-L row half a key in, Shift and Backspace round z..m, the bottom row ?123 , ' Space . Send;
            // the signs page holds the other 29 printable signs (, . are on the letters page; "|" is not typed, chat13)
            val DEFAULT = build(swap = true)
            /** the same keys without "(X) Quick chat" (Space takes its width): a box that only wants a line typed (Bags' search) */
            val NO_SWAP = build(swap = false)

            private fun build(swap: Boolean): Layout {
                fun bottom(mid: List<Cell>, space: Float) =
                    row(listOf(Cell(Key.Page, 1.5f)) + (if (swap) listOf(Cell(Key.Swap, 2f)) else emptyList()) + mid.dropLast(1)
                        + Cell(Key.Space, if (swap) space else space + 2f) + mid.last() + Cell(Key.Send, 1.5f))
                return Layout(listOf(
                    listOf(                                                     // letters
                        row(chars("1234567890")),
                        row(chars("qwertyuiop")),
                        row(chars("asdfghjkl"), 0.5f),
                        row(listOf(Cell(Key.Shift, 1.5f)) + chars("zxcvbnm") + listOf(Cell(Key.Back, 1.5f))),
                        // the input screen (UI Art, SHEET_chat_input): "(X) Quick chat" between ?123 and Space; , and . stay (chat uses
                        // them all the time), ' went to the signs page
                        bottom(listOf(Cell(Key.Ch(',')), Cell(Key.Ch('.'))), 3f),
                    ),
                    listOf(                                                     // signs
                        row(chars("1234567890")),
                        row(chars("!@#$%^&*()")),
                        row(chars("-_=+[]{}\\'")),
                        // no "|" (chat13: its add-on cell shows the top screen's chat now): the row half a key in, as the A-L row
                        row(listOf(Cell(Key.Ch('`'), 1.5f)) + chars("~\"/:;<") + listOf(Cell(Key.Back, 1.5f)), 0.5f),
                        bottom(listOf(Cell(Key.Ch('?')), Cell(Key.Ch('>')), Cell(Key.Ch('.'))), 2f),
                    ),
                ))
            }
        }
    }

    enum class Shift { OFF, ONCE, LOCK }

    /** What the room is to do after a press. */
    sealed class Act {
        object Glow : Act() { override fun toString() = "Glow" }          // the cursor moved / a state changed: redraw
        data class Text(val text: String) : Act()                          // the field's new text
        object Send : Act() { override fun toString() = "Send" }
        object Close : Act() { override fun toString() = "Close" }
        object Refused : Act() { override fun toString() = "Refused" }    // full, or nothing to delete
        object Pass : Act() { override fun toString() = "Pass" }          // not the keyboard's button: the room's (X, LB, RB)
        object Swap : Act() { override fun toString() = "Swap" }          // the Quick chat key: the room turns to quick chat
    }

    var page = 0; private set
    var row = 0; private set
    var col = 0; private set
    var shift = Shift.OFF; private set

    private val rows get() = layout.pages[page]

    /** the rows of the page shown now */
    fun layoutRows(): List<Row> = rows

    /** the key under the cursor */
    val key: Key get() = rows[row][col].key

    private var opened = false

    /**
     * Opening: the cursor on the key it was last on (the SPEC); the first time on "a", the first key of the
     * A-L row. Shift is off again.
     */
    fun open() {
        shift = Shift.OFF
        if (opened) return
        opened = true
        page = 0
        val r = rows.indexOfFirst { row -> row.cells.firstOrNull()?.key == Key.Ch('a') }
        row = if (r >= 0) r else 0
        col = 0
    }

    /** the character a letter key types now (shift applied) */
    fun charOf(k: Key.Ch): Char = if (shift != Shift.OFF && k.c.isLetter()) k.c.uppercaseChar() else k.c

    fun press(button: String, text: String): Act = when (button) {
        "PADDUP" -> { moveRow(-1); Act.Glow }
        "PADDDOWN" -> { moveRow(1); Act.Glow }
        "PADDLEFT" -> { col = (col - 1 + rows[row].size) % rows[row].size; Act.Glow }     // rows wrap (the SPEC)
        "PADDRIGHT" -> { col = (col + 1) % rows[row].size; Act.Glow }
        "PAD1" -> pressKey(key, text)
        "PAD2" -> Act.Close
        "PAD4" -> pressKey(Key.Space, text)
        "PADLTRIGGER" -> pressKey(Key.Back, text)
        "PADRTRIGGER" -> pressKey(Key.Shift, text)
        "PADFORWARD" -> pressKey(Key.Send, text)
        "PAD3", "PADLSHOULDER", "PADRSHOULDER" -> Act.Pass
        else -> Act.Glow
    }

    /** a key pressed by A or by a finger */
    fun pressKey(k: Key, text: String): Act = when (k) {
        is Key.Ch -> type(charOf(k), text).also { if (it is Act.Text && shift == Shift.ONCE) shift = Shift.OFF }
        Key.Space -> type(' ', text)
        Key.Back -> if (text.isEmpty()) Act.Refused else Act.Text(text.dropLast(1))
        Key.Send -> if (text.isBlank()) Act.Refused else Act.Send
        Key.Shift -> { shift = when (shift) { Shift.OFF -> Shift.ONCE; Shift.ONCE -> Shift.LOCK; Shift.LOCK -> Shift.OFF }; Act.Glow }
        Key.Page -> { val x = centreOf(row, col); page = (page + 1) % layout.pages.size; row = row.coerceAtMost(rows.size - 1); col = nearest(row, x); Act.Glow }
        Key.Swap -> Act.Swap
    }

    /** a finger on a key: the cursor goes there too, then the key is pressed */
    fun touch(r: Int, c: Int, text: String): Act {
        if (r !in rows.indices || c !in rows[r].indices) return Act.Glow
        row = r; col = c
        return pressKey(key, text)
    }

    private fun type(ch: Char, text: String): Act =
        if (ch.code !in 32..126 || ch == '|' || text.length >= MAX) Act.Refused else Act.Text(text + ch)

    // up / down keep the cursor over the same place on the row, by the keys' centres
    private fun moveRow(step: Int) {
        val to = (row + step).coerceIn(0, rows.size - 1)
        if (to == row) return
        val x = centreOf(row, col)
        row = to
        col = nearest(row, x)
    }

    private fun centreOf(r: Int, c: Int): Float {
        val row = rows[r]
        val total = row.cells.sumOf { it.w.toDouble() }.toFloat() + row.lead * 2f
        var x = row.lead
        for (i in 0 until c) x += row[i].w
        return (x + row[c].w / 2f) / total
    }

    private fun nearest(r: Int, x: Float): Int =
        // the nearest in x; on a tie (the offset row lies between two keys) the LEFT one
        rows[r].indices.minByOrNull { Math.abs(centreOf(r, it) - x) * 1000f + it * 0.001f } ?: 0

    companion object {
        /** the game's own limit on one chat message */
        const val MAX = 255
    }
}
