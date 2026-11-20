package com.jrees.wowcompanion

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * B: THE SERVER'S WORK_OPTIMS VALUE BEFORE THE FIRST START (the user's ruling 2026-11-20; ported from WoW Foreverywhere, YoruDev-Ryland,
 * 5e360838, GPL-3.0): the launch client's Config.wtf starts with ClientSettings_WORK_OPTIMS_BY_API_MASK "16001,0xe,0x0,0x1ff" so the
 * game skips its login-time graphics-device rebuild; only where the line is missing (a value the game saved is its own); recorded so
 * Reset settings gives the original (no line) back while it is still our value; never on the beta.
 */
class WorkOptimsTest {
    private val us = mapOf("Branch" to "us")
    private fun wtfDir(root: File, p: WowProduct = WowProduct.LAUNCH) = File(root, "${p.flavorDir}/WTF")
    private fun lines(root: File, p: WowProduct = WowProduct.LAUNCH) = File(wtfDir(root, p), "Config.wtf").readLines()
    private fun optims(root: File) = lines(root).filter { it.contains(GameConfigWtf.WORK_OPTIMS_KEY, ignoreCase = true) }

    @Test fun theFork_sValue() {
        assertEquals("SET ClientSettings_WORK_OPTIMS_BY_API_MASK \"16001,0xe,0x0,0x1ff\"", GameConfigWtf.WORK_OPTIMS_LINE)
    }

