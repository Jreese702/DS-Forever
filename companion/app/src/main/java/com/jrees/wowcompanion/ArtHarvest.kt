package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * THE ART HARVEST (the user 2026-10-09: "can we scrape the assets that we use from the game files before launching?" -> "Phase 1 now,
 * interiors next", "Mostly before Play"): the APK carries NO game-derived art; the first run builds it from the user's own game files
 * ([GameFiles]) into the app's PRIVATE storage ([root]), from OUR recipes (assets/harvest/<step>.json: fileDataIDs, rects, names - our
 * data, not the game's). Each [Step] has an id and a version; a stamp (root/stamp.json) remembers the game build each step was made from,
 * so a game update (or a new recipe version) rebuilds only the steps it touches.
 *
 * The six art steps, including minimap tiles, are required before Play. Completion is checked against the active game build. The 7th,
 * interiors ([IndoorSchedulerStep], Phase 2), is OPTIONAL and DETACHED: it runs on its own thread, never touches [state], so it never
 * blocks Play, and stamps itself per game build when every building is done.
 *
 * THE OWNERS write the steps (UI Art: kit / fonts / cast / bags / minimap kit / home pieces / icons.pak; Map: world-map set, poi icons,
 * minimap tiles) against [Job]; Home owns this file, the reader and the launcher's screen. A step registers in [STEPS] (one line).
 */
object ArtHarvest {
    private const val TAG = "WCHARVEST"

    /** one piece of the harvest */
    interface Step {
        /** the stamp key and the recipe's name (assets/harvest/<id>.json when it has one) */
        val id: String
        /** bump when the recipe or the step's code changes what it writes: the step runs again once */
        val version: Int
        /** true = before Play, behind the progress screen; false = in the background after */
        val beforePlay: Boolean
        /** a rough share of the whole run, for the progress bar (e.g. the number of files it writes) */
        val weight: Int
        /**
         * true = the harvest calls [begin] instead of [run]: it only starts the step's own thread and returns at once; the harvest
         * neither waits for it, nor stamps it, nor shows it in [state] (the step reports on its own and calls [markDetachedDone]
         * when it has finished for this build)
         */
        val detached: Boolean get() = false
        fun begin(ctx: Context) { error("$id is not a detached step") }
        fun run(job: Job)
    }

    /** what a step works with: the game's files, the output folder, its recipe and the progress line */
    class Job internal constructor(val ctx: Context, val game: GameFiles, val out: File, private val stepId: String,
                                   private val onProgress: (Float, String) -> Unit) {
        var inputKey: String? = null
        /** this step's recipe (assets/harvest/<id>.json), or an empty object */
        fun recipe(name: String = stepId): JSONObject = runCatching {
            ctx.assets.open("harvest/$name.json").use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        }.getOrElse { JSONObject() }

        /** [done] of [total] in this step, and what is being made (shown under the bar) */
        fun progress(done: Int, total: Int, what: String = "") = onProgress(if (total > 0) done.toFloat() / total else 0f, what)

        /**
         * RESUME (issue #11, 1.0.9: "stuck on 68% EPL, then reopened stuck on 8% EPL"): the parts of this step finished for THIS game
         * build, kept across a restart so a reopened app goes on where it stopped; forgotten when the step is stamped done (or for
         * another build).
         */
        private val resume by lazy { ResumeList(File(out, ".resume/$stepId-${game.buildKey.ifEmpty { "nobuild" }}.txt")) }
        fun finished(part: String): Boolean = resume.finished(part)
        fun markFinished(part: String) = resume.markFinished(part)

        fun file(rel: String): File = File(out, rel).also { it.parentFile?.mkdirs() }
        fun writeBytes(rel: String, b: ByteArray) = atomic(file(rel)) { it.write(b) }
        fun writePng(rel: String, bmp: Bitmap) = atomic(file(rel)) { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        @Suppress("DEPRECATION")
        fun writeWebp(rel: String, bmp: Bitmap, quality: Int) = atomic(file(rel)) {
            val fmt = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            bmp.compress(fmt, quality, it)
        }

        private fun atomic(f: File, w: (FileOutputStream) -> Unit) {
            val tmp = File(f.path + ".part")
            FileOutputStream(tmp).use(w)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /** every step, in the order they run (owners add theirs here) */
    val STEPS: List<Step> by lazy {
        listOf<Step>(KitSteps.Kit, KitSteps.Cast, KitSteps.Icons) + MapHarvestSteps.STEPS + IndoorSchedulerStep + AllIconsStep
    }

    /** the app's own art folder (replaces /sdcard/WowCompanion for everything game-derived) */
    fun root(ctx: Context): File = File(ctx.filesDir, "art").also { it.mkdirs() }
    /** the same folder for code that has no Context at hand (set at app start by [init]) */
    @Volatile var rootDir: File? = null
        private set
    fun init(ctx: Context) {
        IconRequirements.init(ctx)
        if (rootDir != null) return
        val app = ctx.applicationContext
        rootDir = root(app)
        installOwnArt(app)
    }

    /**
     * OUR OWN PICTURES SHIP IN THE APK (the user on h1: "its even missing the shit that we made ourselves like the spiral?"): they were only
     * copied by the kit step, so a failed harvest lost them. Now every start copies the APK's own art into the art folder when a file is
     * missing or differs in size, harvest or no harvest: assets/own/<rel> -> <root>/<rel> (the art folder's own layout), and UI Art's
     * assets/harvest/orig/<name> -> ui/kit/<name>. tools/harvest/check_apk_art.sh fails a build that lacks any of tools/harvest/own_art.txt.
     */
    private fun installOwnArt(ctx: Context) {
        val root = rootDir ?: return
        fun copy(asset: String, to: File) = runCatching {
            val size = ctx.assets.openFd(asset).use { it.length }
            if (to.isFile && to.length() == size) return@runCatching
            to.parentFile?.mkdirs()
            val tmp = File(to.path + ".part")
            ctx.assets.open(asset).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (!tmp.renameTo(to)) { to.delete(); tmp.renameTo(to) }
        }.onFailure {
            // a compressed asset has no fd length: copy it whenever the file is missing
            if (!to.isFile) runCatching { to.parentFile?.mkdirs(); ctx.assets.open(asset).use { i -> to.outputStream().use { o -> i.copyTo(o) } } }
        }
        fun walk(dir: String, rel: String) {
            val names = runCatching { ctx.assets.list(dir) }.getOrNull() ?: return
            for (n in names) {
                val a = "$dir/$n"; val r = if (rel.isEmpty()) n else "$rel/$n"
                if (runCatching { ctx.assets.list(a) }.getOrNull().isNullOrEmpty()) copy(a, File(root, r)) else walk(a, r)
            }
        }
        walk("own", "")
        runCatching { ctx.assets.list("harvest/orig") }.getOrNull()?.forEach { n -> copy("harvest/orig/$n", File(root, "ui/kit/$n")) }
        // UI Art's own redraws of the bar's Home button stone (assets/harvest/own): the SAME names as the harvested game pieces, so they
        // are copied ONLY when neither the art folder nor the old pushed folder has the file (never over a harvested stone)
        runCatching { ctx.assets.list("harvest/own") }.getOrNull()?.forEach { n ->
            val to = File(root, "ui/kit/$n")
            if (!to.isFile && !File(LEGACY, "ui/kit/$n").isFile) copy("harvest/own/$n", to)
        }
    }

    /** where the art lived before the harvest (pushed / snapshot); read until the harvest has made the same file */
    const val LEGACY = "/sdcard/WowCompanion"
    /** an art file by its path under the root: the harvested one when it exists, else the old /sdcard one */
    fun file(rel: String): File {
        rootDir?.let { val f = File(it, rel); if (f.exists()) return f }
        return File(LEGACY, rel)
    }
    /** an art folder the same way (the harvested one once it exists) */
    fun dir(rel: String): File {
        rootDir?.let { val f = File(it, rel); if (f.isDirectory) return f }
        return File(LEGACY, rel)
    }

    sealed class State {
        open val blocksPlay: Boolean = false
        object Idle : State()
        class Running(val beforePlay: Boolean, val fraction: Float, val what: String) : State() {
            override val blocksPlay: Boolean get() = beforePlay
        }
        object Ready : State()
        class Failed(val why: String, val beforePlay: Boolean = true) : State() {
            override val blocksPlay: Boolean get() = beforePlay
        }
    }
    @Volatile var state: State = State.Idle
        private set
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(State) -> Unit>()
    fun onState(l: (State) -> Unit) { listeners.add(l) }
    fun offState(l: (State) -> Unit) { listeners.remove(l) }
    private fun set(s: State) { state = s; for (l in listeners) runCatching { l(s) } }

    // ------------------------------------------------------------------ the stamp

    private fun stampFile(ctx: Context) = File(root(ctx), "stamp.json")
    private fun stamp(ctx: Context): JSONObject = runCatching { JSONObject(stampFile(ctx).readText()) }.getOrElse { JSONObject() }

    /** the steps whose output is missing or older than this game build / this step version */
    fun pending(ctx: Context, beforePlay: Boolean? = null): List<Step> {
        val build = GameFiles.activeBuild(GameFiles.gameRoot(ctx))?.second ?: return emptyList()
        val st = stamp(ctx)
        return STEPS.filter { s ->
            (beforePlay == null || s.beforePlay == beforePlay) &&
                st.optJSONObject(s.id)?.let { it.optString("build") != build || it.optInt("v") != s.version ||
                    (s.id == "icons" && it.optString("inputs") != IconRequirements.inputKey(ctx, build)) } != false
        }
    }

    /** true = Play has to wait for the before-Play steps (the launcher shows the progress screen) */
    fun needsBeforePlay(ctx: Context): Boolean = pending(ctx, beforePlay = true).isNotEmpty()

    fun beforePlayReady(ctx: Context): Boolean =
        !state.blocksPlay && GameFiles.activeBuild(GameFiles.gameRoot(ctx)) != null &&
            pending(ctx, beforePlay = true).isEmpty()

    private fun markDone(ctx: Context, s: Step, build: String, inputs: String?) = synchronized(this) {
        // the step is whole: its resume list (any build's) goes
        File(root(ctx), ".resume").listFiles { f -> f.name.startsWith("${s.id}-") }?.forEach { it.delete() }
        val st = stamp(ctx)
        st.put(s.id, JSONObject().put("build", build).put("v", s.version).put("inputs", inputs).put("at", System.currentTimeMillis()))
        val f = stampFile(ctx); val tmp = File(f.path + ".part")
        tmp.writeText(st.toString()); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** a [Step.detached] step has finished everything for [build]: stamp it like any other */
    fun markDetachedDone(ctx: Context, s: Step, build: String) {
        require(s.detached && s in STEPS)
        markDone(ctx, s, build, null)
        Log.i(TAG, "harvest step ${s.id} v${s.version} done (detached)")
    }

    // ------------------------------------------------------------------ running

    @Volatile private var worker: Thread? = null
    /** this run's before-Play result (null = not known yet) and who waits for it */
    @Volatile private var firstResult: Boolean? = null
    private val waiters = ArrayList<(Boolean) -> Unit>()

    /**
     * Runs what is pending on one worker thread: the before-Play steps first ([State.Running] with beforePlay = true until they are all
     * done, then [onBeforePlayDone] on that thread), then the background steps. A call while it runs only waits for the same result.
     */
    fun start(ctx: Context, onBeforePlayDone: (Boolean) -> Unit = {}) {
        val app = ctx.applicationContext
        init(app)
        synchronized(waiters) {
            if (worker?.isAlive == true) {
                firstResult?.let { r -> onBeforePlayDone(r); return }
                waiters.add(onBeforePlayDone); return
            }
            firstResult = null
            waiters.add(onBeforePlayDone)
            worker = Thread({
                try {
                    work(app) { r -> beforePlayDone(r) }
                } catch (e: Exception) {
                    Log.w(TAG, "harvest failed", e)
                    val requiredReady = runCatching {
                        GameFiles.activeBuild(GameFiles.gameRoot(app)) != null && !needsBeforePlay(app)
                    }.getOrDefault(false)
                    set(State.Failed(e.message ?: "the game files could not be read", beforePlay = !requiredReady))
                    beforePlayDone(requiredReady)
                }
            }, "art-harvest").apply { priority = Thread.NORM_PRIORITY - 1; start() }
        }
    }

    private fun beforePlayDone(r: Boolean) {
        val ws = synchronized(waiters) { firstResult = r; ArrayList(waiters).also { waiters.clear() } }
        for (w in ws) runCatching { w(r) }
    }

    private fun work(ctx: Context, onBeforePlayDone: (Boolean) -> Unit) {
        if (GameFiles.activeBuild(GameFiles.gameRoot(ctx)) == null) {
            set(State.Failed("no game files found in ${GameFiles.gameRoot(ctx).path}"))
            onBeforePlayDone(false)
            return
        }
        val first = pending(ctx, beforePlay = true)
        val all = pending(ctx, beforePlay = false)
        val later = all.filter { !it.detached }
        val detached = all.filter { it.detached }
        if (first.isEmpty() && later.isEmpty()) {
            set(State.Ready); onBeforePlayDone(true); startDetached(ctx, detached); return
        }
        // a CDN hiccup while it fetches the build's tables passes: tried again (HarvestRetry) before the art says it failed
        // after a game update the open downloads the build's tables (about 180 MB): the launcher shows how far (b-art-progress)
        GameFiles.onTables = { line -> set(State.Running(first.isNotEmpty(), 0f, line)) }
        val game = try {
            HarvestRetry.run(onRetry = { n, d, why -> Log.w(TAG, "harvest: open failed ($why), try ${n + 1} in ${d / 1000} s") }) {
                GameFiles.open(ctx) ?: throw java.io.IOException(GameFiles.lastWhy ?: "the game files could not be read")
            }.getOrNull()
        } finally { GameFiles.onTables = null }
        if (game == null) {
            val why = GameFiles.lastWhy ?: "the game files could not be read"
            Log.w(TAG, "harvest: $why")
            set(State.Failed(why, beforePlay = first.isNotEmpty()))
            onBeforePlayDone(first.isEmpty())
            return
        }
        game.use { g ->
            val okFirst = runAll(ctx, g, first, beforePlay = true)
            // Publish the nonblocking phase before waking launch waiters.
            if (okFirst) set(if (later.isEmpty()) State.Ready else State.Running(false, 0f, later.first().id))
            onBeforePlayDone(okFirst)
            if (!okFirst) return
            startDetached(ctx, detached)
            if (later.isNotEmpty() && runAll(ctx, g, later, beforePlay = false)) set(State.Ready)
        }
    }

    /** detached steps start only after the required ones are all done; their own failures stay theirs */
    private fun startDetached(ctx: Context, steps: List<Step>) {
        for (s in steps) runCatching {
            s.begin(ctx)
        }.onFailure { Log.w(TAG, "harvest step ${s.id} could not start", it) }
    }

    private fun runAll(ctx: Context, g: GameFiles, steps: List<Step>, beforePlay: Boolean): Boolean {
        val total = steps.sumOf { it.weight.coerceAtLeast(1) }.toFloat()
        var before = 0f
        for (s in steps) {
            val w = s.weight.coerceAtLeast(1)
            val base = before
            set(State.Running(beforePlay, base / total, s.id))
            val t0 = System.currentTimeMillis()
            var job = Job(ctx, g, root(ctx), s.id) { f, what -> set(State.Running(beforePlay, (base + f.coerceIn(0f, 1f) * w) / total, what)) }
            // a failure (often a CDN hiccup on a table) is tried again before the art says so (HarvestRetry); the last reason is shown
            val result = HarvestRetry.run(onRetry = { n, d, why ->
                Log.w(TAG, "harvest step ${s.id} failed ($why), try ${n + 1} in ${d / 1000} s")
                set(State.Running(beforePlay, base / total, "${s.id}: trying again in ${d / 1000} s"))
            }) {
                job = Job(ctx, g, root(ctx), s.id) { f, what -> set(State.Running(beforePlay, (base + f.coerceIn(0f, 1f) * w) / total, what)) }
                s.run(job)
            }.onFailure { Log.w(TAG, "harvest step ${s.id} failed", it) }
            if (result.isFailure) {
                val reason = result.exceptionOrNull()?.message ?: "unknown reason"
                set(State.Failed("could not build ${s.id}: $reason", beforePlay))
                return false
            }
            markDone(ctx, s, g.buildKey, job.inputKey)
            Log.i(TAG, "harvest step ${s.id} v${s.version} done in ${System.currentTimeMillis() - t0} ms")
            before += w
        }
        return true
    }
}
