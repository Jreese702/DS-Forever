package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE LATEST ADD-ON DOCUMENTS, FOR A REBUILT BOTTOM SCREEN (the bottom screen comes straight back, 2026-11-25; BottomSync). Inside the
 * launcher the reader keeps the newest raw document of each STATE type - a type whose last document is all a new host needs to draw
 * the game as it is (the map, bags, quests, the NPC window, the pop-ups, the spells ...; the host's own comments say "a state: a
 * replayed one counts, the last one wins") - in arrival order. A host made again (Android closed the bottom window, a memory release)
 * replays them before its reader goes on from the old one's place, instead of the whole General.log from byte 0. They are old, so the
 * input gate makes them non-current by itself: they draw, they never act.
 *
 * Never kept: answers and presses (…ack, …pad, …use, …cd), current-only words (a handshake, hello / bye, a notice), the probes. Memory
 * only (never on disk, never a typed field), capped by bytes ([CAP_BYTES], Light 8 [CAP_LIGHT8]): the oldest key goes first. Filled on
 * the reader thread (CompanionHost.startTail's onMessage, before the hand-off), read on the main thread. Pure.
 * THIS GAME RUN ONLY (the review, 2026-11-26): filled only while the game is up (the start screens read the last session's log from
 * byte 0), emptied when a new game run attaches (DsForeverScreen.attachGame, not a configuration relaunch), when the game ends, and
 * when the reader finds the log truncated ([readerSaid]: the client restarted). On Light 8's memory line as "docmemory".
 */
object DocMemory {
    /** the state types (every branch of CompanionHost.handleMessage that draws from a replayed document); DocMemoryTest's table */
    val KEEP: Set<String> = setOf(
        "full", "bagsctl", "pos", "qdbpins", "qdbnodes", "qdbavail", "qdbareas", "mapexpl", "maphl", "mapquests", "lbmap",
        "party", "partycfg", "taxinet", "chat", "quests", "questwin", "mapwin", "radial", "sradial", "smartor", "auras", "interact",
        "dialogs", "pvp", "hub", "lay", "character", "social", "gf", "spells", "prof", "profrec", "calendar", "macros", "talents",
        "talentsel", "padmap", "castbar",
    )

    /** answers, presses, events and current-only words: never kept (a replayed one does nothing, or acts only while current) */
    val SKIP: Set<String> = setOf(
        "aggro", "aggrocov", "aggroack", "padkeytest", "itarget", "pmenu", "petauto", "partybox", "partyack", "pnav", "pnavroute",
        "pqol", "pstep", "pguide", "pgsteps", "pglist", "pgstep", "pgroute", "pgop", "pskip", "routewp", "bye", "notice", "chatt",
        "chatq", "chatlink", "qrtip", "guestpad", "mappad", "mapack", "radialuse", "sradialuse", "sradialcd", "itemcd", "nomana",
        "radialstick", "gamewheel", "pauseack", "gamemenu", "gmack", "radialpinack", "guestack", "questack", "auraack", "cappad",
        "barcmd", "interactack", "dialogack", "dialogpad", "padrx", "atkgo", "poi", "qg", "hello", "socialack", "socialpad",
        "gfcat", "gfack", "gfpad", "spellsctl", "spellsack", "profack", "profmade", "profpad", "calack", "macack", "macpad", "calpad",
        "talentsctl", "talentsack", "castcd", "castbarack", "facecd", "facewatch", "facecast", "holdmap", "holdcast", "holdcd",
        "faceswap", "smartyack", "padack", "svkeep", "dsfreset", "padclean", "padtabctl",
    )

    fun keeps(type: String): Boolean = type in KEEP

    /** the type, plus the document's own id for the per-entity types (a profession's recipe book, one per profession) */
    fun key(type: String, msg: JSONObject): String = when (type) {
        "profrec" -> "profrec#" + (msg.optJSONObject("p")?.optInt("id", 0) ?: 0)
        else -> type
    }

    const val CAP_BYTES = 4L * 1024 * 1024
    const val CAP_LIGHT8 = 2L * 1024 * 1024

    /**
     * a kept document's heap bytes (the review: the cap counted chars): ART keeps a String at one byte a char only while every char is
     * ASCII, two bytes a char otherwise (an é or ñ in a frFR / esES client's quest text, a player's name, '…')
     */
    fun bytesOf(raw: String): Long = if (raw.all { it.code in 1..0x7F }) raw.length.toLong() else raw.length * 2L

    /** the newest raw document per key, in arrival order, at most [capBytes] heap bytes ([bytesOf]; the oldest key evicted first) */
    class Store(@Volatile var capBytes: Long) {
        private val docs = LinkedHashMap<String, String>()
        private val sizes = HashMap<String, Long>()
        @Volatile var bytes = 0L
            private set

        @Synchronized fun put(key: String, raw: String) {
            val n = bytesOf(raw)
            if (n > capBytes) return
            docs.remove(key)?.let { bytes -= sizes.remove(key) ?: 0L }
            docs[key] = raw
            sizes[key] = n
            bytes += n
            val it = docs.entries.iterator()
            while (bytes > capBytes && it.hasNext()) { val e = it.next(); bytes -= sizes.remove(e.key) ?: 0L; it.remove() }
        }

        @Synchronized fun docs(): List<String> = docs.values.toList()
        @Synchronized fun keys(): List<String> = docs.keys.toList()
        @Synchronized fun clear() { docs.clear(); sizes.clear(); bytes = 0L }
        @Synchronized fun size(): Int = docs.size
    }

    /** inside the launcher only (CompanionHost.onCreate: LauncherHooks.pkg != null, Local); the app and Streaming keep nothing */
    @Volatile var on = false
    val shared = Store(CAP_BYTES)

    /** the reader thread, for every document before its hand-off to the main thread */
    fun note(msg: JSONObject) {
        if (!on) return
        val t = msg.optString("type")
        if (!keeps(t)) return
        runCatching { shared.put(key(t, msg), msg.toString()) }
    }

    /**
     * the reader's status line (the reader thread, before its hand-off): a truncated log - the client restarted ([LogTail.RESTARTED]) -
     * keeps nothing of the old client's documents (the review: a rebuild later in the new run replayed the old session's, another
     * character's even, for every type this run had not sent yet)
     */
    fun readerSaid(status: String) { if (status.startsWith(LogTail.RESTARTED)) shared.clear() }

    /** the kept documents, oldest first, as new objects (a rebuilt host's replay, the main thread) */
    fun replay(): List<JSONObject> = shared.docs().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
}
