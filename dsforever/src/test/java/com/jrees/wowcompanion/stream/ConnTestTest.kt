package com.jrees.wowcompanion.stream

import com.jrees.wowcompanion.stream.ConnTest.State
import com.jrees.wowcompanion.stream.ConnTest.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Test connection" (the user's pick 2026-11-17: "Yes, build it"): the step runner with fake probes, each step's judgement, the
 * report line and the PC's answer on the bridge. Off-device.
 */
class ConnTestTest {
    private fun fakes(vararg r: ConnTest.Result): (Step) -> ConnTest.Result {
        val m = r.associateBy { it.step }
        return { s -> m[s] ?: ConnTest.pass(s, "fake") }
    }

    @Test fun everyStepInOrder_whenAllPass() {
        val heard = ArrayList<Step>()
        val rs = ConnTest.run(fakes(), onStep = { heard += it.step })
        assertEquals(Step.values().toList(), heard)
        assertTrue(rs.all { it.state == State.PASS })
        assertNull(ConnTest.firstFailure(rs))
    }

    @Test fun todaysPc_stopsAtRtsp_withTheFix() {
        // 2026-11-17: Windows refused RTSP 53010 at boot, HTTP answered
        val asked = ArrayList<Step>()
        val probe = fakes(ConnTest.rtsp(53010, Diagnosis.Port.TIMEOUT, live = false))
        val rs = ConnTest.run({ s -> asked += s; probe(s) })
        assertEquals(listOf(Step.PC_FOUND, Step.PAIRED, Step.WEB_PORTS, Step.RTSP), asked)     // nothing after the failure is probed
        val bad = ConnTest.firstFailure(rs)!!
        assertEquals(Step.RTSP, bad.step)
        assertEquals("No answer on port 53010", bad.line)
        assertEquals(ConnTest.FIX_RESTART, bad.fix)
        assertEquals(listOf(State.NOT_RUN, State.NOT_RUN, State.NOT_RUN, State.NOT_RUN), rs.drop(4).map { it.state })
    }

    @Test fun aSkipDoesNotStop_aThrowIsAFailure_cancelStops() {
        val rs = ConnTest.run(fakes(ConnTest.controller("unknown")))
        assertEquals(State.SKIP, rs.first { it.step == Step.CONTROLLER }.state)
        assertEquals(State.PASS, rs.last().state)
        val thrown = ConnTest.run({ s -> if (s == Step.WEB_PORTS) throw IllegalStateException("boom") else ConnTest.pass(s) })
        assertEquals(Step.WEB_PORTS, ConnTest.firstFailure(thrown)!!.step)
        assertTrue(ConnTest.firstFailure(thrown)!!.line.contains("boom"))
        var n = 0
        val cut = ConnTest.run({ s -> n++; ConnTest.pass(s) }, cancelled = { n >= 2 })
        assertEquals(2, n)
        assertEquals(State.NOT_RUN, cut[2].state)
    }

    @Test fun pcFoundAndPaired() {
        assertEquals(State.PASS, ConnTest.pcFound(true, true, null).state)
        assertEquals(State.SKIP, ConnTest.pcFound(false, false, null).state)                   // a plain Sunshine: no bridge to find
        val off = ConnTest.pcFound(true, false, Diagnosis.Reason.PC_OFF)
        assertEquals(Diagnosis.Reason.PC_OFF.title, off.line)
        assertEquals(Diagnosis.Reason.PC_OFF.detail, off.fix)
        assertEquals(ConnTest.FIX_RESTART, ConnTest.pcFound(true, false, Diagnosis.Reason.BRIDGE_ONLY_DOWN).fix)
        assertEquals(ConnTest.FIX_PAIR, ConnTest.paired(true, false, authLost = true).fix)
        assertEquals(State.PASS, ConnTest.paired(true, true, false).state)
    }

    @Test fun webPorts() {
        assertEquals(State.PASS, ConnTest.webPorts(52989, 52984, null, null, "1").state)
        assertEquals("No answer on port 52989 (connect timed out)", ConnTest.webPorts(52989, 52984, "connect timed out", null, null).line)
        assertEquals(ConnTest.FIX_PAIR, ConnTest.webPorts(52989, 52984, null, "401", null).fix)
        assertEquals(ConnTest.FIX_PAIR, ConnTest.webPorts(52989, 52984, null, null, "0").fix)
        assertEquals(ConnTest.FIX_RESTART, ConnTest.webPorts(0, 0, null, null, null).fix)         // Desktop never said its port
    }

