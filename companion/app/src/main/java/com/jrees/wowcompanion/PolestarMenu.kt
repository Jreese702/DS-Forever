package com.jrees.wowcompanion

import android.content.Context
import org.json.JSONObject

/**
 * THE POLESTAR MENU, ONE FOR EVERY ROOM (pm1; the user, 2026-11-16: "The polestar option in the map room, I want x to open the polestar
 * menu and y to open skip options to go to the next or previous step"). The Quests room's Polestar tab (pg1) and the Map room's X
 * overlay are the same menu: the add-on's documents ([book]), the cursor ([cur]), the one frame in flight ([PolestarTab.Sender]), the
 * open / keep-alive / close frames (ops 13 / 14), the resting step's goals (op 22) and what a press does. One instance (QuestsView makes
 * it, the host hands it to the Map room), so the add-on sees one open state and one frame at a time; each room draws it with its own
 * [PolestarPane]. Only the player's press sends anything (leave no trace).
 */
class PolestarMenu(private val context: Context, private val pad: () -> JSONObject?) {
    /** the add-on's Polestar documents, kept (the host routes pguide / pgsteps / pglist / pgstep / pgroute / pgop here) */
    val book = PolestarTab.Book()
    val cur = PolestarTab.Cursor()
    private val send = PolestarTab.Sender()
    private var openAt = 0L                          // when op 13 was last tapped (0 = the add-on is not told the menu is open)
    /** when the selection last moved (its goals are asked after REST_MS) */
    var selAt = 0L
    private var asked = ""                           // the step whose goals were asked last ("stamp:i")
    private var askedAt = 0L
    /** the room the menu shows in now (null: none) - a new guide re-enters the cursor only while it shows */
    var shownIn: String? = null
    /** a line from the menu (a refusal, a lost answer): the room that shows it says it */
    var note: (String) -> Unit = {}
    /** in a fight (D3) */
    var fight: () -> Boolean = { false }
    /** Skip ([prev] false) / Previous on Polestar's shown step: the host's one pnav tap; false = not sent */
    var onSkip: ((prev: Boolean) -> Boolean)? = null

    /** the menu can show: Quest tracking is Polestar, Polestar loaded and answering */
    fun shows(): Boolean = Settings.questTracking == "polestar" && book.ready()
    fun rows() = PolestarTab.rows(book.guide, cur.foldOpen)
    fun picks() = PolestarTab.pickRows(book.guide, book.list, cur.folders)
    private fun selStep(): PolestarTab.Row.Step? = rows().firstOrNull { it.key == cur.sel } as? PolestarTab.Row.Step
    fun detail(): PolestarTab.StepDetail? = selStep()?.let { book.step(it.i) }

    /** one document; true = it was one of the menu's (the room redraws) */
    fun onDoc(d: JSONObject, now: Long = System.currentTimeMillis()): Boolean {
        PolestarTab.answer(d)?.let { a -> answer(a, now); return true }
        val before = book.guide?.stamp
        val shownBefore = book.guide?.shown
        val routeBefore = book.route?.stamp
        if (!book.onDoc(d, now)) return false
        // pg1 review: the path view opened before its choice came (Home's row, a notice): it lands on the chosen / recommended option
        // when the choice arrives, as it would have had it been there
        if (d.optString("type") == "pgroute" && cur.view == PolestarTab.View.PATH && routeBefore != book.route?.stamp) cur.openPath(book.route)
        if (d.optString("type") == "pguide") {
            // a new guide (loaded, continued, chosen, chained on): the menu is entered afresh, on its shown step; else the cursor keeps
            // its step (on the shown step it follows the shown step: a Skip there leaves it on the next)
            if (before != book.guide?.stamp && shownIn != null) cur.enter(rows()) else cur.refresh(rows(), shownBefore, book.guide?.shown)
        } else cur.refresh(rows())
        return true
    }

    private fun answer(a: PolestarTab.Answer, now: Long) {
        val after = send.onAnswer(a.op, a.ok, a.why, now)
        after.next?.let { tap(it) }
        if (!after.done && after.next != null) return                     // (garbled: sent once more)
        if (!a.ok && a.op != PolestarTab.OPEN && a.op != PolestarTab.CLOSE && a.op != PolestarTab.DESCRIBE) {
            PolestarTab.refusedLine(a.why)?.let { note(it) }
            if (a.why == "changed") open(now, force = true)               // the docs again: the menu shows what Polestar has now
        }
    }

    /** one framed command ([op], [stamp], [args]): now, or after the one in flight */
    fun frame(op: Int, stamp: Int, args: List<Int> = emptyList()) {
        val taps = PolestarTab.frame(op, stamp, args)
        if (taps.isEmpty()) { note(PolestarSkip.REFUSED_LINE); return }
        send.send(op, taps, System.currentTimeMillis(), PolestarTab.frameMs(taps))?.let { tap(it) }
    }
    private fun tap(o: PolestarTab.Sender.Out) {
        val p = pad()
        if (p?.optJSONObject("rows")?.has(PolestarNav.ROW) != true) { send.failed(); note("The game has not published its tap pad yet"); return }
        val ok = TapBridge.tapsSpaced(context, p, o.taps.map { PolestarNav.ROW to it }, PolestarTab.GAP_MS) { why ->
            android.os.Handler(android.os.Looper.getMainLooper()).post { send.failed(); note(why) } }
        if (!ok) send.failed()
    }

