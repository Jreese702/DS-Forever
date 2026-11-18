package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE CUSTOM THEME MAKER'S MODEL, off-device (the user, 2026-11-17: "lets add a custom theme maker where people can choose their pallets
 * with an oled toggle"): EVERY curated combination (10 accents x 10 metals x 6 grounds x 3 shades x 4 texts x 2 OLED x 3 art = 43,200)
 * derives a theme that passes the same readability checks as the premade presets (ThemePresetsTest), and the guard always ends.
 */
class ThemeMakerTest {
    private fun rgb(c: Int) = c and 0xFFFFFF
    private fun k(a: Int, b: Int) = ThemePresets.contrast(a, b)

    private fun every(): Sequence<ThemeMaker.Picks> = sequence {
        for (a in ThemeMaker.ACCENTS) for (m in ThemeMaker.METALS) for (t in ThemeMaker.TINTS) for (s in ThemeMaker.SHADES)
            for (x in ThemeMaker.TEXTS) for (o in listOf(true, false)) for (art in ThemeMaker.Art.values())
                yield(ThemeMaker.Picks(a.colour, m.id, t.hue, t.sat, s.l, x.colour, o, art))
    }

    @Test fun curatedCounts() {
        assertEquals(10, ThemeMaker.ACCENTS.size); assertEquals(10, ThemeMaker.METALS.size); assertEquals(6, ThemeMaker.TINTS.size)
        assertEquals(3, ThemeMaker.SHADES.size); assertEquals(4, ThemeMaker.TEXTS.size); assertEquals(3, ThemeMaker.Art.values().size)
        assertEquals(43_200, every().count())
        assertEquals(ThemeMaker.METALS.size, ThemeMaker.METALS.map { it.id }.toSet().size)
    }

    /** the guard's pairs (design 9.4) plus every preset threshold, on every curated pick */
    @Test fun everyCuratedPickIsReadable() {
        val cloth = ThemeMaker.CLOTH
        var n = 0
        for (p in every()) {
            val t = ThemeMaker.spec(p)
            val id = "${"%06X".format(rgb(p.accent))}/${p.metal}/${p.tintHue}/${p.tintL}/${"%06X".format(rgb(p.text))}/${p.oled}/${p.art}"
            assertTrue("$id text/raised ${k(t.text, t.raised)}", k(t.text, t.raised) >= 7.0)
            for ((fn, fg) in listOf("text" to t.text, "textDim" to t.textDim, "accent" to t.accent))
                for ((bn, bg) in listOf("ink" to t.ink, "panel" to t.panel, "raised" to t.raised, "selected" to t.selected))
                    assertTrue("$id $fn on $bn ${k(fg, bg)}", k(fg, bg) >= 4.5)
            for (bg in listOf(t.ink, t.panel, t.raised)) assertTrue("$id dim ${k(t.dim, bg)}", k(t.dim, bg) >= 4.5)
            assertTrue("$id dim on selected", k(t.dim, t.selected) >= 3.0)
            assertTrue("$id edge on ink", k(t.edge, t.ink) >= 3.0)
            assertTrue("$id accent apart from words", ThemeMaker.apart(t.accent, t.text))
            assertTrue("$id btnLabel on cloth ${k(t.btnLabel, cloth)}", k(t.btnLabel, cloth) >= 7.0)
            val h = ThemeMaker.hsv(t.btnLabel)
            assertTrue("$id btnLabel gold or near white", (h[0] in 40f..56f && h[1] > 0.8f) || h[1] < 0.2f)
            // OLED: pure black page, a visible plate, selected above raised
            if (p.oled) for (c in listOf(t.ink, t.field, t.panel)) assertEquals(id, 0, rgb(c))
            assertTrue(id, ThemePresets.luminance(t.selected) > ThemePresets.luminance(t.raised))
            assertTrue(id, ThemePresets.luminance(t.raised) > ThemePresets.luminance(t.ink))
            assertEquals(id, p.oled, t.oled)
            n++
        }
        assertEquals(43_200, n)
    }

    /** the derivation's shape (design 9.4): grounds from the tint, OLED black, the art levels, Classic's picks give Classic's look */
    @Test fun derivation() {
        val p = ThemeMaker.Picks(0xFFFFD100.toInt(), "bronze", 32f, 0.25f, 0.045f, 0xFFE8DCC0.toInt(), false, ThemeMaker.Art.MID)
        val t = ThemeMaker.spec(p)
        assertEquals("custom", t.id)
        val l = ThemeMaker.toHsl(t.ink)
        assertEquals(0.045f, l[2], 0.006f); assertEquals(32f, l[0], 6f)
        assertTrue(ThemeMaker.toHsl(t.field)[2] > l[2]); assertTrue(ThemeMaker.toHsl(t.panel)[2] > ThemeMaker.toHsl(t.field)[2])
        assertEquals(0.6f, t.stoneLight); assertEquals(0.6f, t.paneLight); assertFalse(t.oled)
        assertEquals(0xFFD100, rgb(t.btnLabel))                          // the game's gold on red
        assertNull(t.metal)
        val o = ThemeMaker.spec(p.copy(oled = true, art = ThemeMaker.Art.LOW))
        assertEquals(0.22f, o.stoneLight); assertEquals(0.30f, o.paneLight); assertEquals(0.8f, o.vignette)
        assertEquals(ThemeMaker.OLED_RAISED_L, ThemeMaker.toHsl(o.raised)[2], 0.006f)
        val hi = ThemeMaker.spec(p.copy(oled = true, art = ThemeMaker.Art.HIGH))
        assertEquals(0.40f, hi.stoneLight); assertEquals(0.50f, hi.paneLight)
        // the user's pick 9 (2026-11-17): "Medium" = stone 0.30 / pane 0.40, the maker's Mid and every OLED preset's
        val mid = ThemeMaker.spec(p.copy(oled = true, art = ThemeMaker.Art.MID))
        assertEquals(0.30f, mid.stoneLight); assertEquals(0.40f, mid.paneLight)
        for (t in ThemePresets.ALL.filter { it.oled }) { assertEquals(t.id, 0.30f, t.stoneLight); assertEquals(t.id, 0.40f, t.paneLight) }
        // a metal pick carries its edge pair and its recolour
        val j = ThemeMaker.spec(p.copy(metal = "jade"))
        assertEquals(0x9FD6B8, rgb(j.edge)); assertEquals(0x2E4A3C, rgb(j.edgeDim)); assertNotNull(j.metal)
        // a pink accent's button words are a pale tint, not pink on red
        val r = ThemeMaker.spec(p.copy(accent = 0xFFFF8CC6.toInt()))
        assertTrue(ThemeMaker.hsv(r.btnLabel)[1] < 0.2f)
    }

