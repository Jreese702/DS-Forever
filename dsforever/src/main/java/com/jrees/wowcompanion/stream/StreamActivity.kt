package com.jrees.wowcompanion.stream

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.jrees.wowcompanion.fork.DsForeverScreen

/**
 * STREAMING IN ITS OWN ACTIVITY ([Streaming.open]): the same [StreamTop] as S1's chooser route hosts, for a start without the
 * chooser (and the first tests). This activity takes the bottom screen over from the launcher's DS Forever while it runs
 * ([DsForeverScreen.beginStream] / [DsForeverScreen.endStream]); B on Streaming's own screens = [Streaming.backToChooser], then it closes.
 */
class StreamActivity : Activity(), StreamTop.Shell {
    private lateinit var top: StreamTop

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        top = StreamTop(this, this)
        setContentView(top.view)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        DsForeverScreen.beginStream(this)
        top.opened()
    }

    override fun onDestroy() {
        top.closed()
        DsForeverScreen.endStream(this)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = top.key(event) || super.dispatchKeyEvent(event)
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean = top.motion(ev) || super.dispatchGenericMotionEvent(ev)

    override fun back() {
        Streaming.backToChooser?.invoke(this)
        finish()
    }

    companion object {
        const val EXTRA_FROM_SHORTCUT = "dsforever_stream_from_shortcut"
    }
}
