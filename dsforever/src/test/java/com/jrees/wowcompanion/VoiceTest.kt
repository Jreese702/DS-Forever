package com.jrees.wowcompanion

import com.jrees.wowcompanion.Voice.Act
import com.jrees.wowcompanion.Voice.Avail
import com.jrees.wowcompanion.Voice.Mode
import com.jrees.wowcompanion.Voice.Perm
import com.jrees.wowcompanion.Voice.Problem
import com.jrees.wowcompanion.Voice.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VOICE TO TEXT'S RULES, off the device (the user's list 2026-11-16: "A opens the keyboard, X opens our own voice prompt", "intuitive
 * and slick"; every design decision on its recommended pick): the setting, the glyph rule, the cleaning (accents folded BEFORE ChatStream's
 * filter would drop them), where the words go (a message box adds, a search / name box replaces), the settled words, the error map, and
 * the voice sheet's states and buttons: the words shown first, A / Start only 0.4 s after they settle, Y edit, X again, B the old text.
 */
class VoiceTest {
    private var now = 10_000L
    private fun sheet(before: String = "", append: Boolean = true, max: Int = 255, mode: Mode = Mode.DEVICE) =
        Voice.Sheet(before, append, max, mode) { now }

    // ---------------------------------------------------------------- the setting and the glyph

    /** ptt-speed: Whisper's whole-line repeat (a short encoder window's slip) is said once; a part said twice is kept */
    @Test fun aLineSaidTwiceOverIsSaidOnce() {
        assertEquals("Let's go kill the boss in the cave.",
            Voice.whisperClean(" Let's go kill the boss in the cave. Let's go kill the boss in the cave.", 255))
        assertEquals("a short cheer is kept", "go go go go", Voice.whisperClean("go go go go", 255))
        assertEquals("help me help me", Voice.whisperClean("help me help me", 255))
        assertEquals("no no", Voice.whisperClean("no no", 255))
        assertEquals("help me help me please", Voice.whisperClean("help me help me please", 255))
        assertEquals("on my way", Voice.whisperClean(" on my way", 255))
    }

    @Test fun theSettingDefaultsToThisDevice() {
        assertEquals(Mode.DEVICE, Mode.of(null))
        assertEquals(Mode.DEVICE, Mode.of("nonsense"))
        assertEquals(Mode.ONLINE, Mode.of("online"))
        assertEquals(Mode.OFF, Mode.of("off"))
        assertEquals(listOf("This device", "Online", "Off"), Mode.values().map { it.label })
    }

    @Test fun xIsVoicesWhenTheSettingIsOnAndThereIsAMicrophone() {
        assertTrue(Voice.showsMic(Mode.DEVICE, hasMic = true))
        assertTrue(Voice.showsMic(Mode.ONLINE, hasMic = true))
        assertFalse(Voice.showsMic(Mode.OFF, hasMic = true))
        assertFalse(Voice.showsMic(Mode.DEVICE, hasMic = false))
    }

    @Test fun theRecognizerFollowsTheAppsLanguage() {
        assertEquals("en-US", Voice.language("en"))
        assertEquals("es-ES", Voice.language("es"))
        assertEquals("fr-FR", Voice.language("fr"))
        assertEquals("en-US", Voice.language("de"))
    }

    // ---------------------------------------------------------------- the text

    @Test fun accentsAreFoldedNotDropped() {
        assertEquals("Cafe naive", Voice.fold("Café naïve"))
        assertEquals("Que tal? Hola!", Voice.fold("¿Qué tal? ¡Hola!"))
        assertEquals("Strasse", Voice.fold("Straße"))
        assertEquals("OEuvre AEroskobing Lodz", Voice.fold("Œuvre Ærøskøbing Łódź"))
        assertEquals("ca va tres bien", Voice.fold("ça va très bien"))
        // what ChatStream's filter alone would have done: the letters gone
        assertEquals("a va trs bien", "ça va très bien".filter { ChatStream.cellOf(it) != null })
    }

    @Test fun quotesDashesPipesAndSpaces() {
        assertEquals("'quote' \"double\" - dash - em ...", Voice.fold("‘quote’ “double” – dash — em …"))
        assertEquals("ab", Voice.fold("a|b"))
        assertEquals("two spaces", Voice.fold("  two    spaces\t"))
        assertEquals("don't", Voice.fold("don’t"))
    }

