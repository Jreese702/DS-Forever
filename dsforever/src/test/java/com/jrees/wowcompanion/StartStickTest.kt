package com.jrees.wowcompanion

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class StartStickTest {
    private val L = KeyEvent.KEYCODE_DPAD_LEFT
    private val R = KeyEvent.KEYCODE_DPAD_RIGHT

    private fun run(vararg moves: FloatArray): List<Pair<Int, Boolean>> {
        val s = StartStick(); val out = ArrayList<Pair<Int, Boolean>>()
        for (m in moves) s.move(m[0], m[1], m[2], m[3]) { c, d -> out += c to d }
        return out
    }

    @Test fun onePressPerPushOfTheStick() {
        assertEquals(listOf(R to true, R to false, L to true),
            run(floatArrayOf(0f, 0f, 0.3f, 0f), floatArrayOf(0f, 0f, 0.9f, 0f), floatArrayOf(0f, 0f, 1f, 0f), floatArrayOf(0f, 0f, 0.5f, 0f),
                floatArrayOf(0f, 0f, 0.1f, 0f), floatArrayOf(0f, 0f, -0.9f, 0f)))
    }

    @Test fun theHatIsTheDpad() {
        assertEquals(listOf(L to true, L to false), run(floatArrayOf(-1f, 0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 0f)))
    }

    @Test fun aDiagonalIsItsStrongerAxis() {
        assertEquals(listOf(R to true), run(floatArrayOf(0f, 0f, 0.9f, 0.7f)))
    }
}
