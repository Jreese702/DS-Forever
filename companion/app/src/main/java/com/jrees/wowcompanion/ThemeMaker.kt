package com.jrees.wowcompanion

/**
 * THE CUSTOM THEME MAKER'S MODEL (the user, 2026-11-17: "as part of the beautification lets add a custom theme maker where people can
 * choose their pallets with an oled toggle, and then have an extra button to show the premade themes"). Pure: plain ARGB ints, no
 * Android calls, tested off-device (ThemeMakerTest).
 *
 * A theme is made from a few PICKS ([Picks]: an accent, a metal, a ground tint and its shade, a text colour, OLED on / off, how much of
 * the rooms' art shows); [spec] derives every chrome token from them (beautify design 9.4) and then runs the CONTRAST GUARD, so a custom
 * theme can never be unreadable. The guard's order is fixed and every step is single-pass and bounded (the pairs are circular otherwise):
 * grounds -> text family (L up) -> accent vs ink (L up) -> accent apart from the words (text L down, never below 7:1 on raised) ->
 * selected from the adjusted accent -> accent vs selected (selected's accent share down) -> the red buttons' words.
 *
 * One custom slot, per device (prefs "settings", key "theme_custom", [encode] / [decode]); the id is "custom".
 */
object ThemeMaker {
    const val ID = "custom"

    enum class Art(val key: String) { LOW("low"), MID("mid"), HIGH("high") }

    /** what the player picked; [tintHue] degrees, [tintSat] / [tintL] 0..1 (HSL) */
    data class Picks(
        val accent: Int, val metal: String, val tintHue: Float, val tintSat: Float, val tintL: Float,
        val text: Int, val oled: Boolean, val art: Art,
    )

    // ---------------------------------------------------------------- the curated swatches (design 9.3)

    class Swatch(val name: String, val colour: Int)
    class MetalPick(val id: String, val name: String, val metal: Metal?, val edge: Int, val edgeDim: Int)
    class Tint(val name: String, val hue: Float, val sat: Float)
    class Shade(val name: String, val l: Float)

    private fun c(hex: Long) = hex.toInt()

    val ACCENTS = listOf(
        Swatch("Game gold", c(0xFFFFD100)), Swatch("Pale gold", c(0xFFFFE27A)), Swatch("Silver white", c(0xFFF4F8FF)),
        Swatch("Rose", c(0xFFFF8CC6)), Swatch("Rose gold", c(0xFFF7B9A6)), Swatch("Frost", c(0xFF96D4FF)), Swatch("Jade", c(0xFF7FE0A8)),
        Swatch("Violet", c(0xFFC7A0FF)), Swatch("Ember", c(0xFFFF9A5C)), Swatch("Crimson", c(0xFFFF7070)))

    val METAL_GOLD = Metal(hue = 46f, sat = 1.45f, gain = 1.3f, curve = 0.92f)
    val METAL_SILVER = Metal(hue = 215f, sat = 0.07f, gain = 1.12f, curve = 0.88f)
    val METAL_ROSE_GOLD = Metal(hue = 12f, sat = 0.62f, gain = 1.12f, curve = 0.95f)
    val METAL_ROSE = Metal(hue = 330f, sat = 0.55f, gain = 1.1f, curve = 0.92f)
    val METAL_FROST = Metal(hue = 205f, sat = 0.32f, gain = 1.14f, curve = 0.9f)
    val METAL_JADE = Metal(hue = 150f, sat = 0.45f, gain = 1.1f, curve = 0.92f)
    val METAL_VIOLET = Metal(hue = 270f, sat = 0.45f, gain = 1.08f, curve = 0.92f)
    val METAL_EMBER = Metal(hue = 18f, sat = 1.1f, gain = 1.15f, curve = 0.95f)
    val METAL_STEEL = Metal(hue = 215f, sat = 0.02f, gain = 0.92f, curve = 1.0f)

