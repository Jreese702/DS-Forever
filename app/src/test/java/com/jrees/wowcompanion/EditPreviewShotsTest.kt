package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
 * EDIT HOME'S TOP-SCREEN PREVIEW AND THE BIGGER PENCILS ON THE REAL HOME (the user on test 1530, 2026-11-25: "When choosing the top screen
 * options in combat it should also display the top screen ui so that its easy to visualize. Likewise the party mode should show a full
 * party on the top screen so its easy to visualize. The in fight pencil is way to small in edit mode to reasonably expect someone to try
 * tapping it"): HomeView.editPreviewCode follows the session - none before it opens and on Out of combat, the In combat tab's Top picks
 * (35 / 34 / 33 as the picks change), the Party mode tab's full party (36), none while a fight parks it and its own code again after,
 * none after Done - and the host hears every change (onEditPreview); a finger 50 px off the fight pencil's centre opens its bubble (its
 * 48 dp touch box). EDIT_PREVIEW_SHOTS = the shots' folder (none: only the checks), EDIT_PREVIEW_TAG = the pencil shots' name
 * ("after"; "before" on the 1530 pencil), HOME_LAYOUT_ART = an art root.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class EditPreviewShotsTest {
    private val out: File? = System.getenv("EDIT_PREVIEW_SHOTS")?.let { File(it).apply { mkdirs() } }
    private val tag: String = System.getenv("EDIT_PREVIEW_TAG") ?: "after"
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private val homes = ArrayList<HomeView>()

    @After fun down() {
        for (h in homes) runCatching { h.editCancel() }
        setField(PartyModel.live, "party", null); HomeLayoutStore.character(null); HomeLayoutStore.set(HomeLayout())
        HomeSettings.swingTimer = true
    }

    private fun lay(v: View, w: Int = W, h: Int = H) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun settle(n: Int = 30) { for (i in 0 until n) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }
    private fun setField(o: Any, name: String, v: Any?) { o.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(o, v) }
    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)

    private val castDoc = JSONObject("""{"type":"castbar","v":"cb6","n":8,"pick":8,"pp":[39,19],"e":[
        {"s":1,"id":403,"i":136048,"c":0},{"s":2,"id":8042,"i":136026,"c":1},{"s":3,"id":331,"i":136052,"c":2},
        {"s":4,"id":324,"i":136051,"c":3},{"s":5,"id":8017,"i":136086,"c":4},{"s":6,"id":2484,"i":136102,"c":5},
        {"s":7,"id":8018,"i":136015,"c":6},{"s":8,"id":5730,"i":135824,"c":7}]}""")
    private val PAD = JSONObject().put("v", "pad22").put("x", 0).put("y", 12).put("cw", 6).put("ch", 2).put("cols", 40).put("pw", 1920).put("ph", 1080)
        .put("rows", JSONObject().put("qpick", 3).put("qact", 4).put("pfocus", 9))
        .put("sizes", JSONObject().put("qpick", 40).put("qact", 40).put("pfocus", 31))
    private fun quests(): JSONObject {
        val focused = JSONObject().put("id", 60).put("title", "Kobold Candles").put("zone", "Elwynn Forest").put("lv", 7).put("slot", 1)
            .put("watch", 1).put("focus", true)
            .put("obj", JSONArray().put(JSONObject().put("t", "Wax Candles: 3/8").put("have", 3).put("need", 8)))
        return JSONObject().put("plv", 7).put("focus", 60).put("rwMode", "id").put("list", JSONArray().put(focused)).put("pad", PAD).put("cb", false)
    }
    private val chatLines = listOf("general" to "[General] Thornbrook: LF2M Deadmines", "guild" to "[Guild] Mirelle: grats on 12!",
        "say" to "Oswin says: anyone near Goldshire?", "loot" to "You receive loot: Broken Boar Tusk.",
        "whisper" to "Seraphine whispers: inv pls", "party" to "[Party] Iselle: pulling next pack", "guild" to "[Guild] Aldric: gz on 20!",
        "say" to "Korvash says: on my way", "system" to "Quest accepted: Kobold Candles", "general" to "[Trade] Odwin: WTS Copper Bars")
    private val roster = listOf(
        """{"u":"player","i":0,"n":"Korvash","c":"SHAMAN","role":"HEALER","pt":"MANA","lv":20,"dead":false,"off":false,"col":"ff0070dd"}""",
        """{"u":"party1","i":1,"n":"Brannoc","c":"WARRIOR","role":"TANK","pt":"RAGE","lv":21,"dead":false,"off":false,"col":"ffc69b6d"}""",
        """{"u":"party2","i":2,"n":"Seraphine","c":"MAGE","role":"DAMAGER","pt":"MANA","lv":20,"dead":false,"off":false,"col":"ff3fc7eb"}""",
        """{"u":"party3","i":3,"n":"Tamsin","c":"ROGUE","role":"DAMAGER","pt":"ENERGY","lv":20,"dead":false,"off":false,"col":"fffff468"}""")
    private fun partyDoc(on: Boolean, fight: Boolean = false): JSONObject {
        val m = JSONArray(); for (r in roster) m.put(JSONObject(r))
        return JSONObject().put("type", "party").put("v", "m77").put("rs", 1)
            .put("p", JSONObject().put("on", on).put("box", 1).put("fight", fight).put("grp", true).put("sel", "bound").put("m", m))
    }

    private class Page(val home: HomeView, val cast: CastView, val strip: Strip)

    private fun art() { System.getenv("HOME_LAYOUT_ART")?.let { root ->
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) } }

    private fun page(layout: HomeLayout, party: Boolean = true, partyOn: Boolean = false, ct: Int = 1, cc: Int = 1): Page {
        art()
        app.getSharedPreferences("home", 0).edit().clear().commit()
        HomeSettings.init(app); Settings.init(app); Tr.init(app)
        HomeLayoutStore.character(null)
        HomeLayoutStore.set(layout)
        CastStripModel.Live.pos = 0f; CastStripModel.Live.side = null
        setField(PartyModel.live, "party", null)
        if (party) PartyModel.live.onParty(PartyModel.parseParty(partyDoc(partyOn))!!, true, System.currentTimeMillis())
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        homes += home
        home.forceLive = true
        home.borrow()
        home.onPadMap(PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m188","m":{"page":1,"sets":[],"dpad":{"on":true,"want":true,"ct":$ct,"cc":$cc,"pv":0,"gsw":1}}}"""))!!)
        lay(home)
        cast.roomBarOn = true
        cast.roomBar = HoldTalk.DEFAULT_DIRS
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        val bar = CastStripModel.parse(castDoc)!!
        CastStripModel.Live.set(bar); home.onCastBar(bar)
        fightWord(home, cast, false)
        val q = quests()
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
        val strip = Strip(app) { }
        strip.active = Room.HOME
        home.onEditChrome = { v ->
            strip.editRibbon = v?.let { ev -> { c: Canvas, w: Float, top: Float, g0: Float, g1: Float -> ev.drawRibbon(c, w, top, g0, g1) } }
            strip.editTick = v != null
            strip.editTouch = v?.let { ev -> { x: Float, y: Float, action: Int -> ev.ribbonTouch(x, y, action) } }
            strip.editBar = v?.let { ev -> { c: Canvas, layer: Int -> ev.drawBar(c, layer) } }
            strip.editBarTouch = v?.let { ev -> { e: MotionEvent -> ev.barTouch(e) } }
            strip.editPose = v?.let { ev -> { r: Room, o: FloatArray -> ev.roomPose(r, o) } }
            strip.editApps = v?.apps()
        }
        home.roomBar = strip
        strip.showOrder(RoomBar.shown(HomeSettings.layout), 0L)
        return Page(home, cast, strip)
    }

    private fun fightWord(home: HomeView, cast: CastView, on: Boolean) {
        val ab = JSONObject().put("hud", JSONObject().put("ok", true)).put("hudS", JSONObject().put("c", on).put("g", 5000.0))
        home.onFull(JSONObject().put("type", "full").put("map", JSONObject().put("zone", "Elwynn Forest").put("pvp", "contested"))
            .put("actionbar", ab), true)
        cast.update(ab)
        home.tick(); ShadowLooper.idleMainLooper()
    }

    /** the screen as the Thor shows it: Home (its own zoom and tab row while editing) over the room bar's strip; [zoomOn] = a box in
     *  Home's px shown x3 beside it (the pencil shots) */
    private fun shot(p: Page, name: String, zoomOn: RectF? = null) {
        val o = out ?: return
        val b = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            setField(p.home, "clockText", "9:51 AM"); setField(p.home, "batteryPct", 98)
            lay(p.home)
            val c = Canvas(b); c.drawColor(Theme.ink)
            p.home.draw(c)
            p.strip.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Strip.HEIGHT, View.MeasureSpec.EXACTLY))
            p.strip.layout(0, 0, W, Strip.HEIGHT)
            c.save(); c.translate(0f, H.toFloat()); p.strip.draw(c); c.restore()
            if (pass == 0) settle(10)
        }
        val img = if (zoomOn == null) b else {
            val a = p.home.screenOf(zoomOn.left, zoomOn.top); val z = p.home.screenOf(zoomOn.right, zoomOn.bottom)
            val pad = 70f
            val src = android.graphics.Rect((a[0] - pad).toInt().coerceAtLeast(0), (a[1] - pad).toInt().coerceAtLeast(0),
                (z[0] + pad).toInt().coerceAtMost(W), (z[1] + pad).toInt().coerceAtMost(1080))
            val k = 3
            val wide = Bitmap.createBitmap(W + src.width() * k + 24, maxOf(1080, src.height() * k), Bitmap.Config.ARGB_8888)
            val c = Canvas(wide); c.drawColor(Color.rgb(22, 22, 22))
            c.drawBitmap(b, 0f, 0f, null)
            c.drawBitmap(b, src, android.graphics.Rect(W + 24, 0, W + 24 + src.width() * k, src.height() * k), Paint().apply { isFilterBitmap = false })
            b.recycle()
            wide
        }
        File(o, "$name.png").outputStream().use { img.compress(Bitmap.CompressFormat.PNG, 100, it) }
        img.recycle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun boxes(home: HomeView): Map<String, RectF> = (field(home, "now") as Map<Any, RectF>).entries.associate { it.key.toString() to RectF(it.value) }
    private fun model(p: Page) = p.home.editModel()!!
    private fun key(p: Page, k: String, n: Int = 30) { p.home.editKey(k); settle(n) }
    private fun touch(p: Page, action: Int, x: Float, y: Float, down: Long) {
        val e = MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
        p.home.dispatchTouchEvent(e); e.recycle()
    }
    private fun tap(p: Page, x: Float, y: Float) {
        val t = android.os.SystemClock.uptimeMillis()
        touch(p, MotionEvent.ACTION_DOWN, x, y, t); settle(2); touch(p, MotionEvent.ACTION_UP, x, y, t); settle(20)
    }

    // ---------------------------------------------------------------- the records

    /** In combat: Combat bars Top screen with Cast bar Top screen; Party mode: Bottom screen (Grow) */
    private val REC = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
        .onTab(HomeTab.COMBAT) { it.copy(fight = HomeLayout.FightBand.TOP, fightCast = HomeLayout.FightCast.TOP) }
        .onTab(HomeTab.PARTY) { it.copy(cast = HomeLayout.CastDock.SPLIT, party = HomeLayout.PartyDock.ABOVE_CHAT, fight = HomeLayout.FightBand.GROW) }

    /** SingleScreen.inUse (DsForeverScreen alone sets it on the device; SingleNpcShotsTest's way) */
    private fun singleInUse(on: Boolean) =
        SingleScreen::class.java.getDeclaredField("inUse").apply { isAccessible = true }.setBoolean(null, on)

    @Test fun thePreviewFollowsTheTabThePicksAndTheSession() {
        HomeSettings.swingTimer = true
        val p = page(REC)
        var heard = 0
        p.home.onEditPreview = { heard++ }
        assertEquals("no session: none", EditPreview.OFF, p.home.editPreviewCode())
        p.home.editOpen(EditStop.CHAT); settle(40)
        val m = model(p)
        assertEquals(HomeTab.OUT, m.tab)
        assertEquals("Out of combat: none", EditPreview.OFF, p.home.editPreviewCode())
        key(p, "PADRSHOULDER", 40)
        assertEquals(HomeTab.COMBAT, m.tab)
        assertTrue("the host heard the tab change", heard > 0)
        assertEquals("In combat, Top screen + Cast bar Top + the swing", 35, p.home.editPreviewCode())
        // the int/1533 review: single screen mode lays Edit Home in the centred panel, whose opaque ground covers every previewed piece
        // (the Wings halves at x 380..740 / 1180..1540, the party rows at 640..1360: all inside 340..1580) - none sent
        singleInUse(true)
        try { assertEquals("single screen mode: none", EditPreview.OFF, p.home.editPreviewCode()) } finally { singleInUse(false) }
        assertEquals("two screens again: the tab's own", 35, p.home.editPreviewCode())
        shot(p, "01_bottom_in_combat_top_cast_top")
        HomeSettings.swingTimer = false
        assertEquals("the swing off", 34, p.home.editPreviewCode())
        HomeSettings.swingTimer = true
        // Cast bar Bottom screen: the fight bubble's row (its press, the record's own setter)
        p.home.editAct { it.pencilTap(EditStop.FIGHT_PEN) }; settle(20)
        assertEquals(EditStop.FIGHT_PEN, m.menu)
        val h0 = heard
        p.home.editAct { listOf(HomeEditModel.Fx.Press(BottomTiles.FIGHT_CAST, HomeLayout.FightCast.BOTTOM.ordinal)) }; settle(20)
        assertTrue("the host heard the pick", heard > h0)
        assertEquals("Cast bar Bottom screen: the Wings alone", 33, p.home.editPreviewCode())
        shot(p, "01b_bottom_in_combat_top_cast_bottom_bubble")
        p.home.editAct { it.closeMenu() }; settle(10)
        // Party mode's tab: a full party (its own Bottom screen: no Wings)
        key(p, "PADRSHOULDER", 40)
        assertEquals(HomeTab.PARTY, m.tab)
        assertEquals("Party mode: a full party", 36, p.home.editPreviewCode())
        shot(p, "02_bottom_party_mode")
        // a fight parks edit mode: none (the add-on drops it too); its end: the tab's own again
        fightWord(p.home, p.cast, true); settle(40)
        assertTrue(m.parked)
        assertEquals("a fight: none", EditPreview.OFF, p.home.editPreviewCode())
        fightWord(p.home, p.cast, false); settle(60)
        assertTrue(!m.parked)
        assertEquals("the fight's end: the tab's own again", 36, p.home.editPreviewCode())
        p.home.editDone(); settle(40)
        assertEquals("Done: none", EditPreview.OFF, p.home.editPreviewCode())
    }

    /** the fight pencil where the band went (Cast bar Top screen) and on the strip's left end (Bottom screen), zoomed: [tag]'s shots */
    @Test fun theFightPencilShots() {
        for ((name, cast) in listOf("away" to HomeLayout.FightCast.TOP, "strip" to HomeLayout.FightCast.BOTTOM)) {
            val p = page(REC.onTab(HomeTab.COMBAT) { it.copy(fightCast = cast) })
            p.home.editOpen(EditStop.CHAT); settle(40)
            key(p, "PADRSHOULDER", 40)
            val r = p.home.editChrome.bandBox(EditStop.FIGHT_PEN)!!
            val z = p.home.pageZoom()?.first ?: 1f
            val o = out
            if (o != null) {
                File(o, "03_pencil_${name}_$tag.txt").writeText("the fight pencil ($tag, $name): ${r.width()} x ${r.height()} Home px at " +
                    "(${r.left}, ${r.top}); on the screen ${r.width() * z} px = ${r.width() * z * 160f / 369f} dp (page zoom $z)\n")
                shot(p, "03_pencil_${name}_$tag", zoomOn = r)
            }
            p.home.editCancel(); settle(20)
        }
    }

    /** a finger 50 px off the fight pencil's centre (inside its 48 dp box, outside 1530's 48 px one) opens its bubble */
    @Test fun aFingerNearTheFightPencilOpensIt() {
        val p = page(REC)
        p.home.editOpen(EditStop.CHAT); settle(40)
        key(p, "PADRSHOULDER", 40)
        val m = model(p)
        val r = p.home.editChrome.bandBox(EditStop.FIGHT_PEN)!!
        assertTrue("drawn >= 48 px (${r.width()})", r.width() >= 48f)
        val pt = p.home.screenOf(r.centerX() + 50f, r.centerY() + 12f)
        tap(p, pt[0], pt[1])
        assertEquals("50 px off its centre: its bubble", EditStop.FIGHT_PEN, m.menu)
    }
}
