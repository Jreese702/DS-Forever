package com.jrees.wowcompanion

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** issues #10 / #11 (1.0.9), off-device: when the art prefetches, a restart resumes, and the report's caret */
class Art109Test {

    @Test fun prefetchOnlyWhenManyReadsMiss() {
        val w = MissWindow()
        repeat(60) { w.note(false) }                       // a complete folder: never
        assertFalse(w.worthPrefetching())
        repeat(9) { w.note(true) }
        assertFalse(w.worthPrefetching())
        w.note(true)                                       // 10 of the last 50 from the CDN: after a game update
        assertTrue(w.worthPrefetching())
        repeat(50) { w.note(false) }                       // the misses roll out of the window
        assertFalse(w.worthPrefetching())
    }

    @Test fun aRestartResumesTheFinishedMaps() {
        val f = File(Files.createTempDirectory("art").toFile(), ".resume/worldmap-bk70450.txt")
        val first = ResumeList(f)
        first.markFinished("map 1423"); first.markFinished("map 1424"); first.markFinished("map 1423")
        val reopened = ResumeList(f)                       // the app reopened: Eastern Plaguelands is not made again
        assertTrue(reopened.finished("map 1423")); assertTrue(reopened.finished("map 1424")); assertFalse(reopened.finished("map 1425"))
        assertEquals(2, f.readLines().count { it.isNotBlank() })
    }

    private val m: (String) -> Float = { it.length * 10f }   // 10 px a character

    @Test fun theWordsWrapWithWhereEachLineStarts() {
        val lines = ReportPage.Model.wrapIndexed("bars went blank after a fight", 100f, m)
        assertEquals(listOf(0 to "bars went", 10 to "blank", 16 to "after a", 24 to "fight"), lines)
        for ((start, t) in lines) assertEquals(t, "bars went blank after a fight".substring(start, start + t.length))
        assertEquals(listOf(0 to ""), ReportPage.Model.wrapIndexed("", 100f, m))
    }

    @Test fun theCaretMovesAndTypingGoesWhereItIs() {
        val text = "bars went blank"
        val lines = ReportPage.Model.wrapIndexed(text, 100f, m)
        assertEquals(1, ReportPage.Model.caretLine(lines, 12))             // inside "blank"
        assertEquals(13, ReportPage.Model.caretAt(lines[1], 30f, m))       // a tap 3 characters into line 2
        // the typo "went" -> "were": caret after "we", the keyboard deletes two and types "re"
        var (w, c) = ReportPage.Model.edit(text, 9, "bars we", 2000)       // Back twice (the prefix lost "nt")
        assertEquals("bars we blank", w); assertEquals(7, c)
        val r = ReportPage.Model.edit(w, c, "bars were", 2000)
        assertEquals("bars were blank", r.first); assertEquals(9, r.second)
    }
}
