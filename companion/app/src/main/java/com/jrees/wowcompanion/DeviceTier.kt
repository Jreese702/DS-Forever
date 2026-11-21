package com.jrees.wowcompanion

import android.app.ActivityManager
import android.content.Context

/**
 * WHICH THOR THIS IS (the user, 2026-11-08: "there are 4 different versions of the ayn thor and I have been developing for the best
 * model"). AYN's four: Lite (Snapdragon 865 / Adreno 650, 8 GB), Base (8 Gen 2 / Adreno 740, 8 GB), Pro (12 GB), Max (16 GB). The
 * player reports show Android counting 7191 MB (8 GB), 11265 MB (12 GB) and 15255 MB (16 GB) of RAM.
 *
 * [lowMemory]: the 8 GB and 12 GB models. There Android killed DS Forever for memory while the game ran (#37); the user's pick: the
 * remaining art never builds in the background while the game is running on these models, and a notice before Play says so.
 */
object DeviceTier {
    @Volatile var totalMb = 0L
        private set

    fun init(ctx: Context) {
        if (totalMb > 0) return
        runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
            totalMb = mi.totalMem / (1024 * 1024)
        }
    }

    /** under ~13 GB counted: the 8 GB and 12 GB models (never true while the RAM is unknown) */
    fun smallModel(): Boolean = lowMemory(totalMb)
    /** the lighter behaviour: a small model, or Settings > Performance > Lighter mode on */
    fun lowMemory(): Boolean = smallModel() || runCatching { Settings.lighterMode }.getOrDefault(false)
    fun lowMemory(mb: Long): Boolean = mb in 1 until LOW_MB

    /** the Thor Lite's Snapdragon 865 (kona) / Adreno 650 */
    fun isLite(socModel: String?, hardware: String?, renderer: String?): Boolean =
        socModel.orEmpty().contains("SM8250", true) || hardware.orEmpty().equals("kona", true) || renderer.orEmpty().contains("Adreno (TM) 650", true)

    fun isLite(): Boolean = isLite(if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else null, android.os.Build.HARDWARE, null)

    /** the model's name for the report (Lite / Base / Pro / Max) */
    fun model(): String = model(totalMb, isLite())
    fun model(mb: Long, lite: Boolean): String = when {
        lite -> "Thor Lite (Snapdragon 865, 8 GB)"
        mb <= 0 -> "unknown"
        mb < 9_500 -> "Thor Base (8 GB)"
        mb < LOW_MB -> "Thor Pro (12 GB)"
        else -> "Thor Max (16 GB)"
    }

    const val LOW_MB = 13_000L

    /**
     * THE MODEL THAT BUILDS THE WORLD MAP BESIDE THE GAME (the user's pick, 2026-11-16: "Let ONLY the world-map art step build beside
     * the running game on the 16 GB Thor Max (DeviceTier / RAM >= ~15 GB)"; [PlayGate.besideNow]): the Thor Max (Android counts
     * 15255 MB), lighter mode off (Settings > Performance > Lighter mode = the lighter behaviour: it waits like every other step).
     * Never while the RAM is unknown.
     */
    const val BESIDE_MB = 15_000L
    fun besideModel(mb: Long, lighter: Boolean): Boolean = mb >= BESIDE_MB && !lighter
    // test build 1489 (the user's pick 2026-11-18): this one exception stays - the 8 / 12 GB Thors never build art while playing
    fun besideModel(): Boolean = besideModel(totalMb, runCatching { Settings.lighterMode }.getOrDefault(false))

    /**
     * LOW-MEMORY GRAPHICS BY MODEL (the user's approval 2026-11-18, #56; Settings.lowMemGraphics while the player never picked): on for
     * the 8 GB Thors (Base, Lite) and the 12 GB Pro ([lowMemory]), off for the 16 GB Thor Max (the earlier ruling) and an unknown RAM
     */
    fun lowMemGraphicsDefault(mb: Long): Boolean = lowMemory(mb)

    /**
     * THE 8 GB THORS (Base, Lite; test build 1489, the user's pick 2026-11-18): Low-memory graphics also lowers view distance,
     * particle / spell density and shadows there (GameConfigWtf.LOW_MEMORY_8GB). Android counts 7191 MB (and 7700 on one report);
     * never while the RAM is unknown, never the 12 GB Pro (11265 MB).
     */
    const val EIGHT_GB_MB = 9_500L
    fun eightGb(mb: Long, lite: Boolean = false): Boolean = mb in 1 until EIGHT_GB_MB || (lite && mb > 0)
    fun eightGb(): Boolean = eightGb(totalMb, runCatching { isLite() }.getOrDefault(false))

    /**
     * LOW-MEMORY GRAPHICS' EXTRA CUTS (view distance, particles, spells, shadows: GameConfigWtf.LOW_MEMORY_8GB) - the 8 GB Thors (test
     * build 1489) and, from #56 G2 (the user's approval 2026-11-19), the 12 GB Pro too: every model under [LOW_MB] (and the Lite). Never
     * the 16 GB Max, never while the RAM is unknown. Still only while the player's Low-memory graphics is on.
     */
    fun lowMemCuts(mb: Long, lite: Boolean = false): Boolean = eightGb(mb, lite) || lowMemory(mb)
    fun lowMemCuts(): Boolean = lowMemCuts(totalMb, runCatching { isLite() }.getOrDefault(false))

    /**
     * LIGHT 8 (the approved plan LIGHT8_PLAN.md; the user's answers 2026-11-21: Q1 no Settings switch, automatic on 8 GB, the report
     * header says it is on): the 8 GB Thors (Base, Lite) count every cache, hold 3 loading pictures at lower colour depth, cap the
     * per-zone data (trimmed to the player's zone and its neighbours on a memory warning), hold the danger re-read and other zones'
     * SafePath data while the game loads, give the game's container two DXVK options and show a one-line tip before Play. The 12 GB Pro,
     * the 16 GB Max and an unknown RAM keep test build 1516's behaviour exactly. [eightGb] is the rule: the Lite only once the RAM is
     * known. [override]: the tests' (no Settings row in this build). READ LIVE at every eviction, bound and check, never once at a
     * cache's construction ([totalMb] stays 0 until [init]).
     */
    fun light8(mb: Long, lite: Boolean, override: Boolean? = null): Boolean = override ?: eightGb(mb, lite)
    /** the tests' override of [light8] (null = the Thor's own RAM decides; nothing in the app sets it) */
    @Volatile internal var light8Override: Boolean? = null
    fun light8(): Boolean = light8(totalMb, runCatching { isLite() }.getOrDefault(false), light8Override)

    /**
     * HOME'S MAP AT FULL DETAIL (the user, 2026-11-17: "I am slightly concerned about the map turning into a memory sink, if it does we
     * might need to evaluate how big it is and turn this into a Thor based toggle"): by default the whole-world zoom-out on the Map
     * room's art only on the 16 GB Thor Max (Android counts 15255 MB); the 8 / 12 GB Thors and an unknown RAM keep today's lighter map
     * (Settings > Performance > Home map: full detail). The map controls page is everywhere.
     */
    fun homeMapFullDetail(mb: Long): Boolean = mb >= BESIDE_MB

    /** the notice before Play on the 8 / 12 GB models (the user: "some kind of notice for Lower model Thors that the art will not build
     *  in the background if they proceed") */
    const val ART_NOTICE_TITLE = "The rest of the game art builds while the game is closed"
    // 2026-11-11 (issue #108 cluster): every Thor model now holds the background art while the game runs ([PlayGate])
    const val ART_NOTICE_TEXT = "The maps, icons and terrain that are not built yet only build while the game is " +
        "closed, so the game has the memory it needs. Play now and they finish later, or wait here."
}
