package com.jrees.wowcompanion

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A: THE GAME ACCOUNT PICKED AUTOMATICALLY (the user's ruling 2026-11-20; the accountList writer ported from WoW Foreverywhere,
 * YoruDev-Ryland, 8f43e2ac / 565629ef / 432f4a1f, GPL-3.0). "Remembered" marks the learned label "!" in accountList at every start
 * (the game's other names kept); "Ask each time" writes no pick; the label is learned from the game's own marker, never typed, kept in
 * the app's private settings and never reset. Fake labels only.
 */
class GameAccountPickTest {
    private val a = "FAKEACCT1"
    private val b = "FAKEACCT2"
    private val mail = "player@example.invalid"
    private val us = mapOf("Branch" to "us")

    @After fun done() { Settings.startForTest(null) }

    private fun wtf(root: File) = File(root, "${WowProduct.LAUNCH.flavorDir}/WTF/Config.wtf")
    private fun seed(lines: List<String>): File {
        val root = Files.createTempDirectory("pick").toFile()
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us)
        wtf(root).appendText(lines.joinToString("\n") + "\n")
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us)        // settled
        return root
    }
    private fun accountLines(root: File) = wtf(root).readLines().filter { it.contains("accountList", ignoreCase = true) }

    @Test fun theFork_sMarkingRules() {
        assertEquals("!$b|", GameConfigWtf.accountListValue(null, b))
        assertEquals("$a|!$b|", GameConfigWtf.accountListValue("\"!$a|$b|\"", b))
        assertEquals("$a|!$b|", GameConfigWtf.accountListValue("!$a|", b))               // added when the game has not seen it
        assertEquals("!${b.lowercase()}|$a|", GameConfigWtf.accountListValue("${b.lowercase()}|!$a|", b))   // the game's spelling kept
    }

    @Test fun labelsThatWouldBreakTheLineAreRefused() {
        assertEquals("My Account", GameConfigWtf.cleanAccountLabel("  My Account "))
        for (bad in listOf(null, "", "   ", "a|b", "a\"b", "a\nb", "!$a", "x".repeat(65))) assertNull(bad, GameConfigWtf.cleanAccountLabel(bad))
        // markAccount never writes an unusable label
        val lines = listOf("SET portal \"us\"")
        assertEquals(lines, GameConfigWtf.markAccount(lines, "a|b"))
    }

    @Test fun markAccountKeepsOneLineInPlaceAndEveryOtherLine() {
        val lines = listOf("SET gxApi \"D3D11\"", "SET accountList \"!$a|$b|\"", "SET accountName \"$mail\"", "set ACCOUNTLIST \"$a|\"")
        assertEquals(listOf("SET gxApi \"D3D11\"", "SET accountList \"$a|!$b|\"", "SET accountName \"$mail\""), GameConfigWtf.markAccount(lines, b))
        val marked = listOf("SET accountList \"$a|!$b|\"")
        assertTrue(GameConfigWtf.markAccount(marked, b) === marked)                       // already so: the same lines
        assertEquals(listOf("SET portal \"us\"", "SET accountList \"!$a|\""), GameConfigWtf.markAccount(listOf("SET portal \"us\""), a))
    }

    @Test fun thePickIsLearnedFromTheGamesOwnMarkerOnly() {
        assertEquals(b, GameConfigWtf.accountPickIn(listOf("SET accountList \"$a|!$b|\"", "SET accountName \"$mail\"")))
        assertNull(GameConfigWtf.accountPickIn(listOf("SET accountList \"$a|$b|\"")))         // no marker: nothing learned
        assertNull(GameConfigWtf.accountPickIn(listOf("SET accountName \"!$mail\"")))         // never the Battle.net login name
        assertNull(GameConfigWtf.accountPickIn(emptyList()))
        val root = seed(listOf("SET accountList \"!$a|$b|\""))
        assertEquals(a, GameConfigWtf.accountPickIn(root, WowProduct.LAUNCH))
        assertNull(GameConfigWtf.accountPickIn(Files.createTempDirectory("none").toFile(), WowProduct.LAUNCH))
    }

    @Test fun rememberedMarksThePickAtEveryStart() {
        // the game left its list without a marker (or something cleared it): the pick is marked, the others and the login name stay
        val root = seed(listOf("SET accountList \"$a|$b|\"", "SET accountName \"$mail\""))
        val before = wtf(root).readLines()
        assertEquals(listOf("accountList"), GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, accountPick = b))
        assertEquals(listOf("SET accountList \"$a|!$b|\""), accountLines(root).filter { it.startsWith("SET accountList") })
        assertTrue(wtf(root).readLines().contains("SET accountName \"$mail\""))
        assertEquals(before.filterNot { it.startsWith("SET accountList") }, wtf(root).readLines().filterNot { it.startsWith("SET accountList") })
        // the next start: nothing more to write
        val text = wtf(root).readText(); val stamp = wtf(root).lastModified()
        assertEquals(emptyList<String>(), GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, accountPick = b))
        assertEquals(text, wtf(root).readText()); assertEquals(stamp, wtf(root).lastModified())
        // no line at all: added
        val bare = seed(emptyList())
        GameConfigWtf.ensure(bare, WowProduct.LAUNCH, us, accountPick = a)
        assertEquals(listOf("SET accountList \"!$a|\""), accountLines(bare))
        // a new Config.wtf gets it too
        val fresh = Files.createTempDirectory("pick-new").toFile()
        assertEquals(listOf("accountList"), GameConfigWtf.ensure(fresh, WowProduct.LAUNCH, us, accountPick = a))
        assertEquals(listOf("SET accountList \"!$a|\""), accountLines(fresh))
    }

    @Test fun askEachTimeWritesNoPick() {
        val root = seed(listOf("SET accountList \"$a|!$b|\""))
        assertEquals(listOf("accountList"), GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, askAccount = true, accountPick = b))
        assertTrue(accountLines(root).isEmpty())
        // the next start (still Ask): nothing written, the game shows its list
        val text = wtf(root).readText()
        assertEquals(emptyList<String>(), GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, askAccount = true, accountPick = b))
        assertEquals(text, wtf(root).readText())
        val fresh = Files.createTempDirectory("pick-ask").toFile()
        assertEquals(emptyList<String>(), GameConfigWtf.ensure(fresh, WowProduct.LAUNCH, us, askAccount = true, accountPick = b))
        assertTrue(accountLines(fresh).isEmpty())
    }

    @Test fun noPickLearnedYetLeavesTheGamesList() {
        val root = seed(listOf("SET accountList \"$a|$b|\""))
        val text = wtf(root).readText()
        assertEquals(emptyList<String>(), GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, accountPick = null))
        assertEquals(text, wtf(root).readText())
    }

    /** learn-then-write: the game's own new marker (the player picked another account in its list) wins over the stored label */
    @Test fun theGamesNewPickWinsAtTheNextStart() {
        val p = Prefs(); Settings.startForTest(p)
        Settings.gameAccountPick = a
        val root = seed(listOf("SET accountList \"$a|!$b|\""))
        GameConfigWtf.accountPickIn(root, WowProduct.LAUNCH)?.let { Settings.gameAccountPick = it }
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, accountPick = Settings.gameAccountPick)
        assertEquals(b, Settings.gameAccountPick)
        assertEquals(listOf("SET accountList \"$a|!$b|\""), accountLines(root))
    }

    @Test fun theLabelIsKeptCleanAndForgottenWithTheLogin() {
        val p = Prefs(); Settings.startForTest(p)
        assertNull(Settings.gameAccountPick)
        Settings.gameAccountPick = " $a "
        assertEquals(a, Settings.gameAccountPick)
        assertEquals(a, p.m["gameAccountPick"])
        Settings.gameAccountPick = "bad|label"                  // unusable: removed, never stored
        assertNull(Settings.gameAccountPick); assertFalse(p.m.containsKey("gameAccountPick"))
        Settings.gameAccountPick = b
        Settings.forgetGameAccountPick()
        assertNull(Settings.gameAccountPick)
    }

    @Test fun resetSettingsNeverTouchesThePick() {
        val all = ResetPlan.Kind.values().toSet()
        assertEquals(emptySet<String>(), ResetPlan.prefKeys("settings", setOf("gameAccountPick", "gameAccountAsk"), all, null, true))
        assertNull(ResetPlan.ruleOf("settings", "gameAccountPick")!!.kind)
    }

    /** privacy: the launch log line and the report name no label (the call site's words: "set" / "unset", "same" / "new") */
    @Test fun noLabelInTheLogOrReport() {
        val src = listOf("../app/src/main/java/app/gamenative/ui/screen/wow/WoWForeverScreen.kt", "app/src/main/java/app/gamenative/ui/screen/wow/WoWForeverScreen.kt")
            .map { File(it) }.first { it.isFile }.readText()
        val at = src.indexOf("val learned = com.jrees.wowcompanion.GameConfigWtf.accountPickIn")
        assertTrue(at > 0)
        val block = src.substring(at, src.indexOf("} catch (e: Exception)", at))
        for (line in block.lines().filter { it.contains("Timber.") || it.trim().startsWith("if (pick") || it.contains("%s") }) {
            assertFalse(line, Regex("""Timber\.[a-z]\([^)]*,\s*(pick|learned|pickBefore)\s*[,)]""").containsMatchIn(line))
        }
        val root = seed(listOf("SET accountList \"$a|!$b|\""))
        val report = GameConfigWtf.reportLine(wtf(root).readText())
        assertFalse(report.contains(a) || report.contains(b))
    }

    /** streaming: with no marked list on the Thor, the learned label alone goes to Desktop (which keeps the PC's own pick) */
    @Test fun theLabelAloneIsAValidListForDesktop() {
        val v = GameConfigWtf.accountListValue(null, a)
        assertTrue(v.split('|').any { it.startsWith("!") } && v.none { it == '"' || it == '\n' })
    }

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
        override fun edit(): SharedPreferences.Editor = Ed()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        inner class Ed : SharedPreferences.Editor {
            override fun putString(k: String?, v: String?) = apply { m[k!!] = v }
            override fun putStringSet(k: String?, v: MutableSet<String>?) = apply { m[k!!] = v }
            override fun putInt(k: String?, v: Int) = apply { m[k!!] = v }
            override fun putLong(k: String?, v: Long) = apply { m[k!!] = v }
            override fun putFloat(k: String?, v: Float) = apply { m[k!!] = v }
            override fun putBoolean(k: String?, v: Boolean) = apply { m[k!!] = v }
            override fun remove(k: String?) = apply { m.remove(k) }
            override fun clear() = apply { m.clear() }
            override fun commit() = true
            override fun apply() {}
        }
    }
}
