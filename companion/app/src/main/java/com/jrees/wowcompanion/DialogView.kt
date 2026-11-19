package com.jrees.wowcompanion

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * THE GAME'S DIALOGS on the bottom screen: release spirit, resurrect, invites, duels, the yes / no
 * confirmations. First slice of the user's end goal (2026-09-28): every window the game opens
 * appears on the bottom screen.
 *
 * A MIRROR (user: "Mirror, bottom screen only"). The controller stays with the game's own dialog
 * code; this view shows that same dialog, with the same buttons in the same order, each with the
 * glyph of the key the GAME gives it (`key`, read from the game by the addon, never assumed from a
 * button's number or place). A touch is one tap on a secure cell = one click on the game's button.
 *
 * THE SIGN OF LIFE. The addon makes the top-screen dialog invisible only while this view says that
 * it shows it: one tap on the `alive` cell when a dialog has been drawn, then every `life.every`
 * seconds. If this view stops (closed, crashed, another app in front), the dialog is back on the
 * top screen within seconds, by itself.
 *
 * THE LOOK is UI Art's (wow-companion-design/dialogs/SPEC.md part 1), with the buttons in the
 * game's own order from left to right (user, 2026-09-28: "like how the wow UI has it").
 */
class DialogView(context: Context) : View(context) {

    private class Btn(val k: Int, val label: String, val on: Boolean, val key: String?, val cell: Int)
    private class Dlg(
        val i: Int, val which: String, val text: String, val sub: String?, val buttons: List<Btn>,
        val extra: String?, val extraKey: String?, val extraCell: Int, val x: Boolean, val xCell: Int,
        val left: Int, val dur: Float, val bar: Boolean, val delay: Int, val alert: String?,
        val item: JSONObject?, val money: Long, val focus: Boolean, val top: String?, val hid: Boolean,
        val fkey: String?, val safe: Int = 0, val never: Int = 0, val roll: Int = -1)

    private var doc: JSONObject? = null
    private var docGt = -1L
    private var list: List<Dlg> = emptyList()
    private var sig = ""                       // which dialogs, which buttons: a change locks taps for a moment
    private var sigAt = 0L
    private var lastTapAt = 0L
    private var pressed = ""
    private var noteText: String? = null
    private var noteAt = 0L
    private val hits = ArrayList<Pair<RectF, String>>()
    private var lifeAt = 0L                    // when the last sign of life was sent
    private var drawnSig = ""                  // the dialogs that have really been drawn
    private var lifeSig = ""                   // the dialogs and the rolls shown: a change = a sign of life at once
    // ---- LOOT ROLLS (Dialogs d12; loot-pop: [RollBook] - one press = one roll, the next one at once, the game's word corrects)
    private val book = RollBook()
    /** the rolls on screen (the answered ones gone at once; a bind-on-pickup one showing the game's confirm) */
    private val rolls: List<RollBook.Roll> get() = book.shown()
    private var rollsMore = 0
    private var rollsAt = 0L                   // when the rolls' seconds were read
    private var rollGameSel = -1               // the roll the game has chosen (its D-pad)
    private var rollPick = -1                  // a finger's choice of card, the app's alone (the game's is never written)
    private var gameGuess = -1                 // after a controller answer out of a fight: the roll the game's frame takes next (RemoveRoll)

    // ---- PRESS FEEDBACK + CLOSE ON SELECT (the user, 2026-10-07: "the buttons don't really look like they are always getting
    // pressed ... missing a feedback layer" / "I dont like when the context menu stays open after making a selection (the spirit
    // release ... keeps counting down when the top screen is loading)"). A press shows the button DOWN at once (a finger, or the
    // controller's A / B / X / Y read from Home's held mask), for at least PRESS_MIN_MS; then the dialog FOLDS away without waiting
    // for the game. A document written FOLD_REFUSE_MS after the choice that still lists it = the game did not take it: it comes back.
    private var padMask = 0
    private var padHit = ""                    // the button the controller holds down
    private var lingerHit = ""                 // the last choice keeps its DOWN look until lingerUntil
    private var lingerUntil = 0L
    private var foldKey: String? = null        // the dialog folding / folded away ("<i>:<which>")
    private var foldAt = 0L                    // when its fold starts (after the press is seen)
    private var foldGt = -1L                   // the game clock at the choice

    private fun keyOf(d: Dlg) = "${d.i}:${d.which}"
    private fun isDown(hit: String) =
        pressed == hit || padHit == hit || (lingerHit == hit && System.currentTimeMillis() < lingerUntil)
    private fun downStarts(prefix: String) =
        pressed.startsWith(prefix) || padHit.startsWith(prefix) || (lingerHit.startsWith(prefix) && System.currentTimeMillis() < lingerUntil)

    /** a choice was made on [d]: keep its button DOWN a moment, then fold it away */
    private fun chose(hit: String, d: Dlg?) {
        val now = System.currentTimeMillis()
        lingerHit = hit; lingerUntil = now + PRESS_MIN_MS + FOLD_MS     // DOWN through the fold too
        if (d != null) { foldKey = keyOf(d); foldAt = now + PRESS_MIN_MS; foldGt = gameNow() }
        postInvalidateOnAnimation()
        postDelayed(ticker, PRESS_MIN_MS + FOLD_MS + 20L)
    }

    /** the fold's progress 0..1 for [d] (0 = not folding); >= 1 = folded away */
    private fun foldOf(d: Dlg): Float {
        if (foldKey != keyOf(d)) return 0f
        val t = System.currentTimeMillis() - foldAt
        return if (t <= 0L) 0f else (t.toFloat() / FOLD_MS)
    }

    /**
     * loot-pop review: Home's held mask while this view reads no press ([padTakes] false: the fight's small panel, the pill, nothing up).
     * The presses are the EDGES from the last mask seen, so it must stay fresh: a button held through the small panel's grow (a fight's
     * end: the player still on an ability) was a phantom press of the window (A Need / B Pass / X Greed on the game's roll, tapped
     * 400 ms later), and the release of the press that answered the last roll (gone at once, the view no longer reading) stayed "held"
     * and swallowed the next press of that button - a press made twice.
     */
    fun padSeen(mask: Int) {
        padMask = mask
        if (padHit.isNotEmpty() && PAD_SLOTS.none { (slot, key) -> key == padKey && PadInput.isHeld(mask, slot) }) {
            padHit = ""; postInvalidateOnAnimation()
        }
    }

    /** the host: this view reads the controller now - its box, its full roll window, or the small panel's bind confirm the GAME's pop-up
     *  has the controller's focus for (A Okay / B Cancel are the game's then, as on its own screen: one press, one answer) */
    fun padTakes(): Boolean = covers() || focusedConfirm() != null

    /** the fight's small panel: the roll showing the game's bind confirm whose pop-up has the GAME's controller focus (CONFIRM_LOOT_ROLL
     *  is not one the game skips: it takes A / B in a fight too, while we draw it on the row) */
    private fun focusedConfirm(): RollBook.Roll? {
        if (!showing() || rollMode() != RollMode.COMPACT) return null
        val r = confirmRoll() ?: return null
        return if (list.any { it.roll == r.id && it.focus }) r else null
    }

    /** the full window is not readable yet - just grown from the small panel (a fight's end, the player's open) or not drawn as the window
     *  at all: a press now was the fight's, never a roll (its touches are not taken then either) */
    private fun growing(): Boolean = drawnMode != RollMode.FULL || drawClock() - growAt < GROW_MS

    /** Home's controller reader: the held mask (display only; the game itself answers the press) */
    fun onPadHeld(mask: Int) {
        val down = mask and padMask.inv()
        padMask = mask
        var changed = false
        for ((slot, key) in PAD_SLOTS) {
            if (!PadInput.isHeld(mask, slot)) {
                if (padHit.isNotEmpty() && padKey == key) { padHit = ""; changed = true }
                continue
            }
            if (!PadInput.isHeld(down, slot)) continue
            val d = active()
            // combat_nav (the user's decision 1, 2026-11-11: pop-ups and loot rolls in a fight "should be built on the bottom screen"): while
            // the app has the pad in a fight (PadOwnerSync's room mode) the game never sees the press, so it answers the mirror at once
            if (appOwnsPad()) { if (fightPress(key, d)) changed = true; continue }
            if (d != null) {
                // the game answers only the dialog it focuses, with the key it gives each button
                if (!d.focus || foldOf(d) > 0f) continue
                val delayed = secondsOf(d.delay) > 0
                val b = d.buttons.firstOrNull { it.key == key && it.on && !(delayed && it.k == 1) && DialogPad.mayPress(d.which, it.k, finger = false, never = d.never) }   // camp-exit merge: a key on Exit Now (no guard yet) is never ours
                val hit = when {
                    b != null -> "b${b.k}"
                    d.extra != null && d.extraKey == key -> "extra"
                    else -> null
                } ?: continue
                padHit = hit; padKey = key; changed = true
                // b-release-spirit: the controller's press is the GAME's to answer first (nothing is tapped now); if a document written
                // after it still lists this dialog, the game did not take it, and its cell is tapped then ([padRefused])
                android.util.Log.i("WCDLG", "controller $key on '$hit' of dialog ${d.i} ${d.which}: left to the game first")
                padPress = PadPress(keyOf(d), hit, gameNow())
                chose(hit, d)
                pokeSoon()
            } else if (rolls.isNotEmpty() && ((rollMode() == RollMode.FULL && !growing()) || focusedConfirm() != null)) {
                // the rolls OUT OF A FIGHT: the game keeps the controller (A Need, X Greed / Transmog, B Pass) on ITS selected roll, and
                // A Okay / B Cancel on its bind confirm. loot-pop: the answer shows AT ONCE (RollBook), the game's first: its cell is
                // tapped only if a document a moment later still offers what the press answered. (In a fight's small panel the
                // controller is the fight's: nothing here - but A / B on a bind confirm the game's pop-up has the focus for, review.)
                val r = confirmRoll() ?: gameRoll() ?: continue
                if (book.rowOf(r) == RollBook.Row.CONFIRM) {
                    val ok = when (key) { "PAD1" -> true; "PAD2" -> false; else -> null } ?: continue
                    if (book.confirm(r.id, ok, gameNow(), gameFirst = true)) {
                        padHit = "ask:${r.id}:" + (if (ok) "ok" else "cancel"); padKey = key; changed = true
                        android.util.Log.i("WCDLG", "controller $key on roll ${r.id}'s confirm (${if (ok) "Okay" else "Cancel"}): the game's first")
                        rollTaps(); pokeSoon()
                    }
                    continue
                }
                val kind = DialogPad.rollKind(key, r.need, r.greed, r.transmog) ?: continue
                val ids = rolls.map { it.id }
                if (!book.answer(r.id, kind, gameNow(), gameFirst = true)) continue
                padHit = "roll:${r.id}:$kind"; padKey = key; changed = true
                android.util.Log.i("WCDLG", "controller $key: $kind on roll ${r.id}: shown at once, the game's first")
                gameGuess = RollBook.nextAfter(ids, r.id) ?: -1; rollPick = -1
                pokeSoon()
            }
        }
        if (changed) postInvalidateOnAnimation()
    }
    private var padKey = ""

    /** set by the host: the app has the pad in a fight (the router delivers to us, PadOwnerSync's room mode) */
    var appOwnsPad: () -> Boolean = { false }

    /** set by the host: nothing of the add-on's holds the pad but the app's own stray-ENTER holder ("app") - a room or a loot window
     *  that holds gets every press through the add-on, so the rolls must not answer the same press (the review of 2026-11-11) */
    var rollsOwnPad: () -> Boolean = { true }

    /** a press while the app owns the pad in a fight: A / B on the dialog's buttons (DialogPad's rule), else A Need / X Greed or
     *  Transmog / B Pass on the shown roll, Left / Right between rolls - each one tap on its own cell, as a finger's; true = answered */
    private fun fightPress(key: String, d: Dlg?): Boolean {
        if (d != null) {
            if (foldOf(d) > 0f) return false
            val delayed = secondsOf(d.delay) > 0
            val k = DialogPad.dialogButton(key, d.buttons.map { DialogPad.B(it.k, it.on, it.cell) }, delayed, d.which == "QUIT", d.safe, d.never) ?: return false
            val b = d.buttons.firstOrNull { it.k == k } ?: return false
            padHit = "b$k"; padKey = key
            android.util.Log.i("WCDLG", "controller $key on '${b.label}' of dialog ${d.i} ${d.which}: the app has the pad (a fight), its cell now")
            if (send(b.label, b.cell)) chose("b$k", d)
            return true
        }
        if (rolls.isEmpty()) return false
        if (rollMode() != RollMode.FULL) return false     // the fight's small panel / the pill: the presses are the fight's (wantsPad)
        if (growing()) return false                         // review: the window opening from the panel is not readable yet
        if (!rollsOwnPad()) return false       // a room / the loot holds: its presses, the rolls by touch (one press, one answer)
        val ids = rolls.map { it.id }
        val r = shownRoll() ?: return false
        if (book.rowOf(r) == RollBook.Row.CONFIRM) {
            // the game's bind confirm on this roll: A Okay, B Cancel (its own pop-up's cells, at once or the moment it is known)
            val ok = when (key) { "PAD1" -> true; "PAD2" -> false; else -> return false }
            padHit = "ask:${r.id}:" + (if (ok) "ok" else "cancel"); padKey = key
            confirmPress(r.id, ok)
            return true
        }
        val move = DialogPad.rollMove(key, ids, r.id)
        if (move != null) { rollPick = move; return true }
        val kind = DialogPad.rollKind(key, r.need, r.greed, r.transmog) ?: return false
        padHit = "roll:${r.id}:$kind"; padKey = key
        rollAnswer(r, kind)
        return true
    }

    // ---- A THE GAME DID NOT TAKE (b-release-spirit, 1.0.8 test16/17: the user's A on Release Spirit / Accept res "presses but nothing
    // happens and then it reappears", while a finger works). The press is the game's first; a document written PAD_FALLBACK_MS or more
    // after it that still lists the SAME dialog (slot and which) means the game never got it: the button's own cell is tapped then, as
    // a finger's press. Never both: once the game took it the dialog is gone from that document; never another dialog: the slot's
    // which must still match. The add-on sends that document soon (the sign of life asks for one: Dialogs d20).
    private class PadPress(val key: String, val hit: String, val gt: Long)
    private var padPress: PadPress? = null

    /** a sign of life now-ish (a fresh document from the add-on, d20), so a refused press is seen within a second */
    private fun pokeSoon(delayMs: Long = PAD_POKE_MS) {
        postDelayed({
            val d = doc ?: return@postDelayed
            val col = d.optJSONObject("life")?.optInt("col", -1) ?: -1
            if (col < 0 || !docCurrent || dryRun) return@postDelayed
            TapBridge.tap(context, d.optJSONObject("pad"), "iact", col) { }
        }, delayMs)
    }

    /**
     * NEVER PRESSED BY US (the user's standing ruling: never press "Exit Now"; test50: the d20 fallback tapped the QUIT countdown's "Exit
     * now" during the safe close): the QUIT dialog's button 1. The add-on mirrors it again (Dialogs d32, camp-exit: the player's finger
     * may press it, as on the game's own box), so every press the APP makes by itself - the controller's fallback tap - refuses it.
     */
    private fun neverPress(d: Dlg, hit: String) = hit.startsWith("b") && !DialogPad.mayPress(d.which, hit.substring(1).toIntOrNull() ?: 0, finger = false, never = d.never)

    /** a new document: was the controller's press refused? then its cell, once */
    private fun padRefused() {
        val p = padPress ?: return
        if (docGt >= 0 && docGt < p.gt + PAD_FALLBACK_MS) return
        padPress = null
        val d = list.firstOrNull { keyOf(it) == p.key && it.top == null } ?: return       // gone: the game took it
        if (neverPress(d, p.hit)) { android.util.Log.w("WCDLG", "the controller's press on ${d.which} ${p.hit} is never tapped (Exit now)"); return }
        if (docGt - p.gt > PAD_FALLBACK_MAX_MS) return                                     // too late to be that press
        val cell = when {
            p.hit == "extra" -> d.extraCell
            else -> d.buttons.firstOrNull { "b${it.k}" == p.hit }?.takeIf { it.on }?.cell ?: -1
        }
        val label = if (p.hit == "extra") (d.extra ?: "extra") else d.buttons.firstOrNull { "b${it.k}" == p.hit }?.label ?: p.hit
        android.util.Log.w("WCDLG", "the game did not take the controller's press on '$label' (${p.key} still up ${docGt - p.gt} ms after): tapping its cell")
        lastTapAt = 0L
        if (cell >= 0 && send(label, cell)) chose(p.hit, d)
    }
    // the app's own shared reader while a dialog shows (also over a room, where Home's reader is off); the same edges as Home's
    // mask, so a mask that arrives twice presses once
    private var padSub: PadInput? = null
    private fun padSubSync(on: Boolean) {
        if (!on) { padSub?.stop(); return }
        (padSub ?: PadInput({ _, _, _, _ -> }, { _, _ -> }, { m -> onPadHeld(m) }).also { padSub = it }).start()
    }
    override fun onDetachedFromWindow() { padSubSync(false); super.onDetachedFromWindow() }

    /** the preview harness sets this: nothing is ever sent to the game */
    var dryRun = false
    /** the preview harness reads this: what a tap would have sent */
    var lastSent: String? = null

    init { visibility = GONE }

    // ---------------------------------------------------------------- input from MainActivity

    /** {"type":"dialogs", dv, mode?, list = [...], life? = {col, every}, pad?, _gt} */
    /** false while the document is one replayed from the backlog (MainActivity's `current`): it may draw, never tap */
    private var docCurrent = true

    fun update(msg: JSONObject, current: Boolean = true) {
        docCurrent = current
        doc = msg
        docGt = msg.optLong("_gt", -1L)
        val out = ArrayList<Dlg>()
        val a: JSONArray? = msg.optJSONArray("list")
        if (a != null) for (n in 0 until a.length()) {
            val d = a.optJSONObject(n) ?: continue
            val cells = d.optJSONObject("cells")
            // a quest starter's prompt (the user, 2026-11-11; Dialogs d27): one more game dialog, in the app's own terse words
            val qs = QuestStartPrompt.parse(d)
            if (QuestStartPrompt.isPrompt(str(d, "which")) && qs == null) continue
            val bs = ArrayList<Btn>()
            val ba = d.optJSONArray("buttons")
            if (ba != null) for (m in 0 until ba.length()) {
                val b = ba.optJSONObject(m) ?: continue
                val k = b.optInt("k", 0)
                if (k !in 1..4) continue
                val label = str(b, "label") ?: ""
                bs.add(Btn(k, if (qs != null) QuestStartPrompt.label(k, label) else label, b.optBoolean("on", true), str(b, "key"),
                           cells?.optInt("b$k", -1) ?: -1))
            }
            out.add(Dlg(d.optInt("i", 0), str(d, "which") ?: "",
                        if (qs != null) Tr.t(QuestStartPrompt.QUESTION) else str(d, "text") ?: "",
                        if (qs != null) qs.sub else str(d, "sub"), bs,
                        str(d, "extra"), str(d, "extraKey"), cells?.optInt("extra", -1) ?: -1,
                        d.optBoolean("x"), cells?.optInt("x", -1) ?: -1,
                        d.optInt("left", 0), d.optDouble("dur", 0.0).toFloat(), d.optBoolean("bar"),
                        d.optInt("delay", 0), str(d, "alert"), d.optJSONObject("item"),
                        d.optLong("money", -1L), d.optBoolean("focus"), str(d, "top"), d.optBoolean("hid"),
                        str(d, "fkey"), d.optInt("safe", 0), d.optInt("never", 0),
                        if (str(d, "which") == "CONFIRM_LOOT_ROLL") d.optInt("roll", -1) else -1))
        }
        list = out
        if (current) padRefused() else padPress = null
        foldKey?.let { fk ->
            if (out.none { keyOf(it) == fk }) foldKey = null                    // the game closed it: the fold was right
            else if (docGt > foldGt + FOLD_REFUSE_MS) {                         // still there, written well after the choice
                foldKey = null
                android.util.Log.w("WCDLG", "dialog $fk is still up ${docGt - foldGt} ms after the press: the game did not take it")
                note("The game did not take that")
            }
        }
        // LOOT ROLLS (addon Dialogs d12): the game's rolls ride this document. loot-pop: the BOOK keeps the answers made (gone at once)
        // until the game's word; a document written well after an answer that still offers the roll brings it back (a note)
        val ra = msg.optJSONArray("rolls")
        val rl = if (ra == null) emptyList() else (0 until ra.length()).mapNotNull { ra.optJSONObject(it) }.map { RollBook.Roll(it) }
        rollsMore = msg.optInt("rollsMore", 0)
        rollsAt = System.currentTimeMillis()
        val back = book.onDoc(rl, RollBook.asksOf(a), docGt)
        if (current) {
            if (back.isNotEmpty()) {
                android.util.Log.w("WCDLG", "roll(s) $back still offered ${RollBook.REFUSE_MS} ms after the answer: the game did not take it")
                note("The game did not take that")
            }
            rollTaps()
        } else book.taps()                                                  // a replayed document never taps
        val gameSel = rl.firstOrNull { it.sel }?.id ?: -1
        if (gameSel != rollGameSel) { rollGameSel = gameSel; rollPick = -1 }     // the controller moved: its choice wins
        if (rolls.any { it.id == gameSel }) gameGuess = -1                   // the game's own word on its selection
        if (rolls.none { it.id == rollPick }) rollPick = -1
        // the game asks (its bind confirm): a folded window opens to show it (its pop-up on the top screen stays hidden while we draw it)
        if (rollsMin && confirmRoll() != null) rollsMin = false
        // the box's lock (SETTLE) follows the BOX dialogs only: a roll coming or going never locks a press (loot-pop), and a finger held
        // on a roll's button keeps it (its hit names the roll: a changed layout simply misses)
        val s = boxes().joinToString("/") { d -> "${d.i}:${d.which}:" + d.buttons.joinToString(",") { "${it.k}${it.key}" } + ":${d.top}" }
        if (s != sig) { sig = s; sigAt = System.currentTimeMillis(); if (!rollHit(pressed)) pressed = "" }
        lifeSig = s + "|r" + rolls.joinToString(",") { r -> "${r.id}" + (if (book.rowOf(r) == RollBook.Row.CONFIRM) "c" else "") }
        refresh()
    }

    private fun rollHit(h: String) = h.startsWith("roll:") || h.startsWith("ask:") || h.startsWith("rolls:") || h.startsWith("card:")
    /** a press that names its roll (an answer, its confirm's Okay / Cancel) */
    private fun rollPress(h: String) = h.startsWith("roll:") || h.startsWith("ask:")
    private var pressedAt: RectF? = null        // where the finger's press went down (its button then; review: the rows may move under it)

    /** {"type":"dialogack", cell, i, k}: the tap landed, the game's button was clicked */
    fun ack(msg: JSONObject, current: Boolean = true) {
        // b-release-spirit (1.0.8): the add-on's word on the click (d19: which dialog, the button enabled, the cell aimed at it; and
        // half a second later whether the dialog is still up), so ONE log read tells a lost tap from a click the game did not take.
        // test16: an answer replayed from the log at the app's start is not this session's press: said so ("old", and its dv)
        android.util.Log.i("WCDLG", (if (current) "" else "OLD (replayed, ${msg.optString("dv", "before d19")}) ") +
            (if (msg.optBoolean("after")) "after " else "clicked ") + "cell ${msg.optInt("cell")} " +
            "dialog ${msg.optInt("i")} ${msg.optString("which", "?")} button ${msg.optInt("k")}" +
            (if (msg.optBoolean("after")) " still up ${msg.optBoolean("still")} ghost ${msg.optBoolean("ghost")}"
             else " enabled ${if (msg.has("enabled")) msg.optBoolean("enabled") else "?"} aimed ${msg.optBoolean("aimed")} dead ${msg.optBoolean("dead")}"))
        postInvalidate()
    }

    /** true while a dialog owns the bottom screen */
    fun showing() = visibility == VISIBLE

    /**
     * the dialogs want the pad in a fight (PadOwnerSync's want; the user's decision 1, 2026-11-11: pop-ups and loot rolls "should be built on
     * the bottom screen"): a pop-up as the box, or the rolls OPEN - never the folded rolls pill (the review of 2026-11-11: a minimized
     * roll kept the whole controller from the fight for up to a minute)
     */
    fun wantsPad() = showing() && (active() != null || rollMode() == RollMode.FULL)

    /** Interact i152 (router-heal): the pad is wanted for the game's DEATH pop-up alone (no rolls open) - with a dead player that is never
     *  an ENTER (PadOwnerSync.step's deathOnly) */
    fun wantsPadForDeathOnly() = wantsPad() && active()?.which == "DEATH" && rollMode() != RollMode.FULL

    /**
     * loot-pop: this view COVERS the bottom screen (a pop-up as the box, the full roll window): the host's "a window is up" checks. The
     * fight's small roll panel over the minimap and the folded pill cover nothing: the fight's presses, the bar, the cast bar go on.
     */
    fun covers() = showing() && (active() != null || rollMode() == RollMode.FULL)

    private fun str(o: JSONObject, key: String) = if (o.has(key) && !o.isNull(key)) o.optString(key) else null

    // ---------------------------------------------------------------- which dialog is the box

    /** the dialogs this view may show as the box: mirrored ones only (not the ones that stay on top), never a roll's own bind confirm
     *  (Dialogs d34: drawn on its roll, loot-pop) */
    private fun mirrored() = boxes().filter { it.top == null }

    /** the dialogs but a loot roll's bind confirm whose roll is in the book (that one is drawn on the roll itself) */
    private fun boxes() = list.filter { d -> d.roll < 0 || book.rolls.none { it.id == d.roll } }

    /** the box: the mirrored dialog with the game's focus, else the first mirrored one */
    private fun active(): Dlg? {
        val m = mirrored().filter { foldOf(it) < 1f }
        return m.firstOrNull { it.focus } ?: m.firstOrNull()
    }

    // ---------------------------------------------------------------- freshness, the sign of life

    private fun gameNow(): Long {
        val c = Calendar.getInstance()
        val doy = DAYS_BEFORE[c.get(Calendar.MONTH)] + c.get(Calendar.DAY_OF_MONTH)
        return ((doy * 24L + c.get(Calendar.HOUR_OF_DAY)) * 60L + c.get(Calendar.MINUTE)) * 60_000L +
            c.get(Calendar.SECOND) * 1000L + c.get(Calendar.MILLISECOND)
    }

    private fun ageMs(): Long = if (docGt < 0) Long.MAX_VALUE else abs(gameNow() - docGt)

    private val ticker = object : Runnable {
        override fun run() { refresh() }
    }

    private fun refresh() {
        removeCallbacks(ticker)
        // only while the companion is in charge of the dialogs: otherwise the game shows them itself
        val want = doc?.optBoolean("mode") == true && (active() != null || rolls.isNotEmpty()) && ageMs() < STALE_MS
        val v = if (want) VISIBLE else GONE
        if (visibility != v) visibility = v
        padSubSync(want)
        if (!want) drawnSig = ""
        if (rolls.isEmpty() && book.rolls.isEmpty()) { rollsMin = false; openedInFight = false }
        // the rolls look a fight's end up four times a second (the small panel grows into the window then)
        if (list.isNotEmpty() || rolls.isNotEmpty()) postDelayed(ticker, if (rolls.isNotEmpty()) 250L else 500L)
        invalidate()
    }

    /** the host: Home's state changed (a fight's start / end among it): the rolls look again now */
    fun fightCheck() { if (rolls.isNotEmpty() && fightNow() != wasFight) refresh() }

    /**
     * Say that this view shows the dialogs. Called from onDraw, so it is only ever said after they
     * have really been drawn: at once for a new set of dialogs, then every `life.every` seconds.
     */
    private fun signOfLife() {
        val d = doc ?: return
        val life = d.optJSONObject("life") ?: return
        val col = life.optInt("col", -1)
        if (col < 0 || !docCurrent || ageMs() > LIVE_MS) return
        val every = life.optInt("every", 5).coerceIn(2, 30) * 1000L
        val now = System.currentTimeMillis()
        val fresh = drawnSig != lifeSig
        // fix/stream-cursor: on a stream (Desktop posts the pad's clicks) the sign is ONE tap per newly drawn set, never the periodic one
        val pick = LifeSign.pick(fresh, now - lifeAt, every, DeskClicks.route() == DeskClicks.Route.DESKTOP, col, life.optInt("stay", -1))
            ?: return
        drawnSig = lifeSig
        lifeAt = now
        if (dryRun) { lastSent = if (pick.second) "stay" else "alive"; return }
        TapBridge.tap(context, d.optJSONObject("pad"), "iact", pick.first) { }
    }

    // ---------------------------------------------------------------- actions

    private fun blocked(now: Long): String? = when {
        ageMs() > LIVE_MS -> "Waiting for the game"
        doc?.optJSONObject("pad") == null -> "The game has not published its tap pad"
        now - sigAt < SETTLE_MS -> "One moment"            // the dialogs just changed: a tap aimed at the old one
        now - lastTapAt < LOCKOUT_MS -> "One moment"
        else -> null
    }

    private fun send(what: String, cell: Int): Boolean {
        val now = System.currentTimeMillis()
        if (cell < 0 && doc?.has("touch") == true && doc?.optBoolean("touch") == false) { note("Answer with the controller"); return false }
        blocked(now)?.let { note(it); android.util.Log.w("WCDLG", "press '$what' cell $cell: not sent ($it; the document is ${ageMs()} ms old)"); return false }
        if (cell < 0) {
            android.util.Log.w("WCDLG", "press '$what': no tap cell (touch ${doc?.opt("touch")})")
            // touch = false: the tap pad on this game has no room for the dialogs' cells yet
            note(if (doc?.has("touch") == true && doc?.optBoolean("touch") == false) "Answer with the controller"
                 else "The game gave this no tap cell")
            return false
        }
        lastTapAt = now
        if (dryRun) { lastSent = "$what cell $cell"; note("Preview: $what"); return true }
        val ok = TapBridge.tap(context, doc?.optJSONObject("pad"), "idlg", cell) { why -> post { note(why) }; android.util.Log.w("WCDLG", "press '$what' cell $cell failed: $why") }
        android.util.Log.i("WCDLG", "press '$what' cell $cell: ${if (ok) "sent" else "NOT sent (no pad)"} (dialogs ${list.joinToString { "${it.i}:${it.which}" }})")
        if (!ok) note("The game has not published its tap pad")
        return ok
    }

    private fun note(s: String) { noteText = s; noteAt = System.currentTimeMillis(); postInvalidate() }

    /** the preview harness / the off-device tests read this: every tap the rolls made, in order ("<row> <cell> <what>") */
    val rollSent = ArrayList<String>()

    /** one tap of the rolls' (a roll's cell on isell, its confirm's on idlg): no settle lock, no lockout (loot-pop) */
    private fun rollTap(row: String, cell: Int, what: String): Boolean {
        if (cell < 0) { note("The game gave this no tap cell"); return false }
        rollSent.add("$row $cell $what")
        if (dryRun) { lastSent = "$what cell $cell"; return true }
        val ok = TapBridge.tap(context, doc?.optJSONObject("pad"), row, cell) { why -> post { note(why) }; android.util.Log.w("WCDLG", "$what cell $cell failed: $why") }
        android.util.Log.i("WCDLG", "$what: $row $cell ${if (ok) "sent" else "NOT sent (no pad)"}")
        if (!ok) note("The game has not published its tap pad")
        return ok
    }

    /** why an answer cannot go now (null = it can; "" = a bounce, said nowhere) */
    private fun rollGate(gt: Long): String? = when {
        ageMs() > LIVE_MS -> "Waiting for the game"
        doc?.optJSONObject("pad") == null -> "The game has not published its tap pad"
        !book.mayAnswer(gt) -> ""
        else -> null
    }

    /**
     * ONE PRESS = ONE ROLL (loot-pop): [kind] on roll [r] by a finger or the app's pad in a fight - its cell now, the roll gone from the
     * screen at once and the next one shown (the game's own choice of the next: RollBook.nextAfter); the game's word corrects it
     */
    private fun rollAnswer(r: RollBook.Roll, kind: String): Boolean {
        val gt = gameNow()
        rollGate(gt)?.let { if (it.isNotEmpty()) note(it); return false }
        val cell = r.cell(kind)
        if (cell < 0) { note("The game gave this no tap cell"); return false }
        val ids = rolls.map { it.id }
        val wasShown = shownRoll()?.id == r.id
        if (!book.answer(r.id, kind, gt)) return false
        if (!rollTap("isell", cell, "roll $kind on ${r.id}")) { book.forget(r.id); return false }
        // the window moves on as the game's own frame does (the one before, else the first); a bind-on-pickup one stays for its confirm
        if (wasShown && book.rowOf(r) == null) rollPick = RollBook.nextAfter(ids, r.id) ?: -1
        lingerHit = "roll:${r.id}:$kind"; lingerUntil = System.currentTimeMillis() + PRESS_MIN_MS
        // review: a tap the game never got changes nothing in the add-on, so no document would come to bring the roll back (the last roll
        // answered: no sign of life either, only the 15 s heartbeat) - a fresh one is asked for once the game's word is due
        pokeSoon(RollBook.REFUSE_MS + REFUSE_POKE_MS)
        postInvalidateOnAnimation()
        return true
    }

    /** the game's bind confirm on roll [id]: Okay / Cancel - its pop-up's own cell now, or the moment the add-on's document carries it */
    private fun confirmPress(id: Int, ok: Boolean) {
        val gt = gameNow()
        rollGate(gt)?.let { if (it.isNotEmpty()) note(it); return }
        if (!book.confirm(id, ok, gt)) return
        rollTaps()
        pokeSoon(RollBook.REFUSE_MS + REFUSE_POKE_MS)        // review: the game's word on it, even when nothing changed (as rollAnswer)
        postInvalidateOnAnimation()
    }

    /** the book's taps due now (a confirm's answer, the controller's roll the game did not take) */
    private fun rollTaps() {
        if (!docCurrent) { book.taps(); return }
        for (t in book.taps()) rollTap(t.row, t.cell, t.what)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // loot-pop: the fight's small roll panel and the folded pill take only their own touches; the rest reaches Home behind
        if (e.actionMasked == MotionEvent.ACTION_DOWN && active() == null) {
            val m = rollMode()
            if (m == RollMode.COMPACT && !compactRect.contains(e.x, e.y)) return false
            if (m == RollMode.PILL && !rollsPill.contains(e.x, e.y)) return false
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val h = hits.lastOrNull { it.first.contains(e.x, e.y) }
                pressed = h?.second ?: ""
                pressedAt = h?.first?.let { RectF(it) }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val now = hits.lastOrNull { it.first.contains(e.x, e.y) }?.second
                val was = pressed
                // loot-pop review: a roll's button names its roll ("roll:<id>:<kind>", "ask:<id>:ok"), so a finger lifted where it went
                // down is that press even when a document moved the rows under it meanwhile (a roll above it answered or ended): never
                // another roll's, never lost (the box's buttons keep the old rule: their names are not the dialog's)
                val hit = if (now != was && rollPress(was) && pressedAt?.contains(e.x, e.y) == true) was else now
                pressed = ""; pressedAt = null
                // b-release-spirit: every finger press on the dialog, so "it presses but nothing happens" has its reason in the log
                android.util.Log.i("WCDLG", "finger up on '${hit ?: "-"}' (down on '$was')" + if (hit == null || hit != was) ": no press" else "")
                if (hit != null && hit == was) tap(hit)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = ""; pressedAt = null; invalidate() }
        }
        return true       // the dialog owns every touch: nothing reaches what is behind it
    }

    private fun tap(hit: String) {
        if (hit == "rolls:min") {
            // the window folds: in a fight back into the small panel over the minimap, else into the pill
            if (fightNow()) openedInFight = false else rollsMin = true
            refresh(); return
        }
        if (hit == "rolls:open") { rollsMin = false; refresh(); return }
        if (hit == "rolls:full") { if (fightNow()) openedInFight = true else rollsMin = false; refresh(); return }    // the player opens it
        if (hit.startsWith("card:")) { rollPick = hit.substring(5).toIntOrNull() ?: -1; invalidate(); return }
        if (hit.startsWith("roll:")) {
            // "roll:<id>:<kind>": the game's own RollOnLoot through Interact's cell on the isell row - on the first touch
            val parts = hit.split(':')
            val r = rolls.firstOrNull { it.id == parts.getOrNull(1)?.toIntOrNull() } ?: return
            rollAnswer(r, parts.getOrNull(2) ?: return)
            return
        }
        if (hit.startsWith("ask:")) {
            // "ask:<id>:ok|cancel": the game's bind confirm on that roll, through its pop-up's own cell
            val parts = hit.split(':')
            confirmPress(parts.getOrNull(1)?.toIntOrNull() ?: return, parts.getOrNull(2) == "ok")
            return
        }
        val d = active() ?: run { android.util.Log.w("WCDLG", "press '$hit': no dialog is shown"); return }
        when {
            hit == "x" -> if (send("close", d.xCell)) chose(hit, d)
            hit == "extra" -> if (send(d.extra ?: "extra", d.extraCell)) chose(hit, d)
            hit.startsWith("b") -> {
                val b = d.buttons.firstOrNull { "b${it.k}" == hit } ?: return
                // camp-exit (build 1462, the user: "when exiting the game and the timer pops up it doesnt have the 'exit now' option that
                // it normally should"): the QUIT countdown's "Exit Now" is the player's own finger here, as on the game's box (Dialogs d32);
                // only the app's own presses refuse it (neverPress: the controller's fallback, the safe close)
                // d33 (issue #278, "Yes, protect"): our add-on's "has been blocked" Disable is never pressed, not even by a finger here
                if (!DialogPad.mayPress(d.which, b.k, finger = true, never = d.never)) {
                    android.util.Log.i("WCDLG", "finger on ${d.which} $hit: never pressed (our add-on's Disable)")
                    note(DialogPad.NEVER_NOTE); return
                }
                if (neverPress(d, hit)) android.util.Log.i("WCDLG", "finger on ${d.which} $hit: Exit Now, the player's own press")
                if (!b.on || secondsOf(d.delay) > 0) {
                    android.util.Log.i("WCDLG", "press '${b.label}' of ${d.which}: the game has it ${if (!b.on) "greyed out" else "waiting ${secondsOf(d.delay)} s"}")
                    return
                }
                if (send(b.label, b.cell)) chose(hit, d)
            }
        }
    }

    // ---------------------------------------------------------------- text: the game's colour codes

    private class Run(val s: String, val colour: Int)

    /** the game's raw text as coloured words: |cAARRGGBB ... |r, links and textures taken out */
    private fun runs(raw: String, base: Int): List<List<Run>> {
        val lines = ArrayList<ArrayList<Run>>()
        var cur = ArrayList<Run>()
        var colour = base
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { cur.add(Run(sb.toString(), colour)); sb.setLength(0) } }
        var i = 0
        val t = raw.replace("\r", "")
        while (i < t.length) {
            val ch = t[i]
            if (ch == '\n') { flush(); lines.add(cur); cur = ArrayList(); i++; continue }
            if (ch == '|' && i + 1 < t.length) {
                val n = t[i + 1]
                when {
                    (n == 'c' || n == 'C') && i + 10 <= t.length -> {
                        val hex = t.substring(i + 2, i + 10)
                        val v = hex.toLongOrNull(16)
                        if (v != null) { flush(); colour = (0xFF000000L or (v and 0xFFFFFFL)).toInt(); i += 10; continue }
                    }
                    n == 'r' || n == 'R' -> { flush(); colour = base; i += 2; continue }
                    n == 'n' -> { flush(); lines.add(cur); cur = ArrayList(); i += 2; continue }
                    n == 'H' -> { val e = t.indexOf("|h", i + 2); if (e > 0) { i = e + 2; continue } }
                    n == 'h' -> { i += 2; continue }
                    n == 'T' -> { val e = t.indexOf("|t", i + 2); if (e > 0) { i = e + 2; continue } }
                    n == 'A' -> { val e = t.indexOf("|a", i + 2); if (e > 0) { i = e + 2; continue } }
                    n == '|' -> { sb.append('|'); i += 2; continue }
                }
            }
            sb.append(ch); i++
        }
        flush(); lines.add(cur)
        return lines
    }

    /** the same text with nothing but its words: for a waiting plate */
    private fun plain(raw: String) = runs(raw, 0).joinToString(" ") { l -> l.joinToString("") { it.s } }.trim()

    /** wrap coloured runs to [width]; returns the lines, each a list of runs */
    private fun wrap(raw: String, p: Paint, width: Float, base: Int): List<List<Run>> {
        val out = ArrayList<List<Run>>()
        for (para in runs(raw, base)) {
            var line = ArrayList<Run>()
            var w = 0f
            for (r in para) {
                // words keep their space in front, so that a run can be split anywhere between words
                val parts = Regex("\\s*\\S+|\\s+").findAll(r.s).map { it.value }.toList()
                for (word in parts) {
                    val ww = p.measureText(word)
                    if (w + ww > width && line.isNotEmpty()) {
                        out.add(line); line = ArrayList(); w = 0f
                        val trimmed = word.trimStart()
                        if (trimmed.isEmpty()) continue
                        line.add(Run(trimmed, r.colour)); w += p.measureText(trimmed)
                    } else {
                        line.add(Run(word, r.colour)); w += ww
                    }
                }
            }
            out.add(line)
        }
        return out.filter { l -> l.any { it.s.isNotBlank() } }
    }

    private fun drawLines(c: Canvas, lines: List<List<Run>>, p: Paint, cx: Float, top: Float, lineH: Float): Float {
        var y = top
        for (l in lines) {
            val total = l.sumOf { p.measureText(it.s).toDouble() }.toFloat()
            var x = cx - total / 2f
            val base = y + lineH / 2f - (p.ascent() + p.descent()) / 2f
            for (r in l) { p.color = r.colour; c.drawText(Tr.t(r.s), x, base, p); x += p.measureText(r.s) }
            y += lineH
        }
        return y
    }

    // ---------------------------------------------------------------- the countdown

    /** seconds left now, of [atDoc] seconds that were left when the document was written */
    private fun secondsOf(atDoc: Int): Int {
        if (atDoc <= 0) return 0
        val gone = if (docGt < 0) 0L else max(0L, gameNow() - docGt)
        return max(0, ceil(atDoc - gone / 1000.0).toInt())
    }

    private fun clock(s: Int) = "${s / 60}:${(s % 60).toString().padStart(2, '0')}"

    // ---------------------------------------------------------------- drawing (SPEC part 1)

    /** the key the game gives a button -> the glyph the kit draws; no key, no glyph */
    private fun glyphOf(key: String?): String? = when (key) {
        "PAD1" -> "rev_a"; "PAD2" -> "rev_b"; "PAD3" -> "rev_x"; "PAD4" -> "rev_y"
        "PADRSTICK" -> "gen_rstickin"
        "PADBACK" -> "rev_minus"; "PADFORWARD" -> "rev_plus"
        else -> null
    }

    /**
     * NOT FOCUSED (Thor, 2026-09-29: B did not decline a party invite). The game gives no controller
     * focus to a dialog the player did not ask for (party invite, trade, channel invite, area spirit
     * healer): its buttons show no glyph there and A / B do nothing until the player presses the
     * game's "focus the UI" key (`fkey`, read by the addon). The mirror draws it the same way: no
     * glyph on the buttons, and one line with that key. A touch works either way.
     */
    private fun focusLine(d: Dlg): String? = if (!d.focus && d.buttons.isNotEmpty()) glyphOf(d.fkey) else null

    private fun button(c: Canvas, r: RectF, label: String, key: String?, on: Boolean, hit: String) {
        val st = when {
            !on -> UiKit.Button.DISABLED
            isDown(hit) -> UiKit.Button.DOWN
            else -> UiKit.Button.UP
        }
        val g = glyphOf(key)
        // the kit pushes a DOWN button in itself (UiKit.pressed)
        if (g != null) UiKit.button(c, r, label, g, st) else UiKit.button(c, r, label, st)
        hits.add(r to hit)
    }

    /** the height the content of [d] needs, and the pieces measured on the way */
    private class Plan(val text: List<List<Run>>, val sub: List<List<Run>>, val height: Float, val rows: Int)

    private fun plan(d: Dlg): Plan {
        val tp = UiKit.bodyPaint(42f, Color.WHITE)
        val sp = UiKit.bodyPaint(30f, Theme.gold)
        // the alert sign stands left of the text, the close X in its top right corner
        val textW = BOX_W - 100f - 40f - (if (d.alert != null) 150f else 0f) - (if (d.x) 120f else 0f)
        val text = wrap(d.text, tp, textW, Color.WHITE)
        val sub = if (d.sub != null) wrap(d.sub, sp, textW, Theme.gold) else emptyList()
        var h = text.size * 56f
        if (sub.isNotEmpty()) h += 10f + sub.size * 40f
        if (d.alert != null) h = max(h, 120f)
        if (d.item != null) h += 26f + 108f
        if (d.money >= 0) h += 22f + 44f
        if (d.left > 0) h += 22f + 40f
        if (d.bar) h += 26f + 30f
        val rows = when (d.buttons.size) { 0 -> 0; 4 -> 2; else -> 1 }
        if (focusLine(d) != null) h += 26f + 56f
        if (rows > 0) h += 40f + rows * 111f + (rows - 1) * 18f
        if (d.extra != null) h += 16f + 18f + 111f
        return Plan(text, sub, h, rows)
    }

    override fun onDraw(c: Canvas) {
        hits.clear()
        val now = drawClock()
        syncFight()
        val m = rollMode()
        if (m != drawnMode) { modeChanged(drawnMode, m, now); drawnMode = m }
        when (m) {
            RollMode.COMPACT -> drawCompact(c, now)
            RollMode.PILL -> if (active() == null) drawRollsPill(c) else drawRolls(c)
            RollMode.FULL -> if (now - growAt < GROW_MS) drawGrow(c, now) else drawRolls(c)
            RollMode.NONE -> Unit
        }
        val d = active()
        if (d == null) { if (rolls.isNotEmpty()) { rollNote(c, m); signOfLife() }; return }
        if (rolls.isNotEmpty()) hits.clear()        // a game dialog over the rolls owns the touches
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f
        UiKit.cover(c, RectF(0f, 0f, w, h))

        val waiting = list.filter { it !== d }
        val pl = plan(d)
        val boxH = pl.height + 2 * 50f + 36f
        val total = boxH + waiting.size * (96f + 14f)
        val top = max(30f, (h - total) / 2f)
        val box = RectF(cx - BOX_W / 2f, top, cx + BOX_W / 2f, top + boxH)
        val fold = foldOf(d).coerceIn(0f, 1f)
        val foldSave = if (fold > 0f) {
            val k = fold * fold
            val sv = c.saveLayerAlpha(RectF(0f, 0f, w, h), (255 * (1f - k)).toInt().coerceIn(0, 255))
            c.scale(1f - 0.06f * k, 1f - 0.18f * k, box.centerX(), box.centerY())
            postInvalidateOnAnimation()
            sv
        } else -1
        UiKit.dialog(c, box)

        // ---- the content, top to bottom
        var y = box.top + 50f + 18f
        val tcx = if (d.alert != null) cx + 70f else cx
        if (d.alert != null) {
            val b = UiKit.tex(if (d.alert == "gear") "alert_gear" else "alert")
            val ax = box.left + 70f * 0.62f + 16f + 20f
            if (b != null) c.drawBitmap(b, null, RectF(ax, y - 6f, ax + 120f, y + 114f), art)
        }
        y = drawLines(c, pl.text, UiKit.bodyPaint(42f, Color.WHITE), tcx, y, 56f)
        if (pl.sub.isNotEmpty()) y = drawLines(c, pl.sub, UiKit.bodyPaint(30f, Theme.gold), tcx, y + 10f, 40f)
        if (d.alert != null) y = max(y, box.top + 50f + 18f + 120f)
        d.item?.let { it2 ->
            y += 26f
            val rgb = it2.optJSONArray("rgb")
            val col = if (rgb != null && rgb.length() >= 3)
                Color.rgb((rgb.optDouble(0, 1.0) * 255).toInt().coerceIn(0, 255), (rgb.optDouble(1, 1.0) * 255).toInt().coerceIn(0, 255),
                          (rgb.optDouble(2, 1.0) * 255).toInt().coerceIn(0, 255))
            else Color.WHITE
            val name = str(it2, "name") ?: str(it2, "link")?.let { l -> plain(l).trim('[', ']') } ?: ""
            UiKit.itemPlate(c, RectF(cx - 200f, y, cx + 340f, y + 108f), Icons.getOrUnknown(it2.optInt("icon", 0).takeIf { n -> n > 0 }), name,
                            colour = col, count = it2.optInt("count", 0))
            y += 108f
        }
        if (d.money >= 0) {
            y += 22f
            val mw = UiKit.numberPaint(40f).measureText(d.money.toString()) + 120f
            UiKit.money(c, d.money, cx - mw / 2f, y + 22f, 40f, Color.WHITE)
            y += 44f
        }
        val left = secondsOf(d.left)
        if (d.left > 0) {
            y += 22f
            val p = UiKit.bodyPaint(30f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
            c.drawText(Tr.t("Answers itself in " + clock(left)), cx, y + 20f - (p.ascent() + p.descent()) / 2f, p)
            y += 40f
        }
        if (d.bar) {
            y += 26f
            UiKit.progress(c, RectF(cx - 400f, y, cx + 400f, y + 30f), if (d.dur > 0f) left / d.dur else 0f)
            y += 30f
        }

        // ---- no focus yet: the key that gives it, as the game would want it pressed
        val fg = focusLine(d)
        if (fg != null) {
            y += 26f
            val p = UiKit.bodyPaint(34f, Theme.gold)
            val words = "to answer with the controller"
            val g = 56f
            val tw = p.measureText(words)
            val x0 = cx - (g + 14f + tw) / 2f
            UiKit.pad(c, fg, x0 + g / 2f, y + g / 2f, g, 255)
            c.drawText(Tr.t(words), x0 + g + 14f, y + g / 2f - (p.ascent() + p.descent()) / 2f, p)
            y += 56f
        }

        // ---- the buttons, in the GAME'S own order from left to right (user, 2026-09-28)
        val delayed = secondsOf(d.delay) > 0
        if (pl.rows > 0) {
            y += 40f
            val n = d.buttons.size
            val bw = when (n) { 1 -> 440f; 2 -> 380f; 3 -> 300f; else -> 440f }
            val perRow = if (n == 4) 2 else n
            for ((idx, b) in d.buttons.withIndex()) {
                val row = idx / perRow; val col = idx % perRow
                val rowW = perRow * bw + (perRow - 1) * 28f
                val x0 = cx - rowW / 2f + col * (bw + 28f)
                val by = y + row * (111f + 18f)
                val on = b.on && !(delayed && b.k == 1)
                button(c, RectF(x0, by, x0 + bw, by + 111f), b.label, if (fg != null) null else b.key, on, "b${b.k}")
            }
            y += pl.rows * 111f + (pl.rows - 1) * 18f
        }
        if (d.extra != null) {
            y += 16f
            UiKit.divider(c, box.left + 60f, y, box.right - 60f, y)
            y += 18f
            button(c, RectF(cx - 220f, y, cx + 220f, y + 111f), d.extra, d.extraKey, true, "extra")
        }
        if (d.x) {
            val st = if (isDown("x")) UiKit.Button.DOWN else UiKit.Button.UP
            UiKit.square(c, "exit", box.right - 60f, box.top + 58f, 84f, st)
            hits.add(RectF(box.right - 60f - 55.5f, box.top + 58f - 55.5f, box.right - 60f + 55.5f, box.top + 58f + 55.5f) to "x")
        }

        // ---- the ones that wait, in the game's order; a plate is not a button
        var wy = box.bottom + 14f
        for (o in waiting) {
            UiKit.dialogWaiting(c, RectF(box.left, wy, box.right, wy + 96f), plain(o.text).lineSequence().firstOrNull() ?: "")
            wy += 96f + 14f
        }

        if (foldSave >= 0) { c.restoreToCount(foldSave); hits.clear() }
        if (System.currentTimeMillis() < lingerUntil) postInvalidateOnAnimation()
        // ---- why a tap did nothing
        val nt = noteText
        if (nt != null && System.currentTimeMillis() - noteAt < 2500) {
            val p = UiKit.bodyPaint(28f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
            c.drawText(Tr.t(nt), cx, h - 28f, p)
        }
        signOfLife()
    }

    private val art = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // ================================================================ LOOT ROLLS
    // UI Art's LOOT_SPEC + SHEET_loot_v2 (art-kit a3fb2179 + 2d6337bf; the user, 2026-09-30: "Yes, build it"): the game's
    // roll cards down the left (4 at most, "+N more"), the chosen roll's tooltip and, for gear, Currently Equipped beside it
    // with the stat changes, AUTOMATICALLY; the game's own Need / Greed / Pass buttons in the strip, a grey one with the
    // game's reason. A MIRROR: the game keeps the controller (A Need, X Greed, B Pass, its D-pad between rolls); a tap is
    // one cell of Interact's, whose action is the game's RollOnLoot.
    //
    // loot-pop (the user, 2026-11-16: "The loot roll pop up is WAY too intrusive during a fight / there should be a smaller version that
    // pops up over the minimap and gets bigger when transitioning out of combat. / during combat it should accept touch input and show
    // things per item/roll"): IN A FIGHT the rolls are a SMALL PANEL over Home's minimap (one row per roll: its icon, its name in its
    // quality's colour, its timer, and the game's Need / Greed (or Transmogrification) / Disenchant / Pass as buttons, each acting on the
    // first touch); the controller stays the fight's (no ENTER: [wantsPad] false) unless the player opens the window (the panel's open
    // button). When the fight ends the panel GROWS into the full window. Out of a fight the full window, as before (its minimize: the
    // pill). The game's bind confirm is drawn ON ITS ROLL (Okay / Cancel), never a box over the fight.

    enum class RollMode { NONE, COMPACT, FULL, PILL }

    /** the rolls' animation clock (the panel's pop, the grow): the off-device renders set it to stop a frame mid-motion */
    var drawClock: () -> Long = { System.currentTimeMillis() }

    /** set by the host: the player is in a fight (Home's own word: the takeover or the grow) */
    var fightNow: () -> Boolean = { false }
    /** set by the host: Home's minimap module in this view's px while Home shows it, else null (the small panel then stands at the right) */
    var mapBox: () -> RectF? = { null }

    private var wasFight = false
    private var openedInFight = false          // the player opened the window in this fight (the panel's open button)
    private var drawnMode = RollMode.NONE
    private var growAt = 0L                    // the small panel growing into the window (a fight's end, or opened)
    private var growFrom = RectF()
    private var popAt = 0L                     // the small panel popping up over the minimap
    private var compactRect = RectF()

    /** how the rolls show now */
    fun rollMode(): RollMode = when {
        rolls.isEmpty() -> RollMode.NONE
        fightNow() && !openedInFight -> RollMode.COMPACT
        !fightNow() && rollsMin -> RollMode.PILL
        else -> RollMode.FULL
    }

    /** a fight's start or end: a window the player opened in the fight closes back with it; a pill becomes the fight's panel */
    private fun syncFight() {
        val f = fightNow()
        if (f == wasFight) return
        wasFight = f
        openedInFight = false
        if (f) rollsMin = false
    }

    private fun modeChanged(from: RollMode, to: RollMode, now: Long) {
        android.util.Log.i("WCDLG", "rolls: $from -> $to")
        if (from == RollMode.COMPACT && to == RollMode.FULL && compactRect.width() > 0f) { growAt = now; growFrom = RectF(compactRect) }
        if (to == RollMode.COMPACT) popAt = now
        if (to == RollMode.NONE) { growAt = 0L; popAt = 0L }
    }

    private fun hexColour(hex: String?, def: Int): Int =
        if (hex != null && hex.length == 6) try { Color.parseColor("#$hex") } catch (e: Exception) { def } else def

    private fun tipLines(a: JSONArray?, from: Int): List<UiKit.TipLine> {
        val out = ArrayList<UiKit.TipLine>()
        if (a == null) return out
        for (n in from until a.length()) {
            val l = a.optJSONObject(n) ?: continue
            val t = str(l, "t") ?: continue
            out.add(UiKit.TipLine(t, hexColour(str(l, "c"), Color.WHITE), str(l, "r"), hexColour(str(l, "rc"), Color.WHITE)))
        }
        return out
    }

    // ---- MINIMISED ROLLS (the user, 10-22: "I can't minimize the menu"): out of a fight the roll window folds into a small pill at the
    // bottom left; a tap on the pill opens it again; while folded every touch outside the pill reaches what is behind
    private var rollsMin = false
    private var rollsPill = RectF()
    private fun drawRollsPill(c: Canvas) {
        val n = rolls.size + rollsMore
        val text = "Loot rolls ($n)"
        val p = UiKit.bodyPaint(26f, Theme.gold)
        val w = p.measureText(text) + 60f
        rollsPill = RectF(16f, height - 86f, 16f + w, height - 22f)
        UiKit.menu(c, rollsPill, solid = true)
        c.drawText(Tr.t(text), rollsPill.left + 30f, rollsPill.centerY() + 9f, p)
        hits.add(rollsPill to "rolls:open")
    }

    /** the roll the window shows: one showing the game's bind confirm (the game's pop-up has the controller then, as in the game), else
     *  the finger's pick, else the game's own selection */
    private fun shownRoll(): RollBook.Roll? = confirmRoll() ?: rolls.firstOrNull { it.id == rollPick } ?: gameRoll()

    /** the roll showing the game's bind confirm (A Okay / B Cancel answer it before anything else) */
    private fun confirmRoll(): RollBook.Roll? = rolls.firstOrNull { book.rowOf(it) == RollBook.Row.CONFIRM }

    /** the roll the GAME's A / B / X act on out of a fight: its selection (after a controller answer, the one its frame takes next) */
    private fun gameRoll(): RollBook.Roll? =
        rolls.firstOrNull { it.id == gameGuess } ?: rolls.firstOrNull { it.sel } ?: rolls.firstOrNull()

    /** the timer's fraction left on [rl] (the seconds of its document, counted down here) */
    private fun timerOf(rl: RollBook.Roll): Float {
        val dur = rl.dur.toFloat()
        val gone = (System.currentTimeMillis() - rollsAt) / 1000f
        return if (dur > 0f) ((rl.left - gone) / dur).coerceIn(0f, 1f) else 1f
    }

    /** the words on [rl]'s bind confirm, ready to draw: the game's pop-up's own (never through Tr: game text), else the app's line in the
     *  game's words for such an item */
    private fun askWords(rl: RollBook.Roll): String =
        book.askOf(rl.id)?.text?.takeIf { it.isNotBlank() }?.let { plain(it) } ?: Tr.t("Binds when picked up")

    private fun drawRolls(c: Canvas) {
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val inner = UiKit.windowGround(c, r, marble = true)
        val top = r.top + 156f
        val ch = 92f; val cw = ch * 277f / 67f
        val cx0 = inner.left + 14f
        val shown = shownRoll()
        for ((i, rl) in rolls.withIndex()) {
            if (i >= 4) break
            val y = top + i * (ch + 26f)
            val card = RectF(cx0, y, cx0 + cw, y + ch)
            UiKit.rollCard(c, card, Icons.getOrUnknown(rl.icon.takeIf { it > 0 }), rl.name, rl.q, Theme.quality(rl.q),
                max(1, rl.count), timerOf(rl), rl.need, rl.greed, rl.transmog)
            if (rl === shown) UiKit.focus(c, RectF(card.left - 4f, card.top - 6f, card.right + 4f, card.bottom + 6f))
            hits.add(card to "card:${rl.id}")
        }
        if (rollsMore > 0) c.drawText(Tr.t("+$rollsMore more"), cx0 + 12f, top + min(4, rolls.size) * (ch + 26f) + 18f, UiKit.bodyPaint(24f, Theme.dim))
        // the chosen roll's tooltip; gear with a worn item in that slot: Currently Equipped beside it (two worn: one under the other)
        if (shown != null) {
            val tx = cx0 + cw + 26f
            val tw = (inner.right - 8f - tx - 14f) / 2f
            val tip = shown.o.optJSONObject("tip")
            val lines = tip?.optJSONArray("lines")
            UiKit.tooltip(c, tx, r.top + 112f, tw, shown.name, Theme.quality(shown.q), tipLines(lines, 1))
            val cmp = tip?.optJSONArray("cmp")
            var wy = r.top + 112f
            if (cmp != null) for (n in 0 until cmp.length()) {
                val wl = cmp.optJSONObject(n)?.optJSONArray("lines") ?: continue
                val first = wl.optJSONObject(0)
                val rr = UiKit.tooltip(c, tx + tw + 14f, wy, tw, first?.optString("t", "") ?: "", hexColour(first?.let { str(it, "c") }, Color.WHITE),
                    tipLines(wl, 1), header = "Currently Equipped")
                wy = rr.bottom + 14f
            }
        }
        // the strip: the game's own roll buttons, in its order Need, Greed (or Transmogrification), Disenchant, Pass; or, on a
        // bind-on-pickup roll the game asks about, ITS confirm: the words, Okay (A) / Cancel (B)
        val strip = UiKit.strip(c, inner)
        val cy = strip.centerY() + 2f
        val bx = inner.right - 8f
        val bs = 84f; val by = cy - 14f
        if (shown != null && book.rowOf(shown) == RollBook.Row.CONFIRM) {
            val ask = book.askOf(shown.id)
            val bw = 260f; val bh = 92f
            val okR = RectF(bx - 2 * bw - 24f, cy - bh / 2f, bx - bw - 24f, cy + bh / 2f)
            val noR = RectF(bx - bw, cy - bh / 2f, bx, cy + bh / 2f)
            UiKit.button(c, okR, ask?.okLabel ?: "Okay", UiKit.okGlyph(), if (isDown("ask:${shown.id}:ok")) UiKit.Button.DOWN else UiKit.Button.UP)
            UiKit.button(c, noR, ask?.cancelLabel ?: "Cancel", UiKit.backGlyph(), if (isDown("ask:${shown.id}:cancel")) UiKit.Button.DOWN else UiKit.Button.UP)
            hits.add(okR to "ask:${shown.id}:ok"); hits.add(noR to "ask:${shown.id}:cancel")
            val p = android.text.TextPaint(UiKit.bodyPaint(26f, Theme.gold))
            val words = askWords(shown)
            val lay = android.text.StaticLayout.Builder.obtain(words, 0, words.length, p, (okR.left - inner.left - 44f).toInt())
                .setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
            c.save(); c.translate(inner.left + 24f, cy - lay.height / 2f); lay.draw(c); c.restore()
        } else if (shown != null) {
            val canNeed = shown.need; val canGreed = shown.greed; val tm = shown.transmog
            val gKind = shown.xKind
            val id = shown.id
            // right to left: Pass, Disenchant (when the game allows it: touch only, as the game's own frame has no key for it), Greed, Need
            var x = bx - 70f
            UiKit.rollButton(c, x, by, bs, "pass", UiKit.backGlyph(), true, downStarts("roll:$id:pass"), label = "Pass")
            hits.add(RectF(x - 60f, by - 60f, x + 60f, by + 74f) to "roll:$id:pass")
            if (shown.de) {
                x -= 160f
                deButton(c, x, by, bs, true, downStarts("roll:$id:de"), label = "Disenchant")
                hits.add(RectF(x - 60f, by - 60f, x + 60f, by + 74f) to "roll:$id:de")
            }
            x -= 160f
            UiKit.rollButton(c, x, by, bs, gKind, UiKit.xGlyph(), canGreed || tm, downStarts("roll:$id:$gKind"),
                label = if (gKind == "transmog") "Transmog" else "Greed")
            if (canGreed || tm) hits.add(RectF(x - 60f, by - 60f, x + 60f, by + 74f) to "roll:$id:$gKind")
            x -= 160f
            UiKit.rollButton(c, x, by, bs, "need", UiKit.okGlyph(), canNeed, downStarts("roll:$id:need"), label = "Need")
            if (canNeed) hits.add(RectF(x - 60f, by - 60f, x + 60f, by + 74f) to "roll:$id:need")
            // a grey one: the game's reason, at the strip's left
            val why = shown.o.optJSONObject("why")
            val reason = (if (!canNeed) why?.let { str(it, "need") } else null) ?: (if (!canGreed && !tm) why?.let { str(it, "greed") } else null)
            if (reason != null) {
                val p = android.text.TextPaint(UiKit.bodyPaint(22f, Theme.unusable))
                val lay = android.text.StaticLayout.Builder.obtain(Tr.span(reason), 0, Tr.span(reason).length, p, (x - 80f - inner.left - 24f).toInt())
                    .setMaxLines(3).build()
                c.save(); c.translate(inner.left + 20f, cy - lay.height / 2f); lay.draw(c); c.restore()
            }
        }
        UiKit.windowFrame(c, r, "Loot Rolls", UiKit.tex("loot_dice"), Theme.gold, close = false)
        // the fold button (top right): out of a fight the window becomes a pill, in a fight the small panel again
        val mb = RectF(r.right - 110f, r.top + 20f, r.right - 24f, r.top + 92f)
        UiKit.menu(c, mb, solid = true)
        c.drawLine(mb.left + 24f, mb.centerY(), mb.right - 24f, mb.centerY(), UiKit.bodyPaint(26f, Theme.gold).apply { strokeWidth = 6f })
        hits.add(mb to "rolls:min")
        if (System.currentTimeMillis() < lingerUntil) postInvalidateOnAnimation()
    }

    /** the Disenchant roll button: the game's own disenchant art is not harvested, so its spell icon in the roll buttons' place */
    private fun deButton(c: Canvas, cx: Float, cy: Float, size: Float, enabled: Boolean, down: Boolean, label: String?) {
        val r = RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        UiKit.pressed(c, r, down && enabled) {
            val ic = Icons.get(DISENCHANT_ICON)
            val ir = RectF(r.left + size * 0.12f, r.top + size * 0.12f, r.right - size * 0.12f, r.bottom - size * 0.12f)
            c.save()
            c.clipPath(Path().apply { addCircle(ir.centerX(), ir.centerY(), ir.width() / 2f, Path.Direction.CW) })
            if (ic != null) c.drawBitmap(ic, null, ir, if (enabled) art else greyArt)
            c.restore()
            ring.color = if (enabled) Theme.gold else Theme.dim
            c.drawCircle(ir.centerX(), ir.centerY(), ir.width() / 2f, ring)
        }
        if (label != null) {
            val p = UiKit.bodyPaint(22f, if (enabled) Theme.gold else Theme.dim).apply { textAlign = Paint.Align.CENTER }
            c.drawText(Tr.t(label), cx, r.bottom + 6f - p.ascent(), p)
        }
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val greyArt = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }); alpha = 120
    }

    // ---- THE SMALL PANEL OVER THE MINIMAP (a fight)

    /** where the small panel stands: the top of Home's minimap module (else the right half), as tall as its rows */
    private fun compactBox(): RectF {
        val m = compactHost()
        val (n, more) = compactFit(m)
        val ph = compactHeight(n, more)
        val l = m.left + 8f; val t = m.top + 8f
        return RectF(l, t, max(l + 300f, m.right - 8f), t + ph)
    }

    /** the box the small panel stands in: Home's minimap module, else the right half */
    private fun compactHost(): RectF {
        val w = width.toFloat(); val h = height.toFloat()
        return mapBox() ?: RectF(w / 2f + 4f, 110f, w - UiKit.MODULE_GAP, h - 90f)
    }

    private fun compactHeight(n: Int, more: Int) = C_HEAD + n * C_ROW + (if (more > 0) C_MORE else 0f) + 14f

    /** review: the rows the panel has room for INSIDE [m] (1 .. 4) and how many it counts as "+N more" - with the tap-to-cast bar under
     *  the minimap (Right / Both) the module is cut short, and four rows reached ~100 px past it onto the cast bar, whose touches the
     *  panel then took */
    private fun compactFit(m: RectF): Pair<Int, Int> {
        val all = rolls.size
        var n = min(4, all)
        while (n > 1 && compactHeight(n, rollsMore + all - n) > m.height() - 16f) n--
        return n to (rollsMore + all - n)
    }

    /** the small panel, popping up (scale 0.9 -> 1 and in, Home's module timing); its touches are taken from the first frame, where its
     *  buttons land (the combat-touch ruling: the first touch acts) */
    private fun drawCompact(c: Canvas, now: Long) {
        val box = compactBox()
        compactRect = RectF(box)
        val t = (now - popAt).toFloat()
        val k = if (popAt > 0L) lootCurve.getInterpolation((t / Motion.MODULE_OPEN).coerceIn(0f, 1f)) else 1f
        if (k < 1f) {
            postInvalidateOnAnimation()
            val sv = c.saveLayerAlpha(RectF(0f, 0f, width.toFloat(), height.toFloat()), (255 * k).toInt().coerceIn(0, 255))
            val s = 0.9f + 0.1f * k
            c.scale(s, s, box.right, box.top)                                  // from the minimap's corner
            compactPanel(c, box, interactive = true)                          // (the hits are the landed layout's: plain px)
            c.restoreToCount(sv)
            return
        }
        compactPanel(c, box, interactive = true)
    }

    /** the panel's picture in [r]; [interactive] = its touches are taken (not while it pops or grows) */
    private fun compactPanel(c: Canvas, r: RectF, interactive: Boolean) {
        val inner = UiKit.menu(c, r, solid = true)
        // the head: the game's dice, its words, how many; the open button (the full window, with the controller)
        UiKit.tex("loot_dice")?.let { c.drawBitmap(it, null, RectF(inner.left + 4f, inner.top + 2f, inner.left + 44f, inner.top + 42f), art) }
        val n = rolls.size + rollsMore
        val tp = UiKit.bodyPaint(27f, Theme.gold)
        c.drawText(Tr.t(if (n > 1) "Loot rolls ($n)" else "Loot Rolls"), inner.left + 54f, inner.top + 22f - (tp.ascent() + tp.descent()) / 2f, tp)
        val ob = RectF(inner.right - 48f, inner.top, inner.right, inner.top + 44f)
        val down = isDown("rolls:full")
        ring.color = if (down) Color.WHITE else Theme.gold
        val pad = 12f
        // the open glyph: four corners pointing out
        val g = Path().apply {
            moveTo(ob.left + pad, ob.top + pad + 9f); lineTo(ob.left + pad, ob.top + pad); lineTo(ob.left + pad + 9f, ob.top + pad)
            moveTo(ob.right - pad - 9f, ob.top + pad); lineTo(ob.right - pad, ob.top + pad); lineTo(ob.right - pad, ob.top + pad + 9f)
            moveTo(ob.right - pad, ob.bottom - pad - 9f); lineTo(ob.right - pad, ob.bottom - pad); lineTo(ob.right - pad - 9f, ob.bottom - pad)
            moveTo(ob.left + pad + 9f, ob.bottom - pad); lineTo(ob.left + pad, ob.bottom - pad); lineTo(ob.left + pad, ob.bottom - pad - 9f)
        }
        c.drawPath(g, ring)
        if (interactive) hits.add(RectF(ob.left - 30f, r.top, r.right, inner.top + C_HEAD - 6f) to "rolls:full")
        // the rows
        var y = inner.top + C_HEAD - 6f
        val (fit, more) = compactFit(compactHost())
        for (rl in rolls.take(fit)) {
            val row = RectF(inner.left, y, inner.right, y + C_ROW - 6f)
            UiKit.divider(c, row.left + 6f, row.top - 3f, row.right - 6f, row.top - 3f)
            if (book.rowOf(rl) == RollBook.Row.CONFIRM) compactConfirm(c, row, rl, interactive) else compactRow(c, row, rl, interactive)
            y += C_ROW
        }
        if (more > 0) {
            val p = UiKit.bodyPaint(22f, Theme.dim)
            c.drawText(Tr.t("+$more more"), inner.left + 10f, y + 4f - p.ascent(), p)
        }
        if (System.currentTimeMillis() < lingerUntil) postInvalidateOnAnimation()
    }

    /** the item at a row's left: its icon in its quality's loot border, its count; returns where the words may start */
    private fun compactItem(c: Canvas, row: RectF, rl: RollBook.Roll): Float {
        val s = 58f
        val ir = RectF(row.left + 8f, row.centerY() - s / 2f, row.left + 8f + s, row.centerY() + s / 2f)
        Icons.getOrUnknown(rl.icon.takeIf { it > 0 })?.let { c.drawBitmap(it, null, ir, art) }
        UiKit.tex(UiKit.lootBorder(rl.q))?.let { val b = s * 0.62f; c.drawBitmap(it, null, RectF(ir.centerX() - b, ir.centerY() - b + 2f, ir.centerX() + b, ir.centerY() + b + 2f), art) }
        if (rl.count > 1) {
            val p = UiKit.numberPaint(20f, Color.WHITE).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(Tr.t(rl.count.toString()), ir.right - 4f, ir.bottom - 4f, p)
        }
        return ir.right + 12f
    }

    /** the item's name in its quality's colour (one line, cut), in [l] .. [rgt], centred on [cy] */
    private fun compactName(c: Canvas, rl: RollBook.Roll, l: Float, rgt: Float, cy: Float) {
        val p = UiKit.bodyPaint(24f, Theme.quality(rl.q))
        val line = android.text.TextUtils.ellipsize(rl.name, android.text.TextPaint(p), max(40f, rgt - l), android.text.TextUtils.TruncateAt.END).toString()
        // review: held inside its room too (a cut that did not hold let a long line run under the buttons)
        c.save(); c.clipRect(l, cy - 40f, max(l + 40f, rgt), cy + 40f)
        c.drawText(line, l, cy - (p.ascent() + p.descent()) / 2f, p)
        c.restore()
    }

    /** an open roll: icon, name, the timer under it, the game's buttons at the right - each one acts on the first touch */
    private fun compactRow(c: Canvas, row: RectF, rl: RollBook.Roll, interactive: Boolean) {
        val wl = compactItem(c, row, rl)
        val kinds = ArrayList<String>()
        kinds.add("need"); kinds.add(rl.xKind)
        if (rl.de) kinds.add("de")
        kinds.add("pass")
        val bs = 54f; val pitch = 62f
        val bx0 = row.right - kinds.size * pitch
        compactName(c, rl, wl, bx0 - 10f, row.top + 26f)
        // the timer: the game's yellow bar draining, under the name
        val tr = RectF(wl, row.top + 48f, bx0 - 14f, row.top + 56f)
        timerPaint.color = Color.BLACK; c.drawRect(tr, timerPaint)
        val tw = tr.width() * timerOf(rl)
        if (tw > 0.5f) {
            timerPaint.color = Color.rgb(230, 200, 0); c.drawRect(tr.left, tr.top, tr.left + tw, tr.bottom, timerPaint)
            timerPaint.color = Color.rgb(255, 236, 90); c.drawRect(tr.left, tr.top, tr.left + tw, tr.centerY(), timerPaint)
        }
        for ((i, kind) in kinds.withIndex()) {
            val cx = bx0 + i * pitch + pitch / 2f
            val on = rl.can(kind)
            val hit = "roll:${rl.id}:$kind"
            if (kind == "de") deButton(c, cx, row.centerY(), bs, on, isDown(hit), null)
            else UiKit.rollButton(c, cx, row.centerY(), bs, kind, null, on, isDown(hit))
            if (interactive && on) hits.add(RectF(cx - pitch / 2f, row.top, cx + pitch / 2f, row.bottom) to hit)
        }
    }
    private val timerPaint = Paint().apply { style = Paint.Style.FILL }

    /** a bind-on-pickup roll the game asks about: its words, the game's Okay / Cancel (its pop-up's own cells) */
    private fun compactConfirm(c: Canvas, row: RectF, rl: RollBook.Roll, interactive: Boolean) {
        val wl = compactItem(c, row, rl)
        val ask = book.askOf(rl.id)
        val bw = 112f; val bh = 58f
        val okR = RectF(row.right - 2 * bw - 10f, row.centerY() - bh / 2f, row.right - bw - 10f, row.centerY() + bh / 2f)
        val noR = RectF(row.right - bw, row.centerY() - bh / 2f, row.right, row.centerY() + bh / 2f)
        // review: the game's pop-up has the controller's focus (it takes A / B in a fight too, hidden while we draw it here)
        val focused = list.any { it.roll == rl.id && it.focus }
        val wordsR = okR.left - (if (focused) 28f else 10f)              // clear of the A badge
        compactName(c, rl, wl, okR.left - 10f, row.top + 26f)
        val p = UiKit.bodyPaint(19f, Theme.gold)
        val words = android.text.TextUtils.ellipsize(askWords(rl), android.text.TextPaint(p), max(40f, wordsR - wl), android.text.TextUtils.TruncateAt.END).toString()
        c.save(); c.clipRect(wl, row.top, max(wl + 40f, wordsR), row.bottom)       // review: never under the buttons, however long
        c.drawText(words, wl, row.top + 54f - (p.ascent() + p.descent()) / 2f, p)
        c.restore()
        val okHit = "ask:${rl.id}:ok"; val noHit = "ask:${rl.id}:cancel"
        UiKit.button(c, okR, ask?.okLabel ?: "Okay", if (isDown(okHit)) UiKit.Button.DOWN else UiKit.Button.UP, textSize = 24f)
        UiKit.button(c, noR, ask?.cancelLabel ?: "Cancel", if (isDown(noHit)) UiKit.Button.DOWN else UiKit.Button.UP, textSize = 24f)
        // ... its keys on its buttons then, as the game shows them (the roll buttons' badge, at the bottom left)
        if (focused) {
            UiKit.pad(c, UiKit.okGlyph(), okR.left + 4f, okR.bottom - 8f, 30f)
            UiKit.pad(c, UiKit.backGlyph(), noR.left + 4f, noR.bottom - 8f, 30f)
        }
        if (interactive) {
            hits.add(RectF(okR.left - 4f, row.top, okR.right + 4f, row.bottom) to okHit)
            hits.add(RectF(noR.left - 4f, row.top, noR.right + 4f, row.bottom) to noHit)
        }
    }

    // ---- THE GROW (a fight's end, or the player opening the panel): the small panel's box grows into the window, the window's picture
    // scaled from it (as Interact's loot window grows into Make Space), the panel fading over the first half
    private val lootCurve = Motion.curve()

    private fun drawGrow(c: Canvas, now: Long) {
        val full = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val from = growFrom
        val k = lootCurve.getInterpolation(((now - growAt).toFloat() / GROW_MS).coerceIn(0f, 1f))
        val cx = from.centerX() + (full.centerX() - from.centerX()) * k
        val cy = from.centerY() + (full.centerY() - from.centerY()) * k
        val sx = from.width() / full.width() + (1f - from.width() / full.width()) * k
        val sy = from.height() / full.height() + (1f - from.height() / full.height()) * k
        val save = c.save()
        c.translate(cx - full.centerX() * sx, cy - full.centerY() * sy)
        c.scale(sx, sy)
        c.saveLayerAlpha(full, (255 * (0.35f + 0.65f * k)).toInt().coerceIn(0, 255))
        drawRolls(c)
        c.restoreToCount(save)
        if (k < 0.5f) {
            val sv = c.saveLayerAlpha(full, (255 * (1f - 2f * k)).toInt().coerceIn(0, 255))
            compactPanel(c, from, interactive = false)
            c.restoreToCount(sv)
        }
        hits.clear()
        postInvalidateOnAnimation()
    }

    /** why a tap on a roll did nothing: over the strip (the window), under the panel (a fight) */
    private fun rollNote(c: Canvas, m: RollMode) {
        val nt = noteText ?: return
        if (System.currentTimeMillis() - noteAt >= 2500) return
        val p = UiKit.bodyPaint(26f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
        if (m == RollMode.COMPACT) c.drawText(Tr.t(nt), compactRect.centerX(), compactRect.bottom + 34f, p)
        else c.drawText(Tr.t(nt), width / 2f, height - 150f, p)
    }

    companion object {
        const val BOX_W = 1040f
        const val STALE_MS = 40_000L          // the addon re-sends every 15 s; two missed = the game is gone
        const val LIVE_MS = 25_000L           // taps need a document this recent
        const val LOCKOUT_MS = 500L           // one touch = one action
        const val SETTLE_MS = 700L            // after the dialogs changed: a tap may have been aimed at the old one
        const val PRESS_MIN_MS = 160L         // a press stays visibly DOWN at least this long
        const val FOLD_MS = 240L              // the dialog folds away this fast after the press
        const val FOLD_REFUSE_MS = 1_500L
        const val PAD_FALLBACK_MS = 400L      // a document this much newer than the controller's press that still lists its dialog
        const val PAD_FALLBACK_MAX_MS = 6_000L   // later than this the press is stale: nothing is tapped
        const val PAD_POKE_MS = 450L          // the sign of life that asks the add-on for that document     // a document this much newer that still lists the dialog = the game refused
        private val PAD_SLOTS = listOf(PadInput.SLOT_FACE_BOTTOM to "PAD1", PadInput.SLOT_FACE_RIGHT to "PAD2",
                                       PadInput.SLOT_FACE_LEFT to "PAD3", PadInput.SLOT_FACE_TOP to "PAD4",
                                       // combat_nav: Left / Right between the loot rolls while the app has the pad in a fight (fightPress)
                                       PadInput.SLOT_DPAD_LEFT to "PADDLEFT", PadInput.SLOT_DPAD_RIGHT to "PADDRIGHT")
        const val MIN_LIFE_GAP_MS = 1_000L    // never more than one sign of life a second
        private val DAYS_BEFORE = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        // loot-pop: the fight's small roll panel (px) and its grow into the window
        const val C_HEAD = 58f                // the panel's head (the dice, its words, the open button)
        const val C_ROW = 86f                 // one roll's row
        const val C_MORE = 34f                // the "+N more" line
        const val GROW_MS = 280L              // the panel growing into the window (a fight's end)
        /** review: after a finger's answer a fresh document is asked for this long after RollBook.REFUSE_MS (a lost tap comes back) */
        const val REFUSE_POKE_MS = 150L
        /** the game's Disenchant spell icon (INV_Enchant_Disenchant): the Disenchant roll's button */
        const val DISENCHANT_ICON = 132878
    }
}

/**
 * fix/stream-cursor (the user, 2026-11-19, streaming 1.0.36: "the cursor keeps randomly showing on the game"; taps.log: `iact:1 (desktop
 * post)` every 5 s under the "Resurrect now?" dialog). The sign of life that keeps a dialog off the top screen (Dialogs.lua) was a pad
 * click every `life.every` s, and on a stream every posted click is a cursor the PC's WoW draws. With Desktop clicking ([desk]) the sign
 * is ONE tap per newly drawn set of dialogs: the add-on's `stay` cell (Dialogs d35: that dialog stays off the top screen until it goes or
 * a real mouse moves), else the plain `alive` once. Local is unchanged (a sign at once, then every [everyMs]). Pure: the column to tap and
 * whether it is the stay cell, or null = nothing now.
 */
object LifeSign {
    fun pick(fresh: Boolean, sinceMs: Long, everyMs: Long, desk: Boolean, aliveCol: Int, stayCol: Int): Pair<Int, Boolean>? {
        if (!fresh && (desk || sinceMs < everyMs)) return null
        if (sinceMs < DialogView.MIN_LIFE_GAP_MS) return null
        if (desk && stayCol >= 0) return stayCol to true
        return if (aliveCol >= 0) aliveCol to false else null
    }
}
