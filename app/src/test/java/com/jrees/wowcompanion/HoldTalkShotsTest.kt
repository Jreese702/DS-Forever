package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
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
 * HOLD TO TALK ON THE DS TOOLBAR'S CHAT (the user, 2026-11-16: "Add an option to the text chat ds toolbar option for a quick chat option
 * for holding that button for quick voice to text to last used text channel (usage and chat indicated by a mic symbol on the toolbar and
 * the color of the chat is the mic"), on the real views: the last used chat is the one the composer's send went to (Party here) and the
 * hold's send goes there through the same add-on path, keeping the composer's own type and draft; Settings' two rows show only under the
 * toolbar, "On release" only while the switch is on; Home's toolbar draws the microphone in Party's colour and the show-then-A line.
 * PNGs when HOLDTALK_SHOTS_DIR is set (HOLDTALK_ART_ROOT = an art root laid out like the app's, E:/wow-build/runtime-art/r1/files).
 *   HOLDTALK_SHOTS_DIR=<dir> HOLDTALK_ART_ROOT=<root> gradlew :app:testModernDebugUnitTest --tests "*HoldTalkShotsTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HoldTalkShotsTest {
    private val out: File? = System.getenv("HOLDTALK_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val PARTY = Color.parseColor("#AAABFE")
    private val WHO = "Korvash-Stormrage"

    @Before fun art() {
        System.getenv("HOLDTALK_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
        HomeSettings.init(app)
    }

    @After fun clean() { HomeSettings.setHoldTalk(WHO, false); HomeSettings.setTalkSendNow(WHO, false); TalkKeep.reset() }

    private fun idle() = ShadowLooper.idleMainLooper()

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun live() {
        val now = System.currentTimeMillis()
        GameInput.gate.note(now - 11_000L)
        GameInput.gate.note(now)
    }

    // ---------------------------------------------------------------- the chat: the last used one, and the hold's send

    private val taps = ArrayList<Pair<String, Int>>()

    private fun chat(): ChatView {
        val v = ChatView(app) { }
        v.tapper = { cells, _ -> taps.addAll(cells); true }
        val lines = JSONArray().put(JSONObject().put("i", 1).put("ch", "party").put("who", "Mirelle").put("text", "need one more healer")
            .put("cls", "PRIEST").put("chan", "").put("idx", 0).put("style", ""))
        v.update(JSONObject().put("v", "chat37").put("s", 77L).put("n", 1).put("lines", lines)
            .put("can", JSONObject().put("party", true).put("guild", true).put("raid", false).put("chans", JSONArray())))
        lay(v, 1240, 1080 - Strip.HEIGHT); idle()
        return v
    }

    private fun call(v: ChatView, name: String, vararg args: Any): Any? {
        val m = ChatView::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true
        return m.invoke(v, *args)
    }

    private fun set(v: ChatView, name: String, value: Any) =
        ChatView::class.java.getDeclaredField(name).apply { isAccessible = true }.set(v, value)

    private fun <T> get(v: ChatView, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return ChatView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(v) as T
    }

    /** the add-on's answer to the check (its buffer as the mirror has it) and to the send */
    private fun answerCheck(v: ChatView, body: String, ty: String, ok: Boolean = true, why: String? = null) {
        val m = JSONObject().put("type", "chatt").put("op", "body").put("ok", ok).put("len", body.length).put("h", ChatStream.hash(body))
            .put("tlen", 0).put("th", 0).put("ty", ty)
        if (why != null) m.put("why", why)
        v.onDoc(m); idle()
    }
    /** the check's answer with a spelt target (a whisper's person, a channel's number) */
    private fun answerCheckTo(v: ChatView, body: String, ty: String, target: String) {
        // the spelt target's own BODY tap is answered first; the check's answer is the last (until the send's tap goes)
        var n = 0
        while (("chq" to ChatStream.SEND) !in taps && n++ < 4) answerOnce(v, body, ty, target)
    }
    private fun answerOnce(v: ChatView, body: String, ty: String, target: String) {
        v.onDoc(JSONObject().put("type", "chatt").put("op", "body").put("ok", true).put("len", body.length).put("h", ChatStream.hash(body))
            .put("tlen", target.length).put("th", ChatStream.hash(target)).put("ty", ty))
        idle()
    }
    private fun answerSend(v: ChatView, ty: String, ok: Boolean = true) {
        v.onDoc(JSONObject().put("type", "chatt").put("op", "send").put("ok", ok).put("len", 0).put("h", 0).put("tlen", 0).put("th", 0).put("ty", ty))
        idle()
    }

    @Test fun theHoldsSendGoesToTheLastUsedChatAndKeepsTheComposer() {
        live()
        val v = chat()
        assertEquals("Say until a send went", "SAY", v.talkChannel().first)
        var changed = 0
        v.onTalkChannel = { changed++ }
        // the composer sends to Party: Party becomes the last used chat
        set(v, "type", "PARTY"); call(v, "applyType")
        call(v, "setTyped", "omw")
        call(v, "doSend")
        answerCheck(v, "omw", "PARTY"); answerSend(v, "PARTY")
        assertEquals("", get<String>(v, "typed"))                       // the composer's own send: its bar empties, as before
        val (key, prefix, colour) = v.talkChannel()
        assertEquals("PARTY", key); assertEquals("Party: ", prefix); assertEquals(PARTY, colour)
        assertEquals(1, changed)
        // the composer goes back to Say with a draft; the hold's words still go to Party
        set(v, "type", "SAY"); call(v, "applyType")
        call(v, "setTyped", "draft words")
        taps.clear()
        var result: Pair<Boolean, String>? = null
        assertTrue(v.talkSend("on my way") { ok, why -> result = ok to why })
        assertTrue("the party type tapped (chq 17)", ("chq" to ChatStream.TYPES.getValue("PARTY")) in taps)
        answerCheck(v, "on my way", "PARTY"); answerSend(v, "PARTY")
        assertEquals(true to "", result)
        assertEquals("the composer keeps its type", "SAY", get<String>(v, "type"))
        assertEquals("... and its draft", "draft words", get<String>(v, "typed"))
        assertEquals("PARTY", v.talkChannel().first)
        // refused by the add-on (its rules): the reason, the composer untouched
        result = null
        assertTrue(v.talkSend("lol") { ok, why -> result = ok to why })
        answerCheck(v, "lol", "PARTY", ok = false, why = "you are not in a party")
        assertEquals(false to "You are not in a party", result)
        assertEquals("draft words", get<String>(v, "typed"))
        // a slash never runs a command
        result = null
        taps.clear()
        assertTrue(v.talkSend("/dance") { ok, why -> result = ok to why })
        assertFalse("no command cell", taps.any { it.first == "chcmd" })
    }

    @Test fun nothingGoesWhileTheGameIsNotThere() {
        GameInput.gate.restart()                                     // no game documents: not live
        val v = chat()
        var result: Pair<Boolean, String>? = null
        taps.clear()
        assertFalse(v.talkSend("hello") { ok, why -> result = ok to why })
        assertEquals(false to "Waiting for the game", result)
        assertFalse(taps.any { it == ("chq" to ChatStream.SEND) })
    }

    // ---------------------------------------------------------------- Settings: the rows under the toolbar

    private fun section(toolbar: Boolean): HomeSettingsSection {
        val s = HomeSettingsSection(app)
        s.talkWho = { WHO }
        s.dpadState = { PadMapModel.Dpad(toolbar, emptyMap(), emptyList(), emptyList(), toolbar, 1, 2, "Korvash", "Stormrage") }
        return s
    }

    @Test fun theRowsShowUnderTheToolbarOnReleaseOnlyWhileOn() {
        assertFalse(HomeSettings.holdTalk(WHO)); assertFalse(HomeSettings.talkSendNow(WHO))       // off by default, Show then A
        assertFalse(HomeSettingsSection.TALK_ROW in section(false).rowLabels())
        val s = section(true)
        assertEquals(listOf("DS WoW toolbar", HomeSettingsSection.TALK_ROW), s.rowLabels().takeLast(2))
        HomeSettings.setHoldTalk(WHO, true)
        assertEquals(listOf("DS WoW toolbar", HomeSettingsSection.TALK_ROW, HomeSettingsSection.RELEASE_ROW), s.rowLabels().takeLast(3))
        assertFalse("per character", HomeSettings.holdTalk("Other-Realm"))
        shotSection(s, "settings_rows")
    }

    private fun shotSection(s: HomeSettingsSection, name: String) {
        val w = 1240
        s.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val h = s.measuredHeight
        s.layout(0, 0, w, h)
        val d = out ?: return
        d.mkdirs()
        // the last four rows: the toolbar and the two under it (the section's top rows are the page's others)
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        s.draw(Canvas(bm))
        val top = (h - 4 * 122).coerceAtLeast(0)
        val cut = Bitmap.createBitmap(bm, 0, top, w, h - top)
        File(d, "$name.png").outputStream().use { cut.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---------------------------------------------------------------- Home's toolbar: the microphone and the line

    private fun home(): Pair<HomeView, CastView> {
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        lay(home, 1240, 1080 - Strip.HEIGHT)
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        cast.roomBar = mapOf(1 to "chat", 2 to "radial", 3 to "map", 4 to "home")
        cast.roomBarOn = true
        home.tick()
        // Home settled at rest, as the Settings pictures settle it (ClockShotsTest): the diamonds
        val look = HomeView.Companion.Look(HomeRest.DIAMONDS, false, false, false)
        HomeView::class.java.methods.firstOrNull { it.name.startsWith("previewSettle") }?.invoke(home, look)
        idle()
        lay(home, 1240, 1080 - Strip.HEIGHT)
        return home to cast
    }

    private fun shot(home: HomeView, name: String): Bitmap {
        val bm = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        val strip = Strip(app) { }.apply { active = Room.HOME }
        for (pass in 0..1) {
            val c = Canvas(bm); c.drawColor(Theme.ink)
            home.draw(c)
            lay(strip, 1240, Strip.HEIGHT)
            c.save(); c.translate(0f, (1080 - Strip.HEIGHT).toFloat()); strip.draw(c); c.restore()
            if (pass == 0) { idle(); Thread.sleep(120); idle() }
        }
        out?.let { d -> d.mkdirs(); File(d, "$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return bm
    }

    /** how many pixels of [colour] (within a small tolerance) the picture has */
    private fun count(bm: Bitmap, colour: Int): Int {
        var n = 0
        for (y in 0 until bm.height step 2) for (x in 0 until bm.width step 2) {
            val p = bm.getPixel(x, y)
            if (Math.abs(Color.red(p) - Color.red(colour)) < 14 && Math.abs(Color.green(p) - Color.green(colour)) < 14 &&
                Math.abs(Color.blue(p) - Color.blue(colour)) < 14) n++
        }
        return n
    }

    @Test fun theToolbarsMicrophoneInPartysColourAndTheShowThenALine() {
        val (home, cast) = home()
        val plain = shot(home, "toolbar_off")
        val before = count(plain, PARTY)
        cast.talk = CastView.TalkFace(CastView.TalkFace.Mode.READY, PARTY)
        val ready = shot(home, "toolbar_mic_party")
        assertTrue("the small microphone in Party's colour", count(ready, PARTY) > before + 3)
        cast.talk = CastView.TalkFace(CastView.TalkFace.Mode.LISTEN, PARTY, 0.7f)
        cast.talkLine = CastView.TalkLine("Party: ", PARTY, "")
        val listen = shot(home, "toolbar_mic_party_listening")
        assertTrue("the big microphone while held", count(listen, PARTY) > count(ready, PARTY))
        cast.talk = CastView.TalkFace(CastView.TalkFace.Mode.READY, PARTY)
        cast.talkLine = CastView.TalkLine("Party: ", PARTY, "need one more healer for DM, meet at the inn", ask = true)
        shot(home, "line_show_then_a")
        assertFalse(cast.talkLineBox.isEmpty)
        assertTrue("on the page", cast.talkLineBox.left >= 0f && cast.talkLineBox.right <= home.width && cast.talkLineBox.bottom <= home.height)
        val castBottom = (cast.parent as View).top + cast.bottom
        assertTrue("under the D-pad's band, not over it (${cast.talkLineBox} / $castBottom)", cast.talkLineBox.top >= castBottom - 40f)
        cast.talkLine = CastView.TalkLine("Party: ", PARTY, "omw")
        shot(home, "line_sent")
        cast.talk = CastView.TalkFace(CastView.TalkFace.Mode.NO_VOICE, PARTY)
        cast.talkLine = CastView.TalkLine("", PARTY, HoldTalk.NO_VOICE, note = true)
        shot(home, "toolbar_mic_nomodel")
        cast.talk = null; cast.talkLine = null
        assertEquals("the switch off: the Chat picture alone", before, count(shot(home, "toolbar_off_again"), PARTY))
        assertTrue(cast.talkLineBox.isEmpty)
    }

    @Test fun thePreviewPictures() {
        val d = out ?: return
        d.mkdirs()
        for ((id, states) in listOf(HomeSettingsSection.TALK_ROW to listOf("on" to SettingsPreview.State(holdTalk = true), "off" to SettingsPreview.State()),
                HomeSettingsSection.RELEASE_ROW to listOf("show" to SettingsPreview.State(holdTalk = true),
                    "sendnow" to SettingsPreview.State(holdTalk = true, talkSendNow = true))))
            for ((tag, s) in states) for (t in listOf(800L, 2500L)) {
                val b = Bitmap.createBitmap(620, 520, Bitmap.Config.ARGB_8888)
                val c = Canvas(b); c.drawColor(Theme.ink)
                SettingsPreview.draw(c, android.graphics.RectF(10f, 10f, 610f, 510f), id, s, t,
                    SettingsPreview.Glyph { cc, a, _, x, y, size -> UiKit.pad(cc, "rev_$a", x, y, size) })
                File(d, "preview_${id.replace(' ', '_').lowercase()}_${tag}_$t.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
    }

    // ---------------------------------------------------------------- the pop-up's chat tabs (the user, 2026-11-17: "pop up tabs to press
    // L or R to change what chat that its for. lets give it its own pop up UI around it.")

    private val GUILD = Color.parseColor("#40FF40")

    /** a chat in a group and a guild, General and Trade joined, Mirelle whispered */
    private fun chatWithChannels(): ChatView {
        val v = chat()
        v.update(JSONObject().put("v", "chat37").put("s", 77L).put("n", 1).put("lines", JSONArray())
            .put("can", JSONObject().put("party", true).put("guild", true).put("raid", false).put("chans", JSONArray()
                .put(JSONObject().put("i", 1).put("n", "General - Elwynn Forest")).put(JSONObject().put("i", 2).put("n", "Trade - City")))))
        set(v, "whisperName", "Mirelle")
        idle()
        return v
    }

    @Test fun theTabsAreTheChatRoomsListAndASendOnGuildAfterRGoesThereAndIsKept() {
        live()
        TalkKeep.reset()
        val v = chatWithChannels()
        val tabs = v.talkTabs()
        assertEquals(listOf("SAY", "PARTY", "GUILD", "YELL", "CHANNEL:1", "CHANNEL:2", "WHISPER"), tabs.map { it.key })
        assertEquals(listOf("Say", "Party", "Guild", "Yell", "General", "Trade", "→ Mirelle"), tabs.map { it.label })
        assertFalse("never an invented Officer tab", tabs.any { it.key == "OFFICER" })
        // the last used chat: Party (the composer's send)
        set(v, "type", "PARTY"); call(v, "applyType"); call(v, "setTyped", "omw"); call(v, "doSend")
        answerCheck(v, "omw", "PARTY"); answerSend(v, "PARTY")
        val t = TalkTabs(); t.seed(v.talkTabs(), v.talkStart())
        assertEquals("starts on the last used chat", "PARTY", t.now()!!.key)
        assertTrue(t.step(1))                                                   // R
        assertEquals("GUILD", t.now()!!.key)
        assertEquals(Triple("GUILD", "Guild: ", GUILD), v.talkChannel("GUILD", ""))
        taps.clear()
        var result: Pair<Boolean, String>? = null
        assertTrue(v.talkSend("pulling at 5", t.now()!!.let { it.key to it.whisper }) { ok, why -> result = ok to why })
        assertTrue("the guild type tapped (chq ${ChatStream.TYPES.getValue("GUILD")})", ("chq" to ChatStream.TYPES.getValue("GUILD")) in taps)
        assertFalse("not the party's", ("chq" to ChatStream.TYPES.getValue("PARTY")) in taps)
        answerCheck(v, "pulling at 5", "GUILD"); answerSend(v, "GUILD")
        assertEquals(true to "", result)
        assertEquals("kept as the last used chat", "GUILD", TalkKeep.used.type)
        assertEquals("GUILD", v.talkStart())
        // a whisper tab: its person goes with it
        taps.clear(); result = null
        assertTrue(v.talkSend("inv pls", "WHISPER" to "Mirelle") { ok, why -> result = ok to why })
        answerCheckTo(v, "inv pls", "WHISPER", "Mirelle"); answerSend(v, "WHISPER")
        assertEquals(TalkKeep.Used("WHISPER", "Mirelle", ""), TalkKeep.used)
        // a channel: kept by its name
        taps.clear()
        assertTrue(v.talkSend("wts linen", "CHANNEL:2" to "") { _, _ -> })
        answerCheckTo(v, "wts linen", "CHANNEL", "2"); answerSend(v, "CHANNEL")
        assertEquals(TalkKeep.Used("CHANNEL:2", "", "Trade - City"), TalkKeep.used)
        assertEquals("the composer's own type untouched", "PARTY", get<String>(v, "type"))
    }

    /** the tab gaps' ease done before the picture (the clock stands still under the paused looper) */
    private fun settle() { org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(UiKit.TAB_GAP_MS + 100L)) }

    private fun panel(v: ChatView, t: TalkTabs, mode: CastView.TalkFace.Mode, words: String, ask: Boolean = false, level: Float = 0.7f): CastView.TalkPanel {
        val n = t.now()!!
        val (_, prefix, colour) = v.talkChannel(n.key, n.whisper)
        return CastView.TalkPanel(t.tabs, t.sel, CastView.TalkFace(mode, colour, level), prefix, words, ask)
    }

    @Test fun thePopUpHoldingOnPartyThenGuildTheWaitingWordsAndAWhisper() {
        live()
        TalkKeep.reset()
        val v = chatWithChannels()
        val (home, cast) = home()
        val t = TalkTabs(); t.seed(v.talkTabs(), "PARTY")
        cast.talk = CastView.TalkFace(CastView.TalkFace.Mode.LISTEN, PARTY, 0.7f)
        cast.talkPanel = panel(v, t, CastView.TalkFace.Mode.LISTEN, "")
        val onParty = shot(home, "ptt_party_hold")
        assertFalse(cast.talkPanelBox.isEmpty)
        assertTrue("on the page", cast.talkPanelBox.left >= 0f && cast.talkPanelBox.right <= home.width && cast.talkPanelBox.bottom <= home.height)
        val castBottom = (cast.parent as View).top + cast.bottom
        assertTrue("under the toolbar's D-pad, never over the held Left (${cast.talkPanelBox} / $castBottom)", cast.talkPanelBox.top >= castBottom - 40f)
        assertEquals(t.tabs.size, cast.talkTabBoxes.size)
        assertTrue("the big microphone in Party's colour", count(onParty, PARTY) > 200)
        // R: Guild; the microphone and the words take its colour
        assertTrue(t.step(1))
        cast.talk = CastView.TalkFace(CastView.TalkFace.Mode.LISTEN, GUILD, 0.7f)
        cast.talkPanel = panel(v, t, CastView.TalkFace.Mode.LISTEN, "")
        shot(home, "ptt_after_r_guild"); settle()
        val onGuild = shot(home, "ptt_after_r_guild")
        assertTrue("Guild's colour now", count(onGuild, GUILD) > 200)
        assertTrue("Party's only on its tab", count(onGuild, PARTY) < count(onParty, PARTY) / 2)
        // the words wait (A sends, B drops)
        cast.talk = CastView.TalkFace(CastView.TalkFace.Mode.READY, GUILD)
        cast.talkPanel = panel(v, t, CastView.TalkFace.Mode.WORK, "pulling the first pack at five, everyone ready?", ask = true)
        shot(home, "ptt_words_wait")
        // a finger on the whisper's tab picks it
        var picked: String? = null
        cast.onTalkTab = { picked = it }
        val (wKey, wBox) = cast.talkTabBoxes.last()
        assertTrue(cast.talkPanelTouch(wBox.centerX(), wBox.centerY()))
        assertEquals("WHISPER", wKey); assertEquals("WHISPER", picked)
        assertTrue(t.pick("WHISPER"))
        val tell = v.talkChannel("WHISPER", "Mirelle")
        assertEquals("Tell Mirelle: ", tell.second)
        cast.talk = CastView.TalkFace(CastView.TalkFace.Mode.READY, tell.third)
        cast.talkPanel = panel(v, t, CastView.TalkFace.Mode.WORK, "inv please", ask = true)
        shot(home, "ptt_whisper_tab"); settle()
        shot(home, "ptt_whisper_tab")
        assertFalse("a touch off the pop-up is not its", cast.talkPanelTouch(5f, 5f))
        cast.talkPanel = null
        assertTrue(cast.talkPanelBox.isEmpty)
        assertTrue(cast.talkTabBoxes.isEmpty())
        cast.talk = null
    }

}
