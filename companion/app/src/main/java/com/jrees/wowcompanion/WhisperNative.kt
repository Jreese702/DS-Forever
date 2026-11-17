package com.jrees.wowcompanion

import android.util.Log

/**
 * OFFLINE VOICE TYPING'S ENGINE (the user, 2026-11-16: "could we use something like https://handy.computer/?", then "base with optional
 * downloads per language"): whisper.cpp v1.9.5 (MIT, The ggml authors) over JNI (companion/tools/whisper_native/jni/whisper_jni.cpp,
 * built by companion/tools/whisper_native/build_android.sh into libdsfwhisper.so). The model files are downloaded on the player's press
 * ([VoiceModels], Settings > Add-ons), never in the APK. Kotlin calls in; the native code never calls back.
 *
 * A build without libdsfwhisper.so (it is not in git) has no offline voice: [available] is false and voice typing is as before.
 * The JNI names (Java_com_jrees_wowcompanion_WhisperNative_*) are checked in the APK by apk_jni_check.py; R8 keeps them (proguard-rules).
 */
object WhisperNative {
    val available: Boolean = try {
        System.loadLibrary("dsfwhisper"); true
    } catch (t: Throwable) {
        runCatching { Log.w("WCVOICE", "no libdsfwhisper.so in this build: ${t.message}") }; false
    }

    /** whisper.cpp's version and the CPU features it was built for (DiagLog; never anything heard) */
    @JvmStatic external fun version(): String
    /** loads a ggml model file; 0 = failed (not a model, damaged, no memory) */
    @JvmStatic external fun load(path: String): Long
    /** [count] samples of 16 kHz mono PCM16 in [lang] ("en", "es", "fr" ...), [threads] CPU threads, [prompt] the names to expect (or
     *  null): the words, or null on an error or an [abort] */
    @JvmStatic external fun transcribe(handle: Long, pcm: ShortArray, count: Int, lang: String, threads: Int, prompt: String?): String?
    /** the running [transcribe] on [handle] stops as soon as it can (it returns null) */
    @JvmStatic external fun abort(handle: Long)
    @JvmStatic external fun free(handle: Long)
}
