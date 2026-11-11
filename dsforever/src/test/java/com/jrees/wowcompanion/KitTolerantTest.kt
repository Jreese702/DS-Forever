package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ONE UNREADABLE TEXTURE NO LONGER LOCKS PLAY (issues #35 #36 #38 #42, 2026-11-11): the kit leaves a non-core picture that does not read
 * to the app's stand-in (logged with its fid and GameFiles.lastReadWhy) and fails only for KitBuild.CORE or a plainly broken game folder.
 */
class KitTolerantTest {
    private val bad = setOf(4631337, 7960142)
    private val logs = ArrayList<String>()
    private var lastWhy: String? = null

    private fun kit() = KitBuild(
        read = { fid -> if (fid in bad) { lastWhy = "file $fid: read failed (status 4) (CascLib error 95)"; null } else { lastWhy = null; null } },
        write = { _, _ -> },
        asset = { null },
        log = { logs.add(it) },
        why = { lastWhy })

    private fun rect(fid: Int, member: String) = KitBuild.Rect(fid, 0, 0, 8, 8, member, member)

    @Test fun issue36TalentBackgroundsOnAnUnreadableSheetDoNotFailTheKit() {
        val k = kit()
        k.cutAll(listOf(
            KitBuild.Cut(rect(4631337, "talents-background-paladin-holy"), "talentbg_paladin_holy") {},
            KitBuild.Cut(rect(4631337, "talents-background-paladin-protection"), "talentbg_paladin_protection") {},
        ), "kit") {}
        k.judge(780)
        k.requireComplete("kit")                                      // no throw: Play goes on
        assertEquals(2, k.degraded.size)
        assertTrue(k.missing.isEmpty())
        val line = logs.first { it.contains("talentbg_paladin_holy") }
        assertTrue(line, line.startsWith("placeholder for talentbg_paladin_holy"))
        assertTrue(line, line.contains("fid 4631337") && line.contains("CascLib error 95"))
        assertTrue(k.summary("kit"), k.summary("kit").contains("2 left to the app's stand-ins"))
    }

    @Test fun issue38MicroButtonSheetIsNotCore() {
        val k = kit()
        val names = listOf("chat", "exclamation", "professions", "spellbook", "talents")
        k.cutAll(names.map { KitBuild.Cut(rect(7960142, "$it-up-c60"), "micro_$it") {} }, "kit") {}
        k.judge(780)
        k.requireComplete("kit")
        assertEquals(5, k.degraded.size)
    }

    @Test fun aCorePieceStillFailsTheStep() {
        val k = kit()
        k.cutAll(listOf(KitBuild.Cut(rect(7960142, "_128-redbutton-center"), "button_up_center") {}), "kit") {}
        assertEquals(1, k.missing.size)
        assertTrue(k.missing[0], k.missing[0].contains("fid 7960142"))
        try { k.requireComplete("kit"); fail("a core piece must fail the kit") } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("CascLib error 95"))
        }
    }

    @Test fun theMainFontIsCoreTheOthersAreNot() {
        val k = kit()
        k.lost("arialn.ttf", "font 615958 arialn.ttf", 4631337)
        k.requireComplete("kit")
        k.lost("frizqt__.ttf", "font 615960 frizqt__.ttf", 4631337)
        try { k.requireComplete("kit"); fail() } catch (_: IllegalStateException) {}
    }

    @Test fun aBrokenGameFolderStillFails() {
        val k = kit()
        repeat(60) { k.lost("piece_$it", "texture $it") }
        k.judge(100)
        try { k.requireComplete("kit"); fail("most pictures lost = the game's files are broken") } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("look damaged"))
        }
    }

    @Test fun aReadThatDecodesBadlyNamesItsFidWithoutAStaleReason() {
        val k = kit()
        k.lost("loot_dice", "online 130772 loot_dice", 130772)
        assertTrue(k.degraded[0], k.degraded[0].contains("fid 130772: read, but not a picture"))
    }

    @Test fun aDegradedBuildStampsDegraded() {
        val k = kit()
        assertNull(KitSteps.degradedKey(k))
        k.lost("talentbg_paladin_holy", "x", 4631337)
        assertEquals("degraded:1", KitSteps.degradedKey(k))
        assertTrue(KitSteps.degradedKey(k)!!.startsWith(KitSteps.DEGRADED))
    }
}
