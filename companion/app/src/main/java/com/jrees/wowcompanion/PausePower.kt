package com.jrees.wowcompanion

/**
 * THE PAUSE MENU'S POWER BUTTON (feat/pause-power; the user, 2026-11-18: "we need a way to close the game from the app in game ... on the
 * top right corner of the screen I want to add some kind of power button that effectively opens a prompt to close DS forever. When
 * streaming this should also close wow on the PC and revert everything back and be the clean way to disconnect").
 *
 * [Flow] is the button's prompt, pure (HomePauseView drives it): Y on the pad or a tap on the button asks ("Close DS Forever?" and one
 * line of what else closes); A / Close confirms, B / Cancel / a tap outside the box goes back to the wheel (B one step back, the wheel's
 * own B then closes it: the "B in sub-screens" rule); once confirmed the prompt says the close is under way and takes no more input.
 * A fight refuses the button (the game's own Exit Game waits out a fight, and DS Forever never hard-closes a fighting character): the
 * wheel's red line says why.
 *
 * [Sequence] is what Close does, pure (CompanionHost hands it the real pieces; the tests hand it fakes):
 *  - LOCAL: the game's own Exit Game first ([LauncherHooks.cleanExit], the safe close's route: its save written as it exits), then DS
 *    Forever closes (its task finished, the game's environment shut down: [LauncherHooks.quitApp]); the old close only when the clean
 *    exit says it is needed (it timed out, the login screen, no add-on). No game running: DS Forever closes at once.
 *  - STREAMING: the game's own Exit Game tapped over the stream when it can be (its save written on the PC too), then Desktop is told
 *    "end_session" over the bridge ([SetupBridge.endSession]): it closes the PC's WoW cleanly, puts the PC back (controller mode, WoW's
 *    window keys, the monitor's mode) and answers; the stream stops, the bridge disconnects, DS Forever closes. Desktop stays up.
 */
object PausePower {
    /** the prompt's context line: what else closes (terse: labels only) */
    const val LOCAL_LINE = "WoW closes too"
    const val STREAM_LINE = "WoW on your PC closes and your PC is put back"
    /** the prompt's line while the close runs */
    const val CLOSING_LOCAL = "Closing WoW"
    const val CLOSING_STREAM = "Closing WoW on your PC"
    /** the wheel's red line when the button is refused */
    const val FIGHT_LINE = "Not during a fight"

    fun context(streaming: Boolean) = if (streaming) STREAM_LINE else LOCAL_LINE
    fun closing(streaming: Boolean) = if (streaming) CLOSING_STREAM else CLOSING_LOCAL

    enum class State { NONE, ASKING, CLOSING }

    sealed class Act {
        object None : Act()
        /** the prompt shows */
        object Asked : Act()
        /** back to the wheel */
        object Cancelled : Act()
        /** Close: run the [Sequence] */
        object Confirmed : Act()
        /** the button can't be used now: the wheel's line says [line] */
        data class Refused(val line: String) : Act()
    }

    class Flow {
        var state = State.NONE
            private set
        val asking get() = state == State.ASKING
        val busy get() = state != State.NONE

        fun reset() { state = State.NONE }

        /** Y, or a tap on the button; [refusal] = why it can't be used now (a fight), null = it can */
        fun press(refusal: String?): Act {
            if (state != State.NONE) return Act.None
            if (refusal != null) return Act.Refused(refusal)
            state = State.ASKING
            return Act.Asked
        }

        /** A, or a tap on Close */
        fun confirm(): Act {
            if (state != State.ASKING) return Act.None
            state = State.CLOSING
            return Act.Confirmed
        }

        /** B, or a tap on Cancel / outside the box; nothing once the close runs */
        fun cancel(): Act {
            if (state != State.ASKING) return Act.None
            state = State.NONE
            return Act.Cancelled
        }

        /** a finger lifted on the prompt: [hit] = UiKit.pausePowerPromptHit's (POWER_CANCEL, POWER_CLOSE, -1 the box, -2 outside) */
        fun touch(hit: Int): Act = when (hit) {
            UiKit.POWER_CLOSE -> confirm()
            UiKit.POWER_CANCEL, -2 -> cancel()
            else -> Act.None
        }
    }

    /** Desktop's answer to "end_session" (null fields = an older Desktop that never answered) */
    data class Ended(val answered: Boolean, val wow: String = "", val putBack: Boolean = false, val why: String = "")

    /**
     * THE CLOSE, once confirmed. [streaming] = a stream runs (SetupBridge.endSession set); [gameUp] = the local game runs;
     * [cleanExit] = LauncherHooks.cleanExit (null = no companion route); [quitTap] = the game's own Exit Game tapped over the stream
     * (true = asked); [endSession] = StreamTop's end (why, the Exit Game was asked, its answer); [quitApp] = DS Forever closes (true =
     * the old close of the game first). Every step logged through [log].
     */
    class Sequence(
        private val streaming: () -> Boolean,
        private val gameUp: () -> Boolean,
        private val cleanExit: () -> ((String, (Boolean) -> Unit) -> Unit)?,
        private val quitTap: () -> Boolean,
        private val endSession: () -> ((String, Boolean, (Ended) -> Unit) -> Unit)?,
        private val quitApp: (force: Boolean) -> Unit,
        private val log: (String) -> Unit,
    ) {
        var running = false
            private set

        fun run() {
            if (running) return
            running = true
            val end = endSession()
            if (streaming() && end != null) {
                val asked = runCatching { quitTap() }.getOrDefault(false)
                log("power: streaming: ${if (asked) "the game's own Exit Game asked over the stream" else "no Exit Game tap (not asked)"}; " +
                    "end_session to Desktop")
                end("the power button", asked) { r ->
                    log("power: Desktop " + if (r.answered) "answered: WoW ${r.wow.ifEmpty { "?" }}, put back ${r.putBack}" +
                        (if (r.why.isNotEmpty()) " (${r.why})" else "") else "did not answer (an older Desktop, or no connection): the stream stops anyway")
                    log("power: DS Forever closes")
                    quitApp(false)
                }
                return
            }
            val ce = cleanExit()
            when {
                !gameUp() -> { log("power: local: no game running: DS Forever closes"); quitApp(false) }
                ce == null -> { log("power: local: no clean exit route: the old close, then DS Forever closes"); quitApp(true) }
                else -> {
                    log("power: local: the game's own Exit Game first (the safe close)")
                    ce("power") { force ->
                        log("power: local: " + (if (force) "the game did not exit by itself: the old close" else "the game exited by itself, its state saved") +
                            "; DS Forever closes")
                        quitApp(force)
                    }
                }
            }
        }
    }
}
