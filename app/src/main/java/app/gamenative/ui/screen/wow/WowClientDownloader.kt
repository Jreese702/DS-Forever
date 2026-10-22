package app.gamenative.ui.screen.wow

import app.gamenative.utils.Net
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import com.jrees.wowcompanion.WowProduct
import com.jrees.wowcompanion.WowSwitch
import java.time.LocalDate
import timber.log.Timber

/**
 * The game client's updater. DS Forever launch support (2026-10-13): the product, its flavor folder, exe and patch URL are no longer
 * the beta's constants but the game folder's own ([WowProduct]: the beta wow_classic_beta or the launch client wowf, from .build.info).
 */
object WowClientDownloader {
    const val BUILD_INFO = WowProduct.BUILD_INFO
    private const val PLATFORM_ARCH = "arm64"
    private const val EXCLUDED_ARCH = "x86_64"
    private const val DEFAULT_CDN_PATH = "tpr/wow"
    private val DEFAULT_CDN_HOSTS = listOf("level3.blizzard.com", "us.cdn.blizzard.com")

    /** a product's patch server (versions, cdns); the tests point it at a local server */
    @Volatile internal var patchUrlFor: (WowProduct) -> String = { it.patchUrl }

    private val patchHttp by lazy {
        Net.http.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    data class VersionCheckResult(
        val localVersion: String,
        val remoteVersion: String,
        val isOutdated: Boolean,
        val remoteBuildConfig: String = "",
        val remoteCdnConfig: String = "",
        val remoteCdnHosts: List<String> = emptyList(),
        val remoteCdnPath: String = DEFAULT_CDN_PATH,
        /** the client this check was for (its .build.info row is the one [updateGame] rewrites) */
        val product: WowProduct = WowProduct.BETA,
        /** the cdns row's Servers and the versions row's KeyRing (the launch row's columns at the switch) */
        val remoteCdnServers: String? = null,
        val remoteKeyRing: String? = null,
    )

    /** the client the game folder [gameRoot] runs ([WowProduct.of]) */
    fun product(gameRoot: File): WowProduct = WowProduct.of(gameRoot)

    /** its flavor folder (Interface, WTF, Logs, the exe) */
    fun flavorDir(gameRoot: File): File = File(gameRoot, product(gameRoot).flavorDir)

    /** its ARM64 exe */
    fun exe(gameRoot: File): File = product(gameRoot).exe(gameRoot)

    /** the row the game runs of the game folder holding [buildInfo] */
    fun readBuildInfo(buildInfo: File): Map<String, String>? =
        runCatching { activeBuild(buildInfo.absoluteFile.parentFile).row }.getOrNull()

    fun checkVersion(gameRoot: File): VersionCheckResult? {
        val choice = runCatching { activeBuild(gameRoot) }.getOrElse {
            Timber.w(it, "checkVersion: no readable $BUILD_INFO in $gameRoot")
            return null
        }
        val row = choice.row
        val product = choice.product ?: run {
            Timber.w("checkVersion: wrong product '${row["Product"]}', expected one of ${WowProduct.values().map { it.code }}")
            return null
        }
        val localVersion = row["Version"] ?: run {
            Timber.w("checkVersion: row has no Version")
            return null
        }
        val localBuildKey = row["Build Key"] ?: run {
            Timber.w("checkVersion: row has no Build Key")
            return null
        }

        return remoteCheck(product, row, localVersion, localBuildKey)
    }

    /** [product]'s build on its patch server for the install whose .build.info row is [row]; null = no answer */
    private fun remoteCheck(product: WowProduct, row: Map<String, String>, localVersion: String, localBuildKey: String): VersionCheckResult? {
        val versions = runCatching { fetchText("${patchUrlFor(product)}/versions") }.getOrElse {
            Timber.w(it, "checkVersion: http call failed")
            return null
        } ?: run {
            Timber.w("checkVersion: response body is null or unsuccessful")
            return null
        }
        val remoteRow = remoteRow(parsePsv(versions), "Region", row["Branch"])
            ?: run {
                Timber.w("checkVersion: no remote row in $versions")
                return null
            }
        val remoteVersion = remoteRow["VersionsName"] ?: remoteRow["VersionName"] ?: remoteRow["Version"]
            ?: run {
                Timber.w("checkVersion: no remoteVersion in $remoteRow")
                return null
            }
        val remoteBuildKey = remoteRow["BuildConfig"].orEmpty()

        val cdns = runCatching { fetchText("${patchUrlFor(product)}/cdns") }.getOrNull()?.let(::parsePsv).orEmpty()
        val cdnsRow = remoteRow(cdns, "Name", row["Branch"])

        val isOutdated = localVersion != remoteVersion || (remoteBuildKey.isNotBlank() && localBuildKey != remoteBuildKey)
        Timber.i("checkVersion: ${product.code} localVersion=$localVersion, remoteVersion=$remoteVersion, isOutdated=$isOutdated")
        return VersionCheckResult(
            localVersion = localVersion,
            remoteVersion = remoteVersion,
            isOutdated = isOutdated,
            remoteBuildConfig = remoteBuildKey,
            remoteCdnConfig = remoteRow["CDNConfig"].orEmpty(),
            remoteCdnHosts = (cdnsRow?.get("Hosts") ?: row["CDN Hosts"])?.let(::splitHosts) ?: DEFAULT_CDN_HOSTS,
            remoteCdnPath = cdnsRow?.get("Path") ?: row["CDN Path"] ?: DEFAULT_CDN_PATH,
            product = product,
            remoteCdnServers = cdnsRow?.get("Servers"),
            remoteKeyRing = remoteRow["KeyRing"],
        )
    }

    /** what [switchToLaunch] did */
    sealed class Switch {
        /** nothing to do: the folder does not run the beta, or the beta is not over yet */
        object NotDue : Switch()
        /** the launch client's patch server did not answer (no network, or no wowf build yet): nothing was changed */
        data class NoAnswer(val why: String) : Switch()
        /** the folder now runs the launch client */
        data class Done(val version: String) : Switch()
    }

    private val switchLock = Any()

    /**
     * THE BETA IS OVER: MOVE THIS FOLDER TO THE LAUNCH CLIENT (WowSwitch's trigger and design). When [WowSwitch.due] and wowf/versions
     * answers: the wowf row goes into .build.info beside the beta's, then the wowf client is synced as a game update is ([updateGame]:
     * the configs, the _forever_ files with the exe last, the indices, the row's keys). A failure part-way throws; the folder still runs
     * the beta and the next call goes on from where this one stopped. One move at a time (Play and the screen's check may both ask).
     */
    fun switchToLaunch(gameRoot: File, onStatus: (String) -> Unit, today: LocalDate = LocalDate.now()): Switch = synchronized(switchLock) {
        val choice = runCatching { activeBuild(gameRoot, today) }.getOrNull()          // the same day [WowSwitch.due] reads
        if (!WowSwitch.due(choice, today)) return Switch.NotDue
        val row = choice!!.row
        val status: (String) -> Unit = { onStatus(WowSwitch.line(it)) }
        status("Checking World of Warcraft: Forever's patch server...")
        val target = remoteCheck(WowProduct.LAUNCH, row, row["Version"].orEmpty(), row["Build Key"].orEmpty())
            ?.takeIf { it.remoteBuildConfig.isNotBlank() && it.remoteCdnConfig.isNotBlank() }
            ?: return Switch.NoAnswer("Blizzard's World of Warcraft: Forever patch server did not answer").also {
                Timber.w("switchToLaunch: wowf/versions gave no build; the folder stays as it is")
            }
        Timber.i("switchToLaunch: the beta is over; moving ${row["Version"]} -> wowf ${target.remoteVersion}")
        val buildInfo = File(gameRoot, BUILD_INFO)
        val text = buildInfo.readText()
        // a resumed move whose row is already this build's keeps it as it is
        val rowNow = WowProduct.parse(text).firstOrNull { it["Product"] == WowProduct.LAUNCH.code }
        if (rowNow?.get("Build Key") != target.remoteBuildConfig || rowNow["CDN Key"] != target.remoteCdnConfig || rowNow["Active"] != "1") {
            val remote = WowSwitch.Remote(
                version = target.remoteVersion,
                buildConfig = target.remoteBuildConfig,
                cdnConfig = target.remoteCdnConfig,
                cdnHosts = target.remoteCdnHosts,
                cdnPath = target.remoteCdnPath,
                cdnServers = target.remoteCdnServers,
                keyRing = target.remoteKeyRing,
            )
            WowSwitch.replace(buildInfo, WowSwitch.withLaunchRow(text, remote).toByteArray())
        }
        updateGame(gameRoot, target, status)
        check(WowProduct.choose(gameRoot, today)?.product == WowProduct.LAUNCH) { "the World of Warcraft: Forever client is not complete" }
        Switch.Done(target.remoteVersion)
    }

    /** the patch server's row for the install's region ([key] = its region column; .build.info's Branch), else us's, else the first */
    internal fun remoteRow(rows: List<Map<String, String>>, key: String, region: String?): Map<String, String>? =
        rows.firstOrNull { !region.isNullOrBlank() && it[key].equals(region, ignoreCase = true) } ?: rows.firstOrNull { it[key] == "us" }
            ?: rows.firstOrNull()

    fun updateGame(gameRoot: File, target: VersionCheckResult, onStatus: (String) -> Unit) {
        val cdn = Cdn(
            hosts = target.remoteCdnHosts.ifEmpty { DEFAULT_CDN_HOSTS },
            path = target.remoteCdnPath.ifEmpty { DEFAULT_CDN_PATH },
        )
        onStatus("Fetching build ${target.remoteVersion} manifest...")
        val buildConfigRaw = cdn.require("config", target.remoteBuildConfig)
        val cdnConfigRaw = target.remoteCdnConfig.takeIf { it.isNotBlank() }?.let { cdn.require("config", it) }

        saveConfig(gameRoot, target.remoteBuildConfig, buildConfigRaw)
        cdnConfigRaw?.let { saveConfig(gameRoot, target.remoteCdnConfig, it) }

        val buildConfig = parseConfig(String(buildConfigRaw))
        val cdnConfig = cdnConfigRaw?.let { parseConfig(String(it)) }
        syncClient(
            gameRoot = gameRoot,
            product = target.product,
            cdn = cdn,
            buildConfig = buildConfig,
            archives = lazy { cdnConfig?.let { ArchiveLocator(cdn, gameRoot, it.getValue("archives")) } },
            onStatus = onStatus,
        )
        cdnConfig?.let { syncIndices(gameRoot, cdn, it, onStatus) }

        onStatus("Updating build information...")
        updateBuildInfo(gameRoot, target, buildConfig)
    }

    fun download(gameRoot: File, onStatus: (String) -> Unit) {
        val choice = activeBuild(gameRoot)
        val row = choice.row
        val product = checkNotNull(choice.product) {
            "Incompatible game product: ${row["Product"]}. Expected ${WowProduct.values().joinToString(" or ") { it.code }}."
        }
        val latest = checkVersion(gameRoot)
        if (latest != null && latest.remoteBuildConfig.isNotBlank()) return updateGame(gameRoot, latest, onStatus)

        val cdn = Cdn(hosts = splitHosts(row.getValue("CDN Hosts")), path = row.getValue("CDN Path"))
        onStatus("Reading build ${row["Version"].orEmpty()} manifest...")
        syncClient(
            gameRoot = gameRoot,
            product = product,
            cdn = cdn,
            buildConfig = parseConfig(String(cdn.require("config", row.getValue("Build Key")))),
            archives = lazy {
                ArchiveLocator(cdn, gameRoot, parseConfig(String(cdn.require("config", row.getValue("CDN Key")))).getValue("archives"))
            },
            onStatus = onStatus,
        )
    }

    private fun syncClient(
        gameRoot: File,
        product: WowProduct,
        cdn: Cdn,
        buildConfig: Map<String, List<String>>,
        archives: Lazy<ArchiveLocator?>,
        onStatus: (String) -> Unit,
    ) {
        val files = parseInstall(blteDecode(cdn.require("data", buildConfig.getValue("install")[1]))).filter { it.isClientBinary() }
        check(files.isNotEmpty()) { "No Windows ARM64 client files in this build" }

        val clientDir = File(gameRoot, product.flavorDir)
        // the exe LAST: it is what makes the folder run this client (WowProduct.choose), so a stop before it leaves the old one running
        var pending = files.filter { md5Hex(File(clientDir, it.relativePath)) != it.ckey }
            .sortedBy { it.relativePath.equals(product.exeName, ignoreCase = true) }
        if (pending.isEmpty()) return

        // the same file in the other client's folder (the beta's DLLs at the switch) is copied, not downloaded
        val copies = pending.mapNotNull { entry ->
            WowProduct.values().filter { it != product }.map { File(gameRoot, "${it.flavorDir}/${entry.relativePath}") }
                .firstOrNull { md5Hex(it) == entry.ckey }?.let { entry to it }
        }
        copies.forEachIndexed { index, (entry, from) ->
            onStatus("Copying ${entry.name} (${index + 1}/${copies.size})...")
            val dest = File(clientDir, entry.relativePath)
            dest.parentFile?.mkdirs()
            val temp = File(dest.parentFile, "${dest.name}.download")
            from.copyTo(temp, overwrite = true)
            if (md5Hex(temp) == entry.ckey) WowSwitch.move(temp, dest) else temp.delete()
        }
        pending = pending.filter { md5Hex(File(clientDir, it.relativePath)) != it.ckey }
        if (pending.isEmpty()) return

        onStatus("Looking up client files...")
        val ekeys = EncodingLookup(cdn, buildConfig.getValue("encoding")[1]).find(pending.map { it.ckey }.toSet())
        pending.forEachIndexed { index, entry ->
            onStatus("Downloading ${entry.name} (${index + 1}/${pending.size})...")
            val ekey = ekeys[entry.ckey] ?: throw IOException("${entry.name} is missing from the encoding table")
            val encoded = cdn.fetch("data", ekey) ?: archives.value?.read(ekey) ?: throw IOException("${entry.name} not found on the CDN")
            writeVerified(File(clientDir, entry.relativePath), entry, encoded)
        }
    }

    private fun writeVerified(dest: File, entry: InstallEntry, encoded: ByteArray) {
        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, "${dest.name}.download")
        val digest = MessageDigest.getInstance("MD5")
        DigestOutputStream(temp.outputStream().buffered(), digest).use { blteDecode(encoded, it) }
        if (hex(digest.digest(), 0, 16) != entry.ckey) {
            temp.delete()
            throw IOException("Checksum mismatch for ${entry.name}")
        }
        WowSwitch.move(temp, dest)
    }

