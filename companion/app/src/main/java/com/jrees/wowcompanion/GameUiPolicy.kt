package com.jrees.wowcompanion

/**
 * GAME UI ON THE TOP SCREEN: the rules, with nothing of Android in them.
 *
 * USER RULING 2026-09-27. Every element of the game's own UI that the companion can replace has a
 * setting with three modes, and ON / OFF speak about THE GAME'S ELEMENT:
 *     ON       the game keeps drawing it on the top screen
 *     OFF      hidden while the companion is on the bottom screen
 *     DYNAMIC  hidden only while the room that replaces it is showing (the minimap: the Map room),
 *              or while HOME shows, for the elements Home draws its own copy of (home session,
 *              2026-09-28: Home is the page the bottom screen rests on; it holds the minimap, the
 *              controller bars, the focused quest and the chat, so the game's copies are replaced there too)
 * When the companion is not on screen at all, everything is shown: nothing is ever hidden without
 * a replacement in view.
 *
 * ONE TAP CARRIES THE WHOLE LAYOUT (Hub.lua): the pad column is a bit mask of the hidden elements.
 * So one touch on the room strip is one tap, however many elements change with it, which is what
 * the lock asks for (one touch = one game action).
 *
 * WHEN A TAP IS SENT. Only when the game is provably in the world, the addon has published its
 * row, and what the game shows differs from what the modes ask for. The addon holds a change that
 * combat forbids and applies it afterwards, so that case is waited out, not tapped again. A tap
 * that changes nothing is tried ONCE more and then left alone until something changes (user
 * ruling: "tries once more at most").
 *
 * Pure Kotlin on purpose (no android.*, no org.json), so tools/hub_test can run the real file.
 */
enum class UiMode {
    ON, OFF, DYNAMIC,
    /** LATER 25 (the user 2026-10-07: "Show the game's minimap on top while tracking"): the minimap only - shown on the top screen
     *  while one of the game's tracking abilities (Find Herbs / Find Minerals / Find Treasure) is on, hidden as Off otherwise */
    TRACKING,
}

/**
 * what the app knows about an element: its name, the room that replaces it, its first mode, and
 * whether the Home page also replaces it ([onHome]); `home` is the ROOM, not the Home page
 */
data class UiElement(val id: String, val label: String, val home: String, val default: UiMode,
                     val onHome: Boolean = false)

object UiElements {
    /** ids and bits match Hub.lua's FIXED table; `home` is a Room name */
    val KNOWN = listOf(
        // NOT on Home for now (home session, 2026-09-29 00:20): the alpha-only hide leaves the minimap's
        // icons, arrow and zone name on the top screen (user, 00:15), and Home would make that half-hidden
        // minimap the usual sight. Back to onHome = true once the map session's fix is seen clean.
        // ALL OFF by default since 2026-10-06 (the user: "the settings on my old app were really good settings for default start up";
        // their own app had every element Off): a fresh install starts there; saved choices stay as they are
        UiElement("minimap", "Minimap", "MAP", UiMode.OFF, onHome = false),
        // Dynamic since Home (user, 2026-09-28 20:35: "Yes, hide them while Home shows")
        // hub2 (Hub.lua, LATER item 20): the game's buffs, debuffs and XP bar ride this element, so the row names them
        UiElement("hud", "Controller bars, buffs and XP", "CAST", UiMode.OFF, onHome = true),
        UiElement("tracker", "Quest tracker", "QUESTS", UiMode.OFF, onHome = true),
        UiElement("chat", "Chat window", "CHAT", UiMode.OFF, onHome = true),
        // the interaction overlay is no room: Off = the companion shows the NPC windows and the
        // controller drives them on the bottom screen (WowCompanionInteract, element "dialogs")
        UiElement("dialogs", "NPC windows", "", UiMode.OFF),
    )
    fun of(id: String) = KNOWN.firstOrNull { it.id == id }
    /** the modes an element offers: "While tracking" is the minimap's alone (LATER 25) */
    fun modesFor(id: String): List<UiMode> =
        if (id == "minimap") listOf(UiMode.ON, UiMode.OFF, UiMode.DYNAMIC, UiMode.TRACKING) else listOf(UiMode.ON, UiMode.OFF, UiMode.DYNAMIC)
}

