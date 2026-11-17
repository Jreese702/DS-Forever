package com.jrees.wowcompanion

import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import java.time.Duration

/**
 * fix/polestar-b-home, THE ROUTE OFF THE DEVICE (the user, 2026-11-17: "when opening the polestar menu from the map, and then pressing B
 * to go back home, it returns to a full screen view of the map instead of the home screen. pressing B again doesnt do anything to return
 * it home"). The real HomeView, its MapView and the Quests room's own Polestar menu, wired as CompanionHost wires them; the host's
 * room change is played as its show() plays it (Home left: closeAuras + giveBack; Home shown: borrow + land):
 *  - X on the open map module's light blue row opens the Quests room (its Polestar tab) and the module CLOSES as Home is left: nothing
 *    of its controller layer stays wanted while Home is hidden (the guest "home", the guard), and B in the room lands on Home's rest;
 *  - an open module whose hold silently ended (the capture names nobody) never swallows B: Home's own reader takes B again and closes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PolestarBHomeRouteTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    private fun settle(n: Int = 40) { for (i in 0 until n) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }
    private fun lay(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, W, H)
    }
    private fun guide() = JSONObject().put("type", "pguide").put("v", "pg2").put("have", true).put("gid", "forever/alliance/1-10/elwynn")
        .put("gs", 444).put("t", "Elwynn Forest").put("z", "Elwynn Forest").put("zm", 1429).put("lo", 1).put("hi", 10).put("n", 10).put("dn", 3)
        .put("tt", 10).put("sk", true).put("u", 1).put("tb", true).put("s", 4).put("l", 4).put("st", "dddluuuuuu")
    private fun steps() = JSONObject().put("type", "pgsteps").put("gs", 444).put("n", 10).put("o", 1)
        .put("t", JSONArray((1..10).map { "Step $it" }))

    /** Settings is one object for the whole test JVM: the guide choice goes back as GameUiTilesRoomTest leaves it */
    @org.junit.After fun forget() { Settings.polestarGuide = null }

    private var padN = 0
    private fun key(home: HomeView, k: String) { home.auraPad(JSONObject().put("k", k).put("n", ++padN)); settle(4) }

    private class Rig(val content: FrameLayout, val home: HomeView, val map: MapView, val quests: QuestsView) {
        var room = "home"
        val shows = ArrayList<String>()
        /** CompanionHost.show's Home side, in its order */
        fun show(r: String) {
            shows.add(r)
            if (r != "home") { home.closeAuras(); home.giveBack() }
            room = r
            for (i in 0 until content.childCount) content.getChildAt(i).visibility = View.GONE
            (if (r == "home") home else quests).visibility = View.VISIBLE
            if (r == "home") { home.borrow(); home.land() }
            if (r == "quests") quests.onShown()
        }
    }

    private fun rig(): Rig {
        HomeSettings.init(app); Settings.init(app)
        Settings.polestarGuide = PolestarGuide.ON
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val content = FrameLayout(act)
        act.setContentView(content)
        val q = QuestsView(app, { null }, { })
        for (d in listOf(guide(), steps())) q.onPolestarDoc(d)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        val home = HomeView(app, map, cast, chat) { }
        for (v in listOf<View>(home, map, cast, chat, q)) { content.addView(v, FrameLayout.LayoutParams(-1, -1)); v.visibility = View.GONE }
        val rig = Rig(content, home, map, q)
        // as CompanionHost wires them (pm3): the row's X = the Quests room's Polestar tab
        map.psMenu = q.psMenu
        map.onPolestarMenu = { path -> q.openPolestar(path); rig.show("quests") }
        q.onLeave = { rig.show("home") }
        rig.show("home")
        lay(content)
        map.update(JSONObject().put("ok", true).put("mapID", 1429).put("zone", "Elwynn Forest").put("x", 0.42).put("y", 0.65).put("facing", 0.0), 0L)
        map.onPolestarGuide(guide())
        map.onPolestarStep(JSONObject().put("type", "pstep").put("t", "Kobold Camp Cleanup").put("g", "Kill Kobold Vermin").put("q", 7)
            .put("m", 1429).put("x", 0.47).put("y", 0.55).put("b", 1L).put("sk", true).put("u", 1))
        settle(); lay(content)
        return rig
    }

    /** the module open full screen, its hold answered (the controller Home's) */
    private fun openModule(r: Rig) {
        assertTrue(r.home.openMapModule()); settle(); lay(r.content)
        r.home.auraGuestAck(JSONObject().put("type", "guestack").put("id", "home").put("op", "hold").put("ok", true))
        settle(4)
        assertEquals(HomeModule.MAP, r.home.flow.full)
        assertTrue("the module holds", r.home.mapHolds())
    }

    @Test fun xOnTheMapRowOpensTheQuestsRoomAndBLandsOnHomesRest() {
        val r = rig()
        openModule(r)
        key(r.home, "PAD3")
        assertEquals("X: the Quests room (its Polestar tab)", listOf("home", "quests"), r.shows)
        // Home left: the module closed with it, nothing of its controller layer wanted while Home is hidden
        assertNull("the module closes as Home is left", r.home.flow.full)
        assertFalse("no map guard while the Quests room shows (ExitGuard's mapGuarding)", r.home.mapGuarding())
        assertFalse("the guest \"home\" is not wanted while Home is hidden", r.home.homeOwnGuestWanted())
        assertFalse(r.home.mapHolds())
        assertFalse("B on Home's page is not a module's", r.home.bClosesSomething())
        // B in the room: Home's rest
        settle(); r.show("home"); settle(); lay(r.content)
        assertEquals("home", r.room)
        assertNull("Home's rest, no module", r.home.flow.full)
        assertTrue("the small map again", r.map.compact)
        assertFalse(r.home.mapGuarding())
    }

    @Test fun aModuleWhoseHoldWentElsewhereNeverSwallowsB() {
        val r = rig()
        openModule(r)
        // the hold is real: B is the guest's (Home's watch leaves it), so the module stays for the guest's own B
        r.home.pressB(); settle(4)
        assertEquals("held: B through the guest", HomeModule.MAP, r.home.flow.full)
        // the capture names nobody (the host's guard tick: MapExitGuard.holdGone): Home's reader takes B again
        assertFalse(MapExitGuard.holdGone(true, true, MapExitGuard.HOME_HOLDER, 60_000L))
        assertFalse("inside the grace", MapExitGuard.holdGone(true, true, "", MapExitGuard.HOLDER_GRACE_MS - 1))
        assertTrue("nobody holding", MapExitGuard.holdGone(true, true, "", MapExitGuard.HOLDER_GRACE_MS))
        assertFalse("a wheel over the module holds on purpose", MapExitGuard.holdGone(true, true, "radial", 60_000L))
        assertFalse("not held: the module's own guard already reads B", MapExitGuard.holdGone(true, false, "", 60_000L))
        r.home.mapHoldGone()
        assertFalse(r.home.mapHolds())
        r.home.pressB(); settle(4)
        assertNull("B closes the module", r.home.flow.full)
        // and the first press that arrives again makes the module held (the re-asked hold took)
        openModule(r)
        r.home.mapHoldGone()
        key(r.home, "PADDDOWN")
        assertTrue("a press: held again", r.home.mapHolds())
    }
}