    /** the guard moves what it must and says so (the maker's auto mark); a pick that reads as it is moves nothing */
    @Test fun guardReportsWhatItMoved() {
        val ok = ThemeMaker.derive(ThemeMaker.Picks(0xFFFFD100.toInt(), "bronze", 32f, 0.25f, 0.045f, 0xFFE8DCC0.toInt(), false, ThemeMaker.Art.MID))
        assertFalse(ok.textMoved); assertFalse(ok.accentMoved)
        // silver-white accent over paper-white words: the words step down until the accent stands apart
        val sw = ThemeMaker.derive(ThemeMaker.Picks(0xFFF4F8FF.toInt(), "silver", 0f, 0f, 0.045f, 0xFFF0F0F0.toInt(), true, ThemeMaker.Art.MID))
        assertTrue(sw.textMoved)
        assertTrue(ThemeMaker.apart(sw.spec.accent, sw.spec.text))
        // an accent too dark for the page (not curated: a stored value) is lifted
        val dark = ThemeMaker.derive(ThemeMaker.Picks(0xFF402020.toInt(), "bronze", 32f, 0.25f, 0.07f, 0xFFE8DCC0.toInt(), false, ThemeMaker.Art.MID))
        assertTrue(dark.accentMoved)
        assertTrue(k(dark.spec.accent, dark.spec.ink) >= 4.5)
    }

    @Test fun storageRoundTrips() {
        for (p in every().filterIndexed { i, _ -> i % 997 == 0 }) assertEquals(p, ThemeMaker.decode(ThemeMaker.encode(p)))
        val s = ThemeMaker.encode(ThemeMaker.CLASSIC_PICKS)
        assertTrue(s, s.startsWith("{\"v\":1,\"accent\":\"#FFD100\",\"metal\":\"bronze\",\"tint\":["))
        assertNull(ThemeMaker.decode(null)); assertNull(ThemeMaker.decode("")); assertNull(ThemeMaker.decode("{nonsense"))
        // an unknown metal falls back to bronze; an unknown art level to Mid
        val q = ThemeMaker.decode("{\"v\":1,\"accent\":\"#7FE0A8\",\"metal\":\"mithril\",\"tint\":[140,0.25,0.045],\"text\":\"#E4EDF4\",\"oled\":true,\"art\":\"max\"}")!!
        assertEquals("bronze", q.metal); assertEquals(ThemeMaker.Art.MID, q.art); assertTrue(q.oled)
    }

    /** a premade's picks snap to swatches; the maker opens on them */
    @Test fun snapAndStart() {
        val s = ThemeMaker.startOf(ThemePresets.OLED_JADE)
        assertEquals(0x7FE0A8, rgb(s.accent)); assertEquals("jade", s.metal); assertEquals(0xE4EDF4, rgb(s.text))   // E2EEE6 -> Ice
        assertEquals(140f, s.tintHue); assertTrue(s.oled)
        val v = ThemeMaker.startOf(ThemePresets.DARK_VIOLET)
        assertEquals(0.045f, v.tintL)                                                                                  // 0.05 -> Mid
        val c = ThemeMaker.startOf(ThemePresets.CLASSIC)
        assertEquals(ThemeMaker.CLASSIC_PICKS, c)
        // the neutral tint (no saturation) snaps to Neutral, not to the nearest hue
        val n = ThemeMaker.snap(ThemeMaker.CLASSIC_PICKS.copy(tintHue = 200f, tintSat = 0f))
        assertEquals(0f, n.tintSat); assertEquals("Neutral", ThemeMaker.TINTS.first { it.sat == n.tintSat && it.hue == n.tintHue }.name)
    }

    @Test fun hslRoundTrips() {
        for (c in listOf(0xFFFFD100.toInt(), 0xFF0E0B08.toInt(), 0xFF7FE0A8.toInt(), 0xFFC7A0FF.toInt(), 0xFF808080.toInt())) {
            val h = ThemeMaker.toHsl(c)
            val back = ThemeMaker.hsl(h[0], h[1], h[2])
            for (sh in listOf(16, 8, 0)) assertEquals(((c shr sh) and 0xFF).toFloat(), ((back shr sh) and 0xFF).toFloat(), 1.01f)
        }
    }
}
