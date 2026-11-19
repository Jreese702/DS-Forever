package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
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
import java.io.File

/**
 * SETTINGS > GENERAL > RESET, off-device (the user, 2026-11-19: "DS Forever needs reset to defaults buttons, split into four"; the
 * picks: a folding Reset group just above Support the Project; every button asks first in a real Yes / No pop-up that holds the pad;
 * Everything asks This character / All characters, then confirms). The room built as the host builds it: Reset shows folded, its heading
 * opens it (touch, or A on the heading), four one-button rows; a press opens the pop-up (the pad held from the game while it is up), No /
 * B closes it with nothing run, Yes / A runs the reset; Layouts / Skills with no character say so on the button.
 *
 * With RESET_SHOTS_DIR set it renders the group and the pop-ups (folded, open, each pop-up, the scope step, the words after, ES, FR).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResetGroupRoomTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private var n = 0
    private var who: String? = "Shammy-Fakerealm"
    private val resets = ArrayList<Pair<ResetPlan.Button, Boolean>>()
    private var word: (ResetPlan.Button) -> String? = { null }
    private lateinit var host: SettingsView

    private fun art(root: String? = System.getenv("THEME_ART_ROOT")) {
        val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, root?.let { File(it) })
        UiKit.root = UiKit.root
        GeneralPictures.letGo()
    }

    private fun room(): SettingsRoom {
        Settings.init(app); SettingsTabs.init(app); Theme.init(app)
        art()
        ScreenSwap.offered = true
        val v = SettingsView(app) {}
        host = v
        v.loginRow = true
        v.onReset = { b, all -> resets += b to all }
        v.resetWho = { who }
        v.resetWord = { b -> word(b) }
        val r = SettingsRoom(app, v) { null }
        r.show(SettingsTabs.Tab.GENERAL, force = true)
        lay(r)
        return r
    }

    private val scratch: Bitmap by lazy { Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888) }
    private fun lay(r: View) {
        for (k in 0..2) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            r.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
            r.layout(0, 0, W, H)
            r.draw(Canvas(scratch))
        }
    }

    private fun part(r: SettingsRoom, g: SettingsTabs.Group) = r.groupBoxes.first { it.group == g }.parts.single() as GeneralTilesView
    private fun heading(r: SettingsRoom, g: SettingsTabs.Group) = r.groupBoxes.first { it.group == g }.heading
    private fun tap(r: SettingsRoom, v: View, b: RectF) {
        val d = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, b.centerX(), b.centerY(), 0); v.dispatchTouchEvent(d); d.recycle()
        val u = MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, b.centerX(), b.centerY(), 0); v.dispatchTouchEvent(u); u.recycle()
        lay(r)
    }
    private fun press(r: SettingsRoom, k: String) { r.pad.onPad(JSONObject().put("k", k).put("n", ++n)); lay(r) }
    private fun hold(r: SettingsRoom) {
        r.pad.enter(); r.show(SettingsTabs.Tab.GENERAL, force = true)
        r.pad.onGuestAck(JSONObject().put("op", "hold").put("ok", true)); lay(r)
    }
    private fun button(r: SettingsRoom, row: GeneralTiles.Row) = part(r, SettingsTabs.Group.RESET).controlBoxes(row).single()
    private fun openReset(r: SettingsRoom) { tap(r, heading(r, SettingsTabs.Group.RESET), heading(r, SettingsTabs.Group.RESET).rect()) }
    private fun askButtons(r: SettingsRoom) = r.resetAsk.buttonBoxes(r.resetAsk.width.toFloat(), r.resetAsk.height.toFloat())

    @After fun forget() {
        if (::host.isInitialized) runCatching { }
        PopupHold.wheel.down(0L)
        Tr.setChoice(app, "auto")
        Theme.use("classic")
        ScreenSwap.offered = false
        GeneralPictures.letGo()
    }

    @Test fun resetFoldsAboveSupportAndOpensOnItsHeading() {
        val r = room()
        val gs = SettingsTabs.groups(SettingsTabs.Tab.GENERAL)
        assertEquals(listOf(SettingsTabs.Group.RESET, SettingsTabs.Group.SUPPORT), gs.takeLast(2))
        assertFalse("shown folded", SettingsTabs.Group.RESET in r.open)
        assertEquals(View.GONE, part(r, SettingsTabs.Group.RESET).visibility)
        assertEquals("Support the Project stays the last row, open", View.VISIBLE, part(r, SettingsTabs.Group.SUPPORT).visibility)
        openReset(r)
        assertTrue(SettingsTabs.Group.RESET in r.open)
        val reset = part(r, SettingsTabs.Group.RESET)
        assertEquals(listOf(GeneralTiles.Row.RESET_SETTINGS, GeneralTiles.Row.RESET_LAYOUTS, GeneralTiles.Row.RESET_SKILLS, GeneralTiles.Row.RESET_ALL), reset.rowIds())
        for (row in reset.rowIds()) {
            val b = button(r, row)
            assertTrue("one button across the row", b.width() > W * 0.8f)
            assertEquals(SettingsTabs.resetLabel(GeneralTiles.resetOf(row)!!), reset.resetWord(GeneralTiles.resetOf(row)!!))
        }
        // the heading is a cursor stop (A folds it again)
        tap(r, heading(r, SettingsTabs.Group.RESET), heading(r, SettingsTabs.Group.RESET).rect())
        assertFalse(SettingsTabs.Group.RESET in r.open)
    }

    @Test fun everyButtonAsksFirstAndHoldsThePad() {
        val r = room(); openReset(r)
        for ((row, b) in listOf(GeneralTiles.Row.RESET_SETTINGS to ResetPlan.Button.SETTINGS, GeneralTiles.Row.RESET_LAYOUTS to ResetPlan.Button.LAYOUTS,
                GeneralTiles.Row.RESET_SKILLS to ResetPlan.Button.SKILLS)) {
            tap(r, part(r, SettingsTabs.Group.RESET), button(r, row))
            assertTrue("$b asks first", r.resetAsk.isOpen)
            assertTrue("nothing run before the Yes", resets.isEmpty())
            assertTrue("the pad held from the game while it asks", PopupHold.wheel.holds(SystemClock.uptimeMillis()))
            // No (a touch): closed, nothing run
            tap(r, r.resetAsk, askButtons(r)[1])
            assertFalse(r.resetAsk.isOpen)
            assertTrue(resets.isEmpty())
            assertFalse("the hold ends after its grace", PopupHold.wheel.holds(SystemClock.uptimeMillis() + PopupHold.GRACE_MS + 1))
            // Yes (a touch)
            tap(r, part(r, SettingsTabs.Group.RESET), button(r, row))
            tap(r, r.resetAsk, askButtons(r)[0])
            assertEquals(b to false, resets.removeAt(0))
            assertFalse(r.resetAsk.isOpen)
        }
    }

    @Test fun theControllerAnswersAWithYesAndBWithNo() {
        val r = room(); openReset(r)
        hold(r)
        openReset(r)
        val reset = part(r, SettingsTabs.Group.RESET)
        // the pop-up opened from its button; B = No (nothing run, the room still open: B was the pop-up's)
        tap(r, reset, button(r, GeneralTiles.Row.RESET_SKILLS))
        press(r, "PADDUP")                                     // nothing under it moves
        assertTrue(r.resetAsk.isOpen)
        press(r, "PAD2")
        assertFalse(r.resetAsk.isOpen); assertTrue(resets.isEmpty())
        tap(r, reset, button(r, GeneralTiles.Row.RESET_SKILLS))
        press(r, "PAD1")
        assertEquals(listOf(ResetPlan.Button.SKILLS to false), resets)
        // Everything: which characters first (Right = All characters), then Yes
        resets.clear()
        tap(r, reset, button(r, GeneralTiles.Row.RESET_ALL))
        assertEquals(ResetAsk.Step.SCOPE, r.resetAsk.ask!!.step)
        press(r, "PADDRIGHT"); press(r, "PAD1")
        assertEquals(ResetAsk.Step.CONFIRM, r.resetAsk.ask!!.step)
        assertTrue(resets.isEmpty())
        press(r, "PAD1")
        assertEquals(listOf(ResetPlan.Button.EVERYTHING to true), resets)
    }

    @Test fun noCharacterYetSaysSo() {
        who = null
        val r = room(); openReset(r)
        val reset = part(r, SettingsTabs.Group.RESET)
        tap(r, reset, button(r, GeneralTiles.Row.RESET_LAYOUTS))
        assertFalse(r.resetAsk.isOpen)
        assertEquals(GeneralTiles.NO_CHARACTER, reset.resetWord(ResetPlan.Button.LAYOUTS))
        // Settings needs none; Everything only for every character
        tap(r, reset, button(r, GeneralTiles.Row.RESET_ALL))
        assertTrue(r.resetAsk.isOpen)
        assertFalse(r.resetAsk.ask!!.enabled(0))
        r.resetAsk.cancel()
    }

    @Test fun wordsInSpanishAndFrench() {
        for ((l, w) in listOf("es" to listOf("Restablecer ajustes", "¿Restablecer todo?", "Todos los personajes"),
                "fr" to listOf("Réinitialiser les réglages", "Tout réinitialiser ?", "Tous les personnages"))) {
            loadLang(l); Tr.setChoice(app, l)
            assertEquals(w[0], Tr.t("Reset settings")); assertEquals(w[1], Tr.t("Reset everything?")); assertEquals(w[2], Tr.t("All characters"))
        }
    }

    private fun loadLang(l: String) = listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
        .first { it.canRead() }.let { Tr.load(l, it.readText()) }

    // ---------------------------------------------------------------- the renders (RESET_SHOTS_DIR)

    @Test fun shots() {
        val out = System.getenv("RESET_SHOTS_DIR")?.let { File(it) } ?: return
        out.mkdirs()
        for (l in listOf("es", "fr")) loadLang(l)
        val page = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        var r = room()
        val sc = { SettingsRoom::class.java.getDeclaredField("scroll").apply { isAccessible = true }.get(r) as android.widget.ScrollView }
        fun shot(name: String, bottom: Boolean = true) {
            lay(r); if (bottom) { sc().scrollTo(0, 100_000); lay(r) }
            val c = Canvas(page); c.drawColor(Theme.ink); r.draw(c)
            File(out, "$name.png").outputStream().use { page.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        shot("reset_1_folded")
        openReset(r)
        shot("reset_2_open")
        hold(r); openReset(r)
        // the cursor walked down onto Reset settings
        val want = part(r, SettingsTabs.Group.RESET).controlBoxes(GeneralTiles.Row.RESET_SETTINGS).single()
        repeat(40) { if (r.pad.focusBox()?.let { it.height() == want.height() && it.width() == want.width() } != true) press(r, "PADDDOWN") }
        shot("reset_3_cursor_on_button")
        val reset = { part(r, SettingsTabs.Group.RESET) }
        for ((row, nm) in listOf(GeneralTiles.Row.RESET_SETTINGS to "settings", GeneralTiles.Row.RESET_LAYOUTS to "layouts", GeneralTiles.Row.RESET_SKILLS to "skills")) {
            tap(r, reset(), button(r, row)); shot("reset_4_ask_$nm"); r.resetAsk.cancel()
        }
        tap(r, reset(), button(r, GeneralTiles.Row.RESET_ALL)); shot("reset_5_everything_scope")
        press(r, "PADDRIGHT"); shot("reset_6_everything_scope_all_lit")
        press(r, "PAD1"); shot("reset_7_everything_confirm_all")
        r.resetAsk.cancel()
        // the words after a press
        word = { b -> when (b) { ResetPlan.Button.SETTINGS -> ResetFlow.DONE; ResetPlan.Button.LAYOUTS -> ResetFlow.WORKING
            ResetPlan.Button.SKILLS -> ResetFlow.NOT_IN_COMBAT; else -> ResetFlow.NO_ANSWER } }
        reset().invalidate(); shot("reset_8_button_words")
        word = { null }
        who = null; r = room(); openReset(r)
        tap(r, reset(), button(r, GeneralTiles.Row.RESET_SKILLS)); shot("reset_9_no_character")
        tap(r, reset(), button(r, GeneralTiles.Row.RESET_ALL)); shot("reset_10_everything_no_character"); r.resetAsk.cancel()
        who = "Shammy-Fakerealm"
        for (l in listOf("es", "fr")) {
            Tr.setChoice(app, l); r = room(); openReset(r)
            shot("reset_11_${l}_open")
            tap(r, reset(), button(r, GeneralTiles.Row.RESET_ALL)); shot("reset_12_${l}_scope")
            press(r, "PAD1"); shot("reset_13_${l}_confirm"); r.resetAsk.cancel()
            tap(r, reset(), button(r, GeneralTiles.Row.RESET_SETTINGS)); shot("reset_14_${l}_ask_settings"); r.resetAsk.cancel()
        }
        Tr.setChoice(app, "auto")
        for (id in listOf("oled_rose", "dark_silver")) {
            Theme.use(id); r = room(); openReset(r)
            tap(r, reset(), button(r, GeneralTiles.Row.RESET_LAYOUTS)); shot("reset_15_theme_$id"); r.resetAsk.cancel()
        }
        Theme.use("classic")
    }
}
