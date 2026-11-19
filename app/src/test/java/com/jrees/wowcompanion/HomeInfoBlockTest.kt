package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * THE HOME INFO BLOCK (feat/home-info-block; the user, 2026-11-19: "I have ammo turned on for my bottom bar but I dont see it at all when
 * im empty. I think that these (block, battery gold and menu choices) need to fill out that space and shrink when a cast bar pops up or
 * L+R. the bag and ammo ar confusing becaus they just state a number instread of a number out of total available").
 *  - the ammo shows at 0 (red), as carried / what the quiver or the bags can hold; the bags as used / total
 *  - the block grows into the empty column, small again (today's size) while a cast shows or LB + RB / ZL + ZR are held; nothing grown
 *    stands on a button or the cast bar's strip
 * Renders (when CLOCK_SHOTS_DIR is set): info_grown, info_small_lr, info_small_cast, info_mid, info_ammo_empty (+ _band 2x crops).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeInfoBlockTest {
    private val out: File? = System.getenv("CLOCK_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()

    // ------------------------------------------------------------------ the figures (pure)

    private fun bag(bag: Int, slots: Int, free: Int, fam: Int = 0, icon: Int = 0) =
        JSONObject().put("bag", bag).put("slots", slots).put("free", free).put("fam", fam).apply { if (icon > 0) put("icon", icon) }

    private fun doc(bags: List<JSONObject>, items: List<JSONObject> = emptyList(), ammo: JSONObject? = null, needed: Boolean? = null): JSONObject {
        val eq = JSONObject().put("slots", JSONArray())
        if (ammo != null) eq.put("ammo", ammo)
        if (needed != null) eq.put("ammoNeeded", needed)
        return JSONObject().put("bags", JSONArray(bags)).put("items", JSONArray(items)).put("equipped", eq)
            .put("freeGeneral", bags.filter { it.optInt("fam") == 0 }.sumOf { it.optInt("free") })
    }

    private fun arrows(count: Int, stack: Int? = 1000) = JSONObject().put("id", 2512).put("icon", 132382).put("count", count)
        .apply { if (stack != null) put("stack", stack) }

    private fun item(bag: Int, id: Int, count: Int) = JSONObject().put("bag", bag).put("slot", 1).put("id", id).put("count", count)

    /** THE BUG: empty ammo (the slot's count 0) used to give no cell at all (ClockBand.ammo: `if (n <= 0) return null`) */
    @Test fun zeroAmmoStillShows() {
        val d = doc(listOf(bag(0, 16, 1)), ammo = arrows(0), needed = true)
        val a = ClockBand.ammo(d)
        assertNotNull("empty ammo must still show", a)
        assertEquals(0, a!!.count)
        assertTrue(a.empty)
        // no quiver: what the plain bags could hold of it (1 free slot, none holding it) x the 1000 stack
        assertEquals(1000, a.of)
        assertEquals("0/1000", ClockBand.outOf(a.count, a.of))
    }

    /** the slot itself cleared when the last arrow went: still a 0 cell for a hunter (AmmoNeeded, a quiver or ammo seen before) */
    @Test fun clearedAmmoSlotStillShowsZero() {
        val needed = ClockBand.ammo(doc(listOf(bag(0, 16, 2)), needed = true))
        assertNotNull(needed); assertEquals(0, needed!!.count); assertEquals(VendorSlots.AMMO_ICON, needed.icon)
        val before = ClockBand.ammo(doc(listOf(bag(0, 16, 2)), listOf(item(0, 2512, 264)), ammo = arrows(264)))!!
        val after = ClockBand.ammo(doc(listOf(bag(0, 16, 3))), before)
        assertNotNull("ammo seen before: 0 shows when the slot clears", after)
        assertEquals(0, after!!.count)
        assertEquals(132382, after.icon)                                   // the arrows' own icon kept
        assertEquals(3 * 1000, after.of)                                    // the stack seen before x the 3 free slots
        val quiver = ClockBand.ammo(doc(listOf(bag(0, 16, 2), bag(1, 16, 16, fam = 1, icon = 134407))))
        assertNotNull(quiver); assertEquals(0, quiver!!.count); assertEquals(16 * VendorSlots.AMMO_STACK, quiver.of)
    }

    /** a character with no ammo use (no slot item, no quiver, AmmoNeeded false, nothing seen): no ammo cell */
    @Test fun noAmmoUserNoCell() {
        assertNull(ClockBand.ammo(doc(listOf(bag(0, 16, 2)), needed = false)))
        assertNull(ClockBand.ammo(doc(listOf(bag(0, 16, 2)))))
        // a bags document without `equipped` keeps what was said
        val a = ClockBand.Ammo(132382, 5, 1000, 1000, 2512)
        assertEquals(a, ClockBand.ammo(JSONObject().put("bags", JSONArray()), a))
    }

    /** ammo capacity: a quiver = its slots x the stack; no quiver = (free plain slots + slots holding it) x the stack */
    @Test fun ammoCapacity() {
        // a 16-slot quiver (fam 1), the 200 Classic stack said by the add-on: 264 / 3200
        val q = ClockBand.ammo(doc(listOf(bag(0, 16, 4), bag(4, 16, 14, fam = 1)), listOf(item(4, 2512, 200), item(4, 2512, 64)),
            ammo = arrows(264, stack = 200)))!!
        assertEquals(264, q.count); assertEquals(3200, q.of); assertEquals("264/3200", ClockBand.outOf(q.count, q.of))
        // no quiver: 4 free + 1 slot holding the arrows, stack 1000 = 5000; profession / ammo bags never count as room
        val p = ClockBand.ammo(doc(listOf(bag(0, 16, 4), bag(1, 24, 10, fam = 32)), listOf(item(0, 2512, 264)), ammo = arrows(264)))!!
        assertEquals(264, p.count); assertEquals(5000, p.of)
        // the stack unread: the biggest stack seen, never under the Classic 200
        val u = ClockBand.ammo(doc(listOf(bag(0, 16, 1)), listOf(item(0, 2512, 150)), ammo = arrows(150, stack = null)))!!
        assertEquals(2 * VendorSlots.AMMO_STACK, u.of)
        // the count unsaid: the stacks of it in the bags
        val c = ClockBand.ammo(doc(listOf(bag(0, 16, 1)), listOf(item(0, 2512, 1000), item(0, 2512, 30)),
            ammo = JSONObject().put("id", 2512).put("icon", 132382).put("stack", 1000)))!!
        assertEquals(1030, c.count)
    }

    /** the bags: USED / total of bags 0..4, profession bags too, never a quiver / ammo pouch or the reagent bag */
    @Test fun bagsUsedOfTotal() {
        val b = ClockBand.bags(doc(listOf(bag(0, 16, 4), bag(1, 20, 0), bag(2, 24, 10, fam = 32), bag(3, 16, 16, fam = 1),
            bag(5, 36, 36).put("reagent", true))))!!
        assertEquals(60, b.total); assertEquals(12 + 20 + 14, b.used)
        assertEquals("46/60", ClockBand.outOf(b.used, b.total))
        assertFalse(b.full)
        assertTrue(ClockBand.bags(doc(listOf(bag(0, 16, 0), bag(1, 20, 0))))!!.full)
        // no bag rows: the document's own total / free
        assertEquals(ClockBand.Bags(68, 80), ClockBand.bags(JSONObject().put("total", 80).put("free", 12)))
        assertNull(ClockBand.bags(JSONObject()))
    }

    @Test fun countFormats() {
        assertEquals("68/80", ClockBand.outOf(68, 80))
        assertEquals("0/1000", ClockBand.outOf(0, 1000))
        assertEquals("264/1000", ClockBand.outOf(264, 1000))
        assertEquals("12.3k/24k", ClockBand.outOf(12_345, 24_000))
        assertEquals("264", ClockBand.outOf(264, 0))                      // the room unknown: the figure alone
    }

    @Test fun growStepEndsAndTurnsBack() {
        assertEquals(1f, ClockBand.growStep(0f, true, ClockBand.GROW_MS_IN.toLong()), 0f)
        assertEquals(0f, ClockBand.growStep(1f, false, ClockBand.GROW_MS_OUT.toLong()), 0f)
        assertEquals(0.5f, ClockBand.growStep(1f, false, (ClockBand.GROW_MS_OUT / 2).toLong()), 0.001f)
        assertEquals(0.75f, ClockBand.growStep(0.5f, true, (ClockBand.GROW_MS_IN / 2).toLong()), 0.001f)
    }

    // ------------------------------------------------------------------ Home's page

    private fun artRoot() {
        val root = System.getenv("CLOCK_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun setField(o: Any, name: String, v: Any) { val f = o.javaClass.getDeclaredField(name); f.isAccessible = true; f.set(o, v) }

    private class Page(val home: HomeView, val cast: CastView)

    private fun page(): Page {
        HomeSettings.init(app)
        HomeSettings.clockHearth = true; HomeSettings.clockTeleport = true; HomeSettings.clockGold = true
        HomeSettings.clockBags = true; HomeSettings.clockAmmo = true
        ItemCd.reset()
        ItemCd.onDoc(JSONObject("""{"type":"itemcd","t":{"6948":{"d":1800,"l":754}},"hs":{"have":true,"i":134414},"tp":{"id":556,"n":"Astral Recall","i":136010}}"""))
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        val h = 1080 - Strip.HEIGHT
        lay(home, 1240, h)
        val icons = listOf(136048, 136026, 135990, 136052, 135813, 136015, 135824, 136075)
        val n = JSONArray()
        for (p in 1..8) n.put(JSONObject().put("p", p).put("k", "spell").put("id", 400 + p).put("i", icons[p - 1]))
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", n))))
        home.tick()
        val look = HomeView.Companion.Look(HomeRest.DIAMONDS, false, false, false)
        HomeView::class.java.methods.firstOrNull { it.name.startsWith("previewSettle") }?.invoke(home, look)
        lay(home, 1240, h)
        return Page(home, cast)
    }

    /** the bags: 68 of 80 used; the arrows [ammo] of what the bags hold (12 free slots + the one holding them, stack 1000) */
    private fun feed(p: Page, ammo: Int) {
        val eq = JSONObject().put("slots", JSONArray()).put("ammoNeeded", true)
            .put("ammo", JSONObject().put("id", 2512).put("icon", 132382).put("count", ammo).put("stack", 1000))
        val bags = JSONArray().put(bag(0, 16, 1)).put(bag(1, 16, 0)).put(bag(2, 16, 0)).put(bag(3, 16, 11)).put(bag(4, 16, 0))
        val items = JSONArray(); if (ammo > 0) items.put(item(1, 2512, ammo))
        p.home.onFull(JSONObject().put("type", "full")
            .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true)))
            .put("bags", JSONObject().put("money", 1_234_567L).put("freeGeneral", 12).put("free", 12).put("total", 80)
                .put("bags", bags).put("items", items).put("equipped", eq)), true)
        setField(p.home, "clockText", "10:42 AM"); setField(p.home, "batteryPct", 76); setField(p.home, "charging", false)
    }

    private fun onPad(p: Page, lt: Boolean, rt: Boolean, lb: Boolean, rb: Boolean) {
        val m = CastView::class.java.getDeclaredMethod("onPad", Boolean::class.java, Boolean::class.java, Boolean::class.java, Boolean::class.java)
        m.isAccessible = true
        m.invoke(p.cast, lt, rt, lb, rb)
    }

    /** a cast running now (the actionbar's hudS.cs, as the game sends it) */
    private fun cast(p: Page) {
        val g = 5000.0
        val ab = JSONObject().put("hud", JSONObject().put("ok", true)).put("hudS", JSONObject().put("g", g).put("c", false)
            .put("cs", JSONObject().put("k", "c").put("s", g - 1.0).put("e", g + 2.0).put("nm", "Disenchant")))
        p.home.onFull(JSONObject().put("type", "full").put("actionbar", ab), true)
        p.cast.update(ab)
        p.home.tick(); ShadowLooper.idleMainLooper()
        setField(p.home, "clockText", "10:42 AM")                              // tick read the device's own clock
    }

    /** every drawText, with the canvas's x scale at the time and the paint's colour */
    private class Rec(b: Bitmap) : Canvas(b) {
        class T(val text: String, val x: Float, val y: Float, val size: Float, val scale: Float, val color: Int)
        val texts = ArrayList<T>()
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            val v = FloatArray(9); @Suppress("DEPRECATION") matrix.getValues(v)
            texts += T(text, x, y, paint.textSize, v[0], paint.color); super.drawText(text, x, y, paint)
        }
    }

    private fun record(p: Page): Rec {
        lay(p.home, 1240, 1080 - Strip.HEIGHT)
        val rec = Rec(Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888))
        p.home.draw(rec)
        return rec
    }

    private fun bars(p: Page): RectF {
        val f = HomeView::class.java.getDeclaredField("now"); f.isAccessible = true
        @Suppress("UNCHECKED_CAST") return (f.get(p.home) as Map<Any, RectF>).entries.first { it.key.toString() == "BARS" }.value
    }

    private fun spot(p: Page): FloatArray {
        val b = bars(p)
        return p.cast.clockSpot()!!.also { s -> for (i in 0 until s.size - 1) s[i] += if (i % 2 == 0) b.left else b.top }
    }

    /** the time's on-screen size (its paint size x the canvas's scale) */
    private fun timeSize(r: Rec): Float = r.texts.first { it.text == "10:42 AM" }.let { it.size * it.scale }

    private fun settle(ms: Long = 400) { ShadowSystemClock.advanceBy(Duration.ofMillis(ms)); ShadowLooper.idleMainLooper() }

    /** the figures on Home: "68/80" bags, "0/12k" ammo in red (the old row had no ammo cell at 0) */
    @Test fun homeShowsOutOfFiguresAndZeroAmmo() {
        artRoot()
        val p = page(); feed(p, 0); settle()
        val r = record(p)
        val bagsT = r.texts.firstOrNull { it.text == "68/80" }
        assertNotNull("the bags as used / total", bagsT)
        val ammoT = r.texts.firstOrNull { it.text == "0/12k" }
        assertNotNull("the empty ammo still shows, out of its room", ammoT)
        assertEquals("0 ammo in the out red", Color.rgb(210, 50, 40), ammoT!!.color or (0xFF shl 24))
        val p2 = page(); feed(p2, 264); settle()
        val r2 = record(p2)
        // 264 arrows in one slot + 12 free slots, stack 1000
        assertNotNull(r2.texts.firstOrNull { it.text == "264/13k" })
        ItemCd.reset()
    }

    /** grown at rest; small (today's sizes exactly) while LB + RB are held, while ZL + ZR are, and while a cast shows; back after */
    @Test fun growsAtRestShrinksForCastAndLR() {
        artRoot()
        val p = page(); feed(p, 264); settle()
        val grown = timeSize(record(p))
        assertTrue("the time grows (was 32 px): $grown", grown > 32f * 1.15f)
        // LB + RB: small, today's 32 px time
        onPad(p, false, false, true, true); settle()
        assertEquals(32f, timeSize(record(p)), 0.01f)
        onPad(p, false, false, false, false); settle()
        assertEquals(grown, timeSize(record(p)), 0.01f)
        // ZL + ZR (their join in the middle)
        onPad(p, true, true, false, false); settle()
        assertEquals(32f, timeSize(record(p)), 0.01f)
        onPad(p, false, false, false, false); settle()
        assertEquals(grown, timeSize(record(p)), 0.01f)
        // half way through the grow: in between
        onPad(p, false, false, true, true); settle()
        onPad(p, false, false, false, false); ShadowSystemClock.advanceBy(Duration.ofMillis((ClockBand.GROW_MS_IN / 2).toLong()))
        val mid = timeSize(record(p))
        assertTrue("mid-grow $mid between 32 and $grown", mid > 32f + 0.5f && mid < grown - 0.5f)
        settle()
        // a cast: small while it shows
        cast(p); settle()
        assertTrue(p.cast.infoShrink())
        assertEquals(32f, timeSize(record(p)), 0.01f)
        ItemCd.reset()
    }

    /** nothing grown stands on a button or under the bottom (the cast bar's strip): the chip's box and every cell's box clear */
    @Test fun grownBlockClearsButtonsAndCastBar() {
        artRoot()
        for (ammo in listOf(0, 264)) for (gold in listOf(true, false)) {
            val p = page(); HomeSettings.clockGold = gold; feed(p, ammo); settle()
            val s = spot(p); val b = bars(p)
            val items = bandItemsOf(p)
            assertEquals(4, items.size)
            val g = UiKit.clockGrown(s, b.top + ClockBand.CHIP_TOP, "10:42 AM", 76, if (gold) 1_234_567L else null, items)
            assertNotNull("ammo=$ammo gold=$gold: a grown layout", g)
            val cs = UiKit.clockChipSize("10:42 AM", 76, if (gold) 1_234_567L else null)
            val chip = RectF(s[4] - cs[0] * g!!.chip / 2f, b.top + ClockBand.CHIP_TOP, s[4] + cs[0] * g.chip / 2f,
                b.top + ClockBand.CHIP_TOP + cs[1] * g.chip)
            assertTrue("the chip grows: ${g.chip}", g.chip > 1.1f)
            assertTrue("the row grows: ${g.rowScale}", g.rowScale > 1.1f)
            val boxes = ArrayList<RectF>(); boxes += chip
            for ((i, it) in items.withIndex()) boxes += RectF(g.lefts[i], g.rowTop, g.lefts[i] + UiKit.clockCellGrown(it) * g.rowScale,
                g.rowTop + ClockBand.ICON * g.rowScale)
            for (box in boxes) {
                assertTrue("$box under the spot's bottom ${s[3]}", box.bottom <= s[3] + 0.01f)
                assertTrue("$box inside the column", box.left >= s[0] - 0.01f && box.right <= s[2] + 0.01f)
                for (q in 0 until 8) {
                    val o = 6 + 4 * q
                    val btn = RectF(s[o], s[o + 1], s[o + 2], s[o + 3])
                    assertFalse("ammo=$ammo gold=$gold: $box on button $q $btn", RectF.intersects(box, btn))
                }
            }
            // the cells never overlap each other, nor the chip
            for (i in 1 until boxes.size) for (j in i + 1 until boxes.size) assertFalse(RectF.intersects(boxes[i], boxes[j]))
            for (i in 1 until boxes.size) assertTrue(boxes[i].top >= chip.bottom)
        }
        ItemCd.reset()
    }

    @Suppress("UNCHECKED_CAST")
    private fun bandItemsOf(p: Page): List<UiKit.BandItem> {
        val m = HomeView::class.java.getDeclaredMethod("bandItems"); m.isAccessible = true
        return m.invoke(p.home) as List<UiKit.BandItem>
    }

    // ------------------------------------------------------------------ the renders

    private fun draw(p: Page): Bitmap {
        val strip = Strip(app) { }
        val b = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        lay(p.home, 1240, 1080 - Strip.HEIGHT)
        val c = Canvas(b)
        c.drawColor(Theme.ink)
        p.home.draw(c)
        strip.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Strip.HEIGHT, View.MeasureSpec.EXACTLY))
        strip.layout(0, 0, 1240, Strip.HEIGHT)
        c.save(); c.translate(0f, (1080 - Strip.HEIGHT).toFloat()); strip.draw(c); c.restore()
        return b
    }

    private fun save(b: Bitmap, name: String) {
        val d = out ?: return
        d.mkdirs()
        File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val band = Bitmap.createBitmap(b, 0, 0, 1240, 260)
        Bitmap.createScaledBitmap(band, 2480, 520, true).let { s ->
            File(d, "${name}_band.png").outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun shots() {
        if (out == null) return
        artRoot()
        val p = page(); feed(p, 264); settle(); draw(p); settle()
        save(draw(p), "info_grown")
        onPad(p, false, false, true, true); settle(); save(draw(p), "info_small_lr")
        onPad(p, false, false, false, false); ShadowSystemClock.advanceBy(Duration.ofMillis((ClockBand.GROW_MS_IN / 2).toLong()))
        save(draw(p), "info_mid")
        settle()
        cast(p); settle(); save(draw(p), "info_small_cast")
        val e = page(); feed(e, 0); settle(); draw(e); settle()
        save(draw(e), "info_ammo_empty")
        HomeSettings.clockGold = false
        val n = page(); HomeSettings.clockGold = false; feed(n, 264); settle(); draw(n); settle()
        save(draw(n), "info_grown_gold_off")
        ItemCd.reset()
    }
}
