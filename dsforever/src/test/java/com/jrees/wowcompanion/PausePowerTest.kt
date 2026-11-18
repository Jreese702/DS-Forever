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
        assertTrue("the host runs the sequence", host.contains("pauseView.onPower = { powerOff.run() }"))
        assertTrue("a fight refuses", host.contains("if (homeView.inFight()) PausePower.FIGHT_LINE else null"))
        assertTrue("the pause pushes the bars up, the target radial keeps them",
            host.contains("pauseView.onSlide = { t -> slideBarsUp = true;") && host.contains("targetRadial.onSlide = { t -> slideBarsUp = false;"))
        assertTrue("the waiting screen's Exit game is the clean disconnect while streaming",
            host.contains("StartMenu.onExit = { (SetupBridge.endStream ?: SetupBridge.exitStream ?: SetupBridge.exitGame)?.invoke() }"))
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
