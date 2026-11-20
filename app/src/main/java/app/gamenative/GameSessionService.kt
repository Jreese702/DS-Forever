package app.gamenative

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.jrees.wowcompanion.Tr
import timber.log.Timber

/**
 * Keeps DS Forever in the foreground state while the game runs locally, so Android is less likely to freeze or kill it when another
 * app comes to the front (an authenticator, a browser; part of #56's low-memory kills). Without it the backgrounded app is "cached":
 * the process is frozen within seconds (the connection to Blizzard's servers drops) and is an early pick for the low-memory killer.
 *
 * Started once the game's environment is set up (XServerScreen, after setupXEnvironment), retried on MainActivity.onResume when
 * Android refused the start (Android 12+ refuses it from the background), and stopped by PluviaApp.shutdownEnvironment, which every
 * game exit runs: the game exiting by itself, the pause menu's power button (its clean close, then its hard fallback), the safe
 * close before an install, the window closing. Streaming never sets up a local environment, so it never starts this service.
 *
 * The notification is silent and low importance ("DS Forever: game running", menu words through [Tr]). DS Forever asks for no
 * notification permission: on Android 13+ without it the notification stays out of the shade (Android's running-apps list still
 * shows it) and the service works the same.
 *
 * From WoW Forever for Android v2.4.4 (jaredgei/wow-forever-android commit a3147360) with WoW Foreverywhere's fix
 * (YoruDev-Ryland/WoWForeverywhere commit 3ed7cc9c: one FOREGROUND_SERVICE permission, translatable words), GPL-3.0.
 */
class GameSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Timber.i("Game session service: in the foreground")
        } catch (e: Exception) {
            Timber.w(e, "Game session service: couldn't go to the foreground")
            running = false
            stopSelf()
        }
        // the game's process gone = nothing to keep: never restarted by Android on its own
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, Tr.t(CHANNEL_NAME), NotificationManager.IMPORTANCE_LOW).apply {
            description = Tr.t(CHANNEL_DESCRIPTION)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Tr.t(TITLE))
            .setContentText(Tr.t(TEXT))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "game_session"
        private const val NOTIFICATION_ID = 1001
        const val TITLE = "DS Forever: game running"
        const val TEXT = "Tap to return to the game"
        const val CHANNEL_NAME = "Game running"
        const val CHANNEL_DESCRIPTION = "Shown while the game runs, so Android keeps it running when you switch apps."

        @Volatile
        private var running = false

        /** Starts the service if it isn't running. Works only while the app is in the foreground; MainActivity.onResume retries. */
        fun ensureStarted(context: Context) {
            if (running) return
            try {
                context.startForegroundService(Intent(context, GameSessionService::class.java))
                running = true
            } catch (e: Exception) {
                // Android 12+: ForegroundServiceStartNotAllowedException from the background; the next resume tries again
                Timber.w(e, "Game session service: couldn't start")
            }
        }

        fun stop(context: Context) {
            if (!running) return
            running = false
            runCatching { context.stopService(Intent(context, GameSessionService::class.java)) }
                .onFailure { Timber.w(it, "Game session service: couldn't stop") }
        }
    }
}
