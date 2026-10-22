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

    /**
     * NEVER THE SHARED FOLDER'S OWN FILES (test57, the launch plan's hazard live: the repair walked a shadow folder that was a LINK into
     * the shared game folder and moved the real game - WowB-ARM64.exe, WTF, Interface - into the backup): whether [f]'s real path lies
     * inside [sharedRoot]'s (links and junctions resolved). Unknown counts as inside: refused.
     */
    fun inside(f: File, sharedRoot: File): Boolean = runCatching {
        real(f).startsWith(real(sharedRoot))
    }.getOrDefault(true)

    /** [f]'s real path: every link and junction on the way resolved (toRealPath; for a path not there yet, its nearest existing parent's
     *  real path and the rest - canonicalFile alone does not resolve a Windows junction) */
    private fun real(f: File): java.nio.file.Path {
        var p = f.absoluteFile.toPath(); val rest = ArrayList<String>()
        while (!java.nio.file.Files.exists(p)) { rest.add(0, p.fileName.toString()); p = p.parent ?: return f.canonicalFile.toPath() }
        var r = p.toRealPath(); for (n in rest) r = r.resolve(n)
        return r.normalize()
    }

    /** [at] the entry in the shadow; [shared] the same entry in the shared game folder; [sharedRoot] the shared game folder itself:
     *  an [at] whose real path is inside it is REFUSED (never moved, never deleted) */
    fun repair(at: File, shared: File, backupRoot: File, isLink: (File) -> Boolean, sharedRoot: File? = null): Result {
        val name = at.name
        if (isLink(at)) return Result(name, "link")
        if (!at.exists()) return Result(name, "missing")
        if (sharedRoot != null && inside(at, sharedRoot)) return Result(name, "REFUSED: it is the shared game folder's own (nothing moved)")
        var merged = 0; var backed = 0; var failed = 0
        fun one(src: File, dst: File, rel: String) {
            try {
                // the same file by two names (a link on the way): never a copy of itself, never a delete
                if (dst.exists() && runCatching { java.nio.file.Files.isSameFile(src.toPath(), dst.toPath()) }.getOrDefault(true)) { failed++; return }
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

    /**
     * THE SHADOW'S OWN FLAVOR FOLDER, made safe before anything walks it ([NativeLogin.prepare]): a flavor folder in the shadow [root]
     * that is a link ([isLink]) is unlinked (the link only - test55's launch run linked the beta's SHARED folder in as an ordinary
     * entry); one whose real path is inside [gameRoot] all the same (a junction, a bind) is REFUSED - an exception, the shadow not
     * built, nothing touched. Returns the private folder for [flavor].
     */
    fun flavorFolder(root: File, flavor: String, gameRoot: File, flavors: Set<String>, isLink: (File) -> Boolean): File {
        for (n in flavors + flavor) {
            val f = File(root, n)
            if (isLink(f)) f.delete()                                              // the link itself (File.delete never follows one)
            if (f.exists() && inside(f, gameRoot)) error("the shadow's $n is the shared game folder's own (${f.canonicalPath}): not built")
        }
        val fl = File(root, flavor).apply { mkdirs() }
        if (inside(fl, gameRoot)) error("the shadow's $flavor resolves into the shared game folder: not built")
        return fl
    }

    /** the shared game folder's entries the shadow root links: all but the clients' own flavor folders (only the running client's is
     *  in the shadow, as a private folder of links - never a link to a shared flavor folder) */
    fun rootLinks(gameRoot: File, flavors: Set<String>): List<File> = gameRoot.listFiles()?.filter { it.name !in flavors }.orEmpty()

    /** the backup folder for a repair started at [nowMs] */
    fun backupRoot(documents: File, nowMs: Long): File =
        File(documents, "WoW-AddOns-backup/shadow-" + java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss", java.util.Locale.US).format(java.util.Date(nowMs)))
}
