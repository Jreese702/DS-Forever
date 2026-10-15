package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** f-settings-tabs, off-device: the Settings room's tabs as data (every row on exactly one tab, the user's approved grouping) */
private typealias T = SettingsTabs.Tab
private typealias B = SettingsTabs.Block

class SettingsTabsTest {
    @Test fun sixTabsInTheApprovedOrder() =
        assertEquals(listOf("Home", "Controller", "Top screen", "Rooms", "Performance", "System"), T.values().map { it.label })

    @Test fun everyRowIsOnExactlyOneTab() {
        val rows = B.values().flatMap { b -> b.rows.map { it to b.tab } }
        val dup = rows.groupBy { it.first }.filter { it.value.size > 1 }.keys
        assertTrue("rows on more than one tab: $dup", dup.isEmpty())
    }

    @Test fun everyTabHasRows() {
        for (t in T.values()) assertTrue("$t has no rows", SettingsTabs.blocks(t).flatMap { it.rows }.isNotEmpty())
    }

    @Test fun theUsersPicks() {
        assertEquals(T.PERFORMANCE, B.INDOOR.tab)                       // indoor maps: "Performance (Recommended)"
        assertEquals(listOf(B.MAP, B.CHAT), SettingsTabs.blocks(T.ROOMS))   // Map and Chat together as Rooms
        assertEquals(listOf(B.FRAMES, B.INDOOR), SettingsTabs.blocks(T.PERFORMANCE))
        assertEquals(listOf(B.GAME, B.COMPANION), SettingsTabs.blocks(T.SYSTEM))
        assertTrue("Cast bar" in B.GAME_UI.rows && B.GAME_UI.tab == T.TOP)   // the Controller session's new row lands on Top screen
        assertTrue("Frame limit" in B.FRAMES.rows)
    }

    @Test fun settingsViewDrawsItsOwnBlocksOnly() {
        assertTrue(SettingsTabs.viewBlocks(T.HOME).isEmpty())          // Home's section
        assertTrue(SettingsTabs.viewBlocks(T.TOP).isEmpty())           // the game's elements
        assertEquals(listOf(B.GAME), SettingsTabs.viewBlocks(T.SYSTEM))   // + the companion's status (GameUiSection)
        assertEquals(listOf(B.CONTROLLER), SettingsTabs.viewBlocks(T.CONTROLLER))
    }

    @Test fun lAndRWrap() {
        assertEquals(T.SYSTEM, SettingsTabs.step(T.HOME, -1))
        assertEquals(T.HOME, SettingsTabs.step(T.SYSTEM, 1))
        assertEquals(T.CONTROLLER, SettingsTabs.step(T.HOME, 1))
        var t = T.HOME
        repeat(T.values().size) { t = SettingsTabs.step(t, 1) }
        assertEquals(T.HOME, t)
    }
}

/** f-settings-tabs phase 2: the "What it does" pictures belong to bottom-screen rows (the user's rule: none on the top screen's Show/Hide) */
class SettingsPreviewRowsTest {
    private val allRows = SettingsTabs.Block.values().flatMap { b -> b.rows.map { it to b.tab } }.toMap()

    @Test fun everyPictureIsARowOfABottomScreenTab() {
        for (id in SettingsPreview.ROWS.keys) {
            val tab = allRows[id]
            assertTrue("$id is not a Settings row", tab != null)
            assertTrue("$id is on $tab", tab != SettingsTabs.Tab.TOP && tab != SettingsTabs.Tab.SYSTEM)
        }
    }

    @Test fun noPictureOnTheTopScreensShowHide() =
        assertTrue(SettingsTabs.Block.GAME_UI.rows.none { SettingsPreview.has(it) })

    @Test fun everyPictureHasATitleAndWords() {
        val s = SettingsPreview.State(startAttack = true, toolbar = true)
        for (id in SettingsPreview.ROWS.keys) {
            assertTrue(id, SettingsPreview.title(id, s).length in 3..40)
            assertTrue(id, SettingsPreview.caption(id, s).isNotEmpty() && SettingsPreview.caption(id, s).all { it.length <= 46 })
        }
    }

    @Test fun twentyRowsAndTheMovingOnes() {
        assertEquals(20, SettingsPreview.ROWS.size)
        assertTrue(SettingsPreview.animated("Swing timer") && SettingsPreview.animated("Frame limit") && !SettingsPreview.animated("Button glyphs"))
    }
}
