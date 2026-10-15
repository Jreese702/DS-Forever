package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject

/**
 * THE BAR SELECTOR (home session; LATER item 7, the user: "press select to get a controller selector that starts on the home
 * button and lets me use the controler controlls to go to a room and open it with A"; UI Art's SHEET_bar_select.png, the user:
 * "Yes, both as drawn").
 *
 * Select, while NOBODY holds the controller, reaches the app through interact's capture v5 as {type:"cappad", room:"bar"} (its
 * Select listener, which this view keeps switched on with iact `sel on`). The cursor then starts on Home's disc and the selector
 * holds the controller as the generic guest "bar" (Quests.lua q7, Select its extra key), so the D-pad stays off the game:
 *   left / right   the cursor along the bar in the order it is drawn, Home's disc among the rooms; LB / LT and RB / RT too (q8's extras)
 *   A              opens the room under it (Home on the disc); the guest is freed first, then the room takes its own
 *   B / Select     leaves the selector where you are
 * The cursor is the kit's golden box on a picture; on Home the button itself breathes gold (Strip.homeFocus); gold arrows with L / R
 * sit on its sides; a plate centred over the bar says "A Open  B Back". A touch anywhere still works as before: MainActivity closes the selector on any room change.
 * The bar COMMANDS (Hub.lua hub4, Spells' macros on any pad button) bring up the same cursor WITHOUT holding the controller: see onCommand.
 *
 * This view lies over the rooms and the bar and only draws; it never takes a touch.
 */
