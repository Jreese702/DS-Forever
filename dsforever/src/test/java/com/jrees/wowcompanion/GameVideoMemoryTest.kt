package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #56 G1 / G2 / G3 (the user's approval 2026-11-19): the container's video memory cap by the Thor's RAM, BCn kept compressed on an
 * Adreno, and Low-memory graphics' extra cuts on the 12 GB Pro too (originals recorded and given back as on the 8 GB Thors).
 */
class GameVideoMemoryTest {
    private val G = GameVideoMemory
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val repo = src.parentFile.parentFile.parentFile.parentFile.parentFile.parentFile.parentFile.parentFile
    private val screen get() = File(repo, "app/src/main/java/app/gamenative/ui/screen/wow/WoWForeverScreen.kt").readText()

    // ------------------------------------------------------------------ G1 the cap

    @Test fun theCapFollowsTheThorsRam() {
        assertEquals(2048, G.capMb(7_191L)); assertEquals(2048, G.capMb(7_700L))
        assertEquals("the Lite is an 8 GB model", 2048, G.capMb(7_100L, lite = true))
        assertEquals(3072, G.capMb(11_265L))
        assertEquals(4096, G.capMb(15_255L)); assertEquals(4096, G.capMb(15_621L))
        assertNull("an unknown RAM: no cap", G.capMb(0L))
        assertNull(G.capMb(-1L))
    }

    @Test fun theEnvironmentIsOneDxvkConfigEntryDxvkCanParse() {
        assertEquals("DXVK_CONFIG=dxgi.maxDeviceMemory=3072;dxgi.maxSharedMemory=3072", G.env(3072, on = true))
        assertEquals("off: nothing", "", G.env(3072, on = false))
        assertEquals("no cap: nothing", "", G.env(null, on = true))
        // DXVK 2.4.1's parser (util/config/config.cpp): the value split on ';', a key of [A-Za-z0-9._], '=', a value up to whitespace
        val parsed = dxvkParse(G.dxvkConfig(2048))
        assertEquals(mapOf("dxgi.maxDeviceMemory" to "2048", "dxgi.maxSharedMemory" to "2048"), parsed)
        // the container's env string splits on spaces: none in the value
        assertFalse(G.env(4096, true).contains(' '))
        // what DXVKHelper writes today parses to NOTHING (a leading quote is no key character): why our own value is needed
        assertEquals(emptyMap<String, String>(), dxvkParse("\"dxgi.maxDeviceMemory = 2048\ndxgi.maxSharedMemory = 2048\n\""))
    }

    // ------------------------------------------------------------------ Light 8 (LIGHT8_PLAN.md change 5, the user's answer Q2: both, 8 GB only)

    @Test fun light8sTwoDxvkOptionsByThorAndWithTheCapOff() {
        val opts = mapOf("dxvk.trackPipelineLifetime" to "true", "dxvk.numCompilerThreads" to "4")
        // the 8 GB Thor, the cap on: the cap and both options in one DXVK_CONFIG DXVK can parse
        val on8 = G.env(G.capMb(7_191L), on = true, light8 = true)
        assertEquals("DXVK_CONFIG=dxgi.maxDeviceMemory=2048;dxgi.maxSharedMemory=2048;dxvk.trackPipelineLifetime=true;dxvk.numCompilerThreads=4", on8)
        assertEquals(mapOf("dxgi.maxDeviceMemory" to "2048", "dxgi.maxSharedMemory" to "2048") + opts, dxvkParse(on8.removePrefix("DXVK_CONFIG=")))
        // the cap off (the setting) or no cap: the options still there, built apart from the cap
        assertEquals("DXVK_CONFIG=dxvk.trackPipelineLifetime=true;dxvk.numCompilerThreads=4", G.env(2048, on = false, light8 = true))
        assertEquals("DXVK_CONFIG=dxvk.trackPipelineLifetime=true;dxvk.numCompilerThreads=4", G.env(null, on = true, light8 = true))
        assertEquals(opts, dxvkParse(G.LIGHT8_DXVK))
        assertFalse("no spaces (the container's env string splits on them)", on8.contains(' '))
        // the 12 GB Pro, the 16 GB Max: test build 1516's string exactly
        assertEquals(G.env(3072, true), G.env(G.capMb(11_265L), on = true, light8 = false))
        assertEquals(G.env(4096, true), G.env(G.capMb(15_255L), on = true, light8 = false))
        assertEquals("", G.env(3072, on = false, light8 = false))
        // logged once per launch, the exact string
        assertEquals("light8 on, dxcfg dxgi.maxDeviceMemory=2048;dxgi.maxSharedMemory=2048;dxvk.trackPipelineLifetime=true;dxvk.numCompilerThreads=4",
            G.dxcfgLine(on8))
        val choice = screen.substring(screen.indexOf("private fun videoMemoryChoice("), screen.indexOf("private fun containerConfig("))
        assertTrue(choice.contains("com.jrees.wowcompanion.MemWatch.note(\"launch: \$l8\")"))
        assertTrue(choice.contains("return VideoMemoryChoice(env, bcn)"))
    }