    @Test fun aNewFileStartsWithItAndARecord() {
        val root = Files.createTempDirectory("wo").toFile()
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us)
        assertEquals(listOf(GameConfigWtf.WORK_OPTIMS_LINE), optims(root))
        assertEquals("${GameConfigWtf.WORK_OPTIMS_KEY}\t-\n", File(wtfDir(root), GameConfigWtf.WORK_OPTIMS_SAVED).readText())
        // settled: the next start writes nothing
        val f = File(wtfDir(root), "Config.wtf"); val text = f.readText(); val stamp = f.lastModified()
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us)
        assertEquals(text, f.readText()); assertEquals(stamp, f.lastModified())
    }

    @Test fun anOldFileGetsItOnce() {
        val root = Files.createTempDirectory("wo2").toFile()
        val dir = wtfDir(root).apply { mkdirs() }
        File(dir, "Config.wtf").writeText("SET gxApi \"D3D11\"\nSET portal \"us\"\n")
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us)
        assertEquals(listOf(GameConfigWtf.WORK_OPTIMS_LINE), optims(root))
        assertTrue(File(dir, GameConfigWtf.WORK_OPTIMS_SAVED).isFile)
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us)
        assertEquals(1, optims(root).size)
    }

    @Test fun aValueTheGameSavedStays() {
        val root = Files.createTempDirectory("wo3").toFile()
        val dir = wtfDir(root).apply { mkdirs() }
        val newer = "SET clientSettings_work_optims_by_api_mask \"16002,0xf,0x0,0x1ff\""
        File(dir, "Config.wtf").writeText("SET gxApi \"D3D11\"\n$newer\n")
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us)
        assertEquals(listOf(newer), optims(root))
        assertFalse("no record: nothing of ours to give back", File(dir, GameConfigWtf.WORK_OPTIMS_SAVED).exists())
    }

    @Test fun neverOnTheBeta() {
        val root = Files.createTempDirectory("wo4").toFile()
        GameConfigWtf.ensure(root, WowProduct.BETA, null)
        assertTrue(lines(root, WowProduct.BETA).none { it.contains("WORK_OPTIMS", ignoreCase = true) })
        assertFalse(File(wtfDir(root, WowProduct.BETA), GameConfigWtf.WORK_OPTIMS_SAVED).exists())
    }

    @Test fun resetGivesBackTheOriginalOnlyWhileItIsOurValue() {
        val root = Files.createTempDirectory("wo5").toFile()
        val dir = wtfDir(root).apply { mkdirs() }
        File(dir, "Config.wtf").writeText("SET gxApi \"D3D11\"\n")
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us)
        assertTrue(GameConfigWtf.hasOriginals(dir))
        assertEquals(listOf(GameConfigWtf.WORK_OPTIMS_KEY), GameConfigWtf.restoreOriginals(dir))
        assertTrue("no line before: none now", optims(root).isEmpty())
        assertFalse(File(dir, GameConfigWtf.WORK_OPTIMS_SAVED).exists())
        assertFalse(GameConfigWtf.hasOriginals(dir))
        // the game took a newer server value since: it stays theirs, the record still goes
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us)
        val f = File(dir, "Config.wtf")
        f.writeText(f.readText().replace("16001,0xe", "16002,0xf"))
        assertEquals(emptyList<String>(), GameConfigWtf.restoreOriginals(dir))
        assertEquals(1, optims(root).size); assertTrue(optims(root).single().contains("16002"))
        assertFalse(File(dir, GameConfigWtf.WORK_OPTIMS_SAVED).exists())
    }

    @Test fun resetRunGivesItBackWithTheLowMemoryKeys() {
        val root = Files.createTempDirectory("wo6").toFile()
        val dir = wtfDir(root).apply { mkdirs() }
        File(dir, "Config.wtf").writeText("SET gxApi \"D3D11\"\nSET graphicsViewDistance \"1\"\n")
        File(dir, GameConfigWtf.LOW_MEMORY_8GB_SAVED).writeText("graphicsViewDistance\t\"7\"\n")
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us)      // low memory off: the 8 GB key back, WORK_OPTIMS added
        File(dir, GameConfigWtf.LOW_MEMORY_8GB_SAVED).writeText("graphicsViewDistance\t\"7\"\n")
        val f = File(dir, "Config.wtf"); f.writeText(f.readText().replace("graphicsViewDistance \"7\"", "graphicsViewDistance \"1\""))
        val back = GameConfigWtf.restoreOriginals(dir)
        assertEquals(setOf("graphicsViewDistance", GameConfigWtf.WORK_OPTIMS_KEY), back.toSet())
        assertTrue(f.readLines().contains("SET graphicsViewDistance \"7\""))
        assertTrue(optims(root).isEmpty())
        // only a WORK_OPTIMS record: ResetRun still has something to give back (hasOriginals), the game running = at the next start
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us)
        val kept = File(root, "kept").apply { mkdirs() }
        SvKeep.gameWtfFile(kept).writeText(dir.absolutePath)
        val r = ResetRun({ NoPrefs }, kept, File(root, "combat"), {}).run(ResetPlan.Button.SETTINGS, null, false, gameRunning = true)
        assertTrue(r.gameLater)
        assertEquals(1, optims(root).size)
        assertTrue(File(dir, GameConfigWtf.RESET_RESTORE).isFile)
        val r2 = ResetRun({ NoPrefs }, kept, File(root, "combat"), {}).run(ResetPlan.Button.SETTINGS, null, false, gameRunning = false)
        assertEquals(listOf(GameConfigWtf.WORK_OPTIMS_KEY), r2.gameKeys)
        assertTrue(optims(root).isEmpty())
    }

    @Test fun theReportCountsItAsOurs() {
        assertFalse(GameConfigWtf.reportLine(GameConfigWtf.WORK_OPTIMS_LINE + "\n").contains("WORK_OPTIMS"))
    }

    private object NoPrefs : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = HashMap<String, Any?>()
        override fun getString(k: String?, d: String?) = d
        override fun getStringSet(k: String?, d: MutableSet<String>?) = d
        override fun getInt(k: String?, d: Int) = d
        override fun getLong(k: String?, d: Long) = d
        override fun getFloat(k: String?, d: Float) = d
        override fun getBoolean(k: String?, d: Boolean) = d
        override fun contains(k: String?) = false
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
}
