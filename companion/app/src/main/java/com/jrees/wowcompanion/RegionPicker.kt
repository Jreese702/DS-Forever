package com.jrees.wowcompanion

/**
 * THE MENU'S REGION CHOICE (the user, 2026-11-17: "when selecting the region in menu, it is closing the menu before I can even confirm
 * and downloading a new build. I accidentally swaped to EU and had to painfully get back to US one click at a time"). The Region button
 * stepped US -> EU -> Korea -> Taiwan and every press SAVED the next region at once (GameRegion.choose): the launcher checked that
 * region's build, a different build updated by itself, and the chooser (with the Menu in it) went away while it downloaded. Back to US
 * from EU was three more presses, each one another region saved and checked.
 *
 * Now: a list of every region with the cursor on the current one; browsing (D-pad, a touch on a row) changes nothing. A different region
 * asks first: "Switch to EU?" with Cancel focused; only Switch gives [Result.Apply]. B in the list closes it with no change, B on the card
 * is Cancel (back to the list). Pure; main thread.
 */
class RegionPicker(private val codes: List<String> = GameRegion.ALL.map { it.code }) {
    enum class Stage { CLOSED, LIST, CONFIRM }

    /** what the confirm card says under its title (the launcher's answer for the target's build, [preview]) */
    enum class Preview { CHECKING, DOWNLOAD, INSTALL, UNKNOWN }

    sealed class Result {
        /** nothing changes (a move, a press with nothing behind it) */
        object None : Result()
        /** the list shut with no change: back to the Menu */
        object Closed : Result()
        /** the confirm card opened for [code]: the host may ask what switching to it would do */
        data class Ask(val code: String) : Result()
        /** Switch pressed: the ONE path that changes the region */
        data class Apply(val from: String, val code: String) : Result()
    }

    var stage = Stage.CLOSED
        private set
    /** the region in use when the list opened */
    var current = codes.first()
        private set
    /** the list's row under the cursor */
    var cursor = 0
        private set
    /** the confirm card's region */
    var target: String? = null
        private set
    /** the card's focus: false = Cancel (the default), true = Switch */
    var switchFocused = false
        private set
    /** the card's second line (set by the host's answer for [target]) */
    var preview = Preview.CHECKING

    val open: Boolean get() = stage != Stage.CLOSED
    val regions: List<String> get() = codes

    fun open(current: String) {
        this.current = current
        cursor = codes.indexOf(current).coerceAtLeast(0)
        target = null; switchFocused = false
        stage = Stage.LIST
    }

    fun close() { stage = Stage.CLOSED; target = null; switchFocused = false }

    /** D-pad: up / down in the list (no wrap), left / right between Cancel and Switch on the card */
    fun move(dx: Int, dy: Int): Result {
        when (stage) {
            Stage.LIST -> if (dy != 0) cursor = (cursor + dy).coerceIn(0, codes.size - 1)
            Stage.CONFIRM -> if (dx != 0) switchFocused = dx > 0
            Stage.CLOSED -> {}
        }
        return Result.None
    }

    /** A: the row under the cursor (the current region = no change), or the card's focused button */
    fun a(): Result = when (stage) {
        Stage.LIST -> pick(cursor)
        Stage.CONFIRM -> if (switchFocused) switch() else cancel()
        Stage.CLOSED -> Result.None
    }

    /** B: the list shuts with no change; on the card it is Cancel */
    fun b(): Result = when (stage) {
        Stage.LIST -> { close(); Result.Closed }
        Stage.CONFIRM -> cancel()
        Stage.CLOSED -> Result.None
    }

    /** a touch on the row [index] (or A on it): the cursor goes there; another region than the current asks first */
    fun pick(index: Int): Result {
        if (stage != Stage.LIST || index !in codes.indices) return Result.None
        cursor = index
        val code = codes[index]
        if (code == current) { close(); return Result.Closed }
        target = code; switchFocused = false; preview = Preview.CHECKING
        stage = Stage.CONFIRM
        return Result.Ask(code)
    }

    /** Cancel: back to the list, the cursor still on the region looked at; nothing saved */
    fun cancel(): Result {
        if (stage != Stage.CONFIRM) return Result.None
        target = null; switchFocused = false
        stage = Stage.LIST
        return Result.None
    }

    /** Switch: the region changes (the host applies it) and the list shuts */
    fun switch(): Result {
        val t = target ?: return Result.None
        if (stage != Stage.CONFIRM) return Result.None
        val from = current
        close()
        return Result.Apply(from, t)
    }
}
