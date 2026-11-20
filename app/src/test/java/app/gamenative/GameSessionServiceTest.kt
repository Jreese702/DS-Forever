package app.gamenative

import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The game-running foreground service (upstream WoW Forever for Android v2.4.4 a3147360 + WoW Foreverywhere 3ed7cc9c, ported
 * 2026-11-20): a quiet low-importance notification, started once the local game's environment is up, stopped by every exit
 * (PluviaApp.shutdownEnvironment), never by Streaming; the manifest asks for the minimum (FOREGROUND_SERVICE + its special-use type,
 * no notification permission).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameSessionServiceTest {
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun ensureStartedAsksForTheForegroundServiceOnceAndStopEndsIt() {
        GameSessionService.stop(app)
        shadowOf(app).clearStartedServices()
        GameSessionService.ensureStarted(app)
        val started = shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(GameSessionService::class.java.name, started.component?.className)
        GameSessionService.ensureStarted(app)
        assertNull("already running: not asked again", shadowOf(app).nextStartedService)
        GameSessionService.stop(app)
        assertEquals(GameSessionService::class.java.name, shadowOf(app).nextStoppedService?.component?.className)
        GameSessionService.stop(app)
        assertNull("stopped once", shadowOf(app).nextStoppedService)
    }

    @Test fun goesToTheForegroundWithAQuietNotification() {
        val controller = Robolectric.buildService(GameSessionService::class.java, Intent()).create().startCommand(0, 1)
        val shadow = shadowOf(controller.get())
        assertFalse(shadow.isForegroundStopped)
        val n = shadow.lastForegroundNotification
        assertNotNull(n)
        assertEquals("DS Forever: game running", n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
        assertEquals("Tap to return to the game", n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString())
        assertTrue("ongoing", n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel("game_session")
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull("silent", channel.sound)
        assertFalse(channel.shouldVibrate())
        controller.destroy()
    }

    private fun src(path: String) = File(path).readText()

    @Test fun manifestAsksForTheMinimum() {
        val m = src("src/main/AndroidManifest.xml")
        assertTrue(m.contains("""<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />"""))
        assertTrue(m.contains("""<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />"""))
        assertEquals("one FOREGROUND_SERVICE line (the fork's fix)", 1,
            Regex("""android\.permission\.FOREGROUND_SERVICE"""").findAll(m).count())
        assertFalse("no notification permission asked for", m.contains("""<uses-permission android:name="android.permission.POST_NOTIFICATIONS""""))
        val service = Regex("""<service\s+android:name="\.GameSessionService"[^>]*>""").find(m)?.value
        assertNotNull(service)
        assertTrue(service!!.contains("""android:exported="false""""))
        assertTrue(service.contains("""android:foregroundServiceType="specialUse""""))
        assertTrue(m.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
    }

    @Test fun startedWithTheLocalGameAndStoppedByEveryExit() {
        val shutdown = src("src/main/java/app/gamenative/PluviaApp.kt").substringAfter("fun shutdownEnvironment()").substringBefore("fun clearActiveSuspendState")
        assertTrue("shutdownEnvironment stops it", shutdown.contains("GameSessionService.stop("))
        val xs = src("src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt")
        val setup = xs.indexOf("PluviaApp.xEnvironment = setupXEnvironment(")
        val start = xs.indexOf("GameSessionService.ensureStarted(")
        assertTrue("started after the environment is set up", setup in 0 until start)
        assertEquals("one start in XServerScreen", 1, Regex("""GameSessionService\.ensureStarted\(""").findAll(xs).count())
        val exit = xs.substringAfter("private fun exit(").substringBefore("\n}\n")
        assertTrue("XServerScreen's exit runs shutdownEnvironment", exit.contains("PluviaApp.shutdownEnvironment()"))
        val resume = src("src/main/java/app/gamenative/MainActivity.kt").substringAfter("override fun onResume()")
        assertTrue("the resume retry comes before the early return of the move to the top screen",
            resume.indexOf("GameSessionService.ensureStarted(") in 0 until resume.indexOf("DsForeverScreen.moveToTop("))
    }

    @Test fun streamingNeverStartsIt() {
        val hits = File("../dsforever/src/main/java").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { it.readText().contains("GameSessionService") }.map { it.path }.toList()
        assertEquals("Streaming (and the rest of the fork module) never touches the game-running service", emptyList<String>(), hits)
    }

    @Test fun notificationWordsAreTranslated() {
        for (lang in listOf("es", "fr")) {
            val plain = JSONObject(File("../companion/app/src/main/assets/companion/i18n/$lang.json").readText()).getJSONObject("plain")
            for (k in listOf(GameSessionService.TITLE, GameSessionService.TEXT, GameSessionService.CHANNEL_NAME, GameSessionService.CHANNEL_DESCRIPTION)) {
                assertTrue("$lang has \"$k\"", plain.has(k))
            }
        }
    }
}
