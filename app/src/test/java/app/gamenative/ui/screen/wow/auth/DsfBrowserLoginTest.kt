package app.gamenative.ui.screen.wow.auth

import android.app.Application
import android.view.View
import android.widget.TextView
import com.jrees.wowcompanion.BrowserLogin
import com.jrees.wowcompanion.Settings
import com.jrees.wowcompanion.fork.BottomPageSlot
import com.jrees.wowcompanion.fork.DsForeverScreen
import com.winlator.container.Container
import com.winlator.core.ProcessHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * DS FOREVER'S BROWSER LOGIN (the user, 2026-11-18: upstream v2.4.5's, OPT-IN, its page on the Thor's bottom screen), off-device:
 * the choice latched at Play (never for the seeding run), the shadow's login.txt gone before the helper starts, the launch command's
 * exact arguments (the region's portal, the folder's product), the page on the bottom window (focusable while it shows, the focus
 * back when it goes) and the top-screen fallback without one, and the helper asset built from source (its recorded SHA-256).
 * Never a Wine helper, a Battle.net connection or a credential.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DsfBrowserLoginTest {
    @get:Rule
    // DS Forever: the drives string drops the temp path's drive letter, so the folder must sit on the working directory's drive
    // (the system temp folder is on C:, so a worktree on E: read it as E:\Users\..., missing); the module's build folder always is.
    val temporaryFolder = TemporaryFolder(File("build/tmp/auth-tests").absoluteFile.apply { mkdirs() })
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun reset() { BattleNetBrowserLogin.cancel(); Settings.init(app); Settings.browserLogin = false; BrowserLogin.forgetLaunchForTest() }
    @After fun done() { BattleNetBrowserLogin.cancel(); Settings.browserLogin = false; BrowserLogin.forgetLaunchForTest() }

    // ---- opt-in, latched at Play

    @Test fun offByDefaultAndLatchedPerStart() {
        assertFalse("the default is the saved login", BattleNetBrowserLogin.enabled(app))
        assertFalse(BattleNetBrowserLogin.beginRun(app, seeding = false))
        assertFalse(BattleNetBrowserLogin.thisRun)
        assertEquals(false, BrowserLogin.launched)
        Settings.browserLogin = true
        assertTrue(BattleNetBrowserLogin.beginRun(app, seeding = false))
        assertTrue(BattleNetBrowserLogin.thisRun)
        assertEquals(true, BrowserLogin.launched)
        // the latch holds for this start: turning it off mid-start changes the next start only
        Settings.browserLogin = false
        assertTrue(BattleNetBrowserLogin.thisRun)
    }

    @Test fun neverForTheSeedingRun() {
        Settings.browserLogin = true
        assertFalse("a direct install's one run builds indexes; nobody signs in", BattleNetBrowserLogin.beginRun(app, seeding = true))
        assertFalse(BattleNetBrowserLogin.thisRun)
    }

    // ---- login.txt never reaches the helper

    @Test fun startDeletesALoginTxtInTheGameDrive() {
        val container = container("stray")
        val login = File(container.gameRoot(), "_forever_/login.txt").apply { writeText("x\ny\n") }
        BattleNetBrowserLogin.start(app, container)
        assertFalse("the helper refuses a login.txt beside the game; none may stay", login.exists())
    }

    @Test fun theBetaIsRefused() {
        val container = container("beta", product = "wow_classic_beta", flavor = "_classic_beta_")
        val e = runCatching { BattleNetBrowserLogin.start(app, container) }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
        assertSame(BattleNetBrowserLogin.Ui.Idle, BattleNetBrowserLogin.ui.value)
    }

    // ---- the launch command

    @Test fun taiwanSignsInThroughKoreaAndTheCommandIsExact() {
        val container = container("tw", branch = "tw")
        val launch = BattleNetBrowserLogin.start(app, container)
        val request = File(container.rootDir, ".wine/drive_c/wow-session").listFiles()!!.single { it.name.startsWith("request-") }
        assertEquals(
            listOf("C:\\wow-session\\wow-session-bridge.exe", "C:\\wow-session\\${request.name}", "G:\\_forever_\\Wow-arm64.exe",
                "-launcherlogin", "-uid", "wowf", "-d3d11"),
            ProcessHelper.splitCommand("wine explorer /desktop=shell,1920x1080 " + launch.command).drop(3).toList(),
        )
        val log = File(container.gameRoot(), "_forever_/Logs/BrowserLogin.log").readText()
        assertTrue("there is no tw.actual.battle.net: the kr portal", log.contains(", kr, build 70938)"))
        // the request holds a port and a nonce only, never anything of an account
        val lines = request.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].toInt() in 1..65535)
        assertTrue(lines[1].matches(Regex("^[0-9a-f]{64}$")))
        assertFalse("no secret in the command", launch.command.contains(lines[1]))
        // the helper is copied into the private prefix, the same bytes as the APK's asset
        assertEquals(sha(app.assets.open(BattleNetBrowserLogin.ASSET).readBytes()),
            sha(File(container.rootDir, ".wine/drive_c/wow-session/wow-session-bridge.exe").readBytes()))
    }

    @Test fun aGamePathWithASpaceIsRefused() {
        val container = container("space").apply { executablePath = "G:\\_forever_\\My Wow.exe" }
        assertTrue(runCatching { BattleNetBrowserLogin.start(app, container) }.exceptionOrNull() is IllegalStateException)
    }

    // ---- the helper built from source

    @Test fun theBundledHelperIsTheOneBuiltFromSource() {
        val recorded = listOf("../tools/wow-session-bridge/wow-session-bridge.sha256", "tools/wow-session-bridge/wow-session-bridge.sha256")
            .map { File(it) }.first { it.isFile }.readText().trim().substringBefore(' ')
        assertEquals("the APK's helper must be the from-source build recorded in tools/wow-session-bridge (build_dsf.sh)",
            recorded, sha(app.assets.open(BattleNetBrowserLogin.ASSET).readBytes()))
    }

    // ---- the page on the bottom screen

    @Test fun thePageHoldsTheBottomWindowsFocusWhileItShows() {
        val added = ArrayList<View>()
        var focusable = false
        val slot = BottomPageSlot({ added += it }, { focusable }, { focusable = it })
        slot.show(app) { ctx -> TextView(ctx) }
        assertTrue(slot.up)
        assertEquals(1, added.size)
        assertTrue("the page's fields need the keyboard: the bottom window takes the focus", focusable)
        // a new page (a new state) replaces the old one; the focus stays
        slot.show(app) { ctx -> TextView(ctx) }
        assertEquals(2, added.size)
        assertTrue(focusable)
        slot.show(app, null)
        assertFalse(slot.up)
        assertNull(slot.view)
        assertFalse("the page gone: the focus goes back to the game", focusable)
    }

    @Test fun noPageNoFocusChange() {
        var calls = 0
        val slot = BottomPageSlot({}, { false }, { calls++ })
        slot.show(app, null)
        assertEquals(0, calls)
    }

    @Test fun withoutABottomScreenThePageStaysOnThisScreen() {
        // no DsForeverScreen (or none with a bottom window): the fallback dialog shows it here; Idle needs no screen at all
        val idle = BottomPages.show(BattleNetBrowserLogin.Ui.Idle)
        assertTrue(idle)
        if (DsForeverScreen.current == null) assertFalse(BottomPages.show(BattleNetBrowserLogin.Ui.Waiting("w")))
        assertFalse("a DS Forever screen without a bottom window takes no page", DsForeverScreen(app).browserPage { TextView(it) })
        assertFalse(DsForeverScreen(app).bottomUp)
    }

    @Test fun theStatusPageAndTheSignInPageAreBuilt() {
        assertTrue(BottomPages.page(app, BattleNetBrowserLogin.Ui.Failed("f")) is BattleNetStatusPage)
        assertTrue(BottomPages.page(app, BattleNetBrowserLogin.Ui.Waiting("w")) is BattleNetStatusPage)
        // an address outside battle.net never loads: the page reports it instead of showing a WebView
        var error: String? = null
        val page = BattleNetSignInPage(app, "https://example.com/login", {}, {}, { error = it })
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals("Battle.net returned an unsupported sign-in address.", error)
        page.destroy()
    }

    // ---- helpers

    private fun Container.gameRoot(): File = File(Container.drivesIterator(drives).first { it[0] == "G" }[1])

    private fun container(name: String, branch: String = "eu", product: String = "wowf", flavor: String = "_forever_"): Container {
        val root = temporaryFolder.newFolder(name)
        val game = File(root, "game").apply { mkdirs() }
        File(game, ".build.info").writeText(
            "Branch!STRING:0|Active!DEC:1|Version!STRING:0|Product!STRING:0\n$branch|1|1.60.1.70938|$product\n",
        )
        File(game, flavor).mkdirs()
        return Container(name).apply {
            rootDir = File(root, "prefix").apply { mkdirs() }
            // on a Windows test machine the folder's own "C:" would split the drives string: the same folder without it
            drives = "G:" + if (File.separatorChar == '\\') game.absolutePath.substringAfter(':').replace('\\', '/') else game.absolutePath
            executablePath = "G:\\$flavor\\Wow-arm64.exe"
        }
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
