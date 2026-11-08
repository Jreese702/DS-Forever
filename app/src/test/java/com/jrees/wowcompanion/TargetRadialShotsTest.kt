package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * DESIGN RENDERS for the TARGET RADIAL (the user, 2026-11-07: "When targeting something while not in the combat state, I want the Y
 * glyph to appear over them for the inspect menu. Instead of that menu opening a drop down I want to design a radial for it"): the
 * pause wheel's kit (the game's GamepadRadial) filled with a target's menu, on the 1240 x 1080 bottom screen. Pictures only; runs
 * when TRADE_SHOTS_DIR is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TargetRadialShotsTest {
    private val out: File? = System.getenv("TRADE_SHOTS_DIR")?.let { File(it) }

    private fun artRoot() {
        val root = System.getenv("TRADE_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun shot(name: String, title: String, items: List<PlayerMenu.Entry>, pointed: Int, page: TargetRadial.Page = TargetRadial.ROOT) {
        val o = out ?: return
        val b = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(20, 24, 32))
        val screen = RectF(0f, 0f, 1240f, 1080f)
        val hub = UiKit.consumableRadialHub(screen)
        val slices = TargetRadial.slices(items, page)!!.map { it?.let { s -> UiKit.PauseSlice(s.icon, s.label, s.enabled, menu = s.menu) } }
        UiKit.pauseRadial(c, screen, PointF(hub.x, hub.y), 150f, slices, -1, pointed, -1, UiKit.PAUSE_OPEN_MS, 0f, null,
            pickGlyph = true, up = null, down = null, left = null, right = null, title = title)
        o.mkdirs()
        File(o, "$name.png").outputStream().use { s -> b.compress(Bitmap.CompressFormat.PNG, 100, s) }
    }

    private fun e(t: String, k: String, on: Boolean = true, sub: List<PlayerMenu.Entry>? = null) =
        PlayerMenu.Entry(t, if (sub != null) null else 1, on, null, false, sub, key = k)
    private val raid = listOf("Star", "Circle", "Diamond", "Triangle", "Moon", "Square", "Cross", "Skull").mapIndexed { i, n -> e(n, "RaidTarget${i + 1}") }

    @Test
    fun designs() {
        artRoot()
        // a friendly PLAYER targeted (the game's PLAYER menu, Quests q51)
        val player = listOf(PlayerMenu.Entry("Brannoc", null, true, null, true, null), e("Set Focus", "SetFocus"), e("Whisper", "Whisper"),
            e("Inspect", "Inspect"), e("Invite", "Invite"), e("Trade", "Trade"), e("Follow", "Follow"), e("Duel", "Duel"),
            e("Add Friend", "AddFriend"), e("Raid Target Icon", "RaidTarget", sub = raid), e("Report Player", "ReportPlayer", on = false),
            e("Promote to Party Leader", "PromoteToLeader"), e("Uninvite", "Uninvite"))
        shot("target_player", "Brannoc", player, 3)
        shot("target_player_more", "Brannoc", player, 0, TargetRadial.Page(more = true))
        shot("target_player_raid", "Raid Target Icon", player, -1, TargetRadial.Page(sub = "Raid Target Icon"))
        // an NPC targeted (the TARGET menu)
        val npc = listOf(e("Set Focus", "SetFocus"), e("Raid Target Icon", "RaidTarget", sub = raid))
        shot("target_npc", "Kobold Tunneler", npc, -1)
    }
}
