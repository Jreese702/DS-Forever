package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** SafePath's device plan, section 4: one character's danger on one zone (DangerInputs), the device's and the harness's the same */
class DangerInputsTest {
    private val A = TransportRoutes.ALLIANCE
    private val H = TransportRoutes.HORDE
    /** a 1000 x 1000 yd zone: the map's (u, v) is world (1000 - 1000 v, 1000 - 1000 u) */
    private val rect = MapHarvest.Rect(0, 1000.0, 0.0, 1000.0, 0.0)

    private fun sp(u: Double, v: Double, hostile: Int, name: String = "Mob", lv: Int = 10, elite: Boolean = false) =
        QuestieNpcs.Spawn(1, u.toFloat(), v.toFloat(), lv, lv, elite, hostile, name)
    /** [n] spawns in a ring of 15 yd round the map's (u, v) */
    private fun ring(u: Double, v: Double, n: Int, hostile: Int, name: String = "Mob", lv: Int = 10) =
        (0 until n).map { i -> val a = 2 * Math.PI * i / n; sp(u + 0.015 * Math.cos(a), v + 0.015 * Math.sin(a), hostile, name, lv) }
    private fun at(d: DangerInputs.Danger, u: Double, v: Double) = d.grid.at(u, v)
    private fun mobsAt(d: DangerInputs.Danger, u: Double, v: Double) = DangerInputs.world(rect, u.toFloat(), v.toFloat()).let { d.mobs(it[0], it[1]) }

    @Test fun aTownsFactionlessPeopleTakeItsSide() {
        // an Alliance town (its people friendly to the Alliance: hostile to the Horde) with a faction-less guard and a faction-less
        // innkeeper with no level; a wild beast with a level, faction-less too, in the town's ring
        val town = ring(0.2, 0.2, 6, H, "Villager", 30)
        val guard = sp(0.2, 0.2, 3, "Town Guard", 30)
        val keeper = sp(0.21, 0.2, 3, "Innkeeper", 0)
        val beast = sp(0.19, 0.2, 3, "Wolf", 9)
        val far = sp(0.8, 0.8, 3, "Lone Guard", 30)                 // no tagged spawn within 150 yd: stays hostile to both
        val s = DangerInputs.sided(town + guard + keeper + beast + far, rect, A, DangerInputs.Rules.NONE)
        assertEquals(H, s[6].hostile); assertEquals(H, s[7].hostile)
        assertEquals(3, s[8].hostile); assertEquals(3, s[9].hostile)
        // the Alliance character: the town counts nothing but its wolf; the Horde one: the whole town
        val a = DangerInputs.grid(s, rect, A, 30)
        val h = DangerInputs.grid(s, rect, H, 30)
        assertEquals(DangerInputs.hostile(s, A).map { it.name }, listOf("Wolf", "Lone Guard"))
        assertTrue(at(h, 0.2, 0.2) > at(a, 0.2, 0.2))
    }

    @Test fun friendlyNamesAreNoDanger() {
        val people = ring(0.5, 0.5, 8, 3, "Windwalker Scout")
        val rules = DangerInputs.Rules(friendlyNames = listOf(Regex("^Windwalker")))
        for (mine in listOf(A, H)) {
            assertTrue(at(DangerInputs.build(people, rect, mine, 10), 0.5, 0.5) > 0f)
            val d = DangerInputs.build(people, rect, mine, 10, rules)
            assertEquals(0f, at(d, 0.5, 0.5), 0f)
            // made the character's side's: hostile to the other side only (they count as a town's people)
            assertTrue(d.sided.all { it.hostile == DangerInputs.foeOf(mine) })
        }
        // a name the rule doesn't match still counts
        assertTrue(at(DangerInputs.build(ring(0.5, 0.5, 8, 3, "Bandit"), rect, A, 10, rules), 0.5, 0.5) > 0f)
    }