    /** the add-on told the menu is open (at once, then every KEEP_MS while it shows). pg1 review: the first say (and a forced one: the
     *  picker, a "changed") is a fresh open, everything again; the later ones only keep it open ([PolestarTab.OPEN_KEEP]) */
    fun open(now: Long, force: Boolean = false) {
        if (!force && openAt != 0L && now - openAt < PolestarTab.KEEP_MS) return
        if (send.busy && !force) return
        val keep = !force && openAt != 0L
        openAt = now
        frame(PolestarTab.OPEN, if (keep) PolestarTab.OPEN_KEEP else PolestarTab.OPEN_FRESH)
    }
    /** the add-on told the menu is closed (only when it was told it is open) */
    fun close() {
        if (openAt == 0L) return
        openAt = 0L
        frame(PolestarTab.CLOSE, PolestarTab.OPEN_FRESH)
    }

    /** entered: the cursor on the shown step, the add-on told at once */
    fun enter(now: Long = System.currentTimeMillis()) {
        cur.enter(rows()); selAt = now
        open(now, force = true)
    }
    /** the path choice (Home's / the Map room's "Choose your path"): the view, and its options asked */
    fun openPath() { cur.openPath(book.route); book.guide?.let { frame(PolestarTab.ROUTE, it.stamp) } }

    /** while a room draws: the answer's wait; while the menu shows there ([active]) the open's keep-alive and the resting step's goals */
    fun tick(now: Long, active: Boolean) {
        send.tick(now)?.let { after ->
            after.next?.let { tap(it) }
            if (after.done && after.why == "lost") note(PolestarTab.LOST_LINE)
        }
        if (!active || !shows()) return
        open(now)
        val st = selStep() ?: return
        val g = book.guide ?: return
        if (send.busy || now - selAt < PolestarTab.REST_MS) return
        val key = "${g.stamp}:${st.i}"
        val have = book.stepAt(st.i)
        if (have != 0L && now - have < PolestarTab.STEP_FRESH_MS) return
        if (asked == key && now - askedAt < PolestarTab.STEP_FRESH_MS) return
        asked = key; askedAt = now
        frame(PolestarTab.DESCRIBE, g.stamp, PolestarTab.two(st.i))
    }

    /** what a press leaves to the room: nothing, B out of the menu's list (the room's own way back), Up from its first row */
    enum class Out { NONE, LEAVE, UP_TOP }

    /** one press [k] while the menu shows */
    fun press(k: String, now: Long = System.currentTimeMillis()): Out {
        val g = book.guide
        when (val d = cur.key(k, rows(), picks(), book.route, detail(), g, fight())) {
            PolestarTab.Do.Nothing, PolestarTab.Do.GoalsIn -> {}
            PolestarTab.Do.ToTabs -> return Out.UP_TOP
            PolestarTab.Do.Leave -> return Out.LEAVE
            is PolestarTab.Do.Sel -> selAt = now
            is PolestarTab.Do.Look -> g?.let { frame(PolestarTab.LOOK, it.stamp, PolestarTab.two(d.i)) }
            PolestarTab.Do.Back -> g?.let { frame(PolestarTab.BACK, it.stamp) }
            PolestarTab.Do.Skip -> if (onSkip?.invoke(false) != true) note(PolestarSkip.NO_STEP_LINE)
            PolestarTab.Do.Prev -> if (onSkip?.invoke(true) != true) note(PolestarSkip.NOTHING_LINE)
            PolestarTab.Do.Picker -> { cur.openPicker(picks()); open(now, force = true) }
            PolestarTab.Do.Path -> openPath()
            PolestarTab.Do.AskReset -> cur.openConfirm()
            PolestarTab.Do.Reset -> { g?.let { frame(PolestarTab.RESET, it.stamp) }; cur.toList() }
            PolestarTab.Do.Fold -> { cur.foldOpen = !cur.foldOpen; cur.refresh(rows()) }
            is PolestarTab.Do.Load -> book.list?.let { l -> frame(PolestarTab.LOAD, l.stamp, PolestarTab.two(d.k)); cur.toList() }
            is PolestarTab.Do.Cont -> g?.tabs?.getOrNull(d.t - 1)?.let { t -> frame(PolestarTab.CONTINUE, t.stamp, listOf(d.t)); cur.toList() }
            is PolestarTab.Do.Choose -> book.route?.let { r -> frame(PolestarTab.CHOOSE, r.stamp, listOf(d.o)); cur.toList() }
            is PolestarTab.Do.Tick -> g?.let { frame(PolestarTab.TICK, it.stamp, PolestarTab.two(d.i) + d.g) }
            is PolestarTab.Do.ToggleFolder -> { if (!cur.folders.add(d.f)) cur.folders.remove(d.f) }
            PolestarTab.Do.Out -> cur.toList()
            is PolestarTab.Do.Refused -> note(d.line)
        }
        return Out.NONE
    }

    /** a touch on one of the pane's rows ([PolestarPane.rowHits]' key): a pick / an option / a goal in its view, else a list row */
    fun touchRow(key: String, now: Long = System.currentTimeMillis()) {
        when {
            key.startsWith("p:") -> { if (cur.view != PolestarTab.View.PICKER) return; cur.pickSel(key.removePrefix("p:")) }
            key.startsWith("o:") -> cur.pathSel(key.removePrefix("o:").toIntOrNull() ?: 0)
            key.startsWith("g:") -> { if (cur.view == PolestarTab.View.LIST) cur.toGoals(); cur.goalSel(key.removePrefix("g:").toIntOrNull() ?: 0) }
            else -> { cur.select(key); selAt = now }
        }
    }
}
