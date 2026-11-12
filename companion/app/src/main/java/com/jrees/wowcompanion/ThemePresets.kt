package com.jrees.wowcompanion

/**
 * THE THEMES (the user, 2026-11-12: "I personally want an oled theme, but different color variations for menus like pink and silver or
 * gold instead of the classic bronze. I want you to work on compiling like 10 good options for me to review in the morning").
 *
 * A preset is a set of chrome colours ([ThemeSpec]) plus how the harvested art's metal is recoloured ([Metal]). Plain ARGB ints, no
 * Android calls, so the model is tested off-device. Classic Bronze holds exactly the colours the app always had (it is the default).
 * OLED presets put pure black (#000000) under every large surface and drop the rock / marble textures; Dark presets keep the textures,
 * dimmed and cooled. The game's own colours (item quality, class, quest difficulty, parchment ink) are not here: they never change.
 */
data class ThemeSpec(
    val id: String,
    /** the picker's words: terse */
    val name: String,
    val ink: Int, val field: Int, val panel: Int, val raised: Int, val selected: Int,
    /** headings, button / tab labels, the active tab */
    val accent: Int,
    /** rules and borders, and their dim hairline */
    val edge: Int, val edgeDim: Int,
    val text: Int, val textDim: Int, val dim: Int, val warn: Int,
    /** the focus box's glow and the soft cursor wash */
    val focus: Int, val soft: Int,
    /** how bright the rock / marble / pane grounds are drawn (1 = as harvested; 0 = not drawn: the page's ink, OLED) */
    val groundLight: Float,
    /** the grounds' saturation (1 = as harvested; 0 = grey stone) */
    val groundSat: Float,
    /** the harvested art's metal recoloured, null = as harvested (bronze) */
    val metal: Metal?,
) {
    val oled: Boolean get() = groundLight == 0f
}

/**
 * The art's metal recoloured, keeping its shading: a pixel whose hue lies in the bronze / gold band (about 20..56 degrees, soft edges)
 * and that has some colour gets [hue] (plus a third of its own offset from the band's middle, so a gradient keeps its warmth steps), its
 * saturation times [sat] and its brightness through `v^curve * gain`. Red cloth, blue gems, grey stone and black stay as they are.
 */
data class Metal(val hue: Float, val sat: Float, val gain: Float, val curve: Float = 1f) {

    /** [px] (ARGB, as Bitmap.getPixels gives them) recoloured in place */
    fun apply(px: IntArray) {
        val hsv = FloatArray(3)
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0) continue
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            rgbToHsv(r, g, b, hsv)
            val w = weight(hsv[0], hsv[1])
            if (w <= 0f) continue
            val h2 = ((hue + (hsv[0] - MID) / 3f) % 360f + 360f) % 360f
            val s2 = (hsv[1] * sat).coerceIn(0f, 1f)
            val v2 = (Math.pow(hsv[2].toDouble(), curve.toDouble()).toFloat() * gain).coerceIn(0f, 1f)
            val n = hsvToRgb(h2, s2, v2)
            val nr = (n shr 16) and 0xFF; val ng = (n shr 8) and 0xFF; val nb = n and 0xFF
            px[i] = (a shl 24) or (mix(r, nr, w) shl 16) or (mix(g, ng, w) shl 8) or mix(b, nb, w)
        }
    }

    companion object {
        private const val MID = 38f

        /** how much a pixel of hue [h] (degrees) and saturation [s] is metal: 0 = not at all, 1 = fully */
        fun weight(h: Float, s: Float): Float {
            val band = when {
                h < 12f || h > 66f -> 0f
                h < 20f -> (h - 12f) / 8f
                h > 56f -> (66f - h) / 10f
                else -> 1f
            }
            if (band <= 0f) return 0f
            val sw = ((s - 0.08f) / 0.12f).coerceIn(0f, 1f)
            return band * sw
        }

        private fun mix(a: Int, b: Int, w: Float) = (a + (b - a) * w + 0.5f).toInt().coerceIn(0, 255)

        fun rgbToHsv(r: Int, g: Int, b: Int, out: FloatArray) {
            val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
            val mx = maxOf(rf, gf, bf); val mn = minOf(rf, gf, bf); val d = mx - mn
            val h = when {
                d == 0f -> 0f
                mx == rf -> 60f * (((gf - bf) / d) % 6f)
                mx == gf -> 60f * ((bf - rf) / d + 2f)
                else -> 60f * ((rf - gf) / d + 4f)
            }
            out[0] = if (h < 0f) h + 360f else h
            out[1] = if (mx == 0f) 0f else d / mx
            out[2] = mx
        }

        fun hsvToRgb(h: Float, s: Float, v: Float): Int {
            val c = v * s
            val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
            val m = v - c
            val (r, g, b) = when ((h / 60f).toInt() % 6) {
                0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
            }
            fun ch(f: Float) = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }
    }
}

