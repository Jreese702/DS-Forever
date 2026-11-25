package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * EDIT HOME'S TOP-SCREEN PREVIEW, THE APP'S HALF (the user on test 1530, 2026-11-25: "When choosing the top screen options in combat it
 * should also display the top screen ui so that its easy to visualize. Likewise the party mode should show a full party on the top screen
 * so its easy to visualize"): which code the top screen is asked to show for each tab and pick ([EditPreview.code]: In combat only with
 * a Top-screen pick, Party mode always - a full party, the Wings with its own Top pick; Out of combat and no session never), the send /
 * wait / match rules on the add-on's `pv` ([EditPreview.decide]: nothing to an add-on without it, nothing in a fight, re-sent after
 * RESEND_MS, OFF sent while the add-on still shows one), the page-3 taps, the padmap's `pv`, and the app's table of codes kept equal to
 * the add-on's (PadMap m198's PV_LOOK). Pure.
 */
class EditPreviewTest {
    private val C = HomeTab.COMBAT
    private val P = HomeTab.PARTY
    private val O = HomeTab.OUT
    private val top = HomeLayout(fight = HomeLayout.FightBand.TOP)
    private val topCast = top.copy(fightCast = HomeLayout.FightCast.TOP)

    @Test fun inCombatPreviewsOnlyATopScreenPick() {
        assertEquals("Out of combat: never", EditPreview.OFF, EditPreview.code(O, topCast, true, 1, 1))
        assertEquals("no live session: never", EditPreview.OFF, EditPreview.code(null, topCast, true, 1, 1))
        for (f in listOf(HomeLayout.FightBand.GROW, HomeLayout.FightBand.SAME))
            assertEquals("In combat, Combat bars $f: nothing on top", EditPreview.OFF, EditPreview.code(C, HomeLayout(fight = f), true, 1, 1))
        assertEquals("Top screen, Cast bar Bottom: the Wings", 33, EditPreview.code(C, top, true, 1, 1))
        assertEquals("+ Cast bar Top, Swing off: our bar", 34, EditPreview.code(C, topCast, false, 1, 1))
        assertEquals("+ the swing", 35, EditPreview.code(C, topCast, true, 1, 1))
        // the add-on's words (TopCombatBar.previewed's rule): no `ct` = no Wings, no `cc` = no top bar
        assertEquals("no ct: nothing", EditPreview.OFF, EditPreview.code(C, topCast, true, null, 1))
        assertEquals("no cc: the Wings alone", 33, EditPreview.code(C, topCast, true, 0, null))
        assertEquals("ct 0 (the saved flag off) still previews: it is the pick being edited", 35, EditPreview.code(C, topCast, true, 0, 0))
    }

    @Test fun partyModePreviewsAFullPartyAlways() {
        assertEquals("Bottom screen: the party alone", 36, EditPreview.code(P, HomeLayout(fight = HomeLayout.FightBand.GROW), true, 1, 1))
        assertEquals("No grow: the party alone", 36, EditPreview.code(P, HomeLayout(fight = HomeLayout.FightBand.SAME), false, 1, 1))
        assertEquals("Top screen: + the Wings", 37, EditPreview.code(P, top, true, 1, 1))
        assertEquals("+ the cast bar", 38, EditPreview.code(P, topCast, false, 1, 1))
        assertEquals("+ its swing", 39, EditPreview.code(P, topCast, true, 1, 1))
        assertEquals("no ct: the party alone", 36, EditPreview.code(P, topCast, true, null, null))
        for (c in 36..39) assertTrue(EditPreview.look(c)!!.party)
        for (c in 33..35) assertTrue(!EditPreview.look(c)!!.party && EditPreview.look(c)!!.wings)
        assertNull(EditPreview.look(EditPreview.OFF))
    }

    /** the record's own tabs: the In combat tab's page and the Party mode tab's page each give their own code */
    @Test fun eachTabReadsItsOwnPage() {
        val rec = HomeLayout().onTab(C) { it.copy(fight = HomeLayout.FightBand.TOP, fightCast = HomeLayout.FightCast.TOP) }
            .onTab(P) { it.copy(fight = HomeLayout.FightBand.GROW) }
        assertEquals(35, EditPreview.code(C, rec.forTab(C), true, 1, 1))
        assertEquals(36, EditPreview.code(P, rec.forTab(P), true, 1, 1))
        assertEquals(EditPreview.OFF, EditPreview.code(O, rec.forTab(O), true, 1, 1))
    }

