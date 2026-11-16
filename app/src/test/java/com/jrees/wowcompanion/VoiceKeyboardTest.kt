package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * THE KEYBOARD'S VOICE PAGE, driven as a host drives it (the user's picks 2026-11-16): X on a voice board (no Quick chat key) opens it from
 * the keys; the settled words go to the box as they come (an add-on field copies them), the final words on the result, A only 0.4 s after;
 * B gives the box its earlier text back (back to the keys it came from, or the host's close); Android's microphone question once, never in
 * a fight, and the page waits through the window's hide while it is up; Voice typing Off, a sign-in board or Chat's board keep X as before.
 * The recognizer is a stand-in ([Listen]); the drawing of every state runs (no art: the plain fallbacks).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceKeyboardTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var now = 50_000L

    class Listen : ChatKeyboard.VoiceListen {
        val starts = ArrayList<Boolean>(); var stops = 0; var cancels = 0; var packs = 0; var ok = true
        var langs = ArrayList<String>()
        override fun start(online: Boolean, lang: String, hints: List<String>, sink: VoiceEngine.Sink): Boolean { starts += online; langs += lang; return ok }
        override fun stop() { stops++ }
        override fun cancel() { cancels++ }
        override fun getPack(lang: String): Boolean { packs++; return true }
    }

    private var text = ""
    private var sent = 0
    private var closed = 0
    private val pushes = ArrayList<String>()

    private fun board(swap: Boolean = false, signIn: Boolean = false, voice: Boolean = true, append: Boolean = false,
                      perm: Voice.Perm = Voice.Perm.GRANTED, avail: Voice.Avail = Voice.Avail.DEVICE, l: Listen = Listen()): Pair<ChatKeyboard, Listen> {
        val kb = ChatKeyboard(app, swap = swap, signIn = signIn, voice = voice)
        kb.text = { text }
        kb.onText = { t -> text = t; pushes += t }
        kb.onSend = { sent++ }
        kb.onClose = { closed++ }
        kb.voiceAppend = append
        kb.engine = l
        kb.voiceProbe = { perm to avail }
        kb.clock = { now }
        kb.open()
        return kb to l
    }

    @Before fun setUp() {
        Settings.init(app)
        Settings.voiceTyping = Voice.Mode.DEVICE
        VoiceUi.micForTest(true)
        Voice.inFight = { false }
        Voice.askMic = null
        text = ""; sent = 0; closed = 0; pushes.clear()
    }

    @After fun tearDown() { VoiceUi.micForTest(null); Voice.inFight = { false }; Voice.askMic = null; VoiceMic.onAnswer = null }

    private fun draw(kb: ChatKeyboard) {
        kb.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        kb.layout(0, 0, 1240, kb.measuredHeight)
        val b = Bitmap.createBitmap(1240, maxOf(1, kb.measuredHeight), Bitmap.Config.ARGB_8888)
        kb.draw(Canvas(b))
    }

    @Test fun xOnTheKeysListensAndAAcceptsAfterTheWordsSettle() {
        val (kb, l) = board()
        assertTrue(kb.press("PAD3"))
        assertTrue(kb.voiceShown)
        assertEquals(listOf(false), l.starts)                       // This device's recognizer
        assertEquals(listOf("en-US"), l.langs)
        draw(kb)
        val sink = kb.voiceSink()
        sink.rms(8f)
        sink.partial("loot the")
        assertEquals("", text)                                      // nothing settled yet: the box untouched
        sink.partial("loot the chest")
        assertEquals("loot the", text)                              // the settled words, as they come
        draw(kb)
        sink.final("loot the chest")
        assertEquals("loot the chest", text)
        assertEquals(Voice.State.RESULT, kb.voiceSheet!!.state)
        draw(kb)
        assertTrue(kb.press("PAD1")); assertEquals(0, sent)         // too soon after the words settled
        now += Voice.ACCEPT_MS
        assertTrue(kb.press("PAD1"))
        assertEquals(1, sent)
        assertFalse(kb.voiceShown)
        assertEquals("loot the chest", text)
    }

    @Test fun bGivesTheEarlierTextBackAndReturnsToTheKeys() {
        text = "old search"
        val (kb, l) = board()
        kb.press("PAD3")
        kb.voiceSink().partial("new words"); kb.voiceSink().partial("new words here")
        assertEquals("new words", text)                             // a search box: replaced by the settled words
        assertTrue(kb.press("PAD2"))
        assertEquals("old search", text)
        assertEquals(1, l.cancels)
        assertFalse(kb.voiceShown)
        assertEquals("opened from the keys: B comes back to them, the host's close is not called", 0, closed)
        // from the box (the host's X): B closes the board as the host's own close
        assertTrue(kb.startVoice(fromKeys = false))
        kb.press("PAD2")
        assertEquals(1, closed)
    }

    @Test fun yEditsWithTheWordsInPlace() {
        text = "hi"
        val (kb, _) = board(append = true)
        kb.press("PAD3")
        kb.voiceSink().final("there")
        assertTrue(kb.press("PAD4"))
        assertFalse(kb.voiceShown)
        assertEquals("hi there", text)
        // the keys again, typing after the words
        kb.press("PAD4")                                            // Y on the keys = Space
        assertEquals("hi there ", text)
    }

    @Test fun xAgainReplacesOnlyWhatThisSheetHeard() {
        text = "hi"
        val (kb, l) = board(append = true)
        kb.press("PAD3")
        kb.voiceSink().final("wrong words")
        assertEquals("hi wrong words", text)
        kb.press("PAD3")
        assertEquals("hi", text)
        assertEquals(2, l.starts.size)
        kb.voiceSink().final("right words")
        now += 1000
        kb.press("PADFORWARD")                                      // Start = the finish button too
        assertEquals("hi right words", text)
        assertEquals(1, sent)
    }

    @Test fun theMicrophoneQuestionOnceNeverInAFightAndThePageWaitsForIt() {
        var asked = 0
        Voice.askMic = { asked++ }
        var perm = Voice.Perm.ASKABLE
        val kb = ChatKeyboard(app, swap = false, voice = true)
        val l = Listen()
        kb.text = { text }; kb.onText = { text = it }; kb.engine = l; kb.voiceProbe = { perm to Voice.Avail.DEVICE }; kb.clock = { now }
        assertTrue(kb.press("PAD3"))
        assertEquals(Voice.State.ASK, kb.voiceSheet!!.state)
        draw(kb)
        Voice.inFight = { true }
        kb.press("PAD1"); assertEquals(0, asked)                    // Allow greyed: "After the fight"
        draw(kb)
        Voice.inFight = { false }
        kb.press("PAD1"); assertEquals(1, asked)
        assertTrue(kb.voiceAsking)
        assertNotNull(VoiceMic.onAnswer)
        // the host's window hides while Android's question is up: the page stays
        kb.onVisibilityAggregated(false)
        assertTrue(kb.voiceShown)
        Voice.stopAll()
        assertTrue(kb.voiceShown)
        // Android said yes: listening at once
        perm = Voice.Perm.GRANTED
        VoiceMic.onAnswer!!.invoke()
        assertFalse(kb.voiceAsking)
        assertEquals(Voice.State.LISTENING, kb.voiceSheet!!.state)
        assertEquals(1, l.starts.size)
        // and now a hide is a real one: the recognizer goes, the box gets its text back
        kb.voiceSink().partial("a b"); kb.voiceSink().partial("a b c")
        kb.onVisibilityAggregated(false)
        assertFalse(kb.voiceShown)
        assertEquals("", text)
        assertTrue(l.cancels >= 1)
    }

    @Test fun androidStoppedAskingShowsTheSettingsPath() {
        val (kb, _) = board(perm = Voice.Perm.BLOCKED)
        kb.press("PAD3")
        assertEquals(Voice.State.BLOCKED, kb.voiceSheet!!.state)
        draw(kb)
        kb.press("PAD1")
        assertEquals(Voice.State.BLOCKED, kb.voiceSheet!!.state)
        kb.press("PAD2")
        assertFalse(kb.voiceShown)
        assertEquals("Android Settings > Apps > DS Forever > Permissions > Microphone", ChatKeyboard.PATH)
    }

    @Test fun theOnlineOfferChangesTheSettingOnThePlayersA() {
        val (kb, l) = board(avail = Voice.Avail.ONLINE_ONLY)
        kb.press("PAD3")
        assertEquals(Voice.State.OFFER_ONLINE, kb.voiceSheet!!.state)
        assertEquals(Voice.Mode.DEVICE, Settings.voiceTyping)       // nothing changes by itself
        draw(kb)
        kb.press("PAD1")
        assertEquals(Voice.Mode.ONLINE, Settings.voiceTyping)
        assertEquals(listOf(true), l.starts)
    }

    @Test fun problemsDrawAndOfferTheirButtons() {
        val (kb, l) = board()
        kb.press("PAD3")
        kb.voiceSink().error(Voice.E_LANG_UNAVAILABLE)
        assertEquals(Voice.Problem.PACK, kb.voiceSheet!!.problem)
        draw(kb)
        kb.press("PAD1")
        assertEquals(1, l.packs)
        kb.press("PAD3")
        now += 5000
        kb.voiceSink().error(Voice.E_SPEECH_TIMEOUT)
        assertEquals(Voice.Problem.NOT_HEARD, kb.voiceSheet!!.problem)
        draw(kb)
        // a recognizer that cannot start: one quiet retry, then "Voice busy"
        l.ok = false
        kb.press("PAD1")
        assertEquals(Voice.Problem.BUSY, kb.voiceSheet!!.problem)
    }

    @Test fun lbRbAreTheRoomsWhileListening() {
        val (kb, _) = board()
        kb.press("PAD3")
        assertFalse(kb.press("PADLSHOULDER"))                       // the host gets it (Chat: the type)
        assertFalse(kb.press("PADRSHOULDER"))
        assertTrue(kb.voiceShown)
    }

    @Test fun xKeepsItsOldJobWhereVoiceIsNot() {
        // Chat's board: X is its Quick chat key's
        val (chat, cl) = board(swap = true)
        assertFalse(chat.press("PAD3")); assertFalse(chat.voiceShown); assertTrue(cl.starts.isEmpty())
        // a sign-in board: never
        val (sign, sl) = board(signIn = true)
        assertFalse(sign.voiceOn); assertFalse(sign.startVoice()); assertTrue(sl.starts.isEmpty())
        // a board that takes no voice (Macros)
        val (mac, _) = board(voice = false)
        assertFalse(mac.press("PAD3")); assertFalse(mac.voiceShown)
        // a shared board on a field that takes no voice (Mail's To)
        val (to, _) = board()
        to.voiceBox = false
        assertFalse(to.press("PAD3")); assertFalse(to.voiceShown)
        // Voice typing Off: no X anywhere
        Settings.voiceTyping = Voice.Mode.OFF
        val (off, ol) = board()
        assertFalse(off.press("PAD3")); assertFalse(off.startVoice()); assertTrue(ol.starts.isEmpty())
        Settings.voiceTyping = Voice.Mode.DEVICE
        // no microphone on the device
        VoiceUi.micForTest(false)
        val (nm, _) = board()
        assertFalse(nm.startVoice())
        assertFalse(VoiceUi.micShown(app))
    }

    @Test fun theLateWordsOfAClosedPageChangeNothing() {
        val (kb, _) = board()
        kb.press("PAD3")
        val sink = kb.voiceSink()
        kb.press("PAD2")
        sink.final("too late")
        sink.partial("too late again")
        assertEquals("", text)
        assertNull(kb.voiceSheet)
    }

    @Test fun theBoxGlyphsLeaveRoomAndTheirTouchBoxIsTheMicrophone() {
        val b = Bitmap.createBitmap(600, 100, Bitmap.Config.ARGB_8888)
        val g = VoiceUi.boxGlyphs(Canvas(b), 590f, 50f, 40f, voice = true)
        assertNotNull(g.mic)
        assertTrue(g.mic!!.left > g.keys.right)                     // A + keyboard first, then X + microphone at the right
        assertTrue(VoiceUi.glyphsWidth(40f, true) >= g.mic!!.right - g.keys.left - 1f)
        assertNull(VoiceUi.boxGlyphs(Canvas(b), 590f, 50f, 40f, voice = false).mic)
    }
}
