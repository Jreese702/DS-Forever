package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
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
import java.time.Duration

/**
 * loot-pop (the user, 2026-11-16: "The loot roll pop up is WAY too intrusive during a fight / there should be a smaller version that pops
 * up over the minimap and gets bigger when transitioning out of combat. / during combat it should accept touch input and show things per
 * item/roll"): the real DialogView in a fight -
 *  - the SMALL PANEL over the minimap: wants no controller (no ENTER: the fight keeps its presses), covers nothing, a touch outside it
 *    reaches Home, a finger on a row's Need answers THAT roll on the first touch and the next row moves up at once;
 *  - its open button: the full window (the controller then: wantsPad);
 *  - the fight's end: the panel GROWS into the full window;
 *  - a bind-on-pickup Need: the game's confirm on its row; Okay pressed before the add-on's document carries the pop-up goes the moment
 *    it does (the pop-up's own cell), never lost;
 *  - the app's controller in a fight (full window, room mode): A twice = two rolls, no lock between them;
 *  - out of a fight the controller's A is the game's first: shown at once, its cell tapped only if the game did not take it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DialogViewRollsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 1240; private val h = 1080
    private val map = RectF(624f, 120f, 1232f, 1000f)

    private fun gameNow(v: DialogView): Long =
        DialogView::class.java.getDeclaredMethod("gameNow").apply { isAccessible = true }.invoke(v) as Long

    private fun roll(id: Int, bop: Boolean = false, ask: String? = null) = JSONObject().put("id", id).put("name", "Item $id").put("q", 2)
        .put("need", true).put("greed", true).put("left", 55).put("dur", 60).put("icon", 135274).put("bop", bop)
        .put("cells", JSONObject().put("need", id * 10 + 1).put("greed", id * 10 + 2).put("pass", id * 10))
        .apply { if (ask != null) put("ask", ask).put("askI", 1) }

    private fun doc(v: DialogView, rolls: List<JSONObject>, list: JSONArray = JSONArray()) = JSONObject().put("type", "dialogs").put("dv", "d34")
        .put("mode", true).put("list", list).put("touch", true)
        .put("rolls", JSONArray().also { a -> rolls.forEachIndexed { i, r -> a.put(r.put("sel", i == 0)) } })
        .put("life", JSONObject().put("col", 3).put("every", 5)).put("pad", JSONObject().put("rows", JSONObject().put("isell", 16).put("idlg", 18).put("iact", 14)))
        .put("_gt", gameNow(v))

    /** the game's bind confirm in the dialogs list (Dialogs d34: names its roll; slot 1's cells 0 / 1) */
    private fun confirm(id: Int) = JSONArray().put(JSONObject().put("i", 1).put("which", "CONFIRM_LOOT_ROLL").put("roll", id).put("rollType", 1)
        .put("text", "Looting Item $id will bind it to you.").put("focus", true)
        .put("buttons", JSONArray().put(JSONObject().put("k", 1).put("label", "Okay").put("on", true).put("key", "PAD1"))
            .put(JSONObject().put("k", 2).put("label", "Cancel").put("on", true).put("key", "PAD2")))
        .put("cells", JSONObject().put("b1", 0).put("b2", 1)))

    private fun view(fight: Boolean): DialogView {
        Tr.init(app)
        val v = DialogView(app)
        v.dryRun = true
        v.fightNow = { fightState }
        fightState = fight
        v.mapBox = { RectF(map) }
        return v
    }
    private var fightState = false

    private fun draw(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        v.draw(Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)))
    }

    @Suppress("UNCHECKED_CAST")
    private fun hits(v: DialogView): List<Pair<RectF, String>> =
        (DialogView::class.java.getDeclaredField("hits").apply { isAccessible = true }.get(v) as ArrayList<Pair<RectF, String>>).toList()
    private fun hit(v: DialogView, name: String): RectF = hits(v).last { it.second == name }.first
    private fun has(v: DialogView, name: String) = hits(v).any { it.second == name }

    /** a finger: DOWN and UP at [x], [y]; the DOWN's answer (false = the view took no touch: it reaches what is behind) */
    private fun tap(v: DialogView, x: Float, y: Float): Boolean {
        val t0 = SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0)
        val took = v.dispatchTouchEvent(d); d.recycle()
        if (took) { val u = MotionEvent.obtain(t0, t0 + 40, MotionEvent.ACTION_UP, x, y, 0); v.dispatchTouchEvent(u); u.recycle() }
        return took
    }
    private fun tap(v: DialogView, r: RectF) = tap(v, r.centerX(), r.centerY())

    // both clocks: the shadowed one (the view's System.currentTimeMillis) and the real one (its game clock is java.util.Calendar's)
    private fun later(ms: Long) { ShadowSystemClock.advanceBy(Duration.ofMillis(ms)); Thread.sleep(ms); ShadowLooper.idleMainLooper() }
    private fun press(v: DialogView, slot: Int) { v.onPadHeld(1 shl slot); v.onPadHeld(0) }

    @Test fun inAFightTheSmallPanelOverTheMinimapAnswersOnTheFirstTouch() {
        val v = view(fight = true)
        v.update(doc(v, listOf(roll(1), roll(2), roll(3))), true)
        draw(v)
        assertEquals(DialogView.RollMode.COMPACT, v.rollMode())
        assertFalse("the fight keeps its controller: no ENTER", v.wantsPad())
        assertFalse("it covers nothing", v.covers())
        assertTrue("it stands over the minimap", hits(v).all { map.contains(it.first.centerX(), it.first.centerY()) })
        assertFalse("a touch outside the panel reaches Home", tap(v, 200f, 600f))
        assertTrue("one row per roll, each with its own buttons", has(v, "roll:1:need") && has(v, "roll:2:greed") && has(v, "roll:3:pass"))
        val row2 = hit(v, "roll:2:need")
        assertTrue(tap(v, hit(v, "roll:1:need")))
        assertEquals("the first touch: roll 1's Need cell", listOf("isell 11 roll need on 1"), v.rollSent)
        draw(v)
        assertFalse("roll 1 gone at once", has(v, "roll:1:need"))
        assertTrue("roll 2 moved up into its place", hit(v, "roll:2:need").top < row2.top)
        later(150)
        assertTrue(tap(v, hit(v, "roll:2:pass")))
        assertEquals("the next touch 150 ms later answers roll 2 (no lock)", "isell 20 roll pass on 2", v.rollSent.last())
    }

    @Test fun theOpenButtonGivesTheWindowAndTheController() {
        val v = view(fight = true)
        v.update(doc(v, listOf(roll(1))), true)
        draw(v)
        tap(v, hit(v, "rolls:full"))
        draw(v)
        assertEquals(DialogView.RollMode.FULL, v.rollMode())
        assertTrue("opened by the player: the controller now", v.wantsPad())
        // its minimize: back to the small panel (still the fight)
        later(400); draw(v)
        tap(v, hit(v, "rolls:min"))
        assertEquals(DialogView.RollMode.COMPACT, v.rollMode())
    }

    @Test fun theFightsEndGrowsThePanelIntoTheWindow() {
        val v = view(fight = true)
        v.update(doc(v, listOf(roll(1), roll(2))), true)
        draw(v)
        later(300); draw(v)
        fightState = false
        draw(v)
        assertEquals(DialogView.RollMode.FULL, v.rollMode())
        assertTrue("mid-grow: no touch taken", hits(v).isEmpty())
        later(DialogView.GROW_MS + 20); draw(v)
        assertTrue("grown: the window's own buttons", has(v, "roll:1:need") && has(v, "rolls:min"))
    }

    @Test fun aBindOnPickupNeedShowsTheGamesConfirmAndOkayWaitsForIt() {
        val v = view(fight = true)
        v.update(doc(v, listOf(roll(5, bop = true), roll(6))), true)
        draw(v)
        tap(v, hit(v, "roll:5:need"))
        draw(v)
        assertTrue("the game's confirm on roll 5 at once", has(v, "ask:5:ok") && has(v, "ask:5:cancel"))
        later(150)
        tap(v, hit(v, "ask:5:ok"))
        assertEquals("Okay before the pop-up is known: nothing tapped yet", listOf("isell 51 roll need on 5"), v.rollSent)
        draw(v)
        assertFalse("roll 5 gone at once", has(v, "ask:5:ok") || has(v, "roll:5:need"))
        later(150)
        v.update(doc(v, listOf(roll(5, bop = true, ask = "need"), roll(6)), confirm(5)), true)
        assertEquals("the document with the game's pop-up: its Okay cell now", "idlg 0 confirm ok", v.rollSent.last())
        draw(v)
        assertFalse("never a box over the fight", has(v, "b1"))
    }

    @Test fun theAppsControllerInAFightAnswersTwoRollsInARow() {
        val v = view(fight = true)
        v.appOwnsPad = { true }
        v.update(doc(v, listOf(roll(1), roll(2), roll(3))), true)
        draw(v)
        tap(v, hit(v, "rolls:full"))
        draw(v)
        later(300)
        press(v, PadInput.SLOT_FACE_BOTTOM)
        later(200)
        press(v, PadInput.SLOT_FACE_BOTTOM)
        assertEquals("A, A: Need on 1 then Need on 2, no lock", listOf("isell 11 roll need on 1", "isell 21 roll need on 2"), v.rollSent)
    }

    @Test fun outOfAFightTheControllersAIsTheGamesFirst() {
        val v = view(fight = false)
        v.update(doc(v, listOf(roll(1), roll(2))), true)
        draw(v)
        later(300)
        press(v, PadInput.SLOT_FACE_BOTTOM)
        assertTrue("the game's own frame answers: nothing tapped", v.rollSent.isEmpty())
        draw(v)
        assertFalse("shown as answered at once", has(v, "card:1"))
        later(500)
        v.update(doc(v, listOf(roll(1), roll(2))), true)                     // still offered 500 ms later: the game did not take it
        assertEquals(listOf("isell 11 roll need (the game did not take the controller's)"), v.rollSent)
    }

    // ---------------------------------------------------------------- the review's
    /** the host's routing (CompanionHost.onPadHeldAny): the view reads the presses while it takes them, else it only SEES the mask */
    private fun held(v: DialogView, m: Int) { if (v.padTakes()) v.onPadHeld(m) else v.padSeen(m) }
    private fun bit(slot: Int) = 1 shl slot

    @Test fun theReleaseOfThePressThatAnsweredTheLastRollNeverSwallowsTheNextPress() {
        val v = view(fight = false)
        v.update(doc(v, listOf(roll(1))), true)
        draw(v)
        later(300)
        held(v, bit(PadInput.SLOT_FACE_BOTTOM))                                 // A down: Need on the last roll, gone at once
        draw(v)
        assertFalse("roll 1 answered: the view covers nothing now", v.covers())
        held(v, 0)                                                              // A up, while the view reads no press
        later(100)
        v.update(doc(v, listOf(roll(2))), true)                                  // the game had it; a new roll
        draw(v)
        later(300)
        held(v, bit(PadInput.SLOT_FACE_BOTTOM)); held(v, 0)                     // A: Need on roll 2 - the FIRST press counts
        draw(v)
        assertFalse("the next press of A answered roll 2 at once (not swallowed)", has(v, "card:2"))
    }

    @Test fun aButtonHeldThroughTheFightsEndIsNoPressOfTheWindow() {
        val v = view(fight = true)
        v.update(doc(v, listOf(roll(1), roll(2))), true)
        draw(v)
        later(300)
        held(v, bit(PadInput.SLOT_FACE_LEFT))                                   // X held for the fight (the small panel reads nothing)
        fightState = false
        draw(v)                                                                 // the panel grows into the window
        held(v, bit(PadInput.SLOT_FACE_LEFT) or bit(PadInput.SLOT_FACE_RIGHT)) // B pressed mid-grow, X still held
        later(DialogView.GROW_MS + 40); draw(v)
        assertTrue("grown, nothing answered: both rolls still shown", has(v, "card:1") && has(v, "card:2"))
        held(v, bit(PadInput.SLOT_FACE_LEFT) or bit(PadInput.SLOT_FACE_RIGHT) or bit(PadInput.SLOT_FACE_BOTTOM))   // A pressed
        later(500)
        v.update(doc(v, listOf(roll(1), roll(2))), true)                     // the game took none of them
        assertEquals("only A's edge was a press: Need on roll 1 (X held through the end and B mid-grow never rolled)",
            listOf("isell 11 roll need (the game did not take the controller's)"), v.rollSent)
    }

    @Test fun theSmallPanelStaysInsideAShortMinimapModuleAboveTheCastBar() {
        val v = view(fight = true)
        val short = RectF(624f, 425f, 1232f, 785f)                              // the map cut short by the tap-to-cast bar under it
        v.mapBox = { RectF(short) }
        v.update(doc(v, listOf(roll(1), roll(2), roll(3), roll(4))), true)
        draw(v)
        later(400); draw(v)
        assertTrue("every touch of the panel inside the module", hits(v).all { it.first.bottom <= short.bottom + 0.5f })
        assertTrue("the rows that fit have their buttons", has(v, "roll:1:need") && has(v, "roll:2:need"))
        assertFalse("the fourth is counted, not drawn past the module", has(v, "roll:4:need"))
        assertFalse("a touch just under the module (the cast bar) reaches Home", tap(v, 900f, short.bottom + 30f))
    }

    @Test fun inAFightTheGamesFocusedBindConfirmTakesAAndBOnThePanelAlone() {
        val v = view(fight = true)
        v.update(doc(v, listOf(roll(5, bop = true), roll(6))), true)
        draw(v)
        later(400); draw(v)
        tap(v, hit(v, "roll:5:need"))
        later(150)
        v.update(doc(v, listOf(roll(5, bop = true, ask = "need"), roll(6)), confirm(5)), true)
        draw(v)
        assertFalse("the panel still wants no controller", v.wantsPad())
        assertTrue("but the game's pop-up has the focus: A / B are its, read here (never the loot window's under it)", v.padTakes())
        later(150)
        held(v, bit(PadInput.SLOT_FACE_BOTTOM)); held(v, 0)
        draw(v)
        assertFalse("A = the game's Okay: the row gone at once", has(v, "ask:5:ok"))
        assertEquals("nothing tapped: the game's own pop-up answers its A", listOf("isell 51 roll need on 5"), v.rollSent)
    }

    @Test fun aRowMovingUnderTheFingerKeepsItsPress() {
        val v = view(fight = true)
        v.update(doc(v, listOf(roll(1), roll(2), roll(3))), true)
        draw(v)
        later(400); draw(v)
        val at = hit(v, "roll:2:need")
        val t0 = SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, at.centerX(), at.centerY(), 0)
        assertTrue(v.dispatchTouchEvent(d)); d.recycle()
        v.update(doc(v, listOf(roll(2), roll(3))), true)                       // roll 1 ended mid-press: the rows move up
        draw(v)
        val u = MotionEvent.obtain(t0, t0 + 60, MotionEvent.ACTION_UP, at.centerX(), at.centerY(), 0)
        v.dispatchTouchEvent(u); u.recycle()
        assertEquals("the press went to roll 2 (its button when the finger went down), never roll 3 under it now",
            listOf("isell 21 roll need on 2"), v.rollSent)
    }
}
