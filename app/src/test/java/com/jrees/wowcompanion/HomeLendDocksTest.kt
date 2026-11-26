package com.jrees.wowcompanion

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.jrees.wowcompanion.SinglePlayLayout.Arr
import com.jrees.wowcompanion.SinglePlayLayout.Box
import com.jrees.wowcompanion.SinglePlayLayout.Piece
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * SINGLE SCREEN MODE: HOME LENDS ITS VIEWS TO THE PLAY DOCKS (HomeView.lendToDocks / reclaimFromDocks, SingleDocks; the user's pick "1, 2,
 * and also the chat on the left thr ds toolbar should have the same functionality in single screen mode"): the real Home and the real
 * docks (SinglePlayStage). Lent, each view has ONE parent - its dock's slot - and Home's layout passes leave it alone; given back, each is
 * where it was (Home's child at its index, the map in Home's map slot) and Home lays it again; another room taking the map back meanwhile
 * keeps it there; the shift updates each moving window twice (the union, then the new box), never per frame; the windows stand at the
 * plan's boxes; Party mode off = no party window; the talk pop-up's window while the hold lasts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeLendDocksTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun down() {
        PartyModel.live.javaClass.getDeclaredField("party").apply { isAccessible = true }.set(PartyModel.live, null)
        HomeLayoutStore.character(null); HomeLayoutStore.set(HomeLayout())
    }

    private fun v(s: SinglePlayStage, name: String) = s.field(s.home, name) as View

    @Test fun lentEachHasOneParentTheDocksSlot() {
        val s = SinglePlayStage(app, partyOn = true)
        val chatPane = v(s, "chatPane"); val partyView = v(s, "partyView"); val mapSlot = v(s, "mapSlot") as FrameLayout
        val chatAt = s.home.indexOfChild(chatPane); val partyAt = s.home.indexOfChild(partyView)
        assertTrue("Home holds them first", chatAt >= 0 && partyAt >= 0 && s.map.parent === mapSlot)
        assertTrue("Home lays Party mode's list (Party mode on)", s.home.dockLaid().party)
        s.play(Arr.OUT)
        assertSame(s.docks.chat, chatPane.parent); assertSame(s.docks.party, partyView.parent); assertSame(s.docks.map, s.map.parent)
        assertEquals("no longer Home's children", -1, s.home.indexOfChild(chatPane))
        assertEquals(-1, s.home.indexOfChild(partyView))
        assertEquals("Home's map slot empty", 0, mapSlot.childCount)
        assertTrue(s.home.docked(chatPane) && s.home.docked(partyView) && s.home.docked(s.map) && s.home.dockedNow)
        for (x in listOf(chatPane, partyView, s.map)) assertEquals("one child in its slot", 1, (x.parent as ViewGroup).childCount)
    }

    @Test fun homesLayoutLeavesTheDockedViewsAlone() {
        val s = SinglePlayStage(app, partyOn = true)
        val p = s.play(Arr.OUT)
        s.layScreen()
        val chatPane = v(s, "chatPane")
        val before = listOf(chatPane.left, chatPane.top, chatPane.width, chatPane.height)
        assertEquals("laid at the dock's size", listOf(0, 0, p.chat.w, p.chat.h), before)
        s.lay(s.home, s.HW, s.HH)
        s.home.requestLayout(); s.lay(s.home, s.HW, s.HH)
        assertEquals("Home's layout pass never moves a docked view", before, listOf(chatPane.left, chatPane.top, chatPane.width, chatPane.height))
        assertEquals(p.map.w, s.map.width); assertEquals(p.map.h, s.map.height)
    }

    @Test fun givenBackWhereTheyWere() {
        val s = SinglePlayStage(app, partyOn = true)
        val chatPane = v(s, "chatPane"); val partyView = v(s, "partyView"); val mapSlot = v(s, "mapSlot") as FrameLayout
        val chatAt = s.home.indexOfChild(chatPane); val partyAt = s.home.indexOfChild(partyView)
        val lp = chatPane.layoutParams
        chatPane.translationX = 37f
        s.play(Arr.OUT)
        assertEquals("lent at rest (no slide left on it)", 0f, chatPane.translationX)
        s.docks.show(false)
        s.home.reclaimFromDocks()
        assertSame(s.home, chatPane.parent); assertSame(s.home, partyView.parent); assertSame(mapSlot, s.map.parent)
        assertEquals("at its index (Home's draw order)", chatAt, s.home.indexOfChild(chatPane))
        assertEquals(partyAt, s.home.indexOfChild(partyView))
        assertSame("its own layout params", lp, chatPane.layoutParams)
        assertEquals("as it was", 37f, chatPane.translationX)
        assertFalse(s.home.dockedNow)
        chatPane.translationX = 0f
        s.lay(s.home, s.HW, s.HH)
        @Suppress("UNCHECKED_CAST") val now = s.field(s.home, "now") as Map<Any, android.graphics.RectF>
        val chatBox = now.entries.first { it.key.toString() == "CHAT" }.value
        assertEquals("Home lays it again at its own place", chatBox.width().toInt(), chatPane.width)
    }

    @Test fun anotherRoomTakingTheMapBackKeepsIt() {
        val s = SinglePlayStage(app, partyOn = false)
        val chatPane = v(s, "chatPane")
        s.play(Arr.OUT)
        s.docks.show(false)
        s.home.giveBack()                                         // another room opens (CompanionHost.show): Home's loans go home
        assertSame("the map back in its room's container", s.holder, s.map.parent)
        s.home.reclaimFromDocks()
        assertSame("the room keeps it", s.holder, s.map.parent)
        assertSame(s.home, chatPane.parent)
        assertFalse(s.home.dockedNow)
    }

    @Test fun theShiftUpdatesEachWindowTwice() {
        val s = SinglePlayStage(app, partyOn = true)
        val out = s.play(Arr.OUT)
        for (p in listOf(Piece.MAP, Piece.PARTY, Piece.CHAT)) {
            val sub = s.sub(p)!!
            assertTrue("$p shown", sub.on)
            assertEquals("$p's window at the plan's box", out.box(p)!!, sub.bounds.let { Box(it[0], it[1], it[2], it[3]) })
        }
        val before = Piece.values().associateWith { s.sub(it)?.places ?: 0 }
        s.fight(true)
        val inn = SinglePlayLayout.plan(Arr.IN, true, partyH = s.home.dockPartyH())
        s.docks.arrange(inn, animate = true)
        for (p in listOf(Piece.MAP, Piece.PARTY, Piece.CHAT)) {
            val sub = s.sub(p)!!
            assertEquals("$p's window grown to the union first", out.box(p)!!.union(inn.box(p)!!), sub.bounds.let { Box(it[0], it[1], it[2], it[3]) })
        }
        s.settle(3)
        s.docks.arrange(inn, animate = true)                               // the 100 ms tick asks again mid-glide: it goes on
        s.settle(30)                                                      // the glide's 240 ms and more
        for (p in listOf(Piece.MAP, Piece.PARTY, Piece.CHAT)) {
            val sub = s.sub(p)!!
            // (the map's union is its out-of-combat box: no grow needed, the shrink alone)
            val want = if (out.box(p)!!.union(inn.box(p)!!) == out.box(p)) 1 else 2
            assertEquals("$p: two window updates at most (grow, shrink)", want, sub.places - before.getValue(p))
            assertEquals("$p at its in-combat box", inn.box(p)!!, sub.bounds.let { Box(it[0], it[1], it[2], it[3]) })
            assertEquals(inn.box(p), s.docks.boxNow(p))
        }
        s.layScreen()
        assertEquals("the chat laid at its in-combat size", inn.chat.w, v(s, "chatPane").width)
    }

    @Test fun noPartyWindowWithoutPartyMode() {
        val s = SinglePlayStage(app, partyOn = false)
        val p = s.play(Arr.OUT)
        assertFalse(s.home.dockLaid().party)
        assertNull(p.party)
        assertTrue("no party window shown", s.sub(Piece.PARTY)?.on != true)
        assertEquals(p.chat, s.docks.boxNow(Piece.CHAT))
    }

    @Test fun theTalkPopUpWhileTheHoldLasts() {
        val s = SinglePlayStage(app, partyOn = true)
        val p = s.play(Arr.OUT)
        assertFalse(s.docks.talkSync())
        s.cast.talkPanel = CastView.TalkPanel(listOf(TalkTabs.Tab("PARTY", "Party", 0xFFAAAAFF.toInt()), TalkTabs.Tab("GUILD", "Guild", 0xFF40FF40.toInt())),
            0, CastView.TalkFace(CastView.TalkFace.Mode.LISTEN, 0xFFAAAAFF.toInt(), 0.6f), "[Party]: ", "")
        assertTrue(s.docks.talkSync())
        val t = s.sub(Piece.TALK)!!
        assertTrue(t.on && t.touch)
        assertEquals(p.talk, t.bounds.let { Box(it[0], it[1], it[2], it[3]) })
        s.cast.talkPanel = null
        assertFalse(s.docks.talkSync())
        assertFalse(t.on)
    }

    @Test fun goneDocksHideEveryWindow() {
        val s = SinglePlayStage(app, partyOn = true)
        s.play(Arr.OUT)
        s.docks.show(false)
        for (p in Piece.values()) assertTrue("$p hidden", s.sub(p)?.on != true)
        assertNull(s.docks.plan)
        assertNotNull(s.sub(Piece.MAP))
    }
}
