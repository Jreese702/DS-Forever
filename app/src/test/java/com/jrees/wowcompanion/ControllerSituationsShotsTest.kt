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
 * OPTION C, SITUATIONS FIRST: the real renders of every page and state (the user, 2026-11-20: "Render REAL screenshots of every page
 * and state (EN + ES)"), drawn by Robolectric's native graphics from the add-on's own documents (CTL_DOCS: tools/test_party.py with
 * CTL_DUMP) and an art root laid out like the app's (CLOCK_ART_ROOT). Runs only when CTLC_SHOTS_DIR is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControllerSituationsShotsTest {
    private val out: File? = System.getenv("CTLC_SHOTS_DIR")?.let { File(it).also { d -> d.mkdirs() } }
    private val docs: File? = System.getenv("CTL_DOCS")?.let { File(it) }?.takeIf { it.isDirectory }
    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 1240
    private val h = 960

    private var sideWas = CastStripModel.Side.OFF
    private var combatWas = CastStripModel.Combat.HIDE
    private var partyWas = true

    @Before fun up() {
        System.getenv("CLOCK_ART_ROOT")?.let { root ->
            val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, File(root))
        }
        HomeSettings.init(app); sideWas = HomeSettings.castBarSide; combatWas = HomeSettings.castBarCombat; partyWas = HomeSettings.castBarPartyShow
        HomeSettings.castBarSide = CastStripModel.Side.BOTH; HomeSettings.castBarCombat = CastStripModel.Combat.STAY; HomeSettings.castBarPartyShow = true
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
    }
    @After fun down() {
        HomeSettings.castBarSide = sideWas; HomeSettings.castBarCombat = combatWas; HomeSettings.castBarPartyShow = partyWas
        Tr.setChoice(app, "auto")
    }

    companion object {
        /** a level-20 Shaman's bar: every kind on Out of combat, two In combat overrides, one Party mode slot */
        val BAR = """{"type":"castbar","v":"cb15","n":8,"any":1,"mac":1,"fl":1,"show":1,"pl":1,"ps":1,"pick":8,"pp":[39,19],"e":[
            {"s":1,"id":403,"i":136048,"c":0},{"s":2,"id":331,"i":136052,"c":1},
            {"s":3,"k":"command","key":"TARGETNEARESTENEMY","n":"Target Nearest Enemy","i":132212,"c":2},
            {"s":4,"k":"command","key":"DSMAP","n":"Open Map","i":134269,"c":3},
            {"s":5,"k":"item","id":118,"n":"Minor Healing Potion","i":134829,"ct":7,"cons":1,"c":4},
            {"s":6,"k":"item","id":6948,"n":"Hearthstone","i":134414,"ct":1,"c":5},
            {"s":7,"k":"mount","id":6,"n":"Brown Horse","i":132261,"sid":458,"c":6}],
            "f":[{"s":1,"id":8042,"i":136026,"c":9},{"s":2,"id":324,"i":136051,"c":10}],
            "p":[{"s":1,"id":331,"i":136052,"c":17}]}"""
        val BOOK = """{"type":"spells","v":"s9","s":{"lines":[{"k":1,"n":"General","i":136048},{"k":2,"n":"Elemental","i":136048}],
            "items":[{"n":"Lightning Bolt","t":1,"id":403,"a":403,"sl":1,"ln":2,"i":136048},{"n":"Healing Wave","t":1,"id":331,"a":331,"sl":2,"ln":2,"i":136052},
                     {"n":"Earth Shock","t":1,"id":8042,"a":8042,"sl":3,"ln":2,"i":136026},{"n":"Rockbiter Weapon","t":1,"id":8017,"a":8017,"sl":4,"ln":2,"i":136086},
                     {"n":"Lightning Shield","t":1,"id":324,"a":324,"sl":5,"ln":2,"i":136051}],
            "win":{"croom":5,"open":0,"close":1,"bfon":12,"bfoff":13}}}"""
    }

    private fun fixture(name: String): String = listOf(File("../dsforever/src/test/resources/party-fixtures/$name.json"),
        File("dsforever/src/test/resources/party-fixtures/$name.json")).first { it.canRead() }.readText()

    private fun partyLive(box: Int = 2) {
        val cfg = JSONObject(fixture("partycfg_shaman_default"))
        cfg.getJSONObject("c").optJSONObject("slots")?.let { s ->
            s.optJSONObject("a")?.put("i", 136052); s.optJSONObject("x")?.put("i", 136043)
        }
        PartyModel.live.onCfg(PartyModel.parseCfg(cfg)!!, true)
        val p = JSONObject(fixture("party_three_on"))
        p.getJSONObject("p").put("box", box)
        PartyModel.live.onParty(PartyModel.parseParty(p)!!, true, 0L)
    }

    private fun map(name: String): PadMapModel.Map =
        PadMapModel.parse(JSONObject(File(docs!!, "$name.json").readText()))!!

    private fun ctl(view: String, focus: String, pos: Int, extra: (JSONObject) -> Unit = {}): PadMapModel.Ctl {
        val base = docs?.let { File(it, "ctl_dleft.json") }?.takeIf { it.canRead() }?.let { JSONObject(it.readText()).getJSONObject("c") } ?: JSONObject()
        val c = JSONObject(base.toString())
        val top = PadMapModel.topOf(view)
        c.put("on", true).put("view", view).put("top", top).put("focus", focus).put("pos", pos).put("tcur", PadMapModel.TABS.indexOf(top) + 1)
            .put("set", 1).put("shown", 1).put("lay", "swap").put("cbpn", 0).put("cbps", 1).remove("note")
        c.remove("chooser"); c.remove("box"); c.remove("carry")
        extra(c)
        return PadMapModel.parseCtl(JSONObject().put("type", "padtabctl").put("v", "p56").put("c", c))!!
    }

    private fun room(mapName: String): ControllerView {
        CastStripModel.Live.set(CastStripModel.parse(JSONObject(BAR))!!)
        val v = ControllerView(app, { true })
        v.update(SpellsModel.parse(JSONObject(BOOK))!!)
        v.onPadMap(map(mapName))
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        return v
    }

    private fun shot(v: View, name: String, hh: Int = h) {
        val b = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888)
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
        if (out == null || docs == null) return
        partyLive()
        for (l in listOf("en", "es")) {
            lang(l)
            val sfx = if (l == "en") "" else "_es"
            var v = room("padmap_on")
            v.onPadTabCtl(ctl("castbar", "buttons", 1)); shot(v, "c1_out_of_combat_bar$sfx")
            v.onPadTabCtl(ctl("normal", "buttons", 1)); shot(v, "c1b_out_of_combat_controller$sfx")
            v.onPadTabCtl(ctl("normal", "tabs", 1)); shot(v, "c1c_out_of_combat_tabs$sfx")
            v.onPadTabCtl(ctl("normal", "switches", 1)); shot(v, "c1d_out_of_combat_chip$sfx")
            v = room("padmap_combat")
            v.onPadTabCtl(ctl("cbfight", "buttons", 3)); shot(v, "c2_in_combat_bar$sfx")
            v.onPadTabCtl(ctl("combat", "buttons", 8)); shot(v, "c2b_in_combat_controller$sfx")
            v.onPadTabCtl(ctl("combat", "switches", 1) { it.put("sw", 3) }); shot(v, "c2c_in_combat_chips$sfx")
            v = room("padmap_on")
            v.onPadTabCtl(ctl("pbar", "buttons", 1) { it.put("pbar", JSONObject().put("ppos", 8).put("pset", 1)) }); shot(v, "c3_party_mode_bar$sfx")
            v.onPadTabCtl(ctl("party", "pdiam", 1) { it.put("ppos", 8).put("pfocus", "diam") }); shot(v, "c3b_party_mode_controller$sfx")
            v.onPadTabCtl(ctl("party", "switches", 1) { it.put("pfocus", "sw") }); shot(v, "c3c_party_mode_chip$sfx")
            // the locks
            HomeSettings.castBarCombat = CastStripModel.Combat.HIDE
            v = room("padmap_combat")
            v.onPadTabCtl(ctl("cbfight", "switches", 3) { it.put("sw", 3) }); shot(v, "c4a_show_in_fights_off$sfx")
            v.onPadTabCtl(ctl("cbfight", "buttons", 2)); shot(v, "c4a2_show_in_fights_off_on_bar$sfx")
            HomeSettings.castBarCombat = CastStripModel.Combat.STAY
            HomeSettings.castBarSide = CastStripModel.Side.OFF
            v = room("padmap_on")
            v.onPadTabCtl(ctl("castbar", "buttons", 1)); shot(v, "c4b_bar_off$sfx")
            HomeSettings.castBarSide = CastStripModel.Side.BOTH
            HomeSettings.castBarPartyShow = false
            v = room("padmap_on")
            v.onPadTabCtl(ctl("pbar", "buttons", 1) { it.put("cbps", 0).put("pbar", JSONObject().put("ppos", 8).put("pset", 1)) }); shot(v, "c4c_party_bar_off$sfx")
            HomeSettings.castBarPartyShow = true
            v = room("padmap_on")
            v.onPadTabCtl(ctl("radial", "tabs", 1)); shot(v, "c7_spell_radial$sfx")
            // THE BIND BOX (Spellbook / Bags / Macros): the three situations, both devices
            boxShot("c5_bindbox_spellbook_party$sfx", 331, ""","bpm":1""")
            boxShot("c5b_bindbox_spellbook_party_bar$sfx", 331, ""","bcb":2,"bcp":1""")
            boxShot("c5c_bindbox_spellbook_out_of_combat$sfx", 8017, "")
            boxShot("c5d_bindbox_spellbook_in_combat_bar$sfx", 8017, ""","bcb":3,"bcf":1""")
            boxShot("c6_bindbox_bags$sfx", 118, ""","bitem":{"id":118,"n":"Minor Healing Potion","i":134829},"bcb":8""")
            boxShot("c6b_bindbox_macros$sfx", 3, ""","bitem":{"id":3,"n":"Bravo","i":134400,"mac":true},"bcb":4""")
            HomeSettings.castBarPartyShow = false
            boxShot("c6c_bindbox_party_bar_off$sfx", 331, ""","bcb":2,"bcp":1""")
            HomeSettings.castBarPartyShow = true
            HomeSettings.castBarSide = CastStripModel.Side.OFF
            boxShot("c6d_bindbox_bar_off$sfx", 403, ""","bcb":1""")
            HomeSettings.castBarSide = CastStripModel.Side.BOTH
        }
    }

    private fun boxShot(name: String, id: Int, extra: String) {
        CastStripModel.Live.set(CastStripModel.parse(JSONObject(BAR))!!)
        val o = SpellsOverlay(app, otherReader = { true })
        o.onBook(SpellsModel.parse(JSONObject(BOOK))!!)
        docs?.let { o.onPadMap(map("padmap_on")) }
        o.onCtl(SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b71","ctl":{"on":true,"mode":"bind","bind":$id$extra}}"""))!!)
        val hh = 1080 - Strip.HEIGHT
        o.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hh, View.MeasureSpec.EXACTLY))
        o.layout(0, 0, w, hh)
        val b = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Theme.ink); o.draw(c)
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
