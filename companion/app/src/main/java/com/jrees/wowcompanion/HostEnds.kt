package com.jrees.wowcompanion

import android.content.Context
import android.content.ContextWrapper
import android.util.Log

/**
 * WHAT A HOST LEFT IN THE PROCESS IS TAKEN OFF AT ITS END (feat/bottom-sync, 2026-11-26; HostsReleasedTest). The settings' and the
 * stores' listener lists (Theme, Settings, HomeSettings and its switches, CharacterBars, BrowserLogin, CastSettings, FrameGen, GameUi,
 * ImportedAddons, ThirdPartyAddons, VoiceModels, LayoutModel.Live, CastStripModel.Live, RouteWork's file listeners ...), the hooks a
 * host sets on the process-wide objects (StartMenu, SetupBridge, Voice, NativeLogin ...), the memory sources keyed by name and the
 * broadcast receiver live as long as the process. Before, every CompanionHost that was replaced - a real rebuild of the bottom screen, a
 * single screen mode switch, Swap screens' return - stayed alive through them with its rooms, its Home, its map and its decoded art,
 * and its listeners went on running (on an 8 GB Thor that is one more whole host every time). Now the host and every view it makes
 * register their removal here; CompanionHost.onDestroy runs them all, newest first. A hook is cleared only while it is still this
 * host's ([own]): a newer host's own is never touched, so a live host never misses a notification. Main thread.
 */
class HostEnds {
    private val offs = ArrayList<() -> Unit>()
    @Volatile var ended = false
        private set

    /** [off] runs at the end (once); added after the end it runs at once (a view made by a host that already went) */
    fun add(off: () -> Unit) {
        val now = synchronized(offs) { if (ended) true else { offs += off; false } }
        if (now) runCatching(off)
    }

    /** a process-wide hook this host has just set (its value now): back to [off] at its end, only while it still holds that value */
    fun <T> own(p: kotlin.reflect.KMutableProperty0<T>, off: T) {
        val v = p.get()
        add { if (p.get() === v) p.set(off) }
    }

    /** everything off, newest first; a failing one never stops the rest */
    fun end() {
        val list = synchronized(offs) { ended = true; offs.toList().also { offs.clear() } }
        for (f in list.asReversed()) runCatching { f() }.onFailure { Log.w("WCLOG", "host end: ${it.message}", it) }
    }

    val size: Int get() = synchronized(offs) { offs.size }
}

/** the host this context belongs to: every view of a host is made with the host itself (a ContextWrapper) or a wrapper of it; null = none
 *  (a view made by a test with the application, another activity of ours) */
internal fun Context.hostOf(): CompanionHost? {
    var c: Context? = this
    repeat(16) {
        when (c) {
            is CompanionHost -> return c as CompanionHost
            is ContextWrapper -> c = (c as ContextWrapper).baseContext
            else -> return null
        }
    }
    return null
}

/** [off] runs at the end of the host this context belongs to (none: never - such a view's registrations stay as before) */
internal fun Context.atHostEnd(off: () -> Unit) { hostOf()?.ends?.add(off) }

/** [HostEnds.own] for a view's hook (none: nothing, as before) */
internal fun <T> Context.hostOwn(p: kotlin.reflect.KMutableProperty0<T>, off: T) { hostOf()?.ends?.own(p, off) }
