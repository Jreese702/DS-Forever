package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * VOICE TO TEXT, THE OFF-DEVICE RENDERS (the user's list 2026-11-16: "A opens the keyboard, X opens our own voice prompt", "intuitive and
 * slick"; the design's shots: the ask, listening, result and error states in Chat, Bags and Mail). The real rooms with a stand-in
 * recognizer: the Chat room (the bar's A / X glyphs, then the voice page on its board in every state, the Party prefix), the Bags room
 * (the strip's Search with its glyphs, the voice page over the grid as it dims), Send Mail (Subject's glyphs, the voice page). Runs only
 * when VOICE_SHOTS_DIR is set; VOICE_ART_ROOT = an art root laid out like the app's. The state checks run always.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceShotsTest {
    private val out: File? = System.getenv("VOICE_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    private fun art() {
        System.getenv("VOICE_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
        Settings.init(app)
        Settings.voiceTyping = Voice.Mode.DEVICE
        VoiceUi.micForTest(true)
        Voice.inFight = { false }
        Tr.init(app)
    }

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun settle(ms: Long = 300) { for (i in 0 until (ms / 16).toInt()) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }

    class Listen : ChatKeyboard.VoiceListen {
        var starts = 0
        override fun start(online: Boolean, lang: String, hints: List<String>, sink: VoiceEngine.Sink): Boolean { starts++; return true }
        override fun stop() {}
        override fun cancel() {}
        override fun getPack(lang: String) = true
    }

    private fun rig(kb: ChatKeyboard, perm: () -> Voice.Perm, avail: Voice.Avail = Voice.Avail.DEVICE): Listen {
        val l = Listen()
        kb.engine = l
        kb.voiceProbe = { perm() to avail }
        return l
    }

    /** the room on the bar's screen: the room above, the bar under it ([active] lit) */
    private fun shot(name: String, v: View, active: Room, extra: ((Canvas) -> Unit)? = null) {
        val d = out ?: return
        d.mkdirs()
        val bm = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        val strip = Strip(app) { }.apply { this.active = active }
        for (pass in 0..1) {
            val c = Canvas(bm); c.drawColor(Theme.ink)
            v.draw(c)
            extra?.invoke(c)
            lay(strip, W, Strip.HEIGHT)
            c.save(); c.translate(0f, H.toFloat()); strip.draw(c); c.restore()
            if (pass == 0) { ShadowLooper.idleMainLooper(); Thread.sleep(120); ShadowLooper.idleMainLooper() }
        }
        File(d, "$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(o: Any, name: String): T = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as T

    // ---------------------------------------------------------------- Chat

    private var n = 0
    private fun pad(chat: ChatView, k: String) = chat.onPad(JSONObject().put("type", "guestpad").put("id", "chat").put("k", k).put("n", ++n))

    private fun chatRoom(perm: () -> Voice.Perm, avail: Voice.Avail = Voice.Avail.DEVICE): Triple<ChatView, ChatKeyboard, Listen> {
        // attached to a window (an activity): the bar's glyphs draw only on a shown field, and the board slides as on the device
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val chat = ChatView(act) { }
        act.setContentView(chat, android.view.ViewGroup.LayoutParams(W, H))
        chat.tapper = { _, _ -> true }                           // the add-on's cells "land": the copy's gold fill has something to show
        lay(chat, W, H)
        val lines = JSONArray()
        for ((ch, who, text) in listOf(Triple("say", "Grukk", "anyone up for Burning Blade Coven?"), Triple("guild", "Zalaya", "grats on 6!"),
                Triple("party", "Mogra", "I'll tank, pull on 3"), Triple("system", "", "You have learned a new spell: Earth Shock."),
                Triple("party", "Torrik", "ready when you are"))) lines.put(JSONObject().put("ch", ch).put("who", who).put("text", text).put("i", lines.length() + 1))
        chat.update(JSONObject().put("s", 7).put("lines", lines).put("can", JSONObject().put("party", true).put("guild", true)))
        settle(); lay(chat, W, H)
        val kb: ChatKeyboard = field(chat, "keyboard")
        val l = rig(kb, perm, avail)
        return Triple(chat, kb, l)
    }

    @Test fun chatShots() {
        art()
        // the bar under the cursor: A with the keyboard, X with the microphone
        run {
            val (chat, _, _) = chatRoom({ Voice.Perm.GRANTED })
            pad(chat, "PAD4"); settle(); lay(chat, W, H)
            shot("chat_0_bar_glyphs", chat, Room.CHAT)
        }
        // the first X: Android's question is asked by the page (A = Allow, B = Not now), and in a fight Allow waits
        run {
            val (chat, kb, _) = chatRoom({ Voice.Perm.ASKABLE })
            pad(chat, "PADRSHOULDER")                            // the Party type: its prefix on the voice page
            pad(chat, "PAD3"); settle(); lay(chat, W, H)
            assertEquals(Voice.State.ASK, kb.voiceSheet?.state)
            shot("chat_1_ask", chat, Room.CHAT)
            Voice.inFight = { true }
            shot("chat_1b_ask_in_a_fight", chat, Room.CHAT)
            Voice.inFight = { false }
        }
        // listening: the level's ripples, the settled words white, the rest dim; then the result, the cursor on Send
        run {
            val (chat, kb, l) = chatRoom({ Voice.Perm.GRANTED })
            pad(chat, "PADRSHOULDER")
            pad(chat, "PAD3"); settle(); lay(chat, W, H)
            assertEquals(1, l.starts)
            val s = kb.voiceSink()
            for (db in listOf(2f, 6f, 9f, 8f)) s.rms(db)
            s.partial("on my way"); s.partial("on my way to the inn"); s.partial("on my way to the inn now give")
            shot("chat_2_listening", chat, Room.CHAT)
            s.final("on my way to the inn now give me two minutes")
            ShadowSystemClock.advanceBy(Duration.ofMillis(Voice.ACCEPT_MS + 50))
            assertEquals(Voice.State.RESULT, kb.voiceSheet?.state)
            shot("chat_3_result", chat, Room.CHAT)
        }
        // the problems
        run {
            val (chat, kb, _) = chatRoom({ Voice.Perm.GRANTED })
            pad(chat, "PAD3"); settle(); lay(chat, W, H)
            ShadowSystemClock.advanceBy(Duration.ofMillis(6000))
            kb.voiceSink().error(Voice.E_SPEECH_TIMEOUT)
            assertEquals(Voice.Problem.NOT_HEARD, kb.voiceSheet?.problem)
            shot("chat_4_didnt_hear", chat, Room.CHAT)
            kb.voiceSink().let { kb.press("PAD3"); it.error(Voice.E_LANG_UNAVAILABLE) }
            shot("chat_5_pack_needed", chat, Room.CHAT)
        }
        run {
            val (chat, kb, _) = chatRoom({ Voice.Perm.BLOCKED })
            pad(chat, "PAD3"); settle(); lay(chat, W, H)
            assertEquals(Voice.State.BLOCKED, kb.voiceSheet?.state)
            shot("chat_6_mic_off_settings_path", chat, Room.CHAT)
        }
        run {
            val (chat, kb, _) = chatRoom({ Voice.Perm.GRANTED }, Voice.Avail.ONLINE_ONLY)
            pad(chat, "PAD3"); settle(); lay(chat, W, H)
            assertEquals(Voice.State.OFFER_ONLINE, kb.voiceSheet?.state)
            shot("chat_7_offer_online", chat, Room.CHAT)
        }
        // the same page in Spanish
        run {
            Tr.load("es", File("../companion/app/src/main/assets/companion/i18n/es.json").readText())
            val c0 = Tr::class.java.getDeclaredField("choice"); c0.isAccessible = true; c0.set(Tr, "es")
            val (chat, kb, _) = chatRoom({ Voice.Perm.GRANTED })
            pad(chat, "PAD3"); settle(); lay(chat, W, H)
            kb.voiceSink().partial("hola a todos"); kb.voiceSink().partial("hola a todos que")
            shot("chat_8_listening_spanish", chat, Room.CHAT)
            c0.set(Tr, "auto")
        }
    }

    // ---------------------------------------------------------------- Bags

    private fun bagsDoc(ctl: JSONObject): JSONObject {
        val items = JSONArray()
        val names = listOf("Linen Cloth" to 132889, "Light Leather" to 134252, "Minor Healing Potion" to 134830, "Hearthstone" to 134414,
            "Rough Stone" to 135232, "Copper Ore" to 134566, "Silver Ore" to 134579, "Tigerseye" to 134120, "Small Egg" to 132832,
            "Ice Cold Milk" to 132815, "Coarse Thread" to 132891, "Linen Bandage" to 133685)
        for ((k, nm) in names.withIndex()) items.put(JSONObject().put("bag", 0).put("slot", k + 1).put("id", 1000 + k).put("icon", nm.second)
            .put("count", if (k % 3 == 0) 5 else 1).put("quality", 1).put("name", nm.first))
        return JSONObject().put("free", 4).put("total", 16).put("money", 102345)
            .put("bags", JSONArray().put(JSONObject().put("bag", 0).put("slots", 16)))
            .put("items", items).put("equipped", JSONArray()).put("ctl", ctl)
            .put("pad", JSONObject().put("rows", JSONObject().put("bmvq", 27).put("bag0", 30)))
    }

    @Test fun bagsShots() {
        art()
        // attached to a window (an activity), so the strip's cursor finds the search box where it is drawn (getLocationOnScreen)
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val v = BagsView(act)
        act.setContentView(v, android.view.ViewGroup.LayoutParams(W, H))
        val kb: ChatKeyboard = field(v, "kb")
        rig(kb, { Voice.Perm.GRANTED })
        val ctl = JSONObject().put("on", true).put("sid", 5).put("mode", "grid").put("cur", JSONObject().put("bag", 0).put("slot", 1))
            .put("bar", JSONObject().put("k", "search")).put("act", JSONObject().put("k", "search").put("seq", 1))
        v.update(bagsDoc(JSONObject(ctl.toString())), true)                       // the baseline: the old request is not acted on
        lay(v, W, H); settle(); lay(v, W, H)
        shot("bags_0_search_glyphs", v, Room.BAGS)
        // X on Search (Bags.lua bags-voice1): the act "voice", the room standing still (mode search)
        v.update(bagsDoc(JSONObject(ctl.toString()).put("mode", "search").put("act", JSONObject().put("k", "voice").put("seq", 2))), true)
        lay(v, W, H); settle(); lay(v, W, H)
        assertTrue(kb.voiceShown)
        val s = kb.voiceSink()
        for (db in listOf(3f, 7f, 5f)) s.rms(db)
        s.partial("copper"); s.partial("copper ore")
        shot("bags_1_listening_grid_dims", v, Room.BAGS)
        s.final("copper ore")
        ShadowSystemClock.advanceBy(Duration.ofMillis(Voice.ACCEPT_MS + 50))
        lay(v, W, H)
        shot("bags_2_result", v, Room.BAGS)
        kb.press("PAD3"); ShadowSystemClock.advanceBy(Duration.ofMillis(6000)); s.error(Voice.E_NETWORK)
        shot("bags_3_offline", v, Room.BAGS)
    }

    // ---------------------------------------------------------------- Send Mail

    private fun mailDoc(cur: String, kbd: JSONObject? = null): JSONObject {
        val act = JSONObject().put("edit_to", 1).put("edit_subject", 2).put("edit_body", 3).put("send", 4).put("clear", 5).put("pick", 6)
            .put("tab_inbox", 7).put("tab_send", 8).put("close", 9)
        val send = JSONObject().put("to", "Mogra").put("subject", "").put("body", "Here are the bars you asked for.").put("price", 30)
            .put("max", 12).put("items", JSONArray())
        val d = JSONObject().put("type", "interact").put("iv", "i161").put("mode", true)
            .put("mail", JSONObject().put("tab", "send").put("money", 102345).put("send", send).put("act", act))
            .put("nav", JSONObject().put("on", true).put("cur", cur))
        if (kbd != null) d.put("kbd", kbd)
        return d
    }

    @Test fun mailShots() {
        art()
        val holder = FrameLayout(app)
        val v = InteractView(app).apply { dryRun = true }
        val kb = ChatKeyboard(app, swap = false, voice = true).apply { visibility = View.GONE }
        v.keyboard = kb
        holder.addView(v, FrameLayout.LayoutParams(W, H))
        holder.addView(kb, FrameLayout.LayoutParams(W, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))
        rig(kb, { Voice.Perm.GRANTED })
        // the overlay shows itself only for a live game document (its refresh); here it is drawn as it would show, the keyboard over it
        val mail = object : View(app) {
            override fun draw(c: Canvas) {
                v.visibility = View.VISIBLE
                v.draw(c)
                if (kb.visibility == View.VISIBLE) { c.save(); c.translate(0f, kb.top.toFloat()); kb.draw(c); c.restore() }
            }
        }
        v.update(mailDoc("mailedit subject"), true)
        v.visibility = View.VISIBLE
        lay(holder, W, H); settle(); lay(holder, W, H)
        shot("mail_0_subject_glyphs", mail, Room.HOME)
        // X on Subject (Interact i161): the field opens with v = 1, the keyboard on its voice page
        v.update(mailDoc("mailedit subject", JSONObject().put("name", "mailsubject").put("text", "").put("max", 64).put("v", 1).put("off", 10)), true)
        lay(holder, W, H); settle(); lay(holder, W, H)
        assertTrue(kb.voiceShown)
        val s = kb.voiceSink()
        for (db in listOf(4f, 8f)) s.rms(db)
        s.partial("bars for"); s.partial("bars for the forge")
        lay(holder, W, H)
        shot("mail_1_listening", mail, Room.HOME)
        s.final("bars for the forge")
        ShadowSystemClock.advanceBy(Duration.ofMillis(Voice.ACCEPT_MS + 50))
        lay(holder, W, H)
        shot("mail_2_result", mail, Room.HOME)
        kb.press("PAD3"); ShadowSystemClock.advanceBy(Duration.ofMillis(6000)); s.error(Voice.E_TOO_MANY)
        lay(holder, W, H)
        shot("mail_3_voice_busy", mail, Room.HOME)
        // the letter under the cursor: its glyphs at the paper's top right
        kb.press("PAD2")
        v.update(mailDoc("mailedit body"), true)
        lay(holder, W, H); settle(); lay(holder, W, H)
        v.visibility = View.VISIBLE
        lay(holder, W, H)
        shot("mail_4_letter_glyphs", mail, Room.HOME)
    }
}
