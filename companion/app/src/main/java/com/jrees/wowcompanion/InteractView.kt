package com.jrees.wowcompanion

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.*

/**
 * The INTERACTION OVERLAY: whatever NPC window the game has open -- a gossip list, a quest being
 * offered or turned in, a vendor -- takes over the whole bottom screen until it is closed or
 * answered. Fed by the WowCompanionInteract addon (`{"type":"interact"}`), one document that
 * carries up to three sections; the most specific one wins (quest > merchant > gossip), and when
 * none is present the overlay goes away and the room underneath is exactly as it was left.
 *
 * It is an INTERRUPT LAYER, not a room (the user's model, carried over from the Guild Wars
 * companion): it is not on the strip, it never changes the selected room, and it covers the strip
 * while it is up.
 *
 * THE LOOK is the game's own (user, 2026-09-28: the first build "looks like an AI interface
 * instead of a Vanilla wow interface"). The art session designed it and the kit draws it: the
 * game's window frame with a portrait ring, the NPC's name in the title bar and its red close
 * button; ONE parchment page for what is read, options as lines on that page; the vendor in the
 * same frame as a scrolling list with tabs on top and rows built like the game's (slot, name
 * plate, price under the name); a stone strip of red buttons along the bottom. The design is in
 * wow-companion-design/popups/SPEC.md. This file keeps the BEHAVIOUR the user approved and the
 * drawing is its last section, so the two change apart.
 *
 * GRAMMAR (the companion UI rules): nothing has a button on its row. A gossip option IS the action,
 * so a tap sends it. Everywhere else a tap SELECTS (a reward, a vendor item) and the one action bar
 * at the bottom acts on the selection.
 *
 * ACTIONS ARE ONE TAP EACH through TapBridge. The addon publishes the pad and the column of every
 * action it offers; the command ("accept 60", "buy 3 2512") is what its reply echoes. The addon
 * re-checks every command against the window that is open when the tap lands.
 *
 * HOW MANY TO BUY is chosen here, with the stepper, and costs nothing until Buy is pressed. A tap
 * carries no number, so a Buy of more than one sends TWO taps from that one touch: the count's
 * cell (row `iqty`), then the item's. The first changes nothing in the game; the second is the
 * one purchase. The limits are the game's own (steps of the vendor's stack, up to a full stack,
 * what the purse covers, what the vendor has left), and the reply says how many were bought.
 *
 * THE CONTROLLER: the highlight is the ADDON's. It takes the D-pad and the face buttons while a
 * window is open, moves its own cursor, acts on A by itself, and sends `nav` with every document:
 * what is highlighted (`cur`), the vendor's tab, picked item and quantity, the picked reward.
 * This view only DRAWS that, so what A does is always what the addon has highlighted; touch keeps
 * working beside it. `nav.n` counts the controller's presses: the view takes the addon's tab /
 * pick / quantity when it changes, and leaves what a finger chose alone in between.
 *
 * FRESHNESS comes from the game's own clock: LogTail stamps every message with the time the game
 * wrote it (`_gt`). LogTail replays megabytes of backlog on start, so a document that merely
 * ARRIVED says nothing about now; one written more than STALE_MS ago is not shown at all. The
 * addon re-sends every 15 s, so an open window never looks stale while the game runs.
 */
class InteractView(context: Context) : View(context) {

    private enum class Kind { NONE, GOSSIP, QUEST, MERCHANT, TRAINER, STABLE, MAIL, AUCTION, TAXI, BANK, LOOT, PETITION, QTSETUP }
    private enum class Tab { BUY, SELL, BACK }

    /** one tappable or selectable thing: the command is its identity and what a tap sends */
    private class Entry(val cmd: String, val slot: Int, val o: JSONObject, val kind: Int)

    // ---------------------------------------------------------------- state
    private var doc: JSONObject? = null
    private var docGt = -1L
    private var kind = Kind.NONE
    private var sig = ""
    private var dismissedSig: String? = null

    private var tab = Tab.BUY
    private var selected: String? = null        // a command: the selected vendor row or reward
    private var qty = 1                         // how many purchases of the selected vendor item
    private var cur: String? = null             // what the controller has highlighted, or null
    private var navN = -1                       // the last controller press this view has taken in
    private var shownCur: String? = null        // the highlight the lists were last scrolled to
    private var askedQty = 1                    // what the pending Buy asked for
    private var scrollA = 0f                    // the list, or the quest's prose
    private var scrollB = 0f                    // the quest's rewards pane
    private var contentA = 0f
    private var contentB = 0f
    private var viewA = RectF()
    private var viewB = RectF()

    private var pendingCmd: String? = null
    private var pendingAt = 0L
    private var noteText: String? = null
    private var noteOk = true
    private var noteAt = 0L