    val METALS = listOf(
        MetalPick("bronze", "Bronze", null, c(0xFFC8AA6E), c(0xFF4A3A1C)),
        MetalPick("gold", "Gold", METAL_GOLD, c(0xFFF2CC52), c(0xFF6A5414)),
        MetalPick("silver", "Silver", METAL_SILVER, c(0xFFB8BFC9), c(0xFF3D424A)),
        MetalPick("rose", "Rose", METAL_ROSE, c(0xFFD9A3BE), c(0xFF55334A)),
        MetalPick("rose_gold", "Rose gold", METAL_ROSE_GOLD, c(0xFFDDA48F), c(0xFF563A30)),
        MetalPick("frost", "Frost", METAL_FROST, c(0xFFA8CBE6), c(0xFF33414E)),
        MetalPick("jade", "Jade", METAL_JADE, c(0xFF9FD6B8), c(0xFF2E4A3C)),
        MetalPick("violet", "Violet", METAL_VIOLET, c(0xFFC2AEE0), c(0xFF3E3352)),
        MetalPick("ember", "Ember", METAL_EMBER, c(0xFFE0A07A), c(0xFF553424)),
        MetalPick("steel", "Steel", METAL_STEEL, c(0xFF9AA0A8), c(0xFF34383E)))

    val TINTS = listOf(Tint("Warm", 32f, 0.25f), Tint("Neutral", 0f, 0f), Tint("Cool", 215f, 0.25f), Tint("Rose", 330f, 0.25f),
        Tint("Moss", 140f, 0.25f), Tint("Dusk", 265f, 0.25f))

    /** the Shade row (it replaced the ground's fine-tune steps, critic C11): the ground's HSL lightness */
    val SHADES = listOf(Shade("Deep", 0.03f), Shade("Mid", 0.045f), Shade("Lift", 0.07f))

    val TEXTS = listOf(Swatch("Parchment", c(0xFFE8DCC0)), Swatch("Paper", c(0xFFF0F0F0)), Swatch("Ice", c(0xFFE4EDF4)),
        Swatch("Blush", c(0xFFF0E6EB)))

    fun metal(id: String): MetalPick = METALS.firstOrNull { it.id == id } ?: METALS[0]

    /** the red cloth the buttons' words stand on (ThemePresetsTest's), and the game's own gold on it */
    const val CLOTH = 0xFF5A0E0A.toInt()
    const val GAME_GOLD = 0xFFFFD100.toInt()
    const val WARN = 0xFFE0A860.toInt()
    /** OLED raised plate lightness: 0.075, not the design's 0.06 (at 0.06 a Moss or Dusk plate measured 1.1:1 on black: the review's
     *  "invisible plate" again; the reviewed presets sit at about 0.07) */
    const val OLED_RAISED_L = 0.075f

    /** how much of the rooms' art shows (design 8.2): stone / pane light per Art level, OLED or not */
    // (OLED: Low 0.45 / 0.45, Mid 0.55 / 0.60 = the OLED presets', High 0.70 / 0.75; the design's 0.18 .. 0.50 left the dark pane art
    // on pure black once its blacks were crushed: see ThemePresets' OLED levels)
    fun stoneLight(oled: Boolean, a: Art) = if (oled) when (a) { Art.LOW -> 0.45f; Art.MID -> 0.55f; Art.HIGH -> 0.70f }
        else when (a) { Art.LOW -> 0.5f; Art.MID -> 0.6f; Art.HIGH -> 0.8f }
    fun paneLight(oled: Boolean, a: Art) = if (oled) when (a) { Art.LOW -> 0.45f; Art.MID -> 0.60f; Art.HIGH -> 0.75f }
        else stoneLight(false, a)

    // ---------------------------------------------------------------- the derivation (design 9.4)

    /** a derived theme and which of its rows the guard had to move (the maker's auto mark) */
    class Result(val spec: ThemeSpec, val textMoved: Boolean, val accentMoved: Boolean)

    fun spec(p: Picks, id: String = ID, name: String = "Custom"): ThemeSpec = derive(p, id, name).spec

