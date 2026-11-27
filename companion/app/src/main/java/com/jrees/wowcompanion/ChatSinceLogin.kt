package com.jrees.wowcompanion

/**
 * THE CHAT SINCE THIS LOGIN (issue 622, the user 2026-11-27: the player was looking for the chat to clear when logging out). The chat
 * lines shown ([lines], oldest first) are this login's only: a logout (the game's "bye"), a client restart or a switch to another
 * character clears them, and nothing is kept for later. The app's own /reload clears nothing (the host does not call [loggedOut]).
 *
 * Chat.lua's document names no character, and the new login's first lines can come before the padmap that names it. But every login
 * is a new add-on session (the document's `s`): a clear ends the sessions seen so far, so their lines go and a late copy of their
 * ring never brings them back, while a session first seen after it (the new login's) stays. A switch with no logout seen ends the
 * sessions up to the old character's last padmap. Legacy lines (s = 0) go at a switch. The UI thread only. Pure.
 */
class ChatSinceLogin<L> {
    /** the lines shown, oldest first; change them only through [add], [removeAt], [clear] and [loggedOut] */
    val lines = ArrayList<L>()
    private val sessions = ArrayList<Long>()          // each line's session, in step with [lines]
    private val order = HashMap<Long, Int>()          // session -> its place among the sessions seen
    private var seen = 0
    private var named = 0                             // [seen] when the character shown was last named
    private var ended = 0                             // the sessions up to this place are over: they add nothing again
    /** the character named last (CharKey's whole key); null = none yet, or named with no realm */
    var key: String? = null
        private set

    /** a line of session [s]; false when that session is over (before a logout or a switch) */
    fun add(s: Long, l: L): Boolean {
        if (s != 0L && order.getOrPut(s) { ++seen } <= ended) return false
        lines.add(l); sessions.add(s)
        return true
    }

    fun removeAt(i: Int): L { sessions.removeAt(i); return lines.removeAt(i) }

    fun clear() { lines.clear(); sessions.clear() }

    /** the game's logout ("bye") or a client restart (the log truncated): everything goes. True when lines went */
    fun loggedOut(): Boolean {
        ended = seen
        val had = lines.isNotEmpty()
        clear()
        return had
    }

    /** the character now ([k] null: named, its key not known yet). True when the old character's lines went */
    fun character(k: String?): Boolean {
        val old = key
        key = k
        if (k == old) { if (k != null) named = seen; return false }
        ended = maxOf(ended, named)
        var went = false
        for (i in lines.indices.reversed()) {
            val s = sessions[i]
            if (if (s == 0L) old != null else (order[s] ?: 0) <= ended) { removeAt(i); went = true }
        }
        if (k != null) named = seen
        return went
    }
}
