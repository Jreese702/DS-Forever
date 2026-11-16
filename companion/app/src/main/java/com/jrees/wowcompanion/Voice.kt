package com.jrees.wowcompanion

import java.text.Normalizer

/**
 * VOICE TO TEXT, THE RULES (the user's list, 2026-11-16: voice to text in the message box and every search box, "A opens the keyboard,
 * X opens our own voice prompt", and it must be "intuitive and slick"; the design E:/.../design_voice.md, every decision on its
 * RECOMMENDED pick: speech becomes text on the Thor by default and online only after the player picks it once; Android's own
 * microphone question once, on the bottom screen, on the first X and never in a fight; X in Chat starts voice everywhere but inside
 * the open board; the words are shown first and go in only on A / Start; the game's sound is left alone; every box that takes
 * words, but number boxes, Mail's To, macros and sign-in boards).
 *
 * Pure Kotlin, tested off the device (VoiceTest): the setting, the glyph rule, the text's cleaning and where it goes, the words that
 * have settled, the error codes, and THE SHEET (the voice page of [ChatKeyboard]): its states and what each button does there.
 * [VoiceEngine] is Android's recognizer; the keyboard draws the sheet and does its acts.
 */
object Voice {

    /** Settings > General > Look > Voice typing (app-wide, like Language): This device (the default), Online, Off */
    enum class Mode(val key: String, val label: String) {
        DEVICE("device", "This device"), ONLINE("online", "Online"), OFF("off", "Off");
        companion object {
            fun of(key: String?): Mode = values().firstOrNull { it.key == key } ?: DEVICE
        }
    }

    /** the box's X glyph (and X itself) is voice's: Voice typing is not Off and the device has a microphone */
    fun showsMic(mode: Mode, hasMic: Boolean): Boolean = mode != Mode.OFF && hasMic

    /** the recognizer's language: Settings > Language's (the app's language in use) */
    fun language(lang: String): String = when (lang) { "es" -> "es-ES"; "fr" -> "fr-FR"; else -> "en-US" }

    // ---------------------------------------------------------------- the text

