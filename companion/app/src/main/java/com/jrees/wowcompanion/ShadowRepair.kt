package com.jrees.wowcompanion

import java.io.File

/**
 * THE SHADOW FOLDER NEVER KEEPS THE GAME'S OWN FILES (Track C, test22; Tracking's ruling after the evidence: the shared WTF's character
 * folders were last written 10-07/10-08, the day [NativeLogin]'s private shadow folder arrived, and no WowCompanion*.lua exists in the
 * shared SavedVariables). The shadow links every entry of the shared game folder - but a REAL folder found there (one the client made
 * itself, e.g. WTF) was kept and never replaced, so everything the game saved since went to private storage, which an uninstall wipes.
 *
 * Now, before the links are made, every REAL entry in the shadow (WTF, Logs, Cache, Interface, Screenshots, Errors, anything) is MERGED
 * into the shared game folder: per file the NEWER copy wins in the shared folder; the older one is backed up (never deleted without a
 * backup) to [backupRoot] (Documents/WoW-AddOns-backup/shadow-<date>/); then the private entry goes and the caller links it. A link is
 * left as it is. Pure (java.io; the link test is passed in): tools/setup_test runs it.
 */
object ShadowRepair {
    /** [state]: "link" (as it should be), "missing", "real dir" / "real file" (merged); [merged] files now in the shared folder from the
     *  shadow, [backedUp] older copies kept in the backup, [failed] files that could not be moved (then the entry stays, nothing lost) */
    data class Result(val name: String, val state: String, val merged: Int = 0, val backedUp: Int = 0, val failed: Int = 0) {
        fun line(): String = "shadow $name: $state" + if (state.startsWith("real")) " ($merged files merged, $backedUp older kept in the backup" +
            (if (failed > 0) ", $failed NOT moved: kept in place" else "") + ")" else ""
    }

    /** [at] the entry in the shadow; [shared] the same entry in the shared game folder */
    fun repair(at: File, shared: File, backupRoot: File, isLink: (File) -> Boolean): Result {
        val name = at.name
        if (isLink(at)) return Result(name, "link")
        if (!at.exists()) return Result(name, "missing")
        var merged = 0; var backed = 0; var failed = 0
        fun one(src: File, dst: File, rel: String) {
            try {
                val bak = File(backupRoot, "$name/$rel")
                when {
                    !dst.exists() -> { copy(src, dst); merged++ }
                    src.lastModified() > dst.lastModified() -> { copy(dst, bak); backed++; copy(src, dst); merged++ }
                    else -> { copy(src, bak); backed++ }                      // the shared one is newer: the shadow's copy kept aside
                }
                check(src.delete()) { "delete" }
            } catch (_: Throwable) { failed++ }
        }
        if (at.isDirectory) {
            at.walkTopDown().filter { it.isFile }.toList().forEach { f -> one(f, File(shared, f.relativeTo(at).path), "${f.relativeTo(at).path}") }
            if (failed == 0) at.walkBottomUp().forEach { it.delete() }           // only empty folders are left by now
            return Result(name, "real dir", merged, backed, failed)
        }
        one(at, shared, name)
        return Result(name, "real file", merged, backed, failed)
    }

    private fun copy(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.path + ".part")
        src.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        tmp.setLastModified(src.lastModified())
        if (dst.exists() && !dst.delete()) { tmp.delete(); error("replace") }
        check(tmp.renameTo(dst)) { "rename" }
    }

    /** the backup folder for a repair started at [nowMs] */
    fun backupRoot(documents: File, nowMs: Long): File =
        File(documents, "WoW-AddOns-backup/shadow-" + java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss", java.util.Locale.US).format(java.util.Date(nowMs)))
}
