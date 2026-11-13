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
 * m155 / p51 STICK TILES, THE OFF-DEVICE RENDER (the user, 2026-11-13: "L3 and R3 should show the wow default options on them by
 * default, I also want those to be slightly smaller and moved away from the other two cross's to make it clear what they are."): the
 * Controller tab drawn by Robolectric's native graphics from the add-on's own documents (tools/test_stick_binds.py writes them with
 * STICK_DUMP=<dir>/docs). Runs only when STICK_SHOTS_DIR is set (the PNGs go there, its docs/ folder is read); STICK_SHOTS_TAG names
 * them ("before" / "after"); STICK_ART_ROOT = an art root laid out like the app's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StickTilesShotsTest {
    private val out: File? = System.getenv("STICK_SHOTS_DIR")?.let { File(it) }
    private val tag = System.getenv("STICK_SHOTS_TAG") ?: "shot"

    private fun artRoot() {
        val root = System.getenv("STICK_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun doc(name: String) = JSONObject(File(out, "docs/$name.json").readText())

    private fun shot(name: String) {
        val v = ControllerView(RuntimeEnvironment.getApplication(), { true })
        v.onPadMap(PadMapModel.parse(doc("${name}_padmap"))!!)
        v.onPadTabCtl(PadMapModel.parseCtl(doc("${name}_ctl"))!!)
        val w = 1240; val h = 960
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        File(out, "${tag}_$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun shots() {
        assumeTrue(out != null)
        artRoot()
        shot("sticks_game")                 // nothing of ours on L3 / R3: the game's own (L3 Walk / Run, R3 a spell binding)
        shot("sticks_bound")                // Auto Run (ours) on L3, R3 still the game's
    }
}
