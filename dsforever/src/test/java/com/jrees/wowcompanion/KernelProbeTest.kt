package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * issue #358 review ("Character names: Cyrillic characters are displayed as empty squares"): the add-on kernel's self-test (Safety.lua
 * k5: cc / utf8 / sink) reaches the report as one line; DiagLog's per-type summary of "cap" documents never carried it.
 */
class KernelProbeTest {
    @Test fun theK5SelfTestIsOneLine() {
        val m = JSONObject("""{"type":"cap","kernel":{"v":"k5","cc":6,"utf8":true,"sink":"sink1","hasIsSecret":true,"int31":"ok"}}""")
        assertEquals("add-on kernel k5: utf8 whole, %c high bytes 6, sink sink1", kernelProbeLine(m))
        val ascii = JSONObject("""{"type":"cap","kernel":{"v":"k5","cc":0,"utf8":true,"sink":"sink1"}}""")
        assertEquals("add-on kernel k5: utf8 whole, %c high bytes 0, sink sink1", kernelProbeLine(ascii))
    }

    @Test fun anOlderOrBrokenKernelStillSaysWhatItCan() {
        // k4 (1.0.32): no cc / utf8 / sink
        assertEquals("add-on kernel k4: utf8 ?, %c high bytes ?, sink ?",
            kernelProbeLine(JSONObject("""{"type":"cap","kernel":{"v":"k4","int31":"ok"}}""")))
        assertEquals("add-on kernel k5 (its self-test FAILED): utf8 BROKEN, %c high bytes ?, sink ?",
            kernelProbeLine(JSONObject("""{"type":"cap","kernel":{"v":"k5","failed":true,"utf8":false,"err":"Safety.lua:1: x"}}""")))
        // only our own short version words are written, never another string
        assertEquals("add-on kernel ?: utf8 whole, %c high bytes 6, sink ?",
            kernelProbeLine(JSONObject("""{"type":"cap","kernel":{"v":"k5 Анна-Realm","cc":6,"utf8":true,"sink":"a b"}}""")))
        assertEquals("add-on kernel ?: utf8 ?, %c high bytes ?, sink ?",
            kernelProbeLine(JSONObject("""{"type":"cap","kernel":{"v":"Анна","sink":"K5"}}""")))
    }

    @Test fun otherCapDocumentsAreNotIt() {
        assertNull(kernelProbeLine(JSONObject("""{"type":"cap","probe":"ours","loaded":10}""")))
        assertNull(kernelProbeLine(JSONObject("""{"type":"cap","harvest":{"repairFn":true}}""")))
        assertNull(kernelProbeLine(JSONObject("""{"type":"full","kernel":{"v":"k5"}}""")))
    }
}
