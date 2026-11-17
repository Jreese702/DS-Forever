package com.jrees.wowcompanion

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * ADD-ONS THE PLAYER IMPORTS (Settings > Add-ons > Imported; the user, 2026-11-16, instead of an add-on browser: "maybe we just do an
 * import addons button then? and then maybe later we work on an internal addons project to be able to import them from an API key").
 * LATER (not built): "an internal addons project to be able to import them from an API key" - a source the app fetches from with the
 * player's key would hand its zip to [receive] like the picker does; every rule below stays the same.
 *
 * THE WAY IN: Android's own file picker (AddonImportActivity: ACTION_OPEN_DOCUMENT, read access to the picked files only; no storage
 * permission is ever asked for) hands us one or more .zip files:
 *  - each is copied into the app's private storage (at most [MAX_ZIP]), then its central directory is read ([scan]): no path out of the
 *    folder (.., an absolute path, a drive letter), no symlink, no encrypted entry, no name twice, at most [MAX_ENTRIES] entries and
 *    [MAX_TOTAL] bytes unpacked, no entry squeezed more than [MAX_RATIO] times (a zip bomb);
 *  - the add-on folders in it are found ([layout]): a folder holding a .toc of its own name (or its name plus a flavour, "_Forever"),
 *    at any depth (a wrapper folder, a suite of several); a zip with only a loose .toc is one add-on named after it; every folder name
 *    is one plain segment ([validFolder]: never "..", all dots, a separator) and lands right inside AddOns ([addonDir]);
 *  - only the entries [scan] checked are unpacked ([sameFiles]: java.util.zip's file list must be the scanned one, name and size), one
 *    [MAX_TOTAL] cap for the whole install;
 *  - one confirm card lists them (title, version, size, "Replaces yours", "Made for another game version" when the .toc's Interface is
 *    not [GAME_INTERFACE]: a warning, the game still loads it as out of date). DS Forever's own folders and the ones it manages are
 *    REFUSED ([protectedName]), never overwritten or removed;
 *  - Install: the same safe swap as the app's own add-ons (ThirdPartyAddons.moveIn): room checked first, unpacked into the staging folder
 *    beside AddOns (each entry's CRC and size checked), each folder verified (its .toc there and readable), then the old copy of each to
 *    the backup folder and the new one in; any failure puts every old copy back and deletes nothing of them. A running game needs a
 *    /reload or a restart to load them: said, never done for the player.
 *  - NEW NOW, REPLACE NEXT START (the user's pick, 2026-11-16): with the game running only brand-new folders go in at once; a folder that
 *    is already there is QUEUED ([QUEUE], its zip kept in the app's private "queued" folder) and replaced at the same safe point as the
 *    app's own add-ons: the launcher's start step with the game closed (ThirdPartyAddons.applyAtStart -> [applyQueued]), on every route
 *    (Play, a restart, the start step). Its row says "Replaces at next start" with Cancel. The game closed: replaced at once (the card's
 *    two-tap confirm).
 *  - each pick is copied in only with room for it ([copyCapped]: its size and [ThirdPartyAddons.SPACE_MARGIN] free, checked again as
 *    it copies).
 *  - Remove: only a folder this list imported (its own store, [STORE]); it goes to the backup folder (never deleted).
 * Enabling stays with the game's own add-on list (the app edits AddOns.txt only for its own WowCompanion* folders).
 */
object ImportedAddons {
    /** the game's interface number (every WowCompanion .toc; LAUNCH_PLAN: a different number shows "out of date" but loads) */
    const val GAME_INTERFACE = 16001
    const val MAX_ZIP = 256L * 1024 * 1024
    const val MAX_ENTRIES = 20_000
    const val MAX_TOTAL = 512L * 1024 * 1024
    const val MAX_RATIO = 200L
    /** an entry this big or smaller is never called a bomb for its ratio (a text file of spaces squeezes well) */
    const val RATIO_FLOOR = 10L * 1024 * 1024
    private const val TOC_MAX = 256 * 1024
    const val STORE = "imported.json"
    /** the replacements waiting for a start with the game closed (folder -> its card row and its kept zip) */
    const val QUEUE = "queued.json"

    /** a zip the app will not take; [message] is the card's word */
    class Refused(msg: String) : IOException(msg)
    /** Install found [folders] already there that the card did not mark: the card is marked and asks again */
    class NeedsConfirm(val folders: Set<String>) : IOException("Replaces yours: tap Install again")

    // ---------------------------------------------------------------- the zip's central directory
    /** one central-directory entry: its name ('/' separators), sizes, external attributes */
    data class Entry(val name: String, val csize: Long, val usize: Long, val attr: Long, val flags: Int) {
        val dir: Boolean get() = name.endsWith("/")
        val path: String get() = name.trimEnd('/')
    }

    /**
     * The central directory read by hand (java.util.zip does not give the external attributes, where a symlink shows), every guard
     * checked. Throws [Refused] with the card's word.
     */
    fun scan(zip: File): List<Entry> {
        RandomAccessFile(zip, "r").use { f ->
            val len = f.length()
            if (len < 22) throw Refused("Not a zip")
            val tail = minOf(len, 22L + 65535L).toInt()
            val buf = ByteArray(tail)
            f.seek(len - tail); f.readFully(buf)
            // the last end record whose comment runs exactly to the file's end (as java.util.zip reads it): a fake one inside a
            // comment never shows a different directory than the one unpacked
            var eocd = -1
            for (i in tail - 22 downTo 0) if (le32(buf, i) == 0x06054b50L && i + 22 + le16(buf, i + 20) == tail) { eocd = i; break }
            if (eocd < 0) throw Refused("Not a zip")
            val count = le16(buf, eocd + 10)
            val cdSize = le32(buf, eocd + 12)
            val cdOff = le32(buf, eocd + 16)
            if (count == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) throw Refused("Too big")     // ZIP64: never an add-on's size
            if (count > MAX_ENTRIES) throw Refused("Too many files")
            if (cdOff + cdSize > len) throw Refused("Not a zip")
            val cd = ByteArray(cdSize.toInt())
            f.seek(cdOff); f.readFully(cd)
            val out = ArrayList<Entry>(count)
            var p = 0
            var total = 0L
            val seen = HashMap<String, Boolean>()               // lower-case path -> is a folder
            repeat(count) {
                if (p + 46 > cd.size || le32(cd, p) != 0x02014b50L) throw Refused("Not a zip")
                val flags = le16(cd, p + 8)
                val csize = le32(cd, p + 20); val usize = le32(cd, p + 24)
                val nl = le16(cd, p + 28); val xl = le16(cd, p + 30); val cl = le16(cd, p + 32)
                val attr = le32(cd, p + 38)
                if (p + 46 + nl > cd.size) throw Refused("Not a zip")
                val raw = String(cd, p + 46, nl, Charsets.UTF_8)
                p += 46 + nl + xl + cl
                if (flags and 1 != 0) throw Refused("Encrypted zip")
                val name = safeName(raw)
                if (name.isEmpty()) return@repeat
                // a symlink: the Unix mode in the attributes' high half
                if (((attr ushr 16) and 0xF000L) == 0xA000L) throw Refused("Has a link")
                if (csize == 0xFFFFFFFFL || usize == 0xFFFFFFFFL) throw Refused("Too big")
                total += usize
                if (total > MAX_TOTAL) throw Refused("Too big")
                if (usize > RATIO_FLOOR && usize > maxOf(csize, 1L) * MAX_RATIO) throw Refused("Too big")
                val e = Entry(name, csize, usize, attr, flags)
                val key = e.path.lowercase()
                if (key.isNotEmpty()) {
                    val was = seen[key]
                    // a folder named twice is harmless; a file twice (or a file and a folder of one name) is not
                    if (was != null && !(was && e.dir)) throw Refused("A name twice")
                    seen[key] = e.dir
                }
                out.add(e)
            }
            // a file inside a "folder" that is a file
            for (e in out) {
                var parent = e.path.substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    if (seen[parent.lowercase()] == false) throw Refused("A name twice")
                    parent = parent.substringBeforeLast('/', "")
                }
            }
            return out
        }
    }

    /** [raw] with '/' separators, or [Refused] for a name that could leave the folder */
    internal fun safeName(raw: String): String {
        val n = raw.replace('\\', '/')
        if (n.isEmpty() || n.indexOf('\u0000') >= 0) throw Refused("Bad path")
        if (n.startsWith("/")) throw Refused("Bad path")
        if (Regex("^[A-Za-z]:").containsMatchIn(n)) throw Refused("Bad path")
        val segs = n.trimEnd('/').split('/')
        if (segs.any { it == ".." }) throw Refused("Bad path")
        // "a//b" or "./a": made plain ("a/b", "a")
        val clean = segs.filter { it.isNotEmpty() && it != "." }.joinToString("/")
        if (clean.isEmpty()) return if (n.endsWith("/")) "" else throw Refused("Bad path")     // "./": nothing
        return if (n.endsWith("/")) "$clean/" else clean
    }

    private fun le16(b: ByteArray, i: Int): Int = (b[i].toInt() and 255) or ((b[i + 1].toInt() and 255) shl 8)
    private fun le32(b: ByteArray, i: Int): Long =
        (le16(b, i).toLong()) or (le16(b, i + 2).toLong() shl 16)

    // ---------------------------------------------------------------- where the add-ons are
    /** one add-on folder in a zip: [src] its path inside ("" = the zip's top level), [folder] its AddOns name, [tocs] its .toc names */
    data class Spot(val src: String, val folder: String, val tocs: List<String>)

    private val FLAVOUR = Regex("""(?i)[_-](Forever|Camelot|Classic|Vanilla|Mainline|TBC|BCC|Wrath|WOTLKC|Cata|Mists|MoP)$""")
    /** a .toc stem without its flavour ("Bagnon_Forever" -> "Bagnon") */
    internal fun baseOf(stem: String): String = FLAVOUR.replace(stem, "")

    /**
     * The add-on folders among a zip's [entries]: every folder holding a .toc of its own name (or its name and a flavour), not inside
     * another one (a suite: all of them; a wrapper folder: looked through). None: the shallowest folder holding .toc files that all name
     * one add-on (a loose .toc at the top, a GitHub archive's "Name-main/") is that add-on.
     */
    fun layout(entries: List<Entry>): List<Spot> {
        val tocs = entries.filter { !it.dir && it.path.endsWith(".toc", ignoreCase = true) }.map { it.path }
        if (tocs.isEmpty()) throw Refused("No add-on in this zip")
        fun dirOf(p: String) = p.substringBeforeLast('/', "")
        fun stemOf(p: String) = p.substringAfterLast('/').dropLast(4)
        val byDir = tocs.groupBy { dirOf(it) }
        val named = byDir.filter { (d, ts) -> d.isNotEmpty() && ts.any { baseOf(stemOf(it)).equals(d.substringAfterLast('/'), true) } }.keys
        val tops = named.filter { d -> named.none { o -> o != d && d.startsWith("$o/") } }.sorted()
        if (tops.isNotEmpty()) return tops.map { d ->
            val folder = d.substringAfterLast('/')
            if (!validFolder(folder)) throw Refused("Bad name")
            Spot(d, folder, byDir.getValue(d).filter { baseOf(stemOf(it)).equals(folder, true) })
        }
        val depth = byDir.keys.minOf { if (it.isEmpty()) 0 else it.count { c -> c == '/' } + 1 }
        val shallow = byDir.keys.filter { (if (it.isEmpty()) 0 else it.count { c -> c == '/' } + 1) == depth }
        if (shallow.size != 1) throw Refused("No add-on in this zip")
        val d = shallow.first()
        val names = byDir.getValue(d).map { baseOf(stemOf(it)) }.distinctBy { it.lowercase() }
        if (names.size != 1 || names[0].isEmpty()) throw Refused("No add-on in this zip")
        if (!validFolder(names[0])) throw Refused("Bad name")
        return listOf(Spot(d, names[0], byDir.getValue(d)))
    }

    private val BAD_CHARS = Regex("""[\\/:*?"<>|\x00-\x1F]""")
    /**
     * an AddOns folder name the app will make: one plain segment (no '/', '\', ':', NUL or other control character), not "." / ".."
     * or all dots (a "...toc" stem is ".."), no leading dot, no dot or space at either end, at most 100 characters
     */
    internal fun validFolder(n: String): Boolean = n.isNotEmpty() && n.length <= 100 && !BAD_CHARS.containsMatchIn(n) &&
        n.trim() == n && !n.startsWith('.') && !n.endsWith('.')

    /** [folder] inside [addons], or [Refused]: a valid name whose canonical parent is AddOns itself */
    internal fun addonDir(addons: File, folder: String): File {
        if (!validFolder(folder)) throw Refused("Bad name")
        val d = File(addons, folder)
        if (d.canonicalFile.parentFile != addons.canonicalFile) throw Refused("Bad name")
        return d
    }

    // ---------------------------------------------------------------- the .toc
    data class Toc(val title: String?, val version: String?, val iface: List<Int>) {
        val ifaceOk: Boolean get() = GAME_INTERFACE in iface
    }

    /** a .toc's Title (colour codes and textures taken out), Version and Interface (a comma list allowed) */
    fun parseToc(text: String): Toc {
        var title: String? = null; var version: String? = null; var iface = emptyList<Int>()
        for (l0 in text.removePrefix("﻿").lineSequence()) {
            val l = l0.trim()
            if (!l.startsWith("##")) continue
            val m = Regex("""^##\s*([A-Za-z-]+)\s*:\s*(.*)$""").find(l) ?: continue
            val v = m.groupValues[2].trim()
            when (m.groupValues[1].lowercase()) {
                "title" -> if (title == null) title = plainTitle(v).ifEmpty { null }
                "version" -> if (version == null) version = v.ifEmpty { null }
                "interface" -> if (iface.isEmpty()) iface = v.split(',').mapNotNull { it.trim().toIntOrNull() }
            }
        }
        return Toc(title, version, iface)
    }

    internal fun plainTitle(s: String): String = s
        .replace(Regex("""\|c[0-9A-Fa-f]{8}"""), "").replace(Regex("""\|r"""), "")
        .replace(Regex("""\|T[^|]*\|t"""), "").replace(Regex("""\|A[^|]*\|a"""), "").trim()

    /** the .toc the game reads first for this game: <F>_Forever.toc, then <F>.toc, then any of the folder's */
    internal fun preferredToc(folder: String, tocs: List<String>): String? {
        fun stem(p: String) = p.substringAfterLast('/').dropLast(4)
        return tocs.firstOrNull { stem(it).equals("${folder}_Forever", true) } ?: tocs.firstOrNull { stem(it).equals(folder, true) }
            ?: tocs.firstOrNull()
    }

    // ---------------------------------------------------------------- names never touched
    /** DS Forever's own add-on folders (the app's manifest.txt; [init] reads it, the tests set it) */
    @Volatile internal var ownFolders: Set<String> = emptySet()

    /**
     * the folders DS Forever ships or manages: never imported over, never removed from here. "<one of them>-anything" too (the user's
     * "Keep refusing", 2026-11-16, after the review): a backup is named "<folder>-<version>-<stamp>" and DependencyAddons' put-back takes
     * the newest "QuestieDB-*" there, so an imported "QuestieDB-Extra" could never be put back in QuestieDB's place
     */
    fun protectedName(name: String): Boolean {
        val n = name.lowercase()
        if (n.startsWith("wowcompanion") || n.startsWith("blizzard_")) return true
        val managed = listOf(DependencyAddons.STAGING, DependencyAddons.BACKUP) + ownFolders +
            ThirdPartyAddons.ALL.flatMap { a -> a.pkgs().flatMap { (_, p) -> p.folders } } + DependencyAddons.DEPS.map { it.folder }
        return managed.any { m -> m.lowercase().let { n == it || n.startsWith("$it-") } }
    }

    /** the folder names in a manifest.txt text ("<md5>  <Folder>/<file>" lines) */
    internal fun manifestFolders(text: String): Set<String> = text.lines().filter { it.length > 34 && !it.startsWith("set ") }
        .map { it.substring(34).trim().substringBefore('/') }.filter { it.isNotEmpty() }.toSet()

    // ---------------------------------------------------------------- the plan (the confirm card's rows)
    /**
     * one add-on found: [refused] = why it is not taken (DS Forever's, twice in the pick), null = taken. [zip] the private copy it is in.
     */
    data class Found(val zip: File, val src: String, val folder: String, val title: String, val version: String?, val bytes: Long,
                     val files: Int, val tocName: String, val ifaceOk: Boolean, val replaces: Boolean, val refused: String? = null)

    /** a zip of the pick that brought nothing ([name] its display name, [why] the card's word) */
    data class Bad(val name: String, val why: String)

    /** what the confirm card shows */
    data class Pending(val found: List<Found>, val bad: List<Bad>) {
        val taken: List<Found> get() = found.filter { it.refused == null }
        val bytes: Long get() = taken.sumOf { it.bytes }
        val replacesAny: Boolean get() = taken.any { it.replaces }
    }

    /** the add-ons in [zip] for the game's [addons] folder (each [Found]); [Refused] when none can be read */
    fun plan(zip: File, addons: File?): List<Found> {
        val entries = scan(zip)
        val spots = layout(entries)
        return ZipFile(zip).use { z ->
            sameFiles(z, entries)
            val byName = z.entries().asSequence().associateBy { runCatching { safeName(it.name) }.getOrDefault("") }
            spots.map { s ->
                val prefix = if (s.src.isEmpty()) "" else "${s.src}/"
                val files = entries.filter { !it.dir && it.path.startsWith(prefix) }
                val tocName = preferredToc(s.folder, s.tocs) ?: throw Refused("No add-on in this zip")
                val toc = byName[tocName]?.let { e -> z.getInputStream(e).use { readCapped(it, TOC_MAX) } }?.let { parseToc(it) }
                    ?: Toc(null, null, emptyList())
                val refused = if (protectedName(s.folder)) "Kept: DS Forever's own" else null
                Found(zip, s.src, s.folder, toc.title ?: s.folder, toc.version, files.sumOf { it.usize }, files.size,
                    tocName.substringAfterLast('/'), toc.ifaceOk, addons != null && addonDir(addons, s.folder).exists(), refused)
            }
        }
    }

    /**
     * java.util.zip's files (what is unpacked) are exactly [scanned]'s (what every guard checked): the same names, the same sizes;
     * else [Refused] - a zip with two directories never unpacks one that was not checked
     */
    internal fun sameFiles(z: ZipFile, scanned: List<Entry>) {
        val want = scanned.filter { !it.dir }.associate { it.name to it.usize }
        var n = 0
        for (e in z.entries()) {
            val name = runCatching { safeName(e.name) }.getOrElse { throw Refused("Bad path") }
            if (name.isEmpty() || name.endsWith("/")) continue
            n++
            if (want[name] != e.size) throw Refused("Not a zip")
        }
        if (n != want.size) throw Refused("Not a zip")
    }

    private fun readCapped(i: InputStream, max: Int): String {
        val b = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (true) { val n = i.read(buf); if (n < 0) break; b.write(buf, 0, n); if (b.size() > max) break }
        return b.toByteArray().let { String(it, 0, minOf(it.size, max), Charsets.UTF_8) }
    }

    /** several zips' plans as one card: a folder found twice keeps its first */
    internal fun merge(lists: List<List<Found>>): List<Found> {
        val seen = HashSet<String>()
        return lists.flatten().map { f ->
            if (f.refused == null && !seen.add(f.folder.lowercase())) f.copy(refused = "Twice in this pick") else f
        }
    }

    // ---------------------------------------------------------------- the store (only what this list imported)
    data class Imported(val folder: String, val title: String, val version: String?, val bytes: Long, val at: Long)

    @Volatile private var dir: File? = null
    private fun storeFile(): File? = dir?.let { File(it, STORE) }

    @Synchronized private fun readStore(): JSONObject = runCatching { JSONObject(storeFile()!!.readText()) }.getOrDefault(JSONObject())
    @Synchronized private fun writeStore(j: JSONObject) {
        val f = storeFile() ?: return
        f.parentFile?.mkdirs()
        val part = File(f.path + ".part")
        part.writeText(j.toString())
        if (!part.renameTo(f)) { f.delete(); if (!part.renameTo(f)) throw IOException("the list could not be saved") }
        listCache = null
    }

    /** the imported add-ons, in the order they came (kept in memory between writes: the room asks on every draw) */
    fun list(): List<Imported> = listCache ?: readList().also { listCache = it }
    @Volatile private var listCache: List<Imported>? = null
    private fun readList(): List<Imported> {
        val j = readStore()
        return j.keys().asSequence().mapNotNull { k -> j.optJSONObject(k)?.let { o ->
            Imported(k, o.optString("title", k), o.optString("version").ifEmpty { null }, o.optLong("bytes"), o.optLong("at")) } }
            .sortedBy { it.at }.toList()
    }
    fun isImported(folder: String): Boolean = readStore().has(folder)

    // ---------------------------------------------------------------- the swap
    /** the free room in [f]'s volume (the tests set their own) */
    @Volatile internal var freeSpace: (File) -> Long = { it.usableSpace }
    /** the game is running (DependencyAddons' answer; the tests set their own) */
    @Volatile internal var gameRunning: () -> Boolean = { DependencyAddons.gameRunning() }
    /** a step's word while it runs (the Import row's line) */
    @Volatile internal var onStep: (String) -> Unit = {}

    /**
     * the taken add-ons of [found] into [flavorRoot]'s Interface/AddOns: room checked, unpacked into staging, verified, then each old
     * copy to the backup and the new one in; any failure puts every old copy back (nothing of them deleted). The store is written last.
     * The game running: only the brand-new folders now, every replacement queued for the next start ([queue]). One line for the Import row.
     */
    fun installNow(found: List<Found>, flavorRoot: File): String {
        val take = found.filter { it.refused == null }
        if (take.isEmpty()) throw Refused("Nothing to install")
        for (f in take) if (protectedName(f.folder)) throw Refused("Kept: DS Forever's own")      // never, whatever the card said
        val iface = File(flavorRoot, "Interface")
        val addons = File(iface, "AddOns").apply { mkdirs() }
        for (f in take) addonDir(addons, f.folder)                                   // never a name outside AddOns
        // (review, 2026-11-16) the card said "new" but the folder is there now (the game folder picked after the card, or made
        // since): no replace without the card's second tap
        val nowThere = take.filter { !it.replaces && addonDir(addons, it.folder).exists() }.map { it.folder }
        if (nowThere.isNotEmpty()) throw NeedsConfirm(nowThere.toSet())
        // the user's pick (2026-11-16, "New now, replace next start"): a running game's folders are never swapped under it
        val running = gameRunning()
        val later = if (running) take.filter { addonDir(addons, it.folder).exists() } else emptyList()
        val now = take.filter { f -> later.none { it === f } }
        var listed = true
        if (now.isNotEmpty()) {
            val need = now.sumOf { it.bytes } + ThirdPartyAddons.SPACE_MARGIN
            if (freeSpace(flavorRoot) < need) throw Refused("Needs ${mbWord(need)} free")
            listed = swapIn(now, flavorRoot)
            // a replacement queued earlier is older than this one: it never goes in over it
            dropQueued(now.map { it.folder })
        }
        if (later.isNotEmpty()) queue(later)
        if (!listed) return "Installed, but not on the Imported list"
        return when {
            later.isEmpty() -> if (running) "Installed. /reload or restart the game to load them" else "Installed"
            now.isEmpty() -> "Replaces at next start"
            else -> "New ones in: /reload. The rest replace at next start"
        }
    }

    /** "120 MB" for [b] bytes, rounded up */
    private fun mbWord(b: Long): String = ThirdPartyAddons.size(((b + 999_999) / 1_000_000).toInt())

    /** [take] unpacked, verified and swapped in under the add-on lock; the store written last (false = it could not be) */
    private fun swapIn(take: List<Found>, flavorRoot: File): Boolean {
        val iface = File(flavorRoot, "Interface")
        val addons = File(iface, "AddOns").apply { mkdirs() }
        ThirdPartyAddons.applyLock.lock()
        val staging = File(iface, "${DependencyAddons.STAGING}/import")
        try {
            if (staging.exists()) ThirdPartyAddons.deleteTree(staging)               // our own leftover only
            staging.mkdirs()
            onStep("Unpacking")
            val total = longArrayOf(0L)                                              // one cap for the whole install
            for ((zip, group) in take.groupBy { it.zip }) {
                val scanned = scan(zip)                                              // checked again: only these entries come out
                ZipFile(zip).use { z ->
                    sameFiles(z, scanned)
                    for (f in group) unpackOne(z, f, File(staging, f.folder), total)
                }
            }
            // verified: every folder there, its .toc readable, every file in
            for (f in take) {
                val d = File(staging, f.folder)
                val toc = File(d, f.tocName)
                if (!toc.isFile || runCatching { toc.readText() }.isFailure) throw IOException("${f.folder} did not unpack whole")
                if (countFiles(d) != f.files) throw IOException("${f.folder} did not unpack whole")
            }
            onStep("Installing")
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val done = ArrayList<Pair<File, File?>>()
            try {
                for (f in take) {
                    val target = addonDir(addons, f.folder)
                    val old = if (target.exists()) ThirdPartyAddons.backup(iface, target, stamp) else null
                    done.add(target to old)
                    ThirdPartyAddons.moveDir(File(staging, f.folder), target, "the new ${f.folder} could not be moved in")
                }
            } catch (t: Throwable) { ThirdPartyAddons.putBack(done); throw t }
            val j = readStore()
            val now = System.currentTimeMillis()
            for (f in take) j.put(f.folder, JSONObject().put("title", f.title).put("version", f.version ?: "").put("bytes", f.bytes)
                .put("iface", f.ifaceOk).put("at", now))
            val listed = runCatching { writeStore(j) }.onFailure { log("imported add-ons: the list could not be saved: ${it.message}") }.isSuccess
            log("imported add-ons: ${take.joinToString { "${it.folder} ${it.version ?: ""}".trim() }} in")
            return listed
        } finally {
            ThirdPartyAddons.deleteTree(staging)
            File(iface, DependencyAddons.STAGING).delete()                         // only when empty
            ThirdPartyAddons.applyLock.unlock()
        }
    }

    // ---------------------------------------------------------------- the queue (replacements for the next start, game closed)
    /** one replacement waiting: its card row, its kept zip's name in the "queued" folder; [failed] = the start that tried said why */
    data class Queued(val folder: String, val title: String, val version: String?, val bytes: Long, val files: Int, val tocName: String,
                      val ifaceOk: Boolean, val src: String, val zip: String, val at: Long, val failed: String? = null)

    private fun queueDir(): File? = dir?.let { File(it, "queued") }
    private fun queueFile(): File? = dir?.let { File(it, QUEUE) }
    @Synchronized private fun readQueue(): JSONObject = runCatching { JSONObject(queueFile()!!.readText()) }.getOrDefault(JSONObject())
    @Synchronized private fun writeQueue(j: JSONObject) {
        val f = queueFile() ?: throw IOException("not started")
        f.parentFile?.mkdirs()
        val part = File(f.path + ".part")
        part.writeText(j.toString())
        if (!part.renameTo(f)) { f.delete(); if (!part.renameTo(f)) throw IOException("the list could not be saved") }
        queueCache = null
        // a kept zip no row names any more: its room back
        val used = j.keys().asSequence().mapNotNull { j.optJSONObject(it)?.optString("zip") }.toSet()
        queueDir()?.listFiles()?.filter { it.name !in used }?.forEach { it.delete() }
    }

    /** the replacements waiting, in the order they came (kept in memory between writes: the room asks on every draw) */
    fun queued(): List<Queued> = queueCache ?: readQueued().also { queueCache = it }
    @Volatile private var queueCache: List<Queued>? = null
    private fun readQueued(): List<Queued> {
        val j = readQueue()
        return j.keys().asSequence().mapNotNull { k -> j.optJSONObject(k)?.let { o ->
            Queued(k, o.optString("title", k), o.optString("version").ifEmpty { null }, o.optLong("bytes"), o.optInt("files"),
                o.optString("toc"), o.optBoolean("iface", true), o.optString("src"), o.optString("zip"), o.optLong("at"),
                o.optString("failed").ifEmpty { null }) } }.sortedBy { it.at }.toList()
    }

    /** [later] waiting for the next start: each zip kept (moved, never copied twice) in the "queued" folder, a row each (newer wins) */
    private fun queue(later: List<Found>) {
        val qd = queueDir() ?: throw IOException("not started")
        qd.mkdirs()
        ThirdPartyAddons.applyLock.lock()
        try {
            val j = readQueue()
            val now = System.currentTimeMillis()
            for ((zip, group) in later.groupBy { it.zip }) {
                var kept = File(qd, "q$now-${zip.nameWithoutExtension}.zip")
                var k = 2
                while (kept.exists()) kept = File(qd, "q$now-${zip.nameWithoutExtension}~${k++}.zip")
                if (!zip.renameTo(kept)) { zip.copyTo(kept); zip.delete() }
                for (f in group) j.put(f.folder, JSONObject().put("title", f.title).put("version", f.version ?: "").put("bytes", f.bytes)
                    .put("files", f.files).put("toc", f.tocName).put("iface", f.ifaceOk).put("src", f.src).put("zip", kept.name).put("at", now))
            }
            writeQueue(j)
            log("imported add-ons: ${later.joinToString { it.folder }} replace at the next start")
        } finally { ThirdPartyAddons.applyLock.unlock() }
        changed()
    }

    /** [folders]' waiting rows gone (their zips with them when no row names them) */
    private fun dropQueued(folders: List<String>) {
        if (dir == null) return
        val j = readQueue()
        val gone = folders.filter { j.has(it) }
        if (gone.isEmpty()) return
        for (f in gone) j.remove(f)
        runCatching { writeQueue(j) }.onFailure { log("imported add-ons: the queue could not be saved: ${it.message}") }
    }

    /** a waiting replacement's Cancel: its row and its zip gone, nothing in AddOns touched */
    fun cancelQueuedNow(folder: String) {
        ThirdPartyAddons.applyLock.lock()
        try { dropQueued(listOf(folder)); log("imported add-ons: $folder's replacement cancelled") }
        finally { ThirdPartyAddons.applyLock.unlock() }
    }

    /**
     * THE SAFE POINT (ThirdPartyAddons.applyAtStart: the launcher's start step and every launch, the game closed): each waiting
     * replacement swapped in (the zip scanned again, the same staging, backup and put-back as Install), one zip at a time; a failure
     * stays on its row ("Not replaced") and is not tried again; no room = tried at the next start. One line, null = nothing waited.
     */
    fun applyQueued(flavorRoot: File, onStepLine: (String) -> Unit = {}): String? {
        val qd = queueDir() ?: return null
        if (queued().none { it.failed == null }) return null
        if (gameRunning()) return null
        ThirdPartyAddons.applyLock.lock()
        try {
            val lines = ArrayList<String>()
            val j = readQueue()
            val waiting = readQueued().filter { it.failed == null }
            fun fail(q: Queued, why: String) {
                j.optJSONObject(q.folder)?.put("failed", why); lines.add("${q.folder} not replaced ($why)")
                log("imported add-ons: ${q.folder} not replaced: $why")
            }
            for ((zipName, group) in waiting.groupBy { it.zip }) {
                val zip = File(qd, zipName)
                val ok = group.filter { q ->
                    when {
                        protectedName(q.folder) -> { fail(q, "Kept: DS Forever's own"); false }
                        !zip.isFile -> { fail(q, "Could not read it"); false }
                        else -> true
                    }
                }
                if (ok.isEmpty()) continue
                val found = ok.map { q -> Found(zip, q.src, q.folder, q.title, q.version, q.bytes, q.files, q.tocName, q.ifaceOk, true) }
                val need = found.sumOf { it.bytes } + ThirdPartyAddons.SPACE_MARGIN
                if (freeSpace(flavorRoot) < need) {
                    lines.add("${ok.joinToString { it.folder }} replace at the next start (needs ${mbWord(need)} free)"); continue
                }
                onStepLine("Installing add-ons: ${ok.joinToString { it.title }}...")
                try {
                    for (f in found) addonDir(File(flavorRoot, "Interface/AddOns"), f.folder)
                    swapIn(found, flavorRoot)
                    for (q in ok) j.remove(q.folder)
                    lines.add("${ok.joinToString { it.folder }} replaced")
                    log("imported add-ons: ${ok.joinToString { it.folder }} replaced at the start")
                } catch (t: Throwable) {
                    for (q in ok) fail(q, (t as? Refused)?.message ?: DependencyAddons.reasonOf(t))
                }
            }
            writeQueue(j)
            return lines.joinToString("; ").ifEmpty { null }
        } finally { ThirdPartyAddons.applyLock.unlock(); changed() }
    }

    /** [f]'s entries out of [z] into [to]: each one's path checked, its size as declared, its CRC */
    private fun unpackOne(z: ZipFile, f: Found, to: File, total: LongArray) {
        to.mkdirs()
        val root = to.canonicalFile
        val prefix = if (f.src.isEmpty()) "" else "${f.src}/"
        val buf = ByteArray(65536)
        for (e in z.entries().asSequence()) {
            val name = safeName(e.name)
            if (name.isEmpty() || !name.startsWith(prefix) || name == prefix) continue
            val rel = name.removePrefix(prefix)
            val out = File(to, rel.trimEnd('/')).canonicalFile
            if (!out.path.startsWith(root.path + File.separator)) throw Refused("Bad path")
            if (name.endsWith("/")) { out.mkdirs(); continue }
            out.parentFile?.mkdirs()
            val crc = CRC32()
            var n = 0L
            z.getInputStream(e).use { i -> out.outputStream().use { o ->
                while (true) {
                    val r = i.read(buf); if (r < 0) break
                    n += r; total[0] += r
                    if (n > e.size || total[0] > MAX_TOTAL) throw Refused("Too big")
                    crc.update(buf, 0, r); o.write(buf, 0, r)
                }
            } }
            if (n != e.size) throw IOException("${f.folder} did not unpack whole")
            if (e.crc >= 0 && crc.value != e.crc) throw IOException("${f.folder} is damaged")
        }
    }

    private fun countFiles(d: File): Int = d.walkTopDown().count { it.isFile }

    /** an imported [folder] out of the game: to the backup folder (never deleted), then off the list. One line */
    fun removeNow(folder: String, flavorRoot: File): String {
        if (!isImported(folder)) throw Refused("Not imported here")
        if (protectedName(folder)) throw Refused("Kept: DS Forever's own")
        ThirdPartyAddons.applyLock.lock()
        try {
            val iface = File(flavorRoot, "Interface")
            val target = addonDir(File(iface, "AddOns"), folder)
            if (target.exists()) {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                ThirdPartyAddons.backup(iface, target, stamp)
            }
            val j = readStore(); j.remove(folder); writeStore(j)
            dropQueued(listOf(folder))                                             // its waiting replacement never brings it back
            log("imported add-ons: $folder to the backup")
            return if (gameRunning()) "Moved to the backup. /reload or restart the game" else "Moved to the backup"
        } finally { ThirdPartyAddons.applyLock.unlock() }
    }

    // ---------------------------------------------------------------- the room's side (one worker; the card; the line)
    /** the game's flavour folder (the launcher's: LauncherHooks.flavorRoot); null = no game folder yet */
    @Volatile var flavorRoot: () -> File? = { LauncherHooks.flavorRoot() }
    /** the confirm card, while there is one */
    @Volatile var pending: Pending? = null
        private set
    /** the Import row's line (a step, a result); null = its plain line */
    @Volatile var status: String? = null
        private set
    /** a pick, an install or a removal is running */
    @Volatile var busy = false
        private set
    /** the picker's activity closed: the bottom screen's window comes back (the host's openOnBottom hook) */
    @Volatile var onPickerClosed: (() -> Unit)? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun onChange(l: () -> Unit) { listeners.add(l) }
    private fun changed() { for (l in listeners) runCatching { l() } }
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "addons-import").apply { isDaemon = true } }

    fun init(c: Context) {
        if (dir != null) return
        val own = runCatching { c.assets.open("companion/manifest.txt").use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        start(File(c.applicationContext.filesDir, "dsforever/addons"), own?.let { manifestFolders(it) } ?: emptySet())
    }
    /** the off-device tests' way in: their own folder and own-folder names */
    internal fun start(folder: File, own: Set<String>) {
        dir = folder; ownFolders = own; pending = null; status = null; busy = false; listCache = null; queueCache = null
        ThirdPartyAddons.deleteTree(File(folder, "import"))                         // a pick left from an earlier run
    }
    /** the renders: a card and a line as given */
    internal fun preview(p: Pending?, line: String?) { pending = p; status = line; changed() }

    /** one picked file: its display name, its size when the picker says it, and a way to open it */
    class Picked(val name: String, val size: Long? = null, val open: () -> InputStream?)

    /** the picker's files: each copied in (capped), read, and planned; the card shows them (on the worker) */
    fun receive(picks: List<Picked>) {
        if (picks.isEmpty()) return
        job("Reading") {
            clearPending()
            val d = File(dir ?: throw IOException("not started"), "import").apply { mkdirs() }
            val addons = flavorRoot()?.let { File(it, "Interface/AddOns") }
            val lists = ArrayList<List<Found>>(); val bad = ArrayList<Bad>()
            for ((i, p) in picks.withIndex()) {
                val zip = File(d, "$i.zip")
                try {
                    copyCapped(p.open() ?: throw Refused("Could not read it"), zip, p.size)
                    lists.add(plan(zip, addons))
                } catch (t: Throwable) {
                    zip.delete()
                    bad.add(Bad(p.name, (t as? Refused)?.message ?: "Not a zip"))
                }
            }
            pending = Pending(merge(lists), bad)
            null
        }
    }

    /**
     * [i] into [to], at most [MAX_ZIP] bytes, only with room for it: [size] (the picker's, when known) and the margin free before the
     * first byte, the margin still free every [ROOM_EVERY] bytes as it copies (a size the picker did not know)
     */
    internal fun copyCapped(i: InputStream, to: File, size: Long? = null) {
        val vol = to.absoluteFile.parentFile ?: to.absoluteFile
        if (size != null && size > MAX_ZIP) { i.close(); throw Refused("Too big") }
        val first = (size ?: 0L) + ThirdPartyAddons.SPACE_MARGIN
        if (freeSpace(vol) < first) { i.close(); throw Refused("Needs ${mbWord(first)} free") }
        i.use { s -> to.outputStream().use { o ->
            val buf = ByteArray(65536); var n = 0L; var next = ROOM_EVERY
            while (true) {
                val r = s.read(buf); if (r < 0) break
                n += r; if (n > MAX_ZIP) throw Refused("Too big")
                if (n >= next) {
                    next += ROOM_EVERY
                    if (freeSpace(vol) < ThirdPartyAddons.SPACE_MARGIN) throw Refused("Needs ${mbWord(n + ThirdPartyAddons.SPACE_MARGIN)} free")
                }
                o.write(buf, 0, r)
            }
        } }
    }
    private const val ROOM_EVERY = 8L * 1024 * 1024

    /** the card's Install */
    fun install() {
        val p = pending ?: return
        val root = flavorRoot() ?: run { status = "Pick the game folder first"; changed(); return }
        job("Installing") {
            try { val line = installNow(p.found, root); clearPending(); line }
            catch (t: NeedsConfirm) {
                pending = p.copy(found = p.found.map { if (it.folder in t.folders) it.copy(replaces = true) else it })
                t.message
            }
        }
    }

    /** the card's Cancel: its zips' room back */
    fun cancel() { if (busy) return; clearPending(); status = null; changed() }

    /** a waiting replacement's Cancel */
    fun cancelQueued(folder: String) { job("Cancelling") { cancelQueuedNow(folder); null } }

    /** an imported add-on's Remove */
    fun remove(folder: String) {
        val root = flavorRoot() ?: run { status = "Pick the game folder first"; changed(); return }
        job("Removing") { removeNow(folder, root) }
    }

    private fun clearPending() {
        pending = null
        dir?.let { File(it, "import").listFiles()?.forEach { f -> f.delete() } }
    }

    private fun job(step: String, work: () -> String?) {
        if (busy) return
        busy = true; status = step; changed()
        worker.execute {
            onStep = { s -> status = s; changed() }
            try { status = work() }
            catch (t: Throwable) { status = (t as? Refused)?.message ?: "Not installed: ${DependencyAddons.reasonOf(t)}"; log("imported add-ons: ${t.javaClass.simpleName}: ${t.message}") }
            finally { onStep = {}; busy = false; changed() }
        }
    }

    private fun log(line: String) { runCatching { android.util.Log.i("WCSETUP", line) } }
}
