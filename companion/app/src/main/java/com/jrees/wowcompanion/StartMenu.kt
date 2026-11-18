package com.jrees.wowcompanion

import android.graphics.Canvas
import android.graphics.RectF

/**
 * THE MENU (the user 2026-11-01), on the waiting screen ([StartView]) and the chooser ([ChooserPanel]): Auto login's tick and Change login,
 * Forget login, Report issue, the controller's two settings; on the waiting screen also Stream settings or Restart game, Switch mode and
 * Exit game. The Menu button turned into an X, B or a touch outside shuts it. The host fills in the actions; one app, one menu. Main thread.
 */
object StartMenu {
    /** the Menu shows; shutting it shuts the region list / card with no change */
    var open = false
        set(v) { field = v; if (!v) picker.close() }
    var onReport: (() -> Unit)? = null
    var autoLogin: () -> Boolean = { true }
    var onAutoLogin: ((Boolean) -> Unit)? = null
    var onChangeLogin: (() -> Unit)? = null
    /** Exit game answers only while a game or a stream runs */
    var canExit: () -> Boolean = { false }
    var onExit: (() -> Unit)? = null
    var canForget: () -> Boolean = { false }
    var onForgetLogin: (() -> Unit)? = null
    var canRestart: () -> Boolean = { false }
    var onRestart: (() -> Unit)? = null
    var canSwitch: () -> Boolean = { false }
    var onSwitch: (() -> Unit)? = null
    /** the game's region (GameRegion's code) and its change (the user, 2026-11-07: "There should be a change region option in the menu
     *  when starting the app"). The bottom row's Region button opens the list ([picker]; the user 2026-11-17: each press of the old
     *  button saved the next region at once and its build downloaded); [onRegion] is called ONLY by the card's Switch. The portal is
     *  written at the game's next start */
    var region: () -> String = { "us" }
    var onRegion: ((String) -> Unit)? = null
    /** the region list and its confirm card (pure); open while the Menu shows it in the panel's place */
    val picker = RegionPicker()
    /** the card asks what switching to a region would do (the launcher compares that region's build with the install's, off the main
     *  thread); the answer comes back through the callback, on any thread. Null = no such check (the card warns of a download) */
    var regionPreview: ((code: String, answer: (RegionPicker.Preview) -> Unit) -> Unit)? = null
    private var forgetArmedAt = -FORGET_ARM_MS

