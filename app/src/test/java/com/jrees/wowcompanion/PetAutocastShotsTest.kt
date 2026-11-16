package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * THE PET'S AUTOCAST ON ITS WHEELS (Quests q62 / ActionBar pa1; the user, 2026-11-16: "hunters need to be able to visually see when their
 * pets abilities are active like growl, and a way to turn it off from those radials"). The REAL [TargetRadialView] as Party mode's pet
 * slot opens it (Input.KEYS: the PartyPad's presses) with the menu document the add-on's Lua sends (tools/test_pet_autocast.py's rows):
 * X on Growl taps its toggle cell (pfocus 23) and the wheel stays open, showing the flip at once; a long press does the same; A casts it
 * (pfocus 13) and closes; a fight that changed the pet refuses. The class wheel (RB + RT, CastView) draws the same look. With
 * PETAUTO_SHOTS_DIR set (PETAUTO_ART_ROOT = the art root), the pictures: Growl on, Growl off, the toggle, the class wheel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1240dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PetAutocastShotsTest {
    private val out: File? = System.getenv("PETAUTO_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun down() = PetAuto.reset()
    /** the art root before anything draws (UiKit keeps what it looked up) */
    @org.junit.Before fun up() = artRoot()

    private fun artRoot() {
        val root = System.getenv("PETAUTO_ART_ROOT") ?: return
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root))
    }

    /** the pet slot's menu as Quests q62 sends it: the q56 rows, then Growl (on) and Bite (off) */
    private fun menu(growlOn: Boolean = true, growlCell: Int? = 23) = JSONObject().put("type", "pmenu").put("v", "q62").put("unit", "pet")
        .put("which", "PET").put("nm", "Wolf").put("src", "party").put("items", JSONArray("""[
            {"t":"Feed Pet","k":"PetFeed","sc":5,"en":1,"ic":132165},
            {"t":"Dismiss Pet","k":"PetDismiss","sc":6,"en":1,"ic":136095},
            {"t":"Mend Pet","k":"PetMend","sc":8,"en":1,"ic":132179},
            {"t":"Beast Training","k":"PetTrain","sc":9,"en":1,"ic":132162},
            {"t":"Growl","k":"PetAuto","sc":13,${growlCell?.let { "\"ac\":$it," } ?: ""}"au":${if (growlOn) 1 else 0},"as":4,"en":1,"ic":132270},
            {"t":"Bite","k":"PetAuto","sc":14,"ac":24,"au":0,"as":5,"en":1,"ic":132278}]"""))

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private class Wheel(val v: TargetRadialView, val did: ArrayList<PlayerMenu.Do>, var closed: Int = 0)
    private fun wheel(doc: JSONObject = menu()): Wheel {
        // attached to a window, as on the device (a View's postDelayed - the long press - runs only once attached)
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val strip = Strip(act) { }
        val v = TargetRadialView(act, strip, { null }) { true }
        act.setContentView(android.widget.FrameLayout(act).apply { addView(strip); addView(v) })
        lay(strip, 1240, Strip.HEIGHT)
        lay(v, 1240, 1080)
        val did = ArrayList<PlayerMenu.Do>()
        val w = Wheel(v, did)
        v.open("party", TargetRadialView.Input.KEYS, "Wolf", { d, _ -> did += d }, { w.closed++ })
        v.fill(doc)
        return w
    }

    private fun draw(v: View): Bitmap {
        ShadowLooper.idleMainLooper()
        val b = Bitmap.createBitmap(maxOf(1, v.width), maxOf(1, v.height), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(20, 24, 32))
        v.draw(c)
        return b
    }
    private fun save(b: Bitmap, name: String) {
        val d = out ?: return
        d.mkdirs()
        File(d, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    /** the wheel fully open (its motion run out) */
    private fun opened() { ShadowSystemClock.advanceBy(Duration.ofMillis(900)); ShadowLooper.idleMainLooper() }

    @Test fun xTogglesTheAbilityAndTheWheelStaysOpen() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        val w = wheel()
        opened()
        w.v.key("PADDDOWN")                                      // the bottom slice: Growl (the fifth row, the kit's slice 4)
        w.v.key("PAD3")
        assertEquals(listOf<PlayerMenu.Do>(PlayerMenu.Do.Secure(23, "Growl", "pfocus")), w.did)
        assertTrue("X never closes the wheel", w.v.isOpen)
        assertEquals("the flip shows at once", false, PetAuto.on(4, SystemClock.uptimeMillis()))
        // the game's answer (PET_BAR_UPDATE -> petauto): settled off
        PetAuto.onDoc(JSONObject("""{"type":"petauto","pet":1,"s":[{"s":4,"n":"Growl","on":0,"sc":13,"ac":23},{"s":5,"n":"Bite","on":0,"sc":14,"ac":24}]}"""),
            SystemClock.uptimeMillis())
        assertEquals(false, PetAuto.on(4, SystemClock.uptimeMillis()))
        // X again turns it back on
        w.v.key("PAD3")
        assertEquals(PlayerMenu.Do.Secure(23, "Growl", "pfocus"), w.did.last())
        assertEquals(true, PetAuto.on(4, SystemClock.uptimeMillis()))
        // X on a slice that is no pet ability (Feed Pet): nothing
        w.v.key("PADDUP")
        w.v.key("PAD3")
        assertEquals(2, w.did.size)
        // A on Growl casts it (pfocus 13, the pet bar's left-click) and the wheel closes, as every pick
        w.v.key("PADDDOWN")
        w.v.key("PAD1")
        assertEquals(PlayerMenu.Do.Secure(13, "Growl", "pfocus"), w.did.last())
        assertFalse(w.v.isOpen)
        assertEquals(1, w.closed)
    }

    @Test fun aLongPressTogglesAndATapCasts() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        val w = wheel()
        opened()
        // the bottom slice's middle (the kit's slice 4: straight down from the wheel's centre)
        ShadowLooper.idleMainLooper()
        val screen = RectF(0f, 0f, w.v.width.toFloat(), w.v.height.toFloat())
        val x = screen.centerX()
        val ys = (0 until w.v.height).filter { y -> UiKit.pauseRadialHit(screen, x, y.toFloat()) == 4 }
        assertTrue("the bottom slice is under the wheel's middle", ys.isNotEmpty())
        val g = ys[ys.size / 2].toFloat()
        val t = SystemClock.uptimeMillis()
        w.v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, g, 0))
        ShadowSystemClock.advanceBy(Duration.ofMillis(TargetRadialView.LONG_MS + 50)); ShadowLooper.idleMainLooper()
        w.v.dispatchTouchEvent(MotionEvent.obtain(t, t + 500, MotionEvent.ACTION_UP, x, g, 0))
        assertEquals("the long press toggles, the release picks nothing", listOf<PlayerMenu.Do>(PlayerMenu.Do.Secure(23, "Growl", "pfocus")), w.did)
        assertTrue(w.v.isOpen)
        // a short tap casts
        val t2 = SystemClock.uptimeMillis()
        w.v.dispatchTouchEvent(MotionEvent.obtain(t2, t2, MotionEvent.ACTION_DOWN, x, g, 0))
        w.v.dispatchTouchEvent(MotionEvent.obtain(t2, t2 + 60, MotionEvent.ACTION_UP, x, g, 0))
        assertEquals(PlayerMenu.Do.Secure(13, "Growl", "pfocus"), w.did.last())
        assertFalse(w.v.isOpen)
    }

    /** review: a finger pressed on Growl that slides to Feed Pet and rests there picks Feed Pet - Growl's autocast never toggled */
    @Test fun aFingerSlidingOffTheAbilityNeverTogglesIt() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        val w = wheel()
        opened()
        val screen = RectF(0f, 0f, w.v.width.toFloat(), w.v.height.toFloat())
        val x = screen.centerX()
        val bottom = (0 until w.v.height).filter { y -> UiKit.pauseRadialHit(screen, x, y.toFloat()) == 4 }
        val top = (0 until w.v.height).filter { y -> UiKit.pauseRadialHit(screen, x, y.toFloat()) == 0 }
        assertTrue(bottom.isNotEmpty() && top.isNotEmpty())
        val g = bottom[bottom.size / 2].toFloat(); val f = top[top.size / 2].toFloat()
        val t = SystemClock.uptimeMillis()
        w.v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, g, 0))
        ShadowSystemClock.advanceBy(Duration.ofMillis(100)); ShadowLooper.idleMainLooper()
        w.v.dispatchTouchEvent(MotionEvent.obtain(t, t + 100, MotionEvent.ACTION_MOVE, x, f, 0))
        ShadowSystemClock.advanceBy(Duration.ofMillis(TargetRadialView.LONG_MS + 100)); ShadowLooper.idleMainLooper()
        w.v.dispatchTouchEvent(MotionEvent.obtain(t, t + 700, MotionEvent.ACTION_UP, x, f, 0))
        assertEquals("the release on Feed Pet picks it; no toggle", listOf<PlayerMenu.Do>(PlayerMenu.Do.Secure(5, "Feed Pet", "pfocus")), w.did)
    }

    @Test fun aFightThatChangedThePetRefusesTheToggle() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        val w = wheel(menu(growlCell = null))
        opened()
        PetAuto.onDoc(JSONObject("""{"type":"petauto","pet":1,"fight":1,"s":[{"s":4,"n":"Growl","on":1,"sc":13},{"s":5,"n":"Bite","on":0,"sc":14,"ac":24}]}"""),
            SystemClock.uptimeMillis())
        w.v.key("PADDDOWN"); w.v.key("PAD3")
        assertTrue("no toggle cell names Growl: nothing tapped", w.did.isEmpty())
        assertEquals(true, PetAuto.on(4, SystemClock.uptimeMillis()))
        // the pet died meanwhile: Bite refuses too
        PetAuto.onDoc(JSONObject("""{"type":"petauto","pet":1,"dead":1,"s":[{"s":5,"n":"Bite","on":0,"ac":24}]}"""), SystemClock.uptimeMillis() + 1)
        ShadowSystemClock.advanceBy(Duration.ofMillis(5))
        w.v.key("PADDRIGHT"); w.v.key("PAD3")
        assertTrue(w.did.isEmpty())
    }

    /** review: the wheel read on the wolf, then another pet in slot 4 (Claw, its own toggle cell 23 built out of combat): X on the wheel's
     *  Growl must never tap Claw's cell; the slice greys */
    @Test fun aPetSwapUnderAnOpenWheelNeverTogglesTheNewcomer() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        val w = wheel()
        opened()
        PetAuto.onDoc(JSONObject("""{"type":"petauto","pet":1,"s":[{"s":4,"n":"Claw","on":0,"sc":13,"ac":23},{"s":5,"n":"Bite","on":0,"sc":14,"ac":24}]}"""),
            SystemClock.uptimeMillis() + 1)
        ShadowSystemClock.advanceBy(Duration.ofMillis(5))
        w.v.key("PADDDOWN"); w.v.key("PAD3")
        assertTrue("Growl is gone: nothing tapped (pfocus 23 is Claw's now)", w.did.isEmpty())
        assertTrue(w.v.isOpen)
        // Bite is still the pet's: its own toggle
        w.v.key("PADDRIGHT"); w.v.key("PAD3")
        assertEquals(listOf<PlayerMenu.Do>(PlayerMenu.Do.Secure(24, "Bite", "pfocus")), w.did)
    }

    /** a class wheel (RB + RT) of pet spells as ActionBar pa1 sends it: Growl (on), Bite (off), Dash (cannot cast itself) */
    private fun classHud(growlOn: Boolean) = JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
        .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))
        .put("ca", JSONObject().put("r", JSONObject().put("e", JSONArray("""[
            {"k":"petspell","slot":4,"p":1,"n":"Growl","i":132270,"act":0,"auto":${if (growlOn) 1 else 0},"aa":1,"c":8,"ac":16},
            {"k":"petspell","slot":5,"p":2,"n":"Bite","i":132278,"act":0,"auto":0,"aa":1,"c":9,"ac":17},
            {"k":"petspell","slot":6,"p":3,"n":"Dash","i":132120,"act":0,"auto":0,"aa":0,"c":10,"ac":18}]""")))))

    @Test fun shots() {
        if (out == null) return
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        // the pet slot's wheel, Growl pointed: on (the marching shine, the X at its corner), then off (the quiet corners)
        val on = wheel(); opened(); on.v.key("PADDDOWN"); ShadowLooper.idleMainLooper()
        save(draw(on.v), "pet_wheel_growl_on.png")
        on.v.close("shot"); PetAuto.reset()
        val off = wheel(menu(growlOn = false)); opened(); off.v.key("PADDDOWN"); ShadowLooper.idleMainLooper()
        save(draw(off.v), "pet_wheel_growl_off.png")
        // THE TOGGLE: Growl on, X pressed - the flip shown at once (off), the wheel still open; then the shine a moment along its turn
        off.v.close("shot"); PetAuto.reset()
        val tg = wheel(); opened(); tg.v.key("PADDDOWN")
        save(draw(tg.v), "pet_wheel_toggle_1_before.png")
        tg.v.key("PAD3")
        save(draw(tg.v), "pet_wheel_toggle_2_after_x.png")
        tg.v.key("PAD3")
        ShadowSystemClock.advanceBy(Duration.ofMillis(1000))
        save(draw(tg.v), "pet_wheel_toggle_3_x_again_1s.png")
        tg.v.close("shot")
        // the class wheel (RB + RT): Growl on / off, Bite off, Dash (no autocast)
        for ((name, g) in listOf("class_wheel_growl_on.png" to true, "class_wheel_growl_off.png" to false)) {
            PetAuto.reset()
            val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
            val cast = CastView(act) { }
            act.setContentView(cast)
            cast.update(classHud(g))
            val b = Bitmap.createBitmap(620, 620, Bitmap.Config.ARGB_8888)
            // the cast art and the icons decode off the main thread: draw, let them land, draw again
            for (pass in 0..3) {
                val c = Canvas(b); c.drawColor(Color.rgb(20, 24, 32))
                assertTrue(cast.drawClassPreview(c, 620f, 620f, left = false, pointed = 0))
                if (pass < 3) { Thread.sleep(400); ShadowLooper.idleMainLooper() }
            }
            save(b, name)
        }
        // the pause wheel's kit alone, a warlock's imp (no hunter rows): Firebolt / Blood Pact on, Fire Shield / Phase Shift off
        PetAuto.reset()
        val imp = PlayerMenu.parse(JSONArray("""[
            {"t":"Firebolt","k":"PetAuto","sc":13,"ac":23,"au":1,"as":4,"en":1,"ic":135809},
            {"t":"Blood Pact","k":"PetAuto","sc":14,"ac":24,"au":1,"as":5,"en":1,"ic":136168},
            {"t":"Fire Shield","k":"PetAuto","sc":15,"ac":25,"au":0,"as":6,"en":1,"ic":135806},
            {"t":"Phase Shift","k":"PetAuto","sc":16,"ac":26,"au":0,"as":7,"en":1,"ic":136164}]"""))
        val b = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.rgb(20, 24, 32))
        val screen = RectF(0f, 0f, 1240f, 1080f)
        val hub = UiKit.consumableRadialHub(screen)
        val now = SystemClock.uptimeMillis()
        UiKit.pauseRadial(c, screen, PointF(hub.x, hub.y), 150f, TargetRadial.slices(imp)!!.map { it?.let { s -> TargetRadial.kitSlice(s, now) } },
            -1, 1, -1, UiKit.PAUSE_OPEN_MS, 0f, null, pickGlyph = true, up = null, down = null, left = null, right = null, title = "Imp")
        save(b, "pet_wheel_warlock_imp.png")
    }
}
