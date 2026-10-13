package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject
import com.jrees.wowcompanion.ChatPad.Board as Board
import com.jrees.wowcompanion.ChatPad.Focus as Focus

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** 48 dp, the touch floor, on this 369 dpi panel */
private const val TOUCH = 112

/**
 * The bottom bar's Home button stands 34 px over a room's lower edge, in the middle (x 550..690;
 * the user's ruling on the bar, 2026-09-28). The message bar's children end 12 px over the row's
 * lower edge, so with nothing under it the row is this much higher. Asked by UI Art.
 */
private const val HOME_CLEAR = 24

/** room at the right of the message field for the A / Y glyph, and at the right of a line for the A glyph */
private const val BAR_GLYPH_W = 60
private const val LINE_GLYPH_W = 66

/** UI Art's SHEET_chat_rework (kitpreview ChatReworkSample, art-kit d30bfb01): the header, the bar, the board's head */
private const val HEAD_H = 92            // the type row: tabs 14..84 (UI Art's ChatTabsSample)
/** the INPUT screen (UI Art's SHEET_chat_input): the type row 104 px tabs at 12 (full width), the bar two lines (168 px) */
private const val HEAD_H_IN = 120
private const val BAR_H_IN = 168
/** the input coming up / going: the log rises behind the row as the board comes up (UI Art: ~240 ms on the app's curve) */
private const val INPUT_MS = 240L
private const val BAR_H = 80
/** the type row (UiKit.TabGaps, UI Art's ChatTabsSample): 16 px in, at most 170 px a tab, to 150 px from the right (Start + funnel) */
private const val TAB_LEFT = 16f
private const val TAB_W = 170f
private const val TABS_RIGHT = 150f

private fun lp(w: Int, h: Int, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h).apply { if (weight > 0f) this.weight = weight }

/** One line of the log, as Chat.lua sent it. */
private class Line(o: JSONObject) {
    val ch: String = o.optString("ch", "other")
    // the addon's sentinels are control characters; a name that starts with one is not a name
    val who: String = o.optString("who", "").let { if (it.isNotEmpty() && it[0].code > 3) it else "" }
    val text: String = o.optString("text", "")
    val chan: String = o.optString("chan", "")
    val idx: Int = o.optInt("idx", 0)
    val cls: String = o.optString("cls", "")
    val style: String = o.optString("style", "")
    val out: Boolean = o.optString("dir", "") == "out"
    val me: Boolean = o.optBoolean("me", false)
    val hm: String = o.optString("hm", "")
    /** what the text links (Chat.lua chat11: items, spells, quests, players), in the order they stand */
    val links: List<ChatLinks.Link> = o.optJSONArray("links")?.let { a ->
        (0 until a.length()).mapNotNull { k ->
            a.optJSONObject(k)?.let { l ->
                ChatLinks.Link(l.optString("k"), if (l.has("id")) l.optInt("id") else null, l.optString("n"),
                    if (l.has("p")) l.optString("p") else null)
            }
        }
    } ?: emptyList()
    /** every link's name and its own colour (Chat.lua chat16: the code the game put before it), in order */
    val lc: List<Pair<String, Int>> = o.optJSONArray("lc")?.let { a ->
        (0 until a.length()).mapNotNull { k ->
            a.optJSONObject(k)?.let { l ->
                val c = l.optString("c")
                if (c.length == 6) runCatching { l.optString("n") to Color.parseColor("#$c") }.getOrNull() else null
            }
        }
    } ?: emptyList()
    var drawn: CharSequence? = null
    var nameAt = -1             // where draw() put the sender's name, and the text
    var nameEnd = -1
    var textAt = 0
    var picks: List<Pick>? = null

    /** a line somebody ELSE wrote: the only kind a whisper can answer */
    val answerable: Boolean
        get() = who.isNotEmpty() && !me && ch !in NOT_PEOPLE

    companion object {
        val NOT_PEOPLE = setOf("npc", "system", "loot", "money", "currency", "info", "error", "other")
    }
}

/** a thing on a selected line the cursor can pick: its sender, or what its text links; [range] = its words in the drawn line */
private class Pick(val link: ChatLinks.Link, val range: IntRange, val sender: Boolean)

/** The filters, now in Start's menu (the user, 2026-10-07). A filter never hides a line for good: All always has everything. */
private enum class Filter(val label: String, val channels: Set<String>?) {
    ALL("All", null),
    NEARBY("Nearby", setOf("say", "yell", "emote", "npc")),
    GROUP("Group", setOf("party", "raid", "guild", "officer")),
    WHISPER("Whispers", setOf("whisper")),
    CHANNEL("Channels", setOf("channel")),
    SYSTEM("System", setOf("system", "loot", "money", "currency", "info", "error", "other"));

    fun shows(ch: String) = this == ALL || of(ch) == this

    companion object {
        /** the one filter a channel belongs to; a channel nobody has named yet reads as SYSTEM */
        fun of(ch: String): Filter = values().firstOrNull { it.channels?.contains(ch) == true } ?: SYSTEM
    }
}

/** a chat type the module sends on (ChatStream.TYPES' keys); [usable] = the player can talk there right now */
private class Kind(val key: String, val label: String, val colour: Int, val usable: Boolean = true)

/** the game's own limit on one chat message */
private const val MAX_MESSAGE = 255

/**
 * THE CHAT MODULE: the game's chat on the bottom screen, read and written without covering play.
 *
 * THE CHAT REWORK (the user, 2026-10-07; UI Art's SHEET_chat_rework, "we can build it as shown", without the Send by the bar):
 *  - a header with Select's (View) glyph and the Map room's funnel: Select opens the FILTER MENU (the filter row is gone; Start
 *    is Home's pause wheel in every room, the user 10-09);
 *  - the log; a selected line offers its LINKS (its sender, then the items / spells / quests / players its text links): left /
 *    right picks one (the gold box round its words), A opens its view (the game's tooltip; a player's Whisper);
 *  - the message bar: the chat TYPE as three of the game's hanging tabs (LB / RB slide them; the X menu is gone), the field;
 *  - under it THE BOARD, two pages X swaps: QUICK CHAT (12 phrases, A sends at once) and the companion's KEYBOARD (Start = Send);
 *  - while the board is up the log also shows big on the TOP screen, see-through over the game: the ADD-ON draws it (Chat.lua
 *    chat13, the user: "can we make it part of the wow addon instead of an overlay?"); this view only says when.
 * The rules of the controller are [ChatPad]'s; this view draws them and does the acts.
 *
 * SENDING GOES THROUGH THE ADD-ON (the user, 2026-10-07: "Send from the add-on, like quick chat"; it replaced the 09-28 typed chat,
 * whose injected letters made Android's keyboard app pop up over this screen): [ChatStream] mirrors the message into Chat.lua's
 * buffer one pad tap a character as it is typed, checks it and sends with ONE tap on the add-on's Send cell. Nothing is typed into
 * the game, and no Android keyboard is ever raised: a finger on the field opens the companion's own keyboard too.
 *
 * THE LOG IS KEPT HERE, NOT IN THE ADDON. Chat.lua re-sends its whole ring with every document; lines are appended by (session,
 * line number), so a /reload keeps the history.
 */
class ChatView(c: Context, @Suppress("UNUSED_PARAMETER") onSend: (String) -> Unit = {}) : LinearLayout(c) {

    /** Whether the module may send: only while the game is provably in the world. */
    private var sendEnabled = false
        set(v) { if (field != v) { field = v; applySendState() } }

    // the game going live or quiet is learnt by looking, twice a second; the stream's unanswered sends time out on the same beat
    private val watch = object : Runnable {
        override fun run() {
            sendEnabled = GameInput.live()
            stream.tick()
            if (noticeUntil in 1..System.currentTimeMillis()) { noticeUntil = 0; invalidate() }
            if (isAttachedToWindow) postDelayed(this, 500)
        }
    }

    // ---- the controller
    private val pad = ChatPad()
    private var lastPadN = -1
    private var lastLeadsHome: Boolean? = null
    private val glowRect = android.graphics.Rect()

    /** B with nothing open: the hub leaves the module for Home (and lets the controller go) */
    var onLeave: () -> Unit = {}

