package com.jrees.wowcompanion

/**
 * THE CONTROLLER ROOM'S CHOOSER TABS, PURE (PadTabCtl p39). The user, 2026-11-11, on 1428: "The in combat tab of the controller bindings
 * is missing all 5 pages of options, it only shows the first 3. The icons for those 5 tabs look weird being narrowed".
 *
 * Which tabs: all five (Spells, General, Professions, Extra, DS) on WoW Defaults AND In combat; only the three spell tabs where nothing
 * but a spell can go - the spell radial, on the In combat tab a base X / Y / A (its fight spell), a form's own set (PadMap m55) and the
 * bare D-pad while the DS WoW toolbar holds it (p36), and out of combat a combat button (its Otherwise spell). PadTabCtl's onlySpells()
 * is the same rule (the controller's tab row and the finger's must match: the "extra" pad row counts by them).
 *
 * How they look: five tabs share the narrow chooser (about 64 px each, the row 84 high); each tab is drawn SQUARE in its slot (the
 * frame and its icon), never stretched to 64 x 84. Three tabs keep their 84 x 84.
 */
object ChooserTabs {
    val SPELL_TABS = listOf("spells", "general", "professions")
    val ALL_TABS = listOf("spells", "general", "professions", "extra", "dstool")

    /** [view] the room's tab; [face] a base X / Y / A; [form] a form's own set on show; [toolbarDpad] the bare D-pad the DS WoW toolbar
     *  holds; [combatButton] the button carries a fight spell */
    fun spellsOnly(view: String, face: Boolean, form: Boolean, toolbarDpad: Boolean, combatButton: Boolean): Boolean = when (view) {
        "radial" -> true
        "combat" -> face || form || toolbarDpad
        else -> combatButton
    }

    fun keys(spellsOnly: Boolean): List<String> = if (spellsOnly) SPELL_TABS else ALL_TABS

    /** the tab's own square in its slot ([l]..[r] x [t]..[b]): as wide as the slot, centred on its height (never taller than it) */
    fun square(l: Float, t: Float, r: Float, b: Float): PartyLayout.Box {
        val s = minOf(r - l, b - t)
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        return PartyLayout.Box(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f)
    }
}
