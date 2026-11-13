package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #195 ("Adaptative layout for action bars port to main screen"; the user 2026-11-12: a new choice "Nintendo" next to On / Off /
 * Dynamic for the top screen's action bar, DS Forever's Nintendo-layout bar REPLACING the stock one in the same spot). The choice is
 * the "hud" element's fourth mode: for the layout bit it reads as On (the riders stay: buffs, debuffs, XP), and the bar itself rides
 * its own absolute switch on "uisw" (Hub.lua hub45: NBAR_OFF 34 / NBAR_ON 35), wanted exactly while the mode is NINTENDO.
 */
class NintendoBarChoiceTest {
    private val cols = mapOf("PLAYER_SHOW" to 0, "PLAYER_HIDE" to 1, "TARGET_SHOW" to 2, "TARGET_HIDE" to 3, "NP_OFF" to 4, "NP_ON" to 5,
        "PRD_OFF" to 6, "PRD_ON" to 7, "PET_OFF" to 32, "PET_ON" to 33, "NBAR_OFF" to 34, "NBAR_ON" to 35)
    private val settled = mapOf("player" to true, "target" to true, "np" to true, "prd" to true, "pet" to true)
    private fun defaults(nbar: Boolean): (String) -> Boolean = { p ->
        when (p) { GameUi.SW_PLAYER, GameUi.SW_TARGET -> false; GameUi.SW_NBAR -> nbar; else -> true }
    }

    @Test fun theControllerBarsRowOffersNintendoAndNoOtherRowDoes() {
        assertEquals(listOf(UiMode.ON, UiMode.OFF, UiMode.DYNAMIC, UiMode.NINTENDO), UiElements.modesFor("hud"))
        assertFalse(UiMode.NINTENDO in UiElements.modesFor("minimap"))
        assertFalse(UiMode.NINTENDO in UiElements.modesFor("chat"))
        assertFalse(UiMode.NINTENDO in UiElements.modesFor("tracker"))
        assertEquals(UiMode.NINTENDO, UiMode.valueOf("NINTENDO"))           // the saved name reads back (GameUi.mode)
    }

    @Test fun nintendoKeepsTheElementsBitShownInEveryRoom() {
        val p = GameUiPolicy { UiMode.NINTENDO }
        val hud = HubEl("hud", 1, false)
        for (room in listOf("HOME", "CAST", "MAP", null)) assertFalse("room $room", p.wantsHidden(hud, room, true))
        val s = HubState(1L, 0, null, false, true, listOf(hud), true)
        assertEquals(0, p.desired(s, "HOME", true))
        assertEquals(UiStep.InPlace, p.step(s, "HOME", true, true, 10_000L))
        // Off still hides it (the riders with it), so Nintendo is not Off under another name
        assertTrue(GameUiPolicy { UiMode.OFF }.wantsHidden(hud, "HOME", true))
    }

    @Test fun theSwitchFollowsTheMode() {
        assertTrue(GameUi.nbarWanted(UiMode.NINTENDO))
        for (m in listOf(UiMode.ON, UiMode.OFF, UiMode.DYNAMIC, UiMode.TRACKING)) assertFalse("$m", GameUi.nbarWanted(m))
    }

    @Test fun itsOwnColumns() {
        val s = GameUi.Sw("uisw", cols, false, false, true, true, settled, pet = true, nbar = false)
        assertEquals(35, GameUi.swCol(s, GameUi.SW_NBAR, true))
        assertEquals(34, GameUi.swCol(s, GameUi.SW_NBAR, false))
        assertEquals(33, GameUi.swCol(s, GameUi.SW_PET, true))               // the pet keeps its own
    }

    @Test fun nintendoChosenTapsNbarOnOnceThenSettles() {
        val off = GameUi.Sw("uisw", cols, false, false, true, true, settled, pet = true, nbar = false)
        assertEquals(GameUi.SW_NBAR to true, GameUi.swNext(off, defaults(true)))
        // the game says it is on, and set since its /reload: nothing more
        val on = off.copy(nbar = true, set = settled + ("nbar" to true))
        assertNull(GameUi.swNext(on, defaults(true)))
        // back to On: NBAR_OFF, absolutely
        val back = GameUi.swNext(on, defaults(false))
        assertEquals(GameUi.SW_NBAR to false, back)
        assertEquals(34, GameUi.swCol(on, back!!.first, back.second))
    }

    @Test fun afterAReloadTheSwitchIsSentAgainEvenWhenItReadsRight() {
        // a /reload forgets the switch (set.nbar absent): Off is sent once so the add-on knows it was chosen (every switch's rule)
        val fresh = GameUi.Sw("uisw", cols, false, false, true, true, settled, pet = true, nbar = false)
        assertEquals(GameUi.SW_NBAR to false, GameUi.swNext(fresh, defaults(false)))
    }

    @Test fun anAddOnWithoutTheBarIsNeverTapped() {
        val older = GameUi.Sw("uisw", cols, false, false, true, true, settled, pet = true)
        assertNull(older.nbar)
        assertNull(GameUi.swNext(older, defaults(true)))
        assertEquals("The game does not offer this yet", GameUi.nbarNote(null))
        assertEquals("DS Forever's bar on the top screen now", GameUi.nbarNote(true))
        assertEquals("Sent", GameUi.nbarNote(false))
    }

    @Test fun theWordsTranslate() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { java.io.File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) Tr.load(l, java.io.File(dir, "$l.json").readText(Charsets.UTF_8))
        val explain = "DS Forever's bar replaces the game's on the top screen: the layer you hold, Nintendo buttons, cooldowns."
        for (l in listOf("es", "fr")) {
            assertEquals("Nintendo", Tr.t("Nintendo", l))
            assertTrue(l, Tr.t(explain, l) != explain)
            assertTrue(l, Tr.t("DS Forever's bar on the top screen now", l) != "DS Forever's bar on the top screen now")
        }
    }
}
