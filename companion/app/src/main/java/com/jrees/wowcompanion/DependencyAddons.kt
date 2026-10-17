package com.jrees.wowcompanion

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * DEPENDENCY ADD-ONS, DOWNLOADED, NEVER BUNDLED (the user's ruling 2026-10-12; the source approved 2026-10-12: "Use Questie/QuestieDB
 * GitHub releases, package QuestieDB-Forever.zip"; board item w-depaddons; Track C M4). The APK ships only our own WowCompanion*
 * add-ons. Right after the art check, each [Dep] is checked against its project's OWN GitHub releases:
 *  - newer than the installed .toc "## Version", or missing: download into the app's private cache, check the size, GitHub's own
 *    sha256 digest for that asset and every zip entry's CRC (ZipInputStream checks each entry as it is read), unpack into a staging
 *    folder beside AddOns, move the old copy to a backup folder (NEVER deleted), then move the new one in;
 *  - offline / no release / any failure: skipped with the reason; the old copy stays; it NEVER blocks Play (a soft dependency, the
 *    user's 10-06 ruling: DS Forever reads QuestieDB in the game when it is installed);
 *  - never while the game runs (the final move waits for a start with the game closed);
 *  - at most every [RECHECK_MS] (GitHub's unauthenticated API allows 60 calls an hour).
 * More dependencies are one more [Dep] line.
 */
object DependencyAddons {
    /**
     * [asset] the release asset's exact name, or with [assetRegex] its pattern (a versioned name); [multi] = the package holds several
     * add-on folders side by side (each with its own .toc; [folder] is the one whose version is compared); [optIn] = only while the
     * user has chosen it (Polestar: Settings' "Quest tracking").
     */
    data class Dep(val folder: String, val repo: String, val asset: String, val assetRegex: Regex? = null, val multi: Boolean = false,
                   val optIn: Boolean = false)

    val DEPS = listOf(
        Dep("QuestieDB", "Questie/QuestieDB", "QuestieDB-Forever.zip"),
        // POLESTAR (the user, 2026-10-17: "Quest tracking: ... Polestar"; "DS Forever downloads it from its GitHub release"): GPL-3,
        // never bundled - its own release, verified (GitHub's digest, else its SHA256SUMS), only while the setting is Polestar.
        // The package holds Polestar and Polestar_Nav (Shortest Path Forever's router) side by side; the recorder zip is not taken.
        Dep("Polestar", "fitzforge/polestar", "Polestar-<version>.zip", assetRegex = Regex("""^Polestar-v?\d[\w.\-]*\.zip$"""),
            multi = true, optIn = true),
    )

    /** which dependencies are wanted now: the always-on ones, and an opt-in one only while the user has chosen it */
    @Volatile var wanted: (Dep) -> Boolean = { !it.optIn || Settings.questTracking == "polestar" }

    const val RECHECK_MS = 6 * 60 * 60_000L
    /** a whole download's deadline: a server that trickles bytes (each read inside the read timeout) still ends (Track D's note) */
    const val DOWNLOAD_DEADLINE_MS = 10 * 60_000L
    private const val MAX_ZIP = 64L * 1024 * 1024
    private const val MAX_FILES = 5_000
    private const val MAX_UNPACKED = 512L * 1024 * 1024
    private const val BACKUP = "AddOns-backup-dsforever"
    private const val STAGING = "AddOns-staging-dsforever"

    /** where the API lives (the tests point it at a local server) */
    @Volatile var api = "https://api.github.com"
    /** the game is running: nothing is moved into AddOns now */
    @Volatile var gameRunning: () -> Boolean = { LauncherHooks.gameUp() }

    data class Result(val dep: Dep, val line: String, val changed: Boolean = false)

    /**
     * Checks / installs every dependency for the game's [flavorRoot] (the `_classic_beta_` folder); [cache] = the app's private cache,
     * [state] = a small private file remembering the last check. One line for the top screen.
     */
    fun run(flavorRoot: File, cache: File, state: File, now: Long = System.currentTimeMillis()): String {
        val addons = File(flavorRoot, "Interface/AddOns")
        val last = runCatching { JSONObject(state.readText()) }.getOrNull()
        val results = DEPS.filter { wanted(it) }.map { dep ->
            val installed = installedVersion(File(addons, dep.folder))
            val prev = last?.optJSONObject(dep.folder)
            // recently checked and still the same copy: the last line again, no network
            if (prev != null && now - prev.optLong("at") < RECHECK_MS && prev.optString("installed") == (installed ?: "") &&
                !prev.optBoolean("pending")) Result(dep, prev.optString("line"))
            else runCatching { check(dep, flavorRoot, cache, installed) }
                .getOrElse { Result(dep, "${dep.folder} skipped (${reason(it)})${installed?.let { v -> "; $v kept" } ?: ""}") }
        }
        val out = JSONObject()
        for (r in results) out.put(r.dep.folder, JSONObject().put("at", now).put("line", r.line)
            .put("installed", installedVersion(File(addons, r.dep.folder)) ?: "").put("pending", r.line.contains("next start")))
        runCatching { state.parentFile?.mkdirs(); state.writeText(out.toString()) }
        return results.joinToString("; ") { it.line }
    }

    private fun reason(t: Throwable): String = when (t) {
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException -> "offline"
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun check(dep: Dep, flavorRoot: File, cache: File, installed: String?): Result {
        val rel = JSONObject(get("$api/repos/${dep.repo}/releases/latest").toString(Charsets.UTF_8))
        val tag = rel.optString("tag_name").ifEmpty { throw IOException("no release") }
        val latest = tag.removePrefix("v")
        if (installed != null && compare(installed, latest) >= 0) return Result(dep, "${dep.folder} $installed (up to date)")
        val assets = rel.optJSONArray("assets") ?: throw IOException("no ${dep.asset} in $tag")
        val all = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        val asset = all.firstOrNull { a -> dep.assetRegex?.matches(a.optString("name")) ?: (a.optString("name") == dep.asset) }
            ?: throw IOException("no ${dep.asset} in $tag")
        val size = asset.optLong("size")
        // GitHub's own digest, else the release's SHA256SUMS line for this asset
        var sha = asset.optString("digest").removePrefix("sha256:").lowercase()
        if (!sha.matches(Regex("[0-9a-f]{64}"))) all.firstOrNull { it.optString("name").equals("SHA256SUMS", true) }?.let { sums ->
            sha = sumFor(get(sums.optString("browser_download_url")).toString(Charsets.UTF_8), asset.optString("name")) ?: ""
        }
        require(size in 1..MAX_ZIP) { "${dep.asset} has an unexpected size ($size)" }
        require(sha.matches(Regex("[0-9a-f]{64}"))) { "${dep.asset} has no checksum" }
        // the verified zip is kept in the private cache, so a start that cannot move it in yet does not download it again
        val zip = File(cache, "deps/${dep.folder}-$latest.zip")
        if (!(zip.isFile && zip.length() == size && sha256(zip) == sha)) {
            zip.parentFile?.mkdirs()
            val part = File(zip.path + ".part")
            download(asset.optString("browser_download_url"), part, size)
            if (part.length() != size) { part.delete(); throw IOException("the download is incomplete") }
            if (sha256(part) != sha) { part.delete(); throw IOException("the download's checksum is wrong") }
            zip.delete()                                   // a stale or broken copy of the same version
            if (!part.renameTo(zip)) throw IOException("the download could not be kept")
        }
        val deferred = Result(dep, "${dep.folder} $latest downloaded; installs at the next start with the game closed")
        if (gameRunning()) return deferred
        // a package that does not unpack cleanly is not kept: the next check downloads it again; a game that started meanwhile keeps it
        try { install(dep, flavorRoot, zip, installed) }
        catch (_: GameStarted) { return deferred }
        catch (t: Throwable) { zip.delete(); throw t }
        // keep only this version's zip
        File(cache, "deps").listFiles()?.filter { it.name.startsWith("${dep.folder}-") && it != zip }?.forEach { it.delete() }
        return Result(dep, if (installed == null) "${dep.folder} $latest installed" else "${dep.folder} updated $installed -> $latest", true)
    }

    /** the checksum a SHA256SUMS text gives [name] ("<sha256>  <name>" lines, a "*" before the name allowed), or null */
    internal fun sumFor(text: String, name: String): String? = text.lineSequence().map { it.trim() }.mapNotNull { l ->
        Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""").find(l)?.let { m -> if (m.groupValues[2].trim() == name) m.groupValues[1].lowercase() else null }
    }.firstOrNull()

    /** unpack (CRC-checked, no path outside the folder) into staging, old copy to the backup, staging into AddOns */
    private fun install(dep: Dep, flavorRoot: File, zip: File, installed: String?) {
        if (dep.multi) return installMulti(dep, flavorRoot, zip)
        val iface = File(flavorRoot, "Interface")
        val addons = File(iface, "AddOns").apply { mkdirs() }
        val staging = File(iface, "$STAGING/${dep.folder}")
        staging.parentFile?.mkdirs()
        if (staging.exists()) staging.deleteRecursively()          // our own leftover only (never AddOns, never the backup)
        unpack(zip, staging, dep.folder)
        require(installedVersion(staging) != null) { "the package has no ${dep.folder} .toc" }
        // Play may have been pressed while it unpacked: AddOns change only with the game closed (Track D's note)
        if (gameRunning()) throw GameStarted()
        val target = File(addons, dep.folder)
        if (target.exists()) {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val backup = File(iface, "$BACKUP/${dep.folder}-${installed ?: "unknown"}-$stamp")
            backup.parentFile?.mkdirs()
            if (!target.renameTo(backup)) throw IOException("the old ${dep.folder} could not be moved to the backup")
        }
        if (!staging.renameTo(target)) {
            // put the old copy back where it was
            File(iface, BACKUP).listFiles()?.filter { it.name.startsWith("${dep.folder}-") }?.maxByOrNull { it.lastModified() }
                ?.let { if (!target.exists()) it.renameTo(target) }
            throw IOException("the new ${dep.folder} could not be moved in")
        }
    }

    /** a package of several add-on folders (Polestar + Polestar_Nav): each top-level folder with a .toc goes in as its own, the
     *  old copy of each to the backup first; nothing else of the package is taken */
    private fun installMulti(dep: Dep, flavorRoot: File, zip: File) {
        val iface = File(flavorRoot, "Interface")
        val addons = File(iface, "AddOns").apply { mkdirs() }
        val staging = File(iface, "$STAGING/${dep.folder}-pkg")
        if (staging.exists()) staging.deleteRecursively()
        unpack(zip, staging, "")
        val folders = staging.listFiles()?.filter { d -> d.isDirectory && installedVersion(d) != null } ?: emptyList()
        require(folders.any { it.name == dep.folder }) { "the package has no ${dep.folder} .toc" }
        if (gameRunning()) throw GameStarted()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        for (d in folders) {
            val target = File(addons, d.name)
            if (target.exists()) {
                val backup = File(iface, "$BACKUP/${d.name}-${installedVersion(target) ?: "unknown"}-$stamp")
                backup.parentFile?.mkdirs()
                if (!target.renameTo(backup)) throw IOException("the old ${d.name} could not be moved to the backup")
            }
            if (!d.renameTo(target)) throw IOException("the new ${d.name} could not be moved in")
        }
        staging.deleteRecursively()
    }

    /** the zip into [to]: files at its top level, or all under one "<folder>/" (both packagings) */
    internal fun unpack(zip: File, to: File, folder: String) {
        to.mkdirs()
        val root = to.canonicalFile
        var files = 0; var bytes = 0L
        val names = ZipInputStream(zip.inputStream().buffered()).use { z ->
            generateSequence { z.nextEntry }.map { it.name.replace('\\', '/') }.toList()
        }
        val prefix = "$folder/".takeIf { p -> folder.isNotEmpty() && names.isNotEmpty() && names.all { it.startsWith(p) } } ?: ""
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            val buf = ByteArray(65536)
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name.replace('\\', '/').removePrefix(prefix)
                if (name.isEmpty()) continue
                val out = File(to, name).canonicalFile
                require(out.path.startsWith(root.path + File.separator)) { "the package has a bad path ($name)" }
                if (e.isDirectory) { out.mkdirs(); continue }
                require(++files <= MAX_FILES) { "the package has too many files" }
                out.parentFile?.mkdirs()
                out.outputStream().use { o ->
                    while (true) {
                        val n = z.read(buf)              // a CRC mismatch throws here (ZipException)
                        if (n < 0) break
                        bytes += n
                        require(bytes <= MAX_UNPACKED) { "the package unpacks too large" }
                        o.write(buf, 0, n)
                    }
                }
            }
        }
        require(files > 0) { "the package is empty" }
    }

    private class GameStarted : RuntimeException()

    /** "## Version: x" from the folder's .toc files (any flavour's), or null when it is not installed */
    fun installedVersion(dir: File): String? = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".toc", true) }
        ?.firstNotNullOfOrNull { toc ->
            runCatching { toc.readLines() }.getOrNull()?.firstNotNullOfOrNull { l ->
                Regex("""^##\s*Version\s*:\s*v?(\S+)""", RegexOption.IGNORE_CASE).find(l.trim())?.groupValues?.get(1)
            }
        }

    /** numeric parts, left to right ("1.0.10" > "1.0.9"); a missing part is 0 */
    fun compare(a: String, b: String): Int {
        val x = Regex("\\d+").findAll(a).map { it.value.toLong() }.toList()
        val y = Regex("\\d+").findAll(b).map { it.value.toLong() }.toList()
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private fun connect(url: String): HttpURLConnection {
        var u = URL(url)
        repeat(5) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "DS-Forever")
                setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
            }
            // GitHub sends assets through a redirect to its file host; HTTPS only after the first hop
            if (c.responseCode in 300..399) {
                val next = c.getHeaderField("Location") ?: throw IOException("a redirect without a target")
                c.disconnect()
                u = URL(u, next)
                if (u.protocol != "https" && !url.startsWith("http://127.0.0.1")) throw IOException("a redirect off HTTPS")
                return@repeat
            }
            if (c.responseCode != 200) { c.disconnect(); throw IOException("HTTP ${c.responseCode}") }
            return c
        }
        throw IOException("too many redirects")
    }

    private fun get(url: String): ByteArray = connect(url).let { c -> try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() } }

    private fun download(url: String, to: File, size: Long) {
        val until = System.currentTimeMillis() + DOWNLOAD_DEADLINE_MS
        val c = connect(url)
        try {
            c.inputStream.use { i ->
                to.outputStream().use { o ->
                    val buf = ByteArray(65536); var total = 0L
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        total += n
                        if (total > size) throw IOException("the download is larger than announced")
                        if (System.currentTimeMillis() > until) throw IOException("the download took too long")
                        o.write(buf, 0, n)
                    }
                }
            }
        } finally { c.disconnect() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val buf = ByteArray(65536); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
