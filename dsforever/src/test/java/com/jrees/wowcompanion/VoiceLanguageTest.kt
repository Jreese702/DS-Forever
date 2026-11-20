package com.jrees.wowcompanion

import com.jrees.wowcompanion.Voice.Avail
import com.jrees.wowcompanion.Voice.Mode
import com.jrees.wowcompanion.Voice.WhisperOn
import com.jrees.wowcompanion.VoiceModels.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE #477 ("Voice typing: let me pick the speech language (Russian), independent of the app language"): the Voice language pick
 * ([VoiceLanguage]: Same as the app / Auto-detect / Whisper's languages), the model and language Whisper is given for it, the
 * multilingual model offered (never downloaded by itself) when only English is on disk, and Cyrillic / other scripts kept by voice's
 * cleaning and typed into chat as UTF-8 bytes (ChatStream's prefix cells, Chat.lua chat43) - never folded or dropped as ASCII.
 */
class VoiceLanguageTest {
    private val en = WhisperOn(lib = true, en = true, multi = false)
    private val enSmall = WhisperOn(lib = true, en = false, multi = false, enSmall = true)
    private val multi = WhisperOn(lib = true, en = false, multi = true)
    private val multiSmall = WhisperOn(lib = true, en = false, multi = false, multiSmall = true)
    private val both = WhisperOn(lib = true, en = true, multi = true)
    private val none = WhisperOn(lib = true, en = false, multi = false)

    // ---------------------------------------------------------------- the pick

    @Test fun thePickersOrderIsSameAutoThenTheShortListThenTheRest() {
        assertEquals(listOf(VoiceLanguage.SAME, VoiceLanguage.AUTO, "en", "es", "fr", "de", "ru", "pt", "it"), VoiceLanguage.ORDER.take(9))
        assertEquals("every Whisper language once, plus Same and Auto", VoiceLanguage.WHISPER.size + 2, VoiceLanguage.ORDER.size)
        assertEquals(VoiceLanguage.ORDER.size, VoiceLanguage.ORDER.toSet().size)
        assertEquals(100, VoiceLanguage.WHISPER.size)         // whisper.cpp v1.9.5's g_lang
        // the rest by English name: Afrikaans first after the short list
        assertEquals("af", VoiceLanguage.ORDER[2 + VoiceLanguage.SHORT.size])
    }

    @Test fun aSavedValueIsSanitizedAndTheDefaultIsSameAsTheApp() {
        assertEquals(VoiceLanguage.SAME, VoiceLanguage.sanitize(null))
        assertEquals(VoiceLanguage.SAME, VoiceLanguage.sanitize("klingon"))
        assertEquals("ru", VoiceLanguage.sanitize("ru"))
        assertEquals(VoiceLanguage.AUTO, VoiceLanguage.sanitize("auto"))
    }

    @Test fun theStepperGoesRoundBothWays() {
        assertEquals(VoiceLanguage.AUTO, VoiceLanguage.step(VoiceLanguage.SAME, 1))
        assertEquals(VoiceLanguage.ORDER.last(), VoiceLanguage.step(VoiceLanguage.SAME, -1))
        assertEquals(VoiceLanguage.SAME, VoiceLanguage.step(VoiceLanguage.ORDER.last(), 1))
        assertEquals("ru", VoiceLanguage.step("de", 1))
        assertEquals("de", VoiceLanguage.step("ru", -1))
    }

    @Test fun resolveGivesWhisperTheAppsLanguageAutoOrThePick() {
        assertEquals("es", VoiceLanguage.resolve(VoiceLanguage.SAME, "es"))
        assertEquals("en", VoiceLanguage.resolve(VoiceLanguage.SAME, "en-US"))
        assertEquals("auto", VoiceLanguage.resolve(VoiceLanguage.AUTO, "fr"))
        assertEquals("ru", VoiceLanguage.resolve("ru", "en"))
        assertTrue(VoiceLanguage.needsMulti("ru", "en"))
        assertTrue(VoiceLanguage.needsMulti(VoiceLanguage.AUTO, "en"))
        assertFalse(VoiceLanguage.needsMulti("en", "es"))
        assertFalse(VoiceLanguage.needsMulti(VoiceLanguage.SAME, "en"))
    }

    @Test fun labelsAreTrsWordsOrTheLanguagesNameInTheAppsLanguage() {
        assertEquals("Same as the app", VoiceLanguage.label(VoiceLanguage.SAME, "es"))
        assertEquals("Auto-detect", VoiceLanguage.label(VoiceLanguage.AUTO, "fr"))
        assertEquals("Russian", VoiceLanguage.label("ru", "en"))
        assertEquals("Ruso", VoiceLanguage.label("ru", "es"))
        assertEquals("Russe", VoiceLanguage.label("ru", "fr"))
        // every language has a name (the locale data's, else Whisper's English one), never its bare code
        for (c in VoiceLanguage.WHISPER.keys) assertTrue(c, VoiceLanguage.label(c, "en").length > c.length)
    }

    @Test fun androidsRecognizerIsAskedForThePicksLocale() {
        assertEquals("es-ES", VoiceLanguage.androidTag(VoiceLanguage.SAME, "es"))
        assertEquals("fr-FR", VoiceLanguage.androidTag(VoiceLanguage.AUTO, "fr"))
        assertEquals("en-US", VoiceLanguage.androidTag("en", "fr"))
        assertEquals("ru", VoiceLanguage.androidTag("ru", "en"))
        assertEquals("jv", VoiceLanguage.androidTag("jw", "en"))
    }

    // ---------------------------------------------------------------- the model and Whisper's language

    @Test fun sameAsTheAppIsAsBefore() {
        assertEquals(Model.EN to "en", Voice.whisperModel("es", en))                    // the English fallback stays
        assertEquals(Model.MULTI to "es", Voice.whisperModel("es", both))
        assertEquals(Model.EN to "en", Voice.whisperModel("en", both))
        assertEquals(Model.EN to "en", Voice.whisperModel("es", en, VoiceLanguage.SAME))
    }

    @Test fun russianTakesTheMultilingualModelInRussian() {
        assertEquals(Model.MULTI to "ru", Voice.whisperModel("en", both, "ru"))
        assertEquals(Model.MULTI to "ru", Voice.whisperModel("en", multi, "ru"))
        assertEquals(Model.MULTI_SMALL to "ru", Voice.whisperModel("es", multiSmall, "ru"))
        assertEquals(Model.MULTI to "auto", Voice.whisperModel("fr", both, VoiceLanguage.AUTO))
    }

    @Test fun anEnglishOnlyModelNeverListensForRussian() {
        // ".en" cannot write Russian: no model can listen, and the multilingual one is wanted instead
        assertNull(Voice.whisperModel("en", en, "ru"))
        assertNull(Voice.whisperModel("en", enSmall, "ru"))
        assertNull(Voice.whisperModel("en", en, VoiceLanguage.AUTO))
        assertTrue(Voice.needsMulti("en", en, "ru"))
        assertTrue(Voice.needsMulti("en", enSmall, VoiceLanguage.AUTO))
        assertFalse(Voice.needsMulti("en", both, "ru"))
        assertFalse(Voice.needsMulti("en", none, "ru"))                  // nothing on disk: the old offer of the four
        assertFalse(Voice.needsMulti("es", en, VoiceLanguage.SAME))      // Same as the app keeps its English fallback
        assertFalse(Voice.needsMulti("en", WhisperOn(lib = false, en = true, multi = false), "ru"))
    }

    @Test fun pickingEnglishUsesWhicheverModelIsThere() {
        assertEquals(Model.EN to "en", Voice.whisperModel("es", en, "en"))
        assertEquals(Model.MULTI to "en", Voice.whisperModel("es", multi, "en"))
    }

    @Test fun theSheetOffersTheMultilingualDownloadNeverEnglishNeverByItself() {
        // only English on disk, Russian picked: the voice sheet's model list (A downloads the highlighted row, its size on it)
        assertEquals(Avail.NEED_MODEL, Voice.avail(Mode.DEVICE, 33, true, emptyList(), en, "en", "ru"))
        assertEquals(Avail.NEED_MODEL, Voice.avail(Mode.ONLINE, 33, true, emptyList(), en, "en", "ru"))
        assertEquals(Model.MULTI_SMALL, Voice.wantedModel("en", "ru"))
        assertEquals(Model.MULTI_SMALL, Voice.wantedModel("en", VoiceLanguage.AUTO))
        assertEquals(Model.EN_SMALL, Voice.wantedModel("es", "en"))
        assertEquals(Model.MULTI_SMALL, Voice.wantedModel("es"))                     // as before
        assertTrue(Model.MULTI_SMALL.choice, Model.MULTI_SMALL.choice.endsWith("60 MB"))
        assertTrue(Model.MULTI.choice, Model.MULTI.choice.endsWith("148 MB"))
        // with the multilingual model in, Russian listens offline
        assertEquals(Avail.WHISPER, Voice.avail(Mode.DEVICE, 33, false, emptyList(), both, "en", "ru"))
        assertEquals(Avail.NONE, Voice.avail(Mode.OFF, 33, true, emptyList(), en, "en", "ru"))
    }

    // ---------------------------------------------------------------- the text: Cyrillic kept end to end

    @Test fun voicesCleaningKeepsCyrillicAndOtherScriptsWhole() {
        assertEquals("Привет, как дела?", Voice.whisperClean(" Привет, как дела? [BLANK_AUDIO]", 4000))
        // never decomposed: "й" and "ё" are not "и" and "е"
        assertEquals("Йошкар-Ола, ёлка", Voice.clean("Йошкар-Ола, ёлка", 255))
        assertEquals("Καλημέρα", Voice.clean("Καλημέρα", 255))
        assertEquals("ラーメン 日本語", Voice.clean("ラーメン 日本語", 255))
        assertEquals("مرحبا", Voice.clean("مرحبا", 255))
        // Latin still folds to ASCII (the chat's letter cells), an emoji still goes, "|" still goes
        assertEquals("Cafe ok", Voice.clean("Café 😀 ok", 255))
        assertEquals("ab", Voice.clean("a|b", 255))
        assertEquals("Ок, go", Voice.insert("", "«Ок», go", append = true, max = 255).replace("\"", ""))
    }

    @Test fun trNeverTouchesAPlayersWords() {
        // menus only: Tr has no Russian, and a Cyrillic line comes back as it is in every app language
        for (l in listOf("en", "es", "fr")) assertEquals("Привет", Tr.t("Привет", l))
    }

    @Test fun chatSpellsAnotherScriptAsUtf8Bytes() {
        // "П" = D0 9F: 0xC0 + 16 ("0") then 0x80 + 31 ("?")
        assertEquals(listOf(ChatStream.HI_B, ChatStream.cellOf('0'), ChatStream.HI_A, ChatStream.cellOf('?')), ChatStream.cellsOf('П'))
        assertEquals(listOf(ChatStream.cellOf('a')), ChatStream.cellsOf('a'))
        assertEquals(6, ChatStream.cellsOf('日')!!.size)                 // three bytes
        assertNull(ChatStream.cellsOf('|'))
        assertNull(ChatStream.cellsOf('\u0007'))
        assertNull(ChatStream.cellsOf("😀"[0]))                           // half an emoji
        // the add-on's length and hash are over UTF-8 bytes (Lua's #, Chat.lua hashOf)
        assertEquals(12, ChatStream.bytes("Привет"))
        var h = 0L
        for (b in "Привет".toByteArray(Charsets.UTF_8)) h = (h * 31 + (b.toInt() and 0xFF)) % 16777213L
        assertEquals(h.toInt(), ChatStream.hash("Привет"))
        assertEquals(ChatStream.hash("hi there"), "hi there".fold(0L) { a, c -> (a * 31 + c.code) % 16777213L }.toInt())
        // the limit is the game's 255 BYTES, never half a character
        val long = "я".repeat(200)
        assertEquals(127, ChatStream.carry(long, 255).length)
        assertEquals("Ок", ChatStream.carry("Ок 😀".trim(), 4))
    }

    /** Chat.lua chat43 in small: the buffer is BYTES; chcmd 2 / 3 arm a prefix the next letter uses; a delete takes a whole character */
    private inner class ByteField {
        val buf = java.io.ByteArrayOutputStream()
        var hi = -1
        var answer: ((ChatStream.Heard) -> Unit)? = null
        var sent: String? = null
        private val queued = ArrayList<ChatStream.Heard>()
        fun text() = String(buf.toByteArray(), Charsets.UTF_8)
        fun deliver() { while (queued.isNotEmpty()) answer?.invoke(queued.removeAt(0)) }
        private fun heard(op: String) = buf.toByteArray().let { b ->
            var hh = 0L; for (x in b) hh = (hh * 31 + (x.toInt() and 0xFF)) % 16777213L
            ChatStream.Heard("chatt", op, true, null, "FIELD", b.size, hh.toInt(), 0, 0, "body")
        }
        fun tap(c: Pair<String, Int>) {
            val (row, col) = c
            when {
                row == "chcmd" && col == 2 -> hi = 0x80
                row == "chcmd" && col == 3 -> hi = 0xC0
                row == "chq" && col == ChatStream.BACK -> {
                    hi = -1
                    val b = buf.toByteArray(); var i = b.size - 1
                    while (i > 0 && (b[i].toInt() and 0xC0) == 0x80) i--
                    buf.reset(); buf.write(b, 0, maxOf(0, i))
                }
                row == "chq" && col == ChatStream.CLEAR -> { hi = -1; buf.reset() }
                row == "chq" && col == ChatStream.BODY -> { hi = -1; queued.add(heard("body")) }
                row == "chq" && col == ChatStream.SEND -> { hi = -1; sent = text(); queued.add(heard("send")) }
                row == "chs" && (col == 36 || col == 39) -> {}
                else -> {
                    val code = when (row) { "chq" -> col + 8; "chr" -> col + 48; else -> col + 88 }
                    if (hi >= 0) { buf.write(hi + code - 32); hi = -1 } else buf.write(code)
                }
            }
        }
    }

    @Test fun aRussianLineReachesTheAddOnExactlyAndIsSent() {
        val f = ByteField()
        lateinit var s: ChatStream
        s = ChatStream({ cells, _ -> cells.forEach { f.tap(it) }; true }, { 0L }, field = true)
        f.answer = { s.heard(it) }
        val events = ArrayList<ChatStream.Event>()
        s.onEvent = { events.add(it) }
        val words = Voice.clean("Привет, идём в Оргриммар?", ChatStream.MAX)
        assertTrue(s.setBody(words))
        assertEquals(words, f.text())
        assertTrue(s.setBody("Привет, идём"))                   // deletes take whole characters
        assertEquals("Привет, идём", f.text())
        assertTrue(s.setBody("Привет, идём!"))
        assertTrue(s.send())
        f.deliver(); f.deliver()
        assertEquals("Привет, идём!", f.sent)
        assertEquals(listOf<ChatStream.Event>(ChatStream.Event.Sent), events)
    }

    // ---------------------------------------------------------------- Reset settings

    @Test fun theVoiceLanguageIsInTheSettingsBucket() {
        val k = ResetPlan.PREFS.single { it.file == "settings" && it.key == VoiceLanguage.PREF }
        assertEquals(ResetPlan.Kind.SETTINGS, k.kind)
        assertFalse(k.perChar)
    }
}
