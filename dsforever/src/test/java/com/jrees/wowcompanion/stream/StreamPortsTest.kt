package com.jrees.wowcompanion.stream

import com.jrees.wowcompanion.SetupFlow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user, 2026-11-13: streaming worked once, then the reconnect sat on "PC found". A separately installed Sunshine held the stock
 * 47984/47989 while DS Forever Desktop's own moved to 52989, and the Thor's 47989 fallback reached the foreign one. "Yes, add it":
 * the Thor never falls back to the stock ports, uses the ports Desktop reports and shows the PC's reason. Off-device.
 */
class StreamPortsTest {
    private val desk = PairedHost(name = "Den", address = "192.168.1.11", httpPort = 47989, httpsPort = 47984, serverCertPem = "PEM",
        bridgePort = 48210, bridgeToken = "A".repeat(43), desktopId = "h1")
    private val reported = StreamPorts.Report(said = true, http = 52989, https = 52984)

    private fun use(p: StreamPorts.Pick) = p as StreamPorts.Pick.Use

    @Test fun theReportedPortWins() {
        val p = use(StreamPorts.pick(desk, reported, 0))
        assertEquals(52989, p.http); assertEquals(52984, p.https)
        // https not said: Sunshine's http - 5
        assertEquals(52984, use(StreamPorts.pick(desk, StreamPorts.Report(true, 52989, 0), 0)).https)
        // a host stored with no port at all takes Desktop's too
        assertEquals(52989, use(StreamPorts.pick(desk.copy(httpPort = 0, httpsPort = 0), reported, 0)).http)
    }

    @Test fun aNewDesktopNeverFallsBackToTheStockPorts() {
        val unknown = desk.copy(httpPort = 0, httpsPort = 0)
        // no welcome yet: wait, for as long as it takes (the connect's own give-up says "Waiting for the PC"), never 47989
        for (t in listOf(0L, 5_000L, StreamPorts.WAIT_MS, StreamPorts.GIVE_UP_MS, 600_000L))
            assertEquals(StreamPorts.Pick.Wait, StreamPorts.pick(unknown, null, t))
        // Desktop said its Sunshine has no port yet (-1): its word stands, even over a stored port
        val noPort = StreamPorts.Report(said = true, http = -1, https = -1)
        assertFalse(noPort.known)
        for (t in listOf(0L, StreamPorts.WAIT_MS, 600_000L)) {
            assertEquals(StreamPorts.Pick.Wait, StreamPorts.pick(desk, noPort, t))
            assertEquals(StreamPorts.Pick.Wait, StreamPorts.pick(unknown, noPort, t))
        }
    }

    @Test fun aSavedPairingKeepsWorkingWhenTheBridgeIsLate() {
        assertEquals(StreamPorts.Pick.Wait, StreamPorts.pick(desk, null, StreamPorts.WAIT_MS - 1))
        val p = use(StreamPorts.pick(desk.copy(httpPort = 52989, httpsPort = 52984), null, StreamPorts.WAIT_MS))
        assertEquals(52989, p.http); assertEquals(52984, p.https); assertEquals("stored", p.from)
        // a saved pairing's stored port, as saved: kept on reading it back
        val back = PairedHost.fromJson(desk.copy(httpPort = 52989, httpsPort = 52984).toJson())!!
        assertEquals(52989, back.httpPort); assertEquals(52984, back.httpsPort)
        // a record without ports reads as unknown, never as 47989
        val bare = desk.toJson().apply { remove("httpPort"); remove("httpsPort") }
        assertEquals(0, PairedHost.fromJson(bare)!!.httpPort)
    }

    @Test fun anOlderDesktopKeepsTodaysBehaviour() {
        val old = StreamPorts.Report(said = false, http = 0, https = 0)
        assertEquals(47989, use(StreamPorts.pick(desk, old, 0)).http)
        assertEquals(53001, use(StreamPorts.pick(desk.copy(httpPort = 53001, httpsPort = 52996), old, 0)).http)
        val bare = use(StreamPorts.pick(desk.copy(httpPort = 0, httpsPort = 0), old, 0))
        assertEquals(StreamPorts.STOCK_HTTP, bare.http); assertEquals(StreamPorts.STOCK_HTTPS, bare.https)
        // a plain Sunshine (no Desktop) too: it IS a stock Sunshine
        val plain = desk.copy(bridgePort = 0, bridgeToken = "", httpPort = 0, httpsPort = 0)
        assertEquals(StreamPorts.STOCK_HTTP, use(StreamPorts.pick(plain, null, 0)).http)
        assertEquals(48001, use(StreamPorts.pick(plain.copy(httpPort = 48001), null, 0)).http)
    }

    @Test fun pairingDialsTheReportedPortNeverAGuess() {
        val pc = FoundPc("Den", "192.168.1.11", sunshinePort = 47989, bridgePort = 48210)
        assertEquals(52989, StreamPorts.forPairing(52989, pc))
        assertEquals(47989, StreamPorts.forPairing(0, pc))                    // an older Desktop's pair_ok: the advertised port
        assertEquals(0, StreamPorts.forPairing(0, pc.copy(sunshinePort = 0)))
        // Desktop's pair_ok said its Sunshine has no port (-1): not the advert, which may be another Sunshine's
        assertEquals(0, StreamPorts.forPairing(-1, pc))
    }