    /**
     * the panel centred in [area]; [menuButton] = where the Menu button is, the X now; [hits] gets what a touch or the cursor can press,
     * [pressed] the id held down. [game] = the waiting screen, with what acts on a running game or mode (Stream settings or Restart, Switch mode, Exit game)
     */
    fun draw(c: Canvas, area: RectF, pressed: Int, hits: ArrayList<Pair<RectF, Int>>, menuButton: RectF, game: Boolean) {
        hits.clear()
        if (picker.open) { drawPicker(c, area, pressed, hits, menuButton); return }
        val bh = StartView.BUTTON_H; val gap = StartView.GAP
        val rows = if (game) 4 else 3
        val height = PAD * 2 + bh * rows + CONTROLLER_H + gap * rows
        val top = (area.centerY() - height / 2f).coerceAtLeast(area.top)
        val panel = RectF(area.left, top, area.right, top + height)
        UiKit.module(c, panel)
        val inL = panel.left + PAD; val inR = panel.right - PAD
        val mid = (inL + inR) / 2f
        var y = panel.top + PAD
        fun half(right: Boolean, h2: Float = bh) = RectF(if (right) mid + gap / 2f else inL, y, if (right) inR else mid - gap / 2f, y + h2)
        fun down(id: Int) = if (pressed == id) UiKit.Button.DOWN else UiKit.Button.UP
        fun put(r: RectF, word: String, id: Int, on: Boolean) {
            if (!on) { UiKit.button(c, r, word, UiKit.Button.DISABLED); return }
            UiKit.button(c, r, word, down(id))
            hits += r to id
        }
        UiKit.button(c, menuButton, "X", StartView.GLYPH_B, down(CLOSE))
        hits += RectF(menuButton) to CLOSE
        val auto = half(false)
        UiKit.check(c, auto.left + CHECK / 2f, auto.centerY(), CHECK, autoLogin())
        val words = UiKit.bodyPaint(StartView.LINE_SIZE)
        UiKit.outlined(c, "Auto login", (auto.left + CHECK + auto.right) / 2f, auto.centerY() - (words.ascent() + words.descent()) / 2f, StartView.LINE_SIZE, Theme.text)
        hits += auto to AUTO
        put(half(true), "Change login", CHANGE, true)
        y += bh + gap
        val armed = android.os.SystemClock.uptimeMillis() - forgetArmedAt < FORGET_ARM_MS
        put(half(false), if (armed) "Tap again" else "Forget login", FORGET, canForget())
        if (game) {
            if (SetupBridge.openStreamSettings != null) put(half(true), "Stream settings", STREAM, true)
            else put(half(true), "Restart game", RESTART, canRestart())
            y += bh + gap
            put(half(false), "Report issue", REPORT, true)
            put(half(true), "Switch mode", SWITCH, canSwitch())
        } else put(half(true), "Report issue", REPORT, true)
        y += bh + gap
        // the controller, one object: its two buttons on the left, what they make on the right
        val box = RectF(inL, y, inR, y + CONTROLLER_H)
        UiKit.border(c, box)
        val inner = RectF(box.left + INSET, box.top + INSET, box.left + box.width() * 0.62f, box.bottom - INSET)
        val rowH = (inner.height() - gap) / 2f
        put(RectF(inner.left, inner.top, inner.right, inner.top + rowH), "ABXY: ${CastSettings.abxyLocation.label}", ABXY, true)
        put(RectF(inner.left, inner.bottom - rowH, inner.right, inner.bottom), "Glyphs: ${CastSettings.buttonLayout.label}", GLYPHS, true)
        faceDiamond(c, RectF(inner.right, box.top, box.right, box.bottom))
        y += CONTROLLER_H + gap
        // the bottom row: Region (both screens), and Exit game beside it on the waiting screen
        val rg = GameRegion.ALL.firstOrNull { it.code == region() } ?: GameRegion.ALL[0]
        val rWord = "${Tr.t("Region")}: ${Tr.t(rg.label)}"
        if (game) {
            put(half(false), rWord, REGION, true)
            put(half(true), "Exit game", EXIT, canExit())
        } else put(RectF(inL, y, inR, y + bh), rWord, REGION, true)
    }

