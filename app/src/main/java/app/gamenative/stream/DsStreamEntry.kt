package app.gamenative.stream

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.screen.wow.StreamEntry
import com.jrees.wowcompanion.ModeBridge
import com.jrees.wowcompanion.stream.StreamTop
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.GamepadState
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.widget.InputControlsView
import com.winlator.xserver.Pointer
import timber.log.Timber

/**
 * S2's Streaming behind S1's chooser: the top screen is [StreamTop] (pairing by the code, the stream, its errors) inside the launcher's
 * own activity; the bottom screen is the launcher's DS Forever, covered by S1's placeholder page until a PC is paired
 * ([ModeBridge.streamCovers] / [ModeBridge.streamNote]). Keys come through [key]; the sticks / hat / triggers through the launcher's
 * motion events, subscribed while the top screen is open.
 *
 * TOUCH IN STREAMING (the user, 2026-10-23: "both"): while the stream runs, the SAME on-screen controls Local shows over the game
 * (Winlator's InputControlsView with the WoW controls profile, "WoW Forever - Physical Controller") lie over the picture. Their pad goes
 * to the stream's controller (merged with the physical pad), their keys and mouse to Moonlight's keyboard / mouse (StreamKeys' rules),
 * and a touch on no control is the PC's mouse (StreamTop.touch: tap = left click, hold or two fingers = right click, drag = held left,
 * two-finger drag = the wheel). "Touch controls" in Stream quality turns the overlay off (touch = mouse stays).
 */
object DsStreamEntry : StreamEntry {
    private var top: StreamTop? = null
    private var root: FrameLayout? = null
    private var controls: InputControlsView? = null

    private val shell = object : StreamTop.Shell {
        override fun back() { ModeBridge.back("Streaming's Back") }
        override fun bottom(covers: Boolean, note: String) { ModeBridge.streamCovers = covers; ModeBridge.streamNote = note }
        override fun streaming(on: Boolean, touchControls: Boolean) { showControls(on && touchControls) }
    }

    private val motion: (AndroidEvent.MotionEvent) -> Boolean = { e -> e.event?.let { top?.motion(it) } == true }

    private fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

    private fun topFor(ctx: Context): StreamTop? {
        val a = ctx.activity() ?: return null
        return top ?: StreamTop(a, shell).also { top = it }
    }

    /** the top screen: the stream's view, and over it (while streaming) the on-screen controls */
    private fun rootFor(ctx: Context, t: StreamTop): FrameLayout = root ?: FrameLayout(ctx).also { r ->
        (t.view.parent as? ViewGroup)?.removeView(t.view)
        r.addView(t.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root = r
    }

    /** the WoW controls profile Local uses ("WoW Forever - Physical Controller", id 6), else Local's own fallback (profile 0) */
    fun pickProfile(profiles: List<ControlsProfile>): ControlsProfile? =
        profiles.firstOrNull { it.name.equals(WOW_PROFILE, ignoreCase = true) }
            ?: profiles.firstOrNull { it.id == WOW_PROFILE_ID }
            ?: profiles.firstOrNull { it.id == 0 }
            ?: profiles.firstOrNull()

    private fun showControls(on: Boolean) {
        val t = top ?: return
        val r = root ?: return
        var v = controls
        if (on && v == null) {
            val ctx = r.context
            v = try { makeControls(ctx, t) } catch (e: Throwable) { Timber.w(e, "Streaming: no on-screen controls"); null } ?: return
            controls = v
            r.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        v ?: return
        if (!on) v.setRemote(object : InputControlsView.RemoteSink {   // let go of anything held, then nothing more goes out
            override fun gamepad(state: GamepadState) {}
            override fun key(xKeycode: Int, down: Boolean) {}
            override fun pointerButton(button: Pointer.Button, down: Boolean) {}
            override fun pointerMove(dx: Int, dy: Int) {}
        }, null) else v.setRemote(sinkFor(t), fallbackFor(t))
        v.visibility = if (on) View.VISIBLE else View.GONE
        v.invalidate()
    }

    private fun makeControls(ctx: Context, t: StreamTop): InputControlsView? {
        val manager = PluviaApp.inputControlsManager ?: InputControlsManager(ctx)
        val profile = pickProfile(manager.getProfiles(false)) ?: return null
        PrefManager.init(ctx)
        return InputControlsView(ctx).apply {
            setProfile(profile)
            setOverlayOpacity(PrefManager.getFloat("controls_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY))
            setShowTouchscreenControls(true)
            setRemote(sinkFor(t), fallbackFor(t))
            Timber.i("Streaming: on-screen controls ${profile.name} (${profile.id})")
        }
    }

    private fun sinkFor(t: StreamTop) = object : InputControlsView.RemoteSink {
        override fun gamepad(state: GamepadState) =
            t.virtualPad(state.buttons.toInt() and 0xFFFF, state.dpad, state.triggerL, state.triggerR, state.thumbLX, state.thumbLY, state.thumbRX, state.thumbRY)
        override fun key(xKeycode: Int, down: Boolean) = t.remoteKey(xKeycode, down)
        override fun pointerButton(button: Pointer.Button, down: Boolean) {
            when (button) {
                Pointer.Button.BUTTON_LEFT -> t.remoteButton(1, down)
                Pointer.Button.BUTTON_MIDDLE -> t.remoteButton(2, down)
                Pointer.Button.BUTTON_RIGHT -> t.remoteButton(3, down)
                Pointer.Button.BUTTON_SCROLL_UP -> if (down) t.remoteScroll(120)
                Pointer.Button.BUTTON_SCROLL_DOWN -> if (down) t.remoteScroll(-120)
                else -> {}
            }
        }
        override fun pointerMove(dx: Int, dy: Int) = t.remoteMove(dx, dy)
    }

    private fun fallbackFor(t: StreamTop) = object : InputControlsView.TouchFallback {
        override fun onTouchEvent(event: MotionEvent): Boolean = t.touch(event)
        override fun cancelTouchInput() = t.cancelTouch()
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
        controls?.setRemote(null, null)
        top?.closed()
        top = null; root = null; controls = null
        ModeBridge.streamCovers = true; ModeBridge.streamNote = ""
    }

    override fun key(event: KeyEvent): Boolean = top?.key(event) == true

    const val WOW_PROFILE = "WoW Forever - Physical Controller"
    const val WOW_PROFILE_ID = 6
}
