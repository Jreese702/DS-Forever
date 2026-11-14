package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * THE START SCREEN'S REGION LINE (the user's pick 2026-11-13, "Show region when it differs"; #248: a US install signing in to the EU
 * portal): "Region: EU (this install is US)" when the region the game signs in at is not the install's, translated, no question asked.
 */
class RegionLineTest {

    @Test fun aLineOnlyWhenThePortalsDiffer() {
        assertEquals("Region: EU (this install is US)", GameRegion.note("eu", "us")!!.line())
        assertEquals("Region: US (this install is EU)", GameRegion.note("US", "EU")!!.line())
        assertEquals("Region: Korea (this install is US)", GameRegion.note("kr", "us")!!.line())
        assertEquals("Region: Taiwan (this install is EU)", GameRegion.note("tw", "eu")!!.line())
        // the same region, or Taiwan through the Korean portal: nothing to say
        assertNull(GameRegion.note("us", "us"))
        assertNull(GameRegion.note("eu", "EU"))
        assertNull(GameRegion.note("tw", "kr"))
        assertNull(GameRegion.note("kr", "tw"))
        // anything that is not a region (the beta's "test", nothing read)
        assertNull(GameRegion.note("test", "us"))
        assertNull(GameRegion.note("eu", null))
        assertNull(GameRegion.note(null, "us"))
        assertNull(GameRegion.note("eu", ""))
    }

    private fun folder(config: String?, branch: String?, product: String = "wowf"): File {
        val root = Files.createTempDirectory("regionline").toFile()
        if (config != null) File(root, "_forever_/WTF").apply { mkdirs() }.resolve("Config.wtf").writeText(config)
        if (branch != null) File(root, ".build.info").writeText(
            "Branch!STRING:0|Active!DEC:1|Version!STRING:0|Product!STRING:0\n$branch|1|1.60.1.70862|$product\n")
        return root
    }

    @Test fun theFoldersRegionAgainstItsBranch() {
        // #248: no choice, a US install whose Config.wtf signs in at EU
        assertEquals(GameRegion.Note("EU", "US"), GameRegion.noteFor(null, folder("SET portal \"eu\"\n", "us")))
        // a choice wins over the folder's portal (GameConfigWtf.ensure writes it at Play)
        assertEquals(GameRegion.Note("EU", "US"), GameRegion.noteFor("eu", folder("SET portal \"us\"\n", "us")))
        assertNull(GameRegion.noteFor("us", folder("SET portal \"eu\"\n", "us")))
        // the install's own portal, or none yet (a fresh install signs in at its Branch)
        assertNull(GameRegion.noteFor(null, folder("SET portal \"us\"\n", "us")))
        assertNull(GameRegion.noteFor(null, folder(null, "eu")))
        // Taiwan signs in through kr: no line for a Taiwan install
        assertNull(GameRegion.noteFor(null, folder("SET portal \"kr\"\n", "tw")))
        assertNull(GameRegion.noteFor("tw", folder("SET portal \"kr\"\n", "tw")))
        // no game folder read: no line
        assertNull(GameRegion.noteFor("eu", folder(null, null)))
    }

    @Test fun theBetasOwnPortalHasNoRegionLine() {
        // the beta signs in at its "test" portal whatever the region (GameConfigWtf): nothing to compare
        val beta = Files.createTempDirectory("regionbeta").toFile()
        File(beta, ".build.info").writeText("Branch!STRING:0|Active!DEC:1|Version!STRING:0|Product!STRING:0\nus|1|1.60.1.70338|wow_classic_beta\n")
        File(beta, "_classic_beta_/WTF").apply { mkdirs() }.resolve("Config.wtf").writeText("SET portal \"test\"\n")
        assertNull(GameRegion.noteFor("eu", beta))
    }

    private fun words() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) Tr.load(l, File(dir, "$l.json").readText())
    }

    @Test fun theLineInSpanishAndFrench() {
        words()
        val eu = GameRegion.note("eu", "us")!!
        assertEquals("Región: EU (esta instalación es US)", Tr.t(eu.line { Tr.t(it, "es") }, "es"))
        assertEquals("Région : EU (cette installation est US)", Tr.t(eu.line { Tr.t(it, "fr") }, "fr"))
        // the regions' own names in the language too (the login prompt's Korea / Taiwan)
        val kr = GameRegion.note("kr", "us")!!
        assertEquals("Región: Corea (esta instalación es US)", Tr.t(kr.line { Tr.t(it, "es") }, "es"))
        assertEquals("Région : Taïwan (cette installation est EU)", Tr.t(GameRegion.note("tw", "eu")!!.line { Tr.t(it, "fr") }, "fr"))
        assertEquals("Region: Korea (this install is US)", Tr.t(kr.line { Tr.t(it, "en") }, "en"))
    }

    @Test fun theWordsWereAddedAsNewLinesOnly() {
        // the template is in both files' templates, each a CRLF line of its own
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) {
            val b = File(dir, "$l.json").readText()
            val t = org.json.JSONObject(b).getJSONObject("templates")
            assertTrue(l, t.has("Region: %s (this install is %s)"))
            assertTrue(l, b.contains("\"Region: %s (this install is %s)\": \"${t.getString("Region: %s (this install is %s)")}\",\r\n"))
            assertTrue("$l: CRLF throughout", b.split("\n").dropLast(1).all { it.endsWith("\r") })
        }
    }
}
