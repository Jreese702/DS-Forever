package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
 * THE PARTY LIST DROPS WITH THE BAR (the user's pick 2026-11-23, "List drops with the bar (Recommended)": "While a wheel is open, the
 * party list and the Across bar both slide down out of the way, so the wheel gets full size. Both come back when it closes."), on the
 * real [HomeView]. Party mode's full-width list docked at the foot (Under chat / Under map) stands right over an Across tap-to-cast bar;
 * with the bar Off it lies at the page's foot. Pinned, for both docks, swapped and a third share, S / M / L slots:
 *  - the middle wheel and both class wheels are the size they have on the same page with the bar Off: the list slides down with the bar
 *    (the same motion, the bar's own distance) into the place it has with no bar, the bar below the page; the list never drawn over the
 *    wheel; both back, and the bar tappable, once it has closed;
 *  - the LB picture as large as with the bar Off: when it needs the room the list leaves the page with the bar, straight down (with the
 *    bar Off it leaves sideways); one that fits over both leaves both where they are (the ruling "Stay when it fits");
 *  - the pause wheel's and the target radial's full slides: the list goes straight down below the page with the bar, never sideways;
 *  - a wheel held into a fight (Grow: the list's rows grow) - the list never drawn over the wheel, on any frame of the page's motion;
 *  - a top-docked full-width list is not moved by any of them;
 *  - the user's picks 2026-11-23: "Split bar too" - every one of the above over Split's two halves as over Across's bar (the halves
 *    under the list are one bar of no column: they drop with it, never part sideways); "Bar Off: stay when it fits" - with no bar
 *    under the foot list the LB picture leaves the list where it is when it fits over it, else the list leaves sideways as before.
 * With PARTY_DROP_SHOTS=<dir> (and CLOCK_ART_ROOT for the real art) the pictures: rest, the middle wheel, the class wheels, the LB
 * picture, the LB picture partway through its open, a held wheel in a Grow fight. [shots] uses only what 0f57b547 had, so the same test
 * draws the "before" pictures there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PartyDropShotsTest {
    private val out: File? = System.getenv("PARTY_DROP_SHOTS")?.let { File(it).apply { mkdirs() } }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private val G = UiKit.MODULE_GAP
    private val GOLD = 5000.0

    @After fun down() {
        setField(PartyModel.live, "party", null); HomeLayoutStore.character(null); HomeLayoutStore.set(HomeLayout())
        CastStripModel.Live.pos = 0f; CastStripModel.Live.side = null; UiKit.pauseBarsBottom = 0f
        if (!GameUi.swWant(GameUi.SW_PMODE)) GameUi.setSwitch(GameUi.SW_PMODE, true)
    }

    private fun lay(v: View, w: Int = W, h: Int = H) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun settle(n: Int = 40) { for (i in 0 until n) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }
    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)
    private fun setField(o: Any, name: String, v: Any?) { o.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(o, v) }

    private val castDoc = JSONObject("""{"type":"castbar","v":"cb6","n":8,"pick":8,"pp":[39,19],"e":[
        {"s":1,"id":403,"i":136048,"c":0},{"s":2,"id":8042,"i":136026,"c":1},{"s":3,"id":331,"i":136052,"c":2},
        {"s":4,"id":324,"i":136051,"c":3},{"s":5,"id":8017,"i":136086,"c":4},{"s":6,"id":2484,"i":136102,"c":5},
        {"s":7,"id":8018,"i":136015,"c":6},{"s":8,"id":5730,"i":135824,"c":7}]}""")
    private val roster = listOf(
        """{"u":"player","i":0,"n":"Korvash","c":"SHAMAN","role":"HEALER","pt":"MANA","lv":20,"dead":false,"off":false,"col":"ff0070dd"}""",
        """{"u":"party1","i":1,"n":"Brannoc","c":"WARRIOR","role":"TANK","pt":"RAGE","lv":21,"dead":false,"off":false,"col":"ffc69b6d"}""",
        """{"u":"party2","i":2,"n":"Seraphine","c":"MAGE","role":"DAMAGER","pt":"MANA","lv":20,"dead":false,"off":false,"col":"ff3fc7eb"}""",
        """{"u":"party3","i":3,"n":"Tamsin","c":"ROGUE","role":"DAMAGER","pt":"ENERGY","lv":20,"dead":false,"off":false,"col":"fffff468"}""",
        """{"u":"party4","i":4,"n":"Odwin","c":"PRIEST","role":"HEALER","pt":"MANA","lv":19,"dead":false,"off":false,"col":"ffffffff"}""")
    private fun partyDoc(n: Int): JSONObject {
        val m = JSONArray(); for (r in roster.take(n)) m.put(JSONObject(r))
        return JSONObject().put("type", "party").put("v", "m77").put("rs", 1)
            .put("p", JSONObject().put("on", true).put("box", 1).put("fight", false).put("grp", n > 1).put("sel", "bound").put("m", m))
    }
    private val chatLines = listOf("general" to "[General] Thornbrook: LF2M Deadmines", "guild" to "[Guild] Mirelle: grats on 12!",
        "say" to "Oswin says: anyone near Goldshire?", "loot" to "You receive loot: Broken Boar Tusk.",
        "whisper" to "Seraphine whispers: inv pls", "party" to "[Party] Iselle: pulling next pack")
    /** a class radial each side (a pet bar: Attack, Follow, Stay, Passive, Defensive, Aggressive) */
    private fun radial(): JSONObject {
        val e = JSONArray()
        for ((j, ic) in listOf(132152, 132328, 136106, 132311, 132110, 132270).withIndex())
            e.put(JSONObject().put("p", j + 1).put("n", "Pet ${j + 1}").put("i", ic).put("k", "pet").put("c", 30 + j))
        return JSONObject().put("e", e)
    }

    private class Page(val home: HomeView, val cast: CastView)

    /** a Home page for [layout] at rest, Party mode on with [n] in the party, the bar shown in fights ([stay]) */
    private fun page(layout: HomeLayout, n: Int = 3, stay: Boolean = true): Page {
        System.getenv("CLOCK_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
        app.getSharedPreferences("home", 0).edit().clear().commit()
        HomeSettings.init(app); Settings.init(app); Tr.init(app)
        HomeSettings.castBarCombat = if (stay) CastStripModel.Combat.STAY else CastStripModel.Combat.HIDE
        HomeSettings.castBarPartyShow = stay                                  // (Party mode's own "the bar shows in fights" too)
        HomeLayoutStore.character(null)
        HomeLayoutStore.set(layout)
        CastStripModel.Live.pos = 0f; CastStripModel.Live.side = null
        UiKit.pauseBarsBottom = 0f
        setField(PartyModel.live, "party", null)
        PartyModel.live.onParty(PartyModel.parseParty(partyDoc(n))!!, true, System.currentTimeMillis())
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        home.onPadMap(PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m189","m":{"page":1,"sets":[],"dpad":{"on":true,"want":true,"ct":1,"cc":0}}}"""))!!)
        lay(home)
        cast.roomBarOn = true
        cast.roomBar = HoldTalk.DEFAULT_DIRS
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))
            .put("ca", JSONObject().put("l", radial()).put("r", radial()))))
        val bar = CastStripModel.parse(castDoc)!!
        CastStripModel.Live.set(bar); home.onCastBar(bar)
        fightWord(home, cast, false)
        val lines = JSONArray()
        for ((i, l) in chatLines.withIndex()) lines.put(JSONObject().put("i", i + 1).put("ch", l.first).put("text", l.second))
        home.onChat(JSONObject().put("v", "chat40").put("s", 1).put("n", chatLines.size).put("lines", lines))
        home.partyChanged()
        home.tick()
        ShadowLooper.idleMainLooper()
        lay(home)
        HomeView::class.java.getDeclaredMethod("changed").apply { isAccessible = true }.invoke(home)
        settle()
        lay(home)
        return Page(home, cast)
    }
    private fun fightWord(home: HomeView, cast: CastView, on: Boolean) {
        val ab = JSONObject().put("hud", JSONObject().put("ok", true)).put("hudS", JSONObject().put("c", on).put("g", GOLD))
        home.onFull(JSONObject().put("type", "full").put("map", JSONObject().put("zone", "Elwynn Forest").put("pvp", "contested"))
            .put("actionbar", ab), true)
        cast.update(ab)
    }

    @Suppress("UNCHECKED_CAST")
    private fun boxes(home: HomeView): Map<String, RectF> = (field(home, "now") as Map<Any, RectF>).entries.associate { it.key.toString() to RectF(it.value) }
    @Suppress("UNCHECKED_CAST")
    private fun views(home: HomeView): Map<String, View> = (field(home, "views") as Map<Any, View>).entries.associate { it.key.toString() to it.value }
    private fun strips(home: HomeView): List<CastStripView> = listOf(field(home, "castStrip") as CastStripView, field(home, "castStrip2") as CastStripView)
    private fun partyView(home: HomeView): View = views(home).getValue("PARTY")
    /** the party list's box as drawn now (its laid box moved by its view's translation) */
    private fun listDrawn(home: HomeView): RectF = RectF(boxes(home).getValue("PARTY")).apply { val v = partyView(home); offset(v.translationX, v.translationY) }
    /** Across's bar as drawn, cut to the page (null = off it) */
    private fun barDrawn(home: HomeView): RectF? {
        val v = views(home)
        val drawn = boxes(home).filter { (k, _) -> (k == "STRIP" || k == "CAST2") && v[k]?.visibility == View.VISIBLE }.mapNotNull { (k, r) ->
            val d = RectF(r).apply { offset(v.getValue(k).translationX, v.getValue(k).translationY) }
            if (d.top >= H - 0.5f) null else d
        }
        return drawn.reduceOrNull { a, b -> RectF(a).apply { union(b) } }
    }
    private fun overlaps(a: RectF, b: RectF): Boolean = a.left < b.right - 0.5f && b.left < a.right - 0.5f && a.top < b.bottom - 0.5f && b.top < a.bottom - 0.5f
    private fun r(x: RectF) = "${x.left.toInt()}..${x.right.toInt()} x ${x.top.toInt()}..${x.bottom.toInt()}"

    private val showClass by lazy { HomeView::class.java.getDeclaredMethod("classLayerShow", Boolean::class.javaPrimitiveType).apply { isAccessible = true } }
    private fun classOpen(p: Page, left: Boolean): RectF {
        val flow = field(p.cast, "classFlow")!!
        flow.javaClass.getDeclaredField("side").apply { isAccessible = true }.setInt(flow, if (left) 1 else 2)
        showClass.invoke(p.home, true); settle()
        return classRect(p)
    }
    private fun classRect(p: Page) = RectF(field(p.home, "classRect") as Rect)
    private fun classClose(p: Page) { showClass.invoke(p.home, false); settle() }

    private val bad = ArrayList<String>()
    private fun no(tag: String, m: String) { if (bad.size < 60) bad += "$tag: $m" }

    /** the radials' sizes on [p] (each opened and closed in turn) */
    private class Sizes(val middle: Float, val classL: Float, val classR: Float, val lbK: Float, val list: RectF)
    private fun sizes(p: Page): Sizes {
        val list = listDrawn(p.home)
        val m = p.home.wheelSlot(true)!!; settle(); p.home.wheelSlot(false); settle()
        val l = classOpen(p, true); classClose(p)
        val rr = classOpen(p, false); classClose(p)
        p.home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
        val pl = p.home.lbPlace()
        p.home.pauseSlide(0f, LbPartyView.SIDE_PX); p.home.pauseSlide(0f)
        return Sizes(m.width(), l.width(), rr.width(), pl.k, list)
    }

    private fun back(tag: String, what: String, p: Page) {
        val v = partyView(p.home)
        if (kotlin.math.abs(v.translationX) > 0.5f || kotlin.math.abs(v.translationY) > 0.5f) no(tag, "$what: the list not back (${v.translationX}, ${v.translationY})")
        for (s in strips(p.home)) if (s.visibility == View.VISIBLE) {
            if (kotlin.math.abs(s.translationX) > 0.5f || kotlin.math.abs(s.translationY) > 0.5f) no(tag, "$what: the bar not back (${s.translationX}, ${s.translationY})")
            if (!s.takesTouch) no(tag, "$what: the bar takes no taps after the close")
        }
    }

    /** the bars the list drops with: Across's, and Split's two halves (the user's pick "Split bar too") */
    private val BARS = listOf(HomeLayout.CastDock.ACROSS, HomeLayout.CastDock.SPLIT)
    private val FOOT_CASES: List<Pair<String, HomeLayout>> = run {
        val out = ArrayList<Pair<String, HomeLayout>>()
        // (the user's pick 2026-11-23, "Split bar too": the list over Split's halves the same as over Across's bar)
        for (cast in BARS) for (dock in listOf(HomeLayout.PartyDock.UNDER_CHAT, HomeLayout.PartyDock.UNDER_MAP))
            for ((bn, base) in listOf("" to HomeLayout(), " swapped" to HomeLayout().swapped(), " third" to HomeLayout(chatShare = HomeLayout.Share.THIRD)))
            for (size in HomeLayout.SlotSize.values())
                out += "${cast.name.lowercase()} ${dock.name.lowercase()}$bn ${size.name}" to base.copy(party = dock, cast = cast, castSize = size)
        out
    }

    /** the list across at the foot, right over Across's bar (the page these rulings are about) */
    private fun footOverAcross(tag: String, p: Page): Boolean {
        val list = boxes(p.home).getValue("PARTY"); val bar = barDrawn(p.home)
        if (list.width() < 1200f) { no(tag, "the list not across ($list)"); return false }
        if (bar == null || bar.top < list.bottom - 0.5f || bar.top > list.bottom + G + 0.5f) { no(tag, "the list ${r(list)} not right over the bar $bar"); return false }
        return true
    }

    @Test fun theWheelsAreFullSizeWithTheListDropped() {
        for ((tag, l) in FOOT_CASES) {
            val ref = sizes(page(l.copy(cast = HomeLayout.CastDock.OFF)))
            val p = page(l)
            if (!footOverAcross(tag, p)) continue
            val bar = barDrawn(p.home)!!
            // the middle wheel
            val slot = p.home.wheelSlot(true)!!; settle()
            if (kotlin.math.abs(slot.width() - ref.middle) > 1.5f) no(tag, "middle wheel ${r(slot)} not its full size ${ref.middle}")
            var d = listDrawn(p.home)
            if (overlaps(d, slot)) no(tag, "middle wheel ${r(slot)}: the list drawn ${r(d)} over it")
            if (kotlin.math.abs(d.top - ref.list.top) > 1f || kotlin.math.abs(d.bottom - ref.list.bottom) > 1f)
                no(tag, "middle wheel: the list drawn ${r(d)}, not where it lies with no bar ${r(ref.list)}")
            if (kotlin.math.abs(partyView(p.home).translationX) > 0.5f) no(tag, "middle wheel: the list slid sideways")
            if (barDrawn(p.home) != null) no(tag, "middle wheel: the bar still on the page ${barDrawn(p.home)}")
            if (kotlin.math.abs(partyView(p.home).translationY - (H - bar.top)) > 0.5f) no(tag, "middle wheel: the list not on the bar's motion (${partyView(p.home).translationY} vs ${H - bar.top})")
            p.home.wheelSlot(false); settle()
            back(tag, "middle wheel", p)
            // the class wheels
            for (left in listOf(true, false)) {
                val nm = if (left) "LB+LT" else "RB+RT"
                val cr = classOpen(p, left)
                val want = if (left) ref.classL else ref.classR
                if (kotlin.math.abs(cr.width() - want) > 1.5f) no(tag, "$nm wheel ${r(cr)} not its full size $want")
                d = listDrawn(p.home)
                if (overlaps(d, cr)) no(tag, "$nm wheel ${r(cr)}: the list drawn ${r(d)} over it")
                barDrawn(p.home)?.let { if (overlaps(it, cr) || overlaps(it, d)) no(tag, "$nm: the bar drawn ${r(it)} over the wheel / the list") }
                classClose(p)
                back(tag, nm, p)
            }
        }
        assertTrue("${bad.size} misfits:\n${bad.joinToString("\n")}", bad.isEmpty())
    }

    @Test fun theLbPictureAndTheFullSlidesTakeTheListDown() {
        var drops = 0; var stays = 0
        for ((tag, l) in FOOT_CASES) for (n in listOf(1, 3, 5)) {
            val t = "$tag, $n in the party"
            val ref = sizes(page(l.copy(cast = HomeLayout.CastDock.OFF), n))
            val p = page(l, n)
            if (!footOverAcross(t, p)) continue
            val list = boxes(p.home).getValue("PARTY")
            // partway through the picture's open: straight down, one block with the bar
            for (k in listOf(0.2f, 0.35f, 0.6f)) {
                p.home.pauseSlide(UiKit.PAUSE_OPEN_MS * k, LbPartyView.SIDE_PX); p.home.lbPlace()
                val lv = partyView(p.home)
                if (lv.translationX != 0f) no(t, "LB picture at $k: the list slid sideways (${lv.translationX})")
                barDrawn(p.home)?.let { b -> if (overlaps(b, listDrawn(p.home))) no(t, "LB picture at $k: the list ${r(listDrawn(p.home))} over the bar ${r(b)}") }
            }
            p.home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
            val pl = p.home.lbPlace()
            if (kotlin.math.abs(pl.k - ref.lbK) > 1e-3f) no(t, "LB picture at ${pl.k}, not its full size ${ref.lbK}")
            val v = partyView(p.home)
            val bars = LbFit.bars(pl).values.map { RectF(it.l, it.t, it.r, it.b) }
            if (kotlin.math.abs(v.translationX) > 0.5f) no(t, "LB picture: the list slid sideways (${v.translationX})")
            if (v.translationY > 0.5f) {
                drops++
                if (kotlin.math.abs(list.top + v.translationY - H) > 0.5f) no(t, "LB picture: the list not below the page (${list.top + v.translationY})")
                if (barDrawn(p.home) != null) no(t, "LB picture: the list dropped, the bar still on the page")
            } else {
                stays++
                val d = listDrawn(p.home)
                for (b in bars) if (overlaps(d, b)) no(t, "LB picture: the list ${r(d)} stays over its bar ${r(b)}")
                for (s in strips(p.home)) if (s.visibility == View.VISIBLE && (s.translationY != 0f || s.translationX != 0f)) no(t, "LB picture fits: the bar moved")
            }
            barDrawn(p.home)?.let { bd -> for (b in bars) if (overlaps(bd, b)) no(t, "LB picture: the bar ${r(bd)} over its bar ${r(b)}") }
            p.home.pauseSlide(0f, LbPartyView.SIDE_PX); p.home.pauseSlide(0f)
            back(t, "LB picture", p)
            // the pause wheel and the target radial: straight down below the page with the bar
            for (up in listOf(true, false)) {
                val nm = if (up) "pause" else "target radial"
                var last = 0f
                for (k in listOf(0.25f, 0.5f, 0.75f, 1f)) {
                    p.home.pauseSlide(UiKit.PAUSE_OPEN_MS * k, barsUp = up)
                    if (v.translationX != 0f || v.translationY < last - 0.5f) no(t, "$nm at $k: the list not straight down (${v.translationX}, ${v.translationY})")
                    // one block: the list never runs down over the bar on the way
                    barDrawn(p.home)?.let { b -> if (overlaps(b, listDrawn(p.home))) no(t, "$nm at $k: the list ${r(listDrawn(p.home))} over the bar ${r(b)}") }
                    last = v.translationY
                }
                if (kotlin.math.abs(list.top + v.translationY - H) > 0.5f) no(t, "$nm: the list not below the page (${list.top + v.translationY})")
                p.home.pauseSlide(0f, barsUp = up)
                back(t, nm, p)
            }
        }
        assertTrue("${bad.size} misfits ($drops drops, $stays stays):\n${bad.joinToString("\n")}", bad.isEmpty() && drops > 0)
    }

    /** "Stay when it fits": an LB picture that fits over the list and the bar both moves neither */
    @Test fun anLbPictureThatFitsMovesNothing() = BARS.forEach { cast ->
        // Classic's held LB place (1516's, chat left, half) over a one-row list and a small bar (Across's, Split's halves)
        val l = HomeLayout(party = HomeLayout.PartyDock.UNDER_CHAT, cast = cast, castSize = HomeLayout.SlotSize.S)
        val p = page(l, 1)
        assertTrue(bad.joinToString(), footOverAcross("fits $cast", p))
        val list = boxes(p.home).getValue("PARTY")
        p.home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
        val pl = p.home.lbPlace()
        val bottom = LbFit.bars(pl).values.maxOf { it.b }
        val v = partyView(p.home)
        // (review: this page IS one that fits - the test pinned nothing when it took the other branch)
        assertTrue("$cast: the picture fits over the list here (its foot $bottom, the list's top ${list.top})", bottom + G <= list.top + 0.5f)
        assertTrue("$cast: the list stays (${v.translationX}, ${v.translationY})", v.translationX == 0f && v.translationY == 0f)
        for (s in strips(p.home)) if (s.visibility == View.VISIBLE) assertTrue("$cast: the bar stays (${s.translationX}, ${s.translationY})",
            s.translationX == 0f && s.translationY == 0f && s.takesTouch)
        p.home.pauseSlide(0f, LbPartyView.SIDE_PX); p.home.pauseSlide(0f)
    }

    @Test fun aTopListIsNotMoved() {
        for (cast in BARS) for (dock in listOf(HomeLayout.PartyDock.ABOVE_CHAT, HomeLayout.PartyDock.ABOVE_MAP)) {
            val tag = "$cast ${dock.name.lowercase()}"
            val l = HomeLayout(party = dock, cast = cast, castSize = HomeLayout.SlotSize.L)
            val ref = sizes(page(l.copy(cast = HomeLayout.CastDock.OFF)))
            val p = page(l)
            val list = boxes(p.home).getValue("PARTY")
            if (list.width() < 1200f || list.centerY() > H / 2f) { no(tag, "not a top list across ($list)"); continue }
            val slot = p.home.wheelSlot(true)!!; settle()
            val v = partyView(p.home)
            if (v.translationX != 0f || v.translationY != 0f) no(tag, "middle wheel: the top list moved")
            if (overlaps(listDrawn(p.home), slot)) no(tag, "middle wheel under the top list")
            if (kotlin.math.abs(slot.width() - ref.middle) > 1.5f) no(tag, "middle wheel ${slot.width()} not ${ref.middle}")
            p.home.wheelSlot(false); settle()
            for (left in listOf(true, false)) {
                val cr = classOpen(p, left)
                if (v.translationX != 0f || v.translationY != 0f) no(tag, "class wheel: the top list moved")
                if (overlaps(listDrawn(p.home), cr)) no(tag, "class wheel under the top list")
                classClose(p)
            }
        }
        assertTrue("${bad.size} misfits:\n${bad.joinToString("\n")}", bad.isEmpty())
    }

    /** a wheel held into a Grow fight: the list's rows grow (and, Hide in combat, the bar goes) - never drawn over the wheel, on any frame */
    @Test fun aWheelHeldIntoAGrowFightKeepsClearOfTheList() {
        for (cast in BARS) for (dock in listOf(HomeLayout.PartyDock.UNDER_CHAT, HomeLayout.PartyDock.UNDER_MAP)) for (stay in listOf(true, false))
            for (what in listOf("middle wheel", "LB+LT", "RB+RT")) for (n in listOf(3, 5)) {
                val tag = "$cast ${dock.name.lowercase()} ${if (stay) "shown" else "hidden"} in fights, $n, $what"
                val l = HomeLayout(party = dock, cast = cast, castSize = HomeLayout.SlotSize.L, fight = HomeLayout.FightBand.GROW)
                val p = page(l, n, stay)
                if (!footOverAcross(tag, p)) continue
                val rest = boxes(p.home).getValue("PARTY")
                fun sq(): RectF = if (what == "middle wheel") RectF(field(p.home, "wheelRect") as RectF) else classRect(p)
                if (what == "middle wheel") { p.home.wheelSlot(true); settle() } else classOpen(p, what == "LB+LT")
                val at = sq()
                fightWord(p.home, p.cast, true); p.home.tick()
                for (i in 0 until 40) {
                    settle(1); lay(p.home)
                    val d = listDrawn(p.home); val s = sq()
                    if (overlaps(d, s)) { no(tag, "frame $i: the list drawn ${r(d)} over the wheel ${r(s)}"); break }
                    barDrawn(p.home)?.let { if (overlaps(it, s)) { no(tag, "frame $i: the bar ${r(it)} over the wheel ${r(s)}"); return@let } }
                }
                val grown = boxes(p.home).getValue("PARTY")
                if (grown.height() <= rest.height() + 0.5f) no(tag, "the list did not grow ($rest -> $grown)")
                if (sq().width() > at.width() + 0.5f) no(tag, "the wheel grew under the hold (${r(at)} -> ${r(sq())})")
                if (what == "middle wheel") { p.home.wheelSlot(false); settle() } else classClose(p)
                fightWord(p.home, p.cast, false); p.home.tick(); settle(60)
                back(tag, "after the close and the fight", p)
            }
        assertTrue("${bad.size} misfits:\n${bad.joinToString("\n")}", bad.isEmpty())
    }

    /** review: every In fights band (Grow / Same size / Top screen, its cast bar on either screen) x the list's "Size in fights" (Grow /
     *  Same size: one setting for both screens since 1524) x the bar shown or hidden in fights, each wheel held through a whole fight (its start AND its end):
     *  on no frame the list or the bar drawn over the held wheel, and both back where they lie once it closes */
    @Test fun aWheelHeldThroughAnyFightKeepsClearOfTheList() {
        for (cast in BARS) for (band in HomeLayout.FightBand.values()) for (fc in if (band == HomeLayout.FightBand.TOP) HomeLayout.FightCast.values().toList() else listOf(HomeLayout.FightCast.BOTTOM))
            // (the list's one "Size in fights" pick: it applies to every band, the controller on either screen)
            for (sz in HomeLayout.PartySize.values())
            for (stay in listOf(true, false)) for (what in listOf("middle wheel", "LB+LT", "RB+RT")) {
                val tag = "$cast $band/$fc/$sz ${if (stay) "shown" else "hidden"} in fights, $what"
                val base = HomeLayout(party = HomeLayout.PartyDock.UNDER_MAP, cast = cast, castSize = HomeLayout.SlotSize.L,
                    fight = band, fightCast = fc)
                val l = base.copy(partySize = sz)
                val p = page(l, 5, stay)
                if (!footOverAcross(tag, p)) continue
                fun sq(): RectF = if (what == "middle wheel") RectF(field(p.home, "wheelRect") as RectF) else classRect(p)
                if (what == "middle wheel") { p.home.wheelSlot(true); settle() } else classOpen(p, what == "LB+LT")
                for (on in listOf(true, false)) {
                    fightWord(p.home, p.cast, on); p.home.tick()
                    for (i in 0 until 50) {
                        settle(1); lay(p.home)
                        val d = listDrawn(p.home); val s = sq()
                        if (overlaps(d, s)) { no(tag, "fight ${if (on) "start" else "end"} frame $i: the list drawn ${r(d)} over the wheel ${r(s)}"); break }
                        val b = barDrawn(p.home)
                        if (b != null && overlaps(b, s)) { no(tag, "fight ${if (on) "start" else "end"} frame $i: the bar ${r(b)} over the wheel ${r(s)}"); break }
                    }
                }
                if (what == "middle wheel") { p.home.wheelSlot(false); settle() } else classClose(p)
                settle(60)
                back(tag, "after the close", p)
            }
        assertTrue("${bad.size} misfits:\n${bad.joinToString("\n")}", bad.isEmpty())
    }

    /** review: Party mode turned on (the list comes onto the page) while a wheel is held - the list never drawn over the wheel on the way */
    @Test fun theListComingUnderAHeldWheelNeverCoversIt() {
        for (cast in BARS) for (what in listOf("middle wheel", "LB+LT", "RB+RT")) {
            val tag = "$cast $what"
            val l = HomeLayout(party = HomeLayout.PartyDock.UNDER_CHAT, cast = cast, castSize = HomeLayout.SlotSize.L)
            val p = page(l, 3)
            val pv = partyView(p.home)
            GameUi.init(app); GameUi.setSwitch(GameUi.SW_PMODE, false); p.home.partyChanged(); settle(60); lay(p.home)
            if (pv.visibility == View.VISIBLE) { no(tag, "Party mode off: the list still shown"); continue }
            fun sq(): RectF = if (what == "middle wheel") RectF(field(p.home, "wheelRect") as RectF) else classRect(p)
            if (what == "middle wheel") { p.home.wheelSlot(true); settle() } else classOpen(p, what == "LB+LT")
            GameUi.setSwitch(GameUi.SW_PMODE, true); p.home.partyChanged()
            var seen = false
            for (i in 0 until 50) {
                settle(1); lay(p.home)
                if (pv.visibility != View.VISIBLE || boxes(p.home)["PARTY"] == null) continue
                seen = true
                val d = listDrawn(p.home); val s = sq()
                if (pv.alpha > 0.05f && overlaps(d, s)) { no(tag, "frame $i: the list drawn ${r(d)} (alpha ${pv.alpha}) over the wheel ${r(s)}"); break }
            }
            if (!seen) no(tag, "the list never came")
            if (what == "middle wheel") { p.home.wheelSlot(false); settle() } else classClose(p)
            settle(60)
            back(tag, "after the close", p)
        }
        assertTrue("${bad.size} misfits:\n${bad.joinToString("\n")}", bad.isEmpty())
    }

    /** a wheel opened in a Grow fight with the bar hidden (the list on the page's foot), held as the fight ends: the bar comes back
     *  under it - the list stays down with the bar (no frame with the list or the bar over the wheel), the wheel keeps its size */
    @Test fun aFightEndingUnderAHeldWheelKeepsTheListDown() {
        for (cast in BARS) for (dock in listOf(HomeLayout.PartyDock.UNDER_CHAT, HomeLayout.PartyDock.UNDER_MAP)) for (what in listOf("middle wheel", "LB+LT", "RB+RT")) {
            val tag = "$cast ${dock.name.lowercase()}, $what"
            val l = HomeLayout(party = dock, cast = cast, castSize = HomeLayout.SlotSize.L, fight = HomeLayout.FightBand.GROW)
            val p = page(l, 3, stay = false)
            fightWord(p.home, p.cast, true); p.home.tick(); settle(60); lay(p.home)
            if (barDrawn(p.home) != null) { no(tag, "the bar not slid away in the fight"); continue }
            fun sq(): RectF = if (what == "middle wheel") RectF(field(p.home, "wheelRect") as RectF) else classRect(p)
            if (what == "middle wheel") { p.home.wheelSlot(true); settle() } else classOpen(p, what == "LB+LT")
            val at = sq()
            fightWord(p.home, p.cast, false); p.home.tick()
            for (i in 0 until 40) {
                settle(1); lay(p.home)
                val d = listDrawn(p.home); val s = sq()
                if (overlaps(d, s)) { no(tag, "frame $i: the list drawn ${r(d)} over the wheel ${r(s)}"); break }
                barDrawn(p.home)?.let { b -> if (overlaps(b, s) || overlaps(b, d)) { no(tag, "frame $i: the bar ${r(b)} over the wheel ${r(s)} / the list ${r(d)}"); return@let } }
            }
            if (sq().width() < at.width() - 1.5f) no(tag, "the wheel shrank as the fight ended (${r(at)} -> ${r(sq())})")
            if (what == "middle wheel") { p.home.wheelSlot(false); settle() } else classClose(p)
            back(tag, "after the close", p)
            footOverAcross("$tag, after the close", p)
        }
        assertTrue("${bad.size} misfits:\n${bad.joinToString("\n")}", bad.isEmpty())
    }

    private val classicLook by lazy { HomeView::class.java.getDeclaredMethod("classicLook").apply { isAccessible = true } }
    /** the LB picture's place on [p]'s page ending at [bottom] (LbFit, as HomeView places it: the page's columns' line, the band's foot) */
    private fun lbAt(p: Page, l: HomeLayout, bottom: Float): LbFit.Place {
        val line = HomeLayoutSolver.columns(l, W.toFloat(), G).let { it.first.second to it.second.first }
        return LbFit.place(W.toFloat(), p.home.barsBottom(), line.first, line.second, bottom, classicLook.invoke(p.home) as Boolean, UiKit.pauseBarsBottom)
    }
    /** the LB picture ending a gap over [list] is as large as down to the page's foot, and clears the list (HomeSlide.makesRoom's rule) */
    private fun lbFitsOver(p: Page, l: HomeLayout, list: RectF): Boolean {
        val full = lbAt(p, l, H - G); val over = lbAt(p, l, list.top - G)
        // (HomeSlide.makesRoom: smaller than its full size, or down over the list's top - HomeSlide is internal to the shared sources)
        return !(over.k * LbPartyView.PICT_H < full.k * LbPartyView.PICT_H - 0.5f || LbFit.bars(over).values.maxOf { it.b } + G > list.top + 0.5f)
    }

    /**
     * the user's pick 2026-11-23, "Bar Off: stay when it fits" ("With the bar off, the LB picture leaves the party list where it is when
     * the picture fits, like it now does over Across."): Party mode's full-width list at the foot with no bar under it (the bar Off; a
     * bar above a column the same) stays where it is - on every frame of the picture's open - when the picture fits over it at its full
     * size; when it does not, it leaves the page sideways as before and the picture takes the page down to its foot. Either way the
     * picture is its full size and the list as drawn never covers it; both back after the close
     */
    @Test fun withTheBarOffTheLbPictureLeavesAListThatFits() {
        var stays = 0; var leaves = 0
        for (cast in listOf(HomeLayout.CastDock.OFF, HomeLayout.CastDock.ABOVE_MAP)) for (dock in listOf(HomeLayout.PartyDock.UNDER_CHAT, HomeLayout.PartyDock.UNDER_MAP))
            for ((bn, base) in listOf("" to HomeLayout(), " swapped" to HomeLayout().swapped(), " third" to HomeLayout(chatShare = HomeLayout.Share.THIRD)))
            for (n in listOf(1, 3, 5)) {
                val t = "$cast ${dock.name.lowercase()}$bn, $n in the party"
                val l = base.copy(party = dock, cast = cast)
                val p = page(l, n)
                val list = boxes(p.home).getValue("PARTY")
                if (list.width() < 1200f || list.centerY() < H / 2f) { no(t, "not a foot list across (${r(list)})"); continue }
                val fits = lbFitsOver(p, l, list)
                val full = lbAt(p, l, H - G)
                val v = partyView(p.home)
                for (k in listOf(0.2f, 0.5f)) {
                    p.home.pauseSlide(UiKit.PAUSE_OPEN_MS * k, LbPartyView.SIDE_PX); p.home.lbPlace()
                    if (fits && (v.translationX != 0f || v.translationY != 0f)) no(t, "LB picture at $k: the list moved (${v.translationX}, ${v.translationY}) though the picture fits over it")
                }
                p.home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
                val pl = p.home.lbPlace()
                if (kotlin.math.abs(pl.k - full.k) > 1e-3f) no(t, "LB picture at ${pl.k}, not its full size ${full.k}")
                val d = listDrawn(p.home)
                for (b in LbFit.bars(pl).values) if (overlaps(d, RectF(b.l, b.t, b.r, b.b))) no(t, "LB picture: the list drawn ${r(d)} over its bar ${b}")
                if (fits) {
                    stays++
                    if (v.translationX != 0f || v.translationY != 0f) no(t, "the picture fits over the list: the list moved (${v.translationX}, ${v.translationY})")
                } else {
                    leaves++
                    if (d.right > 0.5f && d.left < W - 0.5f) no(t, "the picture does not fit over the list: the list still on the page ${r(d)}")
                }
                p.home.pauseSlide(0f, LbPartyView.SIDE_PX); p.home.pauseSlide(0f)
                back(t, "LB picture", p)
            }
        assertTrue("${bad.size} misfits ($stays stay, $leaves leave):\n${bad.joinToString("\n")}", bad.isEmpty() && stays > 0 && leaves > 0)
    }

    /** review: an LB picture opened in a Grow fight with the bar hidden (Hide in combat: the list on the page's foot, the picture fitting
     *  over it - the list stays), held as the fight ends: the bar comes back under the list and the list rises over it - on no frame the
     *  list or the bar drawn over the picture's bars (the picture shrinks over them, as a held wheel does); both back after the close */
    @Test fun anLbPictureHeldThroughAFightsEndKeepsClearOfTheList() {
        var held = 0; var rose = 0
        for (cast in BARS) for (dock in listOf(HomeLayout.PartyDock.UNDER_CHAT, HomeLayout.PartyDock.UNDER_MAP)) for (n in listOf(1, 2))
            for (size in HomeLayout.SlotSize.values()) {
            val tag = "$cast ${dock.name.lowercase()} ${size.name}, $n in the party"
            val l = HomeLayout(party = dock, cast = cast, castSize = size, fight = HomeLayout.FightBand.GROW)
            val p = page(l, n, stay = false)
            fightWord(p.home, p.cast, true); p.home.tick(); settle(60); lay(p.home)
            if (barDrawn(p.home) != null) { no(tag, "the bar not slid away in the fight"); continue }
            p.home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
            p.home.lbPlace()
            val v = partyView(p.home)
            if (v.translationX != 0f || v.translationY != 0f) { p.home.pauseSlide(0f, LbPartyView.SIDE_PX); p.home.pauseSlide(0f); continue }
            held++
            val foot0 = LbFit.bars(p.home.lbPlace()).values.maxOf { it.b }
            fightWord(p.home, p.cast, false); p.home.tick()
            for (i in 0 until 50) {
                settle(1); lay(p.home)
                val pl = p.home.lbPlace()
                val bars = LbFit.bars(pl).values.map { RectF(it.l, it.t, it.r, it.b) }
                val d = listDrawn(p.home)
                if (bars.any { overlaps(d, it) }) { no(tag, "frame $i: the list drawn ${r(d)} over the picture"); break }
                val b = barDrawn(p.home)
                if (b != null && bars.any { overlaps(b, it) }) { no(tag, "frame $i: the bar drawn ${r(b)} over the picture"); break }
            }
            // (the list laid back over the bar rose past where the picture ended at the open: the case this pins)
            if (boxes(p.home).getValue("PARTY").top < foot0 + G - 0.5f) rose++
            p.home.pauseSlide(0f, LbPartyView.SIDE_PX); p.home.pauseSlide(0f); settle(60)
            back(tag, "after the close", p)
        }
        assertTrue("${bad.size} misfits ($held held with the list staying, $rose with it rising past the picture's foot):\n${bad.joinToString("\n")}",
            bad.isEmpty() && held > 0 && rose > 0)
    }

    // ---------------------------------------------------------------- the pictures

    private val pCyan = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.CYAN }
    private val pRed = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.RED }
    private val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f; color = Color.YELLOW; isFakeBoldText = true }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 200, 160, 40) }

    private fun save(p: Page, name: String, label: String, over: ((Canvas) -> Unit)? = null) {
        val d = out ?: return
        val bm = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        val strip = Strip(app) { }
        for (pass in 0..1) {
            val c = Canvas(bm); c.drawColor(Theme.ink)
            p.home.draw(c)
            over?.invoke(c)
            lay(strip, W, Strip.HEIGHT)
            c.save(); c.translate(0f, H.toFloat()); strip.draw(c); c.restore()
            c.drawText(label, 16f, 1080f - 12f, pLabel)
            if (pass == 0) { ShadowLooper.idleMainLooper(); Thread.sleep(120); ShadowLooper.idleMainLooper() }
        }
        File(d, "$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun lbView(pl: LbFit.Place): LbPartyView {
        val v = LbPartyView(app)
        lay(v, W, H)
        v.place = { pl }
        v.lb(true)
        ShadowSystemClock.advanceBy(Duration.ofMillis(600))
        return v
    }

    /** the user's picks 2026-11-23 ("Split bar too", "Bar Off: stay when it fits"): Split's halves under the list at rest, the middle
     *  wheel, a class wheel, the LB picture; with the bar Off the LB picture over a list it fits over (it stays) and one it does not */
    @Test fun shotsSplitAndBarOff() {
        if (out == null) return
        val l = HomeLayout(party = HomeLayout.PartyDock.UNDER_CHAT, cast = HomeLayout.CastDock.SPLIT, castSize = HomeLayout.SlotSize.L)
        var p = page(l)
        save(p, "s1_split_rest", "Party mode, list Under chat, Split L: at rest")
        val slot = p.home.wheelSlot(true)!!
        p.home.wheelPainter = { c -> c.drawCircle(slot.centerX(), slot.centerY(), slot.width() / 2f, disc) }
        settle()
        save(p, "s2_split_middle_wheel", "Split: middle wheel (D-pad Up / spell radial) ${slot.width().toInt()} px") { c -> c.drawRect(slot, pCyan) }
        p.home.wheelSlot(false); settle(); p.home.wheelPainter = null
        for (left in listOf(true, false)) {
            val cr = classOpen(p, left)
            save(p, "s3_split_class_${if (left) "lb_lt" else "rb_rt"}", "Split: ${if (left) "LB+LT" else "RB+RT"} class wheel ${cr.width().toInt()} px") { c -> c.drawRect(cr, pRed) }
            classClose(p)
        }
        p = page(l)
        p.home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
        val pl = p.home.lbPlace()
        val lbv = lbView(pl)
        save(p, "s4_split_lb_picture", "Split: LB picture at ${"%.2f".format(pl.k)}") { c -> lbv.draw(c) }
        p.home.pauseSlide(0f, LbPartyView.SIDE_PX); p.home.pauseSlide(0f)
        p = page(l, 1)
        p.home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
        val fl = p.home.lbPlace()
        val flv = lbView(fl)
        save(p, "s5_split_lb_picture_fits", "Split, 1 in the party: LB picture fits over the list and the halves (${"%.2f".format(fl.k)})") { c -> flv.draw(c) }
        p.home.pauseSlide(0f, LbPartyView.SIDE_PX); p.home.pauseSlide(0f)
        val off = l.copy(cast = HomeLayout.CastDock.OFF)
        for ((n, nm) in listOf(1 to "fits", 5 to "does_not_fit")) {
            p = page(off, n)
            p.home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
            val op = p.home.lbPlace()
            val ov = lbView(op)
            save(p, "o${if (n == 1) 1 else 2}_bar_off_lb_picture_$nm", "Bar Off, $n in the party: LB picture ${nm.replace('_', ' ')} (${"%.2f".format(op.k)})") { c -> ov.draw(c) }
            p.home.pauseSlide(0f, LbPartyView.SIDE_PX); p.home.pauseSlide(0f)
        }
    }

    @Test fun shots() {
        if (out == null) return
        val l = HomeLayout(party = HomeLayout.PartyDock.UNDER_CHAT, cast = HomeLayout.CastDock.ACROSS, castSize = HomeLayout.SlotSize.L)
        var p = page(l)
        save(p, "1_rest", "Party mode, list Under chat, Across L: at rest")
        val slot = p.home.wheelSlot(true)!!
        p.home.wheelPainter = { c -> c.drawCircle(slot.centerX(), slot.centerY(), slot.width() / 2f, disc) }
        settle()
        save(p, "2_middle_wheel", "middle wheel (D-pad Up / spell radial) ${slot.width().toInt()} px") { c -> c.drawRect(slot, pCyan) }
        p.home.wheelSlot(false); settle(); p.home.wheelPainter = null
        for (left in listOf(true, false)) {
            val cr = classOpen(p, left)
            save(p, "3_class_${if (left) "lb_lt" else "rb_rt"}", "${if (left) "LB+LT" else "RB+RT"} class wheel ${cr.width().toInt()} px") { c -> c.drawRect(cr, pRed) }
            classClose(p)
        }
        p = page(l)
        p.home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
        val pl = p.home.lbPlace()
        val lbv = lbView(pl)
        save(p, "4_lb_picture", "LB picture at ${"%.2f".format(pl.k)}") { c -> lbv.draw(c) }
        p.home.pauseSlide(0f, LbPartyView.SIDE_PX); p.home.pauseSlide(0f)
        p = page(l)
        p.home.pauseSlide(UiKit.PAUSE_OPEN_MS * 0.35f, LbPartyView.SIDE_PX)
        val mpl = p.home.lbPlace()
        val mlb = lbView(mpl)
        save(p, "5_lb_picture_midslide", "LB picture opening (35 %)") { c -> mlb.draw(c) }
        p.home.pauseSlide(0f, LbPartyView.SIDE_PX); p.home.pauseSlide(0f)
        // the pause wheel's slide partway and landed (the middle and class wheels' opens run on view animators, which the off-device
        // clock does not stop partway)
        p = page(l)
        p.home.pauseSlide(UiKit.PAUSE_OPEN_MS * 0.3f, barsUp = true)
        save(p, "6_pause_slide_midslide", "pause wheel's slide (30 %): the list and the bar down as one")
        p.home.pauseSlide(UiKit.PAUSE_OPEN_MS, barsUp = true)
        save(p, "6_pause_slide", "pause wheel's slide: the list and the bar below the page")
        p.home.pauseSlide(0f, barsUp = true)
        // held into a Grow fight (the bar shown in fights): the list's rows grow
        val gl = l.copy(fight = HomeLayout.FightBand.GROW)
        for (left in listOf(true, false)) {
            p = page(gl, 5, stay = true)
            classOpen(p, left)
            fightWord(p.home, p.cast, true); p.home.tick(); settle(60); lay(p.home)
            val cr = classRect(p)
            save(p, "7_fight_grow_class_${if (left) "lb_lt" else "rb_rt"}", "${if (left) "LB+LT" else "RB+RT"} held into a Grow fight ${cr.width().toInt()} px") { c -> c.drawRect(cr, pRed) }
            classClose(p)
        }
        p = page(gl, 5, stay = true)
        p.home.wheelSlot(true); settle()
        fightWord(p.home, p.cast, true); p.home.tick(); settle(60); lay(p.home)
        val fs = RectF(field(p.home, "wheelRect") as RectF)
        p.home.wheelPainter = { c -> c.drawCircle(fs.centerX(), fs.centerY(), fs.width() / 2f, disc) }
        save(p, "7_fight_grow_middle_wheel", "middle wheel held into a Grow fight ${fs.width().toInt()} px") { c -> c.drawRect(fs, pCyan) }
    }
}
