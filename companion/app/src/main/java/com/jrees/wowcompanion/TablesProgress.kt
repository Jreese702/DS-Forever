package com.jrees.wowcompanion

/**
 * THE GAME'S TABLES, DOWNLOADED WITH A PROGRESS LINE (b-art-progress, DS Forever 1.0.8): after a game update the art's first open
 * fetches the build's ENCODING and ROOT tables (about 140 + 40 MB) from the CDN, and "Game art: checking" sat still for about a minute.
 * Now the launcher says "Downloading the game's tables: 87 / 141 MB", counted over every table this open fetches (the total grows as
 * each one's size is known), and on mobile data that Wi-Fi is better (b-art-update-size). Pure: the off-device tests drive it.
 */
object TablesProgress {
    const val PREFIX = "Downloading the game's tables"

    /** files smaller than this are not worth a line (configs, indexes) */
    const val MIN_BYTES = 1L shl 20

    fun mb(bytes: Long): Long = if (bytes <= 0) 0 else maxOf(1L, (bytes + (1L shl 19)) shr 20)

    fun line(done: Long, total: Long, metered: Boolean): String =
        "$PREFIX: ${mb(done)} / ${mb(total)} MB" + if (metered) " (on mobile data: Wi-Fi is better)" else ""

    /**
     * One open's downloads: [start] each file (its announced size), [progress] as bytes arrive, [finish] when it is whole. Lines go to
     * [emit] at most every [EVERY_MS] (and on each start / finish).
     */
    class Counter(private val metered: Boolean, private val emit: (String) -> Unit, private val clock: () -> Long = System::currentTimeMillis) {
        private var before = 0L          // the finished files of this open
        private var size = 0L            // the current file's announced size
        private var got = 0L
        private var at = 0L

        fun start(fileBytes: Long) { size = fileBytes.coerceAtLeast(0); got = 0; say(force = true) }
        fun progress(fileBytesSoFar: Long) { got = fileBytesSoFar; say(force = false) }
        fun finish() { before += maxOf(size, got); size = 0; got = 0; say(force = true) }

        val done: Long get() = before + got
        val total: Long get() = before + maxOf(size, got)

        private fun say(force: Boolean) {
            val now = clock()
            if (!force && now - at < EVERY_MS) return
            at = now
            if (total > 0) emit(line(done, total, metered))
        }
    }

    const val EVERY_MS = 250L
}
