package com.jrees.wowcompanion

/**
 * EXIT GAME CLOSES DS FOREVER AT ONCE (feat/exit-now; the user, 2026-11-24: "I want our built in close game button to completly close
 * the app immidiatly"). The waiting screen's Menu > Exit game used to run the game's own Exit Game first (its 20 s countdown, the safe
 * close up to ~65 s) and then only ended the game's environment: DS Forever stayed open on its launcher screen. Now the whole app goes,
 * Local or Streaming, in under 2 s:
 *  1. [Steps.mark]: "Exit game from the menu" as the game's closer (GameLife: the next report never calls it a crash), our own exit's
 *     mark for the next start (LastRun: Android's signal-9 record of it is no "ended unexpectedly"), the indoor maps' kill marker gone
 *     (not a failed bake), a written login.txt deleted, Swap screens' start count taken back;
 *  2. [Steps.flush]: DS Forever's pending writes on disk (every SharedPreferences apply(), the background logs), at most [FLUSH_MS].
 *     SvKeep's copy of our add-on's settings is written as each word arrives: nothing of it waits;
 *  3. [Steps.endStream] while a stream runs: "end_session" to Desktop (it closes the PC's WoW and puts the PC back on its own), the
 *     stream and the bridge stopped; no wait for Desktop's word beyond [STREAM_MS];
 *  4. [Steps.killGame]: every Wine process (the game, wineserver) killed, then the environment's own teardown - no Exit Game, no wait;
 *  5. [Steps.stopServices]: the game-running and indoor-maps foreground services, our notifications;
 *  6. [Steps.finishUi]: DS Forever on both screens closed and every task of ours finished and out of recents; once they are gone (at
 *     most [UI_MS], then [SETTLE_MS] for their stops' own writes) [Steps.endProcess] ends the process: the next start is a cold one.
 * [Steps.watchdog] ends the process [HARD_MS] after the press whatever a step does (a wedged main thread).
 *
 * NOT SAVED by an exit this fast: the game writes its own settings (Config.wtf, bindings, its chat / UI layout) and every OTHER add-on's
 * SavedVariables only on its own clean exit, so what changed in them this session can be lost. DS Forever's own add-on settings are
 * SvKeep's (written back before the next start), and the quest watch list comes back by itself (QuestWatchKeep: no "bye" was seen).
 *
 * Pure: the steps are passed in (the launcher's ExitNowHost; the tests' fakes). [Sequence.run] blocks: never on the main thread.
 */
object ExitNow {
    /** the Menu's press ([LauncherHooks.exitNow]'s why) */
    const val MENU = "menu"
    /** the game's closer (GameLife.closer): ours, not Android's */
    const val CLOSER = "Exit game from the menu"
    /** DS Forever's pending writes, at most */
    const val FLUSH_MS = 300L
    /** the stream's end: the send to Desktop, at most */
    const val STREAM_MS = 400L
    /** the game's kill: the environment's teardown on the main thread, waited for at most */
    const val KILL_MS = 400L
    /** a step that runs on the main thread (the services, the screens), waited for at most */
    const val MAIN_MS = 150L
    /** the activities' going, waited for at most */
    const val UI_MS = 400L
    /** after the last activity went: its stop's own writes */
    const val SETTLE_MS = 100L
    const val POLL_MS = 25L
    /** the process ends this long after the press, whatever */
    const val HARD_MS = 1_800L

    /** an exit runs (a second press does nothing; the indoor maps start nothing meanwhile) */
    @Volatile var underWay = false
        private set

    /** tests: a new process */
    internal fun reset() { underWay = false }

    private fun claim(): Boolean = synchronized(this) { if (underWay) false else { underWay = true; true } }

    class Steps(
        val watchdog: (ms: Long) -> Unit,
        val mark: () -> Unit,
        val flush: (boundMs: Long) -> Unit,
        val streaming: () -> Boolean,
        val endStream: (boundMs: Long) -> Unit,
        val killGame: (boundMs: Long) -> Unit,
        val stopServices: () -> Unit,
        val finishUi: () -> Unit,
        val uiGone: () -> Boolean,
        val endProcess: () -> Unit,
        val log: (String) -> Unit,
        val clock: () -> Long = System::currentTimeMillis,
        val sleep: (Long) -> Unit = { Thread.sleep(it) },
    )

    class Sequence(private val s: Steps) {
        fun run() {
            if (!claim()) { s.log("exit now: already under way"); return }
            val t0 = s.clock()
            fun step(name: String, f: () -> Unit) {
                try { f() } catch (t: Throwable) { s.log("exit now: $name failed (${t.javaClass.simpleName}: ${t.message}); going on") }
            }
            step("watchdog") { s.watchdog(HARD_MS) }
            s.log("exit now: DS Forever closes at once (no Exit Game, no countdown)")
            step("mark") { s.mark() }
            step("flush") { s.flush(FLUSH_MS) }
            val streaming = try { s.streaming() } catch (_: Throwable) { false }
            if (streaming) step("stream") { s.endStream(STREAM_MS) }
            step("game") { s.killGame(KILL_MS) }
            step("services") { s.stopServices() }
            step("ui") { s.finishUi() }
            val until = s.clock() + UI_MS
            var gone = false
            while (true) {
                gone = try { s.uiGone() } catch (_: Throwable) { false }
                if (gone || s.clock() >= until) break
                s.sleep(POLL_MS)
            }
            s.sleep(SETTLE_MS)
            s.log("exit now: ${if (gone) "every screen gone" else "screens still closing after ${UI_MS} ms"} (${s.clock() - t0} ms): the process ends")
            step("end") { s.endProcess() }
        }
    }
}