    fun derive(p: Picks, id: String = ID, name: String = "Custom"): Result {
        val h = p.tintHue; val s = p.tintSat; val l = p.tintL
        val black = c(0xFF000000)
        // grounds
        val ink = if (p.oled) black else hsl(h, s, l)
        val field = if (p.oled) black else hsl(h, s, l + 0.015f)
        val panel = if (p.oled) black else hsl(h, s, l + 0.03f)
        val raised = if (p.oled) hsl(h, s, OLED_RAISED_L) else hsl(h, s, l + 0.055f)
        val m = metal(p.metal)
        var edge = m.edge
        for (i in 0 until 25) { if (contrast(edge, ink) >= 3.0) break; edge = lighten(edge, 0.02f) }
        // text family: the words L up until they read on the raised plate
        var textMoved = false
        var text = p.text
        for (i in 0 until 25) { if (contrast(text, raised) >= 7.0) break; text = lighten(text, 0.02f); textMoved = true }
        // accent vs the page
        var accentMoved = false
        var accent = p.accent
        for (i in 0 until 25) { if (contrast(accent, ink) >= 4.5 && contrast(accent, raised) >= 4.5) break; accent = lighten(accent, 0.02f); accentMoved = true }
        // the accent is a CUE: apart from the words by brightness or hue (ThemePresetsTest.accentStandsApartFromWords); the words step
        // DOWN, never under 7:1 on raised
        for (i in 0 until 25) {
            if (apart(accent, text)) break
            val t2 = lighten(text, -0.02f)
            if (contrast(t2, raised) < 7.0) break
            text = t2; textMoved = true
        }
        var textDim = mix(text, raised, 0.25f)
        for (i in 0 until 25) { if (contrast(textDim, raised) >= 4.5) break; textDim = lighten(textDim, 0.02f) }
        var dim = mix(text, raised, 0.42f)
        for (i in 0 until 25) { if (contrast(dim, raised) >= 4.5) break; dim = lighten(dim, 0.02f) }
        // selected: raised with a share of the adjusted accent (Classic's own selected row is about 8 % of its gold over its raised; the
        // design's 20 % read as an olive slab in the renders); the share goes DOWN until the accent and the words read on it
        var share = 0.10f
        var selected = mix(raised, accent, share)
        while (share > 0.03f && !(contrast(accent, selected) >= 4.5 && contrast(text, selected) >= 4.5 &&
                contrast(textDim, selected) >= 4.5 && contrast(dim, selected) >= 3.0)) {
            share -= 0.01f; selected = mix(raised, accent, share)
        }
        // the focus light and the soft wash
        val ah = toHsl(accent)
        val focus = hsl(ah[0], ah[1], (ah[2] + 0.08f).coerceAtMost(0.97f))
        val soft = mix(accent, edge, 0.5f)
        val btnLabel = buttonWords(accent)
        val spec = ThemeSpec(id, name, ink = ink, field = field, panel = panel, raised = raised, selected = selected,
            accent = accent, edge = edge, edgeDim = m.edgeDim, text = text, textDim = textDim, dim = dim, warn = WARN,
            focus = focus, soft = soft, btnLabel = btnLabel,
            stoneLight = stoneLight(p.oled, p.art), paneLight = paneLight(p.oled, p.art),
            artSat = if (p.oled) 0.85f else 0.75f, vignette = if (p.oled) 0.8f else 0.35f, oled = p.oled, metal = m.metal, picks = p)
        return Result(spec, textMoved, accentMoved)
    }

    /**
     * The words on the game's red cloth: the accent itself when it is a full gold that reads on the cloth; the game's gold for any other
     * gold-ish accent; otherwise the accent mixed far toward white (a pink or ice accent on red clashes), whitened until it reads
     */
    fun buttonWords(accent: Int): Int {
        val hv = hsv(accent)
        if (hv[0] in 40f..56f && hv[1] > 0.8f && contrast(accent, CLOTH) >= 7.0) return accent
        if (hv[0] in 38f..62f) return GAME_GOLD
        var w = mix(accent, c(0xFFFFFFFF), 0.82f)
        for (i in 0 until 25) { if (contrast(w, CLOTH) >= 7.0) break; w = lighten(w, 0.02f) }
        return w
    }

    /** ThemePresetsTest's rule: the accent stands apart from the words by brightness (1.25:1) or by hue */
    fun apart(accent: Int, text: Int): Boolean {
        val k = contrast(accent, text)
        val a = hsv(accent); val w = hsv(text)
        val dh = Math.abs(a[0] - w[0]).let { minOf(it, 360f - it) }
        val byHue = a[1] >= 0.3f && (dh >= 15f || a[1] - w[1] >= 0.3f)
        return k >= 1.25 || byHue
    }

    // ---------------------------------------------------------------- the maker's starting point

    /** [p] snapped to the curated swatches (a premade's picks, or a saved custom one, may lie between them) */
    fun snap(p: Picks): Picks {
        fun near(list: List<Int>, c: Int) = list.minByOrNull { dist(it, c) } ?: c
        val tint = TINTS.minByOrNull { t ->
            if (p.tintSat < 0.05f) (if (t.sat == 0f) 0f else 1000f) else (if (t.sat == 0f) 1000f else hueDist(t.hue, p.tintHue))
        } ?: TINTS[0]
        val shade = SHADES.minByOrNull { Math.abs(it.l - p.tintL) } ?: SHADES[1]
        return p.copy(accent = near(ACCENTS.map { it.colour }, p.accent), metal = metal(p.metal).id, tintHue = tint.hue, tintSat = tint.sat,
            tintL = shade.l, text = near(TEXTS.map { it.colour }, p.text))
    }

