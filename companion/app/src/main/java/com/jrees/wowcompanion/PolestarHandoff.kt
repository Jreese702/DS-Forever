package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * POLESTAR ON THE PLAYER'S STEP WHEN A STREAM STARTS (fix/desk-click-switch; the user, 2026-11-19: Polestar on the PC "is still not on
 * my step"). Polestar's files only reach the PC as fresh as the Thor's last CLEAN exit of its own WoW (WoW writes SavedVariables on a
 * logout, a /reload or a clean quit; Android kills it), so the PC's Polestar can open on an older guide or step. The app has seen the
 * guide and step itself, live, in the Thor's own game (the add-on's `pguide`: guide stamp `gs`, shown step `s`, live step `l`): it is
 * kept per character ([Saved], the host's prefs, keyed by name-realm like every character store) while playing Local, and at a stream
 * session's first current `pguide` from the PC (Polestar there, a guide loaded, out of a fight) the PC's Polestar is put on it ONCE
 * with the menu's own framed commands: CONTINUE the saved guide's tab when the PC shows another (its tabs come with an open menu),
 * then LOOK at the saved step (or BACK to the live one when that is where the player was).
 * USER RULING (2026-11-19, confirmed with "Off once quiet clicks work"): "Yes, auto-sync at start" - once per stream, after login (the
 * PC's first current `pguide`, Polestar there), not in a fight, and the player's own menu press stops it. The user asked for this (an exception
 * to "only the player's press sends anything", like the default focus push); the player's own press in the menu first ends it; at
 * most [MAX_ACTS] frames a session; a step it can't reach is left to the player with one log line. Pure.
 */
class PolestarHandoff {
    /** what the Thor's own game showed last: guide stamp, title (a log never says it), the shown and the live step, when (ms) */
    data class Saved(val gs: Int, val gid: String?, val shown: Int, val live: Int?, val at: Long) {
        fun json(): JSONObject = JSONObject().put("gs", gs).put("gid", gid ?: "").put("s", shown).put("l", live ?: -1).put("at", at)
        companion object {
            fun parse(s: String?): Saved? = runCatching {
                val o = JSONObject(s ?: return null)
                Saved(o.getInt("gs"), o.optString("gid").ifEmpty { null }, o.getInt("s"), o.optInt("l", -1).takeIf { it > 0 }, o.getLong("at"))
            }.getOrNull()
            /** a Local `pguide` worth keeping: Polestar there, a guide loaded, a shown step */
            fun of(g: PolestarTab.Guide, now: Long): Saved? =
                if (!g.have || g.none || g.stamp < 0 || g.shown == null) null else Saved(g.stamp, g.gid, g.shown, g.live, now)
        }
    }

    sealed class Act {
        object None : Act()
        /** the menu opened (force): the PC's tabs come with its next pguide */
        object Open : Act()
        data class Continue(val tab: Int, val stamp: Int) : Act()
        data class Look(val step: Int, val stamp: Int) : Act()
        data class Back(val stamp: Int) : Act()
        data class Done(val line: String) : Act()
    }

    private var over = false
    private var acts = 0
    private var opened = false
    private var continued = false

    /** a new stream session (or Local again): one hand-off each session */
    fun reset() { over = false; acts = 0; opened = false; continued = false }
    /** the player pressed in the Polestar menu: theirs from now on */
    fun playerPressed() { if (!over) over = true }
    val finished: Boolean get() = over

    /**
     * the PC's current `pguide` [g] while streaming, the [saved] Local state, [fight]: the one framed command to send now, a closing
     * line ([Act.Done], said once), or nothing
     */
    fun onGuide(g: PolestarTab.Guide, saved: Saved?, fight: Boolean): Act {
        if (over) return Act.None
        if (!g.have || g.none) return Act.None                       // (Polestar not there yet, no guide yet: wait)
        if (saved == null) return end("polestar handoff: nothing kept from the Thor's own game")
        if (fight) return Act.None
        if (acts >= MAX_ACTS) return end("polestar handoff: gave up after $acts frames")
        if (g.stamp != saved.gs) {
            if (continued) return Act.None                           // (the CONTINUE's answer, then its new guide: wait)
            val tabs = g.tabs
            if (tabs == null) {
                if (opened) return Act.None
                opened = true; acts++
                return Act.Open
            }
            val t = tabs.indexOfFirst { it.stamp == saved.gs }
            if (t < 0) return end("polestar handoff: the Thor's guide is not open on the PC; left to the player")
            continued = true; acts++
            return Act.Continue(t + 1, tabs[t].stamp)
        }
        // the same guide: on the player's step
        val want = saved.shown
        if (g.shown == want) return end("polestar handoff: the PC's Polestar is on the player's step")
        acts++
        over = true
        return if (want == saved.live && g.live == want) Act.Back(g.stamp) else Act.Look(want, g.stamp)
    }

    private fun end(line: String): Act { over = true; return Act.Done(line) }

    companion object {
        const val MAX_ACTS = 3
        const val PREFS = "polestar_handoff"
        fun key(character: String) = "saved:$character"
    }
}