    @Test fun cleanKeepsOnlyWhatChatCarriesAndCutsToTheBox() {
        assertEquals("hello", Voice.clean("hello world", 5))
        // #477: another script is kept (chat types it as UTF-8, Chat.lua chat43); an emoji still goes
        assertEquals("日本 ok", Voice.clean("日本 ok", 10))
        assertEquals("smile", Voice.clean("smile 😀", 20))
        val c = Voice.clean("Él dijo: «sí» — vale…", 255)
        assertTrue(c, c.all { ChatStream.cellOf(it) != null })         // Latin still folds to ASCII
        assertEquals("El dijo: \"si\" - vale...", c)
    }

    @Test fun aMessageBoxAddsASearchBoxReplaces() {
        assertEquals("hi there", Voice.insert("hi", "there", append = true, max = 255))
        assertEquals("hi there", Voice.insert("hi ", "there", append = true, max = 255))
        assertEquals("there", Voice.insert("", "there", append = true, max = 255))
        assertEquals("new words", Voice.insert("old", "new words", append = false, max = 255))
        // nothing heard yet: the box as it was (a search box is not wiped while the player starts to speak)
        assertEquals("old", Voice.insert("old", "", append = false, max = 255))
        assertEquals("hi", Voice.insert("hi", "  ", append = true, max = 255))
        // the box's own limit
        assertEquals("hi th", Voice.insert("hi", "there", append = true, max = 5))
        assertEquals("abc", Voice.insert("x", "abcdef", append = false, max = 3))
    }

    @Test fun wordsSettleWhenTwoGuessesAgreeAndNeverTheLastWord() {
        val s = Voice.Stable()
        assertFalse(s.partial("hello"))                       // one guess: nothing agreed yet, and its only word is its last
        assertTrue(s.partial("hello wor"))                    // "hello" agreed twice and is not the last word
        assertEquals("hello", s.text)
        assertFalse(s.partial("hello world how"))             // "wor" became "world": only "hello" agrees
        assertTrue(s.partial("hello world how are"))
        assertEquals("hello world how", s.text)
        // a guess that changes an earlier word never takes settled words back (the final words fix it all)
        assertFalse(s.partial("hello word how are you"))
        assertEquals("hello world how", s.text)
        s.reset()
        assertEquals("", s.text)
    }

    // ---------------------------------------------------------------- the error map

    @Test fun theDesignsErrorMap() {
        fun a(code: Int, ms: Long = 5000, retried: Boolean = false, mode: Mode = Mode.DEVICE, flat: Int = 0) = Voice.answer(code, ms, retried, mode, flat)
        assertEquals(Voice.Answer(problem = Problem.PACK, get = true), a(13))
        assertEquals(Voice.Answer(problem = Problem.PACK, get = false), a(13, mode = Mode.ONLINE))
        assertTrue(a(12).offerOnline)
        assertEquals(Problem.PACK, a(12, mode = Mode.ONLINE).problem)
        assertEquals(Problem.OFFLINE, a(1).problem)
        assertEquals(Problem.OFFLINE, a(2).problem)
        assertEquals(Problem.BUSY, a(10).problem)
        assertTrue(a(5).retry); assertEquals(Problem.NOT_HEARD, a(5, retried = true).problem)
        assertTrue(a(8).retry); assertEquals(Problem.BUSY, a(8, retried = true).problem)
        assertTrue(a(7, ms = 400).retry)                      // a no-match within 1 s of the start: one quiet retry
        assertEquals(Problem.NOT_HEARD, a(7, ms = 400, retried = true).problem)
        assertEquals(Problem.NOT_HEARD, a(7, ms = 4000).problem)
        assertEquals(Problem.NOT_HEARD, a(6).problem)
        assertEquals(Problem.NO_SOUND, a(6, flat = 2).problem)
        assertEquals(Problem.NO_SOUND, a(7, ms = 4000, flat = 2).problem)
        assertEquals(Problem.MIC_OFF, a(9).problem)
        assertEquals(Problem.NO_SOUND, a(3).problem)
        assertEquals(Problem.BUSY, a(99).problem)
    }

    // ---------------------------------------------------------------- the sheet: the microphone question

    @Test fun theFirstXAsksForTheMicrophoneNeverInAFight() {
        val s = sheet("hi")
        assertEquals(listOf(Act.Glow), s.open(Perm.ASKABLE, Avail.DEVICE))
        assertEquals(State.ASK, s.state)
        assertEquals(listOf(Act.Glow), s.press("PAD1", fight = true))          // Allow greyed: "After the fight"
        assertEquals(listOf(Act.AskMic), s.press("PAD1", fight = false))
        // Android said yes: listening at once (the player pressed Allow to talk)
        assertEquals(listOf(Act.Start(online = false)), s.permAnswered(Perm.GRANTED, Avail.DEVICE))
        assertEquals(State.LISTENING, s.state)
    }

