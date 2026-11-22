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
        // 1518 build 2: the halves split the cells (the rooms drawn - the visible ones - and edit mode's "+" spot after them)
        assertTrue(strip.contains("val cells = rooms.size + if (plusNow) 1 else 0"))
        assertTrue(strip.contains("val half = RoomBar.leftCount(cells).coerceAtMost(rooms.size)"))
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
        // the rooms' sideways push: by the record's whole order with its hidden rooms (never the Strip's list: it holds only the rooms
        // drawn, and normalizing it would put the hidden rooms back into the count), not Room.ordinal
        val host = k("CompanionHost.kt")
        assertTrue(host.contains("private fun barOrder(r: Room): Int = RoomBar.position(r, HomeSettings.layout.rooms, HomeSettings.layout.hidden)"))
        assertFalse(host.contains("private fun barOrder(r: Room): Int = r.ordinal"))
        assertFalse(host.contains("RoomBar.position(r, strip.order"))
        // the record drives the bar (another character, a reset); edit mode its draft - the VISIBLE rooms in every place
        assertTrue(host.contains("strip.showOrder(RoomBar.shown(HomeSettings.layout), Motion.MODULE_OPEN)"))
        assertTrue(host.contains("homeView.roomBar = strip"))
        assertTrue(k("HomeView.kt").contains("b.showOrder(RoomBar.shown(m?.draft ?: HomeSettings.layout), ms)"))
        // the Settings pictures' bar too
        assertTrue(k("PreviewStage.kt").contains("st.showOrder(RoomBar.shown(HomeSettings.layout), 0L)"))
        for (f in listOf("CompanionHost.kt", "HomeView.kt", "PreviewStage.kt")) assertFalse(f, k(f).contains("showOrder(RoomBar.rooms("))
    }

    @Test fun theBarWearsNoFrameAndEachRoomIsAnApp() {
        // 1518 build 2: drawBar's lower layer draws nothing (no dim, no dashed frame, no gold edge round the row), and no tag
        val ev = k("HomeEditView.kt")
        val draw = ev.substringAfter("fun drawBar(c: Canvas, layer: Int) {").substringBefore("\n    }\n")
        assertTrue(draw.contains("if (layer == 0) return"))
        for (bad in listOf("frame(", "buttonsBox()", "tag(", "DIM", "drawRoundRect(row")) assertFalse(bad, draw.contains(bad))
        // the badge is drawn inside the room's own pose (it wiggles with its app); Settings has none
        val strip = k("Strip.kt")
        val group = strip.substringAfter("private fun group(").substringBefore("\n    }\n")
        assertTrue(group.contains("editApps?.let { a -> if (RoomBar.canHide(r.name)) a.badge(c, r, cx, cy, ICON) }"))
        assertTrue("before the pose's restore", group.indexOf("a.badge(") < group.indexOf("if (posed >= 0) c.restoreToCount(posed)"))
        assertTrue(k("RoomBar.kt").contains("val UNHIDEABLE: Set<String> = setOf(Room.SETTINGS.name)"))
        // the host hands the Strip the apps; the "+" spot wiggles on its own key
        assertTrue(k("CompanionHost.kt").contains("strip.editApps = v?.apps()"))
        assertTrue(k("HomeView.kt").contains("motion.pose(HomeEditMotion.PLUS_ROOM, motionNow(), ROOM_HALF_DIAG, pose)"))
        // the bar's menu is gone (XP bar moved to Settings > Bottom screen's Home tiles; Order: Reset dropped: Discard / Reset Home)
        val model = k("HomeEditModel.kt")
        for (bad in listOf("K_XP", "K_ORDER", "\"Room bar\"")) assertFalse(bad, model.contains(bad))
        assertTrue(k("BottomTiles.kt").contains("XP -> HomeLayoutStore.update { it.copy(xpBar = k == 0) }"))
    }

    @Test fun aHiddenRoomStillOpensEveryOtherWay() {
        // hiding takes a room off the bar only: the game's windows, Polestar, the pause wheel still open it (the host's show / pickRoom
        // by Room, never by a bar place); the bar cursor and the D-pad's bar commands walk only what the Strip drew
        val host = k("CompanionHost.kt")
        for (r in listOf("Room.BAGS", "Room.QUESTS", "Room.CHARACTER", "Room.SOCIAL", "Room.GROUPFINDER", "Room.PROFESSIONS", "Room.MACROS"))
            assertTrue(r, Regex("(show|pickRoom)\\($r\\b").containsMatchIn(host))
        assertFalse("nothing reads the hidden list to refuse a room", host.contains("hidden.contains(") || host.contains("in HomeSettings.layout.hidden"))
        val bar = k("HomeBarSelect.kt")
        assertTrue(bar.contains("strip.place(r)"))
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
