package com.jrees.wowcompanion.stream

/**
 * "TEST CONNECTION" (the user's pick 2026-11-17: "Yes, build it"). Stream settings' Test connection runs each step in order with a
 * pass / fail line, and stops at the first failure with a one-line reason and a suggested fix: PC found (the bridge), paired, the
 * web ports (serverinfo over HTTP and HTTPS), the RTSP port, a 3-second test stream (video frames decoded), the controller (the PC's
 * ViGEmBus), the screen (our own virtual screen / a real one) and 120 fps. The last result goes into the problem report
 * ([summary]: no names, IPs cut to a.b.x.x). Pure: every probe is passed in ([run]'s check), so the tests drive it with fakes.
 */
object ConnTest {
    enum class Step(val label: String) {
        PC_FOUND("PC found"), PAIRED("Paired"), WEB_PORTS("Web ports"), RTSP("RTSP handshake"), TEST_STREAM("Test stream"),
        CONTROLLER("Controller"), SCREEN("Screen"), FPS120("120 fps"),
    }

    enum class State { PASS, FAIL, SKIP, NOT_RUN }

    /** one step's answer: [line] = what was seen (short), [fix] = what to do (failures only) */
    data class Result(val step: Step, val state: State, val line: String = "", val fix: String = "")

    fun pass(s: Step, line: String = "") = Result(s, State.PASS, line)
    fun fail(s: Step, line: String, fix: String) = Result(s, State.FAIL, line, fix)
    fun skip(s: Step, line: String) = Result(s, State.SKIP, line)

    /**
     * Every step in order through [check]; a FAIL stops the run (the rest NOT_RUN), a SKIP does not. A check that throws is that step's
     * failure. [onStep] hears each result as it comes; [cancelled] is asked before each step (the rest then NOT_RUN).
     */
    fun run(check: (Step) -> Result, onStep: (Result) -> Unit = {}, cancelled: () -> Boolean = { false }): List<Result> {
        val out = ArrayList<Result>()
        var stopped = false
        for (s in Step.values()) {
            if (stopped || cancelled()) { out += Result(s, State.NOT_RUN); continue }
            val r = try { check(s).copy(step = s) } catch (t: Throwable) {
                fail(s, "Check failed (${t.message ?: t.javaClass.simpleName})", "Run the test again.")
            }
            out += r
            onStep(r)
            if (r.state == State.FAIL) stopped = true
        }
        return out
    }

    /** the first failure, null = none */
    fun firstFailure(rs: List<Result>): Result? = rs.firstOrNull { it.state == State.FAIL }

    // ---------------------------------------------------------------- the steps' judgements

    const val FIX_RESTART = "Restart DS Forever Desktop on the PC."
    const val FIX_PAIR = "Pair again with the code in DS Forever Desktop."
    const val FIX_60 = "Stream at 60 fps."
    const val FIX_VIGEM = "Install ViGEmBus on the PC (github.com/nefarius/ViGEmBus)."
    const val FIX_SCREEN = "Turn on a screen, or install the virtual screen from DS Forever Desktop."
    const val NOT_CHECKED = "Not checked by the PC"

    /** [welcomed] = the bridge connection got Desktop's welcome; [reason] = Diagnosis's word when it didn't; [hasBridge] = paired with Desktop */
    fun pcFound(hasBridge: Boolean, welcomed: Boolean, reason: Diagnosis.Reason?): Result = when {
        !hasBridge -> skip(Step.PC_FOUND, "Sunshine only, no DS Forever Desktop")
        welcomed -> pass(Step.PC_FOUND, "Bridge connected")
        reason == null || reason == Diagnosis.Reason.OK || reason == Diagnosis.Reason.BRIDGE_ONLY_DOWN ->
            fail(Step.PC_FOUND, "The bridge didn't answer", FIX_RESTART)
        else -> fail(Step.PC_FOUND, reason.title, reason.detail)
    }

