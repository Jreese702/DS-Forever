package com.jrees.wowcompanion

import kotlin.math.max

internal object IndoorFloorBlend {
    inline fun atHeight(z: Float, count: Int, zlo: (Int) -> Float, zhi: (Int) -> Float): FloatArray {
        if (!z.isFinite() || count == 0) return floatArrayOf(-1f, -1f, 0f)
        // Check the complete stair gap before choosing the nearest floor band.
        for (i in 0 until count - 1) {
            val lower = zhi(i)
            val upper = zlo(i + 1)
            if (upper > lower && z > lower && z < upper) {
                val t = (((z - lower) / (upper - lower) - 0.35f) / 0.4f).coerceIn(0f, 1f)
                return floatArrayOf(i.toFloat(), (i + 1).toFloat(), t * t * (3f - 2f * t))
            }
        }
        var best = 0
        var distance = Float.MAX_VALUE
        for (i in 0 until count) {
            val d = max(max(zlo(i) - z, z - zhi(i)), 0f)
            if (d < distance) { distance = d; best = i }
        }
        return floatArrayOf(best.toFloat(), best.toFloat(), 0f)
    }
}
