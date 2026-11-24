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

        /** how long since the last move at [now] (ms); [Long.MAX_VALUE] = no move seen */
        fun sinceMove(now: Long): Long = if (movedAt == 0L) Long.MAX_VALUE else (now - movedAt).coerceAtLeast(0L)

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

    /**
     * BUILD NOW, IN GAME (the user, 2026-11-24: "I also want a button in game to force building in game"; the picks: the button in
     * Settings > Performance > Indoor maps, and "Only while I'm idle": "Builds only when you stand still out of combat (in town, AFK),
     * pauses the moment you move"). Pure: [reason] says why the builder may not build beside the game now (null = it may). The player
     * must be in the world past a loading screen's first minute (the 1.0.29 - 1.0.31 kills came 7 - 36 s after one: [PlayGate.BESIDE_CALM_MS]),
     * not on a flight, out of a fight and standing still, each for [SETTLE_MS]; and the device must have the memory: [OPEN_MIN] free to
     * open the game files (~500 MB of index tables), [RUN_MIN] while it builds - more on the 8 / 12 GB Thors ([small]), and never for the
     * rest of that game once Android warned it runs low there (any Thor: within [PRESSURE_MS]).
     */
    object Beside {
        /** standing still and out of a fight this long before it builds (the user's "only while I'm idle") */
        const val SETTLE_MS = 8_000L
        /**
         * ... and this long before it opens the game files AGAIN in the same game (they were let go after a walk of a minute, a load, low
         * memory): each open is ~5 s of a core and +500-630 MB beside the game, so a player who stops and goes does not pay it every minute
         */
        const val REOPEN_SETTLE_MS = 30_000L
        /** the add-on's documents come at least every 15 s (its heartbeat): none for this long = the game is not answering (a load) */
        const val ALIVE_MS = 30_000L
        /** a building under way when the player moves gets this long to finish; then it is cancelled (not counted as a failure) */
        const val CANCEL_GRACE_MS = 1_500L
        /** free memory needed to open the game files beside the game (16 GB Thor) / while building beside it */
        const val OPEN_MIN = 2_560L shl 20
        const val RUN_MIN = 1_536L shl 20
        /** ... on the 8 / 12 GB Thors (DeviceTier.lowMemCuts: 317 MB free at worst while playing on an 8 GB one) */
        const val OPEN_MIN_SMALL = 3_584L shl 20
        const val RUN_MIN_SMALL = 2_304L shl 20
        /** a 16 GB Thor: Android's running-low warning pauses it this long */
        const val PRESSURE_MS = 5 * 60_000L

        /**
         * The game's state as the builder sees it. [inWorld]: the game is in the world (GameLife.worldNow); [calmMs]: since the world
         * entry or the last loading screen (GameLife.calmMs: 0 while one is up); [loading]: the add-on's word on a loading or setup screen;
         * [aliveAgoMs]: since the add-on's last live document; [fight]: in a fight now; [sinceFightMs] / [sinceMoveMs]: since the last
         * fight ended / the last move ([Long.MAX_VALUE] = none seen); [taxi]: the last position was on a flight; [memAvailable]: MemAvailable
         * (null = not readable: not tested); [filesOpen]: the builder holds the game files now (the run threshold, else the open one);
         * [small]: an 8 / 12 GB Thor; [pressure]: Android's warning counts now; [artRunning]: an art step really works beside the game;
         * [reopen]: the builder already let the game files go once in this game (the open again waits [REOPEN_SETTLE_MS])
         */
        data class Now(val inWorld: Boolean, val calmMs: Long, val loading: Boolean = false, val aliveAgoMs: Long = 0L,
                       val fight: Boolean = false, val sinceFightMs: Long = Long.MAX_VALUE, val sinceMoveMs: Long = Long.MAX_VALUE,
                       val taxi: Boolean = false, val memAvailable: Long? = null, val filesOpen: Boolean = false, val small: Boolean = false,
                       val pressure: Boolean = false, val artRunning: Boolean = false, val reopen: Boolean = false)

        fun reason(n: Now): String? {
            val settle = if (!n.filesOpen && n.reopen) REOPEN_SETTLE_MS else SETTLE_MS
            return reasonAfter(n, settle)
        }

        private fun reasonAfter(n: Now, settle: Long): String? = when {
            !n.inWorld || n.loading || n.calmMs < PlayGate.BESIDE_CALM_MS || n.aliveAgoMs > ALIVE_MS -> PlayGate.PAUSED_LOADING
            n.fight || n.sinceFightMs < settle -> PlayGate.PAUSED_FIGHT
            n.sinceMoveMs < settle -> if (n.taxi) PlayGate.PAUSED_FLIGHT else PlayGate.PAUSED_MOVING
            n.pressure -> PlayGate.PAUSED_MEMORY
            n.memAvailable != null && n.memAvailable < minFree(n.small, n.filesOpen) -> PlayGate.PAUSED_MEMORY
            n.artRunning -> PlayGate.ART_FIRST
            else -> null
        }

        /** the free memory [reason] asks for */
        fun minFree(small: Boolean, filesOpen: Boolean): Long =
            if (small) (if (filesOpen) RUN_MIN_SMALL else OPEN_MIN_SMALL) else (if (filesOpen) RUN_MIN else OPEN_MIN)

        /** a wait that lets the game files go at once (a load: the game takes its memory then; low memory), not after the long pause */
        fun letGoNow(why: String?): Boolean = why == PlayGate.PAUSED_LOADING || why == PlayGate.PAUSED_MEMORY

        /** Android's warning ([MemWatch.pressureAt]) counts: for the rest of this game on an 8 / 12 GB Thor, [PRESSURE_MS] on another */
        fun pressure(small: Boolean, pressureAt: Long, gameStartedAt: Long, now: Long): Boolean = pressureAt > 0L &&
            if (small) gameStartedAt > 0L && pressureAt >= gameStartedAt else now - pressureAt in 0L until PRESSURE_MS
    }
}
