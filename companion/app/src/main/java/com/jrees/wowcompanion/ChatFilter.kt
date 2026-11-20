package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * THE CHAT'S MESSAGE TYPES, A CHECKLIST (the user, 2026-11-16: "the messages filter should work different, instead of filtering to one
 * message type I want it to open a menu where I can check a radio box for all of the message types that I care about seeing so that I
 * can mix and match my preferences"). It replaces the one-of-six filter (All / Nearby / Group / Whispers / Channels / System).
 *
 * The types and their groups follow the game's own Chat Settings window (ChatConfigFrame: Chat, Creature Messages, Combat, System, and
 * a box per joined channel). A line is shown when its type is ticked; the Chat room's log and Home's chat pane both read [shows]. What is
 * kept is what is UNTICKED, per character (the per-character ruling: name-realm), so a type that is new to the list starts ticked.
 * Until the character is known nothing is saved. Pure Kotlin (tested off the device); the app hands it a [Store].
 */
object ChatFilter {
    /** one box of the list: [key] is kept, [chs] are the add-on's line kinds (Chat.lua's `ch`) it covers */
    class Type(val key: String, val label: String, val chs: Set<String>)

    /** one column of the list: the game's group heading and its boxes */
    class Group(val label: String, val types: List<Type>)

    /** the boxes the game's Chat Settings has for what the add-on mirrors, in its order (its group, then the box) */
    val GROUPS: List<Group> = listOf(
        Group("Chat", listOf(
            Type("say", "Say", setOf("say")),
            Type("emote", "Emote", setOf("emote")),
            Type("yell", "Yell", setOf("yell")),
            Type("whisper", "Whisper", setOf("whisper")),
            Type("party", "Party", setOf("party")),
            Type("raid", "Raid", setOf("raid")),
            Type("guild", "Guild", setOf("guild")),
            Type("officer", "Officer", setOf("officer")),
        )),
        // Chat.lua chat37 mirrors the game's experience, honor, reputation and skill-up lines (their own kinds)
        Group("Combat", listOf(
            Type("xp", "Experience", setOf("xp")),
            Type("honor", "Honor", setOf("honor")),
            Type("rep", "Reputation", setOf("rep")),
            Type("skill", "Skill-ups", setOf("skill")),
            Type("loot", "Item Loot", setOf("loot")),
            Type("money", "Money", setOf("money", "currency")),
        )),
        // the game's Creature Messages and System groups; Errors and Progress are its red and yellow screen messages (UIErrorsFrame)
        Group("Other", listOf(
            Type("npc", "Creatures", setOf("npc")),
            Type("system", "System", setOf("system", "other")),
            Type("error", "Errors", setOf("error")),
            Type("info", "Progress", setOf("info")),
        )),
    )

    /** the heading of the joined channels' column */
    const val CHANNELS = "Channels"

    private val byCh: Map<String, String> = HashMap<String, String>().apply {
        for (g in GROUPS) for (t in g.types) for (c in t.chs) put(c, t.key)
    }

    /** the box a line kind belongs to; a kind nobody has named yet reads as System */
    fun typeOf(ch: String): String = byCh[ch] ?: "system"

    private val NUMBER = Regex("^\\d+\\. ")

    /** a channel's own name: "2. Trade - City" (a line's) and "Trade" (the joined list's) are both "Trade" */
    fun chanBase(s: String): String {
        val body = if (NUMBER.containsMatchIn(s)) s.substringAfter(". ") else s
        return body.substringBefore(" - ").trim()
    }

    /** the box a joined channel has: by its name, never its number (the numbers move as channels are joined and left) */
    fun chanKey(name: String): String = "chan:" + chanBase(name)

    /** the box a line belongs to */
    fun keyOf(ch: String, chan: String): String = if (ch == "channel") chanKey(chan) else typeOf(ch)

    // ---------------------------------------------------------------- the player's ticks

    /** where the ticks are kept: the whole map (character -> unticked keys) as one JSON text */
    interface Store { fun read(): String?; fun write(s: String) }

    private var store: Store? = null
    private var all = JSONObject()
    private val hidden = HashSet<String>()
    private val listeners = ArrayList<() -> Unit>()

    /** the character now ("name-realm"); null = not known yet: everything shows and nothing is saved */
    @Volatile var who: String? = null
        private set