    /** whether B leads Home right now (true) or closes something first (false); called on a change */
    var onBackChanged: (Boolean) -> Unit = {}

    /**
     * The add-on's pad cells, for [ChatStream] (the hub sets it: TapBridge.tapsSpaced with its pad document). Until then nothing
     * can be sent, and the module says so.
     */
    var tapper: (List<Pair<String, Int>>, Long) -> Boolean = { _, _ -> false }

    /** Whether the controller's cursor is drawn when this view shows. */
    var cursorOnShow = true
        set(v) { field = v; if (!v) { pad.focus = null; adapter.notifyDataSetChanged(); invalidate() } }

    private val history = ArrayList<Line>()
    private val shown = ArrayList<Line>()
    private var filter = Filter.ALL
    private var selected: Line? = null
    private val unread = HashSet<Filter>()

    private var lastS = 0L      // the addon session the last numbered line came from
    private var lastI = 0       // and its line number
    private var legacyN = -1    // an addon from before line numbers: the last document's count

    // ---- where the message goes
    private var type = "SAY"
    private var replyTo = ""        // the last person who whispered the player (the add-on keeps its own for Reply)
    private var whisperName = ""    // the person the WHISPER type goes to (a selected line's sender, another room's door)
    private var canParty = true
    private var canGuild = true
    private var canRaid = false
    /** the channels the player has joined (Chat.lua chat16 `can.chans`): their numbers and names */
    private var chans: List<Pair<Int, String>> = emptyList()

    /**
     * THE TYPES IN THEIR FIXED PLACES (the user: "they should be in static positions"): Say, Party, Raid, Guild, Yell, Reply
     * always, greyed in place when the player cannot talk there; the whisper "-> Name" last, once there is somebody to whisper.
     */
    private fun kinds(): List<Kind> = buildList {
        add(Kind("SAY", "Say", colour("say")))
        if (canParty) add(Kind("PARTY", "Party", colour("party")))
        if (canRaid) add(Kind("RAID", "Raid", colour("raid")))
        if (canGuild) add(Kind("GUILD", "Guild", colour("guild")))
        add(Kind("YELL", "Yell", colour("yell")))
        if (replyTo.isNotEmpty()) add(Kind("REPLY", "Reply", colour("whisper")))
        // the joined channels (the user, b123: "its also mising the chat channels in citys like general and Trade"), in their
        // log colours, by their short names
        for ((i, n) in chans) add(Kind("CHANNEL:$i", chanLabel(n), NUMBERED[i] ?: Color.rgb(255, 192, 192)))
        if (whisperName.isNotEmpty()) add(Kind("WHISPER", "→ $whisperName", colour("whisper")))
    }

    /** a channel's tab name: the game's long ones shortened (LocalDefense, LookingForGroup), a custom one as it is */
    private fun chanLabel(n: String): String = when (n.substringBefore(" - ")) {
        "LocalDefense" -> "Defense"
        "LookingForGroup" -> "LFG"
        "WorldDefense" -> "World"
        else -> n.substringBefore(" - ")
    }

    // ---- what is open
    private var board = Board.NONE
    private var menuOpen = false
    private var viewOpen = false

    // ---- the add-on's half
    private val tips = ChatLinks.Tips<JSONObject>(300)
    private val stream = ChatStream({ cells, gap -> tapper(cells, gap) })
    private var notice = ""
    private var noticeUntil = 0L

    // ---- the parts
    private val head = Head(c)
    private val list = ListView(c)
    private val newest = GlyphButton(c, Glyph.DOWN, "")
    private val input = EditText(c)
    private val composer = LinearLayout(c)
    private val boardBox = LinearLayout(c)
    private val quick = QuickTiles(c)
    // the companion's own keyboard (the user's pick 2026-09-30): what a controller types with
    private val keyboard = ChatKeyboard(c)
    private val logBox = FrameLayout(c)
    /** 0: the log screen; 1: the input screen (the board up, the log gone up behind the row); between: the move */
    private var inputK = 0f
    /** what is written: the field only SHOWS it (its end, when it needs more than the field's lines) */
    private var typed = ""