    private fun saveConfig(gameRoot: File, key: String, bytes: ByteArray) {
        WowSwitch.replace(File(gameRoot, "Data/config/${key.substring(0, 2)}/${key.substring(2, 4)}/$key"), bytes)
    }

    private fun syncIndices(gameRoot: File, cdn: Cdn, cdnConfig: Map<String, List<String>>, onStatus: (String) -> Unit) {
        val indicesDir = File(gameRoot, "Data/indices")
        indicesDir.mkdirs()
        val required = listOf("archives", "patch-archives").flatMap { cdnConfig[it].orEmpty() } +
            listOf("file-index", "patch-file-index", "archive-group", "patch-archive-group").mapNotNull { cdnConfig[it]?.firstOrNull() }
        val missing = required.distinct().filter { !File(indicesDir, "$it.index").isFile }
        missing.forEachIndexed { index, hash ->
            onStatus("Syncing index archive (${index + 1}/${missing.size})...")
            cdn.fetch("data", "$hash.index")?.let { WowSwitch.replace(File(indicesDir, "$hash.index"), it) }
        }
    }

    private fun updateBuildInfo(gameRoot: File, target: VersionCheckResult, buildConfig: Map<String, List<String>> = emptyMap()) {
        val buildInfo = File(gameRoot, BUILD_INFO)
        if (!buildInfo.isFile) return
        val raw = buildInfo.readText()
        val nl = if (raw.contains("\r\n")) "\r\n" else "\n"
        val lines = raw.split(nl).dropLastWhile { it.isEmpty() }
        if (lines.isEmpty()) return
        val header = psvHeader(lines.first())
        val productIdx = header.indexOf("Product")
        val activeIdx = header.indexOf("Active")
        val updates = mapOf(
            "Version" to target.remoteVersion,
            "Build Key" to target.remoteBuildConfig,
            "CDN Key" to target.remoteCdnConfig,
            "CDN Hosts" to target.remoteCdnHosts.joinToString(" "),
            "CDN Path" to target.remoteCdnPath,
            "Install Key" to buildConfig["install"]?.getOrNull(1).orEmpty(),
            "IM Size" to buildConfig["install-size"]?.getOrNull(1).orEmpty(),
        ).mapNotNull { (column, value) -> header.indexOf(column).takeIf { it >= 0 && value.isNotBlank() }?.to(value) }

        val updated = lines.mapIndexed { idx, line ->
            if (idx == 0 || line.isBlank()) return@mapIndexed line
            val cols = line.split("|").toMutableList()
            val matchesProduct = productIdx < 0 || cols.getOrNull(productIdx) == target.product.code
            val isActive = activeIdx < 0 || cols.getOrNull(activeIdx) == "1"
            if (matchesProduct && isActive) updates.forEach { (i, value) -> if (i in cols.indices) cols[i] = value }
            cols.joinToString("|")
        }
        // whole or not at all, the other rows and the line ending as they were
        if (updated != lines) WowSwitch.replace(buildInfo, (updated.joinToString(nl) + nl).toByteArray())
    }

