package com.jrees.wowcompanion

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.jrees.wowcompanion.SinglePlayLayout.Arr
import com.jrees.wowcompanion.SinglePlayLayout.Piece
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * SINGLE SCREEN MODE, THE MERGE OF PLAY AND CORE: a fresh host in single screen mode (the switch itself, every launcher resume) starts on
 * the game - CORE's first look sets the rooms column GONE in onCreate, before any layout pass, so Home is never laid and PLAY's docks
 * (which show what Home lays: HomeView.dockLaid) stayed empty until Home's page showed once. SingleDocks.layHomeOnce lays the GONE column
 * once at the panel's place: Home works its places out, the docks show the chat, Party mode's list and the map; the column stays GONE;
 * a Home already laid is left alone. The real Home and docks (SinglePlayStage), Home un-laid as the host has it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SingleDocksFirstLayTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun down() {
        PartyModel.live.javaClass.getDeclaredField("party").apply { isAccessible = true }.set(PartyModel.live, null)
        HomeLayoutStore.character(null); HomeLayoutStore.set(HomeLayout())
    }

    /** the host's rooms column: the rooms' frame (Home in it) over the room bar, GONE as CORE's PLAY look leaves it */
    private fun column(s: SinglePlayStage): LinearLayout {
        val col = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val rooms = FrameLayout(app)
        (s.home.parent as? ViewGroup)?.removeView(s.home)
        rooms.addView(s.home, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(rooms, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(View(app), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Strip.HEIGHT))
        s.screen.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // in a real window, as the top window holds the host's content: its own layout passes run (and skip the GONE column); Home's
        // posts run once it is attached
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        (s.screen.parent as? ViewGroup)?.removeView(s.screen)
        act.setContentView(s.screen)
        return col
    }

    @Test fun aFreshHostsHomeIsLaidOnceAndTheDocksShowItsViews() {
        val s = SinglePlayStage(app, partyOn = true, laid = false)
        val col = column(s)
        s.settle()
        s.screen.requestLayout(); s.settle()
        assertEquals("never laid by the window's passes: its column GONE from the first look", 0, s.home.width)
        assertFalse("nothing for the docks yet", s.home.dockLaid().chat)

        val b = s.shell.box
        assertTrue(SingleDocks.layHomeOnce(s.home, col, b[0], b[1], b[2], b[3]))
        s.settle()
        assertEquals("the panel's width", b[2] - b[0], s.home.width)
        assertEquals("the column's height less the room bar", (b[3] - b[1]) - Strip.HEIGHT, s.home.height)
        assertEquals("the column stays GONE (the game is the screen)", View.GONE, col.visibility)
        val laid = s.home.dockLaid()
        assertTrue("Home lays its chat, Party mode's list and its map: the docks show them ($laid)", laid.chat && laid.party && laid.map)

        // the docks as the host's PLAY look plays them: every piece up
        s.play(Arr.OUT)
        for (p in listOf(Piece.CHAT, Piece.PARTY, Piece.MAP)) assertTrue("$p up", s.shell.subs[p.sub]?.on == true)
        assertFalse("laid already: never again", SingleDocks.layHomeOnce(s.home, col, b[0], b[1], b[2], b[3]))
    }

    @Test fun aHomeAlreadyLaidIsLeftAlone() {
        val s = SinglePlayStage(app, partyOn = false)
        val w = s.home.width; val h = s.home.height
        assertTrue(w > 0 && h > 0)
        val col = column(s)
        assertFalse(SingleDocks.layHomeOnce(s.home, col, 0, 0, 10, 10))
        assertEquals(w, s.home.width); assertEquals(h, s.home.height)
    }
}
