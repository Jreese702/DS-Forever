package app.gamenative.ui.components

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent

/**
 * DS FOREVER'S LAUNCH SCREEN IN PLACE OF GAMENATIVE'S SPLASH (the user, 2026-11-24): the companion's [com.jrees.wowcompanion.LaunchTopView]
 * (the swirl reveal, the gold status line, a bar for a step's progress) over the whole top screen while [visible], and GameNative's
 * abort chord kept as it was (Start + Select + L2 + R2 = [onAbort]; [com.jrees.wowcompanion.LaunchChord]). The view fades itself out
 * at the hand-over; the host stops showing it when the launch screen is over (LaunchLoad.covers()).
 */
@Composable
fun LaunchLoadingScreen(visible: Boolean, onAbort: () -> Unit) {
    if (!visible) return
    DisposableEffect(Unit) {
        val chord = com.jrees.wowcompanion.LaunchChord(onAbort)
        val keyHandler: (AndroidEvent.KeyEvent) -> Boolean = { e ->
            val a = e.event.action
            if (a != KeyEvent.ACTION_DOWN && a != KeyEvent.ACTION_UP) false else chord.key(e.event.keyCode, a == KeyEvent.ACTION_DOWN)
        }
        val motionHandler: (AndroidEvent.MotionEvent) -> Boolean = { e ->
            e.event?.let { me ->
                chord.triggers(maxOf(me.getAxisValue(MotionEvent.AXIS_LTRIGGER), me.getAxisValue(MotionEvent.AXIS_BRAKE)),
                    maxOf(me.getAxisValue(MotionEvent.AXIS_RTRIGGER), me.getAxisValue(MotionEvent.AXIS_GAS)))
            } ?: false
        }
        PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(keyHandler)
        PluviaApp.events.on<AndroidEvent.MotionEvent, Boolean>(motionHandler)
        onDispose {
            PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(keyHandler)
            PluviaApp.events.off<AndroidEvent.MotionEvent, Boolean>(motionHandler)
        }
    }
    AndroidView(factory = { com.jrees.wowcompanion.LaunchTopView(it) }, modifier = Modifier.fillMaxSize())
}