class HomeBarSelect(
    context: Context,
    private val strip: Strip,
    /** the quests documents' pad: the guest's cells (GuestPad) */
    private val padSource: () -> JSONObject?,
    /** the room under the cursor when A is pressed */
    private val onOpen: (Room) -> Unit,
) : View(context) {

    var open = false
        private set(v) { if (field != v) { field = v; syncQuiet(); fade(v) } }

    /** the harmony pass's motion: the cursor fades in (Motion.BAR_IN) and out (BAR_OUT); while it fades out it keeps its last look */
    private var fadingOut = false
    /** the look it fades out with (close() resets held / byCommand right after) */
    private var shotHeld = false
    private var shotByCommand = false
    private fun fade(inn: Boolean) {
        animate().cancel()
        if (inn) {
            fadingOut = false
            alpha = 0f
            animate().alpha(1f).setDuration(Motion.BAR_IN).setInterpolator(Motion.curve()).start()
        } else {
            fadingOut = true
            shotHeld = held; shotByCommand = byCommand
            animate().alpha(0f).setDuration(Motion.BAR_OUT).setInterpolator(Motion.curve())
                .withEndAction { fadingOut = false; alpha = 1f; invalidate() }.start()
        }
        invalidate()
    }

    /** the selector is up AND holds the controller (true) or not (false): MainActivity keeps Home's top controller quiet meanwhile
     *  (LATER item 23a). Not while a command cursor goes without the controller (a fight): the buttons are the game's then */
    var onOpenChanged: ((Boolean) -> Unit)? = null
    private var quietSaid: Boolean? = null
    private fun syncQuiet() {
        val q = open && (!byCommand || held)
        if (q != quietSaid) { quietSaid = q; onOpenChanged?.invoke(q) }
    }
    /** the cursor came up by a bar COMMAND (Hub.lua hub4): it asks for the controller as Select does, and hides by itself after IDLE */
    private var byCommand = false
    private var lastCmd = -1 to -1
    private var at = Room.HOME
    private var held = false
        set(v) { field = v; syncQuiet(); syncHome() }
    /** the hold answered ok and not "pending" (a real hold, not queued): with [barHolds] it decides a hand-over (closeFor) */
    private var heldReal = false
    /** the room on screen (MainActivity's): where Select's cursor starts */
    var roomNow: () -> Room = { Room.HOME }
    /** the guest's hold was asked for (Select, or a command): a close sends its free */
    private var holdAsked = false
    /** the cursor came up while a ROOM was on screen: Home's disc is then a stop (the user: "When in a room, the home button should
     *  be an option to select to go back home when using the room left and right buttons"); from Home it is still skipped */
    private var fromRoom = false
    private var openedAt = 0L
    private var lastSelN = -1
    private var lastPadN = -1
    private var selAskedAt = 0L
    private var interactPad: JSONObject? = null
    private var selOffCol = -1

    // ---------------------------------------------------------------- capture v5: Select reaches the app

    /** a CURRENT interact document: keep the capture's Select listener wanted (one `sel on` tap, again at most every 10 s) */
    fun onInteract(msg: JSONObject) {
        // not every interact document carries the pad: the last one seen, else the quests documents' (the same shared grid)
        msg.optJSONObject("pad")?.let { interactPad = it }
        // who holds the shared capture now (absent = nobody): the free check below reads it
        barHolds = msg.optString("holder") == "pad:$ID"; holderAt = SystemClock.uptimeMillis()
        // NO-GAP: the room opened by a pick takes over from the bar (RoomHandOver.Bar)
        if (handTo != null) when (RoomHandOver.Bar.onDoc(msg.optString("holder"), msg.optJSONObject("wait")?.optString("for") ?: "")) {
            RoomHandOver.Bar.Step.DONE -> endHandOver("holder ${msg.optString("holder").ifEmpty { "nobody" }}")
            RoomHandOver.Bar.Step.FREE -> { freeBar("a take waits behind the bar: the free grants it"); endHandOver("queued room") }
            RoomHandOver.Bar.Step.WAIT -> {}
        }
        val selc = msg.optJSONObject("proof")?.optJSONObject("selc") ?: return
        selOffCol = selc.optInt("off", -1)
        if (msg.optJSONObject("sel")?.optBoolean("want") == true) return
        val now = SystemClock.uptimeMillis()
        if (selAskedAt != 0L && now - selAskedAt < ASK_EVERY) return
        val col = selc.optInt("on", -1)
        if (col >= 0 && tap(interactPad ?: padSource(), listOf("iact" to col))) { selAskedAt = now; Log.i(TAG, "bar: sel on (iact $col)") }
    }

    /** the last current interact document said the bar holds the capture ([holderAt] when it was read) */
    private var barHolds = false
    private var holderAt = 0L
    private var freedAt = 0L
    private var freeRetried = false

    /** FREE_CHECK after a free: a current interact document read SINCE the free still says the bar holds -> the free once more
     *  (idempotent); no document yet -> one more look a FREE_CHECK later */
    private val freeCheck = object : Runnable {
        override fun run() {
            if (open || freeRetried || freedAt == 0L) return
            if (holderAt <= freedAt) { if (SystemClock.uptimeMillis() - freedAt < 2 * FREE_CHECK + 50) postDelayed(this, FREE_CHECK); return }
            if (!barHolds) return
            freeRetried = true
            val sent = tap(padSource(), GuestPad.cells(padSource(), ID, hold = false))
            Log.i(TAG, "bar: free again (the capture still says the bar holds after the cursor closed)${if (sent) "" else ", not sent"}")
        }
    }

    /**
     * THE NO-GAP HAND-OVER (the user 2026-10-06: the room takes over BEFORE the bar lets go; b79's revert of 1e8ee754 named the one
     * blocker, Professions' entry, fixed in its p22). A room change while the bar HOLDS sends no free: the new room's take pre-empts
     * "pad:bar" (capture v7 / v12). [handTo] is that room until a current interact document names another holder (or the bar hears
     * lost); a take queued behind the bar gets the free at once; [RoomHandOver.Bar.MAX_MS] without either = the free, late.
     * Meanwhile the bar's presses are dropped, not re-freed (MainActivity.guestShown("bar") counts [handingOver]).
     */
    private var handTo: Room? = null
    private var handAt = 0L
    val handingOver: Boolean get() = handTo != null
    private fun startHandOver(r: Room) {
        handTo = r; handAt = SystemClock.uptimeMillis()
        removeCallbacks(handCheck); postDelayed(handCheck, RoomHandOver.Bar.MAX_MS)
        Log.i(TAG, "bar: hand-over to $r (no free: the room's take pre-empts the bar)")
    }
    private fun endHandOver(why: String) {
        val r = handTo ?: return
        handTo = null
        removeCallbacks(handCheck)
        Log.i(TAG, "bar: hand-over to $r done ($why)")
    }
    private val handCheck = Runnable {
        if (handTo == null) return@Runnable
        when (RoomHandOver.Bar.onTimeout(docSince = holderAt > handAt, holds = barHolds)) {
            RoomHandOver.Bar.Step.DONE -> endHandOver("holder changed")
            else -> { freeBar("$handTo did not take the controller in ${RoomHandOver.Bar.MAX_MS} ms"); endHandOver("late free") }
        }
    }
    /** the bar's free (idempotent), checked once FREE_CHECK later as close()'s is */
    private fun freeBar(why: String) {
        val sent = tap(padSource(), GuestPad.cells(padSource(), ID, hold = false))
        if (sent) { freedAt = SystemClock.uptimeMillis(); freeRetried = false; removeCallbacks(freeCheck); postDelayed(freeCheck, FREE_CHECK) }
        Log.i(TAG, "bar: free ($why)${if (sent) "" else ", not sent"}")
    }

    /** a room change while the cursor is up ([r] from [shown]; a pick, a touch, any show()): the hand-over or the plain close */
    fun closeFor(r: Room, shown: Room) {
        if (!open) return
        val keep = RoomHandOver.Bar.keep(toHome = r == Room.HOME, same = r == shown, asked = holdAsked, holds = barHolds || heldReal)
        close(freeNow = !keep)
        if (keep) startHandOver(r)
    }

    /** the app goes away: Select is the game's again (sel off); it is asked for again when the app is back */
    fun appAway() {
        if (handTo != null) { freeBar("the app went away"); endHandOver("app away") }
        close()
        if (selOffCol >= 0 && tap(interactPad ?: padSource(), listOf("iact" to selOffCol))) Log.i(TAG, "bar: sel off (iact $selOffCol)")
        selAskedAt = 0L
    }

    fun appBack() { selAskedAt = 0L }

    /** {"type":"cappad","room":"bar","k":"PADBACK"} from a CURRENT document: Select with nobody holding opens the selector */
    fun onSelect(msg: JSONObject, blocked: Boolean) {
        val n = msg.optInt("n", -1)
        if (n == lastSelN) return
        lastSelN = n
        if (open) { close(); return }
        if (blocked || strip.place(Room.HOME) == null) return
        // the user: "Going left or right should skip past the middle home key": the cursor is never on Home; it starts on the room on
        // screen, or from Home on the first room right of it
        fromRoom = roomNow() != Room.HOME
        endHandOver("the cursor again")
        open = true; at = startOn(roomNow()); held = false; heldReal = false; lastPadN = -1
        syncHome()
        openedAt = SystemClock.uptimeMillis()
        holdAsked = tap(padSource(), GuestPad.cells(padSource(), ID, hold = true))
        retryHold()
        Log.i(TAG, "bar: open (Select n $n)")
        postDelayed(holdCheck, HOLD_WAIT)
        invalidate()
    }

    // nobody answered the hold: the selector would show with the D-pad still the game's, so it goes
    private val holdCheck = Runnable { if (open && !held) { Log.i(TAG, "bar: no hold answer, closed"); close() } }

    /**
     * A HOLD TAP CAN BE LOST (measured 2026-09-30 23:38-23:40: 4 of 10 keybind opens, each followed at once by the pad's `padstray`
     * -- the tap missed its cell -- and no answer at all; the cursor then stayed up without the controller and without its glyphs,
     * the user: "Sometimes when selecting left or right the button glass for the hom menu don't pop up"). Unanswered after HOLD_RETRY,
     * the hold is asked again, at most HOLD_TRIES times. Asking twice is harmless: the facility takes again for the same guest.
     */
    private var holdTries = 0
    private val holdRetry = object : Runnable {
        override fun run() {
            if (!open || held || !holdAsked) return
            if (holdTries >= HOLD_TRIES) { Log.i(TAG, "bar: hold never answered ($holdTries more tries)"); return }
            holdTries++
            val sent = tap(padSource(), GuestPad.cells(padSource(), ID, hold = true))
            Log.i(TAG, "bar: hold asked again ($holdTries)${if (sent) "" else ": not sent"}")
            postDelayed(this, HOLD_RETRY)
        }
    }
    private fun retryHold() { holdTries = 0; removeCallbacks(holdRetry); if (holdAsked) postDelayed(holdRetry, HOLD_RETRY) }

    // ---------------------------------------------------------------- the bar commands (Hub.lua hub4)

    /**
     * {"type":"barcmd","k":"left"|"right"|"open","n","boot"} from a CURRENT document: one press of Spells' macros "DS Bar Left /
     * Right / Open", which the player puts on any pad button (the user: "keybinds ... to navigate the dsforever homebar left or right").
     * left / right bring the cursor up on [shown] (the room on screen) and move it; with the selector already up they only move it.
     * open = the room under the cursor, as a touch on the bar would. Coming up, the command cursor ASKS FOR THE CONTROLLER as the guest
     * "bar", as Select does (the user: "When going left or right on the home bar a should select it and be should still go back"), so
     * A opens and B goes back; refused (a fight) it stays up without it, the buttons the game's. It goes after IDLE without a command or a
     * press, on A / B, and at once when a fight takes the controller; a room change closes it like any touch.
     */
    /**
     * The D-pad layout's DOWN (the user, 2026-10-05: "down to place the home bar selection down on the swirly to start"): the command
     * cursor comes up ON HOME'S DISC (the swirl), with the disc in its order, asking for the controller as a left / right command does.
     * Already up: the cursor goes to the disc.
     */
    /**
     * b98 (the user: "the animation for opening the home bar is really slow, sometimes I press down and then I want to go left or right
     * and I press buttons too fast before it loads"): the app's OWN reader saw the D-pad's Down on Home, so the bar comes up AT ONCE,
     * 60-170 ms before the game's command arrives (measured b97). The game's hub took the bar's hold in that same click (hub7), so the
     * presses that follow are the bar's: it takes them straight away ([early] counts as live). The command then only confirms it
     * ([openOnHome] sends the hold tap as before). No command within [EARLY_MS] (the game's router was not bare) = closed quietly.
     */
    fun openEarly(shown: Room) {
        if (open || shown != Room.HOME || strip.place(Room.HOME) == null) return
        byCommand = true
        fromRoom = true
        at = Room.HOME
        held = false; heldReal = false; lastPadN = -1; endHandOver("the cursor again")
        early = true
        open = true
        Log.i(TAG, "bar: open on Home EARLY (the app's own D-pad Down; the game's command to confirm)")
        removeCallbacks(earlyExpire); postDelayed(earlyExpire, EARLY_MS)
        removeCallbacks(idleClose); postDelayed(idleClose, IDLE)
        syncHome()
        invalidate()
    }
    /** opened by [openEarly] and not yet confirmed by the game's command */
    var early = false
        private set
    private val earlyExpire = Runnable {
        if (early && open) { Log.i(TAG, "bar: early open not confirmed by the game: closed"); early = false; close() }
    }

    fun openOnHome(shown: Room, blocked: Boolean) {
        if (open && early) {
            // the game's command confirms the early open: the hold is asked exactly as for a command open
            early = false
            removeCallbacks(earlyExpire)
            holdAsked = tap(padSource(), GuestPad.cells(padSource(), ID, hold = true))
            retryHold()
            Log.i(TAG, "bar: early open confirmed${if (holdAsked) ", hold asked" else ", no hold sent"} (shown $shown)")
            // the cursor stays where the early presses moved it (never snapped back to the disc)
            removeCallbacks(idleClose); postDelayed(idleClose, IDLE)
            syncHome()
            invalidate()
            return
        }
        if (!open) {
            if (blocked || strip.place(Room.HOME) == null) return
            byCommand = true
            fromRoom = true                      // the disc stays in the order (the cursor starts on it)
            at = Room.HOME
            held = false; heldReal = false; lastPadN = -1; endHandOver("the cursor again")
            open = true
            holdAsked = tap(padSource(), GuestPad.cells(padSource(), ID, hold = true))
            retryHold()
            Log.i(TAG, "bar: open on Home (D-pad down)${if (holdAsked) ", hold asked" else ", no hold sent"} (shown $shown)")
        } else {
            fromRoom = true; at = Room.HOME
        }
        removeCallbacks(idleClose); postDelayed(idleClose, IDLE)
        syncHome()
        invalidate()
    }

    fun onCommand(msg: JSONObject, shown: Room, blocked: Boolean) {
        val key = msg.optInt("boot", -1) to msg.optInt("n", -1)
        if (key == lastCmd) return
        lastCmd = key
        val k = msg.optString("k")
        Log.i(TAG, "bar: command $k n ${key.second}${if (open) " on $at" else " (closed)"}")
        when (k) {
            "left", "right" -> {
                if (!open) {
                    if (blocked) return
                    val start = if (strip.place(shown) != null) shown else Room.HOME
                    if (strip.place(start) == null) return
                    byCommand = true
                    fromRoom = shown != Room.HOME
                    at = start
                    held = false; heldReal = false; lastPadN = -1; endHandOver("the cursor again")
                    open = true
                    holdAsked = tap(padSource(), GuestPad.cells(padSource(), ID, hold = true))
                    retryHold()
                    Log.i(TAG, "bar: open (command)${if (holdAsked) ", hold asked" else ", no hold sent"}")
                }
                step(if (k == "left") -1 else 1)
                if (byCommand) { removeCallbacks(idleClose); postDelayed(idleClose, IDLE) }
                syncHome()
                invalidate()
            }
            "open" -> if (open) pick()
            // hub5, the D-pad layout: down = the cursor on the swirl here; map / chat / radial are the host's ([onDpad])
            "home" -> openOnHome(shown, blocked)
            // + "pause": Spells' router's Start on plain Home (the radial pause menu, the user 2026-10-08)
            // test24: every host key from ONE list (BarCommands; "sradial" was missing: the spell radial never opened)
            in BarCommands.HOST -> { if (open) close(); if (!blocked) onDpad?.invoke(k) }
            else -> Log.i(TAG, "bar: command $k has no route")
        }
    }

    /** the D-pad layout's map / chat / radial presses (hub5), once each (replays and repeats already dropped): the host opens them */
    var onDpad: ((String) -> Unit)? = null

    private val idleClose = Runnable { if (open && byCommand) { Log.i(TAG, "bar: command cursor idle, closed"); close() } }

    /** the room under the cursor opens (A, or the Open command); Home's own button flashes as pressed */
    private fun pick() {
        val r = at
        if (r == Room.HOME) strip.homePressed()
        // NO GAP: into another room the bar keeps its hold for that room's take to pre-empt (closeFor); Home / the same room free now
        closeFor(r, roomNow())
        Log.i(TAG, "bar: opens $r")
        onOpen(r)
    }

    // ---------------------------------------------------------------- the guest "bar"

    /** {"type":"guestack","id":"bar"} */
    fun onGuestAck(msg: JSONObject) {
        when (msg.optString("op")) {
            "hold" -> if (msg.optBoolean("ok") && open) {
                held = true
                heldReal = msg.optString("why") != "pending"      // queued / pending (Quests' "pending") is not holding yet
                Log.i(TAG, "bar: held")
                // B here only leaves the selector: the Home button must not promise Home
                tap(padSource(), GuestPad.bnext(padSource(), home = false))
                invalidate()        // a command cursor gains its L / R words and the A / B plate
            } else if (!msg.optBoolean("ok")) {
                // refused (a fight, a window): the facility would take it LATER, so the want is dropped now
                Log.i(TAG, "bar: hold refused (${msg.optString("why")})")
                if (open && byCommand) {
                    // the command cursor stays up without the controller (the buttons the game's); only the want goes
                    if (holdAsked) tap(padSource(), GuestPad.cells(padSource(), ID, hold = false))
                    holdAsked = false; held = false
                    invalidate()
                } else close()
            }
            "free", "leave" -> { held = false; heldReal = false }
        }
    }

    /** {"type":"guestpad","id":"bar"}: one press from a CURRENT document */
    fun onPad(msg: JSONObject) {
        if (msg.has("lost")) {
            // the capture let go by itself (a fight, an NPC window): the cursor goes at once, nothing left to free
            Log.i(TAG, "bar: lost (${msg.optString("lost")})")
            endHandOver("lost ${msg.optString("lost")}")          // "taken": the room's take pre-empted the bar (no gap)
            holdAsked = false
            close()
            return
        }
        val n = msg.optInt("n", -1)
        if (n == lastPadN) return
        lastPadN = n
        val k = msg.optString("k")
        Log.i(TAG, "bar: press $k n $n${if (open) " on $at" else " (closed)"}")
        if (!open) return
        held = true; heldReal = true                // only the holder gets presses
        if (byCommand) { removeCallbacks(idleClose); postDelayed(idleClose, IDLE) }
        // a COMMAND cursor was reached with ZL + ZR held: their releases arrive once the bar holds and would step it left then right
        // (a flicker; tracking's ruling). It ignores the triggers; the Select cursor keeps them (the user's item 23)
        if (byCommand && (k == "PADLTRIGGER" || k == "PADRTRIGGER")) return
        when (k) {
            // LATER item 23 (the user: "I want the L and R bumpers and triggers to move it as well"): LB / LT = left, RB / RT = right
            "PADDLEFT", "PADLSHOULDER", "PADLTRIGGER" -> step(-1)
            "PADDRIGHT", "PADRSHOULDER", "PADRTRIGGER" -> step(1)
            "PAD1" -> pick()
            // B on Home's disc (a cursor that came up in a room) goes Home as A does (the user 2026-10-01: "Yes, B on the Home disc
            // goes Home"); anywhere else B only closes the cursor. Select always closes
            "PAD2" -> if (at == Room.HOME && fromRoom) pick() else close()
            "PADBACK" -> close()
        }
        syncHome()
        invalidate()
    }

    /** leaves the selector (free, if it held or asked, unless [freeNow] is false: a hand-over, see closeFor); nothing when it is not open */
    fun close(freeNow: Boolean = true) {
        if (!open) return
        early = false
        removeCallbacks(earlyExpire)
        open = false
        byCommand = false
        Log.i(TAG, "bar: closed")
        removeCallbacks(holdCheck)
        removeCallbacks(idleClose)
        removeCallbacks(holdRetry)
        // only a cursor that asked for the controller has anything to free
        if (holdAsked && freeNow && tap(padSource(), GuestPad.cells(padSource(), ID, hold = false))) {
            // the free often goes right after the user's A (a pick): a tap then can be lost to the park point (padstray, 08:57:05 -
            // Bags' take was refused "busy" by this guest). The free is idempotent: checked once FREE_CHECK later, sent again if a
            // current interact document still says the bar holds
            freedAt = SystemClock.uptimeMillis(); freeRetried = false
            removeCallbacks(freeCheck); postDelayed(freeCheck, FREE_CHECK)
        }
        holdAsked = false
        held = false; heldReal = false
        syncHome()
        invalidate()
    }

    /** the bar's rooms as drawn, left to right: WITHOUT Home's disc when the cursor came up on Home (the user: "skip past the middle
     *  home key"), WITH it when it came up in a room (the user: the home button "should be an option to select to go back home") */
    private fun order(): List<Room> =
        Room.values().filter { it != Room.HOME || fromRoom }
            .mapNotNull { r -> strip.place(r)?.let { r to it.centerX() } }.sortedBy { it.second }.map { it.first }

    /** where a cursor starts on [r]: that room, or for Home (or a room not on the bar) the first room right of Home */
    private fun startOn(r: Room): Room {
        if (r != Room.HOME && strip.place(r) != null) return r
        val homeX = strip.place(Room.HOME)?.centerX() ?: return order().firstOrNull() ?: Room.HOME
        return order().firstOrNull { (strip.place(it)?.centerX() ?: 0f) > homeX } ?: order().lastOrNull() ?: Room.HOME
    }

    /** the direction last pushed (-1 / +1) and when: its point presses out for [PUSH_MS] (f-bar-cursor-polish) */
    private var pushedDir = 0
    private var pushedAt = -10_000L
    private val PUSH_MS = 180L

    private fun step(d: Int) {
        pushedDir = if (d < 0) -1 else 1; pushedAt = android.os.SystemClock.uptimeMillis()
        val all = order()
        if (all.isEmpty()) return
        val i = all.indexOf(at)
        if (i < 0) {
            // from Home (a command cursor that came up on Home): the nearest room on that side of it
            val homeX = strip.place(Room.HOME)?.centerX() ?: 0f
            val xs = all.map { strip.place(it)?.centerX() ?: 0f }
            at = if (d < 0) all.indices.lastOrNull { xs[it] < homeX }?.let { all[it] } ?: all.first()
                 else all.indices.firstOrNull { xs[it] > homeX }?.let { all[it] } ?: all.last()
            return
        }
        at = all[(i + d).coerceIn(0, all.size - 1)]
    }

    private fun tap(pad: JSONObject?, cells: List<Pair<String, Int>>?): Boolean =
        cells != null && pad != null && TapBridge.taps(context, pad, cells) { }

    // ---------------------------------------------------------------- drawing

    private val here = IntArray(2)
    private val there = IntArray(2)

    override fun onDraw(c: Canvas) {
        if (!open && !fadingOut) return
        // the bar's pictures are in the strip's own coordinates
        getLocationInWindow(here); strip.getLocationInWindow(there)
        val dx = (there[0] - here[0]).toFloat(); val dy = (there[1] - here[1]).toFloat()
        // on Home from Home: nothing (the skip; a command cursor that has not stepped yet); on Home from a room: Home's own disc is the
        // cursor (its gold breathing light, Strip.homeFocus) with the arrows and the A (A goes Home)
        val onHome = at == Room.HOME
        if (onHome && !fromRoom) return
        val box = strip.place(at) ?: return
        box.offset(dx, dy)
        // the room's picture gets the kit's golden box, the gold arrows with L / R on its sides (LATER 23: "maybe just includ L and R
        // inside of the arrows"), none toward an end of the bar (the cursor does not wrap; Home's disc is jumped over)
        val all = order(); val i = all.indexOf(at)
        if (!onHome) UiKit.focus(c, box)
        // holding the controller (Select, or a command cursor whose hold came through) the shoulders, A and B are the bar's: L / R in the
        // arrows, the A glyph on the selection (the user: "the A glyph should show on my current selection"), Home red with B
        // (Strip.barB), the A / B plate. A command cursor without it (refused in a fight): plain arrows (words = false), nothing else
        val ours = if (fadingOut && !open) !shotByCommand || shotHeld else !byCommand || held
        // f-bar-cursor-polish (the user, 15 Oct: "remove the L and R on the arrows ... a little more of a part of the golden cursor ...
        // button feedback to pushing a direction"): the points grow out of the gold box, no words; the side just pushed presses out
        val since = android.os.SystemClock.uptimeMillis() - pushedAt
        val push = if (since in 0 until PUSH_MS) 1f - since.toFloat() / PUSH_MS else 0f
        UiKit.cursorArrows(c, box, left = i > 0, right = i in 0 until all.size - 1,
            pushLeft = if (pushedDir < 0) push else 0f, pushRight = if (pushedDir > 0) push else 0f)
        if (push > 0f) postInvalidateOnAnimation()
        if (!ours) return
        val g = (box.height() * 0.46f).coerceIn(34f, 48f)
        UiKit.pad(c, aGlyph(), box.right - g * 0.30f, box.bottom - g * 0.30f, g)
        // no "A Open  B Back" plate any more (UI Art's ruling 2026-09-30): the A on the selection and the B on Home say it
    }

    /** while the bar HOLDS the controller Home shows its red B (B closes the cursor; the user: "it should show red with the B glyph"),
     *  Strip.barB - except when the cursor is ON Home's disc (from a room): then the disc is the cursor, gold (Strip.homeFocus) */
    private fun syncHome() {
        val onHome = open && at == Room.HOME && fromRoom
        // on Home's disc the disc is the cursor (gold, A or B goes Home); anywhere else, while held, Home shows red with B (B closes)
        strip.homeFocus = onHome
        strip.barB = open && held && !onHome
    }

    /** the game's A glyph in the layout the Settings chose (as UiKit.backGlyph does for B) */
    private fun aGlyph(): String = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> "ltr_a"
        CastSettings.ButtonLayout.PLAYSTATION -> "shp_cross"
        else -> "rev_a"
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    companion object {
        const val ID = "bar"
        const val TAG = HomeView.TAG
        private const val ASK_EVERY = 10_000L
        private const val HOLD_WAIT = 3_000L
        /** a hold normally answers in 0.15-0.35 s (measured): unanswered this long, it is asked again, at most HOLD_TRIES more times */
        private const val HOLD_RETRY = 450L
        /** a free's check: past the padstray window after the user's A (the hold retry's 450 ms) */
        private const val FREE_CHECK = 450L
        private const val HOLD_TRIES = 3
        /** the command cursor goes after this long without a bar command */
        private const val IDLE = 5_000L
        /** b98: an early open the game's command has not confirmed by now is closed (the game's router was not bare) */
        private const val EARLY_MS = 600L
    }
}
