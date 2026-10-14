package app.gamenative.ui.screen.wow

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.screen.wow.WowClientDownloader.BUILD_INFO
import com.jrees.wowcompanion.WowProduct
import app.gamenative.ui.theme.WowBackgroundGradient
import app.gamenative.ui.theme.WowBronze
import app.gamenative.ui.theme.WowError
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowMuted
import app.gamenative.ui.theme.WowSubtle
import app.gamenative.utils.AppUpdater
import app.gamenative.utils.StorageUtils
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.FileUtils
import com.winlator.core.GPUInformation
import com.winlator.core.TarCompressorUtils
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.ImageFsInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import timber.log.Timber
import java.io.File

object WoWLauncherState {
    var shouldAutoLaunch = true
}

private const val CONTAINER_ID = "wow_forever"
private const val READY_STATUS = "Ready to launch"
// how long the auto-launch waits for the app's own update check before it goes on without its answer
private const val APP_CHECK_WAIT_MS = 5_000L

@Composable
fun WoWForeverScreen(
    onLaunch: (appId: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gpu = remember { GpuProfile.detect(context) }

    var gamePath by remember { mutableStateOf(GamePath.load(context)) }
    var files by remember { mutableStateOf(GamePath.Status()) }
    var isLaunching by remember { mutableStateOf(false) }
    var launchJob by remember { mutableStateOf<Job?>(null) }
    var statusText by remember { mutableStateOf(READY_STATUS) }
    var isHarvesting by remember {
        mutableStateOf(com.jrees.wowcompanion.ArtHarvest.state is com.jrees.wowcompanion.ArtHarvest.State.Running)
    }
    var artStatusText by remember { mutableStateOf<String?>(null) }
    var artProgress by remember { mutableFloatStateOf(0f) }
    var artReady by remember { mutableStateOf(false) }
    // interiors (Track C): the optional 7th step's own row; it never gates Play
    var interiors by remember { mutableStateOf(com.jrees.wowcompanion.IndoorSchedulerStep.readiness(context)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // DS FOREVER SETUP ON TWO SCREENS (the user's fresh-install test 2026-10-12, Track C): this top screen shows the checks only;
    // Play and ONE pop-up at a time are on the bottom screen (SetupBridge -> the companion's SetupPanel). The Battle.net login is a
    // bottom pop-up until it is saved or "Play without" is picked; the art builds by itself once the game folder is found.
    var loginSaved by remember { mutableStateOf(com.jrees.wowcompanion.HomeLoginStore.has(context)) }
    var loginSkipped by remember { mutableStateOf(false) }
    var appUpdateLater by remember { mutableStateOf(false) }
    // DS Forever on the SD card / adopted storage (p-sd-card-install): the setup says so first; checked once per screen
    val installedExternal = remember {
        runCatching {
            val ai = context.applicationInfo
            val onDefault = android.os.Build.VERSION.SDK_INT < 26 || ai.storageUuid == android.os.storage.StorageManager.UUID_DEFAULT
            com.jrees.wowcompanion.InstallCheck.external(ai.flags, onDefault, ai.sourceDir.orEmpty(), ai.dataDir.orEmpty())
        }.getOrNull()?.also { Timber.w("DS Forever is installed on $it") } != null
    }
    // an auto-start / restart / after-game-update launch held by the "DS Forever vX is out" pop-up: "Later" lets it go on
    var launchAfterAppChoice by remember { mutableStateOf(false) }
    var addonsInstalled by remember { mutableStateOf(false) }
    /** a required-art failure's reason: the bottom's "Try again" (nothing retries by itself after a failure) */
    var artFailed by remember { mutableStateOf<String?>(null) }
    /** the dependency add-ons' line (QuestieDB, M4); null = not checked in this launcher run */
    var dependencies by remember { mutableStateOf<String?>(null) }
    var versionStatus by remember { mutableStateOf<WowClientDownloader.VersionCheckResult?>(null) }
    var isCheckingVersion by remember { mutableStateOf(false) }
    var isUpdating by remember { mutableStateOf(false) }
    var updateStatusText by remember { mutableStateOf("") }
    // DS Forever's self-update (Track C): the startup check's answer; the release when one is newer
    var appCheck by remember { mutableStateOf<com.jrees.wowcompanion.AppUpdate.Check?>(null) }
    val appUpdate = (appCheck as? com.jrees.wowcompanion.AppUpdate.Check.Available)?.release
    var isDownloadingAppUpdate by remember { mutableStateOf(false) }
    var appUpdateProgress by remember { mutableFloatStateOf(0f) }
    var pendingInstallPermission by remember { mutableStateOf(false) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }

    suspend fun fetchAppUpdate() {
        AppUpdater.clearCacheOnce(context)
        appCheck = AppUpdater.check()
    }

    fun checkAppUpdate() {
        scope.launch { fetchAppUpdate() }
    }

    fun installOrRequestPermission(apk: File) {
        if (AppUpdater.canInstall(context)) {
            AppUpdater.install(context, apk)
        } else {
            pendingInstallPermission = true
            AppUpdater.openInstallPermissionSettings(context)
        }
    }

    fun performAppUpdate() {
        val target = appUpdate ?: return
        if (isDownloadingAppUpdate) return
        // never while the game runs (the same rule as QuestieDB)
        if (com.jrees.wowcompanion.LauncherHooks.gameUp()) { errorMessage = "Close the game first, then update"; return }
        downloadedApk?.takeIf { it.exists() && it.length() == target.size }?.let { apk ->
            installOrRequestPermission(apk)
            return
        }
        isDownloadingAppUpdate = true
        appUpdateProgress = 0f
        errorMessage = null
        scope.launch {
            try {
                val apk = AppUpdater.download(context, target) { progress ->
                    appUpdateProgress = progress
                }
                downloadedApk = apk
                isDownloadingAppUpdate = false
                installOrRequestPermission(apk)
            } catch (e: CancellationException) {
                isDownloadingAppUpdate = false
                throw e
            } catch (e: SecurityException) {
                // not ours (another app, another certificate, not newer): refused and deleted, said plainly
                Timber.e(e, "App update refused")
                errorMessage = "The update was refused: ${e.message}"
                downloadedApk = null
                isDownloadingAppUpdate = false
            } catch (e: Exception) {
                Timber.e(e, "Error downloading app update")
                errorMessage = "The update download failed: ${e.message}"
                downloadedApk = null
                isDownloadingAppUpdate = false
            }
        }
    }

    suspend fun fetchVersionStatus() {
        isCheckingVersion = true
        versionStatus = withContext(Dispatchers.IO) { WowClientDownloader.checkVersion(File(gamePath)) }
        isCheckingVersion = false
    }

    fun checkVersionStatus() {
        if (!File(gamePath, BUILD_INFO).exists()) return
        scope.launch { fetchVersionStatus() }
    }

    fun checkFiles(): Boolean {
        GamePath.save(context, gamePath)
        files = GamePath.status(context, gamePath)
        artReady = runCatching { com.jrees.wowcompanion.ArtHarvest.beforePlayReady(context) }.getOrDefault(false)
        loginSaved = com.jrees.wowcompanion.HomeLoginStore.has(context)
        addonsInstalled = File(WowClientDownloader.flavorDir(File(gamePath)), "Interface/AddOns/WowCompanion").isDirectory
        if (files.isWrongGame) {
            errorMessage = "Incompatible game (${files.product}). DS Forever needs " +
                WowProduct.values().joinToString(" or ") { "${it.title} (${it.code})" } + "."
        }
        return files.isReady
    }

    fun denyStorageAccess() {
        files = files.copy(hasStorageAccess = false)      // the bottom's "Allow file access" pop-up
    }

    fun resetLaunch() {
        launchJob?.cancel()
        launchJob = null
        isLaunching = false
        statusText = READY_STATUS
    }

    /** [by] = who launches ("play": the bottom's Play / auto-launch, "restart": the restart's own) - the restart's log names it */
    fun launchGame(by: String = "play") {
        val restart = com.jrees.wowcompanion.SetupBridge.restarting
        fun refused(why: String) {
            if (restart) com.jrees.wowcompanion.SetupBridge.log?.invoke("restart: Play by $by refused ($why)")
            if (restart && by == "restart") com.jrees.wowcompanion.SetupBridge.restarting = false
        }
        if (isLaunching || isUpdating || (isHarvesting && !artReady) || files.isWrongGame) {
            refused("launching=$isLaunching updating=$isUpdating harvesting=$isHarvesting wrongGame=${files.isWrongGame}"); return
        }
        // the "DS Forever vX is out" pop-up waits for Update or Later before any launch (board b-update-blocks-play). The state
        // itself, not the composition's appUpdate val: the auto-launch calls the first composition's launchGame
        if (appCheck is com.jrees.wowcompanion.AppUpdate.Check.Available && !appUpdateLater) {
            refused("app update pop-up waits for Update or Later")
            launchAfterAppChoice = true
            PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
            return
        }
        if (!artReady) {
            refused("art not ready")
            errorMessage = "Game art is required before Play"
            PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
            return
        }
        if (!files.hasStorageAccess) return                // the bottom asks for it
        isLaunching = true
        errorMessage = null
        statusText = "Preparing components..."

        launchJob = scope.launch {
            try {
                // DS FOREVER'S ART HARVEST (the user 2026-10-09, "Mostly before Play"): the companion's pictures are built from this
                // install's own game files before the game starts (the kit, the icons, the world maps); the rest builds in the background.
                // Required art must still be ready for the active build when Play is pressed.
                if (!awaitArtHarvest(context) { msg -> scope.launch(Dispatchers.Main) { statusText = msg } }) {
                    checkFiles()
                    errorMessage = artStatusText ?: "Game art is required before Play"
                    statusText = "Game art is not ready"
                    isLaunching = false
                    PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
                    return@launch
                }
                val containerId = withContext(Dispatchers.IO) {
                    prepareLaunch(context, File(gamePath), gpu) { msg ->
                        scope.launch(Dispatchers.Main) { statusText = msg }
                    }
                }
                statusText = "Booting into World of Warcraft..."
                if (restart) com.jrees.wowcompanion.SetupBridge.log?.invoke("restart: container launching (by $by)")
                onLaunch(containerId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: StorageAccessDeniedException) {
                isLaunching = false
                statusText = READY_STATUS
                denyStorageAccess()
            } catch (e: Exception) {
                refused("launch failed: ${e.message}")
                Timber.e(e, "Error launching WoW Forever")
                errorMessage = e.message ?: "Launch failed"
                statusText = "Launch failed"
                isLaunching = false
            }
        }
    }

    LifecycleResumeEffect(gamePath) {
        checkFiles()
        isLaunching = false
        statusText = READY_STATUS
        if (versionStatus == null && !WoWLauncherState.shouldAutoLaunch) {
            checkVersionStatus()
        }
        if (appCheck == null && !WoWLauncherState.shouldAutoLaunch) {
            checkAppUpdate()
        }
        if (pendingInstallPermission && AppUpdater.canInstall(context)) {
            pendingInstallPermission = false
            downloadedApk?.takeIf { it.exists() }?.let { AppUpdater.install(context, it) }
        }
        onPauseOrDispose {
            launchJob?.cancel()
            launchJob = null
        }
    }

    DisposableEffect(gamePath) {
        val harvestListener: (com.jrees.wowcompanion.ArtHarvest.State) -> Unit = { state ->
            scope.launch(Dispatchers.Main) {
                when (state) {
                    is com.jrees.wowcompanion.ArtHarvest.State.Running -> {
                        isHarvesting = true
                        if (state.beforePlay) artFailed = null
                        if (state.beforePlay) artReady = false else if (!artReady) checkFiles()
                        artProgress = state.fraction
                        val phase = if (state.beforePlay) "Harvesting art" else "Harvesting background art"
                        artStatusText = "$phase: ${(state.fraction * 100).toInt()}%${if (state.what.isBlank()) "" else " - ${state.what}"}"
                    }
                    is com.jrees.wowcompanion.ArtHarvest.State.Ready -> {
                        isHarvesting = false
                        artFailed = null
                        checkFiles()
                        artStatusText = "Game art is ready"
                    }
                    is com.jrees.wowcompanion.ArtHarvest.State.Failed -> {
                        isHarvesting = false
                        checkFiles()
                        if (state.beforePlay) {
                            artReady = false
                            artFailed = state.why
                            WoWLauncherState.shouldAutoLaunch = false
                            PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
                        }
                        val phase = if (state.beforePlay) "Art harvest failed" else "Background art harvest failed"
                        artStatusText = "$phase: ${state.why}"
                    }
                    else -> Unit
                }
            }
        }
        com.jrees.wowcompanion.ArtHarvest.onState(harvestListener)
        harvestListener(com.jrees.wowcompanion.ArtHarvest.state)
        val interiorsListener: () -> Unit = {
            val r = com.jrees.wowcompanion.IndoorSchedulerStep.readiness(context)
            scope.launch(Dispatchers.Main) { interiors = r }
        }
        com.jrees.wowcompanion.IndoorSchedulerStep.onChange(interiorsListener)
        val reset: (Any) -> Unit = { resetLaunch() }
        PluviaApp.events.on<AndroidEvent.ForceCloseApp, Unit>(reset)
        PluviaApp.events.on<AndroidEvent.GuestProgramTerminated, Unit>(reset)
        onDispose {
            com.jrees.wowcompanion.ArtHarvest.offState(harvestListener)
            com.jrees.wowcompanion.IndoorSchedulerStep.offChange(interiorsListener)
            PluviaApp.events.off<AndroidEvent.ForceCloseApp, Unit>(reset)
            PluviaApp.events.off<AndroidEvent.GuestProgramTerminated, Unit>(reset)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = StorageUtils.getPathFromTreeUri(context, uri) ?: return@rememberLauncherForActivityResult
        val root = GamePath.findGameRoot(File(path))
        if (root == null) {
            errorMessage = "No $BUILD_INFO found in $path. Pick the folder that contains $BUILD_INFO and Data/."
            return@rememberLauncherForActivityResult
        }
        errorMessage = null
        gamePath = root.absolutePath
        GamePath.save(context, gamePath)
        versionStatus = null
        val ok = checkFiles()
        if (ok) checkVersionStatus()
    }

    val filesMissing = !(files.dataExists && files.buildInfoExists)

    /** the art build, BY ITSELF once the game folder is found (the user's pick "Automatic, no button"); [retry] = the bottom's Try again */
    fun harvestArt(retry: Boolean = false) {
        if (isLaunching || isUpdating || isHarvesting || files.isWrongGame || filesMissing || !files.hasStorageAccess) return
        if (artFailed != null && !retry) return
        GamePath.save(context, gamePath)
        artFailed = null
        isHarvesting = true
        artProgress = 0f
        scope.launch {
            try {
                withContext(Dispatchers.IO) { com.jrees.wowcompanion.ArtHarvest.start(context) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                isHarvesting = false
                artFailed = e.message ?: "could not open the game files"
            }
        }
    }

    // the art starts BY ITSELF as soon as the game folder is usable (and re-checks silently at every start: nothing pending = Ready)
    LaunchedEffect(gamePath, files.dataExists, files.buildInfoExists, files.hasStorageAccess, files.isWrongGame) { harvestArt() }

    // RIGHT AFTER THE ART CHECK: the dependency add-ons (QuestieDB from its own GitHub releases; the user's approval 2026-10-12).
    // Background, once per launcher run; it never blocks Play and says what it did on the top screen
    LaunchedEffect(artReady, files.hasStorageAccess, gamePath) {
        if (!artReady || !files.hasStorageAccess || filesMissing || dependencies != null) return@LaunchedEffect
        dependencies = "QuestieDB: checking for the latest"
        try {
            dependencies = withContext(Dispatchers.IO) {
                runCatching {
                    com.jrees.wowcompanion.DependencyAddons.run(WowClientDownloader.flavorDir(File(gamePath)),
                        File(context.cacheDir, "dsforever"), File(context.filesDir, "dsforever/dependencies.json"))
                }.getOrElse { "QuestieDB skipped (${it.message})" }
            }
        } catch (e: CancellationException) {
            // a key changed mid-check (file access, the folder): free the line so the next run of this effect checks again
            dependencies = null
            throw e
        }
    }

    fun performUpdate() {
        val target = versionStatus ?: return
        if (isUpdating || isLaunching || isHarvesting) return
        if (!files.hasStorageAccess) return                // the bottom asks for it
        isUpdating = true
        updateStatusText = "Connecting to Blizzard CDN..."
        errorMessage = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    WowClientDownloader.updateGame(File(gamePath), target) { msg ->
                        scope.launch(Dispatchers.Main) { updateStatusText = msg }
                    }
                }
                checkFiles()
                versionStatus = versionStatus?.copy(isOutdated = false, localVersion = target.remoteVersion)
                isUpdating = false
                // a new game build needs its art built again (it is keyed by the build): start the check now, not at the next app
                // start (13 Oct, 70338 -> 70374: Play stayed on "Game art: checking" until the app was restarted)
                harvestArt()
                launchGame()
            } catch (e: Exception) {
                Timber.e(e, "Error updating game")
                if (e.isPermissionDenied()) denyStorageAccess()
                errorMessage = "Update failed: ${e.message}"
                isUpdating = false
            }
        }
    }

    LaunchedEffect(gamePath) {
        val ready = checkFiles()
        Timber.i("WoWForeverScreen LaunchedEffect: gamePath=$gamePath, ready=$ready, shouldAutoLaunch=${WoWLauncherState.shouldAutoLaunch}")
        when {
            !ready || !artReady -> {
                WoWLauncherState.shouldAutoLaunch = false
                PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
                if (ready) {
                    checkVersionStatus()
                    checkAppUpdate()
                }
            }
            !WoWLauncherState.shouldAutoLaunch -> {
                checkVersionStatus()
                checkAppUpdate()
            }
            else -> {
                // the app's update check runs beside the client check; the auto-launch waits for it a few seconds at most (no
                // network = no wait past that). A newer version holds the launch at its pop-up until Update or Later (launchGame)
                val appJob = scope.launch { fetchAppUpdate() }
                fetchVersionStatus()
                withTimeoutOrNull(APP_CHECK_WAIT_MS) { appJob.join() }
                Timber.i("WoWForeverScreen LaunchedEffect: isOutdated=${versionStatus?.isOutdated}")
                WoWLauncherState.shouldAutoLaunch = false
                if (versionStatus?.isOutdated == true) {
                    PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
                } else {
                    launchGame()
                }
            }
        }
    }

    val harvestState = com.jrees.wowcompanion.ArtHarvest.state
    val setupInputs = com.jrees.wowcompanion.SetupFlow.Inputs(
        path = gamePath,
        folderFound = !filesMissing,
        wrongGame = files.isWrongGame,
        product = files.product,
        storage = files.hasStorageAccess,
        loginSaved = loginSaved,
        loginSkipped = loginSkipped,
        art = when {
            artFailed != null -> com.jrees.wowcompanion.SetupFlow.Art.FAILED
            artReady -> com.jrees.wowcompanion.SetupFlow.Art.READY
            isHarvesting && (harvestState as? com.jrees.wowcompanion.ArtHarvest.State.Running)?.beforePlay == true ->
                com.jrees.wowcompanion.SetupFlow.Art.RUNNING
            else -> com.jrees.wowcompanion.SetupFlow.Art.IDLE
        },
        artPercent = (artProgress * 100).toInt(),
        artWhat = (harvestState as? com.jrees.wowcompanion.ArtHarvest.State.Running)?.what.orEmpty(),
        artWhy = artFailed.orEmpty(),
        addonsInstalled = addonsInstalled,
        dependencies = dependencies,
        indoor = interiors.label.substringAfter(": "),
        client = versionStatus?.let { if (it.isOutdated) "${it.localVersion} (${it.remoteVersion} needed)" else "${it.localVersion} (up to date)" },
        gameOutdated = versionStatus?.takeIf { it.isOutdated }?.remoteVersion,
        appUpdate = appUpdate?.takeIf { !appUpdateLater }?.let { "v${it.version}" },
        appLine = AppUpdater.line(appCheck),
        // the background all-icons step (Track C, test23): ready when its stamp says so, else its count while it runs
        installedExternal = installedExternal,
        iconsLine = com.jrees.wowcompanion.AllIcons.line(com.jrees.wowcompanion.ArtHarvest.state is com.jrees.wowcompanion.ArtHarvest.State.Ready),
        busy = when {
            isUpdating -> updateStatusText
            isDownloadingAppUpdate -> "Downloading the update: ${(appUpdateProgress * 100).toInt()}%"
            isLaunching -> statusText
            else -> null
        },
    )
    SideEffect { com.jrees.wowcompanion.SetupBridge.publish(setupInputs) }

    // the bottom's buttons call into this screen; always the CURRENT composition's functions (filesMissing etc. are per composition)
    val onSetupAction by rememberUpdatedState<(com.jrees.wowcompanion.SetupFlow.Action) -> Unit> { a ->
        when (a) {
            com.jrees.wowcompanion.SetupFlow.Action.CHOOSE_FOLDER -> folderPicker.launch(null)
            com.jrees.wowcompanion.SetupFlow.Action.GRANT_STORAGE -> StorageUtils.requestManageExternalStoragePermission(context)
            com.jrees.wowcompanion.SetupFlow.Action.SKIP_LOGIN -> loginSkipped = true
            com.jrees.wowcompanion.SetupFlow.Action.RETRY_ART -> harvestArt(retry = true)
            com.jrees.wowcompanion.SetupFlow.Action.UPDATE_GAME -> performUpdate()
            com.jrees.wowcompanion.SetupFlow.Action.UPDATE_APP -> performAppUpdate()
            com.jrees.wowcompanion.SetupFlow.Action.LATER_APP -> {
                appUpdateLater = true
                if (launchAfterAppChoice) { launchAfterAppChoice = false; launchGame() }
            }
            com.jrees.wowcompanion.SetupFlow.Action.PLAY -> launchGame()
            com.jrees.wowcompanion.SetupFlow.Action.OPEN_APP_SETTINGS -> runCatching {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${context.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Timber.w(it, "app settings") }
            com.jrees.wowcompanion.SetupFlow.Action.RELAUNCH -> launchGame(by = "restart")
            else -> Unit          // the companion's own (the login form, the sign-in pop-ups)
        }
    }
    val onSetupRefresh by rememberUpdatedState { checkFiles() }
    DisposableEffect(Unit) {
        // "Restart the game to sign in" (M3) is pressed while the game runs, when this screen is gone. TEST4: the top stuck "Starting
        // game" - the old route set shouldAutoLaunch, which this screen consumed the moment it came back, seconds into the old Wine
        // session's teardown (stopEnvironmentComponents), so the new launch raced the old one. Now the restart drives itself: close,
        // WAIT until no Wine process of ours is left (2 s in a row, 30 s max) and this screen is back, then Play; every step logged
        // (WCLOGIN "restart: ..."). Not cleared on dispose (the game screen is up when it is pressed).
        com.jrees.wowcompanion.SetupBridge.restartGame = {
            val log = com.jrees.wowcompanion.SetupBridge.log
            com.jrees.wowcompanion.SetupBridge.restarting = true
            WoWLauncherState.shouldAutoLaunch = false
            log?.invoke("restart: close requested")
            CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
                // the user's "Both" ruling after test20: past the login screen the game exits by itself first (its own Exit Game:
                // SavedVariables written); at the login screen, in a fight or with no companion: the old close at once
                val gone = kotlinx.coroutines.CompletableDeferred<Boolean>()
                val ce = com.jrees.wowcompanion.LauncherHooks.cleanExit
                if (ce == null) gone.complete(true) else ce("restart") { force -> gone.complete(force) }
                if (gone.await()) PluviaApp.events.emit(AndroidEvent.ForceCloseApp)
                val t0 = System.currentTimeMillis()
                fun secs() = (System.currentTimeMillis() - t0) / 1000
                var clear = 0
                var left = -1
                while (clear < 2 && System.currentTimeMillis() - t0 < 30_000) {
                    delay(1_000)
                    left = withContext(Dispatchers.IO) { runCatching { app.gamenative.utils.WineProcessSnapshotHelper.readFromProc().size }.getOrDefault(-1) }
                    clear = if (left == 0) clear + 1 else 0
                }
                log?.invoke(if (clear >= 2) "restart: old game gone after ${secs()} s" else "restart: old game still running after ${secs()} s ($left processes)")
                while (com.jrees.wowcompanion.SetupBridge.launcherActions == null && System.currentTimeMillis() - t0 < 40_000) delay(250)
                val play = com.jrees.wowcompanion.SetupBridge.launcherActions
                if (play == null) {
                    log?.invoke("restart: failed, the launcher screen did not come back (${secs()} s)")
                    com.jrees.wowcompanion.SetupBridge.restarting = false
                    return@launch
                }
                delay(1_000)                     // the screen's own start checks first
                log?.invoke("restart: launcher back, launching (${secs()} s)")
                play(com.jrees.wowcompanion.SetupFlow.Action.RELAUNCH)
            }
        }
        com.jrees.wowcompanion.SetupBridge.launcherActions = { a -> scope.launch(Dispatchers.Main) { onSetupAction(a) } }
        com.jrees.wowcompanion.SetupBridge.launcherRefresh = { scope.launch(Dispatchers.Main) { onSetupRefresh() } }
        // the pad belongs to the bottom screen's buttons while this screen is up (the top shows checks only)
        val keys: (AndroidEvent.KeyEvent) -> Boolean = { e ->
            val k = e.event
            val pad = k.source and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD ||
                k.source and android.view.InputDevice.SOURCE_DPAD == android.view.InputDevice.SOURCE_DPAD
            pad && (k.action == android.view.KeyEvent.ACTION_DOWN || k.action == android.view.KeyEvent.ACTION_UP) &&
                com.jrees.wowcompanion.SetupBridge.key(k.keyCode, k.action == android.view.KeyEvent.ACTION_DOWN && k.repeatCount == 0)
        }
        PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(keys)
        onDispose {
            PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(keys)
            com.jrees.wowcompanion.SetupBridge.launcherActions = null
            com.jrees.wowcompanion.SetupBridge.launcherRefresh = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(WowBackgroundGradient))
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // one screen, no scrolling (the user 2026-10-12): no logo (it was the upstream launcher's game art); the title sits
        // left of the checks so the whole list fits the top screen's landscape height
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(modifier = Modifier.weight(0.38f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "WORLD OF WARCRAFT",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 3.sp,
                    color = Color(0xFFF0E6D2)
                )
                Text(
                    text = "FOREVER BETA (ARM64 NATIVE)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = WowGold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = "${gpu.edition} Edition", fontSize = 11.sp, color = WowSubtle)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Play and the choices are on the bottom screen", fontSize = 12.sp, color = WowSubtle,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }

            Card(
                modifier = Modifier
                    .weight(0.62f)
                    .border(1.dp, Color(0xFF2A3C54), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E).copy(alpha = 0.85f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    SectionTitle("ENVIRONMENT READINESS", WowGold)
                    Spacer(modifier = Modifier.height(10.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // the checks only (Track C): Play and every button are on the bottom screen
                        for (check in com.jrees.wowcompanion.SetupFlow.checks(setupInputs)) {
                            CheckItem(label = check.label, mark = check.mark)
                            if (check.label.startsWith("Game art:") && setupInputs.art == com.jrees.wowcompanion.SetupFlow.Art.RUNNING) {
                                LinearProgressIndicator(progress = { artProgress }, modifier = Modifier.fillMaxWidth(), color = WowGold)
                            }
                        }
                        setupInputs.busy?.let { Text(it, fontSize = 13.sp, color = Color(0xFFE2E8F0)) }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = Color(0xFF1E2D44))
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(text = "Target Path: $gamePath", fontSize = 11.sp, color = WowSubtle, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)

                    errorMessage?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = "Error: $it", fontSize = 12.sp, color = WowError, fontWeight = FontWeight.Medium, maxLines = 2)
                    }
                }
            }
        }
    }

}

object GamePath {
    private const val PREFS = "wow_forever"
    private const val KEY_GAME_PATH = "game_path"

    private val defaultPath = File(Environment.getExternalStorageDirectory(), "WoW Forever")

    data class Status(
        val exeExists: Boolean = false,
        val dataExists: Boolean = false,
        val buildInfoExists: Boolean = false,
        val product: String = "",
        val hasStorageAccess: Boolean = true,
    ) {
        val isWrongGame get() = buildInfoExists && product.isNotBlank() && WowProduct.forCode(product) == null
        val isReady get() = exeExists && dataExists && buildInfoExists && hasStorageAccess && !isWrongGame
    }

    fun status(context: Context, path: String): Status {
        val root = File(path)
        return Status(
            exeExists = WowClientDownloader.exe(root).exists(),
            dataExists = File(root, "Data").listFiles()?.isNotEmpty() == true,
            buildInfoExists = File(root, BUILD_INFO).isFile,
            product = productOf(root).orEmpty(),
            hasStorageAccess = StorageUtils.hasStoragePermission(context, path),
        )
    }

    fun load(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_GAME_PATH, null)?.let(::File)
        val usable = listOfNotNull(saved, defaultPath).firstOrNull { WowProduct.forCode(productOf(it)) != null }
        return (usable ?: saved ?: defaultPath).absolutePath
    }

    fun save(context: Context, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_GAME_PATH, path).apply()
    }

    fun isReady(context: Context, path: String = load(context)): Boolean =
        path.isNotEmpty() && status(context, path).let { it.isReady && WowProduct.forCode(it.product) != null }

    fun findGameRoot(picked: File): File? =
        sequenceOf(picked, picked.parentFile, File(picked, "WoW Forever"), File(picked, "World of Warcraft"))
            .filterNotNull()
            .firstOrNull { File(it, BUILD_INFO).isFile }

    private fun productOf(root: File) = WowClientDownloader.readBuildInfo(File(root, BUILD_INFO))?.get("Product")
}

