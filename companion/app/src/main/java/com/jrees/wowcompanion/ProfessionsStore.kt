package com.jrees.wowcompanion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the Professions room knows, kept across restarts: the last Overview (skill bars) and, per
 * profession, its recipe book AS LAST SEEN (user, 2026-09-28: "Recipes as last seen").
 *
 * WHY THE APP KEEPS IT: the game hands a profession's recipes to add-ons only while that profession
 * is open, and SavedVariables do not read back on this beta, so the addon cannot keep them.
 *
 * PER CHARACTER (b-recipes-per-char): every book is kept under its character's name-realm (the add-on's `who`, the key of every
 * per-character store), so an alt never sees another character's recipes, not even for a moment. A book kept before the add-on
 * said whose it was is dropped (nobody can tell whose it is).
 *
 * NEVER STORED: a list the addon marks `foreign` (someone's linked profession, a guild view, an NPC's
 * crafting window). It is shown while it is open and forgotten when it closes.
 *
 * Call from the UI thread (handleMessage), like PoiHarvest. Writes are atomic (tmp + rename).
 */
object ProfessionsStore {

    private const val FILE = "professions.json"

    private var ctx: Context? = null
    var note: String? = null
        private set

    /** the last `prof` document */
    var overview: ProfessionsModel.Overview? = null
        private set
    private var overviewJson: JSONObject? = null
    /** when the addon last said anything; the view says "game not running" when this is old */
    var overviewAt = 0L
        private set

    private class Kept(val json: JSONObject, val book: ProfessionsModel.Book, val seenAt: Long)
    /** name-realm -> profession id -> its book as last seen */
    private val books = HashMap<String, HashMap<Int, Kept>>()
    /** the character now: the last `prof` document's (at a start, the stored one's) */
    var who: String = ""
        private set
    private fun whoOf(p: JSONObject?): String = p?.optString("who", "") ?: ""
    /** a foreign list: shown while open, never kept */
    private var foreignBook: Kept? = null

    /** item id -> count in the bags (bags 0-4), from the Bags room's section */
    var counts: Map<Int, Int> = emptyMap()
        private set

    /** the addon's last answer to a tap, for the view's feedback */
    var lastAck: JSONObject? = null
        private set
    /** the results the game reported since the last Create, newest last (the game's output log) */
    val made = ArrayList<JSONObject>()