object ThemePresets {
    private fun c(hex: Long) = hex.toInt()

    // the metals
    private val GOLD = Metal(hue = 46f, sat = 1.3f, gain = 1.18f)
    private val SILVER = Metal(hue = 215f, sat = 0.07f, gain = 1.12f, curve = 0.88f)
    private val ROSE_GOLD = Metal(hue = 12f, sat = 0.62f, gain = 1.12f, curve = 0.95f)
    private val ROSE = Metal(hue = 330f, sat = 0.55f, gain = 1.1f, curve = 0.92f)
    private val FROST = Metal(hue = 205f, sat = 0.32f, gain = 1.14f, curve = 0.9f)

    val CLASSIC = ThemeSpec("classic", "Classic Bronze",
        ink = c(0xFF0E0B08), field = c(0xFF141009), panel = c(0xFF17120C), raised = c(0xFF1B1610), selected = c(0xFF2E2411),
        accent = c(0xFFFFD100), edge = c(0xFFC8AA6E), edgeDim = c(0xFF4A3A1C),
        text = c(0xFFE8DCC0), textDim = c(0xFFB3A48A), dim = c(0xFF8F8168), warn = c(0xFFD99A4E),
        focus = c(0xFFFFD100), soft = c(0xFFE2AA50), groundLight = 1f, groundSat = 1f, metal = null)

    val OLED_BRONZE = ThemeSpec("oled_bronze", "OLED Bronze",
        ink = c(0xFF000000), field = c(0xFF000000), panel = c(0xFF000000), raised = c(0xFF100C08), selected = c(0xFF2C2210),
        accent = c(0xFFFFD100), edge = c(0xFFC8AA6E), edgeDim = c(0xFF4A3A1C),
        text = c(0xFFE8DCC0), textDim = c(0xFFB3A48A), dim = c(0xFF8F8168), warn = c(0xFFD99A4E),
        focus = c(0xFFFFD100), soft = c(0xFFE2AA50), groundLight = 0f, groundSat = 1f, metal = null)

    val OLED_GOLD = ThemeSpec("oled_gold", "OLED Gold",
        ink = c(0xFF000000), field = c(0xFF000000), panel = c(0xFF000000), raised = c(0xFF110E05), selected = c(0xFF2F2507),
        accent = c(0xFFFFD84A), edge = c(0xFFE6C25A), edgeDim = c(0xFF5C4A14),
        text = c(0xFFF2E8CE), textDim = c(0xFFC4B690), dim = c(0xFF9C8D68), warn = c(0xFFE0A050),
        focus = c(0xFFFFDC40), soft = c(0xFFF0C048), groundLight = 0f, groundSat = 1f, metal = GOLD)

    val OLED_SILVER = ThemeSpec("oled_silver", "OLED Silver",
        ink = c(0xFF000000), field = c(0xFF000000), panel = c(0xFF000000), raised = c(0xFF0E0F11), selected = c(0xFF24272C),
        accent = c(0xFFEAEDF2), edge = c(0xFFB8BFC9), edgeDim = c(0xFF3D424A),
        text = c(0xFFE6E8EB), textDim = c(0xFFB5BAC1), dim = c(0xFF8E939B), warn = c(0xFFE0A860),
        focus = c(0xFFF2F5FA), soft = c(0xFFC8D0DC), groundLight = 0f, groundSat = 1f, metal = SILVER)

    // the user's "pink and silver": silver metal, a pink accent
    val OLED_ROSE = ThemeSpec("oled_rose", "OLED Rose",
        ink = c(0xFF000000), field = c(0xFF000000), panel = c(0xFF000000), raised = c(0xFF110C0F), selected = c(0xFF331A28),
        accent = c(0xFFFF8CC6), edge = c(0xFFC3C8D0), edgeDim = c(0xFF444850),
        text = c(0xFFF0E6EB), textDim = c(0xFFC4B5BD), dim = c(0xFF9C8C95), warn = c(0xFFE0A860),
        focus = c(0xFFFF8CC6), soft = c(0xFFF0A0C8), groundLight = 0f, groundSat = 1f, metal = SILVER)

    val OLED_ROSE_GOLD = ThemeSpec("oled_rose_gold", "OLED Rose Gold",
        ink = c(0xFF000000), field = c(0xFF000000), panel = c(0xFF000000), raised = c(0xFF120D0B), selected = c(0xFF331F19),
        accent = c(0xFFF7B9A6), edge = c(0xFFDDA48F), edgeDim = c(0xFF563A30),
        text = c(0xFFF2E4DE), textDim = c(0xFFC7B2A9), dim = c(0xFF9E8A82), warn = c(0xFFE0A050),
        focus = c(0xFFFFC2B0), soft = c(0xFFE8A890), groundLight = 0f, groundSat = 1f, metal = ROSE_GOLD)