    private data class InstallEntry(val name: String, val ckey: String, val tags: Set<String>) {
        val relativePath get() = name.replace('\\', '/')

        fun isClientBinary(): Boolean {
            if ("Windows" !in tags || PLATFORM_ARCH !in tags || EXCLUDED_ARCH in tags) return false
            val lower = name.lowercase()
            return !lower.startsWith("utils\\") || "exceptionhandler" in lower
        }
    }

    private class Cdn(val hosts: List<String>, val path: String) {
        fun require(kind: String, key: String) = fetch(kind, key) ?: throw IOException("CDN is missing $kind/$key")

        fun fetch(kind: String, key: String, range: LongRange? = null): ByteArray? {
            var failure: IOException? = null
            var missing = false
            for (host in hosts) {
                val request = Request.Builder()
                    .url("http://$host/$path/$kind/${key.substring(0, 2)}/${key.substring(2, 4)}/$key")
                    .apply { if (range != null) header("Range", "bytes=${range.first}-${range.last}") }
                    .build()
                try {
                    Net.http.newCall(request).execute().use { response ->
                        when {
                            response.isSuccessful -> return response.body!!.bytes()
                            response.code == 403 || response.code == 404 -> missing = true
                            else -> failure = IOException("HTTP ${response.code} from $host")
                        }
                    }
                } catch (e: IOException) {
                    failure = e
                }
            }
            if (missing) return null
            throw failure ?: IOException("No CDN hosts in $BUILD_INFO")
        }
    }

