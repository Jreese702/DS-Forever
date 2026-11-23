package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
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
 * PadMap m188 SHOW WHAT'S ON TOP, the real renders (the user's pick 2026-11-23: "Mark a slot on Home and in the Controller room when
 * an In combat spell or our command sits over a macro"): the Controller room on LT + Y (an In combat entry over the player's macro:
 * WoW Defaults and In combat), RT + Y (our command over a macro), English and Spanish; and Home's bars (Cast) on the LT layer and the
 * bare one with the mark. Runs only when MACRO_SHOTS_DIR is set (CLOCK_ART_ROOT: the art root, laid out like the app's).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LtRtMacroOverShotsTest {
    private val out: File? = System.getenv("MACRO_SHOTS_DIR")?.let { File(it).also { d -> d.mkdirs() } }
    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 1240
    private val h = 960

    @After fun down() { Tr.setChoice(app, "auto") }

    private fun art() {
        System.getenv("CLOCK_ART_ROOT")?.let { root ->
            val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, File(root))
        }
        HomeSettings.init(app)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
    }

    private val book = """{"type":"spells","v":"s9","s":{"lines":[{"k":1,"n":"General","i":136048},{"k":2,"n":"Restoration","i":136052}],
        "items":[{"n":"Healing Wave","t":1,"id":331,"a":331,"sl":1,"ln":2,"i":136052},{"n":"Lightning Bolt","t":1,"id":403,"a":403,"sl":2,"ln":2,"i":136048}],
        "win":{"croom":5,"open":0,"close":1,"bfon":12,"bfoff":13}}}"""

    /** LT + Y: Healing Wave (In combat) over the player's macro "MyHeal"; RT + Y: Target Enemy (ours) over "MyBuff"; bare Up: Earth
     *  Shock over a macro; LT + A: Lightning Bolt over a spell (no mark) */
    private fun padmap(): JSONObject {
        fun empty(set: Int) = JSONArray().apply { for (p in 1..8) put(JSONObject().put("pos", p).put("kind", if (set == 1 && p >= 5) "fixed" else "none").put("s", (set - 1) * 12 + p)) }
        val sets = JSONArray()
        for (set in 1..4) {
            val a = empty(set)
            when (set) {
                1 -> a.put(1, JSONObject().put("pos", 2).put("s", 2).put("kind", "combat").put("ov", true).put("base", "macro").put("um", true)
                    .put("c", JSONObject().put("n", "Earth Shock").put("id", 8042).put("i", 136026)).put("o", JSONObject().put("n", "MyShock").put("i", 132369)))
                2 -> {
                    a.put(5, JSONObject().put("pos", 6).put("s", 18).put("kind", "combat").put("ov", true).put("base", "macro").put("um", true)
                        .put("c", JSONObject().put("n", "Healing Wave").put("id", 331).put("i", 136052)).put("o", JSONObject().put("n", "MyHeal").put("i", 135907)))
                    a.put(7, JSONObject().put("pos", 8).put("s", 20).put("kind", "combat").put("ov", true).put("base", "spell")
                        .put("c", JSONObject().put("n", "Lightning Bolt").put("id", 403).put("i", 136048)).put("o", JSONObject().put("n", "Earth Shock").put("id", 8042).put("i", 136026)))
                }
                3 -> a.put(5, JSONObject().put("pos", 6).put("s", 30).put("kind", "command").put("ov", true).put("um", true)
                    .put("key", "TARGETNEARESTENEMY").put("n", "Target Enemy").put("i", 132212).put("b", "/targetenemy"))
            }
            sets.put(JSONObject().put("set", set).put("slots", a))
        }
        val m = JSONObject().put("page", 1).put("pages", 3).put("sets", sets)
            .put("face", JSONObject().put("cc", true))
        return JSONObject().put("type", "padmap").put("v", "m188").put("m", m)
    }

    private fun ctl(view: String, set: Int, pos: Int): PadMapModel.Ctl {
        val top = PadMapModel.topOf(view)
        val c = JSONObject().put("on", true).put("view", view).put("top", top).put("focus", "buttons").put("pos", pos)
            .put("tcur", PadMapModel.TABS.indexOf(top) + 1).put("set", set).put("shown", set).put("lay", "swap").put("cbpn", 0).put("cbps", 1)
        return PadMapModel.parseCtl(JSONObject().put("type", "padtabctl").put("v", "p56").put("c", c))!!
    }

    private fun room(): ControllerView {
        val v = ControllerView(app, { true })
        v.update(SpellsModel.parse(JSONObject(book))!!)
        v.onPadMap(PadMapModel.parse(padmap())!!)
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        return v
    }

    private fun save(b: Bitmap, name: String) { File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }

    private fun shot(v: View, name: String, ww: Int = w, hh: Int = h) {
        val b = Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Theme.ink)
        v.draw(c); c.drawColor(Theme.ink); v.draw(c)                          // twice: the first frame settles
        save(b, name)
    }

    private fun lang(l: String) {
        if (l == "en") { Tr.setChoice(app, "auto"); return }
        listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
            .firstOrNull { it.canRead() }?.let { Tr.load(l, it.readText()) }
        Tr.setChoice(app, l)
    }

    /** Cast's bars alone, on [lt] / [rt], every slot a spell, the m188 cells marked (as HomeView.onPadMap feeds them) */
    private fun bars(name: String, lt: Boolean, rt: Boolean, rooms: Boolean = false, row: Boolean = false) {
        val cast = CastView(app) { }
        if (row) cast.layout = CastView.Layout.ROW                            // m188 review: Home's band at rest (not the full bars)
        // m188 review: the DS WoW toolbar's room bar on the bare D-pad - those presses open rooms, so no mark over them
        if (rooms) { cast.roomBar = mapOf(1 to "map", 2 to "chat", 3 to "home", 4 to "radial"); cast.roomBarOn = true }
        val icons = listOf(136048, 136026, 135990, 136052, 135813, 136015, 135824, 136075)
        fun layer() = JSONArray().apply { for (p in 1..8) put(JSONObject().put("p", p).put("k", if (p in 5..8) "macro" else "spell").put("id", 400 + p).put("i", icons[p - 1])) }
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1).put("pages", JSONArray().put(true).put(true).put(true))
            .put("L", JSONObject().put("n", layer()).put("lt", layer()).put("rt", layer()).put("lr", layer()))))
        val map = PadMapModel.parse(padmap())!!
        cast.overMacro = PadMapModel.overMacroKeys(map)
        cast.commands = HomeFightFace.commandIcons(map)
        CastView::class.java.getDeclaredMethod("onPad", Boolean::class.java, Boolean::class.java, Boolean::class.java, Boolean::class.java)
            .apply { isAccessible = true }.invoke(cast, lt, rt, false, false)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        val ww = 760; val hh = 420
        cast.measure(View.MeasureSpec.makeMeasureSpec(ww, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hh, View.MeasureSpec.EXACTLY))
        cast.layout(0, 0, ww, hh)
        shot(cast, name, ww, hh)
    }

    @Test fun shots() {
        if (out == null) return
        art()
        for (l in listOf("en", "es", "fr")) {
            lang(l)
            val sfx = if (l == "en") "" else "_$l"
            val v = room()
            v.onPadTabCtl(ctl("normal", 2, 6)); shot(v, "m1_room_defaults_lt_y_fight_over_macro$sfx")
            v.onPadTabCtl(ctl("combat", 2, 6)); shot(v, "m2_room_in_combat_lt_y_over_macro$sfx")
            v.onPadTabCtl(ctl("normal", 3, 6)); shot(v, "m3_room_defaults_rt_y_command_over_macro$sfx")
            v.onPadTabCtl(ctl("normal", 2, 8)); shot(v, "m4_room_defaults_lt_a_over_spell_no_mark$sfx")
        }
        lang("en")
        bars("m5_home_bars_lt_layer", lt = true, rt = false)
        bars("m6_home_bars_bare_layer", lt = false, rt = false)
        bars("m7_home_bars_rt_layer", lt = false, rt = true)
        bars("m8_home_band_bare_layer", lt = false, rt = false, row = true)
        bars("m9_home_band_bare_room_bar_no_mark", lt = false, rt = false, rooms = true, row = true)
        bars("m10_home_band_lt_layer", lt = true, rt = false, row = true)
    }
}
