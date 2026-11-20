package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Issue #87 (the user's ruling, 2026-11-19: "accept that the live dots are gone"): the real MapView, Home's full map, its filter open on
 * the Gathering & rares tab: Rares and Sparkles are its switches, and under them the one terse line (MinimapTracking.ADVICE) where the
 * live dots' switches were; Live dots, Auto-switch and Chests are not drawn. A kept "mapprobe" value from an older build asks the
 * add-on for Sparkles only, re-sent when the map snapshot's `mpo` (Map.lua mp3) says otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MapFilterTrackingLineTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private class Rec(b: Bitmap) : Canvas(b) {
        val texts = ArrayList<String>()
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) { texts += text; super.drawText(text, x, y, paint) }
    }

    private fun doc(mpo: Int? = null) = JSONObject("""{"type":"map","ok":true,"mapID":1426,"zone":"Dun Morogh","subzone":"","x":0.5,"y":0.5,
        "cont":0,"wx":-5500.0,"wy":0.0,"facing":0.1,"rect":{"c":0,"t":-3500.0,"b":-7500.0,"l":2000.0,"r":-2000.0}}""")
        .also { if (mpo != null) it.put("mpo", mpo) }

    private fun set(v: MapView, name: String, value: Any) {
        val f = MapView::class.java.getDeclaredField(name); f.isAccessible = true; f.set(v, value)
    }

    private fun bigMap(): MapView = MapView(app).apply {
        update(doc(), 0)
        measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY))
        layout(0, 0, 1080, 1240)
    }

    @Test fun theGatheringTabSaysTheLineInsteadOfTheLiveDotsSwitches() {
        val v = bigMap()
        Rec(Bitmap.createBitmap(1080, 1240, Bitmap.Config.ARGB_8888)).also { v.draw(it) }   // the field measured once
        set(v, "filterOpen", true)
        set(v, "filterTab", 2)
        val rec = Rec(Bitmap.createBitmap(1080, 1240, Bitmap.Config.ARGB_8888)).also { v.draw(it) }
        assertTrue("the line drawn: ${rec.texts}", rec.texts.contains(Tr.t(MinimapTracking.ADVICE)))
        assertTrue(rec.texts.contains(Tr.t("Rares")))
        assertTrue(rec.texts.contains(Tr.t("Sparkles")))
        for (gone in listOf("Live dots", "Auto-switch", "Chests")) assertFalse("'$gone' not drawn", rec.texts.contains(gone))
    }

    @Test fun anOldKeptValueAsksSparklesOnlyWhenTheAddOnDiffers() {
        app.getSharedPreferences("mapprobe", Context.MODE_PRIVATE).edit().putInt("opts", 5).commit()   // probe + sparkles (an older build)
        val asked = ArrayList<Int>()
        val v = MapView(app).apply { onProbeOptions = { asked.add(it) } }
        v.update(doc(mpo = 4), 0)
        assertEquals("the add-on already has Sparkles: nothing sent", emptyList<Int>(), asked)
        v.update(doc(mpo = 0), 0)
        assertEquals("a reloaded add-on (opt 0): Sparkles asked again, the old probe bit dropped", listOf(4), asked)
        v.update(doc(), 0)
        assertEquals("no mpo (an older add-on): nothing more", listOf(4), asked)
        app.getSharedPreferences("mapprobe", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
