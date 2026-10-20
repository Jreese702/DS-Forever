package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** f-settings-tabs, off-device: the Settings room's tabs as data (every row on exactly one tab, the user's approved grouping) */
private typealias T = SettingsTabs.Tab
private typealias B = SettingsTabs.Block

class SettingsTabsTest {
    /** test40: the Rooms tab is gone (the user: "move those settings to the home tab and put them under home module settings") */
    @Test fun sixTabsInTheApprovedOrder() =
        assertEquals(listOf("Home", "Controller", "Top screen", "Add-ons", "Performance", "System"), T.values().map { it.label })

    /** 1.0.9 Add-ons: every third-party add-on has its block there, its rows its own (title, modules, options) */
    @Test fun addOnsHaveTheirOwnTab() {
        // 1.0.9: with the route modes held back, only Spoken's block shows (Polestar / QuestTrail come back with them)
        val shown = if (Settings.ROUTE_MODES_ON) listOf(B.ADDON_SPOKEN, B.ADDON_POLESTAR, B.ADDON_QUESTTRAIL) else listOf(B.ADDON_SPOKEN)
        assertEquals(shown, SettingsTabs.blocks(T.ADDONS))
        assertEquals(shown, SettingsTabs.viewBlocks(T.ADDONS))
        for (a in ThirdPartyAddons.ALL) assertTrue(a.title, SettingsTabs.Block.values().any { SettingsTabs.addonOf(it) == a })
        assertEquals(listOf("Spoken", "Spoken: Quests", "Spoken: Zones", "Spoken: Books", "Stop speech on B, Decline or close",
            "Stop speech on Accept"), B.ADDON_SPOKEN.rows)
    }

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
        assertEquals(listOf(B.HOME_SECTION, B.MAP, B.CHAT), SettingsTabs.blocks(T.HOME))   // Home's rows, then its modules' (test40)
        assertEquals("Home module settings", SettingsTabs.HOME_MODULES)
        assertEquals(listOf(B.FRAMES, B.INDOOR), SettingsTabs.blocks(T.PERFORMANCE))
        assertEquals(listOf(B.GAME, B.COMPANION), SettingsTabs.blocks(T.SYSTEM))
        assertTrue("Cast bar" in B.GAME_UI.rows && B.GAME_UI.tab == T.TOP)   // the Controller session's new row lands on Top screen
        assertTrue("Frame limit" in B.FRAMES.rows)
    }

    @Test fun settingsViewDrawsItsOwnBlocksOnly() {
        assertEquals(listOf(B.MAP, B.CHAT), SettingsTabs.viewBlocks(T.HOME))   // under Home's section (HomeSettingsSection)
        assertTrue(SettingsTabs.viewBlocks(T.TOP).isEmpty())           // the game's elements
        assertEquals(listOf(B.GAME), SettingsTabs.viewBlocks(T.SYSTEM))   // + the companion's status (GameUiSection)
        assertEquals(listOf(B.CONTROLLER, B.PAUSE_WHEEL, B.ITEM_WHEEL, B.SPELL_WHEEL, B.CLASS_LEFT, B.CLASS_RIGHT, B.SMART_Y),
            SettingsTabs.viewBlocks(T.CONTROLLER))
    }

    /** f-radial-settings: each wheel its own heading, naming the button that opens it, and its own open / pick pair */
    @Test fun eachWheelHasItsOwnCategory() {
        for ((b, button) in listOf(B.PAUSE_WHEEL to "Start", B.ITEM_WHEEL to "D-pad Up", B.CLASS_LEFT to "LB + LT", B.CLASS_RIGHT to "RB + RT"))
            assertTrue(b.heading, button in b.heading)
        for (b in listOf(B.PAUSE_WHEEL, B.ITEM_WHEEL, B.SPELL_WHEEL, B.CLASS_LEFT, B.CLASS_RIGHT)) {
            assertEquals(T.CONTROLLER, b.tab)
            assertEquals(2, b.rows.size)
            assertTrue(b.rows[0].endsWith(": open") && b.rows[1].endsWith(": pick"))
            for (r in b.rows) assertTrue("$r has a picture", SettingsPreview.has(r))
        }
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

    @Test fun twentyTwoRowsAndTheMovingOnes() {          // f-radial-settings: + the spell wheel's own pair
        assertEquals(26, SettingsPreview.ROWS.size)
        assertTrue(SettingsPreview.animated("Swing timer") && SettingsPreview.animated("Frame limit") && !SettingsPreview.animated("Button glyphs"))
    }
}

/** test40 ("the tabs seem way too small for the text of each one"): every tab as wide as its label, a strip that scrolls when too wide */
class SettingsTabBarLayoutTest {
    private val w: (String) -> Float = { it.length * 15f }          // a stand-in font: 15 px a letter

    @Test fun everyLabelFitsItsTab() {
        val labels = SettingsTabs.Tab.values().map { it.label }
        val lay = SettingsTabBar.layout(labels, 1080f, 0, w)
        for ((i, l) in labels.withIndex()) {
            val (x0, x1) = lay.xs[i]
            assertTrue(l, x1 - x0 >= w(l) + 2f * SettingsTabBar.TAB_PAD)
            if (i > 0) assertEquals(SettingsTabBar.TAB_GAP, x0 - lay.xs[i - 1].second, 1e-3f)
        }
        assertTrue(lay.total <= 1080f + 1e-3f); assertEquals(0f, lay.offset)
    }

    @Test fun tooWideScrollsToTheChosenTab() {
        val labels = List(9) { "Long tab name $it" }
        val avail = 900f
        for (chosen in labels.indices) {
            val lay = SettingsTabBar.layout(labels, avail, chosen, w)
            assertTrue(lay.total > avail)
            val (x0, x1) = lay.xs[chosen]
            assertTrue("tab $chosen shows whole", x0 - lay.offset >= 0f && x1 - lay.offset <= avail)
            assertTrue(lay.offset in 0f..(lay.total - avail))
        }
        assertEquals(0f, SettingsTabBar.layout(labels, avail, 0, w).offset)
        assertEquals(SettingsTabBar.layout(labels, avail, 8, w).total - avail, SettingsTabBar.layout(labels, avail, 8, w).offset, 1e-3f)
    }
}
