package com.jrees.wowcompanion.stream

/**
 * THE PAIRING CODE ON THE THOR ("Enter the code shown in DS Forever Desktop"; STREAMING_PLAN.md section 1), design B "Two steps" (the
 * user's picks 2026-11-18): the 6-digit secret in six boxes, exactly as before; under them, once opened, the "Other network" row of
 * smaller boxes for Desktop's network code ([PairCode]). The row opens with the "Other network" button beside the pad (touch, or the
 * controller: D-pad DOWN past the boxes selects it, A presses it), or by itself when Pair finds no PC ([openOther]).
 * The controller: D-pad up turns the selected digit (0..9, wrapping; an empty box starts at 0), down goes below the boxes (the button,
 * or the open row), left / right move (right past the last box reaches the open row, left from its first box comes back), up on the
 * button comes back to the boxes. Pure: the pairing screen draws [digits] / [other] / [cursor] and sends every key here.
 * Positions: 0..5 the boxes, [OTHER_BUTTON] the button, [length] + i the row's box i.
 */
class CodeEntry(val length: Int = LENGTH, val max: Int = PairCode.NET_MAX) {
    private val d = IntArray(length) { -1 }
    private val t = ArrayList<Int>()
    var cursor = 0
        private set
    /** the "Other network" row is showing */
    var otherOpen = false
        private set

    /** each box: 0..9, or -1 = empty */
    val digits: List<Int> get() = d.toList()
    /** the network code's digits typed so far (the row's boxes, left to right) */
    val other: List<Int> get() = t.toList()
    val complete: Boolean get() = d.all { it >= 0 }
    /** the whole code (the secret + any network digits) once every box has a digit, else null */
    val code: String? get() = if (complete) d.joinToString("") + t.joinToString("") else null
    /** the row's box count ([PairCode.boxes]); 0 while it is closed */
    val otherBoxes: Int get() = if (otherOpen) PairCode.boxes(t.joinToString("")) else 0
    val onButton: Boolean get() = cursor == OTHER_BUTTON
    /** the row's selected box, or -1 */
    val otherCursor: Int get() = if (cursor >= length && cursor != OTHER_BUTTON) cursor - length else -1

    /** the row's last place the cursor may stand: the box after the typed digits (the next to type), within the row */
    private val otherLast get() = minOf(t.size, maxOf(0, otherBoxes - 1), max - 1)

    /** a digit from the touch pad or a number key: into the selected place, then the next */
    fun type(digit: Int) {
        require(digit in 0..9)
        when {
            onButton -> return
            cursor < length -> {
                d[cursor] = digit
                if (cursor < length - 1) cursor++ else if (otherOpen && t.isEmpty()) cursor = length
            }
            else -> {
                val i = cursor - length
                if (i < t.size) t[i] = digit else if (t.size < max && t.size < otherBoxes) t.add(digit) else return
                cursor = length + minOf(i + 1, otherLast)
            }
        }
    }

    /** the touch pad's Delete: clears the selected box (or, an empty one, the one before it); in the row it removes the selected digit
     *  or, at the empty place, the one before it; in an empty row it closes the row */
    fun backspace() {
        if (onButton) return
        if (cursor >= length) {
            val i = cursor - length
            when {
                i < t.size -> { t.removeAt(i); cursor = length + minOf(i, otherLast) }
                t.isNotEmpty() -> { t.removeAt(t.size - 1); cursor = length + otherLast }
                else -> { otherOpen = false; cursor = length - 1 }
            }
            return
        }
        if (d[cursor] < 0 && cursor > 0) cursor--
        d[cursor] = -1
    }

    /** D-pad up: turns the selected digit (an empty place starts at 0); on the button, back to the boxes */
    fun up() {
        when {
            onButton -> cursor = firstEmptyBox()
            cursor < length -> d[cursor] = if (d[cursor] < 0) 0 else (d[cursor] + 1) % 10
            else -> {
                val i = cursor - length
                if (i < t.size) t[i] = (t[i] + 1) % 10 else if (t.size < max && t.size < otherBoxes) t.add(0)
            }
        }
    }

