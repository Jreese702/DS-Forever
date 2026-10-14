package com.jrees.wowcompanion

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * THE PROBLEM REPORT'S FLOW (board f-problem-report; the user's rules after the two-button screen):
 * - the player's words are REQUIRED ([canSend]) before Send report or Record 5 minutes;
 * - Record 5 minutes keeps the words ([description]) with the recording; Settings' row then says "Send report" (stop + send now);
 * - the 5 minutes running out SAVES the report by itself ([autoSave]: no browser mid-game) and leaves a note ([pending]) on Settings
 *   and the setup screen, "Your problem report was saved (<time>)" with Open GitHub issue, until it is opened or dismissed.
 * Kept in the app's own preferences, so it survives a restart.
 */
object ReportFlow {
    private var prefs: SharedPreferences? = null
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun init(c: Context) { if (prefs == null) prefs = c.applicationContext.getSharedPreferences("report", Context.MODE_PRIVATE) }
    fun onChange(l: () -> Unit) { listeners.add(l) }
    private fun changed() { for (l in listeners) runCatching { l() } }

    /** "a few words": two words, or one long enough to say something */
    fun canSend(text: String): Boolean {
        val t = text.trim()
        return t.split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) } >= 2 || t.length >= 12
    }

    /** the words kept with a recording (Record 5 minutes), "" = none */
    var description: String
        get() = prefs?.getString("description", "") ?: ""
        set(v) { prefs?.edit()?.putString("description", v)?.apply() }

    data class Saved(val path: String, val at: Long, val url: String)

    /** the report saved by itself and not opened or dismissed yet */
    val pending: Saved?
        get() {
            val p = prefs ?: return null
            val path = p.getString("savedPath", null) ?: return null
            return Saved(path, p.getLong("savedAt", 0L), p.getString("savedUrl", "") ?: "")
        }

    fun markSaved(path: String, at: Long, url: String) {
        prefs?.edit()?.putString("savedPath", path)?.putLong("savedAt", at)?.putString("savedUrl", url)?.apply()
        changed()
    }

    /** opened or dismissed: the note goes */
    fun clearPending() { prefs?.edit()?.remove("savedPath")?.remove("savedAt")?.remove("savedUrl")?.apply(); changed() }

    fun noteTitle(s: Saved): String = "Your problem report was saved (${SimpleDateFormat("HH:mm", Locale.US).format(Date(s.at))})"

    /**
     * The 5 minutes ran out: the report (the kept words, the recording) saved to Documents/DS Forever/reports, the note left for
     * the next visit to Settings or the setup screen. Off the main thread. The saved file, or null.
     */
    fun autoSave(ctx: Context, now: Long = System.currentTimeMillis()): java.io.File? {
        init(ctx)
        val words = description.ifBlank { "(recorded with Record 5 minutes; no description kept)" }
        val r = ProblemReport.collect(ctx, words, now)
        val f = ProblemReport.save(ctx, r) ?: return null
        markSaved(f.path, now, ProblemReport.issueUrl(r, words, ProblemReport.facts(ctx), f.path, Redact.names()))
        description = ""
        DiagLog.event("report", "saved by itself after the recording")
        return f
    }
}
