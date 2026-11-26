package app.gamenative

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.winlator.inputcontrols.ExternalController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * THE int/1533 REVIEW, ROUND 2 (single screen mode: a palm on the bottom screen moves Android's keys there while the pad is held, and the
 * launcher's activity gets a joystick ACTION_CANCEL). ExternalController's cancel let the hat's D-pad go - and the hat's NEXT motion (a
 * stick turn, trigger noise: the hat still reads the held direction) pressed it a second time, a press the player never made (a D-pad
 * action twice, a second hold). A held key's down is never sent again, so the hat now matches it: a direction let go by the cancel stays
 * up until the hat first reads it released, and only then can it be pressed again. And the sticks and the analog triggers let go at the
 * cancel too: at rest no motion follows, so a held LT (the layer) or a pushed stick stayed the game's until the pad was touched again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HatCancelTest {
    /** a joystick motion: the hat at [hx] / [hy], the left stick at [lx], the left trigger at [lt] */
    private fun motion(action: Int, hx: Float = 0f, hy: Float = 0f, lx: Float = 0f, lt: Float = 0f): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_HAT_X, hx)
            setAxisValue(MotionEvent.AXIS_HAT_Y, hy)
            setAxisValue(MotionEvent.AXIS_X, lx)
            setAxisValue(MotionEvent.AXIS_LTRIGGER, lt)
        })
        return MotionEvent.obtain(0L, 0L, action, 1, props, coords, 0, 0, 1f, 1f, 7, 0, InputDevice.SOURCE_JOYSTICK, 0)
    }
    private fun key(action: Int, code: Int) = KeyEvent(0L, 0L, action, code, 0, 0, 7, 0, 0, InputDevice.SOURCE_GAMEPAD)

    @Test fun aCancelledHatDirectionIsNeverPressedAgainByTheNextMotion() {
        val c = ExternalController()
        assertTrue(c.updateStateFromMotionEvent(motion(MotionEvent.ACTION_MOVE, hy = -1f)))
        assertTrue("D-pad Up held (the hat)", c.state.dpad[0])
        assertTrue("the palm: the joystick's cancel", c.updateStateFromMotionEvent(motion(MotionEvent.ACTION_CANCEL, hy = -1f)))
        assertFalse("let go at the cancel", c.state.dpad[0])
        // the keys back, Up still held: a right-stick turn's motion carries the hat at -1 - no second press
        c.updateStateFromMotionEvent(motion(MotionEvent.ACTION_MOVE, hy = -1f, lx = 0.5f))
        c.updateStateFromMotionEvent(motion(MotionEvent.ACTION_MOVE, hy = -1f, lx = 0.6f))
        assertFalse("still held since the cancel: up for the game, never pressed again", c.state.dpad[0])
        // another direction pressed meanwhile is a real new press
        c.updateStateFromMotionEvent(motion(MotionEvent.ACTION_MOVE, hx = 1f, hy = -1f))
        assertTrue("Right: a new press", c.state.dpad[1]); assertFalse(c.state.dpad[0])
        // the hat read released: the cut is over; the next Up is a real press
        c.updateStateFromMotionEvent(motion(MotionEvent.ACTION_MOVE))
        assertFalse(c.state.dpad[0]); assertFalse(c.state.dpad[1])
        c.updateStateFromMotionEvent(motion(MotionEvent.ACTION_MOVE, hy = -1f))
        assertTrue("a new Up after the release passes at once", c.state.dpad[0])
        // a cancel with nothing held cuts nothing
        c.updateStateFromMotionEvent(motion(MotionEvent.ACTION_MOVE))
        c.updateStateFromMotionEvent(motion(MotionEvent.ACTION_CANCEL))
        c.updateStateFromMotionEvent(motion(MotionEvent.ACTION_MOVE, hy = 1f))
        assertTrue("Down after an empty cancel: pressed", c.state.dpad[2])
    }

    @Test fun theSticksAndTheAnalogTriggersLetGoAtTheCancel() {
        val c = ExternalController()
        c.updateStateFromMotionEvent(motion(MotionEvent.ACTION_MOVE, lt = 0.8f))
        assertTrue("LT held (analog: the layer)", c.state.isPressed(ExternalController.IDX_BUTTON_L2.toInt()))
        c.state.thumbLX = 0.7f; c.state.thumbRY = -0.4f                 // (no device range here: the sticks set by hand)
        c.updateStateFromMotionEvent(motion(MotionEvent.ACTION_CANCEL, lt = 0.8f))
        assertEquals("LT let go at the cancel", 0f, c.state.triggerL, 0f)
        assertFalse(c.state.isPressed(ExternalController.IDX_BUTTON_L2.toInt()))
        assertEquals("the sticks centred", 0f, c.state.thumbLX, 0f); assertEquals(0f, c.state.thumbRY, 0f)
        // the next motion gives them back as they are (a modifier still held is held again)
        c.updateStateFromMotionEvent(motion(MotionEvent.ACTION_MOVE, lt = 0.8f))
        assertTrue(c.state.isPressed(ExternalController.IDX_BUTTON_L2.toInt()))
        // a DIGITAL trigger's key is its own: the cancel leaves its full pull to the key's own (cancelled) up
        val d = ExternalController()
        d.updateStateFromKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L2))
        d.updateStateFromMotionEvent(motion(MotionEvent.ACTION_CANCEL))
        assertEquals(1f, d.state.triggerL, 0f)
        d.updateStateFromKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_L2, 0, 0, 7, 0, KeyEvent.FLAG_CANCELED, InputDevice.SOURCE_GAMEPAD))
        assertEquals(0f, d.state.triggerL, 0f)
    }
}
