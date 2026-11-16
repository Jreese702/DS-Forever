package com.jrees.wowcompanion

import com.jrees.wowcompanion.ChatPad.Act
import com.jrees.wowcompanion.ChatPad.Board
import com.jrees.wowcompanion.ChatPad.Focus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * THE CHAT, 2026-11-16 (the user's list): "when typing its not TOTALLY obvious where I am sending the message- so I want the added prefix
 * to the message for where I am about to send the message just like in game, also changing the color of that message accordingly. the
 * messages filter should work different, instead of filtering to one message type I want it to open a menu where I can check a radio
 * box for all of the message types that I care about seeing so that I can mix and match my preferences ... on the quick message, after
 * selecting the option I get surprised by it auto sending, I think we need to add a send button to that menu to the right of the message
 * bar." The rules off the device: [ChatFilter] (the checklist, per character), [ChatHeader] (the game's edit box header) and [ChatPad]
 * (the checklist's cursor, quick chat's fill and the bar's Send).
 */
class ChatChecklistTest {
    private val kept = HashMap<String, String>()
    private val store = object : ChatFilter.Store {
        override fun read(): String? = kept["all"]
        override fun write(s: String) { kept["all"] = s }
    }

    @Before fun clean() { ChatFilter.reset() }
    @After fun after() { ChatFilter.reset() }

    // ---------------------------------------------------------------- the checklist's types

    @Test fun everyLineKindHasItsBox() {
        assertEquals("say", ChatFilter.typeOf("say"))
        assertEquals("money", ChatFilter.typeOf("currency"))
        assertEquals("system", ChatFilter.typeOf("other"))
        assertEquals("a kind nobody named reads as System", "system", ChatFilter.typeOf("brand-new"))
        for (k in listOf("xp", "honor", "rep", "skill", "loot", "npc", "error", "info", "whisper", "officer", "emote"))
            assertEquals(k, ChatFilter.typeOf(k))
        // the game's Chat Settings groups, in its order
        assertEquals(listOf("Chat", "Combat", "Other"), ChatFilter.GROUPS.map { it.label })
        assertEquals(listOf("say", "emote", "yell", "whisper", "party", "raid", "guild", "officer"), ChatFilter.GROUPS[0].types.map { it.key })
        val keys = ChatFilter.GROUPS.flatMap { g -> g.types.map { it.key } }
        assertEquals("no box twice", keys.size, keys.toSet().size)
    }

    @Test fun channelsByNameNeverByNumber() {
        assertEquals("Trade", ChatFilter.chanBase("2. Trade - City"))
        assertEquals("Trade", ChatFilter.chanBase("Trade"))
        assertEquals("General", ChatFilter.chanBase("1. General - Elwynn Forest"))
        assertEquals("LookingForGroup", ChatFilter.chanBase("4. LookingForGroup"))
        assertEquals("chan:Trade", ChatFilter.keyOf("channel", "2. Trade - City"))
        assertEquals("the joined list's name is the same box", ChatFilter.chanKey("Trade"), ChatFilter.keyOf("channel", "5. Trade - City"))
        assertEquals("say", ChatFilter.keyOf("say", ""))
    }

    @Test fun ticksArePerCharacterAndKept() {
        ChatFilter.init(store)
        assertTrue("everything shows before the character is known", ChatFilter.shows("loot"))
        ChatFilter.character("Korvash-Realm")
        assertTrue(ChatFilter.shows("loot"))
        assertFalse(ChatFilter.toggle("loot"))
        assertFalse(ChatFilter.shows("loot"))
        assertFalse(ChatFilter.toggle("chan:Trade"))
        assertFalse(ChatFilter.shows("channel", "2. Trade - City"))
        assertTrue("another channel still shows", ChatFilter.shows("channel", "1. General - Elwynn Forest"))
        assertTrue("other types still show", ChatFilter.shows("say") && ChatFilter.shows("xp"))
        // another character: its own ticks (none yet)
        ChatFilter.character("Alt-Realm")
        assertTrue(ChatFilter.shows("loot"))
        assertTrue(ChatFilter.shows("channel", "2. Trade - City"))
        // the app starts again: the first character's ticks come back
        ChatFilter.reset()
        ChatFilter.init(store)
        ChatFilter.character("Korvash-Realm")
        assertFalse(ChatFilter.shows("loot"))
        assertFalse(ChatFilter.shows("channel", "2. Trade - City"))
        ChatFilter.character("Alt-Realm")
        assertTrue(ChatFilter.shows("loot"))
    }

    @Test fun nothingSavedBeforeTheCharacterIsKnown() {
        ChatFilter.init(store)
        ChatFilter.toggle("say")
        assertNull("no character, nothing kept", kept["all"])
    }

    @Test fun allTicksEverythingThenNothing() {
        ChatFilter.init(store)
        ChatFilter.character("Korvash-Realm")
        val keys = listOf("say", "party", "loot", "chan:Trade")
        ChatFilter.toggle("loot")
        assertFalse(ChatFilter.allTicked(keys))
        assertTrue("one unticked: All ticks every box", ChatFilter.toggleAll(keys))
        assertTrue(ChatFilter.allTicked(keys))
        assertFalse("all ticked: All unticks every box (then tick the few wanted)", ChatFilter.toggleAll(keys))
        assertTrue(keys.none { ChatFilter.ticked(it) })
        ChatFilter.toggle("party")
        assertTrue(ChatFilter.shows("party"))
        assertFalse(ChatFilter.shows("say"))
    }

    @Test fun listenersHearEveryChange() {
        var n = 0
        ChatFilter.onChange { n++ }
        ChatFilter.toggle("say")
        ChatFilter.character("Korvash-Realm")
        ChatFilter.character("Korvash-Realm")          // the same character again: nothing
        assertEquals(2, n)
    }

    /** the review (2026-11-16): Home's pane keeps its 40 SHOWN lines, so hidden experience / reputation lines push no whisper out */
    @Test fun homesTailKeepsItsShownLinesWhateverIsHidden() {
        ChatFilter.init(store)
        ChatFilter.character("Korvash-Realm")
        ChatFilter.toggle("xp")
        val tail = HomeChatTail(keep = 3, counts = { ChatFilter.shows(it.ch, it.chan) })
        val lines = org.json.JSONArray()
        var i = 0
        fun add(ch: String, text: String) { lines.put(org.json.JSONObject().put("i", ++i).put("ch", ch).put("text", text)) }
        add("say", "s0"); add("whisper", "w1"); add("say", "s1"); add("party", "p1")
        repeat(20) { add("xp", "xp$it") }
        tail.update(org.json.JSONObject().put("s", 7L).put("lines", lines))
        assertEquals("the newest 3 shown lines stay", listOf("w1", "s1", "p1"), tail.all.filter { ChatFilter.shows(it.ch) }.map { it.text })
        assertEquals("never more than keep x 4 lines in all: the oldest hidden ones went", 12, tail.all.size)
        assertEquals("xp11", tail.all.first { it.ch == "xp" }.text)
        // with nothing hidden it keeps its last [keep] lines, as before
        val plain = HomeChatTail(keep = 3)
        plain.update(org.json.JSONObject().put("s", 7L).put("lines", lines))
        assertEquals(listOf("xp17", "xp18", "xp19"), plain.all.map { it.text })
    }

    // ---------------------------------------------------------------- the game's edit box header

    @Test fun theHeaderIsTheGames() {
        assertEquals("Say:", ChatHeader.of("SAY"))
        assertEquals("Yell:", ChatHeader.of("YELL"))
        assertEquals("Party:", ChatHeader.of("PARTY"))
        assertEquals("Raid:", ChatHeader.of("RAID"))
        assertEquals("Guild:", ChatHeader.of("GUILD"))
        assertEquals("Tell Thrall:", ChatHeader.of("WHISPER", "Thrall"))
        assertEquals("Reply is a Tell to the last one who whispered", "Tell Jaina:", ChatHeader.of("REPLY", "Jaina"))
        assertEquals("2. Trade - City:", ChatHeader.of("CHANNEL:2", chanName = "Trade - City"))
        assertEquals("1. General:", ChatHeader.of("CHANNEL:1", chanName = "General"))
        // translated words, a name kept as it is; a channel never translated
        assertEquals("Decir:", ChatHeader.shown("SAY") { if (it == "Say:") "Decir:" else it })
        assertEquals("2. Trade - City:", ChatHeader.shown("CHANNEL:2", chanName = "Trade - City") { "X" })
    }

    // ---------------------------------------------------------------- the checklist's cursor

    private val cols = listOf(8, 6, 4, 3)
    private fun scene(menu: Boolean = true, board: Board = Board.NONE, lines: Int = 5) =
        ChatPad.Scene(lines = lines, board = board, menu = menu, menuCols = cols, quickTiles = 12)

    @Test fun selectOpensTheChecklistAndATicksWithoutClosingIt() {
        val pad = ChatPad()
        pad.show(scene(menu = false))
        assertEquals(listOf<Act>(Act.Menu(true)), pad.press("PADBACK", scene(menu = false)))
        assertEquals("the cursor on the first box (Say)", Focus.Menu(1), pad.focus)
        assertEquals(listOf<Act>(Act.Filter(1)), pad.press("PAD1", scene()))
        assertEquals("the list stays open: the cursor stays", Focus.Menu(1), pad.focus)
        pad.press("PADDDOWN", scene())
        assertEquals(listOf<Act>(Act.Filter(2)), pad.press("PAD1", scene()))
        // B closes it, the cursor back on the bar; Select again opens it where it was
        assertEquals(listOf<Act>(Act.Menu(false)), pad.press("PAD2", scene()))
        assertEquals(Focus.Field, pad.focus)
        pad.press("PADBACK", scene(menu = false))
        assertEquals(Focus.Menu(2), pad.focus)
    }

    @Test fun theChecklistsArrowsWalkItsColumns() {
        val pad = ChatPad()
        pad.show(scene(menu = false))
        pad.press("PADBACK", scene(menu = false))
        // up from a column's top: All; down from All: back into that column
        pad.press("PADDRIGHT", scene())
        assertEquals("right: Combat's first box", Focus.Menu(9), pad.focus)
        pad.press("PADDUP", scene())
        assertEquals("up from the top: All", Focus.Menu(0), pad.focus)
        pad.press("PADDLEFT", scene())
        assertEquals("All has no sides", Focus.Menu(0), pad.focus)
        pad.press("PADDDOWN", scene())
        assertEquals("down from All: the column it came from", Focus.Menu(9), pad.focus)
        // down to the column's end and no further; right keeps the row where the next column has it
        repeat(10) { pad.press("PADDDOWN", scene()) }
        assertEquals("Combat's last box", Focus.Menu(14), pad.focus)
        pad.press("PADDRIGHT", scene())
        assertEquals("Other has 4: its last", Focus.Menu(18), pad.focus)
        pad.press("PADDRIGHT", scene())
        assertEquals("Channels has 3: its last", Focus.Menu(21), pad.focus)
        pad.press("PADDRIGHT", scene())
        assertEquals("no column further", Focus.Menu(21), pad.focus)
        // an empty Channels column is stepped over
        val noChans = ChatPad.Scene(lines = 5, menu = true, menuCols = listOf(8, 6, 4, 0), quickTiles = 12)
        pad.press("PADDLEFT", noChans)        // settles into the shorter list first
        assertTrue((pad.focus as Focus.Menu).m in 1..18)
        assertEquals("A on All", listOf<Act>(Act.Filter(0)), run { pad.focus = Focus.Menu(0); pad.press("PAD1", scene()) })
    }

    @Test fun yAndSelectCloseTheChecklistToo() {
        val pad = ChatPad()
        pad.show(scene(menu = false))
        pad.press("PADBACK", scene(menu = false))
        assertEquals(listOf<Act>(Act.Menu(false)), pad.press("PAD4", scene()))
        assertEquals(Focus.Field, pad.focus)
        pad.press("PADBACK", scene(menu = false))
        assertEquals(listOf<Act>(Act.Menu(false)), pad.press("PADBACK", scene()))
    }

    // ---------------------------------------------------------------- quick chat: a phrase into the bar, the bar's Send

    @Test fun aPhraseGoesIntoTheBarAndTheCursorToSend() {
        val pad = ChatPad()
        val q = scene(menu = false, board = Board.QUICK)
        pad.show(q)
        pad.focus = Focus.Quick(1)
        assertEquals(listOf<Act>(Act.Quick(1)), pad.press("PAD1", q))
        assertEquals("the cursor on the bar's Send: A again sends", Focus.Send, pad.focus)
        assertEquals(listOf<Act>(Act.Send), pad.press("PAD1", q))
        // down from Send: back to the phrase picked
        pad.press("PADDDOWN", q)
        assertEquals(Focus.Quick(1), pad.focus)
        // the Keyboard tile still opens the keyboard (the cursor in the field)
        pad.focus = Focus.Quick(11)
        assertEquals(listOf<Act>(Act.Quick(11)), pad.press("PAD1", q))
        assertEquals(Focus.Quick(11), pad.focus)
    }

    @Test fun sendIsReachedFromTheFieldAndTheTilesAndStart() {
        val pad = ChatPad()
        val q = scene(menu = false, board = Board.QUICK)
        pad.show(q)
        pad.focus = Focus.Field
        pad.press("PADDRIGHT", q)
        assertEquals("right of the field: Send", Focus.Send, pad.focus)
        pad.press("PADDLEFT", q)
        assertEquals(Focus.Field, pad.focus)
        pad.focus = Focus.Quick(2)
        pad.press("PADDUP", q)
        assertEquals("the top row's right tile goes up to Send", Focus.Send, pad.focus)
        pad.focus = Focus.Quick(0)
        pad.press("PADDUP", q)
        assertEquals("the others go up to the field", Focus.Field, pad.focus)
        assertEquals("Start sends with quick chat up", listOf<Act>(Act.Send), pad.press("PADFORWARD", q))
        assertEquals("Start is no room action on the log screen", emptyList<Act>(), pad.press("PADFORWARD", scene(menu = false)))
        // with no quick chat, Send is not there: the cursor settles on the field
        pad.focus = Focus.Send
        pad.settle(scene(menu = false, board = Board.KEYS))
        assertEquals(Focus.Field, pad.focus)
        // on the log screen left / right on the bar still change the type
        assertEquals(listOf<Act>(Act.Type(1)), pad.press("PADDRIGHT", scene(menu = false)))
    }

    @Test fun aOnTheFieldWithQuickChatUpOpensTheKeyboard() {
        val pad = ChatPad()
        val q = scene(menu = false, board = Board.QUICK)
        pad.show(q)
        pad.focus = Focus.Field
        assertEquals(listOf<Act>(Act.Board(Board.KEYS)), pad.press("PAD1", q))
        // B with quick chat up: the input goes (the log again), as before
        pad.focus = Focus.Send
        assertEquals(listOf<Act>(Act.Board(Board.NONE)), pad.press("PAD2", q))
        assertEquals(Focus.Field, pad.focus)
    }
}
