package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LightingColorFilter
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.LinearGradient
import android.graphics.Rect
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.widget.TextView
import java.io.File

/**
 * The shared Classic look: the game's OWN fonts, buttons, parchment and borders, so that every
 * room reads as part of the game and as part of the same app. SHARED: any room may call it.
 * The art session owns this file; ask in AGENT_LOG for changes.
 *
 * THE LOOK (user rulings, 2026-09-27): dark flat ground and hairline dividers, as before.
 * Morpheus for titles and section headings, Friz Quadrata for everything else, Arial Narrow for
 * numbers. Parchment ONLY where prose is read, inside the game's gold border; names, status and
 * objectives stay on the dark ground in their colours. Every action is the game's red button.
 *
 * WHERE THE ART COMES FROM: `wow-companion/tools/icons/extract_kit.py` builds it on the PC from
 * the user's own client, and it is pushed ONCE:
 *
 *   adb push ui_kit/fonts/. /sdcard/WowCompanion/fonts/
 *   adb push ui_kit/kit/.   /sdcard/WowCompanion/ui/kit/
 *
 * It lives on the device, NOT in the APK, like `icons.pak`. A missing kit is not an error:
 * fonts fall back to the system's, and every draw call falls back to the plain flat look.
 */
object UiKit {
    /** where the kit was pushed. The preview harness points this at its own folder. */
    @Volatile var root: File = File("/sdcard/WowCompanion")
        set(v) { field = v; forget() }

    enum class Button { UP, DOWN, DISABLED }

    // ---------------------------------------------------------------- type

    /** Morpheus: room titles and section headings */
    val title: Typeface get() = font("morpheus.ttf", Typeface.SERIF)

    /** Friz Quadrata, the game's UI font: names, body text, labels */
    val body: Typeface get() = font("frizqt__.ttf", Typeface.DEFAULT)

    /** Arial Narrow: numbers, distances, counts, chat */
    val number: Typeface get() = font("arialn.ttf", Typeface.DEFAULT)

    fun titlePaint(size: Float, colour: Int = Theme.gold, shadow: Boolean = true) = paint(title, size, colour, shadow)
    fun bodyPaint(size: Float, colour: Int = Theme.text, shadow: Boolean = true) = paint(body, size, colour, shadow)
    fun numberPaint(size: Float, colour: Int = Theme.text, shadow: Boolean = true) = paint(number, size, colour, shadow)

    /** text for parchment: dark ink, and no shadow (the game draws none on paper) */
    fun inkPaint(size: Float, heading: Boolean = false) =
        paint(if (heading) title else body, size, Theme.parchmentInk, false)

    /**
     * A room's OWN text paint set in the game's type, for a view that keeps its paints. Call it
     * where the paint is used (the top of onDraw, onMeasure), not where it is built: a paint built
     * before the kit is on the device would keep the fallback font. The game's type is not bold,
     * so bold goes. Without the font on the device the paint is left exactly as the room made it.
     */
    fun asTitle(p: Paint, shadow: Boolean = true) = face(p, "morpheus.ttf", shadow)
    fun asBody(p: Paint, shadow: Boolean = true) = face(p, "frizqt__.ttf", shadow)
    fun asNumber(p: Paint, shadow: Boolean = true) = face(p, "arialn.ttf", shadow)

    /** the same for a `TextView` or an `EditText` */
    fun asTitle(v: TextView, shadow: Boolean = true) = face(v, "morpheus.ttf", shadow)
    fun asBody(v: TextView, shadow: Boolean = true) = face(v, "frizqt__.ttf", shadow)
    fun asNumber(v: TextView, shadow: Boolean = true) = face(v, "arialn.ttf", shadow)

    private fun face(p: Paint, file: String, shadow: Boolean) {
        val t = game(file) ?: return
        p.typeface = t
        p.isFakeBoldText = false
        if (shadow) p.setShadowLayer(1f, p.textSize / 18f, p.textSize / 18f, Color.BLACK)
    }

    private fun face(v: TextView, file: String, shadow: Boolean) {
        val t = game(file) ?: return
        if (v.typeface !== t) v.typeface = t
        if (shadow) v.setShadowLayer(1f, v.textSize / 18f, v.textSize / 18f, Color.BLACK)
    }

    /** the game's font, or null while it is not on the device */
    private fun game(file: String): Typeface? {
        font(file, Typeface.DEFAULT)
        return fonts[file]
    }

    // TextPaint, so a room can hand it straight to a StaticLayout
    private fun paint(face: Typeface, size: Float, colour: Int, shadow: Boolean) =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = face; textSize = size; color = colour
            // the game's own text shadow: a hard black offset, not a glow
            if (shadow) setShadowLayer(1f, size / 18f, size / 18f, Color.BLACK)
        }

    // ---------------------------------------------------------------- textures

    /** a kit texture by the short name `extract_kit.py` writes, or null when it is not on the device */
    fun tex(name: String): Bitmap? {
        texCache[name]?.let { return it }
        if (!retry(name)) return null
        val f = ArtHarvest.file("ui/kit/$name.png")          // the harvested kit (app-private), else the pushed one
        val bmp = if (f.canRead()) BitmapFactory.decodeFile(f.path) else null
        if (bmp != null) { texCache[name] = bmp; missing.remove(name) }
        return bmp
    }

    /**
     * A small picture the game names by fileDataID and that `icons.pak` does not hold (it is
     * interface/icons only): today, what an NPC's gossip options carry (vendor coins, trainer
     * book, the quest marks). Ask `Icons.get(fileID)` first and this second. null when unknown.
     * These are 16 px art: draw them with [pixel], or they blur.
     */
    fun icon(fileID: Int?): Bitmap? = if (fileID == null || fileID <= 0) null else tex("fid_$fileID")

    /** draws small art enlarged with its pixels kept square, the way the game shows it */
    val pixel = Paint()

    /** true when the kit's art is on the device (the fonts and the button, at least) */
    fun ready(): Boolean = tex("button_up_center") != null && ArtHarvest.file("fonts/frizqt__.ttf").canRead()

    // ---------------------------------------------------------------- pieces

    /**
     * The game's red button with [label] centred on it, at any size. DISABLED is the game's
     * grey plate.
     */
    fun button(c: Canvas, r: RectF, label: String, state: Button = Button.UP,
               textSize: Float = r.height() * 0.34f, waiting: Boolean = false, words: Int? = null) {
        pressed(c, r, state == Button.DOWN || waiting) {
            buttonPlate(c, r, state)
            buttonLabel(c, r, label, state, textSize, r.left + 24f, if (waiting) r.right - r.height() * 0.9f else r.right - 24f, words)
        }
        if (waiting) waitOnButton(c, r)
    }

    /**
     * THE PRESS, ONE LOOK FOR EVERY PRESSABLE PIECE (the user, 2026-10-07, after Interact's pop-ups: "Popups working, all other
     * context buttons should have similar feedback"): the game's DOWN art moves a plate by two pixels only, which does not read as a
     * press; a pressed piece is also pushed IN: drawn at [PRESS_SCALE] of its size round its centre, a little darker (Interact's
     * PressLook, now the kit's). [button], [iconButton], [square], [rollButton] and [trackerButton] do it themselves when DOWN.
     */
    const val PRESS_SCALE = 0.94f
    private val pPress = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.78f, 0.78f, 0.78f, 1f) }) }

    /**
     * A PRESS WAITING FOR THE GAME'S ANSWER (the user, 2026-10-07: "we might need some kind of visual for the delay though, to stop the
     * player from spamming after the press"; pick "B: the game's spinner"): the game's own spinner (SharedXML SpinnerTemplate: the
     * ring, the sparks ADDED over it, one turn in 2 s, counter-clockwise), centred on ([cx], [cy]), [size] px. It turns off the clock,
     * so the host only keeps redrawing while something waits (PressWait asks for the frames). Art: kit `wait_ring` / `wait_sparks`.
     */
    fun waitSpinner(c: Canvas, cx: Float, cy: Float, size: Float, alpha: Int = 255) {
        val ring = tex("wait_ring") ?: return
        val box = RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        val deg = -360f * ((SystemClock.uptimeMillis() % 2000L) / 2000f)
        c.save(); c.rotate(deg, cx, cy)
        pBmp.alpha = alpha
        c.drawBitmap(ring, null, box, pBmp)
        pBmp.alpha = 255
        tex("wait_sparks")?.let { pSpark.alpha = alpha; c.drawBitmap(it, null, box, pSpark) }
        c.restore()
    }
    private val pSpark = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }

    /** the spellbook's "not on any bar" pulse, 0.5 .. 1: up in 0.5 s (ease out), down in 0.5 s (ease in), as the game's anim */
    fun bookGlowAlpha(now: Long = SystemClock.uptimeMillis()): Float {
        val t = (now % 1000L) / 500f
        return if (t < 1f) 0.5f + 0.5f * (1f - (1f - t) * (1f - t)) else 1f - 0.5f * ((t - 1f) * (t - 1f))
    }

    /** set when [spell] drew a pulsing "not on any bar" glow; a room reads and clears it after drawing, and keeps redrawing while
     *  it was set (postInvalidateOnAnimation, or ~every 50 ms) */
    @JvmField var bookGlowLive = false

    /** a waiting red button: the spinner at its right end, inside the plate, the plate's height */
    private fun waitOnButton(c: Canvas, r: RectF) {
        val s = r.height() * 0.62f
        waitSpinner(c, r.right - r.height() * 0.5f, r.centerY(), s)
    }

    /**
     * THE WAIT ON A SMALL PIECE (a square, a slot, an icon, a room's own piece): as unobtrusive as it can be (the user: mindful that it
     * covers pictures) - a small spinner on the piece's UPPER RIGHT CORNER, a third of its size, half outside it, on a soft dark disc
     * so it reads over any picture; the picture itself is left alone.
     */
    fun waitOverlay(c: Canvas, r: RectF) {
        val s = maxOf(26f, minOf(r.width(), r.height()) * 0.36f)
        val cx = r.right - s * 0.18f; val cy = r.top + s * 0.18f
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(150, 0, 0, 0)
        c.drawCircle(cx, cy, s * 0.42f, pFlat)
        waitSpinner(c, cx, cy, s)
    }

    /** draws [draw] over [r], pushed in when [down] (see [PRESS_SCALE]); for a room's own pressable piece too */
    fun pressed(c: Canvas, r: RectF, down: Boolean, draw: () -> Unit) {
        if (!down) { draw(); return }
        val sv = c.saveLayer(RectF(r.left - 12f, r.top - 12f, r.right + 12f, r.bottom + 12f), pPress)
        c.scale(PRESS_SCALE, PRESS_SCALE, r.centerX(), r.centerY())
        draw()
        c.restoreToCount(sv)
    }

    /**
     * [button] with a controller glyph: what that button does on the pad. The glyph stands at
     * the left and the label is centred in what is left of the button, so the two never meet,
     * however narrow the button. On a DISABLED button the glyph is faint.
     */
    fun button(c: Canvas, r: RectF, label: String, glyph: String, state: Button = Button.UP, waiting: Boolean = false) {
        buttonG(c, r, label, glyph, state, waiting)
        if (waiting) waitOnButton(c, r)
    }

    /**
     * A HOLD BUTTON THAT FILLS LIKE THE GAME'S CAST BAR (f-delete-hold-polish, the user's pick of three, 15 Oct: option 1, "the game's
     * cast bar"): [button] with its glyph, the plate filling left to right with the cast bar's own fill as the hold goes ([fill] 0..1),
     * the cast bar's spark riding the fill's edge with its trailing glow, and at the end the cast bar's full bar with its completion
     * glow over the whole button, fading ([finish] 0..1 after the hold was full; < 0 = none). The glyph and the label stay ON TOP of the
     * fill. The harvested cast-bar pieces (`ui/cast`, CastView's); drawn gold where the device has none.
     */
    fun buttonCastFill(c: Canvas, r: RectF, label: String, glyph: String, state: Button, fill: Float, finish: Float = -1f) =
        pressed(c, r, state == Button.DOWN) {
            buttonPlate(c, r, state)
            castFillOn(c, r, fill, finish)
            val g = minOf(70f, r.height() * 0.64f)
            val gx = r.left + 22f + g / 2f
            pad(c, glyph, gx, r.centerY() - 1f, g, if (state == Button.DISABLED) 90 else 255)
            buttonLabel(c, r, label, state, r.height() * 0.34f, gx + g / 2f + 8f, r.right - 26f)
        }

    private val pCast = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pCastAdd = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val castClip = android.graphics.Path()

    private fun castFillOn(c: Canvas, r: RectF, fill: Float, finish: Float) {
        val f = fill.coerceIn(0f, 1f)
        // the plate's inside (the button art's own edge stays visible round it)
        val inner = RectF(r.left + 7f, r.top + r.height() * 0.13f, r.right - 7f, r.bottom - r.height() * 0.15f)
        val save = c.save()
        castClip.reset(); castClip.addRoundRect(inner, 6f, 6f, android.graphics.Path.Direction.CW)
        c.clipPath(castClip)
        if (finish >= 0f) {
            // the bar full, then its completion glow fading over the whole button (as CastView's FLASH_CAST)
            val a = (1f - finish).coerceIn(0f, 1f)
            castArt("castbar_full_standard")?.let { c.drawBitmap(it, null, inner, pCast.apply { alpha = (255 * a).toInt() }) }
                ?: run { pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb((235 * a).toInt(), 255, 200, 60); c.drawRect(inner, pFlat) }
            c.restoreToCount(save)
            val glowR = RectF(r.left - 12f, r.top - 10f, r.right + 12f, r.bottom + 10f)
            castArt("castbar_full_glow_standard")?.let { c.drawBitmap(it, null, glowR, pCastAdd.apply { alpha = (255 * a).toInt() }) }
                ?: glow(c, glowR, Color.rgb(255, 220, 120), 0.9f * a)
            pCast.alpha = 255; pCastAdd.alpha = 255
            return
        }
        if (f <= 0f) { c.restoreToCount(save); return }
        val x = inner.left + inner.width() * f
        // the fill: the cast bar's own texture across the plate, shown up to the edge
        c.save(); c.clipRect(inner.left, inner.top, x, inner.bottom)
        castArt("castbar_filling_standard")?.let { c.drawBitmap(it, null, inner, pCast) }
            ?: run { pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(235, 230, 165, 30); c.drawRect(inner, pFlat) }
        c.restore()
        // the glow trailing the spark (37 x 12 units on the game's bar), additive
        val gh = inner.height(); val gw = gh * 37f / 12f
        castArt("castbar_pipglow_standard")?.let { c.drawBitmap(it, null, RectF(x - gw, inner.top, x, inner.bottom), pCastAdd) }
        c.restoreToCount(save)
        // the spark on the edge (it may stand a little past the plate's inside, as on the game's bar)
        if (f < 1f) {
            val sh = r.height() * 0.9f; val sw = sh * 0.45f
            castArt("castbar_pip")?.let { c.drawBitmap(it, null, RectF(x - sw / 2f, r.centerY() - sh / 2f, x + sw / 2f, r.centerY() + sh / 2f), pCastAdd) }
                ?: run {
                    pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 3f; pFlat.color = Color.rgb(255, 250, 225)
                    c.drawLine(x, inner.top - 2f, x, inner.bottom + 2f, pFlat); pFlat.style = Paint.Style.FILL
                }
        }
    }

    private fun buttonG(c: Canvas, r: RectF, label: String, glyph: String, state: Button, waiting: Boolean) = pressed(c, r, state == Button.DOWN || waiting) {
        buttonPlate(c, r, state)
        val g = minOf(70f, r.height() * 0.64f)
        val gx = r.left + 22f + g / 2f
        pad(c, glyph, gx, r.centerY() - 1f, g, if (state == Button.DISABLED) 90 else 255)
        buttonLabel(c, r, label, state, r.height() * 0.34f, gx + g / 2f + 8f, if (waiting) r.right - r.height() * 0.9f else r.right - 26f)
    }

    private fun buttonPlate(c: Canvas, r: RectF, state: Button) {
        val key = state.name.lowercase()
        val l = tex("button_${key}_left"); val m = tex("button_${key}_center"); val rt = tex("button_${key}_right")
        if (l != null && m != null && rt != null) {
            // The pieces are drawn at the button's height. The right piece is the chamfered end
            // plus a long run of centre: a button too narrow for all of it shows its right-hand
            // part, so the centre tile is only ever stretched on a button wider than both pieces.
            val s = r.height() / l.height
            val capL = minOf(l.width * s, r.width() / 2f)
            val room = r.width() - capL
            val fullR = rt.width * s
            if (room > fullR) {
                c.drawBitmap(m, null, RectF(r.left + capL, r.top, r.right - fullR, r.bottom), pBmp)
                c.drawBitmap(rt, null, RectF(r.right - fullR, r.top, r.right, r.bottom), pBmp)
            } else {
                val from = (rt.width - room / s).toInt().coerceIn(0, rt.width - 1)
                c.drawBitmap(rt, Rect(from, 0, rt.width, rt.height), RectF(r.left + capL, r.top, r.right, r.bottom), pBmp)
            }
            c.drawBitmap(l, Rect(0, 0, (capL / s).toInt().coerceAtLeast(1), l.height),
                RectF(r.left, r.top, r.left + capL, r.bottom), pBmp)
        } else {
            pFlat.style = Paint.Style.FILL
            pFlat.color = if (state == Button.DOWN) Theme.selected else Theme.raised
            c.drawRoundRect(r, 6f, 6f, pFlat)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f
            pFlat.color = if (state == Button.DISABLED) Theme.bronzeDim else Theme.gold
            c.drawRoundRect(r, 6f, 6f, pFlat)
        }
    }

    /** the label, centred between [x0] and [x1]. One that does not fit is made smaller, never cut. */
    /** [words] = the caller's colour for the words (the game menu's white); then a disabled button's words are the game's grey */
    private fun buttonLabel(c: Canvas, r: RectF, label: String, state: Button, textSize: Float, x0: Float, x1: Float, words: Int? = null) {
        val p = bodyPaint(textSize, when {
            state == Button.DISABLED -> if (words != null) Color.rgb(128, 128, 128) else Theme.dim
            words != null -> words
            else -> Theme.gold
        })
        p.textAlign = Paint.Align.CENTER
        val w = p.measureText(label)
        if (w > x1 - x0 && w > 0f) p.textSize = maxOf(textSize * 0.6f, textSize * (x1 - x0) / w)
        val nudge = if (state == Button.DOWN) r.height() * 0.03f else 0f
        c.drawText(label, (x0 + x1) / 2f + nudge, r.centerY() + nudge - (p.ascent() + p.descent()) / 2f, p)
    }

    /**
     * Parchment inside the game's gold border, for prose that is READ (quest text, item text,
     * what an NPC says). Returns the rectangle to lay the text out in.
     */
    fun parchment(c: Canvas, r: RectF, edge: Float = 28f): RectF {
        val paper = tex("parchment")
        val inner = RectF(r.left + edge * 0.3f, r.top + edge * 0.3f, r.right - edge * 0.3f, r.bottom - edge * 0.3f)
        if (paper != null) c.drawBitmap(paper, null, inner, pBmp)
        else { pFlat.style = Paint.Style.FILL; pFlat.color = Theme.parchment; c.drawRect(inner, pFlat) }
        border(c, r, "border_gold", edge, Theme.bronze)
        return RectF(r.left + edge * 1.2f, r.top + edge * 1.1f, r.right - edge * 1.2f, r.bottom - edge * 1.1f)
    }

    /**
     * One of the game's edge files round [r]: eight tiles, left, right, top, bottom and the four
     * corners. "border_gold" is the dialog border; "border_tooltip" is the thin tooltip line.
     */
    fun border(c: Canvas, r: RectF, name: String = "border_gold", edge: Float = 28f, fallback: Int = Theme.bronze) {
        val t = tiles(name)
        if (t == null) {
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = fallback
            c.drawRect(r.left + edge * 0.3f, r.top + edge * 0.3f, r.right - edge * 0.3f, r.bottom - edge * 0.3f, pFlat)
            return
        }
        val e = minOf(edge, r.width() / 2f, r.height() / 2f)
        c.save()
        c.clipRect(r.left, r.top + e, r.right, r.bottom - e)
        var y = r.top + e
        while (y < r.bottom - e) {
            c.drawBitmap(t[0], null, RectF(r.left, y, r.left + e, y + e), pBmp)
            c.drawBitmap(t[1], null, RectF(r.right - e, y, r.right, y + e), pBmp)
            y += e
        }
        c.restore()
        c.save()
        c.clipRect(r.left + e, r.top, r.right - e, r.bottom)
        var x = r.left + e
        while (x < r.right - e) {
            c.drawBitmap(t[2], null, RectF(x, r.top, x + e, r.top + e), pBmp)
            c.drawBitmap(t[3], null, RectF(x, r.bottom - e, x + e, r.bottom), pBmp)
            x += e
        }
        c.restore()
        c.drawBitmap(t[4], null, RectF(r.left, r.top, r.left + e, r.top + e), pBmp)
        c.drawBitmap(t[5], null, RectF(r.right - e, r.top, r.right, r.top + e), pBmp)
        c.drawBitmap(t[6], null, RectF(r.left, r.bottom - e, r.left + e, r.bottom), pBmp)
        c.drawBitmap(t[7], null, RectF(r.right - e, r.bottom - e, r.right, r.bottom), pBmp)
    }

    /**
     * The selected row of a list: warm fill, the game's highlight bar tinted with [colour]
     * (a quest's difficulty colour, an item's quality colour), and a gold left edge.
     */
    fun selectedRow(c: Canvas, r: RectF, colour: Int = Theme.gold) {
        pFlat.style = Paint.Style.FILL; pFlat.color = Theme.selected
        c.drawRect(r, pFlat)
        glow(c, r, colour, 0.35f)
        pFlat.color = Theme.gold
        c.drawRect(r.left, r.top, r.left + 4f, r.bottom, pFlat)
    }

    // ---------------------------------------------------------------- Home's small map and clock (the user, 2026-09-29, via Home)

    /**
     * THE ZONE'S NAME across the top of a small map, on the game's own zone bar (the classic minimap's, kit `zone_band`, its rounded
     * ends kept and its middle stretched to [r]'s width). [colour] as the game colours the zone: `Theme.zoneColour(pvp)`.
     */
    fun zoneBand(c: Canvas, r: RectF, zone: String, colour: Int = Theme.gold) {
        val t = tex("zone_band")
        if (t != null) {
            val k = r.height() / t.height
            val cap = 12
            val cw = cap * k
            c.drawBitmap(t, Rect(0, 0, cap, t.height), RectF(r.left, r.top, r.left + cw, r.bottom), pBmp)
            c.drawBitmap(t, Rect(cap, 0, t.width - cap, t.height), RectF(r.left + cw, r.top, r.right - cw, r.bottom), pBmp)
            c.drawBitmap(t, Rect(t.width - cap, 0, t.width, t.height), RectF(r.right - cw, r.top, r.right, r.bottom), pBmp)
        } else {
            pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(20, 18, 14); c.drawRect(r, pFlat)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Theme.bronzeDim; c.drawRect(r, pFlat)
        }
        val p = bodyPaint(r.height() * 0.5f, colour).apply { textAlign = Paint.Align.CENTER }
        val shown = TextUtils.ellipsize(zone, p, r.width() - 40f, TextUtils.TruncateAt.END).toString()
        c.drawText(shown, r.centerX(), r.centerY() - (p.ascent() + p.descent()) / 2f, p)
    }

    /**
     * THE DEVICE'S CLOCK AND BATTERY, centred on [cx] from [top]: the time on the game's minimap clock plate (kit `minimap_clock`),
     * and under it a small battery drawn in the kit's bronze with its charge ([battery] 0..100; green, then yellow under 50, red
     * under 20; a bolt while [charging]) and the percentage. Returns what it covers.
     */
    fun deviceClock(c: Canvas, cx: Float, top: Float, time: String, battery: Int, charging: Boolean = false): RectF {
        val ph = 46f
        val t = tex("minimap_clock")
        val pw = if (t != null) t.width * ph / t.height else 96f
        val plate = RectF(cx - pw / 2f, top, cx + pw / 2f, top + ph)
        if (t != null) c.drawBitmap(t, null, plate, pBmp)
        else { pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(20, 18, 14); c.drawRect(plate, pFlat) }
        val pt = numberPaint(26f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
        c.drawText(time, cx, plate.centerY() - (pt.ascent() + pt.descent()) / 2f, pt)
        // the battery: a bronze case with its nub, the charge inside, the percentage beside it
        val by = plate.bottom + 8f
        val pp = numberPaint(20f, Theme.text)
        val label = "$battery%"
        val bw = 30f; val bh = 15f
        val total = bw + 4f + 6f + pp.measureText(label)
        val bx = cx - total / 2f
        val case = RectF(bx, by, bx + bw, by + bh)
        pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Theme.bronze
        c.drawRect(case, pFlat)
        pFlat.style = Paint.Style.FILL
        c.drawRect(case.right, case.centerY() - 3.5f, case.right + 3f, case.centerY() + 3.5f, pFlat)
        val lvl = battery.coerceIn(0, 100) / 100f
        pFlat.color = when { battery < 20 -> Color.rgb(210, 50, 40); battery < 50 -> Color.rgb(230, 190, 40); else -> Color.rgb(60, 200, 70) }
        c.drawRect(case.left + 3f, case.top + 3f, case.left + 3f + (bw - 6f) * lvl, case.bottom - 3f, pFlat)
        if (charging) {
            val bolt = android.graphics.Path().apply {
                moveTo(case.centerX() + 2f, case.top + 1f); lineTo(case.centerX() - 4f, case.centerY() + 1f)
                lineTo(case.centerX(), case.centerY() + 1f); lineTo(case.centerX() - 2f, case.bottom - 1f)
                lineTo(case.centerX() + 4f, case.centerY() - 1f); lineTo(case.centerX(), case.centerY() - 1f); close()
            }
            pFlat.color = Color.WHITE; c.drawPath(bolt, pFlat)
        }
        c.drawText(label, case.right + 10f, case.centerY() - (pp.ascent() + pp.descent()) / 2f, pp)
        return RectF(minOf(plate.left, bx), top, maxOf(plate.right, bx + total), by + bh)
    }

    /**
     * A FIGHT STARTS WHILE A ROOM IS OPEN (the user, 2026-10-01: "I don't want the game to take the controller at all, I want the room that
     * I am in to flash red"): the strength of the room's red edge [ms] after the fight's start: a first flash to full in 120 ms, down to
     * 0.4, a second flash to 0.85 at 520 ms, then it settles to a steady 0.32 by 800 ms and stays for the rest of the fight. [endMs] =
     * ms since the fight ended (-1 = still fighting): it fades out over 300 ms. Draw with [combatGlow] (edge [COMBAT_EDGE]) round the
     * room's area, and (optionally) [combatTag] at its top centre.
     */
    fun combatFlash(ms: Float, endMs: Float = -1f): Float {
        val f = when {
            ms < 0f -> 0f
            ms < 120f -> ms / 120f
            ms < 360f -> 1f - 0.6f * ((ms - 120f) / 240f)
            ms < 520f -> 0.4f + 0.45f * ((ms - 360f) / 160f)
            ms < 800f -> 0.85f - 0.53f * ((ms - 520f) / 280f)
            else -> 0.32f
        }
        return if (endMs >= 0f) f * (1f - (endMs / 300f).coerceIn(0f, 1f)) else f
    }
    const val COMBAT_EDGE = 56f

    /**
     * THE "IN COMBAT" TAG at the top centre of a room in a fight (optional): the game's crossed swords (kit `combat_swords`) and "In combat" in the
     * game's red, on a dark plate, [alpha] with the edge's (full while it flashes, then 0.85). Why the room's actions are refused.
     * Returns the plate.
     */
    fun combatTag(c: Canvas, r: RectF, alpha: Int = 255): RectF {
        val p = bodyPaint(24f, Color.rgb(255, 64, 48)).apply { this.alpha = alpha }
        val label = "In combat"
        val w = 44f + p.measureText(label) + 22f
        // top CENTRE: a room's corners hold its own things (Bags' money, a close X); its title stands at the left
        val plate = RectF(r.centerX() - w / 2f, r.top + 12f, r.centerX() + w / 2f, r.top + 12f + 46f)
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb((215 * alpha / 255f).toInt(), 12, 6, 4)
        c.drawRoundRect(plate, 10f, 10f, pFlat)
        pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Color.argb((200 * alpha / 255f).toInt(), 140, 30, 20)
        c.drawRoundRect(plate, 10f, 10f, pFlat); pFlat.style = Paint.Style.FILL
        tex("combat_swords")?.let { pBmp.alpha = alpha; c.drawBitmap(it, null, RectF(plate.left + 10f, plate.centerY() - 14f, plate.left + 40f, plate.centerY() + 14f), pBmp); pBmp.alpha = 255 }
        c.drawText(label, plate.left + 46f, plate.centerY() - (p.ascent() + p.descent()) / 2f, p)
        return plate
    }

    /**
     * THE APP'S LOGO: THE LIVING HOME BUTTON (the user, 2026-10-02: "in the app loading screen that you made, I want to scrap the shitty ai
     * made DS forever Title, and also use our swirly home logo"). The Home button drawn large over [box] with its light flowing as on the
     * bar: BASE (kit `home_logo_base`, 560 px; else the bar's `home_live_base`) and the swirl's light (`home_logo_flare` / `home_live_flare`)
     * ADDED twice through the scrolling noise tile (`home_live_noise`), [now] = SystemClock.uptimeMillis(). The caller redraws about 15
     * times a second while it shows. false = a piece is not on the device (draw nothing; the line still shows).
     */
    fun homeLogo(c: Canvas, box: RectF, now: Long): Boolean {
        val base = tex("home_logo_base") ?: tex("home_live_base") ?: return false
        val flare = tex("home_logo_flare") ?: tex("home_live_flare") ?: return false
        val noise = tex("home_live_noise") ?: return false
        c.drawBitmap(base, null, box, pBmp)
        val k = box.width() / flare.width
        val kn = box.width() / 280f                                   // the noise's scale as on the bar's 280 px button
        val n = noise.width.toFloat()
        val mF = Matrix().apply { setScale(k, k); postTranslate(box.left, box.top) }
        val fs = android.graphics.BitmapShader(flare, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(mF) }
        val t = (now % 64_000L) / 1000f
        for (b in 0..1) {
            val mN = Matrix()
            if (b == 0) { mN.setScale(kn, kn); mN.postTranslate(box.left - kn * ((t * 18f) % n), box.top - kn * ((t * 8f) % n)) }
            else { mN.setScale(kn / 0.7f, kn / 0.7f); mN.postTranslate(box.left + kn * ((t * 10f) % n) / 0.7f, box.top - kn * ((t * 14f) % n) / 0.7f) }
            val ns = android.graphics.BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply { setLocalMatrix(mN) }
            pLogo.shader = android.graphics.ComposeShader(fs, ns, PorterDuff.Mode.MULTIPLY)
            c.drawRect(box, pLogo)
        }
        pLogo.shader = null
        return true
    }
    private val pLogo = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }

    /**
     * THE LOADING SWIRL (the user, 2026-10-02, on the start screen: "It doens't look right with the stone part ... it would be a really cool
     * effect to use the swirl as a "Loading effect with the glow kind of cycling through it brighter and dim."): the Home button's swirl
     * ALONE (kit `home_swirl`, its light on black, 560 px, my own drawing), no stone, over a soft blue halo, ADDED to the scene.
     * [style] "travel" = a bright band runs along the arm from the middle out to the tail (kit `home_swirl_field`: how far along the arm
     * each pixel lies), every [TRAVEL_MS], over the swirl glowing dim; "breathe" = the whole swirl brightens and dims together every
     * [BREATHE_MS]. [now] = SystemClock.uptimeMillis(); the caller redraws ~30 times a second while it shows. false = a piece is missing.
     */
    fun swirlLoader(c: Canvas, box: RectF, now: Long, style: String = "travel"): Boolean {
        val sw = tex("home_swirl") ?: return false
        // where the stone stood: a DARK pool (the light needs a dark ground to glow on any scene), then the faintest blue haze in it
        pSwHalo.shader = RadialGradient(box.centerX(), box.centerY(), box.width() * 0.68f,
            intArrayOf(Color.argb(215, 4, 6, 12), Color.argb(150, 4, 6, 12), Color.argb(0, 4, 6, 12)), floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP)
        c.drawCircle(box.centerX(), box.centerY(), box.width() * 0.68f, pSwHalo)
        pSwHalo.shader = RadialGradient(box.centerX(), box.centerY(), box.width() * 0.5f,
            intArrayOf(Color.argb(40, 40, 110, 230), Color.argb(0, 40, 110, 230)), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(box.centerX(), box.centerY(), box.width() * 0.5f, pSwHalo)
        if (style == "breathe") {
            val ph = (now % BREATHE_MS).toFloat() / BREATHE_MS
            val k = 0.32f + 0.68f * (0.5f - 0.5f * kotlin.math.cos(2f * Math.PI.toFloat() * ph))
            pSwAdd.alpha = (255 * k).toInt(); c.drawBitmap(sw, null, box, pSwAdd); pSwAdd.alpha = 255
            return true
        }
        // travel: the swirl dim, then the band: the swirl again in a layer, cut to field in (p - w .. p + w) with soft edges, ADDED
        pSwAdd.alpha = (255 * 0.3f).toInt(); c.drawBitmap(sw, null, box, pSwAdd); pSwAdd.alpha = 255
        val field = tex("home_swirl_field") ?: return true
        val ph = (now % TRAVEL_MS).toFloat() / TRAVEL_MS
        val p = -0.2f + 1.45f * ph                                     // the band enters at the middle and leaves past the tail
        val w = 0.16f
        val l = c.saveLayer(box, pSwAdd)
        c.drawBitmap(sw, null, box, pBmp); c.drawBitmap(sw, null, box, pSwAdd)         // twice: the band is the brightest light
        pSwMask.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        pSwMask.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
            3.5f, 0f, 0f, 0f, -3.5f * 255f * (p - w))))
        c.drawBitmap(field, null, box, pSwMask)
        pSwMask.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
            -3.5f, 0f, 0f, 0f, 3.5f * 255f * (p + w))))
        c.drawBitmap(field, null, box, pSwMask)
        c.restoreToCount(l)
        return true
    }
    const val TRAVEL_MS = 1600L
    const val BREATHE_MS = 1800L
    private val pSwAdd = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val pSwMask = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pSwHalo = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * A BUFF OR DEBUFF BUTTON on a small map, [r] square: the minimap cluster's own button (kit `minimap_button`), faded at rest
     * ([alpha]; full while pressed or open), with the game's buff mark (a cyan triangle up) or debuff mark (a red triangle down) and
     * the [count] at its lower right. The list it opens is the caller's.
     */
    fun auraButton(c: Canvas, r: RectF, debuff: Boolean, count: Int, alpha: Int = 200) {
        val b = tex("minimap_button")
        pBmp.alpha = alpha
        if (b != null) c.drawBitmap(b, null, r, pBmp)
        else { pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(alpha, 30, 34, 26); c.drawRect(r, pFlat) }
        tex(if (debuff) "aura_debuff" else "aura_buff")?.let { m ->
            val w = r.width() * 0.62f
            val h = w * m.height / m.width
            c.drawBitmap(m, null, RectF(r.centerX() - w / 2f, r.centerY() - h / 2f - r.height() * 0.06f,
                r.centerX() + w / 2f, r.centerY() + h / 2f - r.height() * 0.06f), pBmp)
        }
        pBmp.alpha = 255
        if (count > 0) {
            val pn = numberPaint(r.height() * 0.36f, Color.argb(maxOf(alpha, 200), 255, 255, 255)).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(count.toString(), r.right - 5f, r.bottom - 6f, pn)
        }
    }

    /** [c] halfway to white: a type colour made readable as text on the dark ground (the border keeps the true colour) */
    private fun lighter(c: Int): Int = Color.rgb((Color.red(c) + 255) / 2, (Color.green(c) + 255) / 2, (Color.blue(c) + 255) / 2)

    /** a debuff's border and name colour by its dispel type, as the game's DebuffTypeColor */
    fun debuffColour(type: String?): Int = when (type) {
        "Magic" -> Color.rgb(51, 153, 255)
        "Curse" -> Color.rgb(153, 0, 255)
        "Disease" -> Color.rgb(153, 102, 0)
        "Poison" -> Color.rgb(0, 153, 0)
        else -> Color.rgb(204, 0, 0)
    }

    /**
     * ONE BUFF OR DEBUFF in the list a Home aura button opens, as the game shows auras: the icon in its slot with its [stacks] (> 1),
     * a debuff framed in its dispel type's colour ([debuffColour]: Magic, Curse, Disease, Poison; none = the game's
     * red), the [name], and the time left at the right ([timeLeft] seconds; < 0 = none). [selected] = the kit's selected row (the
     * one Remove acts on). [r] is the whole row and the touch target (88 px high or more). [cursor] = the controller's cursor is on
     * the row (the caller draws the box): its name goes gold as in [menuRow]; a debuff's name keeps its type colour, as a coloured
     * menuRow label does, and the time keeps its own. Under the cursor the selected bar is not drawn: the box IS the highlight
     * (the bar's fill ran over the box's right edge, Home's r130).
     */
    fun auraRow(c: Canvas, r: RectF, icon: Bitmap?, name: String, stacks: Int = 0, timeLeft: Int = -1, debuff: Boolean = false,
                type: String? = null, selected: Boolean = false, cursor: Boolean = false) {
        if (selected && !cursor) selectedRow(c, r)
        val s = r.height() - 22f
        slot(c, icon, r.left + 12f, r.top + 11f, s, if (debuff) debuffColour(type) else 0, stacks)
        val tx = r.left + 12f + s + 20f
        val pt = numberPaint(26f, if (timeLeft in 0..59) Theme.questRed else Theme.dim).apply { textAlign = Paint.Align.RIGHT }
        val left = when {
            timeLeft < 0 -> ""
            timeLeft >= 3600 -> "${timeLeft / 3600} h"
            timeLeft >= 60 -> "${timeLeft / 60} m"
            else -> "$timeLeft s"
        }
        val tw = if (left.isEmpty()) 0f else pt.measureText(left) + 20f
        if (left.isNotEmpty()) c.drawText(left, r.right - 16f, r.centerY() - (pt.ascent() + pt.descent()) / 2f, pt)
        val pn = bodyPaint(28f, if (debuff) lighter(debuffColour(type)) else if (cursor) Theme.gold else Theme.text)
        val shown = TextUtils.ellipsize(name, pn, r.right - 16f - tw - tx, TextUtils.TruncateAt.END).toString()
        c.drawText(shown, tx, r.centerY() - (pn.ascent() + pn.descent()) / 2f, pn)
    }

    /**
     * THE GAME'S RED COMBAT GLOW round the inside of [r] (Home, the user 2026-09-29 17:17: "pressing B and making it minimize while
     * in Combat, should make the area around the controller have a Red Highlight until exiting combat"): the game's own red
     * screen-edge glow (FullScreenTextures/LowHealth, kit `fx_lowhealth`), drawn ADDITIVELY as a nine-slice, so its edges are
     * [edge] px deep on all four sides however wide [r] is. [strength] 0..1 (a pulse, if wanted, is the caller's).
     * Without the picture: a flat red fade of the same depth.
     */
    fun combatGlow(c: Canvas, r: RectF, strength: Float = 1f, edge: Float = 40f) {
        val a = (255 * strength.coerceIn(0f, 1f)).toInt()
        val t = tex("fx_lowhealth")
        if (t != null) {
            val sc = 64                                                   // the source's corner, of 256
            val sw = t.width; val sh = t.height
            val xs = intArrayOf(0, sc, sw - sc, sw); val ys = intArrayOf(0, sc, sh - sc, sh)
            val xd = floatArrayOf(r.left, r.left + edge, r.right - edge, r.right)
            val yd = floatArrayOf(r.top, r.top + edge, r.bottom - edge, r.bottom)
            pGlow.colorFilter = null
            pGlow.alpha = a
            for (i in 0..2) for (j in 0..2) {
                if (i == 1 && j == 1) continue                            // the black middle adds nothing
                c.drawBitmap(t, Rect(xs[i], ys[j], xs[i + 1], ys[j + 1]), RectF(xd[i], yd[j], xd[i + 1], yd[j + 1]), pGlow)
            }
            pGlow.alpha = 255
            return
        }
        val red = Color.argb((150 * strength).toInt().coerceIn(0, 255), 200, 20, 20)
        val clear = Color.argb(0, 200, 20, 20)
        pFlat.style = Paint.Style.FILL
        fun band(x0: Float, y0: Float, x1: Float, y1: Float, gx0: Float, gy0: Float, gx1: Float, gy1: Float) {
            pFlat.shader = LinearGradient(gx0, gy0, gx1, gy1, red, clear, Shader.TileMode.CLAMP)
            c.drawRect(x0, y0, x1, y1, pFlat)
        }
        band(r.left, r.top, r.right, r.top + edge, 0f, r.top, 0f, r.top + edge)
        band(r.left, r.bottom - edge, r.right, r.bottom, 0f, r.bottom, 0f, r.bottom - edge)
        band(r.left, r.top, r.left + edge, r.bottom, r.left, 0f, r.left + edge, 0f)
        band(r.right - edge, r.top, r.right, r.bottom, r.right, 0f, r.right - edge, 0f)
        pFlat.shader = null
    }

    /** the game's additive highlight bar over [r], tinted; the ends fade out on their own */
    fun glow(c: Canvas, r: RectF, colour: Int, strength: Float = 0.5f) {
        val bar = tex("highlight") ?: return
        // the texture is a bright bar that fades to BLACK, made for additive blending: take its
        // brightness and give it our colour
        val k = strength / 255f
        val cr = Color.red(colour) * k; val cg = Color.green(colour) * k; val cb = Color.blue(colour) * k
        pGlow.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0.299f * cr, 0.587f * cr, 0.114f * cr, 0f, 0f,
            0.299f * cg, 0.587f * cg, 0.114f * cg, 0f, 0f,
            0.299f * cb, 0.587f * cb, 0.114f * cb, 0f, 0f,
            0f, 0f, 0f, 1f, 0f)))
        c.drawBitmap(bar, null, r, pGlow)
    }

    /** a room's header bar: the title in Morpheus, an optional count or note after it */
    fun header(c: Canvas, width: Float, title: String, note: String? = null, height: Float = 62f) {
        pFlat.style = Paint.Style.FILL; pFlat.color = Theme.panel
        c.drawRect(0f, 0f, width, height, pFlat)
        pFlat.color = Theme.bronzeDim
        c.drawRect(0f, height - 1.5f, width, height, pFlat)
        val pt = titlePaint(height * 0.8f)
        c.drawText(title, 24f, height * 0.8f, pt)
        if (note != null)
            c.drawText(note, 24f + pt.measureText(title) + 22f, height * 0.74f, bodyPaint(height * 0.4f, Theme.dim))
    }

    /** the hairline between two panes or two sections */
    fun divider(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        pFlat.style = Paint.Style.FILL; pFlat.color = Theme.bronzeDim
        if (x0 == x1) c.drawRect(x0, y0, x0 + 1.5f, y1, pFlat) else c.drawRect(x0, y0, x1, y0 + 1.5f, pFlat)
    }

    // ---------------------------------------------------------------- the game's windows
    //
    // What an NPC window is made of (user ruling 2026-09-28: the pop-ups are built the way the
    // game builds its own quest, gossip and vendor windows). The structure is Blizzard's, read
    // from QuestFrame / MerchantFrame in the client: a framed window with a round portrait, the
    // NPC's name in the title bar and a close button; a parchment page; a stone strip of buttons.

    /** the frame art (2x) is drawn at this scale: title bar 56 px, portrait ring 142 px */
    const val FRAME = 1.2f

    /** where the inside of a window starts, under the title bar (from the window's top) */
    const val WINDOW_TOP = 88f

    /** where content on the LEFT can start: under the portrait ring (from the window's top) */
    const val WINDOW_UNDER_RING = 154f

    /** the stone strip of buttons along the bottom of a window */
    const val STRIP = 134f

    /**
     * THE GAME'S OWN WINDOW takes two calls, because the portrait ring lies OVER the content:
     *
     *   val inner = UiKit.windowGround(c, r)       // first: the ground. Returns the inside.
     *   ...draw the page, the list, the strip...
     *   UiKit.windowFrame(c, r, "Remy", portrait)  // last: frame, ring, title, close button
     *
     * [marble] is the vendor's ground; quests and gossip lie on parchment over the rock.
     */
    fun windowGround(c: Canvas, r: RectF, marble: Boolean = false, floating: Boolean = false): RectF {
        // [floating] = a window smaller than the screen (the compact loot window): the ground stops at the frame's outer metal line
        // (18 px in from r's right, 8 px up from its bottom; measured on the drawn frame), so no slab of ground shows outside the frame
        ground(c, if (floating) RectF(r.left + 4f, r.top + 4f, r.right - 18f, r.bottom - 8f) else r,
            if (marble) "ground_marble" else "ground_rock", if (marble) 0.9f else 0.5f)
        return RectF(r.left + 30f, r.top + WINDOW_TOP, r.right - 30f, r.bottom - 22f)
    }

    /**
     * The frame of [windowGround]'s window. [portrait] is what the ring shows: the NPC's own
     * portrait is not on the wire, so it is a picture for the KIND of window
     * (`tex("portrait_quest")`, `"portrait_gossip"`, `"portrait_vendor"`). [close] false = no close X (a window whose strip
     * closes it: Goodbye on gossip, Decline on a quest offer).
     */
    fun windowFrame(c: Canvas, r: RectF, title: String, portrait: Bitmap?, titleColour: Int = Color.WHITE,
                    closeDown: Boolean = false, closeB: Int = 0, close: Boolean = true) {
        val tl = tex("frame_tl"); val tr = tex("frame_tr"); val bl = tex("frame_bl"); val br = tex("frame_br")
        val et = tex("frame_t"); val eb = tex("frame_b"); val el = tex("frame_l"); val er = tex("frame_r")
        val band = r.top - 16f + 57f * FRAME
        if (tl == null || tr == null || bl == null || br == null || et == null || eb == null || el == null || er == null) {
            // the flat stand-in: a bar, a line round the window, the title
            pFlat.style = Paint.Style.FILL; pFlat.color = Theme.panel
            c.drawRect(r.left, r.top, r.right, r.top + WINDOW_TOP - 8f, pFlat)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Theme.bronze
            c.drawRect(r.left + 6f, r.top + 6f, r.right - 6f, r.bottom - 6f, pFlat)
        } else {
            val x0 = r.left - 16f; val y0 = r.top - 16f
            val cx = x0 + 76f * FRAME; val cy = y0 + 76f * FRAME
            val rad = 54f * FRAME
            pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(18, 14, 10)
            c.drawCircle(cx, cy, rad, pFlat)
            if (portrait != null) {
                // a square picture fills the ring and is cut round; a free-standing one sits inside it
                val square = portrait.width == portrait.height
                val k = (if (square) rad * 2f else rad * 1.5f) / maxOf(portrait.width, portrait.height)
                val pw = portrait.width * k; val ph = portrait.height * k
                c.save()
                c.clipPath(android.graphics.Path().apply { addCircle(cx, cy, rad, android.graphics.Path.Direction.CW) })
                c.drawBitmap(portrait, null, RectF(cx - pw / 2f, cy - ph / 2f, cx + pw / 2f, cy + ph / 2f), pBmp)
                c.restore()
            }
            fun w(b: Bitmap) = b.width * FRAME
            fun h(b: Bitmap) = b.height * FRAME
            val rx = r.right - 12f - 144f * FRAME - 1f          // the right pieces' left edge
            val by = r.bottom - 58f * FRAME - 2f                // the bottom pieces' top edge
            repeat(c, et, x0 + w(tl), y0, rx, true)
            repeat(c, eb, x0 + w(bl), by, rx + w(tr) - w(br), true)
            repeat(c, el, x0, y0 + h(tl), by, false)
            repeat(c, er, rx, y0 + h(tr), by, false)
            c.drawBitmap(tl, null, RectF(x0, y0, x0 + w(tl), y0 + h(tl)), pBmp)
            c.drawBitmap(tr, null, RectF(rx, y0, rx + w(tr), y0 + h(tr)), pBmp)
            c.drawBitmap(bl, null, RectF(x0, by, x0 + w(bl), by + h(bl)), pBmp)
            c.drawBitmap(br, null, RectF(rx + w(tr) - w(br), by, rx + w(tr), by + h(br)), pBmp)
        }
        // [close] false (the user, 2026-09-30: "re add the goodbye button and remove the x for those ... the decline button is enough
        // the x button can be removed there as well"): no X and no corner B; the title centres between the ring and the frame's end
        val p = bodyPaint(32f, titleColour).apply { textAlign = Paint.Align.CENTER }
        c.drawText(title, (r.left + 170f + r.right - (if (close) 120f else 40f)) / 2f, band - (p.ascent() + p.descent()) / 2f, p)
        if (close) closeX(c, r.right - 70f, band + 2f, 92f, closeDown, closeB)
    }

    /**
     * The window's close X, [size] px, centred. [closeB] = B would close the window (the user, 2026-09-29: "with vendors I
     * cant see the home bar, so the B should be on the X on the top"; then, 11:23: "do the B in the corner"): 0 = the X
     * alone; any other value = the X with the game's B glyph on its lower left corner.
     */
    fun closeX(c: Canvas, cx: Float, cy: Float, size: Float, down: Boolean = false, closeB: Int = 0) {
        square(c, "exit", cx, cy, size, if (down) Button.DOWN else Button.UP, if (closeB != 0) backGlyph() else null)
    }

    /** the game's B glyph in the layout the Settings chose: Nintendo's B, Xbox's B, PlayStation's circle */
    fun backGlyph(): String = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> "ltr_b"
        CastSettings.ButtonLayout.PLAYSTATION -> "shp_circle"
        else -> "rev_b"
    }

    /** where a finger closes the window: the close button, grown to the touch floor */
    fun windowClose(r: RectF): RectF {
        val band = r.top - 16f + 57f * FRAME
        return RectF(r.right - 70f - 58f, maxOf(r.top, band - 56f), r.right - 12f, band + 60f)
    }

    /** the stone strip the buttons stand in, along the bottom of [inner]. Returns its rectangle. */
    fun strip(c: Canvas, inner: RectF, height: Float = STRIP): RectF {
        val s = RectF(inner.left - 8f, inner.bottom - height, inner.right + 8f, inner.bottom + 6f)
        ground(c, s, "ground_rock", 0.32f)
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(96, 80, 50)
        c.drawRect(s.left, s.top, s.right, s.top + 2f, pFlat)
        pFlat.color = Color.BLACK
        c.drawRect(s.left, s.top + 3f, s.right, s.top + 4f, pFlat)
        return RectF(inner.left, inner.bottom - height, inner.right, inner.bottom)
    }

    /**
     * One of the game's red square buttons: "exit", "plus", "minus"; centred, [size] px. [glyph] = the controller's
     * button that does the same (e.g. "rev_x"), drawn small on the square's lower corner: the LEFT one, or the right one
     * with [glyphRight] (the vendor's plus, so that the glyph stays clear of the count and the price between - and +).
     * Faint on a DISABLED square.
     */
    fun square(c: Canvas, what: String, cx: Float, cy: Float, size: Float, state: Button = Button.UP,
               glyph: String? = null, glyphRight: Boolean = false, waiting: Boolean = false) {
        squareP(c, what, cx, cy, size, state, glyph, glyphRight, waiting)
        if (waiting) waitOverlay(c, RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f))
    }

    private fun squareP(c: Canvas, what: String, cx: Float, cy: Float, size: Float, state: Button, glyph: String?, glyphRight: Boolean,
                        waiting: Boolean) =
        pressed(c, RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f), state == Button.DOWN || waiting) {
        val r = RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        val b = tex("square_${what}_${state.name.lowercase()}") ?: tex("square_${what}_up")
        if (b != null) c.drawBitmap(b, null, r, pBmp)
        else button(c, RectF(r.left + 8f, r.top + 8f, r.right - 8f, r.bottom - 8f),
            when (what) { "exit" -> "X"; "plus" -> "+"; else -> "-" }, state)
        if (glyph != null) pad(c, glyph, if (glyphRight) cx + size * 0.42f else cx - size * 0.42f, cy + size * 0.36f,
            size * 0.52f, if (state == Button.DISABLED) 90 else 255)
    }

    /**
     * A tab. [onTop] is a tab standing on top of a list (the game's hanging tab turned over);
     * without it the tab hangs under a frame, as the game's do. [count] shows after the label.
     */
    fun tab(c: Canvas, r: RectF, label: String, active: Boolean, count: Int = 0, onTop: Boolean = true, cursor: Boolean = false) {
        if (cursor) { tabCursor(c, r, "tab:$active:$onTop") { tab(it, r, label, active, count, onTop) }; return }
        val pre = if (active) "tab_on_" else "tab_"
        val l = tex(pre + "left"); val m = tex(pre + "center"); val rt = tex(pre + "right")
        if (l != null && m != null && rt != null) {
            val k = r.height() / l.height
            val lw = l.width * k; val rw = rt.width * k
            c.save()
            if (onTop) c.scale(1f, -1f, r.centerX(), r.centerY())
            c.drawBitmap(m, null, RectF(r.left + lw, r.top, r.right - rw, r.bottom), pBmp)
            c.drawBitmap(l, null, RectF(r.left, r.top, r.left + lw, r.bottom), pBmp)
            c.drawBitmap(rt, null, RectF(r.right - rw, r.top, r.right, r.bottom), pBmp)
            c.restore()
        } else {
            pFlat.style = Paint.Style.FILL; pFlat.color = if (active) Theme.selected else Theme.raised
            c.drawRect(r, pFlat)
        }
        val p = bodyPaint(28f, if (active) Color.WHITE else Theme.gold).apply { textAlign = Paint.Align.CENTER }
        val ty = r.centerY() + (if (onTop) 8f else -6f) - (p.ascent() + p.descent()) / 2f
        val tx = r.centerX() - (if (count > 0) 14f else 0f)
        c.drawText(label, tx, ty, p)
        if (count > 0) c.drawText(count.toString(), tx + p.measureText(label) / 2f + 12f, ty, numberPaint(27f, Theme.dim))
    }

    /**
     * AN ITEM THE WAY THE GAME SHOWS ONE IN A LIST (MerchantItemTemplate, QuestItemTemplate): the
     * icon in its slot, the dark name plate beside it, the name on the plate's top line and, when
     * there is a [price], the price UNDER THE NAME. [r] is the whole row; 116 px tall or more.
     * No action button belongs on it: a touch selects, and one button elsewhere acts (the quantity, below, is the one
     * exception: it changes nothing in the game).
     *
     * A NAME THAT IS TOO LONG: with a price it is one line and ends in an ellipsis; without one
     * (a quest's reward) it takes two lines, as the game's does.
     * THREE WAYS AN ITEM IS NOT FOR THIS CHARACTER, each the game's own sign: [unusable] = red
     * name and red icon; not [affordable] = red price; not [available] (sold out, or the vendor
     * will not sell it to this character) = the whole row goes grey.
     *
     * THE QUANTITY ON THE TILE (the user, 2026-09-29 11:36: "move the quantity buttons to live on the currently
     * highlighted Item, and adjust that Item price with the QTY adjustment"): [qty] > 0 draws minus, the count and
     * plus on the price line's right end; [price] is then the TOTAL for [qty]. [canLess] / [canMore] grey a square;
     * [lessGlyph] / [moreGlyph] (e.g. "rev_x" / "rev_y", null by touch) sit small on their outer corners, as the B does
     * on the close X. Where a finger presses them: [plateQty].
     */
    fun itemPlate(c: Canvas, r: RectF, icon: Bitmap?, name: String, colour: Int = Theme.text, count: Int = 0,
                  price: Long = -1, affordable: Boolean = true, selected: Boolean = false, unusable: Boolean = false,
                  edge: Int = 0, available: Boolean = true, qty: Int = 0, canLess: Boolean = true,
                  canMore: Boolean = true, lessGlyph: String? = null, moreGlyph: String? = null) {
        val s = r.height() - 20f
        if (selected) glow(c, RectF(r.left - 8f, r.top - 8f, r.right + 8f, r.bottom + 8f), Theme.gold, 0.6f)
        val plate = tex("plate")
        val pr = RectF(r.left + s - 2f, r.top + 7f, r.right, r.bottom - 7f)
        if (plate != null) c.drawBitmap(plate, null, pr, pBmp)
        else { pFlat.style = Paint.Style.FILL; pFlat.color = Theme.raised; c.drawRect(pr, pFlat) }
        slot(c, icon, r.left + 2f, r.top + 10f, s, if (available) edge else 0, count, unusable && available, !available)
        val tx = r.left + s + 18f
        val room = r.right - 22f - tx
        val p = bodyPaint(27f, when { !available -> Theme.dim; unusable -> Theme.unusable; else -> colour })
        if (price >= 0) {
            val shown = TextUtils.ellipsize(name, p, room, TextUtils.TruncateAt.END).toString()
            c.drawText(shown, tx, r.top + 24f - p.ascent() * 0.78f, p)
            money(c, price, tx, r.bottom - 38f, 27f,
                when { !available -> Theme.dim; affordable -> Theme.text; else -> Theme.unusable })
        } else if (p.measureText(name) <= room) {
            c.drawText(name, tx, r.centerY() - (p.ascent() + p.descent()) / 2f, p)
        } else {
            val l = StaticLayout.Builder.obtain(name, 0, name.length, p, room.toInt())
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 0.95f).build()
            c.save(); c.translate(tx, r.centerY() - l.height / 2f); l.draw(c); c.restore()
        }
        if (selected) {
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Theme.gold
            c.drawRect(r.left - 3f, r.top + 3f, r.right + 1f, r.bottom - 3f, pFlat)
        }
        if (qty > 0) {
            val cy = r.bottom - 38f
            val lessX = r.right - QTY_LESS; val moreX = r.right - QTY_MORE
            square(c, "minus", lessX, cy, QTY_SQUARE, if (canLess) Button.UP else Button.DISABLED, lessGlyph)
            square(c, "plus", moreX, cy, QTY_SQUARE, if (canMore) Button.UP else Button.DISABLED, moreGlyph, glyphRight = true)
            val pn = numberPaint(30f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
            c.drawText(qty.toString(), (lessX + moreX) / 2f, cy - (pn.ascent() + pn.descent()) / 2f, pn)
        }
    }

    /**
     * THE PICKED REWARD (the game's QuestInfoItemHighlight: UI-QuestItemHighlight, ADDED): a glowing gold outline round the
     * [itemPlate] row [r]'s icon and another round its name plate. It marks the reward chosen; the CURSOR stays [focus], so the
     * two never look alike (Interact's quest window, the user: "after selecting it then the quest complete button should only
     * have the A glyph"). Draw it over the row, before the cursor.
     */
    fun rewardPicked(c: Canvas, r: RectF) {
        val s = r.height() - 20f
        val hl = tex("quest_item_highlight")
        if (hl == null) {
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 4f; pFlat.color = Theme.gold
            c.drawRect(r.left + 2f, r.top + 10f, r.left + 2f + s, r.top + 10f + s, pFlat)
            c.drawRect(r.left + s - 2f, r.top + 7f, r.right, r.bottom - 7f, pFlat)
            return
        }
        // the art's two outlines, of 256 x 64: the icon's (9..45, 7..45) and the name's (49..152, 7..45)
        val e = s * 0.07f
        c.drawBitmap(hl, Rect(9, 7, 45, 45), RectF(r.left + 2f - e, r.top + 10f - e, r.left + 2f + s + e, r.top + 10f + s + e), pGlow)
        slice3(c, hl, 49, 152, 7, 45, 12, RectF(r.left + s + 2f, r.top + 4f, r.right + 4f, r.bottom - 4f), pGlow)
    }

    /**
     * Where a finger presses the quantity on an [itemPlate] row [r]: minus, the count, plus (each the full height of
     * the price line and a finger wide). The count between them is the caller's to use or leave.
     */
    fun plateQty(r: RectF): Triple<RectF, RectF, RectF> {
        val top = r.bottom - 38f - 46f
        val lessX = r.right - QTY_LESS; val moreX = r.right - QTY_MORE
        val mid = (lessX + moreX) / 2f
        return Triple(RectF(lessX - 46f, top, mid - 22f, r.bottom + 4f), RectF(mid - 22f, top, mid + 22f, r.bottom + 4f),
            RectF(mid + 22f, top, minOf(r.right + 4f, moreX + 46f), r.bottom + 4f))
    }

    // ---------------------------------------------------------------- the trainer
    //
    // What Forever's own trainer window is made of (ClassTrainerFrame, blizzard_trainerui camelot): the services grouped under
    // the Professions recipe header (TrainerUICategoryTemplate), each a ClassTrainerSkillButtonTemplate row (icon, name in gold,
    // "(Rank 2)" after it, the cost at the top right, the requirement line under the name), the profession's rank bar at the top
    // left (profession trainers), the Settings drop-down at the top right, the player's money and TRAIN at the bottom.

    /**
     * THE TRAINER'S GROUP ROW over [r]: the game's Professions recipe header (its ends kept, the middle stretched), [label] in gold
     * at its left, the game's - (open) or + ([collapsed]) at its right. A touch or A on it folds the group (the app alone).
     */
    fun recipeHeader(c: Canvas, r: RectF, label: String, collapsed: Boolean) {
        val l = tex("recipe_head_left"); val m = tex("recipe_head_middle"); val rt = tex("recipe_head_right")
        if (l != null && m != null && rt != null) {
            val k = r.height() / l.height
            val lw = l.width * k; val rw = rt.width * k
            c.drawBitmap(m, null, RectF(r.left + lw - 0.5f, r.top, r.right - rw + 0.5f, r.bottom), pBmp)
            c.drawBitmap(l, null, RectF(r.left, r.top, r.left + lw, r.bottom), pBmp)
            c.drawBitmap(rt, null, RectF(r.right - rw, r.top, r.right, r.bottom), pBmp)
        } else {
            pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(24, 20, 16)
            c.drawRoundRect(r, 8f, 8f, pFlat)
        }
        val p = bodyPaint(28f, Theme.gold, shadow = false)
        c.drawText(label, r.left + 26f, r.centerY() - (p.ascent() + p.descent()) / 2f, p)
        val sign = tex(if (collapsed) "recipe_head_expand" else "recipe_head_collapse")
        val sh = r.height() * 0.62f; val sw = sh * 22f / 16f          // the sign half again as big as the game's: it must read here
        val sr = RectF(r.right - 24f - sw, r.centerY() - sh / 2f, r.right - 24f, r.centerY() + sh / 2f)
        if (sign != null) c.drawBitmap(sign, null, sr, pBmp)
        else c.drawText(if (collapsed) "+" else "-", sr.centerX(), r.centerY() - (p.ascent() + p.descent()) / 2f, p)
    }

    /** one requirement on a trainer's row, as the game writes it: [before] [value] [after] ("Level " "12"; "Mining (" "75" ")"),
     *  the value white when [met], the game's red when not */
    class Need(val before: String, val value: String, val after: String = "", val met: Boolean = true)

    /**
     * A TRAINER'S SERVICE THE WAY THE GAME SHOWS ONE (ClassTrainerSkillButtonTemplate), [r] the whole row, 116 px tall or more like
     * [itemPlate]: the icon in its slot and the dark plate; on the top line the name in gold, [rank] small after it in the game's
     * parentheses ("Rank 2" -> "(Rank 2)"), and the [cost] at the line's right end (red when not [affordable]); under the name the
     * game's requirement line: "Requires Level 12, Mining (75)" ([needs], each value white when met, red when not), or "Already
     * known" ([known]: no cost shown). [unavailable] = the game's grey row: grey name, grey icon. The cursor is [focus] round [r].
     */
    fun servicePlate(c: Canvas, r: RectF, icon: Bitmap?, name: String, rank: String? = null, needs: List<Need> = emptyList(),
                     known: Boolean = false, cost: Long = 0, affordable: Boolean = true, unavailable: Boolean = false) {
        val s = r.height() - 20f
        val plate = tex("plate")
        val pr = RectF(r.left + s - 2f, r.top + 7f, r.right, r.bottom - 7f)
        if (plate != null) c.drawBitmap(plate, null, pr, pBmp)
        else { pFlat.style = Paint.Style.FILL; pFlat.color = Theme.raised; c.drawRect(pr, pFlat) }
        slot(c, icon, r.left + 2f, r.top + 10f, s, dim = unavailable)
        val tx = r.left + s + 18f
        val topY = r.top + 24f
        // the cost first: the name gives way to it
        var nameRoom = r.right - 22f - tx
        if (!known && cost > 0) {
            val mp = numberPaint(25f, Theme.text)
            val cy = topY - mp.ascent() * 0.78f + (mp.ascent() + mp.descent()) / 2f
            // white or red as the game draws it: its grey cover never reaches the money
            money(c, cost, r.right - 18f, cy, 25f, if (affordable) Theme.text else Theme.unusable, fromRight = true)
            nameRoom = r.right - 18f - moneyWidth(cost, 25f) - 16f - tx
        }
        val p = bodyPaint(27f, if (unavailable) Theme.dim else Theme.gold)
        val base = topY - p.ascent() * 0.78f
        val rk = if (rank.isNullOrEmpty()) "" else "($rank)"
        val rp = bodyPaint(22f, if (unavailable) Theme.dim else Theme.gold)
        val rkW = if (rk.isEmpty()) 0f else rp.measureText(rk) + 10f
        val shown = TextUtils.ellipsize(name, p, nameRoom - rkW, TextUtils.TruncateAt.END).toString()
        c.drawText(shown, tx, base, p)
        if (rk.isNotEmpty()) c.drawText(rk, tx + p.measureText(shown) + 10f, base, rp)
        // the requirement line
        val lp = bodyPaint(23f, if (unavailable) Theme.dim else Theme.text)
        val ly = r.bottom - 38f - (lp.ascent() + lp.descent()) / 2f
        if (known) { lp.color = Theme.dim; c.drawText("Already known", tx, ly, lp); return }
        if (needs.isEmpty()) return
        val right = r.right - 18f
        var x = tx
        fun put(t: String, colour: Int) {
            if (x >= right || t.isEmpty()) return
            lp.color = colour
            val fit = TextUtils.ellipsize(t, lp, right - x, TextUtils.TruncateAt.END).toString()
            c.drawText(fit, x, ly, lp); x += lp.measureText(fit)
        }
        val word = if (unavailable) Theme.dim else Theme.text
        put("Requires ", word)
        for ((i, n) in needs.withIndex()) {
            if (i > 0) put(", ", word)
            put(n.before, word); put(n.value, if (n.met) Color.WHITE else Theme.unusable); put(n.after, word)
        }
    }

    // ---------------------------------------------------------------- the stable
    //
    // Forever's own PetStableFrame (blizzard_stableui camelot): the chosen pet on a stage (the game turns its 3D model there; the
    // wire carries only its icon), "Name Level 12 Cat" over it and its loyalty under that, the loyalty level in the boss ring,
    // the experience bar, then Current Pet (one slot) and Stabled Pets (the game's two), Purchase with its Cost.

    /** the game's stable picture for a pet spec: "cunning", "ferocity", "tenacity"; anything else (Forever's pets carry none yet) = ferocity */
    fun stableStage(spec: String?): Bitmap? = tex("stable_bg_" + when (spec?.lowercase()) { "cunning" -> "cunning"; "tenacity" -> "tenacity"; else -> "ferocity" })

    /**
     * THE STAGE over [r]: the stable picture for [spec] cut to fill [r] (its middle band, the floor and the light kept), and the
     * pet's [icon] [size] px in its slot, standing on the game's shadow at ([cx], [footY]) = the icon's foot. The caller writes the
     * words over it.
     */
    fun petStage(c: Canvas, r: RectF, spec: String?, icon: Bitmap?, cx: Float, footY: Float, size: Float = 150f) {
        val bg = stableStage(spec)
        if (bg != null) {
            val want = r.width() / r.height()
            val sh = minOf(bg.height.toFloat(), bg.width / want)
            val y0 = (bg.height * 0.56f - sh / 2f).coerceIn(0f, bg.height - sh)
            c.drawBitmap(bg, Rect(0, y0.toInt(), bg.width, (y0 + sh).toInt()), r, pBmp)
        } else { pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(30, 22, 16); c.drawRect(r, pFlat) }
        tex("stable_shadow")?.let {
            val w = size * 1.7f; val h = w * it.height / it.width
            c.drawBitmap(it, null, RectF(cx - w / 2f, footY - h * 0.45f, cx + w / 2f, footY + h * 0.55f), pBmp)
        }
        slot(c, icon, cx - size / 2f, footY - size - 6f, size)
    }

    /** the pet's loyalty level ([level]) in the game's boss ring, [size] px, centred on ([cx], [cy]) */
    fun loyaltyRing(c: Canvas, cx: Float, cy: Float, size: Float, level: Int) {
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(16, 14, 18)
        c.drawCircle(cx, cy, size * 0.40f, pFlat)
        val ring = tex("stable_loyalty_ring")
        if (ring != null) c.drawBitmap(ring, null, RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f), pBmp)
        else { pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = size * 0.08f; pFlat.color = Theme.gold; c.drawCircle(cx, cy, size * 0.42f, pFlat) }
        val p = numberPaint(size * 0.46f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
        c.drawText(level.toString(), cx, cy - (p.ascent() + p.descent()) / 2f, p)
    }

    /**
     * A STABLE SLOT at ([x], [y]), [size] px: the game's empty slot with the pet's [icon] in it; a slot not bought yet ([open] false)
     * is the game's red slot; [dim] = the pet is in the hand (being moved), its slot greys.
     */
    fun petSlot(c: Canvas, x: Float, y: Float, size: Float, icon: Bitmap?, open: Boolean = true, dim: Boolean = false) {
        if (!open) {
            tex("slot")?.let {
                val o = size * 0.31f
                c.drawBitmap(it, null, RectF(x - o, y - o, x - o + size * 1.62f, y - o + size * 1.62f), pRed)
            }
            return
        }
        slot(c, icon, x, y, size, dim = dim)
    }

    // ---------------------------------------------------------------- the mailbox
    //
    // Forever's own MailFrame (blizzard_mailframe): the inbox's letters (MailItemTemplate), and an opened letter on the game's
    // paper (OpenMailFrame: From, Subject, the words, Take Attachments, the money).

    private val pSlotNew = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(1f, 0.82f, 0f, 1f) })     // the game's (1, 0.82, 0)
    }
    private val pSlotRead = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.5f, 0.5f, 0.5f, 1f) })   // (0.5, 0.5, 0.5)
    }

    /**
     * ONE LETTER IN THE INBOX (MailItemTemplate), [r] the whole row, 116 px tall or more like [itemPlate]: the letter's [icon] in the
     * game's white slot, tinted gold while not [read] and grey once read (the icon greyed too); "C.O.D." over the icon's foot when
     * it costs money ([cod]); [count] on the icon (its first item's); the [sender] on the top line (gold, grey once read), the
     * [subject] under it (white, grey once read), and the time left at the top line's right end: "29 Days" in green, under a day
     * the hours in red ([daysLeft], the game's fraction of days). [opened] = this letter is the one open beside the inbox: the
     * game's checked glow (CheckButtonHilight) on its slot, while the cursor works inside the letter.
     */
    fun mailRow(c: Canvas, r: RectF, icon: Bitmap?, sender: String, subject: String, daysLeft: Float, read: Boolean,
                cod: Boolean = false, count: Int = 0, opened: Boolean = false) {
        val s = r.height() - 20f
        val plate = tex("plate")
        val pr = RectF(r.left + s - 2f, r.top + 7f, r.right, r.bottom - 7f)
        if (plate != null) c.drawBitmap(plate, null, pr, pBmp)
        else { pFlat.style = Paint.Style.FILL; pFlat.color = Theme.raised; c.drawRect(pr, pFlat) }
        val x = r.left + 2f; val y = r.top + 10f
        val well = tex("slot_white")
        if (well != null) {
            val o = s * 0.31f
            c.drawBitmap(well, null, RectF(x - o, y - o, x - o + s * 1.62f, y - o + s * 1.62f), if (read) pSlotRead else pSlotNew)
        }
        val ir = RectF(x, y, x + s, y + s)
        if (icon != null) c.drawBitmap(icon, null, ir, if (read) pGrey else pBmp)
        if (count > 1) {
            val p = numberPaint(s * 0.34f, Color.WHITE).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(count.toString(), ir.right - 5f, ir.bottom - 6f, p)
        }
        if (cod) {
            // over the icon's top, as the game's: the count keeps its corner
            pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(200, 0, 0, 0)
            c.drawRect(ir.left, ir.top, ir.right, ir.top + s * 0.30f, pFlat)
            val p = bodyPaint(s * 0.21f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
            c.drawText("C.O.D.", ir.centerX(), ir.top + s * 0.15f - (p.ascent() + p.descent()) / 2f, p)
        }
        if (opened) tex("focus_gold")?.let { c.drawBitmap(it, null, RectF(ir.left - 4f, ir.top - 4f, ir.right + 4f, ir.bottom + 4f), pGlow) }
        val tx = r.left + s + 18f
        // the time left, from the right end of the top line
        val dl = if (daysLeft >= 1f) "${daysLeft.toInt()} " + (if (daysLeft.toInt() == 1) "Day" else "Days")
            else { val h = (daysLeft * 24f).toInt(); if (h >= 1) "$h Hr" else "${(daysLeft * 1440f).toInt().coerceAtLeast(1)} Min" }
        val dp = bodyPaint(22f, if (daysLeft >= 1f) Color.rgb(32, 255, 32) else Color.rgb(255, 32, 32)).apply { textAlign = Paint.Align.RIGHT }
        val top = r.top + 24f
        val np = bodyPaint(27f, if (read) Color.rgb(191, 191, 191) else Theme.gold)
        val base = top - np.ascent() * 0.78f
        c.drawText(dl, r.right - 18f, base, dp)
        val room = r.right - 18f - dp.measureText(dl) - 16f - tx
        c.drawText(TextUtils.ellipsize(sender, np, room, TextUtils.TruncateAt.END).toString(), tx, base, np)
        val sp = bodyPaint(24f, if (read) Color.rgb(191, 191, 191) else Color.WHITE)
        c.drawText(TextUtils.ellipsize(subject, sp, r.right - 18f - tx, TextUtils.TruncateAt.END).toString(), tx,
            r.bottom - 38f - (sp.ascent() + sp.descent()) / 2f, sp)
    }

    /**
     * THE GAME'S INBOX ROW (f-mailbox-look, 1.0.8; Blizzard_MailFrame's MailItemTemplate, read from the client): on the inbox's parchment
     * the MailItemBorder's dark icon square (its 42 x 48 left piece) and its faint bar (the rest), a brown rule under the row; the
     * letter's icon in the square (grey and dim once read, the slot's edge gold while unread); the sender in the game's gold
     * (GameFontNormal), the subject in white under it, both grey once read; the days left at the top right (green, or red hours on
     * the last day); "COD" over the icon of a C.O.D. letter; [opened] = the game's checked glow round the icon.
     */
    fun mailItem(c: Canvas, r: RectF, icon: Bitmap?, sender: String, subject: String, daysLeft: Float, read: Boolean,
                 cod: Boolean = false, count: Int = 0, opened: Boolean = false) {
        val k = r.height() / 48f
        val cellW = 42f * k
        val b = tex("mail_item_border")
        if (b != null) {
            c.drawBitmap(b, Rect(42, 0, 256, 48), RectF(r.left + cellW, r.top, r.right, r.bottom), pBmp)
            c.drawBitmap(b, Rect(0, 0, 42, 48), RectF(r.left, r.top, r.left + cellW, r.bottom), pBmp)
        } else {
            pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(40, 14, 2)
            c.drawRect(r.left + 2f * k, r.top + 2f * k, r.left + 40f * k, r.top + 40f * k, pFlat)
        }
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(77, 84, 41, 0)                // the game's rule: 0.33, 0.16, 0, .3
        c.drawRect(r.left, r.bottom - 2f, r.right, r.bottom, pFlat)
        // the icon in the square (the game's 37 px button 2.5 px in), its slot edge gold while unread
        val ir = RectF(r.left + 2.5f * k, r.top + 2.5f * k, r.left + 39.5f * k, r.top + 39.5f * k)
        if (icon != null) c.drawBitmap(icon, null, ir, if (read) pGrey else pBmp)
        pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f
        pFlat.color = if (read) Color.argb(160, 128, 128, 128) else Color.argb(220, 255, 209, 0)
        c.drawRect(ir, pFlat)
        if (count > 1) {
            val p = numberPaint(ir.height() * 0.34f, Color.WHITE).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(count.toString(), ir.right - 4f, ir.bottom - 5f, p)
        }
        if (cod) {
            pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(200, 120, 0, 0)
            c.drawRect(ir.left, ir.top, ir.right, ir.top + ir.height() * 0.32f, pFlat)
            val p = bodyPaint(ir.height() * 0.22f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
            c.drawText("COD", ir.centerX(), ir.top + ir.height() * 0.16f - (p.ascent() + p.descent()) / 2f, p)
        }
        if (opened) tex("focus_gold")?.let { c.drawBitmap(it, null, RectF(ir.left - 5f, ir.top - 5f, ir.right + 5f, ir.bottom + 5f), pGlow) }
        val tx = r.left + cellW + 12f
        // the days left (the game's DAYS_ABBR green, or the hours in red on the last day), from the right end of the top line
        val dl = if (daysLeft >= 1f) "${daysLeft.toInt()} " + (if (daysLeft.toInt() == 1) "Day" else "Days")
            else { val h = (daysLeft * 24f).toInt(); if (h >= 1) "$h Hr" else "${(daysLeft * 1440f).toInt().coerceAtLeast(1)} Min" }
        val dp = bodyPaint(r.height() * 0.24f, if (daysLeft >= 1f) Color.rgb(32, 255, 32) else Color.rgb(255, 32, 32)).apply { textAlign = Paint.Align.RIGHT }
        val np = bodyPaint(r.height() * 0.30f, if (read) Color.rgb(191, 191, 191) else Theme.gold)
        val base = r.top + r.height() * 0.40f
        c.drawText(dl, r.right - 10f, base, dp)
        val room = r.right - 10f - dp.measureText(dl) - 14f - tx
        c.drawText(TextUtils.ellipsize(sender, np, room, TextUtils.TruncateAt.END).toString(), tx, base, np)
        val sp = bodyPaint(r.height() * 0.26f, if (read) Color.rgb(191, 191, 191) else Color.WHITE)
        c.drawText(TextUtils.ellipsize(subject, sp, r.right - 10f - tx, TextUtils.TruncateAt.END).toString(), tx, r.top + r.height() * 0.80f, sp)
    }

    /** the inbox's parchment (the game's UI-MailFrameBG is a darker sheet than the quest parchment the kit holds: it is toned down to
     *  it), a thin dark edge round it */
    fun mailSheet(c: Canvas, r: RectF) {
        val p = tex("parchment")
        if (p != null) c.drawBitmap(p, null, r, pSheet) else { pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(176, 132, 72); c.drawRect(r, pFlat) }
        pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 3f; pFlat.color = Color.argb(200, 40, 24, 8)
        c.drawRect(r, pFlat)
    }
    private val pSheet = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = LightingColorFilter(Color.rgb(206, 200, 196), 0) }

    /** the game's radio button (the dropdown's radial tick, the kit's menu_radio / _on) at ([cx], [cy]), [size] across */
    fun radio(c: Canvas, cx: Float, cy: Float, size: Float, on: Boolean) {
        val b = tex(if (on) "menu_radio_on" else "menu_radio")
        val r = RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        if (b != null) { c.drawBitmap(b, null, r, pBmp); return }
        pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 3f; pFlat.color = Theme.gold
        c.drawCircle(cx, cy, size * 0.4f, pFlat)
        if (on) { pFlat.style = Paint.Style.FILL; c.drawCircle(cx, cy, size * 0.2f, pFlat) }
    }

    /**
     * THE GAME'S LETTER PAPER over [r] (the default stationery: its left piece stretched across, the right piece with its torn
     * edge at the right end, both stretched to [r]'s height). Returns the writing area inside it.
     */
    fun letterPaper(c: Canvas, r: RectF): RectF {
        val l = tex("mail_paper_left"); val rt = tex("mail_paper_right")
        if (l != null && rt != null) {
            val rw = r.height() * rt.width / rt.height * 1.6f
            c.drawBitmap(l, null, RectF(r.left, r.top, r.right - rw + 1f, r.bottom), pBmp)
            c.drawBitmap(rt, null, RectF(r.right - rw, r.top, r.right, r.bottom), pBmp)
            return RectF(r.left + 34f, r.top + 28f, r.right - rw * 0.55f, r.bottom - 24f)
        }
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(222, 196, 140)        // the paper's own colour, flat
        c.drawRect(r, pFlat)
        return RectF(r.left + 30f, r.top + 26f, r.right - 30f, r.bottom - 24f)
    }

    // ---------------------------------------------------------------- the auction house
    //
    // Forever's own AuctionHouseFrame (blizzard_auctionhouseui, shared + camelot): the categories down the left (three levels of
    // button), the results as a table (Name, Price, Available), and for the result chosen the game's buy frames: a commodity's
    // price tiers with Quantity / Unit Price / Total Price / Buy Now, or an item's listings.

    /** [b]'s rows [sy0]..[sy1] drawn over [dst], its two ends ([cap] source px each) kept and its middle stretched */
    private fun slice3(c: Canvas, b: Bitmap, sx0: Int, sx1: Int, sy0: Int, sy1: Int, cap: Int, dst: RectF, p: Paint = pBmp) {
        val k = dst.height() / (sy1 - sy0)
        val cw = cap * k
        c.drawBitmap(b, Rect(sx0 + cap, sy0, sx1 - cap, sy1), RectF(dst.left + cw - 0.5f, dst.top, dst.right - cw + 0.5f, dst.bottom), p)
        c.drawBitmap(b, Rect(sx0, sy0, sx0 + cap, sy1), RectF(dst.left, dst.top, dst.left + cw, dst.bottom), p)
        c.drawBitmap(b, Rect(sx1 - cap, sy0, sx1, sy1), RectF(dst.right - cw, dst.top, dst.right, dst.bottom), p)
    }

    /**
     * A CATEGORY of the auction house over [r]: [level] 0 the game's main button, 1 its secondary button (indented by the
     * caller), 2 a tertiary filter (no button: the game's filter line and the words); [selected] = the game's gold edge, the
     * category browsed now. Its [label] in gold (white when selected).
     */
    fun ahNav(c: Canvas, r: RectF, label: String, level: Int = 0, selected: Boolean = false) {
        val p = bodyPaint(if (level == 2) 24f else 26f, if (selected) Color.WHITE else Theme.gold)
        val ty = r.centerY() - (p.ascent() + p.descent()) / 2f
        if (level >= 2) {
            tex("ah_nav3_line")?.let { c.drawBitmap(it, null, RectF(r.left + 22f, r.centerY() - 11f, r.left + 32f, r.centerY() + 11f), pBmp) }
            c.drawText(TextUtils.ellipsize(label, p, r.width() - 54f, TextUtils.TruncateAt.END).toString(), r.left + 44f, ty, p)
            return
        }
        val b = tex(if (level == 0) "ah_nav" else "ah_nav2")
        // the art's button is its top 42 rows (below them its shadow); x 4..267 (the main) / 18..261 (the secondary)
        if (b != null) slice3(c, b, if (level == 0) 4 else 18, if (level == 0) 268 else 262, 0, 42, 22, r)
        else { pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(34, 30, 26); c.drawRoundRect(r, 6f, 6f, pFlat) }
        if (selected) tex(if (level == 0) "ah_nav_select" else "ah_nav2_select")?.let { slice3(c, it, 0, it.width, 0, it.height, 22, r) }
        c.drawText(TextUtils.ellipsize(label, p, r.width() - 40f, TextUtils.TruncateAt.END).toString(), r.left + 22f, ty, p)
    }

    /** a panel's ground: the game's picture for it ("ah_bg_categories", "ah_bg_index", "ah_bg_commodities", "ah_bg_market",
     *  "ah_bg_item_market", "ah_bg_auctions") stretched over [r]; a dark fill without it */
    fun ahPanel(c: Canvas, r: RectF, name: String) {
        val b = tex(name)
        if (b != null) c.drawBitmap(b, null, r, pBmp)
        else { pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(20, 18, 16); c.drawRect(r, pFlat) }
    }

    /** a table's column headings ([cols]: the words and where each starts; a negative x = right-aligned at -x), gold, on [cy] */
    fun ahHeader(c: Canvas, left: Float, right: Float, cy: Float, cols: List<Pair<String, Float>>) {
        val p = bodyPaint(22f, Theme.gold)
        for ((w, x) in cols) {
            p.textAlign = if (x < 0f) Paint.Align.RIGHT else Paint.Align.LEFT
            c.drawText(w, if (x < 0f) -x else x, cy - (p.ascent() + p.descent()) / 2f, p)
        }
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(160, 96, 80, 50)
        c.drawRect(left, cy + 20f, right, cy + 22f, pFlat)
    }

    /** a table row's ground: every second row ([odd]) the game's faint stripe */
    fun ahRowGround(c: Canvas, r: RectF, odd: Boolean) {
        if (!odd) return
        val s = tex("ah_row_stripe")
        if (s != null) c.drawBitmap(s, null, r, pBmp)
        else { pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(28, 255, 255, 255); c.drawRect(r, pFlat) }
    }

    /** the item at the head of a buy frame (AuctionHouseItemDisplay): the game's item header frame over [r], the [icon] in its
     *  slot at the left, the [name] in its quality [colour] */
    fun ahItemHeader(c: Canvas, r: RectF, icon: Bitmap?, name: String, colour: Int, count: Int = 0) {
        val f = tex("ah_item_header")
        if (f != null) c.drawBitmap(f, null, r, pBmp)
        else { pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(24, 22, 20); c.drawRect(r, pFlat) }
        val s = r.height() - 40f
        slot(c, icon, r.left + 24f, r.top + 20f, s, count = count)
        val p = bodyPaint(32f, colour)
        c.drawText(TextUtils.ellipsize(name, p, r.width() - s - 70f, TextUtils.TruncateAt.END).toString(), r.left + s + 52f,
            r.centerY() - (p.ascent() + p.descent()) / 2f, p)
    }

    // ---------------------------------------------------------------- the flight master
    //
    // Forever's own FlightMapFrame (blizzard_flightmap): the continent's map picture (the app's assets/maps/<uiMapID>.webp,
    // the game's painted art, covering the map's 0..1), the game's flight points on it, and the route to the one chosen.

    /** a flight point's state, the game's marker for each: you are here (green), can fly (yellow), not discovered (grey) */
    enum class Taxi { HERE, CAN, CANNOT }

    /** the game's flight point (the winged boot) for [state], [size] px, centred on ([cx], [cy]) */
    fun taxiNode(c: Canvas, cx: Float, cy: Float, size: Float, state: Taxi) {
        val b = tex(when (state) { Taxi.HERE -> "taxi_here"; Taxi.CAN -> "taxi_can"; Taxi.CANNOT -> "taxi_cannot" })
        if (b != null) { c.drawBitmap(b, null, RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f), pBmp); return }
        pFlat.style = Paint.Style.FILL
        pFlat.color = when (state) { Taxi.HERE -> Color.rgb(60, 200, 60); Taxi.CAN -> Theme.gold; Taxi.CANNOT -> Color.rgb(130, 130, 130) }
        c.drawCircle(cx, cy, size * 0.28f, pFlat)
    }

    /**
     * THE FLIGHT MAP'S EDGE (FlightMapFrame's AdventureMap_TopBorder): the game's soft dark inner edge, stretched over the map
     * picture's rectangle [r], so the painted map sits in the window like the game's rather than pasted on. Draw it over the map,
     * under the points. Without the picture: a 2 px bronze line.
     */
    fun flightEdge(c: Canvas, r: RectF) {
        val v = tex("flight_vignette")
        if (v != null) { c.drawBitmap(v, null, r, pBmp); return }
        pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Theme.bronze; c.drawRect(r, pFlat)
    }

    private val mLine = Matrix()

    /**
     * THE ROUTE (the game's taxi line) through [pts] in screen px, from the start to the end; the game's nub at every stop
     * between. [width] = the line's thickness. [lit] = the route to the point chosen, ADDED as the game draws its hover line (a
     * bright double line); otherwise blended (a dark line with a pale edge). Draw it under the flight points.
     */
    fun taxiRoute(c: Canvas, pts: List<android.graphics.PointF>, width: Float = 32f, lit: Boolean = true) {
        if (pts.size < 2) return
        val line = tex("taxi_line")
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            val len = kotlin.math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
            val ang = Math.toDegrees(kotlin.math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
            c.save()
            c.rotate(ang, a.x, a.y)
            if (line != null) {
                // the art's line is its rows 9..23 of 32: those rows stretched along the segment
                c.drawBitmap(line, Rect(0, 9, line.width, 23), RectF(a.x, a.y - width / 2f, a.x + len, a.y + width / 2f), if (lit) pGlow else pBmp)
                if (lit) c.drawBitmap(line, Rect(0, 9, line.width, 23), RectF(a.x, a.y - width / 2f, a.x + len, a.y + width / 2f), pGlow)
            } else {
                pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(255, 247, 197)
                c.drawRect(a.x, a.y - width * 0.3f, a.x + len, a.y + width * 0.3f, pFlat)
            }
            c.restore()
        }
        val nub = tex("taxi_nub")
        for (i in 1 until pts.size - 1) {
            val p = pts[i]; val s = width * 1.1f
            if (nub != null) c.drawBitmap(nub, null, RectF(p.x - s / 2f, p.y - s / 2f, p.x + s / 2f, p.y + s / 2f), pBmp)
        }
    }

    // ---------------------------------------------------------------- the bank
    //
    // Forever's own BankFrame (camelot): the bank's slots on its dark stone, its bag slots under a divider (a lock on one not
    // bought), Cost and Purchase; the game opens the player's bags beside it.

    /** the bank's stone ground over [r] (bank-frame-background, tiled at its own size) */
    fun bankGround(c: Canvas, r: RectF) {
        val g = tex("bank_ground")
        if (g == null) { pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(34, 30, 26); c.drawRect(r, pFlat); return }
        c.save(); c.clipRect(r)
        var y = r.top
        while (y < r.bottom) { var x = r.left; while (x < r.right) { c.drawBitmap(g, x, y, pBmp); x += g.width }; y += g.height }
        c.restore()
        pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Color.argb(160, 0, 0, 0); c.drawRect(r, pFlat)
    }

    /**
     * ONE BANK SLOT at [r] (bank-frame-item-slotframe): the item's [icon] inside, the quality edge ([quality] >= 2, the game's), the
     * [count]; [bag] = a bank BAG slot (bank-frame-bag-slotframe on its well); [locked] = a bag slot not bought (the game's lock).
     */
    fun bankSlot(c: Canvas, r: RectF, icon: Bitmap?, quality: Int = -1, count: Int = 0, bag: Boolean = false, locked: Boolean = false) {
        val inset = RectF(r.left + r.width() * 0.09f, r.top + r.height() * 0.09f, r.right - r.width() * 0.09f, r.bottom - r.height() * 0.09f)
        if (bag) tex("bank_bagslot_bg")?.let { c.drawBitmap(it, null, inset, pBmp) }
        else { pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(150, 0, 0, 0); c.drawRect(inset, pFlat) }
        if (icon != null && !locked) c.drawBitmap(icon, null, inset, pBmp)
        val f = tex(if (bag) "bank_bagslot" else "bank_slot")
        if (f != null) c.drawBitmap(f, null, r, pBmp)
        else { pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Theme.bronzeDim; c.drawRect(r, pFlat) }
        if (quality >= 2 && icon != null && !locked) {
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 3f; pFlat.color = Theme.quality(quality); c.drawRect(inset, pFlat)
        }
        if (locked) tex("bank_lock")?.let { val s = r.width() * 0.5f; c.drawBitmap(it, null, RectF(r.centerX() - s / 2f, r.centerY() - s / 2f, r.centerX() + s / 2f, r.centerY() + s / 2f), pBmp) }
        if (count > 1 && !locked) {
            val p = numberPaint(r.width() * 0.27f, Color.WHITE).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(count.toString(), inset.right - 2f, inset.bottom - 3f, p)
        }
    }

    /** the bank's divider (bank-divider) from [x0] to [x1] on [y] */
    fun bankDivider(c: Canvas, x0: Float, x1: Float, y: Float) {
        val d = tex("bank_divider")
        if (d != null) c.drawBitmap(d, null, RectF(x0, y - 8f, x1, y + 8f), pBmp)
        else { pFlat.style = Paint.Style.FILL; pFlat.color = Theme.bronzeDim; c.drawRect(x0, y - 1f, x1, y + 1f, pFlat) }
    }

    // ---------------------------------------------------------------- the loot rolls
    //
    // Forever's controller window for group loot (GamepadGroupLootRollFrame, "Loot Rolls"): up to 4 roll cards
    // (GroupLootFrameBaseTemplate on the game's LootToast art), the one chosen marked, its footer A Need, X Greed, B Pass.

    /** the loot border picture for an item quality: 0 poor .. 5 legendary (the game's ColorManager mapping) */
    fun lootBorder(quality: Int): String = "loot_border_" + when (quality) { 0 -> "gray"; 1 -> "white"; 2 -> "green"; 3 -> "blue"; 4 -> "purple"; else -> "orange" }

    private val pTintBorder = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * ONE ROLL CARD (GroupLootFrameBaseTemplate) over [r] (the game's card is 277 x 67; [r] scales it): the LootToast ground, its
     * frame tinted the item's [colour] (quality), the [icon] in the [quality]'s loot border with its [count], the name in [colour]
     * (two lines at most), the timer draining ([timer] 1 = full, 0 = out: the game's yellow bar), and the card's own roll icons
     * (the game's: Greed / Transmogrification top left, Pass top right, Need below between them), each greyed when it cannot be done.
     * The cursor is the caller's (UiKit.focus round [r]).
     */
    fun rollCard(c: Canvas, r: RectF, icon: Bitmap?, name: String, quality: Int, colour: Int, count: Int = 1, timer: Float = 1f,
                 canNeed: Boolean = true, canGreed: Boolean = true, transmog: Boolean = false) {
        val k = r.height() / 67f
        val toast = tex("loot_toast")
        if (toast != null) {
            val w = toast.width; val h = toast.height
            c.drawBitmap(toast, Rect((0.28222656f * w).toInt(), (0.30859375f * h).toInt(), (0.55273438f * w).toInt(), (0.5703125f * h).toInt()), r, pBmp)
            pTintBorder.colorFilter = android.graphics.PorterDuffColorFilter(colour, PorterDuff.Mode.MULTIPLY)
            val bw = r.width() * 286f / 277f; val bh = r.height() * 76f / 67f
            c.drawBitmap(toast, Rect((0.00097656f * w).toInt(), (0.4375f * h).toInt(), (0.28027344f * w).toInt(), (0.734375f * h).toInt()),
                RectF(r.centerX() - bw / 2f, r.centerY() - bh / 2f, r.centerX() + bw / 2f, r.centerY() + bh / 2f), pTintBorder)
        } else {
            pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(18, 16, 14); c.drawRect(r, pFlat)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 3f; pFlat.color = colour; c.drawRect(r, pFlat)
        }
        // the timer: the game's yellow bar along the card's foot, on black, draining
        val tr = RectF(r.left + 3f * k, r.bottom - 10f * k, r.left + 193f * k, r.bottom - 2f * k)
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.BLACK; c.drawRect(tr, pFlat)
        val tw = tr.width() * timer.coerceIn(0f, 1f)
        if (tw > 0.5f) {
            pFlat.color = Color.rgb(230, 200, 0); c.drawRect(tr.left, tr.top, tr.left + tw, tr.bottom, pFlat)
            pFlat.color = Color.rgb(255, 236, 90); c.drawRect(tr.left, tr.top, tr.left + tw, tr.centerY(), pFlat)
        }
        // the item: its icon in the quality's loot border, the count
        val ir = RectF(r.left + 10f * k, r.top + 11f * k, r.left + 44f * k, r.top + 45f * k)
        if (icon != null) c.drawBitmap(icon, null, ir, pBmp)
        tex(lootBorder(quality))?.let { val b = 21f * k; c.drawBitmap(it, null, RectF(ir.centerX() - b, ir.centerY() - b + 2f * k, ir.centerX() + b, ir.centerY() + b + 2f * k), pBmp) }
        if (count > 1) {
            val p = numberPaint(13f * k, Color.WHITE).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(count.toString(), ir.right - 3f * k, ir.bottom - 2f * k, p)
        }
        // the name: the game's 125 x 30 box at (60, 15), two lines at most
        val np = TextPaint(bodyPaint(12.5f * k, colour))
        val lay = StaticLayout.Builder.obtain(name, 0, name.length, np, (125f * k).toInt()).setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 0.92f).build()
        c.save(); c.translate(r.left + 60f * k, r.top + 30f * k - lay.height / 2f); lay.draw(c); c.restore()
        // the roll icons: right of the name (x 188 .. the card's end)
        val x0 = r.left + 188f * k; val x1 = r.right - 3f * k
        val s = 30f * k
        fun icon(name: String, cx: Float, cy: Float, on: Boolean) {
            tex(name)?.let { c.drawBitmap(it, null, RectF(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f), if (on) pBmp else pGrey) }
        }
        val topY = r.top + 23f * k
        icon(if (transmog) "roll_transmog" else "roll_greed", x0 + 10f * k + s / 2f, topY, canGreed || transmog)
        icon("roll_pass", x1 - s / 2f, topY, true)
        icon("roll_need", (x0 + x1) / 2f, r.bottom - 1f * k - s / 2f - 4f * k, canNeed)
    }

    /**
     * A ROLL BUTTON as the game's own art (the user: "I want the need greed and pass buttons to actually be the in game assets"):
     * the loot roll's dice (need), coin (greed), no-sign (pass) or transmogrification, [size] px centred on ([cx], [cy]); [down] =
     * the game's pressed picture, [lit] = its highlight ADDED (a finger on it); not [enabled] = greyed, as the game disables it. The
     * controller's button that does it, [glyph], small on the lower left corner (as the kit's squares carry theirs); [label] under it.
     */
    fun rollButton(c: Canvas, cx: Float, cy: Float, size: Float, kind: String, glyph: String?, enabled: Boolean = true,
                   down: Boolean = false, lit: Boolean = false, label: String? = null) =
        pressed(c, RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f), down && enabled) {
        val r = RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        val b = tex("roll_${kind}" + if (down) "_down" else "") ?: tex("roll_$kind")
        if (b != null) c.drawBitmap(b, null, r, if (enabled) pBmp else pGrey)
        else { pFlat.style = Paint.Style.FILL; pFlat.color = if (enabled) Theme.gold else Theme.dim; c.drawCircle(cx, cy, size * 0.38f, pFlat) }
        if (lit && enabled) tex("roll_${kind}_hl")?.let { c.drawBitmap(it, null, r, pGlow) }
        if (glyph != null) pad(c, glyph, r.left + size * 0.10f, r.bottom - size * 0.24f, size * 0.44f, if (enabled) 255 else 90)
        if (label != null) {
            val p = bodyPaint(22f, if (enabled) Theme.gold else Theme.dim).apply { textAlign = Paint.Align.CENTER }
            c.drawText(label, cx, r.bottom + 6f - p.ascent(), p)
        }
    }

    /** how wide [money] writes [copper] at [size] */
    fun moneyWidth(copper: Long, size: Float = 27f): Float {
        val p = numberPaint(size, Theme.text)
        val coin = size * 0.95f
        var w = -12f
        if (copper >= 10000) w += p.measureText((copper / 10000).toString()) + 4f + coin + 12f
        if ((copper / 100) % 100 > 0) w += p.measureText(((copper / 100) % 100).toString()) + 4f + coin + 12f
        if (copper % 100 > 0 || copper < 100) w += p.measureText((copper % 100).toString()) + 4f + coin + 12f
        return w
    }

    private const val QTY_SQUARE = 76f
    private const val QTY_MORE = 22f + QTY_SQUARE / 2f              // plus's centre, from the row's right end
    private const val QTY_LESS = QTY_MORE + QTY_SQUARE + 64f         // minus's centre: the count between them

    /**
     * An item icon in the game's slot well, [size] px. [edge] is a quality colour (0 = none);
     * [unusable] turns the icon red, [dim] turns it grey (it cannot be had).
     */
    fun slot(c: Canvas, icon: Bitmap?, x: Float, y: Float, size: Float, edge: Int = 0, count: Int = 0,
             unusable: Boolean = false, dim: Boolean = false) {
        tex("slot")?.let {
            val o = size * 0.31f
            c.drawBitmap(it, null, RectF(x - o, y - o, x - o + size * 1.62f, y - o + size * 1.62f), pBmp)
        }
        val r = RectF(x, y, x + size, y + size)
        if (icon != null) c.drawBitmap(icon, null, r, when { dim -> pGrey; unusable -> pRed; else -> pBmp })
        if (edge != 0) {
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 3f; pFlat.color = edge
            c.drawRect(r, pFlat)
        }
        if (count > 1) {
            val p = numberPaint(size * 0.34f, if (dim) Theme.dim else Color.WHITE).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(count.toString(), r.right - 5f, r.bottom - 6f, p)
        }
    }

    /**
     * Money the way the game writes it, 2 [s] 50 [c], starting at [x] (or ending there with
     * [fromRight]), centred on [cy]. Returns the x where it ended.
     */
    fun money(c: Canvas, copper: Long, x: Float, cy: Float, size: Float = 27f, colour: Int = Theme.text,
              fromRight: Boolean = false, shadow: Boolean = true): Float {
        val p = numberPaint(size, colour, shadow)
        val coin = size * 0.95f
        val parts = ArrayList<Pair<Long, String>>()
        if (copper >= 10000) parts.add(copper / 10000 to "coin_gold")
        if ((copper / 100) % 100 > 0) parts.add((copper / 100) % 100 to "coin_silver")
        if (copper % 100 > 0 || parts.isEmpty()) parts.add(copper % 100 to "coin_copper")
        var w = -12f
        for ((v, _) in parts) w += p.measureText(v.toString()) + 4f + coin + 12f
        var px = if (fromRight) x - w else x
        val base = cy - (p.ascent() + p.descent()) / 2f
        for ((v, name) in parts) {
            c.drawText(v.toString(), px, base, p)
            px += p.measureText(v.toString()) + 4f
            val b = tex(name)
            if (b != null) c.drawBitmap(b, null, RectF(px, cy - coin / 2f, px + coin, cy + coin / 2f), pBmp)
            else { pFlat.style = Paint.Style.FILL; pFlat.color = Theme.gold; c.drawCircle(px + coin / 2f, cy, coin / 2.4f, pFlat) }
            px += coin + 12f
        }
        return px - 12f
    }

    /**
     * The game's scroll bar down the right of a list, as a sign of where the list is: [from] and
     * [to] are the shown part, 0..1. It is not a control; the list is dragged.
     */
    fun scrollBar(c: Canvas, r: RectF, from: Float, to: Float) {
        val top = tex("scroll_top"); val mid = tex("scroll_mid"); val bot = tex("scroll_bottom")
        val kt = tex("scroll_knob_top"); val kb = tex("scroll_knob_bottom")
        if (top == null || mid == null || bot == null || kt == null || kb == null) {
            pFlat.style = Paint.Style.FILL; pFlat.color = Theme.bronzeDim
            c.drawRect(r.right - 6f, r.top + r.height() * from, r.right, r.top + r.height() * to, pFlat)
            return
        }
        val k = r.width() / top.width
        val th = top.height * k; val bh = bot.height * k
        repeat(c, mid, r.left, r.top + th, r.bottom - bh, false, k)
        c.drawBitmap(top, null, RectF(r.left, r.top, r.right, r.top + th), pBmp)
        c.drawBitmap(bot, null, RectF(r.left, r.bottom - bh, r.right, r.bottom), pBmp)
        val y0 = r.top + 14f + (r.height() - 28f) * from.coerceIn(0f, 1f)
        val y1 = r.top + 14f + (r.height() - 28f) * to.coerceIn(0f, 1f)
        val kw = kt.width * k; val kx = r.centerX() - kw / 2f
        val kh = kt.height * k
        if (y1 - y0 > 2f * kh)
            c.drawBitmap(kt, Rect(0, kt.height - 4, kt.width, kt.height), RectF(kx, y0 + kh, kx + kw, y1 - kh), pBmp)
        c.drawBitmap(kt, null, RectF(kx, y0, kx + kw, y0 + kh), pBmp)
        c.drawBitmap(kb, null, RectF(kx, y1 - kh, kx + kw, y1), pBmp)
    }

    /**
     * The highlight ON PARCHMENT (the row a finger or the controller is on). The game's highlight
     * bar adds light, which does not show on paper, so here it darkens the paper instead.
     */
    fun paperGlow(c: Canvas, r: RectF, strength: Float = 0.45f) {
        val bar = tex("highlight") ?: return
        val a = -strength
        pPaper.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0.299f * a * 0.55f, 0.587f * a * 0.55f, 0.114f * a * 0.55f, 0f, 255f,
            0.299f * a * 0.75f, 0.587f * a * 0.75f, 0.114f * a * 0.75f, 0f, 255f,
            0.299f * a, 0.587f * a, 0.114f * a, 0f, 255f,
            0f, 0f, 0f, 0f, 255f)))
        c.drawBitmap(bar, null, r, pPaper)
    }

    /**
     * THE CONTROLLER'S FOCUS: the one element the pad is on. The same frame for every kind (a
     * row, a button, a tab, an item, a reward), so it reads as one cursor: THE GOLD HIGHLIGHT (the user, 2026-09-30, look 2
     * and LATER 19): the game's highlight bar in gold under a gold line a little outside the element. [onPaper] = a line of a
     * parchment page: the same look, drawn for the light paper ([paperGold] look 2). A round element takes [focusRing].
     */
    /** the focused-quest card's plate over the map, at rest (Home's quest-above-the-map; the user, b120): see-through */
    const val CARD_OVER_MAP_ALPHA = 140

    /** the cursor's wash: a softer, warmer gold than [Theme.gold] (the user, LATER 19), shared by the gold box and the lit tab */
    val SOFT_GOLD = Color.rgb(226, 170, 80)

    fun focus(c: Canvas, r: RectF, onPaper: Boolean = false) {
        // on a parchment page: GOLD, look 2 (the user, 2026-09-30: "2, gold bar with a gold edge"), no more darkened paper
        if (onPaper) { paperGold(c, r, 2); return }
        // on the dark ground THE SAME HIGHLIGHT (the user, LATER 19: "the yellow UI cursor is still being used in a LOT of places,
        // this needs to get updated to the highlight"): the game's highlight bar in gold under the gold edge
        // the user, again (2026-09-30): "I hate the golden outline as the cursor it looks AI generated" and "The Golden box looks great":
        // the BOX is the gold bar itself, clearly lit; the edge only finishes it (it is never the cursor alone)
        // LATER 19 (the user, 2026-10-07, after the lit tabs: "Soften it to match the tabs"): the wash a SOFTER, WARMER gold, the
        // same amber as a lit tab's; the gold edge keeps its weights
        glow(c, r, SOFT_GOLD, 0.62f)
        glow(c, RectF(r.left + r.width() * 0.08f, r.top, r.right - r.width() * 0.08f, r.bottom), SOFT_GOLD, 0.22f)
        val o = RectF(r.left - 5f, r.top - 5f, r.right + 5f, r.bottom + 5f)
        pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 8f
        pFlat.color = if (onPaper) Color.argb(60, 42, 24, 8) else Color.argb(70, 255, 209, 0)
        c.drawRoundRect(o, 8f, 8f, pFlat)
        pFlat.strokeWidth = 3.5f
        pFlat.color = if (onPaper) Theme.parchmentInk else Theme.gold
        c.drawRoundRect(o, 8f, 8f, pFlat)
    }

    /** THE CONTROLLER'S FOCUS ON A ROUND THING (a map's quest bubble, Home's disc on the bar): [focus]'s two gold strokes as a
     *  ring of radius [r] round ([cx], [cy]); a round element never gets the square frame */
    fun focusRing(c: Canvas, cx: Float, cy: Float, r: Float) {
        // a FILLED gold light under the ring (no bare ring: the user rejects a cursor that is only an outline)
        val g = RadialGradient(cx, cy, r * 1.15f, intArrayOf(Color.argb(150, 255, 209, 0), Color.argb(70, 255, 190, 0), Color.argb(0, 255, 190, 0)),
            floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
        pFlat.style = Paint.Style.FILL; pFlat.shader = g
        c.drawCircle(cx, cy, r * 1.15f, pFlat)
        pFlat.shader = null
        pFlat.style = Paint.Style.STROKE
        pFlat.strokeWidth = 9f; pFlat.color = Color.argb(70, 255, 209, 0)
        c.drawCircle(cx, cy, r, pFlat)
        pFlat.strokeWidth = 4f; pFlat.color = Theme.gold
        c.drawCircle(cx, cy, r, pFlat)
    }

    /** the shoulder button's GLYPH in the layout the Settings chose ("rev_lshoulder" / "ltr_..." / "shp_..."), for [pad] */
    fun shoulderGlyph(right: Boolean): String = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> if (right) "ltr_rshoulder" else "ltr_lshoulder"
        CastSettings.ButtonLayout.PLAYSTATION -> if (right) "shp_rshoulder" else "shp_lshoulder"
        else -> if (right) "rev_rshoulder" else "rev_lshoulder"
    }

    // ---------------------------------------------------------------- the controller on a tab (LATER 19)
    //
    // The user, 2026-10-07: tabs "look very ugly with todays boxs"; on the lit tab "I like that a lot, however that highlight is a bit
    // intense and too yellow. it should be a softer gold"; "I also like the Idea of the golden outline to match the other cursor
    // outlines"; the pick: "2: lit + the gold edge shaped to the tab". The game never boxes a tab: it lights the tab itself.

    private val pTabLift = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD); colorFilter = LightingColorFilter(Color.rgb(205, 160, 90), 0); alpha = 80
    }
    private val pTabWash = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
    private val tabEdges = HashMap<String, Bitmap>()

    /**
     * THE CONTROLLER ON A TAB: [draw] (the tab drawn plainly into the canvas it is given) LIT in the soft gold inside its own shape
     * (an amber wash painted only where the tab's art is, strongest across the middle, then the art added back warm for a lift), and
     * the gold box's EDGE (8 px soft + 3.5 px gold) traced round the tab's own silhouette, tight to it, never a rectangle. [key]
     * names the tab's look (kind + state) for the traced edge, which is kept per key and size. The kit's tab pieces call it
     * themselves with their `cursor` flag; a room's own tab can call it directly.
     */
    fun tabCursor(c: Canvas, r: RectF, key: String, draw: (Canvas) -> Unit) {
        val b = RectF(r.left - 8f, r.top - 8f, r.right + 8f, r.bottom + 8f)
        val l = c.saveLayer(b, null)
        draw(c)
        pTabWash.shader = LinearGradient(0f, r.top, 0f, r.bottom,
            intArrayOf(Color.argb(40, 232, 182, 96), Color.argb(100, 226, 170, 80), Color.argb(50, 210, 150, 70)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(b, pTabWash)
        c.restoreToCount(l)
        val l2 = c.saveLayer(b, pTabLift)
        draw(c)
        c.restoreToCount(l2)
        val k = key + "|" + r.width().toInt() + "x" + r.height().toInt() + "|" + (tex("button_up_center") != null)
        if (tabEdges.size > 32) { tabEdges.values.forEach { it.recycle() }; tabEdges.clear() }
        val edge = tabEdges.getOrPut(k) { traceTabEdge(r, draw) }
        c.drawBitmap(edge, r.left - TAB_EDGE_PAD, r.top - TAB_EDGE_PAD, null)
    }

    private const val TAB_EDGE_PAD = 14

    /** the gold edge round the tab's silhouette: the tab drawn into a bitmap, grown (drawn at the points of a ring) and the tab cut
     *  out again; the 8 px soft ring at 70 alpha, then the 3.5 px gold one */
    private fun traceTabEdge(r: RectF, draw: (Canvas) -> Unit): Bitmap {
        val w = r.width().toInt() + 2 * TAB_EDGE_PAD; val h = r.height().toInt() + 2 * TAB_EDGE_PAD
        val sil = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(sil).apply { translate(TAB_EDGE_PAD - r.left, TAB_EDGE_PAD - r.top); draw(this) }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        val cut = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        for ((grow, colour) in listOf(8f to Color.argb(70, 255, 209, 0), 3.5f to Theme.gold)) {
            val ring = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val rc = Canvas(ring)
            val tint = Paint().apply { colorFilter = PorterDuffColorFilter(colour, PorterDuff.Mode.SRC_IN) }
            for (i in 0 until 24) {
                val a = 2.0 * Math.PI * i / 24
                rc.drawBitmap(sil, (grow * Math.cos(a)).toFloat(), (grow * Math.sin(a)).toFloat(), tint)
            }
            rc.drawBitmap(sil, 0f, 0f, cut)
            oc.drawBitmap(ring, 0f, 0f, null)
            ring.recycle()
        }
        sil.recycle()
        return out
    }

    /**
     * THE LB / RB GLYPH BETWEEN THE TABS (the user, 2026-10-07: "LB/RB should show the glyph on the tab that it will swap to", then on
     * b116's corner badge: "SUPER ugly ... it should be centered in the tab inside of a buffer space to the next tab", pick "A: in the
     * gap between the tabs"): the shoulder glyph that moves the page to tab [r], centred in the GAP between [r] and the chosen tab, so
     * it covers no tab, word or picture. RB ([right]) = [r] is the next tab (right of / under the chosen one): the glyph in the gap
     * before [r]; LB = [r] is the previous one: the gap after it. [vertical] = tabs stacked down the screen (side tabs). [gap] = the
     * room's space between two tabs ([TAB_GAP_H] / [TAB_GAP_V]; lay the tabs out with [tabRow] / [tabColumn]); the glyph is sized to
     * fit it, at most [width] wide. A neighbour LB / RB does not reach (no wrap) gets none. Drawn after the tabs.
     */
    fun tabBadge(c: Canvas, r: RectF, right: Boolean, vertical: Boolean = false, width: Float = 60f,
                 gap: Float = if (vertical) TAB_GAP_V else TAB_GAP_H) {
        if (gap < 24f) return                                     // a gap still opening: the glyph comes when there is room for it
        val name = "pad_" + shoulderGlyph(right)
        val g = tex(name) ?: return
        // the glyph's INK inside its square picture (the shoulder glyphs are wide and short, drawn in a 64 x 64 box): sized and centred
        // by the ink, so it fills the gap whatever the button set
        val ink = glyphInk.getOrPut(name) { inkBounds(g) }
        val k = minOf(width / ink.width(), (if (vertical) gap - 8f else 54f) / ink.height(),
            if (vertical) Float.MAX_VALUE else (gap - 10f) / ink.width())
        val cx = if (vertical) r.centerX() else if (right) r.left - gap / 2f else r.right + gap / 2f
        val cy = if (!vertical) r.centerY() else if (right) r.top - gap / 2f else r.bottom + gap / 2f
        val left = cx - (ink.left + ink.width() / 2f) * k
        val top = cy - (ink.top + ink.height() / 2f) * k
        c.drawBitmap(g, null, RectF(left, top, left + g.width * k, top + g.height * k), pBmp)
    }

    private val glyphInk = HashMap<String, RectF>()

    /** the bounds of [b]'s visible pixels (alpha over 40), in its own pixels */
    private fun inkBounds(b: Bitmap): RectF {
        var x0 = b.width; var y0 = b.height; var x1 = -1; var y1 = -1
        for (y in 0 until b.height) for (x in 0 until b.width) if ((b.getPixel(x, y) ushr 24) > 40) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        return if (x1 < 0) RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()) else RectF(x0.toFloat(), y0.toFloat(), x1 + 1f, y1 + 1f)
    }

    /** the space between two tabs that holds the LB / RB glyph: a row of tabs, a column of side tabs */
    const val TAB_GAP_H = 60f
    const val TAB_GAP_V = 36f

    /** [n] tabs in a row from [left] to at most [right], [h] high at [top]: each [maxW] wide, narrower when they would not fit, with
     *  [gap] between them (the glyph's space) */
    fun tabRow(n: Int, left: Float, right: Float, top: Float, h: Float, maxW: Float, gap: Float = TAB_GAP_H): List<RectF> {
        if (n <= 0) return emptyList()
        val w = minOf(maxW, (right - left - (n - 1) * gap) / n)
        return (0 until n).map { i -> RectF(left + i * (w + gap), top, left + i * (w + gap) + w, top + h) }
    }

    /** [n] side tabs down a column at [x] ([w] wide) from [top] to at most [bottom]: each [maxH] high, shorter when they would not
     *  fit, with [gap] between them (the glyph's space) */
    fun tabColumn(n: Int, x: Float, top: Float, bottom: Float, w: Float = 116f, maxH: Float = 126f, gap: Float = TAB_GAP_V): List<RectF> {
        if (n <= 0) return emptyList()
        val h = minOf(maxH, (bottom - top - (n - 1) * gap) / n)
        return (0 until n).map { i -> RectF(x, top + i * (h + gap), x + w, top + i * (h + gap) + h) }
    }

    /**
     * A ROW OR COLUMN OF TABS WITH THE GAPS ONLY ROUND THE CHOSEN ONE (the user after b118: "you spaced out all of the tabs, but the
     * spacing should only be between the currently selected tab. swaping between tabs should animate this spacing"). The tabs sit
     * together ([rest] apart, as before); the CHOSEN tab has a [gap] on each side, where its LB / RB glyphs stand ([badges]). When the
     * chosen tab changes, the gaps close round the old one and open round the new one over [TAB_GAP_MS] on the app's curve; the tabs
     * keep their size (it is fixed for the widest case), only their places move. One strip per tab bar, kept by the view:
     *
     *   private val gaps = UiKit.TabGaps(vertical = true)
     *   val tabs = gaps.column(n, tabsX, 72f, h - 8f, chosen, this)   // or gaps.row(n, left, right, top, h, maxW, chosen, this)
     *   ... draw tabs[i]; hit / nav rects from tabs[i] ...; gaps.badges(c)
     * column / row ask [host] for the next frame while the gaps move; [layout] is the general form.
     *
     * The first layout (and a change of [n]) shows the gaps at once, without the animation.
     */
    class TabGaps(val vertical: Boolean, val gap: Float = if (vertical) TAB_GAP_V else TAB_GAP_H,
                   val rest: Float = if (vertical) 2f else 10f) {
        private var from = FloatArray(0)
        private var to = FloatArray(0)
        private var t0 = 0L
        private var sel = -1
        private val curve = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)   // Motion.curve() (the kit stands alone)
        /** the tabs' places from the last [layout] */
        var rects: List<RectF> = emptyList(); private set
        /** the space after each tab (between tab i and i + 1) from the last [layout] */
        var gaps = FloatArray(0); private set
        var chosen = -1; private set
        /** true while the gaps are still moving: the view asks for the next frame */
        var animating = false; private set

        private fun target(n: Int, s: Int) = FloatArray(maxOf(0, n - 1)) { i -> if (i == s - 1 || i == s) gap else rest }

        fun layout(n: Int, chosenTab: Int, start: Float, end: Float, at: Float, size: Float, maxLen: Float,
                   now: Long = SystemClock.uptimeMillis()): List<RectF> {
            if (n <= 0) { rects = emptyList(); gaps = FloatArray(0); return rects }
            if (to.size != n - 1 || sel < 0) { to = target(n, chosenTab); from = to.copyOf(); t0 = 0L; sel = chosenTab }
            else if (chosenTab != sel) { from = current(now); to = target(n, chosenTab); t0 = now; sel = chosenTab }
            gaps = current(now)
            chosen = chosenTab
            // a ROW keeps the room of its L / R glyphs at both ends ([tabEnds]: the user's "on the outsides, always", 15 Oct)
            val start = if (vertical) start else start + TAB_END_W
            val end = if (vertical) end else end - TAB_END_W
            // a tab's length is fixed for the widest case (two gaps), so the tabs never change size while the gaps move
            val spare = if (n >= 2) minOf(2, n - 1) * gap + maxOf(0, n - 3) * rest else 0f
            val len = minOf(maxLen, (end - start - spare) / n)
            // a ROW is centred in its room (Spells' p16: with the chosen tab at an end only one gap is open, and the spare one sat at the
            // far end); the centring follows the gaps as they ease, so the row glides with them. A column keeps its top.
            val used = n * len + gaps.sum()
            var p = if (vertical) start else start + ((end - start) - used) / 2f
            val out = ArrayList<RectF>(n)
            for (i in 0 until n) {
                out += if (vertical) RectF(at, p, at + size, p + len) else RectF(p, at, p + len, at + size)
                p += len + (if (i < n - 1) gaps[i] else 0f)
            }
            rects = out
            return out
        }

        private fun current(now: Long): FloatArray {
            if (t0 == 0L) { animating = false; return to.copyOf() }
            val k = curve.getInterpolation(((now - t0).toFloat() / TAB_GAP_MS).coerceIn(0f, 1f))
            animating = k < 1f
            return FloatArray(to.size) { i -> from[i] + (to[i] - from[i]) * k }
        }

        /** side tabs down a column at [x] ([w] wide, each at most [maxH] high) from [top] to [bottom], the gaps round [chosenTab] */
        fun column(n: Int, x: Float, top: Float, bottom: Float, chosenTab: Int, host: android.view.View?,
                   w: Float = 116f, maxH: Float = 126f): List<RectF> {
            val r = layout(n, chosenTab, top, bottom, x, w, maxH)
            if (animating) host?.postInvalidateOnAnimation()
            return r
        }

        /** tabs in a row from [left] to [right] at [top] ([h] high, each at most [maxW] wide), the gaps round [chosenTab] */
        fun row(n: Int, left: Float, right: Float, top: Float, h: Float, maxW: Float, chosenTab: Int, host: android.view.View?): List<RectF> {
            val r = layout(n, chosenTab, left, right, top, h, maxW)
            if (animating) host?.postInvalidateOnAnimation()
            return r
        }

        /**
         * The LB / RB glyphs. A ROW: on its outsides, ALWAYS ([tabEnds]: L left of the first tab, R right of the last; the user, 15 Oct),
         * [canL] / [canR] and [width] unused. A column (none uses one today): in the chosen tab's two gaps, as before.
         */
        fun badges(c: Canvas, canL: Boolean = chosen > 0, canR: Boolean = chosen < rects.size - 1, width: Float = 60f) {
            if (!vertical) { if (rects.isNotEmpty()) tabEnds(c, rects.first(), rects.last()); return }
            if (chosen < 0 || chosen >= rects.size) return
            if (canL && chosen > 0) tabBadge(c, rects[chosen - 1], false, vertical, width, gaps[chosen - 1])
            if (canR && chosen < rects.size - 1) tabBadge(c, rects[chosen + 1], true, vertical, width, gaps[chosen])
        }
    }

    /** how long the tab gaps take to close round the old chosen tab and open round the new one */
    const val TAB_GAP_MS = 160L

    /**
     * L AND R ON THE OUTSIDES OF A TAB ROW, ALWAYS (the user, 15 Oct: "Every tabbed room shows L on the far left and R on the far right,
     * always"; first asked of the mailbox: "the L and R need to be on the outsides of both tabs always there"). The LB glyph centred
     * in the [room] left of the row's [first] tab, the RB glyph in the [room] right of its [last], in the chosen BUTTON GLYPHS set
     * ([pad] / [shoulderGlyph]), whether the controller holds or not. A row keeps [TAB_END_W] free at each end for them ([TabGaps]
     * does it by itself).
     */
    fun tabEnds(c: Canvas, first: RectF, last: RectF, room: Float = TAB_END_W, size: Float = minOf(64f, room)) {
        pad(c, shoulderGlyph(false), first.left - room / 2f, first.centerY() + 4f, size)
        pad(c, shoulderGlyph(true), last.right + room / 2f, last.centerY() + 4f, size)
    }

    /** the room an L / R glyph takes beside a tab row's end ([tabEnds]) */
    const val TAB_END_W = 64f

    /** the shoulder button's word in the layout the Settings chose: L / R (Nintendo), LB / RB (Xbox), L1 / R1 (PlayStation) */
    fun shoulderWord(right: Boolean): String = Glyphs.name(if (right) "rshoulder" else "lshoulder")

    /**
     * UP / DOWN ON A LIST'S CHOSEN ROW (the map session's quest list; the user: "Put some up and down arrows on the currently
     * selected quest"): the game's gold arrows (the move cue's), turned up and down, at [box]'s right edge, [x] from it: up above
     * the row's middle, down below it. [up] / [down] false = none (the first / the last row). No words: the D-pad moves it.
     * [around] = Home's buff menu (the user: "add up and down arrow icons to the cursor"): centred on [box], the up arrow on the
     * cursor's top edge and the down arrow on its bottom edge (their flat sides 15 % over the cursor's gold edge, as [moveArrows]).
     */
    fun moveArrowsUpDown(c: Canvas, box: RectF, up: Boolean = true, down: Boolean = true, x: Float = box.right - 24f,
                         size: Float = 30f, alpha: Int = 255, around: Boolean = false, gap: Float = 0f, width: Float = 0f) {
        val pic = tex("move_arrow_right")
        val ax = if (around) box.centerX() else x
        for (toUp in booleanArrayOf(true, false)) {
            if (if (toUp) !up else !down) continue
            // [gap] > 0 (Map's quest list: the user, "on the top and bottom of the selection ... some buffer between current selection
            // and next quests"): centred in the gap of that height above / below [box]; else on the cursor's edge
            val cy = if (around && gap > 0f) { if (toUp) box.top - gap / 2f else box.bottom + gap / 2f }
                else if (around) { if (toUp) box.top - 5f - size * 0.35f else box.bottom + 5f + size * 0.35f }
                else if (toUp) box.centerY() - size * 0.62f else box.centerY() + size * 0.62f
            val x = ax
            c.save()
            c.rotate(if (toUp) -90f else 90f, x, cy)
            // the picture's solid triangle, of 256: x 63..188, y 23..234 (as moveArrows); [size] = the triangle's length,
            // [width] its base (0 = as tall as long)
            val h = if (width > 0f) width else size * 211f / 125f * 0.6f
            val w = size
            val tri = RectF(x - w / 2f, cy - h / 2f, x + w / 2f, cy + h / 2f)
            if (pic != null) {
                val sx = tri.width() / 125f; val sy = tri.height() / 211f
                pBmp.alpha = alpha
                c.drawBitmap(pic, null, RectF(tri.left - 63f * sx, tri.top - 23f * sy, tri.left + 193f * sx, tri.top + 233f * sy), pBmp)
                pBmp.alpha = 255
            } else {
                arrowPath.reset(); arrowPath.moveTo(tri.left, tri.top); arrowPath.lineTo(tri.right, cy); arrowPath.lineTo(tri.left, tri.bottom); arrowPath.close()
                pFlat.style = Paint.Style.FILL; pFlat.color = Theme.gold; pFlat.alpha = alpha; c.drawPath(arrowPath, pFlat)
            }
            c.restore()
        }
    }

    /**
     * THE BAR CURSOR'S ARROWS, PART OF THE GOLD BOX (f-bar-cursor-polish; the user, 15 Oct: "remove the L and R on the arrows and try
     * to make the arrows look like they are a little more of a part of the golden cursor. maybe even giving button feedback to pushing
     * a direction"): a gold point grows out of the middle of each side of [focus]'s box ([box] = the element, as [focus] gets it),
     * in [focus]'s own gold and edge, its base ON the box's gold edge so box and point read as one piece; no words. [pushLeft] /
     * [pushRight] 1 = that direction was just pushed, easing to 0: the point presses OUT a few px and brightens, the box's edge
     * on that side with it. [left] / [right] false = no point there (the bar's end).
     */
    fun cursorArrows(c: Canvas, box: RectF, left: Boolean, right: Boolean, pushLeft: Float = 0f, pushRight: Float = 0f) {
        val edgeL = box.left - 5f; val edgeR = box.right + 5f       // [focus]'s gold edge
        val h = (box.height() * 0.40f).coerceIn(30f, 42f)
        val w = h * 0.55f
        val cy = box.centerY()
        for (toRight in booleanArrayOf(false, true)) {
            if (if (toRight) !right else !left) continue
            val push = (if (toRight) pushRight else pushLeft).coerceIn(0f, 1f)
            val out = 7f * push
            val base = if (toRight) edgeR - 1.75f + out else edgeL + 1.75f - out
            val tip = if (toRight) base + w else base - w
            arrowPath.reset()
            arrowPath.moveTo(base, cy - h / 2f); arrowPath.lineTo(tip, cy); arrowPath.lineTo(base, cy + h / 2f); arrowPath.close()
            // its own soft light, as the box's wash (brighter while pushed)
            val glowR = RectF(minOf(base, tip) - 10f, cy - h / 2f - 10f, maxOf(base, tip) + 10f, cy + h / 2f + 10f)
            glow(c, glowR, SOFT_GOLD, 0.30f + 0.45f * push)
            pFlat.style = Paint.Style.FILL
            pFlat.color = lerpColour(Theme.gold, Color.rgb(255, 240, 170), push)
            c.drawPath(arrowPath, pFlat)
            // the box's edge carried round the point, the same weights as [focus]'s
            pFlat.style = Paint.Style.STROKE; pFlat.strokeJoin = Paint.Join.ROUND
            pFlat.strokeWidth = 3.5f; pFlat.color = Color.argb(200, 120, 72, 0)
            c.drawPath(arrowPath, pFlat)
            pFlat.strokeJoin = Paint.Join.MITER; pFlat.style = Paint.Style.FILL
            // the push lights the box's edge on that side too: the box and its point move as one
            if (push > 0f) {
                pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 3.5f
                pFlat.color = Color.argb((200 * push).toInt(), 255, 240, 170)
                val x = if (toRight) edgeR else edgeL
                c.drawLine(x, box.top - 5f + 8f, x, box.bottom + 5f - 8f, pFlat)
                pFlat.style = Paint.Style.FILL
            }
        }
    }

    private fun lerpColour(a: Int, b: Int, k: Float): Int {
        val t = k.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.argb(ch(Color.alpha(a), Color.alpha(b)), ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    private val pArrowWord = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; color = Color.rgb(46, 26, 0)
    }
    private val arrowPath = android.graphics.Path()

    /**
     * THE CURSOR'S MOVE CUE (LATER item 23, the user, 2026-09-30: "maybe just includ L and R inside of the arrows? I think its fine
     * if we exclude ZL and ZR"): the game's own gold arrows (common-icon-backarrow / -forwardarrow) on the cursor's two sides, their
     * flat side on the cursor's gold edge and their point outward, each with its shoulder button's word inside ([shoulderWord]: L / R, LB / RB,
     * L1 / R1). [box] = the element the cursor is on (the square [focus] is drawn round; for a round element, [focusRing]'s square).
     * [left] / [right] false = that arrow is not drawn (the bar's end, when the cursor does not wrap). Draw it after the cursor.
     */
    fun moveArrows(c: Canvas, box: RectF, left: Boolean = true, right: Boolean = true, alpha: Int = 255, words: Boolean = true) {
        // the triangle: 50..60 px tall whatever the element's size (its word must read: a room's picture and Home's disc alike),
        // 0.6 as wide as it is tall
        val h = (box.height() * 0.62f).coerceIn(50f, 60f)
        val w = h * 0.6f
        val cy = box.centerY()
        for (toRight in booleanArrayOf(false, true)) {
            if (if (toRight) !right else !left) continue
            // its flat side 15 % over the cursor's gold edge (5 px outside [box]): attached to the cursor, the element left clear
            val base = if (toRight) box.right + 5f - w * 0.15f else box.left - 5f + w * 0.15f
            val tri = if (toRight) RectF(base, cy - h / 2f, base + w, cy + h / 2f) else RectF(base - w, cy - h / 2f, base, cy + h / 2f)
            val pic = tex(if (toRight) "move_arrow_right" else "move_arrow_left")
            if (pic != null) {
                // the picture's solid triangle, of 256: x 63..188 (right) / 67..193 (left), y 23..234; its shadow falls outside it
                val x0 = if (toRight) 63f else 67f
                val sx = tri.width() / 125f; val sy = tri.height() / 211f
                val dst = RectF(tri.left - x0 * sx, tri.top - 23f * sy, tri.left + (256f - x0) * sx, tri.top + (256f - 23f) * sy)
                pBmp.alpha = alpha
                c.drawBitmap(pic, null, dst, pBmp)
                pBmp.alpha = 255
            } else {
                arrowPath.reset()
                if (toRight) { arrowPath.moveTo(tri.left, tri.top); arrowPath.lineTo(tri.right, cy); arrowPath.lineTo(tri.left, tri.bottom) }
                else { arrowPath.moveTo(tri.right, tri.top); arrowPath.lineTo(tri.left, cy); arrowPath.lineTo(tri.right, tri.bottom) }
                arrowPath.close()
                pFlat.style = Paint.Style.FILL; pFlat.color = Theme.gold; pFlat.alpha = alpha
                c.drawPath(arrowPath, pFlat)
                pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Color.argb(alpha, 40, 24, 0)
                c.drawPath(arrowPath, pFlat)
            }
            // the word at the triangle's middle (a third of the way from its flat side); [words] false = plain arrows (Home's
            // command mode: the bar moved by the Controller tab's macros, L / R stay the game's)
            if (!words) continue
            val word = shoulderWord(toRight)
            pArrowWord.textSize = h * if (word.length > 1) 0.32f else 0.42f
            pArrowWord.alpha = alpha
            val wx = if (toRight) base + w / 3f else base - w / 3f
            c.drawText(word, wx, cy - (pArrowWord.ascent() + pArrowWord.descent()) / 2f, pArrowWord)
        }
    }

    /**
     * One of the controller's buttons, the game's own glyph, as `<set>_<name>`. Sets: "rev"
     * (white letters, Nintendo names: rev_a, rev_rshoulder = R, rev_rtrigger = ZR, rev_minus,
     * rev_plus), "ltr" (coloured letters), "shp" (shapes), "gen" (the same for every pad:
     * gen_rshoulder = RB, gen_rtrigger = RT, gen_lstickin, gen_rstickin, gen_back, gen_forward,
     * and the D-pad gen_left / gen_right / gen_up / gen_down). Every glyph the client has is on
     * the device. Which set a player sees is the Settings room's button layout; this only draws
     * the one it is asked for.
     */
    fun pad(c: Canvas, name: String, cx: Float, cy: Float, size: Float = 70f, alpha: Int = 255) {
        val r = RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        // a stick's up AND down (Glyphs.upDown, the Map room's zoom): the game's "down" glyph whole (the stick, the arrow under it),
        // then its "up" glyph's arrow over it (the 64 px art's top 17 rows: the stick starts lower in both, at the same place)
        Glyphs.upDown(name)?.let { (up, down) ->
            val u = tex("pad_$up"); val d = tex("pad_$down")
            if (u != null && d != null) {
                pBmp.alpha = alpha
                c.drawBitmap(d, null, r, pBmp)
                c.save(); c.clipRect(r.left, r.top, r.right, r.top + r.height() * 17f / 64f); c.drawBitmap(u, null, r, pBmp); c.restore()
                pBmp.alpha = 255
                return
            }
        }
        // f-abxy-split: every glyph in the chosen BUTTON GLYPHS set, whatever set a caller named (a missing picture: the named one)
        val b = tex("pad_" + Glyphs.translate(name, CastSettings.buttonLayout)) ?: tex("pad_$name") ?: return
        pBmp.alpha = alpha
        c.drawBitmap(b, null, r, pBmp)
        pBmp.alpha = 255
    }

    // ---------------------------------------------------------------- the LOOT window (Forever's mainline LootFrame, 2026-10-01)

    /** the game's quality names (ITEM_QUALITYn_DESC), for the loot card's tag */
    fun qualityName(q: Int): String = when (q) { 0 -> "Poor"; 1 -> "Common"; 2 -> "Uncommon"; 3 -> "Rare"; 4 -> "Epic"; 5 -> "Legendary"
        6 -> "Artifact"; 7 -> "Heirloom"; else -> "" }

    /**
     * ONE ITEM OF THE LOOT WINDOW (LootFrameItemElementTemplate; the game's card is 157 x 46, [r] is any size, the ends kept): the card's
     * ground TINTED by the item's quality colour (the game's NameFrame:SetVertexColor), its edge, the quality tag at the top right
     * ("Uncommon": the game's tiny white type on looting_raritytag_frame), the [icon] in its quality's border with its [count], the
     * game's quest mark over the icon ([quest] 1 = a quest item's gold border, 2 = an item that STARTS a quest: the "!"), the name in
     * the quality colour, two lines at most. [money] = the coins' row (LootFrameMoneyElementTemplate): no tag, the text white and
     * centred on the icon. [locked] = the game's red icon (a roll still on it). The cursor ([focus]) is the caller's.
     */
    fun lootCard(c: Canvas, r: RectF, icon: Bitmap?, name: String, quality: Int, count: Int = 1, quest: Int = 0,
                 money: Boolean = false, locked: Boolean = false, alpha: Int = 255) {
        val k = r.height() / 46f                                  // the game's card is 46 high
        val colour = if (money) Theme.quality(1) else Theme.quality(quality)
        val bg = tex("loot_card_bg"); val edge = tex("loot_card_edge")
        pTintBorder.colorFilter = android.graphics.PorterDuffColorFilter(colour, PorterDuff.Mode.MULTIPLY); pTintBorder.alpha = alpha
        if (bg != null) slice3(c, bg, 0, bg.width, 0, bg.height, 30, r, pTintBorder)
        else { pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(40, 36, 30); pFlat.alpha = alpha; c.drawRect(r, pFlat); pFlat.alpha = 255 }
        pTintBorder.colorFilter = null
        pBmp.alpha = alpha
        if (edge != null) slice3(c, edge, 0, edge.width, 0, edge.height, 30, r)
        // the icon: 37 x 37 at (5, 4), its quality border, the count, the quest mark
        val ic = RectF(r.left + 5f * k, r.top + 4f * k, r.left + 42f * k, r.top + 41f * k)
        if (icon != null) {
            if (locked) pBmp.colorFilter = android.graphics.PorterDuffColorFilter(Color.rgb(230, 0, 0), PorterDuff.Mode.MULTIPLY)
            c.drawBitmap(icon, null, ic, pBmp); pBmp.colorFilter = null
        }
        if (!money) tex(lootBorder(quality))?.let { b ->
            val o = 3f * k / 2f
            c.drawBitmap(b, null, RectF(ic.left - o, ic.top - o, ic.right + o, ic.bottom + o), pBmp)
        }
        if (quest > 0) tex(if (quest == 2) "loot_quest_bang" else "loot_quest_border")?.let { c.drawBitmap(it, null, ic, pBmp) }
        if (count > 1) {
            val pc = numberPaint(14f * k, Color.WHITE).apply { textAlign = Paint.Align.RIGHT; this.alpha = alpha }
            c.drawText(count.toString(), ic.right - 2f * k, ic.bottom - 3f * k, pc)
        }
        pBmp.alpha = 255
        val tx = ic.right + 8f * k
        if (money) {
            val pm = bodyPaint(13f * k, Color.WHITE).apply { this.alpha = alpha }
            c.drawText(name, tx, ic.centerY() - (pm.ascent() + pm.descent()) / 2f, pm)
            return
        }
        // the quality tag: 100 x 13 at the top right, its word right-aligned in the game's tiny white
        tex("loot_card_tag")?.let { t ->
            pBmp.alpha = alpha
            c.drawBitmap(t, null, RectF(r.right - 100f * k, r.top, r.right, r.top + 13f * k), pBmp); pBmp.alpha = 255
        }
        val pq = bodyPaint(8.5f * k, Color.WHITE).apply { textAlign = Paint.Align.RIGHT; this.alpha = alpha }
        c.drawText(qualityName(quality), r.right - 4f * k, r.top + 6.5f * k - (pq.ascent() + pq.descent()) / 2f, pq)
        // the name: GameFontNormal in the quality colour, from 8 px right of the icon and 8 down, two lines at most
        val pn = TextPaint(bodyPaint(12.5f * k, colour)).apply { this.alpha = alpha }
        val lay = StaticLayout.Builder.obtain(name, 0, name.length, pn, (r.right - 6f * k - tx).toInt().coerceAtLeast(10))
            .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 0.95f).build()
        c.save(); c.translate(tx, if (lay.lineCount > 1) r.top + 13f * k else ic.centerY() - lay.height / 2f + 3f * k); lay.draw(c); c.restore()
    }

    // ---------------------------------------------------------------- Home, ONE MODULE LANGUAGE (LATER 18, the user 2026-10-01)
    //
    // The user: "the moduals have no harmony ... The overall Ui needs to just generally mesh together better". Every Home module (the
    // focused quest, the chat, the small map, the clock, ZL / ZR) is the same flat WELL: a dark rounded plate with a thin bronze edge,
    // the same radius, the same padding, 8 px between wells and from the frame's edges. Nothing metal (the 09-27 ruling: flat ground,
    // hairline edges). The quest card IS a well, so when it flies to the Map room's list the module itself flies.

    /** the wells' corner radius, inner padding and gutter (between wells and from the frame's edges), at scale 1 */
    const val MODULE_R = 12f
    const val MODULE_PAD = 14f
    const val MODULE_GAP = 8f

    /**
     * ONE HOME MODULE'S WELL, [r] the whole module: Theme.panel with a 2 px Theme.bronzeDim edge, radius [MODULE_R] x [scale].
     * [edgeOnly] = the edge alone (for a module whose content filled the well first, e.g. the map clipped by [moduleClip]).
     */
    fun module(c: Canvas, r: RectF, alpha: Int = 255, edgeOnly: Boolean = false, scale: Float = 1f) {
        val rad = MODULE_R * scale
        if (!edgeOnly) {
            pFlat.style = Paint.Style.FILL; pFlat.color = Theme.panel; pFlat.alpha = alpha
            c.drawRoundRect(r, rad, rad, pFlat)
        }
        // the edge: the round controller buttons' ring in its square form, the game's own metal (the user 2026-10-09, [ringBox]);
        // without the picture, the drawn stand-in
        if (ringBox(c, r, scale.coerceAtLeast(0.75f), alpha = alpha)) return
        val wE = RING_W * scale.coerceAtLeast(0.75f)
        val e = wE / 2f
        ringPath.reset()
        ringPath.addRoundRect(RectF(r.left + e, r.top + e, r.right - e, r.bottom - e), maxOf(0f, rad - e), maxOf(0f, rad - e),
            android.graphics.Path.Direction.CW)
        ringEdge(c, ringPath, wE, alpha = alpha)
    }

    /** the ring art's scale on Home (the round buttons draw the 64 px art at about this) and its nine-slice corner, in art px */
    const val RING_K = 1.6f
    private const val RING_C = 14
    private val pRingTex = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val ringLit = ColorMatrixColorFilter(ColorMatrix().apply { setScale(1.95f, 1.7f, 0.55f, 1f) })  // the taupe metal -> gold

    /** the art nine-sliced onto [box]: corners [cw] canvas units, edges stretched */
    private fun ringNine(c: Canvas, art: Bitmap, box: RectF, cw: Float, p: Paint) {
        val s = art.width; val k = RING_C
        val sx = intArrayOf(0, k, s - k, s); val sy = intArrayOf(0, k, s - k, s)
        val dx = floatArrayOf(box.left, box.left + cw, box.right - cw, box.right)
        val dy = floatArrayOf(box.top, box.top + cw, box.bottom - cw, box.bottom)
        for (i in 0..2) for (j in 0..2) {
            if (i == 1 && j == 1) continue
            if (dx[i + 1] <= dx[i] || dy[j + 1] <= dy[j]) continue
            c.drawBitmap(art, Rect(sx[i], sy[j], sx[i + 1], sy[j + 1]), RectF(dx[i], dy[j], dx[i + 1], dy[j + 1]), p)
        }
    }

    /**
     * THE ROUND BUTTONS' RING ROUND A PANEL, IN THE GAME'S OWN METAL (the user, 2026-10-09: "what was proposed was almost a metalic
     * finish that had different shades of light around it to make it look more like the rings on the combat buttons"): the game's
     * controller action-bar ring in its square form (kit `ring_square` = gamepad-actionbar-squareslot-border-normal, the same art as the
     * round combat buttons' ring), nine-sliced round [r] at [RING_K] x [scale], its band just inside [r]'s edge. [lit] = the game's gold.
     * Returns false without the picture (the caller draws [ringEdge]).
     */
    fun ringBox(c: Canvas, r: RectF, scale: Float = 1f, lit: Boolean = false, alpha: Int = 255): Boolean {
        val art = tex("ring_square") ?: return false
        val k = RING_K * scale
        val o = 1f * k                                        // the art's band starts 1 px in: its outer side on [r]'s edge
        pRingTex.alpha = alpha; pRingTex.colorFilter = if (lit) ringLit else null
        ringNine(c, art, RectF(r.left - o, r.top - o, r.right + o, r.bottom + o), RING_C * k, pRingTex)
        pRingTex.alpha = 255; pRingTex.colorFilter = null
        return true
    }

    /**
     * THE SAME RING ON A SHAPE WITH A CUT CORNER (the ZL / ZR bumper plates, the user: "the shapes on the bumpers dont match the old
     * ones"): the art's four STRAIGHT edges laid along [bounds]' sides (square corners where they meet, as the plate has) and kept to
     * [path] (the plate's own outline), and along the cut ([cutA] -> [cutB]) the same straight edge laid on the line, its band inside. Everything is in the canvas's current units: [unit] = screen px per unit.
     * Draw the plate's glyph AFTER this. Returns false without the picture.
     */
    fun ringShape(c: Canvas, path: android.graphics.Path, bounds: RectF, cutA: android.graphics.PointF?, cutB: android.graphics.PointF?,
                  unit: Float, lit: Boolean = false, alpha: Int = 255): Boolean {
        val art = tex("ring_square") ?: return false
        val k = RING_K / unit                                 // art px -> canvas units
        val o = 1f * k
        pRingTex.alpha = alpha; pRingTex.colorFilter = if (lit) ringLit else null
        val save = c.save()
        c.clipPath(path)
        val S = art.width; val cw = RING_C * k
        c.drawBitmap(art, Rect(RING_C, 0, S - RING_C, RING_C), RectF(bounds.left, bounds.top - o, bounds.right, bounds.top - o + cw), pRingTex)
        c.drawBitmap(art, Rect(RING_C, S - RING_C, S - RING_C, S), RectF(bounds.left, bounds.bottom + o - cw, bounds.right, bounds.bottom + o), pRingTex)
        c.drawBitmap(art, Rect(0, RING_C, RING_C, S - RING_C), RectF(bounds.left - o, bounds.top, bounds.left - o + cw, bounds.bottom), pRingTex)
        c.drawBitmap(art, Rect(S - RING_C, RING_C, S, S - RING_C), RectF(bounds.right + o - cw, bounds.top, bounds.right + o, bounds.bottom), pRingTex)
        if (cutA != null && cutB != null) {
            var a = cutA; var b = cutB
            val mx = (a.x + b.x) / 2f; val my = (a.y + b.y) / 2f
            val dx = b.x - a.x; val dy = b.y - a.y
            // the strip's "down" (its inner side) must point into the shape, toward [bounds]' middle
            if ((-dy) * (bounds.centerX() - mx) + dx * (bounds.centerY() - my) < 0f) { val t = a; a = b; b = t }
            val ang = Math.toDegrees(kotlin.math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
            val len = kotlin.math.hypot(b.x - a.x, b.y - a.y)
            val s = c.save()
            c.translate(a.x, a.y); c.rotate(ang)
            val ext = RING_C * k * 0.5f
            c.drawBitmap(art, Rect(RING_C, 0, art.width - RING_C, RING_C), RectF(-ext, -o, len + ext, RING_C * k - o), pRingTex)
            c.restoreToCount(s)
        }
        c.restoreToCount(save)
        pRingTex.alpha = 255; pRingTex.colorFilter = null
        return true
    }

    /** the ring's width on Home at scale 1: the round controller buttons' ring as they are drawn there (~6 px) */
    const val RING_W = 6f
    private val ringPath = android.graphics.Path()
    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }

    /**
     * THE ROUND BUTTONS' EDGE ON ANY SHAPE (the user, 2026-10-09: "I like the edges that you used on the circle buttons in my home
     * today. if you could use that on the edges of my other ui without changing their shape"): the game's controller action-bar ring
     * (gamepad-actionbar-circleslot / squareslot-border-normal: a soft dark rim round a warm taupe core, 112 / 91 / 68) stroked along
     * [path] itself, so every shape keeps its own outline. [width] = the ring's whole width in the canvas's units (centred on the
     * path); [lit] = held (the bumper pressed): the core in the game's gold. Draw any glyph that sits on the edge AFTER this.
     */
    fun ringEdge(c: Canvas, path: android.graphics.Path, width: Float, lit: Boolean = false, alpha: Int = 255) {
        fun stroke(w: Float, col: Int, a: Float) {
            pRing.strokeWidth = w; pRing.color = col; pRing.alpha = (alpha * a).toInt().coerceIn(0, 255); c.drawPath(path, pRing)
        }
        stroke(width, Color.rgb(28, 26, 23), 0.55f)                                   // the soft dark rim
        stroke(width * 0.72f, if (lit) Color.rgb(150, 112, 20) else Color.rgb(88, 72, 54), 1f)
        stroke(width * 0.44f, if (lit) Theme.gold else Color.rgb(112, 91, 68), 1f)  // the core
    }

    /** clips [c] to a well's rounded shape (save first; restore after): for a map or a list drawn inside [module] */
    fun moduleClip(c: Canvas, r: RectF, scale: Float = 1f) {
        val rad = MODULE_R * scale
        c.clipPath(android.graphics.Path().apply { addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW) })
    }

    /**
     * ONE QUEST for [questCard]: what the game's tracker shows of it. [colour] = the title's difficulty colour; [objectives] = each
     * line and whether it is done; [turnIn] = ready (one "Ready for turn-in" line, the "?" disc); [number] = the map's numbered disc
     * (null = the plain mark); [focused] = the game's focused (super-tracked) quest: the gold disc and the gold bar.
     */
    class QuestCard(val title: String, val level: Int, val colour: Int, val objectives: List<Pair<String, Boolean>>,
                    val turnIn: Boolean = false, val number: Int? = null, val focused: Boolean = true)

    /** the card's measures at scale 1 (the Map room's list: 400 px wide); Home draws it at [CARD_HOME_SCALE] */
    const val CARD_PAD = 12f
    const val CARD_TITLE = 40f
    const val CARD_LINE = 31f
    const val CARD_DISC = 46f
    const val CARD_HOME_SCALE = 1.25f

    /** [questCard]'s height for [q] at [scale] (one title line + one line per objective; the empty card has two lines) */
    fun questCardHeight(q: QuestCard?, scale: Float = 1f, collapsed: Boolean = false): Float {
        if (collapsed) return (CARD_PAD * 2f + CARD_TITLE) * scale            // the title row only (the user: "Minimize the focused quest")
        val lines = when { q == null -> 1; q.turnIn -> 1; else -> q.objectives.size.coerceAtLeast(1) }
        return (CARD_PAD * 2f + CARD_TITLE + lines * CARD_LINE) * scale
    }

    /**
     * THE QUEST CARD (LATER 18): Home's quest module (only the FOCUSED quest now) and EVERY row of the Map room's list draw this one
     * picture, so the flight between them moves and resizes one drawing. A [module] well; the gold bar at its left and the gold disc
     * when [QuestCard.focused]; the disc, "[7] Title" in its colour, and each objective behind the tracker's nub (a tick when done,
     * dim). [box] = where it stands (its height from [questCardHeight]); [scale] = its type size (1 at the Map's 400 px rows,
     * [CARD_HOME_SCALE] on Home; a flight eases both). [q] null = the empty card ("No focused quest"). The card draws no cursor:
     * the Map's selection box and arrows stay the Map's.
     */
    /** the quest card's see-through plate over the map ([questCard]'s bgAlpha < 255): the module at [bgAlpha], its soft dark band */
    fun seeThroughPlate(c: Canvas, box: RectF, bgAlpha: Int, scale: Float = 1f) {
        module(c, box, alpha = bgAlpha.coerceIn(0, 255), scale = scale)
        if (bgAlpha >= 255) return
        pFlat.style = Paint.Style.FILL
        pFlat.shader = LinearGradient(box.left, 0f, box.left + box.width() * 0.75f, 0f,
            Color.argb(((255 - bgAlpha) * 0.55f).toInt(), 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRoundRect(box, MODULE_R * scale, MODULE_R * scale, pFlat)
        pFlat.shader = null
    }

    fun questCard(c: Canvas, box: RectF, q: QuestCard?, alpha: Int = 255, scale: Float = 1f, bgAlpha: Int = 255,
                  collapsed: Boolean = false) {
        val s = scale
        val layer = alpha < 255
        if (layer) c.saveLayerAlpha(box, alpha.coerceIn(0, 255))
        // [bgAlpha] < 255 = the card over the map (the user, b120: "give the quest a see through background so that I can still see
        // the map"): only the plate (fill + edge) is see-through; the words, the disc and the gold bar stay whole. Over a light map
        // the words keep their footing on a soft dark band that fades out from the card's left (where the words are) to the right
        module(c, box, alpha = bgAlpha.coerceIn(0, 255), scale = s)
        if (bgAlpha < 255) {
            pFlat.style = Paint.Style.FILL
            pFlat.shader = LinearGradient(box.left, 0f, box.left + box.width() * 0.75f, 0f,
                Color.argb(((255 - bgAlpha) * 0.55f).toInt(), 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP)
            c.drawRoundRect(box, MODULE_R * s, MODULE_R * s, pFlat)
            pFlat.shader = null
        }
        c.save(); c.clipRect(box)
        val pad = CARD_PAD * s
        val titleMid = box.top + pad + CARD_TITLE * s / 2f
        val tx = box.left + pad + 54f * s
        if (q == null) {
            val pt = bodyPaint(26f * s, Theme.dim)
            c.drawText("No focused quest", box.left + pad + 10f * s, titleMid - (pt.ascent() + pt.descent()) / 2f, pt)
            val po = bodyPaint(22f * s, Theme.dim)
            val oy = titleMid + CARD_TITLE * s / 2f + CARD_LINE * s / 2f
            c.drawText("Choose one in the Quests room", box.left + pad + 10f * s, oy - (po.ascent() + po.descent()) / 2f, po)
            c.restore(); if (layer) c.restore(); return
        }
        if (q.focused) {
            pFlat.style = Paint.Style.FILL; pFlat.color = Theme.gold
            c.drawRoundRect(RectF(box.left + 5f * s, box.top + 10f * s, box.left + 10f * s, box.bottom - 10f * s), 2.5f * s, 2.5f * s, pFlat)
        }
        questDisc(c, box.left + pad + 26f * s, titleMid, CARD_DISC * s, if (q.turnIn) Quest.TURN_IN else Quest.PROGRESS, q.focused,
            if (q.turnIn) null else q.number)
        val pt = bodyPaint(27f * s, q.colour)
        val title = q.title                             // test27 (the user: "the Level number in front of the quests text is confusing, trim that from the quests"): no "[level]"; its colour keeps the difficulty
        c.drawText(TextUtils.ellipsize(title, pt, box.right - pad - tx, TextUtils.TruncateAt.END).toString(), tx,
            titleMid - (pt.ascent() + pt.descent()) / 2f, pt)
        var y = box.top + pad + CARD_TITLE * s
        // [collapsed] = the title row only (the user: "Minimize the focused quest down to see more of the minimap"): no objective, no
        // turn-in line. While the box eases between the two heights, a line fades with the room it has (never a half line peeking)
        fun lineAlpha(top: Float): Int {
            val room = (box.bottom - pad * 0.5f) - top
            return (255 * (room / (CARD_LINE * s)).coerceIn(0f, 1f)).toInt()
        }
        if (collapsed) Unit
        else if (q.turnIn) {
            val po = bodyPaint(23f * s, Color.rgb(120, 220, 120)).apply { setAlpha(lineAlpha(y)) }
            val cy = y + CARD_LINE * s / 2f
            c.drawText("Ready for turn-in", tx, cy - (po.ascent() + po.descent()) / 2f, po)
        } else for ((line, done) in q.objectives) {
            val la = lineAlpha(y)
            if (la <= 0) break
            val cy = y + CARD_LINE * s / 2f
            if (la >= 255) objectiveMark(c, tx + 9f * s, cy, 20f * s, done)
            val po = bodyPaint(23f * s, if (done) Theme.dim else Color.rgb(204, 204, 204)).apply { setAlpha(la) }
            c.drawText(TextUtils.ellipsize(line, po, box.right - pad - tx - 26f * s, TextUtils.TruncateAt.END).toString(), tx + 26f * s,
                cy - (po.ascent() + po.descent()) / 2f, po)
            y += CARD_LINE * s
        }
        c.restore()
        if (layer) c.restore()
    }

    /**
     * THE DEVICE'S CLOCK AND BATTERY (LATER 18; the user: "the current format of the time and battery kind of stick out and don't fit
     * in", then 2026-10-01: "I don't really like the box around the time and battery"): NO well, centred on [cx] from [top]: the time
     * in the module text colour, under it the battery in the kit's bronze (red only under 20 %; the gold bolt while [charging]) and the
     * percentage, dim. Returns the area it covers (84 high).
     */
    fun clockChip(c: Canvas, cx: Float, top: Float, time: String, battery: Int, charging: Boolean = false, alpha: Int = 255): RectF {
        val pt = numberPaint(32f, Theme.text).apply { textAlign = Paint.Align.CENTER; this.alpha = alpha }
        val pp = numberPaint(21f, Theme.textDim).apply { this.alpha = alpha }
        val label = "$battery%"
        val bw = 28f; val bh = 14f
        val row = bw + 3f + 9f + pp.measureText(label)
        val w = maxOf(pt.measureText(time), row) + 2f * 20f
        val r = RectF(cx - w / 2f, top, cx + w / 2f, top + 84f)
        c.drawText(time, cx, top + 31f - (pt.ascent() + pt.descent()) / 2f, pt)
        val bx = cx - row / 2f; val by = top + 62f - bh / 2f
        val case = RectF(bx, by, bx + bw, by + bh)
        pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Theme.bronze; pFlat.alpha = alpha
        c.drawRoundRect(case, 2f, 2f, pFlat)
        pFlat.style = Paint.Style.FILL
        c.drawRect(case.right, case.centerY() - 3f, case.right + 3f, case.centerY() + 3f, pFlat)
        pFlat.color = if (battery < 20) Color.rgb(210, 50, 40) else Theme.bronze; pFlat.alpha = alpha
        c.drawRect(case.left + 3f, case.top + 3f, case.left + 3f + (bw - 6f) * (battery.coerceIn(0, 100) / 100f), case.bottom - 3f, pFlat)
        if (charging) {
            val bolt = android.graphics.Path().apply {
                moveTo(case.centerX() + 2f, case.top - 2f); lineTo(case.centerX() - 5f, case.centerY() + 1f)
                lineTo(case.centerX(), case.centerY() + 1f); lineTo(case.centerX() - 2f, case.bottom + 2f)
                lineTo(case.centerX() + 5f, case.centerY() - 1f); lineTo(case.centerX(), case.centerY() - 1f); close()
            }
            pFlat.color = Theme.gold; pFlat.alpha = alpha; c.drawPath(bolt, pFlat)
        }
        pFlat.alpha = 255
        c.drawText(label, case.right + 12f, case.centerY() - (pp.ascent() + pp.descent()) / 2f, pp)
        return r
    }

    /** the trigger glyphs' box (LATER 18): where ZL / ZR stand under each bumper plate (no well round them) */
    const val TRIGGER_W = 84f
    const val TRIGGER_H = 56f

    /**
     * ZL / ZR UNDER THE BUMPERS (LATER 18). The user, 2026-10-01: "I also not really like the boxs around them": the game's trigger glyph
     * alone ([glyph], e.g. "rev_ltrigger", the Settings' layout), centred in [r] ([TRIGGER_W] x [TRIGGER_H]): dim at rest; [lit] while
     * held = the glyph full over a soft round gold glow (no box, no outline). [alpha] = the caller's fade (the cross-fade to the centre).
     */
    fun triggerChip(c: Canvas, r: RectF, glyph: String, lit: Boolean, alpha: Int = 255) {
        if (alpha <= 0) return
        if (lit) {
            val rr = r.height() * 0.9f
            pTrigGlow.shader = android.graphics.RadialGradient(r.centerX(), r.centerY(), rr,
                intArrayOf(Color.argb((150 * alpha / 255f).toInt(), 255, 209, 0), Color.argb(0, 255, 209, 0)), floatArrayOf(0f, 1f),
                android.graphics.Shader.TileMode.CLAMP)
            c.drawCircle(r.centerX(), r.centerY(), rr, pTrigGlow)
        }
        pad(c, glyph, r.centerX(), r.centerY(), r.height() * 0.92f, if (lit) alpha else (alpha * 0.55f).toInt())
    }
    private val pTrigGlow = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * THE "+" BETWEEN ZL AND ZR (both held). The user, 2026-10-01: no slide either, a CROSS-FADE: the glyphs fade out under the
     * bumpers and fade in at the bottom centre with this "+" between them. [k] 0..1 = its fade; centred on ([cx], [cy]), [size].
     */
    fun triggerPlus(c: Canvas, cx: Float, cy: Float, size: Float, k: Float, alpha: Int = 255) {
        val a = (alpha * k.coerceIn(0f, 1f)).toInt()
        if (a <= 0) return
        val h = size / 2f; val t = size * 0.16f
        pFlat.style = Paint.Style.FILL; pFlat.color = Theme.gold; pFlat.alpha = a
        c.drawRoundRect(RectF(cx - h, cy - t, cx + h, cy + t), t * 0.6f, t * 0.6f, pFlat)
        c.drawRoundRect(RectF(cx - t, cy - h, cx + t, cy + h), t * 0.6f, t * 0.6f, pFlat)
        pFlat.alpha = 255
    }

    // ---------------------------------------------------------------- the game's dialog
    //
    // What the game's own dialogs are made of (StaticPopup; read from gamedialog.xml and
    // dialogtemplates.xml in the client): the diamond-metal edge over a dark ground, a header
    // plate for a dialog with a title, the warning sign (`tex("alert")`, `tex("alert_gear")`),
    // the quest name plate beside an item (`tex("plate_quest")`), a progress bar.

    /** the dialog's edge art (2x, eight 128 px pieces) is drawn at this scale */
    const val DIALOG = 0.72f

    /** The darkness behind a dialog. The game lays 50 % black over its screen; here the room behind must go quiet. */
    fun cover(c: Canvas, r: RectF, strength: Float = 0.62f) {
        pFlat.style = Paint.Style.FILL
        pFlat.color = Color.argb((255 * strength).toInt().coerceIn(0, 255), 0, 0, 0)
        c.drawRect(r, pFlat)
    }

    /**
     * The game's dialog box, over whatever is behind it: the dark ground, then the edge.
     * Returns the rectangle that is free for content.
     */
    fun dialog(c: Canvas, r: RectF, k: Float = DIALOG): RectF {
        val inset = 44f * k                                   // the middle of the edge's bar: the ground ends under it
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(245, 13, 12, 14)
        c.drawRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, pFlat)
        val tl = tex("dialog_tl"); val tr = tex("dialog_tr"); val bl = tex("dialog_bl"); val br = tex("dialog_br")
        val et = tex("dialog_t"); val eb = tex("dialog_b"); val el = tex("dialog_l"); val er = tex("dialog_r")
        if (tl == null || tr == null || bl == null || br == null || et == null || eb == null || el == null || er == null) {
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 3f; pFlat.color = Theme.bronze
            c.drawRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, pFlat)
        } else {
            val s = 128f * k
            repeat(c, et, r.left + s, r.top, r.right - s, true, k)
            repeat(c, eb, r.left + s, r.bottom - s, r.right - s, true, k)
            repeat(c, el, r.left, r.top + s, r.bottom - s, false, k)
            repeat(c, er, r.right - s, r.top + s, r.bottom - s, false, k)
            c.drawBitmap(tl, null, RectF(r.left, r.top, r.left + s, r.top + s), pBmp)
            c.drawBitmap(tr, null, RectF(r.right - s, r.top, r.right, r.top + s), pBmp)
            c.drawBitmap(bl, null, RectF(r.left, r.bottom - s, r.left + s, r.bottom), pBmp)
            c.drawBitmap(br, null, RectF(r.right - s, r.bottom - s, r.right, r.bottom), pBmp)
        }
        val pad = 70f * k
        return RectF(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad)
    }

    /**
     * The header plate that sits on a dialog's top edge (the game's DialogHeaderTemplate),
     * centred on [cx]; [top] is the plate's own top. Draw it AFTER [dialog]. Returns its rectangle.
     */
    fun dialogHeader(c: Canvas, cx: Float, top: Float, label: String, k: Float = 0.62f): RectF {
        val p = bodyPaint(34f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
        val cw = 128f * k; val ch = 156f * k
        val w = maxOf(p.measureText(label) + 150f, 3f * cw)
        val r = RectF(cx - w / 2f, top, cx + w / 2f, top + ch)
        val l = tex("dialog_head_left"); val m = tex("dialog_head_center"); val rr = tex("dialog_head_right")
        if (l == null || m == null || rr == null) {
            pFlat.style = Paint.Style.FILL; pFlat.color = Theme.panel
            c.drawRect(r.left, r.top + ch * 0.2f, r.right, r.bottom - ch * 0.2f, pFlat)
        } else {
            repeat(c, m, r.left + cw, r.top, r.right - cw, true, k)
            c.drawBitmap(l, null, RectF(r.left, r.top, r.left + cw, r.bottom), pBmp)
            c.drawBitmap(rr, null, RectF(r.right - cw, r.top, r.right, r.bottom), pBmp)
        }
        c.drawText(label, cx, r.centerY() - (p.ascent() + p.descent()) / 2f, p)
        return r
    }

    /** the dialog's progress bar, [fraction] 0..1 of it filled */
    fun progress(c: Canvas, r: RectF, fraction: Float) {
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.BLACK
        c.drawRect(r, pFlat)
        val f = fraction.coerceIn(0f, 1f)
        val edge = r.height() * 0.2f
        val inner = RectF(r.left + edge, r.top + edge, r.right - edge, r.bottom - edge)
        val fill = tex("bar_fill")
        if (f > 0f) {
            val to = inner.left + inner.width() * f
            if (fill != null) {
                c.save(); c.clipRect(inner.left, inner.top, to, inner.bottom)
                c.drawBitmap(fill, null, inner, pBmp)
                c.restore()
            } else {
                pFlat.color = Color.rgb(30, 200, 0)
                c.drawRect(inner.left, inner.top, to, inner.bottom, pFlat)
            }
        }
        val border = tex("bar_border")
        if (border != null) c.drawBitmap(border, null, r, pBmp)
        else { pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Theme.bronzeDim; c.drawRect(r, pFlat) }
    }

    /**
     * A dialog that WAITS (user pick 2026-09-28: the dialog with the focus sits in the middle,
     * the others wait under it). One line of its text on a small dialog box at half strength,
     * which is how the game shows a dialog without the focus. 96 px high.
     */
    fun dialogWaiting(c: Canvas, r: RectF, text: String) {
        val layer = c.saveLayerAlpha(r.left - 2f, r.top - 2f, r.right + 2f, r.bottom + 2f, 128)
        dialog(c, r, 0.5f)
        val p = bodyPaint(32f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
        val line = TextUtils.ellipsize(text.replace('\n', ' '), p, r.width() - 160f, TextUtils.TruncateAt.END).toString()
        c.drawText(line, r.centerX(), r.centerY() - 2f - (p.ascent() + p.descent()) / 2f, p)
        c.restoreToCount(layer)
    }

    // ---------------------------------------------------------------- the controller on items and menus

    /**
     * THE CONTROLLER'S FOCUS ON A SLOT (user pick 2026-09-28: the gold glow of the game's checked
     * slot). [r] is the slot's own square, of any size; the glow's ring lies just outside it.
     * Rows, buttons and tabs keep [focus].
     */
    fun slotFocus(c: Canvas, r: RectF) = focus(c, r)          // LATER 19 (the user, 2026-10-07, "option 1"): a slot takes THE gold box
                                                            // (it was the game's yellow checked glow, the 09-28 pick)

    /**
     * A BUTTON THAT IS "CHECKED", never a cursor: the game's own checked action button (ActionButtonTemplate's CheckedTexture, kit
     * `slot_checked`), a soft gold glow round the button's frame, here a fifth of the icon's size outside [r] (the ICON's rect) so it
     * lies round the frame and not over the picture. The Controller room's button being edited while a chooser is open (the chooser's
     * gold box is the one cursor). Draw it after the slot. Without the picture: nothing.
     */
    fun checkedGlow(c: Canvas, r: RectF) {
        val t = tex("slot_checked") ?: return
        val g = r.width() * 0.2f
        c.drawBitmap(t, null, RectF(r.left - g, r.top - g, r.right + g, r.bottom + g), pBmp)
    }

    /**
     * AN ITEM IN THE HAND: it rides on the focused slot's lower right corner, the way it rides on
     * the game's cursor. [slot] is the slot the focus is on. The slot it came from is the room's
     * to grey (the game greys a locked slot).
     */
    fun carried(c: Canvas, icon: Bitmap?, slot: RectF, count: Int = 0) {
        val s = slot.width() * 0.74f
        val x = slot.left + slot.width() * 0.52f; val y = slot.top + slot.height() * 0.52f
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(140, 0, 0, 0)
        c.drawRect(x + 6f, y + 8f, x + s + 6f, y + s + 8f, pFlat)
        val r = RectF(x, y, x + s, y + s)
        if (icon != null) c.drawBitmap(icon, null, r, pBmp) else { pFlat.color = Theme.raised; c.drawRect(r, pFlat) }
        pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Color.BLACK
        c.drawRect(r, pFlat)
        pFlat.color = Theme.gold
        c.drawRect(r.left - 2.5f, r.top - 2.5f, r.right + 2.5f, r.bottom + 2.5f, pFlat)
        if (count > 1) {
            val p = numberPaint(s * 0.38f, Color.WHITE).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(count.toString(), r.right - 5f, r.bottom - 6f, p)
        }
    }

    /**
     * The game's own menu box (its drop-down list): the tooltip's edge round a dark ground.
     * Returns the inside. Rows are [menuRow], 112 px each.
     */
    fun menu(c: Canvas, r: RectF, solid: Boolean = false): RectF {
        // [solid]: a menu that stands over a panel of text; nothing behind it may shine through
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(if (solid) 255 else 245, 9, 8, 8)
        c.drawRect(r.left + 6f, r.top + 6f, r.right - 6f, r.bottom - 6f, pFlat)
        border(c, r, "border_tooltip", 22f, Theme.bronzeDim)
        return RectF(r.left + 10f, r.top + 10f, r.right - 10f, r.bottom - 10f)
    }

    /**
     * One row of a [menu]. What stands at its left is a controller [glyph] ("rev_rshoulder") or
     * a kit picture ([art], e.g. "square_minus_up", "icon_redx"), or nothing. The row the
     * controller is on is [selected]: the game's highlight bar, and its words go gold. [size] = the label's size (one size
     * for every row of a list keeps them even, e.g. 30 in a narrow chooser); a label too long for its row at the floor of
     * 28 px takes TWO lines (rows of 92 px or more), and is cut only after that.
     */
    fun menuRow(c: Canvas, r: RectF, label: String, selected: Boolean, colour: Int = Theme.text,
                glyph: String? = null, art: String? = null, enabled: Boolean = true, mark: String? = null,
                size: Float = 36f) {
        if (selected) focus(c, RectF(r.left + 6f, r.top + 8f, r.right - 6f, r.bottom - 8f))   // THE one highlight (LATER 19)
        val cy = r.centerY()
        if (glyph != null) pad(c, glyph, r.left + 48f, cy, 56f, if (enabled) 255 else 110)
        else if (art != null) tex(art)?.let {
            pBmp.alpha = if (enabled) 255 else 110
            c.drawBitmap(it, null, RectF(r.left + 18f, cy - 30f, r.left + 78f, cy + 30f), pBmp)
            pBmp.alpha = 255
        }
        val p = bodyPaint(size, if (!enabled) Theme.dim else if (selected && colour == Theme.text) Theme.gold else colour)
        // [mark] = a kit picture at the row's right end (e.g. "icon_check": a command already made); the label keeps clear of it
        if (mark != null) tex(mark)?.let { c.drawBitmap(it, null, RectF(r.right - 56f, cy - 20f, r.right - 16f, cy + 20f), pBmp) }
        // the label in full: in a narrow menu it is set smaller (down to 28 px) before it is cut
        val room = r.right - (if (mark != null) 66f else 16f) - (r.left + 100f)
        while (p.measureText(label) > room && p.textSize > 28f) p.textSize -= 1f
        if (p.measureText(label) > room && r.height() >= 92f) {
            val l = StaticLayout.Builder.obtain(label, 0, label.length, TextPaint(p), room.toInt()).setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 0.92f).build()
            c.save(); c.translate(r.left + 100f, cy - l.height / 2f); l.draw(c); c.restore()
            return
        }
        val line = TextUtils.ellipsize(label, p, room, TextUtils.TruncateAt.END).toString()
        c.drawText(line, r.left + 100f, cy - 2f - (p.ascent() + p.descent()) / 2f, p)
    }

    /**
     * What the controller's buttons do here, as the game's controller bar says it: a glyph and
     * a word, pair after pair, ending at [right]. Returns where the row starts.
     */
    fun hints(c: Canvas, right: Float, cy: Float, vararg pairs: Pair<String, String>): Float {
        val p = bodyPaint(28f, Theme.text)
        var x = right
        for ((glyph, word) in pairs.reversed()) {
            x -= p.measureText(word)
            c.drawText(word, x, cy - (p.ascent() + p.descent()) / 2f, p)
            x -= 34f
            pad(c, glyph, x, cy, 48f)
            x -= 24f + 34f
        }
        return x + 34f
    }

    /**
     * One of the buttons that can be bound, on the bind sheet: its glyph, and what it does now
     * ([label]; [free] = nothing yet, shown bright). [mark] lights the chip: 1 = refused (red),
     * 2 = bound (green).
     */
    fun keyChip(c: Canvas, r: RectF, glyph: String, label: String, free: Boolean, mark: Int = 0) {
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(140, 0, 0, 0)
        c.drawRect(r, pFlat)
        border(c, RectF(r.left - 4f, r.top - 4f, r.right + 4f, r.bottom + 4f), "border_tooltip", 18f, Theme.bronzeDim)
        if (mark != 0) glow(c, RectF(r.left + 4f, r.top + 4f, r.right - 4f, r.bottom - 4f),
            if (mark == 2) Color.rgb(60, 255, 60) else Color.rgb(255, 60, 60), 0.6f)
        pad(c, glyph, r.left + 64f, r.centerY(), 76f, if (free || mark != 0) 255 else 128)
        val p = bodyPaint(30f, if (mark == 2) Theme.questGreen else if (free) Theme.gold else Theme.dim)
        val line = TextUtils.ellipsize(label, p, r.right - 16f - (r.left + 124f), TextUtils.TruncateAt.END).toString()
        c.drawText(line, r.left + 124f, r.centerY() - 2f - (p.ascent() + p.descent()) / 2f, p)
    }

    /**
     * The game's check box, [size] px, centred: ticked when [on]. The art is the game's own
     * 32 px box and tick, drawn with their pixels kept square.
     */
    fun check(c: Canvas, cx: Float, cy: Float, size: Float, on: Boolean, enabled: Boolean = true) {
        val r = RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        val box = tex("check_box")
        val tick = tex(if (enabled) "check_tick" else "check_tick_disabled")
        if (box == null || tick == null) {
            pFlat.style = Paint.Style.FILL; pFlat.color = Theme.panel
            c.drawRoundRect(r, 6f, 6f, pFlat)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f
            pFlat.color = if (enabled) Theme.bronze else Theme.bronzeDim
            c.drawRoundRect(r, 6f, 6f, pFlat)
            if (on) {
                pFlat.strokeWidth = size * 0.11f; pFlat.color = if (enabled) Theme.gold else Theme.dim
                c.drawLine(r.left + size * 0.22f, cy, cx - size * 0.06f, r.bottom - size * 0.24f, pFlat)
                c.drawLine(cx - size * 0.06f, r.bottom - size * 0.24f, r.right - size * 0.16f, r.top + size * 0.2f, pFlat)
            }
            return
        }
        pixel.alpha = if (enabled) 255 else 130
        c.drawBitmap(box, null, r, pixel)
        if (on) c.drawBitmap(tick, null, r, pixel)
        pixel.alpha = 255
    }

    /**
     * ONE OF SEVERAL, all in view: the game's red button for each choice, the chosen one lit
     * (the button's own highlight, gold word), the others quiet (pale word).
     */
    fun choice(c: Canvas, r: RectF, label: String, chosen: Boolean, enabled: Boolean = true, textSize: Float = 0f) {
        val size = if (textSize > 0f) textSize else r.height() * 0.34f
        buttonPlate(c, r, if (!enabled) Button.DISABLED else if (chosen) Button.UP else Button.DOWN)
        if (chosen && enabled) tex("button_highlight")?.let {
            pGlow.colorFilter = null
            c.drawBitmap(it, null, r, pGlow)
        }
        val p = bodyPaint(size, if (!enabled) Theme.dim else if (chosen) Theme.gold else Theme.textDim)
            .apply { textAlign = Paint.Align.CENTER }
        var s = size
        while (p.measureText(label) > r.width() - r.height() * 0.5f && s > 16f) { s -= 1f; p.textSize = s }
        c.drawText(label, r.centerX(), r.centerY() - 2f - (p.ascent() + p.descent()) / 2f, p)
    }

    /**
     * The game's chat edit box: its metal edge round a dark field, three pieces of 32 px art
     * (left, middle, right) drawn at the height of [r] with their pixels kept square.
     */
    fun inputBox(c: Canvas, r: RectF) {
        val l = tex("chat_input_left"); val m = tex("chat_input_mid"); val rr = tex("chat_input_right")
        if (l == null || m == null || rr == null) {
            pFlat.style = Paint.Style.FILL; pFlat.color = Theme.raised
            c.drawRect(r, pFlat)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Theme.bronzeDim
            c.drawRect(r, pFlat)
            return
        }
        val cap = r.height()                                  // the end pieces are square
        c.drawBitmap(m, null, RectF(r.left + cap, r.top, r.right - cap, r.bottom), pixel)
        c.drawBitmap(l, null, RectF(r.left, r.top, r.left + cap, r.bottom), pixel)
        c.drawBitmap(rr, null, RectF(r.right - cap, r.top, r.right, r.bottom), pixel)
    }

    /**
     * ONE KEY OF THE COMPANION'S CONTROLLER KEYBOARD (the user, 2026-09-30: "The companion's own controller keyboard"; the chat-box
     * key art rejected: "I really hate the art that you used for the buttons"). A clean keycap drawn in code, [look]:
     *   1 = DARK: a charcoal rounded cap, a light top lip and a dark bottom lip (a real keycap's bevel), the word white;
     *   2 = PARCHMENT: the game's parchment colour as a rounded cap, a bronze edge, the word in dark ink.
     * [mod] = a mode / edit key (Shift, Backspace, ?123, Send): a shade deeper. [on] = a held mode key (Shift, ?123): its word gold
     * (look 1) / dark red (look 2). [down] = pressed. [icon] instead of a word: "backspace" (the ⌫ arrow) | "enter" (↵). [glyph] = the
     * controller button that does it, small beside the word. The cursor is [focus] round [r] (onPaper on look 2).
     */
    fun keyCap(c: Canvas, r: RectF, label: String, look: Int = 1, mod: Boolean = false, on: Boolean = false, down: Boolean = false,
               glyph: String? = null, icon: String? = null) {
        val rad = r.height() * 0.14f
        val k = RectF(r.left, r.top + (if (down) 3f else 0f), r.right, r.bottom)
        if (look == 2) {
            val top = if (mod) Color.rgb(196, 168, 120) else Color.rgb(232, 210, 164)
            val bot = if (mod) Color.rgb(160, 132, 88) else Color.rgb(204, 178, 128)
            pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(60, 40, 20); c.drawRoundRect(RectF(k.left, k.top + 4f, k.right, k.bottom + 3f), rad, rad, pFlat)
            pFlat.shader = LinearGradient(0f, k.top, 0f, k.bottom, top, bot, Shader.TileMode.CLAMP); c.drawRoundRect(k, rad, rad, pFlat); pFlat.shader = null
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Color.rgb(120, 86, 40); c.drawRoundRect(k, rad, rad, pFlat)
        } else {
            val top = if (mod) Color.rgb(40, 38, 36) else Color.rgb(62, 60, 58)
            val bot = if (mod) Color.rgb(26, 25, 24) else Color.rgb(44, 42, 40)
            pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(8, 8, 8); c.drawRoundRect(RectF(k.left, k.top + 4f, k.right, k.bottom + 4f), rad, rad, pFlat)
            pFlat.shader = LinearGradient(0f, k.top, 0f, k.bottom, top, bot, Shader.TileMode.CLAMP); c.drawRoundRect(k, rad, rad, pFlat); pFlat.shader = null
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Color.argb(70, 255, 255, 255)
            c.drawLine(k.left + rad, k.top + 1.5f, k.right - rad, k.top + 1.5f, pFlat)
        }
        val ink = when { look == 2 && on -> Color.rgb(150, 20, 10); look == 2 -> Color.rgb(48, 28, 10); on -> Theme.gold; else -> Color.WHITE }
        val cy = k.centerY()
        val gw = if (glyph != null) r.height() * 0.40f else 0f
        if (icon != null) {
            val s = r.height() * 0.36f
            val iw = s * 1.5f
            val tw = iw + if (glyph != null) gw + 10f else 0f
            val x0 = k.centerX() - tw / 2f
            if (glyph != null) pad(c, glyph, x0 + gw / 2f, cy, gw)
            val ix = x0 + (if (glyph != null) gw + 10f else 0f)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = r.height() * 0.045f; pFlat.color = ink; pFlat.strokeJoin = Paint.Join.ROUND; pFlat.strokeCap = Paint.Cap.ROUND
            val path = android.graphics.Path()
            if (icon == "backspace") {
                // ⌫: a key-shaped outline pointing left, an x inside
                val l = ix; val rr = ix + iw; val t = cy - s / 2f; val b = cy + s / 2f
                path.moveTo(l, cy); path.lineTo(l + s * 0.45f, t); path.lineTo(rr, t); path.lineTo(rr, b); path.lineTo(l + s * 0.45f, b); path.close()
                c.drawPath(path, pFlat)
                val xc = (l + s * 0.45f + rr) / 2f + s * 0.05f; val d = s * 0.2f
                c.drawLine(xc - d, cy - d, xc + d, cy + d, pFlat); c.drawLine(xc - d, cy + d, xc + d, cy - d, pFlat)
            } else {
                // ↵: down from the top right, left along the bottom, an arrow head
                val rr = ix + iw; val l = ix
                path.moveTo(rr, cy - s * 0.45f); path.lineTo(rr, cy + s * 0.15f); path.lineTo(l + s * 0.15f, cy + s * 0.15f)
                path.moveTo(l + s * 0.45f, cy - s * 0.15f); path.lineTo(l + s * 0.15f, cy + s * 0.15f); path.lineTo(l + s * 0.45f, cy + s * 0.45f)
                c.drawPath(path, pFlat)
            }
            pFlat.strokeCap = Paint.Cap.BUTT; pFlat.strokeJoin = Paint.Join.MITER
            if (label.isNotEmpty()) {
                val lp = bodyPaint(r.height() * 0.20f, ink, shadow = false).apply { textAlign = Paint.Align.CENTER }
                c.drawText(label, k.centerX(), k.bottom - r.height() * 0.10f, lp)
            }
            return
        }
        val one = label.length == 1
        val p = bodyPaint(if (one) r.height() * 0.44f else r.height() * 0.30f, ink, shadow = look == 1).apply { textAlign = Paint.Align.CENTER }
        while (p.measureText(label) > r.width() - 24f - gw && p.textSize > 14f) p.textSize -= 1f
        val tw = p.measureText(label) + if (glyph != null) gw + 8f else 0f
        val x0 = k.centerX() - tw / 2f
        if (glyph != null) pad(c, glyph, x0 + gw / 2f, cy, gw)
        c.drawText(label, x0 + (if (glyph != null) gw + 8f else 0f) + p.measureText(label) / 2f, cy - (p.ascent() + p.descent()) / 2f, p)
    }

    /**
     * A kit drawing as a view's background, for a room that is built of `TextView`s: the
     * background is drawn by [draw] into the view's own rectangle, the view keeps its words.
     *   button.background = UiKit.background { c, r -> UiKit.button(c, r, "") }
     */
    fun background(draw: (Canvas, RectF) -> Unit): android.graphics.drawable.Drawable =
        object : android.graphics.drawable.Drawable() {
            private val r = RectF()
            override fun draw(canvas: Canvas) { r.set(bounds); draw(canvas, r) }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(f: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }

    /**
     * One of the game's small tracker buttons, this client's own art: "plus" (expand), "minus"
     * (collapse), "filter". 36 px art, drawn with its pixels kept square. [alpha] 0..255 for a
     * button that fades while it is not in use. Returns false when the art is not on the device.
     */
    fun trackerButton(c: Canvas, what: String, cx: Float, cy: Float, size: Float, down: Boolean = false,
                      alpha: Int = 255): Boolean {
        val b = tex(if (down) "tracker_${what}_down" else "tracker_$what") ?: tex("tracker_$what") ?: return false
        val h = size * b.height / b.width
        val r = RectF(cx - size / 2f, cy - h / 2f, cx + size / 2f, cy + h / 2f)
        pressed(c, r, down) {
            pixel.alpha = alpha.coerceIn(0, 255)
            c.drawBitmap(b, null, r, pixel)
            pixel.alpha = 255
        }
        return true
    }

    // ---------------------------------------------------------------- the game's quest tracker
    //
    // What the game's own tracker is made of (its ObjectiveTracker, this client's art): a header
    // line with the word in gold; per quest the round disc at the left, the title in the quest's
    // difficulty colour and under it the objectives, each behind a small bronze nub.

    /** how a quest stands: the three dots of a quest in progress, or the question mark of one to hand in */
    enum class Quest { PROGRESS, TURN_IN }

    /**
     * THE GAME'S QUEST DISC, centred, [size] px (72 on a tracker row). The FOCUSED quest's disc is
     * yellow and glows; every other one is dark with a gold rim. The disc is small on purpose:
     * the touch target is the quest's ROW, not the disc. [number] = the game's NUMBERED marker (the map's
     * quest bubbles and the list beside them): the number in place of the mark, gold on the dark disc,
     * dark ink on the focused yellow one.
     * [ghost] = A GUESS (QuestieDB's spots, shown only where the game itself shows nothing; the user approved it 2026-10-06): the same
     * disc as a ghost, so it never passes for the game's answer: see-through (the dark disc and its rim at 45 %), never the yellow focus
     * fill or its glow, the number / mark in pale parchment. Callers draw it at 80 % of the live disc's size.
     */
    fun questDisc(c: Canvas, cx: Float, cy: Float, size: Float, state: Quest = Quest.PROGRESS, focus: Boolean = false,
                  number: Int? = null, ghost: Boolean = false, glow: Boolean = focus) {
        val r = RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        if (ghost) { questGhost(c, cx, cy, size, state, focus, number, r); return }
        // THE ONE MARKER LOOK (cp27; the user on test65): the focused quest's objective wears the world's and the compass's own disc
        // (MarkerArt: the add-on's quest_disc.tga), its number in the spec's dark brown, no outline
        if (focus && state == Quest.PROGRESS) MarkerArt.discLoaded()?.let { d ->
            if (glow) tex("poi_glow")?.let {
                val k = size * 0.78f
                pGlow.colorFilter = null; pGlow.alpha = 204
                c.drawBitmap(it, null, RectF(cx - k, cy - k, cx + k, cy + k), pGlow)
                pGlow.alpha = 255
            }
            c.drawBitmap(d, null, r, pBmp)
            val p = numberPaint(size * 0.46f, MarkerArt.DISC_NUMBER, shadow = false).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
            c.drawText(number?.toString() ?: "!", cx, cy - (p.ascent() + p.descent()) / 2f, p)
            return
        }
        val disc = tex(if (focus) "poi_disc_focus" else "poi_disc")
        if (disc == null) {
            pFlat.style = Paint.Style.FILL; pFlat.color = if (focus) Theme.gold else Color.rgb(70, 30, 20)
            c.drawCircle(cx, cy, size * 0.36f, pFlat)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2.5f; pFlat.color = Theme.gold
            c.drawCircle(cx, cy, size * 0.36f, pFlat)
            if (number != null) {
                val p = numberPaint(size * 0.4f, if (focus) Color.rgb(34, 22, 6) else Theme.gold, shadow = !focus)
                    .apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
                c.drawText(number.toString(), cx, cy - (p.ascent() + p.descent()) / 2f, p)
            }
            return
        }
        if (glow) tex("poi_glow")?.let {
            val k = size * 0.78f
            pGlow.colorFilter = null; pGlow.alpha = 204
            c.drawBitmap(it, null, RectF(cx - k, cy - k, cx + k, cy + k), pGlow)
            pGlow.alpha = 255
        }
        c.drawBitmap(disc, null, r, pBmp)
        if (number != null) {
            val p = numberPaint(size * 0.46f, if (focus) Color.rgb(34, 22, 6) else Theme.gold, shadow = !focus)
                .apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
            c.drawText(number.toString(), cx, cy - (p.ascent() + p.descent()) / 2f, p)
            return
        }
        val mark = tex(when {
            state == Quest.TURN_IN -> "poi_turnin"
            focus -> "poi_progress_focus"
            else -> "poi_progress"
        }) ?: return
        val m = size * 0.4f
        c.drawBitmap(mark, null, RectF(cx - m, cy - m, cx + m, cy + m), pBmp)
    }

    private val pGhost = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.35f) })
    }

    /** the guess's disc (see [questDisc] ghost): the live disc see-through and pale; [focus] only brings its rim up, never the yellow */
    private fun questGhost(c: Canvas, cx: Float, cy: Float, size: Float, state: Quest, focus: Boolean, number: Int?, r: RectF) {
        val disc = tex("poi_disc")
        if (disc != null) { pGhost.alpha = if (focus) 175 else 115; c.drawBitmap(disc, null, r, pGhost); pGhost.alpha = 255 }
        else {
            pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(115, 70, 30, 20); c.drawCircle(cx, cy, size * 0.36f, pFlat)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Color.argb(150, 220, 190, 120)
            c.drawCircle(cx, cy, size * 0.36f, pFlat)
        }
        val ink = Color.argb(if (focus) 235 else 205, 232, 214, 168)
        if (number != null) {
            val p = numberPaint(size * 0.46f, ink, shadow = true).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
            c.drawText(number.toString(), cx, cy - (p.ascent() + p.descent()) / 2f, p)
            return
        }
        val mark = tex(if (state == Quest.TURN_IN) "poi_turnin" else "poi_progress") ?: return
        val m = size * 0.4f
        pGhost.alpha = if (focus) 220 else 175
        c.drawBitmap(mark, null, RectF(cx - m, cy - m, cx + m, cy + m), pGhost)
        pGhost.alpha = 255
    }

    /**
     * A GUESSED OBJECTIVE SPOT (QuestieDB: one of a quest's spawn points, often several): a small ghost dot, a pale ring on a see-through
     * dark well, [size] px (16 on the map). The quest's number stands once, on the spot nearest the player, as a ghost [questDisc].
     */
    fun ghostDot(c: Canvas, cx: Float, cy: Float, size: Float = 16f) {
        val rr = size / 2f
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(110, 20, 12, 8); c.drawCircle(cx, cy, rr, pFlat)
        pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = maxOf(1.5f, size / 9f); pFlat.color = Color.argb(185, 232, 214, 168)
        c.drawCircle(cx, cy, rr - pFlat.strokeWidth / 2f, pFlat)
        pFlat.style = Paint.Style.FILL
    }

    /** a QuestieDB spawn layer's kind: a herb node, an ore node, a treasure chest, a rare (an elite rare) */
    enum class Spawn { HERB, ORE, CHEST, RARE, RARE_ELITE }

    /**
     * A PLACE SOMETHING CAN SPAWN (QuestieDB's node / chest / rare data; the user picked "Try QuestieDB's NPC + node data first",
     * 2026-10-06): the ghost family again (UI Art's rule: what the game shows live is solid; a guess is the same thing as a ghost).
     * Pictures: the game's own (kit `mm_herb` = mobile-herbalism, `mm_ore` = mobile-mining, `mm_chest` = the minimap's vignetteloot,
     * `mm_rare` / `mm_rare_elite` = the minimap's vignettekill / -elite).
     *  - [size] < 16: a TEXTURE DOT (the zone at a glance, ~200 herb and ~170 ore spots in Westfall): a 10..14 px dot in the kind's own
     *    pale colour (herb sage, ore stone, chest gold, rares their maroon disc), see-through, no picture;
     *  - [size] >= 16: the kind's PICTURE as a ghost (see-through, its colour taken down) on a faint dark well;
     *  - [live] = the game shows it right now (a node the player stands next to, soft-interact): the picture SOLID on a dark well with a
     *    gold rim and a soft glow, never a ghost.
     */
    fun spawnSpot(c: Canvas, cx: Float, cy: Float, kind: Spawn, size: Float, live: Boolean = false, glow: Boolean = false) {
        val pic = tex(when (kind) { Spawn.HERB -> "mm_herb"; Spawn.ORE -> "mm_ore"; Spawn.CHEST -> "mm_chest"
            Spawn.RARE -> "mm_rare"; Spawn.RARE_ELITE -> "mm_rare_elite" })
        val rare = kind == Spawn.RARE || kind == Spawn.RARE_ELITE
        // THE TRACKING'S GOLD (the user, 2026-10-16: the herb / ore / treasure the player tracks "is hard to see sometimes blending with
        // the map, can we give those Icons a golden glow"): a soft gold halo under the spot, at least 14 px across at zone zoom
        if (glow) {
            val gr = maxOf(size * 0.95f, 14f)
            pSpotGlow.shader = RadialGradient(cx, cy, gr, intArrayOf(Color.argb(200, 255, 205, 70), Color.argb(110, 255, 190, 40),
                Color.argb(0, 255, 180, 30)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, gr, pSpotGlow)
            pSpotGlow.shader = null
        }
        if (!live && size < 16f) {
            val (r0, g0, b0) = when (kind) {
                Spawn.HERB -> Triple(150, 200, 120); Spawn.ORE -> Triple(185, 192, 205); Spawn.CHEST -> Triple(235, 205, 110)
                else -> Triple(150, 50, 40)
            }
            val rr = size / 2f
            pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(if (rare) 150 else 105, r0, g0, b0); c.drawCircle(cx, cy, rr, pFlat)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = maxOf(1.2f, size / 10f)
            pFlat.color = if (rare) Color.argb(190, 230, 200, 120) else Color.argb(120, 20, 14, 8)
            c.drawCircle(cx, cy, rr, pFlat)
            pFlat.style = Paint.Style.FILL
            return
        }
        val box = RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        if (live) {
            // the game's own, right now: solid, on a dark well with a gold rim and a soft glow
            tex("poi_glow")?.let { g -> val k = size * 0.85f; pGlow.colorFilter = null; pGlow.alpha = 170
                c.drawBitmap(g, null, RectF(cx - k, cy - k, cx + k, cy + k), pGlow); pGlow.alpha = 255 }
            if (!rare) {
                pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(215, 18, 12, 8); c.drawCircle(cx, cy, size * 0.62f, pFlat)
                pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = maxOf(2f, size / 16f); pFlat.color = Theme.gold
                c.drawCircle(cx, cy, size * 0.62f, pFlat); pFlat.style = Paint.Style.FILL
            }
            if (pic != null) c.drawBitmap(pic, null, if (rare) box else RectF(cx - size * 0.42f, cy - size * 0.42f, cx + size * 0.42f, cy + size * 0.42f), pBmp)
            return
        }
        // a ghost: the picture see-through and pale, on a faint dark well
        if (!rare) { pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(70, 18, 12, 8); c.drawCircle(cx, cy, size * 0.55f, pFlat) }
        if (pic != null) { pGhost.alpha = if (rare) 165 else 140; c.drawBitmap(pic, null, if (rare) box else RectF(cx - size * 0.45f, cy - size * 0.45f, cx + size * 0.45f, cy + size * 0.45f), pGhost); pGhost.alpha = 255 }
    }

    private val pSpotGlow = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The tracker's header: the game's line, [label] in gold at its left and an optional [count]
     * at its right. 62 px high from [top]; returns where the rows start. It has no button: the
     * game hides its collapse button in controller mode, and on Home the swap is a tap elsewhere.
     */
    fun trackerHeader(c: Canvas, left: Float, top: Float, right: Float, label: String, count: String? = null): Float {
        val line = tex("tracker_header")
        if (line != null) c.drawBitmap(line, null, RectF(left, top - 4f, right, top + 60f), pBmp)
        else divider(c, left + 16f, top + 58f, right - 16f, top + 58f)
        val p = bodyPaint(32f, Theme.gold)
        c.drawText(label, left + 22f, top + 26f - (p.ascent() + p.descent()) / 2f, p)
        if (count != null) {
            val n = numberPaint(28f, Theme.dim).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(count, right - 24f, top + 28f - (n.ascent() + n.descent()) / 2f, n)
        }
        return top + 62f
    }

    /** the bronze nub in front of an objective, or the green tick of one that is [done]; centred, [size] px */
    fun objectiveMark(c: Canvas, cx: Float, cy: Float, size: Float = 24f, done: Boolean = false) {
        val b = tex(if (done) "tracker_check" else "tracker_nub")
        if (b != null) { c.drawBitmap(b, null, RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f), pBmp); return }
        pFlat.style = Paint.Style.FILL; pFlat.color = if (done) Theme.questGreen else Theme.bronze
        c.drawCircle(cx, cy, size * 0.22f, pFlat)
    }

    /**
     * ONE QUEST OF THE TRACKER, as the game lays it out: the disc, "[7] Title" in [colour] (the
     * quest's difficulty colour), and under it the first [objective] behind its nub; a quest that
     * is ready shows "Ready for turn-in" without a nub. [r] is the whole row and the touch target
     * (112 px high or more).
     * [disc] false = NO disc (the user, 2026-09-29 16:37: "the focused quest should just be highlighted, so that people arent
     * trying to hit that tiny button"): the text starts where the disc stood. [highlight] = the FOCUSED quest: the row drawn as
     * the kit's selected row ([selectedRow]: warm fill, the game's highlight bar in gold, a gold left edge) behind the text.
     */
    fun questRow(c: Canvas, r: RectF, title: String, colour: Int, objective: String, state: Quest = Quest.PROGRESS,
                 focus: Boolean = false, objectiveDone: Boolean = false, disc: Boolean = true, highlight: Boolean = false) {
        if (highlight) selectedRow(c, r)
        if (disc) questDisc(c, r.left + 48f, r.centerY() - 4f, 72f, state, focus)
        val pt = bodyPaint(32f, colour)
        val tx = if (disc) r.left + 104f else r.left + 22f
        val room = r.right - 12f - tx
        c.drawText(TextUtils.ellipsize(title, pt, room, TextUtils.TruncateAt.END).toString(), tx,
            r.top + 32f - (pt.ascent() + pt.descent()) / 2f, pt)
        val po = bodyPaint(27f, Color.rgb(204, 204, 204))
        val oy = r.top + 74f
        if (state == Quest.TURN_IN) {
            c.drawText("Ready for turn-in", tx, oy - (po.ascent() + po.descent()) / 2f, po)
        } else {
            objectiveMark(c, tx + 12f, oy, 24f, objectiveDone)
            if (objectiveDone) po.color = Theme.dim
            c.drawText(TextUtils.ellipsize(objective, po, room - 32f, TextUtils.TruncateAt.END).toString(), tx + 32f,
                oy - (po.ascent() + po.descent()) / 2f, po)
        }
    }

    /**
     * THE FOCUSED QUEST ON ONE LINE (Home, quests small): disc, title, and at the right THE
     * CURRENT OBJECTIVE'S PROGRESS (user, 2026-09-28). The title keeps its room; a progress that
     * does not fit loses its last words, never its numbers. [r] is the line and the touch target.
     * [disc] and [highlight]: as [questRow].
     */
    fun questLine(c: Canvas, r: RectF, title: String, colour: Int, progress: String, state: Quest = Quest.PROGRESS,
                  disc: Boolean = true, highlight: Boolean = false) {
        if (highlight) selectedRow(c, r)
        if (disc) questDisc(c, r.left + 46f, r.centerY(), 64f, state, true)
        val pt = bodyPaint(27f, colour)
        val tx = if (disc) r.left + 90f else r.left + 22f
        val cy = r.centerY()
        val show = if (state == Quest.TURN_IN) "Ready for turn-in" else progress
        val po = bodyPaint(25f, Color.rgb(204, 204, 204)).apply { textAlign = Paint.Align.RIGHT }
        val least = minOf(po.measureText(show), 120f)                 // the numbers always fit
        val t = TextUtils.ellipsize(title, pt, r.right - 16f - least - 20f - tx, TextUtils.TruncateAt.END).toString()
        c.drawText(t, tx, cy - 2f - (pt.ascent() + pt.descent()) / 2f, pt)
        val room = r.right - 16f - (tx + pt.measureText(t) + 20f)
        c.drawText(TextUtils.ellipsize(show, po, room, TextUtils.TruncateAt.END).toString(), r.right - 16f,
            cy - (po.ascent() + po.descent()) / 2f, po)
    }

    /** the game's red arrow button, turned to point "left", "right", "up" or "down"; centred, [size] px */
    fun arrow(c: Canvas, direction: String, cx: Float, cy: Float, size: Float, state: Button = Button.UP) {
        val turn = when (direction) { "left" -> 90f; "right" -> -90f; "up" -> 180f; else -> 0f }   // the art points down
        c.save()
        c.rotate(turn, cx, cy)
        val r = RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        val b = tex("square_arrow_${state.name.lowercase()}") ?: tex("square_arrow_up")
        if (b != null) c.drawBitmap(b, null, r, pBmp)
        else button(c, RectF(r.left + 8f, r.top + 8f, r.right - 8f, r.bottom - 8f), "v", state)
        c.restore()
    }

    // ---------------------------------------------------------------- the spellbook
    //
    // What the game's own spellbook is made of (PlayerSpellsFrame's Spellbook face; read from
    // blizzard_spellbooktemplates.xml and blizzard_spellbookitem.xml in the client): parchment
    // pages, small icon tabs standing on the page's top edge, and for every spell its icon in an
    // ornate frame with its name and rank beside it. User pick 2026-09-28: "B, the open book":
    // the spells on the left page, the chosen spell on the right page.

    /** a spell in the book: known; not yet ("Level 18", grey); or to be had from a trainer now ("See your trainer") */
    enum class Spell { KNOWN, LEVEL, TRAIN }

    /**
     * One page of the book over [r]; [right] = the right-hand page of the open book. The page's
     * edge keeps its shape, its middle stretches. Returns the rectangle that is free for content.
     */
    fun bookPage(c: Canvas, r: RectF, right: Boolean = false): RectF {
        val b = tex(if (right) "book_page_right" else "book_page_left")
        if (b == null) {
            pFlat.style = Paint.Style.FILL; pFlat.color = Theme.parchment
            c.drawRect(r, pFlat)
        } else {
            val e = 60; val o = 84f                                   // the edge in the art and on the screen
            val xs = intArrayOf(0, e, b.width - e, b.width); val ys = intArrayOf(0, e, b.height - e, b.height)
            val xd = floatArrayOf(r.left, r.left + o, r.right - o, r.right)
            val yd = floatArrayOf(r.top, r.top + o, r.bottom - o, r.bottom)
            for (i in 0..2) for (j in 0..2)
                c.drawBitmap(b, Rect(xs[i], ys[j], xs[i + 1], ys[j + 1]), RectF(xd[i], yd[j], xd[i + 1] + 0.5f, yd[j + 1] + 0.5f), pBmp)
        }
        return RectF(r.left + 40f, r.top + 30f, r.right - 40f, r.bottom - 30f)
    }

    /** the fold between the two pages of the open book: a soft shade either side of [x] */
    fun bookFold(c: Canvas, x: Float, top: Float, bottom: Float) {
        pFlat.style = Paint.Style.FILL
        for (i in 0 until 18) {
            pFlat.color = Color.argb((76f * (1f - i / 18f)).toInt(), 40, 20, 5)
            c.drawRect(x - 1f - i, top, x - i, bottom, pFlat)
            c.drawRect(x + i, top, x + i + 1f, bottom, pFlat)
        }
    }

    /**
     * One of the book's icon tabs (a school, General, Pet), [r] = 116 x 100. The tab of the open
     * line is [active]: its frame is lit and its icon bright. Draw the tabs BEFORE the page, so
     * that the page's edge covers their feet.
     */
    fun bookTab(c: Canvas, r: RectF, icon: Bitmap?, active: Boolean, cursor: Boolean = false) {
        if (cursor) { tabCursor(c, r, "book:$active") { bookTab(it, r, icon, active) }; return }
        val k = r.width() / 86f
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(8, 7, 6)
        c.drawRect(r.left + 12f * k, r.top + 13f * k, r.right - 12f * k, r.bottom, pFlat)
        val s = 58f * k - 4f
        val top = r.top + 15f * k + 2f
        if (icon != null)
            c.drawBitmap(icon, null, RectF(r.centerX() - s / 2f, top, r.centerX() + s / 2f, top + s), if (active) pBmp else pDim)
        val frame = tex("book_tab")
        if (frame != null) c.drawBitmap(frame, null, r, pBmp)
        else {
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 3f; pFlat.color = Theme.bronze
            c.drawRect(r.left + 10f, r.top + 10f, r.right - 10f, r.bottom, pFlat)
        }
        if (active) {
            val g = tex("book_tab_glow")
            if (g != null) c.drawBitmap(g, null, r, pBmp)
            else {
                pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 4f; pFlat.color = Theme.gold
                c.drawRect(r.left + 10f, r.top + 10f, r.right - 10f, r.bottom, pFlat)
            }
        }
    }

    /**
     * The consumables radial in miniature (UI Art's approved v4 wheel): a dark disc in a bronze rim, eight spokes, the
     * chosen slice lit gold (bottom-right, where the game's selection sits in the picture) and the hub. THE RADIAL'S ONE PICTURE on the
     * DS WoW toolbar (b-radial-icon, the user 2026-10-13: "use the same icon everywhere on the toolbar"): CastView's D-pad Up and the
     * Controller room's DS tab both draw this, never the potion icon.
     */
    fun miniWheel(c: Canvas, cx: Float, cy: Float, r: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        fill.color = android.graphics.Color.rgb(20, 16, 12)
        c.drawCircle(cx, cy, r, fill)
        // the chosen slice
        fill.color = android.graphics.Color.argb(150, 200, 160, 70)
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 22.5f, 45f, true, fill)
        // the spokes
        stroke.color = Theme.bronzeDim; stroke.strokeWidth = kotlin.math.max(1.2f, r * 0.06f)
        for (i in 0 until 8) {
            val a = Math.toRadians(22.5 + i * 45.0)
            val ca = kotlin.math.cos(a).toFloat(); val sa = kotlin.math.sin(a).toFloat()
            c.drawLine(cx + ca * r * 0.13f, cy + sa * r * 0.13f, cx + ca * r, cy + sa * r, stroke)
        }
        // the rim
        stroke.color = Theme.bronze; stroke.strokeWidth = kotlin.math.max(1.5f, r * 0.1f)
        c.drawCircle(cx, cy, r, stroke)
        // the hub: a small dark centre in a bronze ring (user 10-06: no red swirl, "make that inner circle way smaller")
        fill.color = android.graphics.Color.rgb(46, 34, 22)
        c.drawCircle(cx, cy, r * 0.13f, fill)
        stroke.color = Theme.bronze; stroke.strokeWidth = kotlin.math.max(1f, r * 0.05f)
        c.drawCircle(cx, cy, r * 0.13f, stroke)
    }

    /**
     * A spell's icon in the book's own frame. ([x], [y]) is the ICON's top left corner and [size]
     * its side; the frame lies round it (its leaves reach 0.5 of [size] to the left and 0.4 below).
     * [onBar] = false for a known spell that is on no button: the game's gold glow. [passive] = the
     * round frame. [chosen] = the entry a finger or the controller is on. [onDark] = not on a page.
     * [autoCast] (a PET's spell; hunter pets, warlock demons): 0 = it cannot cast itself, 1 = it
     * may (the game's gold corners, quiet), 2 = it does (the corners bright and the game's ring).
     */
    fun spell(c: Canvas, icon: Bitmap?, x: Float, y: Float, size: Float, state: Spell = Spell.KNOWN, onBar: Boolean = true,
              passive: Boolean = false, chosen: Boolean = false, onDark: Boolean = false, autoCast: Int = 0, noMana: Boolean = false) {
        val known = state == Spell.KNOWN
        val ir = RectF(x, y, x + size, y + size)
        val pIcon = if (known) pBmp else pFaint.apply { alpha = if (onDark) 204 else 153 }
        if (passive) {
            val k = size / 75f
            val fr = RectF(x - 21f * k, y - 20f * k, x + 97f * k, y + 98f * k)
            if (icon != null) {
                c.save()
                c.clipPath(android.graphics.Path().apply {
                    addCircle(ir.centerX(), ir.centerY(), size / 2f, android.graphics.Path.Direction.CW)
                })
                c.drawBitmap(icon, null, ir, pIcon)
                c.restore()
            }
            tex(if (known) "book_round" else "book_round_off")?.let { c.drawBitmap(it, null, fr, pBmp) }
            return
        }
        val k = size / 80f
        val frame = tex(if (known) "book_frame" else "book_frame_off")
        if (frame == null) {                                          // no art: the icon in a plain line
            if (icon != null) c.drawBitmap(icon, null, ir, pIcon)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 3f
            pFlat.color = if (chosen) Theme.gold else Theme.bronze
            c.drawRect(ir, pFlat)
            return
        }
        val ox = if (known) 41f else 40f; val oy = if (known) 18f else 16f
        val fr = RectF(x - ox * k, y - oy * k, x - ox * k + frame.width * k, y - oy * k + frame.height * k)
        if (state == Spell.TRAIN) tex("book_frame_train")?.let { c.drawBitmap(it, null, fr, pBmp) }
        if (known) tex("book_frame_shadow")?.let {
            c.drawBitmap(it, null, RectF(fr.left - 3f * k, fr.top - 3f * k, fr.left - 3f * k + it.width * k,
                fr.top - 3f * k + it.height * k), pBmp)
        }
        if (icon != null) c.drawBitmap(icon, null, ir, pIcon)
        if (noMana && icon != null) manaTint(c, ir)                   // m71: the game's out-of-mana blue, under the frame
        c.drawBitmap(frame, null, fr, pBmp)
        if (known && !onBar) tex("book_glow")?.let {
            // the game's "missing from all bars" glow (SpellBookItem's ActionBarHighlight): ADDED and PULSING, alpha 0.5 -> 1 -> 0.5
            // over a second, every glow on the page in step - so it never reads as the (still) gold cursor box. The room keeps
            // redrawing while one shows (bookGlowLive)
            val g = 127f * k / 2f
            pSpark.alpha = (255 * bookGlowAlpha()).toInt()
            c.drawBitmap(it, null, RectF(ir.centerX() - g, ir.centerY() - g, ir.centerX() + g, ir.centerY() + g), pSpark)
            pSpark.alpha = 255
            bookGlowLive = true
        }
        if (chosen) tex("book_frame_hover")?.let {
            pGlow.colorFilter = null; pGlow.alpha = 76
            c.drawBitmap(it, null, RectF(x - 38f * k, y - 15f * k, x - 38f * k + it.width * k, y - 15f * k + it.height * k), pGlow)
            pGlow.alpha = 255
        }
        if (autoCast > 0) {
            if (autoCast == 2) tex("book_autocast_on")?.let {
                pTint.colorFilter = android.graphics.PorterDuffColorFilter(Theme.gold, PorterDuff.Mode.MULTIPLY)
                pTint.alpha = 200
                c.drawBitmap(it, null, RectF(ir.left - 2f * k, ir.top - 2f * k, ir.right + 2f * k, ir.bottom + 2f * k), pTint)
                pTint.alpha = 255
            }
            tex("book_autocast")?.let {
                pBmp.alpha = if (autoCast == 2) 255 else 130
                c.drawBitmap(it, null, RectF(ir.left - 5f * k, ir.top - 5f * k, ir.right + 5f * k, ir.bottom + 5f * k), pBmp)
                pBmp.alpha = 255
            }
        }
    }

    /**
     * ONE ENTRY OF THE BOOK, on a page: the spell in its frame, its name (up to two lines) and
     * under it [sub]: the rank, "Passive", "Level 18" or "See your trainer" (the game's own words).
     * The whole of [r] is the touch target. [chosen] = the entry a finger or the controller is on.
     */
    fun bookEntry(c: Canvas, r: RectF, icon: Bitmap?, name: String, sub: String?, state: Spell = Spell.KNOWN,
                  onBar: Boolean = true, chosen: Boolean = false, passive: Boolean = false, size: Float = 80f,
                  autoCast: Int = 0, noMana: Boolean = false) {
        if (chosen) {
            val band = RectF(r.left, r.top + 5f, r.right, r.bottom - 5f)
            pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(51, 110, 60, 14)
            c.drawRect(band, pFlat)
            paperGlow(c, band, 0.5f)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Color.rgb(122, 84, 36)
            c.drawRect(band, pFlat)
        }
        val ix = r.left + 24f; val iy = r.top + (r.height() - size) / 2f - 2f
        spell(c, icon, ix, iy, size, state, onBar, passive, chosen, autoCast = autoCast, noMana = noMana)
        val tx = ix + size + 16f
        val room = r.right - 8f - tx
        val known = state == Spell.KNOWN
        val pn = inkPaint(27f).apply { color = if (known) Theme.parchmentInk else Theme.bookInkOff }
        val ps = inkPaint(22f).apply { color = if (known) Theme.parchmentInkDim else Theme.bookInkOffDim }
        val under = sub != null && sub.isNotEmpty()
        if (under) while (ps.textSize > 18f && ps.measureText(sub) > room) ps.textSize -= 1f
        val lines = twoLines(name, pn, room)
        val lh = 31f
        var ty = r.centerY() - (lines.size * lh + (if (under) 26f else 0f)) / 2f - 2f
        for (ln in lines) { c.drawText(ln, tx, ty - pn.ascent() - 3f, pn); ty += lh }
        if (under) c.drawText(sub!!, tx, ty + 2f - ps.ascent() - 3f, ps)
    }

    /** [s] on at most two lines of [room] px; what does not fit on the second is cut with "..." */
    private fun twoLines(s: String, p: TextPaint, room: Float): List<String> {
        if (p.measureText(s) <= room) return listOf(s)
        val words = s.split(' ')
        var first = ""
        var n = 0
        while (n < words.size) {
            val t = if (first.isEmpty()) words[n] else first + " " + words[n]
            if (first.isNotEmpty() && p.measureText(t) > room) break
            first = t; n++
        }
        val rest = words.drop(n).joinToString(" ")
        if (rest.isEmpty()) return listOf(first)
        return listOf(first, TextUtils.ellipsize(rest, p, room, TextUtils.TruncateAt.END).toString())
    }

    /** the name of the open line at the head of a page (Morpheus, in ink) and the book's own rule under it */
    fun bookHeading(c: Canvas, left: Float, cy: Float, width: Float, name: String) {
        val p = inkPaint(42f, heading = true)
        c.drawText(name, left, cy - (p.ascent() + p.descent()) / 2f, p)
        val d = tex("book_divider")
        if (d != null) c.drawBitmap(d, null, RectF(left, cy + 30f, left + width, cy + 41f), pBmp)
        else {
            pFlat.style = Paint.Style.FILL; pFlat.color = Theme.parchmentInkDim
            c.drawRect(left, cy + 34f, left + width, cy + 36f, pFlat)
        }
    }

    /**
     * Turning the pages, as the game's book has it at the foot of a page: two arrows and
     * "Page 1 / 2" between them, centred on ([cx], [cy]). The arrows are 72 px, 150 px left and
     * right of [cx]; give each a touch box of 112 x 111. An arrow that leads nowhere is greyed.
     */
    fun bookPaging(c: Canvas, cx: Float, cy: Float, page: Int, pages: Int, onPaper: Boolean = true) {
        arrow(c, "left", cx - 150f, cy, 72f, if (page > 1) Button.UP else Button.DISABLED)
        arrow(c, "right", cx + 150f, cy, 72f, if (page < pages) Button.UP else Button.DISABLED)
        val p = if (onPaper) inkPaint(26f) else bodyPaint(26f)
        p.textAlign = Paint.Align.CENTER
        c.drawText("Page $page / $pages", cx, cy - 2f - (p.ascent() + p.descent()) / 2f, p)
    }

    /**
     * A controller button as the game names it for the bars: the trigger that is held, a plus,
     * the button ("rev_rtrigger" + "rev_a" = ZR + A). [trigger] null = the button alone. Both
     * are glyph names for [pad]. Starts at [x], centred on [cy]; returns the x after it.
     */
    fun padCombo(c: Canvas, trigger: String?, glyph: String, x: Float, cy: Float, size: Float, colour: Int = Theme.text,
                 shadow: Boolean = true): Float {
        var px = x
        if (trigger != null) {
            pad(c, trigger, px + size / 2f, cy, size)
            px += size + 6f
            val p = numberPaint(size * 0.6f, colour, shadow).apply { textAlign = Paint.Align.CENTER }
            c.drawText("+", px + 8f, cy - 2f - (p.ascent() + p.descent()) / 2f, p)
            px += 22f
        }
        pad(c, glyph, px + size / 2f, cy, size)
        return px + size
    }

    /**
     * A DOOR to another room, drawn with the game's own menu picture: "talents" (the target) in
     * the Spellbook, "spells" (the book) in the Talents room. Centred, its longer side [size] px.
     */
    fun door(c: Canvas, what: String, cx: Float, cy: Float, size: Float, down: Boolean = false) {
        val b = tex("door_$what")
        if (b == null) {
            button(c, RectF(cx - size / 2f, cy - size * 0.35f, cx + size / 2f, cy + size * 0.35f),
                what.replaceFirstChar { it.uppercase() }, if (down) Button.DOWN else Button.UP)
            return
        }
        val k = size / maxOf(b.width, b.height)
        val w = b.width * k; val h = b.height * k
        c.drawBitmap(b, null, RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f), if (down) pDim else pBmp)
    }

    /**
     * A CARD that comes over a room for a moment (the "New spell" card): the game's menu box with
     * a soft shadow under it. [waiting] = how many more cards wait under this one (two edges
     * show at most). Returns the inside.
     */
    fun card(c: Canvas, r: RectF, waiting: Int = 0): RectF {
        val n = waiting.coerceIn(0, 2)
        for (i in n downTo 1) {
            val o = i * 12f
            menu(c, RectF(r.left + o, r.top + o, r.right - o, r.bottom + o), solid = true)
        }
        val under = r.bottom + n * 12f
        pFlat.style = Paint.Style.FILL
        for (i in 0 until 16) {
            pFlat.color = Color.argb((102f * (1f - i / 16f)).toInt(), 0, 0, 0)
            c.drawRect(r.left + 10f, under + i, r.right - 10f, under + i + 1f, pFlat)
        }
        return menu(c, r, solid = true)
    }

    // ---------------------------------------------------------------- the game's character window
    //
    // What the game's own character window is made of (CharacterFrame, camelot; read from
    // characterframe.xml, reputationframe.xml and the shared templates in the client): six large
    // tabs down its right edge, a list pane and a detail pane with a divider between them,
    // sections of stats under title plates, lists under headings that fold, and the standing bar.
    // User pick 2026-09-28: "C, the game's panes in a room", long lists in two columns.
    // THE SIDE TAB also carries the Professions window's and the Group Finder's tabs.

    /**
     * One of the game's large side tabs, [r] = 116 x 126, standing on the room's right edge.
     * The tab of the open page is [active]: the game's gold frame, its picture bright.
     */
    fun sideTab(c: Canvas, r: RectF, icon: Bitmap?, active: Boolean, cursor: Boolean = false) {
        if (cursor) { tabCursor(c, r, "side:$active") { sideTab(it, r, icon, active) }; return }
        val tab = tex("side_tab")
        if (tab == null) {
            pFlat.style = Paint.Style.FILL; pFlat.color = if (active) Theme.selected else Theme.raised
            c.drawRect(r.left, r.top + 4f, r.right, r.bottom - 4f, pFlat)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 3f; pFlat.color = if (active) Theme.gold else Theme.bronzeDim
            c.drawRect(r.left, r.top + 4f, r.right, r.bottom - 4f, pFlat)
            if (icon != null) {
                val s = r.width() * 0.6f
                c.drawBitmap(icon, null, RectF(r.centerX() - s / 2f, r.centerY() - s / 2f, r.centerX() + s / 2f, r.centerY() + s / 2f),
                    if (active) pBmp else pDim)
            }
            return
        }
        c.drawBitmap(tab, null, r, pBmp)
        val mask = tex("side_tab_mask")
        if (icon != null) {
            val s = r.width() * 0.66f
            val ir = RectF(r.centerX() - s / 2f - 6f, r.centerY() - s / 2f, r.centerX() + s / 2f - 6f, r.centerY() + s / 2f)
            if (mask != null) {
                val layer = c.saveLayer(r, null)
                c.drawBitmap(icon, null, ir, if (active) pBmp else pDim)
                c.drawBitmap(mask, null, r, pMask)
                c.restoreToCount(layer)
            } else c.drawBitmap(icon, null, ir, if (active) pBmp else pDim)
        }
        if (active) tex("side_tab_on")?.let { c.drawBitmap(it, null, r, pBmp) }
    }

    /** a pane's ground over [r]: the list's (left) or the detail's ([right]) */
    fun pane(c: Canvas, r: RectF, right: Boolean = false) {
        val b = tex(if (right) "pane_right" else "pane_left")
        if (b == null) {
            pFlat.style = Paint.Style.FILL; pFlat.color = if (right) Theme.panel else Theme.ink
            c.drawRect(r, pFlat); return
        }
        c.drawBitmap(b, null, r, pLit)
    }

    /** the game's divider between the two panes, down from [top] to [bottom], centred on [x] */
    fun paneDivider(c: Canvas, x: Float, top: Float, bottom: Float) {
        val b = tex("pane_divider")
        if (b == null) { divider(c, x, top, x, bottom); return }
        val w = b.width.toFloat()
        val l = x - w / 2f
        c.drawBitmap(b, Rect(0, 0, b.width, 30), RectF(l, top, l + w, top + 30f), pBmp)
        c.drawBitmap(b, Rect(0, b.height - 30, b.width, b.height), RectF(l, bottom - 30f, l + w, bottom), pBmp)
        c.drawBitmap(b, Rect(0, 30, b.width, b.height - 30), RectF(l, top + 30f, l + w, bottom - 30f), pBmp)
    }

    /** the rule under a detail pane's title and subtitle */
    fun paneRule(c: Canvas, left: Float, y: Float, right: Float) {
        val b = tex("pane_rule")
        if (b == null) divider(c, left, y + 6f, right, y + 6f) else c.drawBitmap(b, null, RectF(left, y, right, y + 14f), pBmp)
    }

    /** the title plate of a section of stats ("Attributes"), [r] 58 px high */
    fun sectionTitle(c: Canvas, r: RectF, label: String) {
        val b = tex("section_title")
        if (b != null) c.drawBitmap(b, null, r, pBmp)
        else { pFlat.style = Paint.Style.FILL; pFlat.color = Theme.raised; c.drawRect(r, pFlat) }
        val p = bodyPaint(27f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
        c.drawText(label, r.centerX(), r.centerY() - 1f - (p.ascent() + p.descent()) / 2f, p)
    }

    /**
     * One line of stats, [r] 44 px high: the label in gold at the left, the value at the right,
     * white, green when a buff has raised it ([state] 1), red when something has lowered it (2),
     * the game's light blue for a value that is a bonus in waiting (3: rested experience).
     * [band] = every second line has the game's band behind it. [icon] = a resistance's picture.
     */
    fun statLine(c: Canvas, r: RectF, label: String, value: String, state: Int = 0, band: Boolean = false, icon: Bitmap? = null) {
        if (band) {
            val b = tex("section_band")
            if (b != null) c.drawBitmap(b, null, r, pBmp)
            else { pFlat.style = Paint.Style.FILL; pFlat.color = Theme.raised; c.drawRect(r, pFlat) }
        }
        var x = r.left + 16f
        if (icon != null) {
            c.drawBitmap(icon, null, RectF(r.left + 8f, r.centerY() - 20f, r.left + 48f, r.centerY() + 20f), pBmp)
            x = r.left + 58f
        }
        val pv = bodyPaint(26f, when (state) { 1 -> Theme.buffed; 2 -> Theme.debuffed; 3 -> Theme.notOnBar; else -> Color.WHITE })
        pv.textAlign = Paint.Align.RIGHT
        c.drawText(value, r.right - 16f, r.centerY() - 1f - (pv.ascent() + pv.descent()) / 2f, pv)
        val pl = bodyPaint(26f, Theme.gold)
        val room = r.right - 16f - pv.measureText(value) - 18f - x
        c.drawText(TextUtils.ellipsize(label, pl, room, TextUtils.TruncateAt.END).toString(), x,
            r.centerY() - 1f - (pl.ascent() + pl.descent()) / 2f, pl)
    }

    /** one of the small tabs over the stats (Stats, Gear sets, Pet), [r] = 96 x 96 */
    fun smallTab(c: Canvas, r: RectF, icon: Bitmap?, active: Boolean, cursor: Boolean = false) {
        if (cursor) { tabCursor(c, r, "small:$active") { smallTab(it, r, icon, active) }; return }
        val b = tex("small_tab")
        if (b != null) c.drawBitmap(b, null, r, pBmp)
        else { pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 3f; pFlat.color = Theme.bronzeDim; c.drawRect(r, pFlat) }
        if (icon != null) {
            val k = r.width() * 0.18f
            c.drawBitmap(icon, null, RectF(r.left + k, r.top + k, r.right - k, r.bottom - k), if (active) pBmp else pDim)
        }
        if (active) {
            val on = tex("small_tab_on")
            if (on != null) c.drawBitmap(on, null, r, pBmp)
            else { pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 4f; pFlat.color = Theme.gold; c.drawRect(r, pFlat) }
        }
    }

    /**
     * A SAVED GEAR SET'S CARD (the Gear sets tab over the stats), [r] 111 px high: the game's card,
     * the set's icon in its frame, its name, and the game's green tick on the set that is worn.
     */
    fun gearSet(c: Canvas, r: RectF, icon: Bitmap?, name: String, worn: Boolean, chosen: Boolean = false) {
        val b = tex(if (chosen) "set_card_on" else "set_card")
        if (b != null) c.drawBitmap(b, null, r, pBmp) else listLine(c, r, chosen)
        val s = r.height() - 30f
        val ir = RectF(r.left + 18f, r.centerY() - s / 2f, r.left + 18f + s, r.centerY() + s / 2f)
        if (icon != null) c.drawBitmap(icon, null, RectF(ir.left + 6f, ir.top + 6f, ir.right - 6f, ir.bottom - 6f), pBmp)
        tex("set_frame")?.let { c.drawBitmap(it, null, ir, pBmp) }
        val p = bodyPaint(27f, if (chosen) Theme.gold else Color.WHITE)
        val room = r.right - (if (worn) 78f else 18f) - (ir.right + 16f)
        c.drawText(TextUtils.ellipsize(name, p, room, TextUtils.TruncateAt.END).toString(), ir.right + 16f,
            r.centerY() - 1f - (p.ascent() + p.descent()) / 2f, p)
        if (worn) tex("set_tick")?.let { c.drawBitmap(it, null, RectF(r.right - 70f, r.centerY() - 23f, r.right - 20f, r.centerY() + 23f), pBmp) }
    }

    /**
     * A HEADING OF A LIST that folds ("Alliance", "Armor"): the game's plate, its minus while it
     * is [open] and its plus while it is shut, the name in gold. [r] is the whole line and the
     * touch target; the plate is [plateHeight] (72 px) high in its middle. With [signRight] the
     * name stands at the left and the sign at the right, as the group finder's headings have
     * them (there the line is 56 px high and the plate 50).
     */
    fun listHeading(c: Canvas, r: RectF, label: String, open: Boolean, signRight: Boolean = false, plateHeight: Float = 72f) {
        val plate = RectF(r.left + 12f, r.centerY() - plateHeight / 2f, r.right - 12f, r.centerY() + plateHeight / 2f)
        val b = tex("list_heading")
        if (b != null) c.drawBitmap(b, null, plate, pBmp)
        else { pFlat.style = Paint.Style.FILL; pFlat.color = Theme.raised; c.drawRect(plate, pFlat) }
        // [signRight]: the group finder's headings carry the sign at the right and the name at the left
        val sign = tex(if (open) "list_minus" else "list_plus")
        val sx = if (signRight) r.right - 40f - 30f else r.left + 30f
        if (sign != null) {
            val w = 30f; val h = w * sign.height / sign.width
            c.drawBitmap(sign, null, RectF(sx, r.centerY() - h / 2f, sx + w, r.centerY() + h / 2f), pBmp)
        }
        val p = bodyPaint(28f, Theme.gold)
        val left = if (signRight) r.left + 36f else r.left + 76f
        val room = (if (signRight) sx - 16f else plate.right - 20f) - left
        c.drawText(TextUtils.ellipsize(label, p, room, TextUtils.TruncateAt.END).toString(),
            left, r.centerY() - 2f - (p.ascent() + p.descent()) / 2f, p)
    }

    /** the ground of one entry of a list, [chosen] = the one a finger or the controller is on; draw its content over it */
    fun listLine(c: Canvas, r: RectF, chosen: Boolean) {
        val box = RectF(r.left + 4f, r.top + 4f, r.right - 4f, r.bottom - 4f)
        val b = tex(if (chosen) "list_line_on" else "list_line")
        if (b != null) { c.drawBitmap(b, null, box, pBmp); return }
        pFlat.style = Paint.Style.FILL; pFlat.color = if (chosen) Theme.selected else Theme.raised
        c.drawRect(box, pFlat)
        if (chosen) { pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Theme.bronze; c.drawRect(box, pFlat) }
    }

    /**
     * THE GAME'S STANDING BAR (and its skill bar): the ground, the fill up to [fill] (0..1) in
     * [colour] (`Theme.standing(index)`, `Theme.skillBar`), and [label] on it in white.
     */
    fun standing(c: Canvas, r: RectF, colour: Int, fill: Float, label: String, textSize: Float = 24f) {
        val g = tex("bar_ground")
        val k = r.height() / 58f
        if (g != null) {
            val cap = 24; val cw = cap * k
            c.drawBitmap(g, Rect(0, 0, cap, g.height), RectF(r.left, r.top, r.left + cw, r.bottom), pBmp)
            c.drawBitmap(g, Rect(g.width - cap, 0, g.width, g.height), RectF(r.right - cw, r.top, r.right, r.bottom), pBmp)
            c.drawBitmap(g, Rect(cap, 0, g.width - cap, g.height), RectF(r.left + cw - 0.5f, r.top, r.right - cw + 0.5f, r.bottom), pBmp)
        } else {
            pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(12, 10, 8); c.drawRect(r, pFlat)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Theme.bronzeDim; c.drawRect(r, pFlat)
        }
        val inner = RectF(r.left + 9f * k, r.top + 13f * k, r.right - 9f * k, r.bottom - 13f * k)
        val w = inner.width() * fill.coerceIn(0f, 1f)
        if (w > 0.5f) {
            val white = tex("bar_white")
            val to = RectF(inner.left, inner.top, inner.left + w, inner.bottom)
            // the colour itself first, then the game's white fill in that colour over it: its light from the left
            pFlat.style = Paint.Style.FILL; pFlat.color = colour
            c.drawRect(to, pFlat)
            if (white != null) {
                pTint.colorFilter = android.graphics.PorterDuffColorFilter(colour, PorterDuff.Mode.MULTIPLY)
                pTint.alpha = 150
                c.drawBitmap(white, null, to, pTint)
                pTint.alpha = 255
            }
        }
        val p = bodyPaint(textSize, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
        c.drawText(TextUtils.ellipsize(label, p, r.width() - 24f, TextUtils.TruncateAt.END).toString(), r.centerX(),
            r.centerY() - 1f - (p.ascent() + p.descent()) / 2f, p)
    }

    /**
     * THE CLASS'S EMBLEM: the game's own ground for the place where its window shows the
     * character in 3D. [classFile] is the class's file name as the game gives it ("HUNTER").
     */
    fun emblem(c: Canvas, r: RectF, classFile: String) {
        val b = tex("class_" + classFile.lowercase())
        if (b == null) {
            pFlat.style = Paint.Style.FILL; pFlat.color = Theme.field
            c.drawRect(r, pFlat)
        } else c.drawBitmap(b, null, r, pEmblem)
        pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 1.5f; pFlat.color = Color.rgb(60, 48, 26)
        c.drawRect(r, pFlat)
    }

    /** a class's own picture ([classFile] as the game gives it, "HUNTER"), or null when the kit has none */
    fun classIcon(classFile: String): Bitmap? = tex("classicon_" + classFile.lowercase())

    /**
     * A PET'S PAGE, in the doll's place (the game swaps the left pane when the Pet tab is
     * chosen): the class's emblem as its ground, the pet's picture where the game shows it in
     * 3D, and for a hunter's pet its face top left ([mood] 1 unhappy, 2 content, 3 happy; 0 =
     * none, a warlock's demon). Returns the rectangle under the picture that is free for the
     * level line, the loyalty line and the pet's own experience bar.
     */
    fun petPage(c: Canvas, r: RectF, classFile: String, icon: Bitmap?, mood: Int = 0): RectF {
        emblem(c, r, classFile)
        val s = minOf(r.width() * 0.5f, r.height() * 0.4f)
        val top = r.top + r.height() * 0.12f
        framed(c, icon, r.centerX() - s / 2f, top, s)
        if (mood in 1..3) tex(when (mood) { 1 -> "pet_mad"; 2 -> "pet_neutral"; else -> "pet_happy" })?.let {
            c.drawBitmap(it, null, RectF(r.left + 18f, r.top + 18f, r.left + 18f + 96f, r.top + 18f + 96f), pBmp)
        }
        return RectF(r.left + 24f, top + s + 20f, r.right - 24f, r.bottom - 20f)
    }

    /**
     * THE PVP RANK'S BADGE, centred on ([cx], [cy]), [size] = the ring's width (the game: 154
     * units). [rank] 0 = no rank yet: the faction's own badge. [fill] 0..1 = the rank points
     * earned of the next rank's; [faction] "Alliance" or "Horde".
     */
    fun rankBadge(c: Canvas, cx: Float, cy: Float, size: Float, rank: Int, faction: String, fill: Float) {
        val f = if (faction.equals("Horde", true)) "horde" else "alliance"
        val k = size / 460f
        tex("rank_glow")?.let { c.drawBitmap(it, null, RectF(cx - 185f * k, cy - 185f * k, cx + 185f * k, cy + 185f * k), pBmp) }
        tex("rank_ring")?.let { c.drawBitmap(it, null, RectF(cx - 230f * k, cy - 201f * k, cx + 230f * k, cy + 201f * k), pBmp) }
        tex("rank_disc_$f")?.let { c.drawBitmap(it, null, RectF(cx - 95f * k * 1.6f, cy - 95f * k * 1.6f, cx + 95f * k * 1.6f, cy + 95f * k * 1.6f), pBmp) }
        // what is earned of the next rank: a gold arc round the disc, from the top, clockwise
        val rr = 168f * k
        pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 14f * k; pFlat.color = Color.argb(90, 0, 0, 0)
        c.drawCircle(cx, cy, rr, pFlat)
        pFlat.color = Theme.gold
        c.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), -90f, 360f * fill.coerceIn(0f, 1f), false, pFlat)
        val badge = tex(if (rank <= 0) "rank_$f" else "rank_${rank.coerceIn(1, 14)}")
        if (badge != null) c.drawBitmap(badge, null, RectF(cx - 75f * k * 1.5f, cy - 75f * k * 1.5f, cx + 75f * k * 1.5f, cy + 75f * k * 1.5f), pBmp)
        if (rank > 0) {
            val n = 54f * k * 1.5f
            val ny = cy + 150f * k
            tex("rank_number")?.let { c.drawBitmap(it, null, RectF(cx - n, ny - n, cx + n, ny + n), pBmp) }
            val p = numberPaint(44f * k * 1.5f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
            c.drawText(rank.toString(), cx, ny - (p.ascent() + p.descent()) / 2f, p)
        }
    }

    // ---------------------------------------------------------------- the game's radial
    //
    // The controller's own pop-out (Blizzard's flyout radial): eight places on a ring. User pick
    // 2026-09-28: the Cast room's class-only pop-outs (pet commands, pet spells, a class's own
    // actions) wear it, over the button group of the side that is held.

    /** the ring's width on the screen (the 2x art at 1.4) and the circle its eight places stand on */
    const val RADIAL = 512f
    const val RADIAL_RADIUS = 168f

    /** where place [i] (0 = the top, then clockwise) of a radial centred on ([cx], [cy]) has its middle */
    fun radialPlace(cx: Float, cy: Float, i: Int): android.graphics.PointF {
        val a = Math.toRadians(-90.0 + i * 45.0)
        return android.graphics.PointF(cx + RADIAL_RADIUS * Math.cos(a).toFloat(), cy + RADIAL_RADIUS * Math.sin(a).toFloat())
    }

    /**
     * THE GAME'S RADIAL, centred on ([cx], [cy]): the ring and its eight places. [icons] = up to
     * eight pictures, in order from the top, clockwise; a place past their end (or a null) stays
     * empty and dark. [pointed] = the place the stick points at (or a finger is on), -1 = none:
     * it is lit, and [label] (its name) stands in the ring's middle. [on] = places that wear the
     * gold ring (a pet's mode that is on, a spell that casts itself). Lay `cover(c, room, 0.65f)`
     * over the room first: the game dims its bar to 35 %. A place's touch box: 157 px round
     * [radialPlace].
     */
    fun radial(c: Canvas, cx: Float, cy: Float, icons: List<Bitmap?>, pointed: Int = -1, label: String? = null,
               on: Set<Int> = emptySet()) {
        val k = 1.4f
        val ring = tex("radial_ring")
        if (ring != null) c.drawBitmap(ring, null, RectF(cx - RADIAL / 2f, cy - RADIAL / 2f, cx + RADIAL / 2f, cy + RADIAL / 2f), pBmp)
        else {
            pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(230, 12, 10, 8)
            c.drawCircle(cx, cy, RADIAL / 2f, pFlat)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 3f; pFlat.color = Theme.bronze
            c.drawCircle(cx, cy, RADIAL / 2f - 2f, pFlat)
        }
        for (i in 0 until 8) {
            val p = radialPlace(cx, cy, i)
            val icon = icons.getOrNull(i)
            val s = 112f * k
            tex("radial_back")?.let { c.drawBitmap(it, null, RectF(p.x - 41f * k, p.y - 42f * k, p.x + 41f * k, p.y + 42f * k), pBmp) }
            val place = tex(if (icon != null && i == pointed) "radial_place_on" else "radial_place")
            if (place != null) {
                pBmp.alpha = if (icon == null) 115 else 255
                c.drawBitmap(place, null, RectF(p.x - s / 2f, p.y - s / 2f, p.x + s / 2f, p.y + s / 2f), pBmp)
                pBmp.alpha = 255
            } else {
                pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 3f
                pFlat.color = if (icon != null && i == pointed) Theme.gold else Theme.bronzeDim
                c.drawCircle(p.x, p.y, 54f, pFlat)
            }
            if (icon == null) continue
            val r = 35f * k                                       // the icon, cut round
            c.save()
            c.clipPath(android.graphics.Path().apply { addCircle(p.x, p.y, r, android.graphics.Path.Direction.CW) })
            c.drawBitmap(icon, null, RectF(p.x - r, p.y - r, p.x + r, p.y + r), pBmp)
            c.restore()
            if (i == pointed) tex("radial_light")?.let { c.drawBitmap(it, null, RectF(p.x - s / 2f, p.y - s / 2f, p.x + s / 2f, p.y + s / 2f), pBmp) }
            if (i in on) {
                pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 4f; pFlat.color = Theme.gold
                c.drawCircle(p.x, p.y, 40f * k, pFlat)
            }
        }
        if (label != null && pointed >= 0) {
            val p = bodyPaint(30f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
            while (p.textSize > 22f && p.measureText(label) > 190f) p.textSize -= 1f
            c.drawText(label, cx, cy - (p.ascent() + p.descent()) / 2f, p)
        }
    }

    // ---------------------------------------------------------------- the CONSUMABLES RADIAL (Home's D-pad UP)
    //
    // The user, 2026-10-05: "an animation of the home swirly growing upwards and showing a radial menu of all of my currently
    // available consumables in my bags"; picked look "A: bronze wheel" and "Pages on LB / RB"; then "I want the whole stone background
    // of the spiral circle from the bottom to move up and grow"; "just make the radial overlap where the stone starts from. The b
    // glyph in the middle with the swirling as red and the a glyph on the current selection"; "it needs to be a lot bigger";
    // APPROVED v4 ("Yes, build v4"). Pictures + numbers: wow-companion-design\home\consumables\ (CONSUMABLES_SPEC.md v2-v4,
    // SHEET_consumables_v4.png, CLIP_grow_v4.gif). Kit: `rwheel_bg`, `rwheel_on`, `rwheel_band` (the game's controller radial menu,
    // gamepad-radial-menu-wheelbg / -selected / -toptext) and the bar's own `home_live_base / _flare`, `home_live_red_base / _flare`.

    /** one item in a slice of the consumables radial: its icon (Icons.get), name, how many are in the bags, quality (0..7) */
    class RadialItem(val icon: Bitmap?, val name: String, val count: Int, val quality: Int = 1,
                     /** PINNED to its slice by the player (the game's gold pin on the icon's top-right corner) */
                     val pinned: Boolean = false,
                     /** a pin whose item RAN OUT: the icon greyed, the pin grey; it keeps its slice (pass count 0) */
                     val ranOut: Boolean = false,
                     /** m71: a spell the player cannot afford now: the game's blue out-of-mana look */
                     val noMana: Boolean = false)

    /** m71: the game's out-of-mana look over an icon already drawn in [r]: its colours times (0.5, 0.5, 1.0), the action button's own */
    fun manaTint(c: Canvas, r: RectF, round: Float = 0f) {
        if (r.isEmpty) return
        if (round > 0f) c.drawRoundRect(r, round, round, pManaTint) else c.drawRect(r, pManaTint)
    }
    private val pManaTint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(128, 128, 255); xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }

    /**
     * THE SLICE CHOOSER (the user's pin sheet, signed off 2026-10-06 "Build as drawn"): the item picked in the bag picker floats over the
     * hub, waiting for a slice. [prompt] = the band's second line (null = said by the slice pointed at: "Press A to pin it here" on an
     * empty slice, "Hold A to replace <item>" on a filled one, "Choose a slice" elsewhere). [hold] 0..1 = how far A has been held on a
     * filled slice (1 s in the user's sheet). THE USER'S PICK (b70, "I kind of want a mix of B and C"): the slice's own bronze rim fills gold
     * along its arc with a glowing head (the hold's clock), and the new item rides in from the hub as it fills, landing on the slice as the
     * rim completes, while the old item fades out beneath it. It rests there at 1 until A is let go (b71). A PRESS on an empty slice pins.
     */
    class RadialChoice(val icon: Bitmap?, val name: String, val quality: Int = 1, val prompt: String? = null, val hold: Float = 0f)

    /** the "+" slice (the user's pick: the BOTTOM wedge, always "+"; flick to it, let go: the wheel closes and the bag picker opens) */
    const val CR_PLUS = 4

    /** the wheel's width at s = 1 (the 1080 px bottom screen): rim 970 px, from just under the band to the screen's bottom edge */
    private const val WHEEL_D = 1110f

    /** the open motion's length; closing runs the same motion backward in [CR_CLOSE_MS]: t = CR_OPEN_MS x (1 - closing ms / CR_CLOSE_MS) */
    const val CR_OPEN_MS = 440f
    const val CR_CLOSE_MS = 220f
    /**
     * [consumableRadial]'s parts: all of it; the wheel alone (cover, wheel: Home draws it under its columns, f-radial-behind); the top
     * (the disc rising from the bar, the chooser, the name band: over everything, f-one-disc)
     */
    const val CR_ALL = 0
    const val CR_WHEEL = 1
    const val CR_TOP = 2
    /** [consumableRadialHit]: the hub (B, close) */
    const val CR_HUB = -2

    private class CrGeo(val s: Float, val k: Float, val d: Float, val hx: Float, val hy: Float, val rim: Float) {
        val hub get() = 146f * k                 // the disc's width as the hub: it fills the hub ring's inside (259 px at s 1)
        val slotR get() = 150f * k               // an item's middle from the hub's (the slice runs r 80..222 of the art)
        val icon get() = 75.4f * k               // an item's picture (the pointed one x 1.08): 155 px at the 1110 px wheel
    }

    /**
     * the wheel 1110 px (the game's 540 px art at 2.06) with its bronze rim (r 236 of the art) on [screen]'s bottom edge: as large as the
     * screen allows under the one-line name band at its top (the user, b69: "The Radial menu should be much larger, giving just enough
     * room to comfortable fit the title of the Item at the top of the screen")
     */
    private fun crGeo(screen: RectF, slot: RectF? = null): CrGeo {
        val s = screen.height() / 1080f
        // f-radial-middle: Home's middle slot (HomeView.wheelSlot): the rim fits the square, the words keep the screen's scale
        if (slot != null) {
            val k = minOf(slot.width(), slot.height()) / 472f
            return CrGeo(s, k, 540f * k, slot.centerX(), slot.centerY(), 236f * k)
        }
        val d = WHEEL_D * s; val k = d / 540f; val rim = 236f * k
        return CrGeo(s, k, d, screen.centerX(), screen.bottom - rim, rim)
    }

    /** the hub's middle when open (Home: where the stick's "middle" is, for its own drawing) */
    fun consumableRadialHub(screen: RectF): android.graphics.PointF = crGeo(screen).let { android.graphics.PointF(it.hx, it.hy) }

    /** a finger at ([x], [y]): slice 0..7 (0 = the top, clockwise), [CR_HUB] = the hub (B: close), -1 = outside the wheel */
    fun consumableRadialHit(screen: RectF, x: Float, y: Float, slot: RectF? = null): Int {
        val g = crGeo(screen, slot)
        val dx = x - g.hx; val dy = y - g.hy
        val r = kotlin.math.hypot(dx, dy)
        if (r <= 87f * g.k) return CR_HUB                    // inside the hub ring's outer edge
        if (r > g.rim) return -1
        return consumableRadialAim(dx, dy, 0f)
    }

    /**
     * THE WHEEL AT REST, ANYWHERE (the Controller room's spell radial editor: the user's "you choose, up to 8 per page, 3 pages, filled in
     * the Controller room like buttons"): the same bronze wheel as [consumableRadial] - its slices, the lit [pointed] slice (the cursor),
     * each slice's picture - centred on ([cx], [cy]) at [d] px across, with the hub's stone in the middle; no cover, no motion, no band.
     * So the editor IS the wheel the player opens. [items]: up to 8 (null = an empty slice), slice 0 at the top, clockwise. Returns the
     * slice under ([x], [y]) through [radialPreviewHit].
     */
    fun radialPreview(c: Canvas, cx: Float, cy: Float, d: Float, items: List<RadialItem?>, pointed: Int) {
        val k = d / 540f
        val g = CrGeo(d / WHEEL_D, k, d, cx, cy, 236f * k)
        crWheel(c, g, items, if (pointed in 0..7) pointed else -1, 1f, 1f, false, -1, null)
        val hub = g.hub
        crDisc(c, RectF(cx - hub / 2f, cy - hub / 2f, cx + hub / 2f, cy + hub / 2f), false, 255)
    }

    /** a finger at ([x], [y]) on a [radialPreview] at ([cx], [cy]), [d] across: slice 0..7, or -1 (the hub or outside) */
    fun radialPreviewHit(cx: Float, cy: Float, d: Float, x: Float, y: Float): Int {
        val k = d / 540f
        val r = kotlin.math.hypot(x - cx, y - cy)
        if (r <= 87f * k || r > 236f * k) return -1
        return consumableRadialAim(x - cx, y - cy, 0f)
    }

    /** a stick's deflection ([dx], [dy] with y DOWN, 0..1) -> slice 0..7 (45 deg each, 0 = up, clockwise); -1 inside [dead] */
    fun consumableRadialAim(dx: Float, dy: Float, dead: Float = 0.5f): Int {
        if (kotlin.math.hypot(dx, dy) < dead) return -1
        var a = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), (-dy).toDouble()))
        if (a < 0) a += 360.0
        return ((a + 22.5) / 45.0).toInt() % 8
    }

    /** the confirm glyph in the layout the Settings chose (the A's place): Nintendo's A, Xbox's A, PlayStation's cross */
    fun okGlyph(): String = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> "ltr_a"
        CastSettings.ButtonLayout.PLAYSTATION -> "shp_cross"
        else -> "rev_a"
    }

    /** the X (the top-left face button's place) in the layout the Settings chose: Nintendo's X, Xbox's X, PlayStation's square */
    fun xGlyph(): String = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> "ltr_x"
        CastSettings.ButtonLayout.PLAYSTATION -> "shp_square"
        else -> "rev_x"
    }

    private fun crEaseOut(x: Float): Float { val v = x.coerceIn(0f, 1f); return 1f - (1f - v) * (1f - v) * (1f - v) }
    private fun crEaseInOut(x: Float): Float { val v = x.coerceIn(0f, 1f); return v * v * (3f - 2f * v) }

    /**
     * THE CONSUMABLES RADIAL over the whole bottom screen. [screen] = the room AND the bar (the radial view lies over both: its
     * wheel covers the bar's middle); [disc] / [discSize] = the bar's Home disc as drawn (Strip.place(Room.HOME), mapped into this
     * canvas: middle and width, 140 px). Set Strip.homeLifted = true while this shows (the radial draws the disc that rises) and
     * false once a close has run back to t = 0. [items] = this page's eight slices (index = slice, 0 = the top, clockwise; null or
     * absent = an empty slice), in the user's order: food & drink, potions & elixirs / flasks, bandages, usable quest / other.
     * [pointed] = the slice the stick points at (-1 none): its wedge lights, the A glyph sits on its item, its name + count stand
     * in the band above. [page] / [pages]: the dots under the band (LB / RB turn the page; none for one page). [t] = ms along the
     * open motion (0 .. CR_OPEN_MS; past it = open; closing = the same t run backward over CR_CLOSE_MS).
     *   0 ..  60  the disc sinks (0.94) and a blue halo flares behind it
     *  60 .. 280  the WHOLE disc rises from the bar to the hub (ease in-out), growing to 1.12 x the hub; 260 .. 420 it settles to the hub
     *  60 .. 320  the wheel EMERGES FROM THE STONE behind it: its middle rides with the disc, it grows from the stone's width
     *             (discSize / 960) to 1 (ease out); the items pop in clockwise from 150
     * 120 .. 300  the swirl's light turns red; 260 .. 420 the B (the layout's back glyph) fades in on the hub: B closes
     * 320 .. 440  the lit wedge, the A, the name band and the page dots
     *   0 .. 180  the room dims to 35 %, the bar to 55 %
     * [glyphs] false (the user after the device test, 2026-10-06: "remove the button glyphs"): no B on the hub and no A on the pointed
     * item; the hub keeps its red swirl (the user's earlier "the swirling as red"), the lit wedge still marks the pointed slice.
     * PINNING (the user's pin sheet, signed off 2026-10-06; wow-companion-design\home\consumables\pin\): [plusSlot] = the "+" slice
     * (CR_PLUS; -1 = none): the game's gold plus there, lit with a gold glow when pointed at (band "Pin an item / Let go to choose one
     * from your bags"), its item (if any) never drawn. RadialItem.pinned / .ranOut mark pins. [choose] non-null = THE SLICE CHOOSER:
     * the picked item floats over the hub, the "+" dims, the band says the item and what A does; RadialChoice.hold fills the pointed
     * (filled) slice.
     * [hubDown] 0..1 = THE HUB'S PRESS (the user, 2026-10-06: "Pushing B on that setting should also make the middle of that radial menu
     * play the back button push animation feedback"): the bar's own Home press, at the hub's size: the disc (and its B) to 0.92 around a
     * point 2 px lower, a soft shadow gathering at its lower edge. The caller runs it as the bar does: in over CR_PRESS_IN_MS (linear),
     * out over CR_PRESS_OUT_MS on the bar's curve (PathInterpolator 0.4, 0, 0.2, 1), then starts the close.
     * Returns true while the motion wants another frame (t < CR_OPEN_MS); open, the picture stands still.
     */
    fun consumableRadial(c: Canvas, screen: RectF, disc: android.graphics.PointF, discSize: Float, items: List<RadialItem?>,
                         pointed: Int, page: Int = 0, pages: Int = 1, t: Float = CR_OPEN_MS, glyphs: Boolean = true,
                         plusSlot: Int = -1, choose: RadialChoice? = null, hubDown: Float = 0f, slot: RectF? = null,
                         part: Int = CR_ALL): Boolean {
        val g = crGeo(screen, slot)
        val tt = t.coerceIn(0f, CR_OPEN_MS)
        // the cover: the room 0.65, the bar 0.45 (in Home's middle slot none: the columns part for it, as the class wheel's did)
        val dim = if (slot != null) 0f else crEaseOut(tt / 180f)
        val barTop = screen.bottom - Strip.HEIGHT * g.s
        pFlat.style = Paint.Style.FILL
        pFlat.color = Color.argb((255 * 0.65f * dim).toInt(), 0, 0, 0)
        c.drawRect(screen.left, screen.top, screen.right, barTop, pFlat)
        pFlat.color = Color.argb((255 * 0.45f * dim).toInt(), 0, 0, 0)
        c.drawRect(screen.left, barTop, screen.right, screen.bottom, pFlat)
        // an EMPTY slice lights too when pointed at (the user after the device test, 2026-10-06: "even if its blank I want it to highlight")
        val pt = if (pointed in 0..7) pointed else -1
        val selK = crEaseOut((tt - 320f) / 120f)
        run {
        // the disc: from the bar to the hub
        val cx: Float; val cy: Float; var size: Float; val glow: Float
        if (tt < 60f) {
            cx = disc.x; cy = disc.y; size = discSize * (1f - 0.06f * crEaseOut(tt / 40f)); glow = 0.9f * minOf(1f, tt / 30f)
        } else {
            val rise = crEaseInOut((tt - 60f) / 220f)
            cx = disc.x + (g.hx - disc.x) * rise; cy = disc.y + (g.hy - disc.y) * rise
            size = discSize + (g.hub * 1.12f - discSize) * crEaseOut((tt - 60f) / 220f)
            size += (g.hub - size) * crEaseOut((tt - 260f) / 160f)
            glow = 0.8f * maxOf(0.2f, 1f - (tt - 60f) / 340f)
        }
        // THE WHEEL EMERGES FROM THE STONE (the user, b69: "when opening the radial, it grows out from the center of the screen, instead
        // of growing out from the spiral stone (it should also retract into the stone as well"): its middle rides with the disc along the
        // rise and it grows from the stone's own width (discSize / the wheel's) to 1; the close runs t backward, so it goes back in
        val openK = crEaseOut((tt - 60f) / 260f)
        if (openK > 0f && part != CR_TOP) {
            // in Home's middle slot it SLIDES UP whole, riding with the disc from the bar (f-radial-middle), not growing
            val s0 = if (slot != null) 1f else (discSize / g.d).coerceIn(0.05f, 1f)
            val sc = s0 + (1f - s0) * openK
            val save = c.save()
            c.translate(cx, cy); c.scale(sc, sc); c.translate(-g.hx, -g.hy)
            val half = g.d / 2f + 4f
            val l = c.saveLayerAlpha(RectF(g.hx - half, g.hy - half, g.hx + half, g.hy + half), (255 * minOf(1f, openK * 1.8f)).toInt())
            crWheel(c, g, items, pt, selK, ((tt - 150f) / 190f).coerceIn(0f, 1f), glyphs, plusSlot, choose)
            c.restoreToCount(l)
            c.restoreToCount(save)
        }
        val redK = crEaseInOut((tt - 120f) / 180f)
        // the disc (the spiral) and the chooser: on TOP (f-one-disc: it rises over Home's columns from the bar, the one disc on screen)
        if (part != CR_WHEEL) {
        if (glow > 0f) {
            val hr = size * 0.85f
            val (r0, g0, b0) = if (redK > 0.5f) Triple(230, 50, 40) else Triple(40, 110, 230)
            pCrHalo.shader = RadialGradient(cx, cy, hr, intArrayOf(Color.argb(255, (r0 * glow).toInt(), (g0 * glow).toInt(), (b0 * glow).toInt()),
                Color.argb(255, (r0 * glow * 0.3f).toInt(), (g0 * glow * 0.3f).toInt(), (b0 * glow * 0.3f).toInt()), Color.argb(255, 0, 0, 0)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, hr, pCrHalo)
        }
        val box = RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        // the press (the bar's Home press at the hub's size): the disc and its B sink to 0.92, 2 px down
        val push = hubDown.coerceIn(0f, 1f)
        val pressSave = c.save()
        if (push > 0f) { val sc = 1f - 0.08f * push; c.scale(sc, sc, cx, cy + 2f * g.s * push) }
        crDisc(c, box, false, 255)
        if (redK > 0f) crDisc(c, box, true, (255 * redK).toInt())
        val bK = crEaseOut((tt - 260f) / 160f)
        if (glyphs && bK > 0f && choose == null) pad(c, backGlyph(), cx, cy, size * 0.30f, (255 * bK).toInt())
        if (push > 0f) {
            val r = size / 2f
            pCrShade.shader = RadialGradient(cx, cy - r * 0.35f, r * 1.25f, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT,
                Color.argb((150 * push).toInt(), 0, 0, 0)), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, pCrShade)
        }
        c.restoreToCount(pressSave)
        // the chooser: the picked item floats over the hub on a gold glow, in its quality's border
        if (choose != null && selK > 0f) {
            var fs = 84.4f * g.k
            var fx = g.hx; var fy = g.hy
            val flyK = if (pt >= 0 && pt != plusSlot && items.getOrNull(pt) != null) crEaseInOut(choose.hold.coerceIn(0f, 1f)) else 0f
            if (flyK > 0f) {
                // the new item leaves the hub for the slice as A is held, landing on it as the rim's fill completes
                val a = Math.toRadians(-90.0 + 45.0 * pt)
                val sx = g.hx + g.slotR * Math.cos(a).toFloat(); val sy = g.hy + g.slotR * Math.sin(a).toFloat()
                fx = g.hx + (sx - g.hx) * flyK; fy = g.hy + (sy - g.hy) * flyK
                fs += (g.icon * 1.08f - fs) * flyK
            }
            val l = c.saveLayerAlpha(RectF(fx - fs, fy - fs, fx + fs, fy + fs), (255 * selK).toInt())
            pCrGlow.shader = RadialGradient(fx, fy, fs, intArrayOf(Color.argb(170, 255, 200, 70), Color.argb(0, 255, 200, 70)), null,
                Shader.TileMode.CLAMP)
            c.drawCircle(fx, fy, fs, pCrGlow)
            slot(c, choose.icon, fx - fs / 2f, fy - fs / 2f, fs, Theme.quality(choose.quality))
            c.restoreToCount(l)
        }
        }
        }
        // the band above the wheel: what the pointed slice holds (or what A does there); the page dots under it
        if (part != CR_WHEEL && selK > 0f) {
            val it = if (pt >= 0) items.getOrNull(pt) else null
            when {
                choose != null -> crBand(c, g, choose.name, Theme.quality(choose.quality), choose.prompt ?: when {
                    pt < 0 || pt == plusSlot -> "Choose a slice"
                    it == null -> "Press ${Glyphs.name("a")} to pin it here"
                    // b71: the replace lands when A is LET GO after the full hold; the fill rests at 1 until then
                    choose.hold >= 1f -> "Let go to replace ${it.name}"
                    else -> "Hold ${Glyphs.name("a")} to replace ${it.name}"
                }, page, pages, selK)
                // test28 (the user: "all that it needs to say at the top is pin", the wheel band only): just "Pin", no second line
                pt >= 0 && pt == plusSlot -> crBand(c, g, "Pin", Theme.gold, null, page, pages, selK)
                it != null && it.ranOut -> crBand(c, g, it.name, Color.rgb(150, 150, 150),
                    "None left: it keeps its place until you have one again", page, pages, selK)
                // the item's name alone (the user, b69: "the sub text on Items saying how many are in bags is redundant"): the count
                // stays on the item's own icon
                it != null -> crBand(c, g, it.name, Theme.quality(it.quality), null, page, pages, selK)
                pt >= 0 -> crBand(c, g, "Empty", Color.rgb(150, 140, 120), null, page, pages, selK)
                else -> crBand(c, g, null, 0, null, page, pages, selK)
            }
        }
        return t < CR_OPEN_MS
    }

    private val pCrHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val pCrAdd = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val pCrMask = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val pCrEdge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pCrGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val pCrGrey = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pCrShade = Paint(Paint.ANTI_ALIAS_FLAG)

    /** the hub's press, as the bar's Home press (Strip.pushNow): in over 70 ms, out over 160 ms */
    const val CR_PRESS_IN_MS = 70f
    const val CR_PRESS_OUT_MS = 160f

    /** a picture's colour taken down to [sat] (0 grey .. 1 as it is) and its light to [bright] */
    private fun crGreyFilter(sat: Float, bright: Float): ColorMatrixColorFilter {
        val m = ColorMatrix().apply { setSaturation(sat) }
        m.postConcat(ColorMatrix().apply { setScale(bright, bright, bright, 1f) })
        return ColorMatrixColorFilter(m)
    }

    /** the Home button as the bar draws it, its light at full: BASE, FLARE added, kept to the button's shape (in a layer) */
    private fun crDisc(c: Canvas, box: RectF, red: Boolean, alpha: Int) {
        val base = tex(if (red) "home_live_red_base" else "home_live_base")
        val flare = tex(if (red) "home_live_red_flare" else "home_live_flare")
        if (base == null || flare == null) {
            pFlat.style = Paint.Style.FILL; pFlat.color = if (red) Color.rgb(70, 22, 18) else Color.rgb(40, 34, 26)
            pFlat.alpha = alpha; c.drawCircle(box.centerX(), box.centerY(), box.width() / 2f, pFlat); pFlat.alpha = 255
            return
        }
        val l = c.saveLayerAlpha(box, alpha)
        c.drawBitmap(base, null, box, pBmp)
        c.drawBitmap(flare, null, box, pCrAdd)
        c.drawBitmap(base, null, box, pCrMask)
        c.restoreToCount(l)
    }

    /** the bar Home button's press flash over a disc: its gold pieces by [k], the gold light added again (x 2.3 at the peak) */
    private fun crDiscGold(c: Canvas, box: RectF, k: Float) {
        val base = tex("home_live_gold_base"); val flare = tex("home_live_gold_flare")
        val l = c.saveLayerAlpha(box, (255 * k).toInt())
        if (base != null && flare != null) {
            c.drawBitmap(base, null, box, pBmp)
            c.drawBitmap(flare, null, box, pCrAdd)
            c.drawBitmap(flare, null, box, pCrAdd)
            c.drawBitmap(base, null, box, pCrMask)
        } else {
            pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(140, 255, 200, 70)
            c.drawCircle(box.centerX(), box.centerY(), box.width() / 2f, pFlat)
        }
        c.restoreToCount(l)
    }

    /** the wheel, its lit wedge, the items (their counts, the A on the pointed one), centred on the hub at scale 1 */
    private fun crWheel(c: Canvas, g: CrGeo, items: List<RadialItem?>, pt: Int, selK: Float, reveal: Float, glyphs: Boolean = true,
                        plusSlot: Int = -1, choose: RadialChoice? = null) {
        // a dark ground inside the rim: the game's slices are see-through, and Home's words would read as clutter through them
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(225, 6, 6, 8)
        c.drawCircle(g.hx, g.hy, 228f * g.k, pFlat)
        val bg = tex("rwheel_bg")
        if (bg != null) c.drawBitmap(bg, null, RectF(g.hx - g.d / 2f, g.hy - g.d / 2f, g.hx + g.d / 2f, g.hy - g.d / 2f + g.d * 541f / 540f), pBmp)
        else {
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 10f * g.s; pFlat.color = Theme.bronze
            c.drawCircle(g.hx, g.hy, 229f * g.k, pFlat); c.drawCircle(g.hx, g.hy, 69f * g.k, pFlat)
            pFlat.strokeWidth = 5f * g.s
            for (i in 0 until 8) {
                val a = Math.toRadians(-90.0 + 22.5 + 45.0 * i)
                c.drawLine(g.hx + 78f * g.k * Math.cos(a).toFloat(), g.hy + 78f * g.k * Math.sin(a).toFloat(),
                    g.hx + 224f * g.k * Math.cos(a).toFloat(), g.hy + 224f * g.k * Math.sin(a).toFloat(), pFlat)
            }
        }
        if (pt >= 0 && selK > 0f) {
            // the game's lit wedge, drawn as the BOTTOM slice (narrow end up): turned to the slice, its outer edge on the rim
            val on = tex("rwheel_on")
            val save = c.save()
            c.rotate(45f * pt - 180f, g.hx, g.hy)
            val w = 169f * g.k; val h = 165f * g.k; val my = g.hy + (230f - 82.5f) * g.k
            if (on != null) { pBmp.alpha = (255 * selK).toInt(); c.drawBitmap(on, null, RectF(g.hx - w / 2f, my - h / 2f, g.hx + w / 2f, my + h / 2f), pBmp); pBmp.alpha = 255 }
            else {
                pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 6f * g.s; pFlat.color = Theme.gold; pFlat.alpha = (255 * selK).toInt()
                c.drawArc(RectF(g.hx - 222f * g.k, g.hy - 222f * g.k, g.hx + 222f * g.k, g.hy + 222f * g.k), 67.5f, 45f, false, pFlat)
                pFlat.alpha = 255
            }
            c.restoreToCount(save)
        }
        val hold = if (choose != null && pt >= 0 && pt != plusSlot && items.getOrNull(pt) != null) choose.hold.coerceIn(0f, 1f) else 0f
        if (plusSlot in 0..7) {
            // the "+": the game's gold plus (character select's), a soft gold glow when pointed at; dimmed while choosing a slice
            val a = Math.toRadians(-90.0 + 45.0 * plusSlot)
            val px = g.hx + g.slotR * Math.cos(a).toFloat(); val py = g.hy + g.slotR * Math.sin(a).toFloat()
            val lit = pt == plusSlot && choose == null
            if (lit && selK > 0f) {
                pCrGlow.shader = RadialGradient(px, py, 61.9f * g.k, intArrayOf(Color.argb((150 * selK).toInt(), 255, 200, 60), Color.argb(0, 255, 200, 60)),
                    null, Shader.TileMode.CLAMP)
                c.drawCircle(px, py, 61.9f * g.k, pCrGlow)
            }
            val ps = (if (lit) 84.4f + 16.9f * selK else 84.4f) * g.k
            tex("rwheel_plus")?.let {
                pBmp.alpha = when { choose != null -> 90; lit -> 190 + (65 * selK).toInt(); else -> 190 }
                c.drawBitmap(it, null, RectF(px - ps / 2f, py - ps / 2f, px + ps / 2f, py + ps / 2f), pBmp)
                pBmp.alpha = 255
            } ?: run {
                pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 6.8f * g.k; pFlat.color = Theme.gold
                c.drawLine(px - 19f * g.k, py, px + 19f * g.k, py, pFlat); c.drawLine(px, py - 19f * g.k, px, py + 19f * g.k, pFlat)
            }
        }
        val counts = numberPaint(27f * g.k, Color.WHITE, shadow = false).apply { textAlign = Paint.Align.RIGHT }
        for (i in 0 until 8) {
            if (i == plusSlot) continue
            val it = items.getOrNull(i) ?: continue
            val k = if (reveal < 1f) crEaseOut((reveal * 8f - i * 0.6f) / 1.6f) else 1f
            if (k <= 0.01f) continue
            val a = Math.toRadians(-90.0 + 45.0 * i)
            val x = g.hx + g.slotR * Math.cos(a).toFloat(); val y = g.hy + g.slotR * Math.sin(a).toFloat()
            val on = i == pt
            val sz = g.icon * (0.6f + 0.4f * k) * (if (on) 1f + 0.08f * selK else 1f)
            val r = RectF(x - sz / 2f, y - sz / 2f, x + sz / 2f, y + sz / 2f)
            // the item a hold is replacing fades out whole (icon, count, pin) under the new one riding in
            val fade = if (on && hold > 0f) 1f - crEaseInOut(hold) else 1f
            val al = (255 * k * fade).toInt()
            // the game's square icon: a thin black edge, corners rounded; the ones not pointed at a little darker
            pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb((230 * k * fade).toInt(), 0, 0, 0)
            c.drawRoundRect(RectF(r.left - 2f, r.top - 2f, r.right + 2f, r.bottom + 2f), 10f * g.s, 10f * g.s, pFlat)
            it.icon?.let { b ->
                val save = c.save()
                c.clipPath(android.graphics.Path().apply { addRoundRect(r, 8f * g.s, 8f * g.s, android.graphics.Path.Direction.CW) })
                // a pin that ran out: greyed and darkened
                if (it.ranOut) { pCrGrey.colorFilter = crGreyFilter(0f, 0.55f); pCrGrey.alpha = al; c.drawBitmap(b, null, r, pCrGrey) }
                else { pBmp.alpha = al; c.drawBitmap(b, null, r, pBmp); pBmp.alpha = 255; if (it.noMana) manaTint(c, r) }
                val shade = if (on) (70 * (1f - selK)).toInt() else 70
                if (shade > 0) { pFlat.color = Color.argb((shade * k * fade).toInt(), 0, 0, 0); c.drawRect(r, pFlat) }
                c.restoreToCount(save)
            }
            if (k > 0.9f && it.count >= 0) {
                // the stack's count, the game's way: white, bottom right, a black edge (count -1 = no count: a spell, the spell radial)
                val bx = r.right - 4f; val by = r.bottom - 2f - counts.descent()
                counts.style = Paint.Style.STROKE; counts.strokeWidth = 5f * g.k; counts.strokeJoin = Paint.Join.ROUND
                counts.color = Color.BLACK; counts.alpha = (255 * fade).toInt()
                c.drawText(it.count.toString(), bx, by, counts)
                counts.style = Paint.Style.FILL; counts.color = Color.WHITE; counts.alpha = (255 * fade).toInt()
                c.drawText(it.count.toString(), bx, by, counts)
            }
            if (it.pinned || it.ranOut) {
                // the pin: the Friends list's gold pin over the icon's top-right corner (grey when its item ran out)
                tex(if (it.ranOut) "rwheel_pin_off" else "rwheel_pin")?.let { pin ->
                    val ps = 28.1f * g.k; val px = r.right - 7.9f * g.k; val py = r.top + 6.8f * g.k
                    pBmp.alpha = al; c.drawBitmap(pin, null, RectF(px - ps / 2f, py - ps / 2f, px + ps / 2f, py + ps / 2f), pBmp); pBmp.alpha = 255
                }
            }
            if (glyphs && on && selK > 0f) {
                // the A on the pointed item: its top-left corner, half over the icon (as the game marks a binding on a button)
                val gs = 41.6f * g.k
                pad(c, okGlyph().takeIf { n -> tex("pad_$n") != null } ?: "rev_a", r.left - 0.35f * gs + gs / 2f, r.top - 0.35f * gs + gs / 2f, gs, (255 * selK).toInt())
            }
        }
        if (hold > 0f && selK > 0f) {
            // THE HOLD'S CLOCK: the slice's own bronze rim fills gold along its arc, left edge to right, a bright head at its end
            val rr = 229f * g.k
            val o = RectF(g.hx - rr, g.hy - rr, g.hx + rr, g.hy + rr)
            val start = -90f + 45f * pt - 21f
            pFlat.style = Paint.Style.STROKE; pFlat.strokeCap = Paint.Cap.ROUND
            pFlat.strokeWidth = 16f * g.k; pFlat.color = Color.argb(70, 255, 190, 60); c.drawArc(o, start, 42f * hold, false, pFlat)
            pFlat.strokeWidth = 6.5f * g.k; pFlat.color = Theme.gold; c.drawArc(o, start, 42f * hold, false, pFlat)
            pFlat.strokeCap = Paint.Cap.BUTT; pFlat.style = Paint.Style.FILL
            val ea = Math.toRadians((start + 42f * hold).toDouble())
            crHead(c, g.hx + rr * Math.cos(ea).toFloat(), g.hy + rr * Math.sin(ea).toFloat(), 16f * g.k)
        }
    }

    /** a soft gold glow at a filling line's head */
    private fun crHead(c: Canvas, x: Float, y: Float, r: Float) {
        pCrGlow.shader = RadialGradient(x, y, r, intArrayOf(Color.argb(230, 255, 235, 150), Color.argb(0, 255, 200, 70)), null, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, pCrGlow)
    }

    /**
     * the band over the wheel: [line1] (its colour [colour1]) over [line2]; [line2] null = [line1] alone in the band's middle (a pointed
     * empty slice's dim "Empty", so the band holds still while the stick sweeps); [line1] null = no band; the page dots under it
     */
    private fun crBand(c: Canvas, g: CrGeo, line1: String?, colour1: Int, line2: String?, page: Int, pages: Int, k: Float) {
        // THE PAUSE MENU'S HEADER LOOK (the user on 1.0.9-test18: "The name of the thing that I am selecting should be reduced in size down
        // to look similar to the main menu with L and R to indicate the other pages instead of them way off to the side"): the slim band
        // art, the name in the game's font at the "Main Menu" size, LB / RB right beside it and the page dots under it (more than one
        // page); an instruction (the "+", the chooser, a pin that ran out) a small line under the band
        val a = (255 * k).toInt()
        val s = g.s
        val bh = P_TOP_H * s
        val ty = maxOf(6f * s + bh / 2f, g.hy - g.rim - bh / 2f - 6f * s)
        val size = 32f * s
        val maxName = 420f * s
        val nameW = if (line1 != null) minOf(bodyPaint(size).measureText(line1), maxName) else 0f
        val paging = pages >= 2
        val half = maxOf(nameW / 2f + 52f * s, 150f * s)                 // the glyphs' distance: beside the name, never closer than the pause's
        tex("rwheel_band")?.let {
            val bw = maxOf(520f * s, 2f * half + 90f * s)
            pBmp.alpha = a; c.drawBitmap(it, null, RectF(g.hx - bw / 2f, ty - bh / 2f, g.hx + bw / 2f, ty + bh / 2f), pBmp); pBmp.alpha = 255
        }
        if (line1 != null) gameWords(c, line1, g.hx, ty - (if (paging) 8f else 0f) * s, size, colour1, a, maxName)
        if (paging) {
            val ds = 12f * s; val dy = ty + 20f * s
            for (n in 0 until pages) {
                val x = g.hx + (n - (pages - 1) / 2f) * (ds + 6f * s)
                val dot = tex(if (n == page) "rpage_on" else "rpage_off")
                if (dot != null) { pBmp.alpha = a; c.drawBitmap(dot, null, RectF(x - ds / 2f, dy - ds / 2f, x + ds / 2f, dy + ds / 2f), pBmp); pBmp.alpha = 255 }
                else { pFlat.style = Paint.Style.FILL; pFlat.color = if (n == page) Color.argb(a, 255, 209, 0) else Color.argb(a, 70, 64, 52); c.drawCircle(x, dy, ds / 2f, pFlat) }
            }
            val gs = 46f * s
            pad(c, shoulderGlyph(false), g.hx - half, ty, gs, a)
            pad(c, shoulderGlyph(true), g.hx + half, ty, gs, a)
        }
        if (line2 != null) crWords(c, line2, g.hx, ty + bh / 2f + 16f * s, 22f * s, Color.rgb(200, 200, 200), 2f * s, a, 900f * s)
    }

    /** words with a dark edge, centred on ([cx], [cy]), shrunk to [maxW] */
    private fun crWords(c: Canvas, words: String, cx: Float, cy: Float, size: Float, colour: Int, edge: Float, alpha: Int, maxW: Float) {
        val p = bodyPaint(size, colour, shadow = false).apply { textAlign = Paint.Align.CENTER }
        while (p.textSize > size * 0.6f && p.measureText(words) > maxW) p.textSize -= 1f
        val y = cy - (p.ascent() + p.descent()) / 2f
        p.style = Paint.Style.STROKE; p.strokeWidth = edge * 2f; p.strokeJoin = Paint.Join.ROUND; p.color = Color.rgb(10, 8, 6); p.alpha = alpha
        c.drawText(words, cx, y, p)
        p.style = Paint.Style.FILL; p.color = colour; p.alpha = alpha
        c.drawText(words, cx, y, p)
    }

    // ---------------------------------------------------------------- the PAUSE RADIAL (Home's Start)
    //
    // The user, 2026-10-08: "pressing the start button and an animation of all of the home panels sliding out over the way to make space
    // for the circle moving up to the middle of the screen and opening basically the exact radial controller menu that wow uses,
    // including the extra pages and d pad options for siting/standing and emotes". Picks: "Home disc + release" (the disc is the hub,
    // red swirl + B; the stick lights the wedge and letting go picks; no A), the roomless slices greyed with "coming later".
    // It is the game's own GamepadRadial (Blizzard_Gamepad/UI/Radials/GamepadRadial.xml + .lua): pictures + numbers in
    // wow-companion-design\home\pause_radial\ (PAUSE_RADIAL_SPEC.md, SHEET_pause_radial.png, CLIP_open.gif). Kit: rwheel_bg / _on /
    // _off / _band / _ctx, rpage_on / _off, ropt_on, rdpad, ricon_* and their _off art, the bar's home_live_*.
    // SLICES: the callers count the KIT's way (0 = the top, clockwise, as the consumables wheel); the game counts 0 = east,
    // counter-clockwise: game = (2 - kit) mod 8 (the same formula turns it back).

    /**
     * one slice: [key] = the game's key (GamepadRadial.lua segmentOptions: lfd legacy store guild social buffs tracking collections /
     * bags professions character talents map chat menu spellbook / time calendar pvp / emote), which names its kit picture; [label] = the
     * game's word; [enabled] false = the game's unavailable look (its grey wedge, the picture's _off art, a grey label); [note] = small
     * grey words on the label's outer side (optional; the "Coming later" of a greyed slice can instead be the red line, pauseRadial's line)
     */
    class PauseSlice(val key: String, val label: String, val enabled: Boolean = true, val note: String? = null)

    /** the disc: its middle and width, how red its swirl is (0..1), how far its B has come in (0..1), its halo (0..1) */
    class PauseDisc(val cx: Float, val cy: Float, val size: Float, val red: Float, val b: Float, val glow: Float)

    /** Home's parts for [pauseOut]: the controller band, the chat column, the map (with the quest card and the auras over it), the bar */
    const val PAUSE_TOP = 0
    const val PAUSE_LEFT = 1
    const val PAUSE_MAP = 2
    const val PAUSE_BAR = 3

    /** the context band's D-pad options ([pauseRadial]'s heldOption, [pauseOptionHit]): up (Emotes), down (Sit / Stand) */
    const val PAUSE_OPT_UP = 0
    const val PAUSE_OPT_DOWN = 2
    /** D-pad Left's / Right's options (the Track Pad / the Keyboard), their words left / right of the D-pad */
    const val PAUSE_OPT_LEFT = 3
    const val PAUSE_OPT_RIGHT = 1

    /** the open motion's length; closing runs it backward over [PAUSE_CLOSE_MS]: t = [pauseCloseT] */
    const val PAUSE_OPEN_MS = 600f
    const val PAUSE_CLOSE_MS = 380f
    /** [pauseRadialHit]: the hub (a tap = B: back from Emotes, else close) */
    const val PAUSE_HUB = -2

    /** the game's label colours (NORMAL_FONT_COLOR / DISABLED_FONT_COLOR) and its error line's red (UIErrorsFrame: 1, 0.1, 0.1) */
    private val PAUSE_GOLD = Color.rgb(255, 209, 0)
    private val PAUSE_GREY = Color.rgb(128, 128, 128)
    private val PAUSE_RED = Color.rgb(255, 26, 26)

    private fun pauseFlip(i: Int) = ((2 - i) % 8 + 8) % 8

    /** the slice's kit picture */
    private fun pauseIcon(key: String) = "ricon_" + when (key) {
        "lfd" -> "groupfinder"; "store" -> "shop"; "map" -> "quests"; "menu" -> "gamemenu"; "time" -> "clock"; else -> key
    }

    /** t for the open motion, [ms] after a close began (PAUSE_OPEN_MS .. 0) */
    fun pauseCloseT(ms: Float): Float = PAUSE_OPEN_MS * (1f - (ms / PAUSE_CLOSE_MS).coerceIn(0f, 1f))

    /** [s] = screen px per game unit (1.38 on the 1240 x 1080 bottom screen), the wheel's middle at (620, 520) of it */
    private class PauseGeo(val k: Float, val s: Float, val cx: Float, val cy: Float, val topY: Float = 0f, val padY: Float = 0f)

    private fun pauseGeo(screen: RectF): PauseGeo {
        val k = minOf(screen.width() / 1240f, screen.height() / 1080f)
        if (pauseBarsBottom > 0f) {
            // f-pause-bars (1.0.9, the user: "I dont think I want the controller top part to slide off screen. I want to move the "main
            // menu" text and buttons down to the bottom half of it and then the Dpad part on the bottom is its own full bar that slides up
            // to cover the rooms"): Home's controller stays; the title row in its lower half; the D-pad bar over the rooms' bar at the
            // bottom; the wheel centred between them
            val bar = pauseBarH(screen)
            val dpadTop = screen.bottom - bar
            // the user on test17: "very slightly lowered down so that its not overlapping buttons and there is space between them": the
            // row just above the controller's bottom edge (2 px in), clear of its buttons, still above the wheel
            val topY = screen.top + pauseBarsBottom - (P_TOP_H / 2f + 2f) * k
            val above = screen.top + pauseBarsBottom
            // the user: "make the full radial wheel about 20% bigger in the pause screen to fill the blank space": up to 1.2 x the old
            // 1.3, as large as fits between the controller and the D-pad bar (the rim 236 units, a 6 px margin each side)
            val s = minOf(1.3f * 1.2f * k, (dpadTop - above - 12f * k) / 472f)
            // the user on test18: "The options wheel needs to be moved up very slightly": 8 px up (the title band's art ends ~11 px over
            // its box, so the rim stays clear of it)
            return PauseGeo(k, s, screen.centerX(), (above + dpadTop) / 2f - 8f * k, topY, screen.bottom - bar / 2f)
        }
        run {
            // f-pause-art (the user: "the top main menu with L/R tabs and bottom dpad sections give up WAY too much screen real
            // estate"; "inconsistent size with the rest of our UI"): one slim top row, the wheel, the D-pad with its words hugging it,
            // the stack centred on the screen
            val s = 1.3f * k
            // the wheel and the D-pad centred in the space under the top row
            val below = screen.top + (2f * P_TOP_Y + P_GAP) * k
            val total = (2f * 270f * 1.3f + P_GAP + P_PAD_H) * k
            val y0 = below + (screen.bottom - below - total) / 2f - (P_TOP_H + P_GAP) * k
            // 1.0.9 (the user: "the Text for main menu needs to be larger and pushed up more to make space for the disk"): the top row
            // at the screen's top, whatever the centring
            val topY = screen.top + P_TOP_Y * k
            val cy = y0 + (P_TOP_H + P_GAP) * k + 270f * s
            val padY = cy + 270f * s + (P_GAP + P_PAD_H / 2f) * k
            return PauseGeo(k, s, screen.centerX(), cy, topY, padY)
        }
    }
    /**
     * f-pause-bars: the bottom of Home's controller (its bars) in the pause view's px while the menu opens over Home (the host sets it on
     * every open); 0 = another room: the slim top row at the screen's top as before. Either way the D-pad is its own bar at the bottom
     */
    @JvmField var pauseBarsBottom = 0f
    /**
     * the rooms' bar's top in the pause view's px, MEASURED by the host on each open (the user on test17: "When using the radial the rooms
     * bar clips visually in the background": the D-pad bar was Strip.HEIGHT tall, the bar on the device is not); 0 = not known
     */
    @JvmField var pauseStripTop = 0f
    /** the D-pad bar's height: down from the rooms' bar's top (2 px over it, so no edge of it peeks), else Strip.HEIGHT */
    private fun pauseBarH(screen: RectF): Float =
        if (pauseStripTop > screen.top && pauseStripTop < screen.bottom) screen.bottom - pauseStripTop + 2f else Strip.HEIGHT * screen.height() / 1080f

    /** the Home disc in the wheel's middle, game units (the user: "the disk needs to be larger"): the centre hole, the inner ring clear */
    private const val PAUSE_DISC_U = 168f
    private const val P_TOP_Y = 40f            // the top row's middle, from the screen's top
    private const val P_TOP_H = 54f; private const val P_GAP = 10f; private const val P_PAD_H = 116f
    /** the compact D-pad's words: their distance from the D-pad's middle (frame px) and size */
    private const val P_ARM = 34f; private const val P_WORD = 23f

    private val pauseExit = android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f)          // FastOutLinearIn
    private fun pSpan(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)

    /**
     * a PART's move at [t] ms along the open motion (run t backward to close): FloatArray(dx, dy, alpha) to add to where it stands; each
     * goes fully off its own edge: [PAUSE_TOP] up 264 px over 0..220 ms, [PAUSE_LEFT] left 640 and [PAUSE_MAP] right 640 over 20..240,
     * [PAUSE_BAR] down 170 over 40..260; FastOutLinearIn (backward, they come back in decelerating). Alpha stays 1: they slide, they do
     * not fade. The px are the 1240 x 1080 bottom screen's: pass [screen] if the canvas differs. The bar goes WITHOUT its disc
     * (strip.homeLifted): [pauseRadial] draws the disc from t = 0.
     */
    fun pauseOut(part: Int, t: Float, screen: RectF? = null): FloatArray {
        val kx = (screen?.width() ?: 1240f) / 1240f; val ky = (screen?.height() ?: 1080f) / 1080f
        return when (part) {
            // 264: the band's pieces and their shadows reach ~240 (a 2 px sliver of them stayed at the top at 232: Home's Thor shot)
            PAUSE_TOP -> floatArrayOf(0f, -264f * ky * pauseExit.getInterpolation(pSpan(t, 0f, 220f)), 1f)
            PAUSE_LEFT -> floatArrayOf(-640f * kx * pauseExit.getInterpolation(pSpan(t, 20f, 240f)), 0f, 1f)
            PAUSE_MAP -> floatArrayOf(640f * kx * pauseExit.getInterpolation(pSpan(t, 20f, 240f)), 0f, 1f)
            PAUSE_BAR -> floatArrayOf(0f, 170f * ky * pauseExit.getInterpolation(pSpan(t, 40f, 260f)), 1f)
            else -> floatArrayOf(0f, 0f, 1f)
        }
    }

    /**
     * the DISC at [t]: [disc] / [discSize] = the bar's Home disc as drawn (its middle, its width). It lights where it stands 0..60 ms
     * (a blue halo), rises to the wheel's middle 60..380 (ease in-out) growing to the hub (the hub ring's inside: 146 units), its swirl
     * turns red 380..460, its B comes in 420..520. [pauseRadial] draws it; this is for the caller's own bookkeeping
     */
    fun pauseDisc(screen: RectF, disc: android.graphics.PointF, discSize: Float, t: Float): PauseDisc {
        val g = pauseGeo(screen)
        val m = crEaseInOut(pSpan(t, 60f, 380f))
        return PauseDisc(disc.x + (g.cx - disc.x) * m, disc.y + (g.cy - disc.y) * m, discSize + (PAUSE_DISC_U * g.s - discSize) * m,
            crEaseInOut(pSpan(t, 380f, 460f)), crEaseOut(pSpan(t, 420f, 520f)), 0.55f * pSpan(t, 0f, 60f) * (1f - pSpan(t, 260f, 520f)))
    }

    /**
     * THE PAUSE HUB (1.0.9, the user: "the middle button in th pause menu needs to be red and have the same reaction animations to being
     * pressed"): Home's disc, its swirl's light RED ([red] of it), the B, and the bar's Home press: [push] 0..1 sinks it to 0.92 around a
     * point 2 px lower with a shade at its lower edge (Strip.pushNow), [gold] 0..1 its press flash (Strip.goldNow: the gold light, brighter)
     */
    private fun pauseHubAt(c: Canvas, g: PauseGeo, cx: Float, cy: Float, size: Float, red: Float, b: Float, push: Float, gold: Float,
                           alpha: Int = 255) {
        val box = RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        val p = push.coerceIn(0f, 1f)
        val l = c.saveLayerAlpha(RectF(box).apply { inset(-4f, -4f) }, alpha)
        if (p > 0f) { val sc = 1f - 0.08f * p; c.scale(sc, sc, cx, cy + 2f * g.k * p) }
        if (red < 1f) crDisc(c, box, false, 255)
        if (red > 0f) crDisc(c, box, true, (255 * red.coerceIn(0f, 1f)).toInt())
        if (gold > 0f) crDiscGold(c, box, gold.coerceIn(0f, 1f))
        if (b > 0f) pad(c, backGlyph(), cx, cy, size * 0.30f, (255 * b.coerceIn(0f, 1f)).toInt())
        if (p > 0f) {
            val r = size / 2f
            pCrShade.shader = RadialGradient(cx, cy - r * 0.35f, r * 1.25f, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT,
                Color.argb((150 * p).toInt(), 0, 0, 0)), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, pCrShade)
        }
        c.restoreToCount(l)
    }
    /**
     * the pause hub alone after the wheel has gone (1.0.9, the user: "when closing the main menu, the spiral lingers in the middle, it
     * should transition back down to where it should be instead of fading out"): the disc at [t] of its arrival ([pauseDisc]) run
     * backward by the caller, so it glides from the wheel's middle back down onto the bar's Home disc ([disc], [discSize]), its red going
     * back to the bar's blue; a press ([push], [gold]) plays on it meanwhile. See [pauseHubAt]
     */
    fun pauseHubGlide(c: Canvas, screen: RectF, disc: android.graphics.PointF, discSize: Float, t: Float, push: Float, gold: Float,
                      up: String? = "Emotes", down: String? = "Sit / Stand", left: String? = null, right: String? = null) {
        val g = pauseGeo(screen)
        pauseDpadBar(c, screen, g, t * 260f / 460f, -1, up, down, left, right)     // the D-pad bar goes back down meanwhile
        val d = pauseDisc(screen, disc, discSize, t)
        pauseHubAt(c, g, d.cx, d.cy, d.size, d.red, d.b, push, gold)
    }

    /** a finger at ([x], [y]): a slice 0..7 (the KIT's order: 0 = the top, clockwise), [PAUSE_HUB] = the hub, -1 = outside the wheel */
    fun pauseRadialHit(screen: RectF, x: Float, y: Float): Int {
        val g = pauseGeo(screen)
        val dx = x - g.cx; val dy = y - g.cy
        val r = kotlin.math.hypot(dx, dy)
        if (r <= 87f * g.s) return PAUSE_HUB                    // inside the hub ring's outer edge
        if (r > 236f * g.s) return -1
        return consumableRadialAim(dx, dy, 0f)
    }

    /**
     * a stick's deflection ([dx], [dy], y DOWN, 0..1) -> the slice it points at (the KIT's order), -1 inside [dead]. The game begins a
     * selection past 0.5 of the stick (0.25 squared) and fires it when the stick falls back under 0.2 (0.04 squared): letting go picks
     */
    fun pauseRadialAim(dx: Float, dy: Float, dead: Float = 0.5f): Int = consumableRadialAim(dx, dy, dead)

    /** a finger on the context band's words: [PAUSE_OPT_UP] ("Emotes"), [PAUSE_OPT_DOWN] ("Sit / Stand"), -1 elsewhere */
    fun pauseOptionHit(screen: RectF, x: Float, y: Float): Int {
        val g = pauseGeo(screen)
        val dx = x - g.cx; val dy = y - g.padY
        return when {
            kotlin.math.abs(dx) < 110f * g.k && dy < -16f * g.k && dy > -(P_ARM + 26f) * g.k -> PAUSE_OPT_UP
            kotlin.math.abs(dx) < 110f * g.k && dy > 16f * g.k && dy < (P_ARM + 26f) * g.k -> PAUSE_OPT_DOWN
            kotlin.math.abs(dy) <= 18f * g.k && dx < -22f * g.k && dx > -240f * g.k -> PAUSE_OPT_LEFT
            kotlin.math.abs(dy) <= 18f * g.k && dx > 22f * g.k && dx < 240f * g.k -> PAUSE_OPT_RIGHT
            else -> -1
        }
    }

    /** a finger on LB / RB beside the page dots (a main page): -1 = the previous page, +1 = the next, 0 = elsewhere */
    fun pausePageHit(screen: RectF, x: Float, y: Float): Int {
        val g = pauseGeo(screen)
        if (kotlin.math.abs(y - g.topY) > 30f * g.k) return 0
        val off = x - g.cx
        return when { off < -70f * g.k && off > -220f * g.k -> -1; off > 70f * g.k && off < 220f * g.k -> 1; else -> 0 }
    }

    /**
     * THE PAUSE RADIAL over the whole bottom screen ([screen] = the room AND the bar), [t] ms along the open motion (0 .. PAUSE_OPEN_MS;
     * past it = open; closing = t run backward, [pauseCloseT]). Home moves its own parts with [pauseOut]; this draws the rising disc and
     * the menu over them.
     *   0 ..  60  the disc lights where it stands (a blue halo)
     *  60 .. 380  it rises to the wheel's middle, growing to the hub (ease in-out)
     * 300 .. 520  the wheel opens round the middle, 0.28 -> 1 (ease out), its alpha over the first 60 %
     * 340 .. 600  the slices' pictures and labels pop in, east first, counter-clockwise (the game's order)
     * 380 .. 520  the swirl turns red and the B comes in ([glyphs] false = no B)
     * 440 .. 600  the header band (the title, the page dots, LB / RB) slides down into place and the context band up, fading in
     * 520 .. 600  the pointed slice's wedge lights
     * [slices] = eight in the KIT's order (null = an empty slice); [page] = 0 / 1 / 2 for the three main pages ("Main Menu", the dots
     * between LB and RB; 1, the centre, opens first) or -1 for the Emotes page ("Emotes", no dots); [pointed] = the slice under the
     * stick (-1 none); [heldOption] = [PAUSE_OPT_UP] / [PAUSE_OPT_DOWN] held (its word on the game's bar), -1 none; [hubDown] = the hub's
     * press (as the consumables wheel's: CR_PRESS_IN_MS / _OUT_MS); [line] = the game's red error line (UIErrorsFrame) at the screen's
     * top, over the title ("Coming later" for a greyed pick); the caller holds and fades it ([lineAlpha]) as the game does (about 2 s,
     * then 0.5 s out). [up] / [down] = the context band's words. Returns true while the motion wants another frame.
     */
    /*
     * f-pause-art (the user, 1.0.9: "the art all looks like its made by AI instead of using in game assets. not everything match's the
     * home screen either", then on the renders "Option2, but the disk needs to be larger"): the game's own GamepadRadial
     * (blizzard_gamepad/ui/radials/gamepadradial.xml) - each label UNDER its icon in the game's GameFontNormal (gold, its shadow, no
     * outline), the D-pad's four words at its arms in the same font, one slim top row, sizes at Home's scale - with Home's own blue
     * Home-button disc in the middle (as the bottom bar's), filling the wheel's centre
     */
    /** the game's GameFontNormal: gold, its drop shadow, no outline (styles 1 / 2) */
    private fun gameWords(c: Canvas, text: String, x: Float, y: Float, size: Float, colour: Int, alpha: Int, maxW: Float) {
        val p = bodyPaint(size, colour, shadow = false).apply {
            textAlign = Paint.Align.CENTER; this.alpha = alpha; setShadowLayer(size * 0.08f, size * 0.06f, size * 0.06f, Color.argb(alpha, 0, 0, 0))
        }
        var t = text
        while (p.measureText(t) > maxW && t.length > 2) t = t.dropLast(2) + "\u2026"
        c.drawText(t, x, y - (p.ascent() + p.descent()) / 2f, p)
    }

    fun pauseRadial(c: Canvas, screen: RectF, disc: android.graphics.PointF, discSize: Float, slices: List<PauseSlice?>, page: Int,
                    pointed: Int, heldOption: Int = -1, t: Float = PAUSE_OPEN_MS, hubDown: Float = 0f, line: String? = null,
                    lineAlpha: Float = 1f, glyphs: Boolean = true, up: String? = "Emotes", down: String? = "Sit / Stand",
                    pickGlyph: Boolean = false, left: String? = null, right: String? = null, hubGold: Float = 0f): Boolean {
        val g = pauseGeo(screen)
        val tt = t.coerceIn(0f, PAUSE_OPEN_MS)
        // the game's order from here on
        val game = arrayOfNulls<PauseSlice>(8)
        for (i in 0 until 8) game[pauseFlip(i)] = slices.getOrNull(i)
        val pt = if (pointed in 0..7) pauseFlip(pointed) else -1
        val w = pSpan(tt, 300f, 520f)
        if (w > 0f) {
            val sc = 0.28f + 0.72f * crEaseOut(w)
            val half = 272f * g.s
            val save = c.save()
            c.scale(sc, sc, g.cx, g.cy)
            val l = c.saveLayerAlpha(RectF(g.cx - half, g.cy - half, g.cx + half, g.cy + half), (255 * minOf(1f, w / 0.6f)).toInt())
            pauseWheel(c, g, game, pt, crEaseOut(pSpan(tt, 520f, 600f)), pSpan(tt, 340f, 600f), pickGlyph)
            c.restoreToCount(l)
            c.restoreToCount(save)
        }
        // the D-pad bar slides up over the rooms' bar first; the disc rises over it
        pauseDpadBar(c, screen, g, tt, heldOption, up, down, left, right)
        // the disc (the hub): Home's own disc turning RED as it lands, its B, its press
        val d = pauseDisc(screen, disc, discSize, tt)
        pauseHubAt(c, g, d.cx, d.cy, d.size, d.red, if (glyphs) d.b else 0f, hubDown, hubGold)
        val b = pSpan(tt, 440f, 600f)
        if (b > 0f) pauseBands(c, g, page, crEaseOut(b), 24f * g.k * (1f - crEaseOut(b)))
        if (line != null && lineAlpha > 0f)
            crWords(c, line, g.cx, screen.top + 26f * g.k, 28f * g.k, PAUSE_RED, 2f * g.k, (255 * lineAlpha.coerceIn(0f, 1f)).toInt(),
                screen.width() - 40f * g.k)
        return t < PAUSE_OPEN_MS
    }

    /** the game's wedge art (drawn as the SOUTH slice, narrow end up) turned to slice [i] (the game's order), its middle 150 units out;
     *  [grey] = desaturated (the game's SegmentHighlight:SetDesaturated on an unavailable slice) */
    private fun pauseWedge(c: Canvas, g: PauseGeo, name: String, i: Int, alpha: Int, grey: Boolean = false) {
        val art = tex(name) ?: return
        val save = c.save()
        c.rotate(-(90f + 45f * i), g.cx, g.cy)
        val w = 169f * g.s; val h = 165f * g.s; val my = g.cy + (230f - 82.5f) * g.s
        // brightened 1.3: the game's grey highlight over its grey slice is too faint on the small screen (Home's p_pauseleft)
        pCrGrey.colorFilter = if (grey) crGreyFilter(0f, 1.3f) else null
        pCrGrey.alpha = alpha
        c.drawBitmap(art, null, RectF(g.cx - w / 2f, my - h / 2f, g.cx + w / 2f, my + h / 2f), pCrGrey)
        pCrGrey.colorFilter = null; pCrGrey.alpha = 255
        c.restoreToCount(save)
    }

    /** the wheel, its grey and lit wedges, the slices' pictures and labels, centred on the middle at scale 1 */
    private fun pauseWheel(c: Canvas, g: PauseGeo, slices: Array<PauseSlice?>, pt: Int, selK: Float, reveal: Float, pickGlyph: Boolean = false) {
        val d = 540f * g.s
        val bg = tex("rwheel_bg")
        if (bg != null) c.drawBitmap(bg, null, RectF(g.cx - d / 2f, g.cy - d / 2f, g.cx + d / 2f, g.cy - d / 2f + d * 541f / 540f), pBmp)
        else {
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 8f * g.k; pFlat.color = Theme.bronze
            c.drawCircle(g.cx, g.cy, 229f * g.s, pFlat); c.drawCircle(g.cx, g.cy, 69f * g.s, pFlat)
        }
        for (i in 0 until 8) { val sl = slices[i] ?: continue; if (!sl.enabled) pauseWedge(c, g, "rwheel_off", i, 255) }
        if (pt >= 0 && selK > 0f) pauseWedge(c, g, "rwheel_on", pt, (255 * selK).toInt(), slices[pt]?.enabled == false)
        for (i in 0 until 8) {
            val sl = slices[i] ?: continue
            val plain = sl.key == "emote"                        // a plain texture: 38 units, pulled 20 in
            val icon = pauseIcon(sl.key)
            val k = if (reveal < 1f) crEaseOut((reveal * 8f - i * 0.55f) / 1.6f) else 1f
            if (k <= 0.01f) continue
            val a = Math.toRadians(45.0 * i)
            val off = if (!sl.enabled) tex(icon + "_off") else null
            val art = off ?: tex(icon)
            // the game's SetIcon pulls an atlas icon 50 units toward the hub IN ITS OWN SCALED SPACE (SetScale(57 / its size), ~0.45
            // for the 128 px icons): ~23 units, so it sits mid-slice (r ~127) and its label near the rim (v1 pulled the full 50: the
            // icons sat against the hub; the user 2026-10-09, "the proportions on the pause wheel dont look right", picked 1C)
            val pull = 12f                                        // the icon on its segment's centre, the label under it (the game's)
            val ri = (150f - pull) * g.s
            val ix = g.cx + ri * Math.cos(a).toFloat(); val iy = g.cy - ri * Math.sin(a).toFloat()
            if (art != null) {
                val fit = (if (plain) 38f else 57f) * g.s * (0.6f + 0.4f * k)
                val sc = fit / maxOf(art.width, art.height)
                val aw = art.width * sc; val ah = art.height * sc
                pCrGrey.colorFilter = if (!sl.enabled && off == null) crGreyFilter(0f, 0.6f) else null
                pCrGrey.alpha = (255 * k).toInt()
                c.drawBitmap(art, null, RectF(ix - aw / 2f, iy - ah / 2f, ix + aw / 2f, iy + ah / 2f), pCrGrey)
                pCrGrey.colorFilter = null; pCrGrey.alpha = 255
                // THE A ON THE LIT SLICE while A picks (RC4, the user on RC3: "The pause menu is missing the button glyphs"): as the
                // consumables wheel marks its pointed item, the A half over the icon's top-left corner
                // (UI Art on 98a70ded: on the small plain emote icons the full-size A hid half the face: 0.7 of it there, centred on the corner)
                if (pickGlyph && i == pt && sl.enabled && selK > 0f) {
                    val gs = 41.6f * g.s / 1.3f * (if (plain) 0.7f else 1f)
                    val off = if (plain) 0f else gs / 2f - 0.35f * gs
                    pad(c, okGlyph().takeIf { n -> tex("pad_$n") != null } ?: "rev_a", ix - aw / 2f + off, iy - ah / 2f + off, gs, (255 * selK).toInt())
                }
            }
            if (k > 0.6f) {
                val la = (255 * ((k - 0.6f) / 0.4f).coerceIn(0f, 1f)).toInt()
                // the game's IconLabel: under the icon, 80 x 40 units (two lines), GameFontNormal
                val sx = g.cx + 150f * g.s * Math.cos(a).toFloat(); val sy = g.cy - 150f * g.s * Math.sin(a).toFloat()
                val words = sl.label.split(' ')
                val lsz = 22f * g.s / 1.3f                            // the labels grow with the wheel
                val lines = if (words.size > 1 && bodyPaint(lsz).measureText(sl.label) > 110f * g.s) listOf(words.dropLast(1).joinToString(" "), words.last()) else listOf(sl.label)
                val ly = sy + 35f * g.s * 1.1f
                for ((n, ln) in lines.withIndex()) gameWords(c, ln, sx, ly + n * 23f * g.s / 1.3f, lsz, if (sl.enabled) PAUSE_GOLD else PAUSE_GREY, la, 140f * g.s)
            }
        }
    }

    /** the slim top row (LB, the title and its page dots, RB) and the D-pad with its four words at its arms */
    private fun pauseBands(c: Canvas, g: PauseGeo, page: Int, k: Float, slide: Float) {
        val l = c.saveLayerAlpha(null, (255 * k).toInt())
        val ty = g.topY - slide
        val paging = page >= 0
        tex("rwheel_band")?.let {
            val bw = 520f * g.k; val bh = P_TOP_H * g.k
            c.drawBitmap(it, null, RectF(g.cx - bw / 2f, ty - bh / 2f, g.cx + bw / 2f, ty + bh / 2f), pBmp)
        }
        gameWords(c, if (paging) "Main Menu" else "Emotes", g.cx, ty - (if (paging) 8f else 0f) * g.k, 32f * g.k, Color.WHITE, 255, 340f * g.k)
        if (paging) {
            val ds = 12f * g.k; val dy = ty + 20f * g.k
            for (n in 0..2) {
                val x = g.cx + (n - 1) * (ds + 6f * g.k)
                val dot = tex(if (n == page) "rpage_on" else "rpage_off")
                if (dot != null) c.drawBitmap(dot, null, RectF(x - ds / 2f, dy - ds / 2f, x + ds / 2f, dy + ds / 2f), pBmp)
                else { pFlat.style = Paint.Style.FILL; pFlat.color = if (n == page) PAUSE_GOLD else Color.rgb(40, 40, 44); c.drawCircle(x, dy, ds / 2f, pFlat) }
            }
            val gs = 46f * g.k
            pad(c, shoulderGlyph(false), g.cx - 150f * g.k, ty, gs)
            pad(c, shoulderGlyph(true), g.cx + 150f * g.k, ty, gs)
        }
        c.restoreToCount(l)
    }

    /**
     * f-pause-bars: THE D-PAD BAR - its own full-width bar over the rooms' bar (the user: "the Dpad part on the bottom is its own full bar
     * that slides up to cover the rooms"): it slides up from under the screen's bottom edge over [t] 40..260 of the open (the rooms' bar's
     * old drop, reversed: run t backward to put it away), the D-pad and its four words in it
     */
    private fun pauseDpadBar(c: Canvas, screen: RectF, g: PauseGeo, t: Float, held: Int, up: String?, down: String?,
                             left: String?, right: String?) {
        val bar = pauseBarH(screen)
        val e = crEaseOut(pSpan(t, 40f, 260f))
        if (e <= 0f) return
        val top = screen.bottom - bar * e
        val box = RectF(screen.left, top, screen.right, top + bar)
        pFlat.style = Paint.Style.FILL
        pFlat.shader = LinearGradient(0f, box.top, 0f, box.bottom, Color.rgb(30, 24, 18), Color.rgb(8, 7, 6), Shader.TileMode.CLAMP)
        c.drawRect(box, pFlat)
        pFlat.shader = null
        pFlat.color = Theme.bronze; c.drawRect(box.left, box.top, box.right, box.top + 3f * g.k, pFlat)
        pFlat.color = Color.argb(120, 0, 0, 0); c.drawRect(box.left, box.top - 6f * g.k, box.right, box.top, pFlat)
        val l = c.saveLayerAlpha(null, 255)
        val py = g.padY + bar * (1f - e)
        val ds = 40f * g.k
        tex("rdpad")?.let { c.drawBitmap(it, null, RectF(g.cx - ds / 2f, py - ds / 2f, g.cx + ds / 2f, py + ds / 2f), pBmp) }
        val wp = bodyPaint(P_WORD * g.k)
        fun lit(x: Float, y: Float, w: Float) = tex("ropt_on")?.let {
            c.drawBitmap(it, null, RectF(x - w / 2f - 14f * g.k, y - 15f * g.k, x + w / 2f + 14f * g.k, y + 15f * g.k), pBmp)
        }
        for ((word, opt) in listOf(up to PAUSE_OPT_UP, down to PAUSE_OPT_DOWN, left to PAUSE_OPT_LEFT, right to PAUSE_OPT_RIGHT)) {
            if (word == null) continue
            val w = wp.measureText(word)
            val (x, y) = when (opt) {
                PAUSE_OPT_UP -> g.cx to py - P_ARM * g.k
                PAUSE_OPT_DOWN -> g.cx to py + P_ARM * g.k
                PAUSE_OPT_LEFT -> g.cx - P_ARM * g.k - w / 2f to py
                else -> g.cx + P_ARM * g.k + w / 2f to py
            }
            if (held == opt) lit(x, y, w)
            gameWords(c, word, x, y, P_WORD * g.k, PAUSE_GOLD, 255, 260f * g.k)
        }
        c.restoreToCount(l)
    }


    // ---------------------------------------------------------------- the GAME MENU and its COUNTDOWN (the pause wheel's Game Menu)
    //
    // The user, 2026-10-09: "the game menu, can we rebuild that on the bottom screen? just the menu, we wont dig into all of the
    // options"; picked "Build as drawn, on the cleared space the wheel leaves". The game's GameMenuFrame (Blizzard_GameMenu
    // Shared/GameMenuFrame.lua, SharedXML MainMenuFrameTemplates.xml): a 260-unit Dialog frame with the diamond-metal header "Game Menu",
    // its 200 x 36 red buttons (128-RedButton) with WHITE words (GameFontHighlightLarge), grey when disabled, no gap between buttons,
    // 20 units between sections, padding 48 / 34; with the gamepad UI no "Return to Game" button: the footer under it says
    // A Select / B Return to Game. Drawn at 2 px per unit on the 1240 x 1080 bottom screen. Picture: wow-companion-design\home\game_menu\.
    // The COUNTDOWN is the game's StaticPopup "CAMP" ("20 Seconds until logout", Cancel) / "QUIT" ("20 Seconds until exit",
    // Exit now / Cancel), in the kit's dialog, its buttons with the layout's glyphs.

    /** one button of the game menu: its word and whether it can be used now; null in a list = a section gap */
    class GameMenuItem(val label: String, val enabled: Boolean = true)

    /** [gameMenuHit]: a tap on the footer's "Return to Game" (= B) */
    const val GAME_MENU_BACK = -2

    private fun gmS(screen: RectF) = 2f * minOf(screen.width() / 1240f, screen.height() / 1080f)

    /** the frame and each item's button rectangle (null for a gap) */
    private fun gmLayout(screen: RectF, items: List<GameMenuItem?>): Pair<RectF, List<RectF?>> {
        val s = gmS(screen)
        val n = items.count { it != null }; val gaps = items.count { it == null }
        val fw = 260f * s; val fh = (48f + n * 36f + gaps * 20f + 34f) * s
        val top = screen.top + (screen.height() - fh - 34f * s) / 2f + 3f * s
        val frame = RectF(screen.centerX() - fw / 2f, top, screen.centerX() + fw / 2f, top + fh)
        var y = frame.top + 48f * s
        val rects = items.map { it ->
            if (it == null) { y += 20f * s; null }
            else RectF(frame.centerX() - 100f * s, y, frame.centerX() + 100f * s, y + 36f * s).also { y += 36f * s }
        }
        return frame to rects
    }

    /**
     * THE GAME MENU on [screen] (the cleared stage the pause wheel leaves): [items] top to bottom, null = a section gap (the game's:
     * Options, Shop* | Rewards*, AddOns, What's New*, Edit Mode*, Support, Macros | Log Out, Exit Game; * = only when the game shows
     * it); [cursor] = the item the pad is on (the kit's gold box; -1 none); [pressed] = the item held down (the kit's press), -1 none
     */
    fun gameMenu(c: Canvas, screen: RectF, items: List<GameMenuItem?>, cursor: Int, pressed: Int = -1) {
        val s = gmS(screen); val k = s / 2f
        val (frame, rects) = gmLayout(screen, items)
        dialog(c, RectF(frame.left - 5f * s, frame.top - 5f * s, frame.right + 5f * s, frame.bottom + 5f * s), 0.5f * k)
        dialogHeader(c, frame.centerX(), frame.top - 16f * s, "Game Menu", 0.5f * k)
        for ((i, it) in items.withIndex()) {
            val r = rects[i] ?: continue
            if (it == null) continue
            val state = when { !it.enabled -> Button.DISABLED; i == pressed -> Button.DOWN; else -> Button.UP }
            button(c, r, it.label, state, 32f * k, words = Color.WHITE)
            if (i == cursor) focus(c, RectF(r.left + 2f * s, r.top + 2f * s, r.right - 2f * s, r.bottom - 2f * s))
        }
        // the gamepad footer under the frame: A Select, B Return to Game (the layout's own glyphs)
        val fy = frame.bottom + 20f * s
        val p = bodyPaint(26f * k, Color.WHITE)
        val prompts = listOf(okGlyph() to "Select", backGlyph() to "Return to Game")
        val gs = 44f * k; val gap = 10f * k; val between = 40f * k
        var x = frame.centerX() - (prompts.sumOf { (gs + gap + p.measureText(it.second)).toDouble() }.toFloat() + between) / 2f
        for ((glyph, word) in prompts) {
            pad(c, glyph, x + gs / 2f, fy, gs)
            c.drawText(word, x + gs + gap, fy - (p.ascent() + p.descent()) / 2f, p)
            x += gs + gap + p.measureText(word) + between
        }
    }

    /** a finger at ([x], [y]) on [gameMenu]: the item's index, [GAME_MENU_BACK] on the footer's "Return to Game", -1 elsewhere */
    fun gameMenuHit(screen: RectF, items: List<GameMenuItem?>, x: Float, y: Float): Int {
        val s = gmS(screen)
        val (frame, rects) = gmLayout(screen, items)
        for ((i, r) in rects.withIndex()) if (r != null && x in r.left..r.right && y in r.top..r.bottom) return i
        val fy = frame.bottom + 20f * s
        if (kotlin.math.abs(y - fy) < 30f * s && x > frame.centerX() && x < frame.right + 40f * s) return GAME_MENU_BACK
        return -1
    }

    /** the countdown's words, the game's: "%d %s until logout" / "until exit", Second / Seconds, minutes from 60 s up */
    fun gameCountdownText(exit: Boolean, secondsLeft: Int): String {
        val t = secondsLeft.coerceAtLeast(0)
        val (n, unit) = if (t < 60) t to (if (t == 1) "Second" else "Seconds")
                        else ((t + 59) / 60).let { m -> m to (if (m == 1) "Minute" else "Minutes") }
        return "$n $unit until " + (if (exit) "exit" else "logout")
    }

    private fun gcLayout(screen: RectF, two: Boolean): Pair<RectF, List<RectF>> {
        val k = minOf(screen.width() / 1240f, screen.height() / 1080f)
        val box = RectF(screen.centerX() - 380f * k, screen.centerY() - 150f * k, screen.centerX() + 380f * k, screen.centerY() + 150f * k)
        val bw = 290f * k; val bh = 80f * k; val by = box.bottom - 70f * k - bh
        val buttons = if (two) listOf(RectF(box.centerX() - 20f * k - bw, by, box.centerX() - 20f * k, by + bh),
                                       RectF(box.centerX() + 20f * k, by, box.centerX() + 20f * k + bw, by + bh))
                      else listOf(RectF(box.centerX() - bw / 2f, by, box.centerX() + bw / 2f, by + bh))
        return box to buttons
    }

    /**
     * THE LOGOUT / EXIT COUNTDOWN (the game's StaticPopup CAMP / QUIT, 20 s) in the middle of [screen]: [gameCountdownText] in white,
     * then the game's buttons with the layout's glyphs: Cancel (B). [exitNow] = also the game's "Exit now" (A) before Cancel on an
     * exit: it is the game's ForceQuit, which the companion never sends (Home 2026-10-09), so it is off unless asked for. [cursor] = the
     * button the pad is on (0 = the first; the kit's gold box), [pressed] = the button held down. The caller ticks [secondsLeft] (the
     * game's count) and closes it at 0 or on Cancel.
     */
    fun gameCountdown(c: Canvas, screen: RectF, exit: Boolean, secondsLeft: Int, cursor: Int = 0, pressed: Int = -1,
                      exitNow: Boolean = false) {
        val k = minOf(screen.width() / 1240f, screen.height() / 1080f)
        val (box, buttons) = gcLayout(screen, exit && exitNow)
        val inner = dialog(c, box, 0.5f * k)
        val p = bodyPaint(34f * k, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
        c.drawText(gameCountdownText(exit, secondsLeft), box.centerX(), inner.top + 40f * k - (p.ascent() + p.descent()) / 2f, p)
        val labels = if (exit && exitNow) listOf("Exit now" to okGlyph(), "Cancel" to backGlyph()) else listOf("Cancel" to backGlyph())
        for ((i, r) in buttons.withIndex()) {
            button(c, r, labels[i].first, labels[i].second, if (i == pressed) Button.DOWN else Button.UP)
            if (i == cursor) focus(c, RectF(r.left + 4f * k, r.top + 4f * k, r.right - 4f * k, r.bottom - 4f * k))
        }
    }

    /** a finger on [gameCountdown]'s buttons: 0 = the first (Cancel, or "Exit now" when [exitNow]), 1 = Cancel then, -1 elsewhere */
    fun gameCountdownHit(screen: RectF, exit: Boolean, x: Float, y: Float, exitNow: Boolean = false): Int {
        val (_, buttons) = gcLayout(screen, exit && exitNow)
        for ((i, r) in buttons.withIndex()) if (x in r.left..r.right && y in r.top..r.bottom) return i
        return -1
    }

    // ---------------------------------------------------------------- the WORLD-REFRESH NOTICE (over Home's chat)
    //
    // The user, 2026-10-09: "I have a world notification right now that the world is going to refresh in 5 minutes ... make it a
    // notification above my chat in the home"; picked A, the pinned system line. The game's event is the shard transfer (StaticPopup
    // SHARD_TRANSFER_IMMINENT_EVENT; GetEvictionTimeRemaining()); its words SHARD_TRANSFER_REFRESH_MESSAGE "The world around you will
    // refresh in %s %s" (minutes, then seconds in the last minute) and, at 0, SHARD_TRANSFER_ANYTIME. Picture: wow-companion-design\
    // home\notice\ (SHEET_notice.png, A).

    /** the game's SYSTEM chat colour (ChatTypeInfo SYSTEM 1, 1, 0) */
    private val NOTICE_YELLOW = Color.rgb(255, 255, 0)

    /**
     * THE WORLD-REFRESH NOTICE pinned at the top of [rect] (the chat well's inside): the dialog's warning sign (kit `alert`), then the
     * game's words in its system yellow: "The world around you will refresh" over "in 4 Minutes"; in the last minute "in 42 Seconds"
     * in the game's error red; at 0 (both 0) "Refreshing your world at any time." over "Characters and creatures around you may
     * change."; a thin bronze line under it. [scale] 1 = the 1240 x 1080 bottom screen. Returns its height: the log starts under it.
     */
    fun notice(c: Canvas, rect: RectF, minutes: Int, seconds: Int, scale: Float = 1f): Float {
        val s = scale
        val h = 78f * s
        val x0 = rect.left + 6f * s; val y0 = rect.top + 10f * s
        tex("alert")?.let { c.drawBitmap(it, null, RectF(x0 + 4f * s, y0 + 8f * s, x0 + 56f * s, y0 + 60f * s), pBmp) }
        val tx = x0 + 68f * s; val room = rect.right - 8f * s - tx
        val anytime = minutes <= 0 && seconds <= 0
        val urgent = !anytime && minutes <= 0
        val l1 = if (anytime) "Refreshing your world at any time." else "The world around you will refresh"
        val l2 = when {
            anytime -> "Characters and creatures around you may change."
            urgent -> "in $seconds " + (if (seconds == 1) "Second" else "Seconds")
            else -> "in $minutes " + (if (minutes == 1) "Minute" else "Minutes")
        }
        for ((i, line) in listOf(l1, l2).withIndex()) {
            val p = bodyPaint(25f * s, if (i == 1 && urgent) PAUSE_RED else NOTICE_YELLOW)
            while (p.textSize > 19f * s && p.measureText(line) > room) p.textSize -= 1f
            c.drawText(TextUtils.ellipsize(line, TextPaint(p), room, TextUtils.TruncateAt.END).toString(), tx,
                y0 + (4f + 32f * i) * s - p.ascent(), p)
        }
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(110, 90, 50)
        c.drawRect(rect.left + 6f * s, y0 + 76f * s, rect.right - 8f * s, y0 + 78f * s, pFlat)
        return h + 10f * s
    }

    // ---------------------------------------------------------------- the PIN PICKER (the radial's "+")

    /** one usable item in the pin picker: its icon, stack, quality; [pinned] = already on the wheel (the small pin) */
    class PickItem(val icon: Bitmap?, val count: Int = 1, val quality: Int = 1, val pinned: Boolean = false)

    /** where a finger lands in the pin picker: [cells] = the items drawn (index = item index; RectF() when scrolled away); the buttons */
    class PickerHits(val cells: List<RectF>, val choose: RectF, val unpin: RectF?, val back: RectF)

    /** the picker's grid: 5 across, 104 px slots */
    const val PICK_COLS = 5

    /**
     * THE PIN PICKER (the user's pin sheet, signed off 2026-10-06; wow-companion-design\home\consumables\pin\): the game's window
     * "Pin an item" (the Bags room's picture in its ring) over the room, the usable items of the bags packed in bag order, [PICK_COLS]
     * across, 104 px slots, no empty wells; an item already pinned shows the small pin; the gold-box cursor on [cursor]; the game's
     * tooltip ([tipName] / [tipColour] / [tipLines]) beside the grid at the cursor's row; [firstRow] = the first row shown (the caller
     * scrolls the cursor into view). The strip: Choose (A) / Back (B); on an item ALREADY PINNED (the user's answer: "Pick it again
     * -> Unpin"): Move (A: choose a new slice) / Unpin (X) / Back (B). [down] = the button drawn pressed (0 Choose / Move, 1 Unpin, 2 Back).
     * A PRESS of A chooses. Returns the hit boxes.
     */
    fun pinPicker(c: Canvas, r: RectF, items: List<PickItem>, cursor: Int, tipName: String? = null, tipColour: Int = Color.WHITE,
                  tipLines: List<TipLine> = emptyList(), firstRow: Int = 0, down: Int = -1): PickerHits {
        val inner = windowGround(c, r)
        val strip = strip(c, inner)
        c.drawText("Usable items in your bags", inner.left + 150f, inner.top + 42f, bodyPaint(24f, Theme.textDim))
        val s = 104f; val gap = 26f; val rowH = s + gap + 8f
        val gx = inner.left + 150f; val gy = inner.top + 76f
        val rows = maxOf(1, ((strip.top - 20f - gy) / rowH).toInt())
        val cells = ArrayList<RectF>(items.size)
        var cur: RectF? = null
        for ((k, it) in items.withIndex()) {
            val row = k / PICK_COLS - firstRow
            if (row < 0 || row >= rows) { cells.add(RectF()); continue }
            val x = gx + (k % PICK_COLS) * (s + gap); val y = gy + row * rowH
            slot(c, it.icon, x, y, s, if (it.quality >= 2) Theme.quality(it.quality) else 0, if (it.count > 1) it.count else 0)
            if (it.pinned) tex("rwheel_pin")?.let { pin -> c.drawBitmap(pin, null, RectF(x + s - 30f, y - 8f, x + s + 12f, y + 34f), pBmp) }
            val cell = RectF(x, y, x + s, y + s)
            cells.add(cell)
            if (k == cursor) cur = cell
        }
        // the rows past the window: a small mark at the grid's foot that more follow (the cursor scrolls them in)
        if ((firstRow + rows) * PICK_COLS < items.size) {
            val p = bodyPaint(22f, Theme.textDim).apply { textAlign = Paint.Align.CENTER }
            c.drawText("more below", gx + (PICK_COLS * (s + gap) - gap) / 2f, gy + rows * rowH - 4f, p)
        }
        cur?.let { focus(c, it) }
        if (cur != null && tipName != null) tooltip(c, inner.right - 400f, cur.top - 6f, 380f, tipName, tipColour, tipLines)
        val pinnedNow = items.getOrNull(cursor)?.pinned == true
        val bt = strip.top + 13f; val bw = 330f; val bgap = 24f
        val back = RectF(inner.right - 8f - bw, bt, inner.right - 8f, bt + 111f)
        var unpin: RectF? = null
        val choose: RectF
        if (pinnedNow) {
            unpin = RectF(back.left - bgap - bw, bt, back.left - bgap, bt + 111f)
            choose = RectF(unpin.left - bgap - bw, bt, unpin.left - bgap, bt + 111f)
            button(c, choose, "Move", okGlyph(), if (down == 0) Button.DOWN else Button.UP)
            button(c, unpin, "Unpin", xGlyph(), if (down == 1) Button.DOWN else Button.UP)
        } else {
            choose = RectF(back.left - bgap - bw, bt, back.left - bgap, bt + 111f)
            button(c, choose, "Choose", okGlyph(), if (down == 0) Button.DOWN else Button.UP)
        }
        button(c, back, "Back", backGlyph(), if (down == 2) Button.DOWN else Button.UP)
        windowFrame(c, r, "Pin an item", tex("ricon_bags"), close = false)
        return PickerHits(cells, choose, unpin, back)
    }

    // ---------------------------------------------------------------- the game's friends, guild and raid windows
    //
    // User picks 2026-09-28: ONE Social room, its four tabs as pictures down the right edge
    // ([sideTab]); a list with the chosen player beside it; ONE action and More (the game's player
    // menu, [menu] over the right-hand pane); a raid of forty as eight groups of five.

    /**
     * ONE PLAYER OF A LIST, [r] 100 px high and the touch target: the game's status light
     * ([status] "online", "away", "dnd", "offline"), the name in [colour] (the class's;
     * `Theme.offline` when offline; `Theme.battleNet` for a BattleTag), a second line, and at
     * the right either a word ([word]: the rank, the guild) or one of the kit's marks ([mark]:
     * "role_tank", "mark_leader", "ready_yes" ...) or both (the mark left of the word's place).
     */
    fun playerLine(c: Canvas, r: RectF, status: String, name: String, colour: Int, line2: String, chosen: Boolean,
                   word: String? = null, mark: String? = null, mark2: String? = null, nameMark: String? = null) {
        listLine(c, r, chosen)
        tex("status_$status")?.let { c.drawBitmap(it, null, RectF(r.left + 22f, r.centerY() - 15f, r.left + 52f, r.centerY() + 15f), pixel) }
        var right = r.right - 24f
        if (word != null && word.isNotEmpty()) {
            val pw = bodyPaint(23f, Theme.dim).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(word, right, r.centerY() - (pw.ascent() + pw.descent()) / 2f, pw)
            right -= pw.measureText(word) + 18f
        }
        for (m in listOf(mark, mark2)) if (m != null) tex(m)?.let {
            c.drawBitmap(it, null, RectF(right - 40f, r.centerY() - 20f, right, r.centerY() + 20f), if (it.width < 40) pixel else pBmp)
            right -= 52f
        }
        val room = right - (r.left + 68f)
        val pn = bodyPaint(29f, colour)
        // [nameMark] = a mark RIGHT AFTER THE NAME, where the game puts its favourite-friend star (FriendsFrame camelot: parentKey
        // Favorite, anchored to the name's right): 30 px (the game's 17 at our scale), 8 px after the name; the name gives up its room
        val nm = nameMark?.let { tex(it) }
        val shown = TextUtils.ellipsize(name, pn, if (nm != null) room - 38f else room, TextUtils.TruncateAt.END).toString()
        c.drawText(shown, r.left + 68f, r.top + 34f - (pn.ascent() + pn.descent()) / 2f, pn)
        if (nm != null) {
            val x = r.left + 68f + pn.measureText(shown) + 8f
            c.drawBitmap(nm, null, RectF(x, r.top + 34f - 15f, x + 30f, r.top + 34f + 15f), pBmp)
        }
        val p2 = bodyPaint(22f, Theme.dim)
        c.drawText(TextUtils.ellipsize(line2, p2, room, TextUtils.TruncateAt.END).toString(), r.left + 68f,
            r.top + 68f - (p2.ascent() + p2.descent()) / 2f, p2)
    }

    /**
     * THE CHOSEN PLAYER'S HEAD in the detail pane [r]: the class's picture, the name in the
     * class's colour, the game's rule. Returns the y where the rows start.
     */
    fun playerHead(c: Canvas, r: RectF, classFile: String, name: String, colour: Int): Float {
        classIcon(classFile)?.let { c.drawBitmap(it, null, RectF(r.centerX() - 48f, r.top + 18f, r.centerX() + 48f, r.top + 114f), pBmp) }
        val p = bodyPaint(36f, colour).apply { textAlign = Paint.Align.CENTER }
        while (p.textSize > 26f && p.measureText(name) > r.width() - 12f) p.textSize -= 1f
        c.drawText(name, r.centerX(), r.top + 146f - (p.ascent() + p.descent()) / 2f, p)
        paneRule(c, r.left, r.top + 176f, r.right)
        return r.top + 216f
    }

    /**
     * One row of a detail pane: [label] in gold at the left, [value] in white from 132 px on,
     * on as many lines as it needs. [y] is the first line's centre; returns the next row's.
     */
    fun detailRow(c: Canvas, left: Float, y: Float, right: Float, label: String, value: String): Float {
        val pl = bodyPaint(25f, Theme.gold)
        c.drawText(label, left + 8f, y - (pl.ascent() + pl.descent()) / 2f, pl)
        val pv = TextPaint(bodyPaint(25f, Color.WHITE))
        val sl = StaticLayout.Builder.obtain(value, 0, value.length, pv, (right - left - 140f).toInt()).setLineSpacing(2f, 1f).build()
        c.save(); c.translate(left + 140f, y - 16f); sl.draw(c); c.restore()
        return y + 12f + maxOf(32f, sl.height.toFloat())
    }

    /** one of a raid's eight groups: its box over [r] and its name at the top; its lines start 46 px under the top */
    fun raidGroup(c: Canvas, r: RectF, label: String) {
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(140, 10, 8, 6)
        c.drawRect(r.left + 4f, r.top + 4f, r.right - 4f, r.bottom - 4f, pFlat)
        pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 1.5f; pFlat.color = Color.rgb(70, 56, 30)
        c.drawRect(r.left + 4f, r.top + 4f, r.right - 4f, r.bottom - 4f, pFlat)
        val p = bodyPaint(23f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
        c.drawText(label, r.centerX(), r.top + 26f - (p.ascent() + p.descent()) / 2f, p)
    }

    /** one place of a raid group, [r] about 68 px high: empty ([name] null), or a member in the class's colour */
    fun raidLine(c: Canvas, r: RectF, name: String?, colour: Int = Theme.text, chosen: Boolean = false, mark: String? = null) {
        pFlat.style = Paint.Style.FILL; pFlat.color = if (name == null) Color.rgb(16, 13, 10) else Color.rgb(30, 24, 16)
        c.drawRect(r.left, r.top + 2f, r.right, r.bottom - 2f, pFlat)
        if (name == null) return
        if (chosen) {
            glow(c, RectF(r.left, r.top + 2f, r.right, r.bottom - 2f), Theme.gold, 0.5f)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 1.5f; pFlat.color = Theme.bronze
            c.drawRect(r.left, r.top + 2f, r.right, r.bottom - 2f, pFlat)
        }
        var right = r.right - 12f
        if (mark != null) tex(mark)?.let {
            c.drawBitmap(it, null, RectF(right - 30f, r.centerY() - 15f, right, r.centerY() + 15f), if (it.width < 40) pixel else pBmp)
            right -= 38f
        }
        val p = bodyPaint(25f, colour)
        c.drawText(TextUtils.ellipsize(name, p, right - (r.left + 12f), TextUtils.TruncateAt.END).toString(), r.left + 12f,
            r.centerY() - 1f - (p.ascent() + p.descent()) / 2f, p)
    }

    // ---------------------------------------------------------------- the game's professions window
    //
    // What the game's own professions window is made of (ProfessionsFrame, camelot; read from
    // blizzard_professionsframe.xml, blizzard_professionsbooktemplates.xml and the recipe list's
    // templates in the client): the book of cards (Overview) and a crafting page per profession,
    // tabs down the right edge ([sideTab]), a recipe list under headings that fold ([listHeading],
    // [listLine]), the chosen recipe's reagents beside it. User picks 2026-09-28: "Yes, build
    // them as drawn", recipes in "One column, detail beside it".

    /** the ground of the Overview ([page] false) or of a crafting page, over [r] */
    fun professionGround(c: Canvas, r: RectF, page: Boolean) {
        val b = tex(if (page) "prof_ground_page" else "prof_ground")
        if (b == null) { pFlat.style = Paint.Style.FILL; pFlat.color = Theme.ink; c.drawRect(r, pFlat); return }
        c.drawBitmap(b, null, r, pLit)
    }

    /**
     * A profession's card in the book. [skillLine] is the game's id of the profession (171
     * Alchemy .. 393 Skinning, 185 Cooking, 129 First Aid, 356 Fishing, 40 Poisons, 633
     * Lockpicking): it chooses the card's own art. [small] = one of the three narrow cards.
     * Draw the card's content over it. [focus] = the card the controller is on.
     */
    fun professionCard(c: Canvas, r: RectF, skillLine: Int, small: Boolean = false, focus: Boolean = false) {
        val name = when (skillLine) {
            171 -> "alchemy"; 164 -> "blacksmithing"; 333 -> "enchanting"; 202 -> "engineering"; 182 -> "herbalism"
            165 -> "leatherworking"; 186 -> "mining"; 393 -> "skinning"; 197 -> "tailoring"; 185 -> "cooking"
            129 -> "firstaid"; 356 -> "fishing"; 40 -> "poisons"; 633 -> "lockpicking"; else -> ""
        }
        val b = (if (small) tex("prof_small_$name") else tex("prof_card_$name")) ?: tex("prof_card")
        if (b != null) c.drawBitmap(b, null, r, pCard)
        else { pFlat.style = Paint.Style.FILL; pFlat.color = Theme.raised; c.drawRect(r, pFlat) }
        pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 1.5f; pFlat.color = Color.rgb(70, 56, 30)
        c.drawRect(r, pFlat)
        if (focus) focus(c, r)
    }

    /** the game's skill bar of a profession: its ground, the blue fill up to [fill] (0..1), its frame, [label] on it */
    fun skillBar(c: Canvas, r: RectF, fill: Float, label: String, textSize: Float = 26f, tint: Int = 0) {
        val g = tex("skill_ground")
        if (g != null) c.drawBitmap(g, null, r, pBmp)
        else { pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(12, 10, 8); c.drawRect(r, pFlat) }
        val w = (r.width() - 12f) * fill.coerceIn(0f, 1f)
        if (w > 0.5f) {
            pFlat.style = Paint.Style.FILL
            // [tint] 0 = the skills' blue; else that colour (a pet's experience: the game's purple), its top half lighter
            pFlat.color = if (tint != 0) tint else Color.rgb(30, 120, 200)
            c.drawRect(r.left + 6f, r.top + 7f, r.left + 6f + w, r.bottom - 7f, pFlat)
            pFlat.color = if (tint != 0) Color.rgb(minOf(255, Color.red(tint) + 45), minOf(255, Color.green(tint) + 40),
                minOf(255, Color.blue(tint) + 35)) else Color.rgb(70, 160, 235)
            c.drawRect(r.left + 6f, r.top + 7f, r.left + 6f + w, r.centerY(), pFlat)
        }
        val f = tex("skill_frame")
        if (f != null) c.drawBitmap(f, null, r, pBmp)
        else { pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Theme.bronzeDim; c.drawRect(r, pFlat) }
        val p = bodyPaint(textSize, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
        c.drawText(label, r.centerX(), r.centerY() - 1f - (p.ascent() + p.descent()) / 2f, p)
    }

    /** a picture in the professions' own square frame (a profession, what a recipe makes); [size] = the frame's side */
    fun framed(c: Canvas, icon: Bitmap?, x: Float, y: Float, size: Float, dim: Boolean = false) {
        if (icon != null) c.drawBitmap(icon, null, RectF(x + 8f, y + 8f, x + size - 8f, y + size - 8f), if (dim) pDim else pBmp)
        val f = tex("prof_frame")
        if (f != null) c.drawBitmap(f, null, RectF(x, y, x + size, y + size), pBmp)
        else { pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 3f; pFlat.color = Theme.bronze; c.drawRect(x + 4f, y + 4f, x + size - 4f, y + size - 4f, pFlat) }
    }

    /**
     * A PROFESSION'S SPELL BUTTON on a book card (the game's ProfessionButtonTemplate: the spell's icon, its name beside it in
     * gold, two lines at most). [r] = its touch box, 111 px high or more; the icon, 88 px in the professions' frame, stands at
     * its left, centred. [inert] = shown but not a button here (a spell with no recipes: Find Minerals, Fishing): icon and
     * name dim, never pressed or chosen. [chosen] = the controller's glow (the game's checked glow, as on a slot); [down] =
     * held by a finger (the game's pushed square).
     */
    fun professionButton(c: Canvas, r: RectF, icon: Bitmap?, name: String, inert: Boolean = false, chosen: Boolean = false,
                         down: Boolean = false) {
        val s = 88f
        val x = r.left + 8f; val y = r.centerY() - s / 2f
        framed(c, icon, x, y, s, dim = inert)
        val sq = RectF(x + 8f, y + 8f, x + s - 8f, y + s - 8f)
        if (down && !inert) tex("focus_frame")?.let { c.drawBitmap(it, null, sq, pBmp) }
        if (chosen && !inert) slotFocus(c, sq)
        val tx = x + s + 14f
        val p = TextPaint(bodyPaint(26f, if (inert) Theme.dim else Theme.gold))
        val l = StaticLayout.Builder.obtain(name, 0, name.length, p, (r.right - tx).toInt().coerceAtLeast(1)).setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END).build()
        c.save(); c.translate(tx, r.centerY() - l.height / 2f); l.draw(c); c.restore()
    }

    /**
     * The game's search box over [r] (72 px high): its border, its magnifier, and [text], or
     * [hint] in the dim colour while nothing is typed. As a `TextView`'s ground:
     * `view.background = UiKit.background { c, r -> UiKit.searchBox(c, r, "", "") }`.
     */
    fun searchBox(c: Canvas, r: RectF, text: String, hint: String) {
        val l = tex("search_left"); val m = tex("search_mid"); val e = tex("search_right")
        if (l != null && m != null && e != null) {
            val k = r.height() / l.height
            val cw = l.width * k
            c.drawBitmap(l, null, RectF(r.left, r.top, r.left + cw, r.bottom), pBmp)
            c.drawBitmap(e, null, RectF(r.right - cw, r.top, r.right, r.bottom), pBmp)
            c.drawBitmap(m, null, RectF(r.left + cw - 0.5f, r.top, r.right - cw + 0.5f, r.bottom), pBmp)
        } else inputBox(c, r)
        tex("search_glass")?.let { c.drawBitmap(it, null, RectF(r.left + 16f, r.centerY() - 20f, r.left + 56f, r.centerY() + 20f), pBmp) }
        val shown = text.isNotEmpty()
        val p = bodyPaint(26f, if (shown) Theme.text else Theme.dim)
        c.drawText(TextUtils.ellipsize(if (shown) text else hint, p, r.width() - 90f, TextUtils.TruncateAt.END).toString(), r.left + 70f,
            r.centerY() - 1f - (p.ascent() + p.descent()) / 2f, p)
    }

    /**
     * A COUNT between its two arrows (how many to make), centred on ([cx], [cy]): the game's
     * red arrow buttons 100 px, 120 px left and right of the number's box (136 x 104). An arrow
     * that leads nowhere is greyed. Touch boxes: each arrow's 111 x 111 round its centre.
     */
    fun countBox(c: Canvas, cx: Float, cy: Float, count: Int, canLess: Boolean, canMore: Boolean, focus: Boolean = false) {
        arrow(c, "left", cx - 120f, cy, 100f, if (canLess) Button.UP else Button.DISABLED)
        arrow(c, "right", cx + 120f, cy, 100f, if (canMore) Button.UP else Button.DISABLED)
        val box = RectF(cx - 68f, cy - 52f, cx + 68f, cy + 52f)
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.BLACK
        c.drawRect(box.left + 6f, box.top + 6f, box.right - 6f, box.bottom - 6f, pFlat)
        border(c, box, "border_tooltip", 18f, Theme.bronzeDim)
        val p = numberPaint(56f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
        c.drawText(count.toString(), cx, cy - 2f - (p.ascent() + p.descent()) / 2f, p)
        if (focus) focus(c, box)
    }

    // ---------------------------------------------------------------- the game's group finder
    //
    // What the game's own Looking For Group window is made of in this client (LFGParentFrame, the
    // Classic board; read from blizzard_groupfinder_vanillastyle/mainline/*.xml and
    // blizzard_lfgvanilla_browse.lua): side tabs ([sideTab] with "tab_listing", "tab_browse",
    // "tab_wholist"), two drop-downs and Search Again over a list of players and groups under two
    // headings that fold ([listHeading] with signRight). User picks 2026-09-28: "A, list and
    // chosen entry side by side", "1, the game's two drop-downs", "Two tabs until it is built",
    // "Only what can be done".

    /**
     * THE GAME'S DROP-DOWN over [r] (92 px high): its dark button over the whole width, [label]
     * in white at the left, the small arrow at the right. [open] while its list shows; grey
     * while it is not [enabled]. Its list is a [menu] of [menuChoice] rows.
     */
    fun dropDown(c: Canvas, r: RectF, label: String, open: Boolean = false, enabled: Boolean = true) {
        val b = tex(if (!enabled) "drop_button_off" else if (open) "drop_button_open" else "drop_button")
        if (b != null) {
            // the two ends kept, the middle stretched
            val cap = 34
            val cw = cap * r.height() / b.height
            c.drawBitmap(b, Rect(cap, 0, b.width - cap, b.height), RectF(r.left + cw - 0.5f, r.top, r.right - cw + 0.5f, r.bottom), pBmp)
            c.drawBitmap(b, Rect(0, 0, cap, b.height), RectF(r.left, r.top, r.left + cw, r.bottom), pBmp)
            c.drawBitmap(b, Rect(b.width - cap, 0, b.width, b.height), RectF(r.right - cw, r.top, r.right, r.bottom), pBmp)
        } else {
            pFlat.style = Paint.Style.FILL; pFlat.color = Theme.raised
            c.drawRoundRect(RectF(r.left + 8f, r.top + 8f, r.right - 8f, r.bottom - 8f), 8f, 8f, pFlat)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = if (enabled) Theme.bronze else Theme.bronzeDim
            c.drawRoundRect(RectF(r.left + 8f, r.top + 8f, r.right - 8f, r.bottom - 8f), 8f, 8f, pFlat)
        }
        tex("drop_arrow")?.let {
            c.drawBitmap(it, null, RectF(r.right - 64f, r.centerY() - 8f, r.right - 30f, r.centerY() + 7f), if (enabled) pBmp else pGrey)
        }
        val p = bodyPaint(26f, if (enabled) Color.WHITE else Theme.dim)
        val room = r.width() - 64f - 40f
        while (p.measureText(label) > room && p.textSize > 20f) p.textSize -= 1f
        c.drawText(TextUtils.ellipsize(label, p, room, TextUtils.TruncateAt.END).toString(), r.left + 30f,
            r.centerY() - 3f - (p.ascent() + p.descent()) / 2f, p)
    }

    /** the game's Search Again button, [size] px, centred: its dark square and the round arrow; grey while it cannot search.
     *  [icon] = another kit picture in the same square: the Who tab's search is the magnifier, `"search_glass"` */
    fun searchAgain(c: Canvas, cx: Float, cy: Float, size: Float, enabled: Boolean = true, icon: String = "finder_refresh") {
        val r = RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        val b = tex(if (enabled) "finder_square" else "finder_square_off") ?: tex("finder_square")
        if (b != null) c.drawBitmap(b, null, r, pixel)
        else {
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 3f; pFlat.color = if (enabled) Theme.gold else Theme.bronzeDim
            c.drawRoundRect(RectF(r.left + 6f, r.top + 6f, r.right - 6f, r.bottom - 6f), 8f, 8f, pFlat)
        }
        val s = size * 0.5f
        tex(icon)?.let {
            c.drawBitmap(it, null, RectF(cx - s / 2f - 2f, cy - s / 2f, cx + s / 2f - 2f, cy + s / 2f), if (enabled) pixel else pGrey)
        }
    }

    /**
     * A PLACE OF A GROUP as the game's list draws it: "tank", "heal", "dps", "empty" (a free
     * place), or a class ("class_warrior"). [small] = the icons of a player looking alone.
     */
    fun role(what: String, small: Boolean = false): Bitmap? = when {
        what == "empty" -> tex("role_empty")
        what.startsWith("class_") -> tex("finderclass_" + what.substring(6).lowercase())
        small -> tex("role_small_$what")
        else -> tex("role_$what")
    }

    /** the places of [what] as icons of [size] px, filled FROM THE RIGHT as the game fills them, ending at [right]; returns where they begin */
    fun roles(c: Canvas, right: Float, cy: Float, what: List<String>, size: Float, small: Boolean = false, off: Boolean = false): Float {
        var x = right
        for (w in what.asReversed()) {
            x -= size
            role(w, small)?.let { c.drawBitmap(it, null, RectF(x, cy - size / 2f, x + size, cy + size / 2f), if (off) pGrey else pBmp) }
            x -= 2f
        }
        return x
    }

    private fun finderGround(c: Canvas, r: RectF, chosen: Boolean) {
        val box = RectF(r.left + 4f, r.top + 3f, r.right - 4f, r.bottom - 3f)
        tex("finder_line")?.let { c.drawBitmap(it, null, box, pBmp) }
        if (!chosen) return
        val on = tex("list_line_on")
        if (on != null) c.drawBitmap(on, null, box, pBmp) else selectedRow(c, box)
    }

    private fun finderActivity(c: Canvas, r: RectF, activity: String, tone: Int, room: Float) {
        val p = bodyPaint(22f, tone)
        c.drawText(TextUtils.ellipsize(activity, p, room, TextUtils.TruncateAt.END).toString(), r.left + 28f,
            r.top + 70f - (p.ascent() + p.descent()) / 2f, p)
    }

    /**
     * ONE PLAYER LOOKING ALONE, [r] 100 px high and the touch target, as the game's list draws
     * it: the name in [colour] (the class's), "Lvl N", the class's round icon, the newcomer
     * mark when [friendly]; under it what the player looks for ([activity]) in [tone]
     * (`Theme.finderPlain`, `finderMatch`, `finderMine`); at the right "Roles:" and the small
     * icons of [roles] ("tank", "heal", "dps"). [off] = delisted or filtered out: grey.
     */
    fun finderPlayer(c: Canvas, r: RectF, name: String, colour: Int, level: Int, classFile: String, activity: String, tone: Int,
                     roles: List<String>, friendly: Boolean = false, chosen: Boolean = false, off: Boolean = false) {
        finderGround(c, r, chosen)
        val pl = bodyPaint(22f, if (off) Theme.finderOff else Theme.finderPlain)
        val lvl = "Lvl $level"
        val after = 12f + pl.measureText(lvl) + 10f + 44f + (if (friendly) 42f else 0f)
        val pn = bodyPaint(29f, if (off) Theme.finderOff else colour)
        val pr = bodyPaint(22f, if (off) Theme.finderOff else Color.WHITE).apply { textAlign = Paint.Align.RIGHT }
        // names on this client are two words and run long: the name is set smaller (29 down to 23 px) before
        // anything gives way; then the word "Roles:" goes (its icons stay); only then is the name cut
        val icons = if (roles.isEmpty()) 0f else roles.size * 48f
        val word = if (roles.isEmpty()) 0f else 10f + pr.measureText("Roles:") + 16f
        val room = r.right - 22f - icons - (r.left + 28f) - after
        while (pn.measureText(name) > room - word && pn.textSize > 23f) pn.textSize -= 1f
        val withWord = roles.isNotEmpty() && pn.measureText(name) <= room - word
        var right = r.right - 22f
        if (roles.isNotEmpty()) {
            right = roles(c, right, r.centerY(), roles, 46f, small = true, off = off)
            if (withWord) {
                c.drawText("Roles:", right - 10f, r.centerY() - 1f - (pr.ascent() + pr.descent()) / 2f, pr)
                right -= word
            } else right -= 12f
        }
        val shown = TextUtils.ellipsize(name, pn, right - after - (r.left + 28f), TextUtils.TruncateAt.END).toString()
        c.drawText(shown, r.left + 28f, r.top + 34f - (pn.ascent() + pn.descent()) / 2f, pn)
        var x = r.left + 28f + pn.measureText(shown) + 12f
        c.drawText(lvl, x, r.top + 36f - (pl.ascent() + pl.descent()) / 2f, pl)
        x += pl.measureText(lvl) + 10f
        role("class_$classFile")?.let { c.drawBitmap(it, null, RectF(x, r.top + 12f, x + 44f, r.top + 56f), if (off) pGrey else pBmp) }
        x += 50f
        if (friendly) tex("finder_newcomer")?.let { c.drawBitmap(it, null, RectF(x, r.top + 16f, x + 36f, r.top + 52f), if (off) pGrey else pBmp) }
        finderActivity(c, r, activity, if (off) Theme.finderOff else tone, right - (r.left + 28f))
    }

    /**
     * ONE GROUP, [r] 100 px high and the touch target: the crown, its leader's name in
     * [colour], the newcomer mark when [friendly]; under it [activity] in [tone]. At the right
     * what the game shows for the activity's kind: the group's [places] as icons (roles or
     * classes, "empty" for a free place), or a count per role ([counts]: tank, healer, damage),
     * or a [word] (the number of players; "Has a description"). [off] = delisted or filtered out.
     */
    fun finderGroup(c: Canvas, r: RectF, leader: String, colour: Int, activity: String, tone: Int, places: List<String> = emptyList(),
                    counts: IntArray? = null, word: String? = null, friendly: Boolean = false, chosen: Boolean = false,
                    off: Boolean = false) {
        finderGround(c, r, chosen)
        var right = r.right - 22f
        if (places.isNotEmpty()) right = roles(c, right, r.centerY(), places, 52f, off = off) - 12f
        else if (counts != null && counts.size >= 3) {
            val pc = numberPaint(28f, if (off) Theme.finderOff else Color.WHITE).apply { textAlign = Paint.Align.RIGHT }
            for ((i, what) in listOf("dps", "heal", "tank").withIndex()) {
                val n = counts[2 - i].toString()
                c.drawText(n, right, r.centerY() - (pc.ascent() + pc.descent()) / 2f, pc)
                right -= pc.measureText(n) + 6f
                role(what)?.let { c.drawBitmap(it, null, RectF(right - 44f, r.centerY() - 22f, right, r.centerY() + 22f), if (off) pGrey else pBmp) }
                right -= 44f + 18f
            }
        } else if (word != null) {
            val pw = bodyPaint(23f, if (off) Theme.finderOff else Theme.note).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(word, right, r.centerY() - (pw.ascent() + pw.descent()) / 2f, pw)
            right -= pw.measureText(word) + 18f
        }
        tex("finder_leader")?.let { c.drawBitmap(it, null, RectF(r.left + 22f, r.top + 16f, r.left + 68f, r.top + 52f), if (off) pGrey else pBmp) }
        val pn = bodyPaint(29f, if (off) Theme.finderOff else colour)
        val room = right - (if (friendly) 46f else 0f) - (r.left + 78f)
        while (pn.measureText(leader) > room && pn.textSize > 23f) pn.textSize -= 1f       // smaller before it is cut
        val shown = TextUtils.ellipsize(leader, pn, room, TextUtils.TruncateAt.END).toString()
        c.drawText(shown, r.left + 78f, r.top + 34f - (pn.ascent() + pn.descent()) / 2f, pn)
        if (friendly) tex("finder_newcomer")?.let {
            val x = r.left + 78f + pn.measureText(shown) + 10f
            c.drawBitmap(it, null, RectF(x, r.top + 16f, x + 36f, r.top + 52f), if (off) pGrey else pBmp)
        }
        finderActivity(c, r, activity, if (off) Theme.finderOff else tone, right - (r.left + 28f))
    }

    /**
     * ONE MEMBER of the chosen group, in the detail pane: the game's leader mark for the
     * [leader], the name in [colour], "Lvl N", the role at the right ([role] "tank", "heal",
     * "dps", or null). [y] is the line's centre; returns the next line's (44 px on).
     *
     * With [classWord] (the game's word for the class, "Warrior") the member takes TWO lines:
     * the name over the pane's whole width, under it "Lvl N Warrior"; the next member is 66 px
     * on. Names on this client are two words: in one line they are cut at about twelve letters.
     */
    fun finderMember(c: Canvas, left: Float, y: Float, right: Float, name: String, colour: Int, level: Int, role: String?,
                     leader: Boolean = false, classWord: String? = null): Float {
        if (classWord != null) {
            val cy = y + 11f                       // the two lines' middle
            if (leader) tex("mark_leader")?.let { c.drawBitmap(it, null, RectF(left + 8f, y - 16f, left + 38f, y + 14f), pixel) }
            if (role != null) role(role)?.let { c.drawBitmap(it, null, RectF(right - 56f, cy - 22f, right - 12f, cy + 22f), pBmp) }
            val pn = bodyPaint(26f, colour)
            val room = right - 66f - (left + 46f)
            while (pn.measureText(name) > room && pn.textSize > 21f) pn.textSize -= 1f
            c.drawText(TextUtils.ellipsize(name, pn, room, TextUtils.TruncateAt.END).toString(), left + 46f,
                y - (pn.ascent() + pn.descent()) / 2f, pn)
            val p2 = bodyPaint(21f, Theme.finderPlain)
            c.drawText(TextUtils.ellipsize("Lvl $level $classWord", p2, room, TextUtils.TruncateAt.END).toString(), left + 46f,
                y + 28f - (p2.ascent() + p2.descent()) / 2f, p2)
            return y + 66f
        }
        if (leader) tex("mark_leader")?.let { c.drawBitmap(it, null, RectF(left + 8f, y - 16f, left + 38f, y + 14f), pixel) }
        if (role != null) role(role)?.let { c.drawBitmap(it, null, RectF(right - 52f, y - 20f, right - 12f, y + 20f), pBmp) }
        val pl = bodyPaint(22f, Theme.finderPlain).apply { textAlign = Paint.Align.RIGHT }
        val lvl = "Lvl $level"
        c.drawText(lvl, right - 70f, y + 1f - (pl.ascent() + pl.descent()) / 2f, pl)
        val pn = bodyPaint(26f, colour)
        c.drawText(TextUtils.ellipsize(name, pn, right - 70f - pl.measureText(lvl) - 14f - (left + 46f), TextUtils.TruncateAt.END).toString(),
            left + 46f, y - (pn.ascent() + pn.descent()) / 2f, pn)
        return y + 44f
    }

    /** "New Players Welcome" with the game's mark, in its green; [y] is the line's centre; returns the next line's (50 px on) */
    fun finderWelcome(c: Canvas, left: Float, y: Float, label: String): Float {
        tex("finder_newcomer")?.let { c.drawBitmap(it, null, RectF(left + 6f, y - 18f, left + 42f, y + 18f), pBmp) }
        val p = bodyPaint(25f, Theme.welcome)
        c.drawText(label, left + 50f, y - (p.ascent() + p.descent()) / 2f, p)
        return y + 50f
    }

    /** a search that runs: the game's word ([label], "Searching...") and its eye under it, centred on [cx]; [top] is the word's centre */
    fun searching(c: Canvas, cx: Float, top: Float, label: String) {
        val p = bodyPaint(34f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
        c.drawText(label, cx, top - (p.ascent() + p.descent()) / 2f, p)
        tex("finder_eye")?.let { c.drawBitmap(it, null, RectF(cx - 75f, top + 50f, cx + 75f, top + 200f), pBmp) }
    }

    /**
     * ONE ROW OF A LIST OF CHOICES in a [menu], 96 to 112 px high: at its left the game's round
     * mark ([radio]: one of several) or its check box (any of several), [on] or not; [more] =
     * the row holds a further list (the game's arrow at the right). The row the controller is
     * on is [selected]. [mark] false = no round mark and no box: a row that opens a further list
     * ("Class") or does one thing ("Check All"), as the game's menus draw them.
     */
    fun menuChoice(c: Canvas, r: RectF, label: String, selected: Boolean, on: Boolean, radio: Boolean = false,
                   more: Boolean = false, enabled: Boolean = true, mark: Boolean = true) {
        if (selected) focus(c, RectF(r.left + 6f, r.top + 8f, r.right - 6f, r.bottom - 8f))   // THE one highlight (LATER 19)
        val cx = r.left + 50f; val cy = r.centerY()
        // the name in full: set smaller (32 down to 24 px) before it is cut
        val p = bodyPaint(32f, if (!enabled) Theme.dim else if (selected) Theme.gold else Theme.text)
        val room = r.right - (if (more) 66f else 16f) - (r.left + 100f)
        while (p.measureText(label) > room && p.textSize > 24f) p.textSize -= 1f
        c.drawText(TextUtils.ellipsize(label, p, room, TextUtils.TruncateAt.END).toString(), r.left + 100f,
            cy - 2f - (p.ascent() + p.descent()) / 2f, p)
        if (!mark) Unit
        else if (radio) {
            val ring = tex("menu_radio")
            if (ring != null) c.drawBitmap(ring, null, RectF(cx - 26f, cy - 26f, cx + 26f, cy + 26f), pBmp)
            else { pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 3f; pFlat.color = Theme.bronze; c.drawCircle(cx, cy, 20f, pFlat) }
            if (on) {
                val dot = tex("menu_radio_on")
                if (dot != null) c.drawBitmap(dot, null, RectF(cx - 15f, cy - 15f, cx + 15f, cy + 15f), pBmp)
                else { pFlat.style = Paint.Style.FILL; pFlat.color = Theme.gold; c.drawCircle(cx, cy, 9f, pFlat) }
            }
        } else check(c, cx, cy, 60f, on, enabled)
        if (more) tex("menu_next")?.let { c.drawBitmap(it, null, RectF(r.right - 56f, cy - 15f, r.right - 26f, cy + 15f), pBmp) }
    }

    // ---------------------------------------------------------------- the game's calendar
    //
    // What the game's own calendar is made of (CalendarFrame; read from blizzard_calendar/
    // mainline/*.xml and .lua): the month's name between two arrows, the weekday bar, six rows of
    // seven days on parchment, a holiday's picture laid on its days, the mark of an invitation
    // that waits. User picks 2026-09-29: "A, the month and the chosen day side by side", "1, one
    // under another", "1, the game's own mark", "The four months that are ready".

    /** one of the two arrows beside the month's name, [size] px, centred: the game's page arrows; grey where the months end */
    fun pageArrow(c: Canvas, next: Boolean, cx: Float, cy: Float, size: Float, enabled: Boolean = true) {
        val b = tex((if (next) "page_next" else "page_prev") + (if (enabled) "" else "_off"))
        if (b != null) c.drawBitmap(b, null, RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f), pixel)
        else arrow(c, if (next) "right" else "left", cx, cy, size, if (enabled) Button.UP else Button.DISABLED)
    }

    /** the bar a weekday's name stands on, over [r] (36 px high), the [name] in white */
    fun calendarWeekday(c: Canvas, r: RectF, name: String) {
        val b = tex("cal_weekday")
        if (b != null) c.drawBitmap(b, null, r, pBmp)
        else { pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(150, 70, 10); c.drawRect(r, pFlat) }
        val p = bodyPaint(19f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
        while (p.measureText(name) > r.width() - 6f && p.textSize > 15f) p.textSize -= 1f
        c.drawText(name, r.centerX(), r.centerY() - 1f - (p.ascent() + p.descent()) / 2f, p)
    }

    /**
     * ONE DAY of the month over [r] (112 x 130, the touch target): one of the game's four
     * parchment grounds ([ground], any number: the room passes the day's place in the month so
     * that the grounds vary), the [banner] of a holiday laid on it as the game lays it (the
     * picture's upper left 91 / 128), the date on its little dark plate, and at the foot a mark
     * per ENTRY of the day, from the right ([marks]: the entry's own picture, or null for a gold
     * stud; four at most). [invitation]: the game's mark of an invitation that waits, top right.
     * [today]: the gold frame. [chosen]: the glow. [other]: a day of the month before or after,
     * drawn dark, as the game draws it.
     */
    fun calendarDay(c: Canvas, r: RectF, day: Int, ground: Int = 0, banner: Bitmap? = null, marks: List<Bitmap?> = emptyList(),
                    invitation: Boolean = false, today: Boolean = false, chosen: Boolean = false, other: Boolean = false) {
        val g = tex("cal_day_${(ground and 0x7fffffff) % 4 + 1}")
        if (g != null) c.drawBitmap(g, null, r, pBmp)
        else { pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(190, 150, 80); c.drawRect(r, pFlat) }
        if (banner != null)
            c.drawBitmap(banner, Rect(0, 0, (banner.width * 0.711f).toInt(), (banner.height * 0.711f).toInt()), r, pBmp)
        var x = r.right - 8f
        for (m in marks.take(4).asReversed()) {
            if (m != null) {
                x -= 40f
                c.drawBitmap(m, null, RectF(x, r.bottom - 46f, x + 40f, r.bottom - 6f), pBmp)
            } else {
                x -= 22f
                pFlat.style = Paint.Style.FILL; pFlat.color = Theme.gold
                c.drawCircle(x + 9f, r.bottom - 27f, 9f, pFlat)
                pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Color.rgb(90, 60, 0)
                c.drawCircle(x + 9f, r.bottom - 27f, 9f, pFlat)
            }
            x -= 6f
        }
        if (invitation) tex("cal_invite")?.let { c.drawBitmap(it, null, RectF(r.right - 53f, r.top + 4f, r.right - 4f, r.top + 70f), pBmp) }
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(140, 0, 0, 0)
        c.drawRect(r.left + 6f, r.top + 6f, r.left + 46f, r.top + 38f, pFlat)
        val p = bodyPaint(24f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
        c.drawText(day.toString(), r.left + 26f, r.top + 21f - (p.ascent() + p.descent()) / 2f, p)
        if (other) { pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(158, 0, 0, 0); c.drawRect(r, pFlat) }
        pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 1f; pFlat.color = Color.rgb(28, 20, 10)
        c.drawRect(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f, pFlat)
        if (today && !other) {
            pFlat.strokeWidth = 2f; pFlat.color = Theme.gold
            c.drawRect(r.left + 1f, r.top + 1f, r.right - 1f, r.bottom - 1f, pFlat)
            pFlat.color = Color.rgb(120, 90, 10)
            c.drawRect(r.left + 3f, r.top + 3f, r.right - 3f, r.bottom - 3f, pFlat)
        }
        if (chosen && !other) {
            glow(c, RectF(r.left + 2f, r.top + 2f, r.right - 2f, r.bottom - 2f), Theme.gold, 0.45f)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 1.5f; pFlat.color = Color.WHITE
            c.drawRect(r.left + 1.5f, r.top + 1.5f, r.right - 1.5f, r.bottom - 1.5f, pFlat)
        }
    }

    /**
     * ONE ENTRY of the chosen day's list, [r] 104 px high and the touch target: the list's
     * ground, the entry's [picture] when it has one (64 px), its [title] in [colour] on one or
     * two lines (set smaller, 25 down to 21 px, before it breaks), and [under] it a line in the
     * dim colour (the time; the dates of what is ongoing; "Invited by ...").
     */
    fun calendarEntry(c: Canvas, r: RectF, picture: Bitmap?, title: String, colour: Int, under: String, chosen: Boolean = false) {
        listLine(c, RectF(r.left, r.top, r.right, r.bottom - 4f), chosen)
        val left = if (picture != null) r.left + 96f else r.left + 28f
        if (picture != null) c.drawBitmap(picture, null, RectF(r.left + 20f, r.top + 18f, r.left + 84f, r.top + 82f), pBmp)
        val room = (r.right - 18f - left).toInt()
        val pt = TextPaint(bodyPaint(25f, colour))
        while (pt.measureText(title) > room && pt.textSize > 21f) pt.textSize -= 1f
        val sl = StaticLayout.Builder.obtain(title, 0, title.length, pt, room).setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 0.95f).build()
        val two = sl.lineCount > 1
        c.save(); c.translate(left, r.top + (if (two) 8f else 22f)); sl.draw(c); c.restore()
        val pu = bodyPaint(21f, Theme.dim)
        c.drawText(TextUtils.ellipsize(under, pu, room.toFloat(), TextUtils.TruncateAt.END).toString(), left,
            r.top + (if (two) 80f else 70f) - (pu.ascent() + pu.descent()) / 2f, pu)
    }

    /** a box for words that were written by a player (an event's description), over [r]: dark, with the game's thin edge; returns the inside */
    fun noteBox(c: Canvas, r: RectF): RectF {
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(9, 8, 8)
        c.drawRect(r.left + 4f, r.top + 4f, r.right - 4f, r.bottom - 4f, pFlat)
        border(c, r, "border_tooltip", 18f, Theme.bronzeDim)
        return RectF(r.left + 20f, r.top + 16f, r.right - 20f, r.bottom - 16f)
    }

    // ---------------------------------------------------------------- the talents (camelot's ClassTalentsFrame)
    //
    // The user's picks 2026-09-30: "A, one school at a time", "See and learn". The game's own pieces: the square node in its
    // states, its arrows, the school pictures, the -c60 tabs, the check mark, Undo / Reset. Spec: wow-companion-design/talents/SPEC.md.

    /** a talent's state as the game draws it: LOCKED (its row or arrow not met: the locked frame), OPEN (can take a point), PART (some
     *  points, none to spend now: the game's Normal), FULL (every point), STAGED (a point added but not applied yet: the green glow),
     *  GREY (the game's Disabled: reachable, arrows met, nothing learnt, no point to spend: the grey frame; Spells' t7) */
    enum class Talent { LOCKED, OPEN, PART, FULL, STAGED, GREY }

    /** a school's own picture behind its talents over [r] (kit `talentbg_<class>_<spec>`, e.g. talentbg_hunter_beastmastery), fitted
     *  to [r]'s height and cut at the right, darkened to [light] so the talents stand out; a plain dark ground without it */
    fun talentGround(c: Canvas, r: RectF, cls: String, spec: String, light: Float = 0.55f) {
        val b = tex("talentbg_${cls.lowercase()}_${spec.lowercase()}")
        pFlat.style = Paint.Style.FILL
        if (b != null) {
            val k = r.height() / b.height
            val w = minOf(b.width.toFloat(), r.width() / k)
            c.drawBitmap(b, Rect(0, 0, w.toInt(), b.height), RectF(r.left, r.top, r.left + w * k, r.bottom), pBmp)
            pFlat.color = Color.argb(((1f - light) * 255).toInt().coerceIn(0, 255), 0, 0, 0)
        } else pFlat.color = Color.rgb(12, 10, 9)
        c.drawRect(r, pFlat)
    }

    /**
     * ONE TALENT, a square node of [size] px centred on ([cx], [cy]): its [icon], the game's frame for its [state], and its ranks
     * "r/max" on the lower right, as the game's TalentButtonUtil colours them: green while a point can go in NOW (OPEN, the green
     * frame), yellow on the yellow frame for FULL and for PART (the game's "Normal": some points, none to spend now: Spells' t6, the
     * user's "they show Green, even when there is not points available to spend"), grey when LOCKED (none on a locked talent without
     * a point). [chosen] = the talent a finger or the controller is on (the gold frame and glow: the room's one cursor).
     */
    fun talentNode(c: Canvas, cx: Float, cy: Float, size: Float, icon: Bitmap?, rank: Int, max: Int, state: Talent,
                   chosen: Boolean = false) {
        fun box(k: Float, dy: Float = 0f) = RectF(cx - size * k / 2f, cy - size * k / 2f + dy, cx + size * k / 2f, cy + size * k / 2f + dy)
        if (state == Talent.STAGED) tex("talent_glow")?.let { c.drawBitmap(it, null, box(1.7f), pBmp) }
        tex("talent_shadow")?.let { c.drawBitmap(it, null, box(1.05f, size * 0.03f), pBmp) }
        if (icon != null) {
            if (state == Talent.OPEN) pBmp.alpha = 215
            // LOCKED: the game's gated dim (its overlay at 0.7); GREY: its disabled dim, lighter (overlay 0.25), both desaturated
            if (state == Talent.GREY) { pCrGrey.colorFilter = crGreyFilter(0.15f, 0.75f); c.drawBitmap(icon, null, box(0.74f), pCrGrey); pCrGrey.colorFilter = null }
            else c.drawBitmap(icon, null, box(0.74f), if (state == Talent.LOCKED) pGrey else pBmp)
            pBmp.alpha = 255
        }
        val frame = tex(when (state) {
            Talent.FULL, Talent.PART -> "talent_full"; Talent.LOCKED -> "talent_locked"; Talent.GREY -> "talent_gray"; else -> "talent_open"
        })
        if (frame != null) c.drawBitmap(frame, null, box(1f), pBmp)
        else { pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 3f; pFlat.color = Theme.bronze; c.drawRect(box(0.9f), pFlat) }
        if ((state != Talent.LOCKED && state != Talent.GREY) || rank > 0) {
            val p = numberPaint(size * 0.3f, when (state) {
                Talent.FULL, Talent.PART -> Color.rgb(255, 255, 0)           // YELLOW_FONT_COLOR (Maxed and Normal)
                Talent.LOCKED, Talent.GREY -> Color.rgb(150, 150, 150); else -> Color.rgb(30, 255, 0)
            }).apply { textAlign = Paint.Align.CENTER }
            val bx = cx + size / 2f - 6f; val by = cy + size / 2f - 6f
            pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(230, 12, 10, 8)
            c.drawRect(bx - size * 0.52f, by - size * 0.3f, bx + 4f, by + 2f, pFlat)
            c.drawText("$rank/$max", bx - size * 0.24f, by - size * 0.14f - (p.ascent() + p.descent()) / 2f, p)
        }
        if (chosen) {
            val g = box(1f).apply { inset(-8f, -8f) }
            glow(c, g, Theme.gold, 0.45f)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 3f; pFlat.color = Theme.gold
            c.drawRect(g.left + 1f, g.top + 1f, g.right - 1f, g.bottom - 1f, pFlat)
        }
    }

    /** the game's arrow from one talent to the talent it opens, centre ([x0], [y0]) to centre ([x1], [y1]), straight down or across,
     *  stopping at the nodes' edges ([size] = the nodes' size); [lit] once the first talent is full */
    fun talentArrow(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, size: Float, lit: Boolean) {
        val down = kotlin.math.abs(y1 - y0) >= kotlin.math.abs(x1 - x0)
        val from = if (down) y0 else x0
        val to = if (down) y1 else x1
        val sgn = if (to > from) 1f else -1f
        val a = from + sgn * (size / 2f + 2f)                         // the line: off the first node's edge ...
        val b = to - sgn * (size / 2f + 22f)                          // ... to the head's root
        val mid = (a + b) / 2f; val len = kotlin.math.abs(b - a)
        val line = tex(if (lit) "talent_line_lit" else "talent_line")  // a 16 px tile, lying across: turned for a line going down
        c.save()
        if (down) c.rotate(90f, x0, mid)
        val lr = if (down) RectF(x0 - len / 2f, mid - 8f, x0 + len / 2f, mid + 8f) else RectF(mid - len / 2f, y0 - 8f, mid + len / 2f, y0 + 8f)
        if (line != null) c.drawBitmap(line, null, lr, pBmp)
        else { pFlat.style = Paint.Style.FILL; pFlat.color = if (lit) Color.rgb(230, 190, 20) else Color.rgb(70, 70, 70); c.drawRect(lr.left, lr.centerY() - 3f, lr.right, lr.centerY() + 3f, pFlat) }
        c.restore()
        val head = tex(if (lit) "talent_head_lit" else "talent_head") ?: return   // points down as the game draws it
        val hx = if (down) x0 else b + sgn * 12f
        val hy = if (down) b + sgn * 12f else y0
        c.save()
        c.rotate(if (down) (if (sgn > 0) 0f else 180f) else (if (sgn > 0) -90f else 90f), hx, hy)
        c.drawBitmap(head, null, RectF(hx - 14f, hy - 12f, hx + 14f, hy + 12f), pBmp)
        c.restore()
    }

    /** a SCHOOL's tab over [r]: the game's talent tab (lit when [active]), the school's [icon], its [name] and the points spent in it */
    fun schoolTab(c: Canvas, r: RectF, icon: Bitmap?, name: String, points: Int, active: Boolean, cursor: Boolean = false) {
        if (cursor) { tabCursor(c, r, "school:$active") { schoolTab(it, r, icon, name, points, active) }; return }
        tex("talent_tab")?.let { c.drawBitmap(it, null, r, pBmp) }
        if (active) tex("talent_tab_on")?.let { c.drawBitmap(it, null, r, pBmp) }
        val s = r.height() - 22f
        if (icon != null) {
            if (!active) pBmp.alpha = 150
            c.drawBitmap(icon, null, RectF(r.left + 12f, r.top + 11f, r.left + 12f + s, r.top + 11f + s), pBmp)
            pBmp.alpha = 255
        }
        val tx = r.left + 12f + s + 14f
        val pn = bodyPaint(25f, if (active) Color.WHITE else Theme.gold)
        c.drawText(TextUtils.ellipsize(name, pn, r.right - 12f - tx, TextUtils.TruncateAt.END).toString(), tx,
            r.centerY() - 12f - (pn.ascent() + pn.descent()) / 2f, pn)
        val pp = bodyPaint(19f, Theme.dim)
        c.drawText(if (points == 1) "1 point" else "$points points", tx, r.centerY() + 16f - (pp.ascent() + pp.descent()) / 2f, pp)
    }

    /** a dual-spec tab over [r] ("Primary" / "Secondary"): lit, with the game's check mark, when [active]; a [locked] one is dim and
     *  reads "(Locked)" after its word, as the game shows Secondary before dual spec is learned */
    fun specTab(c: Canvas, r: RectF, word: String, active: Boolean, locked: Boolean = false, cursor: Boolean = false) {
        if (cursor) { tabCursor(c, r, "spec:$active:$locked") { specTab(it, r, word, active, locked) }; return }
        tex("talent_tab")?.let { c.drawBitmap(it, null, r, pBmp) }
        var x = r.left + 16f
        if (active) {
            tex("talent_tab_on")?.let { c.drawBitmap(it, null, r, pBmp) }
            tex("talent_check")?.let { c.drawBitmap(it, null, RectF(x - 4f, r.centerY() - 10f, x + 22f, r.centerY() + 10f), pBmp) }
            x += 30f
        }
        val p = bodyPaint(21f, if (active) Color.WHITE else Theme.dim)
        c.drawText(if (locked) "$word  (Locked)" else word, x, r.centerY() - (p.ascent() + p.descent()) / 2f, p)
    }

    /** "Unspent Talents" and the number, ending at [right], centred on [cy]; returns where the words begin */
    fun talentPool(c: Canvas, right: Float, cy: Float, have: Int, label: String = "Unspent Talents"): Float {
        val pn = numberPaint(34f, Color.WHITE).apply { textAlign = Paint.Align.RIGHT }
        c.drawText(have.toString(), right, cy - (pn.ascent() + pn.descent()) / 2f, pn)
        val pl = bodyPaint(24f, Theme.gold).apply { textAlign = Paint.Align.RIGHT }
        val x = right - pn.measureText(have.toString()) - 16f
        c.drawText(label, x, cy - (pl.ascent() + pl.descent()) / 2f, pl)
        return x - pl.measureText(label)
    }

    /** a red button over [r] with the kit picture [name] in its middle and no word (the talents' Undo `talent_undo`, Reset `talent_reset`) */
    fun iconButton(c: Canvas, r: RectF, name: String, state: Button = Button.UP, waiting: Boolean = false) {
        iconButtonP(c, r, name, state, waiting)
        if (waiting) waitOverlay(c, r)
    }

    private fun iconButtonP(c: Canvas, r: RectF, name: String, state: Button, waiting: Boolean) = pressed(c, r, state == Button.DOWN || waiting) {
        buttonPlate(c, r, state)
        tex(name)?.let { b ->
            val s = r.height() * 0.6f
            pBmp.alpha = if (state == Button.DISABLED) 110 else 255
            c.drawBitmap(b, null, RectF(r.centerX() - s / 2f, r.centerY() - s / 2f, r.centerX() + s / 2f, r.centerY() + s / 2f), pBmp)
            pBmp.alpha = 255
        }
    }

    // ---------------------------------------------------------------- Create Listing (the group finder's first tab, LFGListingFrame)
    //
    // The user's picks 2026-09-30: "B, the game's own window", Quests & Zones / Custom "Grey them with the reason", Back reads Delist
    // while listed "as the game". Spec: wow-companion-design/groupfinder/LISTING_SPEC.md.

    /**
     * A CATEGORY BUTTON over [r], as the game's: the category's picture (kit `finder_cat_dungeons` / `_raids` / `_questing` / `_pvp` /
     * `_custom`, cut to [r]'s shape from its middle) under the game's cover, the [name] in it. [chosen] = the one a finger or the
     * controller is on. [off] = it cannot be listed from the room: grey, with [reason] under the name.
     */
    fun categoryButton(c: Canvas, r: RectF, picture: String, name: String, chosen: Boolean = false, off: Boolean = false,
                       reason: String? = null) {
        tex(picture)?.let {
            val w = minOf(it.width.toFloat(), it.height * r.width() / r.height())
            val src = Rect(((it.width - w) / 2f).toInt(), 0, ((it.width + w) / 2f).toInt(), it.height)
            if (!off && !chosen) pBmp.alpha = 190
            c.drawBitmap(it, src, RectF(r.left + 6f, r.top + 6f, r.right - 6f, r.bottom - 6f), if (off) pGrey else pBmp)
            pBmp.alpha = 255
        }
        tex("finder_cat_cover")?.let { c.drawBitmap(it, null, r, pBmp) }
        if (chosen) focus(c, RectF(r.left + 4f, r.top + 4f, r.right - 4f, r.bottom - 4f))   // THE one highlight (LATER 19)
        val two = off && reason != null
        val pn = bodyPaint(30f, when { off -> Theme.dim; chosen -> Color.WHITE; else -> Theme.gold })
        c.drawText(name, r.left + 28f, r.centerY() - (if (two) 12f else 0f) - (pn.ascent() + pn.descent()) / 2f, pn)
        if (two) {
            val pr = bodyPaint(20f, Theme.dim)
            c.drawText(TextUtils.ellipsize(reason, pr, r.width() - 56f, TextUtils.TruncateAt.END).toString(), r.left + 28f,
                r.centerY() + 22f - (pr.ascent() + pr.descent()) / 2f, pr)
        }
    }

    /** a ROLE the player offers (the listing's roles): the game's large role picture ([role] "tank" / "heal" / "dps") of [size] px at
     *  ([x], [y]), its check box on the lower left, ticked when [on], and [label] under it; dim when not [on], faint when not [enabled] */
    fun roleButton(c: Canvas, x: Float, y: Float, size: Float, role: String, on: Boolean, label: String? = null, enabled: Boolean = true) {
        tex("role_$role")?.let {
            pBmp.alpha = when { !enabled -> 90; on -> 255; else -> 150 }
            c.drawBitmap(it, null, RectF(x, y, x + size, y + size), pBmp)
            pBmp.alpha = 255
        }
        check(c, x + 15f, y + size - 15f, 46f, on, enabled)
        if (label != null) {
            val p = bodyPaint(23f, if (on && enabled) Theme.gold else Theme.dim).apply { textAlign = Paint.Align.CENTER }
            c.drawText(label, x + size / 2f, y + size + 22f - (p.ascent() + p.descent()) / 2f, p)
        }
    }

    /** "New Players Welcome": the game's newcomer mark (kit `finder_newcomer`) in a box of [size] px at ([x], [y]), its check box on
     *  the lower left, the words beside it */
    fun newcomerButton(c: Canvas, x: Float, y: Float, size: Float, on: Boolean, label: String = "New Players Welcome") {
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(30, 24, 16)
        c.drawRect(x, y, x + size, y + size, pFlat)
        pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f; pFlat.color = Theme.bronzeDim
        c.drawRect(x, y, x + size, y + size, pFlat)
        tex("finder_newcomer")?.let {
            val s = size * 0.62f
            if (!on) pBmp.alpha = 150
            c.drawBitmap(it, null, RectF(x + (size - s) / 2f, y + (size - s) / 2f - 6f, x + (size + s) / 2f, y + (size + s) / 2f - 6f), pBmp)
            pBmp.alpha = 255
        }
        check(c, x + 15f, y + size - 15f, 46f, on)
        val p = bodyPaint(23f, if (on) Theme.gold else Theme.dim)
        c.drawText(label, x + size + 20f, y + size / 2f - (p.ascent() + p.descent()) / 2f, p)
    }

    /**
     * ONE LINE OF THE ACTIVITY LIST over [r] (64 px tall): a [group] (its - / + on the left as [open], one tick for all its activities,
     * the name in gold) or an activity (its tick, the name, [level] on the right as the game writes it, "(15 - 25)", and after the
     * name the game's warning mark (kit `finder_saved`) when the character is [saved] to it). [on] = ticked (the row's warm ground).
     */
    fun activityRow(c: Canvas, r: RectF, name: String, level: String?, on: Boolean, group: Boolean = false, open: Boolean = true,
                    saved: Boolean = false, foldable: Boolean = true) {
        val cy = r.centerY()
        if (group) {
            // [foldable] false: the game hides the - / + when the category has ONE group (isMultiActivityGroup); the rest stays put
            if (foldable) tex(if (open) "list_minus" else "list_plus")?.let {             // the minus is a bar: its shape kept
                val sh = 30f * it.height / it.width
                c.drawBitmap(it, null, RectF(r.left + 8f, cy - sh / 2f, r.left + 38f, cy + sh / 2f), pBmp)
            }
            check(c, r.left + 71f, cy, 46f, on)
            val p = bodyPaint(27f, Theme.gold)
            c.drawText(name, r.left + 108f, cy - (p.ascent() + p.descent()) / 2f, p)
            return
        }
        if (on) { pFlat.style = Paint.Style.FILL; pFlat.color = Theme.selected; c.drawRect(r.left + 40f, r.top + 4f, r.right, r.bottom - 4f, pFlat) }
        check(c, r.left + 81f, cy, 46f, on)
        val pl = bodyPaint(23f, if (on) Theme.gold else Theme.dim).apply { textAlign = Paint.Align.RIGHT }
        val lw = if (level != null) pl.measureText(level) + 24f else 0f
        if (level != null) c.drawText(level, r.right - 16f, cy - (pl.ascent() + pl.descent()) / 2f, pl)
        val pn = bodyPaint(26f, if (on) Color.WHITE else Theme.text)
        val room = r.right - 16f - lw - (r.left + 118f) - (if (saved) 30f else 0f)
        val shown = TextUtils.ellipsize(name, pn, room, TextUtils.TruncateAt.END).toString()
        c.drawText(shown, r.left + 118f, cy - (pn.ascent() + pn.descent()) / 2f, pn)
        if (saved) tex("finder_saved")?.let {
            val x = r.left + 118f + pn.measureText(shown) + 10f
            c.drawBitmap(it, null, RectF(x, cy - 11f, x + 22f, cy + 11f), pBmp)
        }
    }

    /**
     * THE LISTING'S DESCRIPTION over [r]: the game's instruction [label] above it ("More details about your group (optional)"), the
     * dark box, and in it [empty] dim in the middle. The room never shows or WRITES the text: the game protects the box (only real
     * typing in the game's own window fills it); [typed] true says so ("Written in the game" in gold), false says it is empty.
     */
    fun descriptionBox(c: Canvas, r: RectF, label: String?, typed: Boolean?, empty: String = "Written in the game's own window") {
        if (label != null) {
            val pl = bodyPaint(21f, Theme.dim)
            c.drawText(label, r.left, r.top - 22f - (pl.ascent() + pl.descent()) / 2f, pl)
        }
        noteBox(c, r)
        val word = if (typed == true) "Written in the game" else empty
        val p = bodyPaint(21f, if (typed == true) Theme.gold else Color.rgb(110, 100, 84)).apply { textAlign = Paint.Align.CENTER }
        c.drawText(word, r.centerX(), r.centerY() - (p.ascent() + p.descent()) / 2f, p)
    }

    // ---------------------------------------------------------------- the later list (the user picked them 2026-09-30)
    //
    // LATER.md item 4, "a golden highlight in most selection scenarios": the selection on a PARCHMENT page (gossip, quest)
    // was the darkened paper ("just a black box"). [paperGold] draws it in gold, in one of the three looks put to the user;
    // after the pick, focus(onPaper = true) uses that one. Items 1-2: the game's item [tooltip] (vendor, quest rewards),
    // and "Currently Equipped" beside it.

    /**
     * THE GOLD SELECTION ON A PARCHMENT PAGE over [r] (a gossip option, a reward). [look] 1 = the game's highlight bar in
     * gold (the words stay ink); 2 = that bar and a gold line round it (the rooms' cursor, with a thin dark edge so it
     * shows on the light paper); 3 = the gold line alone. THE USER PICKED 2 (2026-09-30), which focus(onPaper = true) now draws;
     * 1 and 3 stay for a page where the edge would crowd. [glyph] (e.g. "rev_a") = what the pad does on it, at its right end:
     * the ONE option of a single-option menu, and the focused row of any, say "press A" (LATER item 4).
     */
    fun paperGold(c: Canvas, r: RectF, look: Int = 2, glyph: String? = null) {
        if (look == 1 || look == 2) {
            val bar = tex("highlight")
            val tint = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                0f, 0f, 0f, 0f, 255f, 0f, 0f, 0f, 0f, 209f, 0f, 0f, 0f, 0f, 0f, 0.299f, 0.587f, 0.114f, 0f, 0f)))
            if (bar != null) {
                val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = tint; alpha = 170 }
                c.drawBitmap(bar, null, r, p)
            } else { pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(90, 255, 209, 0); c.drawRect(r, pFlat) }
        }
        if (look == 2 || look == 3) {
            val o = RectF(r.left - 3f, r.top - 3f, r.right + 3f, r.bottom + 3f)
            pFlat.style = Paint.Style.STROKE
            pFlat.strokeWidth = 7f; pFlat.color = Color.argb(150, 70, 44, 10)
            c.drawRoundRect(o, 8f, 8f, pFlat)
            pFlat.strokeWidth = 3.5f; pFlat.color = Theme.gold
            c.drawRoundRect(o, 8f, 8f, pFlat)
        }
        if (glyph != null) pad(c, glyph, r.right - 40f, r.centerY(), minOf(60f, r.height() * 0.62f))
    }

    /** one line of the game's item tooltip: [t] on the left in [c], and [r] on the right in [rc] (the game's two-sided lines:
     *  "Legs" ... "Leather"); [money] >= 0 = a sell price line, the coins after [t] */
    class TipLine(val t: String, val c: Int = Color.WHITE, val r: String? = null, val rc: Int = Color.WHITE, val money: Long = -1)

    /**
     * THE GAME'S ITEM TOOLTIP at ([x], [y]), [w] wide, as tall as its lines: the tooltip's dark ground and thin border,
     * [header] over the name when there is one (the game's grey "Currently Equipped"), the [name] in its quality colour, then
     * the lines (the game's own colours; a long left text wraps, a line with a right text keeps one line). Returns its box,
     * so a second one (the compare) can stand beside or under it. It never takes a touch: it shows what is highlighted.
     * [scale] < 1 = the game's smaller compare tooltip (its shopping tooltip's smaller fonts, [COMPARE_SCALE]): text and padding.
     */
    fun tooltip(c: Canvas, x: Float, y: Float, w: Float, name: String, nameColour: Int, lines: List<TipLine>,
                header: String? = null, alpha: Int = 255, scale: Float = 1f): RectF {
        val pad = 22f * scale
        val tw = (w - 2f * pad).toInt()
        val ph = bodyPaint(21f * scale, Color.rgb(128, 128, 128))
        val pn = TextPaint(bodyPaint(30f * scale, nameColour))
        val pl = TextPaint(bodyPaint(24f * scale))
        // measure first: the box is as tall as what it holds
        val nameL = StaticLayout.Builder.obtain(name, 0, name.length, pn, tw).setLineSpacing(2f, 1f).build()
        val lays = lines.map { l ->
            pl.color = l.c
            val room = if (l.r != null) tw - pl.measureText(l.r).toInt() - 16 else tw
            StaticLayout.Builder.obtain(l.t, 0, l.t.length, TextPaint(pl), room.coerceAtLeast(40))
                .setMaxLines(if (l.r != null) 1 else 6).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(2f, 1f).build()
        }
        val headH = 30f * scale
        var h = pad + (if (header != null) headH else 0f) + nameL.height + 8f + lays.sumOf { (it.height + 4).toDouble() }.toFloat() + pad
        val box = RectF(x, y, x + w, y + h)
        if (alpha <= 0) return box                          // still waiting out its quarter second ([TipFade]): nothing yet
        val layer = alpha < 255
        if (layer) c.saveLayerAlpha(RectF(box.left - 4f, box.top - 4f, box.right + 4f, box.bottom + 4f), alpha)
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(250, 6, 6, 10)
        c.drawRect(box.left + 4f, box.top + 4f, box.right - 4f, box.bottom - 4f, pFlat)
        border(c, box, "border_tooltip", 18f, Color.rgb(120, 120, 120))
        var ty = y + pad
        if (header != null) { c.drawText(header, x + pad, ty + 18f * scale - (ph.ascent() + ph.descent()) / 2f - 4f * scale, ph); ty += headH }
        c.save(); c.translate(x + pad, ty); nameL.draw(c); c.restore(); ty += nameL.height + 8f
        for ((i, l) in lines.withIndex()) {
            val lay = lays[i]
            c.save(); c.translate(x + pad, ty); lay.draw(c); c.restore()
            if (l.r != null) {
                val pr = bodyPaint(24f * scale, l.rc).apply { textAlign = Paint.Align.RIGHT }
                c.drawText(l.r, box.right - pad, ty - pr.ascent(), pr)
            }
            if (l.money >= 0) money(c, l.money, x + pad + pl.measureText(l.t) + 10f * scale, ty + lay.height / 2f, 24f * scale)
            ty += lay.height + 4f
        }
        if (layer) c.restore()
        return box
    }

    /**
     * THE TOOLTIPS' QUARTER SECOND (the user, 2026-09-30: "for all tooltips give them like a quarter second of load time, so that I can
     * see behind it momentarily and fade them in nicely"). One per view. [alpha] gives 0 for [DELAY] ms after [key] changes (a new
     * highlight; null = no tooltip), then ramps to 255 over [FADE] ms. While [animating] is true the view redraws itself
     * (postInvalidateOnAnimation). Pass the alpha to [tooltip], [tooltipHint] and [tooltipMore] alike.
     */
    class TipFade {
        private var key: Any? = null
        private var since = 0L
        var animating = false
            private set
        fun alpha(key: Any?, now: Long = SystemClock.uptimeMillis()): Int {
            if (key != this.key) { this.key = key; since = now }
            if (key == null) { animating = false; return 0 }
            val t = now - since - DELAY
            val a = if (t <= 0) 0 else if (t >= FADE) 255 else (255 * t / FADE).toInt()
            animating = a < 255
            return a
        }
        companion object { const val DELAY = 250L; const val FADE = 160L }
    }

    /** the compare ("Currently Equipped") beside an item's tooltip, as the game draws its shopping tooltip: its lines in the
     *  game's small tooltip font (10 against the main tooltip's 12, the name 12 against 14), so about five sixths the size */
    const val COMPARE_SCALE = 0.82f

    /**
     * THE HINT UNDER A TOOLTIP (the user: "if the Item is comparable, it should show a button under the tooltip saying X to compare"):
     * a small plate of the tooltip's own ground and border, joined under the tooltip [box] (x and width), the controller's [glyph]
     * and [text] in gold ("Compare"). Returns its box. Shown while X is NOT held; while held the comparison shows instead.
     */
    fun tooltipHint(c: Canvas, box: RectF, glyph: String, text: String, alpha: Int = 255): RectF {
        val r = RectF(box.left, box.bottom - 6f, box.right, box.bottom + 58f)
        if (alpha <= 0) return r
        val layer = alpha < 255
        if (layer) c.saveLayerAlpha(RectF(r.left - 4f, r.top - 4f, r.right + 4f, r.bottom + 4f), alpha)
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(250, 6, 6, 10)
        c.drawRect(r.left + 4f, r.top + 4f, r.right - 4f, r.bottom - 4f, pFlat)
        border(c, r, "border_tooltip", 18f, Color.rgb(120, 120, 120))
        val p = bodyPaint(24f, Theme.gold)
        val tw = 44f + 10f + p.measureText(text)
        val x0 = r.centerX() - tw / 2f
        pad(c, glyph, x0 + 22f, r.centerY() + 2f, 44f)
        c.drawText(text, x0 + 54f, r.centerY() + 2f - (p.ascent() + p.descent()) / 2f, p)
        if (layer) c.restore()
        return r
    }

    /**
     * THE ACTIONS UNDER A TOOLTIP (the Bags room, the user 2026-09-30: "reuse some of the tool tip stuff ... the button glyphs can be
     * part of those tool tips"): [tooltipHint]'s plate with SEVERAL glyph + word pairs ([actions]: glyph to word; a null glyph = a
     * word alone), spread evenly across the tooltip's width. Returns each pair's rectangle: those are the finger's buttons (the
     * tooltip itself takes no touch). [down] = the index pressed by a finger (that pair's cell darkened), -1 none.
     */
    fun tooltipActions(c: Canvas, box: RectF, actions: List<Pair<String?, String>>, alpha: Int = 255, down: Int = -1,
                       holdAt: Int = -1, hold: Float = 0f): List<RectF> {
        val r = RectF(box.left, box.bottom - 6f, box.right, box.bottom + 62f)
        val n = maxOf(1, actions.size)
        val cells = (0 until n).map { i -> RectF(r.left + r.width() * i / n, r.top, r.left + r.width() * (i + 1) / n, r.bottom) }
        if (alpha <= 0) return cells
        val layer = alpha < 255
        if (layer) c.saveLayerAlpha(RectF(r.left - 4f, r.top - 4f, r.right + 4f, r.bottom + 4f), alpha)
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(250, 6, 6, 10)
        c.drawRect(r.left + 4f, r.top + 4f, r.right - 4f, r.bottom - 4f, pFlat)
        if (down in cells.indices) { pFlat.color = Color.argb(160, 60, 50, 30); c.drawRect(cells[down].left + 4f, r.top + 4f, cells[down].right - 4f, r.bottom - 4f, pFlat) }
        border(c, r, "border_tooltip", 18f, Color.rgb(120, 120, 120))
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.argb(90, 120, 120, 120)
        for (i in 1 until n) c.drawRect(cells[i].left - 1f, r.top + 12f, cells[i].left + 1f, r.bottom - 12f, pFlat)
        for ((i, a) in actions.withIndex()) {
            val cell = cells[i]
            val p = bodyPaint(24f, Theme.gold)
            while (p.measureText(a.second) + (if (a.first != null) 50f else 0f) > cell.width() - 16f && p.textSize > 16f) p.textSize -= 1f
            val tw = (if (a.first != null) 42f + 8f else 0f) + p.measureText(a.second)
            val x0 = cell.centerX() - tw / 2f
            if (a.first != null) pad(c, a.first!!, x0 + 21f, cell.centerY() + 2f, 42f)
            if (i == holdAt && a.first != null) holdRing(c, x0 + 21f, cell.centerY() + 2f, 25f, hold)
            c.drawText(a.second, x0 + (if (a.first != null) 50f else 0f), cell.centerY() + 2f - (p.ascent() + p.descent()) / 2f, p)
        }
        if (layer) c.restore()
        return cells
    }

    /**
     * A HOLD ON A GLYPH (the user, 2026-10-01: "make the hold A to delete happen on the Item Button Glyph itself"): a gold ring round the
     * glyph centred at ([cx], [cy]), [radius] px, sweeping clockwise from the top as the button is held ([fraction] 0..1; full = done),
     * over a faint full ring that says "hold me" before the press. [tooltipActions]'s holdAt / hold draw it on an action's glyph.
     */
    fun holdRing(c: Canvas, cx: Float, cy: Float, radius: Float, fraction: Float) {
        val o = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 4f; pFlat.strokeCap = Paint.Cap.ROUND
        pFlat.color = Color.argb(90, 255, 209, 0); c.drawOval(o, pFlat)
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) { pFlat.color = Theme.gold; pFlat.strokeWidth = 5f; c.drawArc(o, -90f, 360f * f, false, pFlat) }
        pFlat.strokeCap = Paint.Cap.BUTT; pFlat.style = Paint.Style.FILL
    }

    /** the harvested cast-bar art (`ui/cast/<name>.png`, CastView's pieces), decoded once; null when it is not on the device */
    fun castArt(name: String): Bitmap? {
        castCache[name]?.let { return it }
        if (name in castMissing) return null
        val f = ArtHarvest.file("ui/cast/$name.png")
        val bmp = if (f.canRead()) BitmapFactory.decodeFile(f.path) else null
        if (bmp == null) castMissing.add(name) else castCache[name] = bmp
        return bmp
    }
    private val castCache = HashMap<String, Bitmap>()
    private val castMissing = HashSet<String>()

    private val pHold = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holdPos = FloatArray(2)
    private val holdPath = android.graphics.Path()

    /**
     * THE HOLD ON AN ITEM (f-delete-hold-polish; the user, 15 Oct: "I don't love the way that the hold to delete looks right now, it
     * could be a lot more polished"): over the item's tile [box], [f] 0..1 of the hold: a gold light sweeps round the item clockwise
     * from the top as the game's cooldown swipe does (a radial wipe, additive gold), a gold ring round the tile follows it, and a glow
     * round the tile builds with it.
     */
    fun holdTile(c: Canvas, box: RectF, f: Float) {
        val k = f.coerceIn(0f, 1f)
        if (k <= 0f) return
        // the glow that builds round the tile
        glow(c, RectF(box.left - 14f, box.top - 14f, box.right + 14f, box.bottom + 14f), SOFT_GOLD, 0.20f + 0.55f * k)
        // the radial wipe over the item, clipped to the tile
        val save = c.save()
        c.clipRect(box)
        val r = maxOf(box.width(), box.height())
        val o = RectF(box.centerX() - r, box.centerY() - r, box.centerX() + r, box.centerY() + r)
        holdPath.reset(); holdPath.moveTo(box.centerX(), box.centerY()); holdPath.arcTo(o, -90f, 360f * k); holdPath.close()
        pHold.style = Paint.Style.FILL; pHold.color = Color.argb((70 + 60 * k).toInt(), 255, 200, 70)
        pHold.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        c.drawPath(holdPath, pHold)
        pHold.xfermode = null
        c.restoreToCount(save)
        // the ring round the tile, with its bright head
        val rr = RectF(box.left - 6f, box.top - 6f, box.right + 6f, box.bottom + 6f)
        pHold.style = Paint.Style.STROKE; pHold.strokeCap = Paint.Cap.ROUND
        pHold.strokeWidth = 4f; pHold.color = Color.argb(70, 255, 209, 0); c.drawRoundRect(rr, 10f, 10f, pHold)
        // the gold runs ROUND THE TILE'S OWN EDGE, clockwise from the top middle (a circle over a square read wrong)
        pHold.strokeWidth = 5f; pHold.color = Theme.gold
        holdPath.reset(); holdPath.addRoundRect(rr, 10f, 10f, android.graphics.Path.Direction.CW)
        val pm = android.graphics.PathMeasure(holdPath, true)
        val len = pm.length
        // where the path starts is the platform's: find its point nearest the top middle (96 samples, only while held)
        var start = 0f; var best = Float.MAX_VALUE
        for (i in 0 until 96) {
            val d = len * i / 96f
            pm.getPosTan(d, holdPos, null)
            val dd = kotlin.math.abs(holdPos[0] - rr.centerX()) + kotlin.math.abs(holdPos[1] - rr.top) * 4f
            if (dd < best) { best = dd; start = d }
        }
        val seg = android.graphics.Path()
        val end = start + len * k
        pm.getSegment(start, minOf(end, len), seg, true)
        if (end > len) pm.getSegment(0f, end - len, seg, true)
        c.drawPath(seg, pHold)
        pHold.strokeCap = Paint.Cap.BUTT; pHold.style = Paint.Style.FILL
    }


    /**
     * THE ITEM BREAKS (f-delete-hold-polish, the user after the mock, 15 Oct: "for the Item buttons, I want them to have an almost
     * animated like jagged breaking in half and going grey and fading out"): the item [draw]n into [box] once per half, each half cut
     * along the same jagged crack down its middle; over [k] 0..1 of [ITEM_BREAK_MS] the halves part and drop, tilt away from each
     * other, lose their colour and fade out. What lies under [box] (the empty slot) shows through.
     */
    fun itemBreak(c: Canvas, box: RectF, k: Float, draw: (Canvas) -> Unit) {
        val t = k.coerceIn(0f, 1f)
        if (t >= 1f) return
        val e = t * t                                   // slow at first, then falling away
        val cx = box.centerX()
        // the crack: down the middle, jagged (six teeth), the same for both halves
        val teeth = 6
        val pts = ArrayList<Pair<Float, Float>>()
        for (i in 0..teeth) {
            val y = box.top + box.height() * i / teeth
            val x = cx + (if (i % 2 == 0) -1f else 1f) * box.width() * 0.07f * (if (i == 0 || i == teeth) 0.4f else 1f)
            pts += x to y
        }
        val cm = ColorMatrix().apply { setSaturation(1f - minOf(1f, t * 2.2f)) }
        pBreak.colorFilter = ColorMatrixColorFilter(cm)
        pBreak.alpha = (255 * (1f - e)).toInt().coerceIn(0, 255)
        for (leftHalf in booleanArrayOf(true, false)) {
            breakPath.reset()
            val edgeX = if (leftHalf) box.left - 4f else box.right + 4f
            breakPath.moveTo(edgeX, box.top - 4f)
            breakPath.lineTo(pts.first().first, box.top - 4f)
            for ((x, y) in pts) breakPath.lineTo(x, y)
            breakPath.lineTo(pts.last().first, box.bottom + 4f)
            breakPath.lineTo(edgeX, box.bottom + 4f)
            breakPath.close()
            val dir = if (leftHalf) -1f else 1f
            val save = c.saveLayer(RectF(box.left - box.width(), box.top - box.height(), box.right + box.width(), box.bottom + box.height() * 2f), pBreak)
            c.translate(dir * box.width() * 0.22f * e + dir * 2f * minOf(1f, t * 6f), box.height() * 0.35f * e)
            c.rotate(dir * 14f * e, if (leftHalf) box.left else box.right, box.bottom)
            c.clipPath(breakPath)
            draw(c)
            c.restoreToCount(save)
        }
        // the crack's bright line in its first moment
        val la = (1f - t / 0.3f).coerceIn(0f, 1f)
        if (la > 0f) {
            pHold.style = Paint.Style.STROKE; pHold.strokeWidth = 3f; pHold.color = Color.argb((230 * la).toInt(), 255, 236, 170)
            breakPath.reset(); breakPath.moveTo(pts.first().first, pts.first().second)
            for ((x, y) in pts) breakPath.lineTo(x, y)
            c.drawPath(breakPath, pHold)
            pHold.style = Paint.Style.FILL
        }
    }
    const val ITEM_BREAK_MS = 480L

    /**
     * THE DELETE LANDS (f-delete-hold-polish; the user: "it kind of freezes when its full right now. it needs like a flash with sparkles
     * complete animation"): over the tile [box], [k] 0..1 of [DELETE_BURST_MS]: a white-gold flash that fills the tile and fades, a
     * gold ring that widens out of it, and sparkles thrown outward (the harvested cast bar's sparkles under them when the device
     * has them; the sparks themselves are drawn here, no art needed).
     */
    fun deleteBurst(c: Canvas, box: RectF, k: Float) {
        val t = k.coerceIn(0f, 1f)
        if (t >= 1f) return
        val cx = box.centerX(); val cy = box.centerY(); val s = box.width()
        // the flash: full at once, gone by 60 %
        val fa = (1f - t / 0.6f).coerceIn(0f, 1f)
        if (fa > 0f) {
            val g = RadialGradient(cx, cy, s * 0.9f, intArrayOf(Color.argb((235 * fa).toInt(), 255, 250, 225),
                Color.argb((150 * fa).toInt(), 255, 205, 90), Color.argb(0, 255, 190, 60)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            pHold.style = Paint.Style.FILL; pHold.shader = g
            c.drawCircle(cx, cy, s * 0.9f, pHold)
            pHold.shader = null
        }
        // the ring that widens
        val e = 1f - (1f - t) * (1f - t)
        pHold.style = Paint.Style.STROKE; pHold.strokeWidth = 6f * (1f - t) + 1f
        pHold.color = Color.argb((220 * (1f - t)).toInt(), 255, 220, 120)
        c.drawCircle(cx, cy, s * (0.45f + 0.55f * e), pHold)
        pHold.style = Paint.Style.FILL
        // the game's sparkles (harvested), turning a little as they fade
        castArt("castbar_sparkles1")?.let { sp ->
            val a = (255 * (1f - t)).toInt()
            pBurstAdd.alpha = a
            c.save(); c.rotate(40f * t, cx, cy)
            // the streak keeps its own shape (48 x 15) and is ADDED as light, as the cast bar does: stretched to a square it read as a
            // pale block over the tile (f-loot-feedback's renders)
            val hw = s * (0.9f + 0.6f * e); val hh = hw * sp.height / sp.width.coerceAtLeast(1)
            c.drawBitmap(sp, null, RectF(cx - hw, cy - hh, cx + hw, cy + hh), pBurstAdd)
            c.restore()
            pBurstAdd.alpha = 255
        }
        // the sparks thrown outward: 10 small four-point stars
        for (i in 0 until 10) {
            val ang = Math.toRadians((i * 36.0 + 17.0 * (i % 3)))
            val dist = s * (0.30f + (0.55f + 0.08f * (i % 4)) * e)
            val x = cx + (Math.cos(ang) * dist).toFloat(); val y = cy + (Math.sin(ang) * dist).toFloat()
            val sz = s * (0.10f - 0.02f * (i % 3)) * (1f - t * 0.7f)
            pHold.color = Color.argb((255 * (1f - t)).toInt(), 255, 236, 150)
            holdPath.reset()
            holdPath.moveTo(x, y - sz); holdPath.lineTo(x + sz * 0.25f, y - sz * 0.25f); holdPath.lineTo(x + sz, y)
            holdPath.lineTo(x + sz * 0.25f, y + sz * 0.25f); holdPath.lineTo(x, y + sz); holdPath.lineTo(x - sz * 0.25f, y + sz * 0.25f)
            holdPath.lineTo(x - sz, y); holdPath.lineTo(x - sz * 0.25f, y - sz * 0.25f); holdPath.close()
            c.drawPath(holdPath, pHold)
        }
    }
    const val DELETE_BURST_MS = 420L
    private val pBurstAdd = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val pBreak = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val breakPath = android.graphics.Path()

    /**
     * MORE BELOW (a tooltip stack taller than the window, while it scrolls): over the visible stack's last [h] px above [bottom],
     * the tooltip's ground fades in, and a plate with the scroll glyphs ([glyphs], e.g. "gen_down" and "gen_rstickdown") and "More"
     * sits on it. Draw it last, over the stack. [left] / [right] = the stack's sides.
     */
    fun tooltipMore(c: Canvas, left: Float, right: Float, bottom: Float, glyphs: List<String>, h: Float = 70f, alpha: Int = 255) {
        if (alpha <= 0) return
        // clear at the top, solid from 55 % down: the glyphs and "More" never sit on readable lines
        val solid = Color.argb(alpha * 250 / 255, 6, 6, 10)
        val fade = LinearGradient(0f, bottom - h, 0f, bottom, intArrayOf(Color.argb(0, 6, 6, 10), solid, solid), floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP)
        pFlat.style = Paint.Style.FILL; pFlat.shader = fade
        c.drawRect(left + 4f, bottom - h, right - 4f, bottom, pFlat)
        pFlat.shader = null
        val p = bodyPaint(22f, Theme.gold).apply { this.alpha = alpha }
        val tw = glyphs.size * 42f + 8f + p.measureText("More")
        var x = (left + right) / 2f - tw / 2f
        for (g in glyphs) { pad(c, g, x + 20f, bottom - 26f, 40f, alpha); x += 42f }
        c.drawText("More", x + 8f, bottom - 26f - (p.ascent() + p.descent()) / 2f, p)
    }

    // ---------------------------------------------------------------- the app's own face
    //
    // User picks 2026-09-29: the name "DS Forever" ("DSwow or DS forever with like wow and ds
    // Mashup branding"), the game's own loading pictures behind the start and the loading
    // screens ([Scene]), one line of words. The lettering is drawn here: "DS" in the device's
    // heavy slanted sans, the game's part in the game's title type from the kit folder. No
    // company's logo file is used, and nothing of the game's is packed into the app.

    private val heavy: Typeface by lazy { Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC) }

    /** words with a dark edge round them, centred on ([cx], [cy]): they stand on a picture */
    fun outlined(c: Canvas, words: String, cx: Float, cy: Float, size: Float, colour: Int, edge: Float = 3f) {
        val p = bodyPaint(size, colour, shadow = false).apply { textAlign = Paint.Align.CENTER }
        val y = cy - (p.ascent() + p.descent()) / 2f
        p.style = Paint.Style.STROKE; p.strokeWidth = edge * 2f; p.strokeJoin = Paint.Join.ROUND; p.color = Color.rgb(10, 8, 6)
        c.drawText(words, cx, y, p)
        p.style = Paint.Style.FILL; p.color = colour
        c.drawText(words, cx, y, p)
    }

    /**
     * THE NAME, "DS Forever", centred on [cx] with its letters standing on [base] + 50 k:
     * "DS" heavy and slanted in a pale steel, "Forever" in the game's title type in gold, both
     * with a dark edge; under "DS" the two screens, the lower one lit. [k] = 1 on the start
     * screen (the letters 150 px), 0.42 at the foot of a room that waits.
     */
    fun lettering(c: Canvas, cx: Float, base: Float, k: Float = 1f) {
        val pa = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = heavy; textSize = 150f * k }
        val pb = titlePaint(132f * k, Theme.gold, shadow = false)
        val gap = 26f * k
        val wa = pa.measureText("DS"); val wb = pb.measureText("Forever")
        val x = cx - (wa + gap + wb) / 2f
        val y = base + 50f * k
        for ((p, words, at, colour) in listOf(Quad(pa, "DS", x, Color.rgb(214, 222, 232)), Quad(pb, "Forever", x + wa + gap, Theme.gold))) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 14f * k; p.strokeJoin = Paint.Join.ROUND; p.color = Color.rgb(10, 8, 6)
            c.drawText(words, at, y, p)
            p.style = Paint.Style.FILL; p.color = colour
            c.drawText(words, at, y, p)
        }
        val sx = x + wa / 2f
        val y0 = y + 22f * k
        val w2 = 74f * k; val h2 = 26f * k
        for ((i, fill) in listOf(Color.rgb(30, 34, 42), Color.rgb(232, 176, 28)).withIndex()) {
            val r = RectF(sx - w2 / 2f, y0 + i * (h2 + 6f * k), sx + w2 / 2f, y0 + i * (h2 + 6f * k) + h2)
            pFlat.style = Paint.Style.FILL; pFlat.color = fill
            c.drawRoundRect(r, 5f * k, 5f * k, pFlat)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 2f * k
            pFlat.color = if (i == 0) Color.rgb(150, 150, 160) else Color.rgb(255, 232, 150)
            c.drawRoundRect(r, 5f * k, 5f * k, pFlat)
        }
    }

    private data class Quad(val paint: Paint, val words: String, val x: Float, val colour: Int)

    /**
     * THE GROUND OF A ROOM THAT WAITS for its first word from the game, over [r]: the scene of
     * where the character is, [words] in its middle ("Waiting for your quests"), the name small
     * at its foot. The room draws its own header over it.
     */
    fun waiting(c: Canvas, r: RectF, words: String) {
        Scene.draw(c, r)
        outlined(c, words, r.centerX(), r.top + r.height() * 0.48f, 36f, Theme.text)
        lettering(c, r.centerX(), r.bottom - 190f, 0.42f)
    }

    // ---------------------------------------------------------------- the game's tracking bars
    //
    // The experience bar and the reputation bar, this client's own art (ui-hud-experiencebar-*-c60).
    // User request and pick 2026-09-28: two bars on the bottom bar's top edge, reputation over
    // experience, passing behind HOME.

    /**
     * ONE TRACKING BAR over [r]: its ground, the fill up to [fill] (0..1), its segments, its frame.
     * [kind] names the fill: "xp" (purple), "rested" (blue: experience while rested), or a
     * reputation's colour "red", "orange", "yellow", "green", "blue". [reach] (0..1, 0 = none) is
     * where the rested bonus ends: the game's darker fill up to it, and its mark. With [fill] < 0
     * the bar is an empty frame (nothing to show: the top level, no faction watched).
     *
     * [gap0] .. [gap1] (x, 0 = none) is a stretch that something covers (HOME's button): the bar
     * passes behind it but its SCALE does not. The first half of the value lies left of the gap
     * and the second half right of it, so the button never hides where the bar stands.
     *
     * [gapPx] > 0: the segments stand APART, a dark gap of that width between them (the user, 2026-09-29: "we can make the gaps
     * between bars larger for like 10 total bars"; then "I dont like how chonky the tick marks on the XP bar": no pips on a gap).
     * [gapEdge] > 0: a hairline of that width in the frame's bronze on each edge of a gap; 0 = the gap alone. [gapPx] 0 = the
     * game's single pip.
     */
    fun track(c: Canvas, r: RectF, kind: String, fill: Float, reach: Float = 0f, segments: Int = 20,
              gap0: Float = 0f, gap1: Float = 0f, gapPx: Float = 0f, gapEdge: Float = 0f) {
        val gap = gap1 > gap0
        fun at(v: Float): Float {
            val t = v.coerceIn(0f, 1f)
            if (!gap) return r.left + r.width() * t
            return if (t <= 0.5f) r.left + (gap0 - r.left) * t * 2f else gap1 + (r.right - gap1) * (t - 0.5f) * 2f
        }
        val ground = tex("track_ground")
        if (ground == null) {                                          // no art: flat
            pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(20, 18, 20)
            c.drawRect(r, pFlat)
            if (fill >= 0f) {
                pFlat.color = when (kind) {
                    "xp" -> Color.rgb(150, 70, 170); "rested" -> Color.rgb(60, 90, 220); "red" -> Color.rgb(200, 50, 40)
                    "orange" -> Color.rgb(230, 110, 40); "yellow" -> Color.rgb(200, 160, 20); "blue" -> Color.rgb(30, 170, 230)
                    else -> Color.rgb(50, 190, 60)
                }
                c.drawRect(r.left, r.top + 2f, at(fill), r.bottom - 2f, pFlat)
            }
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 1.5f; pFlat.color = Theme.bronzeDim
            c.drawRect(r, pFlat)
            return
        }
        ends(c, ground, r)
        if (fill >= 0f) {
            if (reach > fill) tex("track_reach")?.let {
                c.save(); c.clipRect(r.left, r.top, at(reach), r.bottom); ends(c, it, r); c.restore()
            }
            (tex("track_$kind") ?: tex("track_green"))?.let {
                c.save(); c.clipRect(r.left, r.top, at(fill), r.bottom); ends(c, it, r); c.restore()
            }
        }
        tex("track_pip")?.let { pip ->
            val ph = r.height() * 28f / 26f + 1f
            val pw = maxOf(3f, pip.width * r.height() / 26f)
            for (i in 1 until segments) {
                if (gap && i * 2 == segments) continue                 // that mark would stand behind the button
                val x = at(i.toFloat() / segments)
                if (gapPx > 0f) {
                    // the segments apart: the bar's dark ground in the gap; at most a hairline on its edges (no pips: "chonky")
                    pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(10, 8, 6)
                    c.drawRect(x - gapPx / 2f, r.top + 2f, x + gapPx / 2f, r.bottom - 2f, pFlat)
                    if (gapEdge > 0f) {
                        pFlat.color = Theme.bronze
                        for (ex in floatArrayOf(x - gapPx / 2f, x + gapPx / 2f - gapEdge))
                            c.drawRect(ex, r.top + 3f, ex + gapEdge, r.bottom - 3f, pFlat)
                    }
                } else
                    c.drawBitmap(pip, null, RectF(x - pw / 2f, r.centerY() - ph / 2f, x + pw / 2f, r.centerY() + ph / 2f), pBmp)
            }
        }
        tex("track_frame")?.let { ends(c, it, r) }
        if (fill >= 0f && reach > fill) {                              // the game's mark where the rested bonus ends
            val x = at(reach)
            val p = android.graphics.Path().apply {
                moveTo(x - 7f, r.top - 2f); lineTo(x + 7f, r.top - 2f); lineTo(x, r.top + 9f); close()
            }
            pFlat.style = Paint.Style.FILL; pFlat.color = Color.rgb(90, 150, 255)
            c.drawPath(p, pFlat)
            pFlat.style = Paint.Style.STROKE; pFlat.strokeWidth = 1.5f; pFlat.color = Color.rgb(10, 20, 60)
            c.drawPath(p, pFlat)
        }
    }

    /** a bar piece over [r]: its two ends (44 px of the art) keep their shape, its middle stretches */
    private fun ends(c: Canvas, b: Bitmap, r: RectF) {
        val cap = 44
        val k = r.height() / b.height
        val cw = cap * k
        c.drawBitmap(b, Rect(0, 0, cap, b.height), RectF(r.left, r.top, r.left + cw, r.bottom), pBmp)
        c.drawBitmap(b, Rect(b.width - cap, 0, b.width, b.height), RectF(r.right - cw, r.top, r.right, r.bottom), pBmp)
        c.drawBitmap(b, Rect(cap, 0, b.width - cap, b.height), RectF(r.left + cw - 0.5f, r.top, r.right - cw + 0.5f, r.bottom), pBmp)
    }

    /** a texture tiled over [r], darkened to [light] (1 = as it is) */
    private fun ground(c: Canvas, r: RectF, name: String, light: Float) {
        val b = tex(name)
        if (b == null) {
            pFlat.style = Paint.Style.FILL; pFlat.color = Theme.ink
            c.drawRect(r, pFlat); return
        }
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.shader = android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        p.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(light, light, light, 1f) })
        c.drawRect(r, p)
    }

    /** an edge piece repeated from its start to [to], across or down, at the frame's scale */
    private fun repeat(c: Canvas, b: Bitmap, x: Float, y: Float, to: Float, across: Boolean, k: Float = FRAME) {
        val w = b.width * k; val h = b.height * k
        c.save()
        if (across) {
            c.clipRect(x, y, to, y + h)
            var px = x
            while (px < to) { c.drawBitmap(b, null, RectF(px, y, px + w, y + h), pBmp); px += w }
        } else {
            c.clipRect(x, y, x + w, to)
            var py = y
            while (py < to) { c.drawBitmap(b, null, RectF(x, py, x + w, py + h), pBmp); py += h }
        }
        c.restore()
    }

    // ---------------------------------------------------------------- loading

    private val pRed = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        // an item the character cannot use: the game turns its icon red
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(1f, 0.35f, 0.35f, 1f) })
    }
    private val pGrey = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        // an item that cannot be had: grey and dark, the way the game greys a sold-out one
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(0.15f)
            postConcat(ColorMatrix().apply { setScale(0.5f, 0.5f, 0.5f, 1f) })
        })
    }
    private val pDim = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        // a picture that is not the active one: the same picture, turned down
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.45f, 0.45f, 0.45f, 1f) })
    }
    private val pFaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        // a spell that is not known yet: grey and faint, as the game's book shows it
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(0f)
            postConcat(ColorMatrix().apply { setScale(0.9f, 0.9f, 0.9f, 1f) })
        })
    }
    private val pMask = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val pTint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pLit = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // the panes' grounds are very dark art, made for a lit monitor: half as bright again on this panel
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(1.5f, 1.5f, 1.5f, 1f) })
    }
    private val pCard = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(1.7f, 1.7f, 1.7f, 1f) })
    }
    private val pEmblem = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(1.9f, 1.9f, 1.9f, 1f) })
    }
    private val pPaper = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY) }
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pGlow = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val pFlat = Paint(Paint.ANTI_ALIAS_FLAG)

    private val fonts = HashMap<String, Typeface>()
    private val texCache = HashMap<String, Bitmap>()
    private val tileCache = HashMap<String, Array<Bitmap>>()
    private val missing = HashMap<String, Long>()

    /** a missing file is looked for again every few seconds, so pushing the kit while the app runs just works */
    private fun retry(name: String): Boolean {
        val now = SystemClock.uptimeMillis()
        val last = missing[name]
        if (last != null && now - last < 5000) return false
        missing[name] = now
        return true
    }

    @Synchronized
    private fun font(file: String, fallback: Typeface): Typeface {
        fonts[file]?.let { return it }
        if (!retry(file)) return fallback
        return try {
            val f = ArtHarvest.file("fonts/$file")
            if (!f.canRead()) return fallback
            Typeface.createFromFile(f).also { fonts[file] = it; missing.remove(file) }
        } catch (t: Throwable) {
            fallback
        }
    }

    /**
     * An edge file cut into its eight tiles. The top and bottom tiles are stored on their side
     * (the way the game's backdrop code expects), so they are turned a quarter clockwise here.
     */
    @Synchronized
    private fun tiles(name: String): Array<Bitmap>? {
        tileCache[name]?.let { return it }
        val src = tex(name) ?: return null
        val w = src.width / 8
        val quarter = Matrix().apply { postRotate(90f) }
        val out = Array(8) { i ->
            if (i == 2 || i == 3) Bitmap.createBitmap(src, i * w, 0, w, src.height, quarter, true)
            else Bitmap.createBitmap(src, i * w, 0, w, src.height)
        }
        tileCache[name] = out
        return out
    }

    @Synchronized
    private fun forget() {
        fonts.clear(); texCache.clear(); tileCache.clear(); missing.clear(); tabEdges.clear(); glyphInk.clear()
    }
}
