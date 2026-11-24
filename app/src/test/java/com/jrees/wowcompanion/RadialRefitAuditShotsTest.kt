package com.jrees.wowcompanion

import android.graphics.Rect
import android.graphics.RectF
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
 * THE 1523 AUDIT: A HELD WHEEL WHEN A FIGHT STARTS UNDER IT, on the real [HomeView]. Pinned:
 *  - a class wheel (LB + LT / RB + RT) held into a Party-mode fight: the full-width party list the fight lays larger (Grow at the foot,
 *    Fill under a Top-screen fight's strip at the top) never grows under the held wheel - its square is cut again (HomeView.classRefit);
 *  - the middle wheel held into a Top-screen fight (Cast bar in fights = Bottom screen: the fight strip across the top) is refitted UNDER
 *    the strip - the same square as the wheel opened in that fight (the strip's line was set only after the refit, so the refit saw no
 *    strip and its square started at the page's top gap).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RadialRefitAuditShotsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private val G = 5000.0
    private val gap = UiKit.MODULE_GAP

    @After fun down() { setField(PartyModel.live, "party", null); HomeLayoutStore.character(null); HomeLayoutStore.set(HomeLayout()) }

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
    private fun partyDoc(on: Boolean, n: Int): JSONObject {
        val m = JSONArray(); for (r in roster.take(n)) m.put(JSONObject(r))
        return JSONObject().put("type", "party").put("v", "m77").put("rs", 1)
            .put("p", JSONObject().put("on", on).put("box", 1).put("fight", false).put("grp", n > 1).put("sel", "bound").put("m", m))
    }

    private class Page(val home: HomeView, val cast: CastView)

    /** a Home page for [layout] at rest, a party of five (Party mode [party]), the add-on's padmap (Wings `ct` 1, `cc` 0: the strip) */
    private fun page(layout: HomeLayout, party: Boolean): Page {
        app.getSharedPreferences("home", 0).edit().clear().commit()
        HomeSettings.init(app); Settings.init(app); Tr.init(app)
        HomeLayoutStore.character(null)
        HomeLayoutStore.set(layout)
        CastStripModel.Live.pos = 0f; CastStripModel.Live.side = null
        setField(PartyModel.live, "party", null)
        if (party) PartyModel.live.onParty(PartyModel.parseParty(partyDoc(true, 5))!!, true, System.currentTimeMillis())
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
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        val bar = CastStripModel.parse(castDoc)!!
        CastStripModel.Live.set(bar); home.onCastBar(bar)
        fightWord(home, cast, false)
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
        val ab = JSONObject().put("hud", JSONObject().put("ok", true)).put("hudS", JSONObject().put("c", on).put("g", G))
        home.onFull(JSONObject().put("type", "full").put("map", JSONObject().put("zone", "Elwynn Forest").put("pvp", "contested"))
            .put("actionbar", ab), true)
        cast.update(ab)
    }
    /** the add-on's fight word arrives while the wheel is held: the page lays itself again under it */
    private fun fight(p: Page, on: Boolean) { fightWord(p.home, p.cast, on); p.home.tick(); settle(); lay(p.home); settle() }

    @Suppress("UNCHECKED_CAST")
    private fun boxes(home: HomeView): Map<String, RectF> = (field(home, "now") as Map<Any, RectF>).entries.associate { it.key.toString() to RectF(it.value) }

    private val showClass by lazy { HomeView::class.java.getDeclaredMethod("classLayerShow", Boolean::class.javaPrimitiveType).apply { isAccessible = true } }
    private fun classOpen(p: Page, left: Boolean): RectF {
        val flow = field(p.cast, "classFlow")!!
        flow.javaClass.getDeclaredField("side").apply { isAccessible = true }.setInt(flow, if (left) 1 else 2)
        showClass.invoke(p.home, true); settle()
        return classRect(p)
    }
    private fun classRect(p: Page) = RectF(field(p.home, "classRect") as Rect)
    private fun wheelRect(p: Page) = RectF(field(p.home, "wheelRect") as RectF)

    @Test fun aClassWheelHeldIntoAPartyFightKeepsClearOfTheGrownList() {
        // the foot list across (no one-column bar) grows in a Grow fight; the top list fills under a Top-screen fight's strip
        val cases = listOf(
            "foot grow" to HomeLayout(party = HomeLayout.PartyDock.UNDER_CHAT, fight = HomeLayout.FightBand.GROW),
            "foot grow, bar across" to HomeLayout(party = HomeLayout.PartyDock.UNDER_MAP, cast = HomeLayout.CastDock.ACROSS, fight = HomeLayout.FightBand.GROW),
            "top fill" to HomeLayout(party = HomeLayout.PartyDock.ABOVE_CHAT, fight = HomeLayout.FightBand.TOP),
            "top same size" to HomeLayout(party = HomeLayout.PartyDock.ABOVE_MAP, fight = HomeLayout.FightBand.TOP, partySize = HomeLayout.PartySize.SAME))
        for ((name, l) in cases) for (left in listOf(true, false)) {
            val tag = "$name ${if (left) "LB+LT" else "RB+RT"}"
            val p = page(l, party = true)
            val rest = boxes(p.home).getValue("PARTY")
            assertTrue("$tag: the list across at rest ($rest)", rest.width() > 1200f)
            val at = classOpen(p, left)
            fight(p, true)
            val pw = boxes(p.home).getValue("PARTY")
            val sq = classRect(p)
            assertTrue("$tag: the list laid again for the fight ($rest -> $pw)", pw.height() > rest.height() + 0.5f || pw.top != rest.top)
            if (l.party.top) assertTrue("$tag: the wheel $sq under the grown list $pw", sq.top >= pw.bottom + gap - 1f)
            else assertTrue("$tag: the wheel $sq over the grown list $pw", sq.bottom <= pw.top - gap + 1f)
            assertTrue("$tag: the wheel kept in its square or smaller ($at -> $sq)", sq.width() <= at.width() + 0.5f && sq.width() >= H / 4f)
            assertEquals("$tag: square", sq.width(), sq.height(), 1f)
            showClass.invoke(p.home, false); settle()
        }
    }

    @Test fun theMiddleWheelHeldIntoATopFightSitsUnderTheStrip() {
        val strip = 8f + HomeLayoutSolver.FIGHT_STRIP_H
        val cases = listOf(
            "classic" to (HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.TOP) to false),
            "party foot list" to (HomeLayout(party = HomeLayout.PartyDock.UNDER_CHAT, fight = HomeLayout.FightBand.TOP) to true))
        for ((name, c) in cases) {
            val (l, party) = c
            val p = page(l, party)
            assertTrue("$name: opened", p.home.wheelSlot(true) != null); settle()
            fight(p, true)
            assertTrue("$name: the band away", p.cast.away)
            assertTrue("$name: the strip laid", boxes(p.home).containsKey("FIGHT_CAST"))
            val held = wheelRect(p)
            assertTrue("$name: the held wheel $held under the strip (its foot $strip)", held.top >= strip + gap - 0.5f)
            p.home.wheelSlot(false); settle()
            val fresh = p.home.wheelSlot(true)!!; settle()
            assertEquals("$name: held into the fight = opened in it ($held / $fresh)", fresh.top, held.top, 0.5f)
            assertEquals("$name: held into the fight = opened in it ($held / $fresh)", fresh.width(), held.width(), 0.5f)
            p.home.wheelSlot(false); settle()
        }
    }
}
