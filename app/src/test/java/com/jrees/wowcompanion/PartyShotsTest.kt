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
            .put("pt", pt).put("hp", hp).put("full", hp >= 100).put("dead", dead).put("off", off)
            .put("col", when (c) { "SHAMAN" -> "ff0070dd"; "WARRIOR" -> "ffc69b6d"; "MAGE" -> "ff3fc7eb"; "ROGUE" -> "fffff468"
                "PRIEST" -> "ffffffff"; else -> "ffffd100" })

    private val roster = listOf(
        member(0, "Korvash Emberhoof", "SHAMAN", "HEALER", "MANA", 100),
        member(1, "Brannoc Ironhelm", "WARRIOR", "TANK", "RAGE", 72),
        member(2, "Seraphine Duskwhisperer", "MAGE", "DAMAGER", "MANA", 91),
        member(3, "Tamsin Quickfoot", "ROGUE", "DAMAGER", "ENERGY", 0, dead = true),
        member(4, "Odwin Thornbrook", "PRIEST", "HEALER", "MANA", 45),
    )

    private fun party(n: Int, on: Boolean, box: Int, deadOn: Boolean = n == 5): JSONObject {
        val m = org.json.JSONArray()
        val order = if (n == 3) listOf(0, 1, 3) else (0 until n).toList()
        for (k in order) {
            val o = JSONObject(roster[k].toString())
            if (k == 3 && !deadOn) o.put("dead", false).put("hp", 58)
            m.put(o)
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

    /** the module as Home places it (HomeView.withParty): its well's top at [top], the left column x 8..615 */
    private fun module(name: String, mode: PartyLayout.Mode, n: Int, on: Boolean, box: Int, top: Float = PartyLayout.defaultTop(mode)) {
        val st = PartyModel.State()
        st.onParty(PartyModel.parseParty(party(n, on, box))!!, true, 0L)
        val v = PartyModuleView(RuntimeEnvironment.getApplication(), st)
        v.mode = mode
        val m = PartyLayout.module(mode, st.party!!.n, top = top, tagShown = !on)
        val (bmp, c) = page()
        // the chat's well under it, empty: where chat would stand
        UiKit.module(c, android.graphics.RectF(8f, m.chatTop, 616f, 927f))
        draw(c, v, 8f, top - PartyModuleView.RISE, 615f, m.well.b)
        save(bmp, name)
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
}
