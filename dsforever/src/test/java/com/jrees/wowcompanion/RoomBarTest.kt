package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MOVABLE HOME, PHASE 4: THE ROOM BAR'S ORDER (SPEC.txt sections 8, 9, 13; the user's D3: reorder only, every room stays on the bar; D5:
 * per character with "All characters"). Pure: the default is today's bar, a saved order can never take a room off the bar, the disc stays
 * in the middle, the rooms' push follows the order, the record keeps it (JSON, per character, All characters, Reset).
 */
class RoomBarTest {
    @Test fun theDefaultIsTodaysBar() {
        assertEquals(listOf("MAP", "QUESTS", "SPELLBOOK", "TALENTS", "PROFESSIONS", "GROUPFINDER",
            "CHARACTER", "BAGS", "CONTROLLER", "SOCIAL", "MACROS", "SETTINGS"), RoomBar.DEFAULT)
        // the disc in the middle: 6 | 6, the right half starting at Character (today's split)
        assertEquals(6, RoomBar.leftCount(RoomBar.DEFAULT.size))
        assertEquals("CHARACTER", RoomBar.DEFAULT[RoomBar.leftCount(RoomBar.DEFAULT.size)])
        assertEquals(RoomBar.DEFAULT, HomeLayout().rooms)
    }

    @Test fun noOrderCanTakeARoomOffTheBar() {
        // unknown, doubled and off-bar names go; every bar room is there once (D3)
        val n = RoomBar.normalize(listOf("BAGS", "BAGS", "CHAT", "HOME", "NOPE", "MAP"))
        assertEquals(RoomBar.DEFAULT.toSet(), n.toSet())
        assertEquals(RoomBar.DEFAULT.size, n.size)
        assertEquals(listOf("BAGS", "MAP"), n.filter { it == "BAGS" || it == "MAP" })
        // a room the list misses (a room a later build adds) comes back after the room it follows today
        val missing = RoomBar.DEFAULT.filter { it != "TALENTS" }.reversed()
        val back = RoomBar.normalize(missing)
        assertEquals(back.indexOf("SPELLBOOK") + 1, back.indexOf("TALENTS"))
        assertEquals(RoomBar.DEFAULT, RoomBar.normalize(null))
        assertEquals(RoomBar.DEFAULT, RoomBar.normalize(emptyList()))
    }

    @Test fun aMoveClosesUpBehindTheRoom() {
        val o = RoomBar.move(RoomBar.DEFAULT, RoomBar.DEFAULT.indexOf("BAGS"), 0)
        assertEquals("BAGS", o[0])
        assertEquals("MAP", o[1])
        assertEquals(RoomBar.DEFAULT.size, o.size)
        assertEquals(RoomBar.DEFAULT, RoomBar.move(RoomBar.DEFAULT, 3, 3))
        assertEquals(RoomBar.DEFAULT, RoomBar.move(RoomBar.DEFAULT, -1, 3))
    }

    @Test fun theRoomsPushFollowsTheOrder() {
        // today's order: the same comparisons as Room.ordinal (the old barOrder), Chat between Controller and Social
        val d = RoomBar.DEFAULT
        val rooms = Room.values().filter { it != Room.HOME }
        for (a in rooms) for (b in rooms) if (a != b)
            assertEquals("$a vs $b", a.ordinal < b.ordinal, RoomBar.position(a, d) < RoomBar.position(b, d))
        assertTrue(RoomBar.position(Room.CHAT, d) in (RoomBar.position(Room.CONTROLLER, d) + 1) until RoomBar.position(Room.SOCIAL, d))
        // the disc between the halves
        assertTrue(RoomBar.position(Room.GROUPFINDER, d) < RoomBar.position(Room.HOME, d))
        assertTrue(RoomBar.position(Room.HOME, d) < RoomBar.position(Room.CHARACTER, d))
        // Settings moved to the far left: a push from Map to Settings now comes in from the LEFT
        val o = RoomBar.move(d, d.indexOf("SETTINGS"), 0)
        assertTrue(RoomBar.position(Room.SETTINGS, o) < RoomBar.position(Room.MAP, o))
        // Bags moved left of the disc: it is left of Home now
        val o2 = RoomBar.move(d, d.indexOf("BAGS"), 2)
        assertTrue(RoomBar.position(Room.BAGS, o2) < RoomBar.position(Room.HOME, o2))
        // Chat still rides with Controller wherever it goes
        val o3 = RoomBar.move(d, d.indexOf("CONTROLLER"), 0)
        assertTrue(RoomBar.position(Room.CHAT, o3) < RoomBar.position(Room.QUESTS, o3))
    }

    @Test fun theRecordKeepsTheOrderAndTheXpTrack() {
        // Classic's text is unchanged (no "rooms" while it is today's order)
        assertFalse(HomeLayoutJson.json(HomeLayout()).contains("rooms"))
        val l = HomeLayout(xpBar = false, rooms = RoomBar.move(RoomBar.DEFAULT, 7, 0))
        val back = HomeLayoutJson.parse(HomeLayoutJson.json(l))
        assertEquals(l, back)
        assertTrue(l.samePlace(back))
        assertFalse(HomeLayout().samePlace(l.copy(xpBar = true)))
        // a broken list is a whole bar again; garbage keeps the fallback
        val bad = HomeLayoutJson.parse("""{"v":1,"rooms":["BAGS","MAP",3,"CHAT","BAGS"]}""")
        assertEquals("BAGS", bad.rooms[0])
        assertEquals(RoomBar.DEFAULT.size, bad.rooms.size)
        assertEquals(RoomBar.DEFAULT, HomeLayoutJson.parse("""{"v":1,"rooms":"x"}""").rooms)
    }

    @Test fun theOrderIsPerCharacterWithAllCharacters() {
        HomeSettings.startForTest(HomeLayoutStoreTest.Prefs())
        try {
            HomeLayoutStore.character("Korvash-Thrall")
            val mine = HomeLayoutStore.active().copy(rooms = RoomBar.move(RoomBar.DEFAULT, 11, 0), xpBar = false)
            HomeLayoutStore.set(mine)
            HomeLayoutStore.character("Iselle-Thrall")
            assertEquals("another character keeps today's bar", RoomBar.DEFAULT, HomeLayoutStore.active().rooms)
            assertTrue(HomeLayoutStore.active().xpBar)
            HomeLayoutStore.character("Korvash-Thrall")
            assertEquals("SETTINGS", HomeLayoutStore.active().rooms[0])
            assertFalse(HomeLayoutStore.active().xpBar)
            // All characters: every character and the default take it
            HomeLayoutStore.set(HomeLayoutStore.active(), all = true)
            HomeLayoutStore.character("Iselle-Thrall")
            assertEquals("SETTINGS", HomeLayoutStore.active().rooms[0])
            assertEquals("SETTINGS", HomeLayoutStore.default().rooms[0])
            // an arrangement of the columns never touches the bar (1517's Big map preset's fields)
            val big = HomeArrangements.of(HomeArrangements.BIG_MAP, HomeLayoutStore.active())
            assertEquals("SETTINGS", big.rooms[0])
            assertFalse(big.xpBar)
        } finally { HomeSettings.startForTest(null) }
    }
}
