package com.jrees.wowcompanion

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Settings > Frame generation > "Choose": the bottom screen is a Presentation, which can't take a picker's result, so this invisible
 * activity (started on the bottom screen's display, with the game running on the top one) opens Android's file picker, copies the pick into private storage (FrameGen.importDll, off the main thread) and closes.
 * Read access to the one picked file only: no permission is asked for.
 */
class FrameGenPickActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return             // the picker is already open (a recreate)
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        runCatching { startActivityForResult(pick, REQ) }.onFailure { FrameGen.init(this); finish() }
    }

    @Deprecated("Activity result on a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ && resultCode == RESULT_OK && uri != null) {
            val app = applicationContext
            Thread({ FrameGen.importDll(app, uri) }, "framegen-import").start()
        }
        finish()
    }

    /** however it ends (a pick, Back, a failure): the bottom screen's window comes back */
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) FrameGen.onPickerClosed?.invoke()
    }

    private companion object { const val REQ = 7101 }
}
