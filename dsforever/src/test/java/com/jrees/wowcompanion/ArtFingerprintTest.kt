package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 2026-11-07: an art step re-runs only when the files it was made from changed (ArtFingerprint) */
class ArtFingerprintTest {
    private val keys = mapOf(1 to byteArrayOf(1, 2), 2 to byteArrayOf(3, 4), 3 to byteArrayOf(5, 6))
    private val files = mapOf("interface\\icons\\a.blp" to byteArrayOf(9, 9))
    private fun fp(k: Map<Int, ByteArray> = keys, f: Map<String, ByteArray> = files, fids: List<Int> = listOf(1, 2, 3),
                   names: List<String> = listOf("Interface/Icons/A.blp")) =
        ArtFingerprint.of(fids, names, { k[it] }, { f[it] })

    @Test fun sameFilesSameFingerprintWhateverTheOrder() {
        assertEquals(fp(), fp(fids = listOf(3, 1, 2)))
        assertEquals(fp(), ArtFingerprint.of(listOf(2, 1, 3), listOf("interface\\icons\\a.blp"), { keys[it] }, { files[it] }))
    }

    @Test fun aChangedFileChangesIt() {
        assertNotEquals(fp(), fp(k = keys + (2 to byteArrayOf(7, 7))))                  // a file's content key changed
        assertNotEquals(fp(), fp(f = mapOf("interface\\icons\\a.blp" to byteArrayOf(1))))   // a path's bytes changed
        assertNotEquals(fp(), fp(k = keys - 3))                                           // a file gone from the build
        assertNotEquals(fp(), fp(fids = listOf(1, 2)))                                    // another set of files
    }

    @Test fun theRecordRoundTrips() {
        val r = ArtFingerprint.Record(listOf(3, 1), listOf("x\\y.blp"), "abc")
        assertEquals(r, ArtFingerprint.fromJson(ArtFingerprint.toJson(r)))
        assertEquals(null, ArtFingerprint.fromJson("not json"))
    }
}
