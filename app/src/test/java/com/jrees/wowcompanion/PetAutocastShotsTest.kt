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

    // ------------------------------------------------------------------------------------------------ the class pet wheel's face buttons

    /*
     * PET AUTOCAST ON THE FACE BUTTONS (the user, 2026-11-16: "I wonder if we should just use the face buttons without having to select
     * the attack to toggle the attack on and off? ... using Y/X/A as the toggles for them? just flat out playing the animation or turning
     * it off when toggling"; picks "Y / X / A, pet bar order", "Class pet wheel only"; the 4th, 2026-11-16: "on the pet skill raadial you
     * made it with the Dpad button but I actually thing that B still makes a lot of sense to use here", pick "Only with a 4th ability":
     * B toggles a 4th, else B goes back; D-pad Up nothing). The REAL CastView: the chord
     * (RB + RT) and the buttons go in as PadInput gives them (onPad / onButtons, bit n = PadInput.SLOT_x), the picks come out as the
     * classr cells the host taps (onClassPick).
     */

    /** the hud with a right class wheel of [entries] (ActionBar pa1's rows) and, on the base and RT layers, a 2.5 s cast on every face */
    private fun classHudOf(entries: String) = JSONObject("""{"hud":{"ok":true,"page":1,"pages":[true,true,true],"L":{
        "n":[{"p":5,"k":"spell","id":133,"i":135812,"ct":2500},{"p":6,"k":"spell","id":133,"i":135812,"ct":2500},
             {"p":7,"k":"spell","id":133,"i":135812,"ct":2500},{"p":8,"k":"spell","id":133,"i":135812,"ct":2500},
             {"p":2,"k":"spell","id":133,"i":135812,"ct":2500}],
        "rt":[{"p":5,"k":"spell","id":133,"i":135812,"ct":2500},{"p":6,"k":"spell","id":133,"i":135812,"ct":2500},
              {"p":7,"k":"spell","id":133,"i":135812,"ct":2500},{"p":8,"k":"spell","id":133,"i":135812,"ct":2500},
              {"p":2,"k":"spell","id":133,"i":135812,"ct":2500}]},
        "ca":{"r":{"e":$entries}}}}""")

    /** a hunter's wolf, the four the user named: Growl ON (pet bar slot 4), Bite OFF (5), Cower ON (6), Dash OFF (7) */
    private val wolf4 = """[
        {"k":"petspell","slot":4,"p":1,"n":"Growl","i":132270,"act":0,"auto":1,"aa":1,"c":8,"ac":16},
        {"k":"petspell","slot":5,"p":2,"n":"Bite","i":132278,"act":0,"auto":0,"aa":1,"c":9,"ac":17},
        {"k":"petspell","slot":6,"p":3,"n":"Cower","i":132118,"act":0,"auto":1,"aa":1,"c":10,"ac":18},
        {"k":"petspell","slot":7,"p":4,"n":"Dash","i":132120,"act":0,"auto":0,"aa":1,"c":11,"ac":19}]"""
    /** a warlock's imp: Firebolt ON, Blood Pact ON, Fire Shield OFF may cast themselves; Phase Shift may not (no button) */
    private val imp = """[
        {"k":"petspell","slot":4,"p":1,"n":"Firebolt","i":135809,"act":0,"auto":1,"aa":1,"c":8,"ac":16},
        {"k":"petspell","slot":5,"p":2,"n":"Blood Pact","i":136168,"act":0,"auto":1,"aa":1,"c":9,"ac":17},
        {"k":"petspell","slot":6,"p":3,"n":"Fire Shield","i":135806,"act":0,"auto":0,"aa":1,"c":10,"ac":18},
        {"k":"petspell","slot":7,"p":4,"n":"Phase Shift","i":136164,"act":0,"auto":0,"aa":0,"c":11,"ac":19}]"""
    /** two that may: Growl ON, Claw OFF; Dash may not */
    private val two = """[
        {"k":"petspell","slot":4,"p":1,"n":"Growl","i":132270,"act":0,"auto":1,"aa":1,"c":8,"ac":16},
        {"k":"petspell","slot":5,"p":2,"n":"Claw","i":132140,"act":0,"auto":0,"aa":1,"c":9,"ac":17},
        {"k":"petspell","slot":6,"p":3,"n":"Dash","i":132120,"act":0,"auto":0,"aa":0,"c":10,"ac":18}]"""
    /** a warrior's stances: no pet ability - the class wheel as it always was */
    private val stances = """[
        {"k":"spell","id":2457,"p":1,"n":"Battle Stance","i":132349,"act":1,"c":8},
        {"k":"spell","id":71,"p":2,"n":"Defensive Stance","i":132341,"act":0,"c":9},
        {"k":"spell","id":2458,"p":3,"n":"Berserker Stance","i":132275,"act":0,"c":10}]"""

    private class Cls(val v: CastView, val picks: ArrayList<Int>)
    private fun classWheel(entries: String): Cls {
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val v = CastView(act) { }
        act.setContentView(v)
        val picks = ArrayList<Int>()
        v.onClassPick = { picks += it }
        v.bottomLine = true                                       // Home's bars: a press there may start the predicted cast bar
        v.update(classHudOf(entries))
        ShadowLooper.idleMainLooper()
        return Cls(v, picks)
    }
    private val bool = java.lang.Boolean.TYPE
    private fun call(v: CastView, name: String, types: Array<Class<*>>, vararg a: Any?): Any? =
        CastView::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(v, *a)
    private fun field(v: CastView, name: String) = CastView::class.java.getDeclaredField(name).apply { isAccessible = true }
    /** RB + RT held (true) or let go */
    private fun chord(v: CastView, held: Boolean) = call(v, "onPad", arrayOf(bool, bool, bool, bool), false, held, false, held)
    private fun mask(v: CastView, m: Int) = call(v, "onButtons", arrayOf(Integer.TYPE), m)
    /** one press and release of PadInput slot [slot] */
    private fun press(v: CastView, slot: Int) { mask(v, 1 shl slot); mask(v, 0) }
    private fun wheelOpen(v: CastView) = call(v, "chordRadial", arrayOf()) != null
    private fun predicted(v: CastView) = field(v, "predOn").getBoolean(v)
    /** the right stick pushed toward [deg] (0 = up, clockwise), or let go (null) */
    private fun stick(v: CastView, deg: Double?) {
        val x = if (deg == null) 0f else kotlin.math.sin(Math.toRadians(deg)).toFloat()
        val y = if (deg == null) 0f else -kotlin.math.cos(Math.toRadians(deg)).toFloat()
        field(v, "stickX").setFloat(v, x); field(v, "stickY").setFloat(v, y)
        call(v, "classStick", arrayOf())
    }
    private fun line(v: CastView) = field(v, "classLine").get(v) as String?
    private fun now() = SystemClock.uptimeMillis()

    @Test fun yXABToggleTheirAbilitiesAtOnceAndTheWheelStaysOpen() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        val w = classWheel(wolf4)
        chord(w.v, true)
        assertTrue(wheelOpen(w.v))
        // Y = the 1st in pet bar order (Growl, on): its classr autocast cell 16, the flip at once, the wheel still open, no aiming
        press(w.v, PadInput.SLOT_FACE_TOP)
        assertEquals(listOf(16), w.picks)
        assertEquals("Growl's flip shows at once", false, PetAuto.on(4, "Growl", now()))
        assertTrue(wheelOpen(w.v))
        // a held Y toggles once
        mask(w.v, 1 shl PadInput.SLOT_FACE_TOP); mask(w.v, 1 shl PadInput.SLOT_FACE_TOP); mask(w.v, 0)
        assertEquals(listOf(16, 16), w.picks)
        assertEquals("Growl back on", true, PetAuto.on(4, "Growl", now()))
        // X = the 2nd (Bite, off -> on)
        press(w.v, PadInput.SLOT_FACE_LEFT)
        assertEquals(17, w.picks.last())
        assertEquals(true, PetAuto.on(5, "Bite", now()))
        // A = the 3rd (Cower): a toggle, never a cast (no place cell 8..11), the wheel open
        stick(w.v, 0.0)                                          // even with a slice pointed
        press(w.v, PadInput.SLOT_FACE_BOTTOM)
        assertEquals(18, w.picks.last())
        assertEquals(false, PetAuto.on(6, "Cower", now()))
        assertTrue(wheelOpen(w.v))
        stick(w.v, null)
        // D-pad Up: nothing (back to its old no-op)
        press(w.v, PadInput.SLOT_DPAD_UP)
        assertEquals(listOf(16, 16, 17, 18), w.picks)
        assertTrue(wheelOpen(w.v))
        // B = the 4th (Dash): a toggle, the wheel stays open (B is not back on this wheel), Home's B leaves it alone
        assertTrue(w.v.classBToggles())
        press(w.v, PadInput.SLOT_FACE_RIGHT)
        assertEquals(19, w.picks.last())
        assertEquals(true, PetAuto.on(7, "Dash", now()))
        assertEquals("nothing was ever cast", listOf(16, 16, 17, 18, 19), w.picks)
        assertTrue(wheelOpen(w.v))
        // letting the chord go closes it (Hold + Release, nothing pointed: no cast)
        chord(w.v, false)
        assertFalse(wheelOpen(w.v))
        assertFalse(w.v.classBToggles())
        assertEquals(5, w.picks.size)
        // a press the game never confirms falls back after PENDING_MS (Dash's last word: off)
        ShadowSystemClock.advanceBy(Duration.ofMillis(PetAuto.PENDING_MS + 100))
        assertEquals(false, PetAuto.on(7, "Dash", now()) ?: false)
    }

    @Test fun aToggleNeverStartsThePredictedCastBar() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        val w = classWheel(wolf4)
        // the control: Y with no wheel open is a bar press - a 2.5 s cast's bar starts at once
        press(w.v, PadInput.SLOT_FACE_TOP)
        assertTrue("the control: a bar press predicts", predicted(w.v))
        field(w.v, "predOn").setBoolean(w.v, false)
        chord(w.v, true)
        for (s in listOf(PadInput.SLOT_FACE_TOP, PadInput.SLOT_FACE_LEFT, PadInput.SLOT_FACE_BOTTOM, PadInput.SLOT_FACE_RIGHT)) {
            press(w.v, s)
            assertFalse("a toggle never starts the predicted bar (slot $s)", predicted(w.v))
        }
        assertEquals(listOf(16, 17, 18, 19), w.picks)
    }

    @Test fun aKeepsItsPickUntilItIsAToggle() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        HomeSettings.init(app)
        HomeSettings.classPickAR = true                           // Hold + A on RB + RT
        try {
            // two abilities: A still casts the pointed slice (Growl's place cell 8) and closes; X toggles the 2nd (Claw)
            val two = classWheel(two)
            chord(two.v, true)
            press(two.v, PadInput.SLOT_FACE_LEFT)
            assertEquals(listOf(17), two.picks)
            press(two.v, PadInput.SLOT_DPAD_UP)
            assertEquals("D-pad Up owns nothing here", listOf(17), two.picks)
            stick(two.v, 0.0)
            press(two.v, PadInput.SLOT_FACE_BOTTOM)
            assertEquals(listOf(17, 8), two.picks)
            assertFalse(wheelOpen(two.v))
            // one ability: X owns nothing (it no longer toggles the pointed one), Y toggles it
            PetAuto.reset()
            val one = classWheel("""[{"k":"petspell","slot":4,"p":1,"n":"Growl","i":132270,"act":0,"auto":1,"aa":1,"c":8,"ac":16},
                {"k":"petspell","slot":5,"p":2,"n":"Dash","i":132120,"act":0,"auto":0,"aa":0,"c":9,"ac":17}]""")
            chord(one.v, true)
            stick(one.v, 0.0)                                     // Growl pointed
            press(one.v, PadInput.SLOT_FACE_LEFT)
            assertTrue("X on a 1-ability wheel: nothing", one.picks.isEmpty())
            press(one.v, PadInput.SLOT_FACE_TOP)
            assertEquals(listOf(16), one.picks)
            // three: A toggles the 3rd and never casts; the pick falls to Release - letting the chord go casts the pointed slice
            PetAuto.reset()
            val imp = classWheel(imp)
            chord(imp.v, true)
            stick(imp.v, 90.0)                                    // Blood Pact (2nd of 4 places, at 90 degrees)
            press(imp.v, PadInput.SLOT_FACE_BOTTOM)
            assertEquals(listOf(18), imp.picks)
            assertTrue(wheelOpen(imp.v))
            chord(imp.v, false)
            assertEquals("the chord let go casts the pointed Blood Pact (cell 9)", listOf(18, 9), imp.picks)
            assertFalse(wheelOpen(imp.v))
            // three: B still goes back (no cast, nothing toggled)
            chord(imp.v, true)
            assertFalse(imp.v.classBToggles())
            press(imp.v, PadInput.SLOT_FACE_RIGHT)
            assertFalse(wheelOpen(imp.v))
            assertEquals(listOf(18, 9), imp.picks)
            // two: B goes back too
            chord(two.v, false); chord(two.v, true)
            assertTrue(wheelOpen(two.v))
            press(two.v, PadInput.SLOT_FACE_RIGHT)
            assertFalse(wheelOpen(two.v))
            assertEquals(listOf(17, 8), two.picks)
        } finally { HomeSettings.classPickAR = false }
    }

    /** with a 4th ability B is a toggle and never closes: the wheel must still close in every open / pick mode */
    @Test fun aFourAbilityWheelAlwaysCloses() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        HomeSettings.init(app)
        try {
            for (pickA in listOf(false, true)) {
                HomeSettings.classPickAR = pickA
                // Hold: letting the chord go closes (nothing pointed: no cast; pointed: casts it - A never does here)
                HomeSettings.classTapR = false
                PetAuto.reset()
                val h = classWheel(wolf4)
                chord(h.v, true)
                press(h.v, PadInput.SLOT_FACE_RIGHT)
                assertEquals("Hold (pickA=$pickA): B toggles Dash", listOf(19), h.picks)
                assertTrue(wheelOpen(h.v))
                chord(h.v, false)
                assertFalse("Hold (pickA=$pickA): the chord let go closes", wheelOpen(h.v))
                assertEquals(listOf(19), h.picks)
                chord(h.v, true); stick(h.v, 90.0)               // Bite pointed
                chord(h.v, false)
                assertFalse(wheelOpen(h.v))
                assertEquals("Hold (pickA=$pickA): the chord let go casts the pointed Bite (cell 9)", listOf(19, 9), h.picks)
                stick(h.v, null)
                // Tap: the chord let go keeps it open; B toggles, never closes; the same chord again closes (no cast)
                HomeSettings.classTapR = true
                PetAuto.reset()
                val t = classWheel(wolf4)
                chord(t.v, true); chord(t.v, false)
                assertTrue("Tap (pickA=$pickA): stays", wheelOpen(t.v))
                press(t.v, PadInput.SLOT_FACE_RIGHT)
                press(t.v, PadInput.SLOT_FACE_BOTTOM)
                assertEquals(listOf(19, 18), t.picks)
                assertTrue(wheelOpen(t.v))
                chord(t.v, true)
                assertFalse("Tap (pickA=$pickA): the same chord again closes", wheelOpen(t.v))
                assertEquals(listOf(19, 18), t.picks)
                chord(t.v, false)
                // Tap: the stick back in the middle after pointing casts the pointed slice and closes
                chord(t.v, true); chord(t.v, false)
                assertTrue(wheelOpen(t.v))
                stick(t.v, 180.0)                                 // Cower pointed
                stick(t.v, null)
                assertFalse("Tap (pickA=$pickA): the stick back closes", wheelOpen(t.v))
                assertEquals("the stick back casts the pointed Cower (cell 10)", listOf(19, 18, 10), t.picks)
            }
        } finally { HomeSettings.classPickAR = false; HomeSettings.classTapR = false }
    }

    @Test fun aClassWheelWithNoPetAbilityIsAsBefore() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        HomeSettings.init(app)
        HomeSettings.classPickAR = true
        try {
            val w = classWheel(stances)
            chord(w.v, true)
            stick(w.v, 120.0)                                     // Defensive Stance (2nd of 3, at 120 degrees)
            for (s in listOf(PadInput.SLOT_FACE_TOP, PadInput.SLOT_FACE_LEFT, PadInput.SLOT_DPAD_UP)) press(w.v, s)
            assertTrue("Y, X, D-pad Up: nothing on a wheel with no pet ability", w.picks.isEmpty())
            assertTrue(wheelOpen(w.v))
            press(w.v, PadInput.SLOT_FACE_BOTTOM)
            assertEquals("A casts the pointed slice, as before", listOf(9), w.picks)
            assertFalse(wheelOpen(w.v))
        } finally { HomeSettings.classPickAR = false }
        // Hold + Release (the default): A does nothing, letting the chord go casts
        val r = classWheel(stances)
        chord(r.v, true)
        stick(r.v, 0.0)
        press(r.v, PadInput.SLOT_FACE_BOTTOM)
        assertTrue(r.picks.isEmpty())
        chord(r.v, false)
        assertEquals(listOf(8), r.picks)
    }

    @Test fun refusalsKeepThePetWheelsAnswers() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        // a fight changed the pet: Growl's toggle cell is gone from the layout (no `ac`)
        val w = classWheel(wolf4.replace(""","c":8,"ac":16}""", ""","c":8}"""))
        chord(w.v, true)
        ShadowSystemClock.advanceBy(Duration.ofMillis(5))
        PetAuto.onDoc(JSONObject("""{"type":"petauto","pet":1,"fight":1,"s":[{"s":4,"n":"Growl","on":1},{"s":5,"n":"Bite","on":0,"ac":24},
            {"s":6,"n":"Cower","on":1,"ac":25},{"s":7,"n":"Dash","on":0,"ac":26}]}"""), now())
        press(w.v, PadInput.SLOT_FACE_TOP)
        assertTrue("no cell: nothing tapped", w.picks.isEmpty())
        assertEquals("Not in combat", line(w.v))
        assertEquals("no flip", true, PetAuto.on(4, "Growl", now()))
        // Bite still has its cell
        press(w.v, PadInput.SLOT_FACE_LEFT)
        assertEquals(listOf(17), w.picks)
        // the pet died: every toggle refuses
        ShadowSystemClock.advanceBy(Duration.ofMillis(5))
        PetAuto.onDoc(JSONObject("""{"type":"petauto","pet":1,"dead":1,"s":[{"s":5,"n":"Bite","on":1,"ac":24}]}"""), now())
        press(w.v, PadInput.SLOT_FACE_BOTTOM)
        assertEquals(listOf(17), w.picks)
        assertEquals("Not available here", line(w.v))
        // the line goes with its time (the next draw shows the pointed name again)
        ShadowSystemClock.advanceBy(Duration.ofMillis(HomePauseView.LINE_MS + HomePauseView.LINE_FADE_MS + 50))
        assertTrue(w.v.drawClassRadial(Canvas(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)), 200f, 200f))
        assertEquals(null, line(w.v))
    }

    /** the open class wheel as Home draws it (drawClassRadial), the icons given time to land; the chord (and the stick at [deg]) held
     *  again before each pass - the shared controller reader's revive (PadHub, no device here) lets every view's buttons go */
    private fun classShot(v: CastView, name: String, size: Int = 620, deg: Double? = null, each: (() -> Unit)? = null) {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (pass in 0..3) {
            chord(v, true); if (deg != null) stick(v, deg); each?.invoke()
            val c = Canvas(b); c.drawColor(Color.rgb(20, 24, 32))
            assertTrue(v.drawClassRadial(c, size.toFloat(), size.toFloat()))
            if (pass < 3) { Thread.sleep(400); ShadowLooper.idleMainLooper() }
        }
        save(b, name)
    }

    @Test fun faceButtonShots() {
        if (out == null) return
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        // the hunter's wolf: Y Growl (on), X Bite (off), A Cower (on), B Dash (off); nothing pointed
        val w = classWheel(wolf4)
        chord(w.v, true)
        classShot(w.v, "1_hunter_4_abilities.png")
        // X pressed: Bite flips on at once (its shine), the wheel stays
        press(w.v, PadInput.SLOT_FACE_LEFT)
        classShot(w.v, "2_hunter_after_x_bite_on.png")
        // the stick on Bite: its name in the middle, the glyph over the selection frame
        classShot(w.v, "3_hunter_bite_pointed.png", deg = 90.0)
        // the warlock's imp: Y Firebolt, X Blood Pact, A Fire Shield; Phase Shift no glyph
        PetAuto.reset()
        val i = classWheel(imp)
        chord(i.v, true)
        classShot(i.v, "4_warlock_imp_3_plus_phase_shift.png")
        // two: Y Growl, X Claw; Dash no glyph
        PetAuto.reset()
        val t = classWheel(two)
        chord(t.v, true)
        classShot(t.v, "5_two_abilities.png")
        // a fight changed the pet: Growl's glyph faint, Y's answer in the middle
        PetAuto.reset()
        val f = classWheel(wolf4.replace(""","c":8,"ac":16}""", ""","c":8}"""))
        chord(f.v, true)
        ShadowSystemClock.advanceBy(Duration.ofMillis(5))
        PetAuto.onDoc(JSONObject("""{"type":"petauto","pet":1,"fight":1,"s":[{"s":4,"n":"Growl","on":1},{"s":5,"n":"Bite","on":0,"ac":24},
            {"s":6,"n":"Cower","on":1,"ac":25},{"s":7,"n":"Dash","on":0,"ac":26}]}"""), now())
        classShot(f.v, "6_fight_refused_not_in_combat.png") { press(f.v, PadInput.SLOT_FACE_TOP) }
        // Home's wheel at a smaller box (a narrow column): the glyphs still read
        PetAuto.reset()
        val s = classWheel(wolf4)
        chord(s.v, true)
        classShot(s.v, "7_hunter_4_small_440.png", 440)
    }

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
