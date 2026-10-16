package com.jrees.wowcompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * THE SAFE CLOSE BEFORE AN INSTALL (the user's pick, 2026-10-15: "Safe close before installs"): an install kills DS Forever and the
 * game with it, so the game never writes its saved state (the quest watch list among it: "All of my tracked quests are getting
 * untracked between updates"). The install step asks first:
 *     adb shell am broadcast -a app.dsforever.SAFE_CLOSE -p app.dsforever.companion
 * and this runs the existing clean exit ([CleanExit], LauncherHooks.cleanExit: the game's own Exit Game, the save written as it
 * exits), then says on the WCSETUP log tag "safe close: done (...)". Protected by android.permission.DUMP (the manifest): the system and
 * the adb shell hold it, no normal app can, so nothing else can close the game. Nothing running: "done (nothing to close)" at once.
 */
class SafeCloseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val ce = LauncherHooks.cleanExit
        if (ce == null || !LauncherHooks.gameUp()) { Log.i(TAG, "safe close: done (nothing to close)"); return }
        Log.i(TAG, "safe close: asked")
        LauncherHooks.lastCleanExit = ""
        ce("safe close") { forceNeeded ->
            if (!forceNeeded) QuestWatchKeep.cleanEnd()           // exited by itself: its save is written
            // the route repeated on the last line, so the one line the install step waits for says why (tracking's ask)
            Log.i(TAG, "safe close: done (" + (if (forceNeeded) "the game did not exit by itself: the install will close it"
                else "the game exited by itself, its state saved") + ") [" + LauncherHooks.lastCleanExit + "]")
        }
    }

    companion object {
        const val ACTION = "app.dsforever.SAFE_CLOSE"
        const val TAG = "WCSETUP"
    }
}
