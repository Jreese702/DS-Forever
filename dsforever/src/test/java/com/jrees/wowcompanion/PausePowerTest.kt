package com.jrees.wowcompanion

import com.jrees.wowcompanion.stream.BridgeMsg
import com.jrees.wowcompanion.stream.BridgeProtocol
import com.jrees.wowcompanion.stream.SessionEnd
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE PAUSE MENU'S POWER BUTTON (feat/pause-power; the user, 2026-11-18: "on the top right corner of the screen I want to add some kind
 * of power button that effectively opens a prompt to close DS forever. When streaming this should also close wow on the PC and revert
 * everything back and be the clean way to disconnect"), off-device: the prompt's keys ([PausePower.Flow]), the close's order in Local
 * and Streaming with the safe close and the bridge mocked ([PausePower.Sequence]), the clean disconnect's wait ([SessionEnd]) and its
 * bridge words, the strings in ES / FR, and the routes in the sources.
 */
class PausePowerTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val view = File(src, "HomePauseView.kt").readText()
    private val host = File(src, "CompanionHost.kt").readText()

    // ---------------------------------------------------------------- the prompt

    @Test fun yAsksAClosesBGoesBackOneStep() {
        val f = PausePower.Flow()
        assertEquals(PausePower.Act.Asked, f.press(null))
        assertTrue(f.asking)
        assertEquals("a second Y while it asks: nothing", PausePower.Act.None, f.press(null))
        assertEquals(PausePower.Act.Cancelled, f.cancel())
        assertEquals("B went back to the wheel only", PausePower.State.NONE, f.state)
        f.press(null)
        assertEquals(PausePower.Act.Confirmed, f.confirm())
        assertEquals(PausePower.State.CLOSING, f.state)
        assertEquals("nothing cancels the close once it runs", PausePower.Act.None, f.cancel())
        assertEquals(PausePower.Act.None, f.confirm())
        assertEquals(PausePower.Act.None, f.press(null))
    }

    @Test fun aFightRefusesTheButton() {
        val f = PausePower.Flow()
        assertEquals(PausePower.Act.Refused(PausePower.FIGHT_LINE), f.press(PausePower.FIGHT_LINE))
        assertEquals(PausePower.State.NONE, f.state)
        assertEquals("A without the prompt does nothing", PausePower.Act.None, f.confirm())
    }

    @Test fun touchCloseConfirmsCancelAndOutsideGoBackTheBoxDoesNothing() {
        val f = PausePower.Flow()
        f.press(null)
        assertEquals(PausePower.Act.None, f.touch(-1))
        assertEquals(PausePower.Act.Cancelled, f.touch(-2))
        f.press(null)
        assertEquals(PausePower.Act.Cancelled, f.touch(UiKit.POWER_CANCEL))
        f.press(null)
        assertEquals(PausePower.Act.Confirmed, f.touch(UiKit.POWER_CLOSE))
    }

    @Test fun theLinesSayWhatElseCloses() {
        assertEquals("WoW closes too", PausePower.context(false))
        assertEquals("WoW on your PC closes and your PC is put back", PausePower.context(true))
        assertEquals("Closing WoW on your PC", PausePower.closing(true))
    }

    // ---------------------------------------------------------------- the close, Local and Streaming

    private class Rig(var streaming: Boolean = false, var gameUp: Boolean = true, var hasClean: Boolean = true, var tap: Boolean = true) {
        val did = ArrayList<String>()
        var cleanDone: ((Boolean) -> Unit)? = null
        var endDone: ((PausePower.Ended) -> Unit)? = null
        val seq = PausePower.Sequence(
            streaming = { streaming },
            gameUp = { gameUp },
            cleanExit = { if (!hasClean) null else { why, done -> did += "cleanExit($why)"; cleanDone = done } },
            quitTap = { did += "quitTap"; tap },
            endSession = { if (!streaming) null else { why, asked, done -> did += "endSession($why, asked=$asked)"; endDone = done } },
            quitApp = { force -> did += "quitApp(force=$force)" },
            log = {},
        )
    }

    @Test fun localTheSafeCloseFirstThenTheApp() {
        val r = Rig()
        r.seq.run()
        assertEquals(listOf("cleanExit(power)"), r.did)
        r.seq.run()
        assertEquals("one run per press", listOf("cleanExit(power)"), r.did)
        r.cleanDone!!(false)
        assertEquals(listOf("cleanExit(power)", "quitApp(force=false)"), r.did)
    }

    @Test fun localTheOldCloseOnlyWhenTheSafeCloseSaysSo() {
        val r = Rig()
        r.seq.run(); r.cleanDone!!(true)
        assertEquals(listOf("cleanExit(power)", "quitApp(force=true)"), r.did)
        val none = Rig(hasClean = false); none.seq.run()
        assertEquals("no clean exit route: the old close", listOf("quitApp(force=true)"), none.did)
        val idle = Rig(gameUp = false); idle.seq.run()
        assertEquals("no game: the app closes at once", listOf("quitApp(force=false)"), idle.did)
    }

    @Test fun streamingTheGameExitTapThenDesktopThenTheApp() {
        val r = Rig(streaming = true)
        r.seq.run()
        assertEquals(listOf("quitTap", "endSession(the power button, asked=true)"), r.did)
        assertFalse("the local safe close is not Streaming's", r.did.any { it.startsWith("cleanExit") })
        r.endDone!!(PausePower.Ended(true, "closed", true))
        assertEquals("quitApp(force=false)", r.did.last())
        val noTap = Rig(streaming = true, tap = false); noTap.seq.run()
        assertEquals("endSession(the power button, asked=false)", noTap.did.last())
        noTap.endDone!!(PausePower.Ended(false, why = "no answer"))
        assertEquals("an older Desktop: the app closes anyway", "quitApp(force=false)", noTap.did.last())
    }

    // ---------------------------------------------------------------- the clean disconnect's wait and words

    @Test fun theWaitEndsOnDesktopsWordOrItsTimes() {
        val e = SessionEnd(0L)
        assertNull("the send's result not known yet", e.check(100L))
        e.sent(true)
        assertNull(e.check(1_000L))
        assertFalse("no ack in 5 s: an older Desktop", e.check(SessionEnd.ACK_MS)!!.answered)
        val ok = SessionEnd(0L); ok.sent(true)
        ok.said(BridgeMsg.SessionEnd(done = false))
        assertNull("acked: wait on past 5 s", ok.check(30_000L))
        ok.said(BridgeMsg.SessionEnd(done = true, wow = "closed", putBack = true))
        assertEquals(PausePower.Ended(true, "closed", true, ""), ok.check(30_500L))
        val slow = SessionEnd(0L); slow.sent(true); slow.said(BridgeMsg.SessionEnd(false))
        assertFalse(slow.check(SessionEnd.DONE_MS)!!.answered)
        val gone = SessionEnd(0L); gone.sent(false)
        assertEquals("no bridge connection", gone.check(1L)!!.why)
    }

    /** fix/1492-feedback (the user, 2026-11-18, test 1492: "closing from the power button didn't close the app"): the power button's
     *  end goes on Desktop's "session_ending" (Desktop then waits out the game's own 20 s Exit Game on the PC by itself) */
    @Test fun thePowerButtonsEndGoesOnDesktopsAck() {
        val e = SessionEnd(0L, closeOnAck = true); e.sent(true)
        assertNull("nothing heard yet", e.check(500L))
        e.said(BridgeMsg.SessionEnd(done = false))
        assertNull("the Exit Game tap still going down the stream", e.check(SessionEnd.ACK_CLOSE_MS - 1))
        val r = e.check(SessionEnd.ACK_CLOSE_MS)!!
        assertTrue("Desktop heard: answered", r.answered)
        assertEquals("closing", r.wow)
        // test 1492's timing: Desktop's session_ended came 20 s after its ack - the power button no longer waits for it
        assertTrue(SessionEnd.ACK_CLOSE_MS < 20_000L)
        val old = SessionEnd(0L); old.sent(true); old.said(BridgeMsg.SessionEnd(done = false))
        assertNull("the waiting screen's Exit game still waits for Desktop's word", old.check(20_000L))
        val noAck = SessionEnd(0L, closeOnAck = true); noAck.sent(true)
        assertFalse("no ack: an older Desktop, as before", noAck.check(SessionEnd.ACK_MS)!!.answered)
    }

    @Test fun thePowerRouteAsksForTheEarlyEndAndTheExitGameDoesNot() {
        val top = File(listOf("src/main/java/com/jrees/wowcompanion/stream/StreamTop.kt", "dsforever/src/main/java/com/jrees/wowcompanion/stream/StreamTop.kt")
            .first { File(it).isFile }).readText()
        assertTrue(top.contains("SetupBridge.endSession = { why, asked, done -> main.post { endSession(why, asked, closeOnAck = true, then = done) } }"))
        assertTrue(top.contains("SetupBridge.endStream = { main.post { endSession(\"the menu's Exit game\", false) { back() } } }"))
    }

    /** fix/1492-feedback: the close's hard fallback - the process ends 5 s after the close if it is still up, on its own thread,
     *  standing down for a DS Forever opened again */
    @Test fun theCloseHasAHardFallback() {
        val main = File(listOf("../app/src/main/java/app/gamenative/MainActivity.kt", "app/src/main/java/app/gamenative/MainActivity.kt")
            .first { File(it).isFile }).readText()
        val quit = main.substringAfter("LauncherHooks.quitApp = { force ->").substringBefore("setContent {")
        assertTrue(quit.contains("closeFallback(if (force) 1_500L else 0L)"))
        val fb = main.substringAfter("private fun closeFallback(").substringBefore("companion object")
        assertTrue("every task of ours", fb.contains("appTasks?.forEach { runCatching { it.finishAndRemoveTask() } }"))
        assertTrue("the bottom screen", fb.contains("DsForeverScreen.current?.stop()"))
        assertTrue("the environment", fb.contains("PluviaApp.shutdownEnvironment()"))
        assertTrue("our own exit's mark first", fb.indexOf("LastRun.ownExit(app)") in 0 until fb.indexOf("killProcess"))
        assertTrue("stands down for a new start", fb.contains("if (createdGen != gen)"))
        assertTrue("its own thread", fb.contains("isDaemon = true"))
        assertEquals(5_000L, Regex("CLOSE_FALLBACK_MS = ([0-9_]+)L").find(main)!!.groupValues[1].replace("_", "").toLong())
    }

    @Test fun thePowerButtonIsOneAndAHalfTimesBigger() {
        assertEquals("1.5 x the 1492 button's 78", 78f * 1.5f, UiKit.POWER_SIZE)
        val kit = File(src, "UiKit.kt").readText()
        assertTrue("still the top-right corner", kit.contains("return RectF(screen.right - m - sz, screen.top + m, screen.right - m, screen.top + m + sz)"))
    }

    @Test fun theBridgeWords() {
        val o = JSONObject(BridgeProtocol.endSession(true))
        assertEquals("end_session", o.getString("t"))
        assertTrue(o.getBoolean("quit_asked"))
        assertEquals(BridgeMsg.SessionEnd(done = false), BridgeProtocol.parse("""{"t":"session_ending"}"""))
        assertEquals(BridgeMsg.SessionEnd(done = true, wow = "left", putBack = false, why = "WoW didn't close"),
            BridgeProtocol.parse("""{"t":"session_ended","wow":"left","put_back":false,"why":"WoW didn't close"}"""))
    }

    // ---------------------------------------------------------------- the routes in the sources

    @Test fun thePadRoutes() {
        assertTrue("Y opens the prompt from the wheel", view.contains("pressed(PadInput.SLOT_FACE_TOP) -> { powerAct(power.press(powerRefusal()), \"Y\"); return }"))
        assertTrue("A closes", view.contains("pressed(PadInput.SLOT_FACE_BOTTOM) -> powerAct(power.confirm(), \"A\")"))
        assertTrue("B: one step back", view.contains("pressed(PadInput.SLOT_FACE_RIGHT) -> powerAct(power.cancel(), \"B\")"))
        assertTrue("the wheel opens roomy", view.contains("UiKit.pauseRoomy = true"))
        // the user's pick (2026-11-24): "Keep the confirm, allow in fights" - Close runs the immediate exit (ExitNow), as the Menu's Exit game
        assertTrue("Close = the immediate exit; the old sequence only without the launcher",
            host.contains("pauseView.onPower = { LauncherHooks.exitNow?.invoke(ExitNow.POWER) ?: powerOff.run() }"))
        assertFalse("no refusal in a fight any more", host.contains("pauseView.powerRefusal ="))
        assertTrue("the pause pushes the bars up, the target radial keeps them",
            host.contains("pauseView.onSlide = { t -> slideBarsUp = true;") && host.contains("targetRadial.onSlide = { t -> slideBarsUp = false;"))
        // feat/exit-now (the user, 2026-11-24): the waiting screen's Exit game closes DS Forever at once (ExitNow); the clean disconnect
        // stays only for the companion app without the launcher
        assertTrue("the waiting screen's Exit game is the immediate exit; the old routes without the launcher",
            host.contains("StartMenu.onExit = { LauncherHooks.exitNow?.invoke(ExitNow.MENU) ?: (SetupBridge.endStream ?: SetupBridge.exitStream ?: SetupBridge.exitGame)?.invoke() }"))
    }

    @Test fun everyLineIsInSpanishAndFrench() {
        val i18n = File(src, "../../../../assets/companion/i18n")
        val words = listOf("Close DS Forever?", "Closing DS Forever", PausePower.LOCAL_LINE, PausePower.STREAM_LINE, PausePower.CLOSING_LOCAL,
            PausePower.CLOSING_STREAM, PausePower.FIGHT_LINE, "Cancel", "Close")
        for (lang in listOf("es", "fr")) {
            val raw = File(i18n, "$lang.json").readText()
            val plain = JSONObject(raw).getJSONObject("plain")
            for (w in words) assertTrue("$lang: $w", plain.has(w) && plain.getString(w).isNotBlank())
            for (w in words) assertEquals("$lang: '$w' once", 1, Regex(Regex.escape("\"${w.replace("\"", "\\\"")}\":")).findAll(raw).count())
        }
    }
}
