package com.jrees.wowcompanion

import android.content.Context
import android.content.SharedPreferences

/**
 * THE SETTINGS ROOM'S TABS (board f-settings-tabs; the user, 2026-10-15: "the settings menu has grown a lot ... we need to make tabs
 * for it", the plan and mocks approved the same day). 2026-11-12 (the user's approved layout): SIX TABS of FOLDING GROUPS - General,
 * Bottom screen, Top screen, Controller, Add-ons, Performance; the QOL and System tabs are gone.
 *
 * ROWS DECLARE THEIR PLACE, as data: every row belongs to one [Block] (rows drawn by one view, in one place), each block to one [Group]
 * (a heading that folds), each group to one [Tab]. The order within a tab / group is the enums' order. [Block.rows] are the rows' own
 * words, so the off-device test proves every row is in exactly one group. A block names WHO draws it ([Drawer]); only where a row is
 * drawn changed in the six-tab pass: each row's id, taps, channels and saved values are the ones it had.
 */
object SettingsTabs {
    /** [folds] false = its groups are always open, plain headings (the user, 2026-11-12: "the general tab doesnt need the folding
     *  sections"). Top screen too since its tiles (the user, 2026-11-16: "VISUAL overhaul of UI menus"; the design's pick, accepted the
     *  same day: "The five headings with no folding on this tab", which changes the 11-12 fold ruling for this tab only). The beautify
     *  pass's pick 5 (the user, 2026-11-17, the recommended answer: "tiled tabs (Bottom, Controller, Performance, like Top) do not fold;
     *  Add-ons folds only Voice / Imported"): every tab of picture tiles is always open; Add-ons is the one tab that still folds, and
     *  only its two lists (its Import button and its cards are headless) */
    enum class Tab(val label: String, val hidden: Boolean = false, val folds: Boolean = true) {
        GENERAL("General", folds = false), BOTTOM("Bottom screen", folds = false), TOP("Top screen", folds = false),
        CONTROLLER("Controller", folds = false), ADDONS("Add-ons"), PERFORMANCE("Performance", folds = false)
    }

    /** the tabs the room shows, in order (a hidden one is never on the row, never stepped onto) */
    val visible: List<Tab> get() = Tab.values().filter { !it.hidden }

