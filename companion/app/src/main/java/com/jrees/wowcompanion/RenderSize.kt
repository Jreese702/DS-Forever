package com.jrees.wowcompanion

/**
 * RENDER SIZE (test build 1529; the user's pick 2026-11-24, "Yes, add the setting (Recommended)" = "Default stays full size; players on a
 * base Thor can pick 900p or 720p for more FPS"). Settings > Performance > Frame limit's band: "Render size: Full size / 900p / 720p", per
 * device (Settings.renderSize, this Thor's own settings), from the next Play.
 *
 * HOW (the way jaredgei/wow-forever-android's RenderResolution does it, read 2026-11-24 in its public WoWForeverScreen.kt: the 16:9 size
 * goes to the CONTAINER's screenSize and to Config.wtf's resolution keys): the game draws into the container's X screen, and the launcher
 * draws that screen scaled up to fill the top screen (Winlator's ViewTransformation; the touch transform scales back the same way). So
 * the size the game renders at is the container's screen: the launch puts the chosen size there, and gxWindowedResolution (forced at
 * every start since #305, gxMaximize 1) to the same size so the game's maximized window and its own idea of its size agree. A smaller
 * gxWindowedResolution alone would change nothing (the maximized window fills the X screen), and the X screen alone would leave the game
 * asking for a window bigger than its screen. gxFullscreenResolution is not written (our launch is windowed-maximized; a player's own
 * fullscreen choice is the game's). RenderScale is never written (#489: the player's own value stays).
 *
 * WHAT IT DOES NOT CHANGE: the game's UI layout (its UI is sized in 768-line units, not pixels), the bottom screen, the tap pad and the
 * cursor (TapBridge maps the game's own pixel size, the add-on's pw / ph, onto the top screen by its shape: a 16:9 size on the 16:9 top
 * screen maps 1:1.5 at 720p), the Thor Lite / other-device profiles (their full size is already 720p: nothing smaller is offered), Swap
 * screens (the game on the bottom panel: always Full size).
 *
 * Pure (the tests): [choices], [effective].
 */
object RenderSize {
    /** a choice: [size] null = Full size (the profile's own container size, exactly as before) */
    data class Choice(val label: String, val size: String?)

    val FULL = Choice("Full size", null)
    /** the smaller 16:9 sizes (offered when smaller than the full size) */
    val SIZES = listOf(Choice("1080p", "1920x1080"), Choice("900p", "1600x900"), Choice("720p", "1280x720"))

    /** "1920x1080" -> (1920, 1080); null when not WxH */
    fun parse(s: String?): Pair<Int, Int>? {
        val m = Regex("^(\\d{2,5})x(\\d{2,5})$").find(s?.trim() ?: return null) ?: return null
        val w = m.groupValues[1].toInt(); val h = m.groupValues[2].toInt()
        return if (w > 0 && h > 0) w to h else null
    }

    private fun sixteenNine(w: Int, h: Int) = kotlin.math.abs(w.toDouble() / h - 16.0 / 9.0) < 0.01

    /** the choices for a [full] container size: Full size first, then each 16:9 size smaller than it; only Full for a non-16:9 one */
    fun choices(full: String?): List<Choice> {
        val (w, h) = parse(full) ?: return listOf(FULL)
        if (!sixteenNine(w, h)) return listOf(FULL)
        return listOf(FULL) + SIZES.filter { c -> parse(c.size)!!.let { it.second < h && it.first < w } }
    }

    /** the container size a launch uses: [pick] when it is one of [full]'s choices and the game is on the top screen, else [full] */
    fun effective(full: String, pick: String?, swapped: Boolean): String {
        if (swapped || pick.isNullOrEmpty()) return full
        return choices(full).firstOrNull { it.size == pick }?.size ?: full
    }

    /** the band's index of [pick] among [full]'s choices (Full size when not one of them) */
    fun index(full: String?, pick: String?): Int = choices(full).indexOfFirst { it.size == pick?.takeIf { p -> p.isNotEmpty() } }.coerceAtLeast(0)

    /** the launch's log line: "render size 1280x720 (full 1920x1080)" / "render size full (1920x1080)" */
    fun line(full: String, used: String): String = if (used == full) "render size full ($full)" else "render size $used (full $full)"
}
