package com.jrees.wowcompanion

import android.content.Context
import android.content.SharedPreferences

/**
 * THE SETTINGS ROOM'S TABS (board f-settings-tabs; the user, 2026-10-15: "the settings menu has grown a lot ... we need to make tabs
 * for it", the plan and mocks approved the same day: indoor maps under Performance, the room opens on the last tab used). Phase 1:
 * the tabs and the rows on them; the "What it does" previews come in phase 2.
 *
 * ROWS DECLARE THEIR TAB, as data: every row belongs to one [Block] (a heading's rows, drawn by one view), and each block names its
 * tab. A new row joins its block's list (and so its tab); a new kind of row is a new block with its tab. The room shows the current
 * tab's blocks only. [Block.rows] are the rows' own words, so the off-device test proves every row is on exactly one tab.
 */
object SettingsTabs {
    enum class Tab(val label: String) {
        HOME("Home"), CONTROLLER("Controller"), TOP("Top screen"), ROOMS("Rooms"), ADDONS("Add-ons"), PERFORMANCE("Performance"),
        SYSTEM("System")
    }

    /** the order within a tab is this enum's order */
    enum class Block(val tab: Tab, val heading: String, val rows: List<String>) {
        HOME_SECTION(Tab.HOME, "Home", listOf("Controller on Home", "Combat layout", "Swing timer", "Stance bar", "Pet bar",
            "Focused quest", "Start the attack when a spell is cast", "DS WoW toolbar")),
        CONTROLLER(Tab.CONTROLLER, "Controller", listOf("ABXY location", "Button glyphs")),
        // f-radial-settings (1.0.9, the user: "better organize each radial setting into categories so its clear what each one is"): a
        // heading per wheel, naming the button that opens it
        PAUSE_WHEEL(Tab.CONTROLLER, "Pause menu (Start)", listOf("Pause menu: open", "Pause menu: pick")),
        ITEM_WHEEL(Tab.CONTROLLER, "Item wheel (D-pad Up)", listOf("Item wheel: open", "Item wheel: pick")),
        SPELL_WHEEL(Tab.CONTROLLER, "Spell wheel (its button in Controller)", listOf("Spell wheel: open", "Spell wheel: pick")),
        CLASS_LEFT(Tab.CONTROLLER, "Left class radial (LB + LT)", listOf("Left class radial: open", "Left class radial: pick")),
        CLASS_RIGHT(Tab.CONTROLLER, "Right class radial (RB + RT)", listOf("Right class radial: open", "Right class radial: pick")),
        GAME_UI(Tab.TOP, "Game UI on the top screen", listOf("Minimap", "Controller bars, buffs and XP", "Quest tracker", "Chat window",
            "NPC windows", "Cast bar", "Loot window")),
        MAP(Tab.ROOMS, "Map", listOf("Minimap zoom outdoors", "Minimap zoom indoors", "Quest tracking")),
        CHAT(Tab.ROOMS, "Chat", listOf("Message input")),
        // 1.0.9 (the user, on Spoken: "make an addon section of our settings"): one block per third-party add-on (ThirdPartyAddons)
        ADDON_SPOKEN(Tab.ADDONS, "Spoken", ThirdPartyAddons.SPOKEN.rowWords()),
        ADDON_POLESTAR(Tab.ADDONS, "Polestar", ThirdPartyAddons.POLESTAR.rowWords()),
        ADDON_QUESTTRAIL(Tab.ADDONS, "QuestTrail", ThirdPartyAddons.QUESTTRAIL.rowWords()),
        FRAMES(Tab.PERFORMANCE, "Frame limit and generation", listOf("Frame limit", "Lossless.dll", "Frame generation", "Multiplier",
            "Flow scale", "Performance mode", "Restart the game")),
        INDOOR(Tab.PERFORMANCE, "Indoor maps", listOf("Build in the background", "Progress")),
        GAME(Tab.SYSTEM, "Game", listOf("Reload game UI", "Battle.net login", "Problem report")),
        COMPANION(Tab.SYSTEM, "Companion", listOf("Status")),
    }

    /** the blocks SettingsView draws itself (the others are Home's section and the two GameUiSection parts) */
    val VIEW_BLOCKS = setOf(Block.GAME, Block.MAP, Block.INDOOR, Block.CONTROLLER, Block.PAUSE_WHEEL, Block.ITEM_WHEEL, Block.SPELL_WHEEL,
        Block.CLASS_LEFT, Block.CLASS_RIGHT,
        Block.CHAT, Block.FRAMES, Block.ADDON_SPOKEN, Block.ADDON_POLESTAR, Block.ADDON_QUESTTRAIL)

    /** the third-party add-on a block draws (Settings > Add-ons), or null */
    fun addonOf(b: Block): ThirdPartyAddons.Addon? = when (b) {
        Block.ADDON_SPOKEN -> ThirdPartyAddons.SPOKEN; Block.ADDON_POLESTAR -> ThirdPartyAddons.POLESTAR
        Block.ADDON_QUESTTRAIL -> ThirdPartyAddons.QUESTTRAIL; else -> null }

    fun blocks(tab: Tab): List<Block> = Block.values().filter { it.tab == tab }
    fun viewBlocks(tab: Tab): List<Block> = blocks(tab).filter { it in VIEW_BLOCKS }

    /** the tab [dir] (-1 / +1) steps from [tab], wrapping (L on the first goes to the last) */
    fun step(tab: Tab, dir: Int): Tab {
        val all = Tab.values()
        return all[Math.floorMod(tab.ordinal + dir, all.size)]
    }

    // ---- the last tab used (the user's pick: the room opens on it); Home before any
    private var prefs: SharedPreferences? = null
    fun init(c: Context) { if (prefs == null) prefs = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    var last: Tab
        get() = runCatching { Tab.valueOf(prefs?.getString(KEY_TAB, null) ?: Tab.HOME.name) }.getOrDefault(Tab.HOME)
        set(v) { prefs?.edit()?.putString(KEY_TAB, v.name)?.apply() }

    private const val KEY_TAB = "settingsTab"
}
