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
import com.jrees.wowcompanion.StreamSettingsLink
import com.jrees.wowcompanion.StreamTest
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
 *  - QUALITY: "Stream quality", only while Streaming's placeholder covers the bottom screen (no PC paired: the pairing's Y).
 * THE STREAM'S SETTINGS ARE THE BOTTOM SCREEN'S (the user, 2026-11-18 on test 1488: "the streaming settings appear on the top screen and
 * the controller inputs get all wacky, they should probably appear on the bottom screen in the stream settings box"): Settings >
 * Performance > Stream settings reads and writes them through [settingsLink] (quality, Defaults, Test connection); every Y / "Stream
 * quality" here opens that band (SetupBridge.openStreamSettings). While it is open over the waiting screen (no game in the world to
 * hold the pad for the room) every press goes to it ([SetupBridge.streamPadHolds]), none to the PC.
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

    enum class Screen { PAIRING, PIN, CONNECTING, STREAMING, PROBLEM, QUALITY, TEST }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dsf-stream-work") }
    private val store by lazy { HostStore(activity) }
    private var host: PairedHost? = null
    @Volatile private var quality = StreamQuality()
    /** 120 fps per codec and size, read once ([StreamRate.can120]) */
    private val can120Seen = HashMap<Pair<StreamQuality.Codec, Int>, Boolean>()
    private fun can120(q: StreamQuality): Boolean = synchronized(can120Seen) {
        can120Seen.getOrPut(q.codec to q.height) { StreamRate.can120(activity, q).also { Log.i(TAG, "120 fps at ${q.codec.label} ${q.height}p: ${if (it) "offered" else "not offered"}") } }
    }
    /** what this Thor plays: a stored 120 it can't show plays at 60 */
    private fun playable(q: StreamQuality = quality) = StreamQuality.forDevice(q, q.fps != 120 || can120(q))
    /** the running stream's frame rate (the refresh it asked for) */
    private var streamFps = 0
    /** stream smoothness (StreamSmooth.kt): the Wi-Fi low-latency lock, held only from a stream's start to its end */
    private val wifiLock by lazy { StreamWifiLock(activity) }
    /** the PC search (mDNS) is stopped for the running stream ("discovery: paused"); started again when it ends */
    private var discoveryPaused = false
    /** the stats line's last reading (null = not streaming) and the running stream's RFI words */
    private var statsLast: StatsSnap? = null
    @Volatile private var smoothLabel = ""
    private val statsTick = object : Runnable {
        override fun run() {
            val v = liveVideo ?: return
            val a = statsLast ?: return
            val b = v.snap(android.os.SystemClock.elapsedRealtime())
            val rtt = try { if (MoonBridge.loaded) MoonBridge.getEstimatedRttInfo() else -1L } catch (_: Throwable) { -1L }
            val (link, rssi) = StreamStats.wifiLink(activity)
            Log.i(TAG, StreamStats.line(a, b, rtt, link, rssi, smoothLabel + if (wifiLock.held) ", wifi lock" else ""))
            statsLast = b
            main.postDelayed(this, StreamStats.EVERY_MS)
        }
    }

    private lateinit var surface: SurfaceView
    /** black round the zoomed window; a touch on the black goes nowhere */
    private lateinit var mask: ZoomMask
    private lateinit var overlay: FrameLayout
    private lateinit var pairing: PairingView
    private lateinit var status: StatusView
    private var qualityView: QualityView? = null
    private var qualityAtOpen = StreamQuality()
    /** the bottom screen's Stream settings band is open */
    @Volatile private var bandOpen = false
    /** the test's rows for that band (null = none this visit) */
    @Volatile private var bandTest: StreamTest? = null
    /** the presses held for the bottom screen's band (SetupBridge.streamPadHolds) at the last press: the PC's pad let go on the change */
    private var padForBand = false
    private val bandPadNames = SignInPad()

    /** the stream's settings as the bottom screen's band sees them ([StreamSettingsLink]): what they do is unchanged (the old page's) */
    private val settingsLink = object : StreamSettingsLink {
        override fun quality(): StreamQuality = playable()
        override fun can120(q: StreamQuality): Boolean = this@StreamTop.can120(q)
        override fun change(q: StreamQuality) { quality = q; StreamQuality.save(activity, q); SetupBridge.streamSettingsChanged() }
        override fun opened() { bandOpen = true; qualityAtOpen = quality; bandTest = null; testRan = false; Log.i(TAG, "stream settings: open (bottom screen)") }
        override fun closed() { bandOpen = false; bandDone() }
        override fun test() { if (bandOpen) runTest(bandOut) }
        override fun tested(): StreamTest? = bandTest
        override fun restartsOnClose(): Boolean = bandOpen && screen == Screen.STREAMING && session != null && quality != qualityAtOpen
        override fun say(line: String): String = TestView.trHead(line)
    }
    private var screen = Screen.CONNECTING
    private var beforeQuality = Screen.CONNECTING

    private var discovery: Discovery? = null
    private var found: List<FoundPc> = emptyList()
    private var pcIndex = 0

    private var session: StreamSession? = null
    private var keys: StreamKeys? = null
    private val pad = StreamPad()
    private val policy = ReconnectPolicy()
    /** RTSP timeouts: ask Desktop to heal its host, then give up with Retry ([HostHeal]) */
    private val heal = HostHeal()
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
            // Desktop 0.2.36's update of the PC game: its bar under its row
            curtainBars = { (shows() as? StreamCurtain.Shows.Curtain)?.bars.orEmpty() }
            // a tap on a stop is its Retry, as A is (the user's ruling 2026-11-18)
            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_UP && retryShown()) wowRetry("a tap"); true }
            contentDescription = "Starting WoW on your PC"
        }
        view.addView(curtainView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        overlay = FrameLayout(activity)
        view.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        pairing = PairingView(activity, onPair = { pair(it) }, onBack = { back() }, onQuality = { openQuality() }, onPcStep = { stepPc(it) })
        status = StatusView(activity, onRetry = { retryNow(byHand = true) }, onPairAgain = { show(Screen.PAIRING) }, onQuality = { openQuality() }, onBack = { back() })
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
        pcWhy.ifEmpty { StreamCurtain.waitLine(pcWait) }, portsWait != null)

    /** this bridge connection's word on Sunshine's ports (its welcome; null = none yet): [StreamPorts] picks from it, never 47989 by guess */
    private var ports: StreamPorts.Report? = null
    /** the PC's reason it can't stream (the welcome's sunshine.problem / why, [StreamCurtain.reason]; "" = none) */
    private var pcWhy = ""
    /** Desktop 0.2.32: what the stream's start waits for ("screen" | "starting"; "" = go): [StreamCurtain.holds] */
    private var pcWait = ""
    /** a connect that answered and waits for Desktop's Sunshine ports: since when, and its stream mode; null = not waiting */
    private var portsWait: Pair<Long, StreamMode.Choice>? = null
    /** issue #414: the next routed connect streams in this mode ([StreamMode.retryAfter]: no QoS after "No video arrived"); null = none */
    private var retryMode: StreamMode.Choice? = null

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
        val over = if (s === StreamCurtain.Shows.Stream && screen == Screen.STREAMING) StreamCurtain.streamLine(pc) else ""
        if (s is StreamCurtain.Shows.SignIn && screen == Screen.STREAMING) {
            main.removeCallbacks(hideBanner)
            banner.text = s.line; banner.visibility = View.VISIBLE; banner.bringToFront()
        } else if (over.isNotEmpty()) {
            // a refused region: WoW's own region screen shows, with the one line over it until another region is taken
            main.removeCallbacks(hideBanner)
            banner.text = over; banner.visibility = View.VISIBLE; banner.bringToFront()
        } else if (up || banner.text in SIGN_IN_LINES) banner.visibility = View.GONE
    }

    private fun pcWord(w: PcWow) {
        // issues #304 / #357: Desktop's word on why the taps can't reach WoW (its status line says it to the player), in the report
        if (w.input != pc?.input.orEmpty()) Log.i(TAG, "PC: WoW input ${w.input.ifEmpty { "fine" }}")
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
        SetupBridge.streamSettings = settingsLink
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
        bandOpen = false; bandTest = null; padForBand = false
        SetupBridge.streamSettings = null
        stopStream("leaving")
        discovery?.stop(); discovery = null
        netCallback?.let { try { activity.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } catch (_: Throwable) {} }
        netCallback = null
        testCancel = true
        testSession?.stop(); testSession = null
        main.removeCallbacksAndMessages(null)
        portsWait = null
        retryMode = null
        heal.reset()
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
        pcWait = ""
        val clientId = store.clientId()
        PlayModes.set(PlayMode.STREAM, { onMsg, onStatus ->
            BridgeLog(h.address, h.bridgePort, h.bridgeTls, h.bridgeFingerprint, h.bridgeToken, clientId, WsClient, onMsg, onStatus,
                onAuthLost = { why -> main.post { authLost(why) } },
                onWow = { state, why -> main.post { wowState(state, why) } },
                onPc = { w -> main.post { pcWord(w) } },
                onWelcome = { w -> main.post { welcomed(w) } },
                onSunshine = { m -> main.post { sunshineSaid(m) } },
                onPcTest = { m -> pcTestSaid(m) },
                onSessionEnd = { m -> main.post { sessionSaid(m) } },
                onBnetReady = { form -> main.post { signIn.answer(form); signInTick() } },
                savedLogin = { com.jrees.wowcompanion.HomeLoginStore.forAutoLogin(activity)?.let { it.email to it.password } },
                gameAccount = { gameAccount() },
                streamMode = { playable().let { Triple(it.width, it.height, it.fps) } },
                // settings follow (2026-11-18): our kept tables to and from the PC, only down a pinned wss connection (as the login)
                svDir = { if (h.bridgeTls && h.bridgeFingerprint.isNotEmpty()) java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS), "DS Forever/settings") else null },
                // the Thor's add-ons for the PC (the user, 2026-11-18: "My desktop seems to be missing the required addons"): what DS Forever
                // manages (pins + wants), the imported ones (their files: only down a pinned wss connection) and their on / off
                addonsWant = { pcAddonsWant(h.bridgeTls && h.bridgeFingerprint.isNotEmpty()) },
                importedFiles = { if (h.bridgeTls && h.bridgeFingerprint.isNotEmpty()) pcImported() else null to emptySet() },
                // the user's ruling 2026-11-18: the PC's stream WoW at this Thor's own UI scale (Desktop puts the PC's back after)
                uiScale = { thorUiScale() },
                // Polestar progress follows (the user's pick 2026-11-18): the Thor game's Polestar.lua files, only down a pinned wss connection
                polestar = { if (h.bridgeTls && h.bridgeFingerprint.isNotEmpty()) polestarCtx() else null }).also { bridge = it }
        }, "${h.desktopId}@${h.address}:${h.bridgePort}")
    }

    /** PsSync's Thor side: the game's WTF folder (null = no game folder yet), its bases + backups beside our kept settings */
    private fun polestarCtx(): com.jrees.wowcompanion.PsSync.Ctx? = runCatching {
        val root = com.jrees.wowcompanion.LauncherHooks.flavorRoot() ?: return null
        com.jrees.wowcompanion.PsSync.Ctx(java.io.File(root, "WTF"), java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOCUMENTS), "DS Forever/settings"), gameRunning = { com.jrees.wowcompanion.LauncherHooks.gameUp() })
    }.getOrNull()

    /** "addons_want" for Desktop; the imported add-ons only with [pinned] (their files cross only a pinned wss connection) */
    private fun pcAddonsWant(pinned: Boolean): org.json.JSONObject? = runCatching {
        com.jrees.wowcompanion.ThirdPartyAddons.init(activity)
        val root = com.jrees.wowcompanion.LauncherHooks.flavorRoot()
        val state = java.io.File(activity.filesDir, "dsforever/addons")
        val acct = runCatching { com.jrees.wowcompanion.SvSync.accountFile(java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOCUMENTS), "DS Forever/settings")).takeIf { it.isFile }?.readText()?.trim() }.getOrNull().orEmpty()
        com.jrees.wowcompanion.PcAddonsWant.build(root, state, acct,
            importedFolders = if (pinned) com.jrees.wowcompanion.ImportedAddons.list().map { it.folder } else emptyList())
    }.onFailure { Log.w(TAG, "pc add-ons: ${it.javaClass.simpleName}") }.getOrNull()

    private fun pcImported(): Pair<java.io.File?, Set<String>> = runCatching {
        val root = com.jrees.wowcompanion.LauncherHooks.flavorRoot()
        root?.let { java.io.File(it, "Interface/AddOns") } to com.jrees.wowcompanion.ImportedAddons.list().map { it.folder }.toSet()
    }.getOrDefault(null to emptySet())

    /**
     * The Thor's remembered game account for DS Forever Desktop (the user's pick 2026-11-17, "Yes, remember it"), as Settings > Game
     * account says: "Ask each time" sends nothing; "Remembered" sends the pick the Thor's own Config.wtf remembers, if any (an account
     * name: never logged). Desktop uses it only to find the WTF/Account folder our settings tables follow: since the user's ruling
     * 2026-11-23 it writes nothing into the PC's Config.wtf
     */
    private fun gameAccount(): BridgeProtocol.GameAccount {
        com.jrees.wowcompanion.Settings.init(activity)
        if (com.jrees.wowcompanion.Settings.gameAccountAsk) return BridgeProtocol.GameAccount(ask = true)
        val root = runCatching { com.jrees.wowcompanion.GameFiles.gameRoot(activity) }.getOrNull()
        // A (the user's ruling 2026-11-20): the Thor's marked list, else the learned pick alone ("!WoW2|"), for Desktop's settings
        // follow (WowSvSync.PickAccount); never written into the PC's Config.wtf (the user's ruling 2026-11-23)
        val list = root?.let { com.jrees.wowcompanion.GameConfigWtf.rememberedAccountList(it) }
            ?: com.jrees.wowcompanion.Settings.gameAccountPick?.let { com.jrees.wowcompanion.GameConfigWtf.accountListValue(null, it) }
        return BridgeProtocol.GameAccount(ask = false, list = list)
    }

    /** this Thor's own UI scale lines (Config.wtf's useUiScale / uiScale, values only in the log); null = none, the PC's stays */
    private fun thorUiScale(): Map<String, String>? {
        val root = runCatching { com.jrees.wowcompanion.GameFiles.gameRoot(activity) }.getOrNull() ?: return null
        val s = com.jrees.wowcompanion.GameConfigWtf.uiScale(root)
        Log.i(TAG, "UI scale for the PC's stream: " + (s?.entries?.joinToString(", ") { "${it.key} ${it.value}" } ?: "none in Config.wtf (the PC's stays)"))
        return s
    }

    /** Desktop listed the PC's home-network addresses: the one on this Wi-Fi is tried first next time (Route); its Tailscale ones are
     *  remembered for away from home, and Sunshine's ports it chose (the user, 2026-11-11: streaming away fixes) */
    private fun welcomed(w: BridgeMsg.Welcome) {
        authLostSeen = false
        // issue #54 ("on pc stream I cant use my ayn thor to play, input doesnt work"): the PC's word on its controller driver, in the report
        if (w.padDriver != padDriver) { padDriver = w.padDriver; Log.i(TAG, "PC controller driver (ViGEmBus): " + PadNote.driver(w.padDriver)) }
        // the user, 2026-11-13 (another Sunshine on the stock ports, "PC found" for ever): Desktop's ports and its reason
        ports = StreamPorts.Report.of(w)
        val why = StreamCurtain.reason(w.sunProblem, w.sunWhy)
        if (why != pcWhy) { pcWhy = why; Log.i(TAG, "PC: streaming ${if (why.isEmpty()) "fine" else "problem ${w.sunProblem}: ${w.sunWhy}"}") }
        waitSaid(w.sunWait)
        val h = host ?: return
        var n = Route.reported(h, w.lan, NetProbe.subnet(activity), w.tailscale)
        if (w.httpPort > 0 && (w.httpPort != n.httpPort || StreamPorts.httpsOf(w.httpPort, w.httpsPort) != n.httpsPort))
            n = n.copy(httpPort = w.httpPort, httpsPort = StreamPorts.httpsOf(w.httpPort, w.httpsPort))
        if (n != h) { host = n; store.save(n); Log.i(TAG, "${h.name}: home address ${n.lanAddress}, Tailscale ${n.tailnet}, ports ${n.httpPort}/${n.httpsPort}") }
        if (portsWait != null) { main.removeCallbacks(portsTick); main.post(portsTick) } else applyCurtain()
    }

    /** Desktop 0.2.32: its Sunshine moved to new ports, or its answer to our host_check ([HostHeal]) */
    private fun sunshineSaid(m: BridgeMsg.Sunshine) {
        Log.i(TAG, "PC: streaming host ${if (m.answer) "check: ${m.state}" else "moved"}, ports ${m.httpPort}/${m.httpsPort}" +
            if (m.problem.isNotEmpty()) " (${m.problem})" else "")
        if (m.httpPort > 0) {
            ports = m.report
            host?.let { h ->
                val n = h.copy(httpPort = m.httpPort, httpsPort = StreamPorts.httpsOf(m.httpPort, m.httpsPort))
                if (n != h) { host = n; store.save(n) }
            }
        }
        val why = StreamCurtain.reason(m.problem, m.why)
        if (why != pcWhy) pcWhy = why
        waitSaid(m.wait)
        if (!m.answer) { if (portsWait != null) { main.removeCallbacks(portsTick); main.post(portsTick) } else applyCurtain(); return }
        when (heal.answer(m.state)) {
            HostHeal.Next.RETRY -> { main.removeCallbacks(healTimeout); Log.i(TAG, "PC: streaming host ready: one more try"); connect() }
            HostHeal.Next.GIVE_UP -> hostDown("PC said ${m.state}")
            else -> applyCurtain()
        }
    }

    /** Desktop's wait word (verifier 2026-11-17: the stream started with the bridge, before the PC's own screen was made) */
    private fun waitSaid(w: String) {
        if (w == pcWait) return
        Log.i(TAG, "PC: stream start " + if (w.isEmpty()) "may go" else "waits ($w)")
        pcWait = w
    }

    /** two RTSP timeouts with HTTP fine: Desktop checks and restarts its streaming host; we wait for its word */
    private fun askHost() {
        main.removeCallbacks(retryTick)
        retryAt = 0L
        show(Screen.PROBLEM)
        val sent = bridge?.askHostCheck() == true
        Log.i(TAG, "RTSP timed out ${heal.timeouts} times: asking the PC to check its streaming host" + if (sent) "" else " (no bridge connection)")
        if (!sent) { heal.noAnswer(); hostDown("no bridge"); return }
        status.show(com.jrees.wowcompanion.Tr.t(HostHeal.RESTARTING), "", "", withButtons = false)
        main.removeCallbacks(healTimeout)
        main.postDelayed(healTimeout, HostHeal.ASK_WAIT_MS)
    }

    private val healTimeout = Runnable { if (heal.noAnswer() == HostHeal.Next.GIVE_UP) hostDown("no answer in ${HostHeal.ASK_WAIT_MS / 1000} s") }

    /** the user's pick: "Message + Retry button"; no more tries by itself */
    private fun hostDown(why: String) {
        main.removeCallbacks(healTimeout)
        Log.i(TAG, "PC streaming host not responding ($why): Retry by hand")
        problem(com.jrees.wowcompanion.Tr.t(HostHeal.DOWN), com.jrees.wowcompanion.Tr.t(HostHeal.DOWN_DETAIL), auto = false)
    }

    private fun authLost(why: String) {
        Log.w(TAG, "bridge: $why")
        authLostSeen = true
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
        testSession?.stop()
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
        if (ending != null) return                           // feat/pause-power: the clean disconnect runs: no new stream
        val h = host ?: return show(Screen.PAIRING)
        if (session != null || !surfaceReady || routing || testSession != null) return
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
            var answered = Route.pick(cands) { NetProbe.port(it, port, Route.PROBE_MS) == Diagnosis.Port.OPEN }
            // issue #414: the stored bridge port answers nowhere and mDNS is silent: Desktop may be on another of its ports (the old
            // one held by a stuck process); one counts only when it names this Desktop
            var moved = 0
            if (answered == null && h.hasBridge) for (a in cands.take(BridgePorts.ADDRESSES)) {
                val p = NetProbe.findBridge(a, BridgePorts.others(h.bridgePort), h.desktopId) ?: continue
                answered = a; moved = p; break
            }
            val ok = answered
            val bridgeAt = moved
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
                val n = Route.after(h, ok, thorNet).let { if (bridgeAt > 0) it.copy(bridgePort = bridgeAt) else it }
                Log.i(TAG, "route: $ok (${route?.label})" + if (bridgeAt > 0) "; bridge port ${h.bridgePort} -> $bridgeAt" else "")
                if (n != h) { host = n; store.save(n) }
                // the bridge dials the address (and the port) that answered (same pin)
                if (n.address != h.address || n.bridgePort != h.bridgePort) applyMode()
                applyCurtain()
                portsWait = System.currentTimeMillis() to StreamMode.forConnect(StreamMode.choose(ok, thorNet, vpn), retryMode)
                retryMode = null
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
                    // Desktop is making its own screen for us, or its host is starting: a start now would land on the real screen
                    // or meet a restart; it waits for Desktop's "go" (a "sunshine" message), at most PC_WAIT_MS
                    if (StreamCurtain.holds(pcWait, waited)) { applyCurtain(); main.postDelayed(this, 500L); return }
                    if (pcWait.isNotEmpty()) Log.i(TAG, "PC: still '$pcWait' after ${waited / 1000} s: starting anyway")
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
        val q = playable()
        streamFps = q.fps
        worker.execute {
            val id = store.identity()
            // stream smoothness: reference-frame invalidation for HEVC / AV1 on a decoder that qualifies, unless it tripped on this build
            val offWhy = RfiStore.offWhy(activity)
            val caps = RfiPolicy.capabilities(q.codec, offWhy == null, VideoDecoder.rfiOk("video/hevc"), VideoDecoder.rfiOk("video/av01"))
            val label = RfiPolicy.label(caps, offWhy ?: RfiPolicy.offReason(q.codec))
            main.post {
                if (session != null || !surfaceReady || !isOpen) return@post
                val video = VideoDecoder(surface.holder.surface).also { liveVideo = it }
                smoothLabel = label
                session = StreamSession(h, id, q, video, AudioOut(q.audioOnThor), events, mode = mode,
                    httpPort = { host?.httpPort?.takeIf { it > 0 } ?: h.httpPort }, videoCapabilities = caps, capsLabel = label).also { it.start() }
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
        heal.reset()
        main.removeCallbacks(healTimeout)
        if (streamFps >= 100) StreamRate.request(activity, surface.holder.surface, streamFps)
        smoothStart()
        padSent = 0
        show(Screen.STREAMING)
        val k = StreamKeys(MoonBridge.INPUT, w, h).also { keys = it }
        DsForeverScreen.current?.attachStream(k)
        DsForeverScreen.current?.signInClose = { boardByHand = false; boardShut = true; applyCurtain() }
        SetupBridge.openPcKeyboard = { boardByHand = true; boardShut = false; applyCurtain() }
        SetupBridge.exitStream = { main.post { stopStream("menu"); back() } }
        // feat/pause-power: THE CLEAN DISCONNECT (the pause menu's power button; the waiting screen's Exit game): the PC's WoW closes
        // and the PC is put back by Desktop, then the stream and the bridge stop
        SetupBridge.endSession = { why, asked, done -> main.post { endSession(why, asked, closeOnAck = true, then = done) } }
        SetupBridge.endStream = { main.post { endSession("the menu's Exit game", false) { back() } } }
        // feat/exit-now: the Menu's Exit game closes DS Forever at once (ExitNow): Desktop told (it closes the PC's WoW and puts the PC
        // back on its own), the send bounded, the stream and the bridge stopped; nothing waits for Desktop's word
        SetupBridge.quitStream = { boundMs ->
            val b = bridge
            val sent = java.util.concurrent.atomic.AtomicBoolean(false)
            val t = Thread({ sent.set(runCatching { b?.endSession(false) == true }.getOrDefault(false)) }, "dsf-stream-quit").apply { isDaemon = true; start() }
            runCatching { t.join(boundMs) }
            Log.i(TAG, "exit now: end_session ${if (sent.get()) "sent: Desktop closes WoW and puts the PC back on its own" else "not sent (no bridge connection, or not within $boundMs ms)"}")
            main.post { stopStream("Exit game: DS Forever closes"); runCatching { bridge?.stop() } }
        }
        touchMouse = StreamTouch(MoonBridge.INPUT, view.width, view.height, w, h,
            android.view.ViewConfiguration.get(activity).scaledTouchSlop.toFloat()) { ms, r -> main.postDelayed(r, ms) }
        try { MoonBridge.sendControllerArrivalEvent(0, 1, MoonBridge.LI_CTYPE_XBOX, StreamPad.SUPPORTED,
            (MoonBridge.LI_CCAP_ANALOG_TRIGGERS.toInt() or MoonBridge.LI_CCAP_RUMBLE.toInt()).toShort()) } catch (t: Throwable) { Log.w(TAG, "arrival: ${t.message}") }
        worker.execute { bridge?.focusWow() }
        host?.let { store.save(it.copy(lastSeenMs = System.currentTimeMillis())) }
        Log.i(TAG, "streaming ${w}x$h")
    }

    private fun onEnded(error: Int, notPaired: Boolean, message: String) {
        // feat/pause-power: the clean disconnect stopped it: no diagnosis, no retry
        if (ending != null) { teardown(); Log.i(TAG, "stream ended ($error) for the clean disconnect"); return }
        val wasStreaming = screen == Screen.STREAMING
        val mode = session?.mode
        val failedStage = session?.failedStage.orEmpty()
        // stream smoothness, the safe default: an RFI stream that may have gone wrong because of it turns RFI off on this Thor
        session?.let { s -> liveVideo?.let { v ->
            RfiPolicy.trips(s.videoCapabilities, error, v.decoderErrors, v.framesOut, v.codecMime)?.let { why -> worker.execute { RfiStore.trip(activity, why) } }
        } }
        teardown()
        Log.i(TAG, "stream ended ($error, paired ${!notPaired}): $message; " + PadNote.sent(padSent, padDriver))
        if (!isOpen) return
        if (notPaired) { problem(Diagnosis.Reason.NOT_PAIRED.title, Diagnosis.Reason.NOT_PAIRED.detail, message, auto = false); return }
        if (wasStreaming) policy.reset()
        // issue #414 (the user's pick, 2026-11-16): no video on a home stream: once more at once, without QoS tagging
        StreamMode.retryAfter(mode, error)?.let { m ->
            Log.i(TAG, "no video on a ${mode?.label} stream: one retry ${m.label} (qosTrafficType 0)")
            retryMode = m
            connect()
            return
        }
        // the user's PC 2026-11-17: RTSP refused for ever while HTTP answered: ask Desktop, then Retry by hand ([HostHeal])
        if (!wasStreaming && HostHeal.isRtspTimeout(error, failedStage)) {
            when (heal.rtspTimeout(host?.hasBridge == true)) {
                HostHeal.Next.ASK_PC -> { askHost(); return }
                HostHeal.Next.GIVE_UP -> { hostDown("RTSP timed out ${heal.timeouts} times"); return }
                HostHeal.Next.WAIT -> return
                else -> {}
            }
        } else heal.otherEnd()
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

    /**
     * [byHand] = the player's Retry (the button or A on the problem screen). Only that asks Desktop to start its own virtual screen's
     * tries over (verifier 2026-11-17: the network coming back, mDNS moves and the quality sheet call this too, so every flap re-armed
     * the screen makes with no pause - against "never a loop").
     */
    private fun retryNow(byHand: Boolean = false) {
        policy.reset(); heal.reset(); main.removeCallbacks(healTimeout); reachFails = 0; retryAt = 0
        // Desktop's own virtual screen gave up after its 3 tries: the player's Retry starts them over
        if (byHand) bridge?.screenRetry()
        if (host == null) show(Screen.PAIRING) else connect()
    }

    /** the curtain shows Desktop's stop with its Retry ([StreamCurtain.stopLine]) */
    private fun retryShown(): Boolean = (screen == Screen.STREAMING || screen == Screen.CONNECTING) &&
        (shows() as? StreamCurtain.Shows.Curtain)?.retry == true
    /** the curtain shows Desktop's update of the PC game with its Cancel (B) ([StreamCurtain.patchShows]) */
    private fun cancelShown(): Boolean = (screen == Screen.STREAMING || screen == Screen.CONNECTING) &&
        (shows() as? StreamCurtain.Shows.Curtain)?.cancel == true
    private var patchCancelAt = 0L

    /** the player's Cancel (B) on the update's bar: Desktop stops it (the old game stays as it was) */
    private fun patchCancel() {
        val now = System.currentTimeMillis()
        if (now - patchCancelAt < WOW_RETRY_GAP_MS) return
        patchCancelAt = now
        Log.i(TAG, "PC: Cancel (B): the game update stops")
        worker.execute { if (bridge?.patchCancel() != true) Log.i(TAG, "PC: Cancel not sent (no bridge connection)") }
    }
    private var wowRetryAt = 0L

    /** the player's Retry after Desktop stopped: WoW once more on the PC (one press = one restart; a press within
     *  [WOW_RETRY_GAP_MS] of the last is the same press) */
    private fun wowRetry(how: String) {
        val now = System.currentTimeMillis()
        if (now - wowRetryAt < WOW_RETRY_GAP_MS) return
        wowRetryAt = now
        Log.i(TAG, "PC: Retry ($how): WoW once more")
        worker.execute { if (bridge?.wowRetry() != true) Log.i(TAG, "PC: Retry not sent (no bridge connection)") }
    }

    // ---------------------------------------------------------------- the clean disconnect (feat/pause-power)

    /** the clean disconnect under way ([SessionEnd]) and what runs once it is decided */
    private var ending: SessionEnd? = null
    private var endThen: ((com.jrees.wowcompanion.PausePower.Ended) -> Unit)? = null

    /**
     * The user, 2026-11-18: "When streaming this should also close wow on the PC and revert everything back and be the clean way to
     * disconnect". "end_session" to Desktop (it closes the PC's WoW - the game's own Exit Game first when [quitAsked] - and puts the PC
     * back), the picture stopped at once (after the Exit Game tap has gone down the stream: the PC's desktop never shows on the top
     * screen as WoW closes), "Closing WoW on your PC" on the top screen; Desktop's answer (or [SessionEnd]'s time) ends it: the bridge
     * disconnects, then [then].
     */
    private fun endSession(why: String, quitAsked: Boolean, closeOnAck: Boolean = false, then: (com.jrees.wowcompanion.PausePower.Ended) -> Unit) {
        if (ending != null) { Log.i(TAG, "clean disconnect ($why): already under way"); return }
        // fix/1492-feedback: the power button's end goes on Desktop's "session_ending" ([SessionEnd.closeOnAck]): DS Forever closes
        // while Desktop waits out the game's own Exit Game countdown on the PC
        val e = SessionEnd(System.currentTimeMillis(), closeOnAck = closeOnAck)
        ending = e; endThen = then
        Log.i(TAG, "clean disconnect ($why): Desktop closes WoW and puts the PC back" + if (quitAsked) " (the game's own Exit Game asked first)" else "")
        worker.execute {
            val sent = bridge?.endSession(quitAsked) == true
            main.post {
                e.sent(sent)
                Log.i(TAG, "clean disconnect: end_session ${if (sent) "sent" else "NOT sent (no bridge connection): the stream stops only"}")
                endTick.run()
            }
        }
        main.postDelayed({
            if (ending !== e) return@postDelayed
            stopStream("the clean disconnect")
            show(Screen.CONNECTING)
            status.show(com.jrees.wowcompanion.Tr.t(com.jrees.wowcompanion.PausePower.CLOSING_STREAM) + "\u2026", "", "", withButtons = false)
        }, if (quitAsked) SessionEnd.QUIT_TAP_SETTLE_MS else 0L)
    }

    private val endTick: Runnable = object : Runnable {
        override fun run() {
            val e = ending ?: return
            main.removeCallbacks(this)
            val r = e.check(System.currentTimeMillis()) ?: run { main.postDelayed(this, 250L); return }
            ending = null
            Log.i(TAG, "clean disconnect: " + if (r.answered) "Desktop: WoW ${r.wow}, put back ${r.putBack}${if (r.why.isNotEmpty()) " (${r.why})" else ""}"
                else "ended without Desktop's word (${r.why})")
            stopStream("the clean disconnect ended")
            bridge?.stop()                                   // the bridge disconnects before DS Forever / Streaming goes
            val then = endThen; endThen = null
            then?.invoke(r)
        }
    }

    /** Desktop's session_ending / session_ended */
    private fun sessionSaid(m: BridgeMsg.SessionEnd) {
        val e = ending ?: return
        Log.i(TAG, "clean disconnect: Desktop " + if (m.done) "ended the session" else "is closing WoW")
        e.said(m)
        endTick.run()
    }

    private fun stopStream(why: String) {
        session?.let { Log.i(TAG, "stopping the stream: $why"); it.stop() }
        teardown()
    }

    private fun teardown() {
        seen = false
        if (streamFps >= 100) StreamRate.release(activity, surface.holder.surface)
        streamFps = 0
        smoothStop()
        SetupBridge.openPcKeyboard = null
        SetupBridge.exitStream = null
        SetupBridge.endSession = null; SetupBridge.endStream = null; SetupBridge.quitStream = null
        boardByHand = false
        board(false)
        touchMouse?.cancel(); touchMouse = null
        session = null
        liveVideo = null
        keys?.releaseAll()
        if (keys != null) { pad.clear(); try { sendPad() } catch (_: Throwable) {} }
        keys = null
        DsForeverScreen.current?.detachGame()
    }

    /**
     * STREAM SMOOTHNESS at a stream's start (StreamSmooth.kt; the user's picks 2026-11-23): the Wi-Fi low-latency lock, the PC search
     * stopped (Moonlight stops polling during a stream), the stats line every 5 s
     */
    private fun smoothStart() {
        Log.i(TAG, wifiLock.acquire())
        if (discovery != null && !discoveryPaused) { discovery?.stop(); discoveryPaused = true; Log.i(TAG, "discovery: paused for the stream") }
        main.removeCallbacks(statsTick)
        statsLast = liveVideo?.snap(android.os.SystemClock.elapsedRealtime())
        main.postDelayed(statsTick, StreamStats.EVERY_MS)
    }

    /** its end (every end: stop, Home, a pause, the screen closing, a lost connection): the lock let go, the search back while open */
    private fun smoothStop() {
        main.removeCallbacks(statsTick)
        statsLast = null
        wifiLock.release("the stream ended").takeIf { it.isNotEmpty() }?.let { Log.i(TAG, it) }
        if (discoveryPaused) {
            discoveryPaused = false
            if (isOpen) { discovery?.start(); Log.i(TAG, "discovery: on again") }
        }
    }

    // ---------------------------------------------------------------- pairing
    private fun selectedPc(): FoundPc? = found.getOrNull(pcIndex.coerceIn(0, (found.size - 1).coerceAtLeast(0)))

    private fun onFound() {
        if (pcIndex >= found.size) pcIndex = 0
        // the paired PC under a new address (DHCP): follow it, by Desktop's id or Sunshine's
        val h0 = host
        if (h0 != null && screen != Screen.STREAMING) {
            val same = found.firstOrNull { (h0.desktopId.isNotEmpty() && it.desktopId == h0.desktopId) || (h0.uuid.isNotEmpty() && it.sunshineId == h0.uuid) ||
                it.name.equals(h0.name, ignoreCase = true) }
            // never while a stream is being set up, and the home LAN's address sticks (AddressPolicy)
            if (same != null && session == null) {
                val thorNet = NetProbe.subnet(activity)
                // issue #414: the paired Desktop's bridge on another port (its old one held by a stuck process): dialled there from now on
                val h = BridgePorts.follow(h0, same)
                val move = AddressPolicy.decide(h.address, same.address, thorNet)
                if (h != h0) {
                    Log.i(TAG, "${h0.name}: bridge port ${h0.bridgePort} -> ${h.bridgePort} (mDNS)")
                    host = h; store.save(h)
                    // a move redials by itself (moveHost)
                    if (move != AddressPolicy.Move.MOVE) { applyMode(); if (screen == Screen.PROBLEM) retryNow() }
                }
                when (move) {
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
        // the Other network code (PairCode; the user's picks 2026-11-18, design B): a PC mDNS can't see is dialled there
        val parsed = PairCode.parse(code)
        if (parsed != null && parsed.hasAddress) { pairByAddress(code, parsed); return }
        // no PC on this network: the Other network row opens by itself, "PC not found" under it
        val pc = selectedPc() ?: run { pairing.notFound(); return }
        if (!pc.hasBridge) { pairPlain(pc); return }
        pairWith(pc, code)
    }

    private fun pairWith(pc: FoundPc, code: String) {
        pairing.busy = true
        pairing.setMessage("")
        worker.execute {
            val id = store.identity()
            val flow = StreamPairing(RealPairSteps(id, store.clientId())) { s -> main.post { pairState(s) } }
            flow.pairWithCode(pc, code, NetProbe.subnet(activity))
        }
    }

    /** a code with the PC's address: the PC mDNS found there, else Desktop's /v1/info at that address (all asked at once), else the
     *  picked PC; nothing = "PC not reachable at ..." */
    private fun pairByAddress(code: String, parsed: PairCode.Parsed) {
        val seen = found; val picked = selectedPc()
        pairing.busy = true
        pairing.setMessage("")
        worker.execute {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(parsed.addresses.size.coerceIn(1, 2))
            val asks = parsed.addresses.associateWith { a -> pool.submit<String?> { NetProbe.bridgeInfo(a, parsed.bridgePort, PAIR_INFO_MS) } }
            val t = try {
                PairCode.target(parsed, seen, picked) { a ->
                    try { asks[a]?.get(PAIR_INFO_MS * 3L, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Throwable) { null }
                }
            } finally { pool.shutdownNow() }
            main.post {
                when (t) {
                    is PairCode.Target.Use -> {
                        if (t.byCode) Log.i(TAG, "pairing: ${t.pc.name} at the code's address ${t.pc.address}:${t.pc.bridgePort}")
                        if (!t.pc.hasBridge) { pairing.busy = false; pairPlain(t.pc) } else pairWith(t.pc, code)
                    }
                    is PairCode.Target.NotReachable -> { pairing.busy = false; pairing.setMessage(com.jrees.wowcompanion.Tr.t(PairCode.notReachable(t.addresses))) }
                    PairCode.Target.NoPc -> pairing.notFound()
                }
            }
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
            is StreamPairing.State.WrongCode -> { pairing.busy = false; pairing.reset(keepOther = true)
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
        testView?.visibility = if (s == Screen.TEST) View.VISIBLE else View.GONE
        if (s == Screen.PAIRING) { main.removeCallbacks(retryTick); showPc() }
        applyCurtain()
    }

    /** Y / "Stream quality": the bottom screen's Stream settings band (every route the same); the top screen's page only while
     *  Streaming's placeholder covers the bottom screen (no PC paired yet: the pairing) */
    private fun openQuality() {
        val bottom = SetupBridge.openStreamSettings
        if (bottom != null && !com.jrees.wowcompanion.ModeBridge.streamCovers) { bottom(); return }
        openQualityTop()
    }

    /** the band closed: a change made while streaming applies now (the stream connects again, the game on the PC keeps running); a test
     *  still running stops; a test run meanwhile connects again (the old page's Done) */
    private fun bandDone() {
        if (testThread?.isAlive == true) { testCancel = true; testSession?.stop(); synchronized(pcTestGate) { pcTestGate.notifyAll() } }
        bandTest = null
        val tested = testRan
        testRan = false
        Log.i(TAG, "stream settings: closed (${if (quality != qualityAtOpen) "changed: ${quality.label}" else "no change"})")
        if (screen == Screen.STREAMING && quality != qualityAtOpen && session != null) {
            stopStream("stream settings changed")
            retryNow()
        } else if (tested && session == null && host != null && screen != Screen.PAIRING && screen != Screen.PIN && screen != Screen.QUALITY &&
            screen != Screen.TEST) {
            show(Screen.CONNECTING)
            retryNow()
        }
    }

    /** the bottom band's test rows, as TestView shows them on the top screen (built off the test's thread, read by the band's draw) */
    private val bandOut = object : TestOut {
        private val rows = LinkedHashMap<ConnTest.Step, StreamTest.Row>()
        private var reason = ""
        private var fix = ""
        private var running = false
        private fun publish() {
            val t = synchronized(rows) { StreamTest(rows.values.toList(), reason, fix, running) }
            if (!bandOpen) return
            bandTest = t
            SetupBridge.streamSettingsChanged()
        }
        override fun clear() {
            synchronized(rows) { rows.clear(); for (s in ConnTest.Step.values()) rows[s] = StreamTest.Row(s.label, "", StreamTest.Mark.WAIT)
                reason = ""; fix = ""; running = true }
            publish()
        }
        override fun working(s: ConnTest.Step) { synchronized(rows) { rows[s] = StreamTest.Row(s.label, "", StreamTest.Mark.WORKING) }; publish() }
        override fun result(r: ConnTest.Result) {
            synchronized(rows) {
                rows[r.step] = StreamTest.Row(r.step.label, r.line, when (r.state) {
                    ConnTest.State.PASS -> StreamTest.Mark.PASS; ConnTest.State.FAIL -> StreamTest.Mark.FAIL
                    ConnTest.State.SKIP -> StreamTest.Mark.SKIP; ConnTest.State.NOT_RUN -> StreamTest.Mark.WAIT })
                if (r.state == ConnTest.State.FAIL) { reason = r.line; fix = r.fix }
            }
            publish()
        }
        override fun done() { synchronized(rows) { running = false }; publish() }
    }

    /** where a test's rows go: the top screen's TestView (the pairing's page) or the bottom screen's band ([bandOut]) */
    private interface TestOut {
        fun clear()
        fun working(s: ConnTest.Step)
        fun result(r: ConnTest.Result)
        fun done()
    }
    private fun topOut(v: TestView) = object : TestOut {
        override fun clear() { v.clear() }
        override fun working(s: ConnTest.Step) { v.working(s) }
        override fun result(r: ConnTest.Result) { v.result(r) }
        override fun done() { v.done() }
    }

    /** the top screen's "Stream quality" page (the pairing, while the bottom screen shows Streaming's placeholder) */
    private fun openQualityTop() {
        if (screen == Screen.QUALITY) return
        beforeQuality = screen
        qualityAtOpen = quality
        val v = qualityView ?: QualityView(activity, quality, onChange = { q -> quality = q; StreamQuality.save(activity, q) }, onClose = { qualityDone() },
            can120 = { q -> can120(q) }, onTest = { openTest() })
            .also { qualityView = it; overlay.centered(it, 560f) }
        show(Screen.QUALITY)
        v.bringToFront()
    }

    /** a change made while streaming applies now: the stream connects again (the game on the PC keeps running) */
    private fun qualityDone() {
        val tested = testRan
        testRan = false
        if (beforeQuality == Screen.STREAMING && quality != qualityAtOpen && session != null) {
            stopStream("stream settings changed")
            retryNow()
        } else if (tested && session == null && host != null && beforeQuality != Screen.PAIRING && beforeQuality != Screen.PIN) {
            // a test ran meanwhile: its own stream stopped, and any wait of the connect with it: connect again now (or once the test's
            // stream has stopped: testStream's end)
            show(Screen.CONNECTING)
            retryNow()
        } else show(beforeQuality)
    }

    private fun back() = shell.back()

    // ---------------------------------------------------------------- Test connection ([ConnTest]; the user's pick 2026-11-17)
    private var testView: TestView? = null
    @Volatile private var testThread: Thread? = null
    @Volatile private var testCancel = false
    /** the test's own 3-second stream (none while a real one runs: that one is measured) */
    @Volatile private var testSession: StreamSession? = null
    /** the running stream's decoder: its frames count for the test while streaming */
    @Volatile private var liveVideo: VideoDecoder? = null
    /** the bridge refused our token on this connection */
    @Volatile private var authLostSeen = false
    /** Desktop's half of the test (pc_test_result), and when it was asked (0 = not asked) */
    @Volatile private var pcTest: BridgeMsg.PcTest? = null
    @Volatile private var pcAskedAt = 0L
    private val pcTestGate = Object()
    /** a test ran since Stream settings opened: closing them connects again */
    private var testRan = false

    private fun pcTestSaid(m: BridgeMsg.PcTest) {
        Log.i(TAG, "PC test: " + m.lines.joinToString("; "))
        synchronized(pcTestGate) { pcTest = m; pcTestGate.notifyAll() }
    }

    private fun openTest() {
        val v = testView ?: TestView(activity, onRun = { testView?.let { runTest(topOut(it)) } }, onClose = { closeTest() })
            .also { testView = it; overlay.centered(it, 680f) }
        show(Screen.TEST)
        v.bringToFront()
        runTest(topOut(v))
    }

    private fun closeTest() {
        testCancel = true
        testSession?.stop()
        synchronized(pcTestGate) { pcTestGate.notifyAll() }
        if (qualityView != null) { show(Screen.QUALITY); qualityView?.bringToFront() } else show(beforeQuality)
    }

    private fun runTest(v: TestOut) {
        if (testThread?.isAlive == true) return
        testCancel = false
        testRan = true
        v.clear()
        val h = host
        val q = playable()
        val live = session != null && liveVideo != null
        testThread = Thread({
            val rs = ConnTest.run({ s -> v.working(s); check(s, h, q, live) }, onStep = { v.result(it) }, cancelled = { testCancel || !isOpen })
            rs.filter { it.state == ConnTest.State.NOT_RUN }.forEach { v.result(it) }
            val summary = ConnTest.summary(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()), rs,
                pcTest?.lines.orEmpty())
            com.jrees.wowcompanion.ConnTestNote.save(activity, summary)
            summary.lineSequence().forEach { Log.i(TAG, it) }
            v.done()
        }, "dsf-conn-test").apply { isDaemon = true; start() }
    }

    /** one step, on the test's own thread */
    private fun check(s: ConnTest.Step, h0: PairedHost?, q: StreamQuality, live: Boolean): ConnTest.Result {
        val h = host ?: h0 ?: return ConnTest.fail(s, "No PC paired", ConnTest.FIX_PAIR)
        return when (s) {
            ConnTest.Step.PC_FOUND -> {
                if (h.hasBridge) { val until = System.currentTimeMillis() + 5_000; while (ports == null && System.currentTimeMillis() < until && !testCancel) Thread.sleep(200) }
                val welcomed = ports != null
                ConnTest.pcFound(h.hasBridge, welcomed, if (welcomed || !h.hasBridge) null else Diagnosis.reason(NetProbe.probe(activity, h, found)))
            }
            ConnTest.Step.PAIRED -> ConnTest.paired(h.hasBridge, ports != null, authLostSeen).also {
                // Desktop's half runs meanwhile (its screen check can take a few seconds)
                if (it.state == ConnTest.State.PASS && h.hasBridge) {
                    synchronized(pcTestGate) { pcTest = null }
                    pcAskedAt = if (bridge?.askPcTest() == true) System.currentTimeMillis() else 0L
                } else pcAskedAt = 0L
            }
            ConnTest.Step.WEB_PORTS -> {
                val (http, https) = testPorts(h)
                var httpErr: String? = null
                var httpsErr: String? = null
                var pair: String? = null
                val cert = try { ClientIdentity.parseCert(h.serverCertPem.toByteArray()) } catch (_: Throwable) { null }
                    ?: return ConnTest.fail(s, "The paired PC's certificate is unreadable", ConnTest.FIX_PAIR)
                val nv = NvHttp(h.address, http, https, store.identity(), cert)
                if (http > 0) try { nv.serverInfo(false) } catch (t: Throwable) { httpErr = why(t) }
                if (http > 0 && httpErr == null) try { pair = XmlFields.get(nv.serverInfo(true), "PairStatus") } catch (t: Throwable) { httpsErr = why(t) }
                ConnTest.webPorts(http, https, httpErr, httpsErr, pair)
            }
            ConnTest.Step.RTSP -> {
                val port = testPorts(h).first + RTSP_OFFSET
                ConnTest.rtsp(port, if (live) Diagnosis.Port.NOT_TRIED else NetProbe.port(h.address, port, 2_000), live)
            }
            ConnTest.Step.TEST_STREAM -> testStream(h, q, live)
            ConnTest.Step.CONTROLLER -> ConnTest.controller(pcAnswer()?.pad?.ifEmpty { null } ?: padDriver)
            ConnTest.Step.SCREEN -> pcAnswer().let { ConnTest.screen(it?.screen.orEmpty(), it?.screenLine.orEmpty()) }
            ConnTest.Step.FPS120 -> ConnTest.fps120(quality.fps, can120(q.copy(fps = 120)), pcAnswer()?.screenHz ?: 0)
        }
    }

    /** the Sunshine ports a stream would dial now ([StreamPorts]: Desktop's word, never a guess) */
    private fun testPorts(h: PairedHost): Pair<Int, Int> = when (val p = StreamPorts.pick(h, ports, StreamPorts.WAIT_MS)) {
        is StreamPorts.Pick.Use -> p.http to p.https
        StreamPorts.Pick.Wait -> 0 to 0
    }

    private fun why(t: Throwable): String = when (t) {
        is HostHttpException -> if (t.code == 401) "401" else "HTTP ${t.code}"
        is NvHttpStageException -> if (t.timedOut) "${t.stage} timed out" else "${t.stage} failed"
        else -> t.javaClass.simpleName
    }

    /** Desktop's answer, waited for up to [PC_TEST_WAIT_MS] from the ask; null = none (an older Desktop, no bridge) */
    private fun pcAnswer(): BridgeMsg.PcTest? {
        if (pcAskedAt == 0L) return null
        synchronized(pcTestGate) {
            while (pcTest == null && !testCancel) {
                val left = pcAskedAt + PC_TEST_WAIT_MS - System.currentTimeMillis()
                if (left <= 0) break
                pcTestGate.wait(minOf(left, 500L))
            }
            return pcTest
        }
    }

    /** 3 s of the stream: the running one's frames, else a test stream of its own (stopped after) */
    private fun testStream(h: PairedHost, q: StreamQuality, live: Boolean): ConnTest.Result {
        val step = ConnTest.Step.TEST_STREAM
        val (http, https) = testPorts(h)
        if (live) {
            val v = liveVideo ?: return ConnTest.fail(step, "The stream just stopped", "Run the test again.")
            val f0 = v.framesOut
            Thread.sleep(3_000)
            return ConnTest.stream(true, 0, "", v.framesOut - f0, http)
        }
        if (!surfaceReady) return ConnTest.fail(step, "The top screen isn't ready", "Run the test again.")
        val up = java.util.concurrent.CountDownLatch(1)
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val endErr = java.util.concurrent.atomic.AtomicInteger(0)
        val ev = object : StreamSession.Events {
            override fun stage(text: String) {}
            override fun started(width: Int, height: Int) { started.set(true); up.countDown() }
            override fun ended(error: Int, notPaired: Boolean, message: String) { endErr.set(error); up.countDown() }
        }
        val video = java.util.concurrent.atomic.AtomicReference<VideoDecoder?>(null)
        val made = java.util.concurrent.CountDownLatch(1)
        main.post {
            if (session == null && surfaceReady && isOpen && !testCancel) {
                val vd = VideoDecoder(surface.holder.surface).also { video.set(it) }
                testSession = StreamSession(h.copy(httpPort = http, httpsPort = https), store.identity(), q, vd, AudioOut(false), ev,
                    mode = StreamMode.choose(h.address, NetProbe.subnet(activity), NetProbe.vpnUp(activity))).also { it.start() }
            }
            made.countDown()
        }
        made.await(2, java.util.concurrent.TimeUnit.SECONDS)
        val ts = testSession ?: return ConnTest.fail(step, "A stream is starting", "Run the test again.")
        Log.i(TAG, "test stream: ${q.label}")
        try {
            if (!up.await(TEST_START_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) return ConnTest.stream(false, -1, ts.failedStage, 0, http)
            if (!started.get()) return ConnTest.stream(false, endErr.get(), ts.failedStage, 0, http, pcAnswer()?.failed?.line.orEmpty())
            val f0 = video.get()?.framesOut ?: 0L
            Thread.sleep(3_000)
            return ConnTest.stream(true, 0, "", (video.get()?.framesOut ?: 0L) - f0, http)
        } finally {
            ts.stop()
            Thread.sleep(800)          // its stop runs on its own thread
            testSession = null
            Log.i(TAG, "test stream stopped")
            main.post { if (isOpen && session == null && screen == Screen.CONNECTING) connect() }
        }
    }

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
        // the bottom screen's Stream settings over the waiting screen holds the pad: every press to it, none to the PC or our readers
        if (bandHolds() && ((gamepad && StreamPad.isPadKey(e.keyCode)) || e.keyCode == KeyEvent.KEYCODE_BACK)) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0)
                (if (e.keyCode == KeyEvent.KEYCODE_BACK) "PAD2" else bandPadNames.name(e.keyCode))?.let { SetupBridge.streamPad?.invoke(it) }
            return true
        }
        if (gamepad) PadFeed.key(e)                          // DS Forever's own readers: a copy of every press, as in Local
        com.jrees.wowcompanion.PadDiag.key(e, gamepad, if (screen == Screen.STREAMING) "stream" else "stream's own screens")   // #116
        val down = e.action == KeyEvent.ACTION_DOWN
        // Desktop stopped (a server error, an outdated PC game) and the curtain says why: A is its Retry, nothing reaches the PC
        // Desktop updates the PC game and the curtain shows its bar: B is its Cancel, nothing reaches the PC
        if (gamepad && cancelShown()) {
            if (down && e.repeatCount == 0 && (e.keyCode == KeyEvent.KEYCODE_BUTTON_B || e.keyCode == KeyEvent.KEYCODE_BACK)) patchCancel()
            if (StreamPad.isPadKey(e.keyCode) || e.keyCode == KeyEvent.KEYCODE_BACK) return true
        }
        if (gamepad && (screen == Screen.STREAMING || screen == Screen.CONNECTING) && retryShown()) {
            if (down && e.repeatCount == 0 && e.keyCode == KeyEvent.KEYCODE_BUTTON_A) wowRetry("A")
            // B still leaves the connecting screen; on the stream every pad key stays here
            val leaves = screen == Screen.CONNECTING && (e.keyCode == KeyEvent.KEYCODE_BUTTON_B || e.keyCode == KeyEvent.KEYCODE_BACK)
            if (StreamPad.isPadKey(e.keyCode) && !leaves) return true
        }
        if (screen != Screen.STREAMING) {
            if (down && e.repeatCount == 0 && overlayKey(e.keyCode)) return true
            return gamepad || e.keyCode == KeyEvent.KEYCODE_BACK      // nothing reaches the PC from our own screens
        }
        if (gamepad && StreamPad.isPadKey(e.keyCode)) {
            // one of our pop-ups asks (the sign-in's, the report notice): A, B and the D-pad do nothing for the game
            if (PopupHold.holdsKey(SetupBridge.holdsGamePad(), e.keyCode, down, SetupBridge.wheelHolds())) return true
            if (e.repeatCount > 0) return true
            if (pad.key(e.keyCode, down)) sendPad()
            return true
        }
        return false
    }

    /** the bottom screen's Stream settings holds the pad now; on the change the PC's pad lets go (no press stays down there) */
    private fun bandHolds(): Boolean {
        val h = SetupBridge.streamPadHolds()
        if (h != padForBand) {
            padForBand = h
            if (h && keys != null) { pad.clear(); try { sendPad() } catch (_: Throwable) {} }
        }
        return h
    }

    private fun overlayKey(code: Int): Boolean = when (screen) {
        Screen.PAIRING -> pairing.pad(code)
        Screen.QUALITY -> qualityView?.pad(code) == true
        Screen.TEST -> testView?.pad(code) == true
        Screen.PROBLEM, Screen.CONNECTING, Screen.PIN -> when (code) {
            KeyEvent.KEYCODE_BUTTON_A -> { if (screen == Screen.PROBLEM) retryNow(byHand = true); true }
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
        if (bandHolds()) {                                   // the hat as the D-pad for the bottom screen's Stream settings
            bandPadNames.axes(hx, hy, 0f, 0f).forEach { SetupBridge.streamPad?.invoke(it) }
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
        /** the wait for /v1/info at a pairing code's address (a VPN's first packet can be slow) */
        const val PAIR_INFO_MS = 2_500
        /** a zoomed window fills this share of the screen at most, and is magnified at most [ZOOM_MAX] x */
        const val ZOOM_FILL = 0.92f
        const val ZOOM_MAX = 3f
        /** Desktop's half of Test connection: its answer within this time from the ask */
        const val PC_TEST_WAIT_MS = 25_000L
        /** the test stream must be up by then */
        const val TEST_START_MS = 20_000L
        /** Sunshine's RTSP port from its base (http) port */
        const val RTSP_OFFSET = 21
        /** Backspaces before each box is typed (the box may hold what the player started) */
        const val CLEAR_KEYS = 16
        /** presses closer than this are one Retry (Desktop's answer takes a moment to lift the stop) */
        const val WOW_RETRY_GAP_MS = 4_000L
        private val SIGN_IN_LINES = setOf(StreamCurtain.SIGN_IN, StreamCurtain.NEEDS_YOU, StreamCurtain.UPDATE, StreamCurtain.CRASHED,
            StreamCurtain.CONNECT_FAILED)
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
