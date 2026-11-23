package com.jrees.wowcompanion

import com.jrees.wowcompanion.HoldTalk.Out
import com.jrees.wowcompanion.HoldTalk.St
import com.jrees.wowcompanion.PadMapModel.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HOLD TO TALK ON THE DS TOOLBAR'S CHAT (the user, 2026-11-16: "Add an option to the text chat ds toolbar option for a quick chat option
 * for holding that button for quick voice to text to last used text channel (usage and chat indicated by a mic symbol on the toolbar and
 * the color of the chat is the mic"; picks: a toggle off by default, on release "Show, then A sends" or "Send straight away"), off-device:
 * the tap keeps today's Chat room, the hold records and never opens it, the line's A / B / fade, Send now, the no-voice note; Left is
 * the toolbar's Chat only where the router says so (the fight switch's In-combat entry wins); the held recorder's stop rule.
 */
class HoldTalkTest {
    private fun pending(t: HoldTalk, at: Long = 1000L, now: Boolean = false) = assertTrue(t.down(at, now))

    // ---------------------------------------------------------------- the tap keeps today's action

    @Test fun aQuickTapReleasedBeforeTheWordOpensChatWhenTheWordComes() {
        val t = HoldTalk(); pending(t)
        assertEquals(Out.NONE, t.up(1120L))                         // released before the router's word: nothing to do yet
        assertEquals(St.IDLE, t.st)
        assertTrue("the word opens the Chat room, as today", t.word(1200L))
        assertFalse("no recording", t.listening)
    }

    @Test fun aTapWhoseWordCameWhileHeldOpensChatAtTheRelease() {
        val t = HoldTalk(); pending(t)
        assertFalse("the word waits while Left is still down", t.word(1150L))
        assertEquals(Out.NONE, t.tick(1250L))                        // under the threshold: still deciding
        assertEquals(Out.OPEN_CHAT, t.up(1300L))                     // a tap: Chat opens at the release, once
        assertEquals(St.IDLE, t.st)
    }

    @Test fun theSettingOffNeverStartsAHoldSoTheWordGoesStraightThrough() {
        val t = HoldTalk()                                            // the host never calls down() with the switch off
        assertTrue(t.word(5000L))
        assertFalse(t.wantsGuest)
    }

    // ---------------------------------------------------------------- the hold records and never opens Chat

    @Test fun aHoldPastTheThresholdListensAndSwallowsTheWord() {
        val t = HoldTalk(); pending(t)
        assertFalse(t.word(1150L))
        assertEquals(Out.NONE, t.tick(1000L + HoldTalk.HOLD_MS - 1))
        assertEquals(Out.LISTEN, t.tick(1000L + HoldTalk.HOLD_MS))
        assertTrue(t.listening); assertTrue(t.wantsGuest); assertTrue(t.takesPad)
        assertFalse("a word while held", t.word(1500L))
        assertEquals(Out.STOP, t.up(2400L))
        assertEquals(St.WORKING, t.st)
        assertFalse("a router that fires on the release: its word is the hold's own", t.word(2500L))
        assertFalse(t.word(2400L + HoldTalk.SWALLOW_MS - 1))
    }

    @Test fun showThenASendsBDropsAnyOtherButtonDropsAndItFades() {
        val t = HoldTalk(); pending(t); t.tick(1400L); t.up(2000L)
        assertEquals(Out.SHOW, t.heard(2600L, " need a healer "))
        assertEquals(St.LINE, t.st); assertEquals("need a healer", t.words)
        assertEquals(Out.SEND, t.key(2700L, HoldTalk.A))
        assertEquals(St.SENDING, t.st)
        assertEquals(Out.NOTE, t.sent(2900L, true))
        assertTrue(t.noteSent); assertEquals("need a healer", t.note)
        assertFalse("the guest goes back with the note", t.wantsGuest)
        assertEquals(Out.DROP, t.tick(2900L + HoldTalk.NOTE_MS))
        assertEquals(St.IDLE, t.st)

        val b = HoldTalk(); pending(b); b.tick(1400L); b.up(2000L); b.heard(2600L, "lol")
        assertEquals(Out.DROP, b.key(2700L, HoldTalk.B)); assertEquals(St.IDLE, b.st)

        val x = HoldTalk(); pending(x); x.tick(1400L); x.up(2000L); x.heard(2600L, "lol")
        assertEquals(Out.DROP, x.key(2700L, "PAD3")); assertEquals(St.IDLE, x.st)

        val f = HoldTalk(); pending(f); f.tick(1400L); f.up(2000L); f.heard(2600L, "lol")
        assertEquals(Out.NONE, f.tick(2600L + HoldTalk.LINE_MS - 1))
        assertEquals("unsent, it fades", Out.DROP, f.tick(2600L + HoldTalk.LINE_MS))
    }

