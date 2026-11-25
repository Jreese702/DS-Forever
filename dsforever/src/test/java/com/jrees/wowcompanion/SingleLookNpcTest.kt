package com.jrees.wowcompanion

import com.jrees.wowcompanion.SingleLook.Frame
import com.jrees.wowcompanion.SingleLook.In
import com.jrees.wowcompanion.SingleLook.Root
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SINGLE SCREEN MODE, THE NPC PART (SingleLook rows 4a / 6-10, SingleNpc; the user's picks 2026-11-25 "Ours over the game", "Centred
 * panel", "Straight to the game"): the quick wheels and the class wheel over a light dim, the type tool / the fight's rolls / LB's
 * picture in the one strip window with the rest of the top screen the game's, the layout editor's light dim, the strip's places on the
 * top screen (clear of the add-on's compass, HUD block and Wings), a tap on the dim, Settings' "Set by One screen".
 */
class SingleLookNpcTest {
    private val play = In()
    @After fun down() { SingleScreen.reset() }

    // ---------------------------------------------------------------- the look's NPC rows

    @Test fun row6TheQuickWheelsOverALightDimNothingUnder() {
        val q = SingleLook.of(play.copy(quick = true))
        assertEquals(Frame.FULL, q.frame); assertEquals(SingleLook.DIM_QUICK, q.dim); assertTrue("its dim tap closes it", q.touchable)
        assertEquals(Root.GONE, q.root); assertFalse(q.docks); assertNull(q.strip)
        // over Home's glance the glance's look (the wheel in Home's own slot, as on two screens)
        assertEquals(Root.HOME, SingleLook.of(play.copy(quick = true, glanceHub = true)).root)
    }

    @Test fun row7TheClassWheelHomesLayersOnly() {
        val c = SingleLook.of(play.copy(classWheel = true))
        assertEquals(Frame.FULL, c.frame); assertEquals(SingleLook.DIM_QUICK, c.dim); assertEquals(Root.HOME_LAYERS, c.root); assertTrue(c.touchable)
        assertFalse(c.docks)
    }

    @Test fun row8TheTypeToolIsTheStripTheRestTheGames() {
        for (t in listOf(0, 1)) {
            val l = SingleLook.of(play.copy(tool = t))
            assertEquals(Frame.HIDDEN, l.frame); assertEquals(0f, l.dim); assertEquals(Root.GONE, l.root)
            assertEquals(SingleNpc.TOOL, l.strip); assertFalse("the docks give the screen to the tool", l.docks); assertFalse(l.play)
        }
    }

    @Test fun row10TheFightsRollsInTheRightDocksPlace() {
        val r = SingleLook.of(play.copy(fightRolls = true, dialog = true))
        assertEquals(Frame.HIDDEN, r.frame); assertEquals(SingleNpc.ROLLS, r.strip); assertTrue("the left dock stays", r.docks)
        // the full roll window (the player opened it, or out of a fight) is a dialog over the dim
        val full = SingleLook.of(play.copy(dialog = true))
        assertEquals(Frame.FULL, full.frame); assertEquals(SingleLook.DIM_PANEL, full.dim); assertNull(full.strip)
    }

    @Test fun row9LbsPictureIsDisplayOnlyInTheStrip() {
        val lb = SingleLook.of(play.copy(lb = true))
        assertEquals("no window over the whole screen for a held button", Frame.HIDDEN, lb.frame)
        assertFalse("display only", lb.touchable); assertEquals(0f, lb.dim); assertTrue(lb.docks); assertEquals(SingleNpc.LB, lb.strip)
        assertFalse(lb.play)
        // a quick wheel / the class wheel win over it (LB's picture drops for them on two screens too)
        assertEquals(Frame.FULL, SingleLook.of(play.copy(lb = true, quick = true)).frame)
        assertEquals(Root.HOME_LAYERS, SingleLook.of(play.copy(lb = true, classWheel = true)).root)
        // LB held over the fight's small rolls: the held button's moment has the strip, the rolls come back at its release
        assertEquals(SingleNpc.LB, SingleLook.of(play.copy(lb = true, fightRolls = true, dialog = true)).strip)
        assertEquals(SingleNpc.ROLLS, SingleLook.of(play.copy(lb = false, fightRolls = true, dialog = true)).strip)
    }

    @Test fun row4aTheLayoutEditorsLightDimOverSettings() {
        val l = SingleLook.of(play.copy(room = "SETTINGS", layoutEdit = true))
        assertEquals(Frame.FULL, l.frame); assertEquals("the pieces it moves show at the sides", SingleLook.DIM_QUICK, l.dim)
        assertEquals(Root.ROOM, l.root); assertTrue(l.touchable)
        assertEquals(SingleLook.DIM_PANEL, SingleLook.of(play.copy(room = "SETTINGS")).dim)
        // the pause wheel / an NPC window over it keep their own dim; with no room it is nothing (Home's rest)
        assertEquals(SingleLook.DIM_PANEL, SingleLook.of(play.copy(room = "SETTINGS", layoutEdit = true, pause = true)).dim)
        assertEquals(SingleLook.PLAY, SingleLook.of(play.copy(layoutEdit = true)))
    }

    @Test fun theNpcWindowsArePanelsOverTheDim() {
        for (i in listOf(play.copy(npc = true), play.copy(dialog = true), play.copy(spells = true), play.copy(report = true))) {
            val l = SingleLook.of(i)
            assertEquals("$i", Frame.FULL, l.frame); assertEquals(SingleLook.DIM_PANEL, l.dim); assertTrue(l.touchable); assertNull(l.strip)
        }
        // an NPC window over the strip's tool / rolls / LB: the window
        for (i in listOf(play.copy(npc = true, tool = 1), play.copy(npc = true, fightRolls = true), play.copy(npc = true, lb = true)))
            assertNull("$i", SingleLook.of(i).strip)
    }

    // ---------------------------------------------------------------- the strip's places (1920 x 1080: the Thor's top screen)

    /** the add-on's own keep-out zones (spec 7.2): K1 the compass + target bar, K2 the HUD block, K4's typical Wings halves */
    private val k1 = intArrayOf(530, 0, 1390, 210)
    private val k2 = intArrayOf(640, 780, 1280, 1080)
    private val k4l = intArrayOf(380, 850, 740, 1064)
    private val k4r = intArrayOf(1180, 850, 1540, 1064)
    private fun meets(a: IntArray, b: IntArray) = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]
    private fun inside(a: IntArray, w: Int, h: Int) = a[0] >= 0 && a[1] >= 0 && a[2] <= w && a[3] <= h && a[2] > a[0] && a[3] > a[1]

    @Test fun theKeyboardStripIsTheBoxsWidthAtTheFoot() {
        val r = SingleNpc.keysRect(1920, 1080, 428)
        assertArrayEquals(intArrayOf(340, 1080 - 72 - 428, 1580, 1080), r)
        assertEquals(1f, SingleNpc.boxScale(1920, 1080))
        // a 1280 x 720 screen: the box's width (826) at its scale
        val s = SingleNpc.keysRect(1280, 720, 285)
        val box = SingleScreen.panelBox(1280, 720)
        assertEquals(box[0], s[0]); assertEquals(box[2], s[2]); assertEquals(720, s[3]); assertTrue(inside(s, 1280, 720))
    }

    @Test fun theTrackPadInTheBottomRightCorner() {
        assertArrayEquals(intArrayOf(1304, 704, 1904, 1064), SingleNpc.padRect(1920, 1080))
        assertArrayEquals(intArrayOf(1304, 704, 1904, 1064), SingleNpc.toolRect(0, 1920, 1080, 428))
        assertArrayEquals(SingleNpc.keysRect(1920, 1080, 428), SingleNpc.toolRect(1, 1920, 1080, 428))
        assertTrue(inside(SingleNpc.padRect(1280, 720), 1280, 720))
    }

    @Test fun theRollsInTheRightDocksPlaceClearOfTheCompass() {
        val r = SingleNpc.rollsRect(1920, 1080)
        assertArrayEquals(intArrayOf(1584, 16, 1904, 336), r)
        assertTrue("the roll panel is 300 wide at least", r[2] - r[0] >= 308)
        assertFalse(meets(r, k1)); assertFalse(meets(r, k2)); assertTrue(inside(SingleNpc.rollsRect(1280, 720), 1280, 720))
    }

    @Test fun lbsPictureOverTheLeftDockAboveTheWingsInAFight() {
        val out = SingleNpc.lbRect(1920, 1080, fight = false)
        val fight = SingleNpc.lbRect(1920, 1080, fight = true)
        assertEquals(16, out[0]); assertEquals(556, out[2]); assertEquals(220, out[1])
        assertEquals(96, fight[1]); assertTrue("above the Wings' worst reach", fight[3] <= 550)
        assertEquals("clear of the compass (x 530)", 520, fight[2])
        for (r in listOf(out, fight)) { assertFalse(meets(r, k1)); assertFalse(meets(r, k2)); assertFalse(meets(r, k4l)); assertFalse(meets(r, k4r)) }
        // the picture fits its window: the Left + Right row inside the plate's sides, its height inside the plate
        for ((r, f) in listOf(out to false, fight to true)) {
            val s = SingleNpc.lbScale(1080, f)
            assertTrue("$s", s <= 1f && s >= LbFit.K_MIN)
            val inner = (r[2] - r[0]) - 2 * SingleNpc.LB_PAD
            assertTrue(SingleNpc.LB_ROW_W * s <= inner + 0.5f)
            val fit = SingleNpc.lbFit(inner, 1080)
            assertEquals(inner / 2f, fit[0], 0.01f); assertEquals(s, fit[2], 0.001f)
            assertTrue(fit[1] + SingleNpc.LB_PICT_H * fit[2] <= (r[3] - r[1]) + 0.5f)
        }
        assertTrue(inside(SingleNpc.lbRect(1280, 720, true), 1280, 720))
    }

    // ---------------------------------------------------------------- a tap on the dim, the rolls, Settings

    @Test fun aTapOnTheDim() {
        assertEquals(SingleNpc.Dim.CLOSE_QUICK, SingleNpc.dimTap(play.copy(quick = true)))
        assertEquals(SingleNpc.Dim.KEEP, SingleNpc.dimTap(play.copy(classWheel = true)))
        for (i in listOf(play.copy(npc = true), play.copy(dialog = true), play.copy(startPages = true), play.copy(report = true),
            play.copy(spells = true), play.copy(npc = true, quick = true)))
            assertEquals("$i: the window keeps it", SingleNpc.Dim.KEEP, SingleNpc.dimTap(i))
        // CORE's: a room (back to the game), Home's glance (off), the pause wheel's own
        for (i in listOf(play.copy(room = "BAGS"), play.copy(glanceHub = true), play.copy(pause = true), play, play.copy(room = "BAGS", quick = true)))
            assertEquals("$i", SingleNpc.Dim.CORE, SingleNpc.dimTap(i))
        // the fight's small rolls are no window over the dim
        assertEquals(SingleNpc.Dim.CORE, SingleNpc.dimTap(play.copy(dialog = true, fightRolls = true)))
    }

    @Test fun theRollsAreSmallOnlyWithNoPopUpAsTheBox() {
        assertTrue(SingleNpc.rollsSmall(showing = true, covers = false, compact = true, pill = false))
        assertTrue(SingleNpc.rollsSmall(showing = true, covers = false, compact = false, pill = true))
        assertFalse("a pop-up as the box", SingleNpc.rollsSmall(showing = true, covers = true, compact = true, pill = false))
        assertFalse("the full window", SingleNpc.rollsSmall(showing = true, covers = true, compact = false, pill = false))
        assertFalse(SingleNpc.rollsSmall(showing = false, covers = false, compact = true, pill = false))
    }

    @Test fun settingsTilesTheOneScreenPresetSets() {
        for (id in listOf("minimap", "tracker", "chat", "dialogs", HudSplit.CONTROLLER_ID, HudSplit.BUFFS_ID, HudSplit.XP_ID, GameUi.CASTBAR_ID))
            assertTrue(id, SingleNpc.setByOne(id))
        for (id in listOf(GameUi.UFT_ID, GameUi.NP_ID, GameUi.PARTY_ID, GameUi.HUD_ID, "loot")) assertFalse(id, SingleNpc.setByOne(id))
    }

    // ---------------------------------------------------------------- the host's wiring (source)

    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val host = File(src, "CompanionHost.kt").readText()
    private fun npcRegion(): String = host.substringAfter("// ===== SINGLE: NPC (builder npc edits only here) =====")
        .substringBefore("/** a pause-menu pick, once the wheel has folded")

    @Test fun theHostsNpcPartIsSingleModeOnly() {
        val r = npcRegion()
        assertTrue(r.substringAfter("private fun singleNpcWire() {").substringBefore("\n    }").contains("single ?: return"))
        assertTrue(r.substringAfter("private fun singleNpcLook(l: SingleLook.Look) {").substringBefore("\n    }").contains("single ?: return"))
        assertTrue(r.substringAfter("private fun singleNpcDim(): Boolean {").substringBefore("\n    }").contains("single == null"))
        // every lambda it wraps keeps the two-screen one as it was (called through when the mode is off)
        for (w in listOf("radialView.homeSlot", "dialogView.mapBox", "lbView.place")) assertTrue(w, r.contains("$w = "))
        // the strip's views go back where they came from
        assertTrue(r.contains("private fun stripBack("))
        // Home's notes: the plate only when Home's page is not drawn
        assertTrue(host.contains("ui.post { if (!singleNote(w)) homeView.padNote(w) }"))
    }

    @Test fun homesLayersOnlyLeavesTwoScreensAsTheyWere() {
        val home = File(src, "HomeView.kt").readText()
        assertTrue(home.contains("override fun drawChild(c: Canvas, child: View, drawingTime: Long): Boolean = " +
            "if (child === editView || (singleLayersOnly && child !== classLayer)) false else super.drawChild(c, child, drawingTime)"))
        assertTrue(home.substringAfter("override fun dispatchDraw(c: Canvas) {").substringBefore("\n    }").contains("if (singleLayersOnly) { singleLayersDraw(c); return }"))
        assertTrue(home.substringAfter("override fun dispatchTouchEvent(e: MotionEvent): Boolean {").substringBefore("\n    }")
            .contains("if (singleLayersOnly) return singleLayersTouch(e)"))
        assertTrue("its default: two screens", home.contains("var singleLayersOnly = false"))
    }
}