    @Test fun placeholderLevelsAreFriendly() {
        val placeholders = ring(0.5, 0.5, 8, 3, "Keeper", 9999)
        val rules = DangerInputs.Rules(friendlyMinLevel = 100)
        // without the rule a level 9999 is red to anyone; with it, no mob
        assertTrue(at(DangerInputs.build(placeholders, rect, H, 10), 0.5, 0.5) > 0f)
        assertEquals(0f, at(DangerInputs.build(placeholders, rect, H, 10, rules), 0.5, 0.5), 0f)
        // below the line: still mobs
        assertTrue(at(DangerInputs.build(ring(0.5, 0.5, 8, 3, "Keeper", 99), rect, H, 10, rules), 0.5, 0.5) > 0f)
        // the user (2026-10-19, "Yes, other faction = danger"): a placeholder tagged for the other side (hostile to me only: Dalaran's
        // guards for a Horde character) stays danger; the exemption is for NPCs tagged for neither faction
        val tagged = ring(0.5, 0.5, 8, H, "Keeper", 9999)
        assertTrue(at(DangerInputs.build(tagged, rect, H, 10, rules), 0.5, 0.5) > 0f)
        assertTrue(DangerInputs.sided(tagged, rect, H, rules).all { it.hostile == H })
    }

    @Test fun theTypicalDangerIsTakenOff() {
        // mobs everywhere (a spawn every 50 yd), a thick camp in the middle
        val all = ArrayList<QuestieNpcs.Spawn>()
        for (j in 1..19) for (i in 1..19) all.add(sp(i / 20.0, j / 20.0, 3))
        all.addAll(ring(0.5, 0.5, 12, 3, "Camp", 14))
        val d = DangerInputs.build(all, rect, A, 10, inZone = { _, _ -> true })
        assertTrue(d.typical > 0f)
        assertEquals(maxOf(0f, at(d, 0.25, 0.25) - d.typical), mobsAt(d, 0.25, 0.25), 1e-6f)
        assertTrue(mobsAt(d, 0.5, 0.5) > 1f)
        // the median over the zone's own cells only: none in the zone, none taken off
        assertEquals(0f, DangerInputs.build(all, rect, A, 10, inZone = { _, _ -> false }).typical, 0f)
        assertEquals(0f, DangerInputs.build(all, rect, A, 10).typical, 0f)
    }

    @Test fun wadingAndRoomsCostExtra() {
        val d = DangerInputs.build(emptyList(), rect, A, 10)
        val wet: (Double, Double) -> Boolean = { x, _ -> x < 500 }
        val room: (Double, Double) -> Boolean = { _, y -> y < 500 }
        val danger = d.danger(wet, room); val calm = d.calm(wet, room)
        assertEquals(0f, danger(700.0, 700.0), 0f)
        assertEquals(DangerInputs.WADE_DANGER, danger(300.0, 700.0), 0f)
        assertEquals(DangerInputs.BUILDING_DANGER, danger(700.0, 300.0), 0f)
        assertEquals(DangerInputs.WADE_DANGER + DangerInputs.BUILDING_DANGER, calm(300.0, 300.0), 0f)
        // with mobs: the mobs' danger plus the wade's; calm counts the wade only
        val m = DangerInputs.build(ring(0.7, 0.7, 6, 3), rect, A, 10)       // world (300, 300)
        val w = m.danger(wet, { _, _ -> false })
        assertEquals(m.mobs(300.0, 300.0) + DangerInputs.WADE_DANGER, w(300.0, 300.0), 1e-6f)
        assertEquals(DangerInputs.WADE_DANGER, m.calm(wet, { _, _ -> false })(300.0, 300.0), 0f)
    }

