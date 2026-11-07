package com.jrees.wowcompanion

import java.security.MessageDigest

/**
 * WHAT AN ART STEP WAS MADE FROM (the user, 2026-11-07: "why do we need to rebuild icons and art like that on every update? ... maybe can
 * be smaller?"; picked "Yes, build it"). A step stamped for one game build ran again in full on the next build, though almost none of
 * its pictures change between builds. Now every file a step reads is recorded ([Log]: by fileDataID, or by game path), and the step's
 * FINGERPRINT is the digest of those files' CASC content keys (a path read: the digest of its bytes, as no key is known by path). After
 * a game update the step is re-checked first: the same fingerprint = the same output, so it is only re-stamped for the new build.
 * Pure: the keys and bytes come from the caller (GameFiles on the phone, a map in the test).
 */
object ArtFingerprint {
    /** the reads of one step run (GameFiles fills it while it is set) */
    class Log {
        val fids: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet())
        val names: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    }

    /**
     * The digest of [fids] by their content keys ([keyOf]; null = not in this build) and [names] by their bytes ([bytesOf]; null = gone).
     * Order-free. A missing key or file is part of the digest too, so a file that appears or goes changes it.
     */
    fun of(fids: Collection<Int>, names: Collection<String>, keyOf: (Int) -> ByteArray?, bytesOf: (String) -> ByteArray?): String {
        val md = MessageDigest.getInstance("SHA-1")
        for (fid in fids.toSortedSet()) {
            md.update("f$fid=".toByteArray())
            md.update(keyOf(fid) ?: byteArrayOf(0))
        }
        for (n in names.map { it.lowercase().replace('/', '\\') }.toSortedSet()) {
            md.update("n$n=".toByteArray())
            val b = bytesOf(n)
            md.update(if (b == null) byteArrayOf(0) else MessageDigest.getInstance("SHA-1").digest(b))
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** a step's record as kept on disk: its fileDataIDs, its paths and the fingerprint they gave */
    data class Record(val fids: List<Int>, val names: List<String>, val fp: String)

    fun toJson(r: Record): String = org.json.JSONObject()
        .put("fids", org.json.JSONArray(r.fids)).put("names", org.json.JSONArray(r.names)).put("fp", r.fp).toString()

    fun fromJson(s: String): Record? = runCatching {
        val o = org.json.JSONObject(s)
        val f = o.getJSONArray("fids"); val n = o.getJSONArray("names")
        Record((0 until f.length()).map { f.getInt(it) }, (0 until n.length()).map { n.getString(it) }, o.getString("fp"))
    }.getOrNull()
}
