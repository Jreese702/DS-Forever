package com.jrees.wowcompanion

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * IS ROOT OURS? (the share's fresh test 2026-10-06: Magisk's first prompt timed out unseen, Magisk saved "Deny", and the app sat on a
 * silent "Waiting for the game." - the user: "Both: SETUP line + app message"). One `su -c id -u` at a time: uid 0 = [State.OK]; a
 * quick refusal = [State.REFUSED] (Magisk's saved Deny); no answer within [WAIT_MS] = [State.ASKING] (Magisk's prompt is up and waits
 * for the player; the request is left to finish, never killed, so the prompt is not cancelled by us).
 */
object HomeRoot {
    enum class State { OK, REFUSED, ASKING }

    const val WAIT_MS = 2_500L
    const val REFUSED_LINE = "Root is refused. Open Magisk > Superuser and turn on DS Forever."
    const val ASKING_LINE = "Allow DS Forever in Magisk's root prompt."

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var pending: Process? = null
    /** the app's one root shell is open (MainActivity: RootShell::alive; kept out of this file so it stays Android-free) */
    var shellAlive: () -> Boolean = { false }

    /** [done] on the worker's thread; nothing when an earlier request is still waiting for Magisk's prompt (it says ASKING then) */
    fun check(done: (State) -> Unit) {
        // the app's one root shell is open and alive: root is ours, no new su (and no Magisk toast) needed to say so
        if (shellAlive()) { done(State.OK); return }
        // inside the private launcher there is no root to ask for, and none is needed (RootPolicy)
        if (!RootPolicy.allowed) { done(State.OK); return }
        pending?.let { if (alive(it)) { done(State.ASKING); return } }
        worker.execute {
            val st = try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id -u"))
                pending = p
                if (!p.waitFor(WAIT_MS, TimeUnit.MILLISECONDS)) State.ASKING
                else {
                    pending = null
                    val out = p.inputStream.bufferedReader().readText().trim()
                    if (p.exitValue() == 0 && out == "0") State.OK else State.REFUSED
                }
            } catch (_: Throwable) { pending = null; State.REFUSED }
            done(st)
        }
    }

    private fun alive(p: Process) = try { p.exitValue(); false } catch (_: IllegalThreadStateException) { true }

    /** the line the start screen shows for [s], or null to keep its own; only over the plain waiting line or an earlier root line */
    fun line(s: State, current: String): String? {
        val ours = current == HomeRoot.REFUSED_LINE || current == HomeRoot.ASKING_LINE
        if (!ours && current != WAITING) return null
        return when (s) {
            State.OK -> if (ours) WAITING else null
            State.REFUSED -> REFUSED_LINE
            State.ASKING -> ASKING_LINE
        }
    }
    const val WAITING = "Waiting for the game."
}
