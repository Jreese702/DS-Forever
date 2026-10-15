package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** f-trackpad-keyboard (a player: "cannot name his edits"; the user: a Keyboard & Trackpad on the pause wheel's D-pad Left), off-device */
class TypeToolTest {

    // ---------------------------------------------------------------- the key-safety ruling: only these ever reach the game

    @Test fun lettersDigitsAndSpaceAreAllowed() {
        for (c in "abcxyzABCXYZ0189 ") assertTrue("'$c'", GameTyping.allowed(c))
    }

    @Test fun nothingElseIsEverTyped() {
        // punctuation, control and system characters (Escape, Tab, Enter as a character, Delete, NUL), anything outside ASCII letters
        val bad = "!@#%^&*()_+-=[]{};:,./<>?|`~".toList() +
            listOf('"', '\'', '\\', '$', '\t', '\n', '\r', 27.toChar(), 127.toChar(), 0.toChar(), 'é', 'ß', '€')
        for (c in bad) assertFalse("'${c.code}'", GameTyping.allowed(c))
    }

    @Test fun theOnlyNamedKeysAreEnterAndBackspace() {
        assertEquals("Enter", 36, GameTyping.X_ENTER)
        assertEquals("Backspace", 22, GameTyping.X_BACKSPACE)
        // never Alt (64 / 108), Ctrl (37 / 105), Escape (9), Tab (23), an F-key (67-76, 95, 96)
        val forbidden = setOf(64, 108, 37, 105, 9, 23) + (67..76) + setOf(95, 96)
        assertFalse(GameTyping.X_ENTER in forbidden); assertFalse(GameTyping.X_BACKSPACE in forbidden)
    }

    // ---------------------------------------------------------------- the keyboard's text -> the game's keys

    @Test fun typingAddsKeys() = assertEquals(GameTyping.Ops(0, "a"), GameTyping.diff("", "a"))
    @Test fun aWholeWordAtOnce() = assertEquals(GameTyping.Ops(0, "My UI 2"), GameTyping.diff("", "My UI 2"))
    @Test fun backspaceErases() = assertEquals(GameTyping.Ops(1, ""), GameTyping.diff("abc", "ab"))
    @Test fun aChangeInTheMiddleErasesBackToIt() = assertEquals(GameTyping.Ops(2, "Xc"), GameTyping.diff("abc", "aXc"))
    @Test fun aDisallowedCharacterIsSkipped() = assertEquals(GameTyping.Ops(0, "ab"), GameTyping.diff("", "a!b"))
    @Test fun clearingEverything() = assertEquals(GameTyping.Ops(5, ""), GameTyping.diff("Hello", ""))

    // ---------------------------------------------------------------- the entry: the pause wheel's D-pad Left

    @Test fun dpadLeftOpensTheToolOnItsRelease() {
        val f = HomePauseModel.Flow()
        assertEquals(HomePauseModel.Act.None, f.dpad(3, down = true))
        assertEquals(HomePauseModel.Act.Pick(HomePauseModel.TOOL), f.dpad(3, down = false))
        assertEquals("typetool", HomePauseModel.TOOL.key)
        assertEquals(HomePauseModel.Kind.HOME, HomePauseModel.TOOL.kind)
    }

    @Test fun aLeftReleaseWithNoPressDoesNothing() = assertEquals(HomePauseModel.Act.None, HomePauseModel.Flow().dpad(3, down = false))

    @Test fun upAndDownAreUnchangedAndRightStaysFree() {
        val f = HomePauseModel.Flow()
        f.dpad(2, true); assertEquals(HomePauseModel.Act.Sit, f.dpad(2, false))
        f.dpad(0, true); assertEquals(HomePauseModel.Act.None, f.dpad(0, false)); assertEquals(HomePauseModel.EMOTE_PAGE, f.page)
        val g = HomePauseModel.Flow()
        assertEquals(HomePauseModel.Act.None, g.dpad(1, true)); assertEquals(HomePauseModel.Act.None, g.dpad(1, false))
    }
}
