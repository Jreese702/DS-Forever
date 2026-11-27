package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TIDY PASS (the user on the 1537 build: "The transitions from like using the chat and minimap suck. They should zoom in just like on
 * the bottom screen. In general the single screen just looks like a mess like things don't fit right with like different font sizes"):
 * the one type scale and grid (SingleType) the single-mode pieces take, and the dock zoom's geometry (SingleFade.zoomFit).
 */
class SingleTidyTest {
    private val panel = floatArrayOf(340f, 0f, 1580f, 1080f)
    private val chat = floatArrayOf(16f, 220f, 456f, 752f)

    @Test fun theZoomStartsOnTheDockAndLandsOnThePanel() {
        val z0 = SingleFade.zoomFit(chat, panel, 0f)
        // at 0 the cut (mapped back to the screen) is the dock's box exactly
        val sc = z0[0]
        assertEquals(chat[0], z0[3] * sc + z0[1], 0.5f)
        assertEquals(chat[1], z0[4] * sc + z0[2], 0.5f)
        assertEquals(chat[2], z0[5] * sc + z0[1], 0.5f)
        assertEquals(chat[3], z0[6] * sc + z0[2], 0.5f)
        // one scale (the text keeps its shape), big enough to fill the dock: the panel's content covers the cut
        assertTrue(z0[3] >= panel[0] - 0.5f && z0[5] <= panel[2] + 0.5f && z0[4] >= panel[1] - 0.5f && z0[6] <= panel[3] + 0.5f)
        val z1 = SingleFade.zoomFit(chat, panel, 1f)
        assertEquals(1f, z1[0], 1e-4f); assertEquals(0f, z1[1], 1e-3f); assertEquals(0f, z1[2], 1e-3f)
        assertEquals(panel[0], z1[3], 1e-3f); assertEquals(panel[3], z1[6], 1e-3f)
        // it grows steadily
        var last = 0f
        for (i in 0..10) { val s = SingleFade.zoomFit(chat, panel, i / 10f)[0]; assertTrue(s >= last); last = s }
    }

    @Test fun theZoomRunsOnTheBottomScreensTimes() {
        assertEquals(Motion.MODULE_OPEN, SingleFade.ZOOM_IN_MS)
        assertEquals(Motion.MODULE_CLOSE, SingleFade.ZOOM_OUT_MS)
        assertEquals(HomeView.FADE_MS, SingleFade.ZOOM_FADE_MS, 0f)
    }

    @Test fun oneTypeScaleAndOneGrid() {
        // the quest card's objectives at the body size, its title at the title size (within a pixel)
        assertEquals(SingleType.BODY, SingleType.CARD_OBJ_1 * UiKit.CARD_DOCK_SCALE, 0.01f)
        assertEquals(SingleType.TITLE, SingleType.CARD_TITLE_1 * UiKit.CARD_DOCK_SCALE, 1f)
        assertEquals(UiKit.MODULE_R, SingleType.R, 0f)
        assertEquals(UiKit.MODULE_PAD, SingleType.PAD, 0f)
        assertEquals(1f, SingleType.k(1080), 0f)
        // the grid: the corner buttons on the docks' edge, their foot on the room row's
        val p = SinglePlayLayout.plan(SinglePlayLayout.Arr.OUT, partyMode = false, questH = 150)
        assertEquals(SingleType.EDGE, p.btnL!!.l)
        assertEquals(1920 - SingleType.EDGE, p.btnR!!.r)
        assertEquals(p.row!!.b, p.btnR!!.b)
        assertEquals(1080 - SingleType.FOOT, p.row!!.b)
        assertEquals(p.chat.l, p.btnL!!.l)
        assertEquals(p.map.r, p.btnR!!.r)
        assertEquals(p.map.l, p.quest!!.l); assertEquals(p.map.r, p.quest!!.r)
        assertEquals(SingleType.GAP, p.quest!!.t - p.map.b)
    }
}
