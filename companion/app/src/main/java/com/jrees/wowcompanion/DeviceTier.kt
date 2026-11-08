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

    /** the notice before Play on the 8 / 12 GB models (the user: "some kind of notice for Lower model Thors that the art will not build
     *  in the background if they proceed") */
    const val ART_NOTICE_TITLE = "The rest of the game art builds while the game is closed"
    const val ART_NOTICE_TEXT = "On this Thor model, the maps, icons and terrain that are not built yet only build while the game is " +
        "closed, so the game has the memory it needs. Play now and they finish later, or wait here."
}
