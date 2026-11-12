package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PadTabCtl p39 (the user, 2026-11-11, on 1428): "The in combat tab of the controller bindings is missing all 5 pages of options, it only
 * shows the first 3. The icons for those 5 tabs look weird being narrowed". The chooser's tab keys per room tab and button, and each tab
 * drawn square in its slot. tools/test_party.py "p39" checks the add-on's own onlySpells() the same way.
 */
class ChooserTabsTest {
    private val five = listOf("spells", "general", "professions", "extra", "dstool")
    private val three = listOf("spells", "general", "professions")

    private fun tabs(view: String, face: Boolean = false, form: Boolean = false, toolbarDpad: Boolean = false, combat: Boolean = false) =
        ChooserTabs.keys(ChooserTabs.spellsOnly(view, face, form, toolbarDpad, combat))

    @Test fun inCombatHasAllFive() {
        assertEquals("In combat, a plain button", five, tabs("combat"))
        assertEquals("In combat, a button with a fight spell (an Extra / DS entry replaces it on the fight layer, p41)", five, tabs("combat", combat = true))
    }

    @Test fun inCombatKeepsThreeWhereOnlyAFightSpellGoes() {
        assertEquals("a base X / Y / A", three, tabs("combat", face = true))
        assertEquals("a form's own set", three, tabs("combat", form = true))
        assertEquals("the bare D-pad the DS WoW toolbar holds (p36)", three, tabs("combat", toolbarDpad = true))
    }

    @Test fun wowDefaultsAndRadialAsBefore() {
        assertEquals("WoW Defaults, a plain button", five, tabs("normal"))
        assertEquals("WoW Defaults, a combat button (its Otherwise spell)", three, tabs("normal", combat = true))
        assertEquals("the spell radial casts spells", three, tabs("radial"))
    }

    @Test fun inCombatFightLayerBoxNamesOnlyTheFightEntry() {
        // p41 / m132 (the user, 2026-11-11: "Build a fight-only layer"): a command from In combat goes on the fight layer only; the box
        // names the fight-layer entry it replaces and nothing of the button's WoW Defaults side
        assertEquals(Triple("Lightning Bolt is X / Y's In combat entry.", "Replace it with Open Map?", "Replace"),
            ChooserTabs.fightLayerBox("X / Y", "Open Map", "Lightning Bolt"))
        assertEquals(Triple("Open Chat is Left's In combat entry.", "Replace it with Jump?", "Replace"),
            ChooserTabs.fightLayerBox("Left", "Jump", "Open Chat"))
        val (l1, l2, _) = ChooserTabs.fightLayerBox("Left", "Open Map", "Lightning Bolt")
        assertEquals("never 'out of combat' / 'both': the WoW Defaults side is not touched", false,
            (l1 + l2).contains("out of combat") || (l1 + l2).contains("both"))
    }

    @Test fun narrowSlotsDrawSquareTabs() {
        // five tabs in the chooser: about 64 wide in an 84-high row -> a 64 x 64 square on the row's middle
        val b = ChooserTabs.square(100f, 30f, 164f, 114f)
        assertEquals(64f, b.r - b.l, 0.001f)
        assertEquals(64f, b.b - b.t, 0.001f)
        assertEquals(100f, b.l, 0.001f)
        assertEquals(72f, (b.t + b.b) / 2f, 0.001f)
        // three tabs: 84 x 84 stays exactly as it was
        assertEquals(PartyLayout.Box(10f, 30f, 94f, 114f), ChooserTabs.square(10f, 30f, 94f, 114f))
    }
}
