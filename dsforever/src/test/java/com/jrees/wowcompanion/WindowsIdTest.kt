package com.jrees.wowcompanion

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * WINDOWS ID, THE SETTING, off-device (the user, 2026-11-14: "build it"; the BLZ51914400 wave): Settings > General > Account > Windows
 * ID, Per install (the default) / Original; the restart row while the running game started with the other one; the problem report's
 * line names the mode only. (The prefix side - what Original writes back - is app's WindowsIdApplyTest.)
 */
class WindowsIdTest {
    private class Prefs(val m: HashMap<String, Any?> = HashMap()) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = m
        override fun getString(k: String?, d: String?) = m[k] as? String ?: d
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(k: String?, d: MutableSet<String>?) = m[k] as? MutableSet<String> ?: d
        override fun getInt(k: String?, d: Int) = m[k] as? Int ?: d
        override fun getLong(k: String?, d: Long) = m[k] as? Long ?: d
        override fun getFloat(k: String?, d: Float) = m[k] as? Float ?: d
        override fun getBoolean(k: String?, d: Boolean) = m[k] as? Boolean ?: d
        override fun contains(k: String?) = m.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val put = HashMap<String, Any?>(); val gone = HashSet<String>()
            override fun putString(k: String, v: String?) = apply { put[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = apply { put[k] = v }
            override fun putInt(k: String, v: Int) = apply { put[k] = v }
            override fun putLong(k: String, v: Long) = apply { put[k] = v }
            override fun putFloat(k: String, v: Float) = apply { put[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { put[k] = v }
            override fun remove(k: String) = apply { gone.add(k) }
            override fun clear() = apply { gone.addAll(m.keys) }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { for (k in gone) m.remove(k); m.putAll(put) }
        }
    }

    @Before fun up() { ThirdPartyAddons.noFetch = true; WindowsId.forgetLaunchForTest() }
    @After fun down() { Settings.startForTest(null); ThirdPartyAddons.noFetch = false; WindowsId.forgetLaunchForTest() }

    private fun stamp(ms: Long) = "T$ms"

    @Test fun twoModesPerInstallFirst() {
        assertEquals(listOf(WindowsId.PER_INSTALL, WindowsId.ORIGINAL), WindowsId.VALUES)
        assertEquals(listOf("Per install", "Original"), WindowsId.SEGMENTS)
        assertEquals(WindowsId.PER_INSTALL, WindowsId.DEFAULT)
        for (v in listOf(null, "", "Original", "ORIGINAL", "per install", "garbage")) assertEquals("$v", WindowsId.PER_INSTALL, WindowsId.sanitize(v))
        assertEquals(WindowsId.ORIGINAL, WindowsId.sanitize("original"))
        assertEquals("Original", WindowsId.label("original"))
        assertEquals("Per install", WindowsId.label(null))
    }

    @Test fun theSettingIsSavedAndDefaultsToPerInstall() {
        Settings.startForTest(null)
        assertEquals("no prefs: the default", WindowsId.PER_INSTALL, Settings.windowsId)
        val p = Prefs()
        Settings.startForTest(p)
        assertEquals(WindowsId.PER_INSTALL, Settings.windowsId)
        assertFalse("nothing saved until the player picks", p.m.containsKey(WindowsId.KEY))
        var told = 0
        Settings.onChange { told++ }
        Settings.windowsId = WindowsId.ORIGINAL
        assertEquals("original", p.m[WindowsId.KEY])
        assertEquals(WindowsId.ORIGINAL, Settings.windowsId)
        assertEquals(1, told)
        Settings.windowsId = WindowsId.ORIGINAL                       // no change: no write, no listener
        assertEquals(1, told)
        Settings.windowsId = "whatever"                               // anything else is the default
        assertEquals(WindowsId.PER_INSTALL, Settings.windowsId)
        // a stored value from a future build reads as the default
        Settings.startForTest(Prefs(hashMapOf(WindowsId.KEY to "v3")))
        assertEquals(WindowsId.PER_INSTALL, Settings.windowsId)
        Settings.startForTest(Prefs(hashMapOf(WindowsId.KEY to "original")))
        assertEquals(WindowsId.ORIGINAL, Settings.windowsId)
    }

    @Test fun theLastStartIsKeptAcrossRestarts() {
        val p = Prefs()
        Settings.startForTest(p)
        assertNull(Settings.windowsIdRan)
        Settings.windowsIdRan = Triple(WindowsId.ORIGINAL, 1234L, true)
        Settings.startForTest(p)                                      // the app started again
        assertEquals(Triple(WindowsId.ORIGINAL, 1234L, true), Settings.windowsIdRan)
        Settings.windowsIdRan = Triple("junk", 99L, false)
        assertEquals(Triple(WindowsId.PER_INSTALL, 99L, false), Settings.windowsIdRan)
        Settings.windowsIdRan = null
        assertNull(Settings.windowsIdRan)
        assertTrue(p.m.keys.none { it.startsWith("windowsIdRan") })
    }

    /** the restart row: only with the game up, started with the other identity, and no restart already going */
    @Test fun restartOnlyWhenTheRunningGameUsesTheOther() {
        val pi = WindowsId.PER_INSTALL; val og = WindowsId.ORIGINAL
        assertFalse("no game started in this process", WindowsId.showRestart(true, false, null, og))
        assertFalse(WindowsId.showRestart(true, false, pi, pi))
        assertTrue(WindowsId.showRestart(true, false, pi, og))
        assertTrue(WindowsId.showRestart(true, false, og, pi))
        assertFalse("game closed: the next start uses it anyway", WindowsId.showRestart(false, false, pi, og))
        assertFalse("a restart is under way", WindowsId.showRestart(true, true, pi, og))
        // switched and switched back before a restart: nothing to do
        assertFalse(WindowsId.needsRestart(og, og))
    }

    @Test fun aGameStartIsToldOnce() {
        var told = 0
        WindowsId.onChange { told++ }
        assertNull(WindowsId.launched)
        WindowsId.markLaunched(WindowsId.ORIGINAL, WindowsId.result(3), 5000L)
        assertEquals(WindowsId.ORIGINAL, WindowsId.launched)
        assertEquals(5000L, WindowsId.launchedAtMs)
        assertEquals("applied, 3 value(s) changed", WindowsId.launchedResult)
        assertEquals(1, told)
        assertEquals("could not be applied (IOException)", WindowsId.result(null, "IOException"))
    }

    /** the problem report's line: the choice and what ran, by name; never a value */
    @Test fun theReportLineNamesTheModeOnly() {
        val pi = WindowsId.PER_INSTALL; val og = WindowsId.ORIGINAL
        assertEquals("Windows ID: Per install chosen; no game start recorded",
            WindowsId.reportLine(pi, null, 0L, "", null, 0L, null, ::stamp))
        assertEquals("Windows ID: Original chosen; this run's game start used Original at T7 (applied, 10 value(s) changed)",
            WindowsId.reportLine(og, og, 7L, "applied, 10 value(s) changed", pi, 3L, true, ::stamp))
        assertEquals("Windows ID: Original chosen; this run's game start used Per install at T7 (applied, 0 value(s) changed); a restart is due",
            WindowsId.reportLine(og, pi, 7L, "applied, 0 value(s) changed", null, 0L, null, ::stamp))
        assertEquals("Windows ID: Per install chosen; no game start in this run; the last one used Original at T3",
            WindowsId.reportLine(pi, null, 0L, "", og, 3L, true, ::stamp))
        assertEquals("Windows ID: Original chosen; no game start in this run; the last one used Original at T3 (it could not be applied)",
            WindowsId.reportLine(og, null, 0L, "", og, 3L, false, ::stamp))
        // the report's privacy pass leaves it whole (no word it masks or drops)
        val line = WindowsId.reportLine(og, pi, 7L, WindowsId.result(9), og, 3L, true, ::stamp)
        assertEquals(line, StartupDiag.sanitize(line, emptyList()))
    }

    @Test fun theDiagnosticsLineReadsTheSettings() {
        val p = Prefs()
        Settings.startForTest(p)
        Settings.windowsId = WindowsId.ORIGINAL
        Settings.windowsIdRan = Triple(WindowsId.PER_INSTALL, 1_700_000_000_000L, true)
        val l = StartupDiag.windowsIdLine()
        assertTrue(l, l.startsWith("Windows ID: Original chosen; no game start in this run; the last one used Per install at "))
        WindowsId.markLaunched(WindowsId.ORIGINAL, WindowsId.result(10), 1_700_000_100_000L)
        val l2 = StartupDiag.windowsIdLine()
        assertTrue(l2, l2.startsWith("Windows ID: Original chosen; this run's game start used Original at ") && "restart" !in l2)
        // no GUID, serial, product id or DESKTOP- name shape in it
        for (s in listOf(l, l2)) {
            assertFalse(s, Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}""").containsMatchIn(s))
            assertFalse(s, "DESKTOP-" in s || "AAOEM" in s)
        }
    }

    /** the row: General > Account, under Game account, drawn by the Settings view; its words in both languages */
    @Test fun theRowSitsUnderGameAccount() {
        val b = SettingsTabs.Block.WINDOWS_ID
        assertEquals(SettingsTabs.Group.ACCOUNT, b.group)
        assertEquals(SettingsTabs.Tab.GENERAL, b.tab)
        assertEquals(SettingsTabs.Drawer.VIEW, b.drawer)
        assertEquals(listOf("Windows ID"), b.rows)
        assertEquals(listOf("Battle.net login", "Game account", "Windows ID"), SettingsTabs.blocks(SettingsTabs.Group.ACCOUNT).flatMap { it.rows })
        assertFalse("no picture: the words say it", SettingsPreview.has("Windows ID"))
        for (l in listOf("es", "fr")) {
            val f = listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json")).first { it.canRead() }
            Tr.load(l, f.readText(Charsets.UTF_8))
            for (w in listOf(WindowsId.ROW_ID, "Per install", WindowsId.RESTART_LINE)) assertNotEquals("$l: $w", w, Tr.t(w, l))
            // "Original" is the same word in Spanish; it still has its own line
            assertTrue(l, "\"Original\":" in f.readText(Charsets.UTF_8))
            for (w in listOf("From the next game start", "Restart the game", "Restart")) assertNotEquals("$l: $w", w, Tr.t(w, l))
        }
    }
}
