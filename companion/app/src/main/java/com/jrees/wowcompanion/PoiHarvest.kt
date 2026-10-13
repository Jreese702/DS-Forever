package com.jrees.wowcompanion

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The harvested service layer: every vendor, trainer, bank, mailbox... the player has actually
 * opened, kept across sessions.
 *
 * WHY THE APP KEEPS IT. SavedVariables are written but never read back on this build, so the
 * addon forgets everything on /reload. Harvest.lua sends each capture twice -- a "poi" line the
 * moment the frame opens, and the whole session's list again in every full snapshot's map.svc
 * -- and this is the permanent copy. Stored in the app's own files dir, so it survives
 * `adb install -r` but not an uninstall.
 *
 * ONE RECORD PER NPC, not per service. An innkeeper who also sells food is one pin with both
 * roles, the way the bundled seed represents it, so MapView's role order picks one icon instead
 * of stacking two on the same spot. Objects (mailboxes) have no creature id and are matched by
 * position alone.
 *
 * WHAT MapView GETS. MapView already draws harvested pins from map.svc as {e, f, x, y}: f is the
 * PoiStore role bits, and e marks a bundled seed pin as visited (drawn solid). attach() REPLACES
 * the addon's map.svc with the stored list in that shape, so MapView needs no change.
 *
 * WHEN A SEED PIN COUNTS AS VISITED. Only when the harvested NPC stands within SEED_MATCH_YD of a
 * bundled spawn with the same creature entry. WoW: Forever moves capital trainers, and turning
 * the bundle's OLD spot solid would be confidently wrong. Without a match `e` is left off: the
 * seed pin stays hollow where the bundle guessed, and the harvested pin draws solid where the NPC
 * really stands.
 */
object PoiHarvest {
    private const val FILE = "poi_harvest.json"
    private const val MERGE_NPC_YD = 40.0     // same creature within this = the same spawn
    private const val MERGE_OBJ_YD = 8.0      // a mailbox: no id, so position alone
    private const val SEED_MATCH_YD = 25.0
    private const val SEED_OBJ_YD = 5.0       // a mailbox is used from within a few yards
    private const val MAX_WEIGHT = 9          // an old average still yields to a moved NPC
    // Only when a record has no world position (an old addon): distance in 0..1 map units.
    // Elwynn is ~3,470 x 2,315 yd, so 0.012 is ~28-42 yd and 0.003 is ~7-10 yd.
    private const val MERGE_NPC_N = 0.012
    private const val MERGE_OBJ_N = 0.003

    /** service label (from Harvest.lua) -> PoiStore role bits; 0 = stored but never drawn */
    private fun bitsOf(svc: String): Int = when (svc) {
        "vendor" -> PoiStore.VENDOR
        "trainer" -> PoiStore.TRAINER
        "bank" -> PoiStore.BANKER
        "auction" -> PoiStore.AUCTIONEER
        "flightmaster" -> PoiStore.FLIGHTMASTER
        "innkeeper" -> PoiStore.INNKEEPER
        "mailbox" -> PoiStore.MAILBOX
        "stable" -> PoiStore.STABLEMASTER
        "tabard" -> PoiStore.TABARD
        else -> 0                                 // guildbank: no role in the map's legend
    }

    /** services recorded on the user's ruling (2026-09-27): not gossip, not quest givers */
    private val KNOWN = setOf("vendor", "trainer", "bank", "auction", "flightmaster",
        "innkeeper", "mailbox", "stable", "tabard", "guildbank")

    class Rec(
        val mapID: Int,
        val id: Int?,                      // null = an object, matched by position
        var name: String?,
        val svcs: MutableSet<String>,
        var x: Double, var y: Double,
        var wx: Double?, var wy: Double?,
        var c: Int?,
        var kind: String?,                 // trainer: "class" | "prof"
        var repair: Boolean,
        var visits: Int,
        val first: Long,
        var last: Long,
        val seenT: ArrayDeque<Double>,     // addon GetTime() of observations already applied
    )

