package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * hub57 / PadMap m169 (the user, 2026-11-16: "The polestar previous or next options should be able to be bound to keys in the controller
 * bindings menus"): a bound "Polestar: next / previous step" press ([PolestarStepChoice.command], the host's barcmd psnext / psprev)
 * is the Map room's own step: the one [PolestarMenu] (QuestsView's, fed Map.lua's documents) sends op 24 / 25; no Polestar or no guide
 * sends nothing and says "No Polestar step"; the first / last step sends nothing and says so; the Map room's choice runs the same path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PolestarStepCommandTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun guide(s: Int) = JSONObject().put("type", "pguide").put("v", "pg2").put("have", true).put("gid", "forever/alliance/1-10/elwynn")
        .put("gs", 444).put("t", "Elwynn Forest").put("z", "Elwynn Forest").put("zm", 1429).put("lo", 1).put("hi", 10).put("n", 10).put("dn", 3)
        .put("tt", 10).put("sk", true).put("u", 0).put("tb", true).put("s", s).put("l", 4).put("st", "dddluuuuuu")
    private fun steps() = JSONObject().put("type", "pgsteps").put("gs", 444).put("n", 10).put("o", 1)
        .put("t", JSONArray((1..10).map { "Step $it" }))

    private fun menu(shown: Int? = 4): PolestarMenu {
        Settings.init(app)
        Settings.polestarGuide = PolestarGuide.ON
        val q = QuestsView(app, { null }, { })
        if (shown != null) for (d in listOf(guide(shown), steps())) q.onPolestarDoc(d)
        return q.psMenu
    }

    @Test fun nextAndPreviousSendPolestarsOwnSteps() {
        val m = menu()
        val said = ArrayList<String>()
        assertTrue(PolestarStepChoice.command(m, next = true) { said.add(it) })
        assertTrue(PolestarStepChoice.command(m, next = false) { said.add(it) })
        assertEquals("op 24 then 25 (Engine.Next / Engine.Prev)", listOf(PolestarTab.NEXT, PolestarTab.PREV), m.steps.toList())
        assertFalse("no refusal", said.contains(PolestarSkip.NO_STEP_LINE))
    }

    @Test fun noGuideOrNoPolestarSendsNothingAndSaysSo() {
        val said = ArrayList<String>()
        assertFalse(PolestarStepChoice.command(null, next = true) { said.add(it) })
        val m = menu(shown = null)
        assertFalse(PolestarStepChoice.command(m, next = true) { said.add(it) })
        assertEquals(0, m.steps.size)
        assertEquals(listOf(PolestarSkip.NO_STEP_LINE, PolestarSkip.NO_STEP_LINE), said)
        val g = menu()
        Settings.polestarGuide = PolestarGuide.OFF                       // Quest tracking not Polestar: the menu does not show
        said.clear()
        assertFalse(PolestarStepChoice.command(g, next = false) { said.add(it) })
        assertEquals(0, g.steps.size)
        assertEquals(listOf(PolestarSkip.NO_STEP_LINE), said)
        Settings.polestarGuide = PolestarGuide.ON
    }

    @Test fun theFirstAndLastStepSayNothingToChange() {
        val said = ArrayList<String>()
        val first = menu(shown = 1)
        assertFalse(PolestarStepChoice.command(first, next = false) { said.add(it) })
        assertTrue(PolestarStepChoice.command(first, next = true) { said.add(it) })
        val last = menu(shown = 10)
        assertFalse(PolestarStepChoice.command(last, next = true) { said.add(it) })
        assertEquals(listOf(PolestarTab.NEXT), first.steps.toList())
        assertEquals(0, last.steps.size)
        assertEquals(2, said.count { it == PolestarTab.NOTHING_LINE })
    }

    @Test fun theMapRoomsChoiceRunsTheSamePress() {
        val m = menu()
        val said = ArrayList<String>()
        val c = PolestarStepChoice({ m }) { said.add(it) }
        assertTrue(c.open())
        c.run(MapRoomKeys.PsSkip.NEXT)
        assertFalse("one press closes it", c.open)
        assertEquals(listOf(PolestarTab.NEXT), m.steps.toList())
    }
}
