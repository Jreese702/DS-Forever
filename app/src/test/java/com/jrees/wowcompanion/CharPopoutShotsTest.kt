package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Looper
import android.view.MotionEvent
import android.view.View
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * char-popout, THE CHARACTER ROOM'S POP-OUT (the user, 2026-11-16: "in the charactor menu, rightr now its A to select and armor but just
 * highlighting it should show it and pressing a should open the pop out. if the only other option for something is nothing then I should
 * be able to select that to unequip. on weapons that are both 1 hand or can actually be swapped to the offhand or vice versa then that
 * option should be listed in the pop out options."), drawn off-device at 1240 x 1007 and driven by the pad's own presses and a finger:
 *  - the cursor moved onto a slot shows its piece at once (the middle card), no A; off the doll the card goes;
 *  - A on a slot opens the slide-out (Bags' X look); B closes it, a second B leaves the room (bLeadsHome);
 *  - a worn slot with nothing else still has its blank (the unequip), alone;
 *  - the main hand lists the off hand's one-hander first (a dual wielder), the off hand the main hand's, rings each other's;
 *  - a finger: a tap highlights and shows, a second tap opens, a long press opens (and its lift is not a tap);
 *  - char-popout-current (the user, 2026-11-16: "the cursor should be able to go back to the currently equipped item in the swap menu"):
 *    the worn piece is the rail's first tile, the cursor starts on it, leaves it and comes back; its card is the worn piece's; A keeps
 *    it (the rail goes in, nothing changes); a tap on it shows its card.
 * Pictures only when CHAR_POPOUT_SHOTS_DIR is set; CHAR_POPOUT_ART_ROOT = an art root laid out like the app's (ui/kit, fonts, icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CharPopoutShotsTest {
    private val out: File? = System.getenv("CHAR_POPOUT_SHOTS_DIR")?.let { File(it) }

    /** the art root before ANY test draws (the kit's pictures are cached at the first draw) */
    @org.junit.Before fun artFirst() { artRoot() }

    private fun artRoot() {
        val root = System.getenv("CHAR_POPOUT_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun l(t: String, c: String = "ffffff") = JSONObject().put("t", t).put("c", c)
    private fun arr(vararg o: JSONObject) = JSONArray().also { a -> o.forEach { a.put(it) } }
    private fun stat(k: String, n: String, v: Int) = JSONObject().put("k", k).put("n", n).put("v", v)
    private val sta = "ITEM_MOD_STAMINA_SHORT"; private val str = "ITEM_MOD_STRENGTH_SHORT"; private val agi = "ITEM_MOD_AGILITY_SHORT"
    private val arm = "RESISTANCE0_NAME"

    private fun item(id: Int, name: String, equip: String, q: Int, ilvl: Int, icon: Int, stats: JSONArray) =
        JSONObject().put("id", id).put("name", name).put("equip", equip).put("quality", q).put("ilvl", ilvl).put("icon", icon)
            .put("count", 1).put("stats", stats)

    private fun worn(slot: Int, name: String, id: Int? = null, item: String? = null, icon: Int = 0, q: Int = 1, equip: String? = null,
                     stats: JSONArray? = null, tip: JSONArray? = null) =
        JSONObject().put("slot", slot).put("name", name).also { o ->
            if (id != null) { o.put("id", id).put("icon", icon).put("quality", q).put("dur", 100) }
            if (item != null) o.put("item", item)
            if (equip != null) o.put("equip", equip)
            if (stats != null) o.put("stats", stats)
            if (tip != null) o.put("tip", tip)
        }

    // the bags: two caps, two rings, a two-hander, a one-hander; nothing for the shoulders
    private val bagItems = listOf(
        Triple(0, 1, item(3101, "Stonemason Cowl", "INVTYPE_HEAD", 2, 18, 133071, arr(stat(arm, "Armor", 41), stat(sta, "Stamina", 4), stat(str, "Strength", 3)))),
        Triple(0, 2, item(3102, "Ragged Leather Cap", "INVTYPE_HEAD", 1, 9, 133117, arr(stat(arm, "Armor", 12)))),
        Triple(0, 3, item(3201, "Ring of the Copper Vein", "INVTYPE_FINGER", 2, 15, 133344, arr(stat(sta, "Stamina", 3), stat(agi, "Agility", 2)))),
        Triple(0, 6, item(3301, "Heavy Ironwood Maul", "INVTYPE_2HWEAPON", 3, 20, 133052, arr(stat(str, "Strength", 8), stat(sta, "Stamina", 5)))),
        Triple(0, 7, item(3302, "Militia Hammer", "INVTYPE_WEAPON", 1, 8, 133476, JSONArray())),
    )

    /** the worn gear; [offWeapon] = a one-hander in the off hand (else a buckler); [ring2] = a ring on Finger 2 too */
    private fun equipped(offWeapon: Boolean, ring2: Boolean): JSONObject {
        val slots = JSONArray()
            .put(worn(1, "Head", 900, "Tattered Hood", 133117, 1, "INVTYPE_HEAD", arr(stat(arm, "Armor", 8))))
            .put(worn(2, "Neck"))
            .put(worn(3, "Shoulder", 901, "Rough Pauldrons", 135036, 1, "INVTYPE_SHOULDER", arr(stat(arm, "Armor", 14)),
                arr(l("Rough Pauldrons"), l("Soulbound"), l("Shoulder", "ffffff"), l("14 Armor"), l("Durability 35 / 35"))))
            .put(worn(4, "Shirt"))
            .put(worn(5, "Chest", 903, "Tattered Cloth Vest", 135009, 1, "INVTYPE_CHEST", arr(stat(arm, "Armor", 12), stat(sta, "Stamina", 1)),
                arr(l("Tattered Cloth Vest"), l("Soulbound"), l("Chest"), l("12 Armor"), l("+1 Stamina"), l("Durability 50 / 50"))))
            .put(worn(6, "Waist", 904, "Frayed Belt", 132491, 1, "INVTYPE_WAIST", arr(stat(arm, "Armor", 4))))
            .put(worn(7, "Legs", 905, "Rough Leggings", 134582, 1, "INVTYPE_LEGS", arr(stat(arm, "Armor", 9))))
            .put(worn(8, "Feet", 906, "Worn Boots", 132535, 1, "INVTYPE_FEET", arr(stat(arm, "Armor", 6))))
            .put(worn(9, "Wrist"))
            .put(worn(10, "Hands", 907, "Ragged Gloves", 132939, 1, "INVTYPE_HAND", arr(stat(arm, "Armor", 3))))
            .put(worn(11, "Finger 1", 908, "Band of Thorns", 133347, 2, "INVTYPE_FINGER", arr(stat(sta, "Stamina", 2)),
                arr(l("Band of Thorns", "1eff00"), l("Soulbound"), l("Finger"), l("+2 Stamina"))))
            .put(if (ring2) worn(12, "Finger 2", 914, "Glinting Ring", 133348, 2, "INVTYPE_FINGER", arr(stat(agi, "Agility", 3)),
                arr(l("Glinting Ring", "1eff00"), l("Soulbound"), l("Finger"), l("+3 Agility"))) else worn(12, "Finger 2"))
            .put(worn(13, "Trinket 1"))
            .put(worn(14, "Trinket 2"))
            .put(worn(15, "Back", 910, "Frayed Cloak", 133762, 1, "INVTYPE_CLOAK", arr(stat(arm, "Armor", 5))))
            .put(worn(16, "Main Hand", 911, "Worn Shortsword", 135274, 1, "INVTYPE_WEAPON", JSONArray(),
                arr(l("Worn Shortsword"), l("Soulbound"), l("One-Hand"), l("1 - 3 Damage"), l("(1.0 damage per second)"))))
            .put(if (offWeapon) worn(17, "Off Hand", 915, "Kobold Fang", 135641, 2, "INVTYPE_WEAPON", arr(stat(agi, "Agility", 2)),
                arr(l("Kobold Fang", "1eff00"), l("Soulbound"), l("One-Hand"), l("2 - 5 Damage"), l("(2.1 damage per second)"), l("+2 Agility")))
                else worn(17, "Off Hand", 912, "Battered Buckler", 134955, 1, "INVTYPE_SHIELD", arr(stat(arm, "Armor", 57)),
                arr(l("Battered Buckler"), l("Soulbound"), l("Off Hand"), l("57 Armor"))))
            .put(worn(18, "Ranged", 913, "Short Bow", 135489, 1, "INVTYPE_RANGED"))
            .put(worn(19, "Tabard"))
        return JSONObject().put("slots", slots).put("rangedShown", true).put("dw", true)
    }

    private fun bagsDoc(free: Int, offWeapon: Boolean, ring2: Boolean) = JSONObject().put("freeGeneral", free).put("equipped", equipped(offWeapon, ring2))
        .put("pad", JSONObject().put("rows", JSONObject().put("bag0", 30).put("bag1", 31).put("bagsort", 6).put("bmvq", 7).put("bmv", 55).put("auracancel", 29))
            .put("sizes", JSONObject().put("bagsort", 40)))

    private fun charDoc() = JSONObject().put("type", "character").put("combat", false).put("c", JSONObject()
        .put("eqs", JSONObject().put("row", "auracancel").put("yes", 3).put("no", 4).put("uq", 5).put("mon", 6).put("moff", 7).put("msb", 8)
            .put("wb", 9).put("ba", 10).put("bb", 11).put("ws", 12).put("ask", false)))

    private fun get(v: CharacterView, name: String): Any? =
        CharacterView::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(v)
    private fun set(v: CharacterView, name: String, value: Any?) {
        CharacterView::class.java.getDeclaredField(name).also { it.isAccessible = true }.set(v, value)
    }
    private fun press(v: CharacterView, key: String) {
        CharacterView::class.java.getDeclaredMethod("press", String::class.java).also { it.isAccessible = true }.invoke(v, key)
        frame(v)
    }

    private fun room(offWeapon: Boolean = false, ring2: Boolean = false, free: Int = 3): CharacterView {
        val v = CharacterView(RuntimeEnvironment.getApplication())
        v.bagItems = { bagItems }
        v.kits = { emptyList() }
        v.updateBags(bagsDoc(free, offWeapon, ring2))
        v.update(charDoc())
        set(v, "active", true)                    // the room holds the controller (its cursor is drawn)
        frame(v)
        return v
    }

    private fun frame(v: View) {
        val w = 1240; val h = 1007
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            v.draw(Canvas(b))
            ShadowSystemClock.advanceBy(Duration.ofMillis(400L))
            ShadowLooper.idleMainLooper()
        }
    }

    private fun shot(name: String, v: View) {
        val w = 1240; val h = 1007
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (pass in 0..3) {
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            val c = Canvas(b)
            c.drawColor(Theme.ink)
            v.draw(c)
            if (pass < 3) ShadowSystemClock.advanceBy(Duration.ofMillis(400L))
            ShadowLooper.idleMainLooper()
            Thread.sleep(120)                                                 // icons decode off the UI thread
            ShadowLooper.idleMainLooper()
        }
        val dir = out ?: return
        dir.mkdirs()
        File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** the box the doll drew for [slot] */
    private fun box(v: CharacterView, slot: Int): RectF {
        for (f in get(v, "focusables") as List<*>) {
            val k = f!!.javaClass.getDeclaredField("key").also { it.isAccessible = true }.get(f) as String
            if (k == "slot:$slot") return f.javaClass.getDeclaredField("r").also { it.isAccessible = true }.get(f) as RectF
        }
        throw AssertionError("no slot $slot drawn")
    }

    /** the cursor onto [slot] by the D-pad from the head (the left column: head, neck, shoulder, back, chest ...) */
    private fun cursorTo(v: CharacterView, slot: Int) {
        set(v, "focusKey", "slot:1"); set(v, "chosenSlot", 1); frame(v)
        val path = mapOf(1 to emptyList(), 3 to listOf("PADDDOWN", "PADDDOWN"), 5 to listOf("PADDDOWN", "PADDDOWN", "PADDDOWN", "PADDDOWN"),
            10 to listOf("PADDRIGHT"))
        val keys = path[slot]
        if (keys != null) { for (k in keys) press(v, k); return }
        set(v, "focusKey", "slot:$slot"); set(v, "chosenSlot", slot); frame(v)
    }

    @Suppress("UNCHECKED_CAST")
    private fun entries(v: CharacterView): List<SlideOut.Entry> {
        val slot = get(v, "railFor") as Int
        val m = CharacterView::class.java.getDeclaredMethod("railEntries", Int::class.javaPrimitiveType).also { it.isAccessible = true }
        return m.invoke(v, slot) as List<SlideOut.Entry>
    }

    private fun touch(v: CharacterView, x: Float, y: Float, holdMs: Long = 50L) {
        val t = android.os.SystemClock.uptimeMillis()
        v.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(holdMs))
        v.onTouchEvent(MotionEvent.obtain(t, t + holdMs, MotionEvent.ACTION_UP, x, y, 0))
        frame(v)
    }

    @Test fun shots() {
        artRoot()
        Tr.init(RuntimeEnvironment.getApplication())
        // 1. the cursor ONTO the chest: its card shows, no A
        room().let { v ->
            cursorTo(v, 5)
            assertEquals("the cursor on the chest shows it", 5, get(v, "chosenSlot"))
            assertNull("no pop-out yet", get(v, "railFor"))
            shot("doll_highlight_shows.png", v)
        }
        // 2. A on the head: the two caps and the blank
        room().let { v ->
            cursorTo(v, 1)
            assertEquals(1, get(v, "chosenSlot"))
            press(v, "PAD1")
            assertEquals("A opens the pop-out", 1, get(v, "railFor"))
            val e = entries(v)
            assertEquals(4, e.size); assertTrue("the worn cap first", e[0].current); assertTrue("the blank last", e.last().blank)
            shot("popout_options_blank.png", v)
        }
        // 3. the shoulders: nothing in the bags fits; the blank alone (the user: "I should be able to select that to unequip")
        room().let { v ->
            cursorTo(v, 3)
            press(v, "PAD1")
            assertEquals(3, get(v, "railFor"))
            val e = entries(v)
            assertEquals("the worn piece and the blank", 2, e.size); assertTrue(e[0].current); assertTrue(e[1].blank)
            shot("popout_nothing_only.png", v)
        }
        // 4. the main hand with a one-hander in the off hand: that one first, then the bags' weapons, then the blank
        room(offWeapon = true).let { v ->
            cursorTo(v, 16)
            press(v, "PAD1")
            assertEquals(16, get(v, "railFor"))
            val e = entries(v)
            assertTrue(e[0].current)
            assertTrue("the off hand's weapon next", e[1].swap); assertEquals(17, e[1].from)
            assertEquals(5, e.size); assertTrue(e.last().blank)
            shot("popout_main_lists_offhand.png", v)
        }
        // 5. the off hand (a buckler) of a dual wielder: the main hand's one-hander first
        room().let { v ->
            cursorTo(v, 17)
            press(v, "PAD1")
            val e = entries(v)
            assertTrue(e[0].current); assertTrue(e[1].swap); assertEquals(16, e[1].from)
            shot("popout_off_lists_mainhand.png", v)
        }
        // 6. Finger 2 with both rings worn: Finger 1's ring first
        room(ring2 = true).let { v ->
            cursorTo(v, 12)
            press(v, "PAD1")
            val e = entries(v)
            assertTrue(e[0].current); assertTrue(e[1].swap); assertEquals(11, e[1].from); assertTrue(e.last().blank)
            shot("popout_ring_swap.png", v)
        }
    }

    @Test fun aOpensBClosesBAgainLeaves() {
        Tr.init(RuntimeEnvironment.getApplication())
        val v = room()
        cursorTo(v, 5)
        assertTrue("nothing open: B leaves", v.bLeadsHome())
        press(v, "PAD1")
        assertEquals(5, get(v, "railFor"))
        assertFalse("the pop-out open: B is one step back", v.bLeadsHome())
        press(v, "PAD2")
        assertNull("B closed it", get(v, "railFor"))
        assertEquals("the chest still shown", 5, get(v, "chosenSlot"))
        assertTrue("a second B leaves the room", v.bLeadsHome())
        assertTrue("the room steps nothing back by touch now", !v.stepBackByTouch())
    }

    @Test fun theCardFollowsTheCursorOffTheDoll() {
        Tr.init(RuntimeEnvironment.getApplication())
        val v = room()
        cursorTo(v, 10)                                   // Hands: the right column's top
        assertEquals(10, get(v, "chosenSlot"))
        press(v, "PADDRIGHT")                             // off the doll, to the stats pane / its small tabs
        val k = get(v, "focusKey") as String?
        assertNotNull(k); assertFalse("off the doll ($k)", k!!.startsWith("slot:"))
        assertNull("the card goes with the cursor", get(v, "chosenSlot"))
    }

    @Test fun aFingerTapShowsASecondTapOpens() {
        Tr.init(RuntimeEnvironment.getApplication())
        val v = room()
        val b = box(v, 1)
        touch(v, b.centerX(), b.centerY())
        assertEquals("a tap highlights and shows", 1, get(v, "chosenSlot"))
        assertNull(get(v, "railFor"))
        touch(v, b.centerX(), b.centerY())
        assertEquals("a second tap opens", 1, get(v, "railFor"))
    }

    /** in a window: a detached view's postDelayed waits for an attach (the long press is a delayed post) */
    private fun attached(v: CharacterView): CharacterView {
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        act.setContentView(v, android.view.ViewGroup.LayoutParams(1240, 1007))
        frame(v)
        return v
    }

    @Test fun aLongPressOpensAndItsLiftIsNoTap() {
        Tr.init(RuntimeEnvironment.getApplication())
        val v = attached(room())
        val b = box(v, 5)
        touch(v, b.centerX(), b.centerY(), holdMs = 450L)
        assertEquals("a long press opens the pop-out", 5, get(v, "railFor"))
        assertEquals(5, get(v, "chosenSlot"))
        // a short press does not
        val w = attached(room())
        val c = box(w, 7)
        touch(w, c.centerX(), c.centerY(), holdMs = 100L)
        assertNull(get(w, "railFor")); assertEquals(7, get(w, "chosenSlot"))
    }

    // ---------------------------------------------------------------- char-popout-current: the worn piece

    @Suppress("UNCHECKED_CAST")
    private fun railHit(v: CharacterView, i: Int): RectF =
        (get(v, "railHits") as List<Pair<RectF, Int>>).first { it.second == i }.first

    @Test fun theCursorStartsOnTheWornPieceAndComesBack() {
        artRoot()
        Tr.init(RuntimeEnvironment.getApplication())
        val v = room()
        cursorTo(v, 1)
        press(v, "PAD1")
        assertEquals(1, get(v, "railFor"))
        assertEquals("the cursor starts on the worn cap", 0, get(v, "railSel"))
        assertTrue(entries(v)[0].current)
        shot("popout_worn_first.png", v)
        press(v, "PADDRIGHT")                            // the head's rail slides right: along it
        assertEquals("away to the first bag cap", 1, get(v, "railSel"))
        press(v, "PADDRIGHT")
        assertEquals(2, get(v, "railSel"))
        shot("popout_worn_away.png", v)
        press(v, "PADDLEFT"); press(v, "PADDLEFT")
        assertEquals("back on the worn cap", 0, get(v, "railSel"))
        assertEquals(1, get(v, "railFor"))
        shot("popout_worn_back.png", v)
        press(v, "PAD1")
        assertNull("A on the worn piece keeps it: the pop-out goes in", get(v, "railFor"))
        assertNull("nothing sent, nothing watched", get(v, "watch"))
        assertEquals("the head still worn", 900, (v.javaClass.getDeclaredMethod("worn").also { it.isAccessible = true }
            .invoke(v) as Map<*, *>).let { (it[1] as JSONObject).optInt("id") })
        assertEquals("the head still chosen", 1, get(v, "chosenSlot"))
    }

    @Test fun theWornPiecesTileOnTheWeaponsRail() {
        artRoot()
        Tr.init(RuntimeEnvironment.getApplication())
        val v = room(offWeapon = true)
        cursorTo(v, 16)
        press(v, "PAD1")
        assertEquals(0, get(v, "railSel")); assertTrue(entries(v)[0].current)
        shot("popout_worn_weapon.png", v)
    }

    @Test fun aTapOnTheWornTileShowsItsCard() {
        artRoot()
        Tr.init(RuntimeEnvironment.getApplication())
        val v = room()
        cursorTo(v, 1)
        press(v, "PAD1")
        press(v, "PADDRIGHT"); press(v, "PADDRIGHT")
        assertEquals(2, get(v, "railSel"))
        val r = railHit(v, 0)
        touch(v, r.centerX(), r.centerY())
        assertEquals("a tap on the worn tile shows its card", 0, get(v, "railSel"))
        assertEquals("the pop-out stays", 1, get(v, "railFor"))
        shot("popout_worn_tapped.png", v)
        val r2 = railHit(v, 0)
        touch(v, r2.centerX(), r2.centerY())
        assertNull("a second tap keeps it (A's Keep)", get(v, "railFor"))
    }
}