/** one element as the addon reports it */
data class HubEl(val id: String, val bit: Int, val hidden: Boolean, val why: String? = null)

/** the addon's `hub` document */
data class HubState(
    val boot: Long,          // changes at every /reload
    val n: Int,              // taps the addon has received since then
    val want: Int?,          // the layout its last tap asked for
    val pending: Boolean,    // part of that layout is waiting for combat to end
    val hasRow: Boolean,     // the pad row exists and is published
    val els: List<HubEl>,
    val current: Boolean,    // InputGate: written by the game moments ago, not replayed
)

/**
 * hub11 (f-top-castbar-toggle; the user, 2026-10-15: "We need to have a UI Toggle for the top screen to Show/hide the cast bar"): the
 * GAME's cast bar, its own Show / Hide. Not a layout bit (all five are held): Hub.lua takes the row's two spare columns, [COL_SHOW] /
 * [COL_HIDE], one absolute tap each. Like an element, Hide holds only while the companion is on the bottom screen. The same tap rules
 * as the layout: after the loading screen settles, a reply waited for, the ask tried once more, then slowly while still silent.
 */
class CastBarPolicy(
    /** the row's Show / Hide columns: the cast bar's (hub11) by default; HUD mode's are [HUD_SHOW] / [HUD_HIDE] (hub13) */
    private val colShow: Int = COL_SHOW, private val colHide: Int = COL_HIDE) {
    private var tapCol = -1
    private var tapBoot = -1L
    private var tapN = -1
    private var tapAt = 0L
    private var tries = 0

    fun freshLogin() { tries = 0; tapCol = -1 }

    /** should the game's cast bar be hidden: the user's Hide, while the companion shows */
    fun wantsHidden(hide: Boolean, visible: Boolean) = hide && visible

    /** the column to tap now, or null. [hidden] = the hub document's cb.hidden (null: an add-on without the cast bar) */
    fun step(hidden: Boolean?, hide: Boolean, visible: Boolean, s: HubState?, live: Boolean, now: Long, settleUntil: Long = 0L): Int? {
        if (hidden == null || s == null || !live || !s.current || !s.hasRow) return null
        val want = wantsHidden(hide, visible)
        if (hidden == want) { tries = 0; tapCol = -1; return null }
        if (now < settleUntil) return null
        val col = if (want) colHide else colShow
        val same = tapCol == col && tapBoot == s.boot
        if (!same) tries = 0
        if (same && s.n == tapN && now - tapAt < GameUiPolicy.REPLY_MS) return null
        if (same && tries >= GameUiPolicy.MAX_TRIES && now - tapAt < GameUiPolicy.SLOW_RETRY_MS) return null
        tapCol = col; tapBoot = s.boot; tapN = s.n; tapAt = now; tries += 1
        return col
    }

    companion object {
        const val COL_SHOW = 32            // Hub.lua CB_SHOW / CB_HIDE: past the 32 layouts, inside the row's 40 columns
        const val COL_HIDE = 33
        const val HUD_SHOW = 34            // hub13: HUD mode, the player / target frames
        const val HUD_HIDE = 35
    }
}

sealed class UiStep {
    /** send one tap on column [mask] of the pad row */
    data class Tap(val mask: Int) : UiStep()
    data class Wait(val why: String) : UiStep()
    object InPlace : UiStep()
}

