package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
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
 * SINGLE SCREEN MODE, THE NPC PART, OFF-DEVICE (the user's picks 2026-11-25 "Ours over the game", "Centred panel"): the REAL InteractView,
 * TypeToolView, LbPartyView and rooms composited on the 1920 x 1080 top screen as the top window shows them - a vendor, a quest giver and
 * the bank as the centred panel over the dimmed, running game; the Keyboard strip at the screen's foot and the Track Pad's corner with
 * the game sharp around them; LB's party picture over the left dock; three rooms in the panel (Chat, Settings > Top screen with "Set by
 * One screen", Calendar). SINGLE_SHOTS_DIR = the shots' folder (none: only the checks), SINGLE_TOP_BG = the blurred game frame,
 * SINGLE_TOP_PLAY = the sharp one, SINGLE_ART_ROOT = an art root (ui/kit, fonts, icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SingleNpcShotsTest {
    private val out: File? = System.getenv("SINGLE_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val TW = 1920
    private val TH = 1080
    private val BW = 1240
    private val BH = 1080
    private val box = SingleScreen.panelBox(TW, TH)

    @Before fun up() {
        System.getenv("SINGLE_ART_ROOT")?.let { root -> ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        Tr.init(app); Settings.init(app); SettingsTabs.init(app); GameUi.init(app); HomeSettings.init(app); CastSettings.init(app)
    }
    @After fun down() { single(false) }

    /** SingleScreen.inUse for Settings' tiles (DsForeverScreen alone sets it on the device) */
    private fun single(on: Boolean) {
        SingleScreen::class.java.getDeclaredField("inUse").apply { isAccessible = true }.setBoolean(null, on)
    }

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun settle(ms: Long = 600) { var t = 0L; while (t < ms) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper(); t += 16 } }
    private fun save(b: Bitmap, name: String) {
        val d = out ?: return
        d.mkdirs()
        File(d, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    /** the game on the top screen: [env]'s picture, else a plain dark one */
    private fun backdrop(env: String = "SINGLE_TOP_BG"): Bitmap {
        val b = Bitmap.createBitmap(TW, TH, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(46, 52, 58))
        System.getenv(env)?.let { p -> BitmapFactory.decodeFile(p) }?.let { g -> c.drawBitmap(g, null, RectF(0f, 0f, TW.toFloat(), TH.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG)) }
        return b
    }
    /** [v] drawn twice (art and icons settle after the first frame) into a [w] x [h] picture */
    private fun picture(v: View, w: Int, h: Int, ground: Int = Color.TRANSPARENT): Bitmap {
        lay(v, w, h)
        val warm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); v.draw(Canvas(warm)); settle(300); Thread.sleep(150); ShadowLooper.idleMainLooper()
        lay(v, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); if (ground != Color.TRANSPARENT) c.drawColor(ground)
        v.draw(c)
        return b
    }
    /** the panel's look: the game, the 0.55 dim, the page in the centred box */
    private fun panelShot(page: Bitmap): Bitmap {
        val pic = backdrop()
        val c = Canvas(pic)
        c.drawColor(Color.argb((255 * SingleLook.DIM_PANEL).toInt(), 0, 0, 0))
        c.drawBitmap(page, null, Rect(box[0], box[1], box[2], box[3]), null)
        return pic
    }

    private fun gameNow(): Long {
        val c = java.util.Calendar.getInstance()
        val before = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val doy = before[c.get(java.util.Calendar.MONTH)] + c.get(java.util.Calendar.DAY_OF_MONTH)
        return ((doy * 24L + c.get(java.util.Calendar.HOUR_OF_DAY)) * 60L + c.get(java.util.Calendar.MINUTE)) * 60_000L +
            c.get(java.util.Calendar.SECOND) * 1000L + c.get(java.util.Calendar.MILLISECOND)
    }
    private fun interact(d: JSONObject, bags: JSONObject? = null): InteractView {
        val v = InteractView(app); v.dryRun = true
        if (bags != null) v.updateBags(bags)
        v.update(d.put("type", "interact").put("iv", "i160").put("mode", true).put("_gt", gameNow()), true)
        return v
    }
    private fun bagsDoc(): JSONObject {
        val items = JSONArray()
        val icons = listOf(134414, 132889, 134829, 135241, 133970, 134433)
        for ((k, ic) in icons.withIndex()) items.put(JSONObject().put("bag", 0).put("slot", k + 1).put("id", 2000 + k).put("icon", ic).put("count", 1 + k))
        return JSONObject().put("bags", JSONArray().put(JSONObject().put("bag", 0).put("slots", 16).put("free", 10).put("fam", 0)))
            .put("free", 10).put("total", 16).put("freeGeneral", 10).put("money", 123456L).put("items", items)
    }

    // ---------------------------------------------------------------- NPC windows over the game

    @Test fun aVendorAQuestGiverAndTheBankOverTheGame() {
        // the vendor (the buy tab, the cursor on the first good)
        val goods = listOf(Triple("Refreshing Spring Water", 132794, 25), Triple("Ice Cold Milk", 132815, 62), Triple("Rough Arrow", 132382, 10),
            Triple("Light Shot", 132384, 10), Triple("Small Quiver", 134407, 400), Triple("Tough Jerky", 133972, 25), Triple("Freshly Baked Bread", 133964, 25),
            Triple("Coarse Thread", 132891, 10))
        val items = JSONArray()
        for ((k, g) in goods.withIndex()) items.put(JSONObject().put("i", k + 1).put("id", 159 + k).put("name", g.first).put("icon", g.second)
            .put("price", g.third.toLong()).put("stack", 5).put("avail", -1).put("buyable", true).put("usable", true).put("max", 20).put("q", 1).put("slot", k))
        val m = JSONObject().put("npc", JSONObject().put("name", "Innkeeper Farley")).put("items", items).put("sell", JSONArray())
            .put("buyback", JSONArray()).put("money", 123456L).put("qtyMax", 20).put("canRepair", true).put("needsRepair", true).put("repairCost", 845L)
            .put("junk", 2).put("junkValue", 70L).put("act", JSONObject().put("repair", 1).put("junk", 2).put("back", 3))
        val vendor = interact(JSONObject().put("merchant", m).put("nav", JSONObject().put("on", true).put("n", 1).put("tab", "buy").put("cur", "buy 1 159")), bagsDoc())
        val vp = picture(vendor, BW, BH)
        assertEquals("the vendor is up", View.VISIBLE, vendor.visibility)
        save(panelShot(vp), "npc_01_vendor.png")

        // a quest giver's offer (Accept / Decline)
        val q = JSONObject().put("stage", "detail").put("id", 783).put("title", "A Threat Within")
            .put("text", "I hope you strapped your belt on tight, young adventurer. There is trouble afoot in Northshire. " +
                "Marshal McBride is waiting for you in the abbey: speak with him and he will tell you what needs doing.")
            .put("objectives", "Speak with Marshal McBride.")
            .put("npc", JSONObject().put("name", "Deputy Willem").put("id", 823))
            .put("rewards", JSONObject().put("xp", 40).put("money", 35L)
                .put("items", JSONArray().put(JSONObject().put("name", "Recruit's Gloves").put("icon", 132939).put("q", 1).put("n", 1))))
            .put("act", JSONObject().put("accept", 1).put("decline", 2))
        val giver = interact(JSONObject().put("quest", q).put("nav", JSONObject().put("on", true).put("n", 1)))
        save(panelShot(picture(giver, BW, BH)), "npc_02_quest_giver.png")

        // the bank: its slots on the left, the bags beside it
        val slots = JSONArray()
        val icons = listOf(133784, 135274, 132889, 134414, 132815, 134829, 132382, 135241, 133972, 132794, 134433, 132891)
        for ((k, ic) in icons.withIndex()) slots.put(JSONObject().put("slot", k + 1).put("id", 3000 + k).put("icon", ic).put("count", if (k % 3 == 0) 5 else 1)
            .put("q", k % 4).put("name", "Item ${k + 1}"))
        val mine = JSONArray()
        for ((k, ic) in listOf(134414, 132889, 134829, 135241, 133970, 134433).withIndex())
            mine.put(JSONObject().put("slot", k + 1).put("id", 2000 + k).put("icon", ic).put("count", 1 + k).put("q", 1).put("name", "Item"))
        val bk = JSONObject().put("money", 123456L).put("acct", false).put("account", false).put("pages", 1).put("page", 1).put("bag", -1)
            .put("n", 28).put("slots", slots).put("act", JSONObject().put("char", 1))
            .put("bags", JSONArray().put(JSONObject().put("bag", 0).put("slots", 16).put("items", mine)))
        val bank = interact(JSONObject().put("bank", bk).put("nav", JSONObject().put("on", true).put("n", 1)), bagsDoc())
        save(panelShot(picture(bank, BW, BH)), "npc_05_bank.png")
        // the look the host gives each of them: the panel over the 0.55 dim, every touch ours, the dim's tap kept by the window
        val l = SingleLook.of(SingleLook.In(npc = true))
        assertEquals(SingleLook.Frame.FULL, l.frame); assertEquals(SingleLook.DIM_PANEL, l.dim)
        assertEquals(SingleNpc.Dim.KEEP, SingleNpc.dimTap(SingleLook.In(npc = true)))
    }

    // ---------------------------------------------------------------- the strip: the type tool, LB's picture

    private fun toolPicture(tab: Int, r: IntArray): Bitmap {
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val v = TypeToolView(act) { }
        val holder = FrameLayout(act)
        holder.addView(v, FrameLayout.LayoutParams(r[2] - r[0], r[3] - r[1]))
        act.setContentView(holder)
        v.stripScale = SingleNpc.boxScale(TW, TH)
        v.strip = true
        v.open(tab)
        return picture(v, r[2] - r[0], r[3] - r[1])
    }

    @Test fun theKeyboardStripAndTheTrackPadCornerOverTheGame() {
        val kr = SingleNpc.keysRect(TW, TH, ChatKeyboard.fullHeight(1f))
        val pic = backdrop("SINGLE_TOP_PLAY")
        Canvas(pic).drawBitmap(toolPicture(1, kr), kr[0].toFloat(), kr[1].toFloat(), null)
        save(pic, "npc_12_keyboard_strip.png")
        val pr = SingleNpc.padRect(TW, TH)
        val pad = backdrop("SINGLE_TOP_PLAY")
        Canvas(pad).drawBitmap(toolPicture(0, pr), pr[0].toFloat(), pr[1].toFloat(), null)
        save(pad, "npc_13_trackpad_strip.png")
        assertEquals(SingleLook.Frame.HIDDEN, SingleLook.of(SingleLook.In(tool = 1)).frame)
    }

    @Test fun lbsPartyPictureOverTheLeftDock() {
        val r = SingleNpc.lbRect(TW, TH, fight = false)
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val pad = SingleNpc.LB_PAD
        val holder = object : FrameLayout(act) {
            init { setWillNotDraw(false) }
            override fun onDraw(c: Canvas) { UiKit.menu(c, RectF(0f, 0f, width.toFloat(), height.toFloat()), solid = true) }
        }
        val lb = LbPartyView(act)
        holder.addView(lb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(pad, 0, pad, 0) })
        act.setContentView(holder)
        lb.place = { val f = SingleNpc.lbFit(lb.width, TH); LbFit.Place(f[0], f[1], f[2], 0f, 0f) }
        lay(holder, r[2] - r[0], r[3] - r[1])
        lb.lb(true)
        settle(UiKit.PAUSE_OPEN_MS.toLong() + 200)
        val pic = backdrop("SINGLE_TOP_PLAY")
        Canvas(pic).drawBitmap(picture(holder, r[2] - r[0], r[3] - r[1]), r[0].toFloat(), r[1].toFloat(), null)
        save(pic, "npc_10_lb_party.png")
        assertTrue(lb.isShown2)
    }

    // ---------------------------------------------------------------- three rooms in the panel

    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    /** Settings > Top screen scrolled to its Game windows (the minimap, the tracker, the chat: the tiles the preset sets) */
    private fun toWindows(room: View) {
        val scroll = all(room).filterIsInstance<android.widget.ScrollView>().firstOrNull() ?: return
        val last = all(scroll).filterIsInstance<GameUiSection>().lastOrNull { it.height > 0 } ?: return
        var y = 0; var v: View = last
        while (v !== scroll) { y += v.top; v = v.parent as? View ?: break }
        scroll.scrollTo(0, (y - 70).coerceAtLeast(0))
    }

    private fun roomPage(room: View, r: Room, then: (View) -> Unit = {}): Bitmap {
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        (room.parent as? ViewGroup)?.removeView(room)
        val column = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; ThemeLive.bg(this, Tok.INK) }
        column.addView(room, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val strip = Strip(act) { }
        strip.active = r
        column.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Strip.HEIGHT))
        act.setContentView(column)
        lay(column, BW, BH); settle(200); lay(column, BW, BH)
        then(room)
        return picture(column, BW, BH)
    }

    @Test fun threeRoomsInThePanel() {
        single(true)
        // Chat
        val chat = ChatView(app) { }
        val lines = JSONArray()
        val said = listOf(Triple("say", "Brannock", "anyone up for the Deadmines?"), Triple("party", "Mirelle", "need one more healer for DM"),
            Triple("guild", "Sennah", "grats on 20!"), Triple("say", "Korvash", "on my way"))
        for ((i, s) in said.withIndex()) lines.put(JSONObject().put("i", i + 1).put("ch", s.first).put("who", s.second).put("text", s.third)
            .put("cls", "WARRIOR").put("chan", "").put("idx", 0).put("style", ""))
        chat.update(JSONObject().put("v", "chat37").put("s", 77L).put("n", 4).put("lines", lines)
            .put("can", JSONObject().put("party", true).put("guild", true).put("raid", false).put("chans", JSONArray())))
        // Settings > Top screen: the tiles the one-screen preset sets say so
        val sv = SettingsView(app) { }
        val settings = SettingsRoom(app, sv) { null }
        settings.show(SettingsTabs.Tab.TOP)
        val pages = listOf(roomPage(chat, Room.CHAT), roomPage(settings, Room.SETTINGS) { toWindows(it) }, roomPage(CalendarView(app), Room.CALENDAR))
        val cw = TW / 2; val ch = TH / 2
        val sheet = Bitmap.createBitmap(cw * pages.size, ch, Bitmap.Config.ARGB_8888)
        val sc = Canvas(sheet)
        for ((i, p) in pages.withIndex()) sc.drawBitmap(panelShot(p), null, Rect(i * cw, 0, (i + 1) * cw, ch), Paint(Paint.FILTER_BITMAP_FLAG))
        save(sheet, "npc_14_rooms_sheet.png")
        save(panelShot(pages[1]), "npc_14b_settings_set_by_one_screen.png")
        assertTrue(SingleNpc.setByOne("minimap"))
    }
}
