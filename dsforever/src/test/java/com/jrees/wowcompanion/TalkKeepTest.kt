package com.jrees.wowcompanion

import com.jrees.wowcompanion.TalkKeep.Used
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * HOLD TO TALK'S LAST USED CHAT, PER CHARACTER (the user, 2026-11-16, pick "Remember per character"), off-device: kept per name-realm,
 * back after a restart for that character, Say for a new one or none known, a whisper keeps its person, a numbered channel is found
 * again by its name, a chat that is not there now reads as Say, and the message's words are never kept.
 */
class TalkKeepTest {
    private var saved: String? = null
    private val store = object : TalkKeep.Store {
        override fun read(): String? = saved
        override fun write(s: String) { saved = s }
    }

    @Before fun clean() { TalkKeep.reset(); saved = null }
    @After fun after() { TalkKeep.reset() }

    private val all = setOf("SAY", "PARTY", "GUILD", "YELL")

    @Test fun keptPerCharacterAndBackAfterARestart() {
        TalkKeep.init(store)
        TalkKeep.character("Thrall-Forever")
        assertTrue(TalkKeep.went(Used("PARTY")))
        assertFalse("the same chat again: no change", TalkKeep.went(Used("PARTY")))
        TalkKeep.character("Jaina-Forever")
        assertEquals("a new character starts on Say", Used(), TalkKeep.used)
        TalkKeep.went(Used("WHISPER", "Thrall"))

        TalkKeep.reset()                                                   // the app restarts
        TalkKeep.init(store)
        assertEquals("no character known yet: Say", Used(), TalkKeep.used)
        TalkKeep.character("Thrall-Forever")
        assertEquals(Used("PARTY"), TalkKeep.used)
        TalkKeep.character("Jaina-Forever")
        assertEquals(Used("WHISPER", "Thrall"), TalkKeep.used)
    }

    @Test fun noCharacterKnownKeepsItInMemoryOnly() {
        TalkKeep.init(store)
        TalkKeep.went(Used("GUILD"))
        assertEquals(Used("GUILD"), TalkKeep.used)
        assertEquals(null, saved)
    }

    @Test fun aCharacterSwitchTellsTheListeners() {
        TalkKeep.init(store)
        var told = 0
        TalkKeep.onChange { told++ }
        TalkKeep.character("A-R"); TalkKeep.character("A-R")
        assertEquals("told once per switch", 1, told)
        TalkKeep.went(Used("YELL"))
        assertEquals(2, told)
    }

    @Test fun neverTheWordsOnlyTheChat() {
        TalkKeep.init(store)
        TalkKeep.character("Thrall-Forever")
        TalkKeep.went(Used("CHANNEL:2", "ignored", "Trade - City"))
        val s = saved ?: ""
        assertTrue(s.contains("Trade - City"))
        assertFalse("a channel keeps no person", s.contains("ignored"))
        val keys = org.json.JSONObject(s).getJSONObject("Thrall-Forever").keys().asSequence().toSet()
        assertEquals(setOf("t", "w", "c"), keys)
        TalkKeep.went(Used("PARTY", "Bob", "x"))
        assertEquals(Used("PARTY"), TalkKeep.used)
        TalkKeep.went(Used("WHISPER"))
        assertEquals("a whisper with nobody is Say", Used(), TalkKeep.used)
    }

    @Test fun whatIsNotThereNowIsSay() {
        assertEquals("SAY" to "", TalkKeep.resolve(Used("PARTY"), setOf("SAY", "YELL"), emptyList()))
        assertEquals("PARTY" to "", TalkKeep.resolve(Used("PARTY"), all, emptyList()))
        assertEquals("no reply target now", "SAY" to "", TalkKeep.resolve(Used("REPLY"), all, emptyList()))
        assertEquals("WHISPER" to "Thrall", TalkKeep.resolve(Used("WHISPER", "Thrall"), all, emptyList()))
        assertEquals("SAY" to "", TalkKeep.resolve(Used("WHISPER", ""), all, emptyList()))
        assertEquals("SAY" to "", TalkKeep.resolve(Used("NONSENSE"), all, emptyList()))
    }

    @Test fun aChannelIsFoundAgainByItsName() {
        val joined = listOf(1 to "General - Orgrimmar", 3 to "Trade - City")
        assertEquals("its number now", "CHANNEL:3" to "", TalkKeep.resolve(Used("CHANNEL:2", chan = "Trade - City"), all, joined))
        assertEquals("another zone's General", "CHANNEL:1" to "", TalkKeep.resolve(Used("CHANNEL:1", chan = "General - Durotar"), all, joined))
        assertEquals("left: Say", "SAY" to "", TalkKeep.resolve(Used("CHANNEL:4", chan = "LookingForGroup"), all, joined))
        assertEquals("no name kept: by number", "CHANNEL:3" to "", TalkKeep.resolve(Used("CHANNEL:3"), all, joined))
        assertEquals("SAY" to "", TalkKeep.resolve(Used("CHANNEL:5"), all, joined))
    }

    @Test fun theChatAndHostWireIt() {
        val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
            .map { File(it) }.first { it.isDirectory }
        val h = File(src, "CompanionHost.kt").readText()
        assertTrue("restored when the padmap names the character (its whole key: CharKey)", h.contains("TalkKeep.character(ck)"))
        val c = File(src, "ChatView.kt").readText()
        assertTrue(c.contains("TalkKeep.resolve(TalkKeep.used, kinds().map { it.key }.toSet(), chans)"))
        assertTrue(c.contains("TalkKeep.went(TalkKeep.Used(t, if (t == \"WHISPER\") w else \"\", chan))"))
        assertFalse("no in-memory last chat left", c.contains("usedType"))
    }
}
