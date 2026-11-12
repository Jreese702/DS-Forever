package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * chat33 (#183, 1.0.25: "Not able to send messages in chat/guild/whisper/party/etc"; #184, its duplicate). On that player's game DS
 * Forever: Interact never ran, so no room could hold the controller and Chat.lua refused every key and the check (15) with ok=false. The
 * stream ignored the refusal, re-typed everything twice and ended "The add-on did not get all the letters". Now a refused check is the
 * add-on's own reason at once; and the login probe {"probe":"ours"} becomes one report line (oursProbeLine).
 */
class ChatSendRefusalTest {
    private var clock = 0L

    /** Chat.lua that does not hold the controller: every typed pick answered ok=false with [why], the buffer left empty */
    private inner class Refusing(val why: String?) {
        val cells = ArrayList<Pair<String, Int>>()
        var answer: ((ChatStream.Heard) -> Unit)? = null
        private val queued = ArrayList<ChatStream.Heard>()
        fun deliver() { while (queued.isNotEmpty()) answer?.invoke(queued.removeAt(0)) }
        fun tap(c: Pair<String, Int>) {
            cells.add(c)
            if (c == "chq" to ChatStream.BODY) queued.add(ChatStream.Heard("chatt", "body", false, why, "SAY", 0, 0, 0, 0, "body", false))
            if (c == "chq" to ChatStream.SEND) queued.add(ChatStream.Heard("chatt", "send", false, why, "SAY", 0, 0, 0, 0, "body", false))
        }
    }

    private fun stream(f: Refusing): ChatStream {
        lateinit var s: ChatStream
        s = ChatStream({ cells, _ -> cells.forEach { f.tap(it) }; true }, { clock })
        f.answer = { h -> s.heard(h) }
        return s
    }

    @Test fun aRefusedCheckSaysTheAddOnsReasonAtOnce() {
        val f = Refusing("Interact add-on not running")
        val s = stream(f)
        val events = ArrayList<ChatStream.Event>()
        s.onEvent = { events.add(it) }
        s.setBody("hello")
        f.cells.clear()
        assertTrue(s.send())
        f.deliver()
        assertEquals(listOf<ChatStream.Event>(ChatStream.Event.Refused("Interact add-on not running")), events)
        assertEquals("one check, no re-type and no Send", listOf("chq" to ChatStream.BODY), f.cells)
        assertFalse(s.sending)
        assertEquals("the text is kept", "hello", s.body)
        clock += ChatStream.ANSWER_MS + 1
        s.tick()
        assertEquals("no late 'No answer' after the refusal", 1, events.size)
    }

    @Test fun aRefusalWithNoReasonStillStops() {
        val f = Refusing(null)
        val s = stream(f)
        val events = ArrayList<ChatStream.Event>()
        s.onEvent = { events.add(it) }
        s.setBody("hi")
        assertTrue(s.send())
        f.deliver()
        assertEquals(listOf<ChatStream.Event>(ChatStream.Event.Refused("The game refused it")), events)
    }

    @Test fun theOursProbeLine() {
        val bad = oursProbeLine(JSONObject("""{"type":"cap","probe":"ours","loaded":9,"missing":1,
            "off":["WowCompanionInteract:DEP_MISSING"],"capv":0,"errN":1,
            "errs":["Interface/AddOns/WowCompanionInteract/Interact.lua:12: attempt to index a nil value"]}"""))!!
        assertTrue("anything wrong is a warning", bad.first)
        assertTrue(bad.second, bad.second.contains("NOT loaded: WowCompanionInteract:DEP_MISSING"))
        assertTrue(bad.second, bad.second.contains("controller capture NONE"))
        assertTrue(bad.second, bad.second.contains("Interact.lua:12: attempt to index a nil value"))
        // an empty Lua table may come as {} rather than []: nothing missing then
        val good = oursProbeLine(JSONObject("""{"type":"cap","probe":"ours","loaded":10,"missing":0,"off":{},"capv":13,"errN":0,"errs":{}}"""))!!
        assertFalse(good.first)
        assertEquals("our add-ons: 10 loaded; controller capture v13", good.second)
        assertNull(oursProbeLine(JSONObject("""{"type":"cap","probe":"chatstyle"}""")))
    }
}
