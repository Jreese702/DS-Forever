package com.jrees.wowcompanion

import java.util.zip.Inflater
import kotlin.math.floor

/** Loader-compatible height field, with an optional explicit slot count for rebuilt cities. */
internal class IndoorGrid private constructor(
    val cell: Double, val nx: Int, val ny: Int, val lx0: Double, val ly0: Double,
    val zmin: Float, val zscale: Float, val slots: Int, val data: ByteArray
) {
    fun layersAt(lx: Double, ly: Double, out: FloatArray): Int {
        if (!lx.isFinite() || !ly.isFinite()) return 0
        val ix = floor((lx-lx0)/cell).toInt()
        val iy = floor((ly-ly0)/cell).toInt()
        if (ix !in 0 until nx || iy !in 0 until ny) return 0
        require(out.size >= slots)
        val base = (iy*nx+ix)*slots
        var n = 0
        for (s in 0 until slots) {
            val b = data[base+s].toInt() and 255
            if (b == 0) break
            out[n++] = zmin+(b-1)*zscale
        }
        return n
    }

    companion object {
        const val MAX_SLOTS = 16
        const val MAX_COMPRESSED_BYTES = 32*1024*1024
        private const val MAX_BYTES = 16*1024*1024

        fun decode(raw: ByteArray, cell: Double, nx: Int, ny: Int, lx0: Double, ly0: Double,
                   zmin: Float, zscale: Float, slots: Int = 4): IndoorGrid {
            require(nx > 0 && ny > 0 && slots in 4..MAX_SLOTS)
            require(cell.isFinite() && cell > 0 && lx0.isFinite() && ly0.isFinite())
            require(zmin.isFinite() && zscale.isFinite() && zscale > 0 && zscale <= 1.6f)
            val cells = nx.toLong()*ny
            require(cells <= MAX_BYTES.toLong()/slots && raw.size <= MAX_COMPRESSED_BYTES)
            val data = ByteArray((cells*slots).toInt())
            val inflater = Inflater()
            try {
                inflater.setInput(raw)
                var n = 0
                while (n < data.size && !inflater.finished()) {
                    val count = inflater.inflate(data, n, data.size-n)
                    require(count > 0)
                    n += count
                }
                // One extra byte forces checksum/end processing and detects output beyond the declared size.
                require(inflater.inflate(ByteArray(1)) == 0 && n == data.size && inflater.finished() && inflater.remaining == 0)
            } finally { inflater.end() }
            for (base in data.indices step slots) {
                var previous = 0
                var ended = false
                for (s in 0 until slots) {
                    val value = data[base+s].toInt() and 255
                    if (value == 0) ended = true else {
                        require(!ended && value > previous)
                        previous = value
                    }
                }
            }
            return IndoorGrid(cell, nx, ny, lx0, ly0, zmin, zscale, slots, data)
        }
    }
}
