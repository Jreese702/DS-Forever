package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * THE CHAT, 2026-11-16, ON THE BOTTOM SCREEN (the user: "when typing its not TOTALLY obvious where I am sending the message- so I want
 * the added prefix to the message for where I am about to send the message just like in game, also changing the color of that message
 * accordingly. the messages filter should work different ... a menu where I can check a radio box for all of the message types that I
 * care about seeing ... visually right now the text in the message box clips a bit with the border art at the beginning of it. on the
 * quick message, after selecting the option I get surprised by it auto sending, I think we need to add a send button to that menu to the
 * right of the message bar"). The real ChatView, fed a chat document: the field's header and colour, the field's inset on both screens,
 * quick chat filling the bar (nothing reaches the add-on until Send), the checklist hiding what is unticked, per character.
 *
 * The checks run always; PNGs when CHAT_SHOTS_DIR is set (CHAT_ART_ROOT = an art root laid out like the app's: ui/kit, fonts, icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "369dpi")                  // the Thor's bottom panel: the field's sp text at the device's size
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ChatShotsTest {
    private val out: File? = System.getenv("CHAT_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    @Before fun clean() {
        ChatFilter.reset()
        System.getenv("CHAT_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
    }

    @After fun after() { ChatFilter.reset() }

    private fun lay(v: View, w: Int = W, h: Int = H) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun idle() { ShadowLooper.idleMainLooper() }

    /** the game is live: two documents ten seconds apart (InputGate's span), the newest now */
    private fun live() {
        val now = System.currentTimeMillis()
        GameInput.gate.note(now - 11_000L)
        GameInput.gate.note(now)
    }

    private fun line(i: Int, ch: String, who: String, text: String, cls: String = "", chan: String = "", idx: Int = 0,
                     style: String = ""): JSONObject = JSONObject().put("i", i).put("ch", ch).put("who", who).put("text", text)
        .put("cls", cls).put("chan", chan).put("idx", idx).put("style", style)

    private fun doc(): JSONObject {
        val lines = JSONArray()
        var i = 0
        fun add(o: JSONObject) { lines.put(o) }
        add(line(++i, "say", "Brannock", "anyone up for the Deadmines?", "WARRIOR"))
        add(line(++i, "channel", "Tarlo", "WTS [Copper Bar] x20, cheap", "ROGUE", "2. Trade - City", 2))
        add(line(++i, "xp", "", "Defias Thug dies, you gain 112 experience.", style = "plain"))
        add(line(++i, "rep", "", "Reputation with Stormwind increased by 25.", style = "plain"))
        add(line(++i, "loot", "", "You receive loot: [Linen Cloth]x2.", style = "plain"))
        add(line(++i, "party", "Mirelle", "need one more healer for DM", "PRIEST"))
        add(line(++i, "channel", "Oakhart", "LF1M Deadmines, need tank", "WARRIOR", "1. General - Elwynn Forest", 1))
        add(line(++i, "whisper", "Thrall", "are you still selling the bracers?", "SHAMAN"))
        add(line(++i, "guild", "Sennah", "grats on 20!", "MAGE"))
        add(line(++i, "skill", "", "Your skill in Swords has increased to 42.", style = "plain"))
        add(line(++i, "loot", "", "You receive loot: [Coarse Stone].", style = "plain"))
        add(line(++i, "info", "", "Defias Trapper slain: 4/10", style = "plain"))
        add(line(++i, "say", "Korvash", "on my way", "SHAMAN").put("me", true))
        val chans = JSONArray().put(JSONObject().put("i", 1).put("n", "General")).put(JSONObject().put("i", 2).put("n", "Trade"))
            .put(JSONObject().put("i", 4).put("n", "LookingForGroup"))
        return JSONObject().put("v", "chat37").put("s", 77L).put("n", i).put("lines", lines)
            .put("can", JSONObject().put("party", true).put("guild", true).put("raid", false).put("chans", chans))
    }

    private val taps = ArrayList<Pair<String, Int>>()

    private fun chat(): ChatView {
        val v = ChatView(app) { }
        v.tapper = { cells, _ -> taps.addAll(cells); true }
        call(v, "fonts")                                      // the game's type, as on the device (done when it is attached)
        v.update(doc())
        lay(v); idle(); lay(v)
        return v
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(v: ChatView, name: String): T =
        ChatView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(v) as T

    private fun call(v: ChatView, name: String, vararg args: Any) {
        val m = ChatView::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true
        m.invoke(v, *args)
    }

    /** a controller press, as the room's guest relays it */
    private var n = 0
    private fun press(v: ChatView, k: String) {
        v.onPad(JSONObject().put("type", "guestpad").put("id", "chat").put("k", k).put("n", ++n))
        idle(); lay(v)
    }

    /** the game's watch (twice a second on the device): the send state from the gate */
    private fun watch(v: ChatView) { (field<Runnable>(v, "watch")).run(); idle(); lay(v) }

    private fun input(v: ChatView): EditText = field(v, "input")

    private fun shot(v: View, name: String) {
        val d = out ?: return
        d.mkdirs()
        lay(v)
        val bm = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        val strip = Strip(app) { }.apply { active = Room.CHAT }
        for (pass in 0..1) {
            val c = Canvas(bm); c.drawColor(Theme.ink)
            v.draw(c)
            lay(strip, W, Strip.HEIGHT)
            c.save(); c.translate(0f, H.toFloat()); strip.draw(c); c.restore()
            if (pass == 0) { idle(); Thread.sleep(120); idle() }
        }
        File(d, "$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** the field's words and the colour of its first character */
    private fun fieldText(v: ChatView): Pair<String, Int> {
        val t = input(v).text
        val c = (t as? Spanned)?.getSpans(0, 1, ForegroundColorSpan::class.java)?.firstOrNull()?.foregroundColor ?: 0
        return t.toString() to c
    }

    // ---------------------------------------------------------------- the header and its colour

    @Test fun theFieldSaysWhereTheMessageGoesInItsColour() {
        live()
        val v = chat()
        watch(v)
        assertEquals("Say: Message" to Color.WHITE, fieldText(v))
        shot(v, "chat_log_say")
        // RB: the next type, Party (the header and the words in the party's colour)
        press(v, "PADRSHOULDER")
        call(v, "setTyped", "need one more for DM")
        val (t, c) = fieldText(v)
        assertEquals("Party: need one more for DM", t)
        assertEquals(Color.parseColor("#AAABFE"), c)
        val sp = input(v).text as Spanned
        assertTrue("the words in the type's colour too", sp.getSpans(t.length - 3, t.length, ForegroundColorSpan::class.java)
            .any { it.foregroundColor == Color.parseColor("#AAABFE") })
        shot(v, "chat_log_party")
        // a whisper: "Tell Name:" in the whisper's pink
        v.whisperTo("Thrall", keyboard = false)
        call(v, "setTyped", "yes, 2g each")
        assertEquals("Tell Thrall: yes, 2g each" to Color.parseColor("#FF80FF"), fieldText(v))
        shot(v, "chat_log_whisper")
        // a channel: its number and the name the game gives it ("2. Trade - City")
        ChatView::class.java.getDeclaredField("type").apply { isAccessible = true }.set(v, "CHANNEL:2")
        call(v, "applyType")
        call(v, "setTyped", "WTS Copper Bar x20")
        assertEquals("2. Trade - City: WTS Copper Bar x20", fieldText(v).first)
        shot(v, "chat_log_trade")
        // the input screen (the keyboard up): the field two lines high, the same header
        ChatView::class.java.getDeclaredField("type").apply { isAccessible = true }.set(v, "PARTY")
        call(v, "applyType")
        call(v, "setTyped", "need one more healer for the Deadmines, meet at the Moonbrook inn")
        press(v, "PAD1")                                      // A on the bar: the keyboard (the Settings default)
        assertTrue(v.ownsStart())
        assertTrue(fieldText(v).first.startsWith("Party: "))
        shot(v, "chat_keys_party")
    }

    // ---------------------------------------------------------------- the field clear of the box's edge

    @Test fun theWordsStartClearOfTheBoxEdgeOnBothScreens() {
        val v = chat()
        val f = input(v)
        // the game's own edit box starts its words 15 px into a 32 px box; the box's end piece is drawn square at the field's height
        assertTrue("log screen: ${f.paddingLeft} for ${f.height}", f.paddingLeft >= f.height * 15f / 32f - 1f)
        press(v, "PAD1")
        assertTrue("the input screen's field is taller", f.height > 120)
        assertTrue("input screen: ${f.paddingLeft} for ${f.height}", f.paddingLeft >= f.height * 15f / 32f - 1f)
        // the old inset, for the picture beside the new one (26 px whatever the height)
        if (out != null) {
            call(v, "setTyped", "need one more healer for the Deadmines")
            shot(v, "chat_keys_inset_new")
            f.setPadding(26, 0, f.paddingRight, 0); lay(v)
            shot(v, "chat_keys_inset_old")
        }
    }

    // ---------------------------------------------------------------- quick chat: into the bar, the bar's Send

    @Test fun aPhraseFillsTheBarAndOnlySendSendsIt() {
        live()
        val v = chat()
        watch(v)
        press(v, "PAD3")                                      // X: quick chat
        assertEquals(View.VISIBLE, field<View>(v, "barSend").visibility)
        assertTrue("Start is the room's while quick chat is up (its Send)", v.ownsStart())
        shot(v, "chat_quick_open")
        press(v, "PADDRIGHT")                                 // the second phrase: Thanks!
        taps.clear()
        press(v, "PAD1")
        assertEquals("Say: Thanks!", fieldText(v).first)
        assertTrue("nothing reached the add-on: no preset, no letter, no Send: $taps",
            taps.none { (r, c) -> (r == "chq" && (c in 0..10 || c == ChatStream.SEND || c >= 24)) || r == "chr" || r == "chs" })
        shot(v, "chat_quick_filled")
        // a second pick replaces the first
        press(v, "PADDDOWN")                                  // from Send back to the phrase
        press(v, "PADDLEFT")
        press(v, "PAD1")
        assertEquals("Say: Hello!", fieldText(v).first)
        assertTrue(taps.none { (r, c) -> r == "chq" && c in 0..10 })
        // A on Send (the cursor is there): the preset cell, after the add-on confirms the type
        press(v, "PAD1")
        v.onDoc(JSONObject().put("type", "chatq").put("op", "type").put("ok", true).put("ty", "SAY"))
        idle(); lay(v)
        assertTrue("the preset cell after Send: $taps", taps.contains("chq" to 0))
        assertEquals("the bar is empty and the input gone after the send", "Say: Message", fieldText(v).first)
        assertFalse(v.ownsStart())
        assertEquals(View.GONE, field<View>(v, "barSend").visibility)
    }

    @Test fun wordsOfTheirOwnStayAndAPhraseGoesAfterThem() {
        live()
        val v = chat()
        watch(v)
        press(v, "PAD3")
        call(v, "setTyped", "brb")
        press(v, "PAD1")                                      // the first phrase (Hello!) after the words
        assertEquals("Say: brb Hello!", fieldText(v).first)
        taps.clear()
        press(v, "PADFORWARD")                                // Start = Send: a typed message (the words, then the check)
        assertTrue("typed: the check cell, never a preset: $taps", taps.contains("chq" to ChatStream.BODY) && taps.none { (r, c) -> r == "chq" && c in 0..10 })
    }

    // ---------------------------------------------------------------- the checklist

    private fun shownCount(v: ChatView): Int = field<java.util.ArrayList<*>>(v, "shown").size

    @Test fun theChecklistShowsOnlyTheTickedTypes() {
        val v = chat()
        ChatFilter.character("Korvash-Realm")
        idle()
        val all = shownCount(v)
        assertEquals(13, all)
        press(v, "PADBACK")                                   // Select: the checklist
        shot(v, "chat_filter_open")
        // Item Loot (Combat's 5th) and Trade (Channels' 2nd) unticked by the controller
        press(v, "PADDRIGHT")                                 // Combat
        repeat(4) { press(v, "PADDDOWN") }
        press(v, "PAD1")
        assertFalse(ChatFilter.ticked("loot"))
        assertEquals(all - 2, shownCount(v))
        press(v, "PADDRIGHT"); press(v, "PADDRIGHT")          // Other, then Channels (the row kept where it can be: its last, LFG)
        press(v, "PADDUP")                                    // Channels' second: Trade
        press(v, "PAD1")
        assertFalse(ChatFilter.ticked("chan:Trade"))
        assertEquals(all - 3, shownCount(v))
        shot(v, "chat_filter_ticks")
        press(v, "PAD2")                                      // B: the list goes, the log shows only the ticked types
        shot(v, "chat_filter_log")
        // another character: its own ticks; back: the first one's again
        ChatFilter.character("Alt-Realm"); idle()
        assertEquals(all, shownCount(v))
        ChatFilter.character("Korvash-Realm"); idle()
        assertEquals(all - 3, shownCount(v))
        // All: everything ticked again
        press(v, "PADBACK")                                   // it opens on Trade again (where it closed)
        repeat(3) { press(v, "PADDUP") }                      // General, All, All
        press(v, "PAD1")
        assertEquals(all, shownCount(v))
    }

    @Test fun homesChatPaneFollowsTheChecklist() {
        if (out == null) return
        HomeSettings.init(app)
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = chat()
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        lay(home)
        home.onChat(doc())
        home.onFull(JSONObject().put("type", "full").put("map", JSONObject().put("zone", "Westfall").put("pvp", "contested"))
            .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true))), true)
        home.tick(); idle(); lay(home)
        HomeView::class.java.getDeclaredMethod("changed").apply { isAccessible = true }.invoke(home)
        idle(); lay(home)
        shot(home, "home_chat_all")
        ChatFilter.character("Korvash-Realm")
        ChatFilter.toggle("loot"); ChatFilter.toggle("xp"); ChatFilter.toggle("rep"); ChatFilter.toggle("skill")
        idle(); lay(home)
        shot(home, "home_chat_filtered")
    }
}
