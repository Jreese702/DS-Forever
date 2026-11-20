package com.jrees.wowcompanion

import java.text.Normalizer

/**
 * VOICE TO TEXT, THE RULES (the user's list, 2026-11-16: voice to text in the message box and every search box, "A opens the keyboard,
 * X opens our own voice prompt", and it must be "intuitive and slick"; the design review of 2026-11-16 (design_voice.md), every decision on its
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
     * dashes as ASCII, "|" dropped (chat13), runs of spaces as one, the ends trimmed. #477: a letter of ANOTHER SCRIPT (Cyrillic, Greek,
     * Arabic, CJK ...: [keepsScript]) is kept as it is (never decomposed: "й" stays "й"), and chat types it as UTF-8 (ChatStream's
     * byte cells, Chat.lua chat43). The Latin letters fold to ASCII as before.
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
                    keepsScript(ch) -> sb.append(ch)
                    else -> for (d in Normalizer.normalize(ch.toString(), Normalizer.Form.NFD))
                        if (Character.getType(d) != Character.NON_SPACING_MARK.toInt()) sb.append(d)
                }
            }
        }
        return sb.toString().replace(SPACES, " ").trim()
    }

    private val SPACES = Regex(" {2,}")

    /**
     * #477: a character of a script that is not Latin, kept as it is ([fold]) and typed as UTF-8: a letter, digit or mark of its own
     * script, or a CJK / full-width sign (the "ー" of Japanese is a Common-script letter), never a surrogate (an emoji: 4 UTF-8 bytes,
     * whisper_jni drops them too), a control, format, private-use or unassigned one
     */
    fun keepsScript(ch: Char): Boolean {
        if (ch.code < 128 || Character.isSurrogate(ch)) return false
        when (Character.getType(ch).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.UNASSIGNED, Character.SURROGATE,
            Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> return false
        }
        return when (runCatching { Character.UnicodeScript.of(ch.code) }.getOrNull()) {
            null, Character.UnicodeScript.LATIN, Character.UnicodeScript.INHERITED, Character.UnicodeScript.UNKNOWN -> false
            Character.UnicodeScript.COMMON -> Character.isLetterOrDigit(ch) || Character.UnicodeBlock.of(ch).let {
                it == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION || it == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
                    it == Character.UnicodeBlock.KATAKANA || it == Character.UnicodeBlock.HIRAGANA
            }
            else -> true
        }
    }

    /** a character chat can type: printable ASCII but "|" (ChatStream's letter cells), or another script's ([keepsScript], as UTF-8) */
    fun chatChar(ch: Char): Boolean = (ch.code in 32..126 && ch != '|') || keepsScript(ch)

    /** [fold], then only what chat can type ([chatChar]), no more than [max] */
    fun clean(s: String, max: Int): String =
        fold(s).filter { chatChar(it) }.replace(SPACES, " ").trim().take(maxOf(0, max)).trimEnd()

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
        /** no recognizer this app trusts (no on-device one, no Google / system speech service): X says so and opens nothing else */
        NO_SERVICE("Voice typing needs Android speech services"),
        /** nothing can listen but offline voice, whose model is not downloaded yet: the four models to pick, A = the highlighted one's download */
        NEED_MODEL("Download voice"),
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

    // ---------------------------------------------------------------- which recognizer (never the device's DEFAULT one)

    /**
     * One speech service Android lists (queryIntentServices for android.speech.RecognitionService): its [pkg], its class [cls], and
     * [system] = it came with the device (ApplicationInfo.FLAG_SYSTEM / FLAG_UPDATED_SYSTEM_APP).
     */
    data class Service(val pkg: String, val cls: String, val system: Boolean)

    /**
     * The speech services this app binds by name, best first. NEVER the device's default recognizer (Settings.Secure
     * voice_recognition_service): on the user's Thor that is MacroDroid's (2026-11-16: "it just flashs and instantly stops and says
     * something about macro droid"), and most players have no such app.
     */
    val TRUSTED = listOf(
        "com.google.android.googlequicksearchbox", "com.google.android.tts", "com.google.android.as", "com.google.android.apps.speechservices",
    )

    /** the service to bind: a known Google one first (in [TRUSTED]'s order), else one that came with the device; a third-party app never */
    fun trusted(services: List<Service>): Service? {
        for (p in TRUSTED) services.firstOrNull { it.pkg == p }?.let { return it }
        return services.firstOrNull { it.system }
    }

    /**
     * OFFLINE VOICE (whisper.cpp, [WhisperNative]; the user, 2026-11-16: "base with optional downloads per language"): [lib] = this build
     * has libdsfwhisper.so, [en] / [multi] = that model file is on disk and checked ([VoiceModels.ready]); [enSmall] / [multiSmall] = the
     * quantised one of that kind (the user's pick 2026-11-16 "Offer both")
     */
    data class WhisperOn(val lib: Boolean, val en: Boolean, val multi: Boolean, val enSmall: Boolean = false, val multiSmall: Boolean = false)

    /**
     * Which model and language offline voice uses for the app's language [appLang] ("en", "es", "fr") and the Voice language pick [voice]
     * ([VoiceLanguage], issue #477; default "Same as the app"):
     *  - SAME AS THE APP (as before): a language that is not English takes the multilingual file when it is there; else the English one
     *    ("en"); else the multilingual one in the app's language.
     *  - A PICKED LANGUAGE or AUTO-DETECT: English takes the English file, else the multilingual one; anything else (Russian, "auto")
     *    ONLY the multilingual one - an English-only ".en" model cannot write Russian, so none = null ([needsMulti]: the sheet offers
     *    the "Other languages" download instead, with its size; nothing downloads by itself).
     * Of one kind the bigger file listens when both are in, else whichever is there. Null = no model that can listen (or no library).
     */
    fun whisperModel(appLang: String, w: WhisperOn?, voice: String = VoiceLanguage.SAME): Pair<VoiceModels.Model, String>? {
        if (w == null || !w.lib) return null
        val lang = VoiceLanguage.resolve(voice, appLang)
        val en = if (w.en) VoiceModels.Model.EN else if (w.enSmall) VoiceModels.Model.EN_SMALL else null
        val multi = if (w.multi) VoiceModels.Model.MULTI else if (w.multiSmall) VoiceModels.Model.MULTI_SMALL else null
        if (VoiceLanguage.sanitize(voice) != VoiceLanguage.SAME) return when {
            lang == "en" -> (en ?: multi)?.let { it to "en" }
            else -> multi?.let { it to lang }
        }
        return when {
            lang != "en" && multi != null -> multi to lang
            en != null -> en to "en"
            multi != null -> multi to lang
            else -> null
        }
    }

    /**
     * #477: the Voice language pick wants a multilingual model and only an English one is on disk (the library in): the voice sheet
     * offers the "Other languages" download (its size on the row), never the English fallback, never a download by itself
     */
    fun needsMulti(appLang: String, w: WhisperOn?, voice: String = VoiceLanguage.SAME): Boolean =
        w != null && w.lib && VoiceLanguage.sanitize(voice) != VoiceLanguage.SAME && VoiceLanguage.needsMulti(voice, appLang) &&
            !w.multi && !w.multiSmall && (w.en || w.enSmall)

    /** the model the voice sheet offers to download for [appLang] and [voice]: the small one, multilingual for a language that is not English */
    fun wantedModel(appLang: String, voice: String = VoiceLanguage.SAME): VoiceModels.Model =
        if (VoiceLanguage.needsMulti(voice, appLang)) VoiceModels.Model.MULTI_SMALL else VoiceModels.Model.EN_SMALL

    /** what listens */
    sealed class Pick {
        /** offline voice: whisper.cpp with [model] in [lang] */
        data class Whisper(val model: VoiceModels.Model, val lang: String) : Pick()
        /** SpeechRecognizer.createOnDeviceSpeechRecognizer (Android 12+) */
        object OnDevice : Pick() { override fun toString() = "OnDevice" }
        /** SpeechRecognizer.createSpeechRecognizer(context, that service's ComponentName) */
        data class Named(val service: Service) : Pick()
        /** no voice: "Voice typing needs Android speech services" */
        object None : Pick() { override fun toString() = "None" }
    }

    /** the on-device recognizer is usable: Android 12+ (API 31) and Android says it is there */
    fun onDevice(api: Int, available: Boolean): Boolean = api >= 31 && available

    /**
     * THE ORDER: offline voice when its model is on disk ([whisperModel]), else the on-device recognizer, else a trusted named service
     * ([trusted]), else none (then the sheet offers the model's download when the library is in: [avail]'s NEED_MODEL)
     */
    fun pick(api: Int, onDeviceAvailable: Boolean, services: List<Service>, whisper: WhisperOn? = null, appLang: String = "en",
             voice: String = VoiceLanguage.SAME): Pick {
        whisperModel(appLang, whisper, voice)?.let { (m, l) -> return Pick.Whisper(m, l) }
        return when {
            onDevice(api, onDeviceAvailable) -> Pick.OnDevice
            else -> trusted(services)?.let { Pick.Named(it) } ?: Pick.None
        }
    }

    /**
     * What one listening uses: [online] (the player picked Online) = the trusted service, else the on-device one; not [online] = the
     * on-device one only (nothing leaves the Thor until the player picks Online once).
     */
    fun recognizer(online: Boolean, api: Int, onDeviceAvailable: Boolean, services: List<Service>, whisper: WhisperOn? = null,
                   appLang: String = "en", voice: String = VoiceLanguage.SAME): Pick {
        // offline voice first, whichever the setting (nothing leaves the Thor)
        whisperModel(appLang, whisper, voice)?.let { (m, l) -> return Pick.Whisper(m, l) }
        return when {
            online -> trusted(services)?.let { Pick.Named(it) } ?: if (onDevice(api, onDeviceAvailable)) Pick.OnDevice else Pick.None
            onDevice(api, onDeviceAvailable) -> Pick.OnDevice
            else -> Pick.None
        }
    }

    /** which recognizer the sheet can use with the setting [mode] ([pick]'s order; Online = the player's pick of the named service) */
    fun avail(mode: Mode, api: Int, onDeviceAvailable: Boolean, services: List<Service>, whisper: WhisperOn? = null,
              appLang: String = "en", voice: String = VoiceLanguage.SAME): Avail {
        if (mode == Mode.OFF) return Avail.NONE
        if (whisperModel(appLang, whisper, voice) != null) return Avail.WHISPER
        // #477: a picked language only the multilingual model writes, and only English on disk: offer that download (never English)
        if (needsMulti(appLang, whisper, voice)) return Avail.NEED_MODEL
        // nothing else can listen: the model's download is offered (a build with the library), else the old line
        val none = if (whisper?.lib == true) Avail.NEED_MODEL else Avail.NONE
        return when (mode) {
            Mode.ONLINE -> when {
                trusted(services) != null -> Avail.ONLINE
                onDevice(api, onDeviceAvailable) -> Avail.DEVICE
                else -> none
            }
            else -> when (pick(api, onDeviceAvailable, services)) {
                Pick.OnDevice -> Avail.DEVICE
                is Pick.Named -> Avail.ONLINE_ONLY
                else -> none
            }
        }
    }

    // ---------------------------------------------------------------- offline voice's pieces (WhisperEngine's rules)

    /**
     * What Whisper wrote, as the box can take it: its non-speech tags ("[BLANK_AUDIO]", "[Music]", "(laughs)", "*coughs*") dropped,
     * then [clean]. Only tags: words in brackets are rare in chat, and Whisper writes these for silence and noise.
     */
    fun whisperClean(s: String, max: Int): String = clean(dropRepeat(s.replace(WHISPER_TAG, " ")), max)

    /**
     * ptt-speed: the words said twice over ("Let's go kill the boss in the cave. Let's go kill the boss in the cave.") are said once.
     * Whisper's known slip with a short encoder window (whisper_jni.cpp's audioCtx; the PC bench saw it with a 256 window, the shipped
     * margin and floor did not): only an exact repeat of the whole line (3+ words each time, case and punctuation aside) is cut, never a part.
     */
    fun dropRepeat(s: String): String {
        val w = s.trim().split(' ', '\t', '\n').filter { it.isNotEmpty() }
        if (w.size < 6 || w.size % 2 != 0) return s
        fun norm(l: List<String>) = l.joinToString(" ") { it.lowercase().filter { c -> c.isLetterOrDigit() || c == '\'' } }
        val h = w.size / 2
        return if (norm(w.subList(0, h)) == norm(w.subList(h, w.size))) w.subList(0, h).joinToString(" ") else s
    }

    private val WHISPER_TAG = Regex("\\[[^\\]]*]|\\([^)]*\\)|\\*[^*]*\\*")

    /** a frame's level in dBFS ([dbfs], about -90 silence .. 0 full scale) on the sheet's -2..10 scale (onRmsChanged's) */
    fun rmsDb(dbfs: Float): Float = ((dbfs + 60f) * 12f / 50f - 2f).coerceIn(-2f, 10f)

    /** dBFS of [n] PCM16 samples of [pcm] from [off] (-90 = silence) */
    fun dbfs(pcm: ShortArray, off: Int, n: Int): Float {
        if (n <= 0) return -90f
        var sum = 0.0
        for (i in off until off + n) { val v = pcm[i] / 32768.0; sum += v * v }
        val rms = Math.sqrt(sum / n)
        return if (rms <= 1e-6) -90f else (20.0 * Math.log10(rms)).toFloat().coerceAtLeast(-90f)
    }

    /**
     * OFFLINE VOICE'S STOP RULE (a plain energy VAD over [FRAME_MS] frames): the first [FLOOR_MS] set the room's noise floor; speech
     * has started once a frame is [SPEECH_DB] above it (and above [MIN_SPEECH_DBFS]); the recording stops after [SILENCE_MS] of quiet
     * once speech started, or at [MAX_MS] in any case. No speech within [NO_SPEECH_MS] = "Didn't hear you" ([E_SPEECH_TIMEOUT]).
     */
    class Vad {
        enum class Step { GO, STOP, NO_SPEECH }

        private var ms = 0
        private var floorSum = 0.0
        private var floorN = 0
        private var quietMs = 0
        /** the room's noise floor (dBFS), set over the first [FLOOR_MS] */
        var floor = -90f; private set
        /** speech has started */
        var speech = false; private set

        /** one frame of [FRAME_MS] ms at [dbfs]: go on, stop (the words are worked out), or nothing was said */
        fun frame(dbfs: Float): Step {
            ms += FRAME_MS
            if (ms <= FLOOR_MS) {
                floorSum += dbfs; floorN++
                floor = (floorSum / floorN).toFloat()
                return Step.GO
            }
            val loud = dbfs >= floor + SPEECH_DB && dbfs > MIN_SPEECH_DBFS
            if (loud) { speech = true; quietMs = 0 } else if (speech) quietMs += FRAME_MS
            return when {
                ms >= MAX_MS -> if (speech) Step.STOP else Step.NO_SPEECH
                speech && quietMs >= SILENCE_MS -> Step.STOP
                !speech && ms >= NO_SPEECH_MS -> Step.NO_SPEECH
                else -> Step.GO
            }
        }
    }

    const val FRAME_MS = 30
    const val FLOOR_MS = 300
    const val SPEECH_DB = 10f
    /** a frame quieter than this is never speech, whatever the floor (a dead-silent room's -90 dB) */
    const val MIN_SPEECH_DBFS = -55f
    const val SILENCE_MS = 1200
    const val MAX_MS = 15_000
    const val NO_SPEECH_MS = 5_000

    // ---------------------------------------------------------------- the sheet

    /** the microphone permission as the sheet needs it */
    enum class Perm { GRANTED, ASKABLE, DENIED, BLOCKED }

    /** which recognizer can run: this device's, only the online one (This device has none), the online one (the player's pick), none */
    enum class Avail {
        DEVICE, ONLINE_ONLY, ONLINE, NONE,
        /** offline voice (its model on disk) */
        WHISPER,
        /** nothing can listen until offline voice's model is downloaded (the sheet offers it) */
        NEED_MODEL,
    }

    enum class State {
        /** the permission is not asked yet: "Voice needs the microphone", A = Allow (greyed "After the fight" in a fight), B = Not now */
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
        /** offline voice: the recording stopped (A, or the quiet after the words), the words are being worked out ("Working") */
        WORKING,
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
        /** offline voice's model download (VoiceModels, the player's press): the [model] highlighted on the sheet's list */
        data class GetModel(val model: VoiceModels.Model) : Act()
        /** not the sheet's button (LB / RB: the room's, the chat type) */
        object Pass : Act() { override fun toString() = "Pass" }
        object Glow : Act() { override fun toString() = "Glow" }
    }

    /** A on the finish button works this long after the words settle, so a double press can't send words nobody has read */
    const val ACCEPT_MS = 400L
    /** A while listening: the recognizer's final words are waited for this long, then the words heard so far are used */
    const val STOP_WAIT_MS = 2500L
    /** offline voice working out the words: this long at most, then "Voice busy" */
    const val WORK_WAIT_MS = 20_000L

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
        private var workAt = 0L
        /** offline voice listens (the sheet opened on [Avail.WHISPER]): A and the quiet go to [State.WORKING]; no partial words */
        var offline = false; private set
        /**
         * NEED_MODEL'S LIST (the user, 2026-11-16: "The voice menu should have all of the options not just the 60 mb"): every model, in
         * Add-ons' order ([VoiceModels.Model]); [model] = the highlighted one (the host starts it on [wantedModel]); D-pad up / down moves
         */
        var model: VoiceModels.Model = VoiceModels.Model.EN_SMALL
        /** the models downloading or being checked now (the host says so: A on one of them waits, its row shows its percentage) */
        var busy: Set<VoiceModels.Model> = emptySet()
        /** the highlighted model is downloading now */
        val downloading: Boolean get() = model in busy
        /** what the box was given last ([Act.Push]) */
        var pushed = before; private set
        /** the sheet is over (B, the finish button, Edit): the recognizer's late words change nothing */
        var closed = false; private set

        /** the box's text now: [before] with what was heard (the settled words while listening) */
        fun boxText(): String = insert(before, if (state == State.LISTENING) stable.text else heard, append, max)

        /** the sheet opens: the permission and the recognizer decide where it starts */
        fun open(perm: Perm, avail: Avail): List<Act> = when {
            perm == Perm.ASKABLE -> { state = State.ASK; listOf(Act.Glow) }
            perm == Perm.DENIED -> { state = State.MIC_OFF; problem = Problem.MIC_OFF; listOf(Act.Glow) }
            perm == Perm.BLOCKED -> { state = State.BLOCKED; problem = Problem.MIC_OFF; listOf(Act.Glow) }
            avail == Avail.ONLINE_ONLY -> { state = State.OFFER_ONLINE; listOf(Act.Glow) }
            avail == Avail.NONE -> { state = State.PROBLEM; problem = Problem.NO_SERVICE; offline = false; listOf(Act.Glow) }
            avail == Avail.NEED_MODEL -> { state = State.PROBLEM; problem = Problem.NEED_MODEL; offline = false; listOf(Act.Glow) }
            else -> { offline = avail == Avail.WHISPER; listen(avail == Avail.ONLINE) }
        }

        /** listening again: what this sheet heard goes (the box gets its earlier text back), the recognizer starts */
        fun listen(online: Boolean = mode == Mode.ONLINE): List<Act> {
            state = State.LISTENING; problem = null; canGet = false
            heard = ""; stable.reset(); level = 0f; loudest = -100f; retried = false
            startAt = now(); resultAt = 0L; stopAt = 0L; workAt = 0L
            return pushIf(before) + Act.Start(online)
        }

        private fun pushIf(t: String): List<Act> = if (t == pushed) emptyList() else { pushed = t; listOf(Act.Push(t)) }

        /** one button (the guest pad's names), [fight] = the game is in a fight now */
        fun press(key: String, fight: Boolean): List<Act> = if (closed) emptyList() else pressOpen(key, fight).also { acts ->
            if (acts.any { it is Act.Finish || it is Act.Edit || it == Act.Close }) closed = true
        }

        private fun pressOpen(key: String, fight: Boolean): List<Act> = when (state) {
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
            State.LISTENING, State.STOPPING, State.WORKING -> when (key) {
                "PAD1" -> when {
                    state != State.LISTENING -> listOf(Act.Glow)
                    offline -> { working(); listOf(Act.Stop) }
                    else -> { state = State.STOPPING; stopAt = now(); listOf(Act.Stop) }
                }
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
            State.PROBLEM -> if (problem == Problem.NEED_MODEL) when (key) {
                // up / down = the list's cursor, A = the highlighted model's download (once); Y = the keyboard, B = back to the box
                "PADDUP", "PADDDOWN" -> { moveModel(if (key == "PADDUP") -1 else 1); listOf(Act.Glow) }
                "PAD1" -> if (downloading) listOf(Act.Glow) else listOf(Act.GetModel(model))
                "PAD4" -> listOf(Act.Edit(before))
                "PAD2" -> cancel()
                "PADLSHOULDER", "PADRSHOULDER" -> listOf(Act.Pass)
                else -> listOf(Act.Glow)
            } else if (problem == Problem.NO_SERVICE) when (key) {
                // nothing can listen: no Say again (it would only fail again); Y = the keyboard, B = back to the box
                "PAD4" -> listOf(Act.Edit(before))
                "PAD2" -> cancel()
                "PADLSHOULDER", "PADRSHOULDER" -> listOf(Act.Pass)
                else -> listOf(Act.Glow)
            } else when (key) {
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

        /** NEED_MODEL's cursor [by] rows, held at the list's ends */
        private fun moveModel(by: Int) {
            val all = VoiceModels.Model.values()
            model = all[(all.indexOf(model) + by).coerceIn(0, all.size - 1)]
        }

        /** B: the recognizer goes, the box gets its earlier text back, the sheet closes */
        fun cancel(): List<Act> {
            if (closed) return emptyList()
            closed = true
            val wasLive = state == State.LISTENING || state == State.STOPPING || state == State.WORKING
            heard = ""; stable.reset()
            return (if (wasLive) listOf<Act>(Act.Abort) else emptyList()) + pushIf(before) + Act.Close
        }

        /** the microphone question answered: [perm] now, [avail] the recognizer */
        fun permAnswered(perm: Perm, avail: Avail): List<Act> {
            if (closed) return emptyList()
            if (state != State.ASK && state != State.MIC_OFF && !(state == State.PROBLEM && problem == Problem.MIC_OFF)) return emptyList()
            problem = null
            return open(perm, avail)
        }

        /** offline voice's model came (its download finished): a sheet still offering it listens now ([open] with the new [avail]) */
        fun modelReady(perm: Perm, avail: Avail): List<Act> {
            if (closed || state != State.PROBLEM || problem != Problem.NEED_MODEL) return emptyList()
            busy = emptySet()
            problem = null
            return open(perm, avail)
        }

        /** offline voice stopped recording (A, or its quiet rule): the words are being worked out */
        fun working() {
            if (closed || state != State.LISTENING || !offline) return
            state = State.WORKING; workAt = now()
        }

        // ---- the recognizer's events (stale ones are the engine's to drop)

        fun rms(db: Float) {
            if (closed || (state != State.LISTENING && state != State.STOPPING)) return
            if (db > loudest) loudest = db
            val k = ((db + 2f) / 12f).coerceIn(0f, 1f)
            level = if (k > level) level * 0.4f + k * 0.6f else level * 0.85f + k * 0.15f
        }

        /** a partial guess: the words heard show at once; the box is given the settled ones, at most once per new settled word */
        fun partial(text: String): List<Act> {
            if (closed || (state != State.LISTENING && state != State.STOPPING)) return emptyList()
            heard = clean(text, max)
            if (state != State.LISTENING || !stable.partial(heard)) return listOf(Act.Glow)
            return pushIf(insert(before, stable.text, append, max)).ifEmpty { listOf(Act.Glow) }
        }

        /** the final words: the result, or "Didn't hear you" when there are none */
        fun final(text: String): List<Act> {
            if (closed || (state != State.LISTENING && state != State.STOPPING && state != State.WORKING)) return emptyList()
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
            if (closed || (state != State.LISTENING && state != State.STOPPING && state != State.WORKING)) return emptyList()
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
            if (closed) return emptyList()
            if (state == State.STOPPING && now() - stopAt > STOP_WAIT_MS) return listOf<Act>(Act.Abort) + final(heard)
            if (state == State.WORKING && now() - workAt > WORK_WAIT_MS) return listOf<Act>(Act.Abort) + showProblem(Answer(problem = Problem.BUSY))
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
    /** Android's microphone question on the bottom screen (the host's openOnBottom: its window hidden until the question closes; true =
     *  it is up there, the window hidden); null = none hosted (before Play): the keyboard starts the question on its own display */
    @Volatile var askMic: (() -> Boolean)? = null
    /** every open sheet stops (the host went away, the bottom screen hid) */
    private val live = java.util.Collections.newSetFromMap(java.util.WeakHashMap<() -> Unit, Boolean>())
    fun register(stop: () -> Unit) { synchronized(live) { live.add(stop) } }
    fun unregister(stop: () -> Unit) { synchronized(live) { live.remove(stop) } }
    fun stopAll() {
        val all = synchronized(live) { live.toList() }
        for (s in all) runCatching { s() }
        runCatching { releaseOffline() }
    }
    /** offline voice's loaded model goes (WhisperEngine sets it once a model is loaded): the host went away */
    @Volatile var releaseOffline: () -> Unit = {}

    /** the most names sent as hints */
    const val MAX_HINTS = 60
}
