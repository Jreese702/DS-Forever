package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * HOLD TO TALK'S LAST USED CHAT, PER CHARACTER (the user, 2026-11-16, pick "Remember per character"): the chat the player's last send
 * went to - its kind (ChatStream's keys: SAY .. WHISPER, REPLY, "CHANNEL:<n>"), the whisper's person, and for a numbered channel the
 * channel's NAME (the numbers move as channels are joined and left: ChatFilter.chanKey's rule) - kept per character ("Name-Realm",
 * GameUi.character's form, as HomeSettings / ChatFilter key theirs), back when that character's padmap names it. NEVER the message's
 * words. A kept chat that is not there now (left the group / guild, the channel left) reads as Say ([resolve]); no character known = in
 * memory only, Say to start.
 */
object TalkKeep {
    /** a kept chat: [type] its kind, [whisper] the whisper's person (WHISPER only), [chan] a numbered channel's name ("Trade - City") */
    data class Used(val type: String = SAY, val whisper: String = "", val chan: String = "")

    const val SAY = "SAY"

    /** where the chats are kept: the whole map (character -> chat) as one JSON text */
    interface Store { fun read(): String?; fun write(s: String) }

    private var store: Store? = null
    private var all = JSONObject()
    private val listeners = ArrayList<() -> Unit>()

    /** the character now ("name-realm"); null = not known yet: nothing is saved */
    @Volatile var who: String? = null
        private set

    /** the last used chat now (as kept; [resolve] says what it is now) */
    var used = Used(); private set

    fun init(s: Store) {
        if (store != null) return
        store = s
        all = runCatching { JSONObject(s.read() ?: "{}") }.getOrDefault(JSONObject())
        if (load()) changed()
    }

    /** the character the padmap names now: its own last used chat (Say when it has none) */
    fun character(name: String?) {
        val n = name?.takeIf { it.isNotEmpty() }
        if (n == who) return
        who = n
        // the k6 review (item 8): the real key wins; round 2 (items A, D): the census agrees, what goes is kept first
        if (n != null && RealmFix.foldStore(all, n, "chat_talk")) runCatching { store?.write(all.toString()) }
        if (n == null) used = Used()                       // the k6 review (item 2): no realm yet = no character: Say, nothing saved
        load()
        changed()
    }

    /** a send went to [u]: kept (for this character); true = it changed */
    fun went(u: Used): Boolean {
        val k = clean(u)
        if (k == used) return false
        used = k
        save()
        changed()
        return true
    }

    private fun clean(u: Used): Used = when {
        u.type == "WHISPER" -> if (u.whisper.isEmpty()) Used() else Used("WHISPER", u.whisper, "")
        u.type.startsWith("CHANNEL:") -> Used(u.type, "", u.chan)
        u.type.isEmpty() -> Used()
        else -> Used(u.type, "", "")
    }

    private fun load(): Boolean {
        val before = used
        val w = who
        val o = if (w == null) null else all.optJSONObject(w)
        if (o != null) used = clean(Used(o.optString("t", SAY), o.optString("w", ""), o.optString("c", "")))
        else if (w != null) used = Used()
        return used != before
    }

    private fun save() {
        val w = who ?: return
        all.put(w, JSONObject().put("t", used.type).put("w", used.whisper).put("c", used.chan))
        runCatching { store?.write(all.toString()) }
    }

    fun onChange(l: () -> Unit) { listeners += l }
    private fun changed() { for (l in listeners.toList()) runCatching { l() } }

    /**
     * the kept chat [u] as it is NOW: (kind, whisper person). [kinds] = the composer's types the player can talk in now (ChatView.kinds'
     * keys), [chans] = the joined channels (number, name). A whisper keeps its person; a channel is found again by its name (its number
     * now), or by its number when no name was kept; anything not there now = Say.
     */
    fun resolve(u: Used, kinds: Set<String>, chans: List<Pair<Int, String>>): Pair<String, String> = when {
        u.type == "WHISPER" -> if (u.whisper.isNotEmpty()) "WHISPER" to u.whisper else SAY to ""
        u.type.startsWith("CHANNEL:") -> {
            val hit = if (u.chan.isNotEmpty()) chans.firstOrNull { ChatFilter.chanBase(it.second).equals(ChatFilter.chanBase(u.chan), true) }
                else chans.firstOrNull { it.first.toString() == u.type.substringAfter(":") }
            if (hit != null) "CHANNEL:${hit.first}" to "" else SAY to ""
        }
        u.type in kinds -> u.type to ""
        else -> SAY to ""
    }

    /** the tests' clean start */
    fun reset() { store = null; all = JSONObject(); listeners.clear(); who = null; used = Used() }
}
