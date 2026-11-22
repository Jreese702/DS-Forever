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
    /**
     * THE BOARD'S SCALE (#557, the Ayaneo Pocket DS: "the popup keyboard its so large it doesn't fit on the screen"): its sizes are the
     * Thor's (1240 x 1080 bottom screen), scaled down to a smaller screen ([scaleFor], the kit's rule: min(1, w / 1240, h / 1080)).
     * Null = the screen it is on ([scaleOf]: its window once attached); a board drawn on another's canvas says it (PadTabPage: its
     * host's; a picture of the board drawn at the Thor's size: 1).
     */
    var scaleOverride: Float? = null
        set(v) { if (field != v) { field = v; requestLayout(); invalidate() } }
    /** the scale the board's sizes take now */
    val scale: Float get() = scaleOverride ?: scaleOf(this)
    /** the board's whole height at its [scale] (a host placing the board before it is measured) */
    fun boardHeight(): Int = fullHeight(scale)
    private var measuredScale = 1f

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
        measuredScale = scale
        setMeasuredDimension(MeasureSpec.getSize(wSpec), resolveSize(fullHeight(measuredScale), hSpec))
    }

    private val board = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        c.drawColor(Theme.ink)
        // #557: the screen's size known only now (the first measure ran before the window had its size): measured again at its scale
        val k = scale
        if (scaleOverride == null && isAttachedToWindow && kotlin.math.abs(k - measuredScale) > 0.001f) requestLayout()
        // drawn at the FULL height even while the room gives it less: sliding in, its top edge carries the keys up
        // from below (the room clips the rest), so the board slides and is never squeezed
        val kb = RectF(6f, 0f, width - 6f, (if (fillHeight > 0) fillHeight else fullHeight(k)) - 4f)
        // look 1, the user's pick: the keys on a black board with a thin bronze-brown edge (UI Art's sample)
        board.style = Paint.Style.FILL; board.color = Color.rgb(6, 6, 6)
        c.drawRoundRect(kb, 14f, 14f, board)
        board.style = Paint.Style.STROKE; board.strokeWidth = 2f; board.color = Color.rgb(60, 52, 40)
        c.drawRoundRect(kb, 14f, 14f, board)
        places.clear()
        // the voice page in the Thor's frame, scaled to the board (its touch boxes then put on the board as drawn: [voiceHitsScaled])
        sheet?.let {
            c.save(); c.scale(k, k)
            drawVoice(c, RectF(kb.left / k, kb.top / k, kb.right / k, kb.bottom / k), it)
            c.restore()
            if (k != 1f) voiceHitsScaled(k)
            return
        }
        val rows = keys.layoutRows()
        val g = keyMetrics(kb.width(), kb.height(), rows.size, k)
        val unit = g[0]; val keyH = g[1]; val inset = g[2]; val gap = g[3]
        var y = kb.top + inset
        for ((ri, row) in rows.withIndex()) {
            var x = kb.left + inset + row.lead * (unit + gap)          // the A-L row half a key in
            for ((ci, cell) in row.cells.withIndex()) {
                val w = unit * cell.w + gap * (cell.w - 1f)
                val r = RectF(x, y, x + w, y + keyH)
                drawKey(c, r, cell.key, down = ri == popRow && ci == popCol && popShown(android.os.SystemClock.uptimeMillis()))
                places += Triple(ri, ci, r)
                if (ri == keys.row && ci == keys.col) UiKit.focus(c, r)       // the gold box: the cursor
                x += w + gap
            }
            y += keyH + gap
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
                UiKit.keyCap(c, r, sendWord, LOOK, mod = true, glyph = Glyphs.start(), icon = "enter")
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

    /** what listens: offline voice ([WhisperEngine]) when its model is on disk, else Android's recognizer ([VoiceEngine]) */
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
    val voiceAnimating: Boolean get() = sheet?.state.let { it == Voice.State.LISTENING || it == Voice.State.STOPPING || it == Voice.State.WORKING }

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    /** every page stops when the host goes (Voice.stopAll), but one waiting on Android's microphone question (it holds no microphone) */
    private val stopHook: () -> Unit = { if (!voiceAsking) stopVoice() }
    private var askPending = false
    /**
     * Android's question is in FRONT of this page (the host hid its window for it, [Voice.askMic] said so). Its buttons are that
     * question's: the app's own reader still sees every press (a root getevent, whatever has the focus) and Chat's direct route is the
     * room's, not the window's, so A on Android's "Allow" also reached this page (still on Allow: a second question, the window shown
     * again over it) and B there cancelled the page. While it is up, the page takes no button.
     */
    private var askHidden = false
    /**
     * Android's microphone question is up for this page: the host's window is hidden meanwhile (openOnBottom), so the page and its host
     * must not take that hiding as "the box went away" (the page waits for the answer, then listens)
     */
    val voiceAsking: Boolean get() = askPending && sheet != null

    private fun listen(): VoiceListen = engine ?: object : VoiceListen {
        private val e by lazy { VoiceEngine(context) }
        private val w by lazy { WhisperEngine(context) }
        /** the one that listens now: offline voice when it can ([Voice.whisperModel], as the sheet's [Voice.avail] decided) */
        private var cur: VoiceListen? = null
        private fun android() = object : VoiceListen {
            override fun start(online: Boolean, lang: String, hints: List<String>, sink: VoiceEngine.Sink) = e.start(online, lang, hints, sink)
            override fun stop() = e.stop()
            override fun cancel() = e.cancel()
            override fun getPack(lang: String) = e.getPack(lang)
        }
        override fun start(online: Boolean, lang: String, hints: List<String>, sink: VoiceEngine.Sink): Boolean {
            cur?.cancel()
            val pick: VoiceListen = if (sheet?.offline == true && Voice.whisperModel(Tr.lang(), WhisperEngine.on(), Settings.voiceLanguage) != null) w else android()
            cur = pick
            return pick.start(online, lang, hints, sink)
        }
        override fun stop() { cur?.stop() }
        override fun cancel() { cur?.cancel() }
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
        // the model list's cursor (if it shows): the small one of the kind the app's language wants
        s.model = sheetModel()
        sheet = s; voiceFromKeys = fromKeys; copyTotal = 0L
        Voice.register(stopHook)
        val (perm, avail) = probe(mode)
        DiagLog.event("voice", s.logLine("open") + " perm=${perm.name.lowercase()} avail=${avail.name.lowercase()}")
        // offline voice's model offered: a download already running (Settings > Add-ons, an earlier page) shows on its row ([run]'s end),
        // and the page listens when one is in
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
        modelWatch?.let { VoiceModels.offChange(it) }; modelWatch = null
        askPending = false; askHidden = false
        Voice.unregister(stopHook)
        ui.removeCallbacks(tick); ui.removeCallbacks(askLost)
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
        override fun working() { sheet?.let { s -> s.working(); ticking(); invalidate(); onVoice() } }
        // memory work #56 (D4): the voice model was let go (a loading screen, the world); the words wait for its load
        override fun loading() { voiceLoading = true; invalidate() }
    }
    /** the words wait for the voice model's load ("Loading voice" over them while working) */
    private var voiceLoading = false

    /** the recognizer's events, for the off-device renders and tests (as Android would call them) */
    fun voiceSink(): VoiceEngine.Sink = sink

    private fun voicePress(button: String): Boolean {
        val s = sheet ?: return false
        if (askPending && askHidden) return true            // Android's question has the buttons while it is up ([askHidden])
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
                    voiceLoading = false
                    val lang = VoiceLanguage.androidTag(Settings.voiceLanguage, Tr.lang())
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
                Voice.Act.GetPack -> { val ok = listen().getPack(VoiceLanguage.androidTag(Settings.voiceLanguage, Tr.lang())); DiagLog.event("voice", "pack download asked ok=$ok") }
                is Voice.Act.GetModel -> getModel(s, a.model)
                Voice.Act.Pass, Voice.Act.Glow -> {}
            }
        }
        // a page that came to offer offline voice's model (after the microphone question too) follows its download
        // (the rows' busy set read once as it arrives; later the watch keeps it, and a press adds its own at once)
        sheet?.let { if (it.state == Voice.State.PROBLEM && it.problem == Voice.Problem.NEED_MODEL && modelWatch == null) { watchModel(it); it.busy = busyModels() } }
        ticking()
        invalidate(); onVoice()
    }

    // ---- OFFLINE VOICE'S MODEL from the sheet (the player's pick from its list of the four, the user 2026-11-16: "The voice menu should
    // have all of the options not just the 60 mb"): its download, its percentage on its row, and listening at once when one is in
    // (unless the sheet has closed)
    private var modelWatch: (() -> Unit)? = null
    /** where the list's cursor starts: the small one of the kind the app's language wants */
    private fun sheetModel(): VoiceModels.Model = Voice.wantedModel(Tr.lang(), Settings.voiceLanguage)
    /** the files downloading or being checked now (Settings > Add-ons' too) */
    private fun busyModels(): Set<VoiceModels.Model> = VoiceModels.Model.values().filterTo(HashSet()) { m ->
        modelState(m).let { it is VoiceModels.State.Downloading || it == VoiceModels.State.Checking }
    }

    private fun getModel(s: Voice.Sheet, m: VoiceModels.Model) {
        VoiceModels.init(context)
        watchModel(s)
        s.busy = s.busy + m
        DiagLog.event("voice", "model ${m.id} download asked from the voice page")
        VoiceModels.download(m)
    }

    private fun watchModel(s: Voice.Sheet) {
        if (modelWatch != null) return
        val w: () -> Unit = {
            ui.post {
                if (sheet === s) {
                    // any of the four in: offline voice listens ([Voice.whisperModel] picks which and in what language)
                    // #477: one that can listen in the Voice language (English alone cannot for Russian: the list stays)
                    val pa = if (VoiceModels.Model.values().any { modelState(it) == VoiceModels.State.Ready }) probe(s.mode) else null
                    if (pa != null && pa.second != Voice.Avail.NEED_MODEL) run(s.modelReady(pa.first, pa.second))
                    else { s.busy = busyModels(); invalidate(); onVoice() }
                }
            }
        }
        modelWatch = w
        VoiceModels.onChange(w)
    }

    /** A on Allow: Android's question on the bottom screen (the host's), the sheet looks again when it closes */
    private fun askMic(s: Voice.Sheet) {
        askPending = true
        val answer: () -> Unit = {
            if (sheet === s) {
                askPending = false; askHidden = false; myAnswer = null; ui.removeCallbacks(askLost)
                val (p, a) = probe(s.mode); run(s.permAnswered(p, a))
            }
        }
        myAnswer = answer
        VoiceMic.onAnswer = answer
        DiagLog.event("voice", "mic question asked")
        val ask = Voice.askMic
        if (ask != null) {
            askHidden = ask()
            if (!askHidden) DiagLog.event("voice", "mic question not on the bottom screen")
            return
        }
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
            Voice.State.LISTENING, Voice.State.STOPPING, Voice.State.WORKING -> 33L
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
        // the window is back: Android's question has closed and its answer follows at once ([VoiceMic.onAnswer], posted right after);
        // an answer that never comes (the question's activity gone without one) must not leave the page deaf to every button
        if (isVisible && voiceAsking && askHidden) { ui.removeCallbacks(askLost); ui.postDelayed(askLost, ASK_LOST_MS) }
    }

    /** the window came back and no answer followed: the page looks again itself (the permission as it is now) */
    private val askLost = Runnable {
        val s = sheet ?: return@Runnable
        if (!askPending) return@Runnable
        askPending = false; askHidden = false
        myAnswer?.let { if (VoiceMic.onAnswer === it) VoiceMic.onAnswer = null }
        myAnswer = null
        DiagLog.event("voice", "mic question closed without an answer")
        val (p, a) = probe(s.mode); run(s.permAnswered(p, a))
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
        Voice.State.ASK, Voice.State.MIC_OFF -> if (fight) "After the fight" to false else "Allow" to true
        Voice.State.BLOCKED -> null
        Voice.State.OFFER_ONLINE -> "Online" to true
        Voice.State.LISTENING, Voice.State.STOPPING, Voice.State.WORKING -> sendWord to false
        Voice.State.RESULT -> sendWord to s.canAccept()
        Voice.State.PROBLEM -> when {
            s.problem == Voice.Problem.NEED_MODEL -> null           // the list of the four takes the button's place (A = the highlighted row)
            s.problem == Voice.Problem.NO_SERVICE -> null           // nothing can listen: no button (Y = keyboard, B = back)
            s.problem == Voice.Problem.PACK && s.canGet -> "Get" to true
            s.problem == Voice.Problem.MIC_OFF -> if (fight) "After the fight" to false else "Allow" to true
            else -> "Say again" to true
        }
    }

    /** the buttons' words under the page (each a touch box too) */
    private fun chipsOf(s: Voice.Sheet): List<Pair<String, String>> {
        val back = UiKit.backGlyph()
        return when (s.state) {
            Voice.State.ASK, Voice.State.MIC_OFF, Voice.State.BLOCKED, Voice.State.OFFER_ONLINE -> listOf(back to "Not now")
            Voice.State.LISTENING -> listOf("rev_a" to "Stop", back to "Cancel")
            Voice.State.STOPPING, Voice.State.WORKING -> listOf(back to "Cancel")
            Voice.State.RESULT -> listOf("rev_x" to "Say again", "rev_y" to "Edit", back to "Cancel")
            Voice.State.PROBLEM -> (if (s.problem == Voice.Problem.NEED_MODEL) listOf("rev_a" to "Download")
                else if (s.problem == Voice.Problem.NO_SERVICE || buttonOf(s, false)?.first == "Say again") emptyList()
                else listOf("rev_x" to "Say again")) +
                listOf("rev_y" to "Edit", back to "Cancel")
        }
    }

    /** the title over the words */
    private fun titleOf(s: Voice.Sheet): String? = when (s.state) {
        Voice.State.ASK -> "Voice needs the microphone"
        Voice.State.MIC_OFF, Voice.State.BLOCKED -> "Microphone off"
        Voice.State.OFFER_ONLINE -> "Use online voice"
        Voice.State.LISTENING, Voice.State.STOPPING -> "Listening"
        Voice.State.WORKING -> if (voiceLoading) "Loading voice" else "Working"
        Voice.State.RESULT -> null
        Voice.State.PROBLEM -> s.problem?.title
    }

    /** the off-device renders: offline voice's model state as given (null = VoiceModels') */
    var modelStateProbe: ((VoiceModels.Model) -> VoiceModels.State)? = null
    private fun modelState(m: VoiceModels.Model): VoiceModels.State = modelStateProbe?.invoke(m) ?: VoiceModels.state(m)

    private var onlineName: String? = null
    private var onlineAsked = false
    /** the off-device renders: the online recognizer's app name as given (null = ask Android) */
    var onlineNameProbe: (() -> String?)? = null
    private fun onlineApp(): String? {
        onlineNameProbe?.let { return it() }
        if (!onlineAsked) { onlineAsked = true; onlineName = runCatching { VoiceEngine.onlineName(context) }.getOrNull() }
        return onlineName
    }

    /** NEED_MODEL's rows as last drawn (a finger's pick) */
    private val modelRows = ArrayList<Pair<VoiceModels.Model, RectF>>()

    private fun drawVoice(c: Canvas, kb: RectF, s: Voice.Sheet) {
        chips.clear(); btnRect = null; modelRows.clear()
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
            val listening = s.state == Voice.State.LISTENING || s.state == Voice.State.STOPPING || s.state == Voice.State.WORKING
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
        val pickModel = s.state == Voice.State.PROBLEM && s.problem == Voice.Problem.NEED_MODEL
        // the model list: its rows' highlight starts clear of the medallion's rim (the rows' words, and the title, 84 px in from there)
        val tx0 = if (pickModel) maxOf(mcx + r * 1.25f + 44f, mcx + r * 1.3f + 96f) else mcx + r * 1.25f + 44f
        val tx1 = if (pickModel) body.right else bRect.left - 34f
        // the words start level with the medallion's top (the board fills the room in Chat: the page stays balanced, never hugging the top)
        var y = maxOf(body.top + 12f, mcy - r * 1.3f)
        titleOf(s)?.let { t ->
            val bad = s.state == Voice.State.PROBLEM || s.state == Voice.State.MIC_OFF || s.state == Voice.State.BLOCKED
            val p = UiKit.bodyPaint(32f, if (bad) Theme.unusable else Theme.gold)
            val tt = Tr.t(t)
            val tp = android.text.TextPaint(p)
            if (tp.measureText(tt) <= tx1 - tx0) {
                c.drawText(tt, tx0, y - p.ascent(), p)
                y += -p.ascent() + p.descent() + 14f
            } else {
                // a long title (no speech services; Spanish / French): two lines, never cut to "Voice typing needs..."
                val lay = android.text.StaticLayout.Builder.obtain(tt, 0, tt.length, tp, (tx1 - tx0).toInt().coerceAtLeast(1)).setMaxLines(2)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END).build()
                c.save(); c.translate(tx0, y); lay.draw(c); c.restore()
                y += lay.height + 14f
            }
        }
        when (s.state) {
            Voice.State.LISTENING, Voice.State.STOPPING, Voice.State.WORKING, Voice.State.RESULT -> drawWords(c, s, RectF(tx0, y, tx1, body.bottom), now)
            Voice.State.BLOCKED -> sub(c, PATH, tx0, y, tx1, body.bottom)
            Voice.State.OFFER_ONLINE -> {
                onlineApp()?.let { sub(c, it, tx0, y, tx1, body.bottom) }
            }
            // offline voice's model: the four as a short list (Add-ons' order), each row's download state at its right
            Voice.State.PROBLEM -> if (pickModel) drawModels(c, s, RectF(tx0, y, tx1, body.bottom))
            else -> {}
        }
        // ---- THE PRIVACY LINE (the design: "Online ... The privacy line names that app"): while the ONLINE recognizer listens, the app
        // that hears the voice is named at the foot's left (only that service can keep EXTRA_PREFER_OFFLINE, so nothing says "this device")
        if (s.mode == Voice.Mode.ONLINE && !s.offline && (s.state == Voice.State.LISTENING || s.state == Voice.State.STOPPING)) onlineApp()?.let { name ->
            val np = UiKit.bodyPaint(26f, Theme.dim)
            val w = (inner.width() * 0.45f).coerceAtLeast(1f)
            val line = android.text.TextUtils.ellipsize(name, android.text.TextPaint(np), w, android.text.TextUtils.TruncateAt.END).toString()
            c.drawText(line, inner.left + 4f, inner.bottom - chipH / 2f - (np.ascent() + np.descent()) / 2f, np)
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

    /**
     * NEED_MODEL's list: the four models as the app's short choice lists draw them ([UiKit.menuRow]: the highlight on the cursor's row, its
     * words gold), "English · 60 MB" ...; a row whose file is doing something says so at its right ("Downloading 42%", "Download failed: ...")
     */
    private fun drawModels(c: Canvas, s: Voice.Sheet, area: RectF) {
        val all = VoiceModels.Model.values()
        val rowH = minOf(MODEL_ROW_H, area.height() / all.size)
        // the label stands just in from the title's words (menuRow's words sit 100 px in from its left)
        val left = area.left - 84f
        for ((i, m) in all.withIndex()) {
            val r = RectF(left, area.top + i * rowH, area.right, area.top + (i + 1) * rowH)
            val st = modelState(m)
            val busy = st is VoiceModels.State.Downloading || st == VoiceModels.State.Checking
            if (busy) postInvalidateDelayed(250)
            UiKit.menuRow(c, r, m.choice, m == s.model, size = 30f)
            if (st != VoiceModels.State.None) {
                val p = UiKit.bodyPaint(26f, if (st is VoiceModels.State.Failed || st is VoiceModels.State.NoSpace) Theme.unusable else Theme.dim)
                val w = Tr.t(VoiceModels.line(m, st))
                val room = r.width() * 0.45f
                val t = android.text.TextUtils.ellipsize(w, android.text.TextPaint(p), room, android.text.TextUtils.TruncateAt.END).toString()
                c.drawText(t, r.right - 22f - p.measureText(t), r.centerY() - (p.ascent() + p.descent()) / 2f, p)
            }
            modelRows += m to r
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
        val listening = s.state == Voice.State.LISTENING || s.state == Voice.State.STOPPING || s.state == Voice.State.WORKING
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
        val ex = e.x; val ey = e.y
        // a model row (NEED_MODEL's list): that one is picked and its download asked, as A on it
        modelRows.firstOrNull { it.second.contains(ex, ey) }?.let { (m, _) -> sheet?.model = m; voicePress("PAD1"); return }
        val key = when {
            btnRect?.contains(ex, ey) == true -> "PAD1"
            // the medallion: X's say it again (and A's stop while listening)
            medRect?.contains(ex, ey) == true -> if (sheet?.state == Voice.State.LISTENING) "PAD1" else "PAD3"
            else -> chips.firstOrNull { it.r.contains(ex, ey) }?.key
        } ?: return
        voicePress(key)
    }

    /** #557: the voice page drawn in the Thor's frame at scale [k]: its touch boxes put on the board as drawn (the view's own pixels) */
    private fun voiceHitsScaled(k: Float) {
        fun RectF.onBoard() = set(left * k, top * k, right * k, bottom * k)
        chips.forEach { it.r.onBoard() }
        btnRect?.onBoard(); medRect?.onBoard()
        modelRows.forEach { it.second.onBoard() }
    }

    /** a finger the host routes here itself (PadTabPage draws this board on its own canvas): the voice page's touch */
    fun voiceTouchAt(e: MotionEvent): Boolean { if (sheet == null) return false; voiceTouch(e); return true }

    companion object {
        /**
         * the keyboard's whole height at scale [k] (1 = the Thor's bottom screen; [scaleFor]): the rows and the board's margins. A host
         * that places the board itself asks the board's own ([boardHeight], or [fullHeight] of a view on its screen)
         */
        fun fullHeight(k: Float = 1f): Int {
            val rows = ChatKeys.Layout.DEFAULT.pages[0].size
            // the rows and the board's margins (the legend under them is gone: LATER 27)
            return ((INSET + rows * KEY_H + (rows - 1) * GAP + INSET) * k + 4f).toInt()
        }
        /** the board's whole height on the screen [v] is on */
        fun fullHeight(v: View): Int = fullHeight(scaleOf(v))

        /** the Thor's bottom screen, the board's sizes' own frame */
        const val REF_W = 1240f
        const val REF_H = 1080f
        /** never smaller than this (a window far smaller than any bottom screen, before its size is known) */
        const val MIN_SCALE = 0.4f

        /** a screen of [w] x [h]: the kit's rule, min(1, w / 1240, h / 1080) (the Pocket DS's 1024 x 768: 0.71); unknown = 1 */
        fun scaleFor(w: Float, h: Float): Float =
            if (w <= 0f || h <= 0f) 1f else minOf(1f, w / REF_W, h / REF_H).coerceAtLeast(MIN_SCALE)

        /**
         * the scale for the screen [v] is on: its window's root once laid out, else (attached, the window's size not known yet) the
         * display its context shows; a view in no window = 1 (a board drawn elsewhere says its own: [scaleOverride])
         */
        fun scaleOf(v: View): Float {
            if (!v.isAttachedToWindow) return 1f
            val root = v.rootView
            if (root != null && root !== v && root.width > 0 && root.height > 0) return scaleFor(root.width.toFloat(), root.height.toFloat())
            val m = runCatching { v.resources.displayMetrics }.getOrNull() ?: return 1f
            return scaleFor(m.widthPixels.toFloat(), m.heightPixels.toFloat())
        }

        /**
         * the keys' sizes on a board [w] x [h] of [rows] rows at scale [k]: [unit (a letter key's width), key height, inset, gap]. The
         * keys fill the board's height (the Chat room's input screen gives it the room: ~120 px keys on the Thor) - never taller than
         * the board (#557: the old floor of 92 px ran the Send row off a 768 px screen)
         */
        fun keyMetrics(w: Float, h: Float, rows: Int, k: Float): FloatArray {
            val inset = INSET * k; val gap = GAP * k
            val unit = (w - inset * 2 - 9 * gap) / 10f
            val keyH = ((h - inset * 2 - (rows - 1) * gap) / rows.coerceAtLeast(1)).coerceAtLeast(8f)
            return floatArrayOf(unit, keyH, inset, gap)
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
        /** one row of NEED_MODEL's list of the four (less when the board is shorter) */
        const val MODEL_ROW_H = 84f
        /** the window back after Android's microphone question: its answer is due by then ([askLost]) */
        const val ASK_LOST_MS = 1500L
        /** where Android keeps the microphone once it stopped asking (the sheet shows it; DS Forever never opens it) */
        const val PATH = "Android Settings > Apps > DS Forever > Permissions > Microphone"
    }
}
