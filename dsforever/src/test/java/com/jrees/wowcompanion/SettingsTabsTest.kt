package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** f-settings-tabs, off-device: the Settings room's tabs as data (every row on exactly one tab, the user's approved grouping) */
private typealias T = SettingsTabs.Tab
private typealias B = SettingsTabs.Block
private typealias G = SettingsTabs.Group

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
        // the world markers: off by default; a fresh add-on showing them gets WM_OFF once the rest is as chosen
        assertEquals(false, GameUi.swWant(GameUi.SW_WM))
        val wmCols = cols + mapOf("WM_OFF" to 8, "WM_ON" to 9)
        val wm = GameUi.Sw("uisw", wmCols, false, false, true, true, mapOf("player" to true, "target" to true, "np" to true, "prd" to true), wm = true)
        val n4 = GameUi.swNext(wm) { (defaults + (GameUi.SW_WM to false))[it]!! }
        assertEquals(GameUi.SW_WM to false, n4); assertEquals(8, GameUi.swCol(wm, n4!!.first, n4.second))
        // an add-on without a part (null): never tapped for it
        val older = GameUi.Sw("uisw", cols, null, null, null, null, emptyMap())
        assertEquals(null, GameUi.swNext(older) { defaults[it]!! })
    }

    /** the six tabs (the user's approved layout, 2026-11-12): QOL and System are gone, Home is "Bottom screen" */
    @Test fun sixTabsInTheApprovedOrder() {
        assertEquals(listOf("General", "Bottom screen", "Top screen", "Controller", "Add-ons", "Performance"), SettingsTabs.visible.map { it.label })
        assertEquals(6, T.values().size)
        assertTrue(T.values().none { it.hidden })
    }

    /** every group in its tab, in the approved order (QuestTrail's only while a copy is installed, as before) */
    @Test fun groupsInTheApprovedOrder() {
        assertEquals(listOf("Look", "Account", "Help"), SettingsTabs.groups(T.GENERAL).map { it.heading })
        // GitHub #43 cb3 (the user: "the tap to cast bar should get its own bottom screen section")
        assertEquals(listOf("Home", "Tap-to-cast bar", "Map", "Chat"), SettingsTabs.groups(T.BOTTOM).map { it.heading })
        assertEquals(listOf("Your HUD", "Target", "Party", "Navigation", "Game windows"), SettingsTabs.groups(T.TOP).map { it.heading })
        assertEquals(listOf("Buttons", "Wheels", "Smart Y", "Combat helpers"), SettingsTabs.groups(T.CONTROLLER).map { it.heading })
        assertEquals(listOf("Polestar", "Spoken", "Manners"), SettingsTabs.groups(T.ADDONS).map { it.heading })   // QuestTrail: not installed
        assertEquals(listOf("Frames", "Memory", "Indoor maps", "Streaming"), SettingsTabs.groups(T.PERFORMANCE).map { it.heading })
        for (g in G.values()) assertTrue("${g.heading} has blocks", B.values().any { it.group == g })
    }

    /** every row's tab and group, exactly as approved (the D-pad walks them in this order) */
    @Test fun everyRowInItsApprovedGroup() {
        val want: Map<G, List<String>> = mapOf(
            G.LOOK to listOf("Theme", "Language"),
            G.ACCOUNT to listOf("Battle.net login", "Game account"),
            G.HELP to listOf("Problem report", "Reload game UI", "Status"),
            // the clock extras (the user's picks 2026-11-13, feat/item-cooldowns): one row each, before the toolbar
            G.HOME to listOf("Controller on Home", "Combat layout", "Swing timer", "Focused quest", "Hearthstone", "Class teleport", "Gold",
                "Free bag slots", "Ammo", "DS WoW toolbar"),          // feat/clock-extras-v2: Ammo
            G.CAST_BAR to listOf("Tap-to-cast bar"),
            G.MAP to listOf("Minimap zoom outdoors", "Minimap zoom indoors", "World map"),
            G.CHAT to listOf("Message input"),
            G.HUD to listOf("Resource bar", "Pet bar", "HUD height", "HUD width", "HUD scale"),
            G.TARGET to listOf("Target bar", "Target health text", "Target of target", "Aggro tint", "Enemy nameplates", "Friendly plates"),
            G.PARTY to listOf("DS party frames", "Party frames"),
            G.NAVIGATION to listOf("Compass bar", "Compass follows the camera", "World markers", "Polestar on the top screen"),
            G.WINDOWS to listOf("Minimap", "Quest tracker", "Chat window", "NPC windows", "Loot window", "Game tooltips", "Cast bar",
                "Player frame", "Target frame", "Controller bars, buffs and XP"),
            G.BUTTONS to listOf("ABXY location", "Button glyphs"),
            G.WHEELS to listOf("Pause menu: open", "Pause menu: pick", "Item wheel: open", "Item wheel: pick", "Spell wheel: open",
                "Spell wheel: pick", "Left class radial: open", "Left class radial: pick", "Right class radial: open", "Right class radial: pick"),
            G.SMART_Y to listOf("Smart Y"),
            G.COMBAT to listOf("Party mode", "Smart Res", "Start attack on hostile target", "Start the attack when a spell is cast", "Combat Auto Shot"),
            G.POLESTAR to ThirdPartyAddons.POLESTAR.rowWords() + "Quest tracking",
            G.SPOKEN to ThirdPartyAddons.SPOKEN.rowWords(),
            G.QUESTTRAIL to ThirdPartyAddons.QUESTTRAIL.rowWords(),
            G.MANNERS to ThirdPartyAddons.MANNERS.rowWords(),
            G.FRAMES to listOf("Frame limit", "Lossless.dll", "Frame generation", "Multiplier", "Flow scale", "Performance mode", "Restart the game"),
            G.MEMORY to listOf("Lighter mode", "Low-memory graphics"),
            G.INDOOR to listOf("Build in the background", "Progress", "Route data"),
            G.STREAMING to listOf("Stream settings"))
        for (g in G.values()) assertEquals(g.heading, want[g], B.values().filter { it.group == g }.flatMap { it.rows })
        // and each group's tab
        val tabOf = mapOf(T.GENERAL to listOf(G.LOOK, G.ACCOUNT, G.HELP), T.BOTTOM to listOf(G.HOME, G.CAST_BAR, G.MAP, G.CHAT),
            T.TOP to listOf(G.HUD, G.TARGET, G.PARTY, G.NAVIGATION, G.WINDOWS), T.CONTROLLER to listOf(G.BUTTONS, G.WHEELS, G.SMART_Y, G.COMBAT),
            T.ADDONS to listOf(G.POLESTAR, G.SPOKEN, G.QUESTTRAIL, G.MANNERS), T.PERFORMANCE to listOf(G.FRAMES, G.MEMORY, G.INDOOR, G.STREAMING))
        for ((t, gs) in tabOf) assertEquals(t.label, gs, G.values().filter { it.tab == t })
        // who draws the rows that left their old view: Home's section still draws its attack row, GameUiSection the Top screen rows
        assertEquals(SettingsTabs.Drawer.HOME, B.ATTACK_CAST.drawer)
        assertEquals(SettingsTabs.Drawer.HOME, B.CAST_BAR.drawer)
        assertEquals(SettingsTabs.Drawer.GAME_UI, B.COMPANION.drawer)
        assertEquals(SettingsTabs.Drawer.VIEW, B.POLESTAR_TOP.drawer)
        assertEquals(SettingsTabs.Drawer.VIEW, B.QUEST_TRACKING.drawer)
    }

    /**
     * NO ROW LOST: the full row set before the six tabs (the seven tabs' blocks, plus the rows GameUiSection's QOL part drew that its
     * blocks never listed: Start attack on hostile target, Combat Auto Shot, Party mode) is the row set now, each once
     */
    @Test fun noRowLost() {
        val before = listOf("Theme", "Controller on Home", "Combat layout", "Swing timer", "Focused quest", "Start the attack when a spell is cast",
            "DS WoW toolbar", "ABXY location", "Button glyphs", "Pause menu: open", "Pause menu: pick", "Item wheel: open", "Item wheel: pick",
            "Spell wheel: open", "Spell wheel: pick", "Left class radial: open", "Left class radial: pick", "Right class radial: open",
            "Right class radial: pick", "Smart Y", "Resource bar", "Pet bar", "HUD height", "HUD width", "HUD scale", "Compass bar",
            "World markers", "Compass follows the camera", "Aggro tint", "Minimap", "Controller bars, buffs and XP", "Quest tracker",
            "Chat window", "NPC windows", "Loot window", "Player frame", "Target bar", "Target health text", "Target of target", "Target frame",
            "DS party frames", "Party frames", "Cast bar", "Enemy nameplates", "Game tooltips", "Minimap zoom outdoors", "Minimap zoom indoors",
            "Quest tracking", "World map", "Message input") +
            ThirdPartyAddons.SPOKEN.rowWords() + ThirdPartyAddons.POLESTAR.rowWords() + ThirdPartyAddons.QUESTTRAIL.rowWords() +
            ThirdPartyAddons.MANNERS.rowWords() +
            listOf("Frame limit", "Lossless.dll", "Frame generation", "Multiplier", "Flow scale", "Performance mode", "Restart the game",
                "Build in the background", "Progress", "Route data", "Language", "Reload game UI", "Battle.net login", "Problem report",
                "Lighter mode", "Low-memory graphics", "Stream settings", "Status") +
            // drawn before but in no block's list: GameUiSection's QOL Combat / Party groups, Add-ons > Polestar's top-screen row
            listOf("Start attack on hostile target", "Combat Auto Shot", "Party mode", "Polestar on the top screen") +
            // m150 / hub47 (the user, 2026-11-13): Smart Res, new under Party mode
            listOf("Smart Res") +
            // added since: GitHub #43's tap-to-cast bar (Bottom screen > Home; cb3: its own group, Bottom screen > Tap-to-cast bar)
            listOf("Tap-to-cast bar") +
            // new since the six tabs: General > Account > Game account (#47)
            listOf("Game account") +
            // added since: the clock extras (feat/item-cooldowns)
            listOf("Hearthstone", "Class teleport", "Gold", "Free bag slots") +
            // hub52 (the user, 2026-11-13: "maybe we should set this as a toggle defaulted to off"): Top > Target
            listOf("Friendly plates") +
            // added since: the clock band's ammo (feat/clock-extras-v2)
            listOf("Ammo")
        val now = B.values().flatMap { it.rows }
        assertEquals(before.toSet(), now.toSet())
        assertEquals("a row in two places", now.size, now.toSet().size)
    }

    /** the old seven tabs' saved names open the tab their rows went to (most of them) */
    @Test fun oldSavedTabNamesMap() {
        assertEquals(T.BOTTOM, SettingsTabs.fromSaved("HOME"))
        assertEquals(T.BOTTOM, SettingsTabs.fromSaved("ROOMS"))
        assertEquals(T.TOP, SettingsTabs.fromSaved("QOL"))
        assertEquals(T.GENERAL, SettingsTabs.fromSaved("SYSTEM"))
        assertEquals(T.TOP, SettingsTabs.fromSaved("TOP"))
        assertEquals(T.CONTROLLER, SettingsTabs.fromSaved("CONTROLLER"))
        assertEquals(T.ADDONS, SettingsTabs.fromSaved("ADDONS"))
        assertEquals(T.PERFORMANCE, SettingsTabs.fromSaved("PERFORMANCE"))
        assertEquals(T.GENERAL, SettingsTabs.fromSaved(null))
        assertEquals(T.GENERAL, SettingsTabs.fromSaved("NOT_A_TAB"))
        for (t in T.values()) assertEquals(t, SettingsTabs.fromSaved(t.name))
    }

    /** the user's pick: showing a tab opens its FIRST group, the rest folded */
    @Test fun firstGroupOpenOnShow() {
        for (t in T.values()) if (t.folds) assertEquals(t.label, setOf(SettingsTabs.groups(t).first()), SettingsTabs.openOnShow(t))
        // the user, 2026-11-12: "the general tab doesnt need the folding sections": every General group always open
        assertEquals(false, T.GENERAL.folds)
        assertEquals(setOf(G.LOOK, G.ACCOUNT, G.HELP), SettingsTabs.openOnShow(T.GENERAL))
        assertEquals(T.values().filter { it != T.GENERAL }, T.values().filter { it.folds })
        assertEquals(setOf(G.HUD), SettingsTabs.openOnShow(T.TOP))
    }

    /** 2026-11-09: Performance on the row (Lossless frame generation), last, with its four groups */
    @Test fun performanceIsOnTheRow() {
        assertTrue(T.PERFORMANCE in SettingsTabs.visible)
        assertEquals(T.PERFORMANCE, SettingsTabs.step(T.ADDONS, 1))
        assertEquals(listOf(B.FRAMES, B.LIGHTER, B.INDOOR, B.STREAMING), SettingsTabs.blocks(T.PERFORMANCE))
    }

    /** moving a row changes only the tab that draws it: the ids its taps go by and the hub columns are the ones they were */
    @Test fun movedRowsKeepTheirChannels() {
        assertEquals("castbar", GameUi.CASTBAR_ID); assertEquals("hudmode", GameUi.HUD_ID)
        assertEquals("hudheight", GameUi.HUD_H_ID); assertEquals("hudwidth", GameUi.HUD_W_ID); assertEquals("hudscale", GameUi.HUD_S_ID)
        assertEquals("compass", GameUiSection.COMPASS_ID); assertEquals("compasscam", GameUiSection.COMPASS_CAM_ID)
        assertEquals("hidetips", GameUiSection.TIPS_ID); assertEquals("aggrorings", GameUiSection.RINGS_ID)
        assertEquals(listOf(32, 33, 34, 35, 36, 37, 38, 39), listOf(CastBarPolicy.COL_SHOW, CastBarPolicy.COL_HIDE, CastBarPolicy.HUD_SHOW,
            CastBarPolicy.HUD_HIDE, CastBarPolicy.HUD_LOWER, CastBarPolicy.HUD_HIGHER, CastBarPolicy.HUD_HDEF, CastBarPolicy.HUD_SEL))
        assertEquals(listOf("off", "low", "high"), Settings.AVOID_DANGER)
        // Top screen > Game windows: the approved order, by the rows' own ids
        assertEquals(listOf("minimap", "tracker", "chat", "dialogs", "loot", "hidetips", "castbar", GameUi.PLAYER_ID, GameUi.TARGET_ID, "hud"),
            GameUiSection.WINDOWS_ORDER)
    }

    /** 1.0.9 Add-ons: every third-party add-on has its group there, its rows its own (title, modules, options) */
    @Test fun addOnsHaveTheirOwnGroups() {
        val shown = if (Settings.ROUTE_MODES_ON) listOf(B.ADDON_POLESTAR, B.QUEST_TRACKING, B.ADDON_SPOKEN, B.ADDON_MANNERS)
            else listOf(B.QUEST_TRACKING, B.ADDON_SPOKEN, B.ADDON_MANNERS)
        assertEquals(shown, SettingsTabs.blocks(T.ADDONS))
        assertEquals(shown, SettingsTabs.viewBlocks(T.ADDONS))
        for (a in ThirdPartyAddons.ALL) assertTrue(a.title, SettingsTabs.Block.values().any { SettingsTabs.addonOf(it) == a })
        assertEquals(listOf("Spoken", "Spoken: Quests", "Spoken: Zones", "Spoken: Books", "Stop speech on B, Decline or close",
            "Stop speech on Accept"), B.ADDON_SPOKEN.rows)
    }

    @Test fun everyRowIsInExactlyOneGroup() {
        val rows = B.values().flatMap { b -> b.rows.map { it to b.group } }
        val dup = rows.groupBy { it.first }.filter { it.value.size > 1 }.keys
        assertTrue("rows in more than one group: $dup", dup.isEmpty())
    }

    @Test fun everyTabHasRows() {
        for (t in T.values()) assertTrue("$t has no rows", SettingsTabs.blocks(t).flatMap { it.rows }.isNotEmpty())
    }

    @Test fun settingsViewDrawsItsOwnBlocksOnly() {
        assertEquals(listOf(B.THEME, B.LANGUAGE, B.ACCOUNT, B.GAME_ACCOUNT, B.HELP), SettingsTabs.viewBlocks(T.GENERAL))   // + the status (GameUiSection)
        assertEquals(listOf(B.MAP, B.CHAT), SettingsTabs.viewBlocks(T.BOTTOM))                            // under Home's section
        assertEquals(listOf(B.POLESTAR_TOP), SettingsTabs.viewBlocks(T.TOP))                              // the rest: GameUiSection
        assertEquals(listOf(B.CONTROLLER, B.PAUSE_WHEEL, B.ITEM_WHEEL, B.SPELL_WHEEL, B.CLASS_LEFT, B.CLASS_RIGHT, B.SMART_Y),
            SettingsTabs.viewBlocks(T.CONTROLLER))
        // only the wheels keep a sub-heading of their own inside their group
        assertEquals(setOf(B.PAUSE_WHEEL, B.ITEM_WHEEL, B.SPELL_WHEEL, B.CLASS_LEFT, B.CLASS_RIGHT), B.values().filter { it.subHead }.toSet())
    }

    /** f-radial-settings: each wheel its own heading, naming the button that opens it, and its own open / pick pair */
    @Test fun eachWheelHasItsOwnCategory() {
        for ((b, button) in listOf(B.PAUSE_WHEEL to "Start", B.ITEM_WHEEL to "D-pad Up", B.CLASS_LEFT to "LB + LT", B.CLASS_RIGHT to "RB + RT"))
            assertTrue(b.heading, button in b.heading)
        for (b in listOf(B.PAUSE_WHEEL, B.ITEM_WHEEL, B.SPELL_WHEEL, B.CLASS_LEFT, B.CLASS_RIGHT)) {
            assertEquals(T.CONTROLLER, b.tab)
            assertEquals(G.WHEELS, b.group)
            assertEquals(2, b.rows.size)
            assertTrue(b.rows[0].endsWith(": open") && b.rows[1].endsWith(": pick"))
            for (r in b.rows) assertTrue("$r has a picture", SettingsPreview.has(r))
        }
    }

    @Test fun lAndRWrap() {
        assertEquals(T.PERFORMANCE, SettingsTabs.step(T.GENERAL, -1))
        assertEquals(T.GENERAL, SettingsTabs.step(T.PERFORMANCE, 1))
        assertEquals(T.BOTTOM, SettingsTabs.step(T.GENERAL, 1))
        assertEquals(T.CONTROLLER, SettingsTabs.step(T.TOP, 1))
        var t = T.GENERAL
        repeat(SettingsTabs.visible.size) { t = SettingsTabs.step(t, 1) }
        assertEquals(T.GENERAL, t)
    }
}

