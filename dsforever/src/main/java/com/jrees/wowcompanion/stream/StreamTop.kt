package com.jrees.wowcompanion.stream

import android.app.Activity
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.jrees.wowcompanion.PadFeed
import com.jrees.wowcompanion.PlayMode
import com.jrees.wowcompanion.PlayModes
import com.jrees.wowcompanion.PopupHold
import com.jrees.wowcompanion.SetupBridge
import com.jrees.wowcompanion.fork.DsForeverScreen
import java.util.concurrent.Executors

/**
 * STREAMING ON THE THOR (S2; STREAMING_PLAN.md section 1), the top screen's whole job, hosted by S1's chooser route (DsStreamEntry,
 * inside the launcher's activity) or by the stand-alone [StreamActivity]: the top screen shows WoW running on the PC (Moonlight's
 * core decoding to [view]'s Surface), the bottom screen keeps the companion (DsForeverScreen's Presentation, fed by the bridge's copy of the
 * PC's log: PlayModes / BridgeLog), the controller goes to the PC as an Xbox pad.
 *
 * Screens before the stream runs (the controller and touch drive them; nothing reaches the PC then):
 *  - PAIRING: "Enter the code shown in DS Forever Desktop" (first time, or "Pair again"); a PC with a plain Sunshine shows a PIN for
 *    Sunshine's own page instead (the first test needs no Desktop);
 *  - CONNECTING, then PROBLEM: why it failed (Diagnosis) with Retry / Pair again / Stream quality / Back, retrying by itself
 *    (ReconnectPolicy) and at once when the network changes;
 *  - QUALITY: "Stream quality".
 * While STREAMING every pad press goes to the PC, except while one of our pop-ups holds the pad (PopupHold, as the launcher does);
 * DS Forever's own readers get a copy of every press (PadFeed), exactly as in Local.
 */
class StreamTop(private val activity: Activity, private val shell: Shell) : SurfaceHolder.Callback {
    /** where Streaming's top screen lives: S1's chooser route (DsStreamEntry) or the stand-alone [StreamActivity] */
    interface Shell {
        /** B on Streaming's own screens: back to the chooser */
        fun back()
        /** the bottom screen: [covers] = Streaming's placeholder page still covers the companion (no PC paired yet); [note] = status */
        fun bottom(covers: Boolean, note: String) {}
    }

    enum class Screen { PAIRING, PIN, CONNECTING, STREAMING, PROBLEM, QUALITY }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dsf-stream-work") }
    private val store by lazy { HostStore(activity) }
    private var host: PairedHost? = null
    private var quality = StreamQuality()

    private lateinit var surface: SurfaceView
    private lateinit var overlay: FrameLayout
    private lateinit var pairing: PairingView
    private lateinit var status: StatusView
    private var qualityView: QualityView? = null
    private var screen = Screen.CONNECTING
    private var beforeQuality = Screen.CONNECTING

    private var discovery: Discovery? = null
    private var found: List<FoundPc> = emptyList()
    private var pcIndex = 0

    private var session: StreamSession? = null
    private var keys: StreamKeys? = null
    private val pad = StreamPad()
    private val policy = ReconnectPolicy()
    private var surfaceReady = false
    @Volatile private var bridge: BridgeLog? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** the top screen's view (the stream's Surface under Streaming's own screens) */
    val view: FrameLayout = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
    private var isOpen = false

    init {
        quality = StreamQuality.load(activity)
        surface = SurfaceView(activity).also { it.holder.addCallback(this) }
        view.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        overlay = FrameLayout(activity)
        view.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        pairing = PairingView(activity, onPair = { pair(it) }, onBack = { back() }, onQuality = { openQuality() }, onPcStep = { stepPc(it) })
        status = StatusView(activity, onRetry = { retryNow() }, onPairAgain = { show(Screen.PAIRING) }, onQuality = { openQuality() }, onBack = { back() })
        overlay.centered(pairing, 640f)
        overlay.centered(status, 760f)
    }

