package com.jrees.wowcompanion.stream

import com.jrees.wowcompanion.Wcb1Assembler
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BridgeLog (S2): the PC's WCB1 lines over the bridge reassemble exactly as the local log's do. Off-device, a scripted bridge. */
class BridgeLogTest {
    /** a scripted connection: the frames the bridge sends, then a close with [closeCode] */
    private class Script(private val frames: List<String>, override val closeCode: Int = 1006) : BridgeConn {
        val sent = ArrayList<String>()
        private var i = 0
        override fun send(text: String) { sent.add(text) }
        override fun receive(): String? = frames.getOrNull(i++)
        override val peerFingerprint = "ab".repeat(32)
        override fun close(code: Int) {}
    }

    private fun lines(vararg l: String) = JSONObject().put("t", "lines").put("l", JSONArray(l.toList())).toString()
    // Desktop v1's welcome (BridgeServer.InfoMsg): host info, the device, the backlog's size
    private val welcome = JSONObject().put("t", "welcome").put("proto", 1).put("host_id", "d1").put("pc_name", "Den PC")
        .put("wow", JSONObject().put("found", true).put("running", true)).put("device_id", "c".repeat(32)).put("backlog", 0).toString()
    private fun doc(seq: Int, gt: Long, text: String) = JSONObject().put("t", "doc").put("seq", seq).put("gt", gt).put("doc", text).toString()
    private fun wcb(seq: Int, part: String, payload: String, time: String = "10:15:30.250") = "10/23 $time  [N][Lua] WCB1|$seq|$part|$payload"

    private fun bridge(conns: List<BridgeConn>, docs: MutableList<JSONObject>, status: MutableList<String>, auth: MutableList<String> = ArrayList(),
                       wall: () -> Long = { com.jrees.wowcompanion.GameInput.wallYearMs() }): BridgeLog {
        val next = conns.iterator()
        var log: BridgeLog? = null
        log = BridgeLog("192.168.1.20", 48210, false, "", "t".repeat(43), "c".repeat(32),
            dialer = { _, _, _, _ -> if (next.hasNext()) next.next() else throw java.io.IOException("refused") },
            onMessage = { docs.add(it) }, onStatus = { status.add(it) }, onAuthLost = { auth.add(it) },
            sleeper = { if (!next.hasNext()) log!!.stop() }, wall = wall)
        return log
    }

    @Test fun aDocumentSplitAcrossFramesIsJoinedWithTheGamesClock() {
        val docs = ArrayList<JSONObject>(); val status = ArrayList<String>()
        val c = Script(listOf(welcome,
            lines("10/23 10:15:30.000  [N][Lua] engine chatter", wcb(41, "1/3", "{\"t\":\"quests\",")),
            lines(wcb(42, "2/3", "\"n\":3,\r"), wcb(43, "3/3", "\"ok\":true}")),
            lines(wcb(44, "1/1", "{\"t\":\"pos\",\"x\":1}"))))
        bridge(listOf(c), docs, status).runHere()
        assertEquals(2, docs.size)
        assertEquals("quests", docs[0].getString("t")); assertEquals(3, docs[0].getInt("n")); assertTrue(docs[0].getBoolean("ok"))
        assertEquals(Wcb1Assembler.gameTime(wcb(43, "3/3", "x"), wcb(43, "3/3", "x").indexOf("WCB1|")), docs[0].getLong("_gt"))
        assertEquals("pos", docs[1].getString("t"))
        // the token went first, then "focus WoW" after the welcome
        assertEquals("hello", JSONObject(c.sent[0]).getString("t"))
        assertEquals("t".repeat(43), JSONObject(c.sent[0]).getString("token"))
        assertEquals("c".repeat(32), JSONObject(c.sent[0]).getString("device_id"))
        assertEquals("focus", JSONObject(c.sent[1]).getString("t"))
        assertTrue(status.any { it.contains("Den PC") })
        assertFalse(c.sent.any { JSONObject(it).optString("t") == "stream_mode" })
    }

    /** Desktop 0.2.31 makes a virtual screen for a headless PC at the Thor's stream mode: sent after the welcome's focus */
    @Test fun theStreamModeFollowsTheWelcome() {
        val c = Script(listOf(welcome))
        var log: BridgeLog? = null
        log = BridgeLog("192.168.1.20", 48210, false, "", "t".repeat(43), "c".repeat(32),
            dialer = { _, _, _, _ -> c }, onMessage = {}, onStatus = {}, streamMode = { Triple(1920, 1080, 120) },
            sleeper = { log!!.stop() })
        log.runHere()
        val i = c.sent.indexOfFirst { JSONObject(it).optString("t") == "stream_mode" }
        assertTrue(i > c.sent.indexOfFirst { JSONObject(it).optString("t") == "focus" })
        val m = JSONObject(c.sent[i])
        assertEquals(1920, m.getInt("w")); assertEquals(1080, m.getInt("h")); assertEquals(120, m.getInt("fps"))
    }

