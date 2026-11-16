package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
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

/**
 * bags-de1 REGRESSION GUARD (the Disenchant grid's design, "Home's cast-bar renders must be pixel-identical before and after the
 * extraction"): Home's own cast bar - a cast at rest, a cast in a fight (the bar along the top edge, its name plate), a channel, the
 * finish flash and the interrupt flash - drawn off-device by the real [HomeView] + [CastView] from made-up `hudS` documents. Uses only
 * what CastView had before the Disenchant grid's painter entry point, so the same file runs on the base commit; the two sets of PNGs are
 * compared pixel for pixel outside the test. Runs only when CASTGUARD_SHOTS_DIR is set; CASTGUARD_ART_ROOT = an art root laid out like
 * the app's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CastBarGuardShotsTest {
    private val out: File? = System.getenv("CASTGUARD_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private val G = 5000.0

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun page(): Pair<HomeView, CastView> {
        HomeSettings.init(app)
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        lay(home, W, H)
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        home.tick()
        ShadowLooper.idleMainLooper()
        lay(home, W, H)
        return home to cast
    }

    private fun state(fight: Boolean, cs: JSONObject?, ce: JSONObject?): JSONObject {
        val s = JSONObject().put("g", G).put("c", fight)
        if (cs != null) s.put("cs", cs)
        if (ce != null) s.put("ce", ce)
        return s
    }

    /** Home's band at rest is drawn by Home only with a live map: the band itself here (CastView as Home lays it at rest) */
    private fun restShot(name: String, cs: JSONObject?, ce: JSONObject?, waitMs: Long) {
        val d = out ?: return
        d.mkdirs()
        val (home, cast) = page()
        val ab = JSONObject().put("hud", JSONObject().put("ok", true)).put("hudS", state(false, cs, ce))
        home.onFull(JSONObject().put("type", "full").put("actionbar", ab), true)
        cast.update(ab)
        home.tick(); ShadowLooper.idleMainLooper()
        lay(home, W, H)
        if (waitMs > 0) ShadowSystemClock.advanceBy(Duration.ofMillis(waitMs))
        // Home at rest draws its band only with a live map: the borrowed band itself (Home's band layout), at rest's flags
        cast.bottomLine = true; cast.castOnly = true; cast.castTop = false
        lay(cast, W, 400)
        val w = W
        val h = 400
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            val c = Canvas(bm); c.drawColor(Theme.ink)
            cast.draw(c)
            if (pass == 0) { ShadowLooper.idleMainLooper(); Thread.sleep(150); ShadowLooper.idleMainLooper() }
        }
        File(d, "$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shot(name: String, fight: Boolean, cs: JSONObject?, ce: JSONObject?, waitMs: Long) {
        val d = out ?: return
        d.mkdirs()
        val (home, cast) = page()
        val ab = JSONObject().put("hud", JSONObject().put("ok", true)).put("hudS", state(fight, cs, ce))
        home.onFull(JSONObject().put("type", "full").put("actionbar", ab), true)
        cast.update(ab)
        home.tick(); ShadowLooper.idleMainLooper()
        lay(home, W, H)
        if (waitMs > 0) ShadowSystemClock.advanceBy(Duration.ofMillis(waitMs))
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            val c = Canvas(bm); c.drawColor(Theme.ink)
            home.draw(c)
            if (pass == 0) { ShadowLooper.idleMainLooper(); Thread.sleep(150); ShadowLooper.idleMainLooper() }
        }
        File(d, "$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun shots() {
        if (out == null) return
        System.getenv("CASTGUARD_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
        Tr.init(app)
        fun cast(k: String, s: Double, e: Double, nm: String) = JSONObject().put("k", k).put("s", s).put("e", e).put("nm", nm)
        restShot("rest_cast", cast("c", G - 1.0, G + 2.0, "Disenchant"), null, 0)
        shot("fight_cast", true, cast("c", G - 1.5, G + 1.5, "Shadow Bolt"), null, 0)
        restShot("rest_channel", cast("h", G - 2.0, G + 3.0, "Arcane Missiles"), null, 0)
        restShot("rest_finish", null, JSONObject().put("x", 1).put("t", G), 120)
        restShot("rest_interrupt", null, JSONObject().put("x", 0).put("t", G), 200)
        shot("fight_finish", true, null, JSONObject().put("x", 1).put("t", G), 60)
        shot("fight_channel", true, cast("h", G - 1.0, G + 4.0, "Drain Life"), null, 0)
    }
}
