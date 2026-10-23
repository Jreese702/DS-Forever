package com.jrees.wowcompanion.stream

/**
 * THE PAIRING CODE ON THE THOR ("Enter the code shown in DS Forever Desktop"; STREAMING_PLAN.md section 1): 6 digits, typed with the
 * touch number pad or the controller (D-pad up / down turns the selected digit, left / right moves between digits, A = OK, B = back).
 * Pure: the pairing screen draws [digits] / [cursor] and sends every key here.
 */
class CodeEntry(val length: Int = LENGTH) {
    private val d = IntArray(length) { -1 }
    /** the selected box (0 until length) */
    var cursor = 0
        private set

    /** each box: 0..9, or -1 = empty */
    val digits: List<Int> get() = d.toList()
    val complete: Boolean get() = d.all { it >= 0 }
    /** the code once every box has a digit, else null */
    val code: String? get() = if (complete) d.joinToString("") else null

    /** a digit from the touch pad or a number key: into the selected box, then the next box */
    fun type(digit: Int) {
        require(digit in 0..9)
        d[cursor] = digit
        if (cursor < length - 1) cursor++
    }

    /** the touch pad's back key: clears the selected box, or (an empty box) the one before it */
    fun backspace() {
        if (d[cursor] < 0 && cursor > 0) cursor--
        d[cursor] = -1
    }

    /** the controller: D-pad up / down turns the selected digit (an empty box starts at 0 / 9) */
    fun up() { d[cursor] = if (d[cursor] < 0) 0 else (d[cursor] + 1) % 10 }
    fun down() { d[cursor] = if (d[cursor] < 0) 9 else (d[cursor] + 9) % 10 }
    fun left() { if (cursor > 0) cursor-- }
    fun right() { if (cursor < length - 1) cursor++ }
    fun select(box: Int) { cursor = box.coerceIn(0, length - 1) }

    fun clear() { d.fill(-1); cursor = 0 }

    /** a whole code at once (a pasted "123 456"): true = taken; anything that is not exactly [length] digits leaves the boxes alone */
    fun fill(text: String): Boolean {
        val r = validate(text, length)
        if (r !is Check.Ok) return false
        r.code.forEachIndexed { i, c -> d[i] = c - '0' }
        cursor = length - 1
        return true
    }

    sealed class Check {
        data class Ok(val code: String) : Check()
        object Empty : Check()
        object TooShort : Check()
        object TooLong : Check()
        object NotDigits : Check()
    }

    companion object {
        const val LENGTH = 6

        /**
         * A code as the player may type it: Desktop shows "123 456", so spaces and dashes between the digits are allowed and dropped.
         * Anything else (letters, other punctuation, a seventh digit) is refused with the reason the screen shows.
         */
        fun validate(text: String, length: Int = LENGTH): Check {
            val t = text.trim().filter { it != ' ' && it != '-' && it != ' ' }
            return when {
                t.isEmpty() -> Check.Empty
                t.any { it !in '0'..'9' } -> Check.NotDigits
                t.length < length -> Check.TooShort
                t.length > length -> Check.TooLong
                else -> Check.Ok(t)
            }
        }

        /** what the screen says for a refused code */
        fun message(c: Check): String? = when (c) {
            is Check.Ok -> null
            Check.Empty -> "Enter the 6-digit code shown in DS Forever Desktop."
            Check.TooShort -> "The code has 6 digits."
            Check.TooLong -> "The code has only 6 digits."
            Check.NotDigits -> "The code is digits only."
        }
    }
}
