package app.gamenative.utils

import com.jrees.wowcompanion.FrameGen
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame generation with the player's own Lossless.dll (board f-frame-gen), off-device: the DLL check and copy, the settings' values,
 * the container extras, conf.toml and the launch env. No real Lossless.dll is used or needed: the DLLs here are made-up PE headers.
 */
class FrameGenTest {
    companion object {
        /** a made-up PE image (see [pe]); shared with [FrameGenLaunchEnvTest] */
        fun peBytes(dll: Boolean, size: Int = 8192): ByteArray = FrameGenTest().pe(dll, size)
    }


    /** a made-up PE image: MZ, e_lfanew = 0x80, "PE\0\0", COFF Characteristics (+ IMAGE_FILE_DLL when [dll]), padded to [size] */
    internal fun pe(dll: Boolean, size: Int = 8192): ByteArray {
        val b = ByteArray(size)
        b[0] = 'M'.code.toByte(); b[1] = 'Z'.code.toByte()
        b[0x3C] = 0x80.toByte()
        b[0x80] = 'P'.code.toByte(); b[0x81] = 'E'.code.toByte()
        val ch = 0x0002 or (if (dll) 0x2000 else 0)          // EXECUTABLE_IMAGE (| DLL)
        b[0x80 + 4 + 18] = (ch and 0xFF).toByte(); b[0x80 + 4 + 19] = (ch shr 8).toByte()
        return b
    }

    private fun tempDir(): File = Files.createTempDirectory("framegen").toFile()

    // ---- the DLL check and copy

    @Test fun aPeDllPasses() = assertNull(FrameGen.peDllProblem(pe(dll = true)))

    @Test fun anExeIsNotADll() = assertEquals("a Windows program, not a DLL", FrameGen.peDllProblem(pe(dll = false)))

    @Test fun otherFilesAreRefused() {
        assertNotNull(FrameGen.peDllProblem(ByteArray(8192)))                      // no MZ
        assertNotNull(FrameGen.peDllProblem("MZ".toByteArray()))                   // too short
        val badOffset = pe(dll = true).also { it[0x3C] = 0x7F; it[0x3D] = 0x7F }   // e_lfanew past the head
        assertNotNull(FrameGen.peDllProblem(badOffset))
        val noSig = pe(dll = true).also { it[0x80] = 'X'.code.toByte() }
        assertEquals("not a Windows DLL (no PE signature)", FrameGen.peDllProblem(noSig))
    }

    @Test fun aGoodPickIsSaved() {
        val d = tempDir()
        val msg = FrameGen.importInto(d, ByteArrayInputStream(pe(dll = true, size = 64 * 1024)))
        assertEquals("Lossless.dll saved (64 KB)", msg)
        assertEquals(64 * 1024L, File(d, "Lossless.dll").length())
        assertFalse(File(d, "Lossless.dll.tmp").exists())
    }

    @Test fun aBadPickNeverReplacesAGoodCopy() {
        val d = tempDir()
        FrameGen.importInto(d, ByteArrayInputStream(pe(dll = true)))
        val before = File(d, "Lossless.dll").readBytes()
        assertTrue(FrameGen.importInto(d, ByteArrayInputStream(pe(dll = false))).startsWith("Not used:"))
        assertEquals("Too small for Lossless.dll", FrameGen.importInto(d, ByteArrayInputStream(ByteArray(100))))
        assertTrue(before.contentEquals(File(d, "Lossless.dll").readBytes()))
        assertFalse(File(d, "Lossless.dll.tmp").exists())
    }

    // ---- the settings' values

    @Test fun flowScaleStaysOnItsGrid() {
        assertEquals(0.80f, FrameGen.sanitizeFlow(0.81f), 1e-6f)
        assertEquals(0.25f, FrameGen.sanitizeFlow(0.0f), 1e-6f)
        assertEquals(1.0f, FrameGen.sanitizeFlow(3.0f), 1e-6f)
        assertEquals(0.80f, FrameGen.sanitizeFlow(Float.NaN), 1e-6f)
        assertEquals(0.85f, FrameGen.stepFlow(0.80f, +1), 1e-6f)
        assertEquals(0.25f, FrameGen.stepFlow(0.25f, -1), 1e-6f)
        assertEquals(1.0f, FrameGen.stepFlow(1.0f, +1), 1e-6f)
        assertEquals("80%", FrameGen.flowLabel(0.8f))
    }

    @Test fun multiplierIsTwoToFour() {
        assertEquals(2, FrameGen.sanitizeMultiplier(0))
        assertEquals(3, FrameGen.sanitizeMultiplier(3))
        assertEquals(4, FrameGen.sanitizeMultiplier(9))
    }

    // ---- the container extras

    @Test fun offOrNoDllOnlyDisarms() {
        val off = FrameGen.Snapshot(enabled = false, multiplier = 3, flowScale = 0.5f, performance = false)
        assertEquals(mapOf(LsfgVkManager.EXTRA_ARMED to "false"), LsfgVkManager.dsForeverExtras(off, dllPresent = true))
        val noDll = off.copy(enabled = true)
        assertEquals(mapOf(LsfgVkManager.EXTRA_ARMED to "false"), LsfgVkManager.dsForeverExtras(noDll, dllPresent = false))
    }

    @Test fun onArmsWithTheValues() {
        val on = FrameGen.Snapshot(enabled = true, multiplier = 3, flowScale = 0.66f, performance = false)
        assertEquals(
            mapOf(
                LsfgVkManager.EXTRA_ARMED to "true",
                LsfgVkManager.EXTRA_MULTIPLIER to "3",
                LsfgVkManager.EXTRA_FLOW_SCALE to "0.65",
                LsfgVkManager.EXTRA_PERFORMANCE_MODE to "false",
            ),
            LsfgVkManager.dsForeverExtras(on, dllPresent = true),
        )
    }

    // ---- conf.toml

    @Test fun configCarriesTheSettings() {
        val toml = LsfgVkManager.buildConfigToml("/x/Lossless.dll", true, 2, 0.8f, true, 0, "mailbox")
        assertTrue(toml, toml.contains("dll = \"/x/Lossless.dll\""))
        assertTrue(toml, toml.contains("multiplier = 2"))
        assertTrue(toml, toml.contains("flow_scale = 0.80"))
        assertTrue(toml, toml.contains("performance_mode = true"))
        assertTrue(toml, toml.contains("experimental_present_mode = \"mailbox\""))
    }

    @Test fun configWithoutGenerationPassesThrough() {
        val toml = LsfgVkManager.buildConfigToml("/x/Lossless.dll", false, 3, 0.8f, true, 0, "mailbox")
        assertTrue(toml, toml.contains("multiplier = 1"))
        assertTrue(toml, toml.contains("performance_mode = false"))
        assertFalse(LsfgVkManager.buildConfigToml(null, false, 1, 0.8f, false, 0, "fifo").contains("[[game]]"))
    }
}
