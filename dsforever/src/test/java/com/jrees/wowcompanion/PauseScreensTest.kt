package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE PAUSE WHEEL'S ONE SCREEN / TWO SCREENS BUTTON (the user, 2026-11-25: "The button to shift it needs to be in the pause menu below
 * the edit mode button"; the pick "Icon on Y": a red plate like power / Edit, on Y, below Edit, its picture flipping One screen / Two
 * screens): its box under Edit on both canvases, Y (or a tap there) only while the button shows, a refusal = the wheel's red line with
 * the wheel open, and single screen mode's hub = Home's glance (the pick "Straight to the game": Home's page opens from the wheel).
 */
class PauseScreensTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val view = File(src, "HomePauseView.kt").readText()
    private val kit = File(src, "UiKit.kt").readText()
    private val host = File(src, "CompanionHost.kt").readText()

    @After fun clean() = SingleScreen.reset()

    @Test fun belowEditOnTheBottomScreen() {
        assertArrayEquals(floatArrayOf(1107f, 149f, 1224f, 266f), HomePauseModel.screensBox(0f, 0f, 1240f, 1080f), 0.01f)
        assertArrayEquals("Edit's own box above it", floatArrayOf(1107f, 16f, 1224f, 133f), HomePauseModel.editBox(0f, 0f, 1240f, 1080f), 0.01f)
    }

    @Test fun belowEditOnTheTopScreen() {
        // single screen mode's wheel spans the whole 1920 x 1080 window: k = 1, the corners at the screen's corners
        assertArrayEquals(floatArrayOf(1787f, 149f, 1904f, 266f), HomePauseModel.screensBox(0f, 0f, 1920f, 1080f), 0.01f)
        assertArrayEquals(floatArrayOf(1787f, 16f, 1904f, 133f), HomePauseModel.editBox(0f, 0f, 1920f, 1080f), 0.01f)
        // a smaller screen scales it with the kit's k
        val s = HomePauseModel.screensBox(0f, 0f, 620f, 540f)
        assertEquals(117f / 2f, s[2] - s[0], 0.01f); assertEquals(620f - 8f, s[2], 0.01f)
    }

    @Test fun theKitsSizesAreTheModels() {
        assertEquals(UiKit.POWER_SIZE, HomePauseModel.CORNER_SIZE)
        assertTrue(kit.contains("private const val POWER_MARGIN = 16f"))
        assertEquals(16f, HomePauseModel.CORNER_MARGIN)
        val box = kit.substringAfter("fun pauseScreensBox(").substringBefore("\n    }")
        assertTrue(box.contains("HomePauseModel.screensBox(screen.left, screen.top, screen.right, screen.bottom)"))
        val hit = kit.substringAfter("fun pauseScreensHit(").substringBefore("\n    }")
        assertTrue("grown as Edit's", hit.contains("val grow = maxOf(0f, (96f - b.width()) / 2f) + 8f"))
    }

    @Test fun yOnlyWhileTheButtonShows() {
        assertEquals("a one-screen device: no button, Y does nothing", HomePauseModel.Act.None, HomePauseModel.screensAct(false, null))
        assertEquals(HomePauseModel.Act.Pick(HomePauseModel.SCREENS), HomePauseModel.screensAct(true, null))
        val no = HomePauseModel.screensAct(true, "Not in a fight")
        assertTrue(no is HomePauseModel.Act.Refuse)
        assertEquals("the wheel's red line says why", "Not in a fight", (no as HomePauseModel.Act.Refuse).slice.offLine)
        assertEquals(HomePauseModel.Kind.HOME, HomePauseModel.SCREENS.kind)
        assertEquals("screens", HomePauseModel.SCREENS.key)
    }

    @Test fun theViewsRoutes() {
        assertTrue("Y = the button", view.contains("pressed(PadInput.SLOT_FACE_TOP) -> { screensPress(\"Y\"); return }"))
        val press = view.substringAfter("private fun screensPress(how: String) {").substringBefore("\n    }")
        assertTrue(press.contains("HomePauseModel.screensAct(SingleScreen.offered, screensRefusal())"))
        assertTrue("refused: the red line, the wheel stays", press.contains("is HomePauseModel.Act.Refuse -> say("))
        assertTrue("picked: the wheel folds first", press.contains("after = HomePauseModel.SCREENS") && press.contains("close(\"Screens (\$how)\")"))
        assertTrue("a finger lifted on it = Y", view.contains("UiKit.pauseScreensHit(scr, e.x, e.y)") && view.contains("screensPress(\"touch\")"))
        assertTrue("drawn below Edit while the button is offered",
            view.contains("if (!power.busy && SingleScreen.offered) UiKit.pauseScreens(c, screen, cornersIn, down = screensDown, single = single)"))
        assertFalse("the old doc line is gone", view.contains("Y does nothing on the wheel"))
        assertTrue(view.contains("Y (or a tap below Edit) switches One screen / Two screens"))
        // the host: the pick switches, the refusal reads the same table
        assertTrue(host.contains("HomePauseModel.SCREENS.key -> singleSwitch(single == null, \"the pause wheel\")"))
        assertTrue(host.contains("pauseView.screensRefusal = { SingleScreen.refusal(singleAsk()) }"))
    }

    @Test fun theTwoPictures() {
        val draw = kit.substringAfter("fun pauseScreens(c: Canvas").substringBefore("\n    }")
        assertTrue("the red plate, pressed like the others", draw.contains("buttonPlate(c, r, if (down) Button.DOWN else Button.UP)") && draw.contains("pressed(c, r, down)"))
        assertTrue("the two screens' mark, its arrow per state", draw.contains("screensMark(c, r, POWER_GOLD, single)"))
        assertTrue("Y's glyph on the inner corner", draw.contains("pad(c, yGlyph(), r.left + r.width() * 0.08f, r.bottom - r.height() * 0.08f, r.width() * 0.46f)"))
    }

    @Test fun singleScreensHubIsHomesGlance() {
        val f = HomePauseModel.Flow()
        assertEquals("two screens: the hub closes", HomePauseModel.Act.Close, f.touch(HomePauseModel.HUB))
        assertEquals("two screens: A with nothing lit does nothing", HomePauseModel.Act.None, f.a())
        f.single = true
        assertEquals(HomePauseModel.Act.Pick(HomePauseModel.HOME_GLANCE), f.touch(HomePauseModel.HUB))
        assertEquals("A with nothing lit, Release to select", HomePauseModel.Act.Pick(HomePauseModel.HOME_GLANCE), f.a())
        f.pickA = true
        assertEquals("A with nothing lit, A to select", HomePauseModel.Act.Pick(HomePauseModel.HOME_GLANCE), f.a())
        // something lit: A picks it, as on two screens
        f.vector(0.0, 1.0); f.vector(0.0, 0.0)
        val a = f.a()
        assertTrue("a lit slice still picks: $a", a is HomePauseModel.Act.Pick && (a as HomePauseModel.Act.Pick).slice != HomePauseModel.HOME_GLANCE)
        assertEquals("B still closes", HomePauseModel.Act.Close, f.b())
        assertEquals("home", HomePauseModel.HOME_GLANCE.key)
        assertTrue("the host opens the glance", host.contains("HomePauseModel.HOME_GLANCE.key -> singleGlance(true, \"the pause wheel's hub\")"))
    }
}
