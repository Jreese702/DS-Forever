package com.jrees.wowcompanion

import com.jrees.wowcompanion.PadMapModel.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/** the user on 1.0.18-test4: LT's In-combat spells blank in a fight (the router casts them; the game's slot under them is empty) */
class TrigFightsTest {
    private val shock = PadMapModel.Ref(8042, "Earth Shock", 136026)
    private val bolt = PadMapModel.Ref(403, "Lightning Bolt", 136048)

    private fun map(fight: Boolean): PadMapModel.Map {
        val base = listOf(PadMapModel.Slot(1, 1, Kind.COMBAT, 403, "Lightning Bolt", 136048, combat = bolt, ov = true))
        val lt = listOf(
            PadMapModel.Slot(2, 14, Kind.COMBAT, 8042, "Earth Shock", 136026, combat = shock, ov = true),
            PadMapModel.Slot(6, 18, Kind.SPELL, 331, "Healing Wave", 136052),
        )
        val rt = listOf(PadMapModel.Slot(5, 29, Kind.COMBAT, 403, "Lightning Bolt", 136048, combat = bolt, ov = false))
        return PadMapModel.Map("m104", 1, 1, fight, listOf(PadMapModel.PadSet(1, base), PadMapModel.PadSet(2, lt), PadMapModel.PadSet(3, rt)),
            null, null, emptyList(), fightSwitch = fight)
    }

    @Test fun ltsInCombatSpellShowsInAFight() =
        assertEquals(mapOf(HomeFightFace.cmdKey(2, 2) to (136026 to 8042)), HomeFightFace.trigFights(map(true)))

    @Test fun nothingOutOfAFight() = assertEquals(emptyMap<Int, Pair<Int, Int>>(), HomeFightFace.trigFights(map(false)))

    @Test fun theBareSetStaysTheDpadsJob() = assertEquals(setOf(HomeFightFace.cmdKey(2, 2)), HomeFightFace.trigFights(map(true)).keys)
}