    @Test fun theSendRules() {
        val A = EditPreview.Act.Matched
        assertEquals("an add-on without `pv`: nothing", EditPreview.Act.Wait, EditPreview.decide(35, null, false, null))
        assertEquals(A, EditPreview.decide(35, 35, false, null))
        assertEquals("OFF matches 0 (none shown)", A, EditPreview.decide(EditPreview.OFF, 0, false, null))
        assertEquals(EditPreview.Act.Send(35), EditPreview.decide(35, 0, false, null))
        assertEquals("a change of pick: the new code", EditPreview.Act.Send(34), EditPreview.decide(34, 35, false, null))
        assertEquals("Edit closed with the preview still up: OFF sent", EditPreview.Act.Send(EditPreview.OFF), EditPreview.decide(EditPreview.OFF, 37, false, null))
        assertEquals("a fight: nothing (the add-on drops it by itself)", EditPreview.Act.Wait, EditPreview.decide(35, 0, true, null))
        assertEquals("just sent: wait", EditPreview.Act.Wait, EditPreview.decide(35, 0, false, EditPreview.RESEND_MS - 1))
        assertEquals("unanswered: again", EditPreview.Act.Send(35), EditPreview.decide(35, 0, false, EditPreview.RESEND_MS))
        // the add-on's lease (PadMap PV_TTL 900 s): a preview still up is renewed before it lapses; OFF never is, nor in a fight
        assertEquals(A, EditPreview.decide(35, 35, false, EditPreview.RENEW_MS - 1))
        assertEquals("renewed", EditPreview.Act.Send(35), EditPreview.decide(35, 35, false, EditPreview.RENEW_MS))
        assertEquals(A, EditPreview.decide(EditPreview.OFF, 0, false, EditPreview.RENEW_MS * 2))
        assertEquals(A, EditPreview.decide(35, 35, true, EditPreview.RENEW_MS * 2))
        assertTrue("renewed inside the add-on's lease", EditPreview.RENEW_MS < 900_000L)
    }

    /**
     * THE REVIEW'S CATCH: page-3 codes 37 / 38 / 39 tap the very cell the prefix (padtab 39, 38, 37) tapped one to three taps before, and
     * TapPad drops a second press on one cell within REPEAT (0.25 s); a batch sent at the tap worker's own pace (~70 ms a tap) lost them.
     * The preview's batch is SPACED: every tap TapSpacing.MIN_MS apart (the 0.25 s plus a slow frame's margin), so every code's repeat
     * of a prefix cell is at least that far from it; and its send stamp is the batch's END, so the other page-3 checks wait it out
     */
    /** the 1532 audit: a preview still wanted is renewed RENEW_MS after its SEND (or after it was first seen up), never RENEW_MS after
     *  the latest check - a check at send + 599 s used to push the renewal to about 1199 s, past PadMap's 900 s lease */
    @Test fun theRenewalCountsFromTheSend() {
        val s = 50_000L
        assertEquals(EditPreview.RENEW_MS - 100L, EditPreview.renewDelay(s + 100L, s))
        assertEquals("a check just before it: a second later, not 600 s", 1_000L, EditPreview.renewDelay(s + 599_500L, s))
        assertEquals("past it: soon", 1_000L, EditPreview.renewDelay(s + 700_000L, s))
        assertEquals("never sent: from now", EditPreview.RENEW_MS, EditPreview.renewDelay(s, null))
        assertTrue("before the add-on's 900 s lease", EditPreview.RENEW_MS + 1_000L < 900_000L)
        val host = File(listOf(File("../app"), File("app")).first { it.isDirectory }.parentFile!!.absoluteFile,
            "companion/app/src/main/java/com/jrees/wowcompanion/CompanionHost.kt").readText()
        assertTrue("CompanionHost renews from the send", host.contains("ui.postDelayed(pvAgain, EditPreview.renewDelay(now, pvSentAt ?: pvUpAt))"))
    }