@Composable
private fun SectionTitle(text: String, color: Color) {
    Text(text = text, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = color)
}

@Composable
private fun CheckItem(label: String, mark: com.jrees.wowcompanion.SetupFlow.Mark) {
    // OK green tick; WAIT and OPTIONAL neutral (not done yet is not a warning); BAD orange
    val colour = when (mark) {
        com.jrees.wowcompanion.SetupFlow.Mark.OK -> Color(0xFF48BB78)
        com.jrees.wowcompanion.SetupFlow.Mark.BAD -> Color(0xFFED8936)
        else -> Color(0xFF94A3B8)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = when (mark) {
                com.jrees.wowcompanion.SetupFlow.Mark.OK -> Icons.Default.CheckCircle
                com.jrees.wowcompanion.SetupFlow.Mark.BAD -> Icons.Default.Warning
                else -> Icons.Default.Refresh
            },
            contentDescription = null,
            tint = colour,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = label, fontSize = 13.sp, color = if (mark == com.jrees.wowcompanion.SetupFlow.Mark.OK) Color(0xFFCBD5E1) else colour)
    }
}

private class StorageAccessDeniedException(cause: Throwable) : Exception(cause)

private fun Throwable.isPermissionDenied() =
    message?.let { "EPERM" in it || "Operation not permitted" in it } == true

