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

    override fun setup(videoFormat: Int, width: Int, height: Int, fps: Int): Int {
        val mime = mimeFor(videoFormat)
        return try {
            val fmt = MediaFormat.createVideoFormat(mime, width, height).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)                       // realtime
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
            }
            val name = pickDecoder(mime) ?: run { Log.w(TAG, "no decoder for $mime"); return -1 }
            val c = MediaCodec.createByCodecName(name)
            try { c.configure(fmt, surface, null, 0) } catch (t: Throwable) {
                // a decoder that refuses a vendor key: configure without them
                Log.w(TAG, "configure with low-latency keys failed (${t.message}); plain")
                c.reset()
                c.configure(MediaFormat.createVideoFormat(mime, width, height), surface, null, 0)
            }
            codec = c
            Log.i(TAG, "video: $name $mime ${width}x$height@$fps")
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
        csd.reset(); csdPending = false; firstShown = false
    }

    override fun submit(data: ByteArray, length: Int, bufferType: Int, frameNumber: Int, frameType: Int, receiveTimeUs: Long): Int {
        val c = codec ?: return MoonBridge.DR_NEED_IDR
        if (bufferType != MoonBridge.BUFFER_TYPE_PICDATA) {
            if (!csdPending) { csd.reset(); csdPending = true }
            csd.write(data, 0, length)
            return MoonBridge.DR_OK
        }
        val idr = frameType == MoonBridge.FRAME_TYPE_IDR
        if (idr && csdPending) {
            val conf = csd.toByteArray(); csdPending = false
            if (!queue(c, conf, conf.size, receiveTimeUs, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) return MoonBridge.DR_NEED_IDR
        }
        if (!queue(c, data, length, receiveTimeUs, if (idr) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)) return MoonBridge.DR_NEED_IDR
        framesIn++
        return MoonBridge.DR_OK
    }

    private fun queue(c: MediaCodec, data: ByteArray, length: Int, ptsUs: Long, flags: Int): Boolean = try {
        val i = c.dequeueInputBuffer(INPUT_WAIT_US)
        if (i < 0) false else {
            val buf = c.getInputBuffer(i) ?: return false
            buf.clear()
            if (length > buf.capacity()) { c.queueInputBuffer(i, 0, 0, ptsUs, 0); false }
            else { buf.put(data, 0, length); c.queueInputBuffer(i, 0, length, ptsUs, flags); true }
        }
    } catch (t: Throwable) { Log.w(TAG, "queue: ${t.message}"); false }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                var idx = c.dequeueOutputBuffer(info, 50_000)
                if (idx < 0) continue
                // newer frames already waiting: drop this one, show the newest
                while (true) {
                    val next = c.dequeueOutputBuffer(info, 0)
                    if (next < 0) break
                    c.releaseOutputBuffer(idx, false)
                    idx = next
                }
                c.releaseOutputBuffer(idx, true)
                framesOut++
                if (!firstShown) { firstShown = true; onFirstFrame() }
            } catch (t: IllegalStateException) {
                if (running) Log.w(TAG, "decoder: ${t.message}")
                break
            }
        }
    }

    companion object {
        const val TAG = "DSFSTREAM"
        private const val INPUT_WAIT_US = 15_000L

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
