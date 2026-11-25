package com.jrees.wowcompanion

/**
 * SINGLE SCREEN MODE (the user, 2026-11-25: "a single screen mode where the game shifts to the top screen when a button is pushed. We
 * need to rebuild all of our menus and features to work on the top screen when single screen mode is selected. The button to shift it
 * needs to be in the pause menu below the edit mode button"). The game stays where it is (display 0, the Thor's top screen); DS Forever's
 * whole bottom screen (CompanionHost) is hosted on the TOP screen instead, in a never-focused window over the game (DsForeverScreen's
 * TopWindow), and the bottom screen goes back to Android's launcher (the user's pick "Back to Thor's launcher").
 *
 * THE USER'S PICKS (2026-11-25): rooms = "Centred panel" (the 1240 x 1080 page 1:1 in the middle, over the dimmed, running game:
 * [panelBox]); B in a room = "Straight to the game"; the button = "Icon on Y", below Edit on the pause wheel, its picture flips One
 * screen / Two screens; every start is two screens ("Two screen start however if its a single screen device then it starts in single
 * screen mode"); a one-screen device = this mode automatically, the button hidden ([offered] false). Builder defaults: never in a fight
 * ("Not in a fight"), never with Swap screens On, never while streaming or loading ([refusal]).
 *
 * The shared state only, pure (no android.*: the tests run it), ScreenSwap-shaped. ONE writer: DsForeverScreen ([set]); the hosts read
 * [inUse] and register [onChange] (weakly); the wheel's button asks through [request].
 */
object SingleScreen {
    /** why the mode is on: the wheel's button, or a device with no other screen */
    enum class Why { USER, DEVICE }

    /** single screen mode is on NOW (DsForeverScreen's latch for this run: never saved, every start is two screens) */
    @Volatile var inUse: Boolean = false; private set
    @Volatile var why: Why? = null; private set

    /** the wheel's button shows: a second screen exists (ScreenSwap.offered's rule); false on a one-screen device (the user's pick) */
    @Volatile var offered: Boolean = false

    /** a fight now (the host sets it, weakly: homeView.inFight() || the book's combat, as MemWatch.fightNow) */
    @Volatile var fightNow: () -> Boolean = { false }

    /** DsForeverScreen's switch (main thread): [on] the mode on / off, [why] for the log; false = not done (refused or not possible) */
    @Volatile var request: ((on: Boolean, why: String) -> Boolean)? = null

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    /** how many times the mode changed this run, and the last change's uptime-free stamp (the caller's clock) */
    @Volatile var changes = 0; private set
    @Volatile var changedAt = 0L; private set

    /**
     * DsForeverScreen only: the mode [on] for [why] (null when off); the listeners hear each CHANGE once. [at] = the caller's clock
     * (uptime ms) for [switchedLately]
     */
    internal fun set(on: Boolean, why: Why?, at: Long = 0L) {
        val w = if (on) (why ?: Why.USER) else null
        if (on == inUse && w == this.why) return
        inUse = on; this.why = w; changes++; changedAt = at
        for (l in listeners) runCatching { l() }
    }

    /** hosts register with weakly(...): the listener list outlives every host */
    fun onChange(l: () -> Unit) { if (l !in listeners) listeners.add(l) }
    fun offChange(l: () -> Unit) { listeners.remove(l) }

    /** a switch happened within [ms] of [now] (the new host's defensive heal, CompanionHost) */
    fun switchedLately(now: Long, ms: Long = HEAL_MS): Boolean = changes > 0 && changedAt != 0L && now - changedAt in 0..ms
    const val HEAL_MS = 10_000L

    /** what the button's switch would meet now (the host reads each) */
    data class Ask(val swapInUse: Boolean, val streaming: Boolean, val loading: Boolean, val inWorld: Boolean, val fight: Boolean)

    const val SWAP_FIRST = "Turn Swap screens Off first"
    const val NOT_STREAMING = "Not while streaming"
    const val NOT_LOADING = "Not while loading"
    const val NOT_FIGHT = "Not in a fight"

    /** null = the switch may go; else the words the wheel shows on its red line (the first that applies, in this order; both directions) */
    fun refusal(a: Ask): String? = when {
        a.swapInUse -> SWAP_FIRST
        a.streaming -> NOT_STREAMING
        a.loading || !a.inWorld -> NOT_LOADING
        a.fight -> NOT_FIGHT
        else -> null
    }