class GameUiPolicy(/** LATER 25: a tracking ability of the game is on now (the map document's track h / m / t, Map.lua) */
                   private val trackingOn: () -> Boolean = { false },
                   private val modeOf: (String) -> UiMode) {

    /** the Home page's Room name (Theme.kt `Room.HOME`); a String here, as every room is */
    private val HOME = "HOME"

    private var tapMask = -1
    private var tapBoot = -1L
    private var tapN = -1
    private var tapAt = 0L
    private var tries = 0

    /** b130: a login / reload (a current hello) starts the tries afresh - a new character's load is a new chance, not a give-up */
    fun freshLogin() { tries = 0; tapMask = -1 }

    /** should [el] be hidden, with [room] on the bottom screen? */
    fun wantsHidden(el: HubEl, room: String?, visible: Boolean): Boolean {
        val info = UiElements.of(el.id) ?: return el.hidden     // not ours to decide: leave it
        if (!visible) return false
        return when (modeOf(el.id)) {
            UiMode.ON -> false
            UiMode.OFF -> true
            UiMode.DYNAMIC -> room == info.home || (room == HOME && info.onHome)
            UiMode.TRACKING -> !trackingOn()
        }
    }

    fun desired(s: HubState, room: String?, visible: Boolean): Int {
        var m = 0
        for (e in s.els) if (e.bit in 0 until BITS && wantsHidden(e, room, visible)) m = m or (1 shl e.bit)
        return m
    }

    fun shown(s: HubState): Int {
        var m = 0
        for (e in s.els) if (e.bit in 0 until BITS && e.hidden) m = m or (1 shl e.bit)
        return m
    }

    /**
     * What to do now. The caller sends the tap when this returns [UiStep.Tap]; it must then call
     * nothing else: the tap is recorded here.
     */
    fun step(s: HubState?, room: String?, visible: Boolean, live: Boolean, now: Long, settleUntil: Long = 0L): UiStep {
        if (s == null) return UiStep.Wait("Waiting for the game")
        if (!live || !s.current) return UiStep.Wait("Checking the game is live")
        if (!s.hasRow) return UiStep.Wait("The game has not published its tap pad")
        val want = desired(s, room, visible)
        if (shown(s) == want) { tries = 0; tapMask = -1; return UiStep.InPlace }
        // JUST LOGGED IN / RELOADED ([settleUntil] = a current hello + SETTLE_MS; measured in WoW Forever 2026-10-02: the hide tap
        // 0.1 s after the hello was swallowed by the loading screen and the 20 s retry hid the UI 21 s late - the user: "a bit of a
        // delay on hiding the UI that I don't love"). The first tap waits for the screen to settle instead; no tap is spent on it.
        if (now < settleUntil) return UiStep.Wait("The game is still loading")
        if (s.want == want && s.pending) return UiStep.Wait("Waits for combat to end")

        val sameAsk = tapMask == want && tapBoot == s.boot
        if (!sameAsk) tries = 0
        if (sameAsk && s.n == tapN && now - tapAt < REPLY_MS) return UiStep.Wait("Sent")
        // b130 (the user, b129: "When loading in on a new charactor the UI stays visible until I mess with the settings"): a new character's first load (its intro) swallowed both quick tries, and the hide waited for a settings change.
        // Past the quick tries the same ask goes again every SLOW_RETRY_MS while the game still shows otherwise - but only when it was
        // SILENT (the taps swallowed); a game that answered with a reason ([HubEl.why]) is left alone, as before
        val refused = s.els.any { it.why != null }
        if (sameAsk && tries >= MAX_TRIES && (refused || now - tapAt < SLOW_RETRY_MS)) return UiStep.Wait("The game did not change it")

        tapMask = want; tapBoot = s.boot; tapN = s.n; tapAt = now; tries += 1
        return UiStep.Tap(want)
    }

    companion object {
        const val BITS = 5                 // Hub.lua MAX_BITS
        const val MAX_TRIES = 2            // the tap, and one more
        const val REPLY_MS = 20_000L       // the reply rides a flush; the 15 s heartbeat heals a lost one
        const val SETTLE_MS = 3_000L       // after a current hello: the loading screen swallows taps (one landed at +2.7 s)
        const val SLOW_RETRY_MS = 30_000L  // b130: past MAX_TRIES, one more tap this often while the game still differs
    }
}
