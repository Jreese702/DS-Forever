package com.jrees.wowcompanion

/**
 * HOLD TO TALK ON THE DS TOOLBAR'S CHAT (the user, 2026-11-16: "Add an option to the text chat ds toolbar option for a quick chat option
 * for holding that button for quick voice to text to last used text channel (usage and chat indicated by a mic symbol on the toolbar and
 * the color of the chat is the mic"; picks: the switch "A toggle, off by default"; on release "Show, then A sends" (the default) or "Send
 * straight away"). Settings > Bottom screen > Home: "Hold to talk" under "DS WoW toolbar", "On release" under it (per character).
 *
 * The press is read from the APP'S OWN controller reader (DirectPad, ~20 ms), the router's "chat" word comes ~0.1-0.2 s later:
 *  - a TAP (Left up before [HOLD_MS]) keeps today's action: the Chat room opens. A word that came while Left was still down opens it at
 *    the release (D1: at most [HOLD_MS] after the press); a word after the release opens it as today;
 *  - a HOLD (still down at [HOLD_MS]) records with offline Whisper; any word while it lasts, or within [SWALLOW_MS] of its release, is
 *    swallowed (the Chat room does not open). The game never sees the hold: the router took the press and the Hub held the chat guest in
 *    the same click (hub7), so the capture keeps every button from the game while this holds;
 *  - the release stops the recording; the words come back ([heard]): "Show, then A sends" shows them on a line (A sends, B or any other
 *    button drops it, [LINE_MS] unsent = gone); "Send straight away" sends at once. Every send is the existing chat path (ChatView's
 *    stream, the add-on's rules), on the player's own press or release.
 *
 * PUSH TO TALK (HOLD) ON ANY BUTTON (the user's pick, 2026-11-17: the bindable DS command "Push to talk (hold)", PadMap m170 / Hub hub58):
 * the router runs the command on the press and the Hub holds the chat guest in that click; the host starts the hold at the command's word
 * with the button the app's own reader saw go down ([pttSource]: its press time, so [HOLD_MS] counts from the press), [ptt] = true. It
 * works with the toolbar's switch off (binding it is the opt-in), and "On release" is the same per-character setting. A TAP DOES
 * NOTHING (the least surprising for a talk button: no room opens; the chat guest is given back). The release of THAT button ([src])
 * stops the recording.
 *
 * Pure: the host feeds it the clock and the events and does what each answer says.
 */
class HoldTalk {
    enum class St { IDLE, PENDING, HELD, WORKING, LINE, SENDING, NOTE }

    /** what the host does after an event */
    enum class Out { NONE, OPEN_CHAT, LISTEN, STOP, CANCEL, SHOW, SEND, DROP, NOTE, TAB_L, TAB_R, FREE }

    var st = St.IDLE; private set
    /** the words heard (the line's text, the send's body) */
    var words = ""; private set
    /** the note line's words while [St.NOTE] (a reason, or the words just sent with [noteSent]) */
    var note = ""; private set
    var noteSent = false; private set
    /** "Send straight away" for this hold (the setting when it started) */
    var sendNow = false; private set
    /** the line / note's start (for its fade) */
    var shownAt = 0L; private set

    /** the button's press (the log says how long a tap's Chat room waited for its release: D1) */
    var downAt = 0L; private set
    /** the held button (the app reader's name): D-pad Left for the toolbar's Chat, the bound button for push to talk */
    var src = LEFT_KEY; private set
    /** this hold is the bound "Push to talk (hold)" command's (a tap does nothing), not the toolbar's Chat (a tap opens the Chat room) */
    var ptt = false; private set
    private var leftDown = false
    private var held = false
    private var wordSeen = false
    private var swallowUntil = 0L

    /** the hold keeps the chat guest (the Hub's same-click hold must not be freed as "its room is not on screen") */
    val wantsGuest: Boolean get() = st == St.PENDING || st == St.HELD || st == St.WORKING || st == St.LINE || st == St.SENDING
    /** the hold takes the controller's presses (A / B on the line, Left's release) */
    val takesPad: Boolean get() = wantsGuest
    /** recording now */
    val listening: Boolean get() = st == St.HELD

