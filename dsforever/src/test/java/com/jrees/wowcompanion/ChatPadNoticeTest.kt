package com.jrees.wowcompanion

import com.jrees.wowcompanion.ChatPad.Act
import com.jrees.wowcompanion.ChatPad.Board
import com.jrees.wowcompanion.ChatPad.Focus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * chat40, THE WORLD-REFRESH "!" ON THE CONTROLLER (the user, 2026-11-16: "the ! notice part should be a button to trigger the refresh"):
 * in the chat module and the Chat room the notice stands over the log; its "!" is the top stop. ChatPad's rules, off the device.
 */
class ChatPadNoticeTest {
    private fun scene(notice: Boolean = true, lines: Int = 3, board: Board = Board.NONE, menu: Boolean = false) =
        ChatPad.Scene(lines = lines, board = board, menu = menu, notice = notice)

    @Test fun upFromTheTopLineIsTheBangAndAPressesIt() {
        val p = ChatPad()
        p.show(scene())
        repeat(3) { p.press("PADDUP", scene()) }                  // the bar -> the newest line -> ... -> the top line
        assertEquals(Focus.Line(0), p.focus)
        p.press("PADDUP", scene())
        assertEquals(Focus.Notice, p.focus)
        assertEquals(listOf<Act>(Act.NoticeGo), p.press("PAD1", scene()))
        assertEquals(Focus.Notice, p.focus)                        // the cursor stays (the game's answer ends the notice)
        p.press("PADDUP", scene())
        assertEquals(Focus.Notice, p.focus)                        // nothing above it
        p.press("PADDDOWN", scene())
        assertEquals(Focus.Line(0), p.focus)
    }

    @Test fun noNoticeNoStop() {
        val p = ChatPad()
        p.show(scene(notice = false))
        repeat(5) { p.press("PADDUP", scene(notice = false)) }
        assertEquals(Focus.Line(0), p.focus)
    }

    @Test fun anEmptyLogGoesStraightUpToIt() {
        val p = ChatPad()
        p.show(scene(lines = 0))
        p.press("PADDUP", scene(lines = 0))
        assertEquals(Focus.Notice, p.focus)
        p.press("PADDDOWN", scene(lines = 0))
        assertEquals(Focus.Field, p.focus)
    }

    @Test fun theNoticeGoingMovesTheCursorOn() {
        val p = ChatPad()
        p.focus = Focus.Notice
        p.settle(scene(notice = false))
        assertEquals(Focus.Line(0), p.focus)
        p.focus = Focus.Notice
        p.settle(scene(notice = false, lines = 0))
        assertEquals(Focus.Field, p.focus)
        // a board up covers the log: not a stop then
        p.focus = Focus.Notice
        p.settle(scene(board = Board.KEYS))
        assertFalse(p.focus == Focus.Notice)
    }

    @Test fun yAndBAsOnALine() {
        val p = ChatPad()
        p.focus = Focus.Notice
        p.press("PAD4", scene())
        assertEquals(Focus.Field, p.focus)
        p.focus = Focus.Notice
        assertTrue(p.press("PAD2", scene()).contains(Act.Leave))
        p.focus = Focus.Notice
        assertEquals(listOf<Act>(Act.Glow), p.press("PADDLEFT", scene()))      // left / right do nothing on it (no type change)
        assertEquals(Focus.Notice, p.focus)
    }
}
