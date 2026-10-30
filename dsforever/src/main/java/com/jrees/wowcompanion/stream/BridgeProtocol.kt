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
                 val crash: String = "") {
    /** signed in: at character select or in the world */
    val signedIn get() = screen == "charselect" || screen == "world"

    companion object {
        fun of(o: JSONObject?): PcWow? = o?.let {
            PcWow(it.optString("state"), it.optString("why"), it.optString("screen"),
                if (it.has("focused")) it.optBoolean("focused") else null, it.optString("bnet"), it.optBoolean("needs_you"),
                it.optString("login_error"), it.optString("crash"))
        }
    }
}

sealed class BridgeMsg {
    data class Welcome(val hostId: String, val pcName: String, val wowRunning: Boolean, val httpPort: Int, val httpsPort: Int,
                       val wowState: String = "", val wowWhy: String = "", val pc: PcWow? = null,
                       /** the PC's home-network IPv4s (Desktop 0.2.1+: "Wi-Fi first"); empty from an older Desktop */
                       val lan: List<String> = emptyList()) : BridgeMsg()
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
    data class FocusResult(val ok: Boolean, val why: String, val state: String = "", val pc: PcWow? = null) : BridgeMsg()
    data class PairChallenge(val nonce: String, val attemptsLeft: Int, val locked: Boolean) : BridgeMsg()
    data class PairOk(val token: String, val deviceId: String, val hostId: String, val pcName: String, val moonlightPin: String,
                      val httpPort: Int, val httpsPort: Int) : BridgeMsg()
    data class PairError(val error: String, val attemptsLeft: Int) : BridgeMsg()
    data class PairStream(val ok: Boolean, val error: String) : BridgeMsg()
    object Unpaired : BridgeMsg()
    data class Unknown(val t: String) : BridgeMsg()
}

object BridgeProtocol {
    const val PROTO = 1
    const val PATH = "/v1/ws"
    const val DEFAULT_PORT = 48210
    /** the WebSocket close code for an unknown device / token */
    const val CLOSE_UNAUTHORIZED = 4401

    fun hello(deviceId: String, token: String): String =
        JSONObject().put("t", "hello").put("proto", PROTO).put("device_id", deviceId).put("token", token).toString()

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
    fun unpair(): String = JSONObject().put("t", "unpair").toString()

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
                o.optJSONArray("lan")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } } ?: emptyList())
            "wow" -> BridgeMsg.Wow(o.optString("state"), o.optString("why"), PcWow.of(o))
            "error" -> BridgeMsg.Error(o.err(), o.optString("detail"))
            "doc" -> BridgeMsg.Doc(o.optInt("seq", -1), o.optLong("gt", -1L), o.optString("doc"))
            "lines" -> BridgeMsg.Lines((o.optJSONArray("l") ?: JSONArray()).let { a -> List(a.length()) { a.optString(it) } })
            "gap" -> BridgeMsg.Gap(o.optInt("seq", -1), o.optInt("expected", -1))
            "status" -> BridgeMsg.Status(o.optString("text"))
            "log" -> BridgeMsg.Log(o.optString("event"))
            "reset" -> BridgeMsg.Log("restart")
            "backlog_end" -> BridgeMsg.BacklogEnd(o.optInt("count", 0))
            "stream" -> BridgeMsg.Stream(o.optString("state"))
            "focus_result" -> BridgeMsg.FocusResult(o.optBoolean("ok"), o.optString("why"), o.optString("state"),
                if (o.has("screen")) PcWow.of(o) else null)
            "pair_challenge" -> BridgeMsg.PairChallenge(o.optString("nonce"), o.optInt("attempts_left", -1), o.optBoolean("locked"))
            "pair_ok" -> BridgeMsg.PairOk(o.optString("token"), o.optString("device_id"), o.optString("host_id"), o.optString("pc_name"),
                o.optString("moonlight_pin"), sun?.optInt("http_port", 0) ?: 0, sun?.optInt("https_port", 0) ?: 0)
            "pair_error" -> BridgeMsg.PairError(o.err(), o.optInt("attempts_left", -1))
            "pair_stream" -> BridgeMsg.PairStream(o.optBoolean("ok"), o.optString("error"))
            "unpaired" -> BridgeMsg.Unpaired
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