    /** a heading that folds (the user's pick: a tab opens with its FIRST group open, the rest folded). [headless] = a group with NO
     *  heading: nothing to fold, no heading stop, always open (Add-ons' Import button: the user, 2026-11-16, "Import addons should be a
     *  single button at the top of the list"); the first group that opens is then the first one WITH a heading */
    enum class Group(val tab: Tab, val heading: String, val headless: Boolean = false, val foldsAlone: Boolean = false) {
        LOOK(Tab.GENERAL, "Look"), ACCOUNT(Tab.GENERAL, "Account"),
        // 2026-11-23 (the user's pick "Both (Recommended)": an opt-in test driver): one row, shown only where a test driver applies
        GRAPHICS(Tab.GENERAL, "Graphics"),
        HELP(Tab.GENERAL, "Help"),
        // the user's pick, 2026-11-19 ("reset to defaults buttons, split into four"): a FOLDING group on a tab that does not fold, just
        // above Support the Project; it shows folded (four destructive buttons stay out of the way until asked for)
        RESET(Tab.GENERAL, "Reset", foldsAlone = true),
        // Support the Project stays the page's last row: a group of its own with no heading, under Reset
        SUPPORT(Tab.GENERAL, "Support", headless = true),
        // GitHub #43 cb3 (the user, 2026-11-13: "the tap to cast bar should get its own bottom screen section"): its own group
        HOME(Tab.BOTTOM, "Home"),
        // party sizing (the user's pick 2026-11-23: "Settings + Edit Home" - Settings > Party, per character): the party list's tile;
        // headed "Party list" (Top screen has its own "Party" group: the game's party frames)
        PARTY_LIST(Tab.BOTTOM, "Party list"),
        CAST_BAR(Tab.BOTTOM, "Tap-to-cast bar"), MAP(Tab.BOTTOM, "Map"), CHAT(Tab.BOTTOM, "Chat"),
        // the layout editor (the user, 2026-11-16: "we need to make a way to basically edit the UI locations on the top screen from our UI
        // menu"; the design's pick: a first group "Layout" on Top screen, so it is the one that opens; Your HUD then opens folded)
        LAYOUT(Tab.TOP, "Layout"),
        HUD(Tab.TOP, "Your HUD"), TARGET(Tab.TOP, "Target"), PARTY(Tab.TOP, "Party"), NAVIGATION(Tab.TOP, "Navigation"),
        WINDOWS(Tab.TOP, "Game windows"),
        BUTTONS(Tab.CONTROLLER, "Buttons"), WHEELS(Tab.CONTROLLER, "Wheels"), SMART_Y(Tab.CONTROLLER, "Smart Y"),
        COMBAT(Tab.CONTROLLER, "Combat helpers"),
        // the user, 2026-11-16: "Import addons should be a single button at the top of the list": first on the tab, no heading
        IMPORT(Tab.ADDONS, "Import add-ons", headless = true),
        // the beautify pass (2026-11-17, the design's section 5): the third-party add-ons are ONE row of picture cards (AddonTiles), a
        // group with no heading, always open; it was one folding group each (Polestar, Spoken, QuestTrail, Manners)
        CARDS(Tab.ADDONS, "Add-ons", headless = true),
        // offline voice's model files (the user, 2026-11-16: "we could make the per languege downloads part of the addons folder"): app
        // files, never the game's AddOns; after Manners, so Polestar's group still opens first
        VOICE(Tab.ADDONS, "Voice"),
        // the player's own add-ons (the user, 2026-11-16: "maybe we just do an import addons button then?"): the list, last; its Import
        // button is IMPORT's, at the top
        IMPORTED(Tab.ADDONS, "Imported"),
        // beautify (the design's critic C6: "Performance one headless row of exactly 5 columns ... No group headers on this tab"): ONE
        // headless group for the tab's tiles and its Stream settings row (Memory, Indoor maps and Streaming were groups of their own)
        FRAMES(Tab.PERFORMANCE, "Performance", headless = true),
    }

    /** who draws a block: SettingsView, Home's section (HomeSettingsSection), a GameUiSection part, Add-ons' cards (AddonTiles), or
     *  the Performance tab's picture tiles (TILES: PerformanceTiles, one part for the tab's every block) */
    enum class Drawer { VIEW, HOME, GAME_UI, ADDONS, TILES }

