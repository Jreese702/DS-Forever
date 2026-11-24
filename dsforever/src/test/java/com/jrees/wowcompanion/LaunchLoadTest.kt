package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

/**
 * THE LAUNCH SCREEN'S RULES (the user, 2026-11-24: our swirl loading screen from Play "until the game's first real picture"): the
 * hand-over comes on the first non-black frames, never on WoW's black ones; a crash, an error or the abort chord ends it at once; a
 * game that never draws is handed over by the timeout (never an endless swirl); the status line's words are the player's, in es / fr
 * too; a second Play after quitting starts clean.
 */
class LaunchLoadTest {
    @Before fun clean() = LaunchLoad.reset()
    @After fun after() = LaunchLoad.reset()

    /** a w x h frame, 4 bytes a pixel, every pixel [v] (RGB), alpha 255 */
    private fun frame(w: Int = 1920, h: Int = 1080, v: Int = 0, stride: Int = w, lit: (Int, Int) -> Int? = { _, _ -> null }): ByteBuffer {
        val b = ByteBuffer.allocate(stride * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val c = lit(x, y) ?: v
            val i = (y * stride + x) * 4
            b.put(i, c.toByte()); b.put(i + 1, c.toByte()); b.put(i + 2, c.toByte()); b.put(i + 3, 255.toByte())
        }
        return b
    }
    private val black = frame()
    /** WoW's login screen fading in: dim at first (a quarter of its brightness) */
    private val login = frame { x, y -> 20 + ((x * 7 + y * 3) % 160) / 4 + if ((x / 60 + y / 60) % 3 == 0) 40 else 0 }
    private fun picture(b: ByteBuffer, n: Int = 0) = LaunchPicture.lit(b, 1920, 1080, 1920, n)

    @Test fun blackFramesAreNoPictureTheLoginScreenIs() {
        for (n in 0 until 32) assertFalse("black frame $n", picture(black, n))
        // near-black noise (a dark clear colour, dithering): still black
        val dark = frame { x, y -> (x * 13 + y * 7) % 21 }
        for (n in 0 until 16) assertFalse("near-black $n", picture(dark, n))
        for (n in 0 until 16) assertTrue("the login screen's dim first frames $n", picture(login, n))
        // a cursor-sized bright spot on black is no picture
        val spot = frame { x, y -> if (x in 900..940 && y in 500..540) 255 else null }
        for (n in 0 until 16) assertFalse("a cursor on black $n", picture(spot, n))
    }

    @Test fun thePictureLookHonoursStrideAndSize() {
        // a padded row (stride 2048 px): the padding is never read as the picture
        val padded = frame(1920, 1080, 0, 2048) { x, _ -> if (x >= 1920) 255 else null }
        assertFalse(LaunchPicture.lit(padded, 1920, 1080, 2048, 0))
        val paddedLit = frame(1920, 1080, 120, 2048)
        assertTrue(LaunchPicture.lit(paddedLit, 1920, 1080, 2048, 0))
        // a small window, or a buffer too small for its size: no picture (never a read past its end)
        assertFalse(LaunchPicture.lit(frame(200, 100, 200), 200, 100, 200, 0))
        assertFalse(LaunchPicture.lit(ByteBuffer.allocate(64), 1920, 1080, 1920, 0))
        assertEquals("the look moves nothing", 0, paddedLit.position())
    }

