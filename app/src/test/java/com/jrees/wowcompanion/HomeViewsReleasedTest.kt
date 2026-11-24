package com.jrees.wowcompanion

import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.lang.ref.WeakReference
import java.time.Duration

/**
 * A HOME THAT IS GONE IS LET GO (the 1528 test build's blocker, 2026-11-24: HomeMenusFitShotsTest's test JVM grew ~1.6 GB a minute -
 * on 1.0.40 as well - until the native getPixels on the "cast-art" thread crashed it. A heap histogram after a full GC: 481 MapViews,
 * 45,695 CastView art pieces and 47,973 Bitmaps still live (their pixels native: ~3 GB at 2 minutes), 708,549 runnables queued on views
 * that were never attached). Every Home page the test made stayed reachable from the SETTINGS' STATIC LISTENER LISTS: HomeView,
 * MapView, ChatView, CastView and Home's chat pane each registered a lambda that held the view itself (HomeSettings, HomeLayoutStore,
 * GameUi, Settings, ChatFilter, TalkKeep, ItemCd) and nothing ever removed it - and every later change posted one more runnable to each
 * kept view. On the device the same happens whenever the bottom screen's host is made again (a display change, Swap screens' restart):
 * the old Home with its map and its decoded art was never freed. Now each such listener holds its view WEAKLY (CastView's Theme
 * listener already did).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeViewsReleasedTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun lay(v: View, w: Int = 1240, h: Int = 1080 - Strip.HEIGHT) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun settle(n: Int = 40) { for (i in 0 until n) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }

    /** one Home page as the host builds it (HomeMenusFitShotsTest's page, short): its views, held only weakly here */
    private fun page(): List<WeakReference<View>> {
        HomeSettings.init(app); Settings.init(app)
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        lay(home)
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        home.tick()
        settle()
        return listOf(WeakReference<View>(home), WeakReference<View>(map), WeakReference<View>(cast), WeakReference<View>(chat))
    }

    @Test fun aHomeThatIsGoneIsCollected() {
        val refs = ArrayList<WeakReference<View>>()
        repeat(3) { refs += page() }
        // the newest Home may stay: the registries keyed by a name keep the latest one only (MemWatch's "plans": the live map's);
        // the ones before it must go
        val last = page()
        // the settings change while they are gone (each listener runs: a kept view would get one more queued runnable)
        HomeSettings.swingTimer = !HomeSettings.swingTimer
        HomeSettings.swingTimer = !HomeSettings.swingTimer
        settle()
        for (i in 0 until 20) {
            System.gc(); System.runFinalization()
            settle(2)
            if (refs.all { it.get() == null }) break
            Thread.sleep(50)
        }
        val kept = refs.mapNotNull { it.get()?.javaClass?.simpleName }
        assertEquals(4, last.size)
        // what still holds one (the walk from the statics and the main looper), for the message
        val held = refs.mapNotNull { it.get() }.toSet()
        val path = if (kept.isEmpty()) null else RetainedPath.find({ o -> o in held })
        assertEquals("views still reachable after the Home pages went: $kept; held by:\n  $path", emptyList<String>(), kept)
    }
}
