package com.jrees.wowcompanion

import java.io.File

/**
 * DXVK's OWN LOG, KEPT FOR THE NEXT CRASH (board b-dxvk-load-crash, DS Forever 1.0.8): the game crashes now and then in d3d11.dll
 * while loading into the world, and the launcher had DXVK log nothing (DXVK_LOG_LEVEL=none), so no crash ever said why. Now the
 * game's container runs DXVK at "warn" into a private folder (Wine's E: drive = <app data>/storage), and before every launch the
 * last run's logs move to "previous/" (DXVK truncates its log when the game starts, so a restart after a crash would erase the
 * evidence). The problem report attaches both, through the privacy pass. Diagnostics only: nothing about rendering changes.
 */
object DxvkLogs {
    /** the container's environment for DXVK's log (Wine path: E: = <app data>/storage; forward slashes, no escapes needed) */
    const val ENV = "DXVK_LOG_LEVEL=warn DXVK_LOG_PATH=E:/dxvk-logs"

    /** the folder on Android, under the app's data dir (its "storage" is Wine's E:) */
    fun dir(appDataDir: File): File = File(appDataDir, "storage/dxvk-logs")

    /** before a launch: the last run's logs into previous/ (replacing the older ones); the folder exists for DXVK */
    fun rotate(dir: File) {
        dir.mkdirs()
        val prev = File(dir, "previous").apply { mkdirs() }
        val logs = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }.orEmpty()
        if (logs.isEmpty()) return
        prev.listFiles()?.forEach { it.delete() }
        for (f in logs) if (!f.renameTo(File(prev, f.name))) { f.copyTo(File(prev, f.name), overwrite = true); f.delete() }
    }

    /** the logs for the report: this run's and the previous run's, newest lines kept within [maxChars] each */
    fun forReport(dir: File, maxChars: Int = 20_000): String {
        val parts = ArrayList<String>()
        for ((label, d) in listOf("this run" to dir, "previous run" to File(dir, "previous"))) {
            for (f in d.listFiles { x -> x.isFile && x.name.endsWith(".log") }.orEmpty().sortedBy { it.name }) {
                val t = runCatching { f.readText() }.getOrDefault("")
                if (t.isBlank()) continue
                parts += "--- ${f.name} ($label) ---\n" + (if (t.length > maxChars) "[older lines cut]\n" + t.takeLast(maxChars) else t)
            }
        }
        return parts.joinToString("\n\n")
    }
}