    @Test fun notNowAndDontAllow() {
        val s = sheet("hi")
        s.open(Perm.ASKABLE, Avail.DEVICE)
        assertEquals(listOf(Act.Close), s.press("PAD2", fight = false))       // Not now: the box was never changed
        assertTrue(s.closed)
        assertEquals(emptyList<Act>(), s.press("PAD1", fight = false))        // a closed sheet does nothing
        val d = sheet()
        d.open(Perm.ASKABLE, Avail.DEVICE); d.press("PAD1", false)
        d.permAnswered(Perm.DENIED, Avail.DEVICE)
        assertEquals(State.MIC_OFF, d.state); assertEquals(Problem.MIC_OFF, d.problem)
        assertEquals(listOf(Act.AskMic), d.press("PAD1", false))              // Android may still ask: Allow again
        val b = sheet()
        assertEquals(listOf(Act.Glow), b.open(Perm.BLOCKED, Avail.DEVICE))
        assertEquals(State.BLOCKED, b.state)
        assertEquals(listOf(Act.Glow), b.press("PAD1", false))                // Android stopped asking: only its Settings path
        assertEquals(listOf(Act.Close), b.press("PAD2", false))
    }

    @Test fun thisDeviceWithoutARecognizerOffersOnlineOnce() {
        val s = sheet(mode = Mode.DEVICE)
        s.open(Perm.GRANTED, Avail.ONLINE_ONLY)
        assertEquals(State.OFFER_ONLINE, s.state)
        assertEquals(listOf(Act.UseOnline, Act.Start(online = true)), s.press("PAD1", false))
        assertEquals(Mode.ONLINE, s.mode)
        val n = sheet()
        n.open(Perm.GRANTED, Avail.NONE)
        assertEquals(State.PROBLEM, n.state); assertEquals(Problem.NO_SERVICE, n.problem)
        // the online pick starts the online recognizer, never "this device" (it is the service's promise, not ours)
        assertEquals(listOf<Act>(Act.Start(online = true)), sheet(mode = Mode.ONLINE).open(Perm.GRANTED, Avail.ONLINE))
    }

    // ---------------------------------------------------------------- the sheet: listening and the result

    @Test fun aMessageBoxGetsTheSettledWordsThenTheFinalWords() {
        val s = sheet("hi")
        assertEquals(listOf<Act>(Act.Start(false)), s.open(Perm.GRANTED, Avail.DEVICE))
        assertEquals(listOf(Act.Glow), s.partial("how"))
        assertEquals("how", s.heard)
        assertEquals(listOf<Act>(Act.Push("hi how")), s.partial("how are"))
        assertEquals(listOf<Act>(Act.Push("hi how are")), s.partial("how are you"))
        assertEquals(listOf(Act.Glow), s.partial("how are you"))             // nothing new settled: no push (no taps piled up)
        assertEquals(listOf<Act>(Act.Push("hi How are you")), s.final("How are you"))
        assertEquals(State.RESULT, s.state)
        assertEquals("hi How are you", s.boxText())
    }

    @Test fun aOrStartOnlyFromAMomentAfterTheWordsSettle() {
        val s = sheet("hi")
        s.open(Perm.GRANTED, Avail.DEVICE)
        s.final("there")
        assertFalse(s.canAccept())
        assertEquals(listOf(Act.Glow), s.press("PAD1", false))                // a double press can't send unread words
        now += Voice.ACCEPT_MS - 1
        assertEquals(listOf(Act.Glow), s.press("PADFORWARD", false))
        now += 1
        assertTrue(s.canAccept())
        assertEquals(listOf<Act>(Act.Finish("hi there")), s.press("PADFORWARD", false))
        assertTrue(s.closed)
        val a = sheet("hi"); a.open(Perm.GRANTED, Avail.DEVICE); a.final("there"); now += 500
        assertEquals(listOf<Act>(Act.Finish("hi there")), a.press("PAD1", false))
    }

