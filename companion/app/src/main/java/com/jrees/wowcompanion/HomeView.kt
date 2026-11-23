package com.jrees.wowcompanion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
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
    private val chat = HomeChatTail(counts = { ChatFilter.shows(it.ch, it.chan) })     // the checklist's hidden lines push nothing out
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

    private enum class Part { BARS, QUESTS, CHAT, PARTY, MAP, AURAS, STRIP, CHAT_FULL, CAST2 }

    private val questPane = QuestPane(context)
    private val chatPane = ChatPane(context)
    private val mapSlot = FrameLayout(context)
    private val auraPane = AuraPane(context)
    private val barsSlot = FrameLayout(context)
    private val chatSlot = FrameLayout(context)
    /** PARTY MODE's module (party_design.md C, m129): one column of plates beside the Select tab, over the left column */
    private val party = PartyModel.live
    private val partyView = PartyModuleView(context, party)
    /** the player menu for a party member (PARTY_BUILD.md 4.6), over the left column; the host drives it */
    val partyMenu = PartyMenuView(context)
    /** the target radial's More: the target's whole menu as a list, in the party menu's place (TargetRadialView drives it) */
    val targetMenu = PartyMenuView(context, emptySet())
    /**
     * THE CHAT'S GLIDE (b-chat-slide; the user's pick: chat going full screen should move like the map's morph). The full chat is laid
     * out ONCE at its full size and its box glides between the chat pane's place and the frame, as the map's does: a rounded window
     * over it in [chatSlot]'s own px, empty = no glide (the whole slot shows). No re-layout per frame (the Controller session: a
     * requestLayout every frame was what made the combat switch lag the game).
     */
    private val chatGlideBox = RectF()
    /** THE TAP-TO-CAST BAR (GitHub #43 cb3, [CastStripModel]): 8 slots (cb6), under the chat (Left) or under the minimap (Right) a
     *  strip with 4 in view that scrolls freely (cb5: no pages; edge arrows where slots are hidden), (cb8: Right resting on 5-8), ONE bar of all 8 across under both columns (Both), by
     *  Settings > Bottom screen > Tap-to-cast bar (Off by default); a tap = ONE pad tap on the add-on's "castbar" row (the scroll never
     *  moves the bar). cb7: Hide in combat slides it down behind Home's bottom bar and gives its room back to the column(s) */
    private val castStrip = CastStripView(context) { i -> castStripTap(i) }
    /** MOVABLE HOME P2: Split's second half (slots 5-8 under the right column; the first half is [castStrip] with slots 1-4). The same
     *  castbar cells 1-8: no new cell, nothing new in the add-on */
    private val castStrip2 = CastStripView(context) { i -> castStripTap(i) }.apply { splitHalf = 2 }
    private val strips get() = listOf(castStrip, castStrip2)
    // CLOSE is added last, so it is drawn above the module it closes
    private val views: Map<Part, View> = linkedMapOf(
        Part.BARS to barsSlot, Part.QUESTS to questPane, Part.CHAT to chatPane, Part.PARTY to partyView, Part.MAP to mapSlot,
        Part.AURAS to auraPane, Part.STRIP to castStrip, Part.CAST2 to castStrip2, Part.CHAT_FULL to chatSlot,
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
        RadialMeta.init(context)
        flow.setRest(HomeSettings.rest)
        HomeSettings.onChange { post { castStripRelay(); castStripFight() } }  // GitHub #43: the tap-to-cast bar's own choices
        // movable Home fix round 1: the active record's owner changed (another character, the late key): edit mode checks its own
        HomeLayoutStore.onOwner { editUi.post { editOwnerCheck("the character changed") } }
        partyView.boxShown = { !editLive() }                              // fix round 1: never a second gold box beside edit mode's
        HomeSettings.onChange { post {
            if (flow.setRest(restWanted())) changed()
            // MOVABLE HOME: a new layout record (a reset, a facade setter, another character): the page glides there on the one
            // motion (240 / 200 ms, Motion.curve); the D-pad's word follows in the add-on ([HomeLayoutStore], ToolbarMirror)
            else if (laidLayout != null && laidLayout != layoutNow() && shown?.let { it.full == null || it.takeover } == true) shown?.let { apply(it, animate = isShown) }
            // the focused quest moved (Settings > Home): the parts go to their new places
            else if (laidOverMap != cardOverMap()) shown?.let { apply(it, animate = isShown) }
            // r-combat-anim-off: Grow / Same size changed during a fight: the band goes to its new size
            else if (lent(castView) && castView.swingOn != HomeSettings.swingTimer)
                castView.swingOn = HomeSettings.swingTimer                                     // p-swing-timer: switched in a fight
            else if (laidGrow != HomeSettings.combatGrow) { laidGrow = HomeSettings.combatGrow; shown?.let { if (it.takeover) apply(it, animate = isShown) } }
            invalidate()                                                     // r20: a clock extra switched on / off
        } }
        ThemeLive.bg(this, Tok.INK)
        setWillNotDraw(false)
        for ((_, v) in views) { addView(v); v.visibility = View.GONE }
        addView(partyMenu); partyMenu.visibility = View.GONE; partyMenu.translationZ = PARTY_MENU_Z
        addView(targetMenu); targetMenu.visibility = View.GONE; targetMenu.translationZ = PARTY_MENU_Z
        mapView.onCompactTap = { touchOpen(HomeModule.MAP) }      // combat-touch: the first touch opens it, in a fight too
        partyView.onCellTap = { i -> onPartyCell?.invoke(i) }
        partyView.onCellLong = { i -> onPartyLong?.invoke(i) }
        partyView.onTag = { on -> onPartyTag?.invoke(on) }
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

    /** f-settings-previews: where the map module lies now (Home's px), for the stage's minimap pictures */
    internal fun previewMapRect(): RectF? = now[Part.MAP]?.let { RectF(it) }

    /** the cast bar question's picture (PreviewStage.castAsk): the boxes as laid now, by part name */
    internal fun previewBoxes(): Map<String, RectF> = now.entries.associate { it.key.name to RectF(it.value) }
    /** ... and the page's one motion at [k] between two of them ([a] at 0, [b] at 1): every box on one progress, the bar sliding */
    internal fun previewBetween(a: Map<String, RectF>, b: Map<String, RectF>, k: Float) {
        for (p in Part.values()) {
            val f = a[p.name] ?: continue; val t = b[p.name] ?: f
            now[p] = RectF(f.left + (t.left - f.left) * k, f.top + (t.top - f.top) * k, f.right + (t.right - f.right) * k, f.bottom + (t.bottom - f.bottom) * k)
        }
        for (st in strips) st.sliding = k > 0f && k < 1f
        placeFrame()
    }

    /** f-settings-previews: the offscreen stage's own Home at [look] (never the live one): its rest, then its places, no motion */
    internal fun previewSettle(look: Companion.Look) {
        flow.setRest(look.rest)
        apply(flow.screen, false)
    }

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
        // fix round 3: the session's draft is laid AT ONCE (the Chat room's closing glide reads chatPaneBox in this same call), the rest
        // of the resume (the guest, the chrome) on the next loop as before
        if (edit?.parked == true && editAway) {
            editBackDue = !fightShown() && editOwner?.let { HomeLayoutStore.editSaveFor(it) } != HomeLayoutStore.EditSave.NONE
            if (editBackDue) editRestSync()
            editUi.post { editAwayBack() }
        }
        lend(mapView, mapSlot)
        mapView.sticksClaim = { mapSticksLive() }                              // #584: the sticks only while the module holds
        mapView.onFieldTouch = { mapFieldTouched() }                          // 2026-11-17: a touch on the open map = its map controls
        // an untracked quest's disc (the user, 2026-11-17: "when clicking on one, it does not open to the quest in the left menu"): the
        // map shows it in the list, so the module goes to the Quests page (from the controls or the buffs; the list full again)
        mapView.onListWanted = { if (mapWanted) { setMapPage(HomeMapPages.Page.QUESTS); syncMapB() } }
        lend(castView, barsSlot)
        // B through the Cast view's OWN reader while Home holds it (see [watchB]): one getevent, never two
        castView.onPadButtons = { held -> onPadHeld(held) }
        castView.onClassRadial = { open -> classLayerShow(open) }             // f-class-radial: the mirror over Home
        castView.onClassStick = { if (classOpen) classLayer.invalidate() }      // b-class-radial-stick: the pointed slice follows
        castView.onTriggers = { lt, rt -> trigHold(lt || rt) }                  // feat/clock-extras-v2: the clock band fades under a trigger
        castView.onInfoShrink = { infoCheck() }                                 // feat/home-info-block: a cast / L+R makes the block small
        infoCheck(instant = true)
        // ARRIVING MID-FIGHT (the user, 2026-10-01: "going back home by what ever means, engages the combat transition at that time"):
        // the takeover was already on while Home was hidden, so Home lands at its rest and the combat transition PLAYS from there
        if (flow.takeover) {
            apply(flow.screen.copy(takeover = false), animate = false)
            post { if (flow.takeover && isShown && shown?.takeover == false) { grewAt = System.currentTimeMillis(); apply(flow.screen, animate = true) } }
        } else apply(flow.screen, animate = false)
    }

    /** another room opens: everything borrowed goes back where it came from, last lent first */
    fun giveBack() {
        holdCancel()                                                      // a hold under way never opens edit mode on a hidden Home
        editAwayPark()
        watchB(false)
        setMapPage(HomeMapPages.Page.QUESTS); mapView.worldDone()                 // 2026-11-17: the map controls' sticks, the world's art
        mailCard(false)                                                   // another room: the mail card closes
        closeTypeTool()                                                   // f-trackpad-keyboard: another room: the tool closes, the guest freed
        // fix/polestar-b-home (the user, 2026-11-17: "when opening the polestar menu from the map, and then pressing B to go back home, it
        // returns to a full screen view of the map instead of the home screen. pressing B again doesnt do anything"): a room opened FROM a
        // full-screen module (the map row's X, its A on the Calendar, a bound command) closes the module HERE, laid while its views are
        // still lent, so its guest "home" is freed before the room takes and nothing of it (mapWanted / mapHeld, the fallback reader, the
        // hold's re-ask, ExitGuard's mapGuarding) lives on while Home is hidden; Home comes back on its rest
        if (flow.close()) { Log.i(TAG, "home: the full module closes (another room opens)"); apply(flow.screen, animate = false); onStateChanged?.invoke() }
        if (lent(mapView)) {
            mapView.compact = false; mapView.compactZoomButtons = false; mapView.headerRightInset = 0f; mapView.buttonTopInset = 0f
            mapView.showHeader = true
        }
        if (lent(castView)) {
            castView.away = false                                        // (Top screen's fight: the Cast room draws it again)
            castView.layout = CastView.Layout.FULL; castView.bottomLine = false; castView.onPadButtons = null
            castView.onClassRadial = null; castView.onClassStick = null; classLayerShow(false)   // the Cast room draws its own radial
            castView.onTriggers = null; trigHold(false)
            castView.onInfoShrink = null
            castView.groundPainter = null; castView.triggerStrip = false; redShown = false
            castView.partyStrip = false
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
    fun dropB(holder: String, muteMs: Long = HomeBGlyph.MUTE_MS) = bGlyph.drop(System.currentTimeMillis(), holder, muteMs)
    /**
     * The Home button shows B: when B would return Home (interact's `bnext`), and while one of Home's modules is full
     * screen (user, 15:08: "use the B glyph on the home button to go back to home").
     */
    /** a fight is on: the self-setup never closes the game then. combat-touch: the takeover OR the fight word, since a module opened
     *  by touch in a fight drops the takeover while the fight goes on (the setup, CombatKeep's taps and the map's hold retry wait) */
    fun inFight(): Boolean = fightShown()
    /** m132: the game is fighting as Home shows it - the takeover (Party mode off) or the grow (Party mode on, no takeover) */
    fun fightShown(): Boolean = flow.takeover || grow.fighting(System.currentTimeMillis())

    fun bGoesHome(): Boolean = bGlyph.shown(System.currentTimeMillis()) || (flow.full != null && !flow.takeover) ||
        // the buff / debuff list open: B returns Home at any time there (the user, 2026-09-30: "the red with the b button")
        auraPane.open != null ||
        // PARTY MODE (4.2; the user v4: "When in Party mode the Home button at the bottom should show red with the B"): B closes it
        partyOnHere()

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
        // Home's own reader (a chat / map module holds the controller). b-radial-exclusive (1.0.9, the user: one overlay at a time): the
        // class chord does nothing here - no wheel and no "close the chat" note over the page
        val p = bReader ?: PadInput({ _, _, _, _ -> }, { _, _ -> }, { held -> onPadHeld(held) }).also { bReader = it }
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
        mailCard(false)
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
                c.drawText(Tr.t(hint), width / 2f, height / 2f, p)
                return
            }
            // closing (classOpen false while it still shows): the slide-back draws the last radial (the chord is already let go)
            if (lent(castView)) castView.drawClassRadial(c, width.toFloat(), height.toFloat(), closing = !classOpen)
            // q62: a pet spell's autocast shine marches (and a toggle waits for the game's answer) while the wheel shows
            if (classOpen && lent(castView) && castView.classRadialLive()) postInvalidateDelayed(33L)
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
    /** 1.0.20 (the user: holding LB, then LT "flash[es] the chat before showing the class radial instead of closing the space to hide the
     *  party select and then sliding out"; LT let go with LB held: "the text to slide back in to cover it, and then slide out to show the
     *  party selecter"): the class wheel and the LB picture take turns - an open waits while the LB picture's sides are out
     *  ([classPending], opened when they are back: pauseSlide), and the host opens the LB picture only once the class wheel is closed
     *  and covered ([classBusy], [onClassIdle]) */
    private var classPending = false
    var onClassIdle: (() -> Unit)? = null
    fun classBusy(): Boolean = classOpen || classPending || (classLayer.parent != null && classLayer.visibility == View.VISIBLE)
    private fun classLayerShow(open: Boolean) {
        // the mail card (HomeMailButton) closes: one overlay at a time (b-radial-exclusive); the wheel's first touch is the wheel's
        if (open) mailCard(false)
        if (!open && classPending) { classPending = false; if (!classOpen) { post { onClassIdle?.invoke() }; return } }
        if (open && classHint == null && pauseSide > 0f && pauseT > 0f) { classPending = true; return }
        if (classLayer.parent == null) { if (!open) return; addView(classLayer) }   // added on first use: over everything
        val hint = classHint != null
        if (hint || !open) {
            if (open) { classRect.set(0, 0, width, frameHi); classLayer.animate().cancel(); classLayer.alpha = 1f
                classLayer.scaleX = 1f; classLayer.scaleY = 1f; classLayer.visibility = View.VISIBLE; requestLayout(); classLayer.invalidate(); return }
            if (!classOpen) { classLayer.visibility = View.GONE; return }
        }
        if (open == classOpen) { classLayer.invalidate(); return }
        classOpen = open
        val w = width.toFloat(); val h = frameH
        val g = UiKit.MODULE_GAP
        // 1.0.9 (the user: "make them appear on the right or left sides of the screen, and slide the whole chat away or back over top
        // when releasing, or the map if its the right rigger"): LB + LT takes the LEFT column's place (the chat slides off the left edge),
        // RB + RT the RIGHT column's (the map slides off the right edge); the other column stays. The side is the one it OPENED on
        if (open) classLeft = !lent(castView) || castView.classRadialLeft()
        val left = classLeft
        val col = ArrayList<Pair<View, RectF>>()
        val half = if (left) HomeSlide.Half.LEFT else HomeSlide.Half.RIGHT
        for ((p, v) in views) {
            if (p == Part.BARS) continue
            val r = now[p] ?: continue
            if (halfOf(p, r, w) == half) col += v to r
        }
        val area = ClassSlot.column(col.filter { it.first.visibility == View.VISIBLE }.map { (_, r) -> ClassSlot.Area(r.left, r.top, r.right, r.bottom) },
            (now[Part.BARS]?.bottom ?: 0f).coerceAtLeast(0f), h, g, left, w)
        val slot = ClassSlot.inColumn(area)
        classRect.set(slot.left.toInt(), slot.top.toInt(), (slot.left + slot.side).toInt(), (slot.top + slot.side).toInt())
        // the wheel lies UNDER its column (raised while it shows) and is uncovered as the column slides away; on release the column
        // slides back OVER it (the frozen picture rides it out, CastView), and the wheel goes when it is covered
        for ((p, v) in views) if (p != Part.BARS) {
            val r = now[p]
            v.translationZ = if (r != null && halfOf(p, r, w) == half && (open || classLayer.visibility == View.VISIBLE)) CLASS_COVER_Z else restZ(p)
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
                onClassIdle?.invoke()                                             // 1.0.20: the LB picture's turn
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
    /** fix/castbar-slides: the half a laid part belongs to for a side slide: the cast bar by its side (Both = neither), any other part by
     *  its centre ([HomeSlide.half]) */
    private fun halfOf(p: Part, r: RectF, w: Float): HomeSlide.Half =
        HomeSlide.half(p == Part.STRIP || p == Part.CAST2, laidSide, laidHidden, r.centerX(), w, split = laidSplit)

    /**
     * f-radial-middle (1.0.9, the user: "The Dpad up radial, and spell radial should slideup between the middle of the modules like the
     * class bars used to, like a mixture of what it is now and what those were sort of"): the item / spell wheel (HomeRadialView) asks
     * for its slot here. [open] true: a square under the bars on the line between the columns (ClassSlot.middle; fix/menus-fit: at any
     * share, either side); each column parts outwards as far as it needs to clear it (the class wheel's 1.0.8 motion), the slot is
     * returned in Home's coordinates; null =
     * Home is not on show or a module is full screen (the wheel then stays the full-screen one). false: the columns come back.
     */
    fun wheelSlot(open: Boolean): RectF? {
        if (open && (width == 0 || flow.screen.full != null)) return null            // the host asks only while Home is the room
        if (open == wheelOpen) return if (open) RectF(wheelRect) else null
        wheelOpen = open
        if (open) wheelFit(now)
        wheelPart(now, open)
        return if (open) RectF(wheelRect) else null
    }
    /**
     * the middle wheel's square for the page laid as [places]: as tall as the space under the bars allows (the user on test18: "I want
     * the dpad up radial to be about the same size as the pause menu radial"; the pause wheel fills the same height); no longer held to
     * the chat column's width (it rises from below, behind the parting columns). fix/castbar-slides: Both's bar stays where it is (it is
     * neither column's), so the wheel sits above it
     */
    private fun wheelFit(places: Map<Part, RectF>) {
        val w = width.toFloat(); val h = frameH
        val g = UiKit.MODULE_GAP
        val line = columnsLine(w)
        val s = ClassSlot.middle(w, h, (places[Part.BARS]?.bottom ?: 0f).coerceAtLeast(0f), g, w * WHEEL_SLOT_MAX,
            bottom = HomeSlide.middleBottom(laidSide, laidHidden, places[Part.STRIP]?.top, h, g), mid = (line.first + line.second) / 2f)
        wheelRect.set(s.left, s.top, s.left + s.side, s.top + s.side)
        wheelPush = s.push; wheelPushR = s.pushRight
    }
    /**
     * fix/radials-clip-castbar (the user on 1.0.32-1469: "The spells and ds tool bar radials both also clip that bar"): the page laid
     * again UNDER an open middle wheel (a fight beginning or ending: the bar slid away by Hide in combat comes back up, the controller
     * grows or shrinks) re-fits the wheel and parts the columns again by the page as it now is - the bar back in Left / Right goes with
     * its column, the wheel moves above Both's bar. It was fitted once, at its open: a spell radial opened in a fight (the bar hidden =
     * no column's) left the bar where the fight's end laid it back, under the wheel. [onWheelSlot] hands the wheel its new square.
     */
    private fun wheelRefit(places: Map<Part, RectF>) {
        if (!wheelOpen || width == 0) return
        val was = RectF(wheelRect)
        wheelFit(places)
        wheelPart(places, true)
        if (was != wheelRect) onWheelSlot?.invoke(RectF(wheelRect))
    }
    /** fix/radials-clip-castbar: the open wheel's square changed ([wheelRefit]), in Home's coordinates */
    var onWheelSlot: ((RectF) -> Unit)? = null
    /** the columns part for the middle wheel ([open]) by [places], or come back */
    private fun wheelPart(places: Map<Part, RectF>, open: Boolean) {
        val w = width.toFloat()
        for ((p, v) in views) {
            if (p == Part.BARS) continue
            if (v.visibility != View.VISIBLE) { if (!open) { v.animate().cancel(); v.translationX = 0f }; continue }
            val r = places[p] ?: continue
            val to = if (!open) 0f else HomeSlide.dx(halfOf(p, r, w), wheelPush, wheelPushR)
            v.animate().cancel()
            val a = v.animate().translationX(to).setDuration(if (open) Motion.MODULE_OPEN else Motion.MODULE_CLOSE)
                .setInterpolator(Motion.curve()).withLayer()
            // the map's well edge and zone band are Home's own paint (dispatchDraw): a redraw per frame of ITS slide (no layout)
            if (v === mapSlot) a.setUpdateListener { invalidate() } else a.setUpdateListener(null)
            a.setListener(null)
            a.start()
        }
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
                l.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(frameHi, MeasureSpec.EXACTLY))
                l.layout(0, 0, width, frameHi)
                l.invalidate()
            }
        }
    private var wheelLayer: View? = null
    fun wheelRedraw() { wheelLayer?.invalidate() }
    private var wheelOpen = false
    private val wheelRect = RectF()
    private var wheelPush = 0f
    private var wheelPushR = 0f
    /**
     * fix/menus-fit: the line between Home's two columns as laid (the left column's right edge, the right column's left edge;
     * HomeLayoutSolver.columns): the middle wheel and the LB picture stand on its middle, not the page's (Classic's is the page's)
     */
    private fun columnsLine(w: Float = width.toFloat()): Pair<Float, Float> {
        val c = HomeLayoutSolver.columns(laidLayout ?: layoutNow(), w, UiKit.MODULE_GAP)
        return c.first.second to c.second.first
    }
    /** fix/menus-fit: the laid record is Classic's ([HomeLayout.classicPlace]): the LB picture keeps 1516's place at rest (LbFit:
     *  under a grown band it is fitted like every layout, the user's pick 2026-11-21 "Fix in Classic too", as the map's paint during the
     *  LB / pause slides), the D-pad toast its spot on the grown diamonds' foot (not yet ruled on).
     *  The player's OWN record decides: a Top screen record laid as Grow while the add-on's Wings are not confirmed
     *  (TopCombatBar.honoured) is not Classic, so it keeps the fitted looks whatever the add-on's flag */
    private fun classicLook(): Boolean {
        if (!(laidLayout ?: layoutNow()).classicPlace) return false
        if (renderLook != null) return true
        return HomeLayoutRules.shown(editDraft() ?: HomeSettings.layout).fight != HomeLayout.FightBand.TOP
    }
    /**
     * fix/menus-fit: THE LB PICTURE's place on the page as laid (LbFit): under the band as it is now, on the line between the columns,
     * scaled to fit above a bar that stays (Both), and how far each side moves to clear it. Home's own coordinates.
     */
    fun lbPlace(): LbFit.Place {
        val w = width.toFloat(); val g = UiKit.MODULE_GAP
        val line = columnsLine(w)
        var bottom = frameH - g
        for (p in listOf(Part.STRIP, Part.CAST2)) {
            val r = now[p] ?: continue
            // slid away: below the page, or (a top bar) up behind the band - no limit
            if (views[p]?.visibility != View.VISIBLE || r.top >= frameH || r.bottom <= barsBottom() + 0.5f) continue
            if (halfOf(p, r, w) == HomeSlide.Half.NONE) bottom = minOf(bottom, r.top - g)
        }
        return LbFit.place(w, barsBottom(), line.first, line.second, bottom, classicLook(), UiKit.pauseBarsBottom)
    }

    /** p-grow-glitch: when the big controller last grew (wall clock, onB's), for B's grace */
    private var grewAt = 0L

    /** the held D-pad / face buttons from whichever reader runs: B's press, once */
    private fun onPadHeld(held: Int) {
        onPadHeldAny?.invoke(held)
        partyPad(held)
        val b = PadInput.isHeld(held, PadInput.SLOT_FACE_RIGHT)
        val was = bWasHeld
        bWasHeld = b                                           // before acting: onB may stop the reader that called us
        if (b && !was) onB()
    }

    private fun onB() {
        if (typeWanted) return                                            // the tool's B comes through the guest (it closes the tool)
        if (editLive()) return                                            // P2: edit mode's B comes through the guest "home" (its B ladder)
        // #540 (1.0.34: "Bar would not go back to combat mode while in combat and got stuck"): the B that closes one of the app's
        // overlays over Home (a wheel, the pause / context radial, the bar cursor) is that overlay's, never Home's shrink or close
        if (HomeOverlayB.owns(classOpen || overlayOpen?.invoke() == true, overlayClosedAt, android.os.SystemClock.uptimeMillis())) return
        // ClassPetKeys (the user, 2026-11-16: "B still makes a lot of sense to use here"; then "the glyph locations on this bar should
        // match where they actually are on the controller"): on a class pet wheel with an ability on B's spot (the physical button the
        // ABXY location puts B on) B is that ability's toggle - it never reaches the game and is nothing of Home's (no shrink, no close)
        if (lent(castView) && castView.classBToggles()) return
        // while the chat module holds the controller its B comes through the guest (the chat's own chain), not this read-only watch
        if (chatWanted && chatHeld) return
        if (mapWanted && mapHeld) return
        // THE MAIL CARD (HomeMailButton): B closes it first, and does nothing else of Home's (the game gets the press as always)
        if (mailOpen) { mailCard(false); return }
        // B closes the buff / debuff list first; while the list holds the controller its B comes through the guest (deselect first)
        if (auraPane.open != null) { if (!auraPane.padHeld) { auraPane.close(); onBHome?.invoke() }; return }
        if (flow.takeover) {
            val now = System.currentTimeMillis()
            // p-grow-glitch (a player: "they expand then return to normal after the first second"): B in a fight shrinks the big
            // controller (the user's own design), but B is a fight button too. Not in the first GROW_B_GRACE_MS after it grew (the
            // pull's opening presses), and never with ZL / ZR held (that B is a layer's ability)
            if (now - grewAt < GROW_B_GRACE_MS || (lent(castView) && castView.triggerHeld())) return
            // movable Home phase 3 (SPEC section 11): In fights = Top screen grew nothing - B in the fight has no new meaning (no shrink,
            // no red reminder); out of a fight (an attack armed) it still gives the faces back, as today
            if (!TopCombatBar.bShrinks(HomeSettings.layout, grow.fighting(now), padMap?.dpad?.ct)) return
            val shrank = grow.minimize(now)
            // the user's pick: out of a fight, B also gives A / X / Y back (Spells' give-back); in a fight B only shrinks
            if (HomeFightFace.giveBack(shrank, grow.fighting(now), fightArmed())) giveBackTap(retry = false)
            if (shrank && flow.combat(false)) { changed(); tick() }
        }
        else if (flow.close()) { changed(); onBHome?.invoke() }
    }

    /** #540: the host's word that one of its overlays over Home is open now (the item / spell wheel, the pause / context radial, the bar cursor) */
    var overlayOpen: (() -> Boolean)? = null
    private var overlayClosedAt = 0L
    /** #540: the host: one of those overlays closed just now (its B may reach [onB] after its own reader closed it) */
    fun overlayClosed() { overlayClosedAt = android.os.SystemClock.uptimeMillis() }

    /** B took a full module or the buff list back to Home's rest while the disc showed B: the disc's press (MainActivity: strip.homePressed) */
    var onBHome: (() -> Unit)? = null
    /**
     * THE CALENDAR BUTTON (the user, 2026-11-13: "The calender can be moved up to a button on the right side of the map name make it
     * small"; HomeCalendarButton): its touch (at rest and in a fight, the first touch) and the open map module cursor's A open the
     * Calendar room (the host's show)
     */
    var onCalendar: (() -> Unit)? = null
    /** an invitation waits (the calendar document's `pend`): the game's mark on the button */
    var calPending = false
        set(v) { if (field != v) { field = v; invalidate(); auraPane.invalidate() } }

    /**
     * THE MAIL ENVELOPE (the user, 2026-11-16: "we need to add an icon somewhere to the home menu for when the player has mail";
     * HomeMailButton): the calendar document's `mail` (Calendar c15). The envelope shows beside the Calendar button while the game says
     * the player has mail ([HomeMail.on]), wherever the button shows; one log line when that changes (a count, never a name).
     */
    var mail: HomeMail? = null
        set(v) {
            if (field == v) return
            val was = field?.on == true; val now = v?.on == true
            field = v
            if (!now) { mailCard(false); auraPane.mailGone() }
            if (was != now) Log.i(TAG, "home: mail ${if (now) "on (${v?.from?.size ?: 0} named)" else "off"}")
            invalidate(); auraPane.invalidate()
        }
    /** the senders card is open (the envelope's touch, or A on it in the open map module) */
    var mailOpen = false
        private set
    /** a touch that closed the card is swallowed to its end (it does nothing else: "tap elsewhere closes") */
    private var mailSwallow = false
    /** the card open / closed; it opens only while the envelope shows, and it closes the buff / debuff list */
    fun mailCard(open: Boolean) {
        val o = open && mail?.on == true
        if (o == mailOpen) return
        mailOpen = o
        if (o) auraPane.close()
        Log.i(TAG, "home: mail card ${if (o) "open" else "closed"}")
        invalidate(); auraPane.invalidate()
        // the 1.0.33 merge review: the host's B: pet follow word follows the card at once (bClosesSomething), as a module's state does
        onStateChanged?.invoke()
    }

    // ---------------------------------------------------------------- the fight faces and B's give-back

    /** the latest padmap (Spells' PadMap): the armed faces, the give-back column */
    private var padMap: PadMapModel.Map? = null

    /** every padmap (MainActivity's route): Cast's fight faces and their switch (the host's, never a constant), and the map kept */
    fun onPadMap(map: PadMapModel.Map) {
        val ctWas = padMap?.dpad?.ct
        padMap = map
        // the 1515 merge's review: the Wings' flag changed (the add-on said it, or a key came / went) - Top screen's band follows it
        if (map.dpad?.ct != ctWas && laidLayout != null && laidLayout != layoutNow() && shown?.let { it.full == null || it.takeover } == true)
            shown?.let { apply(it, animate = isShown) }
        HomeAttackSwitch.note(map)                      // Spells' start-attack switch, drawn in Home's Settings section
        castView.fightFace = HomeFightFace.icons(map)
        castView.fightFaceOn = HomeFightFace.on(map)
        castView.fishName = map.fish?.name; castView.fishIcon = map.fish?.icon ?: 0        // m59: X fishes while a pole is in the main hand
        castView.smartYIcon = map.qitem?.icon?.takeIf { it > 0 } ?: map.smartY?.icon ?: 0     // m61: Smart Y's food / drink; m74: a quest item first
        fightNow = map.fight                                            // m49: A / X / Y's switch (start attack / auto-repeat / combat)
        applyDpad()                                                      // Track C: the D-pad's In-combat spells, in fights only
        castView.commands = HomeFightFace.commandIcons(map)             // Track C: the companion's commands (overlays: the game's slot is empty)
    }

    /** the fight as the latest word says it: the padmap's `combat`, or a newer CURRENT `facewatch`'s `fight` (Tracking after test18:
     *  "have the app also honour the fight flag from faceswap/facewatch"; faceswap carries none) */
    private var fightNow = false
    private var dpadSaid: Map<Int, Int>? = null

    fun onFightWord(fight: Boolean) { if (fight != fightNow) { fightNow = fight; applyDpad() } }
    /** wk-dpad: the fight switch the D-pad's In-combat picture follows (the router's own `sw`): the host's early-bar gate reads it */
    fun dpadFight(): Boolean = fightNow

    private fun applyDpad() {
        val map = padMap ?: return
        // P4: edit mode's fight preview shows the In-combat pictures too (display only: nothing is sent)
        val fight = fightNow || editFightLook()
        val d = HomeFightFace.dpadIcons(map, fight)
        castView.fightDpad = d
        castView.fightIds = HomeFightFace.ids(map, fight)               // b-combat-bar-states: their state by spell id
        castView.fightItems = HomeFightFace.items(map, fight)           // PadMap m165: a macro using an item: the item's cooldown
        castView.trigFights = HomeFightFace.trigFights(map, fight)      // the LT / RT / LT+RT In-combat spells, in fights only
        castView.fightRanks = HomeFightFace.rankIds(map, fight)         // #471: the fight pictures' rank badges (spells only)
        castView.trigRanks = HomeFightFace.trigRankIds(map, fight)
        // test22 (the user: "I actually hate the form mark. Remove that."): no form mark on the bars, in a fight or otherwise
        // ONE line per change, so one approved log read proves what the toolbar's D-pad was given (Tracking's ask after test18)
        if (d != dpadSaid) { dpadSaid = d; Log.i("WCLOG", "dpad draw: fight=$fight dpad=$d") }
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
    /** the off-device tests: the open map cursor's stop - "mail", "calendar", "aura", or null (the map itself) */
    fun cornerFocus(): String? = when {
        auraPane.mailCur -> "mail"; auraPane.calCur -> "calendar"; auraPane.fullCursorOn -> "aura"; else -> null
    }
    /** the off-device tests: the envelope's box in Home's own px (the pane lies over the map), empty = not shown */
    fun mailBoxOnHome(): RectF = auraPane.mailBox().let { b ->
        if (b.isEmpty) b else RectF(b.left + auraPane.left + auraPane.translationX, b.top + auraPane.top + auraPane.translationY,
            b.right + auraPane.left + auraPane.translationX, b.bottom + auraPane.top + auraPane.translationY)
    }

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
        if (editLive()) { editPad(msg); return }                          // P2: edit mode holds the guest "home" while it runs
        if (mapWanted) { mapPad(msg); return }
        if (!chatWanted) { auraPane.onPad(msg); return }
        if (msg.has("lost")) chatHeld = false else chatHeld = true
        chatView.onPad(msg)
    }

    /** one press for the full map module: the MapView's host keys; B with nothing open leaves the module (Home's rest) */
    private fun mapPad(msg: JSONObject) {
        if (msg.has("lost")) {
            mapHeld = false; mapView.padHints = false
            // #584: asked again at once (a drop the capture did not explain), else the watch: Home's reader for B, the ask after the fight
            if (mapClaim.lost(msg.optString("lost"))) mapAsk() else { removeCallbacks(mapWatch); postDelayed(mapWatch, 1000L) }
            return
        }
        val n = msg.optInt("n", -1)
        if (n == homePadN) return
        homePadN = n
        if (!mapHeld) mapHeldNow()                                // a press arrived: held (also after a "pending" answer)
        val k = msg.optString("k")
        // THE THREE PAGES (HomeMapPages; the user, 2026-11-17: "when opening the map, pressing to the right should open some map controls,
        // pressing right again goes to the buffs and left goes back to the quests"): Quests <-> Map controls <-> Buffs on the D-pad.
        // b129 (the user's pick "Right goes to buffs", now the second Right): the buff / debuff list open = its keys; the cursor on an
        // aura = the auras' keys (Left off their end: the controls); SELECT = the map's filter (2026-10-09: Start opens the pause wheel
        // everywhere; Map's MapCursor takes PADBACK)
        mapView.controlsBuffs = auraPane.hasStop()
        if (auraPane.open != null) { auraPane.onPad(msg); syncMapB(); return }
        if (auraPane.fullCursorOn) {
            if (k == "PADBACK" || k == "PADLSHOULDER" || k == "PADRSHOULDER") mapView.hostKey(k) else auraPane.fullKey(k)
            setMapPage(HomeMapPages.afterBuffs(k, auraPane.fullCursorOn || auraPane.open != null))
            syncMapB(); return
        }
        if (mapPage == HomeMapPages.Page.BUFFS) setMapPage(HomeMapPages.Page.QUESTS)     // the pane's stop went (mail read): the list
        // the quest panel open (MapView): every key is the panel's - Right lights the quest's areas, not the buffs (the user, 2026-10-16:
        // "pressing over on the dpad should highlight quest areas not the buffs"); the buff pane redraws round it
        if (mapView.panelOpen()) {
            if (!mapView.hostKey(k)) { if (flow.close()) { changed(); onBHome?.invoke() }; return }
            auraPane.invalidate(); syncMapB(); return
        }
        if (mapPage == HomeMapPages.Page.CONTROLS) {
            // the filter (Select / View) works here as on the list: open, its keys are its own (B closes it first)
            if (mapView.filterShown() || k == "PADBACK") { mapView.hostKey(k); syncMapB(); return }
            val to = HomeMapPages.press(mapPage, k, auraPane.hasStop())
            if (to == HomeMapPages.Page.BUFFS) { auraPane.enterFull(); setMapPage(if (auraPane.fullCursorOn) to else mapPage) }
            else if (to != null) setMapPage(to)
            else mapView.controlsKey(k)
            syncMapB(); return
        }
        HomeMapPages.press(mapPage, k, auraPane.hasStop())?.let { to -> setMapPage(to); syncMapB(); return }    // Right: the controls
        if (k == "PADDLEFT" || k == "PADDRIGHT") return
        if (!mapView.hostKey(k)) { if (flow.close()) { changed(); onBHome?.invoke() }; return }
        syncMapB()
    }

    // ---- THE OPEN MAP'S PAGE (HomeMapPages): the list, the map controls (MapView.controls: the Map room's sticks), the buffs
    private var mapPage = HomeMapPages.Page.QUESTS
    /** the off-device tests: the open map module's page */
    fun mapPageNow(): HomeMapPages.Page = mapPage
    private fun setMapPage(p: HomeMapPages.Page) {
        if (p == mapPage) return
        val was = mapPage
        mapPage = p
        if (was == HomeMapPages.Page.BUFFS && p != HomeMapPages.Page.BUFFS && auraPane.fullCursorOn) auraPane.leaveFull()
        mapView.controlsBuffs = auraPane.hasStop()
        mapView.controls = p == HomeMapPages.Page.CONTROLS
        mapView.buffsPage = p == HomeMapPages.Page.BUFFS      // 2026-11-17: no quest cursor / list hints on the buffs page
        sticksCatch(p == HomeMapPages.Page.CONTROLS)
        Log.i(TAG, "map module: page ${p.name.lowercase()}")
        invalidate(); auraPane.invalidate()
    }
    /** the map controls hold the sticks in the game (Map.lua mr8's catch: the character does not walk while they move the map) */
    private var sticksOn = false
    private fun sticksCatch(on: Boolean) {
        removeCallbacks(sticksKeep); removeCallbacks(sticksCheck)
        if (on == sticksOn && !on) return
        val was = mapSticksLive()
        sticksOn = on
        if (on) sticksAsks++ else sticksAsks = 0
        val cells = MapRoomPad.homeSticksCells(pad, on)
        sticksRow = cells != null
        val sent = cells != null && pad != null && TapBridge.tapsSpaced(context, pad, cells, MapRoomPad.GAP_MS)
        if (sent) {
            if (on != sticksSaid) Log.i(TAG, "map controls: sticks ${if (on) "held" else "let go"}")
            sticksSaid = on
        }
        // #584: every ask of the catch (not the 4 s keep-alive once it shows) and why one could not go
        if (on && !mapClaim.caught) Log.i("WCMAP", "map controls: stick catch ask $sticksAsks ${if (sent) "sent" else "not sent (${if (cells == null) "no map row" else "no pad document"})"}" +
            if (mapHeld) "" else " (the module not held yet: the cursor waits)")
        if (on) postDelayed(if (sent && !mapClaim.caught) sticksCheck else sticksKeep, if (sent && !mapClaim.caught) STICKS_ANSWER_MS else MapRoomPad.HOME_STICKS_KEEP_MS)
        if (mapSticksLive() != was) { if (!was) mapView.sticksResume(); mapView.invalidate() }
    }
    private var sticksSaid: Boolean? = null
    private val sticksKeep = Runnable { if (sticksOn && mapPage == HomeMapPages.Page.CONTROLS && mapWanted) sticksCatch(true) }
    /** #584: the catch's answer did not come in [STICKS_ANSWER_MS]: asked again ([STICKS_ASKS] at most, then the 4 s keep-alive) */
    private val sticksCheck = Runnable {
        if (!sticksOn || mapPage != HomeMapPages.Page.CONTROLS || !mapWanted) return@Runnable
        if (mapClaim.caught || sticksAsks >= STICKS_ASKS) {
            if (!mapClaim.caught) Log.i("WCMAP", "map controls: no stick catch after $sticksAsks asks: the stick stays the game's, the cursor waits")
            postDelayed(sticksKeep, MapRoomPad.HOME_STICKS_KEEP_MS); return@Runnable
        }
        sticksCatch(true)
    }
    private var sticksAsks = 0
    /** the map row is published (Map.lua mr8): the catch is asked for, so the cursor waits for it */
    private var sticksRow = false

    // ---- #584 THE MODULE'S CLAIM (MapHoldClaim; the Map room's rule): the cursor and the zoom move only while the module holds the
    // controller (mapHeld) AND, on the controls page, the game's stick catch shows (once Home's has shown at all: an older add-on
    // without it keeps the hold alone). Else the stick is the game's: the character walks, the cursor stays. WCMAP lines.
    private val mapClaim = MapHoldClaim("map module") { Log.i("WCMAP", it) }
    /** the tests: the claim, and whether the sticks are the map's now */
    fun mapClaimState(): MapHoldClaim.St = mapClaim.st
    fun mapSticksLive(): Boolean = mapWanted && mapHeld && mapClaim.catchOk(needCatch = sticksOn && sticksRow)
    /** Map.lua's mapwin `stick` (the host, every document): the game's stick catch shows or not */
    fun onMapStick(on: Boolean) {
        val was = mapSticksLive()
        val before = mapClaim.caught
        mapClaim.stick(on)
        if (mapClaim.caught == before) return
        // it dropped while the controls hold the sticks: asked again (the add-on lets it go by itself when Home stops holding, or 10 s on)
        if (!on && mapWanted && sticksOn && mapPage == HomeMapPages.Page.CONTROLS) {
            Log.i("WCMAP", "map controls: the stick catch dropped: asked again"); sticksAsks = 0; sticksCatch(true)
        }
        val now = mapSticksLive()
        if (now && !was) mapView.sticksResume()
        if (now != was) mapView.invalidate()
    }
    /** a touch on the open map's field: the map controls (the user: "tapping in the map area should also change it to the map controls") */
    private fun mapFieldTouched() {
        if (!mapWanted) return
        setMapPage(HomeMapPages.touched(mapPage))
        syncMapB()
    }

    /** the Home button's B follows the map's own chain: "home" with nothing open, "back" while its filter is */
    private fun syncMapB() {
        // b129: B on an aura (or in its list) steps back inside the module, not Home
        val where = if (auraPane.open != null || auraPane.fullCursorOn) "back" else HomeMapPages.bLeads(mapPage, mapView.hostBLeads())
        if (where != mapBSaid && homeGuestTap(GuestPad.bnext(pad, home = where == "home"))) mapBSaid = where
    }

    fun auraGuestAck(msg: JSONObject) {
        if (typeWanted) {
            if (msg.optString("op") == "hold" && !msg.optBoolean("ok")) Log.i(TAG, "type tool: hold refused (${msg.optString("why")})")
            return
        }
        if (mapWanted) {
            when (msg.optString("op")) {
                // combat_nav Step 1 (the user, 2026-11-11): "pending" (a fight's take until the ENTER tap) is NOT held: the guard reads B
                // meanwhile; the first press that arrives (mapPad) makes it held
                "hold" -> if (mapAnswer(msg)) mapHeldNow()
                    else if (MapExitGuard.ackPending(msg.optBoolean("ok"), msg.optString("why"))) {
                        Log.i(TAG, "map module: hold pending (the fight's ENTER)")
                        mapHeld = false; mapRefused = false; mapFallbackOn(); removeCallbacks(mapWatch); postDelayed(mapWatch, 1000L)
                    }
                    else {
                        Log.i(TAG, "map module: hold refused (${msg.optString("why")})"); homeGuestTap(GuestPad.cells(pad, GUEST_ID, hold = false))
                        // never a module the player cannot leave: B read by Home closes it (MapExitGuard), the hold asked again after the fight
                        mapRefused = true; mapFallbackOn(); removeCallbacks(mapWatch); postDelayed(mapWatch, 1000L)
                    }
                "free", "leave" -> { mapClaim.freed(); mapHeld = false; mapView.padHints = false }
            }
            return
        }
        if (!chatWanted) { auraPane.onGuestAck(msg); return }
        when (msg.optString("op")) {
            "hold" -> if (MapExitGuard.ackPending(msg.optBoolean("ok"), msg.optString("why"))) {
                chatHeld = false                                  // combat_nav Step 1 (2026-11-11): pending is not held; the first press holds
            } else if (msg.optBoolean("ok")) {
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
    fun homeOwnGuestWanted(): Boolean = chatWanted || mapWanted || auraPane.open != null || edit?.parked == false
    /** B: pet recall's rest-layout B (PetRecall; was Smart pet / B: pet follow): something of Home's own is open that B closes - a module full screen (chat, map, the bars), the buff /
     *  debuff list, the Keyboard & Trackpad tool - so B's press is not Home's plain B. The 1.0.33 merge review (feat/wk-mail x
     *  feat/wk-b-pet-follow): the mail card too - its B "closes it first, and does nothing else of Home's", so it never calls the pet */
    fun bClosesSomething(): Boolean = flow.full != null || auraPane.open != null || typeWanted || mailOpen
    /** the chat module is full screen (the ChatView lent, the guest "home" wanted): only then does the chat's B sign reach the game */
    val chatModuleOn: Boolean get() = chatWanted

    private var chatWanted = false
    private var chatHeld = false
    private var mapWanted = false
    private var mapHeld = false
    /** the map module's exit guard (MapExitGuard): when the hold was asked, whether the game refused it, Home's own reader for B */
    private var mapAskAt = 0L
    private var mapRefused = false
    private var mapFallbackReader: PadInput? = null
    private var mapFallbackHeld = 0
    private val mapWatch = Runnable { mapWatchStep() }
    private fun mapAsk() {
        mapAskAt = android.os.SystemClock.uptimeMillis(); mapRefused = false
        if (!mapClaim.shown) mapClaim.shown() else if (!mapClaim.held) mapClaim.again("the module")
        val cells = GuestPad.cells(pad, GUEST_ID, hold = true)
        val sent = homeGuestTap(cells)
        if (sent) Log.i(TAG, "map module: hold")
        mapClaim.asked(++mapAsks, 0, sent, if (sent) "the guest home" else if (pad == null) "no pad document yet" else "no guest cell")
        removeCallbacks(mapWatch); postDelayed(mapWatch, MapExitGuard.ANSWER_MS)
    }
    private var mapAsks = 0
    /** a hold answer for the module: the claim logs it; true = it holds (MapExitGuard.ackHeld: "pending" does not) */
    private fun mapAnswer(msg: JSONObject): Boolean {
        mapClaim.answer(msg.optBoolean("ok"), msg.optString("why"))
        return MapExitGuard.ackHeld(msg.optBoolean("ok"), msg.optString("why"))
    }
    /** the host's capture holder (null = not known): Home asks again after a loss that names a holder only once nobody holds */
    var capHolderNow: () -> String? = { null }
    /** once a second while the module is open without the controller: the fallback reader, and the hold asked again after the fight */
    private fun mapWatchStep() {
        if (!mapWanted) { mapFallbackOff(); return }
        val since = android.os.SystemClock.uptimeMillis() - mapAskAt
        // #584: a loss that names a holder or a choice (taken, npc, B ...) waits until the capture names nobody (no ping-pong in its queue)
        val waitLoss = mapClaim.st == MapHoldClaim.St.LOST && mapClaim.why in MapHoldClaim.WAIT_WHYS
        val nobody = capHolderNow().let { it == null || it.isEmpty() }
        // #584's review: while that other holder (an NPC window, a room) drives the pad, Home reads no B of its own - the holder's B (the
        // loot / vendor window's close) would close the map module too. The reader starts once nobody holds and no hold came back
        if (waitLoss && !nobody) mapFallbackOff(keepWatch = true)
        else if (MapExitGuard.fallback(mapWanted, mapHeld, mapRefused, since)) mapFallbackOn()
        if (MapExitGuard.retry(mapWanted, mapHeld, inFight(), since) && (!waitLoss || nobody)) {
            Log.i("WCLOG", "map module: the hold asked again (out of the fight)"); mapAsk(); return
        }
        if (!mapHeld) postDelayed(mapWatch, 1000L)
    }
    /** the map module holds the controller now (a "held" answer, or the first press after "pending"): the guard off */
    private fun mapHeldNow() {
        val was = mapSticksLive()
        mapClaim.press()
        mapHeld = true; mapBSaid = null; mapView.padHints = true; mapView.hostStart(); syncMapB(); mapFallbackOff()
        if (mapSticksLive() && !was) mapView.sticksResume()
    }
    private fun mapFallbackOn() {
        if (mapFallbackReader != null) return
        Log.i("WCLOG", "map module: no controller hold (${if (mapRefused) "refused: a fight" else "no answer"}): B closes it")
        // the note fits the case: a fight's refusal / pending ENTER, or (out of one) a hold that did not come back
        val fight = inFight() || mapClaim.why == "combat" || mapClaim.st == MapHoldClaim.St.PENDING
        mapNoteSaid = if (fight) FIGHT_MAP_NOTE else KEPT_MAP_NOTE
        auraPane.note(mapNoteSaid!!)
        mapFallbackHeld = 0
        mapFallbackReader = PadInput(onPad = { _, _, _, _ -> }, onStatus = { _, _ -> }, onButtons = { h ->
            post {
                val was = mapFallbackHeld; mapFallbackHeld = h
                if (mapWanted && !mapHeld && MapExitGuard.bPressed(was, h)) {
                    Log.i("WCLOG", "map module: B read by Home: closed")
                    if (flow.close()) { changed(); onBHome?.invoke() }
                }
            }
        }).also { it.start() }
    }
    /** the tests: Home's own B reader for the module runs, and the last note it said */
    fun mapFallbackReading(): Boolean = mapFallbackReader != null
    var mapNoteSaid: String? = null
        private set
    /** ExitGuard (the host's): the map module guards itself */
    fun mapGuarding(): Boolean = mapWanted
    /** fix/polestar-b-home: the module believes it holds the controller (its B then comes through the guest, never Home's own reader) */
    fun mapHolds(): Boolean = mapWanted && mapHeld
    /**
     * fix/polestar-b-home (the user, 2026-11-17: "pressing B again doesnt do anything to return it home"): the capture names nobody
     * holding (not the guest "home") while the open module still believes it holds (MapExitGuard.holdGone, the host's guard tick): no press
     * reaches the module and its B was swallowed (onB leaves B to the guest). Not held any more: Home's own reader reads B (it closes the
     * module) and the hold is asked again; the first press that arrives makes it held again (mapPad).
     */
    fun mapHoldGone() {
        if (!mapHolds()) return
        Log.i("WCLOG", "map module: the capture names nobody: not held, B read by Home, the hold asked again")
        mapClaim.nobody()
        mapHeld = false; mapBSaid = null; mapView.padHints = false
        mapFallbackOn()
        mapAsk()
    }
    /** ExitGuard's B: one step back out of Home's own controller layer - the aura list, the type tool, a module full screen */
    fun guardBack(): Boolean {
        if (auraPane.open != null) { auraPane.close(); changed(); return true }
        if (typeWanted) { closeTypeTool(); return true }
        if (flow.close()) { changed(); onBHome?.invoke(); return true }
        return false
    }
    /** ExitGuard: ask the guest "home" (or the tool's) again, out of the fight */
    fun guardReask() {
        if (typeWanted) return                                         // the tool asks for its own hold when it opens again
        if (homeOwnGuestWanted() && homeGuestTap(GuestPad.cells(pad, GUEST_ID, hold = true))) Log.i("WCLOG", "exit guard: Home's hold asked again")
    }
    /** Home's B reader off; [keepWatch] = the once-a-second watch goes on (another holder has the pad for now) */
    private fun mapFallbackOff(keepWatch: Boolean = false) {
        if (keepWatch && mapFallbackReader != null) Log.i("WCLOG", "map module: another holder has the controller: Home's B reader off meanwhile")
        mapFallbackReader?.stop(); mapFallbackReader = null; mapFallbackHeld = 0
        if (!keepWatch) removeCallbacks(mapWatch)
    }
    private var mapBSaid: String? = null
    private var homePadN = -1
    private fun homeGuestTap(cells: List<Pair<String, Int>>?): Boolean = cells != null && pad != null && TapBridge.taps(context, pad, cells) { }
    /** Home leaves the screen: an open list closes (and hands the controller back) */
    fun closeAuras() = auraPane.close()

    /** one of [HomeFlow]'s moves from outside (the preview harness; later the controller) */
    fun act(move: HomeFlow.() -> Boolean) { if (flow.move()) changed() }

    // ================================================================ documents (MainActivity routes them)

    /** q65: the quests document's own word on a fight (its `cb`): the quest item's tap is refused in one */
    private var questsFight = false

    fun onQuests(q: JSONObject, current: Boolean) {
        quests.update(q, current, System.currentTimeMillis())
        pad = q.optJSONObject("pad")
        questsFight = q.optBoolean("cb", false)
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
        // r20 THE CLOCK EXTRAS' gold and free bag slots (the user's picks 2026-11-13): from the bags the app already gets
        msg.optJSONObject("bags")?.let { bg ->
            val g = if (bg.has("money")) bg.optLong("money") else null
            // feat/home-info-block (the user, 2026-11-19: "a number out of total available"): the bags USED / total, the ammo / its room
            val b = ClockBand.bags(bg) ?: bagCount
            if (g != goldCopper || b != bagCount) { goldCopper = g; bagCount = b; invalidate() }
            // the ammo (worn + what the quiver or the bags hold of it); a bags doc without `equipped` keeps what was said; 0 stays shown
            val a = ClockBand.ammo(bg, ammo)
            if (a != ammo) { ammo = a; invalidate() }
        }
        val fight = HomeCombat.fromFull(msg)?.also { grow.noteFight(it, current, now) }
        val attack = HomeGrow.fromFull(msg)?.also { grow.noteAttack(it, current, now) }
        if (fight != null || attack != null) tick()
    }

    fun onChat(c: JSONObject) { if (chat.update(c)) chatPane.refresh() }

    /** r20: the purse in copper (null = not said yet) and the free slots in the bags that take anything (-1 = not said yet) */
    private var goldCopper: Long? = null
    /** feat/home-info-block: the bags used / total (null = not said yet) */
    private var bagCount: ClockBand.Bags? = null
    /** feat/clock-extras-v2: the ammo (null = a character with no ammo use / not said); feat/home-info-block: 0 shows, out of its room */
    private var ammo: ClockBand.Ammo? = null

    /**
     * feat/home-info-block: THE BLOCK'S GROW (0 = today's small block, 1 = grown into the empty space; ClockBand.grow), linear here,
     * eased on Motion's curve where drawn. Small while CastView.infoShrink (a cast, LB + RB, ZL + ZR); [infoCheck] on each change.
     */
    private var infoGrown = true
    private var infoFrom = 1f
    private var infoAt = 0L
    private val infoCurve = Motion.curve()
    private fun infoLinear(now: Long): Float = ClockBand.growStep(infoFrom, infoGrown, now - infoAt)
    private fun infoG(now: Long): Float = infoCurve.getInterpolation(infoLinear(now))
    private val infoRecheck = Runnable { infoCheck() }
    private fun infoCheck(instant: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        val grown = !castView.infoShrink(now)
        if (instant) { infoGrown = grown; infoFrom = if (grown) 1f else 0f; infoAt = now; invalidate(); return }
        if (grown == infoGrown) return
        infoFrom = infoLinear(now); infoGrown = grown; infoAt = now
        invalidate()
    }

    /**
     * THE EXTRAS ROW's items (feat/clock-extras-v2; the user's picks 2026-11-13, each by its own Bottom > Home row), in ClockBand's
     * order: the Hearthstone ("shows ALWAYS: bright icon when ready, icon + mm:ss while cooling"; ready = its soft glow), the class
     * teleport (once known), the free bag slots, the equipped ammo. The clock + battery are NOT in it: the top clock chip, as before
     * (the user: "the time and Battery still needs to be centered at the top like it was before"); nor the purse: the chip's gold line
     * under the battery ([chipGold]; the user, 2026-11-13: "lets ditch the Icon for the gold and just list our total gold under the battery").
     */
    private fun bandItems(): List<UiKit.BandItem> {
        val hs = HomeSettings.clockHearth && ItemCd.hearthHave != false
        val tp = ItemCd.teleport.takeIf { HomeSettings.clockTeleport }
        val bags = HomeSettings.clockBags && bagCount != null
        val am = ammo.takeIf { HomeSettings.clockAmmo }
        return ClockBand.kinds(hs, tp != null, bags, am != null).mapNotNull { k -> bandItem(k) }      // ItemCd is written off the main thread
    }
    /** one extra as the row draws it from its data now (null = no data: the row leaves it out) */
    private fun bandItem(k: ClockBand.Kind): UiKit.BandItem? = when (k) {
        ClockBand.Kind.HEARTH -> if (ItemCd.hearthHave == false) null else {
            val l = ItemCd.left(ItemCd.HEARTH); val dim = ItemCd.dim(ItemCd.HEARTH)
            UiKit.BandItem.Icon(Icons.get(ItemCd.hearthIcon), if (l > 0.0) ItemCd.mmss(l) else "", cooling = l > 0.0 || dim,
                glow = ClockBand.hearthGlow(ItemCd.hearthHave, l, dim))
        }
        ClockBand.Kind.TELEPORT -> ItemCd.teleport?.let { tp ->
            val l = ItemCd.teleportLeft()
            UiKit.BandItem.Icon(Icons.get(tp.icon), if (l > 0.0) ItemCd.mmss(l) else "", cooling = l > 0.0 || ItemCd.teleportDim())
        }
        ClockBand.Kind.BAGS -> bagCount?.let { b ->                                              // the backpack's icon, used / total
            UiKit.BandItem.Icon(Icons.get(VendorSlots.BAG_ICON), ClockBand.outOf(b.used, b.total), out = b.full) }
        ClockBand.Kind.AMMO -> ammo?.let { am -> UiKit.BandItem.Icon(Icons.get(am.icon), ClockBand.outOf(am.count, am.of), cooling = am.empty, out = am.empty) }
    }

    /**
     * P2 (the user's ruling 2026-11-22: the clock's extras as apps): edit mode's row - EVERY extra turned on (its own picture, dimmed,
     * while it has no data today: it still wiggles and hides), then the "+" spot while one is hidden ([EDIT_PLUS], a blank cell the
     * chrome draws its "+" on); [editRowKeys] their keys in the same order. Outside edit mode the row is [bandItems]' (unchanged)
     */
    private fun editRowKeys(): List<String> = buildList {
        for (k in HomeEditModel.EXTRA_KEYS) if (k != "gold" && BottomTiles.clockOn(k)) add(k)
        if (HomeEditModel.EXTRA_KEYS.any { !BottomTiles.clockOn(it) }) add(EDIT_PLUS)
    }
    private fun editBandItems(keys: List<String>): List<UiKit.BandItem> = keys.map { k ->
        val kind = when (k) { "hearth" -> ClockBand.Kind.HEARTH; "teleport" -> ClockBand.Kind.TELEPORT; "bags" -> ClockBand.Kind.BAGS; "ammo" -> ClockBand.Kind.AMMO; else -> null }
        kind?.let { bandItem(it) } ?: UiKit.BandItem.Icon(if (k == EDIT_PLUS) null else BottomTiles.CLOCK_LINES.firstOrNull { it.first == k }?.let { Icons.get(it.third) },
            "", cooling = true)
    }
    /** edit mode's gold line: the purse while Gold is on (0 until the bags say it: the line still shows, wiggles and hides) */
    private fun editChipGold(): Long? = if (HomeSettings.clockGold) (goldCopper ?: 0L) else null
    /** the clock block is edit mode's now (live, not parked): its extras as apps. P4 fix round (the review): not while the fight preview
     *  shows - the block is then drawn exactly as a fight draws it (the extras with data only, no "+" cell, nothing wiggling) */
    private fun editClock(): Boolean = edit?.let { !it.parked && !it.fightPreview } == true
    /** the row's keys the last edit-mode clock block drew (its cells' poses: [extraPoser]) */
    private var editRowDrawn: List<String> = emptyList()
    /** P2: each extra's wiggle on its own key (the gold line -1), about its cell's centre; nothing outside edit mode */
    private val extraPoseBuf = HomeEditMotion.Pose()
    private val extraPoser = object : UiKit.CellPose {
        override fun apply(c: Canvas, i: Int, cx: Float, cy: Float) {
            val k = if (i < 0) "gold" else editRowDrawn.getOrNull(i) ?: return
            if (!extraPoseAt(k)) return
            val p = extraPoseBuf
            c.translate(p.dx, p.dy); c.rotate(p.rot, cx, cy); c.scale(p.scale, p.scale, cx, cy)
        }
    }
    /** extra [k]'s pose now into [extraPoseBuf]; false = none */
    private fun extraPoseAt(k: String): Boolean {
        if (!motion.on || !editPaintPosed() || !editClock()) return false          // the fight preview: nothing wiggles
        val key = if (k == EDIT_PLUS) HomeEditMotion.PLUS_EXTRA else HomeEditMotion.extraKey(HomeEditModel.EXTRA_KEYS.indexOf(k).coerceAtLeast(0))
        motion.pose(key, motionNow(), EXTRA_HALF_DIAG, extraPoseBuf)
        return !extraPoseBuf.isIdentity
    }

    /** the purse under the battery (the Gold row On and the bags said), else null: no line, the chip as before */
    private fun chipGold(): Long? = goldCopper.takeIf { HomeSettings.clockGold }

    /** feat/clock-extras-v2: the extras row under the triggers (ZL / ZR held = fading out, let go = back), Home's own fade; the clock chip keeps its own rules */
    private var trigHeld = false
    private var trigFrom = 1f
    private var trigAt = 0L
    private fun trigVis(now: Long): Float = ClockBand.fade(trigFrom, trigHeld, now - trigAt, FADE_MS)
    private fun trigHold(held: Boolean) {
        if (held == trigHeld) return
        val now = android.os.SystemClock.elapsedRealtime()
        trigFrom = trigVis(now); trigHeld = held; trigAt = now
        invalidate()
    }

    /** THE WORLD NOTICE over the chat (the user's pick "A: system line"; Chat's chat21 `notice` documents, current only) */
    private var notice: NoticeModel.Notice? = null
    /** chat40: the Battle.net friend notices under it (newest first, as the game's toasts live), and the "!" press waiting for the game */
    private var bnet: List<NoticeModel.Bnet> = emptyList()
    private var goAt = -1L
    fun onNotice(msg: JSONObject) {
        val now = android.os.SystemClock.uptimeMillis()
        notice = NoticeModel.apply(notice, msg, now)
        bnet = NoticeModel.applyBnet(bnet, msg, now)
        NoticeModel.goAnswer(msg)?.let { took ->
            goAt = -1L
            if (!took) Log.i(TAG, "world refresh: refused (${msg.optString("why")})")
        }
        chatPane.invalidate()
        chatView.worldChanged()
    }

    /**
     * chat40: the world-refresh notice and the Battle.net notices at the top of [r] (Home's chat pane, and the chat module / Chat room's
     * log through [chatWorld]); [bang] is set to the "!" box (empty = no refresh notice); [focused] = a controller cursor on the "!" (the
     * kit's focus and its A). [v] is asked for the next frame of the count and the fades. Returns the height used.
     */
    private fun drawNotices(c: Canvas, r: RectF, bang: RectF, focused: Boolean, v: View): Float {
        val now = android.os.SystemClock.uptimeMillis()
        notice = NoticeModel.live(notice, now)
        var top = r.top + (notice?.let { n ->
            v.postInvalidateDelayed(1_000L - (now - n.at).mod(1_000L))          // the next whole second of the game's count
            UiKit.notice(c, r, n.minutes(now), n.seconds(now), pressed = goAt >= 0 && now - goAt < GO_GUARD_MS, bang = bang,
                focused = focused, urgent = n.urgent(now), flashMs = n.flashMs(now))   // chat41: red in the last minute; chat42: its flash
        } ?: 0f)
        if (notice == null) bang.setEmpty()
        // the Battle.net notices under it (chat41: every kind the game toasts), each fading as the game's toast does
        bnet = NoticeModel.liveBnet(bnet, now)
        for (b in bnet) top += UiKit.bnetNotice(c, RectF(r.left, top, r.right, r.bottom), b, b.alpha(now), flashMs = b.flashMs(now))
        if (bnet.isNotEmpty()) v.postInvalidateDelayed(50L)
        // chat42: a flash plays smoothly (every frame while one is on)
        if ((notice?.let { it.flashMs(now) < UiKit.NOTICE_FLASH_MS } == true) || bnet.any { it.flashMs(now) < UiKit.NOTICE_FLASH_MS })
            v.postInvalidateDelayed(16L)
        return top - r.top
    }

    /** chat40: the same notices over the ChatView's log (the chat module and the Chat room), its "!" on the controller too (ChatPad) */
    private val chatWorld = object : ChatView.WorldNotice {
        override fun live(): Boolean = NoticeModel.live(notice, android.os.SystemClock.uptimeMillis()) != null
        override fun shows(): Boolean = live() || NoticeModel.liveBnet(bnet, android.os.SystemClock.uptimeMillis()).isNotEmpty()
        override fun draw(c: Canvas, r: RectF, focused: Boolean, bang: RectF, v: View): Float = drawNotices(c, r, bang, focused, v)
        override fun go() = noticeGo()
    }.also { chatView.world = it }

    /** chat40 (the user, 2026-11-16: "the ! notice part should be a button to trigger the refresh"): the game's own Refresh now (Chat's
     *  chcmd column 1: C_PlayerInteractionManager.ShardTransferConfirm, the pop-up's button), one press at a time (2 s guard) */
    private fun noticeGo() {
        val now = android.os.SystemClock.uptimeMillis()
        if (goAt >= 0 && now - goAt < GO_GUARD_MS) return
        goAt = now
        chatPane.invalidate(); chatView.invalidate()
        chatPane.postInvalidateDelayed(GO_GUARD_MS); chatView.postInvalidateDelayed(GO_GUARD_MS)
        if (pad == null || !TapBridge.taps(context, pad, listOf("chcmd" to 1)) { why -> post { goAt = -1L; chatPane.invalidate(); chatView.invalidate(); Log.i(TAG, "world refresh: $why") } }) {
            goAt = -1L
            Log.i(TAG, "world refresh: not sent (no tap pad)")
        }
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
        // q65: the quest item's answer: a refusal is said on the card
        if (msg.optString("op") == "useitem") { if (!msg.optBoolean("ok")) questPane.note(msg.optString("why", "").ifEmpty { "Refused" }); return }
        // every answer to a focus is logged (test50: 92860's refusal was only drawn, so the log could not say why it never took)
        if (msg.optString("op") == "track" && quests.wanted != null)
            Log.i(TAG, "focus ${quests.wanted}: the game answered ${if (msg.optBoolean("ok")) "ok" else "refused"} for " +
                "${if (msg.has("id")) msg.optInt("id") else "no quest"}${msg.optString("why", "").let { if (it.isEmpty()) "" else " ($it)" }}")
        val asked = quests.wanted
        val note = quests.ack(msg, System.currentTimeMillis())
        if (asked != null && quests.wanted == null && note == "") Log.i(TAG, "focus $asked: took (the game's answer)")
        if (note == null) {
            // a refusal of a focus: the check retries once the fresh list is in (or after its short wait)
            if (quests.wanted != null) postDelayed({ focusCheck() }, HomeQuests.DOC_WAIT_MS + 50L)
            return
        }
        if (note.isNotEmpty()) questPane.note(note)
        focusCheck()
        questPane.invalidate()
    }

    /**
     * Once a second, and after each document: the big controller follows the auto-attack and the fight, and lapses when the log
     * goes quiet; the red reminder follows [HomeGrow.red] while the small controller is on the page.
     */
    fun tick() {
        val now = System.currentTimeMillis()
        focusCheck()
        if (quests.expire(now)) questPane.note("No answer from the game")
        if (auras.expire(now)) auraPane.note("No answer from the game")
        if (auraPane.open != null) auraPane.invalidate()                 // time left counts down
        // v17: in a fight with Party mode on there is NO takeover (switched on mid-fight, the takeover shrinks back: COMBAT_SHRINK)
        if (flow.combat(takeoverWanted(now))) { if (flow.takeover) grewAt = System.currentTimeMillis(); changed() }
        // a fight began / ended with Party mode on (only where the module is placed: not under a full-screen module)
        else if (party.party != null && (flow.full == null || flow.takeover) && partyModeNow(flow.screen) != laidPartyMode) changed()
        castStripFight()                                                  // the tap-to-cast bar's Hide in combat follows the fight
        editFightCheck(relay = true)                                      // P2: a fight parks edit mode's draft, its end brings it back
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

    // ps3 (the user, 2026-11-11): a quest picked on Home is the player's choice: the Polestar row lets go until the next login
    private fun focus(id: Int) { mapView.psRelease(); focusQuest(id) { questPane.note(it) } }

    /**
     * ONE FOCUS PATH for every room (Home's quest list; the Map room's numbered bubbles and its left list, LATER item 10): make quest
     * [id] the game's focused (super-tracked) quest through quests' QuestPad pair, with Home's checks (live, fresh quest list, not in
     * combat, not while waiting, a pad row, a tap column) and Home's WCHOME logging. [note] shows a refusal where the tap was made.
     * Focusing the quest that is already focused does nothing (the user's 09-29 ruling). True = the taps went out, or the game already
     * had it (ps3: the Polestar sync counts only a send that went out, PolestarFocusSync.sent).
     */
    fun focusQuest(id: Int, note: (String) -> Unit): Boolean {
        val ok = when (val t = quests.tapDisc(id, GameInput.live(), System.currentTimeMillis())) {
            is FocusTap.Send -> {
                focusNote = note
                sendFocus(id, t.slot, "send", note)
            }
            // every refusal goes to logcat too (tag WCHOME): the note is only drawn, so a test could not be read afterwards
            is FocusTap.Refused -> { Log.i(TAG, "focus $id: refused: ${t.why}"); note(t.why); false }
            FocusTap.Already -> { Log.i(TAG, "focus $id: already focused, nothing sent"); true }
        }
        questPane.invalidate()
        return ok
    }

    /** ps3: Polestar's step moved on from quest [id] (the sync pushed it): its focus check stops retrying it */
    fun cancelFocusWant(id: Int) { quests.cancelWant(id) }

    /** where the waiting focus's give-up is shown (the room its tap came from) */
    private var focusNote: (String) -> Unit = { questPane.note(it) }

    /**
     * ONE FOCUS PAIR for quest [id] at column [slot] (q5: the no-action pick, then the act; an older pad: its focus row), in one shell
     * session through quests' TapBridge.taps, all or nothing; [what] names it in the WCHOME line. The focus check looks again
     * [HomeQuests.CHECK_MS] later.
     */
    private fun sendFocus(id: Int, slot: Int, what: String, note: (String) -> Unit): Boolean {
        val cells = QuestPad.cells(pad, QuestPad.Act.FOCUS, slot)
        Log.i(TAG, "focus $id: $what ${cells ?: "no cells"}")
        val ok = cells != null && TapBridge.taps(context, pad, cells) { why ->
            Log.i(TAG, "focus $id: tap failed: $why")
            post { quests.sendFailed(); note(why) }
        }
        if (!ok) { quests.sendFailed(); note("The game has not published its tap pad"); return false }
        postDelayed({ focusCheck() }, HomeQuests.CHECK_MS + 50L)
        return true
    }

    /**
     * THE FOCUS CHECK ([FocusCheck]; fix/focus-latency): after each quests document, each answer, each tick and [HomeQuests.CHECK_MS]
     * after each send. A focus that did not take goes again by quest id (its column afresh from the newest list); a give-up is said.
     */
    private fun focusCheck() {
        when (val c = quests.check(System.currentTimeMillis())) {
            is FocusCheck.Took -> Log.i(TAG, "focus ${c.questId}: took (the game's focus) ${c.ms} ms after the first send, send ${c.tries}")
            is FocusCheck.Retry -> {
                Log.i(TAG, "focus ${c.questId} did not take (game focus ${c.gameFocus ?: "none"}: ${c.why}); retry ${c.n} by id")
                sendFocus(c.questId, c.slot, "retry ${c.n} by id, column ${c.slot}", focusNote)
            }
            is FocusCheck.GaveUp -> {
                Log.i(TAG, "focus ${c.questId}: gave up after ${c.tries} sends (game focus ${c.gameFocus ?: "none"}; last: ${c.why})")
                focusNote("The game did not change the focus")
                questPane.invalidate()
            }
            FocusCheck.Idle -> {}
        }
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
    fun unfocusQuest(id: Int, note: (String) -> Unit): Boolean =
        questAct(id, "unfocus", QuestPad.Act.UNFOCUS, quests.tapUnfocus(id, GameInput.live(), System.currentTimeMillis()), note)

    /** one quest action from the Map room: the model's verdict [t], then the pick + act cells in one shell session, logged as [what];
     *  true = the taps went out (or nothing was needed) */
    private fun questAct(id: Int, what: String, act: QuestPad.Act, t: FocusTap, note: (String) -> Unit): Boolean {
        val sent = when (t) {
            is FocusTap.Send -> {
                val cells = QuestPad.cells(pad, act, t.slot)
                Log.i(TAG, "$what $id: send ${cells ?: "no cells"}")
                val ok = cells != null && TapBridge.taps(context, pad, cells) { why ->
                    Log.i(TAG, "$what $id: tap failed: $why")
                    post { quests.sendFailed(); note(why) }
                }
                if (!ok) { quests.sendFailed(); note("The game has not published its tap pad") }
                ok
            }
            is FocusTap.Refused -> { Log.i(TAG, "$what $id: refused: ${t.why}"); note(t.why); false }
            FocusTap.Already -> true
        }
        questPane.invalidate()
        return sent
    }

    // ================================================================ states -> places (spec parts 1, 2, 3, 6)

    private fun changed() {
        editFightCheck()
        // In fights = Top screen: a module opened in the fight closes (its every route ends here) - the takeover is back at once, so the
        // band goes straight back above the page (HomeTouch.open kept the grow) rather than landing at its rest box for up to a tick
        if (flow.full == null && !flow.takeover && TopCombatBar.live(HomeSettings.layout, padMap?.dpad?.ct)) {
            val now = System.currentTimeMillis()
            if (flow.combat(takeoverWanted(now))) grewAt = now
        }
        apply(flow.screen, animate = isShown); onStateChanged?.invoke()
    }

    /** the D-pad layout's RIGHT (the user, 2026-10-05: "I want right to open that home map module"): the map module opens as a tap on
     *  the small map would (refused in a fight's takeover, as the tap is); false = nothing changed */
    fun openMapModule(): Boolean = touchOpen(HomeModule.MAP)

    /** combat-touch: a module the player opened in this fight; the takeover never comes back over it ([HomeTouch.takeover]) */
    private var openedInFight = false

    /**
     * combat-touch (the user, 2026-11-13: "I JUST WANT TOUCHING IT OR CHAT or any of the rooms and elements TO BEHAVE THAT SAME
     * WAY"): every route that opens a Home module, in a fight too. The takeover shrinks first, B's own change ([HomeTouch.open]); the
     * guest hold / PadOwnerSync's ENTER follow from apply() and padOwnerStep() exactly as after B. False = nothing changed.
     */
    private fun touchOpen(m: HomeModule): Boolean {
        val now = System.currentTimeMillis()
        val fighting = flow.takeover || grow.fighting(now)
        // In fights = Top screen in the fight: the grow stays (the Wings stay up top); closing the module brings the takeover back
        val keepGrow = !TopCombatBar.bShrinks(HomeSettings.layout, grow.fighting(now), padMap?.dpad?.ct)
        if (!HomeTouch.open(flow, grow, m, now, keepGrow)) return false
        if (fighting && flow.full != null) openedInFight = true
        Log.i("WCLOG", "home: $m opened by touch${if (fighting) " (in a fight)" else ""}")
        changed()
        return true
    }

    /** the takeover's word for [now]: the grow, Party mode, and never over a module opened in this fight */
    private fun takeoverWanted(now: Long): Boolean {
        openedInFight = HomeTouch.keepOpened(openedInFight, flow.full, grow.fighting(now) || grow.shown(now))
        return HomeTouch.takeover(grow.shown(now), partyOnHere(), flow.full, openedInFight)
    }

    // ---------------------------------------------------------------- PARTY MODE (PARTY_BUILD.md 4.2)

    /** Party mode on and routing, while Home's rest page shows (a full module = the add-on suspends it; drawn as off) */
    private fun partyOnHere(): Boolean = party.party?.onLive == true
    /** the bottom screen's party module (m129, party_design.md C and the user's decisions 2026-11-11: one column of plates beside the
     *  Select tab, no health or mana: the user "give up on displaying HP/Mana down there") */
    // QOL > Party > Party mode (an experimental switch from 2026-11-07; on by default since 1.0.26, the user 2026-11-12)
    private val PARTY_MODULE get() = GameUi.partyModeOn()
    /** Party mode holds the D-pad here (its box moves on it): the game's D-pad layout must not also open the bar cursor (2026-11-04,
     *  the user: "pushing down on the Dpad makes the cursor also appear on the bottom bar menu") */
    fun partyHoldsDpad(): Boolean = partyOnHere() && flow.full == null && isShown && !typeWanted && !editLive()
    /** Party mode on with Home's rest page shown (no module full screen): the disc's touch = Party mode off */
    fun partyOnRest(): Boolean = partyOnHere() && flow.full == null

    /** the module's mode for [s]: the takeover = the fight with Party mode off; a fight with it on = 88 px rows, or - In fights = Top
     *  screen (the layout as the page lays it: TopCombatBar.honoured keeps TOP only while the add-on's Wings are on) - the rows filling
     *  the band's freed strip (the user's ruling C, 11-22: HomeLayoutSolver.away, PartyLayout.Mode.FIGHT_TOP); else at rest. The Fight
     *  preview lays each as its fight does (the 1519 audit: the solo preview hands the solver the takeover, so the takeover's rows) */
    private fun partyModeNow(s: HomeScreen): PartyLayout.Mode = when {
        s.takeover || previewTakeover() -> PartyLayout.Mode.FIGHT_OFF
        partyFightNow() || previewPartyFight() ->
            if (layoutNow(s).fight == HomeLayout.FightBand.TOP) PartyLayout.Mode.FIGHT_TOP else PartyLayout.Mode.FIGHT_ON
        else -> PartyLayout.Mode.REST
    }
    /** Party mode on in a fight (the add-on's fight word: never an attack armed before the pull - no takeover in Party mode) */
    private fun partyFightNow(): Boolean = partyOnHere() && grow.fighting(System.currentTimeMillis())
    private var laidPartyMode = PartyLayout.Mode.REST
    private var laidPartyN = 0
    /** m131: a pet in place of a party member at the same n changes the cells' widths */
    private var laidPartyPet = false
    private var laidPartyOn = false
    private var laidPartyModule = false
    init {
        // QOL > Party > Party mode flipped: the module comes or goes at once
        GameUi.onChange { post { if (PARTY_MODULE != laidPartyModule) partyChanged() } }
    }

    /** a party document (or a box) changed what Home draws: re-placed when the module's size changed, else redrawn */
    fun partyChanged() {
        val p = party.party
        val n = p?.view?.size ?: 0                                       // m150: the drawn entries (a dead pet entry too)
        val on = p?.onLive == true
        val now = System.currentTimeMillis()
        // Party mode on in a fight: the takeover goes (v17); off again: it comes back
        val combat = flow.combat(takeoverWanted(now))
        if (combat && flow.takeover) grewAt = now
        val placed = flow.full == null || flow.takeover
        if (shown != null && (combat || placed && (n != laidPartyN || on != laidPartyOn || (p?.viewPet == true) != laidPartyPet || partyModeNow(flow.screen) != laidPartyMode
                || PARTY_MODULE != laidPartyModule))) changed()
        partyView.invalidate()
        shown?.let { partyStripSync(it) }
        castStripFight()                                                  // cb15: Party mode on / off: the bar's Party layer and its fight rule
        onStateChanged?.invoke()                                          // the disc's red B follows
    }

    /** the strip's party layout (PARTY_BUILD.md 4.3) while Party mode is on and Home's rest page shows (no takeover then) */
    private fun partyStripSync(s: HomeScreen) {
        if (!lent(castView)) return
        castView.partyStrip = partyOnHere() && !s.takeover && s.full == null
        castView.partyFight = grow.fighting(System.currentTimeMillis())
        // the trigger plates at frame y 124 (v13), in the band's own px (the band starts at y 8 at rest)
        castView.partyPlateTop = (124f - (if (big(s)) 10f else 8f)) * (width.takeIf { it > 0 }?.let { it / 1240f } ?: 1f)
        castView.partyChanged()
    }

    /** the app's own pad reader: a D-pad press moves the box at once while Party mode is on and Home's rest page holds it */
    private var partyDpadWas = 0
    private var partySelWas = false
    /** Home's rest page shown (back from a room / the background): grouped, the Select tab's edge pulses (party_design.md E) */
    fun partyHomeOpened() { if (PARTY_MODULE) partyView.homeOpened() }
    /** m150: a short word over the party module (Smart Res refused in a fight) */
    fun partyNote(t: String) { if (PARTY_MODULE) partyView.note(t) }
    private fun partyPad(held: Int) {
        val dirs = listOf(PadInput.SLOT_DPAD_UP to PartyModel.Dir.UP, PadInput.SLOT_DPAD_DOWN to PartyModel.Dir.DOWN,
            PadInput.SLOT_DPAD_LEFT to PartyModel.Dir.LEFT, PadInput.SLOT_DPAD_RIGHT to PartyModel.Dir.RIGHT)
        var mask = 0
        for ((k, _) in dirs.withIndex()) if (PadInput.isHeld(held, dirs[k].first)) mask = mask or (1 shl k)
        val fresh = mask and partyDpadWas.inv()
        partyDpadWas = mask
        // party_design.md E: a Select press the reader sees flashes the tab's pressed look (the add-on's document turns it on / off)
        val sel = PadInput.isHeld(held, PadInput.SLOT_SELECT)
        if (sel && !partySelWas && PARTY_MODULE && flow.full == null && isShown) partyView.selectPressed()
        partySelWas = sel
        // b-class-takeover: the class wheel's D-pad never reaches the game (its guest keeps it), so the box's optimistic move would part
        // from the game's - the wheel's presses are the wheel's
        if (fresh == 0 || !partyOnHere() || flow.full != null || !isShown || typeWanted || editLive() || (lent(castView) && castView.classRadialOpen())) return
        for ((k, d) in dirs.withIndex()) if ((fresh and (1 shl k)) != 0 && party.move(d.second) != null) { partyView.invalidate(); castView.partyChanged() }
    }

    /** the module's touches: Home's host taps them (a tap = the plate's pick cell, a hold = the player menu, the tab = on / off) */
    var onPartyCell: ((Int) -> Unit)? = null
    var onPartyLong: ((Int) -> Unit)? = null
    var onPartyTag: ((Boolean) -> Unit)? = null
    /** a Home module went full screen (Home's rest page left): the host taps Party mode off (PartyModel.OFF_ON_LEAVE) */
    var onPartyLeaveRest: (() -> Unit)? = null

    /**
     * MOVABLE HOME (SPEC.txt section 6 item 1): every part's box for [s] from the layout record ([HomeLayoutSolver]); it replaces
     * placesBase / withParty / withStrip with the same arithmetic (Classic = today: HomePlacesGoldenTest). The bookkeeping of what was laid
     * (the party module's mode and rows, the bar's side and slide, the card's dock and height) stays here.
     */
    private fun places(s: HomeScreen, w: Float, h: Float): Map<Part, RectF> {
        val l = layoutNow(s)
        val full = s.full != null && !s.takeover
        var partyIn: HomeLayoutSolver.Party? = null
        if (!full) {
            laidCardH = cardHeight()
            laidOverMap = cardOverMap()
            // 2026-11-05 / m129: the party module only with Party mode's switch on and a party document
            laidPartyModule = PARTY_MODULE
            val p = party.party
            if (laidPartyModule && p != null) {
                val mode = partyModeNow(s)
                laidPartyMode = mode; laidPartyN = p.view.size; laidPartyOn = p.onLive; laidPartyPet = p.viewPet
                partyView.mode = mode
                partyIn = HomeLayoutSolver.Party(mode, p.view.size, p.viewPet)
            }
        }
        // P4: edit mode's fight preview lays the band's combat look (the real fight state stays as it is: nothing parks, nothing is sent):
        // the takeover's, or with Party mode on the Party-mode fight's (no takeover, R0) - so Top screen's preview lays the band above the
        // page as its fight does, the party list filling the strip (the 1519 integration)
        val r = HomeLayoutSolver.places(l, HomeLayoutSolver.State(w, h, UiKit.MODULE_GAP, full = s.full, takeover = s.takeover || previewTakeover(),
            card = homeCard() != null, cardH = laidCardH.coerceAtLeast(0f), auraOpen = auraPane.open != null, party = partyIn,
            partyOn = partyOnHere(), castHidden = !full && castHiddenNow(), partyFight = partyFightNow() || previewPartyFight()))
        placedAway = !full && r.away
        if (full) laidSide = CastStripModel.Side.OFF
        else {
            laidLayout = l
            laidSide = r.castSide
            laidHidden = r.castHidden && r.castSide != CastStripModel.Side.OFF
            // P2: Split = the first view on slots 1-4 under the left column, the second on 5-8 under the right (no scroll)
            laidSplit = l.cast == HomeLayout.CastDock.SPLIT
            laidTop = r.castTop
            castStrip.splitHalf = if (laidSplit) 1 else 0
            if (laidSplit) { castStrip.side = CastStripModel.Side.LEFT; castStrip2.side = CastStripModel.Side.RIGHT }
            else if (r.castSide != CastStripModel.Side.OFF) castStrip.side = r.castSide
            partyView.tabRight = r.partyTabRight
            laidChatRight = r.partyTabRight
            laidPartyInMap = l.party.column == HomeModule.MAP
        }
        val out = LinkedHashMap<Part, RectF>()
        for ((p, b) in r.boxes) PART_OF[p]?.let { out[it] = RectF(b.l, b.t, b.r, b.b) }
        return out
    }
    /** the layout record the page was last laid with (a change of it glides the page there) */
    private var laidLayout: HomeLayout? = null
    /** In fights = Top screen: the page [apply] laid last has the band above the page (the fight's takeover with the Wings on: TopSlide);
     *  [placedAway] the last [places]' answer (chatPaneBox asks places too: only apply moves [laidAway]) */
    private var laidAway = false
    private var placedAway = false
    /** the overall fraction (0..1) a Top-screen slide had reached when the next apply cancelled it, and its way; -1 = none */
    private var slideCutAt = -1f
    private var slideCutUp = false
    /** the down-slide's hold left (ms) when the next apply cut it inside it (TopSlide.holdMs: a re-lay then waits only what was left) */
    private var slideCutHold = 0L
    /** THE HOLD ON THE WALL CLOCK (the 11-22 review: folded into the ValueAnimator's duration, the system's animator duration scale cut
     *  it - 0.5x halved it, "Animations off" dropped it - while the add-on's Wings leave in real time): a held slide's animator is started
     *  by [holdRun] after the hold, posted on the main looper; [holdUntil] / [holdFrom] / [holdUp] say where a cut finds it ([holdCut]) */
    private val holdHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var holdRun: Runnable? = null
    private var holdUntil = 0L
    private var holdFrom = 0f
    private var holdUp = false
    /** the wall-clock start of the last up-slide (TopSlide.reversalHoldMs: how far in the Wings had got when the fight's end cut it); -1 = none */
    private var upSlideAt = -1L
    /** a held slide this apply cuts inside its hold: never started (a cancel would tell its listeners nothing), it is ended as a
     *  running one is - its cancel and end run (the motion's clean-up), then the hold it had left is noted for the next motion */
    private fun holdCut() {
        val r = holdRun ?: return
        holdHandler.removeCallbacks(r); holdRun = null
        val a = anim; anim = null
        a?.listeners?.toList()?.forEach { it.onAnimationCancel(a); it.onAnimationEnd(a) }
        slideCutAt = holdFrom; slideCutUp = holdUp
        slideCutHold = (holdUntil - SystemClock.uptimeMillis()).coerceAtLeast(1L)
    }
    /** P2: the last [places] laid the tap-to-cast bar split in two halves */
    private var laidSplit = false
    /** the last [places] laid the bar at the top of its column (Above chat / Above map): hidden, it slides up behind the band */
    private var laidTop = false
    /** the party list's column on the right (the party menus mirror with it: the chat's for Classic's dock) */
    private var laidChatRight = false
    /** after 1518: the party list laid in the map's column (the party menus open over that column) */
    private var laidPartyInMap = false
    private val PART_OF: Map<HomePart, Part> = HomePart.values().mapNotNull { hp -> Part.values().firstOrNull { it.name == hp.name }?.let { hp to it } }.toMap()

    /**
     * the record this page lays now: the player's active layout (the current character's), or a preview look's; its rest is the page's
     * (HomeFlow keeps it), a look's grow / card / bar override (the Settings pictures)
     */
    private fun layoutNow(s: HomeScreen? = shown): HomeLayout {
        val look = renderLook
        // P2: while edit mode runs (not parked by a fight), Home is laid with its draft: every change glides there
        var l = HomeLayoutRules.shown(look?.layout ?: editDraft() ?: HomeSettings.layout)
            // P4: the room bar's fields are the bar's, not the page's (its order glides in the Strip; the XP track's place is [trackInset])
            .copy(rooms = RoomBar.DEFAULT, xpBar = true, hidden = emptyList())
        if (s != null) l = l.copy(rest = s.rest)
        // the 1515 merge's review (P5): Top screen only while the add-on's Wings are on (`ct` 1); no key / not confirmed = Grow. The
        // Fight preview (the 1519 audit) lays what the first fight after Done lays: Top screen whenever the add-on has the flag
        if (look == null) l = if (editFightLook()) TopCombatBar.previewed(l, padMap?.dpad?.ct) else TopCombatBar.honoured(l, padMap?.dpad?.ct)
        if (look != null) {
            l = l.copy(fight = if (look.grow) HomeLayout.FightBand.GROW
                else (if (l.fight == HomeLayout.FightBand.GROW) HomeLayout.FightBand.SAME else l.fight))
            if (look.layout == null) l = l.copy(card = if (look.overMap) HomeLayout.CardDock.ON_MAP else HomeLayout.CardDock.ABOVE_CHAT)
            look.castSide?.let { l = l.copy(cast = HomeLayout.dockOf(it, l.chatSide)) }
        }
        return l
    }

    /** the tap-to-cast bar's place laid by the last [places] (a change of the choice lays the page again) */
    private var laidSide = CastStripModel.Side.OFF
    /** cb7: the last [places] laid the bar slid away (Hide in combat, a fight shown); a change lays the page again with the one motion */
    private var laidHidden = false

    /** a finger on slot [i]: the slot's cell on the "castbar" row (nothing for an empty / waiting slot) */
    private fun castStripTap(i: Int) {
        val cell = CastStripModel.tapCell(CastStripModel.Live.bar, i, castStrip.fightNow, castStrip.partyNow) ?: return   // cb14 / cb15: the layer's cell
        val sent = pad != null && TapBridge.tap(context, pad, cell.first, cell.second) { }
        Log.i(TAG, "cast bar: slot ${i + 1} -> ${cell.first} ${cell.second} ${if (sent) "tapped" else "not sent"}")
    }

    /** every `castbar` document (the host keeps [CastStripModel.Live]): the slots, and the page again when the strip comes or goes */
    fun onCastBar(b: CastStripModel.Bar) {
        for (st in strips) st.update(b)
        castStripRelay()
    }

    /** every CURRENT `castcd` (ActionBar cb1, the whole state): the slots' sweeps on the app's clock */
    fun onCastCd(m: Map<Int, PadMapModel.FaceCd>) { for (st in strips) st.onCd(m) }

    /**
     * THE BAR IN A FIGHT (Settings > Tap-to-cast bar > In combat; the user 2026-11-13: "a settings toggle to make them Hide/stay in
     * combat", Hide the default): the fight is Home's own ([fightShown]: the takeover with Party mode off, the grow's fight with it on).
     * cb7: Hide slides the bar down behind Home's bottom bar (no touches while hidden or on its way) and its column(s) take the room,
     * both back at the fight's end, on the page's one motion ([withStrip], [apply]); Stay keeps it, laid and tappable as at rest.
     */
    private fun castStripFight() {
        castStripGate()
        // cb7: hidden changed: the page again with the one motion (the bar slides down / up, its column(s) grow / shrink with it)
        val s = shown ?: return
        if (s.full != null && !s.takeover) return
        if (laidSide == CastStripModel.Side.OFF || castHiddenNow() == laidHidden) return
        apply(s, animate = isShown)
    }

    /** the bar's touch gate for Home's fight now (no re-lay: [apply] calls this before it lays the page) */
    private fun castStripGate() { for (st in strips) st.fight(castCombatNow(), fightShown(), castPartyKeep()) }
    /** cb15 (Option C, Bar in Party mode): Party mode on and its bar setting on: the bar stays in fights and its Party layer shows */
    private fun castPartyKeep(): Boolean = renderLook?.castSide == null && party.party?.onLive == true && HomeSettings.castBarPartyShow

    /** the bar's side and its fight rule now: the player's, or a preview look's (Hide with it) */
    private fun castSideNow(): CastStripModel.Side = layoutNow().castSide
    // In fights = Top screen (TopCombatBar.stripStays): the band leaves the bottom screen in the fight (Party mode's too, ruling C), the
    // bar stays whatever Show in combat says (its touches, its dock; its In-combat layer only when Show in combat armed it, else its own slots); the cast bar question's
    // picture keeps its own Hide
    private fun castCombatNow(): CastStripModel.Combat = if (renderLook?.castSide != null) CastStripModel.Combat.HIDE
        else TopCombatBar.stripCombat(layoutNow(), HomeSettings.castBarCombat)

    /** cb7: the bar is slid away now (Hide in combat and Home shows a fight) */
    private fun castHiddenNow(): Boolean = CastStripModel.hiddenIn(castCombatNow(), fightShown(), castPartyKeep(), true)

    /** the layout record changed in more than the bar since the page was laid */
    private fun layoutMoved(): Boolean {
        val was = laidLayout ?: return false
        fun core(l: HomeLayout) = l.copy(cast = HomeLayout.CastDock.OFF, castSize = HomeLayout.SlotSize.L, name = "")
        return core(was) != core(layoutNow())
    }

    private fun castStripRelay() {
        val s = shown ?: return
        if (s.full != null && !s.takeover) return
        val side = castSideNow()
        if (side == laidSide && (layoutNow().cast == HomeLayout.CastDock.SPLIT) == laidSplit && layoutNow().cast.top == laidTop) return
        // cb3: Left <-> Right slides the bar over with the page's one motion (the columns make room); coming or going is laid at once.
        // Movable Home: a new layout (a preset, a swap) moving more than the bar glides the whole page there
        // (Above <-> Under on one side glides too: the bar moves up or down its column)
        val slide = (side == CastStripModel.Side.LEFT || side == CastStripModel.Side.RIGHT) &&
            (laidSide == CastStripModel.Side.LEFT || laidSide == CastStripModel.Side.RIGHT) || layoutMoved()
        apply(s, animate = slide && isShown)
    }

    private fun apply(s: HomeScreen, animate: Boolean) {
        val before = shown
        shown = s
        val open = s.full != null && !s.takeover
        // THE MAIL CARD (HomeMailButton) belongs to the page it opened on: a module opening / closing or the fight view coming / going
        // closes it (the envelope itself stays wherever the Calendar button is)
        if (before != null && (before.full != s.full || before.takeover != s.takeover)) mailCard(false)
        // PARTY MODE (the user's ruling): a Home module going full screen leaves Home's rest page: the host turns Party mode off
        if (PartyModel.leftRest(before?.let { it.full != null && !it.takeover } ?: false, open)) onPartyLeaveRest?.invoke()
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
            if (mapFull) { auraPane.close(); homePadN = -1; setMapPage(HomeMapPages.Page.QUESTS); mapAsks = 0; mapAsk() }
            else {
                setMapPage(HomeMapPages.Page.QUESTS); mapView.worldDone()          // 2026-11-17: the sticks and the world's art let go
                mapClaim.hidden()
                if (homeGuestTap(GuestPad.cells(pad, GUEST_ID, hold = false))) Log.i(TAG, "map module: free"); mapHeld = false; mapBSaid = null; mapView.padHints = false; auraPane.leaveFull(); mapFallbackOff()
            }
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
            // (P4: edit mode's fight preview too)
            castView.castOnly = !(s.takeover || editFightLook())
            // p-swing-timer, batch 12 ("they should be seperate"): Settings > Home turns the SWING off; the cast bar stays
            castView.swingOn = renderLook?.swing ?: HomeSettings.swingTimer
            // P4: the preview's stand-in swing (no swing of the game's running); always false outside edit mode
            castView.swingPreview = editFightLook()
            // LATER item 16 (the user: "under the controller area ... the trigger modifiers ... light them up like in the cast menu when
            // they get pressed but dimmed when they arent"): Cast's trigger strip under the diamonds (cast 197ec48, DIAMONDS only)
            castView.triggerStrip = true
        }
        partyStripSync(s)
        castStripGate()
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
        val w = width.toFloat(); val h = frameH
        slideCutAt = -1f; slideCutHold = 0L
        holdCut()
        anim?.cancel()
        // a slide THIS cancel just cut short (its overall fraction and way; -1 = none; the hold it had left)
        val cutAt = slideCutAt; val cutUp = slideCutUp; val cutHold = slideCutHold; slideCutAt = -1f; slideCutHold = 0L
        if (w <= 0f || h <= 0f) { chatReturn(); requestLayout(); return }       // placed at the first layout
        val wasAway = laidAway
        val to = places(s, w, h)
        laidAway = placedAway
        val away = laidAway
        // IN FIGHTS = TOP SCREEN (TopSlide): the band leaving the page for the fight / coming back after it. Only the takeover's own change
        // (no full module before or now): a module opened from a Top fight / closed back into one keeps the module's time and curve, the
        // map's morph in step with its box (the 11-22 review)
        val wasFull = before != null && before.full != null && !before.takeover
        val slide = TopSlide.isSlide(wasAway, away) && !open && !wasFull
        // the 11-22 review: a re-lay inside the slide's 240 ms that keeps the band's way (edit mode's park, a card, the cast bar's side,
        // the XP track's inset) goes ON with the same slide from where it was cut - its time left, its curve - not a fresh MODULE_CLOSE
        // glide on Motion.curve (the columns overtaking the band again)
        val resumeAt = TopSlide.resumeAt(slide, wasAway, away, open || wasFull, cutAt, cutUp)
        val sliding = slide || resumeAt >= 0f
        val slideFrom = if (slide) 0f else resumeAt.coerceAtLeast(0f)      // the slide's fraction this motion starts at
        // the way down waits for the Wings to be mostly off the top screen (TopSlide.DOWN_HOLD_MS; the band and the Wings never both
        // show): a fresh way down from the band landed away, or what was left of that hold; an up-slide turned back by the fight's end
        // as long as the Wings, reversed from as far in as they had got, need (its wall-clock age; an early cut: at once)
        val clock = SystemClock.uptimeMillis()
        val upMs = if (cutUp && cutAt >= 0f && upSlideAt >= 0L) clock - upSlideAt else -1L
        val hold = if (sliding) TopSlide.holdMs(slide, away, TopSlide.bandOff(now[Part.BARS]?.bottom), cutUp, cutHold, resumeAt, upMs) else 0L
        if (slide) upSlideAt = if (away) clock else -1L
        // the down-slide's start (or the band on the page); held, the band stays away above the page - its cast bar given to the rooms'
        // bar - until the hold ends (below), so the player's cast never leaves the bottom screen
        if (lent(castView) && !laidAway && hold == 0L) castView.away = false
        for ((p, v) in views) if (to.containsKey(p)) {
            // a part coming back after being GONE starts from rest: no left-over slide, its own depth (the card over the map)
            if (v.visibility != View.VISIBLE && !classOpen && pauseT == 0f) { v.animate().cancel(); v.translationX = 0f; v.translationZ = restZ(p) }
            v.visibility = View.VISIBLE; v.alpha = 1f
        }
        // fix/radials-clip-castbar: an open middle wheel follows the page laid again (a fight's start / end), never over the bar
        if (s.full == null || s.takeover) wheelRefit(to)
        if (!animate || before == null) {
            if (lent(castView)) castView.away = away                           // laid at once (no motion): away now
            overlayFrom = overlayOf(s); overlayTo = overlayFrom; overlayOut = 1f; overlayIn = 1f
            flightClip(false)
            chatReturn()
            now.clear(); for ((p, r) in to) now[p] = RectF(r)
            for ((p, v) in views) if (!to.containsKey(p)) v.visibility = View.GONE
            for (st in strips) st.sliding = false
            mapScaleEnd()
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
        // the slide keeps its own pace whoever lays the page (a fight parking edit mode's draft too: editRelay's applyMs)
        val ms = if (sliding) TopSlide.restMs(away, slideFrom) else applyMs ?: when {
            combat -> if (s.takeover) Motion.COMBAT_GROW else Motion.COMBAT_SHRINK
            open && !(before.full != null && !before.takeover) -> Motion.MODULE_OPEN
            !open && before.full != null && !before.takeover -> Motion.MODULE_CLOSE
            else -> Motion.MODULE_CLOSE                  // a re-placing (the card's height): the shorter of the two
        }
        val fadeMs = if (combat) 60f else FADE_MS
        val from = HashMap<Part, RectF>()
        for ((p, r) in now) from[p] = RectF(r)
        // THE HAND-BACK (ruling D): a way down from the band wholly above the page starts it from the page's top edge (moved there unseen),
        // so its curve's first, small frames are on the page (TopSlide.edgeShift)
        if (sliding && !away) from[Part.BARS]?.let { b -> b.offset(0f, TopSlide.edgeShift(b.bottom)) }
        // ruling C: the party module's rows follow its box while the slide grows / shrinks it (no jump at either end)
        if (sliding) partyView.stretch = true
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
        // cb7: the bar on its way (slid down / up with the page's motion): no touch until it lands
        castStrip.sliding = from[Part.STRIP]?.let { f -> to[Part.STRIP]?.let { t -> t.top != f.top } } == true
        castStrip2.sliding = from[Part.CAST2]?.let { f -> to[Part.CAST2]?.let { t -> t.top != f.top } } == true
        // P2 (SPEC section 10): an edit glide that changes the map's size or side scales the map's picture along the motion (laid once at
        // its new size, the viewport re-laid at the end), so the map is not drawn again at every frame's size
        mapScaleEnd()
        if (!morphing && !combat && editGlides() && mapView.parent === mapSlot) {
            val mf = from[Part.MAP]; val mt = to[Part.MAP]
            // laid once at the larger of the two sizes, cropped to the gliding box (scaled down only when both sides shrink): no map
            // drawing per frame, no stretch; the viewport re-laid where it lands
            if (mf != null && mt != null && (mf.width() != mt.width() || mf.height() != mt.height()))
                mapScaleTo = RectF(mt.left, mt.top, mt.left + max(mf.width(), mt.width()), mt.top + max(mf.height(), mt.height()))
        }
        questPane.cardHidden = card != null
        // the zone band and the clock fade with the page (Map's recording: they vanished at frame 0 on open, popped back at a close's end)
        overlayFrom = overlayOf(before); overlayTo = overlayOf(s); overlayOut = 1f; overlayIn = 0f
        // Map's call order: morph(0) BEFORE start (else one frame draws the new mode un-glided), morph(k) per frame, morph(1) at the end
        if (morphing) { flightClip(true); mapView.morph(0f, mFrom!!, mTo!!, isMap, card) }
        // THE iPHONE-STYLE EDIT MODE: under edit mode every box glides on a spring (HomeEditMotion.glide: its centre a little past, its
        // size never past its target), starting at the speed it had (a quick D-pad run never stops and starts again). Outside edit mode
        // (Classic, every fight, a module opening) the one curve as before
        val springs = !morphing && !combat && !sliding && !gliding && editGlides() && motion.on && motion.scale > 0f
        if (!springs) partVel.clear()
        val v0 = if (springs) from.keys.associateWith { p -> partVel[p]?.copyOf() ?: FloatArray(4) } else emptyMap()
        val dur = if (springs) HomeEditMotion.glideMs(ms) else ms          // (a hold waits before start(), on the wall clock: below)
        var cancelled = false
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            // the slide: linear time, every part on the way's curve (TopSlide.partK: ease-in up / ease-out down; the columns ride the band's foot)
            interpolator = if (springs || sliding) android.view.animation.LinearInterpolator() else Motion.curve()
            addUpdateListener { a ->
                val k = a.animatedValue as Float
                if (morphing) mapView.morph(k, mFrom!!, mTo!!, isMap, card)
                if (springs) springFrame(a.animatedFraction, dur, ms, from, to, v0)
                else if (sliding) for (p in from.keys) {
                    val f = from[p]!!
                    val t = to[p] ?: f
                    // (a hold ran before the animator started: every part stood where it was; now the slide's own time)
                    // (the band on its own curve; every other part on the columns' - up: braking into the top gap, ruling D)
                    val q = TopSlide.resumeK(a.animatedFraction, slideFrom, away, band = p == Part.BARS)
                    if (hold > 0L && !away && q > 0f && lent(castView)) castView.away = false    // the hold over: the band draws again
                    now[p] = RectF(lerp(f.left, t.left, q), lerp(f.top, t.top, q), lerp(f.right, t.right, q), lerp(f.bottom, t.bottom, q))
                }
                else for (p in from.keys) {
                    val f = from[p]!!
                    val t = to[p] ?: f
                    now[p] = RectF(lerp(f.left, t.left, k), lerp(f.top, t.top, k), lerp(f.right, t.right, k), lerp(f.bottom, t.bottom, k))
                }
                val t = a.animatedFraction * dur                      // the time in, linear (the slide's own, past its hold)
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
                override fun onAnimationCancel(a: Animator) {
                    cancelled = true
                    // a slide cut short: the next apply goes on with it if it keeps the way ([TopSlide.resumeAt])
                    if (sliding) {
                        slideCutAt = TopSlide.overall((a as ValueAnimator).animatedFraction, slideFrom); slideCutUp = away
                        slideCutHold = 0L                            // (past its hold: a cut inside it is [holdCut]'s)
                    }
                }
                override fun onAnimationEnd(a: Animator) {
                    // a spring glide cut short keeps its parts' speeds for the next one; landed, they are still
                    if (!cancelled) partVel.clear()
                    if (sliding) partyView.stretch = false                 // (landed or cut: the next motion sets it again)
                    // the motion landed with the band laid above the page: it draws nothing more (CastView.away). Any motion that lands
                    // there, not only a whole up-slide (the 11-22 review: a re-lay inside the slide's 240 ms cancelled it, and the glide
                    // that took over never set it - the band sat off the page with its cast bar claimed, the bottom screen had none)
                    if (!cancelled && lent(castView)) castView.away = away
                    // fires on cancel too: the morph always ends (Map's safety net would after 700 ms anyway)
                    if (morphing) { mapView.morph(1f, mFrom!!, mTo!!, isMap, card); flightClip(false) }
                    if (gliding) { chatGlideBox.setEmpty(); chatSlot.clipToOutline = false; chatSlot.invalidateOutline() }
                    chatReturn()
                    if (morphing && !isMap && card != null) questPane.plateToRest()   // b121: landed full, eases back to see-through
                    questPane.cardHidden = false
                    overlayFrom = overlayTo; overlayOut = 1f; overlayIn = 1f
                    for (p in coming) views[p]?.alpha = 1f
                    for (p in gone) { views[p]?.visibility = View.GONE; views[p]?.alpha = 1f; now.remove(p) }
                    for (st in strips) st.sliding = false
                    mapScaleEnd()
                    settled = true
                    requestLayout()                                  // one full pass where it lands
                    invalidate()
                }
            })
            if (hold > 0L) {
                // the hold on the WALL clock (the Wings run in real time; an animator's own delay would be scaled by the system's
                // animator duration scale): every part stands where it is until the slide starts
                val va = this
                val r = object : Runnable { override fun run() { if (holdRun === this) { holdRun = null; va.start() } } }
                holdRun = r; holdUntil = clock + hold; holdFrom = slideFrom; holdUp = away
                holdHandler.postDelayed(r, hold)
            } else start()
        }
        invalidate()
    }

    /**
     * One frame of an edit-mode spring glide at [fraction] of [dur] (the old glide [ms] sets the spring's pace): each part's centre and
     * size from [from] (at its speed [v0]) to [to]; its speed kept ([partVel]); landed exactly at 1
     */
    private fun springFrame(fraction: Float, dur: Long, ms: Long, from: Map<Part, RectF>, to: Map<Part, RectF>,
                            v0: Map<Part, FloatArray>) {
        val tSec = fraction * dur / 1000f
        for ((p, f) in from) {
            val tt = to[p] ?: f
            val r = now[p] ?: RectF().also { now[p] = it }
            if (fraction >= 1f) { r.set(tt); partVel.remove(p); continue }
            if (ridesIn(p, from, to)) continue                          // a rider inside its host: after the host (below)
            val t = tSec
            val v = v0[p] ?: ZERO4
            val vel = partVel.getOrPut(p) { FloatArray(4) }
            HomeEditMotion.glide(f.centerX(), tt.centerX(), v[0], ms, t, false, glideOut); val cx = glideOut[0]; vel[0] = glideOut[1]
            HomeEditMotion.glide(f.centerY(), tt.centerY(), v[1], ms, t, false, glideOut); val cy = glideOut[0]; vel[1] = glideOut[1]
            HomeEditMotion.glide(f.width(), tt.width(), v[2], ms, t, true, glideOut); val w = glideOut[0]; vel[2] = glideOut[1]
            HomeEditMotion.glide(f.height(), tt.height(), v[3], ms, t, true, glideOut); val h = glideOut[0]; vel[3] = glideOut[1]
            r.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        }
        if (fraction >= 1f) return
        // THE RIDERS (the card on the map, the buffs): their edges' offsets from the host glide (never past their ends: inside the host
        // at both ends = inside it on every frame), laid on the host's gliding box
        for ((p, f) in from) {
            if (!ridesIn(p, from, to)) continue
            val hp = RIDER_HOST[p] ?: continue
            val hf = from.getValue(hp); val ht = to.getValue(hp); val hn = now[hp] ?: continue
            val tt = to[p] ?: f
            val r = now[p] ?: RectF().also { now[p] = it }
            val t = tSec
            HomeEditMotion.glide(f.left - hf.left, tt.left - ht.left, 0f, ms, t, true, glideOut); val l = glideOut[0]
            HomeEditMotion.glide(f.top - hf.top, tt.top - ht.top, 0f, ms, t, true, glideOut); val tp = glideOut[0]
            HomeEditMotion.glide(f.right - hf.right, tt.right - ht.right, 0f, ms, t, true, glideOut); val rr = glideOut[0]
            HomeEditMotion.glide(f.bottom - hf.bottom, tt.bottom - ht.bottom, 0f, ms, t, true, glideOut); val b = glideOut[0]
            r.set(hn.left + l, hn.top + tp, hn.right + rr, hn.bottom + b)
            partVel.remove(p)
        }
    }
    /** [p] is a rider ([RIDER_HOST]) inside its host at both ends of this glide: it glides as an offset from the host */
    private fun ridesIn(p: Part, from: Map<Part, RectF>, to: Map<Part, RectF>): Boolean {
        val hp = RIDER_HOST[p] ?: return false
        val hf = from[hp] ?: return false; val ht = to[hp] ?: return false
        val f = from[p] ?: return false; val t = to[p] ?: f
        return within(f, hf) && within(t, ht)
    }
    private fun within(a: RectF, b: RectF) = a.left >= b.left - 0.5f && a.top >= b.top - 0.5f && a.right <= b.right + 0.5f && a.bottom <= b.bottom + 0.5f
    /** the riders that lie inside their host (the card on the map, the buffs = the map's box) */
    private val RIDER_HOST: Map<Part, Part> = mapOf(Part.QUESTS to Part.MAP, Part.AURAS to Part.MAP)

    /** r-combat-anim-off: the fight's BIGGER band ("Combat layout: Grow", the default); "Same size" keeps the rest's place and size */
    private fun big(s: HomeScreen) = HomeLayoutSolver.big(layoutNow(s), HomeLayoutSolver.State(takeover = s.takeover || editFightLook(), partyOn = partyOnHere()))

    /**
     * P4 (the user's ruling 2026-11-22): edit mode's fight PREVIEW is on (the ribbon's Fight toggle; live, not parked): the band, its
     * pictures and its swing line take the combat look the record's In fights gives (TopCombatBar.honoured: what a real fight would show).
     * Display only: [fightShown], the bar's fight gate, Party mode and the add-on hear nothing of it
     */
    private fun editFightLook(): Boolean = edit?.let { !it.parked && it.fightPreview } == true
    /** the preview's fight as the page lays it: the takeover with Party mode off; with it on the Party-mode fight (no takeover: R0 -
     *  Top screen's band away and the party list filling the strip, the user's ruling C) */
    private fun previewTakeover(): Boolean = editFightLook() && !partyOnHere()
    private fun previewPartyFight(): Boolean = editFightLook() && partyOnHere()
    /** P4 fix round: the preview's look was given to the D-pad and the band (the toggle's Fx.Preview) and not taken back yet */
    private var previewLaid = false
    /**
     * P4 fix round (the review: Done / Discard / a park / another room cleared the model's preview with no Fx.Preview, so the D-pad kept its
     * In-combat pictures - and the bar's prediction read them as the D-pad's spells out of a fight - and a Discard that changed nothing kept
     * the grown band and its looping stand-in swing on the Classic page): the preview ended by any other way than its toggle - the
     * D-pad's own pictures again here; true = it was showing, so the caller lays the band again FORCED (the preview is no field of the
     * record: an unforced relay finds the record unchanged and keeps the fight look)
     */
    private fun previewEnded(): Boolean {
        if (!previewLaid || editFightLook()) return false
        previewLaid = false
        applyDpad()
        Log.i(TAG, "home edit: fight preview ended (not by its toggle)")
        return true
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
        val w = width.toFloat(); val h = frameH
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

    /**
     * MOVABLE HOME P4, THE XP TRACK'S PLACE (SPEC section 8: "Hide gives Home 34 px (Home only; every other room keeps 935 by ruling)").
     * The host makes Home's view the content's height, which reaches under the room bar's XP track, and keeps [bottomInset] = the track's
     * height while the track shows: Home's FRAME ([frameH]) ends at the track's top, as always. The record's XP bar hidden (and Home on
     * screen, not editing: the ribbon needs the track) the inset goes to 0 and every part glides into the 34 px on the one motion
     * ([trackInset]), as the track slides away under the rooms' row (Strip.trackShown). Off the host (the tests, PreviewStage) it is 0.
     */
    private var bottomInset = 0
    /** Home's frame now: the view's height less the track's place */
    private val frameH: Float get() = (height - bottomInset).toFloat()
    private val frameHi: Int get() = height - bottomInset
    private fun frameOf(hSpec: Int): Int = (MeasureSpec.getSize(hSpec) - bottomInset).coerceAtLeast(0)
    /** the inset now (the host's checks) */
    val trackInsetNow: Int get() = bottomInset
    /** the track's place is Home's ([px] 0) or the track's ([px] = Strip.TRACKS): every part glides there ([animate]) on [ms] */
    fun trackInset(px: Int, animate: Boolean, ms: Long = Motion.MODULE_OPEN) {
        if (px == bottomInset) return
        bottomInset = px
        val s = shown ?: flow.screen
        if (width <= 0 || height <= 0) { requestLayout(); return }
        applyMs = ms
        try { apply(s, animate = animate && live) } finally { applyMs = null }
        requestLayout()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec))
        if (typeWanted) typeTool.measure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(wSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(frameOf(hSpec), MeasureSpec.EXACTLY))
        val cw = if (classRect.isEmpty) MeasureSpec.getSize(wSpec) else classRect.width()
        val ch = if (classRect.isEmpty) frameOf(hSpec) else classRect.height()
        classLayer.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        wheelLayer?.measure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(wSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(frameOf(hSpec), MeasureSpec.EXACTLY))
        for ((p, v) in views) {
            if (v.visibility == View.GONE) continue
            val r = (if (p == Part.MAP) mapScaleTo else null) ?: now[p] ?: continue
            v.measure(MeasureSpec.makeMeasureSpec(max(1, r.width().toInt()), MeasureSpec.EXACTLY),
                      MeasureSpec.makeMeasureSpec(max(1, r.height().toInt()), MeasureSpec.EXACTLY))
        }
        editView.measure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(wSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(frameOf(hSpec), MeasureSpec.EXACTLY))
    }

    /**
     * ONE FRAME OF THE MOTION (r-combat-anim-off: "it shouldn't steal frames from the game"): each part that moved is measured (only when
     * its size changed) and placed right here. It was requestLayout() per frame, which sent a measure / layout pass through the WHOLE
     * window (the strip, every room's view) about 15 times in a 240 ms switch; the landing frame still does one full pass.
     */
    private fun placeFrame() {
        for ((p, v) in views) {
            if (v.visibility == View.GONE) continue
            val f = (if (p == Part.MAP) mapScaleTo else null) ?: now[p] ?: continue
            val x0 = f.left.toInt(); val y0 = f.top.toInt()
            val x1 = max(x0 + 1, f.right.toInt()); val y1 = max(y0 + 1, f.bottom.toInt())
            val resized = v.width != x1 - x0 || v.height != y1 - y0
            if (!resized && v.left == x0 && v.top == y0 && !v.isLayoutRequested) continue
            if (resized || v.isLayoutRequested)
                v.measure(MeasureSpec.makeMeasureSpec(x1 - x0, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(y1 - y0, MeasureSpec.EXACTLY))
            v.layout(x0, y0, x1, y1)
        }
        topBarClip()
        editDecor()
    }

    /** a top bar hidden or on its way (Hide in combat, F2): clipped at the band's foot, so it slides up BEHIND the band whatever the
     *  band's paint (the views' order draws it over the band); laid, lifted or dragged in edit mode it is never clipped */
    private fun topBarClip() {
        val v = castStrip                                                   // a top bar is never Split: only the one bar
        val bars = now[Part.BARS]
        v.clipBounds = if (bars == null || !laidTop || !(laidHidden || v.sliding) || editLive()) null
            else android.graphics.Rect(0, (bars.bottom + UiKit.MODULE_GAP - v.top).toInt().coerceIn(0, max(0, v.height)), max(1, v.width), max(1, v.height))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (typeWanted) typeTool.layout(0, 0, r - l, b - t - bottomInset)                  // f-trackpad-keyboard: the whole page
        // f-class-radial: its slot (the wheel between the columns), or the whole page for the hint
        if (classRect.isEmpty) classLayer.layout(0, 0, r - l, b - t - bottomInset) else classLayer.layout(classRect.left, classRect.top, classRect.right, classRect.bottom)
        wheelLayer?.layout(0, 0, r - l, b - t - bottomInset)                              // f-radial-behind: the whole page, under the columns
        // fix/menus-fit: over the party list's column as laid (any share, either side: the chat's for Classic's dock; after 1518 the
        // map's when it sits there), under the band as it is now
        val partyCol = now[if (laidPartyInMap) Part.MAP else Part.CHAT]?.let { c -> RectF(c).also { u -> now[Part.PARTY]?.let { u.union(it) } } }
        PartyMenuView.place(partyCol, barsBottom(), frameH, laidChatRight, (r - l).toFloat()).let { m ->
            partyMenu.layout(m.left.toInt(), m.top.toInt(), m.right.toInt(), m.bottom.toInt())
            targetMenu.layout(m.left.toInt(), m.top.toInt(), m.right.toInt(), m.bottom.toInt())
        }
        for ((p, v) in views) {
            if (v.visibility == View.GONE) continue
            val f = (if (p == Part.MAP) mapScaleTo else null) ?: now[p] ?: continue
            val x0 = f.left.toInt(); val y0 = f.top.toInt()
            v.layout(x0, y0, max(x0 + 1, f.right.toInt()), max(y0 + 1, f.bottom.toInt()))
        }
        topBarClip()
        editView.layout(0, 0, r - l, b - t - bottomInset)
        editDecor()
        if (motion.on) editKick()                                         // Home laid again (back on screen): the edit motion goes on
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
    /** f-pause-bars: the controller's alpha under the open pause menu (the user's pick B: 40 %, "so the menu reads as on top") */
    var pauseBarsDim = 0.4f
    /** f-pause-bars: the bottom of the controller (the bars) in Home's px, 0 = none laid out */
    fun barsBottom(): Float = (now[Part.BARS]?.bottom ?: 0f).coerceAtLeast(0f)     // (Top screen's fight: the band above the page)
    private var pauseMap = floatArrayOf(0f, 0f, 1f)

    /**
     * THE PANELS MAKE SPACE (UI Art's PAUSE_RADIAL_SPEC): the controller band goes up, the chat column left, the map (with the quest
     * card over it and the auras) right, each on UiKit.pauseOut's curve; [t] = 0 puts them all back. Only Home's own parts move (the
     * bar is the pause view's); a move of Home's own state never runs meanwhile (the wheel holds the controller and covers the touch).
     */
    /** [sidePx] > 0 (1.0.19, the LB picture; the user: "I donw want the sides to slide ALL of the way out, just enough to make space for
     *  the selections"): the side parts move only that far, eased, a little dimmed; the controller bars as the pause has them */
    private var pauseSide = 0f
    private var pauseUp = false
    /** [barsUp] (feat/pause-power: the PAUSE menu's; the user, 2026-11-18: "I want the pause radial to also push the top controller bar
     *  up out of the way so that it can be a bit larger"): the controller slides up off the screen on the kit's PAUSE_TOP curve, the
     *  reverse bringing it back down; false = the target radial's / the LB picture's rule below */
    fun pauseSlide(t: Float, sidePx: Float = 0f, barsUp: Boolean = false) {
        if (t == pauseT && sidePx == pauseSide && barsUp == pauseUp) return
        pauseT = t; pauseSide = sidePx; pauseUp = barsUp
        val screen = RectF(0f, 0f, width.toFloat(), frameH)
        // f-pause-bars (the user: "I dont think I want the controller top part to slide off screen"): over Home the controller stays,
        // only stepping back to [pauseBarsDim] (the title row lies over its lower half); elsewhere its old slide. The pause menu itself
        // pushes it up since feat/pause-power ([barsUp]); the target radial keeps the f-pause-bars rule
        pauseTop = if (UiKit.pauseBarsBottom > 0f && !barsUp) floatArrayOf(0f, 0f, 1f - (1f - pauseBarsDim) * (t / UiKit.PAUSE_OPEN_MS).coerceIn(0f, 1f))
            else UiKit.pauseOut(UiKit.PAUSE_TOP, t, screen)
        val left = UiKit.pauseOut(UiKit.PAUSE_LEFT, t, screen)
        pauseMap = UiKit.pauseOut(UiKit.PAUSE_MAP, t, screen)
        val half = width / 2f
        // fix/menus-fit: the LB picture's place on the page as laid: each side moves only as far as it needs to clear it
        val lb = if (sidePx > 0f) lbPlace() else null
        for ((p, v) in views) {
            // MOVABLE HOME (SPEC section 6 item 2): the side is the part's HALF, not its name: the left column slides left whatever it
            // holds (the map after a swap). A part across the whole page (a full-screen module) keeps its own side. fix/menus-fit: a
            // 1/3 or 2/3 column straddles the middle but is NOT across: it goes by its centre (the swapped Big map's map flew right,
            // across the open wheel and under the LB picture)
            val r = now[p]
            val across = r == null || HomeSlide.fullWidth(r.left, r.right, width.toFloat(), UiKit.MODULE_GAP)
            val o = when {
                p == Part.BARS -> pauseTop
                p == Part.STRIP -> if (laidSide == CastStripModel.Side.RIGHT) pauseMap else left       // cb3: under the minimap, it goes with it
                !across -> if (r!!.centerX() < half) left else pauseMap
                p == Part.QUESTS -> if (cardOverMap()) pauseMap else left
                p == Part.MAP || p == Part.AURAS -> pauseMap
                else -> left                                                                         // the chat, the party module
            }
            // FULLY OFF, whatever the part's size (the user 2026-10-09: the FULL-SCREEN map "only slide[s] ... half way out"): the kit's
            // distance is for the compact parts; a bigger one goes as far as it needs to leave the screen, on the same curve
            val endX = UiKit.pauseOut(if (p == Part.BARS) UiKit.PAUSE_TOP else if (o === left) UiKit.PAUSE_LEFT else UiKit.PAUSE_MAP,
                UiKit.PAUSE_OPEN_MS, screen)
            val kx = when {
                endX[0] > 0f -> ((width - v.left) / endX[0]).coerceAtLeast(1f)
                // (fix/menus-fit: a 1/3 or 2/3 column's laid right edge is fractional: the whole of it goes)
                endX[0] < 0f -> (maxOf(v.right.toFloat(), r?.right ?: 0f) / -endX[0]).coerceAtLeast(1f)
                else -> 1f
            }
            val ky = if (endX[1] < 0f) (v.bottom / -endX[1]).coerceAtLeast(1f) else 1f
            v.translationX = o[0] * kx; v.translationY = o[1] * ky
            // the state's own fades leave every shown part at 1 when settled: the pause's alpha is the only one meanwhile
            if (v.visibility == View.VISIBLE) v.alpha = o[2]
            // the LB picture (the user: "don't make the top controller part shift away"): the controller stays as it is
            if (sidePx > 0f && p == Part.BARS) { v.translationX = 0f; v.translationY = 0f; if (v.visibility == View.VISIBLE) v.alpha = 1f }
            if (sidePx > 0f && p != Part.BARS) {
                val e = (t / UiKit.PAUSE_OPEN_MS).coerceIn(0f, 1f)
                val ee = 1f - (1f - e) * (1f - e)
                // fix/castbar-slides (the user on 1.0.31: "it should only slide the direction if its on that half. If its set to both it
                // can stay in place"): the cast bar by its side (Left with the chat, Right with the minimap), Both's bar stays.
                // fix/menus-fit: every part by its column (its half), each side by its own distance (LbFit: Classic's 340 both)
                val pl = (lb?.pushLeft ?: sidePx) * ee; val pr = (lb?.pushRight ?: sidePx) * ee
                val bar = p == Part.STRIP || p == Part.CAST2
                v.translationX = if (r != null && (!across || bar)) HomeSlide.dx(halfOf(p, r, width.toFloat()), pl, pr)
                    else if (o === left) -pl else pr
                v.translationY = 0f
                if (v.visibility == View.VISIBLE) v.alpha = 1f - 0.35f * ee
            }
        }
        invalidate()
        // 1.0.20: the class wheel that waited for the LB picture's sides opens now they are back
        if (classPending && sidePx > 0f && t <= 0f) { classPending = false; classLayerShow(true) }
    }

    // ================================================================ MOVABLE HOME, PHASE 2: EDIT MODE (SPEC.txt sections 3, 4, 7, 8, 10)

    /**
     * EDIT MODE (the user's rulings D4 / D6, 2026-11-20): Home stays live and full size; [HomeEditModel] keeps the draft, the cursor, the
     * lift, the menus; [HomeEditView] draws the chrome over every part and takes every touch; the host draws the ribbon in the room bar's
     * XP track and the disc's gold tick ([onEditChrome]). Three ways in: a 450 ms hold on the chat's or the map's body ([holdWatch], the
     * ring from 150 ms; hold, drag and release is one swap gesture), Settings > Bottom screen's first tile and Y on the Home disc (the
     * host's [editOpen]). Never in a fight: a request then flashes the widget's edge red; a fight mid-edit parks it (the chrome fades
     * out, Home plays the record's fight) and brings it back after. 1518: every settled change is written as it is made ([editPersist]);
     * Done ends, Discard (Done's long press, a held B) writes the opening record back; nothing is sent to the add-on while it is live.
     */
    private var edit: HomeEditModel? = null
    /** edit mode's timers (the hold, the hover, the flash) on the main looper: they run whether or not Home is attached yet */
    private val editUi = android.os.Handler(android.os.Looper.getMainLooper())
    /** the session whose chrome is fading out (MODULE_CLOSE) after it ended */
    private var chromeModel: HomeEditModel? = null
    /** edit mode runs on Home, not parked by a fight or a room (the host's gates: the class wheel, the LB picture, Start, the disc) */
    val editing: Boolean get() = editLive()
    /** the 1515 merge: edit mode is there, open OR parked (a fight, a room): the host's key question waits for it to end */
    val editPresent: Boolean get() = edit != null
    /** the host's: true while the key question is up (KeyPick.editMayOpen) - edit mode does not open then */
    var editBlocked: (() -> Boolean)? = null
    /** edit mode holds Home now: its chrome takes the touches, the guest "home" its presses; parked (a fight, a room) Home is Home */
    private fun editLive(): Boolean = edit?.parked == false
    /** the off-device tests: the session */
    fun editModel(): HomeEditModel? = edit
    /** a room over Home parked the draft: it comes back when Home does */
    private var editAway = false
    private var chromeA = 0f
    private var chromeAnim: ValueAnimator? = null
    /** the lift (0 .. 1: the shadow, the depth) of [liftStop], on [motion]'s lift spring (1.04 with its overshoot, the drop's plop) */
    private val liftK: Float get() = liftStop?.let { motion.liftK(liftKey, motionNow()) } ?: 0f
    private var liftStop: EditStop? = null
    /** [liftStop]'s motion key, taken at the lift (its stop's EditStop.key) */
    private var liftKey = 0
    private var editFlash: Pair<EditStop, Long>? = null

    /**
     * THE iPHONE-STYLE EDIT MODE (the user, 2026-11-21: "resemble more of like an iPhone app edit mode with apps kind of animating
     * zoomed smaller a bit and being more animated generally"): every widget shrinks a little and wiggles, a lifted one grows and stops,
     * a drop plops, the cursor flies, the menus pop ([HomeEditMotion], pure). Drawn only: the model, the solver and [now] never see it.
     * ONE Choreographer ticker drives it while anything moves ([editKick]); it stops when idle, in a fight, with Home away and with the
     * system's animations off (the static shrink). Classic outside edit mode never reaches it.
     */
    private val motion = HomeEditMotion()
    /** the off-device shots: a pinned clock / animator scale (null = SystemClock / the system's) */
    var editMotionNow: (() -> Long)? = null
    var editMotionScale: (() -> Float)? = null
    private fun motionNow(): Long = editMotionNow?.invoke() ?: SystemClock.uptimeMillis()
    private fun motionScale(): Float = editMotionScale?.invoke() ?: if (!live) 0f else systemAnimScale()
    private fun systemAnimScale(): Float = try {
        if (android.os.Build.VERSION.SDK_INT >= 33) ValueAnimator.getDurationScale()
        else android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    } catch (t: Throwable) { 1f }
    /** the off-device tests: the motion (read only) */
    fun editMotion(): HomeEditMotion = motion
    private val pose = HomeEditMotion.Pose()
    private val poseBox = RectF()
    private val poseOut = FloatArray(4)
    private val viewM = android.graphics.Matrix()
    private val paintM = android.graphics.Matrix()
    private var tickerPosted = false
    private val editTicker = android.view.Choreographer.FrameCallback { tickerPosted = false; editTick() }
    /** what the motion saw last (a change pops a menu, flies the cursor) */
    private var seenCursor: EditStop? = null
    private var seenMenu: EditStop? = null
    private var seenSheet: HomeEditModel.Sheet? = null
    /** each part's speed at the last glide frame (px/s: centre x, y, width, height): a new glide starts at it (no stop, no stutter) */
    private val partVel = HashMap<Part, FloatArray>()
    private val glideOut = FloatArray(2)
    private var editPadN = Int.MIN_VALUE
    /** the next apply's glide (null = apply's own rule) */
    private var applyMs: Long? = null
    /** the map laid once at its glide's destination size and scaled along the motion (null = laid at now[MAP]) */
    private var mapScaleTo: RectF? = null
    private var mapScaleTx = 0f
    private var mapScaleTy = 0f
    /** a transform of edit mode's is on a view (one reset pass after edit mode ends) */
    private var decorDirty = false

    /** the host's hooks: the ribbon + the disc's tick (the chrome, null = gone), the bar's slots (the Controller room's Tap-to-cast
     *  tab), the chat's filters (the Chat room's checklist) */
    var onEditChrome: ((HomeEditView?) -> Unit)? = null
    var onEditSlots: (() -> Unit)? = null
    var onEditFilters: (() -> Unit)? = null
    /** a behaviour row's press with the host's env (BottomTiles.press); null = Home presses it itself (the off-device renders) */
    var onEditPress: ((String, Int) -> Unit)? = null
    /** edit mode opened / closed (the host re-reads its guards) */
    var onEditChanged: ((Boolean) -> Unit)? = null
    /** 1518: edit mode is LIVE (open, not parked) or not any more (Done, Discard, a fight, another room): the host tells the add-on the
     *  record's D-pad sides and fight bar only while it is not live (the autosave writes every change; the pictures preview it meanwhile) */
    var onEditLive: ((Boolean) -> Unit)? = null

    private fun editDraft(): HomeLayout? = edit?.takeIf { !it.parked || editBackDue }?.draft
    /** fix round 3: Home is back over a room-parked session and its resume is posted ([borrow]): the page is laid with the draft already */
    private var editBackDue = false
    /** the band's rest Home is laid with: the draft's while edit mode is live (Y on the band, the menu's Rest row preview live), else the
     *  record's (a fight's park; Discard puts the opening record back) */
    private fun restWanted(): HomeRest = editDraft()?.rest ?: HomeSettings.rest
    /** HomeFlow's rest to [restWanted]; true = it changed (the next lay reads it: [layoutNow], CastView's look, [solveFor]) */
    private fun editRestSync(): Boolean = flow.setRest(restWanted())

    /**
     * P5's ONE EXCEPTION (SPEC section 1): while edit mode runs, the band's D-pad pictures preview the DRAFT (after a swap Left = the map,
     * Right = the chat), the add-on's own `d` kept aside ([roomBarReal]) and put back when edit mode ends or parks. The host hands every
     * padmap's `d` through [roomBarFor].
     */
    private var roomBarReal: Map<Int, String>? = null
    private var roomBarKept = false
    fun roomBarFor(dirs: Map<Int, String>?): Map<Int, String>? { roomBarReal = dirs; roomBarKept = true; return editRoomBar(dirs) }
    private fun editRoomBar(dirs: Map<Int, String>?): Map<Int, String>? {
        val draft = editDraft()
        val want = draft?.let { HomeLayoutSolver.mirrorBit(it) }
            ?: roomBarHold?.takeIf { it == HomeLayoutSolver.mirrorBit(HomeSettings.layout) }
            ?: run { roomBarHold = null; return dirs }
        val have = dirs?.let { ToolbarMirror.bitOf(it) } ?: return dirs
        if (have == want) { if (draft == null) roomBarHold = null; return dirs }
        return dirs + mapOf(1 to dirs.getValue(3), 3 to dirs.getValue(1))
    }
    /**
     * fix round 3 (SPEC section 10: "after Save (new d arrives): 120 ms cross-fade if they differ"): Done keeps the saved record's sides
     * (its mirror bit) on the pictures until the add-on's `d` says them - the swap the player previewed never slides back and forth
     * while ToolbarMirror's taps are on their way. The host's [roomBarHoldEnd] (the resend gave up, no key to send for) puts `d` back with
     * the cross-fade. Null = `d` as it is
     */
    private var roomBarHold: Int? = null
    fun roomBarHoldEnd() {
        if (roomBarHold == null) return
        roomBarHold = null
        if (!lent(castView) || editDraft() != null) return
        val want = editRoomBar(roomBarReal)
        if (castView.roomBar != want) { castView.roomBarFade(want); castView.invalidate() }
    }
    private fun editRoomBarSync() {
        if (!lent(castView)) return
        if (!roomBarKept) { roomBarReal = castView.roomBar; roomBarKept = true }
        val want = editRoomBar(roomBarReal)
        if (castView.roomBar != want) { castView.roomBar = want; castView.invalidate() }
    }
    /** the off-device renders: Home is "on screen" (never attached there), so edit mode's glides run on the test clock */
    var forceLive = false
    private val live: Boolean get() = isShown || forceLive
    /** the off-device tests (and the chrome's own touch route): one call of the model, its effects carried out */
    fun editAct(f: (HomeEditModel) -> List<HomeEditModel.Fx>) { edit?.let { editRun(f(it)) } }
    private fun editGlides(): Boolean = edit != null || chromeModel != null

    private val editHost: HomeEditView.Host by lazy { object : HomeEditView.Host {
        override val model: HomeEditModel? get() = edit ?: chromeModel
        override val liveModel: HomeEditModel? get() = edit
        override val chromeAlpha: Float get() = chromeA
        override fun parts(): Map<HomePart, RectF> {
            partsOut.clear()
            for ((p, r) in now) if (views[p]?.visibility == View.VISIBLE) {
                val hp = HOME_PART_OF[p.ordinal]
                val o = partsPool[p.ordinal]
                o.set(r); partsOut[hp] = o
            }
            return partsOut
        }
        override fun liftK(): Float = liftK
        override fun pose(p: HomePart, out: android.graphics.Matrix): Boolean = partMatrix(Part.valueOf(p.name), out)
        override fun cursorOffset(out: FloatArray) = motion.cursorOffset(motionNow(), out)
        override fun cursorTurn(): Float = motion.cursorTurn(motionNow())
        override fun popK(kind: Int): Float = motion.popK(kind, motionNow())
        override fun holdK(): Float? = editHoldK()
        override fun holdDone(down: Boolean): Boolean = if (down) { editHoldDown(); false } else editHoldUp()
        override fun roomPose(r: Room, out: FloatArray): Boolean {
            if (!motion.on) return false
            motion.pose(HomeEditMotion.roomKey(r.ordinal), motionNow(), ROOM_HALF_DIAG, pose)
            out[0] = pose.scale; out[1] = pose.rot; out[2] = pose.dx; out[3] = pose.dy
            return !pose.isIdentity
        }
        override fun plusPose(out: FloatArray): Boolean {
            if (!motion.on) return false
            motion.pose(HomeEditMotion.PLUS_ROOM, motionNow(), ROOM_HALF_DIAG, pose)
            out[0] = pose.scale; out[1] = pose.rot; out[2] = pose.dx; out[3] = pose.dy
            return !pose.isIdentity
        }
        override fun poke() = editKick()
        override fun solve(l: HomeLayout): Map<HomePart, HomeLayoutSolver.Box> = solveFor(l)
        override val geo: HomeEditModel.Geo get() = editGeo
        override fun run(fx: List<HomeEditModel.Fx>) = editRun(fx)
        override fun menuRows(s: EditStop): List<MenuRow> = editMenuRows(s)
        override val stripH: Int get() = Strip.HEIGHT
        override val flash: Pair<EditStop, Long>? get() = editFlash
        override fun bar(): Strip? = roomBar
        override fun band(): HomeEditView.BandGeo? = editBand()
        override fun extraPose(key: String, out: android.graphics.Matrix): Boolean {
            val r = bandCells[key] ?: return false
            if (!extraPoseAt(key)) return false
            val p = extraPoseBuf
            out.reset()
            out.postScale(p.scale, p.scale, r.centerX(), r.centerY()); out.postRotate(p.rot, r.centerX(), r.centerY()); out.postTranslate(p.dx, p.dy)
            return true
        }
    } }

    /** the extras' cells (and "+") as the last [editBand] laid them, by key: their poses' pivots */
    private val bandCells = HashMap<String, RectF>()
    private val bandSlotBox = RectF()

    /**
     * THE TOP BAR'S PIECES in Home's px (P1 - P4): the band's buttons (CastView's slot table), the clock chip and its extras as the
     * clock block draws them (the edit row: [editRowKeys]), and where BandEdit puts the pencils, the Look chip and the fight pencil.
     * Null = no band on Home now (a module full screen, the controller layout not come yet, edit mode parked)
     */
    private fun editBand(): HomeEditView.BandGeo? {
        val m = edit?.takeIf { !it.parked } ?: return null
        val s = shown ?: return null
        if (!lent(castView) || castView.parent !== barsSlot || castView.layout == CastView.Layout.FULL || castView.width <= 0) return null
        if (s.full != null && !s.takeover) return null
        val b = now[Part.BARS] ?: return null
        val slots = ArrayList<HomeLayoutSolver.Box>(8)
        val pad = RectF(); val face = RectF()
        val keys = ArrayList<RectF>(8)
        for (pos in 0 until 8) {
            castView.slotRect(pos, bandSlotBox); bandSlotBox.offset(b.left, b.top)
            slots += HomeLayoutSolver.Box(bandSlotBox.left, bandSlotBox.top, bandSlotBox.right, bandSlotBox.bottom)
            keys += RectF(bandSlotBox)
            if (pos == 0 || pos == 4) (if (pos == 0) pad else face).set(bandSlotBox) else (if (pos < 4) pad else face).union(bandSlotBox)
        }
        val diamonds = castView.layout == CastView.Layout.DIAMONDS
        val grown = big(s)
        // the clock block (and its extras) only where Home draws it: the small diamonds at rest
        val clockShown = overlayOf(s).clock && barsSeen && clockText.isNotEmpty() && batteryPct >= 0
        bandCells.clear()
        var chip: RectF? = null
        val extras = ArrayList<Pair<String, RectF>>()
        var plus: RectF? = null
        if (clockShown) {
            // the block as the clock draw lays it now (the fight preview: a fight's own row, no edit row)
            val ed = editClock()
            val keys = if (ed) editRowKeys() else emptyList()
            val items = if (ed) editBandItems(keys) else bandItems()
            val spot = castView.clockSpot()?.also { sp -> for (i in 0 until sp.size - 1) sp[i] += if (i % 2 == 0) b.left else b.top }
            val gold = if (ed) editChipGold() else chipGold()
            val (cb, gb, cells) = UiKit.clockBlockBoxes(b.centerX(), b.top + ClockBand.CHIP_TOP, clockText, batteryPct, gold, spot, items,
                infoG(android.os.SystemClock.elapsedRealtime()))
            chip = cb
            val byKey = HashMap<String, RectF>()
            gb?.let { byKey["gold"] = it }
            for ((i, k) in keys.withIndex()) cells.getOrNull(i)?.let { byKey[k] = it }
            bandCells.putAll(byKey)
            if (!m.fightPreview) {
                for (k in HomeEditModel.EXTRA_KEYS) byKey[k]?.let { extras += k to RectF(it) }
                plus = byKey[EDIT_PLUS]?.let { RectF(it) }
            }
        }
        val pieces = BandEdit.place(HomeLayoutSolver.Box(b.left, b.top, b.right, b.bottom), slots,
            chip?.let { HomeLayoutSolver.Box(it.left, it.top, it.right, it.bottom) }, diamonds, grown, m.fightPreview,
            away = b.bottom <= 0f)                                        // Top screen's preview: the band above the page
        fun rf(x: HomeLayoutSolver.Box) = RectF(x.l, x.t, x.r, x.b)
        return HomeEditView.BandGeo(RectF(b), pad, face, rf(pieces.padPen), rf(pieces.facePen), pieces.look?.let { rf(it) },
            pieces.fightPen?.let { rf(it) }, extras, plus, diamonds, chip, keys)
    }

    /** P2: the extras now for edit mode's model (Settings' clock rows: on, hidden) - fed before every input reaches it */
    private fun editBandFeed(m: HomeEditModel? = edit) {
        m ?: return
        m.bandIs(HomeEditModel.EXTRA_KEYS.filter { BottomTiles.clockOn(it) }, HomeEditModel.EXTRA_KEYS.filter { !BottomTiles.clockOn(it) })
    }
    private val editView: HomeEditView by lazy { HomeEditView(context, editHost) }
    /** the host's Strip draws the ribbon through this */
    val editChrome: HomeEditView get() = editView
    // edit mode's chrome over every part (GONE until edit mode opens)
    init {
        addView(editView); editView.visibility = View.GONE; editView.translationZ = EDIT_Z
        if (!PreviewStage.building) liveDraft = null                   // a new live Home has no edit mode (an Activity made again)
    }

    private val editGeo = object : HomeEditModel.Geo {
        override fun columns(l: HomeLayout) = HomeLayoutSolver.columns(l, width.toFloat(), UiKit.MODULE_GAP)
        override fun valid(l: HomeLayout): Boolean {
            val st = solveState(l)
            return HomeLayoutRules.problems(l, st, HomeLayoutSolver.places(HomeLayoutRules.shown(l), st)).isEmpty()
        }
        override val w: Float get() = width.toFloat()
        override val colTop: Float get() = barsBottom() + UiKit.MODULE_GAP
        override val colBottom: Float get() = frameH - UiKit.MODULE_GAP
        override fun barSlots(): List<Float> = roomBar?.slotCentres() ?: emptyList()
    }

    /** the page's state now, as [places] hands it to the solver (for a drop well and a drop's check) */
    private fun solveState(l: HomeLayout): HomeLayoutSolver.State {
        val p = party.party
        val partyIn = if (PARTY_MODULE && p != null) HomeLayoutSolver.Party(partyModeNow(flow.screen), p.view.size, p.viewPet) else null
        return HomeLayoutSolver.State(width.toFloat(), frameH, UiKit.MODULE_GAP, takeover = flow.takeover,
            card = homeCard() != null, cardH = laidCardH.coerceAtLeast(0f), auraOpen = auraPane.open != null, party = partyIn,
            partyOn = partyOnHere(), castHidden = castHiddenNow(), partyFight = partyFightNow())
    }
    private fun solveFor(l: HomeLayout): Map<HomePart, HomeLayoutSolver.Box> =
        HomeLayoutSolver.places(HomeLayoutRules.shown(l.copy(rest = flow.rest)), solveState(l)).boxes

    private val partsOut = LinkedHashMap<HomePart, RectF>()
    private val partsPool = Array(Part.values().size) { RectF() }
    private val HOME_PART_OF: Array<HomePart> by lazy { Part.values().map { HomePart.valueOf(it.name) }.toTypedArray() }

    /** the menus' behaviour values now (HomeSettings / Settings, the toolbar's word, this character's hold to talk) */
    private fun editValues(): HomeWidgetMenus.Values {
        val d = padMap?.dpad
        val who = GameUi.character
        return HomeWidgetMenus.Values(
            quickInput = Settings.chatDefaultInput == "quick",
            talk = if (who != null && d?.want == true && d.colOn != null) HomeSettings.holdTalk(who) else null,
            talkSendNow = who != null && HomeSettings.talkSendNow(who),
            folded = HomeSettings.questMin, zoomOut = Settings.minimapYd, zoomIn = Settings.minimapIndoorYd,
            castShowInFight = CastStripModel.showOf(HomeSettings.castBarCombat), castParty = HomeSettings.castBarPartyShow,
            toolbarOn = d?.want == true, toolbarAvail = d?.colOn != null, rest = HomeSettings.rest, rankBadges = HomeSettings.rankBadges,
            fightBand = HomeSettings.fightBand, swing = HomeSettings.swingTimer, ct = d?.ct)
    }
    private fun editMenuRows(s: EditStop): List<MenuRow> {
        val m = edit ?: chromeModel ?: return emptyList()
        return HomeWidgetMenus.rows(s, m.draft, editValues())
    }

    /**
     * EDIT MODE OPENS on Home's rest page with the cursor on [start] ([lift]: the held widget, lifted). Refused in a fight (the widget's
     * edge red for FLASH_MS); a full-screen module closes first. Returns whether it opened.
     */
    fun editOpen(start: EditStop = EditStop.CHAT, lift: Boolean = false): Boolean {
        if (edit != null) { edit?.let { if (!it.parked) editRun(it.startOn(start, lift)) }; return true }
        if (fightShown()) { editRefuse(start); return false }
        // the 1515 merge: never while the sticky realm's key question is up (shown or waiting for the controller): one owner at a time
        if (editBlocked?.invoke() == true) { Log.i(TAG, "home edit: refused (the key question is up)"); editRefuse(start); return false }
        if (flow.full != null && !flow.takeover && flow.close()) changed()
        mailCard(false); auraPane.close(); closeTypeTool()
        if (classOpen) classLayerShow(false)
        val saved = HomeSettings.layout
        chromeModel = null
        val m = HomeEditModel(saved, start)
        editBandFeed(m)
        edit = m
        editOwner = HomeLayoutStore.owner()                               // the record this draft was read under (it is saved only there)
        editPersisted = saved; editLate = null
        editAway = false
        editView.visibility = View.VISIBLE
        chromeFade(1f, HomeEditModel.CHROME_IN)
        editGuest(true)
        onEditChrome?.invoke(editView)
        Log.i(TAG, "home edit: open on ${start.name.lowercase()}${if (lift) " (lifted)" else ""}, ${HomeLayoutJson.json(saved)}")
        editMotionStart(start)
        editRun(m.startOn(start, lift))
        liveDraft = m.draft
        onEditChanged?.invoke(true)
        onEditLive?.invoke(true)
        onStateChanged?.invoke()
        return true
    }

    /**
     * 1518, THE AUTOSAVE: the draft as last written ([editPersist]: the base of the next write, so only the fields a change changed are
     * laid on the record the store holds - a change made meanwhile is kept)
     */
    private var editPersisted: HomeLayout? = null

    /**
     * a settled draft change is written now (nothing lifted, no finger down: a lifted widget's steps and a finger's hover lays are
     * written at the drop), through the store's per-character rules: the record edit mode opened on only (another character's: nothing
     * written - edit mode ends then, [editOwnerCheck]), the session's own fields laid on the record as it is now, a pending name's edit
     * held until its key comes. Nothing is sent to the add-on while edit mode is live ([onEditLive]). [now] = whatever its state (edit
     * mode parks or ends: the draft as it stands)
     */
    private fun editPersist(m: HomeEditModel, now: Boolean = false) {
        if (!now && (m.parked || !m.settled)) return
        val prev = editPersisted ?: return
        if (m.draft == prev) return
        // 1518 (F1-a): the pending name's key came while a widget was lifted (its rebase waited for the drop): the session takes it
        // BEFORE its first write under it, while the store still holds the character's own record as the key brought it
        val late = editAdopt(m)
        val o = editOwner ?: return
        val rec = HomeLayoutStore.editSave(o, prev, m.draft) ?: run {
            Log.i(TAG, "home edit: NOT saved (the record it was read under is not the active one now)"); return }
        HomeSettings.saveLayout(rec)
        editPersisted = m.draft
        Log.i(TAG, "home edit: saved ${HomeLayoutJson.json(rec)}${if (rec != m.draft) " (laid on the record as it is now)" else ""}")
        // the session is the real record's now: the draft is the record as written, Discard's the character's own (taken before the write)
        if (late != null) {
            val now = HomeLayoutStore.active()
            m.rebase(now, late); editPersisted = now
            Log.i(TAG, "home edit: on the late key's record ${HomeLayoutJson.json(now)}")
            if (!m.parked) editRelay(Motion.MODULE_OPEN)
        }
    }

    /**
     * the store's record changed while edit mode waited (a fight, another room, the pending name's key that arrived: its real record
     * with the held edit laid on): the draft follows it, and the record Discard puts back takes that outside change too (only this
     * session's own changes are undone). A late key's owner becomes the session's (its writes are its own record's from here)
     */
    private fun editRebase(m: HomeEditModel) {
        val prev = editPersisted ?: return
        val late = editAdopt(m)
        val now = HomeLayoutStore.active()
        if (late == null && now == prev) return
        m.rebase(now, late ?: HomeLayoutStore.layOn(m.saved, prev, now))
        editPersisted = now
        Log.i(TAG, "home edit: rebased on the record as it is now ${HomeLayoutJson.json(now)}")
    }

    /**
     * 1518: the pending name's key came (the late key, [HomeLayoutStore.EditSave.LAY_ON]): the session's owner is the character's key from
     * here, and the record Discard puts back is the character's REAL record with only the held changes of before the session on it
     * ([HomeLayoutStore.lateAdopt]; a change made under the key since it came laid on too) - never the pending default's values over the
     * fields the session touched. Run before ANY write or Discard under the new owner (the rebase, the drop's save, a Discard that came
     * first): returns that record (the caller rebases the draft on the record as it is then), null = no late key to take
     */
    private fun editAdopt(m: HomeEditModel): HomeLayout? {
        val o = editOwner ?: return null
        if (HomeLayoutStore.editSaveFor(o) != HomeLayoutStore.EditSave.LAY_ON) return null
        val prev = editPersisted ?: return null
        val back = HomeLayoutStore.lateAdopt(o, editLate, m.saved, prev)
        m.savedIs(back)
        editOwner = HomeLayoutStore.owner(); editLate = null
        return back
    }
    /** 1518 (F1-b): the late key as it came, kept by the session from the owner change (so nothing after - a reset in the Settings room
     *  while parked - can lose it before the session takes it) */
    private var editLate: HomeLayoutStore.Late? = null

    /**
     * EDIT MODE ENDS: Done (every change is written already; the DS toolbar's switch sent now) or Discard (the record it opened on
     * written back over the session's own changes; Home glides back to it); the chrome out (200 ms). [write] false = end only, nothing
     * written (Home is leaving for a Layouts reset, or another character's record is active now)
     */
    private fun editEnd(discard: Boolean, write: Boolean = true) {
        val m = edit ?: return
        // 1518: every end (Done, Discard - a finger may still rest on Done past its hold -, a cancel) lets the ribbon's finger go, so
        // the next session's first ribbon tap is never swallowed by a stale down or a fired hold
        hold.cancel(); editView.ribbonLetGo(); hold.fired = false
        if (write && !discard) editPersist(m, now = true)
        // 1518: a late key's Discard (the key came while lifted / parked): the character's real record back
        if (write && discard) editAdopt(m)?.let { back -> val now = HomeLayoutStore.active(); m.rebase(now, back); editPersisted = now }
        // Discard: the opening record back, laid as the inverse of the session's net change over the record the store holds now (a
        // change made meanwhile kept); only to the record it was read under (another character's is never written)
        val back = if (write && discard) editPersisted?.let { prev -> editOwner?.let { o -> HomeLayoutStore.editSave(o, prev, m.saved) } } else null
        if (back != null) HomeSettings.saveLayout(back)
        val done = write && !discard
        Log.i(TAG, "home edit: ${when { done -> "done"; back != null -> "discarded, back to ${HomeLayoutJson.json(back)}"; discard -> "discard NOT written (another record is active)"; else -> "ended" }}")
        edit = null
        editBackDue = false
        editPersisted = null; editLate = null
        roomBarHold = if (write) HomeLayoutSolver.mirrorBit(HomeSettings.layout) else null   // fix round 3: the record's sides until `d` says them
        editOwner = null
        liveDraft = null
        editRestSync()                                                    // the record's rest
        editRoomBarSync()                                                 // the add-on's own `d` again (the record's mirror follows it)
        editBarSync(Motion.MODULE_CLOSE)                                  // P4: the room bar's order (Discard glides it back)
        chromeModel = m
        editAway = false
        if (!m.parked) editGuest(false)                                   // parked (a fight, a room): the guest was freed then
        motion.leave(motionNow()); editKick()                             // every widget grows back to 1, the wiggle out, the ribbon down
        chromeFade(0f, Motion.MODULE_CLOSE) { chromeModel = null; editView.visibility = View.GONE; onEditChrome?.invoke(null) }
        // every part to the record's rects (Discard: its glide back already runs; Done: kept where the draft put them, the chrome's settle);
        // P4 fix round: a preview that was showing - the D-pad's own pictures again, and the band laid again even for a Discard that
        // changed nothing (its rest look, no stand-in swing)
        val preview = previewEnded()
        if (discard && !preview) editRelay(Motion.MODULE_CLOSE) else editRelay(Motion.MODULE_CLOSE, force = true)
        onEditChanged?.invoke(false)
        onEditLive?.invoke(false)
        onStateChanged?.invoke()
    }

    /** the host's: Home is leaving for a Layouts reset (open or parked): edit mode ends, nothing written (the reset decides the record) */
    fun editCancel() { if (edit != null) editEnd(discard = false, write = false) }
    /** the record edit mode opened on ([HomeLayoutStore.owner]; a late key's once it came) */
    private var editOwner: HomeLayoutStore.Owner? = null
    /** the active record's owner is another now (another character, the key let go): edit mode - open or parked - ends, nothing more
     *  written (what it saved stays that character's); the pending name's key that arrived keeps it (its real record, with the held
     *  edit laid on, is the session's from here: [editRebase]). True = it ended */
    private fun editOwnerCheck(why: String): Boolean {
        val m = edit ?: return false
        val o = editOwner ?: return false
        when (HomeLayoutStore.editSaveFor(o)) {
            HomeLayoutStore.EditSave.SAME -> return false
            HomeLayoutStore.EditSave.LAY_ON -> {
                if (editLate == null) editLate = HomeLayoutStore.lateFor(o)  // 1518 (F1-b): the key as it came, whatever the state
                // live and settled: at once (lifted / a finger down: at the drop, editPersist; parked: at the resume)
                if (!m.parked && m.settled) { editRebase(m); editRelay(Motion.MODULE_OPEN); editBarSync(Motion.MODULE_OPEN); editView.invalidate() }
                return false
            }
            HomeLayoutStore.EditSave.NONE -> {}
        }
        Log.i(TAG, "home edit: ended ($why: the draft's record is not the active one)")
        editEnd(discard = false, write = false)
        return true
    }
    /** the disc's gold tick: Done */
    fun editDone() { edit?.let { editRun(it.done()) } }

    // ---------------------------------------------------------------- Done's long press / a held B = Discard (1518)

    /** the hold on Done (a finger) or on B (the direct feed): Discard when it lasts [HomeEditModel.DISCARD_HOLD_MS] */
    private inner class Hold : Runnable {
        /** since when (uptime ms), -1 = none */
        var at = -1L
        /** it ran out this hold: Discard ran, the release does nothing */
        var fired = false
        fun start() { cancel(); at = android.os.SystemClock.uptimeMillis(); fired = false; editUi.postDelayed(this, HomeEditModel.DISCARD_HOLD_MS); editKick(); editRibbonRedraw?.invoke() }
        fun cancel() { editUi.removeCallbacks(this); at = -1L }
        override fun run() {
            at = -1L; fired = true
            val m = edit?.takeIf { !it.parked } ?: return
            Log.i(TAG, "home edit: Done held: discard")
            editRun(m.discard())
        }
    }
    private val hold = Hold()
    /** the hold's progress now (0 .. 1), null = none - and none for its first [HomeEditModel.RING_FROM_MS] (the review: a normal B or
     *  tap on Done never flashes "Discard"; the ring then fills over the rest of [HomeEditModel.DISCARD_HOLD_MS]) */
    fun editHoldK(): Float? {
        val at = hold.at.takeIf { it >= 0 } ?: return null
        val t = android.os.SystemClock.uptimeMillis() - at
        if (t < HomeEditModel.RING_FROM_MS) return null
        return ((t - HomeEditModel.RING_FROM_MS).toFloat() / (HomeEditModel.DISCARD_HOLD_MS - HomeEditModel.RING_FROM_MS)).coerceIn(0f, 1f)
    }
    private fun editHoldDown() { if (edit?.parked == false) hold.start() }
    /** true = the hold had fired (Discard ran): the release does nothing */
    private fun editHoldUp(): Boolean { val f = hold.fired; hold.cancel(); hold.fired = false; editRibbonRedraw?.invoke(); return f }

    /**
     * B on the controller's DIRECT feed while edit mode is live ([down] its press, then its release): held [HomeEditModel.DISCARD_HOLD_MS]
     * = Discard; a shorter press is B (on its release): one step back, then Done. The relayed guest's B (release only, no hold to see)
     * stays [editKey]'s plain B
     */
    fun editB(down: Boolean) {
        if (edit?.parked != false) return
        if (down) { editHoldDown(); return }
        if (!editHoldUp()) editKey("PAD2")
    }

    /** the guest "home" while edit mode holds the controller (its presses: [editPad]) */
    private fun editGuest(hold: Boolean) {
        partyView.invalidate()                                            // the party's own row box: hidden while edit mode holds Home
        if (homeGuestTap(GuestPad.cells(pad, GUEST_ID, hold = hold))) Log.i(TAG, "home edit: ${if (hold) "hold" else "free"}")
        if (hold) homeGuestTap(GuestPad.bnext(pad, home = false))
    }

    /** one press of the guest "home" while editing (A = PAD1 .. the shoulders, Select, Start through the host) */
    private fun editPad(msg: JSONObject) {
        val m = edit ?: return
        if (msg.has("lost")) { Log.i(TAG, "home edit: the controller let go (${msg.optString("lost")}); touch goes on"); return }
        val n = msg.optInt("n", Int.MIN_VALUE)
        if (n != Int.MIN_VALUE && n == editPadN) return
        editPadN = n
        editKey(msg.optString("k"))
    }

    /** the cursor on a widget with no box now (the card / the bar Off, Party mode's Hide): to "+" or the chat ([HomeEditModel.cursorCheck]) */
    private fun editCursorCheck(m: HomeEditModel) {
        if (m.cursor !in HomeEditModel.GONE_CHECKED) return
        val was = m.lifted
        if (m.cursorCheck(editView.stops())) {
            Log.i(TAG, "home edit: the cursor's widget is gone: on ${m.cursor.name.lowercase()}")
            if (was != null && m.lifted == null) editLift(null)            // 1518: its lift ended too: no stale lifted pose
            editView.invalidate()
        }
    }

    /** one key of edit mode's (the guest's, Start from the host's reader, the tests) */
    fun editKey(k: String) {
        val m = edit ?: return
        editBandFeed(m)
        if (settled) editCursorCheck(m)
        editRun(m.key(k, editView.stops()) { s -> editMenuRows(s) })
        editView.invalidate()
    }

    /** carries out the model's effects */
    private fun editRun(fx: List<HomeEditModel.Fx>) {
        val m = edit
        for (f in fx) when (f) {
            is HomeEditModel.Fx.Relay -> { liveDraft = m?.draft; editRestSync(); editRelay(f.ms); editRoomBarSync() }
            HomeEditModel.Fx.Done -> { editEnd(discard = false); return }
            HomeEditModel.Fx.Discard -> { editEnd(discard = true); return }
            is HomeEditModel.Fx.Press -> editPress(f.key, f.k)
            // the bar's slots / the chat's filters (another room): the draft is saved first; a session that changed nothing ends, one
            // that did WAITS (the room's giveBack parks it, editAwayPark) so Discard can still put it back, and edit mode is back where
            // it was with Home
            HomeEditModel.Fx.EditSlots -> { m?.let { editPersist(it) }; if (m?.changed != true) editEnd(discard = false); onEditSlots?.invoke(); return }
            HomeEditModel.Fx.Filters -> { m?.let { editPersist(it) }; if (m?.changed != true) editEnd(discard = false); onEditFilters?.invoke(); return }
            is HomeEditModel.Fx.Refuse -> editRefuse(f.stop)
            HomeEditModel.Fx.Lift -> editLift(m?.lifted)
            // P4: the fight preview on / off: the page laid again with the band's combat (or rest) look - nothing sent, no fight state
            is HomeEditModel.Fx.Preview -> {
                Log.i(TAG, "home edit: fight preview ${if (f.on) "on" else "off"}")
                previewLaid = editFightLook()
                applyDpad()
                editRelay(if (f.on) Motion.COMBAT_GROW else Motion.COMBAT_SHRINK, force = true)
            }
        }
        m?.let { m2 -> if (m2.drag != null) { editUi.removeCallbacks(dragTick); editUi.postDelayed(dragTick, HomeEditModel.HOVER_MS + 4L) } }
        m?.let { editPersist(it) }                                        // 1518: a settled change is saved as it is made
        m?.let { editBandFeed(it); if (it.settled) editCursorCheck(it) } // P2: Settings' extras as they are now; a piece gone: the cursor on
        editBarSync(fx.lastOrNull { it is HomeEditModel.Fx.Relay }?.let { (it as HomeEditModel.Fx.Relay).ms } ?: Motion.MODULE_OPEN)
        editNotice()
        editDecor()
        editView.invalidate()
        invalidate()
    }

    // ---------------------------------------------------------------- the motion's hooks (HomeEditMotion)

    /** edit mode opens or comes back ([from]: the widget it opened on, the others ripple out from it; null = all at once) */
    private fun editMotionStart(from: EditStop?) {
        val t = motionNow()
        motion.scale = motionScale()
        motion.enter(t, from?.let { rippleFrom(it) }); cardOwnTo(0f, t, 0L)
        val m = edit
        seenCursor = m?.cursor; seenMenu = m?.menu; seenSheet = m?.sheet
        editKick()
    }

    /** each key's start (ms) by its distance from [s]'s widget: the open ripples out from the one held */
    private fun rippleFrom(s: EditStop): LongArray? {
        val c = stopBox(s) ?: return null
        val cx = c.centerX(); val cy = c.centerY()
        val pts = ArrayList<Triple<Int, Float, Float>>()
        for (k in listOf(EditStop.CHAT, EditStop.MAP, EditStop.QUEST, EditStop.CAST, EditStop.PARTY))
            stopBox(k)?.let { pts += Triple(k.key, it.centerX(), it.centerY()) }
        val bar = roomBar
        val xs = bar?.slotCentres().orEmpty()
        val rooms = RoomBar.shown(edit?.draft ?: HomeSettings.layout)
        for ((i, x) in xs.withIndex()) rooms.getOrNull(i)?.let { pts += Triple(HomeEditMotion.roomKey(it.ordinal), x, frameH + Strip.BAR / 2f) }
        bar?.plusBox()?.let { pts += Triple(HomeEditMotion.PLUS_ROOM, it.centerX(), frameH + Strip.BAR / 2f) }
        val far = pts.maxOfOrNull { kotlin.math.hypot(it.second - cx, it.third - cy) } ?: return null
        val out = LongArray(HomeEditMotion.KEYS)
        for ((k, x, y) in pts) out[k] = HomeEditMotion.ripple(kotlin.math.hypot(x - cx, y - cy), far)
        return out
    }
    /** [s]'s widget box now (logical: the model's) */
    private fun stopBox(s: EditStop): RectF? = when (s) {
        EditStop.CHAT -> now[Part.CHAT]; EditStop.MAP -> now[Part.MAP]; EditStop.QUEST -> now[Part.QUESTS]
        EditStop.CAST -> now[Part.STRIP] ?: now[Part.CAST2]
        EditStop.PARTY -> now[Part.PARTY]
        EditStop.DIVIDER -> editView.dividerBox()
        else -> null
    }
    /** the part whose pose [s]'s chrome is drawn through (HomeEditView.poseOf's) */
    private fun stopPart(s: EditStop): Part? = when (s) {
        EditStop.CHAT -> Part.CHAT; EditStop.MAP -> Part.MAP; EditStop.QUEST -> Part.QUESTS
        EditStop.CAST -> Part.STRIP; EditStop.PARTY -> Part.PARTY
        else -> null
    }
    /** [s]'s whole pose now (HomeEditView draws its chrome through it: the scale, the turn, the move, the finger's carry) into [out];
     *  its scale is the return (1 = none) */
    private fun stopMatrix(s: EditStop?, out: android.graphics.Matrix): Float {
        out.reset()
        stopTurn = 0f
        val p = s?.let { stopPart(it) } ?: return 1f
        if (!partMatrix(p, out)) return 1f
        stopTurn = pose.rot
        return pose.scale
    }
    /** the last [stopMatrix]'s turn (deg; 0 = none) */
    private var stopTurn = 0f
    private val seatA = android.graphics.Matrix()
    private val seatB = android.graphics.Matrix()
    private val seatInv = android.graphics.Matrix()
    private val seatPt = FloatArray(2)

    /** the model moved on: a new cursor flies there (and the X chip pops), a menu / the Add tray pops open, the wiggle calms */
    private fun editNotice() {
        val m = edit?.takeIf { !it.parked } ?: return
        val t = motionNow()
        if (m.cursor != seenCursor) {
            val old = seenCursor
            seenCursor = m.cursor
            val wb = editView.widgetBoxes()
            val a = old?.let { wb[it] ?: stopBox(it) }; val b = wb[m.cursor] ?: stopBox(m.cursor)
            if (a != null && b != null) {
                // the gold box leaves the box it was DRAWN at: the old box (its flight's offset kept) through the OLD widget's whole pose
                // (its wiggle's turn too: up to 8 px at a big module's corner), its centre taken back through the NEW widget's, its size
                // by the two scales (the box is drawn through the new pose from this frame on: no jump on its first frame)
                motion.cursorOffset(t, poseOut)
                // the old box's drawn angle (its widget's turn and the gold box's own turn still in flight) over the new widget's: the gold
                // box turns by it on its first frame and springs to the new widget's angle (the two wiggle on their own phases)
                val turnA = motion.cursorTurn(t)
                val sa = stopMatrix(old, seatA); val ra = stopTurn
                val sb = stopMatrix(m.cursor, seatB); val rb = stopTurn
                val hw = (a.width() + poseOut[2] - poseOut[0]) / 2f * sa / sb; val hh = (a.height() + poseOut[3] - poseOut[1]) / 2f * sa / sb
                seatPt[0] = (a.left + poseOut[0] + a.right + poseOut[2]) / 2f; seatPt[1] = (a.top + poseOut[1] + a.bottom + poseOut[3]) / 2f
                seatA.mapPoints(seatPt)
                if (seatB.invert(seatInv)) seatInv.mapPoints(seatPt)
                motion.cursorFrom(seatPt[0] - hw - b.left, seatPt[1] - hh - b.top, seatPt[0] + hw - b.right, seatPt[1] + hh - b.bottom, t,
                    ra + turnA - rb)
            }
            if (m.cursor.removable) motion.pop(HomeEditMotion.CHIP, t)
        }
        if (m.menu != null && m.menu != seenMenu) motion.pop(HomeEditMotion.MENU, t)
        seenMenu = m.menu
        if (m.sheet != null && m.sheet != seenSheet) motion.pop(HomeEditMotion.SHEET, t)
        seenSheet = m.sheet
        motion.modal(m.menu != null || m.sheet != null, t)
        editKick()
    }

    /** the lift ([s] picked up) or the drop (null: the widget that was lifted plops into its dock, from the finger's offset) */
    private fun editLift(s: EditStop?) {
        val t = motionNow()
        if (s != null) {
            val k = s.key
            // 1518: the drop's Lift and the new one come in one run (the model's state is the new lift's by then): once is enough
            if (liftStop == s && liftKey == k && motion.lifted == k) { editKick(); editDecor(); return }
            // 1518: another widget still in its lifted pose (a lift that replaced it): it plops into its dock first
            liftStop?.let { was -> if (motion.lifted == liftKey && liftKey != k) {
                motion.drop(liftKey, 0f, 0f, t); if (was == EditStop.QUEST) cardOwnTo(0f, t, CARD_RIDE_BACK) } }
            liftStop = s; liftKey = k
            motion.lift(liftKey, t); if (s == EditStop.QUEST) cardOwnTo(1f, t, Motion.WIGGLE_IN)
        }
        else liftStop?.let { was ->
            val ox = if (edit?.drag == null) dragOffsetNow[0] else 0f; val oy = if (edit?.drag == null) dragOffsetNow[1] else 0f
            dragOffsetNow.fill(0f)
            motion.drop(liftKey, ox, oy, t)
            // dropped (on the map): it rides the map's pose again over its plop
            if (was == EditStop.QUEST) cardOwnTo(0f, t, CARD_RIDE_BACK)
        }
        editKick()
        editDecor()
    }

    /** the ticker's next frame (one post at a time; only while the motion is on): the display's vsync on screen; off a window (the
     *  off-device renders) there is none to keep time with, so a 16 ms post */
    private fun editKick() {
        if (tickerPosted || !motion.on) return
        tickerPosted = true
        if (isAttachedToWindow) android.view.Choreographer.getInstance().postFrameCallback(editTicker)
        else editUi.postDelayed(editTickOff, FRAME_MS)
    }
    private val editTickOff = Runnable { tickerPosted = false; editTick() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE && motion.on) editKick()          // Home's window back: the edit motion goes on
    }
    /**
     * ONE FRAME OF THE EDIT MOTION: the views' properties and the chrome (nothing is laid or measured here). A fight stills it at once;
     * Home away stops it (an input or Home again starts it); it posts the next frame only while something moves.
     */
    private fun editTick() {
        if (!motion.on) return
        val t = motionNow()
        if (fightShown()) { motion.still(); editDecor(); return }
        if (!live) return
        // the wiggle never rests while edit mode is open (the user's pick 2026-11-21): the window hidden (the screen off, the app gone
        // behind another) stops the ticker too; onWindowVisibilityChanged starts it again
        if (isAttachedToWindow && windowVisibility != View.VISIBLE) return
        edit?.takeIf { !it.parked }?.let { motion.modal(it.menu != null || it.sheet != null, t) }
        editDecor()
        roomBar?.invalidate()
        if (motion.busy(t)) editKick()
        else if (!motion.on) editDecor()                                  // the leave landed: one identity pass
    }

    /** [p]'s pose key: its widget's (a rider's host's: the buffs and the card on the map ride the map's); -1 = none */
    private fun keyOfPart(p: Part): Int = when (p) {
        // 1518 build 3: the band is no widget and never moves (the user's ruling 2026-11-22: it stays still in edit mode)
        Part.BARS -> -1
        Part.CHAT -> EditStop.CHAT.key
        Part.MAP, Part.AURAS -> EditStop.MAP.key
        Part.QUESTS -> if (cardOverMap()) EditStop.MAP.key else EditStop.QUEST.key
        Part.STRIP, Part.CAST2 -> EditStop.CAST.key
        Part.PARTY -> HomeEditMotion.PARTY
        Part.CHAT_FULL -> -1
    }
    /** the box [p]'s pose turns about (its key's widget: the map for its riders, both halves for a Split bar) into [out] */
    private fun ownerBox(p: Part, out: RectF): Boolean {
        val r = when (keyOfPart(p)) {
            -1 -> null
            // the card on the map turns about its OWN centre with the map's pose composed over it ([partPose]: no pivot switch at a lift)
            EditStop.MAP.key -> if (p == Part.QUESTS) now[Part.QUESTS] else now[Part.MAP]
            EditStop.CAST.key -> {
                val a = now[Part.STRIP]; val b = now[Part.CAST2]
                if (a != null) { out.set(a); if (b != null) out.union(b); return true }
                b
            }
            else -> now[p]
        } ?: return false
        out.set(r)
        return true
    }
    /** [p]'s pose at [t] into [out] (its box into [poseBox]) */
    private fun partPose(p: Part, t: Long, out: HomeEditMotion.Pose): HomeEditMotion.Pose {
        if (!motion.on) return out.identity()
        val k = keyOfPart(p)
        if (k < 0 || !ownerBox(p, poseBox)) return out.identity()
        if (p == Part.QUESTS && k == EditStop.MAP.key) return cardRidePose(t, out)
        return posedIn(k, t, poseBox, out)
    }
    /**
     * [k]'s pose for its widget laid at [box], kept inside Home's frame (the user's pick 2026-11-21: a lifted full-height map at 1.04 grew
     * past Home's foot and the room bar / the ribbon drew over its bottom edge; the bigger wiggle's corners at the frame's edges): the
     * shift that keeps its grown, turning box inside (HomeEditMotion.keepIn). Drawn only: touch reads the laid box
     */
    private fun posedIn(k: Int, t: Long, box: RectF, out: HomeEditMotion.Pose): HomeEditMotion.Pose {
        motion.pose(k, t, 0.5f * kotlin.math.hypot(box.width(), box.height()), out)
        if (!out.isIdentity) HomeEditMotion.keepIn(box.left, box.top, box.right, box.bottom, 0f, 0f, width.toFloat(), frameH, out)
        return out
    }
    /**
     * the card on the map: the MAP's pose (its scale and turn about the map's centre) said about the card's own centre ([poseBox]), mixed
     * with the card's own pose (QUEST's key: its lift, its plop, its wiggle) by [cardOwn] - 0 riding the map, 1 lifted - so a lift and a
     * drop move the card on, never by a pivot's jump (the map and the card centres are ~200 px apart: 0.06 of that at 0.94)
     */
    private fun cardRidePose(t: Long, out: HomeEditMotion.Pose): HomeEditMotion.Pose {
        val m = now[Part.MAP] ?: return out.identity()
        val cx = poseBox.centerX(); val cy = poseBox.centerY()
        posedIn(EditStop.MAP.key, t, m, rideM)
        val vx = (cx - m.centerX()) * rideM.scale; val vy = (cy - m.centerY()) * rideM.scale
        val a = Math.toRadians(rideM.rot.toDouble()); val co = kotlin.math.cos(a).toFloat(); val si = kotlin.math.sin(a).toFloat()
        val mdx = m.centerX() + vx * co - vy * si + rideM.dx - cx
        val mdy = m.centerY() + vx * si + vy * co + rideM.dy - cy
        val w = cardOwn(t)
        if (w <= 0f) { out.scale = rideM.scale; out.rot = rideM.rot; out.dx = mdx; out.dy = mdy; return out }
        posedIn(EditStop.QUEST.key, t, poseBox, rideQ)
        out.scale = lerp(rideM.scale, rideQ.scale, w); out.rot = lerp(rideM.rot, rideQ.rot, w)
        out.dx = lerp(mdx, rideQ.dx, w); out.dy = lerp(mdy, rideQ.dy, w)
        return out
    }
    private val rideM = HomeEditMotion.Pose()
    private val rideQ = HomeEditMotion.Pose()
    /** the card's own pose's share over the map's (0 .. 1): up as it is lifted, back down as it plops onto the map again */
    private var ownFrom = 0f
    private var ownTo = 0f
    private var ownAt = 0L
    private var ownMs = 0L
    private fun cardOwn(t: Long): Float {
        if (motion.scale <= 0f || ownMs <= 0L) return ownTo
        val k = ((t - ownAt).toFloat() / ownMs / motion.scale).coerceIn(0f, 1f)
        return ownFrom + (ownTo - ownFrom) * (k * k * (3f - 2f * k))
    }
    private fun cardOwnTo(to: Float, t: Long, ms: Long) { ownFrom = cardOwn(t); ownTo = to; ownAt = t; ownMs = ms }
    /** [p]'s pose as a matrix in Home's px (the finger's carry of a lifted widget too): false = identity. The chrome and Home's own paint
     *  over a part (the map's well, zone band and buttons; the clock block) follow it */
    private fun partMatrix(p: Part, out: android.graphics.Matrix): Boolean {
        if (!motion.on) return false
        partPose(p, motionNow(), pose)
        var dx = pose.dx; var dy = pose.dy
        val lifted = edit?.takeIf { !it.parked }?.lifted ?: liftStop
        if (lifted != null && partOf(lifted, p)) { liftOffset(liftOff); dx += liftOff[0]; dy += liftOff[1] }
        if (pose.scale == 1f && pose.rot == 0f && dx == 0f && dy == 0f) return false
        out.reset()
        out.postScale(pose.scale, pose.scale, poseBox.centerX(), poseBox.centerY())
        out.postRotate(pose.rot, poseBox.centerX(), poseBox.centerY())
        out.postTranslate(dx, dy)
        return true
    }
    /** Home's own paint over the parts follows their poses (edit mode on screen, or its leave landing) */
    private fun editPaintPosed(): Boolean = motion.on && (edit?.parked == false || chromeModel != null)

    /**
     * P4, THE ROOM BAR UNDER EDIT MODE ([roomBar], the host's Strip): its order is the draft's while edit mode is live (each change glides
     * on [ms]: EDIT_STEP a controller step, MODULE_OPEN a change, MODULE_CLOSE a drop / put back), the record's otherwise (a fight's
     * park, Discard: back on MODULE_CLOSE); the lifted room and a finger's carry ride along. A carried room is written at its drop.
     */
    var roomBar: Strip? = null
    private fun editBarSync(ms: Long) {
        val b = roomBar ?: return
        val m = edit?.takeIf { !it.parked }
        b.showOrder(RoomBar.shown(m?.draft ?: HomeSettings.layout), ms)
        val lr = m?.barLifted?.let { n -> Room.values().firstOrNull { it.name == n } }
        b.liftRoom = lr
        b.liftX = m?.drag?.takeIf { it.stop == EditStop.ROOMS && lr != null }?.cx
        b.invalidate()
    }
    private val dragTick = Runnable { edit?.let { m -> if (m.drag != null) editRun(m.dragTick(android.os.SystemClock.uptimeMillis())) } }

    /**
     * a behaviour row: the host's BottomTiles.press (its env), else Home's own for the setters it can reach. THE TOP BAR'S rows (P1 - P4,
     * the user's rulings 2026-11-22) are Settings > Bottom screen's very setters: they autosave and Discard leaves them (1518's ruling for
     * behaviour rows). The resting look and In fights are fields of the per-character record (HomeSettings.rest / fightBand write it):
     * the draft is rebased on the record as it is now (so the page shows it live, Discard keeps it, and the session's own changes stay
     * the only ones Discard undoes)
     */
    private fun editPress(key: String, k: Int) {
        when (key) {
            HomeEditModel.K_FOLD -> { HomeSettings.questMin = if (k < 0) !HomeSettings.questMin else k == 0; changed() }
            HomeEditModel.K_CAST_FIGHT -> HomeSettings.castBarCombat = if (k == 0) CastStripModel.Combat.STAY else CastStripModel.Combat.HIDE
            HomeEditModel.K_CAST_PARTY -> HomeSettings.castBarPartyShow = k == 0
            else -> onEditPress?.invoke(key, k) ?: BottomTiles.press("", key, k, BottomTiles.Env(padMap?.dpad, GameUi.character)) { }
        }
        val m = edit
        when (key) {
            BottomTiles.REST, BottomTiles.COMBAT -> if (m != null && !m.parked) {
                // the session's own settled change first (a widget dropped by this very tap), then the record as it is now
                editPersist(m)
                editRebase(m); liveDraft = m.draft
                editRestSync()
                editRelay(Motion.MODULE_OPEN, force = true)
                Log.i(TAG, "home edit: the band's ${if (key == BottomTiles.REST) "rest look" else "fight look"} now ${HomeLayoutJson.json(m.draft)}")
            }
            // the rank badges are read as the band draws (no listener): redraw it; the extras: Home draws the clock block
            BottomTiles.RANK, BottomTiles.SWING -> { castView.invalidate(); if (lent(castView)) castView.swingOn = HomeSettings.swingTimer }
            in HomeEditModel.EXTRA_KEYS -> { m?.let { editBandFeed(it) }; invalidate() }
        }
        editView.invalidate()
    }

    /** lay Home again with the draft, gliding [ms]; [force] = a glide even when the draft equals what is laid (the chrome's settle) */
    private fun editRelay(ms: Long, force: Boolean = false) {
        // the band's rest changed under edit mode (the draft's, or back to the saved one): the page's screen with the flow's rest now
        val s = shown?.let { if (it.rest != flow.rest) flow.screen else it } ?: return
        if (!force && laidLayout == layoutNow(s) && !laidChanged()) { editDecor(); return }
        applyMs = ms
        try { apply(s, animate = live) } finally { applyMs = null }
    }
    /** what [places] laid besides the record: the card's height, the bar's side (a settle lays these again) */
    private fun laidChanged(): Boolean = laidSide != castSideNow() || (layoutNow().cast == HomeLayout.CastDock.SPLIT) != laidSplit ||
        layoutNow().cast.top != laidTop

    /** refused (a fight, a dimmed choice): the widget's edge red for FLASH_MS, nothing else */
    private fun editRefuse(s: EditStop?) {
        editFlash = (s ?: EditStop.CHAT) to android.os.SystemClock.uptimeMillis()
        Log.i(TAG, "home edit: refused${if (fightShown()) " (a fight)" else ""} on ${s?.name?.lowercase()}")
        invalidate(); editView.invalidate()
        editUi.postDelayed({ invalidate() }, HomeEditModel.FLASH_MS + 20L)
    }

    /** a fight starting mid-edit parks edit mode (chrome out 120 ms, the record's fight laid by the fight's own motion: the draft is
     *  saved already); its end with Home on screen brings edit mode back where it was (120 ms in), on the record as it is then.
     *  [relay]: lay the page here (no takeover change to do it) */
    private fun editFightCheck(relay: Boolean = false) {
        val m = edit ?: return
        if (editAway) return
        val fight = fightShown()
        if (fight && !m.parked) {
            hold.cancel(); editView.ribbonLetGo()                         // 1518: a finger held on Done lets go (no stale down after)
            m.park(); editPersist(m, now = true); liveDraft = null
            editRestSync()                                                // the saved rest for the fight (the draft's waits)
            editRoomBarSync(); editBarSync(Motion.COMBAT_GROW)
            editGuest(false)
            // every widget at its own size at once, nothing turning (no motion in a fight); the ticker stops
            liftStop = null; dragOffsetNow.fill(0f); motion.still(); cardOwnTo(0f, 0L, 0L); editDecor()
            // the room bar's chrome goes with the rest (the XP track back, no disc tick, no ribbon touches) once the fade is out
            chromeFade(0f, HomeEditModel.CHROME_IN) { if (edit?.parked == true) onEditChrome?.invoke(null) }
            Log.i(TAG, "home edit: parked (a fight)")
            onEditLive?.invoke(false)
            // P4 fix round: a preview showing when the fight came: the D-pad follows the real fight's word now (the caller without
            // [relay] lays the page itself)
            val preview = previewEnded()
            if (relay) editRelay(Motion.COMBAT_GROW, force = preview)
        } else if (!fight && m.parked && live) {
            if (editOwnerCheck("the fight ended on another character")) return
            editRebase(m)
            m.resume(); liveDraft = m.draft
            editRestSync()
            editRoomBarSync(); editBarSync(Motion.COMBAT_SHRINK)
            editGuest(true)
            onEditChrome?.invoke(editView)
            chromeFade(1f, HomeEditModel.CHROME_IN)
            editMotionStart(null)
            Log.i(TAG, "home edit: back (the fight ended)")
            onEditLive?.invoke(true)
            if (relay) editRelay(Motion.COMBAT_SHRINK)
        }
    }

    /** another room over Home: the draft waits (as for a fight); Home again: edit mode where it was */
    private fun editAwayPark() {
        editBackDue = false
        val m = edit ?: return
        hold.cancel(); editView.ribbonLetGo()                             // 1518: a finger held on Done lets go (no stale down after)
        if (!m.parked) { m.park(); editPersist(m, now = true); liveDraft = null; editRestSync(); editGuest(false); editBarSync(0L); onEditLive?.invoke(false) }
        // P4 fix round: a preview showing: the D-pad's own pictures and the band's rest look (no stand-in swing, the cast bar alone) laid
        // at once, while the band is still Home's - the room that takes it next never gets the preview's look
        if (previewEnded()) shown?.let { apply(if (it.rest != flow.rest) flow.screen else it, animate = false) }
        editAway = true
        chromeAnim?.cancel(); chromeA = 0f
        liftStop = null; dragOffsetNow.fill(0f); motion.still(); cardOwnTo(0f, 0L, 0L); editDecor()
        onEditChrome?.invoke(null)
    }
    private fun editAwayBack() {
        val due = editBackDue
        editBackDue = false
        val m = edit ?: return
        if (!editAway) { if (due) { editRestSync(); editRelay(Motion.MODULE_CLOSE) }; return }
        editAway = false
        if (editOwnerCheck("Home again on another character")) return
        if (fightShown()) {                                               // the fight's end brings it back (editFightCheck)
            if (due) { editRestSync(); editRelay(Motion.COMBAT_GROW) }    // the draft laid early goes: the saved record for the fight
            return
        }
        editRebase(m)
        m.resume(); liveDraft = m.draft
        editRestSync()
        editGuest(true)
        onEditChrome?.invoke(editView)
        chromeFade(1f, HomeEditModel.CHROME_IN)
        editMotionStart(null)
        editRelay(Motion.MODULE_CLOSE)
        editBarSync(Motion.MODULE_CLOSE)
        onEditLive?.invoke(true)
    }

    private fun chromeFade(to: Float, ms: Long, end: (() -> Unit)? = null) {
        chromeAnim?.cancel()
        if (!live || ms <= 0L) { chromeA = to; editView.invalidate(); end?.invoke(); return }
        chromeAnim = ValueAnimator.ofFloat(chromeA, to).apply {
            duration = ms; interpolator = Motion.curve()
            addUpdateListener { chromeA = it.animatedValue as Float; editView.invalidate(); onEditChrome?.let { _ -> editRibbonRedraw?.invoke() } }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { chromeA = to; end?.invoke() } })
            start()
        }
    }
    /** the host's: redraw the ribbon (it lives in the room bar) */
    var editRibbonRedraw: (() -> Unit)? = null

    /** the drag's offset as last placed (a finger's drop springs into its dock from it) */
    private val dragOffsetNow = FloatArray(2)
    /** [liftOffset]'s frame buffer (every ticker frame reads it: no boxed pair per frame) */
    private val liftOff = FloatArray(2)

    /** the lifted widget's offset now under the finger (its grab kept) into [out] (x, y); its drop's spring into the dock is [motion]'s */
    private fun liftOffset(out: FloatArray) {
        out[0] = 0f; out[1] = 0f
        val d = edit?.drag
        if (d != null && d.stop.movable) {
            val base = baseOf(d.stop) ?: return
            val at = d.at()
            out[0] = at.l - base.left; out[1] = at.t - base.top
        }
    }
    private fun baseOf(s: EditStop): RectF? = when (s) {
        EditStop.CHAT -> now[Part.CHAT]; EditStop.MAP -> now[Part.MAP]; EditStop.QUEST -> now[Part.QUESTS]
        EditStop.CAST -> now[Part.STRIP]; EditStop.PARTY -> now[Part.PARTY]; else -> null
    }
    /** the parts a widget moves with it (the map takes its buffs and the card over it) */
    private fun partOf(s: EditStop, p: Part): Boolean = when (s) {
        EditStop.CHAT -> p == Part.CHAT
        EditStop.MAP -> p == Part.MAP || p == Part.AURAS || (p == Part.QUESTS && cardOverMap())
        EditStop.QUEST -> p == Part.QUESTS
        EditStop.CAST -> p == Part.STRIP || p == Part.CAST2
        EditStop.PARTY -> p == Part.PARTY
        else -> false
    }

    /**
     * edit mode's transforms on the parts, every frame of a glide / a drag / a lift / the ticker: each part's pose ([motion]: the shrink,
     * the wiggle, the lift with its overshoot, the drop's plop and slide), about its widget's centre (a rider's about its host's); the
     * lifted widget's finger offset and depth; the map's picture scaled to its gliding box ([mapScaleTo], cover, clipped to the box) with
     * its pose composed over the crop. Properties only (nothing is laid here). Nothing outside edit mode (one reset pass after it ends),
     * so the class / pause slides keep their own translation.
     */
    private fun editDecor() {
        // fix round 1: a session PARKED (a fight, a room) is no session here: one reset pass, then the class / pause / LB slides keep
        // their own translation and depth (they open while parked), never written over on a layout or an apply frame
        val liveEdit = edit?.takeIf { !it.parked }
        if (liveEdit == null && chromeModel == null && !decorDirty && mapScaleTo == null && !motion.on) return
        // the middle wheel's columns parting (wheelPart's animators) is theirs too: the leave's ticker never writes them back to 0
        if (liveEdit == null && chromeModel == null && mapScaleTo == null && (classOpen || wheelOpen || pauseT > 0f || pauseSide > 0f)) return
        val t = motionNow()
        val lifted = liveEdit?.lifted ?: liftStop
        liftOffset(liftOff)
        val ox = liftOff[0]; val oy = liftOff[1]
        if (liveEdit?.drag != null) { dragOffsetNow[0] = ox; dragOffsetNow[1] = oy }
        val k = liftK
        val settling = lifted != null && motion.settling(lifted.key, t)
        for ((p, v) in views) {
            val mine = lifted != null && partOf(lifted, p)
            partPose(p, t, pose)
            val dx = pose.dx + (if (mine) ox else 0f); val dy = pose.dy + (if (mine) oy else 0f)
            val d = if (p == Part.MAP) mapScaleTo else null
            if (d != null) {
                val cur = now[Part.MAP] ?: d
                val s = max(cur.width() / max(1f, d.width()), cur.height() / max(1f, d.height()))
                val tx = cur.centerX() - (d.left + d.width() * s / 2f)
                val ty = cur.centerY() - (d.top + d.height() * s / 2f)
                // the pose turns and shrinks the map about its DRAWN centre while the crop holds it (identity: today's pivot 0, 0 move)
                HomeEditMotion.composeMapGlide(d.left, d.top, tx, ty, s, cur.centerX(), cur.centerY(), poseOut)
                // the crop's own translation (its pivot's share included): what is left of translationX / Y is the finger's carry, which
                // the map's paint follows when it is not drawn through the pose (a Discard's glide outlives the chrome's fade)
                mapScaleTx = poseOut[2]; mapScaleTy = poseOut[3]
                v.pivotX = poseOut[0]; v.pivotY = poseOut[1]
                v.scaleX = s * pose.scale; v.scaleY = s * pose.scale
                v.rotation = pose.rot
                v.translationX = poseOut[2] + dx; v.translationY = poseOut[3] + dy
                val l = (cur.left - d.left - tx) / s; val tp = (cur.top - d.top - ty) / s
                v.clipBounds = android.graphics.Rect(kotlin.math.floor(l).toInt(), kotlin.math.floor(tp).toInt(),
                    kotlin.math.ceil(l + cur.width() / s).toInt(), kotlin.math.ceil(tp + cur.height() / s).toInt())
            } else {
                // about the widget's centre (a rider: its host's), else the view's own
                if (motion.on && ownerBox(p, poseBox)) { v.pivotX = poseBox.centerX() - v.left; v.pivotY = poseBox.centerY() - v.top }
                else { v.pivotX = v.width / 2f; v.pivotY = v.height / 2f }
                v.scaleX = pose.scale; v.scaleY = pose.scale
                v.rotation = pose.rot
                v.translationX = dx; v.translationY = dy
            }
            v.translationZ = if (mine && (k > 0f || ox != 0f || oy != 0f || settling)) LIFT_Z + restZ(p) else restZ(p)
        }
        // the lift has landed and its drop slid in: nothing is lifted any more
        // (after its lift spring has landed too: the drop's plop runs below 1 after the lift's share reads 0)
        if (liveEdit?.lifted == null && liveEdit?.drag == null && liftStop != null && k == 0f && !settling && motion.liftDone(liftKey, t)) liftStop = null
        decorDirty = liveEdit != null || chromeModel != null || mapScaleTo != null || liftK > 0f || motion.on
        if (mapScaleTo == null) { mapScaleTx = 0f; mapScaleTy = 0f }
        // fix round 1: the page settled with the cursor's widget gone (the card / the bar Off, Party mode's Hide): the cursor moves on
        if (liveEdit != null && settled) editCursorCheck(liveEdit)
        editView.invalidate()
        invalidate()
    }

    /** the drawn boxes (pose and offset applied) of the parts raised over the others by edit mode - a lifted, dragged or settling widget
     *  that is not the map (the map's own paint follows the map); empty outside edit mode. Read every frame: the list and its boxes are
     *  this view's own, good until the next call */
    fun liftedBoxes(): List<RectF> {
        liftedOut.clear()
        // a parked session lifts nothing (the class wheel's cover depth on a column is not a lift)
        if (edit?.takeIf { !it.parked } == null && chromeModel == null) return liftedOut
        if ((edit?.lifted ?: liftStop) == EditStop.MAP) return liftedOut
        for ((p, v) in views) {
            if (p == Part.MAP || p == Part.AURAS || v.visibility != View.VISIBLE || v.width <= 0) continue
            if (v.translationZ <= restZ(p) + 0.5f) continue
            if (liftedPool.size <= liftedOut.size) liftedPool += RectF()
            liftedOut += drawnBox(v, liftedPool[liftedOut.size])
        }
        return liftedOut
    }
    private val liftedOut = ArrayList<RectF>()
    private val liftedPool = ArrayList<RectF>()
    /** [v]'s box as drawn (its scale and turn about its pivot, its translation), in Home's px, into [out] */
    private fun drawnBox(v: View, out: RectF = RectF()): RectF {
        viewM.reset()
        viewM.postScale(v.scaleX, v.scaleY, v.pivotX, v.pivotY)
        viewM.postRotate(v.rotation, v.pivotX, v.pivotY)
        viewM.postTranslate(v.left + v.translationX, v.top + v.translationY)
        out.set(0f, 0f, v.width.toFloat(), v.height.toFloat())
        viewM.mapRect(out)
        return out
    }

    /** the map's glide scaling ends: laid at its box, no scale, no clip (one full layout pass where it lands) */
    private fun mapScaleEnd() {
        if (mapScaleTo == null) return
        mapScaleTo = null; mapScaleTx = 0f; mapScaleTy = 0f
        mapSlot.clipBounds = null
        mapSlot.scaleX = 1f; mapSlot.scaleY = 1f; mapSlot.rotation = 0f
        mapSlot.pivotX = mapSlot.width / 2f; mapSlot.pivotY = mapSlot.height / 2f
        mapSlot.translationX = 0f; mapSlot.translationY = 0f
        decorDirty = true
        placeFrame()
    }

    // ---------------------------------------------------------------- way 1: the long hold on the chat's or the map's BODY

    /** the hold now: its widget, where and when it came down; [holdTakes] = it fired: the rest of the gesture is edit mode's */
    private var holdStop: EditStop? = null
    private var holdAt = 0L
    private var holdX = 0f
    private var holdY = 0f
    private var holdTakes = false
    /** the gesture after a hold that was refused (a fight): swallowed to its end, nothing else */
    private var holdSwallow = false
    private var holdChildCancelled = false
    private val holdFire = Runnable { holdFired() }
    private val holdRing = object : Runnable { override fun run() { if (holdStop != null) { invalidate(); editUi.postDelayed(this, 16L) } } }

    /** the body of the chat (not its "!") or of the map (not its zone band, the calendar and mail buttons, the zoom, the auras, the
     *  card), on Home's rest page; null = a hold there is never edit mode's */
    private fun holdBodyAt(x: Float, y: Float): EditStop? {
        if (flow.full != null && !flow.takeover) return null
        // never armed in a fight: a slow touch there stays today's tap (the map / the chat opens, combat-touch); the refusal's red edge
        // is the Settings tile's and the disc's (editOpen)
        if (fightShown()) return null
        if (classOpen || pauseT > 0f || typeWanted || mailOpen || wheelOpen) return null
        // fix round 3: never while hold to talk's pop-up is up (it lies over the chat's and the map's tops; its tabs take a finger)
        if (castView.talkPanel != null) return null
        now[Part.QUESTS]?.let { if (questPane.visibility == View.VISIBLE && it.contains(x, y)) return null }
        now[Part.PARTY]?.let { if (partyView.visibility == View.VISIBLE && it.contains(x, y)) return null }
        now[Part.CHAT]?.let { c -> if (chatPane.visibility == View.VISIBLE && c.contains(x, y) && !chatPane.onBang(x - c.left, y - c.top)) return EditStop.CHAT }
        now[Part.MAP]?.let { m ->
            if (mapSlot.visibility != View.VISIBLE || !m.contains(x, y)) return null
            if (y < m.top + ZONE_BAND_BOTTOM) return null
            if (auraPane.visibility == View.VISIBLE && auraPane.owns(x - auraPane.left, y - auraPane.top)) return null
            return EditStop.MAP
        }
        return null
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        // the hold fired: the child that had the touch is cancelled once (no Chat room, no map module), the rest is edit mode's
        if (holdTakes || holdSwallow) {
            if (!holdChildCancelled) {
                holdChildCancelled = true
                val c = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(c); c.recycle()
            }
            if (holdTakes && edit != null) editView.touch(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) { holdTakes = false; holdSwallow = false }
            return true
        }
        if (edit == null) holdWatch(e)
        return super.dispatchTouchEvent(e)
    }

    private fun holdWatch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                holdCancel()
                val s = holdBodyAt(e.x, e.y) ?: return
                holdStop = s; holdAt = android.os.SystemClock.uptimeMillis(); holdX = e.x; holdY = e.y
                editUi.postDelayed(holdFire, HomeEditModel.HOLD_MS)
                editUi.postDelayed(holdRing, HomeEditModel.RING_FROM_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> holdCancel()                 // a second finger: never a hold
            MotionEvent.ACTION_MOVE -> if (holdStop != null && (abs(e.x - holdX) > HomeEditModel.HOLD_SLOP || abs(e.y - holdY) > HomeEditModel.HOLD_SLOP)) holdCancel()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> holdCancel()
        }
    }
    private fun holdCancel() {
        if (holdStop == null) return
        holdStop = null
        editUi.removeCallbacks(holdFire); editUi.removeCallbacks(holdRing)
        invalidate()
    }
    private fun holdFired() {
        val s = holdStop ?: return
        holdStop = null
        editUi.removeCallbacks(holdRing)
        holdChildCancelled = false
        // a fight, or (the 1515 merge's review) the key question up - shown or waiting for the controller: refused AND swallowed to the
        // lift (the red edge only; never the Chat room or the map module on the UP). Fix round 1: the DOWN's gates again at the fire
        // (Home left or covered meanwhile: another room, the pause / LB / class wheel, the type tool, the mail card, a host overlay):
        // nothing opens, the gesture stays the child's
        when (HomeEditModel.holdFire(fightShown(), editBlocked?.invoke() == true, holdStillOk(s))) {
            HomeEditModel.HoldFire.REFUSE -> {
                if (!fightShown()) Log.i(TAG, "home edit: refused (the key question is up)")
                holdSwallow = true; editRefuse(s); invalidate(); return
            }
            HomeEditModel.HoldFire.LET_GO -> { Log.i(TAG, "home edit: the hold ended unopened (Home left or covered)"); invalidate(); return }
            HomeEditModel.HoldFire.OPEN -> {}
        }
        if (editOpen(s, lift = true)) { holdTakes = true; editView.carry(s, holdX, holdY) }
        else holdSwallow = true                                           // refused there: the lift is swallowed the same way
        invalidate()
    }
    /** the hold's widget is still what [holdBodyAt] gives where it came down, on a Home that is on screen and not covered */
    private fun holdStillOk(s: EditStop): Boolean = live && holdBodyAt(holdX, holdY) == s && overlayOpen?.invoke() != true

    /** the hold's gold ring at the finger from RING_FROM_MS, and the panel's edge lighting over the hold (final_12) */
    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private fun drawHold(c: Canvas) {
        // a request refused in a fight (edit mode not open: its chrome draws its own): the widget's edge red for FLASH_MS
        editFlash?.let { (fs, at) ->
            val t = android.os.SystemClock.uptimeMillis() - at
            val box = now[when (fs) { EditStop.MAP -> Part.MAP; EditStop.CAST -> Part.STRIP; EditStop.QUEST -> Part.QUESTS
                else -> Part.CHAT }]
            if (edit == null && chromeModel == null && box != null && t in 0 until HomeEditModel.FLASH_MS) {
                pRing.strokeWidth = 6f; pRing.color = Color.argb((255 * (1f - t.toFloat() / HomeEditModel.FLASH_MS)).toInt(), 220, 40, 30)
                c.drawRoundRect(RectF(box).apply { inset(-2f, -2f) }, UiKit.MODULE_R, UiKit.MODULE_R, pRing)
                postInvalidateOnAnimation()
            }
        }
        val s = holdStop ?: return
        val t = android.os.SystemClock.uptimeMillis() - holdAt
        if (t < HomeEditModel.RING_FROM_MS) return
        val k = ((t - HomeEditModel.RING_FROM_MS).toFloat() / (HomeEditModel.HOLD_MS - HomeEditModel.RING_FROM_MS)).coerceIn(0f, 1f)
        val box = now[if (s == EditStop.CHAT) Part.CHAT else Part.MAP] ?: return
        pRing.strokeWidth = 5f; pRing.color = Theme.focusA((200 * k).toInt())
        c.drawRoundRect(RectF(box).apply { inset(2f, 2f) }, UiKit.MODULE_R, UiKit.MODULE_R, pRing)
        val r = RING_R
        pRing.strokeWidth = 4f; pRing.color = Color.argb(150, 0, 0, 0)
        c.drawCircle(holdX, holdY, r, pRing)
        pRing.strokeWidth = 9f; pRing.color = Theme.focusA(255)
        c.drawArc(RectF(holdX - r, holdY - r, holdX + r, holdY + r), -90f, 360f * k, false, pRing)
        pRing.strokeWidth = 0f
    }


    // ================================================================ movable Home: the D-pad toast (SPEC section 6 "SYNC", final_05)

    /** the add-on's `d` changed its Left / Right (a swap took): "D-pad < Map > Chat" on Home for 1.6 s, the kit's gold arrows */
    private var toastAt = 0L
    private var toastWords: List<String> = emptyList()
    fun toolbarToast(dirs: Map<Int, String>) {
        toastWords = ToolbarMirror.toastWords(dirs).map { Tr.t(it) }
        toastAt = SystemClock.uptimeMillis()
        invalidate()
    }
    private val pToast = Paint(Paint.ANTI_ALIAS_FLAG)
    /**
     * the D-pad toast's pill for words [wsum] wide: in the diamonds' clock gap at rest (today's spot); fix/menus-fit: under the band
     * when the band is the thin row or grown for a fight (the gap is not there: the pill lay on the row's buttons / the D-pad's lower
     * button), over the columns' tops, inside the frame; under a tap-to-cast bar that sits at the top of a column (Above chat / Above
     * map, shown) when the pill reaches over it. Classic keeps 1516's spot on the band's foot (classicLook: not yet ruled on)
     */
    fun toastBox(wsum: Float, h: Float = 54f): RectF {
        val cx = width / 2f
        val bb = (now[Part.BARS]?.bottom ?: 226f).coerceAtLeast(0f)
        val diamonds = 8f + REST_DIAMONDS_BARS + CastBarPlace.REST_STRIP
        val l = cx - wsum / 2f - 28f; val r = cx + wsum / 2f + 28f
        // Classic's diamonds grown for a fight: 1516's pill on the band's foot (a thin-row record is not 1516's Classic look: under it)
        val held = classicLook() && (laidLayout ?: layoutNow()).rest == HomeRest.DIAMONDS
        val cy = if (kotlin.math.abs(bb - diamonds) < 1f || held) bb - h / 2f - 6f
            else {
                var top = bb + UiKit.MODULE_GAP
                if (laidTop && !laidHidden) for (p in TOP_BARS) {
                    val b = now[p] ?: continue
                    if (views[p]?.visibility != View.VISIBLE || b.bottom <= bb + 0.5f) continue
                    if (b.left < r && l < b.right) top = maxOf(top, b.bottom + UiKit.MODULE_GAP)
                }
                (top + h / 2f).coerceAtMost(frameH - UiKit.MODULE_GAP - h / 2f)
            }
        return RectF(l, cy - h / 2f, r, cy + h / 2f)
    }
    private val TOP_BARS = arrayOf(Part.STRIP, Part.CAST2)
    private val pToastEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private fun drawToast(c: Canvas) {
        if (toastWords.isEmpty()) return
        val a = ToolbarMirror.toastAlpha(SystemClock.uptimeMillis() - toastAt)
        if (a <= 0f) { toastWords = emptyList(); return }
        pToast.textSize = 30f; UiKit.asBody(pToast); pToast.color = Theme.gold; pToast.alpha = (255 * a).toInt()
        val arrow = 22f; val gap = 14f
        val words = toastWords
        val wsum = words.sumOf { pToast.measureText(it).toDouble() }.toFloat() + (words.size - 1) * (arrow + 2 * gap)
        val h = 54f
        val cx = width / 2f
        val box = toastBox(wsum, h)
        val cy = box.centerY()
        pToastEdge.color = Theme.gold; pToastEdge.alpha = (255 * a).toInt()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ink; alpha = (235 * a).toInt() }
        c.drawRoundRect(box, h / 2f, h / 2f, fill)
        c.drawRoundRect(box, h / 2f, h / 2f, pToastEdge)
        var x = box.left + 28f
        val base = cy - (pToast.ascent() + pToast.descent()) / 2f
        for ((i, w) in words.withIndex()) {
            if (i > 0) {
                x += gap
                UiKit.tex(if (i == 1) "move_arrow_left" else "move_arrow_right")?.let { b ->
                    val bp = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255 * a).toInt() }
                    c.drawBitmap(b, null, RectF(x, cy - arrow / 2f, x + arrow, cy + arrow / 2f), bp)
                }
                x += arrow + gap
            }
            c.drawText(w, x, base, pToast)
            x += pToast.measureText(w)
        }
        postInvalidateOnAnimation()
    }

    /** P2: edit mode's chrome is drawn LAST, over Home's own paint (the zone band, the clock block, the wells' edges) */
    override fun dispatchDraw(c: Canvas) {
        dispatchDrawHome(c)
        if (editView.visibility == View.VISIBLE && editView.width > 0) {
            val s = c.save(); c.translate(editView.left.toFloat(), editView.top.toFloat()); editView.draw(c); c.restoreToCount(s)
        }
    }
    override fun drawChild(c: Canvas, child: View, drawingTime: Long): Boolean = if (child === editView) false else super.drawChild(c, child, drawingTime)

    /** fix/menus-fit: the map's own paint follows the map view's real move during the pause / LB slides (Classic too: the user's pick
     *  2026-11-21, "Fix in Classic too") */
    private fun mapPaintRides(): Boolean = pauseT > 0f
    /** fix/menus-fit, the tests' reading of [dispatchDrawHome]: how far the map's own paint (the well edge, the zone band, the calendar)
     *  is drawn from the map's laid box now, x */
    fun mapPaintDx(): Float = (if (pauseT > 0f && !mapPaintRides()) pauseMap[0] else 0f) + mapSlot.translationX - mapScaleTx

    /** the map's own paint's boxes (the zone band, the Calendar and mail buttons), set in place each draw */
    private val mapBand = RectF()
    private val mapCal = RectF()
    private val mapMail = RectF()

    private fun dispatchDrawHome(c: Canvas) {
        super.dispatchDraw(c)
        if (shown == null || typeWanted) return                          // f-trackpad-keyboard: the tool is a clean page
        drawHold(c)
        // the zone band and the clock FADE with a move (the harmony pass; they used to vanish at a move's start and pop back at
        // its end), drawn where their part is now (and, while the pause menu slides, where the map / the band have gone)
        // fix/menus-fit: during the LB picture's and the pause's slides the map's own paint (its well edge, the zone band, the calendar
        // and mail buttons, the mail card) rides the MAP VIEW's real move (mapSlide below already holds it) at the view's alpha: the
        // pause curve's extra rightward move sent it twice as far, or the wrong way after a swap (an empty framed well with the zone's
        // name stood behind the LB picture). Classic too (the user's pick 2026-11-21: "Fix in Classic too")
        val paintRides = mapPaintRides()
        val zoneA = mix(overlayFrom.zone, overlayTo.zone) * (if (paintRides) mapSlot.alpha else pauseMap[2])
        // (In fights = Top screen: the clock rides the band's box up off the page and back - TopSlide; no "up" plate any more)
        val clockA = mix(overlayFrom.clock, overlayTo.clock) * pauseTop[2]
        val pauseSave = if (pauseT > 0f) c.save() else -1
        if (pauseSave >= 0 && !paintRides) c.translate(pauseMap[0], pauseMap[1])
        // THE ZONE BAND across the small map's top, its whole width, the name centred (user, 09-29 17:30: "The current map
        // name should show over the top of the minimap like it does normally"; 09-30: centred across the full space): the
        // classic minimap's own zone bar (UI Art's UiKit.zoneBand)
        // THE MAP'S WELL: the map drawn square by MapView, Home rounds it like every module (the frame's ink in the corners outside
        // MODULE_R, then the well's edge), at the page's alpha (a full map has no well: the corners fade out with the page)
        // f-class-radial rework (the user on 1.0.8-test4: "the border and map name are not moving still on the map module"): the well's
        // edge and the zone band are painted HERE, from the map's laid-out place, so they follow the map's slide (its translationX) too
        // P2: the map's lift (a finger dragging it) moves its paint too; its glide's scaling does not (now[MAP] is the box drawn)
        val mapSlide = mapSlot.translationX - mapScaleTx
        val mapSlideY = if (edit != null || chromeModel != null) mapSlot.translationY - mapScaleTy else 0f
        // fix round 1: a widget other than the map lifted / dragged / settling lies OVER the map's own paint (its well edge, the zone
        // band, the calendar and mail buttons, the mail card): that paint is clipped out of the lifted box (translationZ orders views only)
        val liftClip = liftedBoxes()
        val clipSave = if (liftClip.isNotEmpty()) c.save() else -1
        for (b in liftClip) c.clipOutRect(b)
        // the iPhone-style edit mode: the map's paint shrinks, turns and lifts with the map (its pose, the finger's carry included)
        val mapPosed = editPaintPosed() && partMatrix(Part.MAP, paintM)
        val slideSave = if (mapPosed || mapSlide != 0f || mapSlideY != 0f) c.save() else -1
        if (mapPosed) c.concat(paintM) else if (slideSave >= 0) c.translate(mapSlide, mapSlideY)
        if (zoneA > 0.01f) now[Part.MAP]?.let { m -> mapWell(c, m, zoneA) }
        if (zoneA > 0.01f && zoneText.isNotEmpty()) now[Part.MAP]?.let { m ->
            // the name is half the bar's height (UiKit.zoneBand): a 64 px bar = 32 px text (user 09-30: "the map name is SUPER
            // small and hard to read"); map's arrow keeps below ZONE_BAND_BOTTOM
            // THE CALENDAR BUTTON (the user, 2026-11-13: "a button on the right side of the map name make it small"): the band ends
            // before it, and both fade and slide together
            // THE MAIL ENVELOPE (the user, 2026-11-16: "we need to add an icon somewhere to the home menu for when the player has mail";
            // HomeMailButton): beside the Calendar button while the game says there is mail; the band one button shorter meanwhile
            // (no boxes made: the edit wiggle's ticker draws this every frame the whole time edit mode is open)
            val mailOn = mail?.on == true
            val band = mapBand.apply { set(m.left + 8f, m.top + ZONE_BAND_TOP, HomeMailButton.bandRight(m.right, mailOn), m.top + ZONE_BAND_BOTTOM) }
            val cb = mapCal.apply { set(m.right - HomeCalendarButton.EDGE - HomeCalendarButton.SIZE, m.top + HomeCalendarButton.TOP, m.right - HomeCalendarButton.EDGE, m.top + HomeCalendarButton.TOP + HomeCalendarButton.SIZE) }
            val save = if (zoneA < 0.99f) c.saveLayerAlpha(band.left - 4f, band.top - 4f, cb.right + 4f, band.bottom + 4f, (zoneA * 255).toInt()) else -1
            UiKit.zoneBand(c, band, zoneText, Theme.zoneColour(zonePvp))
            UiKit.calendarButton(c, cb, calPending, down = auraPane.calDown)
            val mb = mapMail.apply { set(cb); offset(-HomeMailButton.SHIFT, 0f) }
            if (mailOn) UiKit.mailButton(c, mb, lit = mailOpen, down = auraPane.mailDown)
            if (save >= 0) c.restoreToCount(save)
            // its card hangs under it, over the map (and the quest card / buffs while it is open), at the band's own fade
            if (mailOn && mailOpen && !auraPane.fullMap()) {
                val cs = if (zoneA < 0.99f) c.saveLayerAlpha(m.left, m.top, m.right, m.bottom + 40f, (zoneA * 255).toInt()) else -1
                mail?.let { ml -> UiKit.mailCard(c, mb.right, mb.bottom + HomeMailButton.CARD_GAP, m.left + 8f, ml.heading, ml.from) }
                if (cs >= 0) c.restoreToCount(cs)
            }
        }
        if (slideSave >= 0) c.restoreToCount(slideSave)
        if (clipSave >= 0) c.restoreToCount(clipSave)
        // THE CLOCK AND THE BATTERY at the top centre, between the two diamond groups (17:30: "the time should be on display
        // at the top center between the controls, right under it, a battery Icon with my current system charge").
        // not before the controller layout has come: the Cast view's "Waiting" stands there (UI Art, seen on its emulator)
        if (pauseSave >= 0) { c.restoreToCount(pauseSave); c.save(); c.translate(pauseTop[0], pauseTop[1]) }
        // THE EXTRAS ROW (feat/clock-extras-v2): the extras turned on in ONE row where the ZL + ZR join shows (CastBarPlace.clockSpot),
        // under the clock chip, faded out while a trigger is held (the chip is not), under the chip's own combat / pause fades too.
        // feat/home-info-block: the chip and the row are ONE block that grows into the empty column (UiKit.clockBlock) and is small
        // again while a cast shows or L+R is held (CastView.infoShrink)
        val tNow = android.os.SystemClock.elapsedRealtime()
        val trigA = trigVis(tNow)
        if (trigA != (if (trigHeld) 0f else 1f)) postInvalidateOnAnimation()
        val lin = infoLinear(tNow)
        if (lin != (if (infoGrown) 1f else 0f)) postInvalidateOnAnimation()
        // a cast's finish ends with no event: look again while small for a cast
        if (!infoGrown) { removeCallbacks(infoRecheck); postDelayed(infoRecheck, 100L) }
        val g = infoCurve.getInterpolation(lin)
        val chipOn = clockA > 0.01f && barsSeen && clockText.isNotEmpty() && batteryPct >= 0
        val bandA = clockA * trigA
        val rowOn = bandA > 0.01f && barsSeen && castView.parent === barsSlot
        if (chipOn || rowOn) now[Part.BARS]?.let { b ->
            // the items even while the triggers fade the row out: the block's grown size counts them (no jump as the row fades)
            // P2 (the user's ruling 2026-11-22): in edit mode every extra turned on is drawn (with or without its data) and wiggles as an
            // app, the gold line too, with the "+" spot's cell after them; outside edit mode exactly as always
            val ed = editClock()
            val keys = if (ed) editRowKeys() else emptyList()
            val items = if (barsSeen && castView.parent === barsSlot) (if (ed) editBandItems(keys) else bandItems()) else emptyList()
            if (ed) editRowDrawn = keys
            val spot = if (castView.parent === barsSlot) castView.clockSpot()?.also { spot ->
                for (i in 0 until spot.size - 1) spot[i] += if (i % 2 == 0) b.left else b.top
            } else null
            // the band itself never moves in edit mode (1518 build 3; the user's ruling 2026-11-22): only the extras wiggle
            val save = if (clockA < 0.99f) c.saveLayerAlpha(b.left, b.top, b.right, b.bottom, (clockA * 255).toInt()) else -1
            UiKit.clockBlock(c, b.centerX(), b.top + ClockBand.CHIP_TOP, clockText, batteryPct, charging, if (ed) editChipGold() else chipGold(),
                spot, items, g, rowAlpha = if (rowOn) (trigA * 255).toInt() else 0, chipAlpha = if (chipOn) 255 else 0,
                pose = if (ed) extraPoser else null)
            if (save >= 0) c.restoreToCount(save)
            // the mm:ss counts (as the auras' times); nothing ticks otherwise
            if (items.any { it is UiKit.BandItem.Icon && it.value.contains(':') }) postInvalidateDelayed(1000L)
        }
        // HOLD TO TALK's line (HoldTalk, the user 2026-11-16): under the toolbar's D-pad, over the page (the band is only as tall as its
        // buttons); with the band's own pause slide
        // its POP-UP (the user 2026-11-17: "pop up tabs to press L or R to change what chat that its for"): the chat tabs, the microphone
        // and the words, in the line's place while the hold lasts and its words wait
        if ((castView.talkLine != null || castView.talkPanel != null) && castView.parent === barsSlot && barsSlot.visibility == View.VISIBLE) {
            val ox = barsSlot.left + barsSlot.translationX + castView.left + castView.translationX
            val oy = barsSlot.top + barsSlot.translationY + castView.top + castView.translationY
            if (castView.talkPanel != null) castView.drawTalkPanel(c, ox, oy, width.toFloat(), this)
            else castView.drawTalkLine(c, ox, oy, width.toFloat())
        }
        if (pauseSave >= 0) c.restoreToCount(pauseSave)
        drawToast(c)                                                      // the D-pad toast over the clock block's chips (final_05)
    }

    // ================================================================ the bars: a tap opens them full screen

    private var barsDown = false
    /** a touch that came down on hold to talk's pop-up (CastView.talkPanelTouch): the rest of it is the pop-up's */
    private var talkSwallow = false
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        // f-trackpad-keyboard: the tool's touches are the tool's (a drag starting where the bars lie was taken as a tap on the bars)
        if (typeWanted) { barsDown = false; return false }
        // P2: edit mode's chrome (the top child) takes every touch: no touch-cast, no chat / map / bar taps while editing
        if (editLive()) { barsDown = false; return false }
        // hold to talk's pop-up: a finger on it is its own (a tab picks that chat); nothing under it acts
        if (e.actionMasked == MotionEvent.ACTION_DOWN) talkSwallow = castView.talkPanelTouch(e.x, e.y)
        if (talkSwallow) { barsDown = false; return true }
        // THE MAIL CARD (HomeMailButton; the user's pick: "B or tap elsewhere closes"): while it is open a touch anywhere on Home closes
        // it. On the map (the card, the envelope, the Calendar button, the quest card and buffs under it) that touch does nothing else
        // (it is Home's to its end); anywhere else it goes on as always - the controller's buttons still cast, in a fight too (review
        // of feat/wk-mail: the closing touch ate a touch cast), the party's frames still target
        if (e.actionMasked == MotionEvent.ACTION_DOWN) mailSwallow = false
        if (e.actionMasked == MotionEvent.ACTION_DOWN && mailOpen) {
            mailCard(false)
            if (onMapSlot(e)) { mailSwallow = true; barsDown = false; return true }
        }
        if (flow.full == HomeModule.BARS || barsSlot.visibility != View.VISIBLE) return false
        // the (-) Party mode tag stands over the bars' foot: its touch is the tag's
        if (e.actionMasked == MotionEvent.ACTION_DOWN) barsDown = inBars(e) &&
            !(partyView.visibility == View.VISIBLE && partyView.tagAt(e.x - partyView.left - partyView.translationX, e.y - partyView.top))
        return barsDown
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (talkSwallow) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) talkSwallow = false
            return true
        }
        if (mailSwallow) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) mailSwallow = false
            return true
        }
        if (!barsDown) return false
        // 1.0.19 (the user: "When touching the top controller stuff I don't want it to open the old controller room. Its time to retire
        // that. Instead the buttons should touch cast the skills"): the touch goes to the controller's buttons (CastView.touchDown / Up)
        val cv = castView.takeIf { it.parent === barsSlot }
        val lx = e.x - barsSlot.left - barsSlot.translationX - (cv?.left ?: 0) - (cv?.translationX ?: 0f)
        val ly = e.y - barsSlot.top - barsSlot.translationY - (cv?.top ?: 0) - (cv?.translationY ?: 0f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> cv?.touchDown(lx, ly)
            MotionEvent.ACTION_UP -> { barsDown = false; if (inBars(e)) cv?.touchUp(lx, ly) }
            MotionEvent.ACTION_CANCEL -> barsDown = false
        }
        return true
    }

    private fun inBars(e: MotionEvent) =
        e.x >= barsSlot.left && e.x < barsSlot.right && e.y >= barsSlot.top && e.y < barsSlot.bottom

    /** the touch is on the map's part where it is drawn now (its slide included): where the mail envelope and its card are */
    private fun onMapSlot(e: MotionEvent): Boolean = mapSlot.visibility == View.VISIBLE &&
        e.x >= mapSlot.left + mapSlot.translationX && e.x < mapSlot.right + mapSlot.translationX &&
        e.y >= mapSlot.top + mapSlot.translationY && e.y < mapSlot.bottom + mapSlot.translationY

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

        private val pGround by themed { Paint().apply { color = Theme.panel } }

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
            // q64 (the user, 2026-11-17: "I didnt want the rewards on the quests in the home page ... showing the Icon for if there is a
            // usable quest Item"): no reward line; the quest's item at the card's right edge, redrawn while its cooldown runs
            if (!cardHidden) UiKit.questCard(c, RectF(0f, 0f, w, h), q, scale = UiKit.CARD_HOME_SCALE,
                bgAlpha = if (cardOverMap()) lerp(UiKit.CARD_OVER_MAP_ALPHA.toFloat(), 255f, plate).toInt() else 255,
                collapsed = folded, itemButton = true)
            if (!cardHidden && q?.item?.let { QuestItem.Cd.left(it.id) > 0.0 } == true) postInvalidateDelayed(250L)
            val n = noteText
            if (n != null && System.currentTimeMillis() < noteUntil) {
                val p = UiKit.bodyPaint(24f, Theme.warn)
                c.drawText(Tr.t(TextUtils.ellipsize(n, p, w - 36f, TextUtils.TruncateAt.END).toString()), 18f, h - 16f, p)
                postInvalidateDelayed(NOTE_MS)
            } else noteText = null
        }

        private fun line(c: Canvas, w: Float, h: Float, s: String) {
            val p = UiKit.bodyPaint(27f, Theme.dim)
            c.drawText(Tr.t(s), 24f, h / 2f - (p.ascent() + p.descent()) / 2f, p)
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
            // q65 (the user, 2026-11-17: "make the quest item usable by tap out of combat"): a touch on the quest's item uses it
            val qi = if (cardHidden) null else homeCard()?.item
            if (qi != null && UiKit.questItemBox(RectF(0f, 0f, width.toFloat(), height.toFloat()), UiKit.CARD_HOME_SCALE)
                    .apply { inset(-14f, -14f) }.contains(x, y)) {
                val ok = QuestItemTap.use(context, qi, pad, inFight() || questsFight) { note(it) }
                Log.i(TAG, "quest item ${qi.id} (quest ${qi.quest}): ${if (ok) "tapped" else "refused"}")
                return
            }
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

        init { ChatFilter.onChange { postInvalidate() } }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            UiKit.asBody(tp)
            // the chat's WELL (the harmony pass: every module the same well), the log inside its padding, from the bottom
            UiKit.module(c, RectF(0f, 0f, w, h))
            // no "General" tab (user, 15:15: "remove the general tab from the chat ... just visual clutter")
            val pad = UiKit.MODULE_PAD
            // the game's world notice (the shard transfer's countdown) pinned at the top of the well; the log starts under it
            // chat40: the Battle.net friend notices under it ([drawNotices])
            val top = pad + drawNotices(c, RectF(pad, pad, w - pad, h - pad), bang, false, this)
            tp.textSize = 27f
            val width = (w - 2f * pad).toInt().coerceAtLeast(10)
            var y = h - pad
            c.save(); c.clipRect(pad, top, w - pad, h - pad)
            for (l in chat.all.asReversed()) {
                // the Chat room's checklist (the user, 2026-11-16: "check ... all of the message types that I care about seeing"): the
                // pane shows the same types the room does
                if (!ChatFilter.shows(l.ch, l.chan)) continue
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
            return StaticLayout.Builder.obtain(Tr.span(s), 0, Tr.span(s).length, tp, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setLineSpacing(0f, 1.12f)
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build()
        }

        private fun layoutOf(l: HomeChatLine, width: Int): StaticLayout {
            val key = width * 100 + tp.textSize.toInt()
            cache[l]?.let { if (it.first == key) return it.second }
            val s = format(l)
            val lay = StaticLayout.Builder.obtain(Tr.span(s), 0, Tr.span(s).length, tp, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setLineSpacing(0f, 1.12f).build()           // spec: line spacing 1.12
            cache[l] = key to lay
            return lay
        }

        /** chat40: the refresh notice's "!" button (empty = none on show) */
        private val bang = RectF()
        /** P2: ([x], [y]) in the pane's px is on the "!" button (a hold there is the button's, never edit mode's) */
        fun onBang(x: Float, y: Float): Boolean = !bang.isEmpty && notice != null && RectF(bang).apply { inset(-10f, -10f) }.contains(x, y)
        private var onBang = false

        override fun onTouchEvent(e: MotionEvent): Boolean {
            // chat40: a touch that starts on the refresh notice's "!" is its press (the game's Refresh now), never the Chat room; a
            // little slack round the button (a finger is wider than its picture)
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onBang = !bang.isEmpty && notice != null &&
                RectF(bang).apply { inset(-10f, -10f) }.contains(e.x, e.y)
            if (onBang) {
                if (e.actionMasked == MotionEvent.ACTION_UP) { onBang = false; noticeGo() }
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) onBang = false
                return true
            }
            // a tap opens the full CHAT ROOM (the user, 2026-10-05: "Tap Home's chat panel", now that the Controller room has Chat's
            // spot on the bar). combat-touch (2026-11-13): in a fight too, as the D-pad's "chat" does; show() hands over fight-safe
            if (e.actionMasked == MotionEvent.ACTION_UP) openRoom(Room.CHAT)
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
        private val tp by themed { TextPaint(UiKit.bodyPaint(23f, Theme.textDim)) }

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
            // the open quest panel lies ABOVE the buffs (the user: "when a quest is open the buffs should be behind it"): the pane lies
            // exactly over the map, so the panel's box is the same in both; the pane leaves it out
            val cover = if (fullMap() && lent(mapView)) mapView.panelCover() else null
            if (cover != null) { c.save(); c.clipOutRect(cover); drawPane(c); c.restore() } else drawPane(c)
            if (cover != null || coverWas) { coverWas = cover != null; postInvalidateDelayed(100) }   // follows the panel's box
            // map's quest arrow slides along the small map's edge away from these boxes (map's rimKeepOut; the pane lies exactly
            // over the map, so its coordinates are the map's). An unchanged list costs nothing (map compares). NOT the + / - column
            // (the user on the test build, 2026-10-12: "The quest arrow is getting stuck on the + and - buttons. It should show on
            // the edge of the screen for those"): the arrow keeps the true edge there, under the buttons, which rest at ZOOM_MIN
            if (lent(mapView) && !fullMap()) mapView.rimKeepOut = if (!mapView.compact) emptyList()
                else listOf(buffBox, debuffBox, panel, warnBox, calBox(), mailBox()).filter { !it.isEmpty }
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
        val fullCursorOn get() = fullCur != null || calCur || mailCur
        private val buffRects = ArrayList<RectF>(); private val debuffRects = ArrayList<RectF>()

        /** the open map closed: the cursor leaves the auras */
        fun leaveFull() { fullCur = null; calCur = false; mailCur = false; mailCard(false); close(); if (lent(mapView)) mapView.hostZoomHidden = false }

        /** the open map's buffs page has somewhere to land: an aura, the mail envelope or the Calendar button */
        fun hasStop(): Boolean = auras.shownBuffs.isNotEmpty() || auras.shownDebuffs.isNotEmpty() || mailShows() || calShows()

        /** D-pad Right from the map: onto the buffs' leftmost icon on their first line (the debuffs' when there are no buffs) */
        fun enterFull() {
            val nb = minOf(auras.shownBuffs.size, FULL_PER); val nd = minOf(auras.shownDebuffs.size, FULL_PER)
            fullCur = when { nb > 0 -> false to nb - 1; nd > 0 -> true to nd - 1; else -> null }
            // the Calendar button (HomeCalendarButton): with no auras the cursor goes straight onto it - onto the mail envelope first
            // while it shows (HomeMailButton: it stands left of the Calendar's stop)
            mailCur = fullCur == null && mailShows()
            calCur = fullCur == null && !mailCur && calShows()
            invalidate()
        }

        /** the auras' first row's right end (the buffs', the debuffs' when there are no buffs), or null = none */
        private fun firstRowEnd(): Pair<Boolean, Int>? =
            when { auras.shownBuffs.isNotEmpty() -> false to 0; auras.shownDebuffs.isNotEmpty() -> true to 0; else -> null }

        /** the mail went (HomeView.mail): a cursor on the envelope moves onto the Calendar button's stop (it never rests on nothing) */
        fun mailGone() {
            if (!mailCur) return
            mailCur = false
            calCur = calShows()
            invalidate()
        }

        /** one press while the cursor is on an aura (or on the Calendar button, or on the mail envelope) */
        fun fullKey(k: String) {
            // the mail card opened by a touch while the cursor is elsewhere: B closes it first (and does nothing else); any other key
            // closes it and goes on (the card never hangs over a moving cursor)
            if (mailOpen && !mailCur) { mailCard(false); if (k == "PAD2") { invalidate(); return } }
            if (mailCur) {
                // THE MAIL ENVELOPE's stop (HomeMailButton): A opens / closes its card, B closes the card first, then goes back to the
                // map; Left / Right leave it (and the card closes)
                when (k) {
                    "PADDLEFT" -> { mailCur = false; mailCard(false); fullCur = firstRowEnd() }
                    "PADDRIGHT" -> if (calShows()) { mailCur = false; mailCard(false); calCur = true }
                    "PAD1" -> mailCard(!mailOpen)
                    "PAD2" -> if (mailOpen) mailCard(false) else mailCur = false
                }
                invalidate(); return
            }
            if (calCur) {
                when (k) {
                    // Left: the mail envelope while it shows, else the buffs' first row's right end (the debuffs' when there are no
                    // buffs), else back to the map
                    "PADDLEFT" -> {
                        calCur = false
                        if (mailShows()) mailCur = true else fullCur = firstRowEnd()
                    }
                    "PAD1" -> { calCur = false; onCalendar?.invoke() }          // A: the Calendar room
                    "PAD2" -> calCur = false                                       // B: back to the map
                }
                invalidate(); return
            }
            val (deb, i) = fullCur ?: return
            val n = (if (deb) auras.shownDebuffs else auras.shownBuffs).size
            val other = (if (deb) auras.shownBuffs else auras.shownDebuffs).size
            when (k) {
                "PADDLEFT" -> fullCur = if (i + 1 < n && (i + 1) % FULL_PER != 0) deb to i + 1 else null      // past the leftmost: the map
                "PADDRIGHT" -> if (i % FULL_PER > 0) fullCur = deb to i - 1
                    else if (i < FULL_PER && (!deb || auras.shownBuffs.isEmpty()) && calShows()) {
                        // the mail envelope while it shows (it stands first), else the Calendar button
                        fullCur = null; if (mailShows()) mailCur = true else calCur = true
                    }
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
            // the Calendar button has the corner (HomeCalendarButton.fullBox): the rows end left of it - and left of the mail envelope
            // while it shows (HomeMailButton.fullBox)
            val right = w - FULL_RIGHT - (if (calShows()) HomeCalendarButton.FULL_SHIFT else 0f) - (if (mailShows()) HomeMailButton.SHIFT else 0f)
            if (calShows()) calBox().let { b -> UiKit.calendarButton(c, b, calPending, focus = calCur && open == null, down = calDown) }
            if (mailShows()) mailBox().let { b -> UiKit.mailButton(c, b, lit = mailOpen, focus = mailCur && open == null, down = mailDown) }
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
            // THE MAIL CARD (HomeMailButton) under the envelope, over the rows (the list and the card are never open together)
            if (mailOpen && mailShows()) mail?.let { ml ->
                val b = mailBox()
                UiKit.mailCard(c, b.right, b.bottom + HomeMailButton.CARD_GAP, 16f, ml.heading, ml.from)
            }
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
                // 1.0.10-test3 (the user: "The background of the buffs needs to have a low opacity like the focused quest"): the
                // focused quest card's see-through plate over the map (its alpha, its soft dark band); the icons and times stay whole
                UiKit.seeThroughPlate(c, RectF(l0 - 6f, y - 6f, r0 + 6f, y + AURA_ICON + timeH + 2f), UiKit.CARD_OVER_MAP_ALPHA)
            }
            for (k in 0 until n) {
                val line = k / per; val col = k % per
                val y = lineTop(line)
                val r = RectF(xOf(col), y, xOf(col) + AURA_ICON, y + AURA_ICON)
                rects?.add(RectF(r))
                if (k >= shown.size) {
                    val pm = UiKit.numberPaint(26f, Theme.text).apply { textAlign = Paint.Align.CENTER; this.alpha = alpha }
                    c.drawText(Tr.t("+$more"), r.centerX(), r.centerY() - (pm.ascent() + pm.descent()) / 2f, pm)
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
                    c.drawText(Tr.t(a.stacks.toString()), r.right - 3f, r.bottom - 4f, ps)
                }
                // the time left under the icon as the game's buff frame writes it: yellow, white in the last minute
                auras.leftAt(a, now)?.toInt()?.let { left ->
                    val pt = UiKit.numberPaint(19f, if (left < 60) Color.WHITE else Theme.gold).apply { textAlign = Paint.Align.CENTER; this.alpha = alpha }
                    c.drawText(Tr.t(shortTime(left)), r.centerX(), r.bottom + timeH - 5f, pt)
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
            c.drawText(Tr.t(if (debuff) "Debuffs" else "Buffs"), inner.left + 12f, inner.top + 36f, pt)
            if (greyed) {
                val pn = UiKit.bodyPaint(21f, Theme.dim).apply { textAlign = Paint.Align.RIGHT }
                c.drawText(Tr.t("Before the fight"), inner.right - 12f, inner.top + 34f, pn)
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
                c.drawText(Tr.t(TextUtils.ellipsize(n, p, inner.width() - 24f, TextUtils.TruncateAt.END).toString()),
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
                val lay = StaticLayout.Builder.obtain(Tr.span(text), 0, Tr.span(text).length, tp, (textRight - r.left - 24f).toInt().coerceAtLeast(10))
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

        private var coverWas = false

        // ---- THE CALENDAR BUTTON (HomeCalendarButton): its touch box here (the pane lies exactly over the map), its stop on the open
        // map's cursor
        /** a finger is down on it (its press look) */
        var calDown = false
            private set
        private var calTracking = false
        /** where the button shows in the pane: the band's corner at rest / in a fight, the corner by the buffs in the open map */
        private fun calShows(): Boolean = shown?.let { it.full == null || it.takeover || fullMap() } == true && zoneText.isNotEmpty()
        /** its box in the pane (the compact map: under the band's end; the open map: [HomeCalendarButton.fullBox]); empty = not shown */
        fun calBox(): RectF {
            if (!calShows() || width <= 0) return RectF()
            val b = if (fullMap()) HomeCalendarButton.fullBox(width.toFloat(), FULL_RIGHT, FULL_TOP)
                else HomeCalendarButton.box(width.toFloat(), 0f)
            return RectF(b[0], b[1], b[2], b[3])
        }
        private fun calHit(x: Float, y: Float): Boolean {
            if (!calShows() || open != null) return false
            // the mail envelope beside it takes the corner's finger box from the middle of their gap leftwards (HomeMailButton.split)
            val m = mailShows()
            if (fullMap()) return HomeCalendarButton.inBox(x, y, HomeCalendarButton.fullBox(width.toFloat(), FULL_RIGHT, FULL_TOP), 12f) &&
                (!m || x >= HomeMailButton.fullSplit(width.toFloat(), FULL_RIGHT))
            return if (m) HomeMailButton.inCalHit(x, y, width.toFloat()) else HomeCalendarButton.inHit(x, y, width.toFloat())
        }
        private fun calTouch(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    calTracking = calHit(e.x, e.y)
                    if (calTracking) { calDown = true; this@HomeView.invalidate(); invalidate() }
                    return calTracking
                }
                MotionEvent.ACTION_MOVE -> if (calTracking) {
                    val d = calHit(e.x, e.y)
                    if (d != calDown) { calDown = d; this@HomeView.invalidate(); invalidate() }
                    return true
                }
                MotionEvent.ACTION_UP -> if (calTracking) {
                    calTracking = false
                    val go = calDown && calHit(e.x, e.y)
                    calDown = false; this@HomeView.invalidate(); invalidate()
                    if (go) { Log.i(TAG, "home: the Calendar button touched${if (grow.fighting(System.currentTimeMillis())) " (in a fight)" else ""}"); onCalendar?.invoke() }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> if (calTracking) { calTracking = false; calDown = false; this@HomeView.invalidate(); invalidate(); return true }
            }
            return false
        }
        /** the open map's cursor is on the button (Right from the buffs' first row's right end; straight on with no auras) */
        var calCur = false
            private set

        // ---- THE MAIL ENVELOPE (HomeMailButton; the user, 2026-11-16: "we need to add an icon somewhere to the home menu for when the
        // player has mail"): beside the Calendar button wherever that shows, while the game says there is mail. Its touch here (the pane
        // lies exactly over the map), its stop on the open map's cursor (left of the Calendar's)
        /** a finger is down on it (its press look) */
        var mailDown = false
            private set
        private var mailTracking = false
        /** the open map's cursor is on the envelope (Right from the buffs' first row's right end; straight on with no auras) */
        var mailCur = false
            private set
        private fun mailShows(): Boolean = calShows() && mail?.on == true
        /** its box in the pane (the compact map: left of the Calendar button; the open map: [HomeMailButton.fullBox]); empty = not shown */
        fun mailBox(): RectF {
            if (!mailShows() || width <= 0) return RectF()
            val b = if (fullMap()) HomeMailButton.fullBox(width.toFloat(), FULL_RIGHT, FULL_TOP)
                else HomeMailButton.box(width.toFloat(), 0f)
            return RectF(b[0], b[1], b[2], b[3])
        }
        private fun mailHit(x: Float, y: Float): Boolean {
            if (!mailShows() || open != null) return false
            if (fullMap()) return HomeCalendarButton.inBox(x, y, HomeMailButton.fullBox(width.toFloat(), FULL_RIGHT, FULL_TOP), 12f) &&
                x < HomeMailButton.fullSplit(width.toFloat(), FULL_RIGHT)
            return HomeMailButton.inHit(x, y, width.toFloat())
        }
        /** its touch: the first touch opens the card, in a fight too (as the Calendar button's); while the card is open Home's own
         *  touch rule closes it before this pane sees anything (HomeView.onInterceptTouchEvent) */
        private fun mailTouch(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mailTracking = mailHit(e.x, e.y)
                    if (mailTracking) { mailDown = true; this@HomeView.invalidate(); invalidate() }
                    return mailTracking
                }
                MotionEvent.ACTION_MOVE -> if (mailTracking) {
                    val d = mailHit(e.x, e.y)
                    if (d != mailDown) { mailDown = d; this@HomeView.invalidate(); invalidate() }
                    return true
                }
                MotionEvent.ACTION_UP -> if (mailTracking) {
                    mailTracking = false
                    val go = mailDown && mailHit(e.x, e.y)
                    mailDown = false; this@HomeView.invalidate(); invalidate()
                    if (go) { Log.i(TAG, "home: the mail envelope touched${if (grow.fighting(System.currentTimeMillis())) " (in a fight)" else ""}"); mailCard(true) }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> if (mailTracking) { mailTracking = false; mailDown = false; this@HomeView.invalidate(); invalidate(); return true }
            }
            return false
        }

        /** P2: ([x], [y]) in the pane's px is one of its own touch areas (a hold there is never edit mode's) */
        fun owns(x: Float, y: Float): Boolean = open != null || zoomIn.contains(x, y) || zoomOut.contains(x, y) ||
            buffBox.contains(x, y) || debuffBox.contains(x, y)

        override fun onTouchEvent(e: MotionEvent): Boolean {
            // a touch on the open quest panel is the panel's (the map below takes it), never a buff's
            if (e.actionMasked == MotionEvent.ACTION_DOWN && fullMap() && lent(mapView) && mapView.panelCover()?.contains(e.x, e.y) == true) return false
            // THE MAIL ENVELOPE's touch (HomeMailButton): the first touch opens its card, in a fight too
            if (mailTouch(e)) return true
            // THE CALENDAR BUTTON's touch (HomeCalendarButton): the first touch opens the Calendar room, in a fight too (D8)
            if (calTouch(e)) return true
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
        QuestTier.UNKNOWN -> Theme.gameGold
    }

    companion object {
        /** chat40: the refresh notice's "!" waits this long for the game's answer before it can be pressed again */
        private const val GO_GUARD_MS = 2_000L
        /** one pick + act pair at a time: the add-on's pick serves one action (CompanionHost's restore uses the same gap) */
        const val WATCH_GAP_MS = 700L
        /** p-grow-glitch: B does not shrink the big controller this soon after it grew */
        private const val GROW_B_GRACE_MS = 2_000L
        /** f-class-radial: a press this long on a pet spell's place toggles its autocast */
        private const val CLASS_LONG_MS = 450L
        /** 1.0.8-test5: the left column's raise while the wheel shows (it slides out from under the chat) */
        private const val CLASS_COVER_Z = 4f
        /** f-radial-middle: the item / spell wheel's slot is never wider than this share of Home */
        private const val WHEEL_SLOT_MAX = 0.6f
        /** the focused quest's card over the map (b121): above the map's 0, under the wheel's lifted left column */
        private const val CARD_Z = 2f
        /** the focused quest over the minimap (Settings > Home, HomeSettings.questOverMap; was the constant QUEST_ABOVE_MAP) */
        fun cardOverMap(): Boolean = renderLook?.let { l -> l.layout?.let { it.card == HomeLayout.CardDock.ON_MAP } ?: l.overMap }
            ?: ((liveDraft ?: HomeSettings.layout).card == HomeLayout.CardDock.ON_MAP)
        /** P2: the live Home's edit draft while edit mode lays the page with it (null = the active record) */
        @Volatile internal var liveDraft: HomeLayout? = null

        /**
         * f-settings-previews (the user: "make our visual aids in the settings less shitty", "Yes, this direction"): the Settings panel's
         * live miniatures are drawn by an offscreen Home ([PreviewStage]) at a LOOK that is not the player's own (the other choice of a
         * setting). The stage sets this for the length of ONE synchronous render on the main thread and clears it; the live Home never
         * renders while it is set, and no setting is ever written. null = the player's settings
         */
        data class Look(val rest: HomeRest, val grow: Boolean, val overMap: Boolean, val swing: Boolean,
                        /** the one-time cast bar question's picture (the user, 2026-11-13: "make sure that this pop up uses realistic
                         *  visuals"): the bar laid on this side, Hide in combat, whatever the player's own setting; null = theirs */
                        val castSide: CastStripModel.Side? = null,
                        /** movable Home: a whole layout record to draw (the Settings tiles' pictures); null = the player's */
                        val layout: HomeLayout? = null)
        internal var renderLook: Look? = null
        /** the Track Pad & Keyboard's own controller guest (Quests.lua's "tool": ZL / ZR kept from the game while it holds) */
        const val TOOL_GUEST = "tool"
        /** logcat tag for Home's taps and refusals (`adb logcat -s WCHOME`) */
        const val TAG = "WCHOME"
        /** #584: how long the map controls wait for the game's stick catch before asking again, and how many asks before the keep-alive */
        const val STICKS_ANSWER_MS = 1_200L
        /** the map module without the controller (Home's B reader): in a fight, and out of one (es / fr in i18n) */
        const val FIGHT_MAP_NOTE = "In a fight the game keeps the controller: press B to close the map"
        const val KEPT_MAP_NOTE = "The game kept the controller: press B to close the map"
        const val STICKS_ASKS = 3
        /** f-trackpad-keyboard: the tool's lift, above the quest card (2) and the class wheel's cover */
        private const val TYPE_Z = 50f
        /** the party member's player menu: over every module (the card at CARD_Z, a sliding column at CLASS_COVER_Z), under the tool */
        private const val PARTY_MENU_Z = 10f
        /** P2: edit mode's chrome, over the party menus and the tool */
        private const val EDIT_Z = 60f
        private val ZERO4 = FloatArray(4)
        /** the edit ticker's frame off a window */
        private const val FRAME_MS = 16L
        /** the card dropped on the map: its own pose hands back to the map's over this long (its plop shows on the way) */
        private const val CARD_RIDE_BACK = 320L
        /** a room's picture on the bar: half its diagonal (its wiggle's turn) */
        private const val ROOM_HALF_DIAG = 40f
        /** P2: an extra's wiggle size (a small picture: the room bar's pace and turn) and the edit row's "+" cell's key */
        private const val EXTRA_HALF_DIAG = 24f
        private const val EDIT_PLUS = "plus"
        /** P2: a lifted widget's depth (above the others, under the chrome) */
        private const val LIFT_Z = 30f
        /** P2: the hold's gold ring round the finger (final_12) */
        private const val RING_R = 44f
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
        /** phase 3's "up" cue: the plate's side, its red cloth and edge (final_10) */
        const val CUE_PX = 64f
        val CUE_FILL = Color.rgb(122, 26, 18)
        val CUE_EDGE = Color.rgb(214, 58, 40)

        /** the "up" plate ([sz] square round [cx], [cy], alpha [a]): an ink rim, the red cloth, the game's gold arrow (Home's cue and
         *  Settings' In fights picture of Top screen) */
        fun upPlate(c: Canvas, cx: Float, cy: Float, sz: Float, a: Float, p: Paint, path: android.graphics.Path) {
            val box = RectF(cx - sz / 2f, cy - sz / 2f, cx + sz / 2f, cy + sz / 2f)
            val al = (255 * a.coerceIn(0f, 1f)).toInt()
            p.style = Paint.Style.FILL; p.color = Theme.ink; p.alpha = al
            c.drawRoundRect(RectF(box.left - 3f, box.top - 3f, box.right + 3f, box.bottom + 3f), 10f, 10f, p)
            p.color = CUE_FILL; p.alpha = al
            c.drawRoundRect(box, 8f, 8f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = CUE_EDGE; p.alpha = al
            c.drawRoundRect(box, 8f, 8f, p)
            path.reset()
            path.moveTo(cx, box.top + sz * 0.22f); path.lineTo(box.right - sz * 0.2f, box.bottom - sz * 0.26f)
            path.lineTo(box.left + sz * 0.2f, box.bottom - sz * 0.26f); path.close()
            p.style = Paint.Style.FILL; p.color = Theme.gameGold; p.alpha = al
            c.drawPath(path, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f; p.color = Theme.ink; p.alpha = al
            c.drawPath(path, p)
        }
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
            // Chat.lua chat37: experience, honor, reputation, skill-ups (the game's ChatTypeInfo colours)
            "xp" to Color.parseColor("#6F6FFF"), "honor" to Color.parseColor("#E0CA0A"),
            "rep" to Color.parseColor("#8080FF"), "skill" to Color.parseColor("#5555FF"),
        )
        private val NUMBERED: Map<Int, Int> = mapOf(
            1 to Color.parseColor("#FFC0C0"), 2 to Color.parseColor("#F5A97A"),
            3 to Color.parseColor("#B6D7A8"), 4 to Color.parseColor("#9FC5E8"),
            5 to Color.parseColor("#D5A6BD"),
        )
    }
}

/** f-class-radial rework: Home's wheel slot, pure (the tests check it fits: HomeSlideTest, HomeMenusFitTest): a square on the line
 *  between the columns under the bars, at most [maxSide]; [Middle.push] / [Middle.pushRight] = how far each column slides out */
internal object ClassSlot {
    data class Slot(val left: Float, val top: Float, val side: Float)
    data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float)
    /** the column's area: the union of its parts' boxes [parts] (l, t, r, b), under the bars; none shown = that half of the page */
    fun column(parts: List<Area>, barsBottom: Float, h: Float, gap: Float, left: Boolean, w: Float): Area =
        if (parts.isEmpty()) (if (left) Area(gap, barsBottom + gap, w / 2f - gap / 2f, h - gap) else Area(w / 2f + gap / 2f, barsBottom + gap, w - gap, h - gap))
        // fix/menus-fit: never below the frame (the wheel ran off it into the room bar when a stray box counted)
        else Area(parts.minOf { it.left }, maxOf(barsBottom + gap, parts.minOf { it.top }), parts.maxOf { it.right }, minOf(h - gap, parts.maxOf { it.bottom }))
    /** the wheel: the largest square in the column's area, centred in it */
    fun inColumn(a: Area): Slot {
        val side = minOf(a.right - a.left, a.bottom - a.top).coerceAtLeast(1f)
        return Slot((a.left + a.right - side) / 2f, (a.top + a.bottom - side) / 2f, side)
    }
    /**
     * f-radial-middle: the item / spell wheel's square between the columns, under the bars ([barsBottom]), at most [maxSide]; [push] =
     * how far the LEFT column parts, [pushRight] the right one (half the wheel and a gap each).
     * fix/menus-fit (the user on 1516: menus "don't fit right with the map swapped and bigger"): centred on the LINE between the columns
     * ([mid], the middle of the gap between the left column's right edge and the right column's left edge), not the page's middle: at a
     * 1/3 or 2/3 share the wider column covered up to 190 px of a wheel centred on w / 2. Kept inside the page; a column parts as far
     * as it needs to clear the square. Classic's line is the page's middle: unchanged.
     */
    data class Middle(val left: Float, val top: Float, val side: Float, val push: Float, val pushRight: Float = push)
    fun middle(w: Float, h: Float, barsBottom: Float, gap: Float, maxSide: Float, bottom: Float = h - gap, mid: Float = w / 2f): Middle {
        val avail = bottom - (barsBottom + gap)
        val side = minOf(avail, maxSide, w - 2f * gap).coerceAtLeast(1f)
        val top = barsBottom + gap + (avail - side) / 2f
        val left = (mid - side / 2f).coerceIn(gap, maxOf(gap, w - gap - side))
        return Middle(left, top, side, maxOf(0f, mid - left + gap), maxOf(0f, left + side - mid + gap))
    }
    /** how far a part of the column slides to be wholly off its own edge ([w] the page's width) */
    fun away(left: Boolean, partLeft: Float, partRight: Float, w: Float, gap: Float): Float =
        if (left) -(partRight + gap) else (w - partLeft + gap)
}

/**
 * fix/castbar-slides (the user on 1.0.31: "When holding LB it slides right but it should only slide the direction if its on that half. If
 * its set to both it can stay in place. The rb plus l2 class pet attack bar is also behaving weird now with the right side menu stuff
 * sliding the both bar to the right and the circle going the full screen size"): which half of Home a part belongs to for the side
 * slides (the LB picture, the class wheels, the middle wheel). Every part was put on a half by its centre; Both's bar spans the whole
 * width, its centre is the page's middle (not left of it), so it counted as the RIGHT column: RB + RT's wheel took the union of the right
 * column AND the full-width bar as its area (a square as tall as the page) and slid the bar away with the map. Now the cast bar's half
 * is its side: Left goes with the chat, Right with the minimap, Both with neither (it stays); a bar slid away for a fight is no
 * column's (it is below the page). The pause wheel's full slide keeps cb3's choice (Both goes left with the chat, pauseSlide).
 */
internal object HomeSlide {
    enum class Half { LEFT, RIGHT, NONE }
    /** fix/menus-fit: [strip] = the part is a tap-to-cast bar (either view: the Split bar's second half too); [split] = the bar is laid
     *  in two halves, each under its column (by its centre). A bar slid below the page for a fight is NO column's, split or not: the
     *  class wheel counted Fighter's hidden halves into its column and ran off the frame into the room bar */
    fun half(strip: Boolean, side: CastStripModel.Side, hidden: Boolean, centerX: Float, w: Float, split: Boolean = false): Half = when {
        !strip -> if (centerX < w / 2f) Half.LEFT else Half.RIGHT
        hidden -> Half.NONE
        split -> if (centerX < w / 2f) Half.LEFT else Half.RIGHT
        side == CastStripModel.Side.LEFT -> Half.LEFT
        side == CastStripModel.Side.RIGHT -> Half.RIGHT
        else -> Half.NONE
    }
    /** a side slide's offset for [h]: the left half goes left [px], the right half right ([rightPx], [px] unless given), a part of
     *  neither stays */
    fun dx(h: Half, px: Float, rightPx: Float = px): Float = when (h) { Half.LEFT -> -px; Half.RIGHT -> rightPx; Half.NONE -> 0f }
    /** fix/menus-fit: a part that spans the whole page (a full-screen module) - the only one that keeps its NAME's side in the slides;
     *  a 1/3 or 2/3 column straddles the middle but belongs to the half its centre is in */
    fun fullWidth(left: Float, right: Float, w: Float, gap: Float): Boolean = left <= gap + 1f && right >= w - gap - 1f
    /** the middle wheel's lowest edge: above Both's laid bar (it stays), else the page's foot */
    fun middleBottom(side: CastStripModel.Side, hidden: Boolean, stripTop: Float?, h: Float, gap: Float): Float =
        if (side == CastStripModel.Side.BOTH && !hidden && stripTop != null) minOf(h - gap, stripTop - gap) else h - gap
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
