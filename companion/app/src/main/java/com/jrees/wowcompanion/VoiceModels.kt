package com.jrees.wowcompanion

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * OFFLINE VOICE'S MODEL FILES (the user, 2026-11-16: "base with optional downloads per language?", then "we could make the per languege
 * downloads part of the addons folder" = Settings > Add-ons): Whisper base, English ([Model.EN]) and every other language in one file
 * ([Model.MULTI]: Spanish, French, German and more). APP FILES, in the app's own storage ([dir] = files/dsforever/voice), NEVER the game's
 * AddOns folder and never inside the APK.
 *
 * THE RULES: a download starts ONLY on the player's press (Settings > Add-ons' Download, or the voice sheet's "Download (148 MB)"), on
 * any network, the size said first; it is PINNED (Hugging Face ggerganov/whisper.cpp at one commit: its exact size and sha256, read on
 * 2026-11-16 from the whole files); resumed where it stopped ([CdnResume]: Range, 206 appended, 200 from the start, 416 / a wrong resume
 * drop the part) with [CdnRetry]'s retries and more rounds while a round still adds bytes; checked for its size and sha256 before it is
 * used (a wrong one is deleted: "Download failed: ..."); room for it plus [ThirdPartyAddons.SPACE_MARGIN] first. Delete removes it.
 * ONE worker thread; [onChange] for the rows and the sheet. A file is READY when it has the pinned size and its sha256 was checked once
 * (remembered by its size and time in "<file>.ok"; a file without that mark is checked again, [State.Checking]).
 * Pure but for [init] (the off-device test gives [start] its own folder and network).
 */
object VoiceModels {
    /** one model file: its [file] name, the pinned [url], [bytes] and [sha256]; [title] = its Add-ons row */
    enum class Model(val id: String, val file: String, val url: String, val bytes: Long, val sha256: String, val title: String,
                     val what: String) {
        EN("en", "ggml-base.en.bin", "$HF/ggml-base.en.bin", 147_964_211L,
            "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002", "Voice: English",
            "Speech to text on this device. Whisper base by OpenAI, MIT."),
        MULTI("multi", "ggml-base.bin", "$HF/ggml-base.bin", 147_951_465L,
            "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe", "Voice: other languages",
            "Spanish, French, German and more in one file.");
        /** its size in the add-on rows' megabytes (1 MB = 1,000,000 bytes, rounded up): 148 */
        val mb: Int get() = ((bytes + 999_999) / 1_000_000).toInt()
        /** "148 MB" */
        val size: String get() = ThirdPartyAddons.size(mb)
    }

    /** what a model file is doing (its row's state) */
    sealed class State {
        object None : State() { override fun toString() = "None" }
        data class Downloading(val pct: Int) : State()
        object Checking : State() { override fun toString() = "Checking" }
        object Ready : State() { override fun toString() = "Ready" }
        data class NoSpace(val line: String) : State()
        data class Failed(val why: String) : State()
    }

    /** the row's words for [s] (terse: the Add-ons rows and the voice sheet) */
    fun line(m: Model, s: State): String = when (s) {
        State.None -> "Not downloaded · ${m.size}"
        is State.Downloading -> "Downloading ${s.pct}%"
        State.Checking -> "Checking"
        State.Ready -> "Ready"
        is State.NoSpace -> s.line
        is State.Failed -> "Download failed: ${s.why}"
    }

    // ---------------------------------------------------------------- the network (DependencyAddons.connect; the test's own)

    /** one answer: [code], its Content-Range, its length (-1 = not said) and its body */
    interface Conn : java.io.Closeable {
        val code: Int
        val contentRange: String?
        val contentLength: Long
        val body: InputStream
    }

    /** opens [url] from byte [from] (null = the whole file); throws IOException("HTTP <code>") for a refusal, as DependencyAddons does */
    fun interface Net { fun open(url: String, from: Long?): Conn }

    private val HTTP: Net = Net { url, from ->
        val c = DependencyAddons.connect(url, from)
        object : Conn {
            override val code = c.responseCode
            override val contentRange: String? = c.getHeaderField("Content-Range")
            override val contentLength = c.contentLengthLong
            override val body: InputStream by lazy { c.inputStream }
            override fun close() { runCatching { c.disconnect() } }
        }
    }

    // ---------------------------------------------------------------- the state

