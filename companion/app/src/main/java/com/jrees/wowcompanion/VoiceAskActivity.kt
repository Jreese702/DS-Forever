package com.jrees.wowcompanion

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * ANDROID'S MICROPHONE QUESTION ON THE BOTTOM SCREEN (the user's pick 2026-11-16: "Android's own 'Allow microphone?' box, shown once on the
 * bottom screen the first time you press X (never in a fight)"). The bottom screen is a Presentation, which can't ask for a permission,
 * so this invisible activity (started by the host's openOnBottom on the bottom screen's display, the game running on the top one, as
 * FrameGenPickActivity) asks Android once and closes; touch and the D-pad both work in Android's box. The answer is VoiceMic's; the
 * voice sheet looks again when it closes ([VoiceMic.onClosed]). Nothing else is asked, and Settings is never opened from here.
 */
class VoiceAskActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return             // the question is already up (a recreate)
        if (VoiceMic.granted(this)) { finish(); return }
        runCatching { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ) }.onFailure { finish() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ) {
            // an empty answer is a question that was cancelled (another one came over it): nothing is remembered
            if (grantResults.isNotEmpty()) {
                val ok = grantResults[0] == PackageManager.PERMISSION_GRANTED
                VoiceMic.answered(this, ok, ok || shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))
            }
        }
        finish()
    }

    /** however it ends (an answer, Back, a failure): the bottom screen's window comes back, the sheet looks again */
    override fun onDestroy() {
        super.onDestroy()
        if (!isFinishing) return
        VoiceMic.onClosed?.invoke()
        VoiceMic.onAnswer?.let { a -> VoiceMic.onAnswer = null; android.os.Handler(android.os.Looper.getMainLooper()).post { a() } }
    }

    private companion object { const val REQ = 7102 }
}
