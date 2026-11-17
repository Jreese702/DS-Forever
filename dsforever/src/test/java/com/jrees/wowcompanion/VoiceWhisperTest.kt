package com.jrees.wowcompanion

import com.jrees.wowcompanion.Voice.Act
import com.jrees.wowcompanion.Voice.Avail
import com.jrees.wowcompanion.Voice.Mode
import com.jrees.wowcompanion.Voice.Perm
import com.jrees.wowcompanion.Voice.Pick
import com.jrees.wowcompanion.Voice.Problem
import com.jrees.wowcompanion.Voice.Service
import com.jrees.wowcompanion.Voice.State
import com.jrees.wowcompanion.Voice.Vad
import com.jrees.wowcompanion.Voice.WhisperOn
import com.jrees.wowcompanion.VoiceModels.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OFFLINE VOICE (the user, 2026-11-16: "could we use something like https://handy.computer/?", then "base with optional downloads per
 * language"): the Thor lists no speech service but MacroDroid's and the Claude app's, so voice could not work there. The order now:
 * offline Whisper when its model is on disk > Android's on-device recognizer > a trusted Google / system service > offer the model's
 * download (a build with libdsfwhisper.so) > the old line. Plus: which model and language, Whisper's cleaning, the stop rule, the sheet's
 * "Working" and "Download voice" steps, and the JNI names the library binds.
 */
class VoiceWhisperTest {
    private val gsb = Service("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.serviceapi.GoogleRecognitionService", system = true)
    private val macro = Service("com.arlosoft.macrodroid", "com.arlosoft.macrodroid.voice.RecognitionService", system = false)
    private val noLib = WhisperOn(lib = false, en = true, multi = true)
    private val libOnly = WhisperOn(lib = true, en = false, multi = false)
    private val en = WhisperOn(lib = true, en = true, multi = false)
    private val multi = WhisperOn(lib = true, en = false, multi = true)
    private val both = WhisperOn(lib = true, en = true, multi = true)

    // ---------------------------------------------------------------- the order

    @Test fun whisperOnDiskComesFirst() {
        assertEquals(Pick.Whisper(Model.EN, "en"), Voice.pick(33, true, listOf(gsb), en))
        assertEquals(Pick.Whisper(Model.EN, "en"), Voice.recognizer(true, 33, true, listOf(gsb), en))
        assertEquals(Pick.Whisper(Model.EN, "en"), Voice.recognizer(false, 33, false, emptyList(), en))
        assertEquals(Avail.WHISPER, Voice.avail(Mode.DEVICE, 33, true, listOf(gsb), en))
        // Online too: offline voice first, whichever the setting
        assertEquals(Avail.WHISPER, Voice.avail(Mode.ONLINE, 33, true, listOf(gsb), en))
        assertEquals(Avail.NONE, Voice.avail(Mode.OFF, 33, true, listOf(gsb), en))
    }

    @Test fun thenAndroidsRecognizersAsBefore() {
        // no model on disk: the on-device one, then a trusted named one, as before
        assertEquals(Pick.OnDevice, Voice.pick(33, true, listOf(gsb), libOnly))
        assertEquals(Pick.Named(gsb), Voice.pick(30, true, listOf(gsb), libOnly))
        assertEquals(Avail.DEVICE, Voice.avail(Mode.DEVICE, 33, true, emptyList(), libOnly))
        assertEquals(Avail.ONLINE_ONLY, Voice.avail(Mode.DEVICE, 33, false, listOf(gsb), libOnly))
        assertEquals(Avail.ONLINE, Voice.avail(Mode.ONLINE, 33, false, listOf(gsb), libOnly))
    }

    @Test fun theThorOffersTheModelInsteadOfTheDeadEnd() {
        // the Thor: no on-device recognizer, only MacroDroid's (never used)
        assertEquals(Avail.NEED_MODEL, Voice.avail(Mode.DEVICE, 33, false, listOf(macro), libOnly))
        assertEquals(Avail.NEED_MODEL, Voice.avail(Mode.ONLINE, 33, false, listOf(macro), libOnly))
        assertEquals(Pick.None, Voice.pick(33, false, listOf(macro), libOnly))
    }

    @Test fun noLibraryKeepsTheOldLine() {
        // a build without libdsfwhisper.so: a model file on disk changes nothing; nothing to listen = NO_SERVICE, as before
        assertEquals(Avail.NONE, Voice.avail(Mode.DEVICE, 33, false, listOf(macro), noLib))
        assertEquals(Avail.NONE, Voice.avail(Mode.DEVICE, 33, false, listOf(macro), null))
        assertEquals(Pick.OnDevice, Voice.pick(33, true, emptyList(), noLib))
        val s = sheet()
        s.open(Perm.GRANTED, Avail.NONE)
        assertEquals(Problem.NO_SERVICE, s.problem)
    }

    // ---------------------------------------------------------------- the model and its language

    @Test fun languageChoice() {
        // English app: the English model when there, else the multilingual one in English
        assertEquals(Model.EN to "en", Voice.whisperModel("en", both))
        assertEquals(Model.MULTI to "en", Voice.whisperModel("en", multi))
        // Spanish / French app: the multilingual model in that language when there, else the English one
        assertEquals(Model.MULTI to "es", Voice.whisperModel("es", both))
        assertEquals(Model.MULTI to "fr", Voice.whisperModel("fr", multi))
        assertEquals(Model.EN to "en", Voice.whisperModel("es", en))
        assertEquals(Model.MULTI to "fr", Voice.whisperModel("fr-FR", multi))
        assertNull(Voice.whisperModel("en", libOnly))
        assertNull(Voice.whisperModel("es", noLib))
        // the sheet offers the file the app's language wants
        assertEquals(Model.EN, Voice.wantedModel("en"))
        assertEquals(Model.MULTI, Voice.wantedModel("es"))
        assertEquals(Model.MULTI, Voice.wantedModel("fr"))
    }

    @Test fun whisperCleanDropsItsTags() {
        assertEquals("", Voice.whisperClean(" [BLANK_AUDIO]", 255))
        assertEquals("hello there", Voice.whisperClean(" Hello there [Music]".lowercase(), 255))
        assertEquals("LFG for deadmines", Voice.whisperClean(" (coughs) LFG for deadmines *sigh*", 255))
        assertEquals("Que tal", Voice.whisperClean(" ¿Qué tal?".removeSuffix("?"), 255))
        assertEquals("abc", Voice.whisperClean("abcdef", 3))
    }

    @Test fun levelsOnTheSheetsScale() {
        assertEquals(-2f, Voice.rmsDb(-90f), 0.001f)
        assertEquals(-2f, Voice.rmsDb(-60f), 0.001f)
        assertEquals(10f, Voice.rmsDb(0f), 0.001f)
        assertTrue(Voice.rmsDb(-20f) > Voice.FLAT_DB)
        assertTrue(Voice.rmsDb(-58f) < Voice.FLAT_DB)
        assertEquals(-90f, Voice.dbfs(ShortArray(480), 0, 480), 0.001f)
        val loud = ShortArray(480) { if (it % 2 == 0) 16384 else -16384 }
        assertEquals(-6.02f, Voice.dbfs(loud, 0, 480), 0.05f)
    }

    // ---------------------------------------------------------------- the stop rule

    private fun run(v: Vad, db: Float, ms: Int): Vad.Step {
        var last = Vad.Step.GO
        repeat(ms / Voice.FRAME_MS) { last = v.frame(db); if (last != Vad.Step.GO) return last }
        return last
    }

    @Test fun vadStopsAfterTheQuietThatFollowsTheWords() {
        val v = Vad()
        assertEquals(Vad.Step.GO, run(v, -60f, 300))           // the room's floor
        assertEquals(-60f, v.floor, 0.01f)
        assertEquals(Vad.Step.GO, run(v, -25f, 900))            // words
        assertTrue(v.speech)
        assertEquals(Vad.Step.GO, run(v, -60f, 1170))           // a pause shorter than 1.2 s goes on
        assertEquals(Vad.Step.GO, run(v, -25f, 300))            // more words: the quiet starts again
        var n = 0
        while (v.frame(-60f) == Vad.Step.GO) n++
        assertEquals(Voice.SILENCE_MS / Voice.FRAME_MS - 1, n)  // 1.2 s of quiet, then stop
    }

    @Test fun vadStopsAt15SecondsInAnyCase() {
        val v = Vad()
        run(v, -60f, 300)
        var frames = Voice.FLOOR_MS / Voice.FRAME_MS
        var step = Vad.Step.GO
        while (step == Vad.Step.GO) { step = v.frame(-20f); frames++ }
        assertEquals(Vad.Step.STOP, step)
        assertEquals(Voice.MAX_MS, frames * Voice.FRAME_MS)
    }

    @Test fun vadNothingSaidIn5Seconds() {
        val v = Vad()
        var frames = 0
        var step = Vad.Step.GO
        while (step == Vad.Step.GO) { step = v.frame(-62f); frames++ }
        assertEquals(Vad.Step.NO_SPEECH, step)
        assertEquals((Voice.NO_SPEECH_MS + Voice.FRAME_MS - 1) / Voice.FRAME_MS, frames)      // the first frame at 5 s or past it
        assertFalse(v.speech)
        // a dead-silent floor: a breath at -70 dB is not speech
        val q = Vad()
        run(q, -90f, 300)
        assertEquals(Vad.Step.GO, run(q, -70f, 600))
        assertFalse(q.speech)
    }

    // ---------------------------------------------------------------- the sheet

    private var t = 1_000L
    private fun sheet() = Voice.Sheet("", append = true, max = 255, mode = Mode.DEVICE) { t }

    @Test fun offlineSheetGoesListeningWorkingResult() {
        val s = sheet()
        assertEquals(listOf<Act>(Act.Start(false)), s.open(Perm.GRANTED, Avail.WHISPER))
        assertTrue(s.offline)
        assertEquals(State.LISTENING, s.state)
        // the engine's quiet rule stopped the recording
        s.working()
        assertEquals(State.WORKING, s.state)
        // A in Working does nothing; the words come
        assertEquals(listOf<Act>(Act.Glow), s.press("PAD1", fight = false))
        t += 3000
        s.final("hello world")
        assertEquals(State.RESULT, s.state)
        assertEquals("hello world", s.boxText())
        // never sent by itself: A on the finish button only after ACCEPT_MS
        assertEquals(listOf<Act>(Act.Glow), s.press("PAD1", fight = false))
        t += Voice.ACCEPT_MS
        assertEquals(listOf<Act>(Act.Finish("hello world")), s.press("PAD1", fight = false))
    }

    @Test fun aWhileListeningOfflineGoesToWorkingNotStopping() {
        val s = sheet()
        s.open(Perm.GRANTED, Avail.WHISPER)
        assertEquals(listOf<Act>(Act.Stop), s.press("PAD1", fight = false))
        assertEquals(State.WORKING, s.state)
        // the 2.5 s stop wait is Android's: nothing happens at 3 s
        t += Voice.STOP_WAIT_MS + 500
        assertEquals(emptyList<Act>(), s.tick())
        assertEquals(State.WORKING, s.state)
        // 20 s with no words: "Voice busy"
        t += Voice.WORK_WAIT_MS
        val acts = s.tick()
        assertEquals(Act.Abort, acts.first())
        assertEquals(State.PROBLEM, s.state)
        assertEquals(Problem.BUSY, s.problem)
    }

    @Test fun bWhileWorkingAborts() {
        val s = sheet()
        s.open(Perm.GRANTED, Avail.WHISPER)
        s.working()
        val acts = s.press("PAD2", fight = false)
        assertEquals(Act.Abort, acts.first())
        assertEquals(Act.Close, acts.last())
        assertTrue(s.closed)
        assertEquals(emptyList<Act>(), s.final("late words"))
    }

    @Test fun offlineErrorsKeepTheOneLineRule() {
        val s = sheet()
        s.open(Perm.GRANTED, Avail.WHISPER)
        t += 5000
        s.error(Voice.E_SPEECH_TIMEOUT)
        assertEquals(Problem.NOT_HEARD, s.problem)
        s.press("PAD1", fight = false)                         // Say again
        s.working(); t += 2000
        s.error(Voice.E_SERVER)
        assertEquals(Problem.BUSY, s.problem)
        s.press("PAD1", fight = false)
        s.error(Voice.E_AUDIO)
        assertEquals(Problem.NO_SOUND, s.problem)
        // an Android sheet never goes to Working
        val a = sheet()
        a.open(Perm.GRANTED, Avail.DEVICE)
        a.working()
        assertEquals(State.LISTENING, a.state)
        a.press("PAD1", fight = false)
        assertEquals(State.STOPPING, a.state)
    }

    @Test fun needModelOffersTheDownloadThenListens() {
        val s = sheet()
        assertEquals(listOf<Act>(Act.Glow), s.open(Perm.GRANTED, Avail.NEED_MODEL))
        assertEquals(State.PROBLEM, s.state)
        assertEquals(Problem.NEED_MODEL, s.problem)
        assertEquals("Download voice", s.problem?.title)
        assertEquals(listOf<Act>(Act.GetModel), s.press("PAD1", fight = false))
        // downloading: A waits (one download), X has no Say again here
        s.downloading = true
        assertEquals(listOf<Act>(Act.Glow), s.press("PAD1", fight = false))
        assertEquals(listOf<Act>(Act.Glow), s.press("PAD3", fight = false))
        // the model came: listening at once
        assertEquals(listOf<Act>(Act.Start(false)), s.modelReady(Perm.GRANTED, Avail.WHISPER))
        assertEquals(State.LISTENING, s.state)
        assertTrue(s.offline)
        assertFalse(s.downloading)
        // ... but never on a sheet that has closed, or one that is not offering it
        val c = sheet()
        c.open(Perm.GRANTED, Avail.NEED_MODEL)
        c.press("PAD2", fight = false)
        assertEquals(emptyList<Act>(), c.modelReady(Perm.GRANTED, Avail.WHISPER))
        val l = sheet()
        l.open(Perm.GRANTED, Avail.DEVICE)
        assertEquals(emptyList<Act>(), l.modelReady(Perm.GRANTED, Avail.WHISPER))
    }

    @Test fun needModelYIsTheKeyboard() {
        val s = Voice.Sheet("hi", append = true, max = 255, mode = Mode.DEVICE) { t }
        s.open(Perm.GRANTED, Avail.NEED_MODEL)
        assertEquals(listOf<Act>(Act.Edit("hi")), s.press("PAD4", fight = false))
    }

    // ---------------------------------------------------------------- the library's names

    private fun repo(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "companion/tools/whisper_native").isDirectory) d = d.parentFile
        return d ?: error("no repository root above ${File("").absolutePath}")
    }

    /** the JNI names whisper_jni.cpp exports are WhisperNative's natives, with the same arities */
    @Test fun jniNamesMatchTheKotlinNatives() {
        val root = repo()
        val cpp = File(root, "companion/tools/whisper_native/jni/whisper_jni.cpp").readText()
        val kt = File(root, "companion/app/src/main/java/com/jrees/wowcompanion/WhisperNative.kt").readText()
        val exported = Regex("""JNIEXPORT\s+\w+\s+JNICALL\s+Java_com_jrees_wowcompanion_WhisperNative_(\w+)\s*\(([^)]*)\)""")
            .findAll(cpp).associate { m -> m.groupValues[1] to (m.groupValues[2].split(',').size - 2) }
        val natives = Regex("""@JvmStatic\s+external\s+fun\s+(\w+)\s*\(([^)]*)\)""").findAll(kt)
            .associate { m -> m.groupValues[1] to m.groupValues[2].split(',').count { it.isNotBlank() } }
        assertEquals(setOf("version", "load", "transcribe", "abort", "free"), natives.keys)
        assertEquals(natives, exported)
        // the R8 keep rule and the APK check name the class
        assertTrue(File(root, "app/proguard-rules.pro").readText().contains("-keep class com.jrees.wowcompanion.WhisperNative { native <methods>; }"))
        val check = File(root, "companion/tools/interiors_device/apk_jni_check.py").readText()
        for (n in natives.keys) assertTrue(n, check.contains("(\"Lcom/jrees/wowcompanion/WhisperNative;\", \"$n\","))
        assertTrue(check.contains("lib/arm64-v8a/libdsfwhisper.so"))
    }

    /** whisper.cpp is vendored at its pinned release with its MIT licence, and its licence is in the app's notices */
    @Test fun vendoredAtThePinWithItsLicence() {
        val root = repo()
        val up = File(root, "companion/tools/whisper_native/whisper.cpp/UPSTREAM").readText()
        assertTrue(up.contains("v1.9.5") && up.contains("d1be6fde11ac6e0407606b4e42fe72d34add8037"))
        val lic = File(root, "companion/tools/whisper_native/whisper.cpp/LICENSE").readText()
        assertTrue(lic.startsWith("MIT License") && lic.contains("The ggml authors"))
        assertEquals(lic.replace("\r\n", "\n"), File(root, "licenses/whisper.cpp-MIT.txt").readText().replace("\r\n", "\n"))
        assertTrue(File(root, "THIRD_PARTY_NOTICES").readText().contains("libdsfwhisper.so"))
        assertTrue(File(root, "app/src/main/assets/notices/THIRD_PARTY_NOTICES.txt").readText().contains("libdsfwhisper.so"))
        // never an i8mm / SVE build: the Thor Lite (Cortex-A77, ARMv8.2) has neither
        val cm = File(root, "companion/tools/whisper_native/CMakeLists.txt").readText()
        assertTrue(cm.contains("\"armv8.2-a+dotprod+fp16\""))
        assertFalse(cm.contains("i8mm") && !cm.contains("NEVER i8mm"))
    }
}
