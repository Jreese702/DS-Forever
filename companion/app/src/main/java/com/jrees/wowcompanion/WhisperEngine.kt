package com.jrees.wowcompanion

import android.content.ComponentCallbacks2
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * OFFLINE VOICE TYPING for the voice sheet (the user, 2026-11-16: "could we use something like https://handy.computer/?", then "base with
 * optional downloads per language"): the microphone through AudioRecord (16 kHz mono PCM16, only once the player allowed it in Android's
 * own question, [VoiceMic]), [Voice.Vad]'s stop rule (1.2 s of quiet after the words, 15 s at most, A stops at once), then whisper.cpp
 * ([WhisperNative]) on a background thread with the model the Voice language wants ([Voice.whisperModel]: Settings.voiceLanguage, else the app's language; #477). The sheet shows "Listening",
 * then "Working", then the words in the box; NOTHING IS SENT by itself (the words go in only on A / Start, the sheet's rule).
 *
 * Every answer reaches the sink on the main thread, for the current session only: no recorder = [Voice.E_AUDIO], nothing said =
 * [Voice.E_SPEECH_TIMEOUT], no words = [Voice.E_NO_MATCH], a model that would not load or a failed run = [Voice.E_SERVER] ("Voice
 * busy"; the model is checked again, [VoiceModels.distrust]). The model stays loaded [IDLE_MS] after its last use (it is about 300 MB),
 * and goes on Android's running-low warning and at every memory relief (Play, the world, each loading screen: memory work #56) - never
 * while a session listens or works its words out ([VoiceHold]). Nothing heard is ever logged; the hints are the names only, never logged.
 */
class WhisperEngine(context: Context,
    /** HOLD TO TALK (HoldTalk, the user 2026-11-16): the recording lasts while the button is held - only [stop] (the release) or
     *  [Voice.MAX_MS] ends it, never the quiet rule; nothing above [Voice.MIN_SPEECH_DBFS] heard = [Voice.E_SPEECH_TIMEOUT] */
    private val held: Boolean = false) : ChatKeyboard.VoiceListen {
    private val app = context.applicationContext
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var session = 0
    @Volatile private var stopAsked = false
    private var recorder: Thread? = null

    override fun start(online: Boolean, lang: String, hints: List<String>, sink: VoiceEngine.Sink): Boolean {
        cancel()
        val pick = Voice.whisperModel(Tr.lang(), on(), Settings.voiceLanguage) ?: return false
        if (!VoiceMic.granted(app)) { ui.post { sink.error(Voice.E_PERMISSIONS) }; return true }
        val s = ++session
        stopAsked = false
        hold.begin(s)                                     // memory work #56 (D4): no free until this session's words are out
        val prompt = hints.take(PROMPT_NAMES).joinToString(", ").take(PROMPT_CHARS).ifBlank { null }
        // the model loads while the player speaks; a hold keeps it warm longer (ptt-speed: [PTT_IDLE_MS])
        if (held) warmMs = PTT_IDLE_MS
        work.execute { if (s == session) runCatching { loaded(pick.first) } }
        val t = Thread({ record(s, pick, prompt, sink) }, "voice-rec")
        recorder = t
        t.start()
        DiagLog.event("voice", "offline listen model=${pick.first.id} lang=${pick.second}")
        return true
    }

    /** ptt-speed: the model starts loading at the push-to-talk press (the hold starts [HoldTalk.HOLD_MS] later), so a cold load
     *  overlaps the hold and the talking; nothing is recorded */
    fun preload() {
        val pick = Voice.whisperModel(Tr.lang(), on(), Settings.voiceLanguage) ?: return
        if (held) warmMs = PTT_IDLE_MS
        work.execute { runCatching { loaded(pick.first) } }
    }

    /** A: the recording stops now, the words are worked out */
    override fun stop() { stopAsked = true }

    /** the session goes: the recording stops, a run in progress is aborted, nothing it says is used */
    override fun cancel() {
        val was = session
        session++
        stopAsked = true
        recorder = null
        val h = synchronized(lock) { handle }
        if (h != 0L && running) runCatching { WhisperNative.abort(h) }
        ended(was)
    }

    /** offline voice has no language packs of Android's */
    override fun getPack(lang: String): Boolean = false

    private fun live(s: Int) = s == session
    /** session [s] is over (its words out, an error, a cancel): a free asked meanwhile runs now (memory work #56, D4) */
    private fun ended(s: Int) { if (hold.end(s)) release("after the words") }
    private fun post(s: Int, f: () -> Unit) = ui.post { if (live(s)) f() }

    private fun record(s: Int, pick: Pair<VoiceModels.Model, String>, prompt: String?, sink: VoiceEngine.Sink) {
        var handed = false
        try { handed = recordInner(s, pick, prompt, sink) } finally { if (!handed) ended(s) }
    }

    /** true = the words went on to [transcribe] (it ends the session) */
    private fun recordInner(s: Int, pick: Pair<VoiceModels.Model, String>, prompt: String?, sink: VoiceEngine.Sink): Boolean {
        val frame = RATE * Voice.FRAME_MS / 1000
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, frame * 2 * 8))
        } catch (t: Throwable) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec?.release() }
            post(s) { sink.error(Voice.E_AUDIO) }
            return false
        }
        val pcm = ShortArray(RATE * Voice.MAX_MS / 1000 + frame)
        var n = 0
        val vad = Voice.Vad()
        var outcome = Voice.Vad.Step.GO
        var peak = -90f
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { post(s) { sink.error(Voice.E_AUDIO) }; return false }
            while (live(s) && !stopAsked && n + frame <= pcm.size) {
                var got = 0
                while (got < frame && live(s)) {
                    val r = rec.read(pcm, n + got, frame - got)
                    if (r <= 0) break
                    got += r
                }
                if (got < frame) { if (live(s) && n == 0) { post(s) { sink.error(Voice.E_AUDIO) }; return false }; break }
                val db = Voice.dbfs(pcm, n, frame)
                n += frame
                val level = Voice.rmsDb(db)
                post(s) { sink.rms(level) }
                outcome = vad.frame(db)
                if (db > peak) peak = db
                if (HoldTalk.stopsOn(held, outcome)) break
            }
        } catch (t: Throwable) {
            post(s) { sink.error(Voice.E_AUDIO) }
            return false
        } finally {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        if (!live(s)) return false
        if (held && !HoldTalk.heardSomething(n * 1000L / RATE, peak)) { post(s) { sink.error(Voice.E_SPEECH_TIMEOUT) }; return false }
        if (!held && outcome == Voice.Vad.Step.NO_SPEECH) { post(s) { sink.error(Voice.E_SPEECH_TIMEOUT) }; return false }
        if (n == 0) { post(s) { sink.error(Voice.E_NO_MATCH) }; return false }
        post(s) { sink.working() }
        // memory work #56 (D4): the model was let go (a loading screen, the world, Android's pressure): it loads again now, said so
        if (!warmFor(pick.first)) post(s) { sink.loading() }
        val count = n
        val recEnd = android.os.SystemClock.uptimeMillis()
        work.execute { try { transcribe(s, pick, pcm, count, prompt, sink, recEnd) } finally { ended(s) } }
        return true
    }

    private fun transcribe(s: Int, pick: Pair<VoiceModels.Model, String>, pcm: ShortArray, n: Int, prompt: String?, sink: VoiceEngine.Sink,
                           recEnd: Long) {
        if (!live(s)) return
        // ptt-speed timing: how long the words waited behind the work thread (the model's load started at the press), then a load here
        val waited = android.os.SystemClock.uptimeMillis() - recEnd
        val warm = synchronized(lock) { handle != 0L && handleModel == pick.first }
        val l0 = android.os.SystemClock.uptimeMillis()
        val h = runCatching { loaded(pick.first) }.getOrDefault(0L)
        val loadMs = android.os.SystemClock.uptimeMillis() - l0
        if (h == 0L) {
            VoiceModels.distrust(pick.first)
            DiagLog.event("voice", "offline model ${pick.first.id} did not load")
            post(s) { sink.error(Voice.E_SERVER) }
            return
        }
        val t0 = android.os.SystemClock.uptimeMillis()
        running = true
        val text = try { WhisperNative.transcribe(h, pcm, n, pick.second, THREADS, prompt) } catch (t: Throwable) { null } finally { running = false }
        lastUsed = android.os.SystemClock.uptimeMillis()
        idleLater()
        if (!live(s)) return
        val words = text?.let { Voice.whisperClean(it, 4000) }
        // the timing only, never the words: ms = whisper_full, wait = release -> start (a load started at the press finishing),
        // load = a load here (0 + warm = the model was loaded already)
        DiagLog.event("voice", "offline words ms=${lastUsed - t0} audio_ms=${n * 1000L / RATE} chars=${words?.length ?: -1} " +
            "wait_ms=$waited load_ms=$loadMs ${if (warm) "warm" else "cold"} threads=$THREADS")
        when {
            text == null -> post(s) { sink.error(Voice.E_SERVER) }
            words.isNullOrEmpty() -> post(s) { sink.error(Voice.E_NO_MATCH) }
            else -> post(s) { sink.final(words) }
        }
    }

    companion object {
        const val RATE = 16_000
        const val THREADS = 4
        const val PROMPT_NAMES = 30
        const val PROMPT_CHARS = 200
        /** the model stays loaded this long after its last use */
        const val IDLE_MS = 60_000L
        /** ptt-speed (the user, 2026-11-18: "the time between sending a PTT from voice and it actually sending in chat seems really
         *  high"): after a hold to talk the model stays loaded this long, so the next PTT pays no load. Android's running-low warning
         *  still frees it at once while idle ([hookTrim]) */
        const val PTT_IDLE_MS = 5 * 60_000L
        /** how long the model stays loaded after its last use now ([IDLE_MS], or [PTT_IDLE_MS] once a hold used it) */
        @Volatile internal var warmMs = IDLE_MS

        /** offline voice's state now: the library and which model files are ready */
        fun on(): Voice.WhisperOn = Voice.WhisperOn(WhisperNative.available,
            VoiceModels.ready(VoiceModels.Model.EN), VoiceModels.ready(VoiceModels.Model.MULTI),
            VoiceModels.ready(VoiceModels.Model.EN_SMALL), VoiceModels.ready(VoiceModels.Model.MULTI_SMALL))

        // ---- THE LOADED MODEL (one, shared by every board; only the work thread loads, runs and frees it)
        /** memory work #56 (D4): the sessions under way, which hold a free back until their words are out ([VoiceHold]) */
        internal val hold = VoiceHold()
        /** [m] is loaded now (no load would be needed) */
        internal fun warmFor(m: VoiceModels.Model): Boolean = synchronized(lock) { handle != 0L && handleModel == m }
        private val lock = Any()
        private var handle = 0L
        private var handleModel: VoiceModels.Model? = null
        @Volatile private var running = false
        @Volatile private var lastUsed = 0L
        private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "voice-work").apply { isDaemon = true } }
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var trimHooked = false

        /** the model [m] loaded (the work thread), another one freed first; 0 = it would not load */
        private fun loaded(m: VoiceModels.Model): Long {
            synchronized(lock) { if (handle != 0L && handleModel == m) return handle }
            freeNow()
            if (!VoiceModels.ready(m)) return 0L
            val t0 = android.os.SystemClock.uptimeMillis()
            val h = WhisperNative.load(VoiceModels.fileOf(m).absolutePath)
            synchronized(lock) { handle = h; handleModel = if (h != 0L) m else null }
            lastUsed = android.os.SystemClock.uptimeMillis()
            if (h != 0L) { hookTrim(); idleLater(); DiagLog.event("voice", "offline model ${m.id} loaded ms=${lastUsed - t0}") }
            return h
        }

        private fun freeNow(): Long {
            val h = synchronized(lock) { handle.also { handle = 0L; handleModel = null } }
            if (h == 0L) return 0L
            runCatching { WhisperNative.free(h) }
            DiagLog.event("voice", "offline model freed")
            warmMs = IDLE_MS
            return MODEL_BYTES
        }

        private const val MODEL_BYTES = 300L * 1024 * 1024

        private val idle = Runnable {
            // (never while a session listens or works: memory work #56, D4)
            if (android.os.SystemClock.uptimeMillis() - lastUsed >= warmMs && !running && hold.idle()) work.execute { if (!running && hold.idle()) freeNow() }
            else idleLater()
        }

        private fun idleLater() { main.removeCallbacks(idle); main.postDelayed(idle, warmMs) }

        /**
         * the model goes now (the host went away: [Voice.stopAll]'s caller; Android running low; MEMORY WORK #56, D4, the user's yes
         * 2026-11-19: MemWatch's reliefs at Play, at the world's entry and at every loading screen, where the game takes its memory). NEVER
         * in a session's middle: while one listens or works its words out ([hold]) the free waits and runs when it ends. Loaded again by the
         * next push to talk (its press, [preload]), the sheet saying "Loading voice" while the words wait for it. The bytes it lets go now
         * (0 = none loaded, or it waits).
         */
        fun release(why: String = "asked"): Long {
            val bytes = loadedBytes()
            if (bytes == 0L) return 0L
            if (!hold.free()) { DiagLog.event("voice", "offline model free waits for the words ($why)"); return 0L }
            work.execute { if (!running && hold.idle()) { freeNow(); DiagLog.event("voice", "offline model let go ($why)") } }
            return bytes
        }

        /** the loaded model's bytes (its file's size: whisper.cpp reads the whole model), 0 = none loaded */
        fun loadedBytes(): Long = synchronized(lock) {
            if (handle == 0L) 0L else handleModel?.let { runCatching { VoiceModels.fileOf(it).length() }.getOrNull() }?.takeIf { it > 0 } ?: MODEL_BYTES
        }

        private fun hookTrim() {
            if (trimHooked) return
            trimHooked = true
            Voice.releaseOffline = { release("the host went") }
            MemWatch.source("voice") { loadedBytes() }
            MemWatch.onTrim("voice") { level -> if (VoiceHold.letsGo(level)) release(MemWatch.trimName(level)) else 0L }
        }
    }
}

