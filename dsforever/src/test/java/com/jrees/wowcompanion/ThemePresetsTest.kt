package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE THEME PRESETS, off-device (the user, 2026-11-12: "an oled theme, but different color variations for menus like pink and silver or
 * gold instead of the classic bronze ... like 10 good options"): ten complete presets, Classic exactly the old colours, OLED pure black,
 * every preset's words readable on its grounds, and the metal recolour that moves bronze only.
 */
class ThemePresetsTest {
    private val all = ThemePresets.ALL
    private fun rgb(c: Int) = c and 0xFFFFFF

    @Test fun tenPresetsUniqueAndNamed() {
        assertEquals(10, all.size)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all.size, all.map { it.name }.toSet().size)
        assertEquals(ThemePresets.CLASSIC, all.first())                       // the default leads the picker
        for (t in all) {
            assertTrue(t.id, t.name.length in 4..16)                          // terse: the tile's one line
            assertTrue(t.id, t.id.matches(Regex("[a-z_]+")))
        }
        // the user's asks are all there: OLED; pink, silver, gold; classic bronze
        val names = all.map { it.name }
        for (want in listOf("Classic Bronze", "OLED Bronze", "OLED Gold", "OLED Silver", "OLED Rose")) assertTrue(want, want in names)
    }

    @Test fun everyTokenIsOpaque() {
        for (t in all) for ((n, c) in tokens(t)) assertEquals("${t.id}.$n is opaque", 0xFF, c ushr 24)
        for (t in all) assertTrue(t.id, t.groundLight in 0f..1f && t.groundSat in 0f..1f)
    }

    /** Classic Bronze is exactly the colours the app drew before themes (Theme.kt at 44d3b16a) */
    @Test fun classicIsTheOldLook() {
        val c = ThemePresets.CLASSIC
        assertEquals(0x0E0B08, rgb(c.ink)); assertEquals(0x141009, rgb(c.field)); assertEquals(0x17120C, rgb(c.panel))
        assertEquals(0x1B1610, rgb(c.raised)); assertEquals(0x2E2411, rgb(c.selected)); assertEquals(0xFFD100, rgb(c.accent))
        assertEquals(0xC8AA6E, rgb(c.edge)); assertEquals(0x4A3A1C, rgb(c.edgeDim)); assertEquals(0xE8DCC0, rgb(c.text))
        assertEquals(0xB3A48A, rgb(c.textDim)); assertEquals(0x8F8168, rgb(c.dim)); assertEquals(0xD99A4E, rgb(c.warn))
        assertEquals(0xFFD100, rgb(c.focus)); assertEquals(0xE2AA50, rgb(c.soft))         // UiKit.SOFT_GOLD = rgb(226, 170, 80)
        assertEquals(0xFFD100, rgb(c.btnLabel))                                              // the red buttons' words were Theme.gold
        assertNull(c.metal); assertEquals(1f, c.groundLight); assertEquals(1f, c.groundSat); assertFalse(c.oled)
    }

    @Test fun oledIsPureBlack() {
        val oled = all.filter { it.oled }
        assertEquals(6, oled.size)
        for (t in oled) {
            assertTrue(t.id, t.name.startsWith("OLED"))
            for (c in listOf(t.ink, t.field, t.panel)) assertEquals(t.id, 0x000000, rgb(c))
            // a visible step for tiles and the selected row, or they vanish on black
            assertTrue(t.id, ThemePresets.luminance(t.raised) > 0.0 && ThemePresets.luminance(t.selected) > ThemePresets.luminance(t.raised))
        }
        for (t in all.filter { !it.oled }) assertTrue(t.id, t.groundLight > 0f)
    }

    /** WCAG-ish: body words and headings at 4.5:1 or more on every ground they stand on; the faint words at 4.5 on the page */
    @Test fun wordsAreReadableInEveryPreset() {
        for (t in all) {
            for ((fn, fg) in listOf("text" to t.text, "textDim" to t.textDim, "accent" to t.accent))
                for ((bn, bg) in listOf("ink" to t.ink, "panel" to t.panel, "raised" to t.raised, "selected" to t.selected)) {
                    val k = ThemePresets.contrast(fg, bg)
                    assertTrue("${t.id}: $fn on $bn is ${"%.2f".format(k)}:1", k >= 4.5)
                }
            for ((bn, bg) in listOf("ink" to t.ink, "panel" to t.panel, "raised" to t.raised)) {
                val k = ThemePresets.contrast(t.dim, bg)
                assertTrue("${t.id}: dim on $bn is ${"%.2f".format(k)}:1", k >= 4.5)
            }
            assertTrue("${t.id}: dim on selected", ThemePresets.contrast(t.dim, t.selected) >= 3.0)
            // the scroll thumb / rules: the edge must show on the page
            assertTrue("${t.id}: edge on ink", ThemePresets.contrast(t.edge, t.ink) >= 3.0)
        }
    }

    /**
     * The accent is a CUE (headings, the active tab, labels): it must stand apart from the body words, by brightness or by hue
     * (review, 2026-11-12: Silver's accent and words were 1.05:1, both neutral white, so a heading read as plain text)
     */
    @Test fun accentStandsApartFromWords() {
        for (t in all) {
            val k = ThemePresets.contrast(t.accent, t.text)
            val a = hsv(t.accent); val w = hsv(t.text)
            val dh = Math.abs(a[0] - w[0]).let { minOf(it, 360f - it) }
            val byHue = a[1] >= 0.3f && (dh >= 15f || a[1] - w[1] >= 0.3f)
            assertTrue("${t.id}: accent vs words ${"%.2f".format(k)}:1, hue ${"%.0f".format(dh)}", k >= 1.25 || byHue)
        }
    }

    /** the red cloth buttons' words: readable on the cloth (its dark red, ~#5A0E0A) and never a pink / ice accent on red */
    @Test fun buttonWordsReadOnRedCloth() {
        val cloth = 0xFF5A0E0A.toInt()
        for (t in all) {
            assertTrue("${t.id}: ${"%.2f".format(ThemePresets.contrast(t.btnLabel, cloth))}", ThemePresets.contrast(t.btnLabel, cloth) >= 7.0)
            val h = hsv(t.btnLabel)
            assertTrue("${t.id}: the words are gold, or nearly white (s=${h[1]})", (h[0] in 40f..56f && h[1] > 0.8f) || h[1] < 0.2f)
        }
    }

    /** the user asked for gold "instead of the classic bronze": the Gold presets' label and edge must not be Classic's */
    @Test fun goldIsNotClassic() {
        for (g in listOf(ThemePresets.OLED_GOLD, ThemePresets.DARK_GOLD)) {
            assertTrue(g.id, rgb(g.accent) != 0xFFD100)
            val e = hsv(g.edge); val b = hsv(ThemePresets.CLASSIC.edge)
            assertTrue("${g.id}: a yellower, fuller edge", e[1] > b[1] + 0.2f && e[2] > b[2])
        }
    }

    /** the brightness curve's table gives what the per-pixel pow gave (to the 8-bit value) */
    @Test fun metalCurveTableMatchesPow() {
        for (t in all) {
            val m = t.metal ?: continue
            for (v in listOf(0x20, 0x55, 0x80, 0xA0, 0xC8, 0xF0)) {
                val c = (0xFF shl 24) or (v shl 16) or ((v * 85 / 100) shl 8) or (v * 55 / 100)       // a bronze of brightness v
                val got = hsv(recolour(m, c))[2]
                val want = (Math.pow(v / 255.0, m.curve.toDouble()) * m.gain).coerceIn(0.0, 1.0).toFloat()
                assertEquals("${t.id} v=$v", want, got, 1.5f / 255f)
            }
        }
    }

    @Test fun contrastMatchesWcag() {
        assertEquals(21.0, ThemePresets.contrast(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.01)
        assertEquals(1.0, ThemePresets.contrast(0xFF777777.toInt(), 0xFF777777.toInt()), 0.0001)
    }

    // ---------------------------------------------------------------- the metal

    private fun hsv(c: Int): FloatArray = FloatArray(3).also { Metal.rgbToHsv((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, it) }
    private fun recolour(m: Metal, c: Int) = intArrayOf(c).also { m.apply(it) }[0]

    @Test fun metalMovesBronzeOnly() {
        val bronze = 0xFFC8AA6E.toInt()          // the edge's bronze: hue ~40
        val red = 0xFF8A1010.toInt()             // a button's cloth
        val grey = 0xFF808080.toInt()            // stone, no colour
        val blue = 0xFF3060C0.toInt()            // a gem
        val clear = 0x00C8AA6E                   // fully transparent: untouched
        for (t in all) {
            val m = t.metal ?: continue
            assertEquals(t.id, red, recolour(m, red))
            assertEquals(t.id, grey, recolour(m, grey))
            assertEquals(t.id, blue, recolour(m, blue))
            assertEquals(t.id, clear, recolour(m, clear))
            assertTrue(t.id, recolour(m, bronze) != bronze)
            assertEquals("${t.id} keeps alpha", 0x80, recolour(m, 0x80C8AA6E.toInt()) ushr 24)
        }
    }

    @Test fun metalsLookTheirName() {
        val bronze = 0xFFC8AA6E.toInt()
        val silver = hsv(recolour(ThemePresets.OLED_SILVER.metal!!, bronze))
        assertTrue("silver is near grey: s=${silver[1]}", silver[1] < 0.06f)
        assertTrue("silver is lifted", silver[2] >= hsv(bronze)[2])
        val gold = hsv(recolour(ThemePresets.OLED_GOLD.metal!!, bronze))
        assertTrue("gold is more saturated", gold[1] > hsv(bronze)[1])
        assertTrue("gold's hue ~46: ${gold[0]}", gold[0] in 38f..54f)
        val rose = hsv(recolour(ThemePresets.DARK_ROSE.metal!!, bronze))
        assertTrue("rose's hue is pink: ${rose[0]}", rose[0] in 300f..345f)
        val roseGold = hsv(recolour(ThemePresets.OLED_ROSE_GOLD.metal!!, bronze))
        assertTrue("rose gold's hue is copper-pink: ${roseGold[0]}", roseGold[0] < 25f || roseGold[0] > 350f)
        val frost = hsv(recolour(ThemePresets.OLED_FROST.metal!!, bronze))
        assertTrue("frost's hue is icy blue: ${frost[0]}", frost[0] in 190f..225f)
    }

    @Test fun shadingIsKept() {
        // a dark and a light bronze stay in the same order of brightness after any recolour
        val dark = 0xFF5A4A2C.toInt(); val light = 0xFFE0C890.toInt()
        for (t in all) {
            val m = t.metal ?: continue
            assertTrue(t.id, hsv(recolour(m, dark))[2] < hsv(recolour(m, light))[2])
        }
    }

    @Test fun hsvRoundTrips() {
        for (c in listOf(0xFFC8AA6E.toInt(), 0xFF102030.toInt(), 0xFFFFD100.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
            val h = hsv(c)
            assertEquals(c, Metal.hsvToRgb(h[0], h[1], h[2]))
        }
    }

    @Test fun whichArtIsMetal() {
        for (n in listOf("frame_tl", "dialog_head_center", "border_gold", "ring_square", "focus_frame", "focus_gold", "tab_on_left",
                "side_tab_on", "button_up_center", "square_exit_up", "book_frame", "scroll_knob_top", "check_box", "tracker_plus", "tracker_minus_down",
                "move_arrow_right", "check_tick", "drop_arrow", "menu_next")) assertTrue(n, ThemePresets.isMetal(n))
        // never an item quality, an icon, a portrait, paper, a ground or a mask
        for (n in listOf("loot_border_orange", "loot_border_purple", "icon_bags", "ricon_map", "portrait_quest", "parchment", "ground_rock",
                "ground_marble", "pane_left", "side_tab_mask", "mail_paper_left", "fid_132048", "class_mage", "talentbg_mage_fire", "tracker_check")) assertFalse(n, ThemePresets.isMetal(n))
        // the DS toolbar's own art (ui/cast): its frames, slots, radial rings, page plates and trigger plates; never a glyph or the cast bar
        for (n in listOf("frame_square_normal", "frame_circle_focus", "frame_focus_section", "slot_dpad_up", "slot_face_xbox_a_pressed",
                "radial_base", "radial_selected", "radial_highlight", "page_s_frame", "page_2_selected", "trig_nintendo_lt", "trig_glow_behind",
                "castbar_frame")) assertTrue(n, ThemePresets.isCastMetal(n))
        for (n in listOf("glyph_nintendo_a", "glyph_xbox_dpad_up_pressed", "castbar_filling_standard", "castbar_pip", "castbar_full_glow_standard",
                "frame_mask_square", "badge_friendly", "radial_backdrop", "page_s_bg")) assertFalse(n, ThemePresets.isCastMetal(n))
    }

    private fun tokens(t: ThemeSpec) = listOf("ink" to t.ink, "field" to t.field, "panel" to t.panel, "raised" to t.raised,
        "selected" to t.selected, "accent" to t.accent, "edge" to t.edge, "edgeDim" to t.edgeDim, "text" to t.text, "textDim" to t.textDim,
        "dim" to t.dim, "warn" to t.warn, "focus" to t.focus, "soft" to t.soft, "btnLabel" to t.btnLabel)
}
