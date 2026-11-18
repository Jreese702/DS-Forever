package com.jrees.wowcompanion.stream

/**
 * THE PAIRING CODE ON THE THOR ("Enter the code shown in DS Forever Desktop"; STREAMING_PLAN.md section 1): the 6-digit secret in six
 * boxes, then (a Desktop from 0.2.37) the PC's address digits ([PairCode], the user's pick 2026-11-18) in a line under them, typed
 * with the touch number pad or the controller (D-pad up / down turns the selected digit, left / right moves, A = OK, B = back).
 * Pure: the pairing screen draws [digits] / [tail] / [cursor] and sends every key here. Positions 0..5 are the boxes; 6.. the tail,
 * whose position [tail].size is the empty slot where the next address digit goes.
 */
class CodeEntry(val length: Int = LENGTH, val max: Int = PairCode.MAX) {
    private val d = IntArray(length) { -1 }
    private val t = ArrayList<Int>()
    /** the selected position: a box (0 until length) or the tail (length .. length + tail.size, the last = the empty slot) */
    var cursor = 0
        private set

    /** each box: 0..9, or -1 = empty */
    val digits: List<Int> get() = d.toList()
    /** the digits after the boxes (the PC's address part) */
    val tail: List<Int> get() = t.toList()
    val complete: Boolean get() = d.all { it >= 0 }
    /** the whole code (the boxes + the tail) once every box has a digit, else null */
    val code: String? get() = if (complete) d.joinToString("") + t.joinToString("") else null

    private val lastPos get() = minOf(length + t.size, max - 1)

    /** a digit from the touch pad or a number key: into the selected place, then the next (the tail grows at its end) */
    fun type(digit: Int) {
        require(digit in 0..9)
        if (cursor < length) { d[cursor] = digit; cursor++; return }
        val i = cursor - length
        if (i < t.size) t[i] = digit else if (length + t.size < max) t.add(digit) else return
        cursor = minOf(cursor + 1, lastPos)
    }

    /** the touch pad's back key: clears the selected box (or, an empty one, the one before it); in the tail it removes the selected
     *  digit, or at the empty slot the one before it */
    fun backspace() {
        if (cursor >= length) {
            val i = cursor - length
            when {
                i < t.size -> t.removeAt(i)
                t.isNotEmpty() -> { t.removeAt(t.size - 1); cursor-- }
                else -> { cursor = length - 1; d[cursor] = -1 }
            }
            return
        }
        if (d[cursor] < 0 && cursor > 0) cursor--
        d[cursor] = -1
    }

    /** the controller: D-pad up / down turns the selected digit (an empty place starts at 0 / 9) */
    fun up() = turn(1)
    fun down() = turn(9)
    private fun turn(by: Int) {
        if (cursor < length) { d[cursor] = if (d[cursor] < 0) (if (by == 1) 0 else 9) else (d[cursor] + by) % 10; return }
        val i = cursor - length
        if (i < t.size) t[i] = (t[i] + by) % 10 else if (length + t.size < max) t.add(if (by == 1) 0 else 9)
    }
    fun left() { if (cursor > 0) cursor-- }
    fun right() { if (cursor < lastPos) cursor++ }
    fun select(pos: Int) { cursor = pos.coerceIn(0, lastPos) }

    fun clear() { d.fill(-1); t.clear(); cursor = 0 }

    /** a whole code at once (a pasted "123 456 · 100 2790"): true = taken; anything [validate] refuses leaves the entry alone */
    fun fill(text: String): Boolean {
        val r = validate(text, length)
        if (r !is Check.Ok) return false
        clear()
        r.code.take(length).forEachIndexed { i, c -> d[i] = c - '0' }
        r.code.drop(length).forEach { t.add(it - '0') }
        cursor = if (t.isEmpty()) length - 1 else lastPos
        return true
    }

    sealed class Check {
        /** [code] = every digit typed (the secret + any address part); [PairCode.parse] splits it */
        data class Ok(val code: String) : Check()
        object Empty : Check()
        object TooShort : Check()
        object TooLong : Check()
        object NotDigits : Check()
        /** the digits after the first 6 aren't an address Desktop shows (a typo: its check digit) */
        object BadAddress : Check()
    }

    companion object {
        const val LENGTH = 6

        /**
         * A code as the player may type it: Desktop shows "123 456 · 100 2790", so spaces, dashes and the dot between groups are
         * allowed and dropped. Anything else (letters, other punctuation, a tail Desktop never makes) is refused with the reason the
         * screen shows. 6 digits alone are an older Desktop's code (or one with no address): valid as before.
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
            Check.BadAddress -> "Check the digits after the first 6."
        }
    }
}