    @Test fun handsOverOnTheFirstRealPictureNotOnBlackFrames() {
        var t = 1_000L
        LaunchLoad.begin(t)
        assertTrue(LaunchLoad.covers()); assertTrue(LaunchLoad.watching); assertTrue(LaunchLoad.holdsPad())
        LaunchLoad.step("Launching game...", t)
        t += 4_000; LaunchLoad.windowMapped(t)
        assertEquals(LaunchLoad.Phase.WAITING, LaunchLoad.phase)
        assertEquals(LaunchLoad.OPENING, LaunchLoad.line)
        // 20 s of WoW's black frames at 60 fps: the screen stays, fully opaque
        repeat(1200) { t += 16; assertFalse(LaunchLoad.frame(picture(black, it), t)); LaunchLoad.tick(t) }
        assertEquals(LaunchLoad.Phase.WAITING, LaunchLoad.phase)
        assertEquals(1f, LaunchLoad.alpha(t), 0f)
        // one stray lit frame among black ones is not the picture (three in a row are)
        t += 16; LaunchLoad.frame(picture(login), t); t += 16; LaunchLoad.frame(picture(black), t)
        assertEquals(LaunchLoad.Phase.WAITING, LaunchLoad.phase)
        var handed = false
        repeat(LaunchLoad.PICTURE_FRAMES) { t += 16; handed = LaunchLoad.frame(picture(login, it), t) || handed }
        assertTrue("the hand-over on the first real picture", handed)
        assertEquals(LaunchLoad.Phase.FADING, LaunchLoad.phase)
        assertFalse("nothing is looked at after the hand-over", LaunchLoad.watching)
        assertFalse("the pad is the game's again", LaunchLoad.holdsPad())
        assertTrue(LaunchLoad.lastEnd.startsWith("the game's first picture"))
        // the fade: down from 1 to 0 over FADE_MS, then the screen is gone
        val a1 = LaunchLoad.alpha(t + LaunchLoad.FADE_MS / 4); val a2 = LaunchLoad.alpha(t + LaunchLoad.FADE_MS * 3 / 4)
        assertTrue("fades ($a1 > $a2)", a1 > a2 && a1 < 1f && a2 > 0f)
        LaunchLoad.tick(t + LaunchLoad.FADE_MS)
        assertEquals(LaunchLoad.Phase.IDLE, LaunchLoad.phase)
        assertFalse(LaunchLoad.covers())
        // frames after it change nothing
        assertFalse(LaunchLoad.frame(true, t + 2 * LaunchLoad.FADE_MS))
    }

    @Test fun aGameThatNeverDrawsIsHandedOverByTheTimeout() {
        LaunchLoad.begin(0L)
        LaunchLoad.windowMapped(10_000L)
        LaunchLoad.tick(10_000L + LaunchLoad.PICTURE_TIMEOUT_MS - 1)
        assertEquals(LaunchLoad.Phase.WAITING, LaunchLoad.phase)
        LaunchLoad.tick(10_000L + LaunchLoad.PICTURE_TIMEOUT_MS)
        assertEquals("never an endless swirl: the game's own screen", LaunchLoad.Phase.FADING, LaunchLoad.phase)
        assertTrue(LaunchLoad.lastEnd.startsWith("no picture"))
    }

    @Test fun framesThatCannotBeLookedAtHandOverSoon() {
        LaunchLoad.begin(0L); LaunchLoad.windowMapped(1_000L)
        var t = 1_000L
        while (t < 1_000L + LaunchLoad.UNREADABLE_MS - 16) { assertFalse(LaunchLoad.unreadable(t)); t += 16 }
        assertEquals(LaunchLoad.Phase.WAITING, LaunchLoad.phase)
        t += 32
        assertTrue(LaunchLoad.unreadable(t))
        assertEquals(LaunchLoad.Phase.FADING, LaunchLoad.phase)
        // but one readable frame in the launch = we can tell black from a picture: unreadable ones change nothing then
        LaunchLoad.reset(); LaunchLoad.begin(0L); LaunchLoad.windowMapped(1L)
        LaunchLoad.frame(false, 2L)
        t = 3L
        repeat(2000) { assertFalse(LaunchLoad.unreadable(t)); t += 16 }
        assertEquals(LaunchLoad.Phase.WAITING, LaunchLoad.phase)
    }

    @Test fun aStalledBootGoesButMovingProgressNeverTimesOut() {
        LaunchLoad.begin(0L)
        LaunchLoad.step("Installing Bionic components...", 0L)
        // a long first-time install whose progress keeps moving: no timeout
        var t = 0L
        repeat(100) { t += 30_000L; LaunchLoad.progress(it / 100f, t); LaunchLoad.tick(t) }
        assertEquals(LaunchLoad.Phase.BOOTING, LaunchLoad.phase)
        assertEquals(LaunchLoad.INSTALLING, LaunchLoad.line)
        // nothing for BOOT_TIMEOUT_MS: our screen goes (what is under it shows)
        LaunchLoad.tick(t + LaunchLoad.BOOT_TIMEOUT_MS)
        assertEquals(LaunchLoad.Phase.IDLE, LaunchLoad.phase)
        assertTrue(LaunchLoad.lastEnd.startsWith("no launch step"))
    }