    @Test fun sendStraightAwaySendsAtOnceAndARefusalSaysWhy() {
        val t = HoldTalk(); pending(t, now = true); t.tick(1400L); t.up(2000L)
        assertEquals(Out.SEND, t.heard(2600L, "omw"))
        assertEquals(Out.NOTE, t.sent(2700L, false, "Not sent: You are not in a party"))
        assertFalse(t.noteSent); assertEquals("Not sent: You are not in a party", t.note)
    }

    @Test fun noVoiceModelGivesTheHintAndRecordsNothing() {
        val t = HoldTalk(); pending(t)
        assertEquals(Out.LISTEN, t.tick(1400L))
        assertEquals(Out.NOTE, t.noVoice(1400L, HoldTalk.NO_VOICE))
        assertEquals(HoldTalk.NO_VOICE, t.note); assertFalse(t.listening)
        assertFalse("still held: the word is still the hold's", t.word(1450L))
        assertEquals(Out.NONE, t.up(1800L))
        assertFalse(t.word(1900L))
    }

    @Test fun nothingHeardAndBWhileWorking() {
        val t = HoldTalk(); pending(t); t.tick(1400L); t.up(2000L)
        assertEquals(Out.NOTE, t.heard(2500L, "  "))
        assertEquals(HoldTalk.NOT_HEARD, t.note)
        val b = HoldTalk(); pending(b); b.tick(1400L); b.up(2000L)
        assertEquals(Out.NONE, b.key(2100L, HoldTalk.A))
        assertEquals("B gives the words up", Out.CANCEL, b.key(2100L, HoldTalk.B))
        assertEquals(Out.NONE, b.heard(2400L, "late words"))           // a late answer of a hold given up: nothing
    }

    @Test fun aRoomChangeEndsTheHoldNothingSent() {
        val t = HoldTalk(); pending(t); t.tick(1400L)
        assertEquals(Out.CANCEL, t.reset()); assertEquals(St.IDLE, t.st)
        assertEquals(Out.NONE, HoldTalk().reset())
    }

    @Test fun wordsChatCanCarryNeverACommand() {
        assertEquals("dance", HoldTalk.body("/dance"))
        assertEquals("cafe ole", HoldTalk.body("café olé"))
        assertTrue(HoldTalk.body("x".repeat(400)).length <= ChatStream.MAX)
        assertEquals(HoldTalk.MIC_OFF, HoldTalk.problem(Voice.E_PERMISSIONS))
        assertEquals(HoldTalk.NOT_HEARD, HoldTalk.problem(Voice.E_SPEECH_TIMEOUT))
        assertEquals(0f, HoldTalk.level(-2f)); assertEquals(1f, HoldTalk.level(10f))
    }

    // ---------------------------------------------------------------- Left is the toolbar's Chat only where the router says so

    private val bolt = PadMapModel.Ref(403, "Lightning Bolt", 136048)
    private val chatCmd = PadMapModel.Ref(null, "DS Chat", 134332, layer = "command", key = HoldTalk.CHAT_KEY)
    private fun map(left: PadMapModel.Slot?, fight: Boolean): PadMapModel.Map =
        PadMapModel.Map("m164", 1, 1, fight, listOf(PadMapModel.PadSet(1, listOfNotNull(left))), null, null, emptyList(),
            fightSwitch = fight, faceCc = true)
    private val leftFight = PadMapModel.Slot(1, 1, Kind.COMBAT, null, null, 0, combat = bolt, ov = true)
    private val leftPlain = PadMapModel.Slot(1, 1, Kind.SPELL, 331, "Healing Wave", 136052)

    @Test fun leftIsChatWhereTheRouterRunsChat() {
        assertTrue("the toolbar, out of a fight", HoldTalk.leftIsChat(map(leftFight, false), true, mapOf(1 to "chat"), false))
        assertTrue("the router's default place", HoldTalk.leftIsChat(map(leftPlain, false), true, emptyMap(), false))
        assertFalse("toolbar off", HoldTalk.leftIsChat(map(leftPlain, false), false, mapOf(1 to "chat"), false))
        assertFalse("a fight with an In-combat spell on Left casts it", HoldTalk.leftIsChat(map(leftFight, true), true, mapOf(1 to "chat"), true))
        assertTrue("a fight with nothing In combat on Left keeps the toolbar", HoldTalk.leftIsChat(map(leftPlain, true), true, null, true))
        assertTrue("the DS Chat command as Left's In-combat entry",
            HoldTalk.leftIsChat(map(leftPlain.copy(kind = Kind.COMBAT, combat = chatCmd, ov = true), true), false, null, true))
        assertTrue("B gave it back: the toolbar again", HoldTalk.leftIsChat(map(leftFight, false), true, null, false))
        assertFalse("another place on Left", HoldTalk.leftIsChat(map(leftPlain, false), true, mapOf(1 to "map"), false))
    }

    // ---------------------------------------------------------------- the held recorder

