package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import org.json.JSONObject
import java.util.Collections
import java.util.WeakHashMap

/**
 * The map's NPC icons as harvested on the device (MapHarvestSteps.NPC_ICONS: ui/npc_icons.png, one 64 px cell a key in
 * ui/npc_icons.json's "keys"); keys from [NpcIcons]. Shared by Home's map (MapView) and the Map room's "Show: services". Decoded
 * OFF the UI thread (D's report 2026-10-13: no decode on the draw path), asked for at most every 3 s until it is there, and the
 * views that asked are redrawn when it lands; [draw] is false until then, so the caller can draw its old stand-in.
 */
object NpcSheet {
    @Volatile private var sheet: Bitmap? = null
    @Volatile private var index: Map<String, Int> = emptyMap()
    @Volatile private var busy = false
    private var lastTry = 0L
    private val waiting: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private val src = Rect()
    private val dst = RectF()

    private fun load(view: View?): Bitmap? {
        sheet?.let { return it }
        if (view != null) synchronized(waiting) { waiting.add(view) }
        val now = System.currentTimeMillis()
        if (busy || now - lastTry < 3000L) return null
        lastTry = now
        busy = true
        Thread {
            try { decode() } finally { busy = false }
            if (sheet != null) synchronized(waiting) { waiting.forEach { it.postInvalidate() }; waiting.clear() }
        }.apply { isDaemon = true }.start()
        return null
    }

    private fun decode() {
        val png = ArtHarvest.file("ui/npc_icons.png")
        val js = ArtHarvest.file("ui/npc_icons.json")
        if (!png.isFile || !js.isFile) return
        val keys = runCatching { JSONObject(js.readText()).optJSONArray("keys") }.getOrNull() ?: return
        val m = HashMap<String, Int>()
        for (i in 0 until keys.length()) keys.optString(i, null)?.let { m[it] = i }
        val b = runCatching { BitmapFactory.decodeFile(png.path) }.getOrNull() ?: return
        index = m
        sheet = b
    }

    fun has(key: String): Boolean = load(null) != null && index.containsKey(key)

    /** [key]'s icon centred on ([cx], [cy]) at [size] px; false when the strip (or the key) is not there yet ([view] redraws when it is) */
    fun draw(c: Canvas, key: String, cx: Float, cy: Float, size: Float, paint: Paint, view: View? = null): Boolean {
        val b = load(view) ?: return false
        val i = index[key] ?: index["generic"] ?: return false
        val cell = b.height
        src.set(i * cell, 0, (i + 1) * cell, cell)
        val h = size / 2f
        dst.set(cx - h, cy - h, cx + h, cy + h)
        c.drawBitmap(b, src, dst, paint)
        return true
    }
}
