package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Bitmap

/**
 * THE ONE MARKER LOOK (the user on test65: "can we make the checkpoint markers consistent on all areas?"; Interact's cp27 art spec,
 * E:/safepath-work/design/prd/out/marker_art_spec.md): the add-on's own pictures, read from the APK's assets so the bottom map draws
 * exactly what the world and the compass draw - the route CHECKPOINT pin (white-shaded, tinted gold [PIN_GOLD], a black rim under it)
 * and the QUEST OBJECTIVE disc (already coloured; its number in [DISC_NUMBER]). [decode] is pure (tested); [pin] / [disc] load once.
 */
object MarkerArt {
    const val PIN = "companion/addons/WowCompanion/Media/marker_pin.tga"
    const val DISC = "companion/addons/WowCompanion/Media/quest_disc.tga"
    /** the spec's colours: the pin's gold #FFD14D, the disc's number #291700 */
    const val PIN_GOLD = 0xFFFFD14D.toInt()
    const val DISC_NUMBER = 0xFF291700.toInt()
    /** the spec's ranks: the second of the two at this size and alpha; the nearer fades from [FADE_FAR_YD] to [FADE_NEAR_YD] */
    const val SECOND_SIZE = 0.8f
    const val SECOND_ALPHA = 0.75f
    const val FADE_FAR_YD = 45.0
    const val FADE_NEAR_YD = 25.0                 // DangerGrid.PASS_YD: gone exactly when it is passed

    /** the nearer checkpoint's alpha at [yd] from the player: full at [FADE_FAR_YD] and beyond, 0 at [FADE_NEAR_YD] */
    fun nearAlpha(yd: Double): Float = ((yd - FADE_NEAR_YD) / (FADE_FAR_YD - FADE_NEAR_YD)).coerceIn(0.0, 1.0).toFloat()

    /** a TGA's pixels as ARGB, top row first: uncompressed true colour (type 2), 32 or 24 bit; null for anything else */
    fun decode(b: ByteArray): Triple<Int, Int, IntArray>? {
        if (b.size < 18) return null
        val idLen = b[0].toInt() and 0xFF; val cmap = b[1].toInt() and 0xFF; val type = b[2].toInt() and 0xFF
        val w = (b[12].toInt() and 0xFF) or ((b[13].toInt() and 0xFF) shl 8); val h = (b[14].toInt() and 0xFF) or ((b[15].toInt() and 0xFF) shl 8)
        val bpp = b[16].toInt() and 0xFF; val desc = b[17].toInt() and 0xFF
        if (cmap != 0 || type != 2 || (bpp != 32 && bpp != 24) || w <= 0 || h <= 0) return null
        val px = bpp / 8; var o = 18 + idLen
        if (b.size < o + w * h * px) return null
        val topDown = desc and 0x20 != 0
        val out = IntArray(w * h)
        for (row in 0 until h) {
            val y = if (topDown) row else h - 1 - row
            for (x in 0 until w) {
                val bl = b[o].toInt() and 0xFF; val g = b[o + 1].toInt() and 0xFF; val r = b[o + 2].toInt() and 0xFF
                val a = if (px == 4) b[o + 3].toInt() and 0xFF else 255
                out[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or bl
                o += px
            }
        }
        return Triple(w, h, out)
    }

    @Volatile private var pinBmp: Bitmap? = null
    @Volatile private var discBmp: Bitmap? = null
    @Volatile private var tried = false

    private fun load(ctx: Context, path: String): Bitmap? = runCatching {
        val (w, h, px) = decode(ctx.assets.open(path).use { it.readBytes() }) ?: return null
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    private fun ensure(ctx: Context) {
        if (tried) return
        synchronized(this) { if (tried) return; pinBmp = load(ctx, PIN); discBmp = load(ctx, DISC); tried = true }
    }
    /** the checkpoint pin (white-shaded: tint it), null when the asset could not be read */
    fun pin(ctx: Context): Bitmap? { ensure(ctx); return pinBmp }
    /** the quest objective's disc (coloured), null when the asset could not be read */
    fun disc(ctx: Context): Bitmap? { ensure(ctx); return discBmp }
    /** the disc when it is already loaded (UiKit draws without a Context: MapView loads it first) */
    fun discLoaded(): Bitmap? = discBmp
}
