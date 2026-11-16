package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * THE COMPANION'S OWN KEYBOARD, drawn on the bottom screen (the user's pick 2026-09-30: "A keyboard drawn on
 * the bottom screen: D-pad over the keys, A types, B closes. Focus never leaves the game, so nothing can
 * ever be typed into the game by accident"). For the Chat room's message bar and Home's chat module, and
 * later anything else that needs a line typed (Send Mail).
 *
 * It types into a TEXT it is given ([text] / [onText]) and never into the game: what is written goes to the
 * game only when the owner sends it. The rules are [ChatKeys] (tested off the device); this draws them
 * with the kit's pieces (the game's tooltip border on each key, its glyphs, the gold box cursor) and takes
 * a finger on a key. The look is UI Art's to refine.
 */
class ChatKeyboard(c: Context, swap: Boolean = true, signIn: Boolean = false, voice: Boolean = false) : View(c) {

    /** [swap] false: no "(X) Quick chat" key (Space takes its width), for a box that only wants a line (Bags' search) */
    val keys = if (signIn) ChatKeys(ChatKeys.Layout.SIGN_IN, pipe = true) else ChatKeys(if (swap) ChatKeys.Layout.DEFAULT else ChatKeys.Layout.NO_SWAP)

    /**
     * VOICE TO TEXT ON THIS BOARD (the user's list, 2026-11-16: "X opens our own voice prompt", "intuitive and slick"; built as picked):
     * the voice page replaces the keys, with the board's own slide ([startVoice]). Only a box that takes words opts in ([voice]);
     * [signIn] forces it off (no sign-in board, number box, TypeTool or macro line ever listens).
     */
    val voiceOn: Boolean = voice && !signIn

    /** the swap key's word ("Tab" on Streaming's sign-in board) */
    var swapWord = "Quick chat"

    /** the Send key's word (Start / the Enter key): Bags' search says "Search" */
    var sendWord = "Send"
        set(v) { if (field != v) { field = v; invalidate() } }

    /** the text being written (read on every key) */
    var text: () -> String = { "" }
    /** the new text after a key */
    var onText: (String) -> Unit = {}
    var onSend: () -> Unit = {}
    var onClose: () -> Unit = {}
    /** the Quick chat key: the room turns to quick chat */
    var onSwap: () -> Unit = {}
    /** the height the room gives the board at rest (the input screen fills the room: UI Art's SHEET_chat_input); 0 = its own */
    var fillHeight = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Send is drawn faint while the game cannot take a message; a press still asks the owner */
    var sendEnabled = true
        set(v) { if (field != v) { field = v; invalidate() } }

    private val places = ArrayList<Triple<Int, Int, RectF>>()   // row, col, rect: the keys as last drawn
    private val fill = Paint().apply { style = Paint.Style.FILL }

    /** the keyboard opens: the cursor on the first letter, shift off */
    fun open() { keys.open(); invalidate() }

    /**
     * One controller button (the room's guest names: PADDUP .. PAD4, the shoulders, the triggers, PADFORWARD). Returns false for a
     * button that is not the keyboard's (X, LB, RB: [ChatKeys.Act.Pass]), which the room then handles.
     */
    fun press(button: String): Boolean {
        // the voice page has the buttons while it shows (but LB / RB: the room's, the chat type)
        if (sheet != null) return voicePress(button)
        // X on a board without the Quick chat key: voice, from the keys (B there comes back to these keys)
        if (button == "PAD3" && voiceOn && !keys.hasSwap && startVoice(fromKeys = true)) return true
        // A types the cursor's key: its letter pops up over it, as a finger's does (shown a moment, then fading)
        if (button == "PAD1") popFor(keys.row, keys.col, held = false)
        return apply(keys.press(button, text()))
    }

    /**
     * THE KEY POP-UP (the user: "The keyboard needs some button feedback with actually showing the button's letter that you are pressing
     * above your press so you know which letter you are hitting"): a phone keyboard's bubble - the key's character, enlarged on the
     * board's own keycap with a gold rim, ABOVE the key - while it is pressed (a finger held on it; A: [POP_SHOW_MS]), the key itself
     * pressed in; then it is gone at once, no fade (the user, 2026-10-30: "They should just instantly disappear the fade is weird").
     * Letters, digits and signs only (the word keys say what they are). Kept inside the board at its edges, so a top-row or end key's
     * bubble stays on screen.
     */
    private var popRow = -1
    private var popCol = -1
    private var popLabel = ""
    private var popHeld = false
    private var popAt = 0L
    private var popUpAt = 0L

