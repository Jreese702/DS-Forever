package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
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

/** DESIGN RENDER of the Controller room's Spell radial tab with several radials (PadTabCtl p40): settings, wheel, icon grid, keyboard */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControllerRadialShotsTest {
    private val out: File? = System.getenv("TRADE_SHOTS_DIR")?.let { File(it) }

    private fun artRoot() {
        val root = System.getenv("TRADE_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun ctl(focus: String, revn: Int, rev: String?, rrow: Int = 1, pos: Int = 1, rbox: String? = null) =
        PadMapModel.parseCtl(JSONObject("""{"type":"padtabctl","v":"p40","c":{"on":true,"view":"radial","focus":"$focus","tcur":4,
            "pos":$pos,"rpage":2,"rad":[403,331,324],"rslots":[1,2,4],"rplate":2,"rrow":$rrow,"revn":$revn${rev?.let { ",\"rev\":\"$it\"" } ?: ""}
            ${rbox?.let { ",\"rbox\":\"$it\"" } ?: ""}}}"""))!!

    private fun shot(v: ControllerView, name: String) {
        val b = Bitmap.createBitmap(1240, 935, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Theme.ink)
        v.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(935, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1240, 935)
        v.draw(c)
        out!!.mkdirs()
        File(out, "$name.png").outputStream().use { s -> b.compress(Bitmap.CompressFormat.PNG, 100, s) }
    }

    @Test fun radialTab() {
        assumeTrue(out != null)
        artRoot()
        RadialMeta.init(RuntimeEnvironment.getApplication())
        RadialMeta.who = "Korvash-Realm"
        RadialMeta.setName(2, "Fire")
        val v = ControllerView(RuntimeEnvironment.getApplication(), onPadCells = { true })
        v.onPadMap(PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m105","m":{"page":1,"pages":3,"sets":[]}}"""))!!)
        v.onPadTabCtl(ctl("rset", 5, "mode", rrow = 2))
        shot(v, "controller_radial_settings")
        v.onPadTabCtl(ctl("buttons", 5, "mode", pos = 2))
        shot(v, "controller_radial_wheel")
        v.onPadTabCtl(ctl("rset", 6, "icon", rrow = 2, rbox = "ricon"))
        shot(v, "controller_radial_icons")
        v.onPadTabCtl(ctl("rset", 7, "key:PAD2", rrow = 2))
        v.onPadTabCtl(ctl("rset", 8, "name", rrow = 1, rbox = "rname"))
        shot(v, "controller_radial_name")
    }
}
