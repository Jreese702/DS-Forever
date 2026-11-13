package com.jrees.wowcompanion

/**
 * THE CONTROLLER ROOM'S CHOOSER TABS, PURE (PadTabCtl p39). The user, 2026-11-11, on 1428: "The in combat tab of the controller bindings
 * is missing all 5 pages of options, it only shows the first 3. The icons for those 5 tabs look weird being narrowed".
 *
 * Which tabs (PadTabCtl p43; the user, 2026-11-12, on 1429: "In combat still does not show all 5 tabs", then the pick "All 5 on every
 * button"): all five (Spells, General, Professions, Extra, DS) on WoW Defaults AND on EVERY In combat button - a base X / Y / A, a
 * form's own set, the bare D-pad with or without the DS WoW toolbar, every page and ZL / ZR / ZL + ZR set. An Extra / DS entry chosen
 * there goes on that button's FIGHT LAYER only (PadMap m132 / m141); its WoW Defaults assignment never changes. Only the three spell tabs
 * where nothing but a spell can go: the spell radial, and out of combat a combat button (its Otherwise spell). PadTabCtl's onlySpells()
 * is the same rule on the same inputs (the controller's tab row and the finger's must match: the "extra" pad row counts by them).
 *
 * How they look: five tabs share the narrow chooser (about 64 px each, the row 84 high); each tab is drawn SQUARE in its slot (the
 * frame and its icon), never stretched to 64 x 84. Three tabs keep their 84 x 84.
 */
object ChooserTabs {
    val SPELL_TABS = listOf("spells", "general", "professions")
    val ALL_TABS = listOf("spells", "general", "professions", "extra", "dstool")

    /** [view] the room's tab ("normal", "combat", "radial", ...); [combatButton] the button carries a fight spell (WoW Defaults' Otherwise) */
    fun spellsOnly(view: String, combatButton: Boolean): Boolean = when (view) {
        "radial" -> true
        "combat" -> false
        else -> combatButton
    }

    /** p43 the bindings' grey (PadMap m34 bindAllowed + m141; PadTabCtl bindHere): why a game binding cannot go on [set] / [pos], as the
     *  add-on's reason ("form" a form's own set on show, "fixed" the base X / Y / A / B, "layer" ZR / ZL + ZR, "dpad bar" the bare
     *  D-pad while [toolbar] holds it), or null. The rows stay in the list (the "extra" row counts them), drawn dim with the reason */
    fun bindBlock(set: Int, pos: Int, toolbar: Boolean, form: Boolean): String? = when {
        PadMapModel.isStick(pos) -> if (set == 1 || set == 2) null else "layer"      // m150: L3 / R3, bare or ZL (PadMap bindAllowed)
        form -> "form"
        set == 1 && pos >= 5 -> "fixed"
        set == 3 || set == 4 -> "layer"
        set == 1 && toolbar -> "dpad bar"
        else -> null
    }

    fun keys(spellsOnly: Boolean, stick: Boolean = false): List<String> = when {
        stick -> STICK_TABS                                                 // m150 / p49: a stick takes the game bindings only
        spellsOnly -> SPELL_TABS
        else -> ALL_TABS
    }
    /** PadTabCtl p49: L3 / R3's chooser: Extra alone, listing the four game bindings */
    val STICK_TABS = listOf("extra")

    /** PadTabCtl p41 / PadMap m132 THE FIGHT-ONLY LAYER (the user, 2026-11-11: "Build a fight-only layer"; p40's "replace both" box
     *  retired): a command / binding / mount / item from the In combat tab goes on [combo]'s FIGHT LAYER only - what the button does out of
     *  combat (WoW Defaults) is not touched, so the box names only the fight-layer entry it replaces, [fight] (a fight spell or another
     *  whole-button entry). An empty fight layer has no box (it goes straight on) */
    fun fightLayerBox(combo: String, thing: String, fight: String?): Triple<String, String, String> =
        Triple("${fight ?: "?"} is $combo's In combat entry.", "Replace it with $thing?", "Replace")

    /** the tab's own square in its slot ([l]..[r] x [t]..[b]): as wide as the slot, centred on its height (never taller than it) */
    fun square(l: Float, t: Float, r: Float, b: Float): PartyLayout.Box {
        val s = minOf(r - l, b - t)
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        return PartyLayout.Box(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f)
    }
}
