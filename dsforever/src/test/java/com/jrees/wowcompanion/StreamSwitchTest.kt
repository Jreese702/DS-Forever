package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Streaming (S2): LogSource and TapSink pick the right path by mode, and Local's path is unchanged. Off-device. */
class StreamSwitchTest {
    @After fun reset() { PlayModes.current = PlayMode.LOCAL; PlayModes.bridgeLog = null; PlayModes.bridgeKey = ""; PlayModes.onChanged = null; InputSink.sink = null }

    private class FakeSource(override val kind: String) : LogSource {
        var started = false
        override fun start() { started = true }
        override fun stop() {}
        override fun gapCount() = 0
    }

    private class XSink : InputSink.Sink {
        val keys = ArrayList<Pair<Int, Boolean>>(); val taps = ArrayList<Pair<Float, Float>>()
        override fun key(xKeycode: Int, down: Boolean) { keys.add(xKeycode to down) }
        override fun tap(fx: Float, fy: Float) { taps.add(fx to fy) }
    }

    private class StreamSinkFake : TapSink {
        override val route = "stream"
        val keys = ArrayList<Int>()
        override fun key(xKeycode: Int, down: Boolean) { keys.add(xKeycode) }
        override fun tap(fx: Float, fy: Float) {}
    }

    private val noMsg: (JSONObject) -> Unit = {}

    @Test fun localModeReadsTheLocalLogEvenWithABridge() {
        val s = PlayModes.pick(PlayMode.LOCAL, { _, _ -> FakeSource("bridge") }, noMsg, {}) { FakeSource("local") }
        assertEquals("local", s.kind)
    }

    @Test fun streamModeReadsTheBridge() {
        val s = PlayModes.pick(PlayMode.STREAM, { _, _ -> FakeSource("bridge") }, noMsg, {}) { FakeSource("local") }
        assertEquals("bridge", s.kind)
    }

    @Test fun streamModeWithoutAPairedPcSaysSoAndKeepsTheLocalLog() {
        val said = ArrayList<String>()
        val s = PlayModes.pick(PlayMode.STREAM, null, noMsg, { said.add(it) }) { FakeSource("local") }
        assertEquals("local", s.kind)
        assertTrue(said.single().contains("no paired PC"))
    }

    @Test fun setNotifiesTheCompanionOnlyOnAChange() {
        var n = 0
        PlayModes.onChanged = { n++ }
        val f: (onMessage: (JSONObject) -> Unit, onStatus: (String) -> Unit) -> LogSource = { _, _ -> FakeSource("bridge") }
        PlayModes.set(PlayMode.STREAM, f, "pc1")
        PlayModes.set(PlayMode.STREAM, f, "pc1")
        assertEquals(1, n)
        PlayModes.set(PlayMode.STREAM, f, "pc2")
        assertEquals(2, n)
        PlayModes.set(PlayMode.LOCAL, null, "")
        assertEquals(3, n)
        assertEquals(PlayMode.LOCAL, PlayModes.current)
    }

    @Test fun localTapsGoToTheContainerUnchanged() {
        val x = XSink()
        assertEquals("container", TapSinks.wire(PlayMode.LOCAL, x, StreamSinkFake()))
        val s = InputSink.sink!!
        assertTrue(s is ContainerKeys)
        s.key(38, true); s.key(38, false); s.tap(0.5f, 0.25f)
        assertEquals(listOf(38 to true, 38 to false), x.keys)
        assertEquals(listOf(0.5f to 0.25f), x.taps)
        // the default methods pass through too (trackpad): no crash, nothing invented
        s.moveBy(3, 4); s.showPointer(true)
    }

    @Test fun streamTapsNeverFallBackToTheContainer() {
        val x = XSink(); val st = StreamSinkFake()
        assertEquals("stream", TapSinks.wire(PlayMode.STREAM, x, st))
        assertSame(st, InputSink.sink)
        assertEquals("none", TapSinks.wire(PlayMode.STREAM, x, null))
        assertNull(InputSink.sink)
    }

    @Test fun aContainerSinkIsNotWrappedTwice() {
        val c = ContainerKeys(XSink())
        assertSame(c, TapSinks.select(PlayMode.LOCAL, c, null))
    }

    @Test fun theKeyChannelLineIsStillRefusedForAltAndFKeysWhateverTheSink() {
        val st = StreamSinkFake()
        TapSinks.wire(PlayMode.STREAM, null, st)
        assertEquals(1, InputSink.play("wckeys CTRL-ALT-SHIFT F4").exit)
        assertEquals(1, InputSink.play("wckeys CTRL-SHIFT F5 A").exit)
        assertTrue(st.keys.isEmpty())
        assertEquals(0, InputSink.play("wckeys CTRL-SHIFT HOME A").exit)
        assertEquals(listOf(37, 50, 110, 110, 38, 38, 50, 37), st.keys)
    }
}
