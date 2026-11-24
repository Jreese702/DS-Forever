package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HOLD TO CAST, THE APP'S PROBE AND WIRING (PadMap m190). The probe ("Yes, a quick test first"): never pulsed while the game has a binding
 * on the Guide button; once per game session; usable only when the router's own read saw the marker and nothing was bound or done.
 * The add-on's map. The launcher's controller path through the gate (player 1, both roads, the Guide byte), its pause.
 */
class HoldCastTest {
    private val root = listOf(File("../app"), File("app")).first { it.isDirectory }.parentFile!!.absoluteFile
    private fun src(rel: String) = File(root, rel).readText()

    // ---------------------------------------------------------------- the probe

    @Test fun aBoundGuideIsNeverPulsed() {
        val p = HoldCast.Probe()
        val o = p.onReady(7L, bound = true, bn = "PADSYSTEM", ba = "TOGGLEGAMEMENU", now = 1000)
        assertNotNull(o)
        assertEquals(HoldCast.Result.BOUND, o!!.result)
        assertEquals("probe: the Guide button is bound in the game (PADSYSTEM = TOGGLEGAMEMENU): not pulsed -> hold to cast unavailable", o.line)
        assertFalse("never due", p.due(1_000_000, fighting = false, focused = true))
        assertEquals(HoldCast.Probe.St.DONE, p.st)
    }

    @Test fun oncePerSessionAFewSecondsAfterReady() {
        val p = HoldCast.Probe()
        assertNull(p.onReady(7L, false, null, null, 1000))
        assertFalse(p.due(1000 + HoldCast.DELAY_MS - 1, false, true))
        assertTrue(p.due(1000 + HoldCast.DELAY_MS, false, true))
        assertFalse("not in a fight", p.due(1000 + HoldCast.DELAY_MS, true, true))
        assertFalse("not while the game screen is away", p.due(1000 + HoldCast.DELAY_MS, false, false))
        p.pulsed(5000)
        assertNull("a second ready (a /reload) in the same session: ignored", p.onReady(7L, false, null, null, 6000))
        assertEquals(HoldCast.Probe.St.PULSED, p.st)
        val o = p.onDone("PADSYSTEM", "PADSYSTEM", "PADSYSTEM", null, null, 6100)!!
        assertEquals(HoldCast.Result.SEEN, o.result)
        assertEquals("PADSYSTEM", o.guide)
        assertEquals("probe: Guide seen (event PADSYSTEM, state PADSYSTEM, router PADSYSTEM; binding none) -> hold to cast on", o.line)
        assertNull("done once", p.onDone("PADSYSTEM", null, "PADSYSTEM", null, null, 6200))
        assertNull("a new session probes again", p.onReady(8L, false, null, null, 9000))
        assertEquals(HoldCast.Probe.St.ARMED, p.st)
    }

    @Test fun aDoneWithoutOurPulseSaysNothing() {
        val p = HoldCast.Probe()
        p.onReady(1L, false, null, null, 0)
        assertNull("nothing stored", p.onDone(null, null, null, null, null, 100))
        assertEquals("the add-on stopped watching: no pulse this session (it would read as no answer)", HoldCast.Probe.St.DONE, p.st)
        assertFalse(p.due(1_000_000, false, true))
        val q = HoldCast.Probe()
        assertNull("before any ready: nothing", q.onDone("PADSYSTEM", null, "PADSYSTEM", null, null, 100))
        assertEquals(HoldCast.Probe.St.IDLE, q.st)
    }

    @Test fun theOutcomes() {
        fun done(ev: String?, sn: String?, rn: String?, gb: String?, acted: String?): HoldCast.Outcome {
            val p = HoldCast.Probe(); p.onReady(1L, false, null, null, 0); p.pulsed(3000)
            return p.onDone(ev, sn, rn, gb, acted, 3100)!!
        }
        assertEquals(HoldCast.Result.UNSEEN, done(null, null, null, null, null).result)
        assertEquals("the event alone is not enough: the router's read decides", HoldCast.Result.UNSEEN,
            done("PADSYSTEM", "PADSYSTEM", null, null, null).result)
        assertEquals(HoldCast.Result.BOUND, done("PADSYSTEM", null, "PADSYSTEM", "TOGGLEGAMEMENU", null).result)
        val acted = done("PADSYSTEM", null, "PADSYSTEM", null, "menu")
        assertEquals(HoldCast.Result.ACTED, acted.result)
        assertNull(acted.guide)
        assertTrue(acted.line.contains("the game reacted: menu") && acted.line.endsWith("-> hold to cast unavailable"))
        assertEquals("probe: Guide not seen (event -, state -, router -; binding none) -> hold to cast unavailable",
            done(null, null, null, null, null).line)
        val p = HoldCast.Probe(); p.onReady(1L, false, null, null, 0); p.pulsed(3000)
        assertNull(p.timedOut(3000 + HoldCast.ANSWER_MS - 1))
        assertEquals(HoldCast.Result.NO_ANSWER, p.timedOut(3000 + HoldCast.ANSWER_MS)!!.result)
    }

    @Test fun usableOnlyAfterSeen() {
        HoldCast.Store.testResult = null
        assertFalse(HoldCast.usable)
        HoldCast.Store.testResult = "not seen"; assertFalse(HoldCast.usable)
        HoldCast.Store.testResult = "seen"; assertTrue(HoldCast.usable)
        HoldCast.Store.testResult = null
    }

    // ---------------------------------------------------------------- the add-on's map

    @Test fun theMap() {
        val m = HoldCast.parseMap(JSONObject().put("type", "holdmap").put("on", true).put("b", "bare:8,lt:6,ltrt:1,rt:9,odd:2,bare"))!!
        assertEquals(setOf(HoldGate.key(0, 8), HoldGate.key(1, 6), HoldGate.key(3, 1)), m)
        assertEquals(emptySet<Int>(), HoldCast.parseMap(JSONObject().put("type", "holdmap").put("b", "bare:8")))
        assertNull(HoldCast.parseMap(JSONObject().put("type", "padmap")))
    }

    // ---------------------------------------------------------------- the launcher's road

    @Test fun theLauncherSendsPlayerOneThroughTheGate() {
        val w = src("app/src/main/java/com/winlator/winhandler/WinHandler.java")
        assertTrue(w.contains("GamepadState state = holdGated(controller, slot);"))
        assertTrue("the marker is SDL button 5, player 1 only", w.contains("sdlButtons[5] = (slot == 0 && holdGuide) ? (byte)1 : (byte)0;"))
        assertTrue("player 1 only", w.contains("if (slot != 0 || s == null) return s;"))
        assertEquals("both packet roads carry the gated copy", 2, Regex("holdWrite\\(this\\.currentController, this\\.sendData\\)").findAll(w).count())
        assertTrue("the gate's own moments", w.contains("holdTimer.postAtTime(holdTick, Math.max(next, now + 1));"))
        assertTrue("never in the way of the pad", w.contains("} catch (Throwable t) {"))
        val x = src("app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt")
        assertTrue(x.contains("if (event == Lifecycle.Event.ON_PAUSE) com.jrees.wowcompanion.HoldCast.focusLost()"))
        val e = src("app/src/main/cpp/evshim/evshim.c")
        assertTrue("evshim passes all 15 buttons (no native change)", e.contains("for (int i = 0; i < 15; i++) {\n        p_SDL_JoystickSetVirtualButton(js, i, state->btn[i]);"))
        assertTrue("the key channel stays parked", src("companion/app/src/main/java/com/jrees/wowcompanion/TapBridge.kt").contains("private const val KEYS_ON = false"))
    }
}