    /** the order within a group is this enum's order; [subHead] = the block keeps its own sub-heading inside its group (the wheels) */
    enum class Block(val group: Group, val heading: String, val rows: List<String>, val drawer: Drawer = Drawer.VIEW,
                     val subHead: Boolean = false) {
        // ---- General
        // 2026-11-12 (the user: "an oled theme, but different color variations for menus"): the theme picker, the language under it
        THEME(Group.LOOK, "Theme", listOf("Theme")),
        LANGUAGE(Group.LOOK, "Language", listOf("Language")),
        // the user's pick 2026-11-16 (voice to text): This device / Online / Off under Language, app-wide like it
        VOICE(Group.LOOK, "Voice typing", listOf("Voice typing")),
        // GitHub #232 (the user, 2026-11-14: build it): which screen has the game; only with a second screen, Restart under it after a
        // change (ScreenSwap)
        SCREENS(Group.LOOK, "Screens", listOf("Swap screens", "Restart DS Forever")),
        ACCOUNT(Group.ACCOUNT, "Battle.net login", listOf("Battle.net login")),
        // #47: several game accounts on one Battle.net login - the game's own picker each time, or its remembered pick
        GAME_ACCOUNT(Group.ACCOUNT, "Game account", listOf("Game account")),
        // the user, 2026-11-14 ("build it", the BLZ51914400 wave): Per install / Original, from the next game start; while the running game
        // started with the other one, a "Restart the game" row under it (Frames' words: the same launcher restart, so not listed again)
        WINDOWS_ID(Group.ACCOUNT, WindowsId.ROW_ID, listOf(WindowsId.ROW_ID)),
        // the user, 2026-11-18 (upstream v2.4.5's Browser login, opt-in): Off / On, from the next game start; the same "Restart the game"
        // row under Windows ID while the running game signed in the other way
        BROWSER_LOGIN(Group.ACCOUNT, BrowserLogin.ROW_ID, listOf(BrowserLogin.ROW_ID)),
        // 2026-11-23: Recommended (today's driver, the default) / the test drivers this APK carries, from the next Play (GraphicsDriver);
        // the same "Restart the game" row under it while the running game started with the other one
        GRAPHICS_DRIVER(Group.GRAPHICS, GraphicsDriver.ROW_ID, listOf(GraphicsDriver.ROW_ID)),
        HELP(Group.HELP, "Help", listOf("Problem report", "Reload game UI")),
        COMPANION(Group.HELP, "Companion", listOf("Status"), Drawer.GAME_UI),
        // the user, 2026-11-19 ("add a link to my Kofi to the bottom of the General tab. just a simple button at the bottom that says
        // 'Support the Project'"): the tab's LAST row, one full-width button opening SupportLink.URL in the browser
        // 2026-11-19: Reset to defaults (ResetPlan) - four buttons, each asks first (ResetAskHost)
        RESET(Group.RESET, "Reset", listOf("Reset settings", "Reset layouts", "Reset skills", "Reset everything")),
        SUPPORT(Group.SUPPORT, "Support", listOf(SupportLink.ROW_ID)),
        // ---- Bottom screen (the old Home tab)
        HOME_SECTION(Group.HOME, "Home", listOf("Controller on Home", "Combat layout", "Swing timer",
            // "Stance bar", "Pet bar": hidden for now (test40, the user: "hide the settings that don't do anything for now")
            "Focused quest",
            // the clock extras (the user's picks 2026-11-13): one row each
            "Hearthstone", "Class teleport", "Gold", "Free bag slots", "Ammo", "Pet happiness", "DS WoW toolbar",
            // the user, 2026-11-20 ("put that as a toggle in the settings, off by default"): #471's rank badges (BottomTiles.RANK)
            "Spell rank badges",
            // hold to talk (the user, 2026-11-16): under the toolbar, "On release" under its switch (HoldTalk)
            HomeSettingsSection.TALK_ROW, HomeSettingsSection.RELEASE_ROW), Drawer.HOME),
        // GitHub #43 cb3: Off / Left / Right / Both (Home's section draws it, as it drew the old Home row)
        // the bar in a fight (the user 2026-11-13: "a settings toggle to make them Hide/stay in combat"): Hide (the default) / Stay
        // party sizing (2026-11-23): the party list's tile (BottomTiles.PARTY), its band's two lines - this character's record
        PARTY_LIST(Group.PARTY_LIST, "Party list", listOf(HomeWidgetMenus.PARTY_GROW_LABEL, HomeWidgetMenus.PARTY_SIZE_LABEL), Drawer.HOME),
        CAST_BAR(Group.CAST_BAR, "Tap-to-cast bar", listOf("Tap-to-cast bar", "Show in combat"), Drawer.HOME),     // cb14 (D5b)
        MAP(Group.MAP, "Map", listOf("Minimap zoom outdoors", "Minimap zoom indoors", "World map")),
        CHAT(Group.CHAT, "Chat", listOf("Message input")),
        // ---- Top screen (GameUiSection's parts: the same rows, ids, taps and channels as on QOL / Top screen before)
        // the layout editor's one row: its own page (LayoutView); shown once the add-on set has the editor (a "lay" document came)
        // hub61 (the beautify design 7): drawn as a tile by GameUiSection's LAYOUT part ("Edit layout": A opens the page, no band)
        LAYOUT(Group.LAYOUT, "Layout", listOf("Edit layout"), Drawer.GAME_UI),
        // HUD height and HUD scale moved into the layout editor (the design's pick); the width stays a row
        HUD(Group.HUD, "Your HUD", listOf("Resource bar", "Pet bar", "HUD width"), Drawer.GAME_UI),
        TARGET(Group.TARGET, "Target", listOf("Target bar", "Target health text", "Target of target", "Aggro tint", "Enemy nameplates", "Friendly plates"),
            Drawer.GAME_UI),
        PARTY(Group.PARTY, "Party", listOf("DS party frames", "Party frames"), Drawer.GAME_UI),
        NAVIGATION(Group.NAVIGATION, "Navigation", listOf("Compass bar", "Compass follows the camera", "World markers"), Drawer.GAME_UI),
        // hub31: Polestar's own frame on the top screen (it was under Add-ons > Polestar), shown while Polestar is in use. 2026-11-16 (the
        // tiles): Navigation's third tile, drawn by Navigation's GameUiSection part (it was SettingsView's row)
        POLESTAR_TOP(Group.NAVIGATION, "Polestar on the top screen", listOf("Polestar on the top screen"), Drawer.GAME_UI),
        GAME_UI(Group.WINDOWS, "Game windows", listOf("Minimap", "Quest tracker", "Chat window", "NPC windows", "Loot window",
            "Game tooltips", "Cast bar", "Player frame", "Target frame",
            // split-hud (the user, 2026-11-17: "we need to split out the Buffs controller UI and XP bar"): three tiles where one was
            "Controller UI", "Buffs", "XP bar",
            // hub61: the damage meter, the Cooldown Manager and its four parts (its band's lines)
            "Damage meter", "Cooldown Manager", "Essential", "Utility", "Buff icons", "Buff bars"), Drawer.GAME_UI),
        // ---- Controller
        CONTROLLER(Group.BUTTONS, "Buttons", listOf("ABXY location", "Button glyphs", "Hold time")),     // m190: hold to cast's hold time (its tile last, after Smart Y)
        // the user, 2026-11-16 ("a pet class specific option where pressing B in the out of combat home menu basically stops the pet
        // attacking"): B on Home's rest, per character; shown only for a pet class. 2026-11-17 ("to be clear I think this should just be a
        // smart pet feature that includes both of those settings in one"): the one Smart pet row replaces B: pet follow. 2026-11-18 ("I
        // like the pet recall but I think we need to remove the pet smart attack, its obnoxious"): the row is "B: pet recall" (PetRecall)
        PET_RECALL(Group.BUTTONS, "Pet", listOf(PetRecall.ROW_ID)),
        // f-radial-settings (1.0.9, the user: "better organize each radial setting into categories so its clear what each one is"): a
        // sub-heading per wheel, naming the button that opens it
        // the user's pick 2026-11-13 ("Make it a setting toggle defaulted to our wheel"): Start over a game window, Our wheel / Game's wheel
        PAUSE_WHEEL(Group.WHEELS, "Pause menu (Start)", listOf("Pause menu: open", "Pause menu: pick", PauseOver.ROW_ID), subHead = true),
        ITEM_WHEEL(Group.WHEELS, "Item wheel (D-pad Up)", listOf("Item wheel: open", "Item wheel: pick"), subHead = true),
        SPELL_WHEEL(Group.WHEELS, "Spell wheel (its button in Controller)", listOf("Spell wheel: open", "Spell wheel: pick"), subHead = true),
        CLASS_LEFT(Group.WHEELS, "Left class radial (LB + LT)", listOf("Left class radial: open", "Left class radial: pick"), subHead = true),
        CLASS_RIGHT(Group.WHEELS, "Right class radial (RB + RT)", listOf("Right class radial: open", "Right class radial: pick"), subHead = true),
        // m62 SMART BUFFS (the user: "this setting will need its own section in the settings"): Y out of combat, per character
        SMART_Y(Group.SMART_Y, "Smart Y (out of combat, nothing targeted)", listOf("Smart Y")),
        // Combat helpers: Party mode, Start attack on hostile target, Start the attack when a spell is cast (Home's section's row),
        // Combat Auto Shot - in that order, so three blocks by two views
        COMBAT(Group.COMBAT, "Combat", listOf("Party mode", "Smart Res", "Start attack on hostile target"), Drawer.GAME_UI),
        ATTACK_CAST(Group.COMBAT, "Start the attack when a spell is cast", listOf("Start the attack when a spell is cast"), Drawer.HOME),
        AUTO_SHOT(Group.COMBAT, "Combat Auto Shot", listOf("Combat Auto Shot"), Drawer.GAME_UI),
        // ---- Add-ons: one group per third-party add-on (ThirdPartyAddons); Polestar's carries Quest tracking (it was under Map)
        // Import (a .zip through Android's picker): ONE full-width button first on the tab (so first in this enum's Add-ons part), its status line and confirm card under it
        // (the user, 2026-11-16: "Import addons should be a single button at the top of the list"; SettingsView.drawImport)
        IMPORT(Group.IMPORT, "Import add-ons", listOf("Import add-ons")),
        // the cards (2026-11-17): Polestar, Spoken, Manners, then QuestTrail (only while a copy is there: last, so it never leaves a hole);
        // Quest tracking is Polestar's card's guide line (the same PolestarGuide choice)
        ADDON_POLESTAR(Group.CARDS, "Polestar", ThirdPartyAddons.POLESTAR.rowWords(), Drawer.ADDONS),
        QUEST_TRACKING(Group.CARDS, "Quest tracking", listOf("Quest tracking"), Drawer.ADDONS),
        ADDON_SPOKEN(Group.CARDS, "Spoken", ThirdPartyAddons.SPOKEN.rowWords(), Drawer.ADDONS),
        ADDON_MANNERS(Group.CARDS, "Manners", ThirdPartyAddons.MANNERS.rowWords(), Drawer.ADDONS),
        ADDON_QUESTTRAIL(Group.CARDS, "QuestTrail", ThirdPartyAddons.QUESTTRAIL.rowWords(), Drawer.ADDONS),
        // offline voice (VoiceModels): English, and every other language in one file, each small and full ("Voice: English · 60 MB");
        // shown only in a build with libdsfwhisper.so
        ADDON_VOICE(Group.VOICE, "Voice", VoiceModels.Model.values().map { it.label }),
        // the imported list and the replacements waiting (ImportedAddons; SettingsView.drawImported): rows named by the add-ons themselves
        IMPORTED(Group.IMPORTED, "Imported", emptyList()),
        // ---- Performance
        FRAMES(Group.FRAMES, "Frame limit and generation", listOf("Frame limit", "Render size", "Lossless.dll", "Frame generation", "Multiplier",
            "Flow scale", "Performance mode", "Restart the game"), Drawer.TILES),
        // 1.0.17 (the user's pick "A setting, off by default"): the lighter behaviour on any Thor
        LIGHTER(Group.FRAMES, "Lighter mode", listOf("Lighter mode", "Low-memory graphics", "Home map: full detail", "Map style when zoomed out"), Drawer.TILES),
        INDOOR(Group.FRAMES, "Indoor maps", listOf("Build in the background", "Progress", "Route data"), Drawer.TILES),
        STREAMING(Group.FRAMES, "Streaming", listOf("Stream settings"), Drawer.TILES);

        val tab: Tab get() = group.tab
    }

