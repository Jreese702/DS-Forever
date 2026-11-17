package com.jrees.wowcompanion.stream

import android.content.Context
import org.json.JSONObject

/**
 * "STREAM QUALITY" (STREAMING_PLAN.md section 5: HEVC, 1080p60, about 20 Mbps on Wi-Fi 5/6 by default). The Thor's top screen is
 * 1920x1080, so nothing above 1080p is offered. Pure model + its preferences; the pairing / status screen shows the picker and the
 * QOL settings may reuse [load] / [save].
 */
data class StreamQuality(
    val codec: Codec = Codec.HEVC,
    val height: Int = 1080,
    val fps: Int = 60,
    val bitrateKbps: Int = 20_000,
    /** the PC's sound on the Thor (the default) or kept on the PC */
    val audioOnThor: Boolean = true,
) {
    enum class Codec(val label: String) { HEVC("HEVC"), H264("H.264"), AV1("AV1") }

    val width: Int get() = when (height) { 720 -> 1280; 900 -> 1600; else -> 1920 }

    /** what is asked of the PC: the picked codec plus H.264 as the floor every PC encodes (Moonlight's VIDEO_FORMAT_* bits) */
    fun videoFormats(hasHevcDecoder: Boolean, hasAv1Decoder: Boolean): Int {
        var f = VIDEO_FORMAT_H264
        if ((codec == Codec.HEVC || codec == Codec.AV1) && hasHevcDecoder) f = f or VIDEO_FORMAT_H265
        if (codec == Codec.AV1 && hasAv1Decoder) f = f or VIDEO_FORMAT_AV1_MAIN8
        return f
    }

    val label: String get() = "${codec.label} ${height}p$fps, ${bitrateKbps / 1000} Mbps"

    fun toJson(): JSONObject = JSONObject().put("codec", codec.name).put("height", height).put("fps", fps)
        .put("kbps", bitrateKbps).put("audioOnThor", audioOnThor)

    companion object {
        const val VIDEO_FORMAT_H264 = 0x0001
        const val VIDEO_FORMAT_H265 = 0x0100
        const val VIDEO_FORMAT_AV1_MAIN8 = 0x1000

        val HEIGHTS = listOf(720, 900, 1080)
        /** 120 only where this Thor can show it ([offeredFps]): its decoder at the stream's size and a 120 Hz top screen */
        val FPS = listOf(30, 60, 120)
        val BITRATES_KBPS = listOf(5_000, 10_000, 15_000, 20_000, 30_000, 40_000, 50_000)
        /** 120 fps sends twice the frames: the bit rate suggested with it */
        const val KBPS_FOR_120 = 30_000
        const val NOTE_120 = "120 fps: 30+ Mbps"

        /** the frame rates the picker offers: 120 when [can120] */
        fun offeredFps(can120: Boolean): List<Int> = if (can120) FPS else FPS.filter { it != 120 }

        /** a stored 120 on a Thor (or at a size) that can't show it plays at 60; the stored choice is kept */
        fun forDevice(q: StreamQuality, can120: Boolean): StreamQuality = if (q.fps == 120 && !can120) q.copy(fps = 60) else q

        /** [decoderOk] = the decoder takes the stream's size at 120 fps; [displayMaxHz] = the top screen's fastest mode at its size */
        fun can120(decoderOk: Boolean, displayMaxHz: Float): Boolean = decoderOk && displayMaxHz >= 119f

        /** the picker moved the frame rate: at 120 the bit rate goes up to [KBPS_FOR_120] when it was lower */
        fun withFps(q: StreamQuality, fps: Int): StreamQuality =
            if (fps == 120 && q.fps != 120 && q.bitrateKbps < KBPS_FOR_120) q.copy(fps = fps, bitrateKbps = KBPS_FOR_120) else q.copy(fps = fps)

        /** a stored value, each field checked against what is offered (anything else falls back to the default's) */
        fun fromJson(o: JSONObject?): StreamQuality {
            val d = StreamQuality()
            if (o == null) return d
            return StreamQuality(
                codec = Codec.values().firstOrNull { it.name == o.optString("codec") } ?: d.codec,
                height = o.optInt("height", d.height).takeIf { it in HEIGHTS } ?: d.height,
                fps = o.optInt("fps", d.fps).takeIf { it in FPS } ?: d.fps,
                bitrateKbps = o.optInt("kbps", d.bitrateKbps).takeIf { it in BITRATES_KBPS } ?: d.bitrateKbps,
                audioOnThor = o.optBoolean("audioOnThor", d.audioOnThor),
            )
        }

        fun load(ctx: Context): StreamQuality = fromJson(
            ctx.getSharedPreferences(HostStore.PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { try { JSONObject(it) } catch (_: Throwable) { null } })
        fun save(ctx: Context, q: StreamQuality) {
            ctx.getSharedPreferences(HostStore.PREFS, Context.MODE_PRIVATE).edit().putString(KEY, q.toJson().toString()).apply()
        }
        private const val KEY = "quality"

        /** the next value in a list (the picker's controller left / right), wrapping */
        fun <T> step(list: List<T>, now: T, by: Int): T {
            val i = list.indexOf(now).let { if (it < 0) 0 else it }
            return list[((i + by) % list.size + list.size) % list.size]
        }
    }
}