private const val DEFAULT_DRIVER = "Turnip-WoW-scheduler-test"
private const val ADRENO_8XX_DRIVER = "Turnip-V32-RP6sched"
private const val PROTON_VERSION = "proton-11.0-90624-arm64ec"
private const val DXVK_VERSION = "2.4.1-wow-aarch64-test"

private data class GpuProfile(val driver: String, val tuDebug: String, val screenSize: String, val label: String, val edition: String) {
    companion object {
        fun detect(context: Context) = if (isAdreno8xxDevice(context)) {
            GpuProfile(ADRENO_8XX_DRIVER, "noconform,sysmem", "1280x720", "Adreno 8xx", "Snapdragon 8 Elite / Adreno 8xx")
        } else {
            GpuProfile(DEFAULT_DRIVER, "noconform", "1920x1080", "Adreno 740", "Snapdragon 8 Series / Adreno 740")
        }

        private fun isAdreno8xxDevice(context: Context): Boolean {
            if (GPUInformation.isAdreno8Elite(context)) return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val soc = Build.SOC_MODEL
                if (soc.contains("SM8750", ignoreCase = true) || soc.contains("SM8850", ignoreCase = true)) return true
            }
            return Build.HARDWARE.contains("sun", ignoreCase = true)
        }
    }
}

