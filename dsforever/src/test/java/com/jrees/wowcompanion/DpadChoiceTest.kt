package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** issue #136 (2026-11-11): "Cannot disable DsWoW toolbar from settings" - the toolbar choice's decision ([DpadChoice]) */
class DpadChoiceTest {
    private fun decide(want: Boolean?, stored: Boolean?, pending: Boolean = false, owed: Boolean = false, first: Boolean = false,
                       fight: Boolean = false, since: Long? = null) = DpadChoice.decide(want, stored, pending, owed, first, fight, since)

    @Test fun anOwedPromptNeverOverridesTheSettingsChoice() {
        // the old bug: Settings Off left "owed" set, the next padmap ran the prompt and its default ON tap. A choice clears owed and is
        // pending: whatever the flags say, a pending choice is re-sent, never the prompt
        val s = decide(want = true, stored = false, pending = true, owed = true)
        assertTrue(s is DpadChoice.Act.Send && !s.on)
        assertTrue(decide(want = null, stored = false, pending = true, owed = true) is DpadChoice.Act.Send)
    }

    @Test fun aPendingChoiceIsResentUntilTheGameSaysIt() {
        // the game was closed (the tap refused): the next run's first word is the add-on's OLD answer
        val s = decide(want = true, stored = false, pending = true, first = true)
        assertTrue(s is DpadChoice.Act.Send && !s.on)
        // a send just went: wait for the add-on's answer, do not tap again
        assertEquals(DpadChoice.Act.Wait, decide(want = true, stored = false, pending = true, since = 1_000L))
        assertTrue(decide(want = true, stored = false, pending = true, since = DpadChoice.RESEND_MS) is DpadChoice.Act.Send)
        // never in a fight (the add-on refuses there)
        assertEquals(DpadChoice.Act.Wait, decide(want = true, stored = false, pending = true, fight = true))
        // the game has it: no longer pending
        assertEquals(DpadChoice.Act.Confirm, decide(want = false, stored = false, pending = true, since = 100L))
    }

    @Test fun theAppsChoiceWinsAtARunsFirstWord() {
        // the game's saved file lost the Off (no clean exit): the app's kept choice goes back at the first word of the run
        val s = decide(want = true, stored = false, first = true)
        assertTrue(s is DpadChoice.Act.Send && !s.on && s.why.contains("saved file"))
        // a game whose add-on has no answer gets the remembered one (once a run, as before)
        val n = decide(want = null, stored = true, first = true)
        assertTrue(n is DpadChoice.Act.Send && n.on)
        assertEquals(DpadChoice.Act.None, decide(want = null, stored = true, first = false))
        // a fight at the first word: later, not lost
        assertEquals(DpadChoice.Act.Wait, decide(want = true, stored = false, first = true, fight = true))
        // the same answer: nothing to send
        assertEquals(DpadChoice.Act.None, decide(want = false, stored = false, first = true))
    }

    @Test fun inARunTheGamesOwnSwitchIsFollowed() {
        // the in-game Book's switch (or an answer in the other game) after the run's first word: kept as the choice
        assertEquals(DpadChoice.Act.Adopt(true), decide(want = true, stored = false))
        assertEquals(DpadChoice.Act.Adopt(false), decide(want = false, stored = null, first = true))
        assertEquals(DpadChoice.Act.None, decide(want = true, stored = true))
    }

    @Test fun onlyACharacterNeverAskedAnywhereSeesThePrompt() {
        assertEquals(DpadChoice.Act.Ask, decide(want = null, stored = null, first = true))
        // b139: our own default ON tap is not an answer while the prompt is owed
        assertEquals(DpadChoice.Act.Ask, decide(want = true, stored = null, owed = true))
    }

    @Test fun aPreUpdateSettingsOffIsThePlayersChoice() {
        // review of issue #136 (2026-11-11): the old Settings row kept the Off and left owed set (the bug); after the update that state
        // (no pend_ flag) must be re-sent as the player's choice, never the prompt and its default ON tap
        val s = decide(want = true, stored = false, owed = true)
        assertTrue(s is DpadChoice.Act.Send && !s.on)
        val f = decide(want = true, stored = false, owed = true, first = true)
        assertTrue(f is DpadChoice.Act.Send && !f.on)
        assertTrue(decide(want = null, stored = false, owed = true) is DpadChoice.Act.Send)
        // the game already says it: confirmed (the host clears pend_ and owed_), never asked
        assertEquals(DpadChoice.Act.Confirm, decide(want = true, stored = true, owed = true, first = true))
        assertEquals(DpadChoice.Act.Confirm, decide(want = false, stored = false, owed = true))
        // never in a fight, nor twice inside the resend gap
        assertEquals(DpadChoice.Act.Wait, decide(want = true, stored = false, owed = true, fight = true))
        assertEquals(DpadChoice.Act.Wait, decide(want = true, stored = false, owed = true, since = 1_000L))
        // the Settings row shows the Off at once
        assertEquals(false, DpadChoice.shown(addonWant = true, stored = false, pending = false, owed = true))
    }

    @Test fun theSettingsRowShowsThePendingChoice() {
        assertEquals(false, DpadChoice.shown(addonWant = true, stored = false, pending = true))
        assertEquals(true, DpadChoice.shown(addonWant = true, stored = false, pending = false))
        assertEquals(null, DpadChoice.shown(addonWant = null, stored = null, pending = true))
    }
}
