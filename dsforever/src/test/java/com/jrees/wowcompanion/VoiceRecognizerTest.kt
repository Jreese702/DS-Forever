package com.jrees.wowcompanion

import com.jrees.wowcompanion.Voice.Act
import com.jrees.wowcompanion.Voice.Avail
import com.jrees.wowcompanion.Voice.Mode
import com.jrees.wowcompanion.Voice.Perm
import com.jrees.wowcompanion.Voice.Pick
import com.jrees.wowcompanion.Voice.Problem
import com.jrees.wowcompanion.Voice.Service
import com.jrees.wowcompanion.Voice.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WHICH RECOGNIZER (the user, 2026-11-16: "the chat voice is not working it just flashs and instantly stops and says something about
 * macro droid? which absolutly should not be required"): the device's DEFAULT recognizer was MacroDroid's. The order now: the on-device
 * recognizer (Android 12+), else a Google / system speech service by name, else no voice with one short line; a third-party app never.
 */
class VoiceRecognizerTest {
    private val macro = Service("com.arlosoft.macrodroid", "com.arlosoft.macrodroid.voice.RecognitionService", system = false)
    private val gsb = Service("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.serviceapi.GoogleRecognitionService", system = true)
    private val tts = Service("com.google.android.tts", "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService", system = true)
    private val oem = Service("com.oem.speech", "com.oem.speech.Rec", system = true)

    @Test fun onDeviceComesFirstOnAndroid12AndUp() {
        assertEquals(Pick.OnDevice, Voice.pick(31, true, listOf(macro, gsb)))
        assertEquals(Pick.OnDevice, Voice.pick(33, true, emptyList()))
        // Android 10 / 11 (minSdk 29) have no on-device recognizer, whatever a flag says
        assertEquals(Pick.Named(gsb), Voice.pick(30, true, listOf(gsb)))
        assertEquals(Pick.None, Voice.pick(29, true, emptyList()))
    }

    @Test fun aThirdPartyRecognizerIsNeverUsed() {
        assertEquals(Pick.None, Voice.pick(33, false, listOf(macro)))
        assertNull(Voice.trusted(listOf(macro)))
        assertEquals(Pick.None, Voice.recognizer(true, 33, false, listOf(macro)))
        assertEquals(Avail.NONE, Voice.avail(Mode.DEVICE, 33, false, listOf(macro)))
        assertEquals(Avail.NONE, Voice.avail(Mode.ONLINE, 33, false, listOf(macro)))
    }

    @Test fun aKnownGoogleServiceBeatsTheListOrderAndOtherSystemOnes() {
        assertEquals(Pick.Named(gsb), Voice.pick(33, false, listOf(macro, oem, tts, gsb)))
        assertEquals(tts, Voice.trusted(listOf(macro, oem, tts)))
        assertEquals(oem, Voice.trusted(listOf(macro, oem)))
        // a Google package that is not flagged system (a sideloaded update) still counts: the package is the trust
        val gNotSys = gsb.copy(system = false)
        assertEquals(gNotSys, Voice.trusted(listOf(macro, gNotSys)))
    }

    @Test fun thisDeviceModeOffersTheNamedServiceOnlyAsAPick() {
        assertEquals(Avail.DEVICE, Voice.avail(Mode.DEVICE, 33, true, listOf(gsb)))
        assertEquals(Avail.ONLINE_ONLY, Voice.avail(Mode.DEVICE, 33, false, listOf(macro, gsb)))
        // listening in This device mode never binds a named service (nothing leaves the Thor until Online is picked)
        assertEquals(Pick.None, Voice.recognizer(false, 33, false, listOf(gsb)))
        assertEquals(Pick.OnDevice, Voice.recognizer(false, 33, true, listOf(gsb)))
    }

    @Test fun onlineModeBindsTheNamedServiceElseOnDevice() {
        assertEquals(Avail.ONLINE, Voice.avail(Mode.ONLINE, 33, true, listOf(macro, gsb)))
        assertEquals(Pick.Named(gsb), Voice.recognizer(true, 33, true, listOf(macro, gsb)))
        assertEquals(Avail.DEVICE, Voice.avail(Mode.ONLINE, 33, true, listOf(macro)))
        assertEquals(Pick.OnDevice, Voice.recognizer(true, 33, true, listOf(macro)))
        assertEquals(Avail.NONE, Voice.avail(Mode.OFF, 33, true, listOf(gsb)))
    }

    @Test fun noRecognizerSaysOneLineAndLeavesTheBoxUsable() {
        val s = Voice.Sheet("hi", true, 255, Mode.DEVICE) { 0L }
        assertEquals(listOf<Act>(Act.Glow), s.open(Perm.GRANTED, Avail.NONE))
        assertEquals(State.PROBLEM, s.state)
        assertEquals(Problem.NO_SERVICE, s.problem)
        assertEquals("Voice typing needs Android speech services", s.problem!!.title)
        // A and X do not try (and fail) again; Y = the keyboard with the box's text; B = back to the box
        assertEquals(listOf<Act>(Act.Glow), s.press("PAD1", false))
        assertEquals(listOf<Act>(Act.Glow), s.press("PAD3", false))
        assertFalse(s.closed)
        assertEquals(listOf<Act>(Act.Edit("hi")), s.press("PAD4", false))
        val b = Voice.Sheet("hi", true, 255, Mode.DEVICE) { 0L }
        b.open(Perm.GRANTED, Avail.NONE)
        assertEquals(listOf<Act>(Act.Close), b.press("PAD2", false))
    }

    @Test fun everyRecognizerErrorShowsALineNeverASilentClose() {
        for (code in 1..15) for (mode in listOf(Mode.DEVICE, Mode.ONLINE)) {
            var now = 10_000L
            val s = Voice.Sheet("hi", true, 255, mode) { now }
            s.open(Perm.GRANTED, if (mode == Mode.ONLINE) Avail.ONLINE else Avail.DEVICE)
            now += 2000
            var acts = s.error(code)
            if (acts.any { it is Act.Start }) { now += 2000; acts = s.error(code) }   // the one quiet retry
            assertFalse("code $code $mode closed", s.closed)
            assertFalse("code $code $mode closed", acts.any { it == Act.Close })
            assertTrue("code $code $mode: ${s.state}", s.state == State.PROBLEM || s.state == State.OFFER_ONLINE)
            if (s.state == State.PROBLEM) assertTrue(s.problem != null)
        }
    }

    @Test fun theEngineNeverBindsTheDefaultRecognizer() {
        val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion/VoiceEngine.kt",
            "companion/app/src/main/java/com/jrees/wowcompanion/VoiceEngine.kt").map { File(it) }.first { it.exists() }.readText()
        assertFalse("createSpeechRecognizer(app) binds the device's default (MacroDroid on the Thor)",
            Regex("""createSpeechRecognizer\(\s*app\s*\)""").containsMatchIn(src))
        assertFalse(src.contains("\"voice_recognition_service\""))
    }

    @Test fun theNewLineIsTranslated() {
        for (lang in listOf("es", "fr")) {
            val f = listOf("../companion/app/src/main/assets/companion/i18n/$lang.json", "companion/app/src/main/assets/companion/i18n/$lang.json")
                .map { File(it) }.first { it.exists() }
            assertTrue(lang, f.readText().contains("\"Voice typing needs Android speech services\": \""))
        }
    }
}
