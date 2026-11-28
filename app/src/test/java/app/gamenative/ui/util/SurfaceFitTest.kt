package app.gamenative.ui.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE X SURFACE AT THE X SCREEN'S SIZE (based on a pull request by RenaeTheRaven, PR 615): the surface's buffers are the game's size and
 * the display hardware scales them to the panel - but NOT while the quick menu's upscaling (FSR, FSR keeping the aspect, DLS) or a
 * scaling mode (nearest, linear, fill, stretch) is picked: those need the panel-sized surface to scale into, and would silently do
 * nothing on a surface the screen's own size (the user's ruling 2026-11-27).
 */
class SurfaceFitTest {
    @Test fun theSurfaceIsFixedOnlyWithNoScalingModePicked() {
        assertTrue(SurfaceFit.fixed(ScreenEffectsConfig.SCALING_MODE_NONE))
        // "Natural" is a colour filter, not a scaling mode: it works on the fixed surface
        assertTrue(SurfaceFit.fixed(ScreenEffectsConfig.SCALING_MODE_NATURAL))
        for (m in listOf(ScreenEffectsConfig.SCALING_MODE_FSR, ScreenEffectsConfig.SCALING_MODE_FSR_ASPECT, ScreenEffectsConfig.SCALING_MODE_DLS,
            ScreenEffectsConfig.SCALING_MODE_NEAREST, ScreenEffectsConfig.SCALING_MODE_LINEAR, ScreenEffectsConfig.SCALING_MODE_FILL,
            ScreenEffectsConfig.SCALING_MODE_STRETCH)) {
            assertFalse("mode $m follows the view", SurfaceFit.fixed(m))
        }
    }

    @Test fun theViewTheRendererAndTheQuickMenuAgree() {
        val view = File("src/main/java/com/winlator/widget/XServerView.java").readText()
        // the view itself stays full size (touch maps through the view), only the buffers change; back to the view's size when unfixed
        assertTrue(view.contains("public void fitSurfaceToScreen(boolean fixed)"))
        assertTrue(view.contains("getHolder().setFixedSize(w, h);"))
        assertTrue(view.contains("getHolder().setSizeFromLayout();"))
        // the quick menu's every apply (and the launch's first one) decides it, so a pick there takes effect at once
        val cfg = File("src/main/java/app/gamenative/ui/util/ScreenEffectsConfig.kt").readText()
        val vk = cfg.substringAfter("fun applyScreenEffectsConfig(renderer: VulkanRenderer, config: ScreenEffectsConfig) {")
        assertTrue(vk.contains("renderer.xServerView?.fitSurfaceToScreen(SurfaceFit.fixed(config.scalingMode))"))
        // before the surface exists: the container's saved pick, so the first surface is already the right size
        val xs = File("src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt").readText()
        assertTrue(xs.contains("fitSurfaceToScreen(app.gamenative.ui.util.SurfaceFit.fixed(loadScreenEffectsConfig(container).scalingMode))"))
    }
}
