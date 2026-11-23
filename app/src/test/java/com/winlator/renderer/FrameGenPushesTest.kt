package com.winlator.renderer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue 153: a frame-generation settings push that changes nothing never reaches the native renderer (no GPU wait, no rebuild). */
class FrameGenPushesTest {
    @Test fun theSameShadersAreSentOnce() {
        val p = FrameGenPushes()
        assertTrue(p.shaders("/cache/a"))
        assertFalse(p.shaders("/cache/a"))
        assertFalse(p.shaders("/cache/a"))
        assertTrue(p.shaders("/cache/b"))
        assertFalse(p.shaders("/cache/b"))
        assertTrue(p.shaders(null))
        assertFalse(p.shaders(null))
    }

    @Test fun theSameModeRefreshAndSwitchAreSentOnce() {
        val p = FrameGenPushes()
        assertTrue(p.mode(2, 0, 70)); assertFalse(p.mode(2, 0, 70))
        assertTrue(p.mode(3, 0, 70)); assertTrue(p.mode(3, 0, 50)); assertTrue(p.mode(3, 60, 50)); assertFalse(p.mode(3, 60, 50))
        assertTrue(p.refreshRate(60f)); assertFalse(p.refreshRate(60f)); assertTrue(p.refreshRate(120f))
        assertTrue(p.enabled(false)); assertFalse(p.enabled(false)); assertTrue(p.enabled(true)); assertFalse(p.enabled(true))
    }

    @Test fun theFirstPushAlwaysGoesEvenAtTheDefaults() {
        // a fresh native renderer has its own defaults; Java never assumes them
        val p = FrameGenPushes()
        assertTrue(p.enabled(false))
        assertTrue(p.refreshRate(0f))
        assertTrue(p.mode(0, 0, 0))
    }

    @Test fun aNewRendererIsSentEverythingAgain() {
        val p = FrameGenPushes()
        p.shaders("/cache/a"); p.mode(2, 0, 70); p.refreshRate(60f); p.enabled(true)
        p.forget()
        assertTrue(p.shaders("/cache/a"))
        assertTrue(p.mode(2, 0, 70))
        assertTrue(p.refreshRate(60f))
        assertTrue(p.enabled(true))
        assertFalse(p.shaders("/cache/a"))
    }

    @Test fun aChangedValueThenTheOldOneIsSentBothTimes() {
        val p = FrameGenPushes()
        assertTrue(p.shaders("/cache/a")); assertTrue(p.shaders("/cache/b")); assertTrue(p.shaders("/cache/a"))
        assertTrue(p.enabled(true)); assertTrue(p.enabled(false)); assertTrue(p.enabled(true))
    }
}
