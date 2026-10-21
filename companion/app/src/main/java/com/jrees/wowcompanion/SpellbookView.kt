package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextUtils
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * THE SPELLBOOK ROOM: arrangement B, "the open book" (user's pick, 2026-09-28). The spells of the open line on the left page, the
 * chosen spell on the right page. Every size is UI Art's `wow-companion-design/spells/SPEC.md`; the drawing is the kit's
 * (`tools/kitpreview/SpellsSample.kt` is the reference). The data is [SpellsModel], from the addon WowCompanionSpells.
 *
 * WHO CHOOSES: a finger (app only), or, while the room holds the controller, the addon's gold glow (`spellsctl`), which then wins:
 * each controller document puts the glow where the addon has it. A tap on Bind hands the spell to [onBind] (one pad tap); the
 * press that answers the Bind box comes from the controller. The Bind box and the "New spell" card are [SpellsOverlay].
 *
 * THE CONTROLLER TAB: REMOVED from the book (the user 2026-10-05: "it should actually be a home tab instead of a spellbook tab"): the
 * page is its own room now, [ControllerView] (proven on b48). What follows is the old tab's history; the code paths stay unused.
 * (Was: the user 2026-09-30; UI Art's PADTAB_SPEC): a tab after the last skill line, the game's controller picture. Open, its
 * pages are [PadTabPage]'s. Opened by a finger (a no-action tap: the addon enters its half, Book.lua b4) or by the controller (Right past
 * the last line, Down); a finger on a skill line's tab leaves it (another no-action tap).
 */
class SpellbookView(
    context: Context,
    /** Bind was touched for this spell (MainActivity taps its pad column) */
    private val onBind: (SpellsModel.Entry) -> Unit,
    /** the door to the Talents room was touched; null = there is no Talents room yet, and then NO door is drawn (UI Art, 52e6c41) */
    private val onDoor: (() -> Unit)? = null,
    /** the Controller tab's cells on spells' own pad rows (no-action picks, then at most ONE action), in order; true = the taps went out */
    onPadTab: (List<Pair<String, Int>>) -> Boolean = { false },
    /** another view reads the controller now (one reader at a time): the tab does not read the triggers then */
    otherReader: () -> Boolean = { false },
) : View(context), BLeadsHome {

    private var book: SpellsModel.Book? = null
    private var ctl: SpellsModel.Ctl? = null
    private var tabKey: String? = null
    private var page = 1
    private var chosenId: Int? = null           // the chosen entry, by spell id (action id when it has none)
    private var descScroll = 0f
    private var descMax = 0f

    init { CastSettings.init(context) }

    /** true while the addon holds the controller for this room */
    val held: Boolean get() = ctl?.on == true

    /** RC3 (Spells' condition, Book.lua onPress's leaveB): the book in mode "book"; B cancels the Bind box / its answer */
    override fun bLeadsHome(): Boolean = ctl?.let { c ->
        c.on && !c.croom && !c.talentsPage && c.mode == "book" && !(c.ctrlTab && !c.onTabs)
    } == true

    // ---------------------------------------------------------------- the Controller tab

    private val padTabCells = onPadTab
    private val padTab = PadTabPage(this, { cells -> onPadTab(cells) }, { e -> onBind(e) }, otherReader, cursorOnTabs = { cursorOnTabs() })

    /**
     * HOME'S ONE RULE FOR EVERY ROOM (tracking's yes, 2026-09-30, after the Calendar guest kept the controller): let go of the shared
     * capture exactly as leaving the room does (the spells row's `close` cell, NO game action), WHATEVER this view's own flags say; the
     * addon frees its guest even when it thinks it is not holding (Book b8). Home calls it when a current interact document names the
     * holder "spells" while neither the Spellbook nor the Talents room is on screen. True when the tap went out; one log line.
     */
    fun freeAgain(): Boolean {
        val sent = padTabCells(listOf("spells" to SPELLS_CLOSE_CELL))
        Log.i(TAG, "guest spells: free again (close cell ${if (sent) "tapped" else "not sent"})")
        if (tabOpen) closeTab(false)
        return sent
    }

    /**
     * EVERY ROOM THE SAME (the user 2026-10-01 via Home: "Every room opens the game's own window behind it, as Professions does"). Home's
     * show() calls these on EVERY route into / out of the Spellbook and Talents rooms (touch, the bar, the overlay's jump), with its own
     * guards (enter only while !held or when the page must move; leave only while held).
     * [enterRoom] taps the `spells` row's open cell (the Spellbook face) or topen (the Talents face): a secure click on the game's own
     * button, so the game's window opens behind the room and the addon's hold is the window's (Book b12; a cell does nothing while its face
     * already shows, so a repeat never closes it). [leaveRoom] taps close: the game's own close, then the room lets go "closed" (not B).
     * The columns come from the spells doc (`win`); an older addon without them: the old cells (spells 0 / 1, talents 0).
     * The trainer's lend never opens the game's book over the trainer window. True when a tap went out.
     */
    fun enterRoom(talents: Boolean): Boolean {
        if (ctl?.via == "trainer") return false
        val w = book?.win ?: emptyMap()
        val cell = if (talents) w["topen"]?.let { "spells" to it } ?: (TalentsModel.ROW_ACTIONS to TalentsModel.PAD_OPEN)
                   else "spells" to (w["open"] ?: SPELLS_OPEN_CELL)
        val sent = padTabCells(listOf(cell))
        Log.i(TAG, "room enter (${if (talents) "talents" else "book"}): ${cell.first} ${cell.second} ${if (sent) "tapped" else "not sent"}")
        return sent
    }

    fun leaveRoom(): Boolean {
        val col = book?.win?.get("close") ?: SPELLS_CLOSE_CELL
        val sent = padTabCells(listOf("spells" to col))
        Log.i(TAG, "room leave: spells $col ${if (sent) "tapped" else "not sent"}")
        if (tabOpen) closeTab(false)
        return sent
    }

    // the controller was refused (another room keeps it): said in plain words for a while; touch still works (Book b8)
    private var busyUntil = 0L

    // LATER item 19 (the user 2026-09-30): the room opens with the controller's cursor ON the selected tab. A finger's choice is shown at
    // once all the same: after a touch, the cursor's place on the tab row is not drawn until the controller moves again (its next document).
    private var fingerSinceCtl = false
    private fun cursorOnTabs() = ctl?.on == true && ctl?.onTabs == true && !fingerSinceCtl
    private var tabOpen = false
    private var tabGuardUntil = 0L               // a finger opened the tab: older documents do not close it

    fun onPadMap(m: PadMapModel.Map) = padTab.onMap(m)
    fun onPadTabCtl(c: PadMapModel.Ctl) = padTab.onCtl(c)
    fun onPadAck(a: PadMapModel.Ack) = padTab.onAck(a)

    private fun openTab(byTouch: Boolean) {
        tabOpen = true
        if (byTouch) {
            tabGuardUntil = SystemClock.uptimeMillis() + 2000L
            // the addon moves among the buttons as THIS layout draws them: told with the opening, when it differs
            padTabCells((if (padTab.needsLayout()) listOf(padTab.layoutCell()) else emptyList()) + (PadMapModel.ROW to PadMapModel.COL_OPENED))
        }
        padTab.setActive(isShown)
        invalidate()
    }

    private fun closeTab(byTouch: Boolean) {
        tabOpen = false
        padTab.setActive(false)
        if (byTouch && held) padTabCells(listOf(PadMapModel.ROW to PadMapModel.COL_LEFT))
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        padTab.setActive(tabOpen && isShown)
        press.setActive(isShown)
    }

    override fun onDetachedFromWindow() {
        padTab.setActive(false)
        press.setActive(false)
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- data in

    fun update(b: SpellsModel.Book) {
        book = b
        settle()
        invalidate()
    }

    fun onCtl(c: SpellsModel.Ctl) {
        val was = ctl
        ctl = c
        fingerSinceCtl = false
        if (c.note == "take-refused-busy" || c.note == "ctrl-touch-nopad") {
            busyUntil = SystemClock.uptimeMillis() + BUSY_MS
            postDelayed({ invalidate() }, BUSY_MS + 50)
        } else if (c.on) busyUntil = 0L
        // the Controller tab follows the addon: entered or lit by the controller opens it; leaving it with the controller (Left on the tab
        // row, the Talents page) closes it; the controller let go closes it. "ctrl-left" is a finger's own leave (no echo).
        when {
            !c.on -> if (tabOpen && was?.on == true) closeTab(false)
            // the Controller tab is its OWN ROOM now (ControllerView, proven on b48): the book never opens its page any more
            c.ctrlTab -> {}
            tabOpen && was?.ctrlTab == true && c.note != "ctrl-left" && SystemClock.uptimeMillis() >= tabGuardUntil -> closeTab(false)
        }
        if (c.on) {
            c.line?.let { tabKey = "line:$it" }
            page = c.page
            // the addon's glow: its spell, found in OUR list (both follow the game's filter)
            c.selected?.let { chosenId = it }
            descScroll = 0f
        }
        settle()
        invalidate()
    }

    /** open the book at a spell (the "New spell" card was tapped) */
    fun openAt(spellId: Int) {
        val b = book ?: return
        for (t in SpellsModel.tabs(b)) {
            val at = SpellsModel.placeOf(t, spellId, PER_PAGE) ?: continue
            tabKey = t.key; page = at.first; chosenId = spellId; descScroll = 0f
            invalidate()
            return
        }
    }

    // keep tab, page and choice on things that exist
    private fun settle() {
        val b = book ?: return
        val tabs = SpellsModel.tabs(b)
        if (tabs.isEmpty()) return
        val tab = tabs.firstOrNull { it.key == tabKey } ?: tabs.first()
        tabKey = tab.key
        val pages = pagesOf(tab)
        page = page.coerceIn(1, pages)
        val onPage = pageOf(tab)
        if (onPage.isNotEmpty() && onPage.none { isChosen(b, it) }) {
            // a spell chosen on another page stays chosen only if it is still in the tab; else the page's first known entry
            if (tab.entries.none { isChosen(b, it) }) chosenId = idOf(onPage[SpellsModel.firstKnown(b, onPage)])
        }
    }

    private fun idOf(e: SpellsModel.Entry) = e.spellId ?: e.actionId
    /** a tab's picture: the Professions tab wears the game's professions emblem, the Professions room's own bar icon (UI Art's pick, b28) */
    private fun tabIcon(t: SpellsModel.Tab) = if (t.prof) UiKit.tex("ricon_professions") else Icons.get(t.icon)
    // b23: the General tab's professions start on a page of their own (SpellsModel / Book.lua page alike)
    private fun pagesOf(t: SpellsModel.Tab) = SpellsModel.pagesOf(t, PER_PAGE)
    private fun pageOf(t: SpellsModel.Tab) = SpellsModel.pageOf(t, page, PER_PAGE)
    /** [e] is the chosen spell, or the chosen one is a rank of it (b23's rank picker shows a lower rank in its place) */
    private fun isChosen(b: SpellsModel.Book, e: SpellsModel.Entry) =
        idOf(e) == chosenId || SpellsModel.ranksOf(b, e).any { idOf(it) == chosenId }

    /** a face button's glyph by its place (5 X, 6 Y, 7 B, 8 A: the game's keys, drawn in the Settings' print) */
    private fun glyph(p: Int) = SpellsModel.combo(SpellsModel.PadSlot(0, 1, 1, p), style()).button
    private fun style(): SpellsModel.PadStyle = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> SpellsModel.PadStyle.LTR
        CastSettings.ButtonLayout.PLAYSTATION -> SpellsModel.PadStyle.SHP
        CastSettings.ButtonLayout.NINTENDO -> SpellsModel.PadStyle.REV
    }

    // ---------------------------------------------------------------- geometry (SPEC 2)

    private fun tabRect(i: Int) = RectF(36f + i * 124f, 66f, 36f + i * 124f + 116f, 166f)
    /** the Controller tab's place: right after the last skill line's tab (the addon's "Right past the last line") */
    private fun ctrlIndex(lines: Int) = lines.coerceAtMost(8)
    private fun rh(h: Float) = ((h - 111f - Y0) / 5f).coerceIn(111f, 118f)
    private fun entryRect(i: Int, h: Float): RectF {
        val x = 26f + (i % 2) * CW; val y = Y0 + (i / 2) * rh(h)
        return RectF(x, y, x + CW - 8f, y + rh(h))
    }
    private fun bindRect(w: Float, h: Float) = RectF(SPLIT + 40f, h - 30f - 111f, w - 56f, h - 30f)

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        UiKit.bookGlowLive = false
        drawRoom(c)
        // the "not on any button" glow PULSES as the game's does (UI Art, art-kit e48fcc9d): redraw while one shows, ~20 a second
        if (UiKit.bookGlowLive) { UiKit.bookGlowLive = false; if (isShown) postInvalidateDelayed(50L) }
    }

    private fun drawRoom(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(Theme.ink)
        UiKit.header(c, w, "Spellbook")
        if (SystemClock.uptimeMillis() < busyUntil) {
            val pb = UiKit.bodyPaint(24f, Theme.dim).apply { textAlign = Paint.Align.RIGHT }
            c.drawText("The controller is with another room. Touch still works.", w - 24f, 31f - (pb.ascent() + pb.descent()) / 2f, pb)
        }
        val b = book
        val tabs = b?.let { SpellsModel.tabs(it) } ?: emptyList()
        val tab = tabs.firstOrNull { it.key == tabKey }
        val onTabs = cursorOnTabs()

        // tabs BEFORE the pages: the page's top edge covers their feet. The Controller tab comes after the last skill line.
        for ((i, t) in tabs.withIndex()) {
            if (i >= 8) break
            UiKit.bookTab(c, tabRect(i), tabIcon(t), !tabOpen && t.key == tab?.key, cursor = onTabs && !tabOpen && t.key == tab?.key)
        }
        if (onDoor != null) UiKit.door(c, "talents", w - 74f, 116f, 88f, doorDown)

        UiKit.bookPage(c, RectF(8f, 158f, SPLIT, h - 4f))
        UiKit.bookPage(c, RectF(SPLIT, 158f, w - 8f, h - 4f), right = true)
        UiKit.bookFold(c, SPLIT, 166f, h - 12f)

        if (tabOpen) {
            if (onTabs) UiKit.focus(c, tabRect(ctrlIndex(tabs.size)))
            padTab.draw(c, w, h, b)
            return
        }

        if (b == null || tab == null) {
            val p = UiKit.inkPaint(28f).apply { textAlign = Paint.Align.CENTER }
            c.drawText("Open the game to fill the book", SPLIT / 2f, 420f - (p.ascent() + p.descent()) / 2f, p)
            return
        }

        // the left page
        UiKit.bookHeading(c, 56f, 208f, 620f, if (SpellsModel.inSection(tab, page, PER_PAGE)) "Professions" else tab.name)
        val onPage = pageOf(tab)
        for ((i, e) in onPage.withIndex()) {
            val st = SpellsModel.status(b, e)
            UiKit.bookEntry(c, entryRect(i, h), Icons.get(e.icon), e.name, SpellsModel.sub(b, e), kitState(st),
                onBar = SpellsModel.onBar(b, e), chosen = !onTabs && isChosen(b, e), passive = e.passive,
                autoCast = SpellsModel.autoCast(e), noMana = NoMana.has(e.spellId))
        }
        val pages = pagesOf(tab)
        if (pages > 1) UiKit.bookPaging(c, 330f, (Y0 + 5f * rh(h) + h) / 2f, page, pages)

        // the right page: WHERE THE CURSOR IS (the user 2026-10-07: "when selecting a tab in the spell book, the right page should reflect
        // where the cursor is currently, instead of a spell from below"): on the tab row, the tab itself; otherwise the chosen spell
        if (onTabs) { drawTabPage(c, b, tab, w); return }
        val e = tab.entries.firstOrNull { idOf(it) == chosenId }
            ?: tab.entries.firstNotNullOfOrNull { t -> SpellsModel.ranksOf(b, t).firstOrNull { idOf(it) == chosenId } }   // b23: a lower rank
            ?: onPage.getOrNull(SpellsModel.firstKnown(b, onPage)) ?: return
        drawChosen(c, b, e, w, h)
    }

    /** the right page while the cursor is on a tab: the line's picture and name, what it holds, and what is not on a button yet (the
     *  spell page's places: picture, name, sub line, the button line, the divider, the facts) */
    private fun drawTabPage(c: Canvas, b: SpellsModel.Book, tab: SpellsModel.Tab, w: Float) {
        bindLive = false; descMax = 0f; descBox.setEmpty()
        val x0 = SPLIT + 40f; val x1 = w - 56f; val top = 188f
        UiKit.spell(c, tabIcon(tab), x0 + 36f, top + 16f, 96f)
        val nameLeft = x0 + 158f
        val pn = UiKit.inkPaint(42f, heading = true)
        while (pn.textSize > 26f && pn.measureText(tab.name) > x1 - nameLeft) pn.textSize -= 1f
        c.drawText(tab.name, nameLeft, top + 30f - pn.ascent() - 4f, pn)
        val n = tab.entries.size
        val pDim = UiKit.inkPaint(24f).apply { color = Theme.parchmentInkDim }
        c.drawText(if (n == 1) "1 spell" else "$n spells", x0 + 160f, top + 30f + pn.textSize + 24f, pDim)

        // the button line: the known spells on no button (the game's gold glow), or all on one
        val st = tab.entries.map { SpellsModel.status(b, it) }
        val bindable = tab.entries.filterIndexed { i, e -> st[i] == SpellsModel.Status.KNOWN && !e.passive && !e.pet && e.kind != SpellsModel.Kind.FLYOUT }
        val off = bindable.count { !SpellsModel.onBar(b, it) }
        val pOn = UiKit.inkPaint(28f)
        val cy = top + 166f
        val textY = cy - 2f - (pOn.ascent() + pOn.descent()) / 2f
        when {
            bindable.isEmpty() -> {}
            off == 0 -> c.drawText("All on a button", x0 + 8f, textY, pOn.apply { color = Theme.parchmentInkDim })
            else -> c.drawText(if (off == 1) "1 not on a button" else "$off not on a button", x0 + 8f, textY,
                pOn.apply { color = Theme.notOnBarInk })
        }

        UiKit.tex("book_divider")?.let { c.drawBitmap(it, null, RectF(x0, top + 204f, x1, top + 215f), PBMP) }
        // what the line holds, the spell page's words: known (how many passive), "See your trainer", the next level
        val known = st.count { it == SpellsModel.Status.KNOWN }
        val passive = tab.entries.filterIndexed { i, e -> st[i] == SpellsModel.Status.KNOWN && e.passive }.size
        val train = st.count { it == SpellsModel.Status.TRAINABLE }
        val later = tab.entries.filterIndexed { i, _ -> st[i] == SpellsModel.Status.LEVEL }
        val lines = listOfNotNull(
            if (known > 0) "$known known" + (if (passive > 0) ", $passive passive" else "") else null,
            if (train > 0) "$train to learn: see your trainer" else null,
            later.takeIf { it.isNotEmpty() }?.let { "${it.size} at a higher level" },
            later.mapNotNull { it.levelLearned }.minOrNull()?.let { "The next at level $it" })
        val pf = UiKit.inkPaint(24f)
        for ((i, s) in lines.withIndex())
            c.drawText(TextUtils.ellipsize(s, android.text.TextPaint(pf), x1 - x0 - 16f, TextUtils.TruncateAt.END).toString(),
                x0 + 8f, top + 228f + i * 36f - pf.ascent(), pf)
    }

    private fun kitState(s: SpellsModel.Status) = when (s) {
        SpellsModel.Status.KNOWN -> UiKit.Spell.KNOWN
        SpellsModel.Status.LEVEL -> UiKit.Spell.LEVEL
        SpellsModel.Status.TRAINABLE -> UiKit.Spell.TRAIN
    }

    private fun drawChosen(c: Canvas, b: SpellsModel.Book, e: SpellsModel.Entry, w: Float, h: Float) {
        val x0 = SPLIT + 40f; val x1 = w - 56f; val top = 188f
        val st = SpellsModel.status(b, e)
        val onBar = SpellsModel.onBar(b, e)
        UiKit.spell(c, Icons.get(e.icon), x0 + 36f, top + 16f, 96f, kitState(st), onBar, e.passive, autoCast = SpellsModel.autoCast(e),
            noMana = NoMana.has(e.spellId))

        // the name: 42 px, smaller down to 30 to stay on one line, then two lines
        val nameLeft = x0 + 158f; val nameW = x1 - nameLeft
        val pn = UiKit.inkPaint(42f, heading = true)
        while (pn.textSize > 30f && pn.measureText(e.name) > nameW) pn.textSize -= 1f
        var nameBottom: Float
        if (pn.measureText(e.name) <= nameW) {
            c.drawText(e.name, nameLeft, top + 30f - pn.ascent() - 4f, pn)
            nameBottom = top + 30f + pn.textSize
        } else {
            val lay = StaticLayout.Builder.obtain(e.name, 0, e.name.length, android.text.TextPaint(pn), nameW.toInt())
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
            c.save(); c.translate(nameLeft, top + 30f - 4f); lay.draw(c); c.restore()
            nameBottom = top + 30f - 4f + lay.height
        }
        // under the name: the rank (a spell not known yet has one too), or "Passive"
        val sub = e.rank ?: if (e.passive) "Passive" else null
        if (sub != null) c.drawText(sub, x0 + 160f, nameBottom + 24f, UiKit.inkPaint(24f).apply { color = Theme.parchmentInkDim })

        // its button (or why there is none)
        // with a form's line under it the button line stands 12 px higher, so that both fit above the rule at top + 204
        val formLine = st == SpellsModel.Status.KNOWN && !e.passive && !e.pet && e.pad.firstOrNull()?.form != null
        val cy = top + (if (formLine) 154f else 166f)
        val pOn = UiKit.inkPaint(28f).apply { color = Theme.parchmentInkDim }
        val textY = cy - 2f - (pOn.ascent() + pOn.descent()) / 2f
        when {
            // a pet's spell: in place of the button line "Casts itself" while its auto-cast is on, nothing otherwise (UI Art, cf8b5db;
            // the game has no words for the state, only "Toggle Auto Cast", so these are the room's own)
            e.pet -> if (SpellsModel.autoCast(e) == 2) c.drawText("Casts itself", x0 + 8f, textY, pOn)
            // a passive stands on no bar; a flyout is not bound (not in the first build)
            e.passive || e.kind == SpellsModel.Kind.FLYOUT -> {}
            st != SpellsModel.Status.KNOWN -> c.drawText(SpellsModel.sub(b, e) ?: "", x0 + 8f, textY, pOn)
            e.pad.isNotEmpty() -> {
                c.drawText("On", x0 + 8f, textY, pOn)
                val combo = SpellsModel.combo(e.pad.first(), style())
                var x = x0 + 66f
                val size = 56f
                val trigger = when (combo.triggers.size) {
                    2 -> { UiKit.pad(c, combo.triggers[0], x + size / 2f, cy, size); x += 62f; combo.triggers[1] }
                    1 -> combo.triggers[0]
                    else -> null
                }
                val end = UiKit.padCombo(c, trigger, combo.button, x, cy, size, Theme.parchmentInk, shadow = false)
                if (e.pad.size > 1) {
                    val pp = UiKit.numberPaint(26f, Theme.parchmentInkDim, shadow = false)
                    c.drawText("+${e.pad.size - 1}", end + 10f, cy - (pp.ascent() + pp.descent()) / 2f, pp)
                }
                // a form's bar: under the button line, 22 px, dim, "in Cat Form" (UI Art, 5445458)
                e.pad.first().form?.let { f ->
                    val pf = UiKit.inkPaint(22f).apply { color = Theme.parchmentInkDim }
                    c.drawText("in $f", x0 + 66f, top + 199f, pf)
                }
            }
            e.bars.isEmpty() -> c.drawText("Not on a button", x0 + 8f, textY, pOn.apply { color = Theme.notOnBarInk })
            else -> {}   // on a keyboard-only slot: on a bar, no controller button to show
        }

        UiKit.tex("book_divider")?.let { c.drawBitmap(it, null, RectF(x0, top + 204f, x1, top + 215f), PBMP) }
        val facts = SpellsModel.facts(e)
        facts?.let {
            val pf = UiKit.inkPaint(23f).apply { color = Theme.parchmentInkDim }
            c.drawText(it, x0 + 8f, top + 228f - pf.ascent(), pf)
        }
        // reagents and tools, as the game's tooltip writes them: one line each under the facts, dim ink, red ink when missing
        var needsH = 0f
        for (n in listOfNotNull(e.reagents, e.tools)) {
            val pn = UiKit.inkPaint(23f).apply { color = if (n.missing) Color.rgb(150, 30, 20) else Theme.parchmentInkDim }
            val y = top + (if (facts != null) 228f + 32f else 228f) + needsH
            c.drawText(TextUtils.ellipsize(n.text, android.text.TextPaint(pn), x1 - x0 - 16f, TextUtils.TruncateAt.END).toString(),
                x0 + 8f, y - pn.ascent(), pn)
            needsH += 32f
        }

        // b23 THE RANK PICKER (the user 2026-10-07 picked "B: a rank picker on each spell" over the game's Show All Spell Ranks): the rank on
        // show between two arrows, the highest by default; a finger steps with the arrows; b25 (the user 2026-10-07: "on the spell book, we need to have x/Y change spell rank and A to bind"): the controller's X a rank lower, Y a rank higher, their glyphs by the arrows (Book.lua b25); Bind binds the
        // rank on show
        val fam = SpellsModel.ranksOf(b, e)
        rankFam = fam
        rankRow.setEmpty()
        rankPad = null
        var rankH = 0f
        if (fam.size > 1) {
            val at = fam.indexOfFirst { idOf(it) == idOf(e) }.coerceAtLeast(0)
            val rowTop = top + (if (facts != null) 228f + 32f else 228f) + needsH + 4f
            rankRow.set(x0, rowTop, x1, rowTop + 80f)
            val rx = (x0 + x1) / 2f; val ry = rankRow.centerY()
            UiKit.arrow(c, "left", rx - 150f, ry, 64f, if (at < fam.size - 1) press.state("rkL") else UiKit.Button.DISABLED)
            UiKit.arrow(c, "right", rx + 150f, ry, 64f, if (at > 0) press.state("rkR") else UiKit.Button.DISABLED)
            val k = ctl?.takeIf { it.on && !fingerSinceCtl && !it.talentsPage && !it.onTabs && it.mode == "book" && it.selected == idOf(e) }
            val pr = UiKit.inkPaint(26f).apply { textAlign = Paint.Align.CENTER }
            val words = "Rank ${SpellsModel.rankNo(e.rank) ?: (fam.size - at)} of ${fam.size}"
            c.drawText(words, rx, ry - 2f - (pr.ascent() + pr.descent()) / 2f, pr)
            // the controller's keys while its cursor stands on this spell: X by the lower arrow, Y by the higher one
            if (k != null) {
                UiKit.pad(c, glyph(5), rx - 96f, ry, 36f)
                UiKit.pad(c, glyph(6), rx + 96f, ry, 36f)
            }
            rankPad = if (k != null) (at < fam.size - 1) to (at > 0) else null
            rankH = 92f
        }

        // Bind: a known active spell only (SPEC 2.4); grey in combat with the reason over it
        val bind = bindRect(w, h)
        val canBind = st == SpellsModel.Status.KNOWN && SpellsModel.bindable(b, e)
        if (canBind) {
            val glyph = glyph(8)                                // b25: A binds (the user 2026-10-07)
            if (b.combat) {
                UiKit.button(c, bind, "Bind", glyph, UiKit.Button.DISABLED)
                val pw = UiKit.inkPaint(24f).apply { color = Color.rgb(150, 30, 20); textAlign = Paint.Align.CENTER }
                c.drawText("Not while in combat", bind.centerX(), bind.top - 22f, pw)
            } else UiKit.button(c, bind, "Bind", glyph, if (bindDown || press.isDown("bind")) UiKit.Button.DOWN else UiKit.Button.UP)
        }
        // the controller's A works Bind right now (the addon's book page, its glow on a spell): its press pushes the button in at once
        bindLive = canBind && !b.combat && ctl?.on == true && ctl?.mode == "book" && ctl?.onTabs == false && ctl?.talentsPage != true

        // the description: from top + 268 to Bind's top - 8 (to the page's foot without Bind), dragged when longer; with no facts line
        // (a passive with neither cost nor range) it moves up by 40 px (UI Art, 52e6c41)
        val text = e.desc ?: return
        val descTop = top + (if (facts == null) 228f else 268f) + needsH + rankH
        val descBottom = (if (canBind) bind.top - 8f else h - 40f) - (if (canBind && b.combat) 40f else 0f)
        val pd = android.text.TextPaint(UiKit.inkPaint(24f))
        val lay = StaticLayout.Builder.obtain(text, 0, text.length, pd, 388)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f, 1f).build()
        descMax = maxOf(0f, lay.height - (descBottom - descTop))
        descScroll = descScroll.coerceIn(0f, descMax)
        descBox.set(x0, descTop, x1, descBottom)
        c.save()
        c.clipRect(descBox)
        c.translate(x0 + 8f, descTop - descScroll)
        lay.draw(c)
        c.restore()
    }


    // ---------------------------------------------------------------- touch (SPEC 2 / 3)

    private val descBox = RectF()
    private var downX = 0f; private var downY = 0f; private var lastY = 0f
    private var dragging = DRAG_NONE
    private var bindDown = false
    // b23 the rank picker's row and family (set while drawing), and the arrow a finger holds ("rkL" lower / "rkR" higher)
    private val rankRow = RectF()
    private var rankFam: List<SpellsModel.Entry> = emptyList()
    private var rankDown: String? = null
    /** PRESS FEEDBACK (the user 2026-10-07, Interact's recipe): a tap keeps Bind DOWN 160 ms; the controller's X pushes it in at once */
    private var bindLive = false
    // b25: A = Bind, X / Y = the rank's arrows (a lower / higher rank there is), each pushed in at the press
    private var rankPad: Pair<Boolean, Boolean>? = null
    private val press = PressFeedback(this).also {
        it.padTarget = { key ->
            when (key) {
                "PAD1" -> if (bindLive) "bind" else null
                "PAD3" -> if (rankPad?.first == true) "rkL" else null
                "PAD4" -> if (rankPad?.second == true) "rkR" else null
                else -> null
            }
        }
    }
    private var doorDown = false
    private var padDown = false                 // a touch that began on the Controller tab's pages

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val w = width.toFloat(); val h = height.toFloat()
        if (ev.actionMasked == MotionEvent.ACTION_UP) fingerSinceCtl = true
        // the Controller tab's pages are its own; the tab row stays the book's
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) padDown = tabOpen && ev.y > 173f
        if (padDown) {
            padTab.onTouch(ev, w, h, book)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) padDown = false
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastY = ev.y; dragging = DRAG_NONE
                bindDown = bindRect(w, h).contains(ev.x, ev.y)
                rankDown = if (rankFam.size > 1 && rankRow.contains(ev.x, ev.y)) (if (ev.x < rankRow.centerX()) "rkL" else "rkR") else null
                press.finger(if (bindDown) "bind" else rankDown)
                doorDown = onDoor != null && RectF(w - 136f, 62f, w - 12f, 173f).contains(ev.x, ev.y)
                if (bindDown || doorDown) invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX; val dy = ev.y - downY
                if (dragging == DRAG_NONE && (abs(dx) > SLOP || abs(dy) > SLOP)) {
                    dragging = when {
                        descBox.contains(downX, downY) && abs(dy) > abs(dx) && descMax > 0f -> DRAG_DESC
                        downX < SPLIT && downY > 190f && abs(dx) > abs(dy) -> DRAG_SWIPE
                        else -> DRAG_OTHER
                    }
                    if (bindDown || doorDown) { bindDown = false; doorDown = false; invalidate() }
                    rankDown = null
                    press.finger(null)
                }
                if (dragging == DRAG_DESC) {
                    descScroll = (descScroll - (ev.y - lastY)).coerceIn(0f, descMax)
                    lastY = ev.y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasBind = bindDown; val wasDoor = doorDown; val wasRank = rankDown
                press.lift(dragging == DRAG_NONE && ((wasBind && bindRect(w, h).contains(ev.x, ev.y)) ||
                    (wasRank != null && rankRow.contains(ev.x, ev.y))))
                bindDown = false; doorDown = false; rankDown = null
                when (dragging) {
                    DRAG_SWIPE -> turn(if (ev.x < downX) +1 else -1)
                    DRAG_NONE -> tap(ev.x, ev.y, w, h, wasBind, wasDoor, wasRank)
                    else -> {}
                }
                dragging = DRAG_NONE
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                bindDown = false; doorDown = false; rankDown = null; dragging = DRAG_NONE; press.finger(null); invalidate(); return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun tap(x: Float, y: Float, w: Float, h: Float, wasBind: Boolean, wasDoor: Boolean, wasRank: String? = null) {
        // the door needs no book: before the first `spells` document it must still lead to Talents
        if (wasDoor) { onDoor?.invoke(); return }
        // b23 the rank picker's arrows: a rank lower (left) / higher (right), no wrap; the room's own choice (no game action)
        if (wasRank != null && rankFam.size > 1 && rankRow.contains(x, y)) {
            val at = rankFam.indexOfFirst { idOf(it) == chosenId }
            if (at >= 0) rankFam.getOrNull(if (wasRank == "rkL") at + 1 else at - 1)?.let { chosenId = idOf(it); descScroll = 0f }
            return
        }
        val b = book ?: return
        val tabs = SpellsModel.tabs(b)
        // a tab
        for (i in tabs.indices) {
            if (i >= 8) break
            val r = tabRect(i)
            if (x >= r.left - 4f && x <= r.right + 4f && y >= 62f && y <= 173f) {
                if (tabOpen) closeTab(true)
                if (tabs[i].key != tabKey) {
                    tabKey = tabs[i].key; page = 1; descScroll = 0f
                    val onPage = pageOf(tabs[i])
                    chosenId = onPage.getOrNull(SpellsModel.firstKnown(b, onPage))?.let { idOf(it) }
                }
                return
            }
        }
        val tab = tabs.firstOrNull { it.key == tabKey } ?: return
        // the arrows (only with more than one page)
        val pages = pagesOf(tab)
        val armTop = Y0 + 5f * rh(h)
        if (pages > 1 && y >= armTop) {
            if (x >= 330f - 150f - 56f && x <= 330f - 150f + 56f) { turn(-1); return }
            if (x >= 330f + 150f - 56f && x <= 330f + 150f + 56f) { turn(+1); return }
        }
        // an entry
        for ((i, e) in pageOf(tab).withIndex()) {
            if (entryRect(i, h).contains(x, y)) {
                if (chosenId != idOf(e)) { chosenId = idOf(e); descScroll = 0f }
                return
            }
        }
        // Bind
        if (wasBind && bindRect(w, h).contains(x, y)) {
            val e = tab.entries.firstOrNull { idOf(it) == chosenId } ?: return
            if (SpellsModel.status(b, e) == SpellsModel.Status.KNOWN && SpellsModel.bindable(b, e) && !b.combat) onBind(e)
        }
    }

    private fun turn(d: Int) {
        val b = book ?: return
        val tab = SpellsModel.tabs(b).firstOrNull { it.key == tabKey } ?: return
        val np = (page + d).coerceIn(1, pagesOf(tab))
        if (np == page) return
        page = np; descScroll = 0f
        val onPage = pageOf(tab)
        chosenId = onPage.getOrNull(SpellsModel.firstKnown(b, onPage))?.let { idOf(it) }
    }

    companion object {
        private const val TAG = "SpellbookView"
        /** the spells row's `close` cell (the addon claims open 0 / close 1 first, AGENT_LOG "Pad owner -> spells") */
        private const val SPELLS_CLOSE_CELL = 1
        private const val SPELLS_OPEN_CELL = 0
        private const val BUSY_MS = 5000L
        const val PER_PAGE = 10
        private const val SPLIT = 740f
        private const val Y0 = 250f
        private const val CW = 334f
        private const val SLOP = 18f
        private const val DRAG_NONE = 0; private const val DRAG_DESC = 1; private const val DRAG_SWIPE = 2; private const val DRAG_OTHER = 3
        private val PBMP = Paint(Paint.FILTER_BITMAP_FLAG)
    }
}