    private class EncodingLookup(private val cdn: Cdn, private val ekey: String) {
        private data class Chunk(val encodedOffset: Long, val encodedSize: Int, val decodedOffset: Long, val decodedSize: Int)

        private val chunks: List<Chunk> = parseChunkTable(ByteBuffer.wrap(fetchEncoded(0, 8)).getInt(4))

        private fun parseChunkTable(headerSize: Int): List<Chunk> {
            val header = ByteBuffer.wrap(fetchEncoded(0, headerSize))
            val count = header.getInt(8) and 0xFFFFFF
            var encoded = headerSize.toLong()
            var decoded = 0L
            return (0 until count).map { i ->
                val compressedSize = header.getInt(12 + i * 24)
                val decompressedSize = header.getInt(16 + i * 24)
                Chunk(encoded, compressedSize, decoded, decompressedSize).also {
                    encoded += compressedSize
                    decoded += decompressedSize
                }
            }
        }

        private var cachedChunk: Chunk? = null
        private var cachedData: ByteArray = ByteArray(0)

        private fun read(start: Long, length: Int): ByteArray {
            val out = ByteArray(length)
            var written = 0
            for (chunk in chunks) {
                val chunkEnd = chunk.decodedOffset + chunk.decodedSize
                if (chunkEnd <= start || chunk.decodedOffset >= start + length) continue
                val from = maxOf(start, chunk.decodedOffset) - chunk.decodedOffset
                val count = (minOf(start + length, chunkEnd) - chunk.decodedOffset - from).toInt()
                val mode = fetchEncoded(chunk.encodedOffset, 1)[0].toInt().toChar()
                val bytes = if (mode == 'N') {
                    fetchEncoded(chunk.encodedOffset + 1 + from, count)
                } else {
                    decodedChunk(chunk).copyOfRange(from.toInt(), from.toInt() + count)
                }
                bytes.copyInto(out, written)
                written += count
            }
            return out
        }

