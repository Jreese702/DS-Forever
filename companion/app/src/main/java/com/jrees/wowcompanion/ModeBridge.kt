package com.jrees.wowcompanion

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.util.concurrent.CopyOnWriteArrayList

/**
 * THE START-UP MODE, LIVE (S1 "Chooser"; the rules are [StartMode]'s). The launcher's activity starts it ([start] in onCreate, with the
 * intent's [StartMode.EXTRA]; [onIntent] for a shortcut tapped while the app runs); the launcher's top screen follows [route] (the
 * chooser's top, today's Local start screen, or the Streaming entry); the bottom screen's [ChooserPanel] draws the chooser and presses
 * [pick] / [addShortcut]; B or the swirl on a mode's start screen calls [back]. Main thread.
 */
object ModeBridge {
    private const val TAG = "DSFMODE"

    /** null = not started yet (the launcher's activity has not run its onCreate in this process) */
    @Volatile var route: StartMode.Route? = null
        private set

    private var app: Context? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun onChange(l: () -> Unit) { listeners.add(l) }
    fun offChange(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { for (l in listeners) runCatching { l() } }

    /** the last mode entered (prefs), or null */
    fun remembered(ctx: Context): String? =
        runCatching { ctx.getSharedPreferences(StartMode.PREFS, Context.MODE_PRIVATE).getString(StartMode.KEY_LAST, null) }.getOrNull()

    /** the remembered pick as the chooser's cursor start (null before [start]) */
    fun rememberedNow(): String? = app?.let { remembered(it) }

    /**
     * The launcher's activity was created ([extra] its intent's dsf.mode, or null). The first start in this process resolves the route
     * (shortcut -> remembered -> chooser); a recreated activity keeps its mode, and one carrying a shortcut's extra is [onIntent]'s.
     */
    fun start(ctx: Context, extra: String?) {
        app = ctx.applicationContext
        if (route != null) { onIntent(ctx, extra); return }
        go(StartMode.resolve(extra, remembered(ctx)))
    }

    /** a new intent for the running activity (a shortcut tapped with the app open) */
    fun onIntent(ctx: Context, extra: String?) {
        app = ctx.applicationContext
        val next = StartMode.onShortcut(route, extra, LauncherHooks.gameUp(), SetupBridge.restarting, SetupBridge.inputs?.busy != null)
        if (next == null) { if (StartMode.parse(extra) != null) Log.i(TAG, "shortcut ${extra} ignored (route $route, game up ${LauncherHooks.gameUp()})"); return }
        go(next)
    }

    /** the chooser's card pressed (A or a touch) */
    fun pick(mode: StartMode.Mode) {
        if (route != StartMode.Route.Chooser) return
        go(StartMode.Route.Into(mode, StartMode.By.PICKED))
    }

    /** B / the swirl may bring the chooser back now */
    fun canGoBack(): Boolean = StartMode.canGoBack(route, LauncherHooks.gameUp(), SetupBridge.inputs?.busy != null,
        SetupBridge.restarting, SetupBridge.loginFormOpen)

    /** back to the chooser (true = done) */
    fun back(why: String = "B"): Boolean {
        if (!canGoBack()) return false
        Log.i(TAG, "back to the chooser ($why)")
        go(StartMode.Route.Chooser)
        return true
    }

    private fun go(r: StartMode.Route) {
        val was = route
        if (StartMode.showsLocal(was) && !StartMode.showsLocal(r)) {
            // the Local start screen goes: its setup state goes with it (the bottom's setup panel hides; Local publishes it again)
            SetupBridge.publish(null)
        }
        route = r
        StartMode.remember(r)?.let { id ->
            runCatching { app?.getSharedPreferences(StartMode.PREFS, Context.MODE_PRIVATE)?.edit()?.putString(StartMode.KEY_LAST, id)?.apply() }
        }
        runCatching { StartupTrace.mark("mode: $r") }
        Log.i(TAG, "route $was -> $r")
        changed()
    }

    // ------------------------------------------------------------------ the pad before Play (the launcher's activity has the keys)

    /** the chooser panel's keys while it shows (true = the panel's) */
    @Volatile var keyHandler: ((keyCode: Int, down: Boolean) -> Boolean)? = null
    fun key(keyCode: Int, down: Boolean): Boolean = keyHandler?.invoke(keyCode, down) ?: false

    // ------------------------------------------------------------------ Streaming's bottom screen

    /**
     * Streaming's bottom screen: true = the placeholder page ("Streaming: connect to DS Forever Desktop", Back) covers it; S2 sets
     * false once the PC is linked, and the companion (Home, the rooms) shows as in Local.
     */
    @Volatile var streamCovers = true
        set(v) { if (field != v) { field = v; changed() } }

    /** one line under the Streaming page's text (S2's status: "Looking for your PC…"); "" = none */
    @Volatile var streamNote = ""
        set(v) { if (field != v) { field = v; changed() } }

    // ------------------------------------------------------------------ home-screen shortcuts

    /**
     * Asks the home screen to pin [mode]'s shortcut (Android's own confirmation follows); its intent opens the app with dsf.mode set.
     * Returns the line the chooser shows.
     */
    fun addShortcut(ctx: Context, mode: StartMode.Mode): String {
        val c = ctx.applicationContext
        return try {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(c)) return "This home screen can't add shortcuts"
            val s = StartMode.shortcut(mode)
            val intent = (c.packageManager.getLaunchIntentForPackage(c.packageName) ?: Intent().setPackage(c.packageName))
                .setAction(Intent.ACTION_MAIN)
                .putExtra(StartMode.EXTRA, s.extra)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val info = ShortcutInfoCompat.Builder(c, s.id)
                .setShortLabel(s.shortLabel)
                .setLongLabel(s.longLabel)
                .setIcon(IconCompat.createWithResource(c, c.applicationInfo.icon))
                .setIntent(intent)
                .build()
            val ok = ShortcutManagerCompat.requestPinShortcut(c, info, null)
            Log.i(TAG, "pin shortcut ${s.id}: $ok")
            if (ok) "${mode.title} shortcut: confirm it on the home screen" else "The home screen refused the shortcut"
        } catch (t: Throwable) {
            Log.w(TAG, "pin shortcut: ${t.message}")
            "The shortcut couldn't be added (${t.message})"
        }
    }

    /** the tests' clean slate */
    fun reset() { route = null; app = null; keyHandler = null; streamCovers = true; streamNote = ""; listeners.clear() }
}
