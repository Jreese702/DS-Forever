package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * THE CENTRE BLOCK UNDER A CAST (fix/badges-cast; the user on test build 1523, 2026-11-23: "The icons shift smaller when casting but still
 * overlap with the cast text instead of shifting up. The shift back also doesn't happen correctly when completing a cast" - "the middle
 * things we added": the clock chip and the extras badges). On the real [HomeView] + [CastView], attached to a window (its own re-checks
 * run on the main looper), with the page drawn only where the device would draw Home (a Home invalidate: never for the cast bar's own
 * frames, which are the Cast view's):
 *  - while a cast shows, nothing the block draws lands on the cast bar or the lane its name plate takes (the plate stands ON the bar, in
 *    the gap between the two button groups), nor on a button or an L / R plate - Classic, swapped, a Same-size fight, Party mode's fight,
 *    1 .. 4 extras and the pet, gold on / off, short and long names, the shrink itself included;
 *  - after the cast ends (completed, interrupted, a channel, a cast whose end word comes late or never) the block grows back exactly to the
 *    layout it had before the cast, starting once the name is gone and done one grow later - with no Home redraw in between.
 * Pixels, not boxes: the block's ink is the difference between the frame and the same frame with the block left out, so the checks hold
 * whatever the code's layout arithmetic. With BADGES_CAST_SHOTS (+ CLOCK_ART_ROOT / HOME_LAYOUT_ART, BADGES_CAST_TAG) the pictures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class BadgesCastTest {
    private val out: File? = System.getenv("BADGES_CAST_SHOTS")?.let { File(it).apply { mkdirs() } }
    private val tag = System.getenv("BADGES_CAST_TAG") ?: "after"
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private val G = 5000.0
    /** the cast bar's figures (CastBarPlace / CastFx: internal to the app's module): the frame's width, the bar and its gap, the name
     *  plate's height (frame px); how long a finish / an interrupt shows (the name fading with it) */
    private val FRAME_W = 1240f; private val BAR = 14f; private val BAR_GAP = 2f; private val PLATE_H = 30f
    private val FINISH_MS = 500L; private val FAIL_MS = 1300L

    private val artWas: File? = ArtHarvest.rootDir
    @After fun down() {
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, artWas)
        setField(PartyModel.live, "party", null); HomeLayoutStore.character(null); HomeLayoutStore.set(HomeLayout()); ItemCd.reset()
        // 1524 final: the extras switches back to their defaults (all on). HomeSettings keeps the FIRST test's prefs (init sets them only
        // while null), so page()'s clear of this test's "home" file never reaches them: the pet switch this class turns off stayed off for
        // the next class (BottomTilesTest.theModel counted "4 / 6", not "5 / 6", in one JVM after this class)
        HomeSettings.clockHearth = true; HomeSettings.clockTeleport = true; HomeSettings.clockGold = true; HomeSettings.clockBags = true
        setting("clockAmmo", true); setting("clockPet", true)
    }

    private fun art() {
        val root = System.getenv("CLOCK_ART_ROOT") ?: System.getenv("HOME_LAYOUT_ART") ?: return
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root))
    }

    private fun lay(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, W, H)
    }
    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)
    private fun setField(o: Any, name: String, v: Any?) { o.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(o, v) }
    private fun now() = android.os.SystemClock.elapsedRealtime()
    /** time goes by in 16 ms frames, the main looper's due work run each frame (Home is NOT drawn: nothing here draws it) */
    private fun run(ms: Long) {
        var left = ms
        while (left > 0) { val d = minOf(16L, left); ShadowSystemClock.advanceBy(Duration.ofMillis(d)); ShadowLooper.idleMainLooper(); left -= d }
    }

    private enum class Fight { REST, SAME, PARTY }
    private class Page(val home: HomeView, val cast: CastView, val at: Long, val fight: Boolean)

    private val roster = listOf(
        """{"u":"player","i":0,"n":"Korvash","c":"SHAMAN","role":"HEALER","pt":"MANA","lv":20,"dead":false,"off":false,"col":"ff0070dd"}""",
        """{"u":"party1","i":1,"n":"Brannoc","c":"WARRIOR","role":"TANK","pt":"RAGE","lv":21,"dead":false,"off":false,"col":"ffc69b6d"}""")
    private fun partyDoc(): JSONObject {
        val m = JSONArray(); for (r in roster) m.put(JSONObject(r))
        return JSONObject().put("type", "party").put("v", "m77").put("rs", 1)
            .put("p", JSONObject().put("on", true).put("box", 1).put("fight", false).put("grp", true).put("sel", "bound").put("m", m))
    }

    /** a HomeSettings switch by its name (false: this code has no such row) */
    private fun setting(name: String, on: Boolean): Boolean {
        val m = HomeSettings::class.java.methods.firstOrNull { it.name == "set" + name.replaceFirstChar { c -> c.uppercase() } } ?: return false
        m.invoke(HomeSettings, on); return true
    }

    /**
     * Home on a window: [extras] of hearth / teleport / bags / ammo / pet on (no cooldowns: nothing on the page ticks), [gold] the chip's
     * gold line, [rest] the band's look, [fight] the fight state (Same size: the band keeps its rest size; Party mode's fight)
     */
    private fun page(layout: HomeLayout = HomeLayout(), extras: Set<String> = setOf("hearth", "teleport", "bags", "ammo"), gold: Boolean = true,
                     fight: Fight = Fight.REST, pet: Boolean = extras.contains("pet")): Page {
        art()
        app.getSharedPreferences("home", 0).edit().clear().commit()
        HomeSettings.init(app); Settings.init(app); Tr.init(app)
        HomeSettings.clockHearth = "hearth" in extras; HomeSettings.clockTeleport = "teleport" in extras; HomeSettings.clockGold = gold
        HomeSettings.clockBags = "bags" in extras; setting("clockAmmo", "ammo" in extras); setting("clockPet", "pet" in extras)
        ItemCd.reset()
        ItemCd.onDoc(JSONObject("""{"type":"itemcd","hs":{"have":true,"i":134414},"tp":{"id":556,"n":"Astral Recall","i":136010}""" +
            (if (pet) ""","pet":{"h":3}""" else "") + "}"))
        HomeLayoutStore.character(null)
        HomeLayoutStore.set(if (fight == Fight.SAME) layout.copy(fight = HomeLayout.FightBand.SAME) else layout)
        setField(PartyModel.live, "party", null)
        if (fight == Fight.PARTY) PartyModel.live.onParty(PartyModel.parseParty(partyDoc())!!, true, System.currentTimeMillis())
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val top = FrameLayout(act); act.setContentView(top)
        val map = MapView(act); val cast = CastView(act) { }; val chat = ChatView(act) { }
        val holder = FrameLayout(act); holder.addView(map); holder.addView(cast); holder.addView(chat); top.addView(holder)
        val home = HomeView(act, map, cast, chat) { }
        top.addView(home, FrameLayout.LayoutParams(W, H))
        home.borrow(); lay(home)
        val icons = listOf(136048, 136026, 135990, 136052, 135813, 136015, 135824, 136075)
        val n = JSONArray()
        for (q in 1..8) n.put(JSONObject().put("p", q).put("k", "spell").put("id", 400 + q).put("i", icons[q - 1]))
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", n))))
        val eq = JSONObject().put("slots", JSONArray()).put("ammoNeeded", true)
            .put("ammo", JSONObject().put("id", 2512).put("icon", 132382).put("count", 1843).put("stack", 200))
        val bags = JSONArray().put(bag(0, 16, 2)).put(bag(1, 18, 1)).put(bag(2, 16, 2)).put(bag(3, 16, 1))
            .put(bag(4, 16, 0).put("fam", 1).put("icon", 134407))
        val at = now()
        val ab = JSONObject().put("hud", JSONObject().put("ok", true)).put("hudS", JSONObject().put("g", G).put("c", fight != Fight.REST))
        home.onFull(JSONObject().put("type", "full").put("actionbar", ab)
            .put("bags", JSONObject().put("money", 1_234_567L).put("bags", bags).put("items", JSONArray()).put("equipped", eq)), true)
        cast.update(ab)
        if (fight == Fight.PARTY) home.partyChanged()
        home.tick(); ShadowLooper.idleMainLooper(); lay(home)
        HomeView::class.java.getDeclaredMethod("changed").apply { isAccessible = true }.invoke(home)
        run(600); lay(home)
        val p = Page(home, cast, at, fight != Fight.REST)
        render(p); Thread.sleep(150); ShadowLooper.idleMainLooper(); run(400)
        return p
    }
    private fun bag(b: Int, slots: Int, free: Int) = JSONObject().put("bag", b).put("slots", slots).put("free", free)

    /** the game's clock now (the docs keep the one offset the page started with) */
    private fun g(p: Page) = G + (now() - p.at) / 1000.0

    private fun hudS(p: Page, put: JSONObject.() -> Unit) {
        val st = JSONObject().put("g", g(p)).put("c", p.fight).apply(put)
        val ab = JSONObject().put("hud", JSONObject().put("ok", true)).put("hudS", st)
        p.home.onFull(JSONObject().put("type", "full").put("actionbar", ab), true)
        p.cast.update(ab)
        ShadowLooper.idleMainLooper()
    }
    /** a cast (or a channel, [channel]) [ms] long starting now */
    private fun startCast(p: Page, name: String, ms: Long = 2500L, channel: Boolean = false) = hudS(p) {
        put("cs", JSONObject().put("k", if (channel) "h" else "c").put("s", g(p) - 0.01).put("e", g(p) + ms / 1000.0).put("nm", name))
    }
    /** the game's word that the cast ended now: [done] = completed (else interrupted / failed), [channel] = a channel's end */
    private fun endCast(p: Page, done: Boolean, channel: Boolean = false) = hudS(p) {
        put("ce", JSONObject().put("t", g(p)).put("x", if (done) 1 else 0).put("h", if (channel) 1 else 0))
    }
    /** the cast's own document going on (no end word: the cast's line, with nothing new) */
    private fun keepCast(p: Page, name: String, startedAt: Long, ms: Long) = hudS(p) {
        val s = G + (startedAt - p.at) / 1000.0
        put("cs", JSONObject().put("k", "c").put("s", s - 0.01).put("e", s + ms / 1000.0).put("nm", name))
    }

    /** the page drawn as the device draws Home (the room bar under it) */
    private fun render(p: Page): Bitmap {
        val b = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        setField(p.home, "clockText", "10:42 AM"); setField(p.home, "batteryPct", 76); setField(p.home, "charging", false)
        lay(p.home)
        val c = Canvas(b); c.drawColor(Theme.ink); p.home.draw(c)
        val st = Strip(app) { }
        st.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Strip.HEIGHT, View.MeasureSpec.EXACTLY))
        st.layout(0, 0, W, Strip.HEIGHT)
        c.save(); c.translate(0f, H.toFloat()); st.draw(c); c.restore()
        return b
    }

    /** the same frame with the block left out (the chip: no time; the row: faded as under a trigger), Home's state put back after */
    private fun renderBare(p: Page): Bitmap {
        val th = field(p.home, "trigHeld"); val tf = field(p.home, "trigFrom"); val ta = field(p.home, "trigAt")
        setField(p.home, "trigHeld", true); setField(p.home, "trigFrom", 0f)
        val b = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        setField(p.home, "clockText", ""); setField(p.home, "batteryPct", 76)
        lay(p.home)
        val c = Canvas(b); c.drawColor(Theme.ink); p.home.draw(c)
        setField(p.home, "trigHeld", th); setField(p.home, "trigFrom", tf); setField(p.home, "trigAt", ta)
        setField(p.home, "clockText", "10:42 AM")
        return b
    }

    /** the block's ink: where [a] and [bare] differ by more than [over] in a channel, as a mask */
    private fun ink(a: Bitmap, bare: Bitmap, over: Int = 3): Array<BooleanArray> = Array(240) { y -> BooleanArray(W) { x ->
        val u = a.getPixel(x, y); val v = bare.getPixel(x, y)
        var d = 0
        for (sh in intArrayOf(0, 8, 16)) d = maxOf(d, kotlin.math.abs(((u shr sh) and 0xFF) - ((v shr sh) and 0xFF)))
        d > over } }
    private fun inkIn(m: Array<BooleanArray>, r: RectF): Int {
        var n = 0
        for (y in maxOf(0, r.top.toInt()) until minOf(m.size, kotlin.math.ceil(r.bottom).toInt()))
            for (x in maxOf(0, r.left.toInt()) until minOf(W, kotlin.math.ceil(r.right).toInt())) if (m[y][x]) n++
        return n
    }
    private fun inkAt(m: Array<BooleanArray>, r: RectF): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (y in maxOf(0, r.top.toInt()) until minOf(m.size, kotlin.math.ceil(r.bottom).toInt()))
            for (x in maxOf(0, r.left.toInt()) until minOf(W, kotlin.math.ceil(r.right).toInt())) if (m[y][x] && out.size < 8) out += x to y
        return out
    }
    private fun inkBox(m: Array<BooleanArray>): RectF? {
        var l = W; var t = m.size; var r = -1; var b = -1
        for (y in m.indices) for (x in 0 until W) if (m[y][x]) { l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y) }
        return if (r < 0) null else RectF(l.toFloat(), t.toFloat(), r + 1f, b + 1f)
    }

    /** the Cast view's top left on Home */
    private fun castAt(p: Page): FloatArray {
        var x = 0f; var y = 0f; var v: View = p.cast
        while (v !== p.home) { x += v.left + v.translationX; y += v.top + v.translationY; v = v.parent as View }
        return floatArrayOf(x, y)
    }

    /**
     * THE CAST'S OWN PLACE at rest, on Home: the bar (the band's full width, its strip under the buttons) and the lane its name plate
     * takes (the TextBox plate standing on the bar, the whole gap between the two button groups wide - any name, short or long)
     */
    private fun castZones(p: Page): List<RectF> {
        val o = castAt(p)
        val w = p.cast.width.toFloat(); val h = p.cast.height.toFloat(); val s = w / FRAME_W
        val gl = field(p.cast, "gapL") as Float; val gr = field(p.cast, "gapR") as Float
        val bar = RectF(o[0], o[1] + h - (BAR_GAP + BAR) * s, o[0] + w, o[1] + h - BAR_GAP * s)
        val lane = RectF(o[0] + gl, bar.top - 2f * s - PLATE_H * s, o[0] + gr, bar.top)
        return listOf(bar, lane)
    }

    /** the 8 buttons and the L / R plates on Home */
    private fun buttons(p: Page): List<RectF> {
        val o = castAt(p)
        val spot = p.cast.clockSpot()!!
        val out = ArrayList<RectF>()
        for (q in 0 until 8) { val k = 6 + 4 * q; out += RectF(spot[k] + o[0], spot[k + 1] + o[1], spot[k + 2] + o[0], spot[k + 3] + o[1]) }
        for (side in 0..1) { val r = RectF(); p.cast.shoulderPlateRect(side, r); r.offset(o[0], o[1]); out += r }
        return out
    }

    private fun infoGrown(p: Page) = field(p.home, "infoGrown") as Boolean
    private fun infoLinear(p: Page): Float =
        HomeView::class.java.getDeclaredMethod("infoLinear", Long::class.java).apply { isAccessible = true }.invoke(p.home, now()) as Float

    /** the block's ink clear of the cast's place and the buttons, [what] in the messages */
    private fun assertClear(p: Page, what: String, given: Bitmap? = null, cast: Boolean = true) {
        if (given == null) render(p)                                       // (the cast bar's art comes with its first frame)
        val bare = renderBare(p)
        val frame = given ?: render(p)
        val m = ink(frame, bare)
        val box = inkBox(m)
        System.getenv("BADGES_CAST_DEBUG")?.let { d ->
            File(d).mkdirs()
            val mb = Bitmap.createBitmap(W, 240, Bitmap.Config.ARGB_8888)
            for (y in 0 until 240) for (x in 0 until W) mb.setPixel(x, y, if (m[y][x]) -1 else (0xFF shl 24))
            val nm = what.replace(Regex("[^A-Za-z0-9]+"), "_").take(60)
            File(d, "${nm}_mask.png").outputStream().use { mb.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(d, "${nm}_frame.png").outputStream().use { bandCrop(frame).compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(d, "${nm}_bare.png").outputStream().use { bandCrop(bare).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue("$what: the block is drawn", box != null)
        val (bar, lane) = castZones(p)
        assertEquals("$what: block ink on the cast bar $bar (block $box)", 0, inkIn(m, bar))
        // (the lane's edge columns are partly outside it: the antialiased clip edge there is not counted)
        if (cast) assertEquals("$what: block ink on the cast name's lane $lane (block $box)", 0,
            inkIn(m, RectF(lane.left + 1f, lane.top, lane.right - 1f, lane.bottom)))
        // the buttons and plates: the block's real ink (its icons, frames, text) - not the faint rim of a ready Hearthstone's halo, which
        // today's rest and LB + RB rows have always let reach the D-pad's Right's corner (the lifted row keeps even that off: ClockBandTest)
        val strong = ink(frame, bare, 48)
        for ((i, b) in buttons(p).withIndex()) {
            val r = RectF(b.left + 1f, b.top + 1f, b.right - 1f, b.bottom - 1f)
            assertEquals("$what: block ink on ${if (i < 8) "button $i" else "plate ${i - 8}"} $b (block $box) at ${inkAt(strong, r)}", 0, inkIn(strong, r))
        }
    }

    private fun bandCrop(b: Bitmap) = Bitmap.createBitmap(b, 0, 0, W, 240)
    private fun same(a: Bitmap, b: Bitmap): Int {
        var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        return n
    }

    private fun save(b: Bitmap, name: String) {
        val d = out ?: return
        File(d, "${tag}_$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val band = Bitmap.createBitmap(b, 300, 0, 640, 240)
        File(d, "${tag}_${name}_band.png").outputStream().use { Bitmap.createScaledBitmap(band, 1280, 480, true).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * the player's own order with the Hearthstone in the middle, for the checks made while the block MOVES: a ready Hearthstone at the
     * left end has its halo (1.1 icons round its middle) reach the D-pad's Right's corner in today's rest and LB + RB rows - as it always
     * has, not this fix's - and a move starts and ends at those rows. The settled lifted row keeps even the halo off (checked above with
     * the Hearthstone first, and in ClockBandTest)
     */
    private val MIDDLE = HomeLayout(extras = listOf("teleport", "bags", "hearth", "ammo", "pet"))
    private val SHORT = "Frostbolt"
    private val LONG = "Summon Felsteed of the Burning Legion"

    // ------------------------------------------------------------------ while the cast shows

    /** the user's case and every count: the block settled under a cast clear of the bar, the name's lane, the buttons and the plates */
    @Test fun castLeavesTheBarAndItsNameClear() {
        val sets = listOf(setOf("bags"), setOf("hearth", "bags"), setOf("hearth", "bags", "ammo"), setOf("hearth", "teleport", "bags", "ammo"),
            setOf("hearth", "teleport", "bags", "ammo", "pet"))
        for (ex in sets) for (gold in listOf(true, false)) for (name in listOf(SHORT, LONG)) {
            val p = page(extras = ex, gold = gold)
            startCast(p, name); run(400)
            assertFalse("a cast: the block small", infoGrown(p))
            assertClear(p, "extras $ex gold=$gold '$name'")
        }
    }

    /** chat / map swapped, a Same-size fight (the band at its rest size: the cast at the strip, the clock on), Party mode's fight */
    @Test fun castLeavesTheNameClearInEveryLayout() {
        val full = setOf("hearth", "teleport", "bags", "ammo", "pet")
        for (layout in listOf(HomeLayout(), HomeLayout().swapped())) for (fight in Fight.values()) for (gold in listOf(true, false)) {
            val p = page(layout, full, gold, fight)
            if (p.cast.clockSpot() == null) continue
            startCast(p, LONG); run(400)
            assertClear(p, "swapped=${layout.chatSide} $fight gold=$gold")
        }
    }

    /** the shrink itself (from the grown block, every frame of it): the name's lane and the bar never under the block's ink */
    @Test fun theShrinkNeverCrossesTheName() {
        for (ex in listOf(setOf("hearth", "teleport", "bags", "ammo"), setOf("hearth", "teleport", "bags", "ammo", "pet"), setOf("bags")))
            for (gold in listOf(true, false)) {
                val p = page(MIDDLE, extras = ex, gold = gold)
                startCast(p, LONG)
                for (f in 0 until 15) { run(16); assertClear(p, "$ex gold=$gold frame $f of the shrink") }
            }
    }

    /** a cast that starts while LB + RB already hold the block small: it moves up clear too, never jumping */
    @Test fun castWhileShouldersHeld() {
        val p = page(MIDDLE)
        pad(p, lb = true, rb = true); run(400)
        startCast(p, LONG)
        for (f in 0 until 18) { run(16); assertClear(p, "LB+RB held, frame $f") }
        pad(p); run(400)
        assertFalse("still small while the cast shows", infoGrown(p))
        assertClear(p, "LB+RB let go during the cast")
    }

    /** a cast that starts again during the grow back (a chain of casts): clear every frame, and grown as before once the last one ends */
    @Test fun chainedCastsStayClearAndEndGrown() {
        val p = page(MIDDLE)
        val before = bandCrop(render(p))
        startCast(p, LONG, 1000L); run(1000); endCast(p, true); render(p)
        run(FINISH_MS + ClockBand.GROW_MS_IN.toLong() / 3)
        assertTrue("growing back", infoGrown(p) && infoLinear(p) in 0.01f..0.99f)
        render(p)
        startCast(p, SHORT, 800L)
        for (f in 0 until 15) { run(16); assertClear(p, "the second cast, frame $f") }
        run(800 - 15 * 16L); endCast(p, false); render(p)
        run(FAIL_MS + ClockBand.GROW_MS_IN.toLong() + 40)
        assertEquals(1f, if (infoGrown(p)) infoLinear(p) else 0f, 0f)
        assertEquals("the band exactly as before the casts", 0, same(before, bandCrop(render(p))))
    }

    private fun pad(p: Page, lt: Boolean = false, rt: Boolean = false, lb: Boolean = false, rb: Boolean = false) {
        CastView::class.java.getDeclaredMethod("onPad", Boolean::class.java, Boolean::class.java, Boolean::class.java, Boolean::class.java)
            .apply { isAccessible = true }.invoke(p.cast, lt, rt, lb, rb)
        ShadowLooper.idleMainLooper()
    }

    // ------------------------------------------------------------------ after the cast

    /**
     * every way a cast ends: the block small until the name is gone (the finish's fade: FINISH_MS; an interrupt's: FAIL_MS),
     * growing from then, and one grow later exactly the layout it had before the cast - with Home drawn only when the end word came
     */
    private fun endsBack(how: String, gold: Boolean = true, end: (Page) -> Long) {
        val p = page(MIDDLE, gold = gold)
        val before = bandCrop(render(p))
        assertTrue("grown at rest", infoGrown(p))
        val started = now()
        startCast(p, LONG, 1000L)
        run(16); render(p)                                                  // the cast's first frame on Home
        run(600); keepCast(p, LONG, started, 1000L)                         // the game's document goes on (Home redrawn by it)
        render(p)
        run(370)
        val gone = end(p)                                                 // the end word (Home redrawn by it); ms until the name is gone
        render(p)
        run(gone - 40)
        assertFalse("$how: still small while the name fades (${gone - 40} ms after the end)", infoGrown(p) && infoLinear(p) > 0f)
        run(40 + ClockBand.GROW_MS_IN.toLong() / 2)
        val mid = infoLinear(p)
        assertTrue("$how: growing back half a grow after the name went ($mid)", infoGrown(p) && mid > 0.2f && mid < 0.9f)
        // the rest of the grow, every frame drawn (the device draws Home each frame of it): on no button, nor the bar
        for (f in 0 until (ClockBand.GROW_MS_IN.toLong() / 2 / 16).toInt()) { run(16); assertClear(p, "$how: growing, frame $f", cast = false) }
        run(40)
        assertEquals("$how: grown again", 1f, infoLinear(p), 0f)
        val after = bandCrop(render(p))
        assertEquals("$how: the band exactly as before the cast", 0, same(before, after))
        assertClear(p, "$how: grown after", render(p).also { save(it, "after_$how") }, cast = false)
    }

    @Test fun completedCastGrowsBack() = endsBack("completed") { p -> endCast(p, true); FINISH_MS }
    @Test fun completedCastGrowsBackGoldOff() = endsBack("completed_gold_off", gold = false) { p -> endCast(p, true); FINISH_MS }
    @Test fun interruptedCastGrowsBack() = endsBack("interrupted") { p -> endCast(p, false); FAIL_MS }
    @Test fun channelGrowsBack() {
        val p = page()
        val before = bandCrop(render(p))
        startCast(p, "Mind Flay", 3000L, channel = true); run(16); render(p)
        run(1200); endCast(p, true, channel = true); render(p)
        run(FINISH_MS - 40)
        assertFalse("a channel's finish: still small", infoGrown(p) && infoLinear(p) > 0f)
        run(40 + ClockBand.GROW_MS_IN.toLong() + 40)
        assertEquals(1f, infoLinear(p), 0f)
        assertEquals("the channel's end: the band as before", 0, same(before, bandCrop(render(p))))
    }

    /** a cast run to its end whose end word never comes: back once the bar's own tail and the wait for the word are over */
    @Test fun castWithNoEndWordGrowsBack() {
        val p = page()
        val before = bandCrop(render(p))
        startCast(p, SHORT, 1000L); run(16); render(p)
        run(1000 + 2000)
        assertEquals("no end word: grown again", 1f, if (infoGrown(p)) infoLinear(p) else 0f, 0f)
        assertEquals(0, same(before, bandCrop(render(p))))
    }

    /** the end word a little late (the bar's own 100 ms tail over first): no grow-and-shrink-again bounce in between */
    @Test fun lateEndWordNoBounce() {
        val p = page()
        val started = now()
        startCast(p, SHORT, 1000L); run(16); render(p)
        run(1000 + 100 + 150)
        assertFalse("waiting for the end word: still small", infoGrown(p))
        keepCast(p, SHORT, started, 1000L); endCast(p, true); render(p)
        for (t in 0 until (FINISH_MS / 16).toInt() - 2) { run(16); assertFalse("the finish shows: small (frame $t)", infoGrown(p) && infoLinear(p) > 0f) }
        run(200 + ClockBand.GROW_MS_IN.toLong())
        assertEquals(1f, if (infoGrown(p)) infoLinear(p) else 0f, 0f)
    }

    // ------------------------------------------------------------------ the pictures

    /** the pictures; after the end word Home is drawn only when the device would draw it (the word's own redraw, then the shot) */
    @Test fun shots() {
        if (out == null) return
        val p = page()
        save(render(p), "rest")
        startCast(p, SHORT); run(400); save(render(p), "midcast_short")
        val q = page()
        startCast(q, LONG); run(400); save(render(q), "midcast_long")
        endCast(q, true); render(q); run(FINISH_MS / 2); save(render(q), "completing")
        val c1 = page()
        startCast(c1, LONG); run(400); render(c1); endCast(c1, true); render(c1)
        run(FINISH_MS + ClockBand.GROW_MS_IN.toLong() / 2); save(render(c1), "just_after_completion")
        val c2 = page()
        startCast(c2, LONG); run(400); render(c2); endCast(c2, true); render(c2)
        run(FINISH_MS + ClockBand.GROW_MS_IN.toLong() + 300); save(render(c2), "after_completion_settled")
        val r = page()
        startCast(r, LONG); run(400); render(r); endCast(r, false); render(r); run(600); save(render(r), "interrupting")
        val r2 = page()
        startCast(r2, LONG); run(400); render(r2); endCast(r2, false); render(r2)
        run(FAIL_MS + ClockBand.GROW_MS_IN.toLong() + 300); save(render(r2), "after_interrupt")
        val n = page(gold = false, extras = setOf("hearth", "teleport", "bags", "ammo", "pet"))
        startCast(n, LONG); run(400); save(render(n), "midcast_long_5_gold_off")
        val s = page(HomeLayout().swapped(), fight = Fight.SAME)
        startCast(s, LONG); run(400); save(render(s), "midcast_long_swapped_same_fight")
        val party = page(fight = Fight.PARTY)
        startCast(party, LONG); run(400); save(render(party), "midcast_long_party_fight")
        val lr = page()
        pad(lr, lb = true, rb = true); run(400); save(render(lr), "lb_rb_held")
        val row = page(HomeLayout(rest = HomeRest.ROW))
        save(render(row), "row_rest")
        startCast(row, LONG); run(400); save(render(row), "row_midcast_long")
    }
}