    @Test fun thePreviewsBatchIsSpacedPastTheRepeatGuard() {
        assertTrue(EditPreview.GAP_MS >= TapSpacing.MIN_MS && TapSpacing.MIN_MS >= 250L)
        for (code in EditPreview.OFF..PadMapModel.P3_PV_LAST) {
            val cells = EditPreview.cells(code)
            for (i in cells.indices) for (j in i + 1 until cells.size) if (cells[i] == cells[j])
                assertTrue("code $code: cell ${cells[i]} again ${(j - i) * EditPreview.GAP_MS} ms later", (j - i) * EditPreview.GAP_MS >= 250L + TapSpacing.FRAME_MS)
        }
        assertTrue("codes 37-39 do repeat a prefix cell", (37..39).all { c -> EditPreview.cells(c).count { it == ("padtab" to c) } == 2 })
        val f = listOf("../companion/app/src/main/java/com/jrees/wowcompanion/CompanionHost.kt",
            "companion/app/src/main/java/com/jrees/wowcompanion/CompanionHost.kt").map { File(it) }.first { it.isFile }
        val host = f.readText()
        val body = host.substring(host.indexOf("private fun previewCheck(")).substringBefore("\n    }\n")
        assertTrue("sent spaced", body.contains("TapBridge.tapsSpaced(this, talentsPad ?: spellsPad, EditPreview.cells(a.code), EditPreview.GAP_MS)"))
        assertTrue("stamped at the batch's end", body.contains("pvSentAt = now + EditPreview.BATCH_MS"))
        // (the 1532 audit: one guard over every page-3 sender's stamp - HoldCastTest.everyPageThreeSenderWaitsOutEveryOther)
        assertTrue("the guard reads the preview's stamp", host.substringAfter("private fun page3Busy(").substringBefore("\n\n").contains("\"pv\" to pvSentAt"))
        for (check in listOf("mirrorCheck", "topCheck", "castCheck", "swingCheck", "holdTicksCheck"))
            assertTrue("$check waits out the preview's batch", host.substring(host.indexOf("private fun $check(")).substringBefore("\n    }\n").contains("page3Busy(now, "))
    }

    @Test fun theChannel() {
        assertEquals(listOf("padtab" to 39, "padtab" to 38, "padtab" to 37, "padtab" to 35), EditPreview.cells(35).map { it.first to it.second })
        assertEquals(32..39, PadMapModel.PAGE3_CODES["edit preview"])
        val d = PadMapModel.parse(org.json.JSONObject("""{"type":"padmap","v":"m198","m":{"page":1,"sets":[],"dpad":{"on":true,"want":true,"ct":1,"pv":37}}}"""))!!.dpad!!
        assertEquals(37, d.pv)
        val old = PadMapModel.parse(org.json.JSONObject("""{"type":"padmap","v":"m197","m":{"page":1,"sets":[],"dpad":{"on":true,"want":true,"ct":1}}}"""))!!.dpad!!
        assertNull("an older add-on: no pv", old.pv)
    }

    /** the add-on's table (PadMap m198 R.PV_LOOK) says what each code shows: the app's [EditPreview.look] is the same */
    @Test fun theCodesMeanTheSameOnBothSides() {
        val f = listOf("../companion/app/src/main/assets/companion/addons/WowCompanionSpells/PadMap.lua",
            "companion/app/src/main/assets/companion/addons/WowCompanionSpells/PadMap.lua").map { File(it) }.first { it.isFile }
        val src = f.readText()
        val block = Regex("""R\.PV_LOOK = \{(.*?)\n\S""", RegexOption.DOT_MATCHES_ALL).find(src)!!.groupValues[1]
        val seen = HashSet<Int>()
        for (m in Regex("""\[(\d+)\] = \{([^}]*)\}""").findAll(block)) {
            val c = m.groupValues[1].toInt(); val body = m.groupValues[2]
            val l = EditPreview.look(c)!!
            seen += c
            assertEquals("code $c party", l.party, "party = true" in body)
            assertEquals("code $c wings", l.wings, "wings = true" in body)
            assertEquals("code $c cast", l.cast, "cast = true" in body)
            assertEquals("code $c swing", l.swing, "swing = true" in body)
        }
        assertEquals((33..39).toSet(), seen)
    }
}