    /**
     * THE REGION LIST and THE CONFIRM CARD in the panel's place (the user, 2026-11-17). The list: every region, the one in use ticked,
     * the cursor's row boxed by the host ([focusId]). The card: "Switch to EU?", what it does ("Downloads the EU game" / "Uses this
     * install"), Cancel and Switch (Cancel focused first).
     */
    private fun drawPicker(c: Canvas, area: RectF, pressed: Int, hits: ArrayList<Pair<RectF, Int>>, menuButton: RectF) {
        val bh = StartView.BUTTON_H; val gap = StartView.GAP
        fun down(id: Int) = if (pressed == id) UiKit.Button.DOWN else UiKit.Button.UP
        UiKit.button(c, menuButton, "X", StartView.GLYPH_B, down(CLOSE))
        hits += RectF(menuButton) to CLOSE
        val title = UiKit.titlePaint(TITLE_SIZE).apply { textAlign = android.graphics.Paint.Align.CENTER }
        if (picker.stage == RegionPicker.Stage.LIST) {
            val n = picker.regions.size
            val height = PAD * 2 + TITLE_H + bh * n + gap * (n - 1)
            val top = (area.centerY() - height / 2f).coerceAtLeast(area.top)
            val panel = RectF(area.left, top, area.right, top + height)
            UiKit.module(c, panel)
            c.drawText(Tr.t("Region"), panel.centerX(), panel.top + PAD + TITLE_H * 0.62f, title)
            var y = panel.top + PAD + TITLE_H
            for ((i, code) in picker.regions.withIndex()) {
                val r = RectF(panel.left + PAD, y, panel.right - PAD, y + bh)
                UiKit.button(c, r, label(code), down(ROW0 + i))
                if (code == picker.current) UiKit.check(c, r.left + CHECK / 2f + 14f, r.centerY(), CHECK * 0.8f, true)
                hits += r to ROW0 + i
                y += bh + gap
            }
            return
        }
        val t = picker.target ?: return
        val height = PAD * 2 + TITLE_H + LINE_H + gap + bh
        val top = (area.centerY() - height / 2f).coerceAtLeast(area.top)
        val panel = RectF(area.left, top, area.right, top + height)
        UiKit.module(c, panel)
        c.drawText(Tr.t("Switch to ${Tr.t(label(t))}?"), panel.centerX(), panel.top + PAD + TITLE_H * 0.62f, title)
        UiKit.outlined(c, previewLine(t, picker.preview), panel.centerX(), panel.top + PAD + TITLE_H + LINE_H / 2f, StartView.LINE_SIZE, Theme.text)
        val y = panel.top + PAD + TITLE_H + LINE_H + gap
        val mid = panel.centerX()
        val cancel = RectF(panel.left + PAD, y, mid - gap / 2f, y + bh)
        val switch = RectF(mid + gap / 2f, y, panel.right - PAD, y + bh)
        UiKit.button(c, cancel, "Cancel", down(REGION_CANCEL)); hits += cancel to REGION_CANCEL
        UiKit.button(c, switch, "Switch", down(REGION_SWITCH)); hits += switch to REGION_SWITCH
    }

    /** a region's name as the list shows it (English: the drawing translates) */
    fun label(code: String): String = (GameRegion.ALL.firstOrNull { it.code == code } ?: GameRegion.ALL[0]).label

    /** the card's second line for the region [code] (English: the drawing translates); terse. Pure */
    fun previewLine(code: String, p: RegionPicker.Preview): String = when (p) {
        RegionPicker.Preview.CHECKING -> "Checking..."
        RegionPicker.Preview.INSTALL -> "Uses this install"
        // no answer (no network): the plain warning, as a download may follow
        RegionPicker.Preview.DOWNLOAD, RegionPicker.Preview.UNKNOWN -> "Downloads the ${Tr.t(label(code))} game"
    }

    /** the hit the pad's cursor is on while the list or card shows (the host boxes it); null = the Menu's own cursor */
    fun focusId(): Int? = when (picker.stage) {
        RegionPicker.Stage.LIST -> ROW0 + picker.cursor
        RegionPicker.Stage.CONFIRM -> if (picker.switchFocused) REGION_SWITCH else REGION_CANCEL
        RegionPicker.Stage.CLOSED -> null
    }

    /** the D-pad while the list or card shows (true = taken) */
    fun pickerMove(dx: Int, dy: Int): Boolean { if (!picker.open) return false; picker.move(dx, dy); return true }

    /** A while the list or card shows (true = taken) */
    fun pickerA(): Boolean { if (!picker.open) return false; handle(picker.a()); return true }

    /** B: one step back in the list / card (no change), true = taken; false = nothing of the region's open (the Menu shuts) */
    fun back(): Boolean { if (!picker.open) return false; handle(picker.b()); return true }

    private fun handle(r: RegionPicker.Result) {
        when (r) {
            is RegionPicker.Result.Ask -> {
                val ask = regionPreview
                if (ask == null) picker.preview = RegionPicker.Preview.UNKNOWN
                else ask(r.code) { p -> if (picker.target == r.code) picker.preview = p }
            }
            // the one change: the Menu shuts on purpose, then it is saved (a new build may download: the setup page says so, with Stop)
            is RegionPicker.Result.Apply -> { open = false; onRegion?.invoke(r.code) }
            RegionPicker.Result.Closed, RegionPicker.Result.None -> {}
        }
    }

