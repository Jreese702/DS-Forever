package com.jrees.wowcompanion

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** b-dxvk-load-crash (DS Forever 1.0.8), off-device: DXVK's log is on, kept across a restart, and ready for the report */
class DxvkLogsTest {
    private fun tmp(): File = Files.createTempDirectory("dxvk").toFile()

    @Test fun theContainerAsksForWarningsInAPrivateFolder() {
        val env = DxvkLogs.ENV.split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("warn", env["DXVK_LOG_LEVEL"])
        assertEquals("E:/dxvk-logs", env["DXVK_LOG_PATH"])             // Wine's E: = <app data>/storage
        assertFalse(DxvkLogs.ENV.contains('\\'))                      // no escapes for the container's EnvVars parser
        assertEquals(File("/data/x/storage/dxvk-logs"), DxvkLogs.dir(File("/data/x")))
    }

    @Test fun aRestartAfterACrashKeepsTheCrashedRunsLog() {
        val d = tmp()
        DxvkLogs.rotate(d)                                            // first launch: the folder is made, nothing to move
        assertTrue(d.isDirectory)
        File(d, "Wow_d3d11.log").writeText("err: DxvkMemoryAllocator: Memory allocation failed\n")   // the crashed run
        DxvkLogs.rotate(d)                                            // the next launch
        assertFalse(File(d, "Wow_d3d11.log").exists())
        assertEquals("err: DxvkMemoryAllocator: Memory allocation failed\n", File(d, "previous/Wow_d3d11.log").readText())
        File(d, "Wow_d3d11.log").writeText("info: fine\n")
        DxvkLogs.rotate(d)                                            // only the last run's is kept as previous
        assertEquals("info: fine\n", File(d, "previous/Wow_d3d11.log").readText())
    }

    @Test fun theReportGetsBothRunsNewestLinesKept() {
        val d = tmp()
        File(d, "previous").mkdirs()
        File(d, "previous/Wow_d3d11.log").writeText("err: Failed to bind device memory\n")
        File(d, "Wow_dxgi.log").writeText("x".repeat(50_000))
        val r = DxvkLogs.forReport(d, maxChars = 1_000)
        assertTrue(r.contains("--- Wow_d3d11.log (previous run) ---"))
        assertTrue(r.contains("Failed to bind device memory"))
        assertTrue(r.contains("--- Wow_dxgi.log (this run) ---\n[older lines cut]"))
        assertTrue(r.length < 3_000)
        assertEquals("", DxvkLogs.forReport(tmp()))
    }
}
