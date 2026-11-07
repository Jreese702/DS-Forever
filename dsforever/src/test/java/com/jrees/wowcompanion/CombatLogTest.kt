package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-07: the combat log's health, in the shape the Thor's own WoWCombatLog (version 22) wrote it (names made up) */
class CombatLogTest {
    private val me = "Player-1-0000000A"
    private val wolf = "Creature-0-1-0-1-525-00000001"
    private fun adv(g: String, hp: Int, max: Int) = "$g,0000000000000000,$hp,$max,112,0,242,0,0,0,0,345,345,0,-9222.82,446.73,1429,2.6844,4"
    private fun swing(t: String, src: String, sn: String, dst: String, dn: String, info: String) =
        "11/7/2026 $t-7  SWING_DAMAGE,$src,\"$sn\",0x511,0x80000000,$dst,\"$dn\",0x10a48,0x80000000,$info,26,28,-1,1,0,0,0,nil,nil,nil"

    @Test fun anActorsOwnLineIsExact() {
        val h = CombatLog.Health()
        assertTrue(h.line(swing("11:10:42.622", me, "Me-Realm", wolf, "Mangy Wolf", adv(me, 278, 290)), 0))
        val e = h.get(me)!!
        assertEquals(278L, e.hp); assertEquals(290L, e.max)
        assertEquals(CombatLog.stamp("11/7/2026 11:10:42.622-7"), e.at)
    }

    @Test fun aHitOnATrackedUnitTakesItsAmount() {
        val h = CombatLog.Health()
        h.line(swing("11:10:42.622", me, "Me-Realm", wolf, "Mangy Wolf", adv(me, 278, 290)), 0)
        // the wolf's swing: its block is the wolf's; the amount (26 here) comes off my last value
        h.line(swing("11:10:45.544", wolf, "Mangy Wolf", me, "Me-Realm", adv(wolf, 52, 102)), 0)
        assertEquals(252L, h.get(me)!!.hp)
        assertEquals(52L, h.get(wolf)!!.hp)
        assertTrue(h.get(me)!!.exactAt < h.get(me)!!.at)
    }

    @Test fun aSpellLineHasThreePrefixFields() {
        val h = CombatLog.Health()
        h.line("11/7/2026 11:10:38.611-7  SPELL_DAMAGE,$me,\"Me-Realm\",0x511,0x80000000,$wolf,\"Mangy Wolf\",0x10a48,0x80000000,26364," +
            "\"Lightning Shield, Rank 1\",0x8,${adv(wolf, 88, 102)},14,13,-1,8,0,0,0,nil,nil,nil", 0)
        assertEquals(88L, h.get(wolf)!!.hp)
    }

    @Test fun deathAndAuraLines() {
        val h = CombatLog.Health()
        h.line(swing("11:10:45.544", wolf, "Mangy Wolf", me, "Me-Realm", adv(wolf, 4, 102)), 0)
        assertFalse(h.line("11/7/2026 11:10:46.000-7  SPELL_AURA_APPLIED,$me,\"Me\",0x511,0x80000000,$me,\"Me\",0x511,0x80000000,324,\"Lightning Shield\",0x8,BUFF", 0))
        assertTrue(h.line("11/7/2026 11:10:50.623-7  UNIT_DIED,0000000000000000,nil,0x80000000,0x80000000,$wolf,\"Mangy Wolf\",0x10a48,0x80000000,0", 0))
        assertEquals(0L, h.get(wolf)!!.hp)
        assertNull(h.get(me))
    }

    /** the regen true-up: two quiet exact readings teach the rate; the value then climbs on its own, never past the max */
    @Test fun regenIsLearnedAndApplied() {
        val h = CombatLog.Health()
        h.line(swing("11:20:00.000", me, "Me-Realm", wolf, "Mangy Wolf", adv(me, 200, 290)), 0)
        h.line(swing("11:20:10.000", me, "Me-Realm", wolf, "Mangy Wolf", adv(me, 220, 290)), 0)
        val e = h.get(me)!!
        assertEquals(2.0, e.rate, 0.001)
        assertEquals(240L, e.hpAt(e.at + 10_000))
        assertEquals(290L, e.hpAt(e.at + 600_000))
        // a hit: no regen until REGEN_AFTER_MS after it
        h.line(swing("11:20:12.000", wolf, "Mangy Wolf", me, "Me-Realm", adv(wolf, 50, 102)), 0)
        val hit = h.get(me)!!
        assertEquals(hit.hp, hit.hpAt(hit.hurtAt + 5_000))
        assertTrue(hit.hpAt(hit.hurtAt + 10_000) > hit.hp)
    }

    @Test fun theStampReadsTheOffset() {
        // 11:10:42.622 at UTC-7 = 18:10:42.622 UTC
        assertEquals(java.time.Instant.parse("2026-11-07T18:10:42.622Z").toEpochMilli(), CombatLog.stamp("11/7/2026 11:10:42.622-7"))
    }
}
