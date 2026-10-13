package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.jrees.wowcompanion.CalendarModel.Event
import com.jrees.wowcompanion.CalendarModel.Line
import com.jrees.wowcompanion.CalendarModel.State
import com.jrees.wowcompanion.CalendarModel.Tone
import org.json.JSONObject

/**
 * THE CALENDAR ROOM (calendar session), drawn to UI Art's SPEC (`wow-companion-design/calendar/SPEC.md`) with kit calls only,
 * after `tools/kitpreview/CalendarSample.kt`. The user's picks (2026-09-29): the month and the chosen day side by side; an
 * invitation's three answers one under another; the game's own mark on the bar (MainActivity); the arrows stop at the four
 * months the feed has ready. "Look and answer": nothing is created, edited or typed.
 *
 * The data is [CalendarModel] (the addon's `calendar` document). Every GAME action is ONE tap on the pad row `cal`: an answer
 * (Accept, Tentative, Decline, Sign Up, Remove: the game's own day-menu calls, offered where the game offers them) and a filter
 * tick (the game's own setting). Choosing a day, turning among the four months, opening a page and scrolling are the app's alone.
 * B that leaves the room goes HOME (user). The game's own calendar window is never opened, closed or hidden from here.
 */
class CalendarView(context: Context) : View(context), BLeadsHome {

    /** B that leaves the room: the activity shows Home */
    var onLeave: () -> Unit = {}

    var state: State? = null
        private set
    var lastAck: CalendarModel.Ack? = null
        private set

    // ---------------------------------------------------------------- the app's own choices
    private var monthY = 0; private var monthM = 0         // the month on show, by its name (the four can shift)
    private var chosenDay: Int? = null                      // the chosen day of the month on show
    private var pageRef: String? = null                     // the open page, by the event's identity
    private var filtersOpen = false
    private var moreOpen = false
    private var confirmRemove = false
    private var listScroll = 0f; private var listContent = 0f; private var listBox = RectF()
    private var noteScroll = 0f; private var noteContent = 0f; private var noteBox = RectF()

    // ---------------------------------------------------------------- the controller's glow
    private sealed class Fk {
        data class Arrow(val next: Boolean) : Fk()
        data class Day(val index: Int) : Fk()               // a place of the grid, 0..41
        object Filters : Fk()
        data class Entry(val i: Int) : Fk()
        data class Answer(val op: String) : Fk()
        object More : Fk()
        object Remove : Fk()
        object Yes : Fk()
        object No : Fk()
        data class Filter(val i: Int) : Fk()
    }

    private class Nav(val key: Fk, val r: RectF, val act: () -> Unit)

    private val nav = ArrayList<Nav>()          // every target (the D-pad reaches lines out of view)
    private val hits = ArrayList<Nav>()         // what a finger can touch now (clipped to what shows)
    private var focus: Fk? = null
    /** a finger is pressing (not A): the glow stays on what was touched instead of going on into the day's list */
    private var touching = false
    private var lastPadN = -1

    // ---------------------------------------------------------------- documents; showing and leaving

    private var shown = false
    private var holdSent = false
    /** (c9) what the addon was last told the next B does, "home" / "back" (a take starts at "home"); null = nothing told */
    private var bSaid: String? = null
    private var wasHeld = false
    /** the room is being left with B: give the controller back with "B" (the `fb` key), as the capture's contract asks */
    private var leftByB = false
    // (c10) the hold agrees with the screen: a lost `h` or `f` goes again (CalendarModel.ctlFix), RETRY_MS after the last one
    private var holdTapAt = -1_000_000L
    private var freeTapAt = -1_000_000L
    private var retries = 0
    private val recheck = Runnable { reconcile() }

    /**
     * a `calendar` or `calack` document (MainActivity routes them here). [live] = the document is CURRENT (GameInput.note),
     * not replayed from the backlog at start: the room's automatic taps (`hold`, where the next B leads) wait for a live one
     */
    fun onDoc(msg: JSONObject, live: Boolean = true) {
        when (msg.optString("type")) {
            "calendar" -> CalendarModel.parse(msg)?.let { s ->
                val first = state == null
                val merged = CalendarModel.merge(state, s)
                state = merged
                val ms = merged.months
                if (ms != null && ms.isNotEmpty()) {
                    // the month on show stays where it was; when it left the four, back to today's
                    if (first || ms.none { it.y == monthY && it.m == monthM }) {
                        val home = ms[CalendarModel.homeMonth(merged)]
                        monthY = home.y; monthM = home.m
                        chosenDay = merged.now?.takeIf { it.y == monthY && it.m == monthM }?.d
                    }
                }
                if (pageRef != null && findPage() == null) { pageRef = null; moreOpen = false; confirmRemove = false }
                if (live && shown && !holdSent) sendHold("on a document")
                // a (re)take starts at "home" in the capture: say it anew from there
                if (merged.held && !wasHeld) bSaid = "home"
                wasHeld = merged.held
            }
            "calack" -> lastAck = CalendarModel.parseAck(msg)
        }
        if (live) { tellB(); reconcile() }
        invalidate()
    }

    /**
     * the room came on the screen: it takes the controller (the addon's `h`, no game action). The glow is ON from the start,
     * on the day already lit as chosen (today, or the day chosen last); with none, onDraw puts it on the month's first day
     */
    fun onShown() {
        shown = true; leftByB = false; bSaid = "home"; retries = 0
        focus = dayKey(chosenDay)
        holdSent = false
        sendHold("shown")
        reconcileLater()
        invalidate()
    }

