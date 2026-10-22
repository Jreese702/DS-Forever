package com.jrees.wowcompanion

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * DS FOREVER'S OWN UPDATE (the user's rulings 2026-10-12, Track C): at startup, never blocking, read the PUBLIC release repo's latest
 * GitHub release (no token, ever; a plain User-Agent), and on the player's press download the APK into the private cache for Android's
 * installer. Pure (java.net + org.json): the launcher's AppUpdater adds what only Android can do - the signing certificate check against
 * our own install, the archive's versionCode, the installer, the "Install unknown apps" screen.
 *
 * Safety: the asset is picked by its EXACT name ([ASSET]) and its version must equal the tag's; only a newer version than the running
 * one counts; the download must be exactly the API's size, match GitHub's sha256 digest when the release gives one, and open as an APK (a zip with AndroidManifest.xml); it resumes (Range) and
 * retries once inside a [DEADLINE_MS] overall deadline; at most ONE cached APK is kept, and a failed one is deleted.
 */
object AppUpdate {
    /** the release asset: "DS-Forever-<version>.apk" (e.g. DS-Forever-1.0.3.apk); nothing else is ever downloaded */
    val ASSET = Regex("""^DS-Forever-v?(\d+(?:\.\d+){0,3})\.apk$""")
    const val DEADLINE_MS = 10 * 60_000L
    private const val MAX_APK = 600L * 1024 * 1024

    /** where the API lives (the tests point it at a local server) */
    @Volatile var api = "https://api.github.com"

    /**
     * The versionNames that are NOT ours (the user's ruling 2026-10-12: the first public release is "1.0.0", versionCode above 222):
     * a test build still named after the upstream launcher ("2.2.2") counts as older than ANY release, so it never blocks or confuses
     * the line. The name only drives the "update available" line; the real gate is the archive's versionCode at verify (AppUpdater).
     */
    val NOT_OURS = setOf("2.2.2")

    /** [sha256]: GitHub's own checksum of the asset ("digest": "sha256:<hex>"), lower-case hex; null = the release carries none */
    data class Release(val tag: String, val version: String, val asset: String, val url: String, val size: Long, val notes: String,
                       val sha256: String? = null)

    sealed class Check {
        data class UpToDate(val latest: String) : Check()
        data class Available(val release: Release) : Check()
        data class Skipped(val why: String) : Check()
    }

    /** the one-line top-screen check: "DS Forever <ver>: up to date / update <ver> available / skipped (<why>)" */
    fun line(current: String, c: Check?): String = "DS Forever $current: " + when (c) {
        null -> "checking for updates"
        is Check.UpToDate -> "up to date"
        is Check.Available -> "update ${c.release.version} available"
        is Check.Skipped -> "update check skipped (${c.why})"
    }

    /** the release repo's latest release against [current]; never throws (offline / any error = Skipped) */
    /** the last [check]'s answer and when (wall clock): the problem report's "app update" line */
    @Volatile var lastCheck: Pair<Check, Long>? = null

    fun check(repo: String, current: String, userAgent: String, log: (String) -> Unit = {}): Check =
        check0(repo, current, userAgent, log).also { lastCheck = it to System.currentTimeMillis() }

    private fun check0(repo: String, current: String, userAgent: String, log: (String) -> Unit): Check {
        if (!repo.matches(Regex("""[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"""))) return Check.Skipped("no release repo yet").also { log("update: no release repo set") }
        return try {
            val body = get("$api/repos/$repo/releases/latest", userAgent).toString(Charsets.UTF_8)
            parse(body, current).also { log("update: checked $repo: $it") }
        } catch (t: Throwable) {
            Check.Skipped(reason(t)).also { log("update: check skipped (${reason(t)})") }
        }
    }

