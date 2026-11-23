package com.jrees.wowcompanion.stream

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream

/**
 * THE STREAM'S PICTURE on the top screen: a hardware MediaCodec decoder rendering straight to the stream's Surface, set up for low
 * latency as moonlight-android's MediaCodecDecoderRenderer is (KEY_LOW_LATENCY, realtime priority, Qualcomm's vendor low-latency
 * keys: the Thor is a Snapdragon). Parameter sets (VPS / SPS / PPS) are collected and handed over as ONE codec-config buffer right
 * before each IDR frame. The output thread renders the newest decoded frame and drops older ones (latency over smoothness).
 * Stream smoothness (2026-11-23): its input buffers take a whole frame of the stream's size ([maxInputSize]: a big keyframe at a high
 * bit rate is never dropped for want of room, which asked for yet another keyframe), and its counters feed the stats line
 * ([snap], [StreamStats]).
 */
class VideoDecoder(private val surface: Surface, private val onFirstFrame: () -> Unit = {}) : MoonBridge.Video {
    private var codec: MediaCodec? = null
    private var output: Thread? = null
    @Volatile private var running = false
    private val csd = ByteArrayOutputStream()
    private var csdPending = false
    private var firstShown = false
    @Volatile var framesIn = 0L; private set
    @Volatile var framesOut = 0L; private set
    /** picture frames the core handed over (queued or not) */
    @Volatile var framesRx = 0L; private set
    @Volatile var framesDecoded = 0L; private set
    /** decoded but a newer one was shown instead (the output thread's count) */
    @Volatile var framesDropped = 0L; private set
    /** never queued: no input buffer in time, or too big (the core's thread's count: one writer each) */
    @Volatile var framesUnqueued = 0L; private set
    @Volatile var bytesRx = 0L; private set
    @Volatile var keyframeAsks = 0L; private set
    /** the decoder threw (queue or drain): [RfiPolicy.trips] reads it */
    @Volatile var decoderErrors = 0L; private set
    private var inputLogged = false
    /** the codec the PC's answer set the decoder up for ("video/hevc"; null = not set up): [RfiPolicy.trips] reads it */
    @Volatile var codecMime: String? = null; private set

    fun snap(nowMs: Long): StatsSnap = StatsSnap(nowMs, framesRx, framesDecoded, framesOut, framesDropped + framesUnqueued, bytesRx, keyframeAsks)

