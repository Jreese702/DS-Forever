package com.jrees.wowcompanion.stream

import android.util.Log
import java.nio.ByteBuffer
import java.security.SecureRandom

/** the pure parts of starting a stream (the tests drive them) */
object LaunchPlan {
    /** the launch URL's surroundAudioInfo for stereo: channelMask << 16 | channelCount */
    const val SURROUND_INFO_STEREO = (0x3 shl 16) or 2
    /** the app to stream: DS Forever Desktop's own entry first ("WoW Forever" in Desktop v1), then Sunshine's "Desktop", else the first */
    fun pickApp(apps: List<Pair<Int, String>>): Pair<Int, String>? =
        apps.firstOrNull { it.second.equals("WoW Forever", ignoreCase = true) }
            ?: apps.firstOrNull { it.second.equals("DS Forever", ignoreCase = true) }
            ?: apps.firstOrNull { it.second.equals("World of Warcraft", ignoreCase = true) }
            ?: apps.firstOrNull { it.second.equals("Desktop", ignoreCase = true) }
            ?: apps.firstOrNull()

    /** resume when that app already runs on the PC (a stream that dropped), else launch */
    fun verb(currentGame: Int, appId: Int): String = if (currentGame != 0 && currentGame == appId) "resume" else "launch"

    /** launch / resume's query, as moonlight-android's NvHTTP.launchApp builds it (no HDR, no SOPS on a Sunshine PC) */
    fun query(appId: Int, q: StreamQuality, riKey: ByteArray, riKeyId: Int, coreParams: String): String =
        "appid=$appId&mode=${q.width}x${q.height}x${q.fps}&additionalStates=1&sops=0" +
            "&rikey=${Hex.of(riKey)}&rikeyid=$riKeyId" +
            "&localAudioPlayMode=${if (q.audioOnThor) 0 else 1}" +
            "&surroundAudioInfo=$SURROUND_INFO_STEREO" +
            "&remoteControllersBitmap=1&gcmap=1&gcpersist=0" +
            coreParams

    /** the remote-input IV: the key id, big-endian, in the first 4 of 16 bytes */
    fun riIv(riKeyId: Int): ByteArray = ByteBuffer.allocate(16).putInt(riKeyId).array()
}

/**
 * ONE STREAM to the paired PC, on its own thread: serverinfo over the pinned https (are we still paired?), the app list, launch or
 * resume with the stream quality, then Moonlight's core (RTSP, video, audio, input) via [MoonBridge.startConnection]. Follows
 * moonlight-android's NvConnection.start. Its life is reported to [events]; [stop] ends it (also from a lost connection).
 */
