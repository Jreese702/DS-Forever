package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * THE CHARACTER ROOM (character session): the game's CharacterFrame as a room, arrangement C "the game's panes in a room"
 * (user pick 2026-09-28), built to UI Art's `wow-companion-design/character/SPEC.md` with kit calls only.
 *
 * FIRST BUILD = LOOK-ONLY (user ruling): folding a heading and choosing an entry or a gear slot are the room's own view; the game's
 * check boxes are SHOWN, not pressed. What reaches the game since: the gear sets' Equip / Save, a worn piece's kits (X / Apply) and,
 * uneq1, its More menu (Y / the card's More: Unequip, [WornMore]); char-more: Replace ([WornReplace]) and Enchant ([KitFit]) there too.
 *
 * Owned elsewhere and called here: the paper doll is Bags' (`Doll.kt`, one doll for both rooms) and so are the worn pieces'
 * data and tooltip lines (`bags.equipped`); tab 4's contents are the pvp session's ([pvpDrawer], set by the activity once
 * `PvpRankTab` is in the build); the XP numbers come from [CharacterBars].
 */
class CharacterView(context: Context) : View(context), BLeadsHome {

    val model = CharacterModel()

    /** tab 4 (PvP): the pvp session's `PvpRankTab.draw(c, list, detail)`; null until that is in the build */
    var pvpDrawer: ((Canvas, RectF, RectF) -> Unit)? = null

    /** B let the controller go (the addon's note "back"): the bottom screen goes to Home (user ruling ~23:14: "Pressing be to
     *  leave the cursor should take me back to the home by default for everything"). Set by the activity. */
    var onLeave: (() -> Unit)? = null

    /** RC3: B leaves this room (capture v2 passes it to the game, whose own B closes the window; older captures let go "back").
     *  b-fix (the user on 1462: "pressing B in the more menu closes the character page and leaves the menu open."): not while the More
     *  menu, a list or the save question is open (B closes that, one step back) or the game's own question is up ([WornMore.bLeadsHome]) */
    override fun bLeadsHome(): Boolean =
        WornMore.bLeadsHome(ownOpen(), model.setPad?.gameAsks == true, model.setPad?.bToGame == true)

    /** something of the room's own over it: the More menu, a list of it, the kit list, the save question */
    private fun ownOpen(): Boolean = moreFor != null || kitFor != null || replFor != null || model.setPad?.ask != null

    /** b-fix: a touch on the B disc (Home's swirl wearing B) is B: one step back while something of the room's own is open (true =
     *  it closed one step here and the room stays), the same as the pad's B and the menu's own Back */
    fun stepBackByTouch(): Boolean {
        if (model.setPad?.gameAsks == true) return false
        return when {
            kitFor != null -> { closeKits(); invalidate(); true }
            replFor != null -> { closeSub(); invalidate(); true }
            moreFor != null -> { closeMore(); invalidate(); true }
            model.setPad?.ask != null -> { model.setPad?.no?.let { setTap("no", it) }; true }
            else -> false
        }
    }
    private var lastNote = ""

    // bags.equipped (Bags' collector): for the doll and the chosen piece's card
    private var equipped: JSONObject? = null
    // uneq1: the bags document's free plain-bag slots (null = not known yet) and its pad (the bmvq row's presence)
    private var freeGeneral: Int? = null
    private var bagsPad: JSONObject? = null
    private var bagsCtx: String? = null             // char-more2: a bag cell's tap sells / deposits / attaches now (no kit, no secure equip)
    private var chosenSlot: Int? = null

    // scroll offsets, by what is dragged
    private val scroll = HashMap<String, Float>()
    private val content = HashMap<String, Float>()     // how tall each dragged thing was at the last draw

    // what a tap can hit, filled during onDraw (last drawn wins)
    private class Hit(val r: RectF, val act: () -> Unit)
    private val hits = ArrayList<Hit>()

    // what the controller can stand on, filled during onDraw: its key, where it is (drawn, so scrolled), whether it is a gear
    // slot (the slot's own glow), and the pane it scrolls in (to bring it into view)
    private class Focus(val key: String, val r: RectF, val slot: Boolean, val pane: RectF?, val paneKey: String?, val act: () -> Unit)
    private val focusables = ArrayList<Focus>()
    /** LATER 19 (the user's pick): a tab under the cursor is drawn again WITH the kit's tab cursor, never the gold box; key -> that redraw */
    private val tabCursors = HashMap<String, (Canvas) -> Unit>()
    private var focusKey: String? = null
    private var paneKey: String? = null
    // presses wait here and are taken ONE PER FRAME, each against the screen as last drawn: a press that changes the tab
    // must not leave the next press (from the same read of the log) judging the old tab's layout
    private val pending = ArrayDeque<String>()
    private val drags = ArrayList<Pair<RectF, String>>()

    init { CharacterBars.listen { if (model.tab == CharTab.CHARACTER) invalidate() } }

    /** the room holds the controller, as the last CURRENT document said (a document replayed from the log's backlog at app start
     *  tells who held it THEN, and must not leave a stale "held" behind: a later room change would tap `close` for nothing) */
    var held = false; private set
    /** it holds it because the GAME's Character window is open (MainActivity then shows the room) */
    val heldByWindow get() = held && model.controllerVia == "window"
    /** the room REALLY has the controller: held and no longer waiting (capture v11's queue, a fight's ENTER). The cursor and anything
     *  else that needs the keys wait for this; [held] alone still decides the leave (a leave must give a wait up too) */
    var active = false; private set
    /** where to tap `open` / `close` (null until the addon's `grid` part came) */
    val grid get() = model.grid
    /** the GAME's Character window is open, as the last CURRENT document said (a replayed one must not decide a tap) */
    var gameOpenNow = false; private set

    /**
     * A route INTO the room (Home's show(), EVERY route: touch, the bar, a game-window jump; the user 2026-10-01: "Every room opens
     * the game's own window behind it, as Professions does"). The HOLD first ([CharGrid.holdCell]: the plain `open` cell, no game
     * action), asked again [HOLD_CHECK_MS] later while not held, twice at most (Group Finder's holdCheck, Social's socialHold); the
     * game's own window ([CharGrid.openCell], the secure open cell) once the hold is REAL ([openWhenHeld]). Before this the ONLY tap
     * was the secure open cell, whose action runs on the RELEASE: right after a controller press (the bar's A) the game's freelook
     * snap moves the release off the cell (TapPad's "A PLAIN CELL FIRES ON THE PRESS"), the window never opened, its OnShow never
     * took, and nothing asked again: the room never held. True when a tap went out; nothing before the `grid` part came.
     */
    fun touchedIn(): Boolean {
        roomShown = true; openWanted = true
        val sent = tapCell("in", model.grid?.holdCell(held))
        holdSent = holdSent || sent
        if (held) openWhenHeld()                // the game's own key opened it (held by the window): nothing to tap
        holdCheck(0)
        return sent
    }

    /**
     * A route OUT of the room that is not the player's B (B goes to the game, which closes its window and lets go itself). One tap
     * ([CharGrid.leaveCell]): the window's own close, the game's way; the addon then lets go "closed". A hold asked this visit whose
     * answer has not come yet lets go by the plain `close` (no game action; Group Finder's `f`), so a hold landing late never keeps
     * the controller for a room that is gone. True when a tap went out.
     */
    fun touchedOut(): Boolean {
        val g = model.grid
        val cell = g?.leaveCell(gameOpenNow, held) ?: if (g != null && holdSent) g.row to g.close else null
        roomShown = false; openWanted = false; holdSent = false
        // b-fix (the user on 1462: "... leaves the menu open"): the room left = its More menu / lists are gone with it (never drawn again
        // on the next visit); the add-on forgets its own when the controller goes (onLost / the leave), so nothing is tapped for it here
        moreFor = null; kitFor = null; replFor = null; moreSeen = false; subSeen = false
        return tapCell("out", cell)
    }

    /** the room is on screen (between [touchedIn] and [touchedOut]); the window still to open behind it; a hold asked this visit */
    private var roomShown = false
    private var openWanted = false
    private var holdSent = false

    /** the hold not answered [HOLD_CHECK_MS] later while the room shows = asked again, twice at most (the addon's take is idempotent) */
    private fun holdCheck(attempt: Int) {
        postDelayed({
            if (!roomShown || held || attempt >= 2) return@postDelayed
            val again = tapCell("hold again (${attempt + 1})", model.grid?.holdCell(held))
            holdSent = holdSent || again
            holdCheck(attempt + 1)
        }, HOLD_CHECK_MS)
    }

    /** the game's window opens behind the room ONCE, when this visit's hold is real; a window open already needs nothing */
    private fun openWhenHeld() {
        if (!roomShown || !openWanted || !active) return
        openWanted = false
        tapCell("open", model.grid?.openCell(gameOpenNow, active))
    }

    private fun tapCell(why: String, cell: Pair<String, Int>?): Boolean {
        val g = model.grid ?: return false
        if (cell == null) return false
        val ok = TapBridge.tap(context, g.pad, cell.first, cell.second)
        Log.i(TAG, "character: touched $why -> ${if (ok) "tapped" else "refused"} ${cell.first}:${cell.second} (open=$gameOpenNow held=$held)")
        return ok
    }

    /**
     * ONE RULE FOR EVERY ROOM (Home's, tracking's yes under the user's delegation, 2026-09-30, after a guest kept the controller once
     * its room was left): let go of the shared capture by the addon's NO-GAME-ACTION `close` cell, WHATEVER [held] or the room's being
     * on screen say. The addon (Character.lua touchClose) only releases interact's capture: it never opens or closes a game window and
     * presses nothing in the game; if the game's Character window is open, the room then stays off until that window closes.
     * True when a tap went out. Home calls it at most once per 2 s while a CURRENT interact document names holder "character" and
     * the room is not on screen.
     */
    fun freeAgain(): Boolean {
        val g = model.grid
        if (g == null) { Log.i(TAG, "guest character: free again: no grid yet, nothing tapped"); return false }
        val ok = TapBridge.tap(context, g.pad, g.row, g.close)
        Log.i(TAG, "guest character: free again -> ${if (ok) "tapped" else "refused"} ${g.row}:${g.close}")
        return ok
    }

    /** a `character` document; true when something changed. [current] = not replayed from the backlog */
    fun update(msg: JSONObject, current: Boolean = true): Boolean {
        val changed = model.update(msg)
        if ("win" in changed && current) {
            val was = active
            held = model.controller
            active = held && !model.controllerWaiting
            gameOpenNow = model.gameOpen
            // a NEW hand-over starts the cursor on what is already selected (LATER item 19, the user), not where it was last time
            if (active && !was) focusKey = null
            if (active) openWhenHeld()
        }
        if ("pad" in changed) model.pad?.let { key -> model.pad = null; pending.addLast(key) }
        if ("eqs" in changed && current) model.setPad?.let { sp ->
            // uneq1: the add-on's `more` went false while the menu is open (B closed it there): one step back here too
            if (sp.more) moreSeen = true else if (moreSeen && (moreFor != null || kitFor != null)) { moreSeen = false; closeMore(tell = false) }
            // char-more: its list flag went off while a Replace / Enchant list is open (B there): back to the menu, as B does
            if (sp.sub) subSeen = true else if (subSeen && sp.more && moreFor != null && (replFor != null || kitFor != null)) closeSub(tell = false)
        }
        if ("eqs" in changed && current) model.setPad?.ack?.takeIf { it.op == "unequip" }?.let { a ->
            // uneq2 (review): the add-on re-sends its LAST ack in every eqs part, so an old answer (another piece, another visit) is
            // only recorded; a line is said only for an answer that came within [WornMore.ANSWER_MS] of this room's own unequip tap
            if (a.seq != ackShownSeq) {
                ackShownSeq = a.seq
                if (WornMore.fresh(unequipSentAt, android.os.SystemClock.uptimeMillis())) WornMore.say(a.ok, a.why)?.let { kitSay(it) }
            }
        }
        if ("eqs" in changed && current) model.setPad?.ack?.takeIf { it.op != "unequip" }?.let { a ->
            if (a.seq != ackShownSeq) {
                ackShownSeq = a.seq; ackUntil = android.os.SystemClock.uptimeMillis() + 4000L
                postInvalidateDelayed(4050L)
            }
        }
        if ("win" in changed) {
            val note = model.controllerNote
            if (note == "back" && lastNote != "back") { pending.clear(); onLeave?.invoke() }
            lastNote = note
        }
        if (changed.isNotEmpty()) invalidate()
        return changed.isNotEmpty()
    }

    // ------------------------------------------------------------------ ARMOR KITS, STONES, ENCHANTS (the user, 2026-11-07: "a way to use them
    // from both the bags room as well as the charactor room"; pick: the game-style list): X on a worn slot (or the card's Apply) lists the bags'
    // kits (Bags.lua's `kit` flag); A uses the chosen one (its secure bag cell: the game starts "pick an item"), and once the game says the pick is
    // on (an `itarget` document) the slot's secure binv cell is its target. B / X closes the list.

    /** the bags' kits: (bag, slot, row) (BagsView.kitItems, the host's) */
    var kits: () -> List<Triple<Int, Int, JSONObject>> = { emptyList() }
    /** char-more: every bag item: (bag, slot, row) (BagsView.bagItems, the host's), for Replace */
    var bagItems: () -> List<Triple<Int, Int, JSONObject>> = { emptyList() }
    private var kitFor: Int? = null                 // the worn slot the list is open for
    private var kitSel = 0
    private var kitWait: Pair<Int, Long>? = null    // (slot, since): the kit was used, waiting for the game's pick
    private var kitLine: String? = null; private var kitLineAt = 0L
    private val kitHits = ArrayList<Pair<RectF, Int>>()

    private fun tapPad(row: String, col: Int): Boolean {
        val g = model.grid ?: return false
        val ok = TapBridge.tap(context, g.pad, row, col)
        Log.i(TAG, "character: kit tap $row:$col -> ${if (ok) "tapped" else "refused"}")
        return ok
    }
    private fun kitSay(s: String) { kitLine = s; kitLineAt = android.os.SystemClock.uptimeMillis(); invalidate(); postInvalidateDelayed(3100L) }

    /** char-more: the kits that may go on worn [slot]: those the game said no to for the piece worn there left out ([KitFit]) */
    private fun slotKits(slot: Int) = KitFit.forSlot(kits(), slot)

    /** X on a worn slot / the card's Apply: the kit list on its own. char-more: the add-on holds B for it (`moreon`), so B closes it,
     *  one step back, the same as the More menu's Enchant list */
    private fun openKits(slot: Int) {
        val list = slotKits(slot)
        if (list.isEmpty()) { kitSay(if (kits().isEmpty()) "No armor kits, stones or enchants in your bags" else "Nothing fits"); return }
        if (bagsCtx != null) { kitSay("Window open"); return }          // char-more2: the kit's cell would sell / deposit it
        moreFor = null; replFor = null; moreSeen = false; subSeen = false
        kitFor = slot; kitSel = 0
        model.setPad?.moreOn?.let { setTap("more on (kits)", it) }
        invalidate()
    }

    /** the kit list closes one step: opened from the More menu = back to the menu, else the list alone was open = all closed */
    private fun closeKits(tell: Boolean = true) {
        if (kitFor == null) return
        if (moreFor != null) closeSub(tell) else closeMore(tell)
    }

    private fun applyKit(i: Int) {
        val slot = kitFor ?: return
        val (bag, s, row) = slotKits(slot).getOrNull(i) ?: return
        if (bagsCtx != null) { kitSay("Window open"); return }          // char-more2: a vendor / bank / mail opened under the list
        // select -> ONE action: the list (and a More menu under it) is done; the add-on hears it in the same batch as the kit's use
        val sp = model.setPad
        val off = sp?.moreOff?.takeIf { sp.row.isNotEmpty() }?.let { listOf(sp.row to it) } ?: emptyList()
        kitFor = null; moreFor = null; replFor = null; moreSeen = false; subSeen = false
        val g = model.grid
        val ok = g != null && TapBridge.tapsSpaced(context, g.pad, off + listOf("bag$bag" to s - 1), 120L)
        Log.i(TAG, "character: kit bag$bag:${s - 1} for slot $slot -> ${if (ok) "tapped" else "refused"}")
        if (!ok) { sp?.moreOff?.let { setTap("more off", it) }; kitSay("The game did not take it"); return }
        val since = android.os.SystemClock.uptimeMillis()
        kitWait = slot to since
        kitSay("Applying ${row.optString("name")}")
        postDelayed({
            if (kitWait?.second == since) { kitWait = null; tapPad("binv", 19); kitSay("The game did not ask for an item") }   // /stopspelltarget
        }, KIT_WAIT_MS)
    }

    /** Bags.lua's `itarget`: the game's "pick an item" started (or ended); a kit used from here goes on its slot now */
    fun onItarget(on: Boolean, doc: JSONObject? = null) {
        val w = kitWait ?: return
        if (!on) return
        kitWait = null
        // bags-kit3 (the user's pick, 2026-11-13): the slot only when it is a row of the Bags room's list, else the game's /stopspelltarget
        if (!KitTarget.slotFits(doc, w.first)) { tapPad("binv", KitTarget.STOP_COL); kitSay("It can't go there"); return }
        tapPad("binv", w.first - 1)
    }

    private fun kitKey(key: String) {
        val n = kitFor?.let { slotKits(it).size } ?: 0
        when (key) {
            "PADDUP" -> kitSel = (kitSel - 1).coerceAtLeast(0)
            "PADDDOWN" -> kitSel = (kitSel + 1).coerceAtMost(maxOf(0, n - 1))
            "PAD1" -> applyKit(kitSel)
            "PAD2", "PAD3" -> closeKits()
        }
        invalidate()
    }

    /** the list over the room: the kits that may go on this slot (char-more: [slotKits]), the gold row the controller's, A Apply,
     *  B / X Close (Back when it is the More menu's Enchant list) */
    private fun kitList(c: Canvas, w: Float, h: Float) {
        kitHits.clear()
        val slot = kitFor ?: return
        val list = slotKits(slot)
        if (list.isEmpty()) { post { if (kitFor == slot && slotKits(slot).isEmpty()) closeKits() }; return }    // the bags changed under it
        if (kitSel > list.size - 1) kitSel = list.size - 1
        UiKit.cover(c, RectF(0f, 0f, w, h))
        val bw = minOf(760f, w - 80f)
        val rowH = 84f
        val shown = list.size.coerceAtMost(7)
        val box = RectF(w / 2f - bw / 2f, 90f, w / 2f + bw / 2f, 90f + 80f + shown * rowH + 20f)
        val inner = UiKit.menu(c, box, solid = true)
        val name = worn()[slot]?.optString("name") ?: ""
        c.drawText(Tr.t("Apply to"), inner.left + 16f, inner.top + 40f, UiKit.bodyPaint(30f, Theme.gold))
        if (name.isNotEmpty()) {
            val np = UiKit.bodyPaint(24f, Theme.dim).apply { textAlign = android.graphics.Paint.Align.RIGHT }
            c.drawText(android.text.TextUtils.ellipsize(name, android.text.TextPaint(np), inner.width() / 2f, android.text.TextUtils.TruncateAt.END).toString(),
                inner.right - 16f, inner.top + 40f, np)
        }
        val first = (kitSel - shown + 1).coerceAtLeast(0)
        for (k in first until (first + shown).coerceAtMost(list.size)) {
            val (_, _, row) = list[k]
            val y = inner.top + 64f + (k - first) * rowH
            val r = RectF(inner.left + 8f, y, inner.right - 8f, y + rowH - 6f)
            val icon = Icons.getOrUnknown(if (row.has("icon")) row.optInt("icon") else null)
            UiKit.itemPlate(c, r, icon, row.optString("name", "?"), Theme.quality(row.optInt("quality", 1)), row.optInt("count", 0))
            if (k == kitSel) UiKit.focus(c, r)
            kitHits.add(RectF(r) to k)
        }
        val hy = box.bottom + 46f
        val hs = UiKit.hints(c, box.right - 16f, hy, "rev_a" to "Apply", UiKit.backGlyph() to (if (moreFor != null) "Back" else "Close"))
        kitHits.add(RectF((hs + box.right) / 2f, hy - 36f, box.right, hy + 36f) to -1)
    }

    /** the Bags document (`full.bags`): its `equipped` part feeds the doll and the card */
    fun updateBags(bags: JSONObject) {
        if (bags.has("freeGeneral")) freeGeneral = bags.optInt("freeGeneral")
        bags.optJSONObject("pad")?.let { bagsPad = it }
        // char-more2: the game window that changes what a bag cell's tap does (vendor / bank / mail / trade / auction: the Bags room's ctx)
        bagsCtx = bags.optString("ctx").takeIf { it.isNotEmpty() && it[0].code > 3 }
        bags.optJSONObject("equipped")?.let { equipped = it; if (model.tab == CharTab.CHARACTER) invalidate() }
    }

    // ------------------------------------------------------------------ uneq1: A WORN PIECE'S MORE MENU (the user's picks, 2026-11-13: "choose a
    // worn slot, Y = More opens a small menu with 'Unequip' first (A does it, B back, Up/Down moves; touch: a More card button with the Y
    // glyph)"; "WEAPONS (slots 16,17,18) MAY be unequipped in combat, armor is refused in combat"). The rules are [WornMore]'s. Opening it
    // taps the add-on's `moreon` (B is the room's then: one step back closes it, the add-on says `more` false), closing it `moreoff`.
    private var moreFor: Int? = null
    private var moreSel = 0
    private var moreSeen = false                    // the add-on has said `more` true for this menu (its false then closes it)
    private var unequipSentAt = 0L                  // uneq2: uptime of this room's last unequip batch (0 = none): only its answer is said
    private val moreHits = ArrayList<Pair<RectF, Int>>()
    private var subSeen = false                     // char-more: the add-on has said `msub` true for the open list (its false: back)
    // char-more: the Replace list (the worn slot it is open for) over the menu
    private var replFor: Int? = null
    private var replSel = 0
    private val replHits = ArrayList<Pair<RectF, Int>>()

    /** the pad that has both cells of the route (the bags document's is the newer), else null: nothing is tapped */
    private fun routePad(): JSONObject? {
        val row = model.setPad?.row?.takeIf { it.isNotEmpty() } ?: return null
        for (p in listOfNotNull(bagsPad, model.grid?.pad)) {
            val rows = p.optJSONObject("rows") ?: continue
            if (rows.has("bmvq") && rows.has(row)) return p
        }
        return null
    }

    /** char-more: the bags' pieces for worn [slot] (the game's slot rules; what the player can't wear left out) */
    private fun replPicks(slot: Int) = WornReplace.picks(bagItems(), slot, equipped?.optBoolean("dw") == true)

    /** char-more: the pad and the cells that put [p] into worn [slot] now (the More menu's close first, on the add-on's row), or null */
    private fun replRoute(p: WornReplace.Pick, slot: Int): Pair<JSONObject, List<Pair<String, Int>>>? {
        val sp = model.setPad
        val off = sp?.moreOff?.takeIf { sp.row.isNotEmpty() }
        for (pad in listOfNotNull(bagsPad, model.grid?.pad)) {
            val cells = WornReplace.cells(pad, p, slot, model.combat, bagsCtx != null) ?: continue
            if (off != null && pad.optJSONObject("rows")?.has(sp.row) != true) continue
            return pad to ((if (off != null) listOf(sp.row to off) else emptyList()) + cells)
        }
        return null
    }

    private fun moreRows(slot: Int): List<WornMore.Row> {
        val combat = model.combat
        val picks = replPicks(slot)
        val window = bagsCtx != null
        val now = picks.filter { WornReplace.now(it, slot, combat, window) }
        val fits = WornMore.Fits(picks.size, now.size, now.isEmpty() || now.any { replRoute(it, slot) != null },
            kits().size, slotKits(slot).size, window)
        return WornMore.rows(slot, worn()[slot]?.has("id") == true, combat, freeGeneral,
            model.setPad?.unequip != null && routePad() != null, fits)
    }

    private fun openMore(slot: Int) {
        if (moreRows(slot).isEmpty()) return            // ammo: no menu
        moreFor = slot; moreSel = 0; moreSeen = false; subSeen = false; kitFor = null; replFor = null
        model.setPad?.moreOn?.let { setTap("more on", it) }
        invalidate()
    }

    /** the whole More menu closes, with any list over it; [tell] = say so to the add-on (`moreoff`) */
    private fun closeMore(tell: Boolean = true) {
        if (moreFor == null && kitFor == null && replFor == null) return
        moreFor = null; kitFor = null; replFor = null; subSeen = false
        if (tell) model.setPad?.moreOff?.let { setTap("more off", it) }
        invalidate()
    }

    /** char-more: a Replace / Enchant list opens over the menu; the add-on holds its B (`moresub`: B = back to the menu) */
    private fun openSub(slot: Int, enchant: Boolean) {
        subSeen = false
        if (enchant) { kitFor = slot; kitSel = 0 } else { replFor = slot; replSel = 0 }
        model.setPad?.moreSub?.let { setTap("more list", it) }
        invalidate()
    }

    /** char-more: the open list closes, one step back to the menu; [tell] = say so to the add-on (`moreon`: the menu alone again) */
    private fun closeSub(tell: Boolean = true) {
        if (replFor == null && kitFor == null) return
        replFor = null; kitFor = null; subSeen = false
        if (tell) model.setPad?.moreOn?.let { setTap("more back", it) }
        invalidate()
    }

    private fun chooseMore(i: Int) {
        val slot = moreFor ?: return
        val row = moreRows(slot).getOrNull(i) ?: return
        if (!row.usable) { row.why?.let { kitSay(it) }; return }
        when (row.label) {
            WornMore.REPLACE -> { openSub(slot, enchant = false); return }
            WornMore.ENCHANT -> { openSub(slot, enchant = true); return }
        }
        val sp = model.setPad
        val arm = sp?.unequip
        val pad = routePad()
        if (sp == null || arm == null || pad == null) { closeMore(); kitSay("Unavailable"); return }
        closeMore(tell = false)                         // the arm closes it in the add-on too
        // ONE batch, all or nothing: the arm, then the worn slot's column; spaced past the pad's 0.25 s repeat guard
        val ok = TapBridge.tapsSpaced(context, pad, WornMore.cells(sp.row, arm, slot), 120L)
        Log.i(TAG, "character: unequip slot $slot -> ${if (ok) "tapped" else "refused"}")
        unequipSentAt = if (ok) android.os.SystemClock.uptimeMillis() else 0L
        if (!ok) { sp.moreOff?.let { setTap("more off", it) }; kitSay("The game did not take it") }
    }

    private fun moreKey(key: String) {
        val slot = moreFor ?: return
        val (sel, act) = WornMore.key(moreSel, moreRows(slot).size, key)
        moreSel = sel
        when (act) {
            WornMore.Act.CHOOSE -> chooseMore(moreSel)
            WornMore.Act.BACK -> closeMore()
            WornMore.Act.NONE -> {}
        }
        invalidate()
    }

    /** char-more: A on a Replace row = that piece into the slot (ONE batch: the menu's close, then its route); one that can't now says why */
    private fun doReplace(i: Int) {
        val slot = replFor ?: return
        val p = replPicks(slot).getOrNull(i) ?: return
        WornReplace.why(p, slot, model.combat, bagsCtx != null)?.let { kitSay(it); return }
        val route = replRoute(p, slot)
        if (route == null) { kitSay("Unavailable"); return }
        replFor = null; moreFor = null; kitFor = null; moreSeen = false; subSeen = false
        val ok = TapBridge.tapsSpaced(context, route.first, route.second, BagsMoveRoute.GAP_MS)
        Log.i(TAG, "character: replace slot $slot with ${p.bag}:${p.slot} (${if (p.exact && bagsCtx == null) "secure" else "drag"}) -> ${if (ok) "tapped" else "refused"}")
        if (!ok) { model.setPad?.moreOff?.let { setTap("more off", it) }; kitSay("The game did not take it") }
        invalidate()
    }

    private fun replKey(key: String) {
        val slot = replFor ?: return
        val n = replPicks(slot).size
        when (key) {
            "PADDUP" -> replSel = (replSel - 1).coerceAtLeast(0)
            "PADDDOWN" -> replSel = (replSel + 1).coerceAtMost(maxOf(0, n - 1))
            "PAD1" -> doReplace(replSel)
            "PAD2", "PAD3" -> closeSub()
        }
        invalidate()
    }

    /** char-more: the Replace list over the menu, drawn as the Bags room's lists: the bag pieces for this slot (one that can't go on now
     *  greyed, its reason beside its name), the gold row the controller's, A Equip, B Back */
    private fun replList(c: Canvas, w: Float, h: Float) {
        replHits.clear()
        val slot = replFor ?: return
        val list = replPicks(slot)
        if (list.isEmpty()) { post { if (replFor == slot && replPicks(slot).isEmpty()) closeSub() }; return }    // the bags changed under it
        if (replSel > list.size - 1) replSel = list.size - 1
        val combat = model.combat
        UiKit.cover(c, RectF(0f, 0f, w, h))
        val bw = minOf(760f, w - 80f)
        val rowH = 84f
        val shown = list.size.coerceAtMost(7)
        val box = RectF(w / 2f - bw / 2f, 90f, w / 2f + bw / 2f, 90f + 80f + shown * rowH + 20f)
        val inner = UiKit.menu(c, box, solid = true)
        c.drawText(Tr.t(WornMore.REPLACE), inner.left + 16f, inner.top + 40f, UiKit.bodyPaint(30f, Theme.gold))
        val name = worn()[slot]?.optString("name")?.takeIf { it.isNotEmpty() } ?: Doll.NAMES[slot] ?: ""
        if (name.isNotEmpty()) {
            val np = UiKit.bodyPaint(24f, Theme.dim).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(TextUtils.ellipsize(name, TextPaint(np), inner.width() / 2f, TextUtils.TruncateAt.END).toString(), inner.right - 16f, inner.top + 40f, np)
        }
        val first = (replSel - shown + 1).coerceAtLeast(0)
        for (k in first until (first + shown).coerceAtMost(list.size)) {
            val p = list[k]
            val y = inner.top + 64f + (k - first) * rowH
            val r = RectF(inner.left + 8f, y, inner.right - 8f, y + rowH - 6f)
            val icon = Icons.getOrUnknown(if (p.row.has("icon")) p.row.optInt("icon") else null)
            val why = WornReplace.why(p, slot, combat, bagsCtx != null)
            val label = p.row.optString("name", "?") + (why?.let { " (" + Tr.t(it) + ")" } ?: "")
            UiKit.itemPlate(c, r, icon, label, Theme.quality(p.row.optInt("quality", 1)), 0, unusable = why != null)
            if (k == replSel) UiKit.focus(c, r)
            replHits.add(RectF(r) to k)
        }
        val hy = box.bottom + 46f
        val hs = UiKit.hints(c, box.right - 16f, hy, "rev_a" to "Equip", UiKit.backGlyph() to "Back")
        replHits.add(RectF((hs + box.right) / 2f, hy - 36f, box.right, hy + 36f) to -1)
    }

    /** the Y glyph in the layout the Settings chose (Nintendo's Y, Xbox's Y, PlayStation's triangle) */
    private fun yGlyph(): String = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> "ltr_y"
        CastSettings.ButtonLayout.PLAYSTATION -> "shp_triangle"
        else -> "rev_y"
    }

    /** the menu over the room: the piece's name, its rows (a disabled one with its reason), A Choose, B Back */
    private fun moreMenu(c: Canvas, w: Float, h: Float) {
        moreHits.clear()
        val slot = moreFor ?: return
        val rows = moreRows(slot)
        if (rows.isEmpty()) { moreFor = null; return }
        UiKit.cover(c, RectF(0f, 0f, w, h))
        val bw = 520f
        val box = RectF(w / 2f - bw / 2f, 220f, w / 2f + bw / 2f, 220f + 56f + rows.size * 96f + 24f)
        val inner = UiKit.menu(c, box, solid = true)
        val name = worn()[slot]?.optString("name")?.takeIf { it.isNotEmpty() } ?: Doll.NAMES[slot] ?: ""
        val np = UiKit.bodyPaint(26f, Color.WHITE)
        c.drawText(TextUtils.ellipsize(name, TextPaint(np), inner.width() - 32f, TextUtils.TruncateAt.END).toString(),
            inner.left + 16f, inner.top + 34f, np)
        for ((i, row) in rows.withIndex()) {
            val rr = RectF(inner.left, inner.top + 56f + i * 96f, inner.right, inner.top + 56f + i * 96f + 92f)
            UiKit.menuRow(c, rr, Tr.t(row.label) + (row.why?.let { " (" + Tr.t(it) + ")" } ?: ""), moreSel == i, enabled = row.usable, size = 28f)
            moreHits.add(RectF(rr) to i)
        }
        val hy = box.bottom + 40f
        val hs = UiKit.hints(c, box.right - 16f, hy, "rev_a" to "Choose", UiKit.backGlyph() to "Back")
        moreHits.add(RectF((hs + box.right) / 2f, hy - 36f, box.right, hy + 36f) to -1)
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        // a press is judged against the screen last drawn, so only once that screen shows the tab the room is on now
        // (a `win` document can switch the tab in the same read of the log as the presses behind it)
        if (pending.isNotEmpty()) {
            if (drawnTab == model.tab && drawnSide == model.side) press(pending.removeFirst())
            if (pending.isNotEmpty()) postInvalidateOnAnimation()
        }
        hits.clear(); drags.clear(); focusables.clear(); tabCursors.clear()
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(Theme.ink)
        val tab = model.tab
        header(c, w, tab)

        val tabsX = w - 118f
        val split = 704f
        val wide = tab == CharTab.STATISTICS
        UiKit.pane(c, RectF(0f, 62f, if (wide) tabsX - 4f else split, h))
        if (!wide) {
            UiKit.pane(c, RectF(split, 62f, tabsX - 4f, h), right = true)
            UiKit.paneDivider(c, split, 62f, h)
        }
        sideTabs(c, w, tabsX)
        val list = RectF(0f, 68f, split - 8f, h - 6f)
        val detail = RectF(split + 26f, 68f, tabsX - 22f, h - 6f)
        when (tab) {
            CharTab.CHARACTER -> { if (petShown()) petPage(c, list) else doll(c, list); rightPane(c, detail) }
            CharTab.REPUTATION -> { listTab(c, list, model.reputation, "rep", Kind.REP); repDetail(c, detail) }
            CharTab.SKILLS -> { listTab(c, list, model.skills, "sk", Kind.SKILL); skillDetail(c, detail) }
            CharTab.PVP -> pvpDrawer?.invoke(c, list, detail)
            CharTab.CURRENCY -> { listTab(c, list, model.currency, "cur", Kind.CUR); curDetail(c, detail) }
            CharTab.STATISTICS -> statistics(c, RectF(0f, 68f, tabsX - 12f, h - 6f))
        }
        setQuestion(c, w, h)
        glow(c)
        if (moreFor != null) moreMenu(c, w, h)
        if (kitFor != null) kitList(c, w, h)            // char-more: the menu's lists over it
        if (replFor != null) replList(c, w, h)
        kitLine?.let { s ->
            if (android.os.SystemClock.uptimeMillis() - kitLineAt > 3000L) kitLine = null
            else c.drawText(Tr.t(s), w / 2f, h - 60f, UiKit.bodyPaint(28f, Theme.gold).apply { textAlign = android.graphics.Paint.Align.CENTER })
        }
        drawnTab = model.tab; drawnSide = model.side
    }
    private var drawnTab: CharTab? = null
    private var drawnSide: CharSide? = null

    /** the controller's cursor (the kit's UiKit.focus look), drawn on the FIRST frame after the room takes the controller, already
     *  on the selected item (no press needed: the user, LATER item 19); only while it REALLY holds by a CURRENT document (not while
     *  its take waits in the capture's queue or for a fight's ENTER: no cursor that the D-pad cannot move yet) */
    private fun glow(c: Canvas) {
        if (!active) return
        val f = current() ?: return
        tabCursors[f.key]?.let { it(c); return }
        c.save()
        f.pane?.let { c.clipRect(it) }
        if (f.slot) UiKit.slotFocus(c, f.r) else UiKit.focus(c, f.r)
        c.restore()
    }

    private fun current(): Focus? = focusables.firstOrNull { it.key == focusKey } ?: startFocus()

    /** where the cursor starts (LATER item 19, the user: "when opening to a page that has tabs and something already highlighted
     *  because its selected, that feels like a really great natural starting point for the cursor"): the page's selected entry
     *  (Reputation, Skills, Currency), else the chosen gear slot (Character), else the page's own side tab */
    private fun startFocus(): Focus? {
        val keys = ArrayList<String>()
        when (model.tab) {
            CharTab.CHARACTER -> chosenSlot?.let { keys += "slot:$it" }
            CharTab.REPUTATION -> model.reputation.selected?.let { keys += "entry:$it" }
            CharTab.SKILLS -> model.skills.selected?.let { keys += "entry:$it" }
            CharTab.CURRENCY -> model.currency.selected?.let { keys += "entry:$it" }
            else -> {}
        }
        keys += "tab:${model.tab.ordinal}"
        for (k in keys) focusables.firstOrNull { it.key == k }?.let { return it }
        return focusables.firstOrNull()
    }

    /** a press the addon passed on: the D-pad moves the glow, A does what a tap does (SPEC 8); B is the addon's (it closes
     *  the game's window; while the More menu is open the add-on holds it and closes the menu); X on a worn slot = its kits;
     *  Y on a worn slot = its More menu (uneq1) */
    private fun press(key: String) {
        if (kitFor != null) { kitKey(key); return }
        if (replFor != null) { replKey(key); return }
        if (moreFor != null) { moreKey(key); return }
        if (key == "PAD3" || key == "PAD4") {
            val k = current()?.key?.takeIf { it.startsWith("slot:") }?.removePrefix("slot:")?.toIntOrNull()
            if (key == "PAD3" && k != null && worn()[k] != null) { openKits(k); return }
            if (key == "PAD4" && k != null && worn()[k] != null) { chosenSlot = k; openMore(k); return }
        }
        val cur = current() ?: return
        when (key) {
            "PAD1" -> { cur.act(); return }
            "PADDUP", "PADDDOWN" -> if (cur.key.startsWith("tab:")) {
                // on the side tabs, up / down changes the tab at once, counted from the tab the GLOW is on (the glow can
                // reach a side tab by a sideways move while another tab is shown: seen on the emulator 2026-09-29 23:59)
                val from = cur.key.removePrefix("tab:").toIntOrNull() ?: model.tab.ordinal
                val i = (from + if (key == "PADDUP") -1 else 1).coerceIn(0, CharTab.values().size - 1)
                model.tab = CharTab.values()[i]; focusKey = "tab:$i"; invalidate(); return
            }
        }
        val next = nearest(cur, key) ?: return
        focusKey = next.key
        // b117 (the user's own words about this room: "the character tabs auto open to the tab that the cursor is on"): a sideways
        // move onto a side tab or a small tab opens it too, as up / down on the side tabs always did
        if (next.key.startsWith("tab:") || next.key.startsWith("small:")) { next.act(); invalidate(); return }
        // bring it into view in its pane
        val pane = next.pane; val pk = next.paneKey
        if (pane != null && pk != null) {
            val off = scroll[pk] ?: 0f
            if (next.r.bottom > pane.bottom) scroll[pk] = off + (next.r.bottom - pane.bottom) + 8f
            else if (next.r.top < pane.top) scroll[pk] = max(0f, off - (pane.top - next.r.top) - 8f)
        }
    }

    /** the nearest focusable in the pressed direction: along the direction first, sideways counted double */
    private fun nearest(cur: Focus, key: String): Focus? {
        val cx = cur.r.centerX(); val cy = cur.r.centerY()
        var best: Focus? = null; var bestScore = Float.MAX_VALUE
        for (f in focusables) {
            if (f === cur) continue
            val dx = f.r.centerX() - cx; val dy = f.r.centerY() - cy
            // only what lies BEYOND the current item's edge in that direction: a neighbour whose centre is a few pixels
            // lower is beside it, not below it (seen on the emulator: down from a small tab jumped to a side tab)
            val beyond = when (key) {
                "PADDRIGHT" -> f.r.centerX() > cur.r.right; "PADDLEFT" -> f.r.centerX() < cur.r.left
                "PADDDOWN" -> f.r.centerY() > cur.r.bottom; "PADDUP" -> f.r.centerY() < cur.r.top
                else -> return null
            }
            if (!beyond) continue
            val (along, side) = when (key) {
                "PADDRIGHT" -> dx to abs(dy); "PADDLEFT" -> -dx to abs(dy)
                "PADDDOWN" -> dy to abs(dx); else -> -dy to abs(dx)
            }
            val score = along + 2f * side
            if (score < bestScore) { bestScore = score; best = f }
        }
        return best
    }

    private fun header(c: Canvas, w: Float, tab: CharTab) {
        val who = model.who
        val name = who?.let { it.title.ifEmpty { it.name } } ?: ""
        val line = who?.line ?: ""
        if (tab == CharTab.CHARACTER) UiKit.header(c, w, name.ifEmpty { model.word("CHARACTER", "Character") }, line)
        else UiKit.header(c, w, model.word(TAB_WORD[tab.ordinal], TAB_FALLBACK[tab.ordinal]),
            listOf(who?.name ?: "", line).filter { it.isNotEmpty() }.joinToString("   "))
    }

    private fun sideTabs(c: Canvas, w: Float, tabsX: Float) {
        // b120 (the user on side tabs: "(a) No glyphs"): the tabs sit together, no gap and no LB/RB glyph; LB/RB still switch them
        val boxes = UiKit.tabColumn(CharTab.values().size, tabsX, 72f, height - 8f, gap = 2f)
        for (t in CharTab.values()) {
            val i = t.ordinal
            val r = boxes[i]
            UiKit.sideTab(c, r, tabIcon(t), t == model.tab)
            tabCursors["tab:$i"] = { cc -> UiKit.sideTab(cc, r, tabIcon(t), t == model.tab, cursor = true) }
            hit(RectF(tabsX - 6f, r.top, w, r.bottom + 2f), "tab:$i") { choose(t) }
        }
    }

    /** the six tabs' pictures (SPEC 2): the class's picture for Character (the game shows the player's portrait, which
     *  cannot be drawn), the kit's `tab_*` pictures for the rest; the icon ids as a fallback before the kit has them */
    private fun tabIcon(t: CharTab): Bitmap? = when (t) {
        CharTab.CHARACTER -> UiKit.classIcon(model.who?.clsFile ?: "") ?: pic(CLASS_ICON[model.who?.clsFile] ?: 0)
        CharTab.PVP -> UiKit.tex(if (model.who?.faction == "Horde") "tab_pvp_horde" else "tab_pvp_alliance")
            ?: UiKit.tex(if (model.who?.faction == "Horde") "rank_horde" else "rank_alliance")
        else -> UiKit.tex(TAB_PICTURE[t.ordinal]) ?: pic(model.tabIcons[t.key] ?: SIDE_TAB_ICON[t.key] ?: 0)
    }

    private fun choose(t: CharTab) { if (model.tab != t) { model.tab = t; invalidate() } }

    // ------------------------------------------------------------------ the Character tab

    /** The doll's pane (SPEC 3.1): Bags' `Doll.kt` draws the slots in the rectangle given here; the middle holds the class's
     *  emblem, or the chosen piece's card. A tap on a slot chooses it (the room's own view: nothing reaches the game). */
    private fun doll(c: Canvas, r: RectF) {
        val top = r.top + 14f
        val pitch = min(104f, (r.bottom - top - 12f) / 8f)
        val s = pitch - 12f
        val lx = r.left + 30f; val rx = r.right - 30f - s
        val middle = RectF(lx + s + 26f, top + 4f, rx - 26f, top + 4f + 7f * pitch - 30f)
        val eq = equipped
        val layout = Doll.layout(lx, rx, top, s, pitch, top + 7f * pitch, r.centerX(), 18f,
            ammo = eq?.optBoolean("ammoNeeded") == true, ranged = eq?.let { !it.has("rangedShown") || it.optBoolean("rangedShown") } ?: true)
        val slot = chosenSlot
        if (slot == null || slot !in layout.boxes) { chosenSlot = null; UiKit.emblem(c, middle, model.who?.clsFile ?: "") }
        else {
            card(c, middle, slot)
            // the card's Apply (X): the kits for this slot (the same as X on the slot); More (Y, uneq1): the slot's More menu (the same
            // as Y on the slot). Both: side by side; one alone: the card's width
            val apply = worn()[slot] != null && slotKits(slot).isNotEmpty()
            val more = worn()[slot] != null && moreRows(slot).isNotEmpty()
            val bt = middle.bottom - 78f; val bb = middle.bottom - 8f
            val mid = middle.centerX()
            if (apply) {
                val ab = RectF(middle.left + 24f, bt, if (more) mid - 6f else middle.right - 24f, bb)
                UiKit.button(c, ab, "Apply", UiKit.xGlyph(), UiKit.Button.UP)
                hit(ab) { openKits(slot) }
            }
            if (more) {
                val mb = RectF(if (apply) mid + 6f else middle.left + 24f, bt, middle.right - 24f, bb)
                UiKit.button(c, mb, "More", yGlyph(), UiKit.Button.UP)
                hit(mb) { openMore(slot) }
            }
        }
        Doll.draw(c, layout, worn(), eq?.optJSONObject("ammo"), eq?.optBoolean("relic") == true,
            alertColours = eq?.optJSONArray("alertc"))
        chosenSlot?.let { layout.boxes[it]?.let { box -> UiKit.slotFocus(c, box) } }
        for ((k, box) in layout.boxes) hit(box, "slot:$k", slot = true) { chosenSlot = if (chosenSlot == k) null else k; scroll["card"] = 0f; invalidate() }
    }

    private fun worn(): Map<Int, JSONObject> {
        val out = HashMap<Int, JSONObject>()
        val a = equipped?.optJSONArray("slots") ?: return out
        for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; out[o.optInt("slot")] = o }
        return out
    }

    /** a chosen piece's card: the game's tooltip lines (Bags' `tip`: the name first in its quality's colour, then every line in
     *  the colour the game gives it, right-hand words right-aligned); an empty slot: the slot's name alone, dim */
    private fun card(c: Canvas, r: RectF, slot: Int) {
        val inner = UiKit.menu(c, r, solid = true)
        hit(r) { chosenSlot = null; invalidate() }
        val item = if (slot == 0) equipped?.optJSONObject("ammo") else equippedSlot(slot)
        val key = "card"
        c.save(); c.clipRect(inner)
        var y = inner.top + 16f - (scroll[key] ?: 0f)
        val start = y
        val width = (inner.width() - 28f).toInt()
        fun line(t: String, colour: Int, size: Float, right: String = "", rightColour: Int = colour) {
            if (t.isEmpty() && right.isEmpty()) return
            val sl = layout(t, UiKit.bodyPaint(size, colour), width, 4f)
            c.save(); c.translate(inner.left + 14f, y); sl.draw(c); c.restore()
            if (right.isNotEmpty()) {
                val pr = UiKit.bodyPaint(size, rightColour).apply { textAlign = Paint.Align.RIGHT }
                c.drawText(Tr.t(right), inner.right - 14f, y - pr.ascent(), pr)
            }
            y += sl.height + 6f
        }
        val tip = item?.optJSONArray("tip")
        when {
            item == null || !item.has("id") -> line(Doll.NAMES[slot] ?: "", Theme.dim, 30f)
            tip != null && tip.length() > 0 -> for (k in 0 until tip.length()) {
                val l = tip.optJSONObject(k) ?: continue
                val first = k == 0
                val col = if (first) Theme.quality(item.optInt("quality")) else parseColour(l.optString("c"), Color.WHITE)
                line(l.optString("t"), col, if (first) 30f else 23f, l.optString("r"), parseColour(l.optString("rc"), col))
            }
            else -> {
                line(item.optString("item"), Theme.quality(item.optInt("quality")), 30f)
                if (item.optBoolean("use")) line(item.optString("useName"), Theme.buffed, 23f)
            }
        }
        c.restore()
        dragged(c, inner, key, y - start + 16f)
        // the piece's options: the card's More (uneq1: Unequip first; Bind for an on-use piece may follow there)
    }

    private fun equippedSlot(slot: Int): JSONObject? {
        val a = equipped?.optJSONArray("slots") ?: return null
        for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; if (o.optInt("slot") == slot) return o }
        return null
    }

    private fun hasPetTab() = model.pet.isNotEmpty() || model.petInfo != null
    private fun petShown() = model.side == CharSide.PET && hasPetTab()

    /** THE PET PAGE in the doll's place (SPEC 3.2, as this client has it: AGENT_LOG 8824a67): the class's emblem as the
     *  ground, the pet's picture where the game shows it in 3D, a hunter's pet's face top left; its name, level line,
     *  loyalty, and its own experience bar. A tap on the face shows the game's tooltip lines for it. No buttons. */
    private var faceOpen = false
    private fun petPage(c: Canvas, list: RectF) {
        val p = model.petInfo
        val area = RectF(list.left + 30f, list.top + 18f, list.right - 30f, list.bottom - 18f)
        val classFile = model.who?.clsFile ?: ""
        if (p == null) {
            UiKit.emblem(c, area, classFile)
            centred(c, area.centerX(), area.centerY(), model.word("PET", "Pet"), 25f, Theme.dim)
            return
        }
        val mood = if (p.hunter) p.happy.coerceIn(0, 3) else 0
        val under = UiKit.petPage(c, area, classFile, Icons.get(p.icon), mood)
        val pn = UiKit.titlePaint(44f).apply { textAlign = Paint.Align.CENTER }
        c.drawText(Tr.t(p.name), under.centerX(), under.top + 40f, pn)
        val pl = UiKit.bodyPaint(27f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
        c.drawText(Tr.t(p.line), under.centerX(), under.top + 86f, pl)
        if (p.hunter && p.loyalty.isNotEmpty()) {
            val py = UiKit.bodyPaint(24f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
            c.drawText(Tr.t(p.loyalty), under.centerX(), under.top + 124f, py)
        }
        if (p.xpMax > 0) UiKit.standing(c, RectF(under.left + 30f, under.bottom - 70f, under.right - 30f, under.bottom - 6f),
            Theme.standing(5), p.xpFill, p.xpText)
        if (mood > 0) {
            val face = RectF(area.left + 18f, area.top + 18f, area.left + 114f, area.top + 114f)
            hit(face, "face") { faceOpen = !faceOpen; invalidate() }
            if (faceOpen) {
                // the game's happiness tooltip: how it feels, the damage for that, the loyalty's way, the diet
                val lines = listOf(p.happyText to Color.WHITE, p.damageText to Theme.gold, p.loyaltyNote to Theme.gold, p.diet to Theme.gold)
                    .filter { it.first.isNotEmpty() }
                // the box is as tall as its lines (the menu's frame is 10 px inside the box)
                val left = area.left + 18f; val right = area.right - 18f
                val laid = lines.mapIndexed { i, l ->
                    layout(l.first, UiKit.bodyPaint(if (i == 0) 28f else 23f, l.second), (right - left - 20f - 28f).toInt(), 4f)
                }
                val tall = 20f + 12f + laid.sumOf { it.height + 6 }.toFloat() + 12f
                val box = RectF(left, face.bottom + 10f, right, face.bottom + 10f + tall)
                val inner = UiKit.menu(c, box, solid = true)
                hit(box) { faceOpen = false; invalidate() }
                var y = inner.top + 12f
                for (sl in laid) {
                    c.save(); c.translate(inner.left + 14f, y); sl.draw(c); c.restore()
                    y += sl.height + 6f
                }
            }
        } else faceOpen = false
    }

    /** the right pane of the Character tab: the small tabs, then Stats / Gear sets / Pet. SPEC 3.2 */
    private fun rightPane(c: Canvas, r: RectF) {
        val tabs = ArrayList<CharSide>().apply { add(CharSide.STATS); add(CharSide.SETS); if (hasPetTab()) add(CharSide.PET) }
        if (model.side !in tabs) model.side = CharSide.STATS
        for ((i, t) in tabs.withIndex()) {
            val box = RectF(r.left + i * 104f, r.top + 2f, r.left + i * 104f + 96f, r.top + 98f)
            UiKit.smallTab(c, box, pic(SMALL_TAB_ICON[t.ordinal]), t == model.side)
            tabCursors["small:$i"] = { cc -> UiKit.smallTab(cc, box, pic(SMALL_TAB_ICON[t.ordinal]), t == model.side, cursor = true) }
            hit(RectF(r.left + i * 104f, r.top, r.left + i * 104f + 104f, r.top + 104f), "small:$i") { if (model.side != t) { model.side = t; invalidate() } }
        }
        when (model.side) {
            CharSide.STATS -> sections(c, RectF(r.left, r.top + 108f, r.right, r.bottom), withXp(model.stats), "stats")
            CharSide.SETS -> sets(c, r)
            CharSide.PET -> pet(c, r)
        }
    }

    /** the character's stats with the experience line as the LAST line of General (SPEC 3.2): the game's own bar text split
     *  at the colon ("XP" / "1204/2800"); when rested one more line, the rest state's name and the rested XP, its value in
     *  the game's blue. Left out at the top level (the game's rule, `xp.show`). */
    private fun withXp(src: List<StatSection>): List<StatSection> {
        val xp = CharacterBars.xp ?: return src
        if (!xp.show || xp.max <= 0 || src.isEmpty()) return src
        val rows = ArrayList(src[0].rows)
        val text = xp.text.ifEmpty { "XP: %d/%d".format(xp.cur, xp.max) }
        val label = text.substringBefore(":", "XP").trim()
        val value = text.substringAfter(":", "%d/%d".format(xp.cur, xp.max)).trim()
        rows += StatRow("XP", label, value, 0, xp.stale, emptyList())
        if (xp.rested && xp.rest > 0) {
            val state = xp.lines.getOrNull(1)?.substringBefore("\n")?.trim()?.takeIf { it.isNotEmpty() } ?: "Rested"
            rows += StatRow("REST", state, big(xp.rest), BLUE, false, emptyList())
        }
        return listOf(StatSection(src[0].title, rows)) + src.drop(1)
    }

    /** stat sections in the SPEC's shape: a 58 px title, 4 px, its 44 px lines, 8 px; dragged when longer than the pane */
    private fun sections(c: Canvas, r: RectF, secs: List<StatSection>, key: String) {
        c.save(); c.clipRect(r)
        var y = r.top - (scroll[key] ?: 0f)
        val start = y
        for (s in secs) {
            UiKit.sectionTitle(c, RectF(r.left, y, r.right, y + 58f), s.title)
            y += 62f
            for ((i, row) in s.rows.withIndex()) {
                val icon = if (row.key in RESIST) UiKit.tex("resist_" + row.key) else null
                val line = RectF(r.left, y, r.right, y + 44f)
                if (row.stale) staleLine(c, line, label(row.label), row.value, i % 2 == 0, icon)
                else UiKit.statLine(c, line, label(row.label), row.value, row.colour, i % 2 == 0, icon)
                y += 44f
            }
            y += 8f
        }
        c.restore()
        dragged(c, r, key, y - start)
    }

    /**
     * THE GEAR SETS (the game's Equipment Manager pane; f-equipment-manager, the user's pick "Equip + Save"): the character's sets, one
     * chosen as the game's pane chooses one (the room's own view), and under the list the game's two buttons for it: Equip (the
     * set goes on) and Save (the set takes what is worn now; the add-on asks first, as the game does, [setQuestion]). Both greyed in
     * a fight, as the game refuses changing armour then; a refused tap says why under them. New / rename / delete: the game's window.
     */
    private fun sets(c: Canvas, r: RectF) {
        if (model.sets.isEmpty()) {
            centred(c, r.centerX(), r.top + 220f, model.word("PAPERDOLL_EQUIPMENTMANAGER", "Equipment Manager"), 25f, Theme.dim)
            return
        }
        if (model.sets.none { it.id == chosenSet }) chosenSet = model.sets.firstOrNull { it.equipped }?.id
        val buttonsTop = r.bottom - 92f
        val area = RectF(r.left, r.top + 112f, r.right, buttonsTop - 46f)
        c.save(); c.clipRect(area)
        val oldClip = clip; val oldPane = paneKey
        clip = area; paneKey = "sets"
        var y = area.top - (scroll["sets"] ?: 0f)
        val start = y
        for (s in model.sets) {
            val row = RectF(r.left, y, r.right, y + 111f)
            UiKit.gearSet(c, row, pic(s.icon), s.name, s.equipped, s.id == chosenSet)
            hit(row, "set:${s.id}") { chosenSet = s.id; invalidate() }
            y += 117f
        }
        clip = oldClip; paneKey = oldPane
        c.restore()
        dragged(c, area, "sets", y - start)
        // the game's two buttons for the chosen set
        val set = model.sets.firstOrNull { it.id == chosenSet }
        val pad = model.setPad
        val usable = set != null && pad != null && pad.row.isNotEmpty() && !model.combat
        val half = (r.width() - 16f) / 2f
        val eq = RectF(r.left, buttonsTop, r.left + half, r.bottom - 6f)
        val sv = RectF(r.right - half, buttonsTop, r.right, r.bottom - 6f)
        UiKit.button(c, eq, model.word("EQUIPSET_EQUIP", "Equip"), if (usable && set?.equipCell != null) UiKit.Button.UP else UiKit.Button.DISABLED)
        UiKit.button(c, sv, model.word("SAVE", "Save"), if (usable && set?.saveCell != null) UiKit.Button.UP else UiKit.Button.DISABLED)
        hit(eq, "set:equip") { if (usable) set?.equipCell?.let { setTap("equip ${set.name}", it) } }
        hit(sv, "set:save") { if (usable) set?.saveCell?.let { setTap("save ${set.name}", it) } }
        // a refused tap, the game's words where it has them, for a few seconds
        val ack = pad?.ack?.takeIf { it.op != "unequip" }
        if (ack != null && !ack.ok && ack.seq == ackShownSeq && android.os.SystemClock.uptimeMillis() < ackUntil) {
            centred(c, r.centerX(), buttonsTop - 24f, ack.why, 22f, Color.rgb(255, 32, 32))
        }
    }

    /** the gear set the pane has chosen (the room's own view, as the game's pane keeps one chosen) */
    private var chosenSet: Int? = null
    /** the add-on's last refusal shown under the buttons: its number, and until when */
    private var ackShownSeq = -1
    private var ackUntil = 0L

    /** one tap on the add-on's `eqset` row (Equip / Save of a set, Yes / No of the question) */
    private fun setTap(why: String, col: Int) {
        val g = model.grid ?: return
        val row = model.setPad?.row?.takeIf { it.isNotEmpty() } ?: return
        val ok = TapBridge.tap(context, g.pad, row, col)
        Log.i(TAG, "character: gear sets $why -> ${if (ok) "tapped" else "refused"} $row:$col")
    }

    /**
     * THE GAME'S QUESTION before a set is saved (CONFIRM_SAVE_EQUIPMENT_SET, its Yes / No), over the room while the add-on asks it:
     * only its two buttons answer then (finger and controller); B is the add-on's while it asks, and forgets it (one step back).
     */
    private fun setQuestion(c: Canvas, w: Float, h: Float) {
        val pad = model.setPad ?: return
        val ask = pad.ask ?: return
        hits.clear(); focusables.clear(); tabCursors.clear()
        UiKit.cover(c, RectF(0f, 0f, w, h))
        val r = RectF((w - 980f) / 2f, 260f, (w + 980f) / 2f, 640f)
        UiKit.dialog(c, r)
        val pt = UiKit.bodyPaint(32f, Color.WHITE)
        val sl = StaticLayout.Builder.obtain(ask.text, 0, ask.text.length, TextPaint(pt), (r.width() - 200f).toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(6f, 1f).build()
        c.save(); c.translate(r.left + 100f, r.top + 92f); sl.draw(c); c.restore()
        val cx = w / 2f
        val yes = RectF(cx - 14f - 360f, r.bottom - 160f, cx - 14f, r.bottom - 60f)
        val no = RectF(cx + 14f, r.bottom - 160f, cx + 14f + 360f, r.bottom - 60f)
        UiKit.button(c, yes, model.word("YES", "Yes"))
        UiKit.button(c, no, model.word("NO", "No"))
        hit(yes, "ask:yes") { pad.yes?.let { setTap("yes (${ask.name})", it) } }
        hit(no, "ask:no") { pad.no?.let { setTap("no (${ask.name})", it) } }
    }

    /** the Pet small tab's right pane: the pet's stats, as the character's (General and Resistances, zero rows dropped) */
    private fun pet(c: Canvas, r: RectF) {
        sections(c, RectF(r.left, r.top + 108f, r.right, r.bottom), model.pet, "pet")
    }

    // ------------------------------------------------------------------ the list tabs (SPEC 4)

    private enum class Kind { REP, SKILL, CUR }

    private fun listTab(c: Canvas, r: RectF, l: CharList, key: String, kind: Kind) {
        if (l.entries.isEmpty()) {
            centred(c, r.centerX(), r.top + 300f, model.word(EMPTY_WORD[kind.ordinal], ""), 25f, Theme.dim)
            return
        }
        val cw = (r.width() - 24f) / 2f
        c.save(); c.clipRect(r); clip = r; paneKey = key
        var y = r.top + 4f - (scroll[key] ?: 0f)
        val start = y
        var first = true
        for (line in l.lines(twoColumns = true)) {
            when (line) {
                is CharLine.Heading -> {
                    if (!first) y += 6f
                    val e = line.entry
                    val indent = if (e.depth > 0) 24f else 0f
                    val box = RectF(r.left + indent, y, r.right, y + 84f)
                    if (e.bar != null) {                 // a sub-heading with a bar of its own: an entry over both columns
                        val eb = RectF(r.left + 12f + indent, y, r.right - 12f, y + 111f)
                        UiKit.listLine(c, eb, l.selected == e.key)
                        name(c, e.name, eb.left + 22f, eb.top + 55f, eb.width() - 360f, if (l.selected == e.key) Theme.gold else Color.WHITE)
                        bar(c, RectF(eb.right - 330f, eb.top + 22f, eb.right - 22f, eb.bottom - 22f), e, kind, 22f)
                        hit(eb, "head:${e.key}") { l.toggle(e.key); invalidate() }
                        y += 117f
                    } else {
                        UiKit.listHeading(c, box, e.name, !line.folded)
                        hit(box, "head:${e.key}") { if (l.toggle(e.key)) invalidate() }
                        y += 84f
                    }
                }
                is CharLine.Row -> {
                    for ((i, e) in listOfNotNull(line.left, line.right).withIndex()) {
                        val x = r.left + 12f + i * cw
                        val box = RectF(x, y, x + cw, y + 111f)
                        entry(c, box, e, l.selected == e.key, kind)
                        hit(box, "entry:${e.key}") { if (l.select(e.key)) invalidate() }
                    }
                    y += 111f
                }
            }
            first = false
        }
        c.restore(); clip = null; paneKey = null
        dragged(c, r, key, y - start)
    }

    private fun entry(c: Canvas, box: RectF, e: CharEntry, chosen: Boolean, kind: Kind) {
        UiKit.listLine(c, box, chosen)
        if (kind == Kind.CUR) {
            UiKit.slot(c, Icons.get(e.icon), box.left + 18f, box.top + 22f, 66f)
            val col = if (e.grey) Theme.dim else if (chosen) Theme.gold else Color.WHITE
            name(c, e.name, box.left + 100f, box.top + 40f, box.right - box.left - 112f, col)
            val pn = UiKit.numberPaint(28f, if (e.grey) Theme.dim else Color.WHITE)
            c.drawText(Tr.t(e.value), box.left + 100f, box.top + 78f - (pn.ascent() + pn.descent()) / 2f, pn)
            return
        }
        val col = when { e.red -> Theme.unusable; chosen -> Theme.gold; else -> Color.WHITE }
        name(c, e.name, box.left + 22f, box.top + 32f, 292f, col)
        bar(c, RectF(box.left + 16f, box.top + 50f, box.right - 16f, box.bottom - 8f), e, kind, 22f)
    }

    private fun bar(c: Canvas, r: RectF, e: CharEntry, kind: Kind, size: Float) {
        val b = e.bar ?: return
        val colour = if (kind == Kind.SKILL) Theme.skillBar else barColour(b)
        UiKit.standing(c, r, colour, b.fill, b.label, size)
    }

    // ------------------------------------------------------------------ the detail pane (SPEC 5)

    private fun titleBlock(c: Canvas, r: RectF, title: String, sub: String): Float {
        fitCentred(c, title, r.centerX(), r.top + 44f, r.width(), 34f, 26f, Theme.gold)
        if (sub.isNotEmpty()) centred(c, r.centerX(), r.top + 86f, sub, 25f, Color.WHITE)
        UiKit.paneRule(c, r.left, r.top + 108f, r.right)
        return r.top + 132f
    }

    /** the empty detail pane's sentence ("Select a faction to see its details."), wrapped inside the pane with a 24 px
     *  margin each side and centred on its old line (UI Art's nit 2026-09-30: one line ran into the side tabs) */
    private fun prompt(c: Canvas, r: RectF, word: String, fallback: String) {
        val t = model.word(word, fallback)
        if (t.isEmpty()) return
        val sl = StaticLayout.Builder.obtain(Tr.span(t), 0, Tr.span(t).length, TextPaint(UiKit.bodyPaint(25f, Theme.dim)), max(1, (r.width() - 48f).toInt()))
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(4f, 1f).build()
        c.save(); c.translate(r.left + 24f, r.top + 300f - sl.height / 2f); sl.draw(c); c.restore()
    }

    private fun repDetail(c: Canvas, r: RectF) {
        val e = model.reputation.selectedEntry() ?: return prompt(c, r, "REPUTATION_DETAIL_SELECT_PROMPT", "")
        val y = titleBlock(c, r, e.name, e.bar?.label ?: "")
        e.bar?.let { b ->
            UiKit.standing(c, RectF(r.left + 10f, y, r.right - 10f, y + 64f), barColour(b), b.fill, b.label, 24f)
            if (b.progress.isNotEmpty()) centred(c, r.centerX(), y + 86f, b.progress, 26f, Theme.dim, number = true)
        }
        val foot = r.bottom - e.checks.size * 66f
        var yy = prose(c, RectF(r.left, y + 118f, r.right, foot - 8f), e.desc, "rep:" + e.key)
        if (e.note.isNotEmpty()) prose(c, RectF(r.left, yy + 6f, r.right, foot - 8f), e.note, null, Theme.buffed)
        checks(c, r, e.checks)
    }

    private fun skillDetail(c: Canvas, r: RectF) {
        val e = model.skills.selectedEntry() ?: return prompt(c, r, "SKILL_DETAIL_SELECT_PROMPT", "")
        val y = titleBlock(c, r, e.name, headingOf(model.skills, e))
        e.bar?.let { UiKit.standing(c, RectF(r.left + 10f, y, r.right - 10f, y + 64f), Theme.skillBar, it.fill, it.label, 24f) }
        var yy = if (e.desc.isNotEmpty()) prose(c, RectF(r.left, y + 86f, r.right, r.bottom), e.desc, null) + 18f else y + 86f
        // a weapon skill: the game's own two headings and lines (SkillDetailFrameMixin:AddWeaponSkillRows)
        var i = 0
        while (i + 1 < e.lines.size && yy < r.bottom - 60f) {
            UiKit.sectionTitle(c, RectF(r.left, yy, r.right, yy + 58f), e.lines[i])
            yy = prose(c, RectF(r.left, yy + 66f, r.right, r.bottom), e.lines[i + 1], null, Color.WHITE) + 12f
            i += 2
        }
    }

    private fun curDetail(c: Canvas, r: RectF) {
        val e = model.currency.selectedEntry() ?: return prompt(c, r, "TOKEN_DETAIL_SELECT_PROMPT", "")
        var y = titleBlock(c, r, e.name, headingOf(model.currency, e))
        UiKit.slot(c, Icons.get(e.icon), r.centerX() - 80f, y, 72f)
        val pn = UiKit.numberPaint(40f, Color.WHITE)
        c.drawText(Tr.t(e.value), r.centerX() + 10f, y + 50f, pn)
        val foot = r.bottom - e.checks.size * 66f
        y = if (e.desc.isNotEmpty()) prose(c, RectF(r.left, y + 96f, r.right, foot - 8f), e.desc, "cur:" + e.key) + 18f else y + 96f
        for ((i, row) in e.rows.withIndex()) {
            UiKit.statLine(c, RectF(r.left, y, r.right, y + 44f), row.first, row.second, 0, i % 2 == 0)
            y += 44f
        }
        if (e.note.isNotEmpty()) prose(c, RectF(r.left, y + 6f, r.right, foot - 8f), e.note, null, Theme.buffed)
        checks(c, r, e.checks)
    }

    /** the game's check boxes at the pane's foot: SHOWN, not pressed (first build: no touch box) */
    private fun checks(c: Canvas, r: RectF, rows: List<CharCheck>) {
        var y = r.bottom - rows.size * 66f
        for (row in rows) {
            UiKit.check(c, r.left + 32f, y + 33f, 56f, row.on, enabled = false)
            val p = UiKit.bodyPaint(25f, Theme.dim)
            c.drawText(Tr.t(row.label), r.left + 74f, y + 33f - (p.ascent() + p.descent()) / 2f, p)
            y += 66f
        }
    }

    // ------------------------------------------------------------------ Statistics (SPEC 7)

    private fun statistics(c: Canvas, r: RectF) {
        val l = model.statistics
        c.save(); c.clipRect(r); clip = r; paneKey = "sx"
        var y = r.top + 4f - (scroll["sx"] ?: 0f)
        val start = y
        var band = 0
        for (line in l.lines(twoColumns = false)) {
            when (line) {
                is CharLine.Heading -> {
                    val e = line.entry
                    val box = RectF(r.left + 24f * min(e.depth, 2), y, r.right, y + 84f)
                    UiKit.listHeading(c, box, e.name, !line.folded)
                    hit(box, "head:${e.key}") { if (l.toggle(e.key)) invalidate() }
                    y += 84f; band = 0
                }
                is CharLine.Row -> {
                    val e = line.left
                    UiKit.statLine(c, RectF(24f, y, r.right - 12f, y + 52f), e.name, e.value.ifEmpty { "--" }, 0, band % 2 == 0)
                    y += 52f; band++
                }
            }
        }
        c.restore(); clip = null; paneKey = null
        dragged(c, r, "sx", y - start)
    }

    // ------------------------------------------------------------------ helpers

    private fun headingOf(l: CharList, e: CharEntry): String {
        var h = ""
        for (x in l.entries) { if (x.key == e.key) break; if (x.header && x.depth < e.depth) h = x.name }
        return h
    }

    private fun barColour(b: CharBar): Int = when (b.colour) {
        "blue" -> Theme.skillBar
        else -> Theme.standing(b.colour.toIntOrNull() ?: 5)
    }

    /** the game writes "Strength:"; the SPEC's lines carry no colon */
    private fun label(s: String) = s.trimEnd().removeSuffix(":")

    private fun pic(fid: Int): Bitmap? = if (fid <= 0) null else UiKit.icon(fid) ?: Icons.get(fid)

    private fun big(n: Long): String = "%,d".format(n)

    /** the pane being drawn: a tap box drawn inside it is cut to it, so a row scrolled out of view cannot take a tap */
    private var clip: RectF? = null

    private fun hit(r: RectF, key: String? = null, slot: Boolean = false, act: () -> Unit) {
        if (key != null) focusables += Focus(key, RectF(r), slot, clip?.let { RectF(it) }, paneKey, act)
        val box = RectF(r)
        clip?.let { if (!box.intersect(it)) return }
        hits += Hit(box, act)
    }

    /** [r] shows [height] px of something dragged under [key]: remember it for the finger, and draw the scroll bar when it
     *  is longer than [r] (SPEC 2: `UiKit.scrollBar` at the pane's right edge, 14 px) */
    private fun dragged(c: Canvas, r: RectF, key: String, height: Float) {
        content[key] = height
        drags += RectF(r) to key
        val off = scroll[key] ?: 0f
        if (height > r.height()) UiKit.scrollBar(c, RectF(r.right - 14f, r.top, r.right, r.bottom), off / height, (off + r.height()) / height)
        else if (off != 0f) scroll[key] = 0f
    }

    /** a stat kept from before the fight (the user's ruling: greyed until the fight ends): the kit's band and icon, then the
     *  label and value in the kit's dim colour at the kit's own places (statLine has no greyed state) */
    private fun staleLine(c: Canvas, r: RectF, label: String, value: String, band: Boolean, icon: Bitmap?) {
        UiKit.statLine(c, r, "", "", 0, band, icon)
        val x = if (icon != null) r.left + 58f else r.left + 16f
        val pv = UiKit.bodyPaint(26f, Theme.dim).apply { textAlign = Paint.Align.RIGHT }
        c.drawText(Tr.t(value), r.right - 16f, r.centerY() - 1f - (pv.ascent() + pv.descent()) / 2f, pv)
        val pl = UiKit.bodyPaint(26f, Theme.dim)
        val room = r.right - 16f - pv.measureText(value) - 18f - x
        c.drawText(Tr.t(TextUtils.ellipsize(label, pl, room, TextUtils.TruncateAt.END).toString()), x,
            r.centerY() - 1f - (pl.ascent() + pl.descent()) / 2f, pl)
    }

    private fun centred(c: Canvas, x: Float, y: Float, t: String, size: Float, colour: Int, number: Boolean = false) {
        if (t.isEmpty()) return
        val p = (if (number) UiKit.numberPaint(size, colour) else UiKit.bodyPaint(size, colour)).apply { textAlign = Paint.Align.CENTER }
        c.drawText(Tr.t(t), x, y - (p.ascent() + p.descent()) / 2f, p)
    }

    private fun fitCentred(c: Canvas, t: String, x: Float, y: Float, width: Float, size: Float, minSize: Float, colour: Int) {
        var s = size
        var p = UiKit.bodyPaint(s, colour)
        while (s > minSize && p.measureText(t) > width) { s -= 1f; p = UiKit.bodyPaint(s, colour) }
        p.textAlign = Paint.Align.CENTER
        val shown = TextUtils.ellipsize(Tr.t(t), TextPaint(p), width, TextUtils.TruncateAt.END).toString()
        c.drawText(Tr.t(shown), x, y - (p.ascent() + p.descent()) / 2f, p)
    }

    /** a name, set smaller down to 21 px, then ellipsized (SPEC 4) */
    private fun name(c: Canvas, t: String, x: Float, centreY: Float, width: Float, colour: Int) {
        var s = 26f
        var p = UiKit.bodyPaint(s, colour)
        while (s > 21f && p.measureText(t) > width) { s -= 1f; p = UiKit.bodyPaint(s, colour) }
        val shown = TextUtils.ellipsize(Tr.t(t), TextPaint(p), width, TextUtils.TruncateAt.END).toString()
        c.drawText(Tr.t(shown), x, centreY - (p.ascent() + p.descent()) / 2f, p)
    }

    /** prose (24 px gold, line spacing + 6), clipped to [r]; dragged when [key] is given and it is longer. Returns its end. */
    private fun prose(c: Canvas, r: RectF, text: String, key: String?, colour: Int = Theme.gold): Float {
        if (text.isEmpty() || r.height() <= 0f) return r.top
        val sl = layout(text, UiKit.bodyPaint(24f, colour), (r.width() - 12f).toInt(), 6f)
        c.save(); c.clipRect(r)
        c.translate(r.left + 6f, r.top - (if (key != null) scroll[key] ?: 0f else 0f)); sl.draw(c)
        c.restore()
        if (key != null) dragged(c, r, key, sl.height.toFloat())
        return r.top + min(sl.height.toFloat(), r.height())
    }

    private fun layout(t: String, p: Paint, width: Int, spacing: Float): StaticLayout =
        StaticLayout.Builder.obtain(Tr.span(t), 0, Tr.span(t).length, TextPaint(p), max(1, width))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(spacing, 1f).build()

    private fun parseColour(hex: String, fallback: Int): Int =
        try { if (hex.isEmpty()) fallback else Color.parseColor(if (hex.startsWith("#")) hex else "#$hex") } catch (_: Exception) { fallback }

    // ------------------------------------------------------------------ touch: tap = the last box drawn under the finger;
    // a move past the slop drags whatever list, prose or card is under it (SPEC 8)

    private var downX = 0f; private var downY = 0f; private var lastY = 0f
    private var dragKey: String? = null
    private var moved = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastY = e.y; moved = false
                dragKey = drags.lastOrNull { it.first.contains(e.x, e.y) }?.second
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && abs(e.y - downY) > SLOP) moved = true
                val k = dragKey
                // issues #91 / #85 / #77 (2026-11-11): a redraw between the down and this move can drop the dragged box (a new
                // tab, a list that emptied): drags.last { } threw NoSuchElementException. Gone = the drag ends, nothing scrolls.
                val r = if (moved && k != null) dragBox(drags, k) else null
                if (moved && k != null && r == null) dragKey = null
                if (r != null && k != null) {
                    val maxOff = max(0f, (content[k] ?: 0f) - r.height())
                    scroll[k] = ((scroll[k] ?: 0f) - (e.y - lastY)).coerceIn(0f, maxOff)
                    invalidate()
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (kitFor != null) {
                    // the list: a row applies, Close / outside closes
                    val k = kitHits.firstOrNull { it.first.contains(e.x, e.y) }?.second
                    if (k != null && k >= 0) applyKit(k) else closeKits()
                    invalidate()
                    return true
                }
                if (replFor != null) {
                    // char-more, the Replace list: a row equips (as A on it), Back / outside goes back to the menu (as B)
                    val k = replHits.firstOrNull { it.first.contains(e.x, e.y) }?.second
                    if (k != null && k >= 0) { replSel = k; doReplace(k) } else closeSub()
                    invalidate()
                    return true
                }
                if (moreFor != null) {
                    // uneq1, the More menu: a row chooses (as A on it), Back / outside closes (as B)
                    val k = moreHits.firstOrNull { it.first.contains(e.x, e.y) }?.second
                    if (k != null && k >= 0) { moreSel = k; chooseMore(k) } else closeMore()
                    invalidate()
                    return true
                }
                if (!moved && !underHome(e.x, e.y)) hits.lastOrNull { it.r.contains(e.x, e.y) }?.act?.invoke()
            }
        }
        return true
    }

    /** nothing to tap in x 550 .. 690 of the room's last 34 px: Home's button stands there (SPEC 2) */
    private fun underHome(x: Float, y: Float) = x in 550f..690f && y > height - 34f

    companion object {
        private const val TAG = "CharacterView"
        /** the box last drawn for drag [key], or null when this frame drew none (issues #91 / #85 / #77, 2026-11-11: was .last { }) */
        internal fun <B> dragBox(drags: List<Pair<B, String>>, key: String?): B? =
            if (key == null) null else drags.lastOrNull { it.second == key }?.first
        /** the hold unconfirmed this long (no CURRENT `win` with ctl) = asked again (Group Finder's / Social's 700 ms) */
        private const val HOLD_CHECK_MS = 700L
        private const val SLOP = 14f
        /** a kit used from the Character room: the game's "pick an item" should start within this, else the pick is stopped */
        private const val KIT_WAIT_MS = 2_500L
        /** the kit's statLine state for a value in the game's light blue (the rested XP; UI Art's e116707) */
        private const val BLUE = 3
        private val TAB_PICTURE = arrayOf("", "tab_reputation", "tab_skills", "", "tab_currency", "tab_statistics")
        private val TAB_WORD = arrayOf("CHARACTER", "REPUTATION", "SKILLS", "PVP", "CURRENCY", "STATISTICS")
        private val TAB_FALLBACK = arrayOf("Character", "Reputation", "Skills", "PvP", "Currency", "Statistics")
        private val EMPTY_WORD = arrayOf("REPUTATION_DETAIL_SELECT_PROMPT", "SKILL_DETAIL_SELECT_PROMPT", "TOKEN_DETAIL_SELECT_PROMPT")
        private val RESIST = setOf("arcane", "fire", "frost", "nature", "shadow")
        /** the side tabs' icons, found offline (AGENT_LOG e8f3d1c); the addon's `tabs` part confirms them in the game */
        private val SIDE_TAB_ICON = mapOf("rep" to 8197103, "skills" to 2447783, "cur" to 8197078, "stats" to 8197104)
        /** the three small tabs over the stats: UI Art's sample pictures (Stats, Gear sets, Pet) */
        private val SMALL_TAB_ICON = intArrayOf(132222, 132626, 132161)
        /** the class icons (Interface/ICONS/ClassIcon_<Class>) for the Character tab, as UI Art's sample draws the hunter's */
        private val CLASS_ICON = mapOf(
            "WARRIOR" to 626008, "PALADIN" to 626003, "HUNTER" to 626000, "ROGUE" to 626005, "PRIEST" to 626004,
            "SHAMAN" to 626006, "MAGE" to 626001, "WARLOCK" to 626007, "DRUID" to 625999,
        )
    }
}