    /** Streaming's top screen came up: the mode, discovery, then the pairing screen or straight to the PC */
    fun opened() {
        if (isOpen) return
        isOpen = true
        host = store.host()
        // the companion on the bottom screen, reading the PC's log through the bridge (Local is untouched: PlayModes defaults to it)
        applyMode()
        discovery = Discovery(activity) { pcs -> found = pcs; onFound() }.also { it.start() }
        watchNetwork()
        if (!MoonBridge.loaded) { problem("Streaming isn't in this build", "This DS Forever was built without Moonlight's core.", auto = false); return }
        if (host == null) show(Screen.PAIRING) else { show(Screen.CONNECTING); status.show("Connecting to ${host!!.name}…", "", "", withButtons = false); connect() }
    }

    /** it went (back to the chooser, or the activity is gone): the stream, discovery and the bridge stop; the mode is Local again */
    fun closed() {
        if (!isOpen) return
        isOpen = false
        stopStream("leaving")
        discovery?.stop(); discovery = null
        netCallback?.let { try { activity.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } catch (_: Throwable) {} }
        netCallback = null
        main.removeCallbacksAndMessages(null)
        PlayModes.set(PlayMode.LOCAL, null, "")
    }

    // ---------------------------------------------------------------- the mode and the companion's log
    private fun applyMode() {
        val h = host
        if (h == null || !h.hasBridge) {
            shell.bottom(covers = h == null, note = if (h == null) "Pair with your PC on the top screen" else "${h.name}: Sunshine only, no companion data")
            PlayModes.set(PlayMode.STREAM, null, h?.address.orEmpty()); return
        }
        shell.bottom(covers = false, note = "")
        val clientId = store.clientId()
        PlayModes.set(PlayMode.STREAM, { onMsg, onStatus ->
            BridgeLog(h.address, h.bridgePort, h.bridgeTls, h.bridgeFingerprint, h.bridgeToken, clientId, WsClient, onMsg, onStatus,
                onAuthLost = { why -> main.post { authLost(why) } }).also { bridge = it }
        }, "${h.desktopId}@${h.address}:${h.bridgePort}")
    }

    private fun authLost(why: String) {
        Log.w(TAG, "bridge: $why")
        if (screen == Screen.STREAMING) return            // the stream itself still works; the next start asks to pair
        problem(Diagnosis.Reason.NOT_PAIRED.title, Diagnosis.Reason.NOT_PAIRED.detail, auto = false)
    }

