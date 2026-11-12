package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
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
 * THE THEMES' OFF-DEVICE RENDERS (the user, 2026-11-12: "I personally want an oled theme, but different color variations for menus like
 * pink and silver or gold instead of the classic bronze ... like 10 good options for me to review in the morning"): every preset drawn
 * on the same six screens - Home at rest, the Controller room, Bags, Settings, a dialog pop-up, the party module - as
 * <preset>_<screen>.png, then one contact sheet per screen (sheet_<screen>.png, every preset side by side).
 *
 * Runs only when THEME_SHOTS_DIR is set. THEME_ART_ROOT = an art root laid out like the app's (ui/kit, fonts, icons.pak);
 * THEME_CTL_DOCS = the Controller room's documents (tools/test_party.py with CTL_DUMP=<dir>); THEME_ONLY = one preset id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeShotsTest {
    private val out: File? = System.getenv("THEME_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()

    private fun artRoot() {
        val root = System.getenv("THEME_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun settle() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        Thread.sleep(250)                                                     // icons decode off the UI thread
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    /** [v] drawn twice (the second time with its pictures decoded) on a 1240 x [h] page of the theme's ink */
    private fun shot(v: View, h: Int = 1080, ground: Boolean = true): Bitmap {
        lay(v, 1240, h)
        val b = Bitmap.createBitmap(1240, h, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            val c = Canvas(b)
            if (ground) c.drawColor(Theme.ink) else c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
            v.draw(c)
            if (pass == 0) settle()
        }
        return b
    }

    private fun save(b: Bitmap, name: String) {
        val d = out ?: return
        d.mkdirs()
        File(d, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---------------------------------------------------------------- the screens

    private fun home(): Bitmap = homeShot(bar = false)
    /** Home with the DS toolbar fed (a layout of eight spells on the plain D-pad / face, the Controller room's pad map): the toolbar's
     *  slot frames are CastView's own art (ui/cast), not the kit's, so they need their own look */
    private fun homeBar(): Bitmap? = if (System.getenv("THEME_CTL_DOCS") == null) null else homeShot(bar = true)

    private fun homeShot(bar: Boolean): Bitmap {
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        val h = 1080 - Strip.HEIGHT
        lay(home, 1240, h)
        if (bar) {
            val icons = listOf(136048, 136026, 135990, 136052, 135813, 136015, 135824, 136075)
            val n = JSONArray()
            for (p in 1..8) n.put(JSONObject().put("p", p).put("k", "spell").put("id", 400 + p).put("i", icons[p - 1]))
            cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
                .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", n))))
            val dir = File(System.getenv("THEME_CTL_DOCS")!!)
            home.onPadMap(PadMapModel.parse(JSONObject(File(dir, "padmap_on.json").readText()))!!)
            settle()
        }
        home.tick()
        // Home's rest places with no motion (HomeView.previewSettle is internal to the companion's module: found by its JVM name)
        val look = HomeView.Companion.Look(HomeRest.DIAMONDS, false, false, false)
        HomeView::class.java.methods.firstOrNull { it.name.startsWith("previewSettle") }?.invoke(home, look)
        lay(home, 1240, h)
        val strip = Strip(app) { }
        val b = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            lay(home, 1240, h)
            val c = Canvas(b)
            c.drawColor(Theme.ink)
            home.draw(c)
            strip.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Strip.HEIGHT, View.MeasureSpec.EXACTLY))
            strip.layout(0, 0, 1240, Strip.HEIGHT)
            c.save(); c.translate(0f, (1080 - Strip.HEIGHT).toFloat()); strip.draw(c); c.restore()
            if (pass == 0) settle()
        }
        return b
    }

    private fun controller(): Bitmap? {
        val dir = System.getenv("THEME_CTL_DOCS")?.let { File(it) } ?: return null
        fun doc(n: String) = JSONObject(File(dir, "$n.json").readText())
        val v = ControllerView(app, { true })
        v.onPadMap(PadMapModel.parse(doc("padmap_on"))!!)
        v.onPadTabCtl(PadMapModel.parseCtl(doc("ctl_dleft"))!!)
        return shot(v, 960)
    }

    private fun bags(): Bitmap {
        val v = BagsView(app)
        val names = listOf(134085, 134252, 134830, 134414, 135232, 134566, 134579, 134120, 132832, 132815, 132891, 132912)
        val items = JSONArray()
        for ((k, ic) in names.withIndex()) items.put(JSONObject().put("bag", 0).put("slot", k + 1).put("id", 2000 + k).put("icon", ic)
            .put("count", if (k % 3 == 0) 5 else 1).put("q", k % 5).put("name", "Item $k"))
        val bagsDoc = JSONObject().put("free", 14).put("total", 26).put("money", 102345)
            .put("bags", JSONArray().put(JSONObject().put("bag", 0).put("slots", 16)).put(JSONObject().put("bag", 1).put("slots", 10).put("icon", 133628)))
            .put("items", items)
        v.update(bagsDoc, false)
        return shot(v, 1080 - Strip.HEIGHT)
    }

    private fun settings(): Bitmap {
        Settings.init(app); SettingsTabs.init(app)
        val v = SettingsView(app) { }
        v.tab = SettingsTabs.Tab.HOME                                         // the Theme picker's tab (the top of Home)
        return shot(v, 1080 - Strip.HEIGHT)
    }

    private fun dialog(): Bitmap {
        val v = DialogView(app)
        val d = JSONObject().put("type", "dialogs").put("list", JSONArray().put(JSONObject().put("i", 1).put("which", "CONFIRM_LEAVE")
            .put("text", "Brannoc invites you to a group.").put("x", false)
            .put("buttons", JSONArray().put(JSONObject().put("k", 1).put("label", "Accept")).put(JSONObject().put("k", 2).put("label", "Decline")))))
        v.update(d, false)
        v.visibility = View.VISIBLE
        return shot(v, 1080 - Strip.HEIGHT)
    }

    private fun member(i: Int, n: String, c: String, role: String, col: String, dead: Boolean = false) =
        JSONObject().put("u", if (i == 0) "player" else "party$i").put("i", i).put("n", n).put("c", c).put("role", role)
            .put("pt", "MANA").put("lv", 20 + i).put("dead", dead).put("off", false).put("rng", JSONObject.NULL).put("col", col)

    private fun party(): Bitmap {
        val m = JSONArray().put(member(0, "Korvash Emberhoof", "SHAMAN", "HEALER", "ff0070dd"))
            .put(member(1, "Brannoc Ironhelm", "WARRIOR", "TANK", "ffc69b6d"))
            .put(member(2, "Seraphine Dusk", "MAGE", "DAMAGER", "ff3fc7eb"))
            .put(member(3, "Tamsin Quickfoot", "ROGUE", "DAMAGER", "fffff468", dead = true))
            .put(member(4, "Odwin Thornbrook", "PRIEST", "HEALER", "ffffffff"))
        val doc = JSONObject().put("type", "party").put("v", "m77").put("rs", 1)
            .put("p", JSONObject().put("on", true).put("box", 2).put("fight", false).put("grp", true).put("sel", "bound").put("m", m))
        val st = PartyModel.State()
        st.onParty(PartyModel.parseParty(doc)!!, true, 0L)
        val v = PartyModuleView(app, st)
        v.mode = PartyLayout.Mode.REST
        val top = PartyLayout.defaultTop(v.mode)
        val ml = PartyLayout.module(v.mode, st.party!!.n, pet = st.party!!.pet, top = top)
        val b = Bitmap.createBitmap(640, 1080, Bitmap.Config.ARGB_8888)
        val l = PartyLayout.EDGE; val t = top - PartyModuleView.RISE
        v.measure(View.MeasureSpec.makeMeasureSpec((615f - l).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((ml.well.b - t).toInt(), View.MeasureSpec.EXACTLY))
        v.layout(l.toInt(), t.toInt(), 615, ml.well.b.toInt())
        for (pass in 0..1) {
            val c = Canvas(b)
            c.drawColor(Theme.ink)
            UiKit.module(c, RectF(8f, ml.chatTop, 616f, 927f))
            c.save(); c.translate(l, t); v.draw(c); c.restore()
            if (pass == 0) settle()
        }
        return b
    }

    // ---------------------------------------------------------------- the themes

    private fun presetIds(): List<String> = Theme.presets.map { it.id }
    private fun use(id: String) = Theme.use(id)

    @Test fun shots() {
        assumeTrue(out != null)
        artRoot()
        val only = System.getenv("THEME_ONLY")
        val ids = presetIds().filter { only == null || it == only }
        val screens = listOf<Pair<String, () -> Bitmap?>>("home" to ::home, "homebar" to ::homeBar, "controller" to ::controller, "bags" to ::bags,
            "settings" to ::settings, "dialog" to ::dialog, "party" to ::party)
        val made = LinkedHashMap<String, MutableList<Pair<String, Bitmap>>>()
        for (id in ids) {
            use(id)
            for ((s, f) in screens) {
                val b = runCatching { f() }.onFailure { System.err.println("theme shot $id/$s: $it"); it.printStackTrace() }.getOrNull() ?: continue
                save(b, "${id}_$s.png")
                made.getOrPut(s) { ArrayList() }.add(id to b)
            }
        }
        use("classic")
        // the contact sheets: five across, each picture at a third, its name under it
        for ((s, list) in made) {
            if (list.size < 2) continue
            val k = 1f / 3f
            val cw = (list[0].second.width * k).toInt(); val ch = (list[0].second.height * k).toInt()
            val cols = 5; val rows = (list.size + cols - 1) / cols
            val pad = 12; val label = 34
            val sheet = Bitmap.createBitmap(cols * (cw + pad) + pad, rows * (ch + label + pad) + pad, Bitmap.Config.ARGB_8888)
            val c = Canvas(sheet)
            c.drawColor(android.graphics.Color.rgb(40, 40, 40))
            val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 24f }
            for ((n, e) in list.withIndex()) {
                val x = pad + (n % cols) * (cw + pad); val y = pad + (n / cols) * (ch + label + pad)
                c.drawBitmap(e.second, null, android.graphics.Rect(x, y, x + cw, y + ch), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                val nm = Theme.byId(e.first)?.name ?: e.first
                c.drawText(nm, x.toFloat(), (y + ch + 26).toFloat(), tp)
            }
            save(sheet, "sheet_$s.png")
        }
    }
}
