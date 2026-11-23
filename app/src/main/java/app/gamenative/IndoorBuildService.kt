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
import com.jrees.wowcompanion.IndoorKeepAlive
import com.jrees.wowcompanion.Tr
import timber.log.Timber

/**
 * THE INDOOR MAPS' QUIET SERVICE (issue 568, the user's ruling 2026-11-23): runs only while the indoor-maps builder builds after a game,
 * so Android counts DS Forever as a foreground-service app instead of a cached one and no longer kills it for the builder's CPU use
 * ("EXCESSIVE_RESOURCE_USAGE ... excessive cpu ... limit=25", importance 400) or freezes it. When it starts and stops is
 * [IndoorKeepAlive]'s decision (the companion's side): this class is only the service and its notification.
 *
 * The notification is silent and low importance, on its own channel ("Indoor maps"): "Building indoor maps", "120/1853 ready" with a
 * progress bar. Like the game-running service, DS Forever asks for no notification permission: on Android 13+ the notification stays
 * out of the shade (Android's running-apps list shows it) and the service works the same. No new permission: FOREGROUND_SERVICE +
 * FOREGROUND_SERVICE_SPECIAL_USE are already declared for [GameSessionService].
 */
class IndoorBuildService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    /** the [Control.start] this instance last ran for ([gen] at that start) */
    private var myGen = -1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        myGen = intent?.getIntExtra(EXTRA_GEN, gen) ?: gen
        try {
            val n = notification(this, intent?.getIntExtra(EXTRA_READY, 0) ?: 0, intent?.getIntExtra(EXTRA_TOTAL, 0) ?: 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
            inForeground = true
            Timber.i("Indoor maps service: in the foreground")
            // a stop that came before Android ran this (a game started at once): only now, as Android crashes an app whose
            // startForegroundService ends before its startForeground
            // review: by its start id, so a start that came after this stop keeps the service (and Android's wait for that start's
            // startForeground is never cut short)
            if (stopWanted) { stopWanted = false; stopSelf(startId) }
        } catch (e: Exception) {
            Timber.w(e, "Indoor maps service: couldn't go to the foreground")
            onLost("no foreground: ${e.javaClass.simpleName}")
            stopSelf()
        }
        // the builder's process gone = nothing to keep: never restarted by Android on its own
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // review: a stop then a quick new start (DS Forever back in front and away again, the builder's own status) ends THIS instance
        // after the new start was asked for; Android makes a new instance for it. Only the newest start's instance may say the
        // service is gone, or the keep-alive would count the new one as off and never stop it (it would stay on beside a game)
        if (myGen == gen) {
            inForeground = false
            runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
            onLost("the service ended")
        }
        super.onDestroy()
    }

    /** the companion's [IndoorKeepAlive.Service]: set by PluviaApp at the app's start */
    object Control : IndoorKeepAlive.Service {
        override fun start(ctx: Context, ready: Int, total: Int): Boolean = try {
            stopWanted = false
            // review: not in the foreground until this start's onStartCommand (a stop meanwhile waits for it, see there)
            inForeground = false
            val g = ++gen
            ctx.startForegroundService(Intent(ctx, IndoorBuildService::class.java).putExtra(EXTRA_READY, ready).putExtra(EXTRA_TOTAL, total)
                .putExtra(EXTRA_GEN, g))
            true
        } catch (e: Exception) {
            // Android 12+: ForegroundServiceStartNotAllowedException while DS Forever is not in front; asked again when it is
            Timber.w(e, "Indoor maps service: couldn't start")
            false
        }

        override fun progress(ctx: Context, ready: Int, total: Int) {
            // review: only the foreground notification is refreshed; a notify before (or without) startForeground would leave a plain
            // ongoing "Building indoor maps" behind that no service end removes
            if (!inForeground) return
            runCatching { ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(ctx, ready, total)) }
                .onFailure { Timber.w(it, "Indoor maps service: progress") }
        }

        override fun stop(ctx: Context) {
            // not in the foreground yet: it stops itself right after its startForeground (see onStartCommand)
            if (!inForeground) { stopWanted = true; return }
            runCatching { ctx.stopService(Intent(ctx, IndoorBuildService::class.java)) }
                .onFailure { Timber.w(it, "Indoor maps service: couldn't stop") }
        }
    }

    companion object {
        /** the service reached startForeground (a stop before that waits for it) */
        @Volatile internal var inForeground = false
        @Volatile internal var stopWanted = false
        /** bumped by every [Control.start]: which instance is the newest start's ([onDestroy]) */
        @Volatile internal var gen = 0
        /** what the service's own end tells (the keep-alive; the tests listen here) */
        @Volatile internal var onLost: (String) -> Unit = { IndoorKeepAlive.lost(it) }
        const val CHANNEL_ID = "indoor_maps"
        const val NOTIFICATION_ID = 1002
        const val TITLE = "Building indoor maps"
        /** "120/1853 ready" (a template: the numbers are carried over as they are) */
        const val PROGRESS = "%s/%s ready"
        const val CHANNEL_NAME = "Indoor maps"
        const val CHANNEL_DESCRIPTION = "Shown while indoor maps build, so Android lets them finish."
        private const val EXTRA_READY = "ready"
        private const val EXTRA_TOTAL = "total"
        private const val EXTRA_GEN = "gen"

        fun progressText(ready: Int, total: Int): String? = if (total > 0) Tr.t(PROGRESS.replaceFirst("%s", "$ready").replaceFirst("%s", "$total")) else null

        fun notification(ctx: Context, ready: Int, total: Int): Notification {
            val manager = ctx.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, Tr.t(CHANNEL_NAME), NotificationManager.IMPORTANCE_LOW).apply {
                description = Tr.t(CHANNEL_DESCRIPTION)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
            val openApp = PendingIntent.getActivity(
                ctx,
                1,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(Tr.t(TITLE))
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
            progressText(ready, total)?.let { b.setContentText(it).setProgress(total, ready.coerceIn(0, total), false) }
            return b.build()
        }
    }
}
