package com.jrees.wowcompanion

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * THE SPELL RADIALS' OWN SETTINGS (the user's picks 2026-11-08: "I want to be able to pick my own icon on each radial as well" -> the
 * game's macro icon grid; "Type a name"; "Each radial should have their own individual radial behavior settings like tap or hold").
 * The spells of a radial are the add-on's (Radial.lua q2: they need secure cells); its NAME, ICON and OPENING (Hold / Tap) are the app's,
 * per character (the per-character ruling: "name-realm") and per radial SLOT (1..6, the slot of its bar command DSSRADIAL / DSSRADIAL<n>).
 * Unset: "Radial <n>", the command's own icon, the spell wheel's Open setting (HomeSettings.spellTap). A deleted radial's settings go
 * ([forget]); a new radial in that slot starts fresh.
 *
 * The bar commands wear them ([dress]): PadMapModel's command lists and a button's command come through it, so the chooser, the room's
 * button pictures and Home's bars show the radial's own name and icon.
 */
object RadialMeta {
    data class Meta(val name: String? = null, val icon: Int = 0, val tap: Boolean? = null)

    private var prefs: SharedPreferences? = null
    private var all = JSONObject()
    private val listeners = ArrayList<() -> Unit>()

    /** the character now ("name-realm", CombatKeep.who from a current padmap); null = not known yet (nothing is saved then) */
    @Volatile var who: String? = null
        set(v) { if (field != v) { field = v; changed() } }

    fun init(c: Context) {
        if (prefs != null) return
        prefs = c.applicationContext.getSharedPreferences("radial_meta", Context.MODE_PRIVATE)
        all = runCatching { JSONObject(prefs?.getString("all", "{}") ?: "{}") }.getOrDefault(JSONObject())
        PadMapModel.dress = { c -> dress(c) }
    }

    fun onChange(l: () -> Unit) { listeners += l }
    private fun changed() { for (l in listeners.toList()) runCatching { l() } }

    /** the radial slot a bar command opens (DSSRADIAL = 1, DSSRADIAL<n> = n), or null for any other command */
    fun slotOf(key: String?): Int? {
        if (key == null || !key.startsWith("DSSRADIAL")) return null
        val rest = key.removePrefix("DSSRADIAL")
        return if (rest.isEmpty()) 1 else rest.toIntOrNull()?.takeIf { it in 2..6 }
    }

    private fun mine(): JSONObject? = who?.let { w -> all.optJSONObject(w) }

    fun get(slot: Int): Meta {
        val o = mine()?.optJSONObject(slot.toString()) ?: return Meta()
        return Meta(o.optString("n", "").takeIf { it.isNotEmpty() }, o.optInt("i", 0), if (o.has("t")) o.optBoolean("t") else null)
    }

    fun defaultName(slot: Int) = "Radial $slot"
    fun name(slot: Int): String = get(slot).name ?: defaultName(slot)
    /** the chosen icon, 0 = none chosen (the caller's own fallback) */
    fun icon(slot: Int): Int = get(slot).icon
    /** Tap (true: opens and stays) or Hold (false: open while held); unset = the spell wheel's Open setting */
    fun tap(slot: Int): Boolean = get(slot).tap ?: HomeSettings.spellTap

    private fun edit(slot: Int, f: (JSONObject) -> Unit) {
        val w = who ?: return
        val per = all.optJSONObject(w) ?: JSONObject().also { all.put(w, it) }
        val o = per.optJSONObject(slot.toString()) ?: JSONObject().also { per.put(slot.toString(), it) }
        f(o)
        prefs?.edit()?.putString("all", all.toString())?.apply()
        changed()
    }

    /** a typed name; blank = back to "Radial <n>" (at most [NAME_MAX] characters) */
    fun setName(slot: Int, name: String) = edit(slot) { o ->
        val t = name.trim().take(NAME_MAX)
        if (t.isEmpty()) o.remove("n") else o.put("n", t)
    }
    fun setIcon(slot: Int, fileID: Int) = edit(slot) { o -> if (fileID > 0) o.put("i", fileID) else o.remove("i") }
    fun setTap(slot: Int, tap: Boolean) = edit(slot) { o -> o.put("t", tap) }

    /** radial [slot] was deleted: its settings go (a new radial there starts fresh) */
    fun forget(slot: Int) {
        val w = who ?: return
        val per = all.optJSONObject(w) ?: return
        if (per.remove(slot.toString()) == null) return
        prefs?.edit()?.putString("all", all.toString())?.apply()
        changed()
    }

    /** the radials that exist now (the add-on's slots): any other slot's settings go */
    fun keepOnly(slots: Collection<Int>) {
        val w = who ?: return
        val per = all.optJSONObject(w) ?: return
        val gone = per.keys().asSequence().toList().filter { k -> k.toIntOrNull() !in slots }
        if (gone.isEmpty()) return
        for (k in gone) per.remove(k)
        prefs?.edit()?.putString("all", all.toString())?.apply()
        changed()
    }

    /** a spell radial's command wears its radial's name and icon */
    fun dress(c: PadMapModel.Command): PadMapModel.Command {
        val slot = slotOf(c.key) ?: return c
        val m = get(slot)
        return c.copy(name = m.name ?: defaultName(slot), icon = m.icon.takeIf { it > 0 } ?: c.icon)
    }

    const val NAME_MAX = 24
}
