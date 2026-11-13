package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PadTabCtl p39 (the user, 2026-11-11, on 1428): "The in combat tab of the controller bindings is missing all 5 pages of options, it only
 * shows the first 3. The icons for those 5 tabs look weird being narrowed"; p43 (the user, 2026-11-12, on 1429: "In combat still does not
 * show all 5 tabs", then the pick "All 5 on every button"). The chooser's tab keys per room tab and button, the bindings' grey, and each
 * tab drawn square in its slot. tools/test_party.py "p43" checks the add-on's own onlySpells() / bindHere the same way.
 */
class ChooserTabsTest {
    private val five = listOf("spells", "general", "professions", "extra", "dstool")
    private val three = listOf("spells", "general", "professions")

    private fun tabs(view: String, combat: Boolean = false) = ChooserTabs.keys(ChooserTabs.spellsOnly(view, combat))

    @Test fun inCombatHasAllFiveOnEveryButton() {
        // every In combat button: plain, a fight spell on it, a base X / Y / A, a form's set, the toolbar's D-pad, any set / page - the
        // rule has no button input In combat at all
        assertEquals("In combat, a plain button", five, tabs("combat"))
        assertEquals("In combat, a button with a fight spell (an Extra / DS entry replaces it on the fight layer, p41)", five, tabs("combat", combat = true))
    }

    @Test fun inCombatBindingsGreyOnlyWhereNoChordGoes() {
        // the rows stay (five tabs, the "extra" row counts them); a binding is dim with the add-on's own reason
        for (p in listOf(5, 6, 8)) assertEquals("a base face ($p)", "fixed", ChooserTabs.bindBlock(1, p, toolbar = false, form = false))
        assertEquals("a form's own set", "form", ChooserTabs.bindBlock(1, 8, toolbar = false, form = true))
        assertEquals("a form's own D-pad", "form", ChooserTabs.bindBlock(1, 1, toolbar = false, form = true))
        assertEquals("the bare D-pad the DS WoW toolbar holds", "dpad bar", ChooserTabs.bindBlock(1, 2, toolbar = true, form = false))
        assertEquals("ZR", "layer", ChooserTabs.bindBlock(3, 1, toolbar = false, form = false))
        assertEquals("ZL + ZR", "layer", ChooserTabs.bindBlock(4, 8, toolbar = false, form = false))
        assertNull("the bare D-pad, toolbar off", ChooserTabs.bindBlock(1, 1, toolbar = false, form = false))
        assertNull("ZL + A (the toolbar only holds the bare D-pad)", ChooserTabs.bindBlock(2, 8, toolbar = true, form = false))
    }

    @Test fun sticksTakeTheBindingsBareAndWithZl() {
        // m150 / p49 (GitHub #78): L3 / R3 (9 / 10) - bare or ZL, never ZR / ZL + ZR; the toolbar and the fixed four's rules do not apply
        for (p in PadMapModel.STICKS) {
            assertNull("bare $p", ChooserTabs.bindBlock(1, p, toolbar = true, form = false))
            assertNull("ZL + $p", ChooserTabs.bindBlock(2, p, toolbar = false, form = false))
            assertEquals("ZR + $p", "layer", ChooserTabs.bindBlock(3, p, toolbar = false, form = false))
            assertEquals("ZL + ZR + $p", "layer", ChooserTabs.bindBlock(4, p, toolbar = false, form = false))
        }
        // p52 / m157 (the user, 2026-11-13: "any of those 5 controller tabs should be able to bind here"): all five on a stick
        assertEquals("a stick's chooser: all five tabs", five, ChooserTabs.keys(false, stick = true))
        assertEquals("Spells, General, Professions, Extra, DS", listOf("spells", "general", "professions", "extra", "dstool"), ChooserTabs.STICK_TABS)
        assertEquals("the eight as before", five, ChooserTabs.keys(false))
    }

    @Test fun sticksSitBetweenTheDiamondsInEveryLayout() {
        for (swap in listOf(false, true)) {
            // m155: a pair of their own, below and between the two diamonds (the user, 2026-11-13: "moved away from the other two cross's")
            assertEquals(323f to 790f, PadDiamonds.centre(PadMapModel.POS_L3, swap))
            assertEquals(423f to 790f, PadDiamonds.centre(PadMapModel.POS_R3, swap))
        }
        assertEquals("the stick's glyph in any pad style", "gen_lstickin",
            SpellsModel.combo(SpellsModel.PadSlot(0, 1, 2, PadMapModel.POS_L3), SpellsModel.PadStyle.SHP).button)
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
