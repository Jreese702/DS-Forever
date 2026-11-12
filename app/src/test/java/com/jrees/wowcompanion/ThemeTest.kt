package com.jrees.wowcompanion

import android.content.Context
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * THE THEME, LIVE AND SAVED (the user, 2026-11-12, the theme presets): the chosen preset is saved per device and read back at the next
 * start; Theme's colours follow it at once (listeners told, the view tree repainted); the game's own gold never moves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun prefs() = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @After fun back() { Theme.use(ThemePresets.CLASSIC.id) }

    @Test fun choiceIsSavedAndReadBack() {
        prefs().edit().clear().commit()
        Theme.init(app, reread = true)
        assertEquals(ThemePresets.CLASSIC, Theme.cur)                          // nothing saved: Classic Bronze
        Theme.use("oled_rose")
        assertEquals("oled_rose", prefs().getString("theme", null))
        // the next start: the saved one comes back
        Theme.use(ThemePresets.CLASSIC.id)
        prefs().edit().putString("theme", "oled_silver").commit()
        Theme.init(app, reread = true)
        assertEquals(ThemePresets.OLED_SILVER, Theme.cur)
        // a saved id this build does not know: Classic
        prefs().edit().putString("theme", "neon_lime").commit()
        Theme.init(app, reread = true)
        assertEquals(ThemePresets.CLASSIC, Theme.cur)
    }

    @Test fun tokensFollowTheThemeAndGameGoldStays() {
        Theme.init(app, reread = true)
        for (t in Theme.presets) {
            Theme.use(t.id)
            assertEquals(t.ink, Theme.ink); assertEquals(t.panel, Theme.panel); assertEquals(t.raised, Theme.raised)
            assertEquals(t.accent, Theme.gold); assertEquals(t.edge, Theme.bronze); assertEquals(t.text, Theme.text)
            assertEquals(t.soft, UiKit.SOFT_GOLD)
            assertEquals(0x80, Theme.focusA(0x80) ushr 24); assertEquals(t.focus and 0xFFFFFF, Theme.focusA(0x80) and 0xFFFFFF)
            // the game's meanings: a quest mark, an unknown class, an invite, a zone without a PvP type
            assertEquals(0xFFFFD100.toInt(), Theme.gameGold)
            assertEquals(Theme.gameGold, Theme.classColourOf("NOT_A_CLASS"))
            assertEquals(Theme.gameGold, Theme.invite(0))
            assertEquals(Theme.gameGold, Theme.zoneColour(null))
            assertEquals(0xFFFF8000.toInt(), Theme.quality(5))
        }
    }

    @Test fun listenersHearAChangeOnce() {
        Theme.init(app, reread = true)
        Theme.use(ThemePresets.CLASSIC.id)
        var heard = 0
        val l: () -> Unit = { heard++ }
        Theme.onChange(l)
        Theme.use("oled_gold"); Theme.use("oled_gold")                            // the same one again: nothing to tell
        assertEquals(1, heard)
        assertEquals(ThemePresets.CLASSIC, Theme.prev)
        Theme.removeListener(l)
        Theme.use("dark_rose")
        assertEquals(1, heard)
    }

    /** a view built in one theme (its background and words copied then) is brought to the next by ThemeLive.repaint */
    @Test fun repaintCarriesBuiltColoursOver() {
        Theme.init(app, reread = true)
        Theme.use(ThemePresets.CLASSIC.id)
        val root = FrameLayout(app).apply { setBackgroundColor(Theme.ink) }
        val bar = FrameLayout(app).apply { setBackgroundColor(Theme.panel) }
        val words = TextView(app).apply { setTextColor(Theme.gold) }
        val other = TextView(app).apply { setTextColor(0xFF1EFF00.toInt()) }      // the game's green: not a theme colour
        bar.addView(words); root.addView(bar); root.addView(other)
        Theme.use("dark_silver")
        ThemeLive.repaint(root, Theme.prev, Theme.cur)
        val t = ThemePresets.DARK_SILVER
        assertEquals(t.ink, (root.background as android.graphics.drawable.ColorDrawable).color)
        assertEquals(t.panel, (bar.background as android.graphics.drawable.ColorDrawable).color)
        assertEquals(t.accent, words.currentTextColor)
        assertEquals(0xFF1EFF00.toInt(), other.currentTextColor)
    }

    /** a member made with [themed] is made again after a change, and only then */
    @Test fun themedMembersRebuild() {
        Theme.use(ThemePresets.CLASSIC.id)
        var made = 0
        val holder = object { val p by themed { made++; android.graphics.Paint().apply { color = Theme.gold } } }
        assertEquals(ThemePresets.CLASSIC.accent, holder.p.color)
        holder.p; holder.p
        assertEquals(1, made)
        Theme.use("oled_frost")
        assertEquals(ThemePresets.OLED_FROST.accent, holder.p.color)
        assertEquals(2, made)
        assertTrue(Theme.cur.oled)
    }
}
