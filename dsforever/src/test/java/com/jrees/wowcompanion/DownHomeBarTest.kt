package com.jrees.wowcompanion

import com.jrees.wowcompanion.PadMapModel.Kind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * wk-dpad (the user, 2026-11-16: "using the down ability in combat on the D-pad is flashing the home button again with its arrows as if it
 * was doing the down DS toolbar action and then changing its mind to fix it"): the app's early bar (b98) comes up on its own reader's Down
 * only while that Down IS Home's room bar - in a fight a Down with an In-combat entry casts it (PadMap m44; tools/test_fight_layer.py
 * case 10 proves the router's side), so no bar, no flash.
 */
class DownHomeBarTest {
    private val shock = PadMapModel.Ref(8042, "Earth Shock", 136026)
    private val claw = PadMapModel.Ref(1082, "Claw", 132140)
    private val homeCmd = PadMapModel.Ref(null, "Home Bar", 0, layer = "command", key = PadMapModel.HOME_KEY)
    private val mapCmd = PadMapModel.Ref(null, "Open Map", 0, layer = "command", key = "DSMAP")
    private val bear = PadMapModel.Form(1, 5487, "Bear Form", 132276, on = true)

    private fun map(down: PadMapModel.Slot?, combat: Boolean, fight: Boolean = combat, forms: List<PadMapModel.Form> = emptyList(),
                    formFights: List<PadMapModel.FormFight> = emptyList()): PadMapModel.Map =
        PadMapModel.Map("m164", 1, 1, combat, listOf(PadMapModel.PadSet(1, listOfNotNull(down))), null, null, emptyList(),
            fightSwitch = fight, forms = forms, formFights = formFights)

    /** Down holds the player's spell with an In-combat spell over it (the overlay's) */
    private val downWithFight = PadMapModel.Slot(4, 4, Kind.COMBAT, null, null, 0, combat = shock,
        otherwise = PadMapModel.Ref(331, "Healing Wave", 136052), ov = true)
    private val downPlain = PadMapModel.Slot(4, 4, Kind.SPELL, 331, "Healing Wave", 136052)

    private fun bar(m: PadMapModel.Map?, toolbar: Boolean) = HomeFightFace.downIsHomeBar(m, toolbar, m?.fight == true)

    @Test fun theToolbarsDownIsTheBarOutOfAFight() = assertTrue(bar(map(downWithFight, combat = false), toolbar = true))

    @Test fun aFightsDownWithAnInCombatSpellIsNeverTheBar() = assertFalse(bar(map(downWithFight, combat = true), toolbar = true))

    @Test fun aFightsDownWithNothingInCombatKeepsTheToolbar() = assertTrue(bar(map(downPlain, combat = true), toolbar = true))

    @Test fun anEmptyDownInAFightKeepsTheToolbar() = assertTrue(bar(map(null, combat = true), toolbar = true))

    @Test fun bsGiveBackMakesDownTheToolbarsAgain() =
        // in combat, but B gave the buttons back: the padmap's `fight` is off, the router's roombar wins again
        assertTrue(bar(map(downWithFight, combat = true, fight = false), toolbar = true))

    @Test fun aFormsOwnInCombatSpellOnDownIsNeverTheBar() =
        assertFalse(bar(map(downPlain, combat = true, forms = listOf(bear), formFights = listOf(PadMapModel.FormFight(5487, 4, claw))), toolbar = true))

    @Test fun anotherFormsSpellLeavesTheCastersDown() =
        assertTrue(bar(map(downPlain, combat = true, forms = listOf(bear.copy(on = false)),
            formFights = listOf(PadMapModel.FormFight(5487, 4, claw))), toolbar = true))

    @Test fun theHomeBarCommandAsDownsInCombatEntryIsTheBar() {
        val s = downPlain.copy(kind = Kind.COMBAT, combat = homeCmd, ov = true)
        assertTrue(bar(map(s, combat = true), toolbar = true))
        assertTrue(bar(map(s, combat = true), toolbar = false))
    }

    @Test fun anotherCommandAsDownsInCombatEntryIsNotTheBar() =
        assertFalse(bar(map(downPlain.copy(kind = Kind.COMBAT, combat = mapCmd, ov = true), combat = true), toolbar = true))

    @Test fun theHomeBarCommandOnTheBareDown() {
        val cmd = PadMapModel.Slot(4, 4, Kind.COMMAND, null, "Home Bar", 0, key = PadMapModel.HOME_KEY)
        assertTrue(bar(map(cmd, combat = false), toolbar = false))
        assertTrue(bar(map(cmd, combat = true), toolbar = false))         // no In-combat entry: the command runs in fights too
        // the bare Home Bar command with a fight spell over it (OV.view keeps its key): the spell in a fight, the bar out of one
        val over = cmd.copy(kind = Kind.COMBAT, combat = shock, ov = true)
        assertTrue(bar(map(over, combat = false), toolbar = false))
        assertFalse(bar(map(over, combat = true), toolbar = false))
    }

    @Test fun withoutTheToolbarOrTheCommandDownIsNeverTheBar() {
        assertFalse(bar(map(downPlain, combat = false), toolbar = false))
        assertFalse(bar(map(downWithFight, combat = true), toolbar = false))
        assertFalse(bar(null, toolbar = false))
    }

    @Test fun noPadmapYetTheToolbarDecides() = assertTrue(bar(null, toolbar = true))

    @Test fun theHostsEarlyBarUsesThisRule() {
        val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
            .map { File(it) }.first { it.isDirectory }
        val h = File(src, "CompanionHost.kt").readText()
        assertTrue(h.contains("HomeFightFace.downIsHomeBar(lastPadMap, lastDpad?.want == true, homeView.dpadFight())"))
        assertTrue(Regex("k == \"PADDDOWN\"[^\\n]*downIsHomeBar\\(\\)").containsMatchIn(h))
        assertTrue(File(src, "HomeView.kt").readText().contains("fun dpadFight(): Boolean = fightNow"))
    }
}
