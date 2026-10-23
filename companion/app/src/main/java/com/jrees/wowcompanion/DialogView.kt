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
        val fkey: String?)

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
    private var rolls: List<JSONObject> = emptyList()
    private var rollsMore = 0
    private var rollsAt = 0L                   // when the rolls' seconds were read
    private var rollGameSel = -1               // the roll the game has chosen (its D-pad)
    private var rollPick = -1                  // a finger's choice of card, the app's alone (the game's is never written)

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
    private val rollGone = HashMap<Int, Long>()   // roll id -> the game clock when it was answered

    private fun keyOf(d: Dlg) = "${d.i}:${d.which}"
    private fun isDown(hit: String) =
        pressed == hit || padHit == hit || (lingerHit == hit && System.currentTimeMillis() < lingerUntil)
    private fun downStarts(prefix: String) =
        pressed.startsWith(prefix) || padHit.startsWith(prefix) || (lingerHit.startsWith(prefix) && System.currentTimeMillis() < lingerUntil)

    /** a choice was made on [d] (or on roll [rollId]): keep its button DOWN a moment, then fold it away */
    private fun chose(hit: String, d: Dlg?, rollId: Int = -1) {
        val now = System.currentTimeMillis()
        lingerHit = hit; lingerUntil = now + PRESS_MIN_MS + FOLD_MS     // DOWN through the fold too
        if (d != null) { foldKey = keyOf(d); foldAt = now + PRESS_MIN_MS; foldGt = gameNow() }
        if (rollId >= 0) rollGone[rollId] = gameNow()
        postInvalidateOnAnimation()
        postDelayed(ticker, PRESS_MIN_MS + FOLD_MS + 20L)
    }

    /** the fold's progress 0..1 for [d] (0 = not folding); >= 1 = folded away */
    private fun foldOf(d: Dlg): Float {
        if (foldKey != keyOf(d)) return 0f
        val t = System.currentTimeMillis() - foldAt
        return if (t <= 0L) 0f else (t.toFloat() / FOLD_MS)
    }

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
            if (d != null) {
                // the game answers only the dialog it focuses, with the key it gives each button
                if (!d.focus || foldOf(d) > 0f) continue
                val delayed = secondsOf(d.delay) > 0
                val b = d.buttons.firstOrNull { it.key == key && it.on && !(delayed && it.k == 1) }
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
            } else if (rolls.isNotEmpty()) {
                // the rolls: the game keeps the controller (A Need, X Greed / Transmog, B Pass) on ITS selected roll
                val r = rolls.firstOrNull { it.optBoolean("sel") } ?: rolls.firstOrNull() ?: continue
                val canGreed = r.optBoolean("greed"); val tm = r.optBoolean("transmog")
                val hit = when (key) {
                    "PAD1" -> if (r.optBoolean("need")) "roll:need" else null
                    "PAD3" -> if (canGreed) "roll:greed" else if (tm) "roll:transmog" else null
                    "PAD2" -> "roll:pass"
                    else -> null
                } ?: continue
                padHit = hit; padKey = key; changed = true
                rollPadPress[r.optInt("id")] = hit.substring(5) to gameNow()
                chose(hit, null, r.optInt("id"))
            }
        }
        if (changed) postInvalidateOnAnimation()
    }
    private var padKey = ""

    // ---- A THE GAME DID NOT TAKE (b-release-spirit, 1.0.8 test16/17: the user's A on Release Spirit / Accept res "presses but nothing
    // happens and then it reappears", while a finger works). The press is the game's first; a document written PAD_FALLBACK_MS or more
    // after it that still lists the SAME dialog (slot and which) means the game never got it: the button's own cell is tapped then, as
    // a finger's press. Never both: once the game took it the dialog is gone from that document; never another dialog: the slot's
    // which must still match. The add-on sends that document soon (the sign of life asks for one: Dialogs d20).
    private class PadPress(val key: String, val hit: String, val gt: Long)
    private var padPress: PadPress? = null

    /** a sign of life now-ish (a fresh document from the add-on, d20), so a refused press is seen within a second */
    private fun pokeSoon() {
        postDelayed({
            val d = doc ?: return@postDelayed
            val col = d.optJSONObject("life")?.optInt("col", -1) ?: -1
            if (col < 0 || !docCurrent || dryRun) return@postDelayed
            TapBridge.tap(context, d.optJSONObject("pad"), "iact", col) { }
        }, PAD_POKE_MS)
    }

    /**
     * NEVER PRESSED BY US (the user's standing ruling: never press "Exit Now"; test50: the d20 fallback tapped the QUIT countdown's "Exit
     * now" during the safe close): the QUIT dialog's button 1. The add-on (Dialogs d22) no longer mirrors it; this refuses it anyway.
     */
    private fun neverPress(d: Dlg, hit: String) = d.which == "QUIT" && hit == "b1"

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
            val bs = ArrayList<Btn>()
            val ba = d.optJSONArray("buttons")
            if (ba != null) for (m in 0 until ba.length()) {
                val b = ba.optJSONObject(m) ?: continue
                val k = b.optInt("k", 0)
                if (k !in 1..4) continue
                bs.add(Btn(k, str(b, "label") ?: "", b.optBoolean("on", true), str(b, "key"),
                           cells?.optInt("b$k", -1) ?: -1))
            }
            out.add(Dlg(d.optInt("i", 0), str(d, "which") ?: "", str(d, "text") ?: "", str(d, "sub"), bs,
                        str(d, "extra"), str(d, "extraKey"), cells?.optInt("extra", -1) ?: -1,
                        d.optBoolean("x"), cells?.optInt("x", -1) ?: -1,
                        d.optInt("left", 0), d.optDouble("dur", 0.0).toFloat(), d.optBoolean("bar"),
                        d.optInt("delay", 0), str(d, "alert"), d.optJSONObject("item"),
                        d.optLong("money", -1L), d.optBoolean("focus"), str(d, "top"), d.optBoolean("hid"),
                        str(d, "fkey")))
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
        // LOOT ROLLS (addon Dialogs d12): the game's rolls ride this document
        val ra = msg.optJSONArray("rolls")
        rolls = if (ra == null) emptyList() else (0 until ra.length()).mapNotNull { ra.optJSONObject(it) }
        rollsMore = msg.optInt("rollsMore", 0)
        rollsAt = System.currentTimeMillis()
        rollPadRefused()
        val gameSel = rolls.firstOrNull { it.optBoolean("sel") }?.optInt("id") ?: -1
        if (gameSel != rollGameSel) { rollGameSel = gameSel; rollPick = -1 }     // the controller moved: its choice wins
        if (rolls.none { it.optInt("id") == rollPick }) rollPick = -1
        // an answered roll stays away; it comes back if a document written well after the answer still lists it unanswered
        rollGone.entries.removeAll { (id, gt) -> rolls.none { it.optInt("id") == id } || docGt > gt + FOLD_REFUSE_MS }
        rolls = rolls.filter { !rollGone.containsKey(it.optInt("id")) }
        val s = out.joinToString("/") { d -> "${d.i}:${d.which}:" + d.buttons.joinToString(",") { "${it.k}${it.key}" } + ":${d.top}" } +
            "|r" + rolls.joinToString(",") { r -> "${r.optInt("id")}" + (if (r.optBoolean("need")) "n" else "") + (if (r.optBoolean("greed")) "g" else "") }
        if (s != sig) { sig = s; sigAt = System.currentTimeMillis(); pressed = "" }
        refresh()
    }

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

    private fun str(o: JSONObject, key: String) = if (o.has(key) && !o.isNull(key)) o.optString(key) else null

    // ---------------------------------------------------------------- which dialog is the box

    /** the dialogs this view may show as the box: mirrored ones only (not the ones that stay on top) */
    private fun mirrored() = list.filter { it.top == null }

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
        if (list.isNotEmpty() || rolls.isNotEmpty()) postDelayed(ticker, 500)
        invalidate()
    }

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
        val fresh = drawnSig != sig
        if (!fresh && now - lifeAt < every) return
        if (now - lifeAt < MIN_LIFE_GAP_MS) return
        drawnSig = sig
        lifeAt = now
        if (dryRun) { lastSent = "alive"; return }
        TapBridge.tap(context, d.optJSONObject("pad"), "iact", col) { }
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

    private fun rollSend(what: String, cell: Int): Boolean {
        val now = System.currentTimeMillis()
        blocked(now)?.let { note(it); return false }
        if (cell < 0) { note("The game gave this no tap cell"); return false }
        lastTapAt = now
        if (dryRun) { lastSent = "roll $what cell $cell"; note("Preview: $what"); return true }
        val ok = TapBridge.tap(context, doc?.optJSONObject("pad"), "isell", cell) { why -> post { note(why) } }
        if (!ok) note("The game has not published its tap pad")
        return ok
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hits.lastOrNull { it.first.contains(e.x, e.y) }?.second ?: ""
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val hit = hits.lastOrNull { it.first.contains(e.x, e.y) }?.second
                val was = pressed
                pressed = ""
                // b-release-spirit: every finger press on the dialog, so "it presses but nothing happens" has its reason in the log
                android.util.Log.i("WCDLG", "finger up on '${hit ?: "-"}' (down on '$was')" + if (hit == null || hit != was) ": no press" else "")
                if (hit != null && hit == was) tap(hit)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = ""; invalidate() }
        }
        if (rollsMin && active() == null && !rollsPill.contains(e.x, e.y)) return false    // folded: the rest of the screen is Home's
        return true       // the dialog owns every touch: nothing reaches what is behind it
    }

    private fun tap(hit: String) {
        if (hit == "rolls:min") { rollsMin = true; invalidate(); return }
        if (hit == "rolls:open") { rollsMin = false; invalidate(); return }
        if (hit.startsWith("card:")) { rollPick = hit.substring(5).toIntOrNull() ?: -1; invalidate(); return }
        if (hit.startsWith("roll:")) {
            // "roll:<kind>:<cell>": the game's own RollOnLoot through Interact's cell on the isell row
            val parts = hit.split(':')
            if (rollSend(parts[1], parts[2].toIntOrNull() ?: -1)) chose("roll:" + parts[1], null, shownRoll()?.optInt("id") ?: -1)
            return
        }
        val d = active() ?: run { android.util.Log.w("WCDLG", "press '$hit': no dialog is shown"); return }
        when {
            hit == "x" -> if (send("close", d.xCell)) chose(hit, d)
            hit == "extra" -> if (send(d.extra ?: "extra", d.extraCell)) chose(hit, d)
            hit.startsWith("b") -> {
                val b = d.buttons.firstOrNull { "b${it.k}" == hit } ?: return
                if (neverPress(d, hit)) { android.util.Log.w("WCDLG", "press on ${d.which} $hit refused (Exit now)"); return }
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
            for (r in l) { p.color = r.colour; c.drawText(r.s, x, base, p); x += p.measureText(r.s) }
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
        if (rolls.isEmpty()) rollsMin = false
        if (rolls.isNotEmpty()) { if (rollsMin && active() == null) drawRollsPill(c) else drawRolls(c) }
        val d = active()
        if (d == null) { if (rolls.isNotEmpty()) { rollNote(c); signOfLife() }; return }
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
            c.drawText("Answers itself in " + clock(left), cx, y + 20f - (p.ascent() + p.descent()) / 2f, p)
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
            c.drawText(words, x0 + g + 14f, y + g / 2f - (p.ascent() + p.descent()) / 2f, p)
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
            c.drawText(nt, cx, h - 28f, p)
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

    // ---- A ROLL THE GAME DID NOT TAKE (the user, 10-22, an event's loot rolls in combat: "my selections aren't triggering"): the
    // controller's Need / Greed / Pass is the game's first (its GamepadGroupLootRollFrame); a document written PAD_FALLBACK_MS or more
    // after it that still lists that roll means the game never got it: the roll's own cell is tapped then, as a finger's press, once
    private val rollPadPress = HashMap<Int, Pair<String, Long>>()
    private fun rollPadRefused() {
        if (docGt < 0) return
        val it = rollPadPress.entries.iterator()
        while (it.hasNext()) {
            val (id, kp) = it.next()
            val (kind, gt) = kp
            val roll = rolls.firstOrNull { r -> r.optInt("id") == id }
            if (roll == null || docGt - gt > PAD_FALLBACK_MAX_MS) { it.remove(); continue }
            if (docGt < gt + PAD_FALLBACK_MS) continue
            it.remove()
            val cell = roll.optJSONObject("cells")?.optInt(kind, -1) ?: -1
            android.util.Log.w("WCDLG", "the game did not take the controller's $kind on roll $id (${docGt - gt} ms): tapping its cell $cell")
            if (cell >= 0) rollSend(kind, cell)
        }
    }

    // ---- MINIMISED ROLLS (the user, 10-22: "I can't minimize the menu"): the roll window folds into a small pill at the bottom left;
    // a tap on the pill opens it again; while folded every touch outside the pill reaches what is behind
    private var rollsMin = false
    private var rollsPill = RectF()
    private fun drawRollsPill(c: Canvas) {
        val n = rolls.size + rollsMore
        val text = "Loot rolls ($n)"
        val p = UiKit.bodyPaint(26f, Theme.gold)
        val w = p.measureText(text) + 60f
        rollsPill = RectF(16f, height - 86f, 16f + w, height - 22f)
        UiKit.menu(c, rollsPill, solid = true)
        c.drawText(text, rollsPill.left + 30f, rollsPill.centerY() + 9f, p)
        hits.add(rollsPill to "rolls:open")
    }

    private fun shownRoll(): JSONObject? =
        rolls.firstOrNull { it.optInt("id") == rollPick } ?: rolls.firstOrNull { it.optBoolean("sel") } ?: rolls.firstOrNull()

    private fun drawRolls(c: Canvas) {
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val inner = UiKit.windowGround(c, r, marble = true)
        val top = r.top + 156f
        val ch = 92f; val cw = ch * 277f / 67f
        val cx0 = inner.left + 14f
        val shown = shownRoll()
        val gone = (System.currentTimeMillis() - rollsAt) / 1000f
        for ((i, rl) in rolls.withIndex()) {
            if (i >= 4) break
            val y = top + i * (ch + 26f)
            val card = RectF(cx0, y, cx0 + cw, y + ch)
            val q = rl.optInt("q", 1)
            val dur = rl.optInt("dur", 0).toFloat()
            val timer = if (dur > 0f) ((rl.optInt("left", 0) - gone) / dur).coerceIn(0f, 1f) else 1f
            UiKit.rollCard(c, card, Icons.getOrUnknown(rl.optInt("icon", 0).takeIf { it > 0 }), rl.optString("name", ""), q, Theme.quality(q),
                max(1, rl.optInt("count", 1)), timer, rl.optBoolean("need"), rl.optBoolean("greed"), rl.optBoolean("transmog"))
            if (rl === shown) UiKit.focus(c, RectF(card.left - 4f, card.top - 6f, card.right + 4f, card.bottom + 6f))
            hits.add(card to "card:${rl.optInt("id")}")
        }
        if (rollsMore > 0) c.drawText("+$rollsMore more", cx0 + 12f, top + 4 * (ch + 26f) + 18f, UiKit.bodyPaint(24f, Theme.dim))
        // the chosen roll's tooltip; gear with a worn item in that slot: Currently Equipped beside it (two worn: one under the other)
        if (shown != null) {
            val tx = cx0 + cw + 26f
            val tw = (inner.right - 8f - tx - 14f) / 2f
            val tip = shown.optJSONObject("tip")
            val lines = tip?.optJSONArray("lines")
            val q = shown.optInt("q", 1)
            UiKit.tooltip(c, tx, r.top + 112f, tw, shown.optString("name", ""), Theme.quality(q), tipLines(lines, 1))
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
        // the strip: the game's own roll buttons, in its footer's order Need, Greed (or Transmogrification), Pass
        val strip = UiKit.strip(c, inner)
        val cy = strip.centerY() + 2f
        val bx = inner.right - 8f
        val bs = 84f; val by = cy - 14f
        if (shown != null) {
            val cells = shown.optJSONObject("cells")
            fun cell(k: String) = cells?.optInt(k, -1) ?: -1
            val canNeed = shown.optBoolean("need"); val canGreed = shown.optBoolean("greed"); val tm = shown.optBoolean("transmog")
            val gKind = if (!canGreed && tm) "transmog" else "greed"
            UiKit.rollButton(c, bx - 70f, by, bs, "pass", UiKit.backGlyph(), true, downStarts("roll:pass"), label = "Pass")
            UiKit.rollButton(c, bx - 230f, by, bs, gKind, "rev_x", canGreed || tm, downStarts("roll:$gKind"),
                label = if (gKind == "transmog") "Transmog" else "Greed")
            UiKit.rollButton(c, bx - 390f, by, bs, "need", "rev_a", canNeed, downStarts("roll:need"), label = "Need")
            hits.add(RectF(bx - 130f, by - 60f, bx - 10f, by + 74f) to "roll:pass:${cell("pass")}")
            if (canGreed || tm) hits.add(RectF(bx - 290f, by - 60f, bx - 170f, by + 74f) to "roll:$gKind:${cell(gKind)}")
            if (canNeed) hits.add(RectF(bx - 450f, by - 60f, bx - 330f, by + 74f) to "roll:need:${cell("need")}")
            // a grey one: the game's reason, at the strip's left
            val why = shown.optJSONObject("why")
            val reason = (if (!canNeed) why?.let { str(it, "need") } else null) ?: (if (!canGreed && !tm) why?.let { str(it, "greed") } else null)
            if (reason != null) {
                val p = android.text.TextPaint(UiKit.bodyPaint(22f, Theme.unusable))
                val lay = android.text.StaticLayout.Builder.obtain(reason, 0, reason.length, p, (bx - 470f - inner.left - 24f).toInt())
                    .setMaxLines(3).build()
                c.save(); c.translate(inner.left + 20f, cy - lay.height / 2f); lay.draw(c); c.restore()
            }
        }
        UiKit.windowFrame(c, r, "Loot Rolls", UiKit.tex("loot_dice"), Theme.gold, close = false)
        // the fold button (top right): the window becomes a pill
        val mb = RectF(r.right - 110f, r.top + 20f, r.right - 24f, r.top + 92f)
        UiKit.menu(c, mb, solid = true)
        c.drawLine(mb.left + 24f, mb.centerY(), mb.right - 24f, mb.centerY(), UiKit.bodyPaint(26f, Theme.gold).apply { strokeWidth = 6f })
        hits.add(mb to "rolls:min")
    }

    /** why a tap on a roll did nothing, over the strip */
    private fun rollNote(c: Canvas) {
        val nt = noteText ?: return
        if (System.currentTimeMillis() - noteAt >= 2500) return
        val p = UiKit.bodyPaint(26f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
        c.drawText(nt, width / 2f, height - 150f, p)
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
                                       PadInput.SLOT_FACE_LEFT to "PAD3", PadInput.SLOT_FACE_TOP to "PAD4")
        const val MIN_LIFE_GAP_MS = 1_000L    // never more than one sign of life a second
        private val DAYS_BEFORE = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
    }
}
