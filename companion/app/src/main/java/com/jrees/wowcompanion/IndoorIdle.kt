package com.jrees.wowcompanion

import java.io.File

/**
 * WHEN THE INDOOR MAPS MAY BUILD WHILE THE GAME RUNS (board b-interiors-stutter, 2026-10-13; the user's pick "Only when standing
 * still"): the bake ran at ~94% of a core the whole walk and the CPU sat at 86-95 C. Pure Kotlin (the off-device tests drive it);
 * [IndoorSchedulerStep] feeds it and [IndoorScheduler]'s gate asks it at every yield point.
 *
 * - [Motion]: the add-on sends a "pos" document only while the player moves (and a full snapshot every 15 s standing still), so the
 *   player is moving until [Motion.STILL_MS] after the last position that CHANGED (a repeat of the same place is not a move).
 * - [Heat]: the CPU's own temperature with hysteresis (pause at [Heat.PAUSE_C], resume at [Heat.RESUME_C]); [CpuThermal] reads it.
 */
internal object IndoorIdle {

    class Motion(private val stillMs: Long = STILL_MS, private val minMoveYd: Double = MIN_MOVE_YD) {
        private var last: IndoorBakeQueue.Position? = null
        private var movedAt = 0L

        /** a position from the add-on at [now] (ms): the first one, a new continent or a step of at least [minMoveYd] is a move */
        fun note(p: IndoorBakeQueue.Position, now: Long) {
            val l = last
            if (l == null || l.continent != p.continent || Math.hypot(p.x - l.x, p.y - l.y) >= minMoveYd) movedAt = now
            last = p
        }

        /** moved within the last [stillMs] */
        fun moving(now: Long): Boolean = movedAt != 0L && now - movedAt < stillMs

        companion object {
            /** standing still this long lets the bake go on (the user's "only when standing still") */
            const val STILL_MS = 3_000L
            /** less than this between two positions is standing (the snapshot repeats the same place) */
            const val MIN_MOVE_YD = 0.5
        }
    }

    object Heat {
        /** the Thor measured 86-95 C on cpuss-* while walking with the bake running: above this the bake waits */
        const val PAUSE_C = 85f
        /** ...and resumes only once the CPU is back under this (no flapping) */
        const val RESUME_C = 75f

        /** hysteresis: hot until it drops under [RESUME_C], cool until it reaches [PAUSE_C]; null (unreadable) = not hot by this test */
        fun hot(was: Boolean, tempC: Float?): Boolean = when {
            tempC == null -> false
            was -> tempC > RESUME_C
            else -> tempC >= PAUSE_C
        }
    }

    /**
     * The hottest CPU thermal zone (types cpuss-*, cpu-*, cpu*: the Thor's Snapdragon names), in C, from /sys/class/thermal (no
     * permission; a device whose SELinux hides them gives null and the PowerManager signals stay the only heat test).
     */
    class CpuThermal(private val root: File = File("/sys/class/thermal")) {
        private var zones: List<File>? = null

        fun maxC(): Float? {
            val z = zones ?: findZones().also { zones = it }
            var max: Float? = null
            for (f in z) {
                val c = runCatching { parseC(f.readText()) }.getOrNull() ?: continue
                if (max == null || c > max) max = c
            }
            return max
        }

        /** the zones' temp files, found once */
        fun findZones(): List<File> = root.listFiles()?.filter { it.name.startsWith("thermal_zone") }?.mapNotNull { z ->
            val type = runCatching { File(z, "type").readText().trim() }.getOrNull() ?: return@mapNotNull null
            File(z, "temp").takeIf { isCpuZone(type) && it.canRead() }
        }.orEmpty()

        companion object {
            fun isCpuZone(type: String): Boolean = type.lowercase().let { it.startsWith("cpu") && !it.startsWith("cpufreq") }

            /** millidegrees (the usual 45000) or plain degrees; null for junk or impossible readings */
            fun parseC(s: String): Float? {
                val v = s.trim().toFloatOrNull() ?: return null
                val c = if (kotlin.math.abs(v) >= 1000f) v / 1000f else v
                return c.takeIf { it > -40f && it < 200f }
            }
        }
    }
}
