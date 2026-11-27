package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue 622 (1.0.41): after a logout and a login on another character, the bottom screen still showed the first character's chat.
 * Each character keeps its own chat now (the per-character rule): a switch shows the new character's lines only, and switching back
 * shows the first one's again. Chat.lua's document names no character, but each login is a new session (`s`), so the lines of a
 * session that began after the old character was last confirmed are the new character's, even when they came before its padmap.
 */
class ChatPerCharacterTest {
    private fun doc(s: Long, vararg lines: Pair<Int, String>): JSONObject = JSONObject().put("s", s).put("lines", JSONArray().apply {
        for ((i, t) in lines) put(JSONObject().put("i", i).put("ch", "say").put("who", "Someone").put("text", t))
    })

    private fun texts(t: HomeChatTail) = t.all.map { it.text }

    @Test fun aSwitchShowsOnlyTheNewCharactersChat() {
        val t = HomeChatTail()
        t.character("Alpha-Realm")
        t.update(doc(100, 1 to "a1", 2 to "a2"))
        t.character("Alpha-Realm")                                   // a later padmap of the same character: nothing changes
        assertEquals(listOf("a1", "a2"), texts(t))
        // the logout and the new login: the new session's first lines come BEFORE its padmap names the character
        t.update(doc(200, 1 to "b1"))
        t.character("Bravo-Realm")
        assertEquals(listOf("b1"), texts(t))
        t.update(doc(200, 1 to "b1", 2 to "b2"))                    // the ring again, and one more
        assertEquals(listOf("b1", "b2"), texts(t))
        // back to the first character: its own chat comes back, with its new session's lines
        t.update(doc(300, 1 to "a3"))
        t.character("Alpha-Realm")
        assertEquals(listOf("a1", "a2", "a3"), texts(t))
    }

    @Test fun theLogoutSeparatesThemWhenThePadmapCameFirst() {
        val t = HomeChatTail()
        t.character("Alpha-Realm")                                   // Alpha's padmap at its load, before any of its chat
        t.update(doc(100, 1 to "a1", 2 to "a2"))
        t.loggedOut()                                                // the game's "bye": everything before it is Alpha's
        t.update(doc(200, 1 to "b1"))
        t.character("Bravo-Realm")
        assertEquals(listOf("b1"), texts(t))
        t.character("Alpha-Realm")
        assertEquals(listOf("a1", "a2"), texts(t))
    }

    @Test fun theStartScreensOldLogBeforeALogoutIsNobodys() {
        val t = HomeChatTail()
        t.update(doc(100, 1 to "old1"))                              // the last session's log, no padmap read in its last part
        t.loggedOut()                                                // its logout (or the client's restart: the log truncated)
        t.update(doc(200, 1 to "new1"))
        t.character("Bravo-Realm")
        assertEquals(listOf("new1"), texts(t))
    }

    @Test fun aReloadOfTheSameCharacterKeepsItsChat() {
        val t = HomeChatTail()
        t.character("Alpha-Realm")
        t.update(doc(100, 1 to "a1"))
        t.update(doc(101, 1 to "a2"))                                // /reload: a new session, the same character
        t.character("Alpha-Realm")
        assertEquals(listOf("a1", "a2"), texts(t))
    }

    @Test fun anUnknownKeyShowsNeitherCharacter() {
        val t = HomeChatTail()
        t.character("Alpha-Realm")
        t.update(doc(100, 1 to "a1"))
        t.loggedOut()
        t.update(doc(200, 1 to "b1"))
        t.character(null)                                            // named, its realm not known yet: not Alpha's chat
        assertEquals(listOf("b1"), texts(t))
        t.update(doc(200, 2 to "b2"))
        t.character("Bravo-Realm")                                   // its key comes: what came meanwhile is Bravo's
        assertEquals(listOf("b1", "b2"), texts(t))
        t.character("Alpha-Realm")
        assertEquals(listOf("a1"), texts(t))
    }

    @Test fun beforeAnyCharacterTheLinesAreTheFirstCharactersOwn() {
        val t = HomeChatTail()
        t.update(doc(100, 1 to "a1"))
        t.character("Alpha-Realm")
        assertEquals(listOf("a1"), texts(t))
    }

    @Test fun theBookKeepsEachLinesSession() {
        val b = ChatByCharacter<String>()
        b.add(100, "a1"); b.add(100, "a2")
        assertEquals(listOf("a1", "a2"), b.lines)
        assertEquals("a1", b.removeAt(0))
        assertTrue(b.character("A"))
        b.add(200, "b1")
        assertTrue(b.character("B"))
        assertEquals(listOf("b1"), b.lines)
        assertTrue(b.character("A"))
        assertEquals(listOf("a2"), b.lines)
    }

    @Test fun theChatRoomAndHomeFollowTheCharacter() {
        val src = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion"), File("companion/app/src/main/java/com/jrees/wowcompanion"))
            .first { it.isDirectory }
        val host = File(src, "CompanionHost.kt").readText()
        assertTrue(host.contains("if (named) { chatView.character(ck); homeView.chatCharacter(ck) }"))
        assertTrue(host.contains("chatView.loggedOut(); homeView.chatLoggedOut()   // issue 622: the next session's"))
        assertTrue(host.contains("if (s == LogTail.RESTARTED) { chatView.loggedOut(); homeView.chatLoggedOut() }"))
        val cv = File(src, "ChatView.kt").readText()
        assertTrue(cv.contains("fun character(key: String?)"))
        assertTrue(cv.contains("ChatByCharacter<Line>()"))
        assertTrue(!cv.contains("history.add("))                     // every line goes in with its session (book.add)
        assertTrue(File(src, "HomeView.kt").readText().contains("fun chatCharacter(key: String?)"))
    }
}
