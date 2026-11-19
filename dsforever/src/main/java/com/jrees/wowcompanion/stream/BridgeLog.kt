package com.jrees.wowcompanion.stream

import android.util.Log
import com.jrees.wowcompanion.LogSource
import com.jrees.wowcompanion.Wcb1Assembler
import org.json.JSONObject

/**
 * BridgeLog: the add-on's documents from the PC (Streaming, S2). DS Forever Desktop's bridge tails the PC's General.log and sends
 * the add-on's documents over a token-authenticated WebSocket (docs/STREAM_BRIDGE_PROTOCOL.md), so every room reads exactly what it
 * reads in Local:
 *  - "doc": a document the bridge already joined (its own port of the WCB1 reassembly) -> parsed, the game's clock as "_gt" (the
 *    PC's clock moved onto the Thor's: [PcClock], issue #414);
 *  - "lines": raw WCB1 lines (the draft framing) -> the SAME [Wcb1Assembler] as the local reader;
 *  - "gap" / "log" (restart, switch) / "status": the status line, as LogTail says them.
 *
 * One thread: dial -> hello with the token -> messages until the connection drops -> wait (1, 2, 4, 8, then 15 s) -> again. An
 * unknown token or a removed device stops it for good ([onAuthLost]: the app asks to pair again).
 */
