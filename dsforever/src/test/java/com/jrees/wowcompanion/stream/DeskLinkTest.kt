package com.jrees.wowcompanion.stream

import com.jrees.wowcompanion.DeskClicks
import com.jrees.wowcompanion.PsSync
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Desktop clicks the pad only for a Desktop that says so (welcome caps "pad_click", Desktop 0.2.42) down a pinned wss connection; an
 * older Desktop (no caps) or a plain ws connection keeps the stream's touch path. Polestar's files are asked for only from a Desktop
 * that says "polestar_sync", before the PC's WoW starts and again when it stops. A scripted bridge, off-device.
 */
class DeskLinkTest {
    @After fun clean() = DeskClicks.reset()

    /** the bridge's frames; [seen] records whether DeskClicks had a link when each frame was handed out */
    private class Script(private val frames: List<String>) : BridgeConn {
        val sent = ArrayList<String>()
        val linked = ArrayList<Boolean>()
        private var i = 0
        override fun send(text: String) { sent.add(text) }
        override fun receive(): String? { linked.add(DeskClicks.link != null); return frames.getOrNull(i++) }
        override val closeCode = 1006
        override val peerFingerprint = "ab".repeat(32)
        override fun close(code: Int) {}
    }

    private fun welcome(running: Boolean, vararg caps: String) = JSONObject().put("t", "welcome").put("proto", 1).put("host_id", "d1")
        .put("pc_name", "Den PC").put("wow", JSONObject().put("found", true).put("running", running)).put("device_id", "c".repeat(32))
        .put("backlog", 0).also { if (caps.isNotEmpty()) it.put("caps", JSONArray(caps.toList())) }.toString()

    private fun run(c: Script, tls: Boolean, polestar: PsSync.Ctx? = null) {
        var log: BridgeLog? = null
        var used = false
        log = BridgeLog("192.168.1.20", 48210, tls, if (tls) "ab".repeat(32) else "", "t".repeat(43), "c".repeat(32),
            dialer = { _, _, _, _ -> if (!used) { used = true; c } else throw java.io.IOException("refused") },
            onMessage = {}, onStatus = {}, sleeper = { log!!.stop() }, polestar = { polestar })
        log.runHere()
    }

    @Test fun aDesktopThatClicksTakesThePadOnlyDownAPinnedConnection() {
        val c = Script(listOf(welcome(true, "pad_click", "polestar_sync")))
        run(c, tls = true)
        assertEquals(listOf(false, true), c.linked)                // linked from the welcome on
        assertNull("the connection's end drops the link (the touch path again)", DeskClicks.link)
    }

    @Test fun anOlderDesktopOrAPlainConnectionKeepsTheTouchPath() {
        val old = Script(listOf(welcome(true)))
        run(old, tls = true)
        assertFalse(old.linked.any { it })
        val plain = Script(listOf(welcome(true, "pad_click")))
        run(plain, tls = false)
        assertFalse(plain.linked.any { it })
    }

    @Test fun polestarsFilesAreAskedForBeforeThePcsWowStartsAndWhenItStops() {
        val root = Files.createTempDirectory("dlink").toFile()
        try {
            File(root, "WTF/Account/11111111#1/SavedVariables").mkdirs()
            val ctx = PsSync.Ctx(File(root, "WTF"), File(root, "kept"), gameRunning = { false })
            val stop = JSONObject().put("t", "wow").put("state", "stopped").toString()
            val runningNow = JSONObject().put("t", "wow").put("state", "running").toString()
            val a = Script(listOf(welcome(false, "polestar_sync"), runningNow, stop))
            run(a, tls = true, polestar = ctx)
            assertEquals(2, a.sent.count { JSONObject(it).optString("t") == "ps_read" })
            val b = Script(listOf(welcome(false)))                   // an older Desktop: never asked
            run(b, tls = true, polestar = ctx)
            assertEquals(0, b.sent.count { JSONObject(it).optString("t") == "ps_read" })
            val p = Script(listOf(welcome(false, "polestar_sync")))  // a plain connection: never
            run(p, tls = false, polestar = ctx)
            assertEquals(0, p.sent.count { JSONObject(it).optString("t") == "ps_read" })
            assertNotNull(PsSync.readRequest(ctx))
            assertTrue(a.sent.none { it.contains("11111111") })    // the account crosses as its hash
        } finally { root.deleteRecursively() }
    }
}