    private val recs = ArrayList<Rec>()
    private var ctx: Context? = null
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var onSeedReady: (() -> Unit)? = null

    // continent -> creature entry -> bundled spawns. Built off the UI thread from PoiStore's own
    // cache (the Eastern Kingdoms file is 143 KB of JSON).
    private val seeds = ConcurrentHashMap<Int, Map<Int, List<PoiStore.Poi>>>()
    private val seedLoading = HashSet<Int>()

    @Volatile var note: String = ""

    val size: Int get() = recs.size

    /** Load the store. [onSeedReady] runs on the UI thread when a seed index finishes building. */
    fun init(c: Context, onSeedReady: () -> Unit) {
        ctx = c.applicationContext
        this.onSeedReady = onSeedReady
        recs.clear()
        try {
            val f = File(c.filesDir, FILE)
            if (f.exists()) {
                val a = JSONObject(f.readText()).optJSONArray("recs") ?: JSONArray()
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { fromJson(it)?.let(recs::add) }
            }
        } catch (t: Throwable) {
            note = "poi_harvest.json unreadable: ${t.message}"
        }
        loadGivers(c)
    }

    // ---------------------------------------------------------------- ingest
    /** One capture, from a "poi" line. Returns true if the store changed. */
    fun ingest(p: JSONObject): Boolean {
        val svc = p.optString("svc", "")
        if (svc !in KNOWN) return false
        val mapID = p.optInt("mapID", -1)
        val x = p.optDouble("x", Double.NaN)
        val y = p.optDouble("y", Double.NaN)
        if (mapID < 0 || x.isNaN() || y.isNaN()) return false
        val id = if (p.has("id") && svc != "mailbox") p.optInt("id") else null
        val wx = p.optDouble("wx", Double.NaN).takeUnless { it.isNaN() }
        val wy = p.optDouble("wy", Double.NaN).takeUnless { it.isNaN() }
        val t = p.optDouble("t", Double.NaN)
        val now = System.currentTimeMillis()

        val hit = findMatch(mapID, id, svc, x, y, wx, wy)
        if (hit != null) {
            // The addon re-sends its session list on every heartbeat. The same observation must
            // not count as a new visit, or 15-second repeats would outvote a real move.
            if (!t.isNaN() && hit.seenT.contains(t)) return false
            val w = Math.min(hit.visits, MAX_WEIGHT).toDouble()
            hit.x = (hit.x * w + x) / (w + 1)
            hit.y = (hit.y * w + y) / (w + 1)
            if (wx != null && wy != null) {
                val ox = hit.wx; val oy = hit.wy
                if (ox != null && oy != null) {
                    hit.wx = (ox * w + wx) / (w + 1); hit.wy = (oy * w + wy) / (w + 1)
                } else { hit.wx = wx; hit.wy = wy }
            }
            hit.svcs.add(svc)
            p.optString("name", "").takeIf { it.isNotEmpty() }?.let { hit.name = it }
            if (p.has("c")) hit.c = p.optInt("c")
            p.optString("kind", "").takeIf { it.isNotEmpty() }?.let { hit.kind = it }
            if (p.optBoolean("repair", false)) hit.repair = true
            hit.visits++
            hit.last = now
            if (!t.isNaN()) { hit.seenT.addLast(t); while (hit.seenT.size > 8) hit.seenT.removeFirst() }
        } else {
            recs.add(Rec(
                mapID, id, p.optString("name", "").takeIf { it.isNotEmpty() },
                mutableSetOf(svc), x, y, wx, wy,
                if (p.has("c")) p.optInt("c") else null,
                p.optString("kind", "").takeIf { it.isNotEmpty() },
                p.optBoolean("repair", false), 1, now, now,
                ArrayDeque<Double>().also { if (!t.isNaN()) it.addLast(t) }
            ))
        }
        save()
        return true
    }

