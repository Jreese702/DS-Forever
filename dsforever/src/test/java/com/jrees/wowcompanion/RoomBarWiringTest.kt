package com.jrees.wowcompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MOVABLE HOME, PHASE 4: EVERY PLACE THAT MAPS THE ROOM BAR'S POSITIONS FOLLOWS ITS ORDER (read from the sources, as the other host tests):
 * the Strip draws (and hit-tests, and reports [Strip.place]) in the record's order; the bar cursor (HomeBarSelect) and the D-pad's bar
 * commands walk what the Strip drew; the rooms' push slides by the bar's order; the pause / target radials' D-pad bar starts under a
 * hidden XP track; every room keeps 935 while Home's frame reaches under the track. The add-on's DS toolbar (ROOMBAR, the top-screen
 * Nintendo bar's NB.TOOLBAR) maps D-pad directions to chat / radial / map / home, never the room bar's order: nothing to change there.
 */
class RoomBarWiringTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val addons = listOf("../companion/app/src/main/assets/companion/addons", "companion/app/src/main/assets/companion/addons")
        .map { File(it) }.first { it.isDirectory }
    private fun k(name: String) = File(src, name).readText()

    @Test fun theStripDrawsTheOrder() {
        val strip = k("Strip.kt")
        assertTrue(strip.contains("val rooms = order "))
        assertTrue(strip.contains("val half = RoomBar.leftCount(rooms.size)"))
        assertFalse("no fixed Room.values() bar any more", strip.contains("Room.values().filter { it != Room.HOME && it.onBar }"))
        // the touch boxes and place() are the slots of the order (pics / hit filled in group from the order's rooms)
        // (review round 3: in the bar's pooled boxes, set in place - the never-resting edit wiggle redraws the bar every frame)
        assertTrue(strip.contains("hit[r] = hitPool[r.ordinal].apply { set(left, bottom - BUTTONS, left + cell, bottom) }"))
        assertTrue(strip.contains("pics[r] = picPool[r.ordinal].apply { set(slotX - ICON / 2f, cy - ICON / 2f, slotX + ICON / 2f, cy + ICON / 2f) }"))
    }

    @Test fun theCursorTheCommandsAndThePushFollow() {
        // the bar cursor (Select / D-pad Down / the DS Bar Left / Right / Open commands) walks the bar as DRAWN
        val bar = k("HomeBarSelect.kt")
        assertTrue(bar.contains(".mapNotNull { r -> strip.place(r)?.let { r to it.centerX() } }.sortedBy { it.second }.map { it.first }"))
        // the rooms' sideways push: by the bar's order, not Room.ordinal
        val host = k("CompanionHost.kt")
        assertTrue(host.contains("private fun barOrder(r: Room): Int = RoomBar.position(r, strip.order.map { it.name })"))
        assertFalse(host.contains("private fun barOrder(r: Room): Int = r.ordinal"))
        // the record drives the bar (another character, Save, a reset); edit mode its draft
        assertTrue(host.contains("strip.showOrder(RoomBar.rooms(HomeSettings.layout.rooms), Motion.MODULE_OPEN)"))
        assertTrue(host.contains("homeView.roomBar = strip"))
        assertTrue(k("HomeView.kt").contains("b.showOrder(RoomBar.rooms((m?.draft ?: HomeSettings.layout).rooms), ms)"))
        // the Settings pictures' bar too
        assertTrue(k("PreviewStage.kt").contains("st.showOrder(RoomBar.rooms(HomeSettings.layout.rooms), 0L)"))
    }

    @Test fun theXpTrackIsHomesOnly() {
        val host = k("CompanionHost.kt")
        assertTrue(host.contains(".apply { topMargin = -Strip.RISE - Strip.TRACKS })"))
        assertTrue("every room keeps 935", host.contains(".apply { if (v !== homeView) bottomMargin = Strip.TRACKS })"))
        assertTrue(host.contains("val hide = room == Room.HOME && !HomeSettings.layout.xpBar && !homeView.editing"))
        // the radials' D-pad bar covers the bar from where it starts (under a hidden track)
        for (f in listOf("HomePauseView.kt", "TargetRadialView.kt"))
            assertTrue(f, k(f).contains("- strip.translationY + strip.trackGone else 0f"))
        // the track's place is Home's: a touch there falls through to Home (the disc excepted)
        assertTrue(k("Strip.kt").contains("e.y - (height - BAR) < trackGone && !onHome(e.x, e.y)) return false"))
    }

    @Test fun theAddOnsToolbarIsNotTheRoomBar() {
        val padmap = File(addons, "WowCompanionSpells/PadMap.lua").readText()
        val bar = File(addons, "WowCompanion/ActionBar.lua").readText()
        // the DS toolbar's words are chat / radial / map / home: directions, not the room bar's rooms
        assertTrue(padmap.contains("ROOMBAR"))
        for (room in listOf("SPELLBOOK", "PROFESSIONS", "GROUPFINDER", "MACROS")) {
            assertFalse("$room in PadMap", padmap.contains("\"$room\""))
            assertFalse("$room in ActionBar's toolbar", bar.contains("TOOLBAR") && Regex("TOOLBAR[^\\n]*$room").containsMatchIn(bar))
        }
    }
}