    /** the four face buttons where they sit: Nintendo's X top and A right, Xbox's Y top and B right */
    private fun faceDiamond(c: Canvas, r: RectF) {
        val cx = r.centerX(); val cy = r.centerY()
        val nintendo = CastSettings.abxyLocation == CastSettings.AbxyLocation.NINTENDO
        UiKit.pad(c, if (nintendo) "rev_x" else "rev_y", cx, cy - ARM, FACE)
        UiKit.pad(c, if (nintendo) "rev_a" else "rev_b", cx + ARM, cy, FACE)
        UiKit.pad(c, if (nintendo) "rev_b" else "rev_a", cx, cy + ARM, FACE)
        UiKit.pad(c, if (nintendo) "rev_y" else "rev_x", cx - ARM, cy, FACE)
    }

    /** the cursor's next place from ([x], [y]) on a D-pad press ([dx], [dy] = its direction, one of them 0): the nearest in that direction,
     *  off to the side counting double; null = nothing there */
    fun nearest(places: List<Triple<Int, Float, Float>>, x: Float, y: Float, dx: Int, dy: Int): Int? = places
        .filter { (_, px, py) -> (px - x) * dx + (py - y) * dy > 1f }
        .minByOrNull { (_, px, py) ->
            val along = kotlin.math.abs((px - x) * dx + (py - y) * dy)
            val side = kotlin.math.abs((px - x) * dy) + kotlin.math.abs((py - y) * dx)
            along + side * 2f
        }?.first

    private fun <T> next(all: Array<T>, now: T): T = all[(all.indexOf(now) + 1) % all.size]

    fun press(id: Int) {
        when (id) {
            CLOSE -> open = false
            ABXY -> CastSettings.abxyLocation = next(CastSettings.AbxyLocation.values(), CastSettings.abxyLocation)
            GLYPHS -> CastSettings.buttonLayout = next(CastSettings.ButtonLayout.values(), CastSettings.buttonLayout)
            AUTO -> onAutoLogin?.invoke(!autoLogin())
            FORGET -> {
                val at = android.os.SystemClock.uptimeMillis()
                if (at - forgetArmedAt < FORGET_ARM_MS) { forgetArmedAt = -FORGET_ARM_MS; onForgetLogin?.invoke() } else forgetArmedAt = at
            }
            CHANGE -> { open = false; onChangeLogin?.invoke() }
            STREAM -> { open = false; SetupBridge.openStreamSettings?.invoke() }
            RESTART -> { open = false; onRestart?.invoke() }
            SWITCH -> { open = false; onSwitch?.invoke() }
            REPORT -> { open = false; onReport?.invoke() }
            EXIT -> { open = false; onExit?.invoke() }
            // the list, the cursor on the region in use: nothing changes until the card's Switch (the user, 2026-11-17)
            REGION -> picker.open(region())
            REGION_CANCEL -> handle(picker.cancel())
            REGION_SWITCH -> handle(picker.switch())
            in ROW0 until ROW0 + picker.regions.size -> handle(picker.pick(id - ROW0))
        }
    }

    private const val AUTO = 4
    private const val CHANGE = 5
    private const val REPORT = 6
    private const val EXIT = 7
    private const val FORGET = 8
    private const val STREAM = 9
    private const val RESTART = 10
    private const val SWITCH = 11
    private const val CLOSE = 12
    private const val ABXY = 13
    private const val GLYPHS = 14
    const val REGION = 15
    const val REGION_CANCEL = 16
    const val REGION_SWITCH = 17
    /** the list's rows: ROW0 + the region's index in GameRegion.ALL */
    const val ROW0 = 20
    private const val TITLE_SIZE = 52f
    private const val TITLE_H = 90f
    private const val LINE_H = 70f
    private const val FORGET_ARM_MS = 4000L
    const val WIDTH = 1000f
    private const val PAD = 32f
    private const val CHECK = 76f
    private const val CONTROLLER_H = 270f
    private const val INSET = 18f
    private const val FACE = 72f
    private const val ARM = 72f
}
