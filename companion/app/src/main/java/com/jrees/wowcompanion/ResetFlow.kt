package com.jrees.wowcompanion

/**
 * RESET TO DEFAULTS, THE WAY THROUGH (the user, 2026-11-19). The pop-up's Yes ([ResetAsk]) starts one reset:
 *  - the game taking taps ([live]: Local or streaming - the tap reaches the PC the same way): the add-on's half FIRST, one tap on the
 *    pause row ([column]: PadMap m175 / Radial pr3), and nothing else until its answer {type = "dsfreset"} ([onAck]): ok = the app's half
 *    ([apply]: ResetRun), then the game's own Reload ([reload]: F12, every room reads its stores again and the router is armed from them);
 *    a fight = "Not in combat", no answer within [ACK_MS] = "No answer", and in both NOTHING changed (a half-done reset would come back
 *    from the live game's next save);
 *  - the game up but not taking taps yet ([gameUp] without [live]: the sign-in, the character list - the add-on's tables are not loaded,
 *    and the game file it will load still holds the old values): "Enter the world first", nothing changed;
 *  - no game: the app's half alone (the next start's fold writes the game file without the old values: SvReset).
 * The word on the button is [status] for [SHOW_MS]. Pure (the clock and the sends are the caller's): ResetFlowTest runs it.
 */
class ResetFlow(
    private val live: () -> Boolean,
    private val gameUp: () -> Boolean,
    private val tap: (Int) -> Boolean,
    private val apply: (ResetPlan.Button, Boolean) -> ResetRun.Result,
    private val reload: () -> Unit,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
) {
    data class Status(val button: ResetPlan.Button, val word: String, val at: Long)

    private data class Waiting(val button: ResetPlan.Button, val all: Boolean, val col: Int, val until: Long)

    private var waiting: Waiting? = null
    var status: Status? = null
        private set

    /** the word on [b]'s button now (a reset under way: "Resetting"; for [SHOW_MS] after one: its outcome), null = its own name */
    fun word(b: ResetPlan.Button): String? {
        val now = clock()
        waiting?.let { w -> if (now > w.until) timeOut() }
        if (waiting?.button == b) return WORKING
        return status?.takeIf { it.button == b && now - it.at < SHOW_MS }?.word
    }

    val busy get() = waiting != null

    /** the pop-up's Yes: [b] (with [all]: every character) */
    fun start(b: ResetPlan.Button, all: Boolean) {
        if (waiting != null) return
        val now = clock()
        if (!live()) {
            if (gameUp()) { say(b, NOT_NOW, now); log("settings reset: ${b.name.lowercase()}: the game is up but not in the world: nothing changed"); return }
            finish(b, all, now, "no game"); return
        }
        val col = column(b, all)
        if (!tap(col)) { say(b, NO_ANSWER, now); log("settings reset: ${b.name.lowercase()}: the tap was refused"); return }
        waiting = Waiting(b, all, col, now + ACK_MS)
        log("settings reset: ${b.name.lowercase()}${if (all) " (all characters)" else ""}: asked the game (column $col)")
    }

    /** the add-on's answer (PadMap m175) */
    fun onAck(col: Int, ok: Boolean, why: String?) {
        val w = waiting ?: return
        if (col != w.col) return
        waiting = null
        val now = clock()
        if (!ok) {
            log("settings reset: the game refused (${why ?: "no reason"})")
            say(w.button, if (why == "combat") NOT_IN_COMBAT else NOT_DONE, now); return
        }
        finish(w.button, w.all, now, "the game's done")
        reload()
    }

    private fun timeOut() {
        val w = waiting ?: return
        waiting = null
        log("settings reset: no answer from the game: nothing changed")
        say(w.button, NO_ANSWER, clock())
    }

    private fun finish(b: ResetPlan.Button, all: Boolean, now: Long, why: String) {
        val r = runCatching { apply(b, all) }.getOrElse { log("settings reset: NOT done (${it.message})"); null }
        say(b, if (r == null || r.refused.isNotEmpty()) NOT_DONE else DONE, now)
        log("settings reset: ${b.name.lowercase()} done ($why)")
    }

    private fun say(b: ResetPlan.Button, w: String, now: Long) { status = Status(b, w, now) }

    companion object {
        const val ACK_MS = 6_000L
        const val SHOW_MS = 4_000L
        const val DONE = "Done"
        const val WORKING = "Resetting"
        const val NOT_IN_COMBAT = "Not in combat"
        const val NO_ANSWER = "No answer"
        const val NOT_DONE = "Not done"
        const val NOT_NOW = "Enter the world first"
        /** the pause row's column for [b] (PadMap m175): 20 + code (settings 0, layouts 1, skills 2, everything 3), + 4 = every character */
        fun column(b: ResetPlan.Button, all: Boolean): Int = 20 + b.ordinal + if (all && b != ResetPlan.Button.SETTINGS) 4 else 0
    }
}