private fun prepareLaunch(context: Context, gameRoot: File, gpu: GpuProfile, onStatus: (String) -> Unit): String {
    installBundledComponents(context, onStatus)

    check(File(gameRoot, BUILD_INFO).exists()) { "Missing $BUILD_INFO in $gameRoot. Copy it from your WoW install." }
    // launch support: the client this folder runs (the beta or the launch client, from its .build.info), for every path below
    val choice = WowProduct.choose(gameRoot)
    val product = choice?.product ?: WowProduct.of(gameRoot)
    try {
        ensureGameConfig(gameRoot, product, choice?.row)
    } catch (e: Exception) {
        if (e.isPermissionDenied()) throw StorageAccessDeniedException(e)
        throw e
    }

    var arm64Exe = product.exe(gameRoot)
    // DS Forever fork: never a credential file; one left by WoW Forever (same game folder) or an old saved login is deleted
    BattleNetSignIn.removeLoginFile(gameRoot)
    BattleNetSignIn.forget(context)
    // DS Forever S5: its add-on set and Config.wtf keys, from its own bundle (the old app's root setup, now in-process)
    onStatus(com.jrees.wowcompanion.LauncherSetup.run(context, File(gameRoot, product.flavorDir)))
    if (!arm64Exe.exists()) {
        try {
            WowClientDownloader.download(gameRoot, onStatus)
        } catch (e: Exception) {
            if (!product.exe(gameRoot).exists()) throw IllegalStateException("Couldn't download ${product.exeName}: ${e.message}", e)
            Timber.w(e, "Client update check failed, launching the existing ${product.exeName}")
        }
        arm64Exe = product.exe(gameRoot)          // as the download named it on disk
    }

    onStatus("Configuring ${gpu.label} container...")
    val containerManager = ContainerManager(context)
    // DS Forever: the native sign-in (the user's "(a) Private folder"): Wine's G: is a private shadow of the game folder holding this
    // run's login.txt (deleted on the client's read). If the shadow fails, the shared folder WITHOUT a login file (no fallback)
    val wineRoot = com.jrees.wowcompanion.NativeLogin.prepare(context, gameRoot, product.flavorDir) ?: gameRoot
    val config = containerConfig(wineRoot, gpu, "G:\\${product.flavorDir}\\${arm64Exe.name}")
    val container = containerManager.getContainerById(CONTAINER_ID)
    if (container == null) {
        onStatus("Creating prefix environment (first boot)...")
        containerManager.createContainer(CONTAINER_ID, config)
    } else {
        container.loadData(config)
        container.saveData()
    }
    check(containerManager.hasContainer(CONTAINER_ID)) { "Container creation failed. Check system storage and logs." }
    // Frame generation (board f-frame-gen): Settings' values with the player's own Lossless.dll; Off = armed false, nothing else
    com.jrees.wowcompanion.FrameGen.init(context)
    containerManager.getContainerById(CONTAINER_ID)?.let { app.gamenative.utils.LsfgVkManager.applyDsForever(it); it.saveData() }
    return CONTAINER_ID
}