    @Test fun aResetDropsTheHalfDocumentAndTheSequence() {
        val docs = ArrayList<JSONObject>(); val status = ArrayList<String>()
        val c = Script(listOf(welcome,
            lines(wcb(10, "1/2", "{\"t\":\"old\",")),
            JSONObject().put("t", "reset").put("why", "truncated").toString(),
            lines(wcb(1, "2/2", "\"lost\":1}"), wcb(2, "1/1", "{\"t\":\"new\"}"))))
        val b = bridge(listOf(c), docs, status)
        b.runHere()
        assertEquals(listOf("new"), docs.map { it.getString("t") })
        assertTrue(status.contains("client restarted, resyncing"))
        // no gap reported across the reset (seq 10 -> 1 is a new log, not a refused line)
        assertEquals(0, b.gapCount())
    }

    @Test fun aGapInTheSequenceIsSurfaced() {
        val docs = ArrayList<JSONObject>(); val status = ArrayList<String>()
        val b = bridge(listOf(Script(listOf(welcome, lines(wcb(5, "1/1", "{\"t\":\"a\"}"), wcb(7, "1/1", "{\"t\":\"b\"}"))))), docs, status)
        b.runHere()
        assertEquals(2, docs.size)
        assertEquals(1, b.gapCount())
        assertTrue(status.any { it.startsWith("gap at seq 7") })
    }

    @Test fun aDroppedConnectionReconnectsAndTheBacklogDoesNotGlueOntoOldParts() {
        val docs = ArrayList<JSONObject>(); val status = ArrayList<String>()
        val first = Script(listOf(welcome, lines(wcb(20, "1/2", "{\"t\":\"cut\","))))           // drops mid-document
        val second = Script(listOf(welcome, lines(wcb(20, "1/2", "{\"t\":\"whole\","), wcb(21, "2/2", "\"y\":2}"))))
        val b = bridge(listOf(first, second), docs, status)
        b.runHere()
        assertEquals(2, b.sessions)
        assertEquals(listOf("whole"), docs.map { it.getString("t") })
        assertTrue(status.contains("PC: connection lost, reconnecting"))
    }

    @Test fun aBadTokenStopsForGoodAndAsksToPairAgain() {
        val docs = ArrayList<JSONObject>(); val status = ArrayList<String>(); val auth = ArrayList<String>()
        val c = Script(listOf(JSONObject().put("t", "error").put("error", "unauthorized").put("detail", "pair again").toString()), BridgeProtocol.CLOSE_UNAUTHORIZED)
        val never = Script(listOf(welcome))
        val b = bridge(listOf(c, never), docs, status, auth)
        b.runHere()
        assertEquals(listOf("unauthorized"), auth)
        assertEquals(1, b.sessions)                                  // no second try
    }

    @Test fun theUnauthorizedCloseCodeAndUnpairedAlsoAskToPairAgain() {
        val auth = ArrayList<String>()
        bridge(listOf(Script(listOf(welcome), BridgeProtocol.CLOSE_UNAUTHORIZED)), ArrayList(), ArrayList(), auth).runHere()
        bridge(listOf(Script(listOf(welcome, JSONObject().put("t", "unpaired").toString()))), ArrayList(), ArrayList(), auth).runHere()
        assertEquals(listOf("unauthorized", "revoked"), auth)
    }

    @Test fun desktopsJoinedDocumentsArriveWithTheGamesClock() {
        val docs = ArrayList<JSONObject>(); val status = ArrayList<String>()
        val b = bridge(listOf(Script(listOf(welcome,
            doc(41, 25_000_123L, JSONObject().put("t", "quests").put("n", 3).toString()),
            JSONObject().put("t", "gap").put("seq", 44).put("expected", 42).toString(),
            doc(44, -1L, JSONObject().put("t", "pos").toString()),
            doc(45, 5L, "{broken"),
            JSONObject().put("t", "backlog_end").put("count", 3).toString(),
            JSONObject().put("t", "log").put("event", "restart").put("path", "C:/x").toString(),
            JSONObject().put("t", "status").put("text", "waiting for WoW's log").toString()))), docs, status)
        b.runHere()
        assertEquals(listOf("quests", "pos"), docs.map { it.getString("t") })
        assertEquals(25_000_123L, docs[0].getLong("_gt"))
        assertFalse(docs[1].has("_gt"))
        assertEquals(1, b.gapCount())
        assertTrue(status.contains("gap at seq 44 (expected 42)"))
        assertTrue(status.any { it.startsWith("bad json") })
        assertTrue(status.contains("client restarted, resyncing"))
        assertTrue(status.contains("PC: waiting for WoW's log"))
    }