    /** a copy of DXVK 2.4.1's DXVK_CONFIG handling (str::split on ";" then parseUserConfigLine), enough for these options */
    private fun dxvkParse(conf: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun ws(c: Char) = c == ' ' || c == '\t' || c == '\r'
        fun keyChar(c: Char) = c.isLetterOrDigit() && c.code < 128 || c == '.' || c == '_'
        for (line in conf.split(';')) {
            var n = 0
            while (n < line.length && ws(line[n])) n++
            val k = StringBuilder(); while (n < line.length && keyChar(line[n])) k.append(line[n++])
            while (n < line.length && ws(line[n])) n++
            if (n >= line.length || line[n] != '=') continue
            n++
            while (n < line.length && ws(line[n])) n++
            val v = StringBuilder(); var inside = false
            while (n < line.length) {
                if (line[n] == '"') { inside = !inside; n++ } else if (!inside && ws(line[n])) break else v.append(line[n++])
            }
            out[k.toString()] = v.toString()
        }
        return out
    }

    @Test fun theLaunchLinesSayTheValueUsed() {
        assertEquals("video memory cap 3072 MB (11265 MB RAM), BCn native (emulation none)", G.line(3072, true, 11_265L, "none"))
        assertEquals("video memory cap off (setting), BCn emulation auto", G.line(3072, false, 11_265L, "auto"))
        assertEquals("video memory cap none (RAM unknown), BCn native (emulation none)", G.line(null, true, 0L, "none"))
        assertEquals("vcap 2048", G.short(2048, true)); assertEquals("vcap off", G.short(2048, false)); assertEquals("vcap off", G.short(null, true))
        val mem = File(src, "MemWatch.kt").readText()
        assertTrue("every WCMEM line while the game is up", mem.contains("if (up && launchGfx.isNotEmpty()) add(launchGfx)"))
    }

    @Test fun theContainerCarriesTheCapAndTheBcnModeOnLocalPlayOnly() {
        val s = screen
        val cfg = s.substring(s.indexOf("private fun containerConfig("), s.indexOf("private fun installBundledComponents("))
        assertTrue(cfg.contains("com.jrees.wowcompanion.DxvkLogs.ENV +"))
        assertTrue("after DxvkLogs' vars", cfg.contains("(if (gfx.env.isNotEmpty()) \" \" + gfx.env else \"\")"))
        assertTrue(cfg.contains("bcnEmulation=\${gfx.bcn},quality=high"))
        assertFalse("never the Wrapper's own heap cap", cfg.contains("maxDeviceMemory"))
        // the one launch route that builds the Wine container (streaming never calls prepareLaunch)
        val prep = s.substring(s.indexOf("private fun prepareLaunch("), s.indexOf("/** #56 G1 + G3: this launch's video memory"))
        assertTrue(prep.contains("val gfx = videoMemoryChoice(context)"))
        assertTrue(prep.indexOf("Settings.init(context)") < prep.indexOf("videoMemoryChoice(context)"))
        val choice = s.substring(s.indexOf("private fun videoMemoryChoice("), s.indexOf("private fun containerConfig("))
        assertTrue(choice.contains("Settings.vramCap") && choice.contains("MemWatch.note(\"launch: \$line\")"))
        // XServerScreen's driver env comes first, the container's after (so DXVK_CONFIG here wins over DXVKHelper's)
        val x = File(repo, "app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt").readText()
        assertTrue(x.indexOf("extractGraphicsDriverFiles(\n") < x.indexOf("PluviaApp.xEnvironment = setupXEnvironment("))
        assertTrue(x.contains("envVars.putAll(container.envVars)"))
    }