    fun init(c: Context) {
        ctx = c.applicationContext
        books.clear()
        try {
            val f = File(c.filesDir, FILE)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                o.optJSONObject("overview")?.let { overviewJson = it; overview = ProfessionsModel.parseOverview(it); who = whoOf(it) }
                val a = o.optJSONArray("books") ?: JSONArray()
                for (i in 0 until a.length()) {
                    val k = a.optJSONObject(i) ?: continue
                    val p = k.optJSONObject("p") ?: continue
                    val owner = whoOf(p).takeIf { it.isNotEmpty() } ?: continue      // a book from before `who`: whose is unknown
                    val b = ProfessionsModel.parseBook(p)
                    if (b.id > 0 && !b.foreign) books.getOrPut(owner) { HashMap() }[b.id] = Kept(p, b, k.optLong("seen"))
                }
            }
        } catch (t: Throwable) {
            note = "$FILE unreadable: ${t.message}"
        }
        // what was stored says nothing about now: nothing is open until the addon says so
        overview = overview?.let { stale(it) }
    }

    /** a stored Overview never claims an open profession, a selection or a craft in progress */
    private fun stale(o: ProfessionsModel.Overview) =
        ProfessionsModel.Overview(o.list, null, false, null, null, null, false, emptyMap(), emptySet())

    // ---------------------------------------------------------------- ingest

    /** a `prof` document. Returns true when the room must redraw. */
    fun onProf(p: JSONObject, now: Long): Boolean {
        overviewAt = now
        val before = overviewJson
        overviewJson = p
        // another character logged in: from now on only its own books are shown (an add-on without `who` keeps the last one)
        whoOf(p).takeIf { it.isNotEmpty() }?.let { who = it }
        val wasOpen = overview?.open
        overview = ProfessionsModel.parseOverview(p)
        val open = overview?.open
        if (open == null || open != foreignBook?.book?.id) foreignBook = null
        if (before?.toString() == p.toString()) return false
        // Save only what a restart needs: the skill bars (they change rarely), and the moment a
        // profession closes, so its book's "last seen" time is the real one. A selection or a craft
        // in progress changes this document often and is never worth a write.
        val listChanged = before?.optJSONArray("list")?.toString() != p.optJSONArray("list")?.toString()
        if (listChanged || wasOpen != open) save()
        return true
    }

    /** a `profrec` document (the open profession's book). Returns true when the room must redraw. */
    fun onBook(p: JSONObject, now: Long): Boolean {
        val b = ProfessionsModel.parseBook(p)
        if (b.id <= 0) return false
        if (b.foreign) {
            foreignBook = Kept(p, b, now)
            return true
        }
        val owner = whoOf(p).takeIf { it.isNotEmpty() } ?: who
        who = owner                                       // only the character in the game sends its book
        val shelf = books.getOrPut(owner) { HashMap() }
        val old = shelf[b.id]
        shelf[b.id] = Kept(p, b, now)
        // seen again unchanged (the heartbeat): only the time moves, and it is saved when it closes
        if (old != null && old.json.toString() == p.toString()) return true
        save()
        return true
    }

    /** the Bags room's section of a `full` document. Returns true when a count changed. */
    fun onBags(bags: JSONObject): Boolean {
        val c = ProfessionsModel.bagCounts(bags)
        if (c == counts) return false
        counts = c
        return true
    }

    fun onAck(msg: JSONObject) {
        lastAck = msg
        if (msg.optString("op") == "make" || msg.optString("op") == "all") {
            if (msg.optBoolean("ok")) made.clear()
        }
    }

    fun onMade(msg: JSONObject) {
        made.add(msg)
        while (made.size > 20) made.removeAt(0)
    }

    // ---------------------------------------------------------------- reading

    /** the book to show for [id]: the live one while it is open, else the last one seen */
    fun book(id: Int): ProfessionsModel.Book? {
        foreignBook?.let { if (it.book.id == id) return it.book }
        return books[who]?.get(id)?.book
    }

    /** when [id]'s book was last seen (ms), or 0 when never */
    fun seenAt(id: Int): Long = books[who]?.get(id)?.seenAt ?: 0L

    /** is [id]'s book the live one (open in the game, the player's own) */
    fun isLive(id: Int): Boolean = ProfessionsModel.isLive(overview, book(id))

    /** the shared tap pad as the addon last published it (TapBridge reads rows by name from it) */
    fun pad(): JSONObject? = overviewJson?.optJSONObject("pad")

    /** professions whose book has ever been seen */
    fun seenIds(): Set<Int> = books[who]?.keys?.toSet() ?: emptySet()

    // ---------------------------------------------------------------- saving

    private fun save() {
        val o = JSONObject()
        overviewJson?.let { o.put("overview", it) }
        val a = JSONArray()
        for ((owner, shelf) in books) for (k in shelf.values) {
            // the add-on's own `who` rides in the book; one without it is filed under the character it came in for
            val p = if (whoOf(k.json).isEmpty() && owner.isNotEmpty()) JSONObject(k.json.toString()).put("who", owner) else k.json
            a.put(JSONObject().put("seen", k.seenAt).put("p", p))
        }
        o.put("books", a)
        write(o.toString())
    }

    private fun write(text: String) {
        val c = ctx ?: return
        try {
            val dst = File(c.filesDir, FILE)
            val tmp = File(c.filesDir, "$FILE.tmp")
            tmp.writeText(text)
            // rename() replaces the target atomically on Android; on a failure try once more after
            // removing it, and say so rather than lose the store silently
            if (!tmp.renameTo(dst)) {
                dst.delete()
                if (!tmp.renameTo(dst)) note = "$FILE not saved: rename failed"
            }
        } catch (t: Throwable) {
            note = "$FILE not saved: ${t.message}"
        }
    }
}
