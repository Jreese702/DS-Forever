package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.CornerPathEffect
import android.graphics.DashPathEffect
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The CAST room: a faithful replica of WoW: Forever's gamepad Controller UI ("camelot" HUD), so
 * the game's own HUD can be hidden from the top screen (user ruling: layout A, ONE compact bar).
 *
 * DATA (all inside the hub's `actionbar` object, see hud_spec.md):
 *  - `hud`  = the LAYOUT: 7 layers x 8 buttons for the current page, overrides already resolved,
 *            page chips, the hide/show keys and the gamepad CVars. Slow-changing.
 *  - `hudS` = the STATE: usable / mana / range / cooldown / glow / checked / counts for the four
 *            trigger layers, stamped with the game clock. Small and frequent.
 *  - `slots` = the legacy keyboard-slot mirror, drawn only until the first `hud` arrives.
 *  [update] MERGES: each part replaces only itself, so a state-only doc never wipes the layout.
 *
 * LAYERS are chosen LOCALLY from the physical controller ([PadInput], read-only), never from the
 * log: RT -> rt, LT -> lt, both -> lr; else LB -> friendly, RB -> hostile, both -> shortcuts
 * (the shoulders swap with GamepadSwapTargetModifiers); else the base layer.
 *
 * HIDING THE GAME'S OWN HUD is NOT this view's job: the hub's GameUi owns it (element `hud`, the
 * user's three modes On / Off / Dynamic, driven through the `ui` tap-pad row into the addon's
 * WC.hudHide / WC.hudShow). This view sends NOTHING to the game. Taps on buttons show a pressed
 * look only; firing a button (the tap pad's secure row) comes later. [onCast] is kept for that.
 *
 * GEOMETRY is Blizzard's own (ActionBarStyles.lua, expanded bar), in UI units with y UP and the
 * origin at the bar centre, scaled by [k] px per unit from the view size.
 *
 * ART is Blizzard's own, cut from the user's client by wow-companion/tools/icons/extract_cast_art.py
 * and pushed to /sdcard/WowCompanion/ui/cast/ (never shipped in the APK, never committed): button
 * rings (normal / pressed / selected / hover / disabled), empty-slot art, focus shadows, the focus
 * section, shoulder badges, page chips and the preset's glyph tiles (face, D-pad, shoulders,
 * triggers, sticks). The files are decoded on a background thread; every piece falls back to the
 * old vector drawing while it loads or when its file is missing, so the room still works before
 * the art is pushed. Out-of-range glyphs use Blizzard's Disabled glyph art.
 *
 * PRESS FEEDBACK: [PadInput] reports the D-pad and face buttons by PHYSICAL position (the same
 * 0..7 order as the slots here). While one is held its slot shows Blizzard's pushed look (2 units
 * down, pressed ring, pressed glyph) plus a short flash on the press. Read-only, like the triggers.
 */
class CastView(context: Context, private val onCast: (String) -> Unit) : View(context) {

    // =================================================================== model

    private class Btn {
        var p = 0                  // 1..8: D-pad left/up/right/down, face left/top/right/bottom
        var s = 0                  // storage slot (0 = reserved / scripted)
        var k = ""                 // spell | item | macro | flyout | pet | fixed | "" (empty)
        var id = 0
        var icon = 0               // texture fileDataID, 0 = none
        var art: String? = null    // "atlas:<name>" / "tex:<path>" when the icon is not a fileID
        var fx: String? = null     // interact | inspect | back | jump (scripted face buttons)
        var e = true               // button:IsEnabled()
        var ct = -1                // b-cast-bar-late: its spell's cast time in ms (0 instant, -1 unknown)
        var key: String? = null    // legacy slots only: the keybind shown in the glyph
        var special = SP_NONE      // vector stand-in when there is no loadable icon
        var unable = false         // an "Unable..." cursor / crosshair: drawn faded
        var ds = false             // holds one of the companion's "DS Bar ..." macros (drives the bottom screen)
        var kit: String? = null    // a UiKit picture drawn in the slot (the D-pad toolbar's map / chat / Home swirl)
        var kitScale = 0.62f       // ... its size inside the slot
        var wheel = false          // the D-pad toolbar's consumables radial: a mini radial wheel
        var kitCut = false         // draw only the swirl cut out of the kit picture (the Home tile)
        var bmp: Bitmap? = null
        var shader: BitmapShader? = null
        val empty get() = k.isEmpty()
        fun same(o: Btn?) = o != null && o.s == s && o.k == k && o.id == id
    }

    private class St {
        var u = 1; var m = 0; var r = -1; var cd = 0
        var st = Double.NaN; var du = Double.NaN
        var ch = -1; var cnt = -1
        var gl = 0; var chk = 0; var eq = 0
        var countText: String? = null
        fun reset() {
            u = 1; m = 0; r = -1; cd = 0; st = Double.NaN; du = Double.NaN
            ch = -1; cnt = -1; gl = 0; chk = 0; eq = 0; countText = null
        }
    }

    private class Hud {
        var ok = false
        var page = 1
        val pages = BooleanArray(3)
        var hidden = false
        /** false for the addon's error layout `{v=1, ok=false}`, which says nothing about the HUD */
        var hiddenKnown = false
        var swapT = false
        val layers = Array(LAYERS) { arrayOfNulls<Btn>(8) }
        val indexOfPos = Array(LAYERS) { IntArray(8) { it } }   // state index for position p-1
        val ov = arrayOfNulls<String>(STATE_LAYERS)
        /** the class-only radials (hud.ca): left = LB+LT, right = RB+RT; null for classes without one */
        var caL: Radial? = null; var caR: Radial? = null
    }

    /** one class radial: its 8 PLACES as the game lays them out (0 = top, clockwise; f-class-radial: a pet command keeps its fixed
     *  place, 4 empty), each with the add-on's touch cell on row "classr" ([cell]) and, for a pet spell, its autocast cell ([auto]) */
    private class Radial {
        val has = BooleanArray(8); val names = arrayOfNulls<String>(8); val icons = IntArray(8); val lit = BooleanArray(8)
        val cell = arrayOfNulls<Int>(8); val auto = arrayOfNulls<Int>(8)
    }

    private var hud: Hud? = null
    private val state = Array(STATE_LAYERS) { Array(8) { St() } }
    private var hasState = false
    /** false while a window owns the pad (hudS.bu = 0): the game collapses its HUD and ignores LT/RT/LB/RB */
    private var barsUsable = true
    /** set for the duration of one onDraw from [barsUsable] */
    private var drawCollapsed = false
    /** the right stick's eighth (0 = top, clockwise), -1 at rest: the radial's pointed-at place */
    private var stickSector = -1
    private var autoAttack = false
    /** the swing timer (hudS.sw, combat only): which swing (0 main, 1 off, 2 ranged), its start on the
     *  app's elapsedRealtime clock in ms (NaN = none) and its length */
    private var swingKind = -1
    private var swingStartMs = Double.NaN
    private var swingDurMs = 0.0
    /** the cast bar: start / end on the app clock in ms (NaN = no cast), channel = drains instead of fills */
    private var castStartMs = Double.NaN
    private var castEndMs = 0.0
    private var castChannel = false
    private var castEndSeen = Double.NaN
    /** the bar's one-shot flash: which (FLASH_x) and when (elapsedRealtime) */
    private var flashKind = 0
    private var flashAtMs = 0L

    private fun flash(kind: Int) { flashKind = kind; flashAtMs = SystemClock.elapsedRealtime(); invalidate() }

    /**
     * Home sets this while it shows the band in combat: the view then draws the line along its own
     * bottom edge that divides the controller area from the page, and that line IS the swing timer
     * (user, 2026-09-29). While a swing runs, a gold fill grows along it; otherwise it is the plain kit
     * divider, so the page always has one line. Only drawn in the band layouts.
     */
    var bottomLine = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** p-swing-timer, batch 12 (the user: "swing timer also turned off cast bar, they should be seperate"): false = the bottom line shows
     *  no SWING (its fill and its restart flash); the CAST bar and its flashes stay (Settings > Home "Swing timer") */
    var swingOn = true
        set(v) { if (field != v) { field = v; invalidate() } }

    /**
     * DIAMONDS only (Home's band; LATER 16 + 18, UI Art's HARMONY_SPEC): ZL / ZR wells low under the LB / RB plates
     * (they slide together with a "+" when both are held), lit while held (the game's trigger glow) and dimmed otherwise, so the
     * triggers read as modifiers. Collapsed (a window owns the pad: the game ignores them) = both dimmed.
     */
    var triggerStrip = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** while true the band draws NO presses (no push-down, no flash): Home's Select cursor owns the pad then
     *  (user 2026-09-30, LATER item 23a). Layers and the triggers still follow the pad. */
    var quietPresses = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /**
     * FIGHT FACE BUTTONS (user 2026-10-01, Spellbook's PadMap m7): in a fight, the base set's fixed face buttons show the
     * spell the user gave them for fights: game position 5 = X / face-left (Interact), 6 = Y / face-top (Inspect),
     * 8 = A / face-bottom (Jump) -> that spell's icon fileID; B (7) never has one. The host fills it from the padmap
     * document (Slot.fight); positions without a fight spell keep their game look. Drawn only while [fightFaceOn]
     * (default OFF). No combat gate of its own any more (user 2026-10-01: "follow the exact same logic as the controller
     * expanding, using the start attack"): the host's fightFaceOn + the ARMED positions in fightFace decide, and Spellbook
     * arms the keys on start attack OR combat.
     */
    var fightFace: Map<Int, Int>? = null
        set(v) { if (field != v) { field = v; fightBtns.fill(null); invalidate() } }
    var fightFaceOn = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val fightBtns = arrayOfNulls<Btn>(8)

    /**
     * THE ROOM BAR on the D-pad (user 2026-10-02: "I want this to work just like the in combat bar, and have its own group
     * page that is on the default layer out of combat"). Spellbook's router makes the bare D-pad drive Home's room bar OUT
     * of combat; the host mirrors it here: [roomBar] = D-pad game position 1..4 (left / up / right / down) -> what it does
     * ("left" / "right" = move, "open", "back", anything else = a dot), and [roomBarOn] while that mode is live (the host
     * turns it off in fights, when the game's D-pad is back). The base layer's D-pad then draws as its own "Room bar" group.
     */
    var roomBar: Map<Int, String>? = null
        set(v) { if (field != v) { field = v; roomBtns.fill(null); invalidate() } }
    var roomBarOn = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val roomBtns = arrayOfNulls<Btn>(4)

    private fun roomOverride(li: Int, src: Int): Btn? {
        if (!roomBarOn || li != N || src > 3) return null
        val what = roomBar?.get(src + 1) ?: return null
        roomBtns[src]?.let { if (it.icon > 0 && it.shader == null) resolve(it, false); return it }
        val b = Btn().apply {
            p = src + 1; k = "fixed"; e = true
            // PadMap m29 ("DS WoW toolbar", the user's D-pad layout): the same pictures as Home's bar
            when (what) {
                "map" -> kit = "ricon_map"
                "chat" -> kit = "ricon_chat"
                // the Home disc's swirl, as the Home button draws it, filling the tile (user 10-06: "made a bit larger")
                "home" -> { kit = "home_stone_rough"; kitCut = true; kitScale = 0.95f }   // the bar Home button's own face, 1:1 (user 10-06)
                // the consumables radial (user 10-06: "should look like a little mini Radial wheel")
                "radial" -> wheel = true
                else -> special = when (what) {
                    "left" -> SP_ARROW_L; "right" -> SP_ARROW_R; "open" -> SP_ARROW_U; "back", "close" -> SP_ARROW_D
                    else -> SP_DOT
                }
            }
        }
        if (b.icon > 0) resolve(b, false)
        roomBtns[src] = b
        return b
    }

    /**
     * A FIGHT FACE'S COOLDOWN (user 2026-10-01: "when I use earthshock it does not look like it goes on cooldown at all").
     * The host (Spellbook's data: the real cooldown when readable, else the tooltip's cooldown, and the GCD after a
     * successful cast) calls this with the start on SystemClock.elapsedRealtime() and the length; the face then draws a
     * cooldown over its icon until it runs out. A new call replaces the old one (a longer cooldown after a GCD, a reset).
     * [pos] = game position 5 / 6 / 8. durMs <= 0 clears it.
     */
    fun fightCooldown(pos: Int, startMs: Long, durMs: Long) {
        if (pos !in 1..8) return
        fightCdStart[pos - 1] = startMs; fightCdDur[pos - 1] = durMs
        invalidate()
    }
    /** the fight cooldown's look: false = the game's dark sweep (as a real cooldown), true = the dashed estimate arc */
    var fightCdDashed = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val fightCdStart = LongArray(8)
    private val fightCdDur = LongArray(8)

    /** remaining fraction of fight face [src]'s cooldown, or -1 */
    private fun fightCdLeft(src: Int, now: Long): Float {
        val d = fightCdDur[src]; if (d <= 0L) return -1f
        val left = fightCdStart[src] + d - now
        return if (left > 0L) (left.toFloat() / d).coerceIn(0f, 1f) else -1f
    }

    /**
     * THE D-PAD'S IN-COMBAT SPELLS (Track C; the user after test15: "The DS toolbar buttons are still not changing in combat like they
     * should... the Dpad specifically"): base-layer D-pad position (1..4) -> its In-combat spell's icon, set by the host ONLY while the
     * game is in a fight (PadMap m44: in fights such a D-pad press casts it). Drawn over DS Forever's D-pad layout then; a direction
     * without one keeps the layout's picture (it still does chat / radial / map / Home).
     */
    var fightDpad: Map<Int, Int>? = null
        set(v) { if (field != v) { field = v; dpadFightBtns.fill(null); invalidate() } }
    private val dpadFightBtns = arrayOfNulls<Btn>(4)

    private fun dpadFightOverride(li: Int, src: Int): Btn? {
        if (li != N || src > 3) return null
        val icon = fightDpad?.get(src + 1)?.takeIf { it > 0 } ?: return null
        val cached = dpadFightBtns[src]
        if (cached != null && cached.icon == icon) { if (cached.shader == null) resolve(cached, false); return cached }
        val b = Btn().apply { p = src + 1; k = "spell"; this.icon = icon; e = true }
        classify(b)
        resolve(b, false)
        dpadFightBtns[src] = b
        return b
    }

    /**
     * THE COMPANION'S COMMANDS (Track C, after test17: "The button was not there"): HomeFightFace.cmdKey(set, pos) -> the command's icon.
     * A command is the router's overlay, so the game's own slot under it is empty; it is drawn over that slot on the trigger layers
     * (none / LT / RT / LT+RT = sets 1..4), in and out of fights.
     */
    var commands: Map<Int, Int>? = null
        set(v) { if (field != v) { field = v; cmdBtns.fill(null); invalidate() } }
    private val cmdBtns = arrayOfNulls<Btn>(32)

    private fun commandOverride(li: Int, src: Int): Btn? {
        if (li !in 0 until STATE_LAYERS) return null
        val at = li * 8 + src
        val icon = commands?.get(at)?.takeIf { it > 0 } ?: return null
        val cached = cmdBtns[at]
        if (cached != null && cached.icon == icon) { if (cached.shader == null) resolve(cached, src >= 4); return cached }
        val b = Btn().apply { p = src + 1; k = "command"; this.icon = icon; e = true }
        classify(b)
        resolve(b, src >= 4)
        cmdBtns[at] = b
        return b
    }

    /**
     * b-combat-bar-states (the user on 1.0.8-test2: "The combat bars are not showing when an ability does not have enough mana, or if it
     * was selected for casting like the normal bars do"): base-set GAME position 1..8 -> the spell id of the In-combat spell drawn there
     * (X / Y / A's fight spells, the D-pad's overlay ones; HomeFightFace.ids), and their state by spell id from the add-on (hudS.fs).
     * A fight override is drawn with that state - not enough mana blue, unusable dark, out of range red, selected for casting checked -
     * as the game's own buttons are.
     */
    var fightIds: Map<Int, Int>? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    private val fightStates = HashMap<Int, St>()
    private val fightCt = HashMap<Int, Int>()
    private fun fightStateOf(fb: Btn?, src: Int): St? {
        if (fb == null || fb.k != "spell") return null
        if (fb !== fightBtns.getOrNull(src) && fb !== dpadFightBtns.getOrNull(src)) return null
        val id = fightIds?.get(src + 1) ?: return null
        return fightStates[id]
    }

    /** the fight spell drawn over base-layer face slot [src] (0-based), or null to draw the game's own button */
    private fun fightOverride(li: Int, src: Int): Btn? {
        if (!fightFaceOn || li != N || src < 4 || src == 6) return null
        val icon = fightFace?.get(src + 1) ?: return null
        if (icon <= 0) return null
        val cached = fightBtns[src]
        if (cached != null && cached.icon == icon) { if (cached.shader == null) resolve(cached, true); return cached }
        val b = Btn().apply { p = src + 1; k = "spell"; this.icon = icon; e = true }
        classify(b)
        resolve(b, true)
        fightBtns[src] = b
        return b
    }

    /**
     * Painted right after the ink ground and BEFORE every piece (Home's red combat glow: "behind everything",
     * user 2026-09-29). The host draws whatever it likes into (0, 0, w, h); null = nothing. Set it to a new
     * lambda (or null) to repaint.
     */
    var groundPainter: ((c: Canvas, w: Int, h: Int) -> Unit)? = null
        set(v) { field = v; invalidate() }

    private var legacy: Array<Btn> = emptyArray()
    private var legacyState: Array<St> = emptyArray()

    private var lastRxMs = 0L           // elapsedRealtime of the last hud / hudS / legacy doc
    private var layoutAtMs = 0L

    // ---- cooldown memory, per (state layer, position): app-clock seconds
    private val exStart = DoubleArray(STATE_LAYERS * 8) { Double.NaN }
    private val exDur = DoubleArray(STATE_LAYERS * 8) { Double.NaN }
    private val estStart = DoubleArray(STATE_LAYERS * 8) { Double.NaN }
    private val estDur = DoubleArray(STATE_LAYERS * 8) { Double.NaN }
    private val prevCd = IntArray(STATE_LAYERS * 8) { -1 }
    private val lastDu = HashMap<Int, Double>()        // spell id (items: -id) -> last plain duration

    // ---- game clock -> app clock: app = game + clockOff (min over receipts = least latency)
    private var clockOff = Double.NaN
    private var clockHighStreak = 0

    // ---- controller (PadInput) and layer
    private var padLt = false; private var padRt = false
    /** p-grow-glitch: ZL or ZR held now (this view's own controller reader): a B then is a layer's ability, not "shrink" */
    fun triggerHeld(): Boolean = padLt || padRt
    private var padLb = false; private var padRb = false
    private var padOk = true
    private var layer = N
    private var focusAtMs = -10_000L
    /** D-pad + face buttons held, as PadInput slot bits (bit n = drawn position n) */
    private var held = 0
    /** elapsedRealtime of each position's last physical press (drives the press flash) */
    private val pressAtMs = LongArray(8) { -10_000L }

    // ---- touch (pressed look only; nothing is injected in v1)
    private var pressedPos = -1
    private var pressedUntilMs = 0L

    // ---- room visibility (drives the controller reader only)
    private var roomVisible = false
    private var winVisible = false
    private val visCheck = Runnable { checkVisible() }

    // ---- settings
    private var preset = CastSettings.ButtonLayout.NINTENDO
    private var estimate = false
    private var nameLb = "L"; private var nameRb = "R"
    private var nameLt = "ZL"; private var nameRt = "ZR"
    private val layerLabel = arrayOf("Base", "ZL", "ZR", "ZL+ZR", "Friendly", "Enemy", "Shortcuts")
    private var faceLetters = NINTENDO_LETTERS
    /** which Blizzard position each drawn slot shows: identity, or the Nintendo face swap */
    private var srcOf = IDENTITY

    private val ready: Boolean
    private val pad: PadInput
    private val settingsListener: () -> Unit = { refreshSettings() }

    // =================================================================== geometry (px)

