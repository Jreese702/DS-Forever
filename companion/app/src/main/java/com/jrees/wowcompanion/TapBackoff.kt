package com.jrees.wowcompanion

/**
 * PR #533 (mummigast): BACK OFF AND PARK A TAP THE GAME NEVER ANSWERS. GameUi's "uisw" switches and its HUD-height steps retried every
 * 1.5 s for as long as the game's word stayed wrong; while streaming with a WoW window holding the cursor every retry was a stray click,
 * for minutes, and one stuck switch held up the ones after it.
 *
 * Per key (a switch part, or "hud"): the game's answer is the key's own SIGNATURE (the part's word and its `set` flag; the HUD's
 * selected setting and values), so a tap counts as answered only when THAT moved - not when any tap on the row bumped the hub's count
 * (the PR review: a layout or HUD-height answer used to reset the switch's misses, and a rejected tap bumps the count too). Up to
 * [missMax] taps go [retryMs] apart; after that the key is PARKED ([parked]: the switches after it still go) and gets exactly ONE probe
 * tap every [slowMs] until it is answered. An answer (or [clear], a fresh login) forgets the key. Pure; time is passed in.
 */
class TapBackoff(private val missMax: Int = 3, private val retryMs: Long = 1_500L, private val slowMs: Long = 30_000L) {
    private class Try(val sig: Any?, val taps: Int, val at: Long)
    private val tries = HashMap<String, Try>()

    /** forget every key whose signature moved since its last tap (the game answered it, or a /reload reset it) */
    fun answered(sigOf: (String) -> Any?) { tries.entries.removeAll { sigOf(it.key) != it.value.sig } }

    /** the keys sitting out their slow wait: [missMax] unanswered taps, the last one's [retryMs] gone, its [slowMs] not yet */
    fun parked(now: Long): Set<String> =
        tries.filter { (_, t) -> t.taps >= missMax && now - t.at > retryMs && now - t.at < slowMs }.keys

    /** may [key] be tapped now: never tapped (or answered), [retryMs] since an unanswered tap, or (parked) its one probe after [slowMs] */
    fun due(key: String, now: Long): Boolean {
        val t = tries[key] ?: return true
        return if (t.taps >= missMax) now - t.at >= slowMs else now - t.at > retryMs
    }

    /** [key] was tapped at [now] while the game's answer read [sig] */
    fun tapped(key: String, sig: Any?, now: Long) { tries[key] = Try(sig, (tries[key]?.taps ?: 0) + 1, now) }

    /** unanswered taps of [key] so far (tests) */
    fun taps(key: String): Int = tries[key]?.taps ?: 0

    fun clear() = tries.clear()
}