/** f-settings-tabs phase 2: the "What it does" pictures belong to bottom-screen rows (the user's rule: none on the top screen's Show/Hide) */
class SettingsPreviewRowsTest {
    private val allRows = SettingsTabs.Block.values().flatMap { b -> b.rows.map { it to b.tab } }.toMap()

    @Test fun everyPictureIsARowOfABottomScreenTab() {
        for (id in SettingsPreview.ROWS.keys) {
            val tab = allRows[id]
            assertTrue("$id is not a Settings row", tab != null)
            assertTrue("$id is on $tab", tab != SettingsTabs.Tab.TOP)
        }
    }

    @Test fun noPictureOnTheTopScreensShowHide() =
        assertTrue(SettingsTabs.Block.values().filter { it.tab == SettingsTabs.Tab.TOP }.flatMap { it.rows }.none { SettingsPreview.has(it) })

    /** 2026-11-13 (the user: "a lot of these new options and settings need visuals to accompany them in the settings menus"): every
     *  Bottom screen row has its picture (a new row there needs one), and the Controller tab's combat helpers have theirs */
    @Test fun everyBottomScreenRowHasAPicture() {
        val missing = SettingsTabs.Block.values().filter { it.tab == SettingsTabs.Tab.BOTTOM }.flatMap { it.rows }.filter { !SettingsPreview.has(it) }
        assertTrue("Bottom screen rows with no picture: $missing", missing.isEmpty())
        for (r in listOf("Smart Y", "Party mode", "Smart Res", "Start attack on hostile target", "Combat Auto Shot"))
            assertTrue("$r has a picture", SettingsPreview.has(r))
    }

