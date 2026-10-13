package com.jrees.wowcompanion

/**
 * THE GAME'S TABLES ON THE DEVICE (the art harvest, 2026-10-09; the user: "Phase 1 now"): a WDC5 DB2 reader, a line-for-line port of the
 * PC tools' tools/icons/db2.py (UI Art's / Map's), so the device reads the same rows the PC harvest read. The layout notes and checks are
 * db2.py's (derived from the files, every block closing exactly on the file size); a layout this reader does not know throws instead of
 * returning plausible garbage. Encrypted sections are skipped; array fields must be requested explicitly.
 */
class Db2(private val d: ByteArray, private val name: String = "db2") {
    val recordCount: Int
    val fieldCount: Int
    val recordSize: Int
    val idIndex: Int
    val layoutHash: Int
    private val sections = ArrayList<Section>()
    private val storage = ArrayList<Storage>()
    private val pallet: ByteArray
    private val common = HashMap<Int, HashMap<Int, Int>>()
    private val palletOffsets: IntArray

    private class Section(val tact: Long, val fileOffset: Int, val recordCount: Int, val stringTableSize: Int, val idListSize: Int,
                          val relationshipDataSize: Int, val offsetMapIdCount: Int, val copyTableCount: Int)
    private class Storage(val offsetBits: Int, val sizeBits: Int, val additional: Int, val type: Int, val c0: Int, val arrayCount: Int)

    /** one readable record: its id, its field values (strings decoded for the asked fields), its parent id from the relationship block */
    class Row(val id: Int, val values: Array<Any>, val parent: Int?) {
        fun int(f: Int): Int = (values[f] as? Int) ?: 0
        fun str(f: Int): String = (values[f] as? String) ?: ""
        fun ints(f: Int): IntArray = values[f] as? IntArray ?: error("field $f is not an array")
    }

    init {
        require(d.size >= 0xCC && d[0] == 'W'.code.toByte() && d[1] == 'D'.code.toByte() && d[2] == 'C'.code.toByte() && d[3] == '5'.code.toByte()) {
            "$name: not WDC5"
        }
        var o = 0x88
        recordCount = u32(o); fieldCount = u32(o + 4); recordSize = u32(o + 8)
        layoutHash = u32(o + 20)
        o += 36
        idIndex = u16(o + 2); o += 4
        val totalFieldCount = u32(o); val fieldStorageInfoSize = u32(o + 12); val commonDataSize = u32(o + 16)
        val palletDataSize = u32(o + 20); val sectionCount = u32(o + 24)
        o += 28
        repeat(sectionCount) {
            sections.add(Section(u64(o), u32(o + 8), u32(o + 12), u32(o + 16), u32(o + 24), u32(o + 28), u32(o + 32), u32(o + 36)))
            o += 40
        }
        o += fieldCount * 4
        check(fieldStorageInfoSize == totalFieldCount * 24) { "$name: storage_info $fieldStorageInfoSize != $totalFieldCount*24" }
        repeat(totalFieldCount) { i ->
            val b = o + i * 24
            storage.add(Storage(u16(b), u16(b + 2), u32(b + 4), u32(b + 8), u32(b + 12), u32(b + 20)))
        }
        o += fieldStorageInfoSize
        pallet = d.copyOfRange(o, o + palletDataSize); o += palletDataSize
        val commonRaw = d.copyOfRange(o, o + commonDataSize); o += commonDataSize
        // encrypted sections park their id lists here, before section 0's data (db2.py)
        for (s in sections) if (s.tact != 0L && s.idListSize != 0) {
            val cnt = u32(o); o += 4
            check(cnt * 4 == s.idListSize) { "$name: encrypted section id count $cnt != ${s.idListSize}" }
            o += s.idListSize
        }
        if (sections.isNotEmpty()) check(sections[0].fileOffset == o) { "$name: block end $o != section 0 offset ${sections[0].fileOffset}" }
        var p = 0
        for ((i, s) in storage.withIndex()) if (s.type == ST_COMMON && s.additional != 0) {
            val m = HashMap<Int, Int>()
            for (k in 0 until s.additional / 8) m[le32(commonRaw, p + k * 8)] = le32(commonRaw, p + k * 8 + 4)
            common[i] = m
            p += s.additional
        }
        palletOffsets = IntArray(storage.size)
        var off = 0
        for ((i, s) in storage.withIndex()) { palletOffsets[i] = off; if (s.type == ST_INDEXED || s.type == ST_INDEXED_ARRAY) off += s.additional }
    }

