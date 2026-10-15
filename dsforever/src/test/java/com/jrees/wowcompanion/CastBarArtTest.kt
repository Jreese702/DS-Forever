package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** f-cast-bar-art: the game's casting bar timings (castingbarframe.xml), off-device */
class CastBarArtTest {
    private val e = 1e-4f

    @Test fun aFinishHoldsThenFadesOverThreeTenths() {
        assertEquals(1f, CastFx.barAlpha(false, 0), e)
        assertEquals(1f, CastFx.barAlpha(false, 200), e)
        assertEquals(0.5f, CastFx.barAlpha(false, 350), e)
        assertEquals(0f, CastFx.barAlpha(false, CastFx.FINISH_MS), e)
    }

    @Test fun anInterruptHoldsASecond() {
        assertEquals(1f, CastFx.barAlpha(true, 1000), e)
        assertEquals(0f, CastFx.barAlpha(true, CastFx.FAIL_MS), e)
        assertEquals(1f, CastFx.interruptGlow(0), e)
        assertEquals(0.5f, CastFx.interruptGlow(500), e)
    }

    @Test fun theFlashComesInOverTwoTenths() {
        assertEquals(0f, CastFx.flashAlpha(0), e); assertEquals(0.5f, CastFx.flashAlpha(100), e); assertEquals(1f, CastFx.flashAlpha(400), e)
    }

    @Test fun theGlowLineSweepsUpThroughTheBar() {
        assertEquals(-100f, CastFx.standardY(CastFx.GLOW_LINE, 0), e)
        assertEquals(10f, CastFx.standardY(CastFx.GLOW_LINE, 250), e)
        assertEquals(120f, CastFx.standardY(CastFx.GLOW_LINE, 500), e)
        assertEquals(-100f, CastFx.standardY(CastFx.GLOW_LINE, CastFx.LOOP_MS), e)     // REPEAT
    }

    @Test fun theFlakesRiseOnTheGamesCurves() {
        assertEquals(-25f, CastFx.standardY(CastFx.FLAKES1, 0), e)
        assertEquals(75f, CastFx.standardY(CastFx.FLAKES1, 500), e)
        assertEquals(-25f + 25f, CastFx.standardY(CastFx.FLAKES1, 250), e)              // ease-in: a quarter of the way at half time
        assertEquals(-45f + 67.5f, CastFx.standardY(CastFx.FLAKES1 + 1, 250), e)        // ease-out: three quarters
        assertEquals(-25f, CastFx.standardY(CastFx.FLAKES1 + 2, 250), e)                // Flakes03 waits 0.25 s
        assertEquals(0f, CastFx.standardY(CastFx.FLAKES1 + 2, 749), 0.2f)
    }

    @Test fun theShakeReturnsHome() {
        assertEquals(0f, CastFx.shake(0)[0], e)
        assertEquals(-1f, CastFx.shake(160)[0], e)
        assertEquals(0f, CastFx.shake(400)[0], e); assertEquals(0f, CastFx.shake(400)[1], e)
    }

    @Test fun theChannelSparklesFlyAndTurn() {
        val s = CastFx.sparkle(0, 500)
        assertEquals(40f, s[0], e); assertEquals(45f, s[1], e); assertEquals(1f, s[2], e)
        assertEquals(0f, CastFx.sparkle(1, 100)[0], e)
        assertEquals(-45f, CastFx.sparkle(1, 600)[1], e)
        assertEquals(0.5f to 4f, CastFx.channelBase(500).let { (a, x) -> Math.round(a * 10) / 10f to x })
    }

    @Test fun theFlakesRepeatOncePerGameBarWidth() {
        assertEquals(1, CastFx.copies(100f, 1f))
        assertEquals(6, CastFx.copies(1240f, 14f / 13f))
    }

    @Test fun everyCastBarPieceInTheRecipeIsOptional() {
        val f = listOf("companion/app/src/main/assets/harvest/cast.json", "../companion/app/src/main/assets/harvest/cast.json")
            .map { java.io.File(it) }.first { it.exists() }
        // plain text (org.json is Android's stub in unit tests): each entry is {"names":[...],"purpose":"...",...}
        val text = f.readText()
        val entry = Regex("\"([a-z0-9_]+)\":\\{\"names\":[^}]*\\}").findAll(text).associate { it.groupValues[1] to it.value }
        val bar = entry.keys.filter { it.startsWith("castbar_") }
        assertTrue("the casting bar's pieces are asked for (${bar.size})", bar.size >= 20)
        for (k in bar) assertTrue("$k optional", entry.getValue(k).contains("\"optional\":true"))
        for (k in entry.keys - bar.toSet()) assertTrue("$k stays required", !entry.getValue(k).contains("\"optional\""))
    }
}

/** b-cast-any (the user on test9: no bar for a container or the Hearthstone): at rest the line is the cast bar alone (castOnly = swing off) */
class CastAnyTest {
    @Test fun atRestACastStillShows() {
        val p = SwingLine.parts(false, 1_500.0, 1_000.0, 11_000.0, 1_000.0, 2_000.0, 0.5, false, false, -1L, CastFx.FINISH_MS)
        assertTrue(p.cast); assertEquals(-1.0, p.swing, 0.0)
    }
    @Test fun atRestNoSwingShows() {
        val p = SwingLine.parts(false, 1_500.0, Double.NaN, Double.NaN, 1_000.0, 2_000.0, 0.5, false, false, -1L, CastFx.FINISH_MS)
        assertTrue(!p.cast); assertEquals(-1.0, p.swing, 0.0)
    }
    @Test fun atRestTheFinishStillPlaysButNotTheSwingsFlash() {
        assertTrue(SwingLine.parts(false, 0.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0.5, true, false, 100L, CastFx.FINISH_MS).flash)
        assertTrue(!SwingLine.parts(false, 0.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0.5, true, true, 100L, 250L).flash)
    }
}