    @Test fun yEditsXSaysItAgainBGivesTheOldTextBack() {
        val s = sheet("hi")
        s.open(Perm.GRANTED, Avail.DEVICE)
        s.final("there"); now += 500
        assertEquals(listOf<Act>(Act.Edit("hi there")), s.press("PAD4", false))
        val x = sheet("hi"); x.open(Perm.GRANTED, Avail.DEVICE); x.final("there")
        // X: only what this sheet heard goes; the box's own text stays
        assertEquals(listOf(Act.Push("hi"), Act.Start(false)), x.press("PAD3", false))
        assertEquals(State.LISTENING, x.state); assertEquals("", x.heard)
        x.final("again"); now += 500
        assertEquals(listOf<Act>(Act.Finish("hi again")), x.press("PAD1", false))
        val b = sheet("hi"); b.open(Perm.GRANTED, Avail.DEVICE); b.final("there")
        assertEquals(listOf(Act.Push("hi"), Act.Close), b.press("PAD2", false))
        // B while listening: the recognizer goes too
        val l = sheet("hi"); l.open(Perm.GRANTED, Avail.DEVICE); l.partial("one two"); l.partial("one two three")
        assertEquals(listOf(Act.Abort, Act.Push("hi"), Act.Close), l.press("PAD2", false))
        assertEquals(emptyList<Act>(), l.final("late words"))                 // a late result changes nothing
    }

    @Test fun lbRbAreTheRoomsInEveryState() {
        val s = sheet()
        s.open(Perm.ASKABLE, Avail.DEVICE)
        for (st in 0..3) {
            assertEquals(listOf(Act.Pass), s.press("PADLSHOULDER", false))
            assertEquals(listOf(Act.Pass), s.press("PADRSHOULDER", false))
            when (st) {
                0 -> s.permAnswered(Perm.GRANTED, Avail.DEVICE)               // listening
                1 -> s.final("words")                                          // the result
                2 -> { s.press("PAD3", false); s.error(Voice.E_SPEECH_TIMEOUT) }   // a problem
            }
        }
    }

    @Test fun aSearchBoxIsReplacedAndKeptUntilWordsCome() {
        val s = sheet("old", append = false)
        s.open(Perm.GRANTED, Avail.DEVICE)
        assertEquals("old", s.boxText())
        assertEquals(listOf(Act.Glow), s.partial("new"))
        assertEquals(listOf<Act>(Act.Push("new")), s.partial("new thing now"))        // "new" agreed twice; "thing" only once
        assertEquals(listOf<Act>(Act.Push("new thing now")), s.final("new thing now"))
        assertEquals("new thing now", s.boxText())
    }

    @Test fun aStopsAndKeepsWhatWasHeard() {
        val s = sheet()
        s.open(Perm.GRANTED, Avail.DEVICE)
        s.partial("grab the"); s.partial("grab the loot")
        assertEquals(listOf(Act.Stop), s.press("PAD1", false))
        assertEquals(State.STOPPING, s.state)
        assertEquals(listOf(Act.Glow), s.press("PAD1", false))                // one stop
        // the recognizer then says "no match": the words heard so far are the result
        s.error(Voice.E_NO_MATCH)
        assertEquals(State.RESULT, s.state); assertEquals("grab the loot", s.heard)
        // its final words never came: after the wait, the words heard so far
        val t = sheet()
        t.open(Perm.GRANTED, Avail.DEVICE); t.partial("need heals")
        t.press("PAD1", false)
        assertEquals(emptyList<Act>(), t.tick())
        now += Voice.STOP_WAIT_MS + 1
        assertEquals(listOf(Act.Abort, Act.Push("need heals")), t.tick())
        assertEquals(State.RESULT, t.state)
    }

    // ---------------------------------------------------------------- the sheet: problems

    @Test fun problemsAndTheirButtons() {
        val s = sheet("hi")
        s.open(Perm.GRANTED, Avail.DEVICE)
        assertEquals(listOf(Act.Glow), s.error(Voice.E_LANG_UNAVAILABLE))
        assertEquals(State.PROBLEM, s.state); assertEquals(Problem.PACK, s.problem); assertTrue(s.canGet)
        assertEquals(listOf(Act.GetPack), s.press("PAD1", false))             // A = Get
        assertEquals(listOf(Act.Start(false)), s.press("PAD3", false))        // X = say again
        s.error(Voice.E_NETWORK)
        assertEquals(Problem.OFFLINE, s.problem)
        assertEquals(listOf(Act.Start(false)), s.press("PAD1", false))        // A on "Say again"
        s.error(Voice.E_TOO_MANY)
        assertEquals(Problem.BUSY, s.problem)
        assertEquals(listOf<Act>(Act.Edit("hi")), s.press("PAD4", false))     // Y: the keyboard with the earlier text
        val c = sheet(); c.open(Perm.GRANTED, Avail.DEVICE)
        c.error(Voice.E_PERMISSIONS)
        assertEquals(Problem.MIC_OFF, c.problem)
        assertEquals(listOf(Act.Glow), c.press("PAD1", fight = true))
        assertEquals(listOf(Act.AskMic), c.press("PAD1", fight = false))
        assertEquals(listOf(Act.Start(false)), c.permAnswered(Perm.GRANTED, Avail.DEVICE))
    }

