package com.jrees.wowcompanion.stream

import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The bridge's messages (docs/STREAM_BRIDGE_PROTOCOL.md v1, as S3's DS Forever Desktop implements it: BridgeServer.cs): one JSON
 * object per WebSocket text frame, typed by "t". Unknown types and fields are ignored.
 */
/**
 * WoW and Battle.net on the PC as Desktop 0.2.1 reports them (the "wow" message, the welcome's wow, a focus_result): [screen] none |
 * starting | login | signing_in | charselect | world ("" = an older Desktop that does not say); [focused] = WoW is the PC's foreground
 * window (null = not said); [bnet] unknown | not_running | starting | signin_needed | signed_in ("" = not said); [needsYou] = Desktop's
 * one retry is spent; [loginError] = the game's own reason ("308")
 */
data class PcWow(val state: String = "", val why: String = "", val screen: String = "", val focused: Boolean? = null,
                 val bnet: String = "", val needsYou: Boolean = false, val loginError: String = "",
                 /** the client crashed (Desktop 0.2.2): "update" = Battle.net has an update for it, "crash" = anything else; "" = none */
                 val crash: String = "",
                 /** Desktop 0.2.3: Battle.net's window on the streamed screen as shares (x, y, w, h) while the player must use it; empty = none */
                 val show: List<Float> = emptyList(),
                 /** issues #304 / #357: why the taps can't reach WoW on the PC: "elevated" (run as administrator) | "not_filling" (WoW's
                  *  window doesn't fill the streamed monitor); "" = fine, or an older Desktop */
                 val input: String = "",
                 /** Desktop 0.2.31: the game refused the region picked on its own region screen (309) and waits there for another pick */
                 val regionRefused: Boolean = false) {
    /** signed in: at character select or in the world */
    val signedIn get() = screen == "charselect" || screen == "world"

    companion object {
        fun of(o: JSONObject?): PcWow? = o?.let {
            PcWow(it.optString("state"), it.optString("why"), it.optString("screen"),
                if (it.has("focused")) it.optBoolean("focused") else null, it.optString("bnet"), it.optBoolean("needs_you"),
                it.optString("login_error"), it.optString("crash"), rect(it.optString("show")), it.optString("input"),
                it.optString("region") == "refused")
        }

        /** "x,y,w,h" (shares of the screen) -> 4 floats; anything else -> empty */
        fun rect(s: String): List<Float> {
            val v = s.split(',').mapNotNull { it.trim().toFloatOrNull() }
            return if (v.size == 4 && v[2] > 0.01f && v[3] > 0.01f && v.all { it in 0f..1f }) v else emptyList()
        }
    }
}