private fun containerConfig(gameRoot: File, gpu: GpuProfile, executablePath: String): JSONObject {
    val samsungEnv = if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) " FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1" else ""
    val envVars = "WRAPPER_MAX_IMAGE_COUNT=0 ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact,deck_emu MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=0 MESA_VK_WSI_PRESENT_MODE=mailbox TU_DEBUG=${gpu.tuDebug} VKD3D_SHADER_MODEL=6_0 PULSE_LATENCY_MSEC=144$samsungEnv"
    return JSONObject().apply {
        put("id", CONTAINER_ID)
        put("name", "WoW Forever")
        put("screenSize", gpu.screenSize)
        put("envVars", envVars)
        put("graphicsDriver", "Wrapper")
        put("graphicsDriverVersion", gpu.driver)
        put("graphicsDriverConfig", "version=${gpu.driver},adrenotoolsTurnip=1,resourceType=buffer,bcnEmulation=auto,quality=high")
        put("displayRenderer", "vulkan")
        put("dxwrapper", "dxvk-$DXVK_VERSION-1")
        put("dxwrapperConfig", "version=$DXVK_VERSION-1")
        put("wineVersion", "$PROTON_VERSION-1")
        put("containerVariant", "bionic")
        put("fexcoreVersion", "2609-0")
        put("drives", "D:/storage/emulated/0/DownloadE:/data/data/${BuildConfig.APPLICATION_ID}/storageG:${gameRoot.path}")
        put("executablePath", executablePath)
        put("execArgs", "-d3d11")
        put("showFPS", true)
        put("startupSelection", Container.STARTUP_SELECTION_AGGRESSIVE.toInt())
        put("wow64Mode", true)
        // DS Forever: the launcher's overlay (Back) never SIGSTOPs the game. 2026-10-08 22:41: a Back swipe suspended the running
        // online game for 11 s ("manual" policy); 13 s after the SIGCONT a thread froze and the game died (ERROR #109, 20 s freeze)
        put("suspendPolicy", Container.SUSPEND_POLICY_NEVER)
        put("wincomponents", "direct3d=0,directsound=0,directinput8=0,directinput=0,directmusic=0,directshow=0,directplay=0,vcrun2010=0,wmdecoder=0,opengl=0")
    }
}

