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
    /** black round the zoomed window; a touch on the black goes nowhere */
    private lateinit var mask: ZoomMask
    private lateinit var overlay: FrameLayout
    private lateinit var pairing: PairingView
    private lateinit var status: StatusView
    private var qualityView: QualityView? = null
    private var qualityAtOpen = StreamQuality()
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
    private lateinit var banner: android.widget.TextView
    /** the loading reveal and its box over the picture until WoW is signed in and in front (StreamCurtain); it takes every touch */
    private lateinit var curtainView: com.jrees.wowcompanion.LoadingTopView
    /** Desktop's last word on WoW and Battle.net (null = none yet) */
    private var pc: PcWow? = null

    init {
        quality = StreamQuality.load(activity)
        surface = SurfaceView(activity).also { it.holder.addCallback(this) }
        view.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        mask = ZoomMask(activity)
        view.addView(mask, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        curtainView = com.jrees.wowcompanion.LoadingTopView(activity).apply {
            curtain = { (shows() as? StreamCurtain.Shows.Curtain)?.let { it.rows to it.line } }
            setOnTouchListener { _, _ -> true }
            contentDescription = "Starting WoW on your PC"
        }
        view.addView(curtainView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        overlay = FrameLayout(activity)
        view.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        pairing = PairingView(activity, onPair = { pair(it) }, onBack = { back() }, onQuality = { openQuality() }, onPcStep = { stepPc(it) })
        status = StatusView(activity, onRetry = { retryNow() }, onPairAgain = { show(Screen.PAIRING) }, onQuality = { openQuality() }, onBack = { back() })
        overlay.centered(pairing, 640f)
        overlay.centered(status, 760f)
        // "Starting WoW on your PC…" over the stream (S3's wow message)
        banner = StreamLook.text(activity, 18f, StreamLook.GOLD, bold = true).apply {
            background = StreamLook.rounded(StreamLook.PANEL, StreamLook.dp(activity, 12f).toFloat())
            val p = StreamLook.dp(activity, 12f); setPadding(p * 2, p, p * 2, p)
            visibility = View.GONE
        }
        view.addView(banner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL).apply { topMargin = StreamLook.dp(activity, 24f) })
        // touch = the PC's mouse on the picture (no on-screen controls over the stream: "there's a controller overlay? Remove that")
        surface.setOnTouchListener { _, e -> touch(e) }
    }

    private val hideBanner = Runnable { banner.visibility = View.GONE }

    /** what the top screen shows over the stream now (StreamCurtain's rule) */
    private fun shows(): StreamCurtain.Shows = StreamCurtain.decide(screen == Screen.STREAMING, host?.hasBridge == true, pc,
        ((android.os.SystemClock.uptimeMillis() / 500) % 4).toInt(), route?.label.orEmpty(), reachProblem, signIn.working, loginLong(), seen,
        pcWhy, portsWait != null)

    /** this bridge connection's word on Sunshine's ports (its welcome; null = none yet): [StreamPorts] picks from it, never 47989 by guess */
    private var ports: StreamPorts.Report? = null
    /** the PC's reason it can't stream (the welcome's sunshine.problem / why, [StreamCurtain.reason]; "" = none) */
    private var pcWhy = ""
    /** a connect that answered and waits for Desktop's Sunshine ports: since when, and its stream mode; null = not waiting */
    private var portsWait: Pair<Long, StreamMode.Choice>? = null

    /** WoW has shown in this stream: losing the front no longer hides it */
    private var seen = false

    /** the way the PC was reached this time (home Wi-Fi / Tailscale), null = not yet */
    private var route: Route.Kind? = null
    /** why no address of the PC answered (the curtain's line), null = none */
    private var reachProblem: String? = null
    /** the addresses are being tried (one connect at a time) */
    private var routing = false

    /** the curtain up or down, Battle.net's sign-in line, as the rule says (on every word from Desktop and every screen change) */
    private fun applyCurtain() {
        val starting = screen == Screen.STREAMING || screen == Screen.CONNECTING
        val s = shows()
        if (s === StreamCurtain.Shows.Stream && screen == Screen.STREAMING) seen = true
        val up = starting && s is StreamCurtain.Shows.Curtain
        curtainView.visibility = if (up) View.VISIBLE else View.GONE
        // the connecting card would sit on the curtain: the curtain says it ("Connecting to your PC")
        if (screen == Screen.CONNECTING) status.visibility = if (up) View.GONE else View.VISIBLE
        zoom(if (s is StreamCurtain.Shows.SignIn && screen == Screen.STREAMING) s.show else null)
        board(s is StreamCurtain.Shows.SignIn && screen == Screen.STREAMING)
        if (s is StreamCurtain.Shows.SignIn && screen == Screen.STREAMING) {
            main.removeCallbacks(hideBanner)
            banner.text = s.line; banner.visibility = View.VISIBLE; banner.bringToFront()
        } else if (up || banner.text in SIGN_IN_LINES) banner.visibility = View.GONE
    }

    private fun pcWord(w: PcWow) {
        pc = w
        if (!StreamCurtain.atLogin(w)) { loginSince = 0L; main.removeCallbacks(loginShow) }
        else if (loginSince == 0L) { loginSince = System.currentTimeMillis(); main.postDelayed(loginShow, StreamCurtain.LOGIN_SHOW_MS + 100L) }
        signInTick()
    }

    /** since when WoW has held at its login steps, 0 = it is not there */
    private var loginSince = 0L
    private fun loginLong() = loginSince != 0L && System.currentTimeMillis() - loginSince >= StreamCurtain.LOGIN_SHOW_MS

    /** WoW waits for the player: to the front, then the curtain's rule shows it */
    private val loginShow = object : Runnable {
        override fun run() {
            if (loginSince == 0L) return
            if (pc?.focused != true) { worker.execute { bridge?.focusWow() }; main.postDelayed(this, 5_000L) }
            signInTick()
        }
    }

    /** the saved login into the PC's Battle.net (the user, 2026-10-30), then what the top and bottom screens show for it */
    private val signIn = StreamSignIn(
        hasLogin = { com.jrees.wowcompanion.HomeLoginStore.has(activity) },
        formOpen = { SetupBridge.loginFormOpen },
        openForm = { SetupBridge.openLoginForm?.invoke() },
        ask = { worker.execute { bridge?.askBnetReady() } },
        type = { form -> typeLogin(form) },
        now = { System.currentTimeMillis() })

    private val signInLoop = Runnable { signInTick() }

    private fun signInTick() {
        main.removeCallbacks(signInLoop)
        signIn.tick(screen == Screen.STREAMING && pc?.bnet == "signin_needed")
        applyCurtain()
        if (signIn.working) main.postDelayed(signInLoop, 1_000L)
    }

    /** the login from its sealed store to the stream's keys, and no further: nothing of it is logged */
    private fun typeLogin(form: BnetFormAt) {
        Thread({
            val login = com.jrees.wowcompanion.HomeLoginStore.load(activity)
            val stream = (com.jrees.wowcompanion.InputSink.sink as? com.jrees.wowcompanion.TapSink)?.route == "stream"
            val why = when {
                login == null -> "no login"
                !stream -> "no stream"
                else -> com.jrees.wowcompanion.InputSink.signIn(login.email, login.password, form.email, form.password, form.button, CLEAR_KEYS)
            }
            Log.i(TAG, "saved sign-in: " + (why ?: "typed"))
            if (why != null) main.post { signIn.failed(); signInTick() }
        }, "stream-signin").start()
    }

    /** the keyboard on the bottom screen and the pad on it, while Battle.net's window shows */
    private var boardUp = false
    private val boardPad = SignInPad()
    /** the player opened it (the waiting screen's Keyboard) / closed it */
    private var boardByHand = false
    private var boardShut = false

    private fun board(needed: Boolean) {
        if (!needed) boardShut = false
        val up = boardByHand || (needed && !boardShut)
        if (up == boardUp) return
        boardUp = up
        if (up && keys != null) { pad.clear(); try { sendPad() } catch (_: Throwable) {} }
        DsForeverScreen.current?.signInBoard(up)
    }

    /** WoW's state on the PC: "starting" stays up until it runs; a failure shows for a while */
    private fun wowState(state: String, why: String) {
        val text = BridgeProtocol.wowText(state, why)
        main.removeCallbacks(hideBanner)
        if (text.isEmpty() || host?.hasBridge == true) { if (host?.hasBridge != true) banner.visibility = View.GONE; applyCurtain(); return }
        banner.text = text
        banner.visibility = View.VISIBLE
        banner.bringToFront()
        if (state == "start_failed") main.postDelayed(hideBanner, 12_000)
        if (screen == Screen.CONNECTING) status.show("Connecting to ${host?.name ?: "the PC"}…", text, "", withButtons = false)
    }

    /** Streaming's top screen came up: the mode, discovery, then the pairing screen or straight to the PC */
    fun opened() {
        if (isOpen) return
        isOpen = true
        host = store.host()
        // the companion on the bottom screen, reading the PC's log through the bridge (Local is untouched: PlayModes defaults to it)
        applyMode()
        discovery = Discovery(activity, preferSubnet = { NetProbe.subnet(activity) }) { pcs -> found = pcs; onFound() }.also { it.start() }
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
        portsWait = null
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
        // a new bridge (a new address): Desktop's word is the new connection's, "PC found" waits for its welcome (its ports and reason too)
        pc = null
        ports = null
        pcWhy = ""
        val clientId = store.clientId()
        PlayModes.set(PlayMode.STREAM, { onMsg, onStatus ->
            BridgeLog(h.address, h.bridgePort, h.bridgeTls, h.bridgeFingerprint, h.bridgeToken, clientId, WsClient, onMsg, onStatus,
                onAuthLost = { why -> main.post { authLost(why) } },
                onWow = { state, why -> main.post { wowState(state, why) } },
                onPc = { w -> main.post { pcWord(w) } },
                onWelcome = { w -> main.post { welcomed(w) } },
                onBnetReady = { form -> main.post { signIn.answer(form); signInTick() } },
                savedLogin = { com.jrees.wowcompanion.HomeLoginStore.forAutoLogin(activity)?.let { it.email to it.password } }).also { bridge = it }
        }, "${h.desktopId}@${h.address}:${h.bridgePort}")
    }

    /** Desktop listed the PC's home-network addresses: the one on this Wi-Fi is tried first next time (Route); its Tailscale ones are
     *  remembered for away from home, and Sunshine's ports it chose (the user, 2026-11-11: streaming away fixes) */
    private fun welcomed(w: BridgeMsg.Welcome) {
        // issue #54 ("on pc stream I cant use my ayn thor to play, input doesnt work"): the PC's word on its controller driver, in the report
        if (w.padDriver != padDriver) { padDriver = w.padDriver; Log.i(TAG, "PC controller driver (ViGEmBus): " + PadNote.driver(w.padDriver)) }
        // the user, 2026-11-13 (another Sunshine on the stock ports, "PC found" for ever): Desktop's ports and its reason
        ports = StreamPorts.Report.of(w)
        val why = StreamCurtain.reason(w.sunProblem, w.sunWhy)
        if (why != pcWhy) { pcWhy = why; Log.i(TAG, "PC: streaming ${if (why.isEmpty()) "fine" else "problem ${w.sunProblem}: ${w.sunWhy}"}") }
        val h = host ?: return
        var n = Route.reported(h, w.lan, NetProbe.subnet(activity), w.tailscale)
        if (w.httpPort > 0 && (w.httpPort != n.httpPort || StreamPorts.httpsOf(w.httpPort, w.httpsPort) != n.httpsPort))
            n = n.copy(httpPort = w.httpPort, httpsPort = StreamPorts.httpsOf(w.httpPort, w.httpsPort))
        if (n != h) { host = n; store.save(n); Log.i(TAG, "${h.name}: home address ${n.lanAddress}, Tailscale ${n.tailnet}, ports ${n.httpPort}/${n.httpsPort}") }
        if (portsWait != null) { main.removeCallbacks(portsTick); main.post(portsTick) } else applyCurtain()
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
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        val wasStreaming = screen == Screen.STREAMING || session != null
        stopStream("surface gone")
        // 1.0.22 (the user: "when I press the home button while using streaming mode, going back into the game just results in a black
        // screen"): the surface went with Home and the stream with it, but the screen stayed STREAMING, which surfaceCreated never
        // reconnects. Back to CONNECTING (the curtain), so the stream resumes on the PC's same session when the surface returns
        if (wasStreaming) show(Screen.CONNECTING)
    }

    /**
     * Wi-Fi first (the user, 2026-10-28): the PC's addresses in [Route]'s order, each tried briefly; the first that answers is dialled
     * (the bridge too) and remembered. None: the curtain says why ([Route.unreachable]) and it tries again by the policy.
     */
    private fun connect() {
        val h = host ?: return show(Screen.PAIRING)
        if (session != null || !surfaceReady || routing) return
        main.removeCallbacks(retryTick)
        main.removeCallbacks(retryRoute)
        main.removeCallbacks(portsTick); portsWait = null
        show(Screen.CONNECTING)
        status.show("Connecting to ${h.name}…", quality.label, "", withButtons = false)
        val thorNet = NetProbe.subnet(activity)
        val seen = found.filter { (h.desktopId.isNotEmpty() && it.desktopId == h.desktopId) || (h.uuid.isNotEmpty() && it.sunshineId == h.uuid) }
            .map { it.address }
        val cands = Route.candidates(h, seen, emptyList(), thorNet)
        val port = if (h.hasBridge) h.bridgePort else h.httpPort
        routing = true
        worker.execute {
            val ok = Route.pick(cands) { NetProbe.port(it, port, Route.PROBE_MS) == Diagnosis.Port.OPEN }
            val vpn = NetProbe.vpnUp(activity)
            main.post {
                routing = false
                if (!isOpen || session != null) return@post
                if (ok == null) {
                    route = null
                    reachProblem = Route.unreachable(cands, vpn)
                    reachFails++
                    val wait = ReachBackoff.delayMs(reachFails)
                    Log.i(TAG, "route: nothing answered (tried ${cands.joinToString()}, vpn $vpn; try $reachFails, next in ${wait / 1000} s)")
                    // backs off, then offers Retry (the user, 2026-11-11: streaming away fixes - it retried every 3 s for ever)
                    if (ReachBackoff.manual(reachFails)) {
                        problem("Can't reach your PC", reachProblem.orEmpty(), auto = true, waitMs = wait)
                        return@post
                    }
                    applyCurtain()
                    main.postDelayed(retryRoute, wait)
                    return@post
                }
                reachFails = 0
                reachProblem = null
                route = Route.kind(ok, thorNet)
                val n = Route.after(h, ok, thorNet)
                Log.i(TAG, "route: $ok (${route?.label})")
                if (n != h) { host = n; store.save(n) }
                if (n.address != h.address) applyMode()          // the bridge dials the address that answered (same pin)
                applyCurtain()
                portsWait = System.currentTimeMillis() to StreamMode.choose(ok, thorNet, vpn)
                portsTick.run()
            }
        }
    }

    /**
     * The PC answered: the stream starts on the Sunshine ports Desktop reported ([StreamPorts]; never the stock 47989/47984 by guess:
     * the user's PC 2026-11-13 had another, unpaired Sunshine there). Until they are known the curtain says "Waiting for the PC" (or
     * the PC's reason); after [StreamPorts.GIVE_UP_MS] the problem screen, retrying by the policy.
     */
    private val portsTick: Runnable = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            val (since, mode) = portsWait ?: return
            val h = host
            // "Stream quality" opened meanwhile keeps the wait (it returns to CONNECTING); Pair again / a problem ends it
            if (h == null || !isOpen || session != null || (screen != Screen.CONNECTING && screen != Screen.QUALITY)) { portsWait = null; applyCurtain(); return }
            val waited = System.currentTimeMillis() - since
            when (val p = StreamPorts.pick(h, ports, waited)) {
                is StreamPorts.Pick.Use -> {
                    portsWait = null
                    Log.i(TAG, "sunshine ports ${p.http}/${p.https} (${p.from})")
                    applyCurtain()
                    startSession(h.copy(httpPort = p.http, httpsPort = p.https), mode)
                }
                StreamPorts.Pick.Wait -> {
                    if (waited >= StreamPorts.GIVE_UP_MS && screen == Screen.CONNECTING) {
                        portsWait = null
                        Log.i(TAG, "sunshine ports: none from the PC in ${waited / 1000} s (welcome ${if (ports == null) "none" else "without a port"})")
                        problem(StreamCurtain.WAITING, pcWhy.ifEmpty { "DS Forever Desktop hasn't said its streaming port." }, auto = true)
                        return
                    }
                    applyCurtain()
                    main.postDelayed(this, 500L)
                }
            }
        }
    }

    private val retryRoute = Runnable { if (isOpen && session == null && screen == Screen.CONNECTING) connect() }
    /** no address answered, this many tries in a row ([ReachBackoff]) */
    private var reachFails = 0

    private fun startSession(h: PairedHost, mode: StreamMode.Choice) {
        val q = quality
        worker.execute {
            val id = store.identity()
            main.post {
                if (session != null || !surfaceReady || !isOpen) return@post
                val video = VideoDecoder(surface.holder.surface)
                session = StreamSession(h, id, q, video, AudioOut(q.audioOnThor), events, mode = mode,
                    httpPort = { host?.httpPort?.takeIf { it > 0 } ?: h.httpPort }).also { it.start() }
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
        padSent = 0
        show(Screen.STREAMING)
        val k = StreamKeys(MoonBridge.INPUT, w, h).also { keys = it }
        DsForeverScreen.current?.attachStream(k)
        DsForeverScreen.current?.signInClose = { boardByHand = false; boardShut = true; applyCurtain() }
        SetupBridge.openPcKeyboard = { boardByHand = true; boardShut = false; applyCurtain() }
        SetupBridge.exitStream = { main.post { stopStream("menu"); back() } }
        SetupBridge.openStreamSettings = { main.post { openQuality() } }
        touchMouse = StreamTouch(MoonBridge.INPUT, view.width, view.height, w, h,
            android.view.ViewConfiguration.get(activity).scaledTouchSlop.toFloat()) { ms, r -> main.postDelayed(r, ms) }
        try { MoonBridge.sendControllerArrivalEvent(0, 1, MoonBridge.LI_CTYPE_XBOX, StreamPad.SUPPORTED,
            (MoonBridge.LI_CCAP_ANALOG_TRIGGERS.toInt() or MoonBridge.LI_CCAP_RUMBLE.toInt()).toShort()) } catch (t: Throwable) { Log.w(TAG, "arrival: ${t.message}") }
        worker.execute { bridge?.focusWow() }
        host?.let { store.save(it.copy(lastSeenMs = System.currentTimeMillis())) }
        Log.i(TAG, "streaming ${w}x$h")
    }

    private fun onEnded(error: Int, notPaired: Boolean, message: String) {
        val wasStreaming = screen == Screen.STREAMING
        teardown()
        Log.i(TAG, "stream ended ($error, paired ${!notPaired}): $message; " + PadNote.sent(padSent, padDriver))
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
    /** [waitMs] = the wait before the next try when the caller has its own schedule ([ReachBackoff]); null = [policy]'s */
    private fun problem(title: String, detail: String, note: String = "", auto: Boolean, waitMs: Long? = null) {
        show(Screen.PROBLEM)
        main.removeCallbacks(retryTick)
        val wait = if (!auto) -1 else waitMs ?: policy.nextDelayMs(Diagnosis.Reason.PC_OFF)
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

    private fun retryNow() { policy.reset(); reachFails = 0; retryAt = 0; if (host == null) show(Screen.PAIRING) else connect() }

    private fun stopStream(why: String) {
        session?.let { Log.i(TAG, "stopping the stream: $why"); it.stop() }
        teardown()
    }

    private fun teardown() {
        seen = false
        SetupBridge.openPcKeyboard = null
        SetupBridge.exitStream = null
        SetupBridge.openStreamSettings = null
        boardByHand = false
        board(false)
        touchMouse?.cancel(); touchMouse = null
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
            val same = found.firstOrNull { (h.desktopId.isNotEmpty() && it.desktopId == h.desktopId) || (h.uuid.isNotEmpty() && it.sunshineId == h.uuid) ||
                it.name.equals(h.name, ignoreCase = true) }
            // never while a stream is being set up, and the home LAN's address sticks (AddressPolicy)
            if (same != null && session == null) {
                val thorNet = NetProbe.subnet(activity)
                when (AddressPolicy.decide(h.address, same.address, thorNet)) {
                    AddressPolicy.Move.STAY -> {}
                    AddressPolicy.Move.MOVE -> moveHost(h, same.address, thorNet)
                    AddressPolicy.Move.PROBE_FIRST -> if (!probingMove) {
                        probingMove = true
                        worker.execute {
                            val ok = NetProbe.port(h.address, h.httpPort.takeIf { it > 0 } ?: if (h.hasBridge) h.bridgePort else StreamPorts.STOCK_HTTP) == Diagnosis.Port.OPEN
                            main.post {
                                probingMove = false
                                if (ok) Log.i(TAG, "${h.name}: staying on the LAN address ${h.address} (seen also at ${same.address})")
                                else if (host == h && session == null) moveHost(h, same.address, thorNet)
                            }
                        }
                    }
                }
            }
        }
        if (screen == Screen.PAIRING) showPc()
    }

    private var probingMove = false

    private fun moveHost(h: PairedHost, to: String, thorNet: String) {
        Log.i(TAG, "${h.name} moved: ${h.address} -> $to")
        host = h.copy(address = to, subnet = thorNet.ifEmpty { h.subnet }).also { store.save(it) }
        applyMode()
        if (screen == Screen.PROBLEM) retryNow()
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

    /** the part of the PC's screen the top screen shows now (x, y, w, h shares), null = all of it */
    private var zoomed: List<Float>? = null

    /**
     * The stream zoomed onto [show] (shares of the PC's screen) and centred, black round it ([ZoomMask]); null = the whole picture.
     * The surface is laid out larger than the screen and shifted (the window clips it), so the decoder and touch = mouse need no change:
     * a touch maps through the surface's own size.
     */
    private fun zoom(show: List<Float>?) {
        val vw = view.width; val vh = view.height
        if (show == zoomed || vw == 0 || vh == 0) return
        zoomed = show
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        if (show == null) {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT; lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.leftMargin = 0; lp.topMargin = 0
            mask.hole = null
        } else {
            val k = minOf(ZOOM_FILL / show[2], ZOOM_FILL / show[3], ZOOM_MAX).coerceAtLeast(1f)
            val sw = vw * k; val sh = vh * k
            val hw = show[2] * sw; val hh = show[3] * sh
            val hl = (vw - hw) / 2f; val ht = (vh - hh) / 2f
            lp.width = sw.toInt(); lp.height = sh.toInt()
            lp.leftMargin = (hl - show[0] * sw).toInt(); lp.topMargin = (ht - show[1] * sh).toInt()
            mask.hole = android.graphics.RectF(hl, ht, hl + hw, ht + hh)
        }
        surface.layoutParams = lp
        mask.visibility = if (show == null) View.GONE else View.VISIBLE
        mask.invalidate()
        Log.i(TAG, "zoom ${show ?: "off"}")
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
                // the pairing's address is where it was found (mDNS on this Wi-Fi): remembered as the home route (Route; most players
                // have no Tailscale at all - the home network is THE route, anything else only a fallback)
                val paired = Route.after(s.host.copy(address = ""), s.host.address, NetProbe.subnet(activity))
                host = paired; store.save(paired)
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
        applyCurtain()
    }

    private fun openQuality() {
        if (screen == Screen.QUALITY) return
        beforeQuality = screen
        qualityAtOpen = quality
        val v = qualityView ?: QualityView(activity, quality, onChange = { q -> quality = q; StreamQuality.save(activity, q) }, onClose = { qualityDone() })
            .also { qualityView = it; overlay.centered(it, 560f) }
        show(Screen.QUALITY)
        v.bringToFront()
    }

    /** a change made while streaming applies now: the stream connects again (the game on the PC keeps running) */
    private fun qualityDone() {
        if (beforeQuality == Screen.STREAMING && quality != qualityAtOpen && session != null) {
            stopStream("stream settings changed")
            retryNow()
        } else show(beforeQuality)
    }

    private fun back() = shell.back()

    // ---------------------------------------------------------------- the controller
    private fun isPad(e: KeyEvent) = (e.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK || KeyEvent.isGamepadButton(e.keyCode)

    /** a key from the host's activity; true = Streaming took it (its own screens, or sent to the PC) */
    fun key(e: KeyEvent): Boolean {
        val gamepad = isPad(e)
        if (boardUp && gamepad && StreamPad.isPadKey(e.keyCode)) {           // Battle.net's keyboard holds the pad, nothing behind it hears
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) boardPad.name(e.keyCode)?.let { DsForeverScreen.current?.signInPress(it) }
            return true
        }
        if (gamepad) PadFeed.key(e)                          // DS Forever's own readers: a copy of every press, as in Local
        com.jrees.wowcompanion.PadDiag.key(e, gamepad, if (screen == Screen.STREAMING) "stream" else "stream's own screens")   // #116
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
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X); val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (boardUp) {
            boardPad.axes(hx, hy, maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)),
                maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))).forEach { DsForeverScreen.current?.signInPress(it) }
            return true
        }
        PadFeed.motion(e)
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

    // ---------------------------------------------------------------- touch = mouse (no on-screen controls over the stream: the user, 2026-10-29)
    private var touchMouse: StreamTouch? = null
    private val touchIds = HashSet<Int>()

    /** a touch on the picture (no on-screen control under it): the PC's mouse. Only while streaming; true = taken */
    fun touch(e: MotionEvent): Boolean {
        val t = touchMouse
        if (screen != Screen.STREAMING || t == null) return false
        t.viewW = surface.width; t.viewH = surface.height     // the surface's own size: right when zoomed too
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val id = e.getPointerId(i); touchIds.add(id); t.down(id, e.getX(i), e.getY(i), e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> for (k in 0 until e.pointerCount) {
                val id = e.getPointerId(k); if (id in touchIds) t.move(id, e.getX(k), e.getY(k), e.eventTime)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> { val id = e.getPointerId(i); if (touchIds.remove(id)) t.up(id, e.eventTime) }
            MotionEvent.ACTION_CANCEL -> cancelTouch()
        }
        return true
    }

    fun cancelTouch() { touchIds.clear(); touchMouse?.cancel() }

    /** issue #54: controller states sent to the PC this stream, and the PC's controller driver word (a Desktop with this fix) */
    private var padSent = 0
    private var padDriver = ""

    private fun sendPad() {
        if (!MoonBridge.loaded) { if (padSent == 0) { padSent = -1; Log.w(TAG, "pad: not sent (the stream library is not loaded)") }; return }
        if (padSent >= 0 && padSent++ == 0) Log.i(TAG, "pad: first controller input sent to the PC; " + PadNote.driver(padDriver))
        val s = pad.snapshot()
        MoonBridge.sendMultiControllerInput(0, 1, s.buttons, s.lt.toByte(), s.rt.toByte(), s.lx.toShort(), s.ly.toShort(), s.rx.toShort(), s.ry.toShort())
    }

    // ---------------------------------------------------------------- the network
    private fun watchNetwork() {
        val cm = activity.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            // also on the curtain while no address answers (Tailscale just turned on: try at once, not after the backoff's wait)
            override fun onAvailable(network: Network) { main.postDelayed({
                if (screen == Screen.PROBLEM || (screen == Screen.CONNECTING && session == null && !routing && reachFails > 0)) retryNow() }, 1_500) }
        }
        try { cm.registerDefaultNetworkCallback(cb); netCallback = cb } catch (t: Throwable) { Log.w(TAG, "network watch: ${t.message}") }
    }

    companion object {
        const val TAG = "DSFSTREAM"
        /** a zoomed window fills this share of the screen at most, and is magnified at most [ZOOM_MAX] x */
        const val ZOOM_FILL = 0.92f
        const val ZOOM_MAX = 3f
        /** Backspaces before each box is typed (the box may hold what the player started) */
        const val CLEAR_KEYS = 16
        private val SIGN_IN_LINES = setOf(StreamCurtain.SIGN_IN, StreamCurtain.NEEDS_YOU, StreamCurtain.UPDATE, StreamCurtain.CRASHED)
    }
}

/** black everywhere but [hole] (the zoomed window); takes every touch outside the hole so nothing reaches the PC's desktop */
internal class ZoomMask(ctx: android.content.Context) : View(ctx) {
    var hole: android.graphics.RectF? = null
    private val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }

    init { visibility = GONE }

    override fun onDraw(c: android.graphics.Canvas) {
        val h = hole ?: return
        val w = width.toFloat(); val ht = height.toFloat()
        c.drawRect(0f, 0f, w, h.top, paint); c.drawRect(0f, h.bottom, w, ht, paint)
        c.drawRect(0f, h.top, h.left, h.bottom, paint); c.drawRect(h.right, h.top, w, h.bottom, paint)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean = hole?.contains(e.x, e.y) == false
}