    @Test fun anUnreachableBridgeIsSaidAndRetried() {
        val status = ArrayList<String>()
        val b = bridge(emptyList(), ArrayList(), status)
        b.runHere()
        assertTrue(status.single().startsWith("PC: bridge unreachable"))
    }

    @Test fun statusAndUnknownMessages() {
        val status = ArrayList<String>()
        val st = JSONObject().put("t", "focus_result").put("ok", false).put("why", "WoW isn't the front window").toString()
        bridge(listOf(Script(listOf(welcome, st, "{\"t\":\"future_thing\"}", "not json"))), ArrayList(), status).runHere()
        assertTrue(status.contains("PC: WoW isn't the front window"))
    }

    // ---- issue #414: a PC in another time zone ("Companion stuck on 'Waiting for the game'"): the stamps go onto the Thor's clock
    private val hour = 3_600_000L
    /** the Thor's clock (day-of-year scale): 11-15 18:44:41 */
    private val thorNow = com.jrees.wowcompanion.InputGate.yearMs(11, 15, 18, 44, 41, 0)
    private val backlogEnd = JSONObject().put("t", "backlog_end").put("count", 1).toString()

    @Test fun desktopsClockInTheWelcomeMovesEveryStampOntoTheThors() {
        val docs = ArrayList<JSONObject>()
        // the PC on UTC+3, the Thor on UTC+1: the PC's clock reads 2 h ahead
        val w = JSONObject(welcome).put("gt_now", thorNow + 2 * hour).toString()
        val b = bridge(listOf(Script(listOf(w, doc(1, thorNow + 2 * hour - 60_000, "{\"t\":\"old\"}"), backlogEnd,
            doc(2, thorNow + 2 * hour + 500, "{\"t\":\"pos\"}")))), docs, ArrayList(), wall = { thorNow })
        b.runHere()
        assertEquals(-2 * hour, b.clock.shiftMs)
        // the backlog keeps its age (a minute old), the live one is now
        assertEquals(thorNow - 60_000, docs[0].getLong("_gt"))
        assertEquals(thorNow + 500, docs[1].getLong("_gt"))
        // and the gate counts the live one current (it never did: 2 h off)
        val gate = com.jrees.wowcompanion.InputGate()
        assertFalse(gate.note(1_000L, docs[0].getLong("_gt"), thorNow))
        assertTrue(gate.note(2_000L, docs[1].getLong("_gt"), thorNow + 600))
    }

    @Test fun anOlderDesktopsZoneIsLearntFromLiveDocumentsNeverTheBacklog() {
        val docs = ArrayList<JSONObject>()
        // UTC+1 PC, UTC+3 Thor (PC 2 h behind); the backlog's stamp is old and must teach nothing; the live ones come 6 s apart, a few
        // seconds late, and the shift is taken once two of them have kept time
        var calls = 0
        fun arrival(n: Int) = thorNow + n * 6_000L
        val b = bridge(listOf(Script(listOf(welcome, doc(1, thorNow - 2 * hour - 3 * hour, "{\"t\":\"old\"}"), backlogEnd,
            doc(2, arrival(0) - 2 * hour - 3_000, "{\"t\":\"a\"}"), doc(3, arrival(1) - 2 * hour - 1_000, "{\"t\":\"b\"}"),
            doc(4, arrival(2) - 2 * hour - 2_000, "{\"t\":\"c\"}")))),
            docs, ArrayList(), wall = { arrival(calls++) })
        b.runHere()
        assertEquals(2 * hour, b.clock.shiftMs)
        assertEquals(thorNow - 5 * hour, docs[0].getLong("_gt"))     // the backlog: never moved by a guess
        assertEquals(arrival(0) - 2 * hour - 3_000, docs[1].getLong("_gt"))   // one live document proves nothing yet
        assertEquals(arrival(1) - 1_000, docs[2].getLong("_gt"))
        assertEquals(arrival(2) - 2_000, docs[3].getLong("_gt"))
        // and the gate counts them current from then on (it never did: 2 h off)
        val gate = com.jrees.wowcompanion.InputGate()
        assertFalse(gate.note(1_000L, docs[1].getLong("_gt"), arrival(0)))
        assertTrue(gate.note(7_000L, docs[2].getLong("_gt"), arrival(1)))
    }