    /** the blocks SettingsView draws itself (the others are Home's section and the GameUiSection parts) */
    val VIEW_BLOCKS: Set<Block> get() = Block.values().filter { it.drawer == Drawer.VIEW }.toSet()

    /** the third-party add-on a block draws (Settings > Add-ons), or null */
    fun addonOf(b: Block): ThirdPartyAddons.Addon? = when (b) {
        Block.ADDON_SPOKEN -> ThirdPartyAddons.SPOKEN; Block.ADDON_POLESTAR -> ThirdPartyAddons.POLESTAR
        Block.ADDON_QUESTTRAIL -> ThirdPartyAddons.QUESTTRAIL; Block.ADDON_MANNERS -> ThirdPartyAddons.MANNERS; else -> null }

    fun blocks(tab: Tab): List<Block> = Block.values().filter { it.tab == tab && shown(it) }
    fun blocks(g: Group): List<Block> = Block.values().filter { it.group == g && shown(it) }

    /** the groups a tab shows, in order (a group with no shown block is not there: QuestTrail's while it is not installed) */
    fun groups(tab: Tab): List<Group> = Group.values().filter { it.tab == tab && blocks(it).isNotEmpty() }

    /** the groups OPEN when [tab] is shown (the user's pick, 2026-11-12: every time a tab shows, its first group open, the rest folded) */
    fun openOnShow(tab: Tab, hasRows: (Group) -> Boolean = { true }): Set<Group> =
        // a tab that does not fold opens every group WITH ROWS (11-16 merge: Top screen's Layout group has none until the add-on set
        // sends its first "lay" document; the tiles' groups always have their fixed slots); a folding one its headless groups (never
        // folded: Add-ons' Import button) and its first group with a heading
        // a group that folds on its own (General's Reset) shows folded
        if (!tab.folds) groups(tab).filter(hasRows).filter { !it.foldsAlone }.toSet()
        else groups(tab).filter(hasRows).let { gs -> gs.filter { it.headless }.toSet() + gs.filter { !it.headless }.take(1) }

