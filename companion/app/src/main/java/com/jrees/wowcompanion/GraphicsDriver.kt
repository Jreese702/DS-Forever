package com.jrees.wowcompanion

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GRAPHICS DRIVER, THE OPT-IN TEST SETTING (the user, 2026-11-23: "Is there any new drivers that we can leverage?"; the research's pick
 * "Both (Recommended)": an opt-in "Test" driver beside today's default). Settings > General > Graphics > Graphics driver, this device's,
 * from the next Play:
 *
 * - [Choice.RECOMMENDED] (the default): exactly what every GPU profile always used (the launcher's DEFAULT_DRIVER on the Adreno 740 / 650
 *   Thors, Turnip-V32-RP6sched on Adreno 8xx); nothing about the container changes.
 * - [Choice.TEST_MESA]: Turnip built by DS Forever from official Mesa 26.3.1 plus ONE local patch (the vk_icd loader exports the Vulkan
 *   wrapper needs; tools/turnip-test/, SOURCE_PROVENANCE.md). The IR3 scheduler fix our default driver carries by hand is upstream since
 *   26.3.0. Only on the Adreno 740 / 650 profiles; the 8xx profile keeps its own driver whatever is chosen.
 *
 * Banners-Turnip Standard v27.0.0-20261123 was downloaded and checked but NOT built in: it exports only HMI (none of the three vk_icd*
 * hooks the wrapper loads through) and needs Android 11 symbols while its meta.json says API 28. Only drivers that exist are listed
 * ([offered]: a test driver whose zip is in this APK's bundled_components).
 *
 * WHICH DRIVER REALLY LOADED (the research: "Today's report line only shows the driver we asked for, not proof that it loaded"): the
 * game's Wine processes run under OUR uid (MemWatch reads their smaps_rollup the same way), so their /proc/<pid>/maps says which Vulkan
 * driver file is mapped: an adrenotools folder (contents/adrenotools/<name>/...), the phone's own Qualcomm driver (vulkan.adreno.so: the
 * wrapper fell back), or none yet. [watch] looks a few times after Play and stops at the first one it sees. The launcher logs the request
 * ([markLaunched]) and the watch logs what loaded; the problem report's "GPU and container" section shows both ([reportLine]).
 *
 * Pure, but for the volatile fields the launcher sets and the watch's thread.
 */
object GraphicsDriver {
    /** the saved choice's key (Settings' prefs, "settings") */
    const val KEY = "graphicsDriver"
    /** the last game start's request and what loaded, one line kept across app restarts (a report made after a crash still says) */
    const val KEY_RAN = "graphicsDriverRan"
    /** the Settings row's own words (SettingsTabs, GeneralTiles) */
    const val ROW_ID = "Graphics driver"
    /** the folder adrenotools drivers unpack into (the app's files dir) */
    const val ADRENO_DIR = "contents/adrenotools"
    /** [driverInMaps]' word for the phone's own Qualcomm driver */
    const val SYSTEM = "the system Qualcomm driver (vulkan.adreno.so)"
    /** the log tag (StartupDiag.LOG_TAGS has it: the report's log carries the lines) */
    const val TAG = "WCRUNTIME"

    /**
     * a choice: [folder] = the adrenotools folder its zip unpacks into (= its meta.json "name"; a NEW name per build, because the
     * launcher never unpacks again a folder that has its meta.json), [asset] its zip in bundled_components, [version] its meta.json
     * driverVersion. RECOMMENDED has none: the profile's own driver.
     */
    enum class Choice(val id: String, val label: String, val folder: String?, val asset: String?, val version: String?) {
        RECOMMENDED("recommended", "Recommended", null, null, null),
        TEST_MESA("test-mesa-26.3.1", "Test: Mesa 26.3.1", "Turnip-Test-Mesa-26.3.1", "turnip-test-mesa-26.3.1.zip", "26.3.1-dsf-icd"),
    }

    val DEFAULT = Choice.RECOMMENDED

    /** the 1523 test build (the es review): the row's word for [c]. "Recommended" alone is a shared key - Polestar's guide reads it as
     *  "Recomendada" (la ruta) - but el controlador wants "Recomendado", so the driver row's Recommended has its own key
     *  ([RECOMMENDED_KEY]); English (or a language without it) shows the label */
    const val RECOMMENDED_KEY = "Recommended (graphics driver)"
    fun words(c: Choice): String =
        if (c != Choice.RECOMMENDED) c.label else Tr.t(RECOMMENDED_KEY).let { if (it == RECOMMENDED_KEY) c.label else it }

    /** a saved or passed id as a choice (anything else = Recommended) */
    fun sanitize(id: String?): Choice = Choice.values().firstOrNull { it.id == id } ?: DEFAULT

    // ---- what this APK and device offer (the launcher sets both at its start; the standalone app never does: no row there)

    /** the GPU profile can take a test driver (the Adreno 740 / 650 profiles; false on Adreno 8xx and before the launcher said) */
    @Volatile var profileTakesTests = false
    /** the test drivers' zips in this APK's bundled_components (asset names) */
    @Volatile var assets: Set<String> = emptySet()

    /** the choices listed now: Recommended and every test driver whose zip this APK carries. Pure over [present] */
    fun offered(present: Set<String> = assets): List<Choice> = Choice.values().filter { it.asset == null || it.asset in present }

    /** the Settings row shows: the profile takes test drivers and at least one is in this APK */
    fun rowShown(takesTests: Boolean = profileTakesTests, present: Set<String> = assets): Boolean = takesTests && offered(present).size > 1

    /** the row's segment lit for [chosen] among [list] (a choice not listed: Recommended's) */
    fun segment(chosen: Choice, list: List<Choice>): Int = list.indexOf(chosen).let { if (it < 0) 0 else it }

    /**
     * THE LAUNCH'S DRIVER, pure: the profile's own [recommended] driver unless [chosen] is a test driver, the profile takes tests
     * ([takesTests]) and its folder is ready ([installed]); [note] says why the recommended one is used for a test choice.
     */
    data class Pick(val driver: String, val note: String)
    fun pick(chosen: Choice, recommended: String, takesTests: Boolean, installed: Boolean): Pick = when {
        chosen.folder == null -> Pick(recommended, "")
        !takesTests -> Pick(recommended, "test drivers are not used on this GPU: its own driver")
        !installed -> Pick(recommended, "the test driver is not in this app or did not unpack: the recommended driver")
        else -> Pick(chosen.folder, "")
    }

    // ---- what the running game started with, and what it loaded

    /** one game start: the choice, the folder the container asked for ([requested]), why a test choice fell back ([note]) */
    data class Launch(val choice: Choice, val requested: String, val note: String, val atMs: Long)
    /** what was seen loaded: [driver] = an adrenotools folder's name, [SYSTEM], or null (none seen in [looks] looks); [process] its label */
    data class Loaded(val driver: String?, val version: String?, val process: String, val afterMs: Long, val looks: Int)

    /** the running game's start; null = no game started in this process */
    @Volatile var launched: Launch? = null
        private set
    /** what [launched] loaded; null = not looked yet (the watch runs) */
    @Volatile var loaded: Loaded? = null
        private set

    private val listeners = ArrayList<() -> Unit>()
    fun onChange(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    private fun changed() { for (l in synchronized(listeners) { listeners.toList() }) runCatching { l() } }

    /** the launcher, at each game start: what was chosen and what the container asks for; logged */
    fun markLaunched(choice: Choice, requested: String, note: String, now: Long = System.currentTimeMillis()): Launch {
        val l = Launch(choice, requested, note, now)
        launched = l; loaded = null
        log(requestLine(l))
        changed()
        return l
    }

    /** the off-device tests' way back to "no game started in this process" */
    fun forgetForTest() { launched = null; loaded = null }

    /** the log line at game start: "Graphics driver: chosen Recommended; requested Turnip-WoW-scheduler-test" (+ the fallback's why) */
    fun requestLine(l: Launch): String =
        "Graphics driver: chosen ${l.choice.label}; requested ${l.requested}" + (if (l.note.isNotEmpty()) " (${l.note})" else "")

    /** the log line once the watch saw (or gave up): "Graphics driver loaded: Turnip-... 26.3.1-dsf-icd (in Wow.exe, 14 s after Play)" */
    fun loadedLine(l: Launch, s: Loaded): String = "Graphics driver loaded: " + loadedWords(l, s)

    private fun loadedWords(l: Launch, s: Loaded): String = when (s.driver) {
        null -> "none seen yet (${s.looks} looks over ${s.afterMs / 1000} s after Play)"
        else -> s.driver + (s.version?.let { " $it" } ?: "") + " (in ${s.process}, ${s.afterMs / 1000} s after Play)" +
            (if (s.driver != l.requested) "; NOT the requested ${l.requested}" else "")
    }

    /** a restart would change the running game's driver choice */
    fun needsRestart(launched: Choice?, chosen: Choice): Boolean = launched != null && launched != chosen

    /** the "Restart the game" row under Graphics driver: the game runs, it started with the other choice, no restart under way */
    fun showRestart(gameUp: Boolean, restarting: Boolean, launched: Choice?, chosen: Choice): Boolean =
        gameUp && !restarting && needsRestart(launched, chosen)

    /**
     * the problem report's line (StartupDiag, "GPU and container"): the choice, this run's request and what loaded, or the last
     * start's kept line ([last], [KEY_RAN]) when no game started in this run. States and file names only.
     */
    fun reportLine(chosen: Choice, launch: Launch?, seen: Loaded?, last: String?, stamp: (Long) -> String = ::stamp): String {
        val head = "Graphics driver: ${chosen.label} chosen"
        if (launch == null) return "$head; no game start in this run" + (last?.takeIf { it.isNotBlank() }?.let { "; the last one: $it" } ?: "")
        return "$head; " + ranLine(launch, seen, stamp) + (if (needsRestart(launch.choice, chosen)) "; a restart is due" else "")
    }

    /** this start in one line (kept as [KEY_RAN]): "2026-11-23 10:02:11 requested X; loaded Y ..." */
    fun ranLine(launch: Launch, seen: Loaded?, stamp: (Long) -> String = ::stamp): String =
        "${stamp(launch.atMs)} requested ${launch.requested} (${launch.choice.label})" +
            (if (launch.note.isNotEmpty()) ", ${launch.note}" else "") + "; loaded " +
            (seen?.let { loadedWords(launch, it) } ?: "(still looking)")

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    // ---- the memory maps (pure)

    /**
     * one process's /proc/<pid>/maps lines -> the Vulkan driver it has mapped: an adrenotools folder's name (contents/adrenotools/<name>/
     * ... .so), else [SYSTEM] for the vendor's vulkan.adreno.so, else null. An adrenotools driver wins over the system one (adrenotools
     * loads the system Vulkan loader too). Stops at the first adrenotools hit.
     */
    fun driverInMaps(lines: Sequence<String>): String? {
        var system = false
        val marker = "/$ADRENO_DIR/"
        for (raw in lines) {
            val l = raw.removeSuffix(" (deleted)").trimEnd()
            if (!l.endsWith(".so")) continue
            val i = l.indexOf(marker)
            if (i >= 0) {
                val rest = l.substring(i + marker.length)
                val name = rest.substringBefore('/')
                if (name.isNotEmpty() && rest.length > name.length && name != "tmp") return name
            } else if (l.endsWith("/vulkan.adreno.so")) system = true
        }
        return if (system) SYSTEM else null
    }

    /** a process's label from its cmdline (NUL-separated): argv[0]'s last path part (Unix or Windows), safe characters, at most 40 */
    fun processLabel(cmdline: String): String {
        val first = cmdline.substringBefore('\u0000').trim()
        val base = first.substringAfterLast('/').substringAfterLast('\\')
        return base.filter { it.isLetterOrDigit() || it in "._-" }.take(40).ifEmpty { "?" }
    }

    /** a driver's meta.json text -> its "driverVersion" (no JSON library: the off-device tests run without Android's) */
    fun metaVersion(json: String?): String? =
        json?.let { Regex(""""driverVersion"\s*:\s*"([^"]*)"""").find(it)?.groupValues?.get(1) }?.takeIf { it.isNotBlank() }

    // ---- the watch (the device)

    /** a /proc/<pid> folder's real uid (its status' "Uid:" line), null = unreadable */
    private fun uidOf(d: File): Int? = runCatching { File(d, "status").readLines() }.getOrNull()
        ?.firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()

    /** our uid's process ids now (the watch's start: processes alive BEFORE this launch's game, e.g. a previous run's, are not its evidence) */
    fun ourPids(myUid: Int, proc: File = File("/proc")): Set<Int> =
        (proc.listFiles() ?: emptyArray()).mapNotNull { d -> d.name.toIntOrNull()?.takeIf { uidOf(d) == myUid } }.toSet()

    /**
     * one look over our uid's processes (not [myPid], none in [skip]: alive before this launch): the first one with a Vulkan driver
     * mapped, (driver, process label)
     */
    fun scan(myPid: Int, myUid: Int, skip: Set<Int> = emptySet(), proc: File = File("/proc")): Pair<String, String>? {
        var system: Pair<String, String>? = null
        for (d in proc.listFiles() ?: emptyArray()) {
            val pid = d.name.toIntOrNull() ?: continue
            if (pid == myPid || pid in skip) continue
            if (uidOf(d) != myUid) continue
            val found = runCatching { File(d, "maps").useLines { driverInMaps(it) } }.getOrNull() ?: continue
            val label = runCatching { processLabel(File(d, "cmdline").readText()) }.getOrDefault("?")
            if (found != SYSTEM) return found to label
            if (system == null) system = found to label
        }
        return system
    }

    /**
     * after Play: every [everyMs] for at most [forMs], one [scan]; the first driver seen (or none) is [loaded], logged and kept
     * ([keep]: Settings' [KEY_RAN]). A newer launch ends an older watch. [appFiles] = the app's files dir (the drivers' meta.json).
     * Called before the game's processes start: the ones already alive then are skipped ([ourPids]).
     */
    fun watch(launch: Launch, appFiles: File, keep: (String) -> Unit, everyMs: Long = 5_000L, forMs: Long = 180_000L) {
        keep(ranLine(launch, null))
        val myPid = android.os.Process.myPid(); val myUid = android.os.Process.myUid()
        // before this launch's game starts: a process already alive (a previous run's game still closing) is not this launch's evidence
        val before = runCatching { ourPids(myUid) }.getOrDefault(emptySet())
        Thread({
            var looks = 0
            val t0 = System.currentTimeMillis()
            while (launched === launch) {
                runCatching { Thread.sleep(everyMs) }
                if (launched !== launch) return@Thread
                looks++
                val hit = runCatching { scan(myPid, myUid, before) }.getOrNull()
                val after = System.currentTimeMillis() - t0
                if (hit != null || after >= forMs) {
                    val version = hit?.first?.takeIf { it != SYSTEM }?.let { name ->
                        metaVersion(runCatching { File(appFiles, "$ADRENO_DIR/$name/meta.json").readText() }.getOrNull())
                    }
                    val s = Loaded(hit?.first, version, hit?.second ?: "", after, looks)
                    loaded = s
                    log(loadedLine(launch, s))
                    runCatching { keep(ranLine(launch, s)) }
                    changed()
                    return@Thread
                }
            }
        }, "dsf-driver-watch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun log(s: String) { runCatching { android.util.Log.i(TAG, s) } }
}
