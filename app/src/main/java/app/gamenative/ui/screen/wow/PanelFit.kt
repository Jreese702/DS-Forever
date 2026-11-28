package app.gamenative.ui.screen.wow

import java.util.Locale
import kotlin.math.abs

/**
 * PR #256 (KillgoreAlpha): the container's screen size against the panel's shape, pulled out of GpuProfile.detect so a JVM test
 * covers it (the PR review). Pure.
 */
internal object PanelFit {
    /** TapBridge.gamePoint's own tolerance: a container within it of the panel's shape maps taps 1:1 */
    const val TOLERANCE = 0.02f

    /** "1280x720" -> 1.78, landscape whichever way round it is written; null when it is not a size */
    fun ratio(size: String?): Float? {
        val parts = size?.lowercase(Locale.ROOT)?.split("x") ?: return null
        if (parts.size != 2) return null
        val a = parts[0].trim().toFloatOrNull() ?: return null
        val b = parts[1].trim().toFloatOrNull() ?: return null
        if (a <= 0f || b <= 0f) return null
        return maxOf(a, b) / minOf(a, b)
    }

    /** [size] if it is the [panel]'s shape (or either is not a size), else the panel's own size */
    fun fit(size: String, panel: String?): String {
        val want = ratio(size) ?: return size
        val have = ratio(panel) ?: return size
        return if (abs(want - have) <= TOLERANCE) size else panel!!
    }

    /**
     * Render size (Settings > Performance, based on a pull request by RenaeTheRaven): the container screen for [lines] (1080 / 900 /
     * 720, com.jrees.wowcompanion.RenderSize), fitted to the [panel]'s shape as every profile's size is ([fit])
     */
    fun renderScreen(lines: Int, panel: String?): String = fit(com.jrees.wowcompanion.RenderSize.size(lines), panel)
}
