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

    // the user, 2026-10-16: "on the left side it should be Track Pad, and on the right it should be Keyboard"
    @Test fun dpadLeftOpensTheTrackPadOnItsRelease() {
        val f = HomePauseModel.Flow()
        assertEquals(HomePauseModel.Act.None, f.dpad(3, down = true))
        assertEquals(HomePauseModel.Act.Pick(HomePauseModel.TRACKPAD), f.dpad(3, down = false))
        assertEquals("trackpad", HomePauseModel.TRACKPAD.key)
        assertEquals(HomePauseModel.Kind.HOME, HomePauseModel.TRACKPAD.kind)
    }

    @Test fun dpadRightOpensTheKeyboardOnItsRelease() {
        val f = HomePauseModel.Flow()
        assertEquals(HomePauseModel.Act.None, f.dpad(1, down = true))
        assertEquals(HomePauseModel.Act.Pick(HomePauseModel.KEYBOARD), f.dpad(1, down = false))
        assertEquals("keyboard", HomePauseModel.KEYBOARD.key)
    }

    @Test fun everyDirectionHasItsDpadSlot() =
        assertEquals(listOf(0, 1, 2, 3), HomePauseModel.DPAD_OPTIONS.map { it.second }.sorted())

    @Test fun aLeftReleaseWithNoPressDoesNothing() = assertEquals(HomePauseModel.Act.None, HomePauseModel.Flow().dpad(3, down = false))

    @Test fun upAndDownAreUnchanged() {
        val f = HomePauseModel.Flow()
        f.dpad(2, true); assertEquals(HomePauseModel.Act.Sit, f.dpad(2, false))
        f.dpad(0, true); assertEquals(HomePauseModel.Act.None, f.dpad(0, false)); assertEquals(HomePauseModel.EMOTE_PAGE, f.page)
    }

    @Test fun theToolHasItsOwnGuestAfterRadial() {
        assertEquals(listOf("quests", "map", "settings", "chat", "bar", "home", "radial", "tool"), GuestPad.IDS)   // hold 22 / free 23
    }
}

/** 1.0.8-test7 (the user: "the Keyboard/ Trackpad button doesnt do anything"): the wheel's view passes every option's D-pad button on */
class PauseDpadTest {
    @Test fun everyDirectionTheModelActsOnIsPassedOnByTheView() {
        val passed = HomePauseModel.DPAD_OPTIONS.map { it.second }.toSet()
        for (dir in 0..3) {
            val f = HomePauseModel.Flow()
            f.dpad(dir, down = true)
            val acts = f.dpad(dir, down = false) != HomePauseModel.Act.None || f.page == HomePauseModel.EMOTE_PAGE
            if (acts) assertTrue("direction $dir acts in the model but the view never passes it", dir in passed)
        }
    }
    @Test fun leftIsTheReadersDpadLeft() =
        assertTrue(HomePauseModel.DPAD_OPTIONS.contains(PadInput.SLOT_DPAD_LEFT to 3))
}
