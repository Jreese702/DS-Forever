package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MOVABLE HOME, PHASE 4: THE ROOM BAR'S ORDER (SPEC.txt sections 8, 9, 13; D5: per character with "All characters") and, since 1518
 * build 2 (the user's ruling 2026-11-21: each room icon is its own app), its HIDDEN rooms. Pure: the default is today's bar, a saved order
 * is always whole (a hidden room keeps its place in it), Settings can never be hidden, the disc stays in the middle, the rooms' push
 * follows the order, the record keeps both (JSON - a Classic record's text unchanged -, per character, All characters, Reset).
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

    @Test fun theOrderStaysWholeAndHiddenIsApart() {
        // unknown, doubled and off-bar names go; every bar room is there once (a hidden room too: it keeps its place)
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
        // hidden: known hideable bar rooms, once each, in today's order; never Settings, never Home / Chat / Calendar / unknown
        assertEquals(listOf("QUESTS", "BAGS"), RoomBar.hiddenOf(listOf("BAGS", "SETTINGS", "QUESTS", "BAGS", "HOME", "CHAT", "CALENDAR", "NOPE")))
        assertFalse(RoomBar.canHide("SETTINGS")); assertFalse(RoomBar.canHide("HOME")); assertFalse(RoomBar.canHide("CHAT"))
        for (r in RoomBar.DEFAULT - "SETTINGS") assertTrue(r, RoomBar.canHide(r))
        assertEquals(listOf("BAGS"), RoomBar.hide(emptyList(), "BAGS"))
        assertEquals("Settings stays", listOf("BAGS"), RoomBar.hide(listOf("BAGS"), "SETTINGS"))
        assertEquals(emptyList<String>(), RoomBar.unhide(listOf("BAGS"), "BAGS"))
        // the bar shows the whole order less the hidden ones
        assertEquals(RoomBar.DEFAULT - "BAGS" - "MAP", RoomBar.visible(RoomBar.DEFAULT, listOf("MAP", "BAGS")))
        assertEquals(RoomBar.DEFAULT.size - 1, RoomBar.shown(HomeLayout(hidden = listOf("SOCIAL"))).size)
        assertFalse(Room.SOCIAL in RoomBar.shown(HomeLayout(hidden = listOf("SOCIAL"))))
    }

    @Test fun aVisibleMoveKeepsEachHiddenRoomBesideItsNeighbour() {
        val hidden = listOf("QUESTS", "BAGS")
        // Controller to the far left of the bar: Quests still follows Map, Bags still follows Character
        val vis = RoomBar.visible(RoomBar.DEFAULT, hidden)
        val o = RoomBar.moveVisible(RoomBar.DEFAULT, hidden, vis.indexOf("CONTROLLER"), 0)
        assertEquals("CONTROLLER", o[0])
        assertEquals(o.indexOf("MAP") + 1, o.indexOf("QUESTS"))
        assertEquals(o.indexOf("CHARACTER") + 1, o.indexOf("BAGS"))
        assertEquals(RoomBar.DEFAULT.toSet(), o.toSet())
        assertEquals("CONTROLLER", RoomBar.visible(o, hidden)[0])
        // a hidden room that led the bar stays first
        val lead = RoomBar.moveVisible(RoomBar.DEFAULT, listOf("MAP"), 0, 4)
        assertEquals("MAP", lead[0])
        assertEquals("QUESTS", RoomBar.visible(lead, listOf("MAP"))[4])
        // nothing hidden: a plain move; out of range: the order unchanged
        assertEquals(RoomBar.move(RoomBar.DEFAULT, 3, 0), RoomBar.moveVisible(RoomBar.DEFAULT, emptyList(), 3, 0))
        assertEquals(RoomBar.DEFAULT, RoomBar.moveVisible(RoomBar.DEFAULT, hidden, 0, 99))
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
        // a hidden room counts right after the visible room it follows (as a room off the bar); the halves split the VISIBLE rooms
        val h = listOf("TALENTS")
        assertTrue(RoomBar.position(Room.TALENTS, d, h) in (RoomBar.position(Room.SPELLBOOK, d, h) + 1) until RoomBar.position(Room.PROFESSIONS, d, h))
        assertEquals(6, RoomBar.leftCount(RoomBar.visible(d, h).size))
        assertTrue("11 rooms: 6 | 5, Character now left of the disc", RoomBar.position(Room.CHARACTER, d, h) < RoomBar.position(Room.HOME, d, h))
        assertTrue(RoomBar.position(Room.HOME, d, h) < RoomBar.position(Room.BAGS, d, h))
        // a hidden first room counts before every other
        assertTrue(RoomBar.position(Room.MAP, d, listOf("MAP")) < RoomBar.position(Room.QUESTS, d, listOf("MAP")))
        // Chat follows Controller even with Controller hidden (after the visible room before it: Bags)
        val hc = listOf("CONTROLLER")
        assertTrue(RoomBar.position(Room.CHAT, d, hc) > RoomBar.position(Room.BAGS, d, hc))
        assertTrue(RoomBar.position(Room.CHAT, d, hc) < RoomBar.position(Room.SOCIAL, d, hc))
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

    @Test fun theRecordKeepsTheHiddenRooms() {
        // Classic's text is unchanged (no "hide" while nothing is hidden); classic() brings every room back
        val classic = HomeLayout()
        assertFalse(HomeLayoutJson.json(classic).contains("hide"))
        assertEquals(HomeLayoutJson.json(classic), HomeLayoutJson.json(classic.copy(hidden = emptyList())))
        val l = HomeLayout(hidden = listOf("BAGS", "MACROS"), rooms = RoomBar.move(RoomBar.DEFAULT, 7, 0))
        val j = HomeLayoutJson.json(l)
        assertTrue(j.contains("\"hide\":[\"BAGS\",\"MACROS\"]"))
        assertTrue("the order still holds every room", j.contains("\"rooms\":[\"BAGS\""))
        assertEquals(l, HomeLayoutJson.parse(j))
        assertFalse(HomeLayout().samePlace(HomeLayout(hidden = listOf("BAGS"))))
        assertTrue(l.classic().hidden.isEmpty())
        // a hand-edited record never hides Settings (nor unknown names): the player can always get back
        val bad = HomeLayoutJson.parse("""{"v":1,"hide":["SETTINGS","NOPE",3,"SOCIAL","SOCIAL","CHAT"]}""")
        assertEquals(listOf("SOCIAL"), bad.hidden)
        assertTrue(HomeLayoutJson.parse("""{"v":1,"hide":"x"}""").hidden.isEmpty())
        // the store lays the session's own keys on: a hide in edit mode and an order change made meanwhile both survive
        val base = HomeLayout()
        val edit = base.copy(hidden = listOf("BAGS"))
        val real = base.copy(rooms = RoomBar.move(RoomBar.DEFAULT, 11, 0))
        val on = HomeLayoutStore.layOn(real, base, edit)
        assertEquals(listOf("BAGS"), on.hidden)
        assertEquals("SETTINGS", on.rooms[0])
        // putting the last room back removes the key from the record (back to Classic's text)
        val back = HomeLayoutStore.layOn(edit, edit, base)
        assertTrue(back.hidden.isEmpty())
        assertFalse(HomeLayoutJson.json(back).contains("hide"))
    }

    @Test fun theOrderIsPerCharacterWithAllCharacters() {
        HomeSettings.startForTest(HomeLayoutStoreTest.Prefs())
        try {
            HomeLayoutStore.character("Korvash-Thrall")
            val mine = HomeLayoutStore.active().copy(rooms = RoomBar.move(RoomBar.DEFAULT, 11, 0), xpBar = false, hidden = listOf("MACROS"))
            HomeLayoutStore.set(mine)
            HomeLayoutStore.character("Iselle-Thrall")
            assertEquals("another character keeps today's bar", RoomBar.DEFAULT, HomeLayoutStore.active().rooms)
            assertTrue(HomeLayoutStore.active().xpBar)
            assertTrue("and every room on it", HomeLayoutStore.active().hidden.isEmpty())
            HomeLayoutStore.character("Korvash-Thrall")
            assertEquals("SETTINGS", HomeLayoutStore.active().rooms[0])
            assertEquals(listOf("MACROS"), HomeLayoutStore.active().hidden)
            assertFalse(HomeLayoutStore.active().xpBar)
            // All characters: every character and the default take it
            HomeLayoutStore.set(HomeLayoutStore.active(), all = true)
            HomeLayoutStore.character("Iselle-Thrall")
            assertEquals("SETTINGS", HomeLayoutStore.active().rooms[0])
            assertEquals("SETTINGS", HomeLayoutStore.default().rooms[0])
            assertEquals(listOf("MACROS"), HomeLayoutStore.active().hidden)
            // an arrangement of the columns never touches the bar (1517's Big map preset's fields)
            val big = HomeArrangements.of(HomeArrangements.BIG_MAP, HomeLayoutStore.active())
            assertEquals("SETTINGS", big.rooms[0])
            assertFalse(big.xpBar)
        } finally { HomeSettings.startForTest(null) }
    }
}
