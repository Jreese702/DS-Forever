package com.jrees.wowcompanion.stream

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issues #304 / #357 (the user's pick, 2026-11-16: "Desktop warns (window notice + a reason to the Thor in the welcome/status) when
 * WoW's client area doesn't fill the captured monitor, and when WoW runs elevated"): the Thor reads Desktop's word (wow.input) for
 * its report; the player reads Desktop's status line.
 */
class PcInputTest {
    @Test fun desktopSaysWhyTheTapsMiss() {
        fun welcome(wow: JSONObject) = BridgeProtocol.parse(JSONObject().put("t", "welcome").put("wow", wow).toString()) as BridgeMsg.Welcome
        assertEquals("elevated", welcome(JSONObject().put("running", true).put("input", "elevated")
            .put("input_why", "WoW runs as administrator: taps can't reach it")).pc!!.input)
        assertEquals("not_filling", (BridgeProtocol.parse(JSONObject().put("t", "wow").put("state", "running")
            .put("input", "not_filling").toString()) as BridgeMsg.Wow).pc!!.input)
        // fine, or an older Desktop
        assertEquals("", welcome(JSONObject().put("running", true).put("input", "")).pc!!.input)
        assertEquals("", welcome(JSONObject().put("running", true)).pc!!.input)
        // the status line that carries it to the player: as any PC status
        assertEquals("WoW runs as administrator: taps can't reach it", (BridgeProtocol.parse(JSONObject().put("t", "status")
            .put("text", "WoW runs as administrator: taps can't reach it").toString()) as BridgeMsg.Status).text)
    }
}
