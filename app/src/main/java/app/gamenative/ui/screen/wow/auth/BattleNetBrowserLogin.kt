package app.gamenative.ui.screen.wow.auth

import android.content.Context
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import com.jrees.wowcompanion.BrowserLogin
import com.jrees.wowcompanion.GameAccountHandover
import com.jrees.wowcompanion.GameRegion
import com.jrees.wowcompanion.NativeLogin
import com.jrees.wowcompanion.Settings
import com.jrees.wowcompanion.WowProduct
import com.winlator.container.Container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Opt-in browser authentication. Tokens never enter game files, arguments, preferences, or logs.
 *
 * Ported from WoW Forever for Android (jaredgei/wow-forever-android v2.4.5, GPL-3.0): Demineo04's experimental Battle.net browser
 * session login (8e7585fb, PR #87), its device fixes (4f683281, PR #95, marcocastignoli), the Wine exit status (PR #93) and the unquoted
 * launch arguments (f7875f91). The Battle.net protocol follows nyrda/scuffle (MIT, 5efccb44; LICENSE.scuffle).
 *
 * DS FOREVER (the user, 2026-11-18): OPT-IN, Settings > General > Account > Browser login (default Off: the saved login's private
 * login.txt, as before), and offered after a BLZ51914400 refusal. Adapted here:
 *  - the choice is DS Forever's own [Settings.browserLogin], LATCHED once per game start by [beginRun] (Play), so the shadow folder
 *    (NativeLogin.prepare: no login.txt), the launch command (XServerScreen) and the watchers all follow the same answer;
 *  - the game drive G: is NativeLogin's private shadow folder; any login.txt there is deleted (the helper refuses one);
 *  - the region host is the portal's (Taiwan signs in through kr: GameRegion.portal), the product and build are the folder's own
 *    (WowProduct; World of Warcraft: Forever only);
 *  - the page shows on the Thor's BOTTOM screen (BattleNetBrowserLoginHost -> DsForeverScreen.browserPage), the top stays the game;
 *  - the sign-in watchers stand down while the page is up (NativeLogin.browserHold) and follow the client once the helper started it.
 */
internal object BattleNetBrowserLogin {
    const val ASSET = "wow-session-bridge.exe"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableUi = MutableStateFlow<Ui>(Ui.Idle)
    val ui = mutableUi.asStateFlow()
    private var active: Attempt? = null

    sealed interface Ui {
        data object Idle : Ui
        class Waiting(val text: String) : Ui
        // Deliberately no generated toString: a challenge URL is sensitive.
        class Browser(val url: String) : Ui
        class Failed(val text: String) : Ui
    }

    /** The callback belongs to this launch even if sign-in is cancelled or another attempt starts. */
    class Launch(val command: String, val onExit: (Int) -> Unit)

    /** the player's choice (Settings > General > Account > Browser login); default Off */
    fun enabled(context: Context): Boolean = runCatching { Settings.init(context); Settings.browserLogin }.getOrDefault(false)

    /** this game start's way to sign in, latched at Play ([beginRun]); false until then */
    @Volatile var thisRun = false
        private set

    /**
     * Play: this start's way to sign in - the choice, but never for a direct install's one seeding run (it only builds the game's
     * indexes and is closed by us; there is nobody to sign in). Said to [BrowserLogin] for the Settings row's restart. Any thread.
     */
    fun beginRun(context: Context, seeding: Boolean): Boolean {
        val browser = enabled(context) && !seeding
        thisRun = browser
        runCatching { BrowserLogin.markLaunched(browser) }
        GameAccountHandover.beginRun(browser)
        return browser
    }

    /** Runs after Wine prefix initialization. Container executable remains the game for window tracking. */
    @Synchronized
    fun start(context: Context, container: Container): Launch {
        cancel()
        val gameRoot = Container.drivesIterator(container.drives).asSequence()
            .firstOrNull { it[0].equals("G", ignoreCase = true) }?.let { File(it[1]) }
            ?: throw IOException("Cannot locate the game drive for browser login")
        val choice = WowProduct.choose(gameRoot) ?: throw IOException("Cannot read the installed game build for browser login")
        val product = choice.product
        check(product == WowProduct.LAUNCH) { "Browser login is for World of Warcraft: Forever only" }
        val build = choice.row["Version"]?.substringAfterLast('.')?.toIntOrNull()?.takeIf { it > 0 }
            ?: throw IOException("Cannot read the installed game build for browser login")
        // Taiwan has no tw.actual.battle.net: it signs in through kr (GameRegion.portal)
        val portal = GameRegion.portal(GameRegion.current(context, gameRoot))
        check(portal.matches(Regex("^[a-z]{2}$"))) { "Unsupported region for browser login" }
        // Even an empty login.txt makes the client take the password path instead of the launcher session (the helper refuses it).
        runCatching { NativeLogin.clear(context) }
        val loginFile = File(gameRoot, "${product.flavorDir}/login.txt")
        if (loginFile.exists() && !Files.isSymbolicLink(loginFile.toPath())) loginFile.delete()
        check(!loginFile.exists()) { "Remove the old login.txt before using browser login" }

        val directory = File(container.rootDir, ".wine/drive_c/wow-session")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare browser login" }
        // The prefix is in Android's app-private directory, never shared game storage.
        val helper = File(directory, ASSET)
        context.assets.open(ASSET).use { input -> helper.outputStream().use { input.copyTo(it) } }
        val bridge = SessionBridge()
        val request = File(directory, "request-${UUID.randomUUID()}.txt")
        try {
            request.writeText(bridge.config, Charsets.US_ASCII)
            request.setReadable(false, false)
            request.setWritable(false, false)
            check(request.setReadable(true, true) && request.setWritable(true, true)) {
                "Cannot protect the browser login request"
            }
            val log = File(gameRoot, "${product.flavorDir}/Logs/BrowserLogin.log")
            val attempt = Attempt(bridge, request, "$portal.actual.battle.net", build, log, File(gameRoot, "${product.flavorDir}/WTF/Account"))
            diag(attempt, "browser login started (app ${app.gamenative.BuildConfig.VERSION_NAME}, $portal, build $build)")
            active = attempt
            mutableUi.value = Ui.Waiting(WAIT_START)
            // One deadline covers Wine startup, queues and browser interaction, below the helper's 15 minutes.
            attempt.watchdog = scope.launch {
                delay(10 * 60_000L)
                fail(attempt, TIMED_OUT)
            }
            attempt.job = scope.launch { run(attempt) }
            // These arguments contain paths and public switches only. The token travels over the authenticated socket.
            // No quotes: ProcessHelper.splitCommand keeps quote characters, so Wine would get them as part of each
            // path and couldn't find the helper. None of these paths contain spaces.
            val gamePath = container.executablePath
            check(' ' !in gamePath && '"' !in gamePath) { "The game path can't contain spaces for browser login" }
            val command = launchCommand(request.name, gamePath, product.code)
            return Launch(command) { status -> guestExited(attempt, status) }
        } catch (error: Exception) {
            bridge.close()
            request.delete()
            throw error
        }
    }

    /** the Wine command: the helper, its private request, then the game and its launcher-session switches (no quotes, no secrets) */
    fun launchCommand(requestName: String, gamePath: String, productCode: String): String =
        "C:\\wow-session\\$ASSET C:\\wow-session\\$requestName $gamePath -launcherlogin -uid $productCode -d3d11"

    private suspend fun run(attempt: Attempt) {
        try {
            diag(attempt, "waiting for the helper to connect")
            attempt.bridge.accept().use { peer ->
                diag(attempt, "helper connected")
                val device = peer.device().also { attempt.device.set(it) }
                validateDevice(device)
                val url = withClient(attempt) { client ->
                    client.connect()
                    client.webChallenge(device, attempt.build)
                }
                diag(attempt, "showing the Battle.net sign-in page")
                publish(attempt, Ui.Browser(url))
                val ticket = withTimeout(15 * 60_000L) { attempt.ticket.await() }
                diag(attempt, "sign-in page finished")
                try {
                    publish(attempt, Ui.Waiting(WAIT_SESSION))
                    // A fresh connection avoids holding a BGS socket open while the user handles CAPTCHA or 2FA.
                    var account = SessionBridge.Peer.NO_ACCOUNT
                    val token = withClient(attempt) { client ->
                        client.connect()
                        client.webChallenge(device, attempt.build)
                        client.verifyAndGenerateGameToken(ticket).also {
                            // DS Forever (the user's ruling 2026-11-23): after the credential, so it never costs the sign-in
                            account = handover(attempt, client.gameAccounts())
                        }
                    }
                    try {
                        diag(attempt, "Battle.net issued a game session; handing it to the helper")
                        publish(attempt, Ui.Waiting(WAIT_GAME))
                        peer.launch(token, account)
                    } finally {
                        token.fill(0)
                    }
                } finally {
                    ticket.fill(0)
                    device.fill(0)
                }
                diag(attempt, "the game started with the Battle.net session")
                runCatching { NativeLogin.browserGameStarted() }
                publish(attempt, Ui.Idle)
            }
        } catch (_: TimeoutCancellationException) {
            fail(attempt, TIMED_OUT)
        } catch (_: CancellationException) {
            // User cancellation does not turn into a password-login attempt.
        } catch (error: BgsException) {
            diag(attempt, "Battle.net error: ${error.message}")
            fail(attempt, error.message ?: "Battle.net sign-in failed.")
        } catch (error: Exception) {
            // Closing the listener during exit/cancellation can interrupt accept with SocketException.
            if (attempt.cancelled) return
            // The exception type only: messages can carry provider payloads or addresses.
            diag(attempt, "stopped: ${error.javaClass.simpleName}")
            // Never expose provider payloads, redirect URLs, credentials or arbitrary peer output.
            fail(attempt, COULD_NOT_START)
        } finally {
            attempt.close()
            synchronized(this) { if (active === attempt) active = null }
        }
    }

    /**
     * BROWSER LOGIN HANDS THE GAME ACCOUNT OVER (the user's ruling 2026-11-23; GameAccountHandover): this login's game accounts
     * ([accounts], null = unread) against the remembered pick; the helper's frame ("-" = none, the game asks). Any error = none.
     * The log says the decision with a length and a region, never a name.
     */
    private fun handover(attempt: Attempt, accounts: List<BgsClient.GameAccount>?): ByteArray {
        val choice = runCatching {
            GameAccountHandover.decide(attempt.accountDir, accounts?.map { GameAccountHandover.Account(it.name, it.region) })
        }.getOrElse { GameAccountHandover.Choice.None("an error (${it.javaClass.simpleName})") }
        diag(attempt, "game account: ${accounts?.size?.let { "$it listed" } ?: "list unread"}; ${GameAccountHandover.words(choice)}")
        return GameAccountHandover.frame(choice)
    }

    private fun <T> withClient(attempt: Attempt, action: (BgsClient) -> T): T {
        // The ARM64 helper reports the real Windows-on-ARM context, the same platform as the ARM64 game client.
        val client = BgsClient(attempt.host, platform = "WinA")
        attempt.client.set(client)
        try {
            if (attempt.cancelled) throw CancellationException()
            return action(client)
        } finally {
            attempt.client.compareAndSet(client, null)
            client.close()
        }
    }

    private fun validateDevice(device: ByteArray) {
        val expected = setOf("RGKY", "CPGE", "ULNG", "SLNG", "CNME", "UNME", "UTCO", "CARC", "CREV", "CLVL",
            "PMEM", "PSZE", "OVER", "CVRA", "CFTC", "CFTD", "CEFC", "CEFD", "CBRD", "CVEN", "ANME", "ADSC", "MAC")
        val json = JSONObject(device.toString(Charsets.UTF_8))
        check(json.keys().asSequence().toSet() == expected) { "Invalid device description" }
        expected.forEach { check(json.getLong(it) in 0..0xffff_ffffL) { "Invalid device description" } }
    }

    /**
     * Appends a step to the game folder's Logs/BrowserLogin.log (the helper writes its steps there too) and DS Forever's sign-in log.
     * Never pass credentials, account names, tickets, tokens or URLs.
     */
    private fun diag(attempt: Attempt, message: String) {
        runCatching {
            attempt.log.parentFile?.mkdirs()
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
            attempt.log.appendText("$time app: [${attempt.id}] $message\n")
        }
        runCatching { NativeLogin.logEvent("browser login: $message") }
    }

    @Synchronized
    private fun publish(attempt: Attempt, state: Ui) {
        if (active === attempt && !attempt.cancelled) mutableUi.value = state
    }

    @Synchronized
    fun submit(browser: Ui.Browser, ticket: ByteArray) {
        val attempt = active
        if (mutableUi.value !== browser || attempt == null || attempt.ticket.isCompleted) {
            ticket.fill(0)
        } else {
            attempt.ticketBytes.set(ticket)
            attempt.ticket.complete(ticket)
        }
    }

    @Synchronized
    fun cancel(preserveFailure: Boolean = false) {
        active?.let { attempt ->
            attempt.cancelled = true
            attempt.close()
            attempt.job?.cancel()
            runCatching { NativeLogin.browserStopped("cancelled") }
        }
        active = null
        if (!preserveFailure || mutableUi.value !is Ui.Failed) mutableUi.value = Ui.Idle
    }

    @Synchronized
    fun abort(state: Ui) {
        if (mutableUi.value !== state) return
        val running = state !is Ui.Failed
        cancel()
        // Closing the control socket alone cannot stop a helper that has already received the token.
        if (running) closeGame("browser login cancelled")
    }

    @Synchronized
    fun webError(browser: Ui.Browser, message: String) {
        if (mutableUi.value === browser) active?.let { fail(it, message) }
    }

    @Synchronized
    private fun fail(attempt: Attempt, message: String) {
        if (active !== attempt || attempt.cancelled || mutableUi.value is Ui.Idle) return
        val failure = Ui.Failed(message)
        mutableUi.value = failure
        cancel(preserveFailure = true)
        scope.launch(Dispatchers.Main.immediate) {
            synchronized(this@BattleNetBrowserLogin) {
                // A queued failure must not tear down a newer attempt or a game the user has since launched.
                if (mutableUi.value === failure) closeGame("browser login failed")
            }
        }
    }

    /** the game screen closes (issue triage 2026-11-11: GameLife says who closed it) */
    private fun closeGame(why: String) {
        runCatching { com.jrees.wowcompanion.GameLife.closer = why }
        PluviaApp.events.emit(AndroidEvent.ForceCloseApp)
    }

    @Synchronized
    private fun guestExited(attempt: Attempt, status: Int) {
        if (attempt.exitRecorded) return
        attempt.exitRecorded = true
        // This is the outer Wine launch status, not necessarily the Windows helper's exit code.
        // ProcessHelper also reports -1 when launching the process fails. Preserve it verbatim.
        diag(attempt, "Wine launch exit status: $status")
        if (active !== attempt || attempt.cancelled) return
        if (mutableUi.value !is Ui.Idle && mutableUi.value !is Ui.Failed) {
            mutableUi.value = Ui.Failed("$WINE_STOPPED (status $status)")
        }
        cancel(preserveFailure = true)
    }

    // the page's words (terse; es/fr in the app's word lists)
    const val TITLE = "Browser login"
    const val WAIT_START = "Starting browser login…"
    const val WAIT_SESSION = "Completing sign-in…"
    const val WAIT_GAME = "Starting the game…"
    const val TIMED_OUT = "Browser login timed out."
    const val COULD_NOT_START = "Browser login could not start the game."
    const val WINE_STOPPED = "The game stopped before sign-in"

    private class Attempt(
        val bridge: SessionBridge,
        val request: File,
        val host: String,
        val build: Int,
        val log: File,
        val accountDir: File,
    ) {
        // The request filename's random ID is independent of the private socket capability.
        val id = request.name.removePrefix("request-").removeSuffix(".txt")
        var exitRecorded = false // Accessed only by the synchronized exit callback.
        val ticket = CompletableDeferred<ByteArray>()
        val client = AtomicReference<BgsClient?>()
        val ticketBytes = AtomicReference<ByteArray?>()
        val device = AtomicReference<ByteArray?>()
        @Volatile var cancelled = false
        var job: Job? = null
        var watchdog: Job? = null
        fun close() {
            runCatching { client.getAndSet(null)?.close() }
            runCatching { bridge.close() }
            watchdog?.cancel()
            ticketBytes.getAndSet(null)?.fill(0)
            device.getAndSet(null)?.fill(0)
            request.delete()
        }
    }
}