class BridgeLog(
    private val address: String,
    private val port: Int,
    private val tls: Boolean,
    private val fingerprint: String,
    private val token: String,
    private val deviceId: String,
    private val dialer: BridgeDialer,
    private val onMessage: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onAuthLost: (code: String) -> Unit = {},
    /** WoW's state on the PC as Desktop reports it (S3's "wow" message, the welcome's wow.state, a focus_result while it starts) */
    private val onWow: (state: String, why: String) -> Unit = { _, _ -> },
    /** all of WoW and Battle.net on the PC (Desktop 0.2.1's fields; an older Desktop gives the state alone): the stream's curtain */
    private val onPc: (PcWow) -> Unit = {},
    /** the bridge answered (a welcome): the PC's home-network addresses it reported (Wi-Fi first next time), its Tailscale ones and its
     *  Sunshine ports (the user, 2026-11-11: streaming away fixes) */
    private val onWelcome: (BridgeMsg.Welcome) -> Unit = {},
    /** Desktop's answer before the saved login is typed (null = do not type) */
    private val onBnetReady: (BnetFormAt?) -> Unit = {},
    /** the saved login (email, password), for WoW's login.txt on the PC */
    private val savedLogin: () -> Pair<String, String>? = { null },
    /** the game account the PC's WoW remembers, sent with the saved login (null = not said) */
    private val gameAccount: () -> BridgeProtocol.GameAccount? = { null },
    /** the stream's mode (width, height, fps) for Desktop's virtual screen on a headless PC; null = not said */
    private val streamMode: () -> Triple<Int, Int, Int>? = { null },
    /** Desktop 0.2.32: its Sunshine's ports again (it moved) or its answer to [askHostCheck] */
    private val onSunshine: (BridgeMsg.Sunshine) -> Unit = {},
    /** Desktop 0.2.32: its answer to [askPcTest] (Test connection) */
    private val onPcTest: (BridgeMsg.PcTest) -> Unit = {},
    /** settings follow (2026-11-18): the Thor's kept settings folder (SvKeep / SvSync); null = no sync */
    private val svDir: () -> java.io.File? = { null },
    /** the waits between tries (the tests pass a fake that also ends the loop) */
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    /** the Thor's clock on the documents' `_gt` scale ([PcClock]; the tests pass a fixed one) */
    wall: () -> Long = { com.jrees.wowcompanion.GameInput.wallYearMs() },
    /** feat/pause-power: Desktop's answers to [endSession] */
    private val onSessionEnd: (BridgeMsg.SessionEnd) -> Unit = {},
    /** the Thor's add-ons for the PC (2026-11-18): the "addons_want" to send at each welcome (built off this thread; null = none) */
    private val addonsWant: (() -> JSONObject?)? = null,
    /** the game's AddOns folder and the imported add-ons' names, for Desktop's "addon_file_get" (null = no game folder) */
    private val importedFiles: () -> Pair<java.io.File?, Set<String>> = { null to emptySet() },
    /** this Thor's own UI scale lines for the hello (the user's ruling 2026-11-18; null = none: the PC's stays) */
    private val uiScale: () -> Map<String, String>? = { null },
    /** Polestar progress follows (the user's pick 2026-11-18; Desktop 0.2.42): the Thor's game folder for PsSync; null = no sync */
    private val polestar: () -> com.jrees.wowcompanion.PsSync.Ctx? = { null },
) : LogSource {
    /** issue #414: the PC's stamps onto the Thor's clock (a PC in another time zone was never "current") */
    val clock = PcClock(wall)
    private var clockSaid = 0L
    private val docs = Wcb1Assembler({ o -> if (o.has("_gt")) o.put("_gt", stamp(o.optLong("_gt", -1L))); onMessage(o) }, onStatus)
    @Volatile private var running = false
    @Volatile private var conn: BridgeConn? = null
    private var thread: Thread? = null
    @Volatile private var welcomed = false
    private var bridgeGaps = 0
    /** connections made (status / tests) */
    @Volatile var sessions = 0
        private set

    override val kind get() = "bridge"
    override fun gapCount() = docs.gaps + bridgeGaps

    override fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "bridgelog").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        dropDeskLink()
        try { conn?.close(1000) } catch (_: Throwable) {}
        thread?.interrupt()
    }

    /** "focus WoW" (sent when the stream starts and after a reconnect); false = no connection right now */
    fun focusWow(): Boolean = try { conn?.send(BridgeProtocol.focus()); conn != null } catch (_: Throwable) { false }

    /** "is Battle.net's sign-in in front, and where is its form?" (StreamSignIn); false = no connection right now */
    fun askBnetReady(): Boolean = try { conn?.send(BridgeProtocol.bnetReady()); conn != null } catch (_: Throwable) { false }

    /** "check your streaming host" (RTSP timed out twice: [HostHeal]); false = no connection right now */
    fun askHostCheck(): Boolean = try { conn?.send(BridgeProtocol.hostCheck()); conn != null } catch (_: Throwable) { false }

    /** Test connection's PC half ([ConnTest]); false = no connection right now */
    fun askPcTest(): Boolean = try { conn?.send(BridgeProtocol.pcTest()); conn != null } catch (_: Throwable) { false }

    /** the player's Retry: Desktop's own virtual screen tries again (it stops after 3 failures); false = no connection right now */
    fun screenRetry(): Boolean = try { conn?.send(BridgeProtocol.screenRetry()); conn != null } catch (_: Throwable) { false }

    /** the player's Retry after Desktop stopped (a server error, an outdated PC game): WoW once more; false = no connection now */
    fun wowRetry(): Boolean = try { conn?.send(BridgeProtocol.wowRetry()); conn != null } catch (_: Throwable) { false }
    /** the player's Cancel (B) on Desktop's update of the PC game */
    fun patchCancel(): Boolean = try { conn?.send(BridgeProtocol.patchCancel()); conn != null } catch (_: Throwable) { false }
    /** feat/pause-power: the clean disconnect: Desktop closes the PC's WoW and puts the PC back ([SessionEnd]); false = no connection */
    fun endSession(quitAsked: Boolean): Boolean = try { val c = conn; c?.send(BridgeProtocol.endSession(quitAsked)); c != null } catch (_: Throwable) { false }

    /** feat/desktop-clicks: this connection's link for DeskClicks (Desktop clicks the pad), while it is up */
    @Volatile private var deskLink: com.jrees.wowcompanion.DeskClicks.Link? = null
    private fun dropDeskLink() {
        val l = deskLink ?: return
        deskLink = null
        if (com.jrees.wowcompanion.DeskClicks.link === l) com.jrees.wowcompanion.DeskClicks.link = null
    }
    /** the welcome's caps and the PC's WoW as last said (Polestar's files come back when it stops) */
    @Volatile private var caps: List<String> = emptyList()
    @Volatile private var pcWow = ""
    /** Polestar progress follows: "ps_read" (only down a pinned wss connection, only to a Desktop that says polestar_sync) */
    private fun psRead(c: BridgeConn) {
        if (!tls || fingerprint.isEmpty() || "polestar_sync" !in caps) return
        val ctx = runCatching { polestar() }.getOrNull() ?: return
        runCatching { com.jrees.wowcompanion.PsSync.readRequest(ctx)?.let { c.send(it.toString()) } }
    }

    /** the "sv_write" this connection sent (its tables' text: the base once Desktop answers written / same) */
    @Volatile private var svSent: JSONObject? = null
    private fun svLog(line: String) { try { Log.i("WCSETUP", line) } catch (_: Throwable) {} }

    /** how a session ended */
    enum class End { DROPPED, AUTH_LOST, STOPPED }

    private fun loop() {
        var tries = 0
        while (running) {
            val end = try {
                val c = dialer.dial(address, port, tls, fingerprint.ifEmpty { null })
                conn = c
                session(c)
            } catch (t: Throwable) {
                if (running) onStatus("PC: bridge unreachable (${t.message ?: t.javaClass.simpleName})")
                End.DROPPED
            } finally { conn = null; dropDeskLink() }
            if (end != End.DROPPED || !running) break
            // a session that got as far as the welcome worked: the next wait starts short again
            if (welcomed) tries = 0
            welcomed = false
            val wait = DELAYS_MS[tries.coerceAtMost(DELAYS_MS.lastIndex)]
            tries++
            try { sleeper(wait) } catch (_: InterruptedException) { break }
        }
        running = false
    }

    /** one connection: hello, then every message until it ends (internal for the tests) */
    internal fun session(c: BridgeConn): End {
        c.send(BridgeProtocol.hello(deviceId, token, runCatching { uiScale() }.getOrNull()))
        sessions++
        while (running) {
            val text = c.receive() ?: break
            when (val m = BridgeProtocol.parse(text) ?: continue) {
                is BridgeMsg.Welcome -> {
                    welcomed = true
                    caps = m.caps
                    pcWow = m.wowState.ifEmpty { if (m.wowRunning) "running" else "stopped" }
                    // feat/desktop-clicks (the user's pick 2026-11-18, "Desktop clicks on the PC"): a Desktop that clicks the pad, down a
                    // pinned wss connection, takes the stream's pad presses (DeskClicks); else (an older Desktop) the touch over the video
                    dropDeskLink()
                    if (tls && fingerprint.isNotEmpty() && "pad_click" in m.caps) {
                        val l = com.jrees.wowcompanion.DeskClicks.Link { t -> runCatching { c.send(t) }.isSuccess }
                        deskLink = l
                        com.jrees.wowcompanion.DeskClicks.link = l
                    }
                    try { Log.i(TAG, "desktop clicks: " + if (deskLink != null) "on" else "off (the touch path)") } catch (_: Throwable) {}
                    clock.welcome(m.gtNow)
                    onWelcome(m)
                    // a new connection replays the backlog: half-assembled documents of the old one would only glue onto it
                    docs.reset()
                    onStatus("PC: connected to ${m.pcName.ifEmpty { "DS Forever Desktop" }}${if (m.wowRunning) "" else " (WoW isn't running)"}")
                    onWow(m.wowState.ifEmpty { if (m.wowRunning) "running" else "stopped" }, m.wowWhy)
                    onPc(m.pc?.let { if (it.state.isEmpty()) it.copy(state = if (m.wowRunning) "running" else "stopped") else it }
                        ?: PcWow(state = m.wowState.ifEmpty { if (m.wowRunning) "running" else "stopped" }, why = m.wowWhy))
                    sendLogin(c)
                    // the PC game gets Desktop's add-on set BEFORE it is started (never while it runs: its files are read at the start)
                    if (!m.wowRunning) c.send(BridgeProtocol.addonsInstall())
                    // settings follow (2026-11-18): our tables to the PC BEFORE its WoW starts (never while it runs: it reads them at login)
                    if (!m.wowRunning) svDir()?.let { d -> runCatching { svSent = null; c.send(com.jrees.wowcompanion.SvSync.readRequest(d).toString()) } }
                    // Polestar progress follows (2026-11-18): its files both ways BEFORE the PC's WoW starts
                    if (!m.wowRunning) psRead(c)
                    // the Thor's add-ons for the PC (the user, 2026-11-18): every connect, WoW running or not (Desktop moves nothing under a
                    // running WoW: it does it at its next start). Built on its own thread: the imported add-ons' files are hashed
                    addonsWant?.let { build -> Thread({
                        runCatching { build()?.let { c.send(it.toString()) } }.onFailure { svLog("pc add-ons: list not sent (${it.javaClass.simpleName})") }
                    }, "addons-want").apply { isDaemon = true; start() } }
                    c.send(BridgeProtocol.focus())
                    streamMode()?.let { (w, h, fps) -> c.send(BridgeProtocol.streamMode(w, h, fps)) }
                }
                is BridgeMsg.Doc -> emit(m)
                is BridgeMsg.Sunshine -> onSunshine(m)
                is BridgeMsg.PcTest -> onPcTest(m)
                is BridgeMsg.BacklogEnd -> clock.backlogEnd()
                is BridgeMsg.Lines -> for (l in m.lines) docs.line(l)
                is BridgeMsg.Gap -> { bridgeGaps++; onStatus("gap at seq ${m.seq} (expected ${m.expected})") }
                is BridgeMsg.Log -> { docs.reset(); onStatus(if (m.event == "switch") "PC: following another WoW log" else "client restarted, resyncing") }
                is BridgeMsg.Status -> if (m.text.isNotEmpty()) onStatus("PC: ${m.text}")
                is BridgeMsg.BnetReady -> {
                    if (m.form == null && m.why.isNotEmpty()) onStatus("PC: ${m.why}")
                    onBnetReady(m.form)
                }
                is BridgeMsg.FocusResult -> {
                    if (m.state.isNotEmpty()) onWow(m.state, m.why)
                    m.pc?.let(onPc)
                    if (!m.ok && m.why.isNotEmpty()) onStatus("PC: ${m.why}")
                }
                is BridgeMsg.Wow -> {
                    // Polestar progress follows: the PC's WoW closed, so its progress comes back to the Thor (and the Thor's goes over)
                    if (m.state == "stopped" && pcWow == "running") psRead(c)
                    if (m.state.isNotEmpty()) pcWow = m.state
                    onWow(m.state, m.why)
                    onPc(m.pc ?: PcWow(m.state, m.why))
                    // Desktop's stop and its game update say themselves on the curtain (a red row with Retry, the bar): no status line
                    // over them (it read "WoW didn't start: Updating the game ...", rewritten every 500 ms)
                    if (m.pc?.patch == null && m.pc?.stopped != true)
                        BridgeProtocol.wowText(m.state, m.why).takeIf { it.isNotEmpty() }?.let { onStatus("PC: $it") }
                }
                is BridgeMsg.SvFiles -> svDir()?.let { d ->
                    val w = runCatching { com.jrees.wowcompanion.SvSync.onFiles(d, m.o) { svLog(it) } }.onFailure { svLog("settings sync: NOT merged (${it.javaClass.simpleName})") }.getOrNull()
                    svSent = w
                    if (w != null) c.send(w.toString())
                }
                is BridgeMsg.SvWritten -> svDir()?.let { d ->
                    runCatching { com.jrees.wowcompanion.SvSync.onWritten(d, m.o, svSent) { svLog(it) } }
                    svSent = null
                }
                is BridgeMsg.SessionEnd -> onSessionEnd(m)
                is BridgeMsg.PadClickResult -> com.jrees.wowcompanion.DeskClicks.onResult(m.o)?.let { com.jrees.wowcompanion.TapBridge.note(it) }
                is BridgeMsg.PsFiles -> runCatching { polestar() }.getOrNull()?.let { ctx ->
                    runCatching { com.jrees.wowcompanion.PsSync.onFiles(ctx, m.o) { svLog(it) }.forEach { c.send(it.toString()) } }
                        .onFailure { svLog("polestar sync: NOT merged (${it.javaClass.simpleName})") }
                }
                is BridgeMsg.PsWritten -> runCatching { polestar() }.getOrNull()?.let { ctx ->
                    runCatching { com.jrees.wowcompanion.PsSync.onWritten(ctx, m.o) { svLog(it) } }
                }
                is BridgeMsg.AddonsPc -> {
                    com.jrees.wowcompanion.PcAddonsWant.statusLine(m.o)?.let { onStatus(it) }
                    if (m.o.optString("state") == "done") svLog("pc add-ons: ${m.o.optString("line").take(300)}")
                }
                is BridgeMsg.AddonFileGet -> runCatching {
                    val (addons, names) = importedFiles()
                    c.send(com.jrees.wowcompanion.PcAddonsWant.serve(m.o, addons, names).toString())
                }
                is BridgeMsg.Unpaired -> { onAuthLost("revoked"); return End.AUTH_LOST }
                // the user's pick 2026-11-17 ("Ask the Thor again"): WoW closed itself on the PC and starts again; the saved login once
                // more, only down this connection and only while the auto sign-in is on (savedLogin is null otherwise)
                is BridgeMsg.LoginAgain -> {
                    val sent = sendLogin(c)
                    try { Log.i(TAG, "PC asked for the login again: " + if (sent) "sent" else "not sent (auto sign-in off, or no pinned connection)") } catch (_: Throwable) {}
                }
                is BridgeMsg.Error -> {
                    try { Log.w(TAG, "bridge error ${m.code}") } catch (_: Throwable) {}
                    if (m.code == "unauthorized" || m.code == "revoked") { onStatus("PC: pair again"); onAuthLost(m.code); return End.AUTH_LOST }
                    onStatus("PC: ${m.code}")
                }
                else -> {}
            }
        }
        if (!running) return End.STOPPED
        return if (c.closeCode == BridgeProtocol.CLOSE_UNAUTHORIZED) { onAuthLost("unauthorized"); End.AUTH_LOST }
        else { onStatus("PC: connection lost, reconnecting"); End.DROPPED }
    }

    /** the saved login (and the game account to remember) for the PC's login.txt: only down a pinned wss connection; true = sent.
     *  Nothing of it is logged */
    private fun sendLogin(c: BridgeConn): Boolean {
        if (!tls || fingerprint.isEmpty()) return false
        val login = savedLogin() ?: return false
        val account = try { gameAccount() } catch (_: Throwable) { null }
        c.send(BridgeProtocol.loginFile(login.first, login.second, account))
        return true
    }

    private fun emit(m: BridgeMsg.Doc) {
        try {
            val o = JSONObject(m.text)
            if (m.gt >= 0) o.put("_gt", stamp(m.gt))
            onMessage(o)
        } catch (t: Throwable) { onStatus("bad json (${m.text.length}b): ${t.message}") }
    }

    /** a PC stamp on the Thor's clock; a new shift is said once in the log */
    private fun stamp(gt: Long): Long {
        val t = clock.map(gt)
        val shift = clock.shiftMs
        if (shift != clockSaid) {
            clockSaid = shift
            try { Log.i(TAG, "PC clock: ${shift / 1000} s from the Thor's (time zone or drift): the documents' stamps moved by it") } catch (_: Throwable) {}
        }
        return t
    }

    /** for the tests: run as start() would, on the calling thread */
    internal fun runHere() { running = true; loop() }

    companion object {
        const val TAG = "DSFSTREAM"
        private val DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
    }
}