    @Test fun anOldLogReadAfterTheBacklogTeachesNothing() {
        // Desktop 0.2.29 reads the game's log only after its bridge is up (AppController.Start: StartBridge, StartSunshine, StartMdns,
        // then the log), and again when its WoW folder is chosen: a Thor that connected in between gets an empty backlog, then the old
        // log's documents as live ones. Same time zone: a 3 h old document must not teach a 3 h shift (nothing would be current again)
        val docs = ArrayList<JSONObject>()
        // the Thor's clock as each live document arrives: the old ones together (one read of the log), the fresh ones 6 s apart
        fun arrival(n: Int) = if (n < 40) thorNow + n * 2L else thorNow + 80 + (n - 40) * 6_000L
        var calls = 0
        val old = (0 until 40).map { doc(it, thorNow - 3 * hour + it * 1_000L, "{\"t\":\"old$it\"}") }
        val fresh = (40 until 43).map { doc(it, arrival(it) - 300, "{\"t\":\"new$it\"}") }
        val b = bridge(listOf(Script(listOf(welcome, backlogEnd) + old + fresh)), docs, ArrayList(), wall = { arrival(calls++) })
        b.runHere()
        assertEquals(0L, b.clock.shiftMs)
        assertEquals(thorNow - 3 * hour, docs[0].getLong("_gt"))
        for (n in 40 until 43) assertEquals(arrival(n) - 300, docs[n].getLong("_gt"))
    }

    @Test fun sameZoneOrAFewMinutesOffChangesNothing() {
        for (gap in longArrayOf(0L, 4_000L, -9_000L, 4 * 60_000L, -6 * 60_000L)) {
            val docs = ArrayList<JSONObject>()
            bridge(listOf(Script(listOf(welcome, backlogEnd, doc(1, thorNow - gap, "{\"t\":\"a\"}")))), docs, ArrayList(), wall = { thorNow }).runHere()
            assertEquals("gap $gap", thorNow - gap, docs.single().getLong("_gt"))
        }
    }

    @Test fun rawLinesAreMovedToo() {
        val docs = ArrayList<JSONObject>()
        // the PC's clock reads 17:44:41 when the Thor's reads 18:44:41; its line was written then
        val line = wcb(1, "1/1", "{\"t\":\"pos\"}", time = "17:44:41.000").replace("10/23", "11/15")
        val w = JSONObject(welcome).put("gt_now", thorNow - hour).toString()
        bridge(listOf(Script(listOf(w, lines(line)))), docs, ArrayList(), wall = { thorNow }).runHere()
        assertEquals(thorNow, docs.single().getLong("_gt"))
    }

    /** the user's pick 2026-11-17 ("Ask the Thor again"): Desktop's login_again gets the saved login once more, only down a pinned
     *  connection and only while the auto sign-in gives one (fake data) */
    @Test fun desktopsLoginAgainSendsTheSavedLoginOnceMore() {
        fun run(tls: Boolean, fp: String, login: Pair<String, String>?): List<JSONObject> {
            val c = Script(listOf(welcome, """{"t":"login_again"}"""))
            var log: BridgeLog? = null
            log = BridgeLog("192.168.1.20", 48210, tls, fp, "t".repeat(43), "c".repeat(32),
                dialer = { _, _, _, _ -> c }, onMessage = {}, onStatus = {}, savedLogin = { login },
                gameAccount = { BridgeProtocol.GameAccount(ask = false, list = "Fake1|!Fake2|") }, sleeper = { log!!.stop() })
            log.runHere()
            return c.sent.map { JSONObject(it) }.filter { it.optString("t") == "login_file" }
        }
        val sent = run(true, "ab".repeat(32), "fake@example.invalid" to "not-a-password")
        assertEquals("the welcome's and the one asked again", 2, sent.size)
        assertTrue(sent.all { it.getString("email") == "fake@example.invalid" && it.getString("account_list") == "Fake1|!Fake2|" })
        assertEquals("auto sign-in off: nothing", 0, run(true, "ab".repeat(32), null).size)
        assertEquals("no pinned connection: nothing", 0, run(false, "", "fake@example.invalid" to "not-a-password").size)
    }
}