class StreamSession(
    private val host: PairedHost,
    private val identity: ClientIdentity,
    private val quality: StreamQuality,
    private val video: MoonBridge.Video,
    private val audio: MoonBridge.Audio,
    private val events: Events,
    private val decoders: (String) -> Boolean = { VideoDecoder.hasDecoder(it) },
) {
    interface Events {
        fun stage(text: String)
        /** video and input are up */
        fun started(width: Int, height: Int)
        /** it ended: [notPaired] = the PC no longer knows us; [error] = Moonlight's code (0 = it ended normally) */
        fun ended(error: Int, notPaired: Boolean, message: String)
        fun poorConnection(poor: Boolean) {}
        fun rumble(low: Int, high: Int) {}
    }

    @Volatile private var started = false
    @Volatile private var stopped = false
    private var thread: Thread? = null

    fun start() {
        thread = Thread({ run() }, "dsf-stream-start").apply { start() }
    }

    private fun run() {
        if (!MoonBridge.loaded) { events.ended(-1, false, "This build has no streaming core."); return }
        val cert = try { ClientIdentity.parseCert(host.serverCertPem.toByteArray()) } catch (t: Throwable) {
            events.ended(-1, true, "The paired PC's certificate is unreadable: pair again."); return
        }
        val http = NvHttp(host.address, host.httpPort, host.httpsPort, identity, cert)
        try {
            events.stage("Asking ${host.name}…")
            // http first: proves the PC's http port answers, and its HttpsPort (the stored one may be stale) is the one used next
            val plain = http.serverInfo(false)
            XmlFields.get(plain, "HttpsPort")?.toIntOrNull()?.takeIf { it > 0 }?.let { if (it != http.httpsPort) { Log.i(TAG, "https port ${http.httpsPort} -> $it (serverinfo)"); http.httpsPort = it } }
            val info = try { http.serverInfo(true) } catch (e: HostHttpException) {
                // Sunshine answers an unknown client certificate with 401 over https (its http PairStatus is always 0: no proof there)
                if (e.code == 401) { events.ended(-1, true, "${host.name} doesn't know this Thor any more."); return }
                throw e
            } catch (e: NvHttpStageException) {
                if (e.stage != "tls") throw e
                events.ended(ERROR_TLS, StreamDiag.isPinMismatch(e), StreamDiag.tlsText(host.name, e)); return
            }
            if (XmlFields.get(info, "PairStatus") != "1") { events.ended(-1, true, "${host.name} doesn't know this Thor any more."); return }
            val appVersion = XmlFields.get(info, "appversion") ?: run { events.ended(-1, false, "${host.name} sent no version."); return }
            val gfe = XmlFields.get(info, "GfeVersion")
            val codecSupport = XmlFields.get(info, "ServerCodecModeSupport")?.toLongOrNull()?.toInt() ?: 0
            val current = XmlFields.get(info, "currentgame")?.toIntOrNull() ?: 0

            events.stage("Starting WoW's stream…")
            val app = LaunchPlan.pickApp(XmlFields.apps(http.appList())) ?: run { events.ended(-1, false, "${host.name} lists nothing to stream."); return }
            val riKey = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val riKeyId = SecureRandom().nextInt()
            val verb = LaunchPlan.verb(current, app.first)
            val launched = http.launch(verb, LaunchPlan.query(app.first, quality, riKey, riKeyId, MoonBridge.getLaunchUrlQueryParameters()))
            val ok = if (verb == "launch") XmlFields.get(launched, "gamesession") != "0" else XmlFields.get(launched, "resume") != "0"
            if (!ok) { events.ended(-1, false, "${host.name} wouldn't start the stream ($verb)."); return }
            val rtsp = XmlFields.get(launched, "sessionUrl0")
            if (stopped) return

            val formats = quality.videoFormats(decoders("video/hevc"), decoders("video/av01"))
            MoonBridge.setup(video, audio, listener)
            Log.i(TAG, "stream: ${host.address} app ${app.second} ($verb) ${quality.label} formats 0x${Integer.toHexString(formats)}")
            val ret = synchronized(MoonBridge::class.java) {
                MoonBridge.startConnection(host.address, appVersion, gfe, rtsp, codecSupport, quality.width, quality.height, quality.fps,
                    quality.bitrateKbps, PACKET_SIZE, 0 /* local network */, MoonBridge.AUDIO_CONFIGURATION_STEREO, formats,
                    quality.fps * 100, riKey, LaunchPlan.riIv(riKeyId), MoonBridge.CAPABILITY_DIRECT_SUBMIT,
                    MoonBridge.COLORSPACE_REC_709, MoonBridge.COLOR_RANGE_LIMITED)
            }
            if (ret != 0) { if (!stopped) events.ended(ret, false, "The stream didn't start (error $ret)."); return }
            started = true
            if (stopped) stopCore() else events.started(quality.width, quality.height)
        } catch (e: HostHttpException) {
            events.ended(-1, e.code == 401, "${host.name}: ${e.message}")
        } catch (e: NvHttpStageException) {
            events.ended(-2, false, StreamDiag.stageText(host.name, e))
        } catch (t: Throwable) {
            events.ended(-2, false, "${host.name} didn't answer (${t.message ?: t.javaClass.simpleName}).")
        }
    }

    private val listener = object : MoonBridge.Listener {
        override fun stageStarting(stage: Int) { events.stage("Connecting: ${safeStage(stage)}…") }
        override fun stageComplete(stage: Int) {}
        override fun stageFailed(stage: Int, errorCode: Int) { Log.w(TAG, "stage ${safeStage(stage)} failed $errorCode") }
        override fun connectionStarted() {}
        override fun connectionTerminated(errorCode: Int) {
            // Moonlight's rule: stop the connection from another thread than its callback
            Thread({ stopCore(); events.ended(errorCode, false, terminationText(errorCode)) }, "dsf-stream-end").start()
        }
        override fun rumble(controller: Int, lowFreq: Int, highFreq: Int) = events.rumble(lowFreq, highFreq)
        override fun connectionStatus(status: Int) = events.poorConnection(status == MoonBridge.CONN_STATUS_POOR)
    }

    private fun safeStage(s: Int) = try { MoonBridge.getStageName(s) } catch (_: Throwable) { "stage $s" }

    /** ends the stream (any thread); the PC's game keeps running (resume picks it up) */
    fun stop() {
        stopped = true
        if (started) Thread({ stopCore() }, "dsf-stream-stop").start()
        else try { if (MoonBridge.loaded) MoonBridge.interruptConnection() } catch (_: Throwable) {}
    }

    @Synchronized private fun stopCore() {
        if (!started) return
        started = false
        try { synchronized(MoonBridge::class.java) { MoonBridge.stopConnection() } } catch (_: Throwable) {}
        MoonBridge.clear()
    }

    companion object {
        const val TAG = "DSFSTREAM"
        /** the https handshake failed (see [StreamDiag.tlsText]) */
        const val ERROR_TLS = -3
        /** Moonlight's LAN packet size (moonlight-android's Game.java) */
        const val PACKET_SIZE = 1392

        fun terminationText(code: Int): String = when (code) {
            MoonBridge.ML_ERROR_GRACEFUL_TERMINATION -> "The PC ended the stream."
            MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC -> "No video arrived: the PC's firewall may block the stream (UDP 47998-48000)."
            MoonBridge.ML_ERROR_NO_VIDEO_FRAME -> "The stream stopped sending pictures."
            else -> "The connection to the PC was lost (error $code)."
        }
    }
}

/** what the player reads for a request that failed at one stage (pure: the tests drive it) */
object StreamDiag {
    /** the TLS failed because the PC showed a different certificate than the paired one */
    fun isPinMismatch(e: Throwable): Boolean {
        var c: Throwable? = e
        while (c != null) { if (c is java.security.cert.CertificateException && c.message?.contains("paired PC") == true) return true; c = c.cause }
        return false
    }

    fun tlsText(pc: String, e: NvHttpStageException): String = when {
        isPinMismatch(e) -> "$pc answered with a different certificate than the paired one: pair again."
        e.timedOut -> "$pc took the connection on its secure streaming port (${e.port}) but never answered the TLS handshake. " +
            "Its streaming service looks stuck: restart DS Forever Desktop on the PC."
        else -> "$pc refused the secure connection on port ${e.port} (${e.cause?.javaClass?.simpleName}: ${e.cause?.message})."
    }

    fun stageText(pc: String, e: NvHttpStageException): String = when (e.stage) {
        "connect" -> "$pc didn't answer on port ${e.port} (${e.cause?.message ?: "no connection"})."
        "tls" -> tlsText(pc, e)
        else -> "$pc stopped answering on port ${e.port} (${if (e.timedOut) "no answer in ${e.elapsedMs / 1000} s" else e.cause?.message})."
    }
}
