package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** issues #91 / #85 / #77 (2026-11-11): CharacterView.onTouchEvent threw NoSuchElementException from drags.last { } */
class TouchLastCrashTest {
    @Test fun theLastBoxDrawnForTheKeyWins() {
        val drags = listOf("a1" to "rep", "s1" to "skills", "a2" to "rep")
        assertEquals("a2", CharacterView.dragBox(drags, "rep"))
        assertEquals("s1", CharacterView.dragBox(drags, "skills"))
    }

    @Test fun aBoxGoneSinceTheDownEndsTheDrag() {
        // the down took "rep"; a redraw (a new tab) drew only "skills" before the move
        assertNull(CharacterView.dragBox(listOf("s1" to "skills"), "rep"))
        assertNull(CharacterView.dragBox(emptyList<Pair<String, String>>(), "rep"))   // the frame cleared, nothing drawn yet
        assertNull(CharacterView.dragBox(listOf("s1" to "skills"), null))
    }
}
