package app.gamenative

import android.os.SystemClock
import com.jrees.wowcompanion.HoldCast
import com.jrees.wowcompanion.HoldRelease
import com.jrees.wowcompanion.SetupBridge
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.GamepadState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * int/1536: THE DS MENU'S ZL / ZR HOLD-BACK MEETS THE FIRE'S MARK. Both live on player 1's triggers as WinHandler.holdOut writes them:
 * the menu (a room on single screen mode's stage owns ZL / ZR, SetupBridge.roomTriggers) takes the player's pull away from the game;
 * hold to cast's mark (fix/hold-tweaks) writes a small code on every FREE trigger (3 while a press is held, 6 + the control in the write
 * that lets a press go at its ring's fill). The rule: a held-back trigger is free for the game, so the code goes on it - never zeroed
 * away mid-hold - and no pull (analog or the L2 / R2 bits) reaches the game while the room holds them. The real holdOut (PadOut), by
 * reflection, over HoldCast's real engine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MenuTriggersMarkTest {
    // WinHandler.holdOut's body (PadOut: WinHandler itself loads a native library at its class init)
    private val holdOut = Class.forName("com.winlator.winhandler.PadOut").getDeclaredMethod("holdOut", GamepadState::class.java)
        .apply { isAccessible = true }
    private fun out(s: GamepadState): GamepadState = holdOut.invoke(null, s) as GamepadState
    private val gates0 = HoldCast.release.gatesOpen

    private fun state(l: Float, r: Float, a: Boolean = false): GamepadState = GamepadState().apply {
        triggerL = l; triggerR = r
        setPressed(ExternalController.IDX_BUTTON_L2.toInt(), l > 0f)
        setPressed(ExternalController.IDX_BUTTON_R2.toInt(), r > 0f)
        if (a) setPressed(ExternalController.IDX_BUTTON_A.toInt(), true)
    }
    private fun l2(s: GamepadState) = s.isPressed(ExternalController.IDX_BUTTON_L2.toInt())
    private fun r2(s: GamepadState) = s.isPressed(ExternalController.IDX_BUTTON_R2.toInt())
    private fun pressA(s: GamepadState) = s.isPressed(ExternalController.IDX_BUTTON_A.toInt())

    /** what WinHandler.holdFeed tells HoldCast of the triggers (as the game gets them: none pulled while a room holds them) */
    private fun feed(s: GamepadState) {
        val held = SetupBridge.roomTriggers
        HoldCast.trigIn(if (held) 0f else s.triggerL, if (held) 0f else s.triggerR)
    }

    /** A pressed at now - 373 (its ring full at now), kept with the armed code seen (`mk`): its fire at the fill is due now */
    private fun keptA(): Long {
        val now = SystemClock.uptimeMillis()
        val e = HoldCast.release
        e.cn = 3; e.ft = 17
        val t0 = now - HoldRelease.ringMs(3, 17)
        e.edge(HoldRelease.A, true, t0)
        assertTrue(e.keep("PAD1", 8, 0, 3, t0 + 100, mk = true))
        return now
    }

    @Before fun setUp() { HoldCast.release.reset(); HoldCast.release.gatesOpen = { true }; HoldCast.trigIn(0f, 0f); SetupBridge.roomTriggers = false }
    @After fun tearDown() { HoldCast.release.reset(); HoldCast.release.gatesOpen = gates0; HoldCast.trigIn(0f, 0f); SetupBridge.roomTriggers = false }

    @Test fun aRoomHoldsThePullNothingHeldNoCode() {
        SetupBridge.roomTriggers = true
        val s = state(0f, 1f)
        val o = out(s)
        assertEquals("ZR's pull held back", 0f, o.triggerR, 0f)
        assertFalse("its digital bit too", r2(o))
        assertEquals("the game's own state object untouched (a copy)", 1f, s.triggerR, 0f)
        assertTrue(r2(s))
    }

    @Test fun theArmedCodeGoesOnAHeldBackTrigger() {
        SetupBridge.roomTriggers = true
        val s = state(0f, 1f, a = true)
        feed(s)
        HoldCast.release.cn = 3
        HoldCast.release.edge(HoldRelease.A, true, SystemClock.uptimeMillis())
        val o = out(s)
        val armed = HoldCast.markValue(HoldRelease.MARK_ARMED)
        assertEquals("the free LT carries the armed code", armed, o.triggerL, 0f)
        assertEquals("the held-back RT carries it too (not zeroed away)", armed, o.triggerR, 0f)
        assertTrue("the code is far below the game's trigger button", armed < HoldCast.TRIG_FREE)
        assertFalse("no ZR press reaches the game", r2(o))
        assertTrue("A itself as held", pressA(o))
    }

    @Test fun aKeptPressFiresAtTheFillWithItsCodeWhileARoomHoldsBothTriggers() {
        // LT + RT both pulled, a room on the stage: both read free for the game, so the marked fire goes at the fill (R), with its code
        SetupBridge.roomTriggers = true
        val s = state(1f, 1f, a = true)
        feed(s)
        val now = keptA()
        assertTrue("the fire at R", HoldCast.release.due(now))
        val o = out(s)
        val fire = HoldCast.markValue(HoldRelease.markOf(HoldRelease.A))
        assertEquals("the fire's code on LT", fire, o.triggerL, 0f)
        assertEquals("the fire's code on RT", fire, o.triggerR, 0f)
        assertFalse("A up for the game at its fire", pressA(o))
        assertFalse("the forced write keeps L2 held back (the buttons are made from the player's state)", l2(o))
        assertFalse(r2(o))
    }

    @Test fun outOfARoomBothPulledTheFireWaitsForTheLooksAsBefore() {
        // no room: LT + RT pulled are the game's - no free trigger, no code; the marked fire falls back to F (fix/hold-tweaks' rule)
        val s = state(1f, 1f, a = true)
        feed(s)
        val now = keptA()
        assertFalse("nothing fires at R", HoldCast.release.due(now))
        val o = out(s)
        assertEquals("LT's pull as it is", 1f, o.triggerL, 0f)
        assertEquals("RT's pull as it is", 1f, o.triggerR, 0f)
        assertTrue(l2(o) && r2(o))
    }

    @Test fun theRoomClosesThePullIsTheGamesAgainTheCodeOnTheFreeOne() {
        SetupBridge.roomTriggers = true
        val s = state(0f, 1f, a = true)
        feed(s)
        HoldCast.release.cn = 3
        HoldCast.release.edge(HoldRelease.A, true, SystemClock.uptimeMillis())
        assertEquals("held back: the code only", HoldCast.markValue(HoldRelease.MARK_ARMED), out(s).triggerR, 0f)
        SetupBridge.roomTriggers = false
        feed(s)
        val o = out(s)
        assertEquals("ZR given back at once: the full pull", 1f, o.triggerR, 0f)
        assertTrue(r2(o))
        assertEquals("the code stays on the free LT", HoldCast.markValue(HoldRelease.MARK_ARMED), o.triggerL, 0f)
    }
}
