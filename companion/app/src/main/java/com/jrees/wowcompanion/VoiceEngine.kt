package com.jrees.wowcompanion

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * ANDROID'S SPEECH RECOGNIZER for the voice sheet ([Voice.Sheet]; the user's pick 2026-11-16: "On the Thor only by default. Online is used
 * only after you pick it once"). On the main thread with the APP's context; ONE recognizer per listening, destroyed on every way out
 * (its result, its error, [cancel]); a callback that arrives after a cancel belongs to an old session and is dropped.
 *  - This device = [SpeechRecognizer.createOnDeviceSpeechRecognizer] only (nothing leaves the Thor; Android 12+).
 *  - Online = a Google / system speech service BY NAME ([Voice.trusted] over [services]), with EXTRA_PREFER_OFFLINE as a hint (only that
 *    service can keep it, so the sheet never says "on this device" for it); the sheet names that service's app ([onlineName]). NEVER the
 *    device's default recognizer (SpeechRecognizer.createSpeechRecognizer(context) binds Settings.Secure voice_recognition_service, which
 *    on the user's Thor is MacroDroid's: "it just flashs and instantly stops", 2026-11-16). The manifest's <queries> lets Android list them.
 * Extras: free-form, the app's language ([Voice.language]), partial results, one result, no masking; the name hints ([Voice.hints]) on
 * This device only. NO audio focus is asked and nothing touches the game's sound (the user's pick: "Leave it alone for now").
 * Nothing heard is ever logged; the sheet's DiagLog line has only the mode, the state, the time and a character count.
 */
class VoiceEngine(context: Context) {
    private val app = context.applicationContext
    private val ui = Handler(Looper.getMainLooper())

    /** what the recognizer says, on the main thread, for the current session only */
    interface Sink {
        fun rms(db: Float)
        fun partial(text: String)
        fun final(text: String)
        fun error(code: Int)
        /** offline voice only ([WhisperEngine]): the recording stopped, the words are being worked out */
        fun working() {}
        /** offline voice only (memory work #56, D4): the words wait for the voice model to load again ("Loading voice") */
        fun loading() {}
    }

    private var rec: SpeechRecognizer? = null
    private var session = 0

    val listening: Boolean get() = rec != null

    /** starts listening ([online]: the trusted named service, else this device's; [Voice.recognizer]); false when none could be made */
    fun start(online: Boolean, lang: String, hints: List<String>, sink: Sink): Boolean {
        cancel()
        val s = ++session
        val pick = Voice.recognizer(online, Build.VERSION.SDK_INT, onDeviceFlag(app), if (online) services(app) else emptyList())
        val r = runCatching {
            when (pick) {
                Voice.Pick.OnDevice -> if (Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(app) else null
                is Voice.Pick.Named -> SpeechRecognizer.createSpeechRecognizer(app, ComponentName(pick.service.pkg, pick.service.cls))
                // offline voice is WhisperEngine's (never picked here: no model is given to this pick)
                Voice.Pick.None, is Voice.Pick.Whisper -> null
            }
        }.getOrNull() ?: return false
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            private fun live() = s == session
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { if (live()) sink.rms(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { if (live()) { end(s); sink.error(error) } }
            override fun onResults(results: Bundle?) { if (live()) { end(s); sink.final(best(results)) } }
            override fun onPartialResults(partialResults: Bundle?) { if (live()) best(partialResults).takeIf { it.isNotEmpty() }?.let { sink.partial(it) } }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = intent(online, lang, hints)
        return runCatching { r.startListening(i); true }.getOrElse { end(s); false }
    }

    /** the recognizer finishes what it heard (its results follow) */
    fun stop() { runCatching { rec?.stopListening() } }

    /** the recognizer goes now; nothing it says after this is used */
    fun cancel() {
        session++
        val r = rec ?: return
        rec = null
        runCatching { r.cancel() }
        runCatching { r.destroy() }
    }

    /** after a result or an error: that session's recognizer is destroyed (posted: never inside its own callback) */
    private fun end(s: Int) {
        val r = rec ?: return
        rec = null
        session = s + 1
        ui.post { runCatching { r.destroy() } }
    }

    /** "Voice pack needed" > Get: Android's own download of the on-device language pack (Android 13+) */
    fun getPack(lang: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return runCatching {
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
            try { r.triggerModelDownload(intent(false, lang, emptyList())) } finally { ui.post { runCatching { r.destroy() } } }
            true
        }.getOrDefault(false)
    }

    private fun intent(online: Boolean, lang: String, hints: List<String>) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        if (online) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        if (Build.VERSION.SDK_INT >= 33) {
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            if (!online && hints.isNotEmpty()) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(hints.take(Voice.MAX_HINTS)))
        }
    }

    private fun best(b: Bundle?): String = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    companion object {
        /** the device has a microphone (the X glyph's rule) */
        fun hasMic(ctx: Context): Boolean = runCatching { ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) }.getOrDefault(false)

        /** Android says an on-device recognizer is there (Android 12+ only: false below) */
        private fun onDeviceFlag(ctx: Context): Boolean =
            Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrDefault(false)

        /** every speech service Android lists, with whether it came with the device ([Voice.trusted] picks among them) */
        fun services(ctx: Context): List<Voice.Service> = runCatching {
            val sys = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).mapNotNull { ri ->
                val si = ri.serviceInfo ?: return@mapNotNull null
                Voice.Service(si.packageName, si.name, ((si.applicationInfo?.flags ?: 0) and sys) != 0)
            }
        }.getOrDefault(emptyList())