/** Config.wtf's defaults for [product]: its portal (the beta's "test", else the install's region) and agentUID, then the rest */
private fun configDefaults(product: WowProduct, row: Map<String, String>?): Map<String, String> =
    linkedMapOf("portal" to "\"${product.portalFor(row)}\"", "agentUID" to "\"${product.code}\"") + CONFIG_DEFAULTS

private val CONFIG_DEFAULTS = linkedMapOf(
    "gxApi" to "\"D3D11\"",
    "textLocale" to "\"enUS\"",
    "audioLocale" to "\"enUS\"",
    "gxMaximize" to "\"1\"",
    "gxWindowedResolution" to "\"1920x1080\"",
    "graphicsQuality" to "\"0\"",
    "ResampleQuality" to "\"0\"",
    "RenderScale" to "\"1\"",
    "farclip" to "\"1200\"",
    "horizonClip" to "\"1200\"",
    "RAIDfarclip" to "\"1200\"",
    "RAIDhorizonClip" to "\"1200\"",
    "shadowMode" to "\"0\"",
    "graphicsShadowQuality" to "\"0\"",
    "raidGraphicsShadowQuality" to "\"0\"",
    "worldBaseMip" to "\"0\"",
    "RAIDworldBaseMip" to "\"0\"",
    "graphicsTextureResolution" to "\"2\"",
    "raidGraphicsTextureResolution" to "\"2\"",
    "componentTextureLevel" to "\"0\"",
    "RAIDcomponentTextureLevel" to "\"0\"",
    "entityShadowFadeScale" to "\"0\"",
    "refraction" to "\"0\"",
    "groundEffectDensity" to "\"16\"",
    "GamePadEnable" to "\"1\"",
    "InputDeviceInterfaceStyle" to "\"1\"",
)

