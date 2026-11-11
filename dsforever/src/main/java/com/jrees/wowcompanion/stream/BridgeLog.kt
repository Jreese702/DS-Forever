package com.jrees.wowcompanion.stream

import android.util.Log
import com.jrees.wowcompanion.LogSource
import com.jrees.wowcompanion.Wcb1Assembler
import org.json.JSONObject

/**
 * BridgeLog: the add-on's documents from the PC (Streaming, S2). DS Forever Desktop's bridge tails the PC's General.log and sends
 * the add-on's documents over a token-authenticated WebSocket (docs/STREAM_BRIDGE_PROTOCOL.md), so every room reads exactly what it
 * reads in Local:
 *  - "doc": a document the bridge already joined (its own port of the WCB1 reassembly) -> parsed, the game's clock as "_gt";
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
    /** the waits between tries (the tests pass a fake that also ends the loop) */
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : LogSource {
    private val docs = Wcb1Assembler(onMessage, onStatus)
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
        try { conn?.close(1000) } catch (_: Throwable) {}
        thread?.interrupt()
    }

    /** "focus WoW" (sent when the stream starts and after a reconnect); false = no connection right now */
    fun focusWow(): Boolean = try { conn?.send(BridgeProtocol.focus()); conn != null } catch (_: Throwable) { false }

    /** "is Battle.net's sign-in in front, and where is its form?" (StreamSignIn); false = no connection right now */
    fun askBnetReady(): Boolean = try { conn?.send(BridgeProtocol.bnetReady()); conn != null } catch (_: Throwable) { false }

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
            } finally { conn = null }
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
        c.send(BridgeProtocol.hello(deviceId, token))
        sessions++
        while (running) {
            val text = c.receive() ?: break
            when (val m = BridgeProtocol.parse(text) ?: continue) {
                is BridgeMsg.Welcome -> {
                    welcomed = true
                    onWelcome(m)
                    // a new connection replays the backlog: half-assembled documents of the old one would only glue onto it
                    docs.reset()
                    onStatus("PC: connected to ${m.pcName.ifEmpty { "DS Forever Desktop" }}${if (m.wowRunning) "" else " (WoW isn't running)"}")
                    onWow(m.wowState.ifEmpty { if (m.wowRunning) "running" else "stopped" }, m.wowWhy)
                    onPc(m.pc?.let { if (it.state.isEmpty()) it.copy(state = if (m.wowRunning) "running" else "stopped") else it }
                        ?: PcWow(state = m.wowState.ifEmpty { if (m.wowRunning) "running" else "stopped" }, why = m.wowWhy))
                    if (tls && fingerprint.isNotEmpty()) savedLogin()?.let { c.send(BridgeProtocol.loginFile(it.first, it.second)) }
                    // the PC game gets Desktop's add-on set BEFORE it is started (never while it runs: its files are read at the start)
                    if (!m.wowRunning) c.send(BridgeProtocol.addonsInstall())
                    c.send(BridgeProtocol.focus())
                }
                is BridgeMsg.Doc -> emit(m)
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
                    onWow(m.state, m.why)
                    onPc(m.pc ?: PcWow(m.state, m.why))
                    BridgeProtocol.wowText(m.state, m.why).takeIf { it.isNotEmpty() }?.let { onStatus("PC: $it") }
                }
                is BridgeMsg.Unpaired -> { onAuthLost("revoked"); return End.AUTH_LOST }
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

    private fun emit(m: BridgeMsg.Doc) {
        try {
            val o = JSONObject(m.text)
            if (m.gt >= 0) o.put("_gt", m.gt)
            onMessage(o)
        } catch (t: Throwable) { onStatus("bad json (${m.text.length}b): ${t.message}") }
    }

    /** for the tests: run as start() would, on the calling thread */
    internal fun runHere() { running = true; loop() }

    companion object {
        const val TAG = "DSFSTREAM"
        private val DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
    }
}
