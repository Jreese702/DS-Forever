package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject

/**
 * THE CONTEXT RADIAL ON SCREEN (the user, 2026-11-07: "When targeting something while not in the combat state, I want the Y glyph to
 * appear over them for the inspect menu. Instead of that menu opening a drop down I want to design a radial for it"; picks: the BOTTOM
 * screen, TAP Y / aim / A picks, the 8 most used + More; then "I want to use these context radicals for everything, no more drop down
 * menus in any of those places, including when selecting more make another radial"). The pause wheel's look (UiKit.pauseRadial: the
 * game's GamepadRadial, Home's disc rising into the hub) with the game's own menu on its slices ([TargetRadial]), the name as its title,
 * the context icons mixed from game art ([MenuIcons]).
 *
 * WHERE IT OPENS (one view, three openers, each with its own [Input]):
 *  - the TARGET's menu: a bare Y with a target out of the controller's grow (PadMap m101 / m102) reads it (Quests q51, `pmenu` src
 *    "radial"); that document opens the wheel. [Input.HOLD]: it holds the guest "radial" and reads the controller itself.
 *  - Home's PORTRAIT menu (the map module's): [Input.READ]: the map module already holds the controller; the wheel reads it, the map
 *    ignores its keys meanwhile.
 *  - Party mode's MEMBER menu (the PartyPad's Y, a long press on a cell): [Input.KEYS]: the PartyPad's own presses come in by [key] (a
 *    hold of ours would end Party mode); a finger works too.
 *
 * INPUT: either stick (every opener; KEYS reads only the sticks itself) or the D-pad (Left / Right round the ring, Up the top, Down the bottom) aims; A picks; B goes back a
 * wheel, and from the first closes; Y or the hub closes. MORE and a SUB-MENU open the next wheel ([TargetRadial.Page]).
 *
 * THE PET'S AUTOCAST (Quests q62; the user, 2026-11-16: "hunters need to be able to visually see when their pets abilities are active
 * like growl, and a way to turn it off from those radials"): on the pet's wheel (Party mode's pet slot, the target wheel on your pet) an
 * ability that may cast itself wears the game's autocast look ([UiKit.autoCastRing]: the corners; the marching shine while it does), its
 * state live from [PetAuto]. The press mirrors the game's pet bar, whose left-click casts and right-click toggles autocast: A picks (casts
 * it, as every pick), X on the pointed ability toggles it and the wheel stays open (the X glyph shows at its other corner), and a finger's
 * long press ([LONG_MS], the class wheel's) toggles it too. X rather than a held A: A acts on its press here (every opener: the PartyPad
 * delivers presses only), so a hold could only be told after a cast had gone out; X is the one face button no wheel uses, the secondary
 * action as in the Macros room and Bags. A toggle the add-on cannot do now (a fight changed the pet: no cell names its spell) says
 * "Not in combat"; a pet gone or dead "Not available here".
 *
 * A PICK ([open]'s onDo): a menu entry runs as the opener's menu ran it (digit taps or a secure cell). One with a question (Abandon
 * Pet, Promote, Uninvite: its `cf`) opens a REAL Yes / No pop-up over the wheel (the user on test 1502: "this needs to have a real yes no
 * buttons pop up and a should not jump on that prompt"): A or a touch on Yes does it, B or a touch on No goes back to the wheel; the
 * pet's Rename (Quests q69) opens the app's keyboard pop-up with the pet's name, Done (Start, or the board's Done) renames, B cancels.
 * While either is up [PopupHold.wheel] holds every pad button from the game (whoever opened the wheel: with Party mode off nothing else
 * held the pad, and the A jumped), and the wheel reads the presses itself on every opener (KEYS too: the PartyPad gets none meanwhile).
 */
