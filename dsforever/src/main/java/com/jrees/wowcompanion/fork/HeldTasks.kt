package com.jrees.wowcompanion.fork

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle

/**
 * ROUND 2 OF THE REVIEW (2026-11-26: "held is cleared only when our activity is destroyed. A picker left in the background keeps the
 * bottom window hidden at every return"): our own activities alive in this process, by class, with their task ids - what
 * HostPresentation.heldUp asks whether an activity of ours over the bottom screen (CompanionHost.openOnBottom: the import picker, the
 * DLL picker, the microphone question) is still UP. The activity's own stop says nothing (a picker is stopped the moment Android's file
 * chooser covers it, in its same task), so its TASK's visibility is asked ([shows]: ActivityManager.AppTask's TaskInfo.isVisible, read
 * only - never a move: the #383 / #384 lesson). Process level: task ids only (no activity, no host is held), registered once on the
 * application. Our own tasks only: no permission, no root. Main thread
 */
internal object HeldTasks : Application.ActivityLifecycleCallbacks {
    private val live = HashMap<Int, Pair<String, Int>>()                  // identityHashCode -> (class name, task id)
    /** the application it is registered on (one per process; the off-device tests make a new one per test) */
    @Volatile private var on: Application? = null

    fun watch(ctx: Context) {
        val app = ctx.applicationContext as? Application ?: return
        if (on === app) return
        on = app
        synchronized(live) { live.clear() }
        app.registerActivityLifecycleCallbacks(this)
    }

    /** the task ids of the live instances of [cls] (empty = none alive) */
    fun tasksOf(cls: String): Set<Int> = synchronized(live) { live.values.filter { it.first == cls }.map { it.second }.toSet() }

    /**
     * an instance of [cls] is alive and its task shows on a screen now. None alive = false; unknown (an older Android than 12, a refused
     * query) = true (the old rule: the window stays hidden until the activity closes)
     */
    fun shows(ctx: Context, cls: String): Boolean {
        val tasks = tasksOf(cls)
        if (tasks.isEmpty()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return runCatching {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java) ?: return@runCatching true
            am.appTasks.any { t -> runCatching { t.taskInfo.let { i -> i.taskId in tasks && i.isVisible } }.getOrDefault(false) }
        }.getOrDefault(true)
    }

    override fun onActivityCreated(a: Activity, b: Bundle?) { synchronized(live) { live[System.identityHashCode(a)] = a.javaClass.name to a.taskId } }
    override fun onActivityDestroyed(a: Activity) { synchronized(live) { live.remove(System.identityHashCode(a)) } }
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityResumed(a: Activity) {}
    override fun onActivityPaused(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
}