    /** [authLost] = the bridge refused our token (4401 / unpaired) */
    fun paired(hasBridge: Boolean, welcomed: Boolean, authLost: Boolean): Result = when {
        authLost -> fail(Step.PAIRED, "The PC doesn't know this Thor", FIX_PAIR)
        !hasBridge -> skip(Step.PAIRED, "Checked with the web ports")
        welcomed -> pass(Step.PAIRED)
        else -> fail(Step.PAIRED, "No answer from the bridge", FIX_RESTART)
    }

    /** serverinfo's outcome on one port: null = it answered, else why not ("401" = it refused our certificate) */
    fun webPorts(http: Int, https: Int, httpErr: String?, httpsErr: String?, pairStatus: String?): Result = when {
        http <= 0 -> fail(Step.WEB_PORTS, "The PC hasn't said its streaming port", FIX_RESTART)
        httpErr != null -> fail(Step.WEB_PORTS, "No answer on port $http ($httpErr)", FIX_RESTART)
        httpsErr == "401" -> fail(Step.WEB_PORTS, "The streaming host doesn't know this Thor", FIX_PAIR)
        httpsErr != null -> fail(Step.WEB_PORTS, "No answer on port $https ($httpsErr)", FIX_RESTART)
        pairStatus != "1" -> fail(Step.WEB_PORTS, "The streaming host doesn't know this Thor", FIX_PAIR)
        else -> pass(Step.WEB_PORTS, "$http, $https")
    }

    /** a TCP connect to Sunshine's RTSP port (base + 21); [live] = a stream runs now (it got past RTSP) */
    fun rtsp(port: Int, answer: Diagnosis.Port, live: Boolean): Result = when {
        live -> pass(Step.RTSP, "Stream running")
        answer == Diagnosis.Port.OPEN -> pass(Step.RTSP, "Port $port")
        answer == Diagnosis.Port.REFUSED -> fail(Step.RTSP, "Refused on port $port", FIX_RESTART)
        else -> fail(Step.RTSP, "No answer on port $port", FIX_RESTART)
    }

    /**
     * The 3-second test stream: [started] = Moonlight's core came up; [error] / [failedStage] = how it ended before that; [frames] =
     * video frames decoded in the 3 s; [pcWhy] = the PC test's own failed line, if any (an encoder missing, a port refused)
     */
    fun stream(started: Boolean, error: Int, failedStage: String, frames: Long, httpPort: Int, pcWhy: String = ""): Result = when {
        !started && failedStage.contains("RTSP", ignoreCase = true) ->
            fail(Step.TEST_STREAM, "RTSP handshake failed (error $error)", FIX_RESTART)
        !started -> fail(Step.TEST_STREAM, listOf(if (failedStage.isNotEmpty()) "Failed at $failedStage (error $error)" else "Didn't start (error $error)",
            pcWhy).filter { it.isNotEmpty() }.joinToString(": "), if (pcWhy.isNotEmpty()) FIX_RESTART else "Run the test again.")
        frames <= 0 -> fail(Step.TEST_STREAM, "No video decoded", "Check the PC's firewall (${StreamSession.udpPorts(httpPort)}).")
        else -> pass(Step.TEST_STREAM, "Frames decoded: $frames")
    }

    /** the PC's ViGEmBus word ("vigem" | "missing" | "unknown"; "" = not said) */
    fun controller(pad: String): Result = when (pad) {
        "vigem" -> pass(Step.CONTROLLER, "ViGEmBus")
        "missing" -> fail(Step.CONTROLLER, "ViGEmBus missing on the PC", FIX_VIGEM)
        else -> skip(Step.CONTROLLER, NOT_CHECKED)
    }

    /** the PC test's screen word: "own" (our virtual screen) | "real" | "none"; [line] = its line; "" = no answer */
    fun screen(word: String, line: String): Result = when (word) {
        "own" -> pass(Step.SCREEN, line.ifEmpty { "Own virtual screen" })
        "real" -> pass(Step.SCREEN, line.ifEmpty { "Real screen" })
        "none" -> fail(Step.SCREEN, line.ifEmpty { "No screen on the PC" }, FIX_SCREEN)
        else -> skip(Step.SCREEN, NOT_CHECKED)
    }

