package com.jrees.wowcompanion

import android.graphics.Bitmap
import com.jrees.wowcompanion.SinglePlayLayout.Arr
import com.jrees.wowcompanion.SinglePlayLayout.Piece
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
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
 * SINGLE SCREEN MODE WHILE PLAYING, THE APP'S LAYER (SingleDocks over the game; the user's pick "1, 2, and also the chat on the left thr ds
 * toolbar should have the same functionality in single screen mode. We can shift those panels ine and out of combat to all fit better"):
 * the REAL Home's map, Party mode's list and chat in the docks, drawn over a transparent 1920 x 1080 top screen as the window manager lays
 * the dock windows - out of combat, in combat, without Party mode, the shift's frames, hold to talk's pop-up - with each shot's boxes and
 * the keep-outs (docks.json) for tools/render_single_play.py, which lays them over the game with the add-ons' real top-screen scene
 * (the Wings, our top cast bar). SINGLE_PLAY_SHOTS = the folder (none: only the checks); HOME_LAYOUT_ART = an art root.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SinglePlayShotsTest {
    private val out: File? = System.getenv("SINGLE_PLAY_SHOTS")?.let { File(it).apply { mkdirs() } }
    private val app get() = RuntimeEnvironment.getApplication()
    private val boxes = JSONObject()

    @After fun down() {
        PartyModel.live.javaClass.getDeclaredField("party").apply { isAccessible = true }.set(PartyModel.live, null)
        HomeLayoutStore.character(null); HomeLayoutStore.set(HomeLayout())
    }

    private fun arr(b: SinglePlayLayout.Box?) = b?.let { JSONArray().put(it.l).put(it.t).put(it.r).put(it.b) }

    private fun save(s: SinglePlayStage, name: String, p: SinglePlayLayout.Plan, party: Boolean, talk: Boolean = false): Bitmap {
        val b = s.draw()
        val j = JSONObject().put("arr", p.arr.name).put("party", party).put("talk", talk).put("chatLines", p.chatLines)
        for (k in listOf(Piece.MAP, Piece.PARTY, Piece.CHAT, Piece.QUEST, Piece.ROW, Piece.CAST, Piece.BTN_L, Piece.BTN_R)) j.put(k.name.lowercase(), arr(s.docks.boxNow(k)))
        if (talk) j.put("talkBox", arr(p.talk))
        val ks = JSONArray()
        for (k in SinglePlayLayout.keepOuts(p.arr, party)) ks.put(JSONArray().put(k.name).put(k.box.l).put(k.box.t).put(k.box.r).put(k.box.b))
        j.put("keeps", ks)
        boxes.put(name, j)
        out?.let { d -> File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return b
    }

    /** some of the docks' own pixels drawn inside [box] (not the transparent screen) */
    private fun painted(b: Bitmap, box: SinglePlayLayout.Box?): Boolean {
        box ?: return false
        var n = 0
        for (y in box.t + 4 until box.b - 4 step 7) for (x in box.l + 4 until box.r - 4 step 7) if ((b.getPixel(x, y) ushr 24) > 0) n++
        return n > 20
    }

    /** nothing drawn outside the docks' boxes: a finger there reaches the game */
    private fun clearOutside(b: Bitmap, boxes: List<SinglePlayLayout.Box?>): Boolean {
        val bs = boxes.filterNotNull()
        for (y in 0 until b.height step 9) for (x in 0 until b.width step 9) {
            if (bs.any { x >= it.l && x < it.r && y >= it.t && y < it.b }) continue
            if ((b.getPixel(x, y) ushr 24) > 0) return false
        }
        return true
    }

    @Test fun theDocksOutAndInOfCombatTheShiftAndHoldToTalk() {
        val s = SinglePlayStage(app, partyOn = true)
        val o = s.play(Arr.OUT)
        warm(s)
        val bo = save(s, "dock_out", o, party = true)
        assertTrue("the map, the party list and the chat drawn", painted(bo, o.map) && painted(bo, o.party) && painted(bo, o.chat))
        // the 1535 polish: the focused quest's card under the map, the room row at the bottom centre, the chat on the right
        assertTrue("the quest card drawn; Party mode on: no room row (Hub's rows keep the HUD's place)", painted(bo, o.quest) && o.row == null)
        assertTrue("the chat on the right", o.chat.l > 960)
        assertTrue("nothing of ours outside them", clearOutside(bo, listOf(o.map, o.party, o.chat, o.quest, o.row, o.btnL, o.btnR)))
        // the docks see-through (the user: "way more see through"): a pixel inside the chat's well, away from its words, lets the game through
        assertTrue("the chat's ground is see-through: alpha ${bo.getPixel(o.chat.l + 20, o.chat.t + 20) ushr 24}",
            (bo.getPixel(o.chat.l + 20, o.chat.t + 20) ushr 24) < 120)
        // the shift into the fight: its frames
        s.fight(true)
        val i = s.plan(Arr.IN)
        val moving = listOf(Piece.MAP, Piece.PARTY, Piece.CHAT, Piece.QUEST, Piece.ROW)
        val from = moving.associateWith { s.docks.boxNow(it) }
        s.docks.arrange(i, animate = true)
        // the glide's frames as the animator lays them (its own clock under the paused looper runs it to its end at once); the row slides
        // down under the bottom edge whole
        for ((f, name) in listOf(0f to "dock_glide_0", 0.5f to "dock_glide_50")) {
            for (k in moving) {
                val a = from[k]; val b = i.box(k) ?: if (k in SinglePlayLayout.SLIDES && a != null) SinglePlayLayout.below(a) else null
                frame.invoke(s.docks, k, a, b, Motion.curve().getInterpolation(f))
            }
            save(s, name, i, party = true)
        }
        s.settle(20)
        val bi = save(s, "dock_glide_100", i, party = true)
        assertTrue("landed in the fight's places", painted(bi, i.map) && painted(bi, i.party) && painted(bi, i.chat))
        assertTrue("the row gone in the fight (its window down)", i.row == null && s.sub(Piece.ROW)?.on == false)
        save(s, "dock_in", i, party = true)
        // hold to talk's pop-up over the party list (out of combat)
        s.fight(false)
        s.docks.arrange(o, animate = false)
        s.cast.talkPanel = CastView.TalkPanel(listOf(TalkTabs.Tab("PARTY", "Party", 0xFFAAAAFF.toInt()), TalkTabs.Tab("GUILD", "Guild", 0xFF40FF40.toInt()),
            TalkTabs.Tab("SAY", "Say", 0xFFFFFFFF.toInt())), 0, CastView.TalkFace(CastView.TalkFace.Mode.LISTEN, 0xFFAAAAFF.toInt(), 0.6f), "[Party]: ", "")
        s.docks.talkSync()
        val bt = save(s, "dock_talk", o, party = true, talk = true)
        assertTrue("the pop-up drawn over the party list", painted(bt, o.talk))
        s.cast.talkPanel = null
        s.docks.talkSync()
        writeBoxes()
    }

    @Test fun withoutPartyMode() {
        val s = SinglePlayStage(app, partyOn = false, castBar = true)
        val o = s.play(Arr.OUT)
        warm(s)
        val bo = save(s, "dock_out_solo", o, party = false)
        assertTrue(painted(bo, o.map) && painted(bo, o.chat))
        assertTrue("the room row drawn at the bottom centre (the Strip, ROW_K)", painted(bo, o.row) && painted(bo, o.quest))
        // the row's slide into the fight: half way down under the edge (the frame SingleDocks lays), then gone
        s.fight(true)
        val i0 = s.plan(Arr.IN)
        s.docks.arrange(i0, animate = true)                       // (the windows grown to the unions; the paused looper holds the glide)
        frame.invoke(s.docks, Piece.ROW, o.row, SinglePlayLayout.below(o.row!!), Motion.curve().getInterpolation(0.5f))
        save(s, "dock_row_half", i0, party = false)
        s.settle(20)
        // the corner buttons (the user: "touch buttons in the bottome left and right"): in a fight, the swirl asks the row up and the left
        // button opens the tap-to-cast bar
        val asked = s.play(Arr.IN, castOpen = true, rowAsk = true)
        s.settle(20)
        val ba = save(s, "dock_corners_open", asked, party = false)
        assertTrue("the tap-to-cast bar open over the left button", painted(ba, asked.cast))
        assertTrue("the row up on the swirl's ask in a fight", painted(ba, asked.row))
        assertTrue("both corner buttons drawn", painted(ba, asked.btnL) && painted(ba, asked.btnR))
        s.fight(false)
        s.docks.arrange(o, animate = false)
        s.fight(true)
        val i = s.plan(Arr.IN)
        s.docks.arrange(i, animate = false)
        save(s, "dock_in_solo", i, party = false)
        writeBoxes("docks_solo.json")
    }

    private fun writeBoxes(name: String = "docks.json") { out?.let { d -> File(d, name).writeText(boxes.toString(1)) } }

    /** SingleDocks' one glide frame (piece, from, to, the curve's fraction) */
    private val frame = SingleDocks::class.java.getDeclaredMethod("frame", Piece::class.java, SinglePlayLayout.Box::class.java,
        SinglePlayLayout.Box::class.java, Float::class.javaPrimitiveType).apply { isAccessible = true }

    /** the map's minimap pictures decode off the UI thread: drawn a few times until they land */
    private fun warm(s: SinglePlayStage) { for (n in 0 until 12) { s.draw().recycle(); Thread.sleep(40); s.settle(2) } }
}
