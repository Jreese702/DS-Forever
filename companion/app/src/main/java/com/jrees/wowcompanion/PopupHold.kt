package com.jrees.wowcompanion

import android.view.KeyEvent

/**
 * OUR POP-UPS NEVER SEND INPUT TO THE GAME (the user before 1.0.7: "When clicking on the pop up boxes, it shouldn't send inputs to the
 * game"). While one of the bottom screen's pop-ups asks for a press with the game up (the sign-in pop-ups with buttons, the report's
 * notice on Home), every pad button they use is held back from the game: A, B and the D-pad (keys, and the hat axis the Thor's D-pad
 * really sends). Only presses: a release always passes, so nothing can stick down in the game. It all comes back the moment the pop-up
 * goes, and while our own login form is open (its own text fields). DS Forever's own pad copy is never affected. Pure: tested.
 */
object PopupHold {
    /** the pad buttons the pop-ups use */
    val HELD_KEYS = setOf(
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    )

    /**
     * The sign-in states that hold the pad: only the code / password prompt (1.0.7's "Type with Keyboard; don't press A": the game's
     * gamepad cursor sits on the username behind its dialog). The others (FAILED / RESTART / TYPING) let A, B and the D-pad go to the
     * game as normal (b-login-a-input, the user 15 Oct); our pop-up there is touch only.
     */
    private val SIGN_IN_ASKS = setOf(SetupFlow.SignIn.AUTH, SetupFlow.SignIn.CHECK)

    fun holds(signIn: SetupFlow.SignIn, gameUp: Boolean, loginFormOpen: Boolean, noticeAsking: Boolean): Boolean =
        gameUp && !loginFormOpen && (noticeAsking || signIn in SIGN_IN_ASKS)

    /** a key press the game must not get now ([holding] = [holds]); releases always pass. [wheel]: one of the context wheel's pop-ups
     *  is up ([Wheel]): every face button, the D-pad, the shoulders, Start and Select are held ([WHEEL_KEYS]). [room]: a tabbed room
     *  owns L / R ([ROOM_KEYS]: its tab row, the app's own reader; SetupBridge.roomShoulders) */
    fun holdsKey(holding: Boolean, keyCode: Int, down: Boolean, wheel: Boolean = false, room: Boolean = false, trigger: Boolean = false): Boolean =
        down && ((holding && keyCode in HELD_KEYS) || (wheel && keyCode in WHEEL_KEYS) || (room && keyCode in ROOM_KEYS) ||
            (trigger && keyCode in TRIGGER_KEYS))

    /**
     * THE DS MENU'S ZL / ZR (single screen mode, the 1536 pass 2; the user's pick: "HOLD THEM BACK from the game while a room is open, so
     * they only switch rooms, the way L / R only switch tabs"): the triggers' digital keys a room on the menu's stage owns
     * (SetupBridge.roomTriggers). Only presses: a release always passes. Their analog pull is masked where player 1's state is written
     * (WinHandler.holdOut: [maskTriggers])
     */
    val TRIGGER_KEYS = setOf(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2)

    /** the triggers' pull the game is given ([l], [r] 0..1): none while a menu room owns them ([held]); the same values otherwise */
    fun maskTriggers(held: Boolean, l: Float, r: Float): Pair<Float, Float> = if (held) 0f to 0f else l to r

    /**
     * THE ROOMS' TABS ON TOP (the user, 2026-11-25: "...make them so that L and R shift the tabs left and right"): the bumpers a tabbed
     * room owns. Held from the game so a bumper meant for the room's tabs never also reaches it (LB is the game's emulated Ctrl, RB its
     * target / bar paging); only presses: a release always passes, so a bumper held before the room came up lets go in the game too
     */
    val ROOM_KEYS = setOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1)

    /** the keys a wheel pop-up holds: its Yes / No and its keyboard use A, B, X, Y, the D-pad and Start; nothing of the pad reaches the game */
    val WHEEL_KEYS = HELD_KEYS + setOf(
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
    )

    /**
     * THE CONTEXT WHEEL'S POP-UPS (the user on test 1502, Abandon Pet: "theres a text asking if you are sure and a for yes b for no, but
     * pressing a jumps. this needs to have a real yes no buttons pop up and a should not jump on that prompt"): the wheel's Yes / No
     * confirm and its rename keyboard ([TargetRadialView]) hold the pad from the game while they are up, whoever opened the wheel (the
     * target wheel's guest hold, the portrait's map hold, Party mode's PartyPad - or, with Party mode off, nothing at all: that wheel only
     * READ the pad, so the A went to the game too). THE HOLD RACE: the wheel reads the pad from getevent while the game's copy goes
     * through Android's key dispatch; the press that answers the pop-up may be read first and close it before the dispatch asks, so the
     * hold lasts [GRACE_MS] past the pop-up ([down]). Releases always pass (nothing sticks down in the game). Pure (the clock is the caller's).
     */
    class Wheel {
        private var up = false
        private var until = 0L
        /** a pop-up is up from now */
        fun up() { up = true }
        /** it went at [now]: the hold stays [GRACE_MS] more */
        fun down(now: Long) { if (up) { up = false; until = now + GRACE_MS } }
        fun holds(now: Long): Boolean = up || now < until
        val isUp get() = up
    }
    const val GRACE_MS = 500L
    /** the one the app uses (main thread writes, the key dispatch reads) */
    val wheel = Wheel()

    /** a D-pad sent as the hat axis (the Thor): held while it points anywhere; its return to centre always passes */
    fun holdsHat(holding: Boolean, hatX: Float, hatY: Float): Boolean = holding && (hatX != 0f || hatY != 0f)
}
