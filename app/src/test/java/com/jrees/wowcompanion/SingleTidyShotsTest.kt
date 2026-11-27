package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import com.jrees.wowcompanion.SinglePlayLayout.Arr
import com.jrees.wowcompanion.SinglePlayLayout.Piece
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
import java.io.File

/**
 * THE TIDY PASS'S PICTURES (tools/render_single_tidy.py): the REAL docks out of combat with the one type scale (SingleType), the full views
 * the docks zoom into (the Chat room, Home's page with its map module full - the panel's content), and Home as the bottom screen shows it
 * (the comparison row), with the docks' boxes (tidy.json). SINGLE_TIDY_SHOTS = the folder (none: only the checks).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SingleTidyShotsTest {
    private val out: File? = System.getenv("SINGLE_TIDY_SHOTS")?.let { File(it).apply { mkdirs() } }
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun down() {
        PartyModel.live.javaClass.getDeclaredField("party").apply { isAccessible = true }.set(PartyModel.live, null)
        HomeLayoutStore.character(null); HomeLayoutStore.set(HomeLayout())
    }

    private fun png(b: Bitmap, name: String) { out?.let { d -> File(d, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } }

    private fun shot(v: View, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Theme.ink); v.draw(c)
        return b
    }

    private fun arr(b: SinglePlayLayout.Box?) = b?.let { JSONArray().put(it.l).put(it.t).put(it.r).put(it.b) }

    @Test fun theDocksTheirFullViewsAndTheBottomScreen() {
        val s = SinglePlayStage(app, partyOn = true)
        val o = s.play(Arr.OUT)
        for (n in 0 until 12) { s.draw().recycle(); Thread.sleep(40); s.settle(2) }
        png(s.draw(), "docks_out.png")
        val j = JSONObject()
        for (k in listOf(Piece.MAP, Piece.PARTY, Piece.CHAT, Piece.QUEST, Piece.ROW, Piece.BTN_L, Piece.BTN_R)) j.put(k.name.lowercase(), arr(s.docks.boxNow(k)))
        j.put("panel", JSONArray(SingleScreen.panelBox(1920, 1080).toList()))
        // the grid: the docks on one edge each side, the corner buttons on the docks' edges and the row's foot
        assertEquals(o.chat.l, o.party!!.l)
        assertEquals(o.map.r, o.btnR!!.r)
        // the full views: the Chat room and Home's page with its map module full, at the panel's size (what the zoom grows)
        val pb = SingleScreen.panelBox(1920, 1080)
        val pw = pb[2] - pb[0]; val ph = pb[3] - pb[1]
        s.docks.show(false)
        s.home.reclaimFromDocks()
        s.settle(4)
        s.lay(s.home, s.HW, s.HH)
        s.settle(4); s.lay(s.home, s.HW, s.HH)
        png(shot(s.home, s.HW, s.HH), "bottom_home.png")
        (s.chat.parent as? android.view.ViewGroup)?.removeView(s.chat)
        s.lay(s.chat, pw, ph); s.settle(4); s.lay(s.chat, pw, ph)
        png(shot(s.chat, pw, ph), "panel_chat.png")
        assertTrue("the map module opens", s.home.openMapModule())
        s.settle(30); s.lay(s.home, s.HW, s.HH); s.settle(4)
        png(shot(s.home, s.HW, s.HH), "panel_map.png")
        out?.let { d -> File(d, "tidy.json").writeText(j.toString(1)) }
        // the type the docks use: one body size for the chat's lines, the party's names and the card's objectives
        assertEquals(SingleType.BODY, 23f * UiKit.CARD_DOCK_SCALE, 0.01f)
        assertTrue(Color.alpha(Theme.ink) > 0)
    }
}