    fun init(s: Store) {
        if (store != null) return
        store = s
        all = runCatching { JSONObject(s.read() ?: "{}") }.getOrDefault(JSONObject())
        load()
    }

    /** the character the padmap names now: its own ticks */
    fun character(name: String?) {
        val n = name?.takeIf { it.isNotEmpty() }
        if (n == who) return
        who = n
        // the k6 review (item 8): a realm-less session's "Name-" ticks fold into the real key (it wins; a missing one takes them); round 2
        // (items A, D): only when the device's census holds the name under this realm alone, and only once what goes is kept
        if (n != null && RealmFix.foldStore(all, n, "chat_filter")) runCatching { store?.write(all.toString()) }
        load()                                             // null (the k6 review, item 2: no realm yet): everything shows, nothing saved
        changed()
    }

    private fun load() {
        hidden.clear()
        val w = who ?: return
        all.optJSONArray(w)?.let { a -> for (k in 0 until a.length()) a.optString(k).takeIf { it.isNotEmpty() }?.let { hidden.add(it) } }
    }

    private fun save() {
        val w = who ?: return
        all.put(w, JSONArray(hidden.sorted()))
        runCatching { store?.write(all.toString()) }
    }

    fun onChange(l: () -> Unit) { listeners += l }
    private fun changed() { for (l in listeners.toList()) runCatching { l() } }

    /** the box [key] is ticked */
    fun ticked(key: String): Boolean = key !in hidden

    /** a line of kind [ch] (its channel string [chan]) shows */
    fun shows(ch: String, chan: String = ""): Boolean = keyOf(ch, chan) !in hidden

    /** the box [key] ticked or not; returns whether it is ticked now */
    fun toggle(key: String): Boolean {
        if (!hidden.remove(key)) hidden.add(key)
        save(); changed()
        return key !in hidden
    }

    /** the list's All box: every one of [keys] ticked when any is not, else every one unticked */
    fun toggleAll(keys: Collection<String>): Boolean {
        val on = keys.any { it in hidden }
        if (on) hidden.removeAll(keys.toSet()) else hidden.addAll(keys)
        save(); changed()
        return on
    }

    /** every one of [keys] ticked: the All box's tick */
    fun allTicked(keys: Collection<String>): Boolean = keys.none { it in hidden }

    /** the tests' clean start */
    fun reset() { store = null; all = JSONObject(); hidden.clear(); listeners.clear(); who = null }
}

/**
 * WHERE THE MESSAGE GOES, AS THE GAME'S CHAT BOX SAYS IT (the user, 2026-11-16: "when typing its not TOTALLY obvious where I am sending
 * the message- so I want the added prefix to the message for where I am about to send the message just like in game, also changing the
 * color of that message accordingly"). The game's ChatFrameEditBoxMixin:UpdateHeader sets its header from the CHAT_<TYPE>_SEND strings
 * (enUS: "Say: ", "Party: ", "Tell %s: ", "%d. %s: " for a channel by its number and full name) and colours the header AND the typed
 * text in the type's ChatTypeInfo colour. Pure Kotlin; the view colours and translates it.
 */
object ChatHeader {
    /**
     * The header for the composer's type [type] (ChatStream's keys: SAY .. WHISPER, REPLY, "CHANNEL:<n>"), without its trailing space.
     * [name] = the whisper's person (WHISPER, REPLY); [chanName] = the channel's name as the game gives it ("Trade - City"; the joined
     * list's "Trade" when no line has named it yet).
     */
    fun of(type: String, name: String = "", chanName: String = ""): String = when {
        type == "SAY" -> "Say:"
        type == "YELL" -> "Yell:"
        type == "PARTY" -> "Party:"
        type == "RAID" -> "Raid:"
        type == "GUILD" -> "Guild:"
        type == "OFFICER" -> "Officer:"
        type == "WHISPER" || type == "REPLY" -> if (name.isNotEmpty()) "Tell $name:" else "Tell:"
        type.startsWith("CHANNEL:") -> {
            val n = type.substringAfter(":")
            if (chanName.isNotEmpty()) "$n. $chanName:" else "$n.:"
        }
        else -> "Say:"
    }

    /** the header as the field draws it: translated words, a player's or a channel's name kept as it is */
    fun shown(type: String, name: String = "", chanName: String = "", tr: (String) -> String = { it }): String =
        if (type.startsWith("CHANNEL:")) of(type, name, chanName) else tr(of(type, name, chanName))
}
