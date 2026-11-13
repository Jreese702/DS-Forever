package com.jrees.wowcompanion.stream

/**
 * Issue #54 (1.0.17: "on pc stream I cant use my ayn thor to play, input doesnt work"): the report's log said the input stream started and
 * nothing more, so "the Thor sent nothing" and "the PC could not make the pad" looked the same. The log now has the PC's controller driver
 * (Desktop's welcome "pad_driver": Sunshine makes the Xbox pad with ViGEmBus, which Desktop's installer does not install) and whether any
 * controller input went out. Pure: the tests drive it.
 */
object PadNote {
    fun driver(word: String): String = when (word) {
        "vigem" -> "installed"
        "missing" -> "MISSING on the PC (the controller can't reach WoW until ViGEmBus is installed)"
        "unknown" -> "unknown (the PC could not read it)"
        else -> "not reported (an older Desktop)"
    }

    fun sent(n: Int, driverWord: String): String = when {
        n < 0 -> "controller: not sent (the stream library was not loaded)"
        n == 0 -> "controller: nothing sent"
        else -> "controller: $n inputs sent" + if (driverWord == "missing") ", but the PC has no ViGEmBus" else ""
    }
}
