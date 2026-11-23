package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** issue 453: while streaming, older PC add-ons (DS Forever Desktop not updated) are said, and the Tap to cast tab stays on show, dimmed */
class DesktopAddonsTest {
    private fun asset(rel: String): File = listOf("../companion/app/src/main/assets/", "companion/app/src/main/assets/")
        .map { File(it + rel) }.first { it.isFile }

    @Test fun theBundledTagsAreReadFromTheAddOnsThemselves() {
        val pv = asset(DesktopAddons.PADMAP_ASSET).bufferedReader().use { DesktopAddons.tagIn(it.lineSequence(), "PV") }
        val cbv = asset(DesktopAddons.ACTIONBAR_ASSET).bufferedReader().use { DesktopAddons.tagIn(it.lineSequence(), "CBV") }
        assertNotNull(pv); assertNotNull(cbv)
        assertTrue(pv, (DesktopAddons.num(pv, "m") ?: 0) >= 186)
        assertTrue(cbv, (DesktopAddons.num(cbv, "cb") ?: 0) >= 16)
        assertEquals("cb16", DesktopAddons.tagIn(sequenceOf("-- local CBV = \"cb1\" is a note", "local CBV = \"cb16\"     -- the tag"), "CBV"))
    }

    @Test fun olderOnlyWhileStreamingAndOnlyWhenKnownOlder() {
        // before: nothing compared the PC's tags - the tab just vanished (bindTab false) with no word
        assertFalse(CastStripModel.bindTab(null))
        assertTrue("an older PadMap", DesktopAddons.older(true, "m150", "cb16", "m186", "cb16"))
        assertTrue("an older cast bar", DesktopAddons.older(true, "m186", "cb2", "m186", "cb16"))
        assertFalse("the same set", DesktopAddons.older(true, "m186", "cb16", "m186", "cb16"))
        assertFalse("a NEWER PC set is never older", DesktopAddons.older(true, "m190", "cb20", "m186", "cb16"))
        assertFalse("not streaming: the Thor's own add-ons", DesktopAddons.older(false, "m150", "cb2", "m186", "cb16"))
        assertFalse("unknown tags count for nothing", DesktopAddons.older(true, null, "?", "m186", "cb16"))
        assertFalse("the bundled tags not read yet", DesktopAddons.older(true, "m150", "cb2", null, null))
        assertFalse("another kind of tag", DesktopAddons.older(true, "x9", "cb16", "m186", "cb16"))
        assertEquals(150, DesktopAddons.num("m150", "m")); assertEquals(16, DesktopAddons.num("cb16", "cb"))
        assertTrue(DesktopAddons.line("m150", "cb2", "m186", "cb16").contains("m150 < m186"))
    }

    @Test fun theNoteIsInSpanishAndFrench() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { File(it) }.first { it.isDirectory }
        Tr.load("es", File(dir, "es.json").readText()); Tr.load("fr", File(dir, "fr.json").readText())
        assertTrue(Tr.t(DesktopAddons.NOTE, "es") != DesktopAddons.NOTE)
        assertTrue(Tr.t(DesktopAddons.NOTE, "fr") != DesktopAddons.NOTE)
    }
}