    val OLED_FROST = ThemeSpec("oled_frost", "OLED Frost",
        ink = c(0xFF000000), field = c(0xFF000000), panel = c(0xFF000000), raised = c(0xFF0B0E11), selected = c(0xFF182632),
        accent = c(0xFFA8DCFF), edge = c(0xFFB4C6D6), edgeDim = c(0xFF33414E),
        text = c(0xFFE4EDF4), textDim = c(0xFFAFC0CD), dim = c(0xFF8698A7), warn = c(0xFFE0A860),
        focus = c(0xFFB8E4FF), soft = c(0xFF90C8F0), groundLight = 0f, groundSat = 1f, metal = FROST)

    val DARK_GOLD = ThemeSpec("dark_gold", "Dark Gold",
        ink = c(0xFF0B0A08), field = c(0xFF0F0D0A), panel = c(0xFF12100C), raised = c(0xFF19160F), selected = c(0xFF2E2510),
        accent = c(0xFFFFD84A), edge = c(0xFFE6C25A), edgeDim = c(0xFF5C4A14),
        text = c(0xFFF0E6CC), textDim = c(0xFFC2B48E), dim = c(0xFF9C8D68), warn = c(0xFFE0A050),
        focus = c(0xFFFFDC40), soft = c(0xFFF0C048), groundLight = 0.6f, groundSat = 0.6f, metal = GOLD)

    val DARK_SILVER = ThemeSpec("dark_silver", "Dark Silver",
        ink = c(0xFF0C0D0F), field = c(0xFF101113), panel = c(0xFF131417), raised = c(0xFF1A1C1F), selected = c(0xFF2B2F35),
        accent = c(0xFFEAEDF2), edge = c(0xFFB8BFC9), edgeDim = c(0xFF3D424A),
        text = c(0xFFE6E8EB), textDim = c(0xFFB5BAC1), dim = c(0xFF8E939B), warn = c(0xFFE0A860),
        focus = c(0xFFF2F5FA), soft = c(0xFFC8D0DC), groundLight = 0.7f, groundSat = 0f, metal = SILVER)

    val DARK_ROSE = ThemeSpec("dark_rose", "Dark Rose",
        ink = c(0xFF0F0B0D), field = c(0xFF130E11), panel = c(0xFF171114), raised = c(0xFF1E161A), selected = c(0xFF3A2030),
        accent = c(0xFFFF9AD0), edge = c(0xFFD9A3BE), edgeDim = c(0xFF55334A),
        text = c(0xFFF0E4EA), textDim = c(0xFFC6B3BD), dim = c(0xFF9E8B95), warn = c(0xFFE0A860),
        focus = c(0xFFFF9AD0), soft = c(0xFFF0A8CC), groundLight = 0.6f, groundSat = 0.25f, metal = ROSE)

    /** the picker's order: the default first, the OLED family, then the Dark family */
    val ALL = listOf(CLASSIC, OLED_BRONZE, OLED_GOLD, OLED_SILVER, OLED_ROSE, OLED_ROSE_GOLD, OLED_FROST, DARK_GOLD, DARK_SILVER, DARK_ROSE)

    /**
     * THE ART WHOSE METAL A THEME RECOLOURS (kit names, extract_kit.py's): frames, borders, tabs, rings, the focus frames, the book's
     * frames, rules and bands, and the trim of the red buttons (their cloth is red: outside the metal's hue band, it stays). Never an
     * icon, a portrait, parchment, a ground, an item-quality border (loot_border_*) nor a picture of the game's own world.
     */
    private val METAL_PREFIXES = listOf("border_gold", "border_tooltip", "frame_", "dialog_", "tab_left", "tab_center", "tab_right",
        "tab_on_", "small_tab", "side_tab", "ring_square", "home_ring", "radial_ring", "radial_place", "focus_frame", "focus_gold",
        "book_frame", "book_tab", "book_divider", "book_round", "list_heading", "list_line_on", "pane_divider", "pane_rule",
        "section_title", "section_band", "loot_card_edge", "bar_border", "track_frame", "check_box", "menu_radio", "scroll_",
        "prof_frame", "skill_frame", "set_frame", "set_card_on", "recipe_head_left", "recipe_head_middle", "recipe_head_right",
        "search_left", "search_mid", "search_right", "chat_input_", "zone_band", "button_", "square_", "drop_button", "talent_tab")

    fun isMetal(name: String): Boolean = name != "side_tab_mask" && METAL_PREFIXES.any { name.startsWith(it) }

    // ---- contrast (WCAG's relative luminance), for the presets' check
    private fun lin(ch: Int): Double { val s = ch / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
    fun luminance(c: Int): Double = 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
