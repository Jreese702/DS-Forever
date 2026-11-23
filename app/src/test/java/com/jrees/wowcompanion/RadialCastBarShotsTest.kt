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
 * feat/radial-castbar (the user, 2026-11-23: "When using radials and the tap to cast bar is set to full size, it should still move out of
 * the way to make space for the full sized radials"): the live [HomeView] with the tap-to-cast bar in every dock (Under / Above the chat
 * or the map, Across at S / M / L, Split), swapped too, at rest, in a grown fight with the bar shown, in a live Top screen fight with the
 * bar shown (it stays on the bottom), in a fight with the bar hidden and with Party mode on. Under each radial over Home - the middle
 * wheel (D-pad Up / the spell radials), both class wheels (the pet wheels), the LB picture, the pause wheel's slide and the target
 * radial's - checked on the page as drawn (each laid box moved by its view's translation):
 *  - no bar drawn over the radial (the pause / target slides: every bar off the screen, Across's straight down below the page as for
 *    the other wheels - the user, 2026-11-23: "All wheels move the Across bar the same way" - never off to the left with the chat);
 *  - the radial at its FULL size: as large a square (the LB picture: the same scale) as on the same page with the bar Off (Across's bar
 *    drops when it would make it smaller; a radial that fits above it at that size leaves it where it is);
 *  - no tap reaches a bar dropped out of the radial's way; every bar is back in its place, tappable, once the radial has closed.
 * With RADIAL_CASTBAR_SHOTS=<dir> (and CLOCK_ART_ROOT for the real art) the pictures of each radial at the four docks the user named
 * (Under the chat, Across, Above the chat, Above the map; L slots), the LB picture in a grown fight with the bar shown, the pause
 * wheel's and the target radial's slides, and frames partway through that picture's open and the pause wheel's slide. [shots] uses only what the published build had, so the same test draws the "before" pictures there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RadialCastBarShotsTest {
    private val out: File? = System.getenv("RADIAL_CASTBAR_SHOTS")?.let { File(it).apply { mkdirs() } }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private val G = UiKit.MODULE_GAP

    @After fun down() {
        setField(PartyModel.live, "party", null); HomeLayoutStore.character(null); HomeLayoutStore.set(HomeLayout())
        CastStripModel.Live.pos = 0f; CastStripModel.Live.side = null; UiKit.pauseBarsBottom = 0f
    }

    private fun lay(v: View, w: Int = W, h: Int = H) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun settle(n: Int = 30) { for (i in 0 until n) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }
    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)
    private fun setField(o: Any, name: String, v: Any?) { o.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(o, v) }

    private val castDoc = JSONObject("""{"type":"castbar","v":"cb6","n":8,"pick":8,"pp":[39,19],"e":[
        {"s":1,"id":403,"i":136048,"c":0},{"s":2,"id":8042,"i":136026,"c":1},{"s":3,"id":331,"i":136052,"c":2},
        {"s":4,"id":324,"i":136051,"c":3},{"s":5,"id":8017,"i":136086,"c":4},{"s":6,"id":2484,"i":136102,"c":5},
        {"s":7,"id":8018,"i":136015,"c":6},{"s":8,"id":5730,"i":135824,"c":7}]}""")
    private val PAD = JSONObject().put("v", "pad22").put("x", 0).put("y", 12).put("cw", 6).put("ch", 2).put("cols", 40).put("pw", 1920).put("ph", 1080)
        .put("rows", JSONObject().put("qpick", 3).put("qact", 4).put("pfocus", 9))
        .put("sizes", JSONObject().put("qpick", 40).put("qact", 40).put("pfocus", 31))
    /** the quests document (its "cb" = in combat: Home's fight, as HomeMenusFitShotsTest feeds it) */
    private fun quests(fight: Boolean): JSONObject {
        val focused = JSONObject().put("id", 60).put("title", "Kobold Candles").put("zone", "Elwynn Forest").put("lv", 7).put("slot", 1)
            .put("watch", 1).put("focus", true)
            .put("obj", JSONArray().put(JSONObject().put("t", "Wax Candles: 3/8").put("have", 3).put("need", 8)))
        return JSONObject().put("plv", 7).put("focus", 60).put("rwMode", "id").put("list", JSONArray().put(focused)).put("pad", PAD).put("cb", fight)
    }
    private val roster = listOf(
        """{"u":"player","i":0,"n":"Korvash","c":"SHAMAN","role":"HEALER","pt":"MANA","lv":20,"dead":false,"off":false,"col":"ff0070dd"}""",
        """{"u":"party1","i":1,"n":"Brannoc","c":"WARRIOR","role":"TANK","pt":"RAGE","lv":21,"dead":false,"off":false,"col":"ffc69b6d"}""",
        """{"u":"party2","i":2,"n":"Seraphine","c":"MAGE","role":"DAMAGER","pt":"MANA","lv":20,"dead":false,"off":false,"col":"ff3fc7eb"}""")
    private fun partyDoc(on: Boolean): JSONObject {
        val m = JSONArray(); for (r in roster) m.put(JSONObject(r))
        return JSONObject().put("type", "party").put("v", "m77").put("rs", 1)
            .put("p", JSONObject().put("on", on).put("box", 1).put("fight", false).put("grp", true).put("sel", "bound").put("m", m))
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

    /** one state of the page: a fight ([fight]), the bar shown in it ([stay]), the Wings live ([ct], Top screen), Party mode ([party]) */
    private data class St(val name: String, val fight: Boolean = false, val stay: Boolean = false, val ct: Int? = null, val party: Boolean = false,
                          val band: HomeLayout.FightBand? = null)
    private val STATES = listOf(
        St("rest"),
        St("fight_grow_shown", fight = true, stay = true, band = HomeLayout.FightBand.GROW),
        St("fight_top_shown", fight = true, stay = true, ct = 1, band = HomeLayout.FightBand.TOP),
        St("fight_grow_hidden", fight = true, band = HomeLayout.FightBand.GROW),
        St("party", party = true))

    private class Page(val home: HomeView, val cast: CastView, val layout: HomeLayout)

    private fun page(layout: HomeLayout, st: St = St("rest")): Page {
        System.getenv("CLOCK_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
        app.getSharedPreferences("home", 0).edit().clear().commit()
        HomeSettings.init(app); Settings.init(app)
        HomeSettings.castBarCombat = if (st.stay) CastStripModel.Combat.STAY else CastStripModel.Combat.HIDE
        HomeLayoutStore.character(null)
        HomeLayoutStore.set(st.band?.let { layout.copy(fight = it) } ?: layout)
        CastStripModel.Live.pos = 0f; CastStripModel.Live.side = null
        UiKit.pauseBarsBottom = 0f
        setField(PartyModel.live, "party", null)
        PartyModel.live.onParty(PartyModel.parseParty(partyDoc(st.party))!!, true, System.currentTimeMillis())
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        st.ct?.let { home.onPadMap(PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m183","m":{"page":1,"sets":[],"dpad":{"on":true,"want":true,"ct":$it}}}"""))!!) }
        lay(home)
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))
            .put("ca", JSONObject().put("l", radial()).put("r", radial()))))
        val bar = CastStripModel.parse(castDoc)!!
        CastStripModel.Live.set(bar); home.onCastBar(bar)
        home.onFull(JSONObject().put("type", "full").put("map", JSONObject().put("zone", "Elwynn Forest").put("pvp", "contested"))
            .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true).put("hudS", JSONObject().put("c", st.fight)))), true)
        val q = quests(st.fight)
        QuestItem.Cd.onQuests(q, true); map.onQuests(q); home.onQuests(q, true)
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
        return Page(home, cast, HomeLayoutStore.active())
    }

    @Suppress("UNCHECKED_CAST")
    private fun boxes(home: HomeView): Map<String, RectF> = (field(home, "now") as Map<Any, RectF>).entries.associate { it.key.toString() to RectF(it.value) }
    @Suppress("UNCHECKED_CAST")
    private fun views(home: HomeView): Map<String, View> = (field(home, "views") as Map<Any, View>).entries.associate { it.key.toString() to it.value }
    private fun strips(home: HomeView): List<CastStripView> = listOf(field(home, "castStrip") as CastStripView, field(home, "castStrip2") as CastStripView)

    /** the tap-to-cast bar's box(es) as drawn now, cut to the page (none below it, none up behind the band) */
    private fun barsDrawn(home: HomeView): List<Pair<String, RectF>> {
        val b = boxes(home); val v = views(home)
        val bb = b["BARS"]?.bottom ?: 0f
        return b.filter { (k, _) -> (k == "STRIP" || k == "CAST2") && v[k]?.visibility == View.VISIBLE }.mapNotNull { (k, r) ->
            val vv = v.getValue(k)
            val d = RectF(r).apply { offset(vv.translationX, vv.translationY) }
            if (d.top >= H - 0.5f || d.bottom <= bb + 0.5f || d.right <= 0.5f || d.left >= W - 0.5f) null
            else k to RectF(maxOf(0f, d.left), d.top, minOf(W.toFloat(), d.right), minOf(H.toFloat(), d.bottom))
        }
    }
    private fun barShown(home: HomeView): Boolean = barsDrawn(home).isNotEmpty()
    /** Across's bar part(s) laid on the page (no column's: across the whole page, under the band, above the page's foot) and their tops */
    private fun acrossLaid(home: HomeView): Map<String, Float> {
        val b = boxes(home); val v = views(home)
        val bb = b["BARS"]?.bottom ?: 0f
        return b.filter { (k, r) -> (k == "STRIP" || k == "CAST2") && v[k]?.visibility == View.VISIBLE && r.left <= G + 1f && r.right >= W - G - 1f &&
            r.top < H - 0.5f && r.bottom > bb + 0.5f }.mapValues { it.value.top }
    }
    private fun overlaps(a: RectF, b: RectF): Boolean = a.left < b.right - 0.5f && b.left < a.right - 0.5f && a.top < b.bottom - 0.5f && b.top < a.bottom - 0.5f
    private fun r(x: RectF) = "${x.left.toInt()}..${x.right.toInt()} x ${x.top.toInt()}..${x.bottom.toInt()}"

    private val showClass by lazy { HomeView::class.java.getDeclaredMethod("classLayerShow", Boolean::class.javaPrimitiveType).apply { isAccessible = true } }
    private fun classOpen(p: Page, left: Boolean): RectF {
        val flow = field(p.cast, "classFlow")!!
        flow.javaClass.getDeclaredField("side").apply { isAccessible = true }.setInt(flow, if (left) 1 else 2)
        showClass.invoke(p.home, true); settle(40)
        return RectF(field(p.home, "classRect") as Rect)
    }
    private fun classClose(p: Page) { showClass.invoke(p.home, false); settle(40) }

    /** the radials' places on [p] with nothing open, each opened and closed in turn */
    private class Places(val middle: RectF?, val classL: RectF, val classR: RectF, val lbK: Float, val classic: Boolean)
    private fun places(p: Page): Places {
        val home = p.home
        val m = home.wheelSlot(true); settle(); home.wheelSlot(false); settle()
        val l = classOpen(p, true); classClose(p)
        val rr = classOpen(p, false); classClose(p)
        home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
        val pl = home.lbPlace()
        home.pauseSlide(0f, LbPartyView.SIDE_PX); home.pauseSlide(0f)
        return Places(m, l, rr, pl.k, p.layout.classicPlace)
    }

    // ---------------------------------------------------------------- the checks

    private val bad = ArrayList<String>()
    private fun no(tag: String, m: String) { if (bad.size < 60) bad += "$tag: $m" }

    /** while a radial holds the bar: no tap reaches it; [tappable] = the bar takes taps at rest on this page */
    private fun held(tag: String, what: String, home: HomeView) {
        // a bar dropped out of the radial's way (below its laid place) takes no tap; one that stays or rides its column is as it was
        for (s in strips(home)) if (s.visibility == View.VISIBLE && s.translationY > 0.5f && s.takesTouch) no(tag, "$what: the dropped bar takes taps")
    }
    private fun back(tag: String, what: String, home: HomeView, tappable: Boolean) {
        for (s in strips(home)) if (s.visibility == View.VISIBLE) {
            if (kotlin.math.abs(s.translationX) > 0.5f || kotlin.math.abs(s.translationY) > 0.5f) no(tag, "$what: the bar not back (${s.translationX}, ${s.translationY})")
        }
        if (tappable && strips(home).any { it.visibility == View.VISIBLE && !it.takesTouch }) no(tag, "$what: the bar takes no taps after the close")
    }

    private fun check(tag: String, l: HomeLayout, st: St, ref: Places) {
        val p = page(l, st)
        val home = p.home
        val tappable = strips(home).any { it.visibility == View.VISIBLE && it.takesTouch }
        // the state is the one named: a grown fight's band, a live Top screen fight's band above the page, the bar shown in a fight
        val bb = boxes(home).getValue("BARS").bottom
        if (st.fight && st.ct == null && bb < 300f) no(tag, "not a grown fight (band foot $bb)")
        if (st.ct != null && bb > 0f) no(tag, "not a Top screen fight (band foot $bb)")
        if (st.stay && !barShown(home)) no(tag, "the bar not shown in the fight")
        // the middle wheel
        home.wheelSlot(true)?.let { slot ->
            settle()
            for ((k, d) in barsDrawn(home)) if (overlaps(d, slot)) no(tag, "middle wheel ${r(slot)}: $k drawn ${r(d)} over it")
            if (ref.middle != null && kotlin.math.abs(slot.width() - ref.middle.width()) > 1.5f) no(tag, "middle wheel ${r(slot)} not its full size ${r(ref.middle)}")
            held(tag, "middle wheel", home)
            home.wheelSlot(false); settle()
            back(tag, "middle wheel", home, tappable)
        } ?: no(tag, "middle wheel: no slot")
        // the class wheels (the pet wheels too)
        for (left in listOf(true, false)) {
            val nm = if (left) "LB+LT" else "RB+RT"
            val cr = classOpen(p, left)
            for ((k, d) in barsDrawn(home)) if (overlaps(d, cr)) no(tag, "$nm wheel ${r(cr)}: $k drawn ${r(d)} over it")
            val want = if (left) ref.classL else ref.classR
            if (kotlin.math.abs(cr.width() - want.width()) > 1.5f) no(tag, "$nm wheel ${r(cr)} not its full size ${r(want)}")
            held(tag, nm, home)
            classClose(p)
            back(tag, nm, home, tappable)
        }
        // the LB picture
        home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
        val pl = home.lbPlace()
        for (b in LbFit.bars(pl).values.map { RectF(it.l, it.t, it.r, it.b) }) for ((k, d) in barsDrawn(home))
            if (overlaps(d, b)) no(tag, "LB picture: $k drawn ${r(d)} over its bar ${r(b)}")
        // (Classic's held 1516 place is a record's own look: a Split or a top bar is not Classic - compared only with the same look)
        if (p.layout.classicPlace == ref.classic && kotlin.math.abs(pl.k - ref.lbK) > 1e-3f) no(tag, "LB picture at ${pl.k}, not its full size ${ref.lbK}")
        held(tag, "LB picture", home)
        home.pauseSlide(0f, LbPartyView.SIDE_PX); home.pauseSlide(0f)
        back(tag, "LB picture", home, tappable)
        // the pause wheel (the controller up) and the target radial (it stays): every bar off the screen; Across's (laid on the page,
        // no column's) straight down below it, as the other wheels drop it - never sideways
        val acrossBars = acrossLaid(home)
        if (p.layout.cast == HomeLayout.CastDock.ACROSS && barShown(home) && acrossBars.isEmpty()) no(tag, "Across's bar not found on the page")
        for (up in listOf(true, false)) {
            val nm = if (up) "pause" else "target radial"
            home.pauseSlide(UiKit.PAUSE_OPEN_MS, barsUp = up)
            for ((k, d) in barsDrawn(home)) no(tag, "$nm: $k still on the screen ${r(d)}")
            for ((k, top) in acrossBars) {
                val v = views(home).getValue(k)
                if (kotlin.math.abs(v.translationX) > 0.5f) no(tag, "$nm: Across's $k slid ${v.translationX} sideways")
                if (kotlin.math.abs(top + v.translationY - H) > 0.5f) no(tag, "$nm: Across's $k not dropped below the page (${top + v.translationY})")
            }
            held(tag, nm, home)
            home.pauseSlide(0f, barsUp = up)
            back(tag, nm, home, tappable)
        }
    }

    private val DOCKS = listOf(HomeLayout.CastDock.UNDER_CHAT, HomeLayout.CastDock.UNDER_MAP, HomeLayout.CastDock.ABOVE_CHAT,
        HomeLayout.CastDock.ABOVE_MAP, HomeLayout.CastDock.ACROSS, HomeLayout.CastDock.SPLIT)

    private fun matrix(base: HomeLayout, st: St) {
        val off = base.copy(cast = HomeLayout.CastDock.OFF)
        val ref = places(page(off, st))
        for (d in DOCKS) for (size in if (d == HomeLayout.CastDock.ACROSS) HomeLayout.SlotSize.values().toList() else listOf(HomeLayout.SlotSize.L)) {
            val l = base.copy(cast = d, castSize = size)
            check("${d.name.lowercase()} ${size.name}${if (base.mirrored) " swapped" else ""}${if (base.chatShare != HomeLayout.Share.HALF) " ${base.chatShare}" else ""} ${st.name}",
                st.band?.let { l.copy(fight = it) } ?: l, st, ref)
        }
    }

    @Test fun everyRadialKeepsItsFullSizeAtRestAndInPartyMode() {
        for (st in STATES.filter { !it.fight }) for (base in listOf(HomeLayout(), HomeLayout().swapped(), HomeLayout(chatShare = HomeLayout.Share.THIRD)))
            matrix(base, st)
        assertTrue("${bad.size} misfits:\n${bad.joinToString("\n")}", bad.isEmpty())
    }

    @Test fun everyRadialKeepsItsFullSizeInFights() {
        for (st in STATES.filter { it.fight }) for (base in listOf(HomeLayout(), HomeLayout().swapped())) matrix(base, st)
        assertTrue("${bad.size} misfits:\n${bad.joinToString("\n")}", bad.isEmpty())
    }

    /** Across's bar slides DOWN (never sideways: fix/castbar-slides), below the page where Hide in combat lays it, then back; the LB
     *  picture at rest fits above it (it stays: 1.0.31), in a grown fight it needs the room (it drops) */
    @Test fun acrossGoesDownAndComesBack() {
        val p = page(HomeLayout(cast = HomeLayout.CastDock.ACROSS))
        val s = strips(p.home)[0]
        val laid = boxes(p.home).getValue("STRIP")
        p.home.wheelSlot(true); settle()
        assertTrue("down: ${s.translationY}", kotlin.math.abs(laid.top + s.translationY - H) < 0.5f)
        assertTrue("never sideways: ${s.translationX}", kotlin.math.abs(s.translationX) < 0.5f)
        assertTrue(!s.takesTouch)
        p.home.wheelSlot(false); settle()
        assertTrue(s.translationY == 0f && s.takesTouch)
        classOpen(p, false)
        assertTrue("class wheel: down ${s.translationY}", kotlin.math.abs(laid.top + s.translationY - H) < 0.5f && kotlin.math.abs(s.translationX) < 0.5f)
        classClose(p)
        assertTrue(s.translationY == 0f && s.takesTouch)
        p.home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
        assertTrue("LB at rest: it stays (${s.translationX}, ${s.translationY})", s.translationY == 0f && s.translationX == 0f && s.takesTouch)
        p.home.pauseSlide(0f, LbPartyView.SIDE_PX)
        // the pause wheel (the controller up) and the target radial: straight down on the slide's own curve, back up on the close
        for (up in listOf(true, false)) {
            var last = 0f
            for (k in listOf(0.2f, 0.45f, 0.7f, 1f)) {
                p.home.pauseSlide(UiKit.PAUSE_OPEN_MS * k, barsUp = up)
                assertTrue("${if (up) "pause" else "target"} at $k: straight down (${s.translationX}, ${s.translationY})",
                    s.translationX == 0f && s.translationY >= last && laid.top + s.translationY <= H + 0.5f && !s.takesTouch)
                last = s.translationY
            }
            assertTrue("${if (up) "pause" else "target"}: below the page ${s.translationY}", kotlin.math.abs(laid.top + s.translationY - H) < 0.5f)
            p.home.pauseSlide(0f, barsUp = up)
            assertTrue("${if (up) "pause" else "target"}: back (${s.translationX}, ${s.translationY})", s.translationX == 0f && s.translationY == 0f && s.takesTouch)
        }
        val f = page(HomeLayout(cast = HomeLayout.CastDock.ACROSS), STATES[1])
        val fs = strips(f.home)[0]
        val flaid = boxes(f.home).getValue("STRIP")
        f.home.pauseSlide(UiKit.PAUSE_OPEN_MS / 2f, LbPartyView.SIDE_PX)
        val half = fs.translationY
        assertTrue("LB in a fight halfway: $half ${f.home.lbPlace()} ${boxes(f.home)} vis=${fs.visibility}", half > 0f && flaid.top + half < H && !fs.takesTouch)
        f.home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
        assertTrue("LB in a fight: down ${fs.translationY}", kotlin.math.abs(flaid.top + fs.translationY - H) < 0.5f && kotlin.math.abs(fs.translationX) < 0.5f)
        f.home.pauseSlide(0f, LbPartyView.SIDE_PX)
        assertTrue(fs.translationY == 0f && fs.takesTouch)
    }

    /** a fight beginning / ending on [p] as the page's own feeds say it (the controller's state, the quests' "cb") */
    private fun fight(p: Page, on: Boolean) {
        p.home.onFull(JSONObject().put("type", "full").put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true))
            .put("hudS", JSONObject().put("c", on))), true)
        val q = quests(on)
        QuestItem.Cd.onQuests(q, true); p.home.onQuests(q, true)
        p.home.tick()
        settle(60)
    }

    /**
     * review of feat/radial-castbar: a fight beginning or ending UNDER an open radial, grown and live Top screen (Hide in combat: the
     * bar slides below the page with the fight's start, comes back up at its end; Show in combat: it stays and the band grows over the
     * page). No bar drawn over the open middle wheel, class wheel or LB picture as the page is laid again under it; after the close and
     * the fight's end every bar is back in its place (no offset left, a column's bar included) and takes taps
     */
    @Test fun aFightUnderAnOpenRadial() {
        for (d in listOf(HomeLayout.CastDock.UNDER_CHAT, HomeLayout.CastDock.ABOVE_MAP, HomeLayout.CastDock.ACROSS, HomeLayout.CastDock.SPLIT))
            for (top in listOf(false, true)) for (stay in listOf(false, true)) for (inFight in listOf(false, true))
            for (what in listOf("middle wheel", "LB+LT", "RB+RT", "LB picture", "pause", "target radial")) {
                val tag = "${d.name.lowercase()} ${if (top) "Top screen" else "grown"} fight, ${if (stay) "shown" else "hidden"} in it, " +
                    "${if (inFight) "opened in a fight, the fight ends" else "opened at rest, a fight begins"} $what"
                val band = if (top) HomeLayout.FightBand.TOP else HomeLayout.FightBand.GROW
                val l = HomeLayout(cast = d, castSize = HomeLayout.SlotSize.L, fight = band)
                val p = page(l, St(if (inFight) "fight" else "rest", fight = inFight, stay = stay, ct = if (top) 1 else null, band = band))
                val home = p.home
                fun clear(when_: String) {
                    // (the pause wheel / the target radial: every bar off the screen)
                    val boxes = if (what == "pause" || what == "target radial") listOf(RectF(0f, 0f, W.toFloat(), H.toFloat()))
                        else if (what == "LB picture") LbFit.bars(home.lbPlace()).values.map { RectF(it.l, it.t, it.r, it.b) }
                        else if (what == "middle wheel") listOf(RectF(field(home, "wheelRect") as RectF))
                        else listOf(RectF(field(home, "classRect") as Rect))
                    for (b in boxes) for ((k, dr) in barsDrawn(home)) if (overlaps(dr, b)) no(tag, "$when_: $k drawn ${r(dr)} over ${r(b)}")
                    held(tag, when_, home)
                }
                // the state is the one named: the band grown / gone up to the Top screen in the fight, the bar shown or slid away
                fun named(fightOn: Boolean, when_: String) {
                    val bb = boxes(home).getValue("BARS").bottom
                    // (slid away: below the page, or a top bar up behind the band; a Top screen fight keeps the bar on the bottom)
                    val laid = boxes(home).filter { (k, b) -> (k == "STRIP" || k == "CAST2") && b.top < H - 0.5f && b.bottom > bb + 0.5f }.isNotEmpty()
                    if (fightOn && top && bb > 0f) no(tag, "$when_: not a Top screen fight (band foot $bb)")
                    if (fightOn && !top && bb < 300f) no(tag, "$when_: not a grown fight (band foot $bb)")
                    if (laid != (stay || !fightOn || top)) no(tag, "$when_: the bar ${if (laid) "on" else "off"} the page")
                }
                named(inFight, "at the open")
                when (what) {
                    "LB picture" -> home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
                    "pause", "target radial" -> home.pauseSlide(UiKit.PAUSE_OPEN_MS, barsUp = what == "pause")
                    "middle wheel" -> { home.wheelSlot(true); settle() }
                    else -> classOpen(p, what == "LB+LT")
                }
                clear("open")
                fight(p, !inFight)
                named(!inFight, "laid again")
                clear("the page laid again under it")
                when (what) {
                    "LB picture" -> { home.pauseSlide(0f, LbPartyView.SIDE_PX); home.pauseSlide(0f) }
                    "pause", "target radial" -> home.pauseSlide(0f, barsUp = what == "pause")
                    "middle wheel" -> { home.wheelSlot(false); settle() }
                    else -> classClose(p)
                }
                if (!inFight) fight(p, false)
                settle(40)
                back(tag, "after the close and the fight", home, true)
            }
        assertTrue("${bad.size} misfits:\n${bad.joinToString("\n")}", bad.isEmpty())
    }

    /**
     * review of feat/radial-castbar: a bar slid away for a fight (Hide in combat: Across's below the page, a top bar - Above the chat /
     * the map - UP behind the grown band) is no column's but already off the page: no radial moves it (a drop swept the top bar down
     * across the page, over the radial, on every open and back up on every close)
     */
    @Test fun aBarSlidAwayForAFightStaysPut() {
        for (d in DOCKS) {
            val tag = "${d.name.lowercase()} hidden in a grown fight"
            val p = page(HomeLayout(cast = d, castSize = HomeLayout.SlotSize.L, fight = HomeLayout.FightBand.GROW),
                St("fight_grow_hidden", fight = true, band = HomeLayout.FightBand.GROW))
            fun still(what: String) { for (s in strips(p.home)) if (s.visibility == View.VISIBLE && (s.translationX != 0f || s.translationY != 0f))
                no(tag, "$what: the bar moved (${s.translationX}, ${s.translationY})") }
            p.home.wheelSlot(true); settle(); still("middle wheel"); p.home.wheelSlot(false); settle()
            classOpen(p, true); still("LB+LT"); classClose(p)
            classOpen(p, false); still("RB+RT"); classClose(p)
            p.home.pauseSlide(UiKit.PAUSE_OPEN_MS / 2f, LbPartyView.SIDE_PX); p.home.lbPlace(); still("LB picture halfway")
            p.home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX); p.home.lbPlace(); still("LB picture")
            p.home.pauseSlide(0f, LbPartyView.SIDE_PX)
            // review: the pause wheel and the target radial leave Across's slid-away bar where it is too ("All wheels move the Across
            // bar the same way"; it went off to the left with the chat). A column's bar still goes with its column
            if (d == HomeLayout.CastDock.ACROSS) for (up in listOf(true, false)) {
                val nm = if (up) "pause" else "target radial"
                p.home.pauseSlide(UiKit.PAUSE_OPEN_MS / 2f, barsUp = up); still("$nm halfway")
                p.home.pauseSlide(UiKit.PAUSE_OPEN_MS, barsUp = up); still(nm)
                p.home.pauseSlide(0f, barsUp = up); still("$nm closed")
            }
        }
        assertTrue("${bad.size} misfits:\n${bad.joinToString("\n")}", bad.isEmpty())
    }

    /**
     * review: the pause wheel's / the target radial's slide never lifts Across's bar on its open - a bar still on its way back up from
     * a wheel just closed goes on down from where it is (it jumped back onto the page first), and a fight beginning mid-open (Hide in
     * combat: the page lays the bar below it) keeps it going down, never sideways (it popped back up onto the page and slid left with
     * the chat); on the close it only comes back up, then takes taps again
     */
    @Test fun theSlideNeverLiftsAcrossBar() {
        for (up in listOf(true, false)) {
            val nm = if (up) "pause" else "target radial"
            var p = page(HomeLayout(cast = HomeLayout.CastDock.ACROSS, castSize = HomeLayout.SlotSize.L, fight = HomeLayout.FightBand.GROW))
            var s = strips(p.home)[0]
            s.translationY = 70f                                         // (a wheel's bar on its way back up)
            p.home.pauseSlide(UiKit.PAUSE_OPEN_MS * 0.05f, barsUp = up)
            if (s.translationY < 70f - 0.5f || s.translationX != 0f) no(nm, "opened under a bar on its way up: lifted to (${s.translationX}, ${s.translationY})")
            p.home.pauseSlide(UiKit.PAUSE_OPEN_MS * 0.6f, barsUp = up)
            var last = s.translationY
            for (k in listOf(0.5f, 0.3f, 0.1f)) {
                p.home.pauseSlide(UiKit.PAUSE_OPEN_MS * k, barsUp = up)
                if (s.translationY > last + 0.5f || s.translationX != 0f) no(nm, "closing at $k: down again (${s.translationX}, ${s.translationY} after $last)")
                last = s.translationY
            }
            p.home.pauseSlide(0f, barsUp = up)
            back(nm, "after the close", p.home, true)
            // a fight beginning mid-open, Hide in combat
            p = page(HomeLayout(cast = HomeLayout.CastDock.ACROSS, castSize = HomeLayout.SlotSize.L, fight = HomeLayout.FightBand.GROW))
            s = strips(p.home)[0]
            val home = p.home
            fun drawnTop() = boxes(home).getValue("STRIP").top + s.translationY
            home.pauseSlide(UiKit.PAUSE_OPEN_MS * 0.3f, barsUp = up)
            var top = drawnTop()
            home.onFull(JSONObject().put("type", "full").put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true))
                .put("hudS", JSONObject().put("c", true))), true)
            val q = quests(true)
            QuestItem.Cd.onQuests(q, true); home.onQuests(q, true)
            home.tick()
            for (i in 1..30) {
                settle(1)
                home.pauseSlide((UiKit.PAUSE_OPEN_MS * (0.3f + i * 0.03f)).coerceAtMost(UiKit.PAUSE_OPEN_MS), barsUp = up)
                val d = drawnTop()
                if (d < top - 0.5f || s.translationX != 0f) no(nm, "a fight beginning mid-open, frame $i: the bar rose / slid to (${s.translationX}, $d after $top)")
                top = d
            }
            settle(60)
            for ((k, d) in barsDrawn(home)) no(nm, "in the fight: $k on the page ${r(d)}")
            home.pauseSlide(0f, barsUp = up)
            fight(p, false)
            settle(40)
            back(nm, "after the close and the fight", home, true)
        }
        assertTrue("${bad.size} misfits:\n${bad.joinToString("\n")}", bad.isEmpty())
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

    @Test fun shots() {
        if (out == null) return
        val docks = listOf("bottom_under_chat" to HomeLayout.CastDock.UNDER_CHAT, "across" to HomeLayout.CastDock.ACROSS,
            "above_chat" to HomeLayout.CastDock.ABOVE_CHAT, "above_map" to HomeLayout.CastDock.ABOVE_MAP)
        for ((tag, d) in docks) {
            val l = HomeLayout(cast = d, castSize = HomeLayout.SlotSize.L)
            var p = page(l)
            save(p, "${tag}_0_rest", "$tag, L: at rest")
            val slot = p.home.wheelSlot(true)!!
            p.home.wheelPainter = { c -> c.drawCircle(slot.centerX(), slot.centerY(), slot.width() / 2f, disc) }
            settle()
            save(p, "${tag}_1_middle_wheel", "$tag, L: middle wheel (D-pad Up / spell radial) ${slot.width().toInt()} px") { c -> c.drawRect(slot, pCyan) }
            p.home.wheelSlot(false); settle(); p.home.wheelPainter = null
            for (left in listOf(true, false)) {
                val cr = classOpen(p, left)
                save(p, "${tag}_2_class_${if (left) "lb_lt" else "rb_rt"}", "$tag, L: ${if (left) "LB+LT" else "RB+RT"} class wheel ${cr.width().toInt()} px") { c -> c.drawRect(cr, pRed) }
                classClose(p)
            }
            p = page(l)
            p.home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
            val pl = p.home.lbPlace()
            val lbv = lbView(pl)
            save(p, "${tag}_3_lb_picture", "$tag, L: LB picture at ${"%.2f".format(pl.k)}") { c -> lbv.draw(c) }
            p.home.pauseSlide(0f, LbPartyView.SIDE_PX)
            p = page(l, STATES[1])
            p.home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
            val fpl = p.home.lbPlace()
            val flb = lbView(fpl)
            save(p, "${tag}_3_lb_picture_fight", "$tag, L: LB picture in a fight, bar shown, at ${"%.2f".format(fpl.k)}") { c -> flb.draw(c) }
            p.home.pauseSlide(0f, LbPartyView.SIDE_PX)
            p = page(l)
            p.home.pauseSlide(UiKit.PAUSE_OPEN_MS, barsUp = true)
            save(p, "${tag}_4_pause_slide", "$tag, L: pause wheel's slide (the controller up)")
            p.home.pauseSlide(0f, barsUp = true)
            p = page(l)
            p.home.pauseSlide(UiKit.PAUSE_OPEN_MS, barsUp = false)
            save(p, "${tag}_5_target_radial", "$tag, L: target radial's slide (the controller stays)")
            p.home.pauseSlide(0f, barsUp = false)
        }
        // partway through the pause wheel's slide (Across, L: the bar on its way straight down, the chat's curve)
        run {
            val p = page(HomeLayout(cast = HomeLayout.CastDock.ACROSS, castSize = HomeLayout.SlotSize.L))
            p.home.pauseSlide(UiKit.PAUSE_OPEN_MS * 0.3f, barsUp = true)
            save(p, "across_7_pause_slide_midslide", "across, L: pause wheel's slide (30 %)")
        }
        // halfway through the LB picture's open (Across, L, a grown fight with the bar shown: it drops on the sides' curve). The
        // middle and class wheels' opens run on view animators, which the off-device clock does not stop halfway
        run {
            val l = HomeLayout(cast = HomeLayout.CastDock.ACROSS, castSize = HomeLayout.SlotSize.L)
            val p = page(l, STATES[1])
            p.home.pauseSlide(UiKit.PAUSE_OPEN_MS * 0.35f, LbPartyView.SIDE_PX)
            val pl = p.home.lbPlace()
            val lbv = lbView(pl)
            save(p, "across_6_lb_picture_fight_midslide", "across, L: LB picture opening in a fight (35 %)") { c -> lbv.draw(c) }
        }
    }
}
