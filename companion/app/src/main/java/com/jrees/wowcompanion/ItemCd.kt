package com.jrees.wowcompanion

import android.content.Context
import org.json.JSONObject

/**
 * THE ITEM COOLDOWNS, COUNTED HERE (Spells' Radial.lua r20 `itemcd`; the user's picks 2026-11-13, feat/item-cooldowns: "cooldowns on
 * radial slices AND bag slots: dark pie sweep + seconds (UiKit.radialCd), bags always on"; the clock extras "Hearthstone, class teleport
 * (Astral Recall etc., only once known), gold, free bag slots"; "Hearthstone shows ALWAYS: bright icon when ready, icon + mm:ss while
 * cooling"; "honour enable=0 (held potion: dim, no number)"; "Persist the hearthstone end time per name-realm").
 *
 * The add-on says each item's cooldown once, on an event: t = { "<id>" = { d, l, e? } | { h } (held) | { x } (running, length unknown) },
 * hs = { have, i } (the Hearthstone), tp = { id, n, i, cd | x } (the class teleport the player knows), fight. Out of a fight an id that is
 * not there is READY; in a fight it is "not known": what is counted stays (a countdown started before the fight keeps running). Only
 * LIVE documents are trusted (a replayed one is an old life's). Counted on the monotonic clock ([clock], ms). Keyed by ITEM id (its own
 * map: item and spell ids can collide), apart from [RadialModel.Cd] (the spell radials').
 */
object ItemCd {
    const val HEARTH = 6948
    /** the Hearthstone's icon (the game's INV_Misc_Rune_01) when the add-on has not said one yet */
    const val HEARTH_ICON = 134414

    private class E(val endMs: Long, val len: Double, val held: Boolean = false, val dim: Boolean = false)
    private val at = HashMap<Int, E>()
    @Volatile var clock: () -> Long = { System.nanoTime() / 1_000_000L }
    /** the wall clock (the Hearthstone's kept end time survives an app restart and a reboot) */
    @Volatile var wall: () -> Long = { System.currentTimeMillis() }

    /** the Hearthstone: in the bags (null = not said yet), its icon */
    @Volatile var hearthHave: Boolean? = null; private set
    @Volatile var hearthIcon: Int = HEARTH_ICON; private set

    /** the class teleport the character knows (null = none / not said): its spell id, name, icon; its countdown rides [tpE] */
    class Teleport(val id: Int, val name: String, val icon: Int)
    @Volatile var teleport: Teleport? = null; private set
    private var tpE: E? = null

    /** what is kept across app lives: the Hearthstone's end time per character (name-realm) */
    interface Store { fun get(key: String): String?; fun put(key: String, v: String?) }
    @Volatile var store: Store? = null
    private var who: String? = null
    /** whose the last LIVE document was (name-realm; review r20a: only then is the Hearthstone kept under [who]'s name). A switch to
     *  another character in one app life drops what was counted (the old character's countdowns are not the new one's) */
    private var liveWho: String? = null
    private const val ANON = "?anon"

