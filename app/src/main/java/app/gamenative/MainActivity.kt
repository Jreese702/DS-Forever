package app.gamenative

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color.TRANSPARENT
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.PluviaMain
import app.gamenative.ui.enums.Orientation
import app.gamenative.ui.util.LocalSnackbarHostController
import app.gamenative.ui.util.SnackbarHostController
import app.gamenative.utils.LocaleHelper
import app.gamenative.ui.util.SnackbarManager
import com.winlator.core.AppUtils
import com.winlator.inputcontrols.ControllerManager
import dagger.hilt.android.AndroidEntryPoint
import java.util.EnumSet
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val onSetSystemUi: (AndroidEvent.SetSystemUIVisibility) -> Unit = {
        desiredSystemUiVisible = it.visible
        applyImmersiveMode()
    }

    private val onSetAllowedOrientation: (AndroidEvent.SetAllowedOrientation) -> Unit = {
        setOrientationTo(it.orientations)
    }

    private val onEndProcess: (AndroidEvent.EndProcess) -> Unit = {
        finishAndRemoveTask()
    }

    private var controllerInputManager: InputManager? = null
    private val controllerDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            ControllerManager.getInstance().onDeviceConnected(deviceId)
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            ControllerManager.getInstance().onDeviceDisconnected(deviceId)
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            ControllerManager.getInstance().onDeviceConnected(deviceId)
        }
    }

    private var desiredSystemUiVisible: Boolean = false

    override fun attachBaseContext(newBase: Context) {
        // Initialize PrefManager to read language setting
        PrefManager.init(newBase)

        // Apply the saved language preference before creating the activity
        val languageCode = PrefManager.appLanguage
        val context = LocaleHelper.applyLanguage(newBase, languageCode)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Full immersive mode - transparent system bars for console-like experience
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        createdGen++

        // DS Forever fork: the game belongs on the top screen; opened on another one (Cocoon's bottom grid) = moved there first
        if (com.jrees.wowcompanion.fork.DsForeverScreen.moveToTop(this, PluviaApp.xEnvironment != null)) return
        // DS Forever S5: the bottom screen is DS Forever's from the launcher's start (its start screens); the game screen attaches its input
        com.jrees.wowcompanion.fork.DsForeverScreen.begin(this)
        // the start-up mode: the normal icon = the chooser, every time; a home-screen shortcut's dsf.mode = straight into its mode.
        // A rebuilt activity (saved state) or our own move to the top screen (DsForeverScreen's relaunch) keeps the mode it had
        com.jrees.wowcompanion.ModeBridge.start(this, intent?.getStringExtra(com.jrees.wowcompanion.StartMode.EXTRA),
            recreated = savedInstanceState != null || intent?.getBooleanExtra("dsforever_moved_to_top", false) == true)

        // stale keepAlive from a prior crash/swipe — no container is actually running
        if (PluviaApp.keepAlive && PluviaApp.xEnvironment == null) {
            Timber.w("onCreate: clearing stale keepAlive — no container running")
            PluviaApp.shutdownEnvironment()
        }

        // Apply immersive mode based on user preference
        applyImmersiveMode()

        // Initialize the controller management system
        ControllerManager.getInstance().init(applicationContext)
        controllerInputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        controllerInputManager?.registerInputDeviceListener(controllerDeviceListener, null)
        AppUtils.keepScreenOn(this)

        PluviaApp.events.on<AndroidEvent.SetSystemUIVisibility, Unit>(onSetSystemUi)
        PluviaApp.events.on<AndroidEvent.SetAllowedOrientation, Unit>(onSetAllowedOrientation)
        PluviaApp.events.on<AndroidEvent.EndProcess, Unit>(onEndProcess)
        // feat/pause-power (the user, 2026-11-18: "a power button that effectively opens a prompt to close DS forever"): the companion's
        // Close runs the game's own Exit Game first (or Desktop's end_session while streaming), then this: the old close of the game only
        // when it did not exit by itself ([force]), then the task goes; onDestroy shuts the environment down and ends the process
        com.jrees.wowcompanion.LauncherHooks.quitApp = { force ->
            runOnUiThread {
                Timber.i("Close DS Forever (the pause menu's power button)%s", if (force) ": the old close of the game first" else "")
                com.jrees.wowcompanion.GameLife.closer = "Close DS Forever from the pause menu"
                if (force && PluviaApp.xEnvironment != null) PluviaApp.events.emit(AndroidEvent.ForceCloseApp)
                window.decorView.postDelayed({ if (!isFinishing) finishAndRemoveTask() }, if (force) 1_500L else 0L)
                closeFallback(if (force) 1_500L else 0L)
            }
        }

        setContent {
            val snackbarController = remember { SnackbarHostController() }
            CompositionLocalProvider(
                LocalSnackbarHostController provides snackbarController,
            ) {
                PluviaMain()
            }
        }
    }
    /**
     * fix/1492-feedback (the user, 2026-11-18, test 1492: "closing from the power button didn't close the app"): THE HARD FALLBACK of the
     * power button's close. The close above finishes this task and onDestroy ends the process once no task is left; when that did not
     * happen [CLOSE_FALLBACK_MS] after the task's finish (another of our tasks still up - Streaming's activity, Swap screens' bottom
     * window - the environment's keepAlive stuck, onDestroy never reached), every task of ours is finished, DS Forever's bottom screen
     * stopped, the game's environment shut down, and the process ends. Its own thread: a wedged main thread still exits.
     */
    private fun closeFallback(afterMs: Long) {
        val app = applicationContext
        val gen = createdGen
        Thread({
            try { Thread.sleep(afterMs + CLOSE_FALLBACK_MS) } catch (_: InterruptedException) { return@Thread }
            // DS Forever opened again in the same process meanwhile (a fresh MainActivity): that one stays
            if (createdGen != gen) { android.util.Log.i("WCSETUP", "power: the hard fallback stands down (DS Forever opened again)"); return@Thread }
            Timber.w("Close DS Forever: still up %d ms after the close: every task finished, the process ends", CLOSE_FALLBACK_MS)
            android.util.Log.w("WCSETUP", "power: DS Forever still up ${CLOSE_FALLBACK_MS / 1000} s after the close: the hard fallback ends it")
            runCatching { app.getSystemService(android.app.ActivityManager::class.java)?.appTasks?.forEach { runCatching { it.finishAndRemoveTask() } } }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { com.jrees.wowcompanion.fork.DsForeverScreen.current?.stop() }
                if (PluviaApp.keepAlive) runCatching { PluviaApp.shutdownEnvironment() }
            }
            try { Thread.sleep(1_000L) } catch (_: InterruptedException) {}
            com.jrees.wowcompanion.IndoorSchedulerStep.appExit()     // #568: the app's exit is one of the indoor maps' few stops
            com.jrees.wowcompanion.LastRun.ownExit(app)
            android.os.Process.killProcess(android.os.Process.myPid())
        }, "dsf-close-fallback").apply { isDaemon = true }.start()
    }

    companion object {
        /** the power button's hard fallback: the process ends this long after the close if it is still up */
        const val CLOSE_FALLBACK_MS = 5_000L
        /** bumped by every MainActivity onCreate (the fallback stands down for a DS Forever opened again) */
        @Volatile var createdGen = 0
    }

    // a home-screen shortcut tapped while DS Forever runs (singleTop): its mode, unless the game runs (ModeBridge decides)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        com.jrees.wowcompanion.ModeBridge.onIntent(this, intent.getStringExtra(com.jrees.wowcompanion.StartMode.EXTRA))
    }

    override fun onDestroy() {
        com.jrees.wowcompanion.fork.DsForeverScreen.end(this)
        // emit before super so Compose DisposableEffects (which unregister
        // listeners during super.onDestroy's lifecycle transition) still fire
        if (!isChangingConfigurations) {
            // issue #355: the window closed for good ends the game from here (XServerScreen's exit): ours, not Android's, in the
            // game's end line ("was closed by DS Forever (...)"), and no "The game closed" note for it
            if (isFinishing && com.jrees.wowcompanion.GameLife.closer == null) com.jrees.wowcompanion.GameLife.closer = "the app closing"
            PluviaApp.events.emit(AndroidEvent.ActivityDestroyed)

            // if exit() didn't run (listener already unregistered, race, etc.)
            // force-clear so the app isn't stuck on next launch
            if (PluviaApp.keepAlive) {
                Timber.w("onDestroy: keepAlive still set after ActivityDestroyed — forcing cleanup")
                PluviaApp.shutdownEnvironment()
            }
        }

        super.onDestroy()

        controllerInputManager?.unregisterInputDeviceListener(controllerDeviceListener)
        controllerInputManager = null

        // closed for good: no idle process is left for a launcher to count as still playing (the user on CocoonShell, 2026-10-30:
        // "still shows now playing ... won't close"; measured: no task, the process alive). A moment later, so a relaunch keeps it
        if (isFinishing && !isChangingConfigurations) {
            val app = applicationContext
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val am = app.getSystemService(android.app.ActivityManager::class.java)
                if (am?.appTasks.isNullOrEmpty() && !PluviaApp.keepAlive) {
                    // issue #355: Android records this kill as "stopped by a signal" (9); the mark says it was ours (LastRun.ownKill)
                    com.jrees.wowcompanion.IndoorSchedulerStep.appExit()     // #568: it outlives the bottom screen, not the app
                    com.jrees.wowcompanion.LastRun.ownExit(app)
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }, 1_500L)
        }

        PluviaApp.events.off<AndroidEvent.SetSystemUIVisibility, Unit>(onSetSystemUi)
        PluviaApp.events.off<AndroidEvent.SetAllowedOrientation, Unit>(onSetAllowedOrientation)
        PluviaApp.events.off<AndroidEvent.EndProcess, Unit>(onEndProcess)
    }

    private fun hasReadyGameLifecycleState(action: String): Boolean {
        if (!PluviaApp.keepAlive) return false
        if (!PluviaApp.hasValidSuspendPolicyState()) {
            Timber.d("Skipping game %s because suspend policy state is not initialized", action)
            return false
        }
        if (PluviaApp.xEnvironment == null) {
            Timber.d("Skipping game %s because xEnvironment is not ready", action)
            return false
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        PluviaApp.isActivityInForeground = true
        // the game may have finished booting while the app was in the background, when Android refuses to start a foreground
        // service (upstream v2.4.4): before the move to the top screen below, which returns early
        if (PluviaApp.xEnvironment != null) GameSessionService.ensureStarted(applicationContext)
        // DS Forever fork: an existing task brought back on the bottom screen (singleTop: no onCreate) moves to the top screen too
        if (com.jrees.wowcompanion.fork.DsForeverScreen.moveToTop(this, PluviaApp.xEnvironment != null)) return
        com.jrees.wowcompanion.fork.DsForeverScreen.current?.show()

        // Re-apply immersive mode to ensure fullscreen persists
        if (!desiredSystemUiVisible) {
            applyImmersiveMode()
        }

        // Resume game according to the active suspend policy.
        if (hasReadyGameLifecycleState("resume")) {
            when {
                PluviaApp.isNeverSuspendMode() -> {
                    Timber.d("Game resume skipped due to suspend policy=never")
                }
                PluviaApp.isOverlayPaused -> {
                    if (PluviaApp.isBootingSplashShowing) {
                        // The Resume overlay sits under the booting splash, so the user could
                        // never press it; nothing is being played yet, so just carry on booting.
                        PluviaApp.xEnvironment?.onResume()
                        PluviaApp.isOverlayPaused = false
                        Timber.d("Game resumed automatically: still booting behind the splash")
                    } else if (PluviaApp.isManualSuspendMode()) {
                        Timber.d("Game remains suspended until user presses Resume")
                    }
                }
                else -> {
                    PluviaApp.xEnvironment?.onResume()
                    Timber.d("Game resumed")
                }
            }
        }
    }

    // DS Forever (the user 2026-10-09: "When I press the Home button on my controller, sometimes the top window minimizes but the
    // bottom window stays"): the bottom screen goes with the top on EVERY way out, not only onPause. With two screens Android can keep
    // this activity resumed while another display has the focus, and a Home press then reaches us as onUserLeaveHint / onStop only
    // #384 review: hideFor = this activity's own DS Forever only (after a Restart the new activity's is up before this one stops)
    override fun onUserLeaveHint() {
        com.jrees.wowcompanion.fork.DsForeverScreen.hideFor(this)
        super.onUserLeaveHint()
    }

    override fun onStop() {
        com.jrees.wowcompanion.fork.DsForeverScreen.hideFor(this)
        super.onStop()
    }

    override fun onPause() {
        com.jrees.wowcompanion.fork.DsForeverScreen.hideFor(this)
        PluviaApp.isActivityInForeground = false
        if (hasReadyGameLifecycleState("pause")) {
            when {
                PluviaApp.isNeverSuspendMode() -> {
                    Timber.d("Game pause skipped due to suspend policy=never")
                }
                else -> {
                    PluviaApp.xEnvironment?.onPause()
                    if (PluviaApp.isManualSuspendMode()) {
                        PluviaApp.isOverlayPaused = true
                        Timber.d("Game paused due to app backgrounded (manual resume required)")
                    } else {
                        Timber.d("Game paused due to app backgrounded")
                    }
                }
            }
        }
        super.onPause()
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        var eventDispatched = PluviaApp.events.emit(AndroidEvent.KeyEvent(event)) { keyEvent ->
            keyEvent.any { it }
        } == true

        if (!eventDispatched) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && PluviaApp.keepAlive) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    PluviaApp.events.emit(AndroidEvent.BackPressed)
                    eventDispatched = true
                } else if (BuildConfig.MODERN_ANDROID && event.action == KeyEvent.ACTION_UP) {
                    eventDispatched = true
                }
            }
        }

        return if (!eventDispatched) super.dispatchKeyEvent(event) else true
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        val eventDispatched = PluviaApp.events.emit(AndroidEvent.MotionEvent(ev)) { event ->
            event.any { it }
        } == true

        return if (!eventDispatched) super.dispatchGenericMotionEvent(ev) else true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Log.d("MainActivity", "Requested orientation: $requestedOrientation => ${Orientation.fromActivityInfoValue(requestedOrientation)}")
    }

    private fun applyImmersiveMode() {
        if (desiredSystemUiVisible) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(true)
                window.insetsController?.show(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars(),
                )
            } else {
                @Suppress("DEPRECATION")
                run {
                    window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                }
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars(),
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !desiredSystemUiVisible) {
            applyImmersiveMode()
        }
    }

    private fun setOrientationTo(conformTo: EnumSet<Orientation>) {
        requestedOrientation = when {
            conformTo.contains(Orientation.PORTRAIT) -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            conformTo.contains(Orientation.LANDSCAPE) && conformTo.contains(Orientation.REVERSE_LANDSCAPE) ->
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            conformTo.contains(Orientation.LANDSCAPE) -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            conformTo.contains(Orientation.REVERSE_LANDSCAPE) -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
