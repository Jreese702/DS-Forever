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
}
