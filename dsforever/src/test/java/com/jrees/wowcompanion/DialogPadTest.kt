package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** the user's decision 1 (2026-11-11): pop-ups and loot rolls in a fight are answered on the bottom screen with the pad */
class DialogPadTest {
    private val two = listOf(DialogPad.B(1, true, 0), DialogPad.B(2, true, 1))

    @Test fun aAndBOnAPopup() {
        assertEquals(1, DialogPad.dialogButton("PAD1", two, delayed = false, quit = false))
        assertEquals(2, DialogPad.dialogButton("PAD2", two, delayed = false, quit = false))
        assertNull(DialogPad.dialogButton("PAD3", two, delayed = false, quit = false))
    }

    @Test fun neverADelayedFirstButtonNorExitNow() {
        assertNull(DialogPad.dialogButton("PAD1", two, delayed = true, quit = false))
        assertNull(DialogPad.dialogButton("PAD1", two, delayed = false, quit = true))
        assertEquals(2, DialogPad.dialogButton("PAD2", two, delayed = false, quit = true))
        assertNull(DialogPad.dialogButton("PAD2", listOf(DialogPad.B(1, true, 0), DialogPad.B(2, false, 1)), false, false))
        assertNull(DialogPad.dialogButton("PAD1", listOf(DialogPad.B(1, true, -1)), false, false))       // no cell: never a guess
    }

    /** int/1429 (combat-nav's pad answers x starter-prompt's dialog): the quest starter's prompt (Dialogs QUEST_START) is one more
     *  dialog - A = Start on its own cell, B = Not now - its cells the charter's shared Sign / Offer (idlg 28 / 29: the row is full at
     *  40 with the leave cells 33-39) */
    @Test fun theQuestStarterPromptWithThePad() {
        val qs = listOf(DialogPad.B(1, true, 28), DialogPad.B(2, true, 29))
        assertEquals(1, DialogPad.dialogButton("PAD1", qs, delayed = false, quit = QuestStartPrompt.WHICH == "QUIT"))
        assertEquals(2, DialogPad.dialogButton("PAD2", qs, delayed = false, quit = false))
        assertEquals(QuestStartPrompt.START, QuestStartPrompt.label(1, "x")); assertEquals(QuestStartPrompt.LATER, QuestStartPrompt.label(2, "x"))
        assertNull("a cell of the leave range is never the prompt's", qs.firstOrNull { it.cell in 33..39 })
    }

    @Test fun theRollKeys() {
        assertEquals("need", DialogPad.rollKind("PAD1", need = true, greed = true, transmog = false))
        assertNull(DialogPad.rollKind("PAD1", need = false, greed = true, transmog = false))
        assertEquals("greed", DialogPad.rollKind("PAD3", need = false, greed = true, transmog = true))
        assertEquals("transmog", DialogPad.rollKind("PAD3", need = false, greed = false, transmog = true))
        assertEquals("pass", DialogPad.rollKind("PAD2", need = false, greed = false, transmog = false))
    }

    @Test fun leftAndRightBetweenRolls() {
        val ids = listOf(7, 8, 9)
        assertEquals(9, DialogPad.rollMove("PADDRIGHT", ids, 8))
        assertEquals(9, DialogPad.rollMove("PADDRIGHT", ids, 9))         // no wrap
        assertEquals(7, DialogPad.rollMove("PADDLEFT", ids, 8))
        assertNull(DialogPad.rollMove("PAD1", ids, 8))
    }
}