    override fun setup(videoFormat: Int, width: Int, height: Int, fps: Int): Int {
        val mime = mimeFor(videoFormat)
        return try {
            val maxIn = maxInputSize(width, height)
            val fmt = MediaFormat.createVideoFormat(mime, width, height).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)                       // realtime
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxIn)
            }
            val name = pickDecoder(mime) ?: run { Log.w(TAG, "no decoder for $mime"); return -1 }
            val c = MediaCodec.createByCodecName(name)
            var how = "low-latency keys"
            try { c.configure(fmt, surface, null, 0) } catch (t: Throwable) {
                // a decoder that refuses a vendor key: the input size alone, then plain
                Log.w(TAG, "configure with low-latency keys failed (${t.message}); input size only")
                c.reset()
                try {
                    c.configure(MediaFormat.createVideoFormat(mime, width, height).apply { setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxIn) }, surface, null, 0)
                    how = "input size only"
                } catch (t2: Throwable) {
                    Log.w(TAG, "configure with the input size failed (${t2.message}); plain")
                    c.reset()
                    c.configure(MediaFormat.createVideoFormat(mime, width, height), surface, null, 0)
                    how = "plain"
                }
            }
            codec = c
            codecMime = mime
            Log.i(TAG, "video: $name $mime ${width}x$height@$fps ($how, input up to ${maxIn / 1024} KB)")
            0
        } catch (t: Throwable) { Log.w(TAG, "video setup: ${t.message}"); -1 }
    }

    override fun start() {
        val c = codec ?: return
        c.start()
        running = true
        output = Thread({ drain(c) }, "dsf-video-out").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    override fun stop() {
        running = false
        output?.interrupt(); try { output?.join(500) } catch (_: InterruptedException) {}
        output = null
        try { codec?.stop() } catch (_: Throwable) {}
    }

    override fun cleanup() {
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
        csd.reset(); csdPending = false; firstShown = false; inputLogged = false
    }

    override fun submit(data: ByteArray, length: Int, bufferType: Int, frameNumber: Int, frameType: Int, receiveTimeUs: Long): Int {
        val c = codec ?: return askKeyframe()
        if (bufferType != MoonBridge.BUFFER_TYPE_PICDATA) {
            if (!csdPending) { csd.reset(); csdPending = true }
            csd.write(data, 0, length)
            return MoonBridge.DR_OK
        }
        framesRx++
        bytesRx += length
        val idr = frameType == MoonBridge.FRAME_TYPE_IDR
        if (idr && csdPending) {
            val conf = csd.toByteArray(); csdPending = false
            if (!queue(c, conf, conf.size, receiveTimeUs, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) { framesUnqueued++; return askKeyframe() }
        }
        if (!queue(c, data, length, receiveTimeUs, if (idr) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)) { framesUnqueued++; return askKeyframe() }
        framesIn++
        return MoonBridge.DR_OK
    }

    private fun askKeyframe(): Int { keyframeAsks++; return MoonBridge.DR_NEED_IDR }

    private fun queue(c: MediaCodec, data: ByteArray, length: Int, ptsUs: Long, flags: Int): Boolean = try {
        val i = c.dequeueInputBuffer(INPUT_WAIT_US)
        if (i < 0) false else {
            val buf = c.getInputBuffer(i) ?: return false
            buf.clear()
            if (!inputLogged) { inputLogged = true; Log.i(TAG, "video: input buffers ${buf.capacity() / 1024} KB") }
            if (length > buf.capacity()) {
                Log.w(TAG, "video: a ${length / 1024} KB frame is over the ${buf.capacity() / 1024} KB input buffer")
                c.queueInputBuffer(i, 0, 0, ptsUs, 0); false
            }
            else { buf.put(data, 0, length); c.queueInputBuffer(i, 0, length, ptsUs, flags); true }
        }
    } catch (t: Throwable) { decoderErrors++; Log.w(TAG, "queue: ${t.message}"); false }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                var idx = c.dequeueOutputBuffer(info, 50_000)
                if (idx < 0) continue
                framesDecoded++
                // newer frames already waiting: drop this one, show the newest
                while (true) {
                    val next = c.dequeueOutputBuffer(info, 0)
                    if (next < 0) break
                    c.releaseOutputBuffer(idx, false)
                    framesDecoded++; framesDropped++
                    idx = next
                }
                c.releaseOutputBuffer(idx, true)
                framesOut++
                if (!firstShown) { firstShown = true; onFirstFrame() }
            } catch (t: IllegalStateException) {
                if (running) { decoderErrors++; Log.w(TAG, "decoder: ${t.message}") }
                break
            }
        }
    }

    companion object {
        const val TAG = "DSFSTREAM"
        private const val INPUT_WAIT_US = 15_000L
        private const val MIN_INPUT = 2 * 1024 * 1024
        private const val MAX_INPUT = 8 * 1024 * 1024

        /**
         * KEY_MAX_INPUT_SIZE for a stream of [width] x [height]: one raw 8-bit 4:2:0 picture (w x h x 1.5, 3 MB at 1080p; a compressed
         * frame is never bigger), at least 2 MB, at most 8 MB. Pure. Before it the decoder's own default applied, and a frame over it was
         * dropped and a keyframe asked for - at 100+ Mbps a keyframe can be that big, and the next one too.
         */
        fun maxInputSize(width: Int, height: Int): Int =
            (width.toLong().coerceAtLeast(1) * height.toLong().coerceAtLeast(1) * 3 / 2).coerceIn(MIN_INPUT.toLong(), MAX_INPUT.toLong()).toInt()

        /** the decoder [pickDecoder] takes for [mime] qualifies for reference-frame invalidation ([RfiPolicy.decoderOk]) */
        fun rfiOk(mime: String): Boolean = try {
            val name = pickDecoder(mime)
            val info = name?.let { n -> MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == n } }
            val lowLatency = info != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && try {
                info.getCapabilitiesForType(mime).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            } catch (_: Throwable) { false }
            name != null && RfiPolicy.decoderOk(name, lowLatency)
        } catch (_: Throwable) { false }

        fun mimeFor(videoFormat: Int): String = when {
            videoFormat and MoonBridge.VIDEO_FORMAT_MASK_AV1 != 0 -> MediaFormat.MIMETYPE_VIDEO_AV1
            videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H265 != 0 -> MediaFormat.MIMETYPE_VIDEO_HEVC
            else -> MediaFormat.MIMETYPE_VIDEO_AVC
        }

        /** a hardware decoder for [mime] (software ones as a last resort) */
        fun pickDecoder(mime: String): String? {
            val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
            fun hw(i: MediaCodecInfo) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) i.isHardwareAccelerated
                else !i.name.startsWith("OMX.google.") && !i.name.startsWith("c2.android.")
            return (all.firstOrNull { hw(it) } ?: all.firstOrNull())?.name
        }

        fun hasDecoder(mime: String): Boolean = try { pickDecoder(mime) != null } catch (_: Throwable) { false }
    }
}

/** THE PC'S SOUND on the Thor: Opus is decoded in the native core; the PCM goes to a low-latency AudioTrack here */
class AudioOut(private val enabled: Boolean) : MoonBridge.Audio {
    private var track: AudioTrack? = null

    override fun init(audioConfiguration: Int, sampleRate: Int, samplesPerFrame: Int): Int {
        if (!enabled) return 0
        val channels = (audioConfiguration ushr 8) and 0xFF
        val mask = if (channels >= 6) AudioFormat.CHANNEL_OUT_5POINT1 else AudioFormat.CHANNEL_OUT_STEREO
        return try {
            val min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate)
                    .setChannelMask(mask).build())
                .setBufferSizeInBytes(maxOf(min, samplesPerFrame * channels * 2 * 4))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            0
        } catch (t: Throwable) { Log.w("DSFSTREAM", "audio: ${t.message}"); -1 }
    }

    override fun start() { try { track?.play() } catch (_: Throwable) {} }
    override fun stop() { try { track?.pause(); track?.flush() } catch (_: Throwable) {} }
    override fun cleanup() { try { track?.release() } catch (_: Throwable) {}; track = null }
    override fun play(pcm: ShortArray, samples: Int) { try { track?.write(pcm, 0, samples, AudioTrack.WRITE_NON_BLOCKING) } catch (_: Throwable) {} }
}
