package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE CAPTCHA HOLD IS RETIRED (the user, 2026-11-16: "RETIRE the captcha hold"): 1.0.32's approved exception kept a read login.txt up
 * to 3 minutes while the bottom screen's Keyboard was open or the game asked for a captcha. The 1.0.32 reports (#334 #422 #423)
 * showed the client reads the file only at its login screen and at its sign-in's start, never at a captcha's send or a later try, so
 * the hold only kept the private file on disk. Back to deleting it 20 s after the client's FIRST read, with the triggers that delete it
 * sooner: the realm connection (the game's log, the root probe), the world, the game's close, a new run and the Menu's Forget login /
 * Auto login off. Off-device: the 20 s on the wall clock, and the wiring in NativeLogin. Timings only: no file and no credential here.
 */
class LoginHoldRetiredTest {

    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val fork = listOf("src/main/java/com/jrees/wowcompanion/fork", "dsforever/src/main/java/com/jrees/wowcompanion/fork")
        .map { File(it) }.first { it.isDirectory }
    private val native by lazy { File(src, "NativeLogin.kt").readText() }

    private val s = 1000L

    // ---- the 20 s after the first read

    @Test fun twentySecondsAfterTheFirstRead() {
        assertEquals(20 * s, PreLoginTraffic.AFTER_READ_MS)
        val read = 5_000_000L
        assertFalse("no read: nothing is due", PreLoginTraffic.afterReadDue(0L, 10 * 60 * s))
        assertFalse(PreLoginTraffic.afterReadDue(read, read))
        assertFalse(PreLoginTraffic.afterReadDue(read, read + 20 * s - 1))
        assertTrue(PreLoginTraffic.afterReadDue(read, read + 20 * s))
        // the device slept across the 20 s (the Handler's delay counts uptime): the first traffic second after the wake deletes
        assertTrue(PreLoginTraffic.afterReadDue(read, read + 10 * 60 * s))
    }

    @Test fun onlyTheFirstReadTimesTheFile() {
        // the login screen's burst of reads and the form's submission ~2 s later never push it out (the 10-08 device tests)
        val watch = native.substringAfter("private fun watch(dir: File)").substringBefore("fun clear(")
        assertEquals("one 20 s timer, posted once", 1, Regex("""postDelayed\(afterRead, AFTER_READ_MS\)""").findAll(native).count())
        val firstBlock = watch.substringAfter("if (first) {\n                        firstReadAt = readAt")
        assertTrue(watch, "main.postDelayed(afterRead, AFTER_READ_MS)" in firstBlock)
        assertFalse("a later read reschedules nothing", Regex("""if \(!first\)""").containsMatchIn(watch))
        // the 20 s count from the first read on elapsedRealtime, set on the read's thread before [consumed] (an earlier file's timer
        // or a traffic second in between never sees a read without it)
        val readBlock = watch.substringAfter("val first = !consumed").substringBefore("consumed = true")
        assertTrue(readBlock, "readElapsed = android.os.SystemClock.elapsedRealtime()" in readBlock)
        val due = native.substringAfter("private fun afterReadDue() {").substringBefore("\n    }")
        assertTrue(due, "if (!active || !consumed || !PreLoginTraffic.afterReadDue(readElapsed, android.os.SystemClock.elapsedRealtime())) return" in due)
        assertTrue(due, "clear()" in due)
        assertTrue("private val afterRead = Runnable { afterReadDue() }" in native)
        // the Handler's timer, and the wall clock on each traffic second (main thread)
        val traffic = native.substringAfter("fun onTraffic(").substringBefore("private fun changed()")
        assertTrue(traffic, Regex("""main\.post \{\s*(//[^\n]*\n\s*)*afterReadDue\(\)""").containsMatchIn(traffic))
        // every delete stops the timer and the wall-clock read time
        val clear = native.substringAfter("fun clear(ctx: Context? = null) {").substringBefore("val dirs")
        assertTrue(clear, "main.removeCallbacks(afterRead); readElapsed = 0L" in clear)
    }

    // ---- the hold is gone

    @Test fun noHoldAnywhere() {
        assertFalse("LoginHold.kt is gone", File(src, "LoginHold.kt").exists())
        for (w in listOf("LoginHold", "hold.", "captchaAsked", "keyboardOpen", "fun keyboard(", "fun captcha(", "holdReason"))
            assertFalse("NativeLogin.kt: $w", w in native)
        assertFalse("no 3-minute cap of the hold's", Regex("""\bCAP_MS\b""").containsMatchIn(native))
        // the unread file's cap is its own again (no read file is left to a hold)
        val capCheck = native.substringAfter("private fun capCheck() {").substringBefore("val now = System.currentTimeMillis()")
        assertEquals(capCheck, "if (!active) return", capCheck.trim())
        // the game's captcha word and the Keyboard no longer reach the file
        val home = File(src, "HomeLogin.kt").readText()
        assertFalse("fun captcha(" in home); assertFalse("AFTER_CAPTCHA" in home)
        val watch = File(src, "HomeLoginWatch.kt").readText()
        assertFalse("NativeLogin.captcha" in watch)
        val screen = File(fork, "DsForeverScreen.kt").readText()
        assertTrue("fun signInBoard(on: Boolean) { boardWanted = on; shown?.board(on) }" in screen)
        assertFalse("NativeLogin.keyboard" in screen)
    }

    // ---- the triggers that delete it sooner

    @Test fun theRealmConnectionTheWorldAndTheCloseStillDeleteAtOnce() {
        assertTrue(HomeLogin.realmUp("10/6 21:09:57.590  [N][Connection] Realm connection connected realmAddress=\"70-1-2\""))
        assertFalse(HomeLogin.realmUp("10/6 21:09:51.178  [N][Connection] Battle.net logon completed result=\"ERROR_OK (0)\""))
        assertFalse(HomeLogin.realmUp("10/6 21:09:59.408  [N][Lua] Switching to screen=\"CharacterSelect\" (from \"login\")"))
        assertTrue("if (HomeLogin.realmUp(line)) NativeLogin.realmUp(\"the game's log\")" in File(src, "HomeLoginWatch.kt").readText())
        val realm = native.substringAfter("fun realmUp(src: String) =").substringBefore("\n")
        assertTrue(realm, "if (active && consumed) {" in realm && "clear()" in realm)
        val world = native.substringAfter("fun worldSeen() {").substringBefore("worldBack()")
        assertTrue(world, "if (active && consumed && !outOfWorld) { logEvent(\"login.txt: in the world, deleting\"); clear() }" in world)
        val end = native.substringAfter("fun end(ctx: Context? = null) {").substringBefore("logEvent(\"client run ends\")")
        assertTrue(end, "clear(ctx)" in end)
        val prepare = native.substringAfter("fun prepare(ctx: Context, gameRoot: File, flavor: String): File? = try {").substringBefore("consumed = false")
        assertTrue(prepare, "clear()" in prepare)
        // the root probe's realm connection (CompanionHost) and the Menu's Forget login / Auto login off, unchanged
        val host = File(src, "CompanionHost.kt").readText()
        assertTrue("if (oneApp && s.realm) NativeLogin.clear(this)" in host)
        assertTrue(host.substringAfter("StartMenu.onForgetLogin = {").substringBefore("\n").contains("NativeLogin.clear()"))
        assertTrue(host.substringAfter("StartMenu.onAutoLogin = {").substringBefore("\n").contains("if (!on) NativeLogin.clear()"))
    }

    @Test fun theDeleteWordsSurviveTheReportsPrivacyPassAndNameNothing() {
        val words = listOf("login.txt: 20 s after the client's first read, deleting", "login.txt: the realm connection is up (the game's log), deleting",
            "login.txt: in the world, deleting")
        assertTrue("login.txt: \${AFTER_READ_MS / 1000} s after the client's first read, deleting" in native)
        assertTrue("login.txt: the realm connection is up (\$src), deleting" in native)
        val pattern = Redact.namePattern(emptyList())
        for (w in words) {
            assertEquals(w, Redact.text(w, pattern))
            assertFalse(w, Regex("[@=]").containsMatchIn(w))
        }
    }

    @Test fun nativeLoginNeverReadsTheFile() {
        // presence and timing only: the one read in NativeLogin is the sign-in LOG's own cap (login.log), never login.txt
        val reads = native.lines().filter { l -> listOf("readText", "readBytes", "InputStream", "bufferedReader", "readLines").any { it in l } }
        assertEquals(reads.toString(), 1, reads.size)
        assertTrue(reads.single(), "val lines = f.readLines()" in reads.single())
    }
}
