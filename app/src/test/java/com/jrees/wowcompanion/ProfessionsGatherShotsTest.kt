package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Issue 87's follow-up (add-on p28): the Professions room's book with its tracking buttons - Find Herbs off and Find Minerals on, then
 * the other way round, then an older character with no tracking (the buttons greyed, no note), a refusal in the header's place, then es / fr. Each as
 * <name>.png under GATHER_SHOTS_DIR; GATHER_ART_ROOT = an art root laid out like the app's (ui/kit, fonts, icons.pak). A render only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfessionsGatherShotsTest {
    private val out: File? = System.getenv("GATHER_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    private fun artRoot() {
        val root = System.getenv("GATHER_ART_ROOT") ?: return
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root))
    }

    private fun doc(herbs: Int?, minerals: Int?): JSONObject {
        fun trk(o: JSONObject, v: Int?, on: Int, off: Int) = if (v == null) o else o.put("trk", v).put("ton", on).put("toff", off)
        val herb = JSONObject().put("pos", 1).put("id", 182).put("name", "Herbalism").put("icon", 136246).put("rank", 112).put("max", 150)
            .put("main", true)
            .put("btn", JSONArray().put(trk(JSONObject().put("n", "Find Herbs").put("icon", 133939), herbs, 2, 3)))
        val mining = JSONObject().put("pos", 2).put("id", 186).put("name", "Mining").put("icon", 136248).put("rank", 98).put("max", 150)
            .put("main", true).put("craft", true).put("oslot", 4)
            .put("btn", JSONArray().put(JSONObject().put("n", "Smelting").put("icon", 135811).put("rec", true).put("o", true))
                .put(trk(JSONObject().put("n", "Find Minerals").put("icon", 136025), minerals, 5, 6)))
        val cooking = JSONObject().put("pos", 5).put("id", 185).put("name", "Cooking").put("icon", 133971).put("rank", 40).put("max", 75)
            .put("craft", true)
        return JSONObject().put("type", "prof").put("v", "p28").put("list", JSONArray().put(herb).put(mining).put(cooking))
            .put("ctl", JSONObject().put("forget:1", 33).put("forget:2", 34))
            .put("pad", JSONObject().put("rows", JSONObject().put("prof", 1).put("profsel", 2).put("profopen", 3)))
    }

    private fun lay(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, W, H)
    }

    private fun shot(name: String, d: JSONObject, lang: String = "en", ack: JSONObject? = null) {
        Tr.setChoice(app, lang)
        ProfessionsStore.onProf(d, System.currentTimeMillis())
        val v = ProfessionsView(app)
        lay(v); ShadowLooper.idleMainLooper(); lay(v)
        ack?.let { v.onAck(it) }
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Theme.ink); v.draw(c)
        out!!.mkdirs()
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun gatherShots() {
        assumeTrue(out != null)
        artRoot(); Tr.init(app); Settings.init(app)
        shot("book_herbs_off_minerals_on", doc(0, 1))
        shot("book_herbs_on_minerals_off", doc(1, 0))
        shot("book_no_tracking_greyed", doc(null, null))
        shot("book_refused_in_a_fight", doc(1, 0), ack = JSONObject().put("op", "gather").put("ok", false).put("why", "not in a fight"))
        shot("book_es", doc(1, 0), "es")
        shot("book_fr", doc(1, 0), "fr")
        Tr.setChoice(app, "auto")
    }
}
