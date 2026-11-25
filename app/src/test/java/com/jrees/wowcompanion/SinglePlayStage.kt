package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * SINGLE SCREEN MODE'S PLAY DOCKS OFF-DEVICE (HomeLendDocksTest, SinglePlayShotsTest): the REAL Home (EditPreviewShotsTest's page: a
 * party of four, ten chat lines, a focused quest, the Elwynn map) lent to the REAL SingleDocks, whose top-screen windows are
 * [FakeShell]'s: each sub a FrameLayout placed by its bounds in a 1920 x 1080 "screen" (as fork/SubWindow places its window).
 */
class SinglePlayStage(private val app: Context, partyOn: Boolean, val w: Int = 1920, val h: Int = 1080) {
    val holder = FrameLayout(app)
    val map = MapView(app)
    val cast = CastView(app) { }
    val chat = ChatView(app) { }
    val home: HomeView
    val screen = FrameLayout(app)
    val shell = FakeShell(app, screen, w, h)
    val docks: SingleDocks

    /** one fake top-screen window: its content [frame] placed in [screen] at its bounds; counts its updates */
    class FakeSub(ctx: Context, private val screen: FrameLayout) : SingleSub {
        val frame = FrameLayout(ctx).apply { visibility = View.GONE }
        var bounds = intArrayOf(0, 0, 0, 0); private set
        var places = 0; private set
        var on = false; private set
        var touch = true; private set
        init { screen.addView(frame, FrameLayout.LayoutParams(1, 1)) }
        override fun put(v: View?) {
            if (v != null && v.parent === frame) return
            frame.removeAllViews()
            if (v == null) return
            (v.parent as? android.view.ViewGroup)?.removeView(v)
            frame.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        override fun place(l: Int, t: Int, r: Int, b: Int) {
            if (bounds[0] == l && bounds[1] == t && bounds[2] == r && bounds[3] == b) return
            bounds = intArrayOf(l, t, r, b); places++
            frame.layoutParams = FrameLayout.LayoutParams(maxOf(1, r - l), maxOf(1, b - t)).apply { leftMargin = l; topMargin = t }
        }
        override fun shown(on: Boolean) { this.on = on; frame.visibility = if (on) View.VISIBLE else View.GONE }
        override fun touchable(on: Boolean) { touch = on }
    }

    class FakeShell(private val ctx: Context, private val screen: FrameLayout, override val screenW: Int, override val screenH: Int) : SingleShell {
        override val box: IntArray = SingleScreen.panelBox(screenW, screenH)
        override val otherDisplay: Int? = null
        val subs = LinkedHashMap<String, FakeSub>()
        override fun look(l: SingleLook.Look) {}
        override fun sub(id: String): SingleSub = subs.getOrPut(id) { FakeSub(ctx, screen) }
        override fun hint(text: String?, ms: Long) {}
    }

    private fun setField(o: Any, name: String, v: Any?) { o.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(o, v) }
    fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)

    private val PAD = JSONObject().put("v", "pad22").put("x", 0).put("y", 12).put("cw", 6).put("ch", 2).put("cols", 40).put("pw", 1920).put("ph", 1080)
        .put("rows", JSONObject().put("qpick", 3).put("qact", 4).put("pfocus", 9))
        .put("sizes", JSONObject().put("qpick", 40).put("qact", 40).put("pfocus", 31))
    private val ELWYNN_RECT = floatArrayOf(-7939.583f, -10254.166f, 1535.4166f, -1935.4166f)
    fun mapDoc(x: Double = 0.42, y: Double = 0.62) = JSONObject().put("ok", true).put("mapID", 1429).put("zone", "Elwynn Forest")
        .put("subzone", "Goldshire").put("x", x).put("y", y).put("facing", 2.4)
        .put("rect", JSONObject().put("c", 0).put("t", ELWYNN_RECT[0].toDouble()).put("b", ELWYNN_RECT[1].toDouble())
            .put("l", ELWYNN_RECT[2].toDouble()).put("r", ELWYNN_RECT[3].toDouble()))
        .put("quests", JSONArray().put(JSONObject().put("questID", 60).put("x", 0.47).put("y", 0.58).put("title", "Kobold Candles"))
            .put(JSONObject().put("questID", 33).put("x", 0.30).put("y", 0.80).put("title", "Wolves Across the Border")))
    private fun quests(): JSONObject {
        val focused = JSONObject().put("id", 60).put("title", "Kobold Candles").put("zone", "Elwynn Forest").put("lv", 7).put("slot", 1)
            .put("watch", 1).put("focus", true)
            .put("obj", JSONArray().put(JSONObject().put("t", "Wax Candles: 3/8").put("have", 3).put("need", 8)))
        return JSONObject().put("plv", 7).put("focus", 60).put("rwMode", "id").put("list", JSONArray().put(focused)).put("pad", PAD).put("cb", false)
    }
    private val chatLines = listOf("general" to "[General] Thornbrook: LF2M Deadmines", "guild" to "[Guild] Mirelle: grats on 12!",
        "say" to "Oswin says: anyone near Goldshire?", "loot" to "You receive loot: Broken Boar Tusk.",
        "whisper" to "Seraphine whispers: inv pls", "party" to "[Party] Iselle: pulling next pack", "guild" to "[Guild] Aldric: gz on 20!",
        "say" to "Korvash says: on my way", "system" to "Quest accepted: Kobold Candles", "general" to "[Trade] Odwin: WTS Copper Bars")
    private val roster = listOf(
        """{"u":"player","i":0,"n":"Korvash","c":"SHAMAN","role":"HEALER","pt":"MANA","lv":20,"dead":false,"off":false,"col":"ff0070dd"}""",
        """{"u":"party1","i":1,"n":"Brannoc","c":"WARRIOR","role":"TANK","pt":"RAGE","lv":21,"dead":false,"off":false,"col":"ffc69b6d"}""",
        """{"u":"party2","i":2,"n":"Seraphine","c":"MAGE","role":"DAMAGER","pt":"MANA","lv":20,"dead":false,"off":false,"col":"ff3fc7eb"}""",
        """{"u":"party3","i":3,"n":"Tamsin","c":"ROGUE","role":"DAMAGER","pt":"ENERGY","lv":20,"dead":false,"off":false,"col":"fffff468"}""")
    fun partyDoc(on: Boolean, fight: Boolean = false): JSONObject {
        val m = JSONArray(); for (r in roster) m.put(JSONObject(r))
        return JSONObject().put("type", "party").put("v", "m77").put("rs", 1)
            .put("p", JSONObject().put("on", on).put("box", 2).put("fight", fight).put("grp", true).put("sel", "bound").put("m", m))
    }

    fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    fun settle(n: Int = 30) { for (i in 0 until n) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }
    val HW = 1240
    val HH = 1080 - Strip.HEIGHT

    init {
        System.getenv("HOME_LAYOUT_ART")?.let { root -> ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
        app.getSharedPreferences("home", 0).edit().clear().commit()
        HomeSettings.init(app); Settings.init(app); Tr.init(app)
        HomeLayoutStore.character(null)
        HomeLayoutStore.set(HomeLayout())
        setField(PartyModel.live, "party", null)
        if (partyOn) PartyModel.live.onParty(PartyModel.parseParty(partyDoc(true))!!, true, System.currentTimeMillis())
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        home = HomeView(app, map, cast, chat) { }
        home.forceLive = true
        home.borrow()
        home.onPadMap(PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m199","m":{"page":1,"sets":[],"dpad":{"on":true,"want":true,"ct":0,"cc":0,"pv":0,"ss":1}}}"""))!!)
        lay(home, HW, HH)
        cast.roomBarOn = true
        cast.roomBar = HoldTalk.DEFAULT_DIRS
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        fight(false)
        map.update(mapDoc(), 0)
        val q = quests()
        QuestItem.Cd.onQuests(q, true); map.onQuests(q); home.onQuests(q, true)
        val lines = JSONArray()
        for ((i, l) in chatLines.withIndex()) lines.put(JSONObject().put("i", i + 1).put("ch", l.first).put("text", l.second))
        home.onChat(JSONObject().put("v", "chat40").put("s", 1).put("n", chatLines.size).put("lines", lines))
        home.partyChanged()
        home.tick()
        ShadowLooper.idleMainLooper()
        lay(home, HW, HH)
        HomeView::class.java.getDeclaredMethod("changed").apply { isAccessible = true }.invoke(home)
        settle()
        lay(home, HW, HH)
        docks = SingleDocks(app, shell, cast)
    }

    /** the game's fight word as the add-on's full document says it (Home's takeover follows it) */
    fun fight(on: Boolean) {
        val ab = JSONObject().put("hud", JSONObject().put("ok", true)).put("hudS", JSONObject().put("c", on).put("g", 5000.0))
        home.onFull(JSONObject().put("type", "full").put("map", JSONObject().put("zone", "Elwynn Forest").put("pvp", "contested"))
            .put("actionbar", ab), true)
        cast.update(ab)
        home.tick(); ShadowLooper.idleMainLooper()
    }

    /** the PLAY state as CompanionHost's PLAY wiring lays it: Home's views lent, the docks up, the arrangement for [arr] */
    fun play(arr: SinglePlayLayout.Arr, animate: Boolean = false): SinglePlayLayout.Plan {
        home.lendToDocks(docks.chat, docks.party, docks.map)
        docks.show(true)
        val laid = home.dockLaid()
        val p = SinglePlayLayout.plan(arr, laid.party, SinglePlayLayout.Hud(), w, h, home.dockPartyH())
        val hide = HashSet<SinglePlayLayout.Piece>()
        if (!laid.chat) hide += SinglePlayLayout.Piece.CHAT
        if (!laid.map) hide += SinglePlayLayout.Piece.MAP
        docks.arrange(p, animate, hide)
        return p
    }

    /** the top screen's windows laid as the window manager would (each at its bounds) */
    fun layScreen() { lay(screen, w, h) }

    /** the docks alone over a transparent 1920 x 1080 (the compositor lays them over the game) */
    fun draw(): Bitmap {
        layScreen()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.TRANSPARENT)
        screen.draw(c)
        return b
    }

    fun sub(p: SinglePlayLayout.Piece): FakeSub? = shell.subs[p.sub]
}