sealed class BridgeMsg {
    data class Welcome(val hostId: String, val pcName: String, val wowRunning: Boolean, val httpPort: Int, val httpsPort: Int,
                       val wowState: String = "", val wowWhy: String = "", val pc: PcWow? = null,
                       /** the PC's home-network IPv4s (Desktop 0.2.1+: "Wi-Fi first"); empty from an older Desktop */
                       val lan: List<String> = emptyList(),
                       /** the PC's Tailscale addresses (100.64/10, fd7a:115c:a1e0::/48; the user, 2026-11-11: streaming away fixes); empty
                        *  from an older Desktop */
                       val tailscale: List<String> = emptyList(),
                       /** issue #54: whether the PC's Sunshine can make the controller's Xbox pad (ViGEmBus): "vigem" / "missing" /
                        *  "unknown"; empty from an older Desktop */
                       val padDriver: String = "",
                       /** the welcome carries no sunshine.http_port: an older Desktop that never reports its Sunshine ports ([StreamPorts]) */
                       val oldDesktop: Boolean = false,
                       /** Desktop 0.2.27: why streaming can't work on the PC ("other_sunshine" | "other_app" | "not_listening" | "exited";
                        *  "" = fine) and its line for the Thor ("pc busy: another Sunshine"); empty from an older Desktop */
                       val sunProblem: String = "", val sunWhy: String = "",
                       /** issue #414: Desktop's clock when it sent this, on the documents' `gt` scale ([PcClock]); -1 from an older Desktop */
                       val gtNow: Long = -1L,
                       /** Desktop 0.2.32: what the stream's start waits for ("screen" = its own virtual screen being made, "starting" = its
                        *  streaming host starting; "" = go, or an older Desktop) */
                       val sunWait: String = "") : BridgeMsg()
    /** Desktop starting WoW on the PC (S3): "starting" | "running" | "stopped" | "start_failed"; [pc] = all of it (0.2.1) */
    data class Wow(val state: String, val why: String, val pc: PcWow? = null) : BridgeMsg()
    data class Error(val code: String, val detail: String = "") : BridgeMsg()
    /** one add-on document, already joined by the bridge (its own WCB1 reassembly of the PC's General.log) */
    data class Doc(val seq: Int, val gt: Long, val text: String) : BridgeMsg()
    /** raw General.log lines (the S2 draft's framing; still understood, so a bridge may send lines instead of documents) */
    data class Lines(val lines: List<String>) : BridgeMsg()
    data class Gap(val seq: Int, val expected: Int) : BridgeMsg()
    data class Status(val text: String) : BridgeMsg()
    /** the PC's log restarted ("restart": WoW launched and emptied it) or another log is followed ("switch") */
    data class Log(val event: String) : BridgeMsg()
    data class BacklogEnd(val count: Int) : BridgeMsg()
    data class Stream(val state: String) : BridgeMsg()
    /** Desktop 0.2.32: its Sunshine's ports and reason again ("sunshine": it moved to a new base; "host_check_result": the answer to
     *  [BridgeProtocol.hostCheck], [state] ok | restarting | failed | streaming, "" for a plain "sunshine") */
    data class Sunshine(val state: String, val httpPort: Int, val httpsPort: Int, val problem: String, val why: String, val answer: Boolean,
                        /** as [Welcome.sunWait] */ val wait: String = "") : BridgeMsg() {
        val report get() = StreamPorts.Report(true, httpPort, httpsPort)
    }
    /** Desktop's answer to [BridgeProtocol.bnetReady]: [form] = Battle.net's sign-in in front and found; null = do not type */
    data class BnetReady(val form: BnetFormAt?, val why: String) : BridgeMsg()
    data class FocusResult(val ok: Boolean, val why: String, val state: String = "", val pc: PcWow? = null) : BridgeMsg()
    data class PairChallenge(val nonce: String, val attemptsLeft: Int, val locked: Boolean) : BridgeMsg()
    data class PairOk(val token: String, val deviceId: String, val hostId: String, val pcName: String, val moonlightPin: String,
                      val httpPort: Int, val httpsPort: Int) : BridgeMsg()
    data class PairError(val error: String, val attemptsLeft: Int) : BridgeMsg()
    data class PairStream(val ok: Boolean, val error: String) : BridgeMsg()
    /** Desktop 0.2.32's answer to [BridgeProtocol.pcTest]: its own checks in order ([steps]: id, state pass|fail|skip|not_run, line,
     *  fix), the screen a stream gets ([screen] own | real | none, its top rate [screenHz], 0 = unknown) and the ViGEmBus word [pad] */
    data class PcTest(val steps: List<PcStep>, val screen: String, val screenLine: String, val screenHz: Int, val pad: String) : BridgeMsg() {
        data class PcStep(val id: String, val state: String, val line: String, val fix: String)
        /** the first failed step, null = none */
        val failed get() = steps.firstOrNull { it.state == "fail" }
        /** one line per step for the report ("ports ok (all 6 ready)") */
        val lines get() = steps.map { "${it.id} ${if (it.state == "fail") "FAILED" else it.state}" + (if (it.line.isNotEmpty()) " (${it.line})" else "") }
    }
    object Unpaired : BridgeMsg()
    /** Desktop 0.2.31 starts WoW again after a client of the last login.txt closed itself: send the saved login once more */
    object LoginAgain : BridgeMsg()
    data class Unknown(val t: String) : BridgeMsg()
}

object BridgeProtocol {
    const val PROTO = 1
    const val PATH = "/v1/ws"
    const val DEFAULT_PORT = 48210
    /** the WebSocket close code for an unknown device / token */
    const val CLOSE_UNAUTHORIZED = 4401

    /** the add-on set this APK carries (companion/manifest.txt "set N"; 0 = unknown), sent on hello so Desktop 0.2.28+ checks for its
     *  own update at once when the Thor is ahead (the user, 2026-11-13: "we need this shit to have an auto update feature") */
    @Volatile var addonSet: Int = 0

