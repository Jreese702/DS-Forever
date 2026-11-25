package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * HOLD TO CAST, THE REAL RENDERS (PadMap m190; the user's picks 2026-11-24: "A: corner badge", "At the hold time, Settings: 0.3/0.4/0.55 s",
 * "Every button incl. B"): the Controller room's Hold row and hint line (WoW Defaults, In combat, a base face, unavailable, the hold
 * chooser; es / fr), its left page's hold badges; Home's band and diamonds with the badge and its own sweep (both ready, the tap
 * cooling, the hold cooling), the gold arc while a press is held back (the gate's own state) and the flash when the hold fires; the
 * Bind box's foot line; Settings' Hold time picture. Pixel checks prove the badge, the arc and the flash are drawn; with HOLD_SHOTS_DIR
 * set the renders are saved there (CLOCK_ART_ROOT: the art root, laid out like the app's).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HoldCastShotsTest {
    private val out: File? = System.getenv("HOLD_SHOTS_DIR")?.let { File(it).also { d -> d.mkdirs() } }
    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 1240
    private val h = 960

    @Before fun up() {
        System.getenv("CLOCK_ART_ROOT")?.let { root ->
            val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, File(root))
        }
        HomeSettings.init(app); CastSettings.init(app)
        HoldCast.Store.init(app); HoldCast.Store.result = "seen"
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
    }
    @After fun down() {
        Tr.setChoice(app, "auto")
        HoldCast.Store.result = null
        HoldCast.gate.reset(); HoldCast.gate.configure(false, emptySet(), SystemClock.uptimeMillis())
    }

    private val book = """{"type":"spells","v":"s9","s":{"lines":[{"k":1,"n":"General","i":136048},{"k":2,"n":"Restoration","i":136052}],
        "items":[{"n":"Healing Wave","t":1,"id":331,"a":331,"sl":1,"ln":2,"i":136052},{"n":"Lightning Bolt","t":1,"id":403,"a":403,"sl":2,"ln":2,"i":136048},
        {"n":"Chain Heal","t":1,"id":1064,"a":1064,"sl":3,"ln":2,"i":136042},{"n":"Chain Lightning","t":1,"id":421,"a":421,"sl":4,"ln":2,"i":136015}],
        "win":{"croom":5,"open":0,"close":1,"bfon":12,"bfoff":13}}}"""

    /** LT + Y: Healing Wave (In combat) over Healing Wave, hold Chain Heal; LT + A: Lightning Bolt, hold Chain Lightning; the bare A
     *  (the game's Jump) hold Lightning Shield; bare D-pad Up: Earth Shock, hold Frost Shock */
    private fun padmap(holdOn: Boolean = true): JSONObject {
        fun h(n: String, id: Int, i: Int) = JSONObject().put("n", n).put("id", id).put("i", i)
        val sets = JSONArray()
        for (set in 1..4) {
            val a = JSONArray()
            for (p in 1..8) a.put(JSONObject().put("pos", p).put("kind", if (set == 1 && p >= 5) "fixed" else "none").put("s", (set - 1) * 12 + p))
            when (set) {
                1 -> {
                    a.put(1, JSONObject().put("pos", 2).put("s", 2).put("kind", "spell").put("n", "Earth Shock").put("id", 8042).put("i", 136026)
                        .put("h", h("Frost Shock", 8056, 135849)))
                    a.put(7, JSONObject().put("pos", 8).put("kind", "fixed").put("n", "Jump").put("h", h("Lightning Shield", 324, 136051)))
                }
                2 -> {
                    a.put(5, JSONObject().put("pos", 6).put("s", 18).put("kind", "combat").put("ov", true).put("base", "spell")
                        .put("c", h("Healing Wave", 331, 136052)).put("o", h("Lesser Healing Wave", 8004, 136043)).put("h", h("Chain Heal", 1064, 136042))
                        .put("hc", h("Lightning Shield", 324, 136051)))          // PER TAB: In combat's own hold
                    a.put(7, JSONObject().put("pos", 8).put("s", 20).put("kind", "spell").put("n", "Lightning Bolt").put("id", 403).put("i", 136048)
                        .put("h", h("Chain Lightning", 421, 136015)))
                }
            }
            sets.put(JSONObject().put("set", set).put("slots", a))
        }
        val m = JSONObject().put("page", 1).put("pages", 3).put("sets", sets).put("face", JSONObject().put("cc", true))
            .put("hold", JSONObject().put("on", holdOn))
        return JSONObject().put("type", "padmap").put("v", "m190").put("m", m)
    }

    private fun ctl(view: String, set: Int, pos: Int, chooser: JSONObject? = null): PadMapModel.Ctl {
        val top = PadMapModel.topOf(view)
        val c = JSONObject().put("on", true).put("view", view).put("top", top).put("focus", "buttons").put("pos", pos)
            .put("tcur", PadMapModel.TABS.indexOf(top) + 1).put("set", set).put("shown", set).put("lay", "swap").put("cbpn", 0).put("cbps", 1)
        if (chooser != null) c.put("chooser", chooser)
        return PadMapModel.parseCtl(JSONObject().put("type", "padtabctl").put("v", "p59").put("c", c))!!
    }

    private fun room(holdOn: Boolean = true): ControllerView {
        val v = ControllerView(app, { true })
        v.update(SpellsModel.parse(JSONObject(book))!!)
        v.onPadMap(PadMapModel.parse(padmap(holdOn))!!)
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        return v
    }

    private fun save(b: Bitmap, name: String) { out?.let { File(it, "$name.png").outputStream().use { o -> b.compress(Bitmap.CompressFormat.PNG, 100, o) } } }

    private fun shot(v: View, name: String, ww: Int = w, hh: Int = h): Bitmap {
        val b = Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Theme.ink)
        v.draw(c); c.drawColor(Theme.ink); v.draw(c)                          // twice: the first frame settles
        save(b, name)
        return b
    }

    private fun lang(l: String) {
        if (l == "en") { Tr.setChoice(app, "auto"); return }
        listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
            .firstOrNull { it.canRead() }?.let { Tr.load(l, it.readText()) }
        Tr.setChoice(app, l)
    }

    /** how many pixels in [r] are gold-ish (the hold's arc / the lit badge ring / the fired ring) */
    private fun gold(b: Bitmap, r: RectF): Int {
        var n = 0
        for (y in r.top.toInt().coerceAtLeast(0) until r.bottom.toInt().coerceAtMost(b.height))
            for (x in r.left.toInt().coerceAtLeast(0) until r.right.toInt().coerceAtMost(b.width)) {
                val c = b.getPixel(x, y)
                if (Color.red(c) > 220 && Color.green(c) > 180 && Color.blue(c) in 60..190) n++
            }
        return n
    }
    private fun differs(a: Bitmap, b: Bitmap): Boolean {
        for (y in 0 until a.height step 2) for (x in 0 until a.width step 2) if (a.getPixel(x, y) != b.getPixel(x, y)) return true
        return false
    }

    @Test fun theControllerRoom() {
        for (l in listOf("en", "es", "fr")) {
            lang(l)
            val sfx = if (l == "en") "" else "_$l"
            val v = room()
            v.onPadTabCtl(ctl("normal", 2, 6)); shot(v, "r1_room_defaults_lt_y_hold_row$sfx")
            if (l != "en") continue
            v.onPadTabCtl(ctl("combat", 2, 6)); shot(v, "r2_room_in_combat_lt_y_hold_row")
            v.onPadTabCtl(ctl("normal", 1, 8)); shot(v, "r3_room_base_a_hold_row")
            v.onPadTabCtl(ctl("normal", 2, 8)); shot(v, "r4_room_lt_a_hold_row")
            v.onPadTabCtl(ctl("normal", 1, 6)); shot(v, "r5_room_base_y_no_hold_bind_hold")
            v.onPadTabCtl(ctl("normal", 2, 7)); shot(v, "r10_room_empty_button_no_hold")
            v.onPadTabCtl(ctl("normal", 2, 6, JSONObject().put("list", "spells").put("row", 3).put("id", "1064").put("hold", true)))
            shot(v, "r6_room_hold_chooser")
        }
        lang("en")
        val off = room(holdOn = false)
        off.onPadTabCtl(ctl("normal", 2, 6)); val b1 = shot(off, "r7_room_unavailable")
        HoldCast.Store.result = "not seen"
        val off2 = room(holdOn = true)
        off2.onPadTabCtl(ctl("normal", 2, 6)); val b2 = shot(off2, "r8_room_unavailable_app_probe")
        HoldCast.Store.result = "seen"
        val on = room(); on.onPadTabCtl(ctl("normal", 2, 6)); val b3 = shot(on, "r9_room_on_again")
        assertTrue("the Hold row and hint differ from the unavailable line", differs(b1, b3) && differs(b2, b3))
    }

    /** Home's bars (Cast) with the hold badges; [prog] / [fire]: the gate's own state for the bare A (a held press / the hold firing) */
    private fun bars(name: String, lt: Boolean, layout: CastView.Layout?, tapCd: Boolean = false, holdCd: Boolean = false,
                     prog: Long = -1L, fire: Boolean = false): Bitmap {
        val cast = CastView(app) { }
        if (layout != null) cast.layout = layout
        // 1:1 with the Thor's bottom screen: the band 1240 wide (its 112-unit row at rest, 215 px with its room), the diamonds 354 tall
        val ww = 1240; val hh = if (layout == CastView.Layout.DIAMONDS) 354 else 215
        cast.measure(View.MeasureSpec.makeMeasureSpec(ww, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hh, View.MeasureSpec.EXACTLY))
        cast.layout(0, 0, ww, hh)
        val icons = listOf(136048, 136026, 135990, 136052, 135813, 136015, 135824, 136075)
        fun layer() = JSONArray().apply { for (p in 1..8) put(JSONObject().put("p", p).put("k", "spell").put("id", 400 + p).put("i", icons[p - 1])) }
        val hud = JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1).put("pages", JSONArray().put(true).put(true).put(true))
            .put("L", JSONObject().put("n", layer()).put("lt", layer()).put("rt", layer()).put("lr", layer())))
        cast.update(hud)
        // the icons decode off the UI thread (ART_EXEC) and come back through the main looper: let them land, then lay the hud again
        for (i in 0 until 20) { Thread.sleep(25); org.robolectric.shadows.ShadowLooper.idleMainLooper() }
        cast.update(hud)
        val map = PadMapModel.parse(padmap())!!
        cast.holdRefs = PadMapModel.holdRefs(map)
        val now = SystemClock.elapsedRealtime()
        if (holdCd) cast.holdCooldowns(mapOf(HoldGate.key(if (lt) 1 else 0, if (lt) 8 else 8) to PadMapModel.FaceCd(10.0, 6.5, false, false),
            HoldGate.key(0, 2) to PadMapModel.FaceCd(20.0, 15.0, false, false)), now)
        if (tapCd) cast.fightCooldown(8, now - 2000L, 8000L)
        CastView::class.java.getDeclaredMethod("onPad", Boolean::class.java, Boolean::class.java, Boolean::class.java, Boolean::class.java)
            .apply { isAccessible = true }.invoke(cast, lt, false, false, false)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        // the gate: a press of A held back now (its arc), or fired at the hold time (its flash)
        val g = HoldCast.gate
        g.update(0, 0f, 0f, SystemClock.uptimeMillis())                    // the last shot's press let go
        g.reset(); g.configure(true, setOf(HoldGate.key(if (lt) 1 else 0, 8)), SystemClock.uptimeMillis())
        if (prog >= 0 || fire) {
            val t0 = SystemClock.uptimeMillis()
            g.update(1 shl HoldGate.A, if (lt) 1f else 0f, 0f, t0)
            val at = if (fire) g.holdMs else prog
            ShadowSystemClock.advanceBy(Duration.ofMillis(at))
            g.update(1 shl HoldGate.A, if (lt) 1f else 0f, 0f, SystemClock.uptimeMillis())
            if (fire) ShadowSystemClock.advanceBy(Duration.ofMillis(40))
        }
        val b = Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Theme.ink); cast.draw(c)
        save(b, name)
        g.update(0, 0f, 0f, SystemClock.uptimeMillis() + 1000L)
        g.reset(); g.configure(false, emptySet(), SystemClock.uptimeMillis())
        return b
    }

    @Test fun homesBandAndDiamonds() {
        lang("en")
        for ((tag, lay) in listOf("band" to CastView.Layout.ROW, "diamonds" to CastView.Layout.DIAMONDS)) {
            val ready = bars("h1_${tag}_both_ready", lt = false, layout = lay)
            bars("h2_${tag}_tap_cooling", lt = false, layout = lay, tapCd = true)
            val hc = bars("h3_${tag}_hold_cooling", lt = false, layout = lay, holdCd = true)
            val held = bars("h4_${tag}_held_200ms_gold_arc", lt = false, layout = lay, prog = 200L)
            val fired = bars("h5_${tag}_hold_fires_flash", lt = false, layout = lay, fire = true)
            bars("h6_${tag}_lt_layer_badges", lt = true, layout = lay, holdCd = true)
            assertTrue("$tag: the hold's own sweep changes the badge", differs(ready, hc))
            val all = RectF(0f, 0f, ready.width.toFloat(), ready.height.toFloat())
            assertTrue("$tag: the held press's gold arc", gold(held, all) > gold(ready, all) + 40)
            assertTrue("$tag: the hold firing's bright ring", gold(fired, all) > gold(ready, all) + 40)
        }
        // nothing held back: exactly the ready picture (no arc, no flash)
        val ready = bars("h7_band_ready_again", lt = false, layout = CastView.Layout.ROW)
        val idle = bars("h7_band_nothing_held_no_arc", lt = false, layout = CastView.Layout.ROW)
        assertFalse("nothing held: no arc", differs(ready, idle))
    }

    @Test fun theBindBoxAndSettings() {
        lang("en")
        val o = SpellsOverlay(app, otherReader = { true })
        o.onBook(SpellsModel.parse(JSONObject(ControllerSituationsShotsTest.BOOK))!!)
        o.onPadMap(PadMapModel.parse(padmap())!!)
        o.onCtl(SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b78","ctl":{"on":true,"mode":"bind","bind":8017}}"""))!!)
        val hh = 1080 - Strip.HEIGHT
        o.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hh, View.MeasureSpec.EXACTLY))
        o.layout(0, 0, w, hh)
        val withHint = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888).also { Canvas(it).apply { drawColor(Theme.ink); o.draw(this) } }
        save(withHint, "b1_bindbox_hold_a_button_place_as_hold")
        HoldCast.Store.result = "not seen"
        val without = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888).also { Canvas(it).apply { drawColor(Theme.ink); o.draw(this) } }
        save(without, "b2_bindbox_unavailable_no_hold_line")
        HoldCast.Store.result = "seen"
        assertTrue("the foot's hold line shows only while hold to cast works", differs(withHint, without))
        // Settings > Controller: the Hold time picture, each choice
        for (t in CastSettings.HoldTime.values()) {
            CastSettings.holdTime = t
            val b = Bitmap.createBitmap(432, 216, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(Theme.ink)
            ControllerPictures.draw(c, app, ControllerPictures.HOLD_TIME, RectF(0f, 0f, 432f, 216f), ControllerPictures.Look(t = 1.0f))
            save(b, "s1_settings_hold_time_${t.name.lowercase()}")
        }
        CastSettings.holdTime = CastSettings.HoldTime.NORMAL
    }
}