    private var qtyMost = 1                     // the most purchases the stepper allows right now
    private val hits = ArrayList<Pair<RectF, String>>()      // "x", "tab:..", "do:<cmd>", "sel:<cmd>", "go"
    private var goCmd: Entry? = null
    private var goWhy: String? = null

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val pArt = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    /** a quest in progress when the kit has no grey ? of the game's: its gold ? with the colour taken out */
    private val pQuestGrey = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }

    /**
     * The preview harness sets this: every action is shown and named, and NOTHING is sent to the
     * game. The companion never sets it.
     */
    var dryRun = false

    init { visibility = GONE }

    // ---------------------------------------------------------------- input from MainActivity

    /** {"type":"interact", gossip?, quest?, merchant?, act?, pad?, _gt} */
    /** false while the document is one replayed from the backlog (MainActivity's `current`): it may draw, never tap */
    private var docCurrent = true

    private var lastDocAt = 0L                  // when the last document arrived (app clock)

    fun update(msg: JSONObject, current: Boolean = true) {
        lastDocAt = System.currentTimeMillis()
        if (current) spokenSync(msg)
        if (current) takeLootGone(msg, doc?.optJSONObject("loot"))
        prevLoot = doc?.optJSONObject("loot")
        docCurrent = current
        doc = msg
        docGt = msg.optLong("_gt", -1L)
        val k = when {
            // the controller is LENT (Y at the trainer: the Spellbook room shows; addon i38 `lend`)
            msg.has("lend") -> Kind.NONE
            msg.has("quest") -> Kind.QUEST
            msg.has("merchant") -> Kind.MERCHANT
            msg.has("trainer") -> Kind.TRAINER
            msg.has("stable") -> Kind.STABLE
            msg.has("petition") -> Kind.PETITION
            msg.has("mail") -> Kind.MAIL
            msg.has("auction") -> Kind.AUCTION
            msg.has("taxi") -> Kind.TAXI
            msg.has("bank") -> Kind.BANK
            msg.has("loot") -> Kind.LOOT
            msg.has("gossip") -> Kind.GOSSIP
            msg.has("qtsetup") -> Kind.QTSETUP           // 1.0.9: QuestTrail's setup assistant / Shared Trail intro, mirrored
            else -> Kind.NONE
        }
        val s = signature(msg, k)
        if (s != sig) {
            // a different window: start at the top, with nothing selected
            scrollA = 0f; scrollB = 0f; selected = null; qty = 1
            if (k != kind || k != Kind.MERCHANT) tab = Tab.BUY
            if (dismissedSig != null && dismissedSig != s) dismissedSig = null
        }
        // LOOTWIN 7: the compact window GROWS into Make Space; Make Space's layout is kept for the landing; a new landing starts
        val qsp = msg.optJSONObject("quest")?.optJSONObject("space")
        if (qsp != null) {
            // a second refusal right after the retry keeps Make Space up: the lead-in plays only for a fresh one
            val nowMs = System.currentTimeMillis()
            if (qSpaceAt == 0L || (qSpaceGoneAt > 0L && nowMs - qSpaceGoneAt > 2000L)) qSpaceAt = nowMs
            qSpaceGoneAt = 0L
            lastSpace = questLt(msg.optJSONObject("quest")!!, qsp).also { warmIcons(it, msg.optJSONObject("landing")) }
        } else if (qSpaceAt > 0L && qSpaceGoneAt == 0L) qSpaceGoneAt = System.currentTimeMillis()
        val lt0 = msg.optJSONObject("loot")
        if (lt0?.has("space") == true) {
            if (lootCompact) { growAt = System.currentTimeMillis(); growFrom = RectF(lootRect) }
            lastSpace = lt0
        }
        msg.optJSONObject("landing")?.let { ld ->
            val n = ld.optInt("n", 0)
            if (n != landN) {
                landN = n
                val frozen = lastSpace
                if (frozen != null && docCurrent) {
                    landing = ld; landAt = System.currentTimeMillis(); landLt = frozen
                    landHideFrom = ld.optInt("from", -1)
                }
            }
        }
        // the landed card never pops back: hidden until a doc no longer lists it (the game's own clear), at most 4 s
        if (landHideFrom >= 0) {
            val still = lt0 != null && arr(lt0, "items").any { it.optInt("i") == landHideFrom }
            if (!still || System.currentTimeMillis() - landAt > 4000L) landHideFrom = -1
        }
        if (lt0 != null) warmIcons(lt0, msg.optJSONObject("landing"))
        // the compact loot window's POP: when it opens (not when Make Space goes back to it), and its close kept on screen
        val compactNow = k == Kind.LOOT && msg.optJSONObject("loot")?.has("space") != true
        if (compactNow && !lootCompact) lootOpenAt = System.currentTimeMillis()
        if (!compactNow && lootCompact && k != Kind.LOOT) { lootCloseAt = System.currentTimeMillis(); lootLast = prevLoot }
        lootCompact = compactNow
        if (k != kind) wait.clear()             // another window (or none): every press-wait ends
        kind = k; sig = s
        if (k == Kind.NONE) { pendingCmd = null; dismissedSig = null }
        takeNav(msg.optJSONObject("nav"), k)
        takeProof(msg)
        takeKbd(msg)
        if (selected != null && entries().none { it.cmd == selected }) selected = null
        refresh()
    }

    private var navSel: String? = null          // the add-on's last vendor selection, as last taken

    /** the addon's controller state: draw its highlight; take its picks when a press changed them */
    private fun takeNav(nav: JSONObject?, k: Kind) {
        val was = cur
        cur = if (nav != null && nav.optBoolean("on") && nav.has("cur")) nav.optString("cur") else null
        if (nav == null || k == Kind.NONE) { navN = -1; return }
        // THE VENDOR'S SELECTION follows the add-on's on EVERY document (the user, b139: "when I sell an Item it still shows the
        // grey sell button after on the new item that takes its spot"): a sale's result comes in a document AFTER the press's
        // echo, with the same nav.n, the sold item gone and sel already on the item now under the cursor. Taken unless a finger
        // chose something else meanwhile (then that stays)
        if (k == Kind.MERCHANT) {
            val ns = if (nav.has("sel") && !nav.isNull("sel")) nav.optString("sel") else null
            if (ns != navSel) {
                if (selected == null || selected == navSel || entries().none { it.cmd == selected }) selected = ns
                navSel = ns
            }
        }
        val n = nav.optInt("n", 0)
        if (n == navN) return
        val first = navN < 0
        navN = n
        // the user, b120: "after selling, it goes to the next Item and shows the sell button grey": the add-on has handled the
        // press by the time its document shows it (its answer comes first), so a controller press's wait ends here at the latest,
        // and a moved cursor (the sold item's place taken by the next) ends it too
        if (!first) wait.answered(null)
        if (was != cur) wait.answered(null)
        val lk = if (nav.has("last") && !nav.isNull("last")) nav.optString("last") else null
        // the document's echo of a press the reader did not see: the pressed look only, never a wait (its answer is already in)
        if (!first && docCurrent && lk != null && lk in PAD_FACES &&
            System.currentTimeMillis() - (padEdgeAt[lk] ?: 0L) > PAD_ECHO_MS) padFlash(lk, startWait = false)
        fun str(key: String) = if (nav.has(key) && !nav.isNull(key)) nav.optString(key) else null
        tipTs = nav.optInt("ts", 0)             // the right stick's steps over a tall tooltip (addon i31)
        tipCompare = nav.optBoolean("xh")       // X held on a comparable item (addon i34)
        when (k) {
            Kind.MERCHANT -> {
                tab = when (str("tab")) { "sell" -> Tab.SELL; "back" -> Tab.BACK; else -> Tab.BUY }
                selected = str("sel"); navSel = selected
                qty = nav.optInt("qty", 1)
            }
            Kind.STABLE -> {
                stInHand = if (nav.has("from")) nav.optInt("from") else -1
                stBox = nav.optBoolean("box")
            }
            Kind.TRAINER -> {
                val f = HashSet<Int>()
                nav.optJSONArray("folds")?.let { a -> for (i in 0 until a.length()) f.add(a.optInt(i)) }
                trFolds = f
                trMenu = nav.optBoolean("menu")
            }
            Kind.QUEST -> {
                if (doc?.optJSONObject("quest")?.optString("stage") == "complete") selected = str("pick")
                scrollA = nav.optInt("scroll", 0) * SCROLL_STEP
            }
            else -> {}
        }
        // the controller took over: a window the finger dismissed comes back
        if (cur != null && dismissedSig == sig) dismissedSig = null
    }

    // ---- THE KEYBOARD FOR A FIELD (1.0.8: Send Mail's To / Subject / letter / money, the auction house's search box and level range).
    // The add-on opens the field (doc.kbd: its name, its value then) on a press or a tap of ours; the Chat keyboard ([keyboard], the
    // host's, along the bottom) types it, and every letter goes at once into the field Chat.lua lent the add-on (chat22), with Chat's
    // own rows and hash check ([fieldStream]). Done (the keyboard's Send / Start) hands the text over and the add-on takes the field
    // down; B or its close taps the doc's "kbd off". Meanwhile the add-on stands still and the presses come from the app's own reader
    // ([onPadKey], the host's unclaimed route), so the keyboard is driven exactly as Chat's and Bags' are.

    /** the host's keyboard (a sibling over this view, along the bottom); null = none hosted */
    var keyboard: ChatKeyboard? = null
    /** the pad when a document brings none (the quests documents': the same shared grid) */
    var padSource: () -> JSONObject? = { null }
    private var kbField: String? = null
    private var kbText = ""
    private var kbOff = -1
    /** the field's own limit (the add-on's: To 48, Subject 64, a money box 2 or 7, ...): more is never typed */
    private var kbMax = ChatStream.MAX
    private val fieldStream = ChatStream({ cells, gap ->
        TapBridge.tapsSpaced(context, doc?.optJSONObject("pad") ?: padSource(), cells, gap) { why -> post { note(why, false) } }
    }, field = true).apply {
        onEvent = { e -> post { if (e is ChatStream.Event.Refused) note(e.why, false); invalidate() } }
    }
    private val fieldTick = object : Runnable {
        override fun run() { fieldStream.tick(); if (fieldStream.sending) postDelayed(this, 500L) }
    }

    /** the field being typed shows this instead of its value in the document */
    private fun typedOr(field: String, value: String): String = if (kbField == field) kbText else value

    private fun takeKbd(msg: JSONObject) {
        val k = msg.optJSONObject("kbd")
        if (k == null) { if (docCurrent && kbField != null) hideKeyboard(); return }
        kbOff = k.optInt("off", -1)
        val name = k.optString("name")
        if (!docCurrent || name.isEmpty() || name == kbField) return
        val kb = keyboard ?: return
        kbField = name
        kbMax = k.optInt("max", ChatStream.MAX).coerceIn(1, ChatStream.MAX)
        kbText = fieldStream.clean(k.optString("text", ""), kbMax)
        fieldStream.startField(kbText)
        kb.sendWord = if (name == "ahtext") "Search" else "Done"
        kb.text = { kbText }
        kb.onText = { t -> kbText = fieldStream.clean(t, kbMax); fieldStream.setBody(kbText); invalidate() }
        kb.onSend = { if (fieldStream.send()) { removeCallbacks(fieldTick); postDelayed(fieldTick, 500L) } }
        kb.onClose = { closeField() }
        kb.open(); kb.visibility = VISIBLE
    }

    /** B / the keyboard's close: the add-on drops the field (the value stays as it was), the keyboard goes */
    private fun closeField() {
        if (kbOff >= 0 && !dryRun) TapBridge.tap(context, doc?.optJSONObject("pad") ?: padSource(), "iact", kbOff) { }
        hideKeyboard()
    }

    private fun hideKeyboard() {
        kbField = null; kbRepeat = null
        removeCallbacks(kbTick); removeCallbacks(fieldTick)
        keyboard?.visibility = GONE
        invalidate()
    }

    /** the keyboard is up: Start is its Done (the host asks before the pause wheel takes Start) */
    fun ownsStart(): Boolean = kbField != null && visibility == VISIBLE

    private var kbRepeat: String? = null
    private val kbTick = object : Runnable {
        override fun run() { val k = kbRepeat ?: return; keyboard?.press(k); postDelayed(this, KB_REPEAT_MS) }
    }

    /** a controller key from the host's own reader (only while the keyboard is up; the add-on stands still meanwhile) */
    fun onPadKey(k: String, down: Boolean) {
        if (kbField == null || visibility != VISIBLE) return
        val dpad = k == "PADDUP" || k == "PADDDOWN" || k == "PADDLEFT" || k == "PADDRIGHT"
        if (!down) { if (kbRepeat == k) { kbRepeat = null; removeCallbacks(kbTick) }; return }
        keyboard?.press(k)
        if (dpad && kbField != null) { kbRepeat = k; removeCallbacks(kbTick); postDelayed(kbTick, KB_REPEAT_FIRST_MS) }
    }

    /** Chat.lua's answers ({"type":"chatt"}): the field's check and its hand-over */
    fun onChatAnswer(msg: JSONObject) {
        if (kbField == null || msg.optString("type") != "chatt") return
        fieldStream.heard(ChatStream.Heard("chatt", msg.optString("op"), msg.optBoolean("ok"),
            if (msg.has("why")) msg.optString("why") else null, msg.optString("ty", ""),
            msg.optInt("len", -1), msg.optInt("h", -1), msg.optInt("tlen", -1), msg.optInt("th", -1), msg.optString("into")))
    }

    // ---- the remembered proof (user, 2026-09-28: "Remember the proof")
    // The addon cannot keep anything between loads on this client, so the app does: "on this game
    // build, with this capture code, the addon's frame heard the controller". After every load the
    // app says so with ONE tap on the addon's `trust` cell, and the game's own windows are hidden
    // from the first visit on. The addon's watchdog takes it back (`bad`), and then it is forgotten.

    private val prefs by lazy { context.getSharedPreferences("interact", Context.MODE_PRIVATE) }
    private var trustBoot = -1L
    private var trustTries = 0
    private var trustAt = 0L

    /** {"proof":{"gb":..,"cv":..,"boot":..,"col":..,"seen"?,"trusted"?,"bad"?}} */
    private fun takeProof(msg: JSONObject) {
        val p = msg.optJSONObject("proof") ?: return
        val gb = p.optString("gb", "")
        if (gb.isEmpty() || dryRun) return
        val key = gb + "|" + p.optInt("cv", 0)
        val stored = prefs.getString("proof", null)
        if (p.has("bad")) { if (stored != null) prefs.edit().remove("proof").apply(); return }
        if (p.optBoolean("seen")) { if (stored != key) prefs.edit().putString("proof", key).apply(); return }
        if (p.optBoolean("trusted") || stored != key) return
        // only into a game that is running NOW: a document replayed from the backlog taps nothing
        if (!docCurrent || !msg.optBoolean("mode") || ageMs() > LIVE_MS) return
        val pad = msg.optJSONObject("pad") ?: return
        val col = p.optInt("col", -1)
        if (col < 0) return
        val boot = p.optLong("boot", 0L)
        val now = System.currentTimeMillis()
        if (boot != trustBoot) { trustBoot = boot; trustTries = 0 }
        if (trustTries >= TRUST_TRIES || now - trustAt < TRUST_GAP_MS) return
        if (pendingCmd != null || now - lastTapAt < LOCKOUT_MS) return
        trustTries++; trustAt = now
        TapBridge.tap(context, pad, "iact", col) { }
    }

    /**
     * THE TOOLTIP FOR A FINGER'S SELECTION (LATER 1 + 2, user 2026-09-30: "Always, for the highlighted item").
     * The controller's highlight needs nothing: the addon sends the tooltip of what it highlights. A finger's
     * selection is the app's, so the app taps that item's `look` cell on the ipick row once: the addon then
     * sends its tooltip. A look changes nothing in the game (the lock allows a no-action pick tap).
     */
    private var lookedAt: String? = null
    private fun look(cmd: String) {
        if (dryRun || cmd == lookedAt) return
        val d = doc ?: return
        if (!docCurrent || ageMs() > LIVE_MS) return
        val col = lookCol(d, cmd)
        if (col < 0) return
        lookedAt = cmd
        TapBridge.tap(context, d.optJSONObject("pad"), "ipick", col) { }
    }

    /**
     * The addon's tooltip for [cmd], or null. The controller's highlight and the vendor's rows use the same key
     * as the command ("buy 1 159"); a finger's quest reward ("reward <qid> <i>") comes back as "qitem choice <i>".
     */
    private fun tipFor(cmd: String?): JSONObject? {
        val t = doc?.optJSONObject("tip") ?: return null
        if (cmd == null || t.optBoolean("none")) return null
        val k = t.optString("key")
        if (k == cmd) return t
        val m = Regex("""^reward \d+ (\d+)$""").find(cmd) ?: return null
        return if (k == "qitem choice " + m.groupValues[1]) t else null
    }

    /** the reward object behind a quest key ("reward <qid> <i>", "qitem choice|reward <i>"), for its name and quality */
    private fun rewardOf(q: JSONObject, cmd: String, tipKey: String): JSONObject? {
        val rew = q.optJSONObject("rewards") ?: return null
        val (kind, i) = Regex("""^qitem (choice|reward) (\d+)$""").find(tipKey)?.let { it.groupValues[1] to it.groupValues[2].toInt() }
            ?: (Regex("""^reward \d+ (\d+)$""").find(cmd)?.let { "choice" to it.groupValues[1].toInt() } ?: return null)
        val a = rew.optJSONArray(if (kind == "choice") "choices" else "items") ?: return null
        for (n in 0 until a.length()) { val o = a.optJSONObject(n) ?: continue; if (o.optInt("i", -1) == i) return o }
        return null
    }

    private fun str(o: JSONObject, key: String): String? = if (o.has(key) && !o.isNull(key)) o.optString(key) else null

    private fun colourOf(hex: String?, def: Int): Int = TipStack.colour(hex, def)

    // the right stick's steps and X held, from the addon's nav (the shared TipStack draws with them)
    private var tipTs = 0                       // nav.ts: the right stick's steps
    private var tipCompare = false              // nav.xh: X held, "Currently Equipped" shown

    /**
     * THE TOOLTIP (UI Art's kit, 2026-09-30): it waits 250 ms after the highlight lands and fades in (user: "give them like a
     * quarter second of load time ... and fade them in nicely"); it starts level with the highlighted row ([yRow]), lifted only as
     * far as needed so that it (and its hint, or the comparison) fits above [bottom] ("as close to the cursor as possible").
     * A comparable item shows UI Art's "Compare" hint under it; while X is HELD the worn item(s) ("Currently Equipped") stand
     * SIDE BY SIDE at [cmpX], on the cursor's half (user: "showing the currently equipped on the half of the screen that my
     * cursor is on"; the picture SHEET_tooltip_side.png, "Yes, build it"), same width, same top.
     */
    private fun drawTip(c: Canvas, x: Float, yRow: Float, w: Float, name: String, q: Int, t: JSONObject,
                        top: Float, bottom: Float, cmpX: Float) =
        tipStack.draw(c, x, yRow, w, name, q, t, top, bottom, cmpX, tipCompare, tipTs)

    private val tipStack by lazy { TipStack(this) }

    /** the `look` column of the item behind [cmd]: vendor lists, or a quest reward ("reward <qid> <i>") */
    private fun lookCol(d: JSONObject, cmd: String): Int {
        entries().firstOrNull { it.cmd == cmd }?.let { e -> e.o.optInt("look", -1).let { if (it >= 0) return it } }
        val m = Regex("""^reward \d+ (\d+)$""").find(cmd) ?: return -1
        val i = m.groupValues[1].toInt()
        val ch = d.optJSONObject("quest")?.optJSONObject("rewards")?.optJSONArray("choices") ?: return -1
        for (n in 0 until ch.length()) {
            val o = ch.optJSONObject(n) ?: continue
            if (o.optInt("i", -1) == i) return o.optInt("look", -1)
        }
        return -1
    }

    /**
     * SELF-HEAL (Home's rule for every room, 2026-09-30): Home calls this when the documents say holder "npc" but no NPC
     * window has been open for a while. ONE no-action tap on the addon's `heal` cell ("release npc"), which lets go only
     * when no game window is open and no room holds. Returns false when there is nothing to tap.
     */
    /**
     * HOME'S DISC TAPPED while one of these windows shows (Home, 2026-10-01: the user's "B ... back to home and closing the
     * loot table", if a touch on the disc should do the same): the window closes as its own close does. False = none open.
     */
    fun closeByTouch(): Boolean {
        // the user's ruling, widened (2026-10-01): "Yes, everywhere it shows B". Any of these windows, but ONLY while its B would
        // go Home (the addon's bnext "home"); a sub-page, a box or a game dialog on top says "back" and a touch closes nothing
        if (kind == Kind.NONE || visibility != VISIBLE) return false
        if (doc?.optString("bnext") != "home" || !docCurrent || ageMs() > LIVE_MS) return false
        close()
        return true
    }

    /** the loot window is on screen (Home: the disc's touch closes it, the user's ruling) */
    fun lootShowing(): Boolean = kind == Kind.LOOT && visibility == VISIBLE

    fun freeAgain(): Boolean {
        val d = doc ?: return false
        if (!docCurrent || ageMs() > LIVE_MS) return false
        val col = if (d.has("heal") && !d.isNull("heal")) d.optInt("heal", -1) else -1
        if (col < 0 || dryRun) return false
        return TapBridge.tap(context, d.optJSONObject("pad"), "iact", col) { }
    }

    /** {"type":"interactack","cmd":..,"ok":..,"why":..} */
    fun ack(msg: JSONObject) {
        val cmd = msg.optString("cmd")
        // test50 (QuestTrail's setup: "Selecting world trail is not doing anything"): every press answered shows: the button flashes
        // gold and a line says it ("World Trail chosen"), even when it was already the choice
        if (msg.optBoolean("ok") && Regex("^qt \\d+$").matches(cmd)) {
            val id = cmd.substringAfter("qt ")
            val it = arr(doc?.optJSONObject("qtsetup"), "items").firstOrNull { it.optString("id") == id }
            if (it != null && it.optString("k") == "button") { qtFlashId = id; qtFlashAt = fxClock(); note(it.optString("t") + " chosen", true); postInvalidateOnAnimation() }
        }
        // f-loot-feedback: a loot the game refused (full bags, locked, ...) shakes its card red, fresh answers only
        if (cmd.startsWith("loot ") && !msg.optBoolean("ok") && abs(gameNow() - msg.optLong("_gt", gameNow())) < 5_000L) {
            shakes[cmd] = fxClock(); postInvalidateOnAnimation()
        }
        if (msg.optBoolean("ok")) wait.answered(cmd) else wait.refused(cmd)
        if (pendingCmd == null || pendingCmd != cmd) {
            // not one the app sent: a CONTROLLER action (the addon ran it from a press). Its refusal is shown when it is FRESH
            // (the user, 2026-10-01: "the hold to delete is not doing anything": the addon had refused it, the screen said nothing);
            // a replayed ack from the backlog stays silent
            val gt = msg.optLong("_gt", -1L)
            if (!msg.optBoolean("ok") && gt >= 0 && abs(gameNow() - gt) < 5_000L)
                note(msg.optString("why", "Refused").replaceFirstChar { it.uppercase() }, false)
            return
        }
        pendingCmd = null
        if (!msg.optBoolean("ok")) { note("Refused: " + msg.optString("why", "no reason given"), false); return }
        if (cmd.startsWith("buy ") && msg.has("n")) {
            // the addon says how many it bought: a lost count tap shows up here, not in the purse
            val n = msg.optInt("n")
            if (askedQty > 1 && n < askedQty) note("Bought only $n of $askedQty", false)
            else note(if (n > 1) "Bought $n" else "Bought", true)
            return
        }
        note(doneWord(cmd), true)
    }

    /** true while the overlay owns the screen */
    fun showing() = visibility == VISIBLE

    private fun doneWord(cmd: String) = when (cmd.substringBefore(' ')) {
        "buy" -> "Bought"; "sell" -> "Sold"; "back" -> "Bought back"; "repair" -> "Repaired"
        "junk" -> "Junk sold"; "accept" -> "Accepted"; "decline" -> "Declined"
        "reward" -> "Completed"; else -> "Done"
    }

    private fun note(s: String, ok: Boolean) {
        noteText = s; noteOk = ok; noteAt = System.currentTimeMillis(); postInvalidate()
    }

    /** which window this is, so that X can dismiss THIS one and the next one still appears */
    private fun signature(d: JSONObject, k: Kind): String = when (k) {
        Kind.NONE -> ""
        Kind.QUEST -> d.optJSONObject("quest")!!.let {
            "q|${it.optString("stage")}|${it.optInt("id", 0)}|${it.optJSONObject("npc")?.optInt("id", 0)}"
        }
        Kind.MERCHANT -> "m|${d.optJSONObject("merchant")!!.optJSONObject("npc")?.optString("name")}"
        Kind.TRAINER -> "t|${d.optJSONObject("trainer")!!.optJSONObject("npc")?.optString("name")}"
        Kind.STABLE -> "s|${d.optJSONObject("stable")!!.optJSONObject("npc")?.optString("name")}"
        Kind.PETITION -> "pe|${d.optJSONObject("petition")!!.optString("title")}"
        Kind.QTSETUP -> "qt|${d.optJSONObject("qtsetup")!!.optString("kind")}|${d.optJSONObject("qtsetup")!!.optInt("i")}"
        Kind.MAIL -> d.optJSONObject("mail")!!.let { "ml|${it.optString("tab")}|${it.optJSONObject("send")?.has("bags") == true}" }
        Kind.TAXI -> "tx|${d.optJSONObject("taxi")!!.optInt("map")}"
        Kind.BANK -> d.optJSONObject("bank")!!.let { "bk|${it.optBoolean("acct")}|${it.optInt("page")}" }
        Kind.LOOT -> if (d.optJSONObject("loot")!!.has("space")) "lts" else "lt"
        Kind.AUCTION -> d.optJSONObject("auction")!!.let { a ->
            "ah|${a.optString("tab")}|${a.optJSONArray("path")?.join(".")}|${a.optJSONObject("item")?.optInt("id")}"
        }
        Kind.GOSSIP -> d.optJSONObject("gossip")!!.let { g ->
            val ids = StringBuilder()
            g.optJSONArray("options")?.let { a -> for (i in 0 until a.length()) ids.append(a.optJSONObject(i)?.optInt("id")).append(',') }
            "g|${g.optJSONObject("npc")?.optString("name")}|$ids"
        }
    }

    // ---------------------------------------------------------------- freshness

    /** the same clock LogTail stamps messages with: ms into the year, in the device's local time */
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

    /** decide whether the overlay is up, and keep checking while a window is open */
    private fun refresh() {
        removeCallbacks(ticker)
        if (kbField != null && (kind != Kind.MAIL && kind != Kind.AUCTION)) hideKeyboard()
        val now0 = System.currentTimeMillis()
        // the user, 2026-10-07: "I dont like when the context menu stays open after making a selection": an action that got no
        // document at all for PENDING_FOLD_MS (the top screen is loading) folds the window away; the next document brings it back
        val silent = pendingCmd != null && now0 - pendingAt > PENDING_FOLD_MS && lastDocAt < pendingAt
        val want = (kind != Kind.NONE && sig != dismissedSig && ageMs() < STALE_MS && !silent) || lootClosing() || landing != null
        val v = if (want) VISIBLE else GONE
        if (visibility != v) visibility = v
        padSubSync(want && doc?.optJSONObject("nav")?.optBoolean("on") == true)
        if (kind != Kind.NONE) postDelayed(ticker, 1000)
        if (lootClosing()) postDelayed(ticker, Motion.MODULE_CLOSE + 20)
        if (landing != null) postDelayed(ticker, LAND_ALL + 40 + landExtra(landing))
        invalidate()
    }

    /** why actions are unavailable right now, or null if they are available */
    private fun blocked(now: Long): String? = when {
        ageMs() > LIVE_MS -> "Waiting for the game"
        doc?.optJSONObject("pad") == null -> "The game has not published its tap pad"
        pendingCmd != null -> "Waiting for the game to answer"
        now - lastTapAt < LOCKOUT_MS -> "One moment"
        else -> null
    }

    // ---------------------------------------------------------------- the document as entries

    private fun arr(o: JSONObject?, key: String): List<JSONObject> {
        val a: JSONArray = o?.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun slotOf(o: JSONObject, key: String = "slot") =
        if (o.has(key) && !o.isNull(key)) o.optInt(key, -1) else -1

    /** everything in the current window that can be tapped or selected, in display order */
    private fun entries(): List<Entry> {
        val d = doc ?: return emptyList()
        val out = ArrayList<Entry>()
        when (kind) {
            Kind.GOSSIP -> {
                val g = d.optJSONObject("gossip")
                // b-npc-quest-order: ready to turn in, then new, then in progress (the add-on's cursor walks the same order)
                val (ready, going) = arr(g, "active").partition { it.optBoolean("done", false) }
                for (q in ready) out.add(Entry("turnin ${q.optInt("id")}", slotOf(q), q, K_ACTIVE))
                for (q in arr(g, "available")) out.add(Entry("take ${q.optInt("id")}", slotOf(q), q, K_AVAIL))
                for (q in going) out.add(Entry("turnin ${q.optInt("id")}", slotOf(q), q, K_ACTIVE))
                for (o in arr(g, "options")) out.add(Entry("opt ${o.optInt("id")}", slotOf(o), o, K_OPTION))
            }
            Kind.QUEST -> {
                val q = d.optJSONObject("quest")
                if (q?.optString("stage") == "greeting") {
                    val (ready, going) = arr(q, "active").partition { it.optBoolean("done", false) }
                    for (e in ready) out.add(Entry("pick active ${e.optInt("i")}", slotOf(e), e, K_ACTIVE))
                    for (e in arr(q, "available")) out.add(Entry("pick avail ${e.optInt("i")}", slotOf(e), e, K_AVAIL))
                    for (e in going) out.add(Entry("pick active ${e.optInt("i")}", slotOf(e), e, K_ACTIVE))
                } else if (q?.optString("stage") == "complete") {
                    for (c in arr(q.optJSONObject("rewards"), "choices"))
                        out.add(Entry("reward ${q.optInt("id")} ${c.optInt("i")}", slotOf(c), c, K_CHOICE))
                }
            }
            Kind.MERCHANT -> {
                val m = d.optJSONObject("merchant")
                when (tab) {
                    Tab.BUY -> for (i in arr(m, "items"))
                        out.add(Entry("buy ${i.optInt("i")} ${i.optInt("id")}", slotOf(i), i, K_BUY))
                    Tab.SELL -> for (i in arr(m, "sell"))
                        out.add(Entry("sell ${i.optInt("bag")} ${i.optInt("s")} ${i.optInt("id")}", slotOf(i), i, K_SELL))
                    Tab.BACK -> for (i in arr(m, "buyback"))
                        out.add(Entry("back ${i.optInt("i")} ${i.optInt("id")}", slotOf(i), i, K_BACK))
                }
            }
            Kind.STABLE -> {
                for (pt in arr(d.optJSONObject("stable"), "pets")) out.add(Entry("pet ${pt.optInt("i")}", slotOf(pt), pt, K_PET))
            }
            Kind.TRAINER -> {
                for (sv in arr(d.optJSONObject("trainer"), "services"))
                    if (sv.optString("cat") != "header") out.add(Entry("train ${sv.optInt("i")}", slotOf(sv), sv, K_TRAIN))
            }
            Kind.TAXI -> {
                for (n in arr(d.optJSONObject("taxi"), "nodes")) out.add(Entry("taxi ${n.optInt("i")}", slotOf(n), n, K_TAXI))
            }
            Kind.BANK -> {}
            Kind.LOOT -> for (it in arr(d.optJSONObject("loot"), "items")) out.add(Entry("loot ${it.optInt("i")}", slotOf(it), it, K_LOOT))
            Kind.AUCTION -> {
                val au = d.optJSONObject("auction")
                for (cat in arr(au, "cats")) out.add(Entry("ahcat ${cat.optString("i")}", slotOf(cat), cat, K_AH))
                for (r in arr(au, "results")) out.add(Entry("ahres ${r.optInt("i")}", slotOf(r), r, K_AH))
                au?.optJSONObject("item")?.let { it ->
                    if (!it.optBoolean("commodity")) for (r in arr(it, "rows")) out.add(Entry("ahrow ${r.optInt("i")}", slotOf(r), r, K_AH))
                }
                au?.optJSONObject("act")?.let { a -> out.add(Entry("ahbuy", slotOf(a, "buy"), a, K_AH)) }
            }
            Kind.MAIL -> {
                val ml = d.optJSONObject("mail")
                for (l in arr(ml, "mails")) out.add(Entry("mail ${l.optInt("i")}", slotOf(l), l, K_MAIL))
                ml?.optJSONObject("open")?.let { o ->
                    val i = o.optInt("i")
                    for (it in arr(o, "items")) out.add(Entry("mailtake $i ${it.optInt("a")}", slotOf(it), it, K_MAIL))
                    o.optJSONObject("act")?.let { a ->
                        out.add(Entry("mailmoney $i", slotOf(a, "money"), o, K_MAIL))
                        out.add(Entry("mailall $i", slotOf(a, "all"), o, K_MAIL))
                    }
                }
            }
            Kind.NONE, Kind.PETITION, Kind.QTSETUP -> {}
        }
        return out
    }

    // ---- SPOKEN'S STOP OPTIONS (1.0.9, Settings > Add-ons): the add-on says the values it has (doc.spoken, Interact i120); one that
    // differs from the player's setting is put right with its cell, at most every SPK_EVERY_MS (a fresh game starts on the defaults)
    private var spkTapAt = 0L
    private fun spokenSync(msg: JSONObject) {
        val sp = msg.optJSONObject("spoken") ?: return
        val cells = sp.optJSONObject("cells") ?: return
        ThirdPartyAddons.init(context)
        val a = ThirdPartyAddons.SPOKEN
        fun want(id: String) = a.options.firstOrNull { it.id == id }?.let { ThirdPartyAddons.option(a, it) } ?: false
        val (k, v) = when {
            sp.optBoolean("close") != want("stopClose") -> "close" to want("stopClose")
            sp.optBoolean("accept") != want("stopAccept") -> "accept" to want("stopAccept")
            else -> return
        }
        val col = cells.optInt(k + if (v) "On" else "Off", -1)
        val now = android.os.SystemClock.uptimeMillis()
        if (col < 0 || dryRun || now - spkTapAt < SPK_EVERY_MS) return
        spkTapAt = now
        TapBridge.tap(context, msg.optJSONObject("pad"), "iact", col) { }
    }

    private fun rowOf(cmd: String) = when (cmd.substringBefore(' ')) {
        "opt", "take", "turnin", "pick", "trhead", "ahcat" -> "ipick"
        "buy", "train", "pet", "mail", "ahres", "ahrow", "taxi", "bkdo", "loot" -> "ibuy"
        "sell", "mailtake", "mailmoney", "mailall", "bkpick", "bkput", "lootdelc", "lootdelr", "maildetach", "ahsitem" -> "isell"
        "lookbag", "ahfilter" -> "ipick"
        "mailattach", "ahcancel" -> "ibuy"
        else -> "iact"
    }

    private var lastTapAt = 0L

    /**
     * One tap on the pad: [cmd] is what the addon will run and what its reply will echo.
     * [count] > 1 (a vendor purchase only) sends the count's own cell first, from the same touch.
     */
    private fun send(cmd: String, slot: Int, count: Int = 1) {
        val now = System.currentTimeMillis()
        blocked(now)?.let { note(it, false); return }
        if (slot < 0) { note("The game gave this no tap cell", false); return }
        if (dryRun) {
            lastTapAt = now
            note(if (count > 1) "Preview: qty $count, then $cmd" else "Preview: $cmd", true)
            return
        }
        if (count > 1) {
            // same serial worker as the purchase, so the count always lands first
            val sent = TapBridge.tap(context, doc?.optJSONObject("pad"), "iqty", count - 1) { why ->
                post { pendingCmd = null; note(why, false) }
            }
            if (!sent) { note("The game cannot take a quantity yet: reload the game UI", false); return }
        }
        askedQty = count
        val ok = TapBridge.tap(context, doc?.optJSONObject("pad"), rowOf(cmd), slot) { why ->
            post { pendingCmd = null; note(why, false) }
        }
        if (!ok) { note("The game has not published its tap pad", false); return }
        pendingCmd = cmd; pendingAt = now; lastTapAt = now
        postDelayed(ticker, PENDING_FOLD_MS + 50L)
        postInvalidate()
    }

    // ---------------------------------------------------------------- touch

    private var downX = 0f; private var downY = 0f; private var lastY = 0f
    private var dragging = false; private var dragB = false
    private var pressed = ""                    // the hit a finger is on, for the pressed look

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && (lootCompact || lootClosing()) && !lootRect.contains(e.x, e.y)
            && hits.none { it.first.contains(e.x, e.y) }) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastY = e.y; dragging = false
                dragB = viewB.contains(e.x, e.y)
                msDrag = System.currentTimeMillis() - msPaneAt < 400L && msPane.contains(e.x, e.y)
                pressed = hits.lastOrNull { it.first.contains(e.x, e.y) }?.second ?: ""
                if (pressed == "lt:hold") {
                    ltTouchHoldAt = System.currentTimeMillis(); ltTouchFired = false
                    removeCallbacks(ltTouchFire); postDelayed(ltTouchFire, 1000L)
                }
                petDrag = if (kind == Kind.STABLE && pressed.startsWith("sel:pet ")) pressed.substringAfter("sel:pet ").toIntOrNull() ?: -1 else -1
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (petDrag > 0) {
                    if (abs(e.x - downX) + abs(e.y - downY) > 24f) { dragging = true; pressed = "" }
                    petDragX = e.x; petDragY = e.y
                    invalidate()
                    return true
                }
                if (!dragging && abs(e.y - downY) > 18f) { dragging = true; pressed = "" }
                if (dragging) {
                    if (msDrag) { msScrollTo += lastY - e.y; msScroll = msScrollTo }
                    else if (dragB) scrollB += lastY - e.y else scrollA += lastY - e.y
                    invalidate()
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                val was = pressed
                if (was == "lt:hold") { pressed = ""; lootHoldEnd(); invalidate(); return true }
                pressed = ""
                val from = petDrag; petDrag = -1
                if (from > 0 && dragging) {
                    val to = hits.lastOrNull { it.first.contains(e.x, e.y) }?.second
                        ?.takeIf { it.startsWith("sel:pet ") }?.substringAfter("sel:pet ")?.toIntOrNull() ?: -1
                    if (to > 0 && to != from) petMove(from, to)
                    invalidate()
                } else if (!dragging) {
                    if (!wait.accept(was)) { invalidate(); return true }      // still waiting for the game: a second tap is ignored
                    tap(e.x, e.y)
                    lingerPress(was)
                    val pc = pendingCmd
                    if (pc != null && System.currentTimeMillis() - pendingAt < 100L) wait.begin(was, pc)
                } else invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = ""; invalidate() }
        }
        return true       // the overlay owns every touch: nothing reaches the room underneath
    }

    private fun tap(x: Float, y: Float) {
        val hit = hits.lastOrNull { it.first.contains(x, y) }?.second ?: return
        when {
            hit == "x" -> close()
            hit.startsWith("pet:") -> {
                // the charter's own button, pressed by its secure cell on the dialogs' row (Dialogs d18)
                val cell = hit.substring(4).toIntOrNull() ?: return
                if (dryRun) { note("Preview: charter cell $cell", true); return }
                val ok = TapBridge.tap(context, doc?.optJSONObject("pad"), "idlg", cell) { why -> post { note(why, false) } }
                if (!ok) note("The game has not published its tap pad", false)
            }
            hit.startsWith("tab:") -> {
                val t = Tab.valueOf(hit.substring(4))
                if (t != tab) { tab = t; selected = null; scrollA = 0f; qty = 1 }
            }
            hit.startsWith("sel:ahrow ") || hit.startsWith("sel:taxi ") -> selected = hit.substring(4)
            hit.startsWith("bk:") -> bankTap(hit.substring(3))
            hit.startsWith("sel:loot ") -> { selected = hit.substring(4); look(selected!!) }
            hit.startsWith("lt:card ") -> {
                val cmd = hit.substring(8)
                if (selected == cmd || cur == cmd) entries().firstOrNull { it.cmd == cmd }?.let { send(it.cmd, it.slot) }
                else { selected = cmd; look(cmd) }
            }
            hit.startsWith("lt:bag ") -> lootPickBag(hit.substring(7))
            hit.startsWith("lt:yes ") -> hit.substring(7).split(' ').let { p -> send("lootdelr ${p[0]}", p[2].toInt(), p[1].toInt()) }
            hit.startsWith("sel:mail ") -> {
                val cmd = hit.substring(4)
                if (selected == cmd) entries().firstOrNull { it.cmd == cmd }?.let { send(it.cmd, it.slot) }
                selected = cmd
            }
            hit.startsWith("sel:") -> {
                val cmd = hit.substring(4)
                selected = if (selected == cmd && kind == Kind.MERCHANT) null else cmd
                qty = 1
                selected?.let { look(it) }
            }
            hit.startsWith("do:") -> {
                val cmd = hit.substring(3)
                val e = entries().firstOrNull { it.cmd == cmd }
                if (e != null) send(e.cmd, e.slot)
            }
            hit == "go" -> {
                val g = goCmd
                if (g != null) send(g.cmd, g.slot, if (g.kind == K_BUY) qty else 1)
                else goWhy?.let { note(it, false) }
            }
            hit == "mp:-" -> { mailPage = max(0, mailPage - 1); mailPageCur = cur }
            hit == "mp:+" -> { mailPage += 1; mailPageCur = cur }
            hit == "qty:-" -> qty = max(1, qty - 1)
            hit == "qty:+" -> qty = min(qtyMost, qty + 1)
            // the number itself jumps between one and the most that can be bought
            hit == "qty:max" -> qty = if (qty < qtyMost) qtyMost else 1
            hit.startsWith("act:") -> {
                // a fixed action of the window: accept, decline, continue, repair, junk
                val parts = hit.substring(4).split('|')
                send(parts[0], parts[1].toInt())
            }
            hit.startsWith("why:") -> note(hit.substring(4), false)
        }
        invalidate()
    }

    /**
     * X: the overlay goes away at once, and the game is asked to close its window too. When the
     * game cannot be reached the overlay still goes, and it stays away for THIS window only.
     */
    private fun close() {
        val d = doc
        val slot = d?.optJSONObject("act")?.let { slotOf(it, "close") } ?: -1
        if (!dryRun && slot >= 0 && ageMs() < LIVE_MS && d?.optJSONObject("pad") != null) {
            TapBridge.tap(context, d.optJSONObject("pad"), "iact", slot)
            lastTapAt = System.currentTimeMillis()
        }
        dismissedSig = sig
        pendingCmd = null
        refresh()
    }

    // ================================================================ DRAWING
    // Everything below only DRAWS the state above and fills `hits`. The design is the art
    // session's (wow-companion-design/popups/SPEC.md, user's pick 2026-09-28): the game's own
    // window for quests and gossip (frame, portrait ring, the NPC's name in the title bar, ONE
    // parchment page, options as lines on the page), and the vendor in that frame as a scrolling
    // list with the tabs on top and rows built like the game's. No state is decided down here.

    private fun fit(p: TextPaint, s: String, w: Float): String =
        TextUtils.ellipsize(s, p, max(w, 10f), TextUtils.TruncateAt.END).toString()

    private fun layout(s: String, p: TextPaint, w: Int, gap: Float = 5f): StaticLayout =
        StaticLayout.Builder.obtain(s, 0, s.length, p, max(w, 10))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(gap, 1f).build()

    /** the game writes "\r\n" and a player-name token into its prose */
    private fun prose(s: String) = s.replace("\r", "").replace("\$N", "adventurer").replace("\$n", "adventurer").trim()

    /** draws [l] at (x, y) and returns the y under it */
    private fun drawLayout(c: Canvas, l: StaticLayout, x: Float, y: Float): Float {
        c.save(); c.translate(x, y); l.draw(c); c.restore()
        return y + l.height
    }

    private fun itemIcon(o: JSONObject): Bitmap? =
        Icons.getOrUnknown(o.optInt("icon", 0).takeIf { it > 0 })

    /** keep the highlighted row of a list on screen: called once per highlight, not per frame */
    private fun follow(row: Int, rowH: Float, viewH: Float) {
        if (cur == shownCur) return
        shownCur = cur
        if (row < 0) return
        val top = row * rowH
        if (top < scrollA) scrollA = top
        else if (top + rowH > scrollA + viewH) scrollA = top + rowH - viewH
    }

    // ---- PRESS FEEDBACK (the user, 2026-10-07: "the buttons don't really look like they are always getting pressed"): a tap's
    // button stays DOWN at least PRESS_MIN_MS after the finger lifts, and the controller's A / B (the addon's nav.last, the press
    // that reached it) shows the cursor's button / the close DOWN the same way
    private fun lingerPress(hit: String) {
        if (hit.isEmpty() || hit == "lt:hold") return
        pressed = hit
        lingerUntil = System.currentTimeMillis() + PRESS_MIN_MS
        invalidate()
        postDelayed({ if (pressed == hit && System.currentTimeMillis() >= lingerUntil) { pressed = ""; invalidate() } }, PRESS_MIN_MS + 10L)
    }
    private var lingerUntil = 0L
    private fun padFlash(key: String, startWait: Boolean = true) {
        val c0 = cur
        // the button that carries the pressed key's glyph in the strip is the one the press does (Buy, Sell, Repair, Take, ...)
        val byGlyph = glyphHits[when (key) { "PAD1" -> "rev_a"; "PAD2" -> UiKit.backGlyph(); "PAD3" -> "rev_x"; "PAD4" -> "rev_y"; else -> "" }]
        val curHit = if (c0 == null) null else hits.lastOrNull { h -> h.second == c0 || h.second == "do:$c0" || h.second == "lt:$c0" ||
            h.second.endsWith(" $c0") || h.second.endsWith(":$c0") }?.second
        val set = when (key) {
            "PAD2" -> listOfNotNull(byGlyph ?: hits.lastOrNull { it.second == "x" }?.second)
            "PAD1" -> listOfNotNull(curHit, byGlyph)       // the cursor's thing AND the button A does (a vendor's Buy / Sell)
            "PAD3", "PAD4" -> listOfNotNull(byGlyph)
            else -> emptyList()
        }
        if (set.isEmpty()) return
        if (key != "PAD2" && startWait) {
            byGlyph?.let { wait.begin(it) }
            curHit?.takeIf { it.startsWith("do:") }?.let { wait.begin(it) }
        }
        flashHits = set.toSet()
        flashUntil = System.currentTimeMillis() + PRESS_MIN_MS
        invalidate()
        postDelayed({ invalidate() }, PRESS_MIN_MS + 10L)
    }
    // THE PRESS IN ITS OWN FRAME (the user, b116: "the button push is delayed a moment after I push it ... feels slow and laggy"):
    // nav.last comes back through the game (a round trip). While the window holds the controller, the app's own shared reader
    // (PadHub, one su for every view) gives the face buttons' edges at once; the press is drawn then (display only: what it
    // does still comes from the addon), and the document's nav.last for the same key does not flash it again.
    private var padSub: PadInput? = null
    private var padSubMask = 0
    private val padEdgeAt = HashMap<String, Long>()
    private fun padSubSync(on: Boolean) {
        if (!on) { padSub?.stop(); padSubMask = 0; return }
        val p = padSub ?: PadInput({ _, _, _, _ -> }, { _, _ -> }, { m -> padSubHeld(m) }).also { padSub = it }
        p.start()
    }
    private fun padSubHeld(mask: Int) {
        val down = mask and padSubMask.inv()
        padSubMask = mask
        if (down == 0 || doc?.optJSONObject("nav")?.optBoolean("on") != true) return
        for ((slot, key) in FACE_SLOTS) if (PadInput.isHeld(down, slot)) {
            padEdgeAt[key] = System.currentTimeMillis()
            padFlash(key)
        }
    }
    override fun onDetachedFromWindow() { padSubSync(false); super.onDetachedFromWindow() }
    private val wait = PressWait(this)                  // the game's spinner on a pressed action until the add-on's answer
    private var flashHits: Set<String> = emptySet()
    private var flashUntil = 0L
    /** a finger on it (or its linger), or a controller press flashing it */
    private fun isPressed(hit: String) = pressed == hit || (hit in flashHits && System.currentTimeMillis() < flashUntil)
    private val ownPress = HashSet<String>()            // hits drawn pushed in by their own draw this frame (the kit, a wrapped tile)
    private val ownPrefix = HashSet<String>()           // the same, by the prefix of a hit whose slot is added after the draw
    /** the state of a kit button drawn directly, whose hit starts with [prefix] (a press pushes it in; the overlay leaves it) */
    private fun kitState(prefix: String, base: UiKit.Button = UiKit.Button.UP): UiKit.Button {
        ownPrefix.add(prefix)
        if (base == UiKit.Button.DISABLED) return base
        val now = System.currentTimeMillis()
        val down = pressed.startsWith(prefix) || (now < flashUntil && flashHits.any { it.startsWith(prefix) })
        return if (down) UiKit.Button.DOWN else base
    }
    private val glyphHits = HashMap<String, String>()   // glyph -> the strip button drawn with it this frame

    private fun state(enabled: Boolean, hit: String, busy: Boolean) = when {
        !enabled -> UiKit.Button.DISABLED
        busy || isPressed(hit) -> UiKit.Button.DOWN
        else -> UiKit.Button.UP
    }

    /** one of the game's red buttons in the strip; [glyph] is the controller button that does the same */
    private fun stripButton(c: Canvas, r: RectF, label: String, hit: String, enabled: Boolean, busy: Boolean,
                            glyph: String? = null, id: String? = null, always: Boolean = false) {
        val st = state(enabled, hit, busy)
        // a glyph says "this controller button does it": true while the controller is ours, and
        // always on an offer's two answers (user, 2026-09-28); the kit pushes a DOWN button in itself (UiKit.pressed)
        val w = wait.isWaiting(hit)
        if (glyph != null && (always || cur != null)) UiKit.button(c, r, label, glyph, st, waiting = w)
        else UiKit.button(c, r, label, st, waiting = w)
        if (glyph != null && enabled) glyphHits[glyph] = hit
        ownPress.add(hit)
        hits.add(r to hit)
        if (id != null && cur == id) UiKit.focus(c, r)
    }

    /** the page of a quest or of a gossip: paper from edge to edge of the window, no border of its own */
    private fun page(c: Canvas, inner: RectF, bottom: Float): RectF {
        val r = RectF(inner.left, inner.top, inner.right, bottom)
        val paper = UiKit.tex("parchment")
        if (paper != null) c.drawBitmap(paper, null, r, pArt)
        else { pFill.color = Theme.parchment; c.drawRect(r, pFill) }
        return r
    }

    /** what an action answered, or why nothing can be done: the strip's free space, for 4 s */
    private fun resultLine(c: Canvas, left: Float, right: Float, cy: Float, now: Long): Boolean {
        val (s, col) = when {
            noteText != null && now - noteAt < NOTE_MS -> noteText!! to (if (noteOk) Theme.gold else Theme.unusable)
            pendingCmd != null -> "Waiting for the game" to Theme.dim
            ageMs() > LIVE_MS -> "Waiting for the game" to Theme.dim
            else -> return false
        }
        val p = UiKit.bodyPaint(24f, col)
        if (right - left < 60f) return false
        val l = layout(s, p, (right - left).toInt(), 2f)
        if (l.lineCount <= 2) drawLayout(c, l, left, cy - l.height / 2f)
        else c.drawText(fit(p, s, right - left), left, cy + 8f, p)
        return true
    }

    // EVERY NPC WINDOW'S PRESS (the user, b114: "are all the NPC menus completed as well?"): whatever a finger or the controller
    // presses that did not push itself in (a gossip / quest / trainer row, a mail or auction row, a loot card, a bank slot, a
    // flight point, a pet, Repair / Junk) gets the pressed look over it: darker, with a pressed-in edge, for PRESS_MIN_MS
    override fun draw(c: Canvas) {
        super.draw(c)
        val now = System.currentTimeMillis()
        val want = HashSet<String>()
        if (pressed.isNotEmpty() && pressed != "lt:hold") want.add(pressed)
        if (now < flashUntil) want.addAll(flashHits)
        for (h in want) {
            if (h in ownPress || ownPrefix.any { h.startsWith(it) } || h == "x" || h.startsWith("why:") || h.startsWith("tab:") ||
                h.startsWith("qty:")) continue
            val r = hits.lastOrNull { it.second == h }?.first ?: continue
            pressShade.color = Color.argb(84, 0, 0, 0)
            c.drawRoundRect(r, 10f, 10f, pressShade)
            pressEdge.color = Color.argb(150, 0, 0, 0)
            c.drawRoundRect(RectF(r).apply { inset(2f, 2f) }, 10f, 10f, pressEdge)
        }
        if (want.isNotEmpty()) postInvalidateOnAnimation()
        // a waiting piece that is not a kit button (a row, a tile): the kit's spinner over it
        for (k in wait.keys()) {
            if (k in ownPress) continue
            val r = hits.lastOrNull { it.second == k }?.first ?: continue
            UiKit.waitOverlay(c, r)
        }
    }
    private val pressShade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f }

    override fun onDraw(c: Canvas) {
        val now = fxClock()
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val growing = kind == Kind.LOOT && growAt > 0L && now - growAt < LOOT_GROW
        // the user, RC3: "When looting that animation freezes and makes the screen kind of flash black": after the loot window's
        // close animation the view stayed VISIBLE for a frame with nothing open and painted the whole screen ink. Nothing open,
        // nothing closing, nothing landing: hide at once instead
        if (kind == Kind.NONE && !lootClosing() && landing == null) { refresh(); return }
        if (!(lootCompact || growing || landing != null || (kind == Kind.NONE && lootClosing()))) c.drawColor(Theme.ink)
        hits.clear(); glyphHits.clear(); ownPress.clear(); ownPrefix.clear(); goCmd = null; goWhy = null
        if (landing != null) {
            if (drawLanding(c, r, now)) { postInvalidateOnAnimation(); return }
            refresh()
        }
        if (kind == Kind.NONE && lootClosing()) {
            lootLast?.let { compactLoot(c, r, it, now, closing = true) }
            postInvalidateOnAnimation()
            return
        }
        viewA = RectF(); viewB = RectF()
        val d = doc ?: return
        if (pendingCmd != null && now - pendingAt > PENDING_TIMEOUT_MS) {
            pendingCmd = null
            note("No answer from the game", false)
        }
        when (kind) {
            Kind.GOSSIP -> {
                val g = d.optJSONObject("gossip") ?: return
                pageOfLines(c, r, g.optJSONObject("npc")?.optString("name", "") ?: "",
                    prose(g.optString("text", "")), "portrait_gossip", now)
            }
            Kind.QUEST -> {
                val q = d.optJSONObject("quest") ?: return
                val npc = q.optJSONObject("npc")?.optString("name", "") ?: ""
                val qs = q.optJSONObject("space")
                if (q.optString("stage") == "greeting") pageOfLines(c, r, npc, prose(q.optString("text", "")), "portrait_quest", now)
                else if (qs != null) questSpace(c, r, q, qs, npc, now)
                else questWindow(c, r, q, npc, now)
            }
            Kind.MERCHANT -> {
                val m = d.optJSONObject("merchant") ?: return
                vendorWindow(c, r, m, now)
            }
            Kind.TRAINER -> {
                val t = d.optJSONObject("trainer") ?: return
                trainerWindow(c, r, t, now)
            }
            Kind.STABLE -> {
                val st = d.optJSONObject("stable") ?: return
                stableWindow(c, r, st, now)
            }
            Kind.PETITION -> {
                val pe = d.optJSONObject("petition") ?: return
                petitionWindow(c, r, pe)
            }
            Kind.QTSETUP -> {
                val q = d.optJSONObject("qtsetup") ?: return
                qtWindow(c, r, q, now)
            }
            Kind.MAIL -> {
                val ml = d.optJSONObject("mail") ?: return
                mailWindow(c, r, ml, now)
            }
            Kind.AUCTION -> {
                val au = d.optJSONObject("auction") ?: return
                auctionWindow(c, r, au, now)
            }
            Kind.TAXI -> {
                val tx = d.optJSONObject("taxi") ?: return
                taxiWindow(c, r, tx, now)
            }
            Kind.BANK -> {
                val bk = d.optJSONObject("bank") ?: return
                bankWindow(c, r, bk, now)
            }
            Kind.LOOT -> {
                val lt = d.optJSONObject("loot") ?: return
                lootWindow(c, r, lt, now)
            }
            Kind.NONE -> {}
        }
        // the close button is the frame's; its touch area is grown to the touch floor. Gossip, the quest-giver list
        // and a quest offer have no X (Goodbye / Decline close them), so no touch box either
        val q = d.optJSONObject("quest")
        val noX = kind == Kind.GOSSIP || kind == Kind.LOOT || (kind == Kind.QUEST && (q?.optString("stage") == "greeting" || q?.optString("stage") == "detail"))
        if (!noX) hits.add(UiKit.windowClose(r) to "x")
        if (pendingCmd != null || (noteText != null && now - noteAt < NOTE_MS)) postInvalidateDelayed(250)
        typingLine(c, r, now)
    }

    /** the field each keyboard field types, by the add-on's name: its words over the keyboard */
    private val fieldWords = mapOf("mailto" to "To", "mailsubject" to "Subject", "mailbody" to "Letter", "mailgold" to "Gold",
        "mailsilver" to "Silver", "mailcopper" to "Copper", "ahtext" to "Search", "ahmin" to "Level from", "ahmax" to "Level to")

    /**
     * THE LINE OVER THE KEYBOARD (f-mailbox-look): the keyboard covers the window's lower half, where some fields stand (the money,
     * the level range), so what is typed always shows just above it, as Chat's message bar: the field's name in gold, the text, the
     * caret. Touches above the keyboard still reach the window.
     */
    private fun typingLine(c: Canvas, r: RectF, now: Long) {
        val field = kbField ?: return
        val kh = keyboard?.let { if (it.height > 0) it.height else ChatKeyboard.fullHeight() } ?: return
        val box = RectF(r.left + 8f, r.bottom - kh - 86f, r.right - 8f, r.bottom - kh - 6f)
        UiKit.menu(c, box, solid = true)
        val lab = (fieldWords[field] ?: "Text") + ":"
        val lp = UiKit.bodyPaint(26f, Theme.gold)
        c.drawText(lab, box.left + 22f, box.centerY() + 9f, lp)
        val x = box.left + 40f + lp.measureText(lab)
        val tp = UiKit.bodyPaint(28f, Color.WHITE)
        val maxW = box.right - 24f - x
        val drawn = if (tp.measureText(kbText) > maxW) "…" + kbText.takeLast(tp.breakText(kbText.reversed(), true, maxW - 24f, null)) else kbText
        c.drawText(drawn, x, box.centerY() + 10f, tp)
        if ((now / 500L) % 2L == 0L) {
            val cx = x + tp.measureText(drawn) + 3f
            pFill.color = Theme.gold
            c.drawRect(cx, box.top + 18f, cx + 3f, box.bottom - 18f, pFill)
        }
        postInvalidateDelayed(500L)
    }

    /**
     * B ON THE CLOSE X (user, 2026-09-29 11:00: "with vendors I cant see the home bar, so the B
     * should be on the X on the top"; 11:23 picked look 1, the small B on the X's corner). This window
     * covers the bottom bar, so Home's B cannot be seen here: the frame's close X carries it while the
     * addon says the next B closes the window and goes Home (`bnext` = "home"); whenever B would do
     * something else first ("back", e.g. a game dialog on top), the plain X. The look is UI Art's kit
     * (`windowFrame(closeB)`).
     */
    private fun closeB(): Int = if (doc?.optString("bnext") == "home") CLOSE_B_LOOK else 0

    // ---- gossip, and the old-style quest list: what the NPC says, then LINES ON THE PAGE

    private fun pageOfLines(c: Canvas, r: RectF, npc: String, said: String, portrait: String, now: Long) {
        val inner = UiKit.windowGround(c, r)
        val strip = UiKit.strip(c, inner)
        val paper = page(c, inner, strip.top - 6f)

        var y = inner.top + 36f
        if (said.isNotEmpty()) {
            val l = layout(said, UiKit.inkPaint(29f), (inner.width() - 200f).toInt())
            c.save(); c.clipRect(paper.left, paper.top, paper.right, paper.top + paper.height() * 0.42f)
            y = drawLayout(c, l, inner.left + 150f, y)
            c.restore()
            y = min(y, paper.top + paper.height() * 0.42f)
        }
        y = max(y + 22f, r.top + UiKit.WINDOW_UNDER_RING + 10f)

        // column-major: one column up to 4 entries, two above
        val list = entries()
        val cols = if (list.size > 4) 2 else 1
        val rows = if (cols == 2) (list.size + 1) / 2 else list.size
        val colw = (inner.width() - 60f) / 2f
        viewA = RectF(paper.left, y, paper.right, paper.bottom - 8f)
        contentA = rows * LINE_H + 8f
        val at = list.indexOfFirst { it.cmd == cur }
        follow(if (at < 0) -1 else at % max(1, rows), LINE_H, viewA.height())
        scrollA = scrollA.coerceIn(0f, max(0f, contentA - viewA.height()))
        c.save(); c.clipRect(viewA)
        for ((i, e) in list.withIndex()) {
            val col = i / max(1, rows); val row = i % max(1, rows)
            val x = inner.left + 30f + col * colw
            val ry = y + row * LINE_H - scrollA
            val line = RectF(x, ry + 6f, x + (if (cols == 2) colw - 10f else inner.width() - 70f), ry + 105f)
            if (line.bottom < viewA.top || line.top > viewA.bottom) continue
            val usable = e.kind != K_OPTION || e.o.optInt("status", 0) == 0
            val hit = if (usable) "do:${e.cmd}" else "why:Not available"
            // the gold selection with the A glyph (user, 2026-09-30: "Menu's with a single option aren't always obvious
            // that I need to press A" / "a golden highlight in most selection scenarios"); UI Art's paperGold
            if (cur == e.cmd) UiKit.paperGold(c, line, look = PAPER_GOLD, glyph = "rev_a")
            else if (pendingCmd == e.cmd || pressed == hit && usable) UiKit.paperGlow(c, line)
            val pic = RectF(x + 18f, ry + 31f, x + 66f, ry + 79f)
            pArt.alpha = if (usable) 255 else 110
            UiKit.pixel.alpha = pArt.alpha
            when (e.kind) {
                K_AVAIL -> UiKit.tex("icon_quests")?.let { c.drawBitmap(it, null, pic, pArt) }
                // b-npc-quest-icons: the gold ? only when it is ready to turn in; in progress, the game's grey ?
                // (GossipFrame's IncompleteQuestIcon, fid 365195 in the kit), else the gold one with its colour taken out
                K_ACTIVE -> if (e.o.optBoolean("done", false)) UiKit.tex("icon_turnin")?.let { c.drawBitmap(it, null, pic, pArt) }
                    else UiKit.icon(365195)?.let { c.drawBitmap(it, null, pic, pArt) }
                        ?: UiKit.tex("icon_turnin")?.let { pQuestGrey.alpha = pArt.alpha; c.drawBitmap(it, null, pic, pQuestGrey) }
                else -> {
                    val id = e.o.optInt("icon", 0)
                    val big = Icons.get(id.takeIf { it > 0 })
                    if (big != null) c.drawBitmap(big, null, pic, pArt)
                    else (UiKit.icon(id) ?: UiKit.icon(132053))?.let { c.drawBitmap(it, null, pic, UiKit.pixel) }
                }
            }
            pArt.alpha = 255; UiKit.pixel.alpha = 255
            val p = UiKit.inkPaint(31f)
            if (!usable) p.color = Theme.parchmentInkDim
            val label = if (e.kind == K_OPTION) e.o.optString("name", "...") else e.o.optString("title", "Quest")
            c.drawText(fit(p, label, line.right - 12f - (x + 84f)), x + 84f, ry + 55f - (p.ascent() + p.descent()) / 2f, p)
            val clip = RectF(line.left, max(line.top, viewA.top), line.right, min(line.bottom, viewA.bottom))
            if (clip.height() > 0f) hits.add(clip to hit)
        }
        c.restore()
        if (contentA > viewA.height() + 2f)
            UiKit.scrollBar(c, RectF(inner.right - 40f, viewA.top, inner.right - 2f, viewA.bottom),
                scrollA / contentA, min(1f, (scrollA + viewA.height()) / contentA))

        // GOODBYE is back, with B (user, 2026-09-30: "I really want to re add the goodbye button and remove the x
        // for those"; this reverses their "Drop Goodbye" of the same day)
        val bt = strip.top + 13f
        val bye = RectF(inner.right - 338f, bt, inner.right - 8f, bt + 111f)
        stripButton(c, bye, "Goodbye", "x", enabled = true, busy = false, glyph = UiKit.backGlyph(), always = true)
        resultLine(c, inner.left + 24f, bye.left - 24f, strip.centerY() + 2f, now)
        // no X here: Goodbye (B) closes (user, 2026-09-30: "re add the goodbye button and remove the x"; art-kit f900e7a)
        UiKit.windowFrame(c, r, npc, UiKit.tex(portrait), close = false)
    }

    // ---- a quest being offered, asked about, or turned in: ONE page, two columns

    private fun questWindow(c: Canvas, r: RectF, q: JSONObject, npc: String, now: Long) {
        val stage = q.optString("stage")
        val id = q.optInt("id")
        val act = q.optJSONObject("act")
        val inner = UiKit.windowGround(c, r)
        val strip = UiKit.strip(c, inner)
        qStrip = RectF(strip)
        val paper = page(c, inner, strip.top - 6f)
        val ink = UiKit.inkPaint(25f)
        val dim = UiKit.inkPaint(22f).apply { color = Theme.parchmentInkDim }

        // ---- the left of the page: what is read. It starts under the ring and scrolls on its own.
        val lx = inner.left + 44f; val lw = 640f
        viewA = RectF(paper.left, paper.top, lx + lw + 24f, paper.bottom - 8f)
        scrollA = scrollA.coerceIn(0f, max(0f, contentA - (viewA.bottom - (r.top + UiKit.WINDOW_UNDER_RING))))
        c.save(); c.clipRect(viewA)
        var y = r.top + UiKit.WINDOW_UNDER_RING - scrollA
        val y0 = y
        val title = layout(q.optString("title", "Quest"), UiKit.inkPaint(50f, heading = true), lw.toInt(), 0f)
        y = drawLayout(c, title, lx, y) + 10f
        val body = prose(q.optString("text", ""))
        if (body.isNotEmpty()) y = drawLayout(c, layout(body, ink, lw.toInt()), lx, y) + 20f
        val obj = prose(q.optString("objectives", ""))
        if (obj.isNotEmpty()) {
            c.drawText("Quest Objectives", lx, y + 32f, UiKit.inkPaint(36f, heading = true)); y += 48f
            y = drawLayout(c, layout(obj, ink, lw.toInt()), lx, y) + 14f
        }
        contentA = y - y0 + 16f
        c.restore()

        // ---- the right of the page: what it takes and what it gives, in the game's words and order
        val rx = lx + lw + 50f; val rw = inner.right - 34f - rx
        viewB = RectF(rx - 12f, paper.top, paper.right, paper.bottom - 8f)
        scrollB = scrollB.coerceIn(0f, max(0f, contentB - viewB.height()))
        c.save(); c.clipRect(viewB)
        var ry = inner.top + 34f - scrollB
        val ry0 = ry
        fun plate(o: JSONObject, hit: String?, sel: Boolean, idOf: String?) {
            val row = RectF(rx, ry, rx + rw, ry + 116f)
            val usable = !o.has("usable") || o.optBoolean("usable", true)
            UiKit.itemPlate(c, row, itemIcon(o), o.optString("name", "Item"), Theme.quality(o.optInt("q", 1)),
                o.optInt("n", 1), selected = sel, unusable = !usable)
            // the PICKED reward keeps its own gold mark, with no glyph (UI Art, b10 review: the A lives on Complete Quest
            // only once a reward is picked); the controller's cursor is the gold box; an A on the cursor's row only
            // while nothing is picked yet (A there picks it)
            // the picked reward: the game's own QuestInfoItemHighlight (art-kit 529ab73); the cursor stays the gold bar
            // wherever it is, over the outline on the picked one (UI Art's b11 re-shoot)
            val picked = sel && stage == "complete"
            if (picked) UiKit.rewardPicked(c, row)
            if (idOf != null && cur == idOf) {
                if (stage == "complete" && selected != null) UiKit.focus(c, row, onPaper = true)
                else UiKit.paperGold(c, row, look = PAPER_GOLD, glyph = "rev_a")
            }
            if (hit != null) {
                val clip = RectF(row.left, max(row.top, viewB.top), row.right, min(row.bottom, viewB.bottom))
                if (clip.height() > 0f) hits.add(clip to hit)
            }
            ry += 124f
        }
        if (stage == "progress") {
            val req = arr(q, "required")
            val cost = q.optLong("money", 0)
            if (req.isNotEmpty() || cost > 0) {
                c.drawText("Required", rx, ry + 32f, UiKit.inkPaint(36f, heading = true)); ry += 50f
                for (o in req) plate(o, null, false, null)
                if (cost > 0) { UiKit.money(c, cost, rx, ry + 18f, 28f, Theme.parchmentInk, shadow = false); ry += 46f }
            }
            if (!q.optBoolean("completable", true))
                ry = drawLayout(c, layout("You do not have everything yet.", ink, rw.toInt()), rx, ry + 6f) + 10f
        } else {
            val rew = q.optJSONObject("rewards")
            val choices = arr(rew, "choices"); val items = arr(rew, "items")
            val coin = rew?.optLong("money", 0) ?: 0
            val xp = rew?.optInt("xp", 0) ?: 0
            if (choices.isNotEmpty() || items.isNotEmpty() || coin > 0 || xp > 0) {
                c.drawText("Rewards", rx, ry + 32f, UiKit.inkPaint(36f, heading = true)); ry += 50f
            }
            if (choices.isNotEmpty()) {
                val pick = stage == "complete" && choices.size > 1
                val words = when {
                    pick -> "Choose one of these rewards:"
                    choices.size > 1 -> "You will be able to choose one of these rewards:"
                    else -> "You will receive:"
                }
                ry = drawLayout(c, layout(words, dim, rw.toInt(), 2f), rx, ry) + 8f
                for (ch in choices) {
                    val cmd = "reward $id ${ch.optInt("i")}"
                    plate(ch, if (pick) "sel:$cmd" else null, stage == "complete" && selected == cmd, cmd)
                }
                ry += 8f
            }
            if (items.isNotEmpty()) {
                c.drawText(if (choices.isNotEmpty()) "You will also receive:" else "You will receive:", rx, ry + 20f, dim); ry += 36f
                for (o in items) plate(o, null, false, null)
            }
            if (xp > 0 || coin > 0) {
                if (xp > 0) {
                    c.drawText("Experience:", rx, ry + 32f, UiKit.inkPaint(24f).apply { color = Theme.parchmentInkDim })
                    c.drawText(xp.toString(), rx + 140f, ry + 34f, UiKit.numberPaint(28f, Theme.parchmentInk, false))
                }
                if (coin > 0) UiKit.money(c, coin, inner.right - 40f, ry + 26f, 28f, Theme.parchmentInk, fromRight = true, shadow = false)
                ry += 50f
            }
        }
        contentB = ry - ry0 + 16f
        c.restore()

        // ---- the strip, as the game's own quest window has it (user, 2026-09-28: "like how the wow UI has it
        // for everything"): the answer that goes on in the bottom LEFT corner, Decline in the bottom RIGHT one
        val bt = strip.top + 13f
        val go = RectF(inner.left + 8f, bt, inner.left + 368f, bt + 111f)
        val other = RectF(inner.right - 8f - 340f, bt, inner.right - 8f, bt + 111f)
        val block = blocked(now)
        fun fixed(rc: RectF, label: String, cmd: String, slot: Int, enabled: Boolean, why: String,
                  glyph: String?, idOf: String) {
            val on = enabled && slot >= 0
            val hit = when {
                !on -> "why:" + (if (enabled) "The game gave this no tap cell" else why)
                else -> "act:$cmd|$slot"
            }
            // at an offer the two glyphs say it all, so nothing is framed there
            stripButton(c, rc, label, hit, on && (block == null || pendingCmd == cmd), pendingCmd == cmd, glyph,
                if (stage == "detail") null else idOf, always = stage == "detail")
        }
        var left = go.right + 24f
        var right = inner.right - 24f
        when (stage) {
            "detail" -> {
                fixed(other, "Decline", "decline $id", act?.let { slotOf(it, "decline") } ?: -1, true, "", "rev_b", "decline $id")
                fixed(go, "Accept", "accept $id", act?.let { slotOf(it, "accept") } ?: -1, true, "", "rev_a", "accept $id")
                right = other.left - 24f
            }
            // Continue is never the gold focus either, just the A glyph (user, 2026-09-30: "The continue button before
            // completing quest still highlights")
            "progress" -> fixed(go, "Continue", "continue $id", act?.let { slotOf(it, "continue") } ?: -1,
                q.optBoolean("completable", true), "You do not have everything yet", "rev_a", "")
            "complete" -> {
                val choices = arr(q.optJSONObject("rewards"), "choices")
                if (choices.size == 1 && selected == null) selected = "reward $id ${choices[0].optInt("i")}"
                // Complete is never the gold focus (user, 2026-09-30: "highlighting the complete quest button is ugly
                // ... after selecting it then the quest complete button should only have the A glyph"): the A glyph
                // says A completes now: no reward to choose, or the cursor on the reward picked
                if (choices.isEmpty()) fixed(go, "Complete Quest", "reward $id", act?.let { slotOf(it, "reward") } ?: -1, true, "",
                    "rev_a", "")
                else {
                    val e = entries().firstOrNull { it.cmd == selected }
                    goCmd = e; goWhy = "Choose a reward first"
                    stripButton(c, go, "Complete Quest", "go", e != null && (block == null || pendingCmd == e.cmd),
                        e != null && pendingCmd == e.cmd, "rev_a".takeIf { e != null && cur == selected })
                }
            }
        }
        resultLine(c, left, right, strip.centerY() + 2f, now)
        // ---- THE TOOLTIP of the highlighted / selected reward (user, 2026-09-30: "on quest rewards, I need a
        // tooltip to see what my reward choices actually are"), over the NPC's words (UI Art's placement)
        (cur ?: selected)?.let { k ->
            val t = tipFor(k) ?: return@let
            val o = rewardOf(q, k, t.optString("key"))
            drawTip(c, lx - 10f, inner.top + 150f, 560f, o?.optString("name", "Item") ?: "Item", o?.optInt("q", 1) ?: 1, t,
                    inner.top + 140f, inner.bottom - 12f, lx - 10f + 560f + 12f)
        }
        // a quest OFFER has no X: Decline (B) is its close (user, 2026-09-30: "the decline button is enough")
        UiKit.windowFrame(c, r, npc, UiKit.tex("portrait_quest"), closeDown = pressed == "x", closeB = closeB(), close = stage != "detail")
    }

    // ---- the TRAINER (UI Art's TRAINER_SPEC.md, the user's "Yes, build it", 2026-09-30): the game's ClassTrainerFrame
    // (camelot): the next rank first, the game's groups across both columns (they fold), services two to a line,
    // the Settings menu (the game's filters), A Train, Y Spellbook (the user: "Y opens the Spellbook room; B comes back").

    private var trFolds: Set<Int> = emptySet()
    private var trMenu = false

    private class TRow(val id: String, val y: Float, val x: Float, val w: Float, val o: JSONObject, val head: Boolean)

    private fun trainerWindow(c: Canvas, r: RectF, t: JSONObject, now: Long) {
        val inner = UiKit.windowGround(c, r, marble = true)
        val block = blocked(now)
        val act = t.optJSONObject("act")
        val purse = t.optLong("money", 0)
        val ty = r.top + 94f; val th = 108f
        // a profession trainer: its rank bar
        val maxRank = t.optInt("maxRank", 0)
        if (maxRank > 0) {
            val rk = t.optInt("rank", 0)
            UiKit.skillBar(c, RectF(186f, ty + 30f, 546f, ty + 78f), rk.toFloat() / maxRank, "$rk/$maxRank")
        }
        // the Settings drop-down, on SELECT (the user, 2026-10-09: Start opens the pause wheel from every room and pop-up)
        val dd = RectF(inner.right - 290f, ty + 8f, inner.right - 6f, ty + 100f)
        UiKit.dropDown(c, dd, "Settings", open = trMenu)
        UiKit.pad(c, "rev_minus", dd.left - 34f, dd.centerY(), 50f)
        act?.let { slotOf(it, "menu") }?.let { if (it >= 0) hits.add(dd to "act:trmenu|$it") }
        val lp = Paint().apply { color = Color.rgb(96, 80, 50) }
        c.drawRect(RectF(inner.left - 6f, ty + th, inner.right + 6f, ty + th + 2f), lp)

        // ---- the list: built as the addon's controller lines (addon i37), so the cursor and the rows agree
        val ly0 = ty + th + 8f; val ly1 = inner.bottom - UiKit.STRIP - 4f
        val lw = inner.width() - 50f
        val cw = (lw - 14f) / 2f
        viewA = RectF(inner.left - 6f, ly0, inner.right + 6f, ly1)
        val services = arr(t, "services")
        val step = t.optInt("step", -1)
        val rows = ArrayList<TRow>()
        var y = 4f
        if (step > 0) services.firstOrNull { it.optInt("i") == step && it.optString("cat") != "header" }?.let {
            rows.add(TRow("train $step", y, 0f, lw, it, false)); y += 132f
        }
        var col = 0; var folded = false
        for (sv in services) {
            if (sv.optString("cat") == "header") {
                if (col == 1) { y += 132f; col = 0 }
                val i = sv.optInt("i")
                folded = i in trFolds
                rows.add(TRow("trhead $i", y, 0f, lw, sv, true)); y += 72f
                continue
            }
            if (sv.optInt("i") == step || folded) continue
            rows.add(TRow("train ${sv.optInt("i")}", y, col * (cw + 14f), cw, sv, false))
            if (col == 1) { y += 132f; col = 0 } else col = 1
        }
        if (col == 1) y += 132f
        contentA = y + 8f
        val vh = ly1 - ly0
        rows.firstOrNull { it.id == cur }?.let { rw ->
            val bottom = rw.y + if (rw.head) 62f else 124f
            if (rw.y < scrollA) scrollA = rw.y
            if (bottom > scrollA + vh) scrollA = bottom - vh
        }
        scrollA = scrollA.coerceIn(0f, max(0f, contentA - vh))
        val level = t.optInt("level", 0)
        fun rectOf(rw: TRow) = RectF(inner.left + 4f + rw.x, ly0 + rw.y - scrollA,
                                     inner.left + 4f + rw.x + rw.w, ly0 + rw.y - scrollA + if (rw.head) 62f else 124f)
        c.save(); c.clipRect(viewA)
        if (rows.isEmpty()) c.drawText("This trainer has nothing to teach you.", inner.left + 24f, ly0 + 56f, UiKit.bodyPaint(27f, Theme.dim))
        for (rw in rows) {
            val rr = rectOf(rw)
            if (rr.bottom < ly0 || rr.top > ly1) continue
            val o = rw.o
            val clip = RectF(rr.left, max(rr.top, ly0), rr.right, min(rr.bottom, ly1))
            if (rw.head) {
                UiKit.recipeHeader(c, rr, o.optString("name", ""), o.optInt("i") in trFolds)
                val sl = o.optInt("slot", -1)
                if (sl >= 0 && clip.height() > 0f) hits.add(clip to "act:trhead ${o.optInt("i")}|$sl")
            } else {
                val cat = o.optString("cat")
                val cost = o.optLong("cost", 0)
                val needs = ArrayList<UiKit.Need>()
                val lvl = o.optInt("lvl", 0)
                // met: the player's level when known; without it, a learnable ("available") row meets every need
                if (lvl > 1) needs.add(UiKit.Need("Level ", "$lvl", met = if (level > 0) level >= lvl else cat != "unavailable"))
                str(o, "skill")?.let { needs.add(UiKit.Need("$it (", "${o.optInt("rank")}", ")", met = o.optBoolean("skillOk", true))) }
                o.optJSONArray("abil")?.let { a ->
                    for (n in 0 until a.length()) a.optJSONObject(n)?.let { needs.add(UiKit.Need("", it.optString("n"), met = it.optBoolean("ok", true))) }
                }
                UiKit.servicePlate(c, rr, Icons.get(o.optInt("icon", 0).takeIf { it > 0 }), o.optString("name", ""),
                    str(o, "sub")?.takeIf { it.isNotBlank() }, needs, known = cat == "used", cost = cost,
                    affordable = cost <= purse, unavailable = cat == "unavailable")
                if (clip.height() > 0f) hits.add(clip to "sel:${rw.id}")
            }
            if (cur == rw.id || (cur == null && selected == rw.id)) UiKit.focus(c, rr)
        }
        c.restore()
        UiKit.scrollBar(c, RectF(inner.right - 40f, ly0, inner.right - 2f, ly1),
            if (contentA > 0f) scrollA / contentA else 0f,
            if (contentA > 0f) min(1f, (scrollA + vh) / contentA) else 1f)

        // ---- the tooltip: the spell's own words, two thirds wide on the other half, level with the row
        val shownCmd = cur ?: selected
        rows.firstOrNull { it.id == shownCmd && !it.head }?.let { rw ->
            tipFor(shownCmd)?.let { tp ->
                val tw = cw * 2f / 3f
                val inLeft = rw.x == 0f
                val otherLeft = inner.left + 4f + if (inLeft) cw + 14f else 0f
                val tx = if (inLeft) otherLeft else otherLeft + cw - tw
                val rowTop = ly0 + rw.y - scrollA
                drawTip(c, tx, rowTop.coerceIn(ly0, max(ly0, ly1 - TIP_MIN_ROOM)), tw, rw.o.optString("name", ""), 1, tp, ly0, ly1, tx)
            }
        }

        // ---- the strip: your money; A Train; Y Spellbook
        val strip = UiKit.strip(c, inner)
        val cy = strip.centerY() + 2f
        c.drawText("Your money", inner.left + 24f, cy - 14f, UiKit.bodyPaint(22f, Theme.dim))
        UiKit.money(c, purse, inner.left + 24f, cy + 20f, 30f)
        val bx = inner.right - 8f
        val e = entries().firstOrNull { it.cmd == shownCmd }
        val cat = e?.o?.optString("cat")
        val trainable = e != null && cat == "available" && e.o.optLong("cost", 0) <= purse && e.slot >= 0
        if (trainable) goCmd = e else goWhy = when {
            e == null -> "Choose a skill first"
            cat == "used" -> "Already known"
            cat == "unavailable" -> "Not yet: see what it requires"
            e.slot < 0 -> "The game gave this no tap cell"
            else -> "Not enough money"
        }
        stripButton(c, RectF(bx - 250f, cy - 55f, bx, cy + 56f), "Train", "go",
            trainable && (block == null || pendingCmd == e?.cmd), pendingCmd == e?.cmd,
            glyph = "rev_a".takeIf { cur != null }, id = "go")
        // a profession trainer: no Spellbook and nothing on Y (the user, 2026-09-30); a class trainer keeps it
        // (addon i49 `prof`: holds without the skill too; the user's Blacksmith visit had no rank bar)
        val profTrainer = t.optBoolean("prof") || maxRank > 0
        if (!profTrainer) {
            val hl = UiKit.hints(c, bx - 270f, cy, "rev_y" to "Spellbook")
            act?.let { slotOf(it, "spell") }?.let { if (it >= 0) hits.add(RectF(hl, cy - 44f, bx - 262f, cy + 44f) to "act:trspell|$it") }
        }

        // ---- the Settings menu (the game's, flattened): the three filters and Categorize
        if (trMenu) {
            val f = t.optJSONObject("filters")
            val rowsM = listOf(
                Triple("available", "Available", Color.rgb(32, 255, 32)),
                Triple("unavailable", "Unavailable", Color.rgb(255, 32, 32)),
                Triple("used", "Already Known", Color.rgb(160, 160, 160)))
            val mh = 60f + rowsM.size * 64f + 18f + 64f + 16f
            val m = UiKit.menu(c, RectF(dd.right - 440f, dd.bottom + 4f, dd.right, dd.bottom + 4f + mh), solid = true)
            c.drawText("Filters", m.left + 28f, m.top + 42f, UiKit.bodyPaint(24f, Theme.gold))
            var my = m.top + 60f
            for ((k, label, colour) in rowsM) {
                val row = RectF(m.left + 6f, my, m.right - 6f, my + 64f)
                UiKit.menuRow(c, row, "      $label", cur == "trf $k", colour, size = 28f)
                UiKit.check(c, row.left + 44f, row.centerY(), 40f, f?.optBoolean(k) == true)
                act?.let { slotOf(it, k) }?.let { if (it >= 0) hits.add(row to "act:trf $k|$it") }
                my += 64f
            }
            UiKit.divider(c, m.left + 20f, my + 8f, m.right - 20f, my + 8f)
            my += 18f
            val row = RectF(m.left + 6f, my, m.right - 6f, my + 64f)
            UiKit.menuRow(c, row, "      Categorize", cur == "trf categorize", Theme.text, size = 28f)
            UiKit.check(c, row.left + 44f, row.centerY(), 40f, t.optBoolean("categorize"))
            act?.let { slotOf(it, "categorize") }?.let { if (it >= 0) hits.add(row to "act:trf categorize|$it") }
        }

        // the ring: a class trainer = the player's class; a profession trainer = its profession
        val ring = if (t.optBoolean("prof") || maxRank > 0) UiKit.tex("mm_prof_" + (str(t, "skillName") ?: str(t, "line") ?: "").lowercase().replace(" ", ""))
                   else UiKit.tex("classicon_" + (str(t, "class") ?: "").lowercase())
        UiKit.windowFrame(c, r, t.optJSONObject("npc")?.optString("name", "") ?: "Trainer", ring, Theme.gold,
            closeDown = pressed == "x", closeB = closeB())
    }

    // ---- the STABLE (UI Art's STABLE_SPEC.md, the user's "Yes, build it", 2026-09-30): the game's PetStableFrame (camelot):
    // the chosen pet on its stage, the slots (Current Pet + Stabled Pets), A picks / swaps, X Purchase = the game's box.

    private var stInHand = -1
    private var stBox = false
    private var stShown: JSONObject? = null       // the pet on the stage: an empty slot keeps the last one (as the game)
    private var petDrag = -1
    private var petDragX = 0f; private var petDragY = 0f

    /** a drag from slot [from] onto slot [to]: the addon's two pet cells, picked then put (ONE game action, SetPetSlot) */
    private fun petMove(from: Int, to: Int) {
        val d = doc ?: return
        if (dryRun) { note("Preview: move $from to $to", true); return }
        blocked(System.currentTimeMillis())?.let { note(it, false); return }
        val pets = arr(d.optJSONObject("stable"), "pets")
        val a = pets.firstOrNull { it.optInt("i") == from }?.optInt("slot", -1) ?: -1
        val b = pets.firstOrNull { it.optInt("i") == to }?.optInt("slot", -1) ?: -1
        if (a < 0 || b < 0) { note("The game gave this no tap cell", false); return }
        val pad = d.optJSONObject("pad")
        TapBridge.tap(context, pad, "ibuy", a) { why -> post { note(why, false) } }
        TapBridge.tap(context, pad, "ibuy", b) { why -> post { note(why, false) } }
        lastTapAt = System.currentTimeMillis()
    }

    // ---- THE GUILD CHARTER (the user, 2026-10-09: "Yes, next"; add-on i108 + Dialogs d18): the game's PetitionFrame, as its own
    // window shows it: the charter, the guild master, the signatures up to the minimum ("Not yet signed" after them), the
    // instructions. Its OWN buttons in the strip (Sign; or Request Signature + Rename for the one who bought it), each pressed by
    // a secure click: a tap on its cell, A on the one under the cursor. The game's confirm / rename boxes are its own. B / X: Close.
    /**
     * QUESTTRAIL'S SETUP ASSISTANT, MIRRORED (1.0.9; the user: "Shared trail has a UI pop-up right when the game starts ... remake it on
     * the bottom screen like other UI", all 12 steps in full). The add-on reads QuestTrail's own window (QuestTrailSetup, or the Shared
     * Trail intro QuestTrailSharedIntro) and every control on the step's page, generically: [items] in the game's reading order, each
     * { k: head | check | slider | color | drop | num | button | edit | text, t: its label, row: the game's line it sits on, its value,
     * en: usable, slot: its cell }. A touch or the controller's A presses QuestTrail's OWN widget (the add-on :Click()s it or sets its
     * slider), so QuestTrail writes its own settings. The strip: Reset, the step's arrows and number, Finish on the last, and "On the
     * top screen" (its window back, for anything this cannot do here).
     */
    /** Add-ons > "QuestTrail settings" (test51): the add-on's cell opens QuestTrail's own pages (Interact i123); false = no cell yet */
    fun openAddonSettings(id: String): Boolean {
        if (id != "questtrail") return false
        val d = doc ?: return false
        val cell = d.optJSONObject("questtrail")?.optInt("open", -1) ?: -1
        if (cell < 0 || dryRun) return false
        return TapBridge.tap(context, d.optJSONObject("pad"), "iact", cell) { why -> post { note(why, false) } }
    }

    private var qtFlashId: String? = null
    private var qtFlashAt = 0L
    private fun qtWindow(c: Canvas, r: RectF, q: JSONObject, now: Long) {
        val inner = UiKit.windowGround(c, r, marble = true)
        val act = q.optJSONObject("act")
        fun col(k: String) = act?.let { slotOf(it, k) } ?: -1
        fun hitOf(cmd: String, cell: Int) = if (cell >= 0) "act:$cmd|$cell" else "why:The game gave this no tap cell"
        val strip = UiKit.strip(c, inner)
        val cy = strip.centerY() + 2f
        val x0 = inner.left + 24f; val x1 = inner.right - 24f
        var y = r.top + UiKit.WINDOW_UNDER_RING
        // the step's title and its words
        c.drawText(q.optString("title", ""), (x0 + x1) / 2f, y + 44f, UiKit.bodyPaint(40f, Theme.gold).apply { textAlign = Paint.Align.CENTER })
        y += 66f
        val about = q.optString("about", "")
        if (about.isNotEmpty()) {
            val lay = layout(about, UiKit.bodyPaint(24f, Theme.text), (x1 - x0).toInt(), 4f)
            drawLayout(c, lay, x0, y); y += lay.height + 14f
        }
        pFill.color = Color.rgb(96, 80, 50); c.drawRect(x0, y, x1, y + 2f, pFill); y += 10f
        // ---- "QuestTrail settings" (test51): its steps' titles down the left, the step's page to their right
        val cats = arr(q, "cats")
        var pageLeft = inner.left + 4f
        if (q.optBoolean("browse") && cats.isNotEmpty()) {
            val cw = 300f
            val col = RectF(inner.left + 4f, y, inner.left + 4f + cw, strip.top - 8f)
            UiKit.ahPanel(c, col, "ah_bg_categories")
            val rh = min(66f, (col.height() - 12f) / cats.size)
            for ((k, ct) in cats.withIndex()) {
                val rr = RectF(col.left + 8f, col.top + 6f + k * rh, col.right - 8f, col.top + 6f + k * rh + rh - 6f)
                UiKit.ahNav(c, rr, ct.optString("t"), 0, ct.optInt("i") == q.optInt("i"))
                hits.add(rr to hitOf("qtcat ${ct.optInt("i")}", slotOf(ct)))
                if (cur == "qtcat ${ct.optInt("i")}") {
                    UiKit.focus(c, rr)
                    val gs = 40f
                    UiKit.pad(c, UiKit.okGlyph(), rr.left - 0.35f * gs + gs / 2f, rr.top - 0.35f * gs + gs / 2f, gs)
                }
            }
            pageLeft = col.right + 12f
        }
        // ---- the page's controls, scrolled to keep the cursor's in view
        val items = arr(q, "items")
        val view = RectF(pageLeft, y, inner.right - 4f, strip.top - 8f)
        // rows: the items the game draws on one line go side by side (its mode buttons, its check rows)
        val rows = ArrayList<MutableList<JSONObject>>()
        for (it in items) { val rw = it.optInt("row", -1); if (rows.isNotEmpty() && rw >= 0 && rows.last().first().optInt("row", -2) == rw) rows.last().add(it) else rows.add(mutableListOf(it)) }
        fun hOf(row: List<JSONObject>): Float = when (row.first().optString("k")) {
            "head" -> 58f; "text" -> 24f + 30f * ((row.first().optString("t").length / 70) + 1); "slider", "num" -> 76f; "drop" -> 74f; "edit" -> 74f
            "button" -> 84f; else -> 64f }
        var content = 0f
        val tops = rows.map { rw -> content.also { content += hOf(rw) } }
        val curRow = rows.indexOfFirst { rw -> rw.any { "qt ${it.optString("id")}" == cur } }
        if (curRow >= 0) {
            val t = tops[curRow]; val b = t + hOf(rows[curRow])
            if (t < scrollA) scrollA = t else if (b > scrollA + view.height()) scrollA = b - view.height()
        }
        viewA = view; contentA = content
        scrollA = scrollA.coerceIn(0f, max(0f, contentA - view.height()))
        c.save(); c.clipRect(view)
        for ((ri, rw) in rows.withIndex()) {
            val top = view.top + tops[ri] - scrollA
            val h = hOf(rw)
            if (top + h < view.top || top > view.bottom) continue
            val px0 = max(x0, view.left + 16f)
            val w = (x1 - px0 - (rw.size - 1) * 16f) / rw.size
            for ((k, it) in rw.withIndex()) {
                val bx = px0 + k * (w + 16f)
                val box = RectF(bx, top, bx + w, top + h - 8f)
                val id = "qt ${it.optString("id")}"
                val en = it.optBoolean("en", true)
                val sl = slotOf(it)
                val lp = UiKit.bodyPaint(26f, if (en) Theme.text else Theme.dim)
                val ty = box.centerY() - (lp.ascent() + lp.descent()) / 2f
                var focusBox = box
                when (it.optString("k")) {
                    "head" -> {
                        c.drawText(it.optString("t"), box.left + 4f, box.top + 38f, UiKit.bodyPaint(30f, Theme.gold))
                        if (it.has("on")) {
                            val on = it.optBoolean("on")
                            UiKit.check(c, box.right - 30f, box.top + 26f, 50f, on)
                            c.drawText(if (on) "On" else "Off", box.right - 64f, box.top + 36f, UiKit.bodyPaint(22f, Theme.dim).apply { textAlign = Paint.Align.RIGHT })
                            focusBox = RectF(box.right - 130f, box.top, box.right, box.top + 54f)
                            hits.add(focusBox to hitOf("qt ${it.optString("id")}", sl))
                        } else focusBox = RectF()
                        pFill.color = Color.argb(90, 96, 80, 50); c.drawRect(box.left, box.top + 50f, box.right, box.top + 52f, pFill)
                    }
                    "check" -> {
                        UiKit.check(c, box.left + 28f, box.centerY(), 50f, it.optBoolean("on"), enabled = en)
                        c.drawText(fit(lp, it.optString("t"), box.width() - 70f), box.left + 66f, ty, lp)
                        hits.add(box to if (en) hitOf("qt ${it.optString("id")}", sl) else "why:Turn its group on first")
                    }
                    "slider", "num" -> {
                        c.drawText(fit(lp, it.optString("t"), box.width() * 0.42f), box.left + 4f, ty, lp)
                        val lo = it.optDouble("min", 0.0); val hi = it.optDouble("max", 1.0); val v = it.optDouble("v", lo)
                        val sx0 = box.left + box.width() * 0.46f; val sx1 = box.right - 8f
                        val bm = RectF(sx0, box.centerY() - 30f, sx0 + 60f, box.centerY() + 30f)
                        val bp = RectF(sx1 - 60f, box.centerY() - 30f, sx1, box.centerY() + 30f)
                        UiKit.square(c, "minus", bm.centerX(), bm.centerY(), 60f, if (en && v > lo) UiKit.Button.UP else UiKit.Button.DISABLED)
                        UiKit.square(c, "plus", bp.centerX(), bp.centerY(), 60f, if (en && v < hi) UiKit.Button.UP else UiKit.Button.DISABLED)
                        val tr = RectF(bm.right + 16f, box.centerY() - 9f, bp.left - 16f, box.centerY() + 9f)
                        pFill.color = Color.argb(200, 20, 16, 12); c.drawRoundRect(tr, 9f, 9f, pFill)
                        val k2 = if (hi > lo) ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) else 0f
                        pFill.color = if (en) Theme.gold else Theme.dim
                        c.drawRoundRect(RectF(tr.left, tr.top, tr.left + tr.width() * k2, tr.bottom), 9f, 9f, pFill)
                        c.drawText(it.optString("txt", v.toString()), tr.centerX(), tr.top - 8f, UiKit.numberPaint(24f, if (en) Color.WHITE else Theme.dim).apply { textAlign = Paint.Align.CENTER })
                        val cm = slotOf(it, "minus"); val cp = slotOf(it, "plus")
                        if (en) { hits.add(bm to hitOf("qt ${it.optString("id")} -", cm)); hits.add(bp to hitOf("qt ${it.optString("id")} +", cp)) }
                    }
                    "color" -> {
                        c.drawText(fit(lp, it.optString("t"), box.width() * 0.5f), box.left + 4f, ty, lp)
                        val rgb = it.optJSONArray("rgb")
                        val sw = RectF(box.left + box.width() * 0.52f, box.centerY() - 18f, box.left + box.width() * 0.52f + 90f, box.centerY() + 18f)
                        pFill.color = Color.BLACK; c.drawRect(RectF(sw.left - 2f, sw.top - 2f, sw.right + 2f, sw.bottom + 2f), pFill)
                        pFill.color = if (rgb != null) Color.rgb((rgb.optDouble(0) * 255).toInt(), (rgb.optDouble(1) * 255).toInt(), (rgb.optDouble(2) * 255).toInt()) else Color.GRAY
                        c.drawRect(sw, pFill)
                        c.drawText("Colours: on the top screen", sw.right + 20f, ty, UiKit.bodyPaint(21f, Theme.dim))
                        hits.add(box to "why:Colours are chosen on the top screen (On the top screen, below)")
                    }
                    "drop" -> {
                        c.drawText(fit(lp, it.optString("t"), box.width() * 0.42f), box.left + 4f, ty, lp)
                        val dd = RectF(box.left + box.width() * 0.46f, box.centerY() - 30f, box.right - 8f, box.centerY() + 30f)
                        UiKit.choice(c, dd, fit(UiKit.bodyPaint(24f, Color.WHITE), it.optString("v") + "  v", dd.width() - 30f), false, en, textSize = 24f)
                        focusBox = dd
                        if (en) hits.add(dd to hitOf("qt ${it.optString("id")}", sl))
                    }
                    "edit" -> {
                        c.drawText(fit(lp, it.optString("t"), box.width() * 0.5f), box.left + 4f, ty, lp)
                        val eb = RectF(box.left + box.width() * 0.52f, box.centerY() - 28f, box.right - 8f, box.centerY() + 28f)
                        formBox(c, eb, it.optString("v"), "", "qt ${it.optString("id")}", if (en) sl else -1, "qt${it.optString("id")}", size = 24f, digits = true)
                        focusBox = RectF()
                    }
                    "button" -> {
                        val bb = RectF(box.left, box.top + 6f, box.right, box.bottom - 6f)
                        UiKit.choice(c, bb, fit(UiKit.bodyPaint(25f, Color.WHITE), it.optString("t"), bb.width() - 30f), it.optBoolean("on"), en, textSize = 25f)
                        // the chosen one PLAINLY (test50): a gold rim and a soft glow round it
                        if (it.optBoolean("on")) {
                            val sw0 = pStroke.strokeWidth; val sc0 = pStroke.color
                            pStroke.color = Theme.gold; pStroke.strokeWidth = 4f
                            c.drawRoundRect(RectF(bb.left - 3f, bb.top - 3f, bb.right + 3f, bb.bottom + 3f), 10f, 10f, pStroke)
                            pStroke.strokeWidth = sw0; pStroke.color = sc0
                            pFill.color = Color.argb(46, Color.red(Theme.gold), Color.green(Theme.gold), Color.blue(Theme.gold))
                            c.drawRoundRect(RectF(bb.left - 8f, bb.top - 8f, bb.right + 8f, bb.bottom + 8f), 14f, 14f, pFill)
                        }
                        val ft = fxClock() - qtFlashAt
                        if (qtFlashId == it.optString("id") && ft in 0 until 700L) {
                            pFill.color = Color.argb((200 * (1f - ft / 700f)).toInt(), 255, 220, 120)
                            c.drawRoundRect(bb, 10f, 10f, pFill); postInvalidateOnAnimation()
                        }
                        focusBox = bb
                        if (en) hits.add(bb to hitOf("qt ${it.optString("id")}", sl))
                    }
                    else -> {   // text
                        val lay = layout(it.optString("t"), UiKit.bodyPaint(23f, Theme.dim), box.width().toInt(), 3f)
                        drawLayout(c, lay, box.left + 4f, box.top + 4f)
                        focusBox = RectF()
                    }
                }
                if (cur == id && !focusBox.isEmpty) {
                    UiKit.focus(c, focusBox)
                    val gs = 40f                       // the cursor's A, as Settings' cursor wears it (test50: "A on the cursor")
                    UiKit.pad(c, UiKit.okGlyph(), focusBox.left - 0.35f * gs + gs / 2f, focusBox.top - 0.35f * gs + gs / 2f, gs)
                }
            }
        }
        c.restore()
        if (contentA > view.height() + 2f) UiKit.scrollBar(c, RectF(inner.right - 14f, view.top, inner.right - 2f, view.bottom),
            scrollA / contentA, min(1f, (scrollA + view.height()) / contentA))
        // ---- the strip: Reset; the step's arrows and number; Finish on the last; its window back on the top screen
        // test50 (the user: "The arrows left and right are stupid, I need a next and back button to make it clear"): labelled game
        // buttons, Back (B, and LB) and Next (RB; Finish on the last step), the step's number between them
        val bh = strip.height() - 30f
        if (q.optBoolean("reset")) {
            val rb = RectF(strip.left + 8f, cy - bh / 2f, strip.left + 8f + 190f, cy + bh / 2f)
            stripButton(c, rb, "Reset", hitOf("qtreset", col("reset")), true, false, null, id = "qtreset")
        }
        val n = q.optInt("n", 0); val i = q.optInt("i", 0)
        val setup = q.optString("kind") == "setup"
        val mid = strip.centerX() - 40f
        val bw = 230f
        if (setup) {
            val canB = q.optBoolean("back")
            stripButton(c, RectF(mid - 60f - bw, cy - bh / 2f, mid - 60f, cy + bh / 2f), "Back", if (canB) hitOf("qtgo back", col("back"))
                else "why:This is the first step", canB, false, UiKit.backGlyph(), id = "qtgo back")
            c.drawText("$i / $n", mid, cy + 11f, UiKit.numberPaint(28f, Color.WHITE).apply { textAlign = Paint.Align.CENTER })
        }
        val nx = if (setup) RectF(mid + 60f, cy - bh / 2f, mid + 60f + bw, cy + bh / 2f) else RectF(mid - bw / 2f, cy - bh / 2f, mid + bw / 2f, cy + bh / 2f)
        if (q.optBoolean("finish")) {
            stripButton(c, nx, q.optString("finishWord", "Finish"), hitOf("qtfinish", col("finish")), true, false,
                if (setup) UiKit.shoulderGlyph(true) else null, id = "qtfinish")
        } else {
            val canN = q.optBoolean("next")
            stripButton(c, nx, "Next", if (canN) hitOf("qtgo next", col("next")) else "why:No next step", canN, false, UiKit.shoulderGlyph(true), id = "qtgo next")
        }
        if (setup) {
            val right = strip.right - 8f
            stripButton(c, RectF(right - 290f, cy - bh / 2f, right, cy + bh / 2f), "On the top screen", hitOf("qttop", col("top")), true, false,
                null, id = "qttop")
        }
        // a press's line (test50: "World Trail chosen"), just over the strip
        resultLine(c, x0, x1, strip.top - 26f, now)
        UiKit.windowFrame(c, r, when { q.optString("kind") == "intro" -> "QuestTrail: Shared Trail"; q.optBoolean("browse") -> "QuestTrail settings"; else -> "QuestTrail setup" },
            UiKit.tex("portrait_quest"), Theme.gold, closeDown = pressed == "x", closeB = 0)
        hits.add(UiKit.windowClose(r) to hitOf("qtclose", col("close")))
        // QuestTrail's own dropdown menu, open: its choices over the page (a pick presses its row; B closes it)
        q.optJSONObject("menu")?.let { m ->
            hits.clear()
            UiKit.cover(c, r)
            val ch = arr(m, "items")
            val w = 640f; val rowH = 76f
            val d = RectF(r.centerX() - w / 2f, r.centerY() - (ch.size * rowH + 120f) / 2f, r.centerX() + w / 2f, r.centerY() + (ch.size * rowH + 120f) / 2f)
            val inn = UiKit.dialog(c, d)
            c.drawText(m.optString("t", ""), inn.centerX(), inn.top + 40f, UiKit.bodyPaint(30f, Theme.gold).apply { textAlign = Paint.Align.CENTER })
            for ((k, it) in ch.withIndex()) {
                val rr = RectF(inn.left + 20f, inn.top + 64f + k * rowH, inn.right - 20f, inn.top + 64f + k * rowH + rowH - 10f)
                UiKit.choice(c, rr, it.optString("t"), it.optBoolean("on"), true, textSize = 26f)
                hits.add(rr to hitOf("qt ${it.optString("id")}", slotOf(it)))
                if (cur == "qt ${it.optString("id")}") UiKit.focus(c, rr)
            }
        }
    }

    private fun petitionWindow(c: Canvas, r: RectF, p: JSONObject) {
        val inner = UiKit.windowGround(c, r)
        val strip = UiKit.strip(c, inner)
        page(c, inner, strip.top - 6f)
        val ink = UiKit.inkPaint(27f)
        val dim = UiKit.inkPaint(25f).apply { color = Theme.parchmentInkDim }
        val guild = p.optString("kind", "guild") == "guild"
        val lx = inner.left + 44f
        var y = r.top + UiKit.WINDOW_UNDER_RING
        c.drawText(if (guild) "Guild Charter" else "Charter", lx, y + 46f, UiKit.inkPaint(48f, heading = true)); y += 74f
        c.drawText((if (guild) "Guild Name: " else "") + p.optString("title", ""), lx, y + 30f, ink); y += 44f
        c.drawText((if (guild) "Guild Master: " else "") + p.optString("master", ""), lx, y + 30f, ink); y += 62f
        val names = p.optJSONArray("names")
        val n = names?.length() ?: 0
        c.drawText("Signatures  ${p.optInt("signed")} / ${max(p.optInt("min", n), n)}", lx, y + 30f, UiKit.inkPaint(32f, heading = true)); y += 50f
        val colW = (inner.width() - 120f) / 2f
        for (i in 0 until n) {
            val nm = names!!.optString(i, "")
            c.drawText("${i + 1}.  " + nm.ifEmpty { "Not yet signed" }, lx + (i % 2) * colW, y + (i / 2) * 40f + 30f, if (nm.isEmpty()) dim else ink)
        }
        y += ((n + 1) / 2) * 40f + 26f
        val instr = p.optString("instructions", "")
        if (instr.isNotEmpty()) drawLayout(c, layout(instr, dim, (inner.width() - 100f).toInt()), lx, y)
        // the strip: the game's own buttons, the main one on the right
        val btn = p.optJSONObject("btn") ?: JSONObject()
        val cells = p.optJSONObject("cells")
        val bh = strip.height() - 30f
        var right = strip.right - 8f
        for ((key, label, id) in listOf(Triple("sign", "Sign Charter", "petsign"), Triple("offer", "Request Signature", "petoffer"),
                                        Triple("rename", "Rename Guild", "petrename"))) {
            if (!btn.has(key)) continue
            val w = if (key == "offer") 400f else 330f
            val rr = RectF(right - w, strip.centerY() - bh / 2f + 2f, right, strip.centerY() + bh / 2f + 2f)
            val cell = cells?.optInt(key, -1) ?: -1
            val hit = if (cell >= 0) "pet:$cell" else "why:Answer with the controller"
            stripButton(c, rr, label, hit, btn.optBoolean(key), false, glyph = "rev_a".takeIf { cur == id }, id = id)
            right = rr.left - 16f
        }
        UiKit.windowFrame(c, r, p.optJSONObject("npc")?.optString("name", "")?.ifEmpty { null } ?: "Guild Charter",
            UiKit.tex("portrait_quest"), Theme.gold, closeDown = pressed == "x", closeB = closeB())
        hits.add(UiKit.windowClose(r) to "x")
    }

    private fun stableWindow(c: Canvas, r: RectF, st: JSONObject, now: Long) {
        val inner = UiKit.windowGround(c, r, marble = true)
        val act = st.optJSONObject("act")
        val purse = st.optLong("money", 0)
        val pets = arr(st, "pets")
        val at = (cur ?: selected)?.takeIf { it.startsWith("pet ") }?.substringAfter("pet ")?.toIntOrNull()
        pets.firstOrNull { it.optInt("i") == at && it.has("name") }?.let { stShown = it }
        if (stShown != null && pets.none { it.optString("name") == stShown!!.optString("name") && it.has("name") }) stShown = null
        if (stShown == null) stShown = pets.firstOrNull { it.has("name") }

        // ---- the stage: the chosen pet
        val stage = RectF(inner.left + 4f, r.top + 100f, inner.right - 4f, r.top + 600f)
        val pet = stShown
        UiKit.petStage(c, stage, pet?.let { str(it, "spec") }, pet?.let { Icons.get(it.optInt("icon", 0).takeIf { n -> n > 0 }) },
            stage.centerX(), stage.bottom - 100f, 170f)
        if (pet != null) {
            val name = pet.optString("name"); val fam = pet.optString("family")
            val head = (if (name == fam) "" else "$name ") + "Level ${pet.optInt("level")} $fam"
            val hp = UiKit.bodyPaint(32f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
            c.drawText(head, stage.centerX(), stage.top + 52f, hp)
            str(pet, "loyalty")?.let { c.drawText(it, stage.centerX(), stage.top + 92f, UiKit.bodyPaint(24f, Theme.gold).apply { textAlign = Paint.Align.CENTER }) }
            UiKit.loyaltyRing(c, stage.right - 70f, stage.top + 66f, 92f, pet.optInt("loyaltyLevel", 0))
            val need = pet.optInt("xpNeed", 0)
            if (need > 0) {
                val xp = pet.optInt("xp", 0)
                UiKit.skillBar(c, RectF(stage.centerX() - 300f, stage.bottom - 64f, stage.centerX() + 300f, stage.bottom - 20f),
                    xp.toFloat() / need, "$xp / $need", 24f, Color.rgb(130, 40, 150))
            }
        }

        // ---- the slots: Current Pet, then the Stabled Pets
        val sz = 156f; val sy = stage.bottom + 70f
        val n = pets.size
        val stabled = max(0, n - 1)
        val groupW = sz + 150f + stabled * sz + max(0, stabled - 1) * 38f
        val x0 = stage.centerX() - groupW / 2f
        fun xOf(i: Int) = if (i == 0) x0 else x0 + sz + 150f + (i - 1) * (sz + 38f)
        val lp = UiKit.bodyPaint(24f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
        c.drawText("Current Pet", x0 + sz / 2f, sy - 26f, lp)
        if (stabled > 0) c.drawText("Stabled Pets", (xOf(1) + xOf(n - 1) + sz) / 2f, sy - 26f, lp)
        var dragRect: RectF? = null
        for ((k, pt) in pets.withIndex()) {
            val x = xOf(k)
            val i = pt.optInt("i")
            val rr = RectF(x, sy, x + sz, sy + sz)
            UiKit.petSlot(c, x, sy, sz, Icons.get(pt.optInt("icon", 0).takeIf { it > 0 }), open = pt.optBoolean("open", true),
                dim = i == stInHand || i == petDrag && dragging)
            hits.add(rr to "sel:pet $i")
            if (cur == "pet $i" || (cur == null && selected == "pet $i")) { UiKit.focus(c, rr); dragRect = rr }
        }
        // the pet in the hand rides the cursor (or the finger)
        val handPet = pets.firstOrNull { it.optInt("i") == (if (petDrag > 0 && dragging) petDrag else stInHand) }
        if (handPet != null) {
            val ic = Icons.get(handPet.optInt("icon", 0).takeIf { it > 0 })
            if (petDrag > 0 && dragging) UiKit.slot(c, ic, petDragX - 32f, petDragY - 32f, 64f)
            else dragRect?.let { UiKit.slot(c, ic, it.left - 26f, it.top - 26f, 64f) }
        }

        // ---- the strip: money; purchase a slot; the hints
        val strip = UiKit.strip(c, inner)
        val cy = strip.centerY() + 2f
        c.drawText("Your money", inner.left + 24f, cy - 14f, UiKit.bodyPaint(22f, Theme.dim))
        UiKit.money(c, purse, inner.left + 24f, cy + 20f, 30f)
        val bx = inner.right - 8f
        val slots = st.optInt("slots", 0); val maxSlots = st.optInt("maxSlots", 99)
        val cost = st.optLong("cost", 0)
        val canBuyMore = slots < maxSlots
        if (canBuyMore) {
            c.drawText("Do you wish to purchase another stable slot?", inner.left + 250f, cy - 14f, UiKit.bodyPaint(22f, Theme.text))
            c.drawText("Cost:", inner.left + 250f, cy + 28f, UiKit.bodyPaint(26f, Theme.gold))
            UiKit.money(c, cost, inner.left + 330f, cy + 20f, 28f, if (cost <= purse) Theme.text else Theme.unusable)
            val pb = RectF(bx - 290f, cy - 55f, bx, cy + 56f)
            UiKit.button(c, pb, "Purchase", "rev_x", if (cost <= purse) UiKit.Button.UP else UiKit.Button.DISABLED)
            act?.let { slotOf(it, "box") }?.let { if (it >= 0 && cost <= purse) hits.add(pb to "act:stablebox|$it") }
        }
        val hr = if (canBuyMore) bx - 310f else bx - 10f
        if (stInHand > 0) UiKit.hints(c, hr, cy, "rev_a" to "Put here", "rev_b" to "Cancel")
        else UiKit.hints(c, hr, cy, "rev_a" to "Move")

        // ---- the game's own purchase box (CONFIRM_BUY_STABLE_SLOT)
        if (stBox) {
            UiKit.cover(c, r)
            val box = RectF(r.centerX() - 520f, 215f, r.centerX() + 520f, 215f + 390f)
            val ib = UiKit.dialog(c, box)
            val words = "Are you sure you wish to purchase a new stable slot for the following amount?"
            val tp = TextPaint(UiKit.bodyPaint(30f, Color.WHITE))
            val lay = StaticLayout.Builder.obtain(words, 0, words.length, tp, (ib.width() - 80f).toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            c.save(); c.translate(ib.left + 40f, ib.top + 30f); lay.draw(c); c.restore()
            val mw = UiKit.moneyWidth(cost, 34f)
            UiKit.money(c, cost, ib.centerX() - mw / 2f, ib.top + 60f + lay.height + 20f, 34f, Color.WHITE)
            val yb = ib.bottom - 111f - 24f
            val yes = RectF(ib.centerX() - 400f, yb, ib.centerX() - 20f, yb + 111f)
            val no = RectF(ib.centerX() + 20f, yb, ib.centerX() + 400f, yb + 111f)
            UiKit.button(c, yes, "Yes", "rev_a", kitState("act:stableslot", if (cost <= purse) UiKit.Button.UP else UiKit.Button.DISABLED))
            UiKit.button(c, no, "No", "rev_b", kitState("act:stablebox"))
            act?.let { slotOf(it, "buy") }?.let { if (it >= 0 && cost <= purse) hits.add(yes to "act:stableslot|$it") }
            act?.let { slotOf(it, "box") }?.let { if (it >= 0) hits.add(no to "act:stablebox|$it") }
        }

        UiKit.windowFrame(c, r, st.optJSONObject("npc")?.optString("name", "") ?: "Stable Master", UiKit.tex("mm_stablemaster"),
            Theme.gold, closeDown = pressed == "x", closeB = closeB())
    }

    // ---- the MAILBOX, step 1: the inbox (UI Art's MAIL_SPEC, art-kit 2dea35e; user 2026-09-30 "Yes, build it")
    // The letters on the left; the game's paper on the right: the header of the letter under the cursor, or the
    // open letter in full (its body, the attachments, then its money). A C.O.D. letter is the game's to take.

    private fun mailWindow(c: Canvas, r: RectF, ml: JSONObject, now: Long) {
        val inner = UiKit.windowGround(c, r, marble = true)
        val purse = ml.optLong("money", 0)
        val letters = arr(ml, "mails")
        val open = ml.optJSONObject("open")
        val openI = open?.optInt("i") ?: -1
        val ty = r.top + 94f; val th = 108f
        // THE GAME'S TWO TABS (f-mailbox-look): the room's tab row, the tabs open on landing (the add-on starts the cursor on the open
        // tab): the controller's gold tab with the game's gold arrows beside it (D-pad Left / Right switch), elsewhere LB / RB in the
        // gaps. Touch = the controller's A on them.
        val sendTab = ml.optString("tab") == "send"
        // the user (15 Oct, on the render): "the L and R need to be on the outsides of both tabs always there": [LB] Inbox | Send Mail [RB],
        // the glyphs in the chosen button set (UiKit.pad / shoulderGlyph), the narrow gap between the tabs left to the cursor's arrows
        // the purse sits at the right; the row of tabs and glyphs stops short of its words
        val purseLeft = inner.right - 16f - UiKit.moneyWidth(purse, 30f) - 14f - UiKit.bodyPaint(22f, Theme.dim).measureText("Your money")
        val tabs = UiKit.tabRow(2, 176f + MAIL_GLYPH_W, purseLeft - MAIL_GLYPH_W - 16f, ty + 6f, th - 6f, 220f, gap = 44f)
        val names = listOf("Inbox" to "mailtab inbox", "Send Mail" to "mailtab send")
        val openIdx = if (sendTab) 1 else 0
        for ((k, nm) in names.withIndex()) UiKit.tab(c, tabs[k], nm.first, k == openIdx, cursor = cur == nm.second)
        UiKit.tabEnds(c, tabs[0], tabs[1], MAIL_GLYPH_W)
        val onTabs = cur == "mailtab inbox" || cur == "mailtab send"
        if (onTabs) { val ci = if (cur == "mailtab send") 1 else 0; UiKit.moveArrows(c, tabs[ci], left = ci > 0, right = ci < 1, words = false) }
        ml.optJSONObject("act")?.let { a ->
            slotOf(a, "tabInbox").takeIf { it >= 0 }?.let { hits.add(tabs[0] to "act:mailtab inbox|$it") }
            slotOf(a, "tabSend").takeIf { it >= 0 }?.let { hits.add(tabs[1] to "act:mailtab send|$it") }
        }
        pFill.color = Color.rgb(96, 80, 50)
        c.drawRect(inner.left - 6f, ty + th, inner.right + 6f, ty + th + 2f, pFill)
        // the purse, right of the tabs (the words before the coins, never over them)
        val mw = UiKit.moneyWidth(purse, 30f)
        c.drawText("Your money", inner.right - 16f - mw - 14f, ty + th / 2f + 14f,
            UiKit.bodyPaint(22f, Theme.dim).apply { textAlign = Paint.Align.RIGHT })
        UiKit.money(c, purse, inner.right - 16f, ty + th / 2f + 6f, 30f, fromRight = true)
        if (sendTab) {
            ml.optJSONObject("send")?.let { sendPage(c, r, inner, ty + th + 8f, it, ml.optJSONObject("act"), purse, now) }
            UiKit.windowFrame(c, r, "Mailbox", UiKit.tex("mm_mailbox"), Theme.gold, closeDown = pressed == "x", closeB = closeB())
            ml.optJSONObject("send")?.let { sd -> if (sd.has("bags")) bagPicker(c, r, sd, ml.optJSONObject("act")) }
            ml.optJSONObject("send")?.let { sd -> if (sd.optString("status") == "confirm") confirmOnTop(c, r, ml.optJSONObject("act")) }
            return
        }
        val ly0 = ty + th + 8f; val ly1 = inner.bottom - UiKit.STRIP - 4f
        val cw = 580f

        // which letter the paper shows: the cursor's (or the finger's) letter, else the open one, else the first
        val pointed = (cur ?: selected)?.takeIf { it.startsWith("mail ") }?.substringAfter("mail ")?.toIntOrNull()
        val inLetter = cur?.let { it.startsWith("mailtake ") || it.startsWith("mailmoney ") } == true
        val showI = when { inLetter -> openI; pointed != null -> pointed; openI > 0 -> openI; else -> letters.firstOrNull()?.optInt("i") ?: -1 }
        val hdr = letters.firstOrNull { it.optInt("i") == showI }

        // ---- THE INBOX (f-mailbox-look): the game's InboxFrame, a page of seven letters (INBOXITEMS_TO_DISPLAY) on its parchment,
        // "Page n of m" between Prev and Next under them. The page follows the controller's letter; a finger pages with the arrows.
        val sheet = RectF(inner.left + 4f, ly0, inner.left + 4f + cw, ly1)
        UiKit.mailSheet(c, sheet)
        val pages = max(1, (letters.size + PER_PAGE - 1) / PER_PAGE)
        val ci = letters.indexOfFirst { "mail ${it.optInt("i")}" == cur || "mail ${it.optInt("i")}" == selected && cur == null }
        if (ci >= 0 && cur != mailPageCur) { mailPageCur = cur; mailPage = ci / PER_PAGE }
        mailPage = mailPage.coerceIn(0, pages - 1)
        val pagerH = 78f
        val rowH = (sheet.height() - 20f - pagerH) / PER_PAGE
        for (k in mailPage * PER_PAGE until min(letters.size, (mailPage + 1) * PER_PAGE)) {
            val l = letters[k]
            val i = l.optInt("i")
            val y = sheet.top + 12f + (k - mailPage * PER_PAGE) * rowH
            val row = RectF(sheet.left + 16f, y, sheet.right - 16f, y + rowH - 6f)
            val opened = i == openI
            UiKit.mailItem(c, row, Icons.get(l.optInt("icon", 0).takeIf { it > 0 }), l.optString("sender", ""), l.optString("subject", ""),
                l.optDouble("days", 0.0).toFloat(), l.optBoolean("read") || opened, l.optInt("cod", 0) > 0,
                if (l.optInt("items", 0) > 0) l.optInt("qty1", 0) else 0, opened = opened)
            hits.add(row to "sel:mail $i")
            if (cur == "mail $i" || (cur == null && selected == "mail $i")) UiKit.focus(c, row)
        }
        if (letters.isEmpty()) c.drawText("No mail", sheet.centerX(), sheet.top + 80f,
            UiKit.bodyPaint(30f, Theme.gold).apply { textAlign = Paint.Align.CENTER })
        // Prev / Next and the page: the game's own arrows
        val py = sheet.bottom - pagerH / 2f - 6f
        val prev = RectF(sheet.left + 18f, py - 32f, sheet.left + 82f, py + 32f)
        val next = RectF(sheet.right - 82f, py - 32f, sheet.right - 18f, py + 32f)
        UiKit.tex(if (mailPage > 0) "page_prev" else "page_prev_off")?.let { c.drawBitmap(it, null, prev, pArt) }
        UiKit.tex(if (mailPage < pages - 1) "page_next" else "page_next_off")?.let { c.drawBitmap(it, null, next, pArt) }
        val pp = UiKit.bodyPaint(24f, Theme.gold)
        c.drawText("Prev", prev.right + 8f, py + 9f, pp)
        c.drawText("Next", next.left - 8f, py + 9f, UiKit.bodyPaint(24f, Theme.gold).apply { textAlign = Paint.Align.RIGHT })
        c.drawText("Page ${mailPage + 1} of $pages", sheet.centerX(), py + 9f, UiKit.bodyPaint(24f, Theme.gold).apply { textAlign = Paint.Align.CENTER })
        if (mailPage > 0) hits.add(prev to "mp:-")
        if (mailPage < pages - 1) hits.add(next to "mp:+")
        ml.optInt("more", 0).takeIf { it > 0 }?.let {
            c.drawText("$it more in the game", sheet.centerX(), sheet.bottom - 6f, UiKit.bodyPaint(20f, Color.rgb(60, 40, 20)).apply { textAlign = Paint.Align.CENTER })
        }

        // ---- the letter: the game's paper beside the inbox
        val w = UiKit.letterPaper(c, RectF(inner.left + 4f + cw + 18f, ly0, inner.right - 6f, ly1))
        val bx = inner.right - 8f
        val strip = UiKit.strip(c, inner)
        val cy = strip.centerY() + 2f
        val busy = pendingCmd != null
        val es = entries()
        fun entry(cmd: String) = es.firstOrNull { it.cmd == cmd }
        if (hdr != null) {
            val lab = UiKit.inkPaint(26f)
            val gold = UiKit.inkPaint(26f).apply { color = Color.rgb(120, 66, 10) }
            val lw = w.width() - lab.measureText("Subject:") - 14f
            c.drawText("From:", w.left, w.top + 30f, lab)
            c.drawText(fit(gold, hdr.optString("sender", ""), lw), w.left + lab.measureText("From:") + 14f, w.top + 30f, gold)
            c.drawText("Subject:", w.left, w.top + 70f, lab)
            c.drawText(fit(gold, hdr.optString("subject", ""), lw), w.left + lab.measureText("Subject:") + 14f, w.top + 70f, gold)
            UiKit.divider(c, w.left, w.top + 92f, w.right, w.top + 92f)
        }
        val cod = (hdr?.optLong("cod", 0) ?: 0L)
        if (hdr != null && showI == openI && open != null) {
            // the open letter in full
            val text = prose(open.optString("body", ""))
            var ay = w.top + 110f
            if (text.isNotEmpty()) {
                val lay = StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(UiKit.inkPaint(26f)), w.width().toInt())
                    .setLineSpacing(4f, 1f).build()
                c.save(); c.clipRect(w.left, ay, w.right, w.bottom - 260f)
                drawLayout(c, lay, w.left, ay); c.restore()
                ay += min(lay.height.toFloat(), w.bottom - 260f - ay)
            }
            val items = arr(open, "items")
            val money = hdr.optLong("money", 0)
            if (items.isNotEmpty() || money > 0) {
                ay += 34f
                c.drawText("Take Attachments:", w.left, ay, UiKit.inkPaint(24f))
                ay += 16f
                val sz = 96f
                var ax = w.left + 18f
                for (it in items) {
                    val cmd = "mailtake $openI ${it.optInt("a")}"
                    val rr = RectF(ax, ay + 18f, ax + sz, ay + 18f + sz)
                    UiKit.slot(c, itemIcon(it), ax, ay + 18f, sz, count = it.optInt("count", 0))
                    hits.add(rr to (if (cod > 0) "why:This letter costs money (C.O.D.): pay it in the game" else "do:$cmd"))
                    if (cur == cmd) UiKit.focus(c, rr)
                    ax += sz + 52f
                }
                if (money > 0) {
                    val cmd = "mailmoney $openI"
                    val rr = RectF(ax, ay + 18f, ax + sz, ay + 18f + sz)
                    UiKit.slot(c, UiKit.tex("icon_junk"), ax, ay + 18f, sz)
                    val mw = UiKit.moneyWidth(money, 24f)
                    UiKit.money(c, money, ax + sz / 2f - mw / 2f, ay + 18f + sz + 36f, 24f, Theme.parchmentInk, shadow = false)
                    hits.add(rr to "do:$cmd")
                    if (cur == cmd) UiKit.focus(c, rr)
                }
                if (cod > 0) {
                    val cp = UiKit.inkPaint(24f)
                    val cyy = ay + 18f + sz + 70f
                    c.drawText("Cash on Delivery Amount:", w.left, cyy, cp)
                    UiKit.money(c, cod, w.left + cp.measureText("Cash on Delivery Amount:") + 12f, cyy - 8f, 24f, Theme.parchmentInk, shadow = false)
                }
            }
            // the strip: Take (the attachment under the cursor, else the first) and Take All
            val takeCmd = cur?.takeIf { it.startsWith("mailtake ") || it.startsWith("mailmoney ") }
                ?: items.firstOrNull()?.let { "mailtake $openI ${it.optInt("a")}" }
                ?: if (money > 0) "mailmoney $openI" else null
            val tb = RectF(bx - 250f, cy - 55f, bx, cy + 56f)
            if (cod > 0) {
                c.drawText("Pay C.O.D. in the game", bx - 520f, cy + 9f, UiKit.bodyPaint(24f, Theme.dim))
                UiKit.button(c, tb, "Take", "rev_a", UiKit.Button.DISABLED)
            } else {
                val te = takeCmd?.let { entry(it) }
                stripButton(c, tb, "Take", "go", te != null && te.slot >= 0, busy && pendingCmd == takeCmd, "rev_a")
                goCmd = te
                if (te == null) goWhy = "Nothing to take"
                val ae = entry("mailall $openI")
                if (ae != null && (items.isNotEmpty() || money > 0)) {
                    val ab = RectF(bx - 530f, cy - 55f, bx - 270f, cy + 56f)
                    stripButton(c, ab, "Take All", "do:mailall $openI", ae.slot >= 0, busy && pendingCmd == ae.cmd, "rev_x")
                }
            }
            resultLine(c, inner.left + 24f, bx - 560f, cy, now)
        } else {
            // a letter not opened: only what the inbox knows; A (or Open) opens it
            if (hdr != null) {
                val oe = entry("mail $showI")
                val ob = RectF(bx - 250f, cy - 55f, bx, cy + 56f)
                stripButton(c, ob, "Open", "go", oe != null && oe.slot >= 0, busy && pendingCmd == oe?.cmd, "rev_a")
                goCmd = oe
            } else {
                c.drawText("No mail", w.left, w.top + 30f, UiKit.inkPaint(26f))
            }
            resultLine(c, inner.left + 24f, bx - 280f, cy, now)
        }

        UiKit.windowFrame(c, r, "Mailbox", UiKit.tex("mm_mailbox"), Theme.gold, closeDown = pressed == "x", closeB = closeB())
    }

    /** the inbox's page (the game's seven a page) and the cursor it was last moved for */
    private var mailPage = 0
    private var mailPageCur: String? = null

    // ---- SEND MAIL (f-inbox-send, 1.0.8): the game's SendMailFrame, read from the client. To and Subject on their boxes, the letter on
    // the game's paper, the money (Send Money or C.O.D., gold / silver / copper), the postage, up to 12 attachments; Clear and Send on
    // the strip. A box pressed (touch, or the controller's A) opens the Chat keyboard on it; what is typed shows in the box as it goes.

    /** a box of the form: its words (or the hint, dim), the caret while typed; touch = the add-on's cell; the cursor's gold box */
    private fun formBox(c: Canvas, box: RectF, value: String, hint: String, cmd: String, col: Int, field: String,
                        size: Float = 28f, digits: Boolean = false) {
        UiKit.inputBox(c, box)
        val typing = kbField == field
        val shown = typedOr(field, value)
        val p = if (shown.isEmpty()) UiKit.bodyPaint(size, Theme.dim) else if (digits) UiKit.numberPaint(size, Color.WHITE) else UiKit.bodyPaint(size, Color.WHITE)
        val words = if (shown.isEmpty()) hint else shown
        val x = box.left + 18f
        val maxW = box.width() - 36f
        // the end of what is typed stays in view (the game's own box scrolls with the caret)
        val drawn = if (typing && p.measureText(words) > maxW) "…" + words.takeLast(p.breakText(words.reversed(), true, maxW - 24f, null)) else fit(p, words, maxW)
        val by = box.centerY() - (p.ascent() + p.descent()) / 2f
        c.drawText(drawn, x, by, p)
        if (typing) {
            if ((System.currentTimeMillis() / 500L) % 2L == 0L) {
                val cx = x + (if (shown.isEmpty()) 0f else p.measureText(drawn)) + 2f
                pFill.color = Theme.gold
                c.drawRect(cx, box.top + 14f, cx + 3f, box.bottom - 14f, pFill)
            }
            postInvalidateDelayed(500L)                 // the caret blinks while the box is typed
        }
        if (col >= 0) hits.add(box to "act:$cmd|$col")
        if (cur == cmd || typing) UiKit.focus(c, box)
    }

    private fun sendPage(c: Canvas, r: RectF, inner: RectF, top: Float, sd: JSONObject, act: JSONObject?, purse: Long, now: Long) {
        fun col(k: String) = act?.let { slotOf(it, k) } ?: -1
        val strip = UiKit.strip(c, inner)
        val cy = strip.centerY() + 2f
        val bx = inner.right - 8f
        val bot = inner.bottom - UiKit.STRIP - 8f
        val lab = UiKit.bodyPaint(26f, Theme.gold)
        // THE GAME'S SEND MAIL TAB (f-mailbox-look; Blizzard_MailFrame's SendMailFrame, its arrangement): To with the Postage at the
        // right, Subject under it, the letter on the game's stationery, the game's gold rule, the attachments (seven to a row, as
        // ATTACHMENTS_PER_ROW_SEND) with the money box beside them (Send Money / C.O.D., the gold / silver / copper boxes), Cancel and
        // Send on the strip.
        val x0 = inner.left + 16f; val x1 = inner.right - 16f
        val labW = 130f
        val rowH = 62f
        // ---- To, and the Postage
        c.drawText("To:", x0, top + 8f + rowH / 2f + 9f, lab)
        val toBox = RectF(x0 + labW, top + 8f, x0 + labW + 430f, top + 8f + rowH)
        formBox(c, toBox, sd.optString("to"), "Name", "mailedit to", col("edit_to"), "mailto")
        val price = sd.optLong("price", 0)
        val cod = sd.optBoolean("cod")
        val dear = price + (if (cod) 0L else sd.optLong("money", 0)) > purse
        val pw = UiKit.moneyWidth(price, 28f)
        UiKit.money(c, price, x1, toBox.centerY(), 28f, if (dear) Theme.unusable else Theme.text, fromRight = true)
        c.drawText("Postage:", x1 - pw - 14f, toBox.centerY() + 9f, UiKit.bodyPaint(26f, Theme.gold).apply { textAlign = Paint.Align.RIGHT })
        // ---- Subject
        val sy = top + 8f + rowH + 10f
        c.drawText("Subject:", x0, sy + rowH / 2f + 9f, lab)
        val auto = sd.optString("autoSubject", "")
        formBox(c, RectF(x0 + labW, sy, x1, sy + rowH), sd.optString("subject"), auto.ifEmpty { "Subject" },
            "mailedit subject", col("edit_subject"), "mailsubject")
        // ---- the attachments and the money (bottom up, so the letter takes what is left)
        val n = max(1, sd.optInt("max", 12))
        val per = 7
        val sz = 78f; val gap = 12f
        val gridRows = (n + per - 1) / per
        val gridH = gridRows * sz + (gridRows - 1) * gap
        val gy = bot - gridH - 4f
        val ruleY = gy - 20f
        // ---- the letter on the game's stationery
        val paper = RectF(x0, sy + rowH + 14f, x1, ruleY - 10f)
        val w = UiKit.letterPaper(c, paper)
        val body = typedOr("mailbody", sd.optString("body"))
        if (body.isEmpty()) c.drawText("Write your letter here", w.left, w.top + 30f, UiKit.inkPaint(26f).apply { color = Color.argb(140, 60, 40, 20) })
        else {
            val lay = StaticLayout.Builder.obtain(body, 0, body.length, TextPaint(UiKit.inkPaint(26f)), w.width().toInt()).setLineSpacing(4f, 1f).build()
            c.save(); c.clipRect(w); drawLayout(c, lay, w.left, w.top + 4f); c.restore()
        }
        if (kbField == "mailbody") postInvalidateDelayed(500L)
        col("edit_body").takeIf { it >= 0 }?.let { hits.add(paper to "act:mailedit body|$it") }
        if (cur == "mailedit body" || kbField == "mailbody") UiKit.focus(c, paper, onPaper = true)
        UiKit.divider(c, x0, ruleY, x1, ruleY)
        // the attachment slots: the game's empty slot, the item in it; the first empty one opens the bags
        val items = arr(sd, "items")
        val pc = col("pick")
        var placedPick = false
        for (k in 0 until n) {
            val x = x0 + (k % per) * (sz + gap)
            val y = gy + (k / per) * (sz + gap)
            val att = items.getOrNull(k)
            val rr = RectF(x, y, x + sz, y + sz)
            if (att != null) {
                UiKit.slot(c, itemIcon(att), x, y, sz, count = att.optInt("count", 0))
                val cmd = "maildetach ${att.optInt("k")}"
                val sl = slotOf(att)
                hits.add(rr to (if (sl >= 0) "act:$cmd|$sl" else "why:The game gave this no tap cell"))
                if (cur == cmd) UiKit.focus(c, rr)
            } else {
                UiKit.slot(c, null, x, y, sz)
                hits.add(rr to (if (pc >= 0) "act:mailpick on|$pc" else "why:The game gave this no tap cell"))
                if (!placedPick) {
                    placedPick = true
                    c.drawText("+", rr.centerX(), rr.centerY() + 15f, UiKit.bodyPaint(44f, Theme.gold).apply { textAlign = Paint.Align.CENTER })
                    if (cur == "mailpick on") UiKit.focus(c, rr)
                }
            }
        }
        // ---- the money box beside the attachments: the game's two radio buttons over its gold / silver / copper boxes
        val mx = x0 + per * (sz + gap) + 20f
        val hasItems = items.isNotEmpty()
        val radioY = gy + 22f
        val rSend = RectF(mx, radioY - 26f, mx + 210f, radioY + 26f)
        val rCod = RectF(mx + 220f, radioY - 26f, x1, radioY + 26f)
        UiKit.radio(c, rSend.left + 22f, radioY, 40f, !cod)
        c.drawText("Send Money", rSend.left + 50f, radioY + 9f, UiKit.bodyPaint(25f, Theme.gold))
        UiKit.radio(c, rCod.left + 22f, radioY, 40f, cod)
        c.drawText("C.O.D.", rCod.left + 50f, radioY + 9f, UiKit.bodyPaint(25f, if (hasItems) Theme.gold else Theme.dim))
        col("money").takeIf { it >= 0 }?.let { hits.add(rSend to "act:mailcod off|$it") }
        if (hasItems) col("cod").takeIf { it >= 0 }?.let { hits.add(rCod to "act:mailcod on|$it") } else hits.add(rCod to "why:C.O.D. needs an attachment")
        if (cur == "mailcod off") UiKit.focus(c, rSend)
        if (cur == "mailcod on") UiKit.focus(c, rCod)
        val boxY = gy + gridH - 64f
        c.drawText(if (cod) "C.O.D. Amount:" else "Amount to Send:", mx, boxY - 14f, UiKit.bodyPaint(24f, Theme.gold))
        val coinW = (x1 - mx - 2f * 10f) / 3f
        for ((i, f) in listOf("gold", "silver", "copper").withIndex()) {
            val b = RectF(mx + i * (coinW + 10f), boxY, mx + i * (coinW + 10f) + coinW, boxY + 60f)
            formBox(c, RectF(b.left, b.top, b.right - 38f, b.bottom), sd.optString(f), "0", "mailedit $f", col("edit_$f"), "mail$f", size = 26f, digits = true)
            UiKit.tex("coin_$f")?.let { coin -> c.drawBitmap(coin, null, RectF(b.right - 34f, b.centerY() - 15f, b.right - 4f, b.centerY() + 15f), pArt) }
        }

        // ---- the strip: why the letter cannot go yet (or the game's answer), Cancel, Send
        val why = if (sd.has("why")) sd.optString("why") else null
        val st = sd.optString("status", "")
        val busy = pendingCmd != null
        val sendB = RectF(bx - 250f, cy - 55f, bx, cy + 56f)
        val clearB = RectF(bx - 520f, cy - 55f, bx - 270f, cy + 56f)
        val sCol = col("send"); val cCol = col("clear")
        val sendOk = why == null && sCol >= 0 && st != "sending" && st != "confirm"
        stripButton(c, sendB, "Send", if (sendOk) "act:mailsend|$sCol" else "why:${why ?: "The letter is on its way"}", sendOk,
            busy && pendingCmd == "mailsend", "rev_a", id = "mailsend")
        stripButton(c, clearB, "Cancel", if (cCol >= 0) "act:mailclear|$cCol" else "why:Nothing to clear", cCol >= 0,
            busy && pendingCmd == "mailclear", null, id = "mailclear")
        val shown = resultLine(c, inner.left + 24f, bx - 560f, cy, now)
        if (!shown) {
            val (words2, colour) = when (st) {
                "sending" -> "Sending..." to Theme.dim
                "sent" -> "Your letter was sent" to Theme.gold
                "failed" -> (sd.optString("err").ifEmpty { "The game did not send it" }) to Theme.unusable
                else -> (why ?: "") to Theme.dim
            }
            if (words2.isNotEmpty()) c.drawText(fit(UiKit.bodyPaint(24f, colour), words2, bx - 560f - inner.left - 24f), inner.left + 24f, cy + 8f, UiKit.bodyPaint(24f, colour))
        }
    }

    /** the bags, to choose an attachment from: the game's soulbound items greyed (they cannot be mailed); B / Close puts it away */
    private fun bagPicker(c: Canvas, r: RectF, sd: JSONObject, act: JSONObject?) {
        hits.clear()
        UiKit.cover(c, r)
        val bags = arr(sd, "bags")
        val per = 8; val sz = 96f; val gap = 18f
        val rows = max(1, (bags.size + per - 1) / per)
        val w = per * sz + (per - 1) * gap + 80f
        val h = min(r.height() - 120f, 150f + rows * (sz + gap) + 150f)
        val box = RectF(r.centerX() - w / 2f, r.top + 60f, r.centerX() + w / 2f, r.top + 60f + h)
        val inn = UiKit.dialog(c, box)
        c.drawText("Choose an attachment", inn.left + 10f, inn.top + 40f, UiKit.bodyPaint(30f, Theme.gold))
        val gridTop = inn.top + 70f
        val view = RectF(inn.left, gridTop, inn.right, box.bottom - 150f)
        viewB = view; contentB = rows * (sz + gap)
        val ci = bags.indexOfFirst { "mailattach ${it.optInt("bag")} ${it.optInt("s")} ${it.optInt("id")}" == cur }
        if (ci >= 0) {
            val y = (ci / per) * (sz + gap)
            if (y < scrollB) scrollB = y else if (y + sz > scrollB + view.height()) scrollB = y + sz - view.height()
        }
        scrollB = scrollB.coerceIn(0f, max(0f, contentB - view.height()))
        c.save(); c.clipRect(view)
        for ((k, b) in bags.withIndex()) {
            val x = inn.left + 30f + (k % per) * (sz + gap)
            val y = gridTop + (k / per) * (sz + gap) - scrollB
            if (y + sz < view.top || y > view.bottom) continue
            val cmd = "mailattach ${b.optInt("bag")} ${b.optInt("s")} ${b.optInt("id")}"
            val bound = b.optBoolean("bound") || b.optBoolean("locked")
            UiKit.slot(c, itemIcon(b), x, y, sz, count = b.optInt("n", 0), dim = bound)
            val rr = RectF(x, max(y, view.top), x + sz, min(y + sz, view.bottom))
            val sl = slotOf(b)
            hits.add(rr to when { b.optBoolean("bound") -> "why:Soulbound items cannot be mailed"; b.optBoolean("locked") -> "why:That item is locked"
                sl >= 0 -> "act:$cmd|$sl"; else -> "why:The game gave this no tap cell" })
            if (cur == cmd) UiKit.focus(c, RectF(x, y, x + sz, y + sz))
        }
        c.restore()
        if (bags.isEmpty()) c.drawText("Your bags are empty", inn.left + 30f, gridTop + 50f, UiKit.bodyPaint(26f, Theme.dim))
        val cur0 = bags.firstOrNull { "mailattach ${it.optInt("bag")} ${it.optInt("s")} ${it.optInt("id")}" == cur }
        cur0?.let { c.drawText(fit(UiKit.bodyPaint(26f, Theme.quality(it.optInt("q", 1))), it.optString("name", ""), inn.width() - 40f),
            inn.left + 20f, box.bottom - 120f, UiKit.bodyPaint(26f, Theme.quality(it.optInt("q", 1)))) }
        val off = act?.let { slotOf(it, "unpick") } ?: -1
        val cb = RectF(box.centerX() - 190f, box.bottom - 100f, box.centerX() + 190f, box.bottom - 10f)
        stripButton(c, cb, "Close", if (off >= 0) "act:mailpick off|$off" else "why:Press ${Glyphs.name("b")}", true, false, UiKit.backGlyph(), always = true)
    }

    /**
     * THE USER'S PICK (1.0.8, "Confirm on the top"): money or items to someone not a friend or guildmate stop at the game's own secure
     * dialog on the top screen; no add-on may answer it. The controller is the game's meanwhile; Done (a touch) brings the bottom
     * screen back if it was cancelled there. A letter that went says so by itself.
     */
    private fun confirmOnTop(c: Canvas, r: RectF, act: JSONObject?) {
        hits.clear()
        UiKit.cover(c, r)
        val box = RectF(r.centerX() - 520f, 200f, r.centerX() + 520f, 200f + 430f)
        val inn = UiKit.dialog(c, box)
        val tp = UiKit.bodyPaint(34f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
        c.drawText("Confirm on the top screen", inn.centerX(), inn.top + 50f, tp)
        val words = "The game asks before sending money or items to someone who is not on your friends list or in your guild. " +
            "Use the controller or touch on the top screen to confirm or cancel."
        val lay = layout(words, UiKit.bodyPaint(26f, Color.WHITE), (inn.width() - 40f).toInt(), 4f)
        drawLayout(c, lay, inn.left + 20f, inn.top + 90f)
        val res = act?.let { slotOf(it, "resume") } ?: -1
        val db = RectF(box.centerX() - 190f, box.bottom - 140f, box.centerX() + 190f, box.bottom - 30f)
        stripButton(c, db, "Done", if (res >= 0) "act:mailresume|$res" else "why:Waiting for the game", res >= 0, false, null, always = true)
    }

    // ---- the AUCTION HOUSE, step 1: browse and buy (UI Art's AUCTION_SPEC, art-kit 0c0f6cb; user 2026-09-30 "Yes, build it")
    // Categories down the left (the game's tree, the browsed path open in place); on the right the results, a commodity's
    // price tiers with its quantity, or an item's listings; the game's buy box over it all while a purchase is armed.

    /**
     * THE GAME'S CONFIRM BOX over the auction house (1.0.9): [title], the [lines] in their colours, the [money] rows (a label, the coins),
     * Accept ([yesHit]: A) and Cancel ([noHit]: B). Nothing under it takes a touch meanwhile.
     */
    private fun ahConfirm(c: Canvas, r: RectF, title: String, lines: List<Pair<String, Int>>, money: List<Pair<String, Long>>,
                          yesWord: String, yesHit: String, noHit: String) {
        hits.clear()
        UiKit.cover(c, r)
        val w = 960f
        val h = 150f + lines.size * 46f + money.size * 52f + 150f
        val d = RectF(r.centerX() - w / 2f, r.top + max(150f, (r.height() - h) / 2f - 60f), r.centerX() + w / 2f, 0f)
        d.bottom = d.top + h
        val inn = UiKit.dialog(c, d)
        c.drawText(title, inn.centerX(), inn.top + 46f, UiKit.bodyPaint(34f, Theme.gold).apply { textAlign = Paint.Align.CENTER })
        var y = inn.top + 104f
        for ((t, colour) in lines) {
            val p = UiKit.bodyPaint(28f, colour).apply { textAlign = Paint.Align.CENTER }
            c.drawText(fit(p, t, inn.width() - 40f), inn.centerX(), y, p); y += 46f
        }
        y += 6f
        for ((label, v) in money) {
            c.drawText("$label:", inn.centerX() - 20f, y + 10f, UiKit.bodyPaint(27f, Theme.gold).apply { textAlign = Paint.Align.RIGHT })
            UiKit.money(c, v, inn.centerX() + 10f, y, 30f)
            y += 52f
        }
        val bw = 330f; val bh = 104f
        val yes = RectF(inn.centerX() - 14f - bw, d.bottom - 40f - bh, inn.centerX() - 14f, d.bottom - 40f)
        val no = RectF(inn.centerX() + 14f, yes.top, inn.centerX() + 14f + bw, yes.bottom)
        stripButton(c, yes, yesWord, yesHit, !yesHit.startsWith("why:"), pendingCmd != null, "rev_a", always = true)
        stripButton(c, no, "Cancel", noHit, true, false, UiKit.backGlyph(), always = true)
    }

    /**
     * THE SELL TAB (1.0.9; the user's "Yes, build it": post from the bags, the game's way). The game's Sell frame, read from the client:
     * your bags' sellable items down the left (the game takes them by drag; here they are listed); the chosen one's form in the middle
     * (Quantity with X / Y and Max, "Buyout only" and the Bid for an item, the price's gold / silver / copper, 12 / 24 / 48 h, the
     * Deposit, the Total); the game's current listings for it on the right (its own lowest price fills the form until one is typed).
     * Post (A) arms it: the confirm with the deposit; A again posts. A second word the game wants is its own dialog (mirrored).
     */
    private fun sellPage(c: Canvas, r: RectF, inner: RectF, au: JSONObject, act: JSONObject?, by0: Float, by1: Float, strip: RectF,
                         now: Long, name: String) {
        fun col(k: String) = act?.let { slotOf(it, k) } ?: -1
        fun hitOf(cmd: String, k: String) = col(k).let { if (it >= 0) "act:$cmd|$it" else "why:The game gave this no tap cell" }
        val sl = au.optJSONObject("sell") ?: JSONObject()
        val item = sl.optJSONObject("item")
        val cy = strip.centerY() + 2f
        val bx = inner.right - 8f
        // ---- your bags, down the left
        val left = RectF(inner.left + 4f, by0, inner.left + 334f, by1)
        UiKit.ahPanel(c, left, "ah_bg_categories")
        c.drawText("Your bags", left.left + 18f, left.top + 36f, UiKit.bodyPaint(24f, Theme.gold))
        val bags = arr(sl, "bags")
        val view = RectF(left.left, left.top + 52f, left.right, left.bottom - 4f)
        val rowH = 72f
        viewB = view; contentB = bags.size * rowH + 8f
        fun cmdOf(b: JSONObject) = "ahsitem ${b.optInt("bag")} ${b.optInt("s")} ${b.optInt("id")}"
        val ci = bags.indexOfFirst { cmdOf(it) == cur }
        if (ci >= 0 && cur != shownCatCur) {
            shownCatCur = cur
            val top = ci * rowH
            if (top < scrollB) scrollB = top else if (top + rowH > scrollB + view.height()) scrollB = top + rowH - view.height()
        }
        scrollB = scrollB.coerceIn(0f, max(0f, contentB - view.height()))
        val chosen = item?.let { "ahsitem ${it.optInt("bag")} ${it.optInt("s")} ${it.optInt("id")}" }
        c.save(); c.clipRect(view)
        for ((k, b) in bags.withIndex()) {
            val y = view.top + 4f + k * rowH - scrollB
            if (y + rowH < view.top || y > view.bottom) continue
            val rr = RectF(left.left + 8f, y, left.right - 8f, y + rowH - 6f)
            val cmd = cmdOf(b)
            UiKit.ahRowGround(c, rr, k % 2 == 1)
            if (cmd == chosen) { pFill.color = Color.argb(70, Color.red(Theme.gold), Color.green(Theme.gold), Color.blue(Theme.gold)); c.drawRect(rr, pFill) }
            UiKit.slot(c, itemIcon(b), rr.left + 6f, rr.top + 5f, rr.height() - 10f, count = b.optInt("n", 0), dim = b.optBoolean("locked"))
            val np = UiKit.bodyPaint(23f, Theme.quality(b.optInt("q", 1)))
            c.drawText(fit(np, b.optString("name", ""), rr.width() - rr.height() - 16f), rr.left + rr.height() + 6f,
                rr.centerY() - (np.ascent() + np.descent()) / 2f, np)
            val s0 = slotOf(b)
            hits.add(RectF(rr.left, max(rr.top, view.top), rr.right, min(rr.bottom, view.bottom)) to when {
                b.optBoolean("locked") -> "why:That item is locked"; s0 >= 0 -> "act:$cmd|$s0"; else -> "why:The game gave this no tap cell" })
            if (cur == cmd) UiKit.focus(c, rr)
        }
        c.restore()
        if (bags.isEmpty()) {
            val lay = layout("Nothing in your bags can be sold at auction", UiKit.bodyPaint(24f, Theme.dim), (left.width() - 36f).toInt(), 4f)
            drawLayout(c, lay, left.left + 18f, view.top + 20f)
        }
        // ---- the form, in the middle
        val f = RectF(left.right + 12f, by0, left.right + 12f + 470f, by1)
        UiKit.ahPanel(c, f, "ah_bg_commodities")
        val mk = RectF(f.right + 12f, by0, inner.right - 4f, by1)
        UiKit.ahPanel(c, mk, "ah_bg_market")
        val lab = UiKit.bodyPaint(25f, Theme.gold)
        val postB = RectF(bx - 250f, cy - 55f, bx, cy + 56f)
        val why = if (sl.has("why")) sl.optString("why") else null
        val st = sl.optString("status", "")
        if (item == null) {
            val lay = layout("Choose an item from your bags to sell it", UiKit.bodyPaint(27f, Theme.dim), (f.width() - 60f).toInt(), 6f)
            drawLayout(c, lay, f.left + 30f, f.top + 80f)
            stripButton(c, postB, "Post", "why:Choose an item to sell", false, false, "rev_a", id = "ahspost")
        } else {
            val commodity = item.optBoolean("commodity")
            UiKit.ahItemHeader(c, RectF(f.left + 10f, f.top + 10f, f.right - 10f, f.top + 120f), itemIcon(item),
                item.optString("name", ""), Theme.quality(item.optInt("q", 1)))
            var y = f.top + 140f
            val gl = cur != null
            // Quantity: X / Y (- / +), A or a touch on the number = the game's Max
            val q = sl.optInt("qty", 1); val mx = sl.optInt("max", 1)
            if (mx > 1) {
                val qy = y + 40f
                c.drawText("Quantity", f.left + 24f, qy + 10f, lab)
                UiKit.square(c, "minus", f.left + 236f, qy, 72f, if (q > 1) UiKit.Button.UP else UiKit.Button.DISABLED, "rev_x".takeIf { gl })
                c.drawText(q.toString(), f.left + 313f, qy + 12f, UiKit.numberPaint(32f, Color.WHITE).apply { textAlign = Paint.Align.CENTER })
                UiKit.square(c, "plus", f.left + 390f, qy, 72f, if (q < mx) UiKit.Button.UP else UiKit.Button.DISABLED, "rev_y".takeIf { gl }, glyphRight = true)
                if (q > 1) hits.add(RectF(f.left + 192f, qy - 44f, f.left + 280f, qy + 44f) to hitOf("ahsqty less", "sq_less"))
                if (q < mx) hits.add(RectF(f.left + 346f, qy - 44f, f.left + 434f, qy + 44f) to hitOf("ahsqty more", "sq_more"))
                hits.add(RectF(f.left + 282f, qy - 40f, f.left + 344f, qy + 40f) to hitOf("ahsqty max", "sq_max"))
                c.drawText("of $mx", f.left + 313f, qy + 50f, UiKit.bodyPaint(18f, Theme.dim).apply { textAlign = Paint.Align.CENTER })
                if (cur == "ahsq") UiKit.focus(c, RectF(f.left + 12f, qy - 46f, f.right - 12f, qy + 52f))
                y += 104f
            }
            // an item: the game's "Buyout only" box; unchecked, the Bid too
            val bidOn = sl.optBoolean("bidOn")
            if (!commodity) {
                val ry = y + 28f
                UiKit.check(c, f.left + 44f, ry, 40f, !bidOn)
                c.drawText(sl.optString("boWord", "Buyout only"), f.left + 76f, ry + 9f, lab)
                val rr = RectF(f.left + 12f, ry - 28f, f.right - 12f, ry + 28f)
                hits.add(rr to hitOf("ahsbo", "sbo"))
                if (cur == "ahsbo") UiKit.focus(c, rr)
                y += 64f
            }
            val copper = sl.optBoolean("copper")
            fun moneyRow(label: String, v: Long, pre: String) {
                c.drawText(label, f.left + 24f, y + 22f, lab)
                val parts = listOf("g" to (v / 10000), "s" to ((v / 100) % 100), "c" to (v % 100)).filter { it.first != "c" || copper }
                val x0 = f.left + 24f; val x1 = f.right - 20f
                val w = (x1 - x0 - (parts.size - 1) * 10f) / parts.size
                for ((i, pv) in parts.withIndex()) {
                    val (k, n) = pv
                    val b = RectF(x0 + i * (w + 10f), y + 34f, x0 + i * (w + 10f) + w, y + 92f)
                    formBox(c, RectF(b.left, b.top, b.right - 38f, b.bottom), if (n > 0) n.toString() else "", "0", "ahsedit $pre$k",
                        col("se_$pre$k"), "ahs$pre$k", size = 26f, digits = true)
                    val coin = when (k) { "g" -> "coin_gold"; "s" -> "coin_silver"; else -> "coin_copper" }
                    UiKit.tex(coin)?.let { bm -> c.drawBitmap(bm, null, RectF(b.right - 34f, b.centerY() - 15f, b.right - 4f, b.centerY() + 15f), pArt) }
                }
                y += 104f
            }
            if (!commodity && bidOn) moneyRow("Bid Price", sl.optLong("bid", 0), "b")
            moneyRow(if (commodity) "Unit Price" else "Buyout Price", sl.optLong("unit", 0), "p")
            // the duration: the game's three
            val durs = sl.optJSONArray("durs")
            val dur = sl.optInt("dur", 2)
            c.drawText("Duration", f.left + 24f, y + 22f, lab)
            val dw = (f.width() - 40f) / 3f
            for (k in 1..3) {
                val rr = RectF(f.left + 20f + (k - 1) * dw, y + 36f, f.left + 20f + k * dw, y + 84f)
                UiKit.radio(c, rr.left + 22f, rr.centerY(), 36f, dur == k)
                c.drawText(fit(UiKit.bodyPaint(22f, Color.WHITE), durs?.optString(k - 1) ?: "${12 * (1 shl (k - 1))} Hours", dw - 50f),
                    rr.left + 46f, rr.centerY() + 8f, UiKit.bodyPaint(22f, Color.WHITE))
                hits.add(rr to hitOf("ahsdur $k", "sd_$k"))
                if (cur == "ahsdur $k") UiKit.focus(c, rr)
            }
            y += 100f
            // the deposit and the total, the game's two lines
            val dep = sl.optLong("deposit", 0)
            c.drawText("Deposit", f.left + 24f, y + 10f, lab)
            UiKit.money(c, dep, f.right - 24f, y, 28f, if (dep <= au.optLong("money", 0)) Theme.text else Theme.unusable, fromRight = true)
            y += 48f
            c.drawText("Total Price", f.left + 24f, y + 10f, lab)
            UiKit.money(c, sl.optLong("total", 0), f.right - 24f, y, 30f, fromRight = true)
            val canPost = why == null && st != "posting" && st != "warn"
            stripButton(c, postB, "Post", if (canPost) hitOf("ahspost", "spost") else "why:${why ?: "One moment"}", canPost,
                pendingCmd == "ahspost", "rev_a", id = "ahspost")
            // ---- the listings now, on the right (the game's own search for this item)
            val rows = arr(sl, "listings")
            UiKit.ahHeader(c, mk.left + 10f, mk.right - 10f, mk.top + 30f,
                listOf((if (commodity) "Unit Price" else "Buyout") to mk.left + 24f, (if (commodity) "Available" else "Qty") to -(mk.right - 24f)))
            var ly = mk.top + 66f
            for ((k, row) in rows.withIndex()) {
                val rr = RectF(mk.left + 8f, ly, mk.right - 8f, ly + 54f)
                if (rr.bottom > mk.bottom - 4f) break
                UiKit.ahRowGround(c, rr, k % 2 == 1)
                val pr = row.optLong("price", 0)
                if (pr > 0) UiKit.money(c, pr, rr.left + 14f, rr.centerY(), 25f)
                else c.drawText("Bid only", rr.left + 14f, rr.centerY() + 8f, UiKit.bodyPaint(22f, Theme.dim))
                val cp = UiKit.numberPaint(24f, if (row.optBoolean("mine")) Theme.gold else Color.WHITE).apply { textAlign = Paint.Align.RIGHT }
                c.drawText((if (row.optBoolean("mine")) "Yours  " else "") + row.optInt("count", 0), rr.right - 14f, rr.centerY() + 9f, cp)
                ly += 58f
            }
            if (rows.isEmpty()) {
                val lay = layout("No one else is selling this right now", UiKit.bodyPaint(23f, Theme.dim), (mk.width() - 40f).toInt(), 4f)
                drawLayout(c, lay, mk.left + 20f, mk.top + 80f)
            }
        }
        // ---- the strip: the game's word on the last post, or why it can't go yet; Back; Post
        UiKit.hints(c, postB.left - 20f, cy, UiKit.backGlyph() to "Back")
        if (!resultLine(c, inner.left + 24f, postB.left - 200f, cy, now)) {
            val msg = sl.optJSONObject("msg")
            val (words, colour) = when {
                st == "warn" -> "Answer the game's message" to Theme.gold
                st == "posting" -> "Posting..." to Theme.dim
                st == "failed" -> "The game did not post it" to Theme.unusable
                msg != null -> msg.optString("words") to (if (msg.optBoolean("ok")) Theme.gold else Theme.unusable)
                item != null && why != null -> why to Theme.dim
                else -> "" to Theme.dim
            }
            if (words.isNotEmpty()) c.drawText(fit(UiKit.bodyPaint(24f, colour), words, postB.left - 220f - inner.left), inner.left + 24f, cy + 8f,
                UiKit.bodyPaint(24f, colour))
        }
        UiKit.windowFrame(c, r, name, UiKit.tex("mm_auctioneer"), Theme.gold, closeDown = pressed == "x", closeB = closeB())
        // ---- Post armed: the confirm, with the deposit (the user's ask)
        if (item != null && sl.optBoolean("armed")) {
            val commodity = item.optBoolean("commodity")
            val durs = sl.optJSONArray("durs")
            val money = ArrayList<Pair<String, Long>>()
            if (commodity) money.add("Unit Price" to sl.optLong("unit", 0))
            else {
                if (sl.optBoolean("bidOn") && sl.optLong("bid", 0) > 0) money.add("Bid" to sl.optLong("bid", 0))
                if (sl.optLong("unit", 0) > 0) money.add("Buyout" to sl.optLong("unit", 0))
            }
            money.add("Total Price" to sl.optLong("total", 0))
            money.add("Deposit" to sl.optLong("deposit", 0))
            val q = sl.optInt("qty", 1)
            ahConfirm(c, r, "Post Auction", listOf((item.optString("name", "") + if (q > 1) "  x$q" else "") to Theme.quality(item.optInt("q", 1)),
                (durs?.optString(sl.optInt("dur", 2) - 1) ?: "") to Theme.dim), money,
                "Post", hitOf("ahspost", "spost"), act?.let { slotOf(it, "back") }?.takeIf { it >= 0 }?.let { "act:ahback|$it" } ?: "why:Press ${Glyphs.name("b")}")
        }
    }

    private fun timeWord(n: Int) = when (n) { 0 -> "Short"; 1 -> "Medium"; 2 -> "Long"; 3 -> "Very Long"; else -> "" }

    private fun auctionWindow(c: Canvas, r: RectF, au: JSONObject, now: Long) {
        val inner = UiKit.windowGround(c, r, marble = true)
        val act = au.optJSONObject("act")
        val tabId = au.optString("tab", "browse")
        val owned = tabId == "owned"
        val purse = au.optLong("money", 0)
        val busy = pendingCmd != null
        val ty = r.top + 94f; val th = 108f
        val e = UiKit.TAB_END_W           // L and R on the row's outsides, always (the user, 15 Oct)
        // 1.0.9: the game's three tabs, Buy | Sell | Auctions (the vendor's spans); the open one stands taller
        val spans = listOf(Triple("browse", "Buy", 176f to 366f), Triple("sell", "Sell", 362f to 552f), Triple("owned", "Auctions", 548f to 798f))
        for ((id, label, xs) in spans) {
            val on = tabId == id
            val tr = RectF(xs.first + e, ty + (if (on) 4f else 12f), xs.second + e, ty + th)
            // the tab cursor is the kit's (LATER 19: the tab lit soft gold, its own edge traced), never the gold box on a tab
            UiKit.tab(c, tr, label, on, if (id == "owned") au.optInt("nOwned", 0) else 0, cursor = cur == "ahtab $id")
            act?.let { slotOf(it, id) }?.let { if (it >= 0) hits.add(tr to "act:ahtab $id|$it") }
        }
        val ta = RectF(548f + e, ty + 12f, 798f + e, ty + th)
        UiKit.tabEnds(c, RectF(176f + e, ty + 12f, 366f + e, ty + th), ta)
        pFill.color = Color.rgb(96, 80, 50)
        c.drawRect(inner.left - 6f, ty + th, inner.right + 6f, ty + th + 2f, pFill)
        val pm = UiKit.bodyPaint(22f, Theme.dim).apply { textAlign = Paint.Align.RIGHT }
        val wordsR = inner.right - 16f - UiKit.moneyWidth(purse, 30f) - 14f        // the words before the coins, measured
        if (wordsR - UiKit.bodyPaint(22f, Theme.dim).measureText("Your money") > ta.right + e + 8f) c.drawText("Your money", wordsR, ty + th / 2f + 14f, pm)
        UiKit.money(c, purse, inner.right - 16f, ty + th / 2f + 6f, 30f, fromRight = true)
        val by0 = ty + th + 10f; val by1 = inner.bottom - UiKit.STRIP - 6f
        val strip = UiKit.strip(c, inner)
        val cy = strip.centerY() + 2f
        val bx = inner.right - 8f
        val es = entries()
        fun entry(cmd: String) = es.firstOrNull { it.cmd == cmd }
        val name = au.optJSONObject("npc")?.optString("name", "")?.takeIf { it.isNotEmpty() } ?: "Auction House"

        if (tabId == "sell") { sellPage(c, r, inner, au, act, by0, by1, strip, now, name); return }
        if (owned) {
            // ---- your auctions, read only
            val t = RectF(inner.left + 4f, by0, inner.right - 4f, by1)
            UiKit.ahPanel(c, t, "ah_bg_auctions")
            sortHeads(c, t.top + 30f, au, "owned", listOf(Head("Item", t.left + 100f, "Name"), Head("Bid", -(t.right - 470f), "Bid"),
                Head("Buyout", -(t.right - 250f), "Buyout"), Head("Time Left", -(t.right - 24f), "TimeRemaining")))
            val rows = arr(au, "owned")
            val view = RectF(t.left, t.top + 58f, t.right, t.bottom - 4f)
            viewA = view; contentA = rows.size * 84f
            scrollA = scrollA.coerceIn(0f, max(0f, contentA - view.height()))
            follow(rows.indexOfFirst { "ahown ${it.optInt("i")}" == cur }, 84f, view.height())
            c.save(); c.clipRect(view)
            for ((k, o) in rows.withIndex()) {
                val row = RectF(t.left + 8f, view.top + 4f + k * 84f - scrollA, t.right - 8f, view.top + 4f + k * 84f - scrollA + 78f)
                UiKit.ahRowGround(c, row, k % 2 == 1)
                UiKit.slot(c, itemIcon(o), row.left + 14f, row.top + 10f, 58f, count = o.optInt("count", 0))
                val np = UiKit.bodyPaint(26f, Theme.quality(o.optInt("q", 1)))
                c.drawText(fit(np, o.optString("name", ""), row.width() - 600f), row.left + 92f, row.centerY() - (np.ascent() + np.descent()) / 2f, np)
                o.optLong("bid", 0).takeIf { it > 0 }?.let { UiKit.money(c, it, row.right - 470f, row.centerY(), 25f, Theme.dim, fromRight = true) }
                o.optLong("price", 0).takeIf { it > 0 }?.let { UiKit.money(c, it, row.right - 250f, row.centerY(), 25f, fromRight = true) }
                val sold = o.optInt("status", 0) == 1
                val tp = UiKit.bodyPaint(24f, if (sold) Color.rgb(32, 255, 32) else Color.WHITE).apply { textAlign = Paint.Align.RIGHT }
                c.drawText(if (sold) "Sold" else timeWord(o.optInt("left", -1)), row.right - 16f, row.centerY() - (tp.ascent() + tp.descent()) / 2f, tp)
                // 1.0.9: a touch on one of yours cancels it (armed first: the game's confirm), as A does
                val sl = slotOf(o)
                val hr = RectF(row.left, max(row.top, view.top), row.right, min(row.bottom, view.bottom))
                if (hr.height() > 0f) hits.add(hr to when { sold -> "why:That one has sold"; !o.optBoolean("can") -> "why:The game won't cancel this one"
                    sl >= 0 -> "act:ahcancel ${o.optInt("i")}|$sl"; else -> "why:The game gave this no tap cell" })
                if (cur == "ahown ${o.optInt("i")}") UiKit.focus(c, row)
            }
            c.restore()
            if (rows.isEmpty()) c.drawText("You have no auctions", t.left + 24f, t.top + 100f, UiKit.bodyPaint(26f, Theme.dim))
            UiKit.hints(c, inner.right - 16f, cy, UiKit.backGlyph() to "Back", "rev_a" to "Cancel Auction")
            resultLine(c, inner.left + 24f, bx - 520f, cy, now)
            UiKit.windowFrame(c, r, name, UiKit.tex("mm_auctioneer"), Theme.gold, closeDown = pressed == "x", closeB = closeB())
            // 1.0.9: a Cancel armed: the game's CANCEL_AUCTION box (its words, its cost when there is one), A again cancels
            au.optJSONObject("cancel")?.let { cc ->
                val i = cc.optInt("i")
                val o = rows.firstOrNull { it.optInt("i") == i }
                val sl = o?.let { slotOf(it) } ?: -1
                val cost = cc.optLong("cost", 0)
                ahConfirm(c, r, "Cancel Auction", listOfNotNull(o?.let { it.optString("name", "") to Theme.quality(it.optInt("q", 1)) },
                    cc.optString("words") to Color.WHITE), if (cost > 0) listOf("Cost" to cost) else emptyList(),
                    "Accept", if (sl >= 0) "act:ahcancel $i|$sl" else "why:The game gave this no tap cell",
                    act?.let { slotOf(it, "back") }?.takeIf { it >= 0 }?.let { "act:ahback|$it" } ?: "why:Press ${Glyphs.name("b")}")
            }
            return
        }

        // ---- the categories, down the left (dragged / moved by the cursor)
        val cat = RectF(inner.left + 4f, by0, inner.left + 334f, by1)
        UiKit.ahPanel(c, cat, "ah_bg_categories")
        val cats = arr(au, "cats")
        viewB = cat; contentB = cats.size * 68f + 16f
        val ci = cats.indexOfFirst { "ahcat ${it.optString("i")}" == cur }
        if (ci >= 0 && cur != shownCatCur) {
            shownCatCur = cur
            val top = ci * 68f
            if (top < scrollB) scrollB = top else if (top + 76f > scrollB + cat.height()) scrollB = top + 76f - cat.height()
        }
        scrollB = scrollB.coerceIn(0f, max(0f, contentB - cat.height()))
        c.save(); c.clipRect(cat)
        for ((k, ct) in cats.withIndex()) {
            val y = cat.top + 8f + k * 68f - scrollB
            if (y + 62f < cat.top || y > cat.bottom) continue
            val lv = ct.optInt("lv", 0)
            val rr = RectF(cat.left + 8f + lv * 18f, y, cat.right - 8f, y + 62f)
            UiKit.ahNav(c, rr, ct.optString("name", ""), lv, ct.optBoolean("sel"))
            hits.add(RectF(rr.left, max(rr.top, cat.top), rr.right, min(rr.bottom, cat.bottom)) to "do:ahcat ${ct.optString("i")}")
            if (cur == "ahcat ${ct.optString("i")}") UiKit.focus(c, rr)
        }
        c.restore()

        val rx0 = cat.right + 12f; val rx1 = inner.right - 4f
        // 1.0.8: THE GAME'S SEARCH BAR over the results (f-auction-house): the search box, Filter, Search
        searchBar(c, RectF(rx0, by0, rx1, by0 + 84f), au, act)
        val ry0 = by0 + 96f
        val item = au.optJSONObject("item")
        val buy = au.optJSONObject("buy")
        var box: (() -> Unit)? = null
        var tipRowY: Float? = null              // the cursor's row, for the tooltip's height
        if (item != null && item.optBoolean("commodity")) {
            // ---- a commodity: the game's CommoditiesBuyFrame and its price tiers
            val bf = RectF(rx0, ry0, rx0 + 450f, by1)
            UiKit.ahPanel(c, bf, "ah_bg_commodities")
            UiKit.ahItemHeader(c, RectF(bf.left + 10f, bf.top + 10f, bf.right - 10f, bf.top + 120f), itemIcon(item),
                item.optString("name", ""), Theme.quality(item.optInt("q", 1)))
            val rows = arr(item, "rows")
            val q = au.optInt("qty", 1)
            var avail = 0
            for (t in rows) if (!t.optBoolean("mine")) avail += t.optInt("count", 0)
            // the cost of q, the cheapest first (the game's own fill); the game's own total once it has priced it
            var left = q; var est = 0L
            for (t in rows) { if (left <= 0) break; if (t.optBoolean("mine")) continue
                val n = min(left, t.optInt("count", 0)); est += n * t.optLong("price", 0); left -= n }
            val total = buy?.optLong("total", 0)?.takeIf { it > 0 && buy.optInt("qty") == q } ?: est
            val unit = rows.firstOrNull { !it.optBoolean("mine") }?.optLong("price", 0) ?: 0L
            val lp = UiKit.bodyPaint(26f, Theme.gold)
            val qy = bf.top + 200f
            c.drawText("Quantity", bf.left + 30f, qy + 10f, lp)
            val canLess = q > 1; val canMore = q < avail
            val gl = cur != null
            UiKit.square(c, "minus", bf.left + 236f, qy, 76f, if (canLess) UiKit.Button.UP else UiKit.Button.DISABLED, "rev_x".takeIf { gl })
            c.drawText(q.toString(), bf.left + 313f, qy + 12f, UiKit.numberPaint(34f, Color.WHITE).apply { textAlign = Paint.Align.CENTER })
            UiKit.square(c, "plus", bf.left + 390f, qy, 76f, if (canMore) UiKit.Button.UP else UiKit.Button.DISABLED, "rev_y".takeIf { gl }, glyphRight = true)
            act?.let { slotOf(it, "less") }?.let { if (it >= 0 && canLess) hits.add(RectF(bf.left + 190f, qy - 46f, bf.left + 282f, qy + 46f) to "act:ahqty less|$it") }
            act?.let { slotOf(it, "more") }?.let { if (it >= 0 && canMore) hits.add(RectF(bf.left + 344f, qy - 46f, bf.left + 436f, qy + 46f) to "act:ahqty more|$it") }
            c.drawText("Unit Price", bf.left + 30f, qy + 110f, lp)
            UiKit.money(c, unit, bf.right - 30f, qy + 100f, 28f, fromRight = true)
            c.drawText("Total Price", bf.left + 30f, qy + 180f, lp)
            UiKit.money(c, total, bf.right - 30f, qy + 170f, 32f, if (total <= purse) Theme.text else Theme.unusable, fromRight = true)
            val mk = RectF(bf.right + 12f, ry0, rx1, by1)
            UiKit.ahPanel(c, mk, "ah_bg_market")
            UiKit.ahHeader(c, mk.left + 10f, mk.right - 10f, mk.top + 30f, listOf("Unit Price" to mk.left + 24f, "Available" to -(mk.right - 24f)))
            c.save(); c.clipRect(mk.left, mk.top + 58f, mk.right, mk.bottom - 4f)
            var focused = false
            for ((k, t) in rows.withIndex()) {
                val ry = mk.top + 62f + k * 64f
                if (ry > mk.bottom) break
                val row = RectF(mk.left + 8f, ry, mk.right - 8f, ry + 60f)
                val mine = t.optBoolean("mine")
                UiKit.ahRowGround(c, row, k % 2 == 1)
                UiKit.money(c, t.optLong("price", 0), row.left + 16f, row.centerY(), 26f, if (mine) Theme.dim else Theme.text)
                val np = UiKit.numberPaint(26f, if (mine) Theme.dim else Color.WHITE).apply { textAlign = Paint.Align.RIGHT }
                c.drawText(t.optInt("count", 0).toString(), row.right - 16f, row.centerY() - (np.ascent() + np.descent()) / 2f, np)
                if (mine) c.drawText("yours", row.centerX() + 10f, row.centerY() + 8f, UiKit.bodyPaint(20f, Theme.dim))
                // the cursor: the tier the quantity starts from (the cheapest that is not yours)
                if (!mine && !focused) { UiKit.focus(c, row); focused = true }
            }
            c.restore()
            val be = entry("ahbuy")
            val ok = be != null && be.slot >= 0 && avail > 0 && total <= purse
            UiKit.hints(c, bx - 280f, cy, UiKit.backGlyph() to "Back")
            stripButton(c, RectF(bx - 260f, cy - 55f, bx, cy + 56f), "Buy Now", "go", ok, busy && pendingCmd == "ahbuy", "rev_a")
            goCmd = be.takeIf { ok }
            if (!ok) goWhy = if (total > purse) "Not enough money" else "Nothing to buy"
            resultLine(c, inner.left + 24f, bx - 420f, cy, now)
            if (buy != null && (buy.optBoolean("armed") || buy.optBoolean("waiting")))
                box = { ahBox(c, r, item, buy.optInt("qty", q), buy.optLong("total", total), buy.optBoolean("waiting"), be, act) }
        } else if (item != null) {
            // ---- an item: its listings (Buyout, Bid, Time Left); yours dimmed
            UiKit.ahItemHeader(c, RectF(rx0, ry0, rx1, ry0 + 110f), itemIcon(item), item.optString("name", ""), Theme.quality(item.optInt("q", 1)))
            val list = RectF(rx0, ry0 + 120f, rx1, by1)
            UiKit.ahPanel(c, list, "ah_bg_item_market")
            sortHeads(c, list.top + 30f, au, "item", listOf(Head("Buyout", list.left + 24f, "Buyout"),
                Head("Bid", list.left + 330f, "Bid"), Head("Time Left", -(list.right - 24f), null)))
            val rows = arr(item, "rows")
            val view = RectF(list.left, list.top + 58f, list.right, list.bottom - 4f)
            viewA = view; contentA = rows.size * 70f
            scrollA = scrollA.coerceIn(0f, max(0f, contentA - view.height()))
            follow(rows.indexOfFirst { "ahrow ${it.optInt("i")}" == cur }, 70f, view.height())
            val pick = cur?.takeIf { it.startsWith("ahrow ") } ?: selected?.takeIf { it.startsWith("ahrow ") }
            c.save(); c.clipRect(view)
            for ((k, t) in rows.withIndex()) {
                val ry = view.top + 4f + k * 70f - scrollA
                val row = RectF(list.left + 8f, ry, list.right - 8f, ry + 64f)
                val mine = t.optBoolean("mine")
                val col = if (mine) Theme.dim else Theme.text
                UiKit.ahRowGround(c, row, k % 2 == 1)
                if (t.optLong("price", 0) > 0) UiKit.money(c, t.optLong("price", 0), row.left + 16f, row.centerY(), 26f, col)
                if (t.optLong("bid", 0) > 0) UiKit.money(c, t.optLong("bid", 0), row.left + 322f, row.centerY(), 26f, Theme.dim)
                val tp = UiKit.bodyPaint(24f, if (mine) Theme.dim else Color.WHITE).apply { textAlign = Paint.Align.RIGHT }
                c.drawText(if (mine) "yours" else timeWord(t.optInt("left", -1)), row.right - 16f, row.centerY() - (tp.ascent() + tp.descent()) / 2f, tp)
                hits.add(RectF(row.left, max(row.top, view.top), row.right, min(row.bottom, view.bottom)) to "sel:ahrow ${t.optInt("i")}")
                if (pick == "ahrow ${t.optInt("i")}") { UiKit.focus(c, row); tipRowY = row.top }
            }
            c.restore()
            val pe = pick?.let { entry(it) }
            val price = pe?.o?.optLong("price", 0) ?: 0L
            val ok = pe != null && pe.slot >= 0 && price > 0 && !pe.o.optBoolean("mine") && price <= purse
            UiKit.hints(c, bx - 280f, cy, UiKit.backGlyph() to "Back")
            stripButton(c, RectF(bx - 260f, cy - 55f, bx, cy + 56f), "Buy Now", "go", ok, busy && pendingCmd == pe?.cmd, "rev_a")
            goCmd = pe.takeIf { ok }
            if (!ok) goWhy = when {
                pe == null -> "Choose a listing first"
                pe.o.optBoolean("mine") -> "That one is yours"
                price <= 0 -> "No buyout on this one"
                price > purse -> "Not enough money"
                else -> "The game gave this no tap cell"
            }
            resultLine(c, inner.left + 24f, bx - 420f, cy, now)
            if (buy != null && buy.optString("kind") == "item" && buy.optBoolean("armed")) {
                val be = entry("ahrow ${buy.optInt("row")}")
                box = { ahBox(c, r, item, max(1, buy.optInt("qty", 1)), buy.optLong("total", 0), false, be, act) }
            }
        } else {
            // ---- the results: Name, Price, Available
            val res = RectF(rx0, ry0, rx1, by1)
            UiKit.ahPanel(c, res, "ah_bg_index")
            // the game's columns: Price, Name, the category's level column (Item Level for weapons and armour), Available
            val lvlWord = if (au.has("levelWord")) au.optString("levelWord") else null
            sortHeads(c, res.top + 30f, au, "browse", listOfNotNull(Head("Name", res.left + 100f, "Name"),
                lvlWord?.let { Head(it, -(res.right - 400f), "Level") },
                Head("Price", -(res.right - 190f), "Price"), Head("Available", -(res.right - 24f), null)))
            val rows = arr(au, "results")
            val view = RectF(res.left, res.top + 58f, res.right, res.bottom - 4f)
            viewA = view; contentA = rows.size * 84f
            scrollA = scrollA.coerceIn(0f, max(0f, contentA - view.height()))
            follow(rows.indexOfFirst { "ahres ${it.optInt("i")}" == cur }, 84f, view.height())
            c.save(); c.clipRect(view)
            for ((k, l) in rows.withIndex()) {
                val ry = view.top + 4f + k * 84f - scrollA
                if (ry + 78f < view.top || ry > view.bottom) continue
                val row = RectF(res.left + 8f, ry, res.right - 8f, ry + 78f)
                UiKit.ahRowGround(c, row, k % 2 == 1)
                UiKit.slot(c, itemIcon(l), row.left + 14f, row.top + 10f, 58f)
                val np = UiKit.bodyPaint(26f, Theme.quality(l.optInt("q", 1)))
                c.drawText(fit(np, l.optString("name", ""), row.width() - (if (lvlWord != null) 560f else 400f)), row.left + 92f, row.centerY() - (np.ascent() + np.descent()) / 2f, np)
                if (lvlWord != null && l.optInt("lvl", 0) > 0) {
                    val lp = UiKit.numberPaint(26f, Color.WHITE).apply { textAlign = Paint.Align.RIGHT }
                    c.drawText(l.optInt("lvl").toString(), row.right - 400f, row.centerY() - (lp.ascent() + lp.descent()) / 2f, lp)
                }
                UiKit.money(c, l.optLong("price", 0), row.right - 182f, row.centerY(), 25f, fromRight = true)
                val ap = UiKit.numberPaint(26f, Color.WHITE).apply { textAlign = Paint.Align.RIGHT }
                c.drawText(l.optInt("count", 0).toString(), row.right - 16f, row.centerY() - (ap.ascent() + ap.descent()) / 2f, ap)
                hits.add(RectF(row.left, max(row.top, view.top), row.right, min(row.bottom, view.bottom)) to "do:ahres ${l.optInt("i")}")
                if (cur == "ahres ${l.optInt("i")}") { UiKit.focus(c, row); tipRowY = row.top }
            }
            c.restore()
            if (rows.isEmpty()) {
                val searched = (au.optJSONArray("path")?.length() ?: 0) > 0 || au.optJSONObject("search")?.optString("text").orEmpty().isNotEmpty()
                val words = if (searched) "Nothing found" else "Choose a category, or search"
                c.drawText(words, res.left + 30f, view.top + 50f, UiKit.bodyPaint(26f, Theme.dim))
            }
            resultLine(c, inner.left + 24f, bx - 300f, cy, now)
            UiKit.hints(c, inner.right - 16f, cy, "rev_a" to "Open", UiKit.backGlyph() to "Back")
        }
        // ---- THE TOOLTIP (user, 2026-09-30: "we need tool tips in the ah"), as at the vendor: two thirds of a half, over
        // the half away from the cursor (the categories), level with the cursor's row; X held: Currently Equipped on the
        // cursor's half. The result under the cursor, or the item opened (with or without a cursor).
        val tipKey = cur?.takeIf { it.startsWith("ahres ") || it.startsWith("ahrow ") || it == "ahbuy" } ?: if (item != null) "ahbuy" else null
        if (tipKey != null && box == null) tipFor(tipKey)?.let { t ->
            val res = if (tipKey.startsWith("ahres ")) arr(au, "results").firstOrNull { "ahres ${it.optInt("i")}" == tipKey } else null
            val nm = res?.optString("name", "") ?: item?.optString("name", "") ?: ""
            val q = res?.optInt("q", 1) ?: item?.optInt("q", 1) ?: 1
            val half = (inner.width() - 14f) / 2f
            val tw = half * 2f / 3f
            drawTip(c, inner.left + 4f, tipRowY ?: by0, tw, nm, q, t, by0, by1, inner.centerX() + 7f)
        }
        UiKit.windowFrame(c, r, name, UiKit.tex("mm_auctioneer"), Theme.gold, closeDown = pressed == "x", closeB = closeB())
        box?.invoke()
        au.optJSONObject("search")?.let { sr -> if (sr.optBoolean("open")) filterList(c, r, sr, act) }
    }

    /** a column header: its words, where they stand (negative = right-aligned at -x), the game's sort it stands for (null = none) */
    private class Head(val label: String, val x: Float, val sort: String?)

    /**
     * THE GAME'S SORTABLE HEADERS (f-auction-house): the words in gold, the sort's arrow beside a sorted column (bright for the first
     * sort, faint for the second; up = reversed, as the game's header arrows), touch = the add-on's "ahsort" cell, the cursor's box.
     */
    private fun sortHeads(c: Canvas, cy: Float, au: JSONObject, ctx: String, heads: List<Head>) {
        val p = UiKit.bodyPaint(22f, Theme.gold)
        val sorts = au.optJSONObject("sorts")?.optJSONArray(ctx)
        val cells = au.optJSONObject("act")?.optJSONObject("sort")
        val mine = au.optJSONObject("act")?.optString("sortCtx") == ctx
        for (h in heads) {
            p.textAlign = if (h.x < 0f) Paint.Align.RIGHT else Paint.Align.LEFT
            val ax = abs(h.x)
            c.drawText(h.label, ax, cy + 8f, p)
            val w = p.measureText(h.label)
            val l = if (h.x < 0f) ax - w else ax
            val rr = RectF(l - 14f, cy - 26f, l + w + 34f, cy + 22f)
            val s = h.sort ?: continue
            var rank = -1; var rev = false
            if (sorts != null) for (i in 0 until sorts.length()) sorts.optJSONObject(i)?.let { o -> if (o.optString("o") == s) { rank = i; rev = o.optBoolean("r") } }
            if (rank >= 0) {
                val tx = if (h.x < 0f) l - 22f else l + w + 8f
                val path = Path().apply {
                    if (rev) { moveTo(tx, cy + 4f); lineTo(tx + 14f, cy + 4f); lineTo(tx + 7f, cy - 8f) }
                    else { moveTo(tx, cy - 8f); lineTo(tx + 14f, cy - 8f); lineTo(tx + 7f, cy + 4f) }
                    close()
                }
                pFill.color = if (rank == 0) Theme.gold else Color.argb(110, 255, 209, 0)
                c.drawPath(path, pFill)
            }
            val col = if (mine) cells?.let { slotOf(it, s) } ?: -1 else -1
            if (col >= 0) hits.add(rr to "act:ahsort $ctx $s|$col")
            if (cur == "ahsort $ctx $s") UiKit.focus(c, rr)
        }
    }

    /** the game's search bar: its search box (the Chat keyboard; Done searches), Filter (its list), Search */
    private fun searchBar(c: Canvas, r: RectF, au: JSONObject, act: JSONObject?) {
        val sr = au.optJSONObject("search") ?: return
        fun col(k: String) = act?.let { slotOf(it, k) } ?: -1
        val sb = RectF(r.right - 230f, r.top + 4f, r.right, r.bottom - 4f)
        val fb = RectF(sb.left - 250f, r.top + 4f, sb.left - 14f, r.bottom - 4f)
        val box = RectF(r.left, r.top + 6f, fb.left - 14f, r.bottom - 6f)
        formBox(c, box, sr.optString("text"), "Search", "ahedit text", col("edit_text"), "ahtext")
        val open = sr.optBoolean("open")
        UiKit.dropDown(c, fb, if (sr.optBoolean("dirty")) "Filter *" else "Filter", open)
        val fcol = col(if (open) "filters_off" else "filters_on")
        if (fcol >= 0) hits.add(fb to "act:ahfilters ${if (open) "off" else "on"}|$fcol")
        if (cur == "ahfilters on") UiKit.focus(c, fb)
        val scol = col("search")
        stripButton(c, sb, "Search", if (scol >= 0) "act:ahsearch|$scol" else "why:The game gave this no tap cell", scol >= 0,
            pendingCmd == "ahsearch", null, id = "ahsearch")
    }

    /** the game's Filter list: the level range, its checkbox groups (the qualities in their colours), Clear; B or Close puts it away */
    private fun filterList(c: Canvas, r: RectF, sr: JSONObject, act: JSONObject?) {
        hits.clear()
        UiKit.cover(c, r)
        fun col(k: String) = act?.let { slotOf(it, k) } ?: -1
        val groups = arr(sr, "groups")
        val rowH = 64f
        val n = groups.sumOf { arr(it, "filters").size + (if (it.optString("name").isNotEmpty()) 1 else 0) }
        val w = 760f
        val h = min(r.height() - 80f, 120f + 100f + n * rowH + 140f)
        val box = RectF(r.centerX() - w / 2f, r.top + 40f, r.centerX() + w / 2f, r.top + 40f + h)
        val inn = UiKit.dialog(c, box)
        val lab = UiKit.bodyPaint(26f, Theme.gold)
        c.drawText("Level Range", inn.left + 10f, inn.top + 50f, lab)
        val bw = 140f
        val minB = RectF(inn.left + 220f, inn.top + 8f, inn.left + 220f + bw, inn.top + 76f)
        val maxB = RectF(minB.right + 60f, inn.top + 8f, minB.right + 60f + bw, inn.top + 76f)
        formBox(c, minB, sr.optString("min"), "", "ahedit min", col("edit_min"), "ahmin", digits = true)
        c.drawText("-", minB.right + 24f, minB.centerY() + 10f, UiKit.bodyPaint(28f, Color.WHITE))
        formBox(c, maxB, sr.optString("max"), "", "ahedit max", col("edit_max"), "ahmax", digits = true)
        val view = RectF(inn.left, inn.top + 96f, inn.right, box.bottom - 130f)
        viewB = view
        var y = view.top + 10f - scrollB
        var total = 0f
        c.save(); c.clipRect(view)
        for (g in groups) {
            val gn = g.optString("name")
            if (gn.isNotEmpty()) { c.drawText(gn, inn.left + 10f, y + 40f, UiKit.bodyPaint(24f, Theme.dim)); y += rowH; total += rowH }
            for (f in arr(g, "filters")) {
                val row = RectF(inn.left + 10f, y, inn.right - 10f, y + rowH - 6f)
                val on = f.optBoolean("on")
                UiKit.check(c, row.left + 36f, row.centerY(), 52f, on)
                val q = if (f.has("q")) f.optInt("q") else -1
                val tp = UiKit.bodyPaint(26f, if (q >= 0) Theme.quality(q) else Color.WHITE)
                c.drawText(f.optString("name"), row.left + 84f, row.centerY() - (tp.ascent() + tp.descent()) / 2f, tp)
                val cmd = "ahfilter ${f.optInt("f")}"
                val sl = slotOf(f)
                if (row.bottom > view.top && row.top < view.bottom)
                    hits.add(RectF(row.left, max(row.top, view.top), row.right, min(row.bottom, view.bottom)) to (if (sl >= 0) "act:$cmd|$sl" else "why:The game gave this no tap cell"))
                if (cur == cmd) {
                    UiKit.focus(c, row)
                    if (row.top < view.top) scrollB = max(0f, scrollB - (view.top - row.top)) else if (row.bottom > view.bottom) scrollB += row.bottom - view.bottom
                }
                y += rowH; total += rowH
            }
        }
        c.restore()
        contentB = total + 20f
        scrollB = scrollB.coerceIn(0f, max(0f, contentB - view.height()))
        val cl = col("fclear")
        val clearB = RectF(inn.left + 10f, box.bottom - 120f, inn.centerX() - 10f, box.bottom - 20f)
        val closeB = RectF(inn.centerX() + 10f, box.bottom - 120f, inn.right - 10f, box.bottom - 20f)
        stripButton(c, clearB, "Clear", if (cl >= 0) "act:ahfclear|$cl" else "why:The game gave this no tap cell", cl >= 0 && sr.optBoolean("dirty"),
            pendingCmd == "ahfclear", null, id = "ahfclear")
        val off = col("filters_off")
        stripButton(c, closeB, "Close", if (off >= 0) "act:ahfilters off|$off" else "why:Press ${Glyphs.name("b")}", true, false, UiKit.backGlyph(), always = true)
    }

    private var shownCatCur: String? = null

    // ---- the LOOT WINDOW and MAKE SPACE (UI Art's LOOTWIN_SPEC, art-kit cb53daec; the user, 2026-10-01: "Build it as drawn").
    // The loot window mirrors Forever's LootFrame (title "Items", its cards, A Loot / X Loot All / B Close, the chosen item's tooltip
    // with Currently Equipped beside it AUTOMATICALLY). Make Space opens on the game's "Inventory is full.": what is still in the loot
    // on the left, ONLY the general bags on the right in Bags' tiles, the chosen item's full tooltip BY the cursor, and the delete as a
    // HOLD on the Delete button's own A glyph (the ring fills in 1 s); blue / purple: one plain Yes / No after it.

    private var ltTouchHoldAt = 0L                 // a finger on "Hold to delete": since when
    private var ltPadHoldAt = 0L                   // the controller's A held (Home's read-only reader): since when
    private var ltPadA = false

    /**
     * The held D-pad / face buttons from Home's ONE read-only controller reader (PadInput slot bits; display only, nothing sent).
     * Only for the Make Space ring (the user, 2026-10-02: "the hold animation doesn't visibly play, it goes from empty to full"):
     * the ring starts on the physical A-down, on a deletable bag item, and drops on A-up. The addon still decides the delete.
     * Over a room no reader of Home's runs; then the ring follows the doc as before.
     */
    fun onPadHeld(mask: Int) {
        val a = PadInput.isHeld(mask, PadInput.SLOT_FACE_BOTTOM)
        if (a == ltPadA) return
        ltPadA = a
        val sp = doc?.optJSONObject("loot")?.optJSONObject("space")
        val onItem = (bagKey(cur)?.third ?: 0) > 0
        ltPadHoldAt = if (a && sp != null && sp.optBoolean("delete") && onItem) System.currentTimeMillis() else 0L
        invalidate()
    }
    private var ltHoldSeen: String? = null         // the controller's hold (the doc's space.hold.id) and since when the app saw it
    private var ltHoldSeenAt = 0L
    private var ltPick: String? = null             // a finger's chosen bag slot "bag <b> <s> <id>"

    private fun bagKey(id: String?): Triple<Int, Int, Int>? {
        val m = Regex("""^bag (\d+) (\d+) (\d+)$""").find(id ?: return null) ?: return null
        return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    private fun lootCells(bag: Int): JSONObject? =
        (doc?.optJSONObject("loot")?.optJSONObject("space") ?: doc?.optJSONObject("quest")?.optJSONObject("space"))
            ?.optJSONObject("cells")?.optJSONObject(bag.toString())

    /** a finger chose a bag slot: the app's cursor, and its tooltip asked for (the slot as the count, nothing in the game) */
    private fun lootPickBag(key: String) {
        ltPick = "bag $key"
        val (bag, sl, id) = bagKey(ltPick) ?: return
        if (id <= 0) return
        val col = lootCells(bag)?.optInt("look", -1) ?: -1
        if (col >= 0 && !dryRun && docCurrent && ageMs() < LIVE_MS) {
            if (sl > 1) TapBridge.tap(context, doc?.optJSONObject("pad"), "iqty", sl - 1) { }
            TapBridge.tap(context, doc?.optJSONObject("pad"), "ipick", col) { }
        }
    }

    /** a finger lets go of "Hold to delete": the delete went at the full second already ([ltTouchFire]); shorter = nothing */
    private fun lootHoldEnd() {
        removeCallbacks(ltTouchFire)
        val held = System.currentTimeMillis() - ltTouchHoldAt
        ltTouchHoldAt = 0L
        if (!ltTouchFired && held >= 1000L) lootHoldFire()          // the timer was late: the release still counts
        ltTouchFired = false
    }

    /** f-delete-hold-polish (the user: "it kind of freezes when its full"): the finger's delete goes AT the full second, not on the
     *  release, so the flash and the slot's change follow the fill at once (blue / purple: the addon's box first, as before) */
    private var ltTouchFired = false
    private val ltTouchFire = Runnable {
        if (pressed != "lt:hold" || ltTouchHoldAt <= 0L || System.currentTimeMillis() - ltTouchHoldAt < 980L) return@Runnable
        ltTouchFired = true
        lootHoldFire()
        invalidate()
    }

    private fun lootHoldFire() {
        val (bag, sl, id) = bagKey(cur ?: ltPick) ?: run { note("Choose an item first", false); return }
        if (id <= 0) { note("That slot is empty", false); return }
        send("lootdelc $bag", lootCells(bag)?.optInt("del", -1) ?: -1, sl)
    }

    // ---- THE DELETE LANDS AND THE SLOT CHANGES (f-delete-hold-polish): the moment the hold is full, the Delete button gets the cast
    // bar's completion glow (UiKit.buttonCastFill) and the item breaks in half over its tile, which shows EMPTY at once, waiting for the
    // game's answer (the slot really empty, then the loot's own landing flies into it); a blue / purple item does not break (the game's
    // Yes / No comes first). No answer in DZ_WAIT_MS (refused: the strip's line says why) = the item shows again.
    private var dzKey: String? = null
    private var dzAt = 0L
    private var dzRare = false
    private var dzBox: RectF? = null
    private var dzItem: JSONObject? = null
    private val DZ_WAIT_MS = 3000L
    /** the Delete button's completion glow after a full hold (the cast bar's flash, a little longer to be seen) */
    private val CAST_FINISH_MS = 450L

    private fun lootButtons(c: Canvas, strip: RectF, bs: List<Triple<String, String, String>>, enabled: (String) -> Boolean) {
        val bw = 300f; val bh = strip.height() - 30f
        var x = strip.right - 8f
        for (b in bs.reversed()) {
            val rr = RectF(x - bw, strip.centerY() - bh / 2f + 2f, x, strip.centerY() + bh / 2f + 2f)
            val on = enabled(b.third)
            UiKit.button(c, rr, b.first, b.second, if (!on) UiKit.Button.DISABLED else kitState(b.third))
            if (on) hits.add(rr to b.third)
            x -= bw + 16f
        }
    }

    private var lootCompact = false
    private var lootOpenAt = 0L
    private var lootCloseAt = 0L
    private var lootLast: JSONObject? = null
    private var prevLoot: JSONObject? = null
    private var lootRect = RectF()
    private val lootCurve = Motion.curve()

    private fun lootClosing() = lootCloseAt > 0L && (System.currentTimeMillis() - lootCloseAt < Motion.MODULE_CLOSE || fxBusy())

    // LOOTWIN_SPEC 7's beats, in ms
    private val LOOT_GROW = 320L
    private val LOOT_CLOSE = 260L
    private val LAND_PUFF = 200L
    private val LAND_FLY = 550L
    private val LAND_GLOW = 300L
    private val LAND_ALL = LAND_PUFF + LAND_FLY + LAND_GLOW + 50L + LOOT_GROW

    /**
     * THE COMPACT LOOT WINDOW + POP (LOOTWIN_SPEC 6; the user, 2026-10-01: smaller, glyph hints for buttons, B = the Home disc,
     * "a fancy animation", picked "Pop"): 600 px at the left over Home dimmed; the game's cards; the foot's hints; the tooltip
     * outside. Pop on Home's Motion timing (open 320 ms: scale 0.88 -> 1, alpha, a gold glint; close 260 ms: scale 0.95, alpha).
     * A and X act from the first frame: the addon has the buttons whatever this picture is doing.
     */
    private fun compactLoot(c: Canvas, full: RectF, lt: JSONObject, now: Long, closing: Boolean) {
        val t = (now - (if (closing) lootCloseAt else lootOpenAt)).toFloat()
        val dur = (if (closing) Motion.MODULE_CLOSE else Motion.MODULE_OPEN).toFloat()
        val x = (t / dur).coerceIn(0f, 1f)
        val k = if (closing) 1f - lootCurve.getInterpolation(x) else lootCurve.getInterpolation(x)
        if (x < 1f) postInvalidateOnAnimation()
        UiKit.cover(c, full, 0.45f * k)
        val items = arr(lt, "items").filter { it.optInt("i") != landHideFrom && vanishes.none { v -> v.i == it.optInt("i") } }
        val shownN = min(5, max(1, items.size))
        if (lootOpenAt != fxOpenSeen) { fxOpenSeen = lootOpenAt; slides.clear() }
        val ch = 84f; val gap = 10f; val cardsTop = 150f
        // CENTERED on the bottom screen (the user, 2026-10-02: "The main loot window that pops up should be centered")
        val wl = ((full.width() - 600f) / 2f).coerceAtLeast(24f)
        val r = RectF(wl, 30f, wl + 600f, 30f + cardsTop + shownN * (ch + gap) + 64f + 22f)
        lootRect = r
        val save = c.save()
        val sc = if (closing) 0.95f + 0.05f * k else 0.88f + 0.12f * k
        c.scale(sc, sc, r.centerX(), r.centerY())
        c.saveLayerAlpha(RectF(0f, 0f, full.width(), full.height()), (255 * k).toInt().coerceIn(0, 255))
        val inner = UiKit.windowGround(c, r, marble = true, floating = true)   // the ground stops at the frame (UI Art's polish)
        // the cards (more than five scroll with the cursor)
        val view = RectF(inner.left, r.top + cardsTop - 8f, inner.right, r.top + cardsTop + shownN * (ch + gap))
        viewA = view; contentA = items.size * (ch + gap)
        scrollA = scrollA.coerceIn(0f, max(0f, contentA - view.height()))
        if (!closing) follow(items.indexOfFirst { "loot ${it.optInt("i")}" == cur }, ch + gap, view.height())
        val shown = cur?.takeIf { it.startsWith("loot ") } ?: selected?.takeIf { it.startsWith("loot ") }
        var cardAt: RectF? = null; var shownItem: JSONObject? = null
        c.save(); c.clipRect(view)
        for ((i, it) in items.withIndex()) {
            val top = slideTop(it.optInt("i"), r.top + cardsTop + i * (ch + gap) - scrollA, now)
            if (top + ch < view.top || top > view.bottom) continue
            val id0 = "loot ${it.optInt("i")}"
            val sx = shakeX(id0, now)
            val card = RectF(inner.left + 6f + sx, top, inner.right - 6f + sx, top + ch)
            cardRects[it.optInt("i")] = RectF(card)
            // never a decode here (the pop animates): the warm-up's icon, or the empty slot for the frame until it lands
            UiKit.lootCard(c, card, iconReady[it.optInt("icon", 0)], it.optString("name", ""), it.optInt("q", 1), max(1, it.optInt("count", 1)),
                if (it.optBoolean("quest")) 1 else 0, it.optBoolean("coin"), it.optBoolean("locked"))
            val id = "loot ${it.optInt("i")}"
            if (!closing) hits.add(RectF(card.left, max(card.top, view.top), card.right, min(card.bottom, view.bottom)) to "lt:card $id")
            if (id == shown) { UiKit.focus(c, RectF(card.left - 4f, card.top - 6f, card.right + 4f, card.bottom + 6f)); cardAt = card; shownItem = it }
            shakeTint(c, id, card, now)
        }
        c.restore()
        // the foot: a hairline, then the glyph line (no buttons; B = the Home disc); a finger may tap each hint
        val fy = inner.bottom - 40f
        UiKit.divider(c, inner.left + 10f, fy - 30f, inner.right - 10f, fy - 30f)
        val xl = UiKit.hints(c, inner.right - 10f, fy, "rev_x" to "Loot All")
        val al = UiKit.hints(c, xl - 24f, fy, "rev_a" to "Loot")
        if (!closing) {
            val all = lt.optJSONObject("act")?.let { slotOf(it, "all") } ?: -1
            if (all >= 0 && items.isNotEmpty()) hits.add(RectF(xl - 8f, fy - 40f, inner.right - 4f, fy + 40f) to "act:lootall|$all")
            val e = shown?.let { s0 -> entries().firstOrNull { it.cmd == s0 } }
            goCmd = e; if (e == null) goWhy = "Choose an item first"
            hits.add(RectF(al - 8f, fy - 40f, xl - 16f, fy + 40f) to "go")
        }
        UiKit.windowFrame(c, r, "Items", Icons.get(133639), Color.WHITE, close = false)
        // pop: a gold glint sweeps across the frame once, in the open's last three quarters. The user, RC5: "the golden flash on the
        // animation like freezes and makes the window look frozen": it was timed 80..320 ms while the open shrank to 240 ms (Motion,
        // 10-08), so the frames stopped with the glint two-thirds across. Now it rides the open's own clock and ends with it
        val g0 = dur * 0.25f
        if (!closing && t >= g0 && t < dur) {
            val g = (t - g0) / (dur - g0)
            val gx = r.left - 200f + (r.width() + 400f) * g
            val gp = Paint().apply {
                shader = LinearGradient(gx - 120f, r.top, gx + 120f, r.top + 240f,
                    intArrayOf(Color.argb(0, 255, 209, 0), Color.argb(110, 255, 220, 120), Color.argb(0, 255, 209, 0)), null, Shader.TileMode.CLAMP)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            }
            c.save(); c.clipRect(r); c.drawRect(r, gp); c.restore()
        }
        c.restoreToCount(save)
        // the tooltip OUTSIDE at the window's right, level with the chosen card: comes last (open), goes first (close)
        val ta = if (closing) (1f - x * 2f).coerceIn(0f, 1f) else ((x - 0.6f) / 0.4f).coerceIn(0f, 1f)    // full when the open ends
        val ca = cardAt
        if (ta > 0f && ca != null && shown != null) tipFor(shown)?.let { tp ->
            // the tooltip beside the window: at its right where it fits, else at its left
            val tw = 280f
            val tx = if (r.right + 14f + tw <= full.right - 10f) r.right + 14f else max(10f, r.left - 14f - tw)
            val ty = max(r.top + 60f, ca.top - 10f)
            val layer = c.saveLayerAlpha(RectF(0f, 0f, full.width(), full.height()), (255 * ta).toInt())
            tipStack.draw(c, tx, ty, tw, shownItem?.optString("name", "") ?: "", shownItem?.optInt("q", 1) ?: 1, tp,
                r.top + 30f, full.bottom - 20f, tx + tw + 10f, compareHeld = true, steps = tipTs, hint = false)
            c.restoreToCount(layer)
        }
        if (!closing) resultLine(c, r.left + 20f, r.right - 20f, r.bottom + 30f, now)
        lootFx(c, now)
    }

    // ---- f-loot-feedback (the user, 15 Oct: "I really want to have button feedback on looting things, kind of flashing the Item and
    // sparkling away"). An item the game TOOK (its LOOT_SLOT_CLEARED: doc.lootGone, never a window closed with B) flashes bright, then
    // dissolves into the cast bar's own flakes and sparkles (the harvested castbar_* pieces, ui/cast) rising from its icon, and the cards
    // under it slide up into its place. A loot the game refused shakes its card with a red wash. Timings in LootFx; [fxClock] is the
    // clock (the off-device renders set it).

    private class Vanish(val i: Int, val item: JSONObject, val rect: RectF, val at: Long)
    private val vanishes = ArrayList<Vanish>()
    private val cardRects = HashMap<Int, RectF>()           // a slot's card as last drawn, where its sparkle starts
    private val shakes = HashMap<String, Long>()             // "loot i" -> when the game refused it
    private class Slide(var from: Float, var to: Float, var at: Long)
    private val slides = HashMap<Int, Slide>()
    private var fxOpenSeen = -1L
    private var goneSeen = -1
    /** the effects' clock (System.currentTimeMillis on the device) */
    var fxClock: () -> Long = { System.currentTimeMillis() }

    private fun fxBusy(): Boolean = vanishes.any { fxClock() - it.at < LootFx.VANISH_MS }

    /** doc.lootGone: the slots the game cleared; each starts its sparkle once, from its card as last drawn */
    private fun takeLootGone(msg: JSONObject, was: JSONObject?) {
        val g = msg.optJSONObject("lootGone") ?: return
        val n = g.optInt("n", -1)
        if (n == goneSeen) return
        val first = goneSeen < 0
        goneSeen = n
        if (first && lootOpenAt == 0L) return                     // a backlog's word, before any loot window was seen
        val slots = g.optJSONArray("slots") ?: return
        val now = fxClock()
        vanishes.removeAll { now - it.at >= LootFx.VANISH_MS }
        for (k in 0 until slots.length()) {
            val i = slots.optInt(k, -1)
            val item = arr(was, "items").firstOrNull { it.optInt("i") == i } ?: continue
            val rect = cardRects[i] ?: continue
            if (vanishes.any { it.i == i }) continue
            vanishes.add(Vanish(i, item, RectF(rect), now))
        }
        postInvalidateOnAnimation()
    }

    /** a card's top, eased from where it stood when its place changed (a card above it was taken) */
    private fun slideTop(i: Int, target: Float, now: Long): Float {
        val sl = slides[i] ?: return target.also { slides[i] = Slide(target, target, 0L) }
        if (abs(sl.to - target) > 0.5f) {
            sl.from = slideNow(sl, now); sl.to = target; sl.at = now
        }
        return slideNow(sl, now)
    }
    private fun slideNow(sl: Slide, now: Long): Float {
        if (sl.at == 0L) return sl.to
        val k = LootFx.ease(((now - sl.at).toFloat() / LootFx.SLIDE_MS).coerceIn(0f, 1f))
        if (k < 1f) postInvalidateOnAnimation()
        return sl.from + (sl.to - sl.from) * k
    }

    private fun shakeX(id: String, now: Long): Float {
        val at = shakes[id] ?: return 0f
        val t = now - at
        if (t >= LootFx.SHAKE_MS) { shakes.remove(id); return 0f }
        postInvalidateOnAnimation()
        return LootFx.shake(t)
    }
    private fun shakeTint(c: Canvas, id: String, card: RectF, now: Long) {
        val at = shakes[id] ?: return
        val a = LootFx.shakeTint(now - at)
        if (a <= 0) return
        pFill.color = Color.argb(a, 200, 20, 10)
        c.drawRoundRect(card, 8f, 8f, pFill)
    }

    private val pFxAdd = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val pFxFlash = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * every sparkle playing: the card flashes white-gold and fades toward its icon; over the icon the SAME burst as Make Space's delete
     * (UiKit.deleteBurst: Rooms' flash, ring, the game's sparkles and the thrown stars, so the two read as one family); the cast bar's
     * own flakes (UiKit.castArt) rise from it
     */
    private fun lootFx(c: Canvas, now: Long) {
        if (vanishes.isEmpty()) return
        vanishes.removeAll { now - it.at >= LootFx.VANISH_MS }
        for (v in vanishes) {
            val t = now - v.at
            val card = v.rect
            val icon = RectF(card.left + 12f, card.top + (card.height() - 60f) / 2f, card.left + 72f, card.top + (card.height() + 60f) / 2f)
            // the card itself: bright at once, then fading and shrinking toward its icon
            val ca = LootFx.cardAlpha(t)
            if (ca > 0f) {
                val sc = LootFx.cardScale(t)
                val l = c.saveLayerAlpha(RectF(card.left - 40f, card.top - 40f, card.right + 40f, card.bottom + 40f), (255 * ca).toInt())
                c.scale(sc, sc, icon.centerX(), icon.centerY())
                UiKit.lootCard(c, card, iconReady[v.item.optInt("icon", 0)], v.item.optString("name", ""), v.item.optInt("q", 1),
                    max(1, v.item.optInt("count", 1)), if (v.item.optBoolean("quest")) 1 else 0, v.item.optBoolean("coin"), false)
                // the flash: a white-gold wash over the card from its icon, added
                val fa = LootFx.flashAlpha(t)
                if (fa > 0) {
                    pFxFlash.shader = RadialGradient(icon.centerX(), icon.centerY(), card.width() * 0.75f,
                        intArrayOf(Color.argb(fa, 255, 250, 225), Color.argb(fa * 2 / 3, 255, 205, 90), Color.argb(0, 255, 190, 60)),
                        floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
                    pFxFlash.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                    c.drawRoundRect(card, 8f, 8f, pFxFlash)
                    pFxFlash.shader = null; pFxFlash.xfermode = null
                }
                c.restoreToCount(l)
            }
            // the burst over the icon: Make Space's own (one family of effects)
            val bk = t.toFloat() / LootFx.BURST_MS
            if (bk < 1f) UiKit.deleteBurst(c, RectF(icon.left - 14f, icon.top - 14f, icon.right + 14f, icon.bottom + 14f), bk)
            // the cast bar's flakes rising from the icon, turning, fading
            for (k in 0 until LootFx.N) {
                val pt = LootFx.piece(k, v.i, t) ?: continue
                val b = UiKit.castArt(LootFx.PIECES[k % LootFx.PIECES.size]) ?: continue
                val cx = icon.centerX() + pt.dx; val cy = icon.centerY() + pt.dy
                pFxAdd.alpha = pt.alpha
                c.save(); c.rotate(pt.turn, cx, cy)
                val hh = pt.size * b.height / b.width.coerceAtLeast(1)      // a flake keeps its own long shape (never a square)
                c.drawBitmap(b, null, RectF(cx - pt.size, cy - hh, cx + pt.size, cy + hh), pFxAdd)
                c.restore()
                pFxAdd.alpha = 255
            }
        }
        postInvalidateOnAnimation()
    }

    private fun lootWindow(c: Canvas, r: RectF, lt: JSONObject, now: Long) {
        val space = lt.optJSONObject("space")
        if (space != null) {
            val gt = now - growAt
            val from = growFrom
            if (growAt > 0L && gt < LOOT_GROW && from != null && from.width() > 0f) {
                // LOOTWIN 7 (400 .. 720 ms): the frame GROWS from the centred window to the full screen; Make Space fades in
                val k = lootCurve.getInterpolation(gt / LOOT_GROW.toFloat())
                UiKit.cover(c, r, 0.45f)
                val cx = from.centerX() + (r.centerX() - from.centerX()) * k
                val cy = from.centerY() + (r.centerY() - from.centerY()) * k
                val sx = from.width() / r.width() + (1f - from.width() / r.width()) * k
                val sy = from.height() / r.height() + (1f - from.height() / r.height()) * k
                val save = c.save()
                c.translate(cx - r.centerX() * sx, cy - r.centerY() * sy)
                c.scale(sx, sy)
                c.saveLayerAlpha(r, (255 * (0.35f + 0.65f * k)).toInt().coerceIn(0, 255))
                makeSpace(c, r, lt, space, now)
                c.restoreToCount(save)
                hits.clear()
                postInvalidateOnAnimation()
                return
            }
            makeSpace(c, r, lt, space, now)
            return
        }
        compactLoot(c, r, lt, now, closing = false)
        return
        val inner = UiKit.windowGround(c, r, marble = true)
        val act = lt.optJSONObject("act")
        val items = arr(lt, "items")
        val top = r.top + 156f
        val ch = 104f; val cw = 480f; val x0 = inner.left + 14f
        val strip = UiKit.strip(c, inner)
        val view = RectF(x0 - 6f, top - 8f, x0 + cw + 6f, strip.top - 8f)
        viewA = view; contentA = items.size * (ch + 14f)
        scrollA = scrollA.coerceIn(0f, max(0f, contentA - view.height()))
        follow(items.indexOfFirst { "loot ${it.optInt("i")}" == cur }, ch + 14f, view.height())
        val shown = cur?.takeIf { it.startsWith("loot ") } ?: selected?.takeIf { it.startsWith("loot ") }
        var shownItem: JSONObject? = null
        c.save(); c.clipRect(view)
        for ((k, it) in items.withIndex()) {
            val y = top + k * (ch + 14f) - scrollA
            val card = RectF(x0, y, x0 + cw, y + ch)
            val q = it.optInt("q", 1)
            UiKit.lootCard(c, card, itemIcon(it), it.optString("name", ""), q, max(1, it.optInt("count", 1)),
                if (it.optBoolean("quest")) 1 else 0, it.optBoolean("coin"), it.optBoolean("locked"))
            val id = "loot ${it.optInt("i")}"
            hits.add(RectF(card.left, max(card.top, view.top), card.right, min(card.bottom, view.bottom)) to "sel:$id")
            if (id == shown) { UiKit.focus(c, RectF(card.left - 4f, card.top - 6f, card.right + 4f, card.bottom + 6f)); shownItem = it }
        }
        c.restore()
        // the chosen item's tooltip; gear with a worn one: Currently Equipped beside it, AUTOMATICALLY (X is Loot All here)
        if (shown != null) tipFor(shown)?.let { t ->
            val tx = x0 + cw + 26f
            val tw = (inner.right - 8f - tx - 14f) / 2f
            tipStack.draw(c, tx, r.top + 112f, tw, shownItem?.optString("name", "") ?: "", shownItem?.optInt("q", 1) ?: 1, t,
                r.top + 112f, strip.top - 10f, tx + tw + 14f, compareHeld = true, steps = tipTs, hint = false)
        }
        // the game's own footer as buttons: Loot (A), Loot All (X), Close (B)
        val e = shown?.let { s0 -> entries().firstOrNull { it.cmd == s0 } }
        goCmd = e; if (e == null) goWhy = "Choose an item first"
        val all = act?.let { slotOf(it, "all") } ?: -1
        lootButtons(c, strip, listOf(Triple("Loot", "rev_a", "go"), Triple("Loot All", "rev_x", "act:lootall|$all"),
            Triple("Close", UiKit.backGlyph(), "x"))) { h -> when (h) { "go" -> e != null && e.slot >= 0; "x" -> true; else -> all >= 0 && items.isNotEmpty() } }
        resultLine(c, inner.left + 24f, strip.right - 3 * 316f - 16f, strip.centerY() + 2f, now)
        UiKit.windowFrame(c, r, "Items", Icons.get(133639), Color.WHITE, close = false)
    }

    // ---- LOOTWIN_SPEC 7 (APPROVED "as drawn", 2026-10-02): the grow, the visible hold, the puff, THE LANDING, the shrink / close
    private var landHideFrom = -1
    private val iconWarm = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val iconWarmed = HashSet<Int>()
    // icons the warm-up has DECODED (off the main thread): the loot pop draws only these, so its animation never decodes (RC3 stall)
    private val iconReady = java.util.concurrent.ConcurrentHashMap<Int, android.graphics.Bitmap>()

    /**
     * The loot window's icons decoded OFF the UI thread as its doc arrives (UI Art measured ~875 ms on the first frame after a
     * loot doc: first-time decoding), so the Pop does not hitch. Icons.get is safe off the main thread (an LruCache and a
     * duplicate of the mapped pack).
     */
    private fun warmIcons(lt: JSONObject, ld: JSONObject?) {
        val ids = ArrayList<Int>()
        for (it in arr(lt, "items")) ids.add(it.optInt("icon"))
        lt.optJSONObject("space")?.let { sp -> for (b in arr(sp, "bags")) for (it in arr(b, "items")) ids.add(it.optInt("icon")) }
        ld?.let { ids.add(it.optInt("icon")); for (x in arr(it, "items")) ids.add(x.optInt("icon")) }
        if (iconReady.size > 300) { iconReady.clear(); iconWarmed.clear() }      // bounded
        val todo = ids.filter { it > 0 && !iconReady.containsKey(it) && iconWarmed.add(it) }
        if (todo.isEmpty()) return
        iconWarm.execute {
            for (id in todo) Icons.get(id)?.let { iconReady[id] = it }
            postInvalidate()
        }
    }
    private var growAt = 0L
    private var growFrom: RectF? = null
    private var lastSpace: JSONObject? = null           // the last loot doc with Make Space: its layout is frozen for the landing
    private var landing: JSONObject? = null
    private var landN = -1
    private var landAt = 0L
    private var landLt: JSONObject? = null
    private val msTiles = HashMap<String, RectF>()      // "<bag> <slot>" -> its tile, from the last Make Space drawn
    private val msCards = ArrayList<Pair<Int, RectF>>() // (loot slot, its card)
    private var msHide: String? = null                  // that tile drawn empty
    private var msHideSet: Set<String> = emptySet()      // LOOTWIN 9: these tiles drawn empty (rewards still flying to them)
    private var msCardAlphaOf: ((Int) -> Int)? = null    // LOOTWIN 9: each reward card's alpha while it flies
    private var msStripGreen = false                     // LOOTWIN 9: the strip's line is "Room for everything: handing in"

    /** LOOTWIN 9: the extra ms of a landing of several rewards (they fly 100 ms apart) */
    private fun landExtra(ld: JSONObject?): Long {
        val n = ld?.optJSONArray("items")?.length() ?: 0
        return if (n >= 2) 100L * (n - 1) else 0L
    }
    private var msCardFade = -1
    private var msCardAlpha = 255
    private var msStripText: String? = null              // non-null: the landing (no buttons; the line, or nothing yet)

    /** the landing over the frozen Make Space; false when it is over (then the live doc draws) */
    private fun drawLanding(c: Canvas, r: RectF, now: Long): Boolean {
        val ld = landing ?: return false
        val lt = landLt ?: run { landing = null; return false }
        val sp = lt.optJSONObject("space") ?: run { landing = null; return false }
        val t = now - landAt
        val last = ld.optBoolean("last")
        val extra = landExtra(ld)
        val endT = LAND_PUFF + LAND_FLY + LAND_GLOW + 50L + extra
        if (t >= endT + (if (last) LOOT_CLOSE else LOOT_GROW)) {
            landing = null; landLt = null; lastSpace = null
            return false
        }
        val key = "${ld.optInt("bag")} ${ld.optInt("slot")}"
        val fly0 = LAND_PUFF; val fly1 = fly0 + LAND_FLY
        // after the landing: it was the last -> everything closes; else the frame SHRINKS back to the centred window
        var a = 1f; var sx = 1f; var sy = 1f; var cx = r.centerX(); var cy = r.centerY()
        if (t >= endT) {
            val x = ((t - endT).toFloat() / (if (last) LOOT_CLOSE else LOOT_GROW)).coerceIn(0f, 1f)
            val k = lootCurve.getInterpolation(x)
            if (last) { a = 1f - k; sx = 1f - 0.05f * k; sy = sx }
            else {
                val live = doc?.optJSONObject("loot")
                if (live != null && !live.has("space")) {
                    if (lootOpenAt < landAt + endT) lootOpenAt = landAt + endT
                    compactLoot(c, r, live, now, closing = false)
                }
                val to = RectF(lootRect).takeIf { it.width() > 0f } ?: RectF(r.centerX() - 300f, 30f, r.centerX() + 300f, 600f)
                a = 1f - k
                sx = 1f + (to.width() / r.width() - 1f) * k; sy = 1f + (to.height() / r.height() - 1f) * k
                cx = r.centerX() + (to.centerX() - r.centerX()) * k; cy = r.centerY() + (to.centerY() - r.centerY()) * k
            }
        }
        val save = c.save()
        c.translate(cx - r.centerX() * sx, cy - r.centerY() * sy)
        c.scale(sx, sy)
        c.saveLayerAlpha(r, (255 * a).toInt().coerceIn(0, 255))
        // LOOTWIN 9: several rewards: each flies from its card (100 ms apart) to a freed tile (the last one when there are fewer)
        val many = arr(ld, "items").takeIf { it.size >= 2 }
        val tileKeys = arr(ld, "tiles").map { "${it.optInt("bag")} ${it.optInt("slot")}" }.ifEmpty { listOf(key) }
        data class Fl(val icon: Int, val q: Int, val count: Int, val name: String, val from: Int, val tile: String, val at: Long)
        val flights = many?.mapIndexed { k, x ->
            Fl(x.optInt("icon"), x.optInt("q", 1), max(1, x.optInt("count", 1)), x.optString("name", ""), -1 - min(k, 3),
               tileKeys.getOrElse(k) { tileKeys.last() }, fly0 + 100L * k)
        } ?: listOf(Fl(ld.optInt("icon"), ld.optInt("q", 1), ld.optInt("count", 1), ld.optString("name", ""), ld.optInt("from", -1), key, fly0))
        val allLanded = fly1 + extra
        msHideSet = flights.filter { t < it.at + LAND_FLY }.map { it.tile }.toSet()
        msHide = null
        if (many != null) {
            msCardAlphaOf = { i ->
                val f = flights.lastOrNull { it.from == i }      // card 4 also stands for "+N more": it goes with the last
                if (f == null || t < f.at) 255 else (255 * (1f - (t - f.at).toFloat() / LAND_FLY)).toInt().coerceIn(0, 255)
            }
            msStripGreen = t < fly0
            val names = many.map { TipStack.strip(it.optString("name", "")).ifEmpty { "An item" } }
            msStripText = when {
                t < fly0 -> "Room for everything: handing in"
                t < allLanded -> ""
                names.size == 2 -> "${names[0]} and ${names[1]} added to your bags"
                else -> "${names[0]}, ${names[1]} and ${names.size - 2} more added to your bags"
            }
        } else {
            msHide = if (t < fly1) key else null
            msHideSet = emptySet()
            msCardFade = ld.optInt("from", -1)
            msCardAlpha = if (t < fly0) 255 else (255 * (1f - (t - fly0).toFloat() / LAND_FLY)).toInt().coerceIn(0, 255)
            val name = TipStack.strip(ld.optString("name", "")).ifEmpty { "The item" }
            msStripText = if (t >= fly1) "$name added to your bag" else ""
        }
        makeSpace(c, r, lt, sp, now)
        msHide = null; msHideSet = emptySet(); msCardAlphaOf = null; msStripGreen = false
        msCardFade = -1; msCardAlpha = 255; msStripText = null
        hits.clear()
        val tile = msTiles[key]
        if (tile != null) {
            // 1900 .. 2100: the deleted item swells 0.82 -> 1.15 and fades; a grey ring puffs out
            if (t < LAND_PUFF + 50L) {
                val x = (t.toFloat() / LAND_PUFF).coerceIn(0f, 1f)
                val gone = lt.optJSONObject("space")?.optJSONArray("bags")?.let { bags ->
                    (0 until bags.length()).mapNotNull { bags.optJSONObject(it) }
                        .firstOrNull { it.optInt("bag") == ld.optInt("bag") }?.optJSONArray("items")?.let { its ->
                            (0 until its.length()).mapNotNull { its.optJSONObject(it) }.firstOrNull { it.optInt("slot") == ld.optInt("slot") } }
                }
                if (gone != null && x < 1f) {
                    val sv = c.saveLayerAlpha(RectF(tile).apply { inset(-40f, -40f) }, (255 * (1f - x)).toInt())
                    val sc = 0.82f + (1.15f - 0.82f) * x
                    c.scale(sc, sc, tile.centerX(), tile.centerY())
                    val cm = ColorMatrix().apply { setSaturation(0f) }
                    val gp = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
                    val sv2 = c.saveLayer(RectF(tile).apply { inset(-40f, -40f) }, gp)
                    BagsView.drawSlot(c, tile, gone, bagPaints)
                    c.restoreToCount(sv2)
                    c.restoreToCount(sv)
                }
                val rx = (t.toFloat() / 250f).coerceIn(0f, 1f)
                if (rx < 1f) {
                    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.argb((180 * (1f - rx)).toInt(), 170, 170, 170) }
                    c.drawCircle(tile.centerX(), tile.centerY(), tile.width() * (0.35f + 0.45f * rx), ring)
                }
            }
        }
        for (f in flights) {
            val tile = msTiles[f.tile] ?: continue
            val f0 = f.at; val f1 = f.at + LAND_FLY; val g1 = f1 + LAND_GLOW
            // 2100 .. 2650: THE LANDING: the loot item's icon flies on an arc from its card to the freed tile
            val icon = Icons.get(f.icon)
            val qc = Theme.quality(f.q)
            if (t in f0 until f1) {
                val x = ((t - f0).toFloat() / LAND_FLY).coerceIn(0f, 1f)
                val e = lootCurve.getInterpolation(x)
                val card = msCards.firstOrNull { it.first == f.from }?.second
                val k0 = (card?.height() ?: 80f) / 46f
                val from = card?.let { RectF(it.left + 5f * k0, it.top + 4f * k0, it.left + 42f * k0, it.top + 41f * k0) }
                    ?: RectF(r.left + 60f, r.top + 200f, r.left + 120f, r.top + 260f)
                val ctrlX = (from.centerX() + tile.centerX()) / 2f
                val ctrlY = min(from.centerY(), tile.centerY()) - 160f
                val u = 1f - e
                val px = u * u * from.centerX() + 2f * u * e * ctrlX + e * e * tile.centerX()
                val py = u * u * from.centerY() + 2f * u * e * ctrlY + e * e * tile.centerY()
                val size = (from.width() + (tile.width() - from.width()) * e) * (1f + 0.15f * (1f - abs(2f * e - 1f)))
                val dst = RectF(px - size / 2f, py - size / 2f, px + size / 2f, py + size / 2f)
                if (icon != null) c.drawBitmap(icon, null, dst, null)
                c.drawRect(dst, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = qc })
            }
            // 2650 .. 2950: it lands: the item in its tile, a gold glow fading out
            if (t >= f1) {
                val landed = JSONObject().apply {
                    put("icon", f.icon); put("quality", f.q); put("count", f.count)
                    put("name", f.name); put("id", 1)
                }
                BagsView.drawSlot(c, tile, landed, bagPaints)
                if (t < g1) {
                    val gx = 1f - (t - f1).toFloat() / LAND_GLOW
                    val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = RadialGradient(tile.centerX(), tile.centerY(), tile.width() * 0.9f,
                            Color.argb((170 * gx).toInt(), 255, 210, 90), Color.argb(0, 255, 210, 90), Shader.TileMode.CLAMP)
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                    }
                    c.drawCircle(tile.centerX(), tile.centerY(), tile.width() * 0.9f, glow)
                }
            }
        }
        c.restoreToCount(save)
        return true
    }

    // ---- LOOTWIN_SPEC 8 (APPROVED "as drawn", 2026-10-05): BAGS FULL AT A QUEST GIVER
    private val MS_COLS = 6                              // Make Space's tiles per row (the addon's N.MS_COLS)
    // 1.0.9: the bag pane's scroll (content px, eased toward msScrollTo) and the tooltip's fade during a hold
    private var msScroll = 0f
    private var msScrollTo = 0f
    private var msScrollAt = 0L
    private var msFollowed: String? = null                 // the chosen tile the scroll last followed (a finger's scroll stays until it moves)
    private var msPane = RectF()                            // the bag pane as last drawn (its own touch scroll)
    private var msDrag = false
    private var msPaneAt = 0L                               // when the bag pane was last drawn (a drag there is the pane's)
    private var msTipA = 1f
    private var msTipAt = 0L
    private val MS_SCROLL_TAU_MS = 70.0
    private val MS_TIP_FADE_MS = 150f
    private val MS_EDGE = 40f                                // the pane's edge shade (36 px) and a little more
    private var qSpaceAt = 0L
    private var qSpaceGoneAt = 0L                        // when the quest's Make Space last went (a refusal within 2 s keeps its place)
    private var qStrip = RectF()                         // the quest page's strip, from its last draw

    /** the quest's Make Space as the loot's layout reads it: one card (the quest's item), the quest's name, its cells / act */
    private fun questLt(q: JSONObject, sp: JSONObject): JSONObject {
        val want = sp.optJSONObject("want")
        val items = JSONArray()
        val all = want?.let { arr(it, "items") } ?: emptyList()
        if (all.size >= 2) {
            // LOOTWIN 9: EVERY reward (the fixed ones, then the chosen one), card i = -1, -2, ...
            for ((k, x) in all.withIndex())
                items.put(JSONObject().put("i", -1 - k).put("icon", x.optInt("icon")).put("name", x.optString("name", ""))
                    .put("q", x.optInt("q", 1)).put("count", max(1, x.optInt("count", 1))))
        } else if (want != null && want.optInt("icon", 0) > 0)
            items.put(JSONObject().put("i", -1).put("icon", want.optInt("icon")).put("name", want.optString("name", ""))
                .put("q", want.optInt("q", 1)).put("count", max(1, want.optInt("count", 1))))
        return JSONObject().put("items", items).put("space", sp).put("act", sp.optJSONObject("act") ?: JSONObject())
            .put("wcQuest", want?.optString("title", "")?.ifEmpty { null } ?: q.optString("title", "The quest"))
    }

    /**
     * 0 .. 500 ms: the quest page as it was, the game's own "Inventory is full." fading in (120 .. 270) in the strip's middle;
     * 500 .. 800: Make Space SOLID underneath, the quest page fading off the top; then Make Space alone (section 7's).
     */
    private fun questSpace(c: Canvas, r: RectF, q: JSONObject, sp: JSONObject, npc: String, now: Long) {
        val t = now - (if (qSpaceAt > 0L) qSpaceAt else now)
        val qlt = questLt(q, sp)
        if (t >= 800L) { makeSpace(c, r, qlt, sp, now); return }
        if (t >= 500L) makeSpace(c, r, qlt, sp, now)
        val a = if (t < 500L) 1f else 1f - lootCurve.getInterpolation(((t - 500L) / 300f).coerceIn(0f, 1f))
        val sv = c.saveLayerAlpha(r, (255 * a).toInt().coerceIn(0, 255))
        questWindow(c, r, q, npc, now)
        val strip = qStrip
        val ra = ((t - 120L) / 150f).coerceIn(0f, 1f)
        if (ra > 0f) c.drawText("Inventory is full.", strip.centerX(), strip.top + 79f,
            UiKit.bodyPaint(32f, Color.argb((255 * ra).toInt(), 255, 26, 26)).apply { textAlign = Paint.Align.CENTER })
        c.restoreToCount(sv)
        hits.clear()                                     // no touches while the page turns into Make Space
        postInvalidateOnAnimation()
    }

    private fun makeSpace(c: Canvas, r: RectF, lt: JSONObject, sp: JSONObject, now: Long) {
        val inner = UiKit.windowGround(c, r, marble = true)
        val act = lt.optJSONObject("act")
        val top = r.top + 156f
        // LEFT: what is still in the loot
        val ch = 80f; val cw = 420f; val x0 = inner.left + 14f
        val wq = lt.optString("wcQuest", "")
        if (wq.isNotEmpty()) {
            // LOOTWIN 8: "For the quest" over the quest's name, then ONE card: the quest's item
            // right of the window's portrait ring, in the cards' text column (UI Art's check)
            c.drawText("For the quest", inner.left + 144f, top - 44f, UiKit.bodyPaint(22f, Theme.textDim))
            c.drawText(fit(UiKit.bodyPaint(26f, Theme.gold), TipStack.strip(wq), cw - 130f), inner.left + 144f, top - 12f,
                UiKit.bodyPaint(26f, Theme.gold))
        } else c.drawText("Still in the loot", inner.left + 144f, top - 10f, UiKit.bodyPaint(24f, Theme.textDim))
        msCards.clear(); msTiles.clear()
        // LOOTWIN 9 (several rewards): how many slots the rewards need, and how many are free now
        val wantQ = if (wq.isNotEmpty()) sp.optJSONObject("want") else null
        val need = wantQ?.optInt("need", 0) ?: 0
        val multi = need >= 2 && arr(lt, "items").size >= 2
        val free = if (msStripText != null && multi) need else (wantQ?.optInt("free", 0) ?: 0).coerceIn(0, need.coerceAtLeast(0))
        val shown = arr(lt, "items").take(4)
        for ((k, it) in shown.withIndex()) {
            val card = RectF(x0, top + 8f + k * (ch + 10f), x0 + cw, top + 8f + k * (ch + 10f) + ch)
            val i = it.optInt("i")
            msCards.add(i to card)
            val a = msCardAlphaOf?.invoke(i) ?: (if (i == msCardFade) msCardAlpha else 255)
            UiKit.lootCard(c, card, itemIcon(it), it.optString("name", ""), it.optInt("q", 1), max(1, it.optInt("count", 1)),
                if (it.optBoolean("quest")) 1 else 0, it.optBoolean("coin"), it.optBoolean("locked"), alpha = a)
            // the game's tick at the card's right end: its room is ready (one card per free slot, in order)
            if (multi && k < free && a > 0) UiKit.tex("check_tick")?.let { tk ->
                val tp = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = a }
                c.drawBitmap(tk, null, RectF(card.right - 52f, card.centerY() - 20f, card.right - 12f, card.centerY() + 20f), tp)
            }
        }
        if (multi) {
            var ly = top + 8f + shown.size * (ch + 10f)
            val more = arr(lt, "items").size - shown.size
            if (more > 0) { c.drawText("+$more more", x0 + 6f, ly + 22f, UiKit.bodyPaint(24f, Theme.textDim)); ly += 32f }
            val col = when {
                free <= 0 -> Color.rgb(255, 64, 64)
                free >= need -> Color.rgb(30, 255, 0)
                else -> Theme.gold
            }
            c.drawText("$free of $need slots free", x0 + 6f, ly + 32f, UiKit.bodyPaint(28f, col))
            if (free < need) c.drawText("Each item needs a slot of its own", x0 + 6f, ly + 64f, UiKit.bodyPaint(21f, Theme.textDim))
        }
        // the tiles deleted for the quest keep a thin gold rim until a reward lands in them
        val freedKeys = if (multi && msStripText == null)
            arr(sp.optJSONObject("want") ?: JSONObject(), "freed").map { "${it.optInt("bag")} ${it.optInt("slot")}" }.toSet() else emptySet()
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f
            color = Color.argb(200, Color.red(Theme.gold), Color.green(Theme.gold), Color.blue(Theme.gold)) }
        // RIGHT: only the general bags, Bags' own tiles
        val gx = x0 + cw + 40f
        // EXACTLY MS_COLS tiles to a row, each bag on its own rows: the addon's cursor walks this same layout (addon i87,
        // the user 10-06: "some of the items in the bags appear to not be selectable" -- the cursor walked 7 wide, the screen 6)
        val gap = 8f; val cols = MS_COLS
        val s = min(76f, (inner.right - 8f - gx - gap * (cols - 1)) / cols)
        val chosen = cur?.takeIf { it.startsWith("bag ") } ?: ltPick
        // 1.0.9 (the user: "when deleting Items, some of them clip off the bottom, and I cannot see them, the menu should sort of
        // dynamically scroll"): THE BAG PANE SCROLLS. Its content is laid out first; the chosen tile is kept fully in view (with its
        // bag's name over a first row), eased (MS_SCROLL_TAU_MS); a finger drags it too; a gold arrow at an edge says there is more.
        val paneTop = top - 34f
        val paneBot = inner.bottom - UiKit.STRIP - 8f
        var ly = 0f; var c0 = -1f; var c1 = -1f
        for (e in arr(sp, "bags")) {
            val b = e.optInt("bag")
            ly += 32f
            val ids = HashMap<Int, Int>()
            for (it in arr(e, "items")) ids[it.optInt("slot")] = it.optInt("id", 0)
            val n = e.optInt("slots", 0)
            for (k in 0 until n) {
                val sl = k + 1
                if ("bag $b $sl ${ids[sl] ?: 0}" == chosen) {
                    val by = ly + (k / cols) * (s + gap)
                    // clear of the edge's shade and arrow (MS_EDGE): the bag's name over a first row, the shade's height otherwise
                    c0 = if (k < cols) by - 32f - 8f else by - MS_EDGE; c1 = by + s + MS_EDGE
                }
            }
            ly += ((n + cols - 1) / cols) * (s + gap) + 6f
        }
        if ((sp.optJSONArray("skipped")?.length() ?: 0) > 0) ly += 30f
        ly += 8f
        val paneH = paneBot - paneTop
        val maxScroll = max(0f, ly - paneH)
        if (chosen != msFollowed && c0 >= 0f) {
            msFollowed = chosen
            if (c0 < msScrollTo) msScrollTo = c0 else if (c1 > msScrollTo + paneH) msScrollTo = c1 - paneH
        }
        msScrollTo = msScrollTo.coerceIn(0f, maxScroll)
        val dtS = if (msScrollAt > 0L) (now - msScrollAt).coerceIn(0L, 100L) else 0L
        msScrollAt = now
        msScroll = if (abs(msScrollTo - msScroll) < 3f) msScrollTo
            else msScroll + (msScrollTo - msScroll) * (1f - kotlin.math.exp(-dtS / MS_SCROLL_TAU_MS)).toFloat()
        msScroll = msScroll.coerceIn(0f, maxScroll)
        if (msScroll != msScrollTo) postInvalidateOnAnimation()
        msPane = RectF(gx - 6f, paneTop, inner.right, paneBot); msPaneAt = System.currentTimeMillis()
        val sc0 = c.save()
        c.clipRect(msPane)
        var y = paneTop - msScroll
        // the hold, known before the tiles are drawn (the chosen item sinks and greys with it)
        val holdIdE = sp.optJSONObject("hold")?.optString("id")
        val heldE = if (msStripText != null) 0f else max(max(
            if (holdIdE != null && holdIdE == ltHoldSeen) ((now - ltHoldSeenAt) / 1000f).coerceIn(0f, 1f) else 0f,
            if (ltTouchHoldAt > 0L && pressed == "lt:hold") ((now - ltTouchHoldAt) / 1000f).coerceIn(0f, 1f) else 0f),
            if (ltPadHoldAt > 0L) ((now - ltPadHoldAt) / 1000f).coerceIn(0f, 1f) else 0f)
        var chosenBox: RectF? = null
        var chosenItem: JSONObject? = null
        if (dzKey != null && now - dzAt > DZ_WAIT_MS) { dzKey = null; dzBox = null }
        UiKit.asNumber(bagPaints.count, shadow = false)
        for (e in arr(sp, "bags")) {
            val b = e.optInt("bag")
            c.drawText(e.optString("name", "Bag"), gx + 2f, y + 24f, UiKit.bodyPaint(22f, Theme.textDim))
            y += 32f
            val byS = HashMap<Int, JSONObject>()
            for (it in arr(e, "items")) byS[it.optInt("slot")] = it
            val n = e.optInt("slots", 0)
            for (k in 0 until n) {
                val sl = k + 1
                val bx = gx + (k % cols) * (s + gap); val by = y + (k / cols) * (s + gap)
                val box = RectF(bx, by, bx + s, by + s)
                val it = byS[sl]
                val key = "bag $b $sl ${it?.optInt("id", 0) ?: 0}"
                msTiles["$b $sl"] = box
                // the moment the hold is full: the flash starts on this tile, once per hold
                if (key == chosen && heldE >= 1f && it != null && dzKey != key && msStripText == null) {
                    dzKey = key; dzAt = now; dzBox = RectF(box); dzItem = it; dzRare = it.optInt("quality", 1) >= 3
                }
                val dzT = if (key == dzKey) now - dzAt else -1L
                when {
                    msHide == "$b $sl" || "$b $sl" in msHideSet -> BagsView.drawSlot(c, box, null, bagPaints)
                    // the moment it is full the item breaks over its EMPTY slot (UiKit.itemBreak, drawn below), and the slot stays
                    // empty while the game answers
                    dzT >= 0 && !dzRare -> BagsView.drawSlot(c, box, null, bagPaints)
                    key == chosen && heldE > 0f && it != null -> {
                        // LOOTWIN 7 (900 .. 1900 ms): the item sinks to 0.82 and loses its colour with the hold
                        BagsView.drawSlot(c, box, null, bagPaints)
                        val cm = ColorMatrix().apply { setSaturation(1f - heldE) }
                        val lp = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
                        val sv = c.saveLayer(box, lp)
                        val sc = 1f - 0.18f * heldE
                        c.scale(sc, sc, box.centerX(), box.centerY())
                        BagsView.drawSlot(c, box, it, bagPaints)
                        c.restoreToCount(sv)
                        if (dzT < 0) UiKit.holdTile(c, box, heldE)
                    }
                    else -> BagsView.drawSlot(c, box, it, bagPaints)
                }
                if ("$b $sl" in freedKeys) c.drawRect(RectF(box).apply { inset(1.5f, 1.5f) }, rim)
                if (box.bottom > msPane.top && box.top < msPane.bottom)
                    hits.add(RectF(box.left, max(box.top, msPane.top), box.right, min(box.bottom, msPane.bottom)) to "lt:$key")
                if (key == chosen) { chosenBox = box; chosenItem = it }
            }
            y += ((n + cols - 1) / cols) * (s + gap) + 6f
        }
        val skipped = arr(sp, "skipped").let { a -> (0 until (sp.optJSONArray("skipped")?.length() ?: 0)).mapNotNull { sp.optJSONArray("skipped")?.optString(it) } }
        if (skipped.isNotEmpty())
            c.drawText("Not shown: your " + skipped.joinToString(" and ") { it.lowercase() } + " (they can't hold this loot)",
                gx + 2f, y + 22f, UiKit.bodyPaint(20f, Theme.dim))
        chosenBox?.let { UiKit.focus(c, it) }
        c.restoreToCount(sc0)
        // more above / below: a soft shade at that edge and the kit's small gold arrow in it
        if (maxScroll > 1f) {
            val sh = Paint()
            if (msScroll > 1f) {
                sh.shader = LinearGradient(0f, msPane.top, 0f, msPane.top + MS_EDGE - 4f, Color.argb(150, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                c.drawRect(msPane.left, msPane.top, msPane.right, msPane.top + MS_EDGE - 4f, sh)
                UiKit.moveArrowsUpDown(c, RectF(msPane.left, msPane.top + 4f, msPane.right, msPane.top + 30f), up = true, down = false,
                    size = 18f, width = 26f, around = true)
            }
            if (msScroll < maxScroll - 1f) {
                sh.shader = LinearGradient(0f, msPane.bottom - MS_EDGE + 4f, 0f, msPane.bottom, Color.TRANSPARENT, Color.argb(150, 0, 0, 0), Shader.TileMode.CLAMP)
                c.drawRect(msPane.left, msPane.bottom - MS_EDGE + 4f, msPane.right, msPane.bottom, sh)
                UiKit.moveArrowsUpDown(c, RectF(msPane.left, msPane.bottom - 30f, msPane.right, msPane.bottom - 4f), up = false, down = true,
                    size = 18f, width = 26f, around = true)
            }
        }
        // the tooltip, out of the way while the hold runs (the user: "Once I start to hold A, I want the tooltip to fade out of the
        // way"): to 0 in MS_TIP_FADE_MS from the hold's start, back as fast when it is let go early
        val dtT = if (msTipAt > 0L) (now - msTipAt).coerceIn(0L, 100L) else 0L
        msTipAt = now
        val tipWant = if (heldE > 0f || dzKey != null) 0f else 1f
        msTipA = if (tipWant < msTipA) max(tipWant, msTipA - dtT.toFloat() / MS_TIP_FADE_MS) else min(tipWant, msTipA + dtT.toFloat() / MS_TIP_FADE_MS)
        if (msTipA != tipWant) postInvalidateOnAnimation()
        // the item breaks in half, greys and fades (the user's pick for the items); a blue / purple one waits for the game's Yes / No
        dzBox?.let { bx ->
            val t = now - dzAt; val itm = dzItem
            if (!dzRare && itm != null && t < UiKit.ITEM_BREAK_MS)
                UiKit.itemBreak(c, bx, t.toFloat() / UiKit.ITEM_BREAK_MS) { cc -> BagsView.drawSlot(cc, bx, itm, bagPaints) }
        }
        if (dzKey != null) postInvalidateOnAnimation()
        // THE TOOLTIP BY THE CURSOR: beside the chosen slot, flipped left at the edge, raised above the strip, never cut
        val strip = UiKit.strip(c, inner)
        if (chosen != null && chosenBox != null && msStripText == null && msTipA > 0.01f && chosenBox!!.bottom > msPane.top &&
            chosenBox!!.top < msPane.bottom) tipFor(chosen)?.let { t ->
            val box = chosenBox!!
            val tl = c.saveLayerAlpha(null, (255 * msTipA).toInt())
            val tw = 420f; val g = 12f
            var ty = box.top
            val tx = when {
                box.right + g + tw <= inner.right - 8f -> box.right + g
                box.left - g - tw >= gx - 4f -> box.left - g - tw
                else -> { ty = box.bottom + g; val hi = inner.right - 8f - tw; if (hi >= gx) (box.centerX() - tw / 2f).coerceIn(gx, hi) else gx }
            }
            tipStack.draw(c, tx, ty, tw, chosenItem?.let { TipStack.strip(it.optString("name", "")) }?.takeIf { it.isNotEmpty() }
                ?: (t.optJSONArray("lines")?.optJSONObject(0)?.optString("t") ?: ""), chosenItem?.optInt("quality", 1) ?: 1, t,
                r.top + 112f, inner.bottom - UiKit.STRIP - 10f, tx, compareHeld = false, steps = tipTs, hint = false)
            c.restoreToCount(tl)
        }
        // the strip: the words; Hold to delete (A) with its ring; Back (B)
        val bw = 300f; val bh = strip.height() - 30f
        val back = RectF(strip.right - 8f - bw, strip.centerY() - bh / 2f + 2f, strip.right - 8f, strip.centerY() + bh / 2f + 2f)
        val del = RectF(back.left - 16f - 340f, back.top, back.left - 16f, back.bottom)
        val holdId = sp.optJSONObject("hold")?.optString("id")
        if (holdId != ltHoldSeen) { ltHoldSeen = holdId; ltHoldSeenAt = now }
        val ctlHeld = if (holdId != null) ((now - ltHoldSeenAt) / 1000f).coerceIn(0f, 1f) else 0f
        val touchHeld = if (ltTouchHoldAt > 0L && pressed == "lt:hold") ((now - ltTouchHoldAt) / 1000f).coerceIn(0f, 1f) else 0f
        val padHeld = if (ltPadHoldAt > 0L) ((now - ltPadHoldAt) / 1000f).coerceIn(0f, 1f) else 0f
        val held = if (msStripText != null) 0f else max(max(ctlHeld, touchHeld), padHeld)
        val canDel = chosenItem != null
        // the landing: the strip's buttons are gone, its line says what landed
        msStripText?.let { line ->
            if (line.isNotEmpty()) c.drawText(line, strip.left + 20f, strip.centerY() + 10f,
                if (msStripGreen) UiKit.bodyPaint(26f, Color.rgb(30, 255, 0)) else UiKit.bodyPaint(28f, Theme.gold))
            UiKit.windowFrame(c, r, "Inventory is full.", Icons.get(133639), Color.rgb(255, 32, 32), close = false)
            return
        }
        // THE BUTTON FILLS LIKE THE GAME'S CAST BAR (the user's pick of three, 15 Oct: option 1): its plate fills left to right with the
        // cast bar's fill and spark as the hold goes, and the moment it is full the cast bar's completion glow lights the whole button
        // and fades (CAST_FINISH_MS); the item above breaks meanwhile (UiKit.itemBreak). The glyph and the words stay on top.
        val finishT = if (dzKey != null) now - dzAt else -1L
        val finish = if (finishT in 0 until CAST_FINISH_MS) finishT.toFloat() / CAST_FINISH_MS else -1f
        UiKit.buttonCastFill(c, del, "Hold to delete", "rev_a",
            if (!canDel) UiKit.Button.DISABLED else if (held > 0f) UiKit.Button.DOWN else UiKit.Button.UP,
            if (finish >= 0f) 1f else held, finish)
        if (canDel) hits.add(del to "lt:hold")
        UiKit.button(c, back, if (sp.optJSONObject("want")?.optBoolean("anyway") == true) "Accept anyway" else "Back", UiKit.backGlyph(),
            kitState("act:lootback"))
        act?.let { slotOf(it, "back") }?.let { if (it >= 0) hits.add(back to "act:lootback|$it") }
        if (held in 0.001f..0.999f) postInvalidateOnAnimation()
        // what the game / the addon answered takes the words' place for its 4 s
        if (!resultLine(c, strip.left + 20f, del.left - 20f, strip.centerY() + 2f, now))
            c.drawText("Choose what to delete to make space", strip.left + 20f, strip.centerY() + 10f, UiKit.bodyPaint(26f, Theme.textDim))
        UiKit.windowFrame(c, r, "Inventory is full.", Icons.get(133639), Color.rgb(255, 32, 32), close = false)

        // BLUE / PURPLE: one plain Yes / No after the hold
        sp.optJSONObject("rare")?.let { rr ->
            hits.clear()
            UiKit.cover(c, r)
            val w = r.width()
            val d = RectF((w - 1000f) / 2f, 270f, (w + 1000f) / 2f, 690f)
            UiKit.dialog(c, d)
            val q = rr.optInt("q", 3)
            c.drawText("Delete this " + (if (q >= 4) "Epic" else "Rare") + " item?", w / 2f, d.top + 100f,
                UiKit.bodyPaint(28f, Color.WHITE).apply { textAlign = Paint.Align.CENTER })
            // the name in its quality colour; never a blank line (the tooltip's first line when the addon could not read it)
            val rname = rr.optString("name", "").ifEmpty { doc?.optJSONObject("tip")?.optJSONArray("lines")?.optJSONObject(0)?.optString("t", "") ?: "" }
                .ifEmpty { "this item" }
            c.drawText(TipStack.strip(rname), w / 2f, d.top + 156f, UiKit.bodyPaint(38f, Theme.quality(q)).apply { textAlign = Paint.Align.CENTER })
            c.drawText("This cannot be undone.", w / 2f, d.top + 202f, UiKit.bodyPaint(26f, Theme.dim).apply { textAlign = Paint.Align.CENTER })
            val yes = RectF(w / 2f - 14f - 380f, d.bottom - 72f - 111f, w / 2f - 14f, d.bottom - 72f)
            val no = RectF(w / 2f + 14f, yes.top, w / 2f + 14f + 380f, yes.bottom)
            UiKit.button(c, yes, "Yes", "rev_a", kitState("lt:yes"))
            UiKit.button(c, no, "No", UiKit.backGlyph(), kitState("act:lootno"))
            val bag = rr.optInt("bag"); val sl = rr.optInt("slot")
            val rc = lootCells(bag)?.optInt("rare", -1) ?: -1
            if (rc >= 0) hits.add(yes to "lt:yes $bag $sl $rc")
            act?.let { slotOf(it, "no") }?.let { if (it >= 0) hits.add(no to "act:lootno|$it") }
        }
    }

    // ---- the BANK (UI Art's BANK_SPEC, art-kit d920870c; user, 2026-09-30: "Yes, build it"): the bank on the left (a page per
    // bought tab, its page tabs when there are more), your bags on the right in the Bags room's own tiles; the gold box walks
    // both; the strip's one action is Deposit or Withdraw by the side the cursor is on; Y Move; X Purchase. Every move is
    // Bags' own in the game (the addon calls WC.bagsMove); a finger's slot travels as the count cell before one cell.

    private val bagPaints = BagsView.Paints()
    private val bankGaps = UiKit.TabGaps(vertical = false)       // the bank's Bank / Account Bank row
    private var bkScroll = 0f
    private var bkGridScroll = 0f
    private var bkGridShown: String? = null
    private var bkGridBag = -99
    private var bkShownCur: String? = null

    /** "bkslot <bag> <slot>" -> (bag, slot) */
    private fun bkSlotOf(id: String?): Pair<Int, Int>? {
        val m = Regex("""^bkslot (-?\d+) (\d+)$""").find(id ?: return null) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    private fun bankTap(what: String) {
        val bk = doc?.optJSONObject("bank") ?: return
        val cells = bk.optJSONObject("cells")
        fun cell(bag: Int, k: String) = cells?.optJSONObject(bag.toString())?.optInt(k, -1) ?: -1
        when {
            what.startsWith("slot ") -> {
                val id = "bkslot " + what.substring(5)
                val hand = bk.optJSONObject("hand")
                if (hand != null) {
                    val (bag, sl) = bkSlotOf(id) ?: return
                    send("bkput $bag", cell(bag, "put"), sl)
                } else selected = id
            }
            what == "go" -> {
                val (bag, sl) = bkSlotOf(cur ?: selected) ?: run { note("Choose a slot first", false); return }
                send("bkdo $bag", cell(bag, "go"), sl)
            }
            what == "move" -> {
                val (bag, sl) = bkSlotOf(cur ?: selected) ?: run { note("Choose a slot first", false); return }
                send("bkpick $bag", cell(bag, "pick"), sl)
            }
        }
    }

    private fun bankWindow(c: Canvas, r: RectF, bk: JSONObject, now: Long) {
        val inner = UiKit.windowGround(c, r, marble = true)
        val act = bk.optJSONObject("act")
        val purse = bk.optLong("money", 0)
        val acct = bk.optBoolean("acct")
        // ---- the top tabs: Bank, and the account bank while the game allows it
        val ty = r.top + 94f; val th = 108f
        // LB / RB switch these two (the add-on, only with an account bank): the kit's gap only beside the CHOSEN tab, animated when
        // it changes (the user after b118: "the spacing should only be between the currently selected tab. swaping between tabs
        // should animate this spacing"), the shoulder glyph in it (art-kit 494b1830)
        val hasAcct = bk.optBoolean("account")
        val row = bankGaps.row(if (hasAcct) 2 else 1, 176f, inner.right - 20f, ty + 4f, th - 4f, 290f, if (acct) 1 else 0, this)
        val tb = RectF(row[0]).apply { if (acct) top = ty + 12f }
        UiKit.tab(c, tb, "Bank", !acct)
        act?.let { slotOf(it, "char") }?.let { if (it >= 0) hits.add(tb to "act:bktab char|$it") }
        if (hasAcct) {
            val ta = RectF(row[1]).apply { if (!acct) top = ty + 12f }
            UiKit.tab(c, ta, "Account Bank", acct)
            act?.let { slotOf(it, "acct") }?.let { if (it >= 0) hits.add(ta to "act:bktab acct|$it") }
            bankGaps.badges(c)          // L and R on the row's outsides, always (the user, 15 Oct)
        }
        pFill.color = Color.rgb(96, 80, 50)
        c.drawRect(inner.left - 6f, ty + th, inner.right + 6f, ty + th + 2f, pFill)
        val top = ty + th + 10f
        val strip = UiKit.strip(c, inner)
        val bot = strip.top - 10f
        val half = (inner.left + inner.right) / 2f
        val hand = bk.optJSONObject("hand")
        val handId = hand?.let { "bkslot ${it.optInt("bag")} ${it.optInt("slot")}" }
        val pointed = cur ?: selected
        var pointedBox: RectF? = null
        var pointedItem: JSONObject? = null

        // ---- left: the bank
        val bkr = RectF(inner.left + 4f, top, half - 8f, bot)
        UiKit.bankGround(c, bkr)
        val pages = bk.optInt("pages", 1); val page = bk.optInt("page", 1)
        val tabW = if (pages > 1) 70f else 0f
        if (pages > 1) {
            val tabs = arr(bk, "tabs")
            val pc = bk.optJSONArray("pageCells")
            for (p in 0 until pages) {
                val rr = RectF(bkr.right - 72f, bkr.top + 10f + p * 80f, bkr.right - 2f, bkr.top + 86f + p * 80f)
                UiKit.sideTab(c, rr, Icons.get(tabs.getOrNull(p)?.optInt("icon", 0)?.takeIf { it > 0 }), p + 1 == page,
                    cursor = cur == "bkpage ${p + 1}")
                val col = pc?.optInt(p, -1) ?: -1
                if (col >= 0) hits.add(rr to "act:bkpage ${p + 1}|$col")
            }
        }
        val head = if (acct) "Account Bank" else "Bank"
        c.drawText(if (pages > 1) "$head: page $page" else head, bkr.left + 22f, bkr.top + 38f, UiKit.bodyPaint(28f, Theme.gold))
        val sz = if (pages > 1) 68f else 72f
        val gx0 = bkr.left + 16f; val gw = bkr.right - 16f - tabW - gx0
        val cellW = gw / 7f
        val bag = bk.optInt("bag", -99)
        val n = bk.optInt("n", 0)
        val items = HashMap<Int, JSONObject>()
        for (it in arr(bk, "slots")) items[it.optInt("slot")] = it
        // the grid scrolls with the cursor when a tab has more slots than fit (UI Art: the 98-slot account tab), clipped
        // above the divider; the page tabs stay the way between pages
        val gTop = bkr.top + 58f
        val gMax = bkr.bottom - 210f                    // room kept for the divider, the bag slots and the cost
        val gRows = (n + 6) / 7
        val gContent = gRows * 80f
        val gView = min(gContent, max(80f, gMax - gTop))
        val pk = bkSlotOf(pointed)?.takeIf { it.first == bag }?.second
        if (pk != null && pointed != bkGridShown) {
            bkGridShown = pointed
            val rt = ((pk - 1) / 7) * 80f
            if (rt < bkGridScroll) bkGridScroll = rt else if (rt + 80f > bkGridScroll + gView) bkGridScroll = rt + 80f - gView
        }
        if (bag != bkGridBag) { bkGridBag = bag; bkGridScroll = 0f }
        bkGridScroll = bkGridScroll.coerceIn(0f, max(0f, gContent - gView))
        val gClip = RectF(bkr.left, gTop - 6f, bkr.right - tabW, gTop + gView)
        var rowsBottom = gTop + gView - 8f
        c.save(); c.clipRect(gClip)
        for (sl in 1..n) {
            val k = sl - 1
            val x = gx0 + (k % 7) * cellW + (cellW - sz) / 2f
            val y = gTop + (k / 7) * 80f - bkGridScroll
            if (y + sz < gClip.top || y > gClip.bottom) continue
            val box = RectF(x, y, x + sz, y + sz)
            val it = items[sl]
            val id = "bkslot $bag $sl"
            UiKit.bankSlot(c, box, if (id == handId) null else it?.let { o -> Icons.get(o.optInt("icon", 0).takeIf { v -> v > 0 }) },
                it?.optInt("quality", -1) ?: -1, it?.optInt("count", 0) ?: 0)
            hits.add(RectF(box.left, max(box.top, gClip.top), box.right, min(box.bottom, gClip.bottom)) to "bk:slot $bag $sl")
            if (pointed == id) { pointedBox = box; pointedItem = it }
        }
        c.restore()
        UiKit.bankDivider(c, bkr.left + 14f, bkr.right - 14f - tabW, rowsBottom + 14f)
        val gold24 = UiKit.bodyPaint(24f, Theme.gold)
        c.drawText("Bag Slots:", bkr.left + 22f, rowsBottom + 14f + 42f, gold24)
        val tabs = arr(bk, "tabs")
        val maxTabs = bk.optInt("maxTabs", tabs.size)
        val bagRowY = rowsBottom + 14f + 58f
        val bw = (bkr.right - 16f - tabW - (bkr.left + 16f)) / 6f
        for (k in 1 until min(maxTabs, 7)) {
            val x = bkr.left + 16f + (k - 1) * bw + (bw - 64f) / 2f
            val box = RectF(x, bagRowY, x + 64f, bagRowY + 64f)
            val t = tabs.getOrNull(k)
            UiKit.bankSlot(c, box, t?.let { Icons.get(it.optInt("icon", 0).takeIf { v -> v > 0 }) }, bag = true, locked = t == null)
        }
        val cost = bk.optLong("cost", -1)
        if (cost >= 0 && tabs.size < maxTabs) {
            val cy0 = bagRowY + 64f + 34f
            c.drawText("Cost:", bkr.left + 22f, cy0 + 8f, gold24)
            UiKit.money(c, cost, bkr.left + 100f, cy0, 26f, if (cost <= purse) Theme.text else Theme.unusable)
            val pb = RectF(bkr.right - tabW - 250f, cy0 - 40f, bkr.right - tabW - 16f, cy0 + 40f)
            UiKit.button(c, pb, "Purchase", "rev_x", if (cost <= purse) UiKit.Button.UP else UiKit.Button.DISABLED)
            act?.let { slotOf(it, "box") }?.let { if (it >= 0 && cost <= purse) hits.add(pb to "act:bkbox|$it") }
        }

        // ---- right: your bags, in the Bags room's own tiles (BagsView.drawSlot), 7 to a row; they scroll with the cursor
        val br = RectF(half + 8f, top, inner.right - 4f, bot)
        c.drawText("Your Bags", br.left + 22f, br.top + 38f, UiKit.bodyPaint(28f, Theme.gold))
        val view = RectF(br.left, br.top + 52f, br.right, br.bottom)
        val bcw = (br.width() - 32f) / 7f
        val list = ArrayList<Pair<String, JSONObject?>>()
        for (e in arr(bk, "bags")) {
            val b = e.optInt("bag"); val its = HashMap<Int, JSONObject>()
            for (it in arr(e, "items")) its[it.optInt("slot")] = it
            for (sl in 1..e.optInt("slots", 0)) list.add("bkslot $b $sl" to its[sl])
        }
        val rows = (list.size + 6) / 7
        val content = rows * 80f + 6f
        val pIdx = list.indexOfFirst { it.first == pointed }
        if (pIdx >= 0 && pointed != bkShownCur) {
            bkShownCur = pointed
            val rt = (pIdx / 7) * 80f
            if (rt < bkScroll) bkScroll = rt else if (rt + 80f > bkScroll + view.height()) bkScroll = rt + 80f - view.height()
        }
        bkScroll = bkScroll.coerceIn(0f, max(0f, content - view.height()))
        UiKit.asNumber(bagPaints.count, shadow = false)
        c.save(); c.clipRect(view)
        for ((k, pr) in list.withIndex()) {
            val x = br.left + 16f + (k % 7) * bcw + (bcw - 72f) / 2f
            val y = view.top + 6f + (k / 7) * 80f - bkScroll
            if (y + 72f < view.top || y > view.bottom) continue
            val box = RectF(x, y, x + 72f, y + 72f)
            BagsView.drawSlot(c, box, if (pr.first == handId) null else pr.second, bagPaints, dimmed = pr.second?.optBoolean("locked") == true)
            hits.add(RectF(box.left, max(box.top, view.top), box.right, min(box.bottom, view.bottom)) to "bk:slot ${pr.first.removePrefix("bkslot ")}")
            if (pointed == pr.first) { pointedBox = box; pointedItem = pr.second }
        }
        c.restore()

        // ---- the cursor (the gold box) and the item in the hand riding its corner
        pointedBox?.let { b ->
            UiKit.focus(c, b)
            if (hand != null) {
                val hb = hand.optInt("bag"); val hs = hand.optInt("slot")
                val hit = if (hb == bag) items[hs] else arr(bk, "bags").firstOrNull { it.optInt("bag") == hb }?.let { e -> arr(e, "items").firstOrNull { it.optInt("slot") == hs } }
                UiKit.slot(c, hit?.let { Icons.get(it.optInt("icon", 0).takeIf { v -> v > 0 }) }, b.left - 22f, b.top - 22f, 52f)
            }
        }

        // ---- the strip: your money; Deposit / Withdraw by the side the cursor is on; Y Move (the hand: A Put here, B Cancel)
        val cy = strip.centerY() + 2f
        val bx = inner.right - 8f
        c.drawText("Your money", inner.left + 24f, cy - 14f, UiKit.bodyPaint(22f, Theme.dim))
        UiKit.money(c, purse, inner.left + 24f, cy + 20f, 30f)
        if (hand != null) {
            val hx = UiKit.hints(c, bx - 16f, cy, "rev_a" to "Put here", UiKit.backGlyph() to "Cancel")
            act?.let { slotOf(it, "cancel") }?.let { if (it >= 0) hits.add(RectF(hx, cy - 44f, bx, cy + 44f) to "act:bkcancel|$it") }
        } else {
            val onBank = bkSlotOf(pointed)?.first == bag
            val label = if (onBank) "Withdraw" else "Deposit"
            val ok = pointedItem != null
            val gb = RectF(bx - 280f, cy - 55f, bx, cy + 56f)
            val gst = kitState("bk:go", if (ok && pendingCmd == null) UiKit.Button.UP else UiKit.Button.DISABLED)
            if (cur != null) UiKit.button(c, gb, label, "rev_a", gst) else UiKit.button(c, gb, label, gst)
            if (ok) { hits.add(gb to "bk:go"); glyphHits["rev_a"] = "bk:go" }
            val mx = UiKit.hints(c, bx - 300f, cy, "rev_y" to "Move")
            if (ok) { hits.add(RectF(mx, cy - 44f, bx - 290f, cy + 44f) to "bk:move"); glyphHits["rev_y"] = "bk:move" }
        }
        resultLine(c, inner.left + 260f, bx - 480f, cy, now)

        // ---- the purchase box (the next bank tab), its Yes = the game's PurchaseBankTab
        if (bk.optBoolean("box")) {
            hits.clear()
            UiKit.cover(c, r)
            val box = RectF(r.centerX() - 520f, 215f, r.centerX() + 520f, 215f + 390f)
            val ib = UiKit.dialog(c, box)
            // the game's own words (CONFIRM_BUY_CHARACTER_BANK_TAB / CONFIRM_BUY_ACCOUNT_BANK_TAB), then the cost
            val words = if (acct) "Do you want to purchase an Account Bank tab for:" else "Do you want to purchase a Bank tab for:"
            val tp = TextPaint(UiKit.bodyPaint(30f, Color.WHITE))
            val lay = StaticLayout.Builder.obtain(words, 0, words.length, tp, (ib.width() - 80f).toInt()).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            c.save(); c.translate(ib.left + 40f, ib.top + 30f); lay.draw(c); c.restore()
            val mw = UiKit.moneyWidth(max(0L, cost), 34f)
            UiKit.money(c, max(0L, cost), ib.centerX() - mw / 2f, ib.top + 60f + lay.height + 20f, 34f, Color.WHITE)
            val yb = ib.bottom - 111f - 24f
            val yes = RectF(ib.centerX() - 400f, yb, ib.centerX() - 20f, yb + 111f)
            val no = RectF(ib.centerX() + 20f, yb, ib.centerX() + 400f, yb + 111f)
            UiKit.button(c, yes, "Yes", "rev_a", kitState("act:bkbuy", if (cost in 0..purse) UiKit.Button.UP else UiKit.Button.DISABLED))
            UiKit.button(c, no, "No", UiKit.backGlyph(), kitState("act:bkcancel"))
            act?.let { slotOf(it, "buy") }?.let { if (it >= 0 && cost in 0..purse) hits.add(yes to "act:bkbuy|$it") }
            act?.let { slotOf(it, "cancel") }?.let { if (it >= 0) hits.add(no to "act:bkcancel|$it") }
        }
        val name = bk.optJSONObject("npc")?.optString("name", "")?.takeIf { it.isNotEmpty() } ?: "Bank"
        UiKit.windowFrame(c, r, name, UiKit.tex("mm_banker"), Theme.gold, closeDown = pressed == "x", closeB = closeB())
    }

    // ---- the FLIGHT MASTER (UI Art's FLIGHT_SPEC 2; user 2026-09-30: "1, the game's view: the whole continent")
    // The continent's map (the Map session's assets/maps/<uiMap>.webp, 0..1 = the game's own node positions), the chosen
    // point's route under the points, the cursor's ring, the game's tooltip at the point's top right; Fly in the strip.

    private var taxiArtId = -1
    private var taxiArt: Bitmap? = null

    private fun taxiMap(id: Int): Bitmap? {
        if (id != taxiArtId) {
            taxiArtId = id
            // the continent's painted map from the art harvest (MapHarvestSteps.WORLD_MAP: worldmap/<uiMap>/base.webp, 0..1 = the
            // uiMap's own space, as the bundled assets/maps picture was; a continent's art is complete in its base layer). The APK
            // carries no game art since the harvest (the user 2026-10-09).
            taxiArt = try { ArtHarvest.file("worldmap/$id/base.webp").takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) } }
                catch (e: Exception) { null }
        }
        return taxiArt
    }

    private fun taxiWindow(c: Canvas, r: RectF, tx: JSONObject, now: Long) {
        val inner = UiKit.windowGround(c, r, marble = true)
        val purse = tx.optLong("money", 0)
        val nodes = arr(tx, "nodes")
        val top = r.top + 100f; val bot = inner.bottom - UiKit.STRIP - 6f
        val art = taxiMap(tx.optInt("map", 0))
        // the picture's OWN shape, fitted to the room there is (user, 2026-09-30: the map looked "Too flat": on the
        // Thor the game's flight map is uiMap 1463, a SQUARE picture, which was stretched to the continents' 1.5)
        val aspect = if (art != null && art.height > 0) art.width.toFloat() / art.height else 1002f / 668f
        var h = bot - top; var w = h * aspect
        val maxW = inner.width() - 8f
        if (w > maxW) { w = maxW; h = w / aspect }
        val dst = RectF(r.centerX() - w / 2f, top, r.centerX() + w / 2f, top + h)
        if (art != null) c.drawBitmap(art, null, dst, pArt) else UiKit.ahPanel(c, dst, "ah_bg_index")
        UiKit.flightEdge(c, dst)        // the game's own soft inner edge over the map (UI Art, art-kit 07e855c0)
        fun sx(x: Double) = dst.left + x.toFloat() * dst.width()
        fun sy(y: Double) = dst.top + y.toFloat() * dst.height()
        val byI = nodes.associateBy { it.optInt("i") }
        val here = byI[tx.optInt("cur", -1)]
        val pick = (cur?.takeIf { it.startsWith("taxi ") } ?: selected?.takeIf { it.startsWith("taxi ") } ?: here?.let { "taxi ${it.optInt("i")}" })
        val chosen = pick?.substringAfter("taxi ")?.toIntOrNull()?.let { byI[it] }
        val size = 40f
        // the chosen point's route, under the points: from where you are, through each stop
        if (chosen != null && here != null && chosen.optString("state") == "reachable") {
            val pts = ArrayList<android.graphics.PointF>()
            pts.add(android.graphics.PointF(sx(here.optDouble("x")), sy(here.optDouble("y"))))
            chosen.optJSONArray("hops")?.let { a -> for (k in 0 until a.length()) byI[a.optInt(k)]?.let { n -> pts.add(android.graphics.PointF(sx(n.optDouble("x")), sy(n.optDouble("y")))) } }
            if (pts.size == 1) pts.add(android.graphics.PointF(sx(chosen.optDouble("x")), sy(chosen.optDouble("y"))))
            UiKit.taxiRoute(c, pts, 22f)
        }
        for (n in nodes) {
            val st = when (n.optString("state")) { "current" -> UiKit.Taxi.HERE; "reachable" -> UiKit.Taxi.CAN; else -> UiKit.Taxi.CANNOT }
            val x = sx(n.optDouble("x")); val y = sy(n.optDouble("y"))
            UiKit.taxiNode(c, x, y, size, st)
            hits.add(RectF(x - 34f, y - 34f, x + 34f, y + 34f) to "sel:taxi ${n.optInt("i")}")
        }
        // the strip: your money; Fly
        val strip = UiKit.strip(c, inner)
        val cy = strip.centerY() + 2f
        val bx = inner.right - 8f
        c.drawText("Your money", inner.left + 24f, cy - 14f, UiKit.bodyPaint(22f, Theme.dim))
        UiKit.money(c, purse, inner.left + 24f, cy + 20f, 30f)
        val e = pick?.let { p -> entries().firstOrNull { it.cmd == p } }
        val cost = chosen?.optLong("cost", 0) ?: 0L
        val state = chosen?.optString("state")
        val ok = e != null && state == "reachable" && e.slot >= 0 && cost <= purse
        stripButton(c, RectF(bx - 250f, cy - 55f, bx, cy + 56f), "Fly", "go", ok, pendingCmd == e?.cmd, "rev_a")
        goCmd = e.takeIf { ok }
        if (!ok) goWhy = when (state) {
            "current" -> "You are here"
            "unreachable" -> "Not discovered"
            null -> "Choose a flight point"
            else -> if (cost > purse) "Not enough money" else "The game gave this no tap cell"
        }
        resultLine(c, inner.left + 260f, bx - 280f, cy, now)
        // the cursor's ring and the game's tooltip at the point's top right, over everything on the map
        if (chosen != null) {
            val x = sx(chosen.optDouble("x")); val y = sy(chosen.optDouble("y"))
            UiKit.focusRing(c, x, y, size * 0.52f)
            val lines = when (state) {
                "current" -> listOf(UiKit.TipLine("You are here"))
                "reachable" -> listOf(UiKit.TipLine("", money = cost))
                else -> listOf(UiKit.TipLine("Not Discovered", Color.rgb(255, 32, 32)))
            }
            val tw = 360f
            val name = chosen.optString("name", "")
            val probe = UiKit.tooltip(c, 0f, -9999f, tw, name, Theme.gold, lines, alpha = 0)
            val tx0 = min(x + size * 0.5f, inner.right - tw - 6f)
            val ty0 = max(top, y - size * 0.5f - probe.height())
            UiKit.tooltip(c, tx0, ty0, tw, name, Theme.gold, lines)
        }
        val title = tx.optJSONObject("npc")?.optString("name", "")?.takeIf { it.isNotEmpty() } ?: "Flight Master"
        UiKit.windowFrame(c, r, title, UiKit.tex("mm_flightmaster"), Theme.gold, closeDown = pressed == "x", closeB = closeB())
    }

    /** the game's BuyDialog: the item and how many, the total, Buy Now (A, the purchase) / Cancel (B); greyed while it prices */
    private fun ahBox(c: Canvas, r: RectF, item: JSONObject, qty: Int, total: Long, waiting: Boolean, buyE: Entry?, act: JSONObject?) {
        hits.clear()                            // the box owns every touch while it is up (the frame's X is added after)
        UiKit.cover(c, r)
        val box = RectF(r.centerX() - 470f, 215f, r.centerX() + 470f, 215f + 390f)
        if (waiting) { UiKit.dialogWaiting(c, box, "Asking the auction house for the price"); return }
        val inn = UiKit.dialog(c, box)
        val np = UiKit.bodyPaint(32f, Color.WHITE)
        val words = item.optString("name", "") + if (qty > 1) " x$qty" else ""
        val ww = 64f + 18f + np.measureText(words)
        val x0 = inn.centerX() - ww / 2f
        UiKit.slot(c, itemIcon(item), x0, inn.top + 14f, 64f)
        c.drawText(words, x0 + 82f, inn.top + 46f - (np.ascent() + np.descent()) / 2f, np)
        val mw = UiKit.moneyWidth(total, 36f)
        UiKit.money(c, total, inn.centerX() - mw / 2f, inn.top + 130f, 36f)
        val by = box.bottom - 70f - 111f
        val yes = RectF(box.centerX() - 400f, by, box.centerX() - 20f, by + 111f)
        val no = RectF(box.centerX() + 20f, by, box.centerX() + 400f, by + 111f)
        val ok = buyE != null && buyE.slot >= 0
        stripButton(c, yes, "Buy Now", if (ok) "do:${buyE!!.cmd}" else "why:The game gave this no tap cell", ok, pendingCmd == buyE?.cmd, "rev_a", always = true)
        val back = act?.let { slotOf(it, "back") } ?: -1
        stripButton(c, no, "Cancel", if (back >= 0) "act:ahback|$back" else "why:Press ${Glyphs.name("b")}", true, false, UiKit.backGlyph(), always = true)
    }

    // ---- a vendor: the game's frame round a scrolling list, tabs on top, rows like the game's

    /**
     * HOW MANY of a vendor item one purchase may be repeated: steps of the vendor's stack, up to its
     * purchase limit, what the purse covers and what the vendor has left (the limits of the game's own
     * split-stack box), and the touch cells. 1 = no quantity (arrows and shot: one stack of 200 per
     * purchase, and one press is one game action).
     */
    private fun mostOf(m: JSONObject, e: Entry, purse: Long): Int {
        val o = e.o
        if (e.kind != K_BUY || o.optBoolean("ext") || m.optInt("qtyMax", 0) <= 0) return 1
        val stack = max(1, o.optInt("stack", 1))
        val unit = o.optLong("price", 0)
        // one stack per purchase (arrows, shot): no quantity; each Buy is one stack (user, 2026-09-29
        // 13:28: "No quantity on arrows"; one arrow purchase is whatever the game allows)
        var most = max(1, o.optInt("max", stack) / stack)
        if (unit > 0) most = min(most.toLong(), max(1L, purse / unit)).toInt()
        if (o.optInt("avail", -1) > 0) most = min(most, o.optInt("avail"))
        return min(most, m.optInt("qtyMax"))
    }

    private fun vendorWindow(c: Canvas, r: RectF, m: JSONObject, now: Long) {
        val inner = UiKit.windowGround(c, r, marble = true)
        val block = blocked(now)
        val act = m.optJSONObject("act")
        val purse = m.optLong("money", 0)

        // ---- the tabs stand on top of the list, right of the ring; the purse ends their row
        val ty = r.top + 94f; val th = 108f
        val nSell = arr(m, "sell").size; val nBack = arr(m, "buyback").size
        // L and R on the row's outsides, always (the user, 15 Oct): the row starts one glyph's room further right
        val te = UiKit.TAB_END_W
        val spans = listOf(Triple(Tab.BUY, 176f + te, 366f + te), Triple(Tab.SELL, 362f + te, 552f + te), Triple(Tab.BACK, 548f + te, 798f + te))
        for ((t, x0, x1) in spans) {
            val on = t == tab
            val tr = RectF(x0, ty + (if (on) 4f else 12f), x1, ty + th)
            val (label, n, id) = when (t) {
                Tab.BUY -> Triple("Buy", 0, "tab buy"); Tab.SELL -> Triple("Sell", nSell, "tab sell")
                Tab.BACK -> Triple("Buyback", nBack, "tab back")
            }
            UiKit.tab(c, tr, label, on, n, cursor = cur == id)
            hits.add(RectF(x0, ty, x1, ty + th) to "tab:${t.name}")
        }
        UiKit.tabEnds(c, RectF(spans[0].second, ty + 12f, spans[0].third, ty + th), RectF(spans[2].second, ty + 12f, spans[2].third, ty + th))
        pFill.color = Color.rgb(96, 80, 50)
        c.drawRect(inner.left - 6f, ty + th, inner.right + 6f, ty + th + 2f, pFill)
        val pm = UiKit.bodyPaint(22f, Theme.dim).apply { textAlign = Paint.Align.RIGHT }
        val purseX = UiKit.money(c, purse, inner.right - 16f, ty + th / 2f + 6f, 30f, fromRight = true)
        // the words before the coins, measured; left out when they would reach the R glyph (the coins say it alone)
        val wordsR = inner.right - 16f - UiKit.moneyWidth(purse, 30f) - 14f
        if (wordsR - pm.measureText("Your money") > 798f + 2f * te + 8f) c.drawText("Your money", wordsR, ty + th / 2f + 14f, pm)

        // ---- the list: two columns, row-major, dragged; the last row is cut by the strip
        val list = entries()
        val stripTop = inner.bottom - UiKit.STRIP
        val ly0 = ty + th + 8f; val ly1 = stripTop - 4f
        val lw = inner.width() - 50f
        val cw = (lw - 14f) / 2f
        viewA = RectF(inner.left - 6f, ly0, inner.right + 6f, ly1)
        contentA = ((list.size + 1) / 2) * VROW + 8f
        val at = list.indexOfFirst { it.cmd == cur }
        follow(if (at < 0) -1 else at / 2, VROW, viewA.height())
        scrollA = scrollA.coerceIn(0f, max(0f, contentA - viewA.height()))
        c.save(); c.clipRect(viewA)
        if (list.isEmpty()) c.drawText(when (tab) {
            Tab.BUY -> "This vendor has nothing for sale."
            Tab.SELL -> "Nothing in your bags that this vendor will buy."
            Tab.BACK -> "Nothing to buy back."
        }, inner.left + 24f, ly0 + 56f, UiKit.bodyPaint(27f, Theme.dim))
        for ((i, e) in list.withIndex()) {
            val x = inner.left + 4f + (i % 2) * (cw + 14f)
            val y = ly0 + 4f + (i / 2) * VROW - scrollA
            val row = RectF(x, y, x + cw, y + 124f)
            if (row.bottom < ly0 || row.top > ly1) continue
            val o = e.o
            val usable = !o.has("usable") || o.optBoolean("usable", true)
            val buyable = e.kind != K_BUY || (o.optBoolean("buyable", true) && o.optInt("avail", -1) != 0)
            val n = if (e.kind == K_BUY) o.optInt("stack", 1) else o.optInt("n", 1)
            val unit = o.optLong("price", 0)
            val price = if (o.optBoolean("ext")) -1L else if (e.kind == K_SELL) unit * max(1, n) else unit
            var name = o.optString("name", "Item")
            if (e.kind == K_BUY && o.optInt("avail", -1) > 0) name += " (${o.optInt("avail")})"
            // THE QUANTITY LIVES ON THE ITEM (user, 2026-09-29 11:36: "move the quantity buttons to live on
            // the currently highlighted Item, and adjust that Item price with the QTY adjustment"): on the
            // one tile that is highlighted or selected, when it has a quantity; its price is the total
            val most = if (e.cmd == selected) mostOf(m, e, purse) else 1
            val q = if (most > 1) qty.coerceIn(1, most) else 0
            val shown = if (q > 0 && price >= 0) price * q else price
            val pad = cur != null && cur == e.cmd
            // the icon's number follows the quantity too (user, 12:37: "the vendor sells 5 waters, if I
            // change to 2, then it should show 10 on the Icon")
            // the press (a finger, or A on it: the user, b114 "Sell and buy with vendors still arent doing it") pushes the tile in
            UiKit.pressed(c, row, isPressed("sel:${e.cmd}")) {
                UiKit.itemPlate(c, row, itemIcon(o), name, Theme.quality(o.optInt("q", 1)), if (q > 0) n * q else n, shown,
                    affordable = e.kind == K_SELL || shown <= purse, selected = e.cmd == selected, unusable = !usable,
                    available = buyable, qty = q, canLess = q > 1, canMore = q < most,
                    lessGlyph = "rev_x".takeIf { pad && q > 0 }, moreGlyph = "rev_y".takeIf { pad && q > 0 })
                if (cur == e.cmd) UiKit.focus(c, row)
            }
            ownPress.add("sel:${e.cmd}")
            val clip = RectF(row.left, max(row.top, ly0), row.right, min(row.bottom, ly1))
            if (clip.height() > 0f) hits.add(clip to "sel:${e.cmd}")
            if (q > 0) {
                // after the row's own area: the last area that holds a finger wins
                val (less, count, more) = UiKit.plateQty(row)
                if (less.bottom > ly0 && less.top < ly1) {
                    hits.add(less to "qty:-"); hits.add(count to "qty:max"); hits.add(more to "qty:+")
                }
            }
        }
        c.restore()
        UiKit.scrollBar(c, RectF(inner.right - 40f, ly0, inner.right - 2f, ly1),
            if (contentA > 0f) scrollA / contentA else 0f,
            if (contentA > 0f) min(1f, (scrollA + viewA.height()) / contentA) else 1f)

        // ---- THE TOOLTIP of the highlighted / selected item (user, 2026-09-30: "Always, for the highlighted
        // item"; compare "Automatically, like the game"), over the OTHER column so the tile stays in sight
        // (UI Art's placement). The addon sends it for the controller's highlight, or for what a finger selected.
        val shownCmd = cur ?: selected
        val ti = list.indexOfFirst { it.cmd == shownCmd }
        if (ti >= 0) tipFor(shownCmd)?.let { t ->
            val o = list[ti].o
            // two thirds of a column (user, 2026-09-30: "I like the 2/3rds"), against the highlighted column's side
            val tw = cw * 2f / 3f
            val otherLeft = inner.left + 4f + (1 - ti % 2) * (cw + 14f)
            val tx = if (ti % 2 == 0) otherLeft else otherLeft + cw - tw
            val rowTop = ly0 + 4f + (ti / 2) * VROW - scrollA
            val ty0 = rowTop.coerceIn(ly0, max(ly0, ly1 - TIP_MIN_ROOM))
            val curLeft = inner.left + 4f + (ti % 2) * (cw + 14f)
            val cx0 = if (ti % 2 == 0) curLeft + cw - tw else curLeft
            drawTip(c, tx, ty0, tw, o.optString("name", "Item"), o.optInt("q", 1), t, ly0, ly1, cx0)
        }

        // ---- the strip: what can be done. Repair and junk on the left, the ONE action on the right.
        val strip = UiKit.strip(c, inner)
        val cy = strip.centerY() + 2f
        val bx = inner.right - 8f
        val btn = RectF(bx - 250f, cy - 55f, bx, cy + 56f)
        val e = list.firstOrNull { it.cmd == selected }
        val word = when (tab) { Tab.BUY -> "Buy"; Tab.SELL -> "Sell"; Tab.BACK -> "Buyback" }
        qtyMost = 1
        val leftEnd = btn.left - 24f
        if (e == null) {
            goWhy = "Select an item first"
            stripButton(c, btn, word, "go", enabled = false, busy = false, id = "go")
        } else {
            val o = e.o
            val unit = o.optLong("price", 0)
            qtyMost = mostOf(m, e, purse)
            qty = qty.coerceIn(1, qtyMost)
            val price = when (e.kind) { K_SELL -> unit * max(1, o.optInt("n", 1)); K_BUY -> unit * qty; else -> unit }
            val why = when {
                e.kind == K_BUY && o.optBoolean("ext") -> "This costs more than money: buy it on the top screen"
                e.kind == K_BUY && !o.optBoolean("buyable", true) -> "The vendor will not sell you this"
                e.kind == K_BUY && o.optInt("avail", -1) == 0 -> "Sold out"
                e.kind != K_SELL && price > purse -> "Not enough money"
                e.slot < 0 -> "The game gave this no tap cell"
                else -> null
            }
            if (why == null) goCmd = e else goWhy = why
            stripButton(c, btn, word, "go", why == null && (block == null || pendingCmd == e.cmd),
                pendingCmd == e.cmd, glyph = "rev_a".takeIf { cur != null && cur == e.cmd }, id = "go")
        }
        // the result of the last action takes the strip's left for its 4 s; then repair and junk are back
        if (!resultLine(c, inner.left + 24f, leftEnd, cy, now)) {
            var x = inner.left + 8f
            // on the Buy / Sell / Buyback tabs X = Repair All and Y = Sell Junk (user, 2026-10-01): their glyphs on the icons
            val onTab = cur?.startsWith("tab ") == true
            if (m.optBoolean("canRepair") && m.optBoolean("needsRepair")) {
                val cost = m.optLong("repairCost", 0)
                val slot = act?.let { slotOf(it, "repair") } ?: -1
                val box = RectF(x, cy - 54f, x + 280f, cy + 54f)
                UiKit.tex("icon_repair")?.let { c.drawBitmap(it, null, RectF(x, cy - 52f, x + 104f, cy + 52f), UiKit.pixel) }
                c.drawText("Repair all", x + 118f, cy - 10f, UiKit.bodyPaint(24f))
                if (onTab) UiKit.pad(c, "rev_x", x + 14f, cy - 38f, 40f)
                UiKit.money(c, cost, x + 118f, cy + 18f, 25f, if (cost <= purse) Theme.text else Theme.unusable)
                if (pendingCmd == "repair" || pressed.startsWith("act:repair")) UiKit.glow(c, box, Theme.gold, 0.4f)
                if (cur == "repair") UiKit.focus(c, box)
                val rh = when {
                    cost > purse -> "why:Not enough money to repair"
                    slot < 0 -> "why:The game gave this no tap cell"
                    else -> "act:repair|$slot"
                }
                hits.add(box to rh)
                if (onTab && !rh.startsWith("why:")) glyphHits["rev_x"] = rh      // X = Repair All: its press shows here
                x += 292f
            }
            if (m.optInt("junk", 0) > 0 && x + 270f < leftEnd) {
                val slot = act?.let { slotOf(it, "junk") } ?: -1
                val box = RectF(x, cy - 54f, x + 270f, cy + 54f)
                UiKit.slot(c, UiKit.tex("icon_junk"), x + 8f, cy - 44f, 88f)
                c.drawText("Sell junk", x + 112f, cy - 10f, UiKit.bodyPaint(24f))
                if (onTab) UiKit.pad(c, "rev_y", x + 22f, cy - 30f, 40f)
                UiKit.money(c, m.optLong("junkValue", 0), x + 112f, cy + 18f, 25f)
                if (pendingCmd == "junk" || pressed.startsWith("act:junk")) UiKit.glow(c, box, Theme.gold, 0.4f)
                if (cur == "junk") UiKit.focus(c, box)
                hits.add(box to if (slot >= 0) "act:junk|$slot" else "why:The game gave this no tap cell")
                if (onTab && slot >= 0) glyphHits["rev_y"] = "act:junk|$slot"     // Y = Sell Junk: its press shows here
            }
        }
        UiKit.windowFrame(c, r, m.optJSONObject("npc")?.optString("name", "") ?: "Vendor",
            UiKit.tex("portrait_vendor"), Theme.gold, closeDown = pressed == "x", closeB = closeB())
    }

    companion object {
        const val SPK_EVERY_MS = 3_000L
        const val PAPER_GOLD = 2            // UI Art's gold selection look; the user's pick replaces it
        const val CLOSE_B_LOOK = 1
        const val TIP_STEP = 90f                // px the tooltip stack moves per right-stick step
        const val TIP_MIN_ROOM = 260f           // a tooltip starts no lower than this above the list's foot        // the user's pick (2026-09-29 11:23, replacing 2): a small B on the X's corner
        const val LINE_H = 111f           // a line of a gossip page: 48 dp, the touch floor
        const val VROW = 132f             // a vendor row and the gap under it

        const val STALE_MS = 40_000L      // the addon re-sends every 15 s; two missed = the game is gone
        const val TRUST_TRIES = 3             // taps of the trust cell per load of the addon
        const val TRUST_GAP_MS = 6_000L       // and the least time between two of them
        const val LIVE_MS = 25_000L       // actions need a document this recent
        const val PENDING_TIMEOUT_MS = 8_000L
        const val PENDING_FOLD_MS = 1_500L    // no document at all this long after an action: the window folds away
        const val PRESS_MIN_MS = 160L         // a press stays visibly DOWN at least this long
        private val PAD_FACES = setOf("PAD1", "PAD2", "PAD3", "PAD4")
        /** the room for an LB / RB glyph outside the mailbox's tab pair */
        private const val MAIL_GLYPH_W = 64f
        /** the game's INBOXITEMS_TO_DISPLAY */
        private const val PER_PAGE = 7
        /** the keyboard's D-pad repeat (Bags' values) */
        private const val KB_REPEAT_FIRST_MS = 280L
        private const val KB_REPEAT_MS = 70L
        private val FACE_SLOTS = listOf(PadInput.SLOT_FACE_BOTTOM to "PAD1", PadInput.SLOT_FACE_RIGHT to "PAD2",
                                        PadInput.SLOT_FACE_LEFT to "PAD3", PadInput.SLOT_FACE_TOP to "PAD4")
        const val PAD_ECHO_MS = 700L          // the document's nav.last within this long of the reader's own edge is that same press
        const val LOCKOUT_MS = 500L       // one touch = one action: a double tap is one touch
        const val NOTE_MS = 4_000L
        const val SCROLL_STEP = 170f      // one D-pad step of the quest text: about five lines

        private const val K_OPTION = 0; private const val K_AVAIL = 1; private const val K_ACTIVE = 2
        private const val K_CHOICE = 3; private const val K_BUY = 4; private const val K_SELL = 5
        private const val K_BACK = 6; private const val K_TRAIN = 7; private const val K_PET = 8; private const val K_MAIL = 9; private const val K_AH = 10; private const val K_TAXI = 11; private const val K_LOOT = 12

        private val DAYS_BEFORE = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
    }
}