    fun hello(deviceId: String, token: String): String =
        JSONObject().put("t", "hello").put("proto", PROTO).put("device_id", deviceId).put("token", token)
            .apply { if (addonSet > 0) put("addon_set", addonSet) }.toString()

    fun pairHello(deviceId: String, name: String): String =
        JSONObject().put("t", "pair_hello").put("proto", PROTO).put("device_id", deviceId).put("name", name).put("stream", true).toString()

    fun pairProof(proof: String): String = JSONObject().put("t", "pair_proof").put("proof", proof).toString()

    /**
     * The code never crosses the network: proof = HMAC-SHA256(key = the 6-digit code, "dsf-pair-v1|" + nonce + "|" + deviceId), lower
     * case hex (PairingCodes.Proof on the PC).
     */
    fun proof(code: String, nonce: String, deviceId: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(code.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
        return Hex.of(mac.doFinal("dsf-pair-v1|$nonce|$deviceId".toByteArray(Charsets.UTF_8))).lowercase()
    }

    fun focus(): String = JSONObject().put("t", "focus").toString()
    /** the stream's mode (Desktop 0.2.31): a PC with no screen on makes its virtual screen at it */
    fun streamMode(w: Int, h: Int, fps: Int): String = JSONObject().put("t", "stream_mode").put("w", w).put("h", h).put("fps", fps).toString()
    /** 2026-11-04 (the stream test: the PC ran add-on set 239 against the Thor's 251, "a TOOON of Lua errors"): Desktop installs its
     *  bundled add-on set into the PC game (it only did at its own start or a button); it answers {"t":"addons", ..., "result"} */
    fun addonsInstall(): String = JSONObject().put("t", "addons_install").toString()
    fun bnetReady(): String = JSONObject().put("t", "bnet_ready").toString()
    /** Desktop 0.2.32: RTSP timed out twice with HTTP fine: check (and heal) the streaming host; answered by host_check_result */
    fun hostCheck(): String = JSONObject().put("t", "host_check").toString()
    /** Desktop 0.2.32: the PC's half of Test connection (ports, RTSP, encoder, screen, controller); answered by pc_test_result */
    fun pcTest(): String = JSONObject().put("t", "pc_test").toString()
    /** Desktop 0.2.32: the player's Retry by hand: Desktop's own virtual screen starts its tries over (it gives up after 3) */
    fun screenRetry(): String = JSONObject().put("t", "screen_retry").toString()
    /**
     * The game account the PC's WoW remembers (the user's pick 2026-11-17, "Yes, remember it"): [ask] = Settings > Game account "Ask
     * each time" (nothing is sent to remember); [list] = the Thor's Config.wtf accountList with its "!" pick
     * ([com.jrees.wowcompanion.GameConfigWtf.rememberedAccountList]), null = none here. An account name: never logged.
     */
    data class GameAccount(val ask: Boolean, val list: String? = null)

    /** the saved login for WoW's login.txt on the PC: only down the pinned wss connection, never logged; with the game account to
     *  remember on the PC ([GameAccount]: "ask" never carries one) */
    fun loginFile(email: String, password: String, account: GameAccount? = null): String =
        JSONObject().put("t", "login_file").put("email", email).put("password", password).apply {
            if (account != null) {
                put("game_account", if (account.ask) "ask" else "remember")
                if (!account.ask && !account.list.isNullOrEmpty()) put("account_list", account.list)
            }
        }.toString()

    /** "x,y" (shares) -> a point; anything else -> null */
    fun point(s: String): Pair<Float, Float>? {
        val v = s.split(',').mapNotNull { it.trim().toFloatOrNull() }
        return if (v.size == 2 && v.all { it in 0f..1f }) v[0] to v[1] else null
    }
    fun unpair(): String = JSONObject().put("t", "unpair").toString()

    private fun strings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.optString(i) }.filter { s -> s.isNotEmpty() } } ?: emptyList()

    private fun JSONObject.err() = optString("error", optString("code", "error"))

    /** one frame from the bridge; null = not a JSON object (ignored, as unknown types are) */
    fun parse(text: String): BridgeMsg? {
        val o = try { JSONObject(text) } catch (_: Throwable) { return null }
        val sun = o.optJSONObject("sunshine")
        return when (val t = o.optString("t")) {
            "welcome" -> BridgeMsg.Welcome(o.optString("host_id"), o.optString("pc_name"),
                o.optJSONObject("wow")?.optBoolean("running") ?: false, sun?.optInt("http_port", 0) ?: 0, sun?.optInt("https_port", 0) ?: 0,
                o.optJSONObject("wow")?.optString("state").orEmpty(), o.optJSONObject("wow")?.optString("why").orEmpty(),
                PcWow.of(o.optJSONObject("wow")),
                strings(o.optJSONArray("lan")), strings(o.optJSONArray("tailscale")), o.optString("pad_driver"),
                sun?.has("http_port") != true, sun?.optString("problem").orEmpty(), sun?.optString("why").orEmpty(),
                o.optLong("gt_now", -1L), sun?.optString("wait").orEmpty())
            "wow" -> BridgeMsg.Wow(o.optString("state"), o.optString("why"), PcWow.of(o))
            "error" -> BridgeMsg.Error(o.err(), o.optString("detail"))
            "doc" -> BridgeMsg.Doc(o.optInt("seq", -1), o.optLong("gt", -1L), o.optString("doc"))
            "lines" -> BridgeMsg.Lines((o.optJSONArray("l") ?: JSONArray()).let { a -> List(a.length()) { a.optString(it) } })
            "gap" -> BridgeMsg.Gap(o.optInt("seq", -1), o.optInt("expected", -1))
            "status" -> BridgeMsg.Status(o.optString("text"))
            // Desktop's add-on answer: its words, as a status line
            "addons" -> BridgeMsg.Status("add-ons: " + o.optString("result").ifEmpty { o.optString("text") })
            "log" -> BridgeMsg.Log(o.optString("event"))
            "reset" -> BridgeMsg.Log("restart")
            "backlog_end" -> BridgeMsg.BacklogEnd(o.optInt("count", 0))
            "stream" -> BridgeMsg.Stream(o.optString("state"))
            "sunshine", "host_check_result" -> BridgeMsg.Sunshine(o.optString("state"), sun?.optInt("http_port", 0) ?: 0,
                sun?.optInt("https_port", 0) ?: 0, sun?.optString("problem").orEmpty(), sun?.optString("why").orEmpty(), t == "host_check_result",
                sun?.optString("wait").orEmpty())
            "bnet_ready_result" -> {
                val pass = point(o.optString("password")); val button = point(o.optString("button"))
                BridgeMsg.BnetReady(if (o.optBoolean("ok") && pass != null && button != null) BnetFormAt(point(o.optString("email")), pass, button) else null,
                    o.optString("why"))
            }
            "focus_result" -> BridgeMsg.FocusResult(o.optBoolean("ok"), o.optString("why"), o.optString("state"),
                if (o.has("screen")) PcWow.of(o) else null)
            "pair_challenge" -> BridgeMsg.PairChallenge(o.optString("nonce"), o.optInt("attempts_left", -1), o.optBoolean("locked"))
            "pair_ok" -> BridgeMsg.PairOk(o.optString("token"), o.optString("device_id"), o.optString("host_id"), o.optString("pc_name"),
                o.optString("moonlight_pin"), sun?.optInt("http_port", 0) ?: 0, sun?.optInt("https_port", 0) ?: 0)
            "pair_error" -> BridgeMsg.PairError(o.err(), o.optInt("attempts_left", -1))
            "pair_stream" -> BridgeMsg.PairStream(o.optBoolean("ok"), o.optString("error"))
            "unpaired" -> BridgeMsg.Unpaired
            "pc_test_result" -> BridgeMsg.PcTest((o.optJSONArray("steps") ?: JSONArray()).let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                    BridgeMsg.PcTest.PcStep(it.optString("id"), it.optString("state"), it.optString("line"), it.optString("fix")) }
            }, o.optString("screen"), o.optString("screen_line"), o.optInt("screen_hz", 0), o.optString("pad"))
            "login_again" -> BridgeMsg.LoginAgain
            else -> BridgeMsg.Unknown(t)
        }
    }

    /** what the top screen says for WoW's state on the PC ("" = nothing to show) */
    fun wowText(state: String, why: String): String = when (state) {
        "starting" -> "Starting WoW on your PC…"
        "start_failed" -> "WoW didn't start${if (why.isNotEmpty()) ": $why" else ""}. If Battle.net is waiting for you to sign in, sign in on the PC."
        else -> ""
    }

    /** a token as the bridge makes them: 32 random bytes, base64url without padding */
    fun validToken(t: String): Boolean = t.length == 43 && t.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