    /** [g] folds (a tap / A on its heading opens or folds it): a folding tab's group with a heading */
    fun folds(g: Group): Boolean = (g.tab.folds || g.foldsAlone) && !g.headless

    /** a Reset button's words (General > Reset) */
    fun resetLabel(b: ResetPlan.Button): String = when (b) {
        ResetPlan.Button.SETTINGS -> "Reset settings"; ResetPlan.Button.LAYOUTS -> "Reset layouts"
        ResetPlan.Button.SKILLS -> "Reset skills"; ResetPlan.Button.EVERYTHING -> "Reset everything"
    }

    /** 1.0.9 (the user: "Disable the safe path, quest trail and polestar buttons for now"): with the route modes held back, the
     *  Polestar and QuestTrail blocks are not shown (Spoken stays) */
    fun shown(b: Block): Boolean = when {
        b == Block.ADDON_VOICE -> VoiceModels.offered()
        // only in the launcher, on a GPU profile that takes test drivers, with one in this APK
        b == Block.GRAPHICS_DRIVER -> GraphicsDriver.rowShown()
        !Settings.ROUTE_MODES_ON -> b != Block.ADDON_POLESTAR && b != Block.ADDON_QUESTTRAIL
        // 2026-11-12 (the user: "remove ... quest trail from the options"): QuestTrail is never offered; a copy a player installed keeps
        // its block (its Uninstall) while it is there or on its way in
        b == Block.ADDON_QUESTTRAIL -> ThirdPartyAddons.present(ThirdPartyAddons.QUESTTRAIL, "core") ||
            ThirdPartyAddons.coreWanted(ThirdPartyAddons.QUESTTRAIL)
        else -> true
    }
    fun viewBlocks(tab: Tab): List<Block> = blocks(tab).filter { it in VIEW_BLOCKS }

