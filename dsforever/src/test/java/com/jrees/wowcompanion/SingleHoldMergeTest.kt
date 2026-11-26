package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE 1533 MERGE, HOLD TO CAST x SINGLE SCREEN MODE (the user, 2026-11-25: "I also want this to cast right when the hold bar is filled,
 * not when its released"; "a single screen mode where the game shifts to the top screen when a button is pushed"): the rules that only
 * exist where the two meet.
 *  - THE FIRE IS THE LAUNCHER'S, WHATEVER WINDOW IS UP: HoldRelease masks the kept button's bit in WinHandler's controller writes at the
 *    ring's fill (A / X / Y / the D-pad, never B or L2 / R2). Single screen mode's top window, its docks and its strip are never focusable
 *    (the keys stay with the launcher's activity), and nothing of single screen mode feeds or stops the fire.
 *  - THE GATES ARE TWO SCREENS' GATES: HoldCast.gates closes for our pop-ups and wheels (PopupHold: the sign-in prompts, the report's
 *    notice, the context wheel's Yes / No) and stays open under the rooms and our NPC windows (the add-on keeps no press under a
 *    capture), exactly as on two screens - in single screen mode too, where the rooms are the centred panel and the NPC windows ours
 *    over the game; the play docks (the map, the party list, the chat) and the hint plate never close it.
 *  - ONE READER EACH: the band's controller reader (CastView, kept running by readAway in single PLAY) is the one source of the
 *    picture's presses (HoldCast.watch), WinHandler the one source of the fire's edges (HoldCast.padIn) - a held face button is never
 *    handled twice.
 */
class SingleHoldMergeTest {
    private val companion = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion"), File("companion/app/src/main/java/com/jrees/wowcompanion"))
        .first { it.isDirectory }
    private val launcher = listOf(File("../app/src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private val fork = listOf(File("src/main/java/com/jrees/wowcompanion/fork"), File("dsforever/src/main/java/com/jrees/wowcompanion/fork"))
        .first { it.isDirectory }
    private fun kt(n: String) = File(companion, n).readText()

    private val X = HoldRelease.X
    private fun xDown(e: HoldRelease) = HoldRelease.isDown(X, HoldRelease.outButtons(1 shl 2, e.forcedMask), BooleanArray(4))

    private val gates0 = HoldCast.gates
    private val gameUp0 = LauncherHooks.gameUp
    private val clock0 = HoldCast.clock
    private val bandClock0 = HoldCast.bandClock
    private val log0 = HoldCast.log
    @After fun clean() {
        HoldCast.gates = gates0
        HoldCast.clock = clock0; HoldCast.bandClock = bandClock0; HoldCast.log = log0
        HoldCast.padReset(true); HoldCast.release.cn = null; HoldCast.release.ft = null; HoldCast.mapWhy = ""
        HoldCast.watch.reset(); HoldCast.watch.map = emptySet(); HoldCast.takeover.clear()
        LauncherHooks.gameUp = gameUp0
        SetupBridge.signIn(SetupFlow.SignIn.NONE); SetupBridge.noticeAsking = false; SetupBridge.loginFormOpen = false
        wheelDown()
        SingleScreen.set(false, null); SingleUi.reset(); SingleScreen.keysPaused = false
    }

    /** the context wheel's pop-up gone long ago (its grace over at any clock) */
    private fun wheelDown() { PopupHold.wheel.up(); PopupHold.wheel.down(-PopupHold.GRACE_MS - 1_000L) }

    /** single screen mode as its host lays it while playing: the mode on, the play docks built, the add-on's `ss` 1 */
    private fun single(on: Boolean) {
        SingleScreen.set(on, if (on) SingleScreen.Why.USER else null)
        SingleUi.docks = on; SingleUi.addonSingle = on
    }

    /** every two-screen state the real gates read, each set and let go again (the context wheel's Yes / No: [theWheelsPopUp]) */
    private val states: List<Pair<String, Pair<() -> Unit, Boolean>>> = listOf(
        "nothing of ours over the game (two screens: Home; single: PLAY, the docks and the hint plate)" to ({ } to true),
        "the report's notice asking (the game up)" to ({ LauncherHooks.gameUp = { true }; SetupBridge.noticeAsking = true } to false),
        "the sign-in prompt asking (the game up)" to ({ LauncherHooks.gameUp = { true }; SetupBridge.signIn(SetupFlow.SignIn.AUTH) } to false),
        "the holdmap's party" to ({ HoldCast.mapWhy = "party" } to false),
        "a loading screen" to ({ HoldCast.mapWhy = "loading" } to false),
    )

    private fun reset() {
        LauncherHooks.gameUp = gameUp0
        SetupBridge.signIn(SetupFlow.SignIn.NONE); SetupBridge.noticeAsking = false
        wheelDown()
        HoldCast.mapWhy = "on"
    }

    @Test fun theGatesAreTwoScreensGatesInSingleScreenMode() {
        HoldCast.gates = gates0
        for ((name, s) in states) {
            val (set, open) = s
            for (on in listOf(false, true)) {
                reset(); single(on)
                set()
                assertEquals("${if (on) "single screen mode" else "two screens"}: $name", open, HoldCast.gates())
            }
        }
        reset()
        // single screen mode's own state never reaches them: its keys left alone for an activity on the other screen, its lease, its look
        single(true); SingleScreen.keysPaused = true
        assertTrue("the mode's own flags open nothing and close nothing", HoldCast.gates())
    }

    /** the context wheel's Yes / No (PopupHold.wheel, read on the device's uptime clock: here by its own clock) holds the pad over the
     *  game in either mode, and the gates read it through SetupBridge.holdsGamePad */
    @Test fun theWheelsPopUp() {
        for (on in listOf(false, true)) {
            reset(); single(on)
            PopupHold.wheel.up()
            assertTrue("${if (on) "single screen mode" else "two screens"}: the wheel's pop-up holds the pad", PopupHold.wheel.holds(5_000L))
            PopupHold.wheel.down(5_000L)
            assertTrue("... its grace", PopupHold.wheel.holds(5_000L + PopupHold.GRACE_MS - 1))
            assertFalse("... then the game's again", PopupHold.wheel.holds(5_000L + PopupHold.GRACE_MS))
        }
        val bridge = kt("SetupBridge.kt").substringAfter("fun holdsGamePad(): Boolean =").substringBefore("\n")
        assertTrue("the gates' pop-ups include the wheel's", bridge.contains("|| wheelHolds()"))
        assertTrue(kt("HoldCast.kt").substringAfter("var gates: () -> Boolean = {").substringBefore("\n    }").contains("SetupBridge.holdsGamePad()"))
    }

    @Test fun theFireAtTheFillWithTheDocksUp() {
        HoldCast.gates = gates0
        HoldCast.clock = { 1678L }; HoldCast.bandClock = { 1678L }
        HoldCast.log = { }
        reset(); single(true)
        val e = HoldCast.release
        e.reset(); e.cn = 3; e.ft = 17
        e.edge(X, true, 1000)
        assertTrue("the keep word for X (PAD3)", e.keep("PAD3", 5, 0, 3, 1100))
        assertFalse("not before its fire point", e.due(1600)); assertTrue(xDown(e))
        assertTrue("single screen mode, the docks up: X let go for the game at F", e.due(1678)); assertFalse(xDown(e))
        assertEquals(HoldRelease.FORCED, e.state(X))
        e.edge(X, false, 1900)
        assertEquals("the physical up after the fire: nothing new for the game (it read up already)", 0, e.forcedMask)
        assertEquals(HoldRelease.IDLE, e.state(X))
        // a pop-up of ours over the game at the fire (the report's notice): the release decides, as on two screens
        e.reset(); e.cn = 3; e.ft = 17
        LauncherHooks.gameUp = { true }; SetupBridge.noticeAsking = true
        e.edge(X, true, 3000); assertTrue(e.keep("PAD3", 5, 0, 3, 3100))
        assertFalse("a pop-up of ours holds the pad: no early release", e.due(3678)); assertTrue(xDown(e))
    }

    @Test fun nothingOfSingleScreenModeTouchesTheFire() {
        // the single screen mode files (companion and fork): no gate, no hook, no reader of the hold's
        val files = (companion.listFiles { f -> f.name.startsWith("Single") && f.name.endsWith(".kt") }!!.toList() +
            listOf(File(fork, "TopWindow.kt"), File(fork, "SingleSub.kt"))).filter { it.isFile }
        assertTrue("the mode's files found", files.size >= 8)
        for (f in files) {
            val t = f.readText()
            for (w in listOf("HoldCast", "HoldRelease", "PopupHold", "holdsGamePad", "SetupBridge.room", "forcedMask"))
                assertFalse("${f.name} reaches $w", t.contains(w))
        }
        // its windows never take the keys from the launcher's activity (the fire's edges are WinHandler's): never focusable but to type
        val top = File(fork, "TopWindow.kt").readText()
        assertTrue("the top window is not focusable", top.contains("FLAG_NOT_FOCUSABLE"))
        val gates = kt("HoldCast.kt").substringAfter("var gates: () -> Boolean = {").substringBefore("\n    }")
        assertFalse("no single screen mode term among the gates", gates.contains("Single", ignoreCase = true) || gates.contains("dock", ignoreCase = true))
    }

    @Test fun oneReaderForThePictureOneForTheFire() {
        val all = companion.listFiles { f -> f.name.endsWith(".kt") }!!.associate { it.name to it.readText() }
        val press = all.filter { (_, t) -> t.contains("HoldCast.watch.press(") }.keys
        assertEquals("the picture's presses come from the band's reader alone", setOf("CastView.kt"), press)
        val cast = all.getValue("CastView.kt")
        assertEquals("one call of the band's hold watch (its controller reader)", 1, Regex("\\bholdWatch\\(mask, down, wheel\\)").findAll(cast).count())
        assertEquals("one press and one release onto the watch", 1, Regex("HoldCast\\.watch\\.press\\(").findAll(cast).count())
        assertTrue("readAway keeps that one reader running (no second one)", cast.contains("((winVisible && isShown) || readAway)"))
        assertTrue(all.none { (_, t) -> t.contains("HoldCast.padIn(") })
        val launcherCalls = launcher.walkTopDown().filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
            .filter { it.readText().contains("HoldCast.padIn(") }.map { it.name }.toList()
        assertEquals("the fire's edges come from WinHandler's writes alone", listOf("WinHandler.java"), launcherCalls)
        val forkCalls = fork.parentFile!!.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && it.readText().contains("HoldCast.padIn(") }.toList()
        assertTrue("none in the fork", forkCalls.isEmpty())
        // the docks' only use of the band: hold to talk's pop-up drawn from it (no pad reader of their own)
        val docks = all.getValue("SingleDocks.kt")
        assertFalse(docks.contains("onPadButtons") || docks.contains("PadFeed") || docks.contains("DirectPad"))
    }

    @Test fun theRoomsTabsReaderIsTheSameInThePanel() {
        val host = kt("CompanionHost.kt")
        val live = host.substringAfter("private fun roomTabsLive(): Boolean {").substringBefore("\n    }\n")
        assertFalse("no single screen mode term in the rooms' L / R rule: the panel's rooms step their tabs as on two screens",
            live.contains("single", ignoreCase = true))
        // over a room in the panel, what reads the pad itself covers it - the pause wheel, the quick wheels, our NPC windows, dialogs
        for (w in listOf("interactView.visibility", "dialogView.covers()", "radialView.isOpen", "pauseView.isOpen", "tradOpen()",
            "spellsOverlay.visibility", "barSelect.open"))
            assertTrue("covered by $w", live.contains(w))
    }
}
