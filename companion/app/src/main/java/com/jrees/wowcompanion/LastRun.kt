package com.jrees.wowcompanion

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences

/**
 * DID THE LAST SESSION END BADLY? (the user, 2026-11-08: "We need to give players a way to report crashes from the main app, so like if
 * their last run crashed, then it submits a log of any of the details that we could possibly use"; pick "Ask: one-tap Send").
 *
 * Two witnesses, both kept across the app's own death:
 *  - a "the game is running" mark in our prefs, set at the game's start and cleared at its end (GameLife): still there at the next
 *    start = DS Forever died while the game ran;
 *  - Android's own record of how this app's last process ended (ApplicationExitInfo, API 30): a crash, a native crash, a freeze (ANR),
 *    Android's low-memory kill or a signal (#37: "LOW_MEMORY ... pss 1256 MB").
 * A swipe-away, a force stop by the player or our own clean exit is no crash. The start screen then asks "Your last session ended
 * unexpectedly" with Send report / Not now; Send builds the ordinary problem report (it already carries the exit records, the game's
 * crash files, the DXVK log and the last logs, through the privacy pass).
 */
object LastRun {
    private var prefs: SharedPreferences? = null
    /** the start screen's question, or null: what ended the last session, in words */
    @Volatile var pending: String? = null
        private set

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences("dsf_last_run", Context.MODE_PRIVATE)
        runCatching { check(ctx.applicationContext) }.onFailure { android.util.Log.w("WCLASTRUN", "check: ${it.message}") }
    }

    // ---------------------------------------------------------------- the game's mark (GameLife)
    fun gameStarted(now: Long = System.currentTimeMillis()) { prefs?.edit()?.putLong("running", now)?.putBoolean("world", false)?.apply() }
    fun gameInWorld() { prefs?.edit()?.putBoolean("world", true)?.apply() }
    fun gameEnded() { prefs?.edit()?.remove("running")?.remove("world")?.apply() }

    fun dismiss() { pending = null }

    /** the reasons that are a crash, in words (null = not one) */
    fun badReason(reason: Int): String? = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "DS Forever crashed"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "DS Forever crashed (native code)"
        ApplicationExitInfo.REASON_ANR -> "DS Forever stopped responding"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Android closed DS Forever because the device ran low on memory"
        ApplicationExitInfo.REASON_SIGNALED -> "DS Forever was stopped by a signal"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "DS Forever failed to start"
        else -> null
    }

    /** below this a process holds next to nothing (an empty cached process: #106 pss 13 MB, #111 pss 0 / 11 MB) */
    const val TINY_PSS_KB = 50L * 1024
    /** ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED: Android's "in the background, kept only in case it is wanted" */
    const val IMPORTANCE_CACHED = 400

    /**
     * IS THIS EXIT WORTH A REPORT? (issue triage 2026-11-11, cluster "not a bug": #111, #106 and others were "Automatic crash report:
     * Android closed DS Forever because the device ran low on memory" from a CACHED process (importance 400, pss 0-13 MB) with no game
     * running: Android tidying the background, which every app gets and no report can fix.) A low-memory kill is offered only when it
     * hit DS Forever in front (importance below cached) or holding real memory while the game ran; a cached AND tiny process is skipped
     * even under a "game running" mark (#106's mark was 2638 min old: a stale one). Every other bad reason is always offered.
     * [pssKb] = ApplicationExitInfo.pss (KB); [gameRunning] = the "running" mark was still set.
     */
    fun offer(reason: Int, importance: Int, pssKb: Long, gameRunning: Boolean): Boolean {
        if (badReason(reason) == null) return false
        if (reason != ApplicationExitInfo.REASON_LOW_MEMORY) return true
        val cached = importance >= IMPORTANCE_CACHED
        val tiny = pssKb < TINY_PSS_KB
        if (cached && tiny) return false
        if (!gameRunning && (cached || tiny)) return false
        return true
    }

    /** the question's words from the witnesses ([exit] = Android's reason in words, null = a normal end; [wasRunning] = the mark) */
    fun words(exit: String?, wasRunning: Boolean, inWorld: Boolean, ranMs: Long): String? {
        if (exit == null && !wasRunning) return null
        val game = if (wasRunning) "The game was running" + (if (inWorld) " (in the world" else " (still loading") +
            ", ${GameLife.minutes(ranMs)} in)" else ""
        return when {
            exit != null && wasRunning -> "$exit. $game."
            exit != null -> "$exit."
            else -> null          // the mark alone: a force stop by the player looks the same (no Android crash record): no question
        }
    }

    private fun check(ctx: Context) {
        val p = prefs ?: return
        val runningAt = p.getLong("running", 0L)
        val world = p.getBoolean("world", false)
        p.edit().remove("running").remove("world").apply()
        var exit: String? = null
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val seen = p.getLong("exitSeen", 0L)
            val last = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10)
                .filter { it.processName == ctx.packageName }.maxByOrNull { it.timestamp }
            if (last != null && last.timestamp > seen) {
                p.edit().putLong("exitSeen", last.timestamp).apply()
                val worth = offer(last.reason, last.importance, last.pss, runningAt > 0L)
                exit = if (worth) badReason(last.reason) else null
                android.util.Log.i("WCLASTRUN", "last exit: reason ${last.reason} (${last.description ?: "-"}), importance ${last.importance}, " +
                    "pss ${last.pss / 1024} MB; game running $runningAt${if (worth) "" else "; no report (a background clean-up)"}")
            }
        }
        pending = words(exit, runningAt > 0L, world, if (runningAt > 0L) System.currentTimeMillis() - runningAt else 0L)
        pending?.let { DiagLog.event("last run", it) }
    }

    const val TITLE = "Your last session ended unexpectedly"
    fun text(why: String) = "$why Send a report so we can find out why? It includes the device details and recent logs (no logins or chat)."
}
