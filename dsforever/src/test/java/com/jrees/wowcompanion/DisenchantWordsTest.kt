package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * bags-de1 (the Disenchant grid; the user's list 2026-11-16: "It stays open after each one; B closes it"): the grid's own new words are
 * translated (es / fr), the words it reuses already were, and the spell's own name is the game's (never in the app's word files).
 */
class DisenchantWordsTest {
    private fun load() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { java.io.File(it) }.first { it.isDirectory }
        Tr.load("es", java.io.File(dir, "es.json").readText())
        Tr.load("fr", java.io.File(dir, "fr.json").readText())
    }

    @Test fun theGridsWordsAreTranslated() {
        load()
        assertEquals("Nada que desencantar", Tr.t("Nothing to disenchant", "es"))
        assertEquals("Rien à désenchanter", Tr.t("Nothing to disenchant", "fr"))
        assertEquals("Detener", Tr.t("Stop", "es"))
        assertEquals("Arrêter", Tr.t("Stop", "fr"))
        // reused: Close, Yes, Open (the strip's A)
        assertEquals("Cerrar", Tr.t("Close", "es"))
        assertEquals("Oui", Tr.t("Yes", "fr"))
        assertEquals("Ouvrir", Tr.t("Open", "fr"))
    }

    @Test fun theSpellsNameIsTheGames() {
        load()
        // "Disenchant" comes from the game in the player's language: the app's word files never carry it
        assertEquals("Disenchant", Tr.t("Disenchant", "es"))
        assertEquals("Disenchant", Tr.t("Disenchant", "fr"))
    }
}