    @Test fun theSwitchIsOnByDefaultAndResetsAsASetting() {
        val s = File(src, "Settings.kt").readText()
        assertTrue(s.contains("get() = prefs?.getBoolean(\"vramCap\", true) ?: true"))
        assertTrue(ResetPlan.PREFS.any { it.file == "settings" && it.key == "vramCap" && it.kind == ResetPlan.Kind.SETTINGS })
    }

    // ------------------------------------------------------------------ G3 BCn

    @Test fun anAdrenoKeepsBcnCompressedAnythingElseKeepsTheWrappersAuto() {
        assertEquals("none", G.bcnEmulation(adreno = true))
        assertEquals("auto", G.bcnEmulation(adreno = false))
        // XServerScreen: "none" is WRAPPER_EMULATE_BCN 0 (auto is 3)
        val x = File(repo, "app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt").readText()
        assertTrue(x.contains("\"none\" -> envVars.put(\"WRAPPER_EMULATE_BCN\", \"0\")"))
        assertTrue(screen.contains("G.bcnEmulation(runCatching { GPUInformation.isAdrenoGPU(context) }.getOrDefault(false))"))
    }

    // ------------------------------------------------------------------ G2 the 12 GB Pro's cuts

    @Test fun theCutsReachTheTwelveGigabyteProButNeverTheMax() {
        assertTrue(DeviceTier.lowMemCuts(7_191L)); assertTrue(DeviceTier.lowMemCuts(7_100L, lite = true))
        assertTrue("the 12 GB Pro (#56 G2)", DeviceTier.lowMemCuts(11_265L))
        assertFalse("the 16 GB Max", DeviceTier.lowMemCuts(15_255L))
        assertFalse("unknown", DeviceTier.lowMemCuts(0L))
        assertTrue(screen.contains("lowMemory8 = com.jrees.wowcompanion.DeviceTier.lowMemCuts())"))
    }

    @Test fun onATwelveGigabyteThorTheOriginalsComeBackOffAndByReset() {
        val us = mapOf("Branch" to "us")
        val root = Files.createTempDirectory("lowmem12").toFile()
        val wtf = File(root, "${WowProduct.LAUNCH.flavorDir}/WTF/Config.wtf").apply { parentFile.mkdirs() }
        wtf.writeText("SET portal \"us\"\nSET graphicsViewDistance \"7\"\nSET particleDensity \"80\"\nSET graphicsShadowQuality \"3\"\n")
        fun conf() = wtf.readLines().mapNotNull { l -> l.removePrefix("SET ").split(" ", limit = 2).takeIf { it.size == 2 } }.associate { it[0] to it[1] }
        val cuts = DeviceTier.lowMemCuts(11_265L)
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, lowMemory = true, lowMemory8 = cuts)
        assertEquals("\"1\"", conf()["graphicsViewDistance"]); assertEquals("\"10\"", conf()["particleDensity"])
        // Reset settings: exactly the recorded values back
        assertTrue(GameConfigWtf.restoreOriginals(wtf.parentFile).contains("graphicsViewDistance"))
        assertEquals("\"7\"", conf()["graphicsViewDistance"]); assertEquals("\"80\"", conf()["particleDensity"])
        assertEquals("\"3\"", conf()["graphicsShadowQuality"])
        // on again, then off: back once more
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, lowMemory = true, lowMemory8 = cuts)
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, lowMemory = false, lowMemory8 = cuts)
        assertEquals("\"7\"", conf()["graphicsViewDistance"]); assertEquals("\"80\"", conf()["particleDensity"])
        assertFalse(File(wtf.parentFile, GameConfigWtf.LOW_MEMORY_8GB_SAVED).exists())
    }

    @Test fun theReportSaysWhatTheLowMemoryKeysHoldNumbersOnly() {
        val line = GameConfigWtf.lowMemoryValues("SET graphicsTextureResolution \"0\"\nSET worldbasemip \"2\"\nSET farclip \"800\"\n" +
            "SET graphicsViewDistance \"x@y\"\nSET accountName \"someone@example.com\"\n")
        assertTrue(line.startsWith("worldBaseMip 2, RAIDworldBaseMip -, graphicsTextureResolution 0,"))
        assertTrue(line.contains("farclip 800")); assertTrue("never a non-number", line.contains("graphicsViewDistance ?"))
        assertFalse(line.contains("example.com"))
        assertEquals("missing", GameConfigWtf.lowMemoryValues(null))
        assertTrue(File(src, "StartupDiag.kt").readText().contains("out += \"Low-memory keys: \" + runCatching {"))
    }
}
