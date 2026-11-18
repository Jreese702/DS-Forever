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
            kb.onlineNameProbe = { "Speech Services" }               // the online recognizer's app, as Android names it
            pad(chat, "PAD3"); settle(); lay(chat, W, H)
            assertEquals(Voice.State.OFFER_ONLINE, kb.voiceSheet?.state)
            shot("chat_7_offer_online", chat, Room.CHAT)
        }
        // Online picked: while it listens, the app that hears the voice is named at the foot (the design's privacy line)
        run {
            Settings.voiceTyping = Voice.Mode.ONLINE
            val (chat, kb, l) = chatRoom({ Voice.Perm.GRANTED }, Voice.Avail.ONLINE)
            kb.onlineNameProbe = { "Speech Services" }
            pad(chat, "PAD3"); settle(); lay(chat, W, H)
            assertEquals(1, l.starts)
            kb.voiceSink().rms(7f); kb.voiceSink().partial("need a healer"); kb.voiceSink().partial("need a healer at")
            shot("chat_9_online_listening", chat, Room.CHAT)
            Settings.voiceTyping = Voice.Mode.DEVICE
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

    // ---------------------------------------------------------------- offline voice's model list (no model on disk)

    /**
     * the user, 2026-11-16: "The voice menu should have all of the options not just the 60 mb": the sheet's no-model page lists the four
     * (Add-ons' order and words), the cursor on the old pick; D-pad moves, A / a finger asks that one's download, a running one shows its
     * percentage on its row
     */
    @Test fun needModelShots() {
        art()
        // no real network: a download asked here fails at once (the rows' states come from the probe)
        VoiceModels.start(File(System.getProperty("java.io.tmpdir"), "voice-sheet-models-test").apply { mkdirs() },
            VoiceModels.Net { _, _ -> throw java.io.IOException("HTTP 404") }) { }
        val states = HashMap<VoiceModels.Model, VoiceModels.State>()
        val (chat, kb, _) = chatRoom({ Voice.Perm.GRANTED }, Voice.Avail.NEED_MODEL)
        kb.modelStateProbe = { states[it] ?: VoiceModels.State.None }
        pad(chat, "PAD3"); settle(); lay(chat, W, H)
        val s = kb.voiceSheet!!
        assertEquals(Voice.Problem.NEED_MODEL, s.problem)
        assertEquals(VoiceModels.Model.EN_SMALL, s.model)
        draw(chat)
        val rows: List<Pair<VoiceModels.Model, android.graphics.RectF>> = field(kb, "modelRows")
        assertEquals(VoiceModels.Model.values().toList(), rows.map { it.first })
        shot("voice_need_model_0_list", chat, Room.CHAT)
        pad(chat, "PADDDOWN"); pad(chat, "PADDDOWN"); settle()
        assertEquals(VoiceModels.Model.MULTI_SMALL, s.model)
        shot("voice_need_model_1_cursor_moved", chat, Room.CHAT)
        // one downloading (from Add-ons, say): its row says so, the others stay selectable
        states[VoiceModels.Model.EN] = VoiceModels.State.Downloading(42)
        pad(chat, "PADDUP"); settle()
        assertEquals(VoiceModels.Model.EN, s.model)
        shot("voice_need_model_2_one_downloading", chat, Room.CHAT)
        // a finger on the last row: that one is picked and its download asked
        draw(chat)
        val last = field<List<Pair<VoiceModels.Model, android.graphics.RectF>>>(kb, "modelRows").last().second
        val up = android.view.MotionEvent.obtain(0L, 0L, android.view.MotionEvent.ACTION_UP, last.centerX(), last.centerY(), 0)
        assertTrue(kb.voiceTouchAt(up))
        assertEquals(VoiceModels.Model.MULTI, s.model)
        assertTrue(VoiceModels.Model.MULTI in s.busy)
        states[VoiceModels.Model.MULTI] = VoiceModels.State.Downloading(3)
        shot("voice_need_model_3_touch_picked", chat, Room.CHAT)
        kb.stopVoice()
        // the same page in Spanish: the cursor on the small multilingual one
        Tr.load("es", File("../companion/app/src/main/assets/companion/i18n/es.json").readText())
        val c0 = Tr::class.java.getDeclaredField("choice"); c0.isAccessible = true; c0.set(Tr, "es")
        val (chatEs, kbEs, _) = chatRoom({ Voice.Perm.GRANTED }, Voice.Avail.NEED_MODEL)
        kbEs.modelStateProbe = { VoiceModels.State.None }
        pad(chatEs, "PAD3"); settle(); lay(chatEs, W, H)
        assertEquals(VoiceModels.Model.MULTI_SMALL, kbEs.voiceSheet?.model)
        shot("voice_need_model_4_spanish", chatEs, Room.CHAT)
        c0.set(Tr, "auto")
        kbEs.stopVoice()
    }

    private fun draw(v: View) { val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); v.draw(Canvas(bm)) }

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

    // ---------------------------------------------------------------- radial names (the Controller room's Name box)

    private fun radialCtl(revn: Int, rev: String?, rrow: Int = 1, rbox: String? = null) =
        PadMapModel.parseCtl(JSONObject("""{"type":"padtabctl","v":"p40","c":{"on":true,"view":"radial","focus":"rset","tcur":4,
            "pos":1,"rpage":2,"rad":[403,331,324],"rslots":[1,2,4],"rplate":2,"rrow":$rrow,"revn":$revn${rev?.let { ",\"rev\":\"$it\"" } ?: ""}
            ${rbox?.let { ",\"rbox\":\"$it\"" } ?: ""}}}"""))!!

    @Test fun radialNameShots() {
        art()
        RadialMeta.init(app)
        RadialMeta.who = "Korvash-Realm"
        RadialMeta.setName(2, "Fire")
        val v = ControllerView(app, onPadCells = { true })
        v.onPadMap(PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m105","m":{"page":1,"pages":3,"sets":[]}}"""))!!)
        v.onPadTabCtl(radialCtl(5, "mode", rrow = 2))
        v.onPadTabCtl(radialCtl(7, "key:PAD2", rrow = 2))
        v.onPadTabCtl(radialCtl(8, "name", rrow = 1, rbox = "rname"))
        val page: Any = field(v, "page")
        val kb = field<Lazy<ChatKeyboard>>(page, "kb\$delegate").value
        rig(kb, { Voice.Perm.GRANTED })
        lay(v, W, H); settle(); lay(v, W, H)
        shot("radial_0_name_mic", v, Room.CONTROLLER)
        // X on the Name keys (the add-on hands the press over: key:PAD3): the voice page, the name replaced by what is said
        v.onPadTabCtl(radialCtl(9, "key:PAD3", rrow = 1, rbox = "rname"))
        assertTrue(kb.voiceShown)
        val s = kb.voiceSink()
        for (db in listOf(3f, 8f)) s.rms(db)
        s.partial("frost"); s.partial("frost nova")
        lay(v, W, H)
        shot("radial_1_name_listening", v, Room.CONTROLLER)
        s.final("frost nova")
        ShadowSystemClock.advanceBy(Duration.ofMillis(Voice.ACCEPT_MS + 50))
        lay(v, W, H)
        shot("radial_2_name_result", v, Room.CONTROLLER)
    }

    // ---------------------------------------------------------------- the problem report's words

    @Test fun reportShots() {
        art()
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val v = ReportPage(act)
        act.setContentView(v, android.view.ViewGroup.LayoutParams(W, H))
        val kb: ChatKeyboard = field(v, "kb")
        rig(kb, { Voice.Perm.GRANTED })
        ReportFlow.init(act)
        ReportFlow.description = "The map froze"
        v.open()
        lay(v, W, H); settle(600); lay(v, W, H)
        shot("report_0_words_mic", v, Room.SETTINGS)
        // X on the words: the board comes up on its voice page; the words go after what is written
        v.padKey("PAD3")
        lay(v, W, H); settle(600); lay(v, W, H)
        assertTrue(kb.voiceShown)
        val s = kb.voiceSink()
        for (db in listOf(4f, 9f)) s.rms(db)
        s.partial("after the loading"); s.partial("after the loading screen on")
        lay(v, W, H)
        shot("report_1_listening", v, Room.SETTINGS)
        s.final("after the loading screen on the Thor")
        ShadowSystemClock.advanceBy(Duration.ofMillis(Voice.ACCEPT_MS + 50))
        lay(v, W, H)
        shot("report_2_result", v, Room.SETTINGS)
        ReportFlow.description = ""
    }

    // ---------------------------------------------------------------- Send Mail

    private fun mailDoc(cur: String, kbd: JSONObject? = null): JSONObject {
        val act = JSONObject().put("edit_to", 1).put("edit_subject", 2).put("edit_body", 3).put("send", 4).put("clear", 5).put("pick", 6)
            .put("tab_inbox", 7).put("tab_send", 8).put("close", 9)
        val send = JSONObject().put("to", "Mogra").put("subject", "").put("body", "Here are the bars you asked for.").put("price", 30)
            .put("max", 12).put("items", JSONArray())
        // a live document (the clock LogTail stamps with): #536 keeps the field's keyboard only while the window really shows
        val gn = InteractView::class.java.getDeclaredMethod("gameNow").apply { isAccessible = true }
        val d = JSONObject().put("type", "interact").put("iv", "i161").put("mode", true).put("_gt", gn.invoke(InteractView(app)) as Long)
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
                // clipped to the board's own box, as its parent clips it on the device (review: unclipped, the board's ink filled the
                // whole picture and the mail window above the voice page never showed)
                if (kb.visibility == View.VISIBLE) {
                    c.save(); c.translate(0f, kb.top.toFloat()); c.clipRect(0f, 0f, kb.width.toFloat(), kb.height.toFloat()); kb.draw(c); c.restore()
                }
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
