package com.jrees.wowcompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * THE TAINT LOG, COPIED OUT (temporary; the user's "Taint log" pick 2026-11-08 for test6's ADDON_ACTION_FORBIDDEN on
 * SetPreferredGamepadInteractTarget): the game is in DS Forever's private storage, so its Logs/taint.log (written while Sink.lua's probe
 * has the game's taintLog on) is out of adb's reach. An install step asks for a copy:
 *
 *     adb shell am broadcast -a app.dsforever.COPY_TAINT -p app.dsforever.companion
 *
 * -> /sdcard/WowCompanion/logs/taint.log (and the game's General.log is never copied: only the taint log). DUMP guards it as SAFE_CLOSE:
 * only adb can ask. Remove with the probe.
 */
class TaintLogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val r = goAsync()
        Thread {
            try {
                val root = WowProduct.flavorDir(GameFiles.gameRoot(context.applicationContext))
                val src = File(root, "Logs/taint.log")
                val dst = File("/sdcard/WowCompanion/logs/taint.log")
                if (!src.isFile) Log.i(TAG, "taint log: none at ${src.path}")
                else {
                    dst.parentFile?.mkdirs()
                    src.copyTo(dst, overwrite = true)
                    Log.i(TAG, "taint log: copied ${src.length()} B to ${dst.path}")
                }
            } catch (t: Throwable) {
                Log.i(TAG, "taint log: copy failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally { r.finish() }
        }.start()
    }

    companion object {
        const val ACTION = "app.dsforever.COPY_TAINT"
        const val TAG = "WCSETUP"
    }
}