    // ---------------------------------------------------------------- the surface and the stream
    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        if (isOpen && host != null && (screen == Screen.CONNECTING || screen == Screen.PROBLEM)) connect()
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; stopStream("surface gone") }

    private fun connect() {
        val h = host ?: return show(Screen.PAIRING)
        if (session != null || !surfaceReady) return
        main.removeCallbacks(retryTick)
        show(Screen.CONNECTING)
        status.show("Connecting to ${h.name}…", quality.label, "", withButtons = false)
        val q = quality
        worker.execute {
            val id = store.identity()
            main.post {
                if (session != null || !surfaceReady || !isOpen) return@post
                val video = VideoDecoder(surface.holder.surface)
                session = StreamSession(h, id, q, video, AudioOut(q.audioOnThor), events).also { it.start() }
            }
        }
    }

    private val events = object : StreamSession.Events {
        override fun stage(text: String) = main.post { if (screen == Screen.CONNECTING) status.show("Connecting to ${host?.name ?: "the PC"}…", text, "", false) }.let {}
        override fun started(width: Int, height: Int) = main.post { onStarted(width, height) }.let {}
        override fun ended(error: Int, notPaired: Boolean, message: String) = main.post { onEnded(error, notPaired, message) }.let {}
        override fun poorConnection(poor: Boolean) {}
    }

    private fun onStarted(w: Int, h: Int) {
        policy.reset()
        show(Screen.STREAMING)
        val k = StreamKeys(MoonBridge.INPUT, w, h).also { keys = it }
        DsForeverScreen.current?.attachStream(k)
        try { MoonBridge.sendControllerArrivalEvent(0, 1, MoonBridge.LI_CTYPE_XBOX, StreamPad.SUPPORTED,
            (MoonBridge.LI_CCAP_ANALOG_TRIGGERS.toInt() or MoonBridge.LI_CCAP_RUMBLE.toInt()).toShort()) } catch (t: Throwable) { Log.w(TAG, "arrival: ${t.message}") }
        worker.execute { bridge?.focusWow() }
        host?.let { store.save(it.copy(lastSeenMs = System.currentTimeMillis())) }
        Log.i(TAG, "streaming ${w}x$h")
    }

    private fun onEnded(error: Int, notPaired: Boolean, message: String) {
        val wasStreaming = screen == Screen.STREAMING
        teardown()
        Log.i(TAG, "stream ended ($error, paired ${!notPaired}): $message")
        if (!isOpen) return
        if (notPaired) { problem(Diagnosis.Reason.NOT_PAIRED.title, Diagnosis.Reason.NOT_PAIRED.detail, message, auto = false); return }
        if (wasStreaming) policy.reset()
        diagnose(message)
    }

    /** the stream stopped or never started: measure, say why, and retry by the policy */
    private fun diagnose(message: String) {
        val h = host ?: return show(Screen.PAIRING)
        show(Screen.PROBLEM)
        status.show("Checking the connection…", message, "", withButtons = false)
        val seen = found
        worker.execute {
            val reason = Diagnosis.reason(NetProbe.probe(activity, h, seen))
            main.post {
                if (screen != Screen.PROBLEM) return@post
                if (reason == Diagnosis.Reason.OK || reason == Diagnosis.Reason.BRIDGE_ONLY_DOWN) problem("The stream stopped", message, reason.detail, auto = true)
                else problem(reason.title, reason.detail, message, auto = reason.autoRetry)
            }
        }
    }

    private var retryAt = 0L
    private fun problem(title: String, detail: String, note: String = "", auto: Boolean) {
        show(Screen.PROBLEM)
        main.removeCallbacks(retryTick)
        val wait = if (auto) policy.nextDelayMs(Diagnosis.Reason.PC_OFF) else -1
        retryAt = if (wait >= 0) System.currentTimeMillis() + wait else 0L
        problemText = Triple(title, detail, note)
        if (wait >= 0) main.post(retryTick) else status.show(title, detail, note)
    }
    private var problemText = Triple("", "", "")

    private val retryTick = object : Runnable {
        override fun run() {
            if (screen != Screen.PROBLEM || retryAt == 0L) return
            val left = retryAt - System.currentTimeMillis()
            if (left <= 0) { connect(); return }
            val (t, d, n) = problemText
            status.show(t, d, listOf(n, "Trying again in ${(left + 999) / 1000} s").filter { it.isNotEmpty() }.joinToString("\n"))
            main.postDelayed(this, 250)
        }
    }

    private fun retryNow() { policy.reset(); retryAt = 0; if (host == null) show(Screen.PAIRING) else connect() }

    private fun stopStream(why: String) {
        session?.let { Log.i(TAG, "stopping the stream: $why"); it.stop() }
        teardown()
    }

    private fun teardown() {
        session = null
        keys?.releaseAll()
        if (keys != null) { pad.clear(); try { sendPad() } catch (_: Throwable) {} }
        keys = null
        DsForeverScreen.current?.detachGame()
    }

    // ---------------------------------------------------------------- pairing
    private fun selectedPc(): FoundPc? = found.getOrNull(pcIndex.coerceIn(0, (found.size - 1).coerceAtLeast(0)))

    private fun onFound() {
        if (pcIndex >= found.size) pcIndex = 0
        // the paired PC under a new address (DHCP): follow it, by Desktop's id or Sunshine's
        val h = host
        if (h != null && screen != Screen.STREAMING) {
            val same = found.firstOrNull { (h.desktopId.isNotEmpty() && it.desktopId == h.desktopId) || (h.uuid.isNotEmpty() && it.sunshineId == h.uuid) }
            if (same != null && same.address != h.address) {
                Log.i(TAG, "${h.name} moved: ${h.address} -> ${same.address}")
                host = h.copy(address = same.address).also { store.save(it) }
                applyMode()
                if (screen == Screen.PROBLEM) retryNow()
            }
        }
        if (screen == Screen.PAIRING) showPc()
    }

    private fun showPc() {
        val pc = selectedPc()
        pairing.setPc(when {
            pc == null -> "Looking for your PC on this Wi-Fi… (DS Forever Desktop must be running)"
            !pc.hasBridge -> "PC: ${pc.name} (${pc.address}) - Sunshine only, no DS Forever Desktop${if (found.size > 1) "   ·   LB / RB: ${found.size} PCs" else ""}"
            else -> "PC: ${pc.name} (${pc.address})${if (found.size > 1) "   ·   LB / RB: ${found.size} PCs" else ""}"
        })
    }

    private fun stepPc(by: Int) { if (found.isNotEmpty()) { pcIndex = ((pcIndex + by) % found.size + found.size) % found.size; showPc() } }

    private fun pair(code: String) {
        val pc = selectedPc() ?: run { pairing.setMessage("No PC found yet: is DS Forever Desktop running, on the same Wi-Fi?"); return }
        if (!pc.hasBridge) { pairPlain(pc); return }
        pairing.busy = true
        pairing.setMessage("")
        worker.execute {
            val id = store.identity()
            val flow = StreamPairing(RealPairSteps(id, store.clientId())) { s -> main.post { pairState(s) } }
            flow.pairWithCode(pc, code, NetProbe.subnet(activity))
        }
    }

    /** a stock Sunshine (no Desktop): the player types OUR PIN into Sunshine's page */
    private fun pairPlain(pc: FoundPc) {
        val pin = StreamPairing.randomPin()
        show(Screen.PIN)
        worker.execute {
            val id = store.identity()
            StreamPairing(RealPairSteps(id, store.clientId())) { s -> main.post { pairState(s) } }.pairPlainSunshine(pc, pin, NetProbe.subnet(activity))
        }
    }

    private fun pairState(s: StreamPairing.State) {
        when (s) {
            is StreamPairing.State.Working -> pairing.setMessage(s.what)
            is StreamPairing.State.ShowPin -> status.show("Pair with Sunshine", "On the PC, open Sunshine's PIN page and enter:\n\n${s.pin}",
                "No DS Forever Desktop on this PC: the stream works, the companion's live data needs Desktop.", withButtons = false)
            is StreamPairing.State.BadCode -> { pairing.busy = false; pairing.setMessage(s.message) }
            is StreamPairing.State.WrongCode -> { pairing.busy = false; pairing.reset()
                pairing.setMessage("That's not the code on the PC.${if (s.triesLeft >= 0) " ${s.triesLeft} tries left." else ""}") }
            is StreamPairing.State.Failed -> { pairing.busy = false
                if (screen == Screen.PIN) { show(Screen.PAIRING) }
                pairing.setMessage(s.message) }
            is StreamPairing.State.Paired -> {
                pairing.busy = false; pairing.reset()
                host = s.host; store.save(s.host)
                Log.i(TAG, "paired with ${s.host.name} (${s.host.address}, bridge ${s.host.hasBridge})")
                applyMode()
                policy.reset()
                connect()
            }
        }
    }

    // ---------------------------------------------------------------- screens
    private fun show(s: Screen) {
        screen = s
        overlay.visibility = if (s == Screen.STREAMING) View.GONE else View.VISIBLE
        pairing.visibility = if (s == Screen.PAIRING) View.VISIBLE else View.GONE
        status.visibility = if (s == Screen.CONNECTING || s == Screen.PROBLEM || s == Screen.PIN) View.VISIBLE else View.GONE
        qualityView?.visibility = if (s == Screen.QUALITY) View.VISIBLE else View.GONE
        if (s == Screen.PAIRING) { main.removeCallbacks(retryTick); showPc() }
    }

    private fun openQuality() {
        if (screen == Screen.QUALITY) return
        beforeQuality = screen
        val v = qualityView ?: QualityView(activity, quality, onChange = { q -> quality = q; StreamQuality.save(activity, q) }, onClose = { show(beforeQuality) })
            .also { qualityView = it; overlay.centered(it, 560f) }
        show(Screen.QUALITY)
        v.bringToFront()
    }

    private fun back() = shell.back()

    // ---------------------------------------------------------------- the controller
    private fun isPad(e: KeyEvent) = (e.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK || KeyEvent.isGamepadButton(e.keyCode)

    /** a key from the host's activity; true = Streaming took it (its own screens, or sent to the PC) */
    fun key(e: KeyEvent): Boolean {
        val gamepad = isPad(e)
        if (gamepad) PadFeed.key(e)                          // DS Forever's own readers: a copy of every press, as in Local
        val down = e.action == KeyEvent.ACTION_DOWN
        if (screen != Screen.STREAMING) {
            if (down && e.repeatCount == 0 && overlayKey(e.keyCode)) return true
            return gamepad || e.keyCode == KeyEvent.KEYCODE_BACK      // nothing reaches the PC from our own screens
        }
        if (gamepad && StreamPad.isPadKey(e.keyCode)) {
            // one of our pop-ups asks (the sign-in's, the report notice): A, B and the D-pad do nothing for the game
            if (PopupHold.holdsKey(SetupBridge.holdsGamePad(), e.keyCode, down)) return true
            if (e.repeatCount > 0) return true
            if (pad.key(e.keyCode, down)) sendPad()
            return true
        }
        return false
    }

    private fun overlayKey(code: Int): Boolean = when (screen) {
        Screen.PAIRING -> pairing.pad(code)
        Screen.QUALITY -> qualityView?.pad(code) == true
        Screen.PROBLEM, Screen.CONNECTING, Screen.PIN -> when (code) {
            KeyEvent.KEYCODE_BUTTON_A -> { if (screen == Screen.PROBLEM) retryNow(); true }
            KeyEvent.KEYCODE_BUTTON_X -> { stopStream("pair again"); show(Screen.PAIRING); true }
            KeyEvent.KEYCODE_BUTTON_Y -> { openQuality(); true }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { back(); true }
            else -> false
        }
        Screen.STREAMING -> false
    }

    /** a motion event from the host's activity (sticks, hat, triggers); true = taken */
    fun motion(e: MotionEvent): Boolean {
        if ((e.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK || e.action != MotionEvent.ACTION_MOVE) return false
        PadFeed.motion(e)
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X); val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (screen != Screen.STREAMING) {
            // the hat as the D-pad on our own screens (the Thor's D-pad may come as a hat)
            hatToKeys(hx, hy)
            return true
        }
        if (PopupHold.holdsHat(SetupBridge.holdsGamePad(), hx, hy)) return true
        val zx = e.getAxisValue(MotionEvent.AXIS_Z); val zy = e.getAxisValue(MotionEvent.AXIS_RZ)
        val qx = e.getAxisValue(MotionEvent.AXIS_RX); val qy = e.getAxisValue(MotionEvent.AXIS_RY)
        val useZ = zx * zx + zy * zy >= qx * qx + qy * qy
        val lt = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
        if (pad.axes(e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y), if (useZ) zx else qx, if (useZ) zy else qy, lt, rt, hx, hy)) sendPad()
        return true
    }

    private var lastHat = 0
    private fun hatToKeys(hx: Float, hy: Float) {
        val h = when { hx < -0.5f -> KeyEvent.KEYCODE_DPAD_LEFT; hx > 0.5f -> KeyEvent.KEYCODE_DPAD_RIGHT; hy < -0.5f -> KeyEvent.KEYCODE_DPAD_UP
            hy > 0.5f -> KeyEvent.KEYCODE_DPAD_DOWN; else -> 0 }
        if (h != 0 && h != lastHat) overlayKey(h)
        lastHat = h
    }

    private fun sendPad() {
        if (!MoonBridge.loaded) return
        val s = pad.snapshot()
        MoonBridge.sendMultiControllerInput(0, 1, s.buttons, s.lt.toByte(), s.rt.toByte(), s.lx.toShort(), s.ly.toShort(), s.rx.toShort(), s.ry.toShort())
    }

    // ---------------------------------------------------------------- the network
    private fun watchNetwork() {
        val cm = activity.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.postDelayed({ if (screen == Screen.PROBLEM) retryNow() }, 1_500) }
        }
        try { cm.registerDefaultNetworkCallback(cb); netCallback = cb } catch (t: Throwable) { Log.w(TAG, "network watch: ${t.message}") }
    }

    companion object {
        const val TAG = "DSFSTREAM"
    }
}
