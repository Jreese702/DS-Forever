package com.jrees.wowcompanion

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "SEND REPORT" WITHOUT LEAVING THE GAME (board f-problem-report; the user's pick "In-game upload via my own receiver"): the report,
 * already redacted on the device, POSTed to the project's own receiver (tools/report-worker, a Cloudflare Worker that opens the GitHub
 * issue with a token only it holds). Nothing secret is in the APK: [key] only keeps casual traffic out of the receiver.
 * [endpoint] empty (a build without dsfReportEndpoint) = the earlier behaviour: save, then the pre-filled issue in the browser.
 * Only ever called for the player's own Send press, or their Record 5 minutes ending by itself.
 */
object ReportUpload {
    /** the receiver's /report address, from the build (dsfReportEndpoint); "" = no upload */
    @Volatile var endpoint: String = runCatching { BuildConfig.REPORT_ENDPOINT }.getOrDefault("")
    @Volatile var key: String = runCatching { BuildConfig.REPORT_KEY }.getOrDefault("")

    val enabled: Boolean get() = endpoint.startsWith("https://")

    // the receiver's limits (tools/report-worker/src/worker.js LIMITS), kept with a margin
    const val MAX_WORDS = 2_000
    const val MAX_REPORT = 58_000
    const val MAX_LOGS = 140_000

    data class Sent(val number: Int, val url: String)

    /** the request's JSON: the versions, the device, the words, report.txt and logs.txt (the NEWEST lines kept when it is too long) */
    fun request(report: ProblemReport.Report, words: String, facts: ProblemReport.Facts, recorded: Boolean,
                names: Collection<String> = Redact.names()): String {
        fun file(name: String) = report.files.firstOrNull { it.first == name }?.second ?: ""
        var logs = file("logs.txt").let { if (it.length > MAX_LOGS) "[older lines cut]\n" + it.takeLast(MAX_LOGS) else it }
        val head = file("report.txt").let { if (it.length > MAX_REPORT) it.take(MAX_REPORT) + "\n[cut]" else it }
        fun json() = JSONObject()
            .put("version", facts.app)
            .put("addons", facts.addons.take(40))
            .put("device", facts.device.take(120))
            .put("words", Redact.text(words.trim(), names).take(MAX_WORDS))       // the same names as the report's own pass
            .put("report", head)
            .put("logs", logs)
            .put("recorded", recorded)
            .toString()
        // JSON's escapes (every newline, every quote) grow the text: the oldest log lines go until it fits the receiver's 200 KB
        var out = json()
        while (out.length > MAX_REQUEST && logs.length > 1_000) {
            logs = "[older lines cut]\n" + logs.takeLast((logs.length * 4) / 5)
            out = json()
        }
        return out
    }

    /** under the receiver's 200 000 (tools/report-worker LIMITS.maxBytes) */
    const val MAX_REQUEST = 190_000

    /** the receiver's answer: the issue, or the reason it gave */
    fun parse(status: Int, body: String): Result<Sent> = runCatching {
        val o = runCatching { JSONObject(body) }.getOrNull()
        if (status in 200..299 && o != null && o.optInt("number", 0) > 0) Sent(o.getInt("number"), o.optString("url"))
        else error(o?.optString("error")?.ifEmpty { null } ?: "the receiver answered $status")
    }

    /** POSTs [json] (off the main thread); the issue, or why not in the player's words */
    fun send(json: String): Result<Sent> {
        if (!enabled) return Result.failure(IllegalStateException("no receiver in this build"))
        return runCatching {
            val c = URL(endpoint).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                c.setRequestProperty("Content-Type", "application/json")
                if (key.isNotEmpty()) c.setRequestProperty("X-DSF-Key", key)
                c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                val status = c.responseCode
                val body = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                parse(status, body).getOrThrow()
            } finally { c.disconnect() }
        }.recoverCatching { e ->
            throw IllegalStateException(when (e) {
                is java.net.UnknownHostException -> "no internet connection"
                is java.net.SocketTimeoutException -> "the receiver did not answer"
                else -> e.message ?: e.javaClass.simpleName
            })
        }
    }
}
