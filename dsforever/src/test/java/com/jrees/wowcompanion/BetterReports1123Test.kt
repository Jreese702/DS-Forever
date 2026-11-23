package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Better reports (11-23, logging only; nothing a player sees changes), off-device:
 * - issues 580 / 539: the last 30 presses that reached our buttons (Spells' `facecast`, dropped before), a press that did nothing
 *   as one line, every refused padack (only the toolbar's was logged), the fight buttons' `faceswap` in short;
 * - issues 558 / 476: whether the game made the fight-aura containers (Trail ab1's `aurabox` probe, Hub's abox word);
 * - issues 582 / 560: one line per network stall in the world with the Wi-Fi and the game's latency;
 * - #153: the garbage collector's thread right after the main one in a freeze's trace.
 */
class BetterReports1123Test {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val host = File(src, "CompanionHost.kt").readText()

    @After fun reset() = PressLog.reset()

    // ---------------------------------------------------------------- 580 / 539: the presses

    private fun cast(pos: Any, kind: String, at: String? = null, down: Boolean = true, fight: Boolean = false) =
        JSONObject().put("type", "facecast").put("v", "m185").put("pos", pos).put("kind", kind).apply {
            if (at != null) put("at", at); if (down) put("down", true); if (fight) put("fight", true)
        }

    @Test fun theLastThirtyKeyDownsAreKeptWithWhereTheyWent() {
        assertNull(PressLog.press(cast(3, "slotfight", "MultiBarBottomLeftButton5", fight = true), 1_000))
        assertNull(PressLog.press(cast(3, "slotfight", "MultiBarBottomLeftButton5", down = false), 1_100))   // the key-up: not kept
        val l = PressLog.lines()
        assertTrue(l[0], l[0].startsWith("Presses that reached our buttons (the last 1 of at most 30"))
        assertTrue(l[1], l[1].endsWith(" pos 3 slotfight at MultiBarBottomLeftButton5 fight"))
        for (i in 0 until 40) PressLog.press(cast(i % 8 + 1, "fight"), 2_000L + i)
        val after = PressLog.lines()
        assertEquals(30, after.count { it.contains(" fight") && it.contains("pos ") })
        assertFalse(after.any { it.contains("MultiBarBottomLeftButton5") })                           // the oldest went
        // only plain codes: a name-like value is cut to letters, digits and _-.: (never more than 40)
        assertEquals("pos 7 back at WowCompanionFaceB", PressLog.pressWords(cast(7, "back", "WowCompanionFaceB")))
        assertEquals("pos 1 none at abscript", PressLog.pressWords(cast(1, "none", "a b<script>")))
    }

    @Test fun aPressThatDidNothingIsOneLineAtMostEveryTenSecondsPerPosition() {
        assertEquals("press did nothing: pos 2 none fight", PressLog.press(cast(2, "none", fight = true), 10_000))
        assertNull(PressLog.press(cast(2, "none", fight = true), 15_000))
        assertTrue(PressLog.press(cast(4, "none"), 15_000)!!.startsWith("press did nothing: pos 4"))
        assertTrue(PressLog.press(cast(2, "none"), 20_000) != null)
        assertNull(PressLog.press(cast(2, "slotfight", "ActionButton1"), 20_100))
    }

    @Test fun everyRefusalIsALineTheSameAgainIsOnlyCounted() {
        assertEquals("the game's add-on refused combat (in a fight) slot 3 idx 2", PressLog.refused("combat", "in a fight", 3, 2, null, 1_000))
        assertNull(PressLog.refused("combat", "in a fight", 3, 2, null, 5_000))
        assertNull(PressLog.refused("combat", "in a fight", 4, 2, null, 6_000))
        assertEquals("the game's add-on refused cface (no reason)", PressLog.refused("cface", null, null, null, null, 6_000))
        assertEquals("the game's add-on refused combat (in a fight) slot 1; 2 more like it since", PressLog.refused("combat", "in a fight", 1, null, null, 40_000))
        val l = PressLog.lines()
        val at = l.indexOfFirst { it.startsWith("Changes the game's add-on refused") }
        assertEquals(3, l.drop(at + 1).size)
        // the host logs every other refusal (the toolbar's own line stays) and hands the presses / fight buttons in
        assertTrue(host.contains("else if (!it.ok) PressLog.refused(it.op, it.why, it.storage, it.idx, it.kind, System.currentTimeMillis())"))
        assertTrue(host.contains("\"facecast\" -> if (current) PressLog.press(msg, System.currentTimeMillis())"))
        assertTrue(host.contains("\"faceswap\" -> if (current) PressLog.swap(msg)"))
        assertTrue(File(src, "StartupDiag.kt").readText().contains("sec(\"Controller (the last presses and refusals)\") { PressLog.lines() }"))
    }

    @Test fun theFightButtonsWordIsOneLineWhenItChanges() {
        val k = JSONObject().put("1", JSONObject().put("key", "PAD1").put("ours", true))
            .put("2", JSONObject().put("key", "PAD2").put("ours", true).put("given", true)).put("3", JSONObject().put("key", "PAD3"))
        val m = JSONObject().put("type", "faceswap").put("on", true).put("why", "fight-start").put("k", k).put("fight", true).put("bound", 4)
        assertEquals("fight buttons ours: on (fight-start), 3 keys, 2 ours, 1 given back, in a fight, bound 4", PressLog.swap(m))
        assertNull(PressLog.swap(m))
        val end = JSONObject(m.toString()).put("on", false).put("why", "fight-end")
        end.remove("fight")
        assertEquals("fight buttons ours: off (fight-end), 3 keys, 2 ours, 1 given back, bound 4", PressLog.swap(end))
    }

    // ---------------------------------------------------------------- 558 / 476: the aura containers

    @Test fun theAuraContainersAreLoggedWhenMadeAndWhenTheirWordChanges() {
        val made = JSONObject().put("type", "cap").put("probe", "aurabox").put("v", "ab1").put("key", "hud").put("made", true).put("groups", 2)
            .put("steps", JSONObject()).put("fight", false)
        assertEquals("aura container hud (ab1): made true, groups 2, steps ok, out of a fight", AuraProbe.boxLine(made))
        val refused = JSONObject().put("probe", "aurabox").put("v", "ab1").put("key", "target").put("made", false).put("groups", 0)
            .put("steps", JSONObject().put("create", "no Blizzard_AuraContainer")).put("fight", true)
        assertEquals("aura container target (ab1): made false, groups 0, steps create: no Blizzard_AuraContainer, in a fight", AuraProbe.boxLine(refused))
        val word = JSONObject().put("v", "ab1").put("b", JSONObject().put("tot", "none").put("hud", "ok").put("target", "ok")).put("n", 0)
        assertEquals("aura containers (ab1): hud ok, target ok, tot none; buttons made no", AuraProbe.wordLine(word))
        assertEquals("aura containers (ab1): hud ok, target ok, tot none; buttons made yes", AuraProbe.wordLine(JSONObject(word.toString()).put("n", 7)))
        assertNull(AuraProbe.wordLine(null))
        assertNull(AuraProbe.wordLine(JSONObject().put("v", "ab1")))
        assertTrue(host.contains("if (msg.optString(\"probe\") == \"aurabox\") AuraProbe.boxLine(msg)"))
        assertTrue(host.contains("AuraProbe.wordLine(h.optJSONObject(\"abox\"))?.let { l -> if (l != aboxLast)"))
    }

    // ---------------------------------------------------------------- 582 / 560: the network stalls

    @Test fun aQuietRunOfThreeSecondsInTheWorldIsOneStall() {
        var snaps = 0
        val t = NetStall.Tracker { snaps++; "wifi -67 dBm, 433 Mb/s, 5 GHz" to "100 wifi" }
        assertNull(t.feed(5_000, 300, true))
        assertNull(t.feed(0, 120, true)); assertNull(t.feed(50, 0, true))
        assertNull(t.feed(3_000, 100, true))                                         // two quiet seconds: no stall
        assertEquals(0, snaps)
        for (i in 0 until 6) assertNull(t.feed(if (i == 0) 120 else 0, 400, true))
        assertEquals(1, snaps)                                                       // the link read once, at the 3rd second
        val s = t.feed(4_000, 100, true)!!
        assertEquals(6, s.seconds); assertEquals(120L, s.rxBytes); assertEquals(2400L, s.txBytes)
        assertEquals("wifi -67 dBm, 433 Mb/s, 5 GHz", s.wifiAt)
        // out of the world (character select, a logout) no quiet run starts; one under way runs to its end
        assertNull(t.feed(0, 0, false)); assertEquals(0, t.quietNow)
        for (i in 0 until 3) t.feed(0, 0, true)
        assertNull(t.feed(0, 0, false))
        assertEquals(4, t.end()!!.seconds)
        assertNull(t.end())
    }

    @Test fun theStallLineSaysTheLinkTheNetworkAndTheGamesLatency() {
        val s = NetStall.Stall(6, 120, 2400, "wifi -67 dBm, 433 Mb/s, 5 GHz", "100 wifi")
        assertEquals("net stall: nothing received for 6 s (rx 120 B, tx 2.3 KB meanwhile); wifi -67 dBm, 433 Mb/s, 5 GHz; network same; " +
            "game latency 45 / 60 ms (12 s old); stall 3 this run, the longest 9 s",
            NetStall.line(s, "100 wifi", NetStall.latencyWords(Triple(45, 60, 1_000L), 13_000L), 3, 9))
        assertTrue(NetStall.line(s, "101 cellular", "game latency ?", 1, 6).contains("network changed (101 cellular)"))
        assertTrue(NetStall.line(NetStall.Stall(3, 0, 0, "", null), null, "game latency ?", 1, 3).contains("; wifi ?; network ?;"))
        assertEquals("wifi -67 dBm, 433 Mb/s, 5 GHz", NetStall.linkWords("wifi", -67, 433, 5180))
        assertEquals("wifi -40 dBm, 2.4 GHz", NetStall.linkWords("wifi", -40, -1, 2437))
        assertEquals("wifi signal ?", NetStall.linkWords("wifi", -127 - 1, null, null))
        assertEquals("wifi signal ?", NetStall.linkWords("wifi", -127, null, null))                  // WifiInfo.INVALID_RSSI: no reading
        assertEquals("wifi -126 dBm", NetStall.linkWords("wifi", -126, null, null))
        assertEquals("cellular", NetStall.linkWords("cellular", -90, 10, 1800))
        assertEquals("game latency ? / 60 ms (0 s old)", NetStall.latencyWords(Triple(-1, 60, 5_000L), 1_000L))
        // never the network's name or address
        val ns = File(src, "NetStall.kt").readText()
        assertFalse(ns.contains(".ssid") || ns.contains(".bssid") || ns.contains("ipAddress") || ns.contains("macAddress"))
        // no new permission: only what the manifest declares (ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE)
        val manifest = listOf("../app/src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map { File(it) }.first { it.isFile }.readText()
        assertTrue(manifest.contains("android.permission.ACCESS_NETWORK_STATE") && manifest.contains("android.permission.ACCESS_WIFI_STATE"))
        assertFalse(manifest.contains("ACCESS_FINE_LOCATION") || manifest.contains("NEARBY_WIFI_DEVICES"))
        val login = File(src, "NativeLogin.kt").readText()
        assertTrue(login.contains("NetStall.second(appCtx, rxBytes, txBytes, reachedWorld && !outOfWorld && !GameLife.awayNow)"))
        assertTrue(login.contains("NetStall.runEnds()"))
        // review 11-23: the count is per client run, and the tracker and the link read run on NetStall's own thread (never main)
        assertTrue(login.contains("NetStall.runStarts()"))
        assertTrue(ns.contains("onWorker { tracker.feed(rx, tx, inWorld)?.let { said(it) } }"))
        assertTrue(ns.contains("fun runEnds() = onWorker {") && ns.contains("fun runStarts() = onWorker { tracker.end(); count = 0; longest = 0 }"))
        assertTrue(host.contains("NetStall.latency(n.optInt(\"h\", -1), n.optInt(\"w\", -1), System.currentTimeMillis())"))
    }

    // ---------------------------------------------------------------- #153: the garbage collector's thread

    @Test fun aFreezesTraceShowsTheCollectorRightAfterTheMainThread() {
        val rec = "DS Forever: how the last run ended\n---------- Trace ----------\n" +
            "\"Binder:1_1\" prio=5 tid=9 Native\n  at a.b(C.kt:1)\n\n" +
            "\"HeapTaskDaemon\" daemon prio=5 tid=7 WaitingForTaskProcessor\n" +
            "  native: #00 pc 0001 /apex/com.android.art/lib64/bionic/libc.so (syscall+28)\n" +
            "  native: #01 pc 0002 /apex/com.android.art/lib64/libart.so (art::ConditionVariable::WaitHoldingLocks+1)\n" +
            "  native: #02 pc 0003 /apex/com.android.art/lib64/libart.so (art::gc::TaskProcessor::GetTask+1)\n" +
            "  native: #03 pc 0004 /apex/com.android.art/lib64/libart.so (art::gc::TaskProcessor::RunAllTasks+1)\n" +
            "  at dalvik.system.VMRuntime.runHeapTasks(Native method)\n" +
            "  at java.lang.Daemons\$HeapTaskDaemon.runInternal(Daemons.java:609)\n\n" +
            "\"main\" prio=5 tid=1 Native\n  native: #00 pc 0001 /apex/com.android.art/lib64/libart.so (art::ConditionVariable::WaitHoldingLocks+1)\n" +
            "  at android.view.Choreographer.doFrame(Choreographer.java:1)\n"
        val t = LogSieve.traceFirst(rec)
        val main = t.indexOf("\"main\" prio=5")
        val gc = t.indexOf("\"HeapTaskDaemon\"")
        assertTrue(t, main in 0 until gc)
        assertTrue(t, gc < t.indexOf("[the rest of the trace]"))
        assertEquals(1, Regex("\"HeapTaskDaemon\"").findAll(t).count())                  // moved, not copied
        // review 11-23: in full - its native frames say what the collection waits on (in short only 2 were kept, and lost)
        assertTrue(t, t.indexOf("TaskProcessor::RunAllTasks") in gc until t.indexOf("[the rest of the trace]"))
        assertTrue(t, t.contains("Daemons\$HeapTaskDaemon.runInternal"))
        assertTrue(t.indexOf("\"Binder:1_1\"") > t.indexOf("[the rest of the trace]"))
    }
}
