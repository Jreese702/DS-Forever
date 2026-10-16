package com.jrees.wowcompanion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * HOME: the page the bottom screen rests on (home session). The user's design is arrangement D,
 * approved 2026-09-28 20:05; UI Art's build spec is `wow-companion-design/home/SPEC.md` and every
 * number below is its number. The logic is [HomeFlow] and friends in HomeModel.kt; this file places
 * the parts and draws the two Home owns (the quests and the chat).
 *
 * ONE ROOM, THREE STATES. Rest (the bars as a thin row or as diamonds), a module full screen and the combat takeover live in
 * here, so the room never changes between them: GameUi sends no tap on a state change, and none in
 * combat. The parts move to their new places ("a fancy full screen transition", the user's words).
 *
 * BORROWED, NOT COPIED. The one MapView (compact mode, map session) and the one CastView are moved
 * into Home while it shows and given back when another room opens ([borrow] / [giveBack], called by
 * MainActivity OUTSIDE its loop over the rooms). The ChatView is borrowed only while chat is full
 * screen; everywhere else Home draws its own chat lines ([HomeChatTail]).
 *
 * GEOMETRY (spec, "THE FRAME"): Home's frame ends at the strip's TOP EDGE, and the strip's height is
 * not final (user: "a bit taller"), so every place is T+n (from the frame's top) or B-n (from its
 * bottom), never from a strip height. C = the frame's middle.
 */
class HomeView(
    context: Context,
    private val mapView: MapView,
    private val castView: CastView,
    private val chatView: ChatView,
    /** opens a room: the big quest list opens the Quests room (user, 20:35) */
    private val openRoom: (Room) -> Unit = {},
) : ViewGroup(context) {

    val flow = HomeFlow()
    private val grow = HomeGrow()
    private val auras = HomeAuras()
    private val quests = HomeQuests()
    private val chat = HomeChatTail()
    private var pad: JSONObject? = null
    /** the small map's zone band: the game minimap's text (the subzone, else the zone) and the zone's PvP type when sent */
    private var zoneText = ""
    private var zonePvp: String? = null
    /** the top centre's clock and battery (the device's own: no game call) */
    private var clockText = ""
    private var batteryPct = -1
    private var charging = false
    private var batteryAt = 0L
    /** the controller layout has come at least once: until then the Cast view says "Waiting" where the clock would stand */
    private var barsSeen = false

    private enum class Part { BARS, QUESTS, CHAT, MAP, AURAS, CHAT_FULL }

    private val questPane = QuestPane(context)
    private val chatPane = ChatPane(context)
    private val mapSlot = FrameLayout(context)
    private val auraPane = AuraPane(context)
    private val barsSlot = FrameLayout(context)
    private val chatSlot = FrameLayout(context)
    /**
     * THE CHAT'S GLIDE (b-chat-slide; the user's pick: chat going full screen should move like the map's morph). The full chat is laid
     * out ONCE at its full size and its box glides between the chat pane's place and the frame, as the map's does: a rounded window
     * over it in [chatSlot]'s own px, empty = no glide (the whole slot shows). No re-layout per frame (the Controller session: a
     * requestLayout every frame was what made the combat switch lag the game).
     */
    private val chatGlideBox = RectF()
    // CLOSE is added last, so it is drawn above the module it closes
    private val views: Map<Part, View> = linkedMapOf(
        Part.BARS to barsSlot, Part.QUESTS to questPane, Part.CHAT to chatPane, Part.MAP to mapSlot,
        Part.AURAS to auraPane, Part.CHAT_FULL to chatSlot,
    )

    /** where each part is now, in px of the frame; animated towards the state's places */
    private val now = HashMap<Part, RectF>()
    private var anim: ValueAnimator? = null
    private var shown: HomeScreen? = null
    private var settled = true

    init {
        applyHideRuling(context)
        // the thin row or the diamonds at rest: the user's switch in Settings (HomeSettings; the diamonds until changed)
        HomeSettings.init(context)
        flow.setRest(HomeSettings.rest)
        HomeSettings.onChange { post {
            if (flow.setRest(HomeSettings.rest)) changed()
            // the focused quest moved (Settings > Home): the parts go to their new places
            else if (laidOverMap != HomeSettings.questOverMap) shown?.let { apply(it, animate = isShown) }
            // r-combat-anim-off: Grow / Same size changed during a fight: the band goes to its new size
            else if (lent(castView) && castView.swingOn != HomeSettings.swingTimer)
                castView.swingOn = HomeSettings.swingTimer                                     // p-swing-timer: switched in a fight
            else if (laidGrow != HomeSettings.combatGrow) { laidGrow = HomeSettings.combatGrow; shown?.let { if (it.takeover) apply(it, animate = isShown) } }
        } }
        setBackgroundColor(Theme.ink)
        setWillNotDraw(false)
        for ((_, v) in views) { addView(v); v.visibility = View.GONE }
        mapView.onCompactTap = { if (flow.tapMap()) changed() }
        // the card's CONTENT is the map's (its focus, its default focus): a change there re-lays Home when the card comes or goes
        // (p-card-invisible: it only redrew, and a pane laid without a card stayed GONE until a later quests document)
        mapView.onShownFocusChanged = { questPane.invalidate(); cardCheck("focus") }
        chatSlot.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                val b = chatGlideBox
                if (b.isEmpty) o.setRect(0, 0, v.width, v.height)
                else o.setRoundRect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt(), UiKit.MODULE_R)
            }
        }
        questPane.translationZ = restZ(Part.QUESTS)                 // the card lies over the map (b121)   // the card shows the map's shown focus (Map's default focus)
        // clipped AT REST (the default): only a morph unclips, see [flightClip]
    }

    /**
     * THE MORPH'S FLIGHT: the focused quest's card and the map's art fly OUTSIDE the map's box during an open / close (Map's
     * mapView.morph; the harmony pass), so the clip is off ONLY while a morph runs. At rest it is on: with it off the map's edge pins
     * drew over the controller (the Thor, 2026-10-01 07:46: a pin on the B button, two across the module's top edge).
     */
    private fun flightClip(flying: Boolean) {
        if (clipChildren == !flying && mapSlot.clipChildren == !flying) return
        clipChildren = !flying; mapSlot.clipChildren = !flying
        invalidate(); mapSlot.invalidate()
    }

    /**
     * USER, 2026-09-28 20:35: "Yes, hide them while Home shows" -- the game's own controller bars,
     * quest tracker and chat go to Dynamic. New installs get it from UiElements' defaults; a device
     * that already saved a mode for them is switched ONCE here, and a later change in Settings is
     * the user's and stays. GameUi's public API, called after MainActivity's GameUi.init.
     */
    private fun prefs(c: Context) = c.getSharedPreferences("home", Context.MODE_PRIVATE)

    /** the thin row or the diamonds at rest (the preview sets it; the player uses the Settings row, HomeSettingsSection) */
    val rest: HomeRest get() = flow.rest
    fun setRest(r: HomeRest) { HomeSettings.rest = r; if (flow.setRest(r)) changed() }

    private fun applyHideRuling(c: Context) {
        val p = prefs(c)
        if (p.getBoolean(HIDE_RULING, false)) return
        // since 2026-10-06 a fresh install starts with them Off (the user's own values became the defaults); an install that already
        // ran this keeps what it has
        for (id in listOf("hud", "tracker", "chat")) GameUi.setMode(id, UiMode.OFF)
        p.edit().putBoolean(HIDE_RULING, true).apply()
    }

    // ================================================================ borrowing the rooms' views

    private class Loan(val view: View, val parent: ViewGroup, val index: Int, val lp: LayoutParams, val vis: Int)
    private val loans = ArrayList<Loan>()
    private fun lent(v: View) = loans.any { it.view === v }

    private fun lend(v: View, into: FrameLayout) {
        if (lent(v)) return
        val parent = v.parent as? ViewGroup ?: return
        loans.add(Loan(v, parent, parent.indexOfChild(v), v.layoutParams, v.visibility))
        parent.removeView(v)
        into.addView(v, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        v.visibility = View.VISIBLE
    }

    private fun giveBack(v: View) {
        val i = loans.indexOfLast { it.view === v }
        if (i < 0) return
        val l = loans.removeAt(i)
        (v.parent as? ViewGroup)?.removeView(v)
        l.parent.addView(v, min(l.index, l.parent.childCount), l.lp)
        v.visibility = l.vis
    }

    /** Home is about to show: take the map and the bars. MainActivity calls this after its room loop. */
    fun borrow() {
        lend(mapView, mapSlot)
        lend(castView, barsSlot)
        // B through the Cast view's OWN reader while Home holds it (see [watchB]): one getevent, never two
        castView.onPadButtons = { held -> onPadHeld(held) }
        castView.onClassRadial = { open -> classLayerShow(open) }             // f-class-radial: the mirror over Home
        // ARRIVING MID-FIGHT (the user, 2026-10-01: "going back home by what ever means, engages the combat transition at that time"):
        // the takeover was already on while Home was hidden, so Home lands at its rest and the combat transition PLAYS from there
        if (flow.takeover) {
            apply(flow.screen.copy(takeover = false), animate = false)
            post { if (flow.takeover && isShown && shown?.takeover == false) { grewAt = System.currentTimeMillis(); apply(flow.screen, animate = true) } }
        } else apply(flow.screen, animate = false)
    }

    /** another room opens: everything borrowed goes back where it came from, last lent first */
    fun giveBack() {
        watchB(false)
        closeTypeTool()                                                   // f-trackpad-keyboard: another room: the tool closes, the guest freed
        if (lent(mapView)) {
            mapView.compact = false; mapView.compactZoomButtons = false; mapView.headerRightInset = 0f; mapView.buttonTopInset = 0f
            mapView.showHeader = true
        }
        if (lent(castView)) {
            castView.layout = CastView.Layout.FULL; castView.bottomLine = false; castView.onPadButtons = null
            castView.onClassRadial = null; classLayerShow(false)                 // the Cast room draws its own radial
            castView.groundPainter = null; castView.triggerStrip = false; redShown = false
        }
        if (lent(mapView)) mapView.rimKeepOut = emptyList()
        while (loans.isNotEmpty()) giveBack(loans.last().view)
    }

    /** arriving from another room: nothing open */
    fun land() { if (flow.land()) changed() }

    /** the strip's HOME disc while Home already shows: back from a full-screen module, else land */
    fun homeDisc() { if (flow.homeDisc()) changed() }

    private val back = HomeBack()
    private val bGlyph = HomeBGlyph()

    /** an `interact` document's `bnext`: whether the Home button should show B (the strip draws it) */
    fun noteB(msg: JSONObject, current: Boolean) = bGlyph.note(msg, current, System.currentTimeMillis())
    /** a room left for Home: the disc's B goes in the same frame (HomeBGlyph.drop) */
    fun dropB(holder: String) = bGlyph.drop(System.currentTimeMillis(), holder)
    /**
     * The Home button shows B: when B would return Home (interact's `bnext`), and while one of Home's modules is full
     * screen (user, 15:08: "use the B glyph on the home button to go back to home").
     */
    /** a fight is on (Home's combat takeover): the self-setup never closes the game then */
    fun inFight(): Boolean = flow.takeover

    fun bGoesHome(): Boolean = bGlyph.shown(System.currentTimeMillis()) || (flow.full != null && !flow.takeover) ||
        // the buff / debuff list open: B returns Home at any time there (the user, 2026-09-30: "the red with the b button")
        auraPane.open != null

    /** called after every change of Home's state, so the strip's glyph follows at once (MainActivity sets it) */
    var onStateChanged: (() -> Unit)? = null

    /** an `interact` document: true when the controller was just handed back by B, so Home should show */
    fun backToHome(msg: JSONObject, current: Boolean): Boolean = back.note(msg, current)

    /** after [backToHome]: the capture id a fight just let go of (rel by "combat"), else null (HomeBack.combatWho) */
    fun combatReleased(): String? = back.combatWho

    /** after [backToHome] returned true: the capture id the B release named (HomeBack.bWho), else null */
    fun bReleasedBy(): String? = back.bWho

    /**
     * B GOES BACK (user, 15:14: "Back to Home, game gets B too"): while a module is full screen, the controller's B is
     * WATCHED, read-only, through the Cast room's reader (PadInput: getevent, no grab, nothing written, nothing sent);
     * the game receives the same press as always. B = the face button on the right (code 305, BTN_B / BTN_EAST, which
     * the Odin reports at the right: user-confirmed), the button the game calls PAD2.
     * B ALSO SHRINKS the big controller (user, 16:46: "I can press B to make it minimize"); it stays small until the next
     * start ([HomeGrow]), with the red reminder while the fight is on.
     * ONE READER AT A TIME (the user's LT / RT layers stopped switching, 17:24, while Home ran a second getevent next to the
     * Cast view's during the big controller): while the bars are on the page (at rest, grown, or full screen) B comes from
     * the Cast view's own reader ([CastView.onPadButtons], set in [borrow]); Home's own reader runs only while a full-screen
     * chat or map hides the bars, when the Cast view's reader is stopped.
     */
    private var bReader: PadInput? = null
    private var bWasHeld = false
    private fun watchB(on: Boolean) {
        if (!on) { bReader?.stop(); if (classHint != null) { classHint = null; classLayerShow(false) }; return }
        // f-class-radial: Home's own reader (a chat / map module holds the controller): the game's class radial cannot open; say so
        val p = bReader ?: PadInput({ lt, rt, lb, rb ->
            val left = lb && lt; val right = rb && rt
            val hint = if ((left && castView.hasClassRadial(true)) || (right && castView.hasClassRadial(false)))
                "Close the ${if (flow.full == HomeModule.MAP) "map" else "chat"} to use the class radial (${Glyphs.name("b")})" else null
            if (hint != classHint) { classHint = hint; classLayerShow(hint != null) }
        }, { _, _ -> }, { held -> onPadHeld(held) }).also { bReader = it }
        p.start()
    }

    /**
     * Read-only: every held mask of whichever of Home's readers runs (the Cast view's while the bars are on the page, Home's own while a
     * full-screen chat or map hides them), passed on for display only - Interact's loot pop-up starts its Make Space ring on A-down (the
     * user: "the hold animation goes from empty to full"). No second getevent; nothing is sent to the game. Not called in a room (no
     * reader of Home's runs there): the ring falls back to Interact's document.
     */
    var onPadHeldAny: ((Int) -> Unit)? = null

    /**
     * f-class-radial (the user's pick: "Mirror the game's radials"): while LB+LT / RB+RT holds the game's class radial open, Home shows
     * it over the page - the same places, the right stick's place lit (CastView draws it) - and a TOUCH picks: a tap is the place's
     * secure cell on the add-on's "classr" row (the game's own cast / pet action), a long-press on a pet spell its autocast. While
     * Home's chat or map module holds the controller the game's radial cannot open: the layer says so instead.
     */
    // ---------------------------------------------------------------- f-trackpad-keyboard: the Keyboard & Trackpad tool
    /** the tool over the whole page while open; it holds the generic guest "home" (as the chat module does) and takes its presses */
    private var typeWanted = false
    private val typeTool: TypeToolView by lazy { TypeToolView(context) { closeTypeTool() } }
    /** [tab]: 0 the Track Pad, 1 the Keyboard (the pause wheel's D-pad Left / Right) */
    fun openTypeTool(tab: Int = 0) {
        Log.i(TAG, "type tool: open tab $tab${if (typeWanted) " (already)" else ""} sink=${if (InputSink.sink != null) "ok" else "none"}")
        if (typeWanted) return
        if (typeTool.parent == null) addView(typeTool)
        typeWanted = true
        typeTool.visibility = View.VISIBLE
        // f-trackpad-keyboard rework (the user on test9: "the map border map name and quest tracker thing is all in the way. Also the
        // track pad is not working"): the quest card lies at translationZ 2 and the class wheel lifts the chat, so bringToFront alone
        // left them OVER the tool (drawn and touched first); the tool now lies above every module, no shadow, and Home's own
        // paints after the children (the map's well, the zone band, the clock) and its bars touch-intercept stand aside while it is open
        typeTool.outlineProvider = null
        typeTool.translationZ = TYPE_Z
        typeTool.bringToFront()
        typeTool.open(tab)
        requestLayout()
        if (homeGuestTap(GuestPad.cells(pad, TOOL_GUEST, hold = true))) Log.i(TAG, "type tool: hold")
    }
    fun closeTypeTool() {
        if (!typeWanted) return
        typeWanted = false
        typeTool.close()                                                  // the launcher's forced pointer goes
        typeTool.visibility = View.GONE
        invalidate()                                                      // the map's well and the zone band come back
        Log.i(TAG, "type tool: close")
        if (homeGuestTap(GuestPad.cells(pad, TOOL_GUEST, hold = false))) Log.i(TAG, "type tool: free")
        onBHome?.invoke()
    }

    private var classHint: String? = null
    private val classLayer: View by lazy { object : View(context) {
        private var downAt = -1
        private val longPress = Runnable { val i = downAt; if (i >= 0) { downAt = -2; classTap(i, auto = true) } }
        override fun onDraw(c: Canvas) {
            val hint = classHint
            if (hint != null) {
                c.drawColor(Color.argb(166, 0, 0, 0))
                val p = UiKit.bodyPaint(32f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
                c.drawText(hint, width / 2f, height / 2f, p)
                return
            }
            // closing (classOpen false while it still shows): the slide-back draws the last radial (the chord is already let go)
            if (lent(castView)) castView.drawClassRadial(c, width.toFloat(), height.toFloat(), closing = !classOpen)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (classHint != null) return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = castView.classRadialHit(e.x, e.y, width.toFloat(), height.toFloat())
                    if (downAt >= 0) postDelayed(longPress, CLASS_LONG_MS)
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    val i = downAt; downAt = -1
                    if (i >= 0 && castView.classRadialHit(e.x, e.y, width.toFloat(), height.toFloat()) == i) classTap(i, auto = false)
                }
                MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); downAt = -1 }
            }
            return true
        }
    } }
    private fun classTap(i: Int, auto: Boolean) {
        val col = castView.classRadialCell(i, auto) ?: return
        val sent = TapBridge.tap(context, pad, "classr", col) { }
        Log.i(TAG, "class radial: place ${i + 1}${if (auto) " autocast" else ""} -> classr $col ${if (sent) "tapped" else "not sent"}")
    }
    /**
     * f-class-radial (1.0.9 rework, the user: "make them appear on the right or left sides of the screen, and slide the whole chat away or
     * back over top when releasing, or the map if its the right rigger"): the wheel takes the CHORD's own side - LB + LT the left column
     * (chat), RB + RT the right column (the map, the card over it) - as the largest square that fits there under the bars; that column
     * slides off its own screen edge to uncover it and back over it on release; the other column stays. Rooms' motion
     * (Motion.MODULE_OPEN / CLOSE, Motion.curve), translation only, on hardware layers. The "close the chat / map" hint (a module holds
     * the controller) stays a plain full-page note.
     */
    private val classRect = android.graphics.Rect()
    private var classOpen = false
    private fun classLayerShow(open: Boolean) {
        if (classLayer.parent == null) { if (!open) return; addView(classLayer) }   // added on first use: over everything
        val hint = classHint != null
        if (hint || !open) {
            if (open) { classRect.set(0, 0, width, height); classLayer.animate().cancel(); classLayer.alpha = 1f
                classLayer.scaleX = 1f; classLayer.scaleY = 1f; classLayer.visibility = View.VISIBLE; requestLayout(); classLayer.invalidate(); return }
            if (!classOpen) { classLayer.visibility = View.GONE; return }
        }
        if (open == classOpen) { classLayer.invalidate(); return }
        classOpen = open
        val w = width.toFloat(); val h = height.toFloat()
        val g = UiKit.MODULE_GAP
        // 1.0.9 (the user: "make them appear on the right or left sides of the screen, and slide the whole chat away or back over top
        // when releasing, or the map if its the right rigger"): LB + LT takes the LEFT column's place (the chat slides off the left edge),
        // RB + RT the RIGHT column's (the map slides off the right edge); the other column stays. The side is the one it OPENED on
        if (open) classLeft = !lent(castView) || castView.classRadialLeft()
        val left = classLeft
        val col = ArrayList<Pair<View, RectF>>()
        for ((p, v) in views) {
            if (p == Part.BARS) continue
            val r = now[p] ?: continue
            if ((r.centerX() < w / 2f) == left) col += v to r
        }
        val area = ClassSlot.column(col.filter { it.first.visibility == View.VISIBLE }.map { (_, r) -> ClassSlot.Area(r.left, r.top, r.right, r.bottom) },
            now[Part.BARS]?.bottom ?: 0f, h, g, left, w)
        val slot = ClassSlot.inColumn(area)
        classRect.set(slot.left.toInt(), slot.top.toInt(), (slot.left + slot.side).toInt(), (slot.top + slot.side).toInt())
        // the wheel lies UNDER its column (raised while it shows) and is uncovered as the column slides away; on release the column
        // slides back OVER it (the frozen picture rides it out, CastView), and the wheel goes when it is covered
        for ((p, v) in views) if (p != Part.BARS) {
            val r = now[p]
            v.translationZ = if (r != null && (r.centerX() < w / 2f) == left && (open || classLayer.visibility == View.VISIBLE)) CLASS_COVER_Z else restZ(p)
        }
        if (open) {
            classLayer.visibility = View.VISIBLE
            classLayer.layout(classRect.left, classRect.top, classRect.right, classRect.bottom)   // the one layout of the open
            classLayer.animate().cancel()
            classLayer.alpha = 1f; classLayer.scaleX = 1f; classLayer.scaleY = 1f; classLayer.translationX = 0f
        } else {
            classLayer.invalidate()                                           // redrawn as closing: the last radial (CastView keeps it)
        }
        // the column: off its own edge (open) / back (close); the other column never moves
        var last: android.view.ViewPropertyAnimator? = null
        for ((v, r) in col) {
            // a part not shown does not slide; on a close it is put back at once (a card hidden mid-slide kept its offset)
            if (v.visibility != View.VISIBLE) { if (!open) { v.animate().cancel(); v.translationX = 0f }; continue }
            val to = if (open) ClassSlot.away(left, r.left, r.right, w, g) else 0f
            v.animate().cancel()
            val a = v.animate().translationX(to).setDuration(if (open) Motion.MODULE_OPEN else Motion.MODULE_CLOSE)
                .setInterpolator(Motion.curve()).withLayer()
            // the map's well edge and zone band are Home's own paint (dispatchDraw): a redraw per frame of ITS slide (no layout)
            if (v === mapSlot) a.setUpdateListener { invalidate() } else a.setUpdateListener(null)
            a.setListener(null)
            last = a
        }
        val done = {
            if (!classOpen) {
                classLayer.visibility = View.GONE
                for ((p, v) in views) v.translationZ = restZ(p)                   // back to the page's own order (the card over the map)
            }
        }
        if (!open) {
            if (last != null) last.setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) = done()
            }) else done()
        }
        for ((v, _) in col) if (v.visibility == View.VISIBLE) v.animate().start()
    }
    /** the side the open wheel took (true = the left column's) */
    private var classLeft = true

    /**
     * f-radial-middle (1.0.9, the user: "The Dpad up radial, and spell radial should slideup between the middle of the modules like the
     * class bars used to, like a mixture of what it is now and what those were sort of"): the item / spell wheel (HomeRadialView) asks
     * for its slot here. [open] true: a square under the bars between the columns (ClassSlot.middle, never wider than the chat column);
     * the columns part outwards by half of it (the class wheel's 1.0.8 motion), the slot is returned in Home's coordinates; null =
     * Home is not on show or a module is full screen (the wheel then stays the full-screen one). false: the columns come back.
     */
    fun wheelSlot(open: Boolean): RectF? {
        if (open && (width == 0 || flow.screen.full != null)) return null            // the host asks only while Home is the room
        if (open == wheelOpen) return if (open) RectF(wheelRect) else null
        wheelOpen = open
        val w = width.toFloat(); val h = height.toFloat()
        val g = UiKit.MODULE_GAP
        if (open) {
            val chat = now[Part.CHAT]
            val s = ClassSlot.middle(w, h, now[Part.BARS]?.bottom ?: 0f, g, minOf(w * WHEEL_SLOT_MAX, chat?.width() ?: w / 2f))
            wheelRect.set(s.left, s.top, s.left + s.side, s.top + s.side)
            wheelPush = s.push
        }
        for ((p, v) in views) {
            if (p == Part.BARS) continue
            if (v.visibility != View.VISIBLE) { if (!open) { v.animate().cancel(); v.translationX = 0f }; continue }
            val r = now[p] ?: continue
            val to = if (!open) 0f else if (r.centerX() < w / 2f) -wheelPush else wheelPush
            v.animate().cancel()
            val a = v.animate().translationX(to).setDuration(if (open) Motion.MODULE_OPEN else Motion.MODULE_CLOSE)
                .setInterpolator(Motion.curve()).withLayer()
            // the map's well edge and zone band are Home's own paint (dispatchDraw): a redraw per frame of ITS slide (no layout)
            if (v === mapSlot) a.setUpdateListener { invalidate() } else a.setUpdateListener(null)
            a.setListener(null)
            a.start()
        }
        return if (open) RectF(wheelRect) else null
    }
    /**
     * f-radial-behind (1.0.9, the user: "the radial needs to look like its behind the Map and Chat modules, not slide up in front of it"):
     * the item / spell wheel is drawn by HomeRadialView into THIS layer, which lies over the bars but UNDER every column (the chat, the
     * card, the map, the auras): the columns part to uncover it and close over it. Non-null = the layer shows and draws it (Home's own
     * coordinates, clipped to Home); null = gone. [wheelRedraw] asks for its next frame.
     */
    var wheelPainter: ((Canvas) -> Unit)? = null
        set(v) {
            field = v
            val l = wheelLayer ?: if (v == null) return else object : View(context) {
                override fun onDraw(c: Canvas) { wheelPainter?.invoke(c) }
            }.also { wheelLayer = it; addView(it, 1) }                     // right over the bars (child 0), under every column
            l.visibility = if (v != null) View.VISIBLE else View.GONE
            if (v != null) {
                l.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
                l.layout(0, 0, width, height)
                l.invalidate()
            }
        }
    private var wheelLayer: View? = null
    fun wheelRedraw() { wheelLayer?.invalidate() }
    private var wheelOpen = false
    private val wheelRect = RectF()
    private var wheelPush = 0f

    /** p-grow-glitch: when the big controller last grew (wall clock, onB's), for B's grace */
    private var grewAt = 0L

    /** the held D-pad / face buttons from whichever reader runs: B's press, once */
    private fun onPadHeld(held: Int) {
        onPadHeldAny?.invoke(held)
        val b = PadInput.isHeld(held, PadInput.SLOT_FACE_RIGHT)
        val was = bWasHeld
        bWasHeld = b                                           // before acting: onB may stop the reader that called us
        if (b && !was) onB()
    }

    private fun onB() {
        if (typeWanted) return                                            // the tool's B comes through the guest (it closes the tool)
        // while the chat module holds the controller its B comes through the guest (the chat's own chain), not this read-only watch
        if (chatWanted && chatHeld) return
        if (mapWanted && mapHeld) return
        // B closes the buff / debuff list first; while the list holds the controller its B comes through the guest (deselect first)
        if (auraPane.open != null) { if (!auraPane.padHeld) { auraPane.close(); onBHome?.invoke() }; return }
        if (flow.takeover) {
            val now = System.currentTimeMillis()
            // p-grow-glitch (a player: "they expand then return to normal after the first second"): B in a fight shrinks the big
            // controller (the user's own design), but B is a fight button too. Not in the first GROW_B_GRACE_MS after it grew (the
            // pull's opening presses), and never with ZL / ZR held (that B is a layer's ability)
            if (now - grewAt < GROW_B_GRACE_MS || (lent(castView) && castView.triggerHeld())) return
            val shrank = grow.minimize(now)
            // the user's pick: out of a fight, B also gives A / X / Y back (Spells' give-back); in a fight B only shrinks
            if (HomeFightFace.giveBack(shrank, grow.fighting(now), fightArmed())) giveBackTap(retry = false)
            if (shrank && flow.combat(false)) { changed(); tick() }
        }
        else if (flow.close()) { changed(); onBHome?.invoke() }
    }

    /** B took a full module or the buff list back to Home's rest while the disc showed B: the disc's press (MainActivity: strip.homePressed) */
    var onBHome: (() -> Unit)? = null

    // ---------------------------------------------------------------- the fight faces and B's give-back

    /** the latest padmap (Spells' PadMap): the armed faces, the give-back column */
    private var padMap: PadMapModel.Map? = null

    /** every padmap (MainActivity's route): Cast's fight faces and their switch (the host's, never a constant), and the map kept */
    fun onPadMap(map: PadMapModel.Map) {
        padMap = map
        HomeAttackSwitch.note(map)                      // Spells' start-attack switch, drawn in Home's Settings section
        castView.fightFace = HomeFightFace.icons(map)
        castView.fightFaceOn = HomeFightFace.on(map)
        fightNow = map.fight                                            // m49: A / X / Y's switch (start attack / auto-repeat / combat)
        applyDpad()                                                      // Track C: the D-pad's In-combat spells, in fights only
        castView.commands = HomeFightFace.commandIcons(map)             // Track C: the companion's commands (overlays: the game's slot is empty)
    }

    /** the fight as the latest word says it: the padmap's `combat`, or a newer CURRENT `facewatch`'s `fight` (Tracking after test18:
     *  "have the app also honour the fight flag from faceswap/facewatch"; faceswap carries none) */
    private var fightNow = false
    private var dpadSaid: Map<Int, Int>? = null

    fun onFightWord(fight: Boolean) { if (fight != fightNow) { fightNow = fight; applyDpad() } }

    private fun applyDpad() {
        val map = padMap ?: return
        val d = HomeFightFace.dpadIcons(map, fightNow)
        castView.fightDpad = d
        castView.fightIds = HomeFightFace.ids(map, fightNow)            // b-combat-bar-states: their state by spell id
        // m55 (f-combat-forms): in a form, the positions fighting with the form's own set wear its icon
        castView.fightFormPos = HomeFightFace.formOwn(map).keys.filter { it in HomeFightFace.FACE_POSITIONS || fightNow }.toSet()
        castView.fightFormIcon = HomeFightFace.formBadge(map) ?: 0
        // ONE line per change, so one approved log read proves what the toolbar's D-pad was given (Tracking's ask after test18)
        if (d != dpadSaid) { dpadSaid = d; Log.i("WCLOG", "dpad draw: fight=$fightNow dpad=$d") }
    }

    /** every CURRENT `facecd` (Spells' PadMap m12, the whole state): each fight face's cooldown onto Cast's sweep, on the app's clock */
    fun onFaceCd(cds: Map<Int, PadMapModel.FaceCd>) {
        HomeFightFace.cooldowns(cds, android.os.SystemClock.elapsedRealtime()).forEach { (pos, sd) -> castView.fightCooldown(pos, sd.first, sd.second) }
    }

    private fun fightArmed(): Boolean = padMap?.let { HomeFightFace.icons(it).isNotEmpty() } == true

    /** ONE tap on Spells' give-back cell; idempotent in the add-on (nothing switched = no-op), so the one retry is safe: a tap right
     *  after a controller press can be lost to the park point (padstray), and B was just pressed */
    private fun giveBackTap(retry: Boolean) {
        removeCallbacks(giveBackRetry)
        val col = padMap?.faceGiveBackCol
        if (col == null) { Log.i(TAG, "B: no give-back cell (an older add-on): A / X / Y stay"); return }
        val sent = pad != null && TapBridge.tap(context, pad, PadMapModel.ROW_SPELLS, col) { }
        Log.i(TAG, "B: give A / X / Y back${if (retry) " (retry)" else ""}: ${if (sent) "sent" else "not sent"}")
        if (!retry) postDelayed(giveBackRetry, GIVEBACK_RETRY_MS)
    }

    private val giveBackRetry = Runnable {
        // still switched and still out of a fight: the first tap was lost
        if (fightArmed() && !grow.fighting(System.currentTimeMillis())) giveBackTap(retry = true)
    }

    /** the preview harness: open the buffs' (or debuffs') list with row [select] chosen */
    fun openAuras(debuff: Boolean, select: Int? = null) = auraPane.show(debuff, select)
    /** the preview harness: the cursor on the open map's auras (b129) */
    fun previewMapAuraCursor() = auraPane.enterFull()
    fun previewMapAuraList() { auraPane.enterFull(); auraPane.fullKey("PAD1") }
    /** the preview harness: the open list as the controller sees it (no game: nothing is tapped) */
    fun previewAuraPad() = auraPane.previewPad()

    /** the controller's B from outside (the preview harness); the real press is watched by [watchB] */
    fun pressB() = onB()

    /**
     * The generic guest "home" (MainActivity routes CURRENT documents while Home shows): the full-screen chat module's presses go to
     * the ChatView while it holds, else the buff / debuff list's. The two never hold at once (the module closes the list first).
     */
    /** the guest "tool": the Track Pad & Keyboard's presses (its own guest since ZL / ZR click: q-tool) */
    fun toolPad(msg: JSONObject) { if (typeWanted) typeTool.onPad(msg) }
    fun toolGuestAck(msg: JSONObject) {
        if (typeWanted && msg.optString("op") == "hold" && !msg.optBoolean("ok")) Log.i(TAG, "type tool: hold refused (${msg.optString("why")})")
    }
    /** the tool shows (its guest is wanted) */
    fun toolWanted(): Boolean = typeWanted

    fun auraPad(msg: JSONObject) {
        if (typeWanted) return                                            // the tool holds its own guest ("tool")
        if (mapWanted) { mapPad(msg); return }
        if (!chatWanted) { auraPane.onPad(msg); return }
        if (msg.has("lost")) chatHeld = false else chatHeld = true
        chatView.onPad(msg)
    }

    /** one press for the full map module: the MapView's host keys; B with nothing open leaves the module (Home's rest) */
    private fun mapPad(msg: JSONObject) {
        if (msg.has("lost")) { mapHeld = false; mapView.padHints = false; return }
        val n = msg.optInt("n", -1)
        if (n == homePadN) return
        homePadN = n
        mapHeld = true
        val k = msg.optString("k")
        // b129 (the user's pick "Right goes to buffs"): the buff / debuff list open = its keys; the cursor on an aura = the
        // auras' keys; else Right enters the auras, Left does nothing (no D-pad pan), SELECT = the map's filter (2026-10-09: Start
        // opens the pause wheel everywhere; Map's MapCursor takes PADBACK)
        if (auraPane.open != null) { auraPane.onPad(msg); syncMapB(); return }
        if (auraPane.fullCursorOn) {
            if (k == "PADBACK" || k == "PADLSHOULDER" || k == "PADRSHOULDER") mapView.hostKey(k) else auraPane.fullKey(k)
            syncMapB(); return
        }
        if (k == "PADDRIGHT") { auraPane.enterFull(); syncMapB(); return }
        if (k == "PADDLEFT") return
        if (!mapView.hostKey(k)) { if (flow.close()) { changed(); onBHome?.invoke() }; return }
        syncMapB()
    }

    /** the Home button's B follows the map's own chain: "home" with nothing open, "back" while its filter is */
    private fun syncMapB() {
        // b129: B on an aura (or in its list) steps back inside the module, not Home
        val where = if (auraPane.open != null || auraPane.fullCursorOn) "back" else mapView.hostBLeads()
        if (where != mapBSaid && homeGuestTap(GuestPad.bnext(pad, home = where == "home"))) mapBSaid = where
    }

    fun auraGuestAck(msg: JSONObject) {
        if (typeWanted) {
            if (msg.optString("op") == "hold" && !msg.optBoolean("ok")) Log.i(TAG, "type tool: hold refused (${msg.optString("why")})")
            return
        }
        if (mapWanted) {
            when (msg.optString("op")) {
                "hold" -> if (msg.optBoolean("ok")) { mapHeld = true; mapBSaid = null; mapView.padHints = true; mapView.hostStart(); syncMapB() }
                    else { Log.i(TAG, "map module: hold refused (${msg.optString("why")})"); homeGuestTap(GuestPad.cells(pad, GUEST_ID, hold = false)) }
                "free", "leave" -> { mapHeld = false; mapView.padHints = false }
            }
            return
        }
        if (!chatWanted) { auraPane.onGuestAck(msg); return }
        when (msg.optString("op")) {
            "hold" -> if (msg.optBoolean("ok")) {
                chatHeld = true
                // the Home button's B follows the chat's own chain (the Chat room's wiring, MainActivity's onBackChanged)
                chatView.post { chatView.onBackChanged(chatView.bLeadsHome()) }
            } else { Log.i(TAG, "chat module: hold refused (${msg.optString("why")})"); homeGuestTap(GuestPad.cells(pad, GUEST_ID, hold = false)) }
            "free", "leave" -> chatHeld = false
        }
    }

    /** something of Home's wants the guest "home" now: the full-screen chat module, or an open buff / debuff list (MainActivity's b7b check) */
    /** Home is busy with a controller layer of its own: its guest "home" (chat / map / aura list) or the tool's "tool" */
    fun homeGuestWanted(): Boolean = typeWanted || homeOwnGuestWanted()
    /** the guest "home" itself is wanted (the chat or map module, the buff / debuff list; the tool has its own "tool") */
    fun homeOwnGuestWanted(): Boolean = chatWanted || mapWanted || auraPane.open != null
    /** the chat module is full screen (the ChatView lent, the guest "home" wanted): only then does the chat's B sign reach the game */
    val chatModuleOn: Boolean get() = chatWanted

    private var chatWanted = false
    private var chatHeld = false
    private var mapWanted = false
    private var mapHeld = false
    private var mapBSaid: String? = null
    private var homePadN = -1
    private fun homeGuestTap(cells: List<Pair<String, Int>>?): Boolean = cells != null && pad != null && TapBridge.taps(context, pad, cells) { }
    /** Home leaves the screen: an open list closes (and hands the controller back) */
    fun closeAuras() = auraPane.close()

    /** one of [HomeFlow]'s moves from outside (the preview harness; later the controller) */
    fun act(move: HomeFlow.() -> Boolean) { if (flow.move()) changed() }

    // ================================================================ documents (MainActivity routes them)

    fun onQuests(q: JSONObject, current: Boolean) {
        quests.update(q, current, System.currentTimeMillis())
        pad = q.optJSONObject("pad")
        HomeAttackSwitch.pad = pad
        HomeCombat.fromQuests(q)?.let { grow.noteFight(it, current, System.currentTimeMillis()) }
        questPane.invalidate()
        tick()
        // the card's height can change with the focus or its objectives: re-place the wells (the chat's top follows the card). Posted:
        // MainActivity gives the map the same document right after us, and the card is the map's
        cardCheck("quests")
    }

    /**
     * THE CARD MATCHES ITS LAYOUT (p-card-invisible, the user on v1.0.8: "Sometimes the current focused quest is like, invisible"):
     * Home lays the quest pane out only while there is a card (b125: no focused quest, no card: the pane is GONE). The card's content
     * is the MAP'S (mapView.focusedCard(): its focus, else its default focus), and it can change from the map's own document; only
     * a quests document re-laid Home, so a card that came in between stayed invisible. Checked on the quests document, on the map's
     * focus change and on every full document (a cheap compare); a re-lay when the card's height is not the one laid. Then the WCHOME
     * "quest card:" line ([logCard]) says what the card is, only when that changes.
     */
    private fun cardCheck(why: String) {
        post {
            val ch = cardHeight()
            if (ch != laidCardH && shown != null) {
                Log.i(TAG, "quest card: re-laid ($why) height ${laidCardH.toInt()} -> ${ch.toInt()}")
                apply(shown!!, animate = isShown)
            }
            logCard()
        }
    }

    /** a part's depth at rest: the card lies over the map (b121); every other part on the page's own order */
    private fun restZ(p: Part): Float = if (p == Part.QUESTS) CARD_Z else 0f

    private var cardSaid = ""
    /** WCHOME: the card's state (the focused quest, whether there is a card, the pane's visibility / alpha / place / height, why hidden) */
    private fun logCard() {
        val q = mapView.shownFocusQuest()
        val card = homeCard() != null
        val p = questPane
        // what actually draws: under the map (its depth not above the map's, while it lies over the map), on screen at all
        val under = cardOverMap() && p.translationZ <= mapSlot.translationZ
        val drawn = android.graphics.Rect()
        val onScreen = p.visibility == View.VISIBLE && p.getGlobalVisibleRect(drawn)
        val why = HomeCardState.why(q, card, auraPane.open != null, p.visibility == View.VISIBLE, p.cardHidden, p.alpha, p.height,
            under, onScreen)
        val st = "q=$q${if (mapView.focusIsDefault()) " (default)" else ""} card=$card $why vis=${p.visibility == View.VISIBLE} " +
            "a=${"%.2f".format(p.alpha)} t=${p.translationX.toInt()},${p.translationY.toInt()} z=${p.translationZ.toInt()}/map ${mapSlot.translationZ.toInt()} " +
            "h=${p.height} laid=${laidCardH.toInt()} screen=${if (onScreen) drawn.toShortString() else "none"} " +
            "home vis=${visibility == View.VISIBLE} a=${"%.2f".format(alpha)} cols=${(now[Part.CHAT]?.let { chatPane.translationX } ?: 0f).toInt()}," +
            "${mapSlot.translationX.toInt()} wheel=${if (classOpen) "open" else "shut"}"
        if (st != cardSaid) { cardSaid = st; Log.i(TAG, "quest card: $st") }
    }

    fun onFull(msg: JSONObject, current: Boolean) {
        cardCheck("full")                                   // the map's document may have brought (or taken) the card
        if (!barsSeen && msg.optJSONObject("actionbar")?.optJSONObject("hud") != null) { barsSeen = true; invalidate() }
        msg.optJSONObject("map")?.let { m ->
            val t = m.optString("subzone", "").ifEmpty { m.optString("zone", "") }
            val pvp = m.optString("pvp", "").ifEmpty { null }
            if (t.isNotEmpty() && (t != zoneText || pvp != zonePvp)) { zoneText = t; zonePvp = pvp; invalidate() }
        }
        val now = System.currentTimeMillis()
        val fight = HomeCombat.fromFull(msg)?.also { grow.noteFight(it, current, now) }
        val attack = HomeGrow.fromFull(msg)?.also { grow.noteAttack(it, current, now) }
        if (fight != null || attack != null) tick()
    }

    fun onChat(c: JSONObject) { if (chat.update(c)) chatPane.refresh() }

    /** THE WORLD NOTICE over the chat (the user's pick "A: system line"; Chat's chat21 `notice` documents, current only) */
    private var notice: NoticeModel.Notice? = null
    fun onNotice(msg: JSONObject) {
        notice = NoticeModel.apply(notice, msg, android.os.SystemClock.uptimeMillis())
        chatPane.invalidate()
    }

    /** character's `auras` document (a1): the player's buffs, debuffs and weapon enchants */
    fun onAuras(msg: JSONObject, current: Boolean) {
        if (auras.update(msg, current, System.currentTimeMillis())) auraPane.invalidate()
    }

    /** character's `auraack`: the pick's ok fires the cancel tap; a refusal says why */
    fun auraAck(msg: JSONObject) {
        when (val a = auras.ack(msg, System.currentTimeMillis())) {
            AuraAck.Cancel -> if (!GameInput.tap(context, auras.pad, "auracancel", 0)) {
                auras.sendFailed(); auraPane.note("The game has not published its tap pad")
            }
            is AuraAck.Note -> auraPane.note(a.text)
            AuraAck.Idle -> {}
        }
        auraPane.invalidate()
    }

    fun ack(msg: JSONObject) {
        val note = quests.ack(msg, System.currentTimeMillis()) ?: return
        if (note.isNotEmpty()) questPane.note(note)
        questPane.invalidate()
    }

    /**
     * Once a second, and after each document: the big controller follows the auto-attack and the fight, and lapses when the log
     * goes quiet; the red reminder follows [HomeGrow.red] while the small controller is on the page.
     */
    fun tick() {
        val now = System.currentTimeMillis()
        if (quests.expire(now)) questPane.note("No answer from the game")
        if (auras.expire(now)) auraPane.note("No answer from the game")
        if (auraPane.open != null) auraPane.invalidate()                 // time left counts down
        if (flow.combat(grow.shown(now))) { if (flow.takeover) grewAt = System.currentTimeMillis(); changed() }
        val red = grow.red(now) && !flow.takeover && flow.full == null
        if (red != redShown) {
            redShown = red
            // BEHIND the controller's pieces and subtler (user 17:37: "it needs to be BEHIND everything and a bit more subtle"):
            // drawn by the Cast view right after its ground (its groundPainter), at UI Art's 0.55
            if (lent(castView)) castView.groundPainter = if (red) redPainter else null
        }
        // the clock (the device's, in its own 12 / 24 h format) and the battery, redrawn when they change
        val t = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now))
        if (now - batteryAt >= BATTERY_MS || batteryAt == 0L) {
            batteryAt = now
            context.getSystemService(android.os.BatteryManager::class.java)?.let { bm ->
                val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val ch = bm.isCharging
                if (pct != batteryPct || ch != charging) { batteryPct = pct; charging = ch; invalidate() }
            }
        }
        if (t != clockText) { clockText = t; invalidate() }
    }

    private var redShown = false
    private val redBox = RectF()
    private val redPainter: (Canvas, Int, Int) -> Unit = { c, w, h ->
        redBox.set(0f, 0f, w.toFloat(), h.toFloat())
        UiKit.combatGlow(c, redBox, RED_STRENGTH, 40f)
    }

    private fun focus(id: Int) = focusQuest(id) { questPane.note(it) }

    /**
     * ONE FOCUS PATH for every room (Home's quest list; the Map room's numbered bubbles and its left list, LATER item 10): make quest
     * [id] the game's focused (super-tracked) quest through quests' QuestPad pair, with Home's checks (live, fresh quest list, not in
     * combat, not while waiting, a pad row, a tap column) and Home's WCHOME logging. [note] shows a refusal where the tap was made.
     * Focusing the quest that is already focused does nothing (the user's 09-29 ruling).
     */
    fun focusQuest(id: Int, note: (String) -> Unit) {
        when (val t = quests.tapDisc(id, GameInput.live(), System.currentTimeMillis())) {
            is FocusTap.Send -> {
                // the cells of ONE focus, in order (q5: the no-action pick, then the act; an older pad: its focus row), in one
                // shell session through quests' TapBridge.taps, all or nothing
                val cells = QuestPad.cells(pad, QuestPad.Act.FOCUS, t.slot)
                Log.i(TAG, "focus $id: send ${cells ?: "no cells"}")
                val ok = cells != null && TapBridge.taps(context, pad, cells) { why ->
                    Log.i(TAG, "focus $id: tap failed: $why")
                    post { quests.sendFailed(); note(why) }
                }
                if (!ok) { quests.sendFailed(); note("The game has not published its tap pad") }
            }
            // every refusal goes to logcat too (tag WCHOME): the note is only drawn, so a test could not be read afterwards
            is FocusTap.Refused -> { Log.i(TAG, "focus $id: refused: ${t.why}"); note(t.why) }
            FocusTap.Already -> Log.i(TAG, "focus $id: already focused, nothing sent")
        }
        questPane.invalidate()
    }

    /** UNTRACK quest [id] (the Map room's X on its list; map session): ONE action, the game's tracker drops it; refusals to [note] */
    fun untrackQuest(id: Int, note: (String) -> Unit) =
        questAct(id, "untrack", QuestPad.Act.UNWATCH, quests.tapUntrack(id, GameInput.live(), System.currentTimeMillis()), note)

    /** TRACK quest [id] (A on an untracked quest of the map module's Untracked tabs): the game's tracker takes it */
    fun watchQuest(id: Int, note: (String) -> Unit) =
        questAct(id, "watch", QuestPad.Act.WATCH, quests.tapWatch(id, GameInput.live(), System.currentTimeMillis()), note)

    /**
     * TRACK AND FOCUS quest [id] (A on an untracked quest; the user, 2026-10-16: "A to track and Focus"): the tracker takes it, then
     * the game's focus, one pick + act pair each, WATCH_GAP_MS apart (the pick serves one action).
     */
    fun trackAndFocus(id: Int, note: (String) -> Unit) {
        val t = quests.tapAny(id, "track", GameInput.live(), System.currentTimeMillis())
        if (t !is FocusTap.Send) { if (t is FocusTap.Refused) note(t.why); return }
        val watch = QuestPad.cells(pad, QuestPad.Act.WATCH, t.slot)
        Log.i(TAG, "track+focus $id: send ${watch ?: "no cells"}")
        if (watch == null || !TapBridge.taps(context, pad, watch) { why -> post { quests.sendFailed(); note(why) } }) {
            quests.sendFailed(); note("The game has not published its tap pad"); return
        }
        postDelayed({
            QuestPad.cells(pad, QuestPad.Act.FOCUS, t.slot)?.let { TapBridge.taps(context, pad, it) { why -> post { note(why) } } }
        }, WATCH_GAP_MS)
    }

    /** SHARE quest [id] (the map module's quest panel; the game's own Share Quest, Quests.lua q11) */
    fun shareQuest(id: Int, note: (String) -> Unit) =
        questAct(id, "share", QuestPad.Act.SHARE, quests.tapAny(id, "share", GameInput.live(), System.currentTimeMillis()), note)

    /** ABANDON quest [id], first tap: the add-on names the quest it would drop (questack "arm"); the panel then asks */
    fun armAbandon(id: Int, note: (String) -> Unit) =
        questAct(id, "arm", QuestPad.Act.ARM, quests.tapAny(id, "arm", GameInput.live(), System.currentTimeMillis()), note)

    /** ABANDON, the confirm: drops what ARM named */
    fun confirmAbandon(id: Int, note: (String) -> Unit) =
        questAct(id, "abandon", QuestPad.Act.CONFIRM, quests.tapConfirm(id, GameInput.live(), System.currentTimeMillis()), note)

    /** read the game's own player menu for the bottom screen (A on the map module's portrait; Quests.lua q41, qact 34) */
    fun playerMenu(note: (String) -> Unit) {
        if (!GameInput.live()) { note("Waiting for the game"); return }
        val cells = GuestPad.playerMenu(pad)
        Log.i(TAG, "player menu: send ${cells ?: "no cells"}")
        if (cells == null || !TapBridge.taps(context, pad, cells) { why -> post { note(why) } }) note("The game has not published its tap pad")
    }

    /** Set (0) / Clear (1) Focus from the player menu: a tap on the add-on's SECURE "pfocus" row (FocusUnit is protected; Quests q43) */
    fun playerMenuSecure(col: Int, note: (String) -> Unit) {
        if (!GameInput.live()) { note("Waiting for the game"); return }
        if (pad?.optJSONObject("rows")?.has("pfocus") != true) { note("Focus is not on the tap pad yet"); return }
        Log.i(TAG, "player menu: focus cell $col")
        if (!TapBridge.tap(context, pad, "pfocus", col) { why -> post { note(why) } }) note("The game has not published its tap pad")
    }

    /** run entry [id] of the game's player menu (Quests.lua q41's two digit taps, in one shell session) */
    fun playerMenuRun(id: Int, note: (String) -> Unit) {
        if (!GameInput.live()) { note("Waiting for the game"); return }
        val cells = GuestPad.playerMenuRun(pad, id)
        Log.i(TAG, "player menu: run $id ${cells ?: "no cells"}")
        if (cells == null || !TapBridge.taps(context, pad, cells) { why -> post { note(why) } }) note("The game has not published its tap pad")
    }

    /** UNFOCUS quest [id] (the Map room's A on the focused quest; Quests.lua q9 qact 39): the game drops its focus, only if [id] still has it */
    fun unfocusQuest(id: Int, note: (String) -> Unit) =
        questAct(id, "unfocus", QuestPad.Act.UNFOCUS, quests.tapUnfocus(id, GameInput.live(), System.currentTimeMillis()), note)

    /** one quest action from the Map room: the model's verdict [t], then the pick + act cells in one shell session, logged as [what] */
    private fun questAct(id: Int, what: String, act: QuestPad.Act, t: FocusTap, note: (String) -> Unit) {
        when (t) {
            is FocusTap.Send -> {
                val cells = QuestPad.cells(pad, act, t.slot)
                Log.i(TAG, "$what $id: send ${cells ?: "no cells"}")
                val ok = cells != null && TapBridge.taps(context, pad, cells) { why ->
                    Log.i(TAG, "$what $id: tap failed: $why")
                    post { quests.sendFailed(); note(why) }
                }
                if (!ok) { quests.sendFailed(); note("The game has not published its tap pad") }
            }
            is FocusTap.Refused -> { Log.i(TAG, "$what $id: refused: ${t.why}"); note(t.why) }
            FocusTap.Already -> {}
        }
        questPane.invalidate()
    }

    // ================================================================ states -> places (spec parts 1, 2, 3, 6)

    private fun changed() { apply(flow.screen, animate = isShown); onStateChanged?.invoke() }

    /** the D-pad layout's RIGHT (the user, 2026-10-05: "I want right to open that home map module"): the map module opens as a tap on
     *  the small map would (refused in a fight's takeover, as the tap is); false = nothing changed */
    fun openMapModule(): Boolean = flow.tapMap().also { if (it) changed() }

    private fun places(s: HomeScreen, w: Float, h: Float): Map<Part, RectF> {
        val c = w / 2f
        if (!s.takeover) s.full?.let { m ->
            // NO bar and no close button on top (user, 2026-09-29 15:08: "get rid of the ugly top bars and use the B
            // glyph on the home button to go back to home"): the module fills the frame; the strip's Home button
            // shows B ([bGoesHome]) and takes the player back
            return when (m) {
                HomeModule.CHAT -> mapOf(Part.CHAT_FULL to RectF(0f, 0f, w, h))
                // b129 (the user, b128: "I also want the buffs and debuffs to be in the open map module in the top right- pressing right on the d-pad should take you over to them to open with A- below the buffs should be the debuffs"): the aura pane over the open map, its rows in the top right
                HomeModule.MAP -> mapOf(Part.MAP to RectF(0f, 0f, w, h), Part.AURAS to RectF(0f, 0f, w, h))
                HomeModule.BARS -> mapOf(Part.BARS to RectF(0f, 0f, w, h))
            }
        }
        // AT REST: the bars (thin row, or the diamonds), and under them the left column split EQUALLY between the tracked
        // quests and the chat (user, 15:15), the minimap on the right. The diamonds at rest are SMALL, fitted into the top (user,
        // 16:37: "the controller daimonds should still kind of shrink in size to fit on the top until going into combat").
        // WHILE THE CONTROLLER IS BIG (auto-attack or combat, [HomeGrow]) the same page under a BIGGER band (16:37: "once in
        // combat they should grow just a bit bigger than they are now"): the quests keep their place in the left column instead
        // of moving to a line of their own (user, 16:35 report: "when entering combat, lets not move the quests anymore")
        // THE HARMONY PASS (LATER 18, UI Art's HARMONY_SPEC section 2; the user: "Yes, build it as drawn"): every module is a well
        // MODULE_GAP apart and from the frame's edges; the quest module is the focused quest's card only (its own height), the chat
        // well takes the rest of the left column, the map well the right half
        val g = UiKit.MODULE_GAP
        val barsBottom = barsBottom(s)
        val top = barsBottom + g
        val cardBottom = top + cardHeight().also { laidCardH = it }
        // THE TEST (the user 2026-10-07 16:02: "I want to test seeing the focused quest above the mini-map on the home screen to see
        // that that looks like"): the card on top of the right column, the map under it, the chat the whole left column.
        // Today's layout = QUEST_ABOVE_MAP false (one line)
        // b121 (the user on b120: "I want to move the map name back to being above the quest, I also want to give the quest a see
        // through background so that I can still see the map"): the map takes the whole right column again, its zone band on top;
        // the card lies OVER the map under the band (inset as the band), see-through; the buffs row under the card
        laidOverMap = cardOverMap()
        if (cardOverMap()) {
            val qTop = top + ZONE_BAND_BOTTOM + 4f
            val qBottom = qTop + laidCardH
            // b125 (the user: "if there is no focused quest, I doesnt have to say that, it should just not show anything"): no card.
            // b128 (the user: "when opening buffs or debuffs the quest should hide to give that menu that opens more space"): no card
            // while the buffs' or the debuffs' list is open. The aura pane lies over the WHOLE map again (its coordinates are the map's:
            // the rim keep-out); its buff row stands flush under the card ([AuraPane.buffTop]: "the buffs need to be moved up to be
            // flush with the quest")
            val noCard = homeCard() == null || auraPane.open != null
            return if (noCard) mapOf(
                Part.BARS to RectF(0f, if (big(s)) 10f else 8f, w, barsBottom),
                Part.CHAT to RectF(g, top, c - 4f, h - g),
                Part.MAP to RectF(c + 4f, top, w - g, h - g),
                Part.AURAS to RectF(c + 4f, top, w - g, h - g),
            ) else mapOf(
                Part.BARS to RectF(0f, if (big(s)) 10f else 8f, w, barsBottom),
                Part.QUESTS to RectF(c + 12f, qTop, w - g - 8f, qBottom),
                Part.CHAT to RectF(g, top, c - 4f, h - g),
                Part.MAP to RectF(c + 4f, top, w - g, h - g),
                Part.AURAS to RectF(c + 4f, top, w - g, h - g),
            )
        }
        // ABOVE CHAT (the user 2026-10-09, Settings > Home): the card on top of the left column, the chat under it, the map the whole
        // right column (no cover: the player and the arrows keep their places). No focused quest = no card, the chat from the top. The
        // world notice stays at the top of the chat's well, so it stands right under the card, never on it
        if (homeCard() == null) return mapOf(
            Part.BARS to RectF(0f, if (big(s)) 10f else 8f, w, barsBottom),
            Part.CHAT to RectF(g, top, c - 4f, h - g),
            Part.MAP to RectF(c + 4f, top, w - g, h - g),
            Part.AURAS to RectF(c + 4f, top, w - g, h - g),
        )
        return mapOf(
            Part.BARS to RectF(0f, if (big(s)) 10f else 8f, w, barsBottom),
            Part.QUESTS to RectF(g, top, c - 4f, cardBottom),
            Part.CHAT to RectF(g, cardBottom + g, c - 4f, h - g),
            Part.MAP to RectF(c + 4f, top, w - g, h - g),
            // the buff / debuff buttons and their list, over the small map (user 17:30 / 17:49)
            Part.AURAS to RectF(c + 4f, top, w - g, h - g),
        )
    }

    private fun apply(s: HomeScreen, animate: Boolean) {
        val before = shown
        shown = s
        val open = s.full != null && !s.takeover
        // the full-screen chat is the real ChatView, lent only for as long as it shows
        val chatFull = s.full == HomeModule.CHAT && !s.takeover && lent(mapView)
        // THE CHAT MODULE WITH THE CONTROLLER (the user 2026-09-30: "the chat room, and the shortcut in the chat modual should function
        // the same way, but for some reason the module does not have the button controls"): while it shows, Home holds the generic
        // guest "home" (Quests.lua q10: LB / RB with it) and hands every press to the ChatView, which keeps the Chat room's rules
        // (the cursor in the chat bar, LB / RB the filters, X = Say, B steps back and at last leaves: MainActivity's onLeave shows
        // Home, which closes the module)
        if (chatFull != chatWanted) {
            chatWanted = chatFull
            if (chatFull) { auraPane.close(); chatView.cursorOnShow = true; if (homeGuestTap(GuestPad.cells(pad, GUEST_ID, hold = true))) Log.i(TAG, "chat module: hold") }
            else { if (homeGuestTap(GuestPad.cells(pad, GUEST_ID, hold = false))) Log.i(TAG, "chat module: free"); chatHeld = false }
        }
        if (chatFull) { lend(chatView, chatSlot); chatView.focusInput() }
        // closing with the glide: the chat stays in its slot until the box lands back on the pane ([chatReturn] at the glide's end)
        else if (!(animate && isShown && before?.full == HomeModule.CHAT && !before.takeover)) giveBack(chatView)
        // THE MAP MODULE WITH THE CONTROLLER (the user 2026-09-30: "Map room should behave the same in the map module on the home
        // screen"): the same guest "home", every press to the MapView's host keys (the Map room's rules: up / down the list, A focus,
        // X untrack, Y show, left / right pan, LB / RB zoom); its B with nothing open closes the module
        val mapFull = s.full == HomeModule.MAP && !s.takeover && lent(mapView)
        if (mapFull != mapWanted) {
            mapWanted = mapFull
            if (mapFull) { auraPane.close(); homePadN = -1; if (homeGuestTap(GuestPad.cells(pad, GUEST_ID, hold = true))) Log.i(TAG, "map module: hold") }
            else { if (homeGuestTap(GuestPad.cells(pad, GUEST_ID, hold = false))) Log.i(TAG, "map module: free"); mapHeld = false; mapBSaid = null; mapView.padHints = false; auraPane.leaveFull() }
        }
        // only while Home holds it: a fight that starts in the Map room must not shrink that room's map
        // the bars: one thin row at rest, the takeover in combat, the Cast room's own layout full screen
        // (cast session); between ROW and TAKEOVER the view follows its own height as Home animates it
        // the diamonds scale with the band (cast's DIAMONDS: small at rest, a bit bigger in combat; user 16:37); the thin-row
        // setting keeps ROW at rest and unfolds into the same diamonds in combat
        // not while the bars are LEAVING (a module opening over them): the band keeps the look it fades out with (Map's emulator
        // recording of the map's morph: the band switched look at full alpha in its first frames, then faded)
        if (lent(castView) && (s.full == null || s.takeover || s.full == HomeModule.BARS)) {
            castView.layout = when {
                s.full == HomeModule.BARS && !s.takeover -> CastView.Layout.FULL
                big(s) || s.rest == HomeRest.DIAMONDS -> CastView.Layout.DIAMONDS
                else -> CastView.Layout.ROW
            }
            // the big controller's bottom edge is the SWING TIMER (Cast draws it; Home leaves its own line out, see onDraw)
            castView.bottomLine = true
            castView.castTop = big(s)                                     // b-cast-bar-size: the top edge in a fight, the strip at rest
            // b-cast-any: at rest the line is the cast bar alone (every player cast, in or out of a fight); in combat the full line
            castView.castOnly = !s.takeover
            // p-swing-timer, batch 12 ("they should be seperate"): Settings > Home turns the SWING off; the cast bar stays
            castView.swingOn = HomeSettings.swingTimer
            // LATER item 16 (the user: "under the controller area ... the trigger modifiers ... light them up like in the cast menu when
            // they get pressed but dimmed when they arent"): Cast's trigger strip under the diamonds (cast 197ec48, DIAMONDS only)
            castView.triggerStrip = true
        }
        if (lent(mapView)) {
            mapView.compact = !(s.full == HomeModule.MAP && !s.takeover)
            // the small map's + and - step Settings' "Minimap zoom" itself (user, 09-29 16:35: "these buttons should directly
            // overwrite the minimap zoom in the settings"). Since 09-30 Home draws them itself, at the map's BOTTOM RIGHT ([AuraPane];
            // user: "the + and - need to be on the bottom right of the mini map so that the map name can be centered accross the
            // full space"), through map's stepMinimapZoom; the map's own top-right pair stays off
            mapView.compactZoomButtons = false
            // no close button stands over the map any more: its header and buttons need no room kept
            mapView.headerRightInset = 0f
            mapView.buttonTopInset = 0f
            // and no header band on Home's full map (user 15:08: "get rid of the ugly top bars"; map's switch)
            mapView.showHeader = mapView.compact
        }
        // Home's own B reader only while a full-screen chat or map hides the bars (the Cast view's reader is stopped then);
        // everywhere else B comes through the Cast view's reader
        watchB((s.full == HomeModule.CHAT || s.full == HomeModule.MAP) && !s.takeover && lent(mapView))
        val w = width.toFloat(); val h = height.toFloat()
        anim?.cancel()
        if (w <= 0f || h <= 0f) { chatReturn(); requestLayout(); return }       // placed at the first layout
        val to = places(s, w, h)
        for ((p, v) in views) if (to.containsKey(p)) {
            // a part coming back after being GONE starts from rest: no left-over slide, its own depth (the card over the map)
            if (v.visibility != View.VISIBLE && !classOpen && pauseT == 0f) { v.animate().cancel(); v.translationX = 0f; v.translationZ = restZ(p) }
            v.visibility = View.VISIBLE; v.alpha = 1f
        }
        if (!animate || before == null) {
            overlayFrom = overlayOf(s); overlayTo = overlayFrom; overlayOut = 1f; overlayIn = 1f
            flightClip(false)
            chatReturn()
            now.clear(); for ((p, r) in to) now[p] = RectF(r)
            for ((p, v) in views) if (!to.containsKey(p)) v.visibility = View.GONE
            settled = true
            requestLayout(); invalidate(); return
        }
        // THE ONE MOTION (HARMONY_SPEC section 3; the user picked "As in the clips"): one curve for all that moves (Motion.curve), the
        // times from Motion; the page that leaves fades out in the first FADE ms, the page that comes fades in over the last FADE ms
        // (the combat band hides the clock in its first 60). THE MAP'S MORPH (the user: "I want to make sure that the transition in map
        // size and place is smooth"; Map's mapView.morph): the map's own art glides with the box, the focused quest's card flies
        // between Home's quest module and the map list's top spot.
        val combat = s.takeover != before.takeover
        val wasMap = before.full == HomeModule.MAP && !before.takeover
        val isMap = s.full == HomeModule.MAP && !s.takeover
        val morphing = lent(mapView) && wasMap != isMap && now[Part.MAP] != null && to[Part.MAP] != null
        val ms = when {
            combat -> if (s.takeover) Motion.COMBAT_GROW else Motion.COMBAT_SHRINK
            open && !(before.full != null && !before.takeover) -> Motion.MODULE_OPEN
            !open && before.full != null && !before.takeover -> Motion.MODULE_CLOSE
            else -> Motion.MODULE_CLOSE                  // a re-placing (the card's height): the shorter of the two
        }
        val fadeMs = if (combat) 60f else FADE_MS
        val from = HashMap<Part, RectF>()
        for ((p, r) in now) from[p] = RectF(r)
        val coming = to.keys.filter { !now.containsKey(it) }
        for ((p, r) in to) if (!from.containsKey(p)) from[p] = shrunk(r)
        val gone = from.keys.filter { !to.containsKey(it) }
        for (p in coming) views[p]?.alpha = 0f
        settled = false
        val mFrom = from[Part.MAP]?.let { RectF(it) }
        val mTo = to[Part.MAP]?.let { RectF(it) }
        val card = if (morphing) morphCard(isMap, to) else null
        // THE CHAT'S GLIDE (see [chatGlideBox]): the full chat keeps its full box all along (laid out once), a window over it
        // glides from the chat pane's place to the frame (back on a close); the pane fades as the page does, the full chat comes in
        // over the first FADE ms of an open and leaves over the last FADE ms of a close, as the pane comes back over it
        val wasChat = before.full == HomeModule.CHAT && !before.takeover
        val isChat = s.full == HomeModule.CHAT && !s.takeover
        val chatFullBox = (to[Part.CHAT_FULL] ?: now[Part.CHAT_FULL])?.let { RectF(it) }
        val chatPaneBox = (if (isChat) now[Part.CHAT] else to[Part.CHAT])?.let { RectF(it) }
        val gliding = !combat && wasChat != isChat && chatFullBox != null && chatPaneBox != null && lent(chatView)
        if (gliding) {
            from[Part.CHAT_FULL] = RectF(chatFullBox!!)
            if (!isChat) from[Part.CHAT] = RectF(chatPaneBox!!)    // the pane the box lands on fades in where it is (no zoom)
            now[Part.CHAT_FULL] = RectF(chatFullBox)              // its one layout, at the full size, before the first frame
            placeFrame()
            chatSlot.clipToOutline = true
            chatGlide(if (isChat) 0f else 1f, chatPaneBox!!, chatFullBox)
            views[Part.CHAT_FULL]?.alpha = if (isChat) 0f else 1f
        }
        // only a part whose box changes needs a layout per frame (a fade alone does not)
        val moving = from.any { (p, f) -> to[p]?.let { t -> t != f } == true }
        questPane.cardHidden = card != null
        // the zone band and the clock fade with the page (Map's recording: they vanished at frame 0 on open, popped back at a close's end)
        overlayFrom = overlayOf(before); overlayTo = overlayOf(s); overlayOut = 1f; overlayIn = 0f
        // Map's call order: morph(0) BEFORE start (else one frame draws the new mode un-glided), morph(k) per frame, morph(1) at the end
        if (morphing) { flightClip(true); mapView.morph(0f, mFrom!!, mTo!!, isMap, card) }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = Motion.curve()
            addUpdateListener { a ->
                val k = a.animatedValue as Float
                if (morphing) mapView.morph(k, mFrom!!, mTo!!, isMap, card)
                for (p in from.keys) {
                    val f = from[p]!!
                    val t = to[p] ?: f
                    now[p] = RectF(lerp(f.left, t.left, k), lerp(f.top, t.top, k), lerp(f.right, t.right, k), lerp(f.bottom, t.bottom, k))
                }
                val t = a.animatedFraction * ms                       // the time in, linear
                overlayOut = (1f - t / fadeMs).coerceIn(0f, 1f)
                overlayIn = ((t - (ms - FADE_MS)) / FADE_MS).coerceIn(0f, 1f)
                for (p in gone) if (!(gliding && p == Part.CHAT_FULL)) views[p]?.alpha = overlayOut
                for (p in coming) if (!(gliding && p == Part.CHAT_FULL)) views[p]?.alpha = overlayIn
                if (gliding) {
                    chatGlide(if (isChat) k else 1f - k, chatPaneBox!!, chatFullBox!!)
                    views[Part.CHAT_FULL]?.alpha = if (isChat) (t / FADE_MS).coerceIn(0f, 1f) else 1f - overlayIn
                }
                if (cardOverMap()) auraPane.invalidate()            // b128: the buff row rides the card's move
                if (overlayFrom != overlayTo) invalidate()
                // only parts whose box moved are measured / placed (fix/controller placeFrame); a fade alone touches no layout (fix/rooms)
                if (moving) placeFrame()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    // fires on cancel too: the morph always ends (Map's safety net would after 700 ms anyway)
                    if (morphing) { mapView.morph(1f, mFrom!!, mTo!!, isMap, card); flightClip(false) }
                    if (gliding) { chatGlideBox.setEmpty(); chatSlot.clipToOutline = false; chatSlot.invalidateOutline() }
                    chatReturn()
                    if (morphing && !isMap && card != null) questPane.plateToRest()   // b121: landed full, eases back to see-through
                    questPane.cardHidden = false
                    overlayFrom = overlayTo; overlayOut = 1f; overlayIn = 1f
                    for (p in coming) views[p]?.alpha = 1f
                    for (p in gone) { views[p]?.visibility = View.GONE; views[p]?.alpha = 1f; now.remove(p) }
                    settled = true
                    requestLayout()                                  // one full pass where it lands
                    invalidate()
                }
            })
            start()
        }
        invalidate()
    }

    /** r-combat-anim-off: the fight's BIGGER band ("Combat layout: Grow", the default); "Same size" keeps the rest's place and size */
    private fun big(s: HomeScreen) = s.takeover && HomeSettings.combatGrow

    private fun barsBottom(s: HomeScreen) = when {
        big(s) -> 10f + COMBAT_BARS + CastBarPlace.TOP_STRIP          // b-cast-bar-size: the top-edge cast bar's room in a fight
        s.rest == HomeRest.DIAMONDS -> 8f + REST_DIAMONDS_BARS + CastBarPlace.REST_STRIP   // its strip under the buttons at rest
        else -> 120f + CastBarPlace.REST_STRIP
    }

    /**
     * The focused quest's card in HomeView px for the map's morph: where it sits now ([opening]) or where it will sit when the close
     * lands. NULL until the harmony pass's quest module draws the one shared card (UI Art's UiKit.questCard): with null, nothing
     * flies and the map's art still glides.
     */
    private fun morphCard(opening: Boolean, to: Map<Part, RectF>): RectF? {
        if (homeCard() == null) return null                  // no focused quest in the tracker: the empty card stays, nothing flies
        return (if (opening) now[Part.QUESTS] else to[Part.QUESTS])?.let { RectF(it) }
    }

    /** the focused quest's card: the Map room's own object (its number, colour, objectives), read at draw / layout time (Map's note:
     *  Home gets the quests document before the map does); null = the empty card */
    private fun homeCard(): UiKit.QuestCard? = mapView.focusedCard()

    /** the quest module's height: the card's at Home's scale */
    private fun cardHeight(): Float = when {
        homeCard() == null -> 0f                                                          // b125: no focused quest, no card
        HomeSettings.questMin ->                                                             // b125: minimized (the kit's height)
            UiKit.questCardHeight(homeCard(), UiKit.CARD_HOME_SCALE, collapsed = true)
        else -> UiKit.questCardHeight(homeCard(), UiKit.CARD_HOME_SCALE)
    }
    private var laidCardH = -1f
    /** where the last layout put the card (a Settings change re-lays only when it moved) */
    private var laidOverMap = true
    /** the Grow / Same size choice the page was last laid with (Settings > Home "Combat layout") */
    private var laidGrow = HomeSettings.combatGrow

    /** the chat's glide at [k] (0 = the pane's box, 1 = the frame), as a window in the full chat's own px */
    private fun chatGlide(k: Float, pane: RectF, full: RectF) {
        chatGlideBox.set(lerp(pane.left, full.left, k) - full.left, lerp(pane.top, full.top, k) - full.top,
            lerp(pane.right, full.right, k) - full.left, lerp(pane.bottom, full.bottom, k) - full.top)
        chatSlot.invalidateOutline()
    }

    /**
     * The chat pane's box in Home's px for the screen Home is going to (null before the first layout, or with no pane). The CHAT ROOM's
     * glide (CompanionHost.chatGlideStart) leaves from it when the pane's tap opens the room and lands on it when the room goes back Home.
     */
    fun chatPaneBox(): RectF? {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return null
        return places(flow.screen, w, h)[Part.CHAT]?.let { RectF(it) }
    }

    /**
     * The chat pane as it looks now, on Home's ink, in a bitmap [w] x [h] (its box's size), or null when the pane is not that size
     * (not laid out yet, or caught mid-move). The Chat room's glide grows THIS from the pane's box (Thor batch5: the pane itself must
     * grow, not a new window appear in its place) and hands over to the room only as it nears full size.
     */
    fun chatPanePicture(w: Int, h: Int): android.graphics.Bitmap? = try {
        if (w <= 0 || h <= 0 || abs(chatPane.width - w) > 2 || abs(chatPane.height - h) > 2) null
        else android.graphics.Bitmap.createBitmap(chatPane.width, chatPane.height, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
            b.eraseColor(Theme.ink)
            chatPane.draw(Canvas(b))
        }
    } catch (t: Throwable) { null }

    /** the chat goes back to its room once Home no longer shows it full (after a close's glide has landed) */
    private fun chatReturn() { if (!chatWanted) giveBack(chatView) }

    private fun shrunk(r: RectF): RectF {
        val cx = r.centerX(); val cy = r.centerY()
        return RectF(cx - r.width() * 0.4f, cy - r.height() * 0.4f, cx + r.width() * 0.4f, cy + r.height() * 0.4f)
    }

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        // a new frame (the strip's height changed): every place is recomputed, not scaled. The screen is read WHEN THE POST RUNS: one
        // captured here could undo a change queued just before (Home's first layout during an arrival mid-fight landed back at rest)
        post { apply(shown ?: flow.screen, animate = false) }
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec))
        if (typeWanted) typeTool.measure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(wSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(hSpec), MeasureSpec.EXACTLY))
        val cw = if (classRect.isEmpty) MeasureSpec.getSize(wSpec) else classRect.width()
        val ch = if (classRect.isEmpty) MeasureSpec.getSize(hSpec) else classRect.height()
        classLayer.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        wheelLayer?.measure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(wSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(hSpec), MeasureSpec.EXACTLY))
        for ((p, v) in views) {
            if (v.visibility == View.GONE) continue
            val r = now[p] ?: continue
            v.measure(MeasureSpec.makeMeasureSpec(max(1, r.width().toInt()), MeasureSpec.EXACTLY),
                      MeasureSpec.makeMeasureSpec(max(1, r.height().toInt()), MeasureSpec.EXACTLY))
        }
    }

    /**
     * ONE FRAME OF THE MOTION (r-combat-anim-off: "it shouldn't steal frames from the game"): each part that moved is measured (only when
     * its size changed) and placed right here. It was requestLayout() per frame, which sent a measure / layout pass through the WHOLE
     * window (the strip, every room's view) about 15 times in a 240 ms switch; the landing frame still does one full pass.
     */
    private fun placeFrame() {
        for ((p, v) in views) {
            if (v.visibility == View.GONE) continue
            val f = now[p] ?: continue
            val x0 = f.left.toInt(); val y0 = f.top.toInt()
            val x1 = max(x0 + 1, f.right.toInt()); val y1 = max(y0 + 1, f.bottom.toInt())
            val resized = v.width != x1 - x0 || v.height != y1 - y0
            if (!resized && v.left == x0 && v.top == y0 && !v.isLayoutRequested) continue
            if (resized || v.isLayoutRequested)
                v.measure(MeasureSpec.makeMeasureSpec(x1 - x0, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(y1 - y0, MeasureSpec.EXACTLY))
            v.layout(x0, y0, x1, y1)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (typeWanted) typeTool.layout(0, 0, r - l, b - t)                  // f-trackpad-keyboard: the whole page
        // f-class-radial: its slot (the wheel between the columns), or the whole page for the hint
        if (classRect.isEmpty) classLayer.layout(0, 0, r - l, b - t) else classLayer.layout(classRect.left, classRect.top, classRect.right, classRect.bottom)
        wheelLayer?.layout(0, 0, r - l, b - t)                              // f-radial-behind: the whole page, under the columns
        for ((p, v) in views) {
            if (v.visibility == View.GONE) continue
            val f = now[p] ?: continue
            val x0 = f.left.toInt(); val y0 = f.top.toInt()
            v.layout(x0, y0, max(x0 + 1, f.right.toInt()), max(y0 + 1, f.bottom.toInt()))
        }
    }

    /**
     * The dividers between the parts, at rest and in combat (a module full screen has none). In combat the line under the bars
     * is the Cast session's: the SWING TIMER along the bottom of the controller area (user, 16:35: "When going into combat, I
     * want to add a swing timer to the bottom of the controller area that divides the spaces"), a plain divider when no swing
     * is known, drawn by CastView inside the band.
     */
    override fun onDraw(c: Canvas) {
        // no dividers any more: every module is its own well (the harmony pass, LATER 18)
    }

    /**
     * Over the page: the zone band on the small map and the clock. (THE RED REMINDER, user 17:17, is not drawn here: the Cast
     * view paints it behind its pieces, see [tick].)
     */
    private val pInk = Paint().apply { color = Theme.ink }
    private val wellPath = Path()
    private fun mapWell(c: Canvas, m: RectF, alpha: Float) {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val save = c.save()
        c.clipRect(m)
        wellPath.reset(); wellPath.addRoundRect(m, UiKit.MODULE_R, UiKit.MODULE_R, Path.Direction.CW)
        c.clipOutPath(wellPath)
        pInk.alpha = a
        c.drawRect(m, pInk)
        c.restoreToCount(save)
        UiKit.module(c, m, alpha = a, edgeOnly = true)
    }

    /** what of the page's overlay a state shows: the small map's zone band, the clock + battery between the small diamonds */
    private data class Overlay(val zone: Boolean, val clock: Boolean)
    private fun overlayOf(s: HomeScreen?): Overlay {
        if (s == null) return Overlay(false, false)
        val page = s.full == null || s.takeover
        // the clock only with the small diamonds at rest: the grown controller and the thin row leave no gap there
        return Overlay(page, page && !big(s) && s.rest == HomeRest.DIAMONDS)
    }
    /** the overlay before and after the running move, and how far it is (1 = settled on [overlayTo]) */
    private var overlayFrom = Overlay(false, false)
    private var overlayTo = Overlay(false, false)
    private var overlayOut = 1f
    private var overlayIn = 1f
    private fun mix(a: Boolean, b: Boolean): Float = when { a && b -> 1f; a -> overlayOut; b -> overlayIn; else -> 0f }

    // ================================================================ the radial pause menu's slide (HomePauseView, the user 2026-10-08)

    /** the pause menu's point in its motion (ms along the open, 0 = at rest) and each part's (dx, dy, alpha) for it */
    private var pauseT = 0f
    private var pauseTop = floatArrayOf(0f, 0f, 1f)
    private var pauseMap = floatArrayOf(0f, 0f, 1f)

    /**
     * THE PANELS MAKE SPACE (UI Art's PAUSE_RADIAL_SPEC): the controller band goes up, the chat column left, the map (with the quest
     * card over it and the auras) right, each on UiKit.pauseOut's curve; [t] = 0 puts them all back. Only Home's own parts move (the
     * bar is the pause view's); a move of Home's own state never runs meanwhile (the wheel holds the controller and covers the touch).
     */
    fun pauseSlide(t: Float) {
        if (t == pauseT) return
        pauseT = t
        val screen = RectF(0f, 0f, width.toFloat(), height.toFloat())
        pauseTop = UiKit.pauseOut(UiKit.PAUSE_TOP, t, screen)
        val left = UiKit.pauseOut(UiKit.PAUSE_LEFT, t, screen)
        pauseMap = UiKit.pauseOut(UiKit.PAUSE_MAP, t, screen)
        for ((p, v) in views) {
            val o = when (p) {
                Part.BARS -> pauseTop
                Part.CHAT, Part.CHAT_FULL -> left
                Part.QUESTS -> if (cardOverMap()) pauseMap else left
                Part.MAP, Part.AURAS -> pauseMap
            }
            // FULLY OFF, whatever the part's size (the user 2026-10-09: the FULL-SCREEN map "only slide[s] ... half way out"): the kit's
            // distance is for the compact parts; a bigger one goes as far as it needs to leave the screen, on the same curve
            val endX = UiKit.pauseOut(if (p == Part.BARS) UiKit.PAUSE_TOP else if (o === left) UiKit.PAUSE_LEFT else UiKit.PAUSE_MAP,
                UiKit.PAUSE_OPEN_MS, screen)
            val kx = when {
                endX[0] > 0f -> ((width - v.left) / endX[0]).coerceAtLeast(1f)
                endX[0] < 0f -> (v.right / -endX[0]).coerceAtLeast(1f)
                else -> 1f
            }
            val ky = if (endX[1] < 0f) (v.bottom / -endX[1]).coerceAtLeast(1f) else 1f
            v.translationX = o[0] * kx; v.translationY = o[1] * ky
            // the state's own fades leave every shown part at 1 when settled: the pause's alpha is the only one meanwhile
            if (v.visibility == View.VISIBLE) v.alpha = o[2]
        }
        invalidate()
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        if (shown == null || typeWanted) return                          // f-trackpad-keyboard: the tool is a clean page
        // the zone band and the clock FADE with a move (the harmony pass; they used to vanish at a move's start and pop back at
        // its end), drawn where their part is now (and, while the pause menu slides, where the map / the band have gone)
        val zoneA = mix(overlayFrom.zone, overlayTo.zone) * pauseMap[2]
        val clockA = mix(overlayFrom.clock, overlayTo.clock) * pauseTop[2]
        val pauseSave = if (pauseT > 0f) c.save() else -1
        if (pauseSave >= 0) c.translate(pauseMap[0], pauseMap[1])
        // THE ZONE BAND across the small map's top, its whole width, the name centred (user, 09-29 17:30: "The current map
        // name should show over the top of the minimap like it does normally"; 09-30: centred across the full space): the
        // classic minimap's own zone bar (UI Art's UiKit.zoneBand)
        // THE MAP'S WELL: the map drawn square by MapView, Home rounds it like every module (the frame's ink in the corners outside
        // MODULE_R, then the well's edge), at the page's alpha (a full map has no well: the corners fade out with the page)
        // f-class-radial rework (the user on 1.0.8-test4: "the border and map name are not moving still on the map module"): the well's
        // edge and the zone band are painted HERE, from the map's laid-out place, so they follow the map's slide (its translationX) too
        val mapSlide = mapSlot.translationX
        val slideSave = if (mapSlide != 0f) c.save() else -1
        if (slideSave >= 0) c.translate(mapSlide, 0f)
        if (zoneA > 0.01f) now[Part.MAP]?.let { m -> mapWell(c, m, zoneA) }
        if (zoneA > 0.01f && zoneText.isNotEmpty()) now[Part.MAP]?.let { m ->
            // the name is half the bar's height (UiKit.zoneBand): a 64 px bar = 32 px text (user 09-30: "the map name is SUPER
            // small and hard to read"); map's arrow keeps below ZONE_BAND_BOTTOM
            val band = RectF(m.left + 8f, m.top + ZONE_BAND_TOP, m.right - 8f, m.top + ZONE_BAND_BOTTOM)
            val save = if (zoneA < 0.99f) c.saveLayerAlpha(band.left - 4f, band.top - 4f, band.right + 4f, band.bottom + 4f, (zoneA * 255).toInt()) else -1
            UiKit.zoneBand(c, band, zoneText, Theme.zoneColour(zonePvp))
            if (save >= 0) c.restoreToCount(save)
        }
        if (slideSave >= 0) c.restoreToCount(slideSave)
        // THE CLOCK AND THE BATTERY at the top centre, between the two diamond groups (17:30: "the time should be on display
        // at the top center between the controls, right under it, a battery Icon with my current system charge").
        // not before the controller layout has come: the Cast view's "Waiting" stands there (UI Art, seen on its emulator)
        if (pauseSave >= 0) { c.restoreToCount(pauseSave); c.save(); c.translate(pauseTop[0], pauseTop[1]) }
        if (clockA > 0.01f && barsSeen && clockText.isNotEmpty() && batteryPct >= 0) now[Part.BARS]?.let { b ->
            val save = if (clockA < 0.99f) c.saveLayerAlpha(b.left, b.top, b.right, b.bottom, (clockA * 255).toInt()) else -1
            UiKit.clockChip(c, b.centerX(), b.top + 10f, clockText, batteryPct, charging)
            if (save >= 0) c.restoreToCount(save)
        }
        if (pauseSave >= 0) c.restoreToCount(pauseSave)
    }

    // ================================================================ the bars: a tap opens them full screen

    private var barsDown = false
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        // f-trackpad-keyboard: the tool's touches are the tool's (a drag starting where the bars lie was taken as a tap on the bars)
        if (typeWanted) { barsDown = false; return false }
        if (flow.full == HomeModule.BARS || barsSlot.visibility != View.VISIBLE) return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN) barsDown = inBars(e)
        return barsDown
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!barsDown) return false
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            barsDown = false
            if (inBars(e) && flow.tapBars()) changed()
        }
        return true
    }

    private fun inBars(e: MotionEvent) =
        e.x >= barsSlot.left && e.x < barsSlot.right && e.y >= barsSlot.top && e.y < barsSlot.bottom

    // ================================================================ the quests part (spec parts 1, 2, 3, 6, 7)

    /** the tracked quests in the left column, at rest and in combat (the focused one highlighted) */
    inner class QuestPane(c: Context) : View(c) {
        private var noteText: String? = null
        private var noteUntil = 0L
        private var scroll = 0f
        private var listTop = 0f
        private val rows = ArrayList<Pair<RectF, Int>>()     // row -> quest id: the ROW is the touch target

        fun note(s: String) { noteText = s; noteUntil = System.currentTimeMillis() + NOTE_MS; invalidate() }

        private fun state(q: HomeQuest) = if (q.ready) UiKit.Quest.TURN_IN else UiKit.Quest.PROGRESS

        private val pGround = Paint().apply { color = Theme.panel }

        /** the map's morph flies this card: it is not drawn here meanwhile (the flight draws it) */
        var cardHidden = false
            set(v) { if (field != v) { field = v; invalidate() } }

        /** b121: the plate's opacity over the map, 0 = see-through (at rest), 1 = full (the user: "goes back to normal opacity
         *  when opening the map module"). The flight draws the card full; after a close lands, the plate eases back to see-through */
        private var plate = 0f
        fun plateToRest() {
            if (!cardOverMap()) return
            ValueAnimator.ofFloat(1f, 0f).apply {
                duration = Motion.BAR_IN * 2; interpolator = Motion.curve()
                addUpdateListener { plate = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            rows.clear()
            // THE HARMONY PASS (LATER 18; the user: "change the quests to only show the focused quest"): ONLY the focused quest, as the
            // ONE card the Map room's list draws too (Map's focusedCard(): the same object, number and colour), at CARD_HOME_SCALE; the
            // empty card ("No focused quest") when there is none. The pane IS the card's box (places() sizes it by questCardHeight).
            // b125: minimized = the title row only once the shrink has landed; while the box eases the kit fades the objective lines
            // with the room they have (UI Art), so no half line peeks
            val q = homeCard()
            val folded = HomeSettings.questMin &&
                h <= UiKit.questCardHeight(q, UiKit.CARD_HOME_SCALE, collapsed = true) + 0.5f
            if (!cardHidden) UiKit.questCard(c, RectF(0f, 0f, w, h), q, scale = UiKit.CARD_HOME_SCALE,
                bgAlpha = if (cardOverMap()) lerp(UiKit.CARD_OVER_MAP_ALPHA.toFloat(), 255f, plate).toInt() else 255,
                collapsed = folded)
            val n = noteText
            if (n != null && System.currentTimeMillis() < noteUntil) {
                val p = UiKit.bodyPaint(24f, Theme.warn)
                c.drawText(TextUtils.ellipsize(n, p, w - 36f, TextUtils.TruncateAt.END).toString(), 18f, h - 16f, p)
                postInvalidateDelayed(NOTE_MS)
            } else noteText = null
        }

        private fun line(c: Canvas, w: Float, h: Float, s: String) {
            val p = UiKit.bodyPaint(27f, Theme.dim)
            c.drawText(s, 24f, h / 2f - (p.ascent() + p.descent()) / 2f, p)
        }

        private fun list(c: Canvas, w: Float, h: Float, top: Float, right: Float, rowH: Float, gap: Float, inset: Float) {
            val tracked = quests.tracked
            val y0 = UiKit.trackerHeader(c, 0f, top, right, "Quests", tracked.size.toString())
            listTop = y0
            c.save(); c.clipRect(0f, y0, w, h)
            var y = y0 + gap - scroll
            for (q in tracked) {
                val r = RectF(inset, y, w, y + rowH)
                if (r.bottom > y0 && r.top < h) {
                    // no disc, and the focused quest HIGHLIGHTED with the kit's "chosen" look (user, 16:37: "the focused quest should
                    // just be highlighted, so that people arent trying to hit that tiny button"); the whole row is the tap target
                    UiKit.questRow(c, r, q.heading, tierColour(q.tier), q.progress, state(q), focus = q.focused,
                        disc = false, highlight = q.focused)
                    if (q.id == quests.pending(System.currentTimeMillis())) UiKit.glow(c, r, Theme.gold, 0.25f)
                }
                rows.add(RectF(r.left, max(r.top, y0), r.right, r.bottom) to q.id)
                y += rowH
            }
            c.restore()
            if (tracked.isEmpty()) line(c, w, y0 + 60f, "No tracked quests")
        }

        private fun maxScroll(): Float = max(0f, listTop + 4f + quests.tracked.size * 124f - height)

        private var downY = 0f; private var dragging = false; private var startScroll = 0f

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.y; dragging = false; startScroll = scroll }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && abs(e.y - downY) > SLOP) dragging = true
                    if (dragging) { scroll = (startScroll + downY - e.y).coerceIn(0f, maxScroll()); invalidate() }
                }
                MotionEvent.ACTION_UP -> if (!dragging) tap(e.x, e.y)
            }
            return true
        }

        private fun tap(x: Float, y: Float) {
            // b125 (the user: "instead of the focused quest going to the quests tab, I wan it to "Minimize" the focused quest down to see
            // more of the minimap. tapping it again should restore its full size"): over the map a tap folds the card to its title line
            // and back (the pane's box shrinks; the card's own clip hides the objectives). The old layout still opens the Quests room
            // both places fold (the user 2026-10-09: the Chat placement keeps "its minimize-on-tap")
            HomeSettings.questMin = !HomeSettings.questMin; changed()
        }
    }

    // ================================================================ the chat part (spec parts 2, 4, 6)

    /** Home's own chat lines: the log, its half of the left column (at rest and in combat) */
    inner class ChatPane(c: Context) : View(c) {
        private val tp = TextPaint(UiKit.bodyPaint(27f))
        private val cache = HashMap<HomeChatLine, Pair<Int, StaticLayout>>()

        fun refresh() { cache.keys.retainAll(chat.all.toSet()); invalidate() }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            UiKit.asBody(tp)
            // the chat's WELL (the harmony pass: every module the same well), the log inside its padding, from the bottom
            UiKit.module(c, RectF(0f, 0f, w, h))
            // no "General" tab (user, 15:15: "remove the general tab from the chat ... just visual clutter")
            val pad = UiKit.MODULE_PAD
            // the game's world notice (the shard transfer's countdown) pinned at the top of the well; the log starts under it
            val now = android.os.SystemClock.uptimeMillis()
            notice = NoticeModel.live(notice, now)
            val top = pad + (notice?.let { n ->
                postInvalidateDelayed(1_000L - (now - n.at).mod(1_000L))          // the next whole second of the game's count
                UiKit.notice(c, RectF(pad, pad, w - pad, h - pad), n.minutes(now), n.seconds(now))
            } ?: 0f)
            tp.textSize = 27f
            val width = (w - 2f * pad).toInt().coerceAtLeast(10)
            var y = h - pad
            c.save(); c.clipRect(pad, top, w - pad, h - pad)
            for (l in chat.all.asReversed()) {
                var lay = layoutOf(l, width)
                if (y - lay.height < top) {
                    // a message too long for what is left is NEVER dropped (tracking 17:21: the player could miss a whisper):
                    // its first lines that fit, the last one ending in an ellipsis; whole lines only (a line cut through the
                    // middle was seen on the Thor)
                    lay = cutLayout(l, width, lay, y - top) ?: break
                }
                y -= lay.height
                c.save(); c.translate(pad, y); lay.draw(c); c.restore()
                y -= 6f                                     // spec: 6 px between messages
                if (y < top) break
            }
            c.restore()
        }

        /** [l] in as many whole lines as fit [room] px, the last one ending in an ellipsis; null when not even one line fits */
        private fun cutLayout(l: HomeChatLine, width: Int, full: StaticLayout, room: Float): StaticLayout? {
            val lineH = full.height.toFloat() / full.lineCount.coerceAtLeast(1)
            val lines = (room / lineH).toInt()
            if (lines < 1) return null
            val s = format(l)
            return StaticLayout.Builder.obtain(s, 0, s.length, tp, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setLineSpacing(0f, 1.12f)
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build()
        }

        private fun layoutOf(l: HomeChatLine, width: Int): StaticLayout {
            val key = width * 100 + tp.textSize.toInt()
            cache[l]?.let { if (it.first == key) return it.second }
            val s = format(l)
            val lay = StaticLayout.Builder.obtain(s, 0, s.length, tp, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setLineSpacing(0f, 1.12f).build()           // spec: line spacing 1.12
            cache[l] = key to lay
            return lay
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            // a tap opens the full CHAT ROOM (the user, 2026-10-05: "Tap Home's chat panel", now that the Controller room has Chat's
            // spot on the bar); refused in combat as the full-screen chat was (the fight's takeover owns the screen then)
            if (e.actionMasked == MotionEvent.ACTION_UP && !flow.takeover) openRoom(Room.CHAT)
            return true
        }
    }

    // ================================================================ buffs and debuffs over the small map

    /**
     * THE PLAYER'S BUFFS AND DEBUFFS on the small map (user, 17:30: "show the buffs in the top left of the mini map, and the debuffs
     * on the bottom left, give ma a faded button that these all live inside so that I can open them up and select each on
     * individually and read them or remove them"; 17:49: the list opens OVER THE SMALL MAP, in combat it shows the last list before
     * the fight greyed, a buff is removed out of combat only). The pane lies over the map and takes only the touches on its buttons
     * (and every touch while its list is open); the rest fall through to the map. Select a row, then ONE action: Remove.
     */
    /**
     * THE GAME'S DURABILITY WARNING on Home (f-durability; the user after the Thor test, 15 Oct: "I wanted to show the heads up display
     * of broken armor from the top screen and move that to the bottom between the buffs"; the place, the user's pick: between the buff
     * row and the debuff row): `bags.equipped.durw`, the game's own figure as it draws it (Bags.lua reads its DurabilityFrame), drawn
     * by [AuraPane] while the game shows it. The top screen's copy goes with the buffs (Hub's "hud" element).
     */
    fun durability(durw: JSONObject?) {
        val was = durabilityWarning?.toString()
        durabilityWarning = durw
        if (was != durw?.toString()) auraPane.invalidate()
    }
    private var durabilityWarning: JSONObject? = null

    inner class AuraPane(c: Context) : View(c) {
        /** null = closed; false = the buffs' list; true = the debuffs' */
        var open: Boolean? = null
            private set(v) {
                val was = field; field = v
                // b129: in the open map the map module already holds the guest "home": the list neither holds nor frees it
                if (!fullMap()) { if (was == null && v != null) holdPad() else if (was != null && v == null) freePad() }
                if ((was == null) != (v == null)) onStateChanged?.invoke()      // the Home button's red B follows at once
                // b128: the quest card makes way while a list is open, and comes back when it closes
                if ((was == null) != (v == null) && cardOverMap()) post { shown?.let { apply(it, animate = isShown) } }
            }
        private var sel: Int? = null

        // ------------------------------------------------ the list with the controller
        // The user 2026-09-30, second ruling (replaces "A selects, then A on Remove"): "On the buff menu, instead of a to select a buff,
        // add up and down arrow icons to the cursor and make it auto update on what's highlighted, remover the highlight on the remove
        // button and keep the a there. We need to change the home menu to the red with the b button to return at any time in this menu."
        // While a list is open Home holds the controller as the generic guest "home" (Quests.lua q9+), so the presses stay off the
        // game: up / down move the cursor (the kit's golden box with UI Art's up / down arrows) and the aura under it IS the chosen one
        // (its tooltip shows at once); A = Remove (out of combat, the existing pick + cancel; the button wears the A, no gold box); B
        // closes the list at any time, and the Home button shows its red state with B meanwhile. A hold the game refuses (a fight)
        // leaves the list to the finger.
        var padHeld = false; private set
        private var padN = -1
        private var cur: Int? = null                 // the aura the cursor is on: always the chosen one while the controller holds

        private fun guestTap(cells: List<Pair<String, Int>>?): Boolean = cells != null && pad != null && TapBridge.taps(context, pad, cells) { }

        private fun holdPad() {
            resetCursor()
            padN = -1
            if (guestTap(GuestPad.cells(pad, GUEST_ID, hold = true))) Log.i(TAG, "auras: hold")
        }

        /** the cursor on the chosen aura, else the first, which becomes the chosen one (the highlight follows the cursor) */
        private fun resetCursor() {
            cur = sel ?: shownList().firstOrNull()?.id
            sel = cur
        }

        private fun freePad() {
            if (guestTap(GuestPad.cells(pad, GUEST_ID, hold = false))) Log.i(TAG, "auras: free")
            padHeld = false; cur = null
            castView.quietPresses = false
        }

        private fun shownList(): List<HomeAura> = if (open == true) auras.shownDebuffs else auras.shownBuffs

        /** the preview harness only: draw the list as if the controller held it (no taps: the preview has no game) */
        fun previewPad() { padHeld = true; if (cur == null) resetCursor(); invalidate() }

        private fun removable(id: Int?): Boolean =
            open == false && id != null && auras.shownBuffs.firstOrNull { it.id == id }?.enchant == false

        fun onGuestAck(msg: JSONObject) {
            when (msg.optString("op")) {
                "hold" -> if (msg.optBoolean("ok") && open != null) {
                    padHeld = true
                    // B returns Home at any time here: the Home button shows its red state with B; Home's top controller stays quiet
                    guestTap(GuestPad.bnext(pad, home = true))
                    castView.quietPresses = true
                    if (cur == null) resetCursor()
                    invalidate()
                } else if (!msg.optBoolean("ok")) { Log.i(TAG, "auras: hold refused (${msg.optString("why")})"); guestTap(GuestPad.cells(pad, GUEST_ID, hold = false)) }
                "free", "leave" -> { padHeld = false; castView.quietPresses = false; invalidate() }
            }
        }

        fun onPad(msg: JSONObject) {
            if (msg.has("lost")) { padHeld = false; castView.quietPresses = false; invalidate(); return }
            val n = msg.optInt("n", -1)
            if (n == padN) return
            padN = n
            if (open == null) return
            padHeld = true
            val list = shownList()
            val i = list.indexOfFirst { it.id == cur }
            when (msg.optString("k")) {
                "PADDUP" -> if (i > 0) { cur = list[i - 1].id; sel = cur }
                "PADDDOWN" -> if (i in 0 until list.size - 1) { cur = list[i + 1].id; sel = cur }
                // A = Remove the highlighted aura; a debuff or a weapon enchant says why not (the detail shows the same)
                "PAD1" -> sel?.let { id -> if (removable(id)) remove(id)
                    else note(if (open == true) "Debuffs cannot be removed" else "A weapon enchant cannot be removed here") }
                "PAD2" -> close()
            }
            keepInView()
            invalidate()
        }

        /** the list scrolls so the cursor's row stays whole in view */
        private fun keepInView() {
            val i = shownList().indexOfFirst { it.id == cur }
            if (i < 0) return
            val top = i * ROW_H; val visible = rowsBottom - rowsTop
            if (top < scroll) scroll = top
            else if (top + ROW_H > scroll + visible) scroll = top + ROW_H - visible
        }

        /** the A glyph in the Settings' button layout, as the rest of the app draws it */
        private fun aGlyph(): String = when (CastSettings.buttonLayout) {
            CastSettings.ButtonLayout.XBOX -> "ltr_a"
            CastSettings.ButtonLayout.PLAYSTATION -> "shp_cross"
            else -> "rev_a"
        }
        private val buffBox = RectF(); private val debuffBox = RectF()
        private val panel = RectF(); private val removeBox = RectF()
        private val zoomIn = RectF(); private val zoomOut = RectF()
        private var zoomTouchAt = 0L
        private var zoomDown = 0                     // the zoom button under the finger: -1 = in (+), +1 = out (-), 0 = none
        private val rows = ArrayList<Pair<RectF, Int>>()
        private var scroll = 0f; private var rowsTop = 0f; private var rowsBottom = 0f
        private var noteText: String? = null; private var noteUntil = 0L
        private val tp = TextPaint(UiKit.bodyPaint(23f, Theme.textDim))

        fun note(t: String) { noteText = t; noteUntil = System.currentTimeMillis() + NOTE_MS; invalidate() }
        fun close() { if (open != null) { open = null; sel = null; scroll = 0f; invalidate() } }
        /** the preview harness: open a list with row [select] chosen */
        fun show(debuff: Boolean, select: Int?) {
            sel = select?.let { (if (debuff) auras.shownDebuffs else auras.shownBuffs).getOrNull(it)?.id }
            open = debuff
            resetCursor()
            scroll = hitScroll(debuff, sel)
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            drawPane(c)
            // map's quest arrow slides along the small map's edge away from these boxes (map's rimKeepOut; the pane lies exactly
            // over the map, so its coordinates are the map's). An unchanged list costs nothing (map compares). NOT the + / - column
            // (the user on the test build, 2026-10-12: "The quest arrow is getting stuck on the + and - buttons. It should show on
            // the edge of the screen for those"): the arrow keeps the true edge there, under the buttons, which rest at ZOOM_MIN
            if (lent(mapView) && !fullMap()) mapView.rimKeepOut = if (!mapView.compact) emptyList()
                else listOf(buffBox, debuffBox, panel, warnBox).filter { !it.isEmpty }
            // Map 2026-10-09 (the user: the quest arrow under the card; the player lower): how far down the card covers the map now
            if (lent(mapView) && !fullMap()) mapView.compactCover = cardCover()
        }

        private fun drawPane(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val now = System.currentTimeMillis()
            rows.clear(); buffBox.setEmpty(); debuffBox.setEmpty(); panel.setEmpty(); removeBox.setEmpty()
            zoomIn.setEmpty(); zoomOut.setEmpty()
            val full = fullMap()
            if (!full && (open == null || !auras.live(now))) zoom(c, w, h, now)      // the open list covers that corner
            if (full) { drawFull(c, w, h, now); return }
            if (!auras.live(now)) { open = null; warning(c, buffTop(), h - 4f); return }
            val nb = auras.shownBuffs.size; val nd = auras.shownDebuffs.size
            if (open == false && nb == 0 || open == true && nd == 0) { open = null; sel = null }
            // THE AURAS LISTED, NOT A BUTTON (the user 2026-09-30, LATER item 6: "I dont want it to show the triangles, I want it to
            // actually list them out and then go to the menus like it does now when I click on them"): the game's buff row, its
            // icons with the time left under each; the buffs from the top left down, the debuffs from the bottom left up, left of
            // the zoom buttons. The row is the touch target (48 dp high at least); a tap opens the list with that aura chosen.
            cells.clear()
            // b128 (UI Art's check of the open list): the other kind's row lies under the open list's see-through plate, so it is
            // not drawn meanwhile (a dark square showed through the list's corner)
            if (nb > 0 && open != true) auraRow(c, auras.shownBuffs, false, buffTop(), w - 12f, buffBox, now)
            if (nd > 0 && open != false) auraRow(c, auras.shownDebuffs, true, h - 4f, (if (zoomOut.isEmpty) w - 4f else zoomOut.left) - 12f, debuffBox, now)
            if (open == null) warning(c, if (buffBox.isEmpty) buffTop() else buffBox.bottom, if (debuffBox.isEmpty) h - 4f else debuffBox.top)
            val which = open ?: return
            list(c, which, w, h, now)
        }

        /** b129: the pane lies over the OPEN map module (its auras in the top right) */
        fun fullMap(): Boolean = shown?.let { it.full == HomeModule.MAP && !it.takeover } == true

        // ---- THE OPEN MAP'S AURAS (UI Art's layout, mirroring the game's BuffFrame): right-aligned from w - FULL_RIGHT, the
        // buffs' rows (8 a row, growing leftward, the first at the right), the debuffs' row under them; the cursor is the kit's
        // slot box; Right from the map enters on the buff row's LEFTMOST icon (nearest the map)
        private var fullCur: Pair<Boolean, Int>? = null              // (debuff, index in its list: 0 = rightmost)
        val fullCursorOn get() = fullCur != null
        private val buffRects = ArrayList<RectF>(); private val debuffRects = ArrayList<RectF>()

        /** the open map closed: the cursor leaves the auras */
        fun leaveFull() { fullCur = null; close(); if (lent(mapView)) mapView.hostZoomHidden = false }

        /** D-pad Right from the map: onto the buffs' leftmost icon on their first line (the debuffs' when there are no buffs) */
        fun enterFull() {
            val nb = minOf(auras.shownBuffs.size, FULL_PER); val nd = minOf(auras.shownDebuffs.size, FULL_PER)
            fullCur = when { nb > 0 -> false to nb - 1; nd > 0 -> true to nd - 1; else -> null }
            invalidate()
        }

        /** one press while the cursor is on an aura */
        fun fullKey(k: String) {
            val (deb, i) = fullCur ?: return
            val n = (if (deb) auras.shownDebuffs else auras.shownBuffs).size
            val other = (if (deb) auras.shownBuffs else auras.shownDebuffs).size
            when (k) {
                "PADDLEFT" -> fullCur = if (i + 1 < n && (i + 1) % FULL_PER != 0) deb to i + 1 else null      // past the leftmost: the map
                "PADDRIGHT" -> if (i % FULL_PER > 0) fullCur = deb to i - 1
                "PADDDOWN" -> if (!deb && i + FULL_PER < minOf(n, FULL_PER * ROW_LINES)) fullCur = false to i + FULL_PER
                    else if (!deb && other > 0) fullCur = true to minOf(i % FULL_PER, other - 1)
                "PADDUP" -> if (deb && other > 0) fullCur = false to minOf(i % FULL_PER + FULL_PER * ((minOf(other, FULL_PER * ROW_LINES) - 1) / FULL_PER), other - 1)
                    else if (!deb && i >= FULL_PER) fullCur = false to i - FULL_PER
                "PAD1" -> { show(deb, i); padHeld = true }                   // A: the existing list, this aura chosen, the controller's look
                "PAD2" -> fullCur = null                                       // B: back to the map
            }
            invalidate()
        }

        private fun drawFull(c: Canvas, w: Float, h: Float, now: Long) {
            val nb = auras.shownBuffs.size; val nd = auras.shownDebuffs.size
            buffRects.clear(); debuffRects.clear()
            val right = w - FULL_RIGHT
            if (nb > 0) auraRow(c, auras.shownBuffs, false, FULL_TOP, right, buffBox, now, fromRight = true, rects = buffRects)
            var dTop = if (nb > 0) buffBox.bottom + 8f else FULL_TOP
            // the game's durability warning between the buffs and the debuffs (the user's pick): the debuffs make room for it
            warnBox.setEmpty()
            if (open == null && Doll.warning(c, RectF(right - WARN_H, dTop, right, dTop + WARN_H), durabilityWarning, alignRight = true)) {
                warnBox.set(right - WARN_H, dTop, right, dTop + WARN_H)
                dTop = warnBox.bottom + 8f
            }
            if (nd > 0) auraRow(c, auras.shownDebuffs, true, dTop, right, debuffBox, now, fromRight = true, rects = debuffRects)
            fullCur?.let { (deb, i) ->
                val list = if (deb) debuffRects else buffRects
                if (i !in list.indices) fullCur = null else if (open == null) UiKit.slotFocus(c, list[i])
            }
            // UI Art: the map's + / - hide while the list is open (their edges stood out past the panel) - Map's hostZoomHidden
            if (lent(mapView) && mapView.hostZoomHidden != (open != null)) mapView.hostZoomHidden = open != null
            val which = open ?: run { keepOut(); return }
            list(c, which, w, h, now)
            keepOut()
        }

        /** the map's quest arrow keeps off the auras (Map: the open module honours rimKeepOut) */
        private fun keepOut() {
            if (lent(mapView)) mapView.rimKeepOut = listOf(buffBox, debuffBox, panel, warnBox).filter { !it.isEmpty }.map { RectF(it) }
        }

        /** where the durability warning was drawn (empty = not shown): the map's quest arrow keeps off it too */
        private val warnBox = RectF()

        /**
         * The game's durability figure on the SMALL map: at its left edge (the rows' left), centred in the gap between [above] (the buff
         * row's bottom) and [below] (the debuff row's top), about two buff icons tall; nothing while the game does not show it or the
         * gap is too small for it. It takes no touch.
         */
        private fun warning(c: Canvas, above: Float, below: Float) {
            warnBox.setEmpty()
            val gap = below - above - 2f * WARN_PAD
            if (gap < WARN_MIN) return
            val hgt = minOf(WARN_H, gap)
            val top = above + WARN_PAD + (gap - hgt) / 2f
            val box = RectF(ROW_LEFT, top, ROW_LEFT + hgt, top + hgt)
            if (Doll.warning(c, box, durabilityWarning, alignRight = false)) warnBox.set(box)
        }

        /** how far down (this pane's = the map's px) the quest card covers the small map NOW (it rides the fold / fade): 0 = none */
        private fun cardCover(): Float {
            if (!cardOverMap()) return 0f
            val q = now[Part.QUESTS] ?: return 0f
            val m = now[Part.MAP] ?: return 0f
            if (questPane.visibility != View.VISIBLE || questPane.alpha < 0.05f) return 0f
            return q.bottom - m.top + 6f
        }

        /** b128 ("the buffs need to be moved up to be flush with the quest"): the buff row's top in this pane (= the map's) px:
         *  6 px under the quest card while it shows over the map (where it is NOW, so the row rides the card's fold / fade), else
         *  under the zone band */
        private fun buffTop(): Float {
            if (!cardOverMap()) return BTN_TOP
            val q = now[Part.QUESTS] ?: return BTN_TOP
            val m = now[Part.MAP] ?: return BTN_TOP
            if (questPane.visibility != View.VISIBLE || questPane.alpha < 0.05f) return BTN_TOP
            return maxOf(BTN_TOP, q.bottom - m.top + 6f)
        }

        /** each icon of the two rows and its aura (a tap on the row chooses the one under the finger) */
        private val cells = ArrayList<Pair<RectF, Int>>()
        private val pIcon = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val pEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
        // UI Art (2026-09-30): dark enough that no map pin shows through it, a hint of the map at its edges
        private val pBand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 8, 6, 4) }

        /**
         * One row of auras from the map's left edge, at most [right] wide: the buffs' row hangs from [edge] (its top), the debuffs'
         * stands on it (its bottom). Two lines at most; what does not fit is counted in a last "+n" cell. [box] = the touch target.
         */
        private fun auraRow(c: Canvas, list: List<HomeAura>, debuff: Boolean, edge: Float, right: Float, box: RectF, now: Long,
                            fromRight: Boolean = false, rects: MutableList<RectF>? = null) {
            // b129: [fromRight] = the open map's rows, right-aligned at [right], 8 a line, the first aura at the right, every line
            // hanging from [edge] (the debuffs under the buffs, as the game's BuffFrame)
            val per = if (fromRight) FULL_PER else maxOf(1, ((right - ROW_LEFT + ICON_GAP) / (AURA_ICON + ICON_GAP)).toInt())
            fun xOf(col: Int) = if (fromRight) right - AURA_ICON - col * (AURA_ICON + ICON_GAP) else ROW_LEFT + col * (AURA_ICON + ICON_GAP)
            val hangs = fromRight || !debuff
            // b129 (UI Art): in the open map the times sit 8 px lower, so the cursor's box clears them (the plate grows with them)
            val timeH = if (fromRight) TIME_H + 8f else TIME_H
            val fit = per * ROW_LINES
            val shown = if (list.size > fit) list.take(fit - 1) else list
            val more = list.size - shown.size
            val n = shown.size + if (more > 0) 1 else 0
            val lines = (n + per - 1) / per
            val lineH = AURA_ICON + timeH + LINE_GAP
            val drawnH = lines * lineH - LINE_GAP
            val top = if (!hangs) edge - drawnH else edge
            val greyed = auras.greyed
            val alpha = if (open == debuff) 255 else REST_ALPHA
            pIcon.alpha = alpha
            // the debuffs' first line is the lowest, as the buffs' first is the highest
            fun lineTop(line: Int) = if (!hangs) top + (lines - 1 - line) * lineH else top + line * lineH
            // UI Art's band behind each line (the times vanish on a bright map without it), as long as that line's icons
            for (line in 0 until lines) {
                val cnt = minOf(per, n - line * per)
                val y = lineTop(line)
                // a small module well (the harmony pass: "buffs (now in a small well)")
                val l0 = minOf(xOf(0), xOf(cnt - 1)); val r0 = maxOf(xOf(0), xOf(cnt - 1)) + AURA_ICON
                UiKit.module(c, RectF(l0 - 6f, y - 6f, r0 + 6f, y + AURA_ICON + timeH + 2f))
            }
            for (k in 0 until n) {
                val line = k / per; val col = k % per
                val y = lineTop(line)
                val r = RectF(xOf(col), y, xOf(col) + AURA_ICON, y + AURA_ICON)
                rects?.add(RectF(r))
                if (k >= shown.size) {
                    val pm = UiKit.numberPaint(26f, Theme.text).apply { textAlign = Paint.Align.CENTER; this.alpha = alpha }
                    c.drawText("+$more", r.centerX(), r.centerY() - (pm.ascent() + pm.descent()) / 2f, pm)
                    continue
                }
                val a = shown[k]
                cells.add(RectF(r.left - ICON_GAP / 2f, r.top, r.right + ICON_GAP / 2f, r.bottom + timeH) to a.id)
                if (greyed) c.saveLayer(RectF(r.left - 4f, r.top - 4f, r.right + 4f, r.bottom + timeH), pGreyRow)
                val icon = Icons.get(if (a.hidden) Icons.QUESTION_MARK else a.icon)
                if (icon != null) c.drawBitmap(icon, null, r, pIcon)
                else { pEdge.color = Theme.bronzeDim; c.drawRect(r, pEdge) }
                // the game's frames: a debuff in its dispel type's colour, a weapon enchant in the temporary enchant's purple
                val frame = when { debuff -> UiKit.debuffColour(a.dispel); a.enchant -> ENCHANT_PURPLE; else -> 0 }
                if (frame != 0) { pEdge.color = frame; pEdge.alpha = alpha; c.drawRect(r, pEdge) }
                if (a.stacks > 1) {
                    val ps = UiKit.numberPaint(22f, Color.WHITE).apply { textAlign = Paint.Align.RIGHT; this.alpha = alpha }
                    c.drawText(a.stacks.toString(), r.right - 3f, r.bottom - 4f, ps)
                }
                // the time left under the icon as the game's buff frame writes it: yellow, white in the last minute
                auras.leftAt(a, now)?.toInt()?.let { left ->
                    val pt = UiKit.numberPaint(19f, if (left < 60) Color.WHITE else Theme.gold).apply { textAlign = Paint.Align.CENTER; this.alpha = alpha }
                    c.drawText(shortTime(left), r.centerX(), r.bottom + timeH - 5f, pt)
                }
                if (greyed) c.restore()
            }
            val bottom = top + drawnH
            if (fromRight) box.set(xOf(minOf(n, per) - 1) - 4f, top - 4f, right + 4f, bottom + 4f)
            else box.set(0f, top - 4f, ROW_LEFT + minOf(n, per) * (AURA_ICON + ICON_GAP), bottom + 4f)
            // a finger's height at least, growing away from the map's edge
            if (box.height() < AURA_BTN) { if (debuff) box.top = box.bottom - AURA_BTN else box.bottom = box.top + AURA_BTN }
            if (box.width() < AURA_BTN) { if (fromRight) box.left = box.right - AURA_BTN else box.right = AURA_BTN }
            if (shown.any { it.left != null }) postInvalidateDelayed(1000L)     // the times count down
        }

        /** the list's scroll that brings the chosen aura into view */
        private fun hitScroll(debuff: Boolean, id: Int?): Float {
            val i = (if (debuff) auras.shownDebuffs else auras.shownBuffs).indexOfFirst { it.id == id }
            return if (i <= 0) 0f else (i - 1) * ROW_H
        }

        /** the game's short time: "1 h", "29 m", "45 s" */
        private fun shortTime(s: Int): String = when {
            s >= 3600 -> "${(s + 1800) / 3600} h"
            s >= 60 -> "${(s + 30) / 60} m"
            else -> "$s s"
        }

        /**
         * THE SMALL MAP'S ZOOM BUTTONS at its bottom right, + over - (the Map room's own tracker buttons): they fade like the
         * Map room's ("make sure it fades like normal", user 09-29), stay tappable while faint, and a press brings them back.
         */
        private fun zoom(c: Canvas, w: Float, h: Float, now: Long) {
            zoomOut.set(w - 4f - ZOOM_BOX, h - 4f - ZOOM_BOX, w - 4f, h - 4f)
            zoomIn.set(zoomOut.left, zoomOut.top - ZOOM_BOX, zoomOut.right, zoomOut.top)
            val since = now - zoomTouchAt
            val a = if (since < ZOOM_HOLD_MS) 1f
                else 1f - (1f - ZOOM_MIN) * ((since - ZOOM_HOLD_MS) / ZOOM_FADE_MS).coerceIn(0f, 1f)
            val steps = Settings.ZOOM_STEPS_YD
            val yd = mapView.minimapYdNow()      // the width in use: outdoors or indoors (Settings' two rows, 2026-10-02)
            for ((box, dir) in listOf(zoomIn to -1, zoomOut to +1)) {
                val can = if (dir < 0) yd > steps.first() else yd < steps.last()
                val alpha = (255 * a * (if (can) 1f else 0.4f)).toInt()
                val what = if (dir < 0) "plus" else "minus"
                if (!UiKit.trackerButton(c, what, box.centerX(), box.centerY(), ZOOM_DRAWN, down = zoomDown == dir, alpha = alpha))
                    UiKit.square(c, what, box.centerX(), box.centerY(), ZOOM_DRAWN,
                        if (!can) UiKit.Button.DISABLED else if (zoomDown == dir) UiKit.Button.DOWN else UiKit.Button.UP)
            }
            if (since < ZOOM_HOLD_MS + ZOOM_FADE_MS.toLong()) postInvalidateOnAnimation()      // the fade runs, then stops
        }

        private fun list(c: Canvas, debuff: Boolean, w: Float, h: Float, now: Long) {
            // the list opens beside its row, never over it: under the buffs, over the debuffs (the row closes it again)
            if (fullMap()) {
                // b129: in the open map the list hangs under the auras in the right column, the map left of it in view
                val under = maxOf(buffBox.bottom, debuffBox.bottom, FULL_TOP) + 10f
                panel.set(w - FULL_LIST_W, under, w - 16f, h - 16f)
            } else
            panel.set(4f, if (debuff) BTN_TOP else buffBox.bottom + 6f, w - 4f, if (debuff) debuffBox.top - 6f else h - 4f)
            if (panel.height() < MIN_PANEL) { if (debuff) panel.top = panel.bottom - MIN_PANEL else panel.bottom = panel.top + MIN_PANEL }
            val inner = UiKit.menu(c, panel, solid = fullMap())        // b129 (UI Art): nothing of the map shows through in the open module
            val shown = if (debuff) auras.shownDebuffs else auras.shownBuffs
            val greyed = auras.greyed
            val pt = UiKit.titlePaint(30f)
            c.drawText(if (debuff) "Debuffs" else "Buffs", inner.left + 12f, inner.top + 36f, pt)
            if (greyed) {
                val pn = UiKit.bodyPaint(21f, Theme.dim).apply { textAlign = Paint.Align.RIGHT }
                c.drawText("Before the fight", inner.right - 12f, inner.top + 34f, pn)
            }
            val selected = shown.firstOrNull { it.id == sel }
            if (selected == null) sel = null
            val detailH = if (selected != null) DETAIL_H else 0f
            rowsTop = inner.top + 50f; rowsBottom = inner.bottom - detailH
            scroll = scroll.coerceIn(0f, maxOf(0f, shown.size * ROW_H - (rowsBottom - rowsTop)))
            c.save(); c.clipRect(inner.left, rowsTop, inner.right, rowsBottom)
            var y = rowsTop - scroll
            for (a in shown) {
                val r = RectF(inner.left, y, inner.right, y + ROW_H)
                if (r.bottom > rowsTop && r.top < rowsBottom) {
                    // UI Art's aura row; the shared icon pack (Icons, bags session), a hidden aura with the game's own "?" icon;
                    // the list from before the fight drawn grey
                    val icon = Icons.get(if (a.hidden) Icons.QUESTION_MARK else a.icon)
                    val name = when { a.enchant -> "Weapon enchant"; a.name == null -> "?"; else -> a.name }
                    val left = auras.leftAt(a, now)?.toInt() ?: -1
                    // the controller's cursor: the kit's golden box UNDER the row and UI Art's up / down arrows centred on its top and
                    // bottom edges (art-kit 490f9ca, `around`; none up on the first aura, none down on the last)
                    val pointed = padHeld && a.id == cur
                    // UI Art (b129 / b131 frame checks): the box sits 12 px inside the list's clip on BOTH sides, so all four edges show
                    val box = RectF(r.left + 12f, r.top, r.right - 12f, r.bottom)
                    if (pointed) UiKit.focus(c, box)
                    if (greyed) c.saveLayer(r, pGreyRow)
                    UiKit.auraRow(c, r, icon, name, a.stacks, left, debuff, a.dispel, a.id == sel, cursor = pointed)
                    if (greyed) c.restore()
                    if (pointed) { val k = shown.indexOf(a); UiKit.moveArrowsUpDown(c, box, up = k > 0, down = k < shown.size - 1, size = 34f, around = true) }
                }
                rows.add(RectF(r.left, maxOf(r.top, rowsTop), r.right, minOf(r.bottom, rowsBottom)) to a.id)
                y += ROW_H
            }
            c.restore()
            if (selected != null) detail(c, selected, debuff, greyed, RectF(inner.left, rowsBottom, inner.right, inner.bottom), now)
            val n = noteText
            if (n != null && System.currentTimeMillis() < noteUntil) {
                val p = UiKit.bodyPaint(22f, Theme.warn)
                c.drawText(TextUtils.ellipsize(n, p, inner.width() - 24f, TextUtils.TruncateAt.END).toString(),
                    inner.left + 12f, rowsBottom - 10f, p)
                postInvalidateDelayed(NOTE_MS)
            } else noteText = null
        }

        /** the selected aura: its tooltip lines, and ONE action (Remove) for a buff */
        private fun detail(c: Canvas, a: HomeAura, debuff: Boolean, greyed: Boolean, r: RectF, now: Long) {
            UiKit.divider(c, r.left + 8f, r.top + 2f, r.right - 8f, r.top + 2f)
            val removable = !debuff && !a.enchant
            val textRight = if (removable) r.right - REMOVE_W - 20f else r.right - 12f
            val text = when {
                a.tip.isNotEmpty() -> a.tip.joinToString("\n")
                greyed -> "Hidden by the game in combat"
                debuff -> "Debuffs cannot be removed"
                else -> ""
            }
            if (text.isNotEmpty()) {
                val lay = StaticLayout.Builder.obtain(text, 0, text.length, tp, (textRight - r.left - 24f).toInt().coerceAtLeast(10))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(0f, 1.08f)
                    .setMaxLines(((r.height() - 20f) / 27f).toInt().coerceAtLeast(1)).setEllipsize(TextUtils.TruncateAt.END).build()
                c.save(); c.translate(r.left + 12f, r.top + 12f); lay.draw(c); c.restore()
            }
            if (removable) {
                removeBox.set(r.right - REMOVE_W - 6f, r.bottom - REMOVE_H - 6f, r.right - 6f, r.bottom - 6f)
                val waiting = auras.pending(now) == a.id
                val can = !greyed && a.slot != null && auras.pending(now) == null
                // A = Remove while the controller holds the list: the A INSIDE the button, as the kit's Accept / Buy / Train carry
                // their glyph (UI Art), and NO gold box (the user: "remover the highlight on the remove button and keep the a there")
                val label = if (waiting) "Removing" else "Remove"
                val state = if (can) UiKit.Button.UP else UiKit.Button.DISABLED
                if (padHeld) UiKit.button(c, removeBox, label, aGlyph(), state) else UiKit.button(c, removeBox, label, state)
            }
        }

        private fun remove(id: Int) {
            val now = System.currentTimeMillis()
            when (val t = auras.remove(id, GameInput.live(now), now)) {
                is AuraTap.Send -> {
                    // ONE touch: the no-action pick (it arms the cancel cell with this buff); the one action, the cancel tap,
                    // follows on the pick's ok ([auraAck])
                    if (!GameInput.tap(context, auras.pad, "aurapick", t.slot)) { auras.sendFailed(); note("The game has not published its tap pad") }
                }
                is AuraTap.Refused -> note(t.why)
            }
            invalidate()
        }

        private var downX = 0f; private var downY = 0f; private var dragging = false; private var startScroll = 0f

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    zoomDown = when { zoomIn.contains(e.x, e.y) -> -1; zoomOut.contains(e.x, e.y) -> +1; else -> 0 }
                    val mine = open != null || zoomDown != 0 || buffBox.contains(e.x, e.y) || debuffBox.contains(e.x, e.y)
                    if (!mine) return false                                // the map's own touch
                    downX = e.x; downY = e.y; dragging = false; startScroll = scroll
                    if (zoomDown != 0) { zoomTouchAt = System.currentTimeMillis(); invalidate() }
                }
                MotionEvent.ACTION_MOVE -> if (open != null && panel.contains(downX, downY)) {
                    if (!dragging && abs(e.y - downY) > SLOP) dragging = true
                    if (dragging) {
                        val list = if (open == true) auras.shownDebuffs else auras.shownBuffs
                        val max = maxOf(0f, list.size * ROW_H - (rowsBottom - rowsTop))
                        scroll = (startScroll + downY - e.y).coerceIn(0f, max); invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val z = zoomDown; zoomDown = 0
                    if (z != 0) {
                        // on the release, only while still on the button: one step of Settings' "Minimap zoom" (map's call)
                        if ((if (z < 0) zoomIn else zoomOut).contains(e.x, e.y)) mapView.stepMinimapZoom(z)
                        zoomTouchAt = System.currentTimeMillis(); invalidate()
                    } else if (!dragging) tap(e.x, e.y)
                }
                MotionEvent.ACTION_CANCEL -> { zoomDown = 0; invalidate() }
            }
            return true
        }

        private fun tap(x: Float, y: Float) {
            when {
                // a row: open its list with the aura under the finger chosen; the open list's own row (not on an icon) closes it
                buffBox.contains(x, y) || debuffBox.contains(x, y) -> {
                    val debuff = debuffBox.contains(x, y)
                    val hit = cells.firstOrNull { it.first.contains(x, y) }?.second
                    if (open == debuff && (hit == null || hit == sel)) close()
                    else { sel = hit; open = debuff; resetCursor(); scroll = hitScroll(debuff, hit); invalidate() }
                }
                removeBox.contains(x, y) -> sel?.let { remove(it) }
                // a finger's choice moves the controller's cursor with it (the A goes to Remove, as A would)
                panel.contains(x, y) -> rows.firstOrNull { it.first.contains(x, y) }?.let { sel = it.second; cur = it.second; invalidate() }
                else -> close()                                             // outside the open list: close it
            }
        }

        /** the list from before the fight: grey and a little faint */
        private val pGreyRow = Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
            alpha = 170
        }
    }

    // ================================================================ chat formatting
    // A copy of ChatView's rules (its Line, colours and draw() are private). Known duplicate: to be
    // asked of the chat session as one shared formatter.

    private fun colourFor(l: HomeChatLine): Int =
        if (l.ch == "channel") NUMBERED[l.idx] ?: Color.parseColor("#C5B9A0")
        else CHANNEL_COLOUR[l.ch] ?: Theme.textDim

    private fun format(l: HomeChatLine): CharSequence {
        val sb = SpannableStringBuilder()
        val body = colourFor(l)
        fun put(s: String, colour: Int) {
            val a = sb.length; sb.append(s); sb.setSpan(ForegroundColorSpan(colour), a, sb.length, 0)
        }
        val name = if (l.cls.isNotEmpty()) Theme.classColourOf(l.cls) else body
        if (l.ch == "channel") {
            val tag = l.chan.substringAfter(". ", l.chan).substringBefore(" - ")
            put("[${if (tag.isBlank()) "ch${l.idx}" else tag}] ", body)
        }
        val named = l.who.isNotEmpty() && l.style != "plain"
        when {
            !named -> {}
            l.out -> { put("To ", body); put(l.who, name); put(": ", body) }
            l.ch == "whisper" -> { put(l.who, name); put(" whispers: ", body) }
            l.style == "emote" -> { put(l.who, name); put(" ", body) }
            else -> { put(l.who, name); put(": ", body) }
        }
        val textAt = sb.length
        put(l.text, body)
        // b126 (the user: "the different link colors should also show in the home module"): each [link] in its game colour, as
        // the Chat room draws it (Chat.lua chat16's `lc`)
        var from = 0
        for ((n, hex) in l.lc) {
            val at = l.text.indexOf("[$n]", from)
            if (at < 0) continue
            val c = runCatching { android.graphics.Color.parseColor("#$hex") }.getOrNull() ?: continue
            sb.setSpan(ForegroundColorSpan(c), textAt + at, textAt + at + n.length + 2, 0)
            from = at + n.length + 2
        }
        return sb
    }

    private fun tierColour(t: QuestTier) = when (t) {
        QuestTier.TRIVIAL -> Theme.questGrey
        QuestTier.STANDARD -> Theme.questGreen
        QuestTier.DIFFICULT -> Theme.questYellow
        QuestTier.VERY_DIFFICULT -> Theme.questOrange
        QuestTier.IMPOSSIBLE -> Theme.questRed
        QuestTier.UNKNOWN -> Theme.gold
    }

    companion object {
        /** one pick + act pair at a time: the add-on's pick serves one action (CompanionHost's restore uses the same gap) */
        const val WATCH_GAP_MS = 700L
        /** p-grow-glitch: B does not shrink the big controller this soon after it grew */
        private const val GROW_B_GRACE_MS = 2_000L
        /** f-class-radial: a press this long on a pet spell's place toggles its autocast */
        private const val CLASS_LONG_MS = 450L
        /** 1.0.8-test5: the left column's raise while the wheel shows (it slides out from under the chat) */
        private const val CLASS_COVER_Z = 4f
        /** f-radial-middle: the item / spell wheel's slot is never wider than this share of Home (nor than the chat column) */
        private const val WHEEL_SLOT_MAX = 0.5f
        /** the focused quest's card over the map (b121): above the map's 0, under the wheel's lifted left column */
        private const val CARD_Z = 2f
        /** the focused quest over the minimap (Settings > Home, HomeSettings.questOverMap; was the constant QUEST_ABOVE_MAP) */
        fun cardOverMap(): Boolean = HomeSettings.questOverMap
        /** the Track Pad & Keyboard's own controller guest (Quests.lua's "tool": ZL / ZR kept from the game while it holds) */
        const val TOOL_GUEST = "tool"
        /** logcat tag for Home's taps and refusals (`adb logcat -s WCHOME`) */
        const val TAG = "WCHOME"
        /** f-trackpad-keyboard: the tool's lift, above the quest card (2) and the class wheel's cover */
        private const val TYPE_Z = 50f
        /** B's give-back is retried once after this when the faces are still armed (the bar cursor's hold retry, the same reason) */
        const val GIVEBACK_RETRY_MS = 450L
        const val NOTE_MS = 2500L
        /** the buff / debuff rows' touch box: a 48 dp finger at least (112 px on the Thor) */
        const val AURA_BTN = 112f
        /** the rows: icons 56 px with the time under them (24 px), 8 px apart, from 8 px in, two lines at most */
        const val AURA_ICON = 56f
        /** the durability figure on Home: about two buff icons tall (the user's pick), its smallest, its room above and below */
        const val WARN_H = 2f * AURA_ICON
        const val WARN_MIN = 48f
        const val WARN_PAD = 8f
        const val TIME_H = 24f
        const val ICON_GAP = 8f
        /** between two lines of a row, so their bands just meet */
        const val LINE_GAP = 8f
        const val ROW_LEFT = 8f
        const val ROW_LINES = 2
        /** the rows' icons at rest: full, so nothing of the map shows through an icon (UI Art); the grey in combat stays */
        const val REST_ALPHA = 255
        /** the game's temporary-enchant frame (a weapon oil, poison or stone) */
        val ENCHANT_PURPLE = Color.rgb(140, 60, 220)
        /** the open list is never shorter than this, even beside two full rows */
        const val MIN_PANEL = 330f
        /** the small map's zone bar: from 4 px to 68 px down the map (64 px tall, so its name is 32 px) */
        const val ZONE_BAND_TOP = 8f
        const val ZONE_BAND_BOTTOM = 72f
        /** the page's fade at a move's start (out) and end (in) (HARMONY_SPEC section 3) */
        const val FADE_MS = 120f
        /** the buff button, below the small map's zone bar */
        const val BTN_TOP = ZONE_BAND_BOTTOM + 8f
        /** b129, UI Art: the open map's auras - right-aligned at w - FULL_RIGHT (left of the filter corner and its Start glyph),
         *  from FULL_TOP, 8 a line; the list under them FULL_LIST_W wide */
        const val FULL_RIGHT = 150f
        const val FULL_TOP = 16f
        const val FULL_PER = 8
        const val FULL_LIST_W = 600f
        /** UI Art's aura rows: 88 px or taller */
        const val ROW_H = 88f
        const val DETAIL_H = 190f
        const val REMOVE_W = 260f       // room for the A glyph and the word (UI Art)
        /** the buff list's guest of the quests addon's facility (q9: qact 18 / 19), and the room for the A glyph on a pointed row */
        const val GUEST_ID = "home"
        const val A_W = 60f
        const val REMOVE_H = 112f
        /**
         * The bars' band: the small diamonds at rest, and the grown controller a bit bigger than the spec's 354 (user 16:37),
         * brought back from 410 (1.16x) to 380 (about 1.07x): "when in combat the buttons are a little too large now" (17:44)
         */
        const val REST_DIAMONDS_BARS = 200f
        /** the small map's zoom buttons (bottom right): a 48 dp touch box each, drawn 104 px like the Map room's */
        const val ZOOM_BOX = 112f
        const val ZOOM_DRAWN = 104f
        /** they fade as the Map room's do: full for 1.8 s after a touch, then down to 0.16 in 0.45 s */
        const val ZOOM_HOLD_MS = 1800L
        const val ZOOM_FADE_MS = 450f
        const val ZOOM_MIN = 0.16f
        /** the clock plate's top below the band's top */
        const val CLOCK_TOP = 30f
        const val BATTERY_MS = 30_000L
        /** the red reminder's strength behind the controller (UI Art: 0.55; user 17:37: "a bit more subtle") */
        const val RED_STRENGTH = 0.55f
        const val COMBAT_BARS = 380f
        private const val HIDE_RULING = "ruling.hide.2026-09-28"
        const val SLOP = 16f

        private val CHANNEL_COLOUR: Map<String, Int> = mapOf(
            "say" to Color.parseColor("#FFFFFF"), "yell" to Color.parseColor("#FF4040"),
            "party" to Color.parseColor("#AAABFE"), "raid" to Color.parseColor("#FF7F00"),
            "guild" to Color.parseColor("#40FF40"), "officer" to Color.parseColor("#40C040"),
            "whisper" to Color.parseColor("#FF80FF"), "emote" to Color.parseColor("#FF8040"),
            "npc" to Color.parseColor("#FFFF9F"), "loot" to Color.parseColor("#00AA00"),   // b126: the game's LOOT colour (0, 0.667, 0), not the uncommon green (UI Art)
            "money" to Color.parseColor("#FFFF00"), "currency" to Color.parseColor("#00AA00"),
            "system" to Color.parseColor("#FFFF00"), "info" to Color.parseColor("#FFFF00"),
            "error" to Color.parseColor("#FF5050"),
        )
        private val NUMBERED: Map<Int, Int> = mapOf(
            1 to Color.parseColor("#FFC0C0"), 2 to Color.parseColor("#F5A97A"),
            3 to Color.parseColor("#B6D7A8"), 4 to Color.parseColor("#9FC5E8"),
            5 to Color.parseColor("#D5A6BD"),
        )
    }
}

