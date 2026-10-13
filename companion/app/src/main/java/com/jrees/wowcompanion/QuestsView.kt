package com.jrees.wowcompanion

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import java.io.File
import android.os.SystemClock
import kotlin.math.*

/**
 * The QUESTS room: the whole quest log, fed by the WowCompanionQuests addon.
 *
 * Layout (the user's call): the LIST on the left, the DETAIL of the selected quest on the right,
 * and the actions live in the detail pane -- never on the rows.
 *
 * DISTANCE IS COMPUTED HERE, not sent. The addon deliberately leaves distance out of its change
 * test (it would turn an event-driven feed into a per-step one), so a quest's objective position
 * arrives once and the player's position arrives on the map's own stream. Yards come from the
 * zone's world rect, which is exact on both axes -- zone maps are 3:2, so distance measured in
 * 0..1 map units would be wrong by up to 50% depending on bearing.
 *
 * ACTIONS GO THROUGH THE TAP PAD (TapBridge): the addon publishes an invisible grid (`pad`) and each
 * quest's column (`slot`). An action is a PAIR of taps, the quest's cell and then the action's cell
 * ([QuestPad]); the addon's reply names the quest it acted on, and a reply for another quest is shown
 * as a refusal. (The first build typed slash commands into chat; that left the chat box open and is
 * broken on this device.)
 * ABANDON is two taps by design: ARM makes the addon name the quest it would drop, the confirm bar
 * shows that name, and only CONFIRM abandons.
 * Actions are only offered when the game is provably LIVE, by the APP'S OWN GATE ([GameInput.live], InputGate):
 * a document the game wrote after the app started, within 20 s. That is the gate TapBridge itself enforces, so the
 * room can never offer what the bridge would refuse, nor refuse what it would send. (Until 2026-09-30 the room kept
 * its own older rule, two quests documents 10 s apart; on the Thor at 18:06:21 it still said "Checking the game is
 * live" three seconds after a tap through the app's gate had been answered.)
 */
