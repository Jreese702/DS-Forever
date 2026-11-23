package com.jrees.wowcompanion

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * BROWSER LOGIN HANDS THE GAME ACCOUNT OVER (the user's ruling 2026-11-23, "Browser login hands it over (Recommended)"): only the
 * remembered one, only when Battle.net lists it for this login, in ONE format function; learned from the game's own mark or the row
 * picked in its list; held after the game still showed its list, never a loop. Placeholder names only (WoW1 / WoW2).
 */
class GameAccountHandoverTest {
    private val w1 = GameAccountHandover.Account("WoW1", "US")
    private val w2 = GameAccountHandover.Account("WoW2", "US")
    private lateinit var p: Prefs

    @Before fun start() { p = Prefs(); Settings.startForTest(p); GameAccountHandover.beginRun(false) }
    @After fun done() { Settings.startForTest(null); GameAccountHandover.beginRun(false) }

    // ---------------------------------------------------------------- the format: one function, the helper's frame

    @Test fun theFormatIsOneFunction() {
        assertEquals("WoW1" to "US", GameAccountHandover.registryValues(w1))
        assertEquals("My Old Name" to "EU", GameAccountHandover.registryValues(GameAccountHandover.Account("My Old Name", "eu")))
        for (bad in listOf(GameAccountHandover.Account("WoW1", "USA"), GameAccountHandover.Account("WoW1", ""),
                GameAccountHandover.Account(" WoW1", "US"), GameAccountHandover.Account("x".repeat(65), "US"),
                GameAccountHandover.Account("WoWé", "US"))) assertNull(bad.toString(), GameAccountHandover.registryValues(bad))
        assertEquals("US:WoW1", String(GameAccountHandover.frame(GameAccountHandover.Choice.Hand(w1)), Charsets.US_ASCII))
        assertEquals("-", String(GameAccountHandover.frame(GameAccountHandover.Choice.None("Ask each time")), Charsets.US_ASCII))
        // an unusable account is never framed: the game asks
        assertEquals("-", String(GameAccountHandover.frame(GameAccountHandover.Choice.Hand(GameAccountHandover.Account("WoW1", "U"))), Charsets.US_ASCII))
        // the helper's frame limit (tools/wow-session-bridge/protocol.h BRIDGE_ACCOUNT_FRAME_LIMIT)
        assertEquals(67, GameAccountHandover.frame(GameAccountHandover.Choice.Hand(GameAccountHandover.Account("x".repeat(64), "KR"))).size)
    }

    @Test fun theLogNamesNoAccount() {
        val hand = GameAccountHandover.words(GameAccountHandover.Choice.Hand(w1))
        assertEquals("handed over (4 characters, region US)", hand)
        assertEquals("none handed over (Ask each time): the game asks", GameAccountHandover.words(GameAccountHandover.Choice.None("Ask each time")))
        for (s in listOf(hand, w1.toString(), GameAccountHandover.Choice.Hand(w1).toString())) assertFalse(s, s.contains("WoW"))
    }

    // ---------------------------------------------------------------- the choice

    @Test fun onlyTheRememberedOneOnThisLogin() {
        val both = listOf(w1, w2)
        fun pick(ask: Boolean = false, remembered: String? = "WoW2", held: Boolean = false, accounts: List<GameAccountHandover.Account>? = both) =
            GameAccountHandover.choose(ask, remembered, held, accounts)
        assertEquals("WoW2", (pick() as GameAccountHandover.Choice.Hand).account.name)
        assertEquals("WoW2", (pick(remembered = "wow2") as GameAccountHandover.Choice.Hand).account.name)   // Battle.net's spelling goes over
        assertEquals(GameAccountHandover.Choice.None("Ask each time"), pick(ask = true))
        assertEquals(GameAccountHandover.Choice.None("none remembered yet"), pick(remembered = null))
        assertEquals(GameAccountHandover.Choice.None("this login's game accounts unread"), pick(accounts = null))
        assertEquals(GameAccountHandover.Choice.None("no WoW game account listed"), pick(accounts = emptyList()))
        assertEquals(GameAccountHandover.Choice.None("one game account: no list"), pick(accounts = listOf(w2)))
        assertEquals(GameAccountHandover.Choice.None("the remembered one is not on this login"), pick(remembered = "WoW3"))
        assertTrue(pick(held = true) is GameAccountHandover.Choice.None)
        // two accounts of the same name (case only): never guessed
        assertTrue(pick(accounts = listOf(w2, GameAccountHandover.Account("wow2", "EU"))) is GameAccountHandover.Choice.None)
    }

    // ---------------------------------------------------------------- learning the pick

    @Test fun theFolderNamesTheAccount() {
        val names = listOf("WoW1", "WoW2")
        assertEquals("WoW2", GameAccountHandover.learn("11111111#2", names))
        assertEquals("WoW1", GameAccountHandover.learn("11111111#1", names))
        assertNull(GameAccountHandover.learn("11111111#11", names))
        assertEquals("WoW2", GameAccountHandover.learn("WOW2", names))
        assertEquals("FAKEACCT", GameAccountHandover.learn("fakeacct", listOf("WoW1", "FAKEACCT")))
        assertNull(GameAccountHandover.learn("SOMEONE", names))
        assertNull(GameAccountHandover.learn(null, names))
    }

    @Test fun theNewestFolderWrittenAfterThePick() {
        val dir = Files.createTempDirectory("acct").toFile()
        val one = File(dir, "11111111#1").apply { mkdirs() }
        val two = File(dir, "11111111#2").apply { mkdirs() }
        File(dir, "SavedVariables").mkdirs()
        val sv = File(two, "SavedVariables").apply { mkdirs() }
        File(one, "config-cache.wtf").apply { writeText("x"); setLastModified(1_000_000L) }; one.setLastModified(1_000_000L)
        File(sv, "WowCompanion.lua").apply { writeText("x"); setLastModified(3_000_000L) }; two.setLastModified(1_000_000L); sv.setLastModified(1_000_000L)
        assertEquals("11111111#2", GameAccountHandover.newestFolder(dir, 2_000_000L))
        assertNull(GameAccountHandover.newestFolder(dir, 4_000_000L))              // nothing written since the pick: nothing learned
        assertNull(GameAccountHandover.newestFolder(File(dir, "none"), 0L))
    }

    /** Play's "!" mark: only a mark the game wrote anew replaces the pick; a stale one never undoes a Browser-login pick */
    @Test fun aStaleMarkNeverUndoesAPick() {
        assertTrue(GameAccountHandover.markIsNew("WoW1"))
        assertFalse(GameAccountHandover.markIsNew("WoW1"))
        assertFalse(GameAccountHandover.markIsNew(null))                           // the mark went (Ask each time clears it) ...
        assertTrue(GameAccountHandover.markIsNew("WoW1"))                          // ... so the same name marked again is fresh
        assertTrue(GameAccountHandover.markIsNew("WoW2"))
        assertFalse((p.m[GameAccountHandover.KEY] as String).contains("WoW"))      // a hash, never the name
    }

    // ---------------------------------------------------------------- the whole cycle (Browser login, the game's log)

    private fun list(shown: Boolean) = GameAccountDiag.LogList(2, 0L, 0L, done = true, shown = shown)

    @Test fun learnHandOverAndSkip() {
        val dir = Files.createTempDirectory("acct").toFile()
        // run 1: nothing remembered: the list shows, the player picks WoW2
        GameAccountHandover.beginRun(true)
        assertEquals(GameAccountHandover.Choice.None("none remembered yet"), GameAccountHandover.decide(dir, listOf(w1, w2), now = 1_000L))
        assertFalse(GameAccountHandover.handedThisRun)
        assertEquals("Game account hand-over: the list was answered; the pick is learned at the next Browser login",
            GameAccountHandover.observe(list(shown = true), now = 2_000L))
        assertNull(GameAccountHandover.observe(list(shown = true), now = 2_500L))  // once per run
        File(dir, "11111111#2/SavedVariables").mkdirs()
        File(dir, "11111111#2/SavedVariables/x.lua").apply { writeText("x"); setLastModified(System.currentTimeMillis()) }
        // run 2: learned from the folder the game wrote, handed over at once
        GameAccountHandover.beginRun(true)
        val c = GameAccountHandover.decide(dir, listOf(w1, w2), now = 3_000L)
        assertEquals("WoW2", (c as GameAccountHandover.Choice.Hand).account.name)
        assertEquals("WoW2", Settings.gameAccountPick)
        assertTrue(GameAccountHandover.handedThisRun && GameAccountHandover.state().unseen)
        assertEquals("Game account hand-over: the game skipped its list", GameAccountHandover.observe(list(shown = false)))
        assertEquals(GameAccountHandover.State(mark = ""), GameAccountHandover.state())
        assertTrue(GameAccountHandover.reportWords().startsWith("handed over (4 characters, region US); ready"))
    }

    /** the game still showed its list after a hand-over: held, the player picks as before; no hand-over again until something changes */
    @Test fun aListAfterAHandOverIsHeldNeverALoop() {
        Settings.gameAccountPick = "WoW1"
        GameAccountHandover.beginRun(true)
        assertTrue(GameAccountHandover.decide(null, listOf(w1, w2)) is GameAccountHandover.Choice.Hand)
        assertEquals("Game account hand-over: the game still showed its list: held (the player picks as before)",
            GameAccountHandover.observe(list(shown = true)))
        for (run in 1..3) {
            GameAccountHandover.beginRun(true)
            assertEquals(GameAccountHandover.Choice.None("held after an earlier hand-over (the list showed, or no outcome was seen)"),
                GameAccountHandover.decide(null, listOf(w1, w2)))
            GameAccountHandover.observe(list(shown = true))
        }
        assertTrue(GameAccountHandover.reportWords().contains("held"))
        // the Game account setting changed (either way): tried again
        Settings.gameAccountAsk = true
        Settings.gameAccountAsk = false
        GameAccountHandover.beginRun(true)
        assertTrue(GameAccountHandover.decide(null, listOf(w1, w2)) is GameAccountHandover.Choice.Hand)
    }

    /** a hand-over whose outcome never reached the game's log (a refused login, the app gone): held, so it costs one start at most */
    @Test fun anUnseenOutcomeHolds() {
        assertEquals(1, GameAccountHandover.MAX_STRIKES)
        Settings.gameAccountPick = "WoW1"
        GameAccountHandover.beginRun(true)
        assertTrue(GameAccountHandover.decide(null, listOf(w1, w2)) is GameAccountHandover.Choice.Hand)
        GameAccountHandover.beginRun(true)
        assertEquals(GameAccountHandover.Choice.None("held after an earlier hand-over (the list showed, or no outcome was seen)"),
            GameAccountHandover.decide(null, listOf(w1, w2)))
        assertEquals(1, GameAccountHandover.state().strikes)
        // a skip seen clears the strikes
        GameAccountHandover.remember("WoW2")
        GameAccountHandover.beginRun(true)
        assertTrue(GameAccountHandover.decide(null, listOf(w1, w2)) is GameAccountHandover.Choice.Hand)
        GameAccountHandover.observe(list(shown = false))
        assertEquals(0, GameAccountHandover.state().strikes)
    }

    @Test fun theSavedLoginAndAskEachTimeAreLeftAsTheyWere() {
        Settings.gameAccountPick = "WoW1"
        GameAccountHandover.beginRun(false)                                         // the saved login: nothing observed or learned
        assertNull(GameAccountHandover.observe(list(shown = true)))
        assertEquals(0L, GameAccountHandover.state().learnAfter)
        Settings.gameAccountAsk = true
        GameAccountHandover.beginRun(true)
        assertEquals(GameAccountHandover.Choice.None("Ask each time"), GameAccountHandover.decide(null, listOf(w1, w2)))
        assertEquals("-", String(GameAccountHandover.frame(GameAccountHandover.Choice.None("Ask each time")), Charsets.US_ASCII))
        // forgetting the login: no pick, nothing held or waiting
        Settings.forgetGameAccountPick()
        assertNull(Settings.gameAccountPick)
        assertEquals(0L, GameAccountHandover.state().learnAfter)
    }

    /** the setting's words no longer promise a skip where none happens (es / fr too) */
    @Test fun theRememberedWordsSayWhereItSkips() {
        assertEquals("Skip (Browser login)", GeneralTiles.REMEMBERED)
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n").map { File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) {
            val plain = org.json.JSONObject(File(dir, "$l.json").readText()).getJSONObject("plain")
            assertTrue(l, plain.has(GeneralTiles.REMEMBERED) && plain.getString(GeneralTiles.REMEMBERED) != GeneralTiles.REMEMBERED)
            assertFalse(l, plain.has("Remembered"))
        }
    }

    /** Browser login's wiring: the lookup after the credential, the frame to the helper, the outcome from the game's log */
    @Test fun theWiring() {
        fun src(p: String) = listOf("../$p", p).map { File(it) }.first { it.isFile }.readText()
        val login = src("app/src/main/java/app/gamenative/ui/screen/wow/auth/BattleNetBrowserLogin.kt")
        val token = login.indexOf("client.verifyAndGenerateGameToken(ticket).also {")
        assertTrue(token > 0 && login.indexOf("account = handover(attempt, client.gameAccounts())", token) > token)
        assertTrue(login.contains("peer.launch(token, account)"))
        assertTrue(login.contains("GameAccountHandover.beginRun(browser)"))
        assertTrue(src("companion/app/src/main/java/com/jrees/wowcompanion/HomeLoginWatch.kt").contains("GameAccountHandover.observe(GameAccountDiag.logList)"))
        val play = src("app/src/main/java/app/gamenative/ui/screen/wow/WoWForeverScreen.kt")
        assertTrue(play.contains("if (learned != null && markNew && learned != pickBefore) com.jrees.wowcompanion.GameAccountHandover.remember(learned)"))
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
