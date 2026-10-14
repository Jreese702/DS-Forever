package app.gamenative

import android.app.ApplicationExitInfo
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** p-casclib-1000 (a player: "the bottom screen crashed"), off-device: reports written whole, the newest kept */
class CrashLogsTest {
    @Test fun aReportIsWrittenWholeAndOnlyTheNewestStay() {
        val dir = File(Files.createTempDirectory("logs").toFile(), "DS Forever/logs")
        for (i in 0 until 13) CrashLogs.write(dir, "2026-10-14_10-00-%02d_crash.txt".format(i), "report $i", keep = 10)
        val names = dir.list()!!.sorted()
        assertEquals(10, names.size)
        assertEquals("2026-10-14_10-00-03_crash.txt", names.first())   // the three oldest went
        assertFalse(names.any { it.endsWith(".part") })
        assertEquals("report 12", File(dir, names.last()).readText())
    }

    @Test fun theExitsWorthAReport() {
        assertTrue(CrashLogs.worthSaving(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertTrue(CrashLogs.worthSaving(ApplicationExitInfo.REASON_ANR))
        assertTrue(CrashLogs.worthSaving(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertFalse(CrashLogs.worthSaving(ApplicationExitInfo.REASON_EXIT_SELF))
        assertFalse(CrashLogs.worthSaving(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals("native-crash", CrashLogs.reasonName(ApplicationExitInfo.REASON_CRASH_NATIVE))
    }
}