    /** every readable row; [strings] = the field indices to decode as strings */
    fun rows(strings: Set<Int> = emptySet(), arrays: Set<Int> = emptySet()): List<Row> {
        val out = ArrayList<Row>(recordCount)
        for (sec in sections) {
            if (sec.tact != 0L) continue                                     // encrypted: skipped (db2.py says so loudly on the PC)
            val n = sec.recordCount
            if (n == 0) continue
            var o = sec.fileOffset
            val recStart = o; o += n * recordSize
            val strStart = o; o += sec.stringTableSize
            var ids: IntArray? = null
            if (sec.idListSize != 0) {
                check(sec.idListSize == n * 4) { "$name: id list size mismatch" }
                ids = IntArray(n) { u32(o + it * 4) }
                o += sec.idListSize
            }
            o += sec.copyTableCount * 8
            o += sec.offsetMapIdCount * 6
            val parents = HashMap<Int, Int>()
            if (sec.relationshipDataSize != 0) {
                val cnt = u32(o)
                check(12 + cnt * 8 == sec.relationshipDataSize) { "$name: relationship block size mismatch" }
                for (k in 0 until cnt) parents[u32(o + 12 + k * 8 + 4)] = u32(o + 12 + k * 8)
                o += sec.relationshipDataSize
            }
            for (r in 0 until n) {
                val rec = recStart + r * recordSize
                val vals = Array<Any>(storage.size) { fi ->
                    val st = storage[fi]
                    if (fi in arrays) arrayValue(rec, fi, st)
                    else {
                        val v = value(rec, fi, st, r, ids)
                        if (fi in strings) string(strStart, sec.stringTableSize, v, r, st.offsetBits) else v
                    }
                }
                val rid = ids?.get(r) ?: (vals[idIndex] as Int)
                out.add(Row(rid, vals, parents[r]))
            }
        }
        return out
    }