    @Test fun aCrashOrAnErrorBeforeTheFirstFrameEndsAtOnce() {
        LaunchLoad.begin(0L)
        LaunchLoad.windowMapped(5_000L)
        LaunchLoad.end(6_000L, "launch error: Game terminated with error status: 1")
        assertEquals(LaunchLoad.Phase.IDLE, LaunchLoad.phase)
        assertFalse(LaunchLoad.covers()); assertFalse(LaunchLoad.watching); assertFalse(LaunchLoad.holdsPad())
        assertEquals("launch error: Game terminated with error status: 1", LaunchLoad.lastEnd)
        assertFalse("a late frame changes nothing", LaunchLoad.frame(true, 6_100L))
        LaunchLoad.windowMapped(6_200L)
        assertEquals(LaunchLoad.Phase.IDLE, LaunchLoad.phase)
    }

    @Test fun browserLoginWaitsWithoutATimeoutAndSaysWhereToSignIn() {
        LaunchLoad.begin(0L)
        LaunchLoad.step("Launching game...", 1_000L)
        LaunchLoad.signInWait(waiting = true, page = true, now = 2_000L)
        assertEquals(LaunchLoad.SIGN_IN_BELOW, LaunchLoad.line)
        LaunchLoad.tick(2_000L + LaunchLoad.BOOT_TIMEOUT_MS * 3)
        assertEquals("the player signs in for as long as it takes", LaunchLoad.Phase.BOOTING, LaunchLoad.phase)
        val back = 2_000L + LaunchLoad.BOOT_TIMEOUT_MS * 3
        LaunchLoad.signInWait(waiting = false, page = false, now = back)
        assertEquals(LaunchLoad.LAUNCHING, LaunchLoad.line)
        LaunchLoad.windowMapped(back + 1_000L)
        LaunchLoad.tick(back + 1_000L + LaunchLoad.PICTURE_TIMEOUT_MS - 1)
        assertEquals(LaunchLoad.Phase.WAITING, LaunchLoad.phase)
    }

    @Test fun aSecondPlayAfterQuittingStartsClean() {
        LaunchLoad.begin(0L); LaunchLoad.windowMapped(1L)
        repeat(LaunchLoad.PICTURE_FRAMES) { LaunchLoad.frame(true, 2L + it) }
        LaunchLoad.tick(10_000L)
        assertEquals(LaunchLoad.Phase.IDLE, LaunchLoad.phase)
        // the game quits; Play again
        LaunchLoad.begin(20_000L)
        assertEquals(LaunchLoad.Phase.BOOTING, LaunchLoad.phase)
        assertEquals(LaunchLoad.START, LaunchLoad.line)
        assertEquals(-1f, LaunchLoad.progress, 0f)
        assertTrue(LaunchLoad.watching)
        // the count of lit frames starts over (two left from nothing, one more is not enough)
        LaunchLoad.windowMapped(21_000L)
        LaunchLoad.frame(true, 21_001L); LaunchLoad.frame(true, 21_002L)
        assertEquals(LaunchLoad.Phase.WAITING, LaunchLoad.phase)
        // a begin while one shows goes on (the view model's own begin after Play's)
        LaunchLoad.begin(21_003L)
        assertEquals(LaunchLoad.Phase.WAITING, LaunchLoad.phase)
    }

