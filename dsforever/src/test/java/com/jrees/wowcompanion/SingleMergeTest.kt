package com.jrees.wowcompanion

import com.jrees.wowcompanion.SinglePlayLayout.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SINGLE SCREEN MODE, PLAY AND NPC TOGETHER (the integration of feat/single-play and feat/single-npc on feat/single-screen): the rules
 * that only exist where the two meet.
 *  - LB's party picture is NPC's strip over the left dock's party place (SingleLook row 9: the window HIDDEN, the docks up): PLAY's docks
 *    hide the party list under it (PLAY had keyed that on a FULL frame, CORE's old row); the fight's rolls in the map's place hide the map.
 *  - The band's controller reader (CastView.readAway) runs whenever Home is the room, as with two screens: never stopped at a look's edge
 *    on Home (NPC's class wheel opened from the game, Home's glance, a quick wheel) - a stop lets every held button and stick go.
 *  - A fresh host (the switch, every launcher resume) applies its first look in onCreate BEFORE PLAY's wiring makes the docks: the
 *    wiring gives that look to the docks, and Home - never laid, its column GONE - is laid once for them (SingleDocks.layHomeOnce).
 */
class SingleMergeTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val host by lazy { File(src, "CompanionHost.kt").readText() }
    private fun fn(name: String) = host.substringAfter("private fun $name(").substringBefore("\n    }\n")

    @Test fun theDocksLeaveOutWhatNpcsStripCovers() {
        val lb = SingleLook.of(SingleLook.In(lb = true))
        assertEquals(SingleNpc.LB, lb.strip)
        assertTrue("LB's picture keeps the docks up", lb.docks)
        assertEquals(setOf(Piece.PARTY), SinglePlayLayout.hidden(lb.strip, lb.frame == SingleLook.Frame.FULL, chatLaid = true, mapLaid = true))
        val rolls = SingleLook.of(SingleLook.In(fightRolls = true))
        assertEquals(SingleNpc.ROLLS, rolls.strip)
        assertTrue(rolls.docks)
        assertEquals(setOf(Piece.MAP), SinglePlayLayout.hidden(rolls.strip, rolls.frame == SingleLook.Frame.FULL, chatLaid = true, mapLaid = true))
        // LB held over the rolls: the held button's moment wins (NPC's row order), the map back, the party list under the picture
        val both = SingleLook.of(SingleLook.In(lb = true, fightRolls = true))
        assertEquals(setOf(Piece.PARTY), SinglePlayLayout.hidden(both.strip, false, chatLaid = true, mapLaid = true))
        val play = SingleLook.of(SingleLook.In())
        assertEquals("PLAY: every piece Home lays", emptySet<Piece>(), SinglePlayLayout.hidden(play.strip, false, chatLaid = true, mapLaid = true))
        assertEquals(setOf(Piece.CHAT, Piece.MAP), SinglePlayLayout.hidden(null, false, chatLaid = false, mapLaid = false))
        assertTrue(Piece.PARTY in SinglePlayLayout.hidden(null, fullFrame = true, chatLaid = true, mapLaid = true))
        assertTrue("the host uses the rule", fn("playStep").contains("SinglePlayLayout.hidden(l?.strip, l?.frame == SingleLook.Frame.FULL, laid.chat, laid.map)"))
    }

    @Test fun theBandsReaderRunsWheneverHomeIsTheRoom() {
        val look = fn("singlePlayLook")
        assertTrue(look.contains("castView.readAway = room == Room.HOME"))
        assertEquals("set nowhere else but the host's end", 2, Regex("castView\\.readAway = ").findAll(host).count())
        assertTrue(fn("singlePlayEnd").contains("castView.readAway = false"))
    }

    @Test fun aFreshHostsFirstLookReachesTheDocks() {
        val create = host.indexOf("singleReady = true\n        show(Room.HOME)")
        val wire = host.indexOf("        singlePlayWire()\n")
        assertTrue("the first look comes before the PLAY wiring", create in 0 until wire)
        assertTrue("the wiring gives it to the docks", fn("singlePlayWire").contains("singleLookNow?.let { singlePlayLook(it) }"))
        assertTrue("Home laid once for the docks", fn("singlePlayLook").contains("SingleDocks.layHomeOnce(homeView, roomsRoot!!, b[0], b[1], b[2], b[3])"))
        val lay = File(src, "SingleDocks.kt").readText().substringAfter("fun layHomeOnce(").substringBefore("\n        }\n")
        assertTrue("only a Home never laid", lay.contains("if (home.width > 0 && home.height > 0) return false"))
    }
}