    private var touchX = 0f
    private var touchY = 0f

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int): Any = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val tv = (convert as? TextView) ?: TextView(context).apply {
                textSize = 15f
                setLineSpacing(4f, 1f)
                setPadding(24, 11, LINE_GLYPH_W, 11)      // room at the right for the cursor's A glyph
                layoutParams = AbsListView.LayoutParams(MATCH, WRAP)
                // where a finger went down, so a tap on a selected line can open the link under it
                setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) { touchX = e.x; touchY = e.y }; false }
            }
            UiKit.asBody(tv)
            val l = shown[p]
            tv.text = l.drawn ?: draw(l).also { l.drawn = it }
            // the controller's cursor on this line: the kit's highlight UNDER the text; a selected line has the soft glow
            // (qlog_quest_glow, as the map's row link) and its chosen link the gold box (drawn over the list)
            val glowed = !morphOn && (pad.focus as? Focus.Line)?.i == p
            tv.background = when {
                glowed -> lineGlow(l === selected)
                l === selected -> selectedGlow()
                else -> null
            }
            return tv
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.ink)
        isFocusable = true
        isFocusableInTouchMode = true

        // ---- the type row at the top (the user: "the L and R chat tabs to be at the top"), Start's glyph and the funnel
        head.onFunnel = { setMenu(!menuOpen) }
        addView(head, lp(MATCH, HEAD_H))

        // ---- the log
        list.adapter = adapter
        list.divider = null
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.isStackFromBottom = true
        // NORMAL: a new line scrolls into view only when the newest one was already showing, so reading back is not interrupted
        list.transcriptMode = AbsListView.TRANSCRIPT_MODE_NORMAL
        list.setOnItemClickListener { _, v, p, _ -> lineTapped(p, v as? TextView) }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView?, state: Int) {}
            override fun onScroll(v: AbsListView?, first: Int, visible: Int, total: Int) {
                newest.visibility = if (total > 0 && first + visible < total) View.VISIBLE else View.GONE
                if (pad.focus is Focus.Line || pad.focus is Focus.Link || viewOpen) invalidate()   // the marks ride with their line
            }
        })
        newest.visibility = View.GONE
        newest.setOnClickListener { list.setSelection(adapter.count - 1) }
        logBox.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        logBox.addView(newest, FrameLayout.LayoutParams(TOUCH, TOUCH, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = 18; bottomMargin = 14
        })
        addView(logBox, lp(MATCH, 0, 1f))

        // ---- the message bar: just the field (the types went to the top; no Send by the bar: the user, "its redundant")
        composer.orientation = HORIZONTAL
        composer.gravity = Gravity.CENTER_VERTICAL
        composer.setBackgroundColor(Theme.panel)
        composer.setPadding(0, 0, 16, HOME_CLEAR)
        input.setHintTextColor(Theme.dim)
        input.setTextColor(Theme.text)
        input.textSize = 16f
        input.isSingleLine = false
        input.maxLines = 2
        // NEVER Android's keyboard (it popped up over this screen): a finger on the field opens the companion's own
        input.isFocusable = false
        input.isFocusableInTouchMode = false
        input.isCursorVisible = false
        input.setOnClickListener { if (board == Board.NONE) { openInput(); afterAct() } }
        input.filters = arrayOf(
            InputFilter { src, a, b, _, _, _ ->
                if ((a until b).all { ChatStream.cellOf(src[it]) != null }) null
                else src.subSequence(a, b).filter { ChatStream.cellOf(it) != null }
            },
            InputFilter.LengthFilter(MAX_MESSAGE))
        input.background = UiKit.background { cv, r -> UiKit.inputBox(cv, r) }
        input.setPadding(26, 0, 34 + BAR_GLYPH_W, 0)    // room at the right for the A / Y glyph
        input.gravity = Gravity.CENTER_VERTICAL
        composer.addView(input, lp(0, MATCH, 1f).apply { leftMargin = 16; topMargin = 5; bottomMargin = 5 })
        addView(composer, lp(MATCH, BAR_H + HOME_CLEAR))

        // ---- THE BOARD under the bar: quick chat and the keyboard, two pages of one board (X turns the page)
        boardBox.orientation = VERTICAL
        boardBox.setBackgroundColor(Theme.ink)
        boardBox.visibility = View.GONE
        boardBox.setPadding(0, 0, 0, HOME_CLEAR)
        quick.onTile = { t -> pad.focus = Focus.Quick(t); quickTile(t); afterAct() }
        boardBox.addView(quick, lp(MATCH, MATCH))
        keyboard.text = { typed }
        keyboard.onText = { t -> setTyped(t); restartBlink(); invalidate() }
        keyboard.onSend = { doSend() }
        keyboard.onClose = { setBoard(Board.NONE); pad.focus = Focus.Field; afterAct() }      // B: the input goes, the log again
        keyboard.onSwap = { setBoard(Board.QUICK); pad.focus = pad.quickFocus(scene()); afterAct() }
        boardBox.addView(keyboard, lp(MATCH, MATCH))
        addView(boardBox, lp(MATCH, 0))

        stream.onEvent = { e -> post { onStream(e) } }
        applySendState()
    }

    /** the game's type (UI art, fonts step) on the views this room keeps */
    private fun fonts() { UiKit.asBody(input) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        fonts()
        removeCallbacks(watch)
        post(watch)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(watch)
        stream.setTop(false)
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- the log

    fun update(chat: JSONObject) {
        chat.optJSONObject("can")?.let { can ->
            val party = can.optBoolean("party", false)
            val guild = can.optBoolean("guild", false)
            val raid = can.optBoolean("raid", false)
            val cs = can.optJSONArray("chans")?.let { a ->
                (0 until a.length()).mapNotNull { k -> a.optJSONObject(k)?.let { c -> c.optInt("i", 0).takeIf { it > 0 }?.let { it to c.optString("n") } } }
            } ?: emptyList()
            if (party != canParty || guild != canGuild || raid != canRaid || cs != chans) {
                canParty = party; canGuild = guild; canRaid = raid; chans = cs
                // a type that has just gone away cannot stay chosen
                if (kinds().none { it.key == type }) { type = "SAY"; stream.setType(type) }      // gone (left the group / city)
                head.invalidate()
            }
        }
        val lines = chat.optJSONArray("lines") ?: return
        val s = chat.optLong("s", 0L)
        val fresh = ArrayList<Line>()
        if (s == 0L) {
            // An addon from before line numbers. Its document IS the log, so it replaces it.
            val n = chat.optInt("n", -1)
            if (n == legacyN) return
            legacyN = n
            history.clear()
            select(null)
            for (k in 0 until lines.length()) lines.optJSONObject(k)?.let { history.add(Line(it)) }
            lastS = 0L; lastI = 0
        } else {
            for (k in 0 until lines.length()) {
                val o = lines.optJSONObject(k) ?: continue
                val i = o.optInt("i", 0)
                if (s == lastS && i <= lastI) continue      // the ring again: already have it
                lastS = s; lastI = i
                val l = Line(o)
                history.add(l)
                fresh.add(l)
            }
            if (fresh.isEmpty()) return
        }

        while (history.size > KEEP) {
            val gone = history.removeAt(0)
            if (gone === selected) select(null)
        }

        // A dot on the funnel means "something for you arrived where you were not looking". Only whispers and group chat earn one.
        val looking = isShown
        for (l in fresh) {
            if (l.me || l.out) continue
            if (l.ch == "whisper" && l.who.isNotEmpty() && l.who != replyTo) {
                replyTo = l.who
                head.invalidate()
            }
            val f = Filter.of(l.ch)
            if ((f == Filter.WHISPER || f == Filter.GROUP) && !(looking && filter.shows(l.ch))) unread.add(f)
        }
        head.invalidate()
        refill()
    }

    private fun refill() {
        shown.clear()
        for (l in history) if (filter.shows(l.ch)) shown.add(l)
        adapter.notifyDataSetChanged()
    }

    private fun pick(f: Filter) {
        filter = f
        unread.remove(f)
        if (f == Filter.ALL) unread.clear()
        if (selected?.let { f.shows(it.ch) } == false) select(null)
        head.invalidate()
        refill()
        list.setSelection(adapter.count - 1)
    }

    private fun select(l: Line?) {
        selected = if (l == null || l === selected) null else l
        viewOpen = false
        adapter.notifyDataSetChanged()
        refreshBack()
    }

    private fun selIndex(): Int? = selected?.let { s -> shown.indexOfFirst { it === s } }?.takeIf { it >= 0 }

    /** a finger on a line: selects it; on the selected line, a finger on a link opens it (a second tap elsewhere lets it go) */
    private fun lineTapped(p: Int, tv: TextView?) {
        val l = shown.getOrNull(p) ?: return
        if (l === selected && tv != null) {
            val off = tv.getOffsetForPosition(touchX, touchY)
            val k = picksOf(l).indexOfFirst { off in it.range }
            if (k >= 0) {
                pad.focus = Focus.Link(p, k)
                if (viewOpen && (pad.focus as? Focus.Link)?.k == k) viewOpen = false else openView()
                afterAct()
                return
            }
        }
        select(l)
        if (pad.focus != null) pad.focus = Focus.Line(p)      // the cursor follows the finger (onto the first link: settle)
        afterAct()
    }

    /**
     * WoW's own chat colours. Numbered channels are deliberately NOT all the same: Blizzard defaults every channel to the same
     * pink, which makes Trade, General and LFG indistinguishable in a merged window. Each channel index gets its own hue instead,
     * and the channel tag is shown so the mapping is legible.
     */
    private fun colourFor(l: Line): Int =
        if (l.ch == "channel") NUMBERED[l.idx] ?: Color.parseColor("#C5B9A0")
        else CHANNEL_COLOUR[l.ch] ?: Theme.textDim

    /** the sender's name in their CLASS colour -- the biggest readability win here */
    private fun nameColour(l: Line, fallback: Int): Int =
        if (l.cls.isNotEmpty()) Theme.classColourOf(l.cls) else fallback

    /** short tag for a numbered channel: "2. Trade - English" -> "Trade" */
    private fun tagFor(l: Line): String {
        val body = l.chan.substringAfter(". ", l.chan).substringBefore(" - ")
        return if (body.isBlank()) "ch${l.idx}" else body
    }

    private fun draw(l: Line): CharSequence {
        val sb = SpannableStringBuilder()
        val body = colourFor(l)
        fun put(s: String, colour: Int) {
            val a = sb.length
            sb.append(s)
            sb.setSpan(ForegroundColorSpan(colour), a, sb.length, 0)
        }
        fun name() { l.nameAt = sb.length; put(l.who, nameColour(l, body)); l.nameEnd = sb.length }
        // [Trade] tag, so a merged window stays legible; the group channels carry the game's own tags ("[Party] Name:")
        if (l.ch == "channel") put("[${tagFor(l)}] ", body)
        GROUP_TAG[l.ch]?.let { put("[$it] ", body) }
        val named = l.who.isNotEmpty() && l.style != "plain"
        when {
            !named -> {}
            l.out -> { put("To ", body); name(); put(": ", body) }
            l.ch == "whisper" -> { name(); put(" whispers: ", body) }
            l.style == "emote" -> { name(); put(" ", body) }
            else -> { name(); put(": ", body) }
        }
        l.textAt = sb.length
        put(l.text, body)
        // THE LINKS IN THEIR OWN COLOURS (the user: "the links in chat should have the different colors like they normally do"):
        // items by quality, spells, quests, achievements, players, as the game colours them (Chat.lua chat16)
        var from = 0
        for ((n, c) in l.lc) {
            val at = l.text.indexOf("[$n]", from)
            if (at < 0) continue
            sb.setSpan(ForegroundColorSpan(c), l.textAt + at, l.textAt + at + n.length + 2, 0)
            from = at + n.length + 2
        }
        return sb
    }

    /** what the cursor can pick on [l]: its sender (when a whisper can answer it), then its text's links that stand in the text */
    private fun picksOf(l: Line): List<Pick> {
        l.picks?.let { return it }
        if (l.drawn == null) l.drawn = draw(l)
        val out = ArrayList<Pick>()
        if (l.answerable && l.nameAt >= 0) out.add(Pick(ChatLinks.Link("player", null, l.who, l.who), l.nameAt until l.nameEnd, true))
        for ((i, s) in ChatLinks.spans(l.text, l.links).withIndex()) {
            if (s != null) out.add(Pick(l.links[i], (s.first + l.textAt)..(s.last + l.textAt), false))
        }
        l.picks = out
        return out
    }

    // ---------------------------------------------------------------- the type, the board, the menu, a link's view

    private fun colour(ch: String) = CHANNEL_COLOUR[ch] ?: Theme.text

    /** LB / RB: the next type the player can use, that way; at the end of the row it stays (the user: no cycling) */
    private fun stepType(step: Int) {
        val ks = kinds()
        var i = ks.indexOfFirst { it.key == type }.let { if (it < 0) 0 else it } + step
        while (i in ks.indices && !ks[i].usable) i += step
        if (i !in ks.indices) return
        type = ks[i].key
        applyType()
    }

    /** a finger on a type in the row */
    private fun chooseType(key: String) {
        if (kinds().none { it.key == key && it.usable } || key == type) return
        type = key
        applyType()
    }

    /** the type to the add-on at once (no game action); a whisper spells its person first */
    private fun applyType() {
        tellType()
        head.invalidate()
    }

    /** the type to the add-on: a whisper spells its person, a channel its number (chq 11), the rest are one tap */
    private fun tellType() {
        when {
            type == "WHISPER" -> { stream.setTarget(ChatNames.forCommand(whisperName)); stream.setType("WHISPER") }
            type.startsWith("CHANNEL:") -> { stream.setTarget(type.substringAfter(":")); stream.setType("CHANNEL") }
            else -> stream.setType(type)
        }
    }

    /**
     * Make the module ready to whisper [name]: a player's view (the selected line's sender, a linked player) and THE DOOR FOR
     * OTHER ROOMS (asked by the Social session, 2026-09-28): another room has the hub open this module first and then calls this.
     * It only picks where the NEXT message goes: nothing is sent. Returns false, and changes nothing, for a name [ChatNames]
     * refuses. [keyboard]: the keyboard opens (the player asked to write).
     */
    fun whisperTo(name: String, keyboard: Boolean = true): Boolean {
        val who = name.trim()
        if (!ChatNames.canWhisper(who)) return false
        whisperName = who
        type = "WHISPER"
        applyType()
        viewOpen = false
        if (keyboard && sendEnabled) { setBoard(Board.KEYS); pad.focus = Focus.Field }      // writing to a person: the keyboard
        afterAct()
        return true
    }

    private fun setMenu(open: Boolean) {
        if (open == menuOpen) return
        menuOpen = open
        if (open) viewOpen = false
        head.invalidate()
        invalidate()
        refreshBack()
    }

    private fun openView() { viewOpen = true; invalidate() }

    /** the open view's one action: a player's Whisper */
    private fun viewAction(k: Int) {
        val l = selected ?: return
        val p = picksOf(l).getOrNull(k) ?: return
        if (p.link.k != "player") return
        whisperTo(if (p.sender) l.who else (p.link.p ?: p.link.n))
    }

    private fun hint() = when {
        !sendEnabled -> "Waiting for the game"
        board == Board.QUICK -> "Pick a message"
        else -> "Message"
    }

    private fun applySendState() {
        // the field stays usable while the game is quiet: what is written is kept, and only the send waits
        input.hint = hint()
        keyboard.sendEnabled = sendEnabled
        quick.live = sendEnabled
    }

    /**
     * The board under the bar: NONE, QUICK (quick chat) or KEYS (the keyboard). It slides up out of the bar and down into it
     * (UI Art's HARMONY_SPEC section 3, the user's "As in the clips": Motion.KEYBOARD_UP / DOWN); turning its page does not move it.
     */
    private fun setBoard(to: Board, animate: Boolean = true) {
        if (to == board) return
        val was = board
        board = to
        if (to == Board.KEYS) { keyboard.sendEnabled = sendEnabled; keyboard.open() }
        if (to != Board.NONE) { quick.visibility = if (to == Board.QUICK) View.VISIBLE else View.GONE
                                keyboard.visibility = if (to == Board.KEYS) View.VISIBLE else View.GONE }
        if ((was == Board.NONE) != (to == Board.NONE)) {
            val up = to != Board.NONE
            if (up) { menuOpen = false; viewOpen = false }
            // THE TOP SCREEN WHILE TYPING (the user, b121: "when opening the keyboard, the readable chat should all be on the top
            // screen"): the add-on's top chat rises in as the log here rises away behind the type row (Chat.lua chat15)
            stream.setTop(up && isShown)
            moveInput(if (up) 1f else 0f, animate)
        }
        showTyped()
        applySendState()
        refreshBack()
        restartBlink()
        invalidate()
    }

    /** A on the bar, a finger on the field: the input the Settings name (the keyboard unless the player chose quick chat) */
    private fun openInput() {
        val to = defaultBoard()
        setBoard(to)
        pad.focus = if (to == Board.QUICK) pad.quickFocus(scene()) else Focus.Field
    }

    /** the Settings choice (Home's row "Message input", Settings.chatDefaultInput): "quick" = quick chat, anything else the keyboard */
    private fun defaultBoard(): Board = try {
        if (context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("chatDefaultInput", "keys") == "quick")
            Board.QUICK else Board.KEYS
    } catch (_: Throwable) { Board.KEYS }

    private var inputAnim: android.animation.ValueAnimator? = null

    /** the move between the log screen (0) and the input screen (1) */
    private fun moveInput(to: Float, animate: Boolean) {
        inputAnim?.cancel()
        inputAnim = null
        if (to > 0f) boardBox.visibility = View.VISIBLE
        if (!animate || !isShown) { inputK = to; applyInput(); if (to == 0f) boardBox.visibility = View.GONE; return }
        var cancelled = false
        inputAnim = android.animation.ValueAnimator.ofFloat(inputK, to).apply {
            duration = INPUT_MS
            interpolator = Motion.curve()
            addUpdateListener { inputK = it.animatedValue as Float; applyInput() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (cancelled) return
                    if (to == 0f) boardBox.visibility = View.GONE
                    applyInput()
                }
            })
            start()
        }
    }

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    /**
     * The parts' heights for [inputK]: the type row and the bar grow (UI Art: "the space should be filled by the message bar and the
     * tabs"), the board comes up to fill the rest, and the log in between gives way: its lines rise behind the row and are gone.
     */
    private fun applyInput() {
        val total = height
        if (total <= 0) return
        val k = inputK
        val headH = lerp(HEAD_H.toFloat(), HEAD_H_IN.toFloat(), k).toInt()
        val barH = lerp((BAR_H + HOME_CLEAR).toFloat(), BAR_H_IN.toFloat(), k).toInt()
        val boardFull = maxOf(0, total - HEAD_H_IN - BAR_H_IN)
        val boardH = (boardFull * k).toInt()
        val logH = maxOf(0, total - headH - barH - boardH)
        quick.fillHeight = boardFull - HOME_CLEAR
        keyboard.fillHeight = boardFull - HOME_CLEAR
        composer.setPadding(0, 0, 16, ((1f - k) * HOME_CLEAR).toInt())
        fun set(v: View, h: Int) { val p = v.layoutParams as LayoutParams; if (p.height != h || p.weight != 0f) { p.height = h; p.weight = 0f; v.layoutParams = p } }
        set(head, headH); set(logBox, logH); set(composer, barH); set(boardBox, boardH)
        head.k = k
        head.invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        post { applyInput() }
    }

    /** what is written changed: to the add-on's buffer ([ChatStream]) and into the field */
    private fun setTyped(t: String) {
        typed = stream.clean(t)
        stream.setBody(typed)
        showTyped()
    }

    /**
     * The field shows what is written; when it needs more than the field's lines (one on the log screen, two on the input screen:
     * UI Art) it shows the END, with "…" before it, so the caret is always where typing goes.
     */
    private fun showTyped() {
        val lines = if (board == Board.NONE) 1 else 2
        val room = input.width - input.totalPaddingLeft - input.totalPaddingRight
        var shown = typed
        if (room > 0 && typed.isNotEmpty()) {
            val p = android.text.TextPaint(input.paint)
            fun fits(s: String) = android.text.StaticLayout.Builder.obtain(s, 0, s.length, p, room).build().lineCount <= lines
            if (!fits(typed)) {
                var lo = 0; var hi = typed.length          // the longest end that fits behind "…"
                while (lo < hi) { val mid = (lo + hi + 1) / 2; if (fits("\u2026" + typed.takeLast(mid))) lo = mid else hi = mid - 1 }
                shown = "\u2026" + typed.takeLast(lo)
            }
        }
        if (input.text.toString() != shown) { input.setText(shown); input.setSelection(input.text.length) }
        input.maxLines = lines
    }

    // ---------------------------------------------------------------- sending (through the add-on)

    private fun doSend() {
        val text = typed
        if (text.isBlank()) return
        // asked at the moment of sending, not remembered: the text is kept if the game is not there
        if (!GameInput.live()) { sendEnabled = false; say("Waiting for the game"); return }
        if (stream.sending) return
        tellType()
        stream.setBody(text)
        if (stream.send()) say("Sending…", 4000)
    }

    /** a quick chat tile: a phrase goes at once; the bottom-right one is the Keyboard button */
    private fun quickTile(t: Int) {
        if (t == ChatLinks.KEYBOARD_TILE) { setBoard(Board.KEYS); pad.focus = Focus.Field; return }      // the way to the keyboard
        sendQuick(t)
    }

    private fun sendQuick(t: Int) {
        if (!GameInput.live()) { sendEnabled = false; say("Waiting for the game"); return }
        tellType()
        stream.quick(t)
    }

    private fun onStream(e: ChatStream.Event) {
        when (e) {
            ChatStream.Event.Sent -> {
                noticeUntil = 0
                setTyped("")
                // the input goes after a typed send, as the game's chat box does (the user, 2026-09-28: "close after"): the log again
                if (board == Board.KEYS) setBoard(Board.NONE)
                pad.focus = Focus.Field
                afterAct()
            }
            is ChatStream.Event.Refused -> say("Not sent: " + e.why.replaceFirstChar { it.uppercase() })
            is ChatStream.Event.QuickSent -> say("“" + ChatLinks.PRESETS[e.k] + "”", 1500)
        }
    }

    /** a short word over the bottom of the log */
    private fun say(text: String, ms: Long = 3000) {
        notice = text
        noticeUntil = System.currentTimeMillis() + ms
        invalidate()
    }

    /**
     * An answer of the add-on's for this module, routed here by the hub: {"type":"chatt"} (typed), {"type":"chatq"} (quick chat,
     * the type), {"type":"chatlink"} (a link's tooltip, cached by its payload).
     */
    fun onDoc(msg: JSONObject) {
        when (val t = msg.optString("type")) {
            "chatt", "chatq" -> stream.heard(ChatStream.Heard(t, msg.optString("op"), msg.optBoolean("ok"),
                if (msg.has("why")) msg.optString("why") else null, msg.optString("ty", "SAY"),
                msg.optInt("len", -1), msg.optInt("h", -1), msg.optInt("tlen", -1), msg.optInt("th", -1), msg.optString("into"),
                if (msg.has("top")) msg.optBoolean("top") else null))
            "chatlink" -> {
                val key = msg.optString("key")
                val tip = msg.optJSONObject("tip") ?: return
                if (key.isNotEmpty()) { tips.put(key, tip); if (viewOpen) invalidate() }
            }
        }
    }

    // ---- Home's open / close of the module (Q1: it morphs out of Home's chat panel like the map module)
    private var morphOn = false

    /**
     * One frame of Home's open / close (Map's contract, asked by Home 2026-10-07): [k] 0..1 along Home's animator, 1 ends it (a
     * cancel too). Below 1 the module draws no cursor, caret, link box, view, menu or notice, and its frame (the header, the type
     * tabs and the field) fades with the glide, in when [opening] and out when closing, so the lines read as Home's chat panel
     * growing into the module. Home moves and sizes the view itself.
     */
    fun morph(k: Float, opening: Boolean = true) {
        val kk = k.coerceIn(0f, 1f)
        val on = kk < 1f
        val a = if (!on) 1f else if (opening) kk else 1f - kk
        head.alpha = a
        composer.alpha = a
        if (on && !morphOn) { menuOpen = false; viewOpen = false; noticeUntil = 0 }
        if (on != morphOn) { morphOn = on; adapter.notifyDataSetChanged(); if (!on) restartBlink() }
        invalidate()
    }

    /**
     * The hub calls this when the module opens: the newest line into view (the keyboard opens only when asked for).
     */
    fun focusInput() {
        requestFocus()
        list.post { list.setSelection(adapter.count - 1) }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE && isShown) {
            if (filter == Filter.ALL) unread.clear() else unread.remove(filter)
            head.invalidate()
            // A new hold starts with B leading Home, so B's state is said again once the hub has asked for the controller
            lastLeadsHome = null
            // THE LOG FIRST (the user, b121: "I didn't mean that quick chat should immediately open when opening the chat module- it
            // should still open with A"): the input and the top screen's chat come with A
            setBoard(Board.NONE, animate = false)
            stream.setTop(false)
            // THE CURSOR IS THERE AT ONCE, in the message bar; posted so the list is laid out first
            post {
                if (cursorOnShow) pad.show(scene())
                adapter.notifyDataSetChanged()
                invalidate()
                refreshBack()
                restartBlink()
            }
            tellType()                      // the add-on may have reloaded: it learns the type again (no game action)
        } else if (visibility != View.VISIBLE) {
            setBoard(Board.NONE)
            menuOpen = false; viewOpen = false
            stream.setTop(false)            // the add-on also hides it by itself when the module lets the controller go
            pad.focus = null
            lastPadN = -1       // the press counter starts again after a /reload
        }
    }

    // ---------------------------------------------------------------- the controller

    private fun scene(): ChatPad.Scene {
        val sel = selIndex()
        val picks = selected?.let { picksOf(it) } ?: emptyList()
        val k = (pad.focus as? Focus.Link)?.k
        return ChatPad.Scene(
            lines = shown.size,
            selected = sel,
            links = if (sel != null) picks.size else 0,
            board = board,
            menu = menuOpen,
            menuItems = Filter.values().size,
            menuCurrent = filter.ordinal,
            view = viewOpen,
            viewAct = viewOpen && k != null && picks.getOrNull(k)?.link?.k == "player",
            quickTiles = ChatLinks.TILES,
            defaultBoard = defaultBoard(),
        )
    }

    /** D-pad left / right change the type right now: the log screen, the cursor on the bar or a line (not a selected line's links) */
    private fun typeOnDpad(): Boolean = board == Board.NONE && !menuOpen && inputK < 0.5f &&
        (pad.focus == null || pad.focus == Focus.Field || pad.focus is Focus.Line)

    /** true while B leads Home: nothing is open that B would close first */
    fun bLeadsHome(): Boolean = pad.leadsHome(scene())

    private fun refreshBack() {
        val home = bLeadsHome()
        if (home == lastLeadsHome) return
        lastLeadsHome = home
        onBackChanged(home)
    }

    /**
     * A press the addon relayed ({"type":"guestpad","id":"chat"}) or the app's own reader saw; the hub calls this only while the
     * module shows. The same press seen twice is dropped by its number; a lost hold only redraws.
     */
    fun onPad(msg: JSONObject) {
        val n = msg.optInt("n", -1)
        if (n >= 0 && n == lastPadN) return
        lastPadN = n
        if (msg.has("lost")) { invalidate(); return }
        val k = msg.optString("k")
        if (k.isEmpty()) return
        press(k)
    }

    /**
     * Start belongs to the room only while the KEYBOARD is up (its Send); everywhere else it opens Home's pause wheel (the user,
     * 10-09). Home sends Start here only while this says so.
     */
    fun ownsStart(): Boolean = board == Board.KEYS

    /** one controller key, from the addon's relay or from this window's own key events */
    private fun press(k: String) {
        // the keyboard has the buttons while it is up, but X / LB / RB (the room's: the page, the type)
        if (board == Board.KEYS && keyboard.press(k)) { refreshBack(); invalidate(); return }
        for (act in pad.press(k, scene())) when (act) {
            ChatPad.Act.Glow -> {}
            is ChatPad.Act.Select -> shown.getOrNull(act.i)?.let { select(it) }
            ChatPad.Act.Deselect -> select(null)
            is ChatPad.Act.Type -> stepType(act.step)
            is ChatPad.Act.Board -> setBoard(act.to)
            is ChatPad.Act.Quick -> quickTile(act.t)
            is ChatPad.Act.Menu -> setMenu(act.open)
            is ChatPad.Act.Filter -> pick(Filter.values()[act.m.coerceIn(0, Filter.values().size - 1)])
            is ChatPad.Act.Open -> openView()
            ChatPad.Act.CloseView -> { viewOpen = false; invalidate() }
            is ChatPad.Act.ViewAct -> viewAction(act.k)
            ChatPad.Act.Leave -> onLeave()
        }
        afterAct()
    }

    /** after any act: the cursor settles on what is there, and everything that shows it is redrawn */
    private fun afterAct() {
        pad.settle(scene())
        adapter.notifyDataSetChanged()          // a line's cursor is drawn by its row
        val i = when (val f = pad.focus) { is Focus.Line -> f.i; is Focus.Link -> f.i; else -> -1 }
        if (i >= 0 && (i < list.firstVisiblePosition || i > list.lastVisiblePosition - 1)) list.smoothScrollToPosition(i)
        quick.invalidate()
        head.invalidate()
        refreshBack()
        restartBlink()
        invalidate()
    }

    /**
     * BACK OR THE CONTROLLER'S B REACHING THIS WINDOW: Android turns an unhandled B into Back, which would send the app to the
     * background. Here it is the module's B: one step back, then Home.
     */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) press("PAD2")
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------------------------------------------------------------- drawing over the parts

    /** a line's cursor: the kit's focus inside the row (a drawable cannot draw past its bounds) */
    private fun lineGlow(selectedToo: Boolean) = UiKit.background { cv, r ->
        if (selectedToo) selectedGround(cv, r)
        UiKit.focus(cv, RectF(r.left + 14f, r.top + 6f, r.right - 14f, r.bottom - 6f))
    }

    /** the selected line: the passive glow (the map's row link, qlog_quest_glow), or the old ground when the art is missing */
    private fun selectedGlow() = UiKit.background { cv, r -> selectedGround(cv, r) }

    private fun selectedGround(c: Canvas, r: RectF) {
        val g = UiKit.tex("qlog_quest_glow")
        if (g != null) c.drawBitmap(g, null, RectF(r.left + 10f, r.top, r.right - 10f, r.bottom), bmp)
        else c.drawColor(Theme.selected)
    }

    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val menuRows = ArrayList<RectF>()
    private var menuBox: RectF? = null
    private var whisperCell: RectF? = null
    private var viewBox: RectF? = null

    private val fadePaint = Paint()

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // the log's lines slide UNDER the type row: a 70 px fade from the row's ground (UI Art)
        logRect().let { r ->
            fadePaint.shader = android.graphics.LinearGradient(0f, r.top, 0f, r.top + 70f, Theme.ink, Color.TRANSPARENT,
                android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRect(r.left, r.top, r.right, r.top + 70f, fadePaint)
        }
        if (morphOn) return                     // along Home's glide: the lines only
        barMarks(canvas)
        linkMarks(canvas)
        drawView(canvas)
        drawMenu(canvas)
        drawNotice(canvas)
    }

    /** the list's rect in this view's coordinates */
    private fun logRect(): RectF {
        list.getDrawingRect(glowRect)
        offsetDescendantRectToMyCoords(list, glowRect)
        return RectF(glowRect)
    }

    /** row [i]'s rect in this view's coordinates, or null when it is off screen */
    private fun rowRect(i: Int): Pair<TextView, RectF>? {
        val row = list.getChildAt(i - list.firstVisiblePosition) as? TextView ?: return null
        row.getDrawingRect(glowRect)
        offsetDescendantRectToMyCoords(row, glowRect)
        return row to RectF(glowRect)
    }

    /** the words of [range] on [tv] (whose rect is [r]): the first line they stand on */
    private fun wordsRect(tv: TextView, r: RectF, range: IntRange): RectF? {
        val lay = tv.layout ?: return null
        val a = range.first.coerceIn(0, tv.text.length)
        val b = (range.last + 1).coerceIn(a, tv.text.length)
        val line = lay.getLineForOffset(a)
        val x0 = lay.getPrimaryHorizontal(a)
        val x1 = if (lay.getLineForOffset(b) == line) lay.getPrimaryHorizontal(b) else lay.getLineRight(line)
        val ox = r.left + tv.totalPaddingLeft; val oy = r.top + tv.totalPaddingTop
        return RectF(ox + x0 - 6f, oy + lay.getLineTop(line) - 2f, ox + x1 + 6f, oy + lay.getLineBottom(line) + 2f)
    }

    // ---- the message bar and the line under the cursor (the user, 2026-09-30): the A button on the bar with the cursor
    // blinking, A opens the keyboard; on a selected message the A button, and the Y button back down on the bar
    private var caretOn = true
    private val blink = object : Runnable {
        override fun run() {
            caretOn = !caretOn
            invalidate()
            if (pad.focus == Focus.Field && isShown) postDelayed(this, 530)
        }
    }

    /** the caret blinks only while the cursor is on the bar, from fully shown */
    private fun restartBlink() {
        removeCallbacks(blink)
        caretOn = true
        if (pad.focus == Focus.Field) postDelayed(blink, 530)
    }

    private val caretPaint = Paint().apply { color = Theme.gold; strokeWidth = 3f }

    private fun barMarks(c: Canvas) {
        val f = pad.focus ?: return
        if (!input.isShown) return
        input.getDrawingRect(glowRect)
        offsetDescendantRectToMyCoords(input, glowRect)
        val fieldGlyph = when (f) {
            Focus.Field -> if (board == Board.KEYS) null else "rev_a"     // A here opens the keyboard
            is Focus.Line, is Focus.Link -> "rev_y"                      // Y comes back down here
            else -> null
        }
        if (fieldGlyph != null) {
            val g = minOf(48f, glowRect.height() * 0.6f)
            UiKit.pad(c, fieldGlyph, glowRect.right - 36f, glowRect.exactCenterY(), g)
        }
        if (f == Focus.Field && caretOn && board != Board.QUICK) {
            // the caret where typing goes: just after the text; in an empty field 4 px BEFORE where the hint starts
            val lay = input.layout
            val start = glowRect.left + input.totalPaddingLeft - input.scrollX
            val x = if (lay != null && input.text.isNotEmpty()) start + lay.getPrimaryHorizontal(input.text.length) + 2f
                    else start - 4f
            val h = input.lineHeight * 0.55f
            // on the line the text ends on (the input screen's field has two)
            val cy = if (lay != null && input.text.isNotEmpty()) {
                val line = lay.getLineForOffset(input.text.length)
                glowRect.top + input.totalPaddingTop - input.scrollY + (lay.getLineTop(line) + lay.getLineBottom(line)) / 2f
            } else glowRect.exactCenterY()
            c.drawLine(x, cy - h, x, cy + h, caretPaint)
        }
        if (f is Focus.Line) {
            val (_, r) = rowRect(f.i) ?: return
            val g = minOf(46f, r.height() * 0.7f)
            UiKit.pad(c, "rev_a", r.right - 16f - g / 2f, r.centerY(), g)   // A is this line's
        }
    }

    /** the chosen link on the selected line: THE gold box round its words, and what the buttons do here */
    private fun linkMarks(c: Canvas) {
        val f = pad.focus as? Focus.Link ?: return
        val l = selected ?: return
        val p = picksOf(l).getOrNull(f.k) ?: return
        val (tv, r) = rowRect(f.i) ?: return
        wordsRect(tv, r, p.range)?.let { UiKit.focus(c, it) }
        val log = logRect()
        UiKit.hints(c, log.right - 24f, log.bottom - 30f, "ltr_left" to "Links", "rev_a" to if (viewOpen) (if (p.link.k == "player") "Whisper" else "Close") else "Open",
            UiKit.backGlyph() to "Back")
    }

    /** the open link's view, just above its line: the game's tooltip (items, spells, quests), or a player's card and Whisper */
    private fun drawView(c: Canvas) {
        viewBox = null; whisperCell = null
        if (!viewOpen) return
        val f = pad.focus as? Focus.Link ?: return
        val l = selected ?: return
        val p = picksOf(l).getOrNull(f.k) ?: return
        val (tv, r) = rowRect(f.i) ?: return
        val words = wordsRect(tv, r, p.range) ?: r
        val log = logRect()
        val w = 520f
        val x = (words.left - 20f).coerceIn(16f, width - w - 16f)
        val name: String
        val nameColour: Int
        val lines: List<UiKit.TipLine>
        if (p.link.k == "player") {
            name = if (p.sender) l.who else p.link.n
            nameColour = if (p.sender && l.cls.isNotEmpty()) Theme.classColourOf(l.cls) else Theme.text
            lines = listOf(UiKit.TipLine(if (p.sender && l.cls.isNotEmpty()) l.cls.lowercase().replaceFirstChar { it.uppercase() } else "Player", Theme.dim))
        } else {
            val tip = tips[p.link.tipKey]
            val tl = tip?.optJSONArray("lines")
            val first = tl?.optJSONObject(0)
            name = p.link.n
            nameColour = first?.let { TipStack.colour(it.optString("c").takeIf { s -> s.isNotEmpty() }, Color.WHITE) }
                ?: if (p.link.k == "quest") Theme.gold else Color.WHITE
            lines = if (tip == null) listOf(UiKit.TipLine("…", Theme.dim))
                    else TipStack.lines(tl, first != null && TipStack.strip(first.optString("t")) == p.link.n)
        }
        val acts = if (p.link.k == "player") listOf<Pair<String?, String>>("rev_a" to "Whisper") else emptyList()
        val h = UiKit.tooltip(c, 0f, -9999f, w, name, nameColour, lines, alpha = 0).height() + (if (acts.isEmpty()) 0f else 62f)
        var y = words.top - h - 10f
        if (y < log.top + 4f) y = (words.bottom + 10f).coerceAtMost(log.bottom - h)
        val box = UiKit.tooltip(c, x, y, w, name, nameColour, lines)
        viewBox = box
        if (acts.isNotEmpty()) whisperCell = UiKit.tooltipActions(c, box, acts).firstOrNull()
    }

    /** Start's FILTER MENU under the funnel (the Map room's Show:): the filters, the one shown now ticked */
    private fun drawMenu(c: Canvas) {
        menuRows.clear(); menuBox = null
        if (!menuOpen) return
        val names = Filter.values()
        val rowH = 98f
        val box = RectF(width - 440f, HEAD_H + 2f, width - 16f, HEAD_H + 2f + names.size * rowH + 24f)
        menuBox = box
        val inner = UiKit.menu(c, box, solid = true)
        val cur = (pad.focus as? Focus.Menu)?.m
        for ((i, f) in names.withIndex()) {
            val r = RectF(inner.left, inner.top + i * rowH, inner.right, inner.top + (i + 1) * rowH)
            UiKit.menuChoice(c, r, f.label, cur == i, f == filter, radio = true)
            if (f in unread && f != filter) { dot.color = Theme.gold; c.drawCircle(r.right - 30f, r.centerY(), 7f, dot) }
            menuRows.add(r)
        }
    }

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val noticePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawNotice(c: Canvas) {
        if (noticeUntil == 0L || notice.isEmpty()) return
        val log = logRect()
        val p = UiKit.bodyPaint(28f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
        val tw = p.measureText(notice)
        val r = RectF(log.centerX() - tw / 2f - 24f, log.bottom - 64f, log.centerX() + tw / 2f + 24f, log.bottom - 14f)
        noticePaint.color = Color.argb(230, 9, 8, 8)
        c.drawRoundRect(r, 10f, 10f, noticePaint)
        c.drawText(notice, r.centerX(), r.centerY() + 10f, p)
    }

    /** a finger on the menu or a view's action; anywhere else with the menu open closes it */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            menuBox?.let { box ->
                val i = menuRows.indexOfFirst { it.contains(e.x, e.y) }
                if (i >= 0) { pick(Filter.values()[i]); setMenu(false); pad.settle(scene()); afterAct(); return true }
                if (!box.contains(e.x, e.y) && !head.funnelHit(e.x, e.y - head.top)) { setMenu(false); afterAct(); return true }
                if (box.contains(e.x, e.y)) return true
            }
            whisperCell?.let { if (it.contains(e.x, e.y)) { (pad.focus as? Focus.Link)?.let { f -> viewAction(f.k) }; return true } }
            viewBox?.let { if (it.contains(e.x, e.y)) return true }
        }
        return super.dispatchTouchEvent(e)
    }

    // ---------------------------------------------------------------- the parts drawn by hand

    /**
     * THE TYPE ROW AT THE TOP (the user, b119: "I want the L and R chat tabs to be at the top, and I don't want them to cycle,
     * they should be in static positions"; UI Art's ChatTabsSample): every type in its own place as the game's hanging tabs, the
     * chosen one raised in its channel's colour, the others at 170 alpha of theirs, one the player cannot use greyed in place; the
     * gaps and the LB / RB glyphs round the chosen tab (UiKit.TabGaps; no L at the first usable type, no R at the last). At the
     * right end Select's (View) glyph and the Map room's funnel (the filter menu); a dot when unread waits behind it.
     */
    private inner class Head(context: Context) : View(context) {
        var onFunnel: () -> Unit = {}
        private val funnel = RectF()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val gaps = UiKit.TabGaps(vertical = false)
        private var boxes: List<RectF> = emptyList()
        private var keys: List<String> = emptyList()
        /** 0: the log screen's row; 1: the input screen's (104 px tabs, the full width, no Start / funnel: the user, b121) */
        var k = 0f

        fun funnelHit(x: Float, y: Float) = k < 0.5f && RectF(funnel.left - 60f, 0f, width.toFloat(), height.toFloat()).contains(x, y)

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
            if (funnelHit(e.x, e.y)) { onFunnel(); afterAct(); return true }
            val i = boxes.indexOfFirst { it.contains(e.x, e.y) }
            if (i >= 0) { chooseType(keys[i]); afterAct() }
            return true
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            fill.color = Theme.panel
            c.drawRect(0f, 0f, w, height.toFloat(), fill)
            val ks = kinds()
            keys = ks.map { it.key }
            val chosen = ks.indexOfFirst { it.key == type }.let { if (it < 0) 0 else it }
            boxes = gaps.row(ks.size, TAB_LEFT, w - lerp(TABS_RIGHT, 16f, k), lerp(14f, 12f, k), lerp(70f, 104f, k), lerp(TAB_W, 200f, k), chosen, this)
            // ONE SIZE FOR THE ROW'S WORDS (UI Art, measured on the Thor: "General", "Defense", "Services" ran past their tabs while
            // only "TradeLocal" shrank): the hanging tab's art is ~35 px narrower than its box, so a word must fit the ART; every
            // label drops to the size where the longest fits (the chosen one too); a whisper's name is
            // left out of the rule and cut instead (one long name must not shrink the whole row)
            val fitW = (boxes.minOfOrNull { it.width() } ?: 0f) - 44f
            val probe = UiKit.bodyPaint(lerp(26f, 31f, this@Head.k), Color.WHITE)
            val rowLabels = ks.filter { it.key != "WHISPER" }.map { it.label }
            while (rowLabels.any { probe.measureText(it) > fitW } && probe.textSize > 18f) probe.textSize -= 1f
            val rowSize = probe.textSize
            for ((i, k) in ks.withIndex()) {
                UiKit.tab(c, boxes[i], "", i == chosen, onTop = false)
                val col = when {
                    !k.usable -> Theme.dim
                    i == chosen -> k.colour
                    else -> Color.argb(170, Color.red(k.colour), Color.green(k.colour), Color.blue(k.colour))
                }
                // the chosen one at the SAME size (UI Art: the kit's tabs do; the lit tab, its gaps and the arrows mark it, and a
                // label that grows and shrinks as the choice moves jumps most in a crowded row)
                val p = UiKit.bodyPaint(rowSize, col).apply { textAlign = Paint.Align.CENTER }
                val label = android.text.TextUtils.ellipsize(k.label, android.text.TextPaint(p), fitW,
                    android.text.TextUtils.TruncateAt.END).toString()
                c.drawText(label, boxes[i].centerX(), boxes[i].centerY() + 10f, p)
            }
            val canL = ks.take(chosen).any { it.usable }
            val canR = ks.drop(chosen + 1).any { it.usable }
            // THE GOLD ARROWS where the D-pad changes the type (the user: "im missing the gold arrows instead of L and R on the chat
            // tabs ... there are no obvious uses for Left and right D-pad when entering the room until selecting someones chat"):
            // the Controller room's arrows (no words) beside the chosen tab. Where left / right do something else (a selected line's
            // links, the input screen's keys and tiles) NOTHING: the user's tab ruling (10-07) took the L / R glyphs off tab rows,
            // LB / RB still work unlabelled as in every room, and the arrows coming back are the cue (UI Art)
            if (typeOnDpad()) UiKit.moveArrows(c, boxes[chosen], left = canL, right = canR, words = false)
            // Select's glyph and the funnel: the log screen's only (no filters on the input screen: the user, b121); they fade with the move
            funnel.set(w - 84f, 18f, w - 22f, 80f)
            val fa = ((1f - k) * 255).toInt()
            if (fa > 0) {
                val layer = c.saveLayerAlpha(funnel.left - 60f, 0f, w, height.toFloat(), fa)
                UiKit.tex(if (menuOpen) "map_filter_btn_down" else "map_filter_btn")?.let { c.drawBitmap(it, null, funnel, bmp) }
                UiKit.pad(c, "gen_back", funnel.left - 28f, funnel.centerY(), 44f)
                if (unread.isNotEmpty()) { fill.color = Theme.gold; c.drawCircle(funnel.right - 6f, funnel.top + 8f, 7f, fill) }
                c.restoreToCount(layer)
            }
        }
    }

    /**
     * QUICK CHAT: the board's first page, up from the moment the module opens: the 11 phrases as keys (the keyboard's own caps), A
     * or a finger sends one at once; the bottom-right key opens the keyboard (the user: "make the keyboard one of the buttons in
     * the bottom right (replace Inviting)"), the X glyph on it as X does the same.
     */
    private inner class QuickTiles(context: Context) : View(context) {
        var onTile: (Int) -> Unit = {}
        var live = true
            set(v) { field = v; invalidate() }
        private val tiles = ArrayList<RectF>()

        /** the board's height at rest (the room's): drawn at it while it slides, so the tiles come up whole */
        var fillHeight = 0
            set(v) { if (field != v) { field = v; invalidate() } }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                val t = tiles.indexOfFirst { it.contains(e.x, e.y) }
                if (t >= 0 && (live || t == ChatLinks.KEYBOARD_TILE)) onTile(t)
            }
            return true
        }

        override fun onDraw(c: Canvas) {
            tiles.clear()
            val r = RectF(10f, 0f, width - 10f, (if (fillHeight > 0) fillHeight else height) - 8f)
            val cols = 3; val gap = 12f
            val kw = (r.width() - gap * (cols - 1)) / cols
            val rows = (ChatLinks.TILES + cols - 1) / cols
            val kh = (r.height() - gap * (rows - 1)) / rows
            for (i in 0 until ChatLinks.TILES) {
                val col = i % cols; val row = i / cols
                val k = RectF(r.left + col * (kw + gap), r.top + row * (kh + gap), r.left + col * (kw + gap) + kw, r.top + row * (kh + gap) + kh)
                // the phrases faint while the game cannot take them; the Keyboard button never (writing waits for nobody)
                val faint = !live && i != ChatLinks.KEYBOARD_TILE
                val layer = if (faint) c.saveLayerAlpha(k.left - 2f, k.top - 2f, k.right + 2f, k.bottom + 2f, 110) else -1
                if (i == ChatLinks.KEYBOARD_TILE) UiKit.keyCap(c, k, "Keyboard", 1, mod = true, glyph = "rev_x")
                else UiKit.keyCap(c, k, ChatLinks.PRESETS[i], 1)
                if (layer >= 0) c.restoreToCount(layer)
                if ((pad.focus as? Focus.Quick)?.t == i) UiKit.focus(c, k)
                tiles.add(k)
            }
        }
    }

    private companion object {

        const val KEEP = 500

        val CHANNEL_COLOUR: Map<String, Int> = mapOf(
            "say" to Color.parseColor("#FFFFFF"),
            "yell" to Color.parseColor("#FF4040"),
            "party" to Color.parseColor("#AAABFE"),
            "raid" to Color.parseColor("#FF7F00"),
            "guild" to Color.parseColor("#40FF40"),
            "officer" to Color.parseColor("#40C040"),
            "whisper" to Color.parseColor("#FF80FF"),
            "emote" to Color.parseColor("#FF8040"),
            "npc" to Color.parseColor("#FFFF9F"),
            // the game's ChatTypeInfo (UI Art): loot a darker green than an uncommon item, so a green link stands out of it
            "loot" to Color.parseColor("#00AA00"),
            "money" to Color.parseColor("#FFFF00"),
            "currency" to Color.parseColor("#00AA00"),
            "system" to Color.parseColor("#FFFF00"),
            "info" to Color.parseColor("#FFFF00"),
            "error" to Color.parseColor("#FF5050"),
        )
        val GROUP_TAG: Map<String, String> = mapOf("party" to "Party", "raid" to "Raid", "guild" to "Guild", "officer" to "Officer")

        val NUMBERED: Map<Int, Int> = mapOf(
            1 to Color.parseColor("#FFC0C0"),   // General
            2 to Color.parseColor("#F5A97A"),   // Trade
            3 to Color.parseColor("#B6D7A8"),   // LocalDefense
            4 to Color.parseColor("#9FC5E8"),   // LookingForGroup
            5 to Color.parseColor("#D5A6BD"),
        )
    }
}