class QuestsView(
    context: Context,
    private val where: () -> JSONObject?,
    // No button uses it since 2026-09-30 (the user: "instead of a map button I want to change that function to focus").
    // Kept so MainActivity's wiring does not have to change with it.
    @Suppress("unused") private val onShowOnMap: (JSONObject) -> Unit,
) : View(context) {

    // ---------------------------------------------------------------- state
    private var doc: JSONObject? = null
    private var quests: List<JSONObject> = emptyList()
    private var selectedId = -1

    private var lastDocAt = 0L

    private var pendingOp: String? = null
    private var pendingId = -1               // the quest the pending action was meant for, -1 = none
    private var pendingAt = 0L
    private var noteText: String? = null
    private var noteOk = true
    private var noteAt = 0L

    private var confirmAbandonId = -1
    private var confirmTitle = ""       // the ADDON's name for the armed quest, not the app's
    private var confirmAt = 0L

    private var listScroll = 0f
    private var detailScroll = 0f
    private var listContentH = 0f
    private var detailContentH = 0f

    // ---------------------------------------------------------------- paints
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val pTitle = tp(32f, Theme.gold, bold = true)
    private val pHead = tp(21f, Theme.dim)
    private val pZone = tp(21f, Theme.textDim)
    private val pRow = tp(25f, Theme.text)
    private val pRowSub = tp(20f, Theme.textDim)
    private val pDist = tp(21f, Theme.textDim)
    private val pDTitle = tp(40f, Theme.text, bold = true)
    private val pMeta = tp(20f, Theme.dim)
    private val pObj = tp(22f, Theme.text)
    private val pBody = tp(20f, Theme.textDim)
    private val pBtn = tp(24f, Theme.text, bold = true)
    private val pSection = tp(30f, Theme.gold)               // a heading on the dark ground, in Morpheus
    private val pInk = tp(22f, Theme.parchmentInk)           // what is read lies on parchment, in ink
    private val pInkHead = tp(30f, Theme.parchmentInk)
    private val pArt = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pFade = Paint()

    private fun tp(size: Float, colour: Int, bold: Boolean = false) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; color = colour; isFakeBoldText = bold }

    // "!" and "?" from the POI sheet (cells 18 and 19). NO GAME ART IN THE APK (the user's harvest rule, 2026-10-09): the sheet is the
    // ON-DEVICE HARVEST's, built from the user's own game files (ArtHarvest.rootDir/ui/poi_icons.png, Map's step), else the runtime
    // pack's old place on /sdcard, else a drawn mark. The harvest may finish after the room first draws: a missing sheet is looked for
    // again at most every SHEET_RETRY_MS, and kept once found.
    private var sheetBmp: Bitmap? = null
    private var sheetLookAt = 0L
    private fun sheet(): Bitmap? {
        sheetBmp?.let { return it }
        val now = SystemClock.uptimeMillis()
        if (sheetLookAt != 0L && now - sheetLookAt < SHEET_RETRY_MS) return null
        sheetLookAt = now
        val files = listOfNotNull(ArtHarvest.rootDir?.let { File(it, "ui/poi_icons.png") }, File("/sdcard/WowCompanion/ui/poi_icons.png"))
        for (f in files) {
            val b = try { if (f.isFile) BitmapFactory.decodeFile(f.path) else null } catch (_: Throwable) { null }
            if (b != null && b.height > 0 && b.width >= 20 * b.height) { sheetBmp = b; return b }
        }
        return null
    }
    // the drawn mark without the sheet: the game's quest yellow, bold, with a dark edge
    private val pMark = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 209, 0); isFakeBoldText = true; textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }
    private val src = Rect()
    private val dst = RectF()
    private fun glyph(c: Canvas, idx: Int, cx: Float, cy: Float, size: Float, alpha: Int = 255) {
        val s = sheet()
        if (s == null) {
            pMark.textSize = size * 0.9f
            pMark.alpha = alpha
            c.drawText(if (idx == 19) "?" else "!", cx, cy - (pMark.ascent() + pMark.descent()) / 2f, pMark)
            return
        }
        val cell = s.height
        src.set(idx * cell, 0, (idx + 1) * cell, cell)
        dst.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        pArt.alpha = alpha
        c.drawBitmap(s, src, dst, pArt)
    }

    // ---------------------------------------------------------------- hit boxes
    private val rowHits = ArrayList<Pair<RectF, Int>>()
    private val btnHits = ArrayList<Pair<RectF, String>>()
    // PRESS FEEDBACK (the user, 2026-10-07 via tracking: "all other context buttons should have similar feedback"; Interact's recipe,
    // the kit's press on DOWN): the button a finger is on shows pushed in at once and stays so at least PRESS_MIN_MS after the lift; the
    // controller's A / B / X / Y pushes in the button carrying its glyph AT ONCE (DirectPad's press), before the action itself runs
    private var pressed = ""
    private var lingerUntil = 0L
    private val glyphHits = HashMap<String, String>()   // glyph -> the action of the enabled button drawn with it this frame
    // b117 (the user on b116: "the button push is delayed a moment after I push it, its working but it feels slow and laggy"): a press
    // reaches [press] through the game when DirectPad is not delivering (the relayed guestpad, 60-170 ms + a redraw). The app's own
    // controller reader (PadHub, via Spells' PressFeedback) shows A / B / X / Y's button DOWN in the press's own frame, while the room
    // shows and holds the controller -- the drawing only: what the press does still comes from [press]
    private val padNow = PressFeedback(this).apply {
        padTarget = { k ->
            if (!isShown || !held) null
            else glyphHits[when (k) { "PAD1" -> "rev_a"; "PAD2" -> UiKit.backGlyph(); "PAD3" -> "rev_x"; "PAD4" -> "rev_y"; else -> "" }]
        }
    }
    private var splitX = 0f
    private var bodyTop = 0f

    // ---------------------------------------------------------------- input from MainActivity

    fun update(q: JSONObject) {
        val now = System.currentTimeMillis()
        lastDocAt = now
        doc = q
        // the first quests document (its pad) since the show, still unanswered: ask once more (HoldAsk)
        if (q.has("pad") && holdAsk.padArrived()) post { askHold() }
        val list = ArrayList<JSONObject>()
        q.optJSONArray("list")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { list.add(it) } }
        quests = list
        if (list.none { it.optInt("id") == selectedId }) {
            // keep the old place in the list when the selected quest vanishes (abandoned, turned in)
            val focus = list.firstOrNull { it.optBoolean("focus") }
            selectedId = (focus ?: list.firstOrNull())?.optInt("id") ?: -1
            detailScroll = 0f
            menuSel = -1                                    // the More menu was for the quest that left
        }
        if (confirmAbandonId != -1 && list.none { it.optInt("id") == confirmAbandonId }) confirmAbandonId = -1
        if (held) cursor.show()
        postInvalidate()
    }

    /** {"type":"questack","op":..,"id":..,"ok":..,"why":..} */
    fun ack(msg: JSONObject) {
        val op = msg.optString("op")
        if (pendingOp == null || pendingOp != op) return     // a replayed ack from the backlog
        pendingOp = null
        // The addon names the quest it acted on. It must be the one this room meant: the pick and the
        // action are two taps, and the confirm bar must never show a quest the player did not choose.
        val meant = pendingId; pendingId = -1
        val got = if (msg.has("id") && !msg.isNull("id")) msg.optInt("id", -1) else -1
        if (meant != -1 && got != -1 && got != meant) {
            note("Refused: the game answered for another quest", false)
            return
        }
        val ok = msg.optBoolean("ok")
        if (op == "arm" && ok) {
            // the addon has named the quest it would abandon: now, and only now, ask the player
            confirmAbandonId = msg.optInt("id", -1)
            confirmTitle = msg.optString("title", "this quest")
            confirmAt = System.currentTimeMillis()
            if (confirmAbandonId != -1) selectedId = confirmAbandonId
            syncB()
            postInvalidate()
            return
        }
        note(if (ok) when (op) {
            "watch" -> "Tracked"; "unwatch" -> "Untracked"; "abandon" -> "Abandoned"; "track" -> "Focused"
            "untrack" -> "Focus removed"; "share" -> "Shared"
            else -> "Done"
        } else "Refused: " + msg.optString("why", "no reason given"), ok)
    }

    private fun note(s: String, ok: Boolean) {
        noteText = s; noteOk = ok; noteAt = System.currentTimeMillis(); postInvalidate()
    }

    private fun live(now: Long) = GameInput.live(now)

    /**
     * why actions are unavailable right now, or null if they are available. NOT a fight (SPEC_COMBAT item 4, the user's agreed
     * spec: "everything the game itself allows in combat"): the quest actions are engine calls the game allows in a fight, so
     * the room acts there too (Quests.lua q13); what the game refuses comes back in the answer, and the addon flashes Home red.
     */
    private fun blocked(now: Long): String? = when {
        lastDocAt == 0L -> "Waiting for the game"
        !live(now) -> GameInput.gate.why(now) ?: "Checking the game is live"
        pendingOp != null -> "Waiting for the game to answer"
        else -> null
    }

    /** the quest's column on the addon's tap pad, or -1 when it has none */
    private fun slotOf(q: JSONObject) = if (q.has("slot") && !q.isNull("slot")) q.optInt("slot", -1) else -1

    /** One action on the pad: the quest's cell, then the action's. [id] is the quest it is meant for (-1: none). */
    private fun send(act: QuestPad.Act, slot: Int, id: Int) {
        val pad = doc?.optJSONObject("pad")
        val cells = QuestPad.cells(pad, act, slot)
        val ok = cells != null && TapBridge.taps(context, pad, cells) { why ->
            post { pendingOp = null; pendingId = -1; note(why, false) }
        }
        if (!ok) { note("The game has not published its tap pad yet", false); return }
        pendingOp = act.reply; pendingId = id; pendingAt = System.currentTimeMillis()
        postInvalidate()
    }

    // ---------------------------------------------------------------- geometry helpers

    /** live yards + relative bearing (radians, + = to the left), or null when not on this map */
    private fun liveDistance(q: JSONObject): Pair<Double, Double?>? {
        if (!q.has("x") || !q.has("y")) return null
        return distanceTo(doc?.optInt("mapID", -1) ?: -1, q.optDouble("x"), q.optDouble("y"))
    }

    /** yards + relative bearing to the point (x, y, 0..1) of map [map], or null when the player is not on that map */
    private fun distanceTo(map: Int, x: Double, y: Double): Pair<Double, Double?>? {
        val m = where() ?: return null
        if (m.optInt("mapID", -2) != map) return null
        val r = m.optJSONObject("rect") ?: return null
        val wYd = r.optDouble("l", Double.NaN) - r.optDouble("r", Double.NaN)
        val hYd = r.optDouble("t", Double.NaN) - r.optDouble("b", Double.NaN)
        if (!(wYd > 1.0 && hYd > 1.0)) return null
        val px = m.optDouble("x", Double.NaN); val py = m.optDouble("y", Double.NaN)
        if (px.isNaN() || py.isNaN()) return null
        val east = (x - px) * wYd
        val south = (y - py) * hYd
        val facing = m.optDouble("facing", Double.NaN)
        // WoW facing: 0 = north, increasing counter-clockwise (pi/2 = west). Same for bearing.
        val bearing = atan2(-east, -south)
        return hypot(east, south) to (if (facing.isNaN()) null else bearing - facing)
    }

    private fun distLabel(q: JSONObject): Pair<String, Double?> {
        liveDistance(q)?.let { (yd, rel) -> return "${yd.roundToInt()} yd" to rel }
        if (q.has("dist")) return "~${q.optInt("dist")} yd" to null
        return "" to null
    }

    /**
     * TRACKED = in the game's objective tracker, i.e. what the game's own "Track" button toggles
     * (C_QuestLog.GetQuestWatchType is non-nil). NOT super-tracking ("focus"), which is the single
     * quest with the on-screen waypoint. The first build tied the button to focus and so showed
     * TRACK on quests the game listed as tracked -- the user caught it on the device.
     */
    private fun tracked(q: JSONObject) = q.has("watch") && !q.isNull("watch")

    private fun diffColour(q: JSONObject): Int {
        if (q.optBoolean("trivial")) return GREY
        val d = doc ?: return Theme.gold
        if (!q.has("lv") || !d.has("plv")) return Theme.gold
        val diff = q.optInt("lv") - d.optInt("plv")
        val green = if (d.has("trivRange")) d.optInt("trivRange") else 5
        return when {
            diff >= 5 -> RED
            diff >= 3 -> ORANGE
            diff >= -2 -> YELLOW
            -diff <= green -> GREEN
            else -> GREY
        }
    }

    private fun progress(q: JSONObject): Float? {
        val a = q.optJSONArray("obj") ?: return null
        var have = 0; var need = 0
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.has("need") && o.optInt("need") > 0) {
                need += o.optInt("need"); have += min(o.optInt("have"), o.optInt("need"))
            } else { need += 1; if (o.optBoolean("f")) have += 1 }
        }
        return if (need > 0) have.toFloat() / need else null
    }

    private fun summary(q: JSONObject): Pair<String, Int> {
        if (q.optBoolean("failed")) return "Failed" to RED
        if (q.optBoolean("ready") || q.optBoolean("done")) return "Ready to turn in" to Theme.ok
        val a = q.optJSONArray("obj")
        if (a != null) {
            var first: String? = null; var open = 0
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (!o.optBoolean("f")) { open++; if (first == null) first = o.optString("t", "") }
            }
            if (first != null) return (if (open > 1) "$first  +${open - 1} more" else first) to Theme.textDim
        }
        return (q.optString("wp", "")) to Theme.textDim
    }

    private fun arrow(c: Canvas, cx: Float, cy: Float, rel: Double, colour: Int, outline: Boolean = false) {
        c.save()
        c.rotate(Math.toDegrees(-rel).toFloat(), cx, cy)
        val p = Path().apply {
            moveTo(cx, cy - 11f); lineTo(cx + 8f, cy + 9f); lineTo(cx, cy + 4f); lineTo(cx - 8f, cy + 9f); close()
        }
        if (outline) {
            pStroke.color = colour; pStroke.strokeWidth = 2f
            c.drawPath(p, pStroke)
            pStroke.strokeWidth = 1.6f
        } else {
            pFill.color = colour
            c.drawPath(p, pFill)
        }
        c.restore()
    }

    private fun fit(p: TextPaint, s: String, w: Float): String =
        TextUtils.ellipsize(s, p, max(w, 10f), TextUtils.TruncateAt.END).toString()

    private fun layout(s: String, p: TextPaint, w: Int): StaticLayout =
        StaticLayout.Builder.obtain(s, 0, s.length, p, max(w, 10))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(3f, 1f).build()

    // ---------------------------------------------------------------- touch

    private var downX = 0f; private var downY = 0f; private var lastY = 0f
    private var dragging = false; private var dragList = true

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastY = e.y; dragging = false; dragList = e.x < splitX
                btnHits.firstOrNull { it.first.contains(e.x, e.y) }?.let { pressed = it.second; lingerUntil = Long.MAX_VALUE; invalidate() }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.y - downY) > 18f) { dragging = true; if (pressed.isNotEmpty()) { pressed = ""; invalidate() } }
                if (dragging) {
                    val dy = lastY - e.y
                    if (dragList) listScroll += dy else detailScroll += dy
                    invalidate()
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> if (!dragging) tap(e.x, e.y) else if (pressed.isNotEmpty()) { pressed = ""; invalidate() }
            MotionEvent.ACTION_CANCEL -> if (pressed.isNotEmpty()) { pressed = ""; invalidate() }
        }
        return true
    }

    /** the button [act] shows DOWN now and for at least PRESS_MIN_MS (Interact's lingerPress) */
    private fun lingerPress(act: String) {
        if (act.isEmpty()) return
        pressed = act
        lingerUntil = System.currentTimeMillis() + PRESS_MIN_MS
        invalidate()
        postDelayed({ if (pressed == act && System.currentTimeMillis() >= lingerUntil) { pressed = ""; invalidate() } }, PRESS_MIN_MS + 10L)
    }

    private fun tap(x: Float, y: Float) {
        if (menuSel >= 0) {
            // the More menu: a row chooses it; anywhere else closes the menu (and does nothing else)
            for ((box, i) in menuHits) if (box.contains(x, y)) { pick(i); return }
            closeMenu(); return
        }
        for ((box, act) in btnHits) if (box.contains(x, y)) { lingerPress(act); action(act); return }
        if (pressed.isNotEmpty()) { pressed = ""; invalidate() }
        for ((box, id) in rowHits) if (box.contains(x, y)) { select(id); invalidate(); return }
    }

    /**
     * the USER chose a quest (touch or D-pad): the details follow. NOTHING is sent: the game's focus no longer follows the
     * selection (the user, RC3 2026-10-09: "I don't like the auto focus in the quests menu"); Focus is in the More menu.
     */
    private fun select(id: Int) {
        if (id == selectedId) return
        selectedId = id; detailScroll = 0f; confirmAbandonId = -1; menuSel = -1
        ensureVisible(id)
    }

    // ---------------------------------------------------------------- MORE (the user, RC3 2026-10-09: "bring back the more button
    // and move focus and share to that menu"; RC4: "Y more with abandon inside, Track/ untrack set to A"): Y = More, a menu in the
    // game's own look (UiKit.menu / menuRow, as Bags' menus) over the button bar with the quest's name and three rows, in the game's
    // words (its quest menu, QuestMapLogTitleButton_CreateContextMenu): Focus / Remove Focus (SUPER_TRACK_QUEST /
    // STOP_SUPER_TRACK_QUEST), Share Quest (SHARE_QUEST; grey with the game's reason when it cannot be shared) and Abandon Quest
    // (ABANDON_QUEST; the confirm bar follows, as before). Track stays a button (A), so nothing is offered twice. A chooses, B closes.
    private data class MoreItem(val act: String, val label: String, val enabled: Boolean)
    private var menuSel = -1                     // the highlighted row; -1 = the menu is closed
    private val menuHits = ArrayList<Pair<RectF, Int>>()

    private fun moreItems(q: JSONObject, now: Long): List<MoreItem> {
        val block = blocked(now)
        val padDoc = doc?.optJSONObject("pad")
        val slot = slotOf(q)
        val focused = q.optBoolean("focus")
        val canFocus = QuestPad.cells(padDoc, if (focused) QuestPad.Act.UNFOCUS else QuestPad.Act.FOCUS, slot) != null
        val why = shareReason(q)
        val canShare = QuestPad.cells(padDoc, QuestPad.Act.SHARE, slot) != null && why == null
        val reachable = QuestPad.cells(padDoc, QuestPad.Act.WATCH, slot) != null
        val canAb = !q.has("canAbandon") || q.optBoolean("canAbandon")
        return listOf(
            MoreItem("focus", if (focused) "Remove Focus" else "Focus", block == null && canFocus),
            MoreItem("share", if (why == null) "Share Quest" else "Share Quest: $why", block == null && canShare),
            MoreItem("abandon", "Abandon Quest", block == null && canAb && reachable))
    }

    private fun closeMenu() {
        if (menuSel < 0) return
        menuSel = -1
        syncB(); invalidate()
    }

    /** a row of the More menu, by the controller's A or a touch: a grey row says why, a live one closes the menu and acts */
    private fun pick(i: Int) {
        val q = selectedQuest() ?: return
        val now = System.currentTimeMillis()
        val item = moreItems(q, now).getOrNull(i) ?: return
        lingerPress("menu$i")
        if (!item.enabled) {
            note(blocked(now) ?: (if (item.act == "share") shareReason(q) else null)
                ?: (if (item.act == "abandon") "That quest cannot be abandoned." else null) ?: "Not available for this quest", false)
            return
        }
        closeMenu()
        action(item.act)
    }

    /** the More menu over the button bar, its right edge on the More button's ([right]); A Choose / B Back under it while the room holds */
    private fun drawMore(c: Canvas, q: JSONObject, right: Float, actTop: Float, maxW: Float, now: Long) {
        val items = moreItems(q, now)
        if (menuSel >= items.size) menuSel = items.size - 1
        val w = min(maxW, 640f)
        val h = 56f + items.size * 96f + 24f
        val bottom = actTop - 70f
        val box = RectF(right - w, bottom - h, right, bottom)
        val inner = UiKit.menu(c, box, solid = true)
        val np = TextPaint(UiKit.bodyPaint(26f, Color.WHITE))
        c.drawText(fit(np, q.optString("title"), inner.width() - 32f), inner.left + 16f, inner.top + 34f, np)
        for ((i, item) in items.withIndex()) {
            val r = RectF(inner.left, inner.top + 56f + i * 96f, inner.right, inner.top + 56f + i * 96f + 92f)
            UiKit.pressed(c, r, item.enabled && pressed == "menu$i") {
                UiKit.menuRow(c, r, item.label, menuSel == i, enabled = item.enabled, size = 28f)
            }
            menuHits.add(RectF(r) to i)
        }
        if (held && cursor.shown) UiKit.hints(c, box.right - 16f, box.bottom + 36f, "rev_a" to "Choose", UiKit.backGlyph() to "Back")
    }

    private fun selectedQuest(): JSONObject? = quests.firstOrNull { it.optInt("id") == selectedId }

    private fun action(act: String) {
        val q = selectedQuest() ?: return
        val id = q.optInt("id")
        val now = System.currentTimeMillis()
        when (act) {
            "cancel" -> { confirmAbandonId = -1; syncB() }
            // A / the More button: the menu opens on its first row (it only shows; its rows act)
            "more" -> if (menuSel >= 0) closeMenu() else { menuSel = 0; syncB() }
            else -> {
                blocked(now)?.let { note(it, false); return }
                val slot = slotOf(q)
                if (slot < 0 && act != "abandon!") { note("This quest has no place on the game's tap pad yet", false); return }
                when (act) {
                    // X: the game's tracker
                    "track" -> send(if (tracked(q)) QuestPad.Act.UNWATCH else QuestPad.Act.WATCH, slot, id)
                    "share" -> shareReason(q)?.let { note(it, false) } ?: send(QuestPad.Act.SHARE, slot, id)
                    // the More menu's Focus / Remove Focus: the game's super-tracking (the waypoint), never the tracker
                    "focus" -> send(if (q.optBoolean("focus")) QuestPad.Act.UNFOCUS else QuestPad.Act.FOCUS, slot, id)
                    // first press: ARM only -- the confirm bar appears when the addon names the quest
                    "abandon" -> send(QuestPad.Act.ARM, slot, id)
                    "abandon!" -> if (confirmAbandonId == id) {
                        confirmAbandonId = -1
                        send(QuestPad.Act.CONFIRM, 0, id)
                    }
                }
            }
        }
        invalidate()
    }

    // ---------------------------------------------------------------- Share Quest (the game's own, Quests.lua q11)
    /**
     * The one line above the button bar, and whether it is the question (else Share's reason), or null: Abandon's question (the game's ABANDON_QUEST_CONFIRM,
     * naming the quest the ADDON armed) while the confirm bar is up, else why Share is grey (in the game's words).
     */
    @Suppress("UNUSED_PARAMETER")
    private fun aboveBarLine(q: JSONObject, now: Long): Pair<String, Boolean>? {
        if (confirmAbandonId == q.optInt("id")) return "Abandon \"$confirmTitle\"?" to true      // true = the question
        return null                                     // RC3: Share's reason sits in its More menu row
    }

    /** why Share Quest cannot be used now, in the game's words, or null when it can (the doc's party / push, Quests.lua q11) */
    private fun shareReason(q: JSONObject): String? = when {
        doc?.optBoolean("party") != true -> "You are not in a party."          // ERR_QUEST_PUSH_NOT_IN_PARTY_S
        !q.optBoolean("push") -> "That quest cannot be shared."                // ERR_QUEST_PUSH_NOT_ALLOWED_S
        else -> null
    }

    // ---------------------------------------------------------------- the controller (LATER item 5, Home's spec)
    // While the room shows it holds the controller as the generic guest "quests" ([GuestPad]; Quests.lua q6 takes interact's
    // shared capture for it). The addon relays every press as {"type":"guestpad"}; [QuestCursor] turns it into what a touch
    // would do. B with nothing nearer to do leaves with "B": the capture's document then takes the app Home (MainActivity's
    // route for every guest). MainActivity calls [onShown] / [onHidden] and routes the id "quests" documents here.
    private val cursor = QuestCursor()
    private var lastPadN = -1
    private var held = false
    private var bSaid = "home"
    // the game's quest log behind the room ([QuestsWin], Quests.lua q25 + Map.lua mr2): Quests.lua's word (questwin "shown"), Map's
    // (mapwin: the map shows, its shared open cell armed), and whether this visit tapped the open cell already
    private var qShown = false
    private var mapOpen = false
    private var openArmed = false
    private var openTapped = false
    // the room's OPEN tapped, and neither Quests.lua's questwin nor Map's mapwin has said "shown" yet (RC3: a leave in that gap)
    private var openPending = false

    /** only when the leave-with-B cell cannot be tapped: MainActivity's own way Home */
    var onLeave: (() -> Unit)? = null

    private fun pad(): JSONObject? = doc?.optJSONObject("pad")
    private fun guestTap(cells: List<Pair<String, Int>>?): Boolean = cells != null && TapBridge.taps(context, pad(), cells)

    // THE HOLD IS ASKED AGAIN while it has no answer ([HoldAsk], as the Map room: Home's consistency pass / audit, the user's
    // burn-down 2026-10-07). It used to be ONE tap on show: a lost tap, or a show before the quests document (and its pad) was known,
    // left the room on screen with the controller still the game's. Now every EVERY_MS up to MAX asks; any answer stops it (the hold's
    // ack either way, a relayed or direct press), and so does hiding; the first quests document after the show asks once more.
    private val holdAsk = HoldAsk()
    private val holdTick = Runnable { askHold() }
    private fun askHold() {
        removeCallbacks(holdTick)
        if (!holdAsk.due()) return
        if (holdAsk.asked(guestTap(GuestPad.cells(pad(), "quests", hold = true)))) postDelayed(holdTick, HoldAsk.EVERY_MS)
    }

    fun onShown() {
        lastPadN = -1; bSaid = "home"; openTapped = false; openPending = false
        cursor.reset()
        holdAsk.shown(); askHold()
        padNow.setActive(true)
    }

    fun onHidden() {
        holdAsk.hidden(); removeCallbacks(holdTick)
        padNow.setActive(false)
        menuSel = -1
        held = false
        cursor.reset()
        // q25: the game's close while its quest log shows (Quests.lua lets go on the window's OnHide); RC3: the close AND the free,
        // spaced, while the room's open may still be on its way ([QuestsWin.leavePlan]); else the free
        val free = GuestPad.cells(pad(), "quests", hold = false)
        when (QuestsWin.leavePlan(qShown, mapOpen, openPending, MapRoomPad.has(pad()))) {
            QuestsWin.Leave.CLOSE -> guestTap(MapRoomPad.cell(MapRoomPad.CLOSE))
            QuestsWin.Leave.CLOSE_THEN_FREE ->
                TapBridge.tapsSpaced(context, pad(), MapRoomPad.cell(MapRoomPad.CLOSE) + (free ?: emptyList()), LEAVE_GAP_MS)
            QuestsWin.Leave.FREE -> guestTap(free)
        }
        openPending = false
    }

    // q37 CLOSE FIRST: while the game's map still shows, Quests.lua keeps the room ("close first": the map stays invisible) -- a free
    // that beat the open, a close tap that strayed, Home's own free. Close it (the OnHide lets go), and again a few times if the
    // answer does not come; a close on a closed map carries nothing (Map arms it only while the map shows).
    private var closeTries = 0
    private val closeAgain = Runnable { if (closeTries in 1 until CLOSE_TRIES) closeMap() }
    private fun closeMap() {
        removeCallbacks(closeAgain)
        if (!MapRoomPad.has(pad())) return
        if (guestTap(MapRoomPad.cell(MapRoomPad.CLOSE))) closeTries++
        if (closeTries < CLOSE_TRIES) postDelayed(closeAgain, CLOSE_RETRY_MS)
    }
    private fun closeDone() { closeTries = 0; removeCallbacks(closeAgain) }

    /** {"type":"questwin"} (Quests.lua q25): whether the game's quest log shows behind the room */
    fun onQuestWin(msg: JSONObject) {
        qShown = msg.optBoolean("shown", false)
        // RC3: "shown" ends a pending open; "hidden" no longer re-arms the open (the room opens the log ONCE per visit: a stale
        // "hidden" could tap the open cell a second time, or open the map again right as the room leaves)
        if (qShown) openPending = false else closeDone()
        maybeOpen()
    }

    /** Map.lua's "mapwin" state: the map shows, and its shared open cell is armed (for "pad:quests" too, mr2) */
    fun onMapWin(w: MapRoomPad.Win) {
        mapOpen = w.open
        openArmed = w.armed
        if (mapOpen) openPending = false
        maybeOpen()
    }

    /** the way in, once the hold is real: open the game's quest log behind the room (every room opens its window behind it) */
    private fun maybeOpen() {
        if (!isShown) return
        if (!QuestsWin.wantOpen(held, qShown, mapOpen, openArmed, openTapped, MapRoomPad.has(pad()))) return
        if (guestTap(MapRoomPad.cell(MapRoomPad.OPEN))) { openTapped = true; openPending = true }
    }

    /** {"type":"guestack","id":"quests"} */
    fun onGuestAck(msg: JSONObject) {
        when (msg.optString("op")) {
            // the room holds the controller: the cursor is there at once, on the selected quest (the user, 2026-09-30)
            // "pending" (capture v10 / v11: queued, or the fight's pending ENTER) does not hold yet: the game still has the pad
            "hold" -> {
                holdAsk.answered()                      // the add-on has spoken, either way: no more asks
                if (msg.optBoolean("ok")) {
                    held = msg.optString("why") != "pending"; bSaid = "home"; cursor.show(); invalidate()
                    maybeOpen()
                }
            }
            "free", "leave" -> when {
                msg.optBoolean("ok") -> { held = false; closeDone() }
                msg.optString("why") == "close first" -> closeMap()           // q37: the map first; the OnHide lets go
            }
        }
    }

    /**
     * {"type":"guestpad","id":"quests"}: one press RELAYED by the game through its log (~0.2 s), only while the room shows
     * (MainActivity checks). Since DirectPad it is the fallback: MainActivity drops it while it delivers [onDirect].
     */
    fun onPad(msg: JSONObject) {
        holdAsk.answered()                                  // a relayed press or a lost hold: the add-on has spoken
        if (msg.has("lost")) { held = false; cursor.reset(); invalidate(); return }
        val n = msg.optInt("n", -1)
        if (n == lastPadN) return                           // the same press seen twice
        lastPadN = n
        press(msg.optString("k"), direct = false)
    }

    /**
     * DIRECTPAD (Home's PadHub, the user's "All of it" for responsiveness, 2026-10-06): one press read from the controller
     * itself (~20 ms), on the PRESS. MainActivity delivers it only while this room shows AND the capture holds "pad:quests"
     * (never while pending: the game would act on the same press). [repeat] = a held key's repeat; only [QuestCursor.REPEAT_KEYS]
     * (the list's up / down) act on one.
     */
    fun onDirect(k: String, repeat: Boolean = false) {
        holdAsk.answered()                                  // MainActivity delivers only while the capture names "pad:quests"
        if (repeat && !cursor.repeats(k)) return
        press(k, direct = true)
    }

    private fun press(k: String, direct: Boolean) {
        held = true
        cursor.show()
        // the button carrying this key's glyph pushes in AT ONCE (it was drawn last frame: the glyphs show while the room holds)
        glyphHits[when (k) { "PAD1" -> "rev_a"; "PAD2" -> UiKit.backGlyph(); "PAD3" -> "rev_x"; "PAD4" -> "rev_y"; else -> "" }]
            ?.let { lingerPress(it) }
        val q = selectedQuest()
        val confirm = q != null && confirmAbandonId == q.optInt("id")
        val rows = if (menuSel >= 0 && q != null) moreItems(q, System.currentTimeMillis()).size else 0
        when (val d = cursor.key(k, quests.map { it.optInt("id") }, selectedId, confirm, menuSel, rows)) {
            is QuestCursor.Do.Select -> select(d.id)
            QuestCursor.Do.More -> action("more")
            is QuestCursor.Do.MenuMove -> menuSel = d.i
            is QuestCursor.Do.MenuPick -> pick(d.i)
            QuestCursor.Do.MenuClose -> closeMenu()
            QuestCursor.Do.Track -> action("track")
            QuestCursor.Do.Cancel -> action("cancel")
            QuestCursor.Do.ConfirmAbandon -> action("abandon!")
            QuestCursor.Do.Leave -> leave(direct)
            QuestCursor.Do.Nothing -> {}
        }
        syncB()
        invalidate()
    }

    /**
     * B with nothing nearer to do. Relayed: the leave-with-B tap, and the capture's document takes the app Home. Direct: Home AT
     * ONCE ([onLeave]); out of a fight the release tap follows, in a fight none (capture v10's router binds B to the secure
     * RoomLeave, so the game's own B is the leave and its lost("B") confirms). A lost release is Home's refree.
     */
    private fun leave(direct: Boolean) {
        if (!direct) { if (!guestTap(GuestPad.leaveB(pad()))) onLeave?.invoke(); return }
        if (doc?.optBoolean("cb") != true) guestTap(GuestPad.leaveB(pad()))
        onLeave?.invoke()
    }

    /** tell the capture where B leads now, so the Home button shows B only when it leads Home */
    private fun syncB() {
        if (!held) return
        val confirm = confirmAbandonId != -1 && confirmAbandonId == selectedId
        val where = cursor.bLeads(confirm, menuSel >= 0)
        if (where != bSaid && guestTap(GuestPad.bnext(pad(), home = where == "home"))) bSaid = where
    }

    /** scroll the list so the selected row and its arrows' gaps are on screen (same arithmetic as [drawList]) */
    private fun ensureVisible(id: Int) {
        var y = 0f
        var zone: String? = null
        for (q in quests) {
            val z = q.optString("zone", "")
            if (z != zone) { zone = z; if (z.isNotEmpty()) y += ZONE_H }
            val sel = q.optInt("id") == selectedId
            if (q.optInt("id") == id) {
                val top = y
                val bottom = y + ROW_H + (if (sel) 2 * SEL_GAP else 0f)
                val viewH = height - bodyTop
                if (top < listScroll) listScroll = top
                else if (bottom > listScroll + viewH) listScroll = bottom - viewH
                return
            }
            y += ROW_H + (if (sel) 2 * SEL_GAP else 0f)
        }
    }

    // ---------------------------------------------------------------- draw

    /** the game's type (UI art, fonts step). Set where the paints are USED: the kit can reach the
     *  device after this view is built, and a paint keeps the font it was given. */
    private fun fonts() {
        UiKit.asTitle(pTitle); UiKit.asTitle(pDTitle)
        UiKit.asBody(pHead); UiKit.asBody(pZone); UiKit.asBody(pRow); UiKit.asBody(pRowSub)
        UiKit.asBody(pMeta); UiKit.asBody(pObj); UiKit.asBody(pBody); UiKit.asBody(pBtn)
        UiKit.asNumber(pDist)
        UiKit.asTitle(pSection)
        UiKit.asBody(pInk, shadow = false); UiKit.asTitle(pInkHead, shadow = false)   // the game draws no shadow on paper
    }

    override fun onDraw(c: Canvas) {
        fonts()
        val now = System.currentTimeMillis()
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(Theme.ink)
        rowHits.clear(); btnHits.clear(); glyphHits.clear(); menuHits.clear()

        if (pendingOp != null && now - pendingAt > PENDING_TIMEOUT_MS) {
            pendingOp = null; pendingId = -1
            note("No answer from the game", false)
        }
        if (confirmAbandonId != -1 && now - confirmAt > CONFIRM_TIMEOUT_MS) { confirmAbandonId = -1; syncB() }

        // ---- header
        val d = doc
        UiKit.header(c, w, "Quests",
            if (d == null) null else if (d.has("maxQ")) "${quests.size} / ${d.optInt("maxQ")}" else "${quests.size}", HEADER)
        val status: Pair<String, Int>? = when {
            noteText != null && now - noteAt < NOTE_MS -> noteText!! to (if (noteOk) Theme.ok else Theme.warn)
            else -> blocked(now)?.takeIf { d != null && pendingOp == null }?.let { it to Theme.dim }
        }
        status?.let { (s, col) ->
            pHead.color = col
            c.drawText(s, w - 20f - pHead.measureText(s), 42f, pHead)
            pHead.color = Theme.dim
        }

        bodyTop = HEADER
        splitX = floor(w * 0.57f)
        if (d == null) {
            pRowSub.color = Theme.dim
            c.drawText("Waiting for quests. The WowCompanionQuests addon starts on the next /reload.",
                20f, HEADER + 48f, pRowSub)
            return
        }
        if (quests.isEmpty()) {
            pRowSub.color = Theme.dim
            c.drawText("Your quest log is empty.", 20f, HEADER + 48f, pRowSub)
            return
        }

        drawList(c, 0f, bodyTop, splitX, h)
        pFill.color = Theme.bronzeDim; c.drawRect(splitX, bodyTop, splitX + 1f, h, pFill)
        drawDetail(c, splitX + 1f, bodyTop, w, h, now)

        // keep distances and bearings current while the room is on screen
        if (isShown) postInvalidateDelayed(REFRESH_MS)
    }

    private fun drawList(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        val viewH = y1 - y0
        listScroll = listScroll.coerceIn(0f, max(0f, listContentH - viewH))
        c.save()
        c.clipRect(x0, y0, x1, y1)
        var y = y0 - listScroll
        var zone: String? = null
        for ((i, q) in quests.withIndex()) {
            val z = q.optString("zone", "")
            if (z != zone) {
                zone = z
                if (z.isNotEmpty()) {
                    c.drawText(z, x0 + 22f, y + 30f, pZone)
                    y += ZONE_H
                }
            }
            // ONE selector (the user's rework): the selected row opens a gap above and below for the up / down arrows
            val sel = q.optInt("id") == selectedId
            if (sel) y += SEL_GAP
            val box = RectF(x0, y, x1, y + ROW_H)
            if (box.bottom > y0 - SEL_GAP && box.top < y1 + SEL_GAP) drawRow(c, q, box, up = i > 0, down = i < quests.size - 1)
            if (box.bottom > y0 && box.top < y1) {
                rowHits.add(RectF(box.left, max(box.top, y0), box.right, min(box.bottom, y1)) to q.optInt("id"))
            }
            y += ROW_H
            if (sel) y += SEL_GAP
        }
        listContentH = y + listScroll - y0 + 12f
        c.restore()
    }

    private fun drawRow(c: Canvas, q: JSONObject, box: RectF, up: Boolean, down: Boolean) {
        val id = q.optInt("id")
        val sel = id == selectedId
        val inset = RectF(box.left + 8f, box.top + 3f, box.right - 8f, box.bottom - 3f)
        if (sel) {
            // THE selector (UI Art's SHEET_quests_v3): the kit's selection box; its up / down arrows in the gaps while the
            // controller is the room's
            UiKit.focus(c, inset)
            if (held && cursor.shown) UiKit.moveArrowsUpDown(c, inset, up = up, down = down, size = 22f, width = 34f,
                around = true, gap = SEL_GAP)
        }
        if (q.optBoolean("focus")) {
            if (sel) {
                pFill.color = Color.rgb(255, 240, 150)
                c.drawRoundRect(RectF(inset.left + 4f, inset.top + 6f, inset.left + 12f, inset.bottom - 6f), 3f, 3f, pFill)
            } else {
                pFill.color = Theme.gold
                c.drawRoundRect(RectF(inset.left, inset.top, inset.left + 6f, inset.bottom), 3f, 3f, pFill)
            }
        }

        val ready = q.optBoolean("ready") || q.optBoolean("done")
        val failed = q.optBoolean("failed")
        glyph(c, if (ready) 19 else 18, inset.left + 34f, inset.centerY() - 6f, 40f, if (failed) 110 else 255)

        // distance + bearing on the right of line 1
        val (dl, rel) = distLabel(q)
        var right = inset.right - 14f
        if (dl.isNotEmpty()) {
            val dw = pDist.measureText(dl)
            c.drawText(dl, right - dw, inset.top + 38f, pDist)
            right -= dw + 8f
            if (rel != null) { arrow(c, right - 10f, inset.top + 31f, rel, Theme.gold); right -= 28f }
        }
        // the game's tracker mark, so the list agrees with the objective tracker at a glance
        if (tracked(q)) { drawIcon(c, ICON_TRACK, right - 12f, inset.top + 30f, Theme.bronze); right -= 34f }

        val tx = inset.left + 66f
        val col = diffColour(q)
        val lvl = if (q.has("lv")) "[${q.optInt("lv")}] " else ""
        pRow.color = col
        c.drawText(fit(pRow, lvl + q.optString("title", "Quest ${q.optInt("id")}"), right - tx - 8f),
            tx, inset.top + 38f, pRow)

        val (sub, subCol) = summary(q)
        pRowSub.color = subCol
        c.drawText(fit(pRowSub, sub, inset.right - 14f - tx), tx, inset.top + 70f, pRowSub)

        // progress hairline
        progress(q)?.let { f ->
            val bx0 = tx; val bx1 = inset.right - 14f; val by = inset.bottom - 12f
            pFill.color = Theme.bronzeDim; c.drawRect(bx0, by, bx1, by + 3f, pFill)
            pFill.color = if (ready) Theme.ok else Theme.gold
            c.drawRect(bx0, by, bx0 + (bx1 - bx0) * f.coerceIn(0f, 1f), by + 3f, pFill)
        }
    }

    private fun drawDetail(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, now: Long) {
        val q = quests.firstOrNull { it.optInt("id") == selectedId } ?: return
        val pad = 22f
        val tw = (x1 - x0 - pad * 2).toInt()
        val actTop = y1 - ACTIONS_H
        // a line above the bar (Share's reason in the game's words, or Abandon's question) has a strip of its own, so it never
        // lies over the description (Home's b17 shot: the reason over the description's last line)
        val above = aboveBarLine(q, now)
        val textBottom = if (above != null) actTop - ABOVE_BAR_H else actTop
        val viewH = textBottom - y0

        detailScroll = detailScroll.coerceIn(0f, max(0f, detailContentH - viewH))
        c.save()
        c.clipRect(x0, y0, x1, textBottom)
        c.translate(x0 + pad, y0 + 18f - detailScroll)
        var y = 0f

        pDTitle.color = diffColour(q)
        val tl = layout(q.optString("title", "Quest"), pDTitle, tw)
        tl.draw(c); y += tl.height + 8f

        val meta = buildList {
            if (q.has("lv")) add("Level ${q.optInt("lv")}")
            q.optString("zone", "").takeIf { it.isNotEmpty() }?.let { add(it) }
            if (q.optInt("grp", 0) > 1) add("Group ${q.optInt("grp")}")
            when (q.optInt("freq", 0)) { 1 -> add("Daily"); 2 -> add("Weekly") }
            add(if (tracked(q)) "Tracked" else "Not tracked")
            if (q.optBoolean("focus")) add("Focused")
        }.joinToString("  ·  ")
        c.drawText(fit(pMeta, meta, tw.toFloat()), 0f, y + 20f, pMeta); y += 34f

        val (st, stCol) = when {
            q.optBoolean("failed") -> "Failed" to RED
            q.optBoolean("ready") || q.optBoolean("done") -> "Ready to turn in" to Theme.ok
            else -> null to 0
        }
        if (st != null) {
            pObj.color = stCol; c.drawText(st, 0f, y + 24f, pObj); pObj.color = Theme.text
            y += 38f
        }

        // QUESTIEDB ([QuestQdb], q18+): the finisher's name (the game never gives it), and -- when the quest is ready and the GAME
        // shows no point -- where it is. Its marks are the room's own, quieter: dim, the arrow in outline, never the live gold.
        val qi = QuestQdb.parse(q)
        val gamePoint = QuestQdb.gameShowsPoint(q)
        var qdbShown = false
        QuestQdb.turninWho(qi)?.let { who ->
            qdbShown = true
            var tail: String? = null; var rel: Double? = null
            if ((q.optBoolean("ready") || q.optBoolean("done")) && !gamePoint) qi?.turnin?.let { s ->
                val d = distanceTo(s.m, s.x, s.y)
                if (d != null) { tail = "${d.first.roundToInt()} yd"; rel = d.second } else tail = QuestQdb.elsewhere(doc, s)
            }
            var tx = 0f
            rel?.let { arrow(c, 11f, y + 16f, it, Theme.textDim, outline = true); tx = 32f }
            pDist.color = Theme.textDim
            val label = "Turn in: "
            c.drawText(label, tx, y + 24f, pDist); tx += pDist.measureText(label)
            val name = who.removePrefix(label)
            pDist.color = Theme.text; c.drawText(name, tx, y + 24f, pDist); tx += pDist.measureText(name)
            pDist.color = Theme.textDim
            tail?.let { c.drawText(fit(pDist, "  ·  $it", tw - tx), tx, y + 24f, pDist) }
            y += 38f
        }

        // distance line
        val (dl, rel) = distLabel(q)
        if (dl.isNotEmpty()) {
            if (rel != null) arrow(c, 11f, y + 16f, rel, Theme.gold)
            pDist.color = Theme.text
            c.drawText(if (rel != null) dl else "$dl (not on this map)", if (rel != null) 32f else 0f, y + 24f, pDist)
            pDist.color = Theme.textDim
            y += 38f
        }
        q.optString("wp", "").takeIf { it.isNotEmpty() }?.let {
            // StaticLayout draws at the canvas origin, so move there and back
            c.save(); c.translate(0f, y)
            val l = layout("Next: $it", pBody, tw); l.draw(c)
            c.restore(); y += l.height + 10f
        }

        // objectives
        val objs = q.optJSONArray("obj")
        if (objs != null && objs.length() > 0) {
            y += 6f
            c.drawText("Objectives", 0f, y + 24f, pSection); y += 40f
            for (i in 0 until objs.length()) {
                val o = objs.optJSONObject(i) ?: continue
                val fin = o.optBoolean("f")
                pObj.color = if (fin) Theme.dim else Theme.text
                val t = o.optString("t", "").ifEmpty {
                    if (o.has("need")) "${o.optInt("have")}/${o.optInt("need")}" else "objective"
                }
                c.save(); c.translate(34f, y)
                val ol = layout(t, pObj, tw - 34)
                ol.draw(c); c.restore()
                if (fin) {
                    pStroke.color = Theme.ok; pStroke.strokeWidth = 3f
                    c.drawPath(Path().apply { moveTo(4f, y + 14f); lineTo(11f, y + 21f); lineTo(24f, y + 6f) }, pStroke)
                    pStroke.strokeWidth = 1.6f
                } else {
                    pFill.color = Theme.gold; c.drawCircle(13f, y + 14f, 4f, pFill)
                }
                y += ol.height + 10f
                // QuestieDB's nearest spot for this objective, only while it is open and the game shows no point for the quest
                val spot = if (fin || gamePoint) null else qi?.obj?.get(i + 1)
                if (spot != null) {
                    val d = distanceTo(spot.m, spot.x, spot.y)
                    val words = if (d != null) "\u2248 ${d.first.roundToInt()} yd" else QuestQdb.elsewhere(doc, spot)
                    if (words != null) {
                        qdbShown = true
                        var sx = 34f
                        d?.second?.let { arrow(c, sx + 9f, y + 4f, it, Theme.textDim, outline = true); sx += 26f }
                        c.drawText(words, sx, y + 11f, pMeta)
                        y += 26f
                    }
                }
            }
            pObj.color = Theme.text
        }
        if (qdbShown) {
            c.drawText("From QuestieDB", 0f, y + 16f, pMeta)
            y += 30f
        }

        // story
        q.optString("desc", "").takeIf { it.isNotEmpty() }?.let { desc ->
            y += 14f
            val edge = 28f
            val dl2 = layout(desc, pInk, (tw + 16f - edge * 2.4f).toInt())
            val box = RectF(-8f, y, tw + 8f, y + dl2.height + 44f + edge * 2.2f + 8f)
            val inner = UiKit.parchment(c, box, edge)
            c.drawText("Description", inner.left, inner.top + 26f, pInkHead)
            c.save(); c.translate(inner.left, inner.top + 44f); dl2.draw(c); c.restore()
            y = box.bottom + 12f
        }
        detailContentH = y + 30f
        c.restore()

        // MORE BELOW: the last visible line would be cut through its middle and the parchment would just stop, which reads as
        // broken rather than scrollable (UI Art, the --long shot). So the bottom FADE_H of the details fades into the ground
        // and the kit's small gold down arrow sits in it, while there is more to scroll to.
        if (detailContentH - detailScroll > viewH + 1f) {
            pFade.shader = LinearGradient(0f, textBottom - FADE_H, 0f, textBottom,
                Color.argb(0, Color.red(Theme.ink), Color.green(Theme.ink), Color.blue(Theme.ink)), Theme.ink, Shader.TileMode.CLAMP)
            c.drawRect(x0, textBottom - FADE_H, x1, textBottom, pFade)
            // the arrow's centre ~9 px above the area's bottom, on faded ground only (UI Art: it covered the last readable line)
            val cue = RectF(x0, y0, x1, textBottom - 9f - 5f - 18f * 0.35f)
            UiKit.moveArrowsUpDown(c, cue, up = false, down = true, size = 18f, width = 26f, around = true)
        }

        // ---- actions, pinned to the bottom of the pane
        if (above != null) {
            pFill.color = Theme.ink; c.drawRect(x0, textBottom, x1, actTop, pFill)
            // the question in the button type, the reason in red 20 px (UI Art's v5)
            val p = if (above.second) pBtn.also { it.color = Theme.text } else pRowSub.also { it.color = Color.rgb(255, 60, 40) }
            c.drawText(fit(p, above.first, x1 - x0 - pad * 2), x0 + pad, actTop - 10f, p)
        }
        pFill.color = Theme.panel; c.drawRect(x0, actTop, x1, y1, pFill)
        pFill.color = Theme.bronzeDim; c.drawRect(x0, actTop, x1, actTop + 1f, pFill)
        val bTop = actTop + 12f; val bBot = y1 - 12f
        val block = blocked(now)
        val id = q.optInt("id")

        if (confirmAbandonId == id) {
            val half = ((x1 - x0) - pad * 2 - 10f) / 2f
            val cancel = RectF(x0 + pad, bTop, x0 + pad + half, bBot)
            val go = RectF(cancel.right + 10f, bTop, x1 - pad, bBot)
            val glyphs = held && cursor.shown
            button(c, cancel, "Cancel", "cancel", enabled = true, glyph = if (glyphs) UiKit.backGlyph() else null)
            button(c, go, "Abandon", "abandon!", enabled = block == null, glyph = if (glyphs) "rev_a" else null)
            return
        }

        // THE TWO BUTTONS (RC4 2026-10-09, the user: "Y more with abandon inside, Track/ untrack set to A"): A Track / Untrack, Y More
        // (Focus / Remove Focus, Share Quest, Abandon Quest); X does nothing. Their glyphs while the room holds the controller --
        // none while the More menu is open (the menu shows its own A Choose / B Back)
        val gap = 10f
        val bw = ((x1 - x0) - pad * 2 - gap) / 2f
        var bx = x0 + pad
        val padDoc = doc?.optJSONObject("pad")
        val slot = slotOf(q)
        // no pad column = no way to reach this quest in the game (an old addon, or a full grid)
        val reachable = QuestPad.cells(padDoc, QuestPad.Act.WATCH, slot) != null
        val tracked = tracked(q)
        val menuOpen = menuSel >= 0
        val glyphs = held && cursor.shown && !menuOpen
        button(c, RectF(bx, bTop, bx + bw, bBot), if (tracked) "Untrack" else "Track", "track",
            enabled = block == null && reachable, glyph = if (glyphs) "rev_a" else null)
        bx += bw + gap
        val moreBox = RectF(bx, bTop, bx + bw, bBot)
        button(c, moreBox, "More", "more", enabled = reachable, glyph = if (glyphs) "rev_y" else null, down = menuOpen)
        // the menu opens over the More button's side, right-aligned with it
        if (menuOpen) drawMore(c, q, moreBox.right, actTop, x1 - x0 - pad * 2, now)
    }

    /** every action is the game's red button (user ruling 2026-09-27), with the controller's [glyph] while the room holds it */
    private fun button(c: Canvas, r: RectF, label: String, act: String, enabled: Boolean, glyph: String? = null,
                       down: Boolean = false) {
        val isPressed = enabled && (pressed == act || padNow.isDown(act))
        val state = when { !enabled -> UiKit.Button.DISABLED; down || isPressed -> UiKit.Button.DOWN; else -> UiKit.Button.UP }
        // DOWN pushes it in: the kit's own press since art-kit efa4a089 (every room dropped its PressLook calls in that batch)
        if (glyph != null) UiKit.button(c, r, label, glyph, state) else UiKit.button(c, r, label, state)
        if (glyph != null && enabled) glyphHits[glyph] = act
        // disabled buttons still take the tap, so the header can say why nothing happened
        btnHits.add(r to act)
    }

    /** 22-unit stroke glyphs, same drawing language as the strip */
    private fun drawIcon(c: Canvas, which: Int, cx: Float, cy: Float, colour: Int) {
        val s = 0.9f
        fun X(v: Float) = cx + (v - 12f) * s
        fun Y(v: Float) = cy + (v - 12f) * s
        pStroke.color = colour; pStroke.strokeWidth = 2.2f
        when (which) {
            ICON_MAP -> {
                c.drawCircle(cx, cy, 10f * s, pStroke)
                c.drawPath(Path().apply {
                    moveTo(X(15.4f), Y(8.6f)); lineTo(X(13.3f), Y(13.4f))
                    lineTo(X(8.5f), Y(15.5f)); lineTo(X(10.6f), Y(10.7f)); close()
                }, pStroke)
            }
            ICON_TRACK, ICON_UNTRACK -> {
                c.drawPath(Path().apply {
                    moveTo(X(12f), Y(2f)); lineTo(X(22f), Y(12f)); lineTo(X(12f), Y(22f)); lineTo(X(2f), Y(12f)); close()
                }, pStroke)
                if (which == ICON_TRACK) {
                    pFill.color = colour; c.drawCircle(cx, cy, 3.2f, pFill)
                } else {
                    c.drawLine(X(4f), Y(20f), X(20f), Y(4f), pStroke)
                }
            }
            ICON_ABANDON -> {
                c.drawLine(X(5f), Y(5f), X(19f), Y(19f), pStroke)
                c.drawLine(X(19f), Y(5f), X(5f), Y(19f), pStroke)
            }
        }
        pStroke.strokeWidth = 1.6f
    }

    companion object {
        const val PRESS_MIN_MS = 160L   // a press stays visibly DOWN at least this long (Interact's)
        const val SHEET_RETRY_MS = 5_000L   // a missing POI sheet (the harvest still running) is looked for again this often
        const val LEAVE_GAP_MS = 120L   // the close, then the free (Map's MapRoomPad.GAP_MS): never two taps in the same instant
        const val CLOSE_RETRY_MS = 1_200L   // q37: the close again while the room is still kept ("close first")
        const val CLOSE_TRIES = 3
        const val HEADER = 64f
        const val ROW_H = 108f          // ~47 dp at density 369: the 48 dp touch floor, near enough
        const val ZONE_H = 44f
        const val ABOVE_BAR_H = 42f     // the strip above the button bar for its one line (Abandon's question)
        const val FADE_H = 40f          // the details fade into the ground over this, while there is more below (a line + the arrow)
        const val SEL_GAP = 28f         // the selected row's gap above and below, for the selector's arrows (UI Art's sheet)
        const val ACTIONS_H = 112f

        const val PENDING_TIMEOUT_MS = 8_000L
        const val CONFIRM_TIMEOUT_MS = 6_000L
        const val NOTE_MS = 4_000L
        const val REFRESH_MS = 250L

        const val ICON_MAP = 0; const val ICON_TRACK = 1; const val ICON_UNTRACK = 2; const val ICON_ABANDON = 3

        // The game's quest difficulty colours (GetQuestDifficultyColor), so a title reads the same
        // here as in the tracker it replaces.
        val RED = Color.parseColor("#FF1A1A")
        val ORANGE = Color.parseColor("#FF8040")
        val YELLOW = Color.parseColor("#FFFF00")
        val GREEN = Color.parseColor("#40C040")
        val GREY = Color.parseColor("#808080")
        val RED_DIM = Color.parseColor("#7A2A22")
        val RED_TEXT = Color.parseColor("#FF7A66")
    }
}
