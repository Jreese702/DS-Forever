package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE LATEST ADD-ON DOCUMENTS, FOR A REBUILT HOST (the bottom screen comes straight back, 2026-11-25): inside the launcher the reader
 * keeps the newest raw document of each STATE type (the map, bags, quests, the NPC window, the pop-ups ...), so a host made again after
 * Android closed the bottom window replays them (non-current: the gate makes them so) and reads the log on from the old reader's
 * place - not the whole General.log from byte 0. Answers, presses and current-only words are never kept; memory only, capped by bytes.
 */
class DocMemoryTest {
    @After fun clean() { DocMemory.shared.clear(); DocMemory.on = false }

    private fun doc(type: String, vararg kv: Pair<String, Any>) = JSONObject().put("type", type).also { o -> kv.forEach { (k, v) -> o.put(k, v) } }

    @Test fun stateTypesAreKeptTheLastOneWins() {
        DocMemory.on = true
        DocMemory.note(doc("bagsctl", "n" to 1))
        DocMemory.note(doc("quests", "n" to 1))
        DocMemory.note(doc("bagsctl", "n" to 2))
        val r = DocMemory.replay()
        assertEquals(listOf("quests", "bagsctl"), r.map { it.optString("type") })
        assertEquals(2, r[1].optInt("n"))
    }

    @Test fun answersAndPressesAreNeverKept() {
        DocMemory.on = true
        for (t in listOf("bagsack", "guestack", "socialpad", "radialuse", "itemcd", "padkeytest", "hello", "bye", "notice", "chatt", "aggro"))
            DocMemory.note(doc(t))
        assertEquals(0, DocMemory.replay().size)
    }

    @Test fun onlyInsideTheLauncher() {
        DocMemory.note(doc("quests"))
        assertEquals("off (the app, Streaming): nothing kept", 0, DocMemory.replay().size)
    }

    @Test fun theByteCapEvictsTheOldest() {
        val s = DocMemory.Store(capBytes = 100)
        s.put("a", "x".repeat(40)); s.put("b", "y".repeat(40)); s.put("c", "z".repeat(40))
        assertEquals(listOf("b", "c"), s.keys())
        assertTrue(s.bytes <= 100)
        s.put("d", "w".repeat(500))
        assertEquals("a document over the cap alone is not kept", listOf("b", "c"), s.keys())
        s.put("b", "y".repeat(10))
        assertEquals("a newer one moves to the end", listOf("c", "b"), s.keys())
        assertEquals(50L, s.bytes)
        assertEquals(4L * 1024 * 1024, DocMemory.CAP_BYTES)
        assertEquals(2L * 1024 * 1024, DocMemory.CAP_LIGHT8)
    }

    @Test fun replayIsInArrivalOrder() {
        DocMemory.on = true
        for (t in listOf("full", "interact", "dialogs", "padmap", "spells")) DocMemory.note(doc(t))
        DocMemory.note(doc("interact", "holder" to "bags"))
        assertEquals(listOf("full", "dialogs", "padmap", "spells", "interact"), DocMemory.replay().map { it.optString("type") })
    }

    @Test fun perEntityKeys() {
        DocMemory.on = true
        DocMemory.note(doc("profrec", "p" to JSONObject().put("id", 171)))
        DocMemory.note(doc("profrec", "p" to JSONObject().put("id", 333)))
        DocMemory.note(doc("profrec", "p" to JSONObject().put("id", 171).put("again", true)))
        val r = DocMemory.replay()
        assertEquals(2, r.size)
        assertEquals(listOf(333, 171), r.map { it.optJSONObject("p")!!.optInt("id") })
        assertEquals("profrec#171", DocMemory.key("profrec", doc("profrec", "p" to JSONObject().put("id", 171))))
        assertEquals("quests", DocMemory.key("quests", doc("quests")))
    }

    /** every type CompanionHost.handleMessage routes is classified: a new type must be put in KEEP or SKIP */
    @Test fun theFullTypeTable() {
        val host = File(srcDir(), "CompanionHost.kt").readText()
        val body = host.substringAfter("        when (msg.optString(\"type\")) {\n").substringBefore("\n        }\n    }\n")
        val types = Regex("^ {12}((?:\"[a-zA-Z0-9_]+\"(?:, )?)+) ->", RegexOption.MULTILINE).findAll(body)
            .flatMap { m -> Regex("\"([a-zA-Z0-9_]+)\"").findAll(m.groupValues[1]).map { it.groupValues[1] } }.toSet()
        assertTrue("the table was read (${types.size})", types.size > 100)
        val unclassified = types - DocMemory.KEEP - DocMemory.SKIP
        assertTrue("classify: $unclassified", unclassified.isEmpty())
        assertTrue("both: ${DocMemory.KEEP intersect DocMemory.SKIP}", (DocMemory.KEEP intersect DocMemory.SKIP).isEmpty())
        assertTrue("stale: ${(DocMemory.KEEP + DocMemory.SKIP) - types}", ((DocMemory.KEEP + DocMemory.SKIP) - types).isEmpty())
        for (t in types) if (t.endsWith("ack") || t.endsWith("pad") || t.endsWith("use") || t.endsWith("cd")) assertFalse("$t is an answer / press", DocMemory.keeps(t))
        for (t in listOf("padkeytest", "hello", "bye", "notice", "dsfreset", "svkeep")) assertFalse(t, DocMemory.keeps(t))
        for (t in listOf("full", "pos", "bagsctl", "quests", "interact", "dialogs", "spells", "talents", "padmap", "castbar", "social", "gf",
            "calendar", "macros", "character", "chat", "hub", "auras")) assertTrue(t, DocMemory.keeps(t))
        // filled on the reader thread before the hand-off to the main thread, inside the launcher only; memory only
        val tail = host.substringAfter("private fun startTail() {").substringBefore("\n    }\n")
        assertTrue(tail.contains("DocMemory.note(msg); ui.post { handleMessage(msg) }"))
        assertTrue(File(srcDir(), "CompanionHost.kt").readText().contains("DocMemory.on = LauncherHooks.pkg != null"))
    }

    private fun srcDir(): File = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion"), File("companion/app/src/main/java/com/jrees/wowcompanion"))
        .first { it.isDirectory }
}