// ---------------------------------------------------------------- drawn parts

private enum class Glyph { DOWN }

/** simple stroke glyphs on a 24-unit grid, the same hand as the strip's */
private fun glyph(c: Canvas, g: Glyph, cx: Float, cy: Float, size: Float, p: Paint) {
    val s = size / 24f
    fun x(v: Float) = cx + (v - 12f) * s
    fun y(v: Float) = cy + (v - 12f) * s
    val path = Path()
    when (g) {
        Glyph.DOWN -> {
            path.moveTo(x(12f), y(4.5f)); path.lineTo(x(12f), y(19f))
            path.moveTo(x(5.5f), y(12.5f)); path.lineTo(x(12f), y(19f)); path.lineTo(x(18.5f), y(12.5f))
        }
    }
    c.drawPath(path, p)
}

/** A button drawn as a glyph (the log's "newest" arrow). */
private class GlyphButton(context: Context, private val g: Glyph, private val text: String) : View(context) {
    var dim = false
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(resolveSize(TOUCH, wSpec), resolveSize(TOUCH, hSpec))
    }

    override fun onDraw(c: Canvas) {
        if (UiKit.ready()) {
            val st = if (dim) UiKit.Button.DISABLED else UiKit.Button.UP
            UiKit.arrow(c, "down", width / 2f, height / 2f, minOf(width, height) + 10f, st)
            return
        }
        val box = RectF(1f, 1f, width - 1f, height - 1f)
        val r = height / 2f
        fill.color = Theme.raised
        c.drawRoundRect(box, r, r, fill)
        stroke.color = if (dim) Theme.bronzeDim else Theme.bronze
        stroke.strokeWidth = 1.6f
        c.drawRoundRect(box, r, r, stroke)
        stroke.color = if (dim) Theme.dim else Theme.gold
        stroke.strokeWidth = 2.8f
        glyph(c, g, width / 2f, height / 2f, 42f, stroke)
    }
}
