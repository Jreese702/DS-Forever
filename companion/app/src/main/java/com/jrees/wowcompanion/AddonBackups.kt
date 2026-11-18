package com.jrees.wowcompanion

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * THE ADD-ON BACKUPS ARE CAPPED (the user's ruling 2026-11-18: "cap the add-on backups inside the game folders at the NEWEST 2 PER
 * ADD-ON"; it overrides the older "never deleted" rule of ThirdPartyAddons / DependencyAddons / ImportedAddons for these backups only).
 *
 * The game folder's Interface/AddOns-backup-dsforever ([DependencyAddons.BACKUP]: every replaced or removed managed / imported add-on
 * folder, "<folder>-<version>-<yyyyMMdd-HHmmss>[~n]", the stamp = when it was moved there): for each add-on folder the newest [KEEP]
 * stay, the older ones are deleted. Never one less than [YOUNG_MS] old (its stamp; a stamp in the future counts as young), never while
 * the game runs (checked before each delete), never anything of another shape, never a file or a link, never anything outside that one
 * backup folder (AddOns, staging, the player's own things are never looked at). Run by [ThirdPartyAddons.applyAtStart] (the game
 * closed, under its lock), before and after the moves. Desktop does the same on the PC (PcAddons.CapBackups).
 */
object AddonBackups {
    const val KEEP = 2
    const val YOUNG_MS = 24 * 3600_000L
    /** "<folder>-<word>-<stamp>[~n]": the folder = everything before the last word (a version word with a '-' splits further in, so a
     *  group is only ever one add-on's backups or fewer) */
    private val NAME = Regex("""^(.+)-([A-Za-z0-9._]+)-(\d{8}-\d{6})(?:~(\d{1,6}))?$""")

    private class B(val dir: File, val at: Long, val n: Int)

    /** the deleted backups' names */
    fun cap(flavorRoot: File, now: Long = System.currentTimeMillis(), gameRunning: () -> Boolean = DependencyAddons.gameRunning): List<String> {
        val home = File(flavorRoot, "Interface/${DependencyAddons.BACKUP}")
        if (!home.isDirectory || gameRunning()) return emptyList()
        val homeC = home.canonicalFile
        val fmt = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).apply { isLenient = false }
        val groups = HashMap<String, ArrayList<B>>()
        for (d in home.listFiles() ?: return emptyList()) {
            val m = NAME.matchEntire(d.name) ?: continue
            if (!Files.isDirectory(d.toPath(), LinkOption.NOFOLLOW_LINKS)) continue           // a file or a link: never
            val at = runCatching { fmt.parse(m.groupValues[3])?.time }.getOrNull() ?: continue
            val n = m.groupValues[4].toIntOrNull() ?: 1
            groups.getOrPut(m.groupValues[1].lowercase()) { ArrayList() }.add(B(d, at, n))
        }
        val gone = ArrayList<String>()
        for ((folder, l) in groups)
            for (b in l.sortedWith(compareByDescending<B> { it.at }.thenByDescending { it.n }).drop(KEEP)) {
                if (now - b.at < YOUNG_MS) continue                                             // under a day old (or from the future)
                if (b.dir.canonicalFile.parentFile != homeC) continue
                if (gameRunning()) return gone
                if (remove(b.dir)) { gone.add(b.dir.name); log("add-ons: old backup ${b.dir.name} deleted (newest $KEEP of $folder kept)") }
                else log("add-ons: old backup ${b.dir.name} could not be deleted")
            }
        return gone
    }

    private fun isLink(f: File) = runCatching { Files.isSymbolicLink(f.toPath()) }.getOrDefault(true)

    /** [f] and everything under it; a link inside is removed as itself, never walked */
    private fun remove(f: File): Boolean {
        var ok = true
        if (!isLink(f) && f.isDirectory) for (c in f.listFiles().orEmpty()) if (!remove(c)) ok = false
        return f.delete() && ok
    }

    private fun log(line: String) { runCatching { android.util.Log.i("WCSETUP", line) } }
}