    /** every value of the new rows has its title and words (the panel shows the one chosen) */
    @Test fun theNewRowsWordsFollowTheValue() {
        for (side in 0..3) assertTrue(SettingsPreview.caption("Tap-to-cast bar", SettingsPreview.State(castBar = side)).isNotEmpty())
        assertEquals("Tap-to-cast bar: Both", SettingsPreview.title("Tap-to-cast bar", SettingsPreview.State(castBar = 3)))
        assertEquals("Ammo: Off", SettingsPreview.title("Ammo", SettingsPreview.State(clockAmmo = false)))
        assertEquals("Combat Auto Shot: Smart", SettingsPreview.title("Combat Auto Shot", SettingsPreview.State(autoShot = 2)))
        assertEquals("Smart Y: -", SettingsPreview.title("Smart Y", SettingsPreview.State()))
        for (y in listOf(null, true, false)) for (sh in 0..2) for (b in listOf(true, false)) {
            val s = SettingsPreview.State(smartY = y, autoShot = sh, clockHearth = b, clockTeleport = b, clockGold = b, clockBags = b,
                clockAmmo = b, worldMapAll = b, partyMode = b, smartRes = b, attackHostile = b)
            for (id in SettingsPreview.ROWS.keys) {
                assertTrue(id, SettingsPreview.title(id, s).length in 3..40)
                assertTrue(id, SettingsPreview.caption(id, s).isNotEmpty() && SettingsPreview.caption(id, s).all { it.length <= 46 })
            }
        }
    }

