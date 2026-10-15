package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * chat22 (1.0.8, Send Mail and the auction-house search): ChatStream(field = true) fills a field another add-on borrowed from Chat.lua.
 * A small stand-in for Chat.lua's field (the buffer, its letters / deletes / clear, the report on 15 and Send on 23) answers it here.
 */
class ChatStreamFieldTest {
    private var clock = 0L

    /** Chat.lua with a field open: the cells land in [buf]; 15 and 23 answer at once; [lose] drops the next letter (a lost tap) */
    private inner class FakeField(var buf: String = "", val max: Int = 255) {
        val cells = ArrayList<Pair<String, Int>>()
        var lose = 0
        var sentText: String? = null
        var answer: ((ChatStream.Heard) -> Unit)? = null
        private val queued = ArrayList<ChatStream.Heard>()
        /** the add-on's answers arrive later, never inside a tap (as on the device): delivered here, oldest first */
        fun deliver() { while (queued.isNotEmpty()) answer?.invoke(queued.removeAt(0)) }
        fun heard(op: String, ok: Boolean = true) =
            ChatStream.Heard("chatt", op, ok, null, "FIELD", buf.length, ChatStream.hash(buf), 0, ChatStream.hash(""), "body")
        fun tap(c: Pair<String, Int>) {
            cells.add(c)
            val (row, col) = c
            when {
                row == "chq" && col == ChatStream.BACK -> buf = buf.dropLast(1)
                row == "chq" && col == ChatStream.CLEAR -> buf = ""
                row == "chq" && col == ChatStream.BODY -> queued.add(heard("body"))
                row == "chq" && col == ChatStream.SEND -> { sentText = buf; queued.add(heard("send")) }
                row == "chs" && (col == 36 || col == 39) -> {}                         // spacers: nothing typed
                else -> {
                    val ch = when (row) { "chq" -> (col + 8).toChar(); "chr" -> (col + 48).toChar(); else -> (col + 88).toChar() }
                    if (lose > 0) lose-- else if (buf.length < max) buf += ch
                }
            }
        }
    }

    private fun stream(f: FakeField, max: Int = 255): ChatStream {
        lateinit var s: ChatStream
        s = ChatStream({ cells, _ -> cells.forEach { f.tap(it) }; true }, { clock }, field = true, maxLen = max)
        f.answer = { h -> s.heard(h) }
        return s
    }

    @Test fun aFieldIsTypedCheckedAndHandedOver() {
        val f = FakeField()
        val s = stream(f)
        val events = ArrayList<ChatStream.Event>()
        s.onEvent = { events.add(it) }
        assertEquals("FIELD", s.type)
        assertTrue(s.setBody("Thrall"))
        assertEquals("Thrall", f.buf)
        assertTrue(s.setBody("Thra"))                          // two deletes
        assertEquals("Thra", f.buf)
        assertTrue(s.send())
        f.deliver()
        assertEquals("Thra", f.sentText)
        assertEquals(listOf<ChatStream.Event>(ChatStream.Event.Sent), events)
        assertTrue("no type, target or top-screen cell in a field", f.cells.none { it == "chq" to ChatStream.TGT ||
            it.first == "chq" && it.second in 16..22 || it == "chs" to 39 })
    }

    @Test fun aLostLetterIsTypedAgainBodyOnly() {
        val f = FakeField()
        val s = stream(f)
        var sent = false
        s.onEvent = { if (it == ChatStream.Event.Sent) sent = true }
        f.lose = 1
        s.setBody("Auction")
        assertEquals("uction", f.buf)                          // the first letter never landed
        f.cells.clear()
        assertTrue(s.send())
        f.deliver()
        assertTrue(sent)
        assertEquals("Auction", f.sentText)
        assertFalse("a field's resync never spells a target", f.cells.contains("chq" to ChatStream.TGT))
    }

    @Test fun theFieldsOwnLimitAndItsStartingText() {
        val f = FakeField(buf = "12", max = 6)
        val s = stream(f, max = 6)
        s.startField("12")
        f.cells.clear()
        s.setBody("1234567890")                                // only 4 more go: the field holds 6
        assertEquals("123456", f.buf)
        assertEquals(4, f.cells.size)
    }

    @Test fun aChatStreamIsUnchanged() {
        val cells = ArrayList<Pair<String, Int>>()
        val s = ChatStream({ c, _ -> cells.addAll(c); true }, { clock })
        assertEquals("SAY", s.type)
        s.setBody("hi")
        s.resync()
        assertTrue(cells.contains("chq" to ChatStream.TGT))      // the chat still sends its target and type on a resync
        assertTrue(cells.contains("chq" to 16))
    }
}
