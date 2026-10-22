package com.jrees.wowcompanion

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/**
 * THE APP MOVES THE PLAYER FROM THE BETA TO THE LAUNCH CLIENT BY ITSELF (2026-10-21, the user's pick "App switches itself": the beta
 * closed, players saw "no realms", and [WowProduct.choose] kept a beta-only folder on the beta for ever).
 *
 * THE TRIGGER ([due]): the folder runs the beta, and it is launch day or after ([WowProduct.betaOver]: [WowProduct.LAUNCH_DAY], 4 Nov 2026 local date; the user's "Beta until launch"). The
 * updater then also needs the launch client's patch server to ANSWER (wowf/versions has a row): no answer, no change. Both together:
 * the date alone would switch a player whose wowf is not there yet; the answer alone would switch during the beta.
 *
 * THE MOVE (WowClientDownloader.switchToLaunch, LAUNCH_PLAN.md's shared Data): the wowf row is ADDED to .build.info ([withLaunchRow]:
 * every other line byte for byte, the beta row kept), then the wowf client (its _forever_ exe and files, the indices of its CDN config)
 * is synced the way a game update is, the exe LAST. Until that exe is on disk [WowProduct.choose] still picks the beta (only its exe is
 * installed), so a killed app finds the folder still [due] and simply goes on: a file already right is skipped, a half-written one is
 * only ever a temp file ([replace]). Pure apart from [replace] / [due] (reads .build.info).
 */
object WowSwitch {
    /** the player's line while the move runs (the updater's status, the top screen's busy line) */
    const val STATUS = "Moving to World of Warcraft: Forever…"

    /** [choice] (the folder's) on [today] calls for the move */
    fun due(choice: WowProduct.Choice?, today: LocalDate = LocalDate.now()): Boolean =
        choice?.product == WowProduct.BETA && WowProduct.betaOver(today)

    /** [due] for the game folder [root] */
    fun due(root: File, today: LocalDate = LocalDate.now()): Boolean = due(WowProduct.choose(root, today), today)

    /** the launch client's build as its patch server gives it (versions + cdns, the install's region) */
    data class Remote(
        val version: String,
        val buildConfig: String,
        val cdnConfig: String,
        val cdnHosts: List<String>,
        val cdnPath: String,
        val cdnServers: String? = null,
        val keyRing: String? = null,
    )

    /**
     * .build.info [text] with the launch client's row: an existing wowf row is replaced in place (a second one goes), else a new one is
     * added after the last row. The new row is the beta row's (its Branch, Tags, Armadillo...) with the launch build's values. Every
     * other line, and the file's line ending, stay exactly as they were.
     */
    fun withLaunchRow(text: String, remote: Remote): String {
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split(nl).toMutableList()
        val trailing = lines.isNotEmpty() && lines.last().isEmpty()
        if (trailing) lines.removeAt(lines.size - 1)
        val headIdx = lines.indexOfFirst { it.isNotBlank() && !it.startsWith("#") }
        if (headIdx < 0) throw IOException("${WowProduct.BUILD_INFO} has no header")
        val head = lines[headIdx].split('|').map { it.substringBefore('!') }
        fun col(name: String) = head.indexOf(name)
        val productIdx = col("Product")
        if (productIdx < 0) throw IOException("${WowProduct.BUILD_INFO} has no Product column")
        fun cols(line: String) = line.split('|').toMutableList().also { while (it.size < head.size) it += "" }
        fun productOf(i: Int) = if (i <= headIdx || lines[i].isBlank() || lines[i].startsWith("#")) null else cols(lines[i])[productIdx]

        val rowIdx = lines.indices.filter { productOf(it) != null }
        val beta = rowIdx.filter { productOf(it) == WowProduct.BETA.code }
            .let { b -> b.firstOrNull { cols(lines[it]).getOrNull(col("Active")) == "1" } ?: b.firstOrNull() }
        val row = beta?.let { cols(lines[it]) } ?: MutableList(head.size) { "" }
        fun set(name: String, value: String?) { val i = col(name); if (i >= 0 && value != null) row[i] = value }
        set("Active", "1")
        set("Build Key", remote.buildConfig)
        set("CDN Key", remote.cdnConfig)
        set("Install Key", "")            // the updater's build-info step fills it from the build config
        set("IM Size", "")
        set("CDN Path", remote.cdnPath)
        set("CDN Hosts", remote.cdnHosts.joinToString(" "))
        remote.cdnServers?.takeIf { it.isNotBlank() }?.let { set("CDN Servers", it) }
        set("Last Activated", "")
        set("Version", remote.version)
        set("KeyRing", remote.keyRing.orEmpty())
        row[productIdx] = WowProduct.LAUNCH.code
        val line = row.joinToString("|")

        val launch = rowIdx.filter { productOf(it) == WowProduct.LAUNCH.code }
        if (launch.isEmpty()) {
            lines.add((rowIdx.lastOrNull() ?: headIdx) + 1, line)
        } else {
            lines[launch.first()] = line
            launch.drop(1).sortedDescending().forEach { lines.removeAt(it) }
        }
        return lines.joinToString(nl) + if (trailing) nl else ""
    }

    /**
     * [dest] becomes [bytes] whole or not at all: written to a temp file beside it, flushed to disk, then renamed over it (a kill
     * leaves the old file and at most the temp, which the next write replaces).
     */
    fun replace(dest: File, bytes: ByteArray) {
        dest.absoluteFile.parentFile?.mkdirs()
        val tmp = File(dest.absoluteFile.parentFile, "${dest.name}.tmp")
        java.io.FileOutputStream(tmp).use { out -> out.write(bytes); out.fd.sync() }
        move(tmp, dest)
    }

    /** [from] renamed over [to], atomically where the file system can */
    fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * The status line during the move: [STATUS], a rough percent of the whole move, and the updater's own step ([msg]). The file
     * steps ("(3/7)") are spread over the download's share.
     */
    fun line(msg: String): String {
        val n = Regex("""\((\d+)/(\d+)\)""").find(msg)?.destructured?.let { (a, b) -> a.toInt() to b.toInt().coerceAtLeast(1) }
        val pct = when {
            msg.startsWith("Checking") -> 2
            msg.startsWith("Fetching build") || msg.startsWith("Reading build") -> 8
            msg.startsWith("Looking up") -> 15
            msg.startsWith("Copying") || msg.startsWith("Downloading") -> 20 + 65 * ((n?.first ?: 1) - 1) / (n?.second ?: 1)
            msg.startsWith("Syncing index") -> 85 + 10 * ((n?.first ?: 1) - 1) / (n?.second ?: 1)
            msg.startsWith("Updating build") -> 97
            else -> null
        }
        return if (pct == null) "$STATUS $msg" else "$STATUS $pct% · $msg"
    }
}
