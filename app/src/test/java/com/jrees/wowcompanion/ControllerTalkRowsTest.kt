package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
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
 * PadMap m170: HOLD TO TALK ON THE CHAT BUTTON IN THE CONTROLLER ROOM (the user, 2026-11-17: "I want the push to talk settings added to
 * the chat button inside of the controller binding room"), on the real view: WoW Defaults' bare D-pad Left with the toolbar's Chat shows
 * "Hold to talk" (and, while On, "On release"); a finger flips them in the SAME per-character store as Settings > Bottom screen > Home
 * (HomeSettings), and a change made there shows here at once; a button carrying "Push to talk (hold)" shows "On release"; another
 * button shows neither. PNGs when TALKROWS_SHOTS_DIR is set (TALKROWS_ART_ROOT = an art root laid out like the app's).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ControllerTalkRowsTest {
    private val out: File? = System.getenv("TALKROWS_SHOTS_DIR")?.let { File(it).also { d -> d.mkdirs() } }
    private val app get() = RuntimeEnvironment.getApplication()
    private val WHO = "Korvash-Realm"
    private val W = 1240; private val H = 960
    /** the right page's rows (PadTabPage: x from SPLIT + 40 to w - 56, the first at 188 + 236, 76 tall, every 88) */
    private val rowX = (740f + 40f + (1240f - 56f)) / 2f
    private fun rowY(i: Int) = 424f + i * 88f + 38f

    @Before fun setUp() {
        System.getenv("TALKROWS_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
        HomeSettings.init(app)
        GameUi.character = WHO
        HomeSettings.setHoldTalk(WHO, false); HomeSettings.setTalkSendNow(WHO, false)
    }

    @After fun clean() { HomeSettings.setHoldTalk(WHO, false); HomeSettings.setTalkSendNow(WHO, false); GameUi.character = null }

    private fun slots(three: String) = """[{"kind":"none","pos":1},{"kind":"none","pos":2},$three,{"kind":"none","pos":4},
        {"kind":"fixed","pos":5},{"kind":"fixed","pos":6},{"kind":"fixed","pos":7},{"kind":"fixed","pos":8}]"""
    private val cmds = """[{"key":"DSCHAT","n":"Open Chat","i":134332,"made":true,"b":"/click WowCompanionPadChat"},
        {"key":"DSPTT","n":"Push to talk (hold)","i":134332,"made":true,"b":"/click WowCompanionPadTalk"},
        {"key":"DSPSMENU","n":"Polestar menu","i":134269,"made":true,"b":"/click WowCompanionPadPolestarMenu"}]"""
    private fun padmap(toolbar: Boolean, three: String = """{"kind":"none","pos":3}""") = PadMapModel.parse(JSONObject("""{"type":"padmap",
        "v":"m170","m":{"page":1,"pages":3,"sets":[{"set":1,"slots":${slots(three)}}],"commands":$cmds,
        "dpad":{"cols":{"off":8,"on":7},"d":{"1":"chat","2":"radial","3":"map","4":"home"},"on":$toolbar,"want":$toolbar,
        "who":{"n":"Korvash","r":"Realm"}}}}"""))!!
    private fun ctl(pos: Int) = PadMapModel.parseCtl(JSONObject("""{"type":"padtabctl","v":"p54","c":{"dpos":2,"focus":"buttons",
        "lay":"swap","on":true,"page":1,"pos":$pos,"scur":2,"set":1,"shown":1,"sw":1,"tcur":1,"view":"normal"}}"""))!!

    private fun room(map: PadMapModel.Map, pos: Int): ControllerView {
        val v = ControllerView(app, { true })
        v.onPadMap(map); v.onPadTabCtl(ctl(pos))
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, W, H)
        draw(v, null)
        return v
    }

    private fun draw(v: View, name: String?) {
        ShadowLooper.idleMainLooper()
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        val d = out ?: return
        if (name != null) File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tap(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0))
        ShadowLooper.idleMainLooper()
    }

    @Test fun theToolbarsChatCarriesHoldToTalkAndOnRelease() {
        val v = room(padmap(toolbar = true), 1)
        draw(v, "ctl_chat_talk_off")
        tap(v, rowX, rowY(0))
        assertTrue("the row flips this character's Hold to talk", HomeSettings.holdTalk(WHO))
        draw(v, "ctl_chat_talk_on")
        tap(v, rowX, rowY(1))
        assertTrue("On release under it, while On", HomeSettings.talkSendNow(WHO))
        draw(v, "ctl_chat_talk_sendnow")
        // ONE store: Settings' change shows here (the same HomeSettings keys), and turning it off hides On release again
        HomeSettings.setHoldTalk(WHO, false)
        draw(v, null)
        tap(v, rowX, rowY(1))
        assertTrue("no On release row while Off: nothing there flips", HomeSettings.talkSendNow(WHO) && !HomeSettings.holdTalk(WHO))
    }

    @Test fun theDsChatCommandOnTheBareLeftToo() {
        val chatLeft = PadMapModel.parse(JSONObject(padmapJson("""{"kind":"command","pos":1,"s":1,"key":"DSCHAT","n":"Open Chat","i":134332,
            "b":"/click WowCompanionPadChat","ov":true}""")))!!
        val v = room(chatLeft, 1)
        tap(v, rowX, rowY(0))
        assertTrue(HomeSettings.holdTalk(WHO))
        draw(v, "ctl_dschat_left_talk")
    }

    /** a toolbar-off padmap with [one] on the bare D-pad Left */
    private fun padmapJson(one: String) = """{"type":"padmap","v":"m170","m":{"page":1,"pages":3,"sets":[{"set":1,"slots":[$one,
        {"kind":"none","pos":2},{"kind":"none","pos":3},{"kind":"none","pos":4},{"kind":"fixed","pos":5},{"kind":"fixed","pos":6},
        {"kind":"fixed","pos":7},{"kind":"fixed","pos":8}]}],"commands":$cmds,"dpad":{"cols":{"off":8,"on":7},"d":{},"on":false,
        "want":false,"who":{"n":"Korvash","r":"Realm"}}}}"""

    @Test fun pushToTalkCarriesOnReleaseOnly() {
        val v = room(padmap(toolbar = false, three = """{"kind":"command","pos":3,"s":3,"key":"DSPTT","n":"Push to talk (hold)","i":134332,
            "b":"/click WowCompanionPadTalk","ov":true}"""), 3)
        draw(v, "ctl_ptt_button")
        tap(v, rowX, rowY(0))
        assertTrue("On release flips", HomeSettings.talkSendNow(WHO))
        assertFalse("the switch is not push to talk's", HomeSettings.holdTalk(WHO))
    }

    @Test fun anotherButtonHasNoRows() {
        val v = room(padmap(toolbar = false, three = """{"kind":"command","pos":3,"s":3,"key":"DSPSMENU","n":"Polestar menu","i":134269,
            "b":"/click WowCompanionPadPolestarMenu","ov":true}"""), 3)
        draw(v, "ctl_psmenu_button")
        tap(v, rowX, rowY(0))
        assertFalse(HomeSettings.talkSendNow(WHO)); assertFalse(HomeSettings.holdTalk(WHO))
        // the D-pad Left with the toolbar off and nothing on it: no rows either
        val e = room(padmap(toolbar = false), 1)
        tap(e, rowX, rowY(0))
        assertEquals(false, HomeSettings.holdTalk(WHO))
    }
}
