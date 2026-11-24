package app.gamenative

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.gamenative.events.AndroidEvent
import com.jrees.wowcompanion.BgAppend
import com.jrees.wowcompanion.DiagLog
import com.jrees.wowcompanion.ExitNow
import com.jrees.wowcompanion.GameLife
import com.jrees.wowcompanion.IndoorSchedulerStep
import com.jrees.wowcompanion.LastRun
import com.jrees.wowcompanion.NativeLogin
import com.jrees.wowcompanion.ScreenSwap
import com.jrees.wowcompanion.SetupBridge
import com.jrees.wowcompanion.fork.DsForeverScreen
import com.winlator.core.ProcessHelper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * feat/exit-now (the user, 2026-11-24: "I want our built in close game button to completly close the app immidiatly"): the launcher's
 * steps of [ExitNow] - the Menu's Exit game closes the whole of DS Forever at once. Its own thread; the steps that touch the UI, the
 * game's environment or the services run on the main thread, each waited for at most its bound.
 */
object ExitNowHost {
    private const val TAG = "WCSETUP"

    fun start(ctx: Context, why: String) {
        val app = ctx.applicationContext
        // ours, not Android's: in place before anything else can end the game (the guest's end reads it)
        GameLife.closer = ExitNow.CLOSER
        Thread({ ExitNow.Sequence(steps(app, why)).run() }, "dsf-exit-now").start()
    }

    private fun steps(app: Context, why: String) = ExitNow.Steps(
        watchdog = { ms ->
            Thread({
                try { Thread.sleep(ms) } catch (_: InterruptedException) { return@Thread }
                Log.w(TAG, "exit now: still up $ms ms after the press: the process ends")
                endProcess(app)
            }, "dsf-exit-now-watchdog").apply { isDaemon = true; start() }
        },
        mark = {
            GameLife.closer = ExitNow.CLOSER
            LastRun.ownExit(app)
            DiagLog.event("app", "Exit game ($why): DS Forever closes at once (the game ends without its own Exit Game)")
            IndoorSchedulerStep.appExit()
            ScreenSwap.ownExit(app)
            NativeLogin.clear(app)
        },
        flush = { ms -> flush(app, ms) },
        streaming = { SetupBridge.quitStream != null },
        endStream = { ms -> SetupBridge.quitStream?.invoke(ms) },
        killGame = { ms ->
            // every Wine process of ours (the game, wineserver, the bridge) at once; a /proc that cannot be read still gets the teardown
            val me = android.os.Process.myPid().toString()
            runCatching {
                val pids = ProcessHelper.listRunningWineProcesses().filter { it != me }.distinct()
                for (p in pids) runCatching { ProcessHelper.killProcess(p.toInt()) }
                Log.i(TAG, "exit now: ${pids.size} Wine processes killed (the game, wineserver)")
            }.onFailure { Log.w(TAG, "exit now: the Wine processes could not be listed (${it.message}): the environment's teardown ends them") }
            onMain(ms) {
                if (PluviaApp.xEnvironment != null) PluviaApp.events.emit(AndroidEvent.ForceCloseApp)
                if (PluviaApp.keepAlive || PluviaApp.xEnvironment != null) PluviaApp.shutdownEnvironment()
            }
        },
        stopServices = {
            onMain(ExitNow.MAIN_MS) {
                GameSessionService.stop(app)
                IndoorBuildService.Control.stop(app)
                runCatching { app.getSystemService(NotificationManager::class.java)?.cancelAll() }
            }
        },
        finishUi = {
            // the bottom screen (its Presentation, or Swap screens' activity) closed on the main thread; then every task of ours - the
            // launcher, Streaming's, Swap screens' bottom one - finished and out of recents from here (Android's own call, so a busy
            // main thread never keeps a task in recents)
            onMain(ExitNow.MAIN_MS) { DsForeverScreen.closeAll() }
            val tasks = app.getSystemService(ActivityManager::class.java)?.appTasks.orEmpty()
            for (t in tasks) runCatching { t.finishAndRemoveTask() }
            Log.i(TAG, "exit now: ${tasks.size} task(s) finished and removed from recents")
        },
        uiGone = {
            MainActivity.live <= 0 && app.getSystemService(ActivityManager::class.java)?.appTasks.isNullOrEmpty()
        },
        endProcess = { endProcess(app) },
        log = { Log.i(TAG, it) },
    )

    /** DS Forever's pending writes on disk within [boundMs]: the background logs, then every SharedPreferences file's apply()s */
    private fun flush(app: Context, boundMs: Long) {
        val until = System.currentTimeMillis() + boundMs
        val logs = BgAppend.flushAll(boundMs / 2)
        // an empty commit writes what apply() still holds for that file (and waits for it); a file nobody opened this run is read once
        val names = File(app.applicationInfo.dataDir, "shared_prefs").list()?.filter { it.endsWith(".xml") }?.map { it.removeSuffix(".xml") }.orEmpty()
        var done = 0
        for (n in names) {
            if (System.currentTimeMillis() >= until) break
            if (runCatching { app.getSharedPreferences(n, Context.MODE_PRIVATE).edit().commit() }.getOrDefault(false)) done++
        }
        Log.i(TAG, "exit now: pending writes flushed (logs ${if (logs) "written" else "not all in time"}, settings $done/${names.size})")
    }

    private fun endProcess(app: Context) {
        LastRun.ownExit(app)                 // the mark again, a moment before Android's record of the kill
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** [f] on the main thread, waited for at most [boundMs] (at once when already there); its failure is logged, never thrown there
     *  (a crash on the main thread would be Android's "DS Forever crashed" at the next start) */
    private fun onMain(boundMs: Long, f: () -> Unit) {
        val safe = { runCatching(f).onFailure { Log.w(TAG, "exit now: a main-thread step failed (${it.javaClass.simpleName}: ${it.message})") }; Unit }
        if (Looper.myLooper() == Looper.getMainLooper()) { safe(); return }
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { try { safe() } finally { done.countDown() } }
        if (!done.await(boundMs, TimeUnit.MILLISECONDS)) Log.w(TAG, "exit now: a main-thread step still running after $boundMs ms: going on")
    }
}
