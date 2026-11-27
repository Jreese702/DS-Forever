package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Issue 605 (1.0.39): after a report was sent, the next visit to the report page still showed the words sent last time. The page
 * kept its own words and its open() fell back to them when the kept draft was empty. A send empties the box; backing out without a
 * send still keeps the words as the draft; a send that fails puts them back as the draft.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ReportSentClearsTest {
    private fun words(v: ReportPage): String = ReportPage::class.java.getDeclaredField("words").apply { isAccessible = true }.get(v) as String

    private fun page(): Pair<ReportPage, MutableList<String>> {
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val v = ReportPage(act)
        act.setContentView(v, android.view.ViewGroup.LayoutParams(1240, 900))
        val sent = ArrayList<String>()
        v.onSend = { sent.add(it) }
        ReportFlow.init(act)
        return v to sent
    }

    @Test fun aSentReportLeavesTheBoxEmpty() {
        val (v, sent) = page()
        ReportFlow.description = "The map froze after the loading screen"
        v.open()
        assertEquals("The map froze after the loading screen", words(v))
        v.open(sendNow = true)                                             // Send report
        assertEquals(listOf("The map froze after the loading screen"), sent)
        ReportFlow.description = ""                                        // the host: the report was saved (sendReport's success)
        v.open()
        assertEquals("the next report starts empty", "", words(v))
        assertEquals("", ReportFlow.description)
        v.close()
        assertEquals("nothing sent comes back as a draft", "", ReportFlow.description)
    }

    @Test fun aSendClearsTheDraftAtOnceAndBackingOutStillKeepsOne() {
        val (v, _) = page()
        ReportFlow.description = "Bags froze"
        v.open()
        v.close()                                                          // backing out: the draft stays
        assertEquals("Bags froze", ReportFlow.description)
        v.open(sendNow = true)
        assertEquals("the sent words are no draft any more", "", ReportFlow.description)
        v.open()
        assertEquals("", words(v))
        v.close()
        ReportFlow.description = ""
    }
}