    /** pure: a release document against [current] */
    fun parse(json: String, current: String): Check {
        val rel = try { JSONObject(json) } catch (t: Throwable) { return Check.Skipped("bad release data") }
        val tag = rel.optString("tag_name")
        val version = tag.removePrefix("v").removePrefix("V")
        if (!version.matches(Regex("""\d+(\.\d+){0,3}"""))) return Check.Skipped("no release")
        if (current !in NOT_OURS && compare(version, current) <= 0) return Check.UpToDate(version)
        val assets = rel.optJSONArray("assets") ?: return Check.Skipped("no update file in $tag")
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val m = ASSET.matchEntire(name) ?: continue
            if (compare(m.groupValues[1], version) != 0) continue            // the file's version must be the tag's
            val url = a.optString("browser_download_url")
            val size = a.optLong("size")
            if (!url.startsWith("https://") && !url.startsWith("http://127.0.0.1")) continue
            if (size !in 1..MAX_APK) continue
            // the asset's checksum (Tracking 2026-10-12, part of the user's updater request): when GitHub gives one, the download must match
            // it; one that is there but unreadable (not sha256, not 64 hex) never counts as "none": that release is passed over
            val digest = a.optString("digest").trim()
            val sha = if (digest.isEmpty() || digest == "null") null else digest.removePrefix("sha256:").lowercase()
            if (sha != null && (!digest.startsWith("sha256:") || !sha.matches(Regex("[0-9a-f]{64}")))) continue
            return Check.Available(Release(tag, version, name, url, size, rel.optString("body"), sha))
        }
        return Check.Skipped("no update file in $tag")
    }

    /** numeric parts, left to right; a missing part is 0 */
    fun compare(a: String, b: String): Int {
        val x = Regex("\\d+").findAll(a.substringBefore('-')).map { it.value.toLong() }.toList()
        val y = Regex("\\d+").findAll(b.substringBefore('-')).map { it.value.toLong() }.toList()
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /**
     * Downloads [r] into [dir] (the app's private cache), resuming a partial file, one retry, inside [deadlineMs]. Returns the complete
     * APK (exactly [Release.size] bytes, opens as an APK) or throws with the reason; a bad file is deleted. Older files in [dir] go.
     */
    fun download(r: Release, dir: File, userAgent: String, deadlineMs: Long = DEADLINE_MS, log: (String) -> Unit = {},
                 onProgress: (Float) -> Unit = {}): File {
        dir.mkdirs()
        val apk = File(dir, r.asset)
        val part = File(dir, r.asset + ".part")
        dir.listFiles()?.filter { it != apk && it != part }?.forEach { it.delete() }   // at most one cached APK
        if (apk.isFile && apk.length() == r.size && isApk(apk) && matches(r, apk)) { log("update: ${r.asset} already downloaded"); return apk }
        apk.delete()
        val until = System.currentTimeMillis() + deadlineMs
        var lastError: Throwable? = null
        for (attempt in 1..2) {
            try {
                fetch(r, part, userAgent, until, onProgress)
                // a connection closed early can end the stream with no error at all: short = another try (it resumes)
                if (part.length() == r.size) break
                throw IOException("the download is incomplete")
            } catch (t: Throwable) {
                lastError = t
                log("update: download attempt $attempt stopped (${reason(t)})")
                if (System.currentTimeMillis() >= until) break
            }
        }
        if (part.length() != r.size) {
            part.delete()
            throw IOException(if (lastError != null) reason(lastError) else "the download is incomplete")
        }
        if (!isApk(part)) { part.delete(); throw IOException("the download is not a complete APK") }
        if (!matches(r, part)) { part.delete(); log("update: REFUSED ${r.asset}: its sha256 is not the release's"); throw IOException(BAD_CHECKSUM) }
        if (!part.renameTo(apk)) { part.delete(); throw IOException("the download could not be kept") }
        log("update: ${r.asset} downloaded (${r.size} B)")
        return apk
    }

    const val BAD_CHECKSUM = "the download does not match the release's checksum"

    /** true when the release names no checksum, or [f]'s sha256 is exactly it */
    fun matches(r: Release, f: File): Boolean = r.sha256 == null || sha256(f) == r.sha256

    fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val buf = ByteArray(65536); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** a zip that opens and has the manifest an APK always has */
    fun isApk(f: File): Boolean = try { ZipFile(f).use { it.getEntry("AndroidManifest.xml") != null } } catch (_: Throwable) { false }

    /** every cached update file goes (after a hand-over to the installer, or a failure) */
    fun clear(dir: File) { dir.listFiles()?.forEach { it.delete() } }

    private fun fetch(r: Release, part: File, userAgent: String, until: Long, onProgress: (Float) -> Unit) {
        var have = if (part.isFile) part.length() else 0L
        if (have > r.size) { part.delete(); have = 0L }
        if (have == r.size) return
        val c = connect(r.url, userAgent, if (have > 0) "bytes=$have-" else null)
        try {
            // a server that ignores the Range sends it all again: start over
            if (have > 0 && c.responseCode != HttpURLConnection.HTTP_PARTIAL) { part.delete(); have = 0L }
            RandomAccessFile(part, "rw").use { out ->
                out.seek(have)
                c.inputStream.use { i ->
                    val buf = ByteArray(65536); var total = have
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        total += n
                        if (total > r.size) throw IOException("the download is larger than announced")
                        if (System.currentTimeMillis() > until) throw IOException("the download took too long")
                        out.write(buf, 0, n)
                        onProgress(total.toFloat() / r.size)
                    }
                }
            }
        } finally { c.disconnect() }
    }

    private fun reason(t: Throwable): String = when (t) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "offline"
        is java.net.SocketTimeoutException -> "no answer"
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun connect(url: String, userAgent: String, range: String? = null): HttpURLConnection {
        var u = URL(url)
        repeat(5) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent)                  // no token, no personal data: the app's name and version only
                setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
                if (range != null) setRequestProperty("Range", range)
            }
            if (c.responseCode in 300..399) {
                val next = c.getHeaderField("Location") ?: throw IOException("a redirect without a target")
                c.disconnect()
                u = URL(u, next)
                if (u.protocol != "https" && !url.startsWith("http://127.0.0.1")) throw IOException("a redirect off HTTPS")
                return@repeat
            }
            if (c.responseCode != 200 && c.responseCode != 206) { c.disconnect(); throw IOException("HTTP ${c.responseCode}") }
            return c
        }
        throw IOException("too many redirects")
    }

    private fun get(url: String, userAgent: String): ByteArray =
        connect(url, userAgent).let { c -> try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() } }
}
