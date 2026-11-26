package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.jrees.wowcompanion.SingleMenu.Move
import com.jrees.wowcompanion.SingleMenu.Phase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.io.File
import java.time.Duration

/**
 * THE DS MENU ON THE TOP SCREEN, DRAWN (the 1536 pass 1): the REAL SingleMenuView round a rooms column laid as CompanionHost lays it on
 * the stage (SingleMenu.place: laid at its size, scaled, cut at the room's foot), the REAL Strip's live swirl and round room pictures, the
 * REAL Bags (its 16:9 layout: the grid on the left, the tooltip in its own column) and Character (the fallback). The frames of each motion
 * and the states between, out of a fight and in one (the red swirl with B, the red edges). DS_MENU_SHOTS = the folder (none: only the
 * checks), DS_MENU_BG = the game's frame, HOME_LAYOUT_ART = the art root.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SingleMenuShotsTest {
    private val out: File? = System.getenv("DS_MENU_SHOTS")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val TW = 1920
    private val TH = 1080
    private val titles = ArrayList<String>()

    @Before fun up() {
        System.getenv("HOME_LAYOUT_ART")?.let { root -> ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
        Tr.init(app); Settings.init(app); HomeSettings.init(app); CastSettings.init(app); GameUi.init(app)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
    }
    @After fun down() { CastSettings.buttonLayout = CastSettings.ButtonLayout.NINTENDO }

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun idle(ms: Long = 16) { ShadowSystemClock.advanceBy(Duration.ofMillis(ms)); ShadowLooper.idleMainLooper() }

    // ---------------------------------------------------------------- the rooms

    private fun l(t: String, c: String = "ffffff") = JSONObject().put("t", t).put("c", c)
    private fun item(slot: Int, id: Int, name: String, icon: Int, q: Int = 1, count: Int = 1) =
        JSONObject().put("bag", 0).put("slot", slot).put("id", id).put("icon", icon).put("count", count).put("quality", q).put("name", name)
            .put("itype", "Miscellaneous").put("isub", "Junk").put("cls", 15).put("sub", 0)
    private fun bagsDoc(): JSONObject {
        val a = JSONArray()
        a.put(item(1, 6948, "Hearthstone", 134414).put("use", true).put("cls", 0))
        a.put(item(2, 5523, "Small Barnacled Clam", 134433).put("opens", true))
        a.put(item(4, 2589, "Linen Cloth", 132889, count = 12).put("cls", 7).put("itype", "Trade Goods").put("isub", "Cloth"))
        a.put(item(5, 118, "Minor Healing Potion", 134829, count = 4).put("use", true).put("cls", 0))
        a.put(item(6, 2835, "Rough Stone", 135241, count = 7).put("cls", 7).put("itype", "Trade Goods").put("isub", "Parts"))
        val ctl = JSONObject().put("on", true).put("mode", "grid").put("seq", 1).put("sid", 1).put("open", true)
            .put("cur", JSONObject().put("bag", 0).put("slot", 1))
            .put("tip", JSONObject().put("key", "bag 0 1 6948").put("lines", JSONArray().put(l("Hearthstone")).put(l("Use: Returns you to your home.", "00ff00"))))
        return JSONObject().put("free", 11).put("total", 16).put("money", 10234)
            .put("bags", JSONArray().put(JSONObject().put("bag", 0).put("slots", 16).put("free", 11).put("fam", 0)))
            .put("items", a).put("ctl", ctl)
            .put("pad", JSONObject().put("rows", JSONObject().put("bmvq", 27).put("bag0", 30).put("bmv", 29).put("bagsort", 28)))
    }
    private fun character(): CharacterView = CharacterView(app).also { v ->
        v.update(JSONObject().put("type", "character").put("combat", false).put("c", JSONObject()
            .put("who", JSONObject().put("name", "Tharn").put("lvl", 24).put("line", "Level 24 Dwarf Hunter").put("cls", "Hunter")
                .put("clsFile", "HUNTER").put("race", "Dwarf").put("faction", "Alliance"))
            .put("st", JSONArray().put(JSONObject().put("h", "Attributes").put("r", JSONArray()
                .put(JSONObject().put("k", "str").put("l", "Strength").put("v", "41"))
                .put(JSONObject().put("k", "agi").put("l", "Agility").put("v", "77"))
                .put(JSONObject().put("k", "sta").put("l", "Stamina").put("v", "58")))))
            .put("win", JSONObject().put("open", false).put("ctl", true))))
    }

    // ---------------------------------------------------------------- the top window's content as the host lays it

    private inner class Stage {
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val root = FrameLayout(act)
        val column = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; ThemeLive.bg(this, Tok.INK) }
        val content = FrameLayout(act)
        val strip = Strip(act) { }
        val menu = SingleMenuView(act)
        val bags = BagsView(app)
        val char = character()
        var place: SingleMenu.Place? = null
        var fight = false
        var cursor: Room? = Room.HOME
        var dim = SingleLook.DIM_PANEL

        init {
            bags.update(JSONObject(bagsDoc().toString()).put("ctl", JSONObject().put("on", false)), true)
            bags.update(bagsDoc(), true)
            for (v in listOf<View>(bags, char)) { content.addView(v, FrameLayout.LayoutParams(-1, -1)); v.visibility = View.GONE }
            column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            column.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Strip.HEIGHT).apply { topMargin = -Strip.RISE - Strip.TRACKS })
            strip.showOrder(RoomBar.shown(HomeSettings.layout), 0L)
            strip.visibility = View.INVISIBLE                                       // the menu's row instead (menuStripHide)
            root.addView(menu.back, FrameLayout.LayoutParams(-1, -1))
            root.addView(column, FrameLayout.LayoutParams(-1, -1))
            root.addView(menu.front, FrameLayout.LayoutParams(-1, -1))
            act.setContentView(root)
            menu.order = SingleMenu.order(strip.order)
            menu.cursorOf = { cursor }
            menu.fightNow = { fight }
            menu.triggersOn = { true }
            menu.pulse = { 450L }                                                   // the edges' pulse at its height
            menu.drawRoom = { c, r, cx, cy, s -> strip.drawRoomPicture(c, r, cx, cy, s) }
            menu.drawSwirl = { c, cx, cy, d -> strip.drawDiscAt(c, cx, cy, d) }
            menu.onPage = { box, a -> page(box, a) }
            lay(root, TW, TH)
        }

        /** CompanionHost.menuColumn: the column laid at its place's size, cut at the room's foot; Bags' 16:9 layout */
        fun show(r: Room?) {
            for (v in listOf<View>(bags, char)) v.visibility = View.GONE
            when (r) { Room.BAGS -> bags.visibility = View.VISIBLE; Room.CHARACTER -> char.visibility = View.VISIBLE; else -> {} }
            column.visibility = if (r == null) View.GONE else View.VISIBLE
            val p = r?.let { SingleMenu.place(it) }
            place = p
            if (p != null) {
                column.layoutParams = FrameLayout.LayoutParams(p.colW, p.colH)
                column.clipBounds = android.graphics.Rect(0, 0, p.colW, SingleMenu.ROOM_H)
                bags.wideLeft = if (p.wide && r == Room.BAGS) SingleMenu.PANEL_W else 0
                page(p.drawn, 1f)
            }
            lay(root, TW, TH)
        }

        /** CompanionHost.menuPage */
        fun page(box: SingleMenu.Box?, a: Float) {
            val p = place ?: return
            if (box == null) return
            val k = box.w / p.colW
            column.pivotX = 0f; column.pivotY = 0f; column.scaleX = k; column.scaleY = k
            column.translationX = box.l; column.translationY = box.t; column.alpha = a
        }

        /** CompanionHost.menuRecord */
        fun record(): Pair<Picture, SingleMenu.Box> {
            val p = place!!
            val pic = Picture(); val c = pic.beginRecording(p.colW, SingleMenu.ROOM_H)
            c.clipRect(0, 0, p.colW, SingleMenu.ROOM_H); column.draw(c); pic.endRecording()
            return pic to SingleMenu.Box(0f, 0f, p.colW.toFloat(), SingleMenu.ROOM_H.toFloat())
        }

        fun swirl(fightNow: Boolean, phase: Phase) {
            strip.menuRed = SingleMenu.swirlRed(fightNow, phase)
            strip.homeFocus = phase == Phase.ROW && cursor == Room.HOME
            strip.snapRed()
            idle(600)
        }

        /** the top screen now: the game, the window's dim, the window */
        fun shot(name: String, title: String, dimK: Float = 1f): Bitmap {
            lay(root, TW, TH)
            val warm = Bitmap.createBitmap(TW, TH, Bitmap.Config.ARGB_8888)
            for (i in 0 until 3) { root.draw(Canvas(warm)); idle(120) }
            lay(root, TW, TH)
            val pic = backdrop()
            val c = Canvas(pic)
            c.drawColor(Color.argb((255 * dim * dimK).toInt(), 0, 0, 0))
            root.draw(c)
            save(pic, name, title)
            return pic
        }
    }

    private fun backdrop(): Bitmap {
        val b = Bitmap.createBitmap(TW, TH, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(46, 52, 58))
        val p = System.getenv("DS_MENU_BG") ?: "C:/dsf-work/r1533-shots/single/core/_bg_sharp.png"
        BitmapFactory.decodeFile(p)?.let { g -> c.drawBitmap(g, null, RectF(0f, 0f, TW.toFloat(), TH.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG)) }
        return b
    }

    /** each shot at 1280 x 720 (C: is short of room), its title for the sheet */
    private fun save(b: Bitmap, name: String, title: String) {
        val d = out ?: return
        d.mkdirs()
        val small = Bitmap.createScaledBitmap(b, 1280, 720, true)
        File(d, "$name.png").outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        titles += "$name.png|$title"
        File(d, "titles.txt").appendText("$name.png|$title\n")
    }

    // ---------------------------------------------------------------- the checks

    @Test fun bagsSixteenByNineTheGridLeftTheTooltipInItsOwnColumn() {
        val s = Stage()
        s.show(Room.BAGS)
        idle(400); s.show(Room.BAGS)
        val p = SingleMenu.place(Room.BAGS)
        assertEquals("the column laid wide", p.colW, s.column.width)
        assertEquals(p.colW, s.bags.width)
        val col = BagsView::class.java.getDeclaredField("column").apply { isAccessible = true }.get(s.bags) as View
        assertEquals("the bottom screen's page on the left (the grid of 10)", SingleMenu.PANEL_W, col.width)
        val ctl = BagsView::class.java.getDeclaredField("ctlLayer").apply { isAccessible = true }.get(s.bags) as View
        val place = ctl.javaClass.getDeclaredMethod("place", RectF::class.java, Float::class.javaPrimitiveType).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val t = place.invoke(ctl, RectF(900f, 300f, 1000f, 400f), 1f) as Triple<Float, Float, Float>
        assertTrue("the tooltip right of the grid's page (${t.first})", t.first >= SingleMenu.PANEL_W)
        assertTrue("its compare beside it, inside the room", t.third + t.second <= p.colW)
        // back on the bottom screen's page: as before (the tooltip by the cursor)
        s.bags.wideLeft = 0
        lay(s.bags, SingleMenu.PANEL_W, SingleMenu.ROOM_H)
        @Suppress("UNCHECKED_CAST")
        val t2 = place.invoke(ctl, RectF(100f, 300f, 200f, 400f), 1f) as Triple<Float, Float, Float>
        assertTrue("two screens: beside the cursor", t2.first < 400f)
    }

    @Test fun theMenuInMotionAndAtRest() {
        out?.let { d -> File(d, "titles.txt").delete() }
        val s = Stage()
        val m = s.menu
        val dock = SinglePlayLayout.plan(SinglePlayLayout.Arr.OUT, false).row!!.let { b ->
            SingleMenu.Box(b.l.toFloat(), b.t.toFloat(), b.r.toFloat(), b.b.toFloat())
        }
        // ---- out of a fight
        s.show(null); s.cursor = Room.HOME; s.swirl(false, Phase.ROW)
        m.play(Move.RISE, Phase.ROW, null, dockBox = dock); m.seek(70L)
        s.shot("01_down_rise_070ms", "Down: the docked row rises into the fan (70 ms of 200)", dimK = 0.4f)
        m.seek(SingleMenu.RISE_MS)
        s.shot("02_row_on_the_swirl", "The row up: the cursor on Home's swirl (A Home, B Close)")
        s.cursor = Room.BAGS; s.swirl(false, Phase.ROW); idle(200)
        s.shot("03_row_cursor_bags", "Right twice: the cursor on Bags, its name under it")
        s.show(Room.BAGS)
        m.play(Move.UNFOLD, Phase.ROOM, Room.BAGS); s.swirl(false, Phase.ROOM); m.seek(110L)
        s.shot("04_a_bags_unfolds_110ms", "A: Bags unfolds up out of its disc (110 ms of 240)")
        m.seek(SingleMenu.UNFOLD_MS)
        val bagsShot = s.shot("05_bags_16x9", "Bags on the stage, its 16:9 layout: grid left, tooltip in its own column")
        assertEquals("the page drawn at the stage's scale", SingleMenu.place(Room.BAGS).k, s.column.scaleX, 0.001f)
        assertTrue(bagsShot.width == TW)
        val (pic, src) = s.record()
        s.show(Room.CHARACTER)
        m.play(Move.SLIDE, Phase.ROOM, Room.CHARACTER, pic, src); m.seek(100L)
        s.shot("06_zl_slide_100ms", "ZL: Bags slides out, Character (the room before it) slides in (100 ms of 220)")
        m.seek(SingleMenu.SLIDE_MS)
        s.shot("07_character_fallback", "Character at the fallback size until its pass (the same scale as Bags)")
        val (pic2, src2) = s.record()
        s.show(null); s.cursor = Room.CHARACTER
        s.swirl(false, Phase.ROW); m.play(Move.FOLD, Phase.ROW, null, pic2, src2); m.seek(80L)
        s.shot("08_b_fold_080ms", "B: Character folds back down into its disc (80 ms of 200)")
        m.seek(SingleMenu.FOLD_MS)
        s.shot("09_row_on_character", "The row again, the cursor on the room just left")
        m.play(Move.DROP, Phase.CLOSED, null, dockBox = dock); m.seek(90L)
        s.shot("10_b_drop_090ms", "B again: the row drops back into the docked row (90 ms of 180)", dimK = 0.5f)
        // ---- in a fight
        s.fight = true
        s.cursor = Room.HOME
        m.play(Move.RISE, Phase.ROW, null); s.swirl(true, Phase.ROW); m.seek(SingleMenu.RISE_MS)
        s.shot("11_fight_row", "In a fight: the row, the swirl red with B, the edges flash red")
        s.show(Room.BAGS)
        m.play(Move.UNFOLD, Phase.ROOM, Room.BAGS); s.swirl(true, Phase.ROOM); m.seek(SingleMenu.UNFOLD_MS)
        s.shot("12_fight_bags_full", "In a fight: Bags in full (as on two screens), \"In a fight\", the red rim, B = the game")
        val (pic3, src3) = s.record()
        s.show(null)
        m.play(Move.FOLD_DROP, Phase.CLOSED, null, pic3, src3); m.seek(80L)
        s.shot("13_fight_b_to_game_080ms", "In a fight: B goes straight to the game - the room folds, the row drops (80 ms of 220)", dimK = 0.45f)
        m.settle()
        assertEquals(Phase.CLOSED, m.phase)
    }
}
