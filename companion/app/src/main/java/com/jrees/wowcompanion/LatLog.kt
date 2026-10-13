package com.jrees.wowcompanion

/**
 * THE LATENCY MEASUREMENT (the user 2026-10-08: "make things smoother" -> "(c)": measure, trim, snappier motion). Inside the private
 * launcher, one line per stage of the chain goes to /sdcard/WowCompanion/logs/lat.log (one 1 MB rotation), wall-clock milliseconds:
 *   pad <key>          a pad press reached DS Forever (PadFeed)
 *   click x,y / key k  DS Forever's input reached the launcher's X server (InputSink)
 *   doc <type> gt=<_gt> an answer document from the add-on was read (CompanionHost)
 * Read once after a play session, the gaps give each stage's time. Off in the app ([on] is set only by the launcher's glue).
 */
object LatLog {
    @Volatile var on = false
    private val file = java.io.File("/sdcard/WowCompanion/logs/lat.log")
    private val lock = Any()

    fun mark(stage: String, detail: String = "") {
        if (!on) return
        val line = "${System.currentTimeMillis()} $stage $detail\n"
        synchronized(lock) {
            try {
                file.parentFile?.mkdirs()
                if (file.length() > 1_000_000L) file.renameTo(java.io.File(file.path + ".1"))
                file.appendText(line)
            } catch (_: Throwable) {}
        }
    }
}