    /** the room was left: the controller goes back (the addon's `f`, or `fb` when B left it: the app then shows Home) */
    fun onHidden() {
        if (shown && holdSent) {
            val k = state?.keys
            val byB = leftByB && k?.freeB != null
            val sent = tap(if (byB) k?.freeB else k?.free)
            if (sent) freeTapAt = SystemClock.uptimeMillis()
            Log.i(TAG, "hidden: ${if (byB) "fb" else "f"} ${if (sent) "sent" else "not sent"}")
        } else Log.i(TAG, "hidden: nothing to free (shown $shown, hold sent $holdSent)")
        retries = 0
        reconcileLater()
        shown = false; holdSent = false; leftByB = false; bSaid = null
        pageRef = null; filtersOpen = false; moreOpen = false; confirmRemove = false; focus = null
    }

    /**
     * Home's self-heal (MainActivity routes it): the addon still holds the controller for this room while the room is NOT on
     * screen, because the `f` sent on leaving was lost (a tap can miss its cell; Thor 2026-09-30 22:49:14, the room held through
     * the user's moves to three other rooms). ONE `f` (no game action), whatever shown / holdSent say; Home calls it at most
     * once per 2 s. True = the tap was sent.
     */
    fun freeAgain(): Boolean {
        val sent = tap(state?.keys?.free)
        if (sent) freeTapAt = SystemClock.uptimeMillis()
        Log.i(TAG, "free again: ${if (sent) "sent" else "not sent"}")
        return sent
    }

    /** the room's `h` (no game action) */
    private fun sendHold(why: String): Boolean {
        val sent = tap(state?.keys?.hold)
        if (sent) { holdSent = true; holdTapAt = SystemClock.uptimeMillis() }
        Log.i(TAG, "hold ($why): ${if (sent) "sent" else "not sent"}")
        return sent
    }

    /**
     * (c10) the addon's own word (`ctl.want` in its last document) against the screen: while the room shows it must want the
     * controller, while it does not it must neither want nor hold it (CalendarModel.ctlFix). A tap just sent gets RETRY_MS for
     * its document to come back; then the lost one goes again, at most RETRY_MAX times until the two agree. Only from a LIVE
     * document, a show / hide, or the timer they set (never from the backlog LogTail replays at start).
     */
    private fun reconcile() {
        removeCallbacks(recheck)
        val fix = CalendarModel.ctlFix(state, shown)
        if (fix == null) { retries = 0; return }
        val since = SystemClock.uptimeMillis() - (if (fix == "h") holdTapAt else freeTapAt)
        if (since < RETRY_MS) { postDelayed(recheck, RETRY_MS - since + 50); return }
        if (retries >= RETRY_MAX) return
        retries++
        if (retries == RETRY_MAX) Log.w(TAG, "the addon still disagrees ($fix): last try")
        val sent = if (fix == "h") sendHold("again, try $retries") else freeAgain()
        if (sent) postDelayed(recheck, RETRY_MS + 50)
    }

    private fun reconcileLater() {
        removeCallbacks(recheck)
        postDelayed(recheck, RETRY_MS + 50)
    }

    /** (c9) where the NEXT B leads: back while something is open or the glow is in the day's list, else home (out of the room) */
    private fun bMeaning(): String =
        if (confirmRemove || moreOpen || filtersOpen || pageRef != null || focus is Fk.Entry || focus == Fk.Filters) "back" else "home"

    /** RC3: the app's own B read goes Home on the press when [bMeaning] says "home" */
    override fun bLeadsHome(): Boolean = bMeaning() == "home"

    /** tell the addon on CHANGE only (one tap on its fixed key, no game action), while this room holds the controller */
    private fun tellB() {
        val s = state ?: return
        if (!shown || !s.held) return
        val m = bMeaning()
        if (m == bSaid) return
        val col = if (m == "back") s.keys.bBack else s.keys.bHome
        if (col != null && tap(col)) bSaid = m
    }

    private fun tap(col: Int?): Boolean {
        val s = state ?: return false
        if (col == null || !s.padRow) return false
        return TapBridge.tap(context, s.pad, CalendarModel.PAD_ROW, col)
    }

    private fun month(): CalendarModel.Month? = state?.months?.firstOrNull { it.y == monthY && it.m == monthM }
    private fun monthIndex(): Int = state?.months?.indexOfFirst { it.y == monthY && it.m == monthM } ?: -1

    private fun turn(by: Int) {
        val ms = state?.months ?: return
        val i = monthIndex() + by
        if (i !in ms.indices) return
        monthY = ms[i].y; monthM = ms[i].m
        chosenDay = state?.now?.takeIf { it.y == monthY && it.m == monthM }?.d
        listScroll = 0f; pageRef = null; moreOpen = false
        invalidate()
    }

    private fun choose(day: Int) {
        chosenDay = day; listScroll = 0f; pageRef = null; moreOpen = false
        invalidate()
    }

    /** the line of the day's list the open page came from */
    private var pageEntry = 0

    private fun refOf(e: Event): String = e.key ?: "${e.ct}:${e.id}:${e.start?.dayNum}:${e.title}"

    private fun findPage(): Event? {
        val r = pageRef ?: return null
        return state?.months?.flatMap { it.events }?.firstOrNull { refOf(it) == r }
    }

    private fun openPage(e: Event, from: Int) {
        pageRef = refOf(e); pageEntry = from; noteScroll = 0f; moreOpen = false; confirmRemove = false
        focus = CalendarModel.answers(state!!, e).firstOrNull { it.col != null }?.let { Fk.Answer(it.op) }   // none: onDraw settles it
        invalidate()
    }

    private fun closePage() {
        pageRef = null; moreOpen = false; confirmRemove = false
        focus = Fk.Entry(pageEntry)             // B closed it: the glow goes back to the event the page came from
        invalidate()
    }