    @Test fun theFactionFlips() {
        // the same spawns: an Alliance city (its guards friendly to the Alliance, one guard faction-less), a Horde camp, wild beasts
        val city = ring(0.2, 0.2, 8, H, "City Guard", 55) + sp(0.2, 0.2, 3, "City Guard", 55)
        val camp = ring(0.8, 0.2, 8, A, "Grunt", 12)
        val wild = ring(0.5, 0.8, 3, 3, "Boar", 10)
        val all = city + camp + wild
        val rules = DangerInputs.Rules(friendlyMinLevel = 100)
        val a = DangerInputs.build(all, rect, A, 10, rules)
        val h = DangerInputs.build(all, rect, H, 10, rules)
        // the city: friendly to the Alliance only (its faction-less guard too)
        assertEquals(0f, at(a, 0.2, 0.2), 0f)
        assertTrue(at(h, 0.2, 0.2) > 0f)
        // the camp: the other way round
        assertTrue(at(a, 0.8, 0.2) > 0f)
        assertEquals(0f, at(h, 0.8, 0.2), 0f)
        // the wild: the same to both
        assertEquals(at(a, 0.5, 0.8), at(h, 0.5, 0.8), 0f)
        assertTrue(at(a, 0.5, 0.8) > 0f)
        // the guards are level 55: red to a level 10 Horde character, more than the boars
        assertTrue(at(h, 0.2, 0.2) > at(h, 0.5, 0.8))
    }

    @Test fun theCacheBuildsOnlyForANewKey() {
        val c = DangerInputs.Cache(max = 2)
        val s = ring(0.5, 0.5, 6, 3)
        val d1 = c.get(1411, 1, s, rect, H, 8)
        assertSame(d1, c.get(1411, 1, s, rect, H, 8))
        assertEquals(1, c.hits); assertEquals(1, c.misses)
        // a level-up, a character switch, new spawns, a new setting: each builds again
        val d2 = c.get(1411, 1, s, rect, H, 9); assertNotSame(d1, d2)
        c.get(1411, 1, s, rect, A, 9)
        c.get(1411, 2, s, rect, A, 9)
        c.get(1411, 2, s, rect, A, 9, avoid = "low")
        assertEquals(5, c.misses); assertEquals(1, c.hits)
        assertEquals(1.5, c.get(1411, 2, s, rect, A, 9, avoid = "low").k, 0.0)
        assertEquals(2, c.hits)
        // two kept: the oldest dropped
        assertEquals(2, c.size())
        c.get(1411, 1, s, rect, H, 8)
        assertEquals(6, c.misses)
        // another map
        c.get(1429, 2, s, rect, A, 9); assertEquals(7, c.misses)
    }

    @Test fun aBigZoneBuildsFast() {
        // Winterspring-sized (7000 x 5000 yd: 280 x 200 cells), 6000 spawns, the ground and the zone's cells asked per cell
        val big = MapHarvest.Rect(1, 7000.0, 2000.0, 3000.0, -4000.0)
        val rnd = java.util.Random(7)
        val s = (0 until 6000).map { QuestieNpcs.Spawn(1, rnd.nextFloat(), rnd.nextFloat(), 50 + rnd.nextInt(10), 55 + rnd.nextInt(10),
            rnd.nextInt(20) == 0, 1 + rnd.nextInt(3), if (rnd.nextInt(50) == 0) "Guard" else "Mob") }
        val rules = DangerInputs.Rules(100, listOf(Regex("^Friend")))
        repeat(3) { DangerInputs.build(s, big, H, 55, rules, ground = { _, _ -> 1f }, inZone = { _, _ -> true }) }   // warm up
        val ms = (0 until 5).map { DangerInputs.build(s, big, H, 55, rules, ground = { _, _ -> 1f }, inZone = { _, _ -> true }).ms }
        println("DANGERINPUTS big zone: ${"%.1f".format(ms.minOrNull())} - ${"%.1f".format(ms.maxOrNull())} ms")
        assertTrue("built in ${ms.minOrNull()} ms", ms.minOrNull()!! < 100.0)
    }
}
