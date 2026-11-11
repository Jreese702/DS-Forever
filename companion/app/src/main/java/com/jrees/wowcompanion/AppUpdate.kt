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
 * one counts; the download must be exactly the API's size, match GitHub's sha256 digest when the release gives one, and open as an APK (a zip with AndroidManifest.xml); at most ONE cached APK is kept, and a bad one is deleted.
 *
 * SLOW LINKS (the user, 2026-11-11: updates on slow links; ~196 MB at ~50 KB/s mobile data is over an hour): no overall deadline any
 * more (the old 10 minutes could never be met there). A try ends only when NO byte arrives for [STALL_MS] ([Stall]); between tries it
 * resumes with Range ([resumeFrom], [resumeOk]), and a stopped download KEEPS its .part file, so the next Update (or the next start)
 * goes on from there. When the API itself fails (60 calls an hour per IP: shared mobile / hotel IPs) the check falls back to the
 * releases page's latest redirect + the release's SHA256SUMS ([checkWeb], danielnoam/WoWEverywhere 860fb95), and an update found in
 * the last day is still offered when both fail ([CACHE_MS]).
 */
object AppUpdate {
    /** the release asset: "DS-Forever-<version>.apk" (e.g. DS-Forever-1.0.3.apk); nothing else is ever downloaded */
    val ASSET = Regex("""^DS-Forever-v?(\d+(?:\.\d+){0,3})\.apk$""")
    /** a try fails only after this long with no byte at all (not a deadline for the whole file) */
    const val STALL_MS = 60_000L
    /** tries per press; each one resumes, and only a stall ([STALL_MS] with no byte) ends them sooner */
    private const val MAX_TRIES = 100
    /** a found update is still offered this long when GitHub cannot be reached at all */
    const val CACHE_MS = 24 * 3_600_000L
    private const val MAX_APK = 600L * 1024 * 1024

    /** where the API lives (the tests point it at a local server) */
    @Volatile var api = "https://api.github.com"
    /** where the releases pages live (the API's fallback; the tests point it at a local server) */
    @Volatile var web = "https://github.com"

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

    /** [cache]: where the last found update is kept (a day, [CACHE_MS]) for a check that cannot reach GitHub at all; null = none */
    fun check(repo: String, current: String, userAgent: String, log: (String) -> Unit = {}, cache: File? = null): Check =
        check0(repo, current, userAgent, log, cache).also { lastCheck = it to System.currentTimeMillis() }

    private fun check0(repo: String, current: String, userAgent: String, log: (String) -> Unit, cache: File?): Check {
        if (!repo.matches(Regex("""[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"""))) return Check.Skipped("no release repo yet").also { log("update: no release repo set") }
        val live = try {
            val body = get("$api/repos/$repo/releases/latest", userAgent).toString(Charsets.UTF_8)
            parse(body, current).also { log("update: checked $repo: $it") }
        } catch (t: Throwable) {
            // the API's 60 calls an hour per IP (the user, 2026-11-11: updates on slow links): the releases page instead
            log("update: API check failed (${reason(t)}); trying the releases page")
            try { checkWeb(repo, current, userAgent).also { log("update: checked $repo (releases page): $it") } }
            catch (t2: Throwable) { Check.Skipped(reason(t2)).also { log("update: check skipped (${reason(t)}; page: ${reason(t2)})") } }
        }
        if (cache == null) return live
        return when (live) {
            is Check.Available -> live.also { runCatching { saveCached(cache, live.release) } }
            is Check.UpToDate -> live.also { cache.delete() }
            is Check.Skipped -> loadCached(cache, current)?.let { Check.Available(it) }?.also { log("update: GitHub not reached; offering ${it.release.version} from the last check") } ?: live
        }
    }

    /**
     * The fallback check: github.com/<repo>/releases/latest answers with a redirect to /releases/tag/<tag> (not the API, no rate limit
     * of 60); the asset is the fixed name for that tag, its checksum the release's SHA256SUMS line (REQUIRED here: there is no API
     * digest), its size the Content-Range of a one-byte read.
     */
    internal fun checkWeb(repo: String, current: String, userAgent: String): Check {
        val c = open("$web/$repo/releases/latest", userAgent, accept = "text/html")
        val tag = try {
            if (c.responseCode !in 300..399) throw IOException("HTTP ${c.responseCode} from the releases page")
            tagFromLatest(c.getHeaderField("Location")) ?: throw IOException("no release")
        } finally { c.disconnect() }
        val version = tag.removePrefix("v").removePrefix("V")
        if (!version.matches(Regex("""\d+(\.\d+){0,3}"""))) return Check.Skipped("no release")
        if (current !in NOT_OURS && compare(version, current) <= 0) return Check.UpToDate(version)
        val name = "DS-Forever-$version.apk"
        val base = "$web/$repo/releases/download/$tag"
        val sha = sumFor(get("$base/SHA256SUMS", userAgent).toString(Charsets.UTF_8), name) ?: return Check.Skipped("no checksum for $name")
        val probe = connect("$base/$name", userAgent, "bytes=0-0")
        val size = try {
            if (probe.responseCode == HttpURLConnection.HTTP_PARTIAL) contentRange(probe.getHeaderField("Content-Range"))?.third
            else probe.getHeaderField("Content-Length")?.toLongOrNull()
        } finally { probe.disconnect() } ?: return Check.Skipped("no size for $name")
        if (size !in 1..MAX_APK) return Check.Skipped("no update file in $tag")
        return Check.Available(Release(tag, version, name, "$base/$name", size, "", sha))
    }

