package com.jrees.wowcompanion

import android.os.Build
import android.os.Process
import android.os.SystemClock

/**
 * THE START-UP PATH, TIMED (the user, after reports #8-#13 held only the player's words: "make the APP start up provide WAY more
 * information"): each stage the start-up screen reaches - the splash (the process start), the checks, the art, sign-in, the launch,
 * the game up - with its time since the process started. Fed by [SetupBridge.publish] (the launcher's screen publishes its state on
 * every change: [observe] names the stage) and by the launcher's own launch steps ([LauncherHooks.launchEvent]). Kept in memory, the
 * last [MAX] stages; [StartupDiag] puts them in every problem report.
 */
object StartupTrace {
    const val MAX = 60

    class Stage(val atMs: Long, val name: String, val detail: String)

    private val stages = ArrayList<Stage>()
    @Volatile private var lastKey = ""

    /** the process's own start (the splash), on the elapsed clock */
    val processStartMs: Long by lazy {
        if (Build.VERSION.SDK_INT >= 24) runCatching { Process.getStartElapsedRealtime() }.getOrDefault(SystemClock.elapsedRealtime())
        else SystemClock.elapsedRealtime()
    }

    /** a stage reached now ([name] the stage, [detail] what it said then); the same stage again is not a new one */
    fun mark(name: String, detail: String = "", nowMs: Long = SystemClock.elapsedRealtime()) = synchronized(stages) {
        val key = name
        if (key == lastKey) return
        lastKey = key
        stages += Stage(nowMs - processStartMs, name, detail.take(200))
        if (stages.size > MAX) stages.removeAt(0)
    }

    /** the stage the start-up screen's state [i] stands at (pure: the tests read it) */
    fun stageOf(i: SetupFlow.Inputs, gameUp: Boolean): Pair<String, String> = when {
        gameUp || i.gameRunning -> "game up" to ""
        !i.folderFound -> "no game folder" to i.path
        !i.storage -> "storage permission" to ""
        i.wrongGame -> "wrong game in the folder" to i.product
        i.installedExternal -> "installed on external storage" to ""
        i.busy != null -> "busy: ${i.busy.take(48)}" to i.busy
        i.client == null -> "checks: game version" to ""
        i.art == SetupFlow.Art.RUNNING -> "art: ${i.artPercent / 10 * 10}%" to i.artWhat
        i.art == SetupFlow.Art.FAILED -> "art failed" to i.artWhy
        i.signIn != SetupFlow.SignIn.NONE -> "sign-in: ${i.signIn.name.lowercase()}" to ""
        else -> "ready (Play)" to (i.client ?: "")
    }

    /** the start-up screen's state changed ([SetupBridge.publish]) */
    fun observe(i: SetupFlow.Inputs?, gameUp: Boolean = LauncherHooks.gameUp()) {
        if (i == null) return
        val (name, detail) = stageOf(i, gameUp)
        mark(name, detail)
    }

    /** "+12.3 s  art: 40%  (what it said)", oldest first */
    fun lines(): List<String> = synchronized(stages) {
        stages.map { s -> "+%.1f s  %s%s".format(java.util.Locale.US, s.atMs / 1000.0, s.name, if (s.detail.isNotBlank()) "  (${s.detail})" else "") }
    }

    /** the last stage reached, or null */
    fun last(): String? = synchronized(stages) { stages.lastOrNull()?.name }

    /** the tests' clean slate */
    fun reset() = synchronized(stages) { stages.clear(); lastKey = "" }
}