    /**
     * What a recognizer wrote, as chat can carry it: accents folded (BEFORE the cleaning, which would drop the letter), curly quotes and
     * dashes as ASCII, "|" dropped (chat13), runs of spaces as one, the ends trimmed.
     */
    fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '\u2018', '\u2019', '\u201A', '\u201B', '\u2032', '\u00B4', '\u02BC' -> sb.append('\'')
                '\u201C', '\u201D', '\u201E', '\u201F', '\u2033', '\u00AB', '\u00BB' -> sb.append('"')
                '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212' -> sb.append('-')
                '\u2026' -> sb.append("...")
                '\u00DF' -> sb.append("ss")
                '\u00E6' -> sb.append("ae"); '\u00C6' -> sb.append("AE")
                '\u0153' -> sb.append("oe"); '\u0152' -> sb.append("OE")
                '\u00F8' -> sb.append('o'); '\u00D8' -> sb.append('O')
                '\u0142' -> sb.append('l'); '\u0141' -> sb.append('L')
                '\u0111', '\u00F0' -> sb.append('d'); '\u0110', '\u00D0' -> sb.append('D')
                '\u00FE' -> sb.append("th"); '\u00DE' -> sb.append("Th")
                '\u00BF', '\u00A1', '|' -> {}                       // Spanish's opening marks, and the pipe chat cannot carry
                else -> when {
                    ch.isWhitespace() || ch == '\u00A0' -> sb.append(' ')
                    ch.code < 128 -> sb.append(ch)
                    else -> for (d in Normalizer.normalize(ch.toString(), Normalizer.Form.NFD))
                        if (Character.getType(d) != Character.NON_SPACING_MARK.toInt()) sb.append(d)
                }
            }
        }
        return sb.toString().replace(SPACES, " ").trim()
    }

    private val SPACES = Regex(" {2,}")

    /** [fold], then only printable ASCII but "|" (what ChatStream's cells type), no more than [max] */
    fun clean(s: String, max: Int): String =
        fold(s).filter { it.code in 32..126 && it != '|' }.replace(SPACES, " ").trim().take(maxOf(0, max)).trimEnd()

    /**
     * The box's text with [heard] in it: a MESSAGE box ([append]) adds the words after what it had (a space between), a SEARCH or NAME box
     * replaces it; nothing heard yet leaves the box as it was. Cut to the box's limit [max].
     */
    fun insert(before: String, heard: String, append: Boolean, max: Int): String {
        val h = clean(heard, max)
        if (h.isEmpty()) return before
        if (!append || before.isBlank()) return h
        val joined = if (before.endsWith(" ")) before + h else "$before $h"
        return joined.take(max)
    }

    /**
     * THE WORDS THAT HAVE SETTLED. Android's partial results are whole guesses that may change any word, and the API has no "these are
     * final" marker, so a word counts as settled when two partial results in a row agree on it (from the start), and never the newest
     * partial's last word (it may still be growing). The count only grows: the box is given more words at most once per new settled word,
     * so a run of guesses never piles delete-and-retype taps into the add-on's queue. The final result replaces it all.
     */
    class Stable {
        private var last: List<String> = emptyList()
        var words: List<String> = emptyList(); private set
        val text: String get() = words.joinToString(" ")

        fun reset() { last = emptyList(); words = emptyList() }

        /** a new partial result; true when more words have settled than before */
        fun partial(raw: String): Boolean {
            val now = split(raw)
            var agree = 0
            while (agree < now.size && agree < last.size && now[agree] == last[agree]) agree++
            last = now
            val upTo = minOf(agree, now.size - 1).coerceAtLeast(0)
            if (upTo > words.size) { words = now.take(upTo); return true }
            return false
        }

        private fun split(s: String): List<String> = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- the recognizer's errors

    /** what the sheet says when voice could not give words (its title) */
    enum class Problem(val title: String) {
        NOT_HEARD("Didn't hear you"), PACK("Voice pack needed"), OFFLINE("Offline"), BUSY("Voice busy"), NO_SOUND("No sound from the mic"),
        MIC_OFF("Microphone off"),
    }

    /** an error's answer: try once more without a word ([retry]), offer the online recognizer, or say [problem] ([get]: A = Get the pack) */
    data class Answer(val retry: Boolean = false, val problem: Problem? = null, val get: Boolean = false, val offerOnline: Boolean = false)

    // SpeechRecognizer's codes (android.speech.SpeechRecognizer.ERROR_*), here so the tests need no Android
    const val E_NETWORK_TIMEOUT = 1; const val E_NETWORK = 2; const val E_AUDIO = 3; const val E_SERVER = 4; const val E_CLIENT = 5
    const val E_SPEECH_TIMEOUT = 6; const val E_NO_MATCH = 7; const val E_BUSY = 8; const val E_PERMISSIONS = 9
    const val E_TOO_MANY = 10; const val E_DISCONNECTED = 11; const val E_LANG_UNSUPPORTED = 12; const val E_LANG_UNAVAILABLE = 13

    /** a "no match" this soon after the start is the recognizer's own hiccup, not the player's silence: tried again once */
    const val QUICK_NO_MATCH_MS = 1000L
    /** two timeouts in a row with the level flat: the microphone gives nothing */
    const val FLAT_TIMEOUTS = 2
    /** the level (onRmsChanged dB) the microphone must pass once for a session to count as "heard something" */
    const val FLAT_DB = 1.5f

    /**
     * The design's map: 13 (the language's pack not downloaded) = "Voice pack needed" with A = Get; 1 and 2 = Offline; 10 = Voice busy;
     * 5, 8, and a 7 within [QUICK_NO_MATCH_MS] of the start = one quiet retry; a level flat through [FLAT_TIMEOUTS] timeouts = "No sound
     * from the mic". Also: 9 = the microphone off; 12 on This device = the online offer; 3 = no sound; the rest = busy.
     */
    fun answer(code: Int, msSinceStart: Long, retried: Boolean, mode: Mode, flatTimeouts: Int): Answer = when (code) {
        E_LANG_UNAVAILABLE -> Answer(problem = Problem.PACK, get = mode == Mode.DEVICE)
        E_LANG_UNSUPPORTED -> if (mode == Mode.DEVICE) Answer(offerOnline = true) else Answer(problem = Problem.PACK)
        E_NETWORK_TIMEOUT, E_NETWORK -> Answer(problem = Problem.OFFLINE)
        E_TOO_MANY, E_SERVER, E_DISCONNECTED -> Answer(problem = Problem.BUSY)
        E_PERMISSIONS -> Answer(problem = Problem.MIC_OFF)
        E_AUDIO -> Answer(problem = Problem.NO_SOUND)
        E_CLIENT -> if (!retried) Answer(retry = true) else Answer(problem = Problem.NOT_HEARD)
        E_BUSY -> if (!retried) Answer(retry = true) else Answer(problem = Problem.BUSY)
        E_NO_MATCH, E_SPEECH_TIMEOUT -> when {
            code == E_NO_MATCH && msSinceStart < QUICK_NO_MATCH_MS && !retried -> Answer(retry = true)
            flatTimeouts >= FLAT_TIMEOUTS -> Answer(problem = Problem.NO_SOUND)
            else -> Answer(problem = Problem.NOT_HEARD)
        }
        else -> Answer(problem = Problem.BUSY)
    }

    // ---------------------------------------------------------------- the sheet

    /** the microphone permission as the sheet needs it */
    enum class Perm { GRANTED, ASKABLE, DENIED, BLOCKED }

    /** which recognizer can run: this device's, only the online one (This device has none), the online one (the player's pick), none */
    enum class Avail { DEVICE, ONLINE_ONLY, ONLINE, NONE }

    enum class State {
        /** the permission is not asked yet: "Voice needs the microphone", A = Allow (greyed "After combat" in a fight), B = Not now */
        ASK,
        /** the player said Don't allow (Android still asks): "Microphone off", A = Allow again */
        MIC_OFF,
        /** Android stopped asking: "Microphone off" and the Settings path; B only */
        BLOCKED,
        /** This device has no recognizer: "Use online voice", A = Online (the setting changes to Online, the player's pick) */
        OFFER_ONLINE,
        LISTENING,
        /** A while listening: the recognizer is finishing; the words heard so far are kept */
        STOPPING,
        /** the words are shown; the cursor on the box's finish button (Send / Search / Done), A / Start presses it */
        RESULT,
        PROBLEM,
    }

    sealed class Act {
        /** start the recognizer ([online]: the default recognizer, else this device's only) */
        data class Start(val online: Boolean) : Act()
        /** the recognizer finishes what it heard (A while listening) */
        object Stop : Act() { override fun toString() = "Stop" }
        /** the recognizer goes, whatever it was doing (nothing it says is used) */
        object Abort : Act() { override fun toString() = "Abort" }
        /** the box's text changes to [text] (the host's onText: an add-on field copies it) */
        data class Push(val text: String) : Act()
        /** the box's finish button: [text] goes in, then the box's own Send / Search / Done */
        data class Finish(val text: String) : Act()
        /** Y: the keyboard with [text] in place */
        data class Edit(val text: String) : Act()
        /** the sheet goes: back to the box (or the keys it was opened from); the box already has its earlier text again */
        object Close : Act() { override fun toString() = "Close" }
        /** Android's microphone question (VoiceAskActivity on the bottom screen) */
        object AskMic : Act() { override fun toString() = "AskMic" }
        /** the player picked online voice: the setting changes to Online */
        object UseOnline : Act() { override fun toString() = "UseOnline" }
        /** the on-device language pack's download (Android's own) */
        object GetPack : Act() { override fun toString() = "GetPack" }
        /** not the sheet's button (LB / RB: the room's, the chat type) */
        object Pass : Act() { override fun toString() = "Pass" }
        object Glow : Act() { override fun toString() = "Glow" }
    }

    /** A on the finish button works this long after the words settle, so a double press can't send words nobody has read */
    const val ACCEPT_MS = 400L
    /** A while listening: the recognizer's final words are waited for this long, then the words heard so far are used */
    const val STOP_WAIT_MS = 2500L

    /**
     * ONE VOICE SHEET over a box whose text was [before]. [append]: a message box (the words go after its text), else a search / name box
     * (they replace it). [max]: the box's limit. [mode]: the setting when it opened ([Mode.OFF] never opens one). [now]: the clock.
     */
    class Sheet(val before: String, val append: Boolean, val max: Int, var mode: Mode, private val now: () -> Long) {
        var state = State.LISTENING; private set
        var problem: Problem? = null; private set
        /** PACK: A = Get (the on-device pack can be downloaded) */
        var canGet = false; private set
        /** the words this sheet heard: the newest guess as a whole (cleaned), or the final words */
        var heard = ""; private set
        private val stable = Stable()
        /** the settled words (a prefix of what the recognizer guessed) */
        val stableText: String get() = stable.text
        /** the level 0..1 (smoothed), for the medallion's ripples */
        var level = 0f; private set
        private var startAt = 0L
        private var retried = false
        private var loudest = -100f
        private var flat = 0
        private var resultAt = 0L
        private var stopAt = 0L
        /** what the box was given last ([Act.Push]) */
        var pushed = before; private set

        /** the box's text now: [before] with what was heard (the settled words while listening) */
        fun boxText(): String = insert(before, if (state == State.LISTENING) stable.text else heard, append, max)

        /** the sheet opens: the permission and the recognizer decide where it starts */
        fun open(perm: Perm, avail: Avail): List<Act> = when {
            perm == Perm.ASKABLE -> { state = State.ASK; listOf(Act.Glow) }
            perm == Perm.DENIED -> { state = State.MIC_OFF; problem = Problem.MIC_OFF; listOf(Act.Glow) }
            perm == Perm.BLOCKED -> { state = State.BLOCKED; problem = Problem.MIC_OFF; listOf(Act.Glow) }
            avail == Avail.ONLINE_ONLY -> { state = State.OFFER_ONLINE; listOf(Act.Glow) }
            avail == Avail.NONE -> { state = State.PROBLEM; problem = Problem.BUSY; listOf(Act.Glow) }
            else -> listen(avail == Avail.ONLINE)
        }

        /** listening again: what this sheet heard goes (the box gets its earlier text back), the recognizer starts */
        fun listen(online: Boolean = mode == Mode.ONLINE): List<Act> {
            state = State.LISTENING; problem = null; canGet = false
            heard = ""; stable.reset(); level = 0f; loudest = -100f; retried = false
            startAt = now(); resultAt = 0L; stopAt = 0L
            return pushIf(before) + Act.Start(online)
        }

        private fun pushIf(t: String): List<Act> = if (t == pushed) emptyList() else { pushed = t; listOf(Act.Push(t)) }

        /** one button (the guest pad's names), [fight] = the game is in a fight now */
        fun press(key: String, fight: Boolean): List<Act> = when (state) {
            State.ASK, State.MIC_OFF -> when (key) {
                "PAD1" -> if (fight) listOf(Act.Glow) else listOf(Act.AskMic)
                "PAD2" -> cancel()
                "PADLSHOULDER", "PADRSHOULDER" -> listOf(Act.Pass)
                else -> listOf(Act.Glow)
            }
            State.BLOCKED -> when (key) {
                "PAD2" -> cancel()
                "PADLSHOULDER", "PADRSHOULDER" -> listOf(Act.Pass)
                else -> listOf(Act.Glow)
            }
            State.OFFER_ONLINE -> when (key) {
                "PAD1" -> { mode = Mode.ONLINE; listOf<Act>(Act.UseOnline) + listen(true) }
                "PAD2" -> cancel()
                "PADLSHOULDER", "PADRSHOULDER" -> listOf(Act.Pass)
                else -> listOf(Act.Glow)
            }
            State.LISTENING, State.STOPPING -> when (key) {
                "PAD1" -> if (state == State.LISTENING) { state = State.STOPPING; stopAt = now(); listOf(Act.Stop) } else listOf(Act.Glow)
                "PAD2" -> cancel()
                "PAD3" -> listOf<Act>(Act.Abort) + listen()
                "PADLSHOULDER", "PADRSHOULDER" -> listOf(Act.Pass)
                else -> listOf(Act.Glow)
            }
            State.RESULT -> when (key) {
                "PAD1", "PADFORWARD" -> if (now() - resultAt < ACCEPT_MS) listOf(Act.Glow) else listOf(Act.Finish(boxText()))
                "PAD4" -> listOf(Act.Edit(boxText()))
                "PAD3" -> listen()
                "PAD2" -> cancel()
                "PADLSHOULDER", "PADRSHOULDER" -> listOf(Act.Pass)
                else -> listOf(Act.Glow)
            }
            State.PROBLEM -> when (key) {
                "PAD1" -> when {
                    problem == Problem.PACK && canGet -> listOf(Act.GetPack)
                    problem == Problem.MIC_OFF -> if (fight) listOf(Act.Glow) else listOf(Act.AskMic)
                    else -> listen()
                }
                "PAD3" -> listen()
                "PAD4" -> listOf(Act.Edit(before))
                "PAD2" -> cancel()
                "PADLSHOULDER", "PADRSHOULDER" -> listOf(Act.Pass)
                else -> listOf(Act.Glow)
            }
        }

        /** B: the recognizer goes, the box gets its earlier text back, the sheet closes */
        fun cancel(): List<Act> {
            val wasLive = state == State.LISTENING || state == State.STOPPING
            heard = ""; stable.reset()
            return (if (wasLive) listOf<Act>(Act.Abort) else emptyList()) + pushIf(before) + Act.Close
        }

        /** the microphone question answered: [perm] now, [avail] the recognizer */
        fun permAnswered(perm: Perm, avail: Avail): List<Act> {
            if (state != State.ASK && state != State.MIC_OFF && !(state == State.PROBLEM && problem == Problem.MIC_OFF)) return emptyList()
            problem = null
            return open(perm, avail)
        }

        // ---- the recognizer's events (stale ones are the engine's to drop)

        fun rms(db: Float) {
            if (state != State.LISTENING && state != State.STOPPING) return
            if (db > loudest) loudest = db
            val k = ((db + 2f) / 12f).coerceIn(0f, 1f)
            level = if (k > level) level * 0.4f + k * 0.6f else level * 0.85f + k * 0.15f
        }

        /** a partial guess: the words heard show at once; the box is given the settled ones, at most once per new settled word */
        fun partial(text: String): List<Act> {
            if (state != State.LISTENING && state != State.STOPPING) return emptyList()
            heard = clean(text, max)
            if (state != State.LISTENING || !stable.partial(heard)) return listOf(Act.Glow)
            return pushIf(insert(before, stable.text, append, max)).ifEmpty { listOf(Act.Glow) }
        }

        /** the final words: the result, or "Didn't hear you" when there are none */
        fun final(text: String): List<Act> {
            if (state != State.LISTENING && state != State.STOPPING) return emptyList()
            val t = clean(text, max).ifEmpty { if (state == State.STOPPING) heard else "" }
            if (t.isEmpty()) return showProblem(timeoutAnswer())
            heard = t; state = State.RESULT; resultAt = now(); flat = 0
            return pushIf(insert(before, heard, append, max)).ifEmpty { listOf(Act.Glow) }
        }

        private fun timeoutAnswer(): Answer {
            if (loudest < FLAT_DB) flat++ else flat = 0
            return if (flat >= FLAT_TIMEOUTS) Answer(problem = Problem.NO_SOUND) else Answer(problem = Problem.NOT_HEARD)
        }

        /** the recognizer's error [code] */
        fun error(code: Int): List<Act> {
            if (state != State.LISTENING && state != State.STOPPING) return emptyList()
            // A pressed and the recognizer then says it matched nothing: the words heard so far are the result
            if (state == State.STOPPING && heard.isNotEmpty()) return final(heard)
            val timeout = code == E_SPEECH_TIMEOUT || code == E_NO_MATCH
            val quick = now() - startAt < QUICK_NO_MATCH_MS
            if (timeout && !(code == E_NO_MATCH && quick && !retried)) {
                if (loudest < FLAT_DB) flat++ else flat = 0
            }
            val a = answer(code, now() - startAt, retried, mode, flat)
            if (a.retry) {
                retried = true; startAt = now()
                return listOf(Act.Start(mode == Mode.ONLINE))
            }
            if (a.offerOnline) { state = State.OFFER_ONLINE; return pushIf(before) + Act.Glow }
            return showProblem(a)
        }

        private fun showProblem(a: Answer): List<Act> {
            state = State.PROBLEM; problem = a.problem ?: Problem.NOT_HEARD; canGet = a.get
            heard = ""; stable.reset()
            return pushIf(before).ifEmpty { listOf(Act.Glow) }
        }

        /** the clock: A while listening whose final words never came uses the words heard so far */
        fun tick(): List<Act> {
            if (state == State.STOPPING && now() - stopAt > STOP_WAIT_MS) return listOf<Act>(Act.Abort) + final(heard)
            return emptyList()
        }

        /** the finish button can be pressed now (the words have settled [ACCEPT_MS] ago) */
        fun canAccept(): Boolean = state == State.RESULT && now() - resultAt >= ACCEPT_MS

        /** the diagnostics' line: the mode, the state, the time since the start and the count of characters; NEVER the words */
        fun logLine(what: String): String =
            "$what mode=${mode.key} state=${state.name.lowercase()}" + (problem?.let { " problem=${it.name.lowercase()}" } ?: "") +
                " ms=${if (startAt == 0L) 0 else now() - startAt} chars=${heard.length}"
    }

    // ---------------------------------------------------------------- the hooks the host sets (the keyboard asks them)

    /** the game is in a fight now (Home's fight view): the microphone question waits */
    @Volatile var inFight: () -> Boolean = { false }
    /** names the on-device recognizer is told to expect (party, guild, friends, zone, bag items); never logged */
    @Volatile var hints: () -> List<String> = { emptyList() }
    /** Android's microphone question on the bottom screen (the host's openOnBottom: its window hidden until the question closes);
     *  null = none hosted (before Play): the keyboard starts the question on its own display */
    @Volatile var askMic: (() -> Unit)? = null
    /** every open sheet stops (the host went away, the bottom screen hid) */
    private val live = java.util.Collections.newSetFromMap(java.util.WeakHashMap<() -> Unit, Boolean>())
    fun register(stop: () -> Unit) { synchronized(live) { live.add(stop) } }
    fun unregister(stop: () -> Unit) { synchronized(live) { live.remove(stop) } }
    fun stopAll() { val all = synchronized(live) { live.toList() }; for (s in all) runCatching { s() } }

    /** the most names sent as hints */
    const val MAX_HINTS = 60
}
