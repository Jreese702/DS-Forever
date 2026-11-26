package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A HOST THAT WENT IS LET GO (HostEnds; the Robolectric proof is HostsReleasedTest): what a host registered process-wide goes at its
 * end, newest first; a hook only while it is still this host's (a newer host's own is never touched, so a live host never misses a
 * notification); a removal added after the end runs at once; the registries hand back their removal.
 */
class HostEndsTest {
    object Hooks { @Volatile var h: (() -> Unit)? = null }

    @After fun clean() { Hooks.h = null; SingleScreen.reset() }

    @Test fun aHookGoesOnlyWhileItIsStillThisHostsOwn() {
        val old = HostEnds(); val new = HostEnds()
        val a: () -> Unit = {}; val b: () -> Unit = {}
        Hooks.h = a; old.own(Hooks::h, null)
        Hooks.h = b; new.own(Hooks::h, null)                 // the host made again sets its own before the old one's end
        old.end()
        assertSame("the newer host's hook stays", b, Hooks.h)
        new.end()
        assertNull("its own end takes it off", Hooks.h)
    }

    @Test fun everythingGoesAtTheEndNewestFirstAndOnce() {
        val e = HostEnds()
        val order = ArrayList<Int>()
        e.add { order += 1 }; e.add { order += 2 }; e.add { order += 3 }
        assertEquals(3, e.size)
        e.end()
        assertEquals(listOf(3, 2, 1), order)
        e.end()
        assertEquals("once", listOf(3, 2, 1), order)
        e.add { order += 4 }                                  // a view made after its host went: at once
        assertEquals(listOf(3, 2, 1, 4), order)
        assertTrue(e.ended)
    }

    @Test fun aRegistryHandsBackItsRemoval() {
        var heard = 0
        val e = HostEnds()
        e.add(SingleScreen.onChange { heard++ })
        SingleScreen.set(true, SingleScreen.Why.USER, at = 100L)
        assertEquals("a live host hears every change", 1, heard)
        e.end()
        SingleScreen.set(false, null, at = 200L)
        assertEquals("a host that went hears nothing more", 1, heard)
        SingleScreen.reset()
    }
}