    /**
     * How the bar is arranged. FULL = the Cast room (today). ROW / TAKEOVER = the Home page's bars band
     * (wow-companion-design/home/SPEC.md part 5): ROW at rest (LB, 4 D-pad, 4 face, RB in one line, display
     * only), TAKEOVER in combat (the diamonds at the room's size, LB / RB in the top corners, no LT / RT: user
     * ruling). In both band layouts the slots are placed by the spec's pixel tables; while Home animates the
     * view's rect between the two, every slot is interpolated by the view's HEIGHT (112 px ROW .. 354 px
     * TAKEOVER), so the unfold is Home's rect animation and nothing is rebuilt per frame.
     */
    /** DIAMONDS: the TAKEOVER arrangement scaled UNIFORMLY to the view (fits height and width, centred), for
     *  any size: small diamonds in Home's top band at rest, a bit bigger in combat (user, 2026-09-29). */
    enum class Layout { FULL, ROW, TAKEOVER, DIAMONDS }
    var layout: Layout = Layout.FULL
        set(v) {
            if (v == field) return
            field = v
            if (width > 0 && height > 0) applyGeometry(width, height)
            invalidate()
        }

    private var k = 0f          // px per Blizzard UI unit
    private var ox = 0f         // bar origin
    private var oy = 0f
    private var margin = 0f

    private var maskSqTight: Bitmap? = null
    private var maskSqWide: Bitmap? = null
    private var maskCiTight: Bitmap? = null
    private var maskCiWide: Bitmap? = null

    // =================================================================== Blizzard art (null = vector)