    @Test fun everyPictureHasATitleAndWords() {
        val s = SettingsPreview.State(startAttack = true, toolbar = true)
        for (id in SettingsPreview.ROWS.keys) {
            assertTrue(id, SettingsPreview.title(id, s).length in 3..40)
            assertTrue(id, SettingsPreview.caption(id, s).isNotEmpty() && SettingsPreview.caption(id, s).all { it.length <= 46 })
        }
    }

    @Test fun twentyTwoRowsAndTheMovingOnes() {          // f-radial-settings: + the spell wheel's own pair; 2026-11-13: + 12
        assertEquals(38, SettingsPreview.ROWS.size)
        assertTrue(SettingsPreview.animated("Swing timer") && SettingsPreview.animated("Frame limit") && !SettingsPreview.animated("Button glyphs"))
    }
}

/** test40 ("the tabs seem way too small for the text of each one"): every tab as wide as its label, a strip that scrolls when too wide */
class SettingsTabBarLayoutTest {
    private val w: (String) -> Float = { it.length * 15f }          // a stand-in font: 15 px a letter

    @Test fun everyLabelFitsItsTab() {
        val labels = SettingsTabs.visible.map { it.label }             // the tabs the bar draws
        val lay = SettingsTabBar.layout(labels, 1080f, 0, w)
        for ((i, l) in labels.withIndex()) {
            val (x0, x1) = lay.xs[i]
            assertTrue(l, x1 - x0 >= w(l) + 2f * SettingsTabBar.TAB_PAD)
            if (i > 0) assertEquals(SettingsTabBar.TAB_GAP, x0 - lay.xs[i - 1].second, 1e-3f)
        }
        assertEquals(0f, lay.offset)                                    // six tabs may scroll; the first opens unscrolled
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