    /** D-pad down: from the boxes, below them (the "Other network" button, or the open row) */
    fun down() {
        if (cursor < length) cursor = if (otherOpen) length + otherLast else OTHER_BUTTON
    }

    fun left() {
        when {
            onButton -> {}
            cursor == length -> cursor = length - 1
            cursor > 0 -> cursor--
        }
    }

    fun right() {
        when {
            onButton -> {}
            cursor < length - 1 -> cursor++
            cursor == length - 1 -> if (otherOpen) cursor = length
            else -> cursor = length + minOf(cursor - length + 1, otherLast)
        }
    }

    /** A on the button (or a touch on it): opens the row, the cursor in it */
    fun openOther() {
        otherOpen = true
        cursor = length + otherLast
    }

    /** a touch on a box: [pos] = 0..5, or [length] + i for the row's box i */
    fun select(pos: Int) {
        cursor = when {
            pos == OTHER_BUTTON -> OTHER_BUTTON
            pos < length -> pos.coerceAtLeast(0)
            otherOpen -> length + minOf(pos - length, otherLast)
            else -> cursor
        }
    }

    /** a new start; [keepOther] keeps the row and its digits (a wrong secret: the PC's address is still right) */
    fun clear(keepOther: Boolean = false) {
        d.fill(-1); cursor = 0
        if (!keepOther) { t.clear(); otherOpen = false }
    }

    private fun firstEmptyBox() = d.indexOfFirst { it < 0 }.let { if (it < 0) length - 1 else it }

    /** a whole code at once (a pasted "123 456" or "123 456 · 100 279"): true = taken; anything [validate] refuses leaves it alone */
    fun fill(text: String): Boolean {
        val r = validate(text, length)
        if (r !is Check.Ok) return false
        clear()
        r.code.take(length).forEachIndexed { i, c -> d[i] = c - '0' }
        r.code.drop(length).forEach { t.add(it - '0') }
        otherOpen = t.isNotEmpty()
        cursor = if (otherOpen) length + otherLast else length - 1
        return true
    }

    sealed class Check {
        /** [code] = every digit typed (the secret + any network digits); [PairCode.parse] splits it */
        data class Ok(val code: String) : Check()
        object Empty : Check()
        object TooShort : Check()
        object TooLong : Check()
        object NotDigits : Check()
        /** the Other network digits aren't a code Desktop shows (a typo, or not all typed) */
        object BadAddress : Check()
    }

    companion object {
        const val LENGTH = 6
        /** the cursor on the "Other network" button */
        const val OTHER_BUTTON = 1000

        /**
         * A code as the player may type or paste it: Desktop shows "123 456" and, under Other network, "100 279", so spaces, dashes and
         * the dot between the two are allowed and dropped. Anything else (letters, other punctuation, network digits Desktop never makes)
         * is refused with the reason the screen shows. 6 digits alone are the code as before.
         */
        fun validate(text: String, length: Int = LENGTH): Check {
            val t = text.trim().filter { it != ' ' && it != '-' && it != ' ' && it != '·' }
            return when {
                t.isEmpty() -> Check.Empty
                t.any { it !in '0'..'9' } -> Check.NotDigits
                t.length < length -> Check.TooShort
                t.length > PairCode.MAX -> Check.TooLong
                length == PairCode.SECRET && PairCode.parse(t) == null -> Check.BadAddress
                length != PairCode.SECRET && t.length > length -> Check.TooLong
                else -> Check.Ok(t)
            }
        }

        /** what the screen says for a refused code */
        fun message(c: Check): String? = when (c) {
            is Check.Ok -> null
            Check.Empty -> "Enter the code shown in DS Forever Desktop."
            Check.TooShort -> "The code starts with 6 digits."
            Check.TooLong -> "That code is too long."
            Check.NotDigits -> "The code is digits only."
            Check.BadAddress -> BAD_OTHER
        }

        /** the Other network digits are wrong (es / fr: Tr) */
        const val BAD_OTHER = "Check the Other network code."
    }
}
