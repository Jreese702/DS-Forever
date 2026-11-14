package com.jrees.wowcompanion

/**
 * WINDOWS ID, THE SETTING (the user, 2026-11-14: "build it"; Battle.net's BLZ51914400 wave, after upstream's same-day A/B "Windows ID:
 * Original (v2.3.3)"): Settings > General > Account > Windows ID - which Windows machine identity the game's container shows Battle.net,
 * from the next game start.
 *
 * - [PER_INSTALL] (the default; what 1.0.25 - 1.0.32 always did): this install's own random identity (app.gamenative.utils.MachineIdentity:
 *   MachineGuid, computer name DESKTOP-XXXXXXX, C: volume serial, ProductId, InstallDate; made once, kept in the app's private storage)
 *   written into the WoW prefix at every start, and the DNS shim's gethostname() answering that computer name (WOWFOREVER_HOSTNAME).
 * - [ORIGINAL] (v2.3.3 and DS Forever before 1.0.25): the prebuilt prefix's shared values back in place - the MachineGuid, ProductId and
 *   InstallDate every install ships with, the prefix's own computer-name keys, no C: serial file of ours - and no WOWFOREVER_HOSTNAME,
 *   so the shim reports the device's current address as the host name again (Wine's computer name follows the network). The
 *   per-install identity file is KEPT (never read, never deleted), so going back to Per install brings the same identity back.
 *
 * The launcher applies the chosen one at each game start (MachineIdentityApply.applyToContainer) and says which ran ([markLaunched]):
 * the Settings row offers "Restart the game" while the running game started with the other one, and the problem report says which one
 * the last start used ([reportLine]). No identity VALUE is ever logged or reported, only the mode's name. Pure, but for the two volatile
 * fields the launcher sets.
 */
object WindowsId {
    const val PER_INSTALL = "per_install"
    const val ORIGINAL = "original"
    const val DEFAULT = PER_INSTALL

    /** the saved choice's key (Settings' prefs) */
    const val KEY = "windowsId"
    /** what the last game start used, kept across app restarts (the report made after a crash still says) */
    const val KEY_RAN = "windowsIdRan"
    const val KEY_RAN_AT = "windowsIdRanAt"
    const val KEY_RAN_OK = "windowsIdRanOk"

    /** the Settings row's own words (SettingsTabs) */
    const val ROW_ID = "Windows ID"
    /** the segments, in [VALUES]' order */
    val VALUES = listOf(PER_INSTALL, ORIGINAL)
    val SEGMENTS = listOf("Per install", "Original")
    /** the restart row's line (its name is Frames' "Restart the game": the same launcher restart) */
    const val RESTART_LINE = "To use the new Windows ID"

    /** a saved or passed value as one of [VALUES] (anything else = the default) */
    fun sanitize(v: String?): String = if (v == ORIGINAL) ORIGINAL else PER_INSTALL

    /** the segment's words for [mode] */
    fun label(mode: String?): String = SEGMENTS[VALUES.indexOf(sanitize(mode))]

    // ---- what the running game started with (the launcher, at each game start)

    /** the mode the running game's start applied; null = no game started in this process */
    @Volatile var launched: String? = null
        private set
    @Volatile var launchedAtMs = 0L
        private set
    /** the apply's outcome for the report: "applied, N value(s) changed" / "could not be applied (IOException)" */
    @Volatile var launchedResult = ""
        private set

    private val listeners = ArrayList<() -> Unit>()
    fun onChange(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    private fun changed() { for (l in synchronized(listeners) { listeners.toList() }) runCatching { l() } }

    /** the launcher: this game start applied [mode] ([result] = the report's words for how it went) */
    fun markLaunched(mode: String, result: String, atMs: Long = System.currentTimeMillis()) {
        launched = sanitize(mode); launchedAtMs = atMs; launchedResult = result
        changed()
    }

    /** the off-device tests' way back to "no game started in this process" */
    fun forgetLaunchForTest() { launched = null; launchedAtMs = 0L; launchedResult = "" }

    /** a restart would change the identity the running game shows */
    fun needsRestart(launched: String?, chosen: String): Boolean = launched != null && sanitize(launched) != sanitize(chosen)

    /** the "Restart the game" row under Windows ID: the game runs, it started with the other identity, and no restart is under way */
    fun showRestart(gameUp: Boolean, restarting: Boolean, launched: String?, chosen: String): Boolean =
        gameUp && !restarting && needsRestart(launched, chosen)

    /** the launcher's words for one apply ([changed] = values written; null = it failed with [error]) */
    fun result(changed: Int?, error: String? = null): String =
        if (changed != null) "applied, $changed value(s) changed" else "could not be applied (${error ?: "?"})"

    /**
     * the problem report's line (StartupDiag, GPU and container): the choice, what this run's game start used, else what the last
     * start recorded ([ranAtMs] 0 = none). Mode names only.
     */
    fun reportLine(chosen: String, launched: String?, launchedAtMs: Long, launchedResult: String,
                   ran: String?, ranAtMs: Long, ranOk: Boolean?, stamp: (Long) -> String): String {
        val now = when {
            launched != null -> "this run's game start used ${label(launched)} at ${stamp(launchedAtMs)}" +
                (if (launchedResult.isNotEmpty()) " ($launchedResult)" else "") +
                (if (needsRestart(launched, chosen)) "; a restart is due" else "")
            ran != null && ranAtMs > 0L -> "no game start in this run; the last one used ${label(ran)} at ${stamp(ranAtMs)}" +
                (if (ranOk == false) " (it could not be applied)" else "")
            else -> "no game start recorded"
        }
        return "Windows ID: ${label(chosen)} chosen; $now"
    }
}