        private fun decodedChunk(chunk: Chunk): ByteArray {
            if (cachedChunk != chunk) {
                cachedData = decodeChunk(fetchEncoded(chunk.encodedOffset, chunk.encodedSize))
                cachedChunk = chunk
            }
            return cachedData
        }

        private fun fetchEncoded(offset: Long, size: Int) =
            cdn.fetch("data", ekey, offset until offset + size) ?: throw IOException("Encoding file missing")

        fun find(wanted: Set<String>): Map<String, String> {
            val header = ByteBuffer.wrap(read(0, 22))
            val ckeySize = header.get(3).toInt()
            val ekeySize = header.get(4).toInt()
            val pageSize = (header.getShort(5).toInt() and 0xFFFF) * 1024
            val pageCount = header.getInt(9)
            val especSize = header.getInt(18)
            val indexStart = 22L + especSize
            val indexEntrySize = ckeySize + 16
            val index = read(indexStart, pageCount * indexEntrySize)
            val firstKeys = (0 until pageCount).map { hex(index, it * indexEntrySize, ckeySize) }
            val pagesStart = indexStart + pageCount.toLong() * indexEntrySize

            val found = mutableMapOf<String, String>()
            wanted.groupBy { ckey -> firstKeys.binarySearch(ckey).let { if (it >= 0) it else -it - 2 } }
                .filterKeys { it >= 0 }
                .forEach { (page, ckeys) ->
                    val data = read(pagesStart + page.toLong() * pageSize, pageSize)
                    var pos = 0
                    while (pos + 6 + ckeySize <= data.size && data[pos].toInt() != 0) {
                        val keyCount = data[pos].toInt() and 0xFF
                        val ckey = hex(data, pos + 6, ckeySize)
                        if (ckey in ckeys) found[ckey] = hex(data, pos + 6 + ckeySize, ekeySize)
                        pos += 6 + ckeySize + keyCount * ekeySize
                    }
                }
            return found
        }
    }

