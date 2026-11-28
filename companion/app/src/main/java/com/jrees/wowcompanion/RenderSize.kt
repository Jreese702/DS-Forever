package com.jrees.wowcompanion

/**
 * RENDER SIZE (Settings > Performance, the Frame limit band; the user's rulings 2026-11-27 on PR 615, based on a pull request by
 * RenaeTheRaven, who measured the Adreno 740 GPU-bound at 1080p): the size the game draws at - 1080p, 900p or 720p - from the next
 * game start. The DEFAULT is 720p on every device (the user, 2026-11-27: "defaults to 720p on EVERY device"); a player's own pick
 * (900p / 1080p, or 720p) is kept and always wins. A player who never picked has no stored value, so an update gives them 720p.
 *
 * How it applies (the PR's mechanism, driven by this setting instead of a fixed size per GPU): the launcher's own CONTAINER screen size
 * (the X server's and Wine's desktop) is this size, and the X surface is fixed at it (XServerView.fitSurfaceToScreen), so the display
 * hardware scales the picture to the panel. The game's own files are NOT written for it: Config.wtf keeps exactly the keys the launcher
 * already writes at every start (gxMaximize 1, gxWindowedResolution = the panel's size), and gxMaximize fits the game's window to the
 * desktop. A panel of another shape still takes the panel's own size (PanelFit). Pure (no android.*): RenderSizeTest.
 */
object RenderSize {
    /** the stored pick (Settings' prefs, this app only); absent = never picked = [DEFAULT] */
    const val KEY = "renderSize"
    /** Settings' choices, lines (the band's order) */
    val CHOICES = listOf(1080, 900, 720)
    /** the default on every device */
    const val DEFAULT = 720

    /** the choice's word ("1080p") */
    fun label(lines: Int): String = "${lines}p"

    /** the 16:9 size for [lines] ("1280x720") */
    fun size(lines: Int): String = when (lines) {
        1080 -> "1920x1080"
        900 -> "1600x900"
        else -> "1280x720"
    }

    /** the size this start uses: the player's own pick when it is a choice, else [DEFAULT] (no pick, or a value no build offers) */
    fun chosen(stored: Int?): Int = stored?.takeIf { it in CHOICES } ?: DEFAULT

    /** the lines a container screen size names ("1280x720" -> 720), null when it is not a size */
    fun linesOf(size: String?): Int? {
        val p = size?.lowercase()?.split("x") ?: return null
        if (p.size != 2) return null
        val a = p[0].trim().toIntOrNull() ?: return null
        val b = p[1].trim().toIntOrNull() ?: return null
        return if (a <= 0 || b <= 0) null else minOf(a, b)
    }

    /** the render size the running game started with (the launcher sets it at each start), null = no game started in this run */
    @Volatile var launched: Int? = null

    /** a restart would change the running game's render size */
    fun needsRestart(launched: Int?, now: Int): Boolean = launched != null && launched != now
}
