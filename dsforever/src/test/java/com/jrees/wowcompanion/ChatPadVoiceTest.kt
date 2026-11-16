package com.jrees.wowcompanion

import com.jrees.wowcompanion.ChatPad.Act
import com.jrees.wowcompanion.ChatPad.Board
import com.jrees.wowcompanion.ChatPad.Focus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X IN THE CHAT ROOM WITH VOICE (the user's pick 2026-11-16, D3a: "X starts voice everywhere in Chat; from a chat line, the cursor jumps
 * to the message bar. Inside the open chat keyboard, X still flips to quick chat, and D-pad Down from the bar also opens quick chat").
 * ChatPad's rules, off the device; ChatView opens the keyboard's voice page on Act.Voice.
 */
class ChatPadVoiceTest {
    private fun scene(board: Board = Board.NONE, voice: Boolean = true, lines: Int = 5, selected: Int? = null, links: Int = 0,
                      menu: Boolean = false, view: Boolean = false) =
        ChatPad.Scene(lines = lines, selected = selected, links = links, board = board, menu = menu, view = view, voice = voice)

    @Test fun xOnTheBarIsVoice() {
        val p = ChatPad()
        p.show(scene())
        assertEquals(Focus.Field, p.focus)
        assertEquals(listOf<Act>(Act.Voice), p.press("PAD3", scene()))
        assertEquals(Focus.Field, p.focus)
    }

    @Test fun xOnALineJumpsToTheBar() {
        val p = ChatPad()
        p.show(scene())
        p.press("PADDUP", scene())                                  // up into the log: the newest line
        assertTrue(p.focus is Focus.Line)
        assertEquals(listOf<Act>(Act.Voice), p.press("PAD3", scene()))
        assertEquals(Focus.Field, p.focus)
        // a selected line's link
        p.focus = Focus.Link(2, 0)
        assertEquals(listOf<Act>(Act.Voice), p.press("PAD3", scene(selected = 2, links = 2)))
        assertEquals(Focus.Field, p.focus)
    }

    @Test fun xClosesTheMenuOrTheViewOnTheWay() {
        val p = ChatPad()
        p.show(scene())
        p.press("PADBACK", scene())                                 // Select: the filter menu
        assertEquals(listOf(Act.Menu(false), Act.Voice), p.press("PAD3", scene(menu = true)))
        assertEquals(Focus.Field, p.focus)
        p.focus = Focus.Link(1, 0)
        assertEquals(listOf(Act.CloseView, Act.Voice), p.press("PAD3", scene(selected = 1, links = 1, view = true)))
    }

    @Test fun insideTheBoardXStillTurnsItsPage() {
        val p = ChatPad()
        p.show(scene(board = Board.QUICK))
        assertEquals(listOf<Act>(Act.Board(Board.KEYS)), p.press("PAD3", scene(board = Board.QUICK)))
        p.focus = Focus.Field
        assertEquals(listOf<Act>(Act.Board(Board.QUICK)), p.press("PAD3", scene(board = Board.KEYS)))
    }

    @Test fun voiceOffKeepsTheOldX() {
        val p = ChatPad()
        p.show(scene(voice = false))
        assertEquals(listOf<Act>(Act.Board(Board.QUICK)), p.press("PAD3", scene(voice = false)))
        assertTrue(p.focus is Focus.Quick)
    }

    @Test fun downFromTheBarOpensQuickChat() {
        val p = ChatPad()
        p.show(scene())
        assertEquals(listOf<Act>(Act.Board(Board.QUICK)), p.press("PADDDOWN", scene()))
        assertEquals(Focus.Quick(0), p.focus)
        // with quick chat already up, down from the bar goes into its tiles (as before), no second board act
        p.focus = Focus.Field
        assertEquals(listOf<Act>(Act.Glow), p.press("PADDDOWN", scene(board = Board.QUICK)))
        assertEquals(Focus.Quick(0), p.focus)
        // with the menu open, down walks the menu
        val m = ChatPad()
        m.show(scene())
        m.press("PADBACK", scene())
        val acts = m.press("PADDDOWN", scene(menu = true))
        assertFalse(acts.any { it is Act.Board })
        assertTrue(m.focus is Focus.Menu)
    }

    @Test fun bStillLeadsHomeFromTheBar() {
        val p = ChatPad()
        p.show(scene())
        assertTrue(p.leadsHome(scene()))
        assertFalse(p.leadsHome(scene(board = Board.KEYS)))         // the voice page is the keyboard's board: B closes it first
        assertEquals(listOf<Act>(Act.Leave), p.press("PAD2", scene()))
    }

    @Test fun theBoardsKnowTheirXKey() {
        assertTrue(ChatKeys(ChatKeys.Layout.DEFAULT).hasSwap)
        assertFalse(ChatKeys(ChatKeys.Layout.NO_SWAP).hasSwap)
        assertTrue(ChatKeys(ChatKeys.Layout.SIGN_IN, pipe = true).hasSwap)
        // X on a board is the room's ("Pass"): the voice board takes it only where there is no Quick chat key
        assertEquals(ChatKeys.Act.Pass, ChatKeys(ChatKeys.Layout.NO_SWAP).press("PAD3", ""))
    }

    @Test fun theCopysPaceIsKnown() {
        var t = 1000L
        val handed = ArrayList<List<Pair<String, Int>>>()
        val s = ChatStream({ cells, _ -> handed.add(cells); true }, now = { t }, field = true)
        assertEquals(0L, s.copyLeftMs())
        s.setBody("abc")
        // one batch of three letters: the rest before it, two gaps inside it
        assertEquals(ChatStream.REST_MS + 2 * ChatStream.BATCH_GAP_MS, s.copyLeftMs())
        t += 100
        assertEquals(ChatStream.REST_MS + 2 * ChatStream.BATCH_GAP_MS - 100, s.copyLeftMs())
        s.setBody("abcd")                                           // more queued behind the first
        assertTrue(s.copyLeftMs() > ChatStream.REST_MS + 2 * ChatStream.BATCH_GAP_MS - 100)
        t += 10_000
        assertEquals(0L, s.copyLeftMs())
    }
}