    /** Left went down where the toolbar's Chat is Left and nothing else holds (the host's gate); [sendNowSetting] = "Send straight away".
     *  Push to talk: [key] the bound button, [t] its press (the reader's), [isPtt] true */
    fun down(t: Long, sendNowSetting: Boolean, key: String = LEFT_KEY, isPtt: Boolean = false): Boolean {
        if (st != St.IDLE && st != St.NOTE) return false
        st = St.PENDING; downAt = t; leftDown = true; held = false; wordSeen = false; words = ""; note = ""
        sendNow = sendNowSetting; src = key; ptt = isPtt
        return true
    }

    /** the host's clock while [St.PENDING]: past [HOLD_MS] it is a hold */
    fun tick(t: Long): Out {
        if (st == St.PENDING && leftDown && t - downAt >= HOLD_MS) { st = St.HELD; held = true; return Out.LISTEN }
        if (st == St.LINE && t - shownAt >= LINE_MS) { st = St.IDLE; return Out.DROP }
        if (st == St.NOTE && t - shownAt >= NOTE_MS) { st = St.IDLE; return Out.DROP }
        return Out.NONE
    }

    /** Left up */
    fun up(t: Long): Out {
        if (!leftDown) return Out.NONE
        leftDown = false
        if (held) swallowUntil = t + SWALLOW_MS
        return when (st) {
            // push to talk's tap: nothing (the Hub's chat hold goes back); the toolbar's tap: the Chat room (once its word came)
            St.PENDING -> { st = St.IDLE; if (ptt) Out.FREE else if (wordSeen) Out.OPEN_CHAT else Out.NONE }
            St.HELD -> { st = St.WORKING; Out.STOP }
            else -> Out.NONE
        }
    }

    /** the router's "chat" word: true = open the Chat room now (today's action) */
    fun word(t: Long): Boolean = when (st) {
        St.PENDING -> { wordSeen = true; false }
        St.IDLE -> t >= swallowUntil
        St.NOTE -> !leftDown && t >= swallowUntil
        else -> false
    }

    /** the recorder had no voice to use (no model, the microphone off): the hold shows [why] and records nothing */
    fun noVoice(t: Long, why: String): Out {
        if (st != St.HELD && st != St.PENDING) return Out.NONE
        showNote(t, why, false)
        return Out.NOTE
    }

    /** the words came back */
    fun heard(t: Long, text: String): Out {
        if (st != St.WORKING && st != St.HELD) return Out.NONE
        val w = text.trim()
        if (w.isEmpty()) { showNote(t, NOT_HEARD, false); return Out.NOTE }
        words = w
        if (sendNow) { st = St.SENDING; return Out.SEND }
        st = St.LINE; shownAt = t
        return Out.SHOW
    }

    /** voice could not give words ([why]: the problem's title) */
    fun failed(t: Long, why: String): Out {
        if (st != St.WORKING && st != St.HELD) return Out.NONE
        showNote(t, why, false)
        return Out.NOTE
    }

    /**
     * a press while the hold has the controller (not the held button's own release): [k] the capture's name. LB / RB (the user, 2026-11-17:
     * "pop up tabs to press L or R to change what chat that its for") and D-pad Up / Down (the user's pick, 2026-11-17: "L/R plus D-pad
     * Up/Down"; Up = LB's way, Down = RB's) move the panel's chat tab while held, while the words are worked out and while they wait (the
     * wait starts again: a choice is never cut short by the fade). The host takes them all (the "talk" registration first): none reaches
     * the game (the chat guest the Hub held) or another Home action.
     */
    fun key(t: Long, k: String): Out = when {
        k in TAB_KEYS && (st == St.HELD || st == St.WORKING || st == St.LINE) -> {
            if (st == St.LINE) shownAt = t
            if (k == LB || k == UP) Out.TAB_L else Out.TAB_R
        }
        else -> keyOther(k)
    }

    /** a finger picked a tab on the pop-up: a waiting line's time starts again (as LB / RB) */
    fun tabTouched(t: Long) { if (st == St.LINE) shownAt = t }