    @Test fun oneQuietRetryForTheRecognizersHiccups() {
        val s = sheet()
        s.open(Perm.GRANTED, Avail.DEVICE)
        now += 300
        assertEquals(listOf<Act>(Act.Start(false)), s.error(Voice.E_NO_MATCH))   // within 1 s: tried again without a word
        assertEquals(State.LISTENING, s.state)
        now += 300
        s.error(Voice.E_NO_MATCH)
        assertEquals(State.PROBLEM, s.state); assertEquals(Problem.NOT_HEARD, s.problem)
        val b = sheet(); b.open(Perm.GRANTED, Avail.DEVICE)
        assertEquals(listOf<Act>(Act.Start(false)), b.error(Voice.E_BUSY))
        b.error(Voice.E_BUSY)
        assertEquals(Problem.BUSY, b.problem)
        val o = sheet(mode = Mode.ONLINE); o.open(Perm.GRANTED, Avail.ONLINE)
        assertEquals(listOf<Act>(Act.Start(true)), o.error(Voice.E_CLIENT))   // the retry keeps the recognizer the player picked
    }

    @Test fun aFlatLevelThroughTwoTimeoutsIsNoSound() {
        val s = sheet()
        s.open(Perm.GRANTED, Avail.DEVICE)
        now += 6000
        s.error(Voice.E_SPEECH_TIMEOUT)
        assertEquals(Problem.NOT_HEARD, s.problem)
        s.press("PAD3", false); now += 6000
        s.error(Voice.E_SPEECH_TIMEOUT)
        assertEquals(Problem.NO_SOUND, s.problem)
        // a level that moved: just not heard
        val h = sheet(); h.open(Perm.GRANTED, Avail.DEVICE)
        for (k in 0..1) { h.rms(6f); now += 6000; h.error(Voice.E_SPEECH_TIMEOUT); h.press("PAD3", false) }
        assertTrue(h.state == State.LISTENING)
        now += 6000; h.rms(6f); h.error(Voice.E_SPEECH_TIMEOUT)
        assertEquals(Problem.NOT_HEARD, h.problem)
    }

    @Test fun thisDeviceWithoutTheLanguageOffersOnline() {
        val s = sheet("hi")
        s.open(Perm.GRANTED, Avail.DEVICE)
        s.partial("a b"); s.partial("a b c")
        s.error(Voice.E_LANG_UNSUPPORTED)
        assertEquals(State.OFFER_ONLINE, s.state)
        assertEquals("hi", s.pushed)                                          // the settled words taken back out of the box
    }

    @Test fun theLevelFollowsTheVoiceAndFalls() {
        val s = sheet(); s.open(Perm.GRANTED, Avail.DEVICE)
        assertEquals(0f, s.level, 0f)
        s.rms(10f)
        val up = s.level
        assertTrue(up > 0.5f)
        repeat(20) { s.rms(-2f) }
        assertTrue(s.level < up * 0.2f)
    }

    @Test fun theLogLineNeverHasTheWords() {
        val s = sheet("my secret plans")
        s.open(Perm.GRANTED, Avail.DEVICE)
        now += 1234
        s.final("meet at the inn")
        val line = s.logLine("final")
        assertFalse(line, line.contains("inn") || line.contains("secret") || line.contains("meet"))
        assertTrue(line, line.contains("mode=device") && line.contains("state=result") && line.contains("chars=15") && line.contains("ms=1234"))
    }

    @Test fun hooksDefaultSafe() {
        assertFalse(Voice.inFight())
        assertTrue(Voice.hints().isEmpty())
        var stopped = 0
        val hook: () -> Unit = { stopped++ }
        Voice.register(hook)
        Voice.stopAll()
        assertEquals(1, stopped)
        Voice.unregister(hook)
        Voice.stopAll()
        assertEquals(1, stopped)
        assertNull(null)
    }
}