    // ---- the controller after a touch on the bottom screen (DsForeverScreen.keysTop; the open ruling R1, built default A)

    /** R1: A (true) = the game's task is brought back to the front [KEYS_BACK_MS] after the bottom screen took the keys; B (false) = the
     *  hint "Tap the game for the controller" only (Android's own rule: a tap on the top screen gives them back) */
    const val KEYS_BACK = true
    const val KEYS_BACK_MS = 400L
    const val KEYS_STUCK_MS = 1_500L
    const val HINT_MS = 1_200L
    const val CONTROLLER_BACK = "Controller back"
    const val TAP_FOR_KEYS = "Tap the game for the controller"

    /** one of our own activities is up on the other screen (the host's openOnBottom, NPC): the keys are not taken back meanwhile */
    @Volatile var keysPaused = false
    /** NPC: that activity closed - the keys looked at again (DsForeverScreen: taken back if the other screen still has them; it sends no
     *  word of its own when it closes, and Android leaves the keys on the screen that had them) */
    @Volatile var keysAgain: (() -> Unit)? = null

    /** the problem report's counts: the bottom screen took the keys / they were brought back / they stayed away */
    @Volatile var keysAway = 0; private set
    @Volatile var keysBack = 0; private set
    @Volatile var keysStuck = 0; private set
    internal fun noteKeys(away: Boolean = false, back: Boolean = false, stuck: Boolean = false) {
        if (away) keysAway++; if (back) keysBack++; if (stuck) keysStuck++
    }

    /** the problem report's line */
    fun line(): String = "One screen: " + (if (inUse) "on (${if (why == Why.DEVICE) "no other screen" else "the button"})" else "off") +
        ", button ${if (offered) "offered" else "hidden"}, switches $changes" +
        (if (keysAway > 0) ", the other screen took the controller $keysAway (back $keysBack, stayed $keysStuck)" else "")

    // ---- the centred panel (the user's pick "Centred panel")

    const val PANEL_W = 1240
    const val PANEL_H = 1080

    /**
     * the centred box for a [w] x [h] window, (left, top, right, bottom): 1240 x 1080 at (340, 0) on 1920 x 1080; a smaller screen keeps
     * 1240:1080 (as large as fits), centred
     */
    fun panelBox(w: Int, h: Int): IntArray {
        val bw = minOf(w, Math.round(h * PANEL_W.toFloat() / PANEL_H))
        val bh = minOf(h, Math.round(bw * PANEL_H.toFloat() / PANEL_W))
        val l = (w - bw) / 2; val t = (h - bh) / 2
        return intArrayOf(l, t, l + bw, t + bh)
    }

    /**
     * a child of the host's content in the centred panel: its margins (left, top, right, bottom, px) grown by the box's insets in a
     * [screenW] x [screenH] window, its size and gravity kept - a MATCH_PARENT child becomes the box (1240 wide on the Thor, its own
     * margins inside it), a WRAP one stands where it stood inside the box. [box] null (two screens) = the margins as they are
     */
    fun boxMargins(margins: IntArray, box: IntArray?, screenW: Int, screenH: Int): IntArray {
        if (box == null) return margins
        return intArrayOf(margins[0] + box[0], margins[1] + box[1], margins[2] + (screenW - box[2]), margins[3] + (screenH - box[3]))
    }

    // ---- timings
    /** the pause wheel's controller free waited for before the switch (CompanionHost.singleSwitch) */
    const val SWITCH_FREE_WAIT_MS = 800L
    /** GameUi's top-screen taps wait this long after a new host starts (one reconcile follows the switch) / at most after the ask */
    const val SWITCH_HOLD_MS = 1_500L
    const val SWITCH_HOLD_MAX_MS = 5_000L
    /** a second screen came during a fight on a one-screen start: looked at again this often */
    const val DEVICE_RETRY_MS = 2_000L
    /** the host's safety look at what shows ([SingleLook]) while the mode is on */
    const val LOOK_POLL_MS = 100L

    /** tests */
    internal fun reset() {
        inUse = false; why = null; offered = false; fightNow = { false }; request = null; listeners.clear(); changes = 0; changedAt = 0L
        keysAway = 0; keysBack = 0; keysStuck = 0; keysPaused = false; keysAgain = null
    }
}