    private val listeners = ArrayList<() -> Unit>()
    fun onChange(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    private fun changed() { val ls = synchronized(listeners) { listeners.toList() }; for (l in ls) l() }

    fun init(c: Context) {
        if (store != null) return
        val p = c.applicationContext.getSharedPreferences("wc_itemcd", Context.MODE_PRIVATE)
        store = object : Store {
            override fun get(key: String) = p.getString(key, null)
            override fun put(key: String, v: String?) { if (v == null) p.edit().remove(key).apply() else p.edit().putString(key, v).apply() }
        }
    }

    @Synchronized fun reset() { at.clear(); tpE = null; teleport = null; hearthHave = null; hearthIcon = HEARTH_ICON; who = null; liveWho = null }

    /** another character than the one counted: everything counted was the old one's (review r20a: never shown or kept as the new one's) */
    private fun switchTo(key: String) {
        if (who != null && who != key) { at.clear(); tpE = null; teleport = null; hearthHave = null; hearthIcon = HEARTH_ICON }
        who = key
    }

    private fun entry(o: JSONObject, now: Long): E? = when {
        o.optBoolean("h", false) -> E(Long.MAX_VALUE, 0.0, held = true)
        o.optBoolean("x", false) -> E(Long.MAX_VALUE, 0.0, dim = true)
        else -> {
            val d = o.optDouble("d", 0.0); val l = o.optDouble("l", 0.0)
            if (d > 0.0 && l > 0.0) E(now + (l * 1000.0).toLong(), d) else null
        }
    }

    /** an `itemcd` document; [current] = sent this app life (a replay is ignored). true = it was one */
    fun onDoc(msg: JSONObject, current: Boolean = true, now: Long = clock()): Boolean {
        if (msg.optString("type") != "itemcd") return false
        if (!current) return true
        synchronized(this) {
            // r20a: the doc says whose it is (Radial r20a `who`); a different character than counted = a switch first
            val dw = msg.optString("who", "")
            if (dw.isNotEmpty() && dw != who) switchTo(dw)
            liveWho = if (dw.isNotEmpty()) dw else (who ?: ANON)   // no name said and none known yet: the next character named owns it
            val fight = msg.optBoolean("fight", false)
            val t = msg.optJSONObject("t")
            val seen = HashSet<Int>()
            t?.keys()?.forEach { k ->
                val id = k.toIntOrNull() ?: return@forEach
                val o = t.optJSONObject(k) ?: return@forEach
                seen += id
                val e = entry(o, now)
                if (e != null) at[id] = e else at.remove(id)
            }
            if (!fight) at.keys.retainAll(seen)                 // out of a fight: not said = ready
            msg.optJSONObject("hs")?.let { h ->
                hearthHave = if (h.has("have")) h.optBoolean("have") else null
                h.optInt("i", 0).takeIf { it > 0 }?.let { hearthIcon = it }
            }
            val tp = msg.optJSONObject("tp")
            if (tp == null || tp.optInt("id", 0) <= 0) { teleport = null; tpE = null }
            else {
                teleport = Teleport(tp.optInt("id"), tp.optString("n", ""), tp.optInt("i", 0))
                val cd = tp.optJSONObject("cd")
                tpE = when {
                    tp.optBoolean("x", false) -> E(Long.MAX_VALUE, 0.0, dim = true)
                    cd != null -> entry(cd, now)
                    fight -> tpE                                 // in a fight: what is counted stays
                    else -> null
                }
            }
            keepHearth(now)
        }
        changed()
        return true
    }

    /** seconds left on item [id], 0.0 = ready / unknown / held / dim */
    @Synchronized fun left(id: Int, now: Long = clock()): Double = leftOf(at[id], now) { at.remove(id) }
    /** the cooldown's whole length in s, 0.0 = none counted */
    @Synchronized fun len(id: Int, now: Long = clock()): Double = if (left(id, now) > 0.0) at[id]?.len ?: 0.0 else 0.0
    /** dimmed with no number: held until the fight ends (a potion), or running with its length unknown */
    @Synchronized fun dim(id: Int): Boolean = at[id]?.let { it.held || it.dim } ?: false
    /** any countdown counting (the views redraw while one runs; a dim needs no redraw) */
    @Synchronized fun anyRunning(now: Long = clock()): Boolean = at.keys.toList().any { left(it, now) > 0.0 } || teleportLeft(now) > 0.0

    @Synchronized fun teleportLeft(now: Long = clock()): Double = leftOf(tpE, now) { tpE = null }
    @Synchronized fun teleportLen(now: Long = clock()): Double = if (teleportLeft(now) > 0.0) tpE?.len ?: 0.0 else 0.0
    @Synchronized fun teleportDim(): Boolean = tpE?.dim ?: false

    private inline fun leftOf(e: E?, now: Long, gone: () -> Unit): Double {
        if (e == null || e.held || e.dim) return 0.0
        val l = (e.endMs - now) / 1000.0
        if (l <= 0.0) { gone(); return 0.0 }
        return l
    }

    @Synchronized fun note(id: Int, len: Double, left: Double, now: Long = clock()) {
        if (id <= 0) return
        if (len > 0.0 && left > 0.0) at[id] = E(now + (left * 1000.0).toLong(), len) else at.remove(id)
    }

    // ---------------------------------------------------------------- the Hearthstone's end time, kept per character
    private fun hsKey(k: String) = "hs:$k"

    /** the character now ("name-realm", a current padmap): its kept Hearthstone end time counts until the game's own word comes */
    fun character(key: String, now: Long = clock()) {
        synchronized(this) {
            if (key == who) return
            val prev = who
            switchTo(key)
            if (liveWho == key || (prev == null && liveWho == ANON)) { liveWho = key; keepHearth(now); return }  // the game's word this life is this character's: kept under its name
            at.remove(HEARTH)
            val s = store?.get(hsKey(key)) ?: return
            val parts = s.split(',')
            val endWall = parts.getOrNull(0)?.toLongOrNull() ?: return
            val len = parts.getOrNull(1)?.toDoubleOrNull() ?: return
            val left = (endWall - wall()) / 1000.0
            if (left > 0.0 && len > 0.0) at[HEARTH] = E(now + (left * 1000.0).toLong(), len)
        }
        changed()
    }

    private fun keepHearth(now: Long) {
        val k = who ?: return
        if (liveWho != k) return                           // r20a: only the game's own word for THIS character is kept under its name
        val e = at[HEARTH]
        val l = leftOf(e, now) { }
        store?.put(hsKey(k), if (e != null && l > 0.0) "${wall() + (l * 1000.0).toLong()},${e.len}" else null)
    }

    /** "m:ss" (under an hour) or "h:mm" for a countdown of [s] seconds (the clock extras) */
    fun mmss(s: Double): String {
        val t = kotlin.math.ceil(s).toLong().coerceAtLeast(0L)
        return if (t >= 3600L) "${t / 3600}:${"%02d".format((t % 3600) / 60)}h" else "${t / 60}:${"%02d".format(t % 60)}"
    }
}