    /**
     * The COPY TABLE of every readable section: a copied row's id -> the id of the row it copies ([rows] does not emit copies, as
     * db2.py). Map's world-map step needs it: Forever's UiMapArt rows are copies of the classic ones (54 rows + 90 copies on this
     * build), and a copied art's highlight picture is its source row's.
     */
    fun copies(): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for (sec in sections) {
            if (sec.tact != 0L || sec.copyTableCount == 0) continue
            val o = sec.fileOffset + sec.recordCount * recordSize + sec.stringTableSize + sec.idListSize
            for (k in 0 until sec.copyTableCount) out[u32(o + k * 8)] = u32(o + k * 8 + 4)
        }
        return out
    }

    /** Fixed-width byte records used by the client's TactKey and TactKeyLookup tables. */
    fun byteRecords(layout: Int, width: Int): Map<Int, ByteArray> {
        check(layoutHash == layout && fieldCount == 1 && storage.size == 1 && recordSize == width) {
            "$name: unsupported byte-record layout"
        }
        val field = storage.single()
        check(field.type == ST_NONE && field.offsetBits == 0 && field.sizeBits == width * 8) {
            "$name: unsupported byte-record field"
        }
        val out = LinkedHashMap<Int, ByteArray>()
        for (sec in sections) {
            if (sec.tact != 0L) continue
            check(sec.offsetMapIdCount == 0 && sec.idListSize == sec.recordCount * 4) { "$name: invalid byte-record IDs" }
            val ids = sec.fileOffset.toLong() + sec.recordCount.toLong() * width + sec.stringTableSize
            check(sec.fileOffset >= 0 && ids + sec.idListSize <= d.size) { "$name: truncated byte records" }
            for (r in 0 until sec.recordCount) {
                val start = sec.fileOffset + r * width
                val id = u32(ids.toInt() + r * 4)
                check(out.put(id, d.copyOfRange(start, start + width)) == null) { "$name: duplicate byte-record ID" }
            }
        }
        for ((id, source) in copies()) out[id] = out[source]?.copyOf() ?: error("$name: missing byte-record copy source")
        return out
    }

    private fun value(rec: Int, fi: Int, st: Storage, r: Int, ids: IntArray?): Int = when (st.type) {
        ST_NONE, ST_BITPACKED -> bits(rec, st.offsetBits, st.sizeBits).toInt()
        ST_SIGNED -> sign(bits(rec, st.offsetBits, st.sizeBits), st.sizeBits)
        ST_INDEXED, ST_INDEXED_ARRAY -> {
            val i = bits(rec, st.offsetBits, st.sizeBits).toInt()
            val off = palletOffsets[fi] + i * 4
            if (off + 4 <= pallet.size) le32(pallet, off) else i
        }
        ST_COMMON -> common[fi]?.get(ids?.get(r) ?: -1) ?: st.c0
        else -> 0
    }

    private fun arrayValue(rec: Int, fi: Int, st: Storage): IntArray {
        check(st.type == ST_INDEXED_ARRAY && st.arrayCount in 1..1024) { "$name: unsupported array field $fi" }
        val index = bits(rec, st.offsetBits, st.sizeBits)
        val start = index * st.arrayCount * 4
        check(start >= 0 && start + st.arrayCount * 4 <= st.additional) { "$name: array field $fi outside palette" }
        val offset = palletOffsets[fi] + start.toInt()
        check(offset + st.arrayCount * 4 <= pallet.size) { "$name: array palette truncated" }
        return IntArray(st.arrayCount) { le32(pallet, offset + it * 4) }
    }

    // a string field's value is relative to the field's own position, the base being the HEADER's record count (db2.py's derivation)
    private fun string(strStart: Int, strSize: Int, value: Int, rec: Int, fieldBitOff: Int): String {
        if (value == 0) return ""
        val idx = value.toLong() + rec.toLong() * recordSize + (fieldBitOff shr 3) - recordCount.toLong() * recordSize
        if (idx < 0 || idx >= strSize) return ""
        var end = strStart + idx.toInt()
        val start = end
        val limit = strStart + strSize
        while (end < limit && d[end] != 0.toByte()) end++
        return String(d, start, end - start, Charsets.UTF_8)
    }

    private fun bits(base: Int, bitOff: Int, bitLen: Int): Long {
        if (bitLen <= 0) return 0
        var v = 0L
        val byte = base + (bitOff shr 3)
        val shift = bitOff and 7
        val n = (shift + bitLen + 7) shr 3
        for (k in 0 until minOf(n, 8)) if (byte + k < d.size) v = v or ((d[byte + k].toLong() and 0xFF) shl (8 * k))
        v = v ushr shift
        return if (bitLen >= 64) v else v and ((1L shl bitLen) - 1)
    }

    private fun sign(v: Long, bits: Int): Int = (if (bits > 0 && (v and (1L shl (bits - 1))) != 0L) v - (1L shl bits) else v).toInt()
    private fun u16(o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(o: Int) = le32(d, o)
    private fun u64(o: Int): Long = (le32(d, o).toLong() and 0xFFFFFFFFL) or (le32(d, o + 4).toLong() shl 32)

    companion object {
        const val ST_NONE = 0; const val ST_BITPACKED = 1; const val ST_COMMON = 2; const val ST_INDEXED = 3
        const val ST_INDEXED_ARRAY = 4; const val ST_SIGNED = 5
        fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    }
}