private val FORCED_CONFIG_KEYS = listOf("gxApi", "RenderScale", "ResampleQuality", "GamePadEnable", "InputDeviceInterfaceStyle")

private val LEGACY_CONFIG_VALUES = mapOf(
    "farclip" to "\"3000\"",
    "horizonClip" to "\"3000\"",
    "RAIDfarclip" to "\"3000\"",
    "RAIDhorizonClip" to "\"3000\"",
    "worldBaseMip" to "\"2\"",
    "RAIDworldBaseMip" to "\"2\"",
    "graphicsTextureResolution" to "\"0\"",
    "raidGraphicsTextureResolution" to "\"0\"",
    "componentTextureLevel" to "\"1\"",
    "RAIDcomponentTextureLevel" to "\"1\"",
)

private fun ensureGameConfig(root: File, product: WowProduct, row: Map<String, String>?) {
    val defaults = configDefaults(product, row)
    val flavorDir = File(root, product.flavorDir)
    flavorDir.mkdirs()
    val flavorInfo = File(flavorDir, ".flavor.info")
    if (!flavorInfo.exists()) {
        flavorInfo.writeText("Product Flavor!STRING:0\n${product.code}\n")
    }
    val configWtf = File(flavorDir, "WTF/Config.wtf")
    if (!configWtf.exists()) {
        configWtf.parentFile?.mkdirs()
        configWtf.writeText(defaults.entries.joinToString("\n") { "SET ${it.key} ${it.value}" } + "\n")
        return
    }

    val original = configWtf.readLines().filterNot { it.startsWith("SET ResampleSharpness", ignoreCase = true) }
    val existingKeys = mutableSetOf<String>()
    val updated = original.map { line ->
        val parts = line.takeIf { it.startsWith("SET ", ignoreCase = true) }?.removePrefix("SET ")?.trim()?.split(" ", limit = 2)
        if (parts?.size != 2) return@map line
        val (key, value) = parts
        existingKeys.add(key)
        val forcedKey = FORCED_CONFIG_KEYS.firstOrNull { it.equals(key, ignoreCase = true) }
        when {
            forcedKey != null && value != defaults.getValue(forcedKey) -> "SET $forcedKey ${defaults.getValue(forcedKey)}"
            LEGACY_CONFIG_VALUES[key] == value -> "SET $key ${defaults.getValue(key)}"
            else -> line
        }
    } + defaults.filterKeys { it !in existingKeys }.map { (k, v) -> "SET $k $v" }

    if (updated != original) {
        configWtf.writeText(updated.joinToString("\n") + "\n")
    }
}

