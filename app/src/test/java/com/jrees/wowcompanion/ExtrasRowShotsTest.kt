package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
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
 * THE EXTRAS ROW, EVERY COUNT (feat/extras-polish; the user, 2026-11-23: "The badges on the home page when there are 3 for hearth bags and
 * ammo are not centered and look like poop when I use 3 with the ammo"): the whole Home page at rest (1240 x 1080) with 1 .. 5 extras on,
 * the user's case (the Hearthstone ready = no value, the bags "used/total", the ammo "used/total" with a quiver), grown (at rest) and small
 * (LB + RB held), chat / map swapped; each <tag>_<name>.png and the band alone, 2x, <tag>_<name>_band.png.
 *
 * Runs only when EXTRAS_SHOTS_DIR is set; CLOCK_ART_ROOT = an art root laid out like the app's. Written against reflection for what only
 * the new code has (the pet happiness extra), so the same file draws the before pictures on the old code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExtrasRowShotsTest {
    private val out: File? = System.getenv("EXTRAS_SHOTS_DIR")?.let { File(it) }
    private val tag = System.getenv("EXTRAS_SHOTS_TAG") ?: "after"
    private val app get() = RuntimeEnvironment.getApplication()

    /** the art root before [artRoot] (a static: put back after the test, so no later class draws with this one's art) */
    private val artWas: File? = ArtHarvest.rootDir
    @After fun down() { ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, artWas) }

    private fun artRoot() {
        val root = System.getenv("CLOCK_ART_ROOT") ?: return
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root))
    }

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun settle() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        Thread.sleep(250)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    private fun setField(o: Any, name: String, v: Any) {
        val f = o.javaClass.getDeclaredField(name); f.isAccessible = true; f.set(o, v)
    }

    /** a HomeSettings switch by its property name; false = this code has no such row (the before pictures) */
    private fun setting(name: String, on: Boolean): Boolean {
        val m = HomeSettings::class.java.methods.firstOrNull { it.name == "set" + name.replaceFirstChar { c -> c.uppercase() } } ?: return false
        m.invoke(HomeSettings, on); return true
    }

    private fun save(b: Bitmap, name: String) {
        val d = out ?: return
        d.mkdirs()
        File(d, "${tag}_$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val band = Bitmap.createBitmap(b, 300, 0, 640, 240)
        Bitmap.createScaledBitmap(band, 1280, 480, true).let { s ->
            File(d, "${tag}_${name}_band.png").outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private class Page(val home: HomeView, val cast: CastView)

    private fun page(layout: HomeLayout = HomeLayout()): Page {
        HomeSettings.init(app)
        HomeLayoutStore.character(null)
        HomeLayoutStore.set(layout)
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        val h = 1080 - Strip.HEIGHT
        lay(home, 1240, h)
        val icons = listOf(136048, 136026, 135990, 136052, 135813, 136015, 135824, 136075)
        val n = JSONArray()
        for (p in 1..8) n.put(JSONObject().put("p", p).put("k", "spell").put("id", 400 + p).put("i", icons[p - 1]))
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", n))))
        home.tick()
        val look = HomeView.Companion.Look(HomeRest.DIAMONDS, false, false, false)
        HomeView::class.java.methods.firstOrNull { it.name.startsWith("previewSettle") }?.invoke(home, look)
        lay(home, 1240, h)
        return Page(home, cast)
    }

    /** the bags as the user has them: 4 bags (60 / 66 used), a 16-slot quiver (stack 200: "/3200"), 1843 arrows worn */
    private fun feed(p: Page) {
        val eq = JSONObject().put("slots", JSONArray())
            .put("ammo", JSONObject().put("id", 2512).put("icon", 132382).put("count", 1843).put("stack", 200)).put("ammoNeeded", true)
        val bags = JSONArray()
            .put(JSONObject().put("bag", 0).put("slots", 16).put("free", 2))
            .put(JSONObject().put("bag", 1).put("slots", 18).put("free", 1))
            .put(JSONObject().put("bag", 2).put("slots", 16).put("free", 2))
            .put(JSONObject().put("bag", 3).put("slots", 16).put("free", 1))
            .put(JSONObject().put("bag", 4).put("slots", 16).put("free", 0).put("fam", 1).put("icon", 134407))
        p.home.onFull(JSONObject().put("type", "full")
            .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true)))
            .put("bags", JSONObject().put("money", 1_234_567L).put("bags", bags).put("equipped", eq)), true)
        setField(p.home, "clockText", "10:42 AM"); setField(p.home, "batteryPct", 76); setField(p.home, "charging", false)
    }

    private fun draw(p: Page): Bitmap {
        val strip = Strip(app) { }
        val b = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            lay(p.home, 1240, 1080 - Strip.HEIGHT)
            val c = Canvas(b)
            c.drawColor(Theme.ink)
            p.home.draw(c)
            strip.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Strip.HEIGHT, View.MeasureSpec.EXACTLY))
            strip.layout(0, 0, 1240, Strip.HEIGHT)
            c.save(); c.translate(0f, (1080 - Strip.HEIGHT).toFloat()); strip.draw(c); c.restore()
            if (pass == 0) settle()
        }
        return b
    }

    /** LB + RB held: the block small (today's size before the grow) */
    private fun shoulders(p: Page) {
        val m = CastView::class.java.getDeclaredMethod("onPad", Boolean::class.java, Boolean::class.java, Boolean::class.java, Boolean::class.java)
        m.isAccessible = true
        m.invoke(p.cast, false, false, true, true)
        ShadowSystemClock.advanceBy(Duration.ofMillis(400))
    }

    private fun items(hearth: Boolean, tele: Boolean, bags: Boolean, ammo: Boolean, pet: Boolean = false) {
        app.getSharedPreferences("home", 0).edit().clear().commit()
        HomeSettings.init(app)
        HomeSettings.clockHearth = hearth; HomeSettings.clockTeleport = tele; HomeSettings.clockGold = true; HomeSettings.clockBags = bags
        setting("clockAmmo", ammo)
        setting("clockPet", pet)
    }

    /** the itemcd doc: the Hearthstone ready or cooling, Astral Recall cooling, the pet's happiness [pet] (1..3; null = no pet) */
    private fun itemcd(cooling: Boolean, pet: Int? = null) {
        ItemCd.reset()
        val t = if (cooling) """"t":{"6948":{"d":1800,"l":754}},""" else ""
        val pt = if (pet != null) ""","pet":{"h":$pet}""" else ""
        ItemCd.onDoc(JSONObject("""{"type":"itemcd",$t"hs":{"have":true,"i":134414},"tp":{"id":556,"n":"Astral Recall","i":136010,"cd":{"d":900,"l":512}}$pt}"""))
    }

    private fun shot(name: String, layout: HomeLayout = HomeLayout(), small: Boolean = false) {
        val p = page(layout); feed(p)
        if (small) shoulders(p)
        save(draw(p), name)
    }

    @Test fun shots() {
        assumeTrue(out != null)
        artRoot()
        // THE USER'S CASE: the Hearthstone (ready: no value), the bags "60/66", the ammo "1843/3200" (a quiver worn)
        items(true, false, true, true); itemcd(false)
        shot("user_hearth_bags_ammo")
        shot("user_hearth_bags_ammo_small", small = true)
        shot("user_hearth_bags_ammo_swapped", HomeLayout().swapped())
        // every count, 1 .. 4 (the Hearthstone ready, the teleport cooling)
        items(true, false, false, false); itemcd(false); shot("n1_hearth")
        items(false, false, false, true); itemcd(false); shot("n1_ammo")
        items(true, false, false, true); itemcd(false); shot("n2_hearth_ammo")
        items(false, false, true, true); itemcd(false); shot("n2_bags_ammo")
        items(true, true, true, true); itemcd(false); shot("n4_all")
        shot("n4_all_small", small = true)
        items(true, true, true, true); itemcd(true); shot("n4_all_hearth_cooling")
        // the pet happiness extra (new code only: an old one draws the 4 above again)
        items(true, true, true, true, pet = true); itemcd(false, 3); shot("n5_pet_happy")
        shot("n5_pet_happy_small", small = true)
        items(true, false, true, true, pet = true); itemcd(false, 2); shot("n4_pet_content")
        items(true, false, false, false, pet = true); itemcd(false, 1); shot("n2_pet_unhappy")
        items(true, false, true, true, pet = true); itemcd(false, null); shot("pet_on_no_pet")
        ItemCd.reset()
    }
}
