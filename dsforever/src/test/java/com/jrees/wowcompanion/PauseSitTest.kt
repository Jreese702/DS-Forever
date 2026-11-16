package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SIT CLOSES THE PAUSE WHEEL (the user, 2026-11-16: "pressing the sit button from the pause menu should close the pause menu"), off-device.
 * Up to 1.0.32 D-pad Down sent the tap row's "sit" cell and kept the wheel open (HomePauseView's `stay = true`, copying the game's
 * radial: gamepadradial.lua's D-pad Sit is a context option, ToggleSit with no CloseRadial). Now it is a pick like an emote: the
 * same route on the pad (D-pad Down's release) and by touch (a tap on the "Sit / Stand" words = that press and release).
 */
class PauseSitTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val addons = File(src, "../../../../assets/companion/addons")
    private val view = File(src, "HomePauseView.kt").readText()

    private fun body(text: String, head: String): String {
        val i = text.indexOf(head)
        assertTrue("'$head' not found", i >= 0)
        val end = listOf("\n    /**", "\n    private fun ", "\n    fun ").map { text.indexOf(it, i + head.length) }.filter { it > 0 }.minOrNull()
        return text.substring(i, end ?: text.length)
    }

    // ---------------------------------------------------------------- the model: Down is a pick (a pick = the wheel closes)

    @Test fun dpadDownsReleaseIsTheSitPick() {
        val f = HomePauseModel.Flow()
        assertEquals(HomePauseModel.Act.None, f.dpad(2, down = true))
        assertEquals(HomePauseModel.Act.Pick(HomePauseModel.SIT), f.dpad(2, down = false))
    }

    @Test fun sitIsAGameActionOnTheTapRowsSitCell() {
        val s = HomePauseModel.SIT
        assertEquals(HomePauseModel.Kind.GAME, s.kind)
        assertEquals("sit", s.cmd)
        assertTrue(s.enabled)
        assertFalse("no PvP-style confirm: one press sits", s.confirm)
        assertEquals("Spells' pause row: column 0 = sit", 0, HomePauseModel.col("sit"))
        assertTrue("Radial.lua's pause row: column 0 is sit", File(addons, "WowCompanionSpells/Radial.lua").readText().contains("[0] = \"sit\""))
    }

    @Test fun downSitsFromTheEmotesPageToo() {
        val f = HomePauseModel.Flow()
        f.dpad(0, true); f.dpad(0, false)
        assertEquals(HomePauseModel.EMOTE_PAGE, f.page)
        f.dpad(2, true)
        assertEquals(HomePauseModel.Act.Pick(HomePauseModel.SIT), f.dpad(2, false))
    }

    @Test fun aReleaseWithNoPressStillDoesNothing() = assertEquals(HomePauseModel.Act.None, HomePauseModel.Flow().dpad(2, down = false))

    @Test fun theTouchWordsAreTheSameDirection() {
        // the view's tap on "Sit / Stand": act(flow.dpad(opt, true)); act(flow.dpad(opt, false)) with opt = UiKit.PAUSE_OPT_DOWN
        assertEquals(2, UiKit.PAUSE_OPT_DOWN)
        val f = HomePauseModel.Flow()
        f.dpad(UiKit.PAUSE_OPT_DOWN, true)
        assertEquals(HomePauseModel.Act.Pick(HomePauseModel.SIT), f.dpad(UiKit.PAUSE_OPT_DOWN, false))
        assertTrue("the touch route presses and lets go the option", view.contains("opt >= 0 -> { act(flow.dpad(opt, true)); act(flow.dpad(opt, false)) }"))
    }

    // ---------------------------------------------------------------- every one-shot game action closes, as the game's slices do

    @Test fun everyEnabledSliceIsAPickOnlyGreyedOnesStay() {
        // the game's BasicSegmentHandler:Trigger: an enabled slice's action returns true and the radial hides; a disabled one says its
        // line and the radial stays (ours: Refuse)
        val pages = (0 until HomePauseModel.PAGE_COUNT).map { it } + HomePauseModel.EMOTE_PAGE
        var games = 0
        for (p in pages) {
            for (slot in 0 until 8) {
                val f = HomePauseModel.Flow()
                f.psSkip = true; f.psPrev = true
                if (p == HomePauseModel.EMOTE_PAGE) { f.dpad(0, true); f.dpad(0, false) } else f.turn(p - HomePauseModel.DEFAULT_PAGE)
                val s = f.slices()[slot] ?: continue
                val a = f.touch(slot)
                if (s.enabled) {
                    assertEquals("page $p slot $slot (${s.key})", HomePauseModel.Act.Pick(s), a)
                    if (s.kind == HomePauseModel.Kind.GAME) games++
                } else assertTrue("page $p slot $slot (${s.key}) greyed: stays", a is HomePauseModel.Act.Refuse)
            }
        }
        assertEquals("8 emotes + Game Menu + PvP", 10, games)
    }

    @Test fun theEmotesAreGameActionsOnTheirOwnCells() {
        val e = HomePauseModel.EMOTES.filterNotNull()
        assertEquals(8, e.size)
        for (s in e) {
            assertEquals(HomePauseModel.Kind.GAME, s.kind)
            assertTrue("${s.cmd} has a cell", HomePauseModel.col(s.cmd!!) > 0)
        }
    }

    // ---------------------------------------------------------------- the view: a game action's tap always closes the wheel

    @Test fun theViewHasNoStayOpenRoute() {
        assertFalse("no Act.Sit branch", view.contains("Act.Sit"))
        assertFalse("no stay flag", Regex("""game\([^)]*stay""").containsMatchIn(view))
        val g = body(view, "private fun game(cmd: String)")
        assertTrue("a sent tap closes the wheel, no condition", g.contains("\n        close(\"a pick\")"))
        assertFalse("no if before the close", g.contains("if (!stay)"))
        // the GAME pick goes to game() (PvP's confirm first), so Sit runs the emotes' route
        val p = body(view, "private fun pick(s: HomePauseModel.Slice)")
        assertTrue(p.contains("game(cmd)"))
        // review (feat/wk-sit): the checks above let an early `if (cmd == "sit") return` before the close through. No Sit case on the
        // route at all, and game()'s only early returns are the Game Menu's (it closes first) and an unsent tap's (the wheel stays)
        for ((name, b) in listOf("game" to g, "pick" to p)) {
            assertFalse("$name(): no Sit special case", b.contains("\"sit\"") || Regex("""\bSIT\b""").containsMatchIn(b))
        }
        assertEquals("game(): the Game Menu's return and the unsent tap's, no other", 2, Regex("""\breturn\b""").findAll(g).count())
        assertTrue(g.contains("if (!sent) { say(\"Comes with the next add-on update\"); return }"))
        assertTrue("a Pick goes to pick()", body(view, "private fun act(a: HomePauseModel.Act)").contains("is HomePauseModel.Act.Pick -> pick(a.slice)"))
    }

    @Test fun aRefusalAfterTheWheelHasGoneStillShows() {
        // Sit used to keep the wheel open, so its refusal ("Not here") showed on the wheel's line; the wheel is gone by the answer now:
        // note() = the wheel's line while open, else the line where the game's red line stands
        val ack = body(view, "fun onAck(msg: JSONObject)")
        assertTrue(ack.contains("if (!ok) note("))
        assertFalse(ack.contains("live()"))
        assertTrue(body(view, "fun note(text: String)").contains("if (live()) say(text) else showHint(text)"))
    }
}
