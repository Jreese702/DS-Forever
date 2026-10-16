package com.jrees.wowcompanion

import com.jrees.wowcompanion.PadMapModel.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** f-combat-forms (the user's pick "1: A set per form"): Home's bars show the active form's own In-combat set, the caster's elsewhere */
class CombatFormsTest {
    private val moonfire = PadMapModel.Ref(8921, "Moonfire", 136096)
    private val healing = PadMapModel.Ref(5185, "Healing Touch", 136041)
    private val maul = PadMapModel.Ref(6807, "Maul", 132136)
    private val swipe = PadMapModel.Ref(779, "Swipe", 134296)
    private val bear = PadMapModel.Form(1, 5487, "Bear Form", 132276, on = false)
    private val cat = PadMapModel.Form(2, 768, "Cat Form", 132115, on = false)

    private fun map(active: Int?, armed: Map<Int, String> = mapOf(5 to "g", 6 to "g", 8 to "g")): PadMapModel.Map {
        val slots = listOf(
            PadMapModel.Slot(1, 1, Kind.COMBAT, 8921, "Moonfire", 136096, combat = moonfire, ov = true),
            PadMapModel.Slot(2, 2, Kind.SPELL, 5176, "Wrath", 136006),
            PadMapModel.Slot(5, null, Kind.FIXED, null, "Interact", 0, fight = healing),
            PadMapModel.Slot(6, null, Kind.FIXED, null, "Inspect", 0),
            PadMapModel.Slot(7, null, Kind.FIXED, null, "Back", 0),
            PadMapModel.Slot(8, null, Kind.FIXED, null, "Jump", 0),
        )
        return PadMapModel.Map("m55", 1, 1, true, listOf(PadMapModel.PadSet(1, slots)), null, null, emptyList(),
            faceArmedLayer = armed, fightSwitch = true,
            forms = listOf(bear.copy(on = active == bear.id), cat.copy(on = active == cat.id)),
            formFights = listOf(PadMapModel.FormFight(5487, 1, maul), PadMapModel.FormFight(5487, 5, swipe)))
    }

    @Test fun casterShowsTheCasterSet() {
        val m = map(null)
        assertEquals(mapOf(1 to 136096), HomeFightFace.dpadIcons(m))
        assertEquals(mapOf(5 to 136041), HomeFightFace.icons(m))
        assertEquals(emptyMap<Int, PadMapModel.Ref>(), HomeFightFace.formOwn(m))
        assertNull(HomeFightFace.formBadge(m))
    }

    @Test fun bearShowsItsOwnSpellsOverTheCasters() {
        val m = map(5487)
        assertEquals(mapOf(1 to 132136), HomeFightFace.dpadIcons(m))          // Maul over Moonfire
        assertEquals(mapOf(5 to 134296), HomeFightFace.icons(m))              // Swipe over Healing Touch
        assertEquals(setOf(1, 5), HomeFightFace.formOwn(m).keys)
        assertEquals(132276, HomeFightFace.formBadge(m))
    }

    @Test fun bearsIdsFollowItsOwnSpells() {
        val ids = HomeFightFace.ids(map(5487))
        assertEquals(6807, ids[1]); assertEquals(779, ids[5])
    }

    @Test fun catWithoutASetFallsBackToTheCaster() {
        val m = map(768)
        assertEquals(mapOf(1 to 136096), HomeFightFace.dpadIcons(m))
        assertEquals(mapOf(5 to 136041), HomeFightFace.icons(m))
        assertNull(HomeFightFace.formBadge(m))
    }

    @Test fun outOfAFightTheDpadShowsNothing() = assertEquals(emptyMap<Int, Int>(), HomeFightFace.dpadIcons(map(5487), fight = false))

    @Test fun aFaceShowsOnlyWhileArmed() = assertEquals(emptyMap<Int, Int>(), HomeFightFace.icons(map(5487, armed = emptyMap())))

    @Test fun theTouchColumns() { assertEquals(15, PadMapModel.colForm(0)); assertEquals(16, PadMapModel.colForm(1)); assertEquals(25, PadMapModel.colForm(10)) }
}
