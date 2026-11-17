package com.jrees.wowcompanion

import com.jrees.wowcompanion.HoldTalk.Out
import com.jrees.wowcompanion.HoldTalk.St
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HOLD TO TALK'S CHAT TABS (the user, 2026-11-17: "on the push to talk, I want to add some pop up tabs to press L or R to change what
 * chat that its for. lets give it its own pop up UI around it."), off-device: the tabs start on the last used chat, LB / RB and D-pad Up /
 * Down move one and wrap round at the ends (the user's picks, 2026-11-17: "L/R plus D-pad Up/Down", "Wrap around"), a finger picks one, a chat that went away falls back to Say; HoldTalk gives LB / RB
 * as tab moves while held, while working and while the words wait (no longer dropping the words); the host's wiring (read from source).
 */
class TalkTabsTest {
    private val say = TalkTabs.Tab("SAY", "Say", 1)
    private val party = TalkTabs.Tab("PARTY", "Party", 2)
    private val guild = TalkTabs.Tab("GUILD", "Guild", 3)
    private val yell = TalkTabs.Tab("YELL", "Yell", 4)
    private val trade = TalkTabs.Tab("CHANNEL:2", "Trade", 5)
    private val tell = TalkTabs.Tab("WHISPER", "→ Mirelle", 6, "Mirelle")
    private val all = listOf(say, party, guild, yell, trade, tell)

    @Test fun theTabsStartOnTheLastUsedChat() {
        val t = TalkTabs()
        assertNull(t.now())
        t.seed(all, "PARTY")
        assertEquals(party, t.now())
        t.seed(all, "RAID")                                       // not usable now: the first tab (Say)
        assertEquals(say, t.now())
        t.seed(all, "WHISPER")
        assertEquals("Mirelle", t.now()!!.whisper)
    }

    @Test fun lbAndRbMoveOneTabAndWrapAroundAtTheEnds() {
        // the user's pick, 2026-11-17: "Wrap around"
        val t = TalkTabs(); t.seed(all, "SAY")
        assertTrue("both arrows: it wraps", t.canL && t.canR)
        assertTrue("past the first: round to the last", t.step(-1))
        assertEquals(tell, t.now())
        assertTrue("past the last: round to the first", t.step(1))
        assertEquals(say, t.now())
        assertTrue(t.step(1)); assertTrue(t.step(1))
        assertEquals(guild, t.now())
        repeat(all.size) { t.step(1) }
        assertEquals("a whole turn comes back", guild, t.now())
        assertTrue(t.step(-1)); assertEquals(party, t.now())
        val one = TalkTabs(); one.seed(listOf(say), "SAY")
        assertFalse("one tab: nothing to move to", one.step(1) || one.step(-1) || one.canL || one.canR)
        assertEquals(say, one.now())
    }

    @Test fun aFingerPicksATab() {
        val t = TalkTabs(); t.seed(all, "SAY")
        assertTrue(t.pick("CHANNEL:2")); assertEquals(trade, t.now())
        assertFalse("the chosen one again", t.pick("CHANNEL:2"))
        assertFalse("not a tab", t.pick("OFFICER")); assertEquals(trade, t.now())
    }

    @Test fun aChatThatWentAwayFallsBackToSay() {
        val t = TalkTabs(); t.seed(all, "GUILD")
        t.refresh(all.filter { it.key != "PARTY" })               // left the group: Guild stays chosen
        assertEquals(guild, t.now())
        t.refresh(listOf(say, yell))                              // left the guild too
        assertEquals(say, t.now())
        t.clear(); assertNull(t.now())
    }

    // ---------------------------------------------------------------- HoldTalk: LB / RB are the tabs' keys

    private fun held(): HoldTalk {
        val h = HoldTalk(); assertTrue(h.down(1000L, false)); assertEquals(Out.LISTEN, h.tick(1000L + HoldTalk.HOLD_MS)); return h
    }

    @Test fun lbAndRbMoveTheTabWhileHeldWorkingAndWaiting() {
        val h = held()
        assertEquals(Out.TAB_R, h.key(1500L, HoldTalk.RB))
        assertEquals(Out.TAB_L, h.key(1550L, HoldTalk.LB))
        assertEquals(St.HELD, h.st)
        assertEquals(Out.STOP, h.up(2000L))
        assertEquals(Out.TAB_R, h.key(2100L, HoldTalk.RB))
        assertEquals(St.WORKING, h.st)
        assertEquals(Out.SHOW, h.heard(2500L, "on my way"))
        // the words wait: LB / RB change the chat and keep them (before: any other button dropped them)
        assertEquals(Out.TAB_R, h.key(2500L + HoldTalk.LINE_MS - 100L, HoldTalk.RB))
        assertEquals(St.LINE, h.st); assertEquals("on my way", h.words)
        assertEquals("the wait starts again at the move", Out.NONE, h.tick(2500L + HoldTalk.LINE_MS + 200L))
        assertEquals(Out.SEND, h.key(2500L + HoldTalk.LINE_MS + 300L, HoldTalk.A))
        assertEquals(St.SENDING, h.st)
        assertEquals("sending: nothing moves", Out.NONE, h.key(9000L, HoldTalk.RB))
    }

    @Test fun aTouchedTabRestartsTheWait() {
        val h = held(); h.up(2000L); h.heard(2500L, "hi")
        h.tabTouched(2500L + HoldTalk.LINE_MS - 50L)
        assertEquals(Out.NONE, h.tick(2500L + HoldTalk.LINE_MS + 50L))
        assertEquals(St.LINE, h.st)
    }

    @Test fun bStillDropsTheWaitingWords() {
        val h = held(); h.up(2000L); h.heard(2500L, "hi")
        assertEquals(Out.DROP, h.key(2600L, HoldTalk.B))
        assertEquals(St.IDLE, h.st)
    }

    @Test fun dpadUpAndDownMoveTheTabToo() {
        // the user's pick, 2026-11-17: "L/R plus D-pad Up/Down" (they do not replace L / R)
        val h = held()
        assertEquals(Out.TAB_L, h.key(1500L, HoldTalk.UP))
        assertEquals(Out.TAB_R, h.key(1520L, HoldTalk.DOWN))
        assertEquals(Out.TAB_L, h.key(1540L, HoldTalk.LB))
        assertEquals(St.HELD, h.st)
        assertEquals(Out.STOP, h.up(2000L))
        assertEquals("while worked out", Out.TAB_R, h.key(2100L, HoldTalk.DOWN))
        assertEquals(Out.SHOW, h.heard(2500L, "inc"))
        // while the words wait: Up / Down change the chat, keep the words and restart the wait (before: any other button dropped them)
        assertEquals(Out.TAB_L, h.key(2500L + HoldTalk.LINE_MS - 100L, HoldTalk.UP))
        assertEquals(St.LINE, h.st); assertEquals("inc", h.words)
        assertEquals(Out.NONE, h.tick(2500L + HoldTalk.LINE_MS + 200L))
        assertEquals("D-pad Right still drops them", Out.DROP, h.key(9000L, "PADDRIGHT"))
        assertEquals(DirectPadKeys.SLOT_KEYS[1], HoldTalk.UP)
        assertEquals(DirectPadKeys.SLOT_KEYS[3], HoldTalk.DOWN)
    }

    @Test fun theShouldersAreTheAppReadersNames() {
        assertEquals(DirectPadKeys.LB, HoldTalk.LB)
        assertEquals(DirectPadKeys.RB, HoldTalk.RB)
    }

    // ---------------------------------------------------------------- the host's wiring (read from the source)

    @Test fun theHostWiresTheTabs() {
        val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
            .map { File(it) }.first { it.isDirectory }
        val h = File(src, "CompanionHost.kt").readText()
        // LB / RB reach the hold from the app's own reader: the "talk" registration lists them (lr) and comes first
        val reg = h.substring(h.indexOf("DirectPad.Reg(\"talk\""))
        assertTrue(reg.substringBefore("\n").contains("DirectPadKeys.EIGHT + lr"))
        assertTrue(h.contains("val lr = setOf(DirectPadKeys.LB, DirectPadKeys.RB)"))
        // the send goes to the chosen tab
        assertTrue(h.contains("chatView.talkSend(holdTalk.words, talkTabs.now()?.let { it.key to it.whisper })"))
        // the tabs start on the last used chat when the hold starts
        assertTrue(h.contains("talkTabs.seed(chatView.talkTabs(), chatView.talkStart())"))
        // the LB party picture never shows while the hold has the pad
        assertTrue(Regex("private fun lbEval\\(\\) \\{\\s+val free = room == Room.HOME && !holdTalk.takesPad").containsMatchIn(h))
        // the chat guest (Quests.lua's EXTRA) holds both shoulders: RB / LB never reach the game's own binding through the capture
        val q = File(src, "../../../../assets/companion/addons/WowCompanionQuests/Quests.lua").readText()
        assertTrue(Regex("chat = \\{ \"PADLSHOULDER\", \"PADRSHOULDER\"").containsMatchIn(q))
        val c = File(src, "ChatView.kt").readText()
        // the hold's send keeps its chat as the last used one
        assertTrue(c.contains("ChatStream.Event.Sent -> { talkTo?.let { (t, w) -> keepUsed(t, w) }; talkEnd(true, \"\") }"))
    }
}