    @Test fun aHeldRecordingStopsOnlyOnTheRelease() {
        for (step in Voice.Vad.Step.values()) assertFalse("held: $step", HoldTalk.stopsOn(true, step))
        assertTrue(HoldTalk.stopsOn(false, Voice.Vad.Step.STOP))
        assertTrue(HoldTalk.stopsOn(false, Voice.Vad.Step.NO_SPEECH))
        assertFalse(HoldTalk.stopsOn(false, Voice.Vad.Step.GO))
        assertTrue(HoldTalk.heardSomething(1200L, -30f))
        assertFalse("a silent room", HoldTalk.heardSomething(3000L, -70f))
        assertFalse("a slip of the finger", HoldTalk.heardSomething(100L, -20f))
    }

    // ---------------------------------------------------------------- the host's wiring (read from the source)

    @Test fun theHostWiresTheHoldWithoutTouchingTheGame() {
        val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
            .map { File(it) }.first { it.isDirectory }
        val h = File(src, "CompanionHost.kt").readText()
        // the router's word opens the Chat room only as the hold says
        assertTrue(h.contains("\"chat\" -> if (holdTalk.word(SystemClock.uptimeMillis()) && room != Room.CHAT) show(Room.CHAT)"))
        // the Hub's same-click chat hold is the hold's own (no refree while it lasts)
        assertTrue(h.contains("\"chat\" -> room == Room.CHAT || holdTalk.wantsGuest"))
        // the hold's presses come first, before the Chat room's own registration
        assertTrue(h.indexOf("DirectPad.Reg(\"talk\"") in 0 until h.indexOf("DirectPad.Reg(\"chat\""))
        // the gate: the toolbar's Chat under the router's fight rule
        // movable Home: the toolbar's Chat key (Left, or Right after a swap) by the same rule (HoldTalk.chatKey)
        assertTrue(h.contains("HoldTalk.chatKey(lastPadMap, lastDpad?.want == true && castView.roomBarOn, lastDpad?.dirs, homeView.dpadFight())"))
        // every send through ChatView's path
        assertTrue(h.contains("chatView.talkSend(holdTalk.words, talkTabs.now()"))
        assertFalse("no game key from the hold", Regex("GameInput\\.key\\([^)]*talk").containsMatchIn(h))
    }

    /** fix round 2: hold to talk arms D-pad Right ONLY when the toolbar's own `dirs` put the chat there (the mirrored layout, the
     *  Settings row's arrow); a DS Chat command on Right's In-combat entry or on the bare Right never arms it (1514: Left only) */
    @Test fun rightIsTheTalkKeyOnlyWhenTheToolbarMirrorsTheChat() {
        val normal = mapOf(1 to "chat", 2 to "radial", 3 to "map", 4 to "home")
        val mirrored = mapOf(1 to "map", 2 to "radial", 3 to "chat", 4 to "home")
        val rightChatFight = PadMapModel.Slot(3, 3, Kind.COMBAT, null, null, 0, combat = chatCmd, ov = true)
        val rightChatBare = PadMapModel.Slot(3, 3, Kind.COMMAND, null, "DS Chat", 134332, key = HoldTalk.CHAT_KEY)
        fun two(a: PadMapModel.Slot, b: PadMapModel.Slot, fight: Boolean) =
            PadMapModel.Map("m164", 1, 1, fight, listOf(PadMapModel.PadSet(1, listOf(a, b))), null, null, emptyList(), fightSwitch = fight, faceCc = true)
        // (a) the toolbar on, a fight: Left's In-combat entry a spell, Right's the DS Chat command -> no hold (Left casts, Right runs DS Chat)
        assertNull(HoldTalk.chatKey(two(leftFight, rightChatFight, true), true, normal, true))
        // (b) the toolbar off, DS Chat on the bare Right -> no hold on Right
        assertNull(HoldTalk.chatKey(map(rightChatBare, false), false, null, false))
        // the mirrored toolbar: Right (out of a fight, and in one with nothing In combat on Right)
        assertEquals(HoldTalk.RIGHT_KEY, HoldTalk.chatKey(map(leftPlain, false), true, mirrored, false))
        assertEquals(HoldTalk.RIGHT_KEY, HoldTalk.chatKey(map(leftPlain, true), true, mirrored, true))
        // mirrored, but a fight with an In-combat spell on Right: no hold (as Left's spell in 1514)
        val rightSpell = PadMapModel.Slot(3, 3, Kind.COMBAT, null, null, 0, combat = bolt, ov = true)
        assertNull(HoldTalk.chatKey(map(rightSpell, true), true, mirrored, true))
        // 1514's Left rules unchanged
        assertEquals(HoldTalk.LEFT_KEY, HoldTalk.chatKey(map(leftPlain, false), true, normal, false))
        assertEquals(HoldTalk.LEFT_KEY, HoldTalk.chatKey(map(leftPlain.copy(kind = Kind.COMBAT, combat = chatCmd, ov = true), true), false, null, true))
        assertNull(HoldTalk.chatKey(map(leftFight, true), true, normal, true))
        // the key and the Settings row's arrow agree
        for (d in listOf(normal, mirrored, null)) assertEquals(HoldTalk.chatArrowRight(d), HoldTalk.chatKey(map(leftPlain, false), true, d, false) == HoldTalk.RIGHT_KEY)
    }
}
