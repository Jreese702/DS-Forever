package app.gamenative

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import app.gamenative.ui.screen.xserver.PhysicalControllerHandler
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.ExternalControllerBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GitHub #292 (1.0.32, "LT and RT doesnt work"; a player's answer on it: "LT & RT change Digital to Analog"): the Thor's "Digital"
 * L2 / R2 setting sends the triggers as key events only. Both routes into the game take them now, as a full pull OR-ed with the axis:
 * the WinHandler route (ExternalController.updateStateFromKeyEvent) and the profile route (PhysicalControllerHandler, the wildcard
 * "Physical Controller Default"); and a stick's motion event (its trigger axes at 0) never lets a held trigger key go.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DigitalTriggersTest {
    private fun key(action: Int, code: Int) = KeyEvent(0L, 0L, action, code, 0, 0, 7, 0, 0, InputDevice.SOURCE_GAMEPAD)

    /** a joystick motion event: the left stick at [lx], both triggers at [lt] / [rt] */
    private fun motion(lx: Float, lt: Float = 0f, rt: Float = 0f): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_X, lx)
            setAxisValue(MotionEvent.AXIS_LTRIGGER, lt)
            setAxisValue(MotionEvent.AXIS_RTRIGGER, rt)
        })
        return MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 1, props, coords, 0, 0, 1f, 1f, 7, 0, InputDevice.SOURCE_JOYSTICK, 0)
    }

    @Test fun theWinHandlerRouteTakesADigitalTriggerKey() {
        val c = ExternalController()
        assertTrue(c.updateStateFromKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L2)))
        assertEquals(1f, c.state.triggerL, 0f)
        assertTrue(c.state.isPressed(ExternalController.IDX_BUTTON_L2.toInt()))
        // the left stick moves: the event carries the trigger axes at 0, the key is still held
        assertTrue(c.updateStateFromMotionEvent(motion(0.7f)))
        assertEquals("a stick's motion never lets a held trigger key go", 1f, c.state.triggerL, 0f)
        c.updateStateFromKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_L2))
        assertEquals(0f, c.state.triggerL, 0f)
        assertFalse(c.state.isPressed(ExternalController.IDX_BUTTON_L2.toInt()))
        // RT the same
        c.updateStateFromKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R2))
        assertEquals(1f, c.state.triggerR, 0f)
        c.updateStateFromKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R2))
        assertEquals(0f, c.state.triggerR, 0f)
    }

    @Test fun anAnalogTriggerIsAsBefore() {
        val c = ExternalController()
        c.updateStateFromMotionEvent(motion(0f, lt = 0.6f))
        assertEquals(0.6f, c.state.triggerL, 0.001f)
        assertTrue(c.state.isPressed(ExternalController.IDX_BUTTON_L2.toInt()))
        c.updateStateFromMotionEvent(motion(0f, lt = 0f))
        assertEquals(0f, c.state.triggerL, 0f)
        // the key and the axis together (a pad that sends both): the key's full pull while it is held, then the axis again
        c.updateStateFromKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L2))
        c.updateStateFromMotionEvent(motion(0f, lt = 0.4f))
        assertEquals(1f, c.state.triggerL, 0f)
        c.updateStateFromKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_L2))
        assertEquals(0.4f, c.state.triggerL, 0.001f)
    }

    @Test fun theProfileRouteTakesADigitalTriggerKey() {
        val profile = ControlsProfile(ApplicationProvider.getApplicationContext(), 9_292)
        val pad = profile.addController("*")
        for ((code, b) in listOf(KeyEvent.KEYCODE_BUTTON_L2 to Binding.GAMEPAD_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2 to Binding.GAMEPAD_BUTTON_R2,
                KeyEvent.KEYCODE_BUTTON_A to Binding.GAMEPAD_BUTTON_A)) {
            pad.addControllerBinding(ExternalControllerBinding().apply { setKeyCode(code); setBinding(b) })
        }
        var sent = 0
        val h = PhysicalControllerHandler(profile, null, gamepadStateSender = { sent++ })
        val virtual = profile.gamepadState
        assertTrue(h.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L2)))
        assertEquals("the game's LT: a full pull", 1f, virtual.triggerL, 0f)
        assertTrue(virtual.isPressed(ExternalController.IDX_BUTTON_L2.toInt()))
        assertTrue(sent > 0)
        assertTrue(h.onGenericMotionEvent(motion(0.8f)))
        assertEquals("the left stick moving keeps LT held", 1f, virtual.triggerL, 0f)
        assertTrue(h.onKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_L2)))
        assertEquals(0f, virtual.triggerL, 0f)
        assertFalse(virtual.isPressed(ExternalController.IDX_BUTTON_L2.toInt()))
        // RT, and a face button beside it, unchanged
        h.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R2))
        h.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(1f, virtual.triggerR, 0f)
        assertTrue(virtual.isPressed(ExternalController.IDX_BUTTON_A.toInt()))
        h.onKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R2))
        h.onKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(0f, virtual.triggerR, 0f)
        assertFalse(virtual.isPressed(ExternalController.IDX_BUTTON_A.toInt()))
        // an analog pull through the profile route, as before
        h.onGenericMotionEvent(motion(0f, rt = 0.5f))
        assertEquals(0.5f, virtual.triggerR, 0.001f)
        h.onGenericMotionEvent(motion(0f, rt = 0f))
        assertEquals(0f, virtual.triggerR, 0f)
    }
}