/** f-class-radial rework: Home's wheel slot, pure (the tests check it fits): a square centred between the columns under the bars, at
 *  most [maxSide] (half the width, and never wider than the chat column it hides behind); [push] = how far each column slides out */
internal object ClassSlot {
    data class Slot(val left: Float, val top: Float, val side: Float)
    data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float)
    /** the column's area: the union of its parts' boxes [parts] (l, t, r, b), under the bars; none shown = that half of the page */
    fun column(parts: List<Area>, barsBottom: Float, h: Float, gap: Float, left: Boolean, w: Float): Area =
        if (parts.isEmpty()) (if (left) Area(gap, barsBottom + gap, w / 2f - gap / 2f, h - gap) else Area(w / 2f + gap / 2f, barsBottom + gap, w - gap, h - gap))
        else Area(parts.minOf { it.left }, maxOf(barsBottom + gap, parts.minOf { it.top }), parts.maxOf { it.right }, parts.maxOf { it.bottom })
    /** the wheel: the largest square in the column's area, centred in it */
    fun inColumn(a: Area): Slot {
        val side = minOf(a.right - a.left, a.bottom - a.top).coerceAtLeast(1f)
        return Slot((a.left + a.right - side) / 2f, (a.top + a.bottom - side) / 2f, side)
    }
    /**
     * f-radial-middle: the item / spell wheel's square between the columns, under the bars ([barsBottom]), at most [maxSide]; [push] =
     * how far each column parts (half the wheel and a gap)
     */
    data class Middle(val left: Float, val top: Float, val side: Float, val push: Float)
    fun middle(w: Float, h: Float, barsBottom: Float, gap: Float, maxSide: Float): Middle {
        val avail = (h - gap) - (barsBottom + gap)
        val side = minOf(avail, maxSide).coerceAtLeast(1f)
        val top = barsBottom + gap + (avail - side) / 2f
        return Middle((w - side) / 2f, top, side, side / 2f + gap)
    }
    /** how far a part of the column slides to be wholly off its own edge ([w] the page's width) */
    fun away(left: Boolean, partLeft: Float, partRight: Float, w: Float, gap: Float): Float =
        if (left) -(partRight + gap) else (w - partLeft + gap)
}

/** p-card-invisible: the quest card's state in words, for the WCHOME "quest card:" line (pure, so the JVM tests read it) */
internal object HomeCardState {
    /** the quest card's state in words (the WCHOME "quest card:" line; pure, for the tests): "shown", or why it is not */
    fun why(q: Int, card: Boolean, auraOpen: Boolean, visible: Boolean, flying: Boolean, alpha: Float, height: Int,
            underMap: Boolean = false, onScreen: Boolean = true): String = when {
        q == 0 -> "no focused quest"
        !card -> "focus $q has no quest in the log"
        auraOpen -> "buff / debuff list open"
        !visible -> "pane not laid out"
        flying -> "flying (map open / close)"
        alpha < 0.05f -> "faded"
        height <= 0 -> "zero height"
        underMap -> "under the map (depth)"
        !onScreen -> "clipped off screen"
        else -> "shown"
    }
}
