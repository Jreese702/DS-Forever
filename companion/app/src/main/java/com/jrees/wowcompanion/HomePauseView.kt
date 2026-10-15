package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject

/**
 * THE RADIAL PAUSE MENU ON SCREEN (the user 2026-10-08; the rules in [HomePauseModel], the look UI Art's UiKit.pauseRadial): Start on
 * Home (Spells' router sends barcmd "pause" while nothing holds the controller) slides Home's panels out of the way, the bar's disc
 * rises to the middle and becomes the hub, and the game's own GamepadRadial opens round it. Close = the same run backward.
 *
 * INPUT, all read here while open (its own PadInput, as the consumables wheel): a stick lights a slice, letting it go OR A picks (the
 * user 2026-10-09, "A picks too", as our other wheels); B closes (on Emotes: back to the main page); LB / RB the pages; the right
 * stick's click drops the pick; D-pad Up = Emotes, Down = Sit / Stand (both on the release); Start closes. A finger works too.
 *
 * THE CONTROLLER: while open it holds the guest "radial" (the consumables wheel's; never both open: the router only sends "pause" when
 * no guest holds), so the game acts on none of it; Quests q34 adds Start to that guest's keys.
 *
 * WHAT A PICK DOES ([onPick], MainActivity): a ROOM opens as the bar opens it; View Buffs = Home's buff list; a GAME action = one tap
 * on Spells' "pause" row (answered by `pauseack`); the GAME MENU = OUR copy of the game's menu on the cleared stage ([GameMenuModel],
 * the user 2026-10-09; it replaced the one Escape press): its buttons tap Spells' secure "gamemenu" row or, with no clean route, send
 * the game's Escape; a greyed slice says "Coming later" and the wheel stays. The controller's release waits
 * [HomeRadialView.FREE_AFTER_USE_MS] behind an action's tap, as the consumables wheel's (the game takes one touch a frame: a free
 * right behind a tap was lost, b59 / b65, and the wheel's hold then stayed on).
 */
