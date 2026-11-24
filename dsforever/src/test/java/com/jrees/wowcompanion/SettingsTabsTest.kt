package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // 2026-11-19: Reset (folds on its own) above Support the Project (a headless group, the page's last row)
        assertEquals(listOf("Look", "Account", "Help", "Reset", "Support"), SettingsTabs.groups(T.GENERAL).map { it.heading })
        // GitHub #43 cb3 (the user: "the tap to cast bar should get its own bottom screen section")
        // party sizing (the user's pick 2026-11-23: "Settings + Edit Home"): Party, the party list's tile, under Home
        assertEquals(listOf("Home", "Party list", "Tap-to-cast bar", "Map", "Chat"), SettingsTabs.groups(T.BOTTOM).map { it.heading })
        // the layout editor (the user, 2026-11-16: "we need to make a way to basically edit the UI locations on the top screen from our UI
        // menu"; the design's pick): a first group "Layout" on Top screen
        assertEquals(listOf("Layout", "Your HUD", "Target", "Party", "Navigation", "Game windows"), SettingsTabs.groups(T.TOP).map { it.heading })
        assertEquals(listOf("Buttons", "Wheels", "Smart Y", "Combat helpers"), SettingsTabs.groups(T.CONTROLLER).map { it.heading })
        // QuestTrail: not installed. First the Import button's headless group (the user, 2026-11-16: "Import addons should be a single
        // button at the top of the list"; its heading is never drawn)
        // the beautify pass (2026-11-17): the third-party add-ons are ONE headless group of picture cards (AddonTiles)
        assertEquals(listOf("Import add-ons", "Add-ons", "Imported"), SettingsTabs.groups(T.ADDONS).map { it.heading })
        // beautify (critic C6): Performance is ONE headless group, its tiles and the Stream settings row
        assertEquals(listOf("Performance"), SettingsTabs.groups(T.PERFORMANCE).map { it.heading })
        for (g in G.values()) assertTrue("${g.heading} has blocks", B.values().any { it.group == g })
    }

    /** every row's tab and group, exactly as approved (the D-pad walks them in this order) */
    @Test fun everyRowInItsApprovedGroup() {
        val want: Map<G, List<String>> = mapOf(
            G.LOOK to listOf("Theme", "Language", "Voice typing", "Swap screens", "Restart DS Forever"),     // 11-16: Voice typing     // #232: Swap screens (only with a second screen)
            // the user, 2026-11-14 ("build it"): Windows ID under Game account
            G.ACCOUNT to listOf("Battle.net login", "Game account", "Windows ID", "Browser login"),
            // the user's pick 2026-11-23 ("Both (Recommended)"): the opt-in test graphics driver, its own group under Account
            G.GRAPHICS to listOf("Graphics driver"),
            G.HELP to listOf("Problem report", "Reload game UI", "Status"),
            // 2026-11-19: Reset to defaults, four buttons; Support the Project last, under them
            G.RESET to listOf("Reset settings", "Reset layouts", "Reset skills", "Reset everything"),
            G.SUPPORT to listOf("Support the Project"),
            // the clock extras (the user's picks 2026-11-13, feat/item-cooldowns): one row each, before the toolbar
            G.HOME to listOf("Controller on Home", "Combat layout", "Swing timer", "Focused quest", "Hearthstone", "Class teleport", "Gold",
                "Free bag slots", "Ammo", "Pet happiness", "DS WoW toolbar", "Spell rank badges", "Hold to talk", "On release"),   // feat/clock-extras-v2: Ammo; 11-16: hold to talk; 11-20: rank badges; feat/extras-polish: Pet happiness
            // party sizing (the user, 2026-11-23): the party list's tile's two picks (one "Size in fights" since test 1523)
            G.PARTY_LIST to listOf("Grows", "Size in fights"),
            G.CAST_BAR to listOf("Tap-to-cast bar", "Show in combat"),     // cb14 (D5b): the row renamed
            G.MAP to listOf("Minimap zoom outdoors", "Minimap zoom indoors", "World map"),
            G.CHAT to listOf("Message input"),
            // the layout editor's row; HUD height and HUD scale moved into its page (the width stays a row)
            G.LAYOUT to listOf("Edit layout"),
            G.HUD to listOf("Resource bar", "Pet bar", "HUD width"),
            G.TARGET to listOf("Target bar", "Target health text", "Target of target", "Aggro tint", "Enemy nameplates", "Friendly plates"),
            G.PARTY to listOf("DS party frames", "Party frames"),
            G.NAVIGATION to listOf("Compass bar", "Compass follows the camera", "World markers", "Polestar on the top screen"),
            G.WINDOWS to listOf("Minimap", "Quest tracker", "Chat window", "NPC windows", "Loot window", "Game tooltips", "Cast bar",
                "Player frame", "Target frame", "Controller UI", "Buffs", "XP bar",
                // hub61: the damage meter, the Cooldown Manager and its four parts
                "Damage meter", "Cooldown Manager", "Essential", "Utility", "Buff icons", "Buff bars"),
            G.BUTTONS to listOf("ABXY location", "Button glyphs", "Hold time", "B: pet recall"),   // 2026-11-24: hold to cast's hold time       // 2026-11-18: B: pet recall (was Smart pet / B: pet follow), a pet class only
            G.WHEELS to listOf("Pause menu: open", "Pause menu: pick", "Start over game windows", "Item wheel: open", "Item wheel: pick", "Spell wheel: open",
                "Spell wheel: pick", "Left class radial: open", "Left class radial: pick", "Right class radial: open", "Right class radial: pick"),
            G.SMART_Y to listOf("Smart Y"),
            G.COMBAT to listOf("Party mode", "Smart Res", "Start attack on hostile target", "Start the attack when a spell is cast", "Combat Auto Shot"),
            G.CARDS to ThirdPartyAddons.POLESTAR.rowWords() + "Quest tracking" + ThirdPartyAddons.SPOKEN.rowWords() +
                ThirdPartyAddons.MANNERS.rowWords() + ThirdPartyAddons.QUESTTRAIL.rowWords(),
            G.VOICE to listOf("Voice: English · 60 MB", "Voice: English · 148 MB", "Voice: other languages · 60 MB", "Voice: other languages · 148 MB"),
            // the user, 2026-11-16: "maybe we just do an import addons button then?"; then "Import addons should be a single button at
            // the top of the list": the button is its own headless group, first; Imported keeps only the list (rows named by the add-ons)
            G.IMPORT to listOf("Import add-ons"),
            G.IMPORTED to emptyList<String>(),
            G.FRAMES to listOf("Frame limit", "Lossless.dll", "Frame generation", "Multiplier", "Flow scale", "Performance mode", "Restart the game",
                "Lighter mode", "Low-memory graphics", "Home map: full detail", "Map style when zoomed out", "Build in the background", "Progress", "Route data",
                "Stream settings"))
        for (g in G.values()) assertEquals(g.heading, want[g], B.values().filter { it.group == g }.flatMap { it.rows })
        // and each group's tab
        val tabOf = mapOf(T.GENERAL to listOf(G.LOOK, G.ACCOUNT, G.GRAPHICS, G.HELP, G.RESET, G.SUPPORT), T.BOTTOM to listOf(G.HOME, G.PARTY_LIST, G.CAST_BAR, G.MAP, G.CHAT),
            T.TOP to listOf(G.LAYOUT, G.HUD, G.TARGET, G.PARTY, G.NAVIGATION, G.WINDOWS), T.CONTROLLER to listOf(G.BUTTONS, G.WHEELS, G.SMART_Y, G.COMBAT),
            T.ADDONS to listOf(G.IMPORT, G.CARDS, G.VOICE, G.IMPORTED), T.PERFORMANCE to listOf(G.FRAMES))
        for ((t, gs) in tabOf) assertEquals(t.label, gs, G.values().filter { it.tab == t })
        // who draws the rows that left their old view: Home's section still draws its attack row, GameUiSection the Top screen rows
        assertEquals(SettingsTabs.Drawer.HOME, B.ATTACK_CAST.drawer)
        assertEquals(SettingsTabs.Drawer.HOME, B.CAST_BAR.drawer)
        assertEquals(SettingsTabs.Drawer.GAME_UI, B.COMPANION.drawer)
        // 2026-11-16 (the tiles): Polestar's top-screen row is Navigation's third tile, drawn by Navigation's GameUiSection part
        assertEquals(SettingsTabs.Drawer.GAME_UI, B.POLESTAR_TOP.drawer)
        // the beautify pass (2026-11-17): Polestar's card draws it (its guide line, the same PolestarGuide choice)
        assertEquals(SettingsTabs.Drawer.ADDONS, B.QUEST_TRACKING.drawer)
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
                "Lighter mode", "Low-memory graphics", "Home map: full detail", "Map style when zoomed out", "Stream settings", "Status") +
            // drawn before but in no block's list: GameUiSection's QOL Combat / Party groups, Add-ons > Polestar's top-screen row
            listOf("Start attack on hostile target", "Combat Auto Shot", "Party mode", "Polestar on the top screen") +
            // m150 / hub47 (the user, 2026-11-13): Smart Res, new under Party mode
            listOf("Smart Res") +
            // added since: GitHub #43's tap-to-cast bar (Bottom screen > Home; cb3: its own group, Bottom screen > Tap-to-cast bar)
            listOf("Tap-to-cast bar") +
            // the user 2026-11-13 ("a settings toggle to make them Hide/stay in combat"): Bottom screen > Tap-to-cast bar > In combat
            // cb14 (the user, 2026-11-19, D5b): renamed "Show in combat" (the Controller room's In combat check box is the same setting)
            listOf("Show in combat") +
            // new since the six tabs: General > Account > Game account (#47)
            listOf("Game account") +
            // added since: the clock extras (feat/item-cooldowns)
            listOf("Hearthstone", "Class teleport", "Gold", "Free bag slots") +
            // hub52 (the user, 2026-11-13: "maybe we should set this as a toggle defaulted to off"): Top > Target
            listOf("Friendly plates") +
            // added since: the clock band's ammo (feat/clock-extras-v2)
            listOf("Ammo") +
            // the user's pick 2026-11-13 ("Make it a setting toggle defaulted to our wheel"): Controller > Pause menu (Start)
            listOf("Start over game windows") +
            // GitHub #232 (the user, 2026-11-14: build it): General > Look > Swap screens, its Restart under it after a change
            listOf("Swap screens", "Restart DS Forever") +
            // the user, 2026-11-14 ("build it", the BLZ51914400 wave): General > Account > Windows ID
            listOf("Windows ID") +
            // the user, 2026-11-16 ("a pet class specific option where pressing B in the out of combat home menu basically stops the pet
            // attacking"): Controller > Buttons
            listOf("B: pet recall") +                   // 2026-11-18: B: pet recall (was Smart pet, its attack half gone)
            // the user's pick 2026-11-24 (hold to cast, "At the hold time, Settings: 0.3/0.4/0.55 s"): Controller > Buttons
            listOf("Hold time") +
            // the user's pick 2026-11-16 (voice to text): General > Look > Voice typing
            listOf("Voice typing") +
            // the user, 2026-11-16 ("we could make the per languege downloads part of the addons folder"): Add-ons > Voice
            listOf("Voice: English · 60 MB", "Voice: English · 148 MB", "Voice: other languages · 60 MB", "Voice: other languages · 148 MB") +
            // the user, 2026-11-16 ("maybe we just do an import addons button then?"): Add-ons > Imported
            listOf("Import add-ons") +
            // the user, 2026-11-16 (hold to talk on the DS toolbar's Chat): Bottom screen > Home, under the toolbar
            listOf("Hold to talk", "On release") +
            // hub61 (the user, 2026-11-17: "DPS meters and also the cooldown manager"): Top screen > Game windows
            listOf("Damage meter", "Cooldown Manager", "Essential", "Utility", "Buff icons", "Buff bars") +
            // the user, 2026-11-18 (upstream v2.4.5's Browser login, opt-in): General > Account, under Windows ID
            listOf("Browser login") +
            // the user, 2026-11-19 ("a simple button at the bottom that says 'Support the Project'"): General > Help, last
            listOf("Support the Project") +
            // the user, 2026-11-19 ("reset to defaults buttons, split into four"): General > Reset
            listOf("Reset settings", "Reset layouts", "Reset skills", "Reset everything") +
            // the user, 2026-11-20 ("put that as a toggle in the settings, off by default"): Bottom screen > Home, after the toolbar
            listOf("Spell rank badges") +
            // the user's pick 2026-11-23 ("Both (Recommended)"): General > Graphics > Graphics driver (Recommended / the test drivers)
            listOf("Graphics driver") +
            // feat/extras-polish (the user, 2026-11-23: "we should add pet happiness to this and remove it from the top screen HUD"):
            // Bottom screen > Home, the clock extras' new row after Ammo
            listOf("Pet happiness") +
            // party sizing (the user, 2026-11-23): Bottom screen > Party, the party list's picks (test 1523's two fight rows one row:
            // "just consolidate that into one setting")
            listOf("Grows", "Size in fights")
        val now = B.values().flatMap { it.rows }
        // split-hud (the user, 2026-11-17: "we need to split out the Buffs controller UI and XP bar"): one row is three
        val split = before - "Controller bars, buffs and XP" + listOf("Controller UI", "Buffs", "XP bar")
        // the layout editor (the user, 2026-11-16): HUD height and HUD scale are its page's now (its own row "Edit layout" opens it)
        assertEquals((split - setOf("HUD height", "HUD scale") + "Edit layout").toSet(), now.toSet())
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
        // (a headless group - Add-ons' Import button and its picture cards - is never folded: open with the first group that has a heading)
        for (t in T.values()) if (t.folds) assertEquals(t.label, SettingsTabs.groups(t).filter { it.headless }.toSet() +
            listOfNotNull(SettingsTabs.groups(t).firstOrNull { !it.headless }), SettingsTabs.openOnShow(t))
        // the user, 2026-11-12: "the general tab doesnt need the folding sections": every General group always open
        assertEquals(false, T.GENERAL.folds)
        // ... but Reset (2026-11-19: a folding group on it): it shows folded; Support the Project (headless) open
        assertEquals(setOf(G.LOOK, G.ACCOUNT, G.HELP, G.SUPPORT), SettingsTabs.openOnShow(T.GENERAL))
        assertTrue(SettingsTabs.folds(G.RESET))
        assertFalse(SettingsTabs.folds(G.HELP)); assertFalse(SettingsTabs.folds(G.SUPPORT))
        // the user's pick 2026-11-16 (the design's "five headings with no folding on this tab"): Top screen's tiles never fold either
        assertEquals(false, T.TOP.folds)
        // beautify pick 5 (the user, 2026-11-17: tiled tabs do not fold, Add-ons folds only Voice / Imported): Add-ons is the one
        // folding tab, and only its headed groups fold
        assertEquals(listOf(T.ADDONS), T.values().filter { it.folds })
        assertEquals(setOf(G.FRAMES), SettingsTabs.openOnShow(T.PERFORMANCE))
        for (t in listOf(T.BOTTOM, T.CONTROLLER, T.PERFORMANCE)) {
            assertEquals(t.label, SettingsTabs.groups(t).toSet(), SettingsTabs.openOnShow(t))
            for (g in SettingsTabs.groups(t)) assertEquals(g.name, false, SettingsTabs.folds(g))
        }
        assertEquals(setOf(G.RESET, G.VOICE, G.IMPORTED), G.values().filter { SettingsTabs.folds(it) }.toSet())   // + General's Reset (2026-11-19)
        // the layout editor's group first (feat/big-layout's pick), and with the tiles' no-folding tab (feat/big-uimenus) every group opens
        assertEquals(setOf(G.LAYOUT, G.HUD, G.TARGET, G.PARTY, G.NAVIGATION, G.WINDOWS), SettingsTabs.openOnShow(T.TOP))
        assertEquals(G.LAYOUT, SettingsTabs.groups(T.TOP).first())
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
        assertEquals(listOf(32, 33, 34, 35, 36, 37, 38, 39), listOf(CastBarPolicy.COL_SHOW, CastBarPolicy.COL_HIDE, CastBarPolicy.RESTORE,
            CastBarPolicy.HUD_HIDE, CastBarPolicy.HUD_LOWER, CastBarPolicy.HUD_HIGHER, CastBarPolicy.HUD_HDEF, CastBarPolicy.HUD_SEL))
        assertEquals(listOf("off", "low", "high"), Settings.AVOID_DANGER)
        // Top screen > Game windows: the approved order, by the rows' own ids
        assertEquals(listOf("minimap", "tracker", "chat", "dialogs", "loot", "hidetips", "castbar", GameUi.PLAYER_ID, GameUi.TARGET_ID, "hud",
            "buffs", "xp",                                           // split-hud: the controller UI's riders right after it
            GameUi.DMG_ID, GameUi.CDM_ID),                           // hub61: the meters after the controller bars
            GameUiSection.WINDOWS_ORDER)
    }

    /** 1.0.9 Add-ons: every third-party add-on has its group there, its rows its own (title, modules, options) */
    @Test fun addOnsHaveTheirOwnGroups() {
        val cards = if (Settings.ROUTE_MODES_ON) listOf(B.ADDON_POLESTAR, B.QUEST_TRACKING, B.ADDON_SPOKEN, B.ADDON_MANNERS)
            else listOf(B.QUEST_TRACKING, B.ADDON_SPOKEN, B.ADDON_MANNERS)
        val shown = listOf(B.IMPORT) +                         // "a single button at the top of the list" (2026-11-16)
            cards +                                            // the picture cards (the beautify pass, 2026-11-17)
            B.IMPORTED                                         // the player's own imports (2026-11-16), last
        assertEquals(shown, SettingsTabs.blocks(T.ADDONS))
        assertEquals(listOf(B.IMPORT, B.IMPORTED), SettingsTabs.viewBlocks(T.ADDONS))
        for (b in cards) { assertEquals(G.CARDS, b.group); assertEquals(SettingsTabs.Drawer.ADDONS, b.drawer) }
        for (a in ThirdPartyAddons.ALL) assertTrue(a.title, SettingsTabs.Block.values().any { SettingsTabs.addonOf(it) == a })
        assertEquals(listOf("Spoken", "Spoken: Quests", "Spoken: Zones", "Spoken: Books", "Stop speech on B, Decline or close",
            "Stop speech on Accept"), B.ADDON_SPOKEN.rows)
    }

    /**
     * offline voice's model files (the user, 2026-11-16: "we could make the per languege downloads part of the addons folder"): their own
     * group on Add-ons, after Manners (Polestar's still opens first), four rows (small and full of each: "Offer both"); shown only in a build with libdsfwhisper.so
     */
    @Test fun voiceModelsAreAnAddOnsGroupOnlyWithTheLibrary() {
        assertEquals(T.ADDONS, G.VOICE.tab)
        assertEquals(listOf("Voice: English · 60 MB", "Voice: English · 148 MB", "Voice: other languages · 60 MB", "Voice: other languages · 148 MB"), B.ADDON_VOICE.rows)
        assertEquals(SettingsTabs.Drawer.VIEW, B.ADDON_VOICE.drawer)
        val was = VoiceModels.offered
        try {
            VoiceModels.offered = { false }
            assertTrue(B.ADDON_VOICE !in SettingsTabs.blocks(T.ADDONS))
            assertTrue(G.VOICE !in SettingsTabs.groups(T.ADDONS))
            VoiceModels.offered = { true }
            // after Manners, before Imported (the player's own add-ons stay last: feat/addons-import)
            val bs = SettingsTabs.blocks(T.ADDONS)
            assertEquals(listOf(B.ADDON_MANNERS, B.ADDON_VOICE, B.IMPORTED), bs.takeLast(3))
            assertEquals(listOf(G.CARDS, G.VOICE, G.IMPORTED), SettingsTabs.groups(T.ADDONS).takeLast(3))
            // Import and the cards (headless, 2026-11-17) open, and the first group with a heading: Voice (the user's "first open" pick)
            assertEquals(setOf(G.IMPORT, G.CARDS, G.VOICE), SettingsTabs.openOnShow(T.ADDONS))
        } finally { VoiceModels.offered = was }
    }

    /**
     * the user, 2026-11-16: "Import addons should be a single button at the top of the list": Add-ons starts with ONE group with no
     * heading (nothing to fold, no heading stop), its one block the Import button (its confirm card under it); the imported list stays
     * the tab's last group. The only headless group in the room; it is open whenever the tab shows, the first headed group with it.
     */
    @Test fun importIsOneButtonAtTheTopOfAddOns() {
        assertEquals(G.IMPORT, SettingsTabs.groups(T.ADDONS).first())
        assertEquals(B.IMPORT, SettingsTabs.blocks(T.ADDONS).first())
        assertEquals(listOf(B.IMPORT), SettingsTabs.blocks(G.IMPORT))
        assertEquals(listOf("Import add-ons"), B.IMPORT.rows)
        assertEquals(SettingsTabs.Drawer.VIEW, B.IMPORT.drawer)
        // (beautify: Add-ons' cards and Performance's one group are headless too, their tiles under no heading)
        assertEquals(listOf(G.SUPPORT, G.IMPORT, G.CARDS, G.FRAMES), G.values().filter { it.headless })
        assertEquals(G.IMPORTED, SettingsTabs.groups(T.ADDONS).last())
        assertTrue("the old Import row is gone from Imported", B.IMPORTED.rows.isEmpty())
        val open = SettingsTabs.openOnShow(T.ADDONS)
        assertTrue(G.IMPORT in open)
        // the cards (2026-11-17): headless and always open with it, and the first group with a heading as on every folding tab
        assertEquals(setOf(G.IMPORT, G.CARDS) + SettingsTabs.groups(T.ADDONS).first { !it.headless }, open)
        assertEquals(setOf(G.CARDS) + SettingsTabs.groups(T.ADDONS).first { !it.headless }, SettingsTabs.openOnShow(T.ADDONS) { it != G.IMPORT })
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
        // #232: Swap screens under the language (SettingsView draws it, only with a second screen); Windows ID under Game account
        // 11-16: Voice typing under the language (the user's pick: app-wide, like Language)
        assertEquals(listOf(B.THEME, B.LANGUAGE, B.VOICE, B.SCREENS, B.ACCOUNT, B.GAME_ACCOUNT, B.WINDOWS_ID, B.BROWSER_LOGIN, B.HELP, B.RESET, B.SUPPORT), SettingsTabs.viewBlocks(T.GENERAL))   // + the status (GameUiSection); 11-19: Support the Project last
        assertEquals(listOf(B.MAP, B.CHAT), SettingsTabs.viewBlocks(T.BOTTOM))                            // under Home's section
        // hub61: every Top screen block is GameUiSection's tiles now (Edit layout too; Polestar's included)
        assertEquals(emptyList<B>(), SettingsTabs.viewBlocks(T.TOP))
        assertEquals(listOf(B.CONTROLLER, B.PET_RECALL, B.PAUSE_WHEEL, B.ITEM_WHEEL, B.SPELL_WHEEL, B.CLASS_LEFT, B.CLASS_RIGHT, B.SMART_Y),
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
            // the user's pick 2026-11-13: the pause menu's third row, Start over game windows (Our wheel / Game's wheel)
            assertEquals(if (b == B.PAUSE_WHEEL) 3 else 2, b.rows.size)
            assertTrue(b.rows[0].endsWith(": open") && b.rows[1].endsWith(": pick"))
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

/** the Top screen's tiles and their pictures (the "What it does" panel's own registry went with the panel: every tab is tiles) */
class SettingsPreviewRowsTest {
    /** 2026-11-16 (the user: "VISUAL overhaul of UI menus (picture beside each game-UI element option)"): every Top screen tile has its
     *  picture, in the tiles' own registry (GameUiTiles.PICTURED, which TilePictures draws), and every Top screen row is a tile or a row
     *  of a tile's band, each once (19 tiles + 8 band rows = the tab's 27 rows) */
    @Test fun everyTopTileHasAPicture() {
        val missing = GameUiTiles.ALL.filter { it !in GameUiTiles.PICTURED }
        assertTrue("Top screen tiles with no picture: $missing", missing.isEmpty())
        assertEquals(24, GameUiTiles.ALL.size)                       // hub61: + Damage meter, Cooldown Manager, Edit layout; split-hud: + Buffs, XP bar
        val word = TopWords.WORD
        val shown = GameUiTiles.ALL + GameUiTiles.EXTRAS.values.flatten()
        assertEquals("each once", shown.size, shown.toSet().size)
        // (hub61: "Edit layout" is a tile now, the meters and the Cooldown Manager's four parts rows; HUD height / scale in the editor)
        val topRows = SettingsTabs.Block.values().filter { it.tab == SettingsTabs.Tab.TOP }.flatMap { it.rows }
        assertEquals(34, topRows.size)
        assertEquals(topRows.toSet(), shown.map { word.getValue(it) }.toSet())
        // and group by group: a group's tiles and their bands' rows are that group's rows
        for ((g, ids) in GameUiTiles.TILES) {
            val rows = SettingsTabs.Block.values().filter { it.group == g }.flatMap { it.rows }.toSet()
            assertEquals(g.heading, rows, (ids + ids.flatMap { GameUiTiles.EXTRAS[it] ?: emptyList() }).map { word.getValue(it) }.toSet())
        }
    }
}

/** the Top screen rows' own words by the ids their tiles and band rows go by (Block.rows stay the data: the tiles' names are display only) */
object TopWords {
    val WORD = mapOf(GameUi.HUD_ID to "Resource bar", GameUi.PET_ID to "Pet bar", GameUi.HUD_H_ID to "HUD height", GameUi.HUD_W_ID to "HUD width",
        GameUi.HUD_S_ID to "HUD scale", GameUi.UFT_ID to "Target bar", GameUi.UFHP_ID to "Target health text", GameUi.UFTOT_ID to "Target of target",
        GameUiSection.RINGS_ID to "Aggro tint", GameUi.NP_ID to "Enemy nameplates", GameUi.NPF_ID to "Friendly plates",
        GameUi.UFP_ID to "DS party frames", GameUi.PARTY_ID to "Party frames", GameUiSection.COMPASS_ID to "Compass bar",
        GameUiSection.COMPASS_CAM_ID to "Compass follows the camera", GameUi.WM_ID to "World markers", GameUiTiles.PS_ID to "Polestar on the top screen",
        "minimap" to "Minimap", "tracker" to "Quest tracker", "chat" to "Chat window", "dialogs" to "NPC windows", GameUiSection.LOOT_ID to "Loot window",
        GameUiSection.TIPS_ID to "Game tooltips", GameUi.CASTBAR_ID to "Cast bar", GameUi.PLAYER_ID to "Player frame", GameUi.TARGET_ID to "Target frame",
        "hud" to "Controller UI", HudSplit.BUFFS_ID to "Buffs", HudSplit.XP_ID to "XP bar", GameUi.DMG_ID to "Damage meter", GameUi.CDM_ID to "Cooldown Manager",
        GameUi.CDM_ESS_ID to "Essential", GameUi.CDM_UTIL_ID to "Utility", GameUi.CDM_BICON_ID to "Buff icons", GameUi.CDM_BBAR_ID to "Buff bars",
        GameUiTiles.LAYOUT_ID to "Edit layout")
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
