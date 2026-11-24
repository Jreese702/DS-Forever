package app.gamenative.ui.model

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.enums.Orientation
import java.util.EnumSet
import app.gamenative.ui.data.MainState
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.WineProcessSnapshotHelper
import com.winlator.xserver.Window
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@HiltViewModel
class MainViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    companion object {
        private const val KEY_CURRENT_SCREEN_ROUTE = "current_screen_route"

        var gamePlayedThisSession = false
            private set
    }

    sealed class MainUiEvent {
        data object OnBackPressed : MainUiEvent()
        data object LaunchApp : MainUiEvent()
    }

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    private val _uiEvent = Channel<MainUiEvent>()
    val uiEvent = _uiEvent.receiveAsFlow()

    private val _offline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> get() = _offline

    fun setOffline(value: Boolean) {
        _offline.value = value
    }

    private val onBackPressed: (AndroidEvent.BackPressed) -> Unit = {
        viewModelScope.launch {
            _uiEvent.send(MainUiEvent.OnBackPressed)
        }
    }


    private val onSetBootingSplashText: (AndroidEvent.SetBootingSplashText) -> Unit = {
        if (_state.value.showBootingSplash) {
            setBootingSplashText(it.text)
        } else {
            // DS Forever: a step said before GameNative's splash state is up (the pre-launch) still reaches our launch screen
            com.jrees.wowcompanion.LaunchLoad.step(it.text, android.os.SystemClock.uptimeMillis())
        }
    }

    private val onClearBootingSplash: (AndroidEvent.ClearBootingSplash) -> Unit = {
        bootingSplashTimeoutJob?.cancel()
        bootingSplashTimeoutJob = null
        setShowBootingSplash(false)
    }

    private var bootingSplashTimeoutJob: Job? = null

    init {
        val persistedRoute = savedStateHandle.get<String>(KEY_CURRENT_SCREEN_ROUTE)
        val restoredScreen = when (persistedRoute) {
            PluviaScreen.XServer.route -> PluviaScreen.XServer
            else -> null
        }

        _state.update {
            it.copy(
                launchedAppId = "",
                currentScreen = restoredScreen,
            )
        }

        PluviaApp.events.on<AndroidEvent.BackPressed, Unit>(onBackPressed)
        PluviaApp.events.on<AndroidEvent.SetBootingSplashText, Unit>(onSetBootingSplashText)
        PluviaApp.events.on<AndroidEvent.ClearBootingSplash, Unit>(onClearBootingSplash)
    }

    override fun onCleared() {
        PluviaApp.events.off<AndroidEvent.BackPressed, Unit>(onBackPressed)
        PluviaApp.events.off<AndroidEvent.SetBootingSplashText, Unit>(onSetBootingSplashText)
        PluviaApp.events.off<AndroidEvent.ClearBootingSplash, Unit>(onClearBootingSplash)
    }

    fun setLoadingDialogVisible(value: Boolean) {
        _state.update { it.copy(loadingDialogVisible = value) }
    }

    fun setLoadingDialogProgress(value: Float) {
        _state.update { it.copy(loadingDialogProgress = value) }
        // DS Forever: the pre-launch's progress goes to our launch screen's bar (LaunchLoad; nothing while no launch shows)
        com.jrees.wowcompanion.LaunchLoad.progress(value, android.os.SystemClock.uptimeMillis())
    }

    fun setLoadingDialogMessage(value: String) {
        _state.update { it.copy(loadingDialogMessage = value) }
        com.jrees.wowcompanion.LaunchLoad.step(value, android.os.SystemClock.uptimeMillis())
    }

    fun setHasLaunched(value: Boolean) {
        _state.update { it.copy(hasLaunched = value) }
    }

    fun setShowBootingSplash(value: Boolean) {
        PluviaApp.isBootingSplashShowing = value
        _state.update { it.copy(showBootingSplash = value) }
    }

    fun setBootingSplashText(value: String) {
        _state.update { it.copy(bootingSplashText = value) }
        // DS Forever: GameNative's splash texts are our launch screen's steps, terse (LaunchLoad.terse); the splash itself never shows
        com.jrees.wowcompanion.LaunchLoad.step(value, android.os.SystemClock.uptimeMillis())
    }

    fun setCurrentScreen(currentScreen: String?) {
        // Route matching accounts for query params and path params in templates
        // e.g., "home?offline={offline}" should match Home, "chat/{id}" should match Chat
        val screen = when (currentScreen) {
            PluviaScreen.XServer.route -> PluviaScreen.XServer
            else -> PluviaScreen.WoWLauncher
        }

        setCurrentScreen(screen)
    }

    fun setCurrentScreen(value: PluviaScreen) {
        _state.update { it.copy(currentScreen = value) }
        savedStateHandle[KEY_CURRENT_SCREEN_ROUTE] = value.route
    }

    fun setScreen() {
        _state.update { it.copy(resettedScreen = it.currentScreen) }
    }

    fun setLaunchedAppId(value: String) {
        _state.update { it.copy(launchedAppId = value) }
    }

    private var launchAppJob: Job? = null

    fun launchApp(context: Context, appId: String) {
        gamePlayedThisSession = true
        PrefManager.hasAttemptedGameLaunch = true
        launchAppJob?.cancel()
        launchAppJob = viewModelScope.launch {
            com.jrees.wowcompanion.LaunchLoad.begin(android.os.SystemClock.uptimeMillis())     // (already up since Play: goes on)
            setShowBootingSplash(true)
            PluviaApp.events.emit(AndroidEvent.SetAllowedOrientation(PrefManager.allowedOrientation))

            val apiJob = viewModelScope.async(Dispatchers.IO) {
                ContainerUtils.getOrCreateContainer(context, appId)
            }

            delay(100)
            if (!isActive) return@launch
            apiJob.await()
            if (!isActive) return@launch
            _uiEvent.send(MainUiEvent.LaunchApp)
        }
    }

    fun exitApp(context: Context, appId: String, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            try {
                com.jrees.wowcompanion.LaunchLoad.end(android.os.SystemClock.uptimeMillis(), "the game screen closed")
                bootingSplashTimeoutJob?.cancel()
                bootingSplashTimeoutJob = null
                setShowBootingSplash(false)
                PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
            } finally {
                onComplete?.invoke()
            }
        }
    }

    fun onWindowMapped(context: Context, window: Window, appId: String) {
        // DS Forever: the game's window is up, but its first picture is still to come: our launch screen stays until it (LaunchLoad,
        // the user's pick "The game's first real picture"); GameNative's splash state goes as before
        com.jrees.wowcompanion.LaunchLoad.windowMapped(android.os.SystemClock.uptimeMillis())
        viewModelScope.launch {
            bootingSplashTimeoutJob?.cancel()
            bootingSplashTimeoutJob = null
            setShowBootingSplash(false)
            PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
        }
    }

    fun onGameLaunchError(error: String) {
        com.jrees.wowcompanion.LaunchLoad.end(android.os.SystemClock.uptimeMillis(), "launch error: $error")
        viewModelScope.launch {
            // Hide the splash screen if it's still showing
            bootingSplashTimeoutJob?.cancel()
            bootingSplashTimeoutJob = null
            setShowBootingSplash(false)
            PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)

            // You could also show an error dialog here if needed
            Timber.tag("MainViewModel").e("Game launch error: $error")
        }
    }

    /** The splash's back button: hide the splash and close the guest the way a blocked session does. */
    fun abortBoot() {
        com.jrees.wowcompanion.LaunchLoad.end(android.os.SystemClock.uptimeMillis(), "the abort chord")
        launchAppJob?.cancel()
        launchAppJob = null
        viewModelScope.launch {
            Timber.tag("MainViewModel").i("Boot aborted from the splash")
            bootingSplashTimeoutJob?.cancel()
            bootingSplashTimeoutJob = null
            setShowBootingSplash(false)
            PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
            PluviaApp.events.emit(AndroidEvent.ForceCloseApp)
        }
    }

}