    /** one piece of art and its opaque box in bitmap px (alpha >= [INK_ALPHA]), measured once */
    private class Art(val bmp: Bitmap) {
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        var inkL = 0f; var inkT = 0f; var inkR = w; var inkB = h
        init {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
            for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
                if ((px[y * bmp.width + x] ushr 24) < INK_ALPHA) continue
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
            }
            if (r >= 0) { inkL = l.toFloat(); inkT = t.toFloat(); inkR = r + 1f; inkB = b + 1f }
        }
        val inkW get() = inkR - inkL
        val inkH get() = inkB - inkT
    }

    /** a button shape's rings and shadows (ActionBarButton.lua SetShapeToSquare / SetShapeToCircle) */
    private class FrameArt {
        var normal: Art? = null      // NormalTexture
        var pressed: Art? = null     // PushedTexture
        var disabled: Art? = null
        var selected: Art? = null    // CheckedTexture, 4 units beyond the button
        var hover: Art? = null       // HighlightTexture / SpellHighlightTexture
        var border: Art? = null      // Border (equipped: green 0.35)
        var focus: Art? = null       // *ShadowFocus, 13 units beyond the button
    }

    // main thread only; the decoding itself runs on ART_EXEC (see loadArt)
    private val artCache = HashMap<String, Art>()
    private val artMissing = HashSet<String>()      // no such file: not retried, like Icons.ui
    private val artLoading = HashSet<String>()      // handed to ART_EXEC, not back yet
    private var artWant: ArrayList<String>? = null  // set only while loadArt lists what is missing
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sq = FrameArt()
    private val ci = FrameArt()
    private var aFocusSection: Art? = null
    private var aRadBase: Art? = null; private var aRadBack: Art? = null; private var aRadNormal: Art? = null
    private var aRadSel: Art? = null; private var aRadHi: Art? = null
    // by drawn position 0..7 (D-pad L U R D, face L T R B), for the current preset
    private val aSlot = arrayOfNulls<Art>(8)
    private val aSlotP = arrayOfNulls<Art>(8)
    private val aGlyph = arrayOfNulls<Art>(8)
    private val aGlyphP = arrayOfNulls<Art>(8)
    private val aGlyphD = arrayOfNulls<Art>(8)      // Disabled: the slot's action is out of range
    private var aLb: Art? = null; private var aLbP: Art? = null
    private var aRb: Art? = null; private var aRbP: Art? = null
    private var aLt: Art? = null; private var aRt: Art? = null
    private var aL3: Art? = null; private var aR3: Art? = null
    private var aPlus: Art? = null
    private var aTrigL: Art? = null; private var aTrigR: Art? = null
    private var aTrigLGlow: Art? = null; private var aTrigRGlow: Art? = null
    private var aTrigBehind: Art? = null
    // the game's own cast bar (camelot c60 atlas, UI Art's SHEET_castbar): groove, gold flame, channel fill, spark,
    // the finish (full bar + its glow) and the interrupted bar
    private var aBarGroove: Art? = null; private var aBarFill: Art? = null; private var aBarChan: Art? = null
    private var aBarPip: Art? = null; private var aBarFull: Art? = null; private var aBarGlow: Art? = null
    private var aBarFail: Art? = null
    private var aBadgeBgL: Art? = null; private var aBadgeBgR: Art? = null
    private var aBadgeHeldL: Art? = null; private var aBadgeHeldR: Art? = null
    private var aBadgeFr: Art? = null; private var aBadgeHo: Art? = null; private var aBadgeSc: Art? = null
    private val aPage = arrayOfNulls<Art>(9)       // page i: [i*3] selected, [+1] normal, [+2] disabled
    private var aPageSBg: Art? = null; private var aPageSFrame: Art? = null

    // =================================================================== paints (reused)

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val pIcon = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pArt = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pMask = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pFocusBg = Paint()
    private val pBadgeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBadgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pDash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT
    }
    private val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); color = Theme.gold
    }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val pGlyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textAlign = Paint.Align.CENTER
    }
    private val pCount = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT; color = Color.WHITE
    }
    private val pCountEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT; color = Color.BLACK
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }
    /** stale stream or inactive gamepad HUD: the whole bar goes grey */
    private val greyFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    private val pGrey = Paint().apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        alpha = 150
    }

    // icon tints (Blizzard's vertex colours), with and without the 50% combat-cooldown dim
    private val cfUnusable = LightingColorFilter(0x666666, 0)        // 0.4
    private val cfMana = LightingColorFilter(0x8080FF, 0)            // 0.5, 0.5, 1.0
    private val cfDim = LightingColorFilter(0x808080, 0)             // 0.5
    private val cfUnusableDim = LightingColorFilter(0x333333, 0)
    private val cfManaDim = LightingColorFilter(0x404080, 0)
    /** Border:SetVertexColor(0, 1, 0, 0.35) for an equipped item (alpha set on the paint) */
    private val cfEquipped = LightingColorFilter(0x00FF00, 0)
    /**
     * out of range with no Disabled glyph art loaded: the glyph goes red. An app stand-in; the HUD
     * itself switches the glyph to its Disabled art (drawn instead whenever it is on the device).
     */
    private val cfRange = LightingColorFilter(0xFF5A48, 0x200000)

    private val rect = RectF()
    private val shaderMatrix = Matrix()
    private val shaderCache = HashMap<Int, BitmapShader>()   // fileID * 2 + circle

    // normalized vector art, built once (drawn with canvas translate + scale)
    private val pathArrow = Array(4) { Path() }
    private val pathPsTri = Path()
    private val pathJumpTri = Path()
    private val pathJumper = Path()
    private val pathHand = Path()
    private val pathSack = Path()
    private val pathStar = Path()
    private val pathBadgeL = Path()
    private val pathBadgeR = Path()
    private val pathPad = Path()

    private val tick = Runnable { invalidate() }
    private val staleTick = Runnable { invalidate() }
    private val iconRetry = Runnable { resolveIcons(); invalidate() }

    init {
        CastSettings.init(context)
        buildPaths()
        pad = PadInput(
            onPad = { lt, rt, lb, rb -> onPad(lt, rt, lb, rb) },
            onStatus = { ok, _ -> padOk = ok; invalidate() },
            onButtons = { mask -> onButtons(mask) },
            onStick = { sec -> if (sec != stickSector) { stickSector = sec; invalidate() } },
        )
        ready = true
        refreshSettings()
    }

    // =================================================================== data in

    /** The hub's `actionbar` object. Merges hud / hudS / legacy slots; main thread. */
    fun update(actionbar: JSONObject?) {
        if (actionbar == null) return
        if (Looper.myLooper() != Looper.getMainLooper()) { post { update(actionbar) }; return }
        val now = SystemClock.elapsedRealtime()
        var fresh = false
        actionbar.optJSONObject("hud")?.let { parseHud(it, now); fresh = true }
        actionbar.optJSONObject("hudS")?.let { parseState(it); fresh = true }
        // the legacy keyboard slots only matter until the first layout; ignored after that
        if (hud == null) actionbar.optJSONArray("slots")?.let { parseLegacy(it); fresh = true }
        else if (legacy.isNotEmpty()) { legacy = emptyArray(); legacyState = emptyArray() }
        if (fresh) {
            lastRxMs = now
            removeCallbacks(staleTick)
            postDelayed(staleTick, STALE_MS + 50)
        }
        invalidate()
    }

    private fun parseHud(o: JSONObject, now: Long) {
        val h = Hud()
        h.ok = jb(o.opt("ok"), false)
        h.page = ji(o.opt("page"), 1)
        o.optJSONArray("pages")?.let { a -> for (i in 0 until min(3, a.length())) h.pages[i] = jb(a.opt(i), false) }
        val hid = jbOrNull(o.opt("hidden"))
        h.hidden = hid == true
        h.hiddenKnown = hid != null
        o.optJSONObject("cv")?.let { cv -> h.swapT = jb(cv.opt("swapT"), false) }
        o.optJSONObject("L")?.let { l ->
            for (li in 0 until LAYERS) {
                val arr = l.optJSONArray(LAYER_KEYS[li]) ?: continue
                for (j in 0 until min(8, arr.length())) {
                    val bo = arr.optJSONObject(j) ?: continue
                    val b = parseBtn(bo, j + 1)
                    if (b.p !in 1..8) continue
                    h.layers[li][b.p - 1] = b
                    if (li < STATE_LAYERS) h.indexOfPos[li][b.p - 1] = j
                }
            }
        }
        o.optJSONObject("ov")?.let { ov -> for (li in 0 until STATE_LAYERS) h.ov[li] = str(ov.opt(LAYER_KEYS[li])) }
        o.optJSONObject("ca")?.let { ca -> h.caL = parseRadial(ca.optJSONObject("l")); h.caR = parseRadial(ca.optJSONObject("r")) }
        // f-class-radial: the game's stance / pet bar placement, read only (Settings > Home shows it)
        o.optJSONObject("bars")?.let { b -> GameBars.note(b.optJSONObject("st"), b.optJSONObject("ps")) }

        // a position whose action changed forgets its cooldown memory
        val old = hud
        for (li in 0 until STATE_LAYERS) for (pos in 0 until 8) {
            val nb = h.layers[li][pos]
            if (nb == null || !nb.same(old?.layers?.get(li)?.get(pos))) clearMemory(li * 8 + pos)
        }
        hud = h
        layoutAtMs = now
        applyLive()                 // the layout carries the live buttons bare; re-dress them
        resolveIcons()
        setLayer(computeLayer())
    }

    /**
     * hudS.F: the live look of the scripted buttons that follow the target / soft target / auras
     * (n5 Interact, n6 Inspect, fr6/ho6 marker, fr7/ho7 assist, sc6 buffs), keyed "<layer><p>" =
     * {e: 0|1, i?: icon}. The addon keeps them OUT of the layout so a tab-target costs a small state
     * doc instead of a full snapshot; the layout sends them as fixed, enabled and icon-less.
     */
    private var liveF: JSONObject? = null

    private fun applyLive() {
        val h = hud ?: return
        val f = liveF ?: return
        val keys = f.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val cut = key.indexOfFirst { it.isDigit() }
            if (cut <= 0) continue
            val li = LAYER_KEYS.indexOf(key.substring(0, cut))
            val p = key.substring(cut).toIntOrNull() ?: continue
            if (li < 0 || p !in 1..8) continue
            val b = h.layers[li][p - 1] ?: continue
            val o = f.optJSONObject(key) ?: continue
            b.e = ji(o.opt("e"), 1) == 1
            if (o.has("i")) {
                var icon = 0; var art: String? = null
                when (val i = o.opt("i")) {
                    is Number -> icon = i.toInt()
                    is String -> if (i.startsWith("atlas:") || i.startsWith("tex:")) art = i else icon = i.toIntOrNull() ?: 0
                }
                if (icon != b.icon || art != b.art) {
                    b.icon = icon; b.art = art; b.bmp = null; b.shader = null
                }
            }
            classify(b)
        }
    }

    private fun parseRadial(o: JSONObject?): Radial? {
        val arr = o?.optJSONArray("e") ?: return null
        val r = Radial()
        for (j in 0 until min(8, arr.length())) {
            val e = arr.optJSONObject(j) ?: continue
            val i = (ji(e.opt("p"), j + 1) - 1).takeIf { it in 0..7 } ?: continue      // an older add-on: in order
            r.has[i] = true
            r.names[i] = str(e.opt("n")) ?: ""
            r.icons[i] = when (val ic = e.opt("i")) { is Number -> ic.toInt(); else -> 0 }
            // a pet command whose mode is on, or a pet spell that casts itself: the gold ring (UI Art)
            r.lit[i] = ji(e.opt("act"), 0) == 1 || ji(e.opt("auto"), 0) == 1
            r.cell[i] = ji(e.opt("c"), -1).takeIf { it in 0..39 }
            r.auto[i] = ji(e.opt("ac"), -1).takeIf { it in 0..39 }
        }
        return r
    }

    private fun parseBtn(o: JSONObject, defP: Int): Btn {
        val b = Btn()
        b.p = ji(o.opt("p"), defP)
        b.s = ji(o.opt("s"), 0)
        b.k = str(o.opt("k")) ?: ""
        b.id = ji(o.opt("id"), 0)
        when (val i = o.opt("i")) {
            is Number -> b.icon = i.toInt()
            is String -> if (i.startsWith("atlas:") || i.startsWith("tex:")) b.art = i else b.icon = i.toIntOrNull() ?: 0
        }
        b.fx = str(o.opt("fx"))
        b.ct = ji(o.opt("ct"), -1)
        b.e = jb(o.opt("e"), true)
        // the companion's own D-pad commands (Spellbook places "DS Bar Left / Open / Right" in EMPTY LT+RT slots,
        // user pick 2026-10-02): marked so the player sees which buttons drive the bottom screen
        b.ds = b.k == "macro" && (str(o.opt("n")) ?: "").startsWith(DS_MACRO_PREFIX)
        classify(b)
        return b
    }

    /**
     * The bar Home button's swirl WITHOUT its stone (user 10-06: "remove the stone circle from the swirl but keep it looking just
     * like it does now"): the same picture, with every pixel's alpha keyed on how blue OR how bright it is, so the dark stone
     * goes and the painted swirl, its glow and its rotation stay exactly as on the bar. Made once per picture, then cached.
     */
    private fun swirlCut(name: String): Bitmap? {
        swirlCache[name]?.let { return it }
        val src = UiKit.tex(name) ?: return null
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            val a = p ushr 24; val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bl = p and 255
            val blue = ((bl - max(r, g) - 8) * 5).coerceIn(0, 255)
            val lum = (((r * 30 + g * 59 + bl * 11) / 100 - 70) * 4).coerceIn(0, 255)
            val na = max(blue, lum) * a / 255
            px[i] = (na shl 24) or (p and 0xFFFFFF)
        }
        val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        swirlCache[name] = out
        return out
    }
    private val swirlCache = HashMap<String, Bitmap>()

    /** the companion mark on a "DS Bar" slot: a dark gold-rimmed disc with two small stacked screens in it */
    private fun drawDsMark(c: Canvas, cx: Float, cy: Float, r: Float) {
        pFill.color = Color.argb(230, 20, 16, 10)
        c.drawCircle(cx, cy, r, pFill)
        pStroke.color = Theme.gold; pStroke.strokeWidth = max(1f, r * 0.14f)
        c.drawCircle(cx, cy, r, pStroke)
        // two screens, top and bottom (the Thor), the lower one filled: "this drives the bottom screen"
        val w = r * 1.0f; val h = r * 0.42f; val g = r * 0.12f
        pStroke.strokeWidth = max(1f, r * 0.11f)
        rect.set(cx - w / 2f, cy - g / 2f - h, cx + w / 2f, cy - g / 2f)
        c.drawRect(rect, pStroke)
        pFill.color = Theme.gold
        rect.set(cx - w / 2f, cy + g / 2f, cx + w / 2f, cy + g / 2f + h)
        c.drawRect(rect, pFill)
    }

    /** pick the vector stand-in for a button whose art is an atlas / path we cannot load */
    private fun classify(b: Btn) {
        val a = b.art?.lowercase() ?: ""
        b.unable = a.contains("unable")
        b.special = when (b.fx) {
            "inspect" -> SP_INSPECT
            "back" -> SP_BACK
            "jump" -> SP_JUMP
            "interact" -> if (a.contains("loot")) SP_LOOT else SP_HAND
            else -> when {
                a.isEmpty() -> SP_NONE
                a.contains("hostile") -> SP_RETICLE_HOSTILE
                a.contains("friendly") -> SP_RETICLE_FRIENDLY
                a.contains("shortcut") -> SP_SHORTCUTS
                a.contains("raidtarget") || a.contains("marker") -> SP_STAR
                a.contains("quest") -> SP_BANG
                a.contains("left") -> SP_ARROW_L
                a.contains("right") -> SP_ARROW_R
                a.contains("down") -> SP_ARROW_D
                a.contains("-up") || a.contains("_up") || a.contains("arrowup") -> SP_ARROW_U
                a.contains("loot") -> SP_LOOT
                a.contains("jump") -> SP_JUMP
                a.contains("exit") -> SP_BACK
                a.contains("inspect") -> SP_INSPECT
                else -> SP_DOT
            }
        }
    }

    private fun parseState(o: JSONObject) {
        val g = jd(o.opt("g"))
        noteGameClock(g)
        // b-combat-bar-states: the In-combat spells' state by spell id (hudS.fs; absent = none known)
        fightStates.clear()
        o.optJSONArray("fs")?.let { arr ->
            for (j in 0 until arr.length()) {
                val so = arr.optJSONObject(j) ?: continue
                val id = ji(so.opt("i"), 0).takeIf { it > 0 } ?: continue
                fightStates[id] = St().apply { u = ji(so.opt("u"), 1); m = ji(so.opt("m"), 0); r = ji(so.opt("r"), -1); chk = ji(so.opt("chk"), 0) }
                fightCt[id] = ji(so.opt("ct"), -1)
            }
        }
        autoAttack = jb(o.opt("aa"), false)
        val sw = o.optJSONObject("sw")
        val ss = if (sw != null) toApp(jd(sw.opt("s"))) else Double.NaN
        if (sw == null || ss.isNaN()) { swingKind = -1; swingStartMs = Double.NaN }
        else {
            val ns = ss * 1000.0
            // a NEW swing while the last one had filled: the restart flash (grey)
            if (!swingStartMs.isNaN() && ns > swingStartMs + 1.0) flash(FLASH_SWING)
            swingKind = ji(sw.opt("k"), 0); swingStartMs = ns; swingDurMs = jd(sw.opt("d")) * 1000.0
        }
        // the cast bar (hudS.cs while a cast / channel runs; hudS.ce = how it ended, 2 s)
        val cs = o.optJSONObject("cs")
        val cs0 = if (cs != null) toApp(jd(cs.opt("s"))) else Double.NaN
        val cs1 = if (cs != null) toApp(jd(cs.opt("e"))) else Double.NaN
        if (cs == null || cs0.isNaN() || cs1.isNaN()) { castStartMs = Double.NaN }
        else {
            castChannel = str(cs.opt("k")) == "h"; castStartMs = cs0 * 1000.0; castEndMs = cs1 * 1000.0
            // b-cast-bar-late: the game's start lands; the predicted bar (if any) hands over; press -> real start, logged once
            val pressAt = predPressAt
            if (pressAt > 0L) {
                val now = SystemClock.elapsedRealtime()
                runCatching { DiagLog.event("cast", "bar: press -> real start ${now - pressAt} ms (${if (predOn) "predicted at the press" else "no prediction"})") }
                predPressAt = 0L
            }
            predOn = false
        }
        o.optJSONObject("ce")?.let { ce ->
            val t = jd(ce.opt("t"))
            if (!t.isNaN() && t != castEndSeen) {
                castEndSeen = t
                val at = toApp(t)
                val age = if (at.isNaN()) 0.0 else SystemClock.elapsedRealtime() - at * 1000.0
                if (age < 800.0) flash(if (ji(ce.opt("x"), 1) == 1) FLASH_CAST else FLASH_FAIL)
            }
            castStartMs = Double.NaN
        }
        val bu = ji(o.opt("bu"), 1) != 0
        if (bu != barsUsable) { barsUsable = bu; setLayer(computeLayer()) }
        liveF = o.optJSONObject("F")
        if (hud != null) { applyLive(); resolveIcons() }
        val l = o.optJSONObject("L")
        val h = hud
        for (li in 0 until STATE_LAYERS) {
            val arr = l?.optJSONArray(LAYER_KEYS[li])
            for (j in 0 until 8) {
                val st = state[li][j]
                st.reset()
                val so = arr?.optJSONObject(j) ?: continue
                st.u = ji(so.opt("u"), 1)
                st.m = ji(so.opt("m"), 0)
                st.r = ji(so.opt("r"), -1)
                st.cd = ji(so.opt("cd"), 0)
                st.st = jd(so.opt("st"))
                st.du = jd(so.opt("du"))
                st.ch = ji(so.opt("ch"), -1)
                st.cnt = ji(so.opt("cnt"), -1)
                st.gl = ji(so.opt("gl"), 0)
                st.chk = ji(so.opt("chk"), 0)
                st.eq = ji(so.opt("eq"), 0)
            }
            // cooldown memory, walked by POSITION so it lines up with the layout
            for (pos in 0 until 8) {
                val j = h?.indexOfPos?.get(li)?.get(pos) ?: pos
                val st = state[li][j]
                val b = h?.layers?.get(li)?.get(pos)
                st.countText = countText(st, b)
                val key = li * 8 + pos
                if (st.cd == 1) {
                    if (!st.st.isNaN() && !st.du.isNaN() && st.du > 0.0) {
                        // plain timing: the real sweep, exact
                        exStart[key] = toApp(st.st); exDur[key] = st.du
                        estStart[key] = Double.NaN
                        if (b != null && b.id != 0) lastDu[durKey(b)] = st.du
                    } else if (prevCd[key] == 0 && !g.isNaN()) {
                        // the timing is secret: remember when it started (for the optional estimate)
                        estStart[key] = toApp(g)
                        estDur[key] = if (b != null && b.id != 0) lastDu[durKey(b)] ?: Double.NaN else Double.NaN
                    }
                } else {
                    exStart[key] = Double.NaN; estStart[key] = Double.NaN
                }
                prevCd[key] = st.cd
            }
        }
        hasState = true
    }

    private fun countText(st: St, b: Btn?): String? = when {
        st.cnt >= 0 -> if (st.cnt > 9999) "*" else st.cnt.toString()
        st.ch >= 0 && (b == null || b.k != "item") -> st.ch.toString()
        else -> null
    }

    private fun durKey(b: Btn) = if (b.k == "item") -b.id else b.id

    private fun clearMemory(key: Int) {
        exStart[key] = Double.NaN; exDur[key] = Double.NaN
        estStart[key] = Double.NaN; estDur[key] = Double.NaN
        prevCd[key] = -1
    }

    /** the pre-HUD addon's keyboard slots 1-12, shown only until the first `hud` arrives */
    private fun parseLegacy(a: JSONArray) {
        val bs = ArrayList<Btn>(); val ss = ArrayList<St>()
        for (i in 0 until min(12, a.length())) {
            val o = a.optJSONObject(i) ?: continue
            val b = Btn()
            b.p = 1
            b.k = str(o.opt("kind")) ?: "spell"
            b.id = ji(o.opt("id"), 0)
            b.icon = ji(o.opt("icon"), 0)
            b.key = str(o.opt("key"))
            bs.add(b)
            ss.add(St().apply { u = if (jb(o.opt("usable"), true)) 1 else 0 })
        }
        legacy = bs.toTypedArray(); legacyState = ss.toTypedArray()
        resolveIcons()
    }

    /** game time -> app time; min(app - game) over receipts is the offset with the least latency */
    private fun noteGameClock(g: Double) {
        if (g.isNaN()) return
        val off = SystemClock.elapsedRealtime() / 1000.0 - g
        when {
            clockOff.isNaN() || off < clockOff -> { clockOff = off; clockHighStreak = 0 }
            off - clockOff > CLOCK_REBASE_S -> {
                // several late-looking docs in a row: the game clock itself restarted
                if (++clockHighStreak >= 3) { clockOff = off; clockHighStreak = 0 }
            }
            else -> clockHighStreak = 0
        }
    }

    private fun toApp(gameT: Double) = if (clockOff.isNaN()) Double.NaN else gameT + clockOff

    /** fetch bitmaps and build masked-icon shaders (never in onDraw) */
    private fun resolveIcons() {
        removeCallbacks(iconRetry)
        var missing = false
        val h = hud
        if (h != null) {
            for (li in 0 until LAYERS) for (pos in 0 until 8) {
                val b = h.layers[li][pos] ?: continue
                if (!resolve(b, pos >= 4)) missing = true
            }
        }
        for (b in legacy) if (!resolve(b, false)) missing = true
        // the pack may not be on the device yet: Icons retries it itself, we re-ask
        if (missing && !Icons.ready()) postDelayed(iconRetry, 5000)
        if (!Icons.ready()) iconAbsent.clear()
    }

    /** the pack was rebuilt since the icons were last asked for: what it lacked then may be in it now (D's test18 proof) */
    private var iconGen = -1
    private fun iconGenCheck() {
        val g = Icons.generation
        if (g != iconGen) { iconGen = g; iconAbsent.clear() }
    }

    // ---- icons decoded off the UI thread (Icons.get is thread-safe: synchronized LruCache + pack, a duplicated buffer)
    private val iconReady = HashMap<Int, Bitmap>()      // main thread
    private val iconAbsent = HashSet<Int>()             // main thread: the pack has no such icon (retried with the pack)
    private val iconWanted = LinkedHashSet<Int>()       // main thread: queued for the next batch
    private var iconBatchQueued = false

    private fun requestIcon(id: Int) {
        iconGenCheck()
        if (id in iconAbsent || !iconWanted.add(id) || iconBatchQueued) return
        iconBatchQueued = true
        mainHandler.post {                              // one batch per main-loop turn (not View.post: runs detached too)
            iconBatchQueued = false
            val ids = iconWanted.toIntArray(); iconWanted.clear()
            if (ids.isEmpty()) return@post
            ART_EXEC.execute {
                val got = arrayOfNulls<Bitmap>(ids.size)
                for (i in ids.indices) got[i] = try { Icons.get(ids[i]) } catch (_: Throwable) { null }
                mainHandler.post {
                    var any = false
                    for (i in ids.indices) {
                        val bmp = got[i]
                        if (bmp != null) { iconReady[ids[i]] = bmp; any = true }
                        else if (Icons.ready()) iconAbsent.add(ids[i])   // the pack is up and lacks it
                    }
                    if (any) { resolveIcons(); fightBtns.fill(null); roomBtns.fill(null); cmdBtns.fill(null); dpadFightBtns.fill(null); invalidate() }
                }
            }
        }
    }

    /** false when the icon exists but could not be loaded yet */
    private fun resolve(b: Btn, circle: Boolean): Boolean {
        if (b.icon <= 0 || b.empty) return true
        if (b.bmp == null) {
            // NEVER decode on the UI thread (Map's ANR 2026-10-02: a replayed document backlog decoded every icon in
            // MainActivity.handleMessage). A decoded icon is taken from [iconReady]; a missing one is queued for
            // ART_EXEC and the view re-resolves when it lands. An icon the pack doesn't have stays missing.
            val got = iconReady[b.icon]
            if (got != null) b.bmp = got
            else { requestIcon(b.icon); return true }
        }
        val bmp = b.bmp ?: return false
        if (b.shader == null && k > 0f) {
            val ck = b.icon * 2 + if (circle) 1 else 0
            b.shader = shaderCache.getOrPut(ck) {
                BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                    // icon art carries a baked square bevel; action buttons sample only the inner
                    // 0.07..0.93 of it (the standard texcoord trim), then the square / circle mask
                    val iconPx = ((if (circle) 38f else 40f) - 6f) * k
                    val cropX = bmp.width * ICON_CROP
                    val cropY = bmp.height * ICON_CROP
                    shaderMatrix.setScale(iconPx / (bmp.width - 2f * cropX), iconPx / (bmp.height - 2f * cropY))
                    shaderMatrix.preTranslate(-cropX, -cropY)
                    it.setLocalMatrix(shaderMatrix)
                }
            }
        }
        return true
    }

    // =================================================================== controller -> layer

    private fun onPad(lt: Boolean, rt: Boolean, lb: Boolean, rb: Boolean) {
        padLt = lt; padRt = rt; padLb = lb; padRb = rb
        noteClassRadial()                                                  // f-class-radial: Home's mirror
        noteJoin(SystemClock.elapsedRealtime())
        setLayer(computeLayer())
        invalidate()
    }

    /**
     * Read-only listener on the SAME controller reader (for Home's B: one getevent, not two). Called on the
     * main thread with PadInput's slot mask (bit n = PadInput.SLOT_x held) on every change, only while this
     * view's reader runs (the view attached and shown). Nothing is sent to the game.
     */
    var onPadButtons: ((held: Int) -> Unit)? = null

    /** PadInput: the D-pad / face buttons held, bit n = drawn position n; main thread, on change */
    private fun onButtons(mask: Int) {
        onPadButtons?.invoke(mask)
        val down = mask and held.inv()
        if (down != 0) {
            val now = SystemClock.elapsedRealtime()
            for (pos in 0 until 8) if (down and (1 shl pos) != 0) { pressAtMs[pos] = now; predictCast(pos, now) }
        }
        held = mask and 0xFF
        invalidate()
    }

    // ---------------------------------------------------------------- b-cast-bar-late: the predictive cast bar
    /** the user on 1.0.8-test2: "The cast bar starts really delayed at like 25%, is there a way that we can like predictivly start it up
     *  and see if it failed so that its not starting late?" A press of a button whose spell has a cast time starts the bar AT ONCE in a
     *  pending look; the game's real start (hudS.cs) takes over; no start within [CastPredict.CONFIRM_MS] drops it quietly (a fail
     *  shows the game's own red flash, hudS.ce). Instant spells never start one. */
    private var predOn = false
    private var predPressAt = 0L
    private var predStartMs = 0.0
    private var predEndMs = 0.0
    private fun predictCast(pos: Int, now: Long) {
        val h = hud ?: return
        if (!barsUsable || !bottomLine) return
        val src = srcOf[pos]
        val li = layer
        val fb = dpadFightOverride(li, src) ?: fightOverride(li, src)
        val ct: Int; val usable: Boolean
        if (fb != null) {
            val id = fightIds?.get(src + 1) ?: return
            ct = fightCt[id] ?: -1
            val st = fightStates[id]
            usable = st == null || (st.u == 1 && st.m == 0)
        } else {
            val b = h.layers.getOrNull(li)?.getOrNull(src) ?: return
            if (b.k != "spell" && b.k != "macro") return
            ct = b.ct
            val st = if (li < STATE_LAYERS && hasState) state[li][h.indexOfPos[li][src]] else null
            usable = st == null || (st.u == 1 && st.m == 0 && st.cd == 0)
        }
        val casting = !castStartMs.isNaN() && now < castEndMs
        if (ct > 0) predPressAt = now                                     // the latency line, predicted or not
        if (!CastPredict.start(ct, casting, usable)) return
        predOn = true; predStartMs = now.toDouble(); predEndMs = now.toDouble() + ct
        invalidate()
    }

    private fun computeLayer(): Int {
        // a window (NPC dialog, bags, any menu) owns the pad: the game ignores the modifiers
        // (holdbindings.lua IsLeft/RightModifierDown, targeting) and shows the base bar collapsed
        if (!barsUsable) return N
        val swap = hud?.swapT == true
        return when {
            padLt && padRt -> LR
            padLt -> LT
            padRt -> RT
            padLb && padRb -> SC
            padLb -> if (swap) HO else FR
            padRb -> if (swap) FR else HO
            else -> N
        }
    }

    private fun setLayer(l: Int) {
        if (l == layer) return
        layer = l
        focusAtMs = SystemClock.elapsedRealtime()
        pressedPos = -1
    }

    // =================================================================== settings

    private fun refreshSettings() {
        if (!ready) return
        preset = CastSettings.buttonLayout
        estimate = CastSettings.combatCooldowns == CastSettings.CombatCooldowns.ESTIMATE
        pad.triggerSwap = CastSettings.triggerSwap
        when (preset) {
            CastSettings.ButtonLayout.NINTENDO -> { nameLb = "L"; nameRb = "R"; nameLt = "ZL"; nameRt = "ZR" }
            CastSettings.ButtonLayout.PLAYSTATION -> { nameLb = "L1"; nameRb = "R1"; nameLt = "L2"; nameRt = "R2" }
            CastSettings.ButtonLayout.XBOX -> { nameLb = "LB"; nameRb = "RB"; nameLt = "LT"; nameRt = "RT" }
        }
        faceLetters = if (preset == CastSettings.ButtonLayout.XBOX) XBOX_LETTERS else NINTENDO_LETTERS
        // NINTENDO swaps only the PICTURES: each face action is drawn under the letter it has on an Xbox
        // pad (Jump under A on the right, Back under B at the bottom, Interact under X on top, Inspect under
        // Y on the left). The D-pad, the game and every binding stay exactly as they are.
        srcOf = if (preset == CastSettings.ButtonLayout.NINTENDO) NINTENDO_FACE_SWAP else IDENTITY
        layerLabel[LT] = nameLt
        layerLabel[RT] = nameRt
        layerLabel[LR] = "$nameLt+$nameRt"
        loadArt()
        invalidate()
    }

    // =================================================================== art loading (off the main thread)

    /**
     * A cached piece of art, or null. Never decodes: while [loadArt] is listing ([artWant] set), a
     * name that is neither cached, known missing nor already loading is noted for the loader.
     */
    private fun art(name: String): Art? {
        artCache[name]?.let { return it }
        if (name !in artMissing && name !in artLoading) artWant?.add(name)
        return null
    }

    /**
     * Assign what is cached now (the rest draws as vector), then decode what is not on [ART_EXEC]:
     * about 80 PNGs from /sdcard plus an ink scan each, too slow for the main thread. The results
     * come back on the main thread, where the art is assigned again and the view redrawn. Icons.ui
     * is not used here: its caches are main-thread only (the Bags room shares them).
     */
    private fun loadArt() {
        val want = ArrayList<String>()
        artWant = want
        try { applyArt() } finally { artWant = null }
        if (want.isEmpty()) return
        artLoading.addAll(want)
        ART_EXEC.execute {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            } catch (_: Throwable) {}
            val got = arrayOfNulls<Art>(want.size)
            for (i in want.indices) got[i] = decodeArt(want[i])
            mainHandler.post {
                for (i in want.indices) {
                    artLoading.remove(want[i])
                    val a = got[i]
                    if (a != null) artCache[want[i]] = a else artMissing.add(want[i])
                }
                applyArt()
                invalidate()
            }
        }
    }

    private fun loadFrame(f: FrameArt, shape: String) {
        f.normal = art("frame_${shape}_normal")
        f.pressed = art("frame_${shape}_pressed")
        f.disabled = art("frame_${shape}_disabled")
        f.selected = art("frame_${shape}_selected")
        f.hover = art("frame_${shape}_hover")
        f.border = art("frame_${shape}_iconborder")
        f.focus = art("frame_${shape}_focus")
    }

    /**
     * The preset picks the glyph set and the face slot art. Face files are listed by PHYSICAL
     * position (left, top, right, bottom). Nintendo's slot art is Blizzard's "Reverse" set: the
     * Xbox letter art swapped (left y, top x, right a, bottom b), matching the Y/X/A/B print.
     * Main thread; assigns cached art only (see [loadArt]).
     */
    private fun applyArt() {
        loadFrame(sq, "square")
        loadFrame(ci, "circle")
        aFocusSection = art("frame_focus_section")
        val pk: String; val faces: Array<String>; val slots: Array<String>
        when (preset) {
            CastSettings.ButtonLayout.NINTENDO -> {
                pk = "nintendo"; faces = arrayOf("y", "x", "a", "b")
                slots = arrayOf("xbox_y", "xbox_x", "xbox_a", "xbox_b")
            }
            CastSettings.ButtonLayout.XBOX -> {
                pk = "xbox"; faces = arrayOf("x", "y", "b", "a")
                slots = arrayOf("xbox_x", "xbox_y", "xbox_b", "xbox_a")
            }
            CastSettings.ButtonLayout.PLAYSTATION -> {
                pk = "playstation"; faces = arrayOf("square", "triangle", "circle", "cross")
                slots = arrayOf("ps_square", "ps_triangle", "ps_circle", "ps_cross")
            }
        }
        for (i in 0 until 4) {
            aSlot[i] = art("slot_dpad_${DPAD_NAMES[i]}")
            aSlotP[i] = art("slot_dpad_${DPAD_NAMES[i]}_pressed")
            // the Thor has a CROSS D-pad and the game's HUD draws crosses: Switch's Joy-Con dots never
            val dk = if (preset == CastSettings.ButtonLayout.NINTENDO) "xbox" else pk
            aGlyph[i] = art("glyph_${dk}_dpad_${DPAD_NAMES[i]}")
            aGlyphP[i] = art("glyph_${dk}_dpad_${DPAD_NAMES[i]}_pressed")
            aGlyphD[i] = art("glyph_${dk}_dpad_${DPAD_NAMES[i]}_disabled")
            aSlot[4 + i] = art("slot_face_${slots[i]}")
            aSlotP[4 + i] = art("slot_face_${slots[i]}_pressed")
            aGlyph[4 + i] = art("glyph_${pk}_${faces[i]}")
            aGlyphP[4 + i] = art("glyph_${pk}_${faces[i]}_pressed")
            aGlyphD[4 + i] = art("glyph_${pk}_${faces[i]}_disabled")
        }
        aLb = art("glyph_${pk}_lb"); aLbP = art("glyph_${pk}_lb_pressed")
        aRb = art("glyph_${pk}_rb"); aRbP = art("glyph_${pk}_rb_pressed")
        aLt = art("glyph_${pk}_lt"); aRt = art("glyph_${pk}_rt")
        aL3 = art("glyph_${pk}_l3"); aR3 = art("glyph_${pk}_r3")
        aPlus = art("glyph_common_plus")
        aTrigL = art("trig_${pk}_lt"); aTrigR = art("trig_${pk}_rt")
        aTrigLGlow = art("trig_${pk}_lt_glow"); aTrigRGlow = art("trig_${pk}_rt_glow")
        aTrigBehind = art("trig_glow_behind")
        aBarGroove = art("castbar_background"); aBarFill = art("castbar_filling_standard")
        aBarChan = art("castbar_filling_channel"); aBarPip = art("castbar_pip")
        aBarFull = art("castbar_full_standard"); aBarGlow = art("castbar_full_glow_standard")
        aBarFail = art("castbar_interrupted")
        // The shoulder plates stay the ORIGINAL vector plates (user ruling 14:1x: Blizzard's plate art came out
        // distorted; "keep the old ones and just swap the sides"). See drawShoulders.
        aBadgeBgL = null; aBadgeBgR = null; aBadgeHeldL = null; aBadgeHeldR = null
        aBadgeFr = art("badge_friendly"); aBadgeHo = art("badge_hostile"); aBadgeSc = art("badge_shortcuts")
        for (i in 0 until 3) {
            aPage[i * 3] = art("page_${i + 1}_selected")
            aPage[i * 3 + 1] = art("page_${i + 1}_normal")
            aPage[i * 3 + 2] = art("page_${i + 1}_disabled")
        }
        aPageSBg = art("page_s_bg"); aPageSFrame = art("page_s_frame")
        aRadBase = art("radial_base"); aRadBack = art("radial_backdrop"); aRadNormal = art("radial_normal")
        aRadSel = art("radial_selected"); aRadHi = art("radial_highlight")
    }

    // =================================================================== visibility + hide/show

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        CastSettings.onChange(settingsListener)
        refreshSettings()
        winVisible = windowVisibility == VISIBLE
        checkVisible()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(visCheck)
        setRoomVisible(false)          // app closing: stop the controller reader
        CastSettings.removeOnChange(settingsListener)
        removeCallbacks(tick); removeCallbacks(staleTick); removeCallbacks(iconRetry)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!ready) return
        if (changedView === this) {
            // A room switch. The hub's show() sets EVERY room GONE and then the chosen one VISIBLE
            // in one call, so re-tapping Cast flips this view GONE -> VISIBLE. Decide once, after
            // the call: a flip is then no change at all (no show + hide pair, no reader restart).
            removeCallbacks(visCheck)
            post(visCheck)
        } else {
            // an ancestor (the activity stopping): decide now
            checkVisible()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!ready) return
        winVisible = visibility == VISIBLE
        checkVisible()
    }

    private fun checkVisible() = setRoomVisible(isAttachedToWindow && winVisible && isShown)

    private fun setRoomVisible(v: Boolean) {
        if (v == roomVisible) return
        roomVisible = v
        if (v) {
            pad.start()
        } else {
            pad.stop()                  // also drops the layer back to base and releases the buttons
            pressedPos = -1
            held = 0
        }
        invalidate()
    }

    // =================================================================== touch (pressed look only)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pos = hitTest(e.x, e.y)
                if (pos >= 0) {
                    pressedPos = pos
                    pressedUntilMs = SystemClock.elapsedRealtime() + PRESS_MS
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun hitTest(x: Float, y: Float): Int {
        if (k <= 0f) return -1
        if (layout != Layout.FULL && hud != null) {
            for (pos in 0 until 8) {
                bandSlot(pos, bandBox)
                val half = bandBox[2] / 2f
                val hit = if (pos >= 4) hypot(x - bandBox[0], y - bandBox[1]) <= half
                          else Math.abs(x - bandBox[0]) <= half && Math.abs(y - bandBox[1]) <= half
                if (hit) return pos
            }
            return -1
        }
        val slop = 2f * k
        if (hud != null) {
            for (pos in 0 until 8) {
                val px = if (!barsUsable) COL_POS_X[pos] else POS_X[pos]
                val py = if (!barsUsable) COL_POS_Y[pos] else POS_Y[pos]
                val cx = ox + px * k; val cy = oy - py * k
                val half = (if (pos >= 4) 19f else 20f) * k + slop
                val hit = if (pos >= 4) hypot(x - cx, y - cy) <= half
                          else Math.abs(x - cx) <= half && Math.abs(y - cy) <= half
                if (hit) return pos
            }
            return -1
        }
        for (i in legacy.indices) {
            val cx = legacyX(i); val cy = legacyY(i)
            val half = 20f * k + slop
            if (Math.abs(x - cx) <= half && Math.abs(y - cy) <= half) return i
        }
        return -1
    }

    // =================================================================== layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        applyGeometry(w, h)
    }

    private fun applyGeometry(w: Int, h: Int) {
        margin = w * 0.028f
        val nk: Float
        if (layout == Layout.FULL) {
            // the cluster spans x -153..153 (shoulder badges included) and y -47..+85, plus a label
            // row above and a legend row below
            nk = min((w - 2f * margin) / 306f, (h - 2f * margin) / 176f)
            ox = w / 2f
            oy = h / 2f + 19f * nk          // centres the y -47..+85 box
        } else {
            // band layouts draw every slot at this fixed scale and scale the CANVAS to the slot's box:
            // a resize (Home's 200 ms unfold) changes no mask, shader or paint
            nk = BAND_K
            ox = 0f; oy = 0f
        }
        if (layout == Layout.FULL || nk != k) rebuildScale(nk)   // FULL: as before (its shader carries ox/oy)
    }

    /** everything whose pixels depend on [k]: masks, shaders, dashes, text sizes. Only when k changes. */
    private fun rebuildScale(nk: Float) {
        k = nk
        maskSqTight = buildMask(40f * k, false, 4f * k)
        maskSqWide = buildMask(40f * k, false, 13f * k)
        maskCiTight = buildMask(38f * k, true, 4f * k)
        maskCiWide = buildMask(38f * k, true, 13f * k)
        // gamepad-actionbar-focus-bg-section: 512x160 units, alpha 0.375, centred on the bar
        pFocusBg.shader = RadialGradient(0f, 0f, 256f * k,
            Color.argb(96, 70, 52, 22), Color.argb(0, 70, 52, 22), Shader.TileMode.CLAMP).also {
            it.setLocalMatrix(Matrix().apply { setScale(1f, 160f / 512f); postTranslate(ox, oy) })
        }
        pDash.pathEffect = DashPathEffect(floatArrayOf(2.4f * k, 1.8f * k), 0f)
        pBadgeFill.pathEffect = CornerPathEffect(4f)
        pBadgeStroke.pathEffect = CornerPathEffect(4f)
        pLabel.textSize = 9f * k
        pText.textSize = 7f * k
        pGlyph.textSize = 10f * k
        pCount.textSize = 13f * k
        pCountEdge.textSize = 13f * k
        pCountEdge.strokeWidth = 0.9f * k
        // shaders carry a size-dependent matrix: rebuild them
        shaderCache.clear()
        hud?.let { h2 -> for (l in h2.layers) for (b in l) b?.shader = null }
        for (b in legacy) b.shader = null
        for (b in fightBtns) b?.shader = null
        for (b in cmdBtns) b?.shader = null           // the host-driven faces / toolbar carry the old matrix too
        for (b in dpadFightBtns) b?.shader = null
        for (b in roomBtns) b?.shader = null
        for (b in setFightBtns.values) b.shader = null
        resolveIcons()
    }

    /** a soft ALPHA_8 halo around a button shape, tinted by the paint colour when drawn */
    private fun buildMask(sizePx: Float, circle: Boolean, extentPx: Float): Bitmap {
        val dim = ceil(sizePx + 4f * extentPx).toInt().coerceAtLeast(4)
        val bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ALPHA_8)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            maskFilter = BlurMaskFilter(max(1f, extentPx * 0.6f), BlurMaskFilter.Blur.NORMAL)
        }
        val c = dim / 2f
        val half = sizePx / 2f + extentPx * 0.45f
        if (circle) cv.drawCircle(c, c, half, p)
        else cv.drawRoundRect(c - half, c - half, c + half, c + half, sizePx / 7f, sizePx / 7f, p)
        return bmp
    }

    private fun legacyCols() = min(max(legacy.size, 1), 6)
    private fun legacyX(i: Int): Float {
        val cols = legacyCols()
        val row = i / 6
        val inRow = if (row == (legacy.size - 1) / 6) legacy.size - row * 6 else cols
        return ox + ((i % 6) - (inRow - 1) / 2f) * 46f * k
    }
    private fun legacyY(i: Int): Float {
        val rows = (legacy.size + 5) / 6
        return height / 2f + ((i / 6) - (rows - 1) / 2f) * 46f * k
    }

    // =================================================================== draw

    /** the game's type (UI art, fonts step). Set where the paints are USED: the kit can reach the
     *  device after this view is built, and a paint keeps the font it was given. */
    private fun fonts() {
        UiKit.asBody(pLabel); UiKit.asBody(pText)
        // counts have their own black edge (pCountEdge). pGlyph stays: it stands in for button art
        UiKit.asNumber(pCount, shadow = false); UiKit.asNumber(pCountEdge, shadow = false)
    }

    override fun onDraw(c: Canvas) {
        fonts()
        c.drawColor(Theme.ink)
        groundPainter?.invoke(c, width, height)
        if (k <= 0f) return
        val now = SystemClock.elapsedRealtime()
        var next = Long.MAX_VALUE
        val h = hud
        val stale = lastRxMs != 0L && now - lastRxMs > STALE_MS

        if (h == null) {
            if (legacy.isEmpty()) {
                pLabel.textAlign = Paint.Align.CENTER
                pLabel.color = Theme.dim
                c.drawText("Waiting", width / 2f, height / 2f, pLabel)
                pLabel.textAlign = Paint.Align.LEFT
                pLabel.color = Theme.gold
                return
            }
            if (stale) c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), pGrey)
            drawLabel(c, "Base")
            for (i in legacy.indices) {
                val pressed = i == pressedPos && now < pressedUntilMs
                next = min(next, drawSlot(c, i, legacyX(i), legacyY(i), false, legacy[i],
                    legacyState[i], -1, now, pressed, 0f, legacy[i].key ?: ""))
            }
            if (stale) c.restore()
            if (pressedPos >= 0 && now < pressedUntilMs) next = min(next, pressedUntilMs - now)
            schedule(next)
            return
        }

        // p-bars-grey (a player, a druid: "action bars stay greyed out on bottom screen. They work, just always grey?"): the whole bar
        // greys only when the stream is dead or the add-on sent no bar at all. "Not ok" alone (the game's main gamepad frame not shown,
        // e.g. while a form's or a stance's bar stands over it) with a full layout is a working bar. And a grey band still animates: it
        // used to stop scheduling, so the swing / cast line moved only when an input redrew it (p-swing-timer)
        val empty = h.layers.all { l -> l.all { it == null || it.empty } }
        val grey = stale || (!h.ok && empty)
        // p-bars-grey (1.0.8, the user's yes): what this decision saw, for a problem report (CastDiag.line)
        CastDiag.note(grey, if (lastRxMs == 0L) -1L else now - lastRxMs, h.ok, empty, barsUsable, diagEOff(h), diagUOff())
        if (grey) c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), pGrey)

        drawCollapsed = !barsUsable
        if (layout != Layout.FULL) {
            val nb = drawBand(c, h, now)
            if (grey) c.restore()
            schedule(nb)
            return
        }
        // focus background (gamepad-actionbar-focus-bg-section, 512x160 units, alpha 0.375); a collapsed
        // bar has none (actionbar.lua HideHighlight)
        val fs = aFocusSection
        if (!drawCollapsed) {
            if (fs != null) drawCentered(c, fs, ox, oy, 512f, 160f, 96)
            else c.drawRect(ox - 256f * k, oy - 80f * k, ox + 256f * k, oy + 80f * k, pFocusBg)
        }

        val li = layer
        // stance watermark (gamepad-actionbar-stance-backing, 250x115 at (0,-10))
        if (li < STATE_LAYERS && h.ov[li] == "stance") {
            pFill.color = Color.argb(204, 17, 16, 16)
            c.drawRoundRect(ox - 125f * k, oy - 47.5f * k, ox + 125f * k, oy + 67.5f * k, 10f * k, 10f * k, pFill)
        }

        drawLayerLabel(c, li, h)
        // like the game: the page tracker shows only while LB+RB is held (user ruling)
        if (padLb && padRb && !drawCollapsed) drawPageChips(c, h)
        drawShoulders(c, h)

        // the trigger glyph's glow sits under the buttons; the glyph itself is drawn after them
        val trig = li == LT || li == RT || li == LR
        if (trig) drawTriggers(c, li, now, true)

        var flashing = false
        for (pos in 0 until 8) {
            val src = srcOf[pos]                 // drawn at slot pos, taken from Blizzard position src
            val fb = dpadFightOverride(li, src) ?: fightOverride(li, src) ?: commandOverride(li, src)
            val b = fb ?: h.layers[li][src]
            val st = if (fb == null && li < STATE_LAYERS && hasState) state[li][h.indexOfPos[li][src]] else fightStateOf(fb, src)
            // pushed: a touch on the replica, or the PHYSICAL button at this position held
            // A controller press lights the PICTURE of the action the game received, not a guessed
            // physical spot: the pad reports the button by its code (A/B/X/Y = the game's Xbox slot),
            // and that slot is drawn at srcOf^-1. On Nintendo the Thor reports the printed letter, so
            // A lights Jump on the right, B Back at the bottom, X attack on top, Y Inspect on the left
            // (user report 14:1x: the physical-spot guess lit the wrong ones). Identity on other presets.
            val pressed = !quietPresses && ((pos == pressedPos && now < pressedUntilMs) || (held and (1 shl src)) != 0)
            val since = now - pressAtMs[src]
            val flash = if (!quietPresses && since >= 0L && since < PRESS_FLASH_MS) 1f - since.toFloat() / PRESS_FLASH_MS else 0f
            if (flash > 0f) flashing = true
            val px = if (drawCollapsed) COL_POS_X[pos] else POS_X[pos]
            val py = if (drawCollapsed) COL_POS_Y[pos] else POS_Y[pos]
            next = min(next, drawSlot(c, pos, ox + px * k, oy - py * k, pos >= 4,
                b, st, if (li < STATE_LAYERS) li * 8 + src else -1, now, pressed, flash, null))
        }
        if (trig) drawTriggers(c, li, now, false)
        drawLegend(c, h)
        // the class-only radial while LB+LT or RB+RT is held (Blizzard camelot; not while a window has the pad)
        if (!drawCollapsed) {
            val left = padLb && padLt
            val rad = when { left -> h.caL; padRb && padRt -> h.caR; else -> null }
            if (rad != null) drawRadial(c, rad, ox + (if (left) -75f else 70f) * k, oy, width.toFloat(), height.toFloat())
        }

        if (grey) c.restore()
        if (!padOk && roomVisible) drawPadOff(c)

        if (grey) next = Long.MAX_VALUE          // a dead stream does not animate
        // layer-switch effects: focus shadows 0.33 s, trigger glow 0.5 s; the press flash
        val animLeft = (if (trig) GLOW_BOTTOM_MS else FOCUS_MS) - (now - focusAtMs)
        if (animLeft > 0 || flashing) next = 0
        if (pressedPos >= 0 && now < pressedUntilMs) next = min(next, pressedUntilMs - now)
        schedule(next)
    }

    // =================================================================== art drawing (no allocation)

    /** the whole bitmap stretched over a px box */
    private fun drawBox(c: Canvas, a: Art, l: Float, t: Float, r: Float, b: Float,
                        alpha: Int = 255, cf: ColorFilter? = null) {
        pArt.alpha = alpha.coerceIn(0, 255)
        pArt.colorFilter = cf
        rect.set(l, t, r, b)
        c.drawBitmap(a.bmp, null, rect, pArt)
        pArt.alpha = 255
        pArt.colorFilter = null
    }

    /** the whole bitmap as a wU x hU unit box centred on (cx, cy) */
    private fun drawCentered(c: Canvas, a: Art, cx: Float, cy: Float, wU: Float, hU: Float,
                             alpha: Int = 255, cf: ColorFilter? = null) {
        val hw = wU * k / 2f; val hh = hU * k / 2f
        drawBox(c, a, cx - hw, cy - hh, cx + hw, cy + hh, alpha, cf)
    }

    /**
     * A glyph tile scaled so its OPAQUE box is inkU units tall, that box centred on (cx, cy): the
     * tiles carry ~15 px of empty margin and an offset drop shadow, so the tile centre is not the
     * glyph centre. Returns the drawn ink width in px.
     */
    private fun drawInk(c: Canvas, a: Art, cx: Float, cy: Float, inkU: Float,
                        alpha: Int = 255, cf: ColorFilter? = null): Float {
        val s = inkU * k / a.inkH
        val l = cx - (a.inkL + a.inkW / 2f) * s
        val t = cy - (a.inkT + a.inkH / 2f) * s
        drawBox(c, a, l, t, l + a.w * s, t + a.h * s, alpha, cf)
        return a.inkW * s
    }

    private fun inkWidth(a: Art, inkU: Float) = a.inkW * inkU * k / a.inkH

    /**
     * The modifier glyph 35 units under the bar centre on the LT / RT / LT+RT bars (PageUnit
     * LeftIcon / RightIcon / CenteredIcons), with Blizzard's two focus glows: GlowBottom
     * (fx-trigger-behind, 88x88, alpha 0.7 -> 0.2 over 0.5 s, stays) under it and GlowTop
     * (fx-trigger{L,R}-top, 62x62, alpha 1 -> 0 over 0.3 s) over it. [under] draws only the
     * bottom glow (behind the buttons); the second call draws the glyphs and the top glow.
     */
    private fun drawTriggers(c: Canvas, li: Int, now: Long, under: Boolean) {
        val cy = oy + 35f * k
        val l = aTrigL; val r = aTrigR
        if (l == null || r == null) {
            if (!under) drawPill(c, ox, cy, layerLabel[li], 12f)
            return
        }
        val t = (now - focusAtMs).toFloat()
        val topA = (1f - t / GLOW_TOP_MS).coerceIn(0f, 1f)
        val botA = 0.7f - 0.5f * (t / GLOW_BOTTOM_MS).coerceIn(0f, 1f)
        if (li == LR) {
            val wl = inkWidth(l, TRIG_INK); val wr = inkWidth(r, TRIG_INK)
            val plus = aPlus
            val gap = 2f * k
            val wp = if (plus != null) inkWidth(plus, PLUS_INK) + 2f * gap else gap
            val x0 = ox - (wl + wp + wr) / 2f
            drawTrigger(c, l, aTrigLGlow, x0 + wl / 2f, cy, under, topA, botA)
            drawTrigger(c, r, aTrigRGlow, x0 + wl + wp + wr / 2f, cy, under, topA, botA)
            if (!under && plus != null) drawInk(c, plus, x0 + wl + wp / 2f, cy, PLUS_INK)
        } else {
            val right = li == RT
            drawTrigger(c, if (right) r else l, if (right) aTrigRGlow else aTrigLGlow, ox, cy, under, topA, botA)
        }
    }

    private fun drawTrigger(c: Canvas, a: Art, glow: Art?, cx: Float, cy: Float, under: Boolean,
                            topA: Float, botA: Float) {
        if (under) {
            aTrigBehind?.let { drawCentered(c, it, cx, cy, 88f, 88f, (botA * 255f).toInt()) }
            return
        }
        drawInk(c, a, cx, cy, TRIG_INK)
        if (topA > 0f && glow != null) drawCentered(c, glow, cx, cy, 62f, 62f, (topA * 255f).toInt())
    }

    /** one timer for every animation; <= a frame goes through the animation clock */
    private val pSwing = Paint().apply { style = Paint.Style.FILL }
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** the swing's grey: the gold flame without colour, a touch darker (UI Art: saturation 0, brightness 0.85) */
    private val greyFill = ColorMatrixColorFilter(ColorMatrix().apply {
        setSaturation(0f); postConcat(ColorMatrix().apply { setScale(0.85f, 0.85f, 0.85f, 1f) })
    })

    private fun schedule(delayMs: Long) {
        removeCallbacks(tick)
        if (delayMs == Long.MAX_VALUE) return
        if (delayMs <= 16) postInvalidateOnAnimation() else postDelayed(tick, delayMs)
    }

    private fun drawLabel(c: Canvas, text: String) {
        pLabel.color = Theme.gold
        c.drawText(text, margin, margin - pLabel.ascent(), pLabel)
    }

    /**
     * One button, bottom to top: focus shadow, proc halo, well or empty-slot art, masked icon
     * (tinted), cooldown sweep, press flash, ring (normal / pushed / disabled), equipped border,
     * checked ring, proc ring, count and the glyph badge. Blizzard art where it is loaded, vector
     * otherwise. Returns the ms until it next needs a frame.
     *
     * [pressed] = Blizzard's PUSHED style (ApplyExtraButtonStyles): the button drops 2 units and the
     * ring swaps to *-border-pressed; an expanded button keeps its size. [flash] (1 -> 0) is the
     * short bright flash right after a physical press.
     */
    private fun drawSlot(c: Canvas, pos: Int, cx: Float, cyIn: Float, circle: Boolean, b: Btn?,
                         st: St?, memKey: Int, now: Long, pressed: Boolean, flash: Float,
                         keyText: String?, fightCd: Float = -1f): Long {
        var next = Long.MAX_VALUE
        val cy = if (pressed) cyIn + 2f * k else cyIn         // pressed: 2 units down
        // collapsed (a window owns the pad): 32 / 30 units (actionbarstyles.lua collapsed sizes)
        val sizeU = if (drawCollapsed && keyText == null) (if (circle) 30f else 32f) else (if (circle) 38f else 40f)
        val size = sizeU * k
        val half = size / 2f
        val corner = size / 7f
        val iconHalf = half - 3f * k
        val empty = b == null || b.empty
        val enabled = b?.e ?: false
        val hudPos = keyText == null && pos < 8               // a Controller-UI slot, not a legacy key
        val fr = if (circle) ci else sq
        val ringNormal = fr.normal

        // ---- focus shadow (*ShadowFocus, 13 units beyond, alpha 1 -> 0.4 over 0.33 s after a
        // layer switch); the vector fallback keeps its drop shadow + warm halo
        val ft = ((now - focusAtMs).toFloat() / FOCUS_MS).coerceIn(0f, 1f)
        val fa = 0.4f + 0.6f * (1f - ft)
        val focus = fr.focus
        if (focus != null) {
            drawCentered(c, focus, cx, cy, sizeU + 26f, sizeU + 26f, (fa * 255f).toInt())
        } else {
            drawMask(c, if (circle) maskCiTight else maskSqTight, cx, cy + 1.5f * k, Color.argb(150, 0, 0, 0))
            drawMask(c, if (circle) maskCiWide else maskSqWide, cx, cy, Color.argb((fa * 70f).toInt(), 255, 196, 90))
        }
        // proc glow halo sits BEHIND the button (its ring is drawn on top, below)
        val glowing = st != null && st.gl == 1 && !empty
        val pulse = if (glowing) 0.7f + 0.3f * sin(2.0 * Math.PI * (now % 1000L) / 1000.0).toFloat() else 0f
        if (glowing) {
            drawMask(c, if (circle) maskCiWide else maskSqWide, cx, cy, Color.argb((pulse * 230f).toInt(), 255, 214, 60))
            next = 33L
        }

        // ---- well, or the empty slot's own art (SlotArt: D-pad arrow / face letter or shape)
        val slotArt = if (empty && hudPos) (if (pressed) aSlotP[pos] ?: aSlot[pos] else aSlot[pos]) else null
        if (slotArt != null) {
            drawCentered(c, slotArt, cx, cy, sizeU, sizeU, if (enabled) 255 else 150)
        } else {
            pFill.color = WELL
            shape(c, cx, cy, half, corner, circle, pFill)
        }

        // ---- cooldown resolution
        var sweep = -1f           // remaining fraction of a REAL sweep
        var est = -1f             // remaining fraction of the ESTIMATE
        var dim = false
        if (st != null && st.cd == 1 && !empty) {
            val nowS = now / 1000.0
            if (memKey >= 0) {
                val es = exStart[memKey]; val ed = exDur[memKey]
                if (!es.isNaN() && ed > 0.0 && nowS < es + ed) {
                    sweep = ((es + ed - nowS) / ed).toFloat().coerceIn(0f, 1f)
                    next = min(next, sweepFrameMs(ed))
                }
            }
            if (sweep < 0f) {
                dim = true        // cooling down, timing unknown (combat or secret): dim 50%
                if (estimate && memKey >= 0) {
                    val s0 = estStart[memKey]; val d0 = estDur[memKey]
                    if (!s0.isNaN() && !d0.isNaN() && d0 > 0.0 && nowS < s0 + d0) {
                        est = ((s0 + d0 - nowS) / d0).toFloat().coerceIn(0f, 1f)
                        next = min(next, sweepFrameMs(d0))
                    }
                }
            }
        }

        // a fight face's cooldown (fightCooldown): the chosen look, and the icon dimmed as a cooling spell
        if (fightCd > 0f) {
            if (fightCdDashed) est = fightCd else sweep = fightCd
            dim = fightCdDashed
            next = min(next, 33L)
        }

        // ---- content
        if (empty) {
            if (slotArt == null) {
                pStroke.color = if (enabled) EMPTY_INNER else EMPTY_INNER_OFF
                pStroke.strokeWidth = 0.8f * k
                shape(c, cx, cy, half * 0.36f, corner * 0.5f, circle, pStroke)
            }
        } else if (b != null) {
            val usable = st == null || st.u == 1
            val mana = st != null && st.m == 1
            val filter: ColorFilter? = when {
                mana -> if (dim) cfManaDim else cfMana
                // p-bars-grey: IsEnabled says nothing about an ACTION (IsUsableAction does: `u`); a form / stance bar's buttons can read
                // disabled while they work. Only a fixed (scripted) or empty button is darkened for it
                !usable || (!enabled && (b.k.isEmpty() || b.k == "fixed")) -> if (dim) cfUnusableDim else cfUnusable
                dim -> cfDim
                else -> null
            }
            val sh = b.shader
            if (sh != null) {
                pIcon.shader = sh
                pIcon.colorFilter = filter
                pIcon.alpha = if (b.unable) 130 else 255
                c.save()
                c.translate(cx - iconHalf, cy - iconHalf)
                // SquareMask is a plain square (the art ring hides its corners); the vector ring is rounder
                if (circle) c.drawCircle(iconHalf, iconHalf, iconHalf, pIcon)
                else if (ringNormal != null) c.drawRect(0f, 0f, 2f * iconHalf, 2f * iconHalf, pIcon)
                else c.drawRoundRect(0f, 0f, 2f * iconHalf, 2f * iconHalf, corner * 0.7f, corner * 0.7f, pIcon)
                c.restore()
                pIcon.shader = null
                pIcon.colorFilter = null
            } else if (b.special != SP_NONE) {
                drawSpecial(c, b.special, cx, cy, iconHalf, if (b.unable || !enabled) 0.45f else if (dim) 0.5f else 1f)
            } else {
                pFill.color = if (usable) PLATE else PLATE_OFF
                shape(c, cx, cy, iconHalf, corner * 0.7f, circle, pFill)
            }

            // auto-attack flash (the Interact button, and Attack itself), on/off every 0.4 s
            if (autoAttack && (b.fx == "interact" || (b.k == "spell" && b.id == ATTACK_SPELL))) {
                val phase = now % (2 * FLASH_MS)
                if (phase < FLASH_MS) {
                    pFill.color = Color.argb(110, 255, 20, 10)
                    shape(c, cx, cy, iconHalf, corner * 0.7f, circle, pFill)
                }
                next = min(next, FLASH_MS - phase % FLASH_MS)
            }

            // real sweep: a dark pie over the remaining time, clockwise from 12 o'clock
            if (sweep > 0f) {
                pFill.color = SWEEP
                val start = -90f + 360f * (1f - sweep)
                if (circle) {
                    rect.set(cx - iconHalf, cy - iconHalf, cx + iconHalf, cy + iconHalf)
                    c.drawArc(rect, start, 360f * sweep, true, pFill)
                } else {
                    c.save()
                    c.clipRect(cx - iconHalf, cy - iconHalf, cx + iconHalf, cy + iconHalf)
                    val r = iconHalf * 1.45f
                    rect.set(cx - r, cy - r, cx + r, cy + r)
                    c.drawArc(rect, start, 360f * sweep, true, pFill)
                    c.restore()
                }
            }
            // estimate: a DASHED pale arc, deliberately unlike the real sweep
            if (est > 0f) {
                pDash.color = ESTIMATE
                pDash.strokeWidth = 1.6f * k
                val r = iconHalf * 0.74f
                rect.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(rect, -90f + 360f * (1f - est), 360f * est, false, pDash)
            }
        }

        // ---- the D-pad toolbar's pictures (host-driven, PadMap m29): under the ring and the glyph badge
        if (b != null && b.wheel) UiKit.miniWheel(c, cx, cy, size * 0.4f)
        b?.kit?.let { name ->
            val bmp = if (b.kitCut) swirlCut(name) else UiKit.tex(name)
            if (bmp != null) {
                val hs = size * b.kitScale / 2f
                val ar = bmp.width.toFloat() / bmp.height
                val hw = if (ar >= 1f) hs else hs * ar; val hh = if (ar >= 1f) hs / ar else hs
                rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
                c.drawBitmap(bmp, null, rect, pArt)
            }
        }

        // ---- press flash: a quick bright wash over the face of the button
        if (flash > 0f) {
            pFill.color = Color.argb((flash * 120f).toInt(), 255, 246, 220)
            shape(c, cx, cy, iconHalf, if (ringNormal != null) 0f else corner * 0.7f, circle, pFill)
        }

        // ---- ring (NormalTexture / PushedTexture / disabled), equipped, checked
        if (ringNormal != null) {
            val ring = when {
                pressed -> fr.pressed
                empty && !enabled -> fr.disabled
                else -> null
            } ?: ringNormal
            drawCentered(c, ring, cx, cy, sizeU, sizeU)
            if (flash > 0f) fr.hover?.let { drawCentered(c, it, cx, cy, sizeU, sizeU, (flash * 255f).toInt()) }
        } else {
            pStroke.strokeWidth = 1.1f * k
            pStroke.color = when {
                pressed -> RING_PRESSED
                empty && !enabled -> RING_OFF
                else -> Theme.bronze
            }
            shape(c, cx, cy, half - 0.55f * k, corner, circle, pStroke)
            if (flash > 0f) {
                pStroke.color = withAlpha(RING_PRESSED, (flash * 255f).toInt())
                pStroke.strokeWidth = 1.8f * k
                shape(c, cx, cy, half + 0.4f * k, corner, circle, pStroke)
            }
        }
        if (st != null && !empty) {
            if (st.eq == 1) {
                val bd = fr.border
                if (bd != null) drawCentered(c, bd, cx, cy, sizeU, sizeU, 89, cfEquipped)
                else {
                    pStroke.color = Color.argb(150, 0, 255, 0); pStroke.strokeWidth = 1.4f * k
                    shape(c, cx, cy, half - 1.8f * k, corner, circle, pStroke)
                }
            }
            if (st.chk == 1) {
                val sel = fr.selected
                if (sel != null) drawCentered(c, sel, cx, cy, sizeU + 8f, sizeU + 8f)
                else {
                    pStroke.color = RING_CHECKED; pStroke.strokeWidth = 1.5f * k
                    shape(c, cx, cy, half + 1.6f * k, corner * 1.2f, circle, pStroke)
                }
            }
            if (glowing) {
                val hv = fr.hover
                if (hv != null) drawCentered(c, hv, cx, cy, sizeU, sizeU, (pulse * 255f).toInt())
                else {
                    pStroke.color = Color.argb((pulse * 255f).toInt(), 255, 226, 110)
                    pStroke.strokeWidth = 1.6f * k
                    shape(c, cx, cy, half + 0.6f * k, corner * 1.1f, circle, pStroke)
                }
            }
            // count: white, bold, black outline, bottom-right
            st.countText?.let { t ->
                val x = cx + half - 5f * k
                val y = cy + half - 5f * k
                c.drawText(t, x, y, pCountEdge)
                c.drawText(t, x, y, pCount)
            }
        }

        // ---- glyph badge, 15 units, top-right (squares (+11.5,+11.5), circles (+11.5,+8.5))
        val gx = cx + 11.5f * k
        val gy = cy - (if (circle) 8.5f else 11.5f) * k
        val outOfRange = st != null && st.r == 0 && !empty
        // a collapsed bar hides its prompts (mainactionbarframe.lua HideButtonIcons)
        if (!(drawCollapsed && keyText == null)) drawGlyphBadge(c, gx, gy, pos, keyText, outOfRange, pressed)
        // the companion mark: a small gold "two screens" tag in the lower-left corner (the slot drives the bottom screen)
        if (b != null && b.ds) drawDsMark(c, cx - iconHalf * 0.62f, cy + iconHalf * 0.62f, iconHalf * 0.34f)
        return next
    }

    /**
     * A sweep redraws the whole view, and the game runs on the same SoC: about one degree per
     * redraw, never faster than 30 fps (only the short layer-switch glows and the press flash use
     * the animation clock).
     */
    private fun sweepFrameMs(durS: Double): Long = (durS * 1000.0 / 360.0).toLong().coerceIn(33L, 500L)

    private fun shape(c: Canvas, cx: Float, cy: Float, half: Float, corner: Float, circle: Boolean, p: Paint) {
        if (circle) c.drawCircle(cx, cy, half, p)
        else c.drawRoundRect(cx - half, cy - half, cx + half, cy + half, corner, corner, p)
    }

    private fun drawMask(c: Canvas, m: Bitmap?, cx: Float, cy: Float, color: Int) {
        m ?: return
        pMask.color = color
        c.drawBitmap(m, cx - m.width / 2f, cy - m.height / 2f, pMask)
    }

    private fun drawGlyphBadge(c: Canvas, gx: Float, gy: Float, pos: Int, keyText: String?,
                               outOfRange: Boolean, pressed: Boolean) {
        // the preset's real glyph tile, its disc BADGE_INK units across: pressed art while held,
        // Blizzard's Disabled art while out of range (the red tint only stands in until that art
        // is on the device)
        if (keyText == null && pos < 8) {
            val dis = if (outOfRange && !pressed) aGlyphD[pos] else null
            val a = dis ?: (if (pressed) aGlyphP[pos] else null) ?: aGlyph[pos]
            if (a != null) {
                val tint = outOfRange && dis == null && aGlyphD[pos] == null
                drawInk(c, a, gx, gy, BADGE_INK, 255, if (tint) cfRange else null)
                return
            }
        }
        val r = 7.5f * k
        pFill.color = if (outOfRange) BADGE_RED else BADGE
        c.drawCircle(gx, gy, r, pFill)
        pStroke.color = if (outOfRange) BADGE_RED_EDGE else BADGE_EDGE
        pStroke.strokeWidth = 0.6f * k
        c.drawCircle(gx, gy, r, pStroke)
        val ink = GLYPH_INK
        if (keyText != null) {
            if (keyText.isEmpty()) return
            pGlyph.color = ink
            val ts = pGlyph.textSize
            if (keyText.length > 1) pGlyph.textSize = ts * 0.75f
            c.drawText(keyText, gx, gy - (pGlyph.ascent() + pGlyph.descent()) / 2f, pGlyph)
            pGlyph.textSize = ts
            return
        }
        if (pos < 4) {
            // D-pad: an arrow
            pFill.color = ink
            c.save(); c.translate(gx, gy); c.scale(3.9f * k, 3.9f * k)
            c.drawPath(pathArrow[pos], pFill)
            c.restore()
            return
        }
        val face = pos - 4          // 0 left, 1 top, 2 right, 3 bottom
        if (preset == CastSettings.ButtonLayout.PLAYSTATION) {
            pStroke.color = ink
            val s = 3.4f * k
            pStroke.strokeWidth = 0.9f * k
            when (face) {
                0 -> c.drawRect(gx - s * 0.8f, gy - s * 0.8f, gx + s * 0.8f, gy + s * 0.8f, pStroke)   // square
                1 -> {                                                                                // triangle
                    c.save(); c.translate(gx, gy + 0.3f * k); c.scale(s, s)
                    pStroke.strokeWidth = 0.9f / 3.4f
                    c.drawPath(pathPsTri, pStroke)
                    c.restore()
                }
                2 -> c.drawCircle(gx, gy, s * 0.9f, pStroke)                                          // circle
                else -> {                                                                             // cross
                    val d = s * 0.8f
                    c.drawLine(gx - d, gy - d, gx + d, gy + d, pStroke)
                    c.drawLine(gx - d, gy + d, gx + d, gy - d, pStroke)
                }
            }
            return
        }
        pGlyph.color = ink
        c.drawText(faceLetters[face], gx, gy - (pGlyph.ascent() + pGlyph.descent()) / 2f, pGlyph)
    }

    /** code-drawn stand-ins for atlas art we cannot load; r = the icon's half size */
    private fun drawSpecial(c: Canvas, kind: Int, cx: Float, cy: Float, r: Float, alpha: Float) {
        val a = (alpha * 255f).toInt().coerceIn(0, 255)
        c.save()
        c.translate(cx, cy)
        c.scale(r, r)
        when (kind) {
            SP_INSPECT -> {
                pStroke.color = withAlpha(Color.rgb(200, 190, 170), a)
                pStroke.strokeWidth = 0.1f
                c.drawCircle(0f, 0f, 0.5f, pStroke)
                c.drawLine(-0.8f, 0f, -0.26f, 0f, pStroke); c.drawLine(0.26f, 0f, 0.8f, 0f, pStroke)
                c.drawLine(0f, -0.8f, 0f, -0.26f, pStroke); c.drawLine(0f, 0.26f, 0f, 0.8f, pStroke)
            }
            SP_BACK -> {
                pFill.color = withAlpha(Color.rgb(150, 20, 16), a)
                c.drawCircle(0f, 0f, 0.86f, pFill)
                pStroke.color = withAlpha(Color.rgb(255, 210, 60), a)
                pStroke.strokeWidth = 0.2f
                c.drawLine(-0.42f, -0.42f, 0.42f, 0.42f, pStroke)
                c.drawLine(-0.42f, 0.42f, 0.42f, -0.42f, pStroke)
            }
            SP_JUMP -> {
                pFill.color = withAlpha(Color.rgb(240, 196, 40), a)
                c.drawPath(pathJumpTri, pFill)
                pStroke.color = withAlpha(Color.rgb(34, 26, 10), a)
                pStroke.strokeWidth = 0.075f
                c.drawPath(pathJumper, pStroke)
                pFill.color = pStroke.color
                c.drawCircle(0.06f, -0.3f, 0.075f, pFill)
            }
            SP_HAND -> {
                pFill.color = withAlpha(Color.rgb(226, 206, 160), a)
                c.drawPath(pathHand, pFill)
            }
            SP_LOOT -> {
                pFill.color = withAlpha(Color.rgb(176, 132, 70), a)
                c.drawPath(pathSack, pFill)
                pStroke.color = withAlpha(Color.rgb(255, 209, 0), a)
                pStroke.strokeWidth = 0.09f
                c.drawLine(-0.24f, -0.2f, 0.24f, -0.2f, pStroke)
            }
            SP_RETICLE_FRIENDLY, SP_RETICLE_HOSTILE, SP_SHORTCUTS -> {
                c.scale(1f / 9f, 1f / 9f)
                drawReticle(c, 0f, 0f, kind, a)
            }
            SP_STAR -> {
                pFill.color = withAlpha(Color.rgb(255, 214, 60), a)
                c.drawPath(pathStar, pFill)
            }
            SP_BANG -> {
                pFill.color = withAlpha(Theme.gold, a)
                c.drawRoundRect(-0.12f, -0.68f, 0.12f, 0.22f, 0.1f, 0.1f, pFill)
                c.drawCircle(0f, 0.5f, 0.13f, pFill)
            }
            SP_ARROW_L, SP_ARROW_U, SP_ARROW_R, SP_ARROW_D -> {
                pFill.color = withAlpha(Color.rgb(220, 205, 170), a)
                c.scale(0.55f, 0.55f)
                c.drawPath(pathArrow[kind - SP_ARROW_L], pFill)
            }
            else -> {
                pFill.color = withAlpha(Theme.bronze, a)
                c.rotate(45f)
                c.drawRect(-0.22f, -0.22f, 0.22f, 0.22f, pFill)
            }
        }
        c.restore()
    }

    /** a shoulder-badge icon in UNIT space: ring + four diamond ticks, or the shortcuts grid */
    private fun drawReticle(c: Canvas, x: Float, y: Float, kind: Int, a: Int) {
        if (kind == SP_SHORTCUTS) {
            pFill.color = withAlpha(Theme.gold, a)
            for (i in 0 until 4) {
                val dx = if (i % 2 == 0) -3.2f else 0.8f
                val dy = if (i < 2) -3.2f else 0.8f
                c.drawRoundRect(x + dx, y + dy, x + dx + 2.4f, y + dy + 2.4f, 0.6f, 0.6f, pFill)
            }
            return
        }
        val col = if (kind == SP_RETICLE_FRIENDLY) FRIENDLY else HOSTILE
        pFill.color = withAlpha(Color.rgb(58, 52, 46), a)
        c.drawCircle(x, y, 5.2f, pFill)
        pStroke.color = withAlpha(col, a)
        pStroke.strokeWidth = 1.5f
        c.drawCircle(x, y, 6.6f, pStroke)
        pFill.color = withAlpha(col, a)
        for (i in 0 until 4) {
            c.save()
            c.translate(x, y)
            c.rotate(90f * i)
            c.translate(0f, -6.6f)
            c.rotate(45f)
            c.drawRect(-1.3f, -1.3f, 1.3f, 1.3f, pFill)
            c.restore()
        }
    }

    private fun badgeArt(kind: Int): Art? = when (kind) {
        SP_RETICLE_FRIENDLY -> aBadgeFr
        SP_RETICLE_HOSTILE -> aBadgeHo
        else -> aBadgeSc
    }

    /**
     * The targeting badges above the diamonds (PageUnit LeftShoulderBackground / Right...): plates
     * at native atlas size 50x45 units, BOTTOMRIGHT at (-108,+42) and BOTTOMLEFT at (+108,+42) from
     * the bar centre, the layer's icon centred on each and the -pressed overlay while held.
     */
    // =================================================================== the class-only radial

    /**
     * UI Art's LOOK 1 (user pick 2026-09-28): the game's radial over the held side's group. The room goes to
     * 35 % (65 % black over it); Blizzard's ring 512 px across (room at 1240 wide); eight places on a 168 px
     * circle, the first at the top, clockwise; backdrop + normal frame 157 px + the icon cut round at 98 px;
     * the right stick's place gets the selected frame + highlight and its name in the middle; a lit entry
     * (pet mode on / pet spell autocasting) wears a 4 px gold ring 112 px across; empty places at 45 %.
     * Display only: a touch does nothing (several of these actions are protected; tap-to-fire is parked).
     */
    private fun drawRadial(c: Canvas, r: Radial, cx: Float, cy: Float, vw: Float, vh: Float, fitSc: Float = 0f) {
        // fitSc > 0: Home's wheel slot (f-class-radial rework): fitted to its box, no dimming of the page
        val sc = if (fitSc > 0f) fitSc else vw / 1240f
        if (fitSc <= 0f) {
            pFill.color = Color.argb(166, 0, 0, 0)
            c.drawRect(0f, 0f, vw, vh, pFill)
        }
        aRadBase?.let { a -> val hw = 256f * sc; drawBox(c, a, cx - hw, cy - hw, cx + hw, cy + hw) }
        val slot = 157f * sc
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val px = cx + (sin(a) * 168.0).toFloat() * sc
            val py = cy - (kotlin.math.cos(a) * 168.0).toFloat() * sc
            val has = r.has[i]
            val sel = has && i == stickSector
            aRadBack?.let { b -> val bw = slot * 82f / 112f / 2f; val bh = slot * 84f / 112f / 2f
                drawBox(c, b, px - bw, py - bh, px + bw, py + bh, if (has) 255 else 115) }
            val frame = if (sel) aRadSel ?: aRadNormal else aRadNormal
            frame?.let { f -> val hw = slot / 2f; drawBox(c, f, px - hw, py - hw, px + hw, py + hw, if (has) 255 else 115) }
            // the icon sits ON the place's frame (its centre is solid), under the highlight
            if (has) {
                (iconReady[r.icons[i]] ?: run { requestIcon(r.icons[i]); null })?.let { bmp ->
                    val ir = 49f * sc
                    c.save()
                    litClip.reset(); litClip.addCircle(px, py, ir, Path.Direction.CW); c.clipPath(litClip)
                    val w = bmp.width.toFloat(); val hh = bmp.height.toFloat()
                    srcRect.set((w * 0.07f).toInt(), (hh * 0.07f).toInt(), (w * 0.93f).toInt(), (hh * 0.93f).toInt())
                    rect.set(px - ir, py - ir, px + ir, py + ir)
                    c.drawBitmap(bmp, srcRect, rect, pArt)
                    c.restore()
                }
            }
            if (sel) aRadHi?.let { hl -> val hw = slot / 2f; drawBox(c, hl, px - hw, py - hw, px + hw, py + hw) }
            if (has && r.lit[i]) {
                pStroke.color = Theme.gold; pStroke.strokeWidth = 4f * sc
                c.drawCircle(px, py, 56f * sc, pStroke)
            }
        }
        if (stickSector in 0..7 && r.has[stickSector]) {
            val p = radialName ?: UiKit.bodyPaint(30f, Color.WHITE).also { it.textAlign = Paint.Align.CENTER; radialName = it }
            p.textSize = 30f * sc
            c.drawText(r.names[stickSector] ?: "", cx, cy + p.textSize / 3f, p)
        }
    }

    /** p-bars-grey (CastDiag): the layer on show's buttons with something on them that the add-on calls not enabled */
    private fun diagEOff(h: Hud): Int {
        val l = h.layers.getOrNull(layer) ?: return 0
        var n = 0
        for (b in l) if (b != null && !b.empty && !b.e) n++
        return n
    }
    /** p-bars-grey (CastDiag): the layer on show's states the add-on calls not usable (u = 0) */
    private fun diagUOff(): Int {
        if (!hasState || layer !in 0 until STATE_LAYERS) return 0
        var n = 0
        for (st in state[layer]) if (st.u == 0) n++
        return n
    }

    // ---------------------------------------------------------------- f-class-radial: the mirror on Home (HomeView's layer)

    /** the class radial the held chord opens in the game right now (LB+LT the left, RB+RT the right; not while a window has the pad) */
    private fun chordRadial(): Radial? {
        val h = hud ?: return null
        if (!barsUsable) return null
        return when (ClassChord.side(padLt, padRt, padLb, padRb)) { ClassChord.LEFT -> h.caL; ClassChord.RIGHT -> h.caR; else -> null }
    }
    /** Home's layer: the chord opened a radial (true) or closed it (false); main thread, on change */
    var onClassRadial: ((Boolean) -> Unit)? = null
    private var classOpen = false
    private fun noteClassRadial() {
        val open = chordRadial() != null
        if (open != classOpen) { classOpen = open; onClassRadial?.invoke(open) }
    }
    /** the scale that fits the radial (its base art 512 units; places to 168 + 157 / 2) into a [vw] x [vh] box */
    private fun fitScale(vw: Float, vh: Float) = min(vw, vh) / 520f
    /** the radial's place centre in a [vw] x [vh] box (the same geometry [drawRadial] uses, fitted and centred) */
    private fun placeAt(i: Int, vw: Float, vh: Float, out: FloatArray) {
        val sc = fitScale(vw, vh)
        val a = Math.toRadians(i * 45.0)
        out[0] = vw / 2f + (sin(a) * 168.0).toFloat() * sc
        out[1] = vh / 2f - (kotlin.math.cos(a) * 168.0).toFloat() * sc
    }
    /** draw the open class radial fitted into a [vw] x [vh] box (Home's wheel slot); false = none open */
    fun drawClassRadial(c: Canvas, vw: Float, vh: Float): Boolean {
        val r = chordRadial() ?: return false
        drawRadial(c, r, vw / 2f, vh / 2f, vw, vh, fitScale(vw, vh))
        return true
    }
    /** the chord's side now: true = LB + LT (the left radial), false = RB + RT */
    fun classRadialLeft(): Boolean = ClassChord.side(padLt, padRt, padLb, padRb) != ClassChord.RIGHT
    /** the place under ([x], [y]) in that canvas: 0..7, or -1 (none, or an empty place) */
    fun classRadialHit(x: Float, y: Float, vw: Float, vh: Float): Int {
        val r = chordRadial() ?: return -1
        val half = 157f * fitScale(vw, vh) / 2f
        val pt = FloatArray(2)
        for (i in 0 until 8) {
            if (!r.has[i]) continue
            placeAt(i, vw, vh, pt)
            if (kotlin.math.abs(x - pt[0]) <= half && kotlin.math.abs(y - pt[1]) <= half) return i
        }
        return -1
    }
    /** place [i]'s cell on the add-on's "classr" row: its cast / pet action, or with [auto] its autocast toggle; null = none */
    fun classRadialCell(i: Int, auto: Boolean): Int? {
        val r = chordRadial() ?: return null
        if (i !in 0..7) return null
        return if (auto) r.auto[i] else r.cell[i]
    }
    /** a class radial exists for that chord (Home's hint while a module holds the controller) */
    fun hasClassRadial(left: Boolean): Boolean = (if (left) hud?.caL else hud?.caR) != null
    private var radialName: Paint? = null

    // =================================================================== the Home page's bars band

    /** 0 = ROW, 1 = TAKEOVER, in between while Home animates the view's height between the two */
    private fun bandT(): Float = if (layout == Layout.DIAMONDS) 1f
        else ((height - BAND_ROW_H) / (BAND_TAKE_H - BAND_ROW_H)).coerceIn(0f, 1f)

    /** frame px -> view px: ROW / TAKEOVER scale by width; DIAMONDS scales the TAKEOVER band uniformly and centres it */
    private fun bandScale(): Float = if (layout == Layout.DIAMONDS) (height - stripH()) / BAND_TAKE_H else width / FRAME_W

    /** DIAMONDS: the trigger strip's height at the bottom of the view (0 when off); the diamonds fit above it */
    private fun stripH(): Float = 0f   // the triggers sit under the corner plates (UI Art sheet): no strip height
    private fun bandXo(): Float = 0f
    private fun bandYo(): Float = 0f
    /** DIAMONDS: each group grows around its own centre (D-pad x 333, face x 888 in frame px), placed by width */
    private fun groupCx(pos: Int) = if (pos < 4) 333f else 888f

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** slot [pos]'s box in view px for the current band: (centre x, centre y, side) into [out] */
    private fun bandSlot(pos: Int, out: FloatArray) {
        val t = bandT()
        val sx = bandScale(); val xo = bandXo(); val yo = bandYo()
        val top = lerp(BAND_ROW_TOP, BAND_TAKE_TOP, t)          // the band's top in frame px
        val size = lerp(ROW_SLOT[pos * 3 + 2], TAKE_SLOT[pos * 3 + 2], t)
        val x = lerp(ROW_SLOT[pos * 3], TAKE_SLOT[pos * 3], t)
        val y = lerp(ROW_SLOT[pos * 3 + 1], TAKE_SLOT[pos * 3 + 1], t)
        if (layout == Layout.DIAMONDS) {
            // height sets the size; each group stays centred where the band puts it across the width
            val gc = groupCx(pos)
            out[0] = gc * width / FRAME_W + (x + size / 2f - gc) * sx
        } else out[0] = xo + (x + size / 2f) * sx
        out[1] = yo + (y - top + size / 2f) * sx
        out[2] = size * sx
    }
    private val bandBox = FloatArray(3)

    /**
     * SPEC part 5: the 8 slots at their table positions, each drawn by [drawSlot] at the fixed [BAND_K]
     * scale with the canvas scaled to its box, and the LB / RB plates. No label, page chips, legend or
     * trigger glyphs (the user: only LB and RB). Layers, collapse and presses behave as in the room.
     */
    private fun drawBand(c: Canvas, h: Hud, now: Long): Long {
        var next = Long.MAX_VALUE
        val li = layer
        var flashing = false
        for (pos in 0 until 8) {
            val src = srcOf[pos]
            // in a fight a D-pad In-combat spell beats DS Forever's layout picture (that press casts it)
            val fb = dpadFightOverride(li, src) ?: roomOverride(li, src) ?: fightOverride(li, src) ?: commandOverride(li, src)
            if (fb != null && fb.icon > 0 && fb.shader == null) resolve(fb, pos >= 4)   // built now, at this frame's scale
            val b = fb ?: h.layers[li][src]
            val st = if (fb == null && li < STATE_LAYERS && hasState) state[li][h.indexOfPos[li][src]] else fightStateOf(fb, src)
            val pressed = !quietPresses && ((pos == pressedPos && now < pressedUntilMs) || (held and (1 shl src)) != 0)
            val since = now - pressAtMs[src]
            val flash = if (!quietPresses && since >= 0L && since < PRESS_FLASH_MS) 1f - since.toFloat() / PRESS_FLASH_MS else 0f
            if (flash > 0f) flashing = true
            bandSlot(pos, bandBox)
            val circle = pos >= 4
            val scale = bandBox[2] / ((if (circle) 38f else 40f) * k)
            c.save()
            c.translate(bandBox[0], bandBox[1])
            c.scale(scale, scale)
            next = min(next, drawSlot(c, pos, 0f, 0f, circle, b, st,
                if (li < STATE_LAYERS) li * 8 + src else -1, now, pressed, flash, null,
                if (fb != null && fb.k == "spell") fightCdLeft(src, now) else -1f))
            c.restore()
        }
        val t = bandT()
        val sx = bandScale(); val xo = bandXo(); val yo = bandYo()
        val top = lerp(BAND_ROW_TOP, BAND_TAKE_TOP, t)
        for (side in 0..1) {
            val wFrame = lerp(if (side == 0) 118f else 118f, 124f, t)
            val left = lerp(if (side == 0) 16f else 1106f, if (side == 0) 14f else 1102f, t)
            val hFrame = wFrame * 31f / 39f
            val cyFrame = lerp(64f, 16f + hFrame / 2f, t)
            if (layout == Layout.DIAMONDS) {
                // the plates stay in the top corners at ONE size, whatever the height (user, 17:44: the
                // bumpers shrinking at rest looked goofy): the takeover's size, by width
                val ps = width / FRAME_W
                val half = wFrame * ps / 2f
                val px = if (side == 0) 14f * ps + half else width - 14f * ps - half
                drawPlate(c, h, side, px, (cyFrame - top) * ps, wFrame * ps / 39f)
                if (triggerStrip) {
                    // the bumper's own glyph at the plate's inner bottom corner (section 5): LB (right - 10, bottom - 9)
                    val pb = (cyFrame - top) * ps + hFrame * ps / 2f
                    val gx = if (side == 0) px + half - 10f * ps else px - half + 10f * ps
                    UiKit.pad(c, if (side == 0) "${padSet()}_lshoulder" else "${padSet()}_rshoulder", gx, pb - 9f * ps, BUMPER_GLYPH * ps)
                }
            } else drawPlate(c, h, side, xo + (left + wFrame / 2f) * sx, yo + (cyFrame - top) * sx, wFrame * sx / 39f)
        }
        if (triggerStrip && layout == Layout.DIAMONDS) next = min(next, drawTriggerStrip(c, now))
        if (bottomLine) next = min(next, drawSwingLine(c, now))
        val focusLeft = FOCUS_MS - (now - focusAtMs)
        if (focusLeft > 0 || flashing) next = 0
        if (pressedPos >= 0 && now < pressedUntilMs) next = min(next, pressedUntilMs - now)
        return next
    }

    /** the line along the bottom edge: the swing timer while a swing runs, the plain divider otherwise */
    /**
     * The triggers (see [triggerStrip]), UI Art's HARMONY_SPEC sections 2 + 5 (user picks 2026-10-01): ONE rule, the positions
     * follow the band's bottom, nothing shrinks. ZL / LT is the glyph alone (UiKit.triggerChip, no well; 84 x 56 box at 1240
     * wide) centred at (76, bottom - 60) under LB, ZR / RT at (w - 76, bottom - 60) under RB; one held = it lights in place.
     * Both held: a CROSS-FADE, no slide ("I want them to fade from their current spots and fade in to the middle"): the pair
     * under the bumpers fades out while the lit pair + the gold "+" fade in at (w/2 -+ 64, same y) / (w/2, y), FastOutSlowIn
     * on the alpha over 180 ms; let go = the same backwards in 150 ms.
     */
    private fun drawTriggerStrip(c: Canvas, now: Long): Long {
        val ps = width / FRAME_W
        noteJoin(now)
        val k = joinValue(now)
        val cy = height - TRIG_BOTTOM * ps
        val hw = TRIG_W * ps / 2f; val hh = TRIG_H * ps / 2f
        val set = padSet()
        val gl = "${set}_ltrigger"; val gr = "${set}_rtrigger"
        val restA = ((1f - k) * 255f).toInt(); val midA = (k * 255f).toInt()
        // under the bumpers (fading out while both are held)
        if (restA > 0) {
            val litL = barsUsable && padLt; val litR = barsUsable && padRt
            chipRect.set(TRIG_X * ps - hw, cy - hh, TRIG_X * ps + hw, cy + hh)
            UiKit.triggerChip(c, chipRect, gl, litL, restA)
            chipRect.set(width - TRIG_X * ps - hw, cy - hh, width - TRIG_X * ps + hw, cy + hh)
            UiKit.triggerChip(c, chipRect, gr, litR, restA)
        }
        // the joined pair at the bottom centre (fading in), always lit, and the "+"
        if (midA > 0) {
            val l = width / 2f - TRIG_JOIN * ps; val r = width / 2f + TRIG_JOIN * ps
            chipRect.set(l - hw, cy - hh, l + hw, cy + hh); UiKit.triggerChip(c, chipRect, gl, true, midA)
            chipRect.set(r - hw, cy - hh, r + hw, cy + hh); UiKit.triggerChip(c, chipRect, gr, true, midA)
            UiKit.triggerPlus(c, width / 2f, cy, TRIG_PLUS * ps, 1f, midA)
        }
        val busy = (now - joinAtMs).toFloat() < (if (joinTarget) TRIG_IN_MS else TRIG_OUT_MS)
        return if (busy) 0L else Long.MAX_VALUE
    }

    /** the button layout's glyph set in the kit (rev_ = Nintendo names, gen_ = LT / RT / LB / RB, shp_ = PlayStation) */
    private fun padSet(): String = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> "gen"
        CastSettings.ButtonLayout.PLAYSTATION -> "shp"
        else -> "rev"
    }

    // the ZL + ZR join: 0 = under the bumpers, 1 = joined in the middle (eased on the alpha; FastOutSlowIn)
    private var joinTarget = false
    private var joinFrom = 0f
    private var joinAtMs = 0L
    private val chipRect = RectF()
    private val joinCurve = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /** start the fade the moment the pair changes (called from the pad callback and from the draw) */
    private fun noteJoin(now: Long) {
        val both = barsUsable && padLt && padRt
        if (both == joinTarget) return
        joinFrom = joinValue(now)
        joinTarget = both; joinAtMs = now
    }

    private fun joinValue(now: Long): Float {
        val dur = if (joinTarget) TRIG_IN_MS else TRIG_OUT_MS
        val f = ((now - joinAtMs).toFloat() / dur).coerceIn(0f, 1f)
        val e = joinCurve.getInterpolation(f)
        val to = if (joinTarget) 1f else 0f
        return joinFrom + (to - joinFrom) * e
    }

    /**
     * The ONE bar along the bottom edge (user 2026-09-30, LATER item 21; look = UI Art's SHEET_castbar, user pick):
     * the game's own cast bar, 14 px tall at 1240 wide, the full width. A cast fills it with the game's gold flame
     * and its spark (a channel drains with the channel fill); the finish is the game's own: the bright full bar
     * under its gold glow, fading (exp, 0.15 s). A SWING is the same bar in grey; at its restart the grey full bar
     * fades while a white glint runs back to the start (0.25 s). An interrupt / failure shows the game's
     * interrupted bar, fading. Nothing running: the plain kit divider. Missing art falls back to flat colours.
     */
    private fun drawSwingLine(c: Canvas, now: Long): Long {
        val w = width.toFloat()
        // b-cast-bar-late: no real cast yet, a fresh prediction: the bar from the press, in its pending look
        val predicted = castStartMs.isNaN() && predOn && CastPredict.alive(predPressAt, now)
        if (predOn && !predicted && castStartMs.isNaN()) predOn = false               // never confirmed: dropped quietly
        val cStart = if (predicted) predStartMs else castStartMs
        val cEnd = if (predicted) predEndMs else castEndMs
        if (predicted) c.saveLayerAlpha(0f, height - BAR_H * w / FRAME_W, w, height.toFloat(), CastPredict.PENDING_ALPHA)
        val nextPred = drawCastLine(c, now, w, cStart, cEnd)
        if (predicted) c.restore()
        return if (predicted) 0L else nextPred
    }

    private fun drawCastLine(c: Canvas, now: Long, w: Float, castStartMs: Double, castEndMs: Double): Long {
        val bh = BAR_H * w / FRAME_W
        val y0 = height - bh; val y1 = height.toFloat()
        var next = Long.MAX_VALUE
        val fa = now - flashAtMs
        val parts = SwingLine.parts(swingOn, now.toDouble(), castStartMs, castEndMs, swingStartMs, swingDurMs, SWING_GRACE,
            flashKind != 0, flashKind == FLASH_SWING, fa, if (flashKind == FLASH_SWING) SWING_FLASH_MS else BAR_FLASH_MS)
        val flashing = parts.flash
        val casting = parts.cast
        val swingP = parts.swing
        val ahead = parts.wakeMs
        if (!casting && swingP < 0 && !flashing) {
            UiKit.divider(c, 0f, height - 1.5f, w, height - 1.5f)
            return ahead
        }
        // the groove
        val groove = aBarGroove
        if (groove != null) drawBox(c, groove, 0f, y0, w, y1)
        else { pSwing.color = Color.rgb(24, 20, 16); pSwing.alpha = 230; c.drawRect(0f, y0, w, y1, pSwing) }
        val inset = bh / 14f                                   // the fill sits 1 px in, as drawn
        fun fill(a: Art?, p: Float, cf: ColorFilter?, flat: Int) {
            if (p <= 0f) return
            c.save(); c.clipRect(0f, y0, w * p, y1)
            if (a != null) drawBox(c, a, 0f, y0 + inset, w, y1 - inset, 255, cf)
            else { pSwing.color = flat; pSwing.alpha = 255; c.drawRect(0f, y0 + inset, w, y1 - inset, pSwing) }
            c.restore()
        }
        fun spark(x: Float, cf: ColorFilter?) {
            val pw = 10f * w / FRAME_W; val ph = bh + 14f * w / FRAME_W
            val pip = aBarPip
            if (pip != null) drawBox(c, pip, x - pw / 2f, y0 - (ph - bh) / 2f, x + pw / 2f, y1 + (ph - bh) / 2f, 255, cf)
            else { pSwing.color = Color.WHITE; pSwing.alpha = 220; c.drawRect(x - 2f, y0 - 2f, x + 2f, y1 + 2f, pSwing) }
        }
        if (casting) {
            val p = ((now - castStartMs) / (castEndMs - castStartMs)).coerceIn(0.0, 1.0).toFloat()
            if (castChannel) { fill(aBarChan ?: aBarFill, 1f - p, null, Theme.gold); if (p < 1f) spark(w * (1f - p), null) }
            else { fill(aBarFill, p, null, Theme.gold); if (p > 0.001f && p < 0.999f) spark(w * p, null) }
            next = 0L
        } else if (swingP >= 0) {
            val p = min(1.0, swingP).toFloat()
            c.saveLayerAlpha(0f, y0 - bh, w, y1, if (swingP > 1.0) 110 else 255)   // waiting on the next swing: faded
            fill(aBarFill, p, greyFill, SWING_GREY)
            if (p < 0.999f) spark(w * p, greyFilter)
            c.restore()
            // p-swing-timer: on its own clock, ~30 frames a second (a 2-3 s bar needs no more; every vsync re-drew the whole band)
            next = SWING_FRAME_MS
        }
        if (flashing) {
            when (flashKind) {
                FLASH_CAST -> {
                    val k = kotlin.math.exp(-fa / BAR_FLASH_TAU_MS).toFloat()
                    val full = aBarFull
                    if (full != null) drawBox(c, full, 0f, y0, w, y1, (k * 255f).toInt())
                    else { pSwing.color = Theme.gold; pSwing.alpha = (k * 255f).toInt(); c.drawRect(0f, y0, w, y1, pSwing) }
                    val g = 8f * w / FRAME_W
                    aBarGlow?.let { drawBox(c, it, -g, y0 - g, w + g, y1 + g, (min(1f, 1.6f * k) * 255f).toInt()) }
                }
                FLASH_FAIL -> {
                    val k = kotlin.math.exp(-fa / (BAR_FLASH_TAU_MS * 1.5)).toFloat()
                    val fail = aBarFail
                    if (fail != null) drawBox(c, fail, 0f, y0 + inset, w, y1 - inset, (k * 255f).toInt())
                    else { pSwing.color = Color.rgb(220, 40, 30); pSwing.alpha = (k * 255f).toInt(); c.drawRect(0f, y0, w, y1, pSwing) }
                }
                else -> {                                       // a swing restart: grey full bar fades, a glint runs back
                    val k = fa.toFloat() / SWING_FLASH_MS
                    aBarFull?.let { drawBox(c, it, 0f, y0, w, y1, ((1f - 0.8f * k) * 255f).toInt(), greyFilter) }
                    val gx = w * (1f - k)
                    val cy = (y0 + y1) / 2f
                    val r = 60f * w / FRAME_W
                    glintPaint.shader = RadialGradient(gx, cy, r, Color.argb(150, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    c.save(); c.scale(1f, (bh + 12f) / (2f * r), gx, cy)
                    c.drawCircle(gx, cy, r, glintPaint)
                    c.restore()
                }
            }
            next = 0L
        }
        return next
    }

    // =================================================================== the Spellbook's Bind box (read-only)

    /**
     * Draws ONE trigger set of the controller bar into someone else's canvas (the Spellbook's "Bind to
     * Action Bar" box, wow-companion-design/spells/SPEC.md part 4): the D-pad cross fitted into [dpad] and
     * the face diamond into [face], each slot as the Cast room draws it (art, icons, the preset's glyphs, the
     * Nintendo picture swap), from the state this view already holds. Draw only: no input, no taps, no
     * state change. [set]: 1 none, 2 LT, 3 RT, 4 LT+RT. [lit]: a GAME position 1..8 (1-4 D-pad L/U/R/D,
     * 5-8 face X/Y/B/A = left/top/right/bottom) to highlight, drawn with [litIcon] in it when given.
     * Main thread. Returns false when no layout has arrived yet (the caller keeps its stand-ins).
     * [fight] (the Bind box's In combat tab, the user on batch8: "when on the in combat tab the spells should all change to show what's
     * bound for in combat"): GAME position 1..8 -> the icon of that button's IN COMBAT spell for [set] (null = none). Given, every slot
     * shows that instead of the bar: the fight spell drawn as this view draws a fight face (the same Btn road), the gold crossed swords
     * on it; a slot without one is an empty slot. Same frame, glyphs and size as the bar's own picture: only the spells change.
     */
    fun drawSet(c: Canvas, set: Int, dpad: RectF, face: RectF, lit: Int? = null, litIcon: Bitmap? = null,
                fight: ((Int) -> Int?)? = null): Boolean {
        val h = hud ?: return false
        if (k <= 0f) rebuildScale(BAND_K)
        val li = when (set) { 2 -> LT; 3 -> RT; 4 -> LR; else -> N }
        val now = SystemClock.elapsedRealtime()
        val savedCollapsed = drawCollapsed
        drawCollapsed = false
        for (group in 0..1) {
            val r = if (group == 0) dpad else face
            // the cluster in Blizzard units: D-pad x -139..-11, face x 13..127; both y -43..43
            val cxU = if (group == 0) -75f else 70f
            val wU = if (group == 0) 128f else 114f
            val s = min(r.width() / wU, r.height() / 86f)       // px per unit inside this box
            for (pos in group * 4 until group * 4 + 4) {
                val src = srcOf[pos]
                val fi = fight?.invoke(src + 1)
                val b = if (fight != null) setFightBtn(src, fi, pos >= 4) else h.layers[li][src]
                val st = if (fight == null && li < STATE_LAYERS && hasState) state[li][h.indexOfPos[li][src]] else null
                val px = r.centerX() + (POS_X[pos] - cxU) * s
                val py = r.centerY() - POS_Y[pos] * s
                c.save()
                c.translate(px, py)
                c.scale(s / k, s / k)
                drawSlot(c, pos, 0f, 0f, pos >= 4, b, st, if (fight == null && li < STATE_LAYERS) li * 8 + src else -1,
                    now, false, 0f, null)
                // the In combat mark (the Controller room's In combat tab: the game's gold crossed swords, bottom left on the slot)
                if (b != null && fight != null) UiKit.tex("combat_swords")?.let { sw ->
                    rect.set(-19f * k, 3f * k, -3f * k, 18f * k); c.drawBitmap(sw, null, rect, pArt)
                }
                c.restore()
                if (lit != null && src == lit - 1) {
                    c.save(); c.translate(px, py); c.scale(s / k, s / k)
                    drawLitOverlay(c, pos >= 4, litIcon)
                    // the slot's glyph badge stays on top of the highlight
                    drawGlyphBadge(c, 11.5f * k, -(if (pos >= 4) 8.5f else 11.5f) * k, pos, null, false, false)
                    c.restore()
                }
            }
        }
        drawCollapsed = savedCollapsed
        return true
    }

    /** drawSet's In combat slots: a fight spell as a Btn (fightOverride's road), kept per position and icon */
    private val setFightBtns = HashMap<Long, Btn>()
    private fun setFightBtn(src: Int, icon: Int?, circle: Boolean): Btn? {
        if (icon == null || icon <= 0) return null
        val key = src * 1_000_000_000L + icon
        setFightBtns[key]?.let { if (it.shader == null) resolve(it, circle); return it }
        val b = Btn().apply { p = src + 1; k = "spell"; this.icon = icon; e = true }
        classify(b)
        resolve(b, circle)
        setFightBtns[key] = b
        return b
    }

    /** the highlighted slot: the bound spell's icon masked into the slot, and a gold ring */
    private fun drawLitOverlay(c: Canvas, circle: Boolean, icon: Bitmap?) {
        val half = (if (circle) 19f else 20f) * k
        val inner = half - 3f * k
        if (icon != null) {
            c.save()
            val clip = litClip; clip.reset()
            if (circle) clip.addCircle(0f, 0f, inner, Path.Direction.CW)
            else clip.addRoundRect(-inner, -inner, inner, inner, inner / 5f, inner / 5f, Path.Direction.CW)
            c.clipPath(clip)
            val w = icon.width.toFloat(); val hh = icon.height.toFloat()
            rect.set(-inner, -inner, inner, inner)
            // trim the baked bevel like every other icon here (7% each side)
            srcRect.set((w * 0.07f).toInt(), (hh * 0.07f).toInt(), (w * 0.93f).toInt(), (hh * 0.93f).toInt())
            c.drawBitmap(icon, srcRect, rect, pArt)
            c.restore()
        }
        pStroke.color = Theme.gold; pStroke.strokeWidth = 2.2f * k
        if (circle) c.drawCircle(0f, 0f, half + 1.5f * k, pStroke)
        else c.drawRoundRect(-half - 1.5f * k, -half - 1.5f * k, half + 1.5f * k, half + 1.5f * k, half / 5f, half / 5f, pStroke)
    }
    private val srcRect = android.graphics.Rect()
    private val litClip = Path()

    /**
     * UI Art's METAL RING on a bumper plate (RC7b, the user: "almost a metalic finish ... like the rings on the combat buttons"): the game's
     * own ring art (kit ring_square) laid along the plate's sides and its cut corner (buildPaths: R's cut (-9,-15.5)->(-19.5,-5), L's
     * (9,-15.5)->(19.5,-5)); the drawn edge (RC7) only while the picture is missing. [unit] = the plate's scale
     */
    private val plateBounds = RectF(-19.5f, -15.5f, 19.5f, 15.5f)
    private val cutRA = android.graphics.PointF(-9f, -15.5f); private val cutRB = android.graphics.PointF(-19.5f, -5f)
    private val cutLA = android.graphics.PointF(9f, -15.5f); private val cutLB = android.graphics.PointF(19.5f, -5f)
    private fun plateRing(c: Canvas, side: Int, unit: Float, lit: Boolean) {
        val path = if (side == 0) pathBadgeR else pathBadgeL
        if (!UiKit.ringShape(c, path, plateBounds, if (side == 0) cutRA else cutLA, if (side == 0) cutRB else cutLB, unit, lit = lit))
            UiKit.ringEdge(c, path, UiKit.RING_W / unit, lit = lit)
    }

    /** one shoulder plate (the original vector plates, sides swapped per the user) at a px centre and scale */
    private fun drawPlate(c: Canvas, h: Hud, side: Int, cx: Float, cy: Float, scale: Float) {
        var left = SP_RETICLE_FRIENDLY; var right = SP_RETICLE_HOSTILE
        when (layer) {
            FR -> { left = SP_RETICLE_FRIENDLY; right = SP_SHORTCUTS }
            HO -> { left = SP_SHORTCUTS; right = SP_RETICLE_HOSTILE }
            SC -> { left = SP_SHORTCUTS; right = SP_SHORTCUTS }
        }
        if (h.swapT) { val tt = left; left = right; right = tt }
        val heldNow = if (side == 0) padLb else padRb
        c.save()
        c.translate(cx, cy)
        c.scale(scale, scale)
        pBadgeFill.color = if (heldNow) BADGE_BG_HELD else BADGE_BG
        c.drawPath(if (side == 0) pathBadgeR else pathBadgeL, pBadgeFill)
        plateRing(c, side, scale, heldNow)              // the reticle / glyph drawn after it
        drawReticle(c, 0f, -1f, if (side == 0) left else right, 255)
        c.restore()
    }

    private fun drawShoulders(c: Canvas, h: Hud) {
        // icons per layer, as Blizzard's SetShoulderIcons(left, right), swapped by swapT
        var left = SP_RETICLE_FRIENDLY; var right = SP_RETICLE_HOSTILE
        when (layer) {
            FR -> { left = SP_RETICLE_FRIENDLY; right = SP_SHORTCUTS }
            HO -> { left = SP_SHORTCUTS; right = SP_RETICLE_HOSTILE }
            SC -> { left = SP_SHORTCUTS; right = SP_SHORTCUTS }
        }
        if (h.swapT) { val t = left; left = right; right = t }
        for (side in 0..1) {
            val held = if (side == 0) padLb else padRb
            val kind = if (side == 0) left else right
            val plate = if (side == 0) aBadgeBgL else aBadgeBgR
            if (plate != null) {
                val pcx = ox + (if (side == 0) -133f else 133f) * k
                val pcy = oy - 64.5f * k
                drawCentered(c, plate, pcx, pcy, 50f, 45f)
                val icon = badgeArt(kind)
                if (icon != null) drawCentered(c, icon, pcx, pcy, 50f, 45f)
                else {
                    c.save(); c.translate(pcx, pcy); c.scale(k, k)
                    drawReticle(c, 0f, 0f, kind, 255)
                    c.restore()
                }
                if (held) (if (side == 0) aBadgeHeldL else aBadgeHeldR)?.let { drawCentered(c, it, pcx, pcy, 50f, 45f) }
                continue
            }
            val bx = ox + (if (side == 0) -132f else 132f) * k
            val by = oy - 65f * k
            c.save()
            c.translate(bx, by)
            c.scale(k, k)
            pBadgeFill.color = if (held) BADGE_BG_HELD else BADGE_BG
            // sides swapped (user ruling): the plate cut at its top-LEFT sits on the left, as in the game
            c.drawPath(if (side == 0) pathBadgeR else pathBadgeL, pBadgeFill)
            plateRing(c, side, k, held)
            drawReticle(c, 0f, -1f, kind, 255)
            c.restore()
        }
    }

    /**
     * The page tracker (pageunit.lua): 30-unit slots 30 apart, centred above the bar at +65.
     * Pages 1-3 are numericalpage-{n}-selected (current) / -normal (has actions) / -disabled
     * (empty), drawn 10 units beyond the slot; the special possess page is an "S" text slot.
     */
    private fun drawPageChips(c: Canvas, h: Hud) {
        val n = if (h.page == 4) 4 else 3
        val cy = oy - 65f * k
        val half = 11f * k
        for (i in 0 until n) {
            val cx = ox + (i - (n - 1) / 2f) * 30f * k
            val current = h.page == i + 1
            val filled = i < 3 && h.pages[i]
            if (i < 3) {
                val a = aPage[i * 3 + when { current -> 0; filled -> 1; else -> 2 }]
                if (a != null) { drawCentered(c, a, cx, cy, 50f, 50f); continue }
            } else {
                val bg = aPageSBg
                if (bg != null) {
                    drawCentered(c, bg, cx, cy, 30f, 30f)
                    if (current) aPageSFrame?.let { drawCentered(c, it, cx, cy, 30f, 30f) }
                    // GameFontNormalHuge: gold when it is the current page, white otherwise
                    pLabel.textSize = 16f * k
                    pLabel.textAlign = Paint.Align.CENTER
                    pLabel.color = if (current) Theme.gold else Color.WHITE
                    c.drawText(PAGE_TEXT[i], cx, cy - (pLabel.ascent() + pLabel.descent()) / 2f, pLabel)
                    pLabel.textAlign = Paint.Align.LEFT
                    pLabel.textSize = 9f * k
                    continue
                }
            }
            pGlyph.textSize = 12f * k
            pFill.color = when { current -> CHIP_CURRENT; filled -> CHIP_FILLED; else -> CHIP_EMPTY }
            c.drawRoundRect(cx - half, cy - half, cx + half, cy + half, 3f * k, 3f * k, pFill)
            pStroke.strokeWidth = 0.8f * k
            pStroke.color = when { current -> Theme.gold; filled -> Theme.bronzeDim; else -> CHIP_EMPTY_EDGE }
            c.drawRoundRect(cx - half, cy - half, cx + half, cy + half, 3f * k, 3f * k, pStroke)
            pGlyph.color = when { current -> Theme.gold; filled -> Theme.textDim; else -> CHIP_EMPTY_TEXT }
            c.drawText(PAGE_TEXT[i], cx, cy - (pGlyph.ascent() + pGlyph.descent()) / 2f, pGlyph)
            pGlyph.textSize = 10f * k
        }
    }

    /** a dark pill with a controller name (the vector fallback for a glyph tile) */
    private fun drawPill(c: Canvas, cx: Float, cy: Float, text: String, heightU: Float): Float {
        val ph = heightU * k
        pText.textSize = ph * 0.62f
        val w = max(pText.measureText(text) + ph * 0.8f, ph * 1.3f)
        pFill.color = BADGE
        c.drawRoundRect(cx - w / 2f, cy - ph / 2f, cx + w / 2f, cy + ph / 2f, ph / 2f, ph / 2f, pFill)
        pStroke.color = BADGE_EDGE; pStroke.strokeWidth = 0.5f * k
        c.drawRoundRect(cx - w / 2f, cy - ph / 2f, cx + w / 2f, cy + ph / 2f, ph / 2f, ph / 2f, pStroke)
        pText.color = GLYPH_INK
        pText.textAlign = Paint.Align.CENTER
        c.drawText(text, cx, cy - (pText.ascent() + pText.descent()) / 2f, pText)
        pText.textAlign = Paint.Align.LEFT
        return w
    }

    private fun pillWidth(text: String, heightU: Float): Float {
        val ph = heightU * k
        pText.textSize = ph * 0.62f
        return max(pText.measureText(text) + ph * 0.8f, ph * 1.3f)
    }

    /** a glyph tile (inkU units tall) left-aligned at x, or its name in a pill; returns the width */
    private fun drawGlyphAt(c: Canvas, a: Art?, name: String, x: Float, cy: Float, inkU: Float): Float {
        if (a != null) {
            val w = inkWidth(a, inkU)
            drawInk(c, a, x + w / 2f, cy, inkU)
            return w
        }
        val w = pillWidth(name, inkU * 0.85f)
        drawPill(c, x + w / 2f, cy, name, inkU * 0.85f)
        return w
    }

    /**
     * The layer name, top left: the word alone for Base, the modifier glyph(s) for the trigger
     * layers, and glyph + word for the shoulder layers (the shoulder that opens them, per swapT).
     */
    private fun drawLayerLabel(c: Canvas, li: Int, h: Hud) {
        if (li == N) { drawLabel(c, layerLabel[N]); return }
        val lb = aLb; val rb = aRb
        val g1: Art?; val g2: Art?; val n1: String; val n2: String?; val word: String?
        when (li) {
            LT -> { g1 = aLt; n1 = nameLt; g2 = null; n2 = null; word = null }
            RT -> { g1 = aRt; n1 = nameRt; g2 = null; n2 = null; word = null }
            LR -> { g1 = aLt; n1 = nameLt; g2 = aRt; n2 = nameRt; word = null }
            FR -> { g1 = if (h.swapT) rb else lb; n1 = if (h.swapT) nameRb else nameLb; g2 = null; n2 = null; word = layerLabel[FR] }
            HO -> { g1 = if (h.swapT) lb else rb; n1 = if (h.swapT) nameLb else nameRb; g2 = null; n2 = null; word = layerLabel[HO] }
            else -> { g1 = lb; n1 = nameLb; g2 = rb; n2 = nameRb; word = layerLabel[SC] }
        }
        // no art yet: the old text label
        if (g1 == null || (n2 != null && g2 == null)) { drawLabel(c, layerLabel[li]); return }
        val cy = margin + LABEL_INK * k / 2f
        var x = margin + drawGlyphAt(c, g1, n1, margin, cy, LABEL_INK)
        if (n2 != null) {
            val plus = aPlus
            x += 2f * k
            if (plus != null) x += drawInk(c, plus, x + inkWidth(plus, PLUS_INK) / 2f, cy, PLUS_INK) + 2f * k
            x += drawGlyphAt(c, g2, n2, x, cy, LABEL_INK)
        }
        if (word != null) {
            pLabel.color = Theme.gold
            c.drawText(word, x + 4f * k, cy - (pLabel.ascent() + pLabel.descent()) / 2f, pLabel)
        }
    }

    /** replaces the game's hint panel (GamepadPersistentInputLegend): glyph + one word */
    private fun drawLegend(c: Canvas, h: Hud) {
        val cy = height - margin - LEGEND_INK * k / 2f
        var x = margin
        for (i in 0 until 4) {
            val name = when (i) { 0 -> nameLb; 1 -> nameRb; 2 -> "R3"; else -> "L3" }
            // the shoulder glyphs show their pressed art while that shoulder is held
            val a = when (i) {
                0 -> if (padLb) aLbP ?: aLb else aLb
                1 -> if (padRb) aRbP ?: aRb else aRb
                2 -> aR3
                else -> aL3
            }
            val word = when (i) {
                0 -> if (h.swapT) "Enemy" else "Friendly"
                1 -> if (h.swapT) "Friendly" else "Enemy"
                2 -> "Ping"
                else -> "Run"
            }
            val gw = if (a != null) {
                val w = inkWidth(a, LEGEND_INK)
                drawInk(c, a, x + w / 2f, cy, LEGEND_INK)
                w
            } else {
                val w = pillWidth(name, 11f)
                drawPill(c, x + w / 2f, cy, name, 11f)
                w
            }
            pText.textSize = 7f * k
            pText.color = Theme.text
            val tx = x + gw + 3f * k
            c.drawText(word, tx, cy - (pText.ascent() + pText.descent()) / 2f, pText)
            x = tx + pText.measureText(word) + 10f * k
        }
    }

    /** the controller reader is down: a small crossed-out pad, top right */
    private fun drawPadOff(c: Canvas) {
        c.save()
        c.translate(width - margin - 8f * k, margin + 5f * k)
        c.scale(k, k)
        pStroke.color = Theme.dim
        pStroke.strokeWidth = 0.9f
        c.drawPath(pathPad, pStroke)
        pStroke.color = Theme.warn
        c.drawLine(-7f, -5f, 7f, 5f, pStroke)
        c.restore()
    }

    // =================================================================== vector art (unit space)

    private fun buildPaths() {
        // D-pad arrows: left, up, right, down
        pathArrow[0].apply { moveTo(-1f, 0f); lineTo(0.6f, -1f); lineTo(0.6f, 1f); close() }
        pathArrow[1].apply { moveTo(0f, -1f); lineTo(1f, 0.6f); lineTo(-1f, 0.6f); close() }
        pathArrow[2].apply { moveTo(1f, 0f); lineTo(-0.6f, 1f); lineTo(-0.6f, -1f); close() }
        pathArrow[3].apply { moveTo(0f, 1f); lineTo(-1f, -0.6f); lineTo(1f, -0.6f); close() }
        pathPsTri.apply { moveTo(0f, -1f); lineTo(0.95f, 0.72f); lineTo(-0.95f, 0.72f); close() }
        pathJumpTri.apply { moveTo(0f, -0.78f); lineTo(0.78f, 0.6f); lineTo(-0.78f, 0.6f); close() }
        // a running, leaping figure inside the jump triangle (head drawn separately)
        pathJumper.apply {
            moveTo(0.03f, -0.2f); lineTo(-0.06f, 0.14f)                        // body
            moveTo(-0.24f, -0.06f); lineTo(0.02f, -0.12f); lineTo(0.22f, -0.2f) // arms
            moveTo(-0.06f, 0.14f); lineTo(-0.26f, 0.26f); lineTo(-0.32f, 0.44f) // back leg
            moveTo(-0.06f, 0.14f); lineTo(0.12f, 0.28f); lineTo(0.08f, 0.46f)   // front leg
        }
        // an open hand: palm, four fingers, thumb
        pathHand.apply {
            addRoundRect(-0.34f, -0.02f, 0.3f, 0.56f, 0.16f, 0.16f, Path.Direction.CW)
            addRoundRect(-0.34f, -0.46f, -0.2f, 0.1f, 0.07f, 0.07f, Path.Direction.CW)
            addRoundRect(-0.17f, -0.6f, -0.03f, 0.1f, 0.07f, 0.07f, Path.Direction.CW)
            addRoundRect(0.0f, -0.56f, 0.14f, 0.1f, 0.07f, 0.07f, Path.Direction.CW)
            addRoundRect(0.17f, -0.42f, 0.3f, 0.1f, 0.07f, 0.07f, Path.Direction.CW)
            moveTo(0.26f, 0.12f); lineTo(0.56f, -0.12f); lineTo(0.64f, -0.02f); lineTo(0.3f, 0.34f); close()
        }
        // a loot sack
        pathSack.apply {
            addOval(-0.5f, -0.12f, 0.5f, 0.66f, Path.Direction.CW)
            moveTo(-0.2f, -0.18f); lineTo(0.2f, -0.18f); lineTo(0.34f, -0.52f)
            lineTo(0.1f, -0.4f); lineTo(0f, -0.56f); lineTo(-0.1f, -0.4f); lineTo(-0.34f, -0.52f); close()
        }
        // a five-point star (raid marker stand-in)
        pathStar.apply {
            for (i in 0 until 10) {
                val ang = Math.PI / 2 + i * Math.PI / 5
                val rr = if (i % 2 == 0) 0.7 else 0.3
                val x = (Math.cos(ang) * rr).toFloat(); val y = (-Math.sin(ang) * rr).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        // shoulder badges, 39x31 units, the inner top corner cut like the game's plates
        pathBadgeL.apply {
            moveTo(-19.5f, -15.5f); lineTo(9f, -15.5f); lineTo(19.5f, -5f)
            lineTo(19.5f, 15.5f); lineTo(-19.5f, 15.5f); close()
        }
        pathBadgeR.apply {
            moveTo(19.5f, -15.5f); lineTo(-9f, -15.5f); lineTo(-19.5f, -5f)
            lineTo(-19.5f, 15.5f); lineTo(19.5f, 15.5f); close()
        }
        // a small gamepad outline, 16x10 units
        pathPad.apply {
            addRoundRect(-8f, -4f, 8f, 4f, 3f, 3f, Path.Direction.CW)
            addCircle(-4.2f, 0f, 1.2f, Path.Direction.CW)
            addCircle(4.2f, 0f, 1.2f, Path.Direction.CW)
        }
    }

    companion object {
        // layers: the four trigger layers carry state, the three shoulder layers do not
        private const val N = 0; private const val LT = 1; private const val RT = 2; private const val LR = 3
        private const val FR = 4; private const val HO = 5; private const val SC = 6
        private const val LAYERS = 7
        private const val STATE_LAYERS = 4
        private val LAYER_KEYS = arrayOf("n", "lt", "rt", "lr", "fr", "ho", "sc")

        // ActionBarStyles.lua, expanded bar: D-pad squares 40u (L, U, R, D), face circles 38u (L, T, R, B)
        private val POS_X = floatArrayOf(-119f, -75f, -31f, -75f, 32f, 70f, 108f, 70f)
        private val POS_Y = floatArrayOf(0f, 23f, 0f, -23f, 0f, 23f, 0f, -23f)
        // ---- the Home page's bars band (wow-companion-design/home/SPEC.md part 5), frame px, y from the frame top
        private const val FRAME_W = 1240f
        /** p-swing-timer: the swing line's frame time (~30 fps) */
        private const val SWING_FRAME_MS = 33L
        private const val BAND_K = 4.05f                 // 162 px TAKEOVER square / 40 units
        private const val BAND_ROW_TOP = 8f; private const val BAND_TAKE_TOP = 10f
        private const val BAND_ROW_H = 112f; private const val BAND_TAKE_H = 354f
        /** per slot (0..3 D-pad L/U/R/D, 4..7 face left/top/right/bottom): left x, top y, side */
        private val ROW_SLOT = floatArrayOf(
            160f, 14f, 100f,   266f, 14f, 100f,   478f, 14f, 100f,   372f, 14f, 100f,
            662f, 14f, 100f,   768f, 14f, 100f,   980f, 14f, 100f,   874f, 14f, 100f)
        private val TAKE_SLOT = floatArrayOf(
            84f, 106f, 162f,   252f, 18f, 162f,   420f, 106f, 162f,  252f, 194f, 162f,
            664f, 110f, 156f,  810f, 22f, 156f,   956f, 110f, 156f,  810f, 198f, 156f)
        /** collapsed bar (a window owns the pad): squares 32 at L(-92,0) U(-58,17) R(-24,0) D(-58,-17),
         *  circles 30 at X(25,0) Y(55,18) B(85,0) A(55,-18) -- actionbarstyles.lua collapsed style */
        private val COL_POS_X = floatArrayOf(-92f, -58f, -24f, -58f, 25f, 55f, 85f, 55f)
        private val COL_POS_Y = floatArrayOf(0f, 17f, 0f, -17f, 0f, 18f, 0f, -18f)

        // face glyph letters (vector fallback), by position left / top / right / bottom
        private val NINTENDO_LETTERS = arrayOf("Y", "X", "A", "B")  // Nintendo = the Thor's print
        private val XBOX_LETTERS = arrayOf("X", "Y", "B", "A")
        private val IDENTITY = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        /** slots 4..7 = face left / top / right / bottom; Blizzard's 5..8 are Xbox X / Y / B / A there */
        private val NINTENDO_FACE_SWAP = intArrayOf(0, 1, 2, 3, 5, 4, 7, 6)
        private val PAGE_TEXT = arrayOf("1", "2", "3", "S")

        private const val SP_NONE = 0
        private const val SP_INSPECT = 1
        private const val SP_BACK = 2
        private const val SP_JUMP = 3
        private const val SP_HAND = 4
        private const val SP_LOOT = 5
        private const val SP_ARROW_L = 6        // L, U, R, D must stay consecutive
        private const val SP_ARROW_U = 7
        private const val SP_ARROW_R = 8
        private const val SP_ARROW_D = 9
        private const val SP_RETICLE_FRIENDLY = 10
        private const val SP_RETICLE_HOSTILE = 11
        private const val SP_SHORTCUTS = 12
        private const val SP_STAR = 13
        private const val SP_BANG = 14
        private const val SP_DOT = 15


        private const val ATTACK_SPELL = 6603
        private const val STALE_MS = 20_000L
        private const val TRIG_X = 76f           // frame px (1240 wide): a chip's centre under its bumper (UI Art)
        private const val TRIG_BOTTOM = 60f      // ... and its centre this far above the band's bottom
        private const val TRIG_W = 84f; private const val TRIG_H = 56f
        private const val TRIG_JOIN = 64f        // both held: each chip this far from the centre
        private const val TRIG_PLUS = 30f        // the "+" between them
        private const val TRIG_IN_MS = 180f; private const val TRIG_OUT_MS = 150f
        private const val BUMPER_GLYPH = 46f     // L / R on the bumper plates (UI Art, section 5)
        private const val STRIP_DIM = 89         // alpha 0.35: a trigger that is not held
        private const val SWING_H = 4f           // px: the swing fill along the bottom edge
        private val SWING_GREY = Color.rgb(150, 146, 138)   // the swing fill (the game's grey timer bar)
        private const val FLASH_SWING = 1; private const val FLASH_CAST = 2; private const val FLASH_FAIL = 3
        private const val BAR_FLASH_MS = 600L          // a cast's finish fades over 0.6 s (exp, tau below)
        private const val BAR_FLASH_TAU_MS = 150.0
        private const val SWING_FLASH_MS = 250L        // a swing restart: the glint's run back
        private const val BAR_H = 14f                  // the bar's height at 1240 wide (UI Art)
        private const val DS_MACRO_PREFIX = "DS Bar"   // Spellbook's companion macros: DS Bar Left / Open / Right
        private const val SWING_GRACE = 0.5      // swing lengths the line waits dimmed for the next swing
        private const val FOCUS_MS = 330L          // *ShadowFocus alpha 1 -> 0.4
        private const val GLOW_TOP_MS = 300f       // trigger GlowTop alpha 1 -> 0
        private const val GLOW_BOTTOM_MS = 500L    // trigger GlowBottom alpha 0.7 -> 0.2
        private const val PRESS_MS = 150L
        private const val PRESS_FLASH_MS = 180L    // the bright flash after a physical press

        // art: sizes are the OPAQUE part of a glyph tile, in UI units
        private const val INK_ALPHA = 160
        private const val BADGE_INK = 15f          // button prompt glyph (15x15, ActionBarStyles.lua)
        private const val TRIG_INK = 21f           // modifier glyph: fills the 62x62 GlowTop outline
        private const val PLUS_INK = 7f
        private const val LABEL_INK = 13f
        private const val LEGEND_INK = 13f
        /** action-button texcoord trim: the icon art's baked bevel lives outside 0.07..0.93 */
        private const val ICON_CROP = 0.07f
        private val DPAD_NAMES = arrayOf("left", "up", "right", "down")
        private const val FLASH_MS = 400L
        private const val CLOCK_REBASE_S = 10.0

        private val WELL = Color.rgb(10, 8, 6)
        private val PLATE = Color.rgb(43, 35, 18)
        private val PLATE_OFF = Color.rgb(28, 23, 13)
        private val EMPTY_INNER = Color.rgb(60, 50, 34)
        private val EMPTY_INNER_OFF = Color.rgb(34, 28, 20)
        private val RING_OFF = Color.rgb(110, 92, 58)
        private val RING_PRESSED = Color.rgb(255, 232, 170)
        private val RING_CHECKED = Color.rgb(255, 236, 140)
        private val SWEEP = Color.argb(185, 0, 0, 0)
        private val ESTIMATE = Color.argb(230, 170, 205, 255)
        private val BADGE = Color.rgb(30, 27, 24)
        private val BADGE_EDGE = Color.rgb(150, 140, 120)
        private val BADGE_RED = Color.rgb(160, 28, 22)
        private val BADGE_RED_EDGE = Color.rgb(255, 120, 100)
        private val GLYPH_INK = Color.rgb(230, 225, 215)
        private val BADGE_BG = Color.rgb(28, 22, 15)
        private val BADGE_BG_HELD = Color.rgb(58, 46, 26)
        private val FRIENDLY = Color.rgb(96, 214, 80)
        private val HOSTILE = Color.rgb(235, 60, 50)
        private val CHIP_CURRENT = Color.rgb(40, 32, 18)
        private val CHIP_FILLED = Color.rgb(22, 18, 12)
        private val CHIP_EMPTY = Color.rgb(16, 13, 10)
        private val CHIP_EMPTY_EDGE = Color.rgb(40, 33, 22)
        private val CHIP_EMPTY_TEXT = Color.rgb(80, 72, 58)

        private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)

        /** one background thread for every CastView's art decoding (see loadArt) */
        private val ART_EXEC: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "cast-art").apply { isDaemon = true }
        }

        /** one art file, decoded and ink-measured; ART_EXEC only; null when missing or unreadable */
        private fun decodeArt(name: String): Art? = try {
            val f = ArtHarvest.file("ui/cast/$name.png")
            if (f.canRead()) BitmapFactory.decodeFile(f.path)?.let { Art(it) } else null
        } catch (t: Throwable) {
            null
        }

        // ---- tolerant JSON readers: Safety.lua sends control-character sentinels for secret /
        // absent / failed values, and empty Lua tables arrive as [] rather than {}
        private fun isSentinel(s: String) = s.isEmpty() || s[0].code <= 3

        private fun jb(v: Any?, def: Boolean): Boolean = when (v) {
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is String -> when {
                isSentinel(v) -> def
                v == "true" || v == "1" -> true
                v == "false" || v == "0" -> false
                else -> def
            }
            else -> def
        }

        private fun ji(v: Any?, def: Int): Int = when (v) {
            is Number -> v.toInt()
            is Boolean -> if (v) 1 else 0
            is String -> if (isSentinel(v)) def else v.toDoubleOrNull()?.toInt() ?: def
            else -> def
        }

        private fun jd(v: Any?): Double = when (v) {
            is Number -> v.toDouble()
            is String -> if (isSentinel(v)) Double.NaN else v.toDoubleOrNull() ?: Double.NaN
            else -> Double.NaN
        }

        /** a clean boolean, or null when the value is absent, a sentinel or anything else */
        private fun jbOrNull(v: Any?): Boolean? = when (v) {
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }

        private fun str(v: Any?): String? = if (v is String && !isSentinel(v)) v else null
    }
}