    @Volatile private var dir: File? = null
    @Volatile private var net: Net = HTTP
    @Volatile private var pause: (Long) -> Unit = { Thread.sleep(it) }
    /** the free bytes where the file goes (the test's own) */
    @Volatile internal var freeSpace: (File) -> Long = { it.usableSpace }
    private val live = HashMap<Model, State>()
    private val cancelled = HashSet<Model>()
    private val queued = HashSet<Model>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "voice-dl").apply { isDaemon = true } }

    fun init(c: Context) {
        if (dir != null) return
        start(File(c.applicationContext.filesDir, "dsforever/voice"))
    }

    /** the off-device test's way in: its own folder, network and pause (no Context) */
    fun start(folder: File, network: Net = HTTP, wait: (Long) -> Unit = { Thread.sleep(it) }) {
        dir = folder; net = network; pause = wait
        synchronized(live) { live.clear(); cancelled.clear() }
    }

    /** offline voice is in this build (libdsfwhisper.so): Settings > Add-ons shows the Voice group (the off-device tests set their own) */
    @Volatile var offered: () -> Boolean = { WhisperNative.available }

    fun onChange(l: () -> Unit) { listeners.add(l) }
    fun offChange(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { for (l in listeners) runCatching { l() } }

    fun fileOf(m: Model): File = File(dir ?: File("."), m.file)
    private fun partOf(m: Model) = File(dir ?: File("."), m.file + ".part")
    private fun markOf(m: Model) = File(dir ?: File("."), m.file + ".ok")
    private fun stamp(f: File) = "${f.length()}:${f.lastModified()}"

    /** the file is there, the pinned size, its sha256 checked (the mark matches its size and time) */
    fun ready(m: Model): Boolean {
        if (dir == null) return false
        val f = fileOf(m)
        return f.isFile && f.length() == m.bytes && runCatching { markOf(m).readText() == stamp(f) }.getOrDefault(false)
    }

    /** some model file is ready (offline voice can listen once the library is in) */
    fun anyReady(): Boolean = Model.values().any { ready(it) }

    /** the row's state now: what the worker is doing, else READY / CHECKING (the right size, no mark yet) / NONE */
    fun state(m: Model): State {
        synchronized(live) { live[m] }?.let { return it }
        if (ready(m)) return State.Ready
        val f = fileOf(m)
        if (dir != null && f.isFile && f.length() == m.bytes) { check(m); return State.Checking }
        return State.None
    }

    private fun setLive(m: Model, s: State?) {
        synchronized(live) { if (s == null) live.remove(m) else live[m] = s }
        changed()
    }

    /** a file with the pinned size and no mark (copied in, the mark lost) is checked on the worker, once */
    private fun check(m: Model) {
        if (!synchronized(queued) { queued.add(m) }) return
        worker.execute {
            try { verify(m) } finally { synchronized(queued) { queued.remove(m) } }
        }
    }

    /** the file's sha256 against the pin: the mark written (READY), or the file deleted; true = ready */
    internal fun verify(m: Model): Boolean {
        val f = fileOf(m)
        if (!f.isFile || f.length() != m.bytes) return false
        setLive(m, State.Checking)
        val ok = runCatching { DependencyAddons.sha256(f) == m.sha256 }.getOrDefault(false)
        if (ok) runCatching { markOf(m).writeText(stamp(f)) } else { f.delete(); markOf(m).delete() }
        setLive(m, null)
        return ok
    }

    /** the engine could not load a ready file: its mark goes, so it is checked again (a damaged file is then deleted) */
    fun distrust(m: Model) { markOf(m).delete(); changed() }

    // ---------------------------------------------------------------- the player's presses

    /** Download (the player's press only): on the worker, once; a file already ready is left alone */
    fun download(m: Model) {
        if (dir == null || ready(m)) return
        synchronized(live) { cancelled.remove(m) }
        if (!synchronized(queued) { queued.add(m) }) return
        setLive(m, State.Downloading(pctOf(m)))
        worker.execute {
            try { fetch(m) } finally { synchronized(queued) { queued.remove(m) } }
        }
    }

    /** Cancel: the download stops (the part is kept for the next Download) */
    fun cancel(m: Model) {
        synchronized(live) { cancelled.add(m); live.remove(m) }
        changed()
    }

    /** Delete: the download stops, the file, its part and its mark go */
    fun delete(m: Model) {
        synchronized(live) { cancelled.add(m); live.remove(m) }
        if (dir != null) { fileOf(m).delete(); partOf(m).delete(); markOf(m).delete() }
        DiagLog.event("voice", "model ${m.id} deleted")
        changed()
    }

    /** a download is running or queued now */
    fun busy(m: Model): Boolean = synchronized(queued) { m in queued }

    private fun pctOf(m: Model): Int = (partOf(m).let { if (it.isFile) it.length() else 0L } * 100 / m.bytes).toInt().coerceIn(0, 100)
    private fun stopped(m: Model) = synchronized(live) { m in cancelled }

    private const val STOPPED = "stopped"

    /** ONE MODEL'S DOWNLOAD, on this thread (the worker; the off-device test calls it directly): the resulting state */
    internal fun fetch(m: Model): State {
        val f = fileOf(m)
        val part = partOf(m)
        try {
            f.parentFile?.mkdirs()
            if (part.isFile && part.length() > m.bytes) part.delete()
            val have = if (part.isFile) part.length() else 0L
            val need = m.bytes - have + ThirdPartyAddons.SPACE_MARGIN
            if (freeSpace(f.parentFile ?: f) < need) {
                return State.NoSpace("Needs ${ThirdPartyAddons.size(((need + 999_999) / 1_000_000).toInt())} free on this device")
                    .also { setLive(m, it) }
            }
            DiagLog.event("voice", "model ${m.id} download from ${have / 1_000_000} MB")
            var round = 0
            while (true) {
                val before = if (part.isFile) part.length() else 0L
                val why = CdnRetry.run(listOf(HOST), pause = pause, final = { it == STOPPED || it.startsWith("the file is not") }) { _ -> attempt(m, part) }
                if (why == null) break
                if (stopped(m)) { setLive(m, null); return State.None }
                val grew = (if (part.isFile) part.length() else 0L) - before
                if (!CdnResume.keepGoing(grew, ++round)) throw IOException(why.removeSuffix(" from $HOST"))
            }
            if (part.length() != m.bytes) throw IOException("the download is incomplete")
            setLive(m, State.Checking)
            if (DependencyAddons.sha256(part) != m.sha256) { part.delete(); throw IOException("the download's checksum is wrong") }
            f.delete(); markOf(m).delete()
            if (!part.renameTo(f)) throw IOException("the download could not be kept")
            markOf(m).writeText(stamp(f))
            DiagLog.event("voice", "model ${m.id} ready")
            setLive(m, null)
            return State.Ready
        } catch (t: Throwable) {
            if (stopped(m)) { setLive(m, null); return State.None }
            val s = State.Failed(DependencyAddons.reasonOf(t))
            DiagLog.event("voice", "model ${m.id} download stopped: ${s.why}")
            setLive(m, s)
            return s
        }
    }

    /** one request: null = the part is the whole file now; else why not ([STOPPED] = the player's Cancel / Delete) */
    private fun attempt(m: Model, part: File): String? {
        if (stopped(m)) return STOPPED
        var have = if (part.isFile) part.length() else 0L
        if (have >= m.bytes) return null
        val from = CdnResume.rangeFor(have, m.bytes)
        val c = try { net.open(m.url, if (from > 0) from else null) } catch (e: IOException) {
            val msg = e.message.orEmpty()
            if (!HTTP_CODE.matches(msg)) throw e
            if (CdnResume.dropsPart(msg)) part.delete()
            return msg
        }
        c.use {
            when (val u = CdnResume.use(c.code, c.contentRange, c.contentLength, from, m.bytes)) {
                is CdnResume.Use.Refuse -> { if (CdnResume.dropsPart(u.why)) part.delete(); return u.why }
                is CdnResume.Use.Fresh -> {
                    if (u.size > 0 && u.size != m.bytes) return "the file is not the size it was pinned at"
                    have = 0L
                }
                is CdnResume.Use.Append -> {}
            }
            java.io.FileOutputStream(part, have > 0).use { o ->
                val buf = ByteArray(256 * 1024)
                var got = have; var told = got
                val input = c.body
                while (true) {
                    if (stopped(m)) return STOPPED
                    val n = input.read(buf); if (n < 0) break
                    o.write(buf, 0, n); got += n
                    if (got > m.bytes) { o.close(); part.delete(); return "the file is bigger than it should be" }
                    if (got - told >= 2_000_000 || got == m.bytes) { told = got; setLive(m, State.Downloading((got * 100 / m.bytes).toInt())) }
                }
            }
        }
        return if (part.length() == m.bytes) null else "network error (the download ended early)"
    }

    private val HTTP_CODE = Regex("HTTP \\d{3}")

    /** the pinned host (one: Hugging Face; its file CDN is a redirect DependencyAddons.connect follows over HTTPS) */
    const val HOST = "huggingface.co"
    /** the pinned commit of ggerganov/whisper.cpp's model repository (2026-11-16) */
    const val HF_COMMIT = "5359861c739e955e79d9a303bcbc70fb988958b1"
}

private const val HF = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1"
