package com.jrees.wowcompanion

import android.content.Context

/**
 * The last "Test connection" (Streaming's stream settings; the user's pick 2026-11-17) for the problem report: the summary the
 * streaming side wrote (already without names, IPs cut to a.b.x.x), kept across restarts. "" = never run.
 */
object ConnTestNote {
    private const val PREFS = "dsf_conn_test"
    private const val KEY = "last"
    private const val MAX = 4_000

    fun save(ctx: Context, summary: String) {
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, summary.take(MAX)).apply() }
    }

    fun load(ctx: Context): String =
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) }.getOrNull().orEmpty()
}
