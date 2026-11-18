package app.gamenative.ui.screen.wow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PR #256: a 16:9 profile is kept on a 16:9 panel (every Thor), refit on a 4:3 or 16:10 one, and junk passes through */
class PanelFitTest {
    @Test fun thorKeepsItsProfile() {
        assertEquals("1920x1080", PanelFit.fit("1920x1080", "1920x1080"))     // a Thor: getDefaultScreenSize's 16:9
        assertEquals("1280x720", PanelFit.fit("1280x720", "1920x1080"))       // the Lite / Adreno 8xx size, still 16:9
    }

    @Test fun otherShapesTakeThePanel() {
        assertEquals("1280x960", PanelFit.fit("1920x1080", "1280x960"))       // the Retroid Pocket Nova, 4:3
        assertEquals("1280x800", PanelFit.fit("1280x720", "1280x800"))        // 16:10
    }

    @Test fun junkPassesThrough() {
        assertEquals("1280x720", PanelFit.fit("1280x720", null))
        assertEquals("1280x720", PanelFit.fit("1280x720", "garbage"))
        assertEquals("1280x720", PanelFit.fit("1280x720", "0x960"))
        assertEquals("auto", PanelFit.fit("auto", "1280x960"))
    }

    @Test fun ratioIsLandscape() {
        assertEquals(16f / 9f, PanelFit.ratio("720X1280")!!, 1e-4f)
        assertNull(PanelFit.ratio("1280x720x2"))
        assertNull(PanelFit.ratio(" x 720"))
    }
}
