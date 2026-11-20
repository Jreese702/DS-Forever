package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.Locale
import java.util.zip.CRC32

/**
 * MOVABLE HOME, PHASE 0 - THE GOLDEN FREEZE (SPEC.txt section 14 P0 / section 15 HomePlacesGoldenTest; principle P8 "Classic = today"):
 * the real [HomeView] laid in every state the spec lists, recorded on the UNMODIFIED code (5bb9ecc3) before the layout refactor, so the
 * refactor can prove that the Classic layout draws Home exactly as today.
 *
 * Pinned, per state: every part's box ([HomeView]'s `now`), each part's visibility, the chat pane's box (the Chat room's glide leaves
 * from it), the band's CastView layout. The state matrix: rest Diamonds / Thin row x fight (none / Grow / Same size) x Party (none, the
 * takeover's rows with Party mode off, Party mode on with 1 / 3 / 5 rows) x the tap-to-cast bar (Off / Left / Right / Both) x its fight
 * rule (Hide / Stay) x the focused quest's card (none / over the map / above chat). Then the side slides (the LB picture, the pause
 * wheel's full slide, the class wheels LB+LT / RB+RT) as each part's offset, the full-screen map and chat modules, and a CRC of the
 * rendered pixels of the key states (normal, combat grow, Party mode, LB slide, each bar place, full map / chat, quest card).
 *
 * The goldens live in app/src/test/resources/home_golden/. To record them again (only on purpose: a deliberate change of today's Home):
 *   HOME_GOLDEN_RECORD=<repo>/app/src/test/resources/home_golden gradlew :app:testModernDebugUnitTest --tests "*HomePlacesGoldenTest*"
 * A mismatch writes the actual lines to build/home_golden_actual/ and fails with the first differing lines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HomePlacesGoldenTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    enum class Fight { NONE, GROW, SAME }
    enum class Party(val n: Int, val on: Boolean) { NONE(0, false), OFF3(3, false), ON1(1, true), ON3(3, true), ON5(5, true) }
    enum class Card { NONE, OVER, ABOVE }

    data class St(val rest: HomeRest, val fight: Fight, val party: Party, val side: CastStripModel.Side,
                  val combat: CastStripModel.Combat, val card: Card) {
        val key get() = "rest=${rest.name} fight=${fight.name} party=${party.name} bar=${side.name} barFight=${combat.name} card=${card.name}"
    }

    // ---------------------------------------------------------------- the page

    private fun lay(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, W, H)
    }
    private fun settle(n: Int = 30) { for (i in 0 until n) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }
    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)
    private fun setField(o: Any, name: String, v: Any?) { o.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(o, v) }

    private val castDoc = JSONObject("""{"type":"castbar","v":"cb6","n":8,"pick":8,"pp":[39,19],"e":[
        {"s":1,"id":403,"i":136048,"c":0},{"s":2,"id":8042,"i":136026,"c":1},{"s":3,"id":331,"i":136052,"c":2},
        {"s":4,"id":324,"i":136051,"c":3},{"s":5,"id":8017,"i":136086,"c":4},{"s":6,"id":2484,"i":136102,"c":5},
        {"s":7,"id":8018,"i":136015,"c":6},{"s":8,"id":5730,"i":135824,"c":7}]}""")

    private val PAD = JSONObject().put("v", "pad22").put("x", 0).put("y", 12).put("cw", 6).put("ch", 2).put("cols", 40).put("pw", 1920).put("ph", 1080)
        .put("rows", JSONObject().put("qpick", 3).put("qact", 4).put("pfocus", 9))
        .put("sizes", JSONObject().put("qpick", 40).put("qact", 40).put("pfocus", 31))

    private fun questsDoc(card: Boolean, fight: Boolean): JSONObject {
        val focused = JSONObject().put("id", 33).put("title", "Wolves Across the Border").put("zone", "Elwynn Forest").put("lv", 6).put("slot", 1)
            .put("watch", 1).put("focus", true)
            .put("obj", JSONArray().put(JSONObject().put("t", "Den burned: 0/1").put("have", 0).put("need", 1)))
        val list = JSONArray()
        if (card) list.put(focused)
        return JSONObject().put("plv", 7).put("focus", if (card) 33 else 0).put("rwMode", "id").put("list", list).put("pad", PAD).put("cb", fight)
    }

    private val roster = listOf(
        """{"u":"player","i":0,"n":"Korvash","c":"SHAMAN","role":"HEALER","pt":"MANA","lv":20,"dead":false,"off":false,"col":"ff0070dd"}""",
        """{"u":"party1","i":1,"n":"Brannoc","c":"WARRIOR","role":"TANK","pt":"RAGE","lv":21,"dead":false,"off":false,"col":"ffc69b6d"}""",
        """{"u":"party2","i":2,"n":"Seraphine","c":"MAGE","role":"DAMAGER","pt":"MANA","lv":20,"dead":false,"off":false,"col":"ff3fc7eb"}""",
        """{"u":"party3","i":3,"n":"Tamsin","c":"ROGUE","role":"DAMAGER","pt":"ENERGY","lv":20,"dead":false,"off":false,"col":"fffff468"}""",
        """{"u":"party4","i":4,"n":"Odwin","c":"PRIEST","role":"HEALER","pt":"MANA","lv":19,"dead":false,"off":false,"col":"ffffffff"}""")

    private fun partyDoc(p: Party, fight: Boolean): JSONObject {
        val m = JSONArray()
        for (k in 0 until p.n) m.put(JSONObject(roster[k]))
        return JSONObject().put("type", "party").put("v", "m77").put("rs", 1)
            .put("p", JSONObject().put("on", p.on).put("box", 1).put("fight", fight).put("grp", p.n > 1).put("sel", "bound").put("m", m))
    }

    private class Page(val home: HomeView, val cast: CastView, val map: MapView)

    /** no art root (the views' own fallbacks): a test run earlier in this JVM may have pointed ArtHarvest at MK_ART_ROOT (QuestPanelTest,
     *  WaterDepthTest, the map shots) and left it there, and the kit's caches keep what it read (the full suite's order then changed the
     *  pixels' CRCs here) */
    private fun noArt() {
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, null)
        UiKit.root = UiKit.root                                         // the kit's caches forget (a font, a texture read before)
        for (n in listOf("castCache", "castMissing")) when (val o = UiKit::class.java.getDeclaredField(n).apply { isAccessible = true }.get(UiKit)) {
            is MutableMap<*, *> -> o.clear(); is MutableSet<*> -> o.clear()
        }
        Icons.refresh()
        MinimapTiles.refresh()
    }

    private fun page(s: St): Page {
        noArt()
        // HomeSettings, Settings and CastSettings keep the JVM's FIRST app's prefs (init only fills a null), so clearing this app's prefs
        // alone kept what a test run earlier in the full suite wrote there (an ABXY location of Nintendo left by an earlier test moved
        // the face pictures and changed the pixels' CRCs). Each one points at this app's prefs, cleared: today's defaults, whatever ran before.
        for ((o, name) in listOf(HomeSettings to "home", Settings to "settings", CastSettings to "cast")) {
            val sp = app.getSharedPreferences(name, 0).apply { edit().clear().commit() }
            o.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.set(null, sp)
        }
        HomeSettings.init(app)
        Settings.init(app)
        // (movable Home: HomeSettings keeps the JVM's first prefs across Robolectric apps; the layout record back to Classic, no character,
        // before today's settings are set through their old setters - a test run earlier may have left another layout there)
        HomeLayoutStore.character(null); HomeLayoutStore.set(HomeLayout())
        HomeSettings.rest = s.rest
        HomeSettings.combatGrow = s.fight != Fight.SAME
        HomeSettings.castBarSide = s.side
        HomeSettings.castBarCombat = s.combat
        HomeSettings.questOverMap = s.card != Card.ABOVE
        CastStripModel.Live.pos = 0f; CastStripModel.Live.side = null
        setField(PartyModel.live, "party", null)
        val fight = s.fight != Fight.NONE
        if (s.party != Party.NONE) PartyModel.live.onParty(PartyModel.parseParty(partyDoc(s.party, fight))!!, true, System.currentTimeMillis())
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        lay(home)
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        val bar = CastStripModel.parse(castDoc)!!
        CastStripModel.Live.set(bar)
        home.onCastBar(bar)
        home.onFull(JSONObject().put("type", "full").put("map", JSONObject().put("zone", "Elwynn Forest").put("pvp", "contested"))
            .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true).put("hudS", JSONObject().put("c", fight)))), true)
        val q = questsDoc(s.card != Card.NONE, fight)
        QuestItem.Cd.onQuests(q, true)
        map.onQuests(q); home.onQuests(q, true)
        home.partyChanged()
        home.tick()
        ShadowLooper.idleMainLooper()
        lay(home)
        HomeView::class.java.getDeclaredMethod("changed").apply { isAccessible = true }.invoke(home)
        settle()
        lay(home)
        return Page(home, cast, map)
    }

    // ---------------------------------------------------------------- what is pinned

    private fun f(v: Float) = String.format(Locale.US, "%.2f", v).let { if (it == "-0.00") "0.00" else it }
    private fun r(b: RectF?) = if (b == null) "-" else "${f(b.left)},${f(b.top)},${f(b.right)},${f(b.bottom)}"

    @Suppress("UNCHECKED_CAST")
    private fun boxes(home: HomeView): Map<String, RectF> = (field(home, "now") as Map<Any, RectF>).entries.associate { it.key.toString() to RectF(it.value) }
    @Suppress("UNCHECKED_CAST")
    private fun views(home: HomeView): Map<String, View> = (field(home, "views") as Map<Any, View>).entries.associate { it.key.toString() to it.value }

    private fun placesLine(p: Page): String {
        val b = boxes(p.home)
        val vs = views(p.home)
        val parts = b.keys.sorted().joinToString(" ") { "$it=${r(b[it])}" }
        val vis = vs.entries.sortedBy { it.key }.filter { it.value.visibility == View.VISIBLE }.joinToString(",") { it.key }
        return "$parts | visible=$vis | pane=${r(p.home.chatPaneBox())} | band=${p.cast.layout}"
    }

    private fun offsetsLine(p: Page): String {
        val vs = views(p.home)
        return vs.entries.sortedBy { it.key }.filter { it.value.visibility == View.VISIBLE }
            .joinToString(" ") { "${it.key}=${f(it.value.translationX)},${f(it.value.translationY)},${f(it.value.alpha)}" }
    }

    private fun matrix(): List<St> {
        val out = ArrayList<St>()
        for (rest in listOf(HomeRest.DIAMONDS, HomeRest.ROW)) for (fight in Fight.values()) for (party in Party.values())
            for (side in CastStripModel.Side.values()) for (combat in CastStripModel.Combat.values()) for (card in Card.values()) {
                if (side == CastStripModel.Side.OFF && combat == CastStripModel.Combat.STAY) continue   // the fight rule needs a bar
                out.add(St(rest, fight, party, side, combat, card))
            }
        return out
    }

    // ---------------------------------------------------------------- golden I/O

    private fun golden(name: String, lines: List<String>) {
        val text = lines.joinToString("\n") + "\n"
        System.getenv("HOME_GOLDEN_RECORD")?.let { dir ->
            File(dir).mkdirs(); File(dir, name).writeText(text); return
        }
        val want = javaClass.classLoader!!.getResourceAsStream("home_golden/$name")?.bufferedReader()?.readText()
            ?: run { fail("no golden home_golden/$name (record with HOME_GOLDEN_RECORD)"); return }
        if (want.replace("\r\n", "\n") == text) return
        val actual = File("build/home_golden_actual").apply { mkdirs() }
        File(actual, name).writeText(text)
        val a = want.replace("\r\n", "\n").lines(); val b = text.lines()
        val diffs = (0 until maxOf(a.size, b.size)).filter { a.getOrNull(it) != b.getOrNull(it) }
        fail("$name: ${diffs.size} lines differ from today's Home (actual in ${actual.absolutePath}); first:\n" +
            diffs.take(4).joinToString("\n") { "  want: ${a.getOrNull(it)}\n  got:  ${b.getOrNull(it)}" })
    }

    // ---------------------------------------------------------------- the tests

    /** every state of the matrix: each part's box, visibility, the chat pane's box and the band's look */
    @Test fun placesInEveryState() {
        val lines = ArrayList<String>()
        for (s in matrix()) lines.add("${s.key} :: ${placesLine(page(s))}")
        golden("places.txt", lines)
    }

    /** the side slides: the LB picture, the pause wheel, the class wheels (LB+LT left, RB+RT right): every part's offset */
    @Test fun slidesInEveryPlace() {
        val lines = ArrayList<String>()
        for (party in listOf(Party.NONE, Party.ON3)) for (side in CastStripModel.Side.values()) for (card in Card.values()) {
            val s = St(HomeRest.DIAMONDS, Fight.NONE, party, side, CastStripModel.Combat.HIDE, card)
            var p = page(s)
            p.home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
            lines.add("${s.key} lb :: ${offsetsLine(p)}")
            p.home.pauseSlide(0f, LbPartyView.SIDE_PX)
            lines.add("${s.key} lb-back :: ${offsetsLine(p)}")
            p.home.pauseSlide(UiKit.PAUSE_OPEN_MS)
            lines.add("${s.key} pause :: ${offsetsLine(p)}")
            for (left in listOf(true, false)) {
                p = page(s)
                val flow = field(p.cast, "classFlow")!!
                flow.javaClass.getDeclaredField("side").apply { isAccessible = true }.setInt(flow, if (left) 1 else 2)
                HomeView::class.java.getDeclaredMethod("classLayerShow", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(p.home, true)
                settle(40)
                lines.add("${s.key} class${if (left) "L" else "R"} :: rect=${Rect(field(p.home, "classRect") as Rect).flattenToString()} ${offsetsLine(p)}")
            }
        }
        golden("slides.txt", lines)
    }

    /** the full-screen modules (the map's, the dormant chat's) open over each rest */
    @Test fun fullModules() {
        val lines = ArrayList<String>()
        for (rest in listOf(HomeRest.DIAMONDS, HomeRest.ROW)) for (side in listOf(CastStripModel.Side.OFF, CastStripModel.Side.BOTH)) for (card in Card.values()) {
            val s = St(rest, Fight.NONE, Party.NONE, side, CastStripModel.Combat.HIDE, card)
            var p = page(s)
            p.home.openMapModule(); settle()
            lines.add("${s.key} map :: ${placesLine(p)}")
            p = page(s)
            HomeView::class.java.getDeclaredMethod("touchOpen", HomeModule::class.java).apply { isAccessible = true }.invoke(p.home, HomeModule.CHAT)
            settle()
            lines.add("${s.key} chat :: ${placesLine(p)}")
        }
        golden("modules.txt", lines)
    }

    private fun shot(p: Page): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            setField(p.home, "clockText", "9:51 AM"); setField(p.home, "batteryPct", 98); setField(p.home, "charging", false)
            lay(p.home)
            val c = Canvas(b); c.drawColor(Theme.ink); p.home.draw(c)
            if (pass == 0) settle()
        }
        return b
    }
    private fun crc(b: Bitmap): String {
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val c = CRC32(); val buf = java.nio.ByteBuffer.allocate(px.size * 4); buf.asIntBuffer().put(px); c.update(buf.array())
        return java.lang.Long.toHexString(c.value)
    }

    /** the key states' pixels (no art root: the views' own fallbacks, so the picture is the same on every machine) */
    @Test fun pixelsOfTheKeyStates() {
        val out = System.getenv("HOME_GOLDEN_SHOTS")?.let { File(it).apply { mkdirs() } }
        val D = HomeRest.DIAMONDS
        val HIDE = CastStripModel.Combat.HIDE
        val cases = listOf(
            "normal" to St(D, Fight.NONE, Party.NONE, CastStripModel.Side.OFF, HIDE, Card.NONE),
            "card_over" to St(D, Fight.NONE, Party.NONE, CastStripModel.Side.OFF, HIDE, Card.OVER),
            "card_above" to St(D, Fight.NONE, Party.NONE, CastStripModel.Side.OFF, HIDE, Card.ABOVE),
            "thin_row" to St(HomeRest.ROW, Fight.NONE, Party.NONE, CastStripModel.Side.OFF, HIDE, Card.OVER),
            "grow" to St(D, Fight.GROW, Party.NONE, CastStripModel.Side.OFF, HIDE, Card.OVER),
            "same" to St(D, Fight.SAME, Party.NONE, CastStripModel.Side.OFF, HIDE, Card.OVER),
            "party3" to St(D, Fight.NONE, Party.ON3, CastStripModel.Side.OFF, HIDE, Card.OVER),
            "party5_fight" to St(D, Fight.GROW, Party.ON5, CastStripModel.Side.BOTH, HIDE, Card.OVER),
            "bar_left" to St(D, Fight.NONE, Party.NONE, CastStripModel.Side.LEFT, HIDE, Card.OVER),
            "bar_right" to St(D, Fight.NONE, Party.NONE, CastStripModel.Side.RIGHT, HIDE, Card.OVER),
            "bar_both" to St(D, Fight.NONE, Party.NONE, CastStripModel.Side.BOTH, HIDE, Card.ABOVE),
            "bar_both_grow_hidden" to St(D, Fight.GROW, Party.NONE, CastStripModel.Side.BOTH, HIDE, Card.OVER),
        )
        val lines = ArrayList<String>()
        for ((name, s) in cases) {
            val p = page(s)
            val b = shot(p)
            out?.let { d -> File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            lines.add("$name ${s.key} :: ${crc(b)}")
            if (name == "card_over" || name == "party3") {
                p.home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
                val lb = shot(p)
                out?.let { d -> File(d, "${name}_lb.png").outputStream().use { lb.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                lines.add("${name}_lb ${s.key} :: ${crc(lb)}")
            }
        }
        for (mod in listOf("map", "chat")) {
            val s = St(D, Fight.NONE, Party.NONE, CastStripModel.Side.OFF, HIDE, Card.OVER)
            val p = page(s)
            if (mod == "map") p.home.openMapModule()
            else HomeView::class.java.getDeclaredMethod("touchOpen", HomeModule::class.java).apply { isAccessible = true }.invoke(p.home, HomeModule.CHAT)
            settle()
            val b = shot(p)
            out?.let { d -> File(d, "full_$mod.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            lines.add("full_$mod ${s.key} :: ${crc(b)}")
        }
        golden("pixels.txt", lines)
    }
}