    /**
     * the tab [dir] (-1 / +1) steps from [tab] among the VISIBLE tabs; null past an end. NO WRAP (the user's pick, 2026-11-25: "Stop
     * everywhere": every room's L / R and its tab row stop at the ends, as the Controller room's and the tabs moved on top: RoomTabRow.step)
     */
    fun step(tab: Tab, dir: Int): Tab? {
        val all = visible
        val i = all.indexOf(tab).let { if (it < 0) 0 else it }
        return RoomTabRow.step(i, all.size, dir)?.let { all[it] }
    }

    /**
     * a saved tab name (the last tab used) as today's tab: the old seven tabs' names map onto the six (HOME and the older ROOMS ->
     * Bottom screen, QOL -> Top screen, SYSTEM -> General); nothing saved or an unknown name -> General, the first tab
     */
    fun fromSaved(name: String?): Tab = when (name) {
        null -> Tab.GENERAL
        "HOME", "ROOMS" -> Tab.BOTTOM
        "QOL" -> Tab.TOP
        "SYSTEM" -> Tab.GENERAL
        else -> runCatching { Tab.valueOf(name) }.getOrDefault(Tab.GENERAL)
    }.let { if (it.hidden) Tab.GENERAL else it }

    // ---- the last tab used (the user's pick: the room opens on it)
    private var prefs: SharedPreferences? = null
    fun init(c: Context) { if (prefs == null) prefs = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    var last: Tab
        get() = fromSaved(prefs?.getString(KEY_TAB, null))
        set(v) { prefs?.edit()?.putString(KEY_TAB, v.name)?.apply() }

    private const val KEY_TAB = "settingsTab"
}
