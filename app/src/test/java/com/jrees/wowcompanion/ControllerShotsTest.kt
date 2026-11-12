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

/**
 * THE CONTROLLER ROOM'S OFF-DEVICE RENDER CHECK (p34, the user 2026-11-10: the DS tab folded into WoW Defaults, the chooser's DS tab):
 * the room drawn by Robolectric's native graphics from the add-on's own documents (tools/test_party.py writes them with CTL_DUMP=<dir>).
 * Runs only when CTL_SHOTS_DIR is set (the PNGs and the docs/ folder); CTL_ART_ROOT = an art root laid out like the app's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControllerShotsTest {
    private val out: File? = System.getenv("CTL_SHOTS_DIR")?.let { File(it) }

    private fun artRoot() {
        val root = System.getenv("CTL_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun doc(name: String) = JSONObject(File(out, "docs/$name.json").readText())

    private fun shot(name: String, map: String, ctl: JSONObject) {
        val v = ControllerView(RuntimeEnvironment.getApplication(), { true })
        v.onPadMap(PadMapModel.parse(doc(map))!!)
        v.onPadTabCtl(PadMapModel.parseCtl(ctl)!!)
        val w = 1240; val h = 960
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun ctl(name: String, edit: (JSONObject) -> Unit = {}): JSONObject {
        val d = doc(name)
        edit(d.getJSONObject("c"))
        return d
    }

    @Test
    fun shots() {
        assumeTrue(out != null)
        artRoot()
        shot("ctl_toolbar_on", "padmap_on", ctl("ctl_dleft"))
        shot("ctl_toolbar_off", "padmap_off", ctl("ctl_dleft") { it.remove("note") })
        shot("ctl_chooser_ds", "padmap_off", ctl("ctl_dstool"))
        // p39 (the user on 1428): In combat's chooser with all five tabs, each square
        shot("ctl_chooser_combat_ds", "padmap_combat", ctl("ctl_combat_dstool"))
        // p40 (review of p39): Open Map from In combat on the fight-spell button: the box names both sides
        shot("ctl_combat_whole_box", "padmap_combat", ctl("ctl_combat_dstool") {
            it.put("box", org.json.JSONObject().put("kind", "replace").put("thing", "command").put("what", "DSMAP")) })
        shot("ctl_tabs", "padmap_on", ctl("ctl_dleft") { it.put("focus", "tabs").put("tcur", 3).put("view", "combat") })
        shot("ctl_switch", "padmap_on", ctl("ctl_dleft") { it.put("focus", "switches") })
    }
}
