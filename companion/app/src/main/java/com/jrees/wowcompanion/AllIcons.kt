package com.jrees.wowcompanion

/**
 * EVERY ICON, IN THE BACKGROUND (the user 2026-10-12: "a lot of the Icons and stuff in the game are showing blank before I open them"
 * -> "All icons, in the background"). After the required art, an OPTIONAL harvest step reads every icon the client has from the
 * player's own game files - the list is the game's own interface manifest (ManifestInterfaceData: every Interface\Icons texture; PC
 * measurement 2026-10-12: 30,417 icons, ~51.5 MB at 64 px WebP, ~2 min on one PC thread) - into packs of [CHUNK] in art/icons_all/
 * (WCI1, the icons.pak layout), which [Icons.get] reads after the required pack. Never blocks Play; waits while the player fights
 * ([busy]); a finished pack is never redone (a kill resumes at the next one); a new game build re-reads them once (the names carry
 * the build: the native reader has no content key, so a changed picture under an old id is caught that way). Nothing ships in the APK.
 */
object AllIcons {
    const val DIR = "icons_all"
    const val CHUNK = 1000
    /** ManifestInterfaceData: every interface file's id and path */
    const val MANIFEST = 1375801
    const val PAUSE_MS = 400L

    @Volatile var done = 0; private set
    @Volatile var total = 0; private set
    @Volatile var finished = false; private set
    /** the host: true while the game should not share the phone (a fight) */
    @Volatile var busy: () -> Boolean = { false }

    /** the manifest's rows (id, path) -> every icon id, ascending */
    fun iconIds(rows: List<Pair<Int, String>>): List<Int> =
        rows.filter { (_, p) -> p.replace('/', '\\').lowercase().startsWith("interface\\icons\\") }.map { it.first }.distinct().sorted()

    fun chunks(ids: List<Int>): List<List<Int>> = ids.chunked(CHUNK)

    /** a pack's name: its place, and a hash of the game build and its ids (a new build or another list = a new name) */
    fun chunkName(build: String, index: Int, ids: List<Int>): String {
        var h = 1125899906842597L
        for (c in build) h = 31 * h + c.code
        for (i in ids) h = 31 * h + i
        return "%03d-%016x.pak".format(index, h)
    }

    /** the top screen's line: null before the step is known; never blocks Play */
    fun line(readyBefore: Boolean): String? = when {
        finished || readyBefore -> "Icons: all icons ready"
        total > 0 -> "Icons: $done / $total (background)"
        else -> null
    }

    /** the step itself: [AllIconsStep] (the device part) */
    internal fun progress(d: Int, t: Int) { done = d; total = t }
    internal fun finish(f: Boolean) { finished = f }
}
