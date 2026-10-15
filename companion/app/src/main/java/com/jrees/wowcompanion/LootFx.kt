package com.jrees.wowcompanion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * THE LOOT WINDOW'S FEEDBACK, ITS TIMINGS (f-loot-feedback; the user, 15 Oct: "I really want to have button feedback on looting
 * things, kind of flashing the Item and sparkling away"). Pure Kotlin, so the curve can be checked off the device (LootFxTest);
 * InteractView draws with it. Times in ms from the moment the game cleared the slot.
 *   0 .. FLASH_MS      the card bright: the cast bar's full glow added over it
 *   0 .. VANISH_MS     the card fades and shrinks toward its icon (gone at CARD_MS); the icon's glow swells and goes;
 *                      [N] flakes and sparkles fly out and up from the icon, turning, and fade
 *   SLIDE_MS           the cards under it slide up into its place
 *   SHAKE_MS           a refused loot: the card shakes sideways, a red wash fading
 */
object LootFx {
    const val FLASH_MS = 160L
    const val CARD_MS = 380L
    const val VANISH_MS = 720L
    const val SLIDE_MS = 240L
    const val SHAKE_MS = 360L
    /** the burst over the icon (UiKit.deleteBurst, Make Space's own) takes this long */
    const val BURST_MS = 520L
    const val N = 6
    /** the harvested flakes, by turn (CastView's own names; ui/cast) */
    val PIECES = listOf("castbar_flakes1", "castbar_flakes2", "castbar_flakes3")

    /** the app's curve, out (fast, then settling) */
    fun ease(x: Float): Float { val k = 1f - x.coerceIn(0f, 1f); return 1f - k * k * k }

    fun flashAlpha(t: Long): Int = if (t >= FLASH_MS) 0 else (255 * (1f - t.toFloat() / FLASH_MS)).toInt()
    fun cardAlpha(t: Long): Float = (1f - t.toFloat() / CARD_MS).coerceIn(0f, 1f)
    fun cardScale(t: Long): Float = 1f - 0.18f * ease(t.toFloat() / CARD_MS)
    fun glowAlpha(t: Long): Int {
        val x = t.toFloat() / (VANISH_MS * 0.6f)
        return if (x >= 1f) 0 else (230 * (if (x < 0.25f) x / 0.25f else 1f - (x - 0.25f) / 0.75f)).toInt().coerceIn(0, 255)
    }
    fun glowSize(t: Long): Float = 40f + 50f * ease(t.toFloat() / (VANISH_MS * 0.6f))

    class Piece(val dx: Float, val dy: Float, val size: Float, val alpha: Int, val turn: Float)

    /**
     * Piece [k] of a sparkle (seeded by the slot [seed], so two items never sparkle alike) at [t]: out along its angle and up, slowing;
     * each starts a little after the one before. null before it starts or after it is gone.
     */
    fun piece(k: Int, seed: Int, t: Long): Piece? {
        val start = 40L + k * 22L
        val life = VANISH_MS - start
        if (t < start || t >= VANISH_MS) return null
        val x = (t - start).toFloat() / life
        val h = ((seed * 7919 + k * 104729) and 0xffff) / 65535f        // a fixed per-piece number in 0..1
        val ang = (k * (2 * PI / N) + h * 0.9).toFloat()
        val dist = (40f + 50f * h) * ease(x)
        val dx = cos(ang) * dist
        val dy = sin(ang) * dist * 0.5f - 90f * ease(x)                   // they rise, as the cast bar's flakes do
        val size = (46f + 24f * h) * (1f - 0.4f * x)
        val alpha = (255 * (if (x < 0.15f) x / 0.15f else 1f - (x - 0.15f) / 0.85f)).toInt().coerceIn(0, 255)
        return Piece(dx, dy, size, alpha, (h * 360f + x * 180f))
    }

    /** a refused loot's sideways shake (px) and its red wash (alpha) */
    fun shake(t: Long): Float {
        if (t >= SHAKE_MS) return 0f
        val x = t.toFloat() / SHAKE_MS
        return (sin(x * PI * 6) * 14.0 * (1f - x)).toFloat()
    }
    fun shakeTint(t: Long): Int = if (t >= SHAKE_MS) 0 else (110 * (1f - t.toFloat() / SHAKE_MS)).toInt()
}
