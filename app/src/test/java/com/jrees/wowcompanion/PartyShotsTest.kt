package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * PARTY MODE'S OFF-DEVICE RENDER CHECK (no emulator, no device): the new views drawn by Robolectric's native graphics at their real
 * places on a 1240 x 1080 page, saved as PNGs to compare with UI Art's pictures (wow-companion-design/party_mode). Only the Party views
 * are drawn (Home's map, chat and strip are not), on the page's ink.
 *
 * Runs only when PARTY_SHOTS_DIR is set (the PNGs' folder); PARTY_ART_ROOT = an art root laid out like the app's (ui/kit, fonts,
 * icons.pak), e.g. a release export's runtime folder. Without it the views draw their fallbacks (flat fills, the system font).
 *   PARTY_SHOTS_DIR=E:/wow-build/party-app/shots PARTY_ART_ROOT=<runtime> gradlew :app:testModernDebugUnitTest --tests "*PartyShotsTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PartyShotsTest {
    private val out: File? = System.getenv("PARTY_SHOTS_DIR")?.let { File(it) }

    private fun artRoot() {
        val root = System.getenv("PARTY_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun member(i: Int, n: String, c: String, role: String, pt: String, hp: Int, dead: Boolean = false, off: Boolean = false) =
        JSONObject().put("u", if (i == 0) "player" else "party$i").put("i", i).put("n", n).put("c", c).put("role", role)
            .put("pt", pt).put("lv", 20 + i).put("dead", dead).put("off", off).put("rng", if (i == 4) false else JSONObject.NULL)   // m129: no hp
            .put("col", when (c) { "SHAMAN" -> "ff0070dd"; "WARRIOR" -> "ffc69b6d"; "MAGE" -> "ff3fc7eb"; "ROGUE" -> "fffff468"
                "PRIEST" -> "ffffffff"; else -> "ffffd100" })

    private val roster = listOf(
        member(0, "Korvash Emberhoof", "SHAMAN", "HEALER", "MANA", 100),
        member(1, "Brannoc Ironhelm", "WARRIOR", "TANK", "RAGE", 72),
        member(2, "Seraphine Duskwhisperer", "MAGE", "DAMAGER", "MANA", 91),
        member(3, "Tamsin Quickfoot", "ROGUE", "DAMAGER", "ENERGY", 0, dead = true),
        member(4, "Odwin Thornbrook", "PRIEST", "HEALER", "MANA", 45),
    )

    /** m134: the pet's entry (right after you): no class, its portrait icon (a hunter wolf's) */
    private val petEntry = JSONObject().put("u", "pet").put("i", 5).put("n", "Grimfang").put("role", "NONE").put("pt", "FOCUS")
        .put("lv", 20).put("dead", false).put("off", false).put("ic", 132266)

    /** [n] people (you + party), plus [pet] (your pet as the second entry) */
    private fun party(n: Int, on: Boolean, box: Int, deadOn: Boolean = n == 5, pet: Boolean = false): JSONObject {
        val m = org.json.JSONArray()
        val order = if (n == 3) listOf(0, 1, 3) else (0 until n).toList()
        for (k in order) {
            val o = JSONObject(roster[k].toString())
            if (k == 3 && !deadOn) o.put("dead", false)
            m.put(o)
            if (k == 0 && pet) m.put(JSONObject(petEntry.toString()))
        }
        return JSONObject().put("type", "party").put("v", "m77").put("rs", 1)
            .put("p", JSONObject().put("on", on).put("box", box).put("fight", false).put("grp", n > 1).put("sel", "bound").put("m", m))
    }

    private fun page(): Pair<Bitmap, Canvas> {
        val b = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Theme.ink)
        return b to c
    }

    private fun draw(c: Canvas, v: View, l: Float, t: Float, r: Float, b: Float) {
        v.measure(View.MeasureSpec.makeMeasureSpec((r - l).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((b - t).toInt(), View.MeasureSpec.EXACTLY))
        v.layout(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        c.save(); c.translate(l, t); v.draw(c); c.restore()
    }

    private fun save(b: Bitmap, name: String) {
        val d = out ?: return
        d.mkdirs()
        File(d, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** the module as Home places it (HomeView.withParty): its well's top at [top]; m134: the view from the screen's left edge (the
     *  Select tab's place) to x 615, the chat's well under it x 8..615 */
    private fun module(name: String, mode: PartyLayout.Mode, n: Int, on: Boolean, box: Int, top: Float = PartyLayout.defaultTop(mode),
                       pet: Boolean = false, sel: String = "bound") {
        val st = PartyModel.State()
        val doc = party(n, on, box, pet = pet)
        doc.getJSONObject("p").put("sel", sel)
        st.onParty(PartyModel.parseParty(doc)!!, true, 0L)
        val v = PartyModuleView(RuntimeEnvironment.getApplication(), st)
        v.mode = mode
        val m = PartyLayout.module(mode, st.party!!.n, pet = st.party!!.pet, top = top)
        val (bmp, c) = page()
        // the chat's well under it, empty: where chat would stand
        UiKit.module(c, android.graphics.RectF(8f, m.chatTop, 616f, 927f))
        draw(c, v, PartyLayout.EDGE, top - PartyModuleView.RISE, 615f, m.well.b)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        Thread.sleep(300)                                                 // the pet's portrait decodes off the UI thread
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        c.drawColor(Theme.ink)
        UiKit.module(c, android.graphics.RectF(8f, m.chatTop, 616f, 927f))
        draw(c, v, PartyLayout.EDGE, top - PartyModuleView.RISE, 615f, m.well.b)
        save(bmp, name)
    }

    private val cfgJson = """{"type":"partycfg","v":"m77","c":{
      "slots":{"a":{"id":331,"n":"Healing Wave","i":136052,"k":true,"d":true},
               "x":{"id":8004,"n":"Lesser Healing Wave","i":136043,"k":true,"d":true},
               "zla":{"id":526,"n":"Cure Poison","i":136067,"k":true,"d":true},
               "zlx":{"id":2870,"n":"Cure Disease","i":136083,"k":true,"d":true},
               "zly":null,"zra":{"id":2008,"n":"Ancestral Spirit","i":136077,"k":true,"d":true,"res":true},"zrx":null,"zry":null},
      "yc":null,"touch":null,"spells":[{"id":331,"n":"Healing Wave","i":136052},{"id":8004,"n":"Lesser Healing Wave","i":136043}],
      "sy":{"on":false,"list":[{"id":131,"n":"Water Breathing","i":136148,"k":true}],"known":[{"id":131,"n":"Water Breathing","i":136148}],"pick":{}},
      "pad":{"row":"party","pick":0,"on":10,"off":11,"focus":12,"menu":17,"resend":22,"pbsel":23,
             "pbops":{"remove":31,"up":32,"down":33,"add":34},"mdone":35,"ptab":{"row":"padtab","tab":26,"slot0":27,"none":37}}}}"""

    /** the strip's band at rest (Home's bars slot y 8..226), Party mode on, with the game's own Y / B */
    private fun strip(name: String, n: Int, box: Int) {
        val live = PartyModel.live
        live.onParty(PartyModel.parseParty(party(n, true, box))!!, true, 0L)
        live.onCfg(PartyModel.parseCfg(JSONObject(cfgJson))!!, true)
        val v = CastView(RuntimeEnvironment.getApplication()) { }
        v.layout = CastView.Layout.DIAMONDS
        v.triggerStrip = true
        v.update(JSONObject("""{"hud":{"ok":true,"L":{"n":[{"p":6,"k":"fixed","fx":"inspect"},{"p":7,"k":"fixed","fx":"back"}]}}}"""))
        v.partyStrip = true
        v.partyPlateTop = 116f
        val (bmp, c) = page()
        draw(c, v, 0f, 8f, 1240f, 226f)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        Thread.sleep(400)                                                 // the icons decode off the UI thread
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        c.drawColor(Theme.ink)
        draw(c, v, 0f, 8f, 1240f, 226f)
        save(bmp, name)
    }

    @Test fun strip() {
        assumeTrue(out != null)
        artRoot()
        strip("strip_party3_on.png", 3, 2)
        strip("strip_party5_on_boxed_dead.png", 5, 4)
    }

    /** P6: the player menu over the left column (a party of 3), the party module under it */
    @Test fun playerMenu() {
        assumeTrue(out != null)
        artRoot()
        val (bmp, c) = page()
        val st = PartyModel.State()
        st.onParty(PartyModel.parseParty(party(3, true, 2))!!, true, 0L)
        val mod = PartyModuleView(RuntimeEnvironment.getApplication(), st)
        val m = PartyLayout.module(PartyLayout.Mode.REST, 3)
        UiKit.module(c, android.graphics.RectF(8f, m.chatTop, 616f, 927f))
        draw(c, mod, PartyLayout.EDGE, 216f - PartyModuleView.RISE, 615f, m.well.b)
        val v = PartyMenuView(RuntimeEnvironment.getApplication())
        v.open(1, "Brannoc Ironhelm", byPad = true)
        v.onDoc(JSONObject("""{"type":"pmenu","unit":"party1","which":"PARTY","nm":"Brannoc Ironhelm","src":"party","items":[
            {"t":"Brannoc Ironhelm","ttl":1},{"t":"Set Focus","k":"SetFocus","sc":13,"scr":"party","en":1},
            {"t":"Add Friend","en":1,"sub":[{"t":"Character","i":9,"en":1}]},{"t":"Interact","ttl":1},
            {"t":"Whisper","k":"Whisper","i":5,"ap":"whisper","en":1},{"t":"Inspect","k":"Inspect","i":6,"en":1},
            {"t":"Trade","i":7,"en":1},{"t":"Follow","i":8,"en":1},{"t":"Duel","i":10,"en":1},{"t":"Promote to Party Leader","i":11,"en":1},
            {"t":"Uninvite","i":12,"en":1}]}"""))
        val pl = PartyMenuView.PLACE
        draw(c, v, pl.left, pl.top, pl.right, pl.bottom)
        save(bmp, "home_player_menu.png")
    }

    /** P4 / P4Y / P4S: the Controller room on its Party mode tab (the room's 1240 x 935 above the bar) */
    private fun tab(name: String, ppos: Int, smartY: Boolean, yc: Boolean, pfocus: String = "diam", pright: Int = 0, pset: Int = 1) {
        val live = PartyModel.live
        var cfg = JSONObject(cfgJson)
        if (smartY) cfg.getJSONObject("c").getJSONObject("sy").put("on", true)
        if (yc) cfg.getJSONObject("c").put("yc", JSONObject("""{"id":331,"n":"Healing Wave","i":136052}"""))
        live.onCfg(PartyModel.parseCfg(cfg)!!, true)
        live.onParty(PartyModel.parseParty(party(3, true, 1))!!, true, 0L)
        GameUi.init(RuntimeEnvironment.getApplication())                  // party_design.md B: the tab shows while Party mode is allowed
        GameUi.setSwitch(GameUi.SW_PMODE, true)
        val v = ControllerView(RuntimeEnvironment.getApplication(), onPadCells = { true })
        v.onPadTabCtl(PadMapModel.parseCtl(JSONObject("""{"type":"padtabctl","c":{"on":true,"view":"party","focus":"buttons","tcur":5,
            "ppos":$ppos,"pfocus":"$pfocus","pright":$pright,"pset":$pset}}"""))!!)
        val (bmp, c) = page()
        draw(c, v, 0f, 0f, 1240f, 935f)
        save(bmp, name)
    }

    @Test fun controllerTab() {
        assumeTrue(out != null)
        artRoot()
        // p37: ppos = the router's places (8 A, 6 Y, 3 the D-pad's Right: kept for raids), pset the layer on show
        tab("controller_party_tab_a.png", 8, smartY = false, yc = false)
        tab("controller_party_tab_y.png", 6, smartY = false, yc = true)
        tab("controller_party_tab_y_smart.png", 6, smartY = true, yc = false, pfocus = "right", pright = 0)
        tab("controller_party_tab_touch.png", 8, smartY = false, yc = false, pfocus = "touch")
        tab("controller_party_tab_zl.png", 5, smartY = false, yc = false, pset = 2)
        tab("controller_party_tab_reserved.png", 3, smartY = false, yc = false)
    }

    @Test fun homeModule() {
        assumeTrue(out != null)
        artRoot()
        module("home_party3_off_rest.png", PartyLayout.Mode.REST, 3, on = false, box = 1)
        module("home_party3_on_rest.png", PartyLayout.Mode.REST, 3, on = true, box = 2)
        module("home_party4_on_rest.png", PartyLayout.Mode.REST, 4, on = true, box = 1)
        module("home_party5_on_rest.png", PartyLayout.Mode.REST, 5, on = true, box = 4)
        module("home_party5_off_rest.png", PartyLayout.Mode.REST, 5, on = false, box = 1)
        module("home_party5_on_combat.png", PartyLayout.Mode.FIGHT_ON, 5, on = true, box = 3)
        module("home_party5_off_combat.png", PartyLayout.Mode.FIGHT_OFF, 5, on = false, box = 1)
        module("home_solo_off_rest.png", PartyLayout.Mode.REST, 1, on = false, box = 1)
        module("home_solo_on_combat.png", PartyLayout.Mode.FIGHT_ON, 1, on = true, box = 1)
        module("home_solo_off_combat.png", PartyLayout.Mode.FIGHT_OFF, 1, on = false, box = 1)
    }

    /**
     * m134 (party_design.md "PLAYER / PET REVISION" + "SELECT TAB LOOK"; the user, 2026-11-11, after using 1428): you + the pet on your
     * right in the first row (you the big cell, the pet's portrait, no green dot, no A chip), the party under it; no pet = you full width;
     * the Select tab as the game's side tab on the screen's left edge, off / on / not bound
     */
    @Test fun playerPetAndSelectTab() {
        assumeTrue(out != null)
        artRoot()
        module("m134_pet_party5_on_rest.png", PartyLayout.Mode.REST, 5, on = true, box = 2, pet = true)
        module("m134_pet_party3_on_rest.png", PartyLayout.Mode.REST, 3, on = true, box = 1, pet = true)
        module("m134_pet_solo_on_combat.png", PartyLayout.Mode.FIGHT_ON, 1, on = true, box = 2, pet = true)
        module("m134_pet_party4_off_rest.png", PartyLayout.Mode.REST, 4, on = false, box = 1, pet = true)
        module("m134_nopet_party5_on_rest.png", PartyLayout.Mode.REST, 5, on = true, box = 3)
        module("m134_nopet_party4_off_rest.png", PartyLayout.Mode.REST, 4, on = false, box = 1)
        module("m134_tab_on_party3.png", PartyLayout.Mode.REST, 3, on = true, box = 3)
        module("m134_tab_off_party3.png", PartyLayout.Mode.REST, 3, on = false, box = 1)
        module("m134_tab_unbound_party3.png", PartyLayout.Mode.REST, 3, on = false, box = 1, sel = "dialog")
        module("m134_tab_off_takeover_solo.png", PartyLayout.Mode.FIGHT_OFF, 1, on = false, box = 1)
    }
}
