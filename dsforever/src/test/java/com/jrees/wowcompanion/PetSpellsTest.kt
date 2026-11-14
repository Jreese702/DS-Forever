package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #234 PET SPELLS (the user: "build it"; Spells s10, Book b63): the documents the add-on's Lua REALLY sends for a Hunter with his pet
 * out, a Warlock's imp and a Shaman with no pet, dumped by tools/test_pet_spells.py into src/test/resources/pet-fixtures (the harness
 * fails when they go stale), read here by the app's own SpellsModel: the Pet tab, which entries Bind takes (the SPELLS, never a passive
 * or a command), their two taps, the room's tab for the controller's line 101, the chooser's Spells tab, the "not on a button" glow.
 */
class PetSpellsTest {
    private fun fx(name: String): JSONObject {
        val s = javaClass.classLoader!!.getResourceAsStream("pet-fixtures/$name.json")
        assertNotNull("fixture $name (run tools/test_pet_spells.py with PARTY_FIXTURES_WRITE=1)", s)
        return JSONObject(s!!.bufferedReader().use { it.readText() })
    }

    private fun book(name: String) = SpellsModel.parse(fx(name))!!
    private fun pet(b: SpellsModel.Book, n: String) = b.petItems.first { it.name == n }

    @Test fun theHunterHasAPetTabLast() {
        val b = book("spells_hunter")
        val tabs = SpellsModel.tabs(b)
        assertEquals("pet", tabs.last().key)
        assertEquals("Pet", tabs.last().name)
        assertEquals(listOf("Attack", "Follow", "Stay", "Growl", "Bite", "Great Stamina", "Defensive"), tabs.last().entries.map { it.name })
    }

    @Test fun onlyThePetsSpellsAreBindable() {
        val b = book("spells_hunter")
        assertEquals(listOf("Growl", "Bite"), b.petItems.filter { SpellsModel.bindable(b, it) }.map { it.name })
        for (n in listOf("Attack", "Follow", "Stay", "Defensive")) {
            assertTrue("$n is a command", pet(b, n).command)
            assertNull("$n has no number", pet(b, n).padNumber)
        }
        assertTrue(pet(b, "Great Stamina").passive)
        assertFalse(SpellsModel.bindable(b, pet(b, "Great Stamina")))
    }

    @Test fun aPetSpellHasItsTwoTaps() {
        val b = book("spells_hunter")
        val growl = pet(b, "Growl")
        assertNotNull(growl.padNumber)
        val cells = SpellsModel.bindCells(b, growl)!!
        assertEquals(2, cells.size)
        assertTrue(cells.all { it.first == "spells" })
        val n = growl.padNumber!!
        assertEquals(b.win["vb${n / 13}"], cells[0].second)
        assertEquals(b.win["vo${n % 13}"], cells[1].second)
        // the player's numbers and the pet's never meet
        val all = (b.items + b.petItems).mapNotNull { it.padNumber }
        assertEquals(all.size, all.toSet().size)
    }

    @Test fun theControllersLine101IsThePetTab() {
        assertEquals("pet", SpellsModel.tabKeyOfLine(SpellsModel.PET_LINE))
        assertEquals("line:2", SpellsModel.tabKeyOfLine(2))
        val c = SpellsModel.parseCtl(fx("spellsctl_pet_book"))!!
        assertEquals(SpellsModel.PET_LINE, c.line)
        assertEquals(2649, c.selected)
        val bind = SpellsModel.parseCtl(fx("spellsctl_pet_bind"))!!
        assertEquals(2649, bind.binding)
        assertEquals("Growl", SpellsModel.find(book("spells_hunter"), bind.binding!!)?.name)
    }

    @Test fun theChoosersSpellsTabEndsWithThePetsSpells() {
        val b = book("spells_hunter")
        val spells = SpellsModel.tabSpells(b, "spells").map { it.name }
        assertEquals(listOf("Growl", "Bite"), spells.takeLast(2))
        assertFalse("General keeps none", SpellsModel.tabSpells(b, "general").any { it.pet })
        assertFalse("no command anywhere", SpellsModel.tabSpells(b).any { it.command || it.passive })
    }

    @Test fun theGlowFollowsTheGame() {
        val b = book("spells_hunter")
        assertTrue("Growl is on the pet bar", SpellsModel.onBar(b, pet(b, "Growl")))
        assertFalse("Bite is on no bar: the gold glow", SpellsModel.onBar(b, pet(b, "Bite")))
        assertTrue("a command never glows", SpellsModel.onBar(b, pet(b, "Attack")))
        assertTrue("a passive never glows", SpellsModel.onBar(b, pet(b, "Great Stamina")))
        val bound = book("spells_hunter_bound")
        val g = pet(bound, "Growl")
        assertEquals(1, g.pad.size)
        assertEquals(3, g.pad[0].bar)
        assertEquals(8, g.pad[0].pos)
    }

    @Test fun theWarlocksImpTokensAreCommands() {
        val b = book("spells_warlock")
        assertEquals(listOf("Firebolt", "Blood Pact", "Phase Shift"), b.petItems.filter { SpellsModel.bindable(b, it) }.map { it.name })
        assertTrue(b.petItems.first { it.name == "PET_ACTION_ATTACK" }.command)
        assertEquals("pet", SpellsModel.tabs(b).last().key)
    }

    @Test fun theShamanHasNoPetTab() {
        val b = book("spells_shaman")
        assertTrue(b.petItems.isEmpty())
        assertTrue(SpellsModel.tabs(b).none { it.key == "pet" })
        assertEquals(listOf("Lightning Bolt", "Healing Wave"), SpellsModel.tabSpells(b).map { it.name })
    }

    @Test fun anOlderAddOnsPetEntriesStayUnbound() {
        // s9 sent no `bind` / `cmd`: its Book never bound a pet spell, so the app offers no Bind and no glow
        val doc = fx("spells_hunter")
        val items = doc.getJSONObject("s").getJSONObject("pet").getJSONArray("items")
        for (i in 0 until items.length()) items.getJSONObject(i).apply { remove("bind"); remove("cmd"); remove("pv"); remove("pb") }
        val b = SpellsModel.parse(doc)!!
        assertTrue(b.petItems.none { SpellsModel.bindable(b, it) })
        assertTrue(b.petItems.all { SpellsModel.onBar(b, it) })
    }
}
