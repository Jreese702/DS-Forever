package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- review of #35 #36 #38 (2026-11-11): a failed download is tried again, never kept until the next game patch

    private val fetchWhy = "file 4631337: not on this device, and downloading it failed (abc123: timeout)"

    @Test fun aFailedDownloadIsCountedApartFromAnUnreadablePicture() {
        val k = kit()
        lastWhy = fetchWhy
        k.lost("talentbg_paladin_holy", "x", 4631337)
        lastWhy = "file 7960142: read failed (status 4) (CascLib error 95)"
        k.lost("micro_chat", "y", 7960142)
        assertEquals(2, k.degraded.size)
        assertEquals(1, k.fetchLost)
        assertEquals("degraded:2:fetch:0", KitSteps.degradedKey(k))
    }

    @Test fun theGameFilesFetchWordsAreTheOnesTheKitLooksFor() = assertTrue(KitBuild.transient(fetchWhy))

    @Test fun aDegradedStampWithFailedDownloadsIsDueAgainForTheSameBuild() {
        val at = 1_000_000L
        val key = KitSteps.degradedKey(3, 1, 0)
        assertEquals(0, KitSteps.fetchRetries(key))
        assertTrue("the next launch tries again", KitSteps.retryDue(key, at, at + 1))
        val again = KitSteps.degradedKey(3, 1, 1)
        assertFalse("then it backs off", KitSteps.retryDue(again, at, at + 60_000L))
        assertTrue(KitSteps.retryDue(again, at, at + 11 * 60_000L))
        assertTrue("the back-off stops growing", KitSteps.retryDue(KitSteps.degradedKey(3, 1, 40), at, at + 24 * 60 * 60_000L))
        // unreadable in the game's own files: only a new build builds it again
        assertFalse(KitSteps.retryDue(KitSteps.degradedKey(3, 0, 0), at, at + 365L * 24 * 60 * 60_000L))
        assertFalse(KitSteps.retryDue(null, at, at + 1))
        assertFalse(KitSteps.retryDue("some-icons-key", at, at + 1))
    }

    @Test fun aRetryIsBackgroundWorkEvenForABeforePlayStep() {
        assertFalse("never before Play", ArtHarvest.queued(stepBeforePlay = true, query = true, stale = false, retryDue = true))
        assertTrue(ArtHarvest.queued(stepBeforePlay = true, query = false, stale = false, retryDue = true))
        assertTrue(ArtHarvest.queued(stepBeforePlay = true, query = null, stale = false, retryDue = true))
        assertFalse(ArtHarvest.queued(stepBeforePlay = true, query = false, stale = false, retryDue = false))
        // a step not built for this build: its own queue, as before
        assertTrue(ArtHarvest.queued(stepBeforePlay = true, query = true, stale = true, retryDue = false))
        assertFalse(ArtHarvest.queued(stepBeforePlay = true, query = false, stale = true, retryDue = false))
        assertTrue(ArtHarvest.queued(stepBeforePlay = false, query = false, stale = true, retryDue = false))
    }

    // ---- the before-Play icons step (first install) uses the same rule

    private fun blp(): ByteArray {
        val w = 4; val h = 4; val b = ByteArray(148 + w * h * 4)
        "BLP2".toByteArray().copyInto(b)
        b[8] = 3
        fun put(o: Int, v: Int) { for (i in 0..3) b[o + i] = (v shr (8 * i)).toByte() }
        put(12, w); put(16, h); put(20, 148); put(84, w * h * 4)
        for (i in 148 until b.size) b[i] = 0x7F
        return b
    }

    private fun icons(fids: List<Int>, unreadable: Set<Int>, why: String, out: java.io.File, tolerant: Boolean): KitBuild {
        val reasons = HashMap<Int, String>()
        val k = KitBuild(
            read = { fid -> if (fid in unreadable) { reasons[fid] = "file $fid: $why"; null } else blp() },
            write = { _, _ -> }, asset = { null }, log = { logs.add(it) }, whyFor = { synchronized(reasons) { reasons[it] } })
        k.icons(JSONObject().put("tile", 4).put("fids", JSONArray(fids)), out, tolerant = tolerant) { _, _ -> byteArrayOf(1, 2) }
        return k
    }

    @Test fun oneUnreadableIconNoLongerFailsTheBeforePlayIcons() {
        val out = java.io.File(java.nio.file.Files.createTempDirectory("icons").toFile(), "icons.pak").apply { deleteOnExit() }
        val k = icons((1..10).toList(), setOf(4), "read failed (status 4) (CascLib error 95)", out, tolerant = true)
        assertTrue(k.missing.isEmpty())
        assertEquals(1, k.degraded.size)
        assertTrue(k.degraded[0], k.degraded[0].contains("icon 4") && k.degraded[0].contains("CascLib error 95"))
        assertEquals(0, k.fetchLost)
        assertTrue(out.length() > 0)
        assertTrue(logs.any { it.startsWith("placeholder for icon 4") })
    }

    @Test fun aFailedIconDownloadIsCountedForTheRetry() {
        val out = java.io.File(java.nio.file.Files.createTempDirectory("icons").toFile(), "icons.pak").apply { deleteOnExit() }
        val k = icons((1..10).toList(), setOf(4), "not on this device, and downloading it failed (x: no answer)", out, tolerant = true)
        assertEquals(1, k.fetchLost)
    }

    @Test fun mostIconsLostStillFailsAndTheStrictModeIsUnchanged() {
        val out = java.io.File(java.nio.file.Files.createTempDirectory("icons").toFile(), "icons.pak").apply { deleteOnExit() }
        try { icons((1..10).toList(), (1..6).toSet(), "read failed", out, tolerant = true); fail() } catch (_: IllegalStateException) {}
        try { icons((1..10).toList(), setOf(4), "read failed", out, tolerant = false); fail() } catch (_: IllegalStateException) {}
    }
}