        /** the online recognizer's app, by its own name (the privacy line): the trusted service's; null = none */
        fun onlineName(ctx: Context): String? = runCatching {
            val pm = ctx.packageManager
            Voice.trusted(services(ctx))?.let { pm.getApplicationLabel(pm.getApplicationInfo(it.pkg, 0)).toString() }
        }.getOrNull()

        /** which recognizer the sheet can use with the setting [mode] ([Voice.avail]; offline voice first when its model is on disk) */
        fun avail(ctx: Context, mode: Voice.Mode): Voice.Avail {
            VoiceModels.init(ctx)
            return Voice.avail(mode, Build.VERSION.SDK_INT, onDeviceFlag(ctx), services(ctx), WhisperEngine.on(), Tr.lang(), Settings.voiceLanguage)
        }
    }
}

/**
 * THE MICROPHONE PERMISSION (the user's pick: "Android's own 'Allow microphone?' box, shown once on the bottom screen the first time you
 * press X (never in a fight)"). Asked only by VoiceAskActivity on the player's Allow; never granted any other way. Remembered here: the
 * player said no (Android may ask again: "Microphone off") and Android stopped asking (the sheet shows the Settings path).
 */
object VoiceMic {
    private const val PREFS = "voice"

    fun granted(ctx: Context): Boolean =
        runCatching { ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun perm(ctx: Context): Voice.Perm {
        if (granted(ctx)) return Voice.Perm.GRANTED
        val p = runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull() ?: return Voice.Perm.ASKABLE
        return when {
            p.getBoolean("blocked", false) -> Voice.Perm.BLOCKED
            p.getBoolean("denied", false) -> Voice.Perm.DENIED
            else -> Voice.Perm.ASKABLE
        }
    }

    /** Android's answer: [ok] granted; [askAgain] = Android would still ask (false after a no: it stopped asking) */
    fun answered(ctx: Context, ok: Boolean, askAgain: Boolean) {
        val p = runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull() ?: return
        p.edit().putBoolean("denied", !ok).putBoolean("blocked", !ok && !askAgain).apply()
        DiagLog.event("voice", "mic permission ${if (ok) "granted" else if (askAgain) "refused" else "refused, no more asks"}")
    }

    /** the question's activity closed (whatever the answer): the host's window comes back (openOnBottom's hook) */
    @Volatile var onClosed: (() -> Unit)? = null
    /** ... and the sheet that asked looks again (once) */
    @Volatile var onAnswer: (() -> Unit)? = null
}
