package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** f-settings-tabs, off-device: the Settings room's tabs as data (every row on exactly one tab, the user's approved grouping) */
private typealias T = SettingsTabs.Tab
private typealias B = SettingsTabs.Block

class SettingsTabsTest {
    /** hub17 THE HUD-MODE SPLIT: the user's defaults (player / target frames hidden, nameplates on, the resource bar on), and the taps:
     *  a switch is tapped when the game's word differs from the choice or the game has not had it since its /reload, absolutely */
    @Test fun hudSplitDefaultsAndTaps() {
        val cols = mapOf("PLAYER_SHOW" to 0, "PLAYER_HIDE" to 1, "TARGET_SHOW" to 2, "TARGET_HIDE" to 3, "NP_OFF" to 4, "NP_ON" to 5,
            "PRD_OFF" to 6, "PRD_ON" to 7)
        val defaults = mapOf(GameUi.SW_PLAYER to false, GameUi.SW_TARGET to false, GameUi.SW_NP to true, GameUi.SW_PRD to true)
        for ((p, v) in defaults) assertEquals(p, v, GameUi.swWant(p))      // no character known: the defaults
        // before any tap the add-on shows the frames and wants neither: player first, hidden (column 1)
        val fresh = GameUi.Sw("uisw", cols, player = true, target = true, np = false, prd = false, set = emptyMap())
        val n1 = GameUi.swNext(fresh) { defaults[it]!! }
        assertEquals(GameUi.SW_PLAYER to false, n1); assertEquals(1, GameUi.swCol(fresh, n1!!.first, n1.second))
        // all as chosen and set since the reload: nothing to tap
        val done = GameUi.Sw("uisw", cols, false, false, true, true, mapOf("player" to true, "target" to true, "np" to true, "prd" to true))
        assertEquals(null, GameUi.swNext(done) { defaults[it]!! })
        // as chosen but not set since a /reload (an older app's HUD mode may move it): tapped again, absolutely
        val reloaded = done.copy(set = mapOf("player" to true, "target" to true, "np" to true))
        val n3 = GameUi.swNext(reloaded) { defaults[it]!! }
        assertEquals(GameUi.SW_PRD to true, n3); assertEquals(7, GameUi.swCol(reloaded, n3!!.first, n3.second))
        // an add-on without a part (null): never tapped for it
        val older = GameUi.Sw("uisw", cols, null, null, null, null, emptyMap())
        assertEquals(null, GameUi.swNext(older) { defaults[it]!! })
    }

    /** test40: the Rooms tab is gone (the user: "move those settings to the home tab and put them under home module settings") */
    @Test fun sixTabsInTheApprovedOrder() =
        // the housekeeping pass: QOL after Controller; Performance hidden (still a tab of data: its settings keep their values)
        assertEquals(listOf("Home", "Controller", "QOL", "Top screen", "Add-ons", "System"), SettingsTabs.visible.map { it.label })

    /** "Hide it all": Performance is never on the row and never stepped onto, whichever way and from wherever */
    @Test fun performanceIsNeverReachable() {
        assertTrue(T.PERFORMANCE.hidden)
        assertTrue(T.PERFORMANCE !in SettingsTabs.visible)
        for (start in SettingsTabs.visible) for (dir in listOf(-1, 1)) {
            var t = start
            repeat(10) { t = SettingsTabs.step(t, dir); assertTrue("$start $dir reached $t", t != T.PERFORMANCE) }
        }
        assertEquals(listOf(B.FRAMES, B.INDOOR), SettingsTabs.blocks(T.PERFORMANCE))     // its rows are kept, as data
    }

    /** QOL "Build it": Smart Y, then HUD / Navigation / Combat, each row in the approved order (the D-pad walks them in this order) */
    @Test fun qolTabInTheApprovedOrder() {
        assertEquals(listOf(B.SMART_Y, B.QOL_HUD, B.QOL_NAV, B.QOL_COMBAT), SettingsTabs.blocks(T.QOL))
        assertEquals(listOf("Resource bar", "HUD height", "HUD width", "HUD scale"), B.QOL_HUD.rows)
        assertEquals(listOf("Compass bar", "Compass follows the camera", "Avoid dangerous areas"), B.QOL_NAV.rows)
        assertEquals(listOf("Aggro tint"), B.QOL_COMBAT.rows)
        assertEquals(listOf(B.SMART_Y), SettingsTabs.viewBlocks(T.QOL))   // Smart Y by SettingsView, the groups by GameUiSection's QOL part
        // Top screen keeps only the game's own windows
        assertEquals(listOf("Minimap", "Controller bars, buffs and XP", "Quest tracker", "Chat window", "NPC windows", "Loot window",
            "Player frame", "Target frame", "Cast bar", "Enemy nameplates", "Hide game tooltips"), B.GAME_UI.rows)    // + hub17's split
        assertTrue("Avoid dangerous areas" !in B.MAP.rows)
    }

    /** moving a row changes only the tab that draws it: the ids its taps go by and the hub columns are the ones they were */
    @Test fun movedRowsKeepTheirChannels() {
        assertEquals("castbar", GameUi.CASTBAR_ID); assertEquals("hudmode", GameUi.HUD_ID)
        assertEquals("hudheight", GameUi.HUD_H_ID); assertEquals("hudwidth", GameUi.HUD_W_ID); assertEquals("hudscale", GameUi.HUD_S_ID)
        assertEquals("compass", GameUiSection.COMPASS_ID); assertEquals("compasscam", GameUiSection.COMPASS_CAM_ID)
        assertEquals("hidetips", GameUiSection.TIPS_ID); assertEquals("aggrorings", GameUiSection.RINGS_ID)
        assertEquals(listOf(32, 33, 34, 35, 36, 37, 38, 39), listOf(CastBarPolicy.COL_SHOW, CastBarPolicy.COL_HIDE, CastBarPolicy.HUD_SHOW,
            CastBarPolicy.HUD_HIDE, CastBarPolicy.HUD_LOWER, CastBarPolicy.HUD_HIGHER, CastBarPolicy.HUD_HDEF, CastBarPolicy.HUD_SEL))
        assertEquals(listOf("off", "low", "high"), Settings.AVOID_DANGER)       // QOL's danger row sets the same value Home > Map did
    }

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
        assertTrue("Cast bar" in B.GAME_UI.rows && B.GAME_UI.tab == T.TOP)   // the HUD-mode split: the cast bar back on Top screen
        assertTrue("Frame limit" in B.FRAMES.rows)
    }

    @Test fun settingsViewDrawsItsOwnBlocksOnly() {
        assertEquals(listOf(B.MAP, B.CHAT), SettingsTabs.viewBlocks(T.HOME))   // under Home's section (HomeSettingsSection)
        assertTrue(SettingsTabs.viewBlocks(T.TOP).isEmpty())           // the game's elements
        assertEquals(listOf(B.GAME), SettingsTabs.viewBlocks(T.SYSTEM))   // + the companion's status (GameUiSection)
        assertEquals(listOf(B.CONTROLLER, B.PAUSE_WHEEL, B.ITEM_WHEEL, B.SPELL_WHEEL, B.CLASS_LEFT, B.CLASS_RIGHT),
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
        assertEquals(T.QOL, SettingsTabs.step(T.CONTROLLER, 1))
        assertEquals(T.SYSTEM, SettingsTabs.step(T.ADDONS, 1))           // over the hidden Performance
        repeat(SettingsTabs.visible.size) { t = SettingsTabs.step(t, 1) }
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
        val labels = SettingsTabs.visible.map { it.label }             // the tabs the bar draws (Performance hidden)
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