/**
 * THE RESET'S POP-UP, its model (the user's ruling: "Every button asks first in a real Yes/No pop-up that holds the pad"; the 1503 wheel
 * confirm's pattern, PopupHold). Reset everything asks first This character / All characters ([Step.SCOPE]; This character only with a
 * character known), then Yes / No ([Step.CONFIRM]). A = the lit choice (Yes on the confirm), B = No / back, Left / Right move the light;
 * every other press does nothing. Terse words (the terse-text rule). Pure: ResetAskTest runs it.
 */
class ResetAsk(val button: ResetPlan.Button, private val whoKnown: Boolean) {
    enum class Step { SCOPE, CONFIRM }
    sealed class Out { object None : Out(); object Closed : Out(); data class Yes(val all: Boolean) : Out() }

    var step = if (button == ResetPlan.Button.EVERYTHING) Step.SCOPE else Step.CONFIRM
        private set
    var all = button == ResetPlan.Button.EVERYTHING && !whoKnown
        private set
    /** the lit choice: 0 = the first (This character / Yes), 1 = the second (All characters / No) */
    var lit = if (step == Step.SCOPE && !whoKnown) 1 else 0
        private set

    val title: String get() = when (button) {
        ResetPlan.Button.SETTINGS -> "Reset settings?"; ResetPlan.Button.LAYOUTS -> "Reset layouts?"
        ResetPlan.Button.SKILLS -> "Reset skills?"; ResetPlan.Button.EVERYTHING -> "Reset everything?"
    }
    val line: String get() = when {
        step == Step.SCOPE -> "Which characters?"
        button == ResetPlan.Button.SETTINGS -> "Back to defaults."
        button == ResetPlan.Button.EVERYTHING && all -> "All characters, back to defaults."
        else -> "This character, back to defaults."
    }
    val choices: List<String> get() = if (step == Step.SCOPE) listOf("This character", "All characters") else listOf("Yes", "No")
    /** choice [k] can be picked (This character needs a character) */
    fun enabled(k: Int) = !(step == Step.SCOPE && k == 0 && !whoKnown)

    /** one press (the game's key names); the outcome */
    fun key(k: String): Out = when (k) {
        "PAD1" -> pick(lit)
        "PAD2" -> back()
        "PADDLEFT" -> { if (enabled(0)) lit = 0; Out.None }
        "PADDRIGHT" -> { lit = 1; Out.None }
        else -> Out.None
    }

    /** a touch on choice [k] */
    fun pick(k: Int): Out {
        if (!enabled(k)) return Out.None
        if (step == Step.SCOPE) { all = k == 1; step = Step.CONFIRM; lit = 0; return Out.None }
        return if (k == 0) Out.Yes(all) else Out.Closed
    }

    /** B: the confirm of Everything goes back to its scope; else closed */
    fun back(): Out {
        if (step == Step.CONFIRM && button == ResetPlan.Button.EVERYTHING) { step = Step.SCOPE; lit = if (all || !whoKnown) 1 else 0; return Out.None }
        return Out.Closed
    }
}