    /** the picks the maker opens with for [t] (the theme in use): its own, or Classic's */
    fun startOf(t: ThemeSpec): Picks = snap(t.picks ?: CLASSIC_PICKS)

    val CLASSIC_PICKS = Picks(c(0xFFFFD100), "bronze", 32f, 0.25f, 0.045f, c(0xFFE8DCC0), false, Art.HIGH)

    // ---------------------------------------------------------------- storage (one JSON line; no org.json: this file stays pure)

    fun encode(p: Picks): String = "{\"v\":1,\"accent\":\"${hex(p.accent)}\",\"metal\":\"${p.metal}\",\"tint\":[${p.tintHue},${p.tintSat},${p.tintL}]," +
        "\"text\":\"${hex(p.text)}\",\"oled\":${p.oled},\"art\":\"${p.art.key}\"}"

    fun decode(s: String?): Picks? {
        if (s.isNullOrBlank()) return null
        return runCatching {
            fun str(k: String) = Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(s)!!.groupValues[1]
            val tint = Regex("\"tint\"\\s*:\\s*\\[([^\\]]*)]").find(s)!!.groupValues[1].split(",").map { it.trim().toFloat() }
            val oled = Regex("\"oled\"\\s*:\\s*(true|false)").find(s)!!.groupValues[1] == "true"
            val art = Art.values().firstOrNull { it.key == str("art") } ?: Art.MID
            Picks(parseHex(str("accent")), metal(str("metal")).id, tint[0], tint[1].coerceIn(0f, 1f), tint[2].coerceIn(0f, 0.2f),
                parseHex(str("text")), oled, art)
        }.getOrNull()
    }

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)
    private fun parseHex(s: String): Int = (0xFF000000.toInt()) or (s.removePrefix("#").toLong(16).toInt() and 0xFFFFFF)

    // ---------------------------------------------------------------- colour maths

    fun contrast(a: Int, b: Int) = ThemePresets.contrast(a, b)

    /** [a] toward [b] by [t] (0 = a, 1 = b), opaque */
    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch((a shr 16) and 0xFF, (b shr 16) and 0xFF) shl 16) or
            (ch((a shr 8) and 0xFF, (b shr 8) and 0xFF) shl 8) or ch(a and 0xFF, b and 0xFF)
    }

    /** [c] with its HSL lightness moved by [d] */
    fun lighten(c: Int, d: Float): Int { val h = toHsl(c); return hsl(h[0], h[1], (h[2] + d).coerceIn(0f, 1f)) }

    fun hsl(h: Float, s: Float, l: Float): Int {
        val ll = l.coerceIn(0f, 1f); val ss = s.coerceIn(0f, 1f)
        val q = if (ll < 0.5f) ll * (1 + ss) else ll + ss - ll * ss
        val p = 2 * ll - q
        val hk = ((h % 360f) + 360f) % 360f / 360f
        fun f(t0: Float): Int {
            var t = t0; if (t < 0) t += 1f; if (t > 1) t -= 1f
            val v = when { t < 1f / 6f -> p + (q - p) * 6f * t; t < 0.5f -> q; t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f; else -> p }
            return (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (f(hk + 1f / 3f) shl 16) or (f(hk) shl 8) or f(hk - 1f / 3f)
    }

    fun toHsl(c: Int): FloatArray {
        val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val l = (mx + mn) / 2f
        if (mx == mn) return floatArrayOf(0f, 0f, l)
        val d = mx - mn
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        val h = when (mx) { r -> ((g - b) / d + (if (g < b) 6f else 0f)); g -> (b - r) / d + 2f; else -> (r - g) / d + 4f } * 60f
        return floatArrayOf(h, s, l)
    }

    fun hsv(c: Int): FloatArray = FloatArray(3).also { Metal.rgbToHsv((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, it) }

    private fun hueDist(a: Float, b: Float) = Math.abs(a - b).let { minOf(it, 360f - it) }
    private fun dist(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF); val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF); val db = (a and 0xFF) - (b and 0xFF)
        return dr * dr + dg * dg + db * db
    }
}
