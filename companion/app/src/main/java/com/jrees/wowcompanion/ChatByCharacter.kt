package com.jrees.wowcompanion

/**
 * EACH CHARACTER'S OWN CHAT (issue 622, 1.0.41: after a logout and a login on another character the bottom screen still showed the
 * first one's chat; the per-character rule). The chat lines shown ([lines], oldest first) are the character's now; the others' are
 * kept apart in memory and come back when that character does.
 *
 * Chat.lua's document names no character, and its lines can come before the padmap that names the new one. But every login is a new
 * add-on session (the document's `s`), so a switch takes along the lines of the sessions that began after the old character was
 * last seen ([character] with its key) or after the game's logout ([loggedOut], the "bye" document), whichever came later.
 * A /reload of the same character is a new session with the same key: nothing moves. Legacy lines (s = 0) never move.
 * The UI thread only. Pure.
 */
class ChatByCharacter<L> {
    /** the shown character's lines, oldest first; change them only through [add], [removeAt] and [clear] */
    var lines = ArrayList<L>()
        private set
    private var sessions = ArrayList<Long>()          // each line's session, in step with [lines]
    private val others = object : LinkedHashMap<String, Pair<ArrayList<L>, ArrayList<Long>>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<ArrayList<L>, ArrayList<Long>>>?) = size > MAX_CHARACTERS
    }
    /** the character shown (CharKey's whole key); null = not known yet (before the first padmap, or named with no realm) */
    var key: String? = null
        private set
    private val firstSeen = HashMap<Long, Int>()      // session -> its place among the sessions seen
    private var seen = 0
    private var confirmed = 0                         // [seen] when the shown character was last named
    private var bye = 0                               // [seen] at the last logout

    fun add(s: Long, l: L) {
        if (s != 0L && !firstSeen.containsKey(s)) firstSeen[s] = ++seen
        lines.add(l); sessions.add(s)
    }

    fun removeAt(i: Int): L { sessions.removeAt(i); return lines.removeAt(i) }

    fun clear() { lines.clear(); sessions.clear() }

    /** the game's logout ("bye"), or a client restart (the log truncated): the sessions after it are the next character's */
    fun loggedOut() { bye = seen }

    /** the character now ([k] null: named, its key not known yet). True when the shown lines changed */
    fun character(k: String?): Boolean {
        val old = key
        if (k == old) { if (k != null) confirmed = seen; return false }
        // what came since the old character was last named (or since the logout) is the new one's. With no key before (none yet, or
        // named with no realm: [confirmed] is still the last real key's), the same rule: what came before a logout is nobody's now
        // (the start screen's old log: another character's, maybe), and legacy lines (s = 0) go along
        val after = maxOf(confirmed, bye)
        val goL = ArrayList<L>(); val goS = ArrayList<Long>(); val stayL = ArrayList<L>(); val stayS = ArrayList<Long>()
        for (i in lines.indices) {
            val s = sessions[i]
            val go = if (s == 0L) old == null else (firstSeen[s] ?: 0) > after
            if (go) { goL.add(lines[i]); goS.add(s) } else { stayL.add(lines[i]); stayS.add(s) }
        }
        if (old != null && stayL.isNotEmpty()) others[old] = stayL to stayS
        val back = k?.let { others.remove(it) }
        lines = back?.first ?: ArrayList(); sessions = back?.second ?: ArrayList()
        lines.addAll(goL); sessions.addAll(goS)
        key = k
        if (k != null) confirmed = seen
        return true
    }

    companion object {
        /** characters whose chat is kept besides the shown one's */
        const val MAX_CHARACTERS = 16
    }
}
