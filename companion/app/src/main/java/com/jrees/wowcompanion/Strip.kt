package com.jrees.wowcompanion

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * The permanent bottom strip.
 *
 * Grammar carried over from the Guild Wars companion, which the user signed off and directs: a
 * room icon never changes identity; and the round disc is HOME, the page the bottom screen rests
 * on (Room.HOME, the home session's HomeView); it is never an icon in the row.
 *
 * THE SHAPE (user, 2026-09-28, picked from three drawn at real size): HOME sits in the MIDDLE,
 * larger than the bar's buttons; half of the rooms are to its left and half to its right.
 * TALLER (user, 2026-09-29 09:23, typed: "the buttons bar should be a bit taller, lets try moving
 * the XP bar to be flush with the top of the home button to give space for the other buttons to be
 * bigger"): the bar is [BAR] high, the tracking bar its top [TRACKS] px, flush with the top of
 * HOME's ring; under it a full finger ([BUTTONS]) for the rooms' pictures. HOME no longer stands
 * over the room: [RISE] is 0 and nothing of the bar lies on a room.
 *
 * EVERY ROOM IS ON THE BAR (user, 2026-09-28, typed: "We can fit them all if we remove the text
 * from them and make them look like pushed buttons when they get selected"; then picked "All
 * twelve, 89 px each", knowing it is under the 111 px floor in WIDTH, for the bar only). So no
 * room shows its name here: the open room's picture is PUSHED IN (the game's pressed frame, the
 * picture a little darker and set down and right). Each room has an equal share of its side's
 * width and the bar's whole height for the finger.
 *
 * THE TRACKING BAR (user, 2026-09-28: "3, inside the bar"; then typed: "We don't need numbers on
 * the bars ... a larger xp bar that's cut in half if a rep bar is tracked and it gets replaced
 * by rep bar only when the xp bar is gone"; "By height"): the top [TRACKS] px of the bar hold
 * ONE experience bar across the whole width, behind HOME. While a faction is tracked the two
 * share that height, the faction's bar over the experience bar. When there is no experience bar
 * (the top level) the faction's bar has the whole height. No numbers, and no touch: a touch on
 * the bars is a touch on the room's picture under them.
 *
 * THE ART (user ruling 2026-09-27): a flat bar, and each room is one of the game's own item
 * icons from the UI kit. The stroke glyphs below are what is drawn when the kit is not on the
 * device, so a room is never without its mark.
 */
class Strip(context: Context, private val onPick: (Room) -> Unit) : View(context) {

    var active: Room = Room.MAP
        set(v) { field = v; invalidate() }

    /**
     * What a tracking bar shows: [kind] as `UiKit.track` names its fills ("xp", "rested", "red", "orange", "yellow",
     * "green", "blue"), [fill] 0..1, [reach] 0..1 = where the rested bonus ends (0 = none).
     */
    class Track(val kind: String, val fill: Float, val reach: Float = 0f)

    /** the experience / reputation bar's segments: TEN, standing apart (the user, 2026-09-29: "we can make the gaps between bars larger
     *  for like 10 total bars instead of what evers going on right now"); the game's own bar has twenty with a pip between */
    private val SEGMENTS = 10
    private val SEGMENT_GAP = 10f
    /** a hairline on each edge of a segment gap (px), 0 = the gap alone; the user found the pips "chonky" */
    var segmentEdge = 1.5f
        set(v) { if (field != v) { field = v; invalidate() } }

    /** the experience bar; null = there is none (no document yet, or the top level) */
    var xp: Track? = null
        set(v) { field = v; invalidate() }

    /** the tracked faction's bar; null = no faction is shown as the experience bar */
    var reputation: Track? = null
        set(v) { field = v; invalidate() }

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f; strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pArt = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    // a room that is not the open one: the same icon, turned down
    private val pArtDim = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.7f, 0.7f, 0.7f, 1f) })
    }
    // the open room's picture, pushed in: a little darker, under the pressed frame
    private val pArtDown = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.82f, 0.82f, 0.82f, 1f) })
    }

    private val hit = HashMap<Room, RectF>()
    // each room's picture where it is drawn (not pushed in), for [place]
    private val pics = HashMap<Room, RectF>()

    /**
     * WHERE THINGS ARE ON THE BAR (home session, for its bar selector, LATER item 7; read-only): a room's picture as drawn
     * (not pushed in), or for Room.HOME the disc's square, its ring included. null before the first draw.
     */
    fun place(r: Room): RectF? {
        if (r != Room.HOME) return pics[r]?.let { RectF(it) }
        if (homeY == 0f) return null
        val q = HOME / 2f + RING
        return RectF(homeX - q, homeY - q, homeX + q, homeY + q)
    }

    /**
     * B WOULD RETURN HOME (user, 2026-09-29 09:23, typed: "change the little house in the home Icon to
     * be the B button glyph when pressing it would return home"): while true, HOME's disc shows the
     * game's B glyph, in the layout the Settings chose, in place of the house. The disc's touch is
     * unchanged. The home session sets it from interact's route.
     */
    var bGoesHome = false
        set(v) { if (field != v) { val t = now(); val was = redB; val from = redNow(t); field = v; flowIf(was, from, t) } }
    /** the HOME session's bar cursor holds the controller (the user: "it should show red with the B glyph"): Home looks exactly as
     *  while B leads Home - B closes the cursor */
    var barB = false
        set(v) { if (field != v) { val t = now(); val was = redB; val from = redNow(t); field = v; flowIf(was, from, t) } }
    private val redB get() = bGoesHome || barB
    /** test26: the red / B look goes to its target AT ONCE, no flow (a wheel's close: the disc comes back from under the hub blue) */
    fun snapRed() { redAt = 0L; invalidate() }
    /** the red / B state changed ([was] -> [redB]): its flow starts from where the red stood ([from]) */
    private fun flowIf(was: Boolean, from: Float, t: Long) {
        if (redB != was) { redFrom = from; redAt = t }
        invalidate()
    }

    /**
     * THE RED COMES AND GOES AS A FLOW (the user, 2026-10-01: "the Blue to red is a bit too instant, and should kind of flow red and then
     * face in the B glyph"): the red light seeps out from the swirl along the cracks (the noise tile + a radial term, thresholded with a
     * soft edge) over [RED_IN], then the B fades in (scale 0.85 -> 1) over [B_IN]; going back, the B fades out over [B_OUT], then the red
     * recedes over [RED_OUT]. [redNow] = the red's share at a time, 0 blue .. 1 red. The target is [redB] (B leads Home, or Home's bar
     * cursor holds the controller).
     */
    private var redAt = 0L
    private var redFrom = 0f
    private fun redNow(t: Long): Float {
        val target = if (redB) 1f else 0f
        if (redAt == 0L) return target
        val dt = (t - redAt).toFloat()
        val k = if (redB) curve.getInterpolation((dt / RED_IN).coerceIn(0f, 1f))
            else curve.getInterpolation(((dt - B_OUT) / RED_OUT).coerceIn(0f, 1f))
        return redFrom + (target - redFrom) * k
    }
    /** the B's alpha 0..1 at [t]: after the red is in (in), before it recedes (out) */
    private fun bNow(t: Long): Float {
        if (redAt == 0L) return if (redB) 1f else 0f
        val dt = (t - redAt).toFloat()
        return if (redB) ((dt - RED_IN) / B_IN).coerceIn(0f, 1f) else (1f - dt / B_OUT).coerceIn(0f, 1f) * (if (redFrom > 0.5f) 1f else 0f)
    }
    private val curve = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /** the preview's fixed clock (kitpreview only: frames at exact times); < 0 = the real clock */
    var previewNow = -1L
    private fun now(): Long = if (previewNow >= 0) previewNow else SystemClock.uptimeMillis()

    /**
     * Kitpreview: the disc [ms] into one of its motions: "redin" (B comes to lead Home), "redout" (it stops), "press" (B pressed while it
     * leads Home: the push, the gold flash, then back to blue). The clock then stands still at that moment.
     */
    fun previewMotion(kind: String, ms: Long) {
        val base = 10_000_000L
        // from a clean blue state each time (the preview draws many frames with one bar)
        previewNow = base - 100_000L; bGoesHome = false; barB = false; redAt = 0L; pressAt = 0L; focusAt = 0L
        when (kind) {
            "redin" -> { previewNow = base - ms; bGoesHome = true }
            "redout" -> { previewNow = base - 5000L; bGoesHome = true; previewNow = base - ms; bGoesHome = false }
            "press" -> { previewNow = base - 5000L; bGoesHome = true; previewNow = base - ms; homePressed(); bGoesHome = false }
        }
        previewNow = base; invalidate()
    }

    /**
     * THE CURSOR ON HOME (LATER item 22, the user, 2026-09-30: "when it gets pressed or if the cursor is over it, then it should
     * have a golden animation"; picked "A, swirl light turns gold and breathes"): while true, the swirl's light turns gold
     * (0.25 s) and breathes (0.6 times a second, up to 30 % brighter); false fades it back (0.25 s). The home session sets it
     * from its bar selector.
     */
    var homeFocus = false
        set(v) { if (field != v) { field = v; focusAt = now(); invalidate() } }
    private var focusAt = 0L
    private var pressAt = 0L

    /** a press of Home (the controller's A on it; a touch on the disc does this itself): one bright gold flash, gone in 0.75 s */
    fun homePressed() {
        pressAt = now(); invalidate()
    }

    /** the rooms (by their label) that carry the game's mark of something that waits: an invitation in the Calendar */
    private val marked = HashSet<String>()

    /** puts the mark on a room's picture ([room] = its `Room` label, "CALENDAR"), or takes it off; the mark never takes a touch */
    fun mark(room: String, on: Boolean) {
        if (if (on) marked.add(room) else marked.remove(room)) invalidate()
    }
    private var homeX = 0f
    private var homeY = 0f
    private var glyph = 20f / 24f          // the stand-in glyphs' scale, from their 24-unit grid

    private fun onHome(x: Float, y: Float): Boolean {
        val dx = x - homeX; val dy = y - homeY
        val r = HOME / 2f + RING
        return dx * dx + dy * dy <= r * r
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_DOWN) return true
        if (onHome(e.x, e.y)) { homePressed(); onPick(Room.HOME); return true }
        if (e.y < height - BUTTONS) return true                 // the tracking bar: no touch of its own
        for ((r, box) in hit) if (box.contains(e.x, e.y)) { onPick(r); return true }
        return true
    }

    override fun onDraw(c: Canvas) {
        drawBar(c)
        nextLiveFrame()
    }

    private fun drawBar(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val top = h - BAR                                       // the bar's top edge: the tracking bar's top
        pFill.color = Theme.panel
        c.drawRect(0f, top, w, h, pFill)

        homeX = w / 2f
        homeY = top + RING + HOME / 2f                          // the ring's top is flush with the tracking bar's top

        // the tracking bar in the bar's top; HOME's button covers its middle, so its scale skips it
        val g0 = homeX - HOME / 2f - RING - 2f; val g1 = homeX + HOME / 2f + RING + 2f
        val x = xp; val f = reputation
        val all = RectF(0f, top, w, top + TRACKS)
        when {
            x != null && f != null -> {                         // cut in half by height: the faction over the experience
                UiKit.track(c, RectF(0f, top, w, top + TRACKS / 2f), f.kind, f.fill, 0f, SEGMENTS, g0, g1, SEGMENT_GAP, segmentEdge)
                UiKit.track(c, RectF(0f, top + TRACKS / 2f, w, top + TRACKS), x.kind, x.fill, x.reach, SEGMENTS, g0, g1, SEGMENT_GAP, segmentEdge)
            }
            x != null -> UiKit.track(c, all, x.kind, x.fill, x.reach, SEGMENTS, g0, g1, SEGMENT_GAP, segmentEdge)
            f != null -> UiKit.track(c, all, f.kind, f.fill, 0f, SEGMENTS, g0, g1, SEGMENT_GAP, segmentEdge)
            else -> UiKit.track(c, all, "xp", -1f, 0f, SEGMENTS, g0, g1, SEGMENT_GAP, segmentEdge)
        }

        // WHICH SIDE OF HOME a room stands on does not change as rooms are added (user: Character
        // "Right of Home, before Bags"): the right side begins with Character, or with Bags while there
        // is no Character room. In `Room`'s order: Map, Quests, Cast, Spellbook, Professions, Group
        // Finder | Character, Bags, Chat, Social, Calendar, Settings.
        // HOME is the disc, never a room in the row (the home session's Room.HOME)
        val rooms = Room.values().filter { it != Room.HOME && it.onBar }      // a room off the bar (Chat) is reached another way
        var half = rooms.indexOfFirst { it.label == "CHARACTER" }
        if (half < 0) half = rooms.indexOfFirst { it.label == "BAGS" }
        if (half < 0) half = (rooms.size + 1) / 2
        hit.clear(); pics.clear()
        val cy = top + TRACKS + BUTTONS / 2f
        group(c, rooms.take(half), 8f, homeX - HOME / 2f - 14f, top, h, cy)
        group(c, rooms.drop(half), homeX + HOME / 2f + 14f, w - 8f, top, h, cy)

        // the consumables radial has lifted the disc out (it draws the disc that rises and becomes its hub): nothing here
        if (homeLifted) return
        // HOME in the game's look when its pieces are on the device; else the flat disc and the house
        if (drawKitHome(c)) return
        // HOME, over the bar and the rooms' icons: its ring on the bar's colour, the disc, the house
        pFill.color = Theme.panel
        c.drawCircle(homeX, homeY, HOME / 2f + RING, pFill)
        pStroke.color = Theme.bronze; pStroke.strokeWidth = 2f
        c.drawCircle(homeX, homeY, HOME / 2f + RING - 1f, pStroke)
        pFill.color = Color.parseColor("#46361A")
        c.drawCircle(homeX, homeY, HOME / 2f, pFill)
        pStroke.strokeWidth = 3f
        c.drawCircle(homeX, homeY, HOME / 2f - 1.5f, pStroke)
        if (redB && UiKit.tex("pad_" + backGlyph()) != null) {
            UiKit.pad(c, backGlyph(), homeX, homeY, HOME * 0.62f)
            return
        }
        pStroke.strokeWidth = 3.2f; pStroke.color = Theme.gold
        glyph = HOME / 56f * 20f / 24f * 1.1f
        icon(c, Room.MAP, homeX, homeY, true, home = true)
    }

    /**
     * HOME'S GLOW-UP (the user, 2026-09-29 15:17: "The home button itself needs a glow up to actually fit the classic wow
     * asthetic. maybe the wow forver logo or a hearthstone crack with cracked stone look? idk"): the game's round portrait
     * ring on dark stone, with the picture [homeLook] names; while B leads Home, the B glyph in the picture's place. The
     * pieces are `home_*` in the kit (made by `home_kit.py`). false = a piece is not on the device: the caller draws the flat disc.
     */
    private fun drawKitHome(c: Canvas): Boolean {
        val r = HOME / 2f + RING
        if (homeLook.startsWith("stone_")) {
            // ROUND 3 (the user, 15:54: "I want the whole homebutton to look like stone, I dont like the golden outline"): one
            // picture of the whole stone button with the swirl split into it; the _b picture (no swirl) under the B glyph
            val box = RectF(homeX - r, homeY - r, homeX + r, homeY + r)
            // THE PRESS (the user, 2026-10-01: "Pushing B should have the button push effect"): the button sinks to 0.92 and a soft
            // shadow gathers at its lower edge, then it comes back
            val t = now()
            val push = pushNow(t)
            val save = c.save()
            if (push > 0f) { val sc = 1f - 0.08f * push; c.scale(sc, sc, homeX, homeY + 2f * push) }
            if (!drawLiveHome(c, box, homeAlive)) {
                val face = UiKit.tex("home_" + homeLook + if (redB) "_b" else "") ?: return false
                c.drawBitmap(face, null, box, pBmp)
            }
            if (push > 0f) {
                pShade.shader = RadialGradient(homeX, homeY - r * 0.35f, r * 1.25f, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT,
                    Color.argb((150 * push).toInt(), 0, 0, 0)), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(homeX, homeY, r, pShade)
            }
            // while B leads Home the stone's light is red (LATER 22, the user: "it should still turn red but look the same"), with
            // the B small on the button's lower left (the user, 2026-09-29 17:30: "with the B offset like it is on the other buttons");
            // it fades in after the red has flowed (0.85 -> 1) and out before it recedes ([bNow])
            val bA = bNow(t)
            if (bA > 0f) {
                val bs = r * 0.69f * (0.85f + 0.15f * bA)
                UiKit.pad(c, backGlyph(), homeX - r * 0.57f, homeY + r * 0.49f, bs, (255 * bA).toInt())
            }
            c.restoreToCount(save)
            if (push > 0f || bA in 0.01f..0.99f) animating = true
            return true
        }
        val ring = UiKit.tex("home_ring") ?: return false
        val stone = UiKit.tex(if (homeLook == "rune_plain" || homeLook == "hearth" || homeLook == "forever") "home_stone"
            else "home_stone_cracked") ?: return false
        val pic = UiKit.tex(when (homeLook) { "forever" -> "home_forever"; "hearth", "cracked" -> "home_hearth"; else -> "home_rune" })
            ?: return false
        val box = RectF(homeX - r, homeY - r, homeX + r, homeY + r)
        c.drawBitmap(stone, null, box, pBmp)
        if (redB && UiKit.tex("pad_" + backGlyph()) != null) UiKit.pad(c, backGlyph(), homeX, homeY, HOME * 0.62f)
        else c.drawBitmap(pic, null, box, pBmp)
        c.drawBitmap(ring, null, box, pBmp)
        return true
    }

    /**
     * Home's picture. "rune" = THE USER'S PICK (15:25, changed 15:27: "I want the swirl on the hearthstone with the blue glow to
     * literally look like its etched into the stone button"): the swirl etched into the cracked stone; "rune_plain" = the same on
     * uncracked stone. "stone_tablet" / "stone_rough" / "stone_ring" = round 3, the whole button stone (15:54); THE USER PICKED
     * "stone_rough" (16:07: "A rough rock"). Kept for the kit
     * preview: "hearth", "cracked" (the hearthstone picture), "forever" (the Forever crest).
     */
    /**
     * THE DISC IS UP (Home's consumables radial, UiKit.consumableRadial, the user's v4 2026-10-05): the radial draws the disc that
     * rises out of the bar and becomes its hub, so the bar draws none. Home sets it when the radial opens and clears it when a
     * close has run back to its start.
     */
    var homeLifted = false
        set(v) { if (field != v) { field = v; invalidate() } }

    var homeLook = "stone_rough"
        set(v) { if (field != v) { field = v; invalidate() } }
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * THE LIVING HOME BUTTON (the user, 2026-09-29 17:52: "i want the animation to be the glow that is currently there, I also want it
     * to exist for the X"; "Yes, like that" to the clip): the light in the cracks flows. BASE (the button with its light at 0.4) is
     * drawn, then FLARE (the light alone) is ADDED twice, each time through a copy of the NOISE tile scrolling its own way:
     * light = 0.4 + 1.15 x (noiseA + noiseB) / 2 of the still picture's. Kit pictures `home_live_base / _flare` (the swirl) and
     * `home_live_red_base / _flare` (the light red, while B leads Home), `home_live_gold_base / _flare` (the cursor's gold),
     * `home_live_noise`; made by home_kit.py. Only while the bar is on screen: [onVisibilityAggregated] stops it when the bar is
     * hidden or the app is in the background. 15 frames a second, 30 while the gold comes, goes or flashes.
     * false = a piece is missing: the caller draws the still picture.
     */
    var homeAlive = true
        set(v) { if (field != v) { field = v; invalidate() } }
    private var shownNow = false
    private var animating = false
    private var liveDelay = 66L
    private val pLive = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val pFade = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val mFlare = Matrix()
    private val mNoise = Matrix()
    private val mGain = ColorMatrix()
    private var goldK = 0f          // the gold's share of the light now, 0..1
    private var goldGain = 1f       // the light's strength now (the breath, a press's flash)

    /** [flowing] false = the light stands still (one even pass, no noise): the button keeps its colours and its gold, unmoving */
    private fun drawLiveHome(c: Canvas, box: RectF, flowing: Boolean): Boolean {
        // the blue light at rest; the red (while B leads Home or the bar cursor holds, LATER 22) FLOWS over it ([redNow]); until the red pieces are on the
        // device: the blue light (the B glyph still shows), never the retired X
        val base = UiKit.tex("home_live_base") ?: return false
        val flare = UiKit.tex("home_live_flare") ?: return false
        val noise = UiKit.tex("home_live_noise") ?: return false
        val now = now()
        val moving = goldNow(now)
        val red = redNow(now)
        val redBase = UiKit.tex("home_live_red_base"); val redFlare = UiKit.tex("home_live_red_flare")
        val goldBase = UiKit.tex("home_live_gold_base")
        val goldFlare = UiKit.tex("home_live_gold_flare")
        val gold = if (goldBase != null && goldFlare != null) goldK else 0f   // the gold pieces missing: the rest colour, brighter
        val restW = (1f - gold) * goldGain
        c.drawBitmap(base, null, box, pBmp)
        light(c, box, flare, noise, restW, flowing, now)
        if (red > 0.004f && redBase != null && redFlare != null) {
            // the red in its own layer, cut by the flow mask while it comes or goes
            val l = c.saveLayer(box, null)
            c.drawBitmap(redBase, null, box, pBmp)
            light(c, box, redFlare, noise, restW, flowing, now)
            // the light is ADDED with its dark ground: in a layer of its own that ground is opaque, so keep only the button's shape
            pMask.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN); c.drawBitmap(redBase, null, box, pMask); pMask.xfermode = null
            if (red < 0.996f) flowMask(c, box, noise, red)
            c.restoreToCount(l)
        }
        // the bases: the gold's over the rest by its share; then its light ADDED by its share x the gain
        if (gold > 0f) { pFade.alpha = (gold * 255f).toInt(); c.drawBitmap(goldBase!!, null, box, pFade) }
        if (gold > 0f) light(c, box, goldFlare!!, noise, gold * goldGain, flowing, now)
        val dt = (now - redAt).toFloat()
        val redMoving = redAt != 0L && dt < RED_IN + B_IN + B_OUT + RED_OUT
        animating = flowing || moving || redMoving
        liveDelay = if (moving || redMoving) 33L else 66L
        return true
    }

    /**
     * Cuts the layer under it to where the red has flowed. The ARRIVAL FIELD (made once per size): each pixel's grey = (0.7 x the crack
     * noise + 0.6 x its distance from the swirl's middle) / 1.3, so the middle and the dark cracks come first and the rim last; the mask
     * is that field through a soft threshold that rises with [red]: alpha = clamp(4 x (1.25 red - field)).
     */
    private fun flowMask(c: Canvas, box: RectF, noise: Bitmap, red: Float) {
        val w = box.width().toInt().coerceAtLeast(2); val h = box.height().toInt().coerceAtLeast(2)
        val f = field?.takeIf { it.width == w && it.height == h } ?: run {
            val px = IntArray(w * h)
            val nw = noise.width; val nh = noise.height
            val cx = w / 2f; val cy = h / 2f; val rmax = minOf(cx, cy)
            for (y in 0 until h) for (x in 0 until w) {
                val np = noise.getPixel((x * nw / (w * 1.6f)).toInt() % nw, (y * nh / (h * 1.6f)).toInt() % nh)
                val n = (Color.red(np) * 0.3f + Color.green(np) * 0.59f + Color.blue(np) * 0.11f) / 255f
                val d = (kotlin.math.hypot(x - cx, y - cy) / rmax).coerceAtMost(1f)
                val g = ((0.7f * n + 0.6f * d) / 1.3f * 255f).toInt().coerceIn(0, 255)
                px[y * w + x] = Color.argb(255, g, g, g)
            }
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).also { field = it }
        }
        val sThr = 1.25f * red
        pMask.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
            -4f, 0f, 0f, 0f, 1020f * sThr)))
        pMask.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawBitmap(f, null, box, pMask)
        pMask.colorFilter = null; pMask.xfermode = null
    }
    private var field: Bitmap? = null
    private val pShade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pMask = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * The gold's share ([goldK]) and the light's gain ([goldGain]) at [now]: the cursor's crossfade (in 0.12 s, out 0.16 s, on the
     * curve: the user on b98, "the animation still plays a little slow"; it was 0.25 s linear) and its breath (0.6 Hz, +30 %); a press's
     * flash (x 2.3, falling away in 0.18 s, the gold held 0.35 s, then easing back to the rest colour over 0.45 s on the curve: the user,
     * 2026-10-01, "transition from that yellow back to blue"). true = still changing.
     */
    private fun goldNow(now: Long): Boolean {
        val tf = (now - focusAt) / 1000f
        var k: Float
        var gain = 1f
        if (homeFocus) {
            k = curve.getInterpolation((tf / GOLD_IN).coerceIn(0f, 1f))
            gain = 1f + 0.30f * (0.5f - 0.5f * kotlin.math.cos(2f * Math.PI.toFloat() * 0.6f * tf))
        } else k = if (focusAt == 0L) 0f else 1f - curve.getInterpolation((tf / GOLD_OUT).coerceIn(0f, 1f))
        val tp = (now - pressAt) / 1000f
        val flashing = pressAt != 0L && tp < 0.8f
        if (flashing) {
            gain *= 1f + 1.3f * kotlin.math.exp(-tp / 0.18f)
            k = maxOf(k, if (tp < 0.35f) 1f else 1f - curve.getInterpolation(((tp - 0.35f) / 0.45f).coerceIn(0f, 1f)))
        }
        goldK = k; goldGain = gain
        return homeFocus || flashing || k > 0f
    }

    /** the press's depth now, 0..1: in over 70 ms, out over 160 ms (the user: "Pushing B should have the button push effect") */
    private fun pushNow(t: Long): Float {
        if (pressAt == 0L) return 0f
        val dt = (t - pressAt).toFloat()
        return when { dt < 0f -> 0f; dt < 70f -> dt / 70f; dt < 230f -> 1f - curve.getInterpolation((dt - 70f) / 160f); else -> 0f }
    }

    /** one colour's light, ADDED at [weight]: through the two scrolling noise copies, or once and even when not [flowing] */
    private fun light(c: Canvas, box: RectF, flare: Bitmap, noise: Bitmap, weight: Float, flowing: Boolean, now: Long) {
        if (weight <= 0.004f) return
        mGain.setScale(weight, weight, weight, 1f)
        pLive.colorFilter = if (weight == 1f) null else ColorMatrixColorFilter(mGain)
        val k = box.width() / flare.width                                     // screen px per button px
        val n = noise.width.toFloat()
        mFlare.setScale(k, k); mFlare.postTranslate(box.left, box.top)
        val fs = BitmapShader(flare, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(mFlare) }
        if (!flowing) {
            pLive.shader = fs
            c.drawRect(box, pLive)
        } else {
            // seconds, wrapped every 64 s: every offset below is then a whole number of tiles, so the wrap is seamless
            val t = (now % 64_000L) / 1000f
            // copy A: noise (u, v) = (x + 18 t, y + 8 t); copy B: (u, v) = (0.7 x - 10 t, 0.7 y + 14 t), x, y in button px
            for (b in 0..1) {
                mNoise.reset()
                if (b == 0) {
                    mNoise.setScale(k, k)
                    mNoise.postTranslate(box.left - k * ((t * 18f) % n), box.top - k * ((t * 8f) % n))
                } else {
                    mNoise.setScale(k / 0.7f, k / 0.7f)
                    mNoise.postTranslate(box.left + k * ((t * 10f) % n) / 0.7f, box.top - k * ((t * 14f) % n) / 0.7f)
                }
                val ns = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply { setLocalMatrix(mNoise) }
                pLive.shader = ComposeShader(fs, ns, PorterDuff.Mode.MULTIPLY)
                c.drawRect(box, pLive)
            }
        }
        pLive.shader = null
        pLive.colorFilter = null
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shownNow = isVisible
        if (isVisible) invalidate()
    }

    /** the next frame of the living Home button: 15 a second (30 while the gold moves), only while the bar is on screen */
    private fun nextLiveFrame() {
        if (animating && shownNow) postInvalidateDelayed(liveDelay)
        animating = false
    }

    /**
     * The rooms of one side of HOME, from [x0] to [x1], each with an equal share of the width. A
     * room's touch box is its share, from the bar's top edge ([top]) to the screen's edge ([bottom]).
     */
    private fun group(c: Canvas, rooms: List<Room>, x0: Float, x1: Float, top: Float, bottom: Float, cy: Float) {
        if (rooms.isEmpty()) return
        val cell = (x1 - x0) / rooms.size
        // as large as the finger's height allows ([ICON]), and never so large that the pressed frame
        // (FRAME times the picture) reaches into the next room's share
        val ICON = minOf(ICON, (cell - 4f) / FRAME)
        glyph = (ICON - 8f) / 46f * 20f / 24f
        for ((i, r) in rooms.withIndex()) {
            val left = x0 + i * cell
            hit[r] = RectF(left, bottom - BUTTONS, left + cell, bottom)
            val open = r == active
            val cx = left + cell / 2f
            pics[r] = RectF(cx - ICON / 2f, cy - ICON / 2f, cx + ICON / 2f, cy + ICON / 2f)
            if (open && iconSet >= 2) {
                // the game's radial-menu pictures (LATER 18): pushed in = a sunken well under the picture, set down and right
                // (set 3: the disc itself is pushed in, darker, its ring dim; no square under it)
                if (iconSet == 2) sunken(c, RectF(cx - cell / 2f + 6f, cy - ICON / 2f - 6f, cx + cell / 2f - 6f, cy + ICON / 2f + 8f))
                if (!art(c, r, cx + 2f, cy + 3f, ICON, true)) icon(c, r, cx + 2f, cy + 2f, true)
            } else if (open) {
                // pushed in: the picture set down and right, the game's pressed frame over it
                if (!art(c, r, cx + 2f, cy + 2f, ICON, true)) {
                    pStroke.color = Theme.gold; pStroke.strokeWidth = 1.8f
                    icon(c, r, cx + 2f, cy + 2f, true)
                }
                val frame = UiKit.tex("focus_frame")
                val s = ICON * FRAME
                if (frame != null) c.drawBitmap(frame, null, RectF(cx + 1f - s / 2f, cy + 1f - s / 2f, cx + 1f + s / 2f, cy + 1f + s / 2f), pArt)
                else {
                    pStroke.color = Theme.gold; pStroke.strokeWidth = 3f
                    c.drawRect(cx - ICON / 2f - 3f, cy - ICON / 2f - 3f, cx + ICON / 2f + 5f, cy + ICON / 2f + 5f, pStroke)
                }
            } else if (!art(c, r, cx, cy, ICON, false)) {
                pStroke.color = Theme.textDim; pStroke.strokeWidth = 1.8f
                icon(c, r, cx, cy, false)
            }
            // the game's own mark of an invitation that waits (user pick 2026-09-29), at the picture's upper right
            if (r.label in marked) {
                val m = UiKit.tex("cal_invite")
                val k = ICON / 64f
                if (m != null) c.drawBitmap(m, null, RectF(cx + 12f * k, cy - 40f * k, cx + 46f * k, cy + 6f * k), pArt)
                else { pFill.color = Theme.gold; c.drawCircle(cx + 30f * k, cy - 24f * k, 9f * k, pFill) }
            }
        }
    }

    /** the room's icon from the UI kit, [size] px square; false when the kit is not on the device */
    private fun art(c: Canvas, r: Room, cx: Float, cy: Float, size: Float, active: Boolean): Boolean {
        if (iconSet >= 2) UiKit.tex("ricon_" + r.label.lowercase())?.let { bmp ->
            // THE GAME'S CONTROLLER RADIAL-MENU PICTURES (LATER 18): one artist, one light; kept in proportion inside the square
            // (they are not square), no item frame. Set 3 stands each on the radial menu's own dark disc in a bronze ring.
            if (iconSet == 3) {
                pFill.color = if (active) Color.rgb(6, 5, 3) else Color.rgb(16, 12, 9); c.drawCircle(cx, cy, size * 0.52f, pFill)
                pStroke.color = if (active) Theme.bronzeDim else Color.rgb(150, 122, 72); pStroke.strokeWidth = 2.5f
                c.drawCircle(cx, cy, size * 0.52f, pStroke)
            }
            val fit = size * (if (iconSet == 3) 0.74f else 0.92f)
            val k = minOf(fit / bmp.width, fit / bmp.height)
            val w = bmp.width * k; val h = bmp.height * k
            c.drawBitmap(bmp, null, RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f), if (active) pArtDown else pArtDim)
            return true
        }
        val bmp = UiKit.tex("icon_" + r.label.lowercase()) ?: return false
        val box = RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        c.drawBitmap(bmp, null, box, if (active) pArtDown else pArtDim)
        // item icons are square plates and get the thin frame the game gives them; the quest
        // mark is a bare "!" and does not. The open room has the pressed frame instead
        if (r != Room.QUESTS && !active) {
            pStroke.strokeWidth = 1.5f
            pStroke.color = Theme.bronzeDim
            c.drawRect(box, pStroke)
        }
        return true
    }

    /** the open room's sunken well (picture sets 2 and 3): darker than the bar, a dark top edge and a faint light bottom edge */
    private fun sunken(c: Canvas, r: RectF) {
        pFill.color = Color.rgb(8, 6, 4); c.drawRoundRect(r, 12f, 12f, pFill)
        pStroke.strokeWidth = 2f
        pStroke.color = Color.rgb(0, 0, 0); c.drawLine(r.left + 10f, r.top + 1f, r.right - 10f, r.top + 1f, pStroke)
        pStroke.color = Theme.bronzeDim; c.drawRoundRect(r, 12f, 12f, pStroke)
    }

    /** the game's B glyph in the layout the Settings chose: Nintendo's B, Xbox's B, PlayStation's circle */
    private fun backGlyph(): String = UiKit.backGlyph()

    /** simple stroke glyphs, one identity per room, drawn at a 24-unit nominal size */
    private fun icon(c: Canvas, r: Room, cx: Float, cy: Float, active: Boolean, home: Boolean = false) {
        val s = glyph       // scale from the 24-unit design grid
        fun X(v: Float) = cx + (v - 12f) * s
        fun Y(v: Float) = cy + (v - 12f) * s
        val p = pStroke
        if (home) {
            val path = Path().apply {
                moveTo(X(3f), Y(11f)); lineTo(X(12f), Y(3f)); lineTo(X(21f), Y(11f))
                moveTo(X(5f), Y(10f)); lineTo(X(5f), Y(20f)); lineTo(X(19f), Y(20f)); lineTo(X(19f), Y(10f))
            }
            c.drawPath(path, p); return
        }
        when (r) {
            Room.HOME -> {}          // drawn as the disc above, never in the row
            Room.MAP -> {
                c.drawCircle(cx, cy, 9f * s * 1.2f, p)
                val path = Path().apply {
                    moveTo(X(15.4f), Y(8.6f)); lineTo(X(13.3f), Y(13.4f))
                    lineTo(X(8.5f), Y(15.5f)); lineTo(X(10.6f), Y(10.7f)); close()
                }
                c.drawPath(path, p)
            }
            Room.QUESTS -> {
                // WoW's own quest mark: a tapered "!" -- every player reads it as "quests"
                val path = Path().apply {
                    moveTo(X(10f), Y(3f)); lineTo(X(14f), Y(3f)); lineTo(X(13f), Y(15f))
                    lineTo(X(11f), Y(15f)); close()
                }
                c.drawPath(path, p)
                c.drawCircle(X(12f), Y(19.5f), 1.9f * s, p)
            }
            Room.TALENTS -> {
                // a talent tree until the kit's icon_talents arrives: a root, two branches, three nodes
                val path = Path().apply {
                    moveTo(X(12f), Y(21f)); lineTo(X(12f), Y(13f))
                    moveTo(X(12f), Y(13f)); lineTo(X(6f), Y(7f))
                    moveTo(X(12f), Y(13f)); lineTo(X(18f), Y(7f))
                }
                c.drawPath(path, p)
                c.drawCircle(X(6f), Y(6f), 2.2f * s, p); c.drawCircle(X(18f), Y(6f), 2.2f * s, p); c.drawCircle(X(12f), Y(13f), 2.2f * s, p)
            }
            Room.BAGS -> {
                val path = Path().apply {
                    moveTo(X(5.5f), Y(8f)); lineTo(X(18.5f), Y(8f))
                    lineTo(X(17.3f), Y(20f)); lineTo(X(6.7f), Y(20f)); close()
                    moveTo(X(9f), Y(8f)); cubicTo(X(9f), Y(4f), X(15f), Y(4f), X(15f), Y(8f))
                }
                c.drawPath(path, p)
            }
            Room.CHAT -> {
                val path = Path().apply {
                    moveTo(X(20f), Y(11.5f))
                    cubicTo(X(20f), Y(16f), X(16.5f), Y(18.5f), X(13f), Y(18.5f))
                    lineTo(X(8.5f), Y(18.5f)); lineTo(X(4.5f), Y(21f)); lineTo(X(4.5f), Y(17.4f))
                    cubicTo(X(2f), Y(15f), X(3f), Y(4.5f), X(8f), Y(4.5f))
                    lineTo(X(13f), Y(4.5f)); cubicTo(X(17.5f), Y(4.5f), X(20f), Y(7f), X(20f), Y(11.5f))
                    close()
                }
                c.drawPath(path, p)
            }
            Room.SETTINGS -> {
                // a gear: eight teeth around a ring, with a hub
                val path = Path()
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val a0 = a - 0.2; val a1 = a + 0.2
                    val ri = 7.2f; val ro = 10f
                    path.moveTo(X(12f + ri * Math.cos(a0).toFloat()), Y(12f + ri * Math.sin(a0).toFloat()))
                    path.lineTo(X(12f + ro * Math.cos(a0).toFloat()), Y(12f + ro * Math.sin(a0).toFloat()))
                    path.lineTo(X(12f + ro * Math.cos(a1).toFloat()), Y(12f + ro * Math.sin(a1).toFloat()))
                    path.lineTo(X(12f + ri * Math.cos(a1).toFloat()), Y(12f + ri * Math.sin(a1).toFloat()))
                }
                c.drawPath(path, p)
                c.drawCircle(cx, cy, 7.2f * s, p)
                c.drawCircle(cx, cy, 3f * s, p)
            }
            // a room that has no stand-in of its own yet (a NEW room): a book. So a session can add
            // its room to `Room` without touching this file; its picture comes from the kit anyway
            else -> {
                val path = Path().apply {
                    moveTo(X(5f), Y(4f)); lineTo(X(19f), Y(4f)); lineTo(X(19f), Y(20f)); lineTo(X(5f), Y(20f)); close()
                    moveTo(X(8.5f), Y(4f)); lineTo(X(8.5f), Y(20f))
                }
                c.drawPath(path, p)
            }
        }
    }

    companion object {
        /** the tracking bar: the top of the bar, flush with the top of HOME's ring */
        const val TRACKS = 34
        /** the rooms' pictures: a full finger (48 dp at 369 dpi) under the tracking bar */
        const val BUTTONS = 111
        /** the bar: the tracking bar and the rooms' finger. The rooms end on its top edge */
        const val BAR = TRACKS + BUTTONS
        /** how far HOME stands over the room above the bar: nothing now (the user, 2026-09-29) */
        const val RISE = 0
        /** the view's height */
        const val HEIGHT = BAR + RISE
        /**
         * The rooms' pictures (LATER 18, the user 2026-10-01: "the home rooms icons need some work to look better together ( some of them
         * need different icons to better match the actual wow ui for those rooms)"): 1 = today's (icon_*, item icons in their frames);
         * 2 = the game's controller radial-menu set (ricon_*, gamepad-radial-icon-*-c60), bare; 3 = the same on a disc in a ring.
         * THE USER PICKED 2, "A: bare icons" (2026-10-01, via tracking). A room whose ricon_ picture is not on the device yet keeps
         * today's icon_ picture (art() falls back), so the bar never goes blank before the push.
         */
        @JvmStatic var iconSet = 2
        private const val ICON = 88f        // a room's picture, at most (twelve rooms make it smaller: see group)
        /** the red's flow and the B's fade, ms (the user, 2026-10-01; Home's numbers) */
        private const val RED_IN = 420f
        private const val B_IN = 160f
        private const val B_OUT = 100f
        private const val RED_OUT = 300f
        /** the cursor's gold on the Home disc: in / out, seconds, on the curve (the user on b98: "the animation still plays a little slow") */
        private const val GOLD_IN = 0.12f
        private const val GOLD_OUT = 0.16f
        private const val FRAME = 1.18f     // the pressed frame round the open room's picture
        private const val HOME = 128f       // HOME's disc
        private const val RING = 6f         // the ring round it
    }
}
