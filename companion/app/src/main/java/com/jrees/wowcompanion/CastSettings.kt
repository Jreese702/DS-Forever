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

    var buttonLayout: ButtonLayout
        get() = enumOr(prefs?.getString("buttonLayout", null), ButtonLayout.NINTENDO)
        set(v) { prefs?.edit()?.putString("buttonLayout", v.name)?.apply(); changed() }

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