/**
 * p-swing-timer (batch 12, the user: "swing timer also turned off cast bar, they should be seperate"): what Home's bottom line shows,
 * pure. [swingOn] false hides the SWING (its fill, its restart flash, its wake-up), never the CAST bar or a cast's flash. Times on the
 * app's clock in ms; NaN = none. [wakeMs]: when a swing that starts ahead begins (Long.MAX_VALUE = nothing to wake for).
 */
internal object SwingLine {
    data class Parts(val cast: Boolean, val swing: Double, val flash: Boolean, val wakeMs: Long)

    fun parts(swingOn: Boolean, now: Double, castStart: Double, castEnd: Double, swingStart: Double, swingDur: Double, grace: Double,
              flashKindSet: Boolean, flashIsSwing: Boolean, flashAge: Long, flashLen: Long): Parts {
        val cast = !castStart.isNaN() && now < castEnd + 100.0 && castEnd > castStart
        var swing = -1.0
        if (!cast && swingOn && !swingStart.isNaN() && swingDur > 0.0) {
            val p = (now - swingStart) / swingDur
            if (p >= 0.0 && p <= 1.0 + grace) swing = p
        }
        val flash = flashKindSet && (swingOn || !flashIsSwing) && flashAge >= 0 && flashAge < flashLen
        val wake = if (!cast && swingOn && swing < 0 && !swingStart.isNaN() && swingDur > 0.0 && now < swingStart)
            (swingStart - now).toLong().coerceAtLeast(1L) else Long.MAX_VALUE
        return Parts(cast, swing, flash, wake)
    }
}

