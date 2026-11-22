package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.RectF
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * SETTINGS > BOTTOM SCREEN'S PICTURES, the rules (the beautify design's section 3.1, critic C5): the crop cache stays within its bound,
 * goes at a running-low trim and when the tab goes out of view, a crop is never shrunk more than 2.2x into the well nor blown up past 1x
 * into the band; ONE crop is made per frame, the cursor's first; the model's tiles, lines and writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomTilesTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun forget() { BottomPictures.drop(); BottomPictures.stage = BottomPictures.FullStage }

    private fun bmp(w: Int = 216, h: Int = 108) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    /** the well's crops: about 2.5 MB at most; the least recently used goes first; the one just put stays */
    @Test fun cacheStaysWithinItsBound() {
        val c = BottomPictures.Cache(BottomPictures.SMALL_BUDGET)
        val one = bmp().byteCount.toLong()
        val fits = (BottomPictures.SMALL_BUDGET / one).toInt()
        assertTrue("11 tiles x 2 choices fit: $fits", fits >= 22)
        for (i in 0 until fits) { c.put("k$i", bmp()); assertTrue(c.bytes <= BottomPictures.SMALL_BUDGET) }
        c["k0"]                                                              // used again: kept over k1
        c.put("k$fits", bmp())
        assertTrue(c.bytes <= BottomPictures.SMALL_BUDGET)
        assertEquals(fits, c.size)
        assertTrue("the last one stays", "k$fits" in c.keys())
        assertTrue("k0 was used, kept", "k0" in c.keys())
        assertFalse("k1 the least recent, gone", "k1" in c.keys())
        // one bigger than the whole budget still goes in alone (the band's picture is never refused)
        val big = BottomPictures.Cache(one / 2)
        big.put("a", bmp()); assertEquals(1, big.size)
    }

    /** a running-low trim and the tab going out of view drop every crop */
    @Test fun trimAndHideDropEverything() {
        BottomPictures.small.put("a", bmp()); BottomPictures.large.put("b", bmp(438, 220))
        assertTrue(BottomPictures.small.bytes > 0 && BottomPictures.large.bytes > 0)
        // a light trim keeps them; running low lets them go (MemWatch's trimmer, as Android calls it)
        val trimmers = MemWatch::class.java.getDeclaredField("trimmers").apply { isAccessible = true }.get(MemWatch) as Map<*, *>
        @Suppress("UNCHECKED_CAST") val f = trimmers["bottomTiles"] as (Int) -> Long
        assertEquals(0L, f(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertTrue(BottomPictures.small.size == 1)
        assertTrue(f(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) > 0L)
        assertEquals(0L, BottomPictures.small.bytes + BottomPictures.large.bytes)
        // the last view out of sight drops them; one still in sight keeps them
        BottomPictures.small.put("a", bmp())
        BottomPictures.shown(1, true); BottomPictures.shown(2, true)
        BottomPictures.shown(1, false); assertEquals(1, BottomPictures.small.size)
        BottomPictures.shown(2, false); assertEquals(0, BottomPictures.small.size)
    }

    /** the 2.2x rule: the window both use shrinks at most 2.2x into the 216 x 108 well, and is never blown up past 1x into the band */
    @Test fun windowFollowsTheShrinkRule() {
        val w = BottomPictures.WIN_W; val h = BottomPictures.WIN_H
        assertTrue(BottomPictures.fits(w, h, BottomPictures.W, BottomPictures.H))
        assertTrue(BottomPictures.fits(w, h, BottomPictures.BIG_W, BottomPictures.BIG_H))
        assertTrue(BottomPictures.shrink(w, h, BottomPictures.W, BottomPictures.H) <= BottomPictures.MAX_SHRINK + 1e-3f)
        assertTrue("never blown up into the band", BottomPictures.shrink(w, h, BottomPictures.BIG_W, BottomPictures.BIG_H) >= 1f)
        // the whole Home (1240 wide) would be 5.7x: refused - those pictures are composed instead
        assertFalse(BottomPictures.fits(1240f, 620f, BottomPictures.W, BottomPictures.H))
        // a window narrower than the band would be blown up: refused
        assertFalse(BottomPictures.fits(300f, 150f, BottomPictures.BIG_W, BottomPictures.BIG_H))
    }

    /** ONE crop per frame, the most wanted (lowest priority number: the cursor's tile) first; no game yet = nothing waits */
    @Test fun oneCropPerFrameNearestFirst() {
        var renders = 0
        val frame = bmp(620, 504)
        BottomPictures.stage = object : BottomPictures.Stage {
            override fun frame(ctx: android.content.Context, look: HomeView.Companion.Look, fight: Boolean, toolbar: Boolean?): BottomPictures.Frame {
                renders++
                return BottomPictures.Frame(frame, 0.5f, mapOf("map" to RectF(625f, 235f, 1230f, 925f), "chat" to RectF(10f, 235f, 615f, 925f),
                    "controller" to RectF(0f, 0f, 1240f, 235f)))
            }
        }
        val look = HomeView.Companion.Look(HomeRest.DIAMONDS, true, true, true)
        val shot = { BottomPictures.Shot(look, false, null, { b: Map<String, RectF> -> b["map"] }, 216, 108) }
        assertNull(BottomPictures.crop("far", shot(), 5))
        assertNull(BottomPictures.crop("near", shot(), 1))
        assertEquals(2, BottomPictures.pendingCount())
        assertTrue("more wait", BottomPictures.pump(app))
        assertEquals(1, renders)
        assertTrue("the cursor's first", BottomPictures.small["near"] != null && BottomPictures.small["far"] == null)
        assertFalse(BottomPictures.pump(app))
        assertEquals(2, renders)
        assertEquals(216, BottomPictures.small["far"]!!.width)
        // no game document yet: the queue empties at once (the drawings stand in), nothing is asked again every frame
        BottomPictures.drop()
        BottomPictures.stage = object : BottomPictures.Stage {
            override fun frame(ctx: android.content.Context, look: HomeView.Companion.Look, fight: Boolean, toolbar: Boolean?): BottomPictures.Frame? = null
        }
        BottomPictures.crop("a", shot(), 1); BottomPictures.crop("b", shot(), 2)
        assertFalse(BottomPictures.pump(app))
        assertEquals(0, BottomPictures.pendingCount())
    }

    /** 1518 build 2: the XP bar on Home moved here from the room bar's edit-mode menu (gone: each room is an app): Show / Hide, this
     *  character's record, Top's eye badge, the picture dimmed while hidden */
    @Test fun theXpBarTile() {
        HomeSettings.init(app); Settings.init(app)
        HomeLayoutStore.set(HomeLayout())
        val env = BottomTiles.Env(null, null)
        assertEquals("XP bar", BottomTiles.NAMES[BottomTiles.XP])
        assertEquals(listOf("Show", "Hide"), BottomTiles.lines(BottomTiles.XP, env).single().words)
        assertEquals("Show", BottomTiles.value(BottomTiles.XP, env))
        assertEquals(GameUiTiles.Badge.ON, BottomTiles.badge(BottomTiles.XP, env))
        BottomTiles.press(BottomTiles.XP, BottomTiles.XP, 1, env) { }
        assertFalse(HomeSettings.layout.xpBar)
        assertEquals("Hide", BottomTiles.value(BottomTiles.XP, env))
        assertEquals(1, BottomTiles.lines(BottomTiles.XP, env).single().picked)
        assertTrue(BottomTiles.dimmed(BottomTiles.XP, env))
        BottomTiles.press(BottomTiles.XP, BottomTiles.XP, 0, env) { }
        assertTrue(HomeSettings.layout.xpBar)
        assertEquals("a Classic record's text again", HomeLayoutJson.json(HomeLayout()), HomeLayoutJson.json(HomeSettings.layout))
    }

    /** the model: Home's 8 (the toolbar's two only with the Spells add-on's word / the toolbar on), Map & chat's 3; lines that cannot act
     *  are not drawn; the writes are the rows' own setters */
    @Test fun theModel() {
        HomeSettings.init(app); Settings.init(app)
        val none = BottomTiles.Env(null, "Sample-Realm")
        assertEquals(listOf(BottomTiles.LAYOUT, BottomTiles.REST, BottomTiles.COMBAT, BottomTiles.SWING, BottomTiles.QUEST, BottomTiles.CLOCK, BottomTiles.CASTBAR,
            BottomTiles.RANK, BottomTiles.XP, BottomTiles.HOME_RESET), BottomTiles.slots(SettingsTabs.Group.HOME, none))
        var want: Boolean? = null
        val d = { on: Boolean -> PadMapModel.Dpad(on, emptyMap(), emptyList(), emptyList(), on, 1, 2, null, null) }
        assertEquals(BottomTiles.HOME_TILES.size - 1, BottomTiles.slots(SettingsTabs.Group.HOME, BottomTiles.Env(d(false), "Sample-Realm")).size)
        val on = BottomTiles.Env(d(true), "Sample-Realm")
        assertEquals(BottomTiles.HOME_TILES, BottomTiles.slots(SettingsTabs.Group.HOME, on))
        assertEquals(BottomTiles.MAP_TILES, BottomTiles.slots(SettingsTabs.Group.MAP, on))
        assertTrue(BottomTiles.slots(SettingsTabs.Group.CAST_BAR, on).isEmpty() && BottomTiles.slots(SettingsTabs.Group.CHAT, on).isEmpty())
        // the toolbar's On / Off: the switch, only when it changes
        BottomTiles.press(BottomTiles.TOOLBAR, BottomTiles.TOOLBAR, 1, on) { want = it }
        assertEquals(false, want)
        want = null; BottomTiles.press(BottomTiles.TOOLBAR, BottomTiles.TOOLBAR, 0, on) { want = it }
        assertNull("already on: nothing sent", want)
        // hold to talk: On release only while On (per character)
        HomeSettings.setHoldTalk("Sample-Realm", false)
        assertEquals(1, BottomTiles.lines(BottomTiles.TALK, on).size)
        BottomTiles.press(BottomTiles.TALK, BottomTiles.TALK, 0, on) { }
        assertTrue(HomeSettings.holdTalk("Sample-Realm"))
        assertEquals(listOf(BottomTiles.TALK, "release"), BottomTiles.lines(BottomTiles.TALK, on).map { it.key })
        HomeSettings.setHoldTalk("Sample-Realm", false)
        // the cast bar: In combat only while the bar is on
        HomeSettings.castBarSide = CastStripModel.Side.OFF
        assertEquals(1, BottomTiles.lines(BottomTiles.CASTBAR, on).size)
        BottomTiles.press(BottomTiles.CASTBAR, BottomTiles.CASTBAR, 3, on) { }
        assertEquals(CastStripModel.Side.BOTH, HomeSettings.castBarSide)
        assertEquals("the place, its size (movable Home), Show in combat", 3, BottomTiles.lines(BottomTiles.CASTBAR, on).size)
        HomeSettings.castBarSide = CastStripModel.Side.OFF
        // the clock band: five On / Off lines with their icons
        assertEquals(5, BottomTiles.lines(BottomTiles.CLOCK, on).size)
        assertTrue(BottomTiles.lines(BottomTiles.CLOCK, on).all { it.icon != null && it.modes != null })
        BottomTiles.press(BottomTiles.CLOCK, "gold", 1, on) { }
        assertFalse(HomeSettings.clockGold)
        assertEquals("4 / 5", BottomTiles.value(BottomTiles.CLOCK, on))
        HomeSettings.clockGold = true
        // the minimap's steppers: one step
        val out = Settings.minimapYd
        BottomTiles.press(BottomTiles.ZOOM, "out", 1, on) { }
        assertEquals(Settings.stepYd(out, 1), Settings.minimapYd)
        Settings.minimapYd = out
        // Spell rank badges (2026-11-20; Off by default: SpellRanksTest): one On / Off line, the rank index's own switch
        HomeSettings.rankBadges = false
        assertEquals("Off", BottomTiles.value(BottomTiles.RANK, on))
        assertTrue(BottomTiles.dimmed(BottomTiles.RANK, on))
        assertEquals(listOf(BottomTiles.RANK), BottomTiles.lines(BottomTiles.RANK, on).map { it.key })
        BottomTiles.press(BottomTiles.RANK, BottomTiles.RANK, 0, on) { }
        assertTrue(HomeSettings.rankBadges)
        assertEquals("On", BottomTiles.value(BottomTiles.RANK, on))
        assertEquals(GameUiTiles.Badge.ON, BottomTiles.badge(BottomTiles.RANK, on))
        BottomTiles.press(BottomTiles.RANK, BottomTiles.RANK, 1, on) { }
        assertFalse(HomeSettings.rankBadges)
        // the badge: Top's eye on the On / Off tiles only
        assertTrue(BottomTiles.badge(BottomTiles.SWING, on) != null && BottomTiles.badge(BottomTiles.REST, on) == null)
    }

    /** the band's labels never cut short where two lines fit (the clock band's "Free bag slots"); Map's heading says what is in it */
    @Test fun labelsAndHeading() {
        val w = { t: String -> t.length * 10f }
        assertEquals(listOf("Free bag", "slots"), BottomTilesView.wrapWords("Free bag slots", 90f, w))
        assertEquals(listOf("Huecos", "libres en", "bolsas"), BottomTilesView.wrapWords("Huecos libres en bolsas", 90f, w))
        assertNull("one word wider than the room", BottomTilesView.wrapWords("Teletransporte", 90f, w))
        assertEquals("Map & chat", BottomTiles.heading(SettingsTabs.Group.MAP))
        assertEquals(SettingsTabs.Group.HOME.heading, BottomTiles.heading(SettingsTabs.Group.HOME))
    }
}
