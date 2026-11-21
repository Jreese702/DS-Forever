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
 * fix/menus-fit (the user on 1516: "The LB menu doesn't fit right with the map swapped and bigger and probably a lot of other menus.
 * You need to fix that."): EVERY MENU OVER HOME x every offered preset x swapped x the biggest map share (chat 1/3) x rest / a grown fight
 * / Party mode, on the live HomeView. Each one is measured on the page as drawn (each part's laid box moved by its view's translation):
 *  - the LB picture (LbPartyView at HomeView.lbPlace): inside the screen, under the band (not Classic's held fight look), no part over it,
 *    its bars at least LbFit.K_MIN (the text readable), the map's own paint riding the map (not Classic's held look);
 *  - the pause / target radial's slide: every part off the screen, off ITS OWN edge (its column's), the map's paint with it;
 *  - the middle wheel (D-pad Up / the spell radial): inside the frame, under the band, no part over it;
 *  - both class wheels: inside the frame, under the band, no part over them;
 *  - the D-pad toast: inside the frame, never over the band's buttons when the band is the thin row or grown;
 *  - edit mode's CHAT / MAP menus (at rest): inside the screen, under the band;
 *  - the party menu's place: inside the chat's column, under the band.
 * PNG contact sheets with MENUS_FIT_SHOTS=<dir>.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeMenusFitShotsTest {
    private val out: File? = System.getenv("MENUS_FIT_SHOTS")?.let { File(it).apply { mkdirs() } }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private val G = UiKit.MODULE_GAP

    @After fun down() { setField(PartyModel.live, "party", null); HomeLayoutStore.character(null); HomeLayoutStore.set(HomeLayout()) }

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

    private class Page(val home: HomeView, val cast: CastView, val layout: HomeLayout)
    private fun art() { System.getenv("HOME_LAYOUT_ART")?.let { root ->
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) } }
    private val MIRROR = mapOf(1 to "map", 2 to "radial", 3 to "chat", 4 to "home")

    private fun page(layout: HomeLayout, fight: Boolean = false, party: Boolean = false): Page {
        art()
        app.getSharedPreferences("home", 0).edit().clear().commit()
        HomeSettings.init(app); Settings.init(app)
        HomeLayoutStore.character(null)
        HomeLayoutStore.set(layout)
        CastStripModel.Live.pos = 0f; CastStripModel.Live.side = null
        UiKit.pauseBarsBottom = 0f
        setField(PartyModel.live, "party", null)
        PartyModel.live.onParty(PartyModel.parseParty(partyDoc(party))!!, true, System.currentTimeMillis())
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        lay(home)
        cast.roomBarOn = true
        cast.roomBar = if (layout.mirrored) MIRROR else HoldTalk.DEFAULT_DIRS
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        val bar = CastStripModel.parse(castDoc)!!
        CastStripModel.Live.set(bar); home.onCastBar(bar)
        home.onFull(JSONObject().put("type", "full").put("map", JSONObject().put("zone", "Elwynn Forest").put("pvp", "contested"))
            .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true).put("hudS", JSONObject().put("c", fight)))), true)
        val q = quests(fight)
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
        return Page(home, cast, layout)
    }

    @Suppress("UNCHECKED_CAST")
    private fun boxes(home: HomeView): Map<String, RectF> = (field(home, "now") as Map<Any, RectF>).entries.associate { it.key.toString() to RectF(it.value) }
    @Suppress("UNCHECKED_CAST")
    private fun views(home: HomeView): Map<String, View> = (field(home, "views") as Map<Any, View>).entries.associate { it.key.toString() to it.value }

    /** each shown part's box as drawn now (its laid box moved by its translation); the buff list lies in the map, a bar slid away for
     *  a fight below the page */
    private fun drawn(home: HomeView): Map<String, RectF> {
        val b = boxes(home); val v = views(home)
        return b.filter { (k, r) -> k != "AURAS" && k != "BARS" && v[k]?.visibility == View.VISIBLE && r.top < H }.mapValues { (k, r) ->
            val vv = v.getValue(k); RectF(r).apply { offset(vv.translationX, vv.translationY) } }
    }
    private fun band(home: HomeView): RectF = boxes(home).getValue("BARS")

    private fun overlaps(a: RectF, b: RectF): Boolean = a.left < b.right - 0.5f && b.left < a.right - 0.5f && a.top < b.bottom - 0.5f && b.top < a.bottom - 0.5f
    private fun inside(a: RectF, l: Float, t: Float, r: Float, b: Float) = a.left >= l - 0.5f && a.top >= t - 0.5f && a.right <= r + 0.5f && a.bottom <= b + 0.5f
    private fun r(x: RectF) = "${x.left.toInt()}..${x.right.toInt()} x ${x.top.toInt()}..${x.bottom.toInt()}"
    private fun classic(l: HomeLayout) = HomePresets.byId(HomePresets.CLASSIC)!!.holds(l)

    // ---------------------------------------------------------------- the pictures (MENUS_FIT_SHOTS only)

    private val tiles = ArrayList<Pair<String, Bitmap>>()
    private val pRed = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.RED }
    private val pCyan = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.CYAN }
    private val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Color.YELLOW; isFakeBoldText = true }
    private val scratch by lazy { Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888) }

    /** draws the page (every menu's paint runs, in every case); kept as a tile only with MENUS_FIT_SHOTS */
    private fun render(p: Page, name: String, over: ((Canvas) -> Unit)? = null) {
        val b = if (out != null) Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888) else scratch
        val c = Canvas(b); c.drawColor(Theme.ink); p.home.draw(c); over?.invoke(c)
        if (out != null) tiles += name to b
    }
    private fun sheet(name: String) {
        val d = out ?: return
        if (tiles.isEmpty()) return
        val k = 0.4f; val tw = (W * k).toInt(); val th = (H * k).toInt()
        val cols = 4; val rows = (tiles.size + cols - 1) / cols
        val b = Bitmap.createBitmap(cols * tw + (cols - 1) * 6, rows * th + (rows - 1) * 6 + 40, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.DKGRAY)
        c.drawText(name, 8f, 30f, pLabel)
        for ((i, t) in tiles.withIndex()) {
            val x = (i % cols) * (tw + 6); val y = 40 + (i / cols) * (th + 6)
            c.drawBitmap(t.second, null, Rect(x, y, x + tw, y + th), Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawText(t.first, x + 8f, y + th - 10f, pLabel)
        }
        File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tiles.clear()
    }

    // ---------------------------------------------------------------- the checks

    private val bad = ArrayList<String>()
    private fun no(tag: String, m: String) { if (bad.size < 40) bad += "$tag: $m" }

    private fun lbPicture(tag: String, p: Page, fight: Boolean) {
        val home = p.home
        val cl = classic(p.layout)
        home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
        val pl = home.lbPlace()
        val bars = LbFit.bars(pl).values.map { RectF(it.l, it.t, it.r, it.b) }
        if (pl.k < LbFit.K_MIN - 1e-3f) no(tag, "LB picture scaled to ${pl.k} (text under ${28f * pl.k} px)")
        for (b in bars) {
            if (!inside(b, 0f, 0f, W.toFloat(), H.toFloat())) no(tag, "LB bar ${r(b)} off the screen")
            // Classic's grown-fight top is 1516's (held for the user's yes): the picture over the band there is today's look
            if (!(cl && fight) && b.top < band(home).bottom) no(tag, "LB bar ${r(b)} under the band (bottom ${band(home).bottom})")
        }
        for ((k, d) in drawn(home)) for (b in bars) if (overlaps(d, b)) no(tag, "LB: $k drawn ${r(d)} covers the picture's bar ${r(b)}")
        // the map's own paint (the well edge, the zone band, the calendar) rides the map (Classic: 1516's, held)
        val mapV = views(home).getValue("MAP")
        if (!cl && mapV.visibility == View.VISIBLE && kotlin.math.abs(home.mapPaintDx() - mapV.translationX) > 1f)
            no(tag, "LB: the map's paint moved ${home.mapPaintDx()}, the map ${mapV.translationX}")
        val lbv = LbPartyView(app)
        lbv.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        lbv.layout(0, 0, W, 1080)
        lbv.place = { pl }
        lbv.lb(true); ShadowSystemClock.advanceBy(Duration.ofMillis(600))
        render(p, "$tag LB") { c -> lbv.draw(c) }
        home.pauseSlide(0f, LbPartyView.SIDE_PX)
        home.pauseSlide(0f)
    }

    private fun pauseSlide(tag: String, p: Page) {
        val home = p.home
        val laid = boxes(home)
        home.pauseSlide(UiKit.PAUSE_OPEN_MS)
        for ((k, d) in drawn(home)) {
            val l0 = laid.getValue(k)
            if (d.right > 0.5f && d.left < W - 0.5f) no(tag, "pause: $k still on screen ${r(d)}")
            if (l0.left <= G + 1f && l0.right >= W - G - 1f) continue                 // a full-screen module keeps its own side
            // the Both bar's own rule (cb3: it goes left with the chat) is kept
            if (k == "STRIP" && l0.width() > W / 2f) continue
            val dir = d.left - l0.left
            if (dir != 0f && (dir < 0f) != (l0.centerX() < W / 2f)) no(tag, "pause: $k (centre ${l0.centerX().toInt()}) slid ${dir.toInt()} across the page")
        }
        val mapV = views(home).getValue("MAP")
        if (!classic(p.layout) && mapV.visibility == View.VISIBLE && kotlin.math.abs(home.mapPaintDx() - mapV.translationX) > 1f)
            no(tag, "pause: the map's paint moved ${home.mapPaintDx()}, the map ${mapV.translationX}")
        home.pauseSlide(UiKit.PAUSE_OPEN_MS * 0.45f)
        render(p, "$tag pause")
        home.pauseSlide(0f)
    }

    private fun middleWheel(tag: String, p: Page) {
        val home = p.home
        val slot = home.wheelSlot(true) ?: run { no(tag, "middle wheel: no slot"); return }
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 200, 160, 40) }
        home.wheelPainter = { c -> c.drawCircle(slot.centerX(), slot.centerY(), slot.width() / 2f, disc) }
        settle(30)
        if (!inside(slot, G, band(home).bottom, W - G, H - G)) no(tag, "middle wheel ${r(slot)} off the frame or under the band")
        for ((k, d) in drawn(home)) if (overlaps(d, slot)) no(tag, "middle wheel: $k drawn ${r(d)} covers it ${r(slot)}")
        render(p, "$tag middle") { c -> c.drawRect(slot, pCyan) }
        home.wheelSlot(false); settle(30)
        home.wheelPainter = null
    }

    private fun classWheels(tag: String, p: Page) {
        val home = p.home
        val show = HomeView::class.java.getDeclaredMethod("classLayerShow", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        for (side in listOf(1, 2)) {
            val flow = field(p.cast, "classFlow")!!
            flow.javaClass.getDeclaredField("side").apply { isAccessible = true }.setInt(flow, side)
            show.invoke(home, true); settle(40)
            val cr = RectF(field(home, "classRect") as Rect)
            val nm = if (side == 1) "LB+LT" else "RB+RT"
            if (!inside(cr, 0f, band(home).bottom, W.toFloat(), H - G)) no(tag, "$nm wheel ${r(cr)} off the frame or under the band")
            for ((k, d) in drawn(home)) if (overlaps(d, cr)) no(tag, "$nm: $k drawn ${r(d)} covers the wheel ${r(cr)}")
            render(p, "$tag $nm") { c -> c.drawRect(cr, pRed) }
            show.invoke(home, false); settle(40)
        }
    }

    private fun toast(tag: String, p: Page) {
        val home = p.home
        val bb = band(home)
        val box = home.toastBox(380f)
        if (!inside(box, 0f, 0f, W.toFloat(), H.toFloat())) no(tag, "toast ${r(box)} off the frame")
        val diamonds = kotlin.math.abs(bb.bottom - HomeLayoutSolver.barsBottom(HomeLayout(), HomeLayoutSolver.State())) < 1f
        if (!diamonds && box.top < bb.bottom) no(tag, "toast ${r(box)} over the band (bottom ${bb.bottom})")
    }

    private fun partyMenu(tag: String, p: Page) {
        val home = p.home
        val chat = boxes(home)["CHAT"] ?: return
        val m = home.partyMenu
        val box = RectF(m.left.toFloat(), m.top.toFloat(), m.right.toFloat(), m.bottom.toFloat())
        if (box.left < chat.left - 0.5f || box.right > chat.right + 0.5f) no(tag, "party menu ${r(box)} outside the chat's column ${r(chat)}")
        if (box.top < band(home).bottom || box.bottom > H) no(tag, "party menu ${r(box)} under the band or off the frame")
    }

    private fun editMenus(tag: String, l: HomeLayout) {
        for (s in listOf(EditStop.CHAT, EditStop.MAP)) {
            val p = page(l)
            if (!p.home.editOpen(s)) { no(tag, "edit $s refused"); continue }
            settle()
            p.home.editAct { it.openMenu(s); emptyList() }; settle()
            val box = p.home.editChrome.menuBox(s)
            if (!inside(box, 0f, band(p.home).bottom, W.toFloat(), H.toFloat())) no(tag, "edit $s menu ${r(box)} off the screen or under the band")
            render(p, "$tag edit $s")
            p.home.editCancel(); settle()
        }
    }

    // ---------------------------------------------------------------- the matrix

    /** every offered preset, swapped, at the biggest map share (and Big chat's 2/3), one per placement */
    private fun matrix(): List<Pair<String, HomeLayout>> {
        val base = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
        val out = ArrayList<Pair<String, HomeLayout>>()
        for (pr in HomePresets.OFFERED) {
            val l = pr.apply(base)
            val id = pr.id.lowercase().replace(' ', '_')
            out += id to l
            out += "${id}_swapped" to l.swapped()
            if (l.chatShare != HomeLayout.Share.THIRD) {
                out += "${id}_bigmap" to l.copy(chatShare = HomeLayout.Share.THIRD)
                out += "${id}_bigmap_swapped" to l.copy(chatShare = HomeLayout.Share.THIRD).swapped()
            }
        }
        val seen = ArrayList<HomeLayout>()
        return out.filter { (_, l) -> if (seen.any { it.samePlace(l) }) false else { seen += l; true } }
    }

    private fun run(state: String, fight: Boolean, party: Boolean, edit: Boolean, grow: Boolean) {
        for ((id, l0) in matrix()) {
            val l = if (grow) l0.copy(fight = HomeLayout.FightBand.GROW) else l0
            val tag = "$id $state"
            var p = page(l, fight = fight, party = party)
            lbPicture(tag, p, fight)
            p = page(l, fight = fight, party = party)
            pauseSlide(tag, p)
            p = page(l, fight = fight, party = party)
            middleWheel(tag, p)
            classWheels(tag, p)
            toast(tag, p)
            partyMenu(tag, p)
            if (edit) editMenus(tag, l)
            sheet("${id}__$state")
        }
        assertTrue("${bad.size} misfits:\n${bad.joinToString("\n")}", bad.isEmpty())
    }

    @Test fun everyMenuFitsAtRest() = run("rest", fight = false, party = false, edit = true, grow = false)
    @Test fun everyMenuFitsInAGrownFight() = run("fightgrow", fight = true, party = false, edit = false, grow = true)
    @Test fun everyMenuFitsInEachPresetsOwnFight() = run("fight", fight = true, party = false, edit = false, grow = false)
    @Test fun everyMenuFitsWithPartyMode() = run("partymode", fight = false, party = true, edit = false, grow = false)
}
