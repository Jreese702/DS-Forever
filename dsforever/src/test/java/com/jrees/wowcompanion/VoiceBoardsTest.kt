package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WHICH BOXES LISTEN, read from the sources (the user's pick 2026-11-16, D6a: "Every box that takes words: chat, quick message, Bags search,
 * Professions search (which gets controller A and X), auction search, mail subject and letter, radial names, and the problem report. Not
 * numbers, mail 'To' names, macros (until macro builder v2), or sign-in boxes"). Every ChatKeyboard construction in the app's sources with
 * its voice flag; no sign-in board ever listens; Interact's and the add-on's words fields are the same three; the manifest asks for the
 * microphone only as a runtime permission, can see the recognizers, and the game's Wine microphone stays pinned off.
 */
class VoiceBoardsTest {
    private fun dir(vararg c: String): File = c.map { File(it) }.first { it.isDirectory }
    private val app = dir("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
    private val fork = dir("src/main/java", "dsforever/src/main/java")
    private val root = dir("..", ".").let { if (File(it, "settings.gradle.kts").isFile) it else File(".") }

    /** file -> the argument lists of its ChatKeyboard( constructions */
    private fun constructions(): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        val re = Regex("""ChatKeyboard\(([^()]*(?:\([^()]*\))?[^()]*)\)""")
        for (d in listOf(app, fork)) d.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            if (f.name == "ChatKeyboard.kt") return@forEach
            for (m in re.findAll(f.readText())) out.getOrPut(f.name) { ArrayList() }.add(m.groupValues[1])
        }
        return out
    }

    @Test fun everyBoardAndItsVoiceFlag() {
        val c = constructions()
        val want = mapOf(
            "ChatView.kt" to true, "BagsView.kt" to true, "CompanionHost.kt" to true, "PadTabPage.kt" to true, "ReportPage.kt" to true,
            "ProfessionsView.kt" to true,
            "MacrosView.kt" to false, "TypeTool.kt" to false, "PreviewStage.kt" to false, "StreamSignIn.kt" to false,
        )
        assertEquals("every construction is accounted for: ${c.keys}", want.keys, c.keys)
        for ((file, voice) in want) {
            val args = c.getValue(file)
            assertEquals("$file: one board", 1, args.size)
            assertEquals("$file: voice = $voice (${args[0]})", voice, args[0].contains("voice = true"))
        }
    }

    @Test fun signInBoardsAndTheTypeToolNeverListen() {
        val c = constructions()
        for ((file, list) in c) for (a in list) if (a.contains("signIn = true")) assertFalse("$file: a sign-in board with voice", a.contains("voice = true"))
        assertTrue(c.getValue("StreamSignIn.kt").single().contains("signIn = true"))
        assertFalse(c.getValue("TypeTool.kt").single().contains("voice"))
        // the keyboard itself: signIn forces voice off, whatever the caller passes
        val kb = File(app, "ChatKeyboard.kt").readText()
        assertTrue(kb.contains("val voiceOn: Boolean = voice && !signIn"))
    }

    @Test fun theWordsFieldsAreTheSameThreeInTheAppAndTheAddOn() {
        val iv = File(app, "InteractView.kt").readText()
        assertTrue(iv.contains("""val VOICE_FIELDS = setOf("mailsubject", "mailbody", "ahtext")"""))
        val lua = File(app, "../../../../assets/companion/addons/WowCompanionInteract/Interact.lua").readText()
        assertTrue(lua.contains("""N.VOICE_FIELD = { ["mailedit subject"] = true, ["mailedit body"] = true, ["ahedit text"] = true }"""))
        // never To, the money or the level range
        for (no in listOf("\"mailto\"", "\"mailgold\"", "\"ahmin\"", "\"ahmax\"")) assertFalse(no, Regex("VOICE_FIELDS = setOf\\([^)]*$no").containsMatchIn(iv))
    }

    @Test fun theManifestAsksForTheMicrophoneAtRuntimeOnly() {
        val m = File(root, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("""<uses-permission android:name="android.permission.RECORD_AUDIO" />"""))
        assertFalse("RECORD_AUDIO must not be removed (tools:node=\"remove\" strips it from every manifest)",
            Regex("""RECORD_AUDIO"\s+tools:node="remove"""").containsMatchIn(m))
        assertTrue(Regex("""MODIFY_AUDIO_SETTINGS"\s+tools:node="remove"""").containsMatchIn(m))
        assertTrue(Regex("""<queries>\s*<intent>\s*<action android:name="android.speech.RecognitionService" />""").containsMatchIn(m))
        val fm = File(root, "dsforever/src/main/AndroidManifest.xml").readText()
        assertTrue(fm.contains("""android:name="com.jrees.wowcompanion.VoiceAskActivity""""))
        val ask = Regex("""<activity\s+android:name="com.jrees.wowcompanion.VoiceAskActivity"[^>]*>""").find(fm)!!.value
        assertTrue(ask, ask.contains("""android:exported="false""""))
        // the game's own Wine microphone: pinned off
        val xs = File(root, "app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt").readText()
        assertTrue(xs.contains("    val micEnabled = false\n"))
    }

    @Test fun theVoiceWordsAreInSpanishAndFrench() {
        val words = listOf("Voice typing", "X in a text box listens", "This device", "Online", "Off", "Listening", "Didn't hear you", "Say again",
            "Edit", "Voice needs the microphone", "Allow", "Not now", "After combat", "Microphone off", "Use online voice", "Voice pack needed",
            "Get", "Offline", "Voice busy", "No sound from the mic", "Stop", "Cancel", ChatKeyboard.PATH) +
            Voice.Problem.values().map { it.title } + Voice.Mode.values().map { it.label }
        val i18n = File(app, "../../../../assets/companion/i18n")
        for (l in listOf("es", "fr")) {
            val b = File(i18n, "$l.json").readText()
            val plain = org.json.JSONObject(b).getJSONObject("plain")
            for (w in words) assertTrue("$l: $w", plain.has(w))
            assertTrue("$l: CRLF throughout", b.split("\n").dropLast(1).all { it.endsWith("\r") })
        }
    }

    @Test fun nothingHeardIsEverLogged() {
        // every DiagLog line the voice code writes is the sheet's logLine (mode, state, time, a count) or a fixed sentence
        for (f in listOf("ChatKeyboard.kt", "VoiceEngine.kt", "VoiceAskActivity.kt", "SettingsView.kt")) {
            val t = File(app, f).readText()
            for (m in Regex("""DiagLog\.event\("voice",\s*([^\n]*)""").findAll(t)) {
                val arg = m.groupValues[1]
                assertFalse("$f: $arg", arg.contains("heard") || arg.contains("text") || arg.contains("hint") || arg.contains("before"))
            }
        }
    }
}