    // ---------------------------------------------------------------- drawing (SPEC 2 .. 7)

    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        nav.clear(); hits.clear()
        drawRoom(c)
        // the glow is ALWAYS on something while the room shows, never brought out by a first press (the user via Home,
        // 2026-09-30: "it should already be there"): when what it was on is gone, it goes to the first target
        if (shown && nav.isNotEmpty() && nav.none { it.key == focus }) {
            val t = firstTarget()
            if (nav.any { it.key == t }) { focus = t; post { tellB() }; invalidate() }
        }
    }

    private fun drawRoom(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(Theme.ink)
        UiKit.header(c, w, "Calendar")
        val gx = 7 * DAY_W
        UiKit.pane(c, RectF(gx + 12f, 62f, w, h), right = true)
        UiKit.paneDivider(c, gx + 12f, 62f, h)
        val st = state
        val m = month()
        if (st == null || !st.api || (!st.off && m == null)) {
            say(c, 60f, 330f, gx - 140f, "Waiting for the game", 27f)
            return
        }
        if (st.off) {
            say(c, 60f, 330f, gx - 140f, "The calendar is switched off on this realm.", 27f)
            return
        }
        drawMonth(c, st, m!!, gx)
        val detail = RectF(gx + 36f, 76f, w - 22f, h - 16f)
        val page = findPage()
        when {
            st.secret -> say(c, gx + 40f, 120f, w - gx - 80f, "The game hides the calendar during a fight in an instance. This is " +
                "the month as it was last seen. Answers wait until the fight is over.", 25f)
            filtersOpen -> drawFilters(c, st, gx, w, h)
            page != null -> drawPage(c, st, page, detail)
            else -> {
                val fb = RectF(gx + 30f, 70f, w - 14f, 154f)
                UiKit.dropDown(c, fb, st.word("CALENDAR_FILTERS"), enabled = st.padRow)
                add(Fk.Filters, RectF(fb.left, fb.centerY() - 55.5f, fb.right, fb.centerY() + 55.5f)) { filtersOpen = true; focus = Fk.Filter(0); invalidate() }
                if (focus == Fk.Filters) UiKit.focus(c, fb)
                drawDay(c, st, m, RectF(gx + 36f, 176f, w - 22f, h - 16f))
            }
        }
        if (confirmRemove && page != null) drawConfirm(c, st, page, w, h)
    }

    /** the month's name between its two arrows, the weekday bar, six rows of seven days (SPEC 3) */
    private fun drawMonth(c: Canvas, st: State, m: CalendarModel.Month, gx: Float) {
        val ms = st.months ?: return
        val i = monthIndex()
        val prevOn = i > 0; val nextOn = i in 0 until ms.size - 1
        UiKit.pageArrow(c, false, 56f, 90f, 64f, prevOn)
        UiKit.pageArrow(c, true, gx - 56f, 90f, 64f, nextOn)
        if (prevOn) add(Fk.Arrow(false), RectF(0.5f, 34.5f, 111.5f, 145.5f)) { turn(-1) }
        if (nextOn) add(Fk.Arrow(true), RectF(gx - 111.5f, 34.5f, gx - 0.5f, 145.5f)) { turn(1) }
        if (focus == Fk.Arrow(false)) UiKit.focus(c, RectF(24f, 58f, 88f, 122f))
        if (focus == Fk.Arrow(true)) UiKit.focus(c, RectF(gx - 88f, 58f, gx - 24f, 122f))
        val pt = UiKit.titlePaint(40f).apply { textAlign = Paint.Align.CENTER }
        c.drawText("${st.monthName(m.m)} ${m.y}", gx / 2f, 88f - (pt.ascent() + pt.descent()) / 2f, pt)
        val bar = CalendarModel.weekdayBar(st)
        for (k in 0 until 7) UiKit.calendarWeekday(c, RectF(k * DAY_W, 118f, (k + 1) * DAY_W, 154f), bar[k])

        val py = if (m.m == 1) m.y - 1 else m.y; val pm = if (m.m == 1) 12 else m.m - 1
        val grid = CalendarModel.grid(m, CalendarModel.daysIn(py, pm), st.first)
        val now = st.now
        for ((idx, cell) in grid.withIndex()) {
            val r = RectF(cell.col * DAY_W, 154f + cell.row * DAY_H, (cell.col + 1) * DAY_W, 154f + (cell.row + 1) * DAY_H)
            val glowed = focus == Fk.Day(idx)
            if (cell.other != 0) {
                UiKit.calendarDay(c, r, cell.day, ground = cell.row * 3 + cell.col * 5, other = true)
                // a dark day of a neighbouring month: a touch turns to that month and chooses it, when it is one of the four
                val j = i + cell.other
                if (j in ms.indices) add(Fk.Day(idx), r) { turn(cell.other); choose(cell.day); focus = dayKey(cell.day) }
                if (glowed) UiKit.focus(c, r)
                continue
            }
            val banner = CalendarModel.bannerOn(m, cell.day)
            val marks = CalendarModel.entriesOn(m, cell.day).take(4).let { es ->
                CalendarModel.marksOn(m, cell.day).mapIndexed { k, id ->
                    val e = es.getOrNull(k)?.first
                    id?.let { UiKit.icon(it) } ?: if (e != null && e.playerMade && e !== banner?.first) kindArt(e) else null
                }
            }
            UiKit.calendarDay(c, r, cell.day, ground = cell.row * 3 + cell.col * 5,
                banner = banner?.let { UiKit.icon(it.second) }, marks = marks,
                invitation = CalendarModel.invitationOn(m, cell.day),
                today = now != null && now.y == m.y && now.m == m.m && now.d == cell.day,
                chosen = chosenDay == cell.day || glowed)
            add(Fk.Day(idx), r) {
                choose(cell.day)
                // A sends the glow on to the day's list when it has something in it; a finger leaves it on the day
                if (!touching && (CalendarModel.dayNote(st, m, cell.day).let { it.first.isNotEmpty() || it.second.isNotEmpty() }))
                    focus = Fk.Entry(0)
            }
        }
    }

    /** the picture of a player's event without one of its own: the game's meeting / PvP / other pictures */
    private fun kindArt(e: Event): Bitmap? = UiKit.tex(when (e.eventType) { 2 -> "cal_event_pvp"; 3 -> "cal_event_meeting"; else -> "cal_event_other" })

    private fun lineArt(l: Line): Bitmap? =
        l.picture?.let { UiKit.icon(it) } ?: if (l.event.playerMade) kindArt(l.event) else null

    private fun colourOf(tone: Tone, e: Event): Int = when (tone) {
        Tone.GOLD -> Theme.gold
        Tone.MODERATOR -> Theme.moderator
        Tone.STATUS -> Theme.invite(e.inv ?: 0)
    }

    /** the chosen day: the game's day note as a list (SPEC 4) */
    private fun drawDay(c: Canvas, st: State, m: CalendarModel.Month, r: RectF) {
        val day = chosenDay
        if (day == null) { nothing(c, r, "Choose a day"); return }
        val pd = UiKit.bodyPaint(26f, Color.WHITE)
        c.drawText(TextUtils.ellipsize(CalendarModel.fullDate(st, m.y, m.m, day), TextPaint(pd), r.width() - 16f, TextUtils.TruncateAt.END).toString(),
            r.left + 8f, r.top + 26f - (pd.ascent() + pd.descent()) / 2f, pd)
        UiKit.paneRule(c, r.left, r.top + 52f, r.right)
        val (entries, ongoing) = CalendarModel.dayNote(st, m, day)
        if (entries.isEmpty() && ongoing.isEmpty()) { nothing(c, r, "Nothing on this day"); return }
        listBox = RectF(r.left - 10f, r.top + 76f, r.right + 10f, r.bottom)
        listContent = entries.size * 104f + (if (ongoing.isNotEmpty()) 52f + ongoing.size * 104f else 0f) + 40f
        listScroll = listScroll.coerceIn(0f, maxOf(0f, listContent - listBox.height()))
        c.save(); c.clipRect(listBox)
        var y = listBox.top - listScroll
        var i = 0
        for (l in entries) {
            val box = RectF(listBox.left, y, listBox.right, y + 104f)
            UiKit.calendarEntry(c, box, lineArt(l), l.title, colourOf(l.tone, l.event), l.under, chosen = focus == Fk.Entry(i))
            val e = l.event; val at = i
            addClipped(Fk.Entry(i), box, listBox) { openPage(e, at) }
            y += 104f; i++
        }
        if (ongoing.isNotEmpty()) {
            val po = UiKit.bodyPaint(25f, Color.WHITE)
            c.drawText(st.word("CALENDAR_TOOLTIP_ONGOING"), r.left + 8f, y + 26f - (po.ascent() + po.descent()) / 2f, po)
            y += 52f
            for (l in ongoing) {
                val box = RectF(listBox.left, y, listBox.right, y + 104f)
                UiKit.calendarEntry(c, box, lineArt(l), l.title, Theme.gold, l.under, chosen = focus == Fk.Entry(i))
                val e = l.event; val at = i
                addClipped(Fk.Entry(i), box, listBox) { openPage(e, at) }
                y += 104f; i++
            }
        }
        c.restore()
        if (listContent > listBox.height()) UiKit.scrollBar(c, RectF(r.right + 2f, listBox.top, r.right + 10f, listBox.bottom),
            listScroll / listContent, (listScroll + listBox.height()) / listContent)
    }

    /** an event's page in the pane's place (SPEC 5) */
    private fun drawPage(c: Canvas, st: State, e: Event, r: RectF) {
        when {
            e.ct == "HOLIDAY" -> drawHoliday(c, st, e, r)
            e.ct == "RAID_LOCKOUT" || e.ct == "RAID_RESET" -> drawRaid(c, st, e, r)
            else -> drawEvent(c, st, e, r)
        }
    }

    private fun drawEvent(c: Canvas, st: State, e: Event, r: RectF) {
        val page = e.key?.let { st.pages[it] }
        val pic = page?.dungeonIcon?.let { UiKit.icon(it) } ?: e.icon?.let { UiKit.icon(it) } ?: kindArt(e)
        pic?.let { c.drawBitmap(it, null, RectF(r.left + 4f, r.top + 8f, r.left + 88f, r.top + 92f), pBmp) }
        val removeCol = CalendarModel.removeCol(st, e)
        val titleRight = if (removeCol != null) r.right - 84f else r.right - 4f
        val pt = TextPaint(UiKit.bodyPaint(32f, colourOf(CalendarModel.tone(e), e)))
        val title = CalendarModel.pageTitle(st, e)
        while (pt.measureText(title) > titleRight - r.left - 100f && pt.textSize > 22f) pt.textSize -= 1f
        c.drawText(TextUtils.ellipsize(title, pt, titleRight - r.left - 100f, TextUtils.TruncateAt.END).toString(),
            r.left + 100f, r.top + 30f - (pt.ascent() + pt.descent()) / 2f, pt)
        val pk = UiKit.bodyPaint(23f, Theme.dim)
        c.drawText(TextUtils.ellipsize(CalendarModel.kind(st, e, page), TextPaint(pk), titleRight - r.left - 100f, TextUtils.TruncateAt.END).toString(),
            r.left + 100f, r.top + 68f - (pk.ascent() + pk.descent()) / 2f, pk)
        if (removeCol != null) {
            UiKit.trackerButton(c, "filter", r.right - 38f, r.top + 48f, 76f, down = moreOpen)
            val mb = RectF(r.right - 93.5f, r.top - 7.5f, r.right + 17.5f, r.top + 103.5f)
            add(Fk.More, mb) { moreOpen = !moreOpen; focus = if (moreOpen) Fk.Remove else Fk.More; invalidate() }
            if (focus == Fk.More) UiKit.focus(c, RectF(r.right - 76f, r.top + 10f, r.right, r.top + 86f))
        }
        UiKit.paneRule(c, r.left, r.top + 104f, r.right)
        var y = r.top + 140f
        val pw = TextPaint(UiKit.bodyPaint(25f, Color.WHITE))
        val start = e.start
        if (start != null) {
            val date = CalendarModel.fullDate(st, start.y, start.m, start.d)
            while (pw.measureText(date) > r.width() - 16f && pw.textSize > 19f) pw.textSize -= 1f
            c.drawText(date, r.left + 8f, y - (pw.ascent() + pw.descent()) / 2f, pw); y += 34f
            val pt2 = UiKit.bodyPaint(25f, Color.WHITE)
            c.drawText(CalendarModel.clock(st, start.h, start.mi), r.left + 8f, y - (pt2.ascent() + pt2.descent()) / 2f, pt2)
        }
        y += 46f
        val pg = UiKit.bodyPaint(23f, Theme.gold)
        CalendarModel.goldLine(st, e)?.let {
            c.drawText(TextUtils.ellipsize(it, TextPaint(pg), r.width() - 16f, TextUtils.TruncateAt.END).toString(),
                r.left + 8f, y - (pg.ascent() + pg.descent()) / 2f, pg)
        }
        y += 30f
        val answers = CalendarModel.answers(st, e)
        // FIXED AT THE FOOT (UI Art, SPEC 5 changed 2026-09-29): "Set your status:" 30 px over the first answer, or at
        // r.bottom - 22 without answers; between the gold line and it, ONE column that scrolls (the words, then who is invited)
        val status = if (answers.isEmpty()) r.bottom - 22f else r.bottom - 111f - (answers.size - 1) * 119f - 30f
        drawColumn(c, st, e, page, RectF(r.left, y, r.right, status - 26f))
        if (e.inv != null && e.ct != "GUILD_ANNOUNCEMENT") {
            c.drawText(st.word("CALENDAR_VIEW_EVENT_SETSTATUS"), r.left + 8f, status - (pg.ascent() + pg.descent()) / 2f, pg)
            // my status in its own colour (SPEC 5: Theme.invite(inv)); the light blue of an event I made is for its NAME only
            val ps = UiKit.bodyPaint(23f, Theme.invite(e.inv)).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(st.statusName(e.inv), r.right - 8f, status - (ps.ascent() + ps.descent()) / 2f, ps)
        }
        // the answers, ONE UNDER ANOTHER, the last at the pane's foot (user pick); the game disables my status already
        for ((k, a) in answers.withIndex()) {
            val top = r.bottom - 111f - (answers.size - 1 - k) * 119f
            val box = RectF(r.left, top, r.right, top + 111f)
            val same = (a.op == "acc" && e.inv == 1) || (a.op == "ten" && e.inv == 8) || (a.op == "dec" && e.inv == 2)
            val enabled = a.col != null && !same
            UiKit.button(c, box, a.label, if (enabled) UiKit.Button.UP else UiKit.Button.DISABLED, 30f)
            if (enabled) {
                val col = a.col
                add(Fk.Answer(a.op), box) { tap(col) }
            }
            if (focus == Fk.Answer(a.op)) UiKit.focus(c, box)
        }
        if (moreOpen && removeCol != null) {
            val box = RectF(r.right - 330f, r.top + 96f, r.right, r.top + 96f + 128f)
            val inner = UiKit.menu(c, box, solid = true)
            val row = RectF(inner.left, inner.top, inner.right, inner.top + 100f)
            UiKit.menuRow(c, row, st.word("CALENDAR_VIEW_EVENT_REMOVE"), focus == Fk.Remove, Theme.unusable)
            add(Fk.Remove, row) { moreOpen = false; confirmRemove = true; focus = Fk.No; invalidate() }
        }
    }

    /**
     * ONE column that scrolls, clipped to [band] (UI Art, SPEC 5 changed 2026-09-29): the words a player wrote in their
     * box, AS HIGH AS THE WORDS NEED (no box without words), then 30 px under it the invite list (c3), 40 px a player.
     */
    private fun drawColumn(c: Canvas, st: State, e: Event, page: CalendarModel.Page?, band: RectF) {
        noteBox = RectF(band)
        if (band.height() <= 0f) return
        val desc = page?.desc ?: ""
        val sl = StaticLayout.Builder.obtain(desc, 0, desc.length, TextPaint(UiKit.bodyPaint(23f, Theme.text)), maxOf(1, (band.width() - 40f).toInt()))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f, 1f).build()
        val boxH = if (desc.isNotEmpty()) sl.height + 36f else 0f
        val listTop = if (desc.isNotEmpty()) boxH + 30f else 0f
        val invitees = page?.invites ?: emptyList()
        val retrieving = e.ct != "GUILD_ANNOUNCEMENT" && page == null
        // the first player's line is centred 20 px into its 40
        noteContent = listTop + (if (retrieving) 40f else invitees.size * 40f)
        noteScroll = noteScroll.coerceIn(0f, maxOf(0f, noteContent - band.height()))
        c.save(); c.clipRect(band.left - 4f, band.top, band.right + 4f, band.bottom)
        val top = band.top - noteScroll
        if (desc.isNotEmpty()) {
            val inner = UiKit.noteBox(c, RectF(band.left, top, band.right, top + boxH))
            c.save(); c.translate(inner.left, inner.top); sl.draw(c); c.restore()
        }
        var y = top + listTop + 20f
        if (retrieving) {
            val pr = UiKit.bodyPaint(23f, Theme.dim)
            c.drawText(st.word("RETRIEVING_INVITE_LIST"), band.left + 8f, y - (pr.ascent() + pr.descent()) / 2f, pr)
        } else for (inv in invitees) {
            val pn = UiKit.bodyPaint(23f, Theme.classColourOf(inv.cls))
            c.drawText(TextUtils.ellipsize(CalendarModel.inviteeName(st, inv), TextPaint(pn), band.width() * 0.62f, TextUtils.TruncateAt.END).toString(),
                band.left + 8f, y - (pn.ascent() + pn.descent()) / 2f, pn)
            val ps = UiKit.bodyPaint(21f, Theme.invite(inv.status ?: 0)).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(st.statusName(inv.status), band.right - 8f, y - (ps.ascent() + ps.descent()) / 2f, ps)
            y += 40f
        }
        c.restore()
    }

    /** a holiday: its picture and name, the game's own words for it, its beginning and end */
    private fun drawHoliday(c: Canvas, st: State, e: Event, r: RectF) {
        val hol = e.id?.let { st.holidays[it] }
        val startPic = e.on.firstOrNull { it.seq == "START" }?.picture?.takeIf { it > 0 } ?: hol?.tex
        startPic?.let { UiKit.icon(it) }?.let {
            c.drawBitmap(it, Rect(0, 0, (it.width * 0.711f).toInt(), (it.height * 0.711f).toInt()),
                RectF(r.left + 4f, r.top + 8f, r.left + 124f, r.top + 128f), pBmp)
        }
        val left = if (startPic != null) r.left + 138f else r.left + 8f
        val name = hol?.name ?: e.title
        val tp = TextPaint(UiKit.bodyPaint(32f, Theme.gold))
        val nl = StaticLayout.Builder.obtain(name, 0, name.length, tp, (r.right - left).toInt()).setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        c.save(); c.translate(left, r.top + 66f - nl.height / 2f); nl.draw(c); c.restore()
        UiKit.paneRule(c, r.left, r.top + 140f, r.right)
        val box = RectF(r.left + 8f, r.top + 156f, r.right - 8f, r.bottom - 190f)
        noteBox = box
        val words = hol?.desc ?: ""
        val sl = StaticLayout.Builder.obtain(words, 0, words.length, TextPaint(UiKit.bodyPaint(23f, Theme.text)), box.width().toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f, 1f).build()
        noteContent = sl.height.toFloat()
        noteScroll = noteScroll.coerceIn(0f, maxOf(0f, noteContent - box.height()))
        c.save(); c.clipRect(box); c.translate(box.left, box.top - noteScroll); sl.draw(c); c.restore()
        val (begins, ends) = CalendarModel.beginsEnds(st)
        var y = r.bottom - 176f
        e.start?.let { y = UiKit.detailRow(c, r.left, y, r.right, begins, CalendarModel.shortDate(st, it) + "   " + CalendarModel.clock(st, it.h, it.mi)) }
        e.end?.let { UiKit.detailRow(c, r.left, y, r.right, ends, CalendarModel.shortDate(st, it) + "   " + CalendarModel.clock(st, it.h, it.mi)) }
        UiKit.hints(c, r.right - 4f, r.bottom - 34f, "rev_b" to "Back")
    }

    /** a raid lockout or reset: its name, and the game's one sentence */
    private fun drawRaid(c: Canvas, st: State, e: Event, r: RectF) {
        val name = if (e.diff != null) CalendarModel.format(st.word("DUNGEON_NAME_WITH_DIFFICULTY"), e.title, e.diff) else e.title
        val pt = TextPaint(UiKit.bodyPaint(32f, Theme.gold))
        while (pt.measureText(name) > r.width() - 16f && pt.textSize > 22f) pt.textSize -= 1f
        c.drawText(TextUtils.ellipsize(name, pt, r.width() - 16f, TextUtils.TruncateAt.END).toString(),
            r.left + 8f, r.top + 46f - (pt.ascent() + pt.descent()) / 2f, pt)
        UiKit.paneRule(c, r.left, r.top + 104f, r.right)
        say(c, r.left + 8f, r.top + 124f, r.width() - 16f, CalendarModel.raidSentence(st, e), 23f, Theme.text)
        UiKit.hints(c, r.right - 4f, r.bottom - 34f, "rev_b" to "Back")
    }

    /** the game's six filters, in its order, over the right-hand pane; a tick is ONE game action (the game's setting) */
    private fun drawFilters(c: Canvas, st: State, gx: Float, w: Float, h: Float) {
        c.drawRect(gx + 14f, 62f, w, h, Paint().apply { color = Color.rgb(18, 14, 9) })
        val box = RectF(gx + 20f, 70f, w - 6f, 70f + 100f + CalendarModel.FILTERS.size * 100f + 80f)
        UiKit.menu(c, box, solid = true)
        val p = UiKit.bodyPaint(30f, Theme.gold)
        c.drawText(st.word("CALENDAR_FILTERS"), box.left + 36f, box.top + 52f - (p.ascent() + p.descent()) / 2f, p)
        UiKit.divider(c, box.left + 20f, box.top + 91f, box.right - 20f, box.top + 91f)
        var y = box.top + 100f
        for ((i, f) in CalendarModel.FILTERS.withIndex()) {
            val row = RectF(box.left + 10f, y, box.right - 10f, y + 100f)
            val col = st.keys.filters[f.first]
            val enabled = st.padRow && col != null
            UiKit.menuChoice(c, row, st.word(f.second), selected = focus == Fk.Filter(i), on = st.filters[f.first] == true, enabled = enabled)
            add(Fk.Filter(i), row) { if (enabled) tap(col) }
            y += 100f
        }
        UiKit.hints(c, box.right - 28f, box.bottom - 56f, "rev_a" to "Tick", "rev_b" to "Back")
        // a touch outside the list closes it
        add(Fk.No, RectF(gx + 14f, box.bottom, w, h)) { filtersOpen = false; invalidate() }
    }

    /** Remove asks first, naming the event (the user's rule for anything permanent); Yes is the one game action */
    private fun drawConfirm(c: Canvas, st: State, e: Event, w: Float, h: Float) {
        hits.clear()
        nav.retainAll { it.key == Fk.Yes || it.key == Fk.No }
        UiKit.cover(c, RectF(0f, 0f, w, h))
        val box = RectF(w / 2f - 330f, h / 2f - 170f, w / 2f + 330f, h / 2f + 150f)
        val inner = UiKit.dialog(c, box)
        val words = "Remove ${e.title} from your calendar?"
        val sl = StaticLayout.Builder.obtain(words, 0, words.length, TextPaint(UiKit.bodyPaint(27f, Theme.text)), inner.width().toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build()
        c.save(); c.translate(inner.left, inner.top + 20f); sl.draw(c); c.restore()
        val bw = (inner.width() - 30f) / 2f
        val yes = RectF(inner.left, inner.bottom - 100f, inner.left + bw, inner.bottom)
        val no = RectF(inner.right - bw, inner.bottom - 100f, inner.right, inner.bottom)
        val col = CalendarModel.removeCol(st, e)
        UiKit.button(c, yes, st.word("YES"), "rev_a", if (col != null) UiKit.Button.UP else UiKit.Button.DISABLED)
        UiKit.button(c, no, st.word("NO"), "rev_b")
        if (focus == Fk.Yes) UiKit.focus(c, yes)
        if (focus == Fk.No) UiKit.focus(c, no)
        add(Fk.Yes, yes) { confirmRemove = false; if (col != null) tap(col); invalidate() }
        add(Fk.No, no) { confirmRemove = false; focus = Fk.More; invalidate() }
    }

    private fun say(c: Canvas, x: Float, y: Float, width: Float, words: String, size: Float, colour: Int = Theme.dim) {
        val sl = StaticLayout.Builder.obtain(words, 0, words.length, TextPaint(UiKit.bodyPaint(size, colour)), maxOf(1, width.toInt()))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(6f, 1f).build()
        c.save(); c.translate(x, y); sl.draw(c); c.restore()
    }

    private fun nothing(c: Canvas, r: RectF, words: String) {
        val p = UiKit.bodyPaint(25f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
        c.drawText(words, r.centerX(), r.top + 300f, p)
    }

    private fun add(key: Fk, r: RectF, act: () -> Unit) {
        val n = Nav(key, RectF(r), act)
        nav.add(n); hits.add(n)
    }

    /** a target inside a scrolled box: the D-pad reaches it anywhere, a finger only where it shows */
    private fun addClipped(key: Fk, r: RectF, clip: RectF, act: () -> Unit) {
        nav.add(Nav(key, RectF(r), act))
        val v = RectF(r)
        if (v.intersect(clip)) hits.add(Nav(key, v, act))
    }

    // ---------------------------------------------------------------- touch (SPEC 8)

    private var downY = 0f
    private var lastY = 0f
    private var downX = 0f
    private var moved = false
    private var scrollKey: String? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastY = e.y; moved = false
                scrollKey = when {
                    confirmRemove || filtersOpen -> null
                    pageRef != null && noteBox.contains(e.x, e.y) -> "note"
                    pageRef == null && listBox.contains(e.x, e.y) -> "list"
                    else -> null
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (Math.abs(e.y - downY) > slop || Math.abs(e.x - downX) > slop)) moved = true
                if (moved) {
                    val dy = lastY - e.y
                    when (scrollKey) {
                        "list" -> listScroll = (listScroll + dy).coerceIn(0f, maxOf(0f, listContent - listBox.height()))
                        "note" -> noteScroll = (noteScroll + dy).coerceIn(0f, maxOf(0f, noteContent - noteBox.height()))
                    }
                    invalidate()
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    val hit = hits.lastOrNull { it.r.contains(e.x, e.y) }
                    when {
                        // the glow goes where the finger was, unless the touch opened something with its own first target
                        hit != null -> {
                            val before = focus
                            touching = true; hit.act(); touching = false
                            if (focus == before) focus = hit.key
                        }
                        moreOpen -> moreOpen = false
                    }
                }
                tellB()
                invalidate()
            }
        }
        return true
    }

    // ---------------------------------------------------------------- the controller (SPEC 8)

    /** a press the addon relayed ({"type":"calpad"}); only while this room shows (MainActivity checks) */
    fun onPad(msg: JSONObject) {
        val n = msg.optInt("n", -1)
        if (n == lastPadN) return                                   // the same press seen twice
        lastPadN = n
        if (msg.has("lost")) { invalidate(); return }
        val k = msg.optString("k")
        // (c11) B was the game's own close button: the game closed its window and the controller already went back with "B"
        if (k == "BACK") { holdSent = false; leftByB = true; Log.i(TAG, "B closed the game's window: Home"); onLeave(); return }
        if (focus == null || nav.none { it.key == focus }) focus = firstTarget()     // onDraw keeps it on something; this is the spare
        val page = findPage()
        when (k) {
            "PADDUP" -> move(0, -1)
            "PADDDOWN" -> move(0, 1)
            "PADDLEFT" -> move(-1, 0)
            "PADDRIGHT" -> move(1, 0)
            "PAD1" -> nav.firstOrNull { it.key == focus }?.act?.invoke()                        // A
            "PAD2" -> back()                                                                     // B
            "PAD3" -> if (page == null && !filtersOpen && !confirmRemove) turn(-1)             // X: the month before
            "PAD4" -> when {                                                                     // Y
                confirmRemove || filtersOpen -> {}
                page != null -> nav.firstOrNull { it.key == Fk.More }?.act?.invoke()           // More, when it is there
                else -> turn(1)                                                                  // the month after
            }
        }
        tellB()
        invalidate()
    }

    private fun firstTarget(): Fk {
        if (confirmRemove) return Fk.No
        if (filtersOpen) return Fk.Filter(0)
        if (pageRef != null) return nav.firstOrNull { it.key is Fk.Answer }?.key ?: nav.firstOrNull { it.key == Fk.More }?.key ?: firstDayFocus()
        return firstDayFocus()
    }

    /** the glow's key for [day] of the month on show, from the month itself (the room's first draw may not have come yet) */
    private fun dayKey(day: Int?): Fk? {
        val st = state ?: return null
        val m = month() ?: return null
        if (day == null) return null
        val py = if (m.m == 1) m.y - 1 else m.y; val pm = if (m.m == 1) 12 else m.m - 1
        val i = CalendarModel.grid(m, CalendarModel.daysIn(py, pm), st.first).indexOfFirst { it.other == 0 && it.day == day }
        return if (i >= 0) Fk.Day(i) else null
    }

    private fun dayOf(k: Fk.Day): Int? {
        val st = state ?: return null
        val m = month() ?: return null
        val py = if (m.m == 1) m.y - 1 else m.y; val pm = if (m.m == 1) 12 else m.m - 1
        val cell = CalendarModel.grid(m, CalendarModel.daysIn(py, pm), st.first).getOrNull(k.index) ?: return null
        return if (cell.other == 0) cell.day else null
    }

    /** B: the question, More, the filters, the page, the day's list; the last B leaves the room for HOME */
    private fun back() {
        when {
            confirmRemove -> { confirmRemove = false; focus = Fk.More }
            moreOpen -> { moreOpen = false; focus = Fk.More }
            filtersOpen -> { filtersOpen = false; focus = Fk.Filters }
            pageRef != null -> closePage()
            focus is Fk.Entry || focus == Fk.Filters -> focus = firstDayFocus()
            else -> { leftByB = true; onLeave() }
        }
    }

    /** the chosen day; with none chosen, the month's first day (not a dark day of the month before) */
    private fun firstDayFocus(): Fk =
        dayKey(chosenDay) ?: nav.firstOrNull { it.key is Fk.Day && dayOf(it.key as Fk.Day) != null }?.key ?: Fk.Filters

    /** the glow moves to the nearest target that way (the days row by row, the arrows above, the day's list to the right) */
    private fun move(dx: Int, dy: Int) {
        val f = focus ?: return
        val cur = nav.firstOrNull { it.key == f } ?: return
        // SPEC 8, the day's list: right from Saturday enters it at its first entry; up and down stay in it (up from the
        // first entry: Filters); left returns to the month's chosen day. Everything else goes by distance.
        val hasEntries = nav.any { it.key == Fk.Entry(0) }
        when {
            f is Fk.Entry -> {
                when {
                    dx < 0 -> focus = firstDayFocus()
                    dy < 0 -> focus = if (f.i > 0) Fk.Entry(f.i - 1) else if (nav.any { it.key == Fk.Filters }) Fk.Filters else f
                    dy > 0 -> if (nav.any { it.key == Fk.Entry(f.i + 1) }) focus = Fk.Entry(f.i + 1)
                }
                (focus as? Fk.Entry)?.let { e -> nav.firstOrNull { it.key == e }?.let { scrollInto(it.r) } }
                return
            }
            f == Fk.Filters && pageRef == null && !filtersOpen -> {
                when {
                    dx < 0 -> focus = firstDayFocus()
                    dy > 0 && hasEntries -> focus = Fk.Entry(0)
                }
                return
            }
            f is Fk.Day && dx > 0 && f.index % 7 == 6 && pageRef == null && !filtersOpen -> {
                focus = if (hasEntries) Fk.Entry(0) else if (nav.any { it.key == Fk.Filters }) Fk.Filters else f
                return
            }
        }
        val cx = cur.r.centerX(); val cy = cur.r.centerY()
        var best: Nav? = null; var bestD = Float.MAX_VALUE
        for (n in nav) {
            if (n.key == f || n.key == Fk.No && !confirmRemove) continue
            val ex = n.r.centerX() - cx; val ey = n.r.centerY() - cy
            val along = if (dx != 0) ex * dx else ey * dy
            if (along <= 4f) continue
            val across = if (dx != 0) Math.abs(ey) else Math.abs(ex)
            val d = along + across * 2.5f
            if (d < bestD) { bestD = d; best = n }
        }
        val b = best ?: return
        focus = b.key
        if (b.key is Fk.Entry) scrollInto(b.r)
    }

    /** an entry out of view scrolls into it */
    private fun scrollInto(r: RectF) {
        val a = listBox
        if (r.top < a.top) listScroll = maxOf(0f, listScroll - (a.top - r.top))
        else if (r.bottom > a.bottom) listScroll = minOf(maxOf(0f, listContent - a.height()), listScroll + (r.bottom - a.bottom))
    }

    companion object {
        const val TAG = "WCCAL"
        const val RETRY_MS = 3000L      // (c10) time for a tap's document to come back before the tap goes again
        const val RETRY_MAX = 4
        const val DAY_W = 112f      // a day's width: one over the touch floor
        const val DAY_H = 130f      // six rows end at y 934, one pixel over Home's button
    }
}
