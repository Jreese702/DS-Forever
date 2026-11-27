package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue 622 (1.0.41: "the chat history persists when switching between characters"); the user, 2026-11-27: the player was looking
 * for the chat to CLEAR when logging out. A logout (the game's "bye"), a switch to another character or a client restart clears the
 * Chat room's and Home's chat lines; nothing is kept per character and nothing comes back on a later login. The app's own /reload
 * keeps them. Chat.lua's document names no character, but each login is a new session (`s`): the new character's first lines, which
 * can come before its padmap, are kept.
 */
class ChatClearsOnLogoutTest {
    private fun doc(s: Long, vararg lines: Pair<Int, String>): JSONObject = JSONObject().put("s", s).put("lines", JSONArray().apply {
        for ((i, t) in lines) put(JSONObject().put("i", i).put("ch", "say").put("who", "Someone").put("text", t))
    })

    private fun texts(t: HomeChatTail) = t.all.map { it.text }

    @Test fun theLogoutClearsTheChat() {
        val t = HomeChatTail()
        t.character("Alpha-Realm")
        t.update(doc(100, 1 to "a1", 2 to "a2"))
        t.loggedOut()                                                // the game's "bye"
        assertEquals(emptyList<String>(), texts(t))
        // a late copy of the old session's ring (written after the bye) never brings its lines back
        t.update(doc(100, 1 to "a1", 2 to "a2", 3 to "a3"))
        assertEquals(emptyList<String>(), texts(t))
    }

    @Test fun aSwitchClearsTheOldCharactersChat() {
        val t = HomeChatTail()
        t.character("Alpha-Realm")
        t.update(doc(100, 1 to "a1"))
        t.character("Alpha-Realm")                                   // a later padmap: a1's session is Alpha's
        // no bye seen (missed): the new session's lines, then its padmap names another character
        t.update(doc(200, 1 to "b1"))
        assertTrue(t.character("Bravo-Realm"))
        assertEquals(listOf("b1"), texts(t))
    }

    @Test fun nothingComesBackOnALaterLogin() {
        val t = HomeChatTail()
        t.character("Alpha-Realm")
        t.update(doc(100, 1 to "a1"))
        t.loggedOut()
        t.update(doc(200, 1 to "b1"))
        t.character("Bravo-Realm")
        t.loggedOut()
        t.update(doc(300, 1 to "a2"))
        t.character("Alpha-Realm")                                   // back to the first character: only its new login's lines
        assertEquals(listOf("a2"), texts(t))
        // a logout and a login on the SAME character clears too
        t.loggedOut()
        t.update(doc(400, 1 to "a3"))
        t.character("Alpha-Realm")
        assertEquals(listOf("a3"), texts(t))
    }

    @Test fun aReloadOfTheSameCharacterKeepsItsChat() {
        val t = HomeChatTail()
        t.character("Alpha-Realm")
        t.update(doc(100, 1 to "a1"))
        t.update(doc(101, 1 to "a2"))                                // /reload: a new session, the same character, no clear
        assertFalse(t.character("Alpha-Realm"))
        assertEquals(listOf("a1", "a2"), texts(t))
    }

    @Test fun theFirstLinesAfterTheLoginAreKept() {
        val t = HomeChatTail()
        t.character("Alpha-Realm")
        t.update(doc(100, 1 to "a1"))
        t.loggedOut()
        t.update(doc(200, 1 to "b1", 2 to "b2"))                    // the new login's chat BEFORE its padmap
        assertEquals(listOf("b1", "b2"), texts(t))
        t.character("Bravo-Realm")
        assertEquals(listOf("b1", "b2"), texts(t))
        t.update(doc(200, 1 to "b1", 2 to "b2", 3 to "b3"))         // the ring again, and one more
        assertEquals(listOf("b1", "b2", "b3"), texts(t))
    }

    @Test fun theFirstCharacterNamedKeepsWhatCameBeforeIt() {
        val t = HomeChatTail()
        t.update(doc(100, 1 to "a1"))
        assertFalse(t.character("Alpha-Realm"))
        assertEquals(listOf("a1"), texts(t))
    }

    @Test fun theHostClearsOnLogoutSwitchAndRestartButNotItsOwnReload() {
        val src = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion"), File("companion/app/src/main/java/com/jrees/wowcompanion"))
            .first { it.isDirectory }
        val host = File(src, "CompanionHost.kt").readText()
        val bye = host.substring(host.indexOf("\"bye\" -> {"), host.indexOf("\"chat\" -> ", host.indexOf("\"bye\" -> {")))
        assertTrue(bye, bye.contains("if (reloadAt == 0L) { chatView.loggedOut(); homeView.chatLoggedOut() }"))
        assertTrue(host.contains("if (s == LogTail.RESTARTED) { chatView.loggedOut(); homeView.chatLoggedOut() }"))
        assertTrue(host.contains("if (named) { chatView.character(ck); homeView.chatCharacter(ck) }"))
        // nothing is kept per character any more
        assertFalse(File(src, "ChatByCharacter.kt").exists())
        val cv = File(src, "ChatView.kt").readText()
        assertTrue(cv.contains("ChatSinceLogin<Line>()"))
        assertTrue(!cv.contains("history.add("))                     // every line goes in with its session (book.add)
        // a clear drops the selection, the unread dots and the whisper target
        val clear = cv.substring(cv.indexOf("private fun cleared()"), cv.indexOf("\n    }\n", cv.indexOf("private fun cleared()")))
        for (s in listOf("select(null)", "unread.clear()", "replyTo = \"\"", "whisperName = \"\"")) assertTrue(s, clear.contains(s))
        assertTrue(File(src, "HomeView.kt").readText().contains("fun chatLoggedOut() { if (chat.loggedOut()) chatPane.refresh() }"))
    }
}
