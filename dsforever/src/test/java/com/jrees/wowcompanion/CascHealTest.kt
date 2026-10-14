package com.jrees.wowcompanion

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** p-casclib-1000 (a player's report 2026-10-14), off-device: the cache's self-heal and the checks on every download */
class CascHealTest {
    private val dir: File = Files.createTempDirectory("casc").toFile()
    private fun file(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }
    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    // ---- when the cache is dropped

    @Test fun aDamagedFileHealsOncePerRun() {
        assertTrue(CascHeal.shouldHeal(1000, healedThisRun = false))
        assertTrue(CascHeal.shouldHeal(1004, healedThisRun = false))
        assertTrue(CascHeal.shouldHeal(1002, healedThisRun = false))
        assertFalse(CascHeal.shouldHeal(1000, healedThisRun = true))          // never a loop
        assertFalse(CascHeal.shouldHeal(2, healedThisRun = false))            // a missing file is not damage
        assertTrue(CascHeal.shouldHeal(0, healedThisRun = false, readSawDamage = true))
        assertFalse(CascHeal.shouldHeal(0, healedThisRun = true, readSawDamage = true))
    }

    @Test fun stillDamagedSaysWhatToDo() =
        assertEquals("The game's files on this device look damaged: copy the Data folder again (CascLib error 1000: bad format)",
            CascHeal.damagedMessage(1000))

    // ---- every download checked before it is used

    @Test fun aShortDownloadIsRefused() {
        val f = file("x", ByteArray(100) { 1 })
        assertEquals("100 of 200 bytes", CascHeal.problem("data/ab/cd/x", f, announced = 200))
        assertEquals("empty", CascHeal.problem("data/ab/cd/x", file("e", ByteArray(0))))
    }

    @Test fun aConfigMustMatchItsName() {
        val body = "archives = 0123\n".toByteArray()
        val good = file(md5(body), body)
        assertNull(CascHeal.problem("config/ab/cd/${good.name}", good))
        val bad = file("0123456789abcdef0123456789abcdef", body)
        assertEquals("its content does not match its name", CascHeal.problem("config/01/23/${bad.name}", bad))
    }

    @Test fun aDataFileMustBeBlte() {
        assertNull(CascHeal.problem("data/ab/cd/k", file("k", "BLTE".toByteArray() + ByteArray(20))))
        assertEquals("not a BLTE file", CascHeal.problem("data/ab/cd/h", file("h", "<html>".toByteArray())))
    }

    /** an archive index with [blocks] 4 KiB blocks, 16-byte keys, 4-byte sizes and offsets, 8-byte checksums */
    private fun index(blocks: Int, chk: Int = 8): ByteArray {
        val keyBytes = 16
        val footer = ByteArray(chk + 12 + chk)
        val o = chk + 3
        footer[o - 2] = 1                       // version
        footer[o] = 4                           // block size, KiB
        footer[o + 1] = 4; footer[o + 2] = 4; footer[o + 3] = keyBytes.toByte(); footer[o + 4] = chk.toByte()
        return ByteArray(blocks * 4096 + blocks * (keyBytes + chk)) + footer
    }

    @Test fun anIndexMustHaveItsFooter() {
        assertNull(CascHeal.problem("data/ab/cd/x.index", file("i.index", index(3))))
        val cut = index(3).let { it.copyOf(it.size - 10) }
        assertTrue(CascHeal.problem("data/ab/cd/y.index", file("j.index", cut)) != null)
        assertEquals("too short for an index", CascHeal.problem("data/ab/cd/z.index", file("k.index", ByteArray(10) { 1 })))
    }
}
