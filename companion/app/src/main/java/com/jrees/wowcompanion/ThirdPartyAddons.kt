package com.jrees.wowcompanion

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * THIRD-PARTY ADD-ONS THE PLAYER OPTS INTO (Settings > Add-ons; the user, 1.0.9, on Spoken: "Modules, don't download by default but
 * make an addon section of our settings"; then "For the larger app like spoken I think we need an option here to install it in the
 * first place"). Unlike QuestieDB ([DependencyAddons]: needed, installed by itself), nothing here is downloaded until the player (or,
 * for [Addon.enabledBy], another setting) asks for it, every size is shown first, and every package is PINNED: its release tag, its
 * exact size and its sha256, read on the day it was added (an update is offered, never taken by itself). Never inside the APK: the
 * project's own GitHub releases.
 *
 * THE WAY IN, the same rules as [DependencyAddons]:
 *  - Install (or a module switched on) DOWNLOADS at once, in the background, only on an unmetered network (Wi-Fi) and only with the
 *    room for it (the package + [SPACE_MARGIN] free in the app's private storage); resumed where it stopped (HTTP Range); checked for
 *    its exact size and its pinned sha256; kept in the app's private storage until it is in;
 *  - it is MOVED IN only at a start with the game closed ([applyAtStart], from the launcher's dependency step): unpacked (each entry's
 *    CRC checked, no path outside) into a staging folder beside AddOns, each top-level folder with a .toc in, the old copy of each to
 *    the backup folder first (NEVER deleted), the zip then freed;
 *  - Uninstall (or a module switched off) moves its folders out to the same backup at that start; nothing is ever deleted.
 *
 * The shape is general (Map's Polestar is the second one): an [Addon] has its [Addon.core] package, its [Module]s (each its own
 * package, switched on alone, only once the core is installed) and its [Option]s (our own settings for it). One registry line each;
 * the Settings room draws every add-on as one block (SettingsView.drawAddon) and the tab test counts its rows.
 */
object ThirdPartyAddons {
    /**
     * one package on [Addon.repo]'s releases: its [tag], its [asset], its exact [bytes] and [sha256], the AddOns [folders] it brings.
     * [url] set: a host with no release API (CurseForge's file CDN for QuestTrail): that file, our pinned size and hash the only check
     * (the host publishes none), and no newer one is ever looked for (a new version = we test it and bump the pin)
     */
    data class Pkg(val tag: String, val asset: String, val bytes: Long, val sha256: String, val folders: List<String>,
                   val url: String? = null) {
        val mb: Int get() = ((bytes + 999_999) / 1_000_000).toInt()
    }
    data class Module(val id: String, val title: String, val what: String, val pkg: Pkg)
    data class Option(val id: String, val title: String, val on: String, val off: String, val default: Boolean)
    /**
     * [enabledBy] set: no Install of its own, another setting switches it ([enabledWhere] names it: Map's "Quest tracking: Polestar").
     * [version] = the pinned release's version.
     */
    data class Addon(val id: String, val title: String, val what: String, val by: String, val license: String, val repo: String,
                     val version: String, val core: Pkg, val modules: List<Module> = emptyList(), val options: List<Option> = emptyList(),
                     val enabledBy: (() -> Boolean)? = null, val enabledWhere: String? = null) {
        /** the rows' words, as the Settings tabs count them (SettingsTabs.Block.rows) */
        fun rowWords(): List<String> = listOf(title) + modules.map { "$title: ${it.title}" } + options.map { it.title }
        fun pkgs(): List<Pair<String, Pkg>> = listOf("core" to core) + modules.map { it.id to it.pkg }
    }

    /**
     * SPOKEN (rusty-key/spoken-wow, MIT, by rustykey): quest and NPC dialogue, zone lore and books read aloud in AI voices. 3.4.0 and its
     * voice packs, read on 2026-10-17 (sizes and digests are GitHub's own; the folders are the zips' own top level). The user's rulings:
     * an explicit Install, modules opt-in, its own sound CVar writes ALLOWED, its top-screen windows hidden, the stop rules ours.
     */
    val SPOKEN = Addon(
        id = "spoken", title = "Spoken", what = "Reads quests, NPCs, zone lore and books aloud in AI voices", by = "rustykey",
        license = "MIT", repo = "rusty-key/spoken-wow", version = "3.4.0",
        core = Pkg("spoken/v3.4.0", "Spoken-3.4.0.zip", 69_960_237L, "97c07123a6aa39798147d1acdc942a540b1600b96441e08e9eab278d5e14e0ce",
            listOf("Spoken", "SpokenContributions", "SpokenPlayer", "Spoken_Quests", "Spoken_Books", "Spoken_Zones")),
        modules = listOf(
            Module("quests", "Quests", "Quest text and NPC greetings as you talk to them",
                Pkg("quests-audio/v2.3.0", "SpokenQuestsAudioAll-2.3.0.zip", 1_539_981_077L,
                    "93de708bd201a0423e77702a6302db95804d00434a0c72cd48cddd7da59727ed",
                    listOf("SpokenQuestsAudioAlliance", "SpokenQuestsAudioHorde", "SpokenQuestsAudioShared", "SpokenQuestsAudioGossip"))),
            Module("zones", "Zones", "Zone lore beside the world map",
                Pkg("zones-audio/v2.0.1", "SpokenZonesAudio-2.0.1.zip", 469_311_051L,
                    "7cb3bad8713b70e354e55708405d647ea2d703fde6f888531982c63c7a0c6959", listOf("SpokenZonesAudio"))),
            Module("books", "Books", "Books and letters, page by page",
                Pkg("books-audio/v2.1.2", "SpokenBooksAudio-2.1.2.zip", 468_876_729L,
                    "4b443fc4d376fbb6d9a9b83041dcd96a1cadfffd72c1d928d373239e3e907924", listOf("SpokenBooksAudio")))),
        options = listOf(
            Option("stopClose", "Stop speech on B, Decline or close", "A line stops when you leave the NPC.",
                "A line keeps reading after you leave the NPC.", default = true),
            Option("stopAccept", "Stop speech on Accept", "Accepting a quest stops its line.",
                "Accepting a quest lets its line finish.", default = false)))

    /**
     * POLESTAR (fitzforge/polestar, GPL-3.0; Map's "Quest tracking: Polestar", moved here from DependencyAddons on Map's word): walking
     * routes to the quest goals (Shortest Path Forever's router). One package with Polestar and Polestar_Nav side by side (the recorder
     * zip is not taken). v0.2.5, read 2026-10-17; untested on the Thor until the user's first run.
     */
    val POLESTAR = Addon(
        id = "polestar", title = "Polestar", what = "Walking routes to your quest goals (Shortest Path Forever's router)", by = "fitzforge",
        license = "GPL-3.0", repo = "fitzforge/polestar", version = "0.2.5",
        core = Pkg("v0.2.5", "Polestar-0.2.5.zip", 38_112_129L, "ff3ea16e3d83e7e3071416c6a89d14be557b7f56f1733caa6d3067248594ff60",
            listOf("Polestar", "Polestar_Nav")),
        enabledBy = { Settings.questTracking == "polestar" }, enabledWhere = "Map › Quest tracking: Polestar")

    /**
     * QUESTTRAIL (Krom, MIT; CurseForge project 1725624, file 9208872; Map's "Quest tracking: QuestTrail", the user: "Download it
     * (Add-ons tab)"): learns where quests are taken, done and handed in; a guidance arrow that plans the route. One folder,
     * QuestTrail (its .toc QuestTrail_Camelot.toc). No GitHub: CurseForge's file CDN, our pinned size and sha256 (read by Map
     * 2026-10-17). In the game it sets the super-tracked quest itself, and its default "hideBlizzard" turns the game's
     * autoQuestWatch / autoQuestProgress CVars off (saving and restoring the player's values): QuestTrail's own behaviour.
     */
    val QUESTTRAIL = Addon(
        id = "questtrail", title = "QuestTrail", what = "Learns where quests are taken and handed in; an arrow that plans your route",
        by = "Krom", license = "MIT", repo = "", version = "3.1.0",
        core = Pkg("3.1.0", "QuestTrail-3.1.0.zip", 578_289L, "2f2c81c276c1f083cea68f2a69de5b8dd78df81cbd2d8cacc2582166a250194b",
            listOf("QuestTrail"), url = "https://mediafilez.forgecdn.net/files/9208/872/QuestTrail-3.1.0.zip"),
        enabledBy = { Settings.questTracking == "questtrail" }, enabledWhere = "Map › Quest tracking: QuestTrail")

    var ALL: List<Addon> = listOf(SPOKEN, POLESTAR, QUESTTRAIL)
        internal set                                            // the off-device test swaps in its own

    /** "1.5 GB" / "447 MB" */
    fun size(mb: Int): String = if (mb >= 1000) "%.1f GB".format(java.util.Locale.ROOT, mb / 1000.0) else "$mb MB"
    /** free room kept beyond a package, for its download (the private storage) and its unpacking (the game folder) */
    const val SPACE_MARGIN = 200L * 1_000_000
    /** a package small enough to be fetched inside the launcher's start step (Polestar, QuestieDB's size); a bigger one downloads in
     *  the background and goes in at the next start */
    private const val SMALL = 100L * 1_000_000
    private const val DEADLINE_MS = 3 * 60 * 60_000L          // a 1.5 GB pack on a slow line: one download's whole budget

    // ---------------------------------------------------------------- the player's choices (private prefs: nothing is on by default)
    /** where the player's choices live: the app's private prefs; a map in the off-device test */
    interface Store { fun get(k: String, d: Boolean): Boolean; fun put(k: String, v: Boolean) }
    private var prefs: Store? = null
    @Volatile private var app: Context? = null
    fun init(c: Context) {
        if (prefs != null) return
        app = c.applicationContext
        val sp: SharedPreferences = c.applicationContext.getSharedPreferences("addons", Context.MODE_PRIVATE)
        start(object : Store {
            override fun get(k: String, d: Boolean) = sp.getBoolean(k, d)
            override fun put(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply() }
        }, File(c.applicationContext.filesDir, "dsforever/addons"))
    }
    /** the off-device test's way in: its own choices and folder (no Context) */
    internal fun start(store: Store, folder: File) {
        prefs = store
        dir = folder
        synchronized(live) { live.clear() }
        loadState()
        // anything asked for and not here yet carries on downloading (a 1.5 GB pack outlives the app being closed)
        resume()
    }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun onChange(l: () -> Unit) { listeners.add(l) }
    private fun changed() { for (l in listeners) runCatching { l() } }

    /** the core is wanted: Install pressed (or, for [Addon.enabledBy], that setting says so) */
    fun coreWanted(a: Addon): Boolean = a.enabledBy?.invoke() ?: (prefs?.get("${a.id}.core", false) ?: false)
    fun setCore(a: Addon, on: Boolean) {
        if (a.enabledBy != null) return
        prefs?.put("${a.id}.core", on); changed(); resume()
    }
    /**
     * KEEP IT INSTALLED (the user, 1.0.9, on Polestar / QuestTrail: "Keep it installed"): an add-on another setting switches stays in
     * AddOns when that setting moves away (unused, not removed); only its own Uninstall here takes it out ([uninstallAsked])
     */
    fun uninstallAsked(a: Addon): Boolean = prefs?.get("${a.id}.out", false) ?: false
    fun askUninstall(a: Addon, on: Boolean) { prefs?.put("${a.id}.out", on); changed() }
    /** an installed package goes out at the next start: not wanted, and (for one another setting switches) its Uninstall asked */
    fun goesOut(a: Addon, key: String): Boolean =
        installed(a, key) && !wanted(a, key) && (a.enabledBy == null || uninstallAsked(a))

    fun moduleOn(a: Addon, m: Module): Boolean = prefs?.get("${a.id}.${m.id}", false) ?: false
    fun setModule(a: Addon, m: Module, on: Boolean) { prefs?.put("${a.id}.${m.id}", on); changed(); resume() }
    fun option(a: Addon, o: Option): Boolean = prefs?.get("${a.id}.opt.${o.id}", o.default) ?: o.default
    fun setOption(a: Addon, o: Option, on: Boolean) { prefs?.put("${a.id}.opt.${o.id}", on); changed() }

    /** a package is wanted: the core while [coreWanted]; a module while the core is wanted AND it is switched on */
    fun wanted(a: Addon, key: String): Boolean =
        coreWanted(a) && (key == "core" || a.modules.firstOrNull { it.id == key }?.let { moduleOn(a, it) } == true)
    fun installed(a: Addon, key: String): Boolean = state(a, key).optString("in") == pkgOf(a, key)?.tag
    fun coreInstalled(a: Addon): Boolean = installed(a, "core")
    private fun pkgOf(a: Addon, key: String): Pkg? = a.pkgs().firstOrNull { it.first == key }?.second

    // ---------------------------------------------------------------- what each package is doing (for its row)
    enum class Kind { NONE, WAITING_WIFI, NO_SPACE, DOWNLOADING, DOWNLOADED, INSTALLED, REMOVING, FAILED }
    data class Status(val kind: Kind, val line: String, val pct: Int = -1)

    private val live = HashMap<String, Status>()           // "<addon>/<key>" -> what the worker is doing now
    private var dir: File? = null
    private var stateJ = JSONObject()                       // "<addon>/<key>" -> { in: installed tag, folders: [...], zip: verified tag }

    @Synchronized private fun state(a: Addon, key: String): JSONObject = stateJ.optJSONObject("${a.id}/$key") ?: JSONObject()
    @Synchronized private fun putState(a: Addon, key: String, j: JSONObject) {
        stateJ.put("${a.id}/$key", j)
        runCatching { File(dir ?: return@runCatching, "state.json").apply { parentFile?.mkdirs() }.writeText(stateJ.toString()) }
    }
    private fun loadState() { stateJ = runCatching { JSONObject(File(dir!!, "state.json").readText()) }.getOrDefault(JSONObject()) }

    /** the row's word on package [key] of [a] */
    fun status(a: Addon, key: String): Status {
        val p = pkgOf(a, key) ?: return Status(Kind.NONE, "")
        val inst = installed(a, key)
        val want = wanted(a, key)
        // what the worker is doing, while it is wanted and not in yet (an installed package is installed, whatever it did before)
        synchronized(live) { live["${a.id}/$key"] }?.let { if (want && !inst) return it }
        return when {
            inst && !want && !goesOut(a, key) -> Status(Kind.INSTALLED, "Installed, not in use: choose it in ${a.enabledWhere}")
            inst && !want -> Status(Kind.REMOVING, "Moves out when the game next starts (kept in a backup)")
            inst -> Status(Kind.INSTALLED, "Installed")
            want && state(a, key).optString("zip") == p.tag && zipOf(p).length() == p.bytes -> Status(Kind.DOWNLOADED, "Downloaded: goes in when the game next starts")
            want -> Status(Kind.DOWNLOADING, "Waiting to download", -1)
            else -> Status(Kind.NONE, "${size(p.mb)} download")
        }
    }

    private fun setLive(a: Addon, key: String, s: Status?) {
        synchronized(live) { if (s == null) live.remove("${a.id}/$key") else live["${a.id}/$key"] = s }
        changed()
    }

    // ---------------------------------------------------------------- the download (background, one at a time)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "addons-dl").apply { isDaemon = true } }
    private val queued = HashSet<String>()

    /** every wanted package not installed and not downloaded yet goes on the worker (once) */
    /** the off-device renders: never download (their states are set by [previewLive] and the state file) */
    @Volatile internal var noFetch = false
    internal fun previewLive(a: Addon, key: String, s: Status?) = setLive(a, key, s)

    fun resume() {
        if (dir == null || noFetch) return
        for (a in ALL) for ((key, p) in a.pkgs()) {
            if (!wanted(a, key) || installed(a, key) || state(a, key).optString("zip") == p.tag) continue
            val id = "${a.id}/$key"
            val fresh = synchronized(queued) { queued.add(id) }
            if (!fresh) continue
            worker.execute {
                try { if (wanted(a, key)) fetch(a, key, p) }
                finally { synchronized(queued) { queued.remove(id) } }
            }
        }
    }

    /** the off-device test: one package's download, on this thread */
    internal fun fetchNow(a: Addon, key: String) { pkgOf(a, key)?.let { fetch(a, key, it) } }

    private fun zipOf(p: Pkg): File = File(dir ?: File("."), "dl/${p.asset}")

    /** Wi-Fi (an unmetered network) now; true when nothing can tell (a test, no service) */
    private fun unmetered(): Boolean = runCatching {
        val cm = app?.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(true)

    /** one package: room, Wi-Fi, the download (resumed), its size and pinned sha256; then it waits for a start with the game closed */
    private fun fetch(a: Addon, key: String, p: Pkg) {
        val zip = zipOf(p)
        val part = File(zip.path + ".part")
        try {
            zip.parentFile?.mkdirs()
            val have = if (part.isFile) part.length() else 0L
            val free = zip.parentFile?.usableSpace ?: Long.MAX_VALUE
            if (free < p.bytes - have + SPACE_MARGIN) {
                setLive(a, key, Status(Kind.NO_SPACE, "Needs ${size(((p.bytes - have + SPACE_MARGIN) / 1_000_000).toInt())} free on this device"))
                return
            }
            if (!unmetered()) { setLive(a, key, Status(Kind.WAITING_WIFI, "Waiting for Wi-Fi to download ${size(p.mb)}")); return }
            setLive(a, key, Status(Kind.DOWNLOADING, "Downloading ${size(p.mb)}", (have * 100 / p.bytes).toInt()))
            download(a.repo, p, part) { got ->
                setLive(a, key, Status(Kind.DOWNLOADING, "Downloading: ${size((got / 1_000_000).toInt())} of ${size(p.mb)}",
                    (got * 100 / p.bytes).toInt()))
                wanted(a, key)                               // switched off meanwhile: stop (the part is kept)
            } ?: return setLive(a, key, null)
            if (part.length() != p.bytes) throw IOException("the download is incomplete")
            if (DependencyAddons.sha256(part) != p.sha256) { part.delete(); throw IOException("the download's checksum is wrong") }
            zip.delete()
            if (!part.renameTo(zip)) throw IOException("the download could not be kept")
            putState(a, key, state(a, key).put("zip", p.tag))
            setLive(a, key, null)
        } catch (t: Throwable) {
            setLive(a, key, Status(Kind.FAILED, "Download stopped: ${DependencyAddons.reasonOf(t)}. It carries on when you open Add-ons again."))
        }
    }

    /** the release asset by its pinned tag and name, resumed from [part]'s length; [onProgress] false = stop; null = stopped */
    private fun download(repo: String, p: Pkg, part: File, onProgress: (Long) -> Boolean): Unit? {
        val url = p.url ?: releaseUrl(repo, p)
        var have = if (part.isFile) part.length() else 0L
        if (have > p.bytes) { part.delete(); have = 0L }
        val c = DependencyAddons.connect(url, if (have > 0) have else null)
        return pull(c, p, part, have, onProgress)
    }

    /** the release asset's address, by its pinned tag and name, its size as pinned */
    private fun releaseUrl(repo: String, p: Pkg): String {
        val rel = JSONObject(DependencyAddons.getText("${DependencyAddons.api}/repos/$repo/releases/tags/" +
            java.net.URLEncoder.encode(p.tag, "UTF-8")))
        val asset = (0 until (rel.optJSONArray("assets")?.length() ?: 0)).mapNotNull { rel.optJSONArray("assets")?.optJSONObject(it) }
            .firstOrNull { it.optString("name") == p.asset } ?: throw IOException("no ${p.asset} in ${p.tag}")
        if (asset.optLong("size") != p.bytes) throw IOException("${p.asset} is not the size it was pinned at")
        return asset.optString("browser_download_url")
    }

    private fun pull(c: HttpURLConnection, p: Pkg, part: File, have0: Long, onProgress: (Long) -> Boolean): Unit? {
        var have = have0
        val until = System.currentTimeMillis() + DEADLINE_MS
        try {
            if (have > 0 && c.responseCode != HttpURLConnection.HTTP_PARTIAL) have = 0L        // the host sent it whole: start again
            java.io.FileOutputStream(part, have > 0).use { o ->
                c.inputStream.use { i ->
                    val buf = ByteArray(256 * 1024)
                    var got = have; var told = 0L
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        o.write(buf, 0, n); got += n
                        if (got > p.bytes) throw IOException("${p.asset} is bigger than it should be")
                        if (System.currentTimeMillis() > until) throw IOException("the download took too long")
                        if (got - told >= 8_000_000 || got == p.bytes) { told = got; if (!onProgress(got)) return null }
                    }
                }
            }
        } finally { c.disconnect() }
        return Unit
    }

    // ---------------------------------------------------------------- in and out, at a start with the game closed
    /**
     * The launcher's dependency step (the game closed): every wanted package downloaded goes in, a small one not downloaded yet is
     * fetched first (Polestar, as DependencyAddons did), every installed package no longer wanted goes out to the backup. One line.
     */
    fun applyAtStart(flavorRoot: File): String {
        if (dir == null) return ""
        val lines = ArrayList<String>()
        for (a in ALL) for ((key, p) in a.pkgs()) {
            val name = if (key == "core") a.title else "${a.title} ${key.replaceFirstChar { it.uppercase() }}"
            try {
                val want = wanted(a, key); val inst = installed(a, key)
                if (want && a.enabledBy != null && uninstallAsked(a)) askUninstall(a, false)     // chosen again: the Uninstall is off
                if (goesOut(a, key)) {
                    moveOut(a, key, flavorRoot); lines.add("$name moved to the backup")
                    if (key == "core" && a.enabledBy != null) askUninstall(a, false)
                    continue
                }
                if (!want || inst) continue
                if (state(a, key).optString("zip") != p.tag && p.bytes <= SMALL) fetch(a, key, p)
                if (state(a, key).optString("zip") != p.tag || zipOf(p).length() != p.bytes) {
                    resume(); lines.add("$name downloading"); continue
                }
                if (DependencyAddons.gameRunning()) { lines.add("$name goes in at the next start with the game closed"); continue }
                moveIn(a, key, p, flavorRoot)
                lines.add("$name ${a.version.takeIf { key == "core" } ?: p.tag.substringAfterLast('v')} installed")
            } catch (t: Throwable) {
                lines.add("$name skipped (${DependencyAddons.reasonOf(t)})")
            }
        }
        changed()
        return lines.joinToString("; ")
    }

    private fun moveIn(a: Addon, key: String, p: Pkg, flavorRoot: File) {
        val iface = File(flavorRoot, "Interface")
        val addons = File(iface, "AddOns").apply { mkdirs() }
        val free = flavorRoot.usableSpace
        if (free < p.bytes + SPACE_MARGIN) throw IOException("needs ${size(((p.bytes + SPACE_MARGIN) / 1_000_000).toInt())} free for the game")
        val staging = File(iface, "${DependencyAddons.STAGING}/${a.id}-$key")
        if (staging.exists()) staging.deleteRecursively()                 // our own leftover only
        DependencyAddons.unpack(zipOf(p), staging, "", maxFiles = 40_000, maxBytes = p.bytes * 3 / 2 + 50_000_000)
        val folders = staging.listFiles()?.filter { d -> d.isDirectory && DependencyAddons.installedVersion(d) != null } ?: emptyList()
        require(folders.isNotEmpty()) { "the package has no add-on in it" }
        if (DependencyAddons.gameRunning()) { staging.deleteRecursively(); throw IOException("the game started: at the next start") }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        for (d in folders) {
            val target = File(addons, d.name)
            if (target.exists()) backup(iface, target, stamp)
            if (!d.renameTo(target)) throw IOException("the new ${d.name} could not be moved in")
        }
        staging.deleteRecursively()
        putState(a, key, JSONObject().put("in", p.tag).put("folders", JSONArray(folders.map { it.name })))
        zipOf(p).delete()                                                  // in: the download's room back
    }

    private fun moveOut(a: Addon, key: String, flavorRoot: File) {
        if (DependencyAddons.gameRunning()) throw IOException("the game is running: at the next start")
        val iface = File(flavorRoot, "Interface")
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val st = state(a, key).optJSONArray("folders") ?: JSONArray()
        for (i in 0 until st.length()) {
            val f = File(iface, "AddOns/${st.optString(i)}")
            if (f.exists()) backup(iface, f, stamp)
        }
        putState(a, key, JSONObject())
        // a module goes out with its core
        if (key == "core") for (m in a.modules) if (installed(a, m.id)) moveOut(a, m.id, flavorRoot)
    }

    private fun backup(iface: File, target: File, stamp: String) {
        val b = File(iface, "${DependencyAddons.BACKUP}/${target.name}-${DependencyAddons.installedVersion(target) ?: "unknown"}-$stamp")
        b.parentFile?.mkdirs()
        if (!target.renameTo(b)) throw IOException("the old ${target.name} could not be moved to the backup")
    }
}