    /** the user's PC: a foreign Sunshine's _nvstream advert (47989) and Desktop's TXT (52989) at one address: Desktop's wins */
    @Test fun desktopsTxtWinsOverAForeignNvstreamAdvert() {
        val txt = mapOf("proto" to "1", "id" to "h1", "pc" to "DESKTOP-ERNE9KJ", "sunshine" to "52989", "ver" to "0.2.27")
        val nv = Advert("_nvstream._tcp", "DESKTOP-ERNE9KJ", "192.168.1.11", 47989)
        val dsf = Advert("_dsforever._tcp", "DESKTOP-ERNE9KJ", "192.168.1.11", 48210, txt)
        assertEquals(52989, FoundPcs.merge(listOf(nv, dsf)).single().sunshinePort)
        assertEquals(52989, FoundPcs.merge(listOf(dsf, nv)).single().sunshinePort)
        // Desktop's TXT without a port yet ("-1"): the advert, as before
        assertEquals(47989, FoundPcs.merge(listOf(nv, dsf.copy(txt = txt + ("sunshine" to "-1")))).single().sunshinePort)
        // the same PC at two addresses: Desktop's port still wins
        val ts = dsf.copy(address = "100.69.36.86")
        assertEquals(52989, FoundPcs.merge(listOf(ts, nv), preferSubnet = "192.168.1").single().sunshinePort)
    }

    @Test fun theWelcomeCarriesThePcsReason() {
        val sun = JSONObject().put("running", true).put("http_port", 52989).put("https_port", 52984)
            .put("problem", "other_sunshine").put("why", "pc busy: another Sunshine")
        val w = BridgeProtocol.parse(JSONObject().put("t", "welcome").put("host_id", "h1").put("sunshine", sun).toString()) as BridgeMsg.Welcome
        assertEquals("other_sunshine", w.sunProblem); assertEquals("pc busy: another Sunshine", w.sunWhy)
        assertFalse(w.oldDesktop)
        assertEquals(StreamPorts.Report(true, 52989, 52984), StreamPorts.Report.of(w))
        // a Desktop with nothing wrong, and an older one that never reported ports
        val fine = BridgeProtocol.parse(JSONObject().put("t", "welcome").put("sunshine", JSONObject().put("http_port", -1)).toString()) as BridgeMsg.Welcome
        assertEquals("", fine.sunWhy); assertFalse(fine.oldDesktop); assertFalse(StreamPorts.Report.of(fine).known)
        val old = BridgeProtocol.parse(JSONObject().put("t", "welcome").put("host_id", "h1").toString()) as BridgeMsg.Welcome
        assertTrue(old.oldDesktop)
        val oldSun = BridgeProtocol.parse(JSONObject().put("t", "welcome").put("sunshine", JSONObject().put("running", true)).toString()) as BridgeMsg.Welcome
        assertTrue(oldSun.oldDesktop)
    }

    @Test fun theReasonsLine() {
        assertEquals(StreamCurtain.BUSY_SUNSHINE, StreamCurtain.reason("other_sunshine", "pc busy: another Sunshine"))
        assertEquals("PC busy: another Sunshine is running", StreamCurtain.BUSY_SUNSHINE)
        assertEquals(StreamCurtain.BUSY_PROGRAM, StreamCurtain.reason("other_app", "pc busy: another program has the streaming ports"))
        assertEquals(StreamCurtain.BUSY_LISTENING, StreamCurtain.reason("not_listening", ""))
        assertEquals(StreamCurtain.BUSY_STOPPED, StreamCurtain.reason("exited", "pc busy: the streaming host stopped"))
        assertEquals(StreamCurtain.BUSY_SUNSHINE, StreamCurtain.reason("", "pc busy: another Sunshine"))
        assertEquals("PC busy: something new", StreamCurtain.reason("later", "pc busy: something new"))
        assertEquals("", StreamCurtain.reason("", ""))
    }

    /** the curtain shows the PC's reason instead of sitting on "PC found" */
    @Test fun theCurtainShowsTheReason() {
        val pc = PcWow(state = "running", screen = "charselect", bnet = "signed_in")
        val c = StreamCurtain.decide(false, true, pc, route = "home Wi-Fi", pcWhy = StreamCurtain.BUSY_SUNSHINE) as StreamCurtain.Shows.Curtain
        assertEquals("PC busy: another Sunshine is running", c.line)
        assertEquals(listOf("PC found (home Wi-Fi)"), c.rows.map { it.label })
        assertEquals(SetupFlow.Mark.OK, c.rows.single().mark)
        // waiting for Desktop's ports: says so, after the PC answered and before
        assertEquals(StreamCurtain.WAITING, (StreamCurtain.decide(false, true, pc, waiting = true) as StreamCurtain.Shows.Curtain).line)
        assertEquals(StreamCurtain.WAITING, (StreamCurtain.decide(false, true, null, waiting = true) as StreamCurtain.Shows.Curtain).line)
        // a problem reaching the PC still wins before it answered
        assertEquals("nope", (StreamCurtain.decide(false, true, null, problem = "nope", waiting = true) as StreamCurtain.Shows.Curtain).line)
        // once the stream is up the reason no longer holds it back
        assertEquals(StreamCurtain.Shows.Stream, StreamCurtain.decide(true, true, pc, pcWhy = StreamCurtain.BUSY_SUNSHINE))
        // nothing wrong: as before
        assertEquals("Connecting to your PC", (StreamCurtain.decide(false, true, pc) as StreamCurtain.Shows.Curtain).line)
    }
}
