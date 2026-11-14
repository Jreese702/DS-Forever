package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #233 ("when I try to read the information for the items at the bottom of the screen, the tooltip disappears"): a finger on a
 * shown tooltip is the tooltip's (TipStack.Touch) - nothing under it is pressed, a drag scrolls it, a new tooltip starts unscrolled,
 * and a frame that drew no tooltip lets every touch through as before.
 */
class TipTouchTest {
    private fun shownAt(t: TipStack.Touch, key: String = "buy 3 159") { t.clear(); t.shown(key, 100f, 200f, 500f, 800f) }

    @Test fun aFingerOnTheTooltipIsTheTooltips() {
        val t = TipStack.Touch()
        shownAt(t)
        assertTrue(t.down(300f, 500f))
        assertTrue(t.up())
        assertFalse("a second up is no longer the tooltip's", t.up())
    }

    @Test fun aFingerBesideItGoesToTheWindow() {
        val t = TipStack.Touch()
        shownAt(t)
        assertFalse(t.down(50f, 500f))
        assertFalse(t.move(300f))
        assertFalse(t.up())
    }

    @Test fun noTooltipThisFrameNoTouch() {
        val t = TipStack.Touch()
        shownAt(t)
        t.clear()
        assertFalse(t.down(300f, 500f))
    }

    @Test fun aDragScrollsItAndNeverAboveItsTop() {
        val t = TipStack.Touch()
        shownAt(t)
        t.down(300f, 700f)
        t.move(700f - 3 * TipStack.STEP - 1f)
        assertEquals(3, t.steps)
        t.up()
        t.down(300f, 300f)
        t.move(300f + 10 * TipStack.STEP)                 // dragged down past the start: back at the top, not below it
        assertEquals(0, t.steps)
        t.up()
    }

    @Test fun aDragStopsAtTheTooltipsEndSoDraggingBackMovesAtOnce() {
        val t = TipStack.Touch()
        t.clear(); t.shown("buy 3 159", 100f, 200f, 500f, 800f, maxSteps = 2)
        t.down(300f, 700f); t.move(700f - 10 * TipStack.STEP - 1f)
        assertEquals("never past the end", 2, t.steps)
        t.up()
        t.down(300f, 300f); t.move(300f + TipStack.STEP + 1f)
        assertEquals("one step back is one step", 1, t.steps)
        t.up()
        t.clear(); t.shown("buy 3 159", 100f, 200f, 500f, 800f, maxSteps = 0)
        assertEquals("the tooltip now fits: back at its top", 0, t.steps)
    }

    @Test fun aNewTooltipStartsAtItsTop() {
        val t = TipStack.Touch()
        shownAt(t, "buy 3 159")
        t.down(300f, 700f); t.move(700f - 2 * TipStack.STEP - 1f); t.up()
        assertEquals(2, t.stepsFor("buy 3 159"))
        assertEquals(0, t.stepsFor("buy 4 160"))
    }
}
