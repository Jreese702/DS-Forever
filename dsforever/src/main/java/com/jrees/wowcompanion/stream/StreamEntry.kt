package com.jrees.wowcompanion.stream

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.view.Display

/**
 * HOW THE APP ENTERS STREAMING.
 *
 * The start-up chooser is S1's: its interface app.gamenative.ui.screen.wow.StreamEntry (in :app, branch feat/stream-chooser) finds
 * S2's object app.gamenative.stream.DsStreamEntry by name (in :app since stream-int): a thin adapter over [StreamTop] that also lays
 * the launcher's on-screen controls (InputControlsView) over the stream.
 *
 * [Streaming] is the same thing without the chooser: [StreamActivity] on the top screen (the first tests, or a future shortcut).
 */
object Streaming {
    /** what Streaming still needs on this Thor (empty = ready). A Streaming-only player needs no local game folder */
    fun missing(context: Context): List<String> =
        if (MoonBridge.loaded) emptyList() else listOf("This build of DS Forever has no streaming core.")

    fun isPaired(context: Context) = HostStore(context).host() != null
    fun pairedName(context: Context) = HostStore(context).host()?.name

    /** Streaming in its own activity on the TOP screen (display 0, where the container's game renders in Local) */
    fun open(activity: Activity, fromShortcut: Boolean = false) {
        val i = Intent(activity, StreamActivity::class.java).putExtra(StreamActivity.EXTRA_FROM_SHORTCUT, fromShortcut)
        val top = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
        try { activity.startActivity(i, top) } catch (_: Throwable) { activity.startActivity(i) }
    }

    /** B on [StreamActivity]'s own screens: back to the chooser (whoever opened it sets this) */
    @Volatile var backToChooser: ((Activity) -> Unit)? = null
}