class HomePauseView(
    context: Context,
    private val strip: Strip,
    private val questsPad: () -> JSONObject?,
    private val guestTap: (List<Pair<String, Int>>?) -> Boolean,
) : View(context) {
    /** the Home panels' slide for this point of the motion (ms along the open; 0 = at rest) */
    var onSlide: (Float) -> Unit = {}
    /**
     * the wheel is gone (in the B frame itself); [from] = how far the panels / room had slid out: the host plays them back IN, the
     * open's reverse (the user 2026-10-09: "it needs to have a reverse animation for when the B button gets pressed"), input already
     * the room's / Home's
     */
    var onClose: (Float) -> Unit = { onSlide(0f) }
    /** how far the open had gone at the last frame (the reverse starts there) */
    private var slidT = 0f
    /** a ROOM or HOME pick, run once the wheel has folded away */
    var onPick: (HomePauseModel.Slice) -> Unit = {}

    private val flow = HomePauseModel.Flow()
    private var openedAt = 0L
    private var closingFrom = -1f
    private var closingAt = 0L
    private var hubPressAt = 0L
    private val pressCurve = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private var reader: PadInput? = null
    private var held = 0
    private var seeded = false
    /** HOLD: Start is still down from the press that opened the wheel (its release closes it) */
    private var startHeld = false
    /** opened by Start (HOLD's button); the bar's swirl opens it like a TAP (its A is let go at once) */
    private var openByStart = true
    private var lb = false
    private var rb = false
    private var aimStick = -1
    /** the red line under the wheel (a greyed slice, a refusal) and since when */
    private var line: String? = null
    private var lineAt = 0L
    /** the pick to run when the fold ends */
    private var after: HomePauseModel.Slice? = null
    /** PvP asks first: its first pick arms it (the line says so), a second within [CONFIRM_MS] changes the flag */
    private var pvpArmedAt = 0L
    private var holdTry = 0
    private var freeTry = 0
    @Volatile private var heldOk = false
    @Volatile private var freed = false
    /** the Game Menu on the cleared stage (null = the wheel): it keeps the wheel's hold on the controller */
    private var gm: GameMenuModel.Flow? = null
    /** Spells' last "gamemenu" document (the list the game would show; a running countdown) */
    private var gmDoc: JSONObject? = null
    private var gmPressed = -1
    /** the last game action's tap (uptime ms): the controller's release keeps [HomeRadialView.FREE_AFTER_USE_MS] behind it */
    private var lastActAt = 0L
    /** the Game Menu was picked: its Escape goes once the controller is let go (the free's answer, or [MENU_ESC_MS] at the latest) */
    private var menuPending = false

    val isOpen get() = openedAt != 0L

    init { visibility = GONE; isClickable = true }

    /** barcmd "pause" (Spells' router: Start with nothing holding): open; a second one while open closes */
    fun toggle() { if (isOpen && closingFrom < 0f) close() else if (!isOpen) open() }

    /** [fromT]: a reverse still running when Start came again: the open goes on from where the panels are, no jump */
    fun open(fromT: Float = 0f, byStart: Boolean = true) {
        if (isOpen) return
        openByStart = byStart
        LatLog.mark("wheel", "pause open")
        flow.reset(); flow.holdOpen = !HomeSettings.pauseTap; flow.pickA = HomeSettings.pausePickA      // its own pair (the user, 2026-10-09)
        flow.armMs = RadialModel.ARM_MS; flow.openAt = SystemClock.uptimeMillis(); stickWait = false; removeCallbacks(stickBack)
        afterStick = null
        line = null; after = null; pvpArmedAt = 0L; aimStick = -1; startHeld = false
        openedAt = SystemClock.uptimeMillis() - (fromT / SPEED).toLong(); closingFrom = -1f; hubPressAt = 0L
        held = 0; seeded = false; lb = false; rb = false
        strip.homeLifted = true
        visibility = VISIBLE
        hold(0)
        reader?.stop()
        reader = PadInput(
            onPad = { _, _, l, r -> pads(l, r) },
            // a reader that stops cannot see the stick come back: the wait ends at once (RC4: the wait is long now)
            onStatus = { ok, m -> if (!ok) { Log.i(TAG, "pause: controller reader: $m"); post { if (stickWait) stickBack.run() } } },
            onButtons = { h -> post { buttons(h) } },
            onStickVector = { st, x, y -> post {
                mag[st.coerceIn(0, 1)] = kotlin.math.hypot(x.toDouble(), y.toDouble())
                if (live()) vector(st, x.toDouble(), -y.toDouble()) else stickCheck()
            } },
        ).also { it.start() }
        Log.i(TAG, "pause: open")
        invalidate()
    }

    /**
     * GONE AT ONCE and the controller let go behind it (the user 2026-10-09: "when I press b this radial needs to INSTANTly be going
     * away- not taking seconds to think about it"): no fold, no hub press; Home's panels / the room snap back in the same frame; the
     * free (and its retries) follow invisibly. Every close: B, Start, the hub, a pick.
     */
    fun close() {
        if (!isOpen || closingFrom >= 0f) return
        gm = null; gmPressed = -1
        LatLog.mark("wheel", "pause gone")
        stickLater()
        done()
    }

    /** B / the hub: the hub plays the Home press while the wheel folds (the consumables wheel's b70 timing) */
    private fun pressHub() {
        if (!live()) return
        hubPressAt = SystemClock.uptimeMillis()
        close()
    }

    /** the app leaves the screen: gone at once, the hold freed */
    fun shut() {
        if (!isOpen) return
        guestTap(GuestPad.cells(questsPad(), GUEST, hold = false))
        after = null
        done()
    }

    private fun done() {
        if (!stickWait) { reader?.stop(); reader = null }
        openedAt = 0L; closingFrom = -1f; hubPressAt = 0L
        val from = slidT; slidT = 0f
        onClose(from)
        strip.homeLifted = false
        // the Game Menu's "Press Start" stays on its own (touches pass through) until it runs out
        visibility = if (menuHint()) VISIBLE else GONE
        // a ROOM picked with the stick still pushed waits for it too (RC3): the room's own hold would end the wheel's stick catch at once
        after?.let { s -> after = null; if (stickWait && s.kind == HomePauseModel.Kind.ROOM) afterStick = s else onPick(s) }
    }

    private fun live() = isOpen && closingFrom < 0f

    /**
     * THE STICK GOES BACK ONLY FROM NEUTRAL (the user 2026-10-09): after a pick / close the wheel keeps the controller (its hold) until the
     * stick is back inside [RadialModel.NEUTRAL], or [RadialModel.STICK_BACK_MS] pass, so the leftover aim never walks the character or
     * moves the room's cursor. The wheel itself is already gone.
     */
    private val mag = DoubleArray(2)
    private var stickWait = false
    private val stickBack = Runnable { if (stickWait) { stickWait = false; Log.i(TAG, "pause: stick back (time)"); LatLog.mark("wheel", "pause stick back (time)"); free(0); reader?.stop(); reader = null; pickAfterStick() } }
    /** a room pick waiting for the stick ([done]) */
    private var afterStick: HomePauseModel.Slice? = null
    private fun pickAfterStick() { afterStick?.let { s -> afterStick = null; LatLog.mark("wheel", "room pick after the stick"); onPick(s) } }
    private fun stickNeutral() = mag[0] < RadialModel.NEUTRAL && mag[1] < RadialModel.NEUTRAL
    private fun stickLater() {
        if (stickNeutral()) { free(0); return }
        stickWait = true
        postDelayed(stickBack, RadialModel.STICK_BACK_MS)
    }
    private fun stickCheck() {
        if (!stickWait || !stickNeutral()) return
        stickWait = false; removeCallbacks(stickBack)
        Log.i(TAG, "pause: stick back (neutral)")
        LatLog.mark("wheel", "pause stick back (neutral)")
        free(0); reader?.stop(); reader = null
        pickAfterStick()
    }

    private fun hold(attempt: Int) {
        heldOk = false
        val mine = ++holdTry
        val sent = guestTap(GuestPad.cells(questsPad(), GUEST, hold = true))
        Log.i(TAG, "pause: hold${if (attempt > 0) " again ($attempt)" else ""} ${if (sent) "sent" else "NOT sent"}")
        postDelayed({ if (mine == holdTry && !heldOk && live() && attempt < 2) hold(attempt + 1) }, HomeRadialView.HOLD_CHECK_MS)
    }

    private fun free(attempt: Int) {
        // right after a game action's tap the release waits (the game takes one touch a frame; see the class note)
        val rest = HomeRadialView.FREE_AFTER_USE_MS - (SystemClock.uptimeMillis() - lastActAt)
        if (rest > 0) { val token = ++freeTry; postDelayed({ if (token == freeTry && !live()) free(attempt) }, rest); return }
        freed = false
        val mine = ++freeTry
        val sent = guestTap(GuestPad.cells(questsPad(), GUEST, hold = false))
        Log.i(TAG, "pause: free${if (attempt > 0) " again ($attempt)" else ""} ${if (sent) "sent" else "NOT sent"}")
        postDelayed({ if (mine == freeTry && !freed && attempt < 4 && !live()) free(attempt + 1) }, HomeRadialView.FREE_CHECK_MS)
    }

    /** the Game Menu's line after the wheel has gone (since when, its words), 0 = none */
    private var hintAt = 0L
    private var hintText = ""
    private fun menuHint() = hintAt != 0L && SystemClock.uptimeMillis() - hintAt < HomePauseModel.MENU_ARM_MS
    /** a room change or the app leaving ends the line */
    fun clearHint() { if (hintAt != 0L) { hintAt = 0L; if (!isOpen) visibility = GONE } }

    /** the game's answers for the guest "radial" while this wheel is the one open (MainActivity's guestack route) */
    fun onGuestAck(msg: JSONObject) {
        if (msg.optString("op") == "hold" && msg.optBoolean("ok")) heldOk = true
        if (msg.optString("op") == "free" && msg.optBoolean("ok")) { freed = true; menuEscape("the controller let go") }
        Log.i(TAG, "pause: guest ${msg.optString("op")} ${if (msg.optBoolean("ok")) "ok" else "refused (${msg.optString("why")})"}")
    }

    /** Spells' "gamemenu" document (r12): the menu's list and the game's countdown; the countdown's end by a log out closes it */
    fun onGameMenuDoc(msg: JSONObject) {
        gmDoc = msg
        val m = gm ?: return
        m.update(msg)
        Log.i(TAG, "pause: game menu doc ${msg.optString("why")}" + (m.count?.let { " ${it.kind} ${it.left}" } ?: "") +
            (msg.optString("ended").takeIf { it.isNotEmpty() }?.let { " ended $it" } ?: ""))
        if (msg.optString("ended") == "gone") close()            // the log out / exit went through: the world is closing
        invalidate()
    }

    /** Spells' answer to a "gamemenu" cell */
    fun onGameMenuAck(msg: JSONObject) {
        Log.i(TAG, "pause: game menu ${msg.optString("cmd")} ${if (msg.optBoolean("ok", true)) "ok" else "refused (${msg.optString("why")})"}")
        if (!msg.optBoolean("ok", true) && gm != null) say("The game refused it")
    }

    /** Spells' answer to a game action: a refusal shows on the line ("not here" for an emote the game restricts) */
    fun onAck(msg: JSONObject) {
        val ok = msg.optBoolean("ok", true) && !msg.optBoolean("restricted")
        Log.i(TAG, "pause: ${msg.optString("cmd")} ${if (ok) "done" else "refused (${msg.optString("why")})"}")
        if (!ok && live()) say(if (msg.optBoolean("restricted")) "Not here" else "The game refused it")
    }

    /** the preview only (tools/homepreview): drive the wheel's state by hand (a page, an aim, an option held, a line) */
    fun preview(block: HomePauseModel.Flow.() -> Unit, say: String? = null) {
        flow.block()
        if (say != null) say(say)
        invalidate()
    }

    private fun say(s: String) { line = s; lineAt = SystemClock.uptimeMillis(); invalidate() }

    private fun pads(l: Boolean, r: Boolean) {
        post {
            if (live() && gm == null) {
                if (l && !lb && flow.turn(-1)) invalidate()
                if (r && !rb && flow.turn(+1)) invalidate()
            }
            lb = l; rb = r
        }
    }

    private fun buttons(h: Int) {
        if (!live()) { held = h; return }
        // the reader's first report is the state it found: Start still down from the press that opened the wheel is no press.
        // HOLD: Start must still be held (a quick press closes it again, as the consumables wheel with Up)
        if (!seeded) {
            seeded = true; held = h
            startHeld = PadInput.isHeld(h, PadInput.SLOT_START)
            if (!startHeld && flow.holdOpen && openByStart && gm == null) close()
            return
        }
        if (startHeld && !PadInput.isHeld(h, PadInput.SLOT_START)) {
            startHeld = false; held = h
            if (gm == null) act(flow.startReleased())
            invalidate(); return
        }
        val down = h and held.inv()
        val up = held and h.inv()
        held = h
        fun pressed(slot: Int) = PadInput.isHeld(down, slot)
        fun released(slot: Int) = PadInput.isHeld(up, slot)
        gm?.let { m ->
            // THE GAME MENU: D-pad up / down move (wrapping), A presses, B returns to the game (on the countdown: B = Cancel, the game's)
            when {
                pressed(PadInput.SLOT_START) -> close()
                pressed(PadInput.SLOT_FACE_RIGHT) -> gmAct(if (m.count != null) GameMenuModel.Act.Cancel else m.b())
                pressed(PadInput.SLOT_FACE_BOTTOM) -> gmAct(m.a(SystemClock.uptimeMillis()))
                pressed(PadInput.SLOT_DPAD_UP) -> m.move(-1)
                pressed(PadInput.SLOT_DPAD_DOWN) -> m.move(+1)
            }
            invalidate(); return
        }
        when {
            pressed(PadInput.SLOT_START) -> { close(); return }
            pressed(PadInput.SLOT_FACE_RIGHT) -> { if (flow.b() == HomePauseModel.Act.Close) pressHub() else invalidate(); return }
            pressed(PadInput.SLOT_RSTICK) -> { flow.cancel(); invalidate() }
            pressed(PadInput.SLOT_FACE_BOTTOM) -> act(flow.a())
        }
        for ((slot, dir) in listOf(PadInput.SLOT_DPAD_UP to 0, PadInput.SLOT_DPAD_DOWN to 2)) {
            if (pressed(slot)) act(flow.dpad(dir, true))
            if (released(slot)) act(flow.dpad(dir, false))
        }
        invalidate()
    }

    private fun vector(st: Int, x: Double, y: Double) {
        if (gm != null) return                     // the Game Menu: the D-pad moves, the sticks do nothing (the game's)
        // either stick aims (the consumables wheel's rule, the user: "they can both aim"); only the stick that aims can let go
        val m = kotlin.math.hypot(x, y)
        if (aimStick != st) {
            if (m < RadialModel.DEAD_IN) return
            aimStick = st
        }
        val before = flow.pointed
        val a = flow.vector(x, y, SystemClock.uptimeMillis())
        if (m < RadialModel.DEAD_OUT) aimStick = -1
        if (flow.pointed != before) { line = null; invalidate() }
        act(a)
    }

    private fun act(a: HomePauseModel.Act) {
        when (a) {
            HomePauseModel.Act.None -> {}
            HomePauseModel.Act.Close -> pressHub()
            HomePauseModel.Act.Sit -> game("sit", stay = true)
            is HomePauseModel.Act.Refuse -> say(HomePauseModel.LATER_LINE)
            is HomePauseModel.Act.Pick -> pick(a.slice)
        }
        invalidate()
    }

    private fun pick(s: HomePauseModel.Slice) {
        Log.i(TAG, "pause: pick ${s.key}${s.cmd?.let { " ($it)" } ?: ""}")
        when (s.kind) {
            HomePauseModel.Kind.GAME -> {
                val cmd = s.cmd ?: return
                if (s.confirm) {
                    val now = SystemClock.uptimeMillis()
                    if (now - pvpArmedAt > CONFIRM_MS) { pvpArmedAt = now; say("Pick ${s.label} again to change your PvP flag"); return }
                    pvpArmedAt = 0L
                }
                game(cmd, stay = false)
            }
            HomePauseModel.Kind.ROOM, HomePauseModel.Kind.HOME -> { after = s; close() }
            HomePauseModel.Kind.LATER -> say(HomePauseModel.LATER_LINE)
        }
    }

    /** one tap on Spells' "pause" row; [stay] = the wheel stays open (Sit / Stand, as the game's). The Game Menu: OUR menu */
    private fun game(cmd: String, stay: Boolean) {
        if (cmd == "menu") { openMenu(); return }
        val col = HomePauseModel.col(cmd)
        val sent = col >= 0 && TapBridge.tap(context, questsPad(), HomePauseModel.ROW, col) { why -> Log.i(TAG, "pause: tap refused ($why)") }
        Log.i(TAG, "pause: $cmd ${if (sent) "sent" else "NOT sent (no pause row yet?)"}")
        if (!sent) { say("Comes with the next add-on update"); return }
        lastActAt = SystemClock.uptimeMillis()
        if (!stay) close()
    }

    /** the Game Menu on the cleared stage: the last known list at once, Spells' fresh one follows the info tap */
    private fun openMenu() {
        // a countdown is only shown from a FRESH document (the kept one may carry an old count)
        gm = GameMenuModel.Flow(GameMenuModel.items(gmDoc))
        gmPressed = -1; line = null
        val sent = TapBridge.tap(context, questsPad(), GameMenuModel.ROW, GameMenuModel.COL_INFO) { why -> Log.i(TAG, "pause: game menu info refused ($why)") }
        Log.i(TAG, "pause: game menu open (info ${if (sent) "asked" else "NOT asked: no gamemenu row yet"})")
        invalidate()
    }

    /** a Game Menu input's route ([GameMenuModel.Act]) */
    private fun gmAct(a: GameMenuModel.Act) {
        when (a) {
            GameMenuModel.Act.None -> {}
            GameMenuModel.Act.Close -> close()
            is GameMenuModel.Act.Ask -> {}           // the question shows under the menu (gm.askLine)
            is GameMenuModel.Act.Cell -> {
                val sent = TapBridge.tap(context, questsPad(), GameMenuModel.ROW, a.item.col) { why -> Log.i(TAG, "pause: game menu tap refused ($why)") }
                Log.i(TAG, "pause: game menu ${a.item.key} ${if (sent) "sent" else "NOT sent"}")
                if (!sent) { say("Comes with the next add-on update"); return }
                lastActAt = SystemClock.uptimeMillis()
                // Log Out / Exit stay for the game's countdown (Spells' document); Macros / Edit Mode open the game's own window: go
                if (a.item.key != "logout" && a.item.key != "quit") close()
            }
            is GameMenuModel.Act.Escape -> {
                // no clean route (the user's "Escape fallback"): the menu folds, the controller goes, the game's own menu opens on top
                Log.i(TAG, "pause: game menu ${a.item.key} -> the game's own menu (Escape)")
                menuPending = true
                close()
                postDelayed({ menuEscape("no answer to the free in $MENU_ESC_MS ms") }, MENU_ESC_MS)
            }
            GameMenuModel.Act.Cancel -> {
                val sent = TapBridge.tap(context, questsPad(), GameMenuModel.ROW, GameMenuModel.COL_CANCEL) { why -> Log.i(TAG, "pause: game menu cancel refused ($why)") }
                Log.i(TAG, "pause: game menu cancel ${if (sent) "sent" else "NOT sent"}")
                if (sent) lastActAt = SystemClock.uptimeMillis()
            }
        }
        invalidate()
    }

    /** the Game Menu's ONE Escape press (GameInput: closes a chat box first if one is open), once */
    private fun menuEscape(why: String) {
        if (!menuPending) return
        menuPending = false
        val sent = GameInput.escape()
        Log.i(TAG, "pause: menu Escape ${if (sent) "sent" else "NOT sent"} ($why)")
        if (!sent) { hintText = "Couldn't reach the game"; hintAt = SystemClock.uptimeMillis(); if (!isOpen) visibility = VISIBLE; invalidate() }
    }

    /** the preview only: draw the motion frozen at this t (ms along the open), -1 = live */
    var previewT = -1f

    private fun tNow(): Float {
        if (previewT >= 0f) return previewT
        val now = SystemClock.uptimeMillis()
        // smoothness D (the user 2026-10-08): the kit's 600 / 380 ms motion plays in 420 / 266 ms (SPEED), its choreography unchanged
        if (closingFrom >= 0f) return closingFrom - (now - closingAt) * (UiKit.PAUSE_OPEN_MS / UiKit.PAUSE_CLOSE_MS) * SPEED
        return (now - openedAt) * SPEED
    }

    private fun hubDown(): Float {
        if (hubPressAt == 0L) return 0f
        val dt = (SystemClock.uptimeMillis() - hubPressAt).toFloat()
        if (dt < UiKit.CR_PRESS_IN_MS) return dt / UiKit.CR_PRESS_IN_MS
        return 1f - pressCurve.getInterpolation(((dt - UiKit.CR_PRESS_IN_MS) / UiKit.CR_PRESS_OUT_MS).coerceIn(0f, 1f))
    }

    private val pHint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 34f; color = Theme.gold; textAlign = android.graphics.Paint.Align.CENTER }

    private fun slices(): List<UiKit.PauseSlice?> = flow.slices().map { it?.let { s -> UiKit.PauseSlice(s.key, s.label, s.enabled) } }

    override fun onDraw(c: Canvas) {
        if (!isOpen) {
            // the Game Menu's line alone, where the game's red line stands (UiKit.pauseRadial's y), then gone
            if (!menuHint()) { if (hintAt != 0L) { hintAt = 0L; visibility = GONE }; return }
            UiKit.asBody(pHint)
            c.drawText(hintText, width / 2f, HINT_Y, pHint)
            postInvalidateDelayed(250L)
            return
        }
        val t = tNow()
        if (closingFrom >= 0f && t <= 0f) { done(); return }
        gm?.let { m ->
            // THE GAME MENU on the cleared stage (Home's panels stay out, the wheel is gone)
            onSlide(UiKit.PAUSE_OPEN_MS)
            val screen = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val cnt = m.count
            if (cnt != null) UiKit.gameCountdown(c, screen, cnt.kind == "quit", cnt.left, 0, gmPressed)   // one Cancel: never the game's "Exit now" (ForceQuit)
            else {
                UiKit.gameMenu(c, screen, m.rows.map { it?.let { r -> UiKit.GameMenuItem(r.label, r.on) } }, m.cursor, gmPressed)
                val ask = m.askLine(SystemClock.uptimeMillis()) ?: line
                if (ask != null) { UiKit.asBody(pHint); c.drawText(ask, width / 2f, height - ASK_Y_UP, pHint); postInvalidateDelayed(250L) }
            }
            return
        }
        val tt = t.coerceIn(0f, UiKit.PAUSE_OPEN_MS)
        onSlide(tt); slidT = tt
        // the game's red error line: held ~2 s, then 0.5 s out (UIErrorsFrame)
        val lineFor = SystemClock.uptimeMillis() - lineAt
        if (line != null && lineFor > LINE_MS + LINE_FADE_MS) line = null
        val lineAlpha = if (lineFor <= LINE_MS) 1f else (1f - (lineFor - LINE_MS).toFloat() / LINE_FADE_MS).coerceIn(0f, 1f)
        val screen = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val (disc, size) = discHere()
        val moving = UiKit.pauseRadial(c, screen, disc, size, slices(), flow.page, flow.pointed, flow.heldOption, tt, hubDown(), line,
            lineAlpha = lineAlpha, pickGlyph = flow.pickA, left = HomePauseModel.TOOL_WORD)
        // the open runs to its end whatever the kit says (its "moving" covers the wheel only, the panels slide too)
        if (moving || tt < UiKit.PAUSE_OPEN_MS || closingFrom >= 0f || hubPressAt != 0L || line != null) postInvalidateOnAnimation()
    }

    /** the bar's Home disc, in this view's coordinates: its middle and its width (the hub rises from it) */
    private fun discHere(): Pair<PointF, Float> {
        val r = strip.place(Room.HOME) ?: return PointF(width / 2f, height - 80f) to 140f
        val a = IntArray(2); val b = IntArray(2)
        strip.getLocationOnScreen(a); getLocationOnScreen(b)
        // the bar slides down meanwhile; the disc rises from where it stood at rest
        return PointF(r.centerX() + a[0] - b[0], r.centerY() + a[1] - b[1] - strip.translationY) to r.width()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isOpen) return false           // the Game Menu's line takes no touch
        if (!live()) return true
        gm?.let { m ->
            val screen = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val cnt = m.count
            val hit = if (cnt != null) UiKit.gameCountdownHit(screen, cnt.kind == "quit", e.x, e.y)
                      else UiKit.gameMenuHit(screen, m.rows.map { it?.let { r -> UiKit.GameMenuItem(r.label, r.on) } }, e.x, e.y)
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { gmPressed = if (cnt == null && hit >= 0) hit else -1; invalidate() }
                MotionEvent.ACTION_UP -> {
                    gmPressed = -1
                    when {
                        // the countdown: its one Cancel (the exit's "Exit now" is never drawn: no ForceQuit from our side)
                        cnt != null -> if (hit == 0) gmAct(GameMenuModel.Act.Cancel)
                        hit == UiKit.GAME_MENU_BACK -> close()
                        hit >= 0 -> gmAct(m.a(SystemClock.uptimeMillis(), hit))
                    }
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { gmPressed = -1; invalidate() }
            }
            return true
        }
        if (e.action == MotionEvent.ACTION_UP) {
            val screen = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val opt = UiKit.pauseOptionHit(screen, e.x, e.y)
            val turn = UiKit.pausePageHit(screen, e.x, e.y)
            when {
                // a tap on "Emotes" / "Sit / Stand" = that D-pad option pressed and let go; on LB / RB = the page
                opt >= 0 -> { act(flow.dpad(opt, true)); act(flow.dpad(opt, false)) }
                turn != 0 -> flow.turn(turn)
                else -> {
                    val s = UiKit.pauseRadialHit(screen, e.x, e.y)
                    act(flow.touch(if (s == UiKit.PAUSE_HUB) HomePauseModel.HUB else s))
                }
            }
            invalidate()
        }
        return true        // the wheel takes every touch while it shows
    }

    companion object {
        const val TAG = "WCHOME"
        const val GUEST = "radial"
        const val CONFIRM_MS = 4_000L
        /** the Game Menu's Escape goes this long after the pick at the latest (the free's answer took 90-370 ms on b65) */
        const val MENU_ESC_MS = 600L
        /** the user 2026-10-09: "the animation for it needs to be WAAAAAAY faster": the kit's 600 ms open plays in 120 ms */
        const val SPEED = 600f / 120f
        const val HINT_Y = 64f
        /** the Game Menu's question ("Log Out? Press A again") above the bottom edge */
        const val ASK_Y_UP = 60f
        const val LINE_MS = 2_000L
        const val LINE_FADE_MS = 500L
    }
}