/**
 * WHEN THE VOICE MODEL MAY GO (memory work #56, D4, the user's yes 2026-11-19: "free the Whisper model at loading screens and at world
 * entry ... never free it mid-transcription"). The ~300 MB model stayed loaded up to 5 minutes after a push to talk. A session (one
 * listen: the recording, then the words worked out) holds a free back: [free] while one is under way answers false and the free runs
 * at its [end] instead. Pure (the tests); any thread.
 */
class VoiceHold {
    private var busy = 0
    private var waiting = false
    /** session [s] starts (a newer one replaces an older) */
    @Synchronized fun begin(s: Int) { busy = s }
    /** session [s] is over; true = a free was asked meanwhile and should run now */
    @Synchronized fun end(s: Int): Boolean {
        if (busy != s) return false
        busy = 0
        if (!waiting) return false
        waiting = false
        return true
    }
    /** a free is asked: true = it may run now (no session); false = it waits for the session's end */
    @Synchronized fun free(): Boolean { if (busy == 0) return true; waiting = true; return false }
    /** no session under way */
    @Synchronized fun idle(): Boolean = busy == 0
    /** a free waits for a session's end */
    val pending: Boolean @Synchronized get() = waiting

    companion object {
        /** the levels that let the model go: running-low or worse, as every relief (MemWatch.RELIEF_LEVEL) */
        fun letsGo(level: Int): Boolean = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
    }
}
