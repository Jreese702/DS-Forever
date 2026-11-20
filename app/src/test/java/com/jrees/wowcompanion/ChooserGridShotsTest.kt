package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.json.JSONObject
import org.junit.After
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
 * THE CONTROLLER ROOM'S SKILL CHOOSER, DRAWN (the user, 2026-11-20: "when assigning skills in the controller mode I dont like the list
 * for the skills, can we make it better? maybe a grid similar to the AH"): every chooser tab with the controller's cursor in it, EN + ES,
 * drawn by Robolectric's native graphics from self-contained documents (a level-20 Shaman). Runs only when CHOOSER_SHOTS_DIR is set
 * (the PNGs land there); CLOCK_ART_ROOT = an art root laid out like the app's (its icons.pak and ui/kit).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChooserGridShotsTest {
    private val out: File? = System.getenv("CHOOSER_SHOTS_DIR")?.let { File(it).also { d -> d.mkdirs() } }
    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 1240
    private val h = 960
    private var sideWas = CastStripModel.Side.OFF
    private var ranksWas = false

    @Before fun up() {
        System.getenv("CLOCK_ART_ROOT")?.let { root ->
            val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, File(root))
        }
        HomeSettings.init(app); sideWas = HomeSettings.castBarSide; HomeSettings.castBarSide = CastStripModel.Side.BOTH
        ranksWas = HomeSettings.rankBadges; HomeSettings.rankBadges = true      // the "Spell rank badges" setting On (t1c: Off)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
    }
    @After fun down() { HomeSettings.castBarSide = sideWas; HomeSettings.rankBadges = ranksWas; Tr.setChoice(app, "auto") }

    companion object {
        val BOOK = """{"type":"spells","v":"s9","s":{"lines":[{"k":1,"n":"General","i":136048},{"k":2,"n":"Elemental","i":136048},
            {"k":3,"n":"Enhancement","i":136051},{"k":4,"n":"Restoration","i":136052},{"k":5,"n":"Professions","i":133971,"prof":true}],
            "items":[
             {"n":"Lightning Bolt","t":1,"id":403,"a":403,"sl":1,"ln":2,"i":136048,"sub":"Rank 4","pc":2},
             {"n":"Lightning Bolt","t":1,"id":548,"a":548,"sl":24,"ln":2,"i":136048,"sub":"Rank 3","low":true},
             {"n":"Earth Shock","t":1,"id":8042,"a":8042,"sl":2,"ln":2,"i":136026,"sub":"Rank 2"},
             {"n":"Earth Shock","t":1,"id":8044,"a":8044,"sl":25,"ln":2,"i":136026,"sub":"Rank 1","low":true},
             {"n":"Flame Shock","t":1,"id":8050,"a":8050,"sl":3,"ln":2,"i":135813,"sub":"Rank 1"},
             {"n":"Frost Shock","t":1,"id":8056,"a":8056,"sl":4,"ln":2,"i":135849},
             {"n":"Searing Totem","t":1,"id":3599,"a":3599,"sl":5,"ln":2,"i":135825},
             {"n":"Purge","t":1,"id":370,"a":370,"sl":6,"ln":2,"i":136075},
             {"n":"Lightning Shield","t":1,"id":324,"a":324,"sl":7,"ln":3,"i":136051},
             {"n":"Rockbiter Weapon","t":1,"id":8017,"a":8017,"sl":8,"ln":3,"i":136086},
             {"n":"Flametongue Weapon","t":1,"id":8024,"a":8024,"sl":9,"ln":3,"i":135814},
             {"n":"Stoneskin Totem","t":1,"id":8071,"a":8071,"sl":10,"ln":3,"i":136098},
             {"n":"Strength of Earth Totem","t":1,"id":8075,"a":8075,"sl":11,"ln":3,"i":136023},
             {"n":"Earthbind Totem","t":1,"id":2484,"a":2484,"sl":12,"ln":3,"i":136102},
             {"n":"Ghost Wolf","t":1,"id":2645,"a":2645,"sl":13,"ln":3,"i":136095},
             {"n":"Healing Wave","t":1,"id":331,"a":331,"sl":14,"ln":4,"i":136052},
             {"n":"Lesser Healing Wave","t":1,"id":8004,"a":8004,"sl":15,"ln":4,"i":136043},
             {"n":"Healing Stream Totem","t":1,"id":5394,"a":5394,"sl":16,"ln":4,"i":135127},
             {"n":"Ancestral Spirit","t":1,"id":2008,"a":2008,"sl":17,"ln":4,"i":136077},
             {"n":"Cure Poison","t":1,"id":526,"a":526,"sl":18,"ln":4,"i":136067},
             {"n":"Auto Attack","t":1,"id":6603,"a":6603,"sl":19,"ln":1,"i":135641},
             {"n":"Mount","t":1,"id":458,"a":458,"sl":20,"ln":1,"i":132261},
             {"n":"Cooking","t":1,"id":2550,"a":2550,"sl":21,"ln":5,"i":133971,"pf":"Cooking"},
             {"n":"First Aid","t":1,"id":3273,"a":3273,"sl":22,"ln":5,"i":135966,"pf":"First Aid"},
             {"n":"Fishing","t":1,"id":7620,"a":7620,"sl":23,"ln":5,"i":136245,"pf":"Fishing"}],
            "win":{"croom":5,"open":0,"close":1,"bfon":12,"bfoff":13}}}"""

        val MAP = """{"type":"padmap","v":"m177","m":{"page":1,"pages":3,
            "sets":[{"set":1,"slots":[{"pos":1,"s":1,"kind":"spell","id":403,"i":136048,"n":"Lightning Bolt"},{"pos":4,"s":4,"kind":"none"}]}],
            "commands":[{"key":"TARGETNEARESTENEMY","n":"Target Nearest Enemy","i":132212,"made":true,"b":"/targetenemy"},
                {"key":"ASSIST","n":"Assist Target","i":132333,"made":true,"b":"/assist"},
                {"key":"FOLLOW","n":"Follow Target","i":132307,"made":true,"b":"/follow"},
                {"key":"DSMAP","n":"Open Map","i":134269,"made":true,"b":"/click WowCompanionPadMap"},
                {"key":"DSCHAT","n":"Open Chat","i":2056011,"made":true,"b":"/click WowCompanionPadChat"},
                {"key":"DSRADIAL","n":"Spell Radial","i":136048,"made":true,"b":"/click WowCompanionRadial"}],
            "binds":[{"key":"JUMP","n":"Jump","i":135992},{"key":"TOGGLEAUTORUN","n":"Auto Run","i":132307}],
            "mounts":[{"id":6,"n":"Brown Horse","i":132261}],
            "items":[{"id":118,"n":"Minor Healing Potion","i":134829},{"id":6948,"n":"Hearthstone","i":134414}],
            "macs":[{"n":"Bravo","i":134400},{"n":"Totems","i":135825},{"n":"Heal Me","i":136052},{"n":"Pull","i":132212}]}}"""
    }

    private fun room(): ControllerView {
        val v = ControllerView(app, { true })
        val book = SpellsModel.parse(JSONObject(BOOK))!!
        SpellRanks.update(book)
        v.update(book)
        v.onPadMap(PadMapModel.parse(JSONObject(MAP))!!)
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        return v
    }

    private fun ctl(list: String, id: String?, row: Int = 1, tabs: Boolean = false, view: String = "normal", pos: Int = 1, extra: String = "", xo: String? = null): PadMapModel.Ctl {
        val ch = JSONObject().put("list", list).put("row", row).put("tabs", tabs)
        id?.let { ch.put("id", it) }
        val c = JSONObject("""{"on":true,"focus":"buttons","set":1,"shown":1,"pos":$pos,"page":1,"view":"$view","top":"${PadMapModel.topOf(view)}",
            "lay":"swap","tcur":1$extra}""").put("chooser", ch)
        xo?.let { c.put("xo", it) }
        return PadMapModel.parseCtl(JSONObject().put("type", "padtabctl").put("v", "p57").put("c", c))!!
    }

    private fun shot(v: View, name: String) {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b)); v.draw(Canvas(b))                                    // twice: the tab gaps' first frame settles
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun lang(l: String) {
        if (l == "en") { Tr.setChoice(app, "auto"); return }
        listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
            .firstOrNull { it.canRead() }?.let { Tr.load(l, it.readText()) }
        Tr.setChoice(app, l)
    }

    @Test fun shots() {
        if (out == null) return
        for (l in listOf("en", "es")) {
            lang(l)
            val s = if (l == "en") "" else "_es"
            val v = room()
            v.onPadTabCtl(ctl("spells", "8042", 2)); shot(v, "t1_spells$s")
            v.onPadTabCtl(ctl("spells", null, 1, tabs = true)); shot(v, "t1b_spells_on_tabs$s")
            HomeSettings.rankBadges = false
            v.onPadTabCtl(ctl("spells", "403", 1)); shot(v, "t1c_spells_rank_badges_off$s")
            HomeSettings.rankBadges = true
            v.onPadTabCtl(ctl("general", "6603", 1)); shot(v, "t2_general$s")
            v.onPadTabCtl(ctl("professions", "3273", 2)); shot(v, "t3_professions$s")
            v.onPadTabCtl(ctl("extra", "h:cmd", 1)); shot(v, "t4_extra_folded$s")
            v.onPadTabCtl(ctl("extra", "ASSIST", 3, xo = "cmd,mount")); shot(v, "t4b_extra_open$s")
            v.onPadTabCtl(ctl("extra", "DSMAP", 4, xo = "ds")); shot(v, "t5_extra_ds_forever$s")
            v.onPadTabCtl(ctl("macros", "mc:Totems", 2)); shot(v, "t6_macros$s")
            v.onPadTabCtl(ctl("spells", "324", 7, view = "combat", pos = 2)); shot(v, "t7_in_combat_spells$s")
            v.onPadTabCtl(ctl("extra", "DSMAP", 4, view = "castbar", pos = 4, xo = "ds")); shot(v, "t8_castbar_extra$s")
            v.onPadTabCtl(ctl("spells", "clear", 21)); shot(v, "t9_spells_on_clear$s")
        }
    }
}
