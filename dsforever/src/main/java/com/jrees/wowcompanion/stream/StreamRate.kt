package com.jrees.wowcompanion.stream

import android.app.Activity
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Surface

/**
 * 120 FPS ON THE TOP SCREEN (the user's yes, 2026-11-17: a player asked for it). Offered only when this Thor can show it: a
 * hardware decoder that takes the stream's size at 120 fps (MediaCodec's VideoCapabilities) and a top-screen mode of 119 Hz or
 * more at the screen's own size. While a 120 stream runs, the stream's Surface asks for 120 (Surface.setFrameRate, API 30) and the
 * window for the 120 Hz mode (preferredDisplayModeId); both are given back when the stream ends.
 */
object StreamRate {
    private const val TAG = "DsfStream"

    /** the decoder's mime for the picked codec (HEVC / AV1 fall back to H.264 when this Thor has no such decoder, as the stream does) */
    fun mimeFor(codec: StreamQuality.Codec): String {
        val want = when (codec) {
            StreamQuality.Codec.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
            StreamQuality.Codec.AV1 -> MediaFormat.MIMETYPE_VIDEO_AV1
            StreamQuality.Codec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
        }
        return if (VideoDecoder.hasDecoder(want)) want else MediaFormat.MIMETYPE_VIDEO_AVC
    }

    /** a decoder for [mime] (hardware first, as the stream picks it) takes [w] x [h] at [fps] */
    fun decoderTakes(mime: String, w: Int, h: Int, fps: Int): Boolean = try {
        val name = VideoDecoder.pickDecoder(mime)
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == name }
        val caps = info?.getCapabilitiesForType(mime)?.videoCapabilities
        caps != null && caps.areSizeAndRateSupported(w, h, fps.toDouble())
    } catch (_: Throwable) { false }

    /** the fastest refresh rate among the display's modes at its current size */
    fun maxHz(d: Display?): Float {
        if (d == null) return 0f
        val now = d.mode
        return d.supportedModes.filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
            .maxOfOrNull { it.refreshRate } ?: d.refreshRate
    }

    /** the mode id for [fps] at the display's current size (the closest at or above it), 0 = none */
    fun modeFor(d: Display?, fps: Int): Int {
        if (d == null) return 0
        val now = d.mode
        return d.supportedModes.filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight && it.refreshRate >= fps - 1f }
            .minByOrNull { it.refreshRate }?.modeId ?: 0
    }

    @Suppress("DEPRECATION")
    fun display(a: Activity): Display? = try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) a.display else a.windowManager.defaultDisplay } catch (_: Throwable) { null }

    /** this Thor shows [q]'s size at 120 */
    fun can120(a: Activity, q: StreamQuality): Boolean {
        val ok = StreamQuality.can120(decoderTakes(mimeFor(q.codec), q.width, q.height, 120), maxHz(display(a)))
        return ok
    }

    /** the stream started at [fps]: at 120 the surface and the window ask the top screen for its 120 Hz mode */
    fun request(a: Activity, surface: Surface, fps: Int) {
        if (fps < 100) return
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) surface.setFrameRate(fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
        catch (t: Throwable) { Log.w(TAG, "setFrameRate: ${t.message}") }
        val d = display(a)
        val id = modeFor(d, fps)
        if (id != 0) a.window.attributes = a.window.attributes.also { it.preferredDisplayModeId = id }
        Log.i(TAG, "refresh: asked $fps Hz (mode $id; the screen's fastest ${maxHz(d)} Hz)")
    }

    /** the stream ended: the system's own choice again */
    fun release(a: Activity, surface: Surface?) {
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && surface != null && surface.isValid) surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) } catch (_: Throwable) {}
        try { if (a.window.attributes.preferredDisplayModeId != 0) a.window.attributes = a.window.attributes.also { it.preferredDisplayModeId = 0 } } catch (_: Throwable) {}
    }
}
