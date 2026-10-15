package com.jrees.wowcompanion

/**
 * INDOOR-MAP PROGRESS IN SETTINGS (board f-map-tracker; the user's pick for 1.0.8): Settings > Map shows how many buildings are
 * ready, what is building now and what was deferred, from the bake's own status (IndoorScheduler.Status, status.json). The
 * buildings have no names in the game's files (a key: root file and unit), and the bake goes nearest-first, so "building now" is
 * how far the one being built is from the player. Pure (no Android): the off-device tests read it.
 */
internal object IndoorProgress {
    /** the deferred buildings Settings lists at most */
    const val LIST_MAX = 20
    private const val WHY_MAX = 80

    /** "building now" from the yards between the player and the building ([yd] null: the player's place unknown) */
    fun nowText(yd: Double?): String = when {
        yd == null || yd.isNaN() -> "one building"
        yd.isInfinite() -> "one on another map"
        yd < 1.0 -> "the one you are in"
        else -> "one ${Math.round(yd)} yd from you"
    }

    /** one deferred building as Settings lists it */
    fun deferredLine(key: String, why: String): String {
        val w = why.lineSequence().firstOrNull()?.trim().orEmpty().ifEmpty { "failed" }
        return "$key: " + if (w.length > WHY_MAX) w.take(WHY_MAX - 1) + "…" else w
    }

    /** the progress row's line; [buildOn] = Settings' "build in the background" switch */
    fun line(s: IndoorScheduler.Status?, buildOn: Boolean): String {
        if (s == null) return if (buildOn) "Not started: it starts after Play." else "Off: nothing is built."
        val count = if (s.total > 0) "${s.ready} / ${s.total} ready" else "listing buildings"
        val parts = ArrayList<String>()
        parts.add(count)
        when (s.phase) {
            IndoorScheduler.Phase.CATALOGUE -> if (s.total > 0) parts.add("listing buildings")
            IndoorScheduler.Phase.BAKING -> parts.add(if (s.now.isNotEmpty()) "building ${s.now}" else "building")
            IndoorScheduler.Phase.PAUSED -> parts.add("paused: ${s.note.ifEmpty { "waiting" }}")
            IndoorScheduler.Phase.DONE -> parts.add("all built")
            IndoorScheduler.Phase.DEFERRED -> parts.add("finished")
            IndoorScheduler.Phase.STOPPED -> parts.add(if (!buildOn) "off in Settings" else s.note.ifEmpty { "stopped" })
        }
        if (s.deferred > 0) parts.add("${s.deferred} deferred")
        return parts.joinToString(" · ")
    }
}
