package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** b-danger-layer: QuestieDB's toc read by the app, the danger grid, the route bent round it */
class DangerLayerTest {
    /** a CBOR map {area: [[x, y], ...]} as QuestieDB writes spawns (doubles) */
    private fun spawnsCbor(area: Int, pts: List<Pair<Double, Double>>): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0xA1); o.write(0x18); o.write(area)                         // map(1), key uint8
        o.write(0x80 + pts.size)
        for ((x, y) in pts) {
            o.write(0x82)
            for (v in listOf(x, y)) { o.write(0xFB); val b = java.lang.Double.doubleToLongBits(v); for (s in 7 downTo 0) o.write((b ushr (8 * s)).toInt() and 0xFF) }
        }
        return o.toByteArray()
    }
    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)

    @Test fun theTocReadsAsQuestieDBWritesIt() {
        // the real rows of QuestieDB-Forever 1.0.5: Young Wolf (299, "AH": a neutral, left out) and Flesh Eater (3, hostile to both)
        val spawns3 = b64(spawnsCbor(40, listOf(50.0 to 25.0, 60.0 to 30.0)))
        val toc = """
            ## Title: QuestieDB
            ## X-Npc-299-S: pwFKWW91bmcgV29sZgQBBQEJDAwYIA1CQUhBcBhA
            ## X-Npc-299-7: ${b64(spawnsCbor(12, listOf(40.0 to 40.0)))}
            ## X-Npc-3-S: pgFLRmxlc2ggRWF0ZXIEGBgFGBkJCgwVQXAYQA==
            ## X-Npc-3-7: ~2~
            ## X-Npc-3-7-1: ${spawns3.substring(0, 20)}
            ## X-Npc-3-7-2: ${spawns3.substring(20)}
            ## X-Quest-1-S: AAAA
        """.trimIndent()
        val s = QuestieNpcs.read(toc.byteInputStream())
        assertEquals(2, s.size)
        assertTrue(s.all { it.name == "Flesh Eater" && it.area == 40 && it.hostile == 3 && it.minLv == 24 && it.maxLv == 25 })
        assertEquals(0.5f, s[0].x, 1e-6f); assertEquals(0.25f, s[0].y, 1e-6f)
    }

    @Test fun areaOneComesAsAnArray() {
        // Rockjaw Trogg (707), Dun Morogh only: Lua writes {[1] = {...}} as a sequence, so its spawns are a CBOR array, not a map
        val withMap = spawnsCbor(40, listOf(30.0 to 75.0))
        val asArray = byteArrayOf(0x81.toByte()) + withMap.copyOfRange(3, withMap.size)        // array(1) in place of map(1) + key
        val toc = """
            ## X-Npc-3-S: pgFLRmxlc2ggRWF0ZXIEGBgFGBkJCgwVQXAYQA==
            ## X-Npc-3-7: ${b64(asArray)}
        """.trimIndent()
        val s = QuestieNpcs.read(toc.byteInputStream())
        assertEquals(1, s.size)
        assertEquals(1, s[0].area); assertEquals(0.3f, s[0].x, 1e-6f); assertEquals(0.75f, s[0].y, 1e-6f)
    }

    @Test fun greyCountsNothingEliteThreeTimes() {
        assertEquals(0f, DangerGrid.weight(5, 6, false, 20))                   // grey to a level 20 (grey line 13)
        assertEquals(0.3f, DangerGrid.weight(16, 17, false, 20))               // green
        assertEquals(0.7f, DangerGrid.weight(20, 21, false, 20))               // yellow
        assertEquals(1.0f, DangerGrid.weight(23, 24, false, 20))               // orange
        assertEquals(1.5f, DangerGrid.weight(25, 25, false, 20))               // red
        assertEquals(2.1f, DangerGrid.weight(20, 21, true, 20), 1e-6f)         // a yellow elite: x3
        assertEquals(13, DangerGrid.greyLevel(20)); assertEquals(0, DangerGrid.greyLevel(5))
    }

    private fun camp(cx: Float, cy: Float, n: Int, lv: Int, hostile: Int = 3) =
        List(n) { i -> QuestieNpcs.Spawn(1, cx + (i % 4) * 0.004f, cy + (i / 4) * 0.004f, lv, lv, false, hostile, "Murloc") }

    @Test fun theLineBendsRoundACampAndIsStraightOtherwise() {
        // a 2000 x 2000 yd map; a level-13 camp across the straight line; the player level 12
        val g = DangerGrid.build(camp(0.5f, 0.5f, 16, 13), TransportRoutes.ALLIANCE, 12, 2000.0, 2000.0)
        val from = doubleArrayOf(0.2, 0.5); val to = doubleArrayOf(0.8, 0.5)
        val bent = g.route(from, to, DangerGrid.strength("high"))
        assertTrue("bent: ${bent.size} points", bent.size > 2)
        assertTrue(g.cost(bent, 5.0) < g.cost(listOf(from, to), 5.0))
        for (i in 1 until bent.size) assertTrue(g.peak(bent[i - 1], bent[i]) < 1.0f)           // it does not walk through the camp
        assertTrue(DangerGrid.yards(bent, 2000.0, 2000.0) < 1200.0 * 1.6)                    // a detour, not a tour
        // nothing on the way: straight; Off: straight
        assertEquals(2, g.route(doubleArrayOf(0.2, 0.1), doubleArrayOf(0.8, 0.1), 5.0).size)
        assertEquals(2, g.route(from, to, DangerGrid.strength("off")).size)
        // grey to a level 30: no cell danger (no tint, the cell router straight) - only a little at a point among them (test70: greys
        // still aggro, close in), never more than GREY_CAP, nothing a few yards off
        val gg = DangerGrid.build(camp(0.5f, 0.5f, 16, 13), 0, 30, 2000.0, 2000.0)
        assertEquals(0f, gg.max)
        assertEquals(2, gg.route(from, to, DangerGrid.strength("high")).size)
        val among = gg.at(0.5 + 0.004, 0.5 + 0.004)
        assertTrue("among the greys $among", among > 0f && among <= DangerGrid.GREY_CAP)
        assertEquals(0f, gg.at(0.5 - 0.01, 0.5 - 0.01))                                       // 28 yd off
        // a level unknown (0) is not grey
        assertEquals(0f, DangerGrid.build(camp(0.5f, 0.5f, 16, 0), 0, 30, 2000.0, 2000.0).at(0.5 + 0.004, 0.5 + 0.004))
        // friendly to this character's side ("A": hostile to the Horde only): no danger for an Alliance one
        assertEquals(0f, DangerGrid.build(camp(0.5f, 0.5f, 16, 13, hostile = TransportRoutes.HORDE), TransportRoutes.ALLIANCE, 12, 2000.0, 2000.0).max)
    }

    @Test fun theGoalAmongMobsIsStillReached() {
        // the quest's own mobs at the goal: the last 60 yd are free, the line goes straight in
        val g = DangerGrid.build(camp(0.8f, 0.5f, 8, 13), 0, 12, 2000.0, 2000.0)
        val r = g.route(doubleArrayOf(0.2, 0.5), doubleArrayOf(0.8, 0.5), 5.0)
        assertEquals(2, r.size)
    }

    /** test45 ("At every bend I want a checkpoint flag which the arrow leads to"): a REACHED bend is passed, the next leads (test68:
     *  reached = within DangerGrid.PASS_YD, 5 yd - no longer 25) */
    @Test fun aReachedCheckpointIsPassed() {
        val leg = listOf(doubleArrayOf(0.100, 0.5), doubleArrayOf(0.1015, 0.5), doubleArrayOf(0.3, 0.6), doubleArrayOf(0.8, 0.5))
        val p = DangerGrid.passed(leg, 2000.0, 2000.0)!!                      // the first bend 3 yd away: reached
        assertEquals(3, p.size); assertEquals(0.3, p[1][0], 1e-9)
        assertEquals(null, DangerGrid.passed(listOf(leg[0], doubleArrayOf(0.1015, 0.5), leg[3]), 2000.0, 2000.0))   // none left
        // 16 yd short (the old rule's pass): not yet - the marker stays until it is reached
        assertEquals(4, DangerGrid.passed(listOf(leg[0], doubleArrayOf(0.108, 0.5), leg[2], leg[3]), 2000.0, 2000.0)!!.size)
        assertEquals(4, DangerGrid.passed(listOf(leg[0], doubleArrayOf(0.2, 0.5), leg[2], leg[3]), 2000.0, 2000.0)!!.size)
    }

    /** test54 ("I sometimes walk past them by accident and it makes me go all the way back"): passed when past, or skirted */
    @Test fun aCheckpointWalkedPastOrSkirtedIsPassed() {
        val ax = 2000.0; val ay = 2000.0
        val c = doubleArrayOf(0.5, 0.5); val n = doubleArrayOf(0.5, 0.3)          // the bend, then north 400 yd
        // overshot: 40 yd east of the bend and 20 yd on toward the next (past the line across the leg)
        assertTrue(DangerGrid.isPassed(doubleArrayOf(0.52, 0.49), c, n, ax, ay))
        // skirted: 50 yd west of it, 30 yd nearer the next than the bend is
        assertTrue(DangerGrid.isPassed(doubleArrayOf(0.475, 0.485), c, n, ax, ay))
        // coming up to a sharp corner from the west, 30 yd short and a few yards north of the line: NOT passed
        assertFalse(DangerGrid.isPassed(doubleArrayOf(0.485, 0.498), c, n, ax, ay))
        // test68: 24 yd short is no longer passed; reached (3 yd, inside PASS_YD 4): passed
        assertFalse(DangerGrid.isPassed(doubleArrayOf(0.488, 0.5), c, n, ax, ay))
        assertTrue(DangerGrid.isPassed(doubleArrayOf(0.4985, 0.5), c, n, ax, ay))          // 3 yd
        // a runner stepping over it between two position reads (7 yd apart: 3 yd short, then 4 yd on): the next read is past its line
        assertTrue(DangerGrid.isPassed(doubleArrayOf(0.5, 0.497), c, n, ax, ay))
        // walking 4 yd beside it, then 6 yd on past its line: passed
        assertTrue(DangerGrid.isPassed(doubleArrayOf(0.502, 0.497), c, n, ax, ay))
        // 150 yd off to the side and not yet up to it: not passed; the same 100 yd on past its line: passed (progress along the leg)
        assertFalse(DangerGrid.isPassed(doubleArrayOf(0.425, 0.51), c, n, ax, ay))
        assertTrue(DangerGrid.isPassed(doubleArrayOf(0.425, 0.45), c, n, ax, ay))
    }
}
