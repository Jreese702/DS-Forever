package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-07: Spanish and French for the start screen and the login (Tr) */
class TrTest {
    @Test fun spanishAndFrenchCoverTheSameWords() {
        val es = Tr.keys("es"); val fr = Tr.keys("fr")
        assertEquals("in Spanish only: ${es - fr}", emptySet<String>(), es - fr)
        assertEquals("in French only: ${fr - es}", emptySet<String>(), fr - es)
        assertTrue(es.size > 80)
    }

    @Test fun wordsAndTemplates() {
        assertEquals("Jugar", Tr.t("Play", "es"))
        assertEquals("Jouer", Tr.t("Play", "fr"))
        assertEquals("Play", Tr.t("Play", "en"))
        assertEquals("Something new", Tr.t("Something new", "fr"))                       // no words yet: the English
        assertEquals("Actualizando el juego a 1.60.1.70400", Tr.t("Updating the game to 1.60.1.70400", "es"))
        assertEquals("DS Forever 1.0.17 est disponible", Tr.t("DS Forever 1.0.17 is out", "fr"))
        assertEquals("Preparando el arte del juego: 45%", Tr.t("Preparing the game art: 45%", "es"))
        assertEquals("Le dossier /x contient wow, pas WoW Forever. Choisissez le dossier de WoW Forever : celui qui contient .build.info et Data.",
            Tr.t("The folder /x holds wow, not WoW Forever. Choose the WoW Forever folder: the one with .build.info and Data.", "fr"))
    }
}
