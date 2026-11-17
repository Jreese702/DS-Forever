package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * chat40, THE NOTICES OVER HOME'S CHAT, OFF-DEVICE (the user, 2026-11-16: "the Bnet friends coming online pop ups should appear over my
 * chat similar to the world refreesh prompt. both of those should be getting hidden from the top screen. the world refresh prompt should
 * have blue text and the ! notice part should be a button to trigger the refresh."). The real HomeView and its chat pane:
 *  - NoticeModel: a Battle.net document never replaces or ends the refresh notice; the BN notices live as the game's toasts (4 s, then a
 *    1.5 s fade), newest first, two at most, the same id once;
 *  - the refresh notice's "!" is a button: a touch on it is its press (never the Chat room), a touch elsewhere on the pane still opens
 *    the Chat room; with no refresh on there is no button;
 *  - the BN notices stand under the refresh notice, so the log starts lower.
 * With NOTICE_SHOTS_DIR set Home is drawn as home_notice_<case>.png (1240 x 1080, the bar under it) and its chat pane alone as
 * chat_notice_<case>.png; NOTICE_ART_ROOT = an art root laid out like the app's. The checks run always.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NoticeShotsTest {
    private val out: File? = System.getenv("NOTICE_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    private fun art() {
        System.getenv("NOTICE_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
    }

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun settle() { for (i in 0 until 30) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }

    private val rooms = ArrayList<Room>()

    private fun home(): HomeView {
        art()
        HomeSettings.init(app)
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { rooms += it }
        home.borrow()
        lay(home, W, H)
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        home.onFull(JSONObject().put("type", "full").put("map", JSONObject().put("zone", "Elwynn Forest").put("pvp", "contested"))
            .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true))), true)
        home.onChat(JSONObject().put("v", "chat40").put("s", 1).put("n", 3)
            .put("lines", JSONArray()
                .put(JSONObject().put("i", 1).put("ch", "say").put("who", "Brannoc").put("text", "Anyone heading to Goldshire?"))
                .put(JSONObject().put("i", 2).put("ch", "guild").put("who", "Thalia").put("text", "Kobold candles are in the mine."))
                .put(JSONObject().put("i", 3).put("ch", "system").put("text", "You are now Away: AFK"))))
        home.tick()
        ShadowLooper.idleMainLooper()
        lay(home, W, H)
        HomeView::class.java.getDeclaredMethod("changed").apply { isAccessible = true }.invoke(home)
        settle()
        lay(home, W, H)
        return home
    }

    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)
    private fun pane(home: HomeView): View = field(home, "chatPane") as View
    private fun bang(home: HomeView): RectF = field(pane(home), "bang") as RectF

    private fun drawPane(home: HomeView): Bitmap {
        val p = pane(home)
        val bm = Bitmap.createBitmap(p.width.coerceAtLeast(1), p.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bm); c.drawColor(Theme.ink); p.draw(c)
        return bm
    }

    private fun shot(home: HomeView, name: String) {
        val pb = drawPane(home)                         // also lays the button's box
        val d = out ?: return
        d.mkdirs()
        val bm = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        val strip = Strip(app) { }
        for (pass in 0..1) {
            val c = Canvas(bm); c.drawColor(Theme.ink)
            home.draw(c)
            lay(strip, W, Strip.HEIGHT)
            c.save(); c.translate(0f, H.toFloat()); strip.draw(c); c.restore()
            if (pass == 0) { ShadowLooper.idleMainLooper(); Thread.sleep(150); ShadowLooper.idleMainLooper() }
        }
        File(d, "home_notice_$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(d, "chat_notice_$name.png").outputStream().use { drawPane(home).compress(Bitmap.CompressFormat.PNG, 100, it) }
        pb.recycle()
    }

    private fun shard(left: Int, id: String = "1-1") = JSONObject().put("type", "notice").put("v", "chat40").put("kind", "shard")
        .put("id", id).put("left", left).put("urgent", left < 60).put("text", "")
    private fun bn(id: String, who: String?, on: Boolean) = JSONObject().put("type", "notice").put("v", "chat40").put("kind", "bnet")
        .put("id", id).put("on", on).apply { if (who != null) put("who", who) }

    private fun touch(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0))
        ShadowLooper.idleMainLooper()
    }

    // ---------------------------------------------------------------- the model

    @Test fun aBattleNetNoticeNeverTouchesTheRefreshNotice() {
        val n = NoticeModel.apply(null, shard(240), 0L)
        assertNotNull(n)
        assertEquals(n, NoticeModel.apply(n, bn("b1", "Thrall", true), 10L))
        assertEquals(n, NoticeModel.apply(n, JSONObject().put("type", "notice").put("kind", "shard").put("op", "go").put("ok", true), 10L))
        // a BN document with ended never ends it either
        assertEquals(n, NoticeModel.apply(n, bn("b1", "Thrall", true).put("ended", true), 10L))
        assertEquals(true, NoticeModel.goAnswer(JSONObject().put("type", "notice").put("kind", "shard").put("op", "go").put("ok", true)))
        assertNull(NoticeModel.goAnswer(shard(240)))
    }

    @Test fun battleNetNoticesLiveAsTheGamesToasts() {
        var l = NoticeModel.applyBnet(emptyList(), bn("b1", "Thrall", true), 0L)
        l = NoticeModel.applyBnet(l, bn("b1", "Thrall", true), 100L)                // the same id once
        assertEquals(1, l.size)
        l = NoticeModel.applyBnet(l, bn("b2", null, false), 1_000L)
        l = NoticeModel.applyBnet(l, bn("b3", "Quietone|Kx", true), 2_000L)          // a |K name is never shown
        assertEquals(listOf("b3", "b2"), l.map { it.id })                            // newest first, two at most
        assertNull(l[0].who); assertNull(l[1].who)
        assertEquals(1f, l[0].alpha(2_000L + 3_999L), 0f)
        assertEquals(0.5f, l[0].alpha(2_000L + 4_750L), 0.01f)
        assertEquals(0f, l[0].alpha(2_000L + 5_500L), 0f)
        assertEquals(listOf("b3"), NoticeModel.liveBnet(l, 1_000L + 5_600L).map { it.id })
        assertEquals(l, NoticeModel.applyBnet(l, shard(30), 3_000L))
    }

    // ---------------------------------------------------------------- the chat pane

    @Test fun theBangIsAButtonAndTheRestOpensTheChatRoom() {
        val home = home()
        drawPane(home)
        assertTrue("no refresh: no button", bang(home).isEmpty)
        home.onNotice(shard(240)); drawPane(home)
        val b = RectF(bang(home))
        assertFalse("the refresh notice has its button", b.isEmpty)
        assertTrue("the button is a finger's size", b.width() >= 48f && b.height() >= 48f)
        rooms.clear()
        touch(pane(home), b.centerX(), b.centerY())
        assertTrue("a touch on the ! is its press, not the Chat room: $rooms", rooms.isEmpty())
        touch(pane(home), pane(home).width / 2f, pane(home).height - 40f)
        assertEquals("a touch on the log still opens the Chat room", listOf(Room.CHAT), rooms.toList())
        // the refresh ended: its button goes, a touch there is the Chat room's again
        home.onNotice(JSONObject().put("type", "notice").put("kind", "shard").put("id", "1-1").put("ended", true)); drawPane(home)
        assertTrue(bang(home).isEmpty)
        rooms.clear(); touch(pane(home), b.centerX(), b.centerY())
        assertEquals(listOf(Room.CHAT), rooms.toList())
    }

    @Test fun theShots() {
        val home = home()
        home.onNotice(shard(240)); shot(home, "refresh")
        home.onNotice(bn("b1", "Thrall", true)); shot(home, "refresh_bnet")
        home.onNotice(JSONObject().put("type", "notice").put("kind", "shard").put("id", "1-1").put("ended", true))
        home.onNotice(bn("b2", "Quietone", false)); shot(home, "bnet_two")
        home.onNotice(shard(42, "1-2"))
        HomeView::class.java.getDeclaredField("goAt").apply { isAccessible = true }.setLong(home, SystemClock.uptimeMillis())
        shot(home, "refresh_seconds_pressed")
        ShadowSystemClock.advanceBy(Duration.ofMillis(6_000)); ShadowLooper.idleMainLooper()
        home.onNotice(shard(0, "1-3")); home.onNotice(bn("b3", null, true)); shot(home, "anytime_afriend")
        @Suppress("UNCHECKED_CAST")
        val live = field(home, "bnet") as List<NoticeModel.Bnet>
        assertEquals("the faded ones went: only the newest shows", listOf("b3"), live.map { it.id })
    }
}