/**
 * f-class-radial / p-druid-lt-lb: which of the game's class radials the held buttons open - LEFT = LB + LT (a druid's forms, a
 * paladin's auras, a hunter / warlock's pet commands), RIGHT = RB + RT (a warrior's stances, the pet's spells); the game reads the RAW
 * shoulders and triggers (blizzard_gamepadactionbars/classactionflyout.lua), so does Home's mirror. Pure.
 */
internal object ClassChord {
    const val NONE = 0; const val LEFT = 1; const val RIGHT = 2
    fun side(lt: Boolean, rt: Boolean, lb: Boolean, rb: Boolean): Int = when {
        lb && lt -> LEFT
        rb && rt -> RIGHT
        else -> NONE
    }
}

/**
 * p-bars-grey (1.0.8; the user: "would need a user submited recording to troubleshoot further", then yes to "add that line"): what
 * Home's bars decided at their last draw, as ONE line for the problem report's "Settings and state", e.g.
 *     castview8: g=0 s=1200 o=1 m=0 b=1 d=0 u=3
 * g = the whole bar greyed (1 / 0); s = ms since the last bar document (-1 none yet); o = the add-on's hud.ok; m = no button on any
 * layer (missing); b = the bars usable (no game window owns the pad); d / u = buttons of the layer on show the add-on calls not
 * enabled (disabled) / not usable. Nothing personal, and built so the privacy pass can never touch it: the tag carries a digit and
 * every key is ONE letter (the pass replaces whole-word names of 2+ letters: a character, realm or guild named "Grey" or "Empty"
 * would have eaten readable keys). Set from the draw (cheap: no string).
 */