    private fun keyOther(k: String): Out = when (st) {
        St.LINE -> when (k) {
            A -> { st = St.SENDING; Out.SEND }
            else -> { st = St.IDLE; words = ""; Out.DROP }        // B, or any other button: the line goes, nothing sent
        }
        // B while the words are worked out: given up
        St.WORKING -> if (k == B) { st = St.IDLE; words = ""; Out.CANCEL } else Out.NONE
        else -> Out.NONE
    }

    /** the send went ([ok]) or was refused ([why]) */
    fun sent(t: Long, ok: Boolean, why: String = ""): Out {
        if (st != St.SENDING) return Out.NONE
        if (ok) showNote(t, words, true) else showNote(t, why, false)
        return Out.NOTE
    }

    /** the host leaves Home, a fight takes the pad, the app goes: everything goes, nothing sent */
    fun reset(): Out {
        val was = st
        st = St.IDLE; leftDown = false; words = ""; note = ""
        return if (was == St.IDLE || was == St.NOTE) Out.NONE else Out.CANCEL
    }

    private fun showNote(t: Long, s: String, sent: Boolean) { st = St.NOTE; note = s; noteSent = sent; shownAt = t }

    companion object {
        /** past this a press of Left is a hold (the task's ~350 ms) */
        const val HOLD_MS = 350L
        /** a router word this soon after a hold's release is that hold's own (a router that fires on the release) */
        const val SWALLOW_MS = 1000L
        /** the words' line, unsent, goes after this (the task's ~6 s) */
        const val LINE_MS = 6000L
        /** a note (sent, refused, no voice) stays this long */
        const val NOTE_MS = 2500L
        const val A = "PAD1"
        const val B = "PAD2"
        /** the shoulders (DirectPadKeys.LB / RB, the app's own reader): the panel's previous / next chat */
        const val LB = DirectPadKeys.LB
        const val RB = DirectPadKeys.RB
        /** D-pad Up / Down: the panel's previous / next chat too (the user's pick, 2026-11-17: "L/R plus D-pad Up/Down") */
        const val UP = "PADDUP"
        const val DOWN = "PADDDOWN"
        val TAB_KEYS = setOf(LB, RB, UP, DOWN)
        /** the toolbar's Chat button (D-pad Left, the app reader's name) */
        const val LEFT_KEY = "PADDLEFT"

        /**
         * PUSH TO TALK'S BUTTON: the bound command's word comes ~0.1-0.5 s after the press, with no button in it; the button is the one
         * the app's own reader saw go down LAST of those still held ([held]: key -> press time), pressed within [PTT_WINDOW_MS] of
         * [now] (a modifier held first - ZL for ZL + X - is older than the button itself). null = nothing held: it was a tap (nothing).
         */
        fun pttSource(held: Map<String, Long>, now: Long): Pair<String, Long>? =
            held.entries.filter { now - it.value in 0..PTT_WINDOW_MS }.maxByOrNull { it.value }?.let { it.key to it.value }
        /** the longest the command's word may trail its button's press */
        const val PTT_WINDOW_MS = 1500L

        /** the Controller room's Y on the Chat (PadMap m170's page rows): Off -> On, Show then A sends -> On, Send now -> Off ("On
         *  release" kept as it was when the switch goes off, as Settings keeps it); ([on], [sendNow]) now -> the next */
        fun nextSetting(on: Boolean, sendNow: Boolean): Pair<Boolean, Boolean> = when {
            !on -> true to false
            !sendNow -> true to true
            else -> false to true
        }
        const val NOT_HEARD = "Didn't hear you"
        const val NO_VOICE = "Download a voice in Add-ons"
        const val MIC_OFF = "Microphone off"

        /**
         * D-PAD LEFT IS THE TOOLBAR'S CHAT NOW (the router's rule, PadMap m29 / m44 / m55, as [HomeFightFace.downIsHomeBar] reads Down):
         * in the fight switch ([fight]) a Left with an In-combat entry runs that entry - Chat only when the entry is the DS Chat command;
         * else Left is Chat while the DS WoW toolbar is on ([toolbar]) with Chat on Left ([dirs]: the router's own word, none = its
         * default), or the DS Chat command is on the bare button.
         */
        fun leftIsChat(map: PadMapModel.Map?, toolbar: Boolean, dirs: Map<Int, String>?, fight: Boolean): Boolean =
            isChat(map, toolbar, dirs, fight, LEFT)

        /**
         * MOVABLE HOME (SPEC section 6 item 7): the toolbar's Chat is on the side the chat's column is (the add-on's mirror bit makes
         * `dirs` say so): the bare D-pad key whose word is "chat" right now ([LEFT_KEY] or [RIGHT_KEY]), by the same rule as [leftIsChat];
         * null = neither (no hold to talk). Fix round 2: Right is the side only when the toolbar's own `dirs` put the chat there (the
         * mirrored layout, as [chatArrowRight] - the Settings row's arrow - reads it); every other case is 1514's Left only (a DS Chat
         * command on Right's In-combat entry or on the bare Right never arms the hold)
         */
        fun chatKey(map: PadMapModel.Map?, toolbar: Boolean, dirs: Map<Int, String>?, fight: Boolean): String? {
            val pos = if (toolbar && chatArrowRight(dirs)) RIGHT else LEFT
            if (!isChat(map, toolbar, dirs, fight, pos)) return null
            return if (pos == RIGHT) RIGHT_KEY else LEFT_KEY
        }
        /** the hold's arrow for the Settings row ("Hold [arrow] to talk"): the chat key's side, Left when unknown */
        fun chatArrowRight(dirs: Map<Int, String>?): Boolean = dirs?.get(RIGHT) == "chat" && dirs[LEFT] != "chat"

        private fun isChat(map: PadMapModel.Map?, toolbar: Boolean, dirs: Map<Int, String>?, fight: Boolean, pos: Int): Boolean {
            val f = if (fight && map != null) HomeFightFace.dpadFight(map, pos) else null
            if (f != null) return f.layer == "command" && f.key == CHAT_KEY
            if (toolbar) return (dirs?.get(pos) ?: DEFAULT_DIRS[pos]) == "chat"
            return map?.let { PadMapModel.slot(it, 1, pos)?.key == CHAT_KEY } == true
        }
        /** the router's default words (PadMap m29's ROOMBAR, mirror off) */
        val DEFAULT_DIRS = mapOf(1 to "chat", 2 to "radial", 3 to "map", 4 to "home")

        const val LEFT = 1
        const val RIGHT = 3
        /** the toolbar's Chat after a swap (D-pad Right, the app reader's name) */
        const val RIGHT_KEY = "PADDRIGHT"
        /** Spells' DS Chat command (PadMap.lua's commands: /click WowCompanionPadChat) */
        const val CHAT_KEY = "DSCHAT"

        /** the held recorder ([WhisperEngine] held): it stops on the quiet rule's [step] - never while [held] (the release stops it) */
        fun stopsOn(held: Boolean, step: Voice.Vad.Step): Boolean = !held && step != Voice.Vad.Step.GO
        /** a held recording of [ms] whose loudest frame was [peakDb] has something to work out (not a blip, not a silent room) */
        fun heardSomething(ms: Long, peakDb: Float): Boolean = ms >= HELD_MIN_MS && peakDb > Voice.MIN_SPEECH_DBFS
        /** a held recording shorter than this is a slip of the finger */
        const val HELD_MIN_MS = 300L

        /** a voice error's few words (the voice sheet's own titles) */
        fun problem(code: Int): String = when (code) {
            Voice.E_PERMISSIONS -> MIC_OFF
            Voice.E_AUDIO -> "No sound from the mic"
            Voice.E_SPEECH_TIMEOUT, Voice.E_NO_MATCH -> NOT_HEARD
            else -> "Voice busy"
        }

        /** the recorder's level (Voice.rmsDb, -2..10) as the halo's 0..1 */
        fun level(rmsDb: Float): Float = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)

        /** the words as chat can carry them (printable ASCII, no "|"), never a /command (it would run, not be said) */
        fun body(text: String): String = Voice.clean(text, ChatStream.MAX).trimStart('/', ' ')
    }
}
