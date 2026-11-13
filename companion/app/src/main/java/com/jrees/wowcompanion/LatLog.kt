package com.jrees.wowcompanion

/**
 * THE LATENCY MEASUREMENT (the user 2026-10-08: "make things smoother" -> "(c)": measure, trim, snappier motion). Inside the private
 * launcher, one line per stage of the chain goes to /sdcard/WowCompanion/logs/lat.log (one 1 MB rotation), wall-clock milliseconds:
 *   pad <key>          a pad press reached DS Forever (PadFeed)
 *   click x,y / key k  DS Forever's input reached the launcher's X server (InputSink)
 *   doc <type> gt=<_gt> an answer document from the add-on was read (CompanionHost)
 *   keylag ...         the game's own key presses: event time -> the launcher's game handler (KeyLag)
 *   padslow ...        a room's pad delivery that held the main thread [PadSlow.SLOW_MS] or more, named (PadSlow)
 * Read once after a play session, the gaps give each stage's time. Off in the app ([on] is set only by the launcher's glue).
 *
 * 1.0.29 key-lag fix (the user: "insane input lag on my face buttons"): [mark] ran on the main thread for every pad key BEFORE the
 * game got it, and did mkdirs + length + append through FUSE under a lock the InputSink worker held while it wrote too. Now [mark]
 * stamps the time and queues the line ([BgAppend]); the writer thread appends every 500 ms. The caller never waits.
 */
object LatLog {
    @Volatile var on = false
    @Volatile internal var out = BgAppend({ java.io.File("/sdcard/WowCompanion/logs/lat.log") })

    fun mark(stage: String, detail: String = "") {
        if (!on) return
        out.add("${System.currentTimeMillis()} $stage $detail")
    }

    /** lines the queue had no room for (the run's total) */
    fun dropped(): Long = out.dropped.get()
}
