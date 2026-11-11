package com.jrees.wowcompanion

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * POLESTAR'S AUTOMATION, OFF BY DEFAULT (pq1; the user, 2026-11-11: "there needs to be a polestar setting added that is off by default
 * for auto quest accept and auto vendor ... and auto repair"; the picks: one switch for accepting AND turning in guide quests, "auto
 * vendor" = selling greys and buying what a step needs, Polestar keeps what DS Forever set). Three switches per character, all off until
 * turned on; Map.lua (WC.pqol) writes them into Polestar's own options at every log in and on every change. Its answer
 * {"type":"pqol", have, m, w}: [w] = the choice the add-on holds; a difference from ours is tapped on the "pnav" row: 38, then the mask.
 */
object PolestarQol {
    const val QUEST = 1; const val VENDOR = 2; const val REPAIR = 4
    const val COL_NEXT = 38
    private const val RETRY_MS = 4_000L

    private var prefs: SharedPreferences? = null
    fun init(c: Context) { if (prefs == null) prefs = c.applicationContext.getSharedPreferences("polestar_qol", Context.MODE_PRIVATE) }

    /** the add-on's last word: Polestar there, what it does now, the choice the add-on holds (null = no answer yet) */
    @Volatile var have = false; private set
    @Volatile var now: Int? = null; private set
    @Volatile var held: Int? = null; private set
    private var sentAt = Long.MIN_VALUE / 2

    fun onDoc(d: JSONObject) {
        have = d.optBoolean("have")
        now = if (d.has("m")) d.optInt("m") else null
        held = if (d.has("w")) d.optInt("w") else null
    }

    /** this character's choice (all off by default) */
    fun want(who: String?): Int = who?.let { prefs?.getInt("pq.$it", 0) } ?: 0
    fun on(who: String?, bit: Int): Boolean = want(who) and bit != 0
    fun set(who: String?, bit: Int, on: Boolean) {
        val k = who ?: return
        val m = if (on) want(k) or bit else want(k) and bit.inv()
        prefs?.edit()?.putInt("pq.$k", m)?.apply()
        sentAt = Long.MIN_VALUE / 2                      // sent at the next tick
    }

    /** Pure: the "pnav" taps to send now (empty = none): our choice differs from the one the add-on holds, at most every [RETRY_MS] */
    fun taps(who: String?, nowMs: Long): List<Int> {
        if (who == null || !have) return emptyList()
        val h = held ?: return emptyList()
        val w = want(who)
        if (w == h || nowMs - sentAt < RETRY_MS) return emptyList()
        sentAt = nowMs
        return listOf(COL_NEXT, w)
    }

    /** the Settings row's word for [bit] */
    fun line(who: String?, bit: Int, onWord: String, offWord: String): String {
        val w = on(who, bit)
        val n = now
        return when {
            !have || n == null -> if (w) "On (applied when the game runs)" else "Off (applied when the game runs)"
            (n and bit != 0) != w -> "Sent"
            w -> onWord
            else -> offWord
        }
    }
}