    /** pure: the tag in the latest-release redirect's Location (".../releases/tag/<tag>"); null = none (no release yet) */
    fun tagFromLatest(location: String?): String? =
        location?.let { Regex("""/releases/tag/([A-Za-z0-9._-]+)/?$""").find(it.substringBefore('?').substringBefore('#'))?.groupValues?.get(1) }

    /** pure: the checksum a SHA256SUMS text gives [name] ("<sha256>  <name>", a "*" before the name allowed), or null */
    fun sumFor(text: String, name: String): String? = text.lineSequence().map { it.trim() }.mapNotNull { l ->
        Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""").find(l)?.let { m -> if (m.groupValues[2].trim() == name) m.groupValues[1].lowercase() else null }
    }.firstOrNull()

    private fun saveCached(f: File, r: Release) {
        f.absoluteFile.parentFile?.mkdirs()
        f.writeText(JSONObject().put("at", System.currentTimeMillis()).put("tag", r.tag).put("version", r.version).put("asset", r.asset)
            .put("url", r.url).put("size", r.size).put("notes", r.notes).put("sha256", r.sha256 ?: "").toString())
    }

    /** the kept update when it is under a day old and still newer than [current] (and still well-formed); else null */
    internal fun loadCached(f: File, current: String, now: Long = System.currentTimeMillis()): Release? = try {
        val j = JSONObject(f.readText())
        val r = Release(j.getString("tag"), j.getString("version"), j.getString("asset"), j.getString("url"), j.getLong("size"),
            j.optString("notes"), j.optString("sha256").ifEmpty { null })
        val fresh = now - j.getLong("at") in 0 until CACHE_MS
        val ok = ASSET.matchEntire(r.asset)?.groupValues?.get(1)?.let { compare(it, r.version) == 0 } == true &&
            (r.url.startsWith("https://") || r.url.startsWith("http://127.0.0.1")) && r.size in 1..MAX_APK && (r.sha256 == null || r.sha256.matches(Regex("[0-9a-f]{64}")))
        r.takeIf { fresh && ok && (current in NOT_OURS || compare(r.version, current) > 0) }
    } catch (_: Throwable) { null }

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
     * Downloads [r] into [dir] (the app's private cache), resuming a partial file, retrying while bytes still come ([stallMs] with none
     * ends it). Returns the complete APK (exactly [Release.size] bytes, opens as an APK) or throws with the reason; a bad file is deleted,
     * a stopped one KEPT for the next call to resume (the user, 2026-11-11: updates on slow links). Older files in [dir] go.
     */
    fun download(r: Release, dir: File, userAgent: String, stallMs: Long = STALL_MS, log: (String) -> Unit = {},
                 onProgress: (Float) -> Unit = {}, pause: (Long) -> Unit = { Thread.sleep(it) }): File {
        dir.mkdirs()
        val apk = File(dir, r.asset)
        val part = File(dir, r.asset + ".part")
        dir.listFiles()?.filter { it != apk && it != part }?.forEach { it.delete() }   // at most one cached APK
        if (apk.isFile && apk.length() == r.size && isApk(apk) && matches(r, apk)) { log("update: ${r.asset} already downloaded"); return apk }
        apk.delete()
        if (part.isFile && part.length() > 0) log("update: resuming ${r.asset} at ${part.length()}/${r.size} B")
        val stall = Stall(stallMs)
        var lastError: Throwable? = null
        var tries = 0
        while (part.length() != r.size) {
            tries++
            try {
                fetch(r, part, userAgent, stall, onProgress)
                // a connection closed early can end the stream with no error at all: short = another try (it resumes)
                if (part.length() == r.size) break
                throw IOException("the download is incomplete")
            } catch (t: Throwable) {
                if (t is InterruptedException) throw t
                lastError = t
                log("update: download try $tries stopped at ${part.length()}/${r.size} B (${reason(t)})")
                if (t.message == WRONG_SIZE || stall.stalled() || tries >= MAX_TRIES) break
                pause(minOf(1_000L * tries, 10_000L))
            }
        }
        if (part.length() != r.size) {
            // KEPT (a .part longer than the release is never resumed: fetch starts it over)
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

    /**
     * Every cached update file goes (after a hand-over to the installer, or a refused file). [keepPartial]: a stopped download's .part
     * stays (the startup clean-up: the next Update resumes it; [download] drops one that is not the current release's)
     */
    fun clear(dir: File, keepPartial: Boolean = false) {
        dir.listFiles()?.filter { !(keepPartial && it.name.endsWith(".part") && ASSET.matches(it.name.removeSuffix(".part"))) }?.forEach { it.delete() }
    }

    /**
     * Pure: the stall rule (the user, 2026-11-11: updates on slow links). [bytes] on every read that brought data; [stalled] once
     * [limitMs] passed with none, across tries (a reconnect that brings nothing does not reset it).
     */
    class Stall(private val limitMs: Long, private val now: () -> Long = { System.currentTimeMillis() }) {
        private var last = now()
        fun bytes() { last = now() }
        fun stalled(): Boolean = now() - last >= limitMs
    }

    /** pure: where a resume starts for a kept part of [have] bytes of a [size]-byte file: 0 = from the start (none, or too long) */
    fun resumeFrom(have: Long, size: Long): Long = if (have in 1 until size) have else 0L

    /** pure: "bytes <first>-<last>/<total>" (total -1 when "*"); null = not that */
    fun contentRange(h: String?): Triple<Long, Long, Long>? =
        h?.let { Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""").find(it) }?.let { m ->
            Triple(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLongOrNull() ?: -1L)
        }

    /**
     * Pure: a resume from [from] of a [size]-byte file may append only to a 206 whose Content-Range starts exactly at [from] and runs
     * to the file's end (a proxy or portal answering something else never lands in the middle of the APK).
     */
    fun resumeOk(code: Int, contentRange: String?, from: Long, size: Long): Boolean {
        if (code != HttpURLConnection.HTTP_PARTIAL) return false
        val (first, last, total) = contentRange(contentRange) ?: return false
        return first == from && last == size - 1 && (total == size || total == -1L)
    }

    const val WRONG_SIZE = "the download is larger than announced"

    private fun fetch(r: Release, part: File, userAgent: String, stall: Stall, onProgress: (Float) -> Unit) {
        var have = resumeFrom(if (part.isFile) part.length() else 0L, r.size)
        if (part.isFile && part.length() == r.size) return
        if (have == 0L) part.delete()
        val c = connect(r.url, userAgent, if (have > 0) "bytes=$have-" else null)
        try {
            if (have > 0 && !resumeOk(c.responseCode, c.getHeaderField("Content-Range"), have, r.size)) {
                // a server that ignores the Range sends the WHOLE file again (exactly its size): start over; anything else is not
                // trusted and the kept part stays as it is
                if (c.responseCode == 200 && c.getHeaderField("Content-Length")?.toLongOrNull() == r.size) { part.delete(); have = 0L }
                else throw IOException("a wrong resume answer (HTTP ${c.responseCode})")
            }
            RandomAccessFile(part, "rw").use { out ->
                out.seek(have)
                c.inputStream.use { i ->
                    val buf = ByteArray(65536); var total = have
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        if (n == 0) continue
                        stall.bytes()
                        total += n
                        if (total > r.size) { out.setLength(0); throw IOException(WRONG_SIZE) }
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

    /** one request, no redirect followed (the fallback reads the latest-release redirect itself) */
    private fun open(url: String, userAgent: String, range: String? = null,
                     accept: String = "application/vnd.github+json, application/octet-stream"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = false
            setRequestProperty("User-Agent", userAgent)                  // no token, no personal data: the app's name and version only
            setRequestProperty("Accept", accept)
            if (range != null) setRequestProperty("Range", range)
        }

    private fun connect(url: String, userAgent: String, range: String? = null): HttpURLConnection {
        var u = URL(url)
        repeat(5) {
            val c = open(u.toString(), userAgent, range)
            if (c.responseCode in 300..399) {
                val next = c.getHeaderField("Location") ?: throw IOException("a redirect without a target")
                c.disconnect()
                u = URL(u, next)
                if (u.protocol != "https" && !url.startsWith("http://127.0.0.1")) throw IOException("a redirect off HTTPS")
                return@repeat
            }
            if (c.responseCode != 200 && c.responseCode != 206) {
                // the API's own rate-limit header in the log, so a field report tells "limited" from "offline"
                val left = c.getHeaderField("X-RateLimit-Remaining")?.let { " (rate limit left: $it)" }.orEmpty()
                c.disconnect(); throw IOException("HTTP ${c.responseCode}$left")
            }
            return c
        }
        throw IOException("too many redirects")
    }

    private fun get(url: String, userAgent: String): ByteArray =
        connect(url, userAgent).let { c -> try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() } }
}