    @Test fun rtspAndTheTestStream() {
        assertEquals(State.PASS, ConnTest.rtsp(53010, Diagnosis.Port.OPEN, false).state)
        assertEquals(State.PASS, ConnTest.rtsp(53010, Diagnosis.Port.NOT_TRIED, live = true).state)
        assertEquals("Refused on port 53010", ConnTest.rtsp(53010, Diagnosis.Port.REFUSED, false).line)
        assertEquals(State.PASS, ConnTest.stream(true, 0, "", 180, 52989).state)
        val noVideo = ConnTest.stream(true, 0, "", 0, 52989)
        assertEquals("No video decoded", noVideo.line)
        assertTrue(noVideo.fix.contains("UDP 52998-53000"))
        assertEquals("RTSP handshake failed (error 110)", ConnTest.stream(false, 110, "RTSP handshake", 0, 52989).line)
        val enc = ConnTest.stream(false, -1, "video stream establishment", 0, 52989, "No video encoder")
        assertTrue(enc.line.endsWith(": No video encoder"))
    }

    @Test fun controllerScreenAnd120() {
        assertEquals(State.PASS, ConnTest.controller("vigem").state)
        assertEquals(ConnTest.FIX_VIGEM, ConnTest.controller("missing").fix)
        assertEquals(State.SKIP, ConnTest.controller("").state)                                // an older Desktop
        assertEquals(State.PASS, ConnTest.screen("own", "Own virtual screen 1920x1080@120").state)
        assertEquals(State.PASS, ConnTest.screen("real", "").state)
        assertEquals(ConnTest.FIX_SCREEN, ConnTest.screen("none", "").fix)
        assertEquals(State.SKIP, ConnTest.screen("", "").state)
        assertEquals(State.PASS, ConnTest.fps120(120, true, 120).state)
        assertEquals(ConnTest.FIX_60, ConnTest.fps120(120, true, 60).fix)                       // set to 120, the PC's screen can't
        assertEquals(State.SKIP, ConnTest.fps120(60, true, 60).state)                          // not asked for: only said
        assertEquals(State.FAIL, ConnTest.fps120(120, false, 144).state)
        assertEquals(State.SKIP, ConnTest.fps120(120, true, 0).state)
    }

    @Test fun theReportLine_hasNoAddresses() {
        val rs = ConnTest.run(fakes(ConnTest.pcFound(true, true, null), ConnTest.fail(Step.WEB_PORTS, "No answer from 192.168.1.20 or fd7a:115c:a1e0::5", "x")))
        val s = ConnTest.summary("2026-11-17 14:05", rs, listOf("ports ok (all 6 ready)", "rtsp FAILED (127.0.0.1:53010)"))
        assertTrue(s, s.startsWith("Test connection 2026-11-17 14:05: stopped at Web ports"))
        assertTrue(s, s.contains("192.168.x.x"))
        assertFalse(s, s.contains("192.168.1.20"))
        assertFalse(s, s.contains("a1e0"))
        assertTrue(s, s.contains("  PC rtsp FAILED (127.0.x.x:53010)"))
        assertTrue(s, s.contains("RTSP handshake: not run"))
    }

    @Test fun thePcsAnswerOnTheBridge() {
        val m = BridgeProtocol.parse("""{"t":"pc_test_result","steps":[{"id":"ports","state":"pass","line":"All 6 ready"},""" +
            """{"id":"encoder","state":"fail","line":"No video encoder","fix":"Update the graphics driver."}],"screen":"own",""" +
            """"screen_line":"Own virtual screen","screen_hz":120,"pad":"vigem"}""") as BridgeMsg.PcTest
        assertEquals(2, m.steps.size)
        assertEquals("No video encoder", m.failed!!.line)
        assertEquals("own", m.screen); assertEquals(120, m.screenHz); assertEquals("vigem", m.pad)
        assertEquals(listOf("ports pass (All 6 ready)", "encoder FAILED (No video encoder)"), m.lines)
        assertEquals("""{"t":"pc_test"}""", BridgeProtocol.pcTest())
    }

    @Test fun ipv6ShortForms_areCutToo() {
        // reviewers 2026-11-17: fe80::1 (two colons) went into the report whole
        assertEquals("fe80:x, ::x, 2001:x, fe80:x and 192.168.x.x at 12:30:45",
            ConnTest.scrub("fe80::1, ::1, 2001:db8::5, fe80::1c2:3%wlan0 and 192.168.1.20 at 12:30:45"))
        assertEquals("ports ok (52984, 52989)", ConnTest.scrub("ports ok (52984, 52989)"))
        assertEquals("std::string", ConnTest.scrub("std::string"))
    }

    @Test fun failedAt_hasATranslatableHead() {
        // reviewers 2026-11-17: "Failed at <stage> (error 5)" was cut at the bracket, so its head carried the stage and was never translated
        val line = ConnTest.stream(false, 5, "video stream establishment", 0, 47989).line
        assertEquals("Failed at" to " video stream establishment (error 5)", ConnTest.splitHead(line))
        assertEquals("No answer on port" to " 53010", ConnTest.splitHead("No answer on port 53010"))
        assertEquals("Didn't start" to " (error 5)", ConnTest.splitHead("Didn't start (error 5)"))
        assertEquals("Bridge connected" to "", ConnTest.splitHead("Bridge connected"))
    }

    @Test fun retryAsksDesktopToTryItsScreenAgain() {
        assertEquals("""{"t":"screen_retry"}""", BridgeProtocol.screenRetry())
    }
}
