package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * SMART Y'S "SIT BY THE FIRE" ROW IN THE LIST (PadMap m190; the user, 2026-11-24: "I want to add a smart y option to sit when the
 * player has the nearby campfire buff"), off-device: the Controller room's Smart Y list as a sub-page with the new row - its name, its
 * picture, the four buttons every row has (the 11-11 ruling: no Or on a kind row), its remove tap, Add offering it back by its own
 * select cell, es / fr.
 *
 * With SMARTY_FIRE_SHOTS_DIR set it renders the list (the row last, the row lit as Y's pick now, Add offering it back, es, fr);
 * THEME_ART_ROOT = an art root laid out like the app's (ui/kit, fonts, icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmartYFireShotsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private var doc: SmartYModel.Doc? = null
    private var now: String? = null
    private val sent = ArrayList<List<Pair<String, Int>>>()

    private fun art(root: String? = System.getenv("THEME_ART_ROOT")) {
        val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, root?.let { File(it) })
        UiKit.root = UiKit.root
        for (nm in listOf("castCache", "castMissing")) when (val o = UiKit::class.java.getDeclaredField(nm).apply { isAccessible = true }.get(UiKit)) {
            is MutableMap<*, *> -> o.clear(); is MutableSet<*> -> o.clear()
        }
    }

    private fun lay(r: View) {
        for (k in 0..2) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            r.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
            r.layout(0, 0, W, H)
            r.draw(Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)))
        }
    }

    /** the list as the add-on sends it after m190 for a 1.0.40 character: its rows kept, Sit by the fire added at the end */
    private fun fireDoc(rows: List<SmartYModel.Row> = listOf(SmartYModel.Row(SmartYModel.FOOD), SmartYModel.Row(SmartYModel.DRINK),
        SmartYModel.Row(SmartYModel.BUFF, listOf(1)), SmartYModel.Row(SmartYModel.BUFF, listOf(2, 3)), SmartYModel.Row(SmartYModel.FIRE))) =
        SmartYModel.Doc(
            rows = rows,
            list = listOf(SmartYModel.Buff(1, "Lightning Shield", 136051, false), SmartYModel.Buff(2, "Rockbiter Weapon", 136086, true),
                SmartYModel.Buff(3, "Flametongue Weapon", 135814, true)),
            at = null, row = "gamemenu", pad = true, sel0 = 8,
            ops = mapOf("remove" to 24, "up" to 25, "down" to 26, "add" to 27, "addor" to 28, "out" to 29),
            on = true, manners = false,
            kinds = mapOf(SmartYModel.FOOD to 8, SmartYModel.DRINK to 9, SmartYModel.MANNERS to 10, SmartYModel.FIRE to 11),
            icons = mapOf(SmartYModel.FOOD to 133964, SmartYModel.DRINK to 132794, SmartYModel.FIRE to 135805))

    private fun room(): SettingsRoom {
        Settings.init(app); SettingsTabs.init(app); GameUi.init(app); HomeSettings.init(app); CastSettings.init(app)
        art()
        // in a window (an Activity's content): the list takes a tap only while it is shown on screen (SettingsView's isShown rule)
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val v = SettingsView(act) { }
        v.smartDoc = { doc }
        v.smartNow = { now }
        v.smartTaps = { t -> sent.add(t); true }
        return SettingsRoom(act, v) { null }.also { act.setContentView(it); it.show(SettingsTabs.Tab.CONTROLLER); lay(it) }
    }

    private fun openList(r: SettingsRoom) {
        r.ctlTiles.first { "smarty" in it.tileIds() }.press("smarty", ControllerTiles.LIST, 0); lay(r)
        assertEquals(View.VISIBLE, r.smartYPage.visibility)
    }

    /** the list's rows as drawn: each stop's words, top to bottom */
    private fun rows(r: SettingsRoom): List<String> {
        val l = r.smartYPage.list
        return l.focusTargets().mapNotNull { b -> l.rowAt(24f, b.centerY()) }.distinct()
    }

    private fun tap(v: View, x: Float, y: Float) {
        val d = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0); v.dispatchTouchEvent(d); d.recycle()
        val u = MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, x, y, 0); v.dispatchTouchEvent(u); u.recycle()
    }

    /** the row named [label]'s box in the list's own coordinates */
    private fun rowBox(r: SettingsRoom, label: String) =
        r.smartYPage.list.focusTargets().first { r.smartYPage.list.rowAt(24f, it.centerY()) == label }

    @After fun forget() {
        GameUi.character = null
        Tr.setChoice(app, "auto")
    }

    @Test fun theFireRowInTheList() {
        doc = fireDoc()
        val r = room(); openList(r)
        val shown = rows(r)
        assertEquals("Sit by the fire", shown.last { it != "Add a buff" })
        assertTrue(shown.indexOf("Sit by the fire") > shown.indexOf("Water"))
        // its buttons: remove (the X, the row's rightmost square) sends the row's pick then Remove; no Or on it; Down greyed (the last
        // row: drawn, no stop) - so two stops: X and Up
        val box = rowBox(r, "Sit by the fire")
        val list = r.smartYPage.list
        val onRow = list.focusTargets().filter { it.centerY() > box.top && it.centerY() < box.bottom && it.width() < 100f }
        assertEquals(2, onRow.size)
        val x = onRow.maxByOrNull { it.right }!!
        tap(list, x.centerX(), x.centerY()); lay(r)
        assertEquals(listOf("gamemenu" to 12, "gamemenu" to 24), sent.last())  // the 5th row shown: sel0 + 4, then Remove
    }

    @Test fun addOffersItBack() {
        doc = fireDoc(listOf(SmartYModel.Row(SmartYModel.FOOD), SmartYModel.Row(SmartYModel.DRINK)))
        val r = room(); openList(r)
        val add = rowBox(r, "Add a buff")
        tap(r.smartYPage.list, add.centerX(), add.centerY()); lay(r)
        assertTrue(rows(r).contains("Sit by the fire"))
        val b = rowBox(r, "Sit by the fire")
        tap(r.smartYPage.list, b.centerX(), b.centerY()); lay(r)
        assertEquals(listOf("gamemenu" to 27, "gamemenu" to 11), sent.last())  // Add, then the fire's own select cell
    }

    // ---------------------------------------------------------------- the renders (SMARTY_FIRE_SHOTS_DIR)

    @Test fun shots() {
        val out = System.getenv("SMARTY_FIRE_SHOTS_DIR")?.let { File(it) } ?: return
        out.mkdirs()
        doc = fireDoc()
        var r = room()
        fun shot(name: String) {
            lay(r); Thread.sleep(250); lay(r)
            val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(Theme.ink); r.draw(c)
            File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        openList(r); shot("smarty_fire_1_list")
        now = "fire"; r.smartYPage.list.invalidate(); shot("smarty_fire_2_y_sits_now")
        now = null
        doc = fireDoc(listOf(SmartYModel.Row(SmartYModel.FOOD), SmartYModel.Row(SmartYModel.DRINK), SmartYModel.Row(SmartYModel.BUFF, listOf(1))))
        r = room(); openList(r)
        val add = rowBox(r, "Add a buff")
        tap(r.smartYPage.list, add.centerX(), add.centerY()); shot("smarty_fire_3_add_offers_it")
        doc = fireDoc()
        for (l in listOf("es", "fr")) {
            listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
                .firstOrNull { it.canRead() }?.let { Tr.load(l, it.readText()) }
            Tr.setChoice(app, l)
            r = room(); openList(r); shot("smarty_fire_4_$l")
        }
    }
}
