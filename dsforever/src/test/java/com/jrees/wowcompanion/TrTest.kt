package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-07: Spanish and French for the start screen and the login (Tr) */
class TrTest {
    /** the user on test23: "Why is my chat text white in the home screen for everything?" - a coloured line keeps its spans */
    @Test fun aColouredLineKeepsItsColours() {
        val words = "[Party] Brannoc: OK"
        val coloured = object : android.text.Spanned, CharSequence by words {
            override fun <T : Any?> getSpans(start: Int, end: Int, type: Class<T>?): Array<T> =
                @Suppress("UNCHECKED_CAST") (java.lang.reflect.Array.newInstance(type ?: Any::class.java, 1) as Array<T>)
            override fun getSpanStart(tag: Any?) = 0
            override fun getSpanEnd(tag: Any?) = words.length
            override fun getSpanFlags(tag: Any?) = 0
            override fun nextSpanTransition(start: Int, limit: Int, type: Class<*>?) = limit
            override fun toString() = words
        }
        assertTrue("the same object, spans and all", Tr.span(coloured) === coloured)
        assertEquals("OK", Tr.span("OK").toString())                  // plain text: as Tr.t
    }

    @Test fun spanishAndFrenchCoverTheSameWords() {
        val es = Tr.keys("es"); val fr = Tr.keys("fr")
        assertEquals("in Spanish only: ${es - fr}", emptySet<String>(), es - fr)
        assertEquals("in French only: ${fr - es}", emptySet<String>(), fr - es)
        assertTrue(es.size > 80)
    }

    /** the whole app's words (assets companion/i18n): they load, every value keeps the English's %s count, a room's words translate */
    @Test fun theAppsWordFiles() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { java.io.File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) {
            val text = java.io.File(dir, "$l.json").readText()
            Tr.load(l, text)
            val o = org.json.JSONObject(text)
            for (sec in listOf("plain", "templates")) {
                val m = o.getJSONObject(sec)
                for (k in m.keys()) assertEquals("$l: $k", k.split("%s").size, m.getString(k).split("%s").size)
            }
            assertTrue("$l has the rooms' words", o.getJSONObject("plain").length() > 1300)
        }
        assertEquals("Aceptar", Tr.t("OK", "es"))                       // the start screen's own words still win
        assertTrue(Tr.t("Nothing in your bags that this vendor will buy.", "fr") != "Nothing in your bags that this vendor will buy.")
        assertTrue(Tr.t("Remove Brannoc from your calendar?", "es").contains("Brannoc"))
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
