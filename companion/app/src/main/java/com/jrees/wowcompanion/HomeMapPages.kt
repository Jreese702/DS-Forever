package com.jrees.wowcompanion

/**
 * HOME'S OPEN MAP MODULE, ITS THREE PAGES (pure: the tests). The user, 2026-11-17: "when opening the map, pressing to the right should
 * open some map controls, pressing right again goes to the buffs and left goes back to the quests. tapping in the map area should also
 * change it to the map controls. when using map controls it should function just like the map room with the sticks."
 *
 *   QUESTS (the module opens here: the tracked quests' list, MapCursor's keys)   Right -> CONTROLS
 *   CONTROLS (the Map room's sticks on Home's map: MapStickDriver)               Left -> QUESTS, Right -> BUFFS (while the pane has a
 *                                                                                stop: an aura, the mail envelope, the Calendar button),
 *                                                                                B -> QUESTS (the module's default page)
 *   BUFFS (the aura pane's cursor, b129)                                         Left off its left end -> CONTROLS, B -> QUESTS
 * A touch on the map's field (not the list, a button or a pop-out) -> CONTROLS from either other page. Every other key is the page's own.
 * The page changes by the D-pad only, as the module's stops always did (no tab row, so no gold arrows: the ruling's arrows sit on a
 * HIGHLIGHTED TAB, and these pages have none; the cursor itself shows where it is - the list's box, the map's reticle, the aura's slot).
 */
object HomeMapPages {
    enum class Page { QUESTS, CONTROLS, BUFFS }

    /**
     * A press [k] on [page], before the page's own keys: the page it leads to, or null = the page keeps the key. [buffsHave] = the
     * aura pane has a stop to land on. BUFFS' own Left / B are the pane's ([afterBuffs] reads where they left it).
     */
    fun press(page: Page, k: String, buffsHave: Boolean): Page? = when (page) {
        Page.QUESTS -> if (k == "PADDRIGHT") Page.CONTROLS else null
        Page.CONTROLS -> when (k) {
            "PADDLEFT", "PAD2" -> Page.QUESTS
            "PADDRIGHT" -> if (buffsHave) Page.BUFFS else null
            else -> null
        }
        Page.BUFFS -> null
    }

    /** the aura pane took press [k] and its cursor is [stillOn] it or not: the page now (Left off its end = the map controls) */
    fun afterBuffs(k: String, stillOn: Boolean): Page = when {
        stillOn -> Page.BUFFS
        k == "PADDLEFT" -> Page.CONTROLS
        else -> Page.QUESTS
    }

    /** a touch on the map's field: the map controls */
    fun touched(@Suppress("UNUSED_PARAMETER") page: Page): Page = Page.CONTROLS

    /** where B leads on [page] for the Home button's B glyph: "back" inside the module, "home" = the module closes */
    fun bLeads(page: Page, mapSays: String): String = if (page == Page.QUESTS) mapSays else "back"

    /** the controls line on the map controls page, in order: (button, word); [buffs] = Right reaches the buffs, [act] = A's word for
     *  what lies under the cursor (a quest's bubble: "Focus"), null = nothing */
    fun hints(buffs: Boolean, act: String?): List<Pair<String, String>> = buildList {
        add("lstick" to MOVE_WORD)
        add("rstick" to ZOOM_WORD)
        if (act != null) add("a" to act)
        add("left" to QUESTS_WORD)
        if (buffs) add("right" to BUFFS_WORD)
        add("b" to BACK_WORD)
    }

    /** the words (Tr: es / fr in i18n; "Zoom", "Back" and "Focus" were already there) */
    const val MOVE_WORD = "Move"
    const val ZOOM_WORD = "Zoom"
    const val QUESTS_WORD = "Quests"
    const val BUFFS_WORD = "Buffs"
    const val BACK_WORD = "Back"
    const val FOCUS_WORD = "Focus"
}