    private class ArchiveLocator(private val cdn: Cdn, private val gameRoot: File, private val archives: List<String>) {
        fun read(ekey: String): ByteArray? {
            for (archive in archives) {
                val local = File(gameRoot, "Data/indices/$archive.index")
                val index = if (local.isFile) {
                    local.readBytes()
                } else {
                    val fetched = cdn.fetch("data", "$archive.index") ?: continue
                    WowSwitch.replace(local, fetched)
                    fetched
                }
                val buffer = ByteBuffer.wrap(index)
                for (block in 0 until index.size / 4096 * 4096 step 4096) {
                    var pos = block
                    while (pos <= block + 4096 - 24) {
                        if (hex(index, pos, 16) == ekey) {
                            val size = buffer.getInt(pos + 16).toLong() and 0xFFFFFFFFL
                            val offset = buffer.getInt(pos + 20).toLong() and 0xFFFFFFFFL
                            return cdn.fetch("data", archive, offset until offset + size)
                        }
                        pos += 24
                    }
                }
            }
            return null
        }
    }

    private fun fetchText(url: String): String? =
        patchHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }

    /** the row the game runs and its client: [WowProduct.choose], the one order the launcher and the companion share */
    private fun activeBuild(gameRoot: File, today: java.time.LocalDate = java.time.LocalDate.now()): WowProduct.Choice {
        check(File(gameRoot, BUILD_INFO).isFile) { "Missing $BUILD_INFO in $gameRoot" }
        return WowProduct.choose(gameRoot, today) ?: throw IllegalStateException("No build in $BUILD_INFO")
    }

    private fun parsePsv(text: String): List<Map<String, String>> {
        val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val header = psvHeader(lines.firstOrNull() ?: return emptyList())
        return lines.drop(1).map { header.zip(it.split("|")).toMap() }
    }

    private fun psvHeader(line: String) = line.split("|").map { it.substringBefore("!") }

    private fun splitHosts(hosts: String) = hosts.split(" ").filter { it.isNotBlank() }

    private fun parseConfig(text: String): Map<String, List<String>> =
        text.lines().filter { "=" in it && !it.startsWith("#") }.associate { line ->
            line.substringBefore("=").trim() to line.substringAfter("=").trim().split(" ").filter { it.isNotEmpty() }
        }

    private fun parseInstall(data: ByteArray): List<InstallEntry> {
        check(data[0] == 'I'.code.toByte() && data[1] == 'N'.code.toByte()) { "Bad install manifest" }
        val buffer = ByteBuffer.wrap(data)
        val hashSize = data[3].toInt()
        val tagCount = buffer.getShort(4).toInt() and 0xFFFF
        val entryCount = buffer.getInt(6)
        val maskSize = (entryCount + 7) / 8
        var pos = 10

        fun readString(): String {
            val end = (pos until data.size).first { data[it].toInt() == 0 }
            return String(data, pos, end - pos).also { pos = end + 1 }
        }

        val tags = (0 until tagCount).map {
            val name = readString()
            pos += 2
            (name to data.copyOfRange(pos, pos + maskSize)).also { pos += maskSize }
        }
        return (0 until entryCount).map { i ->
            val name = readString()
            val ckey = hex(data, pos, hashSize)
            pos += hashSize + 4
            val entryTags = tags.filter { (_, mask) -> mask[i / 8].toInt() and (0x80 ushr (i % 8)) != 0 }.map { it.first }.toSet()
            InstallEntry(name, ckey, entryTags)
        }
    }

    private fun blteDecode(data: ByteArray) = ByteArrayOutputStream(data.size).also { blteDecode(data, it) }.toByteArray()

    private fun blteDecode(data: ByteArray, out: OutputStream) {
        check(String(data, 0, 4) == "BLTE") { "Not BLTE data" }
        val buffer = ByteBuffer.wrap(data)
        val headerSize = buffer.getInt(4)
        if (headerSize == 0) {
            out.write(decodeChunk(data.copyOfRange(8, data.size)))
            return
        }
        val count = buffer.getInt(8) and 0xFFFFFF
        var offset = headerSize
        for (i in 0 until count) {
            val compressedSize = buffer.getInt(12 + i * 24)
            out.write(decodeChunk(data.copyOfRange(offset, offset + compressedSize)))
            offset += compressedSize
        }
    }

    private fun decodeChunk(chunk: ByteArray): ByteArray {
        val body = chunk.copyOfRange(1, chunk.size)
        return when (chunk[0].toInt().toChar()) {
            'N' -> body
            'Z' -> inflate(body)
            'F' -> blteDecode(body)
            else -> throw IOException("Unsupported BLTE chunk mode ${chunk[0].toInt().toChar()}")
        }
    }

    private fun inflate(body: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(body)
        val out = ByteArrayOutputStream(body.size * 2)
        val buffer = ByteArray(64 * 1024)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw IOException("Truncated zlib chunk")
                out.write(buffer, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun md5Hex(file: File): String? {
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest(), 0, 16)
    }

    private fun hex(data: ByteArray, offset: Int, size: Int): String {
        val chars = CharArray(size * 2)
        for (i in 0 until size) {
            val v = data[offset + i].toInt() and 0xFF
            chars[i * 2] = HEX[v ushr 4]
            chars[i * 2 + 1] = HEX[v and 0xF]
        }
        return String(chars)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