internal object CastDiag {
    @Volatile private var have = false
    @Volatile private var grey = false; @Volatile private var staleMs = -1L; @Volatile private var ok = false
    @Volatile private var empty = false; @Volatile private var bu = false; @Volatile private var eOff = 0; @Volatile private var uOff = 0

    internal fun note(grey: Boolean, staleMs: Long, ok: Boolean, empty: Boolean, bu: Boolean, eOff: Int, uOff: Int) {
        this.grey = grey; this.staleMs = staleMs; this.ok = ok; this.empty = empty; this.bu = bu; this.eOff = eOff; this.uOff = uOff
        have = true
    }

    /** the report's line, or null before the bars have drawn once */
    fun line(): String? = if (!have) null else format(grey, staleMs, ok, empty, bu, eOff, uOff)

    fun format(grey: Boolean, staleMs: Long, ok: Boolean, empty: Boolean, bu: Boolean, eOff: Int, uOff: Int): String {
        fun b(v: Boolean) = if (v) 1 else 0
        return "castview8: g=${b(grey)} s=$staleMs o=${b(ok)} m=${b(empty)} b=${b(bu)} d=$eOff u=$uOff"
    }
}

/**
 * b-cast-bar-late: the predictive cast bar's rule, pure. A press starts one only for a spell with a cast time ([ctMs] > 0; instant
 * spells never), while no cast runs, and when the button is usable (no cooldown, mana enough). It lives [CONFIRM_MS] waiting for the
 * game's real start; then it is dropped quietly.
 */
internal object CastPredict {
    const val CONFIRM_MS = 500L
    const val PENDING_ALPHA = 150
    fun start(ctMs: Int, casting: Boolean, usable: Boolean): Boolean = ctMs > 0 && !casting && usable
    fun alive(pressAt: Long, now: Long): Boolean = pressAt > 0L && now - pressAt in 0 until CONFIRM_MS
}
