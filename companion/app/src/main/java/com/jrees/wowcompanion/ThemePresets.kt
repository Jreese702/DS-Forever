package com.jrees.wowcompanion

/**
 * THE THEMES (the user, 2026-11-12: "I personally want an oled theme, but different color variations for menus like pink and silver or
 * gold instead of the classic bronze. I want you to work on compiling like 10 good options for me to review in the morning").
 *
 * A preset is a set of chrome colours ([ThemeSpec]) plus how the harvested art's metal is recoloured ([Metal]). Plain ARGB ints, no
 * Android calls, so the model is tested off-device. Classic Bronze holds exactly the colours the app always had (it is the default).
 * OLED presets put pure black (#000000) under every large surface and draw the rooms' art dark over it (beautify 8, 2026-11-17: never
 * plain black); Dark presets keep the textures, dimmed and cooled. The game's own colours (item quality, class, quest difficulty, parchment ink) are not here: they never change.
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
    /** the words on the game's RED cloth buttons: gold on red is the game's own; a pink or ice accent on red clashes, so its own */
    val btnLabel: Int,
    /**
     * HOW THE ROOMS' ART IS DRAWN (beautify 8.1; the user, 2026-11-17: "the background on some of the OLED themes look lifeless, like the
     * macro and friend room, I do really like having the background image there"): [stoneLight] = the rock / marble grounds' light,
     * [paneLight] = the list / detail panes' (pane_left / pane_right, prof_ground, the Settings and Controller grounds); 1 = as harvested.
     * OLED no longer means "no art": it is drawn dark and its blackest values crushed to true #000 ([UiKit.groundMatrix])
     */
    val stoneLight: Float, val paneLight: Float,
    /** the grounds' saturation (1 = as harvested; 0 = grey stone) */
    val artSat: Float,
    /** the edge darkness over a ground (0 = none .. 1 = the page's ink in the corners) */
    val vignette: Float,
    /** ink = field = panel = #000 (the OLED family); it no longer drops the art */
    val oled: Boolean,
    /** the harvested art's metal recoloured, null = as harvested (bronze) */
    val metal: Metal?,
    /** the theme maker's picks this theme lies nearest (the maker starts from the theme in use: beautify 9.6) */
    val picks: ThemeMaker.Picks? = null,
)

/**
 * The art's metal recoloured, keeping its shading: a pixel whose hue lies in the bronze / gold band (about 20..56 degrees, soft edges)
 * and that has some colour gets [hue] (plus a third of its own offset from the band's middle, so a gradient keeps its warmth steps), its
 * saturation times [sat] and its brightness through `v^curve * gain`. Red cloth, blue gems, grey stone and black stay as they are.
 */
data class Metal(val hue: Float, val sat: Float, val gain: Float, val curve: Float = 1f) {

    /** the brightness curve per 8-bit value (a pixel's HSV value is its brightest channel / 255): no pow per pixel */
    private val vLut: FloatArray by lazy {
        FloatArray(256) { i -> val v = i / 255f; ((if (curve == 1f) v else Math.pow(v.toDouble(), curve.toDouble()).toFloat()) * gain).coerceIn(0f, 1f) }
    }

