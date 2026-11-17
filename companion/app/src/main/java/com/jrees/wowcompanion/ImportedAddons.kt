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
 *    at any depth (a wrapper folder, a suite of several); a zip with only a loose .toc is one add-on named after it;
 *  - one confirm card lists them (title, version, size, "Replaces yours", "Made for another game version" when the .toc's Interface is
 *    not [GAME_INTERFACE]: a warning, the game still loads it as out of date). DS Forever's own folders and the ones it manages are
 *    REFUSED ([protectedName]), never overwritten or removed;
 *  - Install: the same safe swap as the app's own add-ons (ThirdPartyAddons.moveIn): room checked first, unpacked into the staging folder
 *    beside AddOns (each entry's CRC and size checked), each folder verified (its .toc there and readable), then the old copy of each to
 *    the backup folder and the new one in; any failure puts every old copy back and deletes nothing of them. A running game needs a
 *    /reload or a restart to load them: said, never done for the player.
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

    /** a zip the app will not take; [message] is the card's word */
    class Refused(msg: String) : IOException(msg)

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
            var eocd = -1
            for (i in tail - 22 downTo 0) if (le32(buf, i) == 0x06054b50L) { eocd = i; break }
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
            Spot(d, folder, byDir.getValue(d).filter { baseOf(stemOf(it)).equals(folder, true) })
        }
        val depth = byDir.keys.minOf { if (it.isEmpty()) 0 else it.count { c -> c == '/' } + 1 }
        val shallow = byDir.keys.filter { (if (it.isEmpty()) 0 else it.count { c -> c == '/' } + 1) == depth }
        if (shallow.size != 1) throw Refused("No add-on in this zip")
        val d = shallow.first()
        val names = byDir.getValue(d).map { baseOf(stemOf(it)) }.distinctBy { it.lowercase() }
        if (names.size != 1 || names[0].isEmpty() || names[0] == "." ) throw Refused("No add-on in this zip")
        return listOf(Spot(d, names[0], byDir.getValue(d)))
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

    /** the folders DS Forever ships or manages: never imported over, never removed from here */
    fun protectedName(name: String): Boolean {
        val n = name.lowercase()
        if (n.startsWith("wowcompanion") || n.startsWith("blizzard_")) return true
        if (n == DependencyAddons.STAGING.lowercase() || n == DependencyAddons.BACKUP.lowercase()) return true
        if (ownFolders.any { it.equals(name, true) }) return true
        if (ThirdPartyAddons.ALL.any { a -> a.pkgs().any { (_, p) -> p.folders.any { it.equals(name, true) } } }) return true
        if (DependencyAddons.DEPS.any { it.folder.equals(name, true) }) return true
        return false
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
            val byName = z.entries().asSequence().associateBy { runCatching { safeName(it.name) }.getOrDefault("") }
            spots.map { s ->
                val prefix = if (s.src.isEmpty()) "" else "${s.src}/"
                val files = entries.filter { !it.dir && it.path.startsWith(prefix) }
                val tocName = preferredToc(s.folder, s.tocs) ?: throw Refused("No add-on in this zip")
                val toc = byName[tocName]?.let { e -> z.getInputStream(e).use { readCapped(it, TOC_MAX) } }?.let { parseToc(it) }
                    ?: Toc(null, null, emptyList())
                val refused = if (protectedName(s.folder)) "Kept: DS Forever's own" else null
                Found(zip, s.src, s.folder, toc.title ?: s.folder, toc.version, files.sumOf { it.usize }, files.size,
                    tocName.substringAfterLast('/'), toc.ifaceOk, addons != null && File(addons, s.folder).exists(), refused)
            }
        }
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
     * One line for the Import row.
     */
    fun installNow(found: List<Found>, flavorRoot: File): String {
        val take = found.filter { it.refused == null }
        if (take.isEmpty()) throw Refused("Nothing to install")
        for (f in take) if (protectedName(f.folder)) throw Refused("Kept: DS Forever's own")      // never, whatever the card said
        val iface = File(flavorRoot, "Interface")
        val addons = File(iface, "AddOns").apply { mkdirs() }
        val need = take.sumOf { it.bytes } + ThirdPartyAddons.SPACE_MARGIN
        if (freeSpace(flavorRoot) < need) throw Refused("Needs ${ThirdPartyAddons.size(((need + 999_999) / 1_000_000).toInt())} free")
        ThirdPartyAddons.applyLock.lock()
        val staging = File(iface, "${DependencyAddons.STAGING}/import")
        try {
            if (staging.exists()) staging.deleteRecursively()                       // our own leftover only
            staging.mkdirs()
            onStep("Unpacking")
            for ((zip, group) in take.groupBy { it.zip }) ZipFile(zip).use { z -> for (f in group) unpackOne(z, f, File(staging, f.folder)) }
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
                    val target = File(addons, f.folder)
                    val old = if (target.exists()) ThirdPartyAddons.backup(iface, target, stamp) else null
                    done.add(target to old)
                    ThirdPartyAddons.moveDir(File(staging, f.folder), target, "the new ${f.folder} could not be moved in")
                }
            } catch (t: Throwable) { ThirdPartyAddons.putBack(done); throw t }
            val j = readStore()
            val now = System.currentTimeMillis()
            for (f in take) j.put(f.folder, JSONObject().put("title", f.title).put("version", f.version ?: "").put("bytes", f.bytes)
                .put("iface", f.ifaceOk).put("at", now))
            runCatching { writeStore(j) }.onFailure { log("imported add-ons: the list could not be saved: ${it.message}") }
            log("imported add-ons: ${take.joinToString { "${it.folder} ${it.version ?: ""}".trim() }} in")
            return if (gameRunning()) "Installed. /reload or restart the game to load them" else "Installed"
        } finally {
            staging.deleteRecursively()
            File(iface, DependencyAddons.STAGING).delete()                         // only when empty
            ThirdPartyAddons.applyLock.unlock()
        }
    }

    /** [f]'s entries out of [z] into [to]: each one's path checked, its size as declared, its CRC */
    private fun unpackOne(z: ZipFile, f: Found, to: File) {
        to.mkdirs()
        val root = to.canonicalFile
        val prefix = if (f.src.isEmpty()) "" else "${f.src}/"
        val buf = ByteArray(65536)
        var total = 0L
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
                    n += r; total += r
                    if (n > e.size || total > MAX_TOTAL) throw Refused("Too big")
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
            val target = File(iface, "AddOns/$folder")
            if (target.exists()) {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                ThirdPartyAddons.backup(iface, target, stamp)
            }
            val j = readStore(); j.remove(folder); writeStore(j)
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
        dir = folder; ownFolders = own; pending = null; status = null; busy = false; listCache = null
        File(folder, "import").deleteRecursively()                                  // a pick left from an earlier run
    }
    /** the renders: a card and a line as given */
    internal fun preview(p: Pending?, line: String?) { pending = p; status = line; changed() }

    /** one picked file: its display name and a way to open it */
    class Picked(val name: String, val open: () -> InputStream?)

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
                    copyCapped(p.open() ?: throw Refused("Could not read it"), zip)
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

    /** [i] into [to], at most [MAX_ZIP] bytes */
    internal fun copyCapped(i: InputStream, to: File) {
        i.use { s -> to.outputStream().use { o ->
            val buf = ByteArray(65536); var n = 0L
            while (true) {
                val r = s.read(buf); if (r < 0) break
                n += r; if (n > MAX_ZIP) throw Refused("Too big")
                o.write(buf, 0, r)
            }
        } }
    }

    /** the card's Install */
    fun install() {
        val p = pending ?: return
        val root = flavorRoot() ?: run { status = "Pick the game folder first"; changed(); return }
        job("Installing") { val line = installNow(p.found, root); clearPending(); line }
    }

    /** the card's Cancel: its zips' room back */
    fun cancel() { if (busy) return; clearPending(); status = null; changed() }

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
