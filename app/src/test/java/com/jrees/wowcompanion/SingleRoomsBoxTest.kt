package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

/**
 * SINGLE SCREEN MODE, THE ROOMS PASS (spec 8.2; the user's pick "Centred panel": every room the bottom screen's 1240 x 1080 page 1:1 in
 * the middle of the 1920 x 1080 top screen): each REAL room view in the box the host lays (SingleScreen.boxMargins on the rooms column,
 * its room bar under it) inside a 1920 x 1080 window - the room is exactly the bottom screen's width, nothing in it is laid wider than
 * the box, and a finger at (340 + x, y) on the top screen reaches the room at (x, y). Plus the strip's type tool (its two tabs at their
 * strip sizes, the keyboard at the box's scale) and Home's class-wheel layers (nothing of Home's page drawn, nothing under it pressed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SingleRoomsBoxTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val TW = 1920
    private val TH = 1080
    private val BW = 1240

    @Before fun up() { Tr.init(app); Settings.init(app); SettingsTabs.init(app); GameUi.init(app); HomeSettings.init(app); CastSettings.init(app) }
    @After fun down() { SingleScreen.offered = false }

    private fun lay(v: View, l: Int, t: Int, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(l, t, l + w, t + h)
    }

    /** the finger as the room's own place gets it (a room that is a ViewGroup hands it to its children first: read before them) */
    private class Probe(c: android.content.Context) : FrameLayout(c) {
        var got: Pair<Float, Float>? = null
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) got = e.x to e.y
            return super.dispatchTouchEvent(e)
        }
    }
    private var probe: Probe? = null

    /** the host's content as single screen mode lays it: the rooms column (the room over its bar) in the box */
    private fun panel(room: View, r: Room): Pair<FrameLayout, LinearLayout> {
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val content = FrameLayout(act)
        val column = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; ThemeLive.bg(this, Tok.INK) }
        val p = Probe(act).also { probe = it }
        p.addView(room, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        column.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val strip = Strip(act) { }
        strip.active = r
        column.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Strip.HEIGHT))
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val m = SingleScreen.boxMargins(intArrayOf(0, 0, 0, 0), SingleScreen.panelBox(TW, TH), TW, TH)
        lp.setMargins(m[0], m[1], m[2], m[3])
        content.addView(column, lp)
        act.setContentView(content)
        lay(content, 0, 0, TW, TH)
        ShadowLooper.idleMainLooper()
        lay(content, 0, 0, TW, TH)
        return content to column
    }

    private fun rooms(): List<Triple<String, Room, () -> View>> = listOf(
        Triple("map", Room.MAP) { WorldMapRoomView(app) },
        Triple("quests", Room.QUESTS) { QuestsView(app, { null }) { } },
        Triple("spellbook", Room.SPELLBOOK) { SpellbookView(app, { }) },
        Triple("talents", Room.TALENTS) { TalentsView(app) { _, _ -> } },
        Triple("professions", Room.PROFESSIONS) { ProfessionsView(app) },
        Triple("groupfinder", Room.GROUPFINDER) { GroupFinderView(app) },
        Triple("character", Room.CHARACTER) { CharacterView(app) },
        Triple("bags", Room.BAGS) { BagsView(app) },
        Triple("chat", Room.CHAT) { ChatView(app) { } },
        Triple("controller", Room.CONTROLLER) { ControllerView(app, { true }) },
        Triple("social", Room.SOCIAL) { SocialView(app) },
        Triple("calendar", Room.CALENDAR) { CalendarView(app) },
        Triple("macros", Room.MACROS) { MacrosView(app) },
        Triple("settings", Room.SETTINGS) { SettingsRoom(app, SettingsView(app) { }) { null } },
    )

    /** every descendant of [v] laid inside [w] (its own px: a room never reaches past the bottom screen's width) */
    private fun widest(v: View, x0: Int = 0): Int {
        var r = x0 + v.right - v.left
        if (v is ViewGroup) for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            if (c.visibility == View.GONE) continue
            r = maxOf(r, widest(c, x0 + c.left))
        }
        return r
    }

    @Test fun everyRoomIsTheBottomScreensPageInTheBox() {
        for ((name, r, make) in rooms()) {
            val room = make()
            val (content, column) = panel(room, r)
            assertEquals("$name: the box's left", 340, column.left)
            assertEquals("$name: the box", BW, column.width)
            assertEquals("$name: the room's own width = the bottom screen's", BW, room.width)
            assertEquals("$name: the room's height = the bottom screen's page", TH - Strip.HEIGHT, room.height)
            assertTrue("$name: measured inside the box", room.measuredWidth <= BW)
            assertTrue("$name: nothing laid wider than the box (${widest(room)})", widest(room) <= BW)
            // a finger on the top screen at (340 + x, y) is the room's (x, y) (the room at its place's own origin)
            assertEquals("$name: the room at its place's origin", 0, room.left); assertEquals(0, room.top)
            val pr = probe!!
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 340f + 300f, 400f, 0)
            content.dispatchTouchEvent(down)
            content.dispatchTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_CANCEL, 340f + 300f, 400f, 0))
            ShadowLooper.idleMainLooper()
            val got = pr.got
            assertNotNull("$name: the finger reached the room", got)
            assertEquals("$name: x", 300f, got!!.first, 0.5f); assertEquals("$name: y", 400f, got.second, 0.5f)
            // and the box's side strips are not the room's (the dim beside the panel takes them)
            pr.got = null
            content.dispatchTouchEvent(MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_DOWN, 150f, 400f, 0))
            content.dispatchTouchEvent(MotionEvent.obtain(t, t + 90, MotionEvent.ACTION_CANCEL, 150f, 400f, 0))
            assertEquals("$name: the dim's side strip", null, pr.got)
        }
    }

    // ---------------------------------------------------------------- the type tool in the strip

    private fun tool(tab: Int, strip: Boolean, w: Int, h: Int): TypeToolView {
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val v = TypeToolView(act) { }
        val holder = FrameLayout(act)
        holder.addView(v, FrameLayout.LayoutParams(w, h))
        act.setContentView(holder)
        v.stripScale = 1f
        v.strip = strip
        v.open(tab)
        lay(holder, 0, 0, w, h); lay(v, 0, 0, w, h)
        return v
    }

    @Test fun theKeyboardStripAndTheTrackPadCorner() {
        val kbH = ChatKeyboard.fullHeight(1f)
        val kr = SingleNpc.keysRect(TW, TH, kbH)
        val k = tool(1, true, kr[2] - kr[0], kr[3] - kr[1])
        val kb = (0 until k.childCount).map { k.getChildAt(it) }.first { it is ChatKeyboard } as ChatKeyboard
        assertEquals("the keyboard at the box's scale, not the strip's window", 1f, kb.scale)
        assertEquals("under the short head", SingleNpc.TOOL_HEAD, kb.top)
        assertEquals("the whole keyboard in the strip", kbH, kb.height)
        assertEquals(kr[3] - kr[1], SingleNpc.TOOL_HEAD + kbH)
        var tabs = -1
        k.onTab = { t -> tabs = t }
        k.onPad(JSONObject().put("k", "PADLSHOULDER").put("n", 1))
        assertEquals("LB: the Track Pad (the strip takes its corner)", 0, tabs)
        assertEquals(0, k.tabNow)
        val pr = SingleNpc.padRect(TW, TH)
        val p = tool(0, true, pr[2] - pr[0], pr[3] - pr[1])
        val pad = (0 until p.childCount).map { p.getChildAt(it) }.first { it is TrackpadView }
        assertEquals(SingleNpc.TOOL_HEAD, pad.top); assertEquals(SingleNpc.PAD_W, pad.width); assertEquals(SingleNpc.PAD_H - SingleNpc.TOOL_HEAD, pad.height)
        // two screens: the page exactly as before (the keyboard under the 160 px head and its typed line)
        val page = tool(1, false, BW, 935)
        val kb2 = (0 until page.childCount).map { page.getChildAt(it) }.first { it is ChatKeyboard } as ChatKeyboard
        assertEquals(250, kb2.top)
        assertEquals(null, kb2.scaleOverride)
    }

    // ---------------------------------------------------------------- Home's layers alone (the class wheel)

    @Test fun homesLayersOnlyDrawsNothingOfThePageAndTakesTheTouch() {
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val holder = FrameLayout(act)
        val map = MapView(act); val cast = CastView(act) { }; val chat = ChatView(act) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(act, map, cast, chat) { }
        home.borrow()
        val root = FrameLayout(act); root.addView(home); act.setContentView(root)
        lay(root, 0, 0, BW, 935); lay(home, 0, 0, BW, 935)
        fun opaque(): Int {
            val b = Bitmap.createBitmap(BW, 935, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.TRANSPARENT); home.draw(Canvas(b))
            var n = 0
            for (y in 0 until 935 step 37) for (x in 0 until BW step 41) if (Color.alpha(b.getPixel(x, y)) > 0) n++
            return n
        }
        assertTrue("two screens: Home's page is drawn", opaque() > 100)
        home.singleLayersOnly = true
        assertEquals("its ground gone", 0, home.background?.alpha ?: 0)
        assertEquals("nothing of Home's page over the game", 0, opaque())
        val t = SystemClock.uptimeMillis()
        assertTrue("a touch is taken (nothing of Home's under the wheel is pressed)", home.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 600f, 400f, 0)))
        home.singleLayersOnly = false
        assertEquals(255, home.background?.alpha)
        assertTrue("back: the page again", opaque() > 100)
    }
}