    /** [wantFps] = the stream quality's rate; [thorCan] = this Thor shows 120 at that size; [pcHz] = the streamed screen's top rate (0 = not said) */
    fun fps120(wantFps: Int, thorCan: Boolean, pcHz: Int): Result {
        fun no(line: String) = if (wantFps >= 120) fail(Step.FPS120, line, FIX_60) else skip(Step.FPS120, line)
        return when {
            !thorCan -> no("Not on this Thor at this size")
            pcHz in 1..119 -> no("The PC's screen tops out at $pcHz Hz")
            pcHz <= 0 -> skip(Step.FPS120, if (wantFps >= 120) "Thor yes, PC not checked" else "Thor yes")
            else -> pass(Step.FPS120, if (wantFps >= 120) "On" else "Offered")
        }
    }

    // ---------------------------------------------------------------- the report

    private val IPV4 = Regex("""\b(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}\b""")
    private val IPV6 = Regex("""\b([0-9a-fA-F]{1,4}):(?:[0-9a-fA-F]{0,4}:){2,6}[0-9a-fA-F]{0,4}\b(?:%[0-9A-Za-z]+)?""")   // 3+ colons: never a time
    /** the short forms with "::" and two colons or fewer (fe80::1, ::1, fe80::1%wlan0; reviewers 2026-11-17): a time never has "::" */
    private val IPV6_SHORT = Regex("""(?<![0-9A-Za-z:.])((?:[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4}){0,6})?)::((?:[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4}){0,6})?)(?:%[0-9A-Za-z]+)?(?![0-9A-Za-z:])""")

    /** IPv4 cut to its first two parts (a.b.x.x), IPv6 to its first group (fe80:x; ::1 -> ::x) */
    fun scrub(s: String): String {
        val v4 = IPV4.replace(s) { "${it.groupValues[1]}.${it.groupValues[2]}.x.x" }
        val v6 = IPV6.replace(v4) { "${it.groupValues[1]}:x" }
        return IPV6_SHORT.replace(v6) { m ->
            val a = m.groupValues[1]; val b = m.groupValues[2]
            when {
                a.isNotEmpty() -> a.substringBefore(':') + ":x"
                b.isNotEmpty() -> "::x"
                else -> m.value
            }
        }
    }

    /** lines whose words come before a variable part with no number or bracket ("Failed at" + " video stream establishment") */
    val HEADS = listOf("Failed at")

    /**
     * A line split into the words to translate and the rest kept as it is: a known [HEADS] prefix, else the words before the first
     * number or bracket ("No answer on port" + " 53010"), else the whole line (rest ""). Reviewers 2026-11-17: "Failed at <stage>"
     * had no number before its bracket, so its head was never found in the translations.
     */
    fun splitHead(line: String): Pair<String, String> {
        HEADS.firstOrNull { line.startsWith("$it ") }?.let { return it to line.substring(it.length) }
        val cut = line.indexOfFirst { it.isDigit() || it == '(' }
        if (cut <= 0) return line to ""
        val head = line.substring(0, cut).trimEnd()
        return head to line.substring(head.length)
    }

    private fun word(s: State) = when (s) { State.PASS -> "ok"; State.FAIL -> "FAILED"; State.SKIP -> "skipped"; State.NOT_RUN -> "not run" }

    /** one line per step for the problem report, the PC's own lines after ([pc]: "ports ok (…)"); no names, IPs cut */
    fun summary(at: String, rs: List<Result>, pc: List<String> = emptyList()): String = buildString {
        val bad = firstFailure(rs)
        appendLine("Test connection $at: " + if (bad == null) "passed" else "stopped at ${bad.step.label}")
        for (r in rs) appendLine("  ${r.step.label}: ${word(r.state)}" + (if (r.line.isNotEmpty()) " (${r.line})" else "") +
            if (r.fix.isNotEmpty()) " -> ${r.fix}" else "")
        for (l in pc) appendLine("  PC $l")
    }.trimEnd().let(::scrub)
}
