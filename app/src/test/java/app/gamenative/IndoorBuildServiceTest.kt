package app.gamenative

import android.app.Notification
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
 * Issue 568 (the user's ruling 2026-11-23): the indoor maps' quiet foreground service. Started and stopped by the companion's
 * IndoorKeepAlive through [IndoorBuildService.Control]; a silent low-importance notification "Building indoor maps" with its progress;
 * no new permission in the manifest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IndoorBuildServiceTest {
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun controlStartsTheServiceAndStopsIt() {
        shadowOf(app).clearStartedServices()
        assertTrue(IndoorBuildService.Control.start(app, 120, 1853))
        val started = shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(IndoorBuildService::class.java.name, started.component?.className)
        IndoorBuildService.inForeground = true
        IndoorBuildService.Control.stop(app)
        assertEquals(IndoorBuildService::class.java.name, shadowOf(app).nextStoppedService?.component?.className)
        IndoorBuildService.inForeground = false
    }

    @Test fun aStopBeforeTheForegroundWaitsForIt() {
        // Android crashes an app whose startForegroundService ends before its startForeground: the stop waits for onStartCommand
        IndoorBuildService.inForeground = false
        IndoorBuildService.Control.start(app, 0, 0)
        shadowOf(app).clearStartedServices()
        IndoorBuildService.Control.stop(app)
        assertNull("not stopped yet", shadowOf(app).nextStoppedService)
        val controller = Robolectric.buildService(IndoorBuildService::class.java, Intent()).create().startCommand(0, 1)
        val shadow = shadowOf(controller.get())
        assertNotNull("it went to the foreground first", shadow.lastForegroundNotification)
        assertTrue("then stopped itself", shadow.isStoppedBySelf)
        controller.destroy()
        assertFalse(IndoorBuildService.inForeground)
    }

    @Test fun anOldInstanceEndingAfterANewStartLeavesTheNewOneCounted() {
        // review: stop, then a quick new start; Android ends the old instance only after the new start was asked for. Its end must
        // not count the service as off (the keep-alive would never stop the new one: on beside a game), and a stop before the new
        // instance's startForeground still waits for it
        val lost = ArrayList<String>()
        IndoorBuildService.onLost = { lost += it }
        try {
            IndoorBuildService.stopWanted = false
            IndoorBuildService.Control.start(app, 0, 0)
            val old = Robolectric.buildService(IndoorBuildService::class.java, Intent().putExtra("gen", IndoorBuildService.gen))
                .create().startCommand(0, 1)
            assertTrue(IndoorBuildService.inForeground)
            IndoorBuildService.Control.stop(app)
            IndoorBuildService.Control.start(app, 1, 10)
            assertFalse("the new start is not in the foreground yet", IndoorBuildService.inForeground)
            old.destroy()
            assertEquals("the old instance's end says nothing", emptyList<String>(), lost)
            IndoorBuildService.Control.stop(app)
            assertTrue("waits for the new instance", IndoorBuildService.stopWanted)
            val fresh = Robolectric.buildService(IndoorBuildService::class.java, Intent().putExtra("gen", IndoorBuildService.gen))
                .create().startCommand(0, 2)
            assertNotNull(shadowOf(fresh.get()).lastForegroundNotification)
            assertTrue(shadowOf(fresh.get()).isStoppedBySelf)
            fresh.destroy()
            assertEquals("the newest instance's end is told", listOf("the service ended"), lost)
            assertFalse(IndoorBuildService.inForeground)
        } finally {
            IndoorBuildService.onLost = { com.jrees.wowcompanion.IndoorKeepAlive.lost(it) }
            IndoorBuildService.inForeground = false
            IndoorBuildService.stopWanted = false
        }
    }

    @Test fun progressOnlyRefreshesTheForegroundNotification() {
        // review: a notify before (or without) startForeground would leave an ongoing notification no service end removes
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.cancelAll()
        IndoorBuildService.inForeground = false
        IndoorBuildService.Control.progress(app, 5, 10)
        assertNull(shadowOf(nm).getNotification(IndoorBuildService.NOTIFICATION_ID))
        IndoorBuildService.inForeground = true
        try {
            IndoorBuildService.Control.progress(app, 5, 10)
            val n = shadowOf(nm).getNotification(IndoorBuildService.NOTIFICATION_ID)
            assertNotNull(n)
            assertEquals("5/10 ready", n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        } finally { IndoorBuildService.inForeground = false; nm.cancelAll() }
    }

    @Test fun goesToTheForegroundWithAQuietProgressNotification() {
        IndoorBuildService.stopWanted = false
        val intent = Intent().putExtra("ready", 120).putExtra("total", 1853)
        val controller = Robolectric.buildService(IndoorBuildService::class.java, intent).create().startCommand(0, 1)
        val shadow = shadowOf(controller.get())
        assertFalse(shadow.isForegroundStopped)
        assertFalse(shadow.isStoppedBySelf)
        val n = shadow.lastForegroundNotification
        assertNotNull(n)
        assertEquals("Building indoor maps", n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("120/1853 ready", n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(1853, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(120, n.extras.getInt(Notification.EXTRA_PROGRESS))
        assertTrue("ongoing", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(IndoorBuildService.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull("silent", channel.sound)
        assertFalse(channel.shouldVibrate())
        assertTrue("its own channel, not the game-running one", IndoorBuildService.CHANNEL_ID != "game_session")
        controller.destroy()
    }

    @Test fun noCountYetNoProgressBar() {
        val n = IndoorBuildService.notification(app, 0, 0)
        assertNull(n.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(0, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
    }

    private fun src(path: String) = File(path).readText()

    @Test fun manifestDeclaresItWithNoNewPermission() {
        val m = src("src/main/AndroidManifest.xml")
        val service = Regex("""<service\s+android:name="\.IndoorBuildService"[^>]*>""").find(m)?.value
        assertNotNull(service)
        assertTrue(service!!.contains("""android:exported="false""""))
        assertTrue(service.contains("""android:foregroundServiceType="specialUse""""))
        val block = m.substringAfter("""android:name=".IndoorBuildService"""").substringBefore("</service>")
        assertTrue("its special-use reason", block.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
        // the permissions the game-running service already needed, and nothing more
        val perms = Regex("""<uses-permission android:name="android\.permission\.(FOREGROUND_SERVICE[A-Z_]*|POST_NOTIFICATIONS)"""").findAll(m)
            .map { it.groupValues[1] }.toList()
        assertEquals(listOf("FOREGROUND_SERVICE", "FOREGROUND_SERVICE_SPECIAL_USE"), perms)
    }

    @Test fun notificationWordsAreTranslated() {
        for (lang in listOf("es", "fr")) {
            val o = JSONObject(File("../companion/app/src/main/assets/companion/i18n/$lang.json").readText())
            for (k in listOf(IndoorBuildService.TITLE, IndoorBuildService.CHANNEL_NAME, IndoorBuildService.CHANNEL_DESCRIPTION)) {
                assertTrue("$lang has \"$k\"", o.getJSONObject("plain").has(k))
            }
            assertTrue("$lang has the progress template", o.getJSONObject("templates").has(IndoorBuildService.PROGRESS))
        }
    }
}