private fun installBundledComponents(context: Context, onStatus: (String) -> Unit) {
    installDriver(context, DEFAULT_DRIVER, "turnip-wow-scheduler-test.zip", onStatus)
    installDriver(context, ADRENO_8XX_DRIVER, "turnip-V32-RP6sched.zip", onStatus)

    val protonDir = File(ImageFs.getSharedProtonDir(context), PROTON_VERSION)
    if (!File(protonDir, "profile.json").exists() || !File(protonDir, "bin/wine").exists()) {
        onStatus("Extracting Proton 11 ARM64EC (takes ~10s)...")
        check(extractBundledWcp(context, "$PROTON_VERSION.wcp", protonDir)) { "Failed to extract Proton or graphics components." }
        File(protonDir, "bin").listFiles()?.forEach {
            it.setExecutable(true, false)
            it.setReadable(true, false)
        }
    }

    val dxvkDir = File(ContentsManager.getContentTypeDir(context, ContentProfile.ContentType.CONTENT_TYPE_DXVK), DXVK_VERSION)
    if (!File(dxvkDir, "profile.json").exists()) {
        onStatus("Extracting DXVK ARM64...")
        if (!extractBundledWcp(context, "dxvk-$DXVK_VERSION.wcp", dxvkDir)) Timber.e("Failed to extract DXVK")
    }

    ImageFsInstaller.ensureBionicLib(context, ImageFs.find(context).rootDir)
    ContentsManager(context).syncContents()
}

private fun installDriver(context: Context, driverName: String, assetZip: String, onStatus: (String) -> Unit) {
    val driverDir = File(context.filesDir, "contents/adrenotools/$driverName")
    if (File(driverDir, "meta.json").exists()) return
    onStatus("Installing $driverName driver...")
    driverDir.mkdirs()
    if (!FileUtils.extractZipFromAssets(context, "bundled_components/$assetZip", driverDir)) {
        Timber.e("Error extracting driver $driverName from $assetZip")
    }
}

private fun extractBundledWcp(context: Context, assetName: String, destination: File): Boolean {
    destination.mkdirs()
    return TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "bundled_components/$assetName", destination)
}