    /** The addon's session list, from a full snapshot's map.svc. Returns true if anything changed. */
    fun ingestAll(a: JSONArray?): Boolean {
        if (a == null) return false
        var changed = false
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (!o.has("svc")) continue          // already in MapView's shape: not an addon row
            if (ingest(o)) changed = true
        }
        return changed
    }

    private fun findMatch(mapID: Int, id: Int?, svc: String, x: Double, y: Double,
                          wx: Double?, wy: Double?): Rec? {
        var best: Rec? = null
        var bestD = Double.MAX_VALUE
        for (r in recs) {
            if (r.mapID != mapID || r.id != id) continue
            if (id == null && svc !in r.svcs) continue   // a mailbox never merges with a guild vault
            val rwx = r.wx; val rwy = r.wy
            val (d, lim) = if (wx != null && wy != null && rwx != null && rwy != null)
                Math.hypot(rwx - wx, rwy - wy) to (if (id == null) MERGE_OBJ_YD else MERGE_NPC_YD)
            else
                Math.hypot(r.x - x, r.y - y) to (if (id == null) MERGE_OBJ_N else MERGE_NPC_N)
            if (d <= lim && d < bestD) { best = r; bestD = d }
        }
        return best
    }

    // ---------------------------------------------------------------- MapView's view
    /** Replace m.svc with the stored records for m's map, in MapView's {e, f, x, y} shape. */
    fun attach(m: JSONObject) {
        val mapID = m.optInt("mapID", -1)
        val cont = m.optJSONObject("rect")?.optInt("c", -1) ?: -1
        val seed = if (cont >= 0) seedFor(cont) else null
        val out = JSONArray()
        for (r in recs) {
            if (r.mapID != mapID) continue
            val f = flagsOf(r, seed)
            if (f == 0) continue
            val o = JSONObject().put("f", f).put("x", r.x).put("y", r.y)
            r.name?.let { o.put("n", it) }
            r.id?.let { o.put("i", it) }          // the creature (Map 2026-10-12: its QuestieDB row then draws no second pin)
            if (seed != null) {
                if (r.id != null) {
                    if (nearSeed(r, seed)) o.put("e", r.id)
                    // MOVED: the bundle has this creature, but not where the player met it.
                    // With visited and guessed pins drawn alike (user ruling 2026-09-27), the
                    // bundle's stale spot would read as a second copy of the same NPC, so
                    // MapView is asked to skip seed pins whose entry is listed as `mv`.
                    else if (seed.containsKey(r.id)) o.put("mv", r.id)
                }
                else seedObjectAt(r, f, seed)?.let { o.put("e", it) }
            }
            out.put(o)
        }
        m.put("svc", out)
        attachGivers(m, mapID)
    }

    private fun flagsOf(r: Rec, seed: Map<Int, List<PoiStore.Poi>>?): Int {
        var f = 0
        for (s in r.svcs) f = f or bitsOf(s)
        if (r.repair) f = f or PoiStore.REPAIR
        if ("trainer" in r.svcs) {
            f = f or when (r.kind) {
                "class" -> PoiStore.TRAINER_CLASS
                "prof" -> PoiStore.TRAINER_PROF
                // The addon could not tell (no IsTradeskillTrainer): borrow the bundle's answer
                // for this creature. With neither, it stays a generic trainer, which the default
                // filter hides rather than guesses.
                else -> r.id?.let { seed?.get(it) }?.firstOrNull()?.flags?.and(
                    PoiStore.TRAINER_CLASS or PoiStore.TRAINER_PROF) ?: 0
            }
        }
        return f
    }

    private fun nearSeed(r: Rec, seed: Map<Int, List<PoiStore.Poi>>): Boolean {
        val wx = r.wx ?: return false
        val wy = r.wy ?: return false
        return seed[r.id]?.any { Math.hypot(it.wx - wx, it.wy - wy) <= SEED_MATCH_YD } == true
    }

    /**
     * A visited OBJECT (a mailbox) has no creature id to match on, so match the bundled object of
     * the same role standing where it was used. MEASURED 2026-09-27: the first mailbox harvested
     * on the Thor stood 1 yd from bundled mailbox 142075. Without this its hollow ring claimed the
     * icon cell first and the visited mailbox still looked unvisited.
     *
     * Only an entry with exactly ONE row on the whole continent qualifies. MapView marks every
     * seed pin sharing the entry as visited, and 39 bundled object entries are shared by several
     * mailboxes / anvils; an entry number can also collide with a creature's. Those fall back to
     * no `e`: correct, just drawn under the ring.
     */
    private fun seedObjectAt(r: Rec, f: Int, seed: Map<Int, List<PoiStore.Poi>>): Int? {
        val wx = r.wx ?: return null
        val wy = r.wy ?: return null
        var best: Int? = null
        var bestD = SEED_OBJ_YD
        for ((entry, spawns) in seed) {
            if (spawns.size != 1) continue
            val p = spawns[0]
            if (p.flags and f == 0) continue
            val d = Math.hypot(p.wx - wx, p.wy - wy)
            if (d <= bestD) { bestD = d; best = entry }
        }
        return best
    }

    private fun seedFor(continent: Int): Map<Int, List<PoiStore.Poi>>? {
        seeds[continent]?.let { return it }
        if (continent != 0 && continent != 1) return null
        val c = ctx ?: return null
        synchronized(seedLoading) { if (!seedLoading.add(continent)) return null }
        io.execute {
            val idx = HashMap<Int, MutableList<PoiStore.Poi>>()
            PoiStore.forContinent(c, continent)?.forEach { idx.getOrPut(it.entry) { ArrayList() }.add(it) }
            seeds[continent] = idx
            ui.post { onSeedReady?.invoke() }
        }
        return null
    }

    // ---------------------------------------------------------------- persistence
    private fun save() {
        val a = JSONArray()
        for (r in recs) a.put(toJson(r))
        write(FILE, JSONObject().put("v", 1).put("recs", a).toString())
    }

    private fun write(name: String, text: String) {
        val c = ctx ?: return
        io.execute {
            try {
                // Write-then-rename, so a kill mid-write cannot leave half a file. rename(2)
                // replaces the target atomically on Android; renameTo() reports failure as a
                // false return, never an exception, so a silently lost save has to be checked.
                val tmp = File(c.filesDir, "$name.tmp")
                val dst = File(c.filesDir, name)
                tmp.writeText(text)
                if (!tmp.renameTo(dst)) {
                    dst.delete()
                    if (!tmp.renameTo(dst)) note = "$name not saved: rename failed"
                }
            } catch (t: Throwable) {
                note = "$name not saved: ${t.message}"
            }
        }
    }

    // ---------------------------------------------------------------- quest givers
    // RULING (user, 2026-09-28, confirmed directly with the harvest session): quest dialogs are
    // recorded -- where the NPC stands, the quest ids it offers and the ones it takes back. They
    // live in their own file and their own map key (map.qg) and never create or change a service
    // pin. The map session builds its "!" layer from the bundled quest database and prefers these
    // rows where they exist (a giver seen offering nothing removes a wrong "!").
    // Wire shape: AGENT_LOG, "Quest-giver recording: WIRE SHAPE".
    private const val QG_FILE = "quest_givers.json"

    class Giver(
        val tag: String,                   // "e" creature, "o" game object, "p" unidentified
        val id: Int?,
        val mapID: Int,
        var name: String?,
        var x: Double, var y: Double,
        var wx: Double?, var wy: Double?,
        var visits: Int,
        val offer: MutableSet<Int>, val offerT: MutableSet<String>,
        val turnin: MutableSet<Int>, val done: MutableSet<Int>,
        var seen: Long,                    // epoch ms of the last visit
        var lastT: Double,                 // addon GetTime() of the last row applied
    )

    private val givers = ArrayList<Giver>()
    val giverCount: Int get() = givers.size

    /** a "qg" message: one row (`qg`) or the session's rows re-sent on the heartbeat (`all`) */
    fun ingestQg(msg: JSONObject): Boolean {
        var changed = false
        msg.optJSONObject("qg")?.let { if (ingestGiver(it)) changed = true }
        msg.optJSONArray("all")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (ingestGiver(it)) changed = true }
        }
        if (changed) saveGivers()
        return changed
    }

    private fun ids(a: JSONArray?): List<Int> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optInt(it) }.filter { it > 0 }

    private fun ingestGiver(o: JSONObject): Boolean {
        val mapID = o.optInt("mapID", -1)
        val x = o.optDouble("x", Double.NaN)
        val y = o.optDouble("y", Double.NaN)
        if (mapID < 0 || x.isNaN() || y.isNaN()) return false
        val tag = if (o.has("e")) "e" else if (o.has("o")) "o" else "p"
        val id = if (tag == "p") null else o.optInt(tag)
        val wx = o.optDouble("wx", Double.NaN).takeUnless { it.isNaN() }
        val wy = o.optDouble("wy", Double.NaN).takeUnless { it.isNaN() }
        val t = o.optDouble("t", Double.NaN)

        var hit: Giver? = null
        var bestD = Double.MAX_VALUE
        for (g in givers) {
            if (g.mapID != mapID || g.tag != tag || g.id != id) continue
            val gx = g.wx; val gy = g.wy
            val (d, lim) = if (wx != null && wy != null && gx != null && gy != null)
                Math.hypot(gx - wx, gy - wy) to (if (id == null) MERGE_OBJ_YD else MERGE_NPC_YD)
            else Math.hypot(g.x - x, g.y - y) to (if (id == null) MERGE_OBJ_N else MERGE_NPC_N)
            if (d <= lim && d < bestD) { hit = g; bestD = d }
        }
        // the heartbeat re-sends the latest row per NPC: already applied
        if (hit != null && !t.isNaN() && hit.lastT == t) return false

        val g = hit ?: Giver(tag, id, mapID, null, x, y, wx, wy, 0,
            mutableSetOf(), mutableSetOf(), mutableSetOf(), mutableSetOf(), 0L, Double.NaN).also { givers.add(it) }
        if (hit != null) {
            val w = Math.min(g.visits, MAX_WEIGHT).toDouble()
            g.x = (g.x * w + x) / (w + 1); g.y = (g.y * w + y) / (w + 1)
            val ox = g.wx; val oy = g.wy
            if (wx != null && wy != null) {
                if (ox != null && oy != null) { g.wx = (ox * w + wx) / (w + 1); g.wy = (oy * w + wy) / (w + 1) }
                else { g.wx = wx; g.wy = wy }
            }
        }
        // full = the NPC's complete lists right now: replace. Otherwise one quest: add.
        if (o.optBoolean("full", false)) {
            g.offer.clear(); g.offerT.clear(); g.turnin.clear(); g.done.clear()
        }
        g.offer.addAll(ids(o.optJSONArray("offer")))
        g.turnin.addAll(ids(o.optJSONArray("turnin")))
        g.done.addAll(ids(o.optJSONArray("done")))
        o.optJSONArray("offerT")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotEmpty() }?.let(g.offerT::add) }
        o.optString("name", "").takeIf { it.isNotEmpty() }?.let { g.name = it }
        g.visits++
        g.seen = System.currentTimeMillis()
        g.lastT = t
        return true
    }

    /** m.qg = this map's givers: {e | o, n, x, y, offer, turnin, done, seen (epoch s), offerT?} */
    private fun attachGivers(m: JSONObject, mapID: Int) {
        val out = JSONArray()
        for (g in givers) {
            if (g.mapID != mapID) continue
            val o = JSONObject().put("x", g.x).put("y", g.y).put("seen", g.seen / 1000)
                .put("offer", JSONArray(g.offer.sorted())).put("turnin", JSONArray(g.turnin.sorted()))
                .put("done", JSONArray(g.done.sorted()))
            if (g.tag != "p") o.put(g.tag, g.id)
            g.name?.let { o.put("n", it) }
            if (g.offerT.isNotEmpty()) o.put("offerT", JSONArray(g.offerT.sorted()))
            out.put(o)
        }
        m.put("qg", out)
    }

    private fun saveGivers() {
        val a = JSONArray()
        for (g in givers) a.put(JSONObject().apply {
            put("tag", g.tag); g.id?.let { put("id", it) }; put("m", g.mapID); g.name?.let { put("n", it) }
            put("x", g.x); put("y", g.y); g.wx?.let { put("wx", it) }; g.wy?.let { put("wy", it) }
            put("vis", g.visits); put("seen", g.seen); if (!g.lastT.isNaN()) put("lt", g.lastT)
            put("offer", JSONArray(g.offer.sorted())); put("turnin", JSONArray(g.turnin.sorted()))
            put("done", JSONArray(g.done.sorted())); put("offerT", JSONArray(g.offerT.sorted()))
        })
        write(QG_FILE, JSONObject().put("v", 1).put("givers", a).toString())
    }

    private fun loadGivers(c: Context) {
        givers.clear()
        try {
            val f = File(c.filesDir, QG_FILE)
            if (!f.exists()) return
            val a = JSONObject(f.readText()).optJSONArray("givers") ?: return
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val offerT = mutableSetOf<String>()
                o.optJSONArray("offerT")?.let { t -> for (k in 0 until t.length()) offerT.add(t.optString(k)) }
                givers.add(Giver(
                    o.optString("tag", "p"), if (o.has("id")) o.optInt("id") else null, o.optInt("m"),
                    o.optString("n", "").takeIf { it.isNotEmpty() }, o.optDouble("x"), o.optDouble("y"),
                    o.optDouble("wx", Double.NaN).takeUnless { it.isNaN() },
                    o.optDouble("wy", Double.NaN).takeUnless { it.isNaN() },
                    o.optInt("vis", 1),
                    ids(o.optJSONArray("offer")).toMutableSet(), offerT,
                    ids(o.optJSONArray("turnin")).toMutableSet(), ids(o.optJSONArray("done")).toMutableSet(),
                    o.optLong("seen"), o.optDouble("lt", Double.NaN)
                ))
            }
        } catch (t: Throwable) {
            note = "$QG_FILE unreadable: ${t.message}"
        }
    }

    private fun toJson(r: Rec) = JSONObject().apply {
        put("m", r.mapID); r.id?.let { put("id", it) }; r.name?.let { put("n", it) }
        put("s", JSONArray(r.svcs.sorted())); put("x", r.x); put("y", r.y)
        r.wx?.let { put("wx", it) }; r.wy?.let { put("wy", it) }; r.c?.let { put("c", it) }
        r.kind?.let { put("k", it) }; if (r.repair) put("r", true)
        put("vis", r.visits); put("first", r.first); put("last", r.last)
        put("ts", JSONArray(r.seenT.toList()))
    }

    private fun fromJson(o: JSONObject): Rec? {
        val s = o.optJSONArray("s") ?: return null
        val svcs = mutableSetOf<String>()
        for (i in 0 until s.length()) svcs.add(s.optString(i))
        val ts = ArrayDeque<Double>()
        o.optJSONArray("ts")?.let { for (i in 0 until it.length()) ts.addLast(it.optDouble(i)) }
        return Rec(
            o.optInt("m"), if (o.has("id")) o.optInt("id") else null,
            o.optString("n", "").takeIf { it.isNotEmpty() }, svcs,
            o.optDouble("x"), o.optDouble("y"),
            o.optDouble("wx", Double.NaN).takeUnless { it.isNaN() },
            o.optDouble("wy", Double.NaN).takeUnless { it.isNaN() },
            if (o.has("c")) o.optInt("c") else null,
            o.optString("k", "").takeIf { it.isNotEmpty() },
            o.optBoolean("r", false), o.optInt("vis", 1),
            o.optLong("first"), o.optLong("last"), ts
        )
    }
}
