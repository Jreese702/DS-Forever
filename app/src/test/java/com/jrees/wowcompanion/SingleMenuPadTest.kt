package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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
 * THE DS MENU'S PAD ROUTING (the 1536 pass 1): the REAL bar cursor (HomeBarSelect) as single screen mode wires it for the menu's row -
 * its order the row's (rooms, the swirl, rooms; not the hidden bar's places), sticky (no idle close), not drawn over the bar - driven by
 * the guest "bar"'s presses as the add-on delivers them. Down's command opens it ON the swirl; the D-pad and L / R move it, the swirl a
 * stop; A on a room opens that room, A on the swirl is the swirl's pick (the host's Home stage); B closes it (the keys given back);
 * the triggers never move a command cursor; a fold's reopening puts it on the room just left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SingleMenuPadTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val picks = ArrayList<Pair<Room, BarB.Go>>()
    private val shown = ArrayList<Boolean>()
    private var n = 0
    private lateinit var bar: HomeBarSelect
    private lateinit var strip: Strip

    @Before fun up() {
        Tr.init(app); HomeSettings.init(app); CastSettings.init(app)
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        strip = Strip(act) { }
        strip.visibility = View.INVISIBLE                           // under the menu the bar is not drawn: its places never laid
        bar = HomeBarSelect(act, strip, { null }) { r, go -> picks += r to go }
        bar.menuOrder = { SingleMenu.order(strip.order) }
        bar.sticky = { true }
        bar.drawn = { false }
        bar.onShown = { v -> shown += v }
        val root = FrameLayout(act); root.addView(strip); root.addView(bar); act.setContentView(root)
    }

    private fun press(k: String) { bar.onPad(JSONObject().put("type", "guestpad").put("id", "bar").put("k", k).put("n", ++n)); ShadowLooper.idleMainLooper() }
    private fun down() = bar.onCommand(JSONObject().put("type", "barcmd").put("k", "home").put("n", ++n).put("boot", 1), Room.HOME, blocked = false)

    @Test fun downOpensTheRowOnTheSwirlWithoutTheBarsPlaces() {
        assertEquals("the bar's places never laid (it is hidden under the menu)", null, strip.place(Room.HOME))
        down()
        assertTrue("Down: the row up", bar.open)
        assertEquals("the cursor on the swirl (the user: start it on the spiral)", Room.HOME, bar.cursor)
        assertEquals(listOf(true), shown)
    }

    @Test fun theDpadAndTheShouldersMoveTheCursorTheSwirlAStop() {
        down()
        press("PADDRIGHT"); assertEquals(Room.CHARACTER, bar.cursor)
        press("PADDRIGHT"); assertEquals(Room.BAGS, bar.cursor)
        press("PADLSHOULDER"); assertEquals(Room.CHARACTER, bar.cursor)
        press("PADDLEFT"); assertEquals("the swirl is a stop", Room.HOME, bar.cursor)
        press("PADDLEFT"); assertEquals(Room.GROUPFINDER, bar.cursor)
        press("PADRSHOULDER"); assertEquals(Room.HOME, bar.cursor)
        repeat(10) { press("PADDRIGHT") }
        assertEquals("no wrap: the last room", Room.SETTINGS, bar.cursor)
        press("PADLTRIGGER"); press("PADRTRIGGER")
        assertEquals("the triggers never move a command cursor (ZL + ZR held's releases)", Room.SETTINGS, bar.cursor)
    }

    @Test fun aOpensTheRoomAOnTheSwirlIsItsPickBCloses() {
        down()
        press("PADDRIGHT"); press("PADDRIGHT"); press("PAD1")
        assertEquals(listOf(Room.BAGS to BarB.Go.ROOM), picks)
        assertFalse("the row gives way to its room", bar.open)
        picks.clear()
        down()
        press("PAD1")
        assertEquals("A on the swirl: the swirl's pick (the host: Home's stage)", listOf(Room.HOME to BarB.Go.PAUSE), picks)
        picks.clear()
        down()
        press("PADDRIGHT"); press("PAD2")
        assertFalse("B: the row drops", bar.open)
        assertTrue("nothing opened", picks.isEmpty())
        assertEquals(false, shown.last())
        down(); press("PAD2")
        assertFalse("B on the swirl too (on Home B only closes: BarB)", bar.open)
        assertTrue(picks.isEmpty())
    }

    @Test fun theRowStaysUntilBNoIdleClose() {
        down()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(8)); ShadowLooper.idleMainLooper()
        assertTrue("sticky: the menu's row is a place, not a passing cursor", bar.open)
        bar.sticky = { false }
        press("PADDRIGHT")                                          // (a press re-arms the idle close)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(8)); ShadowLooper.idleMainLooper()
        assertFalse("two screens: the command cursor still goes by itself", bar.open)
    }

    @Test fun aFoldPutsTheCursorOnTheRoomJustLeft() {
        bar.openMenuAt(Room.BAGS, SingleMenu.FOLD_HOLD_MS)
        assertTrue(bar.open); assertEquals(Room.BAGS, bar.cursor)
        press("PADDLEFT"); assertEquals(Room.CHARACTER, bar.cursor)
        press("PADDLEFT"); assertEquals("the swirl a stop after a fold too", Room.HOME, bar.cursor)
        press("PAD2"); assertFalse("B on the swirl after a fold: the row drops (never Home's B from a room)", bar.open)
        assertTrue(picks.isEmpty())
        // the hold asked after the fold, never after the row went
        bar.openMenuAt(Room.BAGS, 1_000L); bar.close()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2)); ShadowLooper.idleMainLooper()
        assertFalse(bar.open)
    }

    @Test fun nothingOfTheBarCursorIsDrawnUnderTheMenu() {
        down(); press("PADDRIGHT")
        bar.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        bar.layout(0, 0, 1240, 1080)
        val b = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.TRANSPARENT)
        bar.draw(Canvas(b))
        var any = 0
        for (y in 0 until 1080 step 7) for (x in 0 until 1240 step 7) if (Color.alpha(b.getPixel(x, y)) != 0) any++
        assertEquals("the menu draws its own row", 0, any)
    }
}
