package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub #305 (1.0.32: "controls not working on bottom screen"; its pad: pw=1542 ph=1080 on a 1920x1080 top screen, every hold "NOT
 * sent"): the taps refused for the game's screen shape are counted for the report, and the log names each new pair once.
 */
class TapShapeTest {
    @Test fun refusalsAreCountedAndNamedOnce() {
        TapShape.reset()
        assertNull("nothing refused: no report line", TapShape.line())
        assertTrue("the first refusal is logged", TapShape.refuse(1542, 1080, 1920, 1080))
        assertFalse("the same pair again: not logged again", TapShape.refuse(1542, 1080, 1920, 1080))
        assertEquals("Taps into the game refused for the screen's shape: 2 (the game's screen 1542x1080, the top screen 1920x1080)", TapShape.line())
        assertTrue("another size: logged", TapShape.refuse(1280, 1024, 1920, 1080))
        TapShape.reset()
    }

    /** PR #256 (KillgoreAlpha): display 0's size is refused only when it is the companion screen's (or, before that is known, any
     *  other screen's) - never for being smaller than another panel */
    @Test fun displayZeroGuard() {
        val thorBottom = 1240 to 1080
        // a Thor: the top screen passes with the companion known or not; a read of the bottom screen's size is refused either way
        assertNull(TapShape.panelRefusal(1920, 1080, thorBottom, listOf(thorBottom)))
        assertNull(TapShape.panelRefusal(1920, 1080, null, listOf(thorBottom)))
        assertEquals("display 0 read back the companion screen's 1240x1080", TapShape.panelRefusal(1240, 1080, thorBottom, listOf(thorBottom)))
        assertEquals("display 0 read 1240x1080, another screen's size, while the companion screen is not known",
            TapShape.panelRefusal(1240, 1080, null, listOf(thorBottom)))
        // the Nova with the Dual Screen Add-on: display 0 (1280x960) is the SMALLER panel and still passes (the old rule refused it)
        val addOn = 1920 to 1080
        assertNull(TapShape.panelRefusal(1280, 960, addOn, listOf(addOn)))
        assertNull(TapShape.panelRefusal(1280, 960, null, listOf(addOn)))
        // no mode
        assertEquals("display 0's mode is 0x1080", TapShape.panelRefusal(0, 1080, null, emptyList()))
    }
}
