package com.jrees.wowcompanion

import java.io.File
import java.security.MessageDigest

/**
 * DAMAGED GAME TABLES IN THE APP'S CACHE (p-casclib-1000, a player's report 2026-10-14: "the bottom screen crashed and now ... game art
 * failed casclib error 1000"). CascLib's 1000 is ERROR_BAD_FORMAT: a file it parsed was malformed. The likeliest one is in the app's
 * own download cache (cacheDir/casc: the build's configs, ENCODING / ROOT / DOWNLOAD tables, archive indexes), so GameFiles drops that
 * cache ONCE per run and opens again ([shouldHeal]); still failing, the message says plainly what to do ([damagedMessage]). Every
 * download is checked before it is used ([problem]). Pure: the off-device tests drive it.
 */
object CascHeal {
    /** CascLib's port codes: ERROR_BAD_FORMAT, ERROR_HANDLE_EOF (a file shorter than its header says), ERROR_FILE_CORRUPT */
    const val BAD_FORMAT = 1000
    const val HANDLE_EOF = 1002
    const val FILE_CORRUPT = 1004
    val DAMAGED = setOf(BAD_FORMAT, HANDLE_EOF, FILE_CORRUPT)

    fun damaged(err: Int): Boolean = err in DAMAGED

    /** the cache is dropped and the open tried again: a damaged-file error (or a read that saw one), once per run */
    fun shouldHeal(err: Int, healedThisRun: Boolean, readSawDamage: Boolean = false): Boolean =
        !healedThisRun && (damaged(err) || readSawDamage)

    fun name(err: Int): String = when (err) {
        BAD_FORMAT -> "bad format"
        HANDLE_EOF -> "a file ends early"
        FILE_CORRUPT -> "a file is corrupt"
        else -> "error $err"
    }

    fun damagedMessage(err: Int): String =
        "The game's files on this device look damaged: copy the Data folder again (CascLib error $err: ${name(err)})"

    /**
     * Whether a downloaded cache file is whole before it is used (null = it is): the length the server announced, a CDN config's
     * name (the MD5 of its content), a loose data file's BLTE magic, an archive index's footer. [rel] is its CDN path.
     */
    fun problem(rel: String, file: File, announced: Long = -1L): String? {
        val len = file.length()
        if (len <= 0L) return "empty"
        if (announced > 0 && len != announced) return "$len of $announced bytes"
        val name = rel.substringAfterLast('/')
        return when {
            rel.startsWith("config/") && Regex("[0-9a-f]{32}").matches(name) ->
                if (md5(file) == name) null else "its content does not match its name"
            rel.endsWith(".index") -> indexProblem(file)
            rel.startsWith("data/") || rel.startsWith("patch/") -> {
                val head = ByteArray(4)
                file.inputStream().use { var o = 0; while (o < 4) { val n = it.read(head, o, 4 - o); if (n < 0) break; o += n } }
                if (head.contentEquals("BLTE".toByteArray())) null else "not a BLTE file"
            }
            else -> null
        }
    }

    /** an archive index's footer: checksum size 1..16 (as CdnArchives reads it), sane key / size / offset widths, room for blocks */
    private fun indexProblem(file: File): String? {
        val len = file.length()
        if (len < 64) return "too short for an index"
        java.io.RandomAccessFile(file, "r").use { f ->
            val probe = ByteArray(1); f.seek(len - 13); f.readFully(probe)
            val chk = probe[0].toInt() and 0xff
            if (chk !in 1..16) return "not an archive index"
            val footer = ByteArray(chk + 12 + chk)
            if (footer.size >= len) return "not an archive index"
            f.seek(len - footer.size); f.readFully(footer)
            val o = chk + 3
            val blockKb = footer[o].toInt() and 0xff
            val offsetBytes = footer[o + 1].toInt() and 0xff
            val sizeBytes = footer[o + 2].toInt() and 0xff
            val keyBytes = footer[o + 3].toInt() and 0xff
            if ((footer[o + 4].toInt() and 0xff) != chk || blockKb == 0 || keyBytes !in 1..16 || offsetBytes !in 1..8 || sizeBytes !in 1..8)
                return "unexpected index footer"
            val blocks = (len - footer.size) / (blockKb * 1024L + keyBytes + chk)
            if (blocks <= 0) return "an index with no blocks"
        }
        return null
    }

    private fun md5(file: File): String {
        val d = MessageDigest.getInstance("MD5")
        file.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; d.update(b, 0, n) } }
        return d.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