    @Test fun theAbortChordStillWorks() {
        var aborts = 0
        val k = LaunchChord { aborts++ }
        assertFalse("an ordinary key is not the chord's", k.key(android.view.KeyEvent.KEYCODE_BUTTON_A, true))
        assertTrue(k.key(android.view.KeyEvent.KEYCODE_BUTTON_START, true))
        assertTrue(k.key(android.view.KeyEvent.KEYCODE_BUTTON_SELECT, true))
        assertTrue(k.key(android.view.KeyEvent.KEYCODE_BUTTON_L2, true))
        assertEquals(0, aborts)
        assertTrue(k.key(android.view.KeyEvent.KEYCODE_BUTTON_R2, true))
        assertEquals(1, aborts)
        k.key(android.view.KeyEvent.KEYCODE_BUTTON_R2, false); k.key(android.view.KeyEvent.KEYCODE_BUTTON_R2, true)
        assertEquals("once", 1, aborts)
        // the triggers as axes (the Thor's analog L2 / R2), Back as Select
        var b = 0
        val m = LaunchChord { b++ }
        m.key(android.view.KeyEvent.KEYCODE_BUTTON_START, true); m.key(android.view.KeyEvent.KEYCODE_BACK, true)
        assertFalse(m.triggers(0.9f, 0.1f))
        assertTrue(m.triggers(0.9f, 0.8f))
        assertEquals(1, b)
        // a let-go key breaks it
        var c = 0
        val n = LaunchChord { c++ }
        n.key(android.view.KeyEvent.KEYCODE_BUTTON_START, true); n.key(android.view.KeyEvent.KEYCODE_BUTTON_SELECT, true)
        n.key(android.view.KeyEvent.KEYCODE_BUTTON_START, false); n.triggers(1f, 1f)
        assertEquals(0, c)
    }

    @Test fun theStatusLineSaysEachStepInThePlayersWords() {
        // every text GameNative's splash and the pre-launch dialog show today
        assertEquals(LaunchLoad.START, LaunchLoad.terse("Booting..."))
        assertEquals(LaunchLoad.START, LaunchLoad.terse("Booting into World of Warcraft..."))
        assertEquals(LaunchLoad.START, LaunchLoad.terse("Loading..."))
        assertEquals(LaunchLoad.DOWNLOADING, LaunchLoad.terse("Downloading first-time files"))
        assertEquals(LaunchLoad.INSTALLING, LaunchLoad.terse("Installing Bionic components..."))
        assertEquals(LaunchLoad.INSTALLING, LaunchLoad.terse("Installing glibc components..."))
        assertEquals(LaunchLoad.PREPARING, LaunchLoad.terse("Preparing proton-10.0-arm64ec base..."))
        assertEquals(LaunchLoad.UPDATING, LaunchLoad.terse("Migrating Wine prefix..."))
        assertEquals(LaunchLoad.LAUNCHING, LaunchLoad.terse("Launching game..."))
        // anything else keeps the line (the launcher's own words never show)
        assertNull(LaunchLoad.terse("Some new GameNative step"))
        assertNull(LaunchLoad.terse(""))
        LaunchLoad.begin(0L)
        LaunchLoad.step("Some new GameNative step", 1L)
        assertEquals(LaunchLoad.START, LaunchLoad.line)
        // terse: no line is long (the box's one line)
        val lines = listOf(LaunchLoad.START, LaunchLoad.DOWNLOADING, LaunchLoad.INSTALLING, LaunchLoad.PREPARING, LaunchLoad.UPDATING,
            LaunchLoad.LAUNCHING, LaunchLoad.OPENING, LaunchLoad.SIGN_IN_BELOW, LaunchLoad.ABORT_HINT)
        for (l in lines) assertTrue("$l is terse", l.length <= 36 && !l.endsWith("."))
        // and each in Spanish and French
        for (lang in listOf("es", "fr")) for (l in lines) assertNotEquals("$l in $lang", l, Tr.t(l, lang))
    }

    @Test fun theDotsMoveAndAreNotPartOfTheWords() {
        val a = LaunchLoad.statusLine(LaunchLoad.LAUNCHING, 0.1f)
        val b = LaunchLoad.statusLine(LaunchLoad.LAUNCHING, 0.6f)
        val c = LaunchLoad.statusLine(LaunchLoad.LAUNCHING, 1.1f)
        assertEquals(setOf("Launching the game.", "Launching the game..", "Launching the game..."), setOf(a, b, c))
    }
}