    private fun popFor(r: Int, c: Int, held: Boolean) {
        val k = keys.layoutRows().getOrNull(r)?.cells?.getOrNull(c)?.key
        if (k !is ChatKeys.Key.Ch) { popRow = -1; return }
        popRow = r; popCol = c; popLabel = keys.charOf(k).toString()
        val now = android.os.SystemClock.uptimeMillis()
        popAt = now; popHeld = held; popUpAt = if (held) 0L else now + POP_SHOW_MS
        invalidate()
    }

    private fun popShown(now: Long) = popRow >= 0 && (popHeld || now < popUpAt)

    private fun apply(a: ChatKeys.Act): Boolean {
        when (a) {
            is ChatKeys.Act.Text -> onText(a.text)
            ChatKeys.Act.Send -> onSend()
            ChatKeys.Act.Close -> onClose()
            ChatKeys.Act.Pass -> return false
            ChatKeys.Act.Swap -> onSwap()
            ChatKeys.Act.Glow, ChatKeys.Act.Refused -> {}
        }
        invalidate()
        return true
    }

    // UI Art's geometry (KeyboardSample.kt on art-kit 8e9a6676): a solid kit menu panel 6 px in from the sides and
    // 4 px over the bottom; inside it 16 px, rows of 92 px keys 8 px apart, ten units across; the hints under the keys
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), resolveSize(fullHeight(), hSpec))
    }

    private val board = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        c.drawColor(Theme.ink)
        // drawn at the FULL height even while the room gives it less: sliding in, its top edge carries the keys up
        // from below (the room clips the rest), so the board slides and is never squeezed
        val kb = RectF(6f, 0f, width - 6f, (if (fillHeight > 0) fillHeight else fullHeight()) - 4f)
        // look 1, the user's pick: the keys on a black board with a thin bronze-brown edge (UI Art's sample)
        board.style = Paint.Style.FILL; board.color = Color.rgb(6, 6, 6)
        c.drawRoundRect(kb, 14f, 14f, board)
        board.style = Paint.Style.STROKE; board.strokeWidth = 2f; board.color = Color.rgb(60, 52, 40)
        c.drawRoundRect(kb, 14f, 14f, board)
        places.clear()
        sheet?.let { drawVoice(c, kb, it); return }
        val rows = keys.layoutRows()
        val unit = (kb.width() - INSET * 2 - 9 * GAP) / 10f
        // the keys as tall as the board allows (the input screen fills the room: ~120 px keys), never shorter than KEY_H
        val keyH = maxOf(KEY_H, (kb.height() - INSET * 2 - (rows.size - 1) * GAP) / rows.size)
        var y = kb.top + INSET
        for ((ri, row) in rows.withIndex()) {
            var x = kb.left + INSET + row.lead * (unit + GAP)          // the A-L row half a key in
            for ((ci, cell) in row.cells.withIndex()) {
                val w = unit * cell.w + GAP * (cell.w - 1f)
                val r = RectF(x, y, x + w, y + keyH)
                drawKey(c, r, cell.key, down = ri == popRow && ci == popCol && popShown(android.os.SystemClock.uptimeMillis()))
                places += Triple(ri, ci, r)
                if (ri == keys.row && ci == keys.col) UiKit.focus(c, r)       // the gold box: the cursor
                x += w + GAP
            }
            y += keyH + GAP
        }
        drawPop(c, kb)
        // what the controller does here, the game's way: glyph and word, under the keys
        // NO glyph legend under the keys (the user, LATER 27: "the button glyph legend at the bottom of the keyboard is redundant
        // and should be removed"): the keys that have a button carry its glyph themselves
    }

    /** the pressed key's bubble over it (see [popFor]), inside the board [kb] */
    private fun drawPop(c: Canvas, kb: RectF) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!popShown(now)) { popRow = -1; return }
        val key = places.firstOrNull { it.first == popRow && it.second == popCol }?.third ?: return
        val w = maxOf(key.width() * 1.45f, key.height() * 1.1f); val h = key.height() * 1.35f
        var left = key.centerX() - w / 2f
        left = left.coerceIn(kb.left + 4f, kb.right - 4f - w)
        // over the key, its foot overlapping the key's top; never above the board (a top-row key's bubble stays on screen)
        val top = (key.top - h + key.height() * 0.30f).coerceAtLeast(kb.top + 2f)
        val bub = RectF(left, top, left + w, top + h)
        shadow.color = Color.argb(150, 0, 0, 0)
        c.drawRoundRect(bub.left + 3f, bub.top + 6f, bub.right + 3f, bub.bottom + 6f, h * 0.16f, h * 0.16f, shadow)
        UiKit.keyCap(c, bub, "", LOOK)
        rim.color = Theme.gold
        c.drawRoundRect(bub, h * 0.14f, h * 0.14f, rim)
        val p = UiKit.bodyPaint(h * 0.52f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
        c.drawText(Tr.t(popLabel), bub.centerX(), bub.centerY() - (p.ascent() + p.descent()) / 2f, p)
        if (!popHeld) postInvalidateDelayed(popUpAt - now)
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }

    /** one key: the kit's keycap, LOOK 1 (dark), as the user picked on SHEET_keyboard_v2.png; [down] = pressed now (its pop-up shows) */
    private fun drawKey(c: Canvas, r: RectF, k: ChatKeys.Key, down: Boolean = false) {
        when (k) {
            is ChatKeys.Key.Ch -> UiKit.keyCap(c, r, keys.charOf(k).toString(), LOOK, down = down)
            ChatKeys.Key.Shift -> {
                UiKit.keyCap(c, r, "Shift", LOOK, mod = true, on = keys.shift != ChatKeys.Shift.OFF, glyph = "rev_rtrigger")
                if (keys.shift == ChatKeys.Shift.LOCK) capsLine(c, r, "Shift")      // locked: a 3 px gold line under the word
            }
            ChatKeys.Key.Page -> UiKit.keyCap(c, r, if (keys.page == 0) "?123" else "abc", LOOK, mod = true, on = keys.page != 0)
            ChatKeys.Key.Space -> UiKit.keyCap(c, r, "Space", LOOK, glyph = "rev_y")
            ChatKeys.Key.Back -> UiKit.keyCap(c, r, "", LOOK, mod = true, glyph = "rev_ltrigger", icon = "backspace")
            ChatKeys.Key.Swap -> UiKit.keyCap(c, r, swapWord, LOOK, mod = true, glyph = "rev_x")
            ChatKeys.Key.Send -> {
                // the ONE send while the keyboard is up; faint while the game cannot take it or nothing is written
                val can = sendEnabled && text().isNotBlank()
                val layer = if (can) -1 else c.saveLayerAlpha(r.left - 2f, r.top - 2f, r.right + 2f, r.bottom + 2f, 110)
                UiKit.keyCap(c, r, sendWord, LOOK, mod = true, glyph = "gen_forward", icon = "enter")
                if (layer >= 0) c.restoreToCount(layer)
            }
        }
    }

    /** under a glyph-and-word key's word, where UiKit.keyCap sets it: the locked Shift's gold line (the SPEC) */
    private fun capsLine(c: Canvas, r: RectF, label: String) {
        val gw = r.height() * 0.40f
        val p = UiKit.bodyPaint(r.height() * 0.30f, Theme.gold)
        while (p.measureText(label) > r.width() - 24f - gw && p.textSize > 14f) p.textSize -= 1f
        val tw = p.measureText(label) + gw + 8f
        val x = r.centerX() - tw / 2f + gw + 8f
        val base = r.centerY() - (p.ascent() + p.descent()) / 2f
        underline.color = Theme.gold
        c.drawRect(x, base + 5f, x + p.measureText(label), base + 8f, underline)
    }

    private val underline = Paint().apply { style = Paint.Style.FILL }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (sheet != null) { voiceTouch(e); return true }
        val at = places.firstOrNull { it.third.contains(e.x, e.y) }
        when (e.actionMasked) {
            // the finger's key pops up while held, follows the finger across the keys, and types on the lift (as before)
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (at == null) { if (popHeld) { popHeld = false; popUpAt = android.os.SystemClock.uptimeMillis(); invalidate() } }
                else if (!popHeld || at.first != popRow || at.second != popCol) popFor(at.first, at.second, held = true)
            }
            MotionEvent.ACTION_UP -> {
                if (popHeld) { popHeld = false; popUpAt = android.os.SystemClock.uptimeMillis(); invalidate() }
                at?.let { (r, col, _) -> apply(keys.touch(r, col, text())) }
            }
            MotionEvent.ACTION_CANCEL -> { popRow = -1; popHeld = false; invalidate() }
        }
        return true
    }

    // ================================================================ THE VOICE PAGE
    // The user's picks (2026-11-16): tap X to talk; it stops by itself when you go quiet; the words are shown first (stable ones white,
    // the rest dim, a gold caret) and go in only on A / Start, from 0.4 s after they settle; Y = the keyboard with the words in place,
    // X = say it again (only what this sheet heard is replaced), B = the box's earlier text back. Android's microphone question on the
    // first X, never in a fight. The rules are [Voice.Sheet]'s (tested off the device); this draws them and does the acts.

    /** the box this board types into now takes words (a shared board, Interact's, says per field: never To, money or levels) */
    var voiceBox = true
    /** a MESSAGE box ([voiceAppend]: the words go after its text) or a SEARCH / NAME box (they replace it) */
    var voiceAppend = false
    /** the box's own limit (the add-on field's, a radial name's ...) */
    var voiceMax = ChatKeys.MAX
    /** the words before the box's text on the voice page: Chat's channel prefix, in that channel's colour */
    var voicePrefix: () -> Pair<String, Int>? = { null }
    /** how long the box's copy into the add-on still takes (ChatStream.copyLeftMs): the finish button's gold fill */
    var copyLeftMs: () -> Long = { 0L }
    /** the voice page changed (a redraw for a host that draws this board itself, Bags' live dimming ...) */
    var onVoice: () -> Unit = {}
    /** the off-device renders: the permission and the recognizer as given (null = ask Android) */
    var voiceProbe: (() -> Pair<Voice.Perm, Voice.Avail>)? = null
    /** the recognizer (the renders and tests give their own) */
    var engine: VoiceListen? = null
    /** the clock (the tests' own) */
    var clock: () -> Long = { android.os.SystemClock.uptimeMillis() }

    /** what listens: Android's recognizer ([VoiceEngine]) */
    interface VoiceListen {
        fun start(online: Boolean, lang: String, hints: List<String>, sink: VoiceEngine.Sink): Boolean
        fun stop()
        fun cancel()
        fun getPack(lang: String): Boolean
    }

    private var sheet: Voice.Sheet? = null
    private var voiceFromKeys = false
    private var copyTotal = 0L
    /** the voice page shows (its buttons are the page's) */
    val voiceShown: Boolean get() = sheet != null
    /** the sheet, for the renders and the tests */
    val voiceSheet: Voice.Sheet? get() = sheet
    /** the page is moving (ripples while listening): a host that draws this board itself redraws on every frame */
    val voiceAnimating: Boolean get() = sheet?.state.let { it == Voice.State.LISTENING || it == Voice.State.STOPPING }

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    /** every page stops when the host goes (Voice.stopAll), but one waiting on Android's microphone question (it holds no microphone) */
    private val stopHook: () -> Unit = { if (!voiceAsking) stopVoice() }
    private var askPending = false
    /**
     * Android's microphone question is up for this page: the host's window is hidden meanwhile (openOnBottom), so the page and its host
     * must not take that hiding as "the box went away" (the page waits for the answer, then listens)
     */
    val voiceAsking: Boolean get() = askPending && sheet != null

    private fun listen(): VoiceListen = engine ?: object : VoiceListen {
        private val e by lazy { VoiceEngine(context) }
        override fun start(online: Boolean, lang: String, hints: List<String>, sink: VoiceEngine.Sink) = e.start(online, lang, hints, sink)
        override fun stop() = e.stop()
        override fun cancel() = e.cancel()
        override fun getPack(lang: String) = e.getPack(lang)
    }.also { engine = it }

    private fun probe(mode: Voice.Mode): Pair<Voice.Perm, Voice.Avail> =
        voiceProbe?.invoke() ?: (VoiceMic.perm(context) to VoiceEngine.avail(context, mode))

    /**
     * X on the box (or on these keys: [fromKeys], so B comes back to them): the voice page over the keys. False, and nothing changes,
     * when this board takes no voice, Voice typing is Off or the device has no microphone (the box's X keeps its old job then).
     */
    fun startVoice(fromKeys: Boolean = false): Boolean {
        if (!voiceOn || !voiceBox || !VoiceUi.micShown(context)) return false
        stopVoice(restore = false)
        val mode = Settings.voiceTyping
        val s = Voice.Sheet(text(), voiceAppend, voiceMax, mode, clock)
        sheet = s; voiceFromKeys = fromKeys; copyTotal = 0L
        Voice.register(stopHook)
        val (perm, avail) = probe(mode)
        DiagLog.event("voice", s.logLine("open") + " perm=${perm.name.lowercase()} avail=${avail.name.lowercase()}")
        run(s.open(perm, avail))
        ticking()
        return true
    }

    /** the voice page goes without a word from the player (the box hid, the host went): [restore] = the box gets its earlier text back */
    fun stopVoice(restore: Boolean = true) {
        val s = sheet ?: return
        engine?.cancel()
        endSheet()
        DiagLog.event("voice", s.logLine("stopped"))
        if (restore && s.pushed != s.before) onText(s.before)
        invalidate(); onVoice()
    }

    private fun endSheet() {
        sheet = null
        askPending = false
        Voice.unregister(stopHook)
        ui.removeCallbacks(tick)
        // only this page's own wait for Android's answer (another board's stays)
        myAnswer?.let { if (VoiceMic.onAnswer === it) VoiceMic.onAnswer = null }
        myAnswer = null
    }

    /** this page's wait for Android's microphone answer ([askMic]) */
    private var myAnswer: (() -> Unit)? = null

    private val sink = object : VoiceEngine.Sink {
        override fun rms(db: Float) { sheet?.rms(db) }
        override fun partial(text: String) { sheet?.let { run(it.partial(text)) } }
        override fun final(text: String) { sheet?.let { s -> run(s.final(text)); DiagLog.event("voice", s.logLine("final")) } }
        override fun error(code: Int) { sheet?.let { s -> run(s.error(code)); DiagLog.event("voice", s.logLine("error $code")) } }
    }

    /** the recognizer's events, for the off-device renders and tests (as Android would call them) */
    fun voiceSink(): VoiceEngine.Sink = sink

    private fun voicePress(button: String): Boolean {
        val s = sheet ?: return false
        val acts = s.press(button, Voice.inFight())
        if (acts.singleOrNull() == Voice.Act.Pass) return false
        run(acts)
        return true
    }

    private fun run(acts: List<Voice.Act>) {
        for (a in acts) {
            val s = sheet ?: break
            when (a) {
                is Voice.Act.Start -> {
                    val lang = Voice.language(Tr.lang())
                    val hints = if (a.online) emptyList() else runCatching { Voice.hints() }.getOrDefault(emptyList())
                    if (!listen().start(a.online, lang, hints, sink)) { run(s.error(Voice.E_BUSY)); return }
                    DiagLog.event("voice", s.logLine("listen"))
                }
                Voice.Act.Stop -> listen().stop()
                Voice.Act.Abort -> engine?.cancel()
                is Voice.Act.Push -> onText(a.text)
                is Voice.Act.Finish -> {
                    endSheet(); DiagLog.event("voice", s.logLine("accept"))
                    onText(a.text); onSend()
                }
                is Voice.Act.Edit -> {
                    endSheet(); DiagLog.event("voice", s.logLine("edit"))
                    onText(a.text); keys.open()
                }
                Voice.Act.Close -> {
                    val back = voiceFromKeys
                    endSheet(); DiagLog.event("voice", s.logLine("cancel"))
                    if (!back) onClose()
                }
                Voice.Act.AskMic -> askMic(s)
                Voice.Act.UseOnline -> { Settings.voiceTyping = Voice.Mode.ONLINE; DiagLog.event("voice", "online voice picked") }
                Voice.Act.GetPack -> { val ok = listen().getPack(Voice.language(Tr.lang())); DiagLog.event("voice", "pack download asked ok=$ok") }
                Voice.Act.Pass, Voice.Act.Glow -> {}
            }
        }
        ticking()
        invalidate(); onVoice()
    }

    /** A on Allow: Android's question on the bottom screen (the host's), the sheet looks again when it closes */
    private fun askMic(s: Voice.Sheet) {
        askPending = true
        val answer: () -> Unit = {
            askPending = false; myAnswer = null
            if (sheet === s) { val (p, a) = probe(s.mode); run(s.permAnswered(p, a)) }
        }
        myAnswer = answer
        VoiceMic.onAnswer = answer
        DiagLog.event("voice", "mic question asked")
        val ask = Voice.askMic
        if (ask != null) { ask(); return }
        // no host (before Play): the question on this board's own display
        val i = android.content.Intent(context, VoiceAskActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = display?.displayId?.let { android.app.ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
        runCatching { context.startActivity(i, opts) }.onFailure { if (VoiceMic.onAnswer === answer) VoiceMic.onAnswer = null; askPending = false; myAnswer = null }
    }

    /** the page's clock: the ripples, the recognizer's late finish, the fight's end on Allow, the copy's gold fill */
    private val tick = object : Runnable {
        override fun run() {
            val s = sheet ?: return
            val acts = s.tick()
            if (acts.isNotEmpty()) run(acts) else { invalidate(); onVoice(); ticking() }
        }
    }

    private fun ticking() {
        ui.removeCallbacks(tick)
        val s = sheet ?: return
        val ms = when (s.state) {
            Voice.State.LISTENING, Voice.State.STOPPING -> 33L
            Voice.State.RESULT -> if (!s.canAccept() || copyLeftMs() > 0L || copyTotal > 0L) 60L else 0L
            Voice.State.ASK, Voice.State.MIC_OFF -> 500L
            Voice.State.PROBLEM -> if (s.problem == Voice.Problem.MIC_OFF) 500L else 0L
            else -> 0L
        }
        if (ms > 0L) ui.postDelayed(tick, ms)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible && !voiceAsking) stopVoice()
    }

    override fun onDetachedFromWindow() {
        stopVoice()
        super.onDetachedFromWindow()
    }

    // ---- the drawing: the medallion (its ripples follow the voice's level), the words, the box's finish button, the buttons' words

    private class Chip(val key: String, val r: RectF)
    private val chips = ArrayList<Chip>()
    private var btnRect: RectF? = null
    private var medRect: RectF? = null
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** the finish button's word and whether it can be pressed now; null = no button (Android stopped asking) */
    private fun buttonOf(s: Voice.Sheet, fight: Boolean): Pair<String, Boolean>? = when (s.state) {
        Voice.State.ASK, Voice.State.MIC_OFF -> if (fight) "After combat" to false else "Allow" to true
        Voice.State.BLOCKED -> null
        Voice.State.OFFER_ONLINE -> "Online" to true
        Voice.State.LISTENING, Voice.State.STOPPING -> sendWord to false
        Voice.State.RESULT -> sendWord to s.canAccept()
        Voice.State.PROBLEM -> when {
            s.problem == Voice.Problem.PACK && s.canGet -> "Get" to true
            s.problem == Voice.Problem.MIC_OFF -> if (fight) "After combat" to false else "Allow" to true
            else -> "Say again" to true
        }
    }

    /** the buttons' words under the page (each a touch box too) */
    private fun chipsOf(s: Voice.Sheet): List<Pair<String, String>> {
        val back = UiKit.backGlyph()
        return when (s.state) {
            Voice.State.ASK, Voice.State.MIC_OFF, Voice.State.BLOCKED, Voice.State.OFFER_ONLINE -> listOf(back to "Not now")
            Voice.State.LISTENING -> listOf("rev_a" to "Stop", back to "Cancel")
            Voice.State.STOPPING -> listOf(back to "Cancel")
            Voice.State.RESULT -> listOf("rev_x" to "Say again", "rev_y" to "Edit", back to "Cancel")
            Voice.State.PROBLEM -> (if (buttonOf(s, false)?.first == "Say again") emptyList() else listOf("rev_x" to "Say again")) +
                listOf("rev_y" to "Edit", back to "Cancel")
        }
    }

    /** the title over the words */
    private fun titleOf(s: Voice.Sheet): String? = when (s.state) {
        Voice.State.ASK -> "Voice needs the microphone"
        Voice.State.MIC_OFF, Voice.State.BLOCKED -> "Microphone off"
        Voice.State.OFFER_ONLINE -> "Use online voice"
        Voice.State.LISTENING, Voice.State.STOPPING -> "Listening"
        Voice.State.RESULT -> null
        Voice.State.PROBLEM -> s.problem?.title
    }

    private var onlineName: String? = null
    private var onlineAsked = false

    private fun drawVoice(c: Canvas, kb: RectF, s: Voice.Sheet) {
        chips.clear(); btnRect = null
        val now = android.os.SystemClock.uptimeMillis()
        val fight = Voice.inFight()
        val inner = RectF(kb.left + INSET + 10f, kb.top + INSET + 6f, kb.right - INSET - 10f, kb.bottom - INSET - 6f)
        val chipH = 60f
        val body = RectF(inner.left, inner.top, inner.right, inner.bottom - chipH - 8f)
        // ---- the medallion, left
        val r = minOf(body.height() * 0.28f, 104f)
        val mcx = body.left + 34f + r * 1.25f
        val mcy = body.centerY()
        drawMedallion(c, mcx, mcy, r, s, now)
        medRect = RectF(mcx - r * 1.3f, mcy - r * 1.3f, mcx + r * 1.3f, mcy + r * 1.3f)
        // ---- the finish button, right, the cursor on it once there is something to press
        val bw = 270f; val bh = minOf(124f, body.height() * 0.42f)
        val bRect = RectF(body.right - bw, mcy - bh / 2f, body.right, mcy + bh / 2f)
        buttonOf(s, fight)?.let { (word, live) ->
            val listening = s.state == Voice.State.LISTENING || s.state == Voice.State.STOPPING
            val alpha = when { listening -> 90; !live -> 120; else -> 255 }
            val layer = if (alpha < 255) c.saveLayerAlpha(bRect.left - 4f, bRect.top - 4f, bRect.right + 4f, bRect.bottom + 8f, alpha) else -1
            val isFinish = s.state == Voice.State.RESULT || listening
            UiKit.keyCap(c, bRect, word, LOOK, mod = true, glyph = if (live || isFinish) "rev_a" else null, icon = if (isFinish) "enter" else null)
            if (layer >= 0) c.restoreToCount(layer)
            // THE COPY'S GOLD FILL (the box's words going into the add-on's field, ChatStream's pace): along the button's foot
            if (s.state == Voice.State.RESULT) {
                val left = copyLeftMs()
                if (left > copyTotal) copyTotal = left
                if (copyTotal > 0L) {
                    val f = (1f - left.toFloat() / copyTotal).coerceIn(0f, 1f)
                    disc.color = Theme.gold
                    c.drawRoundRect(RectF(bRect.left + 10f, bRect.bottom - 12f, bRect.left + 10f + (bRect.width() - 20f) * f, bRect.bottom - 6f), 3f, 3f, disc)
                    if (left <= 0L) copyTotal = 0L
                }
            }
            if (!listening) UiKit.focus(c, bRect)
            btnRect = bRect
        }
        // ---- the words, centre: the title over them, the words in large type
        val tx0 = mcx + r * 1.25f + 44f
        val tx1 = bRect.left - 34f
        var y = body.top + 12f
        titleOf(s)?.let { t ->
            val bad = s.state == Voice.State.PROBLEM || s.state == Voice.State.MIC_OFF || s.state == Voice.State.BLOCKED
            val p = UiKit.bodyPaint(32f, if (bad) Theme.unusable else Theme.gold)
            val line = android.text.TextUtils.ellipsize(Tr.t(t), android.text.TextPaint(p), tx1 - tx0, android.text.TextUtils.TruncateAt.END).toString()
            c.drawText(line, tx0, y - p.ascent(), p)
            y += -p.ascent() + p.descent() + 14f
        }
        when (s.state) {
            Voice.State.LISTENING, Voice.State.STOPPING, Voice.State.RESULT -> drawWords(c, s, RectF(tx0, y, tx1, body.bottom), now)
            Voice.State.BLOCKED -> sub(c, PATH, tx0, y, tx1, body.bottom)
            Voice.State.OFFER_ONLINE -> {
                if (!onlineAsked) { onlineAsked = true; onlineName = runCatching { VoiceEngine.onlineName(context) }.getOrNull() }
                onlineName?.let { sub(c, it, tx0, y, tx1, body.bottom) }
            }
            else -> {}
        }
        // ---- the buttons' words, along the foot (right to left), each a touch box
        val p = UiKit.bodyPaint(28f, Theme.text)
        var x = inner.right
        val cy = inner.bottom - chipH / 2f
        for ((glyph, word) in chipsOf(s).reversed()) {
            val w = Tr.t(word)
            val right = x
            x -= p.measureText(w)
            c.drawText(w, x, cy - (p.ascent() + p.descent()) / 2f, p)
            x -= 34f
            UiKit.pad(c, glyph, x, cy, 46f)
            x -= 24f
            val key = when (glyph) { "rev_x" -> "PAD3"; "rev_y" -> "PAD4"; "rev_a" -> "PAD1"; else -> "PAD2" }
            chips += Chip(key, RectF(x - 10f, cy - chipH / 2f, right + 10f, cy + chipH / 2f))
            x -= 34f
        }
    }

    /** a dim line under the title (the Settings path, the online recognizer's name), wrapped */
    private fun sub(c: Canvas, text: String, x0: Float, y: Float, x1: Float, bottom: Float) {
        val p = android.text.TextPaint(UiKit.bodyPaint(27f, Theme.dim))
        val t = Tr.t(text)
        val lay = android.text.StaticLayout.Builder.obtain(t, 0, t.length, p, (x1 - x0).toInt().coerceAtLeast(1)).setMaxLines(3)
            .setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        c.save(); c.translate(x0, y); c.clipRect(0f, 0f, x1 - x0, bottom - y); lay.draw(c); c.restore()
    }

    /**
     * The words: Chat's channel prefix in its colour, the box's own text (a message box's), the settled words white, the rest dim, the
     * gold caret after them (blinking while listening). The END stays in view: an older start goes behind "…".
     */
    private fun drawWords(c: Canvas, s: Voice.Sheet, area: RectF, now: Long) {
        val tp = android.text.TextPaint(UiKit.bodyPaint(46f, Color.WHITE))
        val sb = android.text.SpannableStringBuilder()
        fun put(t: String, colour: Int) {
            if (t.isEmpty()) return
            val a = sb.length; sb.append(t)
            sb.setSpan(android.text.style.ForegroundColorSpan(colour), a, sb.length, 0)
        }
        voicePrefix()?.let { (t, col) -> put(t, col) }
        val listening = s.state != Voice.State.RESULT
        val heard = s.heard
        val stable = if (listening) s.stableText else heard
        if (s.append && s.before.isNotBlank()) put(if (heard.isEmpty() || s.before.endsWith(" ")) s.before else s.before + " ", Color.argb(200, 255, 255, 255))
        if (stable.isNotEmpty() && heard.startsWith(stable)) { put(stable, Color.WHITE); put(heard.substring(stable.length), Theme.dim) }
        else put(heard, if (listening) Theme.dim else Color.WHITE)
        val width = area.width().toInt().coerceAtLeast(1)
        val maxLines = (area.height() / tp.fontSpacing).toInt().coerceIn(1, 3)
        fun layout(t: CharSequence) = android.text.StaticLayout.Builder.obtain(t, 0, t.length, tp, width).setLineSpacing(2f, 1f).build()
        var shown: CharSequence = sb
        var lay = layout(shown)
        if (lay.lineCount > maxLines) {
            var lo = 0; var hi = sb.length
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                val t = android.text.SpannableStringBuilder("…").append(sb.subSequence(sb.length - mid, sb.length))
                if (layout(t).lineCount <= maxLines) lo = mid else hi = mid - 1
            }
            shown = android.text.SpannableStringBuilder("…").append(sb.subSequence(sb.length - lo, sb.length))
            lay = layout(shown)
        }
        c.save(); c.translate(area.left, area.top); lay.draw(c)
        // the caret: gold, after the words; it blinks while listening, steady on the result
        if (!listening || (now / 530L) % 2L == 0L) {
            val end = shown.length
            val line = lay.getLineForOffset(end)
            val cx = if (end == 0) 0f else lay.getPrimaryHorizontal(end) + 4f
            disc.color = Theme.gold
            c.drawRect(cx, lay.getLineTop(line) + 8f, cx + 4f, lay.getLineBottom(line) - 6f, disc)
        }
        c.restore()
    }

    /** the kit medallion (the radial slot plate, a dark disc, the gold rim) with the microphone; its ripples follow the level */
    private fun drawMedallion(c: Canvas, cx: Float, cy: Float, r: Float, s: Voice.Sheet, now: Long) {
        val listening = s.state == Voice.State.LISTENING || s.state == Voice.State.STOPPING
        val noMic = s.state == Voice.State.ASK || s.state == Voice.State.MIC_OFF || s.state == Voice.State.BLOCKED ||
            (s.state == Voice.State.PROBLEM && s.problem == Voice.Problem.MIC_OFF)
        val gold = Theme.gold
        if (listening) {
            val lv = s.level
            for (i in 0 until 3) {
                val ph = ((now % RIPPLE_MS).toFloat() / RIPPLE_MS + i / 3f) % 1f
                val rr = r * (1.04f + ph * (0.22f + 0.50f * lv))
                ring.color = gold
                ring.alpha = ((1f - ph) * (50f + 190f * lv)).toInt().coerceIn(0, 255)
                ring.strokeWidth = 2.5f + 6f * lv * (1f - ph)
                c.drawCircle(cx, cy, rr, ring)
            }
        }
        val side = r / 0.357f
        UiKit.tex("radial_place")?.let { c.drawBitmap(it, null, RectF(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f), plate) }
        disc.color = VoiceUi.DISC
        c.drawCircle(cx, cy, r, disc)
        if (listening) {
            disc.color = gold; disc.alpha = (26 + 90 * s.level).toInt().coerceIn(0, 255)
            c.drawCircle(cx, cy, r * (0.62f + 0.30f * s.level), disc)
            disc.alpha = 255
        }
        ring.color = gold; ring.alpha = 255; ring.strokeWidth = maxOf(2f, r * 0.07f)
        c.drawCircle(cx, cy, r + ring.strokeWidth / 2f, ring)
        VoiceUi.mic(c, cx, cy + r * 0.04f, r * 1.05f, if (listening || s.state == Voice.State.RESULT) gold else Theme.dim, crossed = noMic)
    }

    private fun voiceTouch(e: MotionEvent) {
        if (e.actionMasked != MotionEvent.ACTION_UP) return
        val key = when {
            btnRect?.contains(e.x, e.y) == true -> "PAD1"
            // the medallion: X's say it again (and A's stop while listening)
            medRect?.contains(e.x, e.y) == true -> if (sheet?.state == Voice.State.LISTENING) "PAD1" else "PAD3"
            else -> chips.firstOrNull { it.r.contains(e.x, e.y) }?.key
        } ?: return
        voicePress(key)
    }

    /** a finger the host routes here itself (PadTabPage draws this board on its own canvas): the voice page's touch */
    fun voiceTouchAt(e: MotionEvent): Boolean { if (sheet == null) return false; voiceTouch(e); return true }

    companion object {
        /** the keyboard's whole height: the rows, the hints under them, the board's margins */
        fun fullHeight(): Int {
            val rows = ChatKeys.Layout.DEFAULT.pages[0].size
            // the rows and the board's margins (the legend under them is gone: LATER 27)
            return (INSET + rows * KEY_H + (rows - 1) * GAP + INSET + 4f).toInt()
        }

        /** the user's pick on SHEET_keyboard_v2.png: 1 = dark keycaps */
        const val LOOK = 1
        const val KEY_H = 92f
        const val GAP = 8f
        const val INSET = 16f
        /** the key pop-up: how long A's shows (a finger's shows while held) */
        const val POP_SHOW_MS = 160L
        /** one ripple's way out from the medallion */
        const val RIPPLE_MS = 1400L
        /** where Android keeps the microphone once it stopped asking (the sheet shows it; DS Forever never opens it) */
        const val PATH = "Android Settings > Apps > DS Forever > Permissions > Microphone"
    }
}
