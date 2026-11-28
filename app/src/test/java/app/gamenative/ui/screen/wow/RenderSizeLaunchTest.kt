package app.gamenative.ui.screen.wow

import com.jrees.wowcompanion.RenderSize
import com.winlator.renderer.ViewTransformation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RENDER SIZE AT THE LAUNCH (the user's rulings 2026-11-27 on PR 615, based on a pull request by RenaeTheRaven): the container's
 * screen size (the X server's and Wine's desktop) is the setting's at every Play, fitted to the panel's shape as every profile was;
 * the game's Config.wtf gets exactly the keys it got before (gxMaximize 1, gxWindowedResolution = the panel's size); the touch on the
 * top screen maps through the view onto the smaller X screen.
 */
class RenderSizeLaunchTest {
    private val launcher by lazy { File("src/main/java/app/gamenative/ui/screen/wow/WoWForeverScreen.kt").readText() }

    @Test fun theContainerScreenIsTheSettingsSizeFittedToThePanel() {
        // a Thor (16:9): the setting's own size
        assertEquals("1280x720", PanelFit.renderScreen(720, "1920x1080"))
        assertEquals("1600x900", PanelFit.renderScreen(900, "1920x1080"))
        assertEquals("1920x1080", PanelFit.renderScreen(1080, "1920x1080"))
        assertEquals("1280x720", PanelFit.renderScreen(RenderSize.DEFAULT, "1920x1080"))
        // another shape (a Retroid Pocket Nova, 4:3): the panel's own size wins, as before (TapBridge refuses another shape)
        assertEquals("1280x960", PanelFit.renderScreen(720, "1280x960"))
        // the panel unread: the setting's size
        assertEquals("1280x720", PanelFit.renderScreen(720, null))
    }

    @Test fun everyPlayReadsTheSettingAndNothingNewGoesIntoTheGamesConfig() {
        val prep = launcher.substringAfter("private fun prepareLaunch(").substringBefore("\n}\n")
        assertTrue(prep.contains("val profile = withRenderSize(context, detected)"))
        val with = launcher.substringAfter("private fun withRenderSize(").substringBefore("\n}\n")
        assertTrue(with.contains("com.jrees.wowcompanion.Settings.renderSize"))
        assertTrue(with.contains("PanelFit.renderScreen(lines, panel)"))
        assertTrue(with.contains("p.copy(screenSize = screen)"))
        // the container's screen size is the profile's (the render size), as it always was
        assertTrue(launcher.contains("put(\"screenSize\", gpu.screenSize)"))
        // Config.wtf: the same call as before - the window size is the PANEL's (gameScreenSize: the display's physical mode), never the
        // render size; gxMaximize fits the game's window to the (smaller) desktop
        assertTrue(prep.contains("val screen = gameScreenSize(context)"))
        assertTrue(prep.contains("askAccount = ask, screen = screen,"))
        assertFalse(prep.contains("RenderSize.size("))
        val wtf = File("../companion/app/src/main/java/com/jrees/wowcompanion/GameConfigWtf.kt").readText()
        assertFalse("no game key for the render size", wtf.contains("RenderSize") || wtf.contains("gxFullscreenResolution"))
    }

    @Test fun aTouchOnTheTopScreenLandsOnTheSameSpotOfTheSmallerScreen() {
        // TouchMouse / TouchpadView: the view (the panel, 1920x1080) over the X screen; the fixed surface changes nothing here
        for (lines in RenderSize.CHOICES) {
            val (w, h) = RenderSize.size(lines).split("x").map { it.toInt() }
            val t = ViewTransformation().apply { update(1920, 1080, w, h) }
            assertEquals(1920f / w, t.aspect, 1e-4f)
            assertEquals(0, t.viewOffsetX); assertEquals(0, t.viewOffsetY)
            for ((vx, vy) in listOf(0f to 0f, 960f to 540f, 1919f to 1079f, 333f to 777f)) {
                val x = vx / t.aspect; val y = vy / t.aspect
                assertEquals(vx * w / 1920f, x, 1e-2f); assertEquals(vy * h / 1080f, y, 1e-2f)
                assertTrue(x < w && y < h)
            }
        }
    }
}