    /** [px] (ARGB, as Bitmap.getPixels gives them) recoloured in place */
    fun apply(px: IntArray) {
        val lut = vLut
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
            val v2 = lut[maxOf(r, g, b)]
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

    // the metals (the maker's Metal row holds them all)
    private val GOLD = ThemeMaker.METAL_GOLD
    private val SILVER = ThemeMaker.METAL_SILVER
    private val ROSE_GOLD = ThemeMaker.METAL_ROSE_GOLD
    private val ROSE = ThemeMaker.METAL_ROSE
    private val FROST = ThemeMaker.METAL_FROST

    // the art levels (beautify 8.2): Classic as harvested; the Dark family dimmed; the OLED family dark with its blacks crushed.
    // OLED at stone 0.40 / pane 0.60, not the design's 0.22 / 0.30: the kit's panes are very dark art (pane_left's mean 15 / 9 / 3), and at
    // 0.30 the Macros and Social lists rendered pure black, the look the user called lifeless (user pick 9 shows 0.30 / 0.45, 0.40 / 0.60
    // and 0.50 / 0.75 side by side)
    const val OLED_STONE = 0.40f
    const val OLED_PANE = 0.60f
    private const val OLED_SAT = 0.85f
    private const val OLED_VIG = 0.80f
    private const val DARK_VIG = 0.35f
    private fun p(accent: Long, metal: String, tint: Int, shade: Int, text: Long, oled: Boolean, art: ThemeMaker.Art = ThemeMaker.Art.MID) =
        ThemeMaker.Picks(c(accent), metal, ThemeMaker.TINTS[tint].hue, ThemeMaker.TINTS[tint].sat, ThemeMaker.SHADES[shade].l, c(text), oled, art)

    val CLASSIC = ThemeSpec("classic", "Classic Bronze",
        ink = c(0xFF0E0B08), field = c(0xFF141009), panel = c(0xFF17120C), raised = c(0xFF1B1610), selected = c(0xFF2E2411),
        accent = c(0xFFFFD100), edge = c(0xFFC8AA6E), edgeDim = c(0xFF4A3A1C),
        text = c(0xFFE8DCC0), textDim = c(0xFFB3A48A), dim = c(0xFF8F8168), warn = c(0xFFD99A4E),
        focus = c(0xFFFFD100), soft = c(0xFFE2AA50), btnLabel = c(0xFFFFD100),
        stoneLight = 1f, paneLight = 1f, artSat = 1f, vignette = 0f, oled = false, metal = null,
        picks = p(0xFFFFD100, "bronze", 0, 1, 0xFFE8DCC0, false, ThemeMaker.Art.HIGH))

    // THE PRESET REVIEW (beautify 9.7, 2026-11-17): every OLED preset's raised / selected lifted off black (its tiles floated with no
    // plate), its art back at stone 0.40 / pane 0.60 under a 0.8 vignette; OLED Gold's words set apart from its headings, OLED Silver's
    // accent off pure white (the brightest thing everywhere), OLED Frost's edge bluer; Dark Gold less muddy, Dark Silver no longer grey
    val OLED_BRONZE = ThemeSpec("oled_bronze", "OLED Bronze",
        ink = c(0xFF000000), field = c(0xFF000000), panel = c(0xFF000000), raised = c(0xFF18120B), selected = c(0xFF33270F),
        accent = c(0xFFFFD100), edge = c(0xFFC8AA6E), edgeDim = c(0xFF4A3A1C),
        text = c(0xFFE8DCC0), textDim = c(0xFFB3A48A), dim = c(0xFF8F8168), warn = c(0xFFD99A4E),
        focus = c(0xFFFFD100), soft = c(0xFFE2AA50), btnLabel = c(0xFFFFD100),
        stoneLight = OLED_STONE, paneLight = OLED_PANE, artSat = OLED_SAT, vignette = OLED_VIG, oled = true, metal = null,
        picks = p(0xFFFFD100, "bronze", 0, 1, 0xFFE8DCC0, true))

    val OLED_GOLD = ThemeSpec("oled_gold", "OLED Gold",
        ink = c(0xFF000000), field = c(0xFF000000), panel = c(0xFF000000), raised = c(0xFF17130A), selected = c(0xFF2F2507),
        accent = c(0xFFFFE27A), edge = c(0xFFF2CC52), edgeDim = c(0xFF6A5414),
        text = c(0xFFDCD3BE), textDim = c(0xFFC4B690), dim = c(0xFF9C8D68), warn = c(0xFFE0A050),
        focus = c(0xFFFFE680), soft = c(0xFFFFD45C), btnLabel = c(0xFFFFD100),
        stoneLight = OLED_STONE, paneLight = OLED_PANE, artSat = OLED_SAT, vignette = OLED_VIG, oled = true, metal = GOLD,
        picks = p(0xFFFFE27A, "gold", 0, 1, 0xFFE8DCC0, true))

    val OLED_SILVER = ThemeSpec("oled_silver", "OLED Silver",
        ink = c(0xFF000000), field = c(0xFF000000), panel = c(0xFF000000), raised = c(0xFF15171A), selected = c(0xFF2C3139),
        accent = c(0xFFF4F8FF), edge = c(0xFFB8BFC9), edgeDim = c(0xFF3D424A),
        text = c(0xFFCDD2D9), textDim = c(0xFFA3A9B2), dim = c(0xFF878D96), warn = c(0xFFE0A860),
        focus = c(0xFFF2F5FA), soft = c(0xFFC8D0DC), btnLabel = c(0xFFFFFFFF),
        stoneLight = OLED_STONE, paneLight = OLED_PANE, artSat = OLED_SAT, vignette = OLED_VIG, oled = true, metal = SILVER,
        picks = p(0xFFF4F8FF, "silver", 2, 1, 0xFFE4EDF4, true))

    // the user's "pink and silver": a pink accent; its borders Dark Rose's (the user, 2026-11-12: "the OLED rose should have the same
    // home border colors as dark rose", "its that second color pallet"): Dark Rose's edge, edgeDim and rose metal
    val OLED_ROSE = ThemeSpec("oled_rose", "OLED Rose",
        ink = c(0xFF000000), field = c(0xFF000000), panel = c(0xFF000000), raised = c(0xFF1A1217), selected = c(0xFF3A1D2E),
        accent = c(0xFFFF8CC6), edge = c(0xFFD9A3BE), edgeDim = c(0xFF55334A),
        text = c(0xFFF0E6EB), textDim = c(0xFFC4B5BD), dim = c(0xFF9C8C95), warn = c(0xFFE0A860),
        focus = c(0xFFFF8CC6), soft = c(0xFFF0A0C8), btnLabel = c(0xFFFFE3F1),
        stoneLight = OLED_STONE, paneLight = OLED_PANE, artSat = OLED_SAT, vignette = OLED_VIG, oled = true, metal = ROSE,
        picks = p(0xFFFF8CC6, "rose", 3, 1, 0xFFF0E6EB, true))

    val OLED_ROSE_GOLD = ThemeSpec("oled_rose_gold", "OLED Rose Gold",
        ink = c(0xFF000000), field = c(0xFF000000), panel = c(0xFF000000), raised = c(0xFF1A130F), selected = c(0xFF331F19),
        accent = c(0xFFF7B9A6), edge = c(0xFFDDA48F), edgeDim = c(0xFF563A30),
        text = c(0xFFF2E4DE), textDim = c(0xFFC7B2A9), dim = c(0xFF9E8A82), warn = c(0xFFE0A050),
        focus = c(0xFFFFC2B0), soft = c(0xFFE8A890), btnLabel = c(0xFFFFE6DC),
        stoneLight = OLED_STONE, paneLight = OLED_PANE, artSat = OLED_SAT, vignette = OLED_VIG, oled = true, metal = ROSE_GOLD,
        picks = p(0xFFF7B9A6, "rose_gold", 0, 1, 0xFFF0E6EB, true))

    val OLED_FROST = ThemeSpec("oled_frost", "OLED Frost",
        ink = c(0xFF000000), field = c(0xFF000000), panel = c(0xFF000000), raised = c(0xFF12171C), selected = c(0xFF182632),
        accent = c(0xFF96D4FF), edge = c(0xFFA8CBE6), edgeDim = c(0xFF33414E),
        text = c(0xFFE4EDF4), textDim = c(0xFFAFC0CD), dim = c(0xFF8698A7), warn = c(0xFFE0A860),
        focus = c(0xFFB8E4FF), soft = c(0xFF90C8F0), btnLabel = c(0xFFEEF8FF),
        stoneLight = OLED_STONE, paneLight = OLED_PANE, artSat = OLED_SAT, vignette = OLED_VIG, oled = true, metal = FROST,
        picks = p(0xFF96D4FF, "frost", 2, 1, 0xFFE4EDF4, true))

    val DARK_GOLD = ThemeSpec("dark_gold", "Dark Gold",
        ink = c(0xFF0B0A08), field = c(0xFF0F0D0A), panel = c(0xFF12100C), raised = c(0xFF19160F), selected = c(0xFF2E2510),
        accent = c(0xFFFFE27A), edge = c(0xFFF2CC52), edgeDim = c(0xFF6A5414),
        text = c(0xFFE6DDC6), textDim = c(0xFFC2B48E), dim = c(0xFF9C8D68), warn = c(0xFFE0A050),
        focus = c(0xFFFFE680), soft = c(0xFFFFD45C), btnLabel = c(0xFFFFD100),
        stoneLight = 0.6f, paneLight = 0.6f, artSat = 0.75f, vignette = DARK_VIG, oled = false, metal = GOLD,
        picks = p(0xFFFFE27A, "gold", 0, 1, 0xFFE8DCC0, false))

    val DARK_SILVER = ThemeSpec("dark_silver", "Dark Silver",
        ink = c(0xFF0B0D10), field = c(0xFF101113), panel = c(0xFF131417), raised = c(0xFF1A1C1F), selected = c(0xFF2B2F35),
        accent = c(0xFFFFFFFF), edge = c(0xFFB8BFC9), edgeDim = c(0xFF3D424A),
        text = c(0xFFCDD2D9), textDim = c(0xFFA3A9B2), dim = c(0xFF878D96), warn = c(0xFFE0A860),
        focus = c(0xFFF2F5FA), soft = c(0xFFC8D0DC), btnLabel = c(0xFFFFFFFF),
        stoneLight = 0.7f, paneLight = 0.7f, artSat = 0.15f, vignette = DARK_VIG, oled = false, metal = SILVER,
        picks = p(0xFFF4F8FF, "silver", 2, 1, 0xFFE4EDF4, false))

    val DARK_ROSE = ThemeSpec("dark_rose", "Dark Rose",
        ink = c(0xFF0F0B0D), field = c(0xFF130E11), panel = c(0xFF171114), raised = c(0xFF1E161A), selected = c(0xFF3A2030),
        accent = c(0xFFFF9AD0), edge = c(0xFFD9A3BE), edgeDim = c(0xFF55334A),
        text = c(0xFFF0E4EA), textDim = c(0xFFC6B3BD), dim = c(0xFF9E8B95), warn = c(0xFFE0A860),
        focus = c(0xFFFF9AD0), soft = c(0xFFF0A8CC), btnLabel = c(0xFFFFE3F1),
        stoneLight = 0.6f, paneLight = 0.6f, artSat = 0.25f, vignette = DARK_VIG, oled = false, metal = ROSE,
        picks = p(0xFFFF8CC6, "rose", 3, 1, 0xFFF0E6EB, false))

    // TWO NEW PREMADES (beautify 9.7, user pick 7, recommended: the gallery's colours span more than warm / pink / ice), built through
    // the maker so they pass the same guard as a custom theme; OLED Jade keeps the OLED family's art level
    val OLED_JADE = ThemeMaker.spec(ThemeMaker.Picks(c(0xFF7FE0A8), "jade", 140f, 0.25f, 0.045f, c(0xFFE2EEE6), true, ThemeMaker.Art.MID),
        "oled_jade", "OLED Jade").copy(stoneLight = OLED_STONE, paneLight = OLED_PANE)

    val DARK_VIOLET = ThemeMaker.spec(ThemeMaker.Picks(c(0xFFC7A0FF), "violet", 265f, 0.25f, 0.05f, c(0xFFF0F0F0), false, ThemeMaker.Art.MID),
        "dark_violet", "Dark Violet")

    /** the picker's order: the default first, the OLED family, then the Dark family (a saved custom theme is [Theme.all]'s, last) */
    val ALL = listOf(CLASSIC, OLED_BRONZE, OLED_GOLD, OLED_SILVER, OLED_ROSE, OLED_ROSE_GOLD, OLED_FROST, OLED_JADE,
        DARK_GOLD, DARK_SILVER, DARK_ROSE, DARK_VIOLET)

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
        "search_left", "search_mid", "search_right", "chat_input_", "zone_band", "button_", "square_", "drop_button", "talent_tab",
        // the chrome's own small gold marks (review, 2026-11-12: left gold on Silver / Rose they were the loudest thing on the page):
        // the tracker's + / - / filter buttons (Home's map zoom), the big move arrows (the party tab's), the tick, the drop arrow and
        // the menu's small next arrow (the party tab's pick)
        "tracker_plus", "tracker_minus", "tracker_filter", "move_arrow_", "check_tick", "drop_arrow", "menu_next")

    fun isMetal(name: String): Boolean = name != "side_tab_mask" && METAL_PREFIXES.any { name.startsWith(it) }

    /**
     * THE DS TOOLBAR'S ART (`ui/cast/<name>`, CastView's own loader): the slot frames, the focus section, the radial's rings, the page
     * plates' frames, the trigger plates and their glow. Never a controller glyph (the buttons' letters), nor the cast bar's fills,
     * sparks and pip (the cast bar is the game's own reading of a cast).
     */
    private val CAST_METAL_PREFIXES = listOf("frame_", "slot_", "radial_base", "radial_normal", "radial_selected", "radial_highlight",
        "page_s_frame", "page_1_", "page_2_", "page_3_", "trig_", "castbar_frame", "castbar_textbox")

    fun isCastMetal(name: String): Boolean = !name.startsWith("frame_mask") && CAST_METAL_PREFIXES.any { name.startsWith(it) }

    // ---- contrast (WCAG's relative luminance), for the presets' check
    private fun lin(ch: Int): Double { val s = ch / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
    fun luminance(c: Int): Double = 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
