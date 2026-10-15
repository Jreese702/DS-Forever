package com.jrees.wowcompanion

import android.content.Context
import android.content.SharedPreferences

/**
 * The Cast room's own settings (SharedPreferences "cast"). Same pattern as [Settings]: one object,
 * read anywhere, listeners fire on the main thread after every write.
 *
 * The Settings room renders these as three rows (requested via AGENT_LOG; SettingsView is not
 * ours). The API it needs:
 *
 *   CastSettings.init(context)
 *   CastSettings.buttonLayout      : ButtonLayout      (NINTENDO | XBOX | PLAYSTATION)
 *   CastSettings.combatCooldowns   : CombatCooldowns   (ONOFF | ESTIMATE)
 *   CastSettings.triggerSwap       : Boolean
 *   CastSettings.onChange(l) / removeOnChange(l)
 *
 * Every value has a [label] for the row, a single capitalised word.
 */
object CastSettings {

    /**
     * Which controller's glyph art the Cast room draws. NINTENDO is the default: the Thor's buttons
     * are printed Nintendo-style (Y left, X top, A right, B bottom). There is no separate Thor preset
     * any more; a stored "THOR" from an older build reads back as NINTENDO.
     */
    enum class ButtonLayout(val label: String) {
        NINTENDO("Nintendo"), XBOX("Xbox"), PLAYSTATION("PlayStation")
    }

    /**
     * What a cooldown looks like in combat, where the client makes its timing secret.
     * ONOFF: the button only dims while it is cooling down. ESTIMATE: it also shows a dashed
     * sweep timed from the last real duration seen for that spell (an estimate, drawn so it can
     * never be mistaken for the real sweep).
     */
    enum class CombatCooldowns(val label: String) {
        ONOFF("Dim"), ESTIMATE("Estimate")
    }

    private var prefs: SharedPreferences? = null
    private val listeners = ArrayList<() -> Unit>()

    fun init(c: Context) {
        if (prefs == null) prefs = c.applicationContext.getSharedPreferences("cast", Context.MODE_PRIVATE)
    }

    /** the BUTTON GLYPHS (f-abxy-split: the pictures only; never what a button does) */
    var buttonLayout: ButtonLayout
        get() = enumOr(prefs?.getString("buttonLayout", null), ButtonLayout.NINTENDO)
        set(v) { prefs?.edit()?.putString("buttonLayout", v.name)?.apply(); changed() }

    /**
     * f-abxy-split (the user 2026-10-15: "users have button mods and need the same swap. I need to seperate this into two different
     * settings, one for ABXY location (literally swapping which button is where like with the nintendo layout) and one for button
     * layout to just change the glyphs"). WHERE each face action sits: XBOX = A bottom, B right, X left, Y top; NINTENDO = A right,
     * B bottom, X top, Y left (the Thor's print). It moves the pictures to where the buttons are and the add-on's cursor with them; the
     * glyph set is [buttonLayout]. Any combination works.
     */
    enum class AbxyLocation(val label: String) { XBOX("Xbox"), NINTENDO("Nintendo") }

    /** before the split one setting did both: no stored location = today's look kept (Nintendo glyphs = the Nintendo location) */
    var abxyLocation: AbxyLocation
        get() = locationFor(prefs?.getString(KEY_LOCATION, null), prefs?.getString("buttonLayout", null))
        set(v) { prefs?.edit()?.putString(KEY_LOCATION, v.name)?.apply(); changed() }

    /** the face buttons are drawn (and moved among) in the Nintendo arrangement */
    val faceSwap: Boolean get() = abxyLocation == AbxyLocation.NINTENDO

    /** the migration, pure: a stored location wins; else the old single setting decides (no setting at all = Nintendo, the default) */
    fun locationFor(storedLocation: String?, storedLayout: String?): AbxyLocation {
        AbxyLocation.values().firstOrNull { it.name == storedLocation }?.let { return it }
        val layout = enumOr(storedLayout, ButtonLayout.NINTENDO)
        return if (layout == ButtonLayout.NINTENDO) AbxyLocation.NINTENDO else AbxyLocation.XBOX
    }
    private const val KEY_LOCATION = "abxyLocation"

    // FIXED (Settings rework, user 2026-10-06: "useless to me"): always Dim, whatever an older build saved; the row is gone
    // from Settings, and the setter is kept only so older callers still compile (it changes nothing)
    var combatCooldowns: CombatCooldowns
        get() = CombatCooldowns.ONOFF
        @Suppress("UNUSED_PARAMETER") set(v) {}

    /**
     * Swap which physical trigger counts as LT and which as RT. The evdev mapping (BTN_TL2 /
     * ABS_BRAKE = LT, BTN_TR2 / ABS_GAS = RT) is ASSUMED until calibrated on the device.
     */
    // FIXED (Settings rework, 2026-10-06): never swapped, whatever was saved; the setter changes nothing
    var triggerSwap: Boolean
        get() = false
        @Suppress("UNUSED_PARAMETER") set(v) {}

    fun onChange(l: () -> Unit) { if (l !in listeners) listeners.add(l) }
    fun removeOnChange(l: () -> Unit) { listeners.remove(l) }

    private fun changed() { for (l in listeners.toTypedArray()) l() }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E =
        if (name == null) def else enumValues<E>().firstOrNull { it.name == name } ?: def
}

/**
 * f-abxy-split: EVERY glyph follows the BUTTON GLYPHS setting. Many rooms name their glyphs in one fixed set ("rev_a" = Nintendo's A,
 * "rev_minus" ...); [translate] turns a face / shoulder / trigger / Select glyph of ANY set into the chosen set's, by the ACTION it
 * stands for (a, b, x, y = Xbox's A / B / X / Y; PlayStation's cross / circle / square / triangle). Names it does not know (the D-pad's
 * arrows, the sticks, the "gen_" set) are kept. Pure.
 */
object Glyphs {
    private val PREFIX = mapOf(CastSettings.ButtonLayout.NINTENDO to "rev", CastSettings.ButtonLayout.XBOX to "ltr",
        CastSettings.ButtonLayout.PLAYSTATION to "shp")
    /** a set's key -> the action ("a" "b" "x" "y", or a shoulder / trigger / "select") */
    private val TO_ACTION = mapOf(
        "a" to "a", "b" to "b", "x" to "x", "y" to "y",
        "cross" to "a", "circle" to "b", "square" to "x", "triangle" to "y",
        "lshoulder" to "lshoulder", "rshoulder" to "rshoulder", "ltrigger" to "ltrigger", "rtrigger" to "rtrigger",
        "minus" to "select", "view" to "select", "share" to "select")
    /** the action -> the chosen set's key */
    private fun keyOf(action: String, layout: CastSettings.ButtonLayout): String = when (layout) {
        CastSettings.ButtonLayout.PLAYSTATION -> when (action) { "a" -> "cross"; "b" -> "circle"; "x" -> "square"; "y" -> "triangle"
            "select" -> "share"; else -> action }
        CastSettings.ButtonLayout.XBOX -> if (action == "select") "view" else action
        CastSettings.ButtonLayout.NINTENDO -> if (action == "select") "minus" else action
    }
    fun translate(name: String, layout: CastSettings.ButtonLayout): String {
        val us = name.indexOf('_')
        if (us <= 0) return name
        val pre = name.substring(0, us)
        if (pre != "rev" && pre != "ltr" && pre != "shp") return name
        val action = TO_ACTION[name.substring(us + 1)] ?: return name
        return PREFIX.getValue(layout) + "_" + keyOf(action, layout)
    }
}
