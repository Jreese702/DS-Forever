package app.gamenative.stream

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.screen.wow.StreamEntry
import com.jrees.wowcompanion.ModeBridge
import com.jrees.wowcompanion.stream.StreamTop

/**
 * S2's Streaming behind S1's chooser: the top screen is [StreamTop] (pairing by the code, the stream, its errors) inside the launcher's
 * own activity; the bottom screen is the launcher's DS Forever, covered by S1's placeholder page until a PC is paired
 * ([ModeBridge.streamCovers] / [ModeBridge.streamNote]). Keys come through [key]; the sticks / hat / triggers through the launcher's
 * motion events, subscribed while the top screen is open.
 *
 * TOUCH IN STREAMING: a touch on the picture is the PC's mouse (StreamTop.touch: tap = left click, hold or two fingers = right click,
 * drag = held left, two-finger drag = the wheel). No on-screen controls lie over the stream (the user, 2026-10-29: "On the streaming on
 * my top screen there's a controller overlay? Remove that"; the 10-23 overlay and its "Touch controls" setting are gone).
 */
object DsStreamEntry : StreamEntry {
    private var top: StreamTop? = null
    private var root: FrameLayout? = null

    private val shell = object : StreamTop.Shell {
        override fun back() { ModeBridge.back("Streaming's Back") }
        override fun bottom(covers: Boolean, note: String) { ModeBridge.streamCovers = covers; ModeBridge.streamNote = note }
    }

    private val motion: (AndroidEvent.MotionEvent) -> Boolean = { e -> e.event?.let { top?.motion(it) } == true }

    private fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

    private fun topFor(ctx: Context): StreamTop? {
        val a = ctx.activity() ?: return null
        return top ?: StreamTop(a, shell).also { top = it }
    }

    /** the top screen: the stream's view */
    private fun rootFor(ctx: Context, t: StreamTop): FrameLayout = root ?: FrameLayout(ctx).also { r ->
        (t.view.parent as? ViewGroup)?.removeView(t.view)
        r.addView(t.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root = r
    }

    @Composable
    override fun TopScreen(onBack: () -> Unit) {
        val ctx = LocalContext.current
        val t = topFor(ctx) ?: return
        val r = rootFor(ctx, t)
        AndroidView(factory = { (r.parent as? ViewGroup)?.removeView(r); r }, modifier = Modifier.fillMaxSize())
    }

    override fun opened(context: Context) {
        val t = topFor(context) ?: return
        PluviaApp.events.on<AndroidEvent.MotionEvent, Boolean>(motion)
        t.opened()
    }

    override fun closed(context: Context) {
        PluviaApp.events.off<AndroidEvent.MotionEvent, Boolean>(motion)
        top?.closed()
        top = null; root = null
        ModeBridge.streamCovers = true; ModeBridge.streamNote = ""
    }

    override fun key(event: KeyEvent): Boolean = top?.key(event) == true
}