class TargetRadialView(
    context: Context,
    private val strip: Strip,
    private val questsPad: () -> JSONObject?,
    private val guestTap: (List<Pair<String, Int>>?) -> Boolean,
) : View(context) {
    enum class Input { HOLD, READ, KEYS }

    var onSlide: (Float) -> Unit = {}
    var onClose: (Float) -> Unit = { onSlide(0f) }
    var barsBottom: (() -> Float)? = null
    /** the target's picks (the host's taps; Whisper opens the Chat room): the TARGET opener's */
    var onDo: (PlayerMenu.Do, String) -> Unit = { _, _ -> }

    private var input = Input.HOLD
    /** who opened it: "target" / "portrait" / "party" (the host routes `pmenu` documents by it) */
    var opener = ""
        private set
    private var doAct: (PlayerMenu.Do, String) -> Unit = { _, _ -> }
    private var closed: () -> Unit = {}
    private var doc: JSONObject? = null
    private var items: List<PlayerMenu.Entry> = emptyList()
    private var name = ""
    private var tell = ""          // q66: whom a Whisper goes to (TargetRadial.tellName: Name-Realm for another realm), else [name]
    private val pages = ArrayList<TargetRadial.Page>()
    private var slices: List<TargetRadial.Slice?> = emptyList()
    private var pointed = -1
    private var openedAt = 0L
    private var reader: PadInput? = null
    private var held = 0
    private var seeded = false
    private var aimStick = -1
    private val mag = DoubleArray(2)
    private var line: String? = null
    private var lineAt = 0L
    /** the Yes / No pop-up's slice (an entry with `cf`), while it is up */
    private var asking: TargetRadial.Slice? = null
    private val yesBox = RectF()
    private val noBox = RectF()
    /** the rename pop-up (Quests q69): the keyboard the host lends ([kb]), the name being typed, the add-on's borrowed field */
    var kb: ChatKeyboard? = null
    /** the rename's board, for the host to lay over the wheel: no voice (a pet's name, as the macros' names: VoiceBoardsTest) */
    fun makeKeyboard(): ChatKeyboard = ChatKeyboard(context, swap = false).apply { visibility = GONE }.also { kb = it }
    /** the spaced taps a typed field needs (the host's TapBridge.tapsSpaced on the game's pad) */
    var tapSpaced: (List<Pair<String, Int>>, Long) -> Boolean = { _, _ -> false }
    private var renaming = false
    private var renameText = ""
    private var renameLine: String? = null
    private val renameStream by lazy {
        ChatStream({ cells, gap -> tapSpaced(cells, gap) }, field = true, maxLen = TargetRadial.RENAME_MAX).apply {
            onEvent = { ev -> post {
                when (ev) {
                    is ChatStream.Event.Sent -> if (renaming) { Log.i(TAG, "context radial: renamed"); endRename(); close("renamed") }
                    is ChatStream.Event.Refused -> if (renaming) { renameLine = ev.why.replaceFirstChar { it.uppercase() }; invalidate() }
                    else -> {}
                }
            } }
        }
    }
    private val renameTick = object : Runnable {
        override fun run() { renameStream.tick(); if (renameStream.sending && renaming) postDelayed(this, 500L) }
    }
    /** a pop-up of the wheel's is up (Yes / No, or the rename keyboard) */
    val popUp get() = asking != null || renaming
    private var lastActAt = 0L
    private var slidT = 0f
    private var holdTry = 0
    private var freeTry = 0
    /** when the open wheel's menu document came (uptime ms): a PetAuto document after it says what changed since */
    private var filledAt = 0L
    /** a finger's long press on slice [downSlice] toggles its autocast (q62) */
    private var downSlice = -1
    private var longDone = false
    private val longPress = Runnable { val i = downSlice; if (i >= 0 && isOpen) { longDone = true; toggle(i) } }
    @Volatile private var heldOk = false
    @Volatile private var freed = false

    val isOpen get() = openedAt != 0L

    init { visibility = GONE; isClickable = true }

    /** Quests q51's `pmenu` src "radial" (current): the TARGET's wheel opens, or refreshes (the checks after a run) */
    fun onDoc(d: JSONObject) {
        if (!isOpen) { open("target", Input.HOLD, TargetRadial.name(d), onDo, {}) }
        fill(d)
    }

    /** the menu's document for the wheel that is open (any opener): its entries, page by page */
    fun fill(d: JSONObject) {
        doc = d
        TargetRadial.name(d).takeIf { it.isNotEmpty() }?.let { name = it }
        TargetRadial.tellName(d).takeIf { it.isNotEmpty() }?.let { tell = it }
        items = PlayerMenu.parse(d.optJSONArray("items"), if (opener == "party") PlayerMenu.PARTY_GREY else emptySet())
        // q62: the pet's abilities as the game said them when it read the menu (the live document follows)
        val now = SystemClock.uptimeMillis()
        filledAt = now
        for (e in items) { val sl = e.autoSlot ?: continue; if (e.auto != null) PetAuto.seed(sl, e.text, e.auto == 1, e.autoCell, now) }
        relayout()
        invalidate()
    }

    private fun relayout() {
        // a page that is gone after the game's re-read (a sub-menu emptied): back to the root
        var s = TargetRadial.slices(items, pages.lastOrNull() ?: TargetRadial.ROOT)
        if (s == null) { pages.clear(); s = TargetRadial.slices(items) }
        slices = s ?: emptyList()
        if (pointed >= 0 && slices.getOrNull(pointed) == null) pointed = -1
    }

    /**
     * open the wheel for [opener] ("target" / "portrait" / "party"), its [name] until the document comes (Asking the game...);
     * [onDo] = what a pick does, [onClosed] = after it closes (by any route)
     */
    fun open(opener: String, input: Input, name: String, onDo: (PlayerMenu.Do, String) -> Unit, onClosed: () -> Unit) {
        if (isOpen) close("another opener")
        this.opener = opener; this.input = input; this.name = name; this.tell = ""
        doAct = onDo; closed = onClosed
        doc = null; items = emptyList(); pages.clear(); slices = emptyList()
        Log.i("WCLOG", "context radial: OPEN ($opener, $name, ${input.name})")
        MenuIcons.prefetch()
        UiKit.pauseBarsBottom = barsBottom?.invoke() ?: 0f
        UiKit.pauseRoomy = false                // feat/pause-power: the pause menu's larger layout is its own; this wheel keeps Home's controller
        UiKit.pauseStripTop = run {
            val a = IntArray(2); val b = IntArray(2)
            strip.getLocationOnScreen(a); getLocationOnScreen(b)
            // P4: the XP track hidden on Home is Home's place, not the bar's: the bar starts under it
            if (strip.height > 0) (a[1] - b[1]).toFloat() - strip.translationY + strip.trackGone else 0f
        }
        openedAt = SystemClock.uptimeMillis()
        pointed = -1; line = null; asking = null; aimStick = -1
        held = 0; seeded = false
        strip.homeLifted = true
        visibility = VISIBLE
        bringToFront()
        if (input == Input.HOLD) { owning = true; hold(0) }
        reader?.stop(); reader = null
        // KEYS (Party mode): the sticks too, the presses only by [key] (TargetRadial.reads)
        val rd = TargetRadial.reads(input)
        if (rd.sticks) reader = PadInput(
            onPad = { _, _, _, _ -> },
            onStatus = { ok, m -> if (!ok) Log.i(TAG, "context radial: controller reader: $m") },
            // a pop-up of the wheel's (Yes / No, rename) reads the buttons itself on EVERY opener: PopupHold.wheel holds them from the
            // game, so Party mode's PartyPad hears none meanwhile; otherwise the state is kept current, so no press counts twice
            onButtons = { h -> post { if (rd.buttons || popUp) buttons(h) else { held = h; seeded = true } } },
            onStickVector = { st, x, y -> post {
                mag[st.coerceIn(0, 1)] = kotlin.math.hypot(x.toDouble(), y.toDouble())
                if (isOpen) vector(st, x.toDouble(), -y.toDouble()) else stickCheck()
            } },
        ).also { it.start() }
        invalidate()
    }

    /** gone at once (the pause wheel's rule); a hold is let go once the sticks are back */
    fun close(why: String) {
        if (!isOpen) return
        Log.i("WCLOG", "context radial: CLOSED ($opener, $why)")
        if (renaming) cancelRename(back = false)
        if (asking != null) { asking = null; popDown() }
        openedAt = 0L
        val from = slidT; slidT = 0f
        strip.homeLifted = false
        visibility = GONE
        onClose(from)
        if (input == Input.HOLD) stickLater() else { reader?.stop(); reader = null }
        val cb = closed; closed = {}
        cb()
    }

    /** the app leaves the screen: gone, the hold freed */
    fun shut() {
        if (!isOpen) return
        close("the app left the screen")
    }

    private fun say(s: String) { line = s; lineAt = SystemClock.uptimeMillis(); invalidate() }

    // ------------------------------------------------------------------------------------------------ the controller

    private fun hold(attempt: Int) {
        heldOk = false
        val mine = ++holdTry
        val sent = guestTap(GuestPad.cells(questsPad(), GUEST, hold = true))
        Log.i(TAG, "context radial: hold${if (attempt > 0) " again ($attempt)" else ""} ${if (sent) "sent" else "NOT sent"}")
        postDelayed({ if (mine == holdTry && !heldOk && isOpen && attempt < 2) hold(attempt + 1) }, HomeRadialView.HOLD_CHECK_MS)
    }

    private fun free(attempt: Int) {
        val rest = HomeRadialView.FREE_AFTER_USE_MS - (SystemClock.uptimeMillis() - lastActAt)
        if (rest > 0) { val token = ++freeTry; postDelayed({ if (token == freeTry && !isOpen) free(attempt) }, rest); return }
        freed = false
        val mine = ++freeTry
        val sent = guestTap(GuestPad.cells(questsPad(), GUEST, hold = false))
        Log.i(TAG, "context radial: free${if (attempt > 0) " again ($attempt)" else ""} ${if (sent) "sent" else "NOT sent"}")
        postDelayed({
            if (mine != freeTry || isOpen) return@postDelayed
            if (!freed && attempt < 4) free(attempt + 1) else owning = false
        }, HomeRadialView.FREE_CHECK_MS)
    }

    /** the guest "radial"'s answers while this wheel is the one that holds it */
    fun onGuestAck(msg: JSONObject) {
        if (msg.optString("op") == "hold" && msg.optBoolean("ok")) heldOk = true
        if (msg.optString("op") == "free" && msg.optBoolean("ok")) { freed = true; if (!isOpen) owning = false }
    }
    /** this wheel holds the guest "radial", or is letting it go (the host routes the guest's answers here meanwhile) */
    var owning = false
        private set

    private var stickWait = false
    private val stickBack = Runnable { if (stickWait) { stickWait = false; free(0); reader?.stop(); reader = null } }
    private fun stickNeutral() = mag[0] < RadialModel.NEUTRAL && mag[1] < RadialModel.NEUTRAL
    private fun stickLater() {
        if (stickNeutral()) { free(0); reader?.stop(); reader = null; return }
        stickWait = true
        postDelayed(stickBack, RadialModel.STICK_BACK_MS)
    }
    private fun stickCheck() {
        if (!stickWait || !stickNeutral()) return
        stickWait = false; removeCallbacks(stickBack)
        free(0); reader?.stop(); reader = null
    }

    private fun pressed(slot: Int, h: Int) = PadInput.isHeld(h, slot) && !PadInput.isHeld(held, slot)

    private fun buttons(h: Int) {
        if (!isOpen) { held = h; return }
        // the first report is the state found: the button still down from the press that opened the wheel is no press
        if (!seeded) { seeded = true; held = h; return }
        val k = when {
            pressed(PadInput.SLOT_FACE_RIGHT, h) -> "PAD2"
            pressed(PadInput.SLOT_FACE_TOP, h) -> "PAD4"
            pressed(PadInput.SLOT_FACE_LEFT, h) -> "PAD3"                 // q62: X, the pointed pet ability's autocast
            pressed(PadInput.SLOT_FACE_BOTTOM, h) -> "PAD1"
            pressed(PadInput.SLOT_DPAD_RIGHT, h) -> "PADDRIGHT"
            pressed(PadInput.SLOT_DPAD_LEFT, h) -> "PADDLEFT"
            pressed(PadInput.SLOT_DPAD_UP, h) -> "PADDUP"
            pressed(PadInput.SLOT_DPAD_DOWN, h) -> "PADDDOWN"
            pressed(PadInput.SLOT_START, h) -> "PADFORWARD"             // q69: the rename keyboard's Done (nothing else on the wheel)
            else -> null
        }
        held = h
        if (k != null) key(k)
    }

    /** one press, the game's key names (the reader's, or the PartyPad's `key` for Party mode) */
    fun key(k: String) {
        if (!isOpen) return
        // the Yes / No pop-up: A yes, B no, nothing else (every press is held from the game meanwhile)
        asking?.let { s ->
            when (TargetRadial.answer(k)) {
                TargetRadial.Answer.YES -> yes(s)
                TargetRadial.Answer.NO -> no()
                TargetRadial.Answer.NONE -> {}
            }
            return
        }
        // the rename keyboard: its own keys (B closes it, Start / its Done renames)
        if (renaming) { kb?.press(k); invalidate(); return }
        when (k) {
            "PAD2" -> back()
            "PAD4" -> close("Y")
            "PAD1" -> if (pointed in 0..7) pick(pointed) else say("Point at a slice")
            "PAD3" -> if (pointed in 0..7) toggle(pointed)                // q62: the pointed pet ability's autocast
            "PADDRIGHT" -> step(+1)
            "PADDLEFT" -> step(-1)
            "PADDUP" -> { pointed = 0; invalidate() }
            "PADDDOWN" -> { pointed = 4; invalidate() }
        }
    }

    /** B: the wheel before (More / a sub-menu), or closed from the first */
    private fun back() {
        if (pages.isEmpty()) { close("B"); return }
        pages.removeAt(pages.size - 1)
        pointed = -1; line = null
        relayout(); invalidate()
    }

    private fun step(d: Int) {
        var p = pointed
        for (n in 0 until 8) {
            p = if (p < 0) (if (d > 0) 0 else 7) else (p + d + 8) % 8
            if (slices.getOrNull(p) != null) break
        }
        pointed = p
        line = null
        invalidate()
    }

    private fun vector(st: Int, x: Double, y: Double) {
        val m = kotlin.math.hypot(x, y)
        if (aimStick != st) { if (m < RadialModel.DEAD_IN) return; aimStick = st }
        if (m < RadialModel.DEAD_OUT) { aimStick = -1; return }
        // the user on test24: "the wheel direction is ... reacting the wrong way" - [y] is already up-positive here (open()'s -y), and
        // UiKit.pauseRadialAim flips it again; the pause wheel's own aim (RadialModel.aimOf: up-positive, with its hold on a slice)
        val s = RadialModel.aimOf(x, y, pointed)
        if (s in 0..7 && s != pointed && slices.getOrNull(s) != null) { pointed = s; line = null; invalidate() }
    }

    // ------------------------------------------------------------------------------------------------ a pick

    private fun pick(i: Int) {
        val s = slices.getOrNull(i) ?: return
        Log.i(TAG, "context radial: pick ${s.label}")
        s.next?.let { p ->
            // More / a sub-menu: the next wheel (B comes back)
            if (!s.enabled) { say("Not available here"); return }
            pages += p; pointed = -1; line = null
            relayout(); invalidate()
            return
        }
        val e = s.entry ?: return
        if (!e.enabled) { say(TargetRadial.greyWhy(e)); return }
        if (e.confirm != null) { openAsk(s); return }
        if (e.app == "rename") { startRename(e); return }
        run(s)
    }

    /** the slice's entry runs and the wheel closes (a plain pick, or the Yes of its pop-up) */
    private fun run(s: TargetRadial.Slice) {
        val e = s.entry ?: return
        val act = when {
            e.secure != null -> PlayerMenu.Do.Secure(e.secure, e.text, e.secureRow)
            e.id != null -> PlayerMenu.Do.Run(e.id, e.text, e.app)
            else -> null
        }
        if (act == null) { say("Not available here"); return }
        lastActAt = SystemClock.uptimeMillis()
        val run = doAct; val who = tell.ifEmpty { name }
        close("picked ${s.label}")
        run(act, who)
    }

    // ------------------------------------------------------------------------------------------------ the pop-ups (Yes / No, rename)

    /** the pad is held from the game from now (PopupHold.wheel) */
    private fun popUpHold() { PopupHold.wheel.up() }
    /** ... until [PopupHold.GRACE_MS] after now: the press that answered may still be on its way to the game (the hold race) */
    private fun popDown() { if (!popUp) PopupHold.wheel.down(SystemClock.uptimeMillis()) }

    /** an entry with `cf`: its Yes / No pop-up over the wheel (the pad held from the game) */
    private fun openAsk(s: TargetRadial.Slice) {
        Log.i(TAG, "context radial: ask ${s.label}")
        asking = s; line = null
        popUpHold()
        invalidate()
    }

    private fun yes(s: TargetRadial.Slice) {
        Log.i(TAG, "context radial: ask ${s.label}: yes")
        asking = null; popDown()
        run(s)
    }

    private fun no() {
        Log.i(TAG, "context radial: ask ${asking?.label}: no")
        asking = null; popDown()
        invalidate()
    }

    /**
     * Quests q69 THE PET'S RENAME (the user on test 1502: "renaming my pet is still grayed out"): the entry runs (the add-on opens Chat's
     * borrowed field with the pet's name, WC.chatField), the app's keyboard comes up with that name, every press held from the game;
     * Done hands the typed name to the add-on, which renames as the game's PETRENAMECONFIRM OnAccept does (C_PetInfo.PetRename). B: the
     * field closes (run id [TargetRadial.RENAME_OFF_ID]) and the wheel is back.
     */
    private fun startRename(e: PlayerMenu.Entry) {
        val id = e.id ?: run { say("Not available here"); return }
        val k = kb ?: run { say("Not available here"); return }
        Log.i(TAG, "context radial: rename $name")
        renaming = true; renameLine = null
        renameText = renameStream.clean(name, TargetRadial.RENAME_MAX)
        popUpHold()
        lastActAt = SystemClock.uptimeMillis()
        doAct(PlayerMenu.Do.Run(id, e.text, e.app), name)
        renameStream.startField(renameText)
        k.sendWord = "Done"
        k.text = { renameText }
        k.onText = { t -> renameText = renameStream.clean(t, TargetRadial.RENAME_MAX); renameStream.setBody(renameText); renameLine = null; invalidate() }
        k.onSend = {
            if (renameText.isBlank()) { renameLine = "Type a name"; invalidate() }
            else if (renameStream.send()) { removeCallbacks(renameTick); postDelayed(renameTick, 500L) }
        }
        k.onClose = { cancelRename(back = true) }
        k.open(); k.visibility = VISIBLE; k.bringToFront()
        invalidate()
    }

    /** B / its close: the add-on's field closes; [back] = the wheel stays (else the wheel is closing) */
    private fun cancelRename(back: Boolean) {
        if (!renaming) return
        Log.i(TAG, "context radial: rename cancelled")
        GuestPad.playerMenuRun(questsPad(), TargetRadial.RENAME_OFF_ID)?.let { guestTap(it) }
        endRename()
        if (back) invalidate()
    }

    private fun endRename() {
        renaming = false; renameLine = null
        removeCallbacks(renameTick)
        kb?.let { it.visibility = GONE; it.onSend = {}; it.onClose = {}; it.onText = {} }
        popDown()
    }

    /** Chat.lua's answers ({"type":"chatt"}) while the rename field is open: its check and its hand-over */
    fun onChatAnswer(msg: JSONObject) {
        if (!renaming || msg.optString("type") != "chatt") return
        renameStream.heard(ChatStream.Heard("chatt", msg.optString("op"), msg.optBoolean("ok"),
            if (msg.has("why")) msg.optString("why") else null, msg.optString("ty", ""),
            msg.optInt("len", -1), msg.optInt("h", -1), msg.optInt("tlen", -1), msg.optInt("th", -1), msg.optString("into")))
    }

    /** a pop-up of the wheel's is up: Start is its own (the rename's Done; the Yes / No ignores it), never the pause wheel's */
    fun ownsStart(): Boolean = isOpen && popUp

    /** the Yes / No pop-up: the game's dialog box, the question and what follows it, Yes (A) and No (B) */
    private fun drawAsk(c: Canvas, s: TargetRadial.Slice) {
        val screen = RectF(0f, 0f, width.toFloat(), height.toFloat())
        UiKit.cover(c, screen)
        val (q, more) = TargetRadial.confirmLines(s.entry?.confirm ?: s.label)
        val w = minOf(900f, width - 60f); val h = if (more != null) 330f else 280f
        val box = RectF(width / 2f - w / 2f, height * 0.30f - h / 2f, width / 2f + w / 2f, height * 0.30f + h / 2f)
        val inn = UiKit.dialog(c, box)
        val tp = UiKit.bodyPaint(36f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
        c.drawText(Tr.t(q), inn.centerX(), inn.top + 56f, tp)
        if (more != null) c.drawText(Tr.t(more), inn.centerX(), inn.top + 106f, UiKit.bodyPaint(28f, Color.WHITE).apply { textAlign = Paint.Align.CENTER })
        val bw = 300f; val bh = 100f
        yesBox.set(inn.centerX() - 14f - bw, box.bottom - 36f - bh, inn.centerX() - 14f, box.bottom - 36f)
        noBox.set(inn.centerX() + 14f, yesBox.top, inn.centerX() + 14f + bw, yesBox.bottom)
        UiKit.button(c, yesBox, "Yes", "rev_a")
        UiKit.button(c, noBox, "No", UiKit.backGlyph())
    }

    /** the rename pop-up over the wheel: its title, the name being typed with the caret, why the game said no; the keyboard under it */
    private fun drawRename(c: Canvas) {
        val screen = RectF(0f, 0f, width.toFloat(), height.toFloat())
        UiKit.cover(c, screen)
        val kh = kb?.let { if (it.height > 0) it.height.toFloat() else it.boardHeight().toFloat() } ?: 0f
        val w = minOf(900f, width - 60f)
        val bottom = (height - kh - 24f).coerceAtLeast(240f)
        val box = RectF(width / 2f - w / 2f, bottom - 230f, width / 2f + w / 2f, bottom)
        val inn = UiKit.dialog(c, box)
        c.drawText(Tr.t("Rename $name"), inn.centerX(), inn.top + 50f, UiKit.bodyPaint(34f, Theme.gold).apply { textAlign = Paint.Align.CENTER })
        val caret = if ((SystemClock.uptimeMillis() / 500L) % 2L == 0L) "|" else " "
        c.drawText(renameText + caret, inn.centerX(), inn.top + 112f, UiKit.bodyPaint(34f, Color.WHITE, shadow = false).apply { textAlign = Paint.Align.CENTER })
        renameLine?.let { c.drawText(Tr.t(it), inn.centerX(), inn.top + 160f, UiKit.bodyPaint(26f, Theme.unusable).apply { textAlign = Paint.Align.CENTER }) }
        postInvalidateDelayed(500L)
    }

    /**
     * q62: X (or a long press) on slice [i]: its autocast toggled, the pet bar's right-click - the add-on's toggle cell (pfocus 20-29,
     * the game's own /petautocasttoggle on our button), the flip shown at once ([PetAuto.pressed]) until the game's answer. The wheel
     * stays open. Not a pet ability: nothing.
     */
    private fun toggle(i: Int) {
        val s = slices.getOrNull(i) ?: return
        val slot = s.autoSlot ?: return
        val now = SystemClock.uptimeMillis()
        if (PetAuto.gone(filledAt)) { say("Not available here"); return }
        // review: the cell of THIS ability (by its name): after a pet swap the slot's live cell names another pet's ability
        val nm = s.entry?.text
        val live = PetAuto.find(slot, nm)
        if (live == null && PetAuto.lost(slot, nm, filledAt)) { say(if (PetAuto.fight) "Not in combat" else "Not available here"); return }
        val cell = if (live != null) live.cell else s.entry?.autoCell
        if (cell == null) { say(if (PetAuto.fight) "Not in combat" else "Not available here"); return }
        val at = live?.slot ?: slot
        val was = PetAuto.on(at, nm, now) ?: (s.entry?.auto == 1)
        Log.i(TAG, "context radial: autocast ${s.label} ${if (was) "off" else "on"} (pfocus $cell)")
        PetAuto.pressed(at, was, now)
        pointed = i; line = null
        lastActAt = now
        doAct(PlayerMenu.Do.Secure(cell, s.label, "pfocus"), name)
        invalidate()
    }

    // ------------------------------------------------------------------------------------------------ paint

    private fun discHere(): Pair<PointF, Float> {
        val r = strip.place(Room.HOME) ?: return PointF(width / 2f, height - 80f) to 140f
        val a = IntArray(2); val b = IntArray(2)
        strip.getLocationOnScreen(a); getLocationOnScreen(b)
        return PointF(r.centerX() + a[0] - b[0], r.centerY() + a[1] - b[1] - strip.translationY) to r.width()
    }

    private fun kitSlices(): List<UiKit.PauseSlice?> {
        val now = SystemClock.uptimeMillis()
        val gone = PetAuto.gone(filledAt)
        // review: an ability the pet no longer has (another pet since the menu was read) greys as a gone pet's
        return slices.map { it?.let { s -> TargetRadial.kitSlice(s, now, gone || (s.autoSlot?.let { sl -> PetAuto.lost(sl, s.entry?.text, filledAt) } == true)) } }
    }

    /** the title: the name, and the wheel it is on (More, a sub-menu's words) */
    private fun title(): String {
        val n = name.ifEmpty { "Target" }
        val p = pages.lastOrNull() ?: return n
        return p.sub ?: "$n: ${Tr.t("More")}"
    }

    override fun onDraw(c: Canvas) {
        if (!isOpen) return
        val t = ((SystemClock.uptimeMillis() - openedAt) * HomePauseView.SPEED).coerceIn(0f, UiKit.PAUSE_OPEN_MS)
        onSlide(t); slidT = t
        if (doc == null && line == null) say("Asking the game...")
        val lineFor = SystemClock.uptimeMillis() - lineAt
        if (line != null && lineFor > HomePauseView.LINE_MS + HomePauseView.LINE_FADE_MS) line = null
        val lineAlpha = if (lineFor <= HomePauseView.LINE_MS) 1f else (1f - (lineFor - HomePauseView.LINE_MS).toFloat() / HomePauseView.LINE_FADE_MS).coerceIn(0f, 1f)
        val screen = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val (disc, size) = discHere()
        UiKit.autoCastLive = false
        val moving = UiKit.pauseRadial(c, screen, disc, size, kitSlices(), -1, pointed, -1, t, 0f, line, lineAlpha = lineAlpha,
            pickGlyph = true, up = null, down = null, left = null, right = null, title = title())
        asking?.let { drawAsk(c, it) }
        if (renaming) drawRename(c)
        if (moving || t < UiKit.PAUSE_OPEN_MS || line != null) postInvalidateOnAnimation()
        // q62: the autocast shine marches (and a toggle waits for the game's answer): ~30 frames a second
        else if (UiKit.autoCastLive || PetAuto.waiting(SystemClock.uptimeMillis())) postInvalidateDelayed(33L)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isOpen) return false
        // a pop-up takes every touch: Yes / No on its buttons (the rename's keyboard is a view of its own over this one)
        if (popUp) {
            if (e.actionMasked == MotionEvent.ACTION_UP) asking?.let { s ->
                when {
                    yesBox.contains(e.x, e.y) -> yes(s)
                    noBox.contains(e.x, e.y) -> no()
                }
            }
            return true
        }
        val screen = RectF(0f, 0f, width.toFloat(), height.toFloat())
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // q62: a long press on a pet ability toggles its autocast (the class wheel's long press)
                removeCallbacks(longPress); longDone = false
                val s = UiKit.pauseRadialHit(screen, e.x, e.y)
                downSlice = if (s in 0..7 && slices.getOrNull(s)?.autoSlot != null) s else -1
                if (downSlice >= 0) postDelayed(longPress, LONG_MS)
            }
            // review: a finger that slides off the ability (a release on another slice picks THAT one) never toggles it
            MotionEvent.ACTION_MOVE -> if (downSlice >= 0 && !longDone && UiKit.pauseRadialHit(screen, e.x, e.y) != downSlice) {
                removeCallbacks(longPress); downSlice = -1
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val was = longDone; longDone = false; downSlice = -1
                if (was) return true                              // the long press toggled: no pick on the release
                val s = UiKit.pauseRadialHit(screen, e.x, e.y)
                when {
                    s == UiKit.PAUSE_HUB -> back()                    // the hub = B (a wheel back, or closed)
                    s < 0 -> close("outside")
                    else -> { pointed = s; pick(s) }
                }
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); downSlice = -1; longDone = false }
        }
        return true
    }

    companion object {
        const val TAG = "WCHOME"
        const val GUEST = "radial"
        /** q62: a finger held this long on a pet ability toggles its autocast (the class wheel's CLASS_LONG_MS) */
        const val LONG_MS = 450L

    }
}
