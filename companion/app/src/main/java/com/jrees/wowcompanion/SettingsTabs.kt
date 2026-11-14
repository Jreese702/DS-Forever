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
     *  sections") */
    enum class Tab(val label: String, val hidden: Boolean = false, val folds: Boolean = true) {
        GENERAL("General", folds = false), BOTTOM("Bottom screen"), TOP("Top screen"), CONTROLLER("Controller"), ADDONS("Add-ons"),
        PERFORMANCE("Performance")
    }

    /** the tabs the room shows, in order (a hidden one is never on the row, never stepped onto) */
    val visible: List<Tab> get() = Tab.values().filter { !it.hidden }

    /** a heading that folds (the user's pick: a tab opens with its FIRST group open, the rest folded) */
    enum class Group(val tab: Tab, val heading: String) {
        LOOK(Tab.GENERAL, "Look"), ACCOUNT(Tab.GENERAL, "Account"), HELP(Tab.GENERAL, "Help"),
        // GitHub #43 cb3 (the user, 2026-11-13: "the tap to cast bar should get its own bottom screen section"): its own group
        HOME(Tab.BOTTOM, "Home"), CAST_BAR(Tab.BOTTOM, "Tap-to-cast bar"), MAP(Tab.BOTTOM, "Map"), CHAT(Tab.BOTTOM, "Chat"),
        HUD(Tab.TOP, "Your HUD"), TARGET(Tab.TOP, "Target"), PARTY(Tab.TOP, "Party"), NAVIGATION(Tab.TOP, "Navigation"),
        WINDOWS(Tab.TOP, "Game windows"),
        BUTTONS(Tab.CONTROLLER, "Buttons"), WHEELS(Tab.CONTROLLER, "Wheels"), SMART_Y(Tab.CONTROLLER, "Smart Y"),
        COMBAT(Tab.CONTROLLER, "Combat helpers"),
        POLESTAR(Tab.ADDONS, "Polestar"), SPOKEN(Tab.ADDONS, "Spoken"), QUESTTRAIL(Tab.ADDONS, "QuestTrail"), MANNERS(Tab.ADDONS, "Manners"),
        FRAMES(Tab.PERFORMANCE, "Frames"), MEMORY(Tab.PERFORMANCE, "Memory"), INDOOR(Tab.PERFORMANCE, "Indoor maps"),
        STREAMING(Tab.PERFORMANCE, "Streaming"),
    }

    /** who draws a block: SettingsView, Home's section (HomeSettingsSection) or a GameUiSection part */
    enum class Drawer { VIEW, HOME, GAME_UI }

    /** the order within a group is this enum's order; [subHead] = the block keeps its own sub-heading inside its group (the wheels) */
    enum class Block(val group: Group, val heading: String, val rows: List<String>, val drawer: Drawer = Drawer.VIEW,
                     val subHead: Boolean = false) {
        // ---- General
        // 2026-11-12 (the user: "an oled theme, but different color variations for menus"): the theme picker, the language under it
        THEME(Group.LOOK, "Theme", listOf("Theme")),
        LANGUAGE(Group.LOOK, "Language", listOf("Language")),
        // GitHub #232 (the user, 2026-11-14: build it): which screen has the game; only with a second screen, Restart under it after a
        // change (ScreenSwap)
        SCREENS(Group.LOOK, "Screens", listOf("Swap screens", "Restart DS Forever")),
        ACCOUNT(Group.ACCOUNT, "Battle.net login", listOf("Battle.net login")),
        // #47: several game accounts on one Battle.net login - the game's own picker each time, or its remembered pick
        GAME_ACCOUNT(Group.ACCOUNT, "Game account", listOf("Game account")),
        // the user, 2026-11-14 ("build it", the BLZ51914400 wave): Per install / Original, from the next game start; while the running game
        // started with the other one, a "Restart the game" row under it (Frames' words: the same launcher restart, so not listed again)
        WINDOWS_ID(Group.ACCOUNT, WindowsId.ROW_ID, listOf(WindowsId.ROW_ID)),
        HELP(Group.HELP, "Help", listOf("Problem report", "Reload game UI")),
        COMPANION(Group.HELP, "Companion", listOf("Status"), Drawer.GAME_UI),
        // ---- Bottom screen (the old Home tab)
        HOME_SECTION(Group.HOME, "Home", listOf("Controller on Home", "Combat layout", "Swing timer",
            // "Stance bar", "Pet bar": hidden for now (test40, the user: "hide the settings that don't do anything for now")
            "Focused quest",
            // the clock extras (the user's picks 2026-11-13): one row each
            "Hearthstone", "Class teleport", "Gold", "Free bag slots", "Ammo", "DS WoW toolbar"), Drawer.HOME),
        // GitHub #43 cb3: Off / Left / Right / Both (Home's section draws it, as it drew the old Home row)
        // the bar in a fight (the user 2026-11-13: "a settings toggle to make them Hide/stay in combat"): Hide (the default) / Stay
        CAST_BAR(Group.CAST_BAR, "Tap-to-cast bar", listOf("Tap-to-cast bar", "In combat"), Drawer.HOME),
        MAP(Group.MAP, "Map", listOf("Minimap zoom outdoors", "Minimap zoom indoors", "World map")),
        CHAT(Group.CHAT, "Chat", listOf("Message input")),
        // ---- Top screen (GameUiSection's parts: the same rows, ids, taps and channels as on QOL / Top screen before)
        HUD(Group.HUD, "Your HUD", listOf("Resource bar", "Pet bar", "HUD height", "HUD width", "HUD scale"), Drawer.GAME_UI),
        TARGET(Group.TARGET, "Target", listOf("Target bar", "Target health text", "Target of target", "Aggro tint", "Enemy nameplates", "Friendly plates"),
            Drawer.GAME_UI),
        PARTY(Group.PARTY, "Party", listOf("DS party frames", "Party frames"), Drawer.GAME_UI),
        NAVIGATION(Group.NAVIGATION, "Navigation", listOf("Compass bar", "Compass follows the camera", "World markers"), Drawer.GAME_UI),
        // hub31: Polestar's own frame on the top screen (it was under Add-ons > Polestar), shown while Polestar is in use
        POLESTAR_TOP(Group.NAVIGATION, "Polestar on the top screen", listOf("Polestar on the top screen")),
        GAME_UI(Group.WINDOWS, "Game windows", listOf("Minimap", "Quest tracker", "Chat window", "NPC windows", "Loot window",
            "Game tooltips", "Cast bar", "Player frame", "Target frame", "Controller bars, buffs and XP"), Drawer.GAME_UI),
        // ---- Controller
        CONTROLLER(Group.BUTTONS, "Buttons", listOf("ABXY location", "Button glyphs")),
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
        ADDON_POLESTAR(Group.POLESTAR, "Polestar", ThirdPartyAddons.POLESTAR.rowWords()),
        QUEST_TRACKING(Group.POLESTAR, "Quest tracking", listOf("Quest tracking")),
        ADDON_SPOKEN(Group.SPOKEN, "Spoken", ThirdPartyAddons.SPOKEN.rowWords()),
        ADDON_QUESTTRAIL(Group.QUESTTRAIL, "QuestTrail", ThirdPartyAddons.QUESTTRAIL.rowWords()),
        ADDON_MANNERS(Group.MANNERS, "Manners", ThirdPartyAddons.MANNERS.rowWords()),
        // ---- Performance
        FRAMES(Group.FRAMES, "Frame limit and generation", listOf("Frame limit", "Lossless.dll", "Frame generation", "Multiplier",
            "Flow scale", "Performance mode", "Restart the game")),
        // 1.0.17 (the user's pick "A setting, off by default"): the lighter behaviour on any Thor
        LIGHTER(Group.MEMORY, "Lighter mode", listOf("Lighter mode", "Low-memory graphics")),
        INDOOR(Group.INDOOR, "Indoor maps", listOf("Build in the background", "Progress", "Route data")),
        STREAMING(Group.STREAMING, "Streaming", listOf("Stream settings"));

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
        if (!tab.folds) groups(tab).toSet() else groups(tab).filter(hasRows).take(1).toSet()

    /** 1.0.9 (the user: "Disable the safe path, quest trail and polestar buttons for now"): with the route modes held back, the
     *  Polestar and QuestTrail blocks are not shown (Spoken stays) */
    fun shown(b: Block): Boolean = when {
        !Settings.ROUTE_MODES_ON -> b != Block.ADDON_POLESTAR && b != Block.ADDON_QUESTTRAIL
        // 2026-11-12 (the user: "remove ... quest trail from the options"): QuestTrail is never offered; a copy a player installed keeps
        // its block (its Uninstall) while it is there or on its way in
        b == Block.ADDON_QUESTTRAIL -> ThirdPartyAddons.present(ThirdPartyAddons.QUESTTRAIL, "core") ||
            ThirdPartyAddons.coreWanted(ThirdPartyAddons.QUESTTRAIL)
        else -> true
    }
    fun viewBlocks(tab: Tab): List<Block> = blocks(tab).filter { it in VIEW_BLOCKS }

    /** the tab [dir] (-1 / +1) steps from [tab] among the VISIBLE tabs, wrapping (L on the first goes to the last) */
    fun step(tab: Tab, dir: Int): Tab {
        val all = visible
        val i = all.indexOf(tab).let { if (it < 0) 0 else it }
        return all[Math.floorMod(i + dir, all.size)]
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
